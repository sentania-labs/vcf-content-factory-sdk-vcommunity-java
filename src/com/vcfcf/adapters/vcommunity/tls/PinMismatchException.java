package com.vcfcf.adapters.vcommunity.tls;

import java.security.cert.CertificateException;

/**
 * The server's certificate is not the pinned one. The message names the field
 * and carries the presented thumbprint (public information) for the admin to
 * compare with the one they trust. It holds no credential.
 */
public final class PinMismatchException extends CertificateException {
    private static final long serialVersionUID = 1L;

    private final String presented;

    public PinMismatchException(String presented, int pinBits) {
        super("vCenter presented a certificate other than the pinned one. Its SHA-" + pinBits
                + " thumbprint is " + presented + ", which differs from the vCenter Thumbprint set "
                + "on the account (vCenter TLS Validation: Pinned). No credential was sent. After "
                + "a planned certificate replacement, update the thumbprint field; if nobody "
                + "replaced it, do not trust this server.");
        this.presented = presented;
    }

    /** The lowercase hex thumbprint the server presented. */
    public String presented() {
        return presented;
    }
}
