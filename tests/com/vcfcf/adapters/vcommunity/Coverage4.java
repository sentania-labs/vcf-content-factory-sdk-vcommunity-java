package com.vcfcf.adapters.vcommunity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phase P4 coverage table, generated from the key map: every row of phase P4 (the derived metrics, R70 and
 * R71, R153 to R219 and R221 to R225) with the first scenario that compared its <em>value</em> with what is
 * expected. A row no scenario compared fails the run, and so does a committed table that is not the one the key
 * map and the tests produce. Written with {@code -Dp4.coverage.write=true}.
 */
final class Coverage4 {

    private Coverage4() {}

    static final String OUT = "docs/p4-key-coverage.tsv";
    /** The rows of the key map this phase owns: 2 + 67 + 5. A change here is a change of the contract. */
    static final int ROWS = 74;

    static String scenario = "";
    /** Kind to key to the first scenario that compared its value. */
    static final Map<String, Map<String, String>> checked = new LinkedHashMap<>();

    /** A scenario compared the value of this key on an object of this kind with what is expected. */
    static synchronized void asserted(String kind, String key) {
        checked.computeIfAbsent(kind, k -> new LinkedHashMap<>()).putIfAbsent(key, scenario);
    }

    /** What differs from the plain read, by row. */
    static String note(int row) {
        if (row == 70 || row == 71) return "read back from the VM's properties, else its statistics; round(shares / size, 2); no value without both";
        if (row >= 153 && row <= 158) return "KPI: round(weighted mean of the rows, 2); withheld unless every row's inputs were read";
        if (row >= 159 && row <= 167) return "max / 90th percentile over the powered-on VMs of the cluster's hosts (by MoRef); bad packets: the worst host";
        if (row == 173) return "worst host of the cluster; the statistic is in nanoseconds and is published in microseconds (DV-113)";
        if (row == 182) return "never published: its input mem:NVMe|tier.usage does not exist in Operations (known gap, DV-109)";
        if (row == 170 || row == 171 || row == 172 || row == 175 || row == 177 || row == 179) return "worst host of the cluster; memory rows divide by DRAM plus the NVMe tier on a host with memory tiering, DRAM alone otherwise (DV-109); unreadable inputs withhold the family";
        if (row >= 168 && row <= 179) return "worst host of the cluster; unreadable inputs withhold the family";
        if (row == 181 || row == 183 || row == 184) return "of the cluster's usable capacity, the running hosts only; memory capacity is DRAM plus the NVMe tier on a host with memory tiering (DV-109)";
        if (row >= 180 && row <= 184) return "of the cluster's usable capacity, the running hosts only";
        if (row >= 185 && row <= 193) return "the VMs on the host; published for hosts vCenter says are connected";
        if (row == 194) return "(compressed + swapped) / guest used memory x 100";
        if (row == 195) return "share of the conditions met, over four, or over three where the host has no quarantine property (DV-110); a host with no data counts 0; withheld when its reads failed";
        if (row == 196) return "published for connected hosts that have a NIC with both speed properties (DV-108: read as properties; needs net|configuredSpeed enabled in the policy)";
        if (row == 197) return "member hosts below 100; a Datacenter none of whose hosts has a value gets no count (DV-112); needs the host NIC configured speed property (DV-108)";
        if (row == 198) return "sum of the vCenters' stored counts within 15 minutes";
        if (row == 199 || row == 200) return "once per window, when every cluster has been checked";
        if (row >= 201 && row <= 202) return "from every VM's stored properties, all vCenters";
        if (row >= 203 && row <= 208) return "from the stored cluster scores, all vCenters (previous cycle)";
        if (row == 209 || row == 210) return "from the stored availability, all vCenters (previous cycle)";
        if (row == 211) return "hosts below 100 % of every vCenter, from the stored values; none when no host has a fresh value (DV-112) (DV-108: the host figure needs the NIC configured speed property)";
        if (row == 212) return "lowest of this cycle's cluster values; withheld when any is unknown";
        if (row == 213) return "distinct names per Datacenter, summed; withheld when any part could not be read";
        if (row == 214) return "mean of VM Performance and Guest OS Performance; VM Performance alone without Tools";
        if (row >= 215 && row <= 217) return "business applications, from the stored VM Guest OS KPI (previous cycle)";
        if (row == 218 || row == 219) return "pool levels from the vCenter's pool tree, without vApps and Supervisor namespaces; 0 for a cluster with none";
        if (row == 221) return "min(100, (sum - the k lowest) / (N - k)); hosts counted and not found are 0";
        if (row >= 222 && row <= 225) return "once per window from the history of the three cluster KPIs";
        return "";
    }

    static boolean mine(int row) {
        return row == 70 || row == 71 || row == 213 || row >= 153 && row <= 219 || row >= 221 && row <= 225;
    }

    static String render() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(Coverage.KEY_MAP), StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder("row_id\tkey\tobject_kind\timplemented\ttest\tnotes\tphase\n");
        List<String> problems = new ArrayList<>();
        int rows = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (!mine(i)) continue;
            String[] c = lines.get(i).split("\t", -1);
            rows++;
            String kind = c[0];
            String key = c[2];
            Map<String, String> m = checked.get(kind);
            String test = m == null ? null : m.get(key);
            if (test == null) {
                problems.add("R" + i + " " + key + " (" + kind + ") is in phase P4 but no scenario compared its value");
                continue;
            }
            String n = note(i);
            b.append("R").append(i).append('\t').append(key).append('\t').append(kind).append("\tyes\t")
                    .append(test).append('\t').append(c[3]).append(n.isEmpty() ? "" : "; " + n).append("\tP4\n");
        }
        if (rows != ROWS) {
            problems.add("the key map has " + rows + " rows of phase P4, this phase was written for " + ROWS
                    + ": a row was added or removed, so the coverage is stale");
        }
        if (!problems.isEmpty()) throw new AssertionError(String.join("\n", problems));
        return b.toString();
    }

    static void verifyOrWrite() throws IOException {
        String text = render();
        Path out = Paths.get(OUT);
        if (Boolean.getBoolean("p4.coverage.write")) {
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + OUT);
            return;
        }
        String have = Files.exists(out) ? new String(Files.readAllBytes(out), StandardCharsets.UTF_8) : "";
        if (!have.equals(text)) {
            throw new AssertionError(OUT + " is out of date; regenerate it with "
                    + "-Dp4.coverage.write=true (java -Dp4.coverage.write=true ... DerivedTest)");
        }
    }
}
