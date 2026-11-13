package com.vcfcf.adapters.vcommunity.rest;

/**
 * What one REST call gave: the status and the body of a 2xx answer, or the reason there is
 * none. Carries its own failure, so a pool of workers shares no "last error" field. The message
 * is one credential-free line, safe to count and to log.
 */
public final class RestResult {
    private final int status;
    private final String body;
    private final String message;
    private final Throwable cause;
    private final boolean forbidden;

    private RestResult(int status, String body, String message, Throwable cause, boolean forbidden) {
        this.status = status;
        this.body = body;
        this.message = message;
        this.cause = cause;
        this.forbidden = forbidden;
    }

    static RestResult ok(int status, String body) {
        return new RestResult(status, body, null, null, false);
    }

    static RestResult failed(int status, String message, Throwable cause) {
        return new RestResult(status, null, message, cause, status == 401 || status == 403);
    }

    public boolean isOk() {
        return message == null;
    }

    /** The HTTP status, or 0 when the call never got an answer. */
    public int status() {
        return status;
    }

    /** The body of a 2xx answer, else null. */
    public String body() {
        return body;
    }

    /** Why the call failed (one line, no credential, no session id), or null when it did not. */
    public String message() {
        return message;
    }

    public Throwable cause() {
        return cause;
    }

    /** The answer was 401 or 403: the account may not do this. */
    public boolean isDenied() {
        return forbidden;
    }
}
