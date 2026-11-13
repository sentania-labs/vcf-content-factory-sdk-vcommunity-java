package com.vcfcf.adapters.vcommunity.list;

import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;

import java.io.IOException;

/**
 * Reads one configuration file from the Suite API. The file itself is asked for
 * with {@code Accept: *}{@code /*}. A refusal that names no {@code apiErrorCode}
 * (VCF Operations 8.18.7 answers an XML error body, which the framework does not
 * read a code from) is asked for once more with {@code Accept: application/json},
 * so the store's error code (1501, no such file) and message reach the caller on
 * every line. Only a code is taken from the second answer (with its message), and it is
 * reported under the first request's status; a file it returned, or an answer with no code,
 * leaves the first refusal as it was.
 */
public final class ConfigFileFetch {

    private ConfigFileFetch() {}

    public static String fetch(OpsApi ops, String path) throws IOException, InterruptedException {
        try {
            return ops.get(path, "*/*");
        } catch (OpsHttpException first) {
            if (first.apiErrorCode() != null || first.status() < 400 || first.status() >= 500) throw first;
            try {
                ops.get(path, "application/json");
            } catch (OpsHttpException json) {
                // Only the store's own error code is taken from the second look, and it is reported
                // under the status of the request that was refused: what the second request got is
                // not evidence about the first one beyond that code (a bare 404 there is not
                // "the file is absent").
                if (json.apiErrorCode() != null) {
                    throw new OpsHttpException(first.getMessage(), first.status(), json.apiErrorCode(),
                            json.bodyExcerpt());
                }
            } catch (IOException ignored) {
                // the second look is a courtesy; the first refusal stands
            }
            throw first;
        }
    }
}
