package com.vcfcf.adapters.vcommunity.kpi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cluster Consumption (map D3): the share of the cluster's usable capacity that is consumed, in five rows.
 * The cluster's usable capacity, as a percentage of its total, is applied to the threads, DRAM and
 * memory tier of the hosts that are running. Higher is worse; the KPI is published as 100 minus its score.
 * Written to give the original's value for every input.
 */
public final class ClusterConsumption {

    private ClusterConsumption() {}

    public static final String GROUP = "Cluster Consumption";
    public static final String KPI_KEY = "Cluster Consumption Score";
    public static final boolean HIGHER_IS_WORSE = true;
    /** What the score reads for a cluster that has running hosts but nothing it may hand to VMs. */
    public static final double FULL = 100.0;

    private static String state(String v) {
        return v == null || v.isEmpty() ? null : v.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** A host that takes no VMs: no current sample, not connected, off or in standby, or fully in maintenance. */
    public static boolean cannotHostVms(HostInv host, Double current) {
        if (current == null) return true;
        String connection = state(host.connectionState);
        if (connection != null && !connection.equals("connected")) return true;
        String power = state(host.powerState);
        if ("poweredoff".equals(power) || "standby".equals(power)) return true;
        return "inmaintenance".equals(state(host.maintenanceState));
    }

    /** Whether the host takes VMs and has a current CPU sample. */
    public static boolean servesVms(HostInv host, Map<String, Double> stats) {
        return !cannotHostVms(host, stats.get(StatKeys.CPU_THREAD_UTIL));
    }

    /** The share of the total capacity that VMs may use, in percent; null when a figure is missing or the total is not positive. A result of 0 means the reserve takes it all. */
    public static Double usableShare(Map<String, Double> cluster, String usableKey, String totalKey) {
        Double usable = cluster.get(usableKey);
        Double total = cluster.get(totalKey);
        if (usable == null || !Stat.truthy(total) || total <= 0) return null;
        return Math.max(usable, 0.0) / total * 100.0;
    }

    /** True when CPU or memory is present in the cluster and none of it may be used by VMs. */
    public static boolean usableIsZero(Map<String, Double> cluster) {
        Double cpu = usableShare(cluster, StatKeys.CPU_USABLE, StatKeys.CPU_TOTAL);
        Double mem = usableShare(cluster, StatKeys.MEM_USABLE, StatKeys.MEM_TOTAL);
        return (cpu != null && cpu == 0.0) || (mem != null && mem == 0.0);
    }

    private static final class Capacity {
        Double cpuThreads;
        List<HostInv> cpuHosts;
        Double dramKb;
        /** The capacity consumed memory is drawn from: DRAM, plus the NVMe tier on a host that has one. */
        Double consumedKb;
        List<HostInv> dramHosts;
        Double nvmeKb;
        List<HostInv> nvmeHosts;
    }

    private static Double scaled(Double usablePct, double capacity, List<HostInv> counted) {
        if (usablePct == null || counted.isEmpty()) return null;
        return capacity * usablePct / 100.0;
    }

    private static Capacity capacityOf(List<HostInv> hosts, Function<HostInv, Map<String, Double>> statsOf,
            Map<String, Double> cluster) {
        Double cpuPct = usableShare(cluster, StatKeys.CPU_USABLE, StatKeys.CPU_TOTAL);
        Double memPct = usableShare(cluster, StatKeys.MEM_USABLE, StatKeys.MEM_TOTAL);
        Capacity u = new Capacity();
        u.cpuHosts = new java.util.ArrayList<>();
        for (HostInv h : hosts) if (Stat.truthy(h.threads) && servesVms(h, statsOf.apply(h))) u.cpuHosts.add(h);
        double threads = 0;
        for (HostInv h : u.cpuHosts) threads += h.threads;
        u.dramHosts = new java.util.ArrayList<>();
        for (HostInv h : hosts) {
            Map<String, Double> s = statsOf.apply(h);
            if (ClusterRisk.dramKb(s) != null && !cannotHostVms(h, s.get(StatKeys.MEM_CONSUMED))) u.dramHosts.add(h);
        }
        double dram = 0;
        double consumedCap = 0;
        for (HostInv h : u.dramHosts) {
            dram += ClusterRisk.dramKb(statsOf.apply(h));
            consumedCap += ClusterRisk.consumedCapacityKb(statsOf.apply(h));
        }
        u.nvmeHosts = new java.util.ArrayList<>();
        for (HostInv h : hosts) {
            Map<String, Double> s = statsOf.apply(h);
            if (Stat.truthy(s.get(StatKeys.NVME_TIER_SIZE)) && !cannotHostVms(h, s.get(StatKeys.NVME_TIER_USAGE))) {
                u.nvmeHosts.add(h);
            }
        }
        double nvme = 0;
        for (HostInv h : u.nvmeHosts) nvme += statsOf.apply(h).get(StatKeys.NVME_TIER_SIZE);
        u.cpuThreads = scaled(cpuPct, threads, u.cpuHosts);
        u.dramKb = scaled(memPct, dram, u.dramHosts);
        u.consumedKb = scaled(memPct, consumedCap, u.dramHosts);
        u.nvmeKb = scaled(memPct, nvme, u.nvmeHosts);
        return u;
    }

    /** Adds the named statistics over the hosts; null when any host lacks one, since a total over only some hosts would misstate the share. */
    private static Double total(List<HostInv> hosts, Function<HostInv, Map<String, Double>> statsOf,
            String... keys) {
        double total = 0.0;
        for (HostInv h : hosts) {
            double sum = 0;
            for (String k : keys) {
                Double v = statsOf.apply(h).get(k);
                if (v == null) return null;
                sum += v;
            }
            total += sum;
        }
        return total;
    }

    private static Double shareOfUsable(Double used, Double usable) {
        if (used == null || !Stat.truthy(usable)) return null;
        return Stat.percent(used, usable);
    }

    /** The cluster's value for each row; a row it cannot compute is missing from the map. */
    public static Map<String, Double> values(List<HostInv> hosts, Function<HostInv, Map<String, Double>> statsOf,
            Map<String, Double> cluster) {
        Capacity u = capacityOf(hosts, statsOf, cluster);
        Map<String, Double> v = new LinkedHashMap<>();
        double inUse = 0;
        for (HostInv h : u.cpuHosts) inUse += statsOf.apply(h).get(StatKeys.CPU_THREAD_UTIL) / 100.0 * h.threads;
        put(v, KpiTable.C_CPU, shareOfUsable(inUse, u.cpuThreads));
        put(v, KpiTable.C_MEMORY, shareOfUsable(total(u.dramHosts, statsOf, StatKeys.MEM_CONSUMED), u.consumedKb));
        Double usedMb = total(u.nvmeHosts, statsOf, StatKeys.NVME_TIER_USAGE);
        put(v, KpiTable.C_TIERING, shareOfUsable(usedMb == null ? null : usedMb * 1024.0, u.nvmeKb));
        put(v, KpiTable.C_BALLOONED, shareOfUsable(total(u.dramHosts, statsOf, StatKeys.MEM_BALLOONED), u.dramKb));
        put(v, KpiTable.C_ZIPSWAP, shareOfUsable(total(u.dramHosts, statsOf, StatKeys.MEM_COMPRESSED,
                StatKeys.MEM_SWAPPED), u.dramKb));
        return v;
    }

    private static void put(Map<String, Double> m, KpiMetric row, Double v) {
        if (v != null) m.put(row.name, v);
    }
}
