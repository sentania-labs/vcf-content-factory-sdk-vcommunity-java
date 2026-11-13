package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

/**
 * The cluster's own configuration keys (design 4.3, rows R132-R152 and R220
 * that vim25 answers directly). A pure function of one {@link BulkRow}: a path
 * that did not come back, a feature that is switched off, and a value that
 * does not parse are all "no value", never a placeholder (5.3 mechanisms 1, 2,
 * 4, 9). Only the part of the cluster's configuration these keys use is kept
 * while the answer streams past.
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
        o.propY(CFG + "Configuration Status", Vals.scalar(row, "configStatus"));
        o.propY(CFG + "Overall Status", Vals.scalar(row, "overallStatus"));
        XmlNode summary = row.val("summary");
        o.statY(CFG + "Number of Effective Hosts", intOf(Vals.integer(summary, "numEffectiveHosts")));
        ha(o, row.val("configuration.dasConfig"));
        boolean drsOn = drs(o, row.val("configuration.drsConfig"), row.val("configurationEx"));
        evc(o, summary);
        drsScore(o, summary, drsOn);
        dpm(o, row.val("configurationEx"));
        return o;
    }

    private static void ha(ObjectOut o, XmlNode das) {
        String[] keys = {"Host Monitoring", "Response \\ Host Isolation",
            "Response \\ Default VM Restart Priority", "Response \\ Datastore APD",
            "Response \\ Datastore PDL", "VM Monitoring", "Heartbeat Datastore", "Failure Interval",
            "Max Failure Window", "Max Failures", "Minimum Up Time"};
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
        // High availability switched off: none of the settings means anything, so none is sent.
        boolean off = "False".equals(enabled);
        // Host monitoring disabled: the five response settings are not in force.
        boolean noResponses = "True".equals(enabled) && "disabled".equals(vals[0]);
        for (int i = 0; i < keys.length; i++) {
            boolean suppressed = off || (noResponses && i < 5);
            o.propY(HA + keys[i], suppressed ? null : vals[i]);
        }
    }

    /** @return whether DRS is on (true only when the cluster says so) */
    private static boolean drs(ObjectOut o, XmlNode drsCfg, XmlNode ex) {
        boolean on = "True".equals(Vals.bool(drsCfg, "enabled"));
        XmlNode proactive = Vals.at(ex, "proactiveDrsConfig");
        // Without the proactive configuration the original raises before it sends any of the four.
        String proactiveEnabled = proactive == null ? null : Vals.bool(proactive, "enabled");
        String maxVcpus = null;
        boolean bad = proactive == null;
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
        boolean send = on && !bad;
        o.propY(DRS + "Proactive DRS", send ? proactiveEnabled : null);
        o.propY(DRS + "Scale Descendants Shares", send ? scale : null);
        o.propY(DRS + "CPU Over-Commitment", send ? maxVcpus : null);
        o.propY(DRS + "Virtual Machine Automation Enabled", send ? overrides : null);
        return on;
    }

    private static void evc(ObjectOut o, XmlNode summary) {
        if (summary == null) {
            o.propY(EVC + "Enabled", null);
            o.propY(EVC + "Mode", null);
            return;
        }
        String mode = Vals.str(summary, "currentEVCModeKey");
        // A summary that was read and names no mode means EVC is off: a reading, kept (Q2). A mode
        // that is spaces is a mode, as for the original (a non-empty string is true there).
        boolean on = mode != null && !mode.isEmpty();
        o.prop(EVC + "Enabled", on ? "True" : "False");
        o.propY(EVC + "Mode", on ? mode : null);
    }

    private static void drsScore(ObjectOut o, XmlNode summary, boolean drsOn) {
        Integer score = null;
        String s = Vals.integer(summary, "drsScore");
        if (s != null) {
            try {
                score = Integer.valueOf(s);
            } catch (NumberFormatException e) {
                score = null;
            }
        }
        // DRS off, a negative score or none at all: vCenter has no score to give (DV-16).
        o.statY(DRS + "DRS Score", drsOn && score != null && score >= 0 ? Double.valueOf(score) : null);
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
