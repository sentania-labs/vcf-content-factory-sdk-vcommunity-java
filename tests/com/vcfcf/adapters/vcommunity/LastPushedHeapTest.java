package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.ops.LastPushed;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The table of pushed values stays inside the retained budget (design 9.3, 35 MB for the whole
 * instance) at the scale of 9.4: ten thousand VMs of sixty properties. The workload runs in a child JVM
 * with a heap cap, so a regression to maps of strings (87 MB) fails by running out of heap.
 */
public final class LastPushedHeapTest {

    static final int VMS = 10_000;
    static final int KEYS = 60;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            child();
            return;
        }
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
        Process p = new ProcessBuilder(java, "-Xmx70m", "-cp", System.getProperty("java.class.path"),
                LastPushedHeapTest.class.getName(), "child").redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) out.append(l).append('\n');
        }
        T.check(p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0, "the child run: " + out);
        T.contains(out.toString(), "retained", "reports what it keeps");
        System.out.print(out);
        T.done("last pushed: 10,000 VMs x 60 properties retained in a heap of 70 MB");
    }

    /** One batch of VMs as a collector builds it: every key a new String. */
    static Map<String, Map<String, Object>> batch(int from, int to) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (int v = from; v < to; v++) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int k = 0; k < KEYS; k++) {
                m.put(new String("vCommunity|Configuration|Some Key Number " + k + "|Name"), "value " + (v % 7) + " " + k);
            }
            all.put("res-vm-" + v, m);
        }
        return all;
    }

    static void child() {
        LastPushed lp = new LastPushed(12);
        // The cycle's properties are fed in slices, so what the test itself holds is not what is measured: this
        // is the table's own size. (The cycle's PushSet is a separate, transient cost.)
        for (int from = 0; from < VMS; from += 500) {
            LastPushed.Selection sel = lp.select(batch(from, from + 500), 1);
            sel.commit();
        }
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long retained = rt.totalMemory() - rt.freeMemory();
        // a second cycle with every value unchanged sends nothing, and a full refresh keeps the table the same size
        for (int from = 0; from < VMS; from += 500) {
            Map<String, Map<String, Object>> b = batch(from, from + 500);
            T.check(lp.select(b, 2).toSend().isEmpty(), "unchanged values are not sent");
        }
        T.eq(VMS * KEYS, lp.tracked(), "every value is remembered");
        System.gc();
        retained = Math.max(retained, rt.totalMemory() - rt.freeMemory());
        System.out.println("retained " + (retained / (1024 * 1024)) + " MB for " + lp.tracked() + " values");
        T.check(retained < 35L * 1024 * 1024, "within the 35 MB retained budget: " + retained);
    }
}
