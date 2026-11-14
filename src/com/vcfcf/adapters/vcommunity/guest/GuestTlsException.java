package com.vcfcf.adapters.vcommunity.guest;

import java.io.IOException;

/** The ESXi host was not trusted, or there was nothing to trust it by; nothing was sent. */
public final class GuestTlsException extends IOException {
    private static final long serialVersionUID = 1L;

    private final boolean mismatch;

    public GuestTlsException(String message) {
        this(message, false);
    }

    /** @param mismatch the host presented a certificate other than the recorded one (not "nothing to check it by") */
    public GuestTlsException(String message, boolean mismatch) {
        super(message);
        this.mismatch = mismatch;
    }

    public boolean isMismatch() {
        return mismatch;
    }
}
