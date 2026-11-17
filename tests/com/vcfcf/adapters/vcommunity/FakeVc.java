package com.vcfcf.adapters.vcommunity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * A throwaway TLS server that speaks just enough vim25 for the client tests.
 * The certificate is made at test time by the JDK's own keytool into a
 * temporary directory, with a random password, and deleted afterwards: no key
 * or certificate is stored in the repository.
 */
final class FakeVc implements AutoCloseable {

    /** One request the server saw. */
    static final class Req {
        final String action;
        final String cookie;
        final String body;
        /** A REST request (no SOAPAction): its method and path with query, and the two headers that matter. */
        final String method;
        final String path;
        final String session;
        final String authorization;

        Req(String action, String cookie, String body) {
            this(action, cookie, body, null, null, null, null);
        }

        Req(String action, String cookie, String body, String method, String path, String session,
                String authorization) {
            this.action = action;
            this.cookie = cookie;
            this.body = body;
            this.method = method;
            this.path = path;
            this.session = session;
            this.authorization = authorization;
        }
    }

    /** What the server answers. */
    static final class Resp {
        final int status;
        final String setCookie;
        final String body;
        final String contentType;
        /** A Location header to send (a redirect), or null. */
        String location;

        Resp withLocation(String l) {
            this.location = l;
            return this;
        }

        Resp(int status, String setCookie, String body) {
            this(status, setCookie, body, "text/xml; charset=utf-8");
        }

        Resp(int status, String setCookie, String body, String contentType) {
            this.status = status;
            this.setCookie = setCookie;
            this.body = body;
            this.contentType = contentType;
        }

        /** A JSON answer of the vSphere Automation REST API. */
        static Resp json(int status, String body) {
            return new Resp(status, null, body, "application/json");
        }
    }

    interface Handler {
        Resp handle(Req r);
    }

    static final String INSTANCE_UUID = "11111111-2222-3333-4444-555555555555";
    static final String COOKIE = "vmware_soap_session=\"52c0ffee-test-session\"";

    private final ServerSocket server;
    final X509Certificate cert;
    /** The certificate a client must trust for this server: the leaf, or the CA that signed it. */
    final X509Certificate anchor;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    private volatile Handler handler;
    /** Answers the requests that carry no SOAPAction (the REST API), if set. */
    private volatile Handler restHandler;
    private volatile boolean running = true;
    /** Send header names in lower case, as a proxy in front of the vCenter may. */
    volatile boolean lowercaseHeaders;

    static String envelope(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope "
                + "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Body>" + inner + "</soapenv:Body></soapenv:Envelope>";
    }

    static Resp serviceContent() {
        return new Resp(200, null, envelope("<RetrieveServiceContentResponse xmlns=\"urn:vim25\">"
                + "<returnval><rootFolder type=\"Folder\">group-d1</rootFolder>"
                + "<propertyCollector type=\"PropertyCollector\">propertyCollector</propertyCollector>"
                + "<about><name>VMware vCenter Server</name>"
                + "<fullName>VMware vCenter Server 8.0.3 build-24022515</fullName>"
                + "<version>8.0.3</version><build>24022515</build>"
                + "<apiVersion>8.0.3.0</apiVersion><instanceUuid>" + INSTANCE_UUID + "</instanceUuid>"
                + "</about><sessionManager type=\"SessionManager\">SessionManager</sessionManager>"
                + "</returnval></RetrieveServiceContentResponse>"));
    }

    static Resp loginOk() {
        return new Resp(200, COOKIE + "; Path=/; HttpOnly; Secure", envelope(
                "<LoginResponse xmlns=\"urn:vim25\"><returnval><key>x</key></returnval></LoginResponse>"));
    }

    static Resp fault(String faultClass, String text) {
        return new Resp(500, null, envelope("<soapenv:Fault><faultcode>ServerFaultCode</faultcode>"
                + "<faultstring>" + text + "</faultstring><detail><" + faultClass
                + "Fault xmlns=\"urn:vim25\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:type=\"" + faultClass + "\"/></detail></soapenv:Fault>"));
    }

    static Resp currentTime() {
        return new Resp(200, null, envelope("<CurrentTimeResponse xmlns=\"urn:vim25\">"
                + "<returnval>2026-11-13T10:00:00Z</returnval></CurrentTimeResponse>"));
    }

    /** A vCenter that accepts every login. */
    static Handler happy() {
        return r -> {
            switch (r.action) {
                case "RetrieveServiceContent": return serviceContent();
                case "Login": return loginOk();
                case "CurrentTime": return currentTime();
                default: return new Resp(200, null, envelope("<" + r.action + "Response xmlns=\"urn:vim25\"/>"));
            }
        };
    }

    FakeVc(Handler h) throws Exception {
        this(h, "dns:localhost,ip:127.0.0.1");
    }

    /** @param san the certificate's subject alternative names, in keytool's {@code -ext san=} form */
    FakeVc(Handler h, String san) throws Exception {
        this(h, san, false);
    }

    /** A server that reuses one certificate made once for the whole run (keytool takes a second each time). */
    static FakeVc withSharedCertificate(Handler h) throws Exception {
        return new FakeVc(h, "dns:localhost,ip:127.0.0.1", true);
    }

    /** A server whose certificate expired yesterday (started three days ago, valid for one day). */
    static FakeVc withExpiredCertificate(Handler h) throws Exception {
        return new FakeVc(h, "dns:localhost,ip:127.0.0.1", false, "-3d");
    }

    private static KeyStore sharedStore;
    private static char[] sharedPassword;

    private FakeVc(Handler h, String san, boolean shared) throws Exception {
        this(h, san, shared, null);
    }

    private FakeVc(Handler h, String san, boolean shared, String startDate) throws Exception {
        this.handler = h;
        KeyStore store;
        char[] pw;
        synchronized (FakeVc.class) {
            if (shared && sharedStore != null) {
                store = sharedStore;
                pw = sharedPassword;
            } else {
                pw = randomPassword();
                store = generate(san, pw, startDate);
                if (shared) {
                    sharedStore = store;
                    sharedPassword = pw;
                }
            }
        }
        this.cert = (X509Certificate) store.getCertificate("fake");
        // what a client must trust: the leaf itself when it is self-signed, else the CA that signed it
        java.security.cert.Certificate ca = store.getCertificate("ca");
        this.anchor = ca != null ? (X509Certificate) ca : this.cert;
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        this.server = ctx.getServerSocketFactory().createServerSocket(0, 20, InetAddress.getLoopbackAddress());
        ((SSLServerSocket) server).setUseClientMode(false);
        Thread t = new Thread(this::acceptLoop, "fakevc-accept");
        t.setDaemon(true);
        t.start();
    }

    /** A key pair and certificate from the JDK's keytool, in a temporary directory that is removed before this returns. */
    private static KeyStore generate(String san, char[] pw, String startDate) throws Exception {
        Path dir = Files.createTempDirectory("fakevc");
        try {
            File ks = dir.resolve("ks.p12").toFile();
            if (startDate != null) {
                return generateExpired(dir, san, pw, startDate);
            }
            run(javaTool("keytool"), "-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "2", "-dname", "CN=localhost", "-ext", "san=" + san,
                    "-storetype", "PKCS12", "-keystore", ks.getPath(), "-storepass", new String(pw),
                    "-keypass", new String(pw));
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(ks)) {
                store.load(in, pw);
            }
            return store;
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                List<Path> all = new ArrayList<>();
                files.forEach(all::add);
                Collections.reverse(all);
                for (Path p : all) Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // temp files
            }
        }
    }

    int port() {
        return server.getLocalPort();
    }

    void setHandler(Handler h) {
        this.handler = h;
    }

    void setRestHandler(Handler h) {
        this.restHandler = h;
    }

    List<Req> seen(String action) {
        List<Req> out = new ArrayList<>();
        for (Req r : requests) if (r.action.equals(action)) out.add(r);
        return out;
    }

    private static char[] randomPassword() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        // hex: a URL-safe base64 password can start with a dash or hold an underscore, and keytool's launcher
        // then reads it as an option or a class name (seen as "Could not find or load main class <password>")
        StringBuilder hex = new StringBuilder("pw");
        for (byte x : b) hex.append(String.format("%02x", x));
        return hex.toString().toCharArray();
    }

    private static String javaTool(String name) {
        return new File(new File(System.getProperty("java.home"), "bin"), name).getPath();
    }

    /**
     * A leaf signed by a CA of the test's own, whose validity ended yesterday. A self-signed leaf cannot be used for this: a
     * trust anchor's dates are not checked, so only a leaf below an anchor shows the platform's expiry failure.
     */
    private static KeyStore generateExpired(Path dir, String san, char[] pw, String startDate) throws Exception {
        String keytool = javaTool("keytool");
        String p = new String(pw);
        File ca = dir.resolve("ca.p12").toFile();
        File ks = dir.resolve("ks.p12").toFile();
        File csr = dir.resolve("leaf.csr").toFile();
        File leaf = dir.resolve("leaf.pem").toFile();
        File caPem = dir.resolve("ca.pem").toFile();
        run(keytool, "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                "-dname", "CN=test-ca", "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", ca.getPath(),
                "-storepass", p, "-keypass", p);
        run(keytool, "-exportcert", "-alias", "ca", "-rfc", "-file", caPem.getPath(), "-keystore", ca.getPath(), "-storepass", p);
        run(keytool, "-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                "-dname", "CN=localhost", "-ext", "san=" + san, "-storetype", "PKCS12", "-keystore", ks.getPath(),
                "-storepass", p, "-keypass", p);
        run(keytool, "-certreq", "-alias", "fake", "-file", csr.getPath(), "-keystore", ks.getPath(), "-storepass", p);
        run(keytool, "-gencert", "-alias", "ca", "-infile", csr.getPath(), "-outfile", leaf.getPath(), "-rfc",
                "-startdate", startDate, "-validity", "1", "-ext", "san=" + san, "-keystore", ca.getPath(),
                "-storepass", p, "-keypass", p);
        run(keytool, "-importcert", "-noprompt", "-alias", "ca", "-file", caPem.getPath(), "-keystore", ks.getPath(), "-storepass", p);
        run(keytool, "-importcert", "-noprompt", "-alias", "fake", "-file", leaf.getPath(), "-keystore", ks.getPath(),
                "-storepass", p, "-keypass", p);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks)) {
            store.load(in, pw);
        }
        return store;
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + out.toString("UTF-8"));
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                connections.incrementAndGet();
                Thread t = new Thread(() -> serve(s), "fakevc-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            while (running) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) return;
                int len = 0;
                String action = "";
                String cookie = null;
                String session = null;
                String authorization = null;
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    String lower = h.toLowerCase();
                    if (lower.startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
                    else if (lower.startsWith("soapaction:")) {
                        action = h.substring(11).trim();
                        if (action.startsWith("urn:vim25/")) action = action.substring(10);
                    } else if (lower.startsWith("cookie:")) cookie = h.substring(7).trim();
                    else if (lower.startsWith("vmware-api-session-id:")) session = h.substring(22).trim();
                    else if (lower.startsWith("authorization:")) authorization = h.substring(14).trim();
                }
                String[] rl = line.split(" ");
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) return;
                    off += n;
                }
                boolean rest = action.isEmpty() && restHandler != null && rl.length >= 2;
                Req r = rest
                        ? new Req("REST " + rl[0] + " " + rl[1], cookie, new String(body, StandardCharsets.UTF_8),
                                rl[0], rl[1], session, authorization)
                        : new Req(action, cookie, new String(body, StandardCharsets.UTF_8));
                requests.add(r);
                Resp resp = rest ? restHandler.handle(r) : handler.handle(r);
                byte[] rb = resp.body.getBytes(StandardCharsets.UTF_8);
                StringBuilder head = new StringBuilder();
                head.append("HTTP/1.1 ").append(resp.status).append(resp.status == 200 ? " OK" : " Other").append("\r\n");
                head.append(hdr("Content-Type")).append(": ").append(resp.contentType).append("\r\n");
                head.append(hdr("Content-Length")).append(": ").append(rb.length).append("\r\n");
                if (resp.setCookie != null) head.append(hdr("Set-Cookie")).append(": ").append(resp.setCookie).append("\r\n");
                if (resp.location != null) head.append("Location: ").append(resp.location).append("\r\n");
                head.append("\r\n");
                // one write: two small writes meet Nagle and the peer's delayed ACK, 40 ms a request
                byte[] hb = head.toString().getBytes(StandardCharsets.UTF_8);
                byte[] all = new byte[hb.length + rb.length];
                System.arraycopy(hb, 0, all, 0, hb.length);
                System.arraycopy(rb, 0, all, hb.length, rb.length);
                out.write(all);
                out.flush();
            }
        } catch (IOException | RuntimeException e) {
            // the client hung up or the handshake was refused: nothing to serve
        }
    }

    private String hdr(String name) {
        return lowercaseHeaders ? name.toLowerCase() : name;
    }

    /** A TLS context that trusts this server's certificate, for tests of the real name check. */
    javax.net.ssl.SSLContext trustingContext() throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, null);
        ts.setCertificateEntry("fake", anchor);
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
        }
        if (c < 0 && b.length() == 0) return null;
        return b.toString();
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing
        }
    }
}
