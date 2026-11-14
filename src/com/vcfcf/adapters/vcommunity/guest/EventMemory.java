package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.ops.OpsEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which events were sent lately (design 8.6, DV-98). The script looks at the last five minutes every cycle,
 * so an event stays in view for the next cycle or two; without a memory each cycle would send it again. An
 * event is the same event when it is on the same object with the same message (the message carries the time
 * the record was written). It is sent once, and not again for {@code ttlMs}.
 *
 * <p>The memory is a bounded table of 64-bit hashes (never the text), so a hostile guest cannot grow it
 * past {@code capacity} entries; when it is full the oldest entry goes first. It is in memory only: after a
 * restart the events still in the window are sent once more, as the original does after its own restart. Not
 * thread safe; the cycle thread owns it.
 */
public final class EventMemory {

    public static final long TTL_MS = 2 * 60 * 60 * 1000L;
    public static final int CAPACITY = 50_000;

    private final long ttlMs;
    private final int capacity;
    private final LinkedHashMap<Long, Long> seen;

    public EventMemory() {
        this(TTL_MS, CAPACITY);
    }

    public EventMemory(long ttlMs, int capacity) {
        this.ttlMs = ttlMs;
        this.capacity = capacity;
        this.seen = new LinkedHashMap<>();
    }

    /**
     * The events not sent within the time to live, each once (the same event twice in the list counts once).
     * Nothing is remembered yet: call {@link #remember} when the send was clean.
     */
    public List<OpsEvent> fresh(List<OpsEvent> events, long nowMs) {
        expire(nowMs);
        List<OpsEvent> out = new ArrayList<>();
        java.util.Set<Long> inThisList = new java.util.HashSet<>();
        for (OpsEvent e : events) {
            long h = hash(e);
            if (seen.containsKey(h) || !inThisList.add(h)) continue;
            out.add(e);
        }
        return out;
    }

    /** Record that these were sent. */
    public void remember(List<OpsEvent> sent, long nowMs) {
        for (OpsEvent e : sent) {
            seen.remove(hash(e));
            seen.put(hash(e), nowMs);
            if (seen.size() > capacity) {
                Iterator<Long> it = seen.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    public int size() {
        return seen.size();
    }

    private void expire(long nowMs) {
        Iterator<Map.Entry<Long, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > ttlMs) it.remove();
            else break; // oldest first: the rest are younger
        }
    }

    static long hash(OpsEvent e) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(e.resourceId.getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            d.update(e.message.getBytes(StandardCharsets.UTF_8));
            byte[] b = d.digest();
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (b[i] & 0xff);
            return h;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", ex);
        }
    }
}
