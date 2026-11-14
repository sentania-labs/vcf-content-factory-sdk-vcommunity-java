package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.kpi.BusinessApps;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;
import com.vcfcf.adapters.vcommunity.ops.OpsReads;
import com.vcfcf.adapters.vcommunity.ops.UnscopedIds;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Business Application figures on the Container objects (map D12, design 4.12): Tier, Business Application
 * and Enterprise performance, from the VM Guest OS KPI of the VMs below them, as stored (a cycle behind). The
 * tree is read from VCF Operations: CHILD relationships, one request each.
 *
 * <p>A relationship request or a batch of statistics that failed withholds the objects whose inputs it carried
 * and every object above them (DV-24); the others are still computed. The original stops at the first failure.
 */
final class BusinessStage {

    private static final String G = StatKeys.P;
    private static final String CONTAINER = "Container";
    private static final String TIER = "Tier";
    private static final String APPLICATION = "BusinessService";
    private static final String ENTERPRISE = "Enterprise";
    private static final String VM = "VirtualMachine";
    private static final String NAMESPACE = "K8S-Namespace";

    private final DerivedContext x;
    private final UnscopedIds lists;
    private final String c;

    BusinessStage(DerivedContext x, UnscopedIds lists, String collector) {
        this.x = x;
        this.lists = lists;
        this.c = collector;
    }

    private List<String> ids(String kind) throws InterruptedException {
        String[] why = new String[1];
        List<String> l = lists.get(x.in.ops, CONTAINER, kind, x.in.cycle, true, why);
        if (why[0] != null) {
            x.failed(c, FailureCause.opsMap(kind), kind, 0, "the VCF Operations list of " + kind
                    + " objects could not be read (" + why[0] + "); the figures that need it are not sent");
            return null;
        }
        return l;
    }

    /** After this many relationship reads fail in one cycle the rest are not tried: Operations is not answering. */
    static final int MAX_RELATION_FAILURES = 20;
    private int relationFailures;

    /** The CHILD objects of one resource; null when they could not be read (counted). */
    private List<OpsReads.Related> children(String id) throws InterruptedException {
        if (relationFailures >= MAX_RELATION_FAILURES) return null;
        try {
            return x.reads.related(id, "CHILD");
        } catch (IOException | RuntimeException e) {
            relationFailures++;
            related(id, e);
            return null;
        }
    }

    private static List<String> kindOf(List<OpsReads.Related> rel, String kind) {
        List<String> out = new ArrayList<>();
        for (OpsReads.Related r : rel) if (kind.equals(r.kind)) out.add(r.id);
        return out;
    }

    void run() throws InterruptedException {
        List<String> tiers = ids(TIER);
        List<String> apps = ids(APPLICATION);
        List<String> enterprises = ids(ENTERPRISE);
        if (tiers == null || apps == null || enterprises == null) {
            // the list of a kind is unknown, so which objects above or below it exist is too
            x.withheld(1);
            return;
        }
        if (tiers.isEmpty() && apps.isEmpty() && enterprises.isEmpty()) return;

        // Tier: the VMs of each Tier, and their VM Guest OS KPI
        Map<String, List<String>> tierVms = new LinkedHashMap<>();
        Set<String> tierUnknown = new HashSet<>();
        Set<String> vmIds = new TreeSet<>();
        for (String t : tiers) {
            if (x.stop()) return;
            List<OpsReads.Related> ch = children(t);
            if (ch == null) {
                tierUnknown.add(t);
                continue;
            }
            List<String> v = kindOf(ch, VM);
            tierVms.put(t, v);
            vmIds.addAll(v);
        }
        Map<String, Double> vmKpi = new HashMap<>();
        OpsReads.Report r = x.reads.latestStats(new ArrayList<>(vmIds), Collections.singletonList(BusinessApps.VM_KPI_STAT),
                OpsReads.STALE_MS, x.now(), (id, s) -> {
                    Double v = s.get(BusinessApps.VM_KPI_STAT);
                    if (v != null) vmKpi.put(id, v);
                });
        x.account(r, c, FailureCause.opsStats("business-vm-kpi"), "the VM Guest OS KPI of the Tiers' VMs");
        Map<String, Double> tierValue = new HashMap<>();
        for (Map.Entry<String, List<String>> e : tierVms.entrySet()) {
            List<String> vs = e.getValue();
            boolean unknown = false;
            List<Double> kpis = new ArrayList<>();
            for (String v : vs) {
                if (r.failedIds.contains(v)) unknown = true;
                Double k = vmKpi.get(v);
                if (k != null) kpis.add(k);
            }
            if (unknown) {
                tierUnknown.add(e.getKey());
                continue;
            }
            Double v = BusinessApps.tier(kpis);
            if (v != null) {
                x.stat(e.getKey(), G + BusinessApps.GROUP + "|Tier Performance", v);
                tierValue.put(e.getKey(), v);
            }
        }
        x.withheld(tierUnknown.size());

        // Business Application: its Tiers, and the K8S Namespaces within two levels of it
        Map<String, List<String>> appTiers = new LinkedHashMap<>();
        Map<String, List<String>> appNamespaces = new LinkedHashMap<>();
        Set<String> appUnknown = new HashSet<>();
        Set<String> nsIds = new TreeSet<>();
        for (String a : apps) {
            if (x.stop()) return;
            List<OpsReads.Related> children = children(a);
            boolean unknown = children == null;
            Set<String> ns = new TreeSet<>();
            if (!unknown) {
                ns.addAll(kindOf(children, NAMESPACE));
                // level 2, through the children that are not Tiers: a Tier's own children are its VMs
                for (OpsReads.Related ch : children) {
                    if (TIER.equals(ch.kind)) continue;
                    List<OpsReads.Related> grand = children(ch.id);
                    if (grand == null) {
                        unknown = true;
                        break;
                    }
                    ns.addAll(kindOf(grand, NAMESPACE));
                }
            }
            if (unknown) {
                appUnknown.add(a);
                continue;
            }
            appTiers.put(a, kindOf(children, TIER));
            appNamespaces.put(a, new ArrayList<>(ns));
            nsIds.addAll(ns);
        }
        Map<String, Double> nsValue = new HashMap<>();
        OpsReads.Report nr = x.reads.latestStats(new ArrayList<>(nsIds), Collections.singletonList(BusinessApps.NAMESPACE_STAT),
                OpsReads.STALE_MS, x.now(), (id, s) -> {
                    Double v = s.get(BusinessApps.NAMESPACE_STAT);
                    if (v != null) nsValue.put(id, v);
                });
        x.account(nr, c, FailureCause.opsStats("business-namespaces"), "the performance of the K8S Namespaces");
        Map<String, Double> appValue = new HashMap<>();
        for (Map.Entry<String, List<String>> e : appTiers.entrySet()) {
            String a = e.getKey();
            boolean unknown = false;
            List<Double> tierList = new ArrayList<>();
            for (String t : e.getValue()) {
                if (tierUnknown.contains(t)) unknown = true;
                Double v = tierValue.get(t);
                if (v != null) tierList.add(v);
            }
            List<Double> nsList = new ArrayList<>();
            for (String n : appNamespaces.get(a)) {
                if (nr.failedIds.contains(n)) unknown = true;
                Double v = nsValue.get(n);
                if (v != null) nsList.add(v);
            }
            if (unknown) {
                appUnknown.add(a);
                continue;
            }
            Double v = BusinessApps.application(nsList, tierList);
            if (v != null) {
                x.stat(a, G + BusinessApps.GROUP + "|Business Application Performance", v);
                appValue.put(a, v);
            }
        }
        x.withheld(appUnknown.size());

        // Enterprise: the Business Applications of each Enterprise
        for (String en : enterprises) {
            if (x.stop()) return;
            List<OpsReads.Related> ch = children(en);
            if (ch == null) {
                x.withheld(1);
                continue;
            }
            boolean unknown = false;
            List<Double> values = new ArrayList<>();
            for (String a : kindOf(ch, APPLICATION)) {
                if (appUnknown.contains(a)) unknown = true;
                Double v = appValue.get(a);
                if (v != null) values.add(v);
            }
            if (unknown) {
                x.withheld(1);
                continue;
            }
            Double v = BusinessApps.enterprise(values);
            if (v != null) x.stat(en, G + BusinessApps.GROUP + "|Business Applications Performance", v);
        }
    }

    private void related(String id, Exception e) {
        x.failed(c, FailureCause.OPS_REL, id, 0, "the relationships of " + id + " could not be read ("
                + (e instanceof com.vcfcf.adapters.vcommunity.ops.OpsHttpException
                        ? "HTTP " + ((com.vcfcf.adapters.vcommunity.ops.OpsHttpException) e).status()
                        : e.getClass().getSimpleName()) + "); the figures that need them are not sent");
    }
}
