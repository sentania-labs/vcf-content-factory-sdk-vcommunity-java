package com.vcfcf.adapters.vcommunity.ops;

import com.vcfcf.adapters.vcommunity.json.Json;
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
    /** The 5-minute interval of one stored sample. */
    public static final int CYCLE_MINUTES = 5;
    /** A latest statistic older than two cycles is no data; a configuration count keeps 15 minutes. */
    public static final long STALE_MS = 2L * CYCLE_MINUTES * 60_000L;
    public static final long CONFIGURATION_STALE_MS = 15L * 60_000L;

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

    /** A resource's instance statistics: statistic key, then instance name, then the latest value. */
    public interface InstanceSink {
        void resource(String id, Map<String, Map<String, Double>> byStat);
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
    private final boolean sentinelIsValue;

    public OpsReads(OpsApi ops, BooleanSupplier stop) {
        this(ops, stop, Quirks.NEGATIVE_ONE_STAT_IS_A_VALUE);
    }

    /** @param sentinelIsValue see {@link Quirks#NEGATIVE_ONE_STAT_IS_A_VALUE} */
    public OpsReads(OpsApi ops, BooleanSupplier stop, boolean sentinelIsValue) {
        this.ops = ops;
        this.stop = stop == null ? () -> false : stop;
        this.sentinelIsValue = sentinelIsValue;
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

    private static Object statList(Object resource) {
        Object sl = Json.get(resource, "stat-list");
        if (sl == null || (sl instanceof Map && ((Map<?, ?>) sl).isEmpty())) sl = Json.get(resource, "stats");
        return sl;
    }

    private static double number(Object o) throws IOException {
        if (o instanceof Double) return (Double) o;
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new IOException("a statistic sample is not a number");
    }

    /** The samples of one statistic that are not older than {@code oldestMs} (all when negative). */
    private static List<Double> samples(Object stat, double oldestMs, boolean filter) throws IOException {
        List<Object> data = Json.list(Json.get(stat, "data"));
        if (data.isEmpty()) return Collections.emptyList();
        List<Object> ts = Json.list(Json.get(stat, "timestamps"));
        List<Double> out = new ArrayList<>(Math.min(data.size(), 8));
        // the age filter applies only when there is one timestamp per sample, as in the original
        boolean byTime = filter && ts.size() == data.size();
        for (int i = 0; i < data.size(); i++) {
            if (byTime) {
                Double t = Json.num(ts.get(i));
                if (t == null || t < oldestMs) continue;
            }
            out.add(number(data.get(i)));
        }
        return out;
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
        final Set<String> wanted = new HashSet<>(keys);
        final double oldest = maxAgeMs < 0 ? Double.NEGATIVE_INFINITY : nowMs - maxAgeMs;
        return batched(ids, BATCH, part -> {
            Object root = post(LATEST_STATS, "{\"resourceId\":" + idList(part) + ",\"statKey\":" + strList(keys) + "}");
            Map<String, Map<String, Double>> got = new LinkedHashMap<>();
            for (Object res : Json.list(Json.get(root, "values"))) {
                String id = Json.str(Json.get(res, "resourceId"));
                if (id == null) continue;
                Map<String, Double> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                for (Object stat : Json.list(Json.get(statList(res), "stat"))) {
                    String key = Json.str(Json.get(Json.get(stat, "statKey"), "key"));
                    if (key == null || !wanted.contains(key)) continue;
                    List<Double> s = samples(stat, oldest, maxAgeMs >= 0);
                    if (s.isEmpty()) continue;
                    double v = s.get(s.size() - 1);
                    if (!sentinelIsValue && v == -1.0 && !StatKeys.SIGNED.contains(key)) {
                        continue; // a "no sample" marker, not a reading
                    }
                    m.put(key, v);
                }
            }
            for (Map.Entry<String, Map<String, Double>> e : got.entrySet()) sink.resource(e.getKey(), e.getValue());
        });
    }

    /** "net:vmnic0|speed" gives {"vmnic0", "net|speed"}; a key with no instance gives null. */
    static String[] splitInstance(String statKey) {
        int bar = statKey.indexOf('|');
        if (bar < 0) return null;
        String group = statKey.substring(0, bar);
        int colon = group.indexOf(':');
        if (colon < 0) return null;
        return new String[] {group.substring(colon + 1), group.substring(0, colon) + "|" + statKey.substring(bar + 1)};
    }

    /**
     * The latest value of each instance of the requested statistics ({@code net:vmnic0|speed} for
     * {@code net|speed}), the statistic with no instance left out. {@code sink} gets only the resources
     * that have at least one instance statistic, as the original does.
     */
    public Report latestInstanceStats(List<String> ids, List<String> keys, long nowMs, InstanceSink sink)
            throws InterruptedException {
        final Set<String> wanted = new HashSet<>(keys);
        final double oldest = nowMs - STALE_MS;
        return batched(ids, BATCH, part -> {
            Object root = post(LATEST_STATS, "{\"resourceId\":" + idList(part) + ",\"statKey\":" + strList(keys) + "}");
            Map<String, Map<String, Map<String, Double>>> got = new LinkedHashMap<>();
            for (Object res : Json.list(Json.get(root, "values"))) {
                String id = Json.str(Json.get(res, "resourceId"));
                if (id == null) continue;
                for (Object stat : Json.list(Json.get(statList(res), "stat"))) {
                    String key = Json.str(Json.get(Json.get(stat, "statKey"), "key"));
                    String[] split = key == null ? null : splitInstance(key);
                    if (split == null || !wanted.contains(split[1])) continue;
                    List<Double> s = samples(stat, oldest, true);
                    if (s.isEmpty()) continue;
                    got.computeIfAbsent(id, k -> new LinkedHashMap<>())
                            .computeIfAbsent(split[1], k -> new LinkedHashMap<>())
                            .put(split[0], s.get(s.size() - 1));
                }
            }
            for (Map.Entry<String, Map<String, Map<String, Double>>> e : got.entrySet()) {
                sink.resource(e.getKey(), e.getValue());
            }
        });
    }

    /** The time of the latest sample of each statistic, however old it is; a statistic never stored is absent. */
    public Report latestTimestamps(List<String> ids, List<String> keys, TimestampSink sink)
            throws InterruptedException {
        final Set<String> wanted = new HashSet<>(keys);
        return batched(ids, BATCH, part -> {
            Object root = post(LATEST_STATS, "{\"resourceId\":" + idList(part) + ",\"statKey\":" + strList(keys) + "}");
            Map<String, Map<String, Double>> got = new LinkedHashMap<>();
            for (Object res : Json.list(Json.get(root, "values"))) {
                String id = Json.str(Json.get(res, "resourceId"));
                if (id == null) continue;
                Map<String, Double> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                for (Object stat : Json.list(Json.get(statList(res), "stat"))) {
                    String key = Json.str(Json.get(Json.get(stat, "statKey"), "key"));
                    if (key == null || !wanted.contains(key)) continue;
                    List<Object> ts = Json.list(Json.get(stat, "timestamps"));
                    if (ts.isEmpty()) continue;
                    double max = Double.NEGATIVE_INFINITY;
                    for (Object t : ts) max = Math.max(max, number(t));
                    m.put(key, max);
                }
            }
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
            Object root = post(STATS_QUERY, body);
            Map<String, Map<String, List<Double>>> got = new LinkedHashMap<>();
            for (Object res : Json.list(Json.get(root, "values"))) {
                String id = Json.str(Json.get(res, "resourceId"));
                if (id == null) continue;
                Map<String, List<Double>> m = got.computeIfAbsent(id, k -> new LinkedHashMap<>());
                for (Object stat : Json.list(Json.get(statList(res), "stat"))) {
                    String key = Json.str(Json.get(Json.get(stat, "statKey"), "key"));
                    if (key == null || !wanted.contains(key)) continue;
                    List<Double> s = samples(stat, 0, false);
                    if (!s.isEmpty()) m.put(key, s);
                }
            }
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
        String answer = ops.get("/api/resources/" + id + "/relationships?relationshipType=" + type, null);
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
