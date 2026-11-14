package com.vcfcf.adapters.vcommunity.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/** Certificate digests as lowercase hex. */
public final class Thumbprints {

    private Thumbprints() {}

    /**
     * SHA-256 ({@code bits} 256), SHA-512 ({@code bits} 512) or SHA-1 ({@code bits} 160, the digest vCenter keeps
     * of a host's certificate) of the certificate's DER.
     */
    public static String of(X509Certificate cert, int bits) throws CertificateEncodingException {
        return hex(digest(cert.getEncoded(), bits));
    }

    static byte[] digest(byte[] der, int bits) {
        try {
            String algorithm = bits == 512 ? "SHA-512" : bits == 160 ? "SHA-1" : "SHA-256";
            return MessageDigest.getInstance(algorithm).digest(der);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the digest for " + bits + " bits is not available in this JVM", e);
        }
    }

    public static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = digits[(b[i] >> 4) & 0xF];
            out[2 * i + 1] = digits[b[i] & 0xF];
        }
        return new String(out);
    }
}
