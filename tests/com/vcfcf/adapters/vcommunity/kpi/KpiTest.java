package com.vcfcf.adapters.vcommunity.kpi;

import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure arithmetic of the derived metrics (design 7): scoring, percentiles, every KPI's rows, availability,
 * network speed, the Periodic Cluster Check's window and rule, the Cluster Delta, the Business Applications.
 * The vectors are worked examples of upstream 0.8.1 (its own tests state them as facts: inputs and the value the
 * original gives), re-expressed here; the limits are pinned against the design's Appendix A.
 */
public final class KpiTest {

    static int checks;

    static void check(boolean c, String what) {
        checks++;
        if (!c) throw new AssertionError(what);
    }

    static void near(double want, Double got, double eps, String what) {
        checks++;
        if (got == null || Math.abs(want - got) > eps) throw new AssertionError(what + ": want " + want + " got " + got);
    }

    static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want <" + want + "> got <" + got + ">");
        }
    }

    static void done(String name) {
        System.out.println("ok  " + name);
    }

    static final double GB_KB = 1024.0 * 1024.0;

    public static void main(String[] args) throws Exception {
        table();
        scoring();
        percentiles();
        clusterPerformance();
        esxiPerformance();
        vmKpis();
        clusterRisk();
        clusterConsumption();
        availability();
        networkSpeed();
        periodic();
        businessApps();
        rounding();
        sentinel();
        System.out.println("KpiTest: " + checks + " checks passed");
    }

    // ------------------------------------------------------------------ the table

    /** The 48 rows pinned against the design's Appendix A (the facts of upstream's kpi_tables.tsv). */
    static void table() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("docs/design/vcommunity-java-port-design.md"),
                StandardCharsets.UTF_8);
        boolean in = false;
        List<String[]> rows = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("## Appendix A")) in = true;
            else if (in && l.startsWith("## ")) break;
            if (!in || !l.startsWith("| ") || l.startsWith("| kpi") || l.startsWith("|---")) continue;
            String[] c = l.substring(1, l.length() - 1).split("\\|");
            for (int i = 0; i < c.length; i++) c[i] = c[i].trim();
            rows.add(c);
        }
        eq(48, rows.size(), "the design's table has 48 rows (its text says 49, counting the header line of the source file)");
        List<KpiMetric> mine = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, KpiMetric[]> g : KpiTable.groups().entrySet()) {
            for (KpiMetric m : g.getValue()) {
                mine.add(m);
                groups.add(g.getKey());
            }
        }
        eq(48, mine.size(), "the code has 48 rows");
        for (int i = 0; i < 48; i++) {
            String[] r = rows.get(i);
            KpiMetric m = mine.get(i);
            String what = "row " + (i + 1) + " " + m.name;
            eq(r[0], groups.get(i), what + ": kpi");
            eq(r[1], m.name, what + ": sub metric");
            eq(r[2], m.unit == null ? "none" : m.unit, what + ": unit");
            eq(Double.parseDouble(r[3]), m.best, what + ": best");
            eq(Double.parseDouble(r[4]), m.green, what + ": green");
            eq(Double.parseDouble(r[5]), m.yellow, what + ": yellow");
            eq(Double.parseDouble(r[6]), m.orange, what + ": orange");
            eq(Double.parseDouble(r[7]), m.zeroAt, what + ": zero at");
            eq("T".equals(r[8]), m.skipWhenGreen, what + ": skip when green");
            eq("T".equals(r[9]), m.published, what + ": published by the pak");
        }
        // and against the source table itself, the facts the design's appendix was made from
        List<String> tsv = Files.readAllLines(Paths.get("docs/design/inputs/kpi_tables.tsv"), StandardCharsets.UTF_8);
        eq(49, tsv.size(), "the source table: a header and 48 rows");
        for (int i = 0; i < 48; i++) {
            String[] c = tsv.get(i + 1).split("\t");
            KpiMetric m = mine.get(i);
            String what = "source row " + (i + 1) + " " + m.name;
            eq(c[0], groups.get(i), what + ": kpi");
            eq(c[1], m.name, what + ": sub metric");
            eq(Double.parseDouble(c[3]), m.best, what + ": best");
            eq(Double.parseDouble(c[4]), m.green, what + ": green");
            eq(Double.parseDouble(c[5]), m.yellow, what + ": yellow");
            eq(Double.parseDouble(c[6]), m.orange, what + ": orange");
            eq(Double.parseDouble(c[7]), m.zeroAt, what + ": zero at");
            eq("True".equals(c[8]), m.skipWhenGreen, what + ": skip when green");
            eq("True".equals(c[9]), m.published, what + ": published by the pak");
        }
        // the limits of every row go one way only
        for (KpiMetric m : mine) {
            double[] l = {m.best, m.green, m.yellow, m.orange, m.zeroAt};
            boolean up = l[0] <= l[1] && l[1] < l[2] && l[2] < l[3] && l[3] < l[4];
            boolean down = l[0] >= l[1] && l[1] > l[2] && l[2] > l[3] && l[3] > l[4];
            check(up || down, m.name + " limits go one way");
        }
        // the published keys of one object do not collide
        List<String> names = new ArrayList<>();
        for (KpiMetric m : KpiTable.CLUSTER_PERFORMANCE) names.add("cp|" + m.name);
        for (KpiMetric m : KpiTable.CLUSTER_RISK) names.add("cr|" + m.name);
        eq(names.size(), new java.util.HashSet<>(names).size(), "row names are unique inside a KPI");
        done("the 48 limits are the design's Appendix A and the source table");
    }

    // ------------------------------------------------------------------ scoring

    static final KpiMetric CPU_READY = new KpiMetric("CPU Ready", "%", 0, 1, 2, 4, 8, false, true);
    static final KpiMetric FREE_MEMORY = new KpiMetric("Guest Free Memory", "MB", 1024, 512, 256, 128, 0, false, true);
    static final KpiMetric CPU_RUN = new KpiMetric("CPU Run", "%", 60, 80, 90, 95, 100, false, true);
    static final KpiMetric BAD = new KpiMetric("Bad Packets", "%", 0, 0, 0.2, 0.4, 0.8, false, true);
    static final KpiMetric BALLOON = new KpiMetric("Memory Ballooned", "%", 0, 1, 2, 4, 8, true, true);

    static void s(KpiMetric m, double v, double score, double weight, String what) {
        near(score, KpiScorer.score(m, v), 1e-9, what + " score");
        near(weight, KpiScorer.weight(KpiScorer.score(m, v)), 1e-9, what + " weight");
    }

    static void scoring() {
        s(CPU_READY, 0, 100, 1, "ready 0");
        s(CPU_READY, 1, 75, 2, "ready 1");
        s(CPU_READY, 2, 50, 4, "ready 2");
        s(CPU_READY, 4, 25, 8, "ready 4");
        s(CPU_READY, 8, 0, 16, "ready 8");
        s(CPU_READY, 0.5, 87.5, Math.pow(2, 0.5), "halfway through green");
        s(CPU_READY, 3, 37.5, Math.pow(2, 2.5), "halfway through orange");
        s(CPU_READY, 5, 18.75, Math.pow(2, 3.25), "a quarter into red");
        s(CPU_READY, 500, 0, 16, "beyond zero-at");
        s(CPU_READY, -1, 100, 1, "below best");
        s(FREE_MEMORY, 1024, 100, 1, "free 1024");
        s(FREE_MEMORY, 512, 75, 2, "free 512");
        s(FREE_MEMORY, 256, 50, 4, "free 256");
        s(FREE_MEMORY, 128, 25, 8, "free 128");
        s(FREE_MEMORY, 0, 0, 16, "free 0");
        s(FREE_MEMORY, 768, 87.5, Math.pow(2, 0.5), "higher is better, halfway through green");
        s(FREE_MEMORY, 192, 37.5, Math.pow(2, 2.5), "higher is better, halfway through orange");
        s(FREE_MEMORY, 64, 12.5, Math.pow(2, 3.5), "higher is better, halfway through red");
        s(FREE_MEMORY, 8192, 100, 1, "more than best");
        s(CPU_RUN, 60, 100, 1, "best that is not 0");
        s(CPU_RUN, 70, 87.5, Math.pow(2, 0.5), "tiers of different widths: green");
        s(CPU_RUN, 80, 75, 2, "yellow starts");
        s(CPU_RUN, 85, 62.5, Math.pow(2, 1.5), "yellow");
        s(CPU_RUN, 92.5, 37.5, Math.pow(2, 2.5), "orange");
        s(CPU_RUN, 97.5, 12.5, Math.pow(2, 3.5), "red");
        s(CPU_RUN, 100, 0, 16, "zero-at");
        s(BAD, 0, 100, 1, "green as one value: exactly 0");
        s(BAD, 0.1, 62.5, Math.pow(2, 1.5), "any bad packet is yellow");
        s(BAD, 0.2, 50, 4, "bad 0.2");
        s(BAD, 0.8, 0, 16, "bad 0.8");

        KpiMetric[] two = {CPU_READY, FREE_MEMORY};
        near(26.48, KpiScorer.combine(two, vals("CPU Ready", 5.0, "Guest Free Memory", 2048.0)), 1e-9, "the weighted mean");
        near(18.75, KpiScorer.combine(two, vals("CPU Ready", 5.0)), 1e-9, "a missing row is left out");
        check(KpiScorer.combine(two, new HashMap<>()) == null, "no data, no KPI");
        KpiMetric[] rare = {CPU_READY, BALLOON};
        near(18.75, KpiScorer.combine(rare, vals("CPU Ready", 5.0, "Memory Ballooned", 0.5)), 1e-9, "skipped while green");
        near(46.89, KpiScorer.combine(rare, vals("CPU Ready", 0.0, "Memory Ballooned", 3.0)), 1e-9, "counts once it leaves green");
        near(100.0, KpiScorer.combine(new KpiMetric[] {BALLOON}, vals("Memory Ballooned", 0.0)), 1e-9, "all green: 100");
        check(KpiScorer.combine(new KpiMetric[] {BALLOON}, new HashMap<>()) == null, "no value is still no KPI");
        // the KPI does not jump at a colour boundary
        double below = KpiScorer.combine(new KpiMetric[] {CPU_READY}, vals("CPU Ready", 1.999999));
        double above = KpiScorer.combine(new KpiMetric[] {CPU_READY}, vals("CPU Ready", 2.000001));
        check(Math.abs(below - above) < 0.01, "smooth across a boundary: " + below + " " + above);
        done("scoring: tiers, weights, higher is better, green as one value, skip when green");
    }

    static Map<String, Double> vals(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Double) kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ percentiles

    static List<Double> seq(int from, int to) {
        List<Double> l = new ArrayList<>();
        if (from <= to) for (int i = from; i <= to; i++) l.add((double) i);
        else for (int i = from; i >= to; i--) l.add((double) i);
        return l;
    }

    static void percentiles() {
        check(Percentile.p90(new ArrayList<Double>()) == null, "p90 of none");
        eq(3.0, Percentile.p90(Arrays.asList(3.0)), "p90 of one");
        eq(5.0, Percentile.p90(Arrays.asList(5.0, 1.0, 2.0)), "fewer than 10: the highest");
        eq(18.0, Percentile.p90(seq(20, 1)), "20 values: the 18th lowest");
        eq(2.0, Percentile.p10(seq(20, 1)), "p10 of 20: the 2nd lowest");
        eq(40.0, Percentile.p10(Arrays.asList(80.0, 40.0, 95.0)), "p10 of 3: the lowest");
        check(Percentile.p10(new ArrayList<Double>()) == null, "p10 of none");
        List<Double> day = seq(288, 1);
        eq(274.0, Percentile.nearestRank(day, 95), "95th of 288 samples is the 274th");
        eq(288.0, Percentile.nearestRank(day, 100), "100th");
        check(Percentile.nearestRank(new ArrayList<Double>(), 95) == null, "none");
        eq(274.0, Percentile.worstTail(day, 5, true), "higher is worse: the 14 highest are ignored");
        eq(15.0, Percentile.worstTail(day, 5, false), "higher is better: the 14 lowest are ignored");
        eq(288.0, Percentile.worstTail(day, 0, true), "0: the single worst, high");
        eq(1.0, Percentile.worstTail(day, 0, false), "0: the single worst, low");
        eq(144.0, Percentile.worstTail(day, 50, true), "50 high");
        eq(145.0, Percentile.worstTail(day, 50, false), "50 low");
        check(Percentile.worstTail(new ArrayList<Double>(), 5, true) == null, "none");
        List<Double> dip = new ArrayList<>(Collections.nCopies(287, 90.0));
        dip.add(20.0);
        eq(90.0, Percentile.worstTail(dip, 5, false), "one bad sample in a quiet window is ignored");
        eq(20.0, Percentile.worstTail(dip, 0, false), "with 0 the dip decides");
        done("nearest-rank percentiles and the worst-percent rule");
    }

    // ------------------------------------------------------------------ performance KPIs

    static double[] vm(double ready, double costop, double mem, double disk) {
        return new double[] {ready, costop, mem, disk};
    }

    static void clusterPerformance() {
        // every row 0: the KPI is 100, bad packets (0 is green, excluded) are left out
        Map<String, Double> allZero = new LinkedHashMap<>();
        for (KpiMetric m : KpiTable.CLUSTER_PERFORMANCE) allZero.put(m.name, 0.0);
        near(100.0, KpiScorer.combine(KpiTable.CLUSTER_PERFORMANCE, allZero), 1e-9, "all zeros is 100");
        Map<String, Double> one = new LinkedHashMap<>(allZero);
        one.put("90th Percentile VM CPU Ready", 5.0);
        near(53.19, KpiScorer.combine(KpiTable.CLUSTER_PERFORMANCE, one), 1e-9, "one red row is not hidden by seven good ones");
        Map<String, Double> pk = new LinkedHashMap<>(allZero);
        pk.put("Highest ESXi Bad Network Packets", 0.005);
        near(94.8, KpiScorer.combine(KpiTable.CLUSTER_PERFORMANCE, pk), 1e-9, "any bad packet is yellow and counts");
        Map<String, Double> worst = new LinkedHashMap<>();
        for (KpiMetric m : KpiTable.CLUSTER_PERFORMANCE) worst.put(m.name, m.zeroAt);
        near(0.0, KpiScorer.combine(KpiTable.CLUSTER_PERFORMANCE, worst), 1e-9, "everything at its worst is 0");

        // ten VMs with ready 1..10: worst 10, the 90th percentile the 9th lowest
        List<double[]> vms = new ArrayList<>();
        for (int n = 1; n <= 10; n++) vms.add(vm(n, 0, 0, 0));
        Map<String, Double> v = ClusterPerformance.values(vms, Arrays.asList(0.0, 0.0));
        eq(10.0, v.get("Worst VM CPU Ready"), "the worst VM");
        eq(9.0, v.get("90th Percentile VM CPU Ready"), "the 90th percentile");
        // one bad VM is deep, not broad
        List<double[]> deep = new ArrayList<>();
        for (int n = 0; n < 10; n++) deep.add(vm(0, 0, 0, n == 0 ? 200 : 0));
        v = ClusterPerformance.values(deep, Arrays.asList(0.0));
        eq(200.0, v.get("Worst VM Disk Latency"), "deep");
        eq(0.0, v.get("90th Percentile VM Disk Latency"), "not broad");
        // the worst host's packets: 15 of 1000 is 1.5 %
        Map<String, Double> h1 = packets(0, 1000);
        Map<String, Double> h2 = packets(15, 1000);
        v = ClusterPerformance.values(deep, Arrays.asList(VmContention.badPacketPct(h1), VmContention.badPacketPct(h2)));
        eq(1.5, v.get("Highest ESXi Bad Network Packets"), "the spike is not averaged away");
        // a VM with no value of a statistic is left out; no VM has one: no row
        List<double[]> none = Arrays.asList(vm(Double.NaN, Double.NaN, Double.NaN, Double.NaN));
        check(VmContention.rows(none).isEmpty(), "no VM has the statistic: no rows");
        check(VmContention.rows(new ArrayList<double[]>()).isEmpty(), "no VM at all: no rows");
        // packets: a missing counter, or no packets, leave the host out
        Map<String, Double> p = packets(1, 100);
        p.remove(StatKeys.PKT_TX);
        check(VmContention.badPacketPct(p) == null, "a counter missing: no value");
        check(VmContention.badPacketPct(packets(0, 0)) == null, "no packets: no value");
        eq(0.0, VmContention.badPacketPct(packets(0, 1000)), "no bad packets is 0, a reading");
        done("Cluster Performance rows and KPI");
    }

    static Map<String, Double> packets(double dropped, double all) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : StatKeys.BAD_PACKETS) m.put(k, 0.0);
        for (String k : StatKeys.ALL_PACKETS) m.put(k, 0.0);
        m.put(StatKeys.PKT_DROPPED_RX, dropped);
        m.put(StatKeys.PKT_RX, all);
        return m;
    }

    static void esxiPerformance() {
        List<double[]> vms = new ArrayList<>();
        for (int n = 0; n < 10; n++) vms.add(vm(0, 0, 0, n == 0 ? 200 : 0));
        Map<String, Double> v = EsxiPerformance.values(vms, packets(0, 1000));
        KpiScorer.Evaluation c = KpiScorer.evaluate(KpiTable.ESXI_PERFORMANCE, v, false);
        near(53.19, c.score, 1e-9, "one bad VM: bad packets 0 are excluded, the KPI is 53.19");
        v = EsxiPerformance.values(new ArrayList<double[]>(), packets(0, 1000));
        c = KpiScorer.evaluate(KpiTable.ESXI_PERFORMANCE, v, false);
        eq(0.0, c.rows.get("ESXi Bad Network Packets"), "a host without VMs is scored on its network");
        near(100.0, c.score, 1e-9, "nothing wrong: 100");
        // 15 of 6000 is 0.25 %
        eq(0.25, EsxiPerformance.values(new ArrayList<double[]>(), packets(15, 6000)).get("ESXi Bad Network Packets"),
                "the host's own packets");
        done("ESXi Performance rows and KPI");
    }

    static Map<String, Double> vmHealthy() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String k : new String[] {StatKeys.VM_COSTOP, StatKeys.VM_READY, StatKeys.VM_OVERLAP,
            StatKeys.VM_MEM_CONTENTION, StatKeys.VM_DISK_LATENCY, StatKeys.VM_DROPPED_TX, StatKeys.VM_CPU_RUN,
            StatKeys.VM_BALLOON}) {
            m.put(k, 0.0);
        }
        m.put(StatKeys.VM_COMPRESSED, 0.0);
        m.put(StatKeys.VM_SWAPPED, 0.0);
        m.put(StatKeys.VM_GUEST_USED, 4000000.0);
        return m;
    }

    static Double vmScore(Map<String, Double> s) {
        return KpiScorer.evaluate(KpiTable.VM_PERFORMANCE, VmKpis.vmPerformanceValues(s), false).score;
    }

    static void vmKpis() {
        KpiScorer.Evaluation healthy = KpiScorer.evaluate(KpiTable.VM_PERFORMANCE, VmKpis.vmPerformanceValues(vmHealthy()), false);
        near(100.0, healthy.score, 1e-9, "a healthy VM scores 100");
        eq(0.0, healthy.rows.get("Memory Zip Swap"), "the one published row");
        Map<String, Double> s = vmHealthy();
        s.put(StatKeys.VM_READY, 10.0);
        near(46.74, vmScore(s), 1e-9, "one red row among five counted");
        s = vmHealthy();
        s.put(StatKeys.VM_CPU_RUN, 90.0);
        near(100.0, vmScore(s), 1e-9, "CPU run below 95 is excluded");
        s.put(StatKeys.VM_CPU_RUN, 98.0);
        near(69.67, vmScore(s), 1e-9, "CPU run at 98");
        s = vmHealthy();
        s.put(StatKeys.VM_DROPPED_TX, 0.25);
        near(87.99, vmScore(s), 1e-9, "any dropped packet is yellow");
        s = vmHealthy();
        s.put(StatKeys.VM_DISK_LATENCY, 30.0);
        near(66.82, vmScore(s), 1e-9, "disk latency 30 ms");
        s = vmHealthy();
        s.remove(StatKeys.VM_GUEST_USED);
        s.put(StatKeys.VM_READY, 10.0);
        near(42.8, vmScore(s), 1e-9, "a VM without Tools is scored on the stats it has");
        check(!VmKpis.vmPerformanceValues(s).containsKey("Memory Zip Swap"), "zip swap needs a Tools statistic");
        check(vmScore(new HashMap<>()) == null, "a VM with no stats has no score");
        Map<String, Double> z = new HashMap<>();
        z.put(StatKeys.VM_COMPRESSED, 10.0);
        z.put(StatKeys.VM_SWAPPED, 10.0);
        z.put(StatKeys.VM_GUEST_USED, 1000.0);
        eq(2.0, VmKpis.vmPerformanceValues(z).get("Memory Zip Swap"), "zip swap is a share of the memory the guest uses");
        z.put(StatKeys.VM_GUEST_USED, 0.0);
        check(!VmKpis.vmPerformanceValues(z).containsKey("Memory Zip Swap"), "guest memory 0: no value");

        // guest OS performance
        Map<String, Double> g = new HashMap<>();
        g.put(StatKeys.GUEST_CPU_QUEUE, 0.0);
        g.put(StatKeys.GUEST_DISK_QUEUE, 0.0);
        g.put(StatKeys.GUEST_FREE_KB, 2048.0 * 1024.0);
        g.put(StatKeys.GUEST_PAGE_OUT, 0.0);
        near(100.0, KpiScorer.evaluate(KpiTable.GUEST_OS_PERFORMANCE, VmKpis.guestValues(g), false).score, 1e-9, "healthy guest");
        g.put(StatKeys.GUEST_CPU_QUEUE, 3.0);
        near(81.8, KpiScorer.evaluate(KpiTable.GUEST_OS_PERFORMANCE, VmKpis.guestValues(g), false).score, 1e-9, "queue of 3");
        g.put(StatKeys.GUEST_CPU_QUEUE, 0.0);
        g.put(StatKeys.GUEST_FREE_KB, 192.0 * 1024.0);
        near(59.16, KpiScorer.evaluate(KpiTable.GUEST_OS_PERFORMANCE, VmKpis.guestValues(g), false).score, 1e-9, "192 MB free");
        eq(512.0, VmKpis.guestValues(Collections.singletonMap(StatKeys.GUEST_FREE_KB, 524288.0)).get("Guest Free Memory"),
                "KB to MB");
        check(VmKpis.guestValues(new HashMap<>()).isEmpty(), "no Tools, no guest rows");

        // the mean
        eq(70.5, VmKpis.vmGuestOsKpi(80.0, 61.0), "the mean of both");
        eq(19.94, VmKpis.vmGuestOsKpi(19.94, null), "VM Performance alone");
        check(VmKpis.vmGuestOsKpi(null, 61.19) == null, "no VM Performance, no KPI");
        check(VmKpis.vmGuestOsKpi(null, null) == null, "none");
        done("VM Performance, Guest OS Performance and VM Guest OS KPI");
    }

    // ------------------------------------------------------------------ Cluster Risk

    static HostInv host(String id, double cores, double threads, int vms, double vcpus, double memMb) {
        HostInv h = new HostInv(id, cores, threads, "connected", "notInMaintenance", "Powered On");
        for (int i = 0; i < vms; i++) h.vms.add(new HostInv.Vm(vcpus, memMb));
        return h;
    }

    static Map<String, Double> riskStats(double cpu, double core, double consumedGb, double balloonedGb,
            double zipGb, double cpuReserved, double memReservedGb, double vcpusAllocated, double memAllocatedGb) {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put(StatKeys.CPU_THREAD_UTIL, cpu);
        s.put(StatKeys.CPU_CORE_UTIL, core);
        s.put(StatKeys.CPU_RESERVED, cpuReserved);
        s.put(StatKeys.CPU_CAPACITY, 64000.0);
        s.put(StatKeys.MEM_CONSUMED, consumedGb * GB_KB);
        s.put(StatKeys.MEM_BALLOONED, balloonedGb * GB_KB);
        s.put(StatKeys.MEM_COMPRESSED, zipGb * GB_KB);
        s.put(StatKeys.MEM_SWAPPED, 0.0);
        s.put(StatKeys.MEM_RESERVED, memReservedGb * GB_KB);
        s.put(StatKeys.MEM_DRAM_CAPACITY, 512.0 * GB_KB);
        s.put(StatKeys.VCPUS_ALLOCATED, vcpusAllocated);
        s.put(StatKeys.MEM_ALLOCATED, memAllocatedGb * GB_KB);
        return s;
    }

    static Map<String, Double> healthyHost() {
        return riskStats(40, 80, 256, 0, 0, 0, 0, 16, 64);
    }

    static KpiScorer.Evaluation risk(List<HostInv> hosts, Map<String, Map<String, Double>> stats, MonsterVm monster) {
        Map<String, Double> raw = ClusterRisk.clusterValues(hosts, h -> stats.get(h.id), monster);
        return KpiScorer.evaluate(KpiTable.CLUSTER_RISK, raw, true);
    }

    static void clusterRisk() {
        Map<String, Map<String, Double>> healthy = new HashMap<>();
        healthy.put("host-1", healthyHost());
        healthy.put("host-2", healthyHost());
        List<HostInv> hosts = Arrays.asList(host("host-1", 32, 64, 4, 4, 16384), host("host-2", 32, 64, 4, 4, 16384));
        KpiScorer.Evaluation r = risk(hosts, healthy, MonsterVm.DEFAULT);
        eq(1.0, r.rows.get("Worst ESXi CPU Imbalance"), "one thread per busy core");
        eq(0.25, r.rows.get("Highest CPU Monster VM to Host Ratio"), "4 vCPU against half of 32 cores is 0.25");
        eq(0.08, r.rows.get("Highest Memory Monster VM to Host Ratio"), "16 GB against 40 % of 512 GB");
        check(!r.rows.containsKey("Worst ESXi Memory Tiering Latency"), "no memory tiering: no row");
        near(1.82, r.score, 1e-9, "a healthy cluster is a low risk, published as 100 minus the health");

        // the worst host is taken
        Map<String, Map<String, Double>> st = new HashMap<>(healthy);
        st.put("host-2", riskStats(40, 80, 256, 10.24, 0, 0, 0, 16, 64));
        eq(2.0, risk(hosts, st, MonsterVm.DEFAULT).rows.get("Worst ESXi Memory Ballooned"), "the worst host");

        // a busy host leaves its excluded green
        st = new HashMap<>();
        st.put("host-1", riskStats(90, 100, 256, 0, 0, 0, 0, 16, 64));
        st.put("host-2", riskStats(30, 60, 256, 0, 0, 0, 0, 16, 64));
        r = risk(hosts, st, MonsterVm.DEFAULT);
        eq(90.0, r.rows.get("Highest ESXi CPU Thread Utilization"), "the busiest host");
        eq(1.8, r.rows.get("Worst ESXi CPU Imbalance"), "57.6 threads on 32 cores");
        near(67.89, r.score, 1e-9, "the KPI with a busy host");

        // tiering latency counts only on a host that has memory tiering
        st = new HashMap<>(healthy);
        Map<String, Double> tiered = healthyHost();
        tiered.put(StatKeys.MEM_TIERING_LATENCY, 300.0);
        st.put("host-2", tiered);
        eq(300.0, risk(hosts, st, MonsterVm.DEFAULT).rows.get("Worst ESXi Memory Tiering Latency"), "tiering latency");

        // formulas
        HostInv big = host("b", 32, 64, 0, 0, 0);
        Map<String, Double> s = new HashMap<>();
        s.put(StatKeys.CPU_THREAD_UTIL, 40.0);
        s.put(StatKeys.CPU_CORE_UTIL, 80.0);
        eq(1.0, ClusterRisk.hostValues(big, s, MonsterVm.DEFAULT).get("Worst ESXi CPU Imbalance"), "imbalance 1");
        s.put(StatKeys.CPU_THREAD_UTIL, 50.0);
        s.put(StatKeys.CPU_CORE_UTIL, 50.0);
        eq(2.0, ClusterRisk.hostValues(big, s, MonsterVm.DEFAULT).get("Worst ESXi CPU Imbalance"), "both threads");
        s.put(StatKeys.CPU_THREAD_UTIL, 90.0);
        s.put(StatKeys.CPU_CORE_UTIL, 100.0);
        eq(1.8, ClusterRisk.hostValues(big, s, MonsterVm.DEFAULT).get("Worst ESXi CPU Imbalance"), "1.8");
        s.put(StatKeys.CPU_THREAD_UTIL, 0.0);
        s.put(StatKeys.CPU_CORE_UTIL, 0.0);
        check(!ClusterRisk.hostValues(big, s, MonsterVm.DEFAULT).containsKey("Worst ESXi CPU Imbalance"), "idle host");
        s.put(StatKeys.CPU_THREAD_UTIL, 40.0);
        s.remove(StatKeys.CPU_CORE_UTIL);
        check(!ClusterRisk.hostValues(big, s, MonsterVm.DEFAULT).containsKey("Worst ESXi CPU Imbalance"), "core missing");
        HostInv noCores = new HostInv("n", null, null, null, null, null);
        s.put(StatKeys.CPU_CORE_UTIL, 80.0);
        check(!ClusterRisk.hostValues(noCores, s, MonsterVm.DEFAULT).containsKey("Worst ESXi CPU Imbalance"), "cores unknown");

        // overcommit uses what Operations allocated to the powered-on VMs
        s = new HashMap<>();
        s.put(StatKeys.VCPUS_ALLOCATED, 64.0);
        s.put(StatKeys.MEM_ALLOCATED, 1024.0 * GB_KB);
        s.put(StatKeys.MEM_DRAM_CAPACITY, 512.0 * GB_KB);
        Map<String, Double> o = ClusterRisk.hostValues(big, s, MonsterVm.DEFAULT);
        eq(2.0, o.get("Highest ESXi CPU Overcommit Ratio"), "64 vCPU on 32 cores");
        eq(2.0, o.get("Highest ESXi Memory Overcommit Ratio"), "1 TB on 512 GB");
        check(!ClusterRisk.hostValues(noCores, s, MonsterVm.DEFAULT).containsKey("Highest ESXi CPU Overcommit Ratio"), "no cores");
        check(!ClusterRisk.hostValues(big, new HashMap<>(), MonsterVm.DEFAULT).containsKey("Highest ESXi CPU Overcommit Ratio"), "no stat");

        // the monster VM
        HostInv h64 = host("m", 64, 128, 0, 0, 0);
        h64.vms.add(new HostInv.Vm(32.0, 1024.0));
        eq(1.0, ClusterRisk.hostValues(h64, new HashMap<>(), MonsterVm.DEFAULT).get("Highest CPU Monster VM to Host Ratio"),
                "32 vCPU is half of 64 cores; threads do not count");
        HostInv h64b = host("m", 64, 128, 0, 0, 0);
        h64b.vms.add(new HostInv.Vm(4.0, 256.0 * 1024));
        Map<String, Double> tier = new HashMap<>();
        tier.put(StatKeys.MEM_DRAM_CAPACITY, 768.0 * GB_KB);
        eq(1.0, ClusterRisk.hostValues(h64b, tier, MonsterVm.DEFAULT).get("Highest Memory Monster VM to Host Ratio"),
                "40 % of 768 GB is more than 256 GB: 256 GB is the monster size");
        HostInv h32 = host("m", 32, 64, 0, 0, 0);
        h32.vms.add(new HostInv.Vm(4.0, 204.8 * 1024));
        Map<String, Double> t512 = new HashMap<>();
        t512.put(StatKeys.MEM_DRAM_CAPACITY, 512.0 * GB_KB);
        eq(1.0, ClusterRisk.hostValues(h32, t512, MonsterVm.DEFAULT).get("Highest Memory Monster VM to Host Ratio"),
                "40 % of 512 GB is the monster size");
        MonsterVm own = MonsterVm.of(25, 20, 128);
        HostInv h64c = host("m", 64, 128, 0, 0, 0);
        h64c.vms.add(new HostInv.Vm(32.0, 1024.0));
        eq(2.0, ClusterRisk.hostValues(h64c, new HashMap<>(), own).get("Highest CPU Monster VM to Host Ratio"), "25 % of 64 cores");
        HostInv h64d = host("m", 64, 128, 0, 0, 0);
        h64d.vms.add(new HostInv.Vm(4.0, 128.0 * 1024));
        eq(1.0, ClusterRisk.hostValues(h64d, tier, own).get("Highest Memory Monster VM to Host Ratio"), "128 GB at most");
        eq(new MonsterVm(0.5, 0.4, 256.0 * 1024).memoryMinMb, MonsterVm.DEFAULT.memoryMinMb, "defaults");
        check(ClusterRisk.hostValues(host("e", 32, 64, 0, 0, 0), new HashMap<>(), MonsterVm.DEFAULT)
                .get("Highest CPU Monster VM to Host Ratio") == null, "no VM, no monster row");

        // the DRAM falls back to the provisioned memory, and a 0 is no value
        Map<String, Double> d = new HashMap<>();
        d.put(StatKeys.MEM_DRAM_CAPACITY, 768.0);
        d.put(StatKeys.MEM_DRAM_FALLBACK, 1536.0);
        eq(768.0, ClusterRisk.dramKb(d), "the DRAM statistic");
        d.remove(StatKeys.MEM_DRAM_CAPACITY);
        eq(1536.0, ClusterRisk.dramKb(d), "the fallback");
        d.put(StatKeys.MEM_DRAM_CAPACITY, 0.0);
        eq(1536.0, ClusterRisk.dramKb(d), "a 0 falls back");
        check(ClusterRisk.dramKb(new HashMap<>()) == null, "none");

        // DV-109: consumed memory is measured against DRAM plus the NVMe tier on a host that has the tier
        Map<String, Double> t = new HashMap<>();
        t.put(StatKeys.MEM_DRAM_CAPACITY, 131784480.0);
        t.put(StatKeys.NVME_TIER_SIZE, 131784480.0);
        t.put(StatKeys.MEM_DRAM_FALLBACK, 263570560.0);
        eq(263568960.0, ClusterRisk.memoryCapacityKb(t), "DRAM plus the NVMe tier");
        t.put(StatKeys.MEM_CONSUMED, 151967904.0);
        HostInv th = host("t", 32, 64, 0, 0, 0);
        near(57.66, PyRound.round2(ClusterRisk.hostValues(th, t, MonsterVm.DEFAULT).get("Highest ESXi Memory Consumed")),
                1e-9, "a tiered host cannot pass 100 % on consumed memory");
        // every memory row divides by the same total (DV-109)
        t.put(StatKeys.MEM_BALLOONED, 13178448.0);
        eq(5.0, ClusterRisk.hostValues(th, t, MonsterVm.DEFAULT).get("Worst ESXi Memory Ballooned"), "ballooned: DRAM plus the tier");
        t.put(StatKeys.MEM_RESERVED, 26356896.0);
        eq(10.0, ClusterRisk.hostValues(th, t, MonsterVm.DEFAULT).get("Highest ESXi Memory Reservation"), "reservation, same total");
        t.put(StatKeys.MEM_ALLOCATED, 263568960.0);
        eq(1.0, ClusterRisk.hostValues(th, t, MonsterVm.DEFAULT).get("Highest ESXi Memory Overcommit Ratio"), "overcommit, same total");
        // a 4:1 tier (the wld hosts: 66,904,440 KB of DRAM, 267,617,760 KB of tier), so a 2 x DRAM error would show
        Map<String, Double> f4 = new HashMap<>();
        f4.put(StatKeys.MEM_DRAM_CAPACITY, 66904440.0);
        f4.put(StatKeys.NVME_TIER_SIZE, 267617760.0);
        eq(334522200.0, ClusterRisk.memoryCapacityKb(f4), "DRAM plus a 4:1 tier");
        f4.put(StatKeys.MEM_CONSUMED, 66904440.0);
        eq(20.0, ClusterRisk.hostValues(th, f4, MonsterVm.DEFAULT).get("Highest ESXi Memory Consumed"), "20 %, not 50 or 100");
        // no tier size, or a 0, or the tiering switched off: DRAM, as before
        Map<String, Double> flat = new HashMap<>(t);
        flat.remove(StatKeys.NVME_TIER_SIZE);
        eq(131784480.0, ClusterRisk.memoryCapacityKb(flat), "no tier statistic");
        flat.put(StatKeys.NVME_TIER_SIZE, 0.0);
        eq(131784480.0, ClusterRisk.memoryCapacityKb(flat), "a tier of 0");
        near(115.32, PyRound.round2(ClusterRisk.hostValues(th, flat, MonsterVm.DEFAULT).get("Highest ESXi Memory Consumed")),
                1e-9, "a host without tiering gives exactly the DRAM-only value");
        // the DRAM fell back to the provisioned memory, which already holds both tiers: nothing is added
        Map<String, Double> fb = new HashMap<>();
        fb.put(StatKeys.MEM_DRAM_FALLBACK, 263570560.0);
        fb.put(StatKeys.NVME_TIER_SIZE, 131784480.0);
        eq(263570560.0, ClusterRisk.memoryCapacityKb(fb), "the fallback is not added to");
        check(ClusterRisk.memoryCapacityKb(new HashMap<>()) == null, "no capacity, no value");

        // zip and swap add per host, then the highest host
        Map<String, Double> h1 = new HashMap<>();
        h1.put(StatKeys.MEM_COMPRESSED, 3 * GB_KB);
        h1.put(StatKeys.MEM_SWAPPED, 2 * GB_KB);
        h1.put(StatKeys.MEM_DRAM_CAPACITY, 100 * GB_KB);
        Map<String, Double> h2 = new HashMap<>();
        h2.put(StatKeys.MEM_COMPRESSED, 4 * GB_KB);
        h2.put(StatKeys.MEM_SWAPPED, 0.0);
        h2.put(StatKeys.MEM_DRAM_CAPACITY, 100 * GB_KB);
        Map<String, Map<String, Double>> zs = new HashMap<>();
        zs.put("a", h1);
        zs.put("b", h2);
        Map<String, Double> zr = ClusterRisk.clusterValues(Arrays.asList(host("a", 8, 16, 0, 0, 0), host("b", 8, 16, 0, 0, 0)),
                h -> zs.get(h.id), MonsterVm.DEFAULT);
        eq(5.0, zr.get("Worst ESXi Memory Zip Swap"), "5 GB of 100 GB on the worse host");
        done("Cluster Risk rows and KPI");
    }

    // ------------------------------------------------------------------ Cluster Consumption

    static HostInv chost(String id, double cores, String maintenance) {
        return new HostInv(id, cores, cores * 2, "connected", maintenance, "Powered On");
    }

    static Map<String, Double> cstats(double cpu, double dramGb, double consumedGb, double ballGb) {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put(StatKeys.CPU_THREAD_UTIL, cpu);
        s.put(StatKeys.MEM_DRAM_CAPACITY, dramGb * GB_KB);
        s.put(StatKeys.MEM_CONSUMED, consumedGb * GB_KB);
        s.put(StatKeys.MEM_BALLOONED, ballGb * GB_KB);
        s.put(StatKeys.MEM_COMPRESSED, 0.0);
        s.put(StatKeys.MEM_SWAPPED, 0.0);
        return s;
    }

    static Map<String, Double> cluster(double usable) {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put(StatKeys.CPU_TOTAL, 100000.0);
        c.put(StatKeys.CPU_USABLE, 100000.0 * usable);
        c.put(StatKeys.MEM_TOTAL, 1000.0 * GB_KB);
        c.put(StatKeys.MEM_USABLE, 1000.0 * GB_KB * usable);
        return c;
    }

    static void clusterConsumption() {
        HostInv h1 = chost("host-1", 32, "notInMaintenance");
        HostInv h2 = chost("host-2", 16, "notInMaintenance");
        List<HostInv> hosts = Arrays.asList(h1, h2);
        Map<String, Map<String, Double>> st = new HashMap<>();
        st.put("host-1", cstats(40, 512, 256, 5.12));
        st.put("host-2", cstats(70, 256, 230.4, 0));
        Map<String, Double> v = ClusterConsumption.values(hosts, h -> st.get(h.id), cluster(1.0));
        eq(50.0, v.get("Cluster-wide ESXi CPU Thread Utilization"), "added up, not averaged: 48 of 96 threads");
        eq(63.33, PyRound.round2(v.get("Cluster-wide ESXi Memory Consumed")), "memory");
        eq(0.67, PyRound.round2(v.get("Cluster-wide ESXi Memory Ballooned")), "ballooned");
        eq(0.0, v.get("Cluster-wide ESXi Memory Zip Swap"), "zip swap");
        check(!v.containsKey("Cluster Memory Tiering Consumption"), "no tiering: no data");
        // DV-109: with a 1:1 NVMe tier on each host the same consumption is against twice the capacity
        Map<String, Map<String, Double>> tiered = new HashMap<>();
        for (String id : new String[] {"host-1", "host-2"}) {
            Map<String, Double> c = new HashMap<>(st.get(id));
            c.put(StatKeys.NVME_TIER_SIZE, c.get(StatKeys.MEM_DRAM_CAPACITY));
            tiered.put(id, c);
        }
        Map<String, Double> tierV = ClusterConsumption.values(hosts, h -> tiered.get(h.id), cluster(1.0));
        eq(31.67, PyRound.round2(tierV.get("Cluster-wide ESXi Memory Consumed")), "memory against DRAM plus the tier");
        eq(0.33, PyRound.round2(tierV.get("Cluster-wide ESXi Memory Ballooned")), "ballooned: the same total");

        v = ClusterConsumption.values(hosts, h -> st.get(h.id), cluster(0.8));
        eq(62.5, PyRound.round2(v.get("Cluster-wide ESXi CPU Thread Utilization")), "against usable capacity");
        eq(79.17, PyRound.round2(v.get("Cluster-wide ESXi Memory Consumed")), "memory against usable");
        eq(0.83, PyRound.round2(v.get("Cluster-wide ESXi Memory Ballooned")), "ballooned against usable");
        KpiScorer.Evaluation c = KpiScorer.evaluate(KpiTable.CLUSTER_CONSUMPTION, v, true);
        near(34.53, c.score, 1e-9, "the KPI is 100 minus the health");

        // CPU and memory have their own usable capacity
        Map<String, Double> cs = cluster(1.0);
        cs.put(StatKeys.CPU_USABLE, 50000.0);
        v = ClusterConsumption.values(hosts, h -> st.get(h.id), cs);
        eq(100.0, v.get("Cluster-wide ESXi CPU Thread Utilization"), "half the CPU is usable");
        eq(63.33, PyRound.round2(v.get("Cluster-wide ESXi Memory Consumed")), "memory unchanged");

        // zip and swap add
        Map<String, Map<String, Double>> zs = new HashMap<>(st);
        Map<String, Double> z1 = cstats(40, 512, 256, 0);
        z1.put(StatKeys.MEM_COMPRESSED, 1.0 * GB_KB);
        z1.put(StatKeys.MEM_SWAPPED, 0.92 * GB_KB);
        zs.put("host-1", z1);
        zs.put("host-2", cstats(70, 256, 128, 0));
        eq(0.25, PyRound.round2(ClusterConsumption.values(hosts, h -> zs.get(h.id), cluster(1.0)).get("Cluster-wide ESXi Memory Zip Swap")), "zip swap");

        // the NVMe tier only
        Map<String, Map<String, Double>> ts = new HashMap<>(st);
        Map<String, Double> t1 = new HashMap<>(st.get("host-1"));
        t1.put(StatKeys.NVME_TIER_SIZE, 256.0 * GB_KB);
        t1.put(StatKeys.NVME_TIER_USAGE, 20.0 * 1024.0);
        ts.put("host-1", t1);
        Map<String, Double> tv = ClusterConsumption.values(hosts, h -> ts.get(h.id), cluster(0.8));
        eq(9.77, PyRound.round2(tv.get("Cluster Memory Tiering Consumption")), "20 GB of 204.8 GB");
        eq(59.37, PyRound.round2(tv.get("Cluster-wide ESXi Memory Consumed")), "consumed is over DRAM plus the tier (DV-109): 486.4 of 819.2 GB");
        eq(0.62, PyRound.round2(tv.get("Cluster-wide ESXi Memory Ballooned")), "the other memory rows use the same total (DV-109)");

        // no usable capacity: no value, a 0 is full, a missing one is no data
        for (Map<String, Double> bad : Arrays.asList(new HashMap<String, Double>(), cluster(0.0))) {
            Map<String, Double> r = ClusterConsumption.values(hosts, h -> st.get(h.id), bad);
            check(!r.containsKey("Cluster-wide ESXi Memory Consumed"), "no memory row without usable capacity");
        }
        check(ClusterConsumption.usableIsZero(cluster(0.0)), "usable 0 is full");
        Map<String, Double> cpuOnly = cluster(1.0);
        cpuOnly.put(StatKeys.CPU_USABLE, 0.0);
        check(ClusterConsumption.usableIsZero(cpuOnly), "CPU alone is enough");
        check(!ClusterConsumption.usableIsZero(new HashMap<>()), "not reported: no data");
        check(!ClusterConsumption.usableIsZero(cluster(0.8)), "usable");

        // a host in maintenance is left out
        HostInv h3 = chost("host-3", 32, "inMaintenance");
        Map<String, Map<String, Double>> ms = new HashMap<>(st);
        ms.put("host-3", cstats(0, 512, 0, 0));
        Map<String, Double> with = ClusterConsumption.values(Arrays.asList(h1, h2, h3), h -> ms.get(h.id), cluster(0.8));
        eq(ClusterConsumption.values(hosts, h -> st.get(h.id), cluster(0.8)), with, "a host in maintenance adds nothing");

        // a row is never a partial sum
        Map<String, Map<String, Double>> ps = new HashMap<>(st);
        Map<String, Double> p2 = new HashMap<>(st.get("host-2"));
        p2.remove(StatKeys.MEM_BALLOONED);
        ps.put("host-2", p2);
        Map<String, Double> pv = ClusterConsumption.values(hosts, h -> ps.get(h.id), cluster(1.0));
        check(!pv.containsKey("Cluster-wide ESXi Memory Ballooned"), "a host without the statistic: no row");
        eq(63.33, PyRound.round2(pv.get("Cluster-wide ESXi Memory Consumed")), "the other DRAM rows are unaffected");
        // a host without data is left out
        Map<String, Map<String, Double>> one = new HashMap<>();
        one.put("host-1", st.get("host-1"));
        Map<String, Double> ov = ClusterConsumption.values(hosts, h -> one.getOrDefault(h.id, new HashMap<>()), cluster(1.0));
        eq(40.0, ov.get("Cluster-wide ESXi CPU Thread Utilization"), "host-2 has no data");
        eq(50.0, ov.get("Cluster-wide ESXi Memory Consumed"), "memory of host-1 alone");

        // a full cluster is red
        Map<String, Map<String, Double>> full = new HashMap<>();
        full.put("host-1", cstats(95, 512, 500, 40));
        full.put("host-2", cstats(95, 256, 250, 20));
        Double fullKpi = KpiScorer.evaluate(KpiTable.CLUSTER_CONSUMPTION,
                ClusterConsumption.values(hosts, h -> full.get(h.id), cluster(0.8)), true).score;
        check(fullKpi > 75.0, "a full cluster is red: " + fullKpi);

        // which hosts can run VMs
        String[][] cases = {
            {"connected", "notInMaintenance", "Powered On", "false"},
            {"connected", "enteringMaintenance", "Powered On", "false"},
            {"connected", "inMaintenance", "Powered On", "true"},
            {"disconnected", "notInMaintenance", "Powered On", "true"},
            {"notResponding", "notInMaintenance", "Powered On", "true"},
            {"connected", "notInMaintenance", "Powered Off", "true"},
            {"connected", "notInMaintenance", "poweredOff", "true"},
            {"connected", "notInMaintenance", "Standby", "true"},
            {null, null, null, "false"}};
        for (String[] k : cases) {
            HostInv hi = new HostInv("h", 32.0, 64.0, k[0], k[1], k[2]);
            eq(Boolean.valueOf(k[3]), ClusterConsumption.cannotHostVms(hi, 40.0), "gone: " + Arrays.toString(k));
        }
        check(ClusterConsumption.cannotHostVms(chost("h", 32, "notInMaintenance"), null), "no current value is gone");
        done("Cluster Consumption rows and KPI");
    }

    // ------------------------------------------------------------------ availability and network

    static Map<String, String> withoutQuarantine(Map<String, String> m) {
        Map<String, String> c = new HashMap<>(m);
        c.remove(StatKeys.HOST_QUARANTINE);
        return c;
    }

    static void availability() {
        Map<String, String> up = new HashMap<>();
        up.put(StatKeys.HOST_CONNECTION, "connected");
        up.put(StatKeys.HOST_MAINTENANCE, "notInMaintenance");
        up.put(StatKeys.HOST_QUARANTINE, "notInQuarantine");
        eq(100.0, Availability.ofHost(1.0, up, true), "fully available");
        Map<String, String> dis = new HashMap<>(up);
        dis.put(StatKeys.HOST_CONNECTION, "disconnected");
        eq(75.0, Availability.ofHost(1.0, dis, true), "disconnected is not connected");
        Map<String, String> mnt = new HashMap<>(up);
        mnt.put(StatKeys.HOST_MAINTENANCE, "inMaintenance");
        eq(75.0, Availability.ofHost(1.0, mnt, true), "in maintenance");
        Map<String, String> nr = new HashMap<>(up);
        nr.put(StatKeys.HOST_CONNECTION, "notResponding");
        eq(50.0, Availability.ofHost(0.0, nr, true), "each condition is 25");
        eq(0.0, Availability.ofHost(null, new HashMap<>(), true), "no data is 0");
        // DV-110: a host that reports the quarantine property behaves as before
        Map<String, String> inq = new HashMap<>(up);
        inq.put(StatKeys.HOST_QUARANTINE, "inQuarantine");
        eq(75.0, Availability.ofHost(1.0, inq, true), "in quarantine still fails one of four");
        // without the property (8.18): left out, three of three is 100, and it is neither met nor failed
        Map<String, String> old = new HashMap<>(up);
        old.remove(StatKeys.HOST_QUARANTINE);
        eq(100.0, Availability.ofHost(1.0, old, false), "no quarantine property: 3 of 3");
        eq(66.67, PyRound.round2(Availability.ofHost(1.0, withoutQuarantine(mnt), false)), "no property and in maintenance: 2 of 3");
        eq(66.67, PyRound.round2(Availability.ofHost(0.0, old, false)), "no property and powered off: 2 of 3");
        eq(0.0, Availability.ofHost(null, new HashMap<>(), false), "nothing read at all is still 0");

        // DV-110: the property exists on this version (some host carries it) but this host has no value: not met
        eq(75.0, Availability.ofHost(1.0, old, true), "a host with no value where the version has the property: 3 of 4");
        check(Availability.quarantineExists(Arrays.asList(old, up)), "one host carrying it settles it for the cycle");
        check(!Availability.quarantineExists(Arrays.asList(old, new HashMap<String, String>())), "no host carries it");
        check(!Availability.quarantineExists(new ArrayList<Map<String, String>>()), "no host answered");

        eq(100.0, Availability.ofCluster(Arrays.asList(100.0, 100.0, 100.0, 100.0), 4.0, 0.0), "N+0 healthy");
        eq(93.75, Availability.ofCluster(Arrays.asList(100.0, 100.0, 100.0, 75.0), 4.0, 0.0), "N+0 counts every host");
        eq(100.0, Availability.ofCluster(Arrays.asList(100.0, 100.0, 100.0, 0.0), 4.0, 1.0), "N+1, one host down");
        eq(66.67, PyRound.round2(Availability.ofCluster(Arrays.asList(100.0, 100.0, 0.0, 0.0), 4.0, 1.0)), "N+1, two down");
        eq(91.67, PyRound.round2(Availability.ofCluster(Arrays.asList(100.0, 100.0, 75.0, 75.0), 4.0, 1.0)), "degraded counts");
        List<Double> ten = new ArrayList<>(Collections.nCopies(8, 100.0));
        ten.addAll(Arrays.asList(0.0, 0.0));
        eq(100.0, Availability.ofCluster(ten, 10.0, 2.0), "N+2, two down");
        List<Double> mixed = new ArrayList<>(Collections.nCopies(7, 100.0));
        mixed.addAll(Collections.nCopies(3, 50.0));
        eq(93.75, Availability.ofCluster(mixed, 10.0, 2.0), "N+2: (850 - 100) / 8");
        eq(100.0, Availability.ofCluster(Arrays.asList(100.0, 100.0, 100.0, 100.0), 4.0, 1.0), "capped at 100");
        eq(75.0, Availability.ofCluster(Arrays.asList(100.0, 100.0, 100.0, 0.0), 4.0, null), "no failover level is k = 0");
        eq(75.0, Availability.ofCluster(Arrays.asList(100.0, 100.0, 100.0), 4.0, 0.0), "a counted host not found is 0");
        check(Availability.ofCluster(Arrays.asList(100.0, 100.0), 2.0, 2.0) == null, "HA tolerates every host: no value");
        eq(100.0, Availability.ofCluster(Arrays.asList(100.0, 100.0), null, null), "no host count: the hosts found");
        eq(91.67, PyRound.round2(Availability.worst(Arrays.asList(91.67, null, 100.0))), "worst leaves out a cluster with no value");
        check(Availability.worst(Arrays.asList((Double) null)) == null, "none");
        done("Operational Availability");
    }

    static void networkSpeed() {
        eq(100.0, NetworkSpeed.degradation(nic("vmnic0", 10000, "vmnic1", 10000), nic("vmnic0", 10000, "vmnic1", 10000)), "none");
        eq(55.0, PyRound.round2(NetworkSpeed.degradation(nic("vmnic0", 1000, "vmnic1", 10000),
                nic("vmnic0", 10000, "vmnic1", 10000))), "one NIC slow");
        eq(100.0, NetworkSpeed.degradation(nic("vmnic0", 0, "vmnic1", 10000), nic("vmnic0", 10000, "vmnic1", 10000)),
                "a NIC that is down is left out");
        eq(100.0, NetworkSpeed.degradation(nic("vmnic0", 1000, "vmnic1", 10000), nic("vmnic1", 10000)),
                "a NIC without a configured speed is left out");
        check(NetworkSpeed.degradation(nic("vmnic0", 10000), nic("vmnic0", 0)) == null, "nothing to compare");
        check(NetworkSpeed.degradation(new LinkedHashMap<>(), new LinkedHashMap<>()) == null, "no NIC");
        eq(100.0, NetworkSpeed.degradation(nic("vmnic0", 25000), nic("vmnic0", 10000)), "capped at 100");
        check(NetworkSpeed.degraded(55.0) && !NetworkSpeed.degraded(100.0) && !NetworkSpeed.degraded(null), "degraded");
        eq(10000.0, NetworkSpeed.parseSpeed(" 10000 "), "text with spaces");
        eq(1000.0, NetworkSpeed.parseSpeed("1000.0"), "a decimal");
        for (String bad : new String[] {null, "", "abc", "0", "-1", "NaN", "Infinity", "10 Gbps"}) {
            check(NetworkSpeed.parseSpeed(bad) == null, "no value for " + bad);
        }
        eq("net:vmnic3|speed", NetworkSpeed.speedKey("vmnic3"), "actual speed key");
        eq("net:vmnic3|configuredSpeed", NetworkSpeed.configuredKey("vmnic3"), "configured speed key");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("net:vmnic0|speed", "1000");
        props.put("net:vmnic0|configuredSpeed", "10000");
        props.put("net:vmnic1|speed", "x");
        props.put("net:vmnic1|configuredSpeed", "10000");
        NetworkSpeed.Speeds sp = NetworkSpeed.read(new java.util.TreeSet<>(Arrays.asList("vmnic0", "vmnic1", "vmnic2")), props);
        eq(1, sp.actual.size(), "only the NIC with a usable speed");
        eq(2, sp.configured.size(), "a configured speed alone is kept, the pair is what counts");
        eq(10.0, NetworkSpeed.degradation(sp.actual, sp.configured), "one NIC at a tenth");
        done("Network Speed Degradation");
    }

    static Map<String, Double> nic(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    // ------------------------------------------------------------------ periodic check

    static final long NOW = 1791797400000L; // 2026-10-12T09:30:00Z

    static void periodic() {
        eq(Long.valueOf(java.time.Instant.parse("2026-10-12T09:30:00Z").toEpochMilli()), Long.valueOf(NOW), "the test clock");
        long t0600 = java.time.Instant.parse("2026-10-12T06:00:00Z").toEpochMilli();
        long y1200 = java.time.Instant.parse("2026-10-11T12:00:00Z").toEpochMilli();
        eq(Long.valueOf(t0600), Long.valueOf(PeriodicWindow.at(NOW, 6, 0, 24).endMs), "06:00 has passed today");
        eq(Long.valueOf(y1200), Long.valueOf(PeriodicWindow.at(NOW, 12, 0, 24).endMs), "12:00 has not: yesterday's");
        eq(Long.valueOf(NOW), Long.valueOf(PeriodicWindow.at(NOW, 9, 30, 24).endMs), "a window ending now is the latest");
        eq(Long.valueOf(t0600), Long.valueOf(PeriodicWindow.at(NOW, 0, 0, 6).endMs), "every 6 hours from 00:00");
        eq(Long.valueOf(java.time.Instant.parse("2026-10-12T09:15:00Z").toEpochMilli()),
                Long.valueOf(PeriodicWindow.at(NOW, 0, 15, 1).endMs), "every hour from 00:15");
        eq(Long.valueOf(java.time.Instant.parse("2026-10-12T02:00:00Z").toEpochMilli()),
                Long.valueOf(PeriodicWindow.at(NOW, 10, 0, 8).endMs), "every 8 hours from 10:00");
        PeriodicWindow w6 = PeriodicWindow.at(NOW, 0, 0, 6);
        eq(Long.valueOf(6 * 3600_000L), Long.valueOf(w6.endMs - w6.beginMs), "the window is as long as the period");

        // which clusters are due
        PeriodicWindow w = PeriodicWindow.at(NOW, 0, 0, 24);
        double begin = w.beginMs;
        double end = w.endMs;
        Map<String, Double> m = new HashMap<>();
        m.put(StatKeys.CLUSTER_PERFORMANCE_SCORE, begin + 500);
        check(w.awaitsCheck(m), "KPI data in the window, nothing calculated: due");
        m.put(StatKeys.CLOSE_FOR_BUSINESS, end + 100);
        check(!w.awaitsCheck(m), "calculated for this window: not due");
        m.put(StatKeys.CLOSE_FOR_BUSINESS, begin + 100);
        check(w.awaitsCheck(m), "calculated for the window before: due");
        Map<String, Double> old = new HashMap<>();
        old.put(StatKeys.CLUSTER_PERFORMANCE_SCORE, begin - 500);
        check(!w.awaitsCheck(old), "KPI data only from before the window: not due");
        check(!w.awaitsCheck(new HashMap<>()), "no data: not due");

        // in turn
        List<String> due = Arrays.asList("a", "b", "c", "d", "e");
        PeriodicWindow wk = PeriodicWindow.at(java.time.Instant.parse("2026-10-12T00:01:00Z").toEpochMilli(), 0, 0, 24);
        List<List<String>> cycles = new ArrayList<>();
        for (int n = 0; n < 3; n++) cycles.add(wk.sliceFor(due, wk.endMs + (5L * n + 1) * 60_000L, 2));
        eq(Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("c", "d"), Arrays.asList("e", "a")), cycles, "in turn");
        eq(Arrays.asList("a", "b"), wk.sliceFor(Arrays.asList("a", "b"), wk.endMs, 2), "up to the limit: all");

        // the rule
        String[] bands = new String[4];
        double[] vs = {10, 30, 60, 90};
        for (int i = 0; i < 4; i++) bands[i] = PeriodicCheck.bandOf(vs[i], false);
        eq(Arrays.asList("red", "orange", "yellow", "green"), Arrays.asList(bands), "performance bands: 25, 50, 75");
        double[] hs = {90, 70, 40, 10};
        for (int i = 0; i < 4; i++) bands[i] = PeriodicCheck.bandOf(hs[i], true);
        eq(Arrays.asList("red", "orange", "yellow", "green"), Arrays.asList(bands), "risk bands");
        eq("orange", PeriodicCheck.bandOf(25, false), "a value on a limit is the better band");
        eq("orange", PeriodicCheck.bandOf(75, true), "risk 75 is orange");
        eq("yellow", PeriodicCheck.bandOf(50, false), "50 is yellow");
        eq(1, PeriodicCheck.closeFlag(24, 0, 0), "performance red");
        eq(1, PeriodicCheck.closeFlag(99, 0, 76), "consumption red");
        eq(0, PeriodicCheck.closeFlag(99, 99, 0), "risk never closes alone");
        eq(0, PeriodicCheck.closeFlag(40, 99, 40), "only one orange");
        eq(0, PeriodicCheck.closeFlag(60, 99, 60), "yellow");
        eq(1, PeriodicCheck.closeFlag(40, 60, 60), "both orange, risk orange");
        eq(1, PeriodicCheck.closeFlag(40, 90, 60), "risk red");
        eq(0, PeriodicCheck.closeFlag(40, 40, 60), "risk yellow");
        eq(0, PeriodicCheck.closeFlag(40, 10, 60), "risk green");
        eq(0, PeriodicCheck.closeFlag(50, 60, 60), "performance 50 is yellow");
        eq(1, PeriodicCheck.closeFlag(49.99, 50.01, 50.01), "just inside orange");
        eq(0, PeriodicCheck.closeFlag(95, 10, 30), "healthy");
        eq(1, PeriodicCheck.closeFlag(25, 75, 75), "all three orange");
        eq(0, PeriodicCheck.closeFlag(25, 0, 0), "performance orange alone");

        Map<String, List<Double>> samples = new HashMap<>();
        samples.put(StatKeys.CLUSTER_PERFORMANCE_SCORE, Arrays.asList(95.0));
        samples.put(StatKeys.CLUSTER_RISK_SCORE, Arrays.asList(10.0));
        samples.put(StatKeys.CLUSTER_CONSUMPTION_SCORE, Arrays.asList(40.0));
        Map<String, Double> v = PeriodicCheck.valuesOver(samples, 5);
        eq(95.0, v.get(StatKeys.PERIODIC_PERFORMANCE), "performance");
        eq(0.0, v.get(StatKeys.CLOSE_FOR_BUSINESS), "open");
        samples.remove(StatKeys.CLUSTER_CONSUMPTION_SCORE);
        v = PeriodicCheck.valuesOver(samples, 5);
        check(!v.containsKey(StatKeys.CLOSE_FOR_BUSINESS) && !v.containsKey(StatKeys.PERIODIC_CONSUMPTION),
                "no Close for Business when an input has no data");

        // the Cluster Delta
        Map<String, Double> prev = new HashMap<>();
        Map<String, Double> cur = new HashMap<>();
        prev.put("a", 0.0); prev.put("b", 1.0); prev.put("c", 0.0); prev.put("d", 1.0); prev.put("e", 1.0);
        cur.put("a", 1.0); cur.put("b", 0.0); cur.put("c", 0.0); cur.put("d", 1.0); cur.put("e", 0.0);
        eq(Arrays.asList(1, 2), asList(PeriodicCheck.flips(prev, cur)), "close and open per direction");
        Map<String, Double> fresh = new HashMap<>();
        fresh.put("new_closed", 1.0);
        fresh.put("new_open", 0.0);
        eq(Arrays.asList(0, 0), asList(PeriodicCheck.flips(new HashMap<>(), fresh)), "a blank previous value is not counted");
        eq(Arrays.asList(0, 0), asList(PeriodicCheck.flips(prev, new HashMap<>())), "a blank current value is not counted");
        eq(Arrays.asList(0, 0), asList(PeriodicCheck.flips(prev, prev)), "no change");
        done("Periodic Cluster Check: window, due, rotation, rule, Cluster Delta");
    }

    static List<Integer> asList(int[] a) {
        return Arrays.asList(a[0], a[1]);
    }

    // ------------------------------------------------------------------ business applications

    static void businessApps() {
        eq(70.0, BusinessApps.tierValue(Arrays.asList(80.0, 60.0)), "tier mean");
        check(BusinessApps.tierValue(new ArrayList<Double>()) == null, "no VM KPI");
        eq(90.0, BusinessApps.applicationValue(Arrays.asList(90.0), Arrays.asList(70.0, 80.0)), "the higher of namespace and lowest tier (Q14)");
        eq(70.0, BusinessApps.applicationValue(Arrays.asList(50.0), Arrays.asList(70.0, 80.0)), "the lowest tier is higher");
        eq(70.0, BusinessApps.applicationValue(new ArrayList<Double>(), Arrays.asList(70.0, 80.0)), "tiers only");
        eq(90.0, BusinessApps.applicationValue(Arrays.asList(90.0), new ArrayList<Double>()), "namespaces only");
        check(BusinessApps.applicationValue(new ArrayList<Double>(), new ArrayList<Double>()) == null, "nothing");
        eq(50.0, BusinessApps.applicationValue(Arrays.asList(90.0, 50.0), Arrays.asList(70.0, 80.0), false), "Q14 off: the lowest of all");
        eq(90.0, BusinessApps.applicationValue(Arrays.asList(90.0, 50.0), Arrays.asList(70.0, 80.0), true), "Q14 on: the higher part");
        eq(70.0, BusinessApps.enterpriseValue(Arrays.asList(60.0, 80.0)), "enterprise mean");
        check(BusinessApps.enterpriseValue(new ArrayList<Double>()) == null, "no application");
        done("Business Applications formulas");
    }

    // ------------------------------------------------------------------ rounding and the sentinel

    static void rounding() {
        eq(2.67, PyRound.round2(2.675), "binary below: 2.67");
        eq(0.12, PyRound.round2(0.125), "half even");
        // the KPI is the rounded mean of rounded rows
        Map<String, Double> raw = new LinkedHashMap<>();
        raw.put("CPU Ready", 0.005);
        KpiScorer.Evaluation c = KpiScorer.evaluate(new KpiMetric[] {CPU_READY}, raw, false);
        eq(0.01, c.rows.get("CPU Ready"), "the rows are rounded first");
        // a higher-is-worse KPI is 100 minus the score, rounded
        Map<String, Double> r2 = new LinkedHashMap<>();
        r2.put("CPU Ready", 5.0);
        eq(PyRound.round2(100.0 - 18.75), KpiScorer.evaluate(new KpiMetric[] {CPU_READY}, r2, true).score, "flipped");
        done("rounding order");
    }

    static void sentinel() {
        // Q13: -1 is scored as read while the switch holds (upstream); a test of both settings is in DerivedTest
        eq(100.0, KpiScorer.combine(new KpiMetric[] {CPU_READY}, vals("CPU Ready", -1.0)), "-1 scores as perfect, as upstream");
        check(Quirks.NEGATIVE_ONE_STAT_IS_A_VALUE, "the quirk is on for the match");
        done("a -1 reading is what upstream scores (Q13)");
    }
}
