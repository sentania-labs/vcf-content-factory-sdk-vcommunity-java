package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.kpi.Availability;
import com.vcfcf.adapters.vcommunity.kpi.NetworkSpeed;
import com.vcfcf.adapters.vcommunity.kpi.Percentile;
import com.vcfcf.adapters.vcommunity.kpi.PeriodicCheck;
import com.vcfcf.adapters.vcommunity.kpi.PeriodicWindow;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.ops.OpsReads;
import com.vcfcf.adapters.vcommunity.ops.UnscopedIds;
import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The figures of the vSphere World object (map D10, D11; design 4.9): roll-ups over every vCenter of VCF
 * Operations, read back from the values already stored (so each is one cycle behind, as in the original), and
 * the Cluster Delta counts, once per window. Every pak instance computes and pushes the same values.
 *
 * <p>Each figure is withheld, and counted, when any read it depends on failed (DV-24): a list of the
 * resources, a batch of statistics. A read that worked and found nothing is "none", as in the original.
 */
final class WorldStage {

    private static final String G = StatKeys.P;
    private static final String VMWARE = "VMWARE";

    private final DerivedContext x;
    private final UnscopedIds lists;
    private final String c;

    private final DeltaMemory delta;

    WorldStage(DerivedContext x, UnscopedIds lists, String collector, DeltaMemory delta) {
        this.delta = delta;
        this.x = x;
        this.lists = lists;
        this.c = collector;
    }

    /** The ids of a kind of every vCenter; null (counted) when the list could not be read. */
    private List<String> ids(String adapterKind, String kind, boolean everyCycle) throws InterruptedException {
        String[] why = new String[1];
        List<String> l = lists.get(x.in.ops, adapterKind, kind, x.in.cycle, everyCycle, why);
        if (why[0] != null) {
            x.failed(c, FailureCause.opsMap(kind), kind, 0, "the VCF Operations list of " + kind
                    + " objects could not be read (" + why[0] + "); the figures that need it are not sent");
            return null;
        }
        return l;
    }

    /** The ids of every object of a kind in Operations, from the map of this vCenter's objects; null (counted) when it has none. */
    private List<String> known(String kind) {
        com.vcfcf.adapters.vcommunity.ops.OpsResourceMap m = x.in.opsMap.apply(kind);
        if (m == null) {
            x.inputMissing(c, "VCF Operations' list of " + kind + " objects");
            return null;
        }
        return m.allIds();
    }

    /**
     * {@link DerivedContext#account}, and a failed read marks the three lists the roll-ups read for a new read next
     * cycle: a list that still names an object that has gone is a likely reason for a batch to fail (DV-87).
     */
    private boolean ok(OpsReads.Report r, String collector, String cause, String what) {
        boolean good = x.account(r, collector, cause, what);
        if (!good) {
            x.in.markStale.accept("ClusterComputeResource");
            x.in.markStale.accept("HostSystem");
            x.in.markStale.accept("VirtualMachine");
        }
        return good;
    }

    void run() throws InterruptedException {
        List<String> worlds = ids(VMWARE, "vSphere World", true);
        if (worlds == null) return;
        if (worlds.size() != 1) {
            // never guess which one (DV-06); none is what a vCenter that is not yet in Operations looks like
            x.failed(c, FailureCause.opsMap("vSphere World"), "vSphere World", 0, worlds.isEmpty()
                    ? "no vSphere World object was found in VCF Operations; its figures are not sent"
                    : worlds.size() + " vSphere World objects were found in VCF Operations; which one is not "
                            + "guessed, so its figures are not sent");
            return;
        }
        String world = worlds.get(0);
        // the clusters, hosts and VMs of every vCenter are the lists the Ops maps were built from: one read
        List<String> clusters = known("ClusterComputeResource");
        List<String> hosts = known("HostSystem");
        List<String> vcenters = ids(VMWARE, "VMwareAdapter Instance", true);
        List<String> vms = known("VirtualMachine");
        if (x.stop()) return;
        clusterSummary(world, clusters);
        availabilitySummary(world, clusters, hosts);
        networkCount(world, hosts);
        clusterDelta(world, clusters);
        portGroups(world, vcenters);
        toolsCounts(world, vms);
    }

    // ------------------------------------------------------------------ cluster summaries

    private static List<Double> scores(Map<String, Map<String, Double>> byCluster, String key) {
        List<Double> out = new ArrayList<>();
        for (Map<String, Double> s : byCluster.values()) {
            Double v = s.get(key);
            if (v != null) out.add(v);
        }
        return out;
    }

    private void clusterSummary(String world, List<String> clusters) throws InterruptedException {
        if (clusters == null) {
            x.withheld(6);
            return;
        }
        Map<String, Map<String, Double>> stored = new LinkedHashMap<>();
        OpsReads.Report r = x.reads.latestStats(clusters, StatKeys.CLUSTER_SCORES, OpsReads.STALE_MS, x.now(),
                stored::put);
        if (!ok(r, c, FailureCause.opsStats("world-clusters"), "the stored cluster scores")) {
            x.withheld(6);
            return;
        }
        List<Double> perf = scores(stored, StatKeys.CLUSTER_PERFORMANCE_SCORE);
        List<Double> risk = scores(stored, StatKeys.CLUSTER_RISK_SCORE);
        List<Double> cons = scores(stored, StatKeys.CLUSTER_CONSUMPTION_SCORE);
        put(world, "Worst 10 Percent Cluster Performance", Percentile.p10(perf));
        put(world, "Worst Cluster Performance", min(perf));
        put(world, "Worst 10 Percent Cluster Risk", Percentile.p90(risk));
        put(world, "Worst Cluster Risk", max(risk));
        put(world, "Highest 10 Percent Cluster Utilization", Percentile.p90(cons));
        put(world, "Highest Cluster Utilization", max(cons));
    }

    private void put(String world, String name, Double v) {
        if (v != null) x.stat(world, G + name, PyRound.round2(v));
    }

    private static Double min(List<Double> l) {
        Double m = null;
        for (Double d : l) if (m == null || d < m) m = d;
        return m;
    }

    private static Double max(List<Double> l) {
        Double m = null;
        for (Double d : l) if (m == null || d > m) m = d;
        return m;
    }

    // ------------------------------------------------------------------ availability

    /** Every object of a kind that has a stored value of the key. Null (counted) when a read failed. */
    private List<Double> stored(List<String> ids, String key, String set, String what) throws InterruptedException {
        if (ids == null) return null;
        List<Double> out = new ArrayList<>();
        OpsReads.Report r = x.reads.latestStats(ids, Collections.singletonList(key), OpsReads.STALE_MS, x.now(),
                (id, s) -> {
                    Double v = s.get(key);
                    if (v != null) out.add(v);
                });
        return ok(r, c, FailureCause.opsStats(set), what) ? out : null;
    }

    private void availabilitySummary(String world, List<String> clusters, List<String> hosts)
            throws InterruptedException {
        List<Double> cl = stored(clusters, StatKeys.CLUSTER_AVAILABILITY, "world-cluster-availability",
                "the stored cluster availability");
        List<Double> ho = stored(hosts, StatKeys.HOST_AVAILABILITY, "world-host-availability",
                "the stored host availability");
        if (cl == null) x.withheld(1);
        else if (Availability.worst(cl) != null) {
            x.stat(world, G + "Availability|Worst Cluster Operational Availability",
                    PyRound.round2(Availability.worst(cl)));
        }
        if (ho == null) {
            x.withheld(1);
        } else if (!ho.isEmpty()) {
            double sum = 0;
            for (double d : ho) sum += d;
            x.stat(world, G + "Availability|ESXi Hosts Operational Availability", PyRound.round2(sum / ho.size()));
        }
    }

    // ------------------------------------------------------------------ network

    /**
     * R211: the hosts whose stored speed is below 100 %. As upstream does it, any host the answer names counts
     * the figure as measured, even when its stored value is too old to use (it is then not below 100, so the count
     * is 0); only an answer that names no host at all publishes nothing (Q16, {@link Quirks#STALE_HOST_VALUE_COUNTS_AS_ZERO}).
     */
    private void networkCount(String world, List<String> hosts) throws InterruptedException {
        if (hosts == null) {
            x.withheld(1);
            return;
        }
        int[] named = new int[1];
        int[] usable = new int[1];
        int[] below = new int[1];
        OpsReads.Report r = x.reads.latestStats(hosts, Collections.singletonList(StatKeys.HOST_NETWORK_SPEED),
                OpsReads.STALE_MS, x.now(), (id, s) -> {
                    named[0]++;
                    Double v = s.get(StatKeys.HOST_NETWORK_SPEED);
                    if (v != null) usable[0]++;
                    if (NetworkSpeed.degraded(v)) below[0]++;
                });
        if (!ok(r, c, FailureCause.opsStats("world-network"), "the stored host network speeds")) {
            x.withheld(1);
            return;
        }
        int measured = Quirks.STALE_HOST_VALUE_COUNTS_AS_ZERO ? named[0] : usable[0];
        // no host has anything stored yet: no figure at all, not a zero
        if (measured == 0) return;
        x.stat(world, StatKeys.DC_NETWORK_SPEED, below[0]);
    }

    // ------------------------------------------------------------------ Cluster Delta

    private void clusterDelta(String world, List<String> clusters) throws InterruptedException {
        PeriodicWindow w = PeriodicWindow.at(x.now(), x.in.checkHour, x.in.checkMinute, x.in.periodHours);
        Map<String, Map<String, Double>> own = new HashMap<>();
        OpsReads.Report wr = x.reads.latestTimestamps(Collections.singletonList(world), StatKeys.DELTA_PUBLISHED,
                own::put);
        if (!ok(wr, c, FailureCause.opsStats("world-delta"), "when the Cluster Delta counts were last stored")) {
            x.withheld(2);
            return;
        }
        double published = 0.0;
        for (Double t : own.getOrDefault(world, Collections.<String, Double>emptyMap()).values()) {
            if (t != null && t > published) published = t;
        }
        // these counts already carry a sample from this window (stamped at or after its end)
        if (published >= w.endMs) return;
        // sent by this instance a moment ago and not yet to be read back (L2)
        if (delta.sentLately(w.endMs, x.now())) return;
        if (clusters == null) {
            x.withheld(2);
            return;
        }
        if (clusters.isEmpty()) return;
        List<String> keys = new ArrayList<>(StatKeys.PERIODIC_PUBLISHED);
        keys.addAll(StatKeys.CLUSTER_SCORES);
        Map<String, Map<String, Double>> latest = new HashMap<>();
        OpsReads.Report cr = x.reads.latestTimestamps(clusters, keys, latest::put);
        if (!ok(cr, c, FailureCause.opsStats("world-delta"), "when the cluster checks were last stored")) {
            x.withheld(2);
            return;
        }
        // hold off while any cluster is still to be checked: the counts would otherwise leave it out
        for (String id : clusters) {
            if (w.awaitsCheck(latest.getOrDefault(id, Collections.<String, Double>emptyMap()))) return;
        }
        List<String> close = Collections.singletonList(StatKeys.CLOSE_FOR_BUSINESS);
        Map<String, Double> previous = new HashMap<>();
        Map<String, Double> current = new HashMap<>();
        OpsReads.Report pr = x.reads.history(clusters, close, w.beginMs, w.endMs, PeriodicCheck.CLUSTERS_PER_REQUEST,
                (id, s) -> last(previous, id, s));
        OpsReads.Report nr = x.reads.history(clusters, close, w.endMs, x.now(), PeriodicCheck.CLUSTERS_PER_REQUEST,
                (id, s) -> last(current, id, s));
        boolean both = ok(pr, c, FailureCause.OPS_HISTORY, "the Close for Business history of the last window");
        both &= ok(nr, c, FailureCause.OPS_HISTORY, "the Close for Business history since the window ended");
        if (!both) {
            // a count from part of the history would be wrong, so none is sent and the next cycle tries again
            x.withheld(2);
            return;
        }
        int[] t = PeriodicCheck.flips(previous, current);
        x.stat(world, StatKeys.DELTA_CLOSE, t[0]);
        x.stat(world, StatKeys.DELTA_OPEN, t[1]);
        delta.queued(w.endMs);
        x.log.info("Cluster Delta counts for the window to " + new java.util.Date(w.endMs).toInstant() + ": "
                + t[0] + " closed, " + t[1] + " opened, of " + clusters.size() + " clusters.");
    }

    private static void last(Map<String, Double> into, String id, Map<String, List<Double>> samples) {
        List<Double> s = samples.get(StatKeys.CLOSE_FOR_BUSINESS);
        if (s != null && !s.isEmpty()) into.put(id, s.get(s.size() - 1));
    }

    // ------------------------------------------------------------------ port groups and VMware Tools

    private void portGroups(String world, List<String> vcenters) throws InterruptedException {
        if (vcenters == null) {
            x.withheld(1);
            return;
        }
        double[] sum = new double[1];
        OpsReads.Report r = x.reads.latestStats(vcenters, Collections.singletonList(StatKeys.PORT_GROUPS),
                OpsReads.CONFIGURATION_STALE_MS, x.now(), (id, s) -> {
                    Double v = s.get(StatKeys.PORT_GROUPS);
                    if (v != null) sum[0] += v;
                });
        if (!ok(r, c, FailureCause.opsStats("world-port-groups"), "the stored port group counts")) {
            x.withheld(1);
            return;
        }
        x.stat(world, StatKeys.PORT_GROUPS, (long) sum[0]);
    }

    private static final String TOOLS_RUNNING = "summary|guest|toolsRunningStatus";
    private static final String TOOLS_VERSION = "summary|guest|toolsVersionStatus2";

    private void toolsCounts(String world, List<String> vms) throws InterruptedException {
        if (vms == null) {
            x.withheld(2);
            return;
        }
        int[] n = new int[2];
        List<String> keys = new ArrayList<>(java.util.Arrays.asList(TOOLS_RUNNING, TOOLS_VERSION, StatKeys.VM_POWER));
        OpsReads.Report r = x.reads.latestProperties(vms, keys, (id, p) -> {
            String version = p.get(TOOLS_VERSION);
            if ("Guest Tools Not Installed".equals(version)) {
                n[0]++;
            } else if (version != null && !version.isEmpty() && "Guest Tools Not Running".equals(p.get(TOOLS_RUNNING))
                    && StatKeys.POWERED_ON.equals(p.get(StatKeys.VM_POWER))) {
                n[1]++;
            }
        });
        if (!ok(r, c, FailureCause.opsProps("world-tools"), "the VMware Tools properties of every VM")) {
            x.withheld(2);
            return;
        }
        x.stat(world, G + "Number of VM without Tools", n[0]);
        x.stat(world, G + "Number of VMware Tools down", n[1]);
    }
}
