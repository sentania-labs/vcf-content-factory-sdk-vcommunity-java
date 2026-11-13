package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.Quirks;

/** Test access to the PCI id text (kept for the old name). */
public final class HostCollectorAccess {

    private HostCollectorAccess() {}

    public static String hex16(String v) {
        return Quirks.pciId(v);
    }
}
