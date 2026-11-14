package com.vcfcf.adapters.vcommunity.rest;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Reads the content libraries of a vCenter (design 4.11): log in, list the libraries, read each
 * one and the list of its items, read each item, log out. Libraries and items are read on a
 * small pool (the answers are small and many; design 9.2), and every failure is isolated to the
 * object that failed (DV-46): a library that cannot be read is skipped, an item that cannot be
 * read is missing from its library, and neither stops the others. Nothing that failed is filled
 * in. The REST session is the cycle's own, opened here and closed in a {@code finally}, and the
 * connection is made only through the account's {@link VcenterTls}.
 *
 * <p>The REST list calls answer the identifiers only and are not paged by the API; a library of
 * many thousand items costs one call each, which is why the deadline is checked before every one.
 */
public final class LibraryReader implements LibrarySource {

    /** How long the logout waits for workers still in a call. */
    static final long WORKER_GRACE_MS = 3_000;

    /** The pool reading libraries and items. */
    public static final int WORKERS = 4;

    private final String hostPort;
    private final String user;
    private final String password; // REDACT-SECRET
    private final VcenterTls tls;
    private final Log log;
    private final int connectMs;
    private final int readMs;
    private final int workers;

    public LibraryReader(String hostPort, String user, String password, VcenterTls tls, Log log) {
        this(hostPort, user, password, tls, log, RestClient.CONNECT_TIMEOUT_MS,
                RestClient.READ_TIMEOUT_MS, WORKERS);
    }

    public LibraryReader(String hostPort, String user, String password, VcenterTls tls, Log log,
            int connectMs, int readMs, int workers) {
        this.hostPort = hostPort;
        this.user = user;
        this.password = password;
        this.tls = tls;
        this.log = log == null ? Log.NONE : log;
        this.connectMs = connectMs;
        this.readMs = readMs;
        this.workers = Math.max(1, workers);
    }

    @Override
    public LibraryData read(long deadlineMs, LongSupplier clock) throws InterruptedException {
        RestClient rest = new RestClient(hostPort, user, password, tls, log, connectMs, readMs);
        ExecutorService pool = Executors.newFixedThreadPool(workers, namedDaemons("vcommunity-rest"));
        try {
            if (clock.getAsLong() >= deadlineMs) {
                LibraryData d = LibraryData.failedList("the cycle deadline was reached before the "
                        + "content libraries were read");
                d.deadlineHit = true;
                return d;
            }
            RestResult login = rest.login();
            if (!login.isOk()) return LibraryData.failedList(login.message());
            return readAll(rest, pool, deadlineMs, clock);
        } finally {
            pool.shutdownNow();
            // a worker inside a call is not interruptible: give it a moment, so that none outlives the
            // logout and the "one REST session per cycle" is exact (a call that hangs is cut by the logout)
            try {
                pool.awaitTermination(WORKER_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rest.logout();
        }
    }

    // ------------------------------------------------------------------ the read

    private LibraryData readAll(RestClient rest, ExecutorService pool, long deadlineMs, LongSupplier clock)
            throws InterruptedException {
        LibraryData data = new LibraryData();
        RestResult listed = rest.get("/content/library");
        List<String> ids = idList(listed, true);
        if (ids == null) {
            return LibraryData.failedList(listed.isOk() ? "the list of content libraries was not a list "
                    + "of identifiers" : listed.message());
        }
        for (String id : ids) data.libraries.add(new LibraryData.Library(id));

        // stage 1: every library and the list of its items
        List<Future<Stage1>> s1 = new ArrayList<>();
        for (LibraryData.Library lib : data.libraries) {
            s1.add(pool.submit(() -> stage1(rest, lib.id, deadlineMs, clock)));
        }
        List<Stage1> done = await(s1, deadlineMs, clock, data);
        for (int i = 0; i < data.libraries.size(); i++) {
            LibraryData.Library lib = data.libraries.get(i);
            Stage1 r = done.get(i);
            if (r == null || r.notRun) {
                lib.notRun = true;
                if (r != null) data.deadlineHit = true;
                continue;
            }
            if (r.gone) {
                lib.gone = true;
                continue;
            }
            lib.detail = r.detail;
            lib.itemIds = r.itemIds;
            if (r.detailFailure != null) data.fail(FailureCause.REST_LIB, "library " + lib.id,
                    r.detailFailure, 8);
            if (r.listFailure != null) data.fail(FailureCause.REST_LIB, "items of library " + lib.id,
                    r.listFailure, 4);
        }

        // stage 2: every item of every library whose list was read
        List<Future<Item>> s2 = new ArrayList<>();
        for (int i = 0; i < data.libraries.size(); i++) {
            LibraryData.Library lib = data.libraries.get(i);
            if (lib.itemIds == null) continue;
            for (String itemId : lib.distinctItemIds()) {
                s2.add(pool.submit(() -> readItem(rest, itemId, deadlineMs, clock)));
            }
        }
        List<Item> items = await(s2, deadlineMs, clock, data);
        int k = 0;
        for (int i = 0; i < data.libraries.size(); i++) {
            LibraryData.Library lib = data.libraries.get(i);
            if (lib.itemIds == null) continue;
            java.util.Set<String> gone = new java.util.HashSet<>();
            for (String itemId : lib.distinctItemIds()) {
                Item r = items.get(k++);
                if (r != null && r.body != null) {
                    lib.items.put(itemId, r.body);
                } else if (r != null && r.gone) {
                    gone.add(itemId);
                } else {
                    lib.itemsMissing++;
                    if (r != null && r.notRun) data.deadlineHit = true;
                    if (r != null && r.failure != null) {
                        data.fail(FailureCause.REST_ITEM, "item " + itemId, r.failure, 11);
                    }
                }
            }
            if (!gone.isEmpty()) lib.itemIds.removeAll(gone); // deleted since the list: no longer in the library
        }
        return data;
    }

    private static final class Stage1 {
        boolean gone;
        Map<String, Object> detail;
        String detailFailure;
        List<String> itemIds;
        String listFailure;
        boolean notRun;
    }

    private static final class Item {
        boolean gone;
        Map<String, Object> body;
        String failure;
        boolean notRun;
    }

    private Stage1 stage1(RestClient rest, String id, long deadlineMs, LongSupplier clock) {
        Stage1 r = new Stage1();
        try {
            stage1(rest, id, deadlineMs, clock, r);
        } catch (RuntimeException e) {
            r.detailFailure = r.detail == null ? "reading the library failed unexpectedly ("
                    + e.getClass().getSimpleName() + ")" : null;
            r.listFailure = r.detail != null ? "reading the items of the library failed unexpectedly ("
                    + e.getClass().getSimpleName() + ")" : null;
        }
        return r;
    }

    private void stage1(RestClient rest, String id, long deadlineMs, LongSupplier clock, Stage1 r) {
        if (stopped(deadlineMs, clock)) {
            r.notRun = true;
            return;
        }
        RestResult d = rest.get("/content/library/" + enc(id));
        r.detail = keep(object(d), LIBRARY_KEYS, LIBRARY_NESTED);
        if (r.detail == null && d.status() == 404) {
            r.gone = true; // deleted since the list: not there any more, which is not a failed read
            return;
        }
        if (r.detail == null) {
            r.detailFailure = d.isOk() ? "the answer for the library is not a library" : d.message();
            return;
        }
        if (stopped(deadlineMs, clock)) {
            r.notRun = true;
            return;
        }
        RestResult l = rest.get("/content/library/item?library_id=" + enc(id));
        r.itemIds = idList(l, !Quirks.REPEATED_ITEM_COUNTED_TWICE);
        if (r.itemIds == null && l.status() == 404) {
            r.gone = true;
            r.detail = null;
            return;
        }
        if (r.itemIds == null) {
            r.listFailure = l.isOk() ? "the list of the items of the library was not a list of identifiers"
                    : l.message();
        }
    }

    private Item readItem(RestClient rest, String id, long deadlineMs, LongSupplier clock) {
        Item r = new Item();
        if (stopped(deadlineMs, clock)) {
            r.notRun = true; // not reached
            return r;
        }
        try {
            RestResult x = rest.get("/content/library/item/" + enc(id));
            r.body = keep(object(x), ITEM_KEYS, ITEM_NESTED);
            if (r.body == null && x.status() == 404) {
                r.gone = true; // deleted since the list: no longer an item, not a failed read
            } else if (r.body == null) {
                r.failure = x.isOk() ? "the answer for the item is not an item" : x.message();
            }
        } catch (RuntimeException e) {
            r.failure = "reading the item failed unexpectedly (" + e.getClass().getSimpleName() + ")";
        }
        return r;
    }

    private static boolean stopped(long deadlineMs, LongSupplier clock) {
        return Thread.currentThread().isInterrupted() || clock.getAsLong() >= deadlineMs;
    }

    /**
     * The result of every task, in order; null for a task that did not finish by the deadline
     * (it is cancelled) or that died of an {@link Error}, which is counted.
     */
    private <T> List<T> await(List<Future<T>> fs, long deadlineMs, LongSupplier clock, LibraryData data)
            throws InterruptedException {
        List<T> out = new ArrayList<>(fs.size());
        boolean cut = false;
        boolean died = false;
        for (Future<T> f : fs) {
            if (cut) {
                f.cancel(true);
                out.add(null);
                continue;
            }
            try {
                // the deadline is on the cycle's clock; the wait is real time
                long left = Math.max(1, deadlineMs - clock.getAsLong());
                out.add(f.get(left, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                cut = true;
                data.deadlineHit = true;
                f.cancel(true);
                out.add(null);
            } catch (ExecutionException e) {
                died = true;
                out.add(null);
            } catch (java.util.concurrent.CancellationException e) {
                out.add(null);
            }
        }
        if (died) {
            data.fail(FailureCause.REST_LIB, "content libraries", "a read stopped unexpectedly", 0);
        }
        return out;
    }

    // ------------------------------------------------------------------ answers

    /** The fields of a library the keys use; the rest of the answer is dropped at once (heap, design 9.3). */
    static final String[] LIBRARY_KEYS = {"id", "name", "type", "subscription_info", "state_info"};
    static final Map<String, String[]> LIBRARY_NESTED = new java.util.HashMap<>();
    static final String[] ITEM_KEYS = {"id", "name", "type", "description", "version", "content_version",
        "cached", "security_compliance", "certificate_verification_info", "last_modified_time",
        "last_sync_time", "source_id", "size"};
    static final Map<String, String[]> ITEM_NESTED = new java.util.HashMap<>();

    static {
        LIBRARY_NESTED.put("subscription_info", new String[] {"subscription_url", "automatic_sync_enabled"});
        LIBRARY_NESTED.put("state_info", new String[] {"state"});
        ITEM_NESTED.put("certificate_verification_info", new String[] {"status"});
    }

    /** A copy of {@code m} with only {@code keys}, and of the nested objects only their listed fields; null for null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> keep(Map<String, Object> m, String[] keys, Map<String, String[]> nested) {
        if (m == null) return null;
        Map<String, Object> out = new java.util.HashMap<>();
        for (String k : keys) {
            if (!m.containsKey(k)) continue;
            Object v = m.get(k);
            String[] kids = nested.get(k);
            if (kids != null && v instanceof Map) {
                Map<String, Object> inner = new java.util.HashMap<>();
                for (String kid : kids) {
                    if (((Map<String, Object>) v).containsKey(kid)) inner.put(kid, ((Map<String, Object>) v).get(kid));
                }
                v = inner;
            }
            out.put(k, v);
        }
        return out;
    }

    /** The answer's JSON with a {@code value} envelope taken off (the API wraps some answers, not all). */
    static Object unwrap(Object body) {
        if (body instanceof Map && ((Map<?, ?>) body).containsKey("value")) return ((Map<?, ?>) body).get("value");
        return body;
    }

    private static Object parsed(RestResult r) {
        if (!r.isOk()) return null;
        try {
            return unwrap(Json.parse(r.body()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A JSON object answer, or null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(RestResult r) {
        Object v = parsed(r);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** A list of identifier strings, in order, duplicates removed; null when the answer is anything else. */
    private static List<String> idList(RestResult r, boolean distinct) {
        Object v = parsed(r);
        if (!(v instanceof List)) return null;
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : (List<?>) v) {
            if (!(o instanceof String) || ((String) o).isEmpty()) return null; // a list we cannot trust
            if (distinct && !seen.add((String) o)) continue;
            ids.add((String) o);
        }
        return ids;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.concurrent.ThreadFactory namedDaemons(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
