package com.vcfcf.adapters.vcommunity.ops;

/**
 * One event for an object of VCF Operations (design 8.6): a diagnostic log event, never a notification (a
 * notification pushed onto an object of another adapter is accepted and dropped). The message identifies the
 * event: the same message on the same object is the same event.
 */
public final class OpsEvent {

    /** How serious, as the Operations event severities the original used (Information to Critical). */
    public enum Severity { INFORMATION, WARNING, IMMEDIATE, CRITICAL }

    public final String resourceId;
    public final String message;
    public final Severity severity;
    public final long startTimeMs;

    public OpsEvent(String resourceId, String message, Severity severity, long startTimeMs) {
        this.resourceId = resourceId;
        this.message = message;
        this.severity = severity;
        this.startTimeMs = startTimeMs;
    }

    @Override
    public String toString() {
        return severity + " on " + resourceId;
    }
}
