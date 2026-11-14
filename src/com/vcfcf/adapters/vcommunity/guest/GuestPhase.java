package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.ops.PushSet;
import com.vcfcf.adapters.vcommunity.vim.VimReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The guest phase of a cycle (design 8.1): asks the Windows VMs in turn, four at a time, until the cycle's
 * time is up, and turns what comes back into properties and events. One VM's failure is its own: it is counted
 * with its cause and does not touch another VM, the properties or the cycle. No VM is started after the cycle's
 * deadline; the hard stop after it cuts the rest, and each cut VM removes its directory.
 *
 * <p>It keeps small memories across cycles, per adapter instance and in memory only: the cursor (who is next), the
 * events sent lately ({@link EventMemory}), when each VM's event log was last looked at, the directories a guest could
 * not remove, and the protection of the Windows account (DV-97):
 * <ul>
 *   <li>Until a logon has worked in this run of the adapter, one VM is asked alone, and after a refusal no other VM is
 *       started in that collection. A refusal counts; the {@value #REFUSALS_BEFORE_PAUSE}nd one pauses the phase for
 *       {@code PAUSE_MS} (24 hours), or until the account is edited (a new instance of this class).
 *       A success clears the count.</li>
 *   <li>Once a logon has worked, VMs are asked {@value #WORKERS} at a time. One refusal is that VM's own (it is not asked
 *       again for 24 hours); two in one collection are taken for a changed credential: the count restarts as in the
 *       first case and the phase pauses.</li>
 *   <li>A restart is a new run: one VM alone, so a restart loop costs at most one refused logon per restart.</li>
 * </ul>
 */
public final class GuestPhase {

    public static final String COLLECTOR = "guest";
    public static final int WORKERS = 4;
    /** One VM's time, from its start. */
    public static final long VM_BUDGET_MS = 600_000;
    /** The refused logons after which the phase pauses (per run of the adapter, until a logon works). */
    public static final int REFUSALS_BEFORE_PAUSE = 2;
    /**
     * How long it pauses, and how long a VM that refused the account is left alone. Two refused logons a day cannot reach a
     * threshold of five in any lockout window shorter than two days; the usual windows are 15 to 30 minutes.
     */
    public static final long PAUSE_MS = 24L * 60 * 60 * 1000;
    /** VMs tried one after another, looking for a logon that works, before the collection gives up looking. */
    static final int PROBE_LIMIT = 3;
    /** How long the workers get to remove their directories after the hard stop. */
    static final long CLEANUP_WAIT_MS = 10_000;
    /** The longest look-back of the events program, in minutes; what is older is counted, not looked at. */
    public static final int EVENT_WINDOW_MAX_MINUTES = 60;

    /** What the phase needs for one cycle. */
    public static final class Input {
        public GuestPlan plan;
        public GuestAuth auth;
        public GuestTrust trust;
        public VimReader vim;
        public GuestHttp http;
        public GuestRoster roster;
        /** A VM's Operations resource id from its MoRef value; null when it has none. */
        public Function<String, String> resourceIdOf;
        public OpsApi ops;
        public PushSet ps;
        public Failures failures;
        public Log log;
        public LongSupplier clock;
        public GuestRunner.Sleeper sleeper;
        public GuestRunner.Limits limits = new GuestRunner.Limits();
        public long cycle;
        /** No VM is started after this. */
        public long deadlineMs;
        /** Whatever is still running is stopped at this time. */
        public long hardStopMs;
        public int workers = WORKERS;
        public long vmBudgetMs = VM_BUDGET_MS;
    }

    /** What the phase did. */
    public static final class Result {
        public Status status;
        public int done;
        public int pending;
        public int eventsSent;
        public int eventsHeld;
        /** True when the phase ran at all (the guest metrics are only reported then). */
        public boolean ran;
        /** True when VMs were left unasked because the cycle's time was up. */
        public boolean deadlineHit;
    }

    /** The directories a VM's guest could not remove, to try again the next time that VM is asked. */
    private static final int MAX_LEFTOVER_VMS = 2_000;
    private static final int MAX_LEFTOVER_PER_VM = 10;
    private static final int MAX_TRACKED_VMS = 20_000;
    private final Map<String, List<String>> leftovers = new ConcurrentHashMap<>();
    private final Map<String, Long> refusedVms = new ConcurrentHashMap<>();
    private final Map<String, Long> lastEventLook = new ConcurrentHashMap<>();
    private final GuestCursor cursor = new GuestCursor();
    private final EventMemory memory;
    private boolean proven;
    private int refusals;
    private long pausedAtMs;

    public GuestPhase() {
        this(new EventMemory());
    }

    public GuestPhase(EventMemory memory) {
        this.memory = memory;
    }

    EventMemory memory() {
        return memory;
    }

    private static final class Item {
        final GuestRoster.Vm vm;
        final String resourceId;
        volatile boolean started;
        volatile GuestRunner.Outcome outcome;
        volatile boolean cut;
        volatile String crash;
        int windowMissed;

        Item(GuestRoster.Vm vm, String resourceId) {
            this.vm = vm;
            this.resourceId = resourceId;
        }
    }

    /** What one batch of VMs left. */
    private static final class Batch {
        final List<Item> items = new ArrayList<>();
        boolean terminated = true;
    }

    public Result run(Input in) throws InterruptedException {
        Result res = new Result();
        GuestPlan plan = in.plan;
        Log log = in.log == null ? Log.NONE : in.log;
        if (!plan.runs()) {
            res.status = offStatus(plan, in, log);
            return res;
        }
        long now = in.clock.getAsLong();
        if (pausedAtMs > 0) {
            if (now - pausedAtMs < PAUSE_MS) {
                res.status = Status.SKIPPED;
                in.failures.add(COLLECTOR, GuestStep.AUTH.cause(), "Windows account", 0, "guest monitoring is paused: the "
                        + "Windows account was refused, and trying again could lock it. Edit the account to try again at "
                        + "once; otherwise one VM is tried again after 24 hours");
                return res;
            }
            // one attempt: a refusal pauses again at once
            pausedAtMs = 0;
            proven = false;
            refusals = REFUSALS_BEFORE_PAUSE - 1;
            refusedVms.clear();
        }
        if (!in.roster.vmsRead()) {
            res.status = Status.SKIPPED;
            in.failures.add(COLLECTOR, FailureCause.guest("SKIPPED"), "VM list", 0,
                    "the virtual machine read did not finish, so there is no list of Windows VMs");
            return res;
        }
        res.ran = true;
        if (in.trust.mode() == com.vcfcf.adapters.vcommunity.VCommunityConfig.GuestTlsMode.DISABLED) {
            log.warn("Guest transfers are not verified: the TLS setting for them is Disabled, so an ESXi host's "
                    + "certificate is not looked at before a guest file is sent or read");
        }

        List<Item> candidates = new ArrayList<>();
        int toolsDown = 0;
        int suppressed = 0;
        for (GuestRoster.Vm vm : in.roster.vms()) {
            if (vm.toolsDown()) {
                toolsDown++;
                in.failures.add(COLLECTOR, GuestStep.TOOLS.cause(), vm.moref, 0, GuestStep.TOOLS.meaning
                        + " on a powered-on Windows VM; nothing is asked of it");
                continue;
            }
            if (!vm.candidate()) continue;
            String rid = in.resourceIdOf.apply(vm.moref);
            if (rid == null) continue;
            Long refusedAt = refusedVms.get(vm.moref);
            if (refusedAt != null && now - refusedAt < PAUSE_MS) {
                suppressed++;
                in.failures.add(COLLECTOR, GuestStep.AUTH.cause(), vm.moref, 0, "the Windows account was refused on this VM; "
                        + "it is not asked again until the account is edited or 24 hours have passed");
                continue;
            }
            candidates.add(new Item(vm, rid));
        }
        List<Item> ordered = cursor.order(candidates, i -> i.vm.moref);

        GuestOpsClient ops = new GuestOpsClient(in.vim, in.auth, GuestOpsClient.CALL_MS, in.clock);
        GuestRunner runner = new GuestRunner(ops, in.http, in.trust, in.clock, in.sleeper, log, in.limits);
        long waitMs = Math.max(0, in.hardStopMs - now);
        long realStop = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        AtomicInteger refusedNow = new AtomicInteger();
        AtomicInteger startedNow = new AtomicInteger();
        AtomicBoolean provenNow = new AtomicBoolean(proven);
        final boolean wasProven = proven;
        List<Item> finished = new ArrayList<>();
        boolean allTerminated = true;

        // until a logon has worked: one VM alone, and no other after a refusal
        List<Item> rest = ordered;
        if (!wasProven && !ordered.isEmpty()) {
            Batch probe = runBatch(in, runner, ordered, 1, realStop, refusedNow, startedNow, provenNow,
                    () -> provenNow.get() || refusedNow.get() > 0 || startedNow.get() >= PROBE_LIMIT);
            finished.addAll(probe.items);
            allTerminated &= probe.terminated;
            rest = new ArrayList<>();
            for (Item i : ordered) if (!i.started) rest.add(i);
        }
        boolean fanOut = wasProven || provenNow.get();
        if (fanOut && !rest.isEmpty()) {
            Batch batch = runBatch(in, runner, rest, in.workers, realStop, refusedNow, startedNow, provenNow,
                    () -> refusedNow.get() >= REFUSALS_BEFORE_PAUSE);
            finished.addAll(batch.items);
            allTerminated &= batch.terminated;
        }
        if (Thread.interrupted()) throw new InterruptedException();

        int refused = refusedNow.get();
        boolean logged = false;
        for (Item item : finished) {
            if (!item.cut && item.outcome != null && item.outcome.loggedOn) logged = true;
        }
        settleAccount(in, wasProven, logged, refused, now, log);
        account(in, res, finished, ordered, refused, toolsDown, suppressed, now, log);
        sendEvents(in, finished, res, log);
        return res;
    }

    /** Count the refusals and the successes of this collection towards the protection of the account. */
    private void settleAccount(Input in, boolean wasProven, boolean logged, int refused, long now, Log log) {
        if (!wasProven) {
            if (logged) {
                proven = true;
                refusals = 0;
            } else {
                refusals += refused;
            }
        } else if (refused >= REFUSALS_BEFORE_PAUSE) {
            // two refusals in one collection after it had worked: the credential changed behind our back
            proven = false;
            refusals = refused;
        }
        if (!proven && refusals >= REFUSALS_BEFORE_PAUSE) {
            pausedAtMs = now;
            log.warn("The Windows account was refused " + refusals + " times; guest monitoring is paused so that the "
                    + "account is not locked. Edit the account to try again at once; otherwise one VM is tried after 24 hours");
        }
    }

    /** Run these VMs on {@code workers} threads; each starts only while {@code stop} is false and the time is not up. */
    private Batch runBatch(Input in, GuestRunner runner, List<Item> items, int workers, long realStop,
            AtomicInteger refusedNow, AtomicInteger startedNow, AtomicBoolean provenNow,
            java.util.function.BooleanSupplier stop) throws InterruptedException {
        Batch batch = new Batch();
        int submitted = items.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(workers, Math.max(1, submitted))),
                new java.util.concurrent.ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "vcommunity-guest-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
        List<Item> collected = new ArrayList<>();
        try {
            CompletionService<Item> cs = new ExecutorCompletionService<>(pool);
            for (Item item : items) cs.submit(task(item, in, runner, refusedNow, startedNow, provenNow, stop));
            int got = 0;
            while (got < submitted) {
                long left = realStop - System.nanoTime();
                Future<Item> f = left <= 0 ? cs.poll() : cs.poll(left, TimeUnit.NANOSECONDS);
                if (f == null) break;
                got++;
                collected.add(take(f));
            }
        } finally {
            pool.shutdownNow();
            // the workers remove their directories before they end; a cancel of this thread does not cut that short
            boolean cancelled = false;
            try {
                batch.terminated = pool.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                cancelled = true;
                try {
                    batch.terminated = pool.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException again) {
                    batch.terminated = false;
                }
            }
            if (cancelled) Thread.currentThread().interrupt();
        }
        if (Thread.currentThread().isInterrupted()) return batch;
        // a VM started and not collected finished while the workers were being stopped, or is still running: only a pool
        // that has ended gives a result that can be read
        for (Item item : items) {
            if (!item.started) continue;
            if (!collected.contains(item) && !(batch.terminated && item.outcome != null && !item.cut)) item.cut = true;
            batch.items.add(item);
        }
        return batch;
    }

    private Callable<Item> task(Item item, Input in, GuestRunner runner, AtomicInteger refusedNow,
            AtomicInteger startedNow, AtomicBoolean provenNow, java.util.function.BooleanSupplier stop) {
        return () -> {
            if (in.clock.getAsLong() >= in.deadlineMs || stop.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return item;
            }
            item.started = true;
            startedNow.incrementAndGet();
            cursor.started(item.vm.moref);
            GuestRunner.Job job = new GuestRunner.Job();
            job.vm = item.vm.moref;
            job.resourceId = item.resourceId;
            job.hostMoref = item.vm.host;
            job.hostName = in.roster.hostName(item.vm.host);
            job.hostNameOf = in.roster::hostName;
            job.plan = in.plan;
            long start = in.clock.getAsLong();
            if (in.plan.events) {
                Long last = lastEventLook.get(item.vm.moref);
                int window = GuestScripts.DEFAULT_WINDOW_MINUTES;
                if (last != null) {
                    long since = (long) Math.ceil((start - last) / 60_000.0) + 1;
                    window = (int) Math.max(GuestScripts.DEFAULT_WINDOW_MINUTES, Math.min(EVENT_WINDOW_MAX_MINUTES, since));
                    if (since > EVENT_WINDOW_MAX_MINUTES) item.windowMissed = (int) (since - EVENT_WINDOW_MAX_MINUTES);
                }
                job.eventWindowMinutes = window;
            }
            List<String> before = leftovers.get(item.vm.moref);
            if (before != null) job.leftover = new ArrayList<>(before);
            long stopAt = Math.min(start + in.vmBudgetMs, in.hardStopMs);
            try {
                GuestRunner.Outcome o = runner.run(job, stopAt);
                remember(item.vm.moref, o.leftBehind);
                if (o.fatal == GuestStep.AUTH) {
                    refusedNow.incrementAndGet();
                    if (refusedVms.size() < MAX_TRACKED_VMS) refusedVms.put(item.vm.moref, start);
                }
                if (o.loggedOn) provenNow.set(true);
                if (o.eventsOk && lastEventLook.size() < MAX_TRACKED_VMS) lastEventLook.put(item.vm.moref, start);
                item.outcome = o;
            } catch (InterruptedException e) {
                item.cut = true;
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                item.crash = e.getClass().getSimpleName();
            }
            return item;
        };
    }

    private void remember(String vm, List<String> left) {
        if (left.isEmpty()) {
            leftovers.remove(vm);
        } else if (leftovers.containsKey(vm) || leftovers.size() < MAX_LEFTOVER_VMS) {
            leftovers.put(vm, new ArrayList<>(left.subList(0, Math.min(left.size(), MAX_LEFTOVER_PER_VM))));
        }
    }

    private Item take(Future<Item> f) throws InterruptedException {
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("a guest task failed outside its own handling", e.getCause());
        }
    }

    private Status offStatus(GuestPlan plan, Input in, Log log) {
        switch (plan.off) {
            case BAD_TLS_MODE:
                in.failures.add(COLLECTOR, FailureCause.TLS_CONFIG, "guest_tls_validation", 0, plan.message);
                log.warn(plan.message);
                return Status.FAILED;
            case VCENTER_TLS:
                in.failures.add(COLLECTOR, FailureCause.guest("TLS-MODE"), "vCenter TLS Validation", 0, plan.message);
                log.warn(plan.message);
                return Status.FAILED;
            case NO_CREDENTIAL:
                log.warn(plan.message);
                return Status.SKIPPED;
            default:
                return Status.SKIPPED;
        }
    }

    /** Publish what the VMs gave and count what failed. */
    private void account(Input in, Result res, List<Item> finished, List<Item> ordered, int refused, int toolsDown,
            int suppressed, long now, Log log) {
        int started = 0;
        int failedVms = 0;
        int usable = 0;
        int cut = 0;
        for (Item item : finished) {
            if (!item.started) continue;
            started++;
            if (item.cut) {
                cut++;
                in.failures.add(COLLECTOR, GuestStep.TIMEOUT.cause(), item.vm.moref, 0,
                        "the VM was still being asked when the cycle's time was up; it was stopped");
                continue;
            }
            res.done++;
            if (item.crash != null || item.outcome == null) {
                failedVms++;
                in.failures.add(COLLECTOR, FailureCause.collect("GUEST"), item.vm.moref, 0,
                        "the guest phase failed on this VM (" + item.crash + ")");
                continue;
            }
            GuestRunner.Outcome o = item.outcome;
            if (o.scriptsOk > 0) usable++;
            for (GuestFailure f : o.failures) {
                in.failures.add(COLLECTOR, f.step.cause(), item.vm.moref, withheld(f, in.plan), f.toString());
            }
            if (item.windowMissed > 0) {
                in.failures.add(COLLECTOR, GuestStep.WINDOW.cause(), item.vm.moref, 0, "this VM was last asked more than "
                        + EVENT_WINDOW_MAX_MINUTES + " minutes ago: its events of the " + item.windowMissed
                        + " minutes before the window were not looked at");
            }
            if (!o.failures.isEmpty() || item.windowMissed > 0) failedVms++;
            publish(in, item);
        }
        int notStarted = ordered.size() - started;
        res.pending = notStarted + cut;
        boolean heldForAccount = (refused > 0 || pausedAtMs > 0) && notStarted > 0;
        res.deadlineHit = (notStarted > 0 && !heldForAccount && in.clock.getAsLong() >= in.deadlineMs) || cut > 0;
        if (ordered.isEmpty()) {
            res.status = toolsDown > 0 || suppressed > 0 ? Status.PARTIAL : Status.OK;
        } else if (started == 0) {
            res.status = Status.NOT_RUN;
            in.failures.add(COLLECTOR, FailureCause.DEADLINE, "guest phase", 0,
                    "the cycle's time was up before any VM was asked");
        } else if (usable == 0) {
            res.status = Status.FAILED;
        } else if (failedVms > 0 || res.pending > 0 || toolsDown > 0 || suppressed > 0) {
            res.status = Status.PARTIAL;
        } else {
            res.status = Status.OK;
        }
        log.info("Guest phase: " + res.done + " of " + ordered.size() + " VM(s) done, " + res.pending
                + " pending, " + failedVms + " with a failure");
    }

    private static int withheld(GuestFailure f, GuestPlan plan) {
        if (f.step == GuestStep.CLEANUP || f.step == GuestStep.ROW || "events".equals(f.script)
                || "cleanup".equals(f.script)) {
            return 0;
        }
        if ("os".equals(f.script)) return 6;
        return plan.serviceNames.size() * 3;
    }

    /** The keys of one VM's outcome. */
    private void publish(Input in, Item item) {
        GuestRunner.Outcome o = item.outcome;
        String p = "vCommunity|Guest OS|";
        if (o.operatingSystem != null) {
            for (Map.Entry<String, String> e : o.operatingSystem.entrySet()) {
                in.ps.prop(item.resourceId, p + "Operating System|" + e.getKey(), e.getValue());
            }
        }
        for (GuestRows.Service s : o.services) {
            String base = p + "Services:" + s.displayName + "|";
            if (s.name != null) in.ps.prop(item.resourceId, base + "Service Name", s.name);
            if (s.status != null) in.ps.prop(item.resourceId, base + "Service Status", s.status);
            if (s.startType != null) in.ps.prop(item.resourceId, base + "Service Start Type", s.startType);
        }
    }

    private void sendEvents(Input in, List<Item> finished, Result res, Log log) {
        List<OpsEvent> all = new ArrayList<>();
        int over = 0;
        for (Item item : finished) {
            if (item.cut || item.outcome == null) continue;
            all.addAll(item.outcome.events);
            over += item.outcome.eventsOver;
        }
        if (over > 0) {
            log.warn(over + " Windows event(s) over the limit of " + WindowsEvents.MAX_PER_VM + " per VM were not sent");
        }
        long now = in.clock.getAsLong();
        List<OpsEvent> fresh = memory.fresh(all, now);
        res.eventsHeld = all.size() - fresh.size();
        if (fresh.isEmpty()) return;
        PushOutcome po = in.ops.pushEvents(fresh);
        if (po.isClean() && po.sent > 0) {
            memory.remember(fresh, now);
            res.eventsSent = fresh.size();
        } else {
            in.failures.add(COLLECTOR, FailureCause.EVENTS, "Windows events", 0,
                    "the Windows events were not sent cleanly (" + po + "); they are sent again next cycle if still in view");
        }
    }
}
