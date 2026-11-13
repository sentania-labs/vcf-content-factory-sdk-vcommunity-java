package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.ops.LastPushed;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.ops.PushSet;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSession;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * One collection cycle, SDK-free (design 4.1). In this build the cycle is the
 * skeleton: it has no domain collector yet, so it does the parts every later
 * collector stands on and reports honestly on them.
 *
 * <pre>
 *   A1 session   log in or keep alive; the vCenter UUID (VCID) comes from here
 *   A2 probe     the certificate thumbprint the vCenter presents
 *   A3 lists     the check-list files, only those in use
 *   A4 binding   the vCenter's own object in VCF Operations, by VCID
 *   E  health    the Collection Health keys on that object; the self metrics
 * </pre>
 *
 * A failed login fails the cycle (the result carries the failure and the
 * cause, so an unreachable vCenter maps to DOWN). Every other failure is
 * counted in {@link Failures} and the cycle goes on with what it has: a failed
 * read sends nothing, and a value is never invented to fill the gap.
 * The class is used from one thread at a time (the collect thread).
 */
public final class CycleEngine {

    /** Wall clock, replaceable in tests. */
    public interface Clock {
        long now();
    }

    /** Every {@value} cycles the Ops resource ids are read again. */
    static final int REFRESH_EVERY = 12;
    static final String COLLECTOR_CHECK_LISTS = "check_lists";
    static final String COLLECTOR_VCENTER = "vcenter";
    static final int PROBE_TIMEOUT_MS = 10_000;

    /** What a cycle produced. */
    public static final class Result {
        /** Why the cycle failed, credential-free; null when it did not. */
        public final String failure;
        public final Throwable failureCause;
        public final CollectionHealth.Summary summary;
        public final About about;
        /** The thumbprint the vCenter presented, or null. */
        public final String presentedThumbprint;
        /** The check-list entries this cycle read. */
        public final Map<ListKind, CheckListStore.Entry> lists;

        Result(String failure, Throwable failureCause, CollectionHealth.Summary summary,
                About about, String presentedThumbprint,
                Map<ListKind, CheckListStore.Entry> lists) {
            this.failure = failure;
            this.failureCause = failureCause;
            this.summary = summary;
            this.about = about;
            this.presentedThumbprint = presentedThumbprint;
            this.lists = lists;
        }

        public Map<String, Double> metrics() {
            return CollectionHealth.selfMetrics(summary);
        }

        public String lastErrorProperty() {
            return CollectionHealth.lastError(summary);
        }
    }

    private final VCommunityConfig cfg;
    private final VimSession vim;
    private final ThumbprintProbe.Probe probe;
    private final OpsApi ops;
    private final CheckListStore store;
    private final WarnCounter warns;
    private final Log log;
    private final Clock clock;
    private final LastPushed lastPushed = new LastPushed(REFRESH_EVERY);

    private long cycleNo;
    private String vcenterId;
    private String vcenterIdFor;
    private long vcenterIdCycle;

    /**
     * @param ops null when the Suite API client could not be built; the cycle then
     *            reports that and pushes nothing
     */
    public CycleEngine(VCommunityConfig cfg, VimSession vim, ThumbprintProbe.Probe probe,
            OpsApi ops, CheckListStore store, WarnCounter warns, Clock clock) {
        this.cfg = cfg;
        this.vim = vim;
        this.probe = probe;
        this.ops = ops;
        this.store = store;
        this.warns = warns;
        this.log = warns;
        this.clock = clock;
    }

    /**
     * Run one cycle.
     *
     * @param deadlineMs when the cycle must stop starting new work (epoch ms)
     * @throws InterruptedException the thread was interrupted
     */
    public Result run(long deadlineMs) throws InterruptedException {
        final long t0 = clock.now();
        cycleNo++;
        warns.reset();
        vim.beginCycle();
        for (String w : cfg.warnings) log.warn(w);
        Failures failures = new Failures();
        CollectionHealth.Summary s = new CollectionHealth.Summary();

        // A1: the session
        VimResult<About> session = vim.ensureSession();
        if (!session.isOk()) {
            failures.add(COLLECTOR_VCENTER, FailureCause.LOGIN, cfg.host, 0, session.message());
            return finish(t0, s, failures, session.message(), session.cause(), null, null, null);
        }
        About about = session.value();
        String vcid = about == null || about.instanceUuid == null
                || about.instanceUuid.trim().isEmpty() ? null : about.instanceUuid.trim();
        if (vcid == null) {
            log.warn("vCenter answered without an instance UUID (about.instanceUuid); nothing can "
                    + "be matched onto its objects this cycle");
        }

        // A2: the certificate probe
        String presented = null;
        ThumbprintProbe.Result pr = probe.probe(cfg.asciiHost(), cfg.port, PROBE_TIMEOUT_MS);
        if (pr.ok()) {
            presented = pr.thumbprint();
        } else {
            failures.add(COLLECTOR_VCENTER, FailureCause.CERT_PROBE, cfg.host, 0, pr.failure());
        }

        // A3: the check-list files
        Map<ListKind, CheckListStore.Entry> lists = new EnumMap<>(ListKind.class);
        if (clock.now() >= deadlineMs) {
            s.deadlineHit = true;
            failures.add(COLLECTOR_CHECK_LISTS, FailureCause.DEADLINE, "check-lists", 0,
                    "the cycle deadline was reached before the check-list files were read");
            s.collectorStatus.put(COLLECTOR_CHECK_LISTS, Status.NOT_RUN);
        } else if (ops == null) {
            failures.add(COLLECTOR_CHECK_LISTS, FailureCause.checklist("all"), "check-lists", 0,
                    "the local VCF Operations Suite API is not available to this instance");
            s.collectorStatus.put(COLLECTOR_CHECK_LISTS, Status.FAILED);
        } else {
            try {
                lists = store.refresh(cfg.filesInUse(), path -> ops.get(path, "*/*"));
                accountLists(lists, failures, s);
            } catch (RuntimeException e) {
                lists = new EnumMap<>(ListKind.class);
                failures.add(COLLECTOR_CHECK_LISTS, FailureCause.checklist("all"), "check-lists", 0,
                        "reading the check-list files failed: " + e.getClass().getSimpleName());
                s.collectorStatus.put(COLLECTOR_CHECK_LISTS, Status.FAILED);
            }
        }

        // A4: the vCenter's own object
        String vcId = null;
        if (vcid != null && ops != null && !s.deadlineHit) {
            vcId = resolveVcenterObject(vcid, failures, s);
        }
        s.vcenterResolved = vcId != null;
        return finish(t0, s, failures, null, null, about, presented, lists, vcId);
    }

    private Result finish(long t0, CollectionHealth.Summary s, Failures failures, String failure,
            Throwable cause, About about, String presented, Map<ListKind, CheckListStore.Entry> lists)
            throws InterruptedException {
        return finish(t0, s, failures, failure, cause, about, presented, lists, null);
    }

    private Result finish(long t0, CollectionHealth.Summary s, Failures failures, String failure,
            Throwable cause, About about, String presented,
            Map<ListKind, CheckListStore.Entry> lists, String vcId)
            throws InterruptedException {
        failures.logSummary(log);
        // E: the health keys on the vCenter object, then the figures that depend on the push
        PushOutcome outcome = PushOutcome.NOTHING;
        boolean attempted = false;
        LastPushed.Selection selection = null;
        PushSet ps = new PushSet(log);
        if (vcId != null && ops != null && failure == null) {
            fill(s, failures, t0);
            CollectionHealth.vcenterKeys(ps, vcId, s);
            selection = lastPushed.select(ps.properties(), cycleNo);
            attempted = true;
            outcome = ops.pushBatch(selection.toSend(), ps.stats(), t0);
            if (outcome.isClean()) {
                selection.commit();
            } else {
                int n = Math.max(1, outcome.batchesFailed);
                warns.addExternal(n, "the health push to VCF Operations was not clean: " + outcome);
                failures.add(COLLECTOR_VCENTER, FailureCause.PUSH, "Collection Health", 0,
                        "the push of the health keys was not clean (" + outcome + ")");
                log.warn(FailureCause.PUSH + ": the push of the health keys was not clean ("
                        + outcome + ")");
            }
        }
        fill(s, failures, t0);
        s.pushBatchesFailed = attempted ? outcome.batchesFailed : 0;
        if (attempted && outcome.isClean()) {
            s.seriesPushed = ps.statCount();
            s.propertiesPushed = selection.toSend().values().stream().mapToInt(Map::size).sum();
        }
        s.cycleSeconds = (clock.now() - t0) / 1000.0;
        return new Result(failure, cause, s, about, presented, lists);
    }

    /** Copy the failure counters and the warning counts into the summary. */
    private void fill(CollectionHealth.Summary s, Failures failures, long t0) {
        s.readsFailed = failures.readsFailed();
        s.valuesWithheld = failures.valuesWithheld();
        s.firstFailure = failures.first();
        s.warnings = warns.count();
        s.firstWarning = warns.first();
        s.cycleSeconds = (clock.now() - t0) / 1000.0;
        for (String c : s.collectorStatus.keySet()) {
            s.collectorFailures.put(c, failures.forCollector(c));
        }
    }

    /**
     * Count what the check-list files gave. A file that could not be read or
     * parsed, or is absent, is a failed read; an entry skipped inside a file
     * that was read is a reported problem, not a failed read (the list is in
     * force). The skipped count is sent only when every file read this cycle
     * was parsed, otherwise it is unknown.
     */
    private void accountLists(Map<ListKind, CheckListStore.Entry> lists, Failures failures,
            CollectionHealth.Summary s) {
        int read = 0;
        int failed = 0;
        int inForce = 0;
        int skipped = 0;
        boolean measured = true;
        boolean anySkipped = false;
        for (CheckListStore.Entry e : lists.values()) {
            if (e.outcome == CheckListStore.Outcome.NOT_CONFIGURED) continue;
            read++;
            if (e.outcome == CheckListStore.Outcome.OK) {
                inForce++;
                skipped += e.skipped.size();
                if (!e.skipped.isEmpty()) {
                    anySkipped = true;
                    log.warn(FailureCause.checklist(e.kind.id) + ": " + e.problem);
                }
                continue;
            }
            failed++;
            if (e.lastGood) inForce++;
            if (e.outcome != CheckListStore.Outcome.ABSENT) measured = false;
            failures.add(COLLECTOR_CHECK_LISTS, FailureCause.checklist(e.kind.id), e.fileName, 0,
                    e.problem);
        }
        if (read > 0 && measured) s.checklistSkipped = skipped;
        s.collectorStatus.put(COLLECTOR_CHECK_LISTS, failed == 0 && !anySkipped ? Status.OK
                : failed >= read && inForce == 0 ? Status.FAILED : Status.PARTIAL);
    }

    /** The resource id of the vCenter's own object: cached, read again every 12th cycle. */
    private String resolveVcenterObject(String vcid, Failures failures, CollectionHealth.Summary s)
            throws InterruptedException {
        boolean known = vcenterId != null && vcid.equals(vcenterIdFor);
        boolean due = !known || cycleNo - vcenterIdCycle >= REFRESH_EVERY;
        if (!due) {
            s.objectsUnmatched = 0; // matched on an earlier cycle and still in the cache
            return vcenterId;
        }
        try {
            OpsResourceMap.VcenterObject o = OpsResourceMap.findVcenterObject(ops, vcid);
            if (o.resolved()) {
                s.objectsUnmatched = 0;
                vcenterId = o.resourceId();
                vcenterIdFor = vcid;
                vcenterIdCycle = cycleNo;
                return vcenterId;
            }
            vcenterId = null;
            s.objectsUnmatched = 1; // the lookup completed and found no single match
            log.warn(o.matches() == 0
                    ? "No VCF Operations object of kind VMwareAdapter Instance carries this "
                            + "vCenter's instance UUID; the vCenter keys are held back"
                    : o.matches() + " VCF Operations objects of kind VMwareAdapter Instance carry "
                            + "this vCenter's instance UUID (a duplicate vCenter account?); the "
                            + "vCenter keys are held back");
            return null;
        } catch (IOException | RuntimeException e) {
            failures.add(COLLECTOR_VCENTER, FailureCause.opsMap("VMwareAdapter Instance"),
                    "VMwareAdapter Instance", 0, "reading the vCenter's object from VCF "
                    + "Operations failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + bound(e.getMessage())));
            // keep the previous id if it was found for this same vCenter
            return known ? vcenterId : null;
        }
    }

    private static String bound(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
