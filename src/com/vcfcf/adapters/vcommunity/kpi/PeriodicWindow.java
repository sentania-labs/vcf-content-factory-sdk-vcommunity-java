package com.vcfcf.adapters.vcommunity.kpi;

/**
 * The window of the Periodic Cluster Check (map D9): it ends at the latest {@code HH:MM} UTC plus a whole
 * number of periods at or before now, and begins one period earlier. The period divides a day, so the
 * ends fall at the same times every day. Pure arithmetic on epoch milliseconds, so a test can set the clock.
 */
public final class PeriodicWindow {

    public static final long DAY_MS = 24L * 3600 * 1000;
    /** One collection cycle, in minutes (the Operations sample interval the history is asked at). */
    public static final int CYCLE_MINUTES = 5;

    public final long endMs;
    public final long beginMs;
    public final int periodHours;

    private PeriodicWindow(long endMs, int periodHours) {
        this.endMs = endMs;
        this.periodHours = periodHours;
        this.beginMs = endMs - periodHours * 3600_000L;
    }

    /** The latest window that ended at or before {@code nowMs}. */
    public static PeriodicWindow at(long nowMs, int hour, int minute, int periodHours) {
        long startOfDay = Math.floorDiv(nowMs, DAY_MS) * DAY_MS;
        long anchor = startOfDay + hour * 3600_000L + minute * 60_000L - DAY_MS;
        long period = periodHours * 3600_000L;
        return new PeriodicWindow(anchor + period * Math.floorDiv(nowMs - anchor, period), periodHours);
    }

    /**
     * True when a cluster was not calculated for the window and has KPI data in it: its newest published
     * sample is older than the window end, and some KPI has a sample at or after the window start.
     * Timestamps are the latest sample time of each key, absent when the key was never published.
     */
    public boolean isDue(java.util.Map<String, Double> latest) {
        double calculated = 0.0;
        for (String k : StatKeys.PERIODIC_PUBLISHED) {
            Double t = latest.get(k);
            if (t != null && t > calculated) calculated = t;
        }
        boolean hasKpi = false;
        for (String k : StatKeys.CLUSTER_SCORES) {
            Double t = latest.get(k);
            if (t != null && t >= beginMs) hasKpi = true;
        }
        return calculated < endMs && hasKpi;
    }

    /**
     * The due clusters to calculate this cycle: at most {@code limit}, taken in turn. Each cycle after the
     * window end starts {@code limit} further along the list, wrapping around, so a cluster that stays due
     * does not keep the others waiting. No state is needed: the position comes from the time since the end.
     */
    public <T> java.util.List<T> thisCycle(java.util.List<T> due, long nowMs, int limit) {
        if (due.size() <= limit) return due;
        long cycle = Math.floorDiv(nowMs - endMs, CYCLE_MINUTES * 60_000L);
        int start = (int) Math.floorMod(cycle * limit, (long) due.size());
        java.util.List<T> rotated = new java.util.ArrayList<>(due.subList(start, due.size()));
        rotated.addAll(due.subList(0, start));
        return new java.util.ArrayList<>(rotated.subList(0, limit));
    }
}
