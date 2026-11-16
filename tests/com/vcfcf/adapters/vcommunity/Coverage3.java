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
 * The phase P3 coverage table, generated from the key map: every row of an own kind (Content
 * Library, Library Item, VM Template) with the first scenario that compared its <em>value</em>
 * with what upstream gives. A row no scenario compared fails the run, and so does a committed
 * table that is not the one the key map and the tests produce, so the table cannot go stale.
 * Written with {@code -Dp3.coverage.write=true}.
 */
final class Coverage3 {

    private Coverage3() {}

    static final String OUT = "docs/p3-key-coverage.tsv";
    /** The kinds this phase owns, as the key map names them in its first column. */
    static final String[] KINDS = {"VM Template", "Library Item", "Content Library"};
    /** The rows of the key map those kinds have today: 7 + 11 + 8. A change here is a change of the contract. */
    static final int ROWS = 26;

    static String scenario = "";
    /** Kind to key to the first scenario that compared its value. */
    static final Map<String, Map<String, String>> checked = new LinkedHashMap<>();

    /** A scenario compared the value of this key on an object of this kind with what upstream gives. */
    static synchronized void asserted(String kind, String key) {
        checked.computeIfAbsent(kind, k -> new LinkedHashMap<>()).putIfAbsent(key, scenario);
    }

    /** Kind to the keys a scenario saw absent on an object whose source was unset. */
    static final Map<String, java.util.Set<String>> unsetSeen = new LinkedHashMap<>();

    static synchronized void unset(String kind, String key) {
        unsetSeen.computeIfAbsent(kind, k -> new java.util.LinkedHashSet<>()).add(key);
    }

    private static boolean own(String kind) {
        for (String k : KINDS) if (k.equals(kind)) return true;
        return false;
    }

    /** What differs from the plain read, by row. */
    static String note(int row) {
        String text = "a returned empty text is sent as it is (Q4)";
        switch (row) {
            case 227: case 228: case 231: case 232:
                return text;
            case 229: return "text of the count";
            case 230: return "text of round(MB / 1024, 2)";
            case 233: return "round(bytes / 1024^3, 2)";
            case 234: case 235: case 236: case 237: case 238: case 239: case 240: case 241: case 242: case 243:
                return text;
            case 244: return "round(bytes / 1024^3, 2)";
            case 245: case 246: case 247: case 248:
                return text;
            case 249: return "the number of items listed; withheld when any item could not be read (DV-46)";
            case 250: case 251: return "not stored locally: sent only when every item carries the flag (or the library is empty); not security compliant: of the items with a compliance verdict; withheld when an item could not be read (DV-46)";
            case 252: return "round(sum / 1024^3, 2); sent only when every item carries a size (or the library is empty); withheld when an item could not be read (DV-46)";
            default: return "";
        }
    }

    static String render() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(Coverage.KEY_MAP), StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder("row_id\tkey\tobject_kind\timplemented\ttest\tnotes\tphase\n");
        List<String> problems = new ArrayList<>();
        int rows = 0;
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split("\t", -1);
            if (!own(c[0])) continue;
            rows++;
            String kind = c[0];
            String key = c[2];
            Map<String, String> m = checked.get(kind);
            String test = m == null ? null : m.get(key);
            if (test == null) {
                problems.add("R" + i + " " + key + " (" + kind + ") is in phase P3 but no scenario compared its value");
                continue;
            }
            String n = note(i);
            java.util.Set<String> u = unsetSeen.get(kind);
            if (u != null && u.contains(key)) n = n.isEmpty() ? "unset: nothing is sent (DV-112)" : n + "; unset: nothing is sent (DV-112)";
            b.append("R").append(i).append('\t').append(key).append('\t').append(kind).append("\tyes\t")
                    .append(test).append('\t').append(c[3]).append(n.isEmpty() ? "" : "; " + n)
                    .append("\tP3\n");
        }
        if (rows != ROWS) {
            problems.add("the key map has " + rows + " rows of the own kinds, this phase was written for " + ROWS
                    + ": a row was added or removed, so the coverage is stale");
        }
        if (!problems.isEmpty()) throw new AssertionError(String.join("\n", problems));
        return b.toString();
    }

    static void verifyOrWrite() throws IOException {
        String text = render();
        Path out = Paths.get(OUT);
        if (Boolean.getBoolean("p3.coverage.write")) {
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + OUT);
            return;
        }
        String have = Files.exists(out) ? new String(Files.readAllBytes(out), StandardCharsets.UTF_8) : "";
        if (!have.equals(text)) {
            throw new AssertionError(OUT + " is out of date; regenerate it with "
                    + "-Dp3.coverage.write=true (java -Dp3.coverage.write=true ... OwnObjectsTest)");
        }
    }
}
