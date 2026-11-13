package com.vcfcf.adapters.vcommunity.ops;

/** What one push did, as counts; the SDK-free mirror of the framework's batch result. */
public final class PushOutcome {
    public final int sent;
    public final int batchesOk;
    public final int batchesFailed;
    public final int skipped;
    public final int dropped;
    public final boolean stopped;

    public PushOutcome(int sent, int batchesOk, int batchesFailed, int skipped, int dropped,
            boolean stopped) {
        this.sent = sent;
        this.batchesOk = batchesOk;
        this.batchesFailed = batchesFailed;
        this.skipped = skipped;
        this.dropped = dropped;
        this.stopped = stopped;
    }

    /** Nothing was queued, so nothing was sent. */
    public static final PushOutcome NOTHING = new PushOutcome(0, 0, 0, 0, 0, false);

    /** Every batch sent was accepted and nothing was skipped, dropped or stopped. */
    public boolean isClean() {
        return batchesFailed == 0 && skipped == 0 && dropped == 0 && !stopped;
    }

    @Override
    public String toString() {
        return "sent=" + sent + " batchesOk=" + batchesOk + " batchesFailed=" + batchesFailed
                + " skipped=" + skipped + " dropped=" + dropped + " stopped=" + stopped;
    }
}
