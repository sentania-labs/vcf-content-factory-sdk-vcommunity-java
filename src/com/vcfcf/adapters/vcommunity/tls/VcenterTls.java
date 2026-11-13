package com.vcfcf.adapters.vcommunity.tls;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;

import java.security.GeneralSecurityException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * The TLS posture of every connection to vCenter (SOAP, REST, probe), built
 * once per account from its mode (design 2.5). SDK-free: the platform trust
 * context and the framework's name check are handed in by the adapter.
 *
 * <ul>
 *   <li><b>Trust on First Use</b>: any certificate is accepted and any name;
 *       the credentials are sent before anything is checked (a documented
 *       weakness of this mode, shared with the original pack).</li>
 *   <li><b>Enabled</b>: the platform trust (the collector's trust, including
 *       what the admin accepted in Validate Connection) and, through
 *       {@link Securer}, the framework's certificate name check.</li>
 *   <li><b>Pinned</b>: {@link PinnedTrustManager}; chain and name ignored.</li>
 * </ul>
 */
public final class VcenterTls {

    /** Applies the name check to one connection (the framework's {@code CertificateNames.secure}). */
    public interface Securer {
        void secure(HttpsURLConnection conn, SSLSocketFactory factory);
    }

    private final TlsMode mode;
    private final SSLSocketFactory factory;
    private final Securer securer;
    /** Only in the Pinned mode. */
    private final PinnedTrustManager pinned;

    private VcenterTls(TlsMode mode, SSLSocketFactory factory, Securer securer,
            PinnedTrustManager pinned) {
        this.mode = mode;
        this.factory = factory;
        this.securer = securer;
        this.pinned = pinned;
    }

    /**
     * @param mode        the account's mode
     * @param pinHex      the normalised pin, required when the mode is Pinned
     * @param platformCtx the name-checking platform context, required when the mode is Enabled
     * @param securer     the name check for an {@link HttpsURLConnection}, required when Enabled
     */
    public static VcenterTls create(TlsMode mode, String pinHex, SSLContext platformCtx,
            Securer securer) {
        try {
            switch (mode) {
                case TRUST_ON_FIRST_USE:
                    return new VcenterTls(mode, contextWith(new TrustAllManager()), null, null);
                case PINNED:
                    PinnedTrustManager pm = new PinnedTrustManager(pinHex);
                    return new VcenterTls(mode, contextWith(pm), null, pm);
                case ENABLED:
                    if (platformCtx == null || securer == null) {
                        throw new IllegalArgumentException(
                                "Enabled needs the platform trust context and its name check");
                    }
                    return new VcenterTls(mode, platformCtx.getSocketFactory(), securer, null);
                default:
                    throw new IllegalArgumentException("unknown TLS mode " + mode);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not build the TLS context: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * A posture over a socket factory the caller built, for tests that need a
     * provider's failure shape the JDK cannot produce (the FIPS provider's alerts).
     * No name check; {@code pinned} may be null.
     */
    public static VcenterTls forTesting(TlsMode mode, SSLSocketFactory factory,
            PinnedTrustManager pinned) {
        return new VcenterTls(mode, factory, null, pinned);
    }

    private static SSLSocketFactory contextWith(TrustManager tm) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, null);
        return ctx.getSocketFactory();
    }

    public TlsMode mode() {
        return mode;
    }

    public SSLSocketFactory socketFactory() {
        return factory;
    }

    /** Forget a pin mismatch recorded on this thread; call before a connection. */
    public void clearPinMismatch() {
        if (pinned != null) pinned.clearMismatch();
    }

    /** The pin mismatch this thread's last handshake raised, or null (also in the other modes). */
    public PinMismatchException takePinMismatch() {
        return pinned == null ? null : pinned.takeMismatch();
    }

    /** Whether the certificate's name is checked at all (only Enabled does). */
    public boolean checksName() {
        return mode == TlsMode.ENABLED;
    }

    /**
     * Wire one connection: this posture's socket factory, and either the
     * framework name check (Enabled) or a verifier that accepts every name
     * (the two modes whose identity is not the name).
     */
    public void secure(HttpsURLConnection conn) {
        if (mode == TlsMode.ENABLED) {
            securer.secure(conn, factory);
            return;
        }
        conn.setSSLSocketFactory(factory);
        conn.setHostnameVerifier(ACCEPT_ANY_NAME);
    }

    private static final HostnameVerifier ACCEPT_ANY_NAME = (host, session) -> true;
}
