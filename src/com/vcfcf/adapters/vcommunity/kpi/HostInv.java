package com.vcfcf.adapters.vcommunity.kpi;

import java.util.ArrayList;
import java.util.List;

/** What one host of a cluster is, for the Cluster Risk and Consumption rows (physical size and the VMs on it). */
public final class HostInv {

    /** One powered-on VM on the host; a size of null or 0 is left out of the largest-VM rows. */
    public static final class Vm {
        public final Double vcpus;
        public final Double memoryMb;

        public Vm(Double vcpus, Double memoryMb) {
            this.vcpus = vcpus;
            this.memoryMb = memoryMb;
        }
    }

    public final String id;
    /** Physical cores, or null. */
    public final Double cores;
    /** CPU threads: twice the cores with hyper-threading both available and active, else the cores; null without cores. */
    public final Double threads;
    public final String connectionState;
    public final String maintenanceState;
    public final String powerState;
    public final List<Vm> vms = new ArrayList<>();

    public HostInv(String id, Double cores, Double threads, String connectionState, String maintenanceState,
            String powerState) {
        this.id = id;
        this.cores = cores;
        this.threads = threads;
        this.connectionState = connectionState;
        this.maintenanceState = maintenanceState;
        this.powerState = powerState;
    }

    /** Twice the cores when hyper-threading is both available and active (the text {@code true}), else the cores. */
    public static Double threads(Double cores, String available, String active) {
        if (cores == null) return null;
        boolean ht = "true".equals(lower(available)) && "true".equals(lower(active));
        return ht ? cores * 2 : cores;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }
}
