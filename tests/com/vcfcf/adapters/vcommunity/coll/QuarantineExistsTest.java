package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.kpi.Availability;
import com.vcfcf.adapters.vcommunity.kpi.StatKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether the version has the quarantine property is decided from every host whose properties were read, whether or
 * not its statistics were (DV-110). A statistics failure on the only hosts that carry it must not turn the others
 * to the three-condition score.
 */
public final class QuarantineExistsTest {

    private static ObjectKpis.HostF host(String id, boolean carries, boolean propsFailed, boolean statsFailed) {
        ObjectKpis.HostF f = new ObjectKpis.HostF("host-" + id, "res-" + id,
                new Membership.Host("host-" + id, id, null, Boolean.TRUE, new java.util.HashSet<>(), new java.util.HashSet<>()));
        Map<String, String> p = new HashMap<>();
        p.put(StatKeys.HOST_CONNECTION, "connected");
        p.put(StatKeys.HOST_MAINTENANCE, "notInMaintenance");
        if (carries) p.put(StatKeys.HOST_QUARANTINE, "notInQuarantine");
        f.props = p;
        f.propsFailed = propsFailed;
        f.statsFailed = statsFailed;
        return f;
    }

    private static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        List<ObjectKpis.HostF> hosts = new ArrayList<>();
        // the only hosts that carry the property had their statistics fail; their properties were read
        hosts.add(host("1", true, false, true));
        hosts.add(host("2", true, false, true));
        hosts.add(host("3", false, false, false));
        check(ObjectKpis.quarantineExists(hosts), "properties read: the version has it, whatever the statistics did");
        boolean exists = ObjectKpis.quarantineExists(hosts);
        check(Availability.ofHost(1.0, hosts.get(2).props, exists) == 75.0,
                "a healthy host with no value scores 75 on a version that has the property, not 100");
        // a failed property read says nothing
        hosts.get(0).propsFailed = true;
        hosts.get(1).propsFailed = true;
        check(!ObjectKpis.quarantineExists(hosts), "no host with a successful property read carries it");
        hosts.get(1).propsFailed = false;
        check(ObjectKpis.quarantineExists(hosts), "one is enough");
        check(!ObjectKpis.quarantineExists(new ArrayList<>()), "no host");
        System.out.println("ok  quarantine existence is decided from the property reads");
    }
}
