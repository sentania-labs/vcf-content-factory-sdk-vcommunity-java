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

    /** A read failed: keep what there is and read again next cycle. */
    public synchronized void failed(String kind) {
        slot(kind).retry = true;
    }

    /** Whether the map was read this cycle (a second read in one cycle is not made). */
    public synchronized boolean loadedIn(String kind, long cycle) {
        return slot(kind).loadedCycle == cycle;
    }

    /**
     * Whether an object missing from the map justifies reading it again: at
     * least a cycle interval has passed since the last read, or the object was
     * not there the cycle before either (a new object, not a persistent miss).
     */
    public synchronized boolean missJustifiesRead(String kind, long cycle, boolean anyNew) {
        Slot s = slot(kind);
        return s.loadedCycle != cycle && (anyNew || cycle - s.loadedCycle >= REFRESH_EVERY);
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
