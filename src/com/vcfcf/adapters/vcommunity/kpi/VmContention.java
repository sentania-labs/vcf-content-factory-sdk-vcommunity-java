package com.vcfcf.adapters.vcommunity.kpi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows both Cluster Performance and ESXi Performance are built from (design 7.1): per VM statistic, the
 * worst VM and the 90th-percentile VM, which shows how far a problem goes and how many VMs share it; and a host's
 * share of faulty network packets.
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
     * Faulty (errored or dropped) packets as a percent of all packets the host moved in the latest sample; no
     * value if one of the six counters is absent or the host moved no packets.
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
