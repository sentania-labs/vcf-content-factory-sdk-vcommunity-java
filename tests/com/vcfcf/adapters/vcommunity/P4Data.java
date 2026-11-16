package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What VCF Operations holds, for the derived metrics: the latest statistics (each with the age of its sample),
 * the latest properties, five-minute history, and the CHILD relationships; answered in the shape of the Suite
 * API's {@code stats/latest/query}, {@code stats/query}, {@code properties/latest/query} and
 * {@code relationships}. A request that names an id in {@link #failIds} answers HTTP 500, one for a path in
 * {@link #failPaths} fails as a network error: a failed read, not an empty answer.
 */
final class P4Data {

    static final String LATEST = "/api/resources/stats/latest/query";
    static final String HISTORY = "/api/resources/stats/query";
    static final String PROPS = "/api/resources/properties/latest/query";

    /** The cycle's start: a sample's time is this less its age. */
    volatile long nowMs;
    /** rid to key to {value, age in ms}. A key with an instance ({@code net:vmnic0|speed}) answers a request for {@code net|speed}. */
    final Map<String, Map<String, double[]>> stats = new HashMap<>();
    final Map<String, Map<String, String>> props = new HashMap<>();
    /** rid to key to the history samples. */
    final Map<String, Map<String, List<Double>>> history = new HashMap<>();
    final Map<String, List<String[]>> children = new HashMap<>();
    final Set<String> failIds = new HashSet<>();
    final Set<String> failPaths = new HashSet<>();
    final Set<String> failRelationshipsOf = new HashSet<>();
    /** Every request: path and the number of resources it named. */
    final List<String> log = new CopyOnWriteArrayList<>();
    /**
     * True: a resource with nothing to report still gets an (empty) entry in an answer. False, as Operations does it:
     * the answer names only resources that have a sample.
     */
    volatile boolean emptyEntries;
    /** A latest-statistics or properties request whose body names one of these keys fails (HTTP 500). */
    final Set<String> failOnKey = new HashSet<>();
    /** Only a latest-statistics request whose body names one of these keys fails (HTTP 500). */
    final Set<String> failLatestOn = new HashSet<>();
    /** Run once, on the first latest-statistics request (a test moves the clock from here). */
    volatile Runnable onFirstLatest;
    /** Samples since the end of the window, for a history request that begins at {@link #splitAtMs} or later. */
    final Map<String, Map<String, List<Double>>> historyLate = new HashMap<>();
    volatile long splitAtMs = Long.MAX_VALUE;
    /** Answer a stats request with a body that is not what is expected. */
    volatile String garbage;
    /** Every history request body (to see the window asked for). */
    final List<String> historyBodies = new CopyOnWriteArrayList<>();

    P4Data(long nowMs) {
        this.nowMs = nowMs;
    }

    P4Data stat(String rid, String key, double value) {
        return stat(rid, key, value, 60_000);
    }

    P4Data stat(String rid, String key, double value, long ageMs) {
        stats.computeIfAbsent(rid, k -> new LinkedHashMap<>()).put(key, new double[] {value, ageMs});
        return this;
    }

    P4Data prop(String rid, String key, String value) {
        props.computeIfAbsent(rid, k -> new LinkedHashMap<>()).put(key, value);
        return this;
    }

    P4Data hist(String rid, String key, Double... samples) {
        history.computeIfAbsent(rid, k -> new LinkedHashMap<>()).put(key, java.util.Arrays.asList(samples));
        return this;
    }

    P4Data histLate(String rid, String key, Double... samples) {
        historyLate.computeIfAbsent(rid, k -> new LinkedHashMap<>()).put(key, java.util.Arrays.asList(samples));
        return this;
    }

    P4Data child(String parent, String kind, String child) {
        children.computeIfAbsent(parent, k -> new ArrayList<>()).add(new String[] {kind, child});
        return this;
    }

    private static List<String> strings(Object list) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.list(list)) out.add(Json.str(o));
        return out;
    }

    /** The answer to a POST, or null when this fake does not answer that path. */
    String post(String path, String body) throws IOException {
        if (!path.equals(LATEST) && !path.equals(HISTORY) && !path.equals(PROPS)) return null;
        Object req = Json.parse(body);
        List<String> ids = strings(Json.get(req, path.equals(PROPS) ? "resourceIds" : "resourceId"));
        log.add(path + " " + ids.size());
        if (failPaths.contains(path)) throw new IOException("connection reset");
        for (String k : failOnKey) {
            if (!path.equals(HISTORY) && body.contains(k)) throw new OpsHttpException("Suite API answered HTTP 500", 500, null);
        }
        if (path.equals(LATEST)) {
            for (String k : failLatestOn) {
                if (body.contains(k)) throw new OpsHttpException("Suite API answered HTTP 500", 500, null);
            }
        }
        Runnable hook = onFirstLatest;
        if (hook != null && path.equals(LATEST)) {
            onFirstLatest = null;
            hook.run();
        }
        for (String id : ids) {
            if (failIds.contains(id)) throw new OpsHttpException("Suite API answered HTTP 500", 500, null);
        }
        if (garbage != null && !path.equals(PROPS)) return garbage;
        StringBuilder b = new StringBuilder("{\"values\":[");
        boolean first = true;
        for (String id : ids) {
            StringBuilder one = new StringBuilder();
            boolean any;
            if (path.equals(PROPS)) {
                any = propContents(one, id, strings(Json.get(req, "propertyKeys")));
            } else if (path.equals(LATEST)) {
                any = latest(one, id, strings(Json.get(req, "statKey")));
            } else {
                Number begin = Json.num(Json.get(req, "begin"));
                Map<String, Map<String, List<Double>>> src = begin != null && begin.longValue() >= splitAtMs
                        ? historyLate : history;
                any = historyOf(one, src, id, strings(Json.get(req, "statKey")));
            }
            if (!any && !emptyEntries) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"resourceId\":").append(Json.quote(id)).append(one).append('}');
        }
        if (path.equals(HISTORY)) historyBodies.add(body);
        return b.append("]}").toString();
    }

    private static String generic(String key) {
        int bar = key.indexOf('|');
        int colon = bar < 0 ? -1 : key.substring(0, bar).indexOf(':');
        return colon < 0 ? key : key.substring(0, colon) + "|" + key.substring(bar + 1);
    }

    private boolean latest(StringBuilder b, String id, List<String> keys) {
        b.append(",\"stat-list\":{\"stat\":[");
        boolean first = true;
        Map<String, double[]> have = stats.getOrDefault(id, new LinkedHashMap<>());
        for (Map.Entry<String, double[]> e : have.entrySet()) {
            // a request for net|speed also gets net:vmnic0|speed, as the platform answers
            if (!keys.contains(e.getKey()) && !keys.contains(generic(e.getKey()))) continue;
            if (!first) b.append(',');
            first = false;
            long ts = nowMs - (long) e.getValue()[1];
            b.append("{\"timestamps\":[").append(ts).append("],\"statKey\":{\"key\":").append(Json.quote(e.getKey()))
                    .append("},\"data\":[").append(e.getValue()[0]).append("]}");
        }
        b.append("]}");
        return !first;
    }

    private boolean historyOf(StringBuilder b, Map<String, Map<String, List<Double>>> src, String id, List<String> keys) {
        b.append(",\"stat-list\":{\"stat\":[");
        boolean first = true;
        for (Map.Entry<String, List<Double>> e : src.getOrDefault(id, new LinkedHashMap<>()).entrySet()) {
            if (!keys.contains(e.getKey())) continue;
            if (!first) b.append(',');
            first = false;
            StringBuilder ts = new StringBuilder();
            StringBuilder data = new StringBuilder();
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) {
                    ts.append(',');
                    data.append(',');
                }
                ts.append(nowMs - 300_000L * (e.getValue().size() - i));
                data.append(e.getValue().get(i));
            }
            b.append("{\"timestamps\":[").append(ts).append("],\"statKey\":{\"key\":").append(Json.quote(e.getKey()))
                    .append("},\"data\":[").append(data).append("]}");
        }
        b.append("]}");
        return !first;
    }

    private boolean propContents(StringBuilder b, String id, List<String> keys) {
        b.append(",\"property-contents\":{\"property-content\":[");
        boolean first = true;
        for (Map.Entry<String, String> e : props.getOrDefault(id, new LinkedHashMap<>()).entrySet()) {
            if (!keys.contains(e.getKey())) continue;
            if (!first) b.append(',');
            first = false;
            boolean numeric = true;
            try {
                Double.parseDouble(e.getValue());
            } catch (NumberFormatException x) {
                numeric = false;
            }
            b.append("{\"statKey\":").append(Json.quote(e.getKey())).append(numeric ? ",\"data\":[" + e.getValue() + "]"
                    : ",\"values\":[" + Json.quote(e.getValue()) + "]").append(",\"timestamps\":[").append(nowMs - 3_600_000L).append("]}");
        }
        b.append("]}");
        return !first;
    }

    /** The answer to a GET of a relationships path, or null when the path is not one. */
    String get(String path) throws IOException {
        if (!path.startsWith("/api/resources/") || !path.contains("/relationships?relationshipType=")) return null;
        String id = path.substring("/api/resources/".length(), path.indexOf("/relationships"));
        String type = path.substring(path.indexOf('=') + 1);
        if (type.contains("&")) type = type.substring(0, type.indexOf('&'));
        log.add("GET " + id + " " + type);
        if (failRelationshipsOf.contains(id)) throw new OpsHttpException("Suite API answered HTTP 500", 500, null);
        StringBuilder b = new StringBuilder("{\"resourceList\":[");
        boolean first = true;
        if (type.equals("CHILD")) {
            for (String[] c : children.getOrDefault(id, new ArrayList<>())) {
                if (!first) b.append(',');
                first = false;
                b.append("{\"resourceKey\":{\"resourceKindKey\":").append(Json.quote(c[0]))
                        .append("},\"links\":[{\"rel\":\"SELF\",\"href\":\"/suite-api/api/resources/").append(c[1])
                        .append("\"}]}");
            }
        }
        return b.append("]}").toString();
    }

    int requests(String path) {
        int n = 0;
        for (String l : log) if (l.startsWith(path + " ")) n++;
        return n;
    }
}
