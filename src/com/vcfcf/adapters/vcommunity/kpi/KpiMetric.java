package com.vcfcf.adapters.vcommunity.kpi;

/**
 * One scored row of a KPI: a name and five limits at which the row scores 100, 75, 50, 25 and 0 (design 7.1,
 * Appendix A). A row whose last limit lies below its first is read as higher-is-better.
 */
public final class KpiMetric {
    public final String name;
    public final String unit;
    public final double best;
    public final double green;
    public final double yellow;
    public final double orange;
    public final double zeroAt;
    /** True: while the value is in the best band the row does not take part in the KPI. */
    public final boolean skipWhenGreen;
    /** False: the row counts in the score but is not sent, the object already carrying that statistic. */
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
