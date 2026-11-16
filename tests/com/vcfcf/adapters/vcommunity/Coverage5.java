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
 * The coverage of phase P5 (the guest keys): the rows of the key map this phase owns, each shown by a scenario that
 * compared its value with what the original publishes for the same guest output. A row no scenario compared
 * fails the run, and so does a committed table that is not the one the key map and the tests produce. Written
 * with {@code -Dp5.coverage.write=true}. The Windows events and the configuration warnings are not key rows;
 * their scenarios are named in the guest test itself.
 */
final class Coverage5 {

    private Coverage5() {}

    static final String OUT = "docs/p5-key-coverage.tsv";
    /** The rows of the key map this phase owns: R72 to R80. A change here is a change of the contract. */
    static final int ROWS = 9;

    static String scenario = "";
    /** Kind to key to the first scenario that compared its value. */
    static final Map<String, Map<String, String>> checked = new LinkedHashMap<>();

    /** A scenario compared the value of this key (as the key map writes it) with what is expected. */
    static synchronized void asserted(String kind, String key) {
        checked.computeIfAbsent(kind, k -> new LinkedHashMap<>()).putIfAbsent(key, scenario);
    }

    static boolean mine(int row) {
        return row >= 72 && row <= 80;
    }

    static String note(int row) {
        if (row <= 77) return "text of the guest's operating system program, as it wrote it; the last row of the output wins; an empty cell is the empty text (quirk Q4)";
        return "one instance per display name of a listed service that exists; a name with | or a control character is left out";
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
            Map<String, String> m = checked.get(c[0]);
            String test = m == null ? null : m.get(c[2]);
            if (test == null) {
                problems.add("R" + i + " " + c[2] + " (" + c[0] + ") is in phase P5 but no scenario compared its value");
                continue;
            }
            b.append("R").append(i).append('\t').append(c[2]).append('\t').append(c[0]).append("\tyes\t")
                    .append(test).append('\t').append(c[3]).append("; ").append(note(i)).append("\tP5\n");
        }
        if (rows != ROWS) {
            problems.add("the key map has " + rows + " rows of phase P5, this phase was written for " + ROWS
                    + ": a row was added or removed, so the coverage is stale");
        }
        if (!problems.isEmpty()) throw new AssertionError(String.join("\n", problems));
        return b.toString();
    }

    static void verifyOrWrite() throws IOException {
        String text = render();
        Path out = Paths.get(OUT);
        if (Boolean.getBoolean("p5.coverage.write")) {
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
            System.out.println("wrote " + OUT);
            return;
        }
        String have = Files.exists(out) ? new String(Files.readAllBytes(out), StandardCharsets.UTF_8) : "";
        if (!have.equals(text)) {
            throw new AssertionError(OUT + " is out of date; regenerate it with "
                    + "-Dp5.coverage.write=true (java -Dp5.coverage.write=true ... GuestEngineTest)");
        }
    }
}
