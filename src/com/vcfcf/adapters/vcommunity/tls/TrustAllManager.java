package com.vcfcf.adapters.vcommunity.tls;

import java.net.Socket;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Accepts any server certificate. Used for Allow Insecure SSL and
 * for the certificate probe, which sends nothing after the handshake. An
 * extended manager, so the JDK does not add its own name check on top.
 */
public final class TrustAllManager extends X509ExtendedTrustManager {

    @Override public void checkServerTrusted(X509Certificate[] c, String a) {}

    @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}

    @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}

    @Override public void checkClientTrusted(X509Certificate[] c, String a) {}

    @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}

    @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
