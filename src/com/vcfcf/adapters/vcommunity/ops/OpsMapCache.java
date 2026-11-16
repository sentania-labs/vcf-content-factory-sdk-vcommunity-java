package com.vcfcf.adapters.vcommunity.ops;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The Ops resource maps of the foreign kinds, kept between cycles (design
 * 3.1): one {@link OpsResourceMap} per kind, built for one vCenter UUID. A map
 * is read when there is none, every {@value #REFRESH_EVERY}th cycle, after a
 * failed read, and again when objects it does not know turn up, the same
 * rules the vSAN performance pak keeps. A failed read keeps the previous map
 * if it was built for the same vCenter; a map built for another UUID is never
 * used.
 *
 * <p>Used by the cycle thread; the tasks that read run on others but hand the
 * result back to it.
 */
public final class OpsMapCache {

    public static final int REFRESH_EVERY = 12;

    private static final class Slot {
        OpsResourceMap map;
        String vcid;
        long loadedCycle = Long.MIN_VALUE / 2;
        boolean retry;
        int missBackoff = 1;
        Set<String> lastSeen = Collections.emptySet();
    }

    private final Map<String, Slot> slots = new HashMap<>();

    private Slot slot(String kind) {
        return slots.computeIfAbsent(kind, k -> new Slot());
    }

    /** Whether the map of this kind is to be read before the objects are matched. */
    public synchronized boolean due(String kind, String vcid, long cycle) {
        Slot s = slot(kind);
        return s.map == null || !vcid.equals(s.vcid) || s.retry
                || cycle - s.loadedCycle >= REFRESH_EVERY;
    }

    /** The map for this vCenter, or null when there is none (never one built for another vCenter). */
    public synchronized OpsResourceMap current(String kind, String vcid) {
        Slot s = slot(kind);
        return s.map != null && vcid.equals(s.vcid) ? s.map : null;
    }

    /** Remember a freshly read map. */
    public synchronized void put(String kind, String vcid, long cycle, OpsResourceMap map) {
        Slot s = slot(kind);
        s.map = map;
        s.vcid = vcid;
        s.loadedCycle = cycle;
        s.retry = false;
    }

    /** A scheduled read starts the wait for a miss over. */
    public synchronized void scheduledRead(String kind) {
        slot(kind).missBackoff = 1;
    }

    /** A read failed: keep what there is and read again next cycle. */
    public synchronized void failed(String kind) {
        slot(kind).retry = true;
    }

    /** Whether the map was read this cycle (a second read in one cycle is not made). */
    public synchronized boolean loadedIn(String kind, long cycle) {
        return slot(kind).loadedCycle == cycle;
    }

    /**
     * Whether objects the map does not know justify reading it again. A new object (not seen the cycle
     * before) is read for at once, since the vCenter adapter may already have it; one that is still
     * missing is read for again after 2, 4, 8 and then every 12 cycles at most, so a VM the vCenter
     * adapter discovers a few minutes late gets its keys within a cycle or two, and one it never
     * models (a template it skips) costs a list read only every 12th cycle.
     */
    public synchronized boolean missJustifiesRead(String kind, long cycle, boolean anyNew) {
        Slot s = slot(kind);
        if (anyNew) s.missBackoff = 1;
        return s.loadedCycle != cycle && cycle - s.loadedCycle >= s.missBackoff;
    }

    /** After a read made for a miss: the next one waits longer if objects are still missing. */
    public synchronized void missRead(String kind, boolean stillMissing) {
        Slot s = slot(kind);
        s.missBackoff = stillMissing ? Math.min(REFRESH_EVERY, s.missBackoff * 2) : 1;
    }

    /** Swap the set of MoRefs seen this cycle for the next cycle's comparison; returns the previous. */
    public synchronized Set<String> swapSeen(String kind, Set<String> seen) {
        Slot s = slot(kind);
        Set<String> old = s.lastSeen;
        s.lastSeen = seen;
        return old;
    }

    /** The map read as a convenience for tests and logs. */
    public static OpsResourceMap read(OpsApi ops, String vcid, String kind)
            throws IOException, InterruptedException {
        return OpsResourceMap.load(ops, vcid, kind);
    }
}
