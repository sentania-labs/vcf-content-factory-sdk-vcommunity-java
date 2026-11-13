package com.vcfcf.adapters.vcommunity.ops;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last property values sent, as 64-bit hashes per resource and key, so a
 * property goes out only when its value changed, on the first cycle after a
 * start, and in a full refresh every {@code refreshEvery}th cycle (DV-49).
 * Stats are not filtered. A value counts as sent only after
 * {@link Selection#commit()}, which the caller calls when the push was clean:
 * a failed push is sent again next cycle. In memory only, so a start sends
 * everything.
 */
public final class LastPushed {

    private final int refreshEvery;
    private final Map<String, Map<String, Long>> hashes = new HashMap<>();

    public LastPushed(int refreshEvery) {
        this.refreshEvery = Math.max(1, refreshEvery);
    }

    /** What to send, and what to remember once it was sent. */
    public final class Selection {
        private final Map<String, Map<String, Object>> toSend = new LinkedHashMap<>();
        private final List<Object[]> pending = new ArrayList<>();
        private int considered;

        /** The properties to send this cycle. */
        public Map<String, Map<String, Object>> toSend() {
            return toSend;
        }

        /** Properties held back because they did not change. */
        public int unchanged() {
            return considered - pending.size();
        }

        /** Remember the selected values as sent. */
        public void commit() {
            for (Object[] p : pending) {
                hashes.computeIfAbsent((String) p[0], k -> new HashMap<>()).put((String) p[1],
                        (Long) p[2]);
            }
        }
    }

    /**
     * @param all     every property of this cycle
     * @param cycleNo this cycle's number, counted from 1 since the start
     */
    public Selection select(Map<String, Map<String, Object>> all, long cycleNo) {
        Selection sel = new Selection();
        boolean full = cycleNo % refreshEvery == 0;
        if (full) prune(all);
        for (Map.Entry<String, Map<String, Object>> r : all.entrySet()) {
            Map<String, Long> known = hashes.get(r.getKey());
            for (Map.Entry<String, Object> p : r.getValue().entrySet()) {
                sel.considered++;
                long h = hash(p.getValue() instanceof String ? (String) p.getValue()
                        : "\u0001n:" + p.getValue());
                Long old = known == null ? null : known.get(p.getKey());
                if (full || old == null || old != h) {
                    sel.toSend.computeIfAbsent(r.getKey(), k -> new LinkedHashMap<>())
                            .put(p.getKey(), p.getValue());
                    sel.pending.add(new Object[] {r.getKey(), p.getKey(), h});
                }
            }
        }
        return sel;
    }

    /**
     * On a full refresh, forget what is no longer there: a resource that has left, and a key
     * a resource no longer has. Without it the table would grow with every object that ever
     * existed. What was forgotten in error (a read that failed this cycle) is sent again.
     */
    private void prune(Map<String, Map<String, Object>> current) {
        hashes.keySet().retainAll(current.keySet());
        for (Map.Entry<String, Map<String, Long>> e : hashes.entrySet()) {
            e.getValue().keySet().retainAll(current.get(e.getKey()).keySet());
        }
    }

    /** How many values are remembered (for tests and the heap budget). */
    public int tracked() {
        int n = 0;
        for (Map<String, Long> m : hashes.values()) n += m.size();
        return n;
    }

    /** Forget everything (the next cycle sends all). */
    public void clear() {
        hashes.clear();
    }

    /** FNV-1a, 64 bit, over the UTF-8 bytes. */
    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
