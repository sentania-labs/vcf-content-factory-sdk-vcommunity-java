package com.vcfcf.adapters.vcommunity.kpi;

import com.vcfcf.adapters.vcommunity.text.PyRound;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The scoring every KPI shares (design 7.1). Plain arithmetic in the order the original does it, so
 * the floating point result is the same: a row has five limits scoring 100, 75, 50, 25 and 0, linear
 * inside a tier; its weight is {@code 2^((100 - score) / 25)}; the KPI is the weighted mean of the
 * rows, rounded to two decimals ({@link PyRound#round2}). A row with no value is left out, never
 * scored as 0.
 */
public final class KpiScorer {

    private KpiScorer() {}

    private static final double SCORE_DROP_PER_TIER = 25.0;
    private static final double[] TIER_START_SCORE = {100.0, 75.0, 50.0, 25.0};

    public static double weight(double score) {
        return Math.pow(2.0, (100.0 - score) / SCORE_DROP_PER_TIER);
    }

    /** True when the value is in the green tier: at or below best, or below the green limit. */
    public static boolean isGreen(KpiMetric m, double value) {
        double v = value;
        double best = m.best;
        double green = m.green;
        if (m.zeroAt < m.best) {
            v = -value;
            best = -m.best;
            green = -m.green;
        }
        return v <= best || v < green;
    }

    /** The score of one row value, 0 to 100. */
    public static double score(KpiMetric m, double value) {
        double[] limits = {m.best, m.green, m.yellow, m.orange, m.zeroAt};
        double v = value;
        if (m.zeroAt < m.best) {
            v = -value;
            for (int i = 0; i < limits.length; i++) limits[i] = -limits[i];
        }
        double tierStart = limits[0];
        if (v <= tierStart) return 100.0;
        for (int t = 0; t < 4; t++) {
            double tierEnd = limits[t + 1];
            // a tier with no width, such as green when it is one value, holds no value
            if (tierEnd == tierStart) continue;
            if (v < tierEnd || t == 3) {
                double distance = Math.min((v - tierStart) / (tierEnd - tierStart), 1.0);
                return TIER_START_SCORE[t] - distance * SCORE_DROP_PER_TIER;
            }
            tierStart = tierEnd;
        }
        // every tier from the value upward had no width: beyond the last limit the score is 0
        return 0.0;
    }

    /**
     * The KPI of rows whose values are already rounded: null when no row has a value, 100 when every
     * row with a value was skipped as green, else the rounded weighted mean.
     */
    public static Double kpi(KpiMetric[] metrics, Map<String, Double> values) {
        double totalWeighted = 0.0;
        double totalWeight = 0.0;
        boolean anyData = false;
        for (KpiMetric m : metrics) {
            Double value = values.get(m.name);
            if (value == null) continue;
            anyData = true;
            double s = score(m, value);
            double w = weight(s);
            if (m.skipWhenGreen && isGreen(m, value)) continue;
            totalWeighted += s * w;
            totalWeight += w;
        }
        if (totalWeight == 0) return anyData ? Double.valueOf(100.0) : null;
        return PyRound.round2(totalWeighted / totalWeight);
    }

    /** What {@link #compute} gave: the rounded row values and the KPI as published. */
    public static final class Computed {
        /** The rows that have a value, each rounded to two decimals (all of them, published or not). */
        public final Map<String, Double> rows;
        /** The KPI as published (flipped for a higher-is-worse KPI), or null when no row had a value. */
        public final Double score;

        Computed(Map<String, Double> rows, Double score) {
            this.rows = rows;
            this.score = score;
        }
    }

    /**
     * The original's {@code publish_kpi} without the publishing: round every value, score them, flip
     * for a KPI where higher is worse (100 minus the score, rounded again).
     */
    public static Computed compute(KpiMetric[] metrics, Map<String, Double> raw, boolean higherIsWorse) {
        Map<String, Double> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e.getValue() != null) rows.put(e.getKey(), PyRound.round2(e.getValue()));
        }
        Double score = kpi(metrics, rows);
        if (score != null && higherIsWorse) score = PyRound.round2(100.0 - score);
        return new Computed(rows, score);
    }
}
