package com.vcfcf.adapters.vcommunity.health;

import com.vcfcf.adapters.vcommunity.Log;

/**
 * A {@link Log} that passes every line on and counts the WARN and ERROR ones
 * of the current cycle, keeping the first (design 5.2, DV-32: the pak cannot
 * hook every logger, so it counts its own). Thread safe.
 */
public final class WarnCounter implements Log {

    private final Log delegate;
    private int count;
    private String first;

    public WarnCounter(Log delegate) {
        this.delegate = delegate == null ? Log.NONE : delegate;
    }

    /** Start a new cycle's count. */
    public synchronized void reset() {
        count = 0;
        first = null;
    }

    public synchronized int count() {
        return count;
    }

    /** The first WARN or ERROR line since the reset, bounded to 250 characters, or null. */
    public synchronized String first() {
        return first;
    }

    /** Count something that is a warning but was not logged through here. */
    public synchronized void addExternal(int n, String firstText) {
        if (n <= 0) return;
        count += n;
        if (first == null && firstText != null) first = Failures.bound(firstText);
    }

    private synchronized void note(String m) {
        count++;
        if (first == null) first = Failures.bound(m);
    }

    @Override
    public void info(String m) {
        delegate.info(m);
    }

    @Override
    public void warn(String m) {
        note(m);
        delegate.warn(m);
    }

    @Override
    public void error(String m) {
        note(m);
        delegate.error(m);
    }
}
