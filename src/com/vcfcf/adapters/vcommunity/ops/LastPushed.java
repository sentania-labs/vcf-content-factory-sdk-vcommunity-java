package com.vcfcf.adapters.vcommunity.ops;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The last property values sent, as 64-bit hashes per resource and key, so a
 * property goes out only when its value changed, on the first cycle after a
 * start, and in a full refresh every {@code refreshEvery}th cycle (DV-49).
 * Stats are not filtered. A value counts as sent only after
 * {@link Selection#commit()}, which the caller calls when the push was clean:
 * a failed push is sent again next cycle. In memory only, so a start sends
 * everything.
 *
 * <p>Compact on purpose (design 9.3, a retained budget of 35 MB for the whole instance): one open
 * addressing table of two longs per value, keyed by a 64-bit hash of the resource id and the key.
 * Ten thousand VMs with sixty keys each take about 16 MB, where a map of maps of strings took
 * 87 MB. A key hash that collides with another's (2^-40 for this many values) would at worst
 * suppress one change until the next full refresh.
 */
public final class LastPushed {

    private final int refreshEvery;
    private LongTable table = new LongTable(1024);

    public LastPushed(int refreshEvery) {
        this.refreshEvery = Math.max(1, refreshEvery);
    }

    /** What to send, and what to remember once it was sent. */
    public final class Selection {
        private Map<String, Map<String, Object>> toSend = new LinkedHashMap<>();
        private long[] pendingKeys = new long[16];
        private long[] pendingValues = new long[16];
        private int pending;
        private int considered;

        /** The properties to send this cycle. */
        public Map<String, Map<String, Object>> toSend() {
            return toSend;
        }

        /** Properties held back because they did not change. */
        public int unchanged() {
            return considered - pending;
        }

        /** Remember the selected values as sent. */
        public void commit() {
            for (int i = 0; i < pending; i++) table.put(pendingKeys[i], pendingValues[i]);
            pending = 0;
        }

        private void add(long k, long v) {
            if (pending == pendingKeys.length) {
                pendingKeys = Arrays.copyOf(pendingKeys, pending * 2);
                pendingValues = Arrays.copyOf(pendingValues, pending * 2);
            }
            pendingKeys[pending] = k;
            pendingValues[pending++] = v;
        }
    }

    /**
     * @param all     every property of this cycle
     * @param cycleNo this cycle's number, counted from 1 since the start
     */
    public Selection select(Map<String, Map<String, Object>> all, long cycleNo) {
        Selection sel = new Selection();
        boolean full = cycleNo % refreshEvery == 0;
        int total = 0;
        for (Map<String, Object> m : all.values()) total += m.size();
        // on a full refresh, keep only what is still there: a resource that has left and a key a
        // resource no longer has are forgotten, so the table does not grow with every object that existed
        LongTable next = full ? new LongTable(total) : table;
        BitSet send = new BitSet(total);
        int i = 0;
        for (Map.Entry<String, Map<String, Object>> r : all.entrySet()) {
            for (Map.Entry<String, Object> p : r.getValue().entrySet()) {
                sel.considered++;
                long k = keyHash(r.getKey(), p.getKey());
                long h = valueHash(p.getValue());
                long old = table.get(k);
                boolean known = old != LongTable.NONE;
                if (full && known) next.put(k, old);
                if (full || !known || old != h) {
                    sel.add(k, h);
                    send.set(i);
                }
                i++;
            }
        }
        table = next;
        if (sel.pending == sel.considered) {
            sel.toSend = all; // everything goes: no second copy of the maps
        } else {
            i = 0;
            for (Map.Entry<String, Map<String, Object>> r : all.entrySet()) {
                Map<String, Object> keep = null;
                for (Map.Entry<String, Object> p : r.getValue().entrySet()) {
                    if (send.get(i++)) {
                        if (keep == null) keep = sel.toSend.computeIfAbsent(r.getKey(), x -> new LinkedHashMap<>());
                        keep.put(p.getKey(), p.getValue());
                    }
                }
            }
        }
        return sel;
    }

    /** How many values are remembered (for tests and the heap budget). */
    public int tracked() {
        return table.size();
    }

    /** Forget everything (the next cycle sends all). */
    public void clear() {
        table = new LongTable(1024);
    }

    private static long keyHash(String rid, String key) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < rid.length(); i++) {
            h ^= rid.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= 0xff;
        h *= 0x100000001b3L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix(h);
    }

    private static long valueHash(Object v) {
        if (v instanceof String) return hash((String) v);
        return mix(Double.doubleToLongBits(((Number) v).doubleValue()) ^ 0x6e756d62657200L);
    }

    /** FNV-1a, 64 bit, over the characters, then mixed. */
    static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix(h);
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** An open addressing map from a non-zero long to a long. */
    private static final class LongTable {
        static final long NONE = Long.MIN_VALUE + 7; // a value no hash is taken to produce

        private long[] keys;
        private long[] vals;
        private int size;
        private int mask;

        LongTable(int expected) {
            int cap = 16;
            while (cap * 0.6 < expected) cap <<= 1;
            keys = new long[cap];
            vals = new long[cap];
            mask = cap - 1;
        }

        int size() {
            return size;
        }

        long get(long key) {
            long k = key == 0 ? 1 : key;
            int i = (int) (k ^ (k >>> 32)) & mask;
            while (keys[i] != 0) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & mask;
            }
            return NONE;
        }

        void put(long key, long val) {
            if ((size + 1) > keys.length * 0.6) grow();
            long k = key == 0 ? 1 : key;
            int i = (int) (k ^ (k >>> 32)) & mask;
            while (keys[i] != 0) {
                if (keys[i] == k) {
                    vals[i] = val;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = k;
            vals[i] = val;
            size++;
        }

        private void grow() {
            long[] ok = keys;
            long[] ov = vals;
            int cap = ok.length * 2;
            keys = new long[cap];
            vals = new long[cap];
            mask = cap - 1;
            size = 0;
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] != 0) put(ok[j], ov[j]);
            }
        }
    }
}
