package com.vcfcf.adapters.vcommunity.kpi;

import java.util.Map;

/** Small helpers for formulas over a map of the statistics one object has; a missing statistic is absent. */
public final class Stat {

    private Stat() {}

    /** True when every key has a value: a formula with one missing must give no value. */
    public static boolean hasAll(Map<String, Double> stats, String... keys) {
        for (String k : keys) if (stats.get(k) == null) return false;
        return true;
    }

    /** {@code part / whole x 100}; null when whole is 0 or less. */
    public static Double percent(double part, double whole) {
        if (whole <= 0) return null;
        return part / whole * 100.0;
    }

    /** Python truthiness of a number: present and not zero. */
    static boolean truthy(Double v) {
        return v != null && v != 0.0;
    }

    static boolean truthy(double v) {
        return v != 0.0;
    }
}
