package com.vcfcf.adapters.vcommunity.kpi;

import java.util.Map;

/** Network Speed Degradation (design 7.1): how fast a host's NICs actually link compared with their configured speed. */
public final class NetworkSpeed {

    private NetworkSpeed() {}

    public static final String GROUP = "Network";

    /**
     * Percent of configured speed reached: total actual speed over total configured speed, over those NICs with both
     * above zero, capped at 100. No value when no NIC qualifies.
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
