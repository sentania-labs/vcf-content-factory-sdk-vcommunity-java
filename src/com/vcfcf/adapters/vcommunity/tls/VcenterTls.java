package com.vcfcf.adapters.vcommunity.tls;

import java.security.GeneralSecurityException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * The TLS posture of every connection to vCenter (SOAP, REST), built once per account (design 2.5).
 * SDK-free: the platform trust context and the framework's name check are handed in by the adapter.
 *
 * <ul>
 *   <li><b>Verified</b> (the default): the platform trust (the collector's trust, including what the
 *       admin accepted in Validate Connection) and, through {@link Securer}, the framework's
 *       certificate name check. A certificate that is not trusted, or does not carry the name the
 *       account dials, fails the handshake before any credential is sent.</li>
 *   <li><b>Insecure</b> (the account's Allow Insecure SSL is true): any certificate and any name are
 *       accepted, and the credentials go to whatever answers. An explicit opt-out for a lab.</li>
 * </ul>
 */
public final class VcenterTls {

    /** Applies the name check to one connection (the framework's {@code CertificateNames.secure}). */
    public interface Securer {
        void secure(HttpsURLConnection conn, SSLSocketFactory factory);
    }

    private final boolean insecure;
    private final SSLSocketFactory factory;
    private final Securer securer;

    private VcenterTls(boolean insecure, SSLSocketFactory factory, Securer securer) {
        this.insecure = insecure;
        this.factory = factory;
        this.securer = securer;
    }

    /**
     * @param allowInsecure the account's Allow Insecure SSL
     * @param platformCtx   the name-checking platform context, required when verified
     * @param securer       the name check for an {@link HttpsURLConnection}, required when verified
     */
    public static VcenterTls create(boolean allowInsecure, SSLContext platformCtx, Securer securer) {
        if (allowInsecure) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[] {new TrustAllManager()}, null);
                return new VcenterTls(true, ctx.getSocketFactory(), null);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("could not build the TLS context: "
                        + e.getClass().getSimpleName(), e);
            }
        }
        if (platformCtx == null || securer == null) {
            throw new IllegalArgumentException("a verified connection needs the platform trust context and its name check");
        }
        return new VcenterTls(false, platformCtx.getSocketFactory(), securer);
    }

    /**
     * A verified posture over a socket factory the caller built, for tests that need a provider's
     * failure shape the JDK cannot produce (the FIPS provider's alerts). No name check.
     */
    public static VcenterTls forTesting(boolean allowInsecure, SSLSocketFactory factory) {
        return new VcenterTls(allowInsecure, factory, null);
    }

    /** True when the account turned verification off. */
    public boolean insecure() {
        return insecure;
    }

    public SSLSocketFactory socketFactory() {
        return factory;
    }

    /** Whether the certificate's name is checked at all (when verified). */
    public boolean checksName() {
        return !insecure && securer != null;
    }

    /**
     * Wire one connection: this posture's socket factory, and either the framework name check
     * (verified) or a verifier that accepts every name (insecure).
     */
    public void secure(HttpsURLConnection conn) {
        if (!insecure && securer != null) {
            securer.secure(conn, factory);
            return;
        }
        conn.setSSLSocketFactory(factory);
        conn.setHostnameVerifier(ACCEPT_ANY_NAME);
    }

    private static final HostnameVerifier ACCEPT_ANY_NAME = (host, session) -> true;
}
