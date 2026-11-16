package com.vcfcf.adapters.vcommunity.kpi;

import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.util.ArrayList;
import java.util.List;

/**
 * The three Business Application figures (design 4.12). A Tier averages the VM Guest OS KPI of the VMs under it.
 * A Business Application takes the larger of the Kubernetes namespace values and the weakest Tier (Q14). An
 * Enterprise averages its Business Applications. Each gives no value when there is nothing to work from.
 */
public final class BusinessApps {

    private BusinessApps() {}

    public static final String GROUP = "Business Application";
    public static final String VM_KPI_STAT = StatKeys.VM_GUEST_KPI;
    public static final String NAMESPACE_STAT = "Performance|Performance";

    /** A Tier's figure: the average of its VMs' scores, rounded to two places; none without a score. */
    public static Double tierValue(List<Double> vmKpis) {
        if (vmKpis.isEmpty()) return null;
        return PyRound.round2(sum(vmKpis) / vmKpis.size());
    }

    /**
     * A Business Application's figure: the larger of the namespace values and the smallest Tier value, where a
     * part that has no values simply does not take part; none when both parts are empty. With
     * {@link Quirks#APPLICATION_TAKES_HIGHER} off, the smallest of every value instead.
     */
    public static Double applicationValue(List<Double> namespaceValues, List<Double> tierValues) {
        return applicationValue(namespaceValues, tierValues, Quirks.APPLICATION_TAKES_HIGHER);
    }

    /** {@link #application(List, List)} with the switch given, so a test can show both settings. */
    public static Double applicationValue(List<Double> namespaceValues, List<Double> tierValues, boolean takesHigher) {
        List<Double> candidates = new ArrayList<>(namespaceValues);
        Double lowestTier = null;
        for (Double t : tierValues) if (lowestTier == null || t < lowestTier) lowestTier = t;
        if (!takesHigher) {
            candidates.addAll(tierValues);
            if (candidates.isEmpty()) return null;
            double min = candidates.get(0);
            for (double d : candidates) if (d < min) min = d;
            return PyRound.round2(min);
        }
        if (lowestTier != null) candidates.add(lowestTier);
        if (candidates.isEmpty()) return null;
        double max = candidates.get(0);
        for (double d : candidates) if (d > max) max = d;
        return PyRound.round2(max);
    }

    /** An Enterprise's figure: the average over those of its applications that have one; none if none does. */
    public static Double enterpriseValue(List<Double> applicationValues) {
        if (applicationValues.isEmpty()) return null;
        return PyRound.round2(sum(applicationValues) / applicationValues.size());
    }

    private static double sum(List<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return s;
    }
}
