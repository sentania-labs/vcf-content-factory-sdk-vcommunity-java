package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.guest.LogonGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gate of DV-97 alone, with no vCenter and no VM: the bound on refused logons holds however many threads ask and
 * however the answers fall. A "VM" here is a thread that takes a place, answers (accepted, refused or neither) and leaves.
 */
public final class LogonGateTest {

    static final long NOW = 1_700_000_000_000L;
    static final long FAR = Long.MAX_VALUE;

    public static void main(String[] args) throws Exception {
        sequential();
        probe();
        pauseAndProbation();
        noClearByAcceptance();
        window();
        concurrent();
        T.done("logon gate");
    }

    /** With strikes + places taken at T, nobody else starts. */
    static void sequential() throws Exception {
        LogonGate g = new LogonGate();
        AtomicLong clock = new AtomicLong(NOW);
        T.eq(LogonGate.Begin.OPEN, g.begin(NOW), "open at first");
        LogonGate.Slot a = g.admit("a", clock::get, FAR);
        T.check(a != null, "the probe is admitted");
        a.accepted();
        // proven: T = 2 places may be taken at once, a third waits (it is not admitted while both are unanswered)
        LogonGate.Slot b = g.admit("b", clock::get, FAR);
        LogonGate.Slot c = g.admit("c", clock::get, FAR);
        T.check(b != null && c != null, "two places at once after a logon worked");
        T.eq(null, g.admit("d", clock::get, NOW), "a third place is not given while two are unanswered (time up: null)");
        b.refused(NOW);
        T.eq(0, g.refusedNow() - 1, "one refused");
        T.eq(null, g.admit("e", clock::get, NOW), "one refused and one unanswered: no place");
        c.accepted();
        LogonGate.Slot d = g.admit("d", clock::get, FAR);
        T.check(d != null, "the other answered: one place (one refusal + one place = T)");
        d.refused(NOW);
        T.check(g.paused(), "the second refusal pauses");
        T.eq(null, g.admit("f", clock::get, FAR), "and nobody starts");
        T.eq(LogonGate.Begin.PAUSED, g.begin(NOW + 1000), "still paused a second later");
    }

    /** Until a logon has worked: one VM alone, none after a refusal. */
    static void probe() throws Exception {
        LogonGate g = new LogonGate();
        g.begin(NOW);
        AtomicLong clock = new AtomicLong(NOW);
        LogonGate.Slot a = g.admit("a", clock::get, FAR);
        // a second VM must wait for the first's answer; let it be a refusal, and the collection is over
        List<LogonGate.Slot> got = new ArrayList<>();
        Thread t = new Thread(() -> {
            try {
                got.add(g.admit("b", clock::get, FAR));
            } catch (InterruptedException e) {
                got.add(null);
            }
        });
        t.start();
        Thread.sleep(100);
        T.eq(0, got.size(), "the second VM waits while the first is unanswered");
        a.refused(NOW);
        t.join(5000);
        T.eq(1, got.size(), "and is answered");
        T.eq(null, got.get(0), "with no place: nobody is asked after a refusal while no logon has worked");
        T.check(!g.paused(), "one refusal does not pause");
        // the next collection: the refused VM is left alone, another probes
        g.begin(NOW + 300_000);
        T.check(g.leftAlone("a", NOW + 300_000), "the VM that refused is left alone");
        T.check(!g.leftAlone("a", NOW + LogonGate.PAUSE_MS), "for 24 hours");
        LogonGate.Slot b = g.admit("b", clock::get, FAR);
        T.check(b != null, "another VM probes");
        b.refused(NOW + 300_000);
        T.check(g.paused(), "its refusal is the second: paused");
    }

    /** After a pause: one VM alone, one refusal pauses at once; one accepted logon ends the probation. */
    static void pauseAndProbation() throws Exception {
        LogonGate g = new LogonGate();
        AtomicLong clock = new AtomicLong(NOW);
        g.begin(NOW);
        g.admit("a", clock::get, FAR).refused(NOW);
        g.begin(NOW + 1);
        g.admit("b", clock::get, FAR).refused(NOW + 1);
        T.check(g.paused(), "paused");
        T.eq(LogonGate.Begin.PAUSED, g.begin(NOW + LogonGate.PAUSE_MS), "paused 24 hours from the second refusal: still, at the exact start");
        clock.set(NOW + 1 + LogonGate.PAUSE_MS);
        T.eq(LogonGate.Begin.OPEN, g.begin(clock.get()), "open after 24 hours");
        LogonGate.Slot c = g.admit("c", clock::get, FAR);
        g.admit("x", clock::get, clock.get()); // time up: null, nothing taken
        c.refused(clock.get());
        T.check(g.paused(), "one refusal on probation pauses again");
        clock.addAndGet(LogonGate.PAUSE_MS);
        g.begin(clock.get());
        LogonGate.Slot d = g.admit("d", clock::get, FAR);
        d.accepted();
        g.admit("e", clock::get, FAR).refused(clock.get());
        T.check(!g.paused(), "after an accepted logon the probation is over: one refusal is that VM's own");
    }

    /** An accepted logon does not wipe a refusal: two broken VMs in a day pause the phase, whatever worked in between. */
    static void noClearByAcceptance() throws Exception {
        LogonGate g = new LogonGate();
        AtomicLong clock = new AtomicLong(NOW);
        g.begin(NOW);
        g.admit("a", clock::get, FAR).refused(NOW);
        g.begin(NOW + 300_000);
        LogonGate.Slot ok = g.admit("b", clock::get, FAR);
        ok.accepted();
        ok.release();
        g.begin(NOW + 600_000);
        g.admit("c", clock::get, FAR).refused(NOW + 600_000);
        T.check(g.paused(), "a refusal an hour ago and one now: two in 24 hours");
    }

    /** A refusal counts for 24 hours and no longer. */
    static void window() throws Exception {
        LogonGate g = new LogonGate();
        AtomicLong clock = new AtomicLong(NOW);
        g.begin(NOW);
        LogonGate.Slot a = g.admit("a", clock::get, FAR);
        a.accepted();
        g.admit("b", clock::get, FAR).refused(NOW);
        long later = NOW + LogonGate.PAUSE_MS;
        clock.set(later);
        g.begin(later);
        LogonGate.Slot c = g.admit("c", clock::get, FAR);
        c.refused(later);
        T.check(!g.paused(), "the first refusal is 24 hours old: it no longer counts");
    }

    /** Many threads, random answers: the refusals recorded never exceed T, in every trial, and nothing hangs. */
    static void concurrent() throws Exception {
        final int t = LogonGate.REFUSALS_BEFORE_PAUSE;
        Random seeds = new Random(97);
        int accepted = 0;
        for (int trial = 0; trial < 300; trial++) {
            final long seed = seeds.nextLong();
            final int vms = 1 + new Random(seed).nextInt(40);
            final int workers = 1 + new Random(seed + 1).nextInt(8);
            final int refuseOdds = new Random(seed + 2).nextInt(100);
            final boolean probation = trial % 5 == 0;
            LogonGate g = new LogonGate();
            AtomicLong clock = new AtomicLong(NOW);
            g.begin(NOW);
            if (probation) {
                // bring it to probation: two refusals, 24 hours
                g.admit("p1", clock::get, FAR).refused(NOW);
                g.begin(NOW + 1);
                g.admit("p2", clock::get, FAR).refused(NOW + 1);
                clock.set(NOW + 1 + LogonGate.PAUSE_MS);
                g.begin(clock.get());
            }
            java.util.concurrent.atomic.AtomicBoolean anyAccepted = new java.util.concurrent.atomic.AtomicBoolean();
            AtomicInteger refused = new AtomicInteger();
            AtomicInteger asked = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            AtomicInteger next = new AtomicInteger();
            for (int w = 0; w < workers; w++) {
                final Random rnd = new Random(seed + 10 + w);
                Thread th = new Thread(() -> {
                    try {
                        while (true) {
                            int i = next.getAndIncrement();
                            if (i >= vms) return;
                            LogonGate.Slot s = g.admit("vm-" + i, clock::get, FAR);
                            if (s == null) continue;
                            asked.incrementAndGet();
                            try {
                                if (rnd.nextInt(3) == 0) Thread.sleep(rnd.nextInt(3));
                                int roll = rnd.nextInt(100);
                                if (roll < refuseOdds) {
                                    refused.incrementAndGet();
                                    s.refused(clock.get());
                                } else if (roll < refuseOdds + (100 - refuseOdds) / 2) {
                                    anyAccepted.set(true);
                                    s.accepted();
                                } // else the VM ends without a logon answer
                                if (rnd.nextInt(3) == 0) Thread.sleep(rnd.nextInt(3));
                            } finally {
                                s.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                th.setDaemon(true);
                threads.add(th);
                th.start();
            }
            for (Thread th : threads) {
                th.join(20_000);
                T.check(!th.isAlive(), "trial " + trial + ": a thread is stuck in the gate");
            }
            // on probation one refusal pauses, until a logon is accepted; from then on it is T in 24 hours as at any time
            final int limit = probation && !anyAccepted.get() ? 1 : t;
            T.check(refused.get() <= limit, "trial " + trial + " (seed " + seed + ", " + vms + " VMs, " + workers
                    + " workers, " + refuseOdds + "% refuse, probation " + probation + "): " + refused.get()
                    + " refused logons, the bound is " + limit);
            T.eq(refused.get() >= limit, g.paused(), "trial " + trial + ": paused exactly when the bound is reached");
            accepted += asked.get();
        }
        T.check(accepted > 300, "the gate does let VMs through (" + accepted + " asked over 300 trials)");
        // and with nothing refused, every VM is asked, four at a time or more
        LogonGate g = new LogonGate();
        AtomicLong clock = new AtomicLong(NOW);
        g.begin(NOW);
        for (int i = 0; i < 50; i++) {
            LogonGate.Slot s = g.admit("v" + i, clock::get, FAR);
            T.check(s != null, "VM " + i + " is admitted when every logon works");
            s.accepted();
            s.release();
        }
    }
}
