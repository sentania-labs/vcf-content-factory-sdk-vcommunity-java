package com.vcfcf.adapters.vcommunity.guest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the guest phase starts (design 8.1, DV-43). The VMs are taken in one fixed order (by MoRef); each cycle
 * begins with the first one after the last VM the previous cycle started, so when the cycle's time is not
 * enough for all of them, the ones left over are the first next time and every VM is reached in turn.
 * Memory only: a restart begins at the first.
 */
public final class GuestCursor {

    private String lastStarted;

    /** The candidates in the order this cycle starts them. */
    public synchronized <T> List<T> order(List<T> candidates, java.util.function.Function<T, String> key) {
        List<T> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> key.apply(a).compareTo(key.apply(b)));
        if (lastStarted == null) return sorted;
        int from = 0;
        while (from < sorted.size() && key.apply(sorted.get(from)).compareTo(lastStarted) <= 0) from++;
        if (from >= sorted.size()) return sorted;
        List<T> out = new ArrayList<>(sorted.subList(from, sorted.size()));
        out.addAll(sorted.subList(0, from));
        return Collections.unmodifiableList(out);
    }

    /** The VM the phase started last. */
    public synchronized void started(String key) {
        lastStarted = key;
    }
}
