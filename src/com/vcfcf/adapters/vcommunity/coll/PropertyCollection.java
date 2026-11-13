package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsMapCache;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.ops.PushSet;
import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.tls.CertificateState;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.MoRef;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.PropertyReader;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.RetrieveStats;
import com.vcfcf.adapters.vcommunity.vim.Returnvals;
import com.vcfcf.adapters.vcommunity.vim.VimReader;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;
import com.vcfcf.adapters.vcommunity.vim.VimXml;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * The property phase of a cycle (design 4.1 phases B and D, and 4.3 to 4.7):
 * reads the clusters, hosts, virtual machines, datastores, licenses and
 * vCenter settings from vim25 on a small pool, matches each object to its
 * Ops resource by vCenter UUID plus MoRef, and queues the keys in the cycle's
 * {@link PushSet}. SDK-free.
 *
 * <p>Honest by construction. A read that fails withholds its keys and is
 * counted in {@link Failures}; a read that fails half way through a type
 * discards what that type had so far; the objects of a kind whose Ops map
 * could not be read are held back; a value is never made up.
 *
 * <p>Holds the state that lives across cycles: the Ops maps, the certificate
 * state, the property paths this vCenter refused and the accepted VM Options.
 * One cycle at a time.
 */
public final class PropertyCollection {

    public static final String C_CLUSTER = "cluster";
    public static final String C_HOST = "host";
    public static final String C_VM = "vm";
    public static final String C_DATASTORE = "datastore";
    public static final String C_VCENTER = "vcenter";

    static final int POOL = 4;
    static final int HOST_POOL = 8;
    static final int CALL_MS = 30_000;
    static final long PACKAGES_MAX_BYTES = 16L * 1024 * 1024;

    /** What a cycle hands to the property phase. */
    public static final class Input {
        public VimReader vim;
        public OpsApi ops;
        public About about;
        public String vcid;
        /** The vCenter's own object in Ops, or null when it was not matched. */
        public String vcenterResourceId;
        public Map<ListKind, CheckListStore.Entry> lists = new EnumMap<>(ListKind.class);
        /** The thumbprint the vCenter presented, or null when the probe failed. */
        public String presentedThumbprint;
        public long cycle;
        /** The cycle's start, the sample time of every value (design 0.8). */
        public long startMs;
        public long deadlineMs;
        public PushSet ps;
        public Failures failures;
    }

    /** What the phase measured. */
    public static final class Result {
        /** Objects of vCenter with no unique Ops resource; null when not every read and map completed. */
        public Integer unmatched;
        public final Map<String, Status> status = new LinkedHashMap<>();
        public boolean deadlineHit;
        private Runnable afterCleanPush = () -> { };

        /** Remember what was sent; call it once, after a clean push. */
        public void commit() {
            afterCleanPush.run();
        }
    }

    private final Log log;
    private final LongSupplier clock;
    private final OpsMapCache maps = new OpsMapCache();
    private final CertificateState cert = new CertificateState();

    // per vCenter session: what this vCenter refused
    private String sessionKey;
    private final Map<String, Set<String>> invalidPaths = new ConcurrentHashMap<>();
    private String optionsFor;
    private List<String> optionsAccepted = Collections.emptyList();
    private final Set<String> reportedOptions = ConcurrentHashMap.newKeySet();

    public PropertyCollection(Log log, LongSupplier clock) {
        this.log = log == null ? Log.NONE : log;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the phase

    public Result run(Input in) throws InterruptedException {
        Result res = new Result();
        if (in.vim == null || in.vim.refs() == null) return res;
        newSession(in.about);
        final List<String> advSettings = items(in, ListKind.ESXI_SETTINGS);
        final List<String> pkgList = items(in, ListKind.ESXI_PACKAGES);
        final List<String> vmParams = items(in, ListKind.VM_PARAMS);
        final List<String> vmOptions = items(in, ListKind.VM_OPTIONS);

        ExecutorService pool = Executors.newFixedThreadPool(POOL, named("vcommunity-read"));
        try {
            Map<String, Future<OpsResourceMap>> mapTasks = new LinkedHashMap<>();
            Map<String, String> mapFailure = new ConcurrentHashMap<>();
            for (String kind : kinds()) {
                if (maps.due(kind, in.vcid, in.cycle)) {
                    mapTasks.put(kind, pool.submit(() -> loadMap(in, kind, mapFailure)));
                }
            }
            Future<List<ObjectOut>> fCluster = pool.submit(() -> clusters(in));
            Future<HostResult> fHost = pool.submit(() -> hosts(in, advSettings, pkgList));
            Future<List<ObjectOut>> fVm = pool.submit(() -> vms(in, vmParams, vmOptions));
            Future<List<ObjectOut>> fDs = pool.submit(() -> datastores(in));
            Future<Map<String, List<HostCollector.Assignment>>> fLic = pool.submit(() -> licenses(in));
            Future<Map<String, String>> fOpt = pool.submit(() -> vcenterSettings(in));

            for (Map.Entry<String, Future<OpsResourceMap>> e : mapTasks.entrySet()) {
                OpsResourceMap m = await(e.getValue(), in, res, C_VCENTER, "the " + e.getKey() + " map");
                if (m != null) {
                    maps.put(e.getKey(), in.vcid, in.cycle, m);
                    log.info("Ops " + m);
                } else {
                    maps.failed(e.getKey());
                    in.failures.add(collectorOf(e.getKey()), FailureCause.opsMap(e.getKey()),
                            e.getKey(), 0, "the VCF Operations list of " + e.getKey()
                            + " objects could not be read" + (mapFailure.containsKey(e.getKey())
                                    ? " (" + mapFailure.get(e.getKey()) + ")" : "")
                            + (maps.current(e.getKey(), in.vcid) == null
                                    ? "; its keys are held back" : "; the previous list is used"));
                }
            }
            List<ObjectOut> cluster = await(fCluster, in, res, C_CLUSTER, "the cluster read");
            HostResult host = await(fHost, in, res, C_HOST, "the host read");
            List<ObjectOut> vm = await(fVm, in, res, C_VM, "the virtual machine read");
            List<ObjectOut> ds = await(fDs, in, res, C_DATASTORE, "the datastore read");
            Map<String, List<HostCollector.Assignment>> lic = await(fLic, in, res, C_HOST,
                    "the license read");
            Map<String, String> settings = await(fOpt, in, res, C_VCENTER, "the vCenter settings read");

            if (host != null) {
                for (ObjectOut o : host.objects) {
                    if (lic != null) HostCollector.addLicenses(o, lic.get(o.moref), in.startMs);
                }
            }
            boolean measured = true;
            int unmatched = 0;
            boolean[] ok = new boolean[1];
            ok[0] = true;
            unmatched += place(in, res, ClusterCollector.KIND, C_CLUSTER, cluster, ok);
            unmatched += place(in, res, HostCollector.KIND, C_HOST, host == null ? null : host.objects, ok);
            unmatched += place(in, res, VmCollector.KIND, C_VM, vm, ok);
            unmatched += place(in, res, DatastoreCollector.KIND, C_DATASTORE, ds, ok);
            measured = ok[0];
            vcenter(in, res, settings);
            res.unmatched = measured && !res.deadlineHit ? Integer.valueOf(unmatched) : null;
            statuses(in, res, cluster != null, host != null, vm != null, ds != null);
        } finally {
            pool.shutdownNow();
        }
        return res;
    }

    private void newSession(About about) {
        String key = about == null ? "" : about.instanceUuid + "|" + about.version + "|" + about.build;
        if (!key.equals(sessionKey)) {
            sessionKey = key;
            invalidPaths.clear();
            optionsFor = null;
            optionsAccepted = Collections.emptyList();
            reportedOptions.clear();
        }
    }

    /** One kind's map; null (with the reason noted) when the read failed. */
    private static OpsResourceMap loadMap(Input in, String kind, Map<String, String> why)
            throws InterruptedException {
        try {
            return OpsResourceMap.load(in.ops, in.vcid, kind);
        } catch (IOException | RuntimeException e) {
            why.put(kind, e.getClass().getSimpleName());
            return null;
        }
    }

    private static String[] kinds() {
        return new String[] {ClusterCollector.KIND, HostCollector.KIND, VmCollector.KIND,
            DatastoreCollector.KIND};
    }

    private static String collectorOf(String kind) {
        switch (kind) {
            case ClusterCollector.KIND: return C_CLUSTER;
            case HostCollector.KIND: return C_HOST;
            case VmCollector.KIND: return C_VM;
            default: return C_DATASTORE;
        }
    }

    private static List<String> items(Input in, ListKind k) {
        CheckListStore.Entry e = in.lists.get(k);
        return e == null ? Collections.<String>emptyList() : new ArrayList<>(e.items);
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private <T> T await(Future<T> f, Input in, Result res, String collector, String what)
            throws InterruptedException {
        long left = in.deadlineMs - clock.getAsLong();
        try {
            return f.get(Math.max(1, left), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            res.deadlineHit = true;
            in.failures.add(collector, FailureCause.DEADLINE, what, 0,
                    "the cycle deadline was reached before " + what + " finished");
            res.status.put(collector, Status.NOT_RUN);
            return null;
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            in.failures.add(collector, FailureCause.soap("TASK"), what, 0, what
                    + " failed unexpectedly (" + c.getClass().getSimpleName() + ")");
            return null;
        }
    }

    // ------------------------------------------------------------------ the reads

    private List<ObjectOut> clusters(Input in) {
        return simpleRead(in, C_CLUSTER, ClusterCollector.request(), ClusterCollector::collect);
    }

    private List<ObjectOut> datastores(Input in) {
        return simpleRead(in, C_DATASTORE, DatastoreCollector.request(), DatastoreCollector::collect);
    }

    private List<ObjectOut> simpleRead(Input in, String collector, RetrieveRequest req,
            java.util.function.Function<BulkRow, ObjectOut> f) {
        List<ObjectOut> out = new ArrayList<>();
        RetrieveStats st = read(in, collector, req, row -> out.add(f.apply(row)));
        return st == null ? null : out;
    }

    /** One paged read with its failure and its refused paths accounted; null when it failed. */
    private RetrieveStats read(Input in, String collector, RetrieveRequest req,
            com.vcfcf.adapters.vcommunity.vim.RowSink sink) {
        Set<String> known = invalidPaths.computeIfAbsent(req.label, k -> ConcurrentHashMap.newKeySet());
        VimResult<RetrieveStats> r = new PropertyReader(in.vim).retrieve(req, known, sink);
        if (!r.isOk()) {
            in.failures.add(collector, FailureCause.soap(req.label), req.type, 0, req.label
                    + " (" + req.type + ") failed: " + r.message());
            return null;
        }
        RetrieveStats st = r.value();
        for (String p : st.droppedPaths()) {
            known.add(p);
            in.failures.add(collector, FailureCause.SOAP_INVALID_PROPERTY, req.type + ":" + p, 0,
                    "vCenter does not know the " + req.type + " property '" + p
                    + "'; the keys that need it are not sent");
        }
        log.info(req.label + " " + st);
        return st;
    }

    // ---- hosts

    private static final class HostResult {
        final List<ObjectOut> objects = new ArrayList<>();
    }

    private HostResult hosts(Input in, List<String> advSettings, List<String> pkgList) {
        Map<String, ObjectOut> byMoref = new LinkedHashMap<>();
        Map<String, MoRef> imageMgr = new HashMap<>();
        List<MoRef> refs = new ArrayList<>();
        RetrieveStats st = read(in, C_HOST, HostCollector.request(), row -> {
            ObjectOut o = HostCollector.collectRow(row);
            if (o == null) return;
            byMoref.put(row.obj.value, o);
            refs.add(row.obj);
            XmlNode im = row.val("configManager.imageConfigManager");
            if (im != null && !im.text().isEmpty()) {
                imageMgr.put(row.obj.value, new MoRef(im.attr("type") == null
                        ? "HostImageConfigManager" : im.attr("type"), im.text()));
            }
        });
        if (st == null) return null;
        Map<String, Map<String, String>> options = hostOptions(in, refs, advSettings);
        Map<String, String> installDate = new ConcurrentHashMap<>();
        Map<String, List<HostCollector.Pkg>> packages = new ConcurrentHashMap<>();
        perHostCalls(in, imageMgr, pkgList, installDate, packages);
        Set<String> wantedPkgs = new HashSet<>(pkgList);
        for (Map.Entry<String, ObjectOut> e : byMoref.entrySet()) {
            HostCollector.Extras x = new HostCollector.Extras();
            x.installDate = installDate.get(e.getKey());
            x.options = options == null ? null : options.get(e.getKey());
            x.packages = wantedPkgs.isEmpty() ? null : packages.get(e.getKey());
            HostCollector.addExtras(e.getValue(), x, advSettings);
        }
        HostResult hr = new HostResult();
        hr.objects.addAll(byMoref.values());
        return hr;
    }

    /** The wanted options of every connected host, by host; null when the read failed. */
    private Map<String, Map<String, String>> hostOptions(Input in, List<MoRef> hosts,
            List<String> advSettings) {
        if (hosts.isEmpty()) return new HashMap<>();
        Set<String> wanted = new HashSet<>(advSettings);
        wanted.add(HostCollector.ATS_HEARTBEAT);
        wanted.add(HostCollector.ATS_LOCKING);
        Map<String, Map<String, String>> out = new HashMap<>();
        RetrieveStats st = read(in, C_HOST, HostCollector.optionsRequest(hosts, wanted), row -> {
            Map<String, String> m = new HashMap<>();
            for (XmlNode opt : Vals.items(row.val("config.option"))) {
                String k = opt.childText("key");
                String v = PyText.scalar(opt.child("value"));
                if (k != null && v != null) m.put(k, v);
            }
            out.put(row.obj.value, m);
        });
        if (st == null) return null;
        for (MoRef h : hosts) {
            if (!out.containsKey(h.value)) {
                in.failures.add(C_HOST, FailureCause.soap(HostCollector.READ_OPTIONS), h.value, 0,
                        HostCollector.READ_OPTIONS + " returned no row for the host");
            }
        }
        return out;
    }

    private void perHostCalls(Input in, Map<String, MoRef> imageMgr, List<String> pkgList,
            Map<String, String> installDate, Map<String, List<HostCollector.Pkg>> packages) {
        if (imageMgr.isEmpty()) return;
        Set<String> wanted = new HashSet<>(pkgList);
        ExecutorService sub = Executors.newFixedThreadPool(HOST_POOL, named("vcommunity-host"));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, MoRef> e : imageMgr.entrySet()) {
                fs.add(sub.submit(() -> {
                    String iso = installDate(in, e.getKey(), e.getValue());
                    if (iso != null) installDate.put(e.getKey(), iso);
                    if (!wanted.isEmpty()) {
                        List<HostCollector.Pkg> p = packages(in, e.getKey(), e.getValue(), wanted);
                        if (p != null) packages.put(e.getKey(), p);
                    }
                }));
            }
            for (Future<?> f : fs) {
                long left = in.deadlineMs - clock.getAsLong();
                try {
                    f.get(Math.max(1, left), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    in.failures.add(C_HOST, FailureCause.DEADLINE, "per-host calls", 0,
                            "the cycle deadline was reached during the per-host calls");
                    break;
                } catch (ExecutionException e) {
                    in.failures.add(C_HOST, FailureCause.soap("TASK"), "per-host call", 0,
                            "a per-host call failed unexpectedly");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            sub.shutdownNow();
        }
    }

    private String installDate(Input in, String host, MoRef mgr) {
        VimResult<XmlNode> r = in.vim.call("<InstallDate xmlns=\"urn:vim25\"><_this type=\""
                + xml(mgr.type) + "\">" + xml(mgr.value) + "</_this></InstallDate>", "InstallDate", CALL_MS);
        if (!r.isOk()) {
            in.failures.add(C_HOST, FailureCause.HOST_INSTALL_DATE, host, 1,
                    "the install date of the host could not be read: " + r.message());
            return null;
        }
        XmlNode rv = r.value().find("returnval");
        return rv == null ? null : PyText.isoUtc(rv.text());
    }

    private List<HostCollector.Pkg> packages(Input in, String host, MoRef mgr, Set<String> wanted) {
        List<HostCollector.Pkg> out = new ArrayList<>();
        PathSpec spec = PathSpec.keeping("name", "version", "acceptanceLevel", "maintenanceModeRequired",
                "summary", "type", "vendor");
        VimResult<Long> r = in.vim.stream("<FetchSoftwarePackages xmlns=\"urn:vim25\"><_this type=\""
                + xml(mgr.type) + "\">" + xml(mgr.value) + "</_this></FetchSoftwarePackages>",
                "FetchSoftwarePackages", CALL_MS, PACKAGES_MAX_BYTES,
                rd -> Returnvals.forEach(rd, spec, n -> {
                    String name = n.childText("name");
                    if (name == null || !wanted.contains(name)) return;
                    String mm = PyText.bool(n.childText("maintenanceModeRequired"));
                    out.add(new HostCollector.Pkg(name, n.childText("version"),
                            n.childText("acceptanceLevel"), mm == null ? null : mm.equals("True"),
                            n.rawChildText("summary"), n.childText("type"), n.childText("vendor")));
                }));
        if (!r.isOk()) {
            in.failures.add(C_HOST, FailureCause.HOST_PACKAGES, host, 1,
                    "the software packages of the host could not be read: " + r.message());
            return null;
        }
        return out;
    }

    // ---- licenses

    private Map<String, List<HostCollector.Assignment>> licenses(Input in) {
        MoRef lm = in.vim.refs().licenseManager;
        if (lm == null) {
            in.failures.add(C_HOST, FailureCause.soap("R-LIC"), "LicenseManager", 0,
                    "R-LIC: the vCenter did not name its license manager");
            return null;
        }
        MoRef[] am = new MoRef[1];
        VimResult<RetrieveStats> pr = new PropertyReader(in.vim).retrieve(
                new RetrieveRequest("R-LIC", "LicenseManager", 1, Collections.singletonList(lm))
                        .path("licenseAssignmentManager"), Collections.<String>emptySet(), row -> {
                            XmlNode v = row.val("licenseAssignmentManager");
                            if (v != null && !v.text().isEmpty()) {
                                am[0] = new MoRef(v.attr("type") == null ? "LicenseAssignmentManager"
                                        : v.attr("type"), v.text());
                            }
                        });
        if (!pr.isOk() || am[0] == null) {
            in.failures.add(C_HOST, FailureCause.soap("R-LIC"), "LicenseAssignmentManager", 0,
                    "R-LIC: the license assignment manager could not be found"
                    + (pr.isOk() ? "" : ": " + pr.message()));
            return null;
        }
        Map<String, List<HostCollector.Assignment>> out = new HashMap<>();
        PathSpec spec = PathSpec.keeping("entityId", "assignedLicense.licenseKey",
                "assignedLicense.editionKey", "assignedLicense.name", "assignedLicense.properties");
        VimResult<Long> r = in.vim.stream("<QueryAssignedLicenses xmlns=\"urn:vim25\"><_this type=\""
                + xml(am[0].type) + "\">" + xml(am[0].value) + "</_this></QueryAssignedLicenses>",
                "QueryAssignedLicenses", VimSoapClient.READ_TIMEOUT_MS, PACKAGES_MAX_BYTES,
                rd -> Returnvals.forEach(rd, spec, n -> {
                    String entity = n.childText("entityId");
                    XmlNode al = n.child("assignedLicense");
                    if (entity == null || al == null) return;
                    String expiration = null;
                    for (XmlNode p : al.children()) {
                        if ("properties".equals(p.name) && "expirationDate".equals(p.childText("key"))) {
                            XmlNode v = p.child("value");
                            expiration = v == null ? null : v.text();
                            break;
                        }
                    }
                    out.computeIfAbsent(entity, k -> new ArrayList<>()).add(new HostCollector.Assignment(
                            al.rawChildText("name"), al.childText("licenseKey"),
                            al.childText("editionKey"), expiration));
                }));
        if (!r.isOk()) {
            in.failures.add(C_HOST, FailureCause.soap("R-LIC"), "QueryAssignedLicenses", 0,
                    "R-LIC: the license assignments could not be read: " + r.message());
            return null;
        }
        return out;
    }

    // ---- vCenter settings

    private Map<String, String> vcenterSettings(Input in) {
        MoRef om = in.vim.refs().setting;
        if (om == null) {
            in.failures.add(C_VCENTER, FailureCause.soap(VCenterCollector.READ), "OptionManager", 0,
                    "R-OPT: the vCenter did not name its option manager");
            return null;
        }
        Set<String> names = VCenterCollector.settingNames();
        Map<String, String> out = new HashMap<>();
        PathSpec spec = PathSpec.keeping("key", "value");
        VimResult<Long> r = in.vim.stream("<QueryOptions xmlns=\"urn:vim25\"><_this type=\""
                + xml(om.type) + "\">" + xml(om.value) + "</_this></QueryOptions>", "QueryOptions",
                VimSoapClient.READ_TIMEOUT_MS, PACKAGES_MAX_BYTES, rd -> Returnvals.forEach(rd, spec, n -> {
                    String k = n.childText("key");
                    if (k == null || !names.contains(k)) return;
                    String t = VCenterCollector.optionText(n);
                    if (t != null) out.put(k, t);
                }));
        if (!r.isOk()) {
            in.failures.add(C_VCENTER, FailureCause.soap(VCenterCollector.READ), "OptionManager", 0,
                    "R-OPT: the vCenter settings could not be read: " + r.message());
            return null;
        }
        return out;
    }

    // ---- virtual machines

    private List<ObjectOut> vms(Input in, List<String> vmParams, List<String> vmOptions) {
        List<String> accepted = acceptedOptions(in, vmOptions);
        Set<String> nonScalar = ConcurrentHashMap.newKeySet();
        List<ObjectOut> out = new ArrayList<>();
        RetrieveRequest req = VmCollector.request(!vmParams.isEmpty(), new HashSet<>(vmParams), accepted);
        RetrieveStats st = read(in, C_VM, req, row -> out.add(VmCollector.collect(row, vmParams,
                accepted, nonScalar)));
        if (st == null) return null;
        for (String p : st.droppedPaths()) {
            if (accepted.contains(p)) forgetOption(p);
        }
        for (String p : nonScalar) {
            if (reportedOptions.add("scalar:" + p)) {
                log.warn("VM Options entry '" + p + "' is not a single value (an object or a list); "
                        + "its text cannot be reproduced, so it is skipped");
            }
        }
        return out;
    }

    private synchronized void forgetOption(String path) {
        List<String> l = new ArrayList<>(optionsAccepted);
        l.remove(path);
        optionsAccepted = l;
    }

    /**
     * The VM Options paths vCenter accepts (design 5.5): each entry is tried once, on one VM, and
     * those it refuses are dropped and reported; the answer is remembered for this list and session.
     */
    private synchronized List<String> acceptedOptions(Input in, List<String> wanted) {
        if (wanted.isEmpty()) return Collections.emptyList();
        String key = String.join("\n", wanted);
        if (key.equals(optionsFor)) return optionsAccepted;
        RetrieveRequest probe = new RetrieveRequest("R-VM-PROBE", VmCollector.KIND, 1, null).firstPageOnly();
        for (String p : wanted) probe.path(p);
        VimResult<RetrieveStats> r = new PropertyReader(in.vim).retrieve(probe,
                Collections.<String>emptySet(), row -> { });
        if (!r.isOk()) {
            in.failures.add(C_VM, FailureCause.soap("R-VM-PROBE"), "VM Options", 0,
                    "the VM Options entries could not be checked against vCenter, so none is "
                    + "collected this cycle: " + r.message());
            return Collections.emptyList(); // not remembered: tried again next cycle
        }
        List<String> accepted = new ArrayList<>(wanted);
        for (String bad : r.value().droppedPaths()) {
            accepted.remove(bad);
            in.failures.add(C_VM, FailureCause.SOAP_INVALID_PROPERTY, "VM Options:" + bad, 0,
                    "vCenter does not know the virtual machine property '" + bad
                    + "' (a VM Options entry); it is skipped");
        }
        optionsFor = key;
        optionsAccepted = accepted;
        return accepted;
    }

    // ------------------------------------------------------------------ placing

    /**
     * Match one kind's objects to resources and queue their keys.
     *
     * @param objects null when the kind's read failed
     * @param ok      set false when the figure of unmatched objects cannot be trusted
     * @return objects with no unique Ops resource
     */
    private int place(Input in, Result res, String kind, String collector, List<ObjectOut> objects,
            boolean[] ok) throws InterruptedException {
        if (objects == null) {
            ok[0] = false;
            return 0;
        }
        OpsResourceMap map = maps.current(kind, in.vcid);
        if (map == null) {
            ok[0] = false;
            return 0;
        }
        List<ObjectOut> unresolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ObjectOut o : objects) {
            seen.add(o.moref);
            String rid = map.resourceId(o.moref);
            if (rid == null) {
                unresolved.add(o);
            } else {
                write(in, rid, o);
            }
        }
        Set<String> before = maps.swapSeen(kind, seen);
        if (!unresolved.isEmpty() && !maps.loadedIn(kind, in.cycle)) {
            boolean anyNew = false;
            for (ObjectOut o : unresolved) {
                if (!before.contains(o.moref) && !map.ambiguous().contains(o.moref)) anyNew = true;
            }
            if (maps.missJustifiesRead(kind, in.cycle, anyNew)) {
                try {
                    OpsResourceMap fresh = OpsResourceMap.load(in.ops, in.vcid, kind);
                    maps.put(kind, in.vcid, in.cycle, fresh);
                    map = fresh;
                    log.info("Ops " + fresh + " (read again: objects it did not know)");
                    List<ObjectOut> still = new ArrayList<>();
                    for (ObjectOut o : unresolved) {
                        String rid = map.resourceId(o.moref);
                        if (rid == null) still.add(o);
                        else write(in, rid, o);
                    }
                    unresolved = still;
                } catch (IOException | RuntimeException e) {
                    maps.failed(kind);
                    in.failures.add(collector, FailureCause.opsMap(kind), kind, 0, "the VCF "
                            + "Operations list of " + kind + " objects could not be read again: "
                            + e.getClass().getSimpleName());
                    ok[0] = false;
                }
            }
        }
        if (!unresolved.isEmpty()) {
            log.info(kind + ": " + unresolved.size() + " of " + objects.size() + " object(s) have no "
                    + "unique VCF Operations resource and get no keys this cycle");
        }
        return unresolved.size();
    }

    private void write(Input in, String rid, ObjectOut o) {
        for (Map.Entry<String, Object> e : o.props().entrySet()) {
            if (e.getValue() instanceof Double) {
                in.ps.numProp(rid, e.getKey(), (Double) e.getValue());
            } else {
                in.ps.prop(rid, e.getKey(), (String) e.getValue());
            }
        }
        for (Map.Entry<String, Double> e : o.stats().entrySet()) {
            in.ps.stat(rid, e.getKey(), e.getValue());
        }
        in.failures.addWithheld(o.withheld());
    }

    // ------------------------------------------------------------------ the vCenter object

    private void vcenter(Input in, Result res, Map<String, String> settings)
            throws InterruptedException {
        String id = in.vcenterResourceId;
        if (id == null) {
            res.status.put(C_VCENTER, Status.SKIPPED);
            return;
        }
        write(in, id, VCenterCollector.collect(settings, in.about));
        if (!cert.isKnown()) {
            try {
                cert.readBack(in.ops, id);
            } catch (IOException | RuntimeException e) {
                in.failures.add(C_VCENTER, FailureCause.CERT_READBACK, "vCenter certificate", 1,
                        "the recorded certificate could not be read back from VCF Operations: "
                        + e.getClass().getSimpleName() + "; its status is not sent");
            }
        }
        CertificateState.Check c = cert.check(in.presentedThumbprint, in.startMs);
        if (c.status != null) in.ps.prop(id, CertificateState.STATUS, c.status);
        if (c.thumbprint != null) in.ps.prop(id, CertificateState.THUMBPRINT, c.thumbprint);
        if (c.previous != null) {
            in.ps.prop(id, CertificateState.PREVIOUS, c.previous);
            in.ps.prop(id, CertificateState.LAST_CHANGED, c.lastChanged);
            log.warn("The vCenter certificate has changed: SHA-256 thumbprint " + c.previous
                    + " before, " + c.thumbprint + " now. Collection goes on. If nobody replaced the "
                    + "certificate, compare the new thumbprint with the one in vCenter.");
        } else if (CertificateState.RECORDED.equals(c.status)) {
            log.info("Recorded the vCenter certificate: SHA-256 thumbprint " + c.thumbprint);
        }
        if (c.status == null) in.failures.addWithheld(1);
        res.afterCleanPush = () -> cert.commit(c);
    }

    private void statuses(Input in, Result res, boolean cl, boolean ho, boolean vm, boolean ds) {
        mark(in, res, C_CLUSTER, cl);
        mark(in, res, C_HOST, ho);
        mark(in, res, C_VM, vm);
        mark(in, res, C_DATASTORE, ds);
        if (!res.status.containsKey(C_VCENTER)) {
            res.status.put(C_VCENTER, in.failures.forCollector(C_VCENTER) == 0 ? Status.OK : Status.PARTIAL);
        }
    }

    private void mark(Input in, Result res, String collector, boolean mainRead) {
        if (res.status.containsKey(collector)) return; // NOT_RUN: the deadline came first
        boolean mapOk = maps.current(kindOf(collector), in.vcid) != null;
        if (!mainRead || !mapOk) {
            res.status.put(collector, Status.FAILED);
        } else {
            res.status.put(collector, in.failures.forCollector(collector) == 0 ? Status.OK : Status.PARTIAL);
        }
    }

    private static String kindOf(String collector) {
        switch (collector) {
            case C_CLUSTER: return ClusterCollector.KIND;
            case C_HOST: return HostCollector.KIND;
            case C_VM: return VmCollector.KIND;
            default: return DatastoreCollector.KIND;
        }
    }

    private static String xml(String s) {
        return VimXml.escape(s);
    }
}
