package com.vcfcf.adapters.vcommunity.guest;

import java.io.IOException;

/** The ESXi host was not trusted, or there was nothing to trust it by; nothing was sent. */
public final class GuestTlsException extends IOException {
    private static final long serialVersionUID = 1L;

    public GuestTlsException(String message) {
        super(message);
    }
}
