package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.health.Reachability;
import com.vcfcf.adapters.vcommunity.tls.PinMismatchException;
import com.vcfcf.adapters.vcommunity.tls.TlsFailure;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS failures to the vCenter read for what they say, whatever the provider wrapped around them. The FIPS
 * provider's alert is an IOException that is not an SSLException; this test builds that shape without the
 * provider. A pin mismatch is only the ESXi host channel of the guest phase now ({@link TlsFailure#findPin}).
 */
public final class TlsFailureTest {

    /** Named like the BouncyCastle alert on purpose: the code matches the name, not the class. */
    static final class TlsFatalAlert extends IOException {
        private static final long serialVersionUID = 1L;

        TlsFatalAlert(String m, Throwable cause) {
            super(m, cause);
        }
    }

    /** A socket factory whose every socket request fails the way a provider fails a handshake. */
    static final class FailingFactory extends SSLSocketFactory {
        private final Supplier<IOException> shape;
        private final Runnable before;

        FailingFactory(Supplier<IOException> shape) {
            this(shape, () -> { });
        }

        /** @param before what the handshake would have run first (the trust manager's decision) */
        FailingFactory(Supplier<IOException> shape, Runnable before) {
            this.shape = shape;
            this.before = before;
        }

        private IOException failure() {
            before.run();
            return shape.get();
        }

        @Override public String[] getDefaultCipherSuites() { return new String[0]; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public Socket createSocket() throws IOException { throw failure(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { throw failure(); }
        @Override public Socket createSocket(String h, int p) throws IOException { throw failure(); }
        @Override public Socket createSocket(String h, int p, InetAddress a, int lp) throws IOException { throw failure(); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { throw failure(); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress a, int lp) throws IOException { throw failure(); }
    }

    static PinMismatchException pin() {
        return new PinMismatchException("ab".repeat(32), 256);
    }

    static Throwable untrustedChain() {
        return new SSLHandshakeException("PKIX path building failed").initCause(
                new java.security.cert.CertPathBuilderException("unable to find valid certification path"));
    }

    static Throwable nameMismatch(String host) {
        return new SSLHandshakeException("No subject alternative names matching IP address " + host + " found")
                .initCause(new CertificateException("No subject alternative names matching IP address " + host + " found"));
    }

    public static void main(String[] args) throws Exception {
        pinShapes();
        loopsAndDepth();
        explained();
        throughTheClient();
    }

    /** The guest phase's ESXi channel still finds a pin mismatch under any wrapping. */
    static void pinShapes() {
        PinMismatchException pm = pin();
        Throwable jdk = new SSLHandshakeException("PKIX path validation failed").initCause(pm);
        T.check(TlsFailure.findPin(jdk) == pm, "JDK shape");
        Throwable fips = new TlsFatalAlert("certificate_unknown(46)", new CertificateException("certificate rejected", pm));
        T.check(!(fips instanceof SSLException), "the FIPS shape is not an SSLException");
        T.check(TlsFailure.findPin(fips) == pm, "FIPS shape, by cause");
        Throwable sup = new TlsFatalAlert("certificate_unknown(46)", null);
        sup.addSuppressed(new RuntimeException("closing", pm));
        T.check(TlsFailure.findPin(sup) == pm, "FIPS shape, by suppressed");
        T.done("a pin mismatch (ESXi channel) is found under any wrapping, causes and suppressed alike");
    }

    static void loopsAndDepth() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.addSuppressed(b);
        T.check(TlsFailure.findPin(a) == null, "a suppressed loop ends");
        Throwable deep = pin();
        for (int i = 0; i < 200; i++) deep = new IOException("w" + i, deep);
        T.check(TlsFailure.findPin(deep) == null, "bounded walk");
        Throwable ok = pin();
        for (int i = 0; i < 20; i++) ok = new IOException("w" + i, ok);
        T.check(TlsFailure.findPin(ok) != null, "twenty wrappers are fine");
        T.done("the walk is cycle safe and bounded");
    }

    /** The sentences for the vCenter connection: verified, and with Allow Insecure SSL on. */
    static void explained() {
        // an untrusted chain, in words the admin can act on
        String untrusted = TlsFailure.explain(untrustedChain(), false);
        T.contains(untrusted, "not trusted by this collector", "untrusted");
        T.contains(untrusted, "Validate Connection", "what to do");
        T.contains(untrusted, "Allow Insecure SSL", "the opt-out is named");
        T.contains(untrusted, "No credential was sent", "and nothing was sent");
        T.notContains(untrusted, "Pinned", "no removed mode");
        T.contains(TlsFailure.explain(new TlsFatalAlert("certificate_unknown(46)", untrustedChain()), false),
                "not trusted by this collector", "untrusted, FIPS shape");
        // a name the certificate does not carry: the name, and that accepting does not help
        String name = TlsFailure.explain(nameMismatch("10.1.2.3"), false);
        T.contains(name, "does not carry the name '10.1.2.3'", "names what was dialled");
        T.contains(name, "Enter the vCenter by a name that is on its certificate", "what to do");
        T.contains(name, "does not change a name mismatch", "accepting does not help");
        T.notContains(name, "not trusted by this collector", "a name mismatch is not an untrusted chain");
        T.contains(TlsFailure.explain(new TlsFatalAlert("certificate_unknown(46)", nameMismatch("vc.lab")), false),
                "does not carry the name 'vc.lab'", "name mismatch, FIPS shape");
        // the framework's own text for a name the certificate lacks, wrapped as the FIPS provider wraps it
        T.contains(TlsFailure.explain(new TlsFatalAlert("certificate_unknown(46)", new CertificateException(
                "No subject alternative name matching vc.lab in the target certificate")), false),
                "does not carry the name 'vc.lab'", "the framework's name text, FIPS shape");
        // 8.18.7: the platform trust manager with nothing accepted has an empty anchor set (W2)
        Throwable anchors = new SSLHandshakeException("the trustAnchors parameter must be non-empty").initCause(
                new java.security.InvalidAlgorithmParameterException("the trustAnchors parameter must be non-empty"));
        T.contains(TlsFailure.explain(anchors, false), "not trusted by this collector", "8.18.7 empty anchors");
        T.contains(TlsFailure.explain(new SSLHandshakeException("java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty"), false),
                "not trusted by this collector", "8.18.7 text only");
        T.contains(TlsFailure.explain(new IOException("HTTPS hostname wrong:  should be <vc1.lab.test>"), false),
                "does not carry the name 'vc1.lab.test'", "the post-handshake rejection of the JDK");
        // an expired or revoked certificate does not get the 'accept it' advice
        Throwable expired = new SSLHandshakeException("validity").initCause(
                new java.security.cert.CertPathValidatorException("validity check failed", null, null, -1,
                        java.security.cert.CertPathValidatorException.BasicReason.EXPIRED));
        String ex = TlsFailure.explain(expired, false);
        T.contains(ex, "expired, not yet valid or revoked", "expired");
        T.notContains(ex, "accept it", "no accept advice for an expired certificate");
        T.contains(TlsFailure.explain(new SSLHandshakeException("x").initCause(
                new java.security.cert.CertificateExpiredException("NotAfter: x")), false),
                "expired, not yet valid or revoked", "CertificateExpiredException");
        // any other alert is named, with the checking state, and invents nothing
        String bare = TlsFailure.explain(new TlsFatalAlert("handshake_failure(40)", null), false);
        T.contains(bare, "TlsFatalAlert: handshake_failure(40)", "names the alert");
        T.contains(bare, "certificate checking is on", "says verification is on");
        T.notContains(bare, "No credential", "claims nothing it cannot know");
        // server text stays one line
        String nl = TlsFailure.explain(new TlsFatalAlert("bad\nthing\r\n" + "x".repeat(400), null), true);
        T.notContains(nl, "\n", "no newline");
        T.notContains(nl, "\r", "no carriage return");
        T.check(nl.length() < 400, "bounded: " + nl.length());
        // with Allow Insecure SSL nothing is checked, so a chain or a name failure cannot happen and is not described as one
        T.notContains(TlsFailure.explain(untrustedChain(), true), "not trusted by this collector", "insecure, chain");
        T.contains(TlsFailure.explain(new TlsFatalAlert("handshake_failure(40)", null), true),
                "certificate checking is off (Allow Insecure SSL)", "insecure says so");
        // not TLS: nothing to say
        T.check(TlsFailure.explain(new java.net.ConnectException("refused"), false) == null, "refused");
        T.check(TlsFailure.explain(new java.net.SocketTimeoutException("slow"), false) == null, "timeout");
        T.check(TlsFailure.explain(null, false) == null, "null");
        advice();
        T.done("untrusted, name mismatch, expired and other failures each say what they are");
    }

    /** What Validate Connection adds after VCF Operations' own certificate message. */
    static void advice() {
        String base = "VCF Operations says: certificate problem.";
        String u = TlsFailure.certificateAdvice(untrustedChain(), base, false);
        T.check(u.startsWith(base), "the platform's text comes first");
        T.contains(u, "Review the vCenter certificate here and accept it", "an untrusted chain is answered by accepting it");
        T.eq(base, TlsFailure.certificateAdvice(nameMismatch("10.1.2.3"), base, false), "a name mismatch: the framework's text is enough");
        T.eq(base, TlsFailure.certificateAdvice(untrustedChain(), base, true), "no usable host: unchanged");
        Throwable expired = new SSLHandshakeException("validity").initCause(new java.security.cert.CertificateExpiredException("NotAfter"));
        String e = TlsFailure.certificateAdvice(expired, base, false);
        T.contains(e, "renew it on the vCenter", "expired is renewed");
        T.notContains(e, "accept it", "and not accepted");
        T.eq(base, TlsFailure.certificateAdvice(new java.net.ConnectException("refused"), base, false), "not a certificate failure: unchanged");
        T.contains(TlsFailure.certificateAdvice(new SSLHandshakeException("the trustAnchors parameter must be non-empty"), base, false),
                "accept it", "8.18.7 empty anchors are answered by accepting");
    }

    /** The client end to end: the message the caller gets, in each wrapping. */
    static void throughTheClient() throws Exception {
        try (FakeVc vc = new FakeVc(FakeVc.happy())) {
            Supplier<IOException>[] shapes = shapesToTry();
            String[] names = {"JDK untrusted", "FIPS alert, untrusted", "FIPS alert, name mismatch", "JDK name mismatch"};
            String[] want = {"not trusted by this collector", "not trusted by this collector",
                "does not carry the name '127.0.0.1'", "does not carry the name '127.0.0.1'"};
            for (int i = 0; i < shapes.length; i++) {
                VcenterTls tls = failing(shapes[i]);
                int before = vc.requests.size();
                T.RecLog log = new T.RecLog();
                try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "very-secret-pw", tls, log, 5000, 10000, 10000)) {
                    VimResult<About> r = c.ensureSession();
                    T.check(!r.isOk(), names[i] + ": fails");
                    T.contains(r.message(), want[i], names[i] + ": the caller gets the sentence");
                    T.notContains(r.message(), "reading the service content failed", names[i] + ": no step prefix on a TLS failure");
                    T.check(!Reachability.isUnreachable(r.cause()), names[i] + ": not 'unreachable'");
                    T.notContains(log.all(), "very-secret-pw", names[i] + ": log");
                }
                T.eq(before, vc.requests.size(), names[i] + ": nothing reached the server");
            }
            // an alert that is no certificate failure: the caller gets the alert and the checking state
            VcenterTls other = failing(() -> new TlsFatalAlert("handshake_failure(40)", null));
            try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "pw", other, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "alert fails");
                T.contains(r.message(), "TlsFatalAlert: handshake_failure(40)", "alert named");
                T.contains(r.message(), "certificate checking is on", "state named");
                T.notContains(r.message(), "reading the service content failed", "no step prefix on a TLS failure");
            }
            // a failure that is no TLS failure keeps its step prefix
            VcenterTls plain = failing(() -> new java.net.ConnectException("refused"));
            try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "pw", plain, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = c.ensureSession();
                T.contains(r.message(), "reading the service content failed", "step prefix");
                T.check(Reachability.isUnreachable(r.cause()), "still unreachable");
            }
            // the real thing, on this JDK: a certificate the platform does not trust
            VcenterTls verified = VcenterTls.create(false, javax.net.ssl.SSLContext.getDefault(),
                    (conn, factory) -> conn.setSSLSocketFactory(factory));
            try (VimSoapClient c = TlsAndSessionTest.client(vc, verified, "svc", "pw", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.contains(r.message(), "not trusted by this collector", "collect path text for an untrusted chain");
                T.notContains(r.message(), "PKIX", "no provider text");
            }
        }
        T.done("through the client: each certificate failure reaches the caller as a sentence, in every wrapping");
    }

    static javax.net.ssl.SSLContext platform() {
        try {
            return javax.net.ssl.SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A verified posture whose every socket fails the way a provider fails a handshake (the name check is the caller's). */
    static VcenterTls failing(Supplier<IOException> shape) {
        return VcenterTls.create(false, platform(), (conn, factory) ->
                conn.setSSLSocketFactory(new FailingFactory(shape)));
    }

    @SuppressWarnings("unchecked")
    static Supplier<IOException>[] shapesToTry() {
        Supplier<IOException>[] s = new Supplier[4];
        s[0] = () -> (IOException) untrustedChain();
        s[1] = () -> new TlsFatalAlert("certificate_unknown(46)", untrustedChain());
        s[2] = () -> new TlsFatalAlert("certificate_unknown(46)", nameMismatch("127.0.0.1"));
        s[3] = () -> (IOException) nameMismatch("127.0.0.1");
        return s;
    }
}
