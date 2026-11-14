package com.vcfcf.adapters.vcommunity.kpi;

/**
 * One scored row of a KPI: its name and the five limits where its score is 100, 75, 50, 25 and 0
 * (design 7.1, Appendix A). A row whose zero-at is below its best is higher-is-better.
 */
public final class KpiMetric {
    public final String name;
    public final String unit;
    public final double best;
    public final double green;
    public final double yellow;
    public final double orange;
    public final double zeroAt;
    /** True: the row is left out of the KPI while its value is green. */
    public final boolean skipWhenGreen;
    /** False: the row is scored but not pushed (the object already has that statistic). */
    public final boolean published;

    KpiMetric(String name, String unit, double best, double green, double yellow, double orange,
            double zeroAt, boolean skipWhenGreen, boolean published) {
        this.name = name;
        this.unit = unit;
        this.best = best;
        this.green = green;
        this.yellow = yellow;
        this.orange = orange;
        this.zeroAt = zeroAt;
        this.skipWhenGreen = skipWhenGreen;
        this.published = published;
    }

    @Override
    public String toString() {
        return name;
    }
}
