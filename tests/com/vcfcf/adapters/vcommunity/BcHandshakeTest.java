package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.tls.Thumbprints;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import java.security.Provider;
import java.security.Security;
import javax.net.ssl.SSLContext;

/**
 * Real TLS handshakes with the BouncyCastle JSSE provider as the JVM's TLS
 * provider, as on VCF Operations 9.1.1 collectors (FIPS), where the provider's
 * alert is an {@code IOException} that is not an {@code SSLException}: the
 * shape that lost the pin message in the 0.0.0.2 live run. The adapter's own
 * {@link VimSoapClient} and {@link VcenterTls} dial a local TLS server (SunJSSE).
 * Pattern from the compliance adapter's BcHandshakeTest.
 *
 * <p>Needs the BouncyCastle jars on the classpath (ci/run_java_tests.sh fetches
 * them from Maven Central, checksum pinned, test only, never in the pak); the
 * providers are loaded by name so the other tests compile without them. Runs in
 * its own JVM because it installs BCJSSE as the default TLS provider. The
 * non-FIPS jars are used: they throw the same alert type; the FIPS jars are
 * not on Maven Central.
 */
public final class BcHandshakeTest {

    private static final java.util.logging.Logger BC_LOG =
            java.util.logging.Logger.getLogger("org.bouncycastle");

    public static void main(String[] args) throws Exception {
        try (FakeVc vc = new FakeVc(FakeVc.happy())) { // the server is made with the JDK providers
            install();
            T.eq("BCJSSE", SSLContext.getInstance("TLS").getProvider().getName(), "BCJSSE is the TLS provider");
            wrongPin(vc);
            rightPin(vc);
            enabledUntrusted(vc);
            trustOnFirstUse(vc);
        }
        System.out.println("BcHandshakeTest: all assertions passed");
    }

    private static void install() throws Exception {
        BC_LOG.setLevel(java.util.logging.Level.OFF); // BCJSSE logs every alert with a trace
        Provider bc = (Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor().newInstance();
        Provider bcjsse = (Provider) Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider")
                .getDeclaredConstructor().newInstance();
        Security.insertProviderAt(bc, 1);
        Security.insertProviderAt(bcjsse, 2);
    }

    static void wrongPin(FakeVc vc) throws Exception {
        String wrong = "00" + Thumbprints.of(vc.cert, 256).substring(2);
        String presented = Thumbprints.of(vc.cert, 256);
        int before = vc.requests.size();
        T.RecLog log = new T.RecLog();
        try (VimSoapClient c = TlsAndSessionTest.client(vc, TlsAndSessionTest.pinned(wrong), "svc", "very-secret-pw", log)) {
            VimResult<About> r = c.ensureSession();
            T.check(!r.isOk(), "a wrong pin fails under BCJSSE");
            T.check(r.message().startsWith("vCenter presented a certificate other than the pinned one"),
                    "the caller gets the pin text, not the provider's alert: " + r.message());
            T.contains(r.message(), presented, "and the presented thumbprint");
            T.notContains(r.message(), "TlsFatalAlert", "no alert text");
            T.notContains(r.message(), "reading the service content failed", "no step prefix");
            T.check(r.cause() != null, "cause kept");
            System.out.println("BcHandshakeTest evidence: BCJSSE wrong pin; the provider's own exception was "
                    + describeChain(rawFailure(vc, wrong)));
        }
        T.eq(before, vc.requests.size(), "no request reached the server: no Login, no credential");
        T.notContains(log.all(), "very-secret-pw", "log");
        T.done("BCJSSE: a wrong pin gives the pin text and sends nothing");
    }

    static void rightPin(FakeVc vc) throws Exception {
        try (VimSoapClient c = TlsAndSessionTest.client(vc,
                TlsAndSessionTest.pinned(Thumbprints.of(vc.cert, 256)), "svc", "pw", new T.RecLog())) {
            VimResult<About> r = c.ensureSession();
            T.check(r.isOk(), "the right pin logs in under BCJSSE: " + r.message());
        }
        T.done("BCJSSE: the right pin connects");
    }

    static void enabledUntrusted(FakeVc vc) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        VcenterTls tls = VcenterTls.create(TlsMode.ENABLED, "", ctx, (conn, factory) -> conn.setSSLSocketFactory(factory));
        int before = vc.requests.size();
        try (VimSoapClient c = TlsAndSessionTest.client(vc, tls, "svc", "pw", new T.RecLog())) {
            VimResult<About> r = c.ensureSession();
            T.check(!r.isOk(), "an untrusted certificate fails under BCJSSE");
            T.contains(r.message(), "not trusted by this collector", "the collect path says so: " + r.message());
            T.notContains(r.message(), "TlsFatalAlert", "no alert text");
        }
        T.eq(before, vc.requests.size(), "nothing reached the server");
        T.done("BCJSSE: an untrusted chain in Enabled is said plainly");
    }

    static void trustOnFirstUse(FakeVc vc) throws Exception {
        try (VimSoapClient c = TlsAndSessionTest.client(vc, TlsAndSessionTest.tofu(), "svc", "pw", new T.RecLog())) {
            T.check(c.ensureSession().isOk(), "Trust on First Use connects under BCJSSE");
        }
        T.done("BCJSSE: Trust on First Use connects");
    }

    /** What the provider throws at a plain HttpsURLConnection using the pinned factory. */
    private static Throwable rawFailure(FakeVc vc, String pin) throws Exception {
        javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection)
                new java.net.URL("https://localhost:" + vc.port() + "/sdk").openConnection();
        TlsAndSessionTest.pinned(pin).secure(conn);
        try {
            conn.getResponseCode();
            return null;
        } catch (java.io.IOException e) {
            return e;
        } finally {
            conn.disconnect();
        }
    }

    private static String describeChain(Throwable t) {
        if (t == null) return "(no failure)";
        StringBuilder b = new StringBuilder();
        for (int i = 0; t != null && i < 6; i++, t = t.getCause()) {
            if (i > 0) b.append(" <- ");
            b.append(t.getClass().getName());
        }
        return b.toString();
    }
}
