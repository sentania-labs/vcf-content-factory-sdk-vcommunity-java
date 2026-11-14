package com.vcfcf.adapters.vcommunity.kpi;

import java.util.List;
import java.util.Map;

/** Cluster Performance (map D1): the eight VM contention rows over the cluster's powered-on VMs, and the worst host's bad packets. */
public final class ClusterPerformance {

    private ClusterPerformance() {}

    public static final String GROUP = "Cluster Performance";
    public static final String KPI_KEY = "Cluster Performance Score";

    /**
     * @param vms          the contention statistics of each powered-on VM of the cluster
     * @param hostPackets  the bad packet percentage of each host of the cluster that has one
     */
    public static Map<String, Double> values(List<double[]> vms, List<Double> hostPackets) {
        Map<String, Double> v = VmContention.rows(vms);
        Double max = null;
        for (Double d : hostPackets) if (d != null && (max == null || d > max)) max = d;
        if (max != null) v.put(KpiTable.CLUSTER_BAD_PACKETS.name, max);
        return v;
    }
}
