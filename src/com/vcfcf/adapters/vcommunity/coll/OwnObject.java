package com.vcfcf.adapters.vcommunity.coll;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An object of one of the pak's own kinds ({@code Content Library}, {@code Library Item},
 * {@code VM Template}): its name, its identifiers and the keys to put on it. A collector builds
 * it as a pure function of what was read; nothing here knows the SDK. A key whose value is
 * {@code null} is not queued, and the flagged ones are counted as withheld (design 5.1).
 */
public final class OwnObject {

    public final String kind;
    public final String name;
    private final Map<String, String> identifiers = new LinkedHashMap<>();
    private final Map<String, String> props = new LinkedHashMap<>();
    private final Map<String, Double> stats = new LinkedHashMap<>();
    private int withheld;

    public OwnObject(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }

    /** An identifying identifier, in the order the describe lists them. */
    public OwnObject id(String key, String value) {
        identifiers.put(key, value);
        return this;
    }

    /** A string property; null is left out and not counted. */
    public OwnObject prop(String key, String value) {
        if (value != null) props.put(key, value);
        return this;
    }

    /** A string property whose absence is counted as a withheld value. */
    public OwnObject propY(String key, String value) {
        if (value != null) {
            props.put(key, value);
        } else {
            withheld++;
        }
        return this;
    }

    /** A metric; null is left out and not counted. */
    public OwnObject stat(String key, Double value) {
        if (value != null) stats.put(key, value);
        return this;
    }

    /** A metric whose absence is counted as a withheld value. */
    public OwnObject statY(String key, Double value) {
        if (value != null) {
            stats.put(key, value);
        } else {
            withheld++;
        }
        return this;
    }

    /** Count values that were not sent for a reason the caller knows. */
    public OwnObject withhold(int n) {
        withheld += n;
        return this;
    }

    public Map<String, String> identifiers() {
        return Collections.unmodifiableMap(identifiers);
    }

    public Map<String, String> props() {
        return Collections.unmodifiableMap(props);
    }

    public Map<String, Double> stats() {
        return Collections.unmodifiableMap(stats);
    }

    public int withheld() {
        return withheld;
    }

    /** What tells this object from every other of the pak: its kind and identifier values. */
    public String identity() {
        return identity(kind, identifiers);
    }

    public static String identity(String kind, Map<String, String> identifiers) {
        StringBuilder b = new StringBuilder(kind);
        for (Map.Entry<String, String> e : identifiers.entrySet()) {
            b.append('\u0000').append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }
}
