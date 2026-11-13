package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.ops.LastPushed;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.ops.PushSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Push set, last-pushed filter, resource identity, JSON. */
public final class OpsTest {

    public static void main(String[] a) throws Exception {
        json();
        pushSet();
        lastPushed();
        resourceMap();
        vcenterObject();
        noPageInfo();
    }

    static String bare(String... rows) {
        return "{\"resourceList\":[" + String.join(",", rows) + "]}";
    }

    /** Without a total, only a short page is known to be the last. */
    static void noPageInfo() throws Exception {
        FakeOps small = new FakeOps();
        small.pages.add(bare(row("a", "m1", "VC-A"), row("b", "m2", "VC-A")));
        T.eq(2, OpsResourceMap.load(small, "VC-A", "HostSystem").size(), "a short page with no total is the whole list");
        T.eq(1, small.paths.size(), "one read");

        FakeOps full = new FakeOps();
        String[] rows = new String[OpsResourceMap.PAGE_SIZE];
        for (int i = 0; i < rows.length; i++) rows[i] = row("id" + i, "m" + i, "VC-A");
        full.pages.add(bare(rows));
        full.pages.add(bare(row("last", "mlast", "VC-A")));
        OpsResourceMap m = OpsResourceMap.load(full, "VC-A", "HostSystem");
        T.eq(OpsResourceMap.PAGE_SIZE + 1, m.size(), "a full page with no total is followed by another read");
        T.eq(2, full.paths.size(), "two reads");
        T.eq("last", m.resourceId("mlast"), "the row on the second page");

        FakeOps endless = new FakeOps();
        for (int i = 0; i < OpsResourceMap.MAX_PAGES + 2; i++) endless.pages.add(bare(rows));
        try {
            OpsResourceMap.load(endless, "VC-A", "HostSystem");
            throw new AssertionError("an endless list was accepted");
        } catch (IOException e) {
            T.contains(e.getMessage(), "longer than", "fails loudly");
        }
        FakeOps vc = new FakeOps();
        vc.pages.add(bare(row("v1", null, "VC-A")));
        T.check(OpsResourceMap.findVcenterObject(vc, "VC-A").resolved(), "vCenter object with no total");
        T.done("a page with no total is not assumed to be the whole list");
    }

    static void json() {
        Object o = Json.parse("{\"a\":[1,2.5,\"x\\n\\u0041\",true,null],\"b\":{\"c\":\"d\"}}");
        T.eq("d", Json.str(Json.get(Json.get(o, "b"), "c")), "nested");
        List<Object> l = Json.list(Json.get(o, "a"));
        T.eq(5, l.size(), "list");
        T.eq("1", Json.str(l.get(0)), "integer renders without .0");
        T.eq("2.5", Json.str(l.get(1)), "fraction");
        T.eq("x\nA", Json.str(l.get(2)), "escapes");
        T.check(l.get(4) == null, "null");
        T.eq(0, Json.list(null).size(), "no list");
        T.check(Json.get("x", "k") == null, "get on a non-map");
        for (String bad : new String[] {"", "{", "[1,", "{\"a\" 1}", "{\"a\":1} x", "tru", "\"abc", "[1 2]", "01x", "[" + "[".repeat(100)}) {
            try {
                Json.parse(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (IllegalArgumentException e) {
                // refused
            }
        }
        T.eq("\"a\\\"b\\n\"", Json.quote("a\"b\n"), "quote");
        T.eq("null", Json.quote(null), "quote null");
        T.done("json");
    }

    static void pushSet() {
        T.RecLog log = new T.RecLog();
        PushSet ps = new PushSet(log);
        T.check(ps.isEmpty(), "starts empty");
        T.check(ps.prop("r1", "k", "v"), "good prop");
        T.check(ps.stat("r1", "m", 1.5), "good stat");
        T.check(!ps.prop(null, "k", "v"), "null id");
        T.check(!ps.prop("  ", "k", "v"), "blank id");
        T.check(!ps.prop("r1", null, "v"), "null key");
        T.check(!ps.prop("r1", "", "v"), "blank key");
        T.check(!ps.prop("r1", "k2", null), "null value");
        T.check(!ps.stat("r1", "m2", Double.NaN), "NaN");
        T.check(!ps.stat("r1", "m3", Double.POSITIVE_INFINITY), "infinity");
        T.check(!ps.stat("r1", "m3", Double.NEGATIVE_INFINITY), "negative infinity");
        T.check(!ps.stat("", "m", 1), "stat blank id");
        T.eq(1, ps.propertyCount(), "one property queued");
        T.eq(1, ps.statCount(), "one stat queued");
        T.eq(9, ps.refused(), "nine refusals counted as programming errors");
        T.check(ps.stat("r1", "zero", 0.0), "a real zero is a value");
        T.check(ps.prop("r1", "empty", ""), "an empty string is a value the source returned");
        // one WARN per key
        int warnsForM3 = 0;
        for (String w : log.warns) if (w.contains("'m3'")) warnsForM3++;
        T.eq(1, warnsForM3, "one warning per key");
        PushSet empty = new PushSet(null);
        T.check(empty.isEmpty() && empty.properties().isEmpty() && empty.stats().isEmpty(), "all-absent input gives an empty set");
        ps.stat("r1", "m", 2.5);
        T.eq(2.5, ps.stats().get("r1").get("m"), "a later value replaces");
        T.eq(2, ps.statCount(), "replacing does not double count (m and zero)");
        T.done("push set refuses what must never be sent");
    }

    static Map<String, Map<String, Object>> props(String... kv) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.computeIfAbsent(kv[i], k -> new LinkedHashMap<>()).put(kv[i + 1], kv[i + 2]);
        }
        return m;
    }

    static void lastPushed() {
        LastPushed lp = new LastPushed(12);
        Map<String, Map<String, Object>> all = props("r1", "a", "1", "r1", "b", "2", "r2", "a", "1");
        LastPushed.Selection s1 = lp.select(all, 1);
        T.eq(3, s1.toSend().values().stream().mapToInt(Map::size).sum(), "first cycle sends everything");
        s1.commit();
        LastPushed.Selection s2 = lp.select(all, 2);
        T.check(s2.toSend().isEmpty(), "nothing changed, nothing sent");
        T.eq(3, s2.unchanged(), "three held back");
        LastPushed.Selection s3 = lp.select(props("r1", "a", "1", "r1", "b", "CHANGED", "r2", "a", "1"), 3);
        T.eq(1, s3.toSend().values().stream().mapToInt(Map::size).sum(), "one changed value sent");
        T.eq("CHANGED", s3.toSend().get("r1").get("b"), "the changed one");
        // not committed (the push failed): sent again
        LastPushed.Selection s4 = lp.select(props("r1", "a", "1", "r1", "b", "CHANGED", "r2", "a", "1"), 4);
        T.eq(1, s4.toSend().values().stream().mapToInt(Map::size).sum(), "an uncommitted value is sent again");
        s4.commit();
        T.check(lp.select(all.isEmpty() ? all : props("r1", "a", "1", "r1", "b", "CHANGED", "r2", "a", "1"), 5).toSend().isEmpty(),
                "committed");
        // full refresh every 12th cycle
        LastPushed.Selection s12 = lp.select(props("r1", "a", "1", "r1", "b", "CHANGED", "r2", "a", "1"), 12);
        T.eq(3, s12.toSend().values().stream().mapToInt(Map::size).sum(), "the 12th cycle sends everything");
        T.eq(3, lp.select(props("r1", "a", "1", "r1", "b", "CHANGED", "r2", "a", "1"), 24).toSend().values().stream().mapToInt(Map::size).sum(), "and the 24th");
        // a restart sends everything
        T.eq(3, new LastPushed(12).select(all, 7).toSend().values().stream().mapToInt(Map::size).sum(), "cold start");
        // an empty value is a change from a value
        LastPushed lp2 = new LastPushed(12);
        lp2.select(props("r", "k", "x"), 1).commit();
        T.eq(1, lp2.select(props("r", "k", ""), 2).toSend().size(), "'' differs from 'x'");
        lp2.clear();
        T.eq(1, lp2.select(props("r", "k", "x"), 3).toSend().size(), "clear forgets");
        T.done("properties are pushed on change, refreshed, and resent after a failed push");
    }

    /** A fake Suite API serving a paged resource list. */
    static final class FakeOps implements OpsApi {
        final List<String> pages = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        IOException fail;
        int failOnPage = -1;

        @Override
        public String get(String path, String accept) throws IOException {
            paths.add(path);
            int p = Integer.parseInt(path.substring(path.lastIndexOf("page=") + 5));
            if (fail != null && (failOnPage < 0 || failOnPage == p)) throw fail;
            return p < pages.size() ? pages.get(p) : "{\"pageInfo\":{\"totalCount\":0},\"resourceList\":[]}";
        }

        @Override
        public String post(String path, String jsonBody) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PushOutcome pushBatch(Map<String, Map<String, Object>> properties, Map<String, Map<String, Double>> stats, long ts) {
            throw new UnsupportedOperationException();
        }
    }

    static String row(String id, String moref, String vcid) {
        StringBuilder ids = new StringBuilder("[");
        if (moref != null) ids.append("{\"identifierType\":{\"name\":\"VMEntityObjectID\"},\"value\":\"").append(moref).append("\"}");
        if (vcid != null) {
            if (moref != null) ids.append(',');
            ids.append("{\"identifierType\":{\"name\":\"VMEntityVCID\"},\"value\":\"").append(vcid).append("\"}");
        }
        ids.append(']');
        return "{\"identifier\":" + (id == null ? "null" : "\"" + id + "\"") + ",\"resourceKey\":{\"resourceIdentifiers\":" + ids + "}}";
    }

    static String page(int total, String... rows) {
        return "{\"pageInfo\":{\"totalCount\":" + total + "},\"resourceList\":[" + String.join(",", rows) + "]}";
    }

    static void resourceMap() throws Exception {
        FakeOps ops = new FakeOps();
        ops.pages.add(page(9,
                row("id-1", "host-1", "VC-A"),
                row("id-2", "host-2", "VC-A"),
                row("id-3", "host-1", "VC-B"),        // another vCenter: same MoRef, never matched
                row("id-4", "host-9", null),          // no vCenter id
                row("id-5", null, "VC-A"),            // no MoRef
                row(null, "host-7", "VC-A"),          // no resource id
                row("id-6", "host-5", "VC-A"),
                row("id-7", "host-5", "VC-A"),        // second resource, same MoRef: ambiguous
                row("id-1", "host-1", "VC-A")));      // the same row twice (paging over a changing list)
        OpsResourceMap m = OpsResourceMap.load(ops, "VC-A", "HostSystem");
        T.eq("id-1", m.resourceId("host-1"), "matched on VCID plus MoRef");
        T.eq("id-2", m.resourceId("host-2"), "second");
        T.check(m.resourceId("host-9") == null, "no vCenter id: never matched");
        T.check(m.resourceId("host-7") == null, "no resource id");
        T.check(m.resourceId("host-5") == null, "ambiguous MoRef matches neither");
        T.check(m.ambiguous().contains("host-5"), "reported as ambiguous");
        T.check(m.resourceId(null) == null && m.resourceId("host-404") == null, "unknown");
        T.eq(2, m.size(), "two matched");
        T.eq(1, m.otherVcenter(), "one row of another vCenter");
        T.eq(3, m.notIndexed(), "three rows without identity");
        T.eq(9, m.rows(), "rows");
        T.contains(m.toString(), "2 matched of 9 rows", "summary");
        T.eq("/api/resources?adapterKind=VMWARE&resourceKind=HostSystem&pageSize=5000&page=0", ops.paths.get(0), "path");
        T.contains(OpsResourceMap.path("VirtualMachine", 3), "pageSize=500&page=3", "VM page size");
        T.contains(OpsResourceMap.path("VMwareAdapter Instance", 0), "resourceKind=VMwareAdapter%20Instance", "encoded kind");

        // paging: totalCount spans pages
        FakeOps two = new FakeOps();
        two.pages.add(page(3, row("a", "m1", "VC-A"), row("b", "m2", "VC-A")));
        two.pages.add(page(3, row("c", "m3", "VC-A")));
        OpsResourceMap pm = OpsResourceMap.load(two, "VC-A", "HostSystem");
        T.eq(3, pm.size(), "both pages read");
        T.eq(2, pm.pages(), "two pages");

        // a failed or short read throws: the caller keeps its previous map
        FakeOps broken = new FakeOps();
        broken.pages.add(page(3, row("a", "m1", "VC-A")));
        broken.pages.add(page(3)); // ends early
        try {
            OpsResourceMap.load(broken, "VC-A", "HostSystem");
            throw new AssertionError("a short list was accepted");
        } catch (IOException e) {
            T.contains(e.getMessage(), "ended after 1 of 3", "says how short");
        }
        FakeOps down = new FakeOps();
        down.fail = new java.net.ConnectException("x");
        try {
            OpsResourceMap.load(down, "VC-A", "HostSystem");
            throw new AssertionError("no failure");
        } catch (IOException e) {
            T.eq(2, down.paths.size(), "a dropped connection is retried once");
        }
        FakeOps http = new FakeOps();
        http.fail = new OpsHttpException("x", 500, null);
        try {
            OpsResourceMap.load(http, "VC-A", "HostSystem");
            throw new AssertionError("no failure");
        } catch (OpsHttpException e) {
            T.eq(1, http.paths.size(), "an HTTP error is not retried");
        }
        FakeOps garbage = new FakeOps();
        garbage.pages.add("<html>");
        try {
            OpsResourceMap.load(garbage, "VC-A", "HostSystem");
            throw new AssertionError("garbage accepted");
        } catch (IOException e) {
            T.contains(e.getMessage(), "not valid JSON", "garbage");
        }
        try {
            OpsResourceMap.load(new FakeOps(), "", "HostSystem");
            throw new AssertionError("no VCID accepted");
        } catch (IOException e) {
            T.contains(e.getMessage(), "UUID is unknown", "no VCID, no match");
        }
        T.done("resource identity is vCenter UUID plus MoRef, never a name");
    }

    static void vcenterObject() throws Exception {
        FakeOps ops = new FakeOps();
        ops.pages.add(page(3, row("v1", null, "VC-A"), row("v2", null, "VC-B"), row("v3", null, null)));
        OpsResourceMap.VcenterObject o = OpsResourceMap.findVcenterObject(ops, "VC-A");
        T.check(o.resolved(), "exactly one");
        T.eq("v1", o.resourceId(), "the matching object");
        T.eq(1, o.matches(), "one match");
        T.contains(ops.paths.get(0), "resourceKind=VMwareAdapter%20Instance", "kind");

        OpsResourceMap.VcenterObject none = OpsResourceMap.findVcenterObject(ops, "VC-Z");
        T.check(!none.resolved() && none.matches() == 0, "none carries it: no match");

        FakeOps dup = new FakeOps();
        dup.pages.add(page(2, row("v1", null, "VC-A"), row("v9", null, "VC-A")));
        OpsResourceMap.VcenterObject two = OpsResourceMap.findVcenterObject(dup, "VC-A");
        T.check(!two.resolved() && two.matches() == 2, "two carry it: never guessed");

        FakeOps same = new FakeOps();
        same.pages.add(page(2, row("v1", null, "VC-A"), row("v1", null, "VC-A")));
        T.check(OpsResourceMap.findVcenterObject(same, "VC-A").resolved(), "the same resource twice is one");

        try {
            OpsResourceMap.findVcenterObject(ops, null);
            throw new AssertionError("null VCID accepted");
        } catch (IOException e) {
            // refused
        }
        T.done("the vCenter object is the one whose UUID matches, exactly one");
    }
}
