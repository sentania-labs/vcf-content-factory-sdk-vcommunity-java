package com.vcfcf.adapters.vcommunity.coll;

/** Test access to the package-private helpers of the collectors. */
public final class HostCollectorAccess {

    private HostCollectorAccess() {}

    public static String hex16(String v) {
        return HostCollector.hex16(v);
    }
}
