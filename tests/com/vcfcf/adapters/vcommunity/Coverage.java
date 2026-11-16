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
import java.util.regex.Pattern;

/**
 * Counts what the property tests produced against the key map. Every key a
 * test pushes is matched to the rows of the map by object kind and key shape;
 * a row is "implemented" only when some test pushed a key of that shape. The
 * result is written to {@code docs/p2-key-coverage.tsv} (and checked against
 * the committed file), so the coverage is counted, not asserted: a row that
 * is neither produced by a test nor listed here with its reason fails the run.
 */
final class Coverage {

    private Coverage() {}

    static final String KEY_MAP = "docs/design/vcommunity-e1-key-map.tsv";
    static final String OUT = "docs/p2-key-coverage.tsv";

    /** The test that is running. */
    static String scenario = "";
    /** Kind to key to the first test that pushed it. */
    static final Map<String, Map<String, String>> seen = new LinkedHashMap<>();

    static String kindOf(String rid) {
        if (rid.startsWith("res-host-")) return "HostSystem";
        if (rid.startsWith("res-vm-")) return "VirtualMachine";
        if (rid.startsWith("res-domain-")) return "ClusterComputeResource";
        if (rid.startsWith("res-datastore-")) return "Datastore";
        if (rid.equals("vc-res")) return "VMwareAdapter Instance";
        return null;
    }

    /** Kind to key to the first test that asserted the value pushed for it (not only that it was pushed). */
    static final Map<String, Map<String, String>> checked = new LinkedHashMap<>();

    /** A test compared the value of this key on this resource with what upstream gives. */
    static synchronized void asserted(String rid, String key) {
        String kind = kindOf(rid);
        if (kind != null) checked.computeIfAbsent(kind, k -> new LinkedHashMap<>()).putIfAbsent(key, scenario);
    }

    /** Record everything the Suite API fake saw pushed. */
    static synchronized void record(P2Ops ops) {
        for (Map<String, Map<String, Object>> push : ops.pushedProps) {
            for (Map.Entry<String, Map<String, Object>> r : push.entrySet()) {
                add(r.getKey(), r.getValue().keySet());
            }
        }
        for (Map<String, Map<String, Double>> push : ops.pushedStats) {
            for (Map.Entry<String, Map<String, Double>> r : push.entrySet()) {
                add(r.getKey(), r.getValue().keySet());
            }
        }
    }

    private static void add(String rid, Iterable<String> keys) {
        String kind = kindOf(rid);
        if (kind == null) return;
        Map<String, String> m = seen.computeIfAbsent(kind, k -> new LinkedHashMap<>());
        for (String k : keys) m.putIfAbsent(k, scenario);
    }

    /** The regex of a key-map key: placeholders match one segment, a family item may hold anything. */
    static Pattern pattern(String key) {
        StringBuilder b = new StringBuilder("^");
        int i = 0;
        boolean family = key.endsWith("|{key}") || key.endsWith("|{configPath}") || key.endsWith("|<item>");
        while (i < key.length()) {
            char c = key.charAt(i);
            if (c == '{' || c == '<') {
                int end = key.indexOf(c == '{' ? '}' : '>', i);
                boolean last = end == key.length() - 1;
                b.append(family && last ? ".+" : "[^|]+");
                i = end + 1;
            } else {
                b.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return Pattern.compile(b.append('$').toString());
    }

    /** The build phase that owns a row (design section 11). */
    static String phase(int row) {
        if (row == 253 || row == 254) return "P1";
        if (row == 70 || row == 71 || row == 213 || row >= 153 && row <= 219 || row >= 221 && row <= 225) return "P4";
        if (row >= 72 && row <= 80) return "P5";
        if (row >= 227 && row <= 252) return "P3";
        return "P2";
    }

    /** Why a row is not part of phase P2, by row number; null when it is. */
    static String notDone(int row) {
        if (row == 70 || row == 71) return "P4: reads the VM's shares back from VCF Operations";
        if (row >= 72 && row <= 80) return "P5: guest operations";
        if (row == 213) return "P4: port groups (the design's phase table puts them with the Datacenter counts)";
        if (row >= 153 && row <= 219) return "P4: derived metrics, World, Business Applications, resource pools";
        if (row >= 221 && row <= 225) return "P4: availability and the periodic cluster check";
        if (row >= 227 && row <= 252) return "P3: own objects (templates, libraries, items)";
        return null;
    }

    /** Notes of implemented rows, where something differs from the plain read. */
    static String note(int row) {
        switch (row) {
            case 1: return "ISO UTC text; no image manager or no date is \"null\" (Q9); a failed call is counted (F-HOST-INSTALLDATE), not sent as null (DV-10)";
            case 3: return "masked to the last group (DV-25)";
            case 6: return "metric, floor of days";
            case 7: return "count of the adapters read; none on a read that worked is \"0\" (Q9)";
            case 31: case 32: return "no PCI device for the NIC: \"N/A\" (Q9)";
            case 33: case 34: case 35: return "signed 16-bit text, as upstream (Q3); \"N/A\" with no device (Q9)";
            case 39: return "half-duplex while the link is down, as upstream (Q10)";
            case 43: return "numeric property (framework typed push)";
            case 47: return "\"0\" for a datastore with no VMFS extents, as upstream (Q9)";
            case 56: return "withheld on a failed read-back or probe (DV-23)";
            case 96: return "negative value read becomes 0, as the original; unset is 0 (Q9)";
            case 120: return "metric; 0 when the VM lists no device (Q9)";
            case 121: return "type text of an unmapped controller is its vim25 type name (DV-34)";
            case 131: case 257: return "invalid entry dropped and reported (DV-26)";
            case 132: case 133: case 134: case 135: case 136: case 137: case 138: case 139: case 140:
            case 141: case 142:
                return "HA off or host monitoring disabled: \"null\"; unset: \"None\" (Q9)";
            case 144: return "False when the cluster summary was read and names no mode (kept, Q2)";
            case 145: return "\"null\" when EVC is off (Q9)";
            case 146: case 147: case 148: case 149: return "DRS off, or the value missing: \"null\"; no proactive configuration: none, as upstream (Q9)";
            case 151: return "metric; not returned: 0, as upstream's default (Q9)";
            case 220: return "metric; DRS off or a negative score: 0, as upstream (Q9)";
            case 253: case 254: return "built in P1; counted here because the cycle pushes them with the property keys";
            case 226: return "unset: \"None\" (Q9)";
            default:
                if (row >= 60 && row <= 69) return "a setting vCenter lacks or leaves blank: \"null\" (Q9)";
                return "";
        }
    }

    /** One row of the key map. */
    static final class Row {
        final int n;
        final String kind;
        final String key;
        final String type;

        Row(int n, String kind, String key, String type) {
            this.n = n;
            this.kind = kind;
            this.key = key;
            this.type = type;
        }
    }

    static List<Row> keyMap() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(KEY_MAP), StandardCharsets.UTF_8);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split("\t", -1);
            rows.add(new Row(i, c[0], c[2], c[3]));
        }
        return rows;
    }

    /** The coverage table as text. */
    static String render() throws IOException {
        StringBuilder b = new StringBuilder("row_id\tkey\tobject_kind\timplemented\ttest\tnotes\tphase\n");
        int yes = 0;
        List<String> problems = new ArrayList<>();
        for (Row r : keyMap()) {
            String reason = notDone(r.n);
            String test = null;
            Pattern p = pattern(r.key);
            // a P2 row needs a test that compared its value; a shape that was only pushed does not count
            Map<String, String> seenKind = phase(r.n).equals("P2") ? checked.get(r.kind) : seen.get(r.kind);
            if (seenKind != null) {
                for (Map.Entry<String, String> e : seenKind.entrySet()) {
                    if (p.matcher(e.getKey()).matches()) {
                        test = e.getValue();
                        break;
                    }
                }
            }
            if (reason != null) {
                // a P4 row is counted in docs/p4-key-coverage.tsv; the property scenarios may push it too
                if (test != null && !phase(r.n).equals("P4")) {
                    problems.add("R" + r.n + " is listed as not done but a test produced it");
                }
                b.append("R").append(r.n).append('\t').append(r.key).append('\t').append(r.kind)
                        .append("\tno\t\t").append(reason).append('\t').append(phase(r.n)).append('\n');
            } else if (test == null) {
                problems.add("R" + r.n + " " + r.key + " (" + r.kind + ") is in phase P2 but no test compared its value");
            } else {
                yes++;
                String n = note(r.n);
                b.append("R").append(r.n).append('\t').append(r.key).append('\t').append(r.kind)
                        .append("\tyes\t").append(test).append('\t').append(r.type)
                        .append(n.isEmpty() ? "" : "; " + n).append('\t').append(phase(r.n)).append('\n');
            }
        }
        if (!problems.isEmpty()) throw new AssertionError(String.join("\n", problems));
        return b.toString();
    }

    /** Write the table with {@code -Dp2.coverage.write=true}, else require it to be the committed one. */
    static void verifyOrWrite() throws IOException {
        String text = render();
        Path out = Paths.get(OUT);
        if (Boolean.getBoolean("p2.coverage.write")) {
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + OUT);
            return;
        }
        String have = Files.exists(out) ? new String(Files.readAllBytes(out), StandardCharsets.UTF_8) : "";
        if (!have.equals(text)) {
            throw new AssertionError(OUT + " is out of date; regenerate it with "
                    + "-Dp2.coverage.write=true (java -Dp2.coverage.write=true ... PropertiesTest)");
        }
    }
}
