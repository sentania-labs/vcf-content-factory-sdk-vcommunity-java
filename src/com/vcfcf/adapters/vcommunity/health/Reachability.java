package com.vcfcf.adapters.vcommunity.health;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Unreachable (DOWN) versus failed (ERROR): walks the cause chain, cycle safe.
 * A target that cannot be reached is a different state from one that refused
 * the credentials or presented the wrong certificate.
 */
public final class Reachability {

    private Reachability() {}

    public static boolean isUnreachable(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof java.net.UnknownHostException
                    || c instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }
}
