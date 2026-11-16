package com.vcfcf.adapters.vcommunity.guest;

import java.util.function.LongSupplier;

/**
 * Lets the VMs of one batch go to the logon gate one at a time, in the order they were listed. Without it the
 * workers reach the gate in whatever order the threads happen to run, so which VM is the probe (the one asked alone
 * until a logon is accepted) was luck: a VM that refuses could be the probe on one run and a VM that accepts on the
 * next, and the collection would end after one logon or go on to all the VMs. The order is the cursor's.
 *
 * <p>It only orders the calls to the gate; the gate alone decides who may start (the lockout bound lives there). So a
 * turn that ends early must never stall the others.
 */
final class Turnstile {

    private static final long POLL_MS = 20;

    private int next;

    /**
     * Waits for the turn of {@code order}.
     *
     * @return false when the time is up before the turn comes
     * @throws InterruptedException the thread was interrupted while it waited; the caller still calls {@link #done}
     */
    synchronized boolean await(int order, LongSupplier clock, long deadlineMs) throws InterruptedException {
        while (next < order) {
            if (clock.getAsLong() >= deadlineMs) return false;
            wait(POLL_MS);
        }
        return true;
    }

    /**
     * The turn of {@code order} is over, whether or not the VM was admitted. {@code next} moves past it even when a
     * lower turn is still inside the gate (a turn that left on the deadline or on an interrupt): that is deliberate.
     * The gate is the bound and is independent of arrival order, and holding the later turns until the earlier ones
     * return would let one stuck wait stall the whole batch. Do not change this into a strict queue.
     */
    synchronized void done(int order) {
        if (next <= order) next = order + 1;
        notifyAll();
    }
}
