package com.vcfcf.adapters.vcommunity.tls;

import java.security.cert.CertificateException;

/**
 * The certificate a server presented is not the one pinned. The message names
 * the field and carries the presented thumbprint (public information), so the
 * admin can compare it with the one they trust. It holds no credential.
 */
public final class PinMismatchException extends CertificateException {
    private static final long serialVersionUID = 1L;

    private final String presented;

    public PinMismatchException(String presented, int pinBits) {
        super("The certificate vCenter presented does not match the vCenter Thumbprint on the "
                + "account (vCenter TLS Validation is Pinned). Presented SHA-" + pinBits
                + " thumbprint: " + presented + ". If the certificate was replaced on purpose, "
                + "enter the new thumbprint; otherwise do not trust this server.");
        this.presented = presented;
    }

    /** The lowercase hex thumbprint the server presented. */
    public String presented() {
        return presented;
    }
}
