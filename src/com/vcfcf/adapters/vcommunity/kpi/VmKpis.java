package com.vcfcf.adapters.vcommunity.kpi;

import com.vcfcf.adapters.vcommunity.text.PyRound;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three per-VM KPIs of a powered-on VM (map D5 to D7), from its latest statistics: VM Performance
 * (nine rows, one of them, Memory Zip Swap, published), Guest OS Performance (four rows, the KPI only) and
 * their mean, VM Guest OS KPI.
 */
public final class VmKpis {

    private VmKpis() {}

    public static final String VM_GROUP = "VM Performance";
    public static final String GUEST_GROUP = "Guest OS Performance";

    /** The nine rows of VM Performance; a row with no value is absent. */
    public static Map<String, Double> vmPerformanceValues(Map<String, Double> s) {
        Map<String, Double> v = new LinkedHashMap<>();
        put(v, KpiTable.V_COSTOP, s.get(StatKeys.VM_COSTOP));
        put(v, KpiTable.V_READY, s.get(StatKeys.VM_READY));
        put(v, KpiTable.V_OVERLAP, s.get(StatKeys.VM_OVERLAP));
        put(v, KpiTable.V_MEMCONT, s.get(StatKeys.VM_MEM_CONTENTION));
        put(v, KpiTable.V_DISK, s.get(StatKeys.VM_DISK_LATENCY));
        put(v, KpiTable.V_DROPPED, s.get(StatKeys.VM_DROPPED_TX));
        put(v, KpiTable.V_CPURUN, s.get(StatKeys.VM_CPU_RUN));
        put(v, KpiTable.V_BALLOON, s.get(StatKeys.VM_BALLOON));
        put(v, KpiTable.V_ZIPSWAP, Stat.hasAll(s, StatKeys.VM_COMPRESSED, StatKeys.VM_SWAPPED, StatKeys.VM_GUEST_USED)
                ? Stat.percent(s.get(StatKeys.VM_COMPRESSED) + s.get(StatKeys.VM_SWAPPED),
                        s.get(StatKeys.VM_GUEST_USED)) : null);
        return v;
    }

    /** The four rows of Guest OS Performance; free memory is converted from KB to the MB of its limits. */
    public static Map<String, Double> guestValues(Map<String, Double> s) {
        Map<String, Double> v = new LinkedHashMap<>();
        put(v, KpiTable.G_CPU_QUEUE, s.get(StatKeys.GUEST_CPU_QUEUE));
        put(v, KpiTable.G_DISK_QUEUE, s.get(StatKeys.GUEST_DISK_QUEUE));
        Double free = s.get(StatKeys.GUEST_FREE_KB);
        put(v, KpiTable.G_FREE_MEMORY, free == null ? null : free / 1024.0);
        put(v, KpiTable.G_PAGE_OUT, s.get(StatKeys.GUEST_PAGE_OUT));
        return v;
    }

    private static void put(Map<String, Double> m, KpiMetric row, Double v) {
        if (v != null) m.put(row.name, v);
    }

    /** The mean of the two KPIs rounded; VM Performance alone without Guest OS Performance; null without VM Performance. */
    public static Double vmGuestOsKpi(Double vmPerformance, Double guestOsPerformance) {
        if (vmPerformance == null) return null;
        if (guestOsPerformance == null) return vmPerformance;
        return PyRound.round2((vmPerformance + guestOsPerformance) / 2);
    }
}
