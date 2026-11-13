package com.vcfcf.adapters.vcommunity.health;

/**
 * The fixed ids of the failure catalogue (design 5.2). They appear in logs,
 * in {@code First Failure} and in tests, so they do not change. The ones that
 * carry a name (one per read, set or file) are built by the methods.
 */
public final class FailureCause {

    private FailureCause() {}

    public static final String LOGIN = "F-LOGIN";
    public static final String SOAP_INVALID_PROPERTY = "F-SOAP-INVALIDPROP";
    public static final String HOST_INSTALL_DATE = "F-HOST-INSTALLDATE";
    public static final String HOST_PACKAGES = "F-HOST-PACKAGES";
    public static final String REST_LIST = "F-REST-LIST";
    public static final String REST_LIB = "F-REST-LIB";
    public static final String REST_ITEM = "F-REST-ITEM";
    public static final String OPS_HISTORY = "F-OPS-HISTORY";
    public static final String OPS_REL = "F-OPS-REL";
    public static final String CERT_PROBE = "F-CERT-PROBE";
    public static final String CERT_READBACK = "F-CERT-READBACK";
    public static final String PUSH = "F-PUSH";
    public static final String EVENTS = "F-EVENTS";
    public static final String DEADLINE = "F-DEADLINE";
    public static final String TLS_CONFIG = "F-TLS-CONFIG";

    /** A vim25 read, by its read id ({@code R-CL}, {@code R-HO}, ...). */
    public static String soap(String read) {
        return "F-SOAP-" + read;
    }

    /** The Ops resource map of one VMWARE kind. */
    public static String opsMap(String kind) {
        return "F-OPS-MAP-" + kind;
    }

    public static String opsStats(String set) {
        return "F-OPS-STATS-" + set;
    }

    public static String opsProps(String set) {
        return "F-OPS-PROPS-" + set;
    }

    /** A check-list file, by its list id. */
    public static String checklist(String file) {
        return "F-CHECKLIST-" + file;
    }

    /** A guest-operations step. */
    public static String guest(String step) {
        return "F-GUEST-" + step;
    }
}
