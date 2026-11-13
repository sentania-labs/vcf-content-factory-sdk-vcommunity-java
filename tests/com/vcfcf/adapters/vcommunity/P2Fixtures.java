package com.vcfcf.adapters.vcommunity;

import static com.vcfcf.adapters.vcommunity.SoapFx.arr;
import static com.vcfcf.adapters.vcommunity.SoapFx.e;
import static com.vcfcf.adapters.vcommunity.SoapFx.el;
import static com.vcfcf.adapters.vcommunity.SoapFx.typed;

import com.vcfcf.adapters.vcommunity.SoapFx.Obj;

/**
 * The hand-built vCenter the property tests read: clusters, hosts, virtual
 * machines and datastores of every shape the collectors meet, the complete
 * ones, the sparse ones with their optional parts missing, and the ones whose
 * values are present but empty. Every value is written out, so a test can say
 * exactly what each key must carry.
 */
final class P2Fixtures {

    private P2Fixtures() {}

    static final String ADV_ITEM = "Net.TcpipHeapSize";
    static final String PKG_ITEM = "esx-base";

    // ------------------------------------------------------------------ clusters

    /** HA and DRS on, EVC on, DPM configured; the configuration carries a lot the keys never use. */
    static Obj clusterFull(String moid, String name) {
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            noise.append(el("dasVmConfig", e("key", "vm-" + i) + el("dasSettings", e("restartPriority", "high"))));
            noise.append(el("rule", typed("rule", "ClusterAntiAffinityRuleSpec", e("name", "rule" + i))));
        }
        Obj c = new Obj("ClusterComputeResource", moid).name(name);
        c.put("configStatus", SoapFx.en("ManagedEntityStatus", "green"));
        c.put("overallStatus", SoapFx.en("ManagedEntityStatus", "yellow"));
        c.put("summary", SoapFx.obj("ClusterComputeResourceSummary",
                e("totalCpu", "100000") + e("numHosts", "3") + e("numEffectiveHosts", "2")
                + e("currentEVCModeKey", "intel-skylake") + e("drsScore", "87")
                + el("usageSummary", e("cpuDemandMhz", "10"))));
        c.put("configuration.dasConfig", SoapFx.obj("ClusterDasConfigInfo",
                e("enabled", "true") + e("hostMonitoring", "enabled") + e("vmMonitoring", "vmMonitoringOnly")
                + e("admissionControlEnabled", "true") + e("hBDatastoreCandidatePolicy", "allFeasibleDsWithUserPreference")
                + el("defaultVmSettings", e("restartPriority", "medium") + e("isolationResponse", "none")
                        + el("vmToolsMonitoringSettings", e("enabled", "true") + e("failureInterval", "30")
                                + e("minUpTime", "120") + e("maxFailures", "3") + e("maxFailureWindow", "3600"))
                        + el("vmComponentProtectionSettings", e("vmStorageProtectionForAPD", "warning")
                                + e("vmStorageProtectionForPDL", "restartAggressive")))));
        c.put("configuration.drsConfig", SoapFx.obj("ClusterDrsConfigInfo",
                e("enabled", "true") + e("enableVmBehaviorOverrides", "true") + e("defaultVmBehavior", "fullyAutomated")
                + e("scaleDescendantsShares", "disabled")));
        c.put("configurationEx", SoapFx.obj("ClusterConfigInfoEx",
                el("dpmConfigInfo", e("enabled", "false") + e("hostPowerActionRate", "3"))
                + el("proactiveDrsConfig", e("enabled", "false"))
                + el("drsConfig", e("enabled", "true") + typed("option", "OptionValue",
                        e("key", "MaxVcpusPerCore") + "<value xsi:type=\"xsd:string\">4</value>")
                        + typed("option", "OptionValue", e("key", "Other") + "<value xsi:type=\"xsd:string\">x</value>"))
                + noise));
        return c;
    }

    /** HA off, DRS off, no EVC, no DPM, no effective-host count. */
    static Obj clusterOff(String moid, String name) {
        Obj c = new Obj("ClusterComputeResource", moid).name(name);
        c.put("configStatus", SoapFx.en("ManagedEntityStatus", "green"));
        c.put("overallStatus", SoapFx.en("ManagedEntityStatus", "green"));
        c.put("summary", SoapFx.obj("ClusterComputeResourceSummary", e("numHosts", "1") + e("drsScore", "55")));
        c.put("configuration.dasConfig", SoapFx.obj("ClusterDasConfigInfo", e("enabled", "false")
                + e("hostMonitoring", "enabled") + el("defaultVmSettings", e("restartPriority", "medium"))));
        c.put("configuration.drsConfig", SoapFx.obj("ClusterDrsConfigInfo", e("enabled", "false")
                + e("enableVmBehaviorOverrides", "true") + e("scaleDescendantsShares", "disabled")));
        c.put("configurationEx", SoapFx.obj("ClusterConfigInfoEx", el("proactiveDrsConfig", e("enabled", "true"))
                + el("drsConfig", e("enabled", "false"))));
        return c;
    }

    /** HA on with host monitoring disabled; DRS on with no over-commitment option; negative score. */
    static Obj clusterNoMonitoring(String moid, String name) {
        Obj c = new Obj("ClusterComputeResource", moid).name(name);
        c.put("summary", SoapFx.obj("ClusterComputeResourceSummary", e("numEffectiveHosts", "0") + e("drsScore", "-1")));
        c.put("configuration.dasConfig", SoapFx.obj("ClusterDasConfigInfo", e("enabled", "true")
                + e("hostMonitoring", "disabled") + e("vmMonitoring", "vmMonitoringDisabled")
                + e("hBDatastoreCandidatePolicy", "userSpecifiedDs")
                + el("defaultVmSettings", e("restartPriority", "low") + e("isolationResponse", "powerOff")
                        + el("vmToolsMonitoringSettings", e("failureInterval", "60")))));
        c.put("configuration.drsConfig", SoapFx.obj("ClusterDrsConfigInfo", e("enabled", "true")
                + e("enableVmBehaviorOverrides", "false") + e("scaleDescendantsShares", "scaleCpuAndMemoryShares")));
        c.put("configurationEx", SoapFx.obj("ClusterConfigInfoEx", el("dpmConfigInfo", e("enabled", "true"))
                + el("proactiveDrsConfig", e("enabled", "true")) + el("drsConfig", e("enabled", "true"))));
        return c;
    }

    /** Nothing but a name: every optional structure is missing. */
    static Obj clusterBare(String moid, String name) {
        return new Obj("ClusterComputeResource", moid).name(name);
    }

    /** DRS on, but its proactive configuration is missing (the original raises and sends none of the four). */
    static Obj clusterNoProactive(String moid, String name) {
        Obj c = new Obj("ClusterComputeResource", moid).name(name);
        c.put("configuration.drsConfig", SoapFx.obj("ClusterDrsConfigInfo", e("enabled", "true")
                + e("scaleDescendantsShares", "disabled")));
        c.put("configurationEx", SoapFx.obj("ClusterConfigInfoEx", el("drsConfig", e("enabled", "true"))));
        return c;
    }

    // ------------------------------------------------------------------ hosts

    private static String nic(String dev, String pci, boolean up, boolean full, String driver, String fw,
            String mac, Boolean wol) {
        return el("PhysicalNic", e("key", "key-vim.host.PhysicalNic-" + dev) + e("device", dev)
                + (pci == null ? "" : e("pci", pci)) + e("driver", "nmlx5_core")
                + (driver == null ? "" : e("driverVersion", driver))
                + (fw == null ? "" : e("firmwareVersion", fw))
                + (up ? el("linkSpeed", e("speedMb", "25000") + e("duplex", String.valueOf(full))) : "")
                + (wol == null ? "" : e("wakeOnLanSupported", String.valueOf(wol)))
                + (mac == null ? "" : e("mac", mac)));
    }

    private static String pci(String id, long vid, long did, long svid, String vendor, String device) {
        return el("HostPciDevice", e("id", id) + e("classId", "512") + e("vendorId", String.valueOf(vid))
                + e("subVendorId", String.valueOf(svid)) + (vendor == null ? "" : e("vendorName", vendor))
                + e("deviceId", String.valueOf(did)) + (device == null ? "" : e("deviceName", device)));
    }

    private static String optionValue(String key, String xsiType, String v) {
        return typed("OptionValue", "OptionValue", e("key", key)
                + "<value xsi:type=\"" + xsiType + "\">" + SoapFx.esc(v) + "</value>");
    }

    /** A connected host with everything, two NICs (one up, one down), three adapters and a long option list. */
    static Obj hostFull(String moid, String name, String imgMgr) {
        Obj h = new Obj("HostSystem", moid).name(name);
        h.put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", "connected"));
        h.put("runtime.bootTime", SoapFx.date("2025-03-04T05:06:07.5Z"));
        h.put("configStatus", SoapFx.en("ManagedEntityStatus", "green"));
        h.put("summary.maxEVCModeKey", SoapFx.str("intel-icelake"));
        h.put("config.network.pnic", arr("ArrayOfPhysicalNic",
                nic("vmnic0", "0000:3b:00.0", true, true, "4.23.0.66", "16.35.2000", "aa:bb:cc:00:00:01", true)
                + nic("vmnic1", "0000:3b:00.1", false, false, null, "16.35.2000", "aa:bb:cc:00:00:02", null)
                + nic("vmnic2", "0000:99:00.0", true, false, "1.0", null, null, false)));
        h.put("config.network.dnsConfig", SoapFx.obj("HostDnsConfig", e("dhcp", "false") + e("hostName", "esx1")
                + e("domainName", "lab.test") + e("address", "10.0.0.2")
                + e("searchDomain", "lab.test") + e("searchDomain", "  ") + e("searchDomain", " corp.test ")));
        h.put("config.dateTimeInfo.timeZone.name", SoapFx.str("UTC"));
        h.put("config.dateTimeInfo.timeZone.description", SoapFx.str("UTC"));
        h.put("config.dateTimeInfo.timeZone.gmtOffset", SoapFx.intv(0));
        h.put("config.storageDevice.hostBusAdapter", arr("ArrayOfHostHostBusAdapter",
                typed("HostHostBusAdapter", "HostFibreChannelHba", e("key", "key-vim.host.FibreChannelHba-vmhba2")
                        + e("device", "vmhba2") + e("bus", "59") + e("status", "online") + e("model", " QLE2692 ")
                        + e("driver", "qlnativefc") + e("pci", "0000:3b:00.0") + e("storageProtocol", "fc"))
                + typed("HostHostBusAdapter", "HostBlockHba", e("key", "key-vim.host.BlockHba-vmhba64")
                        + e("device", "null") + e("bus", "0") + e("status", "unknown") + e("model", "Block")
                        + e("storageProtocol", "scsi"))
                + typed("HostHostBusAdapter", "HostParallelScsiHba", e("key", "key-vim.host.ParallelScsiHba-vmhba1")
                        + e("device", "vmhba1") + e("bus", "2") + e("status", "unknown") + e("model", "  ")
                        + e("driver", "lsi_mr3"))));
        h.put("hardware.biosInfo.vendor", SoapFx.str("Dell Inc."));
        h.put("hardware.pciDevice", arr("ArrayOfHostPciDevice",
                pci("0000:3b:00.0", -32634, 5384, -32634, "Intel Corporation", "Ethernet Controller")
                + pci("0000:3b:00.1", 5555, 6666, 7777, "Mellanox", null)
                + pci("0000:00:01.0", 1, 2, 3, "Other", "Other")));
        h.put("capability.storageVMotionSupported", SoapFx.bool(true));
        h.put("configManager.imageConfigManager", SoapFx.moref("HostImageConfigManager", imgMgr));
        StringBuilder opts = new StringBuilder();
        for (int i = 0; i < 300; i++) opts.append(optionValue("Misc.Noise" + i, "xsd:long", String.valueOf(i)));
        opts.append(optionValue("VMFS3.UseATSForHBOnVMFS5", "xsd:long", "1"));
        opts.append(optionValue("VMFS3.HardwareAcceleratedLocking", "xsd:long", "1"));
        opts.append(optionValue(ADV_ITEM, "xsd:long", "64"));
        opts.append(optionValue("Net.Bool", "xsd:boolean", "true"));
        opts.append(optionValue("Net.Dbl", "xsd:double", "0.1"));
        opts.append(optionValue("Net.Text", "xsd:string", "  padded  "));
        h.put("config.option", arr("ArrayOfOptionValue", opts.toString()));
        return h;
    }

    /** Connected, with almost nothing returned. */
    static Obj hostSparse(String moid, String name) {
        Obj h = new Obj("HostSystem", moid).name(name);
        h.put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", "connected"));
        h.put("config.option", arr("ArrayOfOptionValue", ""));
        return h;
    }

    /** Connected, with an empty adapter list, empty NIC list and an empty search domain list. */
    static Obj hostEmptyLists(String moid, String name) {
        Obj h = new Obj("HostSystem", moid).name(name);
        h.put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", "connected"));
        h.put("config.storageDevice.hostBusAdapter", arr("ArrayOfHostHostBusAdapter", ""));
        h.put("config.network.pnic", arr("ArrayOfPhysicalNic", ""));
        h.put("config.network.dnsConfig", SoapFx.obj("HostDnsConfig", e("dhcp", "true")));
        h.put("config.option", arr("ArrayOfOptionValue", ""));
        return h;
    }

    static Obj hostDisconnected(String moid, String name) {
        Obj h = hostFull(moid, name, "imgmgr-x");
        h.put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", "disconnected"));
        return h;
    }

    // ------------------------------------------------------------------ virtual machines

    private static String scsi(String type, int key, int bus, String label) {
        return typed("VirtualDevice", type, e("key", String.valueOf(key))
                + el("deviceInfo", e("label", label) + e("summary", "x")) + e("busNumber", String.valueOf(bus))
                + e("sharedBus", "noSharing") + e("scsiCtlrUnitNumber", "7") + e("device", "2000"));
    }

    private static String vnic(String type, int key, String label, Boolean startConnected, String addrType) {
        return typed("VirtualDevice", type, e("key", String.valueOf(key))
                + (label == null ? "" : el("deviceInfo", e("label", label) + e("summary", "VM Network")))
                + el("backing", e("deviceName", "VM Network"))
                + (startConnected == null ? "" : el("connectable", e("startConnected", String.valueOf(startConnected))
                        + e("allowGuestControl", "true") + e("connected", "true")))
                + (addrType == null ? "" : e("addressType", addrType)) + e("macAddress", "00:50:56:aa:bb:cc"));
    }

    private static String vdisk(int key, int ctl, String label, String backing, String shares) {
        return typed("VirtualDevice", "VirtualDisk", e("key", String.valueOf(key))
                + (label == null ? "" : el("deviceInfo", e("label", label) + e("summary", "10 GB")))
                + backing + (ctl < 0 ? "" : e("controllerKey", String.valueOf(ctl))) + e("unitNumber", "0")
                + e("capacityInKB", "10485760")
                + (shares == null ? "" : el("shares", e("shares", "1000") + e("level", shares))));
    }

    private static String flat(boolean eager, boolean split, boolean wt) {
        return typed("backing", "VirtualDiskFlatVer2BackingInfo", e("fileName", "[ds1] vm/vm.vmdk")
                + e("diskMode", "persistent") + e("split", String.valueOf(split)) + e("writeThrough", String.valueOf(wt))
                + e("thinProvisioned", "true") + e("eagerlyScrub", String.valueOf(eager)) + e("uuid", "6000"));
    }

    static String rdm() {
        return typed("backing", "VirtualDiskRawDiskMappingVer1BackingInfo", e("fileName", "[ds1] vm/rdm.vmdk")
                + e("deviceName", "/vmfs/devices/disks/naa.1") + e("diskMode", "persistent"));
    }

    /** A powered-on VM with every key the property collector answers, devices of each family included. */
    static Obj vmFull(String moid, String name) {
        Obj v = new Obj("VirtualMachine", moid).name(name);
        v.put("config.numaInfo.vnumaOnCpuHotaddExposed", SoapFx.bool(false));
        v.put("config.cpuAllocation.shares.level", SoapFx.en("SharesLevel", "normal"));
        v.put("config.bootOptions.bootDelay", SoapFx.longv(0));
        v.put("config.bootOptions.bootRetryDelay", SoapFx.intv(10000));
        v.put("config.bootOptions.bootRetryEnabled", SoapFx.bool(false));
        v.put("config.bootOptions.enterBIOSSetup", SoapFx.bool(false));
        v.put("config.changeVersion", SoapFx.str("2026-01-02T03:04:05.678901Z"));
        v.put("config.firmware", SoapFx.str("efi"));
        v.put("config.flags.diskUuidEnabled", SoapFx.bool(true));
        v.put("config.latencySensitivity.level", SoapFx.en("LatencySensitivitySensitivityLevel", "normal"));
        v.put("config.rebootPowerOff", SoapFx.bool(false));
        v.put("config.scheduledHardwareUpgradeInfo.scheduledHardwareUpgradeStatus",
                SoapFx.en("VirtualMachineScheduledHardwareUpgradeInfoHardwareUpgradeStatus", "none"));
        v.put("config.scheduledHardwareUpgradeInfo.upgradePolicy",
                SoapFx.en("VirtualMachineScheduledHardwareUpgradeInfoHardwareUpgradePolicy", "never"));
        v.put("config.scheduledHardwareUpgradeInfo.versionKey", SoapFx.str("vmx-21"));
        v.put("config.vmOpNotificationTimeout", SoapFx.longv(30));
        v.put("config.memoryAllocation.shares.level", SoapFx.en("SharesLevel", "high"));
        v.put("configStatus", SoapFx.en("ManagedEntityStatus", "green"));
        v.put("guest.guestState", SoapFx.str("running"));
        v.put("guest.appState", SoapFx.en("GuestInfoAppStateType", "none"));
        v.put("guest.guestKernelCrashed", SoapFx.bool(false));
        v.put("guest.guestOperationsReady", SoapFx.bool(true));
        v.put("guest.guestStateChangeSupported", SoapFx.bool(true));
        v.put("guest.interactiveGuestOperationsReady", SoapFx.bool(false));
        v.put("config.tools.syncTimeWithHost", SoapFx.bool(false));
        v.put("config.tools.toolsUpgradePolicy", SoapFx.str("manual"));
        v.put("runtime.consolidationNeeded", SoapFx.bool(false));
        v.put("runtime.dasVmProtection.dasProtected", SoapFx.bool(true));
        v.put("runtime.minRequiredEVCModeKey", SoapFx.str("intel-skylake"));
        v.put("guest.customizationInfo.customizationStatus", SoapFx.en("GuestInfoCustomizationStatus", " TOOLSDEPLOYPKG_SUCCEEDED "));
        v.put("summary.quickStats.guestHeartbeatStatus", SoapFx.en("ManagedEntityStatus", "green"));
        v.put("guest.guestDetailedData", SoapFx.str("architecture='X86' bitness='64' buildNumber='22000' "
                + "cpeString='cpe:/o:microsoft:windows_11' distroAddlVersion='' distroName='Microsoft Windows 11' "
                + "distroVersion='10.0.22000' familyName='Windows' kernelVersion='22000' "
                + "prettyName='Windows \"11\" Pro' extra='it''s'"));
        v.put("summary.config.vmPathName", SoapFx.str("[datastore1] vm1/vm1.vmx"));
        v.put("config.hardware.device", arr("ArrayOfVirtualDevice",
                scsi("ParaVirtualSCSIController", 1000, 0, "SCSI controller 0")
                + scsi("VirtualLsiLogicSASController", 1001, 1, "SCSI controller 1")
                + scsi("VirtualLsiLogicController", 1002, 2, "SCSI controller 2")
                + scsi("VirtualBusLogicController", 1003, 3, "SCSI controller 3")
                + scsi("VirtualSCSIController", 1004, 4, "SCSI controller 4")
                + vnic("VirtualVmxnet3", 4000, "Network adapter 1", true, "assigned")
                + vnic("VirtualE1000e", 4001, " Network adapter 2 ", false, "manual")
                + vnic("VirtualVmxnet3", 4002, null, true, "generated")
                + vnic("VirtualE1000", 4003, "Network adapter 4", null, null)
                + vdisk(2000, 1000, "Hard disk 1", flat(true, false, true), "normal")
                + vdisk(2001, 1000, "Hard disk 2", rdm(), null)
                + vdisk(2002, -1, "Hard disk 3", flat(false, true, false), "custom")
                + vdisk(2003, 1001, null, flat(false, false, false), "low")
                + typed("VirtualDevice", "VirtualCdrom", e("key", "3000") + el("deviceInfo", e("label", "CD/DVD drive 1")))));
        v.put("config.extraConfig", arr("ArrayOfOptionValue",
                optionValue("disk.EnableUUID", "xsd:string", "TRUE") + optionValue("tools.guest.desktop.autolock", "xsd:string", "")
                + optionValue("other.key", "xsd:string", "v")));
        v.put("config.version", SoapFx.str("vmx-21"));
        v.put("runtime.powerState", SoapFx.en("VirtualMachinePowerState", "poweredOn"));
        v.put("config.maxMksConnections", SoapFx.intv(40));
        v.put("config.hardware", SoapFx.obj("VirtualHardware", e("numCPU", "4") + e("memoryMB", "8192")));
        v.put("config.cpuAffinity.affinitySet", arr("ArrayOfInt", e("int", "0") + e("int", "1")));
        v.put("config.createDate", SoapFx.date("2024-05-06T07:08:09Z"));
        v.put("runtime.host", SoapFx.moref("HostSystem", "host-1"));
        return v;
    }

    /** A powered-off VM that returns only its configuration status; the rest is unset. */
    static Obj vmSparse(String moid, String name) {
        return new Obj("VirtualMachine", moid).name(name).put("configStatus", SoapFx.en("ManagedEntityStatus", "gray"))
                .put("config.hardware.device", arr("ArrayOfVirtualDevice", ""))
                .put("config.vmOpNotificationTimeout", SoapFx.longv(-1));
    }

    // ------------------------------------------------------------------ datastores

    static Obj dsVmfs(String moid, String name) {
        Obj d = new Obj("Datastore", moid).name(name);
        d.put("overallStatus", SoapFx.en("ManagedEntityStatus", "green"));
        d.put("iormConfiguration.enabled", SoapFx.bool(true));
        d.put("iormConfiguration.congestionThreshold", SoapFx.intv(30));
        d.put("info", SoapFx.obj("VmfsDatastoreInfo", e("name", name) + e("url", "ds:///vmfs/volumes/x/")
                + e("freeSpace", "100") + el("vmfs", e("type", "VMFS") + e("blockSizeMb", "1") + e("maxBlocks", "262144")
                        + e("vmfsUpgradable", "false") + e("version", "6.82")
                        + el("extent", e("diskName", "naa.600a0001") + e("partition", "1"))
                        + el("extent", e("partition", "2"))
                        + el("extent", e("diskName", "naa.600a0002") + e("partition", "1")))));
        return d;
    }

    static Obj dsNfs(String moid, String name) {
        Obj d = new Obj("Datastore", moid).name(name);
        d.put("overallStatus", SoapFx.en("ManagedEntityStatus", "yellow"));
        d.put("iormConfiguration.enabled", SoapFx.bool(false));
        d.put("info", SoapFx.obj("NasDatastoreInfo", e("name", name) + el("nas", e("remoteHost", "nfs.lab"))));
        return d;
    }

    static Obj dsBare(String moid, String name) {
        return new Obj("Datastore", moid).name(name);
    }

    // ------------------------------------------------------------------ method answers

    static String license(String entity, String name, String key, String edition, String expiry) {
        return "<returnval>" + e("entityId", entity) + e("scope", "x") + e("entityDisplayName", "disp")
                + el("assignedLicense", (key == null ? "" : e("licenseKey", key))
                        + (edition == null ? "" : e("editionKey", edition)) + (name == null ? "" : e("name", name))
                        + el("properties", e("key", "cores") + "<value xsi:type=\"xsd:int\">64</value>")
                        + (expiry == null ? "" : el("properties", e("key", "expirationDate")
                                + "<value xsi:type=\"xsd:dateTime\">" + expiry + "</value>")))
                + "</returnval>";
    }

    static String pkg(String name, String version, String type, String vendor, String level, String summary,
            String mmr) {
        return "<returnval>" + e("name", name) + (version == null ? "" : e("version", version))
                + (type == null ? "" : e("type", type)) + (vendor == null ? "" : e("vendor", vendor))
                + (level == null ? "" : e("acceptanceLevel", level))
                + (summary == null ? "" : e("summary", summary)) + e("description", "long text")
                + e("creationDate", "2025-01-01T00:00:00Z") + (mmr == null ? "" : e("maintenanceModeRequired", mmr))
                + el("payload", e("name", "p")) + "</returnval>";
    }

    static String setting(String key, String xsiType, String v) {
        return "<returnval>" + e("key", key) + "<value xsi:type=\"" + xsiType + "\">" + SoapFx.esc(v)
                + "</value></returnval>";
    }
}
