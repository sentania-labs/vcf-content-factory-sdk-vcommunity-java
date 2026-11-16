package com.vcfcf.adapters.vcommunity.rest;

import java.util.function.LongSupplier;

/**
 * The content libraries of the vCenter, read once per cycle. {@link LibraryReader} is the real
 * one; the cycle takes this so that it does not know how they are read.
 */
public interface LibrarySource {

    /**
     * Read every library and every item. Never throws for a failed read (it is in the data); an
     * interrupt is passed on.
     *
     * @param deadlineMs when to stop asking, on {@code clock}
     * @param clock      the cycle's clock
     */
    LibraryData read(long deadlineMs, LongSupplier clock) throws InterruptedException;
}
