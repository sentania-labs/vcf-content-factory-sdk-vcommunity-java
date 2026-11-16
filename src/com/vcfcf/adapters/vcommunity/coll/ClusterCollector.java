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
 * missing are not sent, with no placeholder of any kind (DV-112). A path that vCenter
 * refused or could not read sends nothing and is counted. Only
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
        // a value vCenter answered without is not sent (DV-112); one that could not be read is withheld and counted
        Vals.prop(o, row, CFG + "Configuration Status", "configStatus");
        Vals.prop(o, row, CFG + "Overall Status", "overallStatus");
        XmlNode summary = row.val("summary");
        boolean summaryRead = summary != null || row.isUnset("summary");
        Double effective = intOf(Vals.integer(summary, "numEffectiveHosts"));
        if (effective != null) {
            o.stat(CFG + "Number of Effective Hosts", effective);
        } else if (!summaryRead) {
            o.withhold(1);
        }
        ha(o, row, row.val("configuration.dasConfig"));
        XmlNode drsCfg = row.val("configuration.drsConfig");
        // a DRS configuration that could not be read says nothing about whether DRS is on: no DRS key, no score
        boolean drsRead = drsCfg != null || row.isUnset("configuration.drsConfig");
        boolean drsOn = drs(o, row, drsCfg, row.val("configurationEx"), drsRead);
        evc(o, summary, summaryRead);
        drsScore(o, summary, summaryRead, drsOn, drsRead);
        dpm(o, row.val("configurationEx"));
        return o;
    }

    /**
     * The eleven HA keys: the value, or nothing for a setting that is unset, that does not apply while HA is off
     * (and the five response settings while host monitoring is disabled).
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
            if (!suppressed) o.prop(HA + keys[i], vals[i]);
        }
    }

    /** @return whether DRS is on (true only when the cluster says so) */
    private static boolean drs(ObjectOut o, BulkRow row, XmlNode drsCfg, XmlNode ex, boolean drsRead) {
        boolean on = "True".equals(Vals.bool(drsCfg, "enabled"));
        if (!drsRead) {
            o.withhold(4); // the DRS configuration could not be read: nothing can be said
            return on;
        }
        if (!on) return false; // DRS is off: its four settings do not apply and are not sent
        boolean exRead = ex != null || row.isUnset("configurationEx");
        XmlNode proactive = Vals.at(ex, "proactiveDrsConfig");
        if (proactive != null) {
            o.prop(DRS + "Proactive DRS", Vals.bool(proactive, "enabled"));
        } else if (!exRead) {
            o.withhold(1);
        }
        o.prop(DRS + "Scale Descendants Shares", Vals.str(drsCfg, "scaleDescendantsShares"));
        o.prop(DRS + "Virtual Machine Automation Enabled", Vals.bool(drsCfg, "enableVmBehaviorOverrides"));
        if (!exRead) {
            o.withhold(1);
        } else {
            for (XmlNode opt : Vals.items(Vals.at(ex, "drsConfig"))) {
                if ("option".equals(opt.name) && "MaxVcpusPerCore".equals(opt.childText("key"))) {
                    String v = pyInt(PyText.scalar(opt.child("value")));
                    if (v == null) o.withhold(1); else o.prop(DRS + "CPU Over-Commitment", v);
                    break;
                }
            }
        }
        return true;
    }

    private static void evc(ObjectOut o, XmlNode summary, boolean summaryRead) {
        if (!summaryRead) {
            o.withhold(2); // the summary could not be read
            return;
        }
        String mode = Vals.str(summary, "currentEVCModeKey");
        // A summary that was read and names no mode means EVC is off: a reading, kept (Q2), and there is no mode to
        // send. A mode that is spaces is a mode (a non-empty string is true there).
        boolean on = mode != null && !mode.isEmpty();
        o.prop(EVC + "Enabled", on ? "True" : "False");
        if (on) o.prop(EVC + "Mode", mode);
    }

    /**
     * The DRS score, 0 to 100, where 0 is the worst. It is published only when DRS is on and vCenter gave a
     * score (Q9, DV-111): with DRS off there is nothing to score, and a score below zero is outside the scale (we
     * take it to mean "no score yet"; that is an assumption, not a documented value), so neither is sent as a 0 that
     * would read as the worst balance. A score of 0 vCenter really gave stays.
     * A summary or DRS setting that could not be read is withheld and counted, as before.
     */
    private static void drsScore(ObjectOut o, XmlNode summary, boolean summaryRead, boolean drsOn, boolean drsRead) {
        Integer score = null;
        String s = Vals.integer(summary, "drsScore");
        if (s != null) {
            try {
                score = Integer.valueOf(s);
            } catch (NumberFormatException e) {
                score = null;
            }
        }
        if (!summaryRead || !drsRead) {
            o.withhold(1); // could not be read
        } else if (!drsOn || score == null) {
            return; // DRS is off, or vCenter gave no score: nothing to send
        } else if (score < 0) {
            return; // below zero is outside the scale: no score (assumed to mean "not yet scored")
        } else {
            o.stat(DRS + "DRS Score", score);
        }
    }

    private static void dpm(ObjectOut o, XmlNode ex) {
        XmlNode dpm = Vals.at(ex, "dpmConfigInfo");
        if (dpm == null) return;
        o.prop(CFG + "DPM|Host Power Action Rate", Vals.integer(dpm, "hostPowerActionRate"));
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
