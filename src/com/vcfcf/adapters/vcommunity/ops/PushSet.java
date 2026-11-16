package com.vcfcf.adapters.vcommunity.ops;

import com.vcfcf.adapters.vcommunity.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The one way a value is queued for a push (design 5.6): per resource id, per
 * key, one string property, one numeric property (a {@link Double}) or one
 * finite stat. It refuses, counts and logs
 * once per key what must never be sent: a null or blank resource id or key, a
 * null value, a NaN or an infinity. A refusal is a programming error in a
 * collector, not a failed read, so it is counted apart ({@link #refused()}).
 * Not thread safe: the caller thread builds and pushes it.
 */
public final class PushSet {

    private final Map<String, Map<String, Object>> props = new LinkedHashMap<>();
    private final Map<String, Map<String, Double>> stats = new LinkedHashMap<>();
    private final Set<String> warnedKeys = new HashSet<>();
    private final Log log;
    private int refused;
    private int propCount;
    private int statCount;

    public PushSet(Log log) {
        this.log = log == null ? Log.NONE : log;
    }

    /** Queue a string property; false when it was refused. */
    public boolean prop(String resourceId, String key, String value) {
        if (!valid(resourceId, key) || value == null) {
            return refuse(key, value == null ? "a null value" : "a blank id or key");
        }
        Map<String, Object> m = props.computeIfAbsent(resourceId, k -> new LinkedHashMap<>());
        if (m.put(key, value) == null) propCount++;
        return true;
    }

    /** Queue a numeric property (design 4.4, R43); false when it was refused (NaN, infinity, blank id or key). */
    public boolean numProp(String resourceId, String key, double value) {
        if (!valid(resourceId, key) || Double.isNaN(value) || Double.isInfinite(value)) {
            return refuse(key, Double.isNaN(value) || Double.isInfinite(value)
                    ? "a NaN or infinite number" : "a blank id or key");
        }
        Map<String, Object> m = props.computeIfAbsent(resourceId, k -> new LinkedHashMap<>());
        if (m.put(key, value) == null) propCount++;
        return true;
    }

    /** Queue a number; false when it was refused (NaN, infinity, blank id or key). */
    public boolean stat(String resourceId, String key, double value) {
        if (!valid(resourceId, key) || Double.isNaN(value) || Double.isInfinite(value)) {
            return refuse(key, Double.isNaN(value) || Double.isInfinite(value)
                    ? "a NaN or infinite number" : "a blank id or key");
        }
        Map<String, Double> m = stats.computeIfAbsent(resourceId, k -> new LinkedHashMap<>());
        if (m.put(key, value) == null) statCount++;
        return true;
    }

    private static boolean valid(String id, String key) {
        return id != null && !id.trim().isEmpty() && key != null && !key.trim().isEmpty();
    }

    private boolean refuse(String key, String why) {
        refused++;
        String k = key == null ? "(null)" : key;
        if (warnedKeys.add(k)) {
            log.warn("push refused for key '" + k + "': " + why + " (a collector bug, nothing sent)");
        }
        return false;
    }

    /** Per resource, per key: a {@link String} property or a {@link Double} numeric property. */
    public Map<String, Map<String, Object>> properties() {
        return Collections.unmodifiableMap(props);
    }

    public Map<String, Map<String, Double>> stats() {
        return Collections.unmodifiableMap(stats);
    }

    public int propertyCount() {
        return propCount;
    }

    public int statCount() {
        return statCount;
    }

    /** Values refused as programming errors. */
    public int refused() {
        return refused;
    }

    public boolean isEmpty() {
        return propCount == 0 && statCount == 0;
    }
}
