package com.vcfcf.adapters.vcommunity.vim;

/** The vCenter's {@code ServiceContent.about}: who it is. Fields the server did not send are null. */
public final class About {
    public final String instanceUuid;
    public final String fullName;
    public final String version;
    public final String apiVersion;
    public final String build;
    public final String patchLevel;

    public About(String instanceUuid, String fullName, String version, String apiVersion,
            String build, String patchLevel) {
        this.instanceUuid = instanceUuid;
        this.fullName = fullName;
        this.version = version;
        this.apiVersion = apiVersion;
        this.build = build;
        this.patchLevel = patchLevel;
    }

    /** One line for the log and Test Connection; the product name and version only. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append(fullName != null && !fullName.isEmpty() ? fullName : "vCenter");
        if (version != null && (fullName == null || !fullName.contains(version))) {
            b.append(' ').append(version);
        }
        if (apiVersion != null) b.append(" (API ").append(apiVersion).append(')');
        return b.toString();
    }
}
