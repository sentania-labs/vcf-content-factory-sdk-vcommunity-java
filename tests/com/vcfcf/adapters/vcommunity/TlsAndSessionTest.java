package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.health.Reachability;
import com.vcfcf.adapters.vcommunity.tls.PinMismatchException;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.tls.Thumbprints;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import java.net.ServerSocket;
import javax.net.ssl.SSLContext;

/**
 * The three vCenter TLS modes and the session, against a local TLS server
 * with a certificate made at test time.
 */
public final class TlsAndSessionTest {

    static VimSoapClient client(FakeVc vc, VcenterTls tls, String user, String pw, T.RecLog log) {
        return new VimSoapClient("localhost:" + vc.port(), user, pw, tls, log, 5000, 10000, 10000);
    }

    static VcenterTls tofu() {
        return VcenterTls.create(TlsMode.TRUST_ON_FIRST_USE, "", null, null);
    }

    static VcenterTls pinned(String pin) {
        return VcenterTls.create(TlsMode.PINNED, pin, null, null);
    }

    public static void main(String[] args) throws Exception {
        try (FakeVc vc = new FakeVc(FakeVc.happy())) {
            probe(vc);
            trustOnFirstUse(vc);
            pinnedRight(vc);
            pinnedWrong(vc);
            enabledRefusesUntrusted(vc);
            lowercaseCookieHeader(vc);
        }
        enabledNameCheck();
        oversizedAndRedirect();
        deepXml();
        sessionLifecycle();
        loginFailures();
        unreachable();
    }

    static void probe(FakeVc vc) throws Exception {
        String want = Thumbprints.of(vc.cert, 256);
        ThumbprintProbe.Result r = ThumbprintProbe.probe("localhost", vc.port(), 5000);
        T.check(r.ok(), "probe ok: " + r.failure());
        T.eq(want, r.thumbprint(), "the probe reads the SHA-256 of the leaf");
        T.eq(64, r.thumbprint().length(), "64 hex digits");
        T.eq(r.thumbprint(), r.thumbprint().toLowerCase(), "lower case");
        int before = vc.requests.size();
        ThumbprintProbe.probe("localhost", vc.port(), 5000);
        T.eq(before, vc.requests.size(), "the probe sends nothing after the handshake");
        ThumbprintProbe.Result bad = ThumbprintProbe.probe("localhost", freePort(), 2000);
        T.check(!bad.ok() && bad.failure() != null && bad.thumbprint() == null, "refused port");
        T.contains(bad.failure(), "ConnectException", "says what failed");
        T.done("probe reads the presented thumbprint and sends nothing");
    }

    static void trustOnFirstUse(FakeVc vc) throws Exception {
        T.RecLog log = new T.RecLog();
        try (VimSoapClient c = client(vc, tofu(), "svc", "pw-1", log)) {
            VimResult<About> r = c.ensureSession();
            T.check(r.isOk(), "TOFU logs in against a self-signed certificate: " + r.message());
            T.eq(FakeVc.INSTANCE_UUID, r.value().instanceUuid, "VCID");
            T.eq("8.0.3", r.value().version, "version");
            T.eq("8.0.3.0", r.value().apiVersion, "api version");
            T.contains(r.value().describe(), "VMware vCenter Server 8.0.3", "describe");
            T.check(c.hasSession(), "session held");
        }
        T.done("Trust on First Use connects to a self-signed certificate");
    }

    static void pinnedRight(FakeVc vc) throws Exception {
        String pin256 = Thumbprints.of(vc.cert, 256);
        String pin512 = Thumbprints.of(vc.cert, 512);
        for (String pin : new String[] {pin256, pin512}) {
            try (VimSoapClient c = client(vc, pinned(pin), "svc", "pw-1", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.check(r.isOk(), "Pinned with the right " + pin.length() * 4 + " bit pin: " + r.message());
            }
        }
        T.done("Pinned accepts the right SHA-256 and SHA-512 pin");
    }

    static void pinnedWrong(FakeVc vc) throws Exception {
        String wrong = "00" + Thumbprints.of(vc.cert, 256).substring(2);
        int before = vc.requests.size();
        T.RecLog log = new T.RecLog();
        try (VimSoapClient c = client(vc, pinned(wrong), "svc", "very-secret-pw", log)) {
            VimResult<About> r = c.ensureSession();
            T.check(!r.isOk(), "a wrong pin fails");
            T.contains(r.message(), "differs from the vCenter Thumbprint", "text names the field");
            T.contains(r.message(), Thumbprints.of(vc.cert, 256), "text carries the presented thumbprint");
            T.contains(r.message(), "Pinned", "text names the mode");
            T.check(r.message().startsWith("vCenter presented a certificate other than the pinned one"), "text is the pin message as is");
            T.notContains(r.message(), "very-secret-pw", "message");
            T.check(r.cause() instanceof PinMismatchException || r.cause() != null, "cause kept");
            T.check(!Reachability.isUnreachable(r.cause()), "a wrong pin is not 'unreachable'");
        }
        // nothing at all reached the application layer: the credentials were never sent
        T.eq(before, vc.requests.size(), "no request (so no Login) after a pin mismatch");
        T.notContains(log.all(), "very-secret-pw", "log");
        T.done("Pinned refuses a wrong pin before any credential is sent");
    }

    static void enabledRefusesUntrusted(FakeVc vc) throws Exception {
        // Enabled uses the platform's trust; here the JDK default trust, which does not know the test CA.
        SSLContext platform = SSLContext.getDefault();
        VcenterTls.Securer securer = (conn, factory) -> conn.setSSLSocketFactory(factory);
        VcenterTls tls = VcenterTls.create(TlsMode.ENABLED, "", platform, securer);
        int before = vc.requests.size();
        try (VimSoapClient c = client(vc, tls, "svc", "pw", new T.RecLog())) {
            VimResult<About> r = c.ensureSession();
            T.check(!r.isOk(), "Enabled refuses a certificate the platform does not trust");
            boolean chain = false;
            for (Throwable t = r.cause(); t != null; t = t.getCause()) {
                if (t instanceof java.security.cert.CertPathBuilderException
                        || t instanceof java.security.cert.CertPathValidatorException
                        || (t.getMessage() != null && t.getMessage().contains("PKIX"))) chain = true;
            }
            T.check(chain, "the PKIX failure stays in the cause chain, so the platform's advice can apply");
        }
        T.eq(before, vc.requests.size(), "no credential sent");
        T.throwsIae(() -> VcenterTls.create(TlsMode.ENABLED, "", null, null), "platform", "Enabled needs the platform context");
        T.check(VcenterTls.create(TlsMode.ENABLED, "", platform, securer).checksName(), "Enabled checks the name");
        T.check(!tofu().checksName(), "TOFU does not");
        T.check(!pinned("a".repeat(64)).checksName(), "Pinned does not");
        T.done("Enabled refuses an untrusted certificate");
    }

    /** A proxy in front of the vCenter may send "set-cookie"; the session must still be kept. */
    static void lowercaseCookieHeader(FakeVc vc) throws Exception {
        vc.lowercaseHeaders = true;
        try (VimSoapClient c = client(vc, tofu(), "svc", "pw", new T.RecLog())) {
            T.check(c.ensureSession().isOk(), "login with lower case headers");
            T.check(c.hasSession(), "the cookie was found");
            int before = vc.seen("CurrentTime").size();
            T.check(c.ensureSession().isOk(), "keepalive");
            FakeVc.Req ka = vc.seen("CurrentTime").get(before);
            T.contains(ka.cookie, "vmware_soap_session", "the keepalive carries the cookie that came in lower case");
        }
        T.eq(1, vc.seen("Logout").size() > 0 ? 1 : 0, "the session was logged out (not leaked)");
        vc.lowercaseHeaders = false;
        java.util.Map<String, java.util.List<String>> h = new java.util.LinkedHashMap<>();
        h.put(null, java.util.Arrays.asList("HTTP/1.1 200 OK"));
        h.put("SET-COOKIE", java.util.Arrays.asList("other=1", "vmware_soap_session=\"abc\"; Path=/"));
        T.eq("vmware_soap_session=\"abc\"", VimSoapClient.sessionCookie(h), "any case, among several cookies");
        T.check(VimSoapClient.sessionCookie(null) == null, "no headers");
        T.check(VimSoapClient.sessionCookie(new java.util.HashMap<>()) == null, "no cookie");
        T.done("session cookie is found whatever the header case");
    }

    /** The real certificate name check (JDK endpoint identification), not a stub. */
    static void enabledNameCheck() throws Exception {
        // the certificate names "localhost" only: dialling it by that name passes, the IP does not
        try (FakeVc vc = new FakeVc(FakeVc.happy(), "dns:localhost")) {
            VcenterTls tls = VcenterTls.create(TlsMode.ENABLED, "", vc.trustingContext(), nameChecking());
            try (VimSoapClient ok = new VimSoapClient("localhost:" + vc.port(), "u", "p", tls, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = ok.ensureSession();
                T.check(r.isOk(), "a trusted certificate that names the host is accepted: " + r.message());
            }
            int before = vc.requests.size();
            try (VimSoapClient bad = new VimSoapClient("127.0.0.1:" + vc.port(), "u", "p", tls, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = bad.ensureSession();
                T.check(!r.isOk(), "a trusted certificate that does not name the host is refused");
                boolean name = false;
                for (Throwable t = r.cause(); t != null; t = t.getCause()) {
                    if (t instanceof java.security.cert.CertificateException) name = true;
                }
                T.check(name, "the failure is a certificate failure");
            }
            T.eq(before, vc.requests.size(), "nothing sent to a server whose name does not match");
        }
        T.done("Enabled checks the certificate name for real");
    }

    /** A name check through JSSE endpoint identification: the real thing, what the framework adds on the platform. */
    static VcenterTls.Securer nameChecking() {
        return (conn, factory) -> {
            conn.setSSLSocketFactory(new javax.net.ssl.SSLSocketFactory() {
                private java.net.Socket id(java.net.Socket s) {
                    javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) s;
                    javax.net.ssl.SSLParameters p = ss.getSSLParameters();
                    p.setEndpointIdentificationAlgorithm("HTTPS");
                    ss.setSSLParameters(p);
                    return s;
                }
                @Override public String[] getDefaultCipherSuites() { return factory.getDefaultCipherSuites(); }
                @Override public String[] getSupportedCipherSuites() { return factory.getSupportedCipherSuites(); }
                @Override public java.net.Socket createSocket(java.net.Socket s, String h, int p, boolean a) throws java.io.IOException { return id(factory.createSocket(s, h, p, a)); }
                @Override public java.net.Socket createSocket(String h, int p) throws java.io.IOException { return id(factory.createSocket(h, p)); }
                @Override public java.net.Socket createSocket(String h, int p, java.net.InetAddress la, int lp) throws java.io.IOException { return id(factory.createSocket(h, p, la, lp)); }
                @Override public java.net.Socket createSocket(java.net.InetAddress h, int p) throws java.io.IOException { return id(factory.createSocket(h, p)); }
                @Override public java.net.Socket createSocket(java.net.InetAddress h, int p, java.net.InetAddress la, int lp) throws java.io.IOException { return id(factory.createSocket(h, p, la, lp)); }
            });
            conn.setHostnameVerifier((h, s) -> true);
        };
    }

    static void oversizedAndRedirect() throws Exception {
        // an answer over the tree cap is refused, never read whole
        try (FakeVc vc = new FakeVc(r -> new FakeVc.Resp(200, null, FakeVc.envelope("<x>" + "a".repeat(1_200_000) + "</x>")))) {
            try (VimSoapClient c = client(vc, tofu(), "svc", "pw", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "an oversized answer fails");
                T.contains(r.message(), "more than " + VimSoapClient.MAX_RESPONSE_BYTES + " bytes", "says why");
            }
        }
        T.check(VimSoapClient.MAX_RESPONSE_BYTES <= 2 * 1024 * 1024, "tree answers are capped near 1 MiB");
        // a redirect is not followed (the cookie never leaves for another URL)
        try (FakeVc vc = new FakeVc(r -> new FakeVc.Resp(302, null, ""))) {
            try (VimSoapClient c = client(vc, tofu(), "svc", "pw", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "a redirect is a failure");
                T.eq(1, vc.requests.size(), "followed nowhere");
            }
        }
        T.done("oversized answers and redirects are refused");
    }

    static void deepXml() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append("<a>");
        for (int i = 0; i < 100; i++) deep.append("</a>");
        try {
            com.vcfcf.adapters.vcommunity.vim.XmlNode.parse(new java.io.ByteArrayInputStream(deep.toString().getBytes("UTF-8")));
            throw new AssertionError("deep XML accepted");
        } catch (javax.xml.stream.XMLStreamException e) {
            T.contains(e.getMessage(), "nested deeper", "refused");
        }
        StringBuilder ok = new StringBuilder();
        for (int i = 0; i < 30; i++) ok.append("<a>");
        ok.append("<leaf>x</leaf>");
        for (int i = 0; i < 30; i++) ok.append("</a>");
        com.vcfcf.adapters.vcommunity.vim.XmlNode n = com.vcfcf.adapters.vcommunity.vim.XmlNode.parse(
                new java.io.ByteArrayInputStream(ok.toString().getBytes("UTF-8")));
        T.eq("x", n.find("leaf").text(), "30 levels are fine, find is iterative");
        T.check(n.find("missing") == null, "missing element is null");
        T.done("XML depth is bounded");
    }

    static void sessionLifecycle() throws Exception {
        final int[] mode = {0}; // 0 normal, 1 session lapsed
        try (FakeVc vc = new FakeVc(r -> {
            if (r.action.equals("CurrentTime") && mode[0] == 1) {
                return FakeVc.fault("NotAuthenticated", "The session is not authenticated.");
            }
            return FakeVc.happy().handle(r);
        })) {
            try (VimSoapClient c = client(vc, tofu(), "svc", "pw", new T.RecLog())) {
                T.check(c.ensureSession().isOk(), "first login");
                T.eq(1, vc.seen("Login").size(), "one login");
                T.eq(1, vc.seen("RetrieveServiceContent").size(), "one service content read");
                T.check(c.ensureSession().isOk(), "second cycle keeps the session");
                T.eq(1, vc.seen("Login").size(), "still one login (keepalive only)");
                T.eq(1, vc.seen("CurrentTime").size(), "one keepalive");
                FakeVc.Req ka = vc.seen("CurrentTime").get(0);
                T.contains(ka.cookie, "vmware_soap_session", "the keepalive carries the session cookie");
                T.check(vc.seen("Login").get(0).cookie == null, "the login carries none");

                mode[0] = 1; // the session lapses
                VimResult<About> again = c.ensureSession();
                mode[0] = 0;
                T.check(again.isOk(), "a lapsed session logs in again: " + again.message());
                T.eq(2, vc.seen("Login").size(), "second login after NotAuthenticated");
            }
            T.eq(1, vc.seen("Logout").size(), "close() logs the session out");
            T.check(vc.seen("Logout").get(0).cookie != null, "the logout carries the cookie");
            // a closed client never logs in again
            VimSoapClient c2 = client(vc, tofu(), "svc", "pw", new T.RecLog());
            c2.close();
            VimResult<About> closed = c2.ensureSession();
            T.check(!closed.isOk() && "Closed".equals(closed.faultClass()), "closed client refuses");
        }
        T.done("session: keepalive, relogin once, logout, closed");
    }

    static void loginFailures() throws Exception {
        final int[] logins = {0};
        try (FakeVc vc = new FakeVc(r -> {
            if (r.action.equals("Login")) {
                logins[0]++;
                T.notContains(r.body, "&amp;amp;", "escaped once");
                return FakeVc.fault("InvalidLogin", "Cannot complete login due to an incorrect user name or password.");
            }
            return FakeVc.happy().handle(r);
        })) {
            T.RecLog log = new T.RecLog();
            try (VimSoapClient c = client(vc, tofu(), "svc", "bad-pw-<&>", log)) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "wrong password fails");
                T.eq("InvalidLogin", r.faultClass(), "fault class");
                T.contains(r.message(), "rejected the user name or password", "clean text");
                T.notContains(r.message(), "bad-pw", "message");
                T.eq(1, logins[0], "one login attempt");
                // the same cycle does not try again (the SSO lockout policy counts attempts)
                VimResult<About> second = c.ensureSession();
                T.check(!second.isOk(), "still failing");
                T.eq(1, logins[0], "no second attempt in the same cycle");
                c.beginCycle();
                c.ensureSession();
                T.eq(2, logins[0], "a new cycle tries again");
                // the password reached the wire escaped, never raw
                String body = vc.seen("Login").get(0).body;
                T.contains(body, "bad-pw-&lt;&amp;&gt;", "escaped in the request");
            }
            T.notContains(log.all(), "bad-pw", "log");
        }
        // a password XML cannot carry is refused, never altered
        try (FakeVc vc = new FakeVc(FakeVc.happy())) {
            try (VimSoapClient c = client(vc, tofu(), "svc", "pw\u0001x", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "unsendable password fails");
                T.contains(r.message(), "cannot carry", "says why");
                T.eq(0, vc.seen("Login").size(), "nothing sent");
            }
        }
        T.done("login failures: once per cycle, escaped, never leaked");
    }

    static void unreachable() throws Exception {
        VimSoapClient c = new VimSoapClient("127.0.0.1:" + freePort(), "u", "p", tofu(), new T.RecLog(),
                1000, 1000, 1000);
        VimResult<About> r = c.ensureSession();
        T.check(!r.isOk(), "refused");
        T.check(Reachability.isUnreachable(r.cause()), "a refused connection is 'unreachable' (DOWN)");
        c.close();
        T.done("unreachable vCenter maps to unreachable");
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
