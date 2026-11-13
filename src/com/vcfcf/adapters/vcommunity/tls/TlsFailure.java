package com.vcfcf.adapters.vcommunity.tls;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.health.Failures;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Reads a TLS failure for what it says, whatever the provider wrapped around
 * it. The JDK's JSSE hands our trust manager's exception up as a cause of an
 * {@code SSLHandshakeException}; the FIPS provider (BouncyCastle) throws its
 * own {@code TlsFatalAlert}, an {@code IOException} that is not an
 * {@code SSLException}, with the real reason several causes down or among the
 * suppressed. Every walk here follows both, breadth first, bounded, and safe
 * against a cycle.
 */
public final class TlsFailure {

    private static final int MAX_NODES = 64;

    private TlsFailure() {}

    /** The {@link PinMismatchException} in {@code t}'s causes or suppressed exceptions, or null. */
    public static PinMismatchException findPin(Throwable t) {
        Throwable hit = find(t, 0);
        return (PinMismatchException) hit;
    }

    /**
     * One credential-free sentence for a TLS failure, or null when {@code t} is
     * not one (a refused connection, a timeout, a fault). A pin mismatch gives
     * the pin message as it is; an untrusted chain in the Enabled mode says so;
     * any other TLS failure names the alert and the mode.
     */
    public static String explain(Throwable t, TlsMode mode) {
        if (t == null) return null;
        PinMismatchException pin = findPin(t);
        if (pin != null) return pin.getMessage();
        if (mode == TlsMode.ENABLED && find(t, 3) != null) {
            return "the vCenter's certificate is expired, not yet valid or revoked (vCenter TLS "
                    + "Validation: Enabled). Renew the certificate on the vCenter, or use Pinned "
                    + "with its thumbprint. No credential was sent.";
        }
        if (mode == TlsMode.ENABLED && find(t, 1) != null) {
            return "the vCenter's certificate is not trusted by this collector (vCenter TLS "
                    + "Validation: Enabled). Accept the certificate in Validate Connection, or "
                    + "use Pinned with its thumbprint. No credential was sent.";
        }
        Throwable alert = find(t, 2);
        if (alert == null) return null;
        // one line: the alert text is the provider's, bounded and stripped of control characters
        String m = Failures.bound(alert.getMessage());
        return "the TLS connection to the vCenter failed (" + alert.getClass().getSimpleName()
                + (m.isEmpty() ? "" : ": " + m)
                + "); vCenter TLS Validation: " + (mode == null ? "unknown" : mode.label);
    }

    /**
     * Breadth first over causes and suppressed. kind 0: a pin mismatch; 1: an
     * untrusted chain; 3: an expired, not yet valid or revoked certificate; 2: any TLS handshake failure (an SSLException, or a
     * BouncyCastle alert, matched by name so no provider class is needed).
     */
    private static Throwable find(Throwable start, int kind) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> todo = new ArrayDeque<>();
        if (start != null) todo.add(start);
        while (!todo.isEmpty() && seen.size() < MAX_NODES) {
            Throwable cur = todo.poll();
            if (!seen.add(cur)) continue;
            switch (kind) {
                case 0:
                    if (cur instanceof PinMismatchException) return cur;
                    break;
                case 3:
                    if (cur instanceof java.security.cert.CertificateExpiredException
                            || cur instanceof java.security.cert.CertificateNotYetValidException
                            || (cur instanceof java.security.cert.CertPathValidatorException
                                    && isValidityReason(
                                            (java.security.cert.CertPathValidatorException) cur))
                            || mentions(cur, "validity check failed")) {
                        return cur;
                    }
                    break;
                case 1:
                    if (cur instanceof java.security.cert.CertPathBuilderException
                            || cur instanceof java.security.cert.CertPathValidatorException
                            || cur.getClass().getSimpleName().equals("CustomCertificateException")
                            || mentions(cur, "PKIX path building failed")
                            || mentions(cur, "unable to find valid certification path")) {
                        return cur;
                    }
                    break;
                default:
                    if (cur instanceof javax.net.ssl.SSLException
                            || cur.getClass().getSimpleName().startsWith("TlsFatalAlert")) {
                        return cur;
                    }
            }
            if (cur.getCause() != null) todo.add(cur.getCause());
            for (Throwable s : cur.getSuppressed()) {
                if (s != null) todo.add(s);
            }
        }
        return null;
    }

    private static boolean isValidityReason(java.security.cert.CertPathValidatorException e) {
        java.security.cert.CertPathValidatorException.Reason r = e.getReason();
        return r == java.security.cert.CertPathValidatorException.BasicReason.EXPIRED
                || r == java.security.cert.CertPathValidatorException.BasicReason.NOT_YET_VALID
                || r == java.security.cert.CertPathValidatorException.BasicReason.REVOKED;
    }

    private static boolean mentions(Throwable t, String text) {
        String m = t.getMessage();
        return m != null && m.contains(text);
    }
}
