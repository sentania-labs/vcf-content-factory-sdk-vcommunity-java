package com.vcfcf.adapters.vcommunity.coll;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one vCenter object gave: the keys to push onto its Ops resource, and
 * how many values were left out. A collector fills it with the pure helpers
 * below; nothing here knows a resource id, so a collector is a plain function
 * of the object's row (design 4.2, "collectors are pure functions").
 *
 * <p>A key whose value is {@code null} is not queued and not counted: a value
 * vCenter answered without is simply absent (DV-112). A value that could not be
 * read is counted by the caller with {@link #withhold}, so it reaches Collection
 * Health (design 5.1 rule 2).
 */
public final class ObjectOut {
    /** The Ops resource kind this object maps to ({@code HostSystem}, ...). */
    public final String kind;
    /** The vCenter MoRef value ({@code host-12}), or null for the vCenter itself. */
    public final String moref;
    private final Map<String, Object> props = new LinkedHashMap<>();
    private final Map<String, Double> stats = new LinkedHashMap<>();
    private int withheld;

    public ObjectOut(String kind, String moref) {
        this.kind = kind;
        this.moref = moref;
    }

    /** A string property; null is left out and not counted. */
    public ObjectOut prop(String key, String value) {
        if (value != null) props.put(key, value);
        return this;
    }

    /** A numeric property (sent as a number, not text). */
    public ObjectOut numProp(String key, double value) {
        props.put(key, value);
        return this;
    }

    /** A metric. */
    public ObjectOut stat(String key, double value) {
        stats.put(key, value);
        return this;
    }

    /** Count values that were not sent for a reason the caller knows. */
    public ObjectOut withhold(int n) {
        withheld += n;
        return this;
    }

    public Map<String, Object> props() {
        return Collections.unmodifiableMap(props);
    }

    public Map<String, Double> stats() {
        return Collections.unmodifiableMap(stats);
    }

    public int withheld() {
        return withheld;
    }

    public boolean isEmpty() {
        return props.isEmpty() && stats.isEmpty();
    }
}
