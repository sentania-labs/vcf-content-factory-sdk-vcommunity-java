package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.ops.OpsReads;

import java.util.function.LongSupplier;

/** What the stages of the derived phase share: the reads, the push set, the failure accounting and the deadline. */
final class DerivedContext {

    final DerivedPhase.Input in;
    final OpsReads reads;
    final LongSupplier clock;
    final Log log;
    boolean deadlineHit;

    DerivedContext(DerivedPhase.Input in, LongSupplier clock, Log log) {
        this.in = in;
        this.clock = clock;
        this.log = log;
        this.reads = new OpsReads(in.ops, this::stop);
    }

    /** True when work must stop: the deadline passed or the worker was interrupted. */
    boolean stop() {
        return Thread.currentThread().isInterrupted() || clock.getAsLong() >= in.deadlineMs;
    }

    long now() {
        return in.startMs;
    }

    void stat(String rid, String key, double value) {
        in.ps.stat(rid, key, value);
    }

    /** Values that were not sent because an input of theirs was not read: counted for Collection Health. */
    void withheld(int n) {
        in.failures.addWithheld(n);
    }

    void failed(String collector, String cause, String object, int withheld, String message) {
        in.failures.add(collector, cause, object, withheld, message);
    }

    /** How many inputs each collector's stages found missing (their reads were counted where they were made). */
    final java.util.Map<String, Integer> gaps = new java.util.HashMap<>();

    /**
     * An input a stage needs is missing (a membership list, a list of Operations objects). The read that failed
     * was counted where it was made, so this adds no failed read of its own: it says so once at INFO, counts
     * one withheld value (how many it would have been is unknown) and makes the collector PARTIAL.
     */
    void inputMissing(String collector, String what) {
        gaps.merge(collector, 1, Integer::sum);
        log.info(what + " was not read this cycle, so the figures that need it are not sent");
        withheld(1);
    }

    /**
     * Account what a read gave. Nothing failed: nothing to say. A batch that failed is one failed read, naming
     * the first reason; the caller withholds what depended on the ids in {@code r.failedIds}.
     */
    boolean account(OpsReads.Report r, String collector, String cause, String what) {
        if (r.ok()) return true;
        if (r.stopped) {
            deadlineHit = true;
            failed(collector, FailureCause.DEADLINE, what, 0, "the cycle deadline was reached while reading "
                    + what + "; the figures that need it are not sent");
        } else {
            failed(collector, cause, what, 0, "reading " + what + " failed for " + r.failedIds.size()
                    + " resource(s): " + r.firstError + "; the figures that need them are not sent");
        }
        return false;
    }
}
