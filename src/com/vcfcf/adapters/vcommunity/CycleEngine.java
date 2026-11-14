package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.coll.DerivedPhase;
import com.vcfcf.adapters.vcommunity.coll.LibraryCollector;
import com.vcfcf.adapters.vcommunity.coll.OwnObject;
import com.vcfcf.adapters.vcommunity.coll.OwnObjects;
import com.vcfcf.adapters.vcommunity.coll.PropertyCollection;
import com.vcfcf.adapters.vcommunity.coll.TemplateCollector;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.kpi.MonsterVm;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.ConfigFileFetch;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.ops.LastPushed;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.ops.PushSet;
import com.vcfcf.adapters.vcommunity.rest.LibraryData;
import com.vcfcf.adapters.vcommunity.rest.LibrarySource;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimReader;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSession;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *   B  reads     the property keys of clusters, hosts, virtual machines, datastores
 *                and the vCenter itself ({@link PropertyCollection}, phase P2); beside
 *                it, on a thread of its own, the content libraries (REST, phase P3)
 *   C  own       the pak's own objects: libraries, library items and VM templates,
 *                with the kinds that were read in full ({@link OwnObjects})
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
        /** The objects of the pak's own kinds this cycle read; empty when it read none. */
        public final OwnObjects own;
        /** The cycle's start, the sample time of every value of an own object. */
        public final long startMs;

        Result(String failure, Throwable failureCause, CollectionHealth.Summary summary,
                About about, String presentedThumbprint,
                Map<ListKind, CheckListStore.Entry> lists, OwnObjects own, long startMs) {
            this.failure = failure;
            this.failureCause = failureCause;
            this.summary = summary;
            this.about = about;
            this.presentedThumbprint = presentedThumbprint;
            this.lists = lists;
            this.own = own;
            this.startMs = startMs;
        }

        /**
         * What a failed cycle fails every object of the account with: the one exception, carrying the
         * credential-free text and the cause (so an unreachable vCenter maps to DOWN). Null when the
         * cycle did not fail.
         */
        public IOException failureException() {
            return failure == null ? null : new IOException(failure, failureCause);
        }

        public Map<String, Double> metrics() {
            return CollectionHealth.selfMetrics(summary);
        }

        public String failureCausesProperty() {
            return CollectionHealth.failureCauses(summary);
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
    private final PropertyCollection properties;
    private final DerivedPhase derived;
    private final LibrarySource libraries;

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
        this(cfg, vim, probe, ops, store, warns, clock, null);
    }

    /**
     * @param libraries the content libraries of the vCenter, or null when they are not read (no
     *                  Library collector status is then reported)
     */
    public CycleEngine(VCommunityConfig cfg, VimSession vim, ThumbprintProbe.Probe probe,
            OpsApi ops, CheckListStore store, WarnCounter warns, Clock clock, LibrarySource libraries) {
        this.libraries = libraries;
        this.cfg = cfg;
        this.vim = vim;
        this.probe = probe;
        this.ops = ops;
        this.store = store;
        this.warns = warns;
        this.log = warns;
        this.clock = clock;
        this.properties = new PropertyCollection(warns, clock::now);
        this.derived = new DerivedPhase(warns, clock::now);
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
                lists = store.refresh(cfg.filesInUse(), path -> ConfigFileFetch.fetch(ops, path));
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

        // B: the content libraries start reading on a thread of their own, then the property keys
        LibraryRun libs = startLibraries(deadlineMs, s, failures);
        try {
            PushSet ps = new PushSet(log);
            PropertyCollection.Result p2 = null;
            VimReader reader = vim.reader();
            if (reader != null) {
                if (s.deadlineHit || clock.now() >= deadlineMs) {
                    s.deadlineHit = true;
                    skipProperties(s, Status.NOT_RUN);
                } else if (vcid == null || ops == null) {
                    if (vcid != null) {
                        failures.add(COLLECTOR_VCENTER, FailureCause.opsMap("all"), "VCF Operations", 0,
                                "the local VCF Operations Suite API is not available; no property is sent");
                    } else {
                        failures.add(COLLECTOR_VCENTER, FailureCause.opsMap("all"), "vCenter UUID", 0,
                                "the vCenter instance UUID is unknown; its objects cannot be matched");
                    }
                    skipProperties(s, Status.SKIPPED);
                } else {
                    PropertyCollection.Input in = new PropertyCollection.Input();
                    in.vim = reader;
                    in.ops = ops;
                    in.about = about;
                    in.vcid = vcid;
                    in.vcenterResourceId = vcId;
                    in.lists = lists;
                    in.presentedThumbprint = presented;
                    in.cycle = cycleNo;
                    in.startMs = t0;
                    in.deadlineMs = deadlineMs;
                    in.ps = ps;
                    in.failures = failures;
                    p2 = properties.run(in);
                    s.collectorStatus.putAll(p2.status);
                    if (p2.deadlineHit) s.deadlineHit = true;
                    // the VM Options entries skipped each cycle are skipped entries of a list too
                    if (s.checklistSkipped != null && p2.optionsSkipped != null) s.checklistSkipped += p2.optionsSkipped;
                    // D: the derived metrics, from the membership those reads gave and what Operations holds
                    DerivedPhase.Input d = new DerivedPhase.Input();
                    d.ops = ops;
                    d.vcid = vcid;
                    d.vcenterResourceId = vcId;
                    d.members = p2.membership;
                    d.opsMap = kind -> properties.opsMap(kind, vcid);
                    d.markStale = properties::markStale;
                    d.periodHours = cfg.periodHours;
                    d.checkHour = cfg.checkHour;
                    d.checkMinute = cfg.checkMinute;
                    d.worstPercent = cfg.worstPercent;
                    d.monster = MonsterVm.of(cfg.monsterCpuPercent, cfg.monsterMemPercent, cfg.monsterMemMinGb);
                    d.cycle = cycleNo;
                    d.startMs = t0;
                    d.deadlineMs = deadlineMs;
                    d.ps = ps;
                    d.failures = failures;
                    DerivedPhase.Result dr = derived.run(d);
                    for (Map.Entry<String, Status> e : dr.status.entrySet()) {
                        // a collector the deadline kept from running stays "not run", whatever it found missing after
                        if (s.collectorStatus.get(e.getKey()) != Status.NOT_RUN) s.collectorStatus.put(e.getKey(), e.getValue());
                    }
                    if (dr.deadlineHit) s.deadlineHit = true;
                    // the count is whole only when the vCenter lookup and every kind's read and map completed
                    s.objectsUnmatched = s.objectsUnmatched == null || p2.unmatched == null ? null
                            : Integer.valueOf(s.objectsUnmatched + p2.unmatched);
                }
            }
            // C: the pak's own objects
            OwnObjects own = OwnObjects.none();
            if (reader != null) own.merge(templates(p2, vcid, failures, s));
            own.merge(libs.finish(deadlineMs, failures, s));
            for (OwnObject o : own.objects()) failures.addWithheld(o.withheld());
            guardEmpty(own);
            return finish(t0, s, failures, null, null, about, presented, lists, vcId, ps, p2, own);
        } finally {
            libs.close();
        }
    }

    // ------------------------------------------------------------------ own objects

    /** The content libraries being read beside the property phase; {@link #close} releases the thread. */
    private final class LibraryRun implements AutoCloseable {
        private final ExecutorService pool;
        private final Future<LibraryData> future;

        LibraryRun(ExecutorService pool, Future<LibraryData> future) {
            this.pool = pool;
            this.future = future;
        }

        /** What the read gave, as objects; nothing (and a status) when it did not finish. */
        OwnObjects finish(long deadlineMs, Failures failures, CollectionHealth.Summary s)
                throws InterruptedException {
            if (future == null) return OwnObjects.none(); // not read in this cycle (status already set)
            LibraryData d;
            try {
                // the reader stops asking at the deadline itself; this only guards a call that hangs
                long left = Math.max(0, deadlineMs - clock.now()) + LIBRARY_GRACE_MS;
                d = future.get(left, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                s.deadlineHit = true;
                failures.add(LibraryCollector.COLLECTOR, FailureCause.DEADLINE, "content libraries", 0,
                        "the cycle deadline was reached before the content libraries were read");
                s.collectorStatus.put(LibraryCollector.COLLECTOR, Status.NOT_RUN);
                return OwnObjects.none();
            } catch (ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                failures.add(LibraryCollector.COLLECTOR, FailureCause.REST_LIST, "content libraries", 0,
                        "reading the content libraries failed unexpectedly (" + c.getClass().getSimpleName() + ")");
                s.collectorStatus.put(LibraryCollector.COLLECTOR, Status.FAILED);
                return OwnObjects.none();
            }
            LibraryCollector.Out out = LibraryCollector.collect(d, failures);
            s.collectorStatus.put(LibraryCollector.COLLECTOR, out.status);
            if (d.deadlineHit) s.deadlineHit = true;
            return out.own;
        }

        @Override
        public void close() {
            if (pool != null) pool.shutdownNow();
        }
    }

    /** A kind that had objects and reads as none this many cycles running is believed to be gone. */
    static final int EMPTY_CYCLES_BEFORE_GONE = 12;
    private final Map<String, Boolean> hadObjects = new java.util.HashMap<>();
    private final Map<String, Integer> emptyRun = new java.util.HashMap<>();

    /**
     * W3: a successful read that finds none of a kind the engine has seen objects of is what a
     * narrowed account role looks like (the API lists only what the account may see), and upstream
     * never deletes. So a kind that drops to none is not declared read in full until it has read as none
     * {@value #EMPTY_CYCLES_BEFORE_GONE} cycles in a row; the platform then waits its own 12 cycles
     * before it marks anything. The memory is per adapter instance and starts empty: after a restart a
     * kind that is already none is believed at once.
     */
    private void guardEmpty(OwnObjects own) {
        for (String kind : new String[] {OwnObjects.LIBRARY, OwnObjects.ITEM, OwnObjects.TEMPLATE}) {
            if (!own.kindsRead().contains(kind)) continue; // not read this cycle: nothing learnt
            if (own.count(kind) > 0) {
                hadObjects.put(kind, Boolean.TRUE);
                emptyRun.put(kind, 0);
            } else if (Boolean.TRUE.equals(hadObjects.get(kind))) {
                int run = emptyRun.merge(kind, 1, Integer::sum);
                if (run < EMPTY_CYCLES_BEFORE_GONE) {
                    own.unread(kind);
                    if (run == 1) {
                        log.info("The " + kind + " read found none, where there were some. If the account's "
                                + "role was narrowed, grant it back; they are not marked as gone unless this "
                                + "stays so for " + EMPTY_CYCLES_BEFORE_GONE + " collections.");
                    }
                }
            }
        }
    }

    /** Wait this long past the deadline for a library read that is still finishing its last call. */
    static final long LIBRARY_GRACE_MS = 5_000;

    private LibraryRun startLibraries(long deadlineMs, CollectionHealth.Summary s, Failures failures) {
        if (libraries == null) return new LibraryRun(null, null);
        if (s.deadlineHit || clock.now() >= deadlineMs) {
            s.deadlineHit = true;
            failures.add(LibraryCollector.COLLECTOR, FailureCause.DEADLINE, "content libraries", 0,
                    "the cycle deadline was reached before the content libraries were read");
            s.collectorStatus.put(LibraryCollector.COLLECTOR, Status.NOT_RUN);
            return new LibraryRun(null, null);
        }
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vcommunity-library");
            t.setDaemon(true);
            return t;
        });
        return new LibraryRun(pool, pool.submit(() -> libraries.read(deadlineMs, clock::now)));
    }

    /** The VM Template objects the VM read found, or nothing and the reason in the collector's status. */
    private OwnObjects templates(PropertyCollection.Result p2, String vcid, Failures failures,
            CollectionHealth.Summary s) {
        if (p2 == null) return OwnObjects.none(); // the property phase did not run: its status says why
        if (p2.templates == null) {
            s.collectorStatus.put(TemplateCollector.COLLECTOR,
                    p2.status.get(PropertyCollection.C_VM) == Status.NOT_RUN ? Status.NOT_RUN : Status.FAILED);
            return OwnObjects.none();
        }
        TemplateCollector.Out t = TemplateCollector.collect(p2.templates, vcid, p2.datastoreNames,
                p2.folderNames, failures);
        s.collectorStatus.put(TemplateCollector.COLLECTOR, t.status);
        return t.own;
    }

    private static void skipProperties(CollectionHealth.Summary s, Status st) {
        for (String c : new String[] {PropertyCollection.C_CLUSTER, PropertyCollection.C_HOST,
            PropertyCollection.C_VM, PropertyCollection.C_DATASTORE, PropertyCollection.C_VCENTER,
            TemplateCollector.COLLECTOR}) {
            s.collectorStatus.put(c, st);
        }
    }

    private Result finish(long t0, CollectionHealth.Summary s, Failures failures, String failure,
            Throwable cause, About about, String presented, Map<ListKind, CheckListStore.Entry> lists)
            throws InterruptedException {
        return finish(t0, s, failures, failure, cause, about, presented, lists, null,
                new PushSet(log), null, OwnObjects.none());
    }

    private Result finish(long t0, CollectionHealth.Summary s, Failures failures, String failure,
            Throwable cause, About about, String presented,
            Map<ListKind, CheckListStore.Entry> lists, String vcId, PushSet ps,
            PropertyCollection.Result p2, OwnObjects own) throws InterruptedException {
        failures.logSummary(log);
        // E: the health keys on the vCenter object, then the figures that depend on the push
        PushOutcome outcome = PushOutcome.NOTHING;
        boolean attempted = false;
        LastPushed.Selection selection = null;
        if (ops != null && failure == null && (vcId != null || !ps.isEmpty())) {
            fill(s, failures, t0);
            if (vcId != null) CollectionHealth.vcenterKeys(ps, vcId, s);
            selection = lastPushed.select(ps.properties(), cycleNo);
            attempted = true;
            outcome = ops.pushBatch(selection.toSend(), ps.stats(), t0);
            if (outcome.isClean()) {
                selection.commit();
                if (p2 != null) p2.commit();
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
        log.info(summaryLine(cycleNo, s, failure));
        return new Result(failure, cause, s, about, presented, lists, own, t0);
    }

    /**
     * The one line a cycle leaves at INFO: how long, what it matched and pushed,
     * how much failed. No object names, no values, no credential; a failure text
     * is the credential-free one the cycle itself reports.
     */
    static String summaryLine(long cycleNo, CollectionHealth.Summary s, String failure) {
        StringBuilder b = new StringBuilder("Collection cycle ").append(cycleNo)
                .append(failure == null ? " finished in " : " failed after ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", s.cycleSeconds)).append(" s: ");
        if (failure != null) b.append(Failures.bound(failure)).append("; ");
        b.append("vCenter object matched=").append(s.vcenterResolved ? "yes" : "no")
                .append(", objects not matched=").append(count(s.objectsUnmatched))
                .append(", series pushed=").append(count(s.seriesPushed))
                .append(", properties pushed=").append(count(s.propertiesPushed))
                .append(", reads failed=").append(s.readsFailed)
                .append(", values withheld=").append(s.valuesWithheld)
                .append(", warnings=").append(s.warnings);
        if (s.deadlineHit) b.append(", deadline reached");
        return b.toString();
    }

    private static String count(Integer n) {
        return n == null ? "n/a" : n.toString();
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
            String causes = failures.causesOf(c);
            if (causes != null) s.collectorCauses.put(c, causes);
        }
        s.allCauses = failures.allCauses();
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
        s = Failures.bound(s); // one line
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
