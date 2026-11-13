package com.vcfcf.adapters.vcommunity.vim;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One object of a property collector answer: its reference and the values of
 * the paths it returned, each as the (pruned) element tree of its {@code val}.
 * A path that is not in {@link #val} was not returned: unset, or named in the
 * object's missing set. Both mean "absent" to a collector (design 5.1 rule 2);
 * neither is ever read as a value.
 */
public final class BulkRow {
    public final MoRef obj;
    private final Map<String, XmlNode> values = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final Map<String, String> missingFault = new HashMap<>();

    BulkRow(MoRef obj) {
        this.obj = obj;
    }

    /** For tests that build a row by hand. */
    public static BulkRow of(MoRef obj, Map<String, XmlNode> values) {
        BulkRow r = new BulkRow(obj);
        r.values.putAll(values);
        return r;
    }

    void put(String path, XmlNode val) {
        values.put(path, val);
    }

    void miss(String path, String faultType) {
        missing.add(path);
        if (faultType != null) missingFault.put(path, faultType);
    }

    /** What vCenter said went wrong reading a path of the missing set ({@code NoPermission}), or null. */
    public String missingFault(String path) {
        return missingFault.get(path);
    }

    /** The value element of a path, or null when it was not returned. */
    public XmlNode val(String path) {
        return values.get(path);
    }

    /** True when the path came back, empty arrays included. */
    public boolean has(String path) {
        return values.containsKey(path);
    }

    /** The object's {@code name} property, or null. */
    public String name() {
        XmlNode n = values.get("name");
        return n == null ? null : n.rawText();
    }

    public Set<String> missing() {
        return Collections.unmodifiableSet(missing);
    }

    public int size() {
        return values.size();
    }
}
