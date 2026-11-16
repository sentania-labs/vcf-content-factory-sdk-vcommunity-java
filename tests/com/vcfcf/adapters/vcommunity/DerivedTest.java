package com.vcfcf.adapters.vcommunity;

import static com.vcfcf.adapters.vcommunity.SoapFx.arr;
import static com.vcfcf.adapters.vcommunity.SoapFx.e;
import static com.vcfcf.adapters.vcommunity.SoapFx.el;

import com.vcfcf.adapters.vcommunity.kpi.KpiMetric;
import com.vcfcf.adapters.vcommunity.kpi.KpiScorer;
import com.vcfcf.adapters.vcommunity.kpi.KpiTable;
import com.vcfcf.adapters.vcommunity.kpi.PeriodicWindow;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.text.PyRound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The derived metrics (phase P4) through the real entry point: a cycle of the engine over the real vim25 client and
 * the property reads, against a vCenter built from hand-written SOAP answers, a Suite API fake that holds what the
 * VMware adapter and earlier cycles stored ({@link P4Data}), and the cycle's push. What is pushed is what is
 * asserted, key by key, and each assertion counts for {@link Coverage4}.
 */
public final class DerivedTest {

    static final String V = "vCommunity|";
    static final long NOW = PropertiesTest.NOW;
    static final double GB = 1024.0 * 1024.0;

    static final String C1 = "res-domain-c1";
    static final String C2 = "res-domain-c2";
    static final String C3 = "res-domain-c3";
    static final String H1 = "res-host-1";
    static final String H2 = "res-host-2";
    static final String H3 = "res-host-3";
    static final String H4 = "res-host-4";
    static final String H5 = "res-host-5";
    static final String VC = "vc-res";
    static final String WORLD = "world-res";
    static final String DC1 = "res-datacenter-1";
    static final String DC2 = "res-datacenter-2";

    // ------------------------------------------------------------------ the lab

    /** One scenario's world: the vCenter, Operations' answers and the engine. */
    static final class Lab implements AutoCloseable {
        final FakeVcenter vc = new FakeVcenter();
        final PropertiesTest.Rig r;
        final P4Data d = new P4Data(NOW);

        Lab(FakeVc fake, String... cfg) {
            vc.add(new SoapFx.Obj("Datacenter", "datacenter-1").name("DC1").put("networkFolder", SoapFx.moref("Folder", "group-n1")));
            vc.add(new SoapFx.Obj("Datacenter", "datacenter-2").name("DC2").put("networkFolder", SoapFx.moref("Folder", "group-n2")));
            for (String c : new String[] {"domain-c1:Prod", "domain-c2:Edge", "domain-c3:Empty"}) {
                vc.add(P2Fixtures.clusterBare(c.split(":")[0], c.split(":")[1]));
            }
            vc.add(host("host-1", "domain-c1", "datacenter-1", "connected", "VM Network", "Mgmt"));
            vc.add(host("host-2", "domain-c1", "datacenter-1", "connected", "VM Network", "vMotion"));
            vc.add(host("host-3", "domain-c1", "datacenter-1", "connected"));
            vc.add(host("host-4", "domain-c2", "datacenter-2", "connected", "VM Network"));
            vc.add(host("host-5", null, "datacenter-2", "disconnected"));
            vc.add(vm("vm-1", "host-1")).add(vm("vm-2", "host-1")).add(vm("vm-3", "host-2")).add(vm("vm-4", "host-4"))
                    .add(vm("vm-5", "host-3")).add(vm("vm-6", null));
            vc.add(new SoapFx.Obj("DistributedVirtualPortgroup", "dvportgroup-1").name("DVPG-A").in("group-n1"));
            vc.add(new SoapFx.Obj("DistributedVirtualPortgroup", "dvportgroup-2").name("DVPG-A").in("group-n2"));
            vc.add(new SoapFx.Obj("DistributedVirtualPortgroup", "dvportgroup-3").name("DVPG-B").in("group-n2"));
            // the pool tree of Prod: a root pool, then levels 1 to 6, a vApp, and one pool of a standalone host
            vc.add(pool("resgroup-r1", "Resources", "ClusterComputeResource", "domain-c1", false));
            String parent = "resgroup-r1";
            for (int i = 1; i <= 6; i++) {
                vc.add(pool("resgroup-" + i, "pool" + i, "ResourcePool", parent, false));
                parent = "resgroup-" + i;
            }
            vc.add(pool("resgroup-v1", "vApp", "ResourcePool", "resgroup-r1", true));
            // a Supervisor: the Namespaces pool is an Operations pool, each namespace under it (and what is in one) is not
            vc.add(pool("resgroup-n0", "Namespaces", "ResourcePool", "resgroup-r1", false));
            for (int i = 1; i <= 3; i++) {
                vc.add(pool("resgroup-n" + i, "ns" + i, "ResourcePool", "resgroup-n0", false)
                        .put("namespace", SoapFx.str("namespace-" + i)));
            }
            vc.add(pool("resgroup-n1c", "ns1-child", "ResourcePool", "resgroup-n1", false));
            vc.add(pool("resgroup-r2", "Resources", "ClusterComputeResource", "domain-c2", false));
            vc.add(pool("resgroup-s1", "standalone", "ResourcePool", "resgroup-s0", false));
            vc.add(pool("resgroup-s0", "Resources", "ComputeResource", "domain-s5", false));
            r = new PropertiesTest.Rig(fake, vc, cfg);
            PropertiesTest.mapAll(r.ops, vc);
            PropertiesTest.files(r.ops, "", "", "", "");
            r.ops.add("Datacenter", DC1, "datacenter-1", FakeVc.INSTANCE_UUID);
            r.ops.add("Datacenter", DC2, "datacenter-2", FakeVc.INSTANCE_UUID);
            r.ops.data = d;
            fill();
        }

        static SoapFx.Obj host(String moid, String cluster, String dc, String state, String... portGroups) {
            SoapFx.Obj h = P2Fixtures.hostSparse(moid, "esx-" + moid.substring(5));
            h.put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", state));
            h.put("parent", cluster == null ? SoapFx.moref("ComputeResource", "domain-s5")
                    : SoapFx.moref("ClusterComputeResource", cluster));
            if (portGroups.length > 0 || state.equals("connected")) {
                StringBuilder pg = new StringBuilder();
                for (String n : portGroups) pg.append(el("HostPortGroup", el("spec", e("name", n))));
                h.put("config.network.portgroup", arr("ArrayOfHostPortGroup", pg.toString()));
            }
            if (state.equals("connected")) {
                h.put("config.network.pnic", arr("ArrayOfPhysicalNic",
                        el("PhysicalNic", e("device", "vmnic0")) + el("PhysicalNic", e("device", "vmnic1"))));
            }
            return h.in(dc);
        }

        static SoapFx.Obj vm(String moid, String host) {
            SoapFx.Obj v = P2Fixtures.vmSparse(moid, "vm-" + moid.substring(3));
            if (host != null) v.put("runtime.host", SoapFx.moref("HostSystem", host));
            return v;
        }

        static SoapFx.Obj pool(String moid, String name, String parentType, String parent, boolean vApp) {
            SoapFx.Obj p = new SoapFx.Obj("ResourcePool", moid).name(name).put("parent", SoapFx.moref(parentType, parent));
            return vApp ? p.asType("VirtualApp") : p;
        }

        void vmOps(String n, String power, String cpus, String memKb, String cpuShares, String memShares) {
            String id = "res-vm-" + n;
            d.prop(id, StatKeys.VM_POWER, power);
            if (cpus != null) d.prop(id, StatKeys.VM_VCPUS, cpus);
            if (memKb != null) d.prop(id, StatKeys.VM_MEMORY_KB, memKb);
            if (cpuShares != null) d.prop(id, StatKeys.VM_CPU_SHARES, cpuShares);
            if (memShares != null) d.prop(id, StatKeys.VM_MEM_SHARES, memShares);
        }

        void vmStats(String n, double ready, double costop, double memCont, double disk) {
            String id = "res-vm-" + n;
            d.stat(id, StatKeys.VM_READY, ready).stat(id, StatKeys.VM_COSTOP, costop)
                    .stat(id, StatKeys.VM_MEM_CONTENTION, memCont).stat(id, StatKeys.VM_DISK_LATENCY, disk);
        }

        void hostOps(String n, String cores, String maintenance, double poweredOn) {
            String id = "res-host-" + n;
            d.prop(id, StatKeys.HOST_CORES, cores).prop(id, StatKeys.HOST_HT_AVAILABLE, "true")
                    .prop(id, StatKeys.HOST_HT_ACTIVE, "true").prop(id, StatKeys.HOST_CONNECTION, "connected")
                    .prop(id, StatKeys.HOST_MAINTENANCE, maintenance).prop(id, StatKeys.HOST_POWER, "Powered On")
                    .prop(id, StatKeys.HOST_QUARANTINE, "notInQuarantine");
            d.stat(id, StatKeys.HOST_POWERED_ON, poweredOn);
        }

        void hostStats(String n, double cpu, double core, double consumedGb, double ballGb, double vcpusAllocated,
                double memAllocatedGb, double errors, double packets) {
            String id = "res-host-" + n;
            d.stat(id, StatKeys.CPU_THREAD_UTIL, cpu).stat(id, StatKeys.CPU_CORE_UTIL, core)
                    .stat(id, StatKeys.MEM_CONSUMED, consumedGb * GB).stat(id, StatKeys.MEM_BALLOONED, ballGb * GB)
                    .stat(id, StatKeys.MEM_COMPRESSED, 0).stat(id, StatKeys.MEM_SWAPPED, 0)
                    .stat(id, StatKeys.MEM_RESERVED, 0).stat(id, StatKeys.MEM_DRAM_CAPACITY, 512 * GB)
                    .stat(id, StatKeys.CPU_RESERVED, 0).stat(id, StatKeys.CPU_CAPACITY, 64000)
                    .stat(id, StatKeys.VCPUS_ALLOCATED, vcpusAllocated).stat(id, StatKeys.MEM_ALLOCATED, memAllocatedGb * GB)
                    .stat(id, StatKeys.PKT_ERRORS_RX, errors).stat(id, StatKeys.PKT_ERRORS_TX, 0)
                    .stat(id, StatKeys.PKT_DROPPED_RX, 0).stat(id, StatKeys.PKT_DROPPED_TX, 0)
                    .stat(id, StatKeys.PKT_RX, packets).stat(id, StatKeys.PKT_TX, 0);
        }

        void nic(String n, double s0, double c0, double s1, double c1) {
            String id = "res-host-" + n;
            nicProps(n, "vmnic0", num(s0), num(c0));
            nicProps(n, "vmnic1", num(s1), num(c1));
        }

        private static String num(double v) {
            return String.valueOf((long) v);
        }

        /** One NIC's two properties as the Suite API holds them (text); null leaves a property out. */
        void nicProps(String n, String dev, String speed, String configured) {
            String id = "res-host-" + n;
            d.props.computeIfAbsent(id, k -> new java.util.LinkedHashMap<>());
            if (speed != null) d.prop(id, "net:" + dev + "|speed", speed);
            else d.props.get(id).remove("net:" + dev + "|speed");
            if (configured != null) d.prop(id, "net:" + dev + "|configuredSpeed", configured);
            else d.props.get(id).remove("net:" + dev + "|configuredSpeed");
        }

        /** The VMware adapter's data: what the derived metrics read back. */
        void fill() {
            vmOps("1", "Powered On", "4", "16777216", "4000", "163840");
            vmOps("2", "Powered On", "2", "8388608", null, null);
            d.stat("res-vm-2", StatKeys.VM_CPU_SHARES, 2000).stat("res-vm-2", StatKeys.VM_MEM_SHARES, 81920);
            vmOps("3", "Powered On", "8", "33554432", "16000", "327680");
            vmOps("4", "Powered On", "2", "4194304", "2000", "40960");
            vmOps("5", "Powered Off", "2", "4194304", "2000", "40960");
            vmOps("6", "Powered On", null, null, null, null);
            vmStats("1", 10, 1.0, 0.5, 30);
            for (String k : new String[] {StatKeys.VM_OVERLAP, StatKeys.VM_DROPPED_TX, StatKeys.VM_BALLOON,
                StatKeys.GUEST_CPU_QUEUE, StatKeys.GUEST_DISK_QUEUE, StatKeys.GUEST_PAGE_OUT}) {
                d.stat("res-vm-1", k, 0);
            }
            d.stat("res-vm-1", StatKeys.VM_CPU_RUN, 50).stat("res-vm-1", StatKeys.VM_COMPRESSED, 10)
                    .stat("res-vm-1", StatKeys.VM_SWAPPED, 10).stat("res-vm-1", StatKeys.VM_GUEST_USED, 1000)
                    .stat("res-vm-1", StatKeys.GUEST_FREE_KB, 2048.0 * 1024);
            vmStats("2", 2, 0, 0, 10);
            vmStats("3", 4, 0.5, 0.25, 60);
            // vm-4 is new: Operations has no statistic for it yet
            hostOps("1", "32", "notInMaintenance", 1);
            hostOps("2", "32", "notInMaintenance", 1);
            hostOps("3", "32", "inMaintenance", 1);
            hostOps("4", "16", "notInMaintenance", 1);
            d.prop("res-host-4", StatKeys.HOST_HT_AVAILABLE, "false");
            d.prop("res-host-5", StatKeys.HOST_CONNECTION, "disconnected");
            hostStats("1", 40, 80, 256, 0, 6, 24, 5, 1000);
            hostStats("2", 60, 90, 384, 5.12, 8, 32, 0, 1000);
            // host-3 is in maintenance and reports nothing but that it is on
            d.stat("res-host-4", StatKeys.CPU_THREAD_UTIL, 10).stat("res-host-4", StatKeys.MEM_DRAM_CAPACITY, 128 * GB)
                    .stat("res-host-4", StatKeys.MEM_CONSUMED, 64 * GB).stat("res-host-4", StatKeys.MEM_BALLOONED, 0)
                    .stat("res-host-4", StatKeys.MEM_COMPRESSED, 0).stat("res-host-4", StatKeys.MEM_SWAPPED, 0);
            nic("1", 1000, 10000, 10000, 10000);
            nic("2", 10000, 10000, 10000, 10000);
            d.stat("res-domain-c1", StatKeys.CLUSTER_HOST_COUNT, 3).stat("res-domain-c1", StatKeys.CLUSTER_FAILOVER, 0)
                    .stat("res-domain-c1", StatKeys.CPU_TOTAL, 100000).stat("res-domain-c1", StatKeys.CPU_USABLE, 80000)
                    .stat("res-domain-c1", StatKeys.MEM_TOTAL, 1000 * GB).stat("res-domain-c1", StatKeys.MEM_USABLE, 800 * GB);
            d.stat("res-domain-c2", StatKeys.CLUSTER_HOST_COUNT, 1)
                    .stat("res-domain-c2", StatKeys.CPU_TOTAL, 10000).stat("res-domain-c2", StatKeys.CPU_USABLE, 10000)
                    .stat("res-domain-c2", StatKeys.MEM_TOTAL, 128 * GB).stat("res-domain-c2", StatKeys.MEM_USABLE, 128 * GB);
            d.stat("res-domain-c3", StatKeys.CLUSTER_HOST_COUNT, 0);
        }

        /** The value pushed in push n for a resource and key. */
        Double st(int n, String rid, String key) {
            return r.ops.stat(n, rid, key);
        }

        @Override
        public void close() {
            r.close();
        }
    }

    // ------------------------------------------------------------------ assertions

    /** The value pushed (a number rounded to two places, or an exact count) equals {@code want}; counted for the coverage. */
    static void chk(Lab l, String kind, String rid, String key, double want) {
        chkAt(l, 0, kind, rid, key, want);
    }

    static void chkAt(Lab l, int n, String kind, String rid, String key, double want) {
        Double got = l.st(n, rid, key);
        T.check(got != null, kind + " " + rid + " must carry " + key + "; it has " + keysOf(l, n, rid));
        T.check(Math.abs(got - want) < 1e-9 || Math.abs(got - want) <= 0.0050001 && Math.abs(got - PyRound.round2(got)) < 1e-9,
                rid + " " + key + ": want " + want + " got " + got);
        Coverage4.asserted(kind, key);
    }

    static void nothing(Lab l, String rid, String key) {
        T.check(l.st(0, rid, key) == null, rid + " must not carry " + key + " but has " + l.st(0, rid, key));
    }

    static String keysOf(Lab l, int n, String rid) {
        Map<String, Double> m = l.r.ops.pushedStats.get(n).get(rid);
        return m == null ? "nothing" : m.keySet().toString();
    }


    static void kind(String name) {
        Coverage4.scenario = "DerivedTest: " + name;
    }

    public static void main(String[] args) throws Exception {
        try (FakeVc fake = new FakeVc(FakeVc.happy())) {
            run("values of every family", () -> values(fake));
            run("memory tiering", () -> tiering(fake));
            run("a cluster with no host, all hosts in maintenance, a new VM, an off VM", () -> emptyShapes(fake));
            run("world roll-ups over every vCenter", () -> world(fake));
            run("cluster delta, once per window", () -> delta(fake));
            run("periodic cluster check, window rollover, restart", () -> periodic(fake));
            run("business applications", () -> business(fake));
            run("a failed read withholds, never a zero", () -> failedReads(fake));
            run("review fixes: stale network counts, placement, shares per key, warnings, stale lists", () -> reviewFixes(fake));
            run("failed membership reads", () -> failedMembership(fake));
            run("a stale sample is no data, an empty answer is a reading", () -> staleAndEmpty(fake));
            run("the deadline", () -> deadline(fake));
        }
        Coverage4.scenario = "coverage";
        Coverage4.verifyOrWrite();
        T.done("P4 key coverage table equals the key map and the tests");
    }

    interface Scenario {
        void go() throws Exception;
    }

    static void run(String name, Scenario s) throws Exception {
        kind(name);
        long t0 = System.currentTimeMillis();
        s.go();
        T.done("derived: " + name + " (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    static final String VM = "VirtualMachine";
    static final String HOST = "HostSystem";
    static final String CL = "ClusterComputeResource";

    /** The history requests that ask for the cluster KPIs (the periodic check's, not the Cluster Delta's). */
    static int scoreHistories(Lab l) {
        int n = 0;
        for (String b : l.d.historyBodies) if (b.contains("Cluster Performance Score")) n++;
        return n;
    }

    static double kpi(KpiMetric[] metrics, boolean worse, Object... rows) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < rows.length; i += 2) m.put((String) rows[i], ((Number) rows[i + 1]).doubleValue());
        return KpiScorer.evaluate(metrics, m, worse).score;
    }

    // ------------------------------------------------------------------ values

    static void values(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            CycleEngine.Result res = l.r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            T.eq("None in the last cycle", String.valueOf(l.r.health(0, "First Failure")), "no read failed");
            T.eq("OK", l.r.health(0, "Collector:kpi|Status"), "the KPI collector");
            T.eq("OK", l.r.health(0, "Collector:datacenter|Status"), "the Datacenter collector");
            T.eq("OK", l.r.health(0, "Collector:world|Status"), "the World collector");
            T.eq("OK", l.r.health(0, "Collector:business_applications|Status"), "the Business Applications collector");

            // ---- virtual machines
            String v1 = "res-vm-1";
            chk(l, VM, v1, V + "Configuration|Share per vCPU", 1000.0);
            chk(l, VM, v1, V + "Configuration|Share per MB of Memory", 10.0);
            chk(l, VM, v1, V + "VM Performance|Memory Zip Swap", 2.0);
            double vm1 = kpi(KpiTable.VM_PERFORMANCE, false, "CPU Co-Stop", 1.0, "CPU Ready", 10.0, "CPU Overlap", 0.0,
                    "Memory Contention", 0.5, "Disk Latency", 30.0, "Network Dropped Tx", 0.0, "CPU Run", 50.0,
                    "Memory Ballooned", 0.0, "Memory Zip Swap", 2.0);
            chk(l, VM, v1, V + "VM Performance|VM Performance Score", vm1);
            chk(l, VM, v1, V + "Guest OS Performance|Guest OS Performance Score", 100.0);
            chk(l, VM, v1, V + "VM Guest OS KPI", PyRound.round2((vm1 + 100.0) / 2));
            // vm-2 is read from statistics for its shares, has no Tools: VM Performance alone is its KPI
            chk(l, VM, "res-vm-2", V + "Configuration|Share per vCPU", 1000.0);
            chk(l, VM, "res-vm-2", V + "Configuration|Share per MB of Memory", 10.0);
            double vm2 = kpi(KpiTable.VM_PERFORMANCE, false, "CPU Co-Stop", 0.0, "CPU Ready", 2.0, "Memory Contention", 0.0,
                    "Disk Latency", 10.0);
            chk(l, VM, "res-vm-2", V + "VM Performance|VM Performance Score", vm2);
            chk(l, VM, "res-vm-2", V + "VM Guest OS KPI", vm2);
            nothing(l, "res-vm-2", V + "Guest OS Performance|Guest OS Performance Score");
            nothing(l, "res-vm-2", V + "VM Performance|Memory Zip Swap");
            chk(l, VM, "res-vm-3", V + "Configuration|Share per vCPU", 2000.0);
            // a new VM has no statistic: shares, and no score of any kind
            chk(l, VM, "res-vm-4", V + "Configuration|Share per vCPU", 1000.0);
            nothing(l, "res-vm-4", V + "VM Performance|VM Performance Score");
            nothing(l, "res-vm-4", V + "VM Guest OS KPI");
            // a powered-off VM has shares and no score; a VM Operations knows no size of has no shares
            chk(l, VM, "res-vm-5", V + "Configuration|Share per MB of Memory", 10.0);
            nothing(l, "res-vm-5", V + "VM Performance|VM Performance Score");
            nothing(l, "res-vm-6", V + "Configuration|Share per vCPU");

            // ---- hosts
            chk(l, HOST, H1, V + "ESXi Performance|Worst VM CPU Ready", 10.0);
            chk(l, HOST, H1, V + "ESXi Performance|Worst VM CPU Co-Stop", 1.0);
            chk(l, HOST, H1, V + "ESXi Performance|Worst VM Memory Latency", 0.5);
            chk(l, HOST, H1, V + "ESXi Performance|Worst VM Disk Latency", 30.0);
            chk(l, HOST, H1, V + "ESXi Performance|90th Percentile VM CPU Ready", 10.0);
            chk(l, HOST, H1, V + "ESXi Performance|90th Percentile VM CPU Co-Stop", 1.0);
            chk(l, HOST, H1, V + "ESXi Performance|90th Percentile VM Memory Latency", 0.5);
            chk(l, HOST, H1, V + "ESXi Performance|90th Percentile VM Disk Latency", 30.0);
            chk(l, HOST, H1, V + "ESXi Performance|ESXi Bad Network Packets", 0.5);
            chk(l, HOST, H1, V + "ESXi Performance|ESXi Performance Score", kpi(KpiTable.ESXI_PERFORMANCE, false,
                    "Worst VM CPU Ready", 10.0, "Worst VM CPU Co-Stop", 1.0, "Worst VM Memory Latency", 0.5,
                    "Worst VM Disk Latency", 30.0, "90th Percentile VM CPU Ready", 10.0, "90th Percentile VM CPU Co-Stop", 1.0,
                    "90th Percentile VM Memory Latency", 0.5, "90th Percentile VM Disk Latency", 30.0,
                    "ESXi Bad Network Packets", 0.5));
            chk(l, HOST, H2, V + "ESXi Performance|Worst VM CPU Ready", 4.0);
            chk(l, HOST, H2, V + "ESXi Performance|ESXi Bad Network Packets", 0.0);
            chk(l, HOST, H1, V + "Availability|ESXi Host Operational Availability", 100.0);
            chk(l, HOST, H3, V + "Availability|ESXi Host Operational Availability", 75.0);
            nothing(l, H3, V + "ESXi Performance|ESXi Performance Score");
            chk(l, HOST, H1, V + "Network|Network Speed Degradation", 55.0);
            chk(l, HOST, H2, V + "Network|Network Speed Degradation", 100.0);
            nothing(l, H3, V + "Network|Network Speed Degradation");
            nothing(l, H4, V + "Network|Network Speed Degradation");
            // the host vCenter says is disconnected is not collected
            T.check(l.r.ops.pushedStats.get(0).get(H5) == null, "a disconnected host gets no derived key");

            // ---- clusters: Prod
            String c = V + "Cluster Performance|";
            chk(l, CL, C1, c + "Worst VM CPU Ready", 10.0);
            chk(l, CL, C1, c + "Worst VM CPU Co-Stop", 1.0);
            chk(l, CL, C1, c + "Worst VM Memory Latency", 0.5);
            chk(l, CL, C1, c + "Worst VM Disk Latency", 60.0);
            chk(l, CL, C1, c + "90th Percentile VM CPU Ready", 10.0);
            chk(l, CL, C1, c + "90th Percentile VM CPU Co-Stop", 1.0);
            chk(l, CL, C1, c + "90th Percentile VM Memory Latency", 0.5);
            chk(l, CL, C1, c + "90th Percentile VM Disk Latency", 60.0);
            chk(l, CL, C1, c + "Highest ESXi Bad Network Packets", 0.5);
            chk(l, CL, C1, c + "Cluster Performance Score", kpi(KpiTable.CLUSTER_PERFORMANCE, false,
                    "Worst VM CPU Ready", 10.0, "Worst VM CPU Co-Stop", 1.0, "Worst VM Memory Latency", 0.5,
                    "Worst VM Disk Latency", 60.0, "90th Percentile VM CPU Ready", 10.0, "90th Percentile VM CPU Co-Stop", 1.0,
                    "90th Percentile VM Memory Latency", 0.5, "90th Percentile VM Disk Latency", 60.0,
                    "Highest ESXi Bad Network Packets", 0.5));
            String r = V + "Cluster Risk|";
            chk(l, CL, C1, r + "Highest ESXi CPU Thread Utilization", 60.0);
            chk(l, CL, C1, r + "Worst ESXi CPU Imbalance", 1.33);
            chk(l, CL, C1, r + "Highest ESXi Memory Consumed", 75.0);
            chk(l, CL, C1, r + "Worst ESXi Memory Ballooned", 1.0);
            chk(l, CL, C1, r + "Worst ESXi Memory Zip Swap", 0.0);
            nothing(l, C1, r + "Worst ESXi Memory Tiering Latency");
            chk(l, CL, C1, r + "Highest ESXi CPU Reservation", 0.0);
            chk(l, CL, C1, r + "Highest ESXi Memory Reservation", 0.0);
            chk(l, CL, C1, r + "Highest ESXi CPU Overcommit Ratio", 0.25);
            chk(l, CL, C1, r + "Highest ESXi Memory Overcommit Ratio", 0.0625);
            chk(l, CL, C1, r + "Highest CPU Monster VM to Host Ratio", 0.5);
            chk(l, CL, C1, r + "Highest Memory Monster VM to Host Ratio", 0.15625);
            chk(l, CL, C1, r + "Cluster Risk Score", kpi(KpiTable.CLUSTER_RISK, true,
                    "Highest ESXi CPU Thread Utilization", 60.0, "Worst ESXi CPU Imbalance", 1.33,
                    "Highest ESXi Memory Consumed", 75.0, "Worst ESXi Memory Ballooned", 1.0, "Worst ESXi Memory Zip Swap", 0.0,
                    "Highest ESXi CPU Reservation", 0.0, "Highest ESXi Memory Reservation", 0.0,
                    "Highest ESXi CPU Overcommit Ratio", 0.25, "Highest ESXi Memory Overcommit Ratio", 0.06,
                    "Highest CPU Monster VM to Host Ratio", 0.5, "Highest Memory Monster VM to Host Ratio", 0.16));
            String k = V + "Cluster Consumption|";
            // 64 of 102.4 usable threads; 636 GB of 819.2 GB usable; 5.12 GB of 819.2 GB
            chk(l, CL, C1, k + "Cluster-wide ESXi CPU Thread Utilization", 62.5);
            chk(l, CL, C1, k + "Cluster-wide ESXi Memory Consumed", 640.0 / 819.2 * 100);
            nothing(l, C1, k + "Cluster Memory Tiering Consumption");
            chk(l, CL, C1, k + "Cluster-wide ESXi Memory Ballooned", 5.12 / 819.2 * 100);
            chk(l, CL, C1, k + "Cluster-wide ESXi Memory Zip Swap", 0.0);
            chk(l, CL, C1, k + "Cluster Consumption Score", kpi(KpiTable.CLUSTER_CONSUMPTION, true,
                    "Cluster-wide ESXi CPU Thread Utilization", 62.5, "Cluster-wide ESXi Memory Consumed", 78.12,
                    "Cluster-wide ESXi Memory Ballooned", 0.62, "Cluster-wide ESXi Memory Zip Swap", 0.0));
            // 3 hosts, none to spare: (100 + 100 + 75) / 3
            chk(l, CL, C1, V + "Availability|Cluster Operational Availability", 275.0 / 3);
            // the pools: levels 1 to 3 are counted (pool1 to pool3 and Namespaces), 2 to 5 cascade (pool2 to pool5); the root
            // pool, the vApp, a standalone host's pool, the three Supervisor namespaces and the pool inside one are not
            chk(l, CL, C1, V + "Resource Pools|Number of Resource Pools", 4.0);
            chk(l, CL, C1, V + "Resource Pools|Cascading Resource Pools", 4.0);
            // ---- Edge: one host, a 16-core host without hyper-threading, no failover level
            T.eq(10.0, l.st(0, C2, r + "Highest ESXi CPU Thread Utilization"), "Edge risk");
            T.eq(10.0, l.st(0, C2, k + "Cluster-wide ESXi CPU Thread Utilization"), "Edge consumption: 1.6 of 16 threads");
            T.eq(100.0, l.st(0, C2, V + "Availability|Cluster Operational Availability"), "Edge availability");
            T.eq(0.0, l.st(0, C2, V + "Resource Pools|Number of Resource Pools"), "a cluster with only a root pool has none");

            // ---- Datacenters and the vCenter
            chk(l, "Datacenter", DC1, V + "Network|Total Network Speed Degradation", 1.0);
            T.eq(0.0, l.st(0, DC2, V + "Network|Total Network Speed Degradation"), "no comparable NIC: 0, as upstream (Q12)");
            chk(l, "VMwareAdapter Instance", VC, V + "Availability|Worst Cluster Operational Availability", 275.0 / 3);
            chk(l, "VMwareAdapter Instance", VC, V + "Number of Port Groups", 7.0);
            // the empty cluster gets no score and no availability
            nothing(l, C3, V + "Cluster Risk|Cluster Risk Score");
            nothing(l, C3, V + "Availability|Cluster Operational Availability");
            T.eq(0.0, l.st(0, C3, V + "Resource Pools|Number of Resource Pools"), "a cluster with no pools is a real 0");
        }
    }

    static void tiering(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            // host-2 has an NVMe memory tier of 256 GB with 20 GB in use and a read latency of 300 microseconds
            l.d.stat(H2, StatKeys.MEM_TIERING_LATENCY, 300).stat(H2, StatKeys.NVME_TIER_SIZE, 256 * GB)
                    .stat(H2, StatKeys.NVME_TIER_USAGE, 20 * 1024);
            l.r.run();
            chk(l, CL, C1, V + "Cluster Risk|Worst ESXi Memory Tiering Latency", 300.0);
            // 20 GB of 256 GB x the 80 % that is usable
            chk(l, CL, C1, V + "Cluster Consumption|Cluster Memory Tiering Consumption", 20.0 / 204.8 * 100);
            T.eq(60.0, l.st(0, C1, V + "Cluster Risk|Highest ESXi CPU Thread Utilization"), "the other rows do not move");
        }
    }

    // ------------------------------------------------------------------ shapes with nothing to say

    static void emptyShapes(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            // every host of Edge in maintenance: no VM runs there, so nothing is consumed; risk still reads the hosts
            l.d.prop(H4, StatKeys.HOST_MAINTENANCE, "inMaintenance");
            l.r.run();
            nothing(l, C2, V + "Cluster Consumption|Cluster Consumption Score");
            nothing(l, C2, V + "Cluster Consumption|Cluster-wide ESXi CPU Thread Utilization");
            T.check(l.st(0, C2, V + "Cluster Risk|Cluster Risk Score") != null, "the risk of the same hosts is still scored");
            // 25 points are lost for the host in maintenance: one host, N+0
            T.eq(75.0, l.st(0, C2, V + "Availability|Cluster Operational Availability"), "75: maintenance mode");
        }
        try (Lab l = new Lab(fake)) {
            // the cluster's usable capacity is none while its hosts run: full, 100 (the original's rule)
            l.d.stat(C2, StatKeys.CPU_USABLE, 0);
            l.r.run();
            T.eq(100.0, l.st(0, C2, V + "Cluster Consumption|Cluster Consumption Score"), "full");
            nothing(l, C2, V + "Cluster Consumption|Cluster-wide ESXi CPU Thread Utilization");
            T.eq(50.0, l.st(0, C2, V + "Cluster Consumption|Cluster-wide ESXi Memory Consumed"), "the memory rows are kept");
        }
        try (Lab l = new Lab(fake)) {
            // HA tolerates every host of the cluster: no availability value
            l.d.stat(C2, StatKeys.CLUSTER_FAILOVER, 1);
            l.r.run();
            nothing(l, C2, V + "Availability|Cluster Operational Availability");
            T.eq(PyRound.round2(275.0 / 3), l.st(0, VC, V + "Availability|Worst Cluster Operational Availability"), "the cluster without a value is left out");
        }
        try (Lab l = new Lab(fake)) {
            // nothing answered for any host: upstream sends no availability at all (Q15), not 0 %
            for (int h = 1; h <= 5; h++) {
                l.d.stats.remove("res-host-" + h);
                l.d.props.remove("res-host-" + h);
            }
            l.r.run();
            T.check(l.st(0, H1, V + "Availability|ESXi Host Operational Availability") == null, "no host availability");
            T.check(l.st(0, C1, V + "Availability|Cluster Operational Availability") == null, "no cluster availability");
            T.check(l.st(0, VC, V + "Availability|Worst Cluster Operational Availability") == null, "no worst cluster");
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "no NIC speed for any host: none");
            T.check(l.st(0, DC1, V + "Network|Total Network Speed Degradation") == null, "and no Datacenter count");
            T.eq("None in the last cycle", String.valueOf(l.r.health(0, "First Failure")), "none of it is a failed read");
        }
    }

    // ------------------------------------------------------------------ World

    static void world(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            l.d.stat(C1, StatKeys.CLUSTER_PERFORMANCE_SCORE, 80).stat(C1, StatKeys.CLUSTER_RISK_SCORE, 30)
                    .stat(C1, StatKeys.CLUSTER_CONSUMPTION_SCORE, 40);
            l.d.stat(C2, StatKeys.CLUSTER_PERFORMANCE_SCORE, 60).stat(C2, StatKeys.CLUSTER_RISK_SCORE, 50)
                    .stat(C2, StatKeys.CLUSTER_CONSUMPTION_SCORE, 20);
            l.d.stat(C1, StatKeys.CLUSTER_AVAILABILITY, 91.67).stat(C2, StatKeys.CLUSTER_AVAILABILITY, 100);
            l.d.stat(H1, StatKeys.HOST_AVAILABILITY, 100).stat(H2, StatKeys.HOST_AVAILABILITY, 100)
                    .stat(H3, StatKeys.HOST_AVAILABILITY, 75).stat(H4, StatKeys.HOST_AVAILABILITY, 100);
            l.d.stat(H1, StatKeys.HOST_NETWORK_SPEED, 55).stat(H2, StatKeys.HOST_NETWORK_SPEED, 100);
            l.r.ops.add("VMwareAdapter Instance", "vc2-res", null, "ANOTHER-VCENTER-UUID");
            l.r.ops.add("VMwareAdapter Instance", "vc3-res", null, "A-THIRD-VCENTER-UUID");
            l.d.stat(VC, StatKeys.PORT_GROUPS, 7).stat("vc2-res", StatKeys.PORT_GROUPS, 3, 20 * 60_000L)
                    .stat("vc3-res", StatKeys.PORT_GROUPS, 5, 14 * 60_000L);
            l.d.prop("res-vm-1", "summary|guest|toolsVersionStatus2", "Guest Tools Not Installed");
            l.d.prop("res-vm-2", "summary|guest|toolsVersionStatus2", "Guest Tools Current")
                    .prop("res-vm-2", "summary|guest|toolsRunningStatus", "Guest Tools Not Running");
            l.d.prop("res-vm-3", "summary|guest|toolsVersionStatus2", "Guest Tools Current")
                    .prop("res-vm-3", "summary|guest|toolsRunningStatus", "Guest Tools Running");
            l.d.prop("res-vm-5", "summary|guest|toolsVersionStatus2", "Guest Tools Current")
                    .prop("res-vm-5", "summary|guest|toolsRunningStatus", "Guest Tools Not Running");
            l.r.run();
            String w = "vSphere World";
            chk(l, w, WORLD, V + "Worst 10 Percent Cluster Performance", 60.0);
            chk(l, w, WORLD, V + "Worst Cluster Performance", 60.0);
            chk(l, w, WORLD, V + "Worst 10 Percent Cluster Risk", 50.0);
            chk(l, w, WORLD, V + "Worst Cluster Risk", 50.0);
            chk(l, w, WORLD, V + "Highest 10 Percent Cluster Utilization", 40.0);
            chk(l, w, WORLD, V + "Highest Cluster Utilization", 40.0);
            chk(l, w, WORLD, V + "Availability|Worst Cluster Operational Availability", 91.67);
            chk(l, w, WORLD, V + "Availability|ESXi Hosts Operational Availability", 93.75);
            chk(l, w, WORLD, V + "Network|Total Network Speed Degradation", 1.0);
            // the sum over the vCenters whose count is at most 15 minutes old: 7 + 5, not the 20-minute-old 3
            chk(l, w, WORLD, V + "Number of Port Groups", 12.0);
            chk(l, w, WORLD, V + "Number of VM without Tools", 1.0);
            chk(l, w, WORLD, V + "Number of VMware Tools down", 1.0);
            // both clusters have KPI samples in the window and no check yet: the counts wait for the check
            T.check(l.st(0, WORLD, V + "Cluster Delta Close Count") == null, "the counts wait for the periodic check");
        }
    }

    static void delta(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            PeriodicWindow w = PeriodicWindow.at(NOW, 0, 0, 24);
            // both clusters were checked for this window (a sample at or after its end), the others have no KPI
            long ageSinceEnd = NOW - w.endMs - 60_000;
            l.d.stat(C1, StatKeys.CLOSE_FOR_BUSINESS, 0, ageSinceEnd).stat(C2, StatKeys.CLOSE_FOR_BUSINESS, 1, ageSinceEnd);
            l.d.hist(C1, StatKeys.CLOSE_FOR_BUSINESS, 0.0, 0.0).hist(C2, StatKeys.CLOSE_FOR_BUSINESS, 1.0);
            l.d.splitAtMs = w.endMs;
            l.d.histLate(C1, StatKeys.CLOSE_FOR_BUSINESS, 0.0, 1.0).histLate(C2, StatKeys.CLOSE_FOR_BUSINESS, 0.0);
            l.r.run();
            chk(l, "vSphere World", WORLD, V + "Cluster Delta Close Count", 1.0);
            chk(l, "vSphere World", WORLD, V + "Cluster Delta Open Count", 1.0);
            // the next collection, before Operations has made the stored counts readable (a minute or four of lag, L2): the
            // push was clean and is remembered, so the same counts are not sent again
            l.r.run();
            T.check(l.st(1, WORLD, StatKeys.DELTA_CLOSE) == null && l.st(1, WORLD, StatKeys.DELTA_OPEN) == null,
                    "not sent again while the read-back has not caught up");
            // the counts were stored just now (a sample at or after the window's end): not again this window
            int histories = l.d.requests(P4Data.HISTORY);
            l.d.stat(WORLD, StatKeys.DELTA_CLOSE, 1, 1000).stat(WORLD, StatKeys.DELTA_OPEN, 1, 1000);
            l.r.run();
            T.check(l.st(2, WORLD, StatKeys.DELTA_CLOSE) == null, "published once per window");
            T.eq(histories, l.d.requests(P4Data.HISTORY), "and not read for");
            // a cluster still due holds the counts back
            Lab.class.getName();
        }
        try (Lab l = new Lab(fake)) {
            PeriodicWindow w = PeriodicWindow.at(NOW, 0, 0, 24);
            // Prod has KPI samples in the window and no check yet: it is still due, so no count is taken
            l.d.stat(C1, StatKeys.CLUSTER_PERFORMANCE_SCORE, 80, 3_600_000);
            l.d.splitAtMs = w.endMs;
            l.r.run();
            T.check(l.st(0, WORLD, StatKeys.DELTA_CLOSE) == null && l.st(0, WORLD, StatKeys.DELTA_OPEN) == null,
                    "a cluster still due: no count");
        }
        try (Lab l = new Lab(fake)) {
            PeriodicWindow w = PeriodicWindow.at(NOW, 0, 0, 24);
            l.d.stat(C1, StatKeys.CLOSE_FOR_BUSINESS, 0, NOW - w.endMs - 60_000);
            l.d.failOnKey.add("\"begin\"");
            l.d.splitAtMs = w.endMs;
            // a history read that fails: no count, counted, tried again next cycle
            l.d.failPaths.add(P4Data.HISTORY);
            l.r.run();
            T.check(l.st(0, WORLD, StatKeys.DELTA_CLOSE) == null, "no count from half the history");
            T.contains(String.valueOf(l.r.health(0, "Collector:world|Causes")), "F-OPS-HISTORY", "the failed read is named");
        }
    }

    // ------------------------------------------------------------------ the periodic check

    static void periodic(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            PeriodicWindow w = PeriodicWindow.at(NOW, 0, 0, 24);
            T.eq(java.time.Instant.parse("2023-11-14T00:00:00Z").toEpochMilli(), w.endMs, "the window ends at 00:00 UTC today");
            for (String id : new String[] {C1, C2}) {
                for (String key : StatKeys.CLUSTER_SCORES) l.d.stat(id, key, 50, 3 * 3_600_000L);
            }
            l.d.hist(C1, StatKeys.CLUSTER_PERFORMANCE_SCORE, 95.0).hist(C1, StatKeys.CLUSTER_RISK_SCORE, 10.0, 20.0)
                    .hist(C1, StatKeys.CLUSTER_CONSUMPTION_SCORE, 40.0);
            l.d.hist(C2, StatKeys.CLUSTER_PERFORMANCE_SCORE, 20.0).hist(C2, StatKeys.CLUSTER_RISK_SCORE, 80.0)
                    .hist(C2, StatKeys.CLUSTER_CONSUMPTION_SCORE, 40.0);
            l.r.run();
            chk(l, CL, C1, V + "Periodic Cluster Check|Worst Cluster Performance", 95.0);
            chk(l, CL, C1, V + "Periodic Cluster Check|Worst Cluster Risk", 20.0);
            chk(l, CL, C1, V + "Periodic Cluster Check|Worst Cluster Consumption", 40.0);
            chk(l, CL, C1, V + "Periodic Cluster Check|Close for Business", 0.0);
            chk(l, CL, C2, V + "Periodic Cluster Check|Close for Business", 1.0);
            T.eq(20.0, l.st(0, C2, V + "Periodic Cluster Check|Worst Cluster Performance"), "Edge: performance 20 is red");
            T.check(l.st(0, C3, V + "Periodic Cluster Check|Close for Business") == null, "no KPI data: not due");
            T.contains(l.d.historyBodies.get(0), "\"intervalQuantifier\":5", "five-minute averages");
            T.contains(l.d.historyBodies.get(0), "\"begin\":" + w.beginMs + ",\"end\":" + w.endMs, "the window asked for");

            // the next cycle: the check was stored (its samples are at or after the window's end): nothing again
            for (String id : new String[] {C1, C2}) {
                for (String key : StatKeys.PERIODIC_PUBLISHED) l.d.stat(id, key, 1, 1000);
            }
            int before = scoreHistories(l);
            l.r.clock.addAndGet(300_000);
            l.d.nowMs = l.r.clock.get();
            l.r.run();
            T.check(l.st(1, C1, V + "Periodic Cluster Check|Close for Business") == null, "not twice for one window");
            T.eq(before, scoreHistories(l), "no history of the KPIs is read for a window that was checked");

            // a restart or a second collector: no memory is needed, the stored samples say it was done
            try (Lab again = new Lab(fake)) {
                again.r.ops.data = l.d;
                l.d.nowMs = again.r.clock.get();
                again.r.run();
                T.check(again.st(0, C1, V + "Periodic Cluster Check|Close for Business") == null, "no double publication after a restart");
            }

            // window rollover: a day later the stored samples are older than the new window's end, and the KPIs have
            // samples in it: the check is due again; the cycle count in between does not matter
            l.r.clock.addAndGet(24 * 3_600_000L);
            long now2 = l.r.clock.get();
            l.d.nowMs = now2;
            for (String id : new String[] {C1, C2}) {
                for (String key : StatKeys.PERIODIC_PUBLISHED) l.d.stat(id, key, 1, 25 * 3_600_000L);
                for (String key : StatKeys.CLUSTER_SCORES) l.d.stat(id, key, 50, 3_600_000L);
            }
            l.r.run();
            int last = l.r.ops.pushedStats.size() - 1;
            T.check(l.st(last, C1, V + "Periodic Cluster Check|Close for Business") != null, "due again after the rollover");
            T.eq(java.time.Instant.parse("2023-11-15T00:00:00Z").toEpochMilli(),
                    PeriodicWindow.at(now2, 0, 0, 24).endMs, "the next window");
        }
        // a failed history read leaves the cluster due, and sends nothing; a failed timestamp read calculates nothing
        try (Lab l = new Lab(fake)) {
            for (String key : StatKeys.CLUSTER_SCORES) l.d.stat(C1, key, 50, 3_600_000L);
            l.d.hist(C1, StatKeys.CLUSTER_PERFORMANCE_SCORE, 95.0);
            l.d.failPaths.add(P4Data.HISTORY);
            l.r.run();
            T.check(l.st(0, C1, V + "Periodic Cluster Check|Worst Cluster Performance") == null, "a failed history sends nothing");
            T.contains(String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "F-OPS-HISTORY", "and is counted");
            l.d.failPaths.clear();
            l.r.run();
            T.eq(95.0, l.st(1, C1, V + "Periodic Cluster Check|Worst Cluster Performance"), "tried again, and published");
            T.check(l.st(1, C1, V + "Periodic Cluster Check|Close for Business") == null, "Close for Business needs all three");
        }
        try (Lab l = new Lab(fake)) {
            for (String key : StatKeys.CLUSTER_SCORES) l.d.stat(C1, key, 50, 3_600_000L);
            l.d.failOnKey.add(StatKeys.CLOSE_FOR_BUSINESS);
            l.r.run();
            T.check(l.st(0, C1, V + "Periodic Cluster Check|Worst Cluster Performance") == null, "no timestamps, no calculation");
            T.eq(0, scoreHistories(l), "and no history read");
        }
    }

    // ------------------------------------------------------------------ business applications

    static void business(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            for (String[] o : new String[][] {{"Tier", "t-llm"}, {"Tier", "t-web"}, {"BusinessService", "b-ai"},
                {"BusinessService", "b-bank"}, {"Enterprise", "e-1"}}) {
                l.r.ops.add(o[0], o[1], null, null);
            }
            l.d.child("t-llm", "VirtualMachine", "res-vm-1").child("t-llm", "VirtualMachine", "res-vm-2")
                    .child("t-web", "VirtualMachine", "res-vm-3");
            l.d.child("b-ai", "Tier", "t-llm").child("b-ai", "Tier", "t-web").child("b-ai", "K8S-Namespace", "ns-ai")
                    .child("b-bank", "Tier", "t-llm");
            l.d.child("e-1", "BusinessService", "b-ai").child("e-1", "BusinessService", "b-bank");
            l.d.stat("res-vm-1", StatKeys.VM_GUEST_KPI, 80).stat("res-vm-2", StatKeys.VM_GUEST_KPI, 60);
            l.d.stat("ns-ai", "Performance|Performance", 90);
            l.r.run();
            chk(l, "Tier", "t-llm", V + "Business Application|Tier Performance", 70.0);
            T.check(l.st(0, "t-web", V + "Business Application|Tier Performance") == null, "a tier with no VM KPI has no value");
            chk(l, "BusinessService", "b-ai", V + "Business Application|Business Application Performance", 90.0);
            T.eq(70.0, l.st(0, "b-bank", V + "Business Application|Business Application Performance"), "the lowest tier");
            chk(l, "Enterprise", "e-1", V + "Business Application|Business Applications Performance", 80.0);
            T.eq("OK", l.r.health(0, "Collector:business_applications|Status"), "all read");
        }
        // a relationship that cannot be read withholds that object and the ones above it, not every other
        try (Lab l = new Lab(fake)) {
            for (String[] o : new String[][] {{"Tier", "t-llm"}, {"Tier", "t-web"}, {"BusinessService", "b-ai"},
                {"BusinessService", "b-bank"}, {"Enterprise", "e-1"}}) {
                l.r.ops.add(o[0], o[1], null, null);
            }
            l.d.child("t-llm", "VirtualMachine", "res-vm-1").child("t-web", "VirtualMachine", "res-vm-3");
            l.d.child("b-ai", "Tier", "t-llm").child("b-bank", "Tier", "t-web");
            l.d.child("e-1", "BusinessService", "b-ai").child("e-1", "BusinessService", "b-bank");
            l.d.stat("res-vm-1", StatKeys.VM_GUEST_KPI, 80).stat("res-vm-3", StatKeys.VM_GUEST_KPI, 50);
            l.d.failRelationshipsOf.add("t-web");
            l.r.run();
            T.eq(80.0, l.st(0, "t-llm", V + "Business Application|Tier Performance"), "the tier that was read");
            T.eq(80.0, l.st(0, "b-ai", V + "Business Application|Business Application Performance"), "its application");
            T.check(l.st(0, "t-web", V + "Business Application|Tier Performance") == null, "the tier that was not");
            T.check(l.st(0, "b-bank", V + "Business Application|Business Application Performance") == null, "its application");
            T.check(l.st(0, "e-1", V + "Business Application|Business Applications Performance") == null,
                    "and the enterprise over it: never a mean over some");
            T.contains(String.valueOf(l.r.health(0, "Collector:business_applications|Causes")), "F-OPS-REL", "counted");
            T.eq("PARTIAL", l.r.health(0, "Collector:business_applications|Status"), "partial");
        }
    }

    // ------------------------------------------------------------------ failed reads

    static void failedReads(FakeVc fake) throws Exception {
        // the VM statistics cannot be read: the VM keys and everything computed from VMs are withheld, the rest is not
        try (Lab l = new Lab(fake)) {
            l.d.failOnKey.add(StatKeys.VM_READY);
            l.r.run();
            T.check(l.st(0, "res-vm-1", V + "VM Performance|VM Performance Score") == null, "no VM score");
            T.check(l.st(0, "res-vm-1", V + "VM Guest OS KPI") == null, "no VM Guest OS KPI");
            T.check(l.st(0, "res-vm-1", V + "VM Performance|Memory Zip Swap") == null, "no zip swap");
            T.eq(1000.0, l.st(0, "res-vm-1", V + "Configuration|Share per vCPU"), "the shares are another read");
            T.check(l.st(0, C1, V + "Cluster Performance|Worst VM CPU Ready") == null, "no cluster contention row");
            T.check(l.st(0, C1, V + "Cluster Performance|Cluster Performance Score") == null,
                    "and no KPI from the bad packets alone");
            T.eq(0.5, l.st(0, C1, V + "Cluster Performance|Highest ESXi Bad Network Packets"), "the host row is known");
            T.check(l.st(0, H1, V + "ESXi Performance|Worst VM CPU Ready") == null, "no host contention row");
            T.check(l.st(0, H1, V + "ESXi Performance|ESXi Performance Score") == null, "no host KPI");
            T.eq(60.0, l.st(0, C1, V + "Cluster Risk|Highest ESXi CPU Thread Utilization"), "risk reads no VM statistic");
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") != null, "and is scored");
            T.contains(String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "F-OPS-STATS-vm", "named");
            T.eq("PARTIAL", l.r.health(0, "Collector:kpi|Status"), "partial");
            T.check((Double) l.r.health(0, "Values Withheld in Last Cycle") > 10, "the values are counted");
            T.check((Double) l.r.health(0, "Reads Failed in Last Cycle") >= 1, "the read is counted");
        }
        // the host statistics: risk, consumption, the packet row, availability and the figures that stand on them
        try (Lab l = new Lab(fake)) {
            l.d.failOnKey.add(StatKeys.HOST_POWERED_ON);
            l.r.run();
            for (String key : new String[] {"Cluster Risk|Cluster Risk Score", "Cluster Risk|Highest ESXi CPU Thread Utilization",
                "Cluster Consumption|Cluster Consumption Score", "Cluster Performance|Highest ESXi Bad Network Packets",
                "Cluster Performance|Cluster Performance Score", "Availability|Cluster Operational Availability"}) {
                T.check(l.st(0, C1, V + key) == null, key + " is withheld");
            }
            T.check(l.st(0, H1, V + "Availability|ESXi Host Operational Availability") == null, "no host availability, not 0 or 25");
            T.check(l.st(0, VC, V + "Availability|Worst Cluster Operational Availability") == null, "no worst cluster");
            T.eq(10.0, l.st(0, C1, V + "Cluster Performance|Worst VM CPU Ready"), "the VM rows are another read");
            T.check(l.st(0, C1, V + "Cluster Performance|Cluster Performance Score") == null, "but no KPI over some rows");
            T.contains(String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "F-OPS-STATS-host", "named");
        }
        // the cluster statistics: no usable capacity and no host count: consumption and availability are withheld
        try (Lab l = new Lab(fake)) {
            l.d.failOnKey.add(StatKeys.CLUSTER_HOST_COUNT);
            l.r.run();
            T.check(l.st(0, C1, V + "Cluster Consumption|Cluster Consumption Score") == null, "no consumption");
            T.check(l.st(0, C1, V + "Availability|Cluster Operational Availability") == null,
                    "no availability: a missing host count would make it look lower, and wrong");
            T.check(l.st(0, VC, V + "Availability|Worst Cluster Operational Availability") == null, "no worst cluster");
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") != null, "risk needs no cluster statistic");
            T.eq(100.0, l.st(0, H1, V + "Availability|ESXi Host Operational Availability"), "hosts are another read");
        }
        // the NIC speeds
        try (Lab l = new Lab(fake)) {
            l.d.failOnKey.add("net:vmnic0|speed");
            l.r.run();
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "no host speed");
            T.check(l.st(0, DC1, V + "Network|Total Network Speed Degradation") == null, "no Datacenter count, not 0");
            T.check(l.st(0, DC2, V + "Network|Total Network Speed Degradation") == null, "none for either");
            T.eq(100.0, l.st(0, H1, V + "Availability|ESXi Host Operational Availability"), "availability is unaffected");
        }
        // the host properties (cores, power, maintenance): availability and the CPU rows are withheld
        try (Lab l = new Lab(fake)) {
            l.d.failOnKey.add(StatKeys.HOST_CORES);
            l.r.run();
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") == null, "no risk without the cores");
            T.check(l.st(0, C1, V + "Cluster Consumption|Cluster Consumption Score") == null, "no consumption without the hosts' state");
            T.check(l.st(0, H1, V + "Availability|ESXi Host Operational Availability") == null, "not computed as 0");
            T.contains(String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "F-OPS-PROPS-host", "named");
        }
        // a malformed answer is a failed read, not an empty one
        try (Lab l = new Lab(fake)) {
            l.d.garbage = "{\"values\":[{\"resourceId\":\"res-vm-1\",\"stat-list\":{\"stat\":[{\"statKey\":{\"key\":\""
                    + StatKeys.VM_READY + "\"},\"data\":[null],\"timestamps\":[" + (NOW - 1000) + "]}]}}]}";
            l.r.run();
            T.check(l.st(0, "res-vm-1", V + "VM Performance|VM Performance Score") == null, "a sample that is not a number fails the read");
            T.contains(String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "F-OPS-STATS", "counted");
        }
        // a failed relationship read is not "no pools", and an unreadable pool list is not 0
        // (see failedMembership)
    }

    static SoapFx.Obj hostObj(Lab l, String moid) {
        for (SoapFx.Obj o : l.vc.byType.get("HostSystem")) if (o.moid.equals(moid)) return o;
        throw new AssertionError("no " + moid);
    }

    static void reviewFixes(FakeVc fake) throws Exception {
        // W1: hosts that once had a speed and have nothing fresh: the World count is 0 while the answer names them (Q16)
        try (Lab l = new Lab(fake)) {
            l.d.stat(H1, StatKeys.HOST_NETWORK_SPEED, 55, 20 * 60_000L);
            l.r.run();
            T.eq(0.0, l.st(0, WORLD, StatKeys.DC_NETWORK_SPEED), "a stored speed too old to use still means 'measured': 0");
        }
        try (Lab l = new Lab(fake)) {
            l.r.run();
            T.check(l.st(0, WORLD, StatKeys.DC_NETWORK_SPEED) == null, "nothing stored for any host: nothing");
        }
        // N3: a VM whose host vCenter would not read is placed by the host name Operations holds
        try (Lab l = new Lab(fake)) {
            for (SoapFx.Obj o : l.vc.byType.get("VirtualMachine")) {
                if (o.moid.equals("vm-1")) {
                    o.vals.remove("runtime.host");
                    o.missing("runtime.host");
                }
            }
            l.d.prop("res-vm-1", StatKeys.VM_PARENT_HOST, "esx-1");
            l.r.run();
            T.eq(10.0, l.st(0, C1, V + "Cluster Performance|Worst VM CPU Ready"), "vm-1 still counts in its cluster");
            T.eq(10.0, l.st(0, H1, V + "ESXi Performance|Worst VM CPU Ready"), "and on its host");
        }
        try (Lab l = new Lab(fake)) {
            for (SoapFx.Obj o : l.vc.byType.get("VirtualMachine")) {
                if (o.moid.equals("vm-1")) {
                    o.vals.remove("runtime.host");
                    o.missing("runtime.host");
                }
            }
            l.r.run(); // no host name either: the placement of every VM is in doubt, and nothing is invented
            T.check(l.st(0, C1, V + "Cluster Performance|Worst VM CPU Ready") == null, "an unplaceable VM withholds the VM-based rows");
            T.eq(100.0, l.st(0, H1, V + "Availability|ESXi Host Operational Availability"), "not the others");
            T.eq(1000.0, l.st(0, "res-vm-1", V + "Configuration|Share per vCPU"), "nor the VM's own figures");
        }
        // N5: only the share that needed the fallback read is withheld when that read fails
        try (Lab l = new Lab(fake)) {
            l.d.props.get("res-vm-2").put(StatKeys.VM_CPU_SHARES, "2000");
            l.d.stats.get("res-vm-2").remove(StatKeys.VM_CPU_SHARES);
            l.d.failLatestOn.add(StatKeys.VM_MEM_SHARES);
            l.r.run();
            T.eq(1000.0, l.st(0, "res-vm-2", V + "Configuration|Share per vCPU"), "the CPU shares came with the properties");
            T.check(l.st(0, "res-vm-2", V + "Configuration|Share per MB of Memory") == null, "the memory share needed the failed read");
            T.eq(1000.0, l.st(0, "res-vm-1", V + "Configuration|Share per vCPU"), "a VM that needed no fallback is unaffected");
        }
        // L6: the warning the original writes when no host has a NIC speed, counted and first
        try (Lab l = new Lab(fake)) {
            for (String h : new String[] {H1, H2}) {
                l.d.props.get(h).keySet().removeIf(k -> k.startsWith("net:"));
            }
            l.r.run();
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "no NIC speed for any host: none");
            T.eq("No NIC speeds for any host, so no Network Speed Degradation this cycle", l.r.health(0, "First Warning"),
                    "the original's text");
            T.eq(1.0, l.r.health(0, "Warnings in Last Cycle"), "counted once");
        }
        // DV-108: the NIC speeds are properties. Only the actual speed is held: the configured speed is opt-in, off in
        // the stock policy. That is no failure: no status, no cause, no warning and no withheld count change
        Object baseWithheld;
        try (Lab l = new Lab(fake)) {
            l.r.run();
            baseWithheld = l.r.health(0, "Values Withheld in Last Cycle");
        }
        try (Lab l = new Lab(fake)) {
            for (String h : new String[] {H1, H2}) {
                l.d.props.get(h).keySet().removeIf(k -> k.endsWith("|configuredSpeed"));
            }
            l.r.run();
            l.r.run();
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "no configured speed: no value, not 100");
            T.check(l.st(0, H2, V + "Network|Network Speed Degradation") == null, "and none for the other host");
            T.check(l.st(0, DC1, V + "Network|Total Network Speed Degradation") == null, "no Datacenter count, not 0");
            T.eq("OK", l.r.health(0, "Collector:kpi|Status"), "the opt-in property being off is not a partial collection");
            T.eq("None in the last cycle", String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "no cause");
            T.eq(0.0, l.r.health(0, "Warnings in Last Cycle"), "no warning");
            T.eq(baseWithheld, l.r.health(0, "Values Withheld in Last Cycle"), "nothing more counted as withheld than with the property on");
            int said = 0;
            for (String m : l.r.log.infos) if (m.contains("F-POLICY-NIC-CONFIGURED-SPEED") && m.contains("net|configuredSpeed")) said++;
            T.eq(1, said, "said once at INFO over two cycles, naming the property");
            for (String m : l.r.log.infos) T.check(!m.contains("enable that property in the active policy"), "no instruction to a policy it cannot see");
            T.check(l.r.log.warns.stream().noneMatch(m -> m.contains("configured")), "not a warning");
            T.eq(100.0, l.st(0, H1, V + "Availability|ESXi Host Operational Availability"), "nothing else is affected");
        }
        // the same, with the Ops list of clusters failing: the opt-in gap must not make the KPI collector FAILED
        try (Lab l = new Lab(fake)) {
            for (String h : new String[] {H1, H2}) {
                l.d.props.get(h).keySet().removeIf(k -> k.endsWith("|configuredSpeed"));
            }
            l.r.ops.listFails.put("ClusterComputeResource", new java.io.IOException("the list is not available"));
            l.r.run();
            T.eq("PARTIAL", l.r.health(0, "Collector:kpi|Status"), "two missing lists are PARTIAL, as they are without the policy gap");
            T.eq(100.0, l.st(0, H1, V + "Availability|ESXi Host Operational Availability"), "host figures still published");
        }
        // a host whose NIC list vCenter would not give, alone: that host and its Datacenter are withheld and counted
        try (Lab l = new Lab(fake)) {
            hostObj(l, "host-1").missing("config.network.pnic");
            l.r.run();
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "no figure for the host whose NICs are unknown");
            T.eq(100.0, l.st(0, H2, V + "Network|Network Speed Degradation"), "the other host is unaffected");
            T.check(l.st(0, DC1, V + "Network|Total Network Speed Degradation") == null, "its Datacenter is withheld, not 0");
            T.check((Double) l.r.health(0, "Values Withheld in Last Cycle") > (Double) baseWithheld, "counted");
        }
        // B1: the same unreadable NIC list on the stock policy (no configured speed anywhere): nothing was measured,
        // so no Datacenter publishes 0
        try (Lab l = new Lab(fake)) {
            for (String h : new String[] {H1, H2, H4}) {
                l.d.props.computeIfAbsent(h, k -> new java.util.LinkedHashMap<>()).keySet().removeIf(k -> k.endsWith("|configuredSpeed"));
            }
            l.nicProps("4", "vmnic0", "1000", null);
            hostObj(l, "host-1").missing("config.network.pnic");
            l.r.run();
            for (String dc : new String[] {DC1, DC2}) {
                T.check(l.st(0, dc, V + "Network|Total Network Speed Degradation") == null, dc + ": nothing measured, not 0");
            }
            for (String h : new String[] {H1, H2, H4}) {
                T.check(l.st(0, h, V + "Network|Network Speed Degradation") == null, h + ": no value");
            }
            T.eq("OK", l.r.health(0, "Collector:kpi|Status"), "the opt-in property is still no status");
            T.check(l.r.log.infos.stream().anyMatch(m -> m.contains("F-POLICY-NIC-CONFIGURED-SPEED")), "and still said");
        }
        // the Datacenter count follows the host figure's eligibility: an unknown connection state makes the count unknown
        try (Lab l = new Lab(fake)) {
            hostObj(l, "host-1").missing("runtime.connectionState");
            l.r.run();
            T.check(l.st(0, DC1, V + "Network|Total Network Speed Degradation") == null,
                    "a member whose connection state is unknown: no count, not a misleading 0");
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "and no host figure");
            T.eq(100.0, l.st(0, H2, V + "Network|Network Speed Degradation"), "the other host is unaffected");
        }
        // a host that is known to be disconnected is not part of the count, even when its NIC list cannot be read
        try (Lab l = new Lab(fake)) {
            hostObj(l, "host-5").missing("config.network.pnic");
            l.r.run();
            T.eq(0.0, l.st(0, DC2, V + "Network|Total Network Speed Degradation"), "DC2 is counted without the disconnected host");
            T.eq(1.0, l.st(0, DC1, V + "Network|Total Network Speed Degradation"), "DC1 unchanged");
        }
        // a connected host's frozen property values count; a host vCenter says is not connected does not
        try (Lab l = new Lab(fake)) {
            l.nicProps("5", "vmnic0", "1000", "10000");
            l.r.run();
            T.check(l.st(0, "res-host-5", V + "Network|Network Speed Degradation") == null, "a disconnected host has no figure");
            T.eq(1.0, l.st(0, DC1, V + "Network|Total Network Speed Degradation"), "and is not in a Datacenter count");
            T.eq(0.0, l.st(0, DC2, V + "Network|Total Network Speed Degradation"), "DC2: host-4 has no NICs, host-5 is not counted");
        }
        // text that is not a number, a zero and an absent value are no value for that NIC
        try (Lab l = new Lab(fake)) {
            l.nicProps("1", "vmnic0", "1000", "n/a");
            l.nicProps("1", "vmnic1", "10000", "0");
            l.nicProps("2", "vmnic0", "", "10000");
            l.nicProps("2", "vmnic1", "10000", "10000");
            l.r.run();
            T.check(l.st(0, H1, V + "Network|Network Speed Degradation") == null, "no NIC with a usable pair: no value");
            T.eq(100.0, l.st(0, H2, V + "Network|Network Speed Degradation"), "the NIC with a blank speed is left out");
            T.eq(0.0, l.st(0, DC1, V + "Network|Total Network Speed Degradation"), "one host has a value and is not degraded");
            T.eq("None in the last cycle", String.valueOf(l.r.health(0, "First Failure")), "none of it is a failed read");
        }
        // a disconnected NIC (no speed) beside a connected one that runs below its configured speed
        try (Lab l = new Lab(fake)) {
            l.nicProps("1", "vmnic0", "1000", "10000");
            l.nicProps("1", "vmnic1", null, "10000");
            l.nicProps("2", "vmnic0", "10000", "10000");
            l.nicProps("2", "vmnic1", "0", "10000");
            // the World count reads back what the hosts' figures were stored as
            l.d.stat(H1, V + "Network|Network Speed Degradation", 10.0).stat(H2, V + "Network|Network Speed Degradation", 100.0);
            l.r.run();
            T.eq(10.0, l.st(0, H1, V + "Network|Network Speed Degradation"), "only the NIC that has a speed counts");
            T.eq(100.0, l.st(0, H2, V + "Network|Network Speed Degradation"), "a zero speed is left out");
            T.eq(1.0, l.st(0, DC1, V + "Network|Total Network Speed Degradation"), "one degraded host");
            T.eq(1.0, l.st(0, WORLD, V + "Network|Total Network Speed Degradation"), "and the World count");
        }
        // W3: a failed World read marks the lists of clusters and hosts for a new read next cycle
        try (Lab l = new Lab(fake)) {
            l.d.failOnKey.add(StatKeys.CLUSTER_PERFORMANCE_SCORE);
            l.r.run();
            int hosts = PropertiesTest.listReads(l.r, "HostSystem");
            int clusters = PropertiesTest.listReads(l.r, "ClusterComputeResource");
            l.d.failOnKey.clear();
            l.r.run();
            T.eq(hosts + 1, PropertiesTest.listReads(l.r, "HostSystem"), "the host list is read again");
            T.eq(clusters + 1, PropertiesTest.listReads(l.r, "ClusterComputeResource"), "and the cluster list");
            l.r.run();
            T.eq(hosts + 1, PropertiesTest.listReads(l.r, "HostSystem"), "once, not every cycle");
        }
    }

    static void failedMembership(FakeVc fake) throws Exception {
        // the resource pools cannot be read: no cluster gets a count, not 0
        try (Lab l = new Lab(fake)) {
            l.vc.forcedType.put("ResourcePool", FakeVcenter.serverFault("pools refused"));
            l.r.run();
            for (String c : new String[] {C1, C2, C3}) {
                T.check(l.st(0, c, V + "Resource Pools|Number of Resource Pools") == null, c + ": no pool count");
                T.check(l.st(0, c, V + "Resource Pools|Cascading Resource Pools") == null, c + ": no cascading count");
            }
            T.contains(String.valueOf(l.r.health(0, "Collector:cluster|Causes")), "F-SOAP-R-RP", "named");
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") != null, "the rest of the cluster is fine");
        }
        // the Datacenters cannot be read: no port group count, no Datacenter count
        try (Lab l = new Lab(fake)) {
            l.vc.forcedType.put("Datacenter", FakeVcenter.serverFault("datacenters refused"));
            l.r.run();
            T.check(l.st(0, VC, V + "Number of Port Groups") == null, "no port groups");
            T.check(l.st(0, DC1, V + "Network|Total Network Speed Degradation") == null, "no Datacenter count");
            T.eq(PyRound.round2(275.0 / 3), l.st(0, VC, V + "Availability|Worst Cluster Operational Availability"), "the worst cluster is unaffected");
            T.eq("FAILED", l.r.health(0, "Collector:datacenter|Status"), "the Datacenter collector failed");
        }
        // one Datacenter's distributed port groups cannot be read: the count is withheld, the other Datacenter's is not
        try (Lab l = new Lab(fake)) {
            l.vc.forcedType.put("DistributedVirtualPortgroup", FakeVcenter.serverFault("port groups refused"));
            l.r.run();
            T.check(l.st(0, VC, V + "Number of Port Groups") == null, "no port groups from half the answer");
            T.eq(1.0, l.st(0, DC1, V + "Network|Total Network Speed Degradation"), "the NIC count needs no port group");
        }
        // the VM list cannot be read: no VM figure, and no cluster or host figure that needs the VMs on a host
        try (Lab l = new Lab(fake)) {
            l.vc.forcedType.put("VirtualMachine", FakeVcenter.serverFault("vms refused"));
            l.r.run();
            T.check(l.st(0, "res-vm-1", V + "Configuration|Share per vCPU") == null, "no shares");
            T.check(l.st(0, C1, V + "Cluster Performance|Worst VM CPU Ready") == null, "no contention row");
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") == null, "no risk: the monster rows need the VMs");
            T.eq(100.0, l.st(0, H1, V + "Availability|ESXi Host Operational Availability"), "availability does not");
            T.eq(0.5, l.st(0, C1, V + "Cluster Performance|Highest ESXi Bad Network Packets"), "nor the packet row");
        }
        // the host list cannot be read
        try (Lab l = new Lab(fake)) {
            l.vc.forcedType.put("HostSystem", FakeVcenter.serverFault("hosts refused"));
            l.r.run();
            T.check(l.st(0, C1, V + "Availability|Cluster Operational Availability") == null, "no cluster availability");
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") == null, "no risk");
            T.check(l.st(0, VC, V + "Number of Port Groups") == null, "no port groups");
            T.eq(10.0, l.st(0, "res-vm-1", V + "VM Performance|VM Performance Score") == null ? -1.0 : 10.0, "VM scores need no host");
        }
        // a host with no Operations resource: no keys for it, the cluster counts it as missing (0 %)
        try (Lab l = new Lab(fake)) {
            l.r.ops.resources.get("HostSystem").removeIf(x -> x[1].equals("host-2"));
            l.r.run();
            T.check(l.r.ops.pushedStats.get(0).get(H2) == null, "no keys for a host Operations does not know");
            // host-2 is not found, so the cluster pads it with 0 of the 3 counted: (100 + 75 + 0) / 3
            T.eq(PyRound.round2(175.0 / 3), l.st(0, C1, V + "Availability|Cluster Operational Availability"),
                    "a counted host that is not found counts as 0");
        }
    }

    static void staleAndEmpty(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            // a sample older than two cycles is no data (a host that stopped reporting), not its old value
            l.d.stat("res-vm-1", StatKeys.VM_READY, 40, 11 * 60_000L);
            l.r.run();
            T.eq(4.0 + 0, l.st(0, H2, V + "ESXi Performance|Worst VM CPU Ready"), "another host's VM");
            T.eq(2.0, l.st(0, H1, V + "ESXi Performance|Worst VM CPU Ready"), "the stale sample is not the worst: vm-2's 2");
        }
        try (Lab l = new Lab(fake)) {
            // upstream uses a latest statistic as it reads it, so a -1 (no sample) is a reading and the worst VM is -1 (Q13)
            l.d.stat("res-vm-3", StatKeys.VM_READY, -1);
            l.r.run();
            T.eq(-1.0, l.st(0, H2, V + "ESXi Performance|Worst VM CPU Ready"), "-1 is scored as read while Q13 is on");
        }
        try (Lab l = new Lab(fake)) {
            // an answer that names resources with nothing to report is an answer: no row, and no failure
            l.d.emptyEntries = true;
            l.r.run();
            T.eq("None in the last cycle", String.valueOf(l.r.health(0, "First Failure")), "an empty entry is not a failure");
            T.eq(10.0, l.st(0, C1, V + "Cluster Performance|Worst VM CPU Ready"), "same values");
            T.check(l.st(0, "res-vm-4", V + "VM Performance|VM Performance Score") == null, "a VM with no sample has no score");
        }
    }

    static void deadline(FakeVc fake) throws Exception {
        try (Lab l = new Lab(fake)) {
            // the clock jumps past the deadline while the share statistics are read: what is not read is not sent,
            // and nothing is made up for it
            l.d.onFirstLatest = () -> l.r.clock.addAndGet(10 * 60_000L);
            CycleEngine.Result res = l.r.run();
            T.check(res.failure == null, "the cycle does not fail");
            T.check(res.summary.deadlineHit, "the deadline is reported");
            T.eq(1000.0, l.st(0, "res-vm-1", V + "Configuration|Share per vCPU"), "what was read before it is sent");
            for (String key : new String[] {"VM Performance|VM Performance Score", "VM Guest OS KPI"}) {
                T.check(l.st(0, "res-vm-1", V + key) == null, key + " is not sent");
            }
            T.check(l.st(0, C1, V + "Cluster Risk|Cluster Risk Score") == null, "no cluster figure");
            T.check(l.st(0, H1, V + "Availability|ESXi Host Operational Availability") == null, "no host figure, and not a 0");
            T.check(l.st(0, WORLD, V + "Number of Port Groups") == null, "no World figure");
            T.contains(String.valueOf(l.r.health(0, "Collector:kpi|Causes")), "F-DEADLINE", "named");
            T.eq("NOT RUN", l.r.health(0, "Collector:world|Status"), "the World collector did not run");
            T.eq("NOT RUN", l.r.health(0, "Collector:business_applications|Status"), "nor the Business Applications");
        }
    }
}
