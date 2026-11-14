package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the tables a guest wrote into what is published (design 8.5): the columns are found by name, a row
 * shorter than the header is left out and counted, a value that cannot be a property is left out with its row
 * and counted, an empty cell is the empty text (quirk Q4). None of it can name a key the table did not earn: instance
 * names pass {@link GuestText#isSafeInstance}.
 */
public final class GuestRows {

    private GuestRows() {}

    /** The output lacks a column the keys need. */
    public static final class HeaderException extends Exception {
        private static final long serialVersionUID = 1L;

        HeaderException(String message) {
            super(message);
        }
    }

    /** One service. A null {@code startType} is a column that is absent or a cell that is empty. */
    public static final class Service {
        public final String name;
        public final String displayName;
        public final String status;
        public final String startType;

        Service(String name, String displayName, String status, String startType) {
            this.name = name;
            this.displayName = displayName;
            this.status = status;
            this.startType = startType;
        }
    }

    /** One event of a Windows log, as its script wrote it. */
    public static final class EventRow {
        public final String level;
        /** The Windows level number, or -1 when the column is absent or not a number. */
        public final int levelNumber;
        public final String event;

        EventRow(String level, int levelNumber, String event) {
            this.level = level;
            this.levelNumber = levelNumber;
            this.event = event;
        }
    }

    /** Rows kept, and how many were left out. */
    public static final class Parsed<T> {
        public final List<T> rows = new ArrayList<>();
        public int skipped;
    }

    /** The six operating system values as property names of the keys' last segment; the last valid row wins. */
    public static Parsed<Map<String, String>> operatingSystem(GuestCsv.Table t) throws HeaderException {
        String[][] cols = {{"Name", "OS Name"}, {"Version", "OS Version"}, {"BuildNumber", "OS BuildNumber"},
            {"OSArchitecture", "OS Architecture"}, {"LastBootUpTime", "OS Last Boot Up Time"},
            {"ReleaseId", "OS Release ID"}};
        int[] at = new int[cols.length];
        for (int i = 0; i < cols.length; i++) {
            at[i] = t.column(cols[i][0]);
            if (at[i] < 0) throw new HeaderException("the output has no " + cols[i][0] + " column");
        }
        Parsed<Map<String, String>> out = new Parsed<>();
        for (List<String> row : t.rows) {
            if (row.size() < t.header.size()) {
                out.skipped++;
                continue;
            }
            Map<String, String> v = new LinkedHashMap<>();
            boolean bad = false;
            for (int i = 0; i < cols.length && !bad; i++) {
                String cell = row.get(at[i]);
                if (!GuestText.isSafeValue(cell)) {
                    bad = true;
                    continue;
                }
                // a cell that is empty is the empty text, as the original sends it (quirk Q4)
                String text = Quirks.text(cell);
                if (text != null) v.put(cols[i][1], text);
            }
            if (bad) {
                out.skipped++;
            } else {
                out.rows.add(v);
            }
        }
        return out;
    }

    public static Parsed<Service> services(GuestCsv.Table t) throws HeaderException {
        int name = t.column("Name");
        int display = t.column("DisplayName");
        int status = t.column("Status");
        int start = t.column("StartType");
        if (name < 0 || display < 0 || status < 0) {
            throw new HeaderException("the output has no " + (name < 0 ? "Name" : display < 0 ? "DisplayName"
                    : "Status") + " column");
        }
        Parsed<Service> out = new Parsed<>();
        for (List<String> row : t.rows) {
            if (row.size() < t.header.size()) {
                out.skipped++;
                continue;
            }
            String d = row.get(display);
            String n = row.get(name);
            String s = row.get(status);
            String st = start < 0 ? null : row.get(start);
            if (!GuestText.isSafeInstance(d) || !GuestText.isSafeValue(n) || !GuestText.isSafeValue(s)
                    || (st != null && !GuestText.isSafeValue(st))) {
                out.skipped++;
                continue;
            }
            out.rows.add(new Service(Quirks.text(n), d, Quirks.text(s), st == null ? null : Quirks.text(st)));
        }
        return out;
    }

    public static Parsed<EventRow> events(GuestCsv.Table t) throws HeaderException {
        int level = t.column("Level");
        int event = t.column("Event");
        int number = t.column("LevelNumber");
        if (level < 0 || event < 0) {
            throw new HeaderException("the output has no " + (level < 0 ? "Level" : "Event") + " column");
        }
        Parsed<EventRow> out = new Parsed<>();
        for (List<String> row : t.rows) {
            if (row.size() < t.header.size()) {
                out.skipped++;
                continue;
            }
            int n = -1;
            if (number >= 0 && row.get(number).matches("[0-9]{1,3}")) n = Integer.parseInt(row.get(number));
            out.rows.add(new EventRow(row.get(level), n, row.get(event)));
        }
        return out;
    }
}
