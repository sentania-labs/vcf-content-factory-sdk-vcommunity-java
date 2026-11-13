package com.vcfcf.adapters.vcommunity.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/** Certificate digests as lowercase hex. */
public final class Thumbprints {

    private Thumbprints() {}

    /** SHA-256 ({@code bits} 256) or SHA-512 ({@code bits} 512) of the certificate's DER. */
    public static String of(X509Certificate cert, int bits) throws CertificateEncodingException {
        return hex(digest(cert.getEncoded(), bits));
    }

    static byte[] digest(byte[] der, int bits) {
        try {
            return MessageDigest.getInstance(bits == 512 ? "SHA-512" : "SHA-256").digest(der);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-" + bits + " is not available in this JVM", e);
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
