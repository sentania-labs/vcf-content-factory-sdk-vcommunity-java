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
 * every line. The second answer is used only for its error; a file it returned
 * would not be what was asked for, so the first refusal stands.
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
                if (json.apiErrorCode() != null || !ErrorText.of(json.bodyExcerpt()).isEmpty()) throw json;
            } catch (IOException ignored) {
                // the second look is a courtesy; the first refusal stands
            }
            throw first;
        }
    }
}
