package com.vcfcf.adapters.vcommunity.kpi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Network Speed Degradation (design 7.1): how fast a host's NICs actually link compared with their configured speed. */
public final class NetworkSpeed {

    private NetworkSpeed() {}

    public static final String GROUP = "Network";

    /** The property key of one NIC's actual speed, for example {@code net:vmnic0|speed}. */
    public static String speedKey(String device) {
        return instanceKey(device, StatKeys.NIC_SPEED);
    }

    /** The property key of one NIC's configured speed, for example {@code net:vmnic0|configuredSpeed}. */
    public static String configuredKey(String device) {
        return instanceKey(device, StatKeys.NIC_CONFIGURED_SPEED);
    }

    private static String instanceKey(String device, String generic) {
        int bar = generic.indexOf('|');
        return generic.substring(0, bar) + ":" + device + generic.substring(bar);
    }

    /**
     * A speed property value as a number: only a finite number above zero counts. Text that is not a number, a
     * zero, a negative or nothing at all is no value (null), never a stand-in.
     */
    public static Double parseSpeed(String text) {
        if (text == null) return null;
        try {
            double v = Double.parseDouble(text.trim());
            return Double.isNaN(v) || Double.isInfinite(v) || v <= 0 ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One host's NIC speeds read from its properties: by device, only the NICs that carry a usable value. */
    public static final class Speeds {
        public final Map<String, Double> actual = new LinkedHashMap<>();
        public final Map<String, Double> configured = new LinkedHashMap<>();
    }

    public static Speeds read(Set<String> devices, Map<String, String> props) {
        Speeds out = new Speeds();
        for (String dev : devices) {
            Double a = parseSpeed(props.get(speedKey(dev)));
            Double c = parseSpeed(props.get(configuredKey(dev)));
            if (a != null) out.actual.put(dev, a);
            if (c != null) out.configured.put(dev, c);
        }
        return out;
    }

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
