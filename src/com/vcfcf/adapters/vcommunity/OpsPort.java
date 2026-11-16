package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapter.stitch.SuiteApiBatchResult;
import com.vcfcf.adapter.stitch.SuiteApiEvent;
import com.vcfcf.adapter.stitch.SuiteApiHttpException;
import com.vcfcf.adapter.stitch.SuiteApiStitcher;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    /**
     * Diagnostic log events, not managed externally: the platform ends one that is not sent again (design 8.6;
     * what it does exactly for this adapter is experiment G5).
     */
    @Override
    public PushOutcome pushEvents(Collection<OpsEvent> events) {
        if (events == null || events.isEmpty()) return PushOutcome.NOTHING;
        List<SuiteApiEvent> out = new ArrayList<>(events.size());
        for (OpsEvent e : events) {
            out.add(SuiteApiEvent.builder(SuiteApiEvent.Type.DIAGNOSTIC, e.resourceId, e.message, e.startTimeMs)
                    .severity(severity(e.severity)).diagnosticSubType(SuiteApiEvent.DiagnosticSubType.LOG)
                    .managedExternally(Boolean.FALSE).build());
        }
        SuiteApiBatchResult r = stitcher.pushEvents(out);
        return new PushOutcome(r.getSent(), r.getBatchesOk(), r.getBatchesFailed(),
                r.getSkipped(), r.getDropped(), r.isStopped());
    }

    private static SuiteApiEvent.Severity severity(OpsEvent.Severity s) {
        switch (s) {
            case CRITICAL: return SuiteApiEvent.Severity.CRITICAL;
            case IMMEDIATE: return SuiteApiEvent.Severity.IMMEDIATE;
            case WARNING: return SuiteApiEvent.Severity.WARNING;
            default: return SuiteApiEvent.Severity.INFORMATION;
        }
    }
}
