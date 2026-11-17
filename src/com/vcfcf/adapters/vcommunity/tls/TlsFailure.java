package com.vcfcf.adapters.vcommunity.tls;

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
     * One credential-free sentence for a TLS failure to the vCenter, or null when {@code t} is not one (a
     * refused connection, a timeout, a fault). With verification on it names the three things an operator can
     * act on: a certificate this collector does not trust (accept it in Validate Connection), a name the
     * certificate does not carry (enter a name that is on it), an expired one (renew it); any other TLS failure
     * names the alert. With Allow Insecure SSL on, nothing is checked, so only the alert is named.
     */
    public static String explain(Throwable t, boolean insecure) {
        return explain(t, insecure, null);
    }

    /**
     * As {@link #explain(Throwable, boolean)}; {@code url} is what the connection dialled, so a name mismatch whose
     * failure text does not carry the name (the FIPS provider's does not always) still names it.
     */
    public static String explain(Throwable t, boolean insecure, String url) {
        if (t == null) return null;
        if (!insecure) {
            if (find(t, 4) != null) {
                String host = mismatchedHost(t);
                if (host == null) host = hostOf(url);
                return "the vCenter's certificate does not carry the name " + (host == null ? "the account dials"
                        : "'" + host + "'") + ". Enter the vCenter by a name that is on its certificate (an IP "
                        + "address works only if the certificate lists it), or reissue the certificate with that "
                        + "name. Accepting the certificate in Validate Connection does not change a name "
                        + "mismatch. Allow Insecure SSL (Advanced Settings) switches both checks off. No credential "
                        + "was sent.";
            }
            if (find(t, 3) != null) {
                return "the vCenter's certificate is expired, not yet valid or revoked. Renew the certificate on "
                        + "the vCenter. No credential was sent.";
            }
            if (find(t, 1) != null) {
                return "the vCenter's certificate is not trusted by this collector. Edit the account, click "
                        + "Validate Connection, review the certificate and accept it; it is checked from then on. "
                        + "Allow Insecure SSL (Advanced Settings) switches the check off, which is not for "
                        + "production. No credential was sent.";
            }
        }
        Throwable alert = find(t, 2);
        if (alert == null) return null;
        // one line: the alert text is the provider's, bounded and stripped of control characters
        String m = Failures.bound(alert.getMessage());
        return "the TLS connection to the vCenter failed (" + alert.getClass().getSimpleName()
                + (m.isEmpty() ? "" : ": " + m)
                + "); certificate checking is " + (insecure ? "off (Allow Insecure SSL)" : "on");
    }

    /** The same patterns as the framework's {@code CertificateNames.MISMATCH_HOST_PATTERNS} (that class is SDK side and not on this one's class path); keep the two in step. */
    private static final java.util.regex.Pattern[] MISMATCH_HOST = {
        java.util.regex.Pattern.compile("No subject alternative name matching (\\S+) in the "),
        java.util.regex.Pattern.compile("No subject alternative (?:DNS )?names? matching (?:IP address )?(\\S+) found"),
        java.util.regex.Pattern.compile("No name matching (\\S+) found"),
        java.util.regex.Pattern.compile("(?i)https hostname.*should be <([^>]+)>"),
    };

    /**
     * What Validate Connection adds after VCF Operations' own certificate message ({@code certificateFailureMessage}):
     * advice for the failure, or {@code defaultMessage} unchanged when it already says everything. The framework's text
     * is enough for a name mismatch and for "no usable host"; an expired, not yet valid or revoked certificate is
     * renewed, not accepted; only an untrusted chain is answered by accepting the certificate.
     */
    public static String certificateAdvice(Throwable failure, String defaultMessage, boolean noUsableHost) {
        if (noUsableHost || find(failure, 4) != null) return defaultMessage;
        if (find(failure, 3) != null) {
            return defaultMessage + " The certificate is expired, not yet valid or revoked: renew it on the vCenter. "
                    + "Accepting it here does not help.";
        }
        if (find(failure, 1) != null) {
            return defaultMessage + " Review the vCenter certificate here and accept it, and it is checked from then "
                    + "on. Allow Insecure SSL (Advanced Settings) switches the check off; it is not for production.";
        }
        return defaultMessage;
    }

    /** The host of a URL, for a message; null when there is none. Never throws. */
    static String hostOf(String url) {
        if (url == null) return null;
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? null : Failures.bound(h.startsWith("[") ? h.substring(1, h.length() - 1) : h);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The name a mismatch failure says the certificate lacks, or null (for the message only). */
    static String mismatchedHost(Throwable t) {
        Throwable hit = find(t, 4);
        if (hit == null) return null;
        for (Throwable cur = hit; cur != null; cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m == null) continue;
            for (java.util.regex.Pattern p : MISMATCH_HOST) {
                java.util.regex.Matcher mm = p.matcher(m);
                if (mm.find()) {
                    String h = mm.group(1).trim();
                    while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
                    if (!h.isEmpty()) return Failures.bound(h);
                }
            }
        }
        return null;
    }

    /**
     * Breadth first over causes and suppressed. kind 0: a pin mismatch (the ESXi host channel of the guest
     * phase); 1: an untrusted chain; 3: an expired, not yet valid or revoked certificate; 4: a name the
     * certificate does not carry; 2: any TLS handshake failure (an SSLException, or a BouncyCastle alert,
     * matched by name so no provider class is needed).
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
                case 4:
                    if (mentions(cur, "No subject alternative") || mentions(cur, "No name matching")
                            || isHostnameWrong(cur.getMessage())) {
                        return cur;
                    }
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
                    // 8.18.7: the platform trust manager with no accepted certificate has an empty anchor set
                    if (cur instanceof java.security.InvalidAlgorithmParameterException
                            || mentions(cur, "trustAnchors parameter must be non-empty")
                            || cur instanceof java.security.cert.CertPathBuilderException
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

    private static boolean isHostnameWrong(String m) {
        if (m == null) return false;
        String l = m.toLowerCase(java.util.Locale.ROOT);
        return l.contains("https hostname wrong") || l.contains("wrong https hostname")
                || (l.contains("https hostname") && l.contains("should be"));
    }

    private static boolean mentions(Throwable t, String text) {
        String m = t.getMessage();
        return m != null && m.contains(text);
    }
}
