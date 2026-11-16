package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

/**
 * The cluster's own configuration keys (design 4.3, rows R132-R152 and R220
 * that vim25 answers directly). A pure function of one {@link BulkRow}. A value
 * that was read and is unset, a feature that is switched off and a count that is
 * missing are published as the original prints them (Q9): the text {@code None}
 * for an unset property, {@code null} for HA and DRS settings that are off or
 * missing, 0 for a DRS score while DRS is off. A path that vCenter refused or
 * could not read is never one of these: it sends nothing and is counted. Only
 * the part of the cluster's configuration these keys use is kept while the
 * answer streams past.
 */
public final class ClusterCollector {

    public static final String KIND = "ClusterComputeResource";
    public static final String READ = "R-CL";
    public static final int PAGE = 10;

    private ClusterCollector() {}

    private static final String HA = Vals.P + "Cluster Configuration|vSphere HA|";
    private static final String DRS = Vals.P + "Cluster Configuration|Distributed Resource Scheduler|";
    private static final String EVC = Vals.P + "Cluster Configuration|EVC|";
    private static final String CFG = Vals.P + "Cluster Configuration|";

    /** The read: every cluster, a few at a time. */
    public static RetrieveRequest request() {
        return new RetrieveRequest(READ, KIND, PAGE, null)
                .path("configStatus").path("overallStatus")
                .path("summary", PathSpec.keeping("drsScore", "currentEVCModeKey", "numEffectiveHosts"))
                .path("configuration.dasConfig", PathSpec.keeping("enabled", "hostMonitoring",
                        "vmMonitoring", "hBDatastoreCandidatePolicy",
                        "defaultVmSettings.isolationResponse", "defaultVmSettings.restartPriority",
                        "defaultVmSettings.vmComponentProtectionSettings.vmStorageProtectionForAPD",
                        "defaultVmSettings.vmComponentProtectionSettings.vmStorageProtectionForPDL",
                        "defaultVmSettings.vmToolsMonitoringSettings.failureInterval",
                        "defaultVmSettings.vmToolsMonitoringSettings.maxFailureWindow",
                        "defaultVmSettings.vmToolsMonitoringSettings.maxFailures",
                        "defaultVmSettings.vmToolsMonitoringSettings.minUpTime"))
                .path("configuration.drsConfig", PathSpec.keeping("enabled",
                        "enableVmBehaviorOverrides", "scaleDescendantsShares"))
                .path("configurationEx", PathSpec.keeping("dpmConfigInfo.hostPowerActionRate",
                        "proactiveDrsConfig.enabled", "drsConfig.option"));
    }

    public static ObjectOut collect(BulkRow row) {
        ObjectOut o = new ObjectOut(KIND, row.obj.value);
        o.propY(CFG + "Configuration Status", Vals.scalarOrNone(row, "configStatus"));
        o.propY(CFG + "Overall Status", Vals.scalarOrNone(row, "overallStatus"));
        XmlNode summary = row.val("summary");
        boolean summaryRead = summary != null || row.isUnset("summary");
        // no effective-host count on a summary that was read: the original's default, 0 (Q9)
        Double effective = intOf(Vals.integer(summary, "numEffectiveHosts"));
        o.statY(CFG + "Number of Effective Hosts", effective != null ? effective
                : summaryRead && Quirks.UNSET_AS_UPSTREAM ? Double.valueOf(0) : null);
        ha(o, row, row.val("configuration.dasConfig"));
        XmlNode drsCfg = row.val("configuration.drsConfig");
        // a DRS configuration that could not be read says nothing about whether DRS is on: no DRS key, no score
        boolean drsRead = drsCfg != null || row.isUnset("configuration.drsConfig");
        boolean drsOn = drs(o, drsCfg, row.val("configurationEx"), drsRead);
        evc(o, summary, summaryRead);
        drsScore(o, summary, drsOn, drsRead);
        dpm(o, row.val("configurationEx"));
        return o;
    }

    /**
     * The eleven HA keys, as the original prints them: the text {@code null} while HA is off (and the
     * five response settings while host monitoring is disabled), else the value, else {@code None}
     * for a setting that is unset (Q9).
     */
    private static void ha(ObjectOut o, BulkRow row, XmlNode das) {
        String[] keys = {"Host Monitoring", "Response \\ Host Isolation",
            "Response \\ Default VM Restart Priority", "Response \\ Datastore APD",
            "Response \\ Datastore PDL", "VM Monitoring", "Heartbeat Datastore", "Failure Interval",
            "Max Failure Window", "Max Failures", "Minimum Up Time"};
        if (das == null && !row.isUnset("configuration.dasConfig")) {
            o.withhold(keys.length); // the configuration could not be read
            return;
        }
        String vmts = "defaultVmSettings.vmToolsMonitoringSettings.";
        String[] vals = {
            Vals.str(das, "hostMonitoring"),
            Vals.str(das, "defaultVmSettings.isolationResponse"),
            Vals.str(das, "defaultVmSettings.restartPriority"),
            Vals.str(das, "defaultVmSettings.vmComponentProtectionSettings.vmStorageProtectionForAPD"),
            Vals.str(das, "defaultVmSettings.vmComponentProtectionSettings.vmStorageProtectionForPDL"),
            Vals.str(das, "vmMonitoring"),
            Vals.str(das, "hBDatastoreCandidatePolicy"),
            Vals.integer(das, vmts + "failureInterval"),
            Vals.integer(das, vmts + "maxFailureWindow"),
            Vals.integer(das, vmts + "maxFailures"),
            Vals.integer(das, vmts + "minUpTime")};
        String enabled = Vals.bool(das, "enabled");
        // High availability switched off: none of the settings means anything, the original says "null".
        boolean off = "False".equals(enabled);
        // Host monitoring disabled: the five response settings are not in force.
        boolean noResponses = "True".equals(enabled) && "disabled".equals(vals[0]);
        for (int i = 0; i < keys.length; i++) {
            boolean suppressed = off || (noResponses && i < 5);
            o.propY(HA + keys[i], suppressed ? Quirks.orNull(null) : Quirks.orNone(vals[i]));
        }
    }

    /** @return whether DRS is on (true only when the cluster says so) */
    private static boolean drs(ObjectOut o, XmlNode drsCfg, XmlNode ex, boolean drsRead) {
        boolean on = "True".equals(Vals.bool(drsCfg, "enabled"));
        XmlNode proactive = Vals.at(ex, "proactiveDrsConfig");
        // Without the proactive configuration the original raises before it sends any of the four.
        String proactiveEnabled = proactive == null ? null : Vals.bool(proactive, "enabled");
        String maxVcpus = null;
        boolean bad = proactive == null || !drsRead;
        for (XmlNode opt : Vals.items(Vals.at(ex, "drsConfig"))) {
            if ("option".equals(opt.name) && "MaxVcpusPerCore".equals(opt.childText("key"))) {
                String v = pyInt(PyText.scalar(opt.child("value")));
                if (v == null) bad = true;
                maxVcpus = v;
                break;
            }
        }
        String scale = Vals.str(drsCfg, "scaleDescendantsShares");
        String overrides = Vals.bool(drsCfg, "enableVmBehaviorOverrides");
        // DRS off, or a value DRS does not have: the original's text "null" (Q9)
        o.propY(DRS + "Proactive DRS", bad ? null : Quirks.orNull(on ? proactiveEnabled : null));
        o.propY(DRS + "Scale Descendants Shares", bad ? null : Quirks.orNull(on ? scale : null));
        o.propY(DRS + "CPU Over-Commitment", bad ? null : Quirks.orNull(on ? maxVcpus : null));
        o.propY(DRS + "Virtual Machine Automation Enabled", bad ? null : Quirks.orNull(on ? overrides : null));
        return on;
    }

    private static void evc(ObjectOut o, XmlNode summary, boolean summaryRead) {
        if (!summaryRead) {
            o.propY(EVC + "Enabled", null);
            o.propY(EVC + "Mode", null);
            return;
        }
        String mode = Vals.str(summary, "currentEVCModeKey");
        // A summary that was read and names no mode means EVC is off: a reading, kept (Q2), and the mode is
        // the original's "null" (Q9). A mode that is spaces is a mode (a non-empty string is true there).
        boolean on = mode != null && !mode.isEmpty();
        o.prop(EVC + "Enabled", on ? "True" : "False");
        o.propY(EVC + "Mode", on ? mode : Quirks.orNull(null));
    }

    /**
     * The original: 0 while DRS is off, 0 for a negative score, the score otherwise; no summary, or a
     * score that is unset while DRS is on, raises there and sends nothing (Q9 for the zeros).
     */
    private static void drsScore(ObjectOut o, XmlNode summary, boolean drsOn, boolean drsRead) {
        Integer score = null;
        String s = Vals.integer(summary, "drsScore");
        if (s != null) {
            try {
                score = Integer.valueOf(s);
            } catch (NumberFormatException e) {
                score = null;
            }
        }
        Double zero = Quirks.UNSET_AS_UPSTREAM ? Double.valueOf(0) : null;
        Double v;
        if (summary == null || !drsRead) {
            v = null;
        } else if (!drsOn) {
            v = zero;
        } else if (score == null) {
            v = null;
        } else {
            v = score < 0 ? zero : Double.valueOf(score);
        }
        o.statY(DRS + "DRS Score", v);
    }

    private static void dpm(ObjectOut o, XmlNode ex) {
        XmlNode dpm = Vals.at(ex, "dpmConfigInfo");
        if (dpm == null) return;
        o.prop(CFG + "DPM|Host Power Action Rate", Quirks.orNone(Vals.integer(dpm, "hostPowerActionRate")));
    }

    private static Double intOf(String s) {
        if (s == null) return null;
        try {
            return Double.valueOf(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Python {@code int(text)} then {@code str()}: whitespace around digits is fine, anything else is not. */
    static String pyInt(String text) {
        if (text == null) return null;
        return PyText.integer(PyText.strip(text));
    }
}
