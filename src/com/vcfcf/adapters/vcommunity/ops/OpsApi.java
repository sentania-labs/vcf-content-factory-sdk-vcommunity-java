package com.vcfcf.adapters.vcommunity.ops;

import java.io.IOException;
import java.util.Map;

/**
 * The VCF Operations Suite API as the cycle needs it. The adapter implements
 * it over the framework's stitcher ({@code OpsPort}); tests implement it with
 * a fake. Pushes never throw: a failure is in the outcome.
 */
public interface OpsApi {

    /**
     * An authenticated GET.
     *
     * @param accept the Accept header; null for JSON
     * @throws OpsHttpException a non-2xx answer, with its status and error code
     * @throws IOException      a network failure
     */
    String get(String path, String accept) throws IOException, InterruptedException;

    /** Properties first, then stats, in batches; every value stamped {@code timestampMs}. */
    PushOutcome pushBatch(Map<String, Map<String, String>> properties,
            Map<String, Map<String, Double>> stats, long timestampMs);
}
