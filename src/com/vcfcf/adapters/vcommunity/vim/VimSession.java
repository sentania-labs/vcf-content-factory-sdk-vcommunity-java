package com.vcfcf.adapters.vcommunity.vim;

/** The session surface the cycle and Test Connection use; {@link VimSoapClient} is the real one. */
public interface VimSession extends AutoCloseable {

    /** A new cycle may log in again after a failed login. */
    void beginCycle();

    /** Have a live session (log in, or keep it alive) and say who the vCenter is. */
    VimResult<About> ensureSession();

    /** Log out and refuse every later call. Never throws. */
    @Override
    void close();

    /** What the property collectors read through; null for a session that cannot read (a test double). */
    default VimReader reader() {
        return null;
    }

    /** {@link #close()}, saying why in the log ({@code reconfigure}, {@code stop}). */
    default void close(String reason) {
        close();
    }
}
