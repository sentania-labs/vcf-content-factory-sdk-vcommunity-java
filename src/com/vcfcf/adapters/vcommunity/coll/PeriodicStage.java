package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.kpi.PeriodicCheck;
import com.vcfcf.adapters.vcommunity.kpi.PeriodicWindow;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.ops.OpsReads;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.text.PyRound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Periodic Cluster Check (map D9, design 6 and 7.1): once per window, for each cluster, the worst of the
 * three cluster KPIs over the window that just ended, and whether the cluster is closed for business.
 *
 * <p>No state is held between cycles, as in the original: the time of the latest sample of the published keys
 * is the state, read back every cycle. So a restart, a reconfiguration or a second collector loses nothing, a
 * window is never published twice by one instance (the sample this cycle pushes is at or after the window end,
 * which ends the cluster's being due), and the cycle count need not be contiguous. The cost is one small
 * request per 500 clusters, and the history of the due clusters only.
 */
final class PeriodicStage {

    private final DerivedContext x;
    private final Membership m;
    private final String c;

    PeriodicStage(DerivedContext x, Membership m, String collector) {
        this.x = x;
        this.m = m;
        this.c = collector;
    }

    void run(OpsResourceMap clusterMap) throws InterruptedException {
        if (!m.clustersRead()) {
            x.inputMissing(c, "the list of clusters (R-CL)");
            return;
        }
        if (clusterMap == null) {
            x.inputMissing(c, "VCF Operations' list of clusters");
            return;
        }
        PeriodicWindow w = PeriodicWindow.at(x.now(), x.in.checkHour, x.in.checkMinute, x.in.periodHours);
        List<String> ids = new ArrayList<>();
        for (String mo : m.clusters().keySet()) {
            String rid = clusterMap.resourceId(mo);
            if (rid != null) ids.add(rid);
        }
        Collections.sort(ids);
        if (ids.isEmpty()) return;
        List<String> keys = new ArrayList<>(StatKeys.PERIODIC_PUBLISHED);
        keys.addAll(StatKeys.CLUSTER_SCORES);
        Map<String, Map<String, Double>> latest = new HashMap<>();
        OpsReads.Report tr = x.reads.latestTimestamps(ids, keys, latest::put);
        // without the times every cluster would look due: wait for the next cycle
        if (!x.account(tr, c, FailureCause.opsStats("periodic"),
                "the time of the latest Periodic Cluster Check samples")) {
            x.withheld(ids.size() * 4);
            return;
        }
        List<String> due = new ArrayList<>();
        for (String id : ids) {
            if (w.isDue(latest.getOrDefault(id, Collections.<String, Double>emptyMap()))) due.add(id);
        }
        if (due.isEmpty()) return;
        due = w.thisCycle(due, x.now(), PeriodicCheck.MAX_CLUSTERS_PER_CYCLE);
        x.log.info("Periodic Cluster Check: " + due.size() + " of " + ids.size() + " clusters due for the "
                + w.periodHours + " hours to " + new java.util.Date(w.endMs).toInstant() + ", worst "
                + x.in.worstPercent + " %.");
        Map<String, Map<String, List<Double>>> history = new HashMap<>();
        OpsReads.Report hr = x.reads.history(due, StatKeys.CLUSTER_SCORES, w.beginMs, w.endMs,
                PeriodicCheck.CLUSTERS_PER_REQUEST, history::put);
        x.account(hr, c, FailureCause.OPS_HISTORY, "the history of the cluster KPIs");
        for (String id : due) {
            // not read: it stays due, so the next cycle tries again
            if (hr.failedIds.contains(id)) {
                x.withheld(4);
                continue;
            }
            Map<String, List<Double>> samples = history.getOrDefault(id, Collections.<String, List<Double>>emptyMap());
            for (Map.Entry<String, Double> e : PeriodicCheck.windowValues(samples, x.in.worstPercent).entrySet()) {
                x.stat(id, e.getKey(), PyRound.round2(e.getValue()));
            }
        }
    }
}
