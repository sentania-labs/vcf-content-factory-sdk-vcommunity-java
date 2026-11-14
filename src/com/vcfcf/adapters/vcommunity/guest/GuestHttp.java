package com.vcfcf.adapters.vcommunity.guest;

import java.io.IOException;

/**
 * The HTTPS leg of a guest file transfer: a PUT of a file to the ESXi host's URL, and a GET of one from it.
 * {@link HttpsGuestHttp} is the real one; tests substitute a fake for the cases that are about the guest and
 * not about the wire. An implementation must not send content, and must send no credential, before the
 * channel's certificate check has passed, and must not follow a redirect.
 */
public interface GuestHttp {

    /** What the host answered. */
    final class Response {
        public final int status;
        public final byte[] body;
        /** The body is longer than the limit asked for; {@link #body} is then empty. */
        public final boolean tooLarge;

        public Response(int status, byte[] body, boolean tooLarge) {
            this.status = status;
            this.body = body;
            this.tooLarge = tooLarge;
        }
    }

    /**
     * @param url the transfer URL, already checked by {@link TransferUrl}
     * @throws IOException the connection, the handshake or the transfer failed; the message is credential-free
     *         and carries no URL (the URL holds a ticket)
     */
    Response put(String url, byte[] body, GuestTrust.Channel channel) throws IOException;

    /**
     * @param maxBytes the most bytes read
     * @throws IOException as {@link #put}
     */
    Response get(String url, GuestTrust.Channel channel, long maxBytes) throws IOException;
}
