package com.vcfcf.adapters.vcommunity.tls;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * Reads the SHA-256 thumbprint the vCenter presents: one TLS handshake,
 * every certificate accepted, nothing sent after it (design 2.5). Run whether or
 * not the connection is verified, so the certificate can be watched whatever the setting.
 */
public final class ThumbprintProbe {

    /** What the probe found: a thumbprint, or why there is none. Never both. */
    public static final class Result {
        private final String thumbprint;
        private final String failure;

        private Result(String thumbprint, String failure) {
            this.thumbprint = thumbprint;
            this.failure = failure;
        }

        public static Result found(String thumbprintHex) {
            return new Result(thumbprintHex, null);
        }

        public static Result failed(String reason) {
            return new Result(null, reason);
        }

        public boolean ok() {
            return thumbprint != null;
        }

        /** Lowercase hex SHA-256, or null. */
        public String thumbprint() {
            return thumbprint;
        }

        /** Credential-free reason, or null. */
        public String failure() {
            return failure;
        }
    }

    /** The probe as the engine sees it, so tests can supply one. */
    public interface Probe {
        Result probe(String host, int port, int timeoutMs);
    }

    private ThumbprintProbe() {}

    public static final Probe TLS = ThumbprintProbe::probe;

    public static Result probe(String host, int port, int timeoutMs) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new TrustAllManager()}, null);
            String name = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1) : host;
            try (Socket plain = new Socket()) {
                plain.connect(new InetSocketAddress(name, port), timeoutMs);
                plain.setSoTimeout(timeoutMs);
                try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                        .createSocket(plain, name, port, true)) {
                    ssl.startHandshake();
                    Certificate[] chain = ssl.getSession().getPeerCertificates();
                    if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                        return Result.failed("the server presented no X.509 certificate");
                    }
                    return Result.found(Thumbprints.of((X509Certificate) chain[0], 256));
                }
            }
        } catch (IOException e) {
            return Result.failed(e.getClass().getSimpleName() + " during the TLS handshake with "
                    + host + ":" + port);
        } catch (GeneralSecurityException | RuntimeException e) {
            return Result.failed(e.getClass().getSimpleName() + " reading the certificate");
        }
    }
}
