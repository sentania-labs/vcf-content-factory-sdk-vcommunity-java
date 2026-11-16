package com.vcfcf.adapters.vcommunity.ops;

import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.json.JsonPull;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * What the derived metrics read back from VCF Operations (design 4.1, 7.1; map D1-D12): latest statistics,
 * the time of the latest sample, latest properties, five-minute history and relationships, all through the
 * one {@link OpsApi} (the Suite API client the pak already has). Each read is batched and bounded, handing
 * every resource of an answer to a sink as it is parsed so that no whole answer is kept; memory is one batch.
 *
 * <p>Honest by construction: a batch that fails (a non-2xx answer, a network error, an answer that is not
 * the expected JSON, a sample that is not a number) is not an empty answer. Its ids are in
 * {@link Report#failedIds}, nothing of it reaches the sink, and the caller withholds what depended on them.
 * A resource that answers with no statistic is an answer, not a failure. A read stops between batches at the
 * cycle deadline or an interrupt; the ids not yet asked for are then failed ({@link Report#stopped}).
 */
public final class OpsReads {

    /** Resources one request asks for. Bounded so the answer of one request stays a few megabytes. */
    public static final int BATCH = 500;
    /**
     * Hosts one statistics request asks for. A host answers a series for every CPU thread and every NIC as well as
     * the keys asked for (a 128-thread host is hundreds of series), so a host batch is a tenth of a VM batch.
     */
    public static final int HOST_BATCH = 50;
    /** The 5-minute interval of one stored sample. */
    public static final int CYCLE_MINUTES = 5;
    /** A latest statistic older than two cycles is no data; a configuration count keeps 15 minutes. */
    public static final long STALE_MS = 2L * CYCLE_MINUTES * 60_000L;
    public static final long CONFIGURATION_STALE_MS = 15L * 60_000L;

    /** Related objects asked for in one answer; a list longer than this is refused, not cut. */
    public static final int RELATIONS_PER_PAGE = 1000;

    public static final String LATEST_STATS = "/api/resources/stats/latest/query";
    public static final String STATS_QUERY = "/api/resources/stats/query";
    public static final String LATEST_PROPERTIES = "/api/resources/properties/latest/query";

    /** How a read ended. */
    public static final class Report {
        public int batches;
        public int failedBatches;
        public final Set<String> failedIds = new HashSet<>();
        /** The first failure's credential-free text, or null. */
        public String firstError;
        /** The read ended at the deadline or an interrupt; the ids it did not reach are in failedIds. */
        public boolean stopped;

        public boolean ok() {
            return failedIds.isEmpty();
        }

        /** True when every id of the read was answered. */
        public boolean ok(String id) {
            return !failedIds.contains(id);
        }
    }

    /** A resource of a latest-statistics answer, with its latest value of each statistic it has. */
    public interface StatSink {
        void resource(String id, Map<String, Double> stats);
    }

    public interface TimestampSink {
        void resource(String id, Map<String, Double> latestMs);
    }

    public interface PropertySink {
        void resource(String id, Map<String, String> props);
    }

    public interface HistorySink {
        void resource(String id, Map<String, List<Double>> samples);
    }

    /** One object related to another: its resource kind key and its id. */
    public static final class Related {
        public final String kind;
        public final String id;

        public Related(String kind, String id) {
            this.kind = kind;
            this.id = id;
        }
    }

    private final OpsApi ops;
    private final BooleanSupplier stop;

    public OpsReads(OpsApi ops, BooleanSupplier stop) {
        this.ops = ops;
        this.stop = stop == null ? () -> false : stop;
    }

    // ------------------------------------------------------------------ batching

    private interface Batch {
        void run(List<String> ids) throws IOException, InterruptedException;
    }

    private Report batched(List<String> ids, int size, Batch b) throws InterruptedException {
        Report r = new Report();
        for (int from = 0; from < ids.size(); from += size) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + size));
            if (Thread.currentThread().isInterrupted() || stop.getAsBoolean()) {
                r.stopped = true;
                for (int k = from; k < ids.size(); k++) r.failedIds.add(ids.get(k));
                if (r.firstError == null) r.firstError = "the cycle deadline was reached";
                return r;
            }
            r.batches++;
            try {
                b.run(part);
            } catch (IOException | RuntimeException e) {
                r.failedBatches++;
                r.failedIds.addAll(part);
                if (r.firstError == null) r.firstError = describe(e);
            }
        }
        return r;
    }

    /** A credential-free, one-line text of what went wrong. */
    static String describe(Exception e) {
        if (e instanceof OpsHttpException) {
            return "VCF Operations answered HTTP " + ((OpsHttpException) e).status();
        }
        if (e instanceof IOException) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + bound(e.getMessage()));
        }
        return "an answer that is not what was expected (" + e.getClass().getSimpleName() + ")";
    }

    private static String bound(String s) {
        String t = s.replaceAll("[\\p{Cntrl}]", " ");
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    private static String idList(List<String> ids) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Json.quote(ids.get(i)));
        }
        return b.append(']').toString();
    }

    private static String strList(List<String> keys) {
        return idList(keys);
    }

    private Object post(String path, String body) throws IOException, InterruptedException {
        String answer = ops.post(path, body);
        try {
            return Json.parse(answer);
        } catch (IllegalArgumentException e) {
            throw new IOException("the answer of " + path + " is not valid JSON");
        }
    }

    // ------------------------------------------------------------------ statistics

    /** One statistic of one resource as the answer holds it: its key, the times and the samples (a Double, a numeric String or null). */
    private static final class Stat {
        String key;
        double[] ts = new double[0];
        Object[] data = new Object[0];
    }

    private interface Resources {
        void resource(String id, List<Stat> stats);
    }

    private interface Wanted {
        boolean test(String key);
    }

    private static JsonPull.Token want(JsonPull p, JsonPull.Token expected) {
        JsonPull.Token t = p.next();
        if (t != expected) throw new IllegalArgumentException("the answer is not the expected JSON (" + t + ")");
        return t;
    }

    /**
     * Read a statistics answer as a stream: for each resource it names, the statistics whose key the test accepts. The
     * statistics it does not want (a platform answers every instance of a key it is asked for, per CPU thread, disk
     * and NIC) are read past and dropped, so memory is the text and one statistic. Nothing is handed to the sink
     * before the whole text has been read.
     */
    private static void readStats(String body, Wanted wanted, Resources sink) {
        JsonPull p = new JsonPull(body);
        Map<String, List<Stat>> byId = new LinkedHashMap<>();
        want(p, JsonPull.Token.BEGIN_OBJECT);
        for (JsonPull.Token t = p.next(); t != JsonPull.Token.END_OBJECT; t = p.next()) {
            if (t != JsonPull.Token.NAME) throw new IllegalArgumentException("a name expected");
            JsonPull.Token v = p.next();
            if (!"values".equals(p.text())) {
                p.skip(v);
                continue;
            }
            if (v == JsonPull.Token.NULL) continue;
            if (v != JsonPull.Token.BEGIN_ARRAY) throw new IllegalArgumentException("values is not a list");
            for (JsonPull.Token r = p.next(); r != JsonPull.Token.END_ARRAY; r = p.next()) {
                if (r != JsonPull.Token.BEGIN_OBJECT) {
                    p.skip(r);
                    continue;
                }
                resource(p, wanted, byId);
            }
        }
        if (p.next() != JsonPull.Token.END) throw new IllegalArgumentException("text after the answer");
        for (Map.Entry<String, List<Stat>> e : byId.entrySet()) sink.resource(e.getKey(), e.getValue());
    }

    private static void resource(JsonPull p, Wanted wanted, Map<String, List<Stat>> byId) {
        String id = null;
        List<Stat> stats = new ArrayList<>();
        for (JsonPull.Token t = p.next(); t != JsonPull.Token.END_OBJECT; t = p.next()) {
            String name = p.text();
            JsonPull.Token v = p.next();
            if ("resourceId".equals(name) && v == JsonPull.Token.STRING) {
                id = p.text();
            } else if (("stat-list".equals(name) || "stats".equals(name)) && v == JsonPull.Token.BEGIN_OBJECT) {
                for (JsonPull.Token n = p.next(); n != JsonPull.Token.END_OBJECT; n = p.next()) {
                    JsonPull.Token lv = p.next();
                    if (!"stat".equals(p.text()) || lv != JsonPull.Token.BEGIN_ARRAY) {
                        p.skip(lv);
                        continue;
                    }
                    for (JsonPull.Token st = p.next(); st != JsonPull.Token.END_ARRAY; st = p.next()) {
                        if (st != JsonPull.Token.BEGIN_OBJECT) {
                            p.skip(st);
                            continue;
                        }
                        Stat one = oneStat(p);
                        if (one.key != null && wanted.test(one.key)) stats.add(one);
                    }
                }
            } else {
                p.skip(v);
            }
        }
        if (id != null) byId.computeIfAbsent(id, k -> new ArrayList<>()).addAll(stats);
    }

    private static Stat oneStat(JsonPull p) {
        Stat s = new Stat();
        for (JsonPull.Token t = p.next(); t != JsonPull.Token.END_OBJECT; t = p.next()) {
            String name = p.text();
            JsonPull.Token v = p.next();
            if ("statKey".equals(name) && v == JsonPull.Token.BEGIN_OBJECT) {
                for (JsonPull.Token n = p.next(); n != JsonPull.Token.END_OBJECT; n = p.next()) {
                    String f = p.text();
                    JsonPull.Token fv = p.next();
                    if ("key".equals(f) && fv == JsonPull.Token.STRING) s.key = p.text();
                    else p.skip(fv);
                }
            } else if ("timestamps".equals(name) && v == JsonPull.Token.BEGIN_ARRAY) {
                List<Object> l = list(p);
                s.ts = new double[l.size()];
                for (int i = 0; i < s.ts.length; i++) s.ts[i] = sampleNumber(l.get(i));
            } else if ("data".equals(name) && v == JsonPull.Token.BEGIN_ARRAY) {
                s.data = list(p).toArray();
            } else {
                p.skip(v);
            }
        }
        return s;
    }

    /** The elements of the array just opened: a Double, a String, or null for a JSON null (a nested value is refused). */
    private static List<Object> list(JsonPull p) {
        List<Object> l = new ArrayList<>(2);
        for (JsonPull.Token t = p.next(); t != JsonPull.Token.END_ARRAY; t = p.next()) {
            switch (t) {
                case NUMBER: l.add(p.number()); break;
                case STRING: l.add(p.text()); break;
                case NULL: l.add(null); break;
                default: throw new IllegalArgumentException("a sample that is not a number");
            }
        }
        return l;
    }

    private static double sampleNumber(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException("a statistic sample is not a number");
    }

    /** The samples of one statistic that are not older than {@code oldestMs} (all when the filter is off). */
    private static List<Double> samples(Stat st, double oldestMs, boolean filter) {
        if (st.data.length == 0) return Collections.emptyList();
        List<Double> out = new ArrayList<>(Math.min(st.data.length, 8));
        // the age filter applies only when there is one time per sample
        boolean byTime = filter && st.ts.length == st.data.length;
        for (int i = 0; i < st.data.length; i++) {
            if (byTime && st.ts[i] < oldestMs) continue;
            out.add(sampleNumber(st.data[i]));
        }
        return out;
    }

    private String postText(String path, String body) throws IOException, InterruptedException {
        return ops.post(path, body);
    }

    private static String statBody(List<String> ids, List<String> keys) {
        return "{\"resourceId\":" + idList(ids) + ",\"statKey\":" + strList(keys) + "}";
    }

    /**
     * The latest value of each requested statistic of each resource, older than {@code maxAgeMs} dropped
     * (negative: kept whatever its age). {@code sink} gets every resource the answer names, with the
     * statistics that have a sample; a resource with none gets an empty map.
     *
     * @param nowMs the time the age is measured from (the cycle's start, so the read is repeatable)
     */
    public Report latestStats(List<String> ids, List<String> keys, long maxAgeMs, long nowMs, StatSink sink)
            throws InterruptedException {
        return latestStats(ids, keys, maxAgeMs, nowMs, BATCH, sink);
    }

    /** {@link #latestStats(List, List, long, long, StatSink)} with the number of resources a request asks for given. */
    public Report latestStats(List<String> ids, List<String> keys, long maxAgeMs, long nowMs, int perRequest,
            StatSink sink) throws InterruptedException {
        final Set<String> wanted = new HashSet<>(keys);
        final double oldest = maxAgeMs < 0 ? Double.NEGATIVE_INFINITY : nowMs - maxAgeMs;
        return batched(ids, perRequest, part -> {
            Map<String, Map<String, Double>> got = new LinkedHashMap<>();
            readStats(postText(LATEST_STATS, statBody(part, keys)), wanted::contains, (id, stats) -> {
                Map<String, Double> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                for (Stat st : stats) {
                    List<Double> s = samples(st, oldest, maxAgeMs >= 0);
                    if (s.isEmpty()) continue;
                    double v = s.get(s.size() - 1);
                    // -1 is the platform's "no sample" marker, not a reading (DV-112), except for the two usable
                    // capacities, which can really be negative
                    if (v == -1.0 && !StatKeys.SIGNED.contains(st.key)) {
                        continue;
                    }
                    m.put(st.key, v);
                }
            });
            for (Map.Entry<String, Map<String, Double>> e : got.entrySet()) sink.resource(e.getKey(), e.getValue());
        });
    }

    /** The time of the latest sample of each statistic, however old it is; a statistic never stored is absent. */
    public Report latestTimestamps(List<String> ids, List<String> keys, TimestampSink sink)
            throws InterruptedException {
        final Set<String> wanted = new HashSet<>(keys);
        return batched(ids, BATCH, part -> {
            Map<String, Map<String, Double>> got = new LinkedHashMap<>();
            readStats(postText(LATEST_STATS, statBody(part, keys)), wanted::contains, (id, stats) -> {
                Map<String, Double> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                for (Stat st : stats) {
                    if (st.ts.length == 0) continue;
                    double max = Double.NEGATIVE_INFINITY;
                    for (double t : st.ts) max = Math.max(max, t);
                    m.put(st.key, max);
                }
            });
            for (Map.Entry<String, Map<String, Double>> e : got.entrySet()) sink.resource(e.getKey(), e.getValue());
        });
    }

    /**
     * The five-minute averages of each statistic between two times, oldest first. A statistic with no sample is
     * absent from its resource's map.
     *
     * @param perRequest resources one request asks for (history is long, so fewer than a latest read)
     */
    public Report history(List<String> ids, List<String> keys, long beginMs, long endMs, int perRequest,
            HistorySink sink) throws InterruptedException {
        final Set<String> wanted = new HashSet<>(keys);
        return batched(ids, perRequest, part -> {
            String body = "{\"resourceId\":" + idList(part) + ",\"statKey\":" + strList(keys) + ",\"begin\":"
                    + beginMs + ",\"end\":" + endMs + ",\"rollUpType\":\"AVG\",\"intervalType\":\"MINUTES\","
                    + "\"intervalQuantifier\":" + CYCLE_MINUTES + "}";
            Map<String, Map<String, List<Double>>> got = new LinkedHashMap<>();
            readStats(postText(STATS_QUERY, body), wanted::contains, (id, stats) -> {
                Map<String, List<Double>> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                for (Stat st : stats) {
                    List<Double> s = samples(st, 0, false);
                    if (!s.isEmpty()) m.put(st.key, s);
                }
            });
            for (Map.Entry<String, Map<String, List<Double>>> e : got.entrySet()) sink.resource(e.getKey(), e.getValue());
        });
    }

    // ------------------------------------------------------------------ properties and relationships

    /** The latest value of each requested property as text; a property with no value is absent. */
    public Report latestProperties(List<String> ids, List<String> keys, PropertySink sink)
            throws InterruptedException {
        return batched(ids, BATCH, part -> {
            Object root = post(LATEST_PROPERTIES, "{\"resourceIds\":" + idList(part) + ",\"propertyKeys\":"
                    + strList(keys) + "}");
            Map<String, Map<String, String>> got = new LinkedHashMap<>();
            for (Object res : Json.list(Json.get(root, "values"))) {
                String id = Json.str(Json.get(res, "resourceId"));
                if (id == null) continue;
                Map<String, String> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                Object contents = Json.get(Json.get(res, "property-contents"), "property-content");
                for (Object c : Json.list(contents)) {
                    String key = Json.str(Json.get(c, "statKey"));
                    if (key == null || key.isEmpty()) continue;
                    List<Object> values = Json.list(Json.get(c, "values"));
                    if (values.isEmpty()) values = Json.list(Json.get(c, "data"));
                    if (values.isEmpty()) continue;
                    String v = Json.str(values.get(values.size() - 1));
                    if (v != null) m.put(key, v);
                }
            }
            for (Map.Entry<String, Map<String, String>> e : got.entrySet()) sink.resource(e.getKey(), e.getValue());
        });
    }

    /**
     * The objects related to a resource by CHILD or PARENT.
     *
     * @throws IOException the read failed, or the list is longer than the one page the answer holds: a
     *                     partial list is never read as the whole
     */
    public List<Related> related(String id, String type) throws IOException, InterruptedException {
        String answer = ops.get("/api/resources/" + id + "/relationships?relationshipType=" + type
                + "&pageSize=" + RELATIONS_PER_PAGE, null);
        Object root;
        try {
            root = Json.parse(answer);
        } catch (IllegalArgumentException e) {
            throw new IOException("the relationships answer is not valid JSON");
        }
        List<Object> list = Json.list(Json.get(root, "resourceList"));
        Double total = Json.num(Json.get(Json.get(root, "pageInfo"), "totalCount"));
        if (total != null && total > list.size()) {
            throw new IOException("the relationships of " + id + " are paged (" + list.size() + " of "
                    + total.longValue() + ")");
        }
        List<Related> out = new ArrayList<>();
        for (Object item : list) {
            String rid = relatedId(item);
            String kind = Json.str(Json.get(Json.get(item, "resourceKey"), "resourceKindKey"));
            if (rid != null && kind != null) out.add(new Related(kind, rid));
        }
        return out;
    }

    /** The id of a related resource: the one in its SELF link. */
    static String relatedId(Object item) {
        for (Object link : Json.list(Json.get(item, "links"))) {
            String href = Json.str(Json.get(link, "href"));
            if ("SELF".equals(Json.str(Json.get(link, "rel"))) && href != null && href.contains("/resources/")) {
                String tail = href.substring(href.lastIndexOf("/resources/") + "/resources/".length());
                int slash = tail.indexOf('/');
                if (slash >= 0) tail = tail.substring(0, slash);
                int q = tail.indexOf('?');
                if (q >= 0) tail = tail.substring(0, q);
                return tail.isEmpty() ? null : tail;
            }
        }
        return null;
    }
}
