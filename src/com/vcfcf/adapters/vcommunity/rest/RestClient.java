package com.vcfcf.adapters.vcommunity.rest;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.tls.TlsFailure;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;

/**
 * The vSphere Automation REST API of the vCenter (content libraries), over the same
 * {@link VcenterTls} posture as the SOAP session (the certificate probe is separate and sends nothing): the account's TLS
 * setting (verified by default, or Allow Insecure SSL) decides the trust and the name check, both are
 * decided during the handshake before the credentials are sent, and no connection is made any other way. One REST session per use: log
 * in, read, log out in a {@code finally}. SDK-free and plain JDK.
 *
 * <p>The class holds the password to send it once in a Basic header, and nowhere else. It is
 * never logged, and no message built here carries it, the session id or a header. Every call
 * returns a {@link RestResult} that holds its own failure, so the pool that reads items shares
 * no state but the session id, which is only ever written by {@link #login()}.
 */
public final class RestClient implements AutoCloseable {

    public static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Per request, as the original (design 4.1). */
    public static final int READ_TIMEOUT_MS = 60_000;
    private static final int LOGOUT_TIMEOUT_MS = 5_000;
    /** The most bytes of one answer read: a list of identifiers, or one library or item. */
    public static final long MAX_BODY_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    static final String SESSION_HEADER = "vmware-api-session-id";

    private final String base;
    private final String user;
    private final String password; // REDACT-SECRET
    private final VcenterTls tls;
    private final Log log;
    private final int connectMs;
    private final int readMs;

    private volatile String sessionId; // REDACT-SECRET

    /** @param hostPort {@code host:port}, an IPv6 literal bracketed */
    public RestClient(String hostPort, String user, String password, VcenterTls tls, Log log) {
        this(hostPort, user, password, tls, log, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    public RestClient(String hostPort, String user, String password, VcenterTls tls, Log log,
            int connectMs, int readMs) {
        this.base = "https://" + hostPort + "/api";
        this.user = user;
        this.password = password;
        this.tls = tls;
        this.log = log == null ? Log.NONE : log;
        this.connectMs = connectMs;
        this.readMs = readMs;
    }

    /** Whether a session is held. */
    public boolean hasSession() {
        return sessionId != null;
    }

    /** {@code POST /api/session} with Basic authentication; the answer is the session id as a JSON string. */
    public RestResult login() {
        String token;
        try {
            token = Base64.getEncoder().encodeToString((user + ":" + password) // REDACT-SECRET
                    .getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return RestResult.failed(0, "the REST login could not be built", e);
        }
        RestResult r = send("POST", "/session", "Basic " + token, null, connectMs, readMs);
        if (!r.isOk()) {
            String m = r.status() == 401 || r.status() == 403
                    ? "vCenter refused the REST login for the user name and password of this account"
                    : r.message();
            return RestResult.failed(r.status(), "the REST login failed: " + m, r.cause());
        }
        String id;
        try {
            Object v = Json.parse(r.body());
            if (v instanceof java.util.Map) v = ((java.util.Map<?, ?>) v).get("value");
            id = v instanceof String ? (String) v : null;
        } catch (IllegalArgumentException e) {
            id = null;
        }
        if (id == null || id.isEmpty()) {
            return RestResult.failed(r.status(), "the REST login answered no session id", null);
        }
        this.sessionId = id;
        return RestResult.ok(r.status(), "");
    }

    /** {@code GET /api/<path>} with the session; {@code path} starts with {@code /} and is already encoded. */
    public RestResult get(String path) {
        String id = sessionId;
        if (id == null) return RestResult.failed(0, "no REST session", null);
        return send("GET", path, null, id, connectMs, readMs);
    }

    /** Log out (a bounded wait) and forget the session. Never throws; the session expires on its own if this fails. */
    public void logout() {
        String id = sessionId;
        sessionId = null;
        if (id == null) return;
        try {
            RestResult r = send("DELETE", "/session", null, id, LOGOUT_TIMEOUT_MS, LOGOUT_TIMEOUT_MS);
            if (!r.isOk()) {
                log.info("the REST session logout did not complete (" + Failures.bound(r.message())
                        + "); the session expires on its own");
            }
        } catch (RuntimeException e) {
            log.info("the REST session logout did not complete (" + e.getClass().getSimpleName()
                    + "); the session expires on its own");
        }
    }

    @Override
    public void close() {
        logout();
    }

    // ------------------------------------------------------------------ transport

    private RestResult send(String method, String path, String authorization, String session,
            int connectTimeoutMs, int readTimeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) java.net.URI.create(base + path).toURL().openConnection();
            if (conn instanceof HttpsURLConnection) tls.secure((HttpsURLConnection) conn);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false); // never carry the session or the password elsewhere
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            if (authorization != null) conn.setRequestProperty("Authorization", authorization);
            if (session != null) conn.setRequestProperty(SESSION_HEADER, session);
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String body = readCapped(conn.getInputStream(), MAX_BODY_BYTES);
                if (body == null) {
                    return RestResult.failed(code, method + " " + describePath(path)
                            + " answered more than " + MAX_BODY_BYTES + " bytes; not read", null);
                }
                return RestResult.ok(code, body);
            }
            String err = readCapped(conn.getErrorStream(), MAX_ERROR_BYTES);
            return RestResult.failed(code, method + " " + describePath(path) + " was refused (HTTP "
                    + code + ")" + scrub(errorText(err), session), null);
        } catch (IOException | RuntimeException e) {
            if (conn != null) conn.disconnect(); // never reuse a connection that failed
            String tlsText = e instanceof IOException ? TlsFailure.explain(e, tls.insecure(), base) : null;
            return RestResult.failed(0, tlsText != null ? tlsText : describe(method, path, e), e);
        }
    }

    /**
     * A server's words with this client's own secrets taken out: a message that echoes the
     * session id or the password must not carry it into a count, a log line or an account row.
     */
    private String scrub(String text, String session) {
        String t = text;
        if (session != null && !session.isEmpty()) t = t.replace(session, "<session>");
        String id = sessionId;
        if (id != null && !id.isEmpty()) t = t.replace(id, "<session>");
        if (password != null && !password.isEmpty()) t = t.replace(password, "<password>");
        return t;
    }

    /** The path without its query: an identifier is not secret but the query can name a library. */
    private static String describePath(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /** The server's own message from an error body, bounded, as one line; empty when there is none. */
    static String errorText(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        String text = null;
        try {
            Object v = Json.parse(body);
            Object msgs = Json.get(v, "messages");
            if (msgs instanceof java.util.List && !((java.util.List<?>) msgs).isEmpty()) {
                text = Json.str(Json.get(((java.util.List<?>) msgs).get(0), "default_message"));
            }
            if (text == null) text = Json.str(Json.get(v, "error_type"));
        } catch (IllegalArgumentException e) {
            text = null; // not JSON: the text of an HTML page is not shown
        }
        if (text == null || text.trim().isEmpty()) return "";
        String one = Failures.bound(text);
        return ": " + (one.length() > 160 ? one.substring(0, 160) + "..." : one);
    }

    private static String describe(String method, String path, Exception e) {
        String what = method + " " + describePath(path);
        if (e instanceof java.net.SocketTimeoutException) {
            return what + ": the vCenter did not answer in time";
        }
        if (e instanceof java.net.UnknownHostException) {
            return what + ": the vCenter host name could not be resolved";
        }
        if (e instanceof java.net.ConnectException) {
            return what + ": the vCenter refused or did not accept the connection";
        }
        String m = e.getMessage();
        String one = m == null ? "" : Failures.bound(m);
        return what + " failed: " + e.getClass().getSimpleName()
                + (one.isEmpty() ? "" : ": " + (one.length() > 160 ? one.substring(0, 160) + "..." : one));
    }

    /** The body as text, or null when it is longer than {@code max}. */
    private static String readCapped(InputStream in, long max) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > max) return null;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
