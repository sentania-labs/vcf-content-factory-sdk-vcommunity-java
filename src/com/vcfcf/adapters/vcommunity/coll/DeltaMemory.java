package com.vcfcf.adapters.vcommunity.coll;

/**
 * Which window's Cluster Delta counts this adapter instance has sent and Operations has accepted (DV-86, L2). The counts
 * are sent once a window, and whether they were is read back from Operations; but a stored value is not read back at once
 * (a minute or four, then the next collection five minutes later may still not see it), so a read-back alone sent the
 * same counts again on the next collection. After a clean push this remembers the window for {@code SUPPRESS_MS}; after
 * that the read-back decides again, so counts Operations lost are sent again. Memory only: a restart sends nothing twice
 * either, because the first collection after it has no sample to wait for.
 */
final class DeltaMemory {

    /** Long enough for the read-back to catch up, short enough that counts that never landed are sent again. */
    static final long SUPPRESS_MS = 30 * 60 * 1000L;

    private long sentEnd = -1;
    private long sentAt;
    private long pendingEnd = -1;

    /** The counts of this window were queued for a push; they count as sent only when {@link #commit} runs. */
    synchronized void queued(long windowEndMs) {
        pendingEnd = windowEndMs;
    }

    /** The push was clean. */
    synchronized void commit(long nowMs) {
        if (pendingEnd >= 0) {
            sentEnd = pendingEnd;
            sentAt = nowMs;
            pendingEnd = -1;
        }
    }

    /** The counts of this window were sent lately by this instance. */
    synchronized boolean sentLately(long windowEndMs, long nowMs) {
        return sentEnd == windowEndMs && nowMs - sentAt < SUPPRESS_MS;
    }
}
