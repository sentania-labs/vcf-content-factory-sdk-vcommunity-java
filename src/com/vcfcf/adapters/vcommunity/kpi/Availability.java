package com.vcfcf.adapters.vcommunity.kpi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Operational Availability (map D8): per host, per cluster, and the lowest cluster of a vCenter. */
public final class Availability {

    private Availability() {}

    public static final String GROUP = "Availability";
    public static final double POINTS_PER_CONDITION = 25.0;

    /**
     * 25 points for each condition met: powered on (the statistic is 1), connected, not in maintenance,
     * not in quarantine. A condition with no data is not met, so the value is 0, 25, 50, 75 or 100.
     *
     * @param poweredOn the latest {@code sys|poweredOn} sample, or null
     * @param props     the three runtime properties of the host
     */
    public static double host(Double poweredOn, Map<String, String> props) {
        int met = 0;
        if (poweredOn != null && poweredOn == 1.0) met++;
        if ("connected".equals(props.get(StatKeys.HOST_CONNECTION))) met++;
        if ("notInMaintenance".equals(props.get(StatKeys.HOST_MAINTENANCE))) met++;
        if ("notInQuarantine".equals(props.get(StatKeys.HOST_QUARANTINE))) met++;
        return POINTS_PER_CONDITION * met;
    }

    /**
     * {@code min(100, (sum - the k lowest) / (N - k))}. N is the cluster's host count or the hosts found, if
     * larger; k the host failures vSphere HA tolerates (none counts as 0). Hosts counted in N but not found
     * count as 0 %. Null when N - k is 0 or less.
     */
    public static Double cluster(List<Double> hostValues, Double hostCount, Double failoverLevel) {
        int n = Math.max(hostCount == null ? 0 : (int) (double) hostCount, hostValues.size());
        int k = failoverLevel == null ? 0 : (int) (double) failoverLevel;
        if (n - k <= 0) return null;
        List<Double> values = new ArrayList<>(hostValues);
        for (int i = hostValues.size(); i < n; i++) values.add(0.0);
        Collections.sort(values);
        double sum = 0;
        for (double d : values) sum += d;
        // Python slicing: values[:k] is the first k, and for a negative k all but the last |k|
        int cut = k >= 0 ? Math.min(k, values.size()) : Math.max(values.size() + k, 0);
        double lowest = 0;
        for (int i = 0; i < cut; i++) lowest += values.get(i);
        return Math.min(100.0, (sum - lowest) / (n - k));
    }

    /** The lowest of the values; null for none. */
    public static Double worst(List<Double> values) {
        Double min = null;
        for (Double d : values) if (d != null && (min == null || d < min)) min = d;
        return min;
    }
}
