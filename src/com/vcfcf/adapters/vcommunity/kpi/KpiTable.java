package com.vcfcf.adapters.vcommunity.kpi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 48 rows of the six KPIs, with their limits (design Appendix A). The order inside a group is
 * the order the original scores in, which fixes the order of the floating point sum. A test pins
 * every row against the design's table.
 */
public final class KpiTable {

    private KpiTable() {}

    private static KpiMetric m(String name, String unit, double best, double green, double yellow,
            double orange, double zeroAt, boolean skip, boolean published) {
        return new KpiMetric(name, unit, best, green, yellow, orange, zeroAt, skip, published);
    }

    private static KpiMetric m(String name, String unit, double best, double green, double yellow,
            double orange, double zeroAt) {
        return m(name, unit, best, green, yellow, orange, zeroAt, false, true);
    }

    public static final String G_CLUSTER_PERFORMANCE = "Cluster Performance Score";
    public static final String G_CLUSTER_RISK = "Cluster Risk Score";
    public static final String G_CLUSTER_CONSUMPTION = "Cluster Consumption Score";
    public static final String G_ESXI_PERFORMANCE = "ESXi Performance Score";
    public static final String G_VM_PERFORMANCE = "VM Performance Score";
    public static final String G_GUEST_OS_PERFORMANCE = "Guest OS Performance Score";

    // ---- the eight VM contention rows (worst VM, then 90th percentile), shared by cluster and host
    static final KpiMetric W_READY = m("Worst VM CPU Ready", "%", 0, 4, 8, 16, 32);
    static final KpiMetric W_COSTOP = m("Worst VM CPU Co-Stop", "%", 0, 2, 4, 8, 16);
    static final KpiMetric W_MEMLAT = m("Worst VM Memory Latency", "%", 0, 1, 2, 4, 8);
    static final KpiMetric W_DISK = m("Worst VM Disk Latency", "ms", 0, 40, 80, 160, 320);
    static final KpiMetric P_READY = m("90th Percentile VM CPU Ready", "%", 0, 1, 2, 4, 8);
    static final KpiMetric P_COSTOP = m("90th Percentile VM CPU Co-Stop", "%", 0, 0.5, 1, 2, 4);
    static final KpiMetric P_MEMLAT = m("90th Percentile VM Memory Latency", "%", 0, 0.5, 1, 2, 4);
    static final KpiMetric P_DISK = m("90th Percentile VM Disk Latency", "ms", 0, 20, 40, 60, 80);

    public static final KpiMetric[] VM_CONTENTION = {W_READY, W_COSTOP, W_MEMLAT, W_DISK, P_READY,
        P_COSTOP, P_MEMLAT, P_DISK};

    public static final KpiMetric CLUSTER_BAD_PACKETS =
            m("Highest ESXi Bad Network Packets", "%", 0, 0, 0.2, 0.4, 0.8, true, true);
    public static final KpiMetric ESXI_BAD_PACKETS =
            m("ESXi Bad Network Packets", "%", 0, 0, 0.2, 0.4, 0.8, true, true);

    public static final KpiMetric[] CLUSTER_PERFORMANCE = concat(VM_CONTENTION, CLUSTER_BAD_PACKETS);
    public static final KpiMetric[] ESXI_PERFORMANCE = concat(VM_CONTENTION, ESXI_BAD_PACKETS);

    public static final KpiMetric R_THREADS = m("Highest ESXi CPU Thread Utilization", "%", 0, 60, 80, 90, 95, true, true);
    public static final KpiMetric R_IMBALANCE = m("Worst ESXi CPU Imbalance", "none", 1, 1, 1.3, 1.6, 2, false, true);
    public static final KpiMetric R_MEM_CONSUMED = m("Highest ESXi Memory Consumed", "%", 0, 80, 90, 95, 100, true, true);
    public static final KpiMetric R_BALLOONED = m("Worst ESXi Memory Ballooned", "%", 0, 1, 2, 4, 8);
    public static final KpiMetric R_ZIPSWAP = m("Worst ESXi Memory Zip Swap", "%", 0, 0.25, 0.5, 1, 2);
    public static final KpiMetric R_TIERING = m("Worst ESXi Memory Tiering Latency", "µs", 0, 150, 300, 450, 600);
    public static final KpiMetric R_CPU_RESERVATION = m("Highest ESXi CPU Reservation", "%", 0, 10, 40, 60, 80, true, true);
    public static final KpiMetric R_MEM_RESERVATION = m("Highest ESXi Memory Reservation", "%", 0, 10, 40, 60, 80, true, true);
    public static final KpiMetric R_CPU_OVERCOMMIT = m("Highest ESXi CPU Overcommit Ratio", "none", 0, 1, 2, 3, 4, true, true);
    public static final KpiMetric R_MEM_OVERCOMMIT = m("Highest ESXi Memory Overcommit Ratio", "none", 0, 1, 1.4, 1.8, 2.2, true, true);
    public static final KpiMetric R_CPU_MONSTER = m("Highest CPU Monster VM to Host Ratio", "none", 0, 1, 2, 3, 4);
    public static final KpiMetric R_MEM_MONSTER = m("Highest Memory Monster VM to Host Ratio", "none", 0, 1, 2, 3, 4);

    public static final KpiMetric[] CLUSTER_RISK = {R_THREADS, R_IMBALANCE, R_MEM_CONSUMED, R_BALLOONED,
        R_ZIPSWAP, R_TIERING, R_CPU_RESERVATION, R_MEM_RESERVATION, R_CPU_OVERCOMMIT, R_MEM_OVERCOMMIT,
        R_CPU_MONSTER, R_MEM_MONSTER};

    public static final KpiMetric C_CPU = m("Cluster-wide ESXi CPU Thread Utilization", "%", 0, 55, 70, 85, 100);
    public static final KpiMetric C_MEMORY = m("Cluster-wide ESXi Memory Consumed", "%", 0, 70, 80, 90, 100);
    public static final KpiMetric C_TIERING = m("Cluster Memory Tiering Consumption", "%", 0, 4, 8, 16, 32);
    public static final KpiMetric C_BALLOONED = m("Cluster-wide ESXi Memory Ballooned", "%", 0, 1, 2, 4, 8);
    public static final KpiMetric C_ZIPSWAP = m("Cluster-wide ESXi Memory Zip Swap", "%", 0, 0.25, 0.5, 1, 2);

    public static final KpiMetric[] CLUSTER_CONSUMPTION = {C_CPU, C_MEMORY, C_TIERING, C_BALLOONED, C_ZIPSWAP};

    public static final KpiMetric V_COSTOP = m("CPU Co-Stop", "%", 0, 1, 2, 4, 8, false, false);
    public static final KpiMetric V_READY = m("CPU Ready", "%", 0, 2, 4, 8, 16, false, false);
    public static final KpiMetric V_OVERLAP = m("CPU Overlap", "%", 0, 0.5, 1, 2, 4, true, false);
    public static final KpiMetric V_MEMCONT = m("Memory Contention", "%", 0, 0.5, 1, 2, 4, false, false);
    public static final KpiMetric V_DISK = m("Disk Latency", "ms", 0, 10, 20, 40, 80, false, false);
    public static final KpiMetric V_DROPPED = m("Network Dropped Tx", "%", 0, 0, 0.5, 1, 2, true, false);
    public static final KpiMetric V_CPURUN = m("CPU Run", "%", 0, 95, 97, 99, 100, true, false);
    public static final KpiMetric V_BALLOON = m("Memory Ballooned", "%", 0, 2, 4, 8, 16, false, false);
    public static final KpiMetric V_ZIPSWAP = m("Memory Zip Swap", "%", 0, 0.25, 0.5, 1, 2, false, true);

    public static final KpiMetric[] VM_PERFORMANCE = {V_COSTOP, V_READY, V_OVERLAP, V_MEMCONT, V_DISK,
        V_DROPPED, V_CPURUN, V_BALLOON, V_ZIPSWAP};

    public static final KpiMetric G_CPU_QUEUE = m("Guest CPU Run Queue per vCPU", null, 0, 2, 4, 8, 16, false, false);
    public static final KpiMetric G_DISK_QUEUE = m("Guest Disk Queue", null, 0, 20, 40, 80, 160, false, false);
    public static final KpiMetric G_FREE_MEMORY = m("Guest Free Memory", "MB", 1024, 512, 256, 128, 0, false, false);
    public static final KpiMetric G_PAGE_OUT = m("Guest Page-Out Rate", null, 0, 1000, 2000, 4000, 8000, false, false);

    public static final KpiMetric[] GUEST_OS_PERFORMANCE = {G_CPU_QUEUE, G_DISK_QUEUE, G_FREE_MEMORY, G_PAGE_OUT};

    private static KpiMetric[] concat(KpiMetric[] a, KpiMetric b) {
        KpiMetric[] r = new KpiMetric[a.length + 1];
        System.arraycopy(a, 0, r, 0, a.length);
        r[a.length] = b;
        return r;
    }

    /** Every row by KPI name, in the order of the design's Appendix A. */
    public static Map<String, KpiMetric[]> groups() {
        Map<String, KpiMetric[]> g = new LinkedHashMap<>();
        g.put(G_CLUSTER_PERFORMANCE, CLUSTER_PERFORMANCE);
        g.put(G_CLUSTER_RISK, CLUSTER_RISK);
        g.put(G_CLUSTER_CONSUMPTION, CLUSTER_CONSUMPTION);
        g.put(G_ESXI_PERFORMANCE, ESXI_PERFORMANCE);
        g.put(G_VM_PERFORMANCE, VM_PERFORMANCE);
        g.put(G_GUEST_OS_PERFORMANCE, GUEST_OS_PERFORMANCE);
        return Collections.unmodifiableMap(g);
    }

    /** The 48 rows flat, in the same order. */
    public static List<KpiMetric> all() {
        List<KpiMetric> l = new ArrayList<>();
        for (KpiMetric[] g : groups().values()) Collections.addAll(l, g);
        return l;
    }
}
