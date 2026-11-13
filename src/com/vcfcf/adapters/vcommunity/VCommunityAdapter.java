package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.http.CertificateNames;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfDiscoverer;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.RelationshipBuilder;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;
import com.vcfcf.adapters.vcommunity.coll.LibraryLinks;
import com.vcfcf.adapters.vcommunity.coll.OwnObject;
import com.vcfcf.adapters.vcommunity.coll.OwnObjects;
import com.vcfcf.adapters.vcommunity.health.Reachability;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.rest.LibraryReader;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.MetricKey;
import com.integrien.alive.common.adapter3.Relationships;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;

/**
 * VCF Content Factory vCommunity: a Java port of the vCommunity management
 * pack. It reads a vCenter over vim25 and pushes the {@code vCommunity|} keys
 * onto the vCenter adapter's own objects through the Suite API (design:
 * {@code knowledge/designs/managementpacks/vcommunity-java-port-design.md}).
 *
 * <p>This build reads the property keys of the vCenter's own objects (phase P2)
 * and delivers three kinds of object of its own (phase P3): content libraries,
 * the items in them, and VM templates.
 *
 * <p><b>Where the cycle runs.</b> At the top of the collection, in
 * {@link #enumerateResources}: the pak's own objects can only be named once
 * the vCenter has been read, and the platform decides what has vanished from
 * what that enumeration delivers. The collector object
 * ({@link #COLLECTOR_KIND}, one per account, identified by the account's own
 * values so it exists while the vCenter is unreachable) is delivered first,
 * and its {@code collect()} reports the cycle: the self metrics go onto the
 * adapter instance resource, which {@code rediscover} hands over each cycle,
 * and a failed cycle fails there (an unreachable vCenter then shows the
 * account DOWN; the same pattern as the vSAN Performance pak). Every own
 * object's {@code collect()} puts the values the cycle read on it.
 *
 * <p>Everything except this class and {@link OpsPort} is SDK-free and tested
 * by {@code ci/run_java_tests.sh}.
 */
public final class VCommunityAdapter extends VcfCfAdapter<VCommunityConfig> {

    /** MUST match describe.xml and adapter.yaml. */
    private static final String ADAPTER_KIND = "vcfcf_vcommunity";

    /** The per-account object the cycle runs for. */
    static final String COLLECTOR_KIND = "vCommunityCollector";
    static final String COLLECTOR_NAME = "VCF Content Factory vCommunity Collector";

    /** The default collection interval, used when the instance does not report one. */
    private static final int DEFAULT_INTERVAL_SECONDS = 300;
    /** The cycle stops starting new work this long before the next one is due. */
    private static final int DEADLINE_MARGIN_SECONDS = 60;

    /** One configuration's clients and engine, published with one volatile write. */
    private static final class Runtime {
        final VCommunityConfig cfg;
        final VimSoapClient vim;
        final SuiteApiStitcher stitcher;
        final CycleEngine engine;

        Runtime(VCommunityConfig cfg, VimSoapClient vim, SuiteApiStitcher stitcher,
                CycleEngine engine) {
            this.cfg = cfg;
            this.vim = vim;
            this.stitcher = stitcher;
            this.engine = engine;
        }
    }

    private volatile Runtime runtime;
    /** Why the last configure built nothing; every cycle fails with it. */
    private volatile String configError;
    /** The adapter instance resource of the current cycle, handed over by {@code rediscover}. */
    private volatile ResourceConfig cycleInstance;
    /** The cycle {@link #enumerateResources} ran, read by the collects that follow it; null before it has run. */
    private volatile CycleRecord cycle;
    /** The kinds the last enumeration read in full, and the thread that enumerated. */
    private volatile ReadRecord lastRead;

    /** What the enumeration's cycle produced, for the collect calls of the same collection. */
    private static final class CycleRecord {
        final CycleEngine.Result result;
        /** Credential-free text when the engine itself failed; the cycle then reports it. */
        final String unexpected;
        /** True when the cycle was not run because a stop was asked for. */
        final boolean aborted;
        final ResourceConfig instance;
        final long startMs;
        final Map<String, OwnObject> byIdentity = new HashMap<>();

        CycleRecord(CycleEngine.Result result, String unexpected, boolean aborted, ResourceConfig instance,
                long startMs) {
            this.result = result;
            this.unexpected = unexpected;
            this.aborted = aborted;
            this.instance = instance;
            this.startMs = startMs;
            if (result != null) {
                for (OwnObject o : result.own.objects()) byIdentity.put(o.identity(), o);
            }
        }
    }

    /** One enumeration's read kinds and the thread that ran it. */
    private static final class ReadRecord {
        final Thread thread;
        final Set<String> kinds;

        ReadRecord(Thread thread, Set<String> kinds) {
            this.thread = thread;
            this.kinds = kinds;
        }
    }

    public VCommunityAdapter() {
        super(ADAPTER_KIND);
    }

    public VCommunityAdapter(String adapterDir, Integer adapterInstanceId) {
        super(ADAPTER_KIND, adapterDir, adapterInstanceId);
    }

    // ------------------------------------------------------------------
    // Configure
    // ------------------------------------------------------------------

    @Override
    protected void configureAdapter(ResourceStatus status, ResourceConfig rc) {
        Runtime old = this.runtime;
        this.runtime = null;
        this.configError = null;
        try {
            VCommunityConfig cfg = buildConfig(rc);
            this.config = cfg;
            Log log = pakLog();
            WarnCounter warns = new WarnCounter(log);
            // one TLS posture for the account: the vim25 session and the REST session of the content
            // libraries both go through it, so neither can bypass the mode (design 2.5)
            VcenterTls tls = buildTls(cfg);
            VimSoapClient vim = new VimSoapClient(cfg.hostPort(), cfg.user, cfg.password(), tls, warns);
            LibraryReader libraries = new LibraryReader(cfg.hostPort(), cfg.user, cfg.password(), tls,
                    warns);
            SuiteApiStitcher sas = openStitcher();
            OpsPort ops = sas == null ? null : new OpsPort(sas);
            CycleEngine engine = new CycleEngine(cfg, vim, ThumbprintProbe.TLS, ops,
                    new CheckListStore(warns), warns, System::currentTimeMillis, libraries);
            if (cfg.guestTlsError != null) {
                logWarn(cfg.guestTlsError + "; guest operations stay off until it is fixed");
            }
            this.runtime = new Runtime(cfg, vim, sas, engine);
            // Never the password, the Windows password, a token or a session. // REDACT-SECRET
            logInfo("VCommunityAdapter configured: " + cfg + " suiteApi=" + (sas != null));
            if (cfg.tlsMode == VCommunityConfig.TlsMode.TRUST_ON_FIRST_USE) {
                logInfo("vCenter TLS Validation is Trust on First Use: this account sends its "
                        + "credentials to whatever answers, before any certificate check. On a "
                        + "network that is not fully trusted, set Pinned or Enabled.");
            }
        } catch (IllegalArgumentException e) {
            this.configError = e.getMessage();
            logError("VCommunityAdapter configuration is invalid: " + e.getMessage(), null);
        } catch (RuntimeException e) {
            this.configError = "the vCenter client could not be built ("
                    + e.getClass().getSimpleName() + "); see the configure ERROR in the log";
            logError("VCommunityAdapter could not build its vCenter client", e);
        } finally {
            if (old != null) release(old, "reconfigure");
        }
    }

    private VCommunityConfig buildConfig(ResourceConfig rc) {
        return VCommunityConfig.parse(k -> getIdentifier(rc, k),
                getCredentialField(rc, VCommunityConfig.C_USER),
                getCredentialField(rc, VCommunityConfig.C_PASSWORD), // REDACT-SECRET
                getCredentialField(rc, VCommunityConfig.C_WIN_USER),
                getCredentialField(rc, VCommunityConfig.C_WIN_PASSWORD)); // REDACT-SECRET
    }

    /** The TLS posture of the account's mode (design 2.5). */
    private VcenterTls buildTls(VCommunityConfig cfg) {
        SSLContext platform = null;
        if (cfg.tlsMode == VCommunityConfig.TlsMode.ENABLED) {
            platform = getPlatformSslContext(cfg.dialledHost());
        }
        return VcenterTls.create(cfg.tlsMode, cfg.thumbprint, platform, CertificateNames::secure);
    }

    /** The Suite API client, or null (logged at WARN: nothing can be pushed) when this instance cannot have one. */
    private SuiteApiStitcher openStitcher() {
        try {
            return SuiteApiStitcher.create(this, componentLogger(SuiteApiStitcher.class));
        } catch (RuntimeException e) {
            logWarn("the local VCF Operations Suite API is unavailable to this instance ("
                    + e.getClass().getSimpleName() + "); nothing can be pushed onto vCenter "
                    + "objects until it is");
            return null;
        }
    }

    private void release(Runtime r, String reason) {
        try {
            r.vim.close(reason);
        } catch (RuntimeException ignored) {
            // best effort
        }
        if (r.stitcher != null) {
            try {
                r.stitcher.discard();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
    }

    // ------------------------------------------------------------------
    // Validate Connection: the certificate review
    // ------------------------------------------------------------------

    /** Offered only in the Enabled mode; the other two modes do not use the platform's trust. */
    @Override
    protected List<String> certificateCheckUrls(ResourceConfig rc) {
        String url = VCommunityConfig.certificateCheckUrl(k -> getIdentifier(rc, k));
        return url == null ? Collections.emptyList() : Collections.singletonList(url);
    }

    @Override
    protected String certificateFailureMessage(Throwable failure, ResourceConfig rc,
            String defaultMessage) {
        return defaultMessage + " The vCenter TLS Validation setting (Advanced Settings) chooses "
                + "how the vCenter certificate is treated: Trust on First Use accepts any "
                + "certificate, Enabled needs a certificate this collector trusts (accept it "
                + "here), Pinned compares the certificate with the vCenter Thumbprint.";
    }

    // ------------------------------------------------------------------
    // Test Connection: self-contained (configure has not run)
    // ------------------------------------------------------------------

    @Override
    protected VcfCfTester<VCommunityConfig> getTester() {
        return (cfg, http, param) -> {
            ResourceConfig rc = testResourceConfig(param);
            if (rc == null) {
                throw new Exception("Test Connection: no adapter instance configuration on "
                        + "the request");
            }
            VCommunityConfig c = buildConfig(rc);
            WarnCounter log = new WarnCounter(pakLog());
            VimSoapClient vim = new VimSoapClient(c.hostPort(), c.user, c.password(), buildTls(c),
                    log);
            // During a test the account is not configured, so the client usually cannot be built
            // (no per-instance credential, finding 4 of the 0.0.0.2 live run). Only that known
            // case is a plain line; any other failure is reported as a warning, with its class.
            SuiteApiStitcher sas = null;
            String notBuilt = null;
            boolean expected = false;
            try {
                sas = SuiteApiStitcher.create(this, componentLogger(SuiteApiStitcher.class));
            } catch (RuntimeException e) {
                notBuilt = e.getClass().getSimpleName();
                expected = ConnectionTester.isNoCredentialInTest(e);
            }
            try {
                ConnectionTester.Report report = ConnectionTester.run(c, vim,
                        ThumbprintProbe.TLS, sas == null ? null : new OpsPort(sas),
                        new CheckListStore(log), notBuilt, expected);
                // The success line is INFO, which is not written anywhere during a Test Connection:
                // no instance log exists yet, and under the pak's own conf/logging.properties
                // entry only WARN and above reach collector.log (SKILL.md, Logging). Our code
                // never logs success at WARN. What the admin must act on is WARN, one line each.
                logInfo("Test Connection OK: " + report.text());
                for (String a : report.attentionLines()) logWarn("Test Connection: " + a);
            } finally {
                vim.close("test finished");
                if (sas != null) {
                    try {
                        sas.discard();
                    } catch (RuntimeException ignored) {
                        // best effort
                    }
                }
            }
        };
    }

    private static ResourceConfig testResourceConfig(TestParam param) {
        if (param == null) return null;
        AdapterConfig ac = param.getAdapterConfig();
        return ac == null ? null : ac.getAdapterInstResource();
    }

    // ------------------------------------------------------------------
    // Discovery: the collector object
    // ------------------------------------------------------------------

    @Override
    protected void onCycleStart() {
        cycleInstance = null;
        cycle = null;
    }

    @Override
    protected boolean discoverOnCollect() {
        return true;
    }

    /**
     * Discovery only needs the collector object: it is what the first collection runs for.
     * The own objects come from the cycle, which {@link #enumerateResources} runs, not from a
     * discovery pass (which must not log in and read a whole vCenter).
     */
    @Override
    @SuppressWarnings("rawtypes")
    protected VcfCfDiscoverer getDiscoverer() {
        return (cfg, http, param, dr) -> {
            Runtime r = this.runtime;
            if (r == null) throw new IllegalStateException(notConfigured());
            dr.addResource(collectorResource(r.cfg));
        };
    }

    /**
     * The collector object, then the cycle, then the objects the cycle read.
     *
     * <p>The collector is identified by the account's own values (the vCenter Server and the
     * four check-list file names, as stored, trimmed only), known from the configuration alone:
     * not by the vCenter's UUID, which needs a login, and the object must exist when the vCenter
     * is unreachable for the failure to show as DOWN. An invalid configuration throws (the
     * account shows ERROR with its message).
     *
     * <p>The cycle runs here, between the two, because the own objects are what it finds; a
     * cycle that fails still delivers the collector, and delivers no own object and declares no
     * kind read, so nothing is marked as vanished on the strength of a failed read.
     */
    @Override
    protected void enumerateResources(com.vcfcf.adapter.spi.ResourceSink sink) throws Exception {
        lastRead = null;
        Runtime r = this.runtime;
        if (r == null) {
            throw new IllegalStateException(notConfigured());
        }
        sink.accept(collectorResource(r.cfg));
        CycleRecord rec = executeCycle(r);
        this.cycle = rec;
        Set<String> kinds = new HashSet<>();
        if (rec.result != null) {
            for (OwnObject o : rec.result.own.objects()) {
                ResourceConfig rc = ownResource(o);
                renameIfChanged(rc);
                sink.accept(rc);
            }
            kinds.addAll(rec.result.own.kindsRead());
        }
        // declared only once the enumeration has finished: a throw above leaves null (marks nothing)
        lastRead = new ReadRecord(Thread.currentThread(), kinds);
    }

    /**
     * The kinds this cycle's enumeration read in full (the framework counts a miss for an object
     * of a kind named here that was not delivered, and after 12 complete cycles marks it
     * not existing). {@code Content Library} and {@code Library Item} are named only when the
     * list, every library and every item were read; {@code VM Template} only when the virtual
     * machine read finished and left no VM of unknown kind. See {@link OwnObjects}.
     */
    @Override
    protected Set<String> kindsReadThisCycle() {
        ReadRecord rr = lastRead;
        // only the thread that enumerated speaks for this cycle
        return rr != null && rr.thread == Thread.currentThread() ? rr.kinds : null;
    }

    /** The collector object is delivered every cycle and is never marked. */
    @Override
    protected Set<String> notExistingExcludedKinds() {
        return Collections.singleton(COLLECTOR_KIND);
    }

    /**
     * A known object takes the name the cycle now reports, as the vCenter adapter's own objects
     * do (a template or a library renamed in vCenter is renamed here). Without it the platform
     * keeps the first name for good, and the renamed object would also look absent to the
     * vanished-object count until it did. Identity is untouched: it is the identifiers.
     */
    @Override
    public boolean isResourceRenameAllowed() {
        return true;
    }

    /**
     * The SDK compares a resource's reported name with the stored one only inside
     * {@code getMonitoringResource(ResourceKey)}; resources registered through the collect
     * result never make that comparison, so each own key is looked up here. A new object (not yet
     * in the cache) is a no-op; a failed lookup costs only the rename.
     */
    private void renameIfChanged(ResourceConfig rc) {
        try {
            getMonitoringResource(rc.getResourceKey());
        } catch (RuntimeException e) {
            logWarn("Resource rename lookup failed (a renamed object may keep its first name): "
                    + e.getClass().getSimpleName());
        }
    }

    private static ResourceConfig ownResource(OwnObject o) {
        ResourceKey key = new ResourceKey(o.name, o.kind, ADAPTER_KIND);
        for (Map.Entry<String, String> id : o.identifiers().entrySet()) {
            key.addIdentifier(new ResourceIdentifierConfig(id.getKey(), id.getValue(), true));
        }
        return new ResourceConfig(key);
    }

    /** The identifiers of an own kind, in the order its objects list them (describe.xml). */
    private static String[] identifierKeys(String kind) {
        switch (kind) {
            case OwnObjects.LIBRARY: return new String[] {"Library ID"};
            case OwnObjects.ITEM: return new String[] {"Item ID"};
            case OwnObjects.TEMPLATE: return new String[] {"VMEntityObjectID", "VMEntityVCID"};
            default: return null;
        }
    }

    private static ResourceConfig collectorResource(VCommunityConfig cfg) {
        ResourceKey key = new ResourceKey(COLLECTOR_NAME, COLLECTOR_KIND, ADAPTER_KIND);
        key.addIdentifier(new ResourceIdentifierConfig("vcenter", cfg.host, true));
        key.addIdentifier(new ResourceIdentifierConfig("config_files", cfg.fileIdentityText(), true));
        return new ResourceConfig(key);
    }

    private String notConfigured() {
        String invalid = configError;
        return invalid != null ? "the adapter instance configuration is invalid: " + invalid
                : "the adapter instance is not configured";
    }

    // ------------------------------------------------------------------
    // Collection
    // ------------------------------------------------------------------

    @Override
    protected VcfCfCollector<VCommunityConfig> getCollector() {
        return new VcfCfCollector<VCommunityConfig>() {

            /** Only to receive the adapter instance resource; no work, never throws. */
            @Override
            public boolean needsRediscovery(VCommunityConfig cfg) {
                return true;
            }

            @Override
            public void rediscover(VCommunityConfig cfg, ManagedHttpClient http,
                    ResourceConfig adapterInst, AdapterBase adapter) {
                cycleInstance = adapterInst;
            }

            @Override
            public void collect(VCommunityConfig cfg, ManagedHttpClient http, ResourceConfig rc,
                    List<MetricData> out, AdapterBase adapter) throws Exception {
                if (COLLECTOR_KIND.equals(rc.getResourceKind())) {
                    reportCycle();
                } else {
                    collectOwn(rc, out);
                }
            }

            /**
             * The full child set of every library whose items were all read, once per cycle,
             * anchored on the collector object (always present, always collected). A library read
             * in part is not in it, so its links stay as they were.
             */
            @Override
            public Relationships collectRelationships(VCommunityConfig cfg, ResourceConfig rc) {
                if (!COLLECTOR_KIND.equals(rc.getResourceKind())) return null;
                CycleRecord rec = cycle;
                if (rec == null || rec.result == null || rec.result.failure != null) return null;
                try {
                    return buildRelationships(rec.result.own);
                } catch (RuntimeException e) {
                    logWarn("the library relationships could not be built (" + e.getClass().getSimpleName()
                            + "); they stay as they were");
                    return null;
                }
            }

            @Override
            public ResourceStatusEnum mapCollectException(Exception e) {
                return Reachability.isUnreachable(e) ? ResourceStatusEnum.RESOURCE_STATUS_DOWN
                        : ResourceStatusEnum.RESOURCE_STATUS_ERROR;
            }
        };
    }

    /**
     * Run the cycle (the one place it runs) and keep what it produced for the collects that
     * follow. The engine's own failures are returned in the record, not thrown, so the collector
     * object is still delivered and the failure is reported where it belongs; only an interrupt
     * passes through.
     */
    private CycleRecord executeCycle(Runtime r) throws InterruptedException {
        final ResourceConfig inst = cycleInstance != null ? cycleInstance : instanceResource();
        if (isAbortRequested()) return new CycleRecord(null, null, true, inst, System.currentTimeMillis());
        int intervalSec = DEFAULT_INTERVAL_SECONDS;
        try {
            Integer i = inst == null ? Integer.valueOf(getMonitoringIntervalInSec())
                    : inst.getIntervalInSec();
            if (i != null && i > 0) intervalSec = i;
        } catch (RuntimeException ignored) {
            // the default interval applies
        }
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(30, intervalSec - DEADLINE_MARGIN_SECONDS) * 1000L;
        try {
            return new CycleRecord(r.engine.run(deadline), null, false, inst, start);
        } catch (RuntimeException e) {
            logWarn("the collection cycle failed unexpectedly", e);
            return new CycleRecord(null, "the collection cycle failed unexpectedly ("
                    + e.getClass().getSimpleName() + "); see the log", false, inst, start);
        }
    }

    /** The collector object's collect: report the cycle, the self metrics first, then a failure throws. */
    private void reportCycle() throws Exception {
        if (isAbortRequested()) return;
        CycleRecord rec = this.cycle;
        if (rec == null) {
            final ResourceConfig inst = cycleInstance != null ? cycleInstance : instanceResource();
            String msg = runtime == null ? notConfigured()
                    : "the collection cycle did not run; see the log";
            if (inst != null) pushStringProperty(inst, "Collection|last_error", msg);
            throw new IllegalStateException(msg);
        }
        if (rec.aborted) return;
        final ResourceConfig inst = rec.instance;
        if (rec.result == null) {
            if (inst != null) pushStringProperty(inst, "Collection|last_error", rec.unexpected);
            throw new IllegalStateException(rec.unexpected);
        }
        CycleEngine.Result res = rec.result;
        if (inst != null) {
            for (Map.Entry<String, Double> e : res.metrics().entrySet()) {
                pushMetric(inst, e.getKey(), e.getValue(), rec.startMs);
            }
            pushStringProperty(inst, "Collection|last_error", res.lastErrorProperty());
        } else {
            logWarn("the adapter instance resource was not available; the self metrics were not "
                    + "pushed this cycle");
        }
        if (res.failure != null) throw new IOException(res.failure, res.failureCause);
    }

    /** An own object's collect: the values the cycle read for it, none when it read none. */
    private void collectOwn(ResourceConfig rc, List<MetricData> out) {
        CycleRecord rec = this.cycle;
        String kind = rc.getResourceKind();
        String[] keys = kind == null ? null : identifierKeys(kind);
        if (rec == null || rec.result == null || keys == null) return;
        Map<String, String> ids = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            String v = getIdentifier(rc, k);
            if (v == null) return;
            ids.put(k, v);
        }
        OwnObject o = rec.byIdentity.get(OwnObject.identity(kind, ids));
        if (o == null) return; // not read this cycle (a failed read, or gone): nothing is sent
        long ts = rec.startMs;
        for (Map.Entry<String, String> p : o.props().entrySet()) {
            out.add(new MetricData(new MetricKey(true, p.getKey()), ts, p.getValue()));
        }
        for (Map.Entry<String, Double> m : o.stats().entrySet()) {
            out.add(new MetricData(new MetricKey(m.getKey()), ts, m.getValue()));
        }
    }

    /** Library > Library Item (design 3.4): the only relationship written, one full child set per library. */
    private Relationships buildRelationships(OwnObjects own) {
        int[] skipped = new int[1];
        List<LibraryLinks> sets = own.linksWithin(getMaxRelationshipsPerCycle(), skipped);
        if (skipped[0] > 0) {
            logWarn(skipped[0] + " content library child set(s) left out: together they exceed the "
                    + getMaxRelationshipsPerCycle() + " relationships a cycle may send; those libraries "
                    + "keep the links they have");
        }
        if (sets.isEmpty()) return null;
        RelationshipBuilder rb = new RelationshipBuilder(ADAPTER_KIND, getMaxRelationshipsPerCycle());
        for (LibraryLinks l : sets) {
            ResourceKey lib = rb.resource(OwnObjects.LIBRARY, l.libraryName, "Library ID", l.libraryId);
            for (LibraryLinks.Child c : l.children()) {
                rb.parent(lib, rb.resource(OwnObjects.ITEM, c.name, "Item ID", c.id));
            }
        }
        return rb.build();
    }

    /** {@code AdapterBase.getAdapterInstResource()}, or null. */
    private ResourceConfig instanceResource() {
        try {
            return getAdapterInstResource();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onDiscard() {
        Runtime r = this.runtime;
        this.runtime = null;
        if (r != null) release(r, "stop");
        super.onDiscard();
    }

    // ------------------------------------------------------------------
    // Port to the SDK-free classes: logging
    // ------------------------------------------------------------------

    /**
     * The pak's own log lines, through the adapter's {@code logInfo} /
     * {@code logWarn} / {@code logError}: those reach the per-instance log
     * where a helper's own logger may not (vSAN Performance build 9).
     */
    private Log pakLog() {
        return new Log() {
            @Override public void info(String m) { logInfo(m); }
            @Override public void warn(String m) { logWarn(m); }
            @Override public void error(String m) { logError(m, null); }
        };
    }
}
