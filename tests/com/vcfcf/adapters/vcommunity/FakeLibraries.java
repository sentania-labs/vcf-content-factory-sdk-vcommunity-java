package com.vcfcf.adapters.vcommunity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The vSphere Automation REST API of a vCenter for the content library tests: the session, the
 * library list, a library, the items of a library, an item. Answers as the real one does (bare
 * bodies, or inside a {@code value} envelope when {@link #envelope} is set), and refuses with
 * the statuses it is told to. Served through {@link FakeVc}, so the real client, TLS and
 * parser run. Safe to call from the reader's pool.
 */
final class FakeLibraries implements FakeVc.Handler {

    static final String USER = "svc-reader";
    static final String PASSWORD = "hunter2-not-for-logs";
    static final String SESSION = "rest-session-0f9e8d7c";

    final Map<String, String> libraries = new LinkedHashMap<>();       // id -> detail JSON
    final Map<String, List<String>> itemIds = new LinkedHashMap<>();   // library id -> item ids
    final Map<String, String> items = new ConcurrentHashMap<>();        // item id -> JSON
    /** Exact path (with query) to the status it answers, whatever the rest. */
    final Map<String, Integer> status = new ConcurrentHashMap<>();
    /** Exact path to a body it answers with (status 200), instead of the stored one. */
    final Map<String, String> body = new ConcurrentHashMap<>();
    volatile boolean envelope;
    volatile boolean refuseLogin;
    /** Put the session id and the password in the text of a refusal, as a careless server might. */
    volatile boolean echoSecrets;
    volatile int delayMs;
    volatile Consumer<String> onGet = p -> { };

    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger logins = new AtomicInteger();
    final AtomicInteger logouts = new AtomicInteger();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> sessionsSeen = new CopyOnWriteArrayList<>();

    FakeLibraries lib(String id, String json, String... itemIdList) {
        libraries.put(id, json);
        List<String> l = new ArrayList<>();
        for (String s : itemIdList) l.add(s);
        itemIds.put(id, l);
        return this;
    }

    FakeLibraries item(String id, String json) {
        items.put(id, json);
        return this;
    }

    int count(String prefix) {
        int n = 0;
        for (String c : calls) if (c.startsWith(prefix)) n++;
        return n;
    }

    int exact(String call) {
        int n = 0;
        for (String c : calls) if (c.equals(call)) n++;
        return n;
    }

    // ---- the JSON of the objects

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A library; {@code extra} is more members, already JSON ({@code "type":"LOCAL"}), comma separated. */
    static String libraryJson(String id, String name, String... extra) {
        StringBuilder b = new StringBuilder("{\"id\":" + q(id) + (name == null ? "" : ",\"name\":" + q(name)));
        for (String e : extra) b.append(',').append(e);
        return b.append('}').toString();
    }

    static String itemJson(String id, String name, String... extra) {
        return libraryJson(id, name, extra);
    }

    static String m(String key, String value) {
        return q(key) + ":" + q(value);
    }

    static String mRaw(String key, String raw) {
        return q(key) + ":" + raw;
    }

    // ---- the server

    @Override
    public FakeVc.Resp handle(FakeVc.Req r) {
        calls.add(r.method + " " + r.path);
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String path = r.path;
        if (r.method.equals("POST") && path.equals("/api/session")) {
            authorizations.add(r.authorization == null ? "" : r.authorization);
            String want = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD)
                    .getBytes(StandardCharsets.UTF_8));
            if (refuseLogin || !want.equals(r.authorization)) {
                return FakeVc.Resp.json(401, "{\"error_type\":\"UNAUTHENTICATED\",\"messages\":["
                        + "{\"default_message\":\"Authentication required.\"}]}");
            }
            logins.incrementAndGet();
            return FakeVc.Resp.json(201, q(SESSION));
        }
        if (r.method.equals("DELETE") && path.equals("/api/session")) {
            logouts.incrementAndGet();
            return new FakeVc.Resp(204, null, "", "application/json");
        }
        if (!r.method.equals("GET")) return FakeVc.Resp.json(405, "{}");
        sessionsSeen.add(r.session == null ? "" : r.session);
        if (!SESSION.equals(r.session)) {
            return FakeVc.Resp.json(401, "{\"error_type\":\"UNAUTHENTICATED\"}");
        }
        onGet.accept(path);
        Integer st = status.get(path);
        if (st != null) {
            return FakeVc.Resp.json(st, "{\"error_type\":\"" + (st == 403 ? "UNAUTHORIZED" : "ERROR")
                    + "\",\"messages\":[{\"default_message\":\"refused by the fixture (" + st + ")"
                    + (echoSecrets ? " for session " + r.session + " and " + PASSWORD : "") + "\"}]}");
        }
        String forced = body.get(path);
        if (forced != null) return FakeVc.Resp.json(200, forced);
        if (path.equals("/api/content/library")) {
            StringBuilder b = new StringBuilder("[");
            for (String id : libraries.keySet()) b.append(b.length() > 1 ? "," : "").append(q(id));
            return ok(b.append(']').toString());
        }
        if (path.startsWith("/api/content/library/item?library_id=")) {
            String id = path.substring("/api/content/library/item?library_id=".length());
            List<String> ids = itemIds.get(id);
            if (ids == null) return notFound();
            StringBuilder b = new StringBuilder("[");
            for (String i : ids) b.append(b.length() > 1 ? "," : "").append(q(i));
            return ok(b.append(']').toString());
        }
        if (path.startsWith("/api/content/library/item/")) {
            String j = items.get(path.substring("/api/content/library/item/".length()));
            return j == null ? notFound() : ok(j);
        }
        if (path.startsWith("/api/content/library/")) {
            String j = libraries.get(path.substring("/api/content/library/".length()));
            return j == null ? notFound() : ok(j);
        }
        return notFound();
    }

    private FakeVc.Resp ok(String json) {
        return FakeVc.Resp.json(200, envelope ? "{\"value\":" + json + "}" : json);
    }

    private static FakeVc.Resp notFound() {
        return FakeVc.Resp.json(404, "{\"error_type\":\"NOT_FOUND\",\"messages\":[{\"default_message\":"
                + "\"Library or item not found.\"}]}");
    }
}
