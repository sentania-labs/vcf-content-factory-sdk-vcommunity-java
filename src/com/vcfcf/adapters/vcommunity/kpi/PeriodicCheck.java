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
     * Colour bands, judged on a KPI's health (its value, or 100 less it where higher is worse); a value exactly on
     * a limit counts for the better band. The thresholds are 25, 50 and 75, as 0.8.1 behaves (the map's 20 and
     * 40 is wrong about that; design 5.8, M1).
     */
    static final double RED_BELOW = 25.0;
    static final double ORANGE_BELOW = 50.0;
    static final double YELLOW_BELOW = 75.0;

    static String bandOf(double value, boolean higherIsWorse) {
        double health = higherIsWorse ? 100.0 - value : value;
        if (health < RED_BELOW) return "red";
        if (health < ORANGE_BELOW) return "orange";
        if (health < YELLOW_BELOW) return "yellow";
        return "green";
    }

    /** 1 when the cluster should take no new VMs: consumption or performance in the red band, or both in orange with risk at least orange. */
    public static int closeFlag(double performance, double risk, double consumption) {
        String p = bandOf(performance, false);
        String r = bandOf(risk, ClusterRisk.HIGHER_IS_WORSE);
        String c = bandOf(consumption, ClusterConsumption.HIGHER_IS_WORSE);
        if ("red".equals(p) || "red".equals(c)) return 1;
        if ("orange".equals(p) && "orange".equals(c) && ("orange".equals(r) || "red".equals(r))) return 1;
        return 0;
    }

    /**
     * What one cluster publishes for a window, by key and not yet rounded, from its samples per KPI. A value with
     * no samples is left out, and the closed-for-business flag needs all three of the others.
     */
    public static Map<String, Double> valuesOver(Map<String, List<Double>> samples, int worstPercent) {
        Map<String, Double> v = new LinkedHashMap<>();
        Double perf = Percentile.worstTail(samples.getOrDefault(StatKeys.CLUSTER_PERFORMANCE_SCORE,
                java.util.Collections.<Double>emptyList()), worstPercent, false);
        Double risk = Percentile.worstTail(samples.getOrDefault(StatKeys.CLUSTER_RISK_SCORE,
                java.util.Collections.<Double>emptyList()), worstPercent, ClusterRisk.HIGHER_IS_WORSE);
        Double cons = Percentile.worstTail(samples.getOrDefault(StatKeys.CLUSTER_CONSUMPTION_SCORE,
                java.util.Collections.<Double>emptyList()), worstPercent, ClusterConsumption.HIGHER_IS_WORSE);
        if (perf != null) v.put(StatKeys.PERIODIC_PERFORMANCE, perf);
        if (risk != null) v.put(StatKeys.PERIODIC_RISK, risk);
        if (cons != null) v.put(StatKeys.PERIODIC_CONSUMPTION, cons);
        if (perf != null && risk != null && cons != null) {
            v.put(StatKeys.CLOSE_FOR_BUSINESS, (double) closeFlag(perf, risk, cons));
        }
        return v;
    }

    /** The value as published: rounded to two decimals. */
    public static double published(double v) {
        return PyRound.round2(v);
    }

    /** How many clusters changed between the two windows: {closed (0 then 1), opened (1 then 0)}. A cluster with no value in either window does not take part. */
    public static int[] flips(Map<String, Double> previous, Map<String, Double> current) {
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
