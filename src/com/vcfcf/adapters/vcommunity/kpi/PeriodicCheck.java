package com.vcfcf.adapters.vcommunity.kpi;

import com.vcfcf.adapters.vcommunity.text.PyRound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calculation of the Periodic Cluster Check (map D9): from the samples of the three cluster KPIs over
 * the window, the worst of each, and whether the cluster is closed for business. Pure.
 */
public final class PeriodicCheck {

    private PeriodicCheck() {}

    public static final int MAX_CLUSTERS_PER_CYCLE = 500;
    public static final int CLUSTERS_PER_REQUEST = 100;
    public static final int DEFAULT_WORST_PERCENT = 5;

    /**
     * The bands, on a KPI's health (the value, or 100 minus it when higher is worse); a value on a limit
     * belongs to the better band. Upstream 0.8.1 uses 25, 50 and 75; the map's D9 text says 20 and 40 and is
     * wrong about the code (design 5.8, M1).
     */
    static final double RED_BELOW = 25.0;
    static final double ORANGE_BELOW = 50.0;
    static final double YELLOW_BELOW = 75.0;

    static String band(double value, boolean higherIsWorse) {
        double health = higherIsWorse ? 100.0 - value : value;
        if (health < RED_BELOW) return "red";
        if (health < ORANGE_BELOW) return "orange";
        if (health < YELLOW_BELOW) return "yellow";
        return "green";
    }

    /** 1 when the cluster is full for new VMs: consumption or performance red, or both orange with risk orange or red. */
    public static int decide(double performance, double risk, double consumption) {
        String p = band(performance, false);
        String r = band(risk, ClusterRisk.HIGHER_IS_WORSE);
        String c = band(consumption, ClusterConsumption.HIGHER_IS_WORSE);
        if ("red".equals(p) || "red".equals(c)) return 1;
        if ("orange".equals(p) && "orange".equals(c) && ("orange".equals(r) || "red".equals(r))) return 1;
        return 0;
    }

    /**
     * The values of one cluster, by published key (not rounded), from the window's samples by KPI key. A value
     * with no samples is absent; Close for Business only when all three are there.
     */
    public static Map<String, Double> windowValues(Map<String, List<Double>> samples, int worstPercent) {
        Map<String, Double> v = new LinkedHashMap<>();
        Double perf = Percentile.worst(samples.getOrDefault(StatKeys.CLUSTER_PERFORMANCE_SCORE,
                java.util.Collections.<Double>emptyList()), worstPercent, false);
        Double risk = Percentile.worst(samples.getOrDefault(StatKeys.CLUSTER_RISK_SCORE,
                java.util.Collections.<Double>emptyList()), worstPercent, ClusterRisk.HIGHER_IS_WORSE);
        Double cons = Percentile.worst(samples.getOrDefault(StatKeys.CLUSTER_CONSUMPTION_SCORE,
                java.util.Collections.<Double>emptyList()), worstPercent, ClusterConsumption.HIGHER_IS_WORSE);
        if (perf != null) v.put(StatKeys.PERIODIC_PERFORMANCE, perf);
        if (risk != null) v.put(StatKeys.PERIODIC_RISK, risk);
        if (cons != null) v.put(StatKeys.PERIODIC_CONSUMPTION, cons);
        if (perf != null && risk != null && cons != null) {
            v.put(StatKeys.CLOSE_FOR_BUSINESS, (double) decide(perf, risk, cons));
        }
        return v;
    }

    /** The value as published: rounded to two decimals. */
    public static double published(double v) {
        return PyRound.round2(v);
    }

    /** {@code (clusters that went from 0 to 1, clusters that went from 1 to 0)}; a cluster missing on either side is not counted. */
    public static int[] transitions(Map<String, Double> previous, Map<String, Double> current) {
        int closed = 0;
        int opened = 0;
        for (Map.Entry<String, Double> e : current.entrySet()) {
            Double before = previous.get(e.getKey());
            double now = e.getValue();
            if (before != null && before == 0 && now == 1) closed++;
            else if (before != null && before == 1 && now == 0) opened++;
        }
        return new int[] {closed, opened};
    }
}
