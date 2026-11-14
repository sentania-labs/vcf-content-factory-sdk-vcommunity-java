package com.vcfcf.adapters.vcommunity.kpi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The statistic and property keys of the VMware adapter's own objects that the KPIs read back from
 * VCF Operations (design 7.1, map D1-D8, D12). One place, so a read asks for exactly what the formulas use.
 */
public final class StatKeys {

    private StatKeys() {}

    // ---- virtual machines
    public static final String VM_READY = "cpu|20_sec_peak_readyPct";
    public static final String VM_COSTOP = "cpu|20_sec_peak_costopPct";
    public static final String VM_MEM_CONTENTION = "mem|20_sec_peak_host_contentionPct";
    public static final String VM_DISK_LATENCY = "virtualDisk|20_sec_peak_totalLatency_average";
    public static final String VM_OVERLAP = "cpu|20_sec_peak_overlap";
    public static final String VM_DROPPED_TX = "net|droppedTxPct_max";
    public static final String VM_CPU_RUN = "cpu|net_run";
    public static final String VM_BALLOON = "mem|balloonPct";
    public static final String VM_COMPRESSED = "mem|compressed_average";
    public static final String VM_SWAPPED = "mem|swapped_average";
    public static final String VM_GUEST_USED = "guest|used_memory";
    public static final String GUEST_CPU_QUEUE = "guest|20_sec_peak_cpu_queue";
    public static final String GUEST_DISK_QUEUE = "guest|20_sec_peak_disk_queue";
    public static final String GUEST_FREE_KB = "guest|mem.free_latest";
    public static final String GUEST_PAGE_OUT = "guest|page.outRate_latest";

    /** The four contention statistics of the VMs behind the cluster and ESXi rows, in row order. */
    public static final String[] VM_CONTENTION = {VM_READY, VM_COSTOP, VM_MEM_CONTENTION, VM_DISK_LATENCY};

    /** Everything the per-VM KPIs read, the four contention statistics first. */
    public static final List<String> VM_KPI_STATS = unique(VM_READY, VM_COSTOP, VM_MEM_CONTENTION,
            VM_DISK_LATENCY, VM_OVERLAP, VM_DROPPED_TX, VM_CPU_RUN, VM_BALLOON, VM_COMPRESSED, VM_SWAPPED,
            VM_GUEST_USED, GUEST_CPU_QUEUE, GUEST_DISK_QUEUE, GUEST_FREE_KB, GUEST_PAGE_OUT);

    public static final String VM_POWER = "summary|runtime|powerState";
    public static final String VM_VCPUS = "config|hardware|numCpu";
    public static final String VM_MEMORY_KB = "config|hardware|memoryKB";
    public static final String VM_CPU_SHARES = "config|cpuAllocation|shares|shares";
    public static final String VM_MEM_SHARES = "config|memoryAllocation|shares|shares";
    public static final List<String> VM_PROPS = unique(VM_POWER, VM_VCPUS, VM_MEMORY_KB, VM_CPU_SHARES, VM_MEM_SHARES);
    public static final List<String> SHARE_KEYS = unique(VM_CPU_SHARES, VM_MEM_SHARES);
    public static final String POWERED_ON = "Powered On";

    // ---- hosts
    public static final String PKT_ERRORS_RX = "net|errorsRx_summation_sum";
    public static final String PKT_ERRORS_TX = "net|errorsTx_summation_sum";
    public static final String PKT_DROPPED_RX = "net|droppedRx_summation_sum";
    public static final String PKT_DROPPED_TX = "net|droppedTx_summation_sum";
    public static final String PKT_RX = "net|packetsRx_summation_sum";
    public static final String PKT_TX = "net|packetsTx_summation_sum";
    public static final String[] BAD_PACKETS = {PKT_ERRORS_RX, PKT_ERRORS_TX, PKT_DROPPED_RX, PKT_DROPPED_TX};
    public static final String[] ALL_PACKETS = {PKT_RX, PKT_TX};

    public static final String CPU_THREAD_UTIL = "cpu|utilization_average";
    public static final String CPU_CORE_UTIL = "cpu|coreUtilization_average";
    public static final String MEM_DRAM_CAPACITY = "mem:DRAM|memory_tier_total_capacity";
    public static final String MEM_DRAM_FALLBACK = "mem|host_provisioned";
    public static final String MEM_CONSUMED = "mem|consumed_average";
    public static final String MEM_BALLOONED = "mem|vmmemctl_average";
    public static final String MEM_COMPRESSED = "mem|compressed_average";
    public static final String MEM_SWAPPED = "mem|swapout_average";
    public static final String MEM_TIERING_LATENCY = "mem:NVMe|latency.read_latest";
    public static final String CPU_RESERVED = "cpu|reservedCapacity_average";
    public static final String CPU_CAPACITY = "cpu|totalCapacity_average";
    public static final String MEM_RESERVED = "mem|reservedCapacity_average";
    public static final String VCPUS_ALLOCATED = "cpu|vcpus_allocated_on_all_powered_on_vms";
    public static final String MEM_ALLOCATED = "mem|memory_allocated_on_all_powered_on_vms";
    public static final String NVME_TIER_USAGE = "mem:NVMe|tier.usage";
    public static final String NVME_TIER_SIZE = "mem:NVMe|memory_tier_size";
    public static final String HOST_POWERED_ON = "sys|poweredOn";

    /** Every statistic a host is asked for, once: Cluster Risk and Consumption, the packet counters, availability. */
    public static final List<String> HOST_STATS = unique(MEM_DRAM_CAPACITY, MEM_DRAM_FALLBACK, CPU_THREAD_UTIL,
            CPU_CORE_UTIL, MEM_CONSUMED, MEM_BALLOONED, MEM_COMPRESSED, MEM_SWAPPED, MEM_TIERING_LATENCY,
            CPU_RESERVED, CPU_CAPACITY, MEM_RESERVED, VCPUS_ALLOCATED, MEM_ALLOCATED, NVME_TIER_USAGE,
            NVME_TIER_SIZE, PKT_ERRORS_RX, PKT_ERRORS_TX, PKT_DROPPED_RX, PKT_DROPPED_TX, PKT_RX, PKT_TX,
            HOST_POWERED_ON);

    public static final String HOST_CORES = "hardware|cpuInfo|numCpuCores";
    public static final String HOST_HT_ACTIVE = "config|hyperThread|active";
    public static final String HOST_HT_AVAILABLE = "config|hyperThread|available";
    public static final String HOST_CONNECTION = "runtime|connectionState";
    public static final String HOST_MAINTENANCE = "runtime|maintenanceState";
    public static final String HOST_POWER = "runtime|powerState";
    public static final String HOST_QUARANTINE = "runtime|quarantineState";
    public static final List<String> HOST_PROPS = unique(HOST_CORES, HOST_HT_ACTIVE, HOST_HT_AVAILABLE,
            HOST_CONNECTION, HOST_MAINTENANCE, HOST_POWER, HOST_QUARANTINE);

    public static final String NIC_SPEED = "net|speed";
    public static final String NIC_CONFIGURED_SPEED = "net|configuredSpeed";
    public static final List<String> NIC_STATS = unique(NIC_SPEED, NIC_CONFIGURED_SPEED);

    // ---- clusters
    public static final String CPU_USABLE = "cpu|demand|usableCapacity";
    public static final String CPU_TOTAL = "cpu|capacity_provisioned";
    public static final String MEM_USABLE = "mem|demand|usableCapacity";
    public static final String MEM_TOTAL = "mem|host_provisioned";
    public static final String CLUSTER_HOST_COUNT = "summary|total_number_hosts";
    public static final String CLUSTER_FAILOVER = "configuration|dasConfig|hostFailoverLevel";
    public static final List<String> CLUSTER_STATS = unique(CPU_USABLE, CPU_TOTAL, MEM_USABLE, MEM_TOTAL,
            CLUSTER_HOST_COUNT, CLUSTER_FAILOVER);
    public static final List<String> CLUSTER_PROPS = unique(CLUSTER_FAILOVER);
    /** A usable capacity can be negative (it is total less reserve and buffer): never a "no sample" marker. */
    public static final Set<String> SIGNED = new LinkedHashSet<>(Arrays.asList(CPU_USABLE, MEM_USABLE));

    // ---- the pak's own keys, read back
    public static final String P = "vCommunity|";
    public static final String CLUSTER_PERFORMANCE_SCORE = P + "Cluster Performance|Cluster Performance Score";
    public static final String CLUSTER_RISK_SCORE = P + "Cluster Risk|Cluster Risk Score";
    public static final String CLUSTER_CONSUMPTION_SCORE = P + "Cluster Consumption|Cluster Consumption Score";
    public static final List<String> CLUSTER_SCORES = unique(CLUSTER_PERFORMANCE_SCORE, CLUSTER_RISK_SCORE,
            CLUSTER_CONSUMPTION_SCORE);
    public static final String HOST_AVAILABILITY = P + "Availability|ESXi Host Operational Availability";
    public static final String CLUSTER_AVAILABILITY = P + "Availability|Cluster Operational Availability";
    public static final String WORST_CLUSTER_AVAILABILITY = P + "Availability|Worst Cluster Operational Availability";
    public static final String HOST_NETWORK_SPEED = P + "Network|Network Speed Degradation";
    public static final String DC_NETWORK_SPEED = P + "Network|Total Network Speed Degradation";
    public static final String PORT_GROUPS = P + "Number of Port Groups";
    public static final String VM_GUEST_KPI = P + "VM Guest OS KPI";

    public static final String PERIODIC = P + "Periodic Cluster Check|";
    public static final String PERIODIC_PERFORMANCE = PERIODIC + "Worst Cluster Performance";
    public static final String PERIODIC_RISK = PERIODIC + "Worst Cluster Risk";
    public static final String PERIODIC_CONSUMPTION = PERIODIC + "Worst Cluster Consumption";
    public static final String CLOSE_FOR_BUSINESS = PERIODIC + "Close for Business";
    public static final List<String> PERIODIC_PUBLISHED = unique(PERIODIC_PERFORMANCE, PERIODIC_RISK,
            PERIODIC_CONSUMPTION, CLOSE_FOR_BUSINESS);

    public static final String DELTA_CLOSE = P + "Cluster Delta Close Count";
    public static final String DELTA_OPEN = P + "Cluster Delta Open Count";
    public static final List<String> DELTA_PUBLISHED = unique(DELTA_CLOSE, DELTA_OPEN);

    private static List<String> unique(String... keys) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(Arrays.asList(keys))));
    }
}
