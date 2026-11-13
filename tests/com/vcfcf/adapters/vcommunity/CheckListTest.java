package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.list.CheckListParser;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.CheckListStore.Outcome;
import com.vcfcf.adapters.vcommunity.list.EventListValidator;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.list.ServiceNameFilter;
import com.vcfcf.adapters.vcommunity.list.VmOptionPath;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The check-list files: parsing, validation, outcomes, last good list. */
public final class CheckListTest {

    public static void main(String[] a) throws Exception {
        parser();
        paths();
        events();
        store();
        gating();
    }

    static List<String> items(String xml) throws Exception {
        return CheckListParser.parse(xml).items;
    }

    static void parser() throws Exception {
        T.eq(Arrays.asList("a", "b", "c"), items("<List>a, b ,c</List>"), "plain list");
        T.eq(new ArrayList<String>(), items("<List>  </List>"), "blank root");
        T.eq(new ArrayList<String>(), items(""), "empty text is an empty list");
        T.eq(new ArrayList<String>(), items(null), "null is an empty list");
        T.eq(Arrays.asList("a", "b"), items("<L>a,,b,,</L>"), "empty items dropped");
        T.eq(Arrays.asList("a", "b"), items("<L>a,b,a,b,a</L>"), "duplicates collapse, first kept");
        // comments are dropped and the text either side is joined
        T.eq(Arrays.asList("keep1", "keep2"), items("<L>\n<!-- one, two, -->\nkeep1,\n<!-- three, -->\nkeep2\n</L>"),
                "commented lines contribute nothing");
        T.eq(Arrays.asList("ab"), items("<L>a<!-- x -->b</L>"), "text either side of a comment is joined");
        T.eq(new ArrayList<String>(), items("<L><!-- every entry commented out, like the shipped file --></L>"),
                "all commented out");
        // only the text before the first child counts
        T.eq(Arrays.asList("a"), items("<L>a<child>x,y</child>,z</L>"), "text after a child is a tail");
        T.eq(Arrays.asList("a", "b"), items("<L><![CDATA[a,b]]></L>"), "CDATA text");
        T.eq(Arrays.asList("a&b"), items("<L>a&amp;b</L>"), "entities decoded");
        // case and dots are kept
        T.eq(Arrays.asList("Net.TcpipHeapMax", "net.tcpipheapmax"), items("<L>Net.TcpipHeapMax,net.tcpipheapmax</L>"),
                "case sensitive");

        // a newline is not a separator (DV-60)
        CheckListParser.Parsed p = CheckListParser.parse("<L>first\nsecond,third</L>");
        T.eq(Arrays.asList("third"), p.items, "item with a line break is not used");
        T.eq(1, p.skipped.size(), "and is reported");
        T.contains(p.skipped.get(0), "separate items with commas", "with the advice");
        T.contains(p.skipped.get(0), "first second", "shown on one line");
        CheckListParser.Parsed tab = CheckListParser.parse("<L>a\tb,c</L>");
        T.eq(Arrays.asList("c"), tab.items, "tab inside an item");

        // not well-formed
        for (String bad : new String[] {"<L>a,b", "not xml", "<L>a</M>", "<L></L><L></L>", "<L>&nope;</L>"}) {
            try {
                CheckListParser.parse(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (CheckListParser.ParseFailure e) {
                T.check(e.getMessage() != null && !e.getMessage().isEmpty(), "reason for " + bad);
            }
        }
        // a DTD never expands an entity (no entity attack, no file read)
        try {
            CheckListParser.Parsed d = CheckListParser.parse("<!DOCTYPE L [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><L>&x;</L>");
            T.check(d.items.isEmpty() || !d.items.get(0).contains("/"), "external entity not read");
        } catch (CheckListParser.ParseFailure e) {
            // refusing the document is equally safe
        }
        try {
            CheckListParser.Parsed d = CheckListParser.parse("<!DOCTYPE L [<!ENTITY a \"AAAA\"><!ENTITY b \"&a;&a;&a;&a;\">]><L>&b;</L>");
            T.check(d.items.isEmpty() || d.items.get(0).length() <= 16, "entities not multiplied");
        } catch (CheckListParser.ParseFailure e) {
            // refused
        }
        T.done("check-list parser");
    }

    static void paths() {
        for (String ok : new String[] {"config.vm.name", "guest.toolsStatus", "summary.config.numCpu", "name", "A1.b2"}) {
            T.check(VmOptionPath.isValid(ok), ok);
        }
        for (String bad : new String[] {"", "a b", "config.hardware.device[0]", "a/b", "a.", ".a", "a..b", "1abc", "a.1b",
                "a-b", "a;b", "a'b", "x".repeat(257)}) {
            T.check(!VmOptionPath.isValid(bad), "refused: " + bad);
        }
        T.check(VmOptionPath.isValid("a".repeat(256)), "256 characters allowed");
        T.check(!VmOptionPath.isValid(null), "null");

        for (String ok : new String[] {"Dhcp", "MSSQL$SQLEXPRESS", "_x", "Win Defend", "a.b-c+d#e@f(g)", "9lives"}) {
            T.check(ServiceNameFilter.isSafe(ok), ok);
        }
        for (String bad : new String[] {"", " lead", "-lead", "a'b", "a\"b", "a`b", "a;b", "a|b", "a&b", "a<b", "a*b",
                "a$(x)", "a’b", "a\nb", "x".repeat(257)}) {
            T.check(!ServiceNameFilter.isSafe(bad), "refused: " + bad.replace("\n", "\\n"));
        }
        T.check(!ServiceNameFilter.isSafe(null), "null");
        T.done("VM option path and service name rules");
    }

    static void events() {
        EventListValidator.Result ok = EventListValidator.validate(
                "<Events><Log Name=\"System\"><EventID>7036</EventID><EventID>41</EventID></Log>"
                        + "<Log Name=\"Application\"><EventID>1000</EventID></Log></Events>");
        T.check(ok.ok(), "valid list: " + ok.problem);
        T.eq(2, ok.logs, "logs");
        T.eq(3, ok.eventIds, "event ids");
        EventListValidator.Result empty = EventListValidator.validate("<Events><!-- nothing yet --></Events>");
        T.check(empty.ok() && empty.logs == 0, "the shipped shape (all commented out) is valid and empty");
        String[][] bad = {
            {"", "empty"},
            {"<Events>", "well-formed"},
            {"<List><Log Name=\"x\"/></List>", "not <Events>"},
            {"<Events><Other/></Events>", "only <Log>"},
            {"<Events><Log><EventID>1</EventID></Log></Events>", "no Name"},
            {"<Events><Log Name=\" \"><EventID>1</EventID></Log></Events>", "no Name"},
            {"<Events><Log Name=\"S\"><EventID>abc</EventID></Log></Events>", "not a whole number"},
            {"<Events><Log Name=\"S\"><EventID>-1</EventID></Log></Events>", "not a whole number"},
            {"<Events><Log Name=\"S\"><Id>1</Id></Log></Events>", "only <EventID>"},
        };
        for (String[] b : bad) {
            EventListValidator.Result r = EventListValidator.validate(b[0]);
            T.check(!r.ok(), "refused: " + b[0]);
            T.contains(r.problem, b[1], "reason for " + b[0]);
        }
        T.done("event list validation");
    }

    /** A fetcher with a script of answers per path. */
    static final class Fake implements CheckListStore.Fetcher {
        final Map<String, Object> answers = new LinkedHashMap<>(); // path -> String body | Exception
        final List<String> paths = new ArrayList<>();

        void put(String name, Object answer) {
            String enc;
            try {
                enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
            answers.put("/api/configurations/files?path=SolutionConfig/" + enc + ".xml", answer);
        }

        @Override
        public String fetch(String path) throws IOException {
            paths.add(path);
            Object r = answers.get(path);
            if (r instanceof IOException) throw (IOException) r;
            if (r instanceof RuntimeException) throw (RuntimeException) r;
            if (r == null) throw new OpsHttpException("no such file", 404, null);
            return (String) r;
        }
    }

    static Map<ListKind, String> one(ListKind k, String name) {
        Map<ListKind, String> m = new EnumMap<>(ListKind.class);
        m.put(k, name);
        return m;
    }

    static void store() throws Exception {
        T.RecLog log = new T.RecLog();
        CheckListStore store = new CheckListStore(log);
        Fake f = new Fake();

        f.put("esx", "<L>Net.A, Net.B</L>");
        CheckListStore.Entry e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.OK, e.outcome, "ok");
        T.eq(Arrays.asList("Net.A", "Net.B"), e.items, "items");
        T.eq("2 item(s)", e.countText(), "count text");
        T.contains(e.summary(), "ESXi Advanced System Settings 'esx': OK, 2 item(s)", "summary");
        T.check(e.ok() && e.problem == null, "no problem");

        // transient failures keep the last good list (DV-27)
        f.put("esx", new java.net.SocketTimeoutException("slow"));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "fetch failure");
        T.eq(Arrays.asList("Net.A", "Net.B"), e.items, "last good stays in force");
        T.check(e.lastGood, "marked");
        T.contains(e.problem, "The last good list stays in use", "problem says so");
        T.contains(e.summary(), "last good list kept", "summary says so");

        f.put("esx", new OpsHttpException("x", 503, null));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "503");
        T.contains(e.problem, "HTTP 503", "status in the problem");

        f.put("esx", "<L>not closed");
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.PARSE_FAILED, e.outcome, "parse failure");
        T.eq(Arrays.asList("Net.A", "Net.B"), e.items, "last good stays in force on a parse failure");

        // an absent file clears it: 404, and 400 with apiErrorCode 1501
        f.put("esx", new OpsHttpException("x", 404, null));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.ABSENT, e.outcome, "404 is absent");
        T.check(e.items.isEmpty() && !e.lastGood, "list cleared");
        T.contains(e.problem, "does not exist", "problem");

        // An HTTP 400 that names no apiErrorCode (seen on 8.18.7) is not taken for an absent file:
        // it is a failed read, and it shows the answer's own words so the next look can settle it.
        f.put("esx", new OpsHttpException("x", 400, null, "{\"message\":\"Request parameter is invalid\"}"));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "400 without a code is a failed read");
        T.contains(e.problem, "HTTP 400; it said: {\"message\":\"Request parameter is invalid\"}", "the answer's words");
        f.put("esx", new OpsHttpException("x", 400, null, "y".repeat(500)));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.check(e.problem.length() < 400, "the excerpt is bounded: " + e.problem.length());
        f.put("esx", new OpsHttpException("x", 400, null));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.notContains(e.problem, "it said", "no excerpt, no clause");

        f.put("esx", "<L>x</L>");
        store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f);
        f.put("esx", new OpsHttpException("x", 400, 1501));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.ABSENT, e.outcome, "400 with 1501 is absent");
        f.put("esx", new OpsHttpException("x", 400, 1500));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "400 with another code is not 'absent'");
        f.put("esx", new OpsHttpException("x", 400, null));
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "400 with no code is not 'absent'");

        // first cycle after a start: nothing to fall back on, and it says so
        CheckListStore fresh = new CheckListStore(log);
        f.put("esx", new java.net.ConnectException("down"));
        e = fresh.refresh(one(ListKind.ESXI_SETTINGS, "esx"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Outcome.FETCH_FAILED, e.outcome, "cold fetch failure");
        T.check(e.items.isEmpty() && !e.lastGood, "empty");
        T.contains(e.problem, "No list is in use for it yet", "says nothing is in force");
        T.notContains(e.problem, "down", "the exception text is not copied into the problem");

        // a blank file is a valid empty list (parity); a blank name reads nothing; a path-like name reads nothing
        f.put("blank", "   ");
        e = store.refresh(one(ListKind.VM_PARAMS, "blank"), f).get(ListKind.VM_PARAMS);
        T.check(e.outcome == Outcome.OK && e.items.isEmpty(), "blank body: empty list");
        int reads = f.paths.size();
        e = store.refresh(one(ListKind.VM_PARAMS, ""), f).get(ListKind.VM_PARAMS);
        T.eq(Outcome.NOT_CONFIGURED, e.outcome, "blank name");
        e = store.refresh(one(ListKind.VM_PARAMS, "   "), f).get(ListKind.VM_PARAMS);
        T.eq(Outcome.NOT_CONFIGURED, e.outcome, "blank name (spaces)");
        for (String evil : new String[] {"../x", "a/b", "a\\b", "x?y=1", "..", ".", "a\nb"}) {
            e = store.refresh(one(ListKind.VM_PARAMS, evil), f).get(ListKind.VM_PARAMS);
            T.eq(Outcome.INVALID_NAME, e.outcome, "refused name " + evil.replace("\n", "\\n"));
            T.check(e.problem != null && e.problem.contains("not a plain file name"), "problem for " + evil);
        }
        T.eq(reads, f.paths.size(), "none of those reached the store");
        // what is not a path or a query is a plain name; it is encoded when sent
        for (String fine : new String[] {"a&b", "x#y", "%2e%2e", "a..b", "50%", "a b"}) {
            f.put(fine, "<L>q</L>");
            e = store.refresh(one(ListKind.VM_PARAMS, fine), f).get(ListKind.VM_PARAMS);
            T.eq(Outcome.OK, e.outcome, "plain name " + fine);
        }
        T.contains(f.paths.get(f.paths.size() - 3), "SolutionConfig/a..b.xml", "dots inside a name are kept");
        T.contains(f.paths.get(f.paths.size() - 2), "SolutionConfig/50%25.xml", "percent is encoded");

        // the path is encoded
        f.put("my list", "<L>a</L>");
        store.refresh(one(ListKind.ESXI_PACKAGES, "my list"), f);
        T.eq("/api/configurations/files?path=SolutionConfig/my%20list.xml", f.paths.get(f.paths.size() - 1), "encoded path");

        // item rules per list
        f.put("opts", "<L>config.version, bad path, summary.config[0].x, name</L>");
        e = store.refresh(one(ListKind.VM_OPTIONS, "opts"), f).get(ListKind.VM_OPTIONS);
        T.eq(Arrays.asList("config.version", "name"), e.items, "valid option paths kept");
        T.eq(2, e.skipped.size(), "two skipped");
        T.contains(e.skipped.get(0), "bad path", "named");
        T.check(!e.ok(), "an entry skipped is not 'ok'");
        T.contains(e.problem, "2 entries", "problem counts them");
        T.contains(e.summary(), "2 skipped", "summary counts them");

        f.put("svc", "<L>Dhcp, evil'; Remove-Item, Spooler</L>");
        e = store.refresh(one(ListKind.WIN_SERVICES, "svc"), f).get(ListKind.WIN_SERVICES);
        T.eq(Arrays.asList("Dhcp", "Spooler"), e.items, "unsafe service name left out");
        T.eq(1, e.skipped.size(), "reported");

        // other lists take items verbatim, even keys with | or :
        f.put("adv", "<L>Mem.Foo|Bar, x:y</L>");
        e = store.refresh(one(ListKind.ESXI_SETTINGS, "adv"), f).get(ListKind.ESXI_SETTINGS);
        T.eq(Arrays.asList("Mem.Foo|Bar", "x:y"), e.items, "verbatim");
        T.check(log.infos.stream().anyMatch(s -> s.contains("exactly that key")), "logged once at INFO");
        int infos = log.infos.size();
        store.refresh(one(ListKind.ESXI_SETTINGS, "adv"), f);
        T.eq(infos, log.infos.size(), "only once");

        // the event list: validated, kept raw, counted
        String ev = "<Events><Log Name=\"System\"><EventID>1</EventID><EventID>2</EventID></Log></Events>";
        f.put("ev", ev);
        e = store.refresh(one(ListKind.WIN_EVENTS, "ev"), f).get(ListKind.WIN_EVENTS);
        T.eq(Outcome.OK, e.outcome, "event list ok");
        T.eq(ev, e.rawXml, "raw text kept for the guest");
        T.eq("1 log(s), 2 event id(s)", e.countText(), "event list count text");
        f.put("ev", "<Events><Log><EventID>1</EventID></Log></Events>");
        e = store.refresh(one(ListKind.WIN_EVENTS, "ev"), f).get(ListKind.WIN_EVENTS);
        T.eq(Outcome.PARSE_FAILED, e.outcome, "a wrong structure is a reported problem");
        T.eq(ev, e.rawXml, "the last good event list stays in force");
        T.contains(e.problem, "Name", "says what is wrong");
        T.done("check-list store outcomes");
    }

    static void gating() {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("host", "h");
        VCommunityConfig off = VCommunityConfig.parse(m::get, "u", "p", null, null);
        T.eq(4, off.filesInUse().size(), "monitoring off: four files");
        m.put("serviceMonitoring", "Enabled");
        T.eq(5, VCommunityConfig.parse(m::get, "u", "p", null, null).filesInUse().size(), "services on");
        T.done("only the files in use are read");
    }
}
