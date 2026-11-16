package com.vcfcf.adapters.vcommunity.ops;

import java.io.IOException;
import java.util.Collection;
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

    /**
     * An authenticated POST of a JSON query body (the read-style endpoints
     * {@code .../query}).
     *
     * @throws OpsHttpException a non-2xx answer, with its status and error code
     * @throws IOException      a network failure
     */
    String post(String path, String jsonBody) throws IOException, InterruptedException;

    /**
     * Properties first, then stats, in batches; every value stamped {@code timestampMs}. A
     * property value is a {@link String}, or a {@link Double} sent as a numeric property.
     */
    PushOutcome pushBatch(Map<String, Map<String, Object>> properties,
            Map<String, Map<String, Double>> stats, long timestampMs);

    /**
     * Send events to the objects they name. Never throws: a failure is in the outcome. The default sends
     * nothing and says every event was dropped, so an implementation that cannot send events is seen to.
     */
    default PushOutcome pushEvents(Collection<OpsEvent> events) {
        return events == null || events.isEmpty() ? PushOutcome.NOTHING
                : new PushOutcome(0, 0, 0, 0, events.size(), false);
    }
}
