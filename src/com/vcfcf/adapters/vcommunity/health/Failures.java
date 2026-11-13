package com.vcfcf.adapters.vcommunity.health;

import com.vcfcf.adapters.vcommunity.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One cycle's failure accounting (design 5.1 rule 1): a read that failed
 * sends nothing, and is counted here, once per cause per object. A failed
 * read unit is one distinct (cause, object) pair; the keys it withheld are
 * added up. Thread safe: collectors on a pool report into one instance.
 *
 * <p>Every message passed in must already be free of credentials, tokens,
 * session ids and license keys; this class does not clean them, it only
 * bounds their length.
 */
public final class Failures {

    public static final int MAX_TEXT = 250;

    private final Set<String> units = new HashSet<>();
    private final Map<String, Integer> unitsByCollector = new LinkedHashMap<>();
    private final Map<String, int[]> byCause = new LinkedHashMap<>(); // [units, withheld]
    private final Map<String, String> firstObject = new LinkedHashMap<>();
    private final Map<String, String> firstMessage = new LinkedHashMap<>();
    private int withheld;
    private String first;

    /**
     * Record a failed read.
     *
     * @param collector    the collector the failure belongs to ({@code check_lists}, {@code vcenter}, ...)
     * @param cause        a {@link FailureCause} id
     * @param object       what was being read (a MoRef, a kind, a file name)
     * @param keysWithheld how many values were therefore not sent (0 when not known)
     * @param message      credential-free text of what went wrong
     */
    public synchronized void add(String collector, String cause, String object, int keysWithheld,
            String message) {
        String unit = cause + "\u0000" + object;
        boolean isNew = units.add(unit);
        if (isNew) {
            unitsByCollector.merge(collector, 1, Integer::sum);
        }
        int[] c = byCause.computeIfAbsent(cause, k -> new int[2]);
        if (isNew) c[0]++;
        if (keysWithheld > 0) {
            c[1] += keysWithheld;
            withheld += keysWithheld;
        }
        firstObject.putIfAbsent(cause, object);
        firstMessage.putIfAbsent(cause, message);
        if (first == null) first = bound(cause + ": " + message);
    }

    /** Failed read units this cycle. */
    public synchronized int readsFailed() {
        return units.size();
    }

    /** Object x key values not sent because their read failed. */
    public synchronized int valuesWithheld() {
        return withheld;
    }

    /** Failed read units of one collector. */
    public synchronized int forCollector(String collector) {
        return unitsByCollector.getOrDefault(collector, 0);
    }

    /** The first failure's text, or null when there was none. */
    public synchronized String first() {
        return first;
    }

    public synchronized boolean isEmpty() {
        return units.isEmpty();
    }

    /** The causes seen, in the order first seen. */
    public synchronized Map<String, Integer> causes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : byCause.entrySet()) m.put(e.getKey(), e.getValue()[0]);
        return Collections.unmodifiableMap(m);
    }

    /** One WARN line per cause: how many units, how many values withheld, the first object. */
    public synchronized void logSummary(Log log) {
        for (Map.Entry<String, int[]> e : byCause.entrySet()) {
            String cause = e.getKey();
            log.warn(cause + ": " + e.getValue()[0] + " failed read(s), " + e.getValue()[1]
                    + " value(s) not sent; first: " + firstObject.get(cause) + ": "
                    + bound(firstMessage.get(cause)));
        }
    }

    /** One bounded line: control characters (newlines) become spaces. For any text a server sent. */
    public static String bound(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]", " ");
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }
}
