package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.guest.EventMemory;
import com.vcfcf.adapters.vcommunity.guest.GuestAuth;
import com.vcfcf.adapters.vcommunity.guest.GuestCsv;
import com.vcfcf.adapters.vcommunity.guest.GuestCursor;
import com.vcfcf.adapters.vcommunity.guest.GuestPlan;
import com.vcfcf.adapters.vcommunity.guest.GuestRows;
import com.vcfcf.adapters.vcommunity.guest.GuestScripts;
import com.vcfcf.adapters.vcommunity.guest.GuestText;
import com.vcfcf.adapters.vcommunity.guest.WindowsEvents;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.ConfigProblemEvents;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** The parts of the guest phase that need no server: parsing, cleaning, memory, the programs' text, the plan. */
public final class GuestUnitTest {

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        csv();
        rows();
        text();
        events();
        memory();
        programs();
        plan();
        cursor();
        auth();
        configEvents();
        programOutput();
        System.out.println("guest units: all passed");
    }

    /** What the programs themselves write (run under PowerShell, see tests/fixtures/guest/README.md), through the parser. */
    static void programOutput() throws Exception {
        java.nio.file.Path dir = java.nio.file.Paths.get("tests/fixtures/guest");
        byte[] os = java.nio.file.Files.readAllBytes(dir.resolve("os.csv"));
        T.check(os[0] != (byte) 0xEF, "the writer's output has no byte order mark");
        GuestRows.Parsed<java.util.Map<String, String>> o = GuestRows.operatingSystem(GuestCsv.parse(os));
        T.eq(1, o.rows.size(), "one operating system row");
        T.eq("Microsoft Windows Server 2019 Datacenter", o.rows.get(0).get("OS Name"), "name");
        T.eq("11/12/2026 08:15:00", o.rows.get(0).get("OS Last Boot Up Time"), "the date as ConvertTo-Csv writes it");
        GuestRows.Parsed<GuestRows.Service> sv = GuestRows.services(GuestCsv.parse(
                java.nio.file.Files.readAllBytes(dir.resolve("services.csv"))));
        T.eq(2, sv.rows.size(), "two services");
        T.eq("Display of BITS, \"q\"", sv.rows.get(0).displayName, "a comma and quotes in a display name");
        GuestRows.Parsed<GuestRows.EventRow> ev = GuestRows.events(GuestCsv.parse(
                java.nio.file.Files.readAllBytes(dir.resolve("events.csv"))));
        T.eq(1, ev.rows.size(), "one event");
        T.eq(2, ev.rows.get(0).levelNumber, "level number");
        T.contains(ev.rows.get(0).event, "line1\r\nline2 \"quoted\", comma  \u00fc \u65e5\u672c\u8a9e]", "a line break, quotes and non-ASCII in a message");
        T.eq(com.vcfcf.adapters.vcommunity.ops.OpsEvent.Severity.IMMEDIATE,
                WindowsEvents.severity(ev.rows.get(0).levelNumber, ev.rows.get(0).level), "severity");
        T.contains(WindowsEvents.message(ev.rows.get(0).level, ev.rows.get(0).event), "line1\nline2 \"quoted\"", "CRLF in the message is one LF");
        // none found: the header alone, which is a reading and not a program that wrote nothing
        GuestCsv.Table none = GuestCsv.parse(java.nio.file.Files.readAllBytes(dir.resolve("services-none-found.csv")));
        T.eq(0, GuestRows.services(none).rows.size(), "header only: no service found");
        GuestCsv.Table noEvents = GuestCsv.parse(java.nio.file.Files.readAllBytes(dir.resolve("events-none-found.csv")));
        T.eq(0, GuestRows.events(noEvents).rows.size(), "header only: no event in the window");
        T.done("guest units: the output of the programs themselves");
    }

    static void csv() throws Exception {
        GuestCsv.Table t = GuestCsv.parse(b("\uFEFF\"A\",\"B\"\r\n\"x,y\",\"say \"\"hi\"\"\"\r\n\"line1\r\nline2\",z\r\n"));
        T.eq(Arrays.asList("A", "B"), t.header, "header after a BOM");
        T.eq(Arrays.asList("x,y", "say \"hi\""), t.rows.get(0), "comma and doubled quotes in a quoted field");
        T.eq("line1\r\nline2", t.rows.get(1).get(0), "a line break inside a quoted field is data");
        T.eq(1, t.column("B"), "column by name");
        T.eq(-1, t.column("b"), "names are exact");
        T.eq(null, GuestCsv.parse(b("")), "nothing");
        T.eq(null, GuestCsv.parse(b("\uFEFF \r\n")), "only a BOM and a blank");
        T.eq(2, GuestCsv.parse(b("A,B\nx,y\n")).header.size(), "LF only");
        T.eq(1, GuestCsv.parse(b("A\rx\r")).rows.size(), "a lone CR ends a row");
        T.eq(1, GuestCsv.parse(b("A,B\r\nx,y")).rows.size(), "no final line break");
        for (String bad : new String[] {"A\r\n\"ZZQ", "A\r\n\"ZZQ\"y\r\n", "A\r\nZZQ\"y\r\n", "A,B\r\n\"ZZQ\"\""}) {
            try {
                GuestCsv.parse(b(bad));
                throw new AssertionError("accepted: " + bad);
            } catch (GuestCsv.CsvException e) {
                T.notContains(e.getMessage(), "ZZQ", "the message holds none of the text");
            }
        }
        try {
            GuestCsv.parse(new byte[] {'A', '\r', '\n', (byte) 0xC3, (byte) 0x28});
            throw new AssertionError("invalid UTF-8 accepted");
        } catch (GuestCsv.CsvException e) {
            T.contains(e.getMessage(), "UTF-8", "invalid UTF-8");
        }
        StringBuilder wide = new StringBuilder("c0");
        for (int i = 1; i < 40; i++) wide.append(",c").append(i);
        try {
            GuestCsv.parse(b(wide + "\r\n"));
            throw new AssertionError("too many columns accepted");
        } catch (GuestCsv.CsvException e) {
            T.contains(e.getMessage(), "columns", "column limit");
        }
        T.done("guest units: CSV (RFC 4180, BOM, strict, bounded)");
    }

    static void rows() throws Exception {
        GuestCsv.Table t = GuestCsv.parse(b("Name,DisplayName,Status,StartType\r\na,A,Running,Auto\r\nb,B,,\r\n"));
        GuestRows.Parsed<GuestRows.Service> p = GuestRows.services(t);
        T.eq(2, p.rows.size(), "two rows");
        // an empty cell is the empty text, as the original sends it (Q4); the seam turns that off
        T.eq(com.vcfcf.adapters.vcommunity.text.Quirks.BLANK_STRINGS_PUSHED ? "" : null, p.rows.get(1).status, "an empty cell");
        T.eq(com.vcfcf.adapters.vcommunity.text.Quirks.BLANK_STRINGS_PUSHED ? "" : null, p.rows.get(1).startType, "an empty cell");
        T.throwsNothing(() -> GuestRows.services(GuestCsv.parse(b("Name,DisplayName,Status\r\na,,c\r\n"))), "blank display name");
        T.eq(0, GuestRows.services(GuestCsv.parse(b("Name,DisplayName,Status\r\na,,c\r\n"))).rows.size(),
                "a blank display name cannot be an instance of a key");
        // the same display name twice: both rows come out, the later one overwrites in the push set (as the original)
        GuestRows.Parsed<GuestRows.Service> dup = GuestRows.services(GuestCsv.parse(
                b("Name,DisplayName,Status\r\na,Same,Running\r\nb,Same,Stopped\r\n")));
        T.eq(2, dup.rows.size(), "duplicates are kept in order");
        // the operating system: the last valid row wins
        GuestRows.Parsed<java.util.Map<String, String>> os = GuestRows.operatingSystem(GuestCsv.parse(b(
                "Name,Version,BuildNumber,OSArchitecture,LastBootUpTime,ReleaseId\r\nOne,1,1,64,t,\r\nTwo,2,2,64,t,2009\r\n")));
        T.eq("Two", os.rows.get(os.rows.size() - 1).get("OS Name"), "last row last");
        T.eq(com.vcfcf.adapters.vcommunity.text.Quirks.BLANK_STRINGS_PUSHED ? "" : null, os.rows.get(0).get("OS Release ID"),
                "an empty ReleaseId cell is the empty text (Q4)");
        try {
            GuestRows.events(GuestCsv.parse(b("Level\r\nx\r\n")));
            throw new AssertionError("header");
        } catch (GuestRows.HeaderException e) {
            T.contains(e.getMessage(), "Event", "names the column");
        }
        T.done("guest units: rows by column name");
    }

    static void text() {
        T.check(GuestText.isSafeValue("Windows Server 2022 \u00fc"), "plain text");
        T.check(GuestText.isSafeValue("\u200e11/12/2026\u200f"), "left-to-right and right-to-left marks of a localised date are allowed");
        T.check(!GuestText.isSafeValue("a\nb"), "line break");
        T.check(!GuestText.isSafeValue("a\u0085b"), "next line");
        T.check(!GuestText.isSafeValue("x".repeat(513)), "long");
        T.check(!GuestText.isSafeValue("\ud83d"), "a lone surrogate");
        T.check(GuestText.isSafeValue("\ud83d\ude80"), "a pair");
        T.check(GuestText.isSafeInstance("Background Intelligent Transfer Service"), "an instance name");
        T.check(!GuestText.isSafeInstance("a|b"), "a separator would start a new level of the key");
        T.check(!GuestText.isSafeInstance("   "), "blank");
        T.check(GuestText.isSafeInstance("Name: with colon"), "a colon is part of a name");
        T.eq("a\nb\tc", GuestText.eventText("a\r\nb\tc", 100), "CRLF is one LF");
        T.eq("ab", GuestText.eventText("a\u0000\u001bb", 100), "controls dropped");
        T.eq("abc...", GuestText.eventText("abcdef", 3), "cut with an ellipsis");
        T.eq("a...", GuestText.eventText("a\ud83d\ude80", 2), "never half a pair");
        T.done("guest units: what text may become a value, an instance, an event");
    }

    static void events() {
        T.eq(OpsEvent.Severity.CRITICAL, WindowsEvents.severity(1, "x"), "1");
        T.eq(OpsEvent.Severity.IMMEDIATE, WindowsEvents.severity(2, "x"), "2");
        T.eq(OpsEvent.Severity.WARNING, WindowsEvents.severity(3, "x"), "3");
        T.eq(OpsEvent.Severity.INFORMATION, WindowsEvents.severity(4, "Error"), "4 beats a name");
        T.eq(OpsEvent.Severity.INFORMATION, WindowsEvents.severity(5, "x"), "5");
        T.eq(OpsEvent.Severity.INFORMATION, WindowsEvents.severity(0, "x"), "0");
        T.eq(OpsEvent.Severity.IMMEDIATE, WindowsEvents.severity(-1, "Error"), "no number: Error");
        T.eq(OpsEvent.Severity.WARNING, WindowsEvents.severity(-1, "Warning"), "no number: Warning");
        T.eq(OpsEvent.Severity.INFORMATION, WindowsEvents.severity(99, "Information"), "an unknown number: by name");
        T.eq("[WindowsEvent-Error [Timestamp=t]]", WindowsEvents.message("Error", "[Timestamp=t]"), "the original's message form");
        T.done("guest units: event severity and message");
    }

    static void memory() {
        EventMemory m = new EventMemory(1000, 3);
        List<OpsEvent> a = new ArrayList<>(Arrays.asList(ev("r1", "one"), ev("r1", "one"), ev("r2", "one")));
        List<OpsEvent> fresh = m.fresh(a, 0);
        T.eq(2, fresh.size(), "the same event twice in a list is one; the same text on another object is another");
        T.eq(0, m.size(), "nothing is remembered before the send is known to be clean");
        m.remember(fresh, 0);
        T.eq(0, m.fresh(fresh, 500).size(), "an event in view again is not sent again");
        T.eq(2, m.fresh(fresh, 1500).size(), "after the time to live it is");
        m.remember(Arrays.asList(ev("r3", "a"), ev("r3", "b")), 2000);
        T.check(m.size() <= 3, "bounded: " + m.size());
        T.eq(0, m.fresh(Arrays.asList(ev("r3", "b")), 2100).size(), "the newest are kept");
        // a memory of hashes only: no text
        EventMemory big = new EventMemory();
        for (int i = 0; i < EventMemory.CAPACITY + 100; i++) big.remember(Collections.singletonList(ev("r", "m" + i)), i);
        T.eq(EventMemory.CAPACITY, big.size(), "the table does not grow past its capacity");
        T.done("guest units: event memory (once, bounded, hashes only)");
    }

    static OpsEvent ev(String rid, String msg) {
        return new OpsEvent(rid, msg, OpsEvent.Severity.INFORMATION, 0);
    }

    static void programs() {
        String p = GuestScripts.program(GuestScripts.Kind.SERVICES, "C:\\Windows\\Temp\\d\\input.txt", "C:\\Windows\\Temp\\d\\output.csv");
        T.contains(p, "$InputFile = 'C:\\Windows\\Temp\\d\\input.txt'", "input bound");
        T.contains(p, "$OutputFile = 'C:\\Windows\\Temp\\d\\output.csv'", "output bound");
        T.contains(p, "exit 2", "a terminating error ends with 2");
        String q = GuestScripts.program(GuestScripts.Kind.OS, "C:\\it's\\in", "out");
        T.contains(q, "'C:\\it''s\\in'", "a quote in a path is doubled");
        try {
            GuestScripts.program(GuestScripts.Kind.OS, "a\nb", "o");
            throw new AssertionError("control character accepted");
        } catch (IllegalArgumentException e) {
            // refused
        }
        String args = GuestScripts.arguments(p);
        T.check(args.startsWith("-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "), "switches");
        String b64 = args.substring(args.lastIndexOf(' ') + 1);
        T.eq(p, new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_16LE), "UTF-16LE base64 round trip");
        for (GuestScripts.Kind k : GuestScripts.Kind.values()) {
            int len = GuestScripts.arguments(GuestScripts.program(k, "C:\\Windows\\Temp\\VCFOperationsvCommunity-12345-"
                    + k.label + "-TEMP\\input.txt", "C:\\Windows\\Temp\\VCFOperationsvCommunity-12345-" + k.label
                    + "-TEMP\\output.csv")).length();
            T.check(len < 6000, k + " arguments are " + len + " characters (the Windows limit is 32767)");
        }
        T.done("guest units: the programs' text and arguments");
    }

    static void plan() throws Exception {
        VCommunityConfig on = cfg("serviceMonitoring", "Enabled", "winEventMonitoring", "Disabled");
        T.eq(GuestPlan.Off.SWITCHED_OFF, GuestPlan.of(cfg(), new java.util.EnumMap<>(com.vcfcf.adapters.vcommunity.list.ListKind.class)).off,
                "both switches off: no guest operation");
        T.eq(GuestPlan.Off.NO_CREDENTIAL, GuestPlan.of(on, new java.util.EnumMap<>(com.vcfcf.adapters.vcommunity.list.ListKind.class)).off,
                "enabled with no account");
        T.eq(GuestPlan.Off.BAD_TLS_MODE, GuestPlan.of(cfgWithAccount("serviceMonitoring", "Enabled", "guest_tls_validation", "Sometimes"),
                new java.util.EnumMap<>(com.vcfcf.adapters.vcommunity.list.ListKind.class)).off, "a mode that is not one of the three");
        GuestPlan ok = GuestPlan.create(true, Arrays.asList("BITS", "bad'name", "a$(x)", "W32Time"), true,
                "<Events><Log Name=\"System\"><EventID>1</EventID><EventID>2</EventID></Log><Log Name=\"A&#9;B\"><EventID>3</EventID></Log></Events>");
        T.eq(Arrays.asList("BITS", "W32Time"), ok.serviceNames, "only names the filter accepts");
        T.eq(2, ok.queries.size(), "the two ids of System");
        T.eq(1, ok.queriesSkipped, "a log name with a tab cannot be carried in the guest file");
        T.eq("System\t1\nSystem\t2\n", new String(ok.eventFile(), StandardCharsets.UTF_8), "the guest file");
        T.eq("BITS\nW32Time\n", new String(ok.serviceFile(), StandardCharsets.UTF_8), "the service file");
        T.check(ok.events && ok.services && ok.operatingSystem, "all three");
        GuestPlan onlyEvents = GuestPlan.create(false, Collections.<String>emptyList(), true, "<Events/>");
        T.eq(GuestPlan.Off.NOTHING_TO_DO, onlyEvents.off, "an event switch with nothing to look for");
        GuestPlan badXml = GuestPlan.create(false, Collections.<String>emptyList(), true, "<Events><Log/></Events>");
        T.eq(GuestPlan.Off.NOTHING_TO_DO, badXml.off, "an invalid event list is not used");
        T.done("guest units: the plan");
    }

    static VCommunityConfig cfg(String... kv) {
        return EngineTest.cfg(kv);
    }

    static VCommunityConfig cfgWithAccount(String... kv) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("host", "vc.example.test");
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return VCommunityConfig.parse(m::get, "u", "p", "svc", "pw");
    }

    static void cursor() {
        GuestCursor c = new GuestCursor();
        List<String> all = Arrays.asList("n-30", "n-10", "n-50", "n-20", "n-40");
        T.eq(Arrays.asList("n-10", "n-20", "n-30", "n-40", "n-50"), c.order(all, x -> x), "first cycle: in order");
        c.started("n-30");
        T.eq(Arrays.asList("n-40", "n-50", "n-10", "n-20", "n-30"), c.order(all, x -> x), "the next begins after the last started");
        c.started("n-20");
        T.eq(Arrays.asList("n-30", "n-40", "n-50", "n-10", "n-20"), c.order(all, x -> x), "and wraps");
        c.started("n-50");
        T.eq(Arrays.asList("n-10", "n-20", "n-30", "n-40", "n-50"), c.order(all, x -> x), "after the last: the first");
        c.started("n-99");
        T.eq("n-10", c.order(all, x -> x).get(0), "a VM that is gone does not stop the rotation");
        T.done("guest units: the rotating cursor");
    }

    static void auth() {
        GuestAuth a = new GuestAuth("DOM\\svc", "p&ss<w>rd\"");
        String s = a.scrub("login DOM\\svc failed with p&amp;ss&lt;w&gt;rd&quot; and p&ss<w>rd\" again");
        T.notContains(s, "svc", "name scrubbed");
        T.notContains(s, "ss", "password scrubbed in plain and escaped forms");
        T.eq(null, a.scrub(null), "null stays null");
        T.notContains(a.toString(), "svc", "toString");
        T.throwsIae(() -> new GuestAuth("u", ""), null, "a password is required");
        T.done("guest units: scrubbing");
    }

    static void configEvents() throws Exception {
        CheckListStore store = new CheckListStore(Log.NONE);
        java.util.Map<com.vcfcf.adapters.vcommunity.list.ListKind, String> files = new java.util.EnumMap<>(
                com.vcfcf.adapters.vcommunity.list.ListKind.class);
        files.put(com.vcfcf.adapters.vcommunity.list.ListKind.ESXI_SETTINGS, "esxi_advanced_system_settings");
        files.put(com.vcfcf.adapters.vcommunity.list.ListKind.VM_OPTIONS, "vm_options");
        java.util.Map<com.vcfcf.adapters.vcommunity.list.ListKind, CheckListStore.Entry> r = store.refresh(files, path -> {
            if (path.contains("vm_options")) return "<L>config.version</L>";
            throw new com.vcfcf.adapters.vcommunity.ops.OpsHttpException("missing", 404, null);
        });
        List<OpsEvent> ev = ConfigProblemEvents.of(r.values(), "vc-res", 5);
        T.eq(1, ev.size(), "one event for the file that is absent, none for the one that is fine");
        T.check(ev.get(0).message.startsWith("vCommunity configuration: Configuration file 'esxi_advanced_system_settings.xml'"),
                ev.get(0).message);
        T.eq(OpsEvent.Severity.WARNING, ev.get(0).severity, "a warning");
        T.eq("vc-res", ev.get(0).resourceId, "on the vCenter's object");
        T.eq(0, ConfigProblemEvents.of(r.values(), null, 5).size(), "none when the vCenter object is not resolved");
        T.done("guest units: configuration problem events");
    }
}
