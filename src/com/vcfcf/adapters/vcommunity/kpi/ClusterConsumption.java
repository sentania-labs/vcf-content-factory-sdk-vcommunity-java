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
    /** The KPI of a cluster whose hosts run but whose usable capacity is none: fully consumed. */
    public static final double FULL = 100.0;

    private static String state(String v) {
        return v == null || v.isEmpty() ? null : v.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** No value, or not connected, powered off, in standby or fully in maintenance mode: the host runs no VM. */
    public static boolean hostIsGone(HostInv host, Double current) {
        if (current == null) return true;
        String connection = state(host.connectionState);
        if (connection != null && !connection.equals("connected")) return true;
        String power = state(host.powerState);
        if ("poweredoff".equals(power) || "standby".equals(power)) return true;
        return "inmaintenance".equals(state(host.maintenanceState));
    }

    /** True when the host can run VMs and reports its CPU utilization. */
    public static boolean hostIsRunning(HostInv host, Map<String, Double> stats) {
        return !hostIsGone(host, stats.get(StatKeys.CPU_THREAD_UTIL));
    }

    /** Usable capacity as a percentage of total capacity; null when either is missing. 0 is an answer: the cluster is full. */
    public static Double usablePercent(Map<String, Double> cluster, String usableKey, String totalKey) {
        Double usable = cluster.get(usableKey);
        Double total = cluster.get(totalKey);
        if (usable == null || !Stat.truthy(total) || total <= 0) return null;
        return Math.max(usable, 0.0) / total * 100.0;
    }

    /** True when the cluster has CPU or memory and none of it is usable by VMs. */
    public static boolean hasNoUsableCapacity(Map<String, Double> cluster) {
        Double cpu = usablePercent(cluster, StatKeys.CPU_USABLE, StatKeys.CPU_TOTAL);
        Double mem = usablePercent(cluster, StatKeys.MEM_USABLE, StatKeys.MEM_TOTAL);
        return (cpu != null && cpu == 0.0) || (mem != null && mem == 0.0);
    }

    private static final class Usable {
        Double cpuThreads;
        List<HostInv> cpuHosts;
        Double dramKb;
        List<HostInv> dramHosts;
        Double nvmeKb;
        List<HostInv> nvmeHosts;
    }

    private static Double apply(Double usablePct, double capacity, List<HostInv> counted) {
        if (usablePct == null || counted.isEmpty()) return null;
        return capacity * usablePct / 100.0;
    }

    private static Usable usable(List<HostInv> hosts, Function<HostInv, Map<String, Double>> statsOf,
            Map<String, Double> cluster) {
        Double cpuPct = usablePercent(cluster, StatKeys.CPU_USABLE, StatKeys.CPU_TOTAL);
        Double memPct = usablePercent(cluster, StatKeys.MEM_USABLE, StatKeys.MEM_TOTAL);
        Usable u = new Usable();
        u.cpuHosts = new java.util.ArrayList<>();
        for (HostInv h : hosts) if (Stat.truthy(h.threads) && hostIsRunning(h, statsOf.apply(h))) u.cpuHosts.add(h);
        double threads = 0;
        for (HostInv h : u.cpuHosts) threads += h.threads;
        u.dramHosts = new java.util.ArrayList<>();
        for (HostInv h : hosts) {
            Map<String, Double> s = statsOf.apply(h);
            if (ClusterRisk.dramKb(s) != null && !hostIsGone(h, s.get(StatKeys.MEM_CONSUMED))) u.dramHosts.add(h);
        }
        double dram = 0;
        for (HostInv h : u.dramHosts) dram += ClusterRisk.dramKb(statsOf.apply(h));
        u.nvmeHosts = new java.util.ArrayList<>();
        for (HostInv h : hosts) {
            Map<String, Double> s = statsOf.apply(h);
            if (Stat.truthy(s.get(StatKeys.NVME_TIER_SIZE)) && !hostIsGone(h, s.get(StatKeys.NVME_TIER_USAGE))) {
                u.nvmeHosts.add(h);
            }
        }
        double nvme = 0;
        for (HostInv h : u.nvmeHosts) nvme += statsOf.apply(h).get(StatKeys.NVME_TIER_SIZE);
        u.cpuThreads = apply(cpuPct, threads, u.cpuHosts);
        u.dramKb = apply(memPct, dram, u.dramHosts);
        u.nvmeKb = apply(memPct, nvme, u.nvmeHosts);
        return u;
    }

    /** The sum of these statistics over the hosts; null when any host lacks one, never a partial sum. */
    private static Double addUp(List<HostInv> hosts, Function<HostInv, Map<String, Double>> statsOf,
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

    private static Double consumed(Double used, Double usable) {
        if (used == null || !Stat.truthy(usable)) return null;
        return Stat.percent(used, usable);
    }

    /** The value of every row for one cluster; a row with no value is absent. */
    public static Map<String, Double> values(List<HostInv> hosts, Function<HostInv, Map<String, Double>> statsOf,
            Map<String, Double> cluster) {
        Usable u = usable(hosts, statsOf, cluster);
        Map<String, Double> v = new LinkedHashMap<>();
        double inUse = 0;
        for (HostInv h : u.cpuHosts) inUse += statsOf.apply(h).get(StatKeys.CPU_THREAD_UTIL) / 100.0 * h.threads;
        put(v, KpiTable.C_CPU, consumed(inUse, u.cpuThreads));
        put(v, KpiTable.C_MEMORY, consumed(addUp(u.dramHosts, statsOf, StatKeys.MEM_CONSUMED), u.dramKb));
        Double usedMb = addUp(u.nvmeHosts, statsOf, StatKeys.NVME_TIER_USAGE);
        put(v, KpiTable.C_TIERING, consumed(usedMb == null ? null : usedMb * 1024.0, u.nvmeKb));
        put(v, KpiTable.C_BALLOONED, consumed(addUp(u.dramHosts, statsOf, StatKeys.MEM_BALLOONED), u.dramKb));
        put(v, KpiTable.C_ZIPSWAP, consumed(addUp(u.dramHosts, statsOf, StatKeys.MEM_COMPRESSED,
                StatKeys.MEM_SWAPPED), u.dramKb));
        return v;
    }

    private static void put(Map<String, Double> m, KpiMetric row, Double v) {
        if (v != null) m.put(row.name, v);
    }
}
