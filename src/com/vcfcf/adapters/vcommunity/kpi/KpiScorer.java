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

    /** Whether the value lies in the best band: at or beyond the best limit, or short of the green limit (compared negated for a higher-is-better row). */
    public static boolean inGreen(KpiMetric m, double value) {
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
            // two equal limits enclose no values, so the search moves to the next band
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
     * Combines rows whose values are already rounded: no result when no row has a value; 100 when every row that has
     * one is in a band that is set aside (so nothing is wrong); otherwise the weighted mean, rounded.
     */
    public static Double combine(KpiMetric[] metrics, Map<String, Double> values) {
        double totalWeighted = 0.0;
        double totalWeight = 0.0;
        boolean anyData = false;
        for (KpiMetric m : metrics) {
            Double value = values.get(m.name);
            if (value == null) continue;
            anyData = true;
            double s = score(m, value);
            double w = weight(s);
            if (m.skipWhenGreen && inGreen(m, value)) continue;
            totalWeighted += s * w;
            totalWeight += w;
        }
        if (totalWeight == 0) return anyData ? Double.valueOf(100.0) : null;
        return PyRound.round2(totalWeighted / totalWeight);
    }

    /** What {@link #compute} gave: the rounded row values and the KPI as published. */
    public static final class Evaluation {
        /** The rows that have a value, each rounded to two decimals (all of them, published or not). */
        public final Map<String, Double> rows;
        /** The KPI as published (flipped for a higher-is-worse KPI), or null when no row had a value. */
        public final Double score;

        Evaluation(Map<String, Double> rows, Double score) {
            this.rows = rows;
            this.score = score;
        }
    }

    /**
     * Rounds every row value to two places, combines the rows, and for a higher-is-worse KPI reports 100 less the
     * result, rounded again. Nothing is queued here; the caller decides what to send.
     */
    public static Evaluation evaluate(KpiMetric[] metrics, Map<String, Double> raw, boolean higherIsWorse) {
        Map<String, Double> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e.getValue() != null) rows.put(e.getKey(), PyRound.round2(e.getValue()));
        }
        Double score = combine(metrics, rows);
        if (score != null && higherIsWorse) score = PyRound.round2(100.0 - score);
        return new Evaluation(rows, score);
    }
}
