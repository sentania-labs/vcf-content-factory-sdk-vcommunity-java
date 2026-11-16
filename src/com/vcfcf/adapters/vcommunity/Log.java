package com.vcfcf.adapters.vcommunity;

/**
 * The logging the SDK-free classes need. The adapter adapts its platform
 * logger; tests pass a recorder. INFO is the lowest level used on purpose:
 * DEBUG may not reach the per-instance log. Never pass a credential, a
 * session id, a token or a license key to any method.
 */
public interface Log {
    void info(String message);

    void warn(String message);

    void error(String message);

    /** Drops everything. */
    Log NONE = new Log() {
        @Override public void info(String m) {}
        @Override public void warn(String m) {}
        @Override public void error(String m) {}
    };
}
