package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.kpi.MonsterVm;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsMapCache;
import com.vcfcf.adapters.vcommunity.ops.OpsResourceMap;
import com.vcfcf.adapters.vcommunity.ops.PushSet;
import com.vcfcf.adapters.vcommunity.ops.UnscopedIds;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The derived metrics of a cycle (design 7, phase D): the KPIs, availability, network speed, shares, port
 * groups and resource pools of the cluster, host, VM, Datacenter and vCenter objects; the Periodic Cluster
 * Check; the vSphere World roll-ups with the Cluster Delta; the Business Applications. SDK-free.
 *
 * <p>It runs after the property phase, on the caller thread, and queues into the same {@link PushSet}, so the
 * values go out in the same push at the cycle's start time. Everything it knows comes from the membership the
 * vCenter reads gave and from VCF Operations read back through the one {@link OpsApi}; it holds no value
 * between cycles except two lists of resource ids (the Datacenters of this vCenter, and the unscoped kinds
 * the roll-ups read), which are read again on the cadence of the Ops maps and after a failure.
 */
public final class DerivedPhase {

    public static final String C_KPI = "kpi";
    public static final String C_WORLD = "world";
    public static final String C_DATACENTER = PropertyCollection.C_DATACENTER;
    public static final String C_BUSINESS = "business_applications";

    /** What a cycle hands to the derived phase. */
    public static final class Input {
        public OpsApi ops;
        public String vcid;
        /** The vCenter's own object in Ops, or null when it was not matched. */
        public String vcenterResourceId;
        public Membership members;
        /** This vCenter's Ops resource map of a kind (cluster, host, VM), or null when there is none. */
        public Function<String, OpsResourceMap> opsMap = k -> null;
        /** Told that a list of this kind should be read again next cycle (a batch that used it failed). */
        public java.util.function.Consumer<String> markStale = k -> { };
        public int periodHours = 24;
        public int checkHour;
        public int checkMinute;
        public int worstPercent = 5;
        public MonsterVm monster = MonsterVm.DEFAULT;
        public long cycle;
        /** The cycle's start: the sample time of every value, and the "now" of every age and window. */
        public long startMs;
        public long deadlineMs;
        public PushSet ps;
        public Failures failures;
    }

    /** What the phase measured. */
    public static final class Result {
        public final Map<String, Status> status = new LinkedHashMap<>();
        public boolean deadlineHit;
        private Runnable afterCleanPush = () -> { };

        /** Remember what was sent; call it once, after a clean push. */
        public void commit() {
            afterCleanPush.run();
        }
    }

    private final Log log;
    private final LongSupplier clock;
    private final OpsMapCache datacenterMaps = new OpsMapCache();
    private final UnscopedIds unscoped = new UnscopedIds();
    private final DeltaMemory delta = new DeltaMemory();
    /** What the last cycle saw of the opt-in NIC configured speed property; null before the first cycle. */
    private String nicPolicy;

    public DerivedPhase(Log log, LongSupplier clock) {
        this.log = log == null ? Log.NONE : log;
        this.clock = clock;
    }

    public Result run(Input in) throws InterruptedException {
        Result res = new Result();
        if (in.ops == null || in.vcid == null || in.members == null) {
            for (String c : new String[] {C_KPI, C_DATACENTER, C_WORLD, C_BUSINESS}) res.status.put(c, Status.SKIPPED);
            return res;
        }
        DerivedContext x = new DerivedContext(in, clock, log);
        x.nicPolicy = this::nicPolicyChanged;
        if (x.stop()) {
            x.deadlineHit = true;
            in.failures.add(C_KPI, FailureCause.DEADLINE, "derived metrics", 0,
                    "the cycle deadline was reached before the derived metrics were computed");
            for (String c : new String[] {C_KPI, C_DATACENTER, C_WORLD, C_BUSINESS}) res.status.put(c, Status.NOT_RUN);
            res.deadlineHit = true;
            return res;
        }
        Membership m = in.members;
        new ObjectKpis(x, m, () -> datacenterMap(x), C_KPI).run();
        if (!x.stop()) new PeriodicStage(x, m, C_KPI).run(in.opsMap.apply(ClusterCollector.KIND));
        res.status.put(C_KPI, status(x, in, C_KPI));
        res.status.put(C_DATACENTER, mergeDc(x, in, m));
        Status world = Status.NOT_RUN;
        Status business = Status.NOT_RUN;
        if (!x.stop()) {
            new WorldStage(x, unscoped, C_WORLD, delta).run();
            world = status(x, in, C_WORLD);
        }
        if (!x.stop()) {
            new BusinessStage(x, unscoped, C_BUSINESS).run();
            business = status(x, in, C_BUSINESS);
        }
        if (x.stop()) {
            x.deadlineHit = true;
        }
        res.status.put(C_WORLD, world);
        res.status.put(C_BUSINESS, business);
        res.deadlineHit = x.deadlineHit;
        final long at = x.now();
        res.afterCleanPush = () -> delta.commit(at);
        return res;
    }

    /**
     * The NIC configured speed property is opt-in (off in the stock policy). Not having it is no failure: nothing
     * is counted and no status changes. It is said once at INFO when first seen missing, and again when that changes.
     */
    private void nicPolicyChanged(String state) {
        String before = nicPolicy;
        if (state.equals(before)) return;
        nicPolicy = state;
        if (ObjectKpis.NIC_CONFIGURED_OFF.equals(state)) {
            log.info(FailureCause.POLICY_NIC_CONFIGURED_SPEED + ": no host reports a configured NIC speed ("
                    + "net|configuredSpeed, Network | Configured Speed), so Network Speed Degradation is not sent;"
                    + " the property is not collected unless the active policy enables it (a NIC left on"
                    + " auto-negotiate may report none either way)");
        } else if (before != null) {
            log.info(FailureCause.POLICY_NIC_CONFIGURED_SPEED + ": a configured NIC speed is reported again");
        }
    }

    private static Status status(DerivedContext x, Input in, String collector) {
        int gaps = x.gaps.getOrDefault(collector, 0);
        // the cluster, host and VM lists are each an input of the KPI collector: with none of them it did nothing
        if (C_KPI.equals(collector) && gaps >= 3) return Status.FAILED;
        return in.failures.forCollector(collector) == 0 && gaps == 0 ? Status.OK : Status.PARTIAL;
    }

    /** The Datacenter collector: its own reads (structure), and its figures; PARTIAL when any failed. */
    private static Status mergeDc(DerivedContext x, Input in, Membership m) {
        if (!m.datacentersRead()) return Status.FAILED;
        return status(x, in, C_DATACENTER);
    }

    /** This vCenter's Datacenters in Ops by MoRef (read on the Ops map cadence); null, counted, when there is none. */
    private OpsResourceMap datacenterMap(DerivedContext x) {
        Input in = x.in;
        String kind = "Datacenter";
        try {
            if (datacenterMaps.due(kind, in.vcid, in.cycle)) {
                try {
                    OpsResourceMap fresh = OpsResourceMap.load(in.ops, in.vcid, kind);
                    datacenterMaps.put(kind, in.vcid, in.cycle, fresh);
                    datacenterMaps.scheduledRead(kind);
                } catch (IOException | RuntimeException e) {
                    datacenterMaps.failed(kind);
                    in.failures.add(C_DATACENTER, FailureCause.opsMap(kind), kind, 0,
                            "the VCF Operations list of Datacenter objects could not be read ("
                            + e.getClass().getSimpleName() + ")"
                            + (datacenterMaps.current(kind, in.vcid) == null ? "; their keys are held back"
                                    : "; the previous list is used"));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return datacenterMaps.current(kind, in.vcid);
    }
}
