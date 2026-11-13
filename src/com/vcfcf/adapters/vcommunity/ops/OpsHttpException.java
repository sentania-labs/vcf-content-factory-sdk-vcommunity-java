package com.vcfcf.adapters.vcommunity.ops;

import java.io.IOException;

/** A Suite API answer that was not 2xx, with the status and the platform's error code. */
public final class OpsHttpException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final Integer apiErrorCode;

    public OpsHttpException(String message, int status, Integer apiErrorCode) {
        super(message);
        this.status = status;
        this.apiErrorCode = apiErrorCode;
    }

    public int status() {
        return status;
    }

    /** The {@code apiErrorCode} of the error body, or null. */
    public Integer apiErrorCode() {
        return apiErrorCode;
    }
}
