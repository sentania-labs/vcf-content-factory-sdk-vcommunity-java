package com.vcfcf.adapters.vcommunity.kpi;

import java.util.Map;

/** Network Speed Degradation (map D12): the speed the physical NICs run at as a share of what they are set to. */
public final class NetworkSpeed {

    private NetworkSpeed() {}

    public static final String GROUP = "Network";

    /**
     * {@code sum of actual speeds / sum of configured speeds x 100} over the NICs that have both greater than 0,
     * at most 100; null when no NIC can be compared.
     */
    public static Double degradation(Map<String, Double> speeds, Map<String, Double> configured) {
        double actual = 0;
        double conf = 0;
        boolean any = false;
        for (Map.Entry<String, Double> e : speeds.entrySet()) {
            Double c = configured.get(e.getKey());
            if (e.getValue() > 0 && c != null && c > 0) {
                any = true;
                actual += e.getValue();
                conf += c;
            }
        }
        if (!any) return null;
        return Math.min(100.0, actual / conf * 100);
    }

    /** True for a host below 100 %; a host with no value does not count. */
    public static boolean degraded(Double value) {
        return value != null && value < 100;
    }
}
