package com.vcfcf.adapters.vcommunity.guest;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The protection of the Windows account (DV-97), in one place. Every VM the guest phase starts, in every collection and
 * on every path, passes through {@link #admit}; every refused logon is recorded through {@link Slot#refused}. Nothing
 * else decides whether a VM may be asked.
 *
 * <h3>The invariant</h3>
 * A directory account locks after a number of refused logons inside a window (usually 5 inside 15 to 30 minutes), whichever
 * VM they come from. For one credential generation (one run of the adapter with one credential: an edit of the account or a
 * restart makes a new gate), with {@code T} = {@value #REFUSALS_BEFORE_PAUSE} and {@code W} = the workers:
 * <ul>
 *   <li>A VM may start only while {@code refusals in the last 24 hours + VMs whose first logon is not yet answered < T}.
 *       So refused logons never exceed {@code T} in any 24 hours, however many VMs are asked and however they overlap
 *       (the answer is known after the first call, so the throttle holds back the start of a VM, not its run).</li>
 *   <li>Until a logon has been accepted in this run (the probe), one VM is asked alone, and after a refusal no other VM
 *       is started in that collection. So the first collection of a run, and a restart, cost at most one refused logon.</li>
 *   <li>The refusal that makes {@code T} pauses the phase for 24 hours. After the pause the same rule runs in probation:
 *       {@code T} is 1 until a logon is accepted, so one more refused logon pauses again at once.</li>
 *   <li>A VM that refused is not asked again for 24 hours (its refusal also stays in the count for 24 hours).</li>
 *   <li>The one thing the gate cannot stop is a credential that changes while VMs are running: each VM that is running
 *       then refuses once at most (a refusal ends that VM's run) and no VM starts after the {@code T}th refusal, so the
 *       worst case is {@code T + W - 1}.</li>
 * </ul>
 * An accepted logon never clears a refusal: a refusal leaves the count only when 24 hours have passed since it (or when
 * the pause it caused ends).
 */
public final class LogonGate {

    /** The refused logons that pause the phase (T). */
    public static final int REFUSALS_BEFORE_PAUSE = 2;
    /**
     * How long it pauses, how long a refusal counts, and how long a VM that refused is left alone. Two refused logons a
     * day cannot reach a threshold of five in any lockout window shorter than two days.
     */
    public static final long PAUSE_MS = 24L * 60 * 60 * 1000;
    /** VMs tried one after another, looking for a logon that works, before a collection gives up looking. */
    public static final int PROBE_LIMIT = 3;

    private static final long POLL_MS = 20;
    private static final int MAX_TRACKED_VMS = 20_000;

    private final Object lock = new Object();
    /** When each refusal of the last 24 hours happened, oldest first. */
    private final ArrayDeque<Long> refusals = new ArrayDeque<>();
    private final Map<String, Long> refusedVms = new HashMap<>();
    /** A logon has been accepted since the run began (or since the last pause ended). */
    private boolean proven;
    /** The phase has just come back from a pause: one refusal pauses it again. */
    private boolean probation;
    private long pausedAtMs;

    // this collection
    private int unresolved;
    private int refusedNow;
    private int probes;
    private boolean closed;

    /** What a collection finds when it asks to begin. */
    public enum Begin { OPEN, PAUSED }

    /** Start a collection at {@code now}: ends a pause that has run its 24 hours, forgets the last collection's count. */
    public Begin begin(long now) {
        synchronized (lock) {
            if (pausedAtMs > 0) {
                if (now - pausedAtMs < PAUSE_MS) return Begin.PAUSED;
                pausedAtMs = 0;
                proven = false;
                probation = true;
                refusals.clear();
            }
            expire(now);
            unresolved = 0;
            refusedNow = 0;
            probes = 0;
            closed = false;
            return Begin.OPEN;
        }
    }

    public boolean paused() {
        synchronized (lock) {
            return pausedAtMs > 0;
        }
    }

    /** The refused logons of the current collection. */
    public int refusedNow() {
        synchronized (lock) {
            return refusedNow;
        }
    }

    /** True while a VM that refused the account is being left alone. */
    public boolean leftAlone(String vm, long now) {
        synchronized (lock) {
            Long at = refusedVms.get(vm);
            return at != null && now - at < PAUSE_MS;
        }
    }

    private int threshold() {
        return probation ? 1 : REFUSALS_BEFORE_PAUSE;
    }

    private void expire(long now) {
        while (!refusals.isEmpty() && now - refusals.peekFirst() >= PAUSE_MS) refusals.pollFirst();
        if (refusedVms.size() > MAX_TRACKED_VMS / 2) refusedVms.values().removeIf(at -> now - at >= PAUSE_MS);
    }

    /**
     * Wait until a VM may start, and take its place. Returns null when no VM may start in this collection (the phase
     * is paused, the probe is over, or the time is up). The place is released by {@link Slot#release} in every case.
     *
     * @throws InterruptedException the thread was interrupted while it waited; nothing was taken
     */
    public Slot admit(String vm, LongSupplier clock, long deadlineMs) throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (closed || pausedAtMs > 0) return null;
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (clock.getAsLong() >= deadlineMs) return null;
                if (may()) {
                    unresolved++;
                    if (!proven) probes++;
                    return new Slot(vm);
                }
                if (!proven && (refusedNow > 0 || probes >= PROBE_LIMIT) && unresolved == 0) {
                    closed = true; // the probe is over: nobody else is asked in this collection
                    return null;
                }
                lock.wait(POLL_MS);
            }
        }
    }

    private boolean may() {
        if (refusals.size() + unresolved >= threshold()) return false;
        if (proven) return true;
        // the probe: one VM alone, none after a refusal, and only so many looked at
        return unresolved == 0 && refusedNow == 0 && probes < PROBE_LIMIT;
    }

    /** One VM's place in the gate, from its start until its logon is answered. */
    public final class Slot {
        private final String vm;
        private boolean open = true;
        private boolean accepted;
        private boolean refused;

        private Slot(String vm) {
            this.vm = vm;
        }

        /** The guest accepted the account. */
        public void accepted() {
            synchronized (lock) {
                if (accepted) return;
                accepted = true;
                proven = true;
                probation = false;
                resolve();
            }
        }

        /** The guest (or vCenter) refused the account, at {@code now}. */
        public void refused(long now) {
            synchronized (lock) {
                if (refused) return;
                refused = true;
                refusals.addLast(now);
                refusedNow++;
                if (refusedVms.size() < MAX_TRACKED_VMS) refusedVms.put(vm, now);
                if (refusals.size() >= threshold()) {
                    closed = true;
                    proven = false;
                    pausedAtMs = now;
                }
                resolve();
            }
        }

        /** The VM's run ended (or never got as far as a logon): its place is free. */
        public void release() {
            synchronized (lock) {
                resolve();
            }
        }

        private void resolve() {
            if (open) {
                open = false;
                unresolved--;
            }
            lock.notifyAll();
        }
    }
}
