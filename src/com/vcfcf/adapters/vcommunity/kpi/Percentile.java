package com.vcfcf.adapters.vcommunity.kpi;

import java.util.Arrays;
import java.util.List;

/**
 * Nearest-rank percentiles, written the way the original computes the rank so that the floating
 * point of the rank is the same (design 7.1). Every method returns null for no values.
 */
public final class Percentile {

    private Percentile() {}

    private static double[] sorted(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a;
    }

    /** {@code sorted[ceil(0.9 n) - 1]}; with fewer than 10 values the maximum. */
    public static Double p90(List<Double> v) {
        if (v.isEmpty()) return null;
        int position = (int) Math.ceil(0.9 * v.size());
        return sorted(v)[position - 1];
    }

    /** {@code sorted[ceil(0.1 n) - 1]}; with 10 or fewer values the minimum. */
    public static Double p10(List<Double> v) {
        if (v.isEmpty()) return null;
        int position = (int) Math.ceil(0.1 * v.size());
        return sorted(v)[position - 1];
    }

    /** {@code sorted[max(ceil(percent / 100 * n), 1) - 1]} (the Periodic Cluster Check). */
    public static Double nearestRank(List<Double> v, double percent) {
        if (v.isEmpty()) return null;
        int position = (int) Math.ceil(percent / 100.0 * v.size());
        return sorted(v)[Math.max(position, 1) - 1];
    }

    /**
     * The value with only the worst {@code worstPercent} % of the samples beyond it: the
     * (100 - worstPercent)th percentile when higher is worse, the same rank from the low end when
     * higher is better. 0 is the single worst sample.
     */
    public static Double worst(List<Double> v, int worstPercent, boolean higherIsWorse) {
        if (v.isEmpty()) return null;
        if (higherIsWorse) return nearestRank(v, 100 - worstPercent);
        double[] neg = new double[v.size()];
        for (int i = 0; i < neg.length; i++) neg[i] = -v.get(i);
        Arrays.sort(neg);
        int position = (int) Math.ceil((100 - worstPercent) / 100.0 * neg.length);
        return -neg[Math.max(position, 1) - 1];
    }
}
