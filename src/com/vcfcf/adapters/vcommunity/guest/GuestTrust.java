package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.GuestTlsMode;
import com.vcfcf.adapters.vcommunity.tls.PinMismatchException;
import com.vcfcf.adapters.vcommunity.tls.PinnedTrustManager;
import com.vcfcf.adapters.vcommunity.tls.TlsFailure;
import com.vcfcf.adapters.vcommunity.tls.TrustAllManager;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * How a file transfer to or from a guest decides whether to trust the ESXi host (design 8.4, DV-95). The
 * identity of a host is the thumbprint vCenter recorded for it ({@code summary.config.sslThumbprint}, SHA-1
 * as 40 hex digits or SHA-256 as 64), checked by {@link PinnedTrustManager} during the handshake, before a
 * byte of content is sent. The three modes of the account:
 *
 * <ul>
 *   <li><b>Pinned</b> (the default) and <b>Strict</b>: the leaf certificate must have the recorded thumbprint.
 *       A host with no usable thumbprint is not connected to in either mode: the original carried on without a
 *       check in Pinned, which is the same as no check at all.</li>
 *   <li><b>Disabled</b>: any certificate is accepted. This is the administrator's explicit choice, and it is
 *       logged once a cycle.</li>
 * </ul>
 *
 * No name check in any mode: the thumbprint is the identity. Nothing here is ever a trust-all client unless
 * the account says Disabled.
 */
public final class GuestTrust {

    /** The connection posture for one host: a socket factory, or the reason there is none. */
    public static final class Channel {
        private final SSLSocketFactory factory;
        private final PinnedTrustManager pinned;
        private final String refusal;

        private Channel(SSLSocketFactory factory, PinnedTrustManager pinned, String refusal) {
            this.factory = factory;
            this.pinned = pinned;
            this.refusal = refusal;
        }

        /** Null when the host may not be connected to at all; see {@link #refusal()}. */
        public SSLSocketFactory socketFactory() {
            return factory;
        }

        /** Why there is no connection (credential-free), or null. */
        public String refusal() {
            return refusal;
        }

        public void clearMismatch() {
            if (pinned != null) pinned.clearMismatch();
        }

        /** What went wrong with the host's certificate in the failure {@code t}, or null when nothing did. */
        public String explain(Throwable t) {
            PinMismatchException pin = TlsFailure.findPin(t);
            PinMismatchException recorded = pinned == null ? null : pinned.takeMismatch();
            if (pin == null) pin = recorded;
            if (pin == null) return null;
            return "the ESXi host presented a certificate (thumbprint " + pin.presented()
                    + ") other than the one vCenter has recorded for it; nothing was sent";
        }
    }

    private final GuestTlsMode mode;
    private final Map<String, String> thumbprints;
    private final Map<String, Channel> channels = new HashMap<>();
    private SSLSocketFactory trustAll;

    /**
     * @param thumbprints host MoRef value to the thumbprint vCenter recorded for it as text (colons and case
     *        as vCenter gives it); a host that is missing or has none is refused in Pinned and Strict
     */
    public GuestTrust(GuestTlsMode mode, Map<String, String> thumbprints) {
        this.mode = mode;
        this.thumbprints = new HashMap<>(thumbprints);
    }

    public GuestTlsMode mode() {
        return mode;
    }

    /** The recorded thumbprint as 40, 64 or 128 lowercase hex digits; null when it is none of those. */
    static String normalise(String text) {
        if (text == null) return null;
        String t = text.replace(":", "").replace("-", "").replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        if (!t.matches("[0-9a-f]{40}|[0-9a-f]{64}|[0-9a-f]{128}")) return null;
        return t;
    }

    /** The posture for the ESXi host with this MoRef value. */
    public synchronized Channel forHost(String hostMoref) {
        if (mode == GuestTlsMode.DISABLED) {
            return new Channel(trustAll(), null, null);
        }
        String pin = hostMoref == null ? null : normalise(thumbprints.get(hostMoref));
        if (pin == null) {
            return new Channel(null, null, "vCenter records no usable certificate thumbprint for the ESXi host "
                    + "this VM runs on, so the file transfer is not made (Guest File Transfer TLS Validation: "
                    + mode.label + ")");
        }
        Channel c = channels.get(pin);
        if (c == null) {
            try {
                PinnedTrustManager tm = new PinnedTrustManager(pin);
                c = new Channel(contextWith(tm), tm, null);
            } catch (GeneralSecurityException | IllegalStateException e) {
                c = new Channel(null, null, "the certificate check for the ESXi host could not be set up ("
                        + e.getClass().getSimpleName() + ")");
            }
            channels.put(pin, c);
        }
        return c;
    }

    private SSLSocketFactory trustAll() {
        if (trustAll == null) {
            try {
                trustAll = contextWith(new TrustAllManager());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("could not build the TLS context", e);
            }
        }
        return trustAll;
    }

    private static SSLSocketFactory contextWith(TrustManager tm) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {tm}, null);
        return ctx.getSocketFactory();
    }
}
