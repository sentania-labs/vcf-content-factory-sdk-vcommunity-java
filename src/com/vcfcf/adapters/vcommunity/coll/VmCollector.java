package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.DetailedDataParser;
import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The virtual machine keys the property collector answers (design 4.5, rows
 * R81-R131, R256, R257). A pure function of one {@link BulkRow}. The guest
 * operations keys (R72-R80) come with the guest phase; the shares and
 * performance keys read Ops and come with the derived metrics.
 *
 * <p>The device list is the largest value a VM carries. Only the fields the
 * keys use of each device are kept while it streams past.
 */
public final class VmCollector {

    public static final String KIND = "VirtualMachine";
    public static final String READ = "R-VM";
    public static final int PAGE = 50;
    public static final String DEVICES = "config.hardware.device";
    public static final String EXTRA_CONFIG = "config.extraConfig";

    private VmCollector() {}

    private static final String V = Vals.P;

    private static final Set<String> SCSI_TYPES = new HashSet<>(Arrays.asList(
            "VirtualSCSIController", "ParaVirtualSCSIController", "VirtualLsiLogicController",
            "VirtualLsiLogicSASController", "VirtualBusLogicController"));
    private static final Set<String> ETHERNET_TYPES = new HashSet<>(Arrays.asList(
            "VirtualEthernetCard", "VirtualE1000", "VirtualE1000e", "VirtualPCNet32", "VirtualVmxnet",
            "VirtualVmxnet2", "VirtualVmxnet3", "VirtualVmxnet3Vrdma", "VirtualSriovEthernetCard"));

    /** The paths every VM read asks for, with their keys' rows in the key map. */
    static final String[] SCALARS = {
        "config.numaInfo.vnumaOnCpuHotaddExposed", "config.cpuAllocation.shares.level",
        "config.bootOptions.bootDelay", "config.bootOptions.bootRetryDelay",
        "config.bootOptions.bootRetryEnabled", "config.bootOptions.enterBIOSSetup",
        "config.changeVersion", "config.firmware", "config.flags.diskUuidEnabled",
        "config.latencySensitivity.level", "config.rebootPowerOff",
        "config.scheduledHardwareUpgradeInfo.scheduledHardwareUpgradeStatus",
        "config.scheduledHardwareUpgradeInfo.upgradePolicy",
        "config.scheduledHardwareUpgradeInfo.versionKey", "config.vmOpNotificationTimeout",
        "config.memoryAllocation.shares.level", "configStatus",
        "guest.guestState", "guest.appState", "guest.guestKernelCrashed", "guest.guestOperationsReady",
        "guest.guestStateChangeSupported", "guest.interactiveGuestOperationsReady",
        "config.tools.syncTimeWithHost", "config.tools.toolsUpgradePolicy",
        "runtime.consolidationNeeded", "runtime.dasVmProtection.dasProtected",
        "runtime.minRequiredEVCModeKey", "guest.customizationInfo.customizationStatus",
        "summary.quickStats.guestHeartbeatStatus", "guest.guestDetailedData", "summary.config.vmPathName"};

    /**
     * @param extraConfigWanted true when the VM Advanced Parameters list is not empty
     * @param extraKeys         the list, to keep only those entries of each VM's extra configuration
     * @param optionPaths       the VM Options paths that vCenter accepts (design 5.5)
     */
    public static RetrieveRequest request(boolean extraConfigWanted, Set<String> extraKeys,
            List<String> optionPaths) {
        RetrieveRequest r = new RetrieveRequest(READ, KIND, PAGE, null);
        for (String p : SCALARS) r.path(p);
        r.path(DEVICES, PathSpec.keepingInElements("key", "deviceInfo.label", "controllerKey", "busNumber",
                "scsiCtlrUnitNumber", "addressType", "macAddress", "connectable.startConnected",
                "backing.eagerlyScrub", "backing.split", "backing.writeThrough", "shares.level"));
        if (extraConfigWanted) {
            r.path(EXTRA_CONFIG, PathSpec.elements(e -> extraKeys.contains(e.childText("key"))));
        }
        // an entry that names a path already read keeps that path's pruning
        for (String p : optionPaths) {
            if (!r.paths().containsKey(p)) r.path(p);
        }
        return r;
    }

    /**
     * @param nonScalar receives each VM Options path whose value is not a single scalar here
     *                  (an object or an array: its Python text is not reproducible, DV-26)
     */
    public static ObjectOut collect(BulkRow row, List<String> advParams, List<String> optionPaths,
            Set<String> nonScalar) {
        ObjectOut o = new ObjectOut(KIND, row.obj.value);
        String c = V + "Configuration|";
        yv(o, row, c + "NUMA|Hot Add Exposed", "config.numaInfo.vnumaOnCpuHotaddExposed");
        yv(o, row, c + "CPU Allocation Share Level", "config.cpuAllocation.shares.level");
        yv(o, row, c + "Boot Options|Delay", "config.bootOptions.bootDelay");
        yv(o, row, c + "Boot Options|Retry Delay", "config.bootOptions.bootRetryDelay");
        yv(o, row, c + "Boot Options|Retry Enabled", "config.bootOptions.bootRetryEnabled");
        yv(o, row, c + "Boot Options|Enter BIOS Setup", "config.bootOptions.enterBIOSSetup");
        yv(o, row, c + "Change Version", "config.changeVersion");
        yv(o, row, c + "Firmware", "config.firmware");
        yv(o, row, c + "Flags|Disk UUID Enabled", "config.flags.diskUuidEnabled");
        yv(o, row, c + "Latency Sensitivity Level", "config.latencySensitivity.level");
        yv(o, row, c + "Reboot PowerOff", "config.rebootPowerOff");
        yv(o, row, c + "Hardware Upgrade|Status",
                "config.scheduledHardwareUpgradeInfo.scheduledHardwareUpgradeStatus");
        yv(o, row, c + "Hardware Upgrade|Policy", "config.scheduledHardwareUpgradeInfo.upgradePolicy");
        yv(o, row, c + "Hardware Upgrade|Target Version", "config.scheduledHardwareUpgradeInfo.versionKey");
        o.propY(c + "Op Notification Timeout", opNotificationTimeout(Vals.scalar(row, "config.vmOpNotificationTimeout")));
        yv(o, row, c + "Memory Allocation Share Level", "config.memoryAllocation.shares.level");
        yv(o, row, c + "Config Status", "configStatus");
        yv(o, row, V + "Guest|State", "guest.guestState");
        yv(o, row, V + "Guest|Application Status", "guest.appState");
        yv(o, row, V + "Guest|Kernel Crash State", "guest.guestKernelCrashed");
        yv(o, row, V + "Guest|Operation Ready", "guest.guestOperationsReady");
        yv(o, row, V + "Guest|State Change Support", "guest.guestStateChangeSupported");
        yv(o, row, V + "Guest|Interactive Guest", "guest.interactiveGuestOperationsReady");
        yv(o, row, V + "Guest|Tools Sync Time", "config.tools.syncTimeWithHost");
        yv(o, row, V + "Guest|Tools Upgrade Policy", "config.tools.toolsUpgradePolicy");
        yv(o, row, V + "Runtime|Consolidation Needed", "runtime.consolidationNeeded");
        yv(o, row, V + "Runtime|DAS protection", "runtime.dasVmProtection.dasProtected");
        yv(o, row, V + "Runtime|Min Required EVC Mode Key", "runtime.minRequiredEVCModeKey");
        o.propY(V + "Guest|Customization Status", customization(Vals.scalar(row, "guest.customizationInfo.customizationStatus")));
        yv(o, row, V + "Guest|Heartbeat Status", "summary.quickStats.guestHeartbeatStatus");
        detailed(o, Vals.scalar(row, "guest.guestDetailedData"));
        yv(o, row, V + "Summary|VM Path Name", "summary.config.vmPathName");
        devices(o, row.val(DEVICES));
        extra(o, row.val(EXTRA_CONFIG), advParams);
        options(o, row, optionPaths, nonScalar);
        return o;
    }

    /** A flagged scalar key: its value, or none (and counted). */
    private static void yv(ObjectOut o, BulkRow row, String key, String path) {
        o.propY(key, Vals.scalar(row, path));
    }

    /** {@code max(0, int(v))}; null when absent or not an integer. */
    static String opNotificationTimeout(String v) {
        String t = PyText.integer(v);
        if (t == null) return null;
        BigInteger n = new BigInteger(t);
        return n.signum() < 0 ? "0" : n.toString();
    }

    static String customization(String v) {
        if (v == null) return null;
        String t = PyText.strip(v);
        if (t.isEmpty()) return null;
        return t.replace("TOOLSDEPLOYPKG_", "");
    }

    private static void detailed(ObjectOut o, String text) {
        String[][] fields = {{"buildNumber", "Build Number"}, {"cpeString", "CPE String"},
            {"distroAddlVersion", "Distro Additional Version"}, {"distroName", "Distro Name"},
            {"distroVersion", "Distro Version"}, {"kernelVersion", "Kernel Version"},
            {"prettyName", "Pretty Name"}};
        Map<String, String> m = DetailedDataParser.parse(text);
        for (String[] f : fields) o.propY(V + "Guest|Detailed Data|" + f[1], m.get(f[0]));
    }

    private static void devices(ObjectOut o, XmlNode list) {
        if (list == null) {
            o.statY(V + "Configuration|SCSI Controllers|Count", null);
            return;
        }
        int scsi = 0;
        for (XmlNode d : Vals.items(list)) {
            String type = localType(d);
            if (isScsi(d, type)) {
                scsi++;
                String bus = Vals.integer(d, "busNumber");
                if (bus == null) {
                    o.withhold(1);
                } else {
                    o.prop(V + "Configuration|SCSI Controllers:" + bus + "|Type", controllerType(type));
                }
            } else if (isEthernet(d, type)) {
                nic(o, d);
            } else if ("VirtualDisk".equals(type)) {
                disk(o, d);
            }
        }
        o.stat(V + "Configuration|SCSI Controllers|Count", scsi);
    }

    private static String localType(XmlNode d) {
        String t = d.xsiType();
        if (t == null) return d.name;
        int c = t.indexOf(':');
        return c < 0 ? t : t.substring(c + 1);
    }

    private static boolean isScsi(XmlNode d, String type) {
        return SCSI_TYPES.contains(type) || (d.child("scsiCtlrUnitNumber") != null);
    }

    private static boolean isEthernet(XmlNode d, String type) {
        return ETHERNET_TYPES.contains(type) || d.child("addressType") != null
                || d.child("macAddress") != null;
    }

    /** The controller type as the original names it; any other type is its vim25 type name (DV-34). */
    static String controllerType(String type) {
        switch (type) {
            case "ParaVirtualSCSIController": return "VMware Paravirtual (PVSCSI)";
            case "VirtualLsiLogicSASController": return "LSI Logic SAS";
            case "VirtualLsiLogicController": return "LSI Logic Parallel";
            case "VirtualBusLogicController": return "BusLogic";
            default: return type;
        }
    }

    private static void nic(ObjectOut o, XmlNode d) {
        String label = Vals.stripped(Vals.str(d, "deviceInfo.label"));
        if (label == null) {
            o.withhold(2); // a card with no label has no instance name to publish under (DV-57)
            return;
        }
        String p = V + "Network|Network Adapters:" + label + "|";
        o.propY(p + "Type", Vals.nonBlank(Vals.str(d, "addressType")));
        o.propY(p + "Starts Connected", Vals.bool(d, "connectable.startConnected"));
    }

    private static void disk(ObjectOut o, XmlNode d) {
        String label = Vals.str(d, "deviceInfo.label");
        if (label == null || label.isEmpty()) {
            o.withhold(7);
            return;
        }
        String p = V + "Virtual Disk:" + label + "|";
        o.prop(p + "Label", label);
        o.prop(p + "Key", Vals.integer(d, "key"));
        o.propY(p + "Controller Key", Vals.integer(d, "controllerKey"));
        o.propY(p + "Eagerly Scrub", Vals.bool(d, "backing.eagerlyScrub"));
        o.propY(p + "Split", Vals.bool(d, "backing.split"));
        o.propY(p + "Write Through", Vals.bool(d, "backing.writeThrough"));
        o.propY(p + "Storage Allocation Share Level", Vals.nonBlank(Vals.str(d, "shares.level")));
    }

    private static void extra(ObjectOut o, XmlNode extra, List<String> keys) {
        if (extra == null || keys.isEmpty()) return;
        Map<String, String> m = new java.util.HashMap<>();
        for (XmlNode e : Vals.items(extra)) {
            String k = e.childText("key");
            String v = PyText.scalar(e.child("value"));
            if (k != null && v != null) m.put(k, v);
        }
        for (String k : keys) {
            String v = m.get(k);
            if (v != null) o.prop(V + "Configuration|Advanced Parameters|" + k, v);
        }
    }

    private static void options(ObjectOut o, BulkRow row, List<String> paths, Set<String> nonScalar) {
        for (String p : paths) {
            XmlNode v = row.val(p);
            if (v == null) continue; // unset on this VM: nothing
            String t = PyText.scalar(v);
            if (t != null) {
                o.prop(V + "Options|" + p, t);
            } else {
                nonScalar.add(p);
            }
        }
    }
}
