package com.vcfcf.adapters.vcommunity.ops;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ids of every resource of a small kind, of every adapter instance, for the roll-ups that read all
 * vCenters and are not on the maps of this vCenter's objects (the vSphere World object, the vCenter objects,
 * the Container kinds of the Business Applications). Read every cycle, as the original reads it, or when
 * missing; a failed read keeps the previous list and is tried again next cycle, and the caller does not use
 * the list of a refresh that failed. Used from the cycle thread.
 */
public final class UnscopedIds {

    private static final class Slot {
        List<String> ids;
        long loadedCycle = Long.MIN_VALUE / 2;
        boolean retry;
    }

    private final Map<String, Slot> slots = new HashMap<>();

    /**
     * The ids of the kind, read now when due; null when there is no list at all (the first read failed).
     *
     * @param everyCycle true for a kind that is read every cycle
     * @throws IOException only when a list is needed and none could be read, and none is kept
     */
    public List<String> get(OpsApi ops, String adapterKind, String kind, long cycle, boolean everyCycle,
            String[] failure) throws InterruptedException {
        String key = adapterKind + "|" + kind;
        Slot s = slots.computeIfAbsent(key, k -> new Slot());
        boolean due = s.ids == null || s.retry || everyCycle || cycle - s.loadedCycle >= OpsMapCache.REFRESH_EVERY;
        if (due) {
            try {
                s.ids = OpsResourceMap.listIds(ops, adapterKind, kind);
                s.loadedCycle = cycle;
                s.retry = false;
            } catch (IOException | RuntimeException e) {
                s.retry = true;
                failure[0] = e.getClass().getSimpleName() + (e instanceof OpsHttpException
                        ? " (HTTP " + ((OpsHttpException) e).status() + ")" : "");
                return s.ids == null ? null : Collections.unmodifiableList(s.ids);
            }
        }
        return Collections.unmodifiableList(s.ids);
    }
}
