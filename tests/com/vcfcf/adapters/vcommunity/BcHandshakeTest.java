package com.vcfcf.adapters.vcommunity;

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
 * shape that hid the certificate message in the 0.0.0.2 live run. The adapter's own
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
            verifiedUntrusted(vc);
            insecure(vc);
        }
        nameMismatch();
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

    static void verifiedUntrusted(FakeVc vc) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        VcenterTls tls = VcenterTls.create(false, ctx, (conn, factory) -> conn.setSSLSocketFactory(factory));
        VimResult<About> r0 = null;
        int before = vc.requests.size();
        try (VimSoapClient c = TlsAndSessionTest.client(vc, tls, "svc", "pw", new T.RecLog())) {
            VimResult<About> r = c.ensureSession();
            r0 = r;
            T.check(!r.isOk(), "an untrusted certificate fails under BCJSSE");
            T.contains(r.message(), "not trusted by this collector", "the collect path says so: " + r.message());
            T.notContains(r.message(), "TlsFatalAlert", "no alert text");
        }
        T.eq(before, vc.requests.size(), "nothing reached the server");
        T.contains(r0.message(), "Validate Connection", "and says what to do");
        T.done("BCJSSE: an untrusted chain on a verified connection is said plainly");
    }

    static void insecure(FakeVc vc) throws Exception {
        try (VimSoapClient c = TlsAndSessionTest.client(vc, TlsAndSessionTest.insecure(), "svc", "pw", new T.RecLog())) {
            T.check(c.ensureSession().isOk(), "Allow Insecure SSL connects under BCJSSE");
        }
        T.done("BCJSSE: Allow Insecure SSL connects");
    }

    /**
     * The FIPS provider's own failure shape for a certificate that is trusted but does not carry the name dialled:
     * the operator must still be told the name, not a bare alert. (Own server: the certificate names "localhost".)
     */
    static void nameMismatch() throws Exception {
        try (FakeVc vc = new FakeVc(FakeVc.happy(), "dns:localhost")) {
            VcenterTls tls = VcenterTls.create(false, vc.trustingContext(), TlsAndSessionTest.nameChecking());
            int before = vc.requests.size();
            try (VimSoapClient c = new VimSoapClient("127.0.0.1:" + vc.port(), "svc", "pw", tls, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "a name the certificate lacks fails under BCJSSE");
                T.contains(r.message(), "does not carry the name", "the sentence under the provider's own failure: " + r.message());
                T.contains(r.message(), "127.0.0.1", "names the address");
                T.notContains(r.message(), "TlsFatalAlert", "no alert text");
            }
            T.eq(before, vc.requests.size(), "nothing reached the server");
        }
        T.done("BCJSSE: a name mismatch is said plainly");
    }
}
