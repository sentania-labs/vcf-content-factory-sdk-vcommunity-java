package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.health.Reachability;
import com.vcfcf.adapters.vcommunity.tls.PinMismatchException;
import com.vcfcf.adapters.vcommunity.tls.PinnedTrustManager;
import com.vcfcf.adapters.vcommunity.tls.Thumbprints;
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
 * TLS failures read for what they say, whatever the provider wrapped around
 * them (finding 2 of the 0.0.0.2 live run: under FIPS the pin message was
 * lost). The FIPS provider's alert is an IOException that is not an
 * SSLException; this test builds that shape without the provider.
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

    public static void main(String[] args) throws Exception {
        shapes();
        loopsAndDepth();
        otherModes();
        throughTheClient();
        staleRecord();
    }

    /** N1: a mismatch recorded on this thread is never reported on a later unrelated failure. */
    static void staleRecord() throws Exception {
        try (FakeVc vc = new FakeVc(FakeVc.happy())) {
            PinnedTrustManager tm = new PinnedTrustManager("ab".repeat(32));
            // a mismatch is decided on this thread, outside any client call
            try {
                tm.checkServerTrusted(new X509Certificate[] {vc.cert}, "RSA");
            } catch (CertificateException expected) {
                // recorded
            }
            VcenterTls tls = VcenterTls.forTesting(TlsMode.PINNED,
                    new FailingFactory(() -> new java.net.ConnectException("refused")), tm);
            try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "pw", tls, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = c.ensureSession();
                T.notContains(r.message(), "pinned", "a stale record is not a pin mismatch: " + r.message());
                T.check(Reachability.isUnreachable(r.cause()), "it is the refusal it was");
            }
            // and a record the cause walk did not need is still taken, so it cannot outlive the call
            PinnedTrustManager tm2 = new PinnedTrustManager("ab".repeat(32));
            VcenterTls tls2 = VcenterTls.forTesting(TlsMode.PINNED, new FailingFactory(
                    () -> new SSLHandshakeException("x") {
                        private static final long serialVersionUID = 1L;
                        { initCause(pin()); }
                    }, () -> {
                        try {
                            tm2.checkServerTrusted(new X509Certificate[] {vc.cert}, "RSA");
                        } catch (CertificateException expected) {
                            // recorded
                        }
                    }), tm2);
            try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "pw", tls2, new T.RecLog(), 5000, 10000, 10000)) {
                T.check(!c.ensureSession().isOk(), "fails");
            }
            T.check(tls2.takePinMismatch() == null, "the record did not outlive the call");
            T.throwsIae(() -> VcenterTls.forTesting(TlsMode.ENABLED, new FailingFactory(() -> null), null),
                    "Enabled", "the test seam refuses Enabled");
        }
        T.done("a pin mismatch record never outlives its call nor colours a later failure");
    }

    static void shapes() {
        PinMismatchException pm = pin();
        // JDK shape: SSLHandshakeException with the pin as its cause
        Throwable jdk = new SSLHandshakeException("PKIX path validation failed").initCause(pm);
        T.check(TlsFailure.findPin(jdk) == pm, "JDK shape");
        // FIPS shape: an alert that is no SSLException, the pin two causes down
        Throwable fips = new TlsFatalAlert("certificate_unknown(46)",
                new CertificateException("certificate rejected", pm));
        T.check(!(fips instanceof SSLException), "the FIPS shape is not an SSLException");
        T.check(TlsFailure.findPin(fips) == pm, "FIPS shape, by cause");
        T.eq(pm.getMessage(), TlsFailure.explain(fips, TlsMode.PINNED), "the pin text, whole");
        // the pin only among the suppressed exceptions
        Throwable sup = new TlsFatalAlert("certificate_unknown(46)", null);
        sup.addSuppressed(new RuntimeException("closing", pm));
        T.check(TlsFailure.findPin(sup) == pm, "FIPS shape, by suppressed");
        // a bare alert says what it is, the mode, and invents nothing
        String bare = TlsFailure.explain(new TlsFatalAlert("handshake_failure(40)", null), TlsMode.TRUST_ON_FIRST_USE);
        T.contains(bare, "TlsFatalAlert: handshake_failure(40)", "names the alert");
        T.contains(bare, "Trust on First Use", "names the mode");
        T.notContains(bare, "No credential", "claims nothing it cannot know");
        // not TLS: nothing to say
        T.check(TlsFailure.explain(new java.net.ConnectException("refused"), TlsMode.PINNED) == null, "refused");
        T.check(TlsFailure.explain(new java.net.SocketTimeoutException("slow"), TlsMode.ENABLED) == null, "timeout");
        T.check(TlsFailure.explain(null, TlsMode.PINNED) == null, "null");
        T.done("a pin mismatch is found under any wrapping, causes and suppressed alike");
    }

    static void loopsAndDepth() {
        // a cause loop does not hang the walk
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.addSuppressed(b);
        T.check(TlsFailure.findPin(a) == null, "a suppressed loop ends");
        // a very deep chain is bounded, and a pin beyond the bound is not claimed
        Throwable deep = pin();
        for (int i = 0; i < 200; i++) deep = new IOException("w" + i, deep);
        T.check(TlsFailure.findPin(deep) == null, "bounded walk");
        Throwable ok = pin();
        for (int i = 0; i < 20; i++) ok = new IOException("w" + i, ok);
        T.check(TlsFailure.findPin(ok) != null, "twenty wrappers are fine");
        T.done("the walk is cycle safe and bounded");
    }

    static void otherModes() {
        // Enabled: an untrusted chain is said in words the admin can act on
        Throwable pkix = new SSLHandshakeException("PKIX path building failed").initCause(
                new java.security.cert.CertPathBuilderException("unable to find valid certification path"));
        String enabled = TlsFailure.explain(pkix, TlsMode.ENABLED);
        T.contains(enabled, "not trusted by this collector", "Enabled, untrusted");
        T.contains(enabled, "Validate Connection", "Enabled, what to do");
        // the same shape wrapped as the FIPS alert
        T.contains(TlsFailure.explain(new TlsFatalAlert("certificate_unknown(46)", pkix), TlsMode.ENABLED),
                "not trusted by this collector", "Enabled, untrusted, FIPS shape");
        // N2: an expired or revoked certificate does not get the 'accept it' advice
        Throwable expired = new SSLHandshakeException("validity").initCause(
                new java.security.cert.CertPathValidatorException("validity check failed", null, null, -1,
                        java.security.cert.CertPathValidatorException.BasicReason.EXPIRED));
        String ex = TlsFailure.explain(expired, TlsMode.ENABLED);
        T.contains(ex, "expired, not yet valid or revoked", "expired");
        T.notContains(ex, "Accept the certificate", "no accept advice for an expired certificate");
        T.contains(TlsFailure.explain(new SSLHandshakeException("x").initCause(
                new java.security.cert.CertificateExpiredException("NotAfter: x")), TlsMode.ENABLED),
                "expired, not yet valid or revoked", "CertificateExpiredException");
        T.contains(TlsFailure.explain(new TlsFatalAlert("certificate_expired(45)", new java.security.cert.CertPathValidatorException(
                "revoked", null, null, -1, java.security.cert.CertPathValidatorException.BasicReason.REVOKED)), TlsMode.ENABLED),
                "expired, not yet valid or revoked", "revoked, FIPS shape");
        // N3: server text stays one line
        String nl = TlsFailure.explain(new TlsFatalAlert("bad\nthing\r\n" + "x".repeat(400), null), TlsMode.PINNED);
        T.notContains(nl, "\n", "no newline"); T.notContains(nl, "\r", "no carriage return");
        T.check(nl.length() < 400, "bounded: " + nl.length());
        // outside Enabled an untrusted chain cannot happen; it is not described as one
        T.notContains(TlsFailure.explain(pkix, TlsMode.PINNED), "not trusted by this collector", "Pinned");
        // an Enabled failure that is not a chain failure (a name mismatch the framework checks) is a plain TLS line
        T.contains(TlsFailure.explain(new TlsFatalAlert("certificate_unknown(46)", null), TlsMode.ENABLED),
                "TlsFatalAlert: certificate_unknown(46)", "Enabled, other alert");
        T.done("Enabled and the other modes say what failed");
    }

    /** The client end to end: the message the caller gets, in each wrapping. */
    static void throughTheClient() throws Exception {
        try (FakeVc vc = new FakeVc(FakeVc.happy())) {
            Supplier<IOException>[] shapes = shapesToTry();
            String[] names = {"JDK cause chain", "FIPS alert, cause chain", "FIPS alert, suppressed", "FIPS alert, nothing in the chain"};
            for (int i = 0; i < shapes.length; i++) {
                PinnedTrustManager tm = new PinnedTrustManager("ab".repeat(32));
                final X509Certificate presented = vc.cert;
                // the last shape hides the exception entirely: only the trust manager knows
                final boolean hidden = i == 3;
                Runnable handshake = !hidden ? () -> { } : () -> {
                    try {
                        tm.checkServerTrusted(new X509Certificate[] {presented}, "RSA");
                    } catch (CertificateException expected) {
                        // the provider swallowed it
                    }
                };
                VcenterTls tls = VcenterTls.forTesting(TlsMode.PINNED, new FailingFactory(shapes[i], handshake), tm);
                String want = hidden ? new PinMismatchException(Thumbprints.of(vc.cert, 256), 256).getMessage()
                        : pin().getMessage();
                int before = vc.requests.size();
                T.RecLog log = new T.RecLog();
                try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "very-secret-pw", tls, log, 5000, 10000, 10000)) {
                    VimResult<About> r = c.ensureSession();
                    T.check(!r.isOk(), names[i] + ": fails");
                    T.eq(want, r.message(), names[i] + ": the caller gets the pin text, nothing else");
                    T.check(!Reachability.isUnreachable(r.cause()), names[i] + ": not 'unreachable'");
                    T.notContains(log.all(), "very-secret-pw", names[i] + ": log");
                }
                T.eq(before, vc.requests.size(), names[i] + ": nothing reached the server");
            }
            // an alert that is not a pin mismatch: the caller gets the alert, the mode, no invented text
            VcenterTls other = VcenterTls.forTesting(TlsMode.TRUST_ON_FIRST_USE,
                    new FailingFactory(() -> new TlsFatalAlert("handshake_failure(40)", null)), null);
            try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "pw", other, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = c.ensureSession();
                T.check(!r.isOk(), "alert fails");
                T.contains(r.message(), "TlsFatalAlert: handshake_failure(40)", "alert named");
                T.contains(r.message(), "Trust on First Use", "mode named");
                T.notContains(r.message(), "reading the service content failed", "no step prefix on a TLS failure");
            }
            // a failure that is no TLS failure keeps its step prefix
            VcenterTls plain = VcenterTls.forTesting(TlsMode.TRUST_ON_FIRST_USE,
                    new FailingFactory(() -> new java.net.ConnectException("refused")), null);
            try (VimSoapClient c = new VimSoapClient("localhost:" + vc.port(), "svc", "pw", plain, new T.RecLog(), 5000, 10000, 10000)) {
                VimResult<About> r = c.ensureSession();
                T.contains(r.message(), "reading the service content failed", "step prefix");
                T.check(Reachability.isUnreachable(r.cause()), "still unreachable");
            }
            // the real thing, on this JDK: a wrong pin against a real handshake gives the same text
            String wrong = "00" + Thumbprints.of(vc.cert, 256).substring(2);
            try (VimSoapClient c = TlsAndSessionTest.client(vc, TlsAndSessionTest.pinned(wrong), "svc", "pw", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.check(r.message().startsWith("vCenter presented a certificate other than the pinned one"), "real handshake: " + r.message());
            }
            // the Enabled mode on a real handshake against a certificate the platform does not trust
            VcenterTls enabled = VcenterTls.create(TlsMode.ENABLED, "", javax.net.ssl.SSLContext.getDefault(),
                    (conn, factory) -> conn.setSSLSocketFactory(factory));
            try (VimSoapClient c = TlsAndSessionTest.client(vc, enabled, "svc", "pw", new T.RecLog())) {
                VimResult<About> r = c.ensureSession();
                T.contains(r.message(), "not trusted by this collector", "collect path text for an untrusted chain");
                T.notContains(r.message(), "PKIX", "no provider text");
            }
        }
        T.done("through the client: the pin text reaches the caller in every wrapping, and the other modes speak plainly");
    }

    @SuppressWarnings("unchecked")
    static Supplier<IOException>[] shapesToTry() {
        Supplier<IOException>[] s = new Supplier[4];
        s[0] = () -> {
            SSLHandshakeException e = new SSLHandshakeException("handshake failed");
            e.initCause(pin());
            return e;
        };
        s[1] = () -> new TlsFatalAlert("certificate_unknown(46)", new CertificateException("rejected", pin()));
        s[2] = () -> {
            TlsFatalAlert e = new TlsFatalAlert("certificate_unknown(46)", null);
            e.addSuppressed(new RuntimeException("while closing", pin()));
            return e;
        };
        // The provider hid the exception completely; only the trust manager knows what it decided.
        // The trust manager is asked first, as a handshake would: the factory below does it.
        s[3] = () -> new TlsFatalAlert("certificate_unknown(46)", null);
        return s;
    }
}
