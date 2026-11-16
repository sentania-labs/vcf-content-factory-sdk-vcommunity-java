package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.ops.PushSet;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The derived metrics at the scale of design 9.4, in a heap that is capped (design 9.3: 65 MB peak, 35 MB
 * retained between cycles): 10,000 virtual machines on 200 hosts in 20 clusters, every statistic of every one
 * of them read back, scored and queued. The Suite API is generated as it is asked, so the test holds none of the
 * data; the work runs in a child JVM with {@code -Xmx}, so a regression that holds whole answers (or one
 * object per sample) fails by running out of heap. It also counts the requests: bounded, in batches.
 */
public final class DerivedHeapTest {

    static final int VMS = 10_000;
    static final int HOSTS = 200;
    static final int CLUSTERS = 20;
    static final long NOW = 1_700_000_000_000L;
    static final String VCID = "VC-UUID";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        Process p = new ProcessBuilder(java, "-Xmx48m", "-cp", System.getProperty("java.class.path"),
                DerivedHeapTest.class.getName(), "child").redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) out.append(l).append('\n');
        }
        check(p.waitFor(300, TimeUnit.SECONDS) && p.exitValue() == 0, "the child run: " + out);
        System.out.print(out);
        System.out.println("ok  derived metrics: 10,000 VMs, 200 hosts, 20 clusters in a heap of 48 MB");
    }

    static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }

    // ------------------------------------------------------------------ an Operations that makes its answers

    /** Answers by generating: lists of resources, latest statistics, properties. Retains nothing. */
    static final class Generated implements OpsApi {
        final AtomicLong requests = new AtomicLong();
        final AtomicLong maxIdsPerRequest = new AtomicLong();

        static String rid(String kind, int n) {
            return kind + "-res-" + n;
        }

        @Override
        public String get(String path, String accept) {
            requests.incrementAndGet();
            if (path.contains("resourceKind=vSphere%20World")) return list("vSphere World", 1);
            if (path.contains("resourceKind=VMwareAdapter%20Instance")) return list("VMwareAdapter Instance", 1);
            if (path.contains("resourceKind=VirtualMachine")) return list("VirtualMachine", VMS, path);
            if (path.contains("resourceKind=HostSystem")) return list("HostSystem", HOSTS, path);
            if (path.contains("resourceKind=ClusterComputeResource")) return list("ClusterComputeResource", CLUSTERS, path);
            if (path.contains("resourceKind=Datacenter")) return list("Datacenter", 1);
            return "{\"pageInfo\":{\"totalCount\":0},\"resourceList\":[]}";
        }

        private static String list(String kind, int n) {
            return list(kind, n, "page=0");
        }

        private static String list(String kind, int n, String path) {
            int pageSize = kind.equals("VirtualMachine") ? 500 : 5000;
            int page = Integer.parseInt(path.substring(path.lastIndexOf("page=") + 5));
            StringBuilder b = new StringBuilder("{\"pageInfo\":{\"totalCount\":" + n + "},\"resourceList\":[");
            for (int i = page * pageSize; i < Math.min(n, (page + 1) * pageSize); i++) {
                if (i > page * pageSize) b.append(',');
                String moref = kind.equals("VirtualMachine") ? "vm-" + i : kind.equals("HostSystem") ? "host-" + i
                        : kind.equals("ClusterComputeResource") ? "domain-c" + i : kind.equals("Datacenter") ? "datacenter-" + i : null;
                b.append("{\"identifier\":\"").append(rid(kind, i)).append("\",\"resourceKey\":{\"resourceIdentifiers\":[");
                if (moref != null) {
                    b.append("{\"identifierType\":{\"name\":\"VMEntityObjectID\"},\"value\":\"").append(moref).append("\"},");
                }
                b.append("{\"identifierType\":{\"name\":\"VMEntityVCID\"},\"value\":\"").append(VCID).append("\"}]}}");
            }
            return b.append("]}").toString();
        }

        @Override
        public String post(String path, String body) {
            requests.incrementAndGet();
            Object req = Json.parse(body);
            List<Object> ids = Json.list(Json.get(req, path.endsWith("properties/latest/query") ? "resourceIds" : "resourceId"));
            List<Object> keys = Json.list(Json.get(req, path.endsWith("properties/latest/query") ? "propertyKeys" : "statKey"));
            maxIdsPerRequest.accumulateAndGet(ids.size(), Math::max);
            StringBuilder b = new StringBuilder(ids.size() * 400).append("{\"values\":[");
            boolean first = true;
            for (Object o : ids) {
                String id = (String) o;
                int n = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
                String one = path.endsWith("properties/latest/query") ? props(id, keys, n) : stats(id, keys, n);
                if (one == null) continue;
                if (!first) b.append(',');
                first = false;
                b.append(one);
            }
            return b.append("]}").toString();
        }

        private static String stats(String id, List<Object> keys, int n) {
            StringBuilder b = new StringBuilder("{\"resourceId\":\"").append(id).append("\",\"stat-list\":{\"stat\":[");
            boolean any = false;
            for (Object k : keys) {
                String key = (String) k;
                Double v = id.startsWith("VirtualMachine") ? vm(key, n) : id.startsWith("HostSystem") ? host(key, n) : null;
                if (v == null) continue;
                if (any) b.append(',');
                any = true;
                b.append("{\"timestamps\":[").append(NOW - 1000).append("],\"statKey\":{\"key\":\"").append(key)
                        .append("\"},\"data\":[").append(v).append("]}");
                // the platform also answers every instance of a requested key: a host's 128 CPU threads, its NICs;
                // a VM's vCPUs, disks and virtual NICs (the reader keeps only what it asked for)
                int bar = key.indexOf('|');
                int instances = id.startsWith("HostSystem") ? key.startsWith("cpu|") ? 128 : 4 : 4;
                for (int i = 0; i < instances; i++) {
                    String inst = key.substring(0, bar) + ":" + (key.startsWith("net|") ? "vmnic" : "") + i + key.substring(bar);
                    b.append(",{\"timestamps\":[").append(NOW - 1000).append("],\"statKey\":{\"key\":\"")
                            .append(inst).append("\"},\"data\":[").append(v).append("]}");
                }
            }
            return any ? b.append("]}}").toString() : null;
        }

        private static Double vm(String key, int n) {
            if (key.equals(StatKeys.GUEST_FREE_KB)) return 2_000_000.0;
            if (key.equals(StatKeys.VM_GUEST_USED)) return 4_000_000.0;
            return (n % 9) * 0.5;
        }

        private static Double host(String key, int n) {
            if (key.contains("DRAM") || key.equals(StatKeys.MEM_DRAM_FALLBACK)) return 512.0 * 1024 * 1024;
            if (key.equals(StatKeys.CPU_CAPACITY)) return 64000.0;
            if (key.equals(StatKeys.HOST_POWERED_ON)) return 1.0;
            if (key.equals(StatKeys.PKT_RX)) return 1000.0;
            if (key.startsWith("net:") || key.startsWith("mem:NVMe")) return null;
            return 20.0 + (n % 5);
        }

        private static String props(String id, List<Object> keys, int n) {
            StringBuilder b = new StringBuilder("{\"resourceId\":\"").append(id).append("\",\"property-contents\":{\"property-content\":[");
            boolean any = false;
            for (Object k : keys) {
                String key = (String) k;
                String v = null;
                if (id.startsWith("VirtualMachine")) {
                    v = key.equals(StatKeys.VM_POWER) ? "\"Powered On\"" : key.equals(StatKeys.VM_VCPUS) ? "4"
                            : key.equals(StatKeys.VM_MEMORY_KB) ? "16777216" : key.contains("cpuAllocation") ? "4000"
                            : key.contains("memoryAllocation") ? "163840" : null;
                } else if (id.startsWith("HostSystem")) {
                    v = key.equals(StatKeys.HOST_CORES) ? "32" : key.contains("hyperThread") ? "\"true\""
                            : key.equals(StatKeys.HOST_CONNECTION) ? "\"connected\"" : key.equals(StatKeys.HOST_MAINTENANCE)
                            ? "\"notInMaintenance\"" : key.equals(StatKeys.HOST_QUARANTINE) ? "\"notInQuarantine\""
                            : key.equals(StatKeys.HOST_POWER) ? "\"Powered On\"" : null;
                }
                if (v == null) continue;
                if (any) b.append(',');
                any = true;
                boolean text = v.startsWith("\"");
                b.append("{\"statKey\":\"").append(key).append(text ? "\",\"values\":[" : "\",\"data\":[").append(v).append("]}");
            }
            return any ? b.append("]}}").toString() : null;
        }

        @Override
        public PushOutcome pushBatch(Map<String, Map<String, Object>> p, Map<String, Map<String, Double>> s, long t) {
            throw new AssertionError("the derived phase only queues");
        }
    }

    static void child() throws Exception {
        Generated ops = new Generated();
        Membership m = new Membership();
        for (int c = 0; c < CLUSTERS; c++) m.addCluster("domain-c" + c, "cluster" + c);
        for (int h = 0; h < HOSTS; h++) {
            m.addHost("host-" + h, "esx" + h, "domain-c" + (h % CLUSTERS), Boolean.TRUE, new java.util.HashSet<>());
        }
        for (int v = 0; v < VMS; v++) m.addVm("vm-" + v, "host-" + (v % HOSTS));
        m.done(true, true, true, true, true);
        Map<String, OpsResourceMap> maps = new HashMap<>();
        for (String kind : new String[] {"ClusterComputeResource", "HostSystem", "VirtualMachine"}) {
            maps.put(kind, OpsResourceMap.load(ops, VCID, kind));
        }
        Failures failures = new Failures();
        PushSet ps = new PushSet(Log.NONE);
        DerivedPhase.Input in = new DerivedPhase.Input();
        in.ops = ops;
        in.vcid = VCID;
        in.vcenterResourceId = "vc-res";
        in.members = m;
        in.opsMap = maps::get;
        in.startMs = NOW;
        in.deadlineMs = NOW + 240_000;
        in.cycle = 1;
        in.ps = ps;
        in.failures = failures;

        // peak: sampled while the phase runs, from a thread of its own (an upper bound: it counts garbage not yet collected)
        Runtime rt = Runtime.getRuntime();
        long[] peak = new long[1];
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory());
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long t0 = System.currentTimeMillis();
        DerivedPhase.Result res = new DerivedPhase(Log.NONE, () -> NOW).run(in);
        long ms = System.currentTimeMillis() - t0;
        sampler.interrupt();
        System.gc();
        long retained = rt.totalMemory() - rt.freeMemory();
        System.out.println("derived: " + ps.statCount() + " series, " + ops.requests.get() + " Suite API requests, at most "
                + ops.maxIdsPerRequest.get() + " ids in one, " + ms + " ms, peak sampled " + (peak[0] >> 20)
                + " MB, retained " + (retained >> 20) + " MB");
        check(failures.isEmpty(), "no failed read: " + failures.first());
        check(res.status.get("kpi") == com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status.OK, "kpi " + res.status);
        // every VM has its two shares, and the powered-on ones their score; every host its rows; every cluster its KPIs
        check(ps.statCount() > VMS * 4 + HOSTS * 10 + CLUSTERS * 20, "the figures were produced: " + ps.statCount());
        check(ops.maxIdsPerRequest.get() <= 500, "at most 500 resources in one request");
        // 10,000 VMs: 20 pages of the list, 20 + 20 + 20 batches of VM reads, a few more for the hosts and clusters
        check(ops.requests.get() < 200, "bounded requests: " + ops.requests.get());
        check(retained < 35L * 1024 * 1024, "within the 35 MB retained budget: " + (retained >> 20) + " MB");
        check(peak[0] < 64L * 1024 * 1024, "within the peak budget: " + (peak[0] >> 20) + " MB");
    }
}
