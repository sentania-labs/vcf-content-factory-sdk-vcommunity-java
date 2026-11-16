package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.kpi.NetworkSpeed;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;
import com.vcfcf.adapters.vcommunity.ops.OpsReads;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The read-backs of the derived metrics, one by one (design 4.1, 7.1): what each answer is read as, how the ids
 * are batched, what a failed batch leaves, what a stop does. The values of the rows and KPIs are in
 * {@code KpiTest} and {@code DerivedTest}.
 */
public final class OpsReadsTest {

    static final long NOW = 1_700_000_000_000L;

    /** An Operations whose answer is made by a function of the path and the body. */
    interface Answer {
        String of(String path, String body) throws IOException;
    }

    static OpsApi ops(Answer a, List<String> log) {
        return new OpsApi() {
            @Override
            public String get(String path, String accept) throws IOException {
                log.add("GET " + path);
                return a.of(path, "");
            }

            @Override
            public String post(String path, String body) throws IOException {
                log.add("POST " + path);
                return a.of(path, body);
            }

            @Override
            public PushOutcome pushBatch(Map<String, Map<String, Object>> p, Map<String, Map<String, Double>> s, long t) {
                throw new AssertionError("reads do not push");
            }
        };
    }

    static String stat(String key, long ageMs, String... values) {
        return "{\"timestamps\":[" + (NOW - ageMs) + "],\"statKey\":{\"key\":" + Json.quote(key) + "},\"data\":["
                + String.join(",", values) + "]}";
    }

    static String res(String id, String... stats) {
        return "{\"resourceId\":" + Json.quote(id) + ",\"stat-list\":{\"stat\":[" + String.join(",", stats) + "]}}";
    }

    static String values(String... resources) {
        return "{\"values\":[" + String.join(",", resources) + "]}";
    }

    static List<String> ids(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add("r" + i);
        return l;
    }

    public static void main(String[] args) throws Exception {
        latest();
        instancedProperties();
        recordedNicProperties();
        recordedHostProperties();
        timestamps();
        history();
        properties();
        related();
        batching();
        sentinel();
        stopping();
        System.out.println("OpsReadsTest done");
    }

    static void latest() throws Exception {
        List<String> log = new ArrayList<>();
        OpsReads r = new OpsReads(ops((p, b) -> values(
                res("a", stat("cpu|x", 1000, "1", "2", "3"), stat("cpu|other", 1000, "9"), stat("cpu:26|x", 1000, "7"),
                        stat("cpu|old", 11 * 60_000L, "5")),
                res("b"),
                res("c", stat("cpu|x", 11 * 60_000L, "8"))), log), null);
        Map<String, Map<String, Double>> got = new LinkedHashMap<>();
        OpsReads.Report rep = r.latestStats(Arrays.asList("a", "b", "c"), Arrays.asList("cpu|x", "cpu|old"),
                OpsReads.STALE_MS, NOW, got::put);
        T.check(rep.ok() && rep.batches == 1, "one request");
        T.eq(3.0, got.get("a").get("cpu|x"), "the last sample is the latest");
        T.check(!got.get("a").containsKey("cpu|other") && !got.get("a").containsKey("cpu:26|x"),
                "only the keys asked for: an instance of a key is not the key");
        T.check(!got.get("a").containsKey("cpu|old"), "a sample older than two cycles is no data");
        T.check(got.get("b").isEmpty(), "a resource with nothing: an entry with no statistic, not a failure");
        T.check(got.get("c").isEmpty(), "a stale sample leaves nothing");
        T.contains(log.get(0), "/api/resources/stats/latest/query", "the latest statistics endpoint");
        T.done("read: latest statistics");

        // with no age limit a stale sample is kept; with a mismatch of samples and times the filter is not applied
        got.clear();
        new OpsReads(ops((p, b) -> values(res("c", stat("cpu|x", 11 * 60_000L, "8"))), new ArrayList<>()), null)
                .latestStats(Arrays.asList("c"), Arrays.asList("cpu|x"), -1, NOW, got::put);
        T.eq(8.0, got.get("c").get("cpu|x"), "no age limit");
        got.clear();
        String mismatch = "{\"timestamps\":[" + (NOW - 20 * 60_000L) + "],\"statKey\":{\"key\":\"cpu|x\"},\"data\":[1,2]}";
        new OpsReads(ops((p, b) -> values(res("c", mismatch)), new ArrayList<>()), null)
                .latestStats(Arrays.asList("c"), Arrays.asList("cpu|x"), OpsReads.STALE_MS, NOW, got::put);
        T.eq(2.0, got.get("c").get("cpu|x"), "the age filter needs one time per sample, as upstream");
        T.done("read: the age filter");

        // the key of a statistic list may be "stats", and a sample may be a numeric string
        got.clear();
        new OpsReads(ops((p, b) -> "{\"values\":[{\"resourceId\":\"a\",\"stats\":{\"stat\":[{\"timestamps\":["
                + (NOW - 1) + "],\"statKey\":{\"key\":\"cpu|x\"},\"data\":[\"4.5\"]}]}}]}", new ArrayList<>()), null)
                .latestStats(Arrays.asList("a"), Arrays.asList("cpu|x"), OpsReads.STALE_MS, NOW, got::put);
        T.eq(4.5, got.get("a").get("cpu|x"), "both spellings, strings that are numbers");
    }

    /**
     * Instanced property keys through {@code properties/latest/query}. The payload is a recorded-answer slot: a
     * real answer (from a live read-only call) can replace {@link #INSTANCED_PROPERTY_ANSWER} and the same
     * assertions run on it. The shape below is the one the reader is written against, not a recording.
     */
    static final String INSTANCED_PROPERTY_ANSWER = "{\"values\":[{\"resourceId\":\"h1\",\"property-contents\":"
            + "{\"property-content\":[{\"statKey\":\"net:vmnic0|speed\",\"values\":[\"1000.0\"]},"
            + "{\"statKey\":\"net:vmnic1|speed\",\"values\":[\"0.0\"]},"
            + "{\"statKey\":\"net:vmnic0|configuredSpeed\",\"values\":[\"10000.0\"]}]}},"
            + "{\"resourceId\":\"h2\"}]}";

    static void instancedProperties() throws Exception {
        List<String> bodies = new ArrayList<>();
        OpsReads r = new OpsReads(ops((p, b) -> {
            bodies.add(b);
            return INSTANCED_PROPERTY_ANSWER;
        }, new ArrayList<>()), null);
        Map<String, Map<String, String>> got = new LinkedHashMap<>();
        OpsReads.Report rep = r.latestProperties(Arrays.asList("h1", "h2"),
                Arrays.asList("net:vmnic0|speed", "net:vmnic1|speed", "net:vmnic0|configuredSpeed"), got::put);
        T.check(rep.ok(), "read");
        T.check(bodies.get(0).contains("\"net:vmnic0|speed\""), "the instanced key is sent as it is");
        T.eq("1000.0", got.get("h1").get("net:vmnic0|speed"), "by instance key");
        T.eq("10000.0", got.get("h1").get("net:vmnic0|configuredSpeed"), "configured");
        T.eq("0.0", got.get("h1").get("net:vmnic1|speed"), "a link that is down reads 0.0 and is kept as text");
        T.check(NetworkSpeed.parseSpeed(got.get("h1").get("net:vmnic1|speed")) == null, "and is no value as a speed");
        T.done("read: instanced properties");
    }

    static String recording(String path) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The text of the {@code response} member of a recording (it is the last member). */
    static String responseOf(String raw) {
        int i = raw.indexOf("\"response\":");
        return raw.substring(i + "\"response\":".length(), raw.lastIndexOf('}'));
    }

    /** Recorded host properties (GET, trimmed to runtime|*): 8.18.7 has no quarantine property, 9.0.2 has (DV-110). */
    static void recordedHostProperties() throws Exception {
        Map<String, Map<String, String>> byVersion = new LinkedHashMap<>();
        for (String f : new String[] {"8.18.7-host.json", "9.0.2-host.json"}) {
            Object rec = Json.parse(recording("tests/fixtures/host-properties/" + f));
            Map<String, String> m = new LinkedHashMap<>();
            for (Object p : Json.list(Json.get(Json.get(rec, "response"), "property"))) {
                m.put(Json.str(Json.get(p, "name")), Json.str(Json.get(p, "value")));
            }
            byVersion.put(f, m);
        }
        Map<String, String> v818 = byVersion.get("8.18.7-host.json");
        Map<String, String> v902 = byVersion.get("9.0.2-host.json");
        T.check(v818.containsKey(StatKeys.HOST_CONNECTION) && !v818.containsKey(StatKeys.HOST_QUARANTINE),
                "8.18.7: the other runtime properties exist and the quarantine one does not");
        T.eq("notInQuarantine", v902.get(StatKeys.HOST_QUARANTINE), "9.0.2 has it");
        T.eq(100.0, com.vcfcf.adapters.vcommunity.kpi.Availability.ofHost(1.0, v818,
                com.vcfcf.adapters.vcommunity.kpi.Availability.quarantineExists(Arrays.asList(v818))),
                "the real 8.18.7 host scores 100, not 75");
        T.eq(100.0, com.vcfcf.adapters.vcommunity.kpi.Availability.ofHost(1.0, v902,
                com.vcfcf.adapters.vcommunity.kpi.Availability.quarantineExists(Arrays.asList(v902))), "and the 9.0.2 host 100");
        T.done("read: recorded host runtime properties");
    }

    /** Recorded answers of the Suite API (9.0.2 and 8.18.7, 2026-11-16): the value is in a numeric {@code data} array. */
    static void recordedNicProperties() throws Exception {
        String dir = "tests/fixtures/nic-properties/";
        Object[][] cases = {
            {"devel-vcf-lab-mgmt-esx06-instanced-nic-both.json", 0.0, "link down reads 0.0"},
            {"devel-vcf-lab-osa-esx03-instanced-nic-both.json", 10000.0, "a fixed 10000"},
            {"devel_8_18-vcf-lab-wld01-esx01-instanced-nic-both.json", 1000.0, "8.18.7, 1000"},
        };
        for (Object[] c : cases) {
            String raw = recording(dir + c[0]);
            Object rec = Json.parse(raw);
            String id = Json.str(Json.list(Json.get(Json.get(rec, "body"), "resourceIds")).get(0));
            List<String> keys = new ArrayList<>();
            for (Object k : Json.list(Json.get(Json.get(rec, "body"), "propertyKeys"))) keys.add(Json.str(k));
            String answer = responseOf(raw);
            OpsReads r = new OpsReads(ops((p, b) -> answer, new ArrayList<>()), null);
            Map<String, Map<String, String>> got = new LinkedHashMap<>();
            OpsReads.Report rep = r.latestProperties(Arrays.asList(id), keys, got::put);
            T.check(rep.ok(), "read " + c[0]);
            String text = got.get(id).get("net:vmnic0|speed");
            T.check(text != null, "the numeric data array is read: " + c[2]);
            Double v = Double.valueOf(text);
            T.eq((Double) c[1], v, (String) c[2]);
            T.check(!got.get(id).containsKey("net:vmnic0|configuredSpeed"),
                    "a key the resource does not have is dropped by the platform and absent here");
            T.eq(c[1].equals(0.0) ? null : (Double) c[1], NetworkSpeed.parseSpeed(text), "as a speed: " + c[2]);
        }
        String emptyAnswer = responseOf(recording(dir + "devel-vcf-lab-mgmt-esx06-generic-net-speed-instanced-false.json"));
        Map<String, Map<String, String>> none = new LinkedHashMap<>();
        new OpsReads(ops((p, b) -> emptyAnswer, new ArrayList<>()), null)
                .latestProperties(Arrays.asList("x"), Arrays.asList("net|speed"), none::put);
        T.check(none.isEmpty(), "the generic key without the instanced flag answers nothing (why NIC names are asked for)");
        T.done("read: recorded NIC properties");
    }

    static void timestamps() throws Exception {
        OpsReads r = new OpsReads(ops((p, b) -> "{\"values\":[{\"resourceId\":\"a\",\"stat-list\":{\"stat\":["
                + "{\"timestamps\":[100,300,200],\"statKey\":{\"key\":\"k\"},\"data\":[1,2,3]},"
                + "{\"statKey\":{\"key\":\"none\"},\"data\":[1]}]}}]}", new ArrayList<>()), null);
        Map<String, Map<String, Double>> got = new LinkedHashMap<>();
        r.latestTimestamps(Arrays.asList("a"), Arrays.asList("k", "none"), got::put);
        T.eq(300.0, got.get("a").get("k"), "the newest time, however old");
        T.check(!got.get("a").containsKey("none"), "a statistic with no time is absent");
        T.done("read: times of the latest samples");
    }

    static void history() throws Exception {
        List<String> log = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        OpsReads r = new OpsReads(ops((p, b) -> {
            bodies.add(b);
            return values(res("a", stat("k", 99999999, "1", "2", "3")));
        }, log), null);
        Map<String, Map<String, List<Double>>> got = new LinkedHashMap<>();
        OpsReads.Report rep = r.history(ids(250).subList(0, 250), Arrays.asList("k"), 1000L, 2000L, 100, got::put);
        T.eq(3, rep.batches, "100 clusters a request");
        T.eq(Arrays.asList(1.0, 2.0, 3.0), got.get("a").get("k"), "oldest first, and an old sample is not dropped");
        T.contains(bodies.get(0), "\"rollUpType\":\"AVG\"", "averages");
        T.contains(bodies.get(0), "\"intervalType\":\"MINUTES\",\"intervalQuantifier\":5", "five minutes");
        T.contains(bodies.get(0), "\"begin\":1000,\"end\":2000", "the window");
        T.contains(log.get(0), "/api/resources/stats/query", "the history endpoint");
        T.done("read: history");
    }

    static void properties() throws Exception {
        OpsReads r = new OpsReads(ops((p, b) -> "{\"values\":[{\"resourceId\":\"a\",\"property-contents\":{\"property-content\":["
                + "{\"statKey\":\"t\",\"values\":[\"old\",\"Powered On\"]},{\"statKey\":\"n\",\"data\":[4.0]},"
                + "{\"statKey\":\"e\",\"values\":[]},{\"statKey\":\"\",\"values\":[\"x\"]}]}}]}", new ArrayList<>()), null);
        Map<String, Map<String, String>> got = new LinkedHashMap<>();
        r.latestProperties(Arrays.asList("a"), Arrays.asList("t", "n", "e"), got::put);
        T.eq("Powered On", got.get("a").get("t"), "text: the last value");
        T.eq("4", got.get("a").get("n"), "a number property");
        T.check(!got.get("a").containsKey("e"), "a property with no value is absent");
        T.done("read: properties");
    }

    static void related() throws Exception {
        String list = "{\"pageInfo\":{\"totalCount\":3},\"resourceList\":["
                + "{\"resourceKey\":{\"resourceKindKey\":\"VirtualMachine\"},\"links\":[{\"rel\":\"SELF\",\"href\":\"/suite-api/api/resources/abc-1?x=1\"}]},"
                + "{\"resourceKey\":{\"resourceKindKey\":\"Tier\"},\"links\":[{\"rel\":\"OTHER\",\"href\":\"/suite-api/api/resources/zzz\"}]},"
                + "{\"resourceKey\":{\"resourceKindKey\":\"K8S-Namespace\"},\"links\":[{\"rel\":\"SELF\",\"href\":\"/suite-api/api/resources/ns-2/relationships\"}]}]}";
        OpsReads r = new OpsReads(ops((p, b) -> list, new ArrayList<>()), null);
        List<OpsReads.Related> rel = r.related("p", "CHILD");
        T.eq(2, rel.size(), "an item with no SELF link is dropped");
        T.eq("abc-1", rel.get(0).id, "the id in the SELF link");
        T.eq("VirtualMachine", rel.get(0).kind, "its kind");
        T.eq("ns-2", rel.get(1).id, "up to the next slash");
        // a list that is longer than the page it came in is not the whole
        OpsReads paged = new OpsReads(ops((p, b) -> "{\"pageInfo\":{\"totalCount\":5000},\"resourceList\":[]}", new ArrayList<>()), null);
        try {
            paged.related("p", "CHILD");
            throw new AssertionError("a partial list was read as the whole");
        } catch (IOException expected) {
            T.contains(expected.getMessage(), "paged", "said");
        }
        try {
            new OpsReads(ops((p, b) -> {
                throw new OpsHttpException("x", 500, null);
            }, new ArrayList<>()), null).related("p", "PARENT");
            throw new AssertionError("a failure was read as no relations");
        } catch (OpsHttpException expected) {
            T.eq(500, expected.status(), "the status");
        }
        T.done("read: relationships");
    }

    static void batching() throws Exception {
        List<String> bodies = new ArrayList<>();
        OpsReads r = new OpsReads(ops((p, b) -> {
            bodies.add(b);
            if (bodies.size() == 2) throw new OpsHttpException("x", 500, null);
            return values();
        }, new ArrayList<>()), null);
        List<String> all = ids(1201);
        OpsReads.Report rep = r.latestStats(all, Arrays.asList("k"), OpsReads.STALE_MS, NOW, (id, s) -> { });
        T.eq(3, rep.batches, "500, 500, 201");
        T.eq(1, rep.failedBatches, "the middle one failed");
        T.eq(500, rep.failedIds.size(), "its ids, and only its ids");
        T.check(rep.failedIds.contains("r500") && rep.failedIds.contains("r999") && !rep.failedIds.contains("r499")
                && !rep.failedIds.contains("r1000"), "the right ones");
        T.check(!rep.ok() && !rep.ok("r700") && rep.ok("r1100"), "per id");
        T.contains(rep.firstError, "HTTP 500", "the first reason, no credential");
        T.eq(3, bodies.size(), "the batches after it were still read");
        for (String b : bodies) T.check(b.length() < 500 * 14 + 200, "a request names at most 500 resources");

        // an answer that is not JSON, and a network failure, fail their batch too
        for (Answer a : new Answer[] {(p, b) -> "<html>", (p, b) -> {
            throw new IOException("connection reset by a very long peer " + "x".repeat(400));
        }}) {
            OpsReads.Report bad = new OpsReads(ops(a, new ArrayList<>()), null)
                    .latestProperties(ids(3), Arrays.asList("k"), (id, p) -> { });
            T.check(bad.failedIds.size() == 3, "all three failed");
            T.check(bad.firstError.length() < 200, "bounded: " + bad.firstError.length());
        }
        T.eq(0, new OpsReads(ops((p, b) -> { throw new AssertionError("no request for no ids"); }, new ArrayList<>()), null)
                .latestStats(Collections.<String>emptyList(), Arrays.asList("k"), 1, NOW, (id, s) -> { }).batches, "no ids, no request");
        T.done("read: batches of 500, a failed batch is its ids and no more");
    }

    static void sentinel() throws Exception {
        Answer a = (p, b) -> values(res("a", stat("cpu|x", 1000, "-1"), stat(StatKeys.CPU_USABLE, 1000, "-1")));
        Map<String, Map<String, Double>> got = new LinkedHashMap<>();
        new OpsReads(ops(a, new ArrayList<>()), null)
                .latestStats(Arrays.asList("a"), Arrays.asList("cpu|x", StatKeys.CPU_USABLE), OpsReads.STALE_MS, NOW, got::put);
        T.eq(null, got.get("a").get("cpu|x"), "-1 is no sample, not a statistic");
        T.eq(Double.valueOf(-1), got.get("a").get(StatKeys.CPU_USABLE), "a usable capacity can be negative: always a value");
        T.done("read: -1 is no sample, and a usable capacity is signed");
    }

    static void stopping() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        List<String> log = new ArrayList<>();
        OpsReads r = new OpsReads(ops((p, b) -> {
            stop.set(true);
            return values();
        }, log), stop::get);
        OpsReads.Report rep = r.latestStats(ids(1200), Arrays.asList("k"), OpsReads.STALE_MS, NOW, (id, s) -> { });
        T.eq(1, log.size(), "one request, then the deadline");
        T.check(rep.stopped && rep.failedIds.size() == 700, "the rest is failed, not empty: " + rep.failedIds.size());
        Thread.currentThread().interrupt();
        try {
            OpsReads.Report i = new OpsReads(ops((p, b) -> values(), log), null)
                    .latestStats(ids(3), Arrays.asList("k"), OpsReads.STALE_MS, NOW, (id, s) -> { });
            T.check(i.stopped && i.failedIds.size() == 3, "an interrupt stops a read");
        } finally {
            Thread.interrupted();
        }
        T.done("read: the deadline and an interrupt stop between batches");
    }
}
