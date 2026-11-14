package com.vcfcf.adapters.vcommunity.kpi;

/**
 * The Periodic Cluster Check window (design 7.1). Its end is the most recent moment, no later than now, that falls
 * on the configured {@code HH:MM} UTC plus a whole number of periods; it starts one period before that. Periods
 * divide a day, so the same end times recur daily. Integer arithmetic on epoch milliseconds, so tests set the clock.
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
     * Whether a cluster still has to be checked for this window: none of its four published series has a sample as
     * new as the window's end, and one of its three KPI series has a sample inside the window. The map holds the
     * latest sample time of each key; a key never stored is absent.
     */
    public boolean awaitsCheck(java.util.Map<String, Double> latest) {
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
     * The part of the due list to work on now. Up to {@code limit} clusters, all of them; beyond that, a slice of
     * {@code limit} whose start advances by {@code limit} for every five minutes since the window ended and wraps
     * past the end of the list. The slice depends on the clock alone, so nothing is remembered between cycles and
     * a cluster that cannot be finished does not block the rest.
     */
    public <T> java.util.List<T> sliceFor(java.util.List<T> due, long nowMs, int limit) {
        if (due.size() <= limit) return due;
        long cycle = Math.floorDiv(nowMs - endMs, CYCLE_MINUTES * 60_000L);
        int start = (int) Math.floorMod(cycle * limit, (long) due.size());
        java.util.List<T> rotated = new java.util.ArrayList<>(due.subList(start, due.size()));
        rotated.addAll(due.subList(0, start));
        return new java.util.ArrayList<>(rotated.subList(0, limit));
    }
}
