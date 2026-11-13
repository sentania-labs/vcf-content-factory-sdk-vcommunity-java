package com.vcfcf.adapters.vcommunity.health;

import com.vcfcf.adapters.vcommunity.ops.PushSet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The health figures of one cycle, in the two places they land (design 2.4
 * and 5.2): the self metrics on the pak's own adapter instance, which always
 * land, and the {@code vCommunity|Collection Health|*} keys on the vCenter's
 * VMwareAdapter Instance object, which land only when that object is matched.
 *
 * <p>A figure that was not measured is {@code null} and is not sent: a push
 * that failed does not say how many series it sent, and no collector for a
 * phase that has not run reports a status (design 5.1).
 */
public final class CollectionHealth {

    public static final String PREFIX = "vCommunity|Collection Health|";
    public static final String NONE = "None in the last cycle";

    /** Status of one collector (design 5.2). */
    public enum Status {
        OK, PARTIAL, FAILED, SKIPPED, NOT_RUN;

        public String text() {
            return this == NOT_RUN ? "NOT RUN" : name();
        }
    }

    /** What the cycle measured. */
    public static final class Summary {
        public int warnings;
        public String firstWarning;
        public int readsFailed;
        public int valuesWithheld;
        /** Null unless every check-list file read this cycle was parsed (else the count is unknown). */
        public Integer checklistSkipped;
        /** Null unless the lookup of the vCenter object completed (0 matched, 1 not). */
        public Integer objectsUnmatched;
        public double cycleSeconds;
        public String firstFailure;
        /** Null when the push was not clean (the count is unknown). */
        public Integer seriesPushed;
        public Integer propertiesPushed;
        public int pushBatchesFailed;
        public boolean deadlineHit;
        public boolean vcenterResolved;
        /** Collector name to status, only for collectors that exist in this build. */
        public final Map<String, Status> collectorStatus = new LinkedHashMap<>();
        public final Map<String, Integer> collectorFailures = new LinkedHashMap<>();
    }

    private CollectionHealth() {}

    /** Queue the health keys for the vCenter object. */
    public static void vcenterKeys(PushSet ps, String vcenterResourceId, Summary s) {
        ps.stat(vcenterResourceId, PREFIX + "Warnings in Last Cycle", s.warnings);
        ps.prop(vcenterResourceId, PREFIX + "First Warning",
                s.firstWarning == null ? NONE : s.firstWarning);
        ps.stat(vcenterResourceId, PREFIX + "Reads Failed in Last Cycle", s.readsFailed);
        ps.stat(vcenterResourceId, PREFIX + "Values Withheld in Last Cycle", s.valuesWithheld);
        if (s.checklistSkipped != null) {
            ps.stat(vcenterResourceId, PREFIX + "Check-List Entries Skipped", s.checklistSkipped);
        }
        if (s.objectsUnmatched != null) {
            ps.stat(vcenterResourceId, PREFIX + "Objects Not Matched", s.objectsUnmatched);
        }
        ps.stat(vcenterResourceId, PREFIX + "Cycle Seconds", s.cycleSeconds);
        ps.prop(vcenterResourceId, PREFIX + "First Failure",
                s.firstFailure == null ? NONE : s.firstFailure);
        for (Map.Entry<String, Status> e : s.collectorStatus.entrySet()) {
            ps.prop(vcenterResourceId, PREFIX + "Collector:" + e.getKey() + "|Status",
                    e.getValue().text());
            Integer f = s.collectorFailures.get(e.getKey());
            if (f != null) {
                ps.stat(vcenterResourceId, PREFIX + "Collector:" + e.getKey() + "|Failures", f);
            }
        }
    }

    public static final String LAST_ERROR = "Collection|last_error";

    /** The self metrics for the adapter instance; unmeasured figures are absent. */
    public static Map<String, Double> selfMetrics(Summary s) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("Collection|cycle_seconds", s.cycleSeconds);
        m.put("Collection|reads_failed", (double) s.readsFailed);
        m.put("Collection|values_withheld", (double) s.valuesWithheld);
        if (s.objectsUnmatched != null) {
            m.put("Collection|objects_unmatched", (double) s.objectsUnmatched);
        }
        if (s.checklistSkipped != null) {
            m.put("Collection|checklist_entries_skipped", (double) s.checklistSkipped);
        }
        if (s.seriesPushed != null) m.put("Collection|series_pushed", (double) s.seriesPushed);
        if (s.propertiesPushed != null) {
            m.put("Collection|properties_pushed", (double) s.propertiesPushed);
        }
        m.put("Collection|push_batches_failed", (double) s.pushBatchesFailed);
        m.put("Collection|deadline_hit", s.deadlineHit ? 1.0 : 0.0);
        m.put("Collection|vcenter_object_resolved", s.vcenterResolved ? 1.0 : 0.0);
        return m;
    }

    /** The text for {@code Collection|last_error}. */
    public static String lastError(Summary s) {
        return s.firstFailure == null ? NONE : s.firstFailure;
    }
}
