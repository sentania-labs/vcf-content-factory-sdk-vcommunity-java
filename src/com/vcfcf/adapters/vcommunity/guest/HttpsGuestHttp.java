package com.vcfcf.adapters.vcommunity.guest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

/**
 * {@link GuestHttp} over {@link HttpsURLConnection}. The handshake is made by an explicit {@code connect()}
 * before anything is written, so a certificate the channel does not accept ends the connection with nothing
 * sent. A connection is made for each transfer and closed after it; redirects are off.
 */
public final class HttpsGuestHttp implements GuestHttp {

    public static final int CONNECT_MS = 10_000;
    public static final int READ_MS = 300_000;

    private static final HostnameVerifier ANY_NAME = (host, session) -> true;

    private final int connectMs;
    private final int readMs;

    public HttpsGuestHttp() {
        this(CONNECT_MS, READ_MS);
    }

    public HttpsGuestHttp(int connectMs, int readMs) {
        this.connectMs = connectMs;
        this.readMs = readMs;
    }

    @Override
    public Response put(String url, byte[] body, GuestTrust.Channel channel) throws IOException {
        HttpsURLConnection c = open(url, channel, "PUT");
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            connect(c, channel);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            int code = c.getResponseCode();
            drain(c);
            return new Response(code, new byte[0], false);
        } catch (IOException e) {
            throw described(e, channel);
        } finally {
            c.disconnect();
        }
    }

    @Override
    public Response get(String url, GuestTrust.Channel channel, long maxBytes) throws IOException {
        HttpsURLConnection c = open(url, channel, "GET");
        try {
            connect(c, channel);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                drain(c);
                return new Response(code, new byte[0], false);
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > maxBytes) return new Response(code, new byte[0], true);
                    out.write(buf, 0, n);
                }
                return new Response(code, out.toByteArray(), false);
            }
        } catch (IOException e) {
            throw described(e, channel);
        } finally {
            c.disconnect();
        }
    }

    private HttpsURLConnection open(String url, GuestTrust.Channel channel, String method) throws IOException {
        if (channel.socketFactory() == null) {
            throw new GuestTlsException(channel.refusal());
        }
        HttpsURLConnection c = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        c.setSSLSocketFactory(channel.socketFactory());
        c.setHostnameVerifier(ANY_NAME); // the thumbprint is the identity, not the name
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setUseCaches(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private static void connect(HttpsURLConnection c, GuestTrust.Channel channel) throws IOException {
        channel.clearMismatch();
        c.connect();
    }

    private static void drain(HttpsURLConnection c) {
        try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in == null) return;
            byte[] buf = new byte[4096];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0 && (total += n) < 65_536) {
                // an answer body is of no use; read a little so the connection ends cleanly
            }
        } catch (IOException ignored) {
            // the status is what matters
        }
    }

    /** The failure as a credential-free, URL-free IOException. */
    private static IOException described(IOException e, GuestTrust.Channel channel) {
        String tls = channel.explain(e);
        if (tls != null) return new GuestTlsException(tls);
        if (e instanceof SocketTimeoutException) return new SocketTimeoutException("the ESXi host did not answer in time");
        return new IOException("the transfer to the ESXi host failed (" + e.getClass().getSimpleName() + ")");
    }
}
