package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapter.stitch.SuiteApiBatchResult;
import com.vcfcf.adapter.stitch.SuiteApiHttpException;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;

import java.io.IOException;
import java.util.Map;

/**
 * The Suite API port, the one SDK-facing adapter between the SDK-free cycle
 * and the framework's {@link SuiteApiStitcher}. It only translates: a non-2xx
 * answer becomes an {@link OpsHttpException}, a batch result becomes a
 * {@link PushOutcome}.
 */
final class OpsPort implements OpsApi {

    private final SuiteApiStitcher stitcher;

    OpsPort(SuiteApiStitcher stitcher) {
        this.stitcher = stitcher;
    }

    SuiteApiStitcher stitcher() {
        return stitcher;
    }

    @Override
    public String get(String path, String accept) throws IOException, InterruptedException {
        try {
            return accept == null ? stitcher.get(path) : stitcher.get(path, accept);
        } catch (SuiteApiHttpException e) {
            throw new OpsHttpException("Suite API answered HTTP " + e.getStatusCode(),
                    e.getStatusCode(), e.getApiErrorCode(), e.getBodyExcerpt());
        }
    }

    @Override
    public String post(String path, String jsonBody) throws IOException, InterruptedException {
        try {
            return stitcher.post(path, jsonBody);
        } catch (SuiteApiHttpException e) {
            throw new OpsHttpException("Suite API answered HTTP " + e.getStatusCode(),
                    e.getStatusCode(), e.getApiErrorCode(), e.getBodyExcerpt());
        }
    }

    @Override
    public PushOutcome pushBatch(Map<String, Map<String, Object>> properties,
            Map<String, Map<String, Double>> stats, long timestampMs) {
        if (properties.isEmpty() && stats.isEmpty()) return PushOutcome.NOTHING;
        SuiteApiBatchResult r = stitcher.pushTypedBatch(properties, stats, timestampMs);
        return new PushOutcome(r.getSent(), r.getBatchesOk(), r.getBatchesFailed(),
                r.getSkipped(), r.getDropped(), r.isStopped());
    }
}
