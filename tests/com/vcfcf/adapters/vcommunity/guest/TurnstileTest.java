package com.vcfcf.adapters.vcommunity.guest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The order in which the VMs of a batch reach the logon gate (DV-112 follow-up to the flaky GuestEngineTest): list
 * order, whatever the threads do, and a turn that ends early (deadline, interrupt, no admission) never strands the
 * later ones.
 */
public final class TurnstileTest {

    private static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }

    /** A pool of {@code workers} threads takes the tasks first in, first out, as GuestPhase's pool does. */
    private static ExecutorService pool(int workers) {
        return Executors.newFixedThreadPool(workers);
    }

    static void orderIsListOrder() throws Exception {
        for (int round = 0; round < 30; round++) {
            int n = 40;
            Turnstile ts = new Turnstile();
            List<Integer> admitted = Collections.synchronizedList(new ArrayList<>());
            Random rnd = new Random(round);
            ExecutorService pool = pool(4);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int order = i;
                final int jitter = rnd.nextInt(3);
                fs.add(pool.submit(() -> {
                    try {
                        Thread.sleep(jitter); // threads reach the turnstile in a random order
                        check(ts.await(order, System::currentTimeMillis, Long.MAX_VALUE), "turn " + order);
                        admitted.add(order); // what the gate would see
                    } finally {
                        ts.done(order);
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
            pool.shutdownNow();
            for (int i = 0; i < n; i++) check(admitted.get(i) == i, "admitted in list order: " + admitted);
        }
        System.out.println("ok  turnstile: admission order is list order with four workers");
    }

    static void earlyEndsDoNotStrand() throws Exception {
        // turns that come, are not admitted by the gate (slot null) or crash: the later ones go on, in order
        Turnstile ts = new Turnstile();
        List<Integer> admitted = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = pool(3);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int order = i;
            fs.add(pool.submit(() -> {
                try {
                    check(ts.await(order, System::currentTimeMillis, Long.MAX_VALUE), "turn " + order);
                    if (order == 2) return null; // the gate gave no place
                    if (order == 4) throw new RuntimeException("a crash at the gate"); // leaves through finally
                    admitted.add(order);
                } finally {
                    ts.done(order);
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause().getMessage().contains("crash"), "only the planned crash");
            }
        }
        check(admitted.equals(List.of(0, 1, 3, 5, 6, 7)), "the others went on in order: " + admitted);
        // a task that ends before its turn (the deadline was already passed, or it was interrupted before waiting)
        // moves the turnstile past itself and strands nobody; the later turns still all run
        Turnstile t2 = new Turnstile();
        List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> gs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int order = i;
            gs.add(pool.submit(() -> {
                try {
                    if (order == 2) return null; // never reaches the turnstile
                    check(t2.await(order, System::currentTimeMillis, Long.MAX_VALUE), "turn " + order);
                    ran.add(order);
                } finally {
                    t2.done(order);
                }
                return null;
            }));
        }
        for (Future<?> f : gs) f.get();
        pool.shutdownNow();
        List<Integer> sorted = new ArrayList<>(ran);
        Collections.sort(sorted);
        check(sorted.equals(List.of(0, 1, 3, 4, 5, 6, 7)), "nobody was stranded: " + ran);
        System.out.println("ok  turnstile: a turn that ends early does not strand the later ones");
    }

    static void deadlineAndInterrupt() throws Exception {
        // the time is up: a waiter gives up with false, and calls done so the next one is not stuck
        Turnstile ts = new Turnstile();
        AtomicLong clock = new AtomicLong(0);
        ts.done(0);
        check(!ts.await(5, clock::get, 0), "a deadline already passed: no waiting, false");
        ts.done(5);
        check(ts.await(6, clock::get, Long.MAX_VALUE), "turn 5 is over, so 6 is next");
        // an interrupted waiter leaves through InterruptedException, and done releases the others
        Turnstile t2 = new Turnstile();
        Thread waiter = new Thread(() -> {
            try {
                t2.await(3, System::currentTimeMillis, Long.MAX_VALUE);
            } catch (InterruptedException e) {
                // leaves; the caller's finally is done(3)
            } finally {
                t2.done(3);
            }
        });
        waiter.start();
        Thread.sleep(60);
        waiter.interrupt();
        waiter.join(2000);
        check(!waiter.isAlive(), "the interrupted waiter left");
        check(t2.await(4, System::currentTimeMillis, Long.MAX_VALUE), "the next turn is not stranded by an interrupted one");
        System.out.println("ok  turnstile: a deadline or an interrupt leaves through done");
    }

    public static void main(String[] args) throws Exception {
        orderIsListOrder();
        earlyEndsDoNotStrand();
        deadlineAndInterrupt();
    }
}
