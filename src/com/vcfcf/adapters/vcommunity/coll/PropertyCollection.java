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
    /** The Datacenters' membership and distributed port groups; their figures are the derived phase's. */
    public static final String C_DATACENTER = "datacenter";

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
        /** True when the guest phase will run: the VM and host reads also take what it picks its VMs by. */
        public boolean guest;
    }

    /** What the phase measured. */
    public static final class Result {
        /** Objects of vCenter with no unique Ops resource; null when not every read and map completed. */
        public Integer unmatched;
        public final Map<String, Status> status = new LinkedHashMap<>();
        public boolean deadlineHit;
        /** The templates the VM read found; null when the VM read did not finish. */
        public TemplateScan templates;
        /** Datastore MoRef value to name; null when the datastore read did not finish. */
        public Map<String, String> datastoreNames;
        /** Folder MoRef value to name for the templates' parents; null when that read failed. */
        public Map<String, String> folderNames;
        /** Who is in what, as this cycle's reads say it; each part carries whether its read finished. */
        public Membership membership;
        /** The Windows VMs and the ESXi thumbprints, for the guest phase; null when it is not wanted. */
        public com.vcfcf.adapters.vcommunity.guest.GuestRoster roster;
        /** VM Options entries skipped this cycle (invalid for vCenter, or a value this port cannot print); null when not measured. */
        public Integer optionsSkipped;
        private Runnable afterCleanPush = () -> { };

        /** Remember what was sent; call it once, after a clean push. */
        public void commit() {
            afterCleanPush.run();
        }

        /** Also run this after a clean push (the derived phase's memory of what it sent). */
        public void alsoOnCleanPush(Runnable r) {
            Runnable before = afterCleanPush;
            afterCleanPush = () -> {
                before.run();
                r.run();
            };
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

    /** This vCenter's Ops resource map of a kind as the last cycle left it, or null when there is none. */
    public OpsResourceMap opsMap(String kind, String vcid) {
        return vcid == null ? null : maps.current(kind, vcid);
    }

    /** A read that used this kind's list failed: the list may name an object that is gone, so read it next cycle. */
    public void markStale(String kind) {
        maps.failed(kind);
    }

    // ------------------------------------------------------------------ the phase

    public Result run(Input in) throws InterruptedException {
        Result res = new Result();
        final Membership members = new Membership();
        res.membership = members;
        if (in.guest) {
            res.roster = new com.vcfcf.adapters.vcommunity.guest.GuestRoster();
            members.roster(res.roster);
        }
        if (in.vim == null || in.vim.refs() == null) return res;
        newSession(in.about, in.vim.generation());
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
            Future<List<ObjectOut>> fCluster = pool.submit(() -> clusters(in, members));
            Future<HostResult> fHost = pool.submit(() -> hosts(in, advSettings, pkgList, members));
            TemplateScan scan = new TemplateScan();
            Map<String, String> dsNames = new ConcurrentHashMap<>();
            Future<List<ObjectOut>> fVm = pool.submit(() -> vms(in, vmParams, vmOptions, scan, members));
            Future<Boolean> fPools = pool.submit(() -> pools(in, members));
            Future<Boolean> fDc = pool.submit(() -> datacenters(in, members));
            Future<List<ObjectOut>> fDs = pool.submit(() -> datastores(in, dsNames));
            Future<Map<String, List<HostCollector.Assignment>>> fLic = pool.submit(() -> licenses(in));
            Future<Map<String, String>> fOpt = pool.submit(() -> vcenterSettings(in));

            for (Map.Entry<String, Future<OpsResourceMap>> e : mapTasks.entrySet()) {
                OpsResourceMap m = await(e.getValue(), in, res, C_VCENTER, "the " + e.getKey() + " map");
                if (m != null) {
                    maps.put(e.getKey(), in.vcid, in.cycle, m);
                    maps.scheduledRead(e.getKey());
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
            res.optionsSkipped = vm == null ? null : optionsSkipped;
            List<ObjectOut> ds = await(fDs, in, res, C_DATASTORE, "the datastore read");
            Map<String, List<HostCollector.Assignment>> lic = await(fLic, in, res, C_HOST,
                    "the license read");
            Map<String, String> settings = await(fOpt, in, res, C_VCENTER, "the vCenter settings read");
            Boolean poolsOk = await(fPools, in, res, C_CLUSTER, "the resource pool read");
            Boolean dcOk = await(fDc, in, res, C_DATACENTER, "the Datacenter read");
            members.done(cluster != null, host != null, vm != null, Boolean.TRUE.equals(dcOk),
                    Boolean.TRUE.equals(poolsOk));
            if (vm != null) {
                // the templates came with the VM read; their names come with the datastore and folder reads
                res.templates = scan;
                res.datastoreNames = ds == null ? null : dsNames;
                res.folderNames = scan.rows.isEmpty() ? new HashMap<>() : folders(in, scan);
            }

            if (host != null) {
                if (lic == null && !res.deadlineHit && clock.getAsLong() < in.deadlineMs) {
                    List<String> morefs = new ArrayList<>();
                    for (ObjectOut o : host.objects) morefs.add(o.moref);
                    lic = licensesPerHost(in, morefs);
                }
                for (ObjectOut o : host.objects) {
                    // a host with no entry has no read to show: nothing is sent for it
                    if (lic != null && lic.containsKey(o.moref)) HostCollector.addLicenses(o, lic.get(o.moref), in.startMs);
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

    private long sessionGen;

    private void newSession(About about, long generation) {
        if (generation != sessionGen) {
            // a new login: ask vCenter again which VM Options entries it accepts (5.5)
            sessionGen = generation;
            optionsFor = null;
        }
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

    /** How a read's objects fared: how many collected, how many threw. */
    static final class Tally {
        int ok;
        int bad;
    }

    /**
     * One object's collector, isolated: an unexpected failure on one object is counted as a
     * failed read of that object ({@code F-COLLECT-<read>}, apart from a vCenter fault) and the
     * read goes on with the next; one object never fails its type. The caller treats a read in
     * which every object threw as a failed one ({@link #allFailed}).
     *
     * @return the object's keys, or null when it failed or has none
     */
    static ObjectOut guarded(java.util.function.Function<BulkRow, ObjectOut> f, BulkRow row, Failures failures,
            String collector, String read, Tally tally) {
        try {
            ObjectOut o = f.apply(row);
            tally.ok++;
            return o;
        } catch (RuntimeException e) {
            tally.bad++;
            failures.add(collector, FailureCause.collect(read), row.obj.value, 1, "collecting object "
                    + row.obj.value + " failed unexpectedly (" + e.getClass().getSimpleName()
                    + "); its keys are not sent");
            return null;
        }
    }

    /** Every object of the read threw: a defect of the collector, not of the objects; the type failed. */
    static boolean allFailed(Tally t) {
        return t.bad > 0 && t.ok == 0;
    }

    private List<ObjectOut> clusters(Input in, Membership members) {
        return simpleRead(in, C_CLUSTER, ClusterCollector.request(), row -> {
            members.cluster(row);
            return ClusterCollector.collect(row);
        });
    }

    private List<ObjectOut> datastores(Input in, Map<String, String> names) {
        // the templates name their datastores from this read: no read of their own (design 4.11)
        return simpleRead(in, C_DATASTORE, DatastoreCollector.request(), row -> {
            String n = row.name();
            if (n != null) names.put(row.obj.value, n);
            return DatastoreCollector.collect(row);
        });
    }

    private List<ObjectOut> simpleRead(Input in, String collector, RetrieveRequest req,
            java.util.function.Function<BulkRow, ObjectOut> f) {
        List<ObjectOut> out = new ArrayList<>();
        Tally tally = new Tally();
        RetrieveStats st = read(in, collector, req, row -> {
            ObjectOut o = guarded(f, row, in.failures, collector, req.label, tally);
            if (o != null) out.add(o);
        });
        return st == null || allFailed(tally) ? null : out;
    }

    /** True when this cycle's work must stop: the deadline passed or the worker was interrupted. */
    private boolean stopNow(Input in) {
        return Thread.currentThread().isInterrupted() || clock.getAsLong() >= in.deadlineMs;
    }

    /** The reader every read goes through: it stops at the cycle deadline or an interrupt. */
    private PropertyReader reader(Input in) {
        return new PropertyReader(in.vim, PropertyReader.MAX_PAGE_BYTES,
                () -> Thread.currentThread().isInterrupted() || clock.getAsLong() >= in.deadlineMs, log);
    }

    /** One paged read with its failure and its refused paths accounted; null when it failed. */
    private RetrieveStats read(Input in, String collector, RetrieveRequest req,
            com.vcfcf.adapters.vcommunity.vim.RowSink sink) {
        return read(in, collector, req, sink, null);
    }

    /** @param faultOut when not null, receives the fault class of a failed read */
    private RetrieveStats read(Input in, String collector, RetrieveRequest req,
            com.vcfcf.adapters.vcommunity.vim.RowSink sink, String[] faultOut) {
        Set<String> known = invalidPaths.computeIfAbsent(req.label, k -> ConcurrentHashMap.newKeySet());
        int[] missed = new int[1];
        String[] firstMissing = new String[1];
        VimResult<RetrieveStats> r = reader(in).retrieve(req, known, row -> {
            if (!row.missing().isEmpty()) {
                if (missed[0]++ == 0) {
                    String p = row.missing().iterator().next();
                    firstMissing[0] = p + (row.missingFault(p) == null ? "" : " (" + row.missingFault(p) + ")");
                }
            }
            sink.row(row);
        });
        if (!r.isOk()) {
            if (faultOut != null) faultOut[0] = r.faultClass();
            in.failures.add(collector, "Stopped".equals(r.faultClass()) ? FailureCause.DEADLINE
                    : FailureCause.soap(req.label), req.type, 0, req.label + " (" + req.type + ") failed: "
                    + r.message());
            return null;
        }
        RetrieveStats st = r.value();
        for (String p : st.droppedPaths()) {
            known.add(p);
            in.failures.add(collector, FailureCause.SOAP_INVALID_PROPERTY, req.type + ":" + p, 0,
                    "vCenter does not know the " + req.type + " property '" + p
                    + "'; the keys that need it are not sent");
        }
        if (missed[0] > 0) {
            // vCenter could not read a property of an object it has (a permission, a fault): that is a
            // failed read of those values, not an unset property, which it simply leaves out (5.1 rule 1)
            in.failures.add(collector, FailureCause.soap(req.label), req.type + ":missing", 0, req.label
                    + ": vCenter could not read " + firstMissing[0] + " of " + missed[0]
                    + " object(s); those keys are not sent");
        }
        log.info(req.label + " " + st);
        return st;
    }

    // ---- hosts

    private static final class HostResult {
        final List<ObjectOut> objects = new ArrayList<>();
    }

    private HostResult hosts(Input in, List<String> advSettings, List<String> pkgList, Membership members) {
        Map<String, ObjectOut> byMoref = new LinkedHashMap<>();
        Map<String, MoRef> imageMgr = new HashMap<>();
        // hosts whose image manager was read and is unset (its install date is not sent); a host whose
        // read of it failed is in neither set, and gets no install date at all
        Set<String> noImageMgr = new HashSet<>();
        List<MoRef> refs = new ArrayList<>();
        Tally tally = new Tally();
        RetrieveStats st = read(in, C_HOST, HostCollector.request(in.guest), row -> {
            members.host(row);
            ObjectOut o = guarded(HostCollector::collectRow, row, in.failures, C_HOST, HostCollector.READ, tally);
            if (o == null) return;
            byMoref.put(row.obj.value, o);
            refs.add(row.obj);
            XmlNode im = row.val("configManager.imageConfigManager");
            if (im != null && !im.text().isEmpty()) {
                imageMgr.put(row.obj.value, new MoRef(im.attr("type") == null
                        ? "HostImageConfigManager" : im.attr("type"), im.text()));
            } else if (row.isUnset("configManager.imageConfigManager")) {
                noImageMgr.add(row.obj.value);
            }
        });
        if (st == null || allFailed(tally)) return null;
        Map<String, Map<String, String>> options = hostOptions(in, refs, advSettings);
        Map<String, String> installDate = new ConcurrentHashMap<>();
        Set<String> installAnswered = ConcurrentHashMap.newKeySet();
        Map<String, List<HostCollector.Pkg>> packages = new ConcurrentHashMap<>();
        perHostCalls(in, imageMgr, pkgList, installDate, installAnswered, packages);
        Set<String> wantedPkgs = new HashSet<>(pkgList);
        for (Map.Entry<String, ObjectOut> e : byMoref.entrySet()) {
            HostCollector.Extras x = new HostCollector.Extras();
            x.installDate = installDate.get(e.getKey());
            // a host whose image manager is unset, or whose call answered, counts as read (with no date nothing is sent); a
            // host whose manager could not be read, or whose call failed, has no answer at all
            x.installDateRead = noImageMgr.contains(e.getKey()) || installAnswered.contains(e.getKey());
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
        com.vcfcf.adapters.vcommunity.vim.RowSink sink = row -> {
            // no option list: vCenter could not read it (a missing-set fault, a refused path) or the host left
            // between the two reads and has no configuration; either way there is no answer for this host,
            // not an empty list, and it is counted below as a row not returned
            if (row.val("config.option") == null) return;
            Map<String, String> m = new HashMap<>();
            for (XmlNode opt : Vals.items(row.val("config.option"))) {
                String k = opt.childText("key");
                String v = PyText.scalar(opt.child("value"));
                if (k != null && v != null) m.put(k, v);
            }
            out.put(row.obj.value, m);
        };
        String[] fault = new String[1];
        RetrieveStats st = read(in, C_HOST, HostCollector.optionsRequest(hosts, wanted), sink, fault);
        if (st == null && "ManagedObjectNotFound".equals(fault[0])) {
            // a host left the vCenter between the two reads and vCenter refused the whole list: ask for
            // each host alone, so only the host that went is without its options
            out.clear();
            st = new RetrieveStats();
            for (MoRef h : hosts) {
                if (stopNow(in)) break;
                read(in, C_HOST, HostCollector.optionsRequest(Collections.singletonList(h), wanted), sink);
            }
        }
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
            Map<String, String> installDate, Set<String> installAnswered,
            Map<String, List<HostCollector.Pkg>> packages) {
        if (imageMgr.isEmpty()) return;
        Set<String> wanted = new HashSet<>(pkgList);
        ExecutorService sub = Executors.newFixedThreadPool(HOST_POOL, named("vcommunity-host"));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Map.Entry<String, MoRef> e : imageMgr.entrySet()) {
                fs.add(sub.submit(() -> {
                    if (stopNow(in)) return; // an abandoned cycle makes no more calls
                    String[] answer = new String[1];
                    if (installDate(in, e.getKey(), e.getValue(), answer)) installAnswered.add(e.getKey());
                    if (answer[0] != null) installDate.put(e.getKey(), answer[0]);
                    if (!wanted.isEmpty() && !stopNow(in)) {
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

    /**
     * {@code HostImageConfigManager.installDate()}: the operation's name on the wire is {@code installDate}
     * (lower case, unlike most of vim25; {@code InstallDate} is refused as an unknown method).
     *
     * @param out receives the date as ISO UTC text when the call returned one
     * @return true when the call answered (with or without a date); false when it failed (counted)
     */
    /** The privilege both per-host calls need. */
    static final String IMAGE_PRIVILEGE = "Host.Config.Image";

    /**
     * Count a failed per-host call. A refusal for lack of the privilege is the same on every host, so it is
     * one failed read of the cycle that says what to grant, not one per host (the withheld values are still
     * counted per host).
     */
    private static void hostCallFailed(Input in, String cause, String host, VimResult<?> r, String what) {
        if ("NoPermission".equals(r.faultClass())) {
            in.failures.add(C_HOST, cause, "privilege " + IMAGE_PRIVILEGE, 1, what + " could not be read: the "
                    + "account has no " + IMAGE_PRIVILEGE + " privilege on the vCenter objects; grant it to the "
                    + "account's role, or those keys stay unsent on every host");
            return;
        }
        in.failures.add(C_HOST, cause, host, 1, what + " could not be read: " + r.message());
    }

    private boolean installDate(Input in, String host, MoRef mgr, String[] out) {
        VimResult<XmlNode> r = in.vim.call("<installDate xmlns=\"urn:vim25\"><_this type=\""
                + xml(mgr.type) + "\">" + xml(mgr.value) + "</_this></installDate>", "installDate", CALL_MS);
        if (!r.isOk()) {
            hostCallFailed(in, FailureCause.HOST_INSTALL_DATE, host, r, "the install date of the host");
            return false;
        }
        XmlNode rv = r.value().find("returnval");
        out[0] = rv == null ? null : PyText.isoUtc(rv.text());
        return true;
    }

    private List<HostCollector.Pkg> packages(Input in, String host, MoRef mgr, Set<String> wanted) {
        List<HostCollector.Pkg> out = new ArrayList<>();
        PathSpec spec = PathSpec.keeping("name", "version", "acceptanceLevel", "maintenanceModeRequired",
                "summary", "type", "vendor");
        VimResult<Long> r = in.vim.stream("<fetchSoftwarePackages xmlns=\"urn:vim25\"><_this type=\""
                + xml(mgr.type) + "\">" + xml(mgr.value) + "</_this></fetchSoftwarePackages>",
                "fetchSoftwarePackages", CALL_MS, PACKAGES_MAX_BYTES,
                rd -> Returnvals.forEach(rd, spec, n -> {
                    String name = n.childText("name");
                    if (name == null || !wanted.contains(name)) return;
                    String mm = PyText.bool(n.childText("maintenanceModeRequired"));
                    out.add(new HostCollector.Pkg(name, n.rawChildText("version"),
                            n.rawChildText("acceptanceLevel"), mm == null ? null : mm.equals("True"),
                            n.rawChildText("summary"), n.rawChildText("type"), n.rawChildText("vendor")));
                }));
        if (!r.isOk()) {
            hostCallFailed(in, FailureCause.HOST_PACKAGES, host, r, "the software packages of the host");
            return null;
        }
        return out;
    }

    // ---- licenses

    /** The most bytes the one call for every assignment may answer: feature lists make each large. */
    static final long LICENSES_MAX_BYTES = 256L * 1024 * 1024;

    /** The license assignment manager, or null (the failure is counted). */
    private MoRef assignmentManager(Input in) {
        MoRef lm = in.vim.refs().licenseManager;
        if (lm == null) {
            in.failures.add(C_HOST, FailureCause.soap("R-LIC"), "LicenseManager", 0,
                    "R-LIC: the vCenter did not name its license manager");
            return null;
        }
        MoRef[] am = new MoRef[1];
        VimResult<RetrieveStats> pr = reader(in).retrieve(
                new RetrieveRequest("R-LIC", "LicenseManager", 1, Collections.singletonList(lm))
                        .withoutName().path("licenseAssignmentManager"), Collections.<String>emptySet(), row -> {
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
        return am[0];
    }

    private static final PathSpec LICENSE_SPEC = PathSpec.keeping("entityId", "assignedLicense.licenseKey",
            "assignedLicense.editionKey", "assignedLicense.name", "assignedLicense.properties");

    /** Add the assignments of one answer; the check of the deadline is per item, so a huge answer stops. */
    private void readAssignments(javax.xml.stream.XMLStreamReader rd, Input in,
            Map<String, List<HostCollector.Assignment>> out) throws javax.xml.stream.XMLStreamException {
        Returnvals.forEach(rd, LICENSE_SPEC, n -> {
            if (Thread.currentThread().isInterrupted() || clock.getAsLong() >= in.deadlineMs) {
                throw new IllegalStateException("stopped");
            }
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
                    al.rawChildText("name"), al.rawChildText("licenseKey"),
                    al.rawChildText("editionKey"), expiration));
        });
    }

    /** Every assignment in one call; null when it failed (the caller then asks per host). */
    private Map<String, List<HostCollector.Assignment>> licenses(Input in) {
        MoRef am = assignmentManager(in);
        if (am == null) return null;
        Map<String, List<HostCollector.Assignment>> out = new HashMap<>();
        VimResult<Long> r = in.vim.stream("<QueryAssignedLicenses xmlns=\"urn:vim25\"><_this type=\""
                + xml(am.type) + "\">" + xml(am.value) + "</_this></QueryAssignedLicenses>",
                "QueryAssignedLicenses", VimSoapClient.READ_TIMEOUT_MS, LICENSES_MAX_BYTES,
                rd -> readAssignments(rd, in, out));
        if (!r.isOk()) {
            in.failures.add(C_HOST, FailureCause.soap("R-LIC"), "QueryAssignedLicenses", 0,
                    "R-LIC: the license assignments could not be read in one call: " + r.message());
            return null;
        }
        return out;
    }

    /**
     * The fallback when the one call fails (a vCenter with so many assignments that the answer is over its
     * cap or its time): one call per host, so the hosts it can read still get their licensing.
     * A host whose call fails gets none and is counted. Null when the manager cannot be found.
     */
    private Map<String, List<HostCollector.Assignment>> licensesPerHost(Input in, List<String> hosts) {
        MoRef am = assignmentManager(in);
        if (am == null) return null;
        Map<String, List<HostCollector.Assignment>> out = new ConcurrentHashMap<>();
        ExecutorService sub = Executors.newFixedThreadPool(HOST_POOL, named("vcommunity-lic"));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String h : hosts) {
                fs.add(sub.submit(() -> {
                    if (Thread.currentThread().isInterrupted() || clock.getAsLong() >= in.deadlineMs) return;
                    Map<String, List<HostCollector.Assignment>> one = new HashMap<>();
                    VimResult<Long> r = in.vim.stream("<QueryAssignedLicenses xmlns=\"urn:vim25\"><_this type=\""
                            + xml(am.type) + "\">" + xml(am.value) + "</_this><entityId>" + xml(h)
                            + "</entityId></QueryAssignedLicenses>", "QueryAssignedLicenses", CALL_MS,
                            PACKAGES_MAX_BYTES, rd -> readAssignments(rd, in, one));
                    if (!r.isOk()) {
                        in.failures.add(C_HOST, FailureCause.soap("R-LIC"), h, 0,
                                "R-LIC: the license assignments of the host could not be read: " + r.message());
                        return;
                    }
                    List<HostCollector.Assignment> l = one.get(h);
                    out.put(h, l == null ? new ArrayList<>() : l);
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get(Math.max(1, in.deadlineMs - clock.getAsLong()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    break;
                } catch (ExecutionException e) {
                    in.failures.add(C_HOST, FailureCause.soap("TASK"), "license call", 0,
                            "a license call failed unexpectedly");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            sub.shutdownNow();
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

    private List<ObjectOut> vms(Input in, List<String> vmParams, List<String> vmOptions, TemplateScan scan,
            Membership members) {
        optionsSkipped = null;
        List<String> accepted = acceptedOptions(in, vmOptions);
        Set<String> nonScalar = ConcurrentHashMap.newKeySet();
        List<ObjectOut> out = new ArrayList<>();
        Tally tally = new Tally();
        String[] fault = new String[1];
        RetrieveStats st = readVms(in, vmParams, accepted, nonScalar, out, tally, fault, scan, members);
        if (st == null && !accepted.isEmpty() && ("TooLarge".equals(fault[0]) || "Parse".equals(fault[0]))) {
            // An entry whose value is huge or deeply nested (a whole config, a snapshot tree) can fail the
            // page. The base VM keys are never lost to an Options entry (5.5 rule 3): read again without
            // the entries and keep them off until the list or the session changes.
            suspendOptions();
            log.warn("The VM read failed with VM Options entries in it (" + fault[0] + "); reading the VMs "
                    + "again without them. The entries " + accepted + " are off until the list is changed");
            out.clear();
            nonScalar.clear();
            tally = new Tally();
            accepted = Collections.emptyList();
            st = readVms(in, vmParams, accepted, nonScalar, out, tally, fault, scan, members);
        }
        if (st == null || allFailed(tally)) return null;
        for (String p : st.droppedPaths()) {
            if (accepted.contains(p)) forgetOption(p);
        }
        for (String p : nonScalar) {
            // an object or a list this port cannot print costs nothing to find; it is left out of every later read.
            // The tools configuration stays in: its two keys of the Guest group come from the same read
            if (!"config.tools".equals(p)) forgetOption(p);
            if (reportedOptions.add("scalar:" + p)) {
                log.warn("VM Options entry '" + p + "' is not a single value this port can reproduce (an "
                        + "object, a list, or a reference of a type it has no Python name for); it is skipped");
            }
        }
        optionsSkipped = skippedOptions(vmOptions, nonScalar);
        return out;
    }

    /** How many VM Options entries are skipped now; null when it is not known (the list was not checked this cycle). */
    private volatile Integer optionsSkipped;

    private synchronized Integer skippedOptions(List<String> wanted, Set<String> nonScalar) {
        if (wanted.isEmpty()) return 0;
        if (!String.join("\n", wanted).equals(optionsFor)) return null;
        int n = 0;
        for (String w : new java.util.LinkedHashSet<>(wanted)) {
            if (!optionsAccepted.contains(w) || nonScalar.contains(w)) n++;
        }
        return n;
    }

    private RetrieveStats readVms(Input in, List<String> vmParams, List<String> accepted, Set<String> nonScalar,
            List<ObjectOut> out, Tally tally, String[] fault, TemplateScan scan, Membership members) {
        RetrieveRequest req = VmCollector.request(!vmParams.isEmpty(), new HashSet<>(vmParams), accepted, in.guest);
        scan.reset(); // a page that failed is read again from the start, never added to
        members.resetVms();
        return read(in, C_VM, req, row -> {
            ObjectOut o = guarded(rw -> VmCollector.collect(rw, vmParams, accepted, nonScalar), row,
                    in.failures, C_VM, VmCollector.READ, tally);
            if (o != null) out.add(o);
            scan.take(row);
            members.vm(row);
        }, fault);
    }

    // ---- resource pools and Datacenters (P4: only their structure; the figures are the derived phase's)

    /** True when every resource pool was read with its parent. */
    private Boolean pools(Input in, Membership members) {
        boolean[] unreadable = new boolean[1];
        members.resetPools();
        RetrieveStats st = read(in, C_CLUSTER, new RetrieveRequest("R-RP", "ResourcePool", 500, null)
                .path("parent", PathSpec.shallow()).path("namespace", PathSpec.shallow()), row -> {
                    // a pool whose parent or namespace flag could not be read cannot be placed: the counts are withheld
                    if (!row.readable("parent") || !row.readable("namespace")) unreadable[0] = true;
                    members.pool(row);
                });
        return st != null && !unreadable[0];
    }

    /**
     * Each Datacenter with the hosts under it and its distributed port groups, a container view of each. False
     * when any part failed: a Datacenter with a part missing is incomplete, and what needs it is withheld.
     */
    private Boolean datacenters(Input in, Membership members) {
        List<MoRef[]> dcs = new ArrayList<>(); // {datacenter, network folder}
        RetrieveStats st = read(in, C_DATACENTER, new RetrieveRequest("R-DC", "Datacenter", 100, null)
                .path("networkFolder", PathSpec.shallow()), row -> {
                    members.datacenter(row.obj.value, row.name());
                    XmlNode nf = row.val("networkFolder");
                    dcs.add(new MoRef[] {row.obj, nf == null || nf.text().isEmpty() ? null
                        : new MoRef(nf.attr("type") == null ? "Folder" : nf.attr("type"), nf.text())});
                });
        if (st == null) return false;
        boolean ok = true;
        for (MoRef[] d : dcs) {
            if (stopNow(in)) return false;
            Membership.Datacenter dc = members.datacenterOf(d[0].value);
            Set<String> hosts = ConcurrentHashMap.newKeySet();
            RetrieveStats h = read(in, C_DATACENTER, new RetrieveRequest("R-DC-HOSTS", "HostSystem", 100, null)
                    .inContainer(d[0]), row -> hosts.add(row.obj.value));
            if (h != null) dc.hosts = hosts; else ok = false;
            if (d[1] == null) {
                // a Datacenter that names no network folder has no distributed port groups to read
                dc.dvPortGroups = new HashSet<>();
                continue;
            }
            Set<String> names = ConcurrentHashMap.newKeySet();
            boolean[] nameless = new boolean[1];
            RetrieveStats g = read(in, C_DATACENTER, new RetrieveRequest("R-DC-DVPG",
                    "DistributedVirtualPortgroup", 100, null).inContainer(d[1]), row -> {
                        String n = row.name();
                        if (n == null) nameless[0] = true; else names.add(n);
                    });
            if (g != null && !nameless[0]) dc.dvPortGroups = names; else ok = false;
        }
        return ok;
    }

    // ---- folders of the templates

    /** The folders the templates are in, by MoRef value; null when the read failed (counted). */
    private Map<String, String> folders(Input in, TemplateScan scan) {
        Set<String> wanted = new java.util.LinkedHashSet<>();
        for (TemplateScan.Row r : scan.rows) if (r.parent != null) wanted.add(r.parent);
        Map<String, String> out = new HashMap<>();
        if (wanted.isEmpty()) return out;
        List<MoRef> refs = new ArrayList<>();
        for (String w : wanted) refs.add(new MoRef("Folder", w));
        com.vcfcf.adapters.vcommunity.vim.RowSink sink = row -> {
            String n = row.name();
            if (n != null) out.put(row.obj.value, n);
        };
        String[] fault = new String[1];
        RetrieveStats st = read(in, TemplateCollector.COLLECTOR, folderRequest(refs), sink, fault);
        if (st == null && "ManagedObjectNotFound".equals(fault[0])) {
            // a folder went between the two reads and vCenter refused the whole list: ask for each
            // alone, so only the templates of the folder that went are without its name
            out.clear();
            st = new RetrieveStats();
            for (MoRef f : refs) {
                if (stopNow(in)) break;
                read(in, TemplateCollector.COLLECTOR, folderRequest(Collections.singletonList(f)), sink);
            }
        }
        return st == null ? null : out;
    }

    private static RetrieveRequest folderRequest(List<MoRef> refs) {
        return new RetrieveRequest("R-FO", "Folder", 100, refs);
    }

    private synchronized void suspendOptions() {
        optionsAccepted = Collections.emptyList();
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
        for (String p : wanted) probe.path(p, PathSpec.shallow());
        VimResult<RetrieveStats> r = reader(in).retrieve(probe,
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
            if (!stopNow(in) && maps.missJustifiesRead(kind, in.cycle, anyNew)) {
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
                    maps.missRead(kind, !still.isEmpty());
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
        if (!cert.isKnown() && !stopNow(in)) {
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
