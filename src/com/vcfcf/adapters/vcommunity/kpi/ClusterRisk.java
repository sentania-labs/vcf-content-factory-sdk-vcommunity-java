package com.vcfcf.adapters.vcommunity.kpi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cluster Risk (map D2): twelve rows, each computed for every host of the cluster and then the highest
 * host taken. Higher is worse; the KPI is published as 100 minus its score. Written to give the
 * original's value for every input, including its use of "no value" for a zero (a host whose DRAM
 * capacity statistic is 0 falls back to the provisioned memory), except on a host with memory tiering:
 * every row that divides by the host's memory uses DRAM plus the NVMe tier there (DV-109).
 */
public final class ClusterRisk {

    private ClusterRisk() {}

    public static final String GROUP = "Cluster Risk";
    /** Nanoseconds in a microsecond: the tier latency statistic is read in ns and scored in µs. */
    static final double NS_PER_US = 1000.0;
    public static final String KPI_KEY = "Cluster Risk Score";
    public static final boolean HIGHER_IS_WORSE = true;

    /** The host's DRAM in KB: the DRAM capacity statistic, else the provisioned memory; a 0 is no value. */
    public static Double dramKb(Map<String, Double> s) {
        Double a = s.get(StatKeys.MEM_DRAM_CAPACITY);
        if (Stat.truthy(a)) return a;
        Double b = s.get(StatKeys.MEM_DRAM_FALLBACK);
        return Stat.truthy(b) ? b : null;
    }

    /**
     * The host's memory capacity, which every memory row divides by. On a host with memory tiering it is the DRAM
     * tier plus the NVMe tier: the host's memory is both, the consumed figure counts both, and the same host reads
     * that total through a version that has no DRAM tier statistic (it falls back to the provisioned memory). On any
     * other host it is the DRAM, as always. The tier is added only to the DRAM tier statistic: when the DRAM fell
     * back to the provisioned memory, that figure already holds both tiers and nothing is added.
     */
    public static Double memoryCapacityKb(Map<String, Double> s) {
        Double dram = dramKb(s);
        if (dram == null) return null;
        Double tier = s.get(StatKeys.NVME_TIER_SIZE);
        if (Stat.truthy(s.get(StatKeys.MEM_DRAM_CAPACITY)) && Stat.truthy(tier) && tier > 0) return dram + tier;
        return dram;
    }

    private static Double percentOfMemory(Map<String, Double> s, double memoryKb) {
        Double capacity = memoryCapacityKb(s);
        return capacity == null ? null : Stat.percent(memoryKb, capacity);
    }

    /** One host's value for each row; a row it has no value for is missing from the map. */
    public static Map<String, Double> hostValues(HostInv host, Map<String, Double> s, MonsterVm monster) {
        Map<String, Double> v = new LinkedHashMap<>();
        put(v, KpiTable.R_THREADS, s.get(StatKeys.CPU_THREAD_UTIL));
        put(v, KpiTable.R_IMBALANCE, imbalance(host, s));
        Double consumed = s.get(StatKeys.MEM_CONSUMED);
        put(v, KpiTable.R_MEM_CONSUMED, consumed == null ? null : percentOfMemory(s, consumed));
        Double ballooned = s.get(StatKeys.MEM_BALLOONED);
        put(v, KpiTable.R_BALLOONED, ballooned == null ? null : percentOfMemory(s, ballooned));
        put(v, KpiTable.R_ZIPSWAP, Stat.hasAll(s, StatKeys.MEM_COMPRESSED, StatKeys.MEM_SWAPPED)
                ? percentOfMemory(s, s.get(StatKeys.MEM_COMPRESSED) + s.get(StatKeys.MEM_SWAPPED)) : null);
        // the statistic is in nanoseconds (Operations declares the unit; vCenter counter mem.latency.read); the row
        // is in microseconds, which is what its limits and its label say (DV-113). A 0 is a reading.
        Double latencyNs = s.get(StatKeys.MEM_TIERING_LATENCY);
        put(v, KpiTable.R_TIERING, latencyNs == null ? null : latencyNs / NS_PER_US);
        put(v, KpiTable.R_CPU_RESERVATION, Stat.hasAll(s, StatKeys.CPU_RESERVED, StatKeys.CPU_CAPACITY)
                ? Stat.percent(s.get(StatKeys.CPU_RESERVED), s.get(StatKeys.CPU_CAPACITY)) : null);
        Double reserved = s.get(StatKeys.MEM_RESERVED);
        put(v, KpiTable.R_MEM_RESERVATION, reserved == null ? null : percentOfMemory(s, reserved));
        Double vcpus = s.get(StatKeys.VCPUS_ALLOCATED);
        put(v, KpiTable.R_CPU_OVERCOMMIT, !Stat.truthy(host.cores) || vcpus == null ? null : vcpus / host.cores);
        Double capacity = memoryCapacityKb(s);
        Double allocated = s.get(StatKeys.MEM_ALLOCATED);
        put(v, KpiTable.R_MEM_OVERCOMMIT, allocated == null || capacity == null ? null : allocated / capacity);
        put(v, KpiTable.R_CPU_MONSTER, cpuMonster(host, monster));
        put(v, KpiTable.R_MEM_MONSTER, memoryMonster(host, s, monster));
        return v;
    }

    private static void put(Map<String, Double> m, KpiMetric row, Double v) {
        if (v != null) m.put(row.name, v);
    }

    /** Running CPU threads over running CPU cores. No value for an idle host (no core running). */
    private static Double imbalance(HostInv host, Map<String, Double> s) {
        if (!Stat.truthy(host.cores) || !Stat.truthy(host.threads)
                || !Stat.hasAll(s, StatKeys.CPU_THREAD_UTIL, StatKeys.CPU_CORE_UTIL)) {
            return null;
        }
        double runningThreads = s.get(StatKeys.CPU_THREAD_UTIL) / 100.0 * host.threads;
        double runningCores = s.get(StatKeys.CPU_CORE_UTIL) / 100.0 * host.cores;
        if (runningCores <= 0) return null;
        return runningThreads / runningCores;
    }

    private static Double cpuMonster(HostInv host, MonsterVm monster) {
        Double max = null;
        for (HostInv.Vm vm : host.vms) {
            if (Stat.truthy(vm.vcpus) && (max == null || vm.vcpus > max)) max = vm.vcpus;
        }
        if (!Stat.truthy(host.cores) || max == null) return null;
        return max / (monster.ratioOfCores * host.cores);
    }

    private static Double memoryMonster(HostInv host, Map<String, Double> s, MonsterVm monster) {
        Double max = null;
        for (HostInv.Vm vm : host.vms) {
            if (Stat.truthy(vm.memoryMb) && (max == null || vm.memoryMb > max)) max = vm.memoryMb;
        }
        Double capacity = memoryCapacityKb(s);
        if (capacity == null || max == null) return null;
        double monsterMb = Math.min(monster.ratioOfDram * capacity / 1024.0, monster.memoryMinMb);
        if (monsterMb == 0) return null; // the original would raise here; no row is left
        return max / monsterMb;
    }

    /** The cluster's value for each row: the highest value among its hosts. */
    public static Map<String, Double> clusterValues(List<HostInv> hosts,
            Function<HostInv, Map<String, Double>> statsOf, MonsterVm monster) {
        Map<String, Double> out = new LinkedHashMap<>();
        List<Map<String, Double>> perHost = new java.util.ArrayList<>();
        for (HostInv h : hosts) perHost.add(hostValues(h, statsOf.apply(h), monster));
        for (KpiMetric m : KpiTable.CLUSTER_RISK) {
            Double max = null;
            for (Map<String, Double> hv : perHost) {
                Double d = hv.get(m.name);
                if (d != null && (max == null || d > max)) max = d;
            }
            if (max != null) out.put(m.name, max);
        }
        return out;
    }
}
