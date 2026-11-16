package com.vcfcf.adapters.vcommunity.tls;

import java.io.IOException;

/** Test access to the package-private parser of the certificate read-back. */
public final class CertificateStateAccess {

    private CertificateStateAccess() {}

    public static String[] parse(String json, String resourceId) throws IOException {
        return CertificateState.parse(json, resourceId);
    }
}
