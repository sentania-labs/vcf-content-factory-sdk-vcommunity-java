package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.ops.PushSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Failure accounting, the warning counter, the health keys. */
public final class HealthTest {

    public static void main(String[] a) {
        failures();
        warnCounter();
        keys();
        causes();
    }

    static void failures() {
        Failures f = new Failures();
        T.check(f.isEmpty() && f.readsFailed() == 0 && f.first() == null, "starts empty");
        f.add("host", "F-SOAP-R-HO", "page 1", 40, "timed out");
        f.add("host", "F-SOAP-R-HO", "page 1", 10, "timed out");   // the same unit again
        f.add("host", "F-SOAP-R-HO", "page 2", 0, "timed out");
        f.add("vm", "F-SOAP-R-VM", "all", 100, "fault");
        T.eq(3, f.readsFailed(), "distinct (cause, object) units");
        T.eq(150, f.valuesWithheld(), "withheld values add up");
        T.eq(2, f.forCollector("host"), "per collector");
        T.eq(1, f.forCollector("vm"), "per collector");
        T.eq(0, f.forCollector("datastore"), "none for a collector that did not fail");
        T.eq("F-SOAP-R-HO: timed out", f.first(), "the first failure's text");
        T.eq(2, f.causes().size(), "two causes");
        T.eq(Integer.valueOf(2), f.causes().get("F-SOAP-R-HO"), "units per cause");
        T.RecLog log = new T.RecLog();
        f.logSummary(log);
        T.eq(2, log.warns.size(), "one WARN line per cause");
        T.contains(log.warns.get(0), "F-SOAP-R-HO: 2 failed read(s), 50 value(s) not sent; first: page 1", "line");
        // the text is bounded and one line
        Failures g = new Failures();
        g.add("x", "F-X", "o", 0, "a\nb" + "z".repeat(500));
        T.check(g.first().length() <= 250, "bounded");
        T.notContains(g.first(), "\n", "one line");
        T.done("failure accounting");
    }

    static void warnCounter() {
        T.RecLog inner = new T.RecLog();
        WarnCounter w = new WarnCounter(inner);
        w.info("fyi");
        T.eq(0, w.count(), "INFO is not counted");
        w.warn("first warning");
        w.error("an error");
        w.warn("third");
        T.eq(3, w.count(), "WARN and ERROR counted");
        T.eq("first warning", w.first(), "first kept");
        T.eq(1, inner.infos.size(), "lines pass through");
        T.eq(2, inner.warns.size(), "warn passes through");
        w.addExternal(2, "push failed");
        T.eq(5, w.count(), "external count");
        w.addExternal(0, "ignored");
        w.addExternal(-4, "ignored");
        T.eq(5, w.count(), "no negative or zero");
        w.reset();
        T.eq(0, w.count(), "reset");
        T.check(w.first() == null, "reset first");
        w.addExternal(1, "from push");
        T.eq("from push", w.first(), "an external count can be first");
        WarnCounter bare = new WarnCounter(null);
        bare.warn("x");
        T.eq(1, bare.count(), "no delegate is fine");
        T.done("warning counter");
    }

    static void keys() {
        CollectionHealth.Summary s = new CollectionHealth.Summary();
        s.warnings = 0;
        s.cycleSeconds = 1.25;
        PushSet ps = new PushSet(null);
        // counts that were not measured are not sent
        CollectionHealth.vcenterKeys(ps, "vc-0", s);
        T.check(!ps.stats().get("vc-0").containsKey("vCommunity|Collection Health|Check-List Entries Skipped"), "skipped not measured");
        T.check(!ps.stats().get("vc-0").containsKey("vCommunity|Collection Health|Objects Not Matched"), "unmatched not measured");
        T.check(!CollectionHealth.selfMetrics(s).containsKey("Collection|checklist_entries_skipped"), "self: skipped not measured");
        T.check(!CollectionHealth.selfMetrics(s).containsKey("Collection|objects_unmatched"), "self: unmatched not measured");
        s.checklistSkipped = 0;
        s.objectsUnmatched = 0;
        PushSet ps0 = new PushSet(null);
        CollectionHealth.vcenterKeys(ps0, "vc-0", s);
        T.eq(0.0, ps0.stats().get("vc-0").get("vCommunity|Collection Health|Check-List Entries Skipped"), "a measured zero is sent");
        T.eq(0.0, CollectionHealth.selfMetrics(s).get("Collection|objects_unmatched"), "a measured zero is sent (self)");
        s.checklistSkipped = null;
        s.objectsUnmatched = null;
        CollectionHealth.vcenterKeys(ps, "vc-1", s);
        String p = "vCommunity|Collection Health|";
        T.eq(new TreeSet<>(Arrays.asList(p + "First Warning", p + "First Failure")),
                new TreeSet<>(ps.properties().get("vc-1").keySet()), "property keys");
        T.eq("None in the last cycle", ps.properties().get("vc-1").get(p + "First Warning"), "none text");
        T.eq("None in the last cycle", ps.properties().get("vc-1").get(p + "First Failure"), "none text");
        T.eq(new TreeSet<>(Arrays.asList(p + "Warnings in Last Cycle", p + "Reads Failed in Last Cycle",
                p + "Values Withheld in Last Cycle", p + "Cycle Seconds")), new TreeSet<>(ps.stats().get("vc-1").keySet()), "stat keys");
        T.eq(0.0, ps.stats().get("vc-1").get(p + "Warnings in Last Cycle"), "a count of zero is a count");
        T.eq(1.25, ps.stats().get("vc-1").get(p + "Cycle Seconds"), "seconds");

        s.warnings = 3;
        s.firstWarning = "boom";
        s.firstFailure = "F-LOGIN: no";
        s.collectorStatus.put("check_lists", Status.PARTIAL);
        s.collectorFailures.put("check_lists", 2);
        s.collectorStatus.put("guest", Status.NOT_RUN);
        PushSet ps2 = new PushSet(null);
        CollectionHealth.vcenterKeys(ps2, "vc-1", s);
        Map<String, String> pr = ps2.properties().get("vc-1");
        T.eq("boom", pr.get(p + "First Warning"), "first warning text");
        T.eq("F-LOGIN: no", pr.get(p + "First Failure"), "first failure text");
        T.eq("PARTIAL", pr.get(p + "Collector:check_lists|Status"), "collector status");
        T.eq("NOT RUN", pr.get(p + "Collector:guest|Status"), "NOT RUN spelling");
        T.eq(2.0, ps2.stats().get("vc-1").get(p + "Collector:check_lists|Failures"), "collector failures");
        T.check(!ps2.stats().get("vc-1").containsKey(p + "Collector:guest|Failures"), "no count where none was measured");

        // self metrics: an unmeasured figure is absent, never zero
        Map<String, Double> m = CollectionHealth.selfMetrics(new CollectionHealth.Summary());
        T.check(!m.containsKey("Collection|series_pushed"), "series_pushed not measured");
        T.check(!m.containsKey("Collection|objects_unmatched") && !m.containsKey("Collection|checklist_entries_skipped"), "counts of stages that did not run");
        T.check(!m.containsKey("Collection|properties_pushed"), "properties_pushed not measured");
        T.check(!m.containsKey("Collection|guest_vms_done"), "guest progress not measured");
        T.eq(0.0, m.get("Collection|deadline_hit"), "deadline not hit");
        T.eq(0.0, m.get("Collection|vcenter_object_resolved"), "not resolved");
        CollectionHealth.Summary done = new CollectionHealth.Summary();
        done.seriesPushed = 7;
        done.propertiesPushed = 0;
        done.deadlineHit = true;
        done.vcenterResolved = true;
        Map<String, Double> m2 = CollectionHealth.selfMetrics(done);
        T.eq(7.0, m2.get("Collection|series_pushed"), "series pushed");
        T.eq(0.0, m2.get("Collection|properties_pushed"), "a measured zero is kept");
        T.eq(1.0, m2.get("Collection|deadline_hit"), "deadline hit");
        T.eq(1.0, m2.get("Collection|vcenter_object_resolved"), "resolved");
        for (Double d : m2.values()) T.check(!d.isNaN() && !d.isInfinite(), "finite");
        T.eq("None in the last cycle", CollectionHealth.lastError(new CollectionHealth.Summary()), "last error none");
        T.done("health keys and self metrics");
    }

    static void causes() {
        T.eq("F-LOGIN", FailureCause.LOGIN, "id");
        T.eq("F-SOAP-R-CL", FailureCause.soap("R-CL"), "soap");
        T.eq("F-OPS-MAP-HostSystem", FailureCause.opsMap("HostSystem"), "map");
        T.eq("F-OPS-STATS-vm", FailureCause.opsStats("vm"), "stats");
        T.eq("F-OPS-PROPS-host", FailureCause.opsProps("host"), "props");
        T.eq("F-CHECKLIST-vm_options", FailureCause.checklist("vm_options"), "checklist");
        T.eq("F-GUEST-gate", FailureCause.guest("gate"), "guest");
        List<String> fixed = new ArrayList<>(Arrays.asList(FailureCause.LOGIN, FailureCause.CERT_PROBE,
                FailureCause.PUSH, FailureCause.DEADLINE, FailureCause.EVENTS, FailureCause.OPS_REL));
        for (String id : fixed) T.check(id.startsWith("F-"), id);
        T.done("failure cause ids");
    }
}
