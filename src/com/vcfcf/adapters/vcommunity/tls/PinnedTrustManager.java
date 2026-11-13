package com.vcfcf.adapters.vcommunity.tls;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Accepts exactly one certificate: the server's leaf, when its SHA-256 (a 64
 * digit pin) or SHA-512 (128 digit pin) digest equals the pin. The chain, the
 * dates and the name are not looked at; the pin alone decides (design 2.5). It
 * runs during the handshake, so a mismatch ends the connection before the
 * first request.
 */
public final class PinnedTrustManager extends X509ExtendedTrustManager {

    private final String pin;
    private final int bits;

    /** @param pinHex lowercase hex, 64 or 128 characters */
    public PinnedTrustManager(String pinHex) {
        if (pinHex == null || (pinHex.length() != 64 && pinHex.length() != 128)) {
            throw new IllegalArgumentException("a pin is 64 (SHA-256) or 128 (SHA-512) hex digits");
        }
        this.pin = pinHex;
        this.bits = pinHex.length() == 128 ? 512 : 256;
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0 || chain[0] == null) {
            throw new CertificateException("the server presented no certificate");
        }
        String presented = Thumbprints.of(chain[0], bits);
        // Constant time: the pin is not a secret, but there is no reason to leak how much matched.
        boolean same = MessageDigest.isEqual(presented.getBytes(StandardCharsets.US_ASCII),
                pin.getBytes(StandardCharsets.US_ASCII));
        if (!same) throw new PinMismatchException(presented, bits);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        throw new CertificateException("this trust manager does not accept client certificates");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        throw new CertificateException("this trust manager does not accept client certificates");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        throw new CertificateException("this trust manager does not accept client certificates");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
