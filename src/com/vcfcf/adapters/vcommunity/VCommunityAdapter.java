package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.http.CertificateNames;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfTester;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;
import com.vcfcf.adapters.vcommunity.health.Reachability;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;

/**
 * VCF Content Factory vCommunity: a Java port of the vCommunity management
 * pack. It reads a vCenter over vim25 and pushes the {@code vCommunity|} keys
 * onto the vCenter adapter's own objects through the Suite API (design:
 * {@code knowledge/designs/managementpacks/vcommunity-java-port-design.md}).
 *
 * <p>This build is the skeleton (phase P1): the account, its TLS modes, the
 * vCenter session, Test Connection and the cycle with its health keys and self
 * metrics. No domain collector exists yet.
 *
 * <p><b>Where the cycle runs.</b> In {@code collect()} for the one object the
 * pak discovers per account ({@link #COLLECTOR_KIND}), identified by the
 * account's own values so it exists while the vCenter is unreachable (an
 * unreachable vCenter then shows the account DOWN; the same pattern as the
 * vSAN Performance pak). The self metrics go onto the adapter instance
 * resource, which {@code rediscover} hands over each cycle.
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
            VimSoapClient vim = new VimSoapClient(cfg.hostPort(), cfg.user, cfg.password(),
                    buildTls(cfg), warns);
            SuiteApiStitcher sas = openStitcher();
            OpsPort ops = sas == null ? null : new OpsPort(sas);
            CycleEngine engine = new CycleEngine(cfg, vim, ThumbprintProbe.TLS, ops,
                    new CheckListStore(warns), warns, System::currentTimeMillis);
            if (cfg.guestTlsError != null) {
                logWarn(cfg.guestTlsError + "; guest operations stay off until it is fixed");
            }
            this.runtime = new Runtime(cfg, vim, sas, engine);
            // Never the password, the Windows password, a token or a session. // REDACT-SECRET
            logInfo("VCommunityAdapter configured: " + cfg + " suiteApi=" + (sas != null));
            if (cfg.tlsMode == VCommunityConfig.TlsMode.TRUST_ON_FIRST_USE) {
                logInfo("vCenter TLS Validation is Trust on First Use: the vCenter certificate is "
                        + "not checked before the user name and password are sent. Use Pinned "
                        + "or Enabled on a network that is not fully trusted.");
            }
        } catch (IllegalArgumentException e) {
            this.configError = e.getMessage();
            logError("VCommunityAdapter configuration is invalid: " + e.getMessage(), null);
        } catch (RuntimeException e) {
            this.configError = "the vCenter client could not be built ("
                    + e.getClass().getSimpleName() + "); see the configure ERROR in the log";
            logError("VCommunityAdapter could not build its vCenter client", e);
        } finally {
            if (old != null) release(old);
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

    /** The Suite API client, or null (logged) when this instance cannot have one. */
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

    private void release(Runtime r) {
        try {
            r.vim.close();
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
            SuiteApiStitcher sas = openStitcher();
            try {
                ConnectionTester.Report report = ConnectionTester.run(c, vim,
                        ThumbprintProbe.TLS, sas == null ? null : new OpsPort(sas),
                        new CheckListStore(log));
                // Test Connection INFO does not reach a log under the pak's package entry, and
                // the platform shows no text for a passing test: WARN is what is readable.
                logWarn("Test Connection OK: " + report.text());
            } finally {
                vim.close();
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
    }

    @Override
    protected boolean discoverOnCollect() {
        return true;
    }

    /**
     * The collector object, identified by the account's own values (the vCenter
     * Server and the four check-list file names, as stored, trimmed only), known
     * from the configuration alone: not by the vCenter's UUID, which needs a
     * login, and the object must exist when the vCenter is unreachable for the
     * failure to show as DOWN. An invalid configuration throws (the account
     * shows ERROR with its message).
     */
    @Override
    protected void enumerateResources(com.vcfcf.adapter.spi.ResourceSink sink) throws Exception {
        Runtime r = this.runtime;
        if (r == null) {
            throw new IllegalStateException(notConfigured());
        }
        sink.accept(collectorResource(r.cfg));
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
                if (!COLLECTOR_KIND.equals(rc.getResourceKind())) return;
                runCycle();
            }

            @Override
            public ResourceStatusEnum mapCollectException(Exception e) {
                return Reachability.isUnreachable(e) ? ResourceStatusEnum.RESOURCE_STATUS_DOWN
                        : ResourceStatusEnum.RESOURCE_STATUS_ERROR;
            }
        };
    }

    /** One cycle; the self metrics go onto the adapter instance, then a failure throws. */
    private void runCycle() throws Exception {
        if (isAbortRequested()) return;
        final ResourceConfig inst = cycleInstance != null ? cycleInstance : instanceResource();
        Runtime r = this.runtime;
        if (r == null) {
            String msg = notConfigured();
            if (inst != null) pushStringProperty(inst, "Collection|last_error", msg);
            throw new IllegalStateException(msg);
        }
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
        CycleEngine.Result res = r.engine.run(deadline);
        if (inst != null) {
            for (Map.Entry<String, Double> e : res.metrics().entrySet()) {
                pushMetric(inst, e.getKey(), e.getValue(), start);
            }
            pushStringProperty(inst, "Collection|last_error", res.lastErrorProperty());
        } else {
            logWarn("the adapter instance resource was not available; the self metrics were not "
                    + "pushed this cycle");
        }
        if (res.failure != null) throw new IOException(res.failure, res.failureCause);
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
        if (r != null) release(r);
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
