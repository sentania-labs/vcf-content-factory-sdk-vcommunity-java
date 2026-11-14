package com.vcfcf.adapters.vcommunity.kpi;

import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.util.ArrayList;
import java.util.List;

/**
 * The Business Application figures (map D12, design 4.12): the Tier is the mean of the VM Guest OS KPI of its
 * VMs, the Business Application the higher of its namespaces' performance values and the lowest of its tiers
 * (Q14), the Enterprise the mean of its Business Applications. Each is null when it has nothing to average.
 */
public final class BusinessApps {

    private BusinessApps() {}

    public static final String GROUP = "Business Application";
    public static final String VM_KPI_STAT = StatKeys.VM_GUEST_KPI;
    public static final String NAMESPACE_STAT = "Performance|Performance";

    /** The mean of the VM KPIs of a Tier's VMs, rounded; null when no VM has a value. */
    public static Double tier(List<Double> vmKpis) {
        if (vmKpis.isEmpty()) return null;
        return PyRound.round2(sum(vmKpis) / vmKpis.size());
    }

    /**
     * The higher of the namespace values and the lowest tier value (a part with no value is left out); null
     * when neither part has one. With {@link Quirks#APPLICATION_TAKES_HIGHER} off, the lowest of all of them.
     */
    public static Double application(List<Double> namespaceValues, List<Double> tierValues) {
        return application(namespaceValues, tierValues, Quirks.APPLICATION_TAKES_HIGHER);
    }

    /** {@link #application(List, List)} with the switch given, so a test can show both settings. */
    public static Double application(List<Double> namespaceValues, List<Double> tierValues, boolean takesHigher) {
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

    /** The mean of the Business Applications that have a value; null when none has one. */
    public static Double enterprise(List<Double> applicationValues) {
        if (applicationValues.isEmpty()) return null;
        return PyRound.round2(sum(applicationValues) / applicationValues.size());
    }

    private static double sum(List<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return s;
    }
}
