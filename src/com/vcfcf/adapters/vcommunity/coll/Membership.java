package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in what, as this cycle's vCenter reads say it (design 3.2, 4.8): which hosts are in which cluster
 * and Datacenter, which VMs run on which host, the port group names, the resource pools. Identity is the
 * MoRef, never a name (DV-03, DV-04, DV-05). It is filled by the reads of {@link PropertyCollection} as their
 * answers stream past, one small entry per object, and read by {@link DerivedPhase} once they are done.
 *
 * <p>Each part has a flag that says its read finished. A part whose read failed is {@code null}-flagged
 * ({@link #hostsRead()} and so on), and what depends on it is withheld, never computed from half a list.
 */
public final class Membership {

    /** A cluster as vCenter names it. */
    public static final class Cluster {
        public final String moref;
        public final String name;

        Cluster(String moref, String name) {
            this.moref = moref;
            this.name = name;
        }
    }

    /** A host: its cluster (null when it is in none, or the parent is unknown), connection and port groups. */
    public static final class Host {
        public final String moref;
        public final String name;
        /** The cluster's MoRef; null for a host that is not in a cluster. */
        public final String cluster;
        /** True/false when the connection state was read; null when it could not be. */
        public final Boolean connected;
        /** Distinct standard port group names; null when the read of them failed (empty when unset). */
        public final Set<String> portGroups;

        Host(String moref, String name, String cluster, Boolean connected, Set<String> portGroups) {
            this.moref = moref;
            this.name = name;
            this.cluster = cluster;
            this.connected = connected;
            this.portGroups = portGroups;
        }
    }

    /** A Datacenter, with the hosts under it and the names of its distributed port groups. */
    public static final class Datacenter {
        public final String moref;
        public final String name;
        /** The MoRefs of the hosts under it; null when that read failed. */
        public Set<String> hosts;
        /** The names of its distributed port groups; null when that read failed. */
        public Set<String> dvPortGroups;

        Datacenter(String moref, String name) {
            this.moref = moref;
            this.name = name;
        }

        public boolean complete() {
            return hosts != null && dvPortGroups != null;
        }
    }

    /** A resource pool as vCenter has it: its parent's type and MoRef, and whether it is really a vApp. */
    public static final class Pool {
        public final String moref;
        public final boolean vApp;
        public final String parentType;
        public final String parent;
        /** True for a Supervisor namespace: vCenter keeps it as a pool, Operations keeps it as a Namespace object. */
        public final boolean namespace;

        Pool(String moref, boolean vApp, String parentType, String parent, boolean namespace) {
            this.moref = moref;
            this.vApp = vApp;
            this.parentType = parentType;
            this.parent = parent;
            this.namespace = namespace;
        }
    }

    private final Map<String, Cluster> clusters = new ConcurrentHashMap<>();
    private final Map<String, Host> hosts = new ConcurrentHashMap<>();
    /** VM MoRef to host MoRef; "" for a VM with no host (unset). A VM whose host could not be read is in {@link #vmsUnknownHost}. */
    private final Map<String, String> vmHost = new ConcurrentHashMap<>();
    private final Set<String> vmsUnknownHost = ConcurrentHashMap.newKeySet();
    private final Map<String, Datacenter> datacenters = new ConcurrentHashMap<>();
    private final List<Pool> pools = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean clustersRead;
    private volatile boolean hostsRead;
    private volatile boolean vmsRead;
    private volatile boolean datacentersRead;
    private volatile boolean poolsRead;

    // ------------------------------------------------------------------ filling

    void cluster(BulkRow row) {
        addCluster(row.obj.value, row.name());
    }

    void addCluster(String moref, String name) {
        clusters.put(moref, new Cluster(moref, name == null ? "" : name));
    }

    void resetClusters() {
        clusters.clear();
    }

    void host(BulkRow row) {
        XmlNode parent = row.val("parent");
        String cluster = parent != null && "ClusterComputeResource".equals(parent.attr("type"))
                && !parent.text().isEmpty() ? parent.text() : null;
        Boolean connected = row.readable("runtime.connectionState")
                ? Boolean.valueOf(HostCollector.isConnected(row)) : null;
        Set<String> groups;
        if (!row.readable("config.network.portgroup")) {
            groups = null;
        } else {
            groups = new HashSet<>();
            XmlNode pg = row.val("config.network.portgroup");
            if (pg != null) {
                for (XmlNode g : pg.children()) {
                    XmlNode spec = g.child("spec");
                    String n = spec == null ? null : spec.childText("name");
                    if (n != null) groups.add(n);
                }
            }
        }
        addHost(row.obj.value, row.name(), cluster, connected, groups);
        if (!row.readable("parent")) hostsParentUnknown.add(row.obj.value);
    }

    void addHost(String moref, String name, String cluster, Boolean connected, Set<String> portGroups) {
        hosts.put(moref, new Host(moref, name == null ? "" : name, cluster, connected, portGroups));
    }

    private final Set<String> hostsParentUnknown = ConcurrentHashMap.newKeySet();

    void resetHosts() {
        hosts.clear();
        hostsParentUnknown.clear();
    }

    void vm(BulkRow row) {
        XmlNode h = row.val("runtime.host");
        if (h != null && !h.text().isEmpty()) {
            addVm(row.obj.value, h.text());
        } else if (row.isUnset("runtime.host")) {
            addVm(row.obj.value, "");
        } else {
            vmsUnknownHost.add(row.obj.value);
        }
    }

    /** A VM and the MoRef of its host; "" for a VM that has none. */
    void addVm(String moref, String host) {
        vmHost.put(moref, host);
    }

    void resetVms() {
        vmHost.clear();
        vmsUnknownHost.clear();
    }

    void datacenter(String moref, String name) {
        datacenters.put(moref, new Datacenter(moref, name == null ? "" : name));
    }

    Datacenter datacenterOf(String moref) {
        return datacenters.get(moref);
    }

    void pool(BulkRow row) {
        XmlNode parent = row.val("parent");
        XmlNode ns = row.val("namespace");
        addPool(row.obj.value, "VirtualApp".equals(row.obj.type), parent == null ? null : parent.attr("type"),
                parent == null ? null : parent.text(), ns != null && !ns.text().isEmpty());
    }

    void addPool(String moref, boolean vApp, String parentType, String parent, boolean namespace) {
        pools.add(new Pool(moref, vApp, parentType, parent, namespace));
    }

    void resetPools() {
        pools.clear();
    }

    void done(boolean clusters, boolean hosts, boolean vms, boolean datacenters, boolean pools) {
        this.clustersRead = clusters;
        this.hostsRead = hosts;
        this.vmsRead = vms;
        this.datacentersRead = datacenters;
        this.poolsRead = pools;
    }

    // ------------------------------------------------------------------ reading

    public boolean clustersRead() {
        return clustersRead;
    }

    public boolean hostsRead() {
        return hostsRead;
    }

    /** True when the VM list was read (the VMs' own figures need only this). */
    public boolean vmListRead() {
        return vmsRead;
    }

    /** True when every VM's host was read too (a VM that has none is a reading): what the host and cluster figures need. */
    public boolean vmsRead() {
        return vmsRead && vmsUnknownHost.isEmpty();
    }

    public boolean datacentersRead() {
        return datacentersRead;
    }

    public boolean poolsRead() {
        return poolsRead;
    }

    public Map<String, Cluster> clusters() {
        return Collections.unmodifiableMap(clusters);
    }

    public Map<String, Host> hosts() {
        return Collections.unmodifiableMap(hosts);
    }

    /** The host's cluster, or null for none; whether the host's parent could be read is {@link #hostParentKnown}. */
    public boolean hostParentKnown(String host) {
        return !hostsParentUnknown.contains(host);
    }

    /** The MoRef of the host a VM runs on; null when it has none or it is unknown ({@link #vmHostKnown}). */
    public String hostOfVm(String vm) {
        String h = vmHost.get(vm);
        return h == null || h.isEmpty() ? null : h;
    }

    /** The VMs whose host vCenter could not be read. */
    public Set<String> vmsWithUnknownHost() {
        return Collections.unmodifiableSet(vmsUnknownHost);
    }

    /** The MoRef of the one host with this name; null when none or several have it. */
    public String hostByName(String name) {
        if (name == null || name.isEmpty()) return null;
        String found = null;
        for (Host h : hosts.values()) {
            if (!name.equals(h.name)) continue;
            if (found != null) return null;
            found = h.moref;
        }
        return found;
    }

    public boolean vmHostKnown(String vm) {
        return vmHost.containsKey(vm);
    }

    public Set<String> vms() {
        return Collections.unmodifiableSet(vmHost.keySet());
    }

    /** Every VM MoRef that was seen, those whose host could not be read too. */
    public Set<String> vmsAll() {
        Set<String> all = new HashSet<>(vmHost.keySet());
        all.addAll(vmsUnknownHost);
        return all;
    }

    public Map<String, Datacenter> datacenters() {
        return Collections.unmodifiableMap(datacenters);
    }

    public List<Pool> pools() {
        synchronized (pools) {
            return new ArrayList<>(pools);
        }
    }

    /** The hosts of a cluster, by MoRef, in a stable order. */
    public List<Host> hostsOf(String clusterMoref) {
        List<Host> out = new ArrayList<>();
        for (Host h : hosts.values()) if (clusterMoref.equals(h.cluster)) out.add(h);
        out.sort((a, b) -> a.moref.compareTo(b.moref));
        return out;
    }

    /**
     * The distinct port group names over the hosts and distributed port groups of each Datacenter, summed; null
     * when any part of it could not be read (a Datacenter's membership or distributed port groups, or the
     * port groups of any member host, or a member host with no row).
     */
    public Integer portGroupCount() {
        if (!datacentersRead || !hostsRead) return null;
        int total = 0;
        for (Datacenter d : datacenters.values()) {
            if (!d.complete()) return null;
            Set<String> names = new HashSet<>(d.dvPortGroups);
            for (String hm : d.hosts) {
                Host h = hosts.get(hm);
                if (h == null || h.portGroups == null) return null;
                names.addAll(h.portGroups);
            }
            total += names.size();
        }
        return total;
    }

    /** A summary line for the log: counts only. */
    @Override
    public String toString() {
        return "membership: " + clusters.size() + " clusters, " + hosts.size() + " hosts, " + vmHost.size()
                + " VMs, " + datacenters.size() + " Datacenters, " + pools.size() + " pools";
    }

    // ------------------------------------------------------------------ resource pools

    /**
     * The two counts per cluster MoRef: pools at level 1 to 3 and at level 2 to 5. A pool's level is the
     * number of pool-to-pool steps from it up to the cluster, with the cluster's own root pool at level 0
     * (it is not counted). Left out of every count: a vApp, a Supervisor namespace and everything below
     * either (Operations holds those as other kinds of object, so its own pool list never has them; DV-35),
     * and a pool whose chain of parents does not end at a cluster or loops. A cluster with none has no entry.
     */
    public Map<String, int[]> poolCounts() {
        Map<String, Pool> byMoref = new HashMap<>();
        List<Pool> all = pools();
        for (Pool p : all) byMoref.put(p.moref, p);
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Pool p : all) {
            int level = 0;
            String cluster = null;
            Set<String> seen = new HashSet<>();
            for (Pool cur = p; cur != null && seen.add(cur.moref); cur = byMoref.get(cur.parent)) {
                if (cur.vApp || cur.namespace) break; // this pool, or one above it, is not an Operations pool
                if ("ClusterComputeResource".equals(cur.parentType)) {
                    cluster = cur.parent;
                    break;
                }
                if (!"ResourcePool".equals(cur.parentType)) break;
                level++;
            }
            if (cluster == null) continue;
            int[] c = counts.computeIfAbsent(cluster, k -> new int[2]);
            if (level >= 1 && level <= 3) c[0]++;
            if (level >= 2 && level <= 5) c[1]++;
        }
        return counts;
    }
}
