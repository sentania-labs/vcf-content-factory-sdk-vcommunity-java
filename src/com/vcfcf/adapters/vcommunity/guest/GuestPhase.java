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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The guest phase of a cycle (design 8.1): asks the Windows VMs in turn, four at a time, until the cycle's
 * time is up, and turns what comes back into properties and events. One VM's failure is its own: it is counted
 * with its cause and does not touch another VM, the properties or the cycle. A started VM runs on to the end of
 * its own time; the hard stop at the end cuts the rest, and each cut VM's directory is removed first.
 *
 * <p>It keeps three small memories across cycles, all per account and in memory: the cursor (who is next),
 * the events sent lately ({@link EventMemory}) and the pause after the account was refused. The pause is the
 * protection of the Windows account: a wrong password tried on every VM every five minutes would lock a
 * directory account, so after {@value #AUTH_FAILURES_BEFORE_STOP} refusals in a cycle the phase stops for that
 * cycle and does not try again for {@value #PAUSE_CYCLES} cycles (DV-97).
 */
public final class GuestPhase {

    public static final String COLLECTOR = "guest";
    public static final int WORKERS = 4;
    /** One VM's time, from its start. */
    public static final long VM_BUDGET_MS = 600_000;
    public static final int AUTH_FAILURES_BEFORE_STOP = 3;
    public static final int PAUSE_CYCLES = 12;
    /** How long the workers get to remove their directories after the hard stop. */
    static final long CLEANUP_WAIT_MS = 20_000;

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
    private final java.util.Map<String, List<String>> leftovers = new java.util.concurrent.ConcurrentHashMap<>();
    private final GuestCursor cursor = new GuestCursor();
    private final EventMemory memory;
    private long pausedUntilCycle;

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
        boolean started;
        GuestRunner.Outcome outcome;
        boolean cut;
        String crash;

        Item(GuestRoster.Vm vm, String resourceId) {
            this.vm = vm;
            this.resourceId = resourceId;
        }
    }

    public Result run(Input in) throws InterruptedException {
        Result res = new Result();
        GuestPlan plan = in.plan;
        Log log = in.log == null ? Log.NONE : in.log;
        if (!plan.runs()) {
            res.status = offStatus(plan, in, log);
            return res;
        }
        if (in.cycle < pausedUntilCycle) {
            res.status = Status.SKIPPED;
            log.info("Guest monitoring is paused for " + (pausedUntilCycle - in.cycle) + " more collection(s): the "
                    + "Windows account was refused, and trying again at once could lock it");
            return res;
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
        for (GuestRoster.Vm vm : in.roster.vms()) {
            if (vm.toolsDown()) {
                toolsDown++;
                in.failures.add(COLLECTOR, GuestStep.TOOLS.cause(), vm.moref, 0, GuestStep.TOOLS.meaning
                        + " on a powered-on Windows VM; nothing is asked of it");
                continue;
            }
            if (!vm.candidate()) continue;
            String rid = in.resourceIdOf.apply(vm.moref);
            if (rid != null) candidates.add(new Item(vm, rid));
        }
        List<Item> ordered = cursor.order(candidates, i -> i.vm.moref);

        GuestOpsClient ops = new GuestOpsClient(in.vim, in.auth);
        GuestRunner runner = new GuestRunner(ops, in.http, in.trust, in.clock, in.sleeper, log, in.limits);
        AtomicInteger refused = new AtomicInteger();
        List<Item> finished = new ArrayList<>();
        int submitted = ordered.size();
        long waitMs = Math.max(0, in.hardStopMs - in.clock.getAsLong());
        long realStop = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(in.workers, Math.max(1, submitted))),
                new java.util.concurrent.ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "vcommunity-guest-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
        try {
            CompletionService<Item> cs = new ExecutorCompletionService<>(pool);
            for (Item item : ordered) {
                cs.submit(task(item, in, runner, refused));
            }
            int got = 0;
            while (got < submitted) {
                long left = realStop - System.nanoTime();
                Future<Item> f = left <= 0 ? cs.poll() : cs.poll(left, TimeUnit.NANOSECONDS);
                if (f == null) break;
                got++;
                finished.add(take(f));
            }
        } finally {
            pool.shutdownNow();
            // the workers remove their directories before they end; a cancel of this thread does not cut that short
            boolean cancelled = false;
            try {
                pool.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                cancelled = true;
                try {
                    pool.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException again) {
                    // a second cancel: stop waiting
                }
            }
            if (cancelled) Thread.currentThread().interrupt();
        }
        if (Thread.interrupted()) throw new InterruptedException();
        // what finished while the workers were removing their directories still counts
        for (Item item : ordered) {
            if (item.started && !finished.contains(item)) {
                item.cut = true;
                finished.add(item);
            }
        }

        account(in, res, finished, ordered, refused.get(), toolsDown, log);
        sendEvents(in, finished, res, log);
        return res;
    }

    private Callable<Item> task(Item item, Input in, GuestRunner runner, AtomicInteger refused) {
        return () -> {
            if (in.clock.getAsLong() >= in.deadlineMs || refused.get() >= AUTH_FAILURES_BEFORE_STOP
                    || Thread.currentThread().isInterrupted()) {
                return item;
            }
            item.started = true;
            cursor.started(item.vm.moref);
            GuestRunner.Job job = new GuestRunner.Job();
            job.vm = item.vm.moref;
            job.resourceId = item.resourceId;
            job.hostMoref = item.vm.host;
            job.hostName = in.roster.hostName(item.vm.host);
            job.plan = in.plan;
            List<String> before = leftovers.get(item.vm.moref);
            if (before != null) job.leftover = new ArrayList<>(before);
            long stop = Math.min(in.clock.getAsLong() + in.vmBudgetMs, in.hardStopMs);
            try {
                item.outcome = runner.run(job, stop);
                remember(item.vm.moref, item.outcome.leftBehind);
                if (item.outcome.fatal == GuestStep.AUTH) refused.incrementAndGet();
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
            case NO_CREDENTIAL:
                log.warn(plan.message);
                return Status.SKIPPED;
            default:
                return Status.SKIPPED;
        }
    }

    /** Publish what the VMs gave and count what failed. */
    private void account(Input in, Result res, List<Item> finished, List<Item> ordered, int refused, int toolsDown,
            Log log) {
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
            if (item.crash != null) {
                failedVms++;
                in.failures.add(COLLECTOR, FailureCause.collect("GUEST"), item.vm.moref, 0,
                        "the guest phase failed on this VM (" + item.crash + ")");
                continue;
            }
            GuestRunner.Outcome o = item.outcome;
            if (o.scriptsOk > 0) usable++;
            boolean failed = false;
            for (GuestFailure f : o.failures) {
                if (f.step != GuestStep.CLEANUP && f.step != GuestStep.ROW) failed = true;
                in.failures.add(COLLECTOR, f.step.cause(), item.vm.moref, withheld(f, in.plan), f.toString());
            }
            if (failed || !o.failures.isEmpty()) failedVms++;
            publish(in, item);
        }
        int notStarted = ordered.size() - started;
        res.pending = notStarted + cut;
        res.deadlineHit = (notStarted > 0 && refused < AUTH_FAILURES_BEFORE_STOP) || cut > 0;
        if (refused >= AUTH_FAILURES_BEFORE_STOP) {
            pausedUntilCycle = in.cycle + PAUSE_CYCLES + 1;
            log.warn("The Windows account was refused on " + refused + " VMs; guest monitoring stops for this "
                    + "collection and for the next " + PAUSE_CYCLES + " so that the account is not locked");
            in.failures.add(COLLECTOR, GuestStep.SKIPPED.cause(), "Windows account", 0,
                    "guest monitoring is paused after the account was refused on " + refused + " VMs");
        }
        if (ordered.isEmpty()) {
            res.status = toolsDown > 0 ? Status.PARTIAL : Status.OK;
        } else if (started == 0) {
            res.status = refused >= AUTH_FAILURES_BEFORE_STOP ? Status.SKIPPED : Status.NOT_RUN;
            if (res.status == Status.NOT_RUN) {
                in.failures.add(COLLECTOR, FailureCause.DEADLINE, "guest phase", 0,
                        "the cycle's time was up before any VM was asked");
            }
        } else if (usable == 0) {
            res.status = Status.FAILED;
        } else if (failedVms > 0 || res.pending > 0 || toolsDown > 0) {
            res.status = Status.PARTIAL;
        } else {
            res.status = Status.OK;
        }
        log.info("Guest phase: " + res.done + " of " + ordered.size() + " VM(s) done, " + res.pending
                + " pending, " + failedVms + " with a failure");
    }

    private static int withheld(GuestFailure f, GuestPlan plan) {
        if (f.step == GuestStep.CLEANUP || f.step == GuestStep.ROW || "events".equals(f.script)) return 0;
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
            if (item.outcome == null) continue;
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
