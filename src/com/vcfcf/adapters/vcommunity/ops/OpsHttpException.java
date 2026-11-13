package com.vcfcf.adapters.vcommunity.ops;

import java.io.IOException;

/** A Suite API answer that was not 2xx, with the status and the platform's error code. */
public final class OpsHttpException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final Integer apiErrorCode;
    private final String bodyExcerpt;

    public OpsHttpException(String message, int status, Integer apiErrorCode) {
        this(message, status, apiErrorCode, "");
    }

    /** @param bodyExcerpt the start of the error body (already bounded and sanitised), or null */
    public OpsHttpException(String message, int status, Integer apiErrorCode, String bodyExcerpt) {
        super(message);
        this.status = status;
        this.apiErrorCode = apiErrorCode;
        this.bodyExcerpt = bodyExcerpt == null ? "" : bodyExcerpt;
    }

    /** The start of the error body the server sent, or "" (server text: bounded, never trusted). */
    public String bodyExcerpt() {
        return bodyExcerpt;
    }

    public int status() {
        return status;
    }

    /** The {@code apiErrorCode} of the error body, or null. */
    public Integer apiErrorCode() {
        return apiErrorCode;
    }
}
