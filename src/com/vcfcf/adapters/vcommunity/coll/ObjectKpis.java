package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.kpi.Availability;
import com.vcfcf.adapters.vcommunity.kpi.ClusterConsumption;
import com.vcfcf.adapters.vcommunity.kpi.ClusterPerformance;
import com.vcfcf.adapters.vcommunity.kpi.ClusterRisk;
import com.vcfcf.adapters.vcommunity.kpi.EsxiPerformance;
import com.vcfcf.adapters.vcommunity.kpi.HostInv;
import com.vcfcf.adapters.vcommunity.kpi.KpiMetric;
import com.vcfcf.adapters.vcommunity.kpi.KpiScorer;
import com.vcfcf.adapters.vcommunity.kpi.KpiTable;
import com.vcfcf.adapters.vcommunity.kpi.NetworkSpeed;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.kpi.VmContention;
import com.vcfcf.adapters.vcommunity.kpi.VmKpis;
import com.vcfcf.adapters.vcommunity.ops.OpsReads;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The figures of the clusters, hosts, virtual machines, Datacenters and the vCenter that are computed from what
 * VCF Operations already holds (design 7, 4.3 to 4.8; map D1 to D8 and D12; rows R70, R71, R153 to R197, R212,
 * R213, R214, R218, R219, R221). One read of each data set a cycle, shared by every figure that needs it.
 *
 * <p>A figure is sent only when every input it depends on was read (design 5.1 rule 4). An input that was
 * read and has no sample is a reading and leaves the row out, as in the original; an input whose read failed
 * withholds the figure and counts it ({@link DerivedContext#withheld}); a KPI whose rows are not all known is
 * withheld, never computed from the rows that happen to be.
 */
final class ObjectKpis {

    private static final String G = StatKeys.P;

    private final DerivedContext x;
    private final Membership m;
    private final Supplier<OpsResourceMap> datacenterMap;
    private final String c;

    ObjectKpis(DerivedContext x, Membership m, Supplier<OpsResourceMap> datacenterMap, String collector) {
        this.x = x;
        this.m = m;
        this.datacenterMap = datacenterMap;
        this.c = collector;
    }

    // ------------------------------------------------------------------ per-object data

    private static final class VmF {
        final String moref;
        final String rid;
        boolean powered;
        boolean propsFailed;
        boolean statsFailed;
        /** The share statistics (the fallback for a missing share property) could not be read. */
        boolean sharesFailed;
        /** The host MoRef named by the VM's Operations property {@code summary|parentHost}, for a VM whose vCenter host was unreadable. */
        String hostName;
        Double vcpus;
        Double memoryMb;
        Double cpuShares;
        Double memShares;
        /** The four contention statistics (NaN: none); null until read. */
        double[] contention;

        VmF(String moref, String rid) {
            this.moref = moref;
            this.rid = rid;
        }
    }

    private static final class HostF {
        final String moref;
        final String rid;
        final Membership.Host h;
        Map<String, Double> stats = Collections.emptyMap();
        Map<String, String> props = Collections.emptyMap();
        Set<String> nics; // device names; null when the host's NIC list was not read
        NetworkSpeed.Speeds nic;
        boolean answered; // an answer named it (as upstream tests it, whether or not a sample survived the age limit)
        boolean statsFailed;
        boolean propsFailed;
        boolean nicFailed;
        HostInv inv;
        Double availability;
        Double speed;

        HostF(String moref, String rid, Membership.Host h) {
            this.moref = moref;
            this.rid = rid;
            this.h = h;
        }

        boolean ok() {
            return !statsFailed && !propsFailed;
        }
    }

    private static final class ClusterF {
        final String moref;
        final String rid;
        Map<String, Double> stats = Collections.emptyMap();
        Map<String, String> props = Collections.emptyMap();
        boolean statsFailed;
        boolean propsFailed;
        List<HostF> hosts = new ArrayList<>();

        ClusterF(String moref, String rid) {
            this.moref = moref;
            this.rid = rid;
        }
    }

    private final Map<String, VmF> vmsByRid = new LinkedHashMap<>();
    private final Map<String, List<VmF>> poweredOnByHost = new HashMap<>();
    /** Hosts with a VM whose power state or size could not be read, and hosts with a powered-on VM whose statistics could not. */
    private final Set<String> hostsWithUnknownVmProps = new HashSet<>();
    private final Set<String> hostsWithUnknownVmStats = new HashSet<>();
    private boolean vmListOk;
    /** False when a VM could not be placed on a host, so no host or cluster figure over VMs can be trusted. */
    private boolean placementOk = true;
    private final Map<String, HostF> hostsByMoref = new LinkedHashMap<>();
    private final Map<String, ClusterF> clustersByMoref = new LinkedHashMap<>();

    // ------------------------------------------------------------------ the run

    void run() throws InterruptedException {
        OpsResourceMap vmMap = x.in.opsMap.apply(VmCollector.KIND);
        OpsResourceMap hostMap = x.in.opsMap.apply(HostCollector.KIND);
        OpsResourceMap clusterMap = x.in.opsMap.apply(ClusterCollector.KIND);
        vms(vmMap);
        if (x.stop()) return;
        hosts(hostMap);
        if (x.stop()) return;
        clusters(clusterMap);
        if (x.stop()) return;
        publishHosts();
        publishClusters();
        datacenters();
        vcenter();
    }

    // ------------------------------------------------------------------ virtual machines

    private static Double num(String s) {
        if (s == null) return null;
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void vms(OpsResourceMap vmMap) throws InterruptedException {
        if (!m.vmListRead()) {
            x.inputMissing(c, "the list of virtual machines (R-VM)");
            return;
        }
        if (vmMap == null) {
            x.inputMissing(c, "VCF Operations' list of virtual machines");
            return;
        }
        List<String> mor = new ArrayList<>(m.vmsAll());
        Collections.sort(mor);
        List<String> rids = new ArrayList<>();
        for (String mo : mor) {
            String rid = vmMap.resourceId(mo);
            if (rid != null) {
                vmsByRid.put(rid, new VmF(mo, rid));
                rids.add(rid);
            }
        }
        vmListOk = true;
        OpsReads.Report props = x.reads.latestProperties(rids, StatKeys.VM_PROPS, (id, p) -> {
            VmF v = vmsByRid.get(id);
            if (v == null) return;
            v.powered = StatKeys.POWERED_ON.equals(p.get(StatKeys.VM_POWER));
            v.vcpus = num(p.get(StatKeys.VM_VCPUS));
            Double kb = num(p.get(StatKeys.VM_MEMORY_KB));
            v.memoryMb = kb == null ? null : kb / 1024.0;
            v.cpuShares = num(p.get(StatKeys.VM_CPU_SHARES));
            v.memShares = num(p.get(StatKeys.VM_MEM_SHARES));
            v.hostName = p.get(StatKeys.VM_PARENT_HOST);
        });
        x.account(props, c, FailureCause.opsProps("vm"), "the properties of the virtual machines");
        for (String id : props.failedIds) vmsByRid.get(id).propsFailed = true;

        // shares: the properties, and for a VM missing one of them the statistics (latest, not stale)
        List<String> need = new ArrayList<>();
        for (VmF v : vmsByRid.values()) {
            if (!v.propsFailed && (v.cpuShares == null || v.memShares == null)) need.add(v.rid);
        }
        if (!need.isEmpty()) {
            OpsReads.Report sr = x.reads.latestStats(need, StatKeys.SHARE_KEYS, OpsReads.STALE_MS, x.now(),
                    (id, st) -> {
                        VmF v = vmsByRid.get(id);
                        if (v == null) return;
                        if (v.cpuShares == null) v.cpuShares = st.get(StatKeys.VM_CPU_SHARES);
                        if (v.memShares == null) v.memShares = st.get(StatKeys.VM_MEM_SHARES);
                    });
            x.account(sr, c, FailureCause.opsStats("vm-shares"), "the share statistics of the virtual machines");
            for (String id : sr.failedIds) vmsByRid.get(id).sharesFailed = true;
        }
        for (VmF v : vmsByRid.values()) shares(v);

        // the KPIs of the powered-on VMs
        List<String> on = new ArrayList<>();
        for (VmF v : vmsByRid.values()) if (!v.propsFailed && v.powered) on.add(v.rid);
        OpsReads.Report st = x.reads.latestStats(on, StatKeys.VM_KPI_STATS, OpsReads.STALE_MS, x.now(),
                (id, s) -> vmKpis(vmsByRid.get(id), s));
        x.account(st, c, FailureCause.opsStats("vm"), "the statistics of the virtual machines");
        for (String id : st.failedIds) {
            vmsByRid.get(id).statsFailed = true;
            x.withheld(4); // zip swap, VM Performance, Guest OS Performance, VM Guest OS KPI
        }
        for (VmF v : vmsByRid.values()) {
            String host = m.hostOfVm(v.moref);
            if (!m.vmHostKnown(v.moref)) {
                // vCenter would not say where this VM runs: place it as the original does, by the host name Operations
                // holds, when exactly one host has that name; else the placement of every VM is in doubt
                host = v.propsFailed ? null : m.hostByName(v.hostName);
                if (host == null) placementOk = false;
            }
            if (host == null) continue;
            if (v.propsFailed) {
                hostsWithUnknownVmProps.add(host);
            } else if (v.powered && v.statsFailed) {
                hostsWithUnknownVmStats.add(host);
                poweredOnByHost.computeIfAbsent(host, k -> new ArrayList<>()).add(v);
            } else if (v.powered) {
                poweredOnByHost.computeIfAbsent(host, k -> new ArrayList<>()).add(v);
            }
        }
    }

    /** {@code shares / units}, rounded to two decimals; no value when either is missing or the units are 0. */
    private void shares(VmF v) {
        if (v.propsFailed) {
            x.withheld(2);
            return;
        }
        // a failed fallback read costs only the share that needed it: one found in the properties is known
        if (v.cpuShares == null && v.sharesFailed) x.withheld(1);
        else {
            Double cpu = perUnit(v.cpuShares, v.vcpus);
            if (cpu != null) x.stat(v.rid, G + "Configuration|Share per vCPU", PyRound.round2(cpu));
        }
        if (v.memShares == null && v.sharesFailed) x.withheld(1);
        else {
            Double mem = perUnit(v.memShares, v.memoryMb);
            if (mem != null) x.stat(v.rid, G + "Configuration|Share per MB of Memory", PyRound.round2(mem));
        }
    }

    private static Double perUnit(Double shares, Double units) {
        if (shares == null || units == null || units == 0.0) return null;
        return shares / units;
    }

    private void vmKpis(VmF v, Map<String, Double> s) {
        if (v == null) return;
        double[] cont = new double[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            Double d = s.get(StatKeys.VM_CONTENTION[i]);
            cont[i] = d == null ? VmContention.MISSING : d;
            if (d != null) any = true;
        }
        v.contention = any ? cont : null;
        KpiScorer.Evaluation perf = KpiScorer.evaluate(KpiTable.VM_PERFORMANCE, VmKpis.vmPerformanceValues(s), false);
        publishRows(v.rid, VmKpis.VM_GROUP, KpiTable.VM_PERFORMANCE, perf.rows);
        if (perf.score != null) x.stat(v.rid, G + VmKpis.VM_GROUP + "|VM Performance Score", perf.score);
        KpiScorer.Evaluation guest = KpiScorer.evaluate(KpiTable.GUEST_OS_PERFORMANCE, VmKpis.guestValues(s), false);
        if (guest.score != null) x.stat(v.rid, G + VmKpis.GUEST_GROUP + "|Guest OS Performance Score", guest.score);
        Double kpi = VmKpis.vmGuestOsKpi(perf.score, guest.score);
        if (kpi != null) x.stat(v.rid, StatKeys.VM_GUEST_KPI, kpi);
    }

    private void publishRows(String rid, String group, KpiMetric[] metrics, Map<String, Double> rows) {
        for (KpiMetric mt : metrics) {
            Double v = rows.get(mt.name);
            if (mt.published && v != null) x.stat(rid, G + group + "|" + mt.name, v);
        }
    }

    // ------------------------------------------------------------------ hosts

    private void hosts(OpsResourceMap hostMap) throws InterruptedException {
        if (!m.hostsRead()) {
            x.inputMissing(c, "the list of hosts (R-HO)");
            return;
        }
        if (hostMap == null) {
            x.inputMissing(c, "VCF Operations' list of hosts");
            return;
        }
        List<String> mor = new ArrayList<>(m.hosts().keySet());
        Collections.sort(mor);
        List<String> rids = new ArrayList<>();
        Map<String, HostF> byRid = new HashMap<>();
        for (String mo : mor) {
            String rid = hostMap.resourceId(mo);
            if (rid == null) continue;
            HostF f = new HostF(mo, rid, m.hosts().get(mo));
            hostsByMoref.put(mo, f);
            byRid.put(rid, f);
            rids.add(rid);
        }
        OpsReads.Report st = x.reads.latestStats(rids, StatKeys.HOST_STATS, OpsReads.STALE_MS, x.now(),
                OpsReads.HOST_BATCH, (id, s) -> {
            HostF f = byRid.get(id);
            if (f == null) return;
            f.stats = s;
            f.answered = true;
        });
        x.account(st, c, FailureCause.opsStats("host"), "the statistics of the hosts");
        for (String id : st.failedIds) byRid.get(id).statsFailed = true;
        OpsReads.Report pr = x.reads.latestProperties(rids, StatKeys.HOST_PROPS, (id, p) -> {
            HostF f = byRid.get(id);
            if (f == null) return;
            f.props = p;
            f.answered = true;
        });
        x.account(pr, c, FailureCause.opsProps("host"), "the properties of the hosts");
        for (String id : pr.failedIds) byRid.get(id).propsFailed = true;
        // NIC speeds are properties of the host (one pair per NIC), asked for by the NIC names vCenter gave
        Set<String> nicKeys = new java.util.LinkedHashSet<>();
        List<String> nicRids = new ArrayList<>();
        for (HostF f : hostsByMoref.values()) {
            f.nics = f.h == null ? null : f.h.nics;
            if (f.nics == null) {
                f.nicFailed = true;
                continue;
            }
            if (f.nics.isEmpty()) continue;
            nicRids.add(f.rid);
            for (String dev : f.nics) {
                nicKeys.add(NetworkSpeed.speedKey(dev));
                nicKeys.add(NetworkSpeed.configuredKey(dev));
            }
        }
        if (!nicRids.isEmpty()) {
            OpsReads.Report nr = x.reads.latestProperties(nicRids, new ArrayList<>(nicKeys), (id, p) -> {
                HostF f = byRid.get(id);
                if (f != null) f.nic = NetworkSpeed.read(f.nics, p);
            });
            x.account(nr, c, FailureCause.opsProps("host-nic"), "the NIC speeds of the hosts");
            for (String id : nr.failedIds) byRid.get(id).nicFailed = true;
        }

        for (HostF f : hostsByMoref.values()) {
            Double cores = num(f.props.get(StatKeys.HOST_CORES));
            f.inv = new HostInv(f.rid, cores, HostInv.threads(cores, f.props.get(StatKeys.HOST_HT_AVAILABLE),
                    f.props.get(StatKeys.HOST_HT_ACTIVE)), f.props.get(StatKeys.HOST_CONNECTION),
                    f.props.get(StatKeys.HOST_MAINTENANCE), f.props.get(StatKeys.HOST_POWER));
            for (VmF v : poweredOnByHost.getOrDefault(f.moref, Collections.<VmF>emptyList())) {
                f.inv.vms.add(new HostInv.Vm(v.vcpus, v.memoryMb));
            }
            if (f.ok()) f.availability = Availability.ofHost(f.stats.get(StatKeys.HOST_POWERED_ON), f.props);
        }
        // upstream publishes no availability at all when no host answered either read (Q15)
        boolean anyAnswer = false;
        boolean anyFailed = false;
        for (HostF f : hostsByMoref.values()) {
            anyAnswer |= f.answered;
            anyFailed |= !f.ok();
        }
        if (Quirks.NOTHING_WHEN_NO_HOST_HAS_DATA && !hostsByMoref.isEmpty() && !anyAnswer && !anyFailed) {
            for (HostF f : hostsByMoref.values()) f.availability = null;
            availabilityNoData = true;
            x.log.warn("No Operational Availability data for any host, so none is published this cycle");
        }
        // network speed: nothing at all when not one NIC anywhere has both speeds (Q15, DV-108)
        boolean anyActual = false;
        boolean anyConfigured = false;
        boolean anyPair = false;
        boolean nicFailed = false;
        for (HostF f : hostsByMoref.values()) {
            nicFailed |= f.nicFailed;
            if (f.nic == null) continue;
            anyActual |= !f.nic.actual.isEmpty();
            anyConfigured |= !f.nic.configured.isEmpty();
            for (String dev : f.nic.actual.keySet()) anyPair |= f.nic.configured.containsKey(dev);
        }
        networkNoData = Quirks.NOTHING_WHEN_NO_HOST_HAS_DATA && !hostsByMoref.isEmpty() && !anyPair && !nicFailed;
        if (networkNoData) {
            if (!anyActual) {
                x.log.warn("No NIC speeds for any host, so no Network Speed Degradation this cycle");
            } else if (!anyConfigured) {
                // one line a cycle, and Collection Health shows PARTIAL: the property is off in the policy
                x.gaps.merge(c, 1, Integer::sum);
                x.withheld(1);
                x.log.warn("No host reports the NIC property " + StatKeys.NIC_CONFIGURED_SPEED + " (Network|Configured"
                        + " Speed), so no Network Speed Degradation is sent this cycle; enable that property in the"
                        + " active policy");
            } else {
                x.log.warn("No host has a NIC with both a speed and a configured speed, so no Network Speed"
                        + " Degradation this cycle");
            }
        } else {
            for (HostF f : hostsByMoref.values()) {
                if (f.nicFailed) continue;
                f.speed = f.nic == null ? null : NetworkSpeed.degradation(f.nic.actual, f.nic.configured);
            }
        }
    }

    private boolean availabilityNoData;
    private boolean networkNoData;

    // ------------------------------------------------------------------ clusters

    private void clusters(OpsResourceMap clusterMap) throws InterruptedException {
        if (!m.clustersRead()) {
            x.inputMissing(c, "the list of clusters (R-CL)");
            return;
        }
        if (clusterMap == null) {
            x.inputMissing(c, "VCF Operations' list of clusters");
            return;
        }
        List<String> mor = new ArrayList<>(m.clusters().keySet());
        Collections.sort(mor);
        List<String> rids = new ArrayList<>();
        Map<String, ClusterF> byRid = new HashMap<>();
        for (String mo : mor) {
            String rid = clusterMap.resourceId(mo);
            if (rid == null) continue;
            ClusterF f = new ClusterF(mo, rid);
            if (m.hostsRead()) {
                for (Membership.Host h : m.hostsOf(mo)) {
                    HostF hf = hostsByMoref.get(h.moref);
                    if (hf != null) f.hosts.add(hf);
                }
            }
            clustersByMoref.put(mo, f);
            byRid.put(rid, f);
            rids.add(rid);
        }
        OpsReads.Report st = x.reads.latestStats(rids, StatKeys.CLUSTER_STATS, OpsReads.STALE_MS, x.now(), (id, s) -> {
            ClusterF f = byRid.get(id);
            if (f != null) f.stats = s;
        });
        x.account(st, c, FailureCause.opsStats("cluster"), "the statistics of the clusters");
        for (String id : st.failedIds) byRid.get(id).statsFailed = true;
        OpsReads.Report pr = x.reads.latestProperties(rids, StatKeys.CLUSTER_PROPS, (id, p) -> {
            ClusterF f = byRid.get(id);
            if (f != null) f.props = p;
        });
        x.account(pr, c, FailureCause.opsProps("cluster"), "the properties of the clusters");
        for (String id : pr.failedIds) byRid.get(id).propsFailed = true;
    }

    // ------------------------------------------------------------------ publishing: hosts

    /** The VMs on the host are known with their statistics (what the contention rows need). */
    private boolean vmDataOk(String hostMoref) {
        return vmSizesOk(hostMoref) && !hostsWithUnknownVmStats.contains(hostMoref);
    }

    /** The VMs on the host are known with their sizes (what the monster rows need). */
    private boolean vmSizesOk(String hostMoref) {
        return m.vmListRead() && vmListOk && placementOk && !hostsWithUnknownVmProps.contains(hostMoref);
    }

    private void publishHosts() {
        for (HostF f : hostsByMoref.values()) {
            if (f.h.connected == null) {
                x.withheld(1); // the connection state was not read: whether the host is kept is unknown
                continue;
            }
            if (!f.h.connected) continue; // the host collector keeps only connected hosts (parity, issue 86)
            // ESXi Performance
            boolean vmOk = vmDataOk(f.moref);
            List<double[]> vc = new ArrayList<>();
            for (VmF v : poweredOnByHost.getOrDefault(f.moref, Collections.<VmF>emptyList())) {
                if (v.contention != null) vc.add(v.contention);
            }
            Map<String, Double> raw = new LinkedHashMap<>();
            if (vmOk) raw.putAll(VmContention.rows(vc));
            if (!f.statsFailed) {
                Double bad = VmContention.badPacketPct(f.stats);
                if (bad != null) raw.put(KpiTable.ESXI_BAD_PACKETS.name, bad);
            }
            publishKpi(f.rid, EsxiPerformance.GROUP, EsxiPerformance.KPI_KEY, KpiTable.ESXI_PERFORMANCE, raw,
                    false, (vmOk ? 0 : 8) + (f.statsFailed ? 1 : 0));
            if (!f.ok()) x.withheld(1);
            else if (f.availability != null) x.stat(f.rid, StatKeys.HOST_AVAILABILITY, PyRound.round2(f.availability));
            if (f.nicFailed) x.withheld(1);
            else if (f.speed != null) x.stat(f.rid, StatKeys.HOST_NETWORK_SPEED, PyRound.round2(f.speed));
        }
    }

    /**
     * Publish the rows that are known, then the KPI if no row is unknown. {@code unknownRows} rows could not be
     * computed because their inputs were not read: the KPI is then withheld too (never a score over some rows).
     */
    private void publishKpi(String rid, String group, String key, KpiMetric[] metrics, Map<String, Double> raw,
            boolean higherIsWorse, int unknownRows) {
        KpiScorer.Evaluation r = KpiScorer.evaluate(metrics, raw, higherIsWorse);
        publishRows(rid, group, metrics, r.rows);
        if (unknownRows > 0) {
            x.withheld(unknownRows + 1);
            return;
        }
        if (r.score != null) x.stat(rid, G + group + "|" + key, r.score);
    }

    // ------------------------------------------------------------------ publishing: clusters

    private void publishClusters() {
        List<Double> clusterAvailability = new ArrayList<>();
        boolean worstKnown = true;
        for (ClusterF cl : clustersByMoref.values()) {
            boolean hostsOk = m.hostsRead();
            boolean hostStatsOk = hostsOk;
            boolean hostPropsOk = hostsOk;
            boolean hostVmOk = hostsOk;
            boolean hostSizesOk = hostsOk;
            for (HostF h : cl.hosts) {
                hostStatsOk &= !h.statsFailed;
                hostPropsOk &= !h.propsFailed;
                hostVmOk &= vmDataOk(h.moref);
                hostSizesOk &= vmSizesOk(h.moref);
            }
            performance(cl, hostStatsOk, hostVmOk);
            risk(cl, hostStatsOk && hostPropsOk && hostSizesOk);
            consumption(cl, hostStatsOk && hostPropsOk && !cl.statsFailed);
            availabilityUnknown = false;
            Double av = availability(cl);
            if (availabilityUnknown) {
                worstKnown = false;
            } else if (av != null) {
                clusterAvailability.add(av);
            }
            resourcePools(cl);
        }
        // the lowest cluster of this vCenter (R212): only when every cluster's value is known
        worstCluster = worstKnown ? Availability.worst(clusterAvailability) : null;
        worstClusterKnown = worstKnown;
    }

    private Double worstCluster;
    private boolean worstClusterKnown;

    private void performance(ClusterF cl, boolean hostStatsOk, boolean vmOk) {
        List<double[]> vc = new ArrayList<>();
        List<Double> packets = new ArrayList<>();
        for (HostF h : cl.hosts) {
            for (VmF v : poweredOnByHost.getOrDefault(h.moref, Collections.<VmF>emptyList())) {
                if (v.contention != null) vc.add(v.contention);
            }
            if (!h.statsFailed) packets.add(VmContention.badPacketPct(h.stats));
        }
        Map<String, Double> raw = new LinkedHashMap<>();
        if (vmOk) raw.putAll(VmContention.rows(vc));
        if (hostStatsOk) {
            Map<String, Double> p = ClusterPerformance.values(Collections.<double[]>emptyList(), packets);
            raw.putAll(p);
        }
        publishKpi(cl.rid, ClusterPerformance.GROUP, ClusterPerformance.KPI_KEY, KpiTable.CLUSTER_PERFORMANCE, raw,
                false, (vmOk ? 0 : 8) + (hostStatsOk ? 0 : 1));
    }

    private void risk(ClusterF cl, boolean allKnown) {
        if (!allKnown) {
            x.withheld(KpiTable.CLUSTER_RISK.length + 1);
            return;
        }
        List<HostInv> invs = new ArrayList<>();
        Map<HostInv, Map<String, Double>> stats = new HashMap<>();
        for (HostF h : cl.hosts) {
            invs.add(h.inv);
            stats.put(h.inv, h.stats);
        }
        Map<String, Double> raw = ClusterRisk.clusterValues(invs, stats::get, x.in.monster);
        publishKpi(cl.rid, ClusterRisk.GROUP, ClusterRisk.KPI_KEY, KpiTable.CLUSTER_RISK, raw,
                ClusterRisk.HIGHER_IS_WORSE, 0);
    }

    private void consumption(ClusterF cl, boolean allKnown) {
        if (!allKnown) {
            x.withheld(KpiTable.CLUSTER_CONSUMPTION.length + 1);
            return;
        }
        List<HostInv> invs = new ArrayList<>();
        Map<HostInv, Map<String, Double>> stats = new HashMap<>();
        boolean anyRunning = false;
        for (HostF h : cl.hosts) {
            invs.add(h.inv);
            stats.put(h.inv, h.stats);
            anyRunning |= ClusterConsumption.servesVms(h.inv, h.stats);
        }
        // with no serving host there is no load to measure: nothing is published, even if capacity figures exist
        if (!anyRunning) return;
        Map<String, Double> raw = ClusterConsumption.values(invs, stats::get, cl.stats);
        if (ClusterConsumption.usableIsZero(cl.stats)) {
            // hosts are serving but the reserve takes all the capacity: the score is fixed at full; the rows that can be computed still go out
            KpiScorer.Evaluation r = KpiScorer.evaluate(new KpiMetric[0], raw, ClusterConsumption.HIGHER_IS_WORSE);
            publishRows(cl.rid, ClusterConsumption.GROUP, KpiTable.CLUSTER_CONSUMPTION, r.rows);
            x.stat(cl.rid, G + ClusterConsumption.GROUP + "|" + ClusterConsumption.KPI_KEY, ClusterConsumption.FULL);
            return;
        }
        publishKpi(cl.rid, ClusterConsumption.GROUP, ClusterConsumption.KPI_KEY, KpiTable.CLUSTER_CONSUMPTION, raw,
                ClusterConsumption.HIGHER_IS_WORSE, 0);
    }

    /** Set by {@link #availability} when a cluster's value could not be worked out because an input was not read. */
    private boolean availabilityUnknown;

    private Double availability(ClusterF cl) {
        if (availabilityNoData) return null;
        if (!m.hostsRead()) {
            x.withheld(1);
            availabilityUnknown = true;
            return null;
        }
        List<Double> values = new ArrayList<>();
        for (HostF h : cl.hosts) {
            if (h.availability == null) {
                // a host whose reads failed has no value: the cluster's value is unknown, not lower (DV-21)
                if (!h.ok()) {
                    x.withheld(1);
                    availabilityUnknown = true;
                    return null;
                }
                continue;
            }
            values.add(h.availability);
        }
        Double count = cl.stats.get(StatKeys.CLUSTER_HOST_COUNT);
        Double failover = cl.stats.get(StatKeys.CLUSTER_FAILOVER);
        if (failover == null) failover = num(cl.props.get(StatKeys.CLUSTER_FAILOVER));
        boolean failoverUnknown = failover == null && cl.propsFailed;
        if (cl.statsFailed || failoverUnknown) {
            x.withheld(1);
            availabilityUnknown = true;
            return null;
        }
        Double v = Availability.ofCluster(values, count, failover);
        if (v != null) x.stat(cl.rid, StatKeys.CLUSTER_AVAILABILITY, PyRound.round2(v));
        return v;
    }

    private void resourcePools(ClusterF cl) {
        if (!m.poolsRead()) {
            x.withheld(2); // the pool read failed; it is counted where it was made
            return;
        }
        int[] counts = m.poolCounts().get(cl.moref);
        int a = counts == null ? 0 : counts[0];
        int b = counts == null ? 0 : counts[1];
        x.stat(cl.rid, G + "Resource Pools|Number of Resource Pools", a);
        x.stat(cl.rid, G + "Resource Pools|Cascading Resource Pools", b);
    }

    // ------------------------------------------------------------------ Datacenters and the vCenter

    private void datacenters() {
        if (m.datacenters().isEmpty() && m.datacentersRead()) return;
        OpsResourceMap dcMap = datacenterMap.get();
        if (dcMap == null) {
            x.inputMissing(PropertyCollection.C_DATACENTER, "VCF Operations' list of Datacenters");
            return;
        }
        if (networkNoData) return;
        for (Membership.Datacenter d : m.datacenters().values()) {
            String rid = dcMap.resourceId(d.moref);
            if (rid == null) continue;
            if (d.hosts == null || !m.hostsRead()) {
                x.withheld(1);
                continue;
            }
            boolean unknown = false;
            boolean anyHost = false;
            boolean anyValue = false;
            int degraded = 0;
            for (String hm : d.hosts) {
                HostF f = hostsByMoref.get(hm);
                if (f == null) continue;
                anyHost = true;
                if (f.nicFailed) {
                    unknown = true;
                    break;
                }
                if (f.speed != null) anyValue = true;
                if (NetworkSpeed.degraded(f.speed)) degraded++;
            }
            if (!anyHost) continue;
            if (unknown) {
                x.withheld(1);
                continue;
            }
            if (!anyValue && !Quirks.EMPTY_DATACENTER_COUNT_IS_ZERO) continue;
            x.stat(rid, StatKeys.DC_NETWORK_SPEED, degraded);
        }
    }

    private void vcenter() {
        String vc = x.in.vcenterResourceId;
        if (vc == null) return;
        if (worstClusterKnown) {
            if (worstCluster != null) x.stat(vc, StatKeys.WORST_CLUSTER_AVAILABILITY, PyRound.round2(worstCluster));
        } else {
            x.withheld(1);
        }
        Integer ports = m.portGroupCount();
        if (ports == null) {
            x.inputMissing(PropertyCollection.C_DATACENTER, "the port group names of the hosts and Datacenters");
            x.withheld(1);
        } else {
            x.stat(vc, StatKeys.PORT_GROUPS, ports);
        }
    }
}
