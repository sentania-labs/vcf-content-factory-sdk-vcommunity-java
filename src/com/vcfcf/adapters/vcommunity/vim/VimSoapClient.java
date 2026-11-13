package com.vcfcf.adapters.vcommunity.vim;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.tls.PinMismatchException;
import com.vcfcf.adapters.vcommunity.tls.TlsFailure;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.xml.stream.XMLStreamException;

/**
 * vim25 as raw SOAP 1.1 over {@code HttpsURLConnection}, parsed with the JDK's
 * StAX. No JAX-WS and no {@code vim25.jar} (design rule 2: the 9.1 JAX-WS
 * provider collision). One long-lived session per client: log in on first
 * use, keep it alive with {@code CurrentTime}, log in again once when a call
 * meets {@code NotAuthenticated}, log out when closed.
 *
 * <p>Every call returns a {@link VimResult} that carries its own fault, so a
 * pool of workers shares no "last fault" field. The class holds the password
 * to send it in {@code Login} and nowhere else; it is never logged, and no
 * message built here contains it, the session cookie or a request body.
 * Thread safety: {@link #ensureSession()} and {@link #close()} are
 * synchronized; {@link #call} may run from many threads once a session
 * exists.
 */
public final class VimSoapClient implements VimSession {

    /** The protocol default connect timeout (design 4.1). */
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 120_000;
    /** The cheap calls (keepalive, login) wait this long for an answer. */
    public static final int SHORT_READ_TIMEOUT_MS = 30_000;
    /**
     * The most bytes of an answer read into a tree ({@link XmlNode}): service
     * content, a keepalive, a fault. The bulk reads of later phases stream and
     * set their own, larger cap.
     */
    public static final long MAX_RESPONSE_BYTES = 1024L * 1024;
    private static final int MAX_FAULT_BYTES = 1024 * 1024;
    private static final int LOGOUT_TIMEOUT_MS = 5_000;

    private final String url;
    private final String user;
    private final String password; // REDACT-SECRET
    private final VcenterTls tls;
    private final Log log;
    private final int connectMs;
    private final int readMs;
    private final int shortReadMs;

    private volatile String cookie;
    private volatile MoRef sessionManager;
    private volatile About about;
    private volatile boolean closed;
    private volatile String loginFailure;
    /** Counts successful logins, so a call can tell whether another thread already logged in again. */
    private volatile int generation;

    /**
     * @param hostPort {@code host:port}, an IPv6 literal bracketed
     */
    public VimSoapClient(String hostPort, String user, String password, VcenterTls tls, Log log) {
        this(hostPort, user, password, tls, log, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                SHORT_READ_TIMEOUT_MS);
    }

    public VimSoapClient(String hostPort, String user, String password, VcenterTls tls, Log log,
            int connectMs, int readMs, int shortReadMs) {
        this("https://" + hostPort + "/sdk", user, password, tls, log, connectMs, readMs,
                shortReadMs, true);
    }

    /** Test seam: the full SDK URL. */
    VimSoapClient(String sdkUrl, String user, String password, VcenterTls tls, Log log,
            int connectMs, int readMs, int shortReadMs, boolean fullUrl) {
        this.url = sdkUrl;
        this.user = user;
        this.password = password;
        this.tls = tls;
        this.log = log == null ? Log.NONE : log;
        this.connectMs = connectMs;
        this.readMs = readMs;
        this.shortReadMs = Math.min(shortReadMs, readMs);
    }

    // ------------------------------------------------------------------ session

    /** Whether a session is held (no call is made). */
    public boolean hasSession() {
        return cookie != null;
    }

    /** The about of the current session, or null. */
    public About about() {
        return about;
    }

    /**
     * Have a live session and return who it is. A held session is checked
     * with one {@code CurrentTime} call; when that finds it expired, one new
     * login is made. A failed login is remembered for the rest of the cycle
     * ({@link #beginCycle}) so a wrong password is sent once per cycle, not
     * once per call (the SSO lockout policy counts attempts).
     */
    @Override
    public synchronized VimResult<About> ensureSession() {
        if (closed) return VimResult.fault("Closed", CLOSED, null, 0, null);
        if (cookie != null) {
            VimResult<XmlNode> ka = rawCall("<CurrentTime xmlns=\"urn:vim25\">"
                    + "<_this type=\"ServiceInstance\">ServiceInstance</_this></CurrentTime>",
                    "CurrentTime", true, shortReadMs);
            if (ka.isOk()) {
                log.info("vCenter session reused (kept alive, no new login): " + described(about));
                return VimResult.ok(about, ka.bytes());
            }
            if (!"NotAuthenticated".equals(ka.faultClass())) {
                return VimResult.fault(ka.faultClass(), "the session check failed: "
                        + ka.message(), null, ka.bytes(), ka.cause());
            }
            clearSession();
            return login("renewed");
        }
        return login("new");
    }

    /** The vCenter's product and version for a log line; never a credential. */
    private static String described(About a) {
        return a == null ? "vCenter" : a.describe();
    }

    /** A new cycle may try to log in again after a failed login. */
    @Override
    public void beginCycle() {
        loginFailure = null;
    }

    /**
     * @param how {@code new} or {@code renewed} (the held session had expired) picks the one
     *        log line a successful login writes; null writes none (the caller already has)
     */
    private VimResult<About> login(String how) {
        String failed = loginFailure;
        if (failed != null) {
            return VimResult.fault("InvalidLogin", "not logging in again this cycle (the last "
                    + "login failed: " + failed + ")", null, 0, null);
        }
        VimResult<XmlNode> sc = rawCall("<RetrieveServiceContent xmlns=\"urn:vim25\">"
                + "<_this type=\"ServiceInstance\">ServiceInstance</_this>"
                + "</RetrieveServiceContent>", "RetrieveServiceContent", false, shortReadMs);
        if (!sc.isOk()) {
            // a pin mismatch already says everything; other failures get the step they hit
            String m = TlsFailure.explain(sc.cause(), tls.mode()) != null ? sc.message()
                    : "reading the service content failed: " + sc.message();
            return VimResult.fault(sc.faultClass(), m, null, sc.bytes(), sc.cause());
        }
        XmlNode rv = sc.value().find("returnval");
        if (rv == null) {
            return VimResult.fault("Parse", "the service content has no returnval", null,
                    sc.bytes(), null);
        }
        XmlNode sm = rv.child("sessionManager");
        if (sm == null || sm.text().isEmpty()) {
            return VimResult.fault("Parse", "the service content names no session manager", null,
                    sc.bytes(), null);
        }
        XmlNode ab = rv.child("about");
        About a = ab == null ? new About(null, null, null, null, null, null)
                : new About(ab.childText("instanceUuid"), ab.childText("fullName"),
                        ab.childText("version"), ab.childText("apiVersion"),
                        ab.childText("build"), ab.childText("patchLevel"));
        MoRef smRef = new MoRef(sm.attr("type") == null ? "SessionManager" : sm.attr("type"),
                sm.text());

        Holder<String> newCookie = new Holder<>();
        String loginBody;
        try {
            loginBody = "<Login xmlns=\"urn:vim25\">"
                    + "<_this type=\"SessionManager\">" + xml(smRef.value) + "</_this>"
                    + "<userName>" + xml(user) + "</userName>"
                    + "<password>" + xml(password) + "</password>" // REDACT-SECRET
                    + "</Login>";
        } catch (IllegalArgumentException e) {
            loginFailure = "InvalidLogin: unsendable credential";
            return VimResult.fault("InvalidLogin", "login failed: the vCenter user name or "
                    + "password contains a character that XML cannot carry", null, 0, null);
        }
        VimResult<XmlNode> lg = post(loginBody, "Login", false, shortReadMs, newCookie);
        if (!lg.isOk()) {
            String m = "InvalidLogin".equals(lg.faultClass())
                    ? "vCenter rejected the user name or password" : lg.message();
            loginFailure = lg.faultClass() + ": " + m;
            return VimResult.fault(lg.faultClass(), "login failed: " + m, null, lg.bytes(),
                    lg.cause());
        }
        if (newCookie.value == null) {
            loginFailure = "no session cookie";
            return VimResult.fault("Parse", "login succeeded but vCenter returned no session "
                    + "cookie", null, lg.bytes(), null);
        }
        loginFailure = null;
        this.sessionManager = smRef;
        this.about = a;
        this.cookie = newCookie.value;
        this.generation++;
        if (closed) { // closed while this login was in flight: log it straight out
            logoutAndClear();
            return VimResult.fault("Closed", CLOSED, null, 0, null);
        }
        if ("renewed".equals(how)) {
            log.info("vCenter session renewed (the held session had expired; logged in again): "
                    + described(a));
        } else if ("new".equals(how)) {
            log.info("vCenter login succeeded (new session): " + described(a));
        }
        return VimResult.ok(a, sc.bytes() + lg.bytes());
    }

    static final String CLOSED = "the vCenter client is closed (the account was edited or "
            + "removed); not logging in again";

    /**
     * One authenticated call: log in again once and retry when the session
     * turns out to be dead. {@code bodyXml} is the operation element with its
     * {@code _this}; the answer is the element tree of the SOAP body.
     */
    public VimResult<XmlNode> call(String bodyXml, String operation) {
        int gen = generation;
        VimResult<XmlNode> r = rawCall(bodyXml, operation, true, readMs);
        if (r.isOk() || !"NotAuthenticated".equals(r.faultClass())) return r;
        synchronized (this) {
            if (closed) return VimResult.fault("Closed", CLOSED, null, 0, null);
            if (loginFailure != null) {
                return VimResult.fault("NotAuthenticated", "the session is not authenticated and "
                        + "this cycle's login failed", null, 0, null);
            }
            if (gen == generation) { // nobody has logged in again since this call started
                log.warn("vCenter session not authenticated on " + operation
                        + "; logging in again and retrying once");
                clearSession();
                VimResult<About> l = login(null); // the warning above is this event's line
                if (!l.isOk()) {
                    return VimResult.fault(l.faultClass(), l.message(), null, 0, l.cause());
                }
            }
        }
        return rawCall(bodyXml, operation, true, readMs);
    }

    /** Log out (bounded wait) and refuse every later call. Never throws. */
    @Override
    public void close() {
        close("closed");
    }

    /**
     * Log out and refuse every later call, saying why in the log (one line,
     * only when a session was actually held). Never throws.
     *
     * @param reason {@code reconfigure} or {@code stop}, for the log line
     */
    @Override
    public synchronized void close(String reason) {
        closed = true;
        boolean held = cookie != null;
        boolean out = logoutAndClear();
        if (held) {
            log.info(out ? "vCenter session logged out (reason: " + reason + ")"
                    : "vCenter logout did not complete (reason: " + reason + "); the session "
                            + "expires on its own");
        }
    }

    /** Logs the held session out and forgets it; false when the logout could not be sent or failed. */
    private boolean logoutAndClear() {
        MoRef sm = sessionManager;
        boolean ok = true;
        if (sm != null && cookie != null) {
            try {
                postOnce("<Logout xmlns=\"urn:vim25\"><_this type=\"SessionManager\">"
                        + xml(sm.value) + "</_this></Logout>", "Logout", true,
                        LOGOUT_TIMEOUT_MS, LOGOUT_TIMEOUT_MS, null);
            } catch (Exception ignored) {
                // an idle session expires on its own
                ok = false;
            }
        }
        clearSession();
        return ok;
    }

    private void clearSession() {
        cookie = null;
        sessionManager = null;
        about = null;
    }

    // ------------------------------------------------------------------ transport

    private VimResult<XmlNode> rawCall(String bodyXml, String operation, boolean authenticated,
            int readTimeoutMs) {
        return post(bodyXml, operation, authenticated, readTimeoutMs, null);
    }

    private static final class Holder<T> {
        T value;
    }

    /** One POST mapped to a result; never throws. */
    private VimResult<XmlNode> post(String bodyXml, String operation, boolean authenticated,
            int readTimeoutMs, Holder<String> cookieOut) {
        try {
            return postOnce(bodyXml, operation, authenticated, connectMs, readTimeoutMs, cookieOut);
        } catch (PinMismatchWrapper e) {
            return VimResult.fault("Transport", e.getMessage(), null, 0, e.getCause());
        } catch (IOException e) {
            String tlsText = TlsFailure.explain(e, tls.mode());
            return VimResult.fault("Transport", tlsText != null ? tlsText : describe(e), null, 0, e);
        } catch (RuntimeException e) {
            return VimResult.fault("Transport", operation + " failed: "
                    + e.getClass().getSimpleName(), null, 0, e);
        }
    }

    /** Carries the clean message of a pin mismatch out of the SSL exception around it. */
    private static final class PinMismatchWrapper extends IOException {
        private static final long serialVersionUID = 1L;

        PinMismatchWrapper(PinMismatchException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private VimResult<XmlNode> postOnce(String bodyXml, String operation, boolean authenticated,
            int connectTimeoutMs, int readTimeoutMs, Holder<String> cookieOut) throws IOException {
        String envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"><soapenv:Body>"
                + bodyXml + "</soapenv:Body></soapenv:Envelope>";
        tls.clearPinMismatch();
        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        if (conn instanceof HttpsURLConnection) {
            tls.secure((HttpsURLConnection) conn);
        }
        CappedInputStream body = null;
        try {
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false); // never carry the session cookie elsewhere
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            conn.setRequestProperty("SOAPAction", "urn:vim25/" + operation);
            String c = cookie;
            if (authenticated && c != null) conn.setRequestProperty("Cookie", c);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(envelope.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (cookieOut != null) cookieOut.value = sessionCookie(conn.getHeaderFields());
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                if (is == null) {
                    return VimResult.fault("Parse", operation + " returned no body", null, 0, null);
                }
                body = new CappedInputStream(is, MAX_RESPONSE_BYTES);
                XmlNode doc;
                try {
                    doc = XmlNode.parse(body);
                } catch (XMLStreamException e) {
                    conn.disconnect();
                    if (body.exceeded) {
                        return VimResult.fault("TooLarge", operation + " answered more than "
                                + MAX_RESPONSE_BYTES + " bytes; not read", null, body.read, null);
                    }
                    return VimResult.fault("Parse", operation + " answered something that is not "
                            + "well-formed XML", null, body.read, null);
                }
                long n = body.read;
                body.close();
                XmlNode b = doc.find("Body");
                return VimResult.ok(b == null ? doc : b, n);
            }
            String fault = readCapped(conn.getErrorStream(), MAX_FAULT_BYTES);
            return faultResult(operation, code, fault);
        } catch (IOException | RuntimeException e) {
            conn.disconnect(); // never reuse a connection that failed
            // Not only an SSLException: the FIPS provider throws its own alert, an IOException of
            // another kind, with the pin mismatch below it. When the chain was rewrapped past
            // recognition, the trust manager's own record of this thread's handshake answers.
            PinMismatchException pm = TlsFailure.findPin(e);
            if (pm == null) pm = tls.takePinMismatch();
            if (pm != null) throw new PinMismatchWrapper(pm);
            throw e;
        }
    }

    /**
     * The session cookie ({@code name=value}) from the response headers. Header
     * names are matched ignoring case: a proxy in front of the vCenter may send
     * {@code set-cookie}, and {@code getHeaderFields()} keeps the case it got.
     */
    public static String sessionCookie(Map<String, List<String>> headers) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !"set-cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String h : e.getValue()) {
                if (h != null && h.startsWith("vmware_soap_session")) {
                    int semi = h.indexOf(';');
                    return semi < 0 ? h : h.substring(0, semi);
                }
            }
        }
        return null;
    }

    /** A SOAP fault body (HTTP 500) as a result: the fault's class, its text and the path it names. */
    static VimResult<XmlNode> faultResult(String operation, int code, String faultBody) {
        String faultClass = "Http";
        String text = null;
        String path = null;
        if (faultBody != null && !faultBody.isEmpty()) {
            try {
                XmlNode f = XmlNode.parse(new java.io.ByteArrayInputStream(
                        faultBody.getBytes(StandardCharsets.UTF_8)));
                XmlNode fs = f.find("faultstring");
                if (fs != null) text = fs.text();
                XmlNode detail = f.find("detail");
                if (detail != null && !detail.children().isEmpty()) {
                    XmlNode d = detail.children().get(0);
                    faultClass = d.name.endsWith("Fault")
                            ? d.name.substring(0, d.name.length() - "Fault".length()) : d.name;
                    if ("InvalidProperty".equals(faultClass)) {
                        path = d.childText("name");
                    }
                }
            } catch (XMLStreamException | RuntimeException ignored) {
                // an unparseable fault is just an HTTP error
            }
        }
        String msg = operation + " was refused (HTTP " + code + ")";
        if (text != null && !text.isEmpty()) {
            msg += ": " + (text.length() > 200 ? text.substring(0, 200) + "..." : text);
        }
        return VimResult.fault(faultClass, msg, path, faultBody == null ? 0 : faultBody.length(),
                null);
    }

    /** A transport failure as one credential-free line. */
    static String describe(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "the vCenter did not answer in time (" + e.getClass().getSimpleName() + ")";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "the vCenter host name could not be resolved";
        }
        if (e instanceof java.net.ConnectException) {
            return "the vCenter refused or did not accept the connection";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 200
                ? m.substring(0, 200) + "..." : m));
    }

    static String readCapped(InputStream is, int max) {
        if (is == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (bos.size() < max && (n = is.read(buf, 0, Math.min(buf.length, max - bos.size()))) >= 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:
                    // a character XML 1.0 cannot carry is refused, never silently dropped
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        throw new IllegalArgumentException("a character XML cannot carry");
                    }
                    b.append(c);
            }
        }
        return b.toString();
    }

    /** An input stream that fails once it has read more than its cap. */
    static final class CappedInputStream extends FilterInputStream {
        private final long max;
        long read;
        boolean exceeded;

        CappedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        private void count(long n) throws IOException {
            if (n <= 0) return;
            read += n;
            if (read > max) {
                exceeded = true;
                throw new IOException("response over " + max + " bytes");
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(n);
            return n;
        }
    }
}
