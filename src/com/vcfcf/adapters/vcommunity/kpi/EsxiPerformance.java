package com.vcfcf.adapters.vcommunity.kpi;

import java.util.List;
import java.util.Map;

/** ESXi Performance (map D4): Cluster Performance for one host. */
public final class EsxiPerformance {

    private EsxiPerformance() {}

    public static final String GROUP = "ESXi Performance";
    public static final String KPI_KEY = "ESXi Performance Score";

    public static Map<String, Double> values(List<double[]> vms, Map<String, Double> hostPackets) {
        Map<String, Double> v = VmContention.rows(vms);
        Double bad = VmContention.badPacketPct(hostPackets);
        if (bad != null) v.put(KpiTable.ESXI_BAD_PACKETS.name, bad);
        return v;
    }
}
