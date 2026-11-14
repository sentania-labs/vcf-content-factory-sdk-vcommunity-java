package com.vcfcf.adapters.vcommunity.kpi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The eight VM contention rows shared by Cluster Performance and ESXi Performance, and the bad
 * network packets of a host (map D1, D4): for each of four VM statistics, how deep (the worst VM)
 * and how broad (the 90th percentile).
 */
public final class VmContention {

    private VmContention() {}

    /** A VM with no value of a statistic is NaN in its array; this is internal and never leaves this class. */
    public static final double MISSING = Double.NaN;

    /**
     * The values of the eight rows over these VMs; a row with no VM that has the statistic is absent.
     *
     * @param vms one array per VM, the four statistics of {@link StatKeys#VM_CONTENTION} (NaN: none)
     */
    public static Map<String, Double> rows(List<double[]> vms) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (int s = 0; s < 4; s++) {
            List<Double> per = new ArrayList<>();
            for (double[] vm : vms) if (!Double.isNaN(vm[s])) per.add(vm[s]);
            KpiMetric worst = KpiTable.VM_CONTENTION[s];
            KpiMetric broad = KpiTable.VM_CONTENTION[s + 4];
            if (!per.isEmpty()) {
                double max = per.get(0);
                for (double d : per) if (d > max) max = d;
                values.put(worst.name, max);
                values.put(broad.name, Percentile.p90(per));
            }
        }
        return values;
    }

    /**
     * Errored and dropped packets of one host as a percentage of all its packets in the last cycle; null when
     * any of the six counters is missing or the host sent and received nothing.
     */
    public static Double badPacketPct(Map<String, Double> packets) {
        for (String k : StatKeys.BAD_PACKETS) if (packets.get(k) == null) return null;
        for (String k : StatKeys.ALL_PACKETS) if (packets.get(k) == null) return null;
        double bad = 0;
        for (String k : StatKeys.BAD_PACKETS) bad += packets.get(k);
        double all = 0;
        for (String k : StatKeys.ALL_PACKETS) all += packets.get(k);
        return Stat.percent(bad, all);
    }
}
