package com.vcfcf.adapters.vcommunity.tls;

import java.security.cert.CertificateException;

/**
 * The server's certificate is not the pinned one. Now used only for the ESXi host channel of the guest phase (the pin is the
 * thumbprint vCenter recorded for the host). The message carries the presented thumbprint (public information) for the admin
 * to compare with the one they trust. It holds no credential.
 */
public final class PinMismatchException extends CertificateException {
    private static final long serialVersionUID = 1L;

    private final String presented;

    public PinMismatchException(String presented, int pinBits) {
        super("The server presented a certificate other than the expected one. Its SHA-" + pinBits
                + " thumbprint is " + presented + ", which differs from the thumbprint expected for it. "
                + "No credential was sent. If nobody replaced the certificate, do not trust this server.");
        this.presented = presented;
    }

    /** The lowercase hex thumbprint the server presented. */
    public String presented() {
        return presented;
    }
}
