package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The property phase through the real entry point: a cycle of the engine over
 * the real vim25 client, stream parser and collectors, against a vCenter built
 * from hand-written SOAP answers and a Suite API fake. What the cycle pushes is
 * what is asserted.
 */
public final class PropertiesTest {

    static final String V = "vCommunity|";
    static final long NOW = 1_700_000_000_000L;

    /** Everything one scenario needs. */
    static final class Rig implements AutoCloseable {
        final FakeVcenter vc;
        final P2Ops ops = new P2Ops();
        final VimSoapClient vim;
        final CycleEngine engine;
        final T.RecLog log = new T.RecLog();
        final WarnCounter warns = new WarnCounter(log);
        final AtomicLong clock = new AtomicLong(NOW);
        final VCommunityConfig cfg;
        final FakeVc fake;
        final int from;

        Rig(FakeVc fake, FakeVcenter vc, String... cfgKv) {
            this.vc = vc;
            this.fake = fake;
            this.from = fake.requests.size();
            fake.setHandler(vc);
            cfg = EngineTest.cfg(cfgKv);
            vim = new VimSoapClient("localhost:" + fake.port(), "u", "p",
                    VcenterTls.create(VCommunityConfig.TlsMode.TRUST_ON_FIRST_USE, "", null, null), warns,
                    5000, 20000, 20000);
            engine = new CycleEngine(cfg, vim,
                    (h, p, t) -> presented == null ? ThumbprintProbe.Result.failed("probe failed")
                            : ThumbprintProbe.Result.found(presented), ops,
                    new CheckListStore(warns), warns, clock::get);
        }

        volatile String presented = "ab".repeat(32);

        CycleEngine.Result lastResult;

        CycleEngine.Result run() throws Exception {
            CycleEngine.Result r = engine.run(clock.get() + 240_000);
            lastResult = r;
            Coverage.record(ops);
            return r;
        }

        /** The requests of one action made since this rig was built. */
        List<FakeVc.Req> reqs(String action) {
            List<FakeVc.Req> out = new ArrayList<>();
            for (int i = from; i < fake.requests.size(); i++) {
                if (fake.requests.get(i).action.equals(action)) out.add(fake.requests.get(i));
            }
            return out;
        }

        int count(String action) {
            return reqs(action).size();
        }

        /** The value pushed in push {@code n} for a resource and key. */
        Object p(int n, String rid, String key) {
            return ops.prop(n, rid, key);
        }

        Double st(int n, String rid, String key) {
            return ops.stat(n, rid, key);
        }

        /** The health string or number the vCenter object got in push {@code n}. */
        Object health(int n, String key) {
            Object o = ops.prop(n, "vc-res", CollectionHealth.PREFIX + key);
            return o != null ? o : ops.stat(n, "vc-res", CollectionHealth.PREFIX + key);
        }

        @Override
        public void close() {
            vim.close("test finished");
        }
    }

    /** The default vCenter: one of each shape. */
    static FakeVcenter dataset() {
        FakeVcenter vc = new FakeVcenter();
        vc.add(P2Fixtures.clusterFull("domain-c1", "Prod")).add(P2Fixtures.clusterOff("domain-c2", "Edge"))
                .add(P2Fixtures.clusterNoMonitoring("domain-c3", "NoMon")).add(P2Fixtures.clusterBare("domain-c4", "Bare"))
                .add(P2Fixtures.clusterNoProactive("domain-c5", "NoProactive"));
        vc.add(P2Fixtures.hostFull("host-1", "esx1.lab.test", "imgmgr-1")).add(P2Fixtures.hostSparse("host-2", "esx2"))
                .add(P2Fixtures.hostDisconnected("host-3", "esx3")).add(P2Fixtures.hostEmptyLists("host-4", "esx4"));
        vc.add(P2Fixtures.vmFull("vm-1", "web01")).add(P2Fixtures.vmSparse("vm-2", "off01"));
        vc.add(P2Fixtures.dsVmfs("datastore-1", "ds1")).add(P2Fixtures.dsNfs("datastore-2", "nfs1"))
                .add(P2Fixtures.dsBare("datastore-3", "bare"));
        vc.installDate.put("imgmgr-1", "2025-01-02T03:04:05.123456Z");
        vc.packages.put("imgmgr-1", P2Fixtures.pkg("esx-base", "8.0.3-0.35", "bootbank", "VMware, Inc.",
                "vmware_certified", "ESXi base", "true")
                + P2Fixtures.pkg("pad-vib", " 1.0 ", "", "  Padded  ", "community", "", null)
                + P2Fixtures.pkg("lsi-msgpt3", "1.0", "bootbank", "Broadcom", "vmware_certified", "x", "false"));
        vc.licenses = P2Fixtures.license("host-1", "VMware vSphere 8 Enterprise Plus", "AB12C-DE34F-GH56I-JK78L-MN90P",
                "esx.enterprisePlus.cpuPackage", "2030-01-01T00:00:00Z")
                + P2Fixtures.license("host-1", "VMware vSAN", "11111-22222-33333-44444-55555", "vsan.std", null)
                + P2Fixtures.license("vc-uuid", "vCenter Server", "VC000-VC000-VC000-VC000-VC000", "vc", null);
        vc.options = P2Fixtures.setting("ads.timeout", "xsd:long", "5000")
                + P2Fixtures.setting("config.vpxd.enableDebugBrowse", "xsd:boolean", "false")
                + P2Fixtures.setting("event.maxAge", "xsd:int", "30")
                + P2Fixtures.setting("event.maxAgeEnabled", "xsd:boolean", "true")
                + P2Fixtures.setting("task.maxAge", "xsd:int", "30")
                + P2Fixtures.setting("task.maxAgeEnabled", "xsd:boolean", "true")
                + P2Fixtures.setting("instance.id", "xsd:string", "  ")
                + P2Fixtures.setting("VirtualCenter.ManagedIP", "xsd:string", "10.1.1.1")
                + P2Fixtures.setting("VirtualCenter.MaxDBConnection", "xsd:int", "50")
                + P2Fixtures.setting("some.other", "xsd:string", "x");
        return vc;
    }

    /** Ops resources for every object of the dataset. */
    static void mapAll(P2Ops ops, FakeVcenter vc) {
        for (Map.Entry<String, List<SoapFx.Obj>> e : vc.byType.entrySet()) {
            if (!e.getKey().matches("ClusterComputeResource|HostSystem|VirtualMachine|Datastore")) continue;
            for (SoapFx.Obj o : e.getValue()) ops.add(e.getKey(), "res-" + o.moid, o.moid, FakeVc.INSTANCE_UUID);
        }
    }

    static void files(P2Ops ops, String adv, String pkgs, String vmParams, String vmOptions) {
        ops.files.put("esxi_advanced_system_settings", "<L>" + adv + "</L>");
        ops.files.put("esxi_packages", "<L>" + pkgs + "</L>");
        ops.files.put("vm_advanced_parameters", "<L>" + vmParams + "</L>");
        ops.files.put("vm_options", "<L>" + vmOptions + "</L>");
    }

    /** What the first push put on one resource, sorted. */
    static Map<String, Object> pushed(P2Ops ops, int n, String rid) {
        Map<String, Object> m = new TreeMap<>();
        Map<String, Object> p = ops.pushedProps.get(n).get(rid);
        if (p != null) m.putAll(p);
        Map<String, Double> s = ops.pushedStats.get(n).get(rid);
        if (s != null) m.putAll(s);
        return m;
    }


    static final String H = "res-host-1";
    static final String VM = "res-vm-1";
    static final String C1 = "res-domain-c1";
    static final String DS1 = "res-datastore-1";
    static final String VC = "vc-res";

    static void eq(Rig r, String rid, String key, Object want) {
        eqAt(r, 0, rid, key, want);
    }

    /** Assert the value pushed in push {@code n}; the key counts as value-tested for the coverage table. */
    static void eqAt(Rig r, int n, String rid, String key, Object want) {
        Object got = r.p(n, rid, V + key);
        if (got == null) got = r.st(n, rid, V + key);
        T.eq(want, got, rid + " " + key);
        Coverage.asserted(rid, V + key);
    }

    static void none(Rig r, String rid, String key) {
        T.check(r.p(0, rid, V + key) == null && r.st(0, rid, V + key) == null,
                rid + " must not carry " + key + " but has " + r.p(0, rid, V + key));
    }

    static Rig standard(FakeVc fake, FakeVcenter vc) {
        Rig r = new Rig(fake, vc);
        mapAll(r.ops, vc);
        files(r.ops, P2Fixtures.ADV_ITEM + ",Net.Bool,Net.Dbl,Net.Text,Missing.Setting",
                P2Fixtures.PKG_ITEM + ",absent-vib,pad-vib", "disk.EnableUUID,tools.guest.desktop.autolock,missing.key",
                "config.version,runtime.powerState");
        return r;
    }

    public static void main(String[] args) throws Exception {
        try (FakeVc fake = new FakeVc(FakeVc.happy())) {
            run("hosts", () -> hosts(fake));
            run("vms", () -> vms(fake));
            run("virtual disks need a network adapter (upstream quirk Q2)", () -> disksNeedAnAdapter(fake));
            run("clusters", () -> clusters(fake));
            run("datastores and vCenter", () -> datastoresAndVcenter(fake));
            run("no sentinel, no null, no secret", () -> noSentinels(fake));
            run("sparse objects print the original's text for unset values, and are counted", () -> sparse(fake));
            run("an unset value is the original's text; a failed read sends nothing", () -> unsetVersusFailed(fake));
            run("a refused privilege is one failed read naming it", () -> privilegeRefused(fake));
            run("failed reads of every family send no placeholder and are counted", () -> failedReadFamilies(fake));
            run("failed reads withhold and count", () -> failedReads(fake));
            run("a failed page discards the whole type", () -> failedPage(fake));
            run("per-host call failures", () -> hostCallFailures(fake));
            run("refused property paths", () -> refusedPaths(fake));
            run("check lists gate the reads", () -> gating(fake));
            run("matching: unmatched, ambiguous, other vCenters", () -> matching(fake));
            run("Ops lists: refresh rules, failures, unknown vCenter", () -> refresh(fake));
            run("certificate state", () -> certificate(fake));
            run("pushed on change", () -> onChange(fake));
            run("deadline", () -> deadline(fake));
            run("paging and a large inventory", () -> paging(fake));
            run("license edge cases", () -> licenseEdges(fake));
            run("blank strings are pushed as upstream pushes them", () -> blanks(fake));
            run("tokens are released, a missing-set fault is a failed read, a stopped read stops", () -> tokensMissingStop(fake));
            run("a VM Options entry that fails the page costs the entry", () -> optionFailsPage(fake));
            run("license reads fall back to one call per host", () -> licensesPerHost(fake));
            run("a host that left between the host reads", () -> hostLeaves(fake));
        }
        Coverage.scenario = "coverage";
        Coverage.verifyOrWrite();
        T.done("key coverage table equals the key map and the tests");
    }

    interface Scenario {
        void go() throws Exception;
    }

    static void run(String name, Scenario s) throws Exception {
        Coverage.scenario = "PropertiesTest: " + name;
        long t0 = System.currentTimeMillis();
        s.go();
        T.done("properties: " + name + " (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    // ------------------------------------------------------------------ values

    static void hosts(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            eq(r, H, "Configuration|Install Date|UTC", "2025-01-02T03:04:05.123456+00:00");
            eq(r, H, "Configuration|Config Status", "green");
            eq(r, H, "Configuration|Max EVC", "intel-icelake");
            eq(r, H, "Network|DHCP", "False");
            eq(r, H, "Network|Search Domain", "lab.test, corp.test");
            eq(r, H, "Configuration|Time Zone|Name", "UTC");
            eq(r, H, "Configuration|Time Zone|Description", "UTC");
            eq(r, H, "Configuration|Time Zone|GMT Offset", "0");
            eq(r, H, "Runtime|Boot Time", "2025-03-04 05:06:07.500000+00:00");
            eq(r, H, "Hardware|BIOS Vendor", "Dell Inc.");
            eq(r, H, "Capability|Storage vMotion Supported", "True");
            eq(r, H, "Configuration|VAAI|ATS Heartbeat", "1");
            eq(r, H, "Configuration|VAAI|ATS Locking", "1");
            // advanced settings: exact key, any value type, as the original's str()
            eq(r, H, "Configuration|Advanced System Settings|Net.TcpipHeapSize", "64");
            eq(r, H, "Configuration|Advanced System Settings|Net.Bool", "True");
            eq(r, H, "Configuration|Advanced System Settings|Net.Dbl", "0.1");
            eq(r, H, "Configuration|Advanced System Settings|Net.Text", "  padded  ");
            none(r, H, "Configuration|Advanced System Settings|Missing.Setting");
            none(r, H, "Configuration|Advanced System Settings|Misc.Noise1");
            // storage adapters
            eq(r, H, "Storage Adapter|Number of HBA", "3");
            eq(r, H, "Storage Adapter:vmhba2|Device", "vmhba2");
            eq(r, H, "Storage Adapter:vmhba2|Bus", "59");
            eq(r, H, "Storage Adapter:vmhba2|Model", "QLE2692");
            eq(r, H, "Storage Adapter:vmhba2|PCI", "0000:3b:00.0");
            eq(r, H, "Storage Adapter:vmhba2|Status", "online");
            eq(r, H, "Storage Adapter:vmhba2|Type", "fc");
            eq(r, H, "Storage Adapter:vmhba64|Device", "vmhba64"); // device "null": last segment of the key
            eq(r, H, "Storage Adapter:vmhba64|Bus", "0");
            eq(r, H, "Storage Adapter:vmhba1|Model", ""); // spaces are stripped to the empty string, and pushed, as upstream does (Q4)
            eq(r, H, "Storage Adapter:vmhba1|PCI", "null"); // a field the adapter lacks is the original's "null" (Q9)
            eq(r, H, "Storage Adapter:vmhba1|Type", "null");
            eq(r, H, "Storage Adapter:vmhba1|Status", "unknown");
            // NICs
            String n0 = "Network|Device:vmnic0|";
            eq(r, H, n0 + "Device Name", "vmnic0");
            eq(r, H, n0 + "Driver Version", "4.23.0.66");
            eq(r, H, n0 + "Firmware Version", "16.35.2000");
            eq(r, H, n0 + "Status", "Connected");
            eq(r, H, n0 + "PCI Vendor Name", "Intel Corporation");
            eq(r, H, n0 + "PCI Device Name", "Ethernet Controller");
            eq(r, H, n0 + "PCI Vendor ID (VID)", "-7f7a"); // -32634 on the wire: upstream formats the signed value (Q3)
            eq(r, H, n0 + "PCI Device ID (DID)", "1508");
            eq(r, H, n0 + "PCI SubVendor ID (SVID)", "-7f7a");
            eq(r, H, n0 + "MAC", "aa:bb:cc:00:00:01");
            eq(r, H, n0 + "Wake On Lan Supported", "True");
            eq(r, H, n0 + "PCI", "0000:3b:00.0");
            eq(r, H, n0 + "Duplex", "full-duplex");
            String n1 = "Network|Device:vmnic1|"; // link down
            eq(r, H, n1 + "Status", "Disconnected");
            eq(r, H, n1 + "Duplex", "half-duplex"); // a link that is down is half-duplex to the original (Q10)
            none(r, H, n1 + "Driver Version"); // unset on the NIC: the original passes None to the SDK, which refuses it
            eq(r, H, n1 + "Wake On Lan Supported", "None"); // str(None) (Q9)
            eq(r, H, n1 + "PCI Vendor ID (VID)", "15b3");
            String n2 = "Network|Device:vmnic2|"; // up, half duplex, no PCI device matches
            eq(r, H, n2 + "Duplex", "half-duplex");
            eq(r, H, n2 + "PCI Vendor Name", "N/A"); // no PCI device matches the NIC: the original's "N/A" (Q9)
            eq(r, H, n2 + "PCI Vendor ID (VID)", "N/A");
            eq(r, H, n2 + "MAC", "None"); // no MAC: str(None)
            // packages
            String pk = "Configuration|Packages:esx-base|";
            eq(r, H, pk + "Package Name", "esx-base");
            eq(r, H, pk + "Package Version", "8.0.3-0.35");
            eq(r, H, pk + "Acceptance Level", "vmware_certified");
            eq(r, H, pk + "Package Summary", "ESXi base");
            eq(r, H, pk + "Package Type", "bootbank");
            eq(r, H, pk + "Package Vendor", "VMware, Inc.");
            eq(r, H, pk + "Maintenance Mode Required", Double.valueOf(1.0)); // the flag is a number
            // package text is as sent, spaces and empty values included (upstream str()s nothing); a missing flag is no flag
            eq(r, H, "Configuration|Packages:pad-vib|Package Version", " 1.0 ");
            eq(r, H, "Configuration|Packages:pad-vib|Package Vendor", "  Padded  ");
            eq(r, H, "Configuration|Packages:pad-vib|Package Type", "");
            none(r, H, "Configuration|Packages:pad-vib|Maintenance Mode Required");
            none(r, H, "Configuration|Packages:lsi-msgpt3|Package Name"); // not on the list
            none(r, H, "Configuration|Packages:absent-vib|Package Name"); // not installed
            // licenses
            String l1 = "Licensing:VMware vSphere 8 Enterprise Plus|";
            eq(r, H, l1 + "Name", "VMware vSphere 8 Enterprise Plus");
            eq(r, H, l1 + "License Key", "*****-*****-*****-*****-MN90P");
            eq(r, H, l1 + "Edition Key", "esx.enterprisePlus.cpuPackage");
            eq(r, H, l1 + "License Expiration Date", "2030-01-01 00:00:00+00:00");
            long days = Math.floorDiv(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond() - NOW / 1000, 86400);
            eq(r, H, l1 + "Remaining Days", Double.valueOf(days));
            String l2 = "Licensing:VMware vSAN|";
            eq(r, H, l2 + "License Key", "*****-*****-*****-*****-55555");
            none(r, H, l2 + "License Expiration Date"); // perpetual: nothing to publish
            none(r, H, l2 + "Remaining Days");
            // hosts that are not connected, or that returned nothing
            T.check(r.ops.pushedProps.get(0).get("res-host-3") == null, "a disconnected host gets no keys");
            // a connected host that returned nothing: each property it lacks is the text the original prints (Q9)
            eq(r, "res-host-2", "Configuration|Config Status", "None");
            eq(r, "res-host-2", "Hardware|BIOS Vendor", "None");
            eq(r, "res-host-2", "Configuration|Max EVC", "[]");
            eq(r, "res-host-2", "Network|DHCP", "null");
            eq(r, "res-host-2", "Network|Search Domain", "null");
            eq(r, "res-host-2", "Storage Adapter|Number of HBA", "0");
            eq(r, "res-host-4", "Storage Adapter|Number of HBA", "0"); // a returned empty list is a count of 0
            eq(r, "res-host-4", "Network|DHCP", "True");
            eq(r, "res-host-4", "Network|Search Domain", "null"); // no search domain: "null"
            // the hosts' install-date and packages calls went only to the connected host that has the manager
            T.eq(1, r.count("installDate"), "one install date call");
            T.eq(1, r.count("fetchSoftwarePackages"), "one packages call");
        }
    }

    static void vms(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            String c = "Configuration|";
            eq(r, VM, c + "NUMA|Hot Add Exposed", "False");
            eq(r, VM, c + "CPU Allocation Share Level", "normal");
            eq(r, VM, c + "Boot Options|Delay", "0");
            eq(r, VM, c + "Boot Options|Retry Delay", "10000");
            eq(r, VM, c + "Boot Options|Retry Enabled", "False");
            eq(r, VM, c + "Boot Options|Enter BIOS Setup", "False");
            eq(r, VM, c + "Change Version", "2026-01-02T03:04:05.678901Z"); // a string, as sent
            eq(r, VM, c + "Firmware", "efi");
            eq(r, VM, c + "Flags|Disk UUID Enabled", "True");
            eq(r, VM, c + "Latency Sensitivity Level", "normal");
            eq(r, VM, c + "Reboot PowerOff", "False");
            eq(r, VM, c + "Hardware Upgrade|Status", "none");
            eq(r, VM, c + "Hardware Upgrade|Policy", "never");
            eq(r, VM, c + "Hardware Upgrade|Target Version", "vmx-21");
            eq(r, VM, c + "Op Notification Timeout", "30");
            eq(r, VM, c + "Memory Allocation Share Level", "high");
            eq(r, VM, c + "Config Status", "green");
            eq(r, VM, "Guest|State", "running");
            eq(r, VM, "Guest|Application Status", "none");
            eq(r, VM, "Guest|Kernel Crash State", "False");
            eq(r, VM, "Guest|Operation Ready", "True");
            eq(r, VM, "Guest|State Change Support", "True");
            eq(r, VM, "Guest|Interactive Guest", "False");
            eq(r, VM, "Guest|Tools Sync Time", "None"); // Q1: upstream always sends the text None
            eq(r, VM, "Guest|Tools Upgrade Policy", "None");
            eq(r, VM, "Runtime|Consolidation Needed", "False");
            eq(r, VM, "Runtime|DAS protection", "True");
            eq(r, VM, "Runtime|Min Required EVC Mode Key", "intel-skylake");
            eq(r, VM, "Guest|Customization Status", "SUCCEEDED"); // stripped, prefix removed
            eq(r, VM, "Guest|Heartbeat Status", "green");
            eq(r, VM, "Summary|VM Path Name", "[datastore1] vm1/vm1.vmx");
            // detailed data: the same pattern, a value is cut at its first quote, an empty or missing one is "null"
            eq(r, VM, "Guest|Detailed Data|Build Number", "22000");
            eq(r, VM, "Guest|Detailed Data|CPE String", "cpe:/o:microsoft:windows_11");
            eq(r, VM, "Guest|Detailed Data|Distro Name", "Microsoft Windows 11");
            eq(r, VM, "Guest|Detailed Data|Distro Version", "10.0.22000");
            eq(r, VM, "Guest|Detailed Data|Kernel Version", "22000");
            eq(r, VM, "Guest|Detailed Data|Pretty Name", "Windows");
            eq(r, VM, "Guest|Detailed Data|Distro Additional Version", "null");
            // advanced parameters: exact keys, a present empty value is a value
            eq(r, VM, c + "Advanced Parameters|disk.EnableUUID", "TRUE");
            eq(r, VM, c + "Advanced Parameters|tools.guest.desktop.autolock", "");
            none(r, VM, c + "Advanced Parameters|missing.key");
            none(r, VM, c + "Advanced Parameters|other.key");
            // options: scalars through the Python rules
            eq(r, VM, "Options|config.version", "vmx-21");
            eq(r, VM, "Options|runtime.powerState", "poweredOn");
            // controllers
            eq(r, VM, c + "SCSI Controllers|Count", 5.0);
            eq(r, VM, c + "SCSI Controllers:0|Type", "VMware Paravirtual (PVSCSI)");
            eq(r, VM, c + "SCSI Controllers:1|Type", "LSI Logic SAS");
            eq(r, VM, c + "SCSI Controllers:2|Type", "LSI Logic Parallel");
            eq(r, VM, c + "SCSI Controllers:3|Type", "BusLogic");
            eq(r, VM, c + "SCSI Controllers:4|Type", "VirtualSCSIController");
            // network adapters: the label is stripped; a card with no label is not published
            eq(r, VM, "Network|Network Adapters:Network adapter 1|Type", "assigned");
            eq(r, VM, "Network|Network Adapters:Network adapter 1|Starts Connected", "True");
            eq(r, VM, "Network|Network Adapters:Network adapter 2|Type", "manual");
            eq(r, VM, "Network|Network Adapters:Network adapter 2|Starts Connected", "False");
            eq(r, VM, "Network|Network Adapters:Network adapter 4|Type", "None"); // no address type returned: str(None)
            eq(r, VM, "Network|Network Adapters:Network adapter 4|Starts Connected", "null"); // no connectable: "null"
            for (String k : r.ops.pushedProps.get(0).get(VM).keySet()) {
                T.check(!k.contains("Network Adapters:|") && !k.contains("Unknown Adapter"), "no sentinel adapter: " + k);
            }
            // disks
            String d1 = "Virtual Disk:Hard disk 1|";
            eq(r, VM, d1 + "Label", "Hard disk 1");
            eq(r, VM, d1 + "Key", "2000");
            eq(r, VM, d1 + "Controller Key", "1000");
            eq(r, VM, d1 + "Eagerly Scrub", "True");
            eq(r, VM, d1 + "Split", "False");
            eq(r, VM, d1 + "Write Through", "True");
            eq(r, VM, d1 + "Storage Allocation Share Level", "normal");
            String d2 = "Virtual Disk:Hard disk 2|"; // a raw device mapping has none of the flat-file flags, and no shares
            eq(r, VM, d2 + "Key", "2001");
            eq(r, VM, d2 + "Controller Key", "1000");
            eq(r, VM, d2 + "Eagerly Scrub", "None"); // a backing without the flag: str(None) (Q9)
            eq(r, VM, d2 + "Split", "None");
            eq(r, VM, d2 + "Write Through", "None");
            eq(r, VM, d2 + "Storage Allocation Share Level", "Unknown"); // no shares: the original's "Unknown"
            String d3 = "Virtual Disk:Hard disk 3|"; // no controller key returned
            eq(r, VM, d3 + "Key", "2002");
            eq(r, VM, d3 + "Controller Key", "None");
            eq(r, VM, d3 + "Split", "True");
            eq(r, VM, d3 + "Storage Allocation Share Level", "custom");
            // a disk with no label has no instance name
            for (String k : r.ops.pushedProps.get(0).get(VM).keySet()) {
                T.check(!k.startsWith(V + "Virtual Disk:|") && !k.contains("Disk:unknown") && !k.contains("Disk:Unknown"),
                        "no sentinel disk: " + k);
            }
            T.check(!r.ops.pushedProps.get(0).get(VM).containsKey(V + "Virtual Disk:Hard disk 4|Key"), "no fourth disk");
            // the second VM: unset almost everywhere
            eq(r, "res-vm-2", "Configuration|Config Status", "gray");
            eq(r, "res-vm-2", "Configuration|Op Notification Timeout", "0"); // a negative value read is 0, as the original
            eq(r, "res-vm-2", "Configuration|SCSI Controllers|Count", 0.0); // a returned empty device list
            // what it did not return is the text upstream prints for an unset value (Q9)
            eq(r, "res-vm-2", "Configuration|Firmware", "None");
            eq(r, "res-vm-2", "Configuration|NUMA|Hot Add Exposed", "None");
            eq(r, "res-vm-2", "Configuration|Boot Options|Delay", "None");
            eq(r, "res-vm-2", "Guest|Application Status", "None");
            eq(r, "res-vm-2", "Guest|Customization Status", "null");
            eq(r, "res-vm-2", "Guest|Detailed Data|CPE String", "null");
            eq(r, "res-vm-2", "Runtime|DAS protection", "None");
            eq(r, "res-vm-2", "Summary|VM Path Name", "None");
            eq(r, "res-vm-2", "Guest|Tools Sync Time", "None");
        }
    }

    /** Q2: no network adapter, no Virtual Disk keys; the controllers are still published. */
    static void disksNeedAnAdapter(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.add(P2Fixtures.vmNoNic("vm-8", "appliance"));
        try (Rig r = standard(fake, vc)) {
            r.run();
            eq(r, "res-vm-8", "Configuration|SCSI Controllers:0|Type", "VMware Paravirtual (PVSCSI)");
            eq(r, "res-vm-8", "Configuration|SCSI Controllers|Count", 1.0);
            for (String k : r.ops.pushedProps.get(0).get("res-vm-8").keySet()) {
                T.check(!k.contains("Virtual Disk:"), "upstream sends no disk key for a VM with no adapter: " + k);
            }
            eq(r, VM, "Virtual Disk:Hard disk 1|Key", "2000"); // with an adapter they are there
        }
    }

    static void clusters(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            String ha = "Cluster Configuration|vSphere HA|";
            eq(r, C1, "Cluster Configuration|Configuration Status", "green");
            eq(r, C1, "Cluster Configuration|Overall Status", "yellow");
            eq(r, C1, "Cluster Configuration|Number of Effective Hosts", 2.0);
            eq(r, C1, ha + "Host Monitoring", "enabled");
            eq(r, C1, ha + "Response \\ Host Isolation", "none");
            eq(r, C1, ha + "Response \\ Default VM Restart Priority", "medium");
            eq(r, C1, ha + "Response \\ Datastore APD", "warning");
            eq(r, C1, ha + "Response \\ Datastore PDL", "restartAggressive");
            eq(r, C1, ha + "VM Monitoring", "vmMonitoringOnly");
            eq(r, C1, ha + "Heartbeat Datastore", "allFeasibleDsWithUserPreference");
            eq(r, C1, ha + "Failure Interval", "30");
            eq(r, C1, ha + "Max Failure Window", "3600");
            eq(r, C1, ha + "Max Failures", "3");
            eq(r, C1, ha + "Minimum Up Time", "120");
            eq(r, C1, "Cluster Configuration|DPM|Host Power Action Rate", "3");
            eq(r, C1, "Cluster Configuration|EVC|Enabled", "True");
            eq(r, C1, "Cluster Configuration|EVC|Mode", "intel-skylake");
            String drs = "Cluster Configuration|Distributed Resource Scheduler|";
            eq(r, C1, drs + "Proactive DRS", "False");
            eq(r, C1, drs + "Scale Descendants Shares", "disabled");
            eq(r, C1, drs + "CPU Over-Commitment", "4");
            eq(r, C1, drs + "Virtual Machine Automation Enabled", "True");
            eq(r, C1, drs + "DRS Score", 87.0);
            // HA off, DRS off, no EVC: the original says "null" for every HA and DRS setting, and a DRS score of 0
            String c2 = "res-domain-c2";
            for (String k : new String[] {"Host Monitoring", "Response \\ Host Isolation",
                "Response \\ Default VM Restart Priority", "Response \\ Datastore APD", "Response \\ Datastore PDL",
                "VM Monitoring", "Heartbeat Datastore", "Failure Interval", "Max Failure Window", "Max Failures",
                "Minimum Up Time"}) {
                eq(r, c2, ha + k, "null");
            }
            for (String k : new String[] {"Proactive DRS", "Scale Descendants Shares", "CPU Over-Commitment",
                "Virtual Machine Automation Enabled"}) {
                eq(r, c2, drs + k, "null");
            }
            eq(r, c2, drs + "DRS Score", 0.0); // DRS off: 0 (the live differential: Python 0.0)
            eq(r, c2, "Cluster Configuration|EVC|Enabled", "False"); // the summary was read and names no mode
            eq(r, c2, "Cluster Configuration|EVC|Mode", "null");
            none(r, c2, "Cluster Configuration|DPM|Host Power Action Rate"); // no dpmConfigInfo: nothing, as the original
            eq(r, c2, "Cluster Configuration|Number of Effective Hosts", 0.0); // not returned: the original's default 0
            // HA on with host monitoring disabled: the five responses are not sent, the rest is
            String c3 = "res-domain-c3";
            for (String k : new String[] {"Host Monitoring", "Response \\ Host Isolation",
                "Response \\ Default VM Restart Priority", "Response \\ Datastore APD", "Response \\ Datastore PDL"}) {
                eq(r, c3, ha + k, "null");
            }
            eq(r, c3, ha + "VM Monitoring", "vmMonitoringDisabled");
            eq(r, c3, ha + "Failure Interval", "60");
            eq(r, c3, ha + "Max Failures", "None"); // not returned: str(None)
            // DRS on without the over-commitment option: that one is "null"
            eq(r, c3, drs + "Proactive DRS", "True");
            eq(r, c3, drs + "CPU Over-Commitment", "null");
            eq(r, c3, drs + "DRS Score", 0.0); // a negative score is published as 0
            eq(r, c3, "Cluster Configuration|Number of Effective Hosts", 0.0); // a returned 0 is a count
            eq(r, c3, "Cluster Configuration|DPM|Host Power Action Rate", "None"); // dpmConfigInfo has no rate: str(None)
            // nothing returned at all: what the original prints for each unset value, and nothing it would not send
            eq(r, "res-domain-c4", "Cluster Configuration|Configuration Status", "None");
            eq(r, "res-domain-c4", "Cluster Configuration|EVC|Mode", "null");
            eq(r, "res-domain-c4", ha + "Host Monitoring", "None"); // HA enabled flag unset: not False, so the values are str(None)
            none(r, "res-domain-c4", drs + "DRS Score"); // no summary: the original raises, no score
            none(r, "res-domain-c4", drs + "Proactive DRS"); // no proactive configuration: the original raises
            // DRS on but the proactive configuration missing: the original raises and sends none of the four
            for (String k : new String[] {"Proactive DRS", "Scale Descendants Shares", "CPU Over-Commitment",
                "Virtual Machine Automation Enabled"}) {
                none(r, "res-domain-c5", drs + k);
            }
        }
    }

    static void datastoresAndVcenter(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            eq(r, DS1, "Summary|MOID", "datastore-1");
            eq(r, DS1, "Summary|Status", "green");
            eq(r, DS1, "Summary|SIOC Enabled", "True");
            eq(r, DS1, "Summary|SIOC Threshold", "30");
            eq(r, DS1, "Summary|Block Size", "1");
            eq(r, DS1, "Summary|Max Blocks", "262144");
            eq(r, DS1, "Summary|VMFS Upgradable", "False");
            eq(r, DS1, "Identifiers|Number of Extents", "2");
            eq(r, DS1, "Identifiers|Extent:1|ID", "naa.600a0001");
            eq(r, DS1, "Identifiers|Extent:2|ID", "naa.600a0002"); // the extent with no disk name is not counted
            String ds2 = "res-datastore-2"; // NFS: no VMFS information
            eq(r, ds2, "Summary|Status", "yellow");
            eq(r, ds2, "Summary|SIOC Enabled", "False");
            eq(r, ds2, "Summary|SIOC Threshold", "None"); // unset: str(None)
            eq(r, ds2, "Identifiers|Number of Extents", "0"); // no extents on a datastore that is not VMFS: 0 (the live differential)
            none(r, ds2, "Summary|Block Size");
            none(r, ds2, "Summary|Max Blocks");
            none(r, ds2, "Summary|VMFS Upgradable");
            String ds3 = "res-datastore-3";
            eq(r, ds3, "Summary|MOID", "datastore-3");
            eq(r, ds3, "Summary|Status", "None");
            // the vCenter object
            String c = "Configuration|";
            eq(r, VC, c + "User Directory Timeout", "5000");
            eq(r, VC, c + "Enable MOB", "False");
            eq(r, VC, c + "Database|Event retention (days)", "30");
            eq(r, VC, c + "Database|Event Cleanup Enabled", "True");
            eq(r, VC, c + "Database|Task retention (days)", "30");
            eq(r, VC, c + "Database|Task Cleanup Enabled", "True");
            eq(r, VC, c + "Runtime|vCenter managed address", "10.1.1.1");
            eq(r, VC, c + "Database|Maximum Connections", "50");
            eq(r, VC, c + "Runtime|vCenter Unique ID", "null"); // blank setting: the original's "null"
            eq(r, VC, c + "Log Level", "null"); // not a setting this vCenter has: "null"
            eq(r, VC, c + "Patch Level", "8.0.3.00200");
            T.eq(1, r.count("QueryOptions"), "one call for every setting (DV-37)");
        }
        // a vCenter that names no patch level
        FakeVcenter old = dataset();
        old.patchLevel = null;
        old.options = P2Fixtures.setting("instance.id", "xsd:int", "7") + P2Fixtures.setting("log.level", "xsd:string", "info");
        old.add(P2Fixtures.vmFull("vm-7", "other").put("guest.guestDetailedData",
                SoapFx.str("distroAddlVersion='SP1' prettyName='Plain'")));
        try (Rig r = standard(fake, old)) {
            r.run();
            eq(r, VC, "Configuration|Patch Level", "None");
            eq(r, VC, "Configuration|Runtime|vCenter Unique ID", "7");
            eq(r, VC, "Configuration|Runtime|vCenter managed address", "null");
            eq(r, VC, "Configuration|Log Level", "info");
            eq(r, "res-vm-7", "Guest|Detailed Data|Distro Additional Version", "SP1");
            eq(r, "res-vm-7", "Guest|Detailed Data|Pretty Name", "Plain");
            eq(r, "res-vm-7", "Guest|Detailed Data|Build Number", "null");
        }
    }

    // ------------------------------------------------------------------ honesty

    static final Set<String> SENTINELS = new HashSet<>(java.util.Arrays.asList("None", "null", "Unknown",
            "N/A", "[]", "Unknown Adapter"));

    /**
     * No key is under another namespace, none is null or untyped, no number is NaN, no instance is named
     * with a sentinel, and no secret is pushed. (A placeholder as a <em>value</em> is the original's own text
     * for an unset property, Q9, and is tested where it is built; what must not happen is one for a read that
     * failed: {@link #unsetVersusFailed}.)
     */
    static void noSentinels(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        for (int i = 10; i < 14; i++) {
            vc.add(P2Fixtures.hostSparse("host-" + i, "s" + i)).add(P2Fixtures.vmSparse("vm-" + i, "v" + i))
                    .add(P2Fixtures.clusterBare("domain-c" + i, "c" + i)).add(P2Fixtures.dsBare("datastore-" + i, "d" + i));
        }
        try (Rig r = standard(fake, vc)) {
            r.run();
            int n = 0;
            for (Map<String, Map<String, Object>> push : r.ops.pushedProps) {
                for (Map.Entry<String, Map<String, Object>> e : push.entrySet()) {
                    for (Map.Entry<String, Object> p : e.getValue().entrySet()) {
                        n++;
                        T.check(p.getKey().startsWith(V), "every key is under vCommunity|: " + p.getKey());
                        T.check(p.getValue() instanceof String || p.getValue() instanceof Double, "typed: " + p.getKey());
                        T.check(p.getValue() != null, "no null value: " + p.getKey());
                        T.check(!p.getKey().contains(":null|") && !p.getKey().contains(":None|")
                                && !p.getKey().contains(":Unknown"),
                                "no sentinel instance name: " + p.getKey());
                    }
                }
            }
            for (Map<String, Map<String, Double>> push : r.ops.pushedStats) {
                for (Map<String, Double> m : push.values()) {
                    for (Map.Entry<String, Double> s : m.entrySet()) {
                        T.check(!s.getValue().isNaN() && !s.getValue().isInfinite(), "finite: " + s.getKey());
                    }
                }
            }
            T.check(n > 100, "the run did push properties");
            // no license key and no credential in any log line, any push
            String all = r.log.all();
            T.notContains(all, "AB12C-DE34F-GH56I-JK78L-MN90P", "the license key is never logged");
            T.notContains(all, "11111-22222-33333-44444-55555", "no other license key either");
            for (Map<String, Map<String, Object>> push : r.ops.pushedProps) {
                for (Map<String, Object> m : push.values()) {
                    for (Object v : m.values()) {
                        T.check(!String.valueOf(v).contains("AB12C-DE34F") && !String.valueOf(v).contains("DE34F-GH56I"),
                                "only the last group of a key is pushed");
                    }
                }
            }
        }
    }

    /** The object loses the value of these paths and vCenter says it could not read them (a missing-set fault). */
    static SoapFx.Obj unreadable(SoapFx.Obj o, String... paths) {
        for (String p : paths) {
            o.vals.remove(p);
            o.missing(p);
        }
        return o;
    }

    /** Keys must not be sent: a value for them would be one made up from a failed read. */
    static void absent(Rig r, String rid, String... keys) {
        for (String k : keys) {
            T.check(r.p(0, rid, V + k) == null && r.st(0, rid, V + k) == null,
                    rid + " must not carry " + k + " (its read failed) but has " + r.p(0, rid, V + k) + r.st(0, rid, V + k));
        }
    }

    /**
     * B1 to B4 and the sweep behind them (Q8, Q9, Q10): for every family of key that has a placeholder for an
     * unset value, a read that FAILED (a missing-set fault) sends nothing for it and is counted, while the
     * same key on an object whose read worked and is unset gets the original's text.
     */
    static void failedReadFamilies(FakeVc fake) throws Exception {
        String ha = "Cluster Configuration|vSphere HA|";
        String drs = "Cluster Configuration|Distributed Resource Scheduler|";
        String evc = "Cluster Configuration|EVC|";
        // clusters: DRS (B1), HA, summary
        FakeVcenter vc = dataset();
        unreadable(vc.byType.get("ClusterComputeResource").get(0), "configuration.drsConfig");
        unreadable(vc.byType.get("ClusterComputeResource").get(1), "summary");
        unreadable(vc.byType.get("ClusterComputeResource").get(2), "configuration.dasConfig");
        try (Rig r = standard(fake, vc)) {
            r.run();
            absent(r, C1, drs + "Proactive DRS", drs + "Scale Descendants Shares", drs + "CPU Over-Commitment",
                    drs + "Virtual Machine Automation Enabled", drs + "DRS Score");
            eq(r, C1, ha + "Host Monitoring", "enabled"); // what did read is sent
            eq(r, C1, "Cluster Configuration|Configuration Status", "green");
            absent(r, "res-domain-c2", evc + "Enabled", evc + "Mode", "Cluster Configuration|Number of Effective Hosts",
                    drs + "DRS Score");
            absent(r, "res-domain-c3", ha + "Host Monitoring", ha + "VM Monitoring", ha + "Failure Interval",
                    ha + "Max Failures", ha + "Minimum Up Time");
            T.check((Double) r.health(0, "Reads Failed in Last Cycle") >= 1, "the failed reads are counted");
            T.check((Double) r.health(0, "Values Withheld in Last Cycle") >= 10, "and the values left out");
        }
        // hosts: the option read (B2), the image manager (B3), DNS, PCI, adapters, EVC, BIOS vendor
        vc = dataset();
        unreadable(vc.byType.get("HostSystem").get(0), "config.option", "configManager.imageConfigManager",
                "config.network.dnsConfig", "hardware.pciDevice", "config.storageDevice.hostBusAdapter",
                "summary.maxEVCModeKey", "hardware.biosInfo.vendor");
        try (Rig r = standard(fake, vc)) {
            r.run();
            absent(r, H, "Configuration|VAAI|ATS Heartbeat", "Configuration|VAAI|ATS Locking",
                    "Configuration|Advanced System Settings|Net.TcpipHeapSize", "Configuration|Install Date|UTC",
                    "Network|DHCP", "Network|Search Domain", "Storage Adapter|Number of HBA", "Configuration|Max EVC",
                    "Hardware|BIOS Vendor", "Network|Device:vmnic2|PCI Vendor Name", "Network|Device:vmnic2|PCI Vendor ID (VID)",
                    "Storage Adapter:vmhba2|Device");
            eq(r, H, "Configuration|Config Status", "green");
            eq(r, H, "Network|Device:vmnic0|Status", "Connected");
            T.check(r.count("installDate") == 0, "no call for a host whose image manager could not be read");
        }
        // a host that left between the host read and the option read: no option list, no ATS "null"
        vc = dataset();
        vc.byType.get("HostSystem").get(0).vals.remove("config.option");
        try (Rig r = standard(fake, vc)) {
            r.run();
            absent(r, H, "Configuration|VAAI|ATS Heartbeat", "Configuration|VAAI|ATS Locking");
            eq(r, H, "Configuration|Config Status", "green");
            T.contains(firstFailure(r, 0), "R-HOO", "counted as a row not returned");
        }
        // VMs: timeout, customization, detailed data, devices, firmware
        vc = dataset();
        unreadable(vc.byType.get("VirtualMachine").get(0), "config.vmOpNotificationTimeout",
                "guest.customizationInfo.customizationStatus", "guest.guestDetailedData", "config.hardware.device",
                "config.firmware");
        try (Rig r = standard(fake, vc)) {
            r.run();
            absent(r, VM, "Configuration|Op Notification Timeout", "Guest|Customization Status",
                    "Guest|Detailed Data|Build Number", "Guest|Detailed Data|Pretty Name",
                    "Configuration|SCSI Controllers|Count", "Configuration|Firmware");
            eq(r, VM, "Guest|State", "running");
        }
        // a path vCenter refuses on every VM (InvalidProperty): nothing for it, on any VM, whatever its value
        vc = dataset();
        vc.invalid.add("config.vmOpNotificationTimeout");
        vc.invalid.add("guest.guestDetailedData");
        try (Rig r = standard(fake, vc)) {
            r.run();
            for (String vm : new String[] {VM, "res-vm-2"}) {
                absent(r, vm, "Configuration|Op Notification Timeout", "Guest|Detailed Data|CPE String");
            }
            eq(r, VM, "Configuration|Firmware", "efi");
            T.contains(r.log.all(), "F-SOAP-INVALIDPROP", "named");
        }
        // datastore: no info, no status
        vc = dataset();
        unreadable(vc.byType.get("Datastore").get(1), "info", "overallStatus");
        try (Rig r = standard(fake, vc)) {
            r.run();
            absent(r, "res-datastore-2", "Identifiers|Number of Extents", "Summary|Status");
            eq(r, "res-datastore-2", "Summary|SIOC Enabled", "False");
        }
    }

    /**
     * A value that was read and is unset is the original's text; one whose read FAILED is not sent and is
     * counted: a refused path, and a path vCenter could not read for the object (missing set).
     */
    static void unsetVersusFailed(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        // vCenter refuses one VM path outright, and cannot read another for one VM
        vc.invalid.add("guest.appState");
        SoapFx.Obj denied = P2Fixtures.vmFull("vm-9", "denied");
        denied.vals.remove("config.firmware"); // vCenter returns no value for what it could not read
        denied.vals.remove("guest.guestState");
        vc.add(denied.missing("config.firmware").missing("guest.guestState"));
        // a host whose DNS configuration came back without the dhcp flag: str(None); with none at all: "null" (N1)
        vc.add(new SoapFx.Obj("HostSystem", "host-12").name("flagless")
                .put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", "connected"))
                .put("config.network.dnsConfig", SoapFx.obj("HostDnsConfig", SoapFx.e("hostName", "x")))
                .put("config.option", SoapFx.arr("ArrayOfOptionValue", "")));
        try (Rig r = standard(fake, vc)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            String vm9 = "res-vm-9";
            T.check(r.p(0, VM, V + "Guest|Application Status") == null, "a refused path: not \"None\"");
            T.check(r.p(0, vm9, V + "Guest|Application Status") == null, "a refused path: not \"None\" on any VM");
            T.check(r.p(0, vm9, V + "Configuration|Firmware") == null, "a path vCenter could not read for this VM: not \"None\"");
            T.check(r.p(0, vm9, V + "Guest|State") == null, "likewise");
            eq(r, "res-vm-2", "Configuration|Firmware", "None"); // unset on a read that worked: the text
            eq(r, "res-host-12", "Network|DHCP", "None"); // the DNS configuration without the flag: str(None)
            eq(r, "res-host-4", "Network|DHCP", "True");
            T.check(r.p(0, vm9, V + "Configuration|Boot Options|Delay") != null, "what read is sent");
            T.contains(firstFailure(r, 0), "F-SOAP", "the failed reads are counted");
            T.check((Double) r.health(0, "Values Withheld in Last Cycle") >= 1, "the values left out are counted");
        }
    }

    /** Objects that return nothing: what was not returned is the original's text; what could not exist is counted. */
    static void sparse(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        vc.add(P2Fixtures.hostSparse("host-2", "esx2")).add(P2Fixtures.vmSparse("vm-2", "off01"))
                .add(P2Fixtures.clusterBare("domain-c4", "bare")).add(P2Fixtures.dsBare("datastore-3", "ds"));
        try (Rig r = standard(fake, vc)) {
            mapAll(r.ops, vc);
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "the cycle did not fail");
            // objects that returned nothing, on reads that worked, get what the original prints for unset values (Q9)
            eq(r, "res-host-2", "Hardware|BIOS Vendor", "None");
            eq(r, "res-domain-c4", "Cluster Configuration|Overall Status", "None");
            eq(r, "res-vm-2", "Configuration|Firmware", "None");
            // what has no source even so (an unset NIC driver, a cluster with no summary) is counted
            Double withheld = (Double) r.health(0, "Values Withheld in Last Cycle");
            T.check(withheld != null && withheld > 0, "the values left out are counted: " + withheld);
            T.eq(0.0, r.health(0, "Reads Failed in Last Cycle"), "no read failed");
            T.eq("OK", r.health(0, "Collector:host|Status"), "a successful read is OK");
            T.eq("OK", r.health(0, "Collector:vm|Status"), "a successful read is OK");
        }
    }

    // ------------------------------------------------------------------ failures

    static String firstFailure(Rig r, int n) {
        return String.valueOf(r.health(n, "First Failure"));
    }

    static void failedReads(FakeVc fake) throws Exception {
        // the cluster read is refused: no cluster key, everything else untouched
        FakeVcenter vc = dataset();
        vc.forcedType.put("ClusterComputeResource", FakeVcenter.serverFault("the cluster read was refused"));
        try (Rig r = standard(fake, vc)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "a failed read does not fail the cycle");
            T.check(r.ops.pushedProps.get(0).get(C1) == null && r.ops.pushedStats.get(0).get(C1) == null,
                    "nothing is sent for the clusters");
            eq(r, H, "Configuration|Config Status", "green");
            eq(r, VM, "Configuration|Firmware", "efi");
            T.eq(1.0, r.health(0, "Reads Failed in Last Cycle"), "one read failed");
            T.contains(firstFailure(r, 0), "F-SOAP-R-CL", "the cause is named");
            T.eq("FAILED", r.health(0, "Collector:cluster|Status"), "the cluster collector failed");
            T.eq(1.0, r.health(0, "Collector:cluster|Failures"), "its failures are counted");
            T.eq("OK", r.health(0, "Collector:host|Status"), "the others are fine");
            T.check(r.health(0, "Objects Not Matched") == null, "the unmatched count is not sent when a read failed");
            T.contains(r.log.all(), "F-SOAP-R-CL: 1 failed read(s)", "one WARN line names the cause");
        }
        // the host options are refused: the options keys are withheld, the rest of each host is sent
        vc = dataset();
        vc.forcedPath.put("config.option", FakeVcenter.serverFault("options refused"));
        try (Rig r = standard(fake, vc)) {
            r.run();
            eq(r, H, "Configuration|Config Status", "green");
            none(r, H, "Configuration|VAAI|ATS Heartbeat");
            none(r, H, "Configuration|Advanced System Settings|Net.TcpipHeapSize");
            T.contains(firstFailure(r, 0), "F-SOAP-R-HOO", "named");
            T.eq("PARTIAL", r.health(0, "Collector:host|Status"), "partly collected");
            T.check((Double) r.health(0, "Values Withheld in Last Cycle") >= 2, "the options keys are counted");
        }
        // the license call is refused
        vc = dataset();
        vc.forced.put("QueryAssignedLicenses", FakeVcenter.serverFault("no licenses today"));
        try (Rig r = standard(fake, vc)) {
            r.run();
            for (String k : r.ops.pushedProps.get(0).get(H).keySet()) {
                T.check(!k.contains("Licensing:"), "no license key when the read failed: " + k);
            }
            eq(r, H, "Configuration|Config Status", "green");
            T.contains(firstFailure(r, 0), "F-SOAP-R-LIC", "named");
            T.eq("PARTIAL", r.health(0, "Collector:host|Status"), "partly collected");
        }
        // the settings call is refused: the ten settings are withheld, the patch level (no call) is not
        vc = dataset();
        vc.forced.put("QueryOptions", FakeVcenter.serverFault("settings refused"));
        try (Rig r = standard(fake, vc)) {
            r.run();
            none(r, VC, "Configuration|Enable MOB");
            none(r, VC, "Configuration|Database|Maximum Connections");
            eq(r, VC, "Configuration|Patch Level", "8.0.3.00200");
            T.contains(firstFailure(r, 0), "F-SOAP-R-OPT", "named");
            T.eq("PARTIAL", r.health(0, "Collector:vcenter|Status"), "partly collected");
        }
        // the datastore and host reads answer malformed XML
        vc = dataset();
        vc.rewrite.put("Datastore", s -> s.replace("</returnval>", "</returnval><oops>"));
        try (Rig r = standard(fake, vc)) {
            r.run();
            T.check(r.ops.pushedProps.get(0).get(DS1) == null, "a malformed answer sends nothing");
            T.contains(firstFailure(r, 0), "F-SOAP-R-DS", "named");
            eq(r, H, "Configuration|Config Status", "green");
        }
        // a login failure is a failed cycle, not a failed read
        vc = dataset();
        vc.forced.put("Login", FakeVc.fault("InvalidLogin", "Cannot complete login"));
        try (Rig r = standard(fake, vc)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure != null, "login failed");
            T.eq(0, r.ops.pushes(), "nothing is pushed");
        }
    }

    static void failedPage(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        for (int i = 100; i < 160; i++) vc.add(P2Fixtures.vmSparse("vm-" + i, "bulk" + i));
        vc.failContinueAt.put("VirtualMachine", 1); // page 2 of 3 fails
        try (Rig r = standard(fake, vc)) {
            r.run();
            for (Map.Entry<String, Map<String, Object>> e : r.ops.pushedProps.get(0).entrySet()) {
                T.check(!e.getKey().startsWith("res-vm-"), "a failed page discards the type: " + e.getKey());
            }
            for (String rid : r.ops.pushedStats.get(0).keySet()) {
                T.check(!rid.startsWith("res-vm-"), "no VM statistics either: " + rid);
            }
            T.eq("FAILED", r.health(0, "Collector:vm|Status"), "the VM collector failed");
            T.contains(firstFailure(r, 0), "F-SOAP-R-VM", "named");
            eq(r, H, "Configuration|Config Status", "green"); // the other types are unaffected
            // next cycle the read works and the VMs are collected
            vc.failContinueAt.clear();
            r.run();
            T.check(r.ops.pushedProps.get(1).get(VM) != null, "the next cycle collects the VMs");
        }
    }

    /** W6: a refusal for lack of Host.Config.Image is one failed read of the cycle that names the privilege, not one per host. */
    static void privilegeRefused(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.add(P2Fixtures.hostFull("host-5", "esx5", "imgmgr-5"));
        vc.add(P2Fixtures.hostFull("host-6", "esx6", "imgmgr-6"));
        vc.forced.put("installDate", FakeVc.fault("NoPermission", "Permission to perform this operation was denied."));
        vc.forced.put("fetchSoftwarePackages", FakeVc.fault("NoPermission", "Permission to perform this operation was denied."));
        try (Rig r = standard(fake, vc)) {
            r.run();
            T.eq(3, r.count("installDate"), "every host was asked");
            T.contains(r.log.all(), "F-HOST-INSTALLDATE: 1 failed read(s)", "one failed read, not one per host");
            T.contains(r.log.all(), "F-HOST-PACKAGES: 1 failed read(s)", "likewise for the packages");
            T.contains(r.log.all(), "Host.Config.Image", "and it names the privilege to grant");
            absent(r, H, "Configuration|Install Date|UTC", "Configuration|Packages:esx-base|Package Name");
            eq(r, H, "Configuration|Config Status", "green");
            T.eq(2.0, r.health(0, "Reads Failed in Last Cycle"), "two failed reads in all");
            // which reads failed, from the health keys alone
            T.eq("F-HOST-INSTALLDATE x1; F-HOST-PACKAGES x1", r.health(0, "Collector:host|Causes"), "the causes of the host collector");
            T.eq("None in the last cycle", r.health(0, "Collector:vm|Causes"), "none for a collector that did not fail");
            T.contains(r.engine == null ? "" : String.valueOf(r.lastResult.failureCausesProperty()), "host: F-HOST-INSTALLDATE x1",
                    "and all of them in one line for the account");
            T.check((Double) r.health(0, "Values Withheld in Last Cycle") >= 6, "the values left out are counted per host");
        }
    }

    static void hostCallFailures(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.add(P2Fixtures.hostFull("host-5", "esx5", "imgmgr-5")); // the manager answers faults
        vc.add(P2Fixtures.hostFull("host-6", "esx6", "imgmgr-6"));
        vc.installDate.put("imgmgr-6", null); // answers, with no date
        vc.packages.put("imgmgr-6", "");
        try (Rig r = standard(fake, vc)) {
            r.run();
            String h5 = "res-host-5";
            none(r, h5, "Configuration|Install Date|UTC");
            none(r, h5, "Configuration|Packages:esx-base|Package Name");
            eq(r, h5, "Configuration|Config Status", "green"); // the rest of the host is sent
            eq(r, "res-host-6", "Configuration|Install Date|UTC", "null"); // no date returned: not a failure, the original's "null"
            none(r, "res-host-6", "Configuration|Packages:esx-base|Package Name");
            eq(r, H, "Configuration|Install Date|UTC", "2025-01-02T03:04:05.123456+00:00");
            T.eq(2.0, r.health(0, "Reads Failed in Last Cycle"), "the two failed calls are counted, once each");
            T.eq("PARTIAL", r.health(0, "Collector:host|Status"), "partly collected");
            T.contains(r.log.all(), "F-HOST-INSTALLDATE: 1 failed read(s)", "named once");
            T.contains(r.log.all(), "F-HOST-PACKAGES: 1 failed read(s)", "named once");
            T.eq(3, r.count("installDate"), "a call per connected host that has a manager");
        }
    }

    static void refusedPaths(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.invalid.add("config.nosuch");
        try (Rig r = standard(fake, vc)) {
            files(r.ops, "", "", "", "config.version,config.nosuch,runtime.powerState,config.hardware,bad path,x[0]");
            r.run();
            eq(r, VM, "Options|config.version", "vmx-21");
            eq(r, VM, "Options|runtime.powerState", "poweredOn");
            none(r, VM, "Options|config.nosuch");
            none(r, VM, "Options|config.hardware"); // an object: not a single value
            eq(r, VM, "Configuration|Firmware", "efi"); // a bad entry costs only its own key
            eq(r, "res-vm-2", "Configuration|Config Status", "gray");
            T.contains(r.log.all(), "config.hardware", "the non-scalar entry is reported");
            T.contains(firstFailure(r, 0), "F-SOAP-INVALIDPROP", "the refused entry is named");
            T.contains(firstFailure(r, 0), "config.nosuch", "with its path");
            T.eq("PARTIAL", r.health(0, "Collector:vm|Status"), "partly collected");
            T.check(((Double) r.health(0, "Check-List Entries Skipped")) >= 2, "the two malformed entries are counted");
            // reported once: the next cycle neither asks again nor counts it again
            int asked = 0;
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) if (q.body.contains("config.nosuch")) asked++;
            r.run();
            int askedAfter = 0;
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) if (q.body.contains("config.nosuch")) askedAfter++;
            T.eq(asked, askedAfter, "a refused entry is not asked for again");
            T.eq(0.0, r.health(1, "Collector:vm|Failures"), "and not counted again");
        }
        // entries that name paths the collector reads anyway, or hold a reference, an object or a date
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            files(r.ops, "", "", "", "config.hardware.device,config.extraConfig,runtime.host,config.createDate,config.maxMksConnections,name,"
                    + "resourcePool,config.bytes,snapshot,config.hardware");
            r.run();
            for (String k : new String[] {"config.hardware.device", "config.extraConfig"}) {
                none(r, VM, "Options|" + k); // a list: its Python text is not reproduced
                T.contains(r.log.all(), k, "reported: " + k);
            }
            eq(r, VM, "Options|runtime.host", "'vim.HostSystem:host-1'"); // a reference, as pyVmomi prints it
            eq(r, VM, "Options|config.createDate", "2024-05-06 07:08:09+00:00");
            eq(r, VM, "Options|config.maxMksConnections", "40");
            eq(r, VM, "Options|name", "web01");
            eq(r, VM, "Virtual Disk:Hard disk 1|Key", "2000"); // the device list is still read for its keys
            eq(r, VM, "Configuration|SCSI Controllers|Count", 5.0);
            // B3: a value nested far deeper than any tree is built costs only its own key
            none(r, VM, "Options|snapshot");
            none(r, VM, "Options|config.hardware");
            none(r, VM, "Options|resourcePool"); // a reference of a type with no Python name here
            T.contains(r.log.all(), "'snapshot'", "reported");
            T.contains(r.log.all(), "'resourcePool'", "reported");
            eq(r, VM, "Options|config.bytes", "b\"hi'\\x01\""); // bytes, as Python prints them
            eq(r, VM, "Configuration|Firmware", "efi");
            eq(r, "res-vm-2", "Configuration|Config Status", "gray");
            T.eq("OK", r.health(0, "Collector:vm|Status"), "the VM read did not fail");
            int asked = countAsking(r, "snapshot");
            T.check(asked >= 1, "asked for once at least");
            r.run();
            T.eq(asked, countAsking(r, "snapshot"), "an entry found not to be a single value is dropped from every later read");
            T.eq(0.0, r.health(1, "Collector:vm|Failures"), "and costs nothing after");
        }
        // a property this vCenter does not have (an older version): its keys are withheld, the VM is not lost
        vc = dataset();
        vc.invalid.add("guest.guestDetailedData");
        try (Rig r = standard(fake, vc)) {
            r.run();
            none(r, VM, "Guest|Detailed Data|Build Number");
            none(r, VM, "Guest|Detailed Data|Pretty Name");
            eq(r, VM, "Guest|State", "running");
            eq(r, VM, "Summary|VM Path Name", "[datastore1] vm1/vm1.vmx");
            T.contains(firstFailure(r, 0), "guest.guestDetailedData", "named");
            T.check((Double) r.health(0, "Values Withheld in Last Cycle") >= 7, "the seven keys are counted");
            int asked = 0;
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) if (q.body.contains("guest.guestDetailedData")) asked++;
            r.run();
            int after = 0;
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) if (q.body.contains("guest.guestDetailedData")) after++;
            T.eq(asked, after, "not asked for again in this session");
        }
        // a refused path on the host read costs that one key
        vc = dataset();
        vc.invalid.add("hardware.biosInfo.vendor");
        try (Rig r = standard(fake, vc)) {
            r.run();
            none(r, H, "Hardware|BIOS Vendor");
            eq(r, H, "Capability|Storage vMotion Supported", "True");
        }
    }

    static int countAsking(Rig r, String path) {
        int n = 0;
        for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) if (q.body.contains("<pathSet>" + path + "</pathSet>")) n++;
        return n;
    }

    static void gating(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = new Rig(fake, vc)) {
            mapAll(r.ops, vc);
            files(r.ops, "", "", "", "");
            r.run();
            T.eq(0, r.count("fetchSoftwarePackages"), "no packages call without a packages list");
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) {
                T.notContains(q.body, "config.extraConfig", "the extra configuration is not asked for");
            }
            eq(r, H, "Configuration|VAAI|ATS Heartbeat", "1"); // always read
            for (String k : r.ops.pushedProps.get(0).get(H).keySet()) {
                T.check(!k.contains("Advanced System Settings|") && !k.contains("Packages:"), "no list keys: " + k);
            }
            for (String k : r.ops.pushedProps.get(0).get(VM).keySet()) {
                T.check(!k.contains("Advanced Parameters|") && !k.startsWith(V + "Options|"), "no list keys: " + k);
            }
            eq(r, VM, "Configuration|Firmware", "efi");
            // the VM Options probe is not made without entries
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) {
                T.check(!q.body.contains("<maxObjects>1</maxObjects>") || !q.body.contains("<type>VirtualMachine"),
                        "no VM Options probe without entries");
            }
        }
        // the list file is absent: its block is empty, the rest is collected
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.files.remove("esxi_packages");
            r.run();
            T.eq(0, r.count("fetchSoftwarePackages"), "an absent list is empty");
            eq(r, H, "Configuration|Config Status", "green");
        }
        // a fetch failure keeps the last good list in force (DV-27)
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            r.ops.files.put("esxi_advanced_system_settings", new IOException("the store is down"));
            vc.packages.put("imgmgr-1", ""); // nothing else changes
            r.run();
            T.eq(2, r.count("installDate"), "the cycle ran");
            T.check(r.ops.pushedProps.get(1).get(H) == null || !r.ops.pushedProps.get(1).get(H)
                    .containsKey(V + "Configuration|Advanced System Settings|Net.TcpipHeapSize") ||
                    "64".equals(r.ops.pushedProps.get(1).get(H).get(V + "Configuration|Advanced System Settings|Net.TcpipHeapSize")),
                    "the last good list still collects the setting");
        }
    }

    // ------------------------------------------------------------------ matching

    static int listReads(Rig r, String kind) {
        int n = 0;
        for (String p : r.ops.paths) if (p.contains("resourceKind=" + kind + "&")) n++;
        return n;
    }

    static void matching(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = new Rig(fake, vc)) {
            mapAll(r.ops, vc);
            files(r.ops, "", "", "", "");
            // vm-1 has no resource; domain-c1 has two (a MoRef two resources claim matches neither);
            // host-2 has a resource of another vCenter only
            r.ops.resources.get("VirtualMachine").removeIf(x -> x[1].equals("vm-1"));
            r.ops.add("ClusterComputeResource", "res-domain-c1-dup", "domain-c1", FakeVc.INSTANCE_UUID);
            r.ops.resources.get("HostSystem").removeIf(x -> x[1].equals("host-2"));
            r.ops.add("HostSystem", "other-vc-host-2", "host-2", "ANOTHER-VCENTER-UUID");
            r.run();
            T.check(r.ops.pushedProps.get(0).get(VM) == null, "an object with no resource gets no keys");
            T.check(r.ops.pushedProps.get(0).get(C1) == null && r.ops.pushedProps.get(0).get("res-domain-c1-dup") == null,
                    "a MoRef two resources claim matches neither");
            T.check(r.ops.pushedProps.get(0).get("other-vc-host-2") == null, "another vCenter's resource is never used");
            eq(r, "res-domain-c2", "Cluster Configuration|Configuration Status", "green");
            // vm-1, domain-c1 and host-2 (connected, returned nothing but still an object) are not matched
            T.eq(3.0, r.health(0, "Objects Not Matched"), "the unmatched objects are counted");
            T.eq(3.0, r.ops.pushedStats.get(0).get(VC).get(CollectionHealth.PREFIX + "Objects Not Matched"), "same");
        }
    }

    /** The list of one kind is read once, again when objects it does not know turn up, and every 12th cycle. */
    static void refresh(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run(); // cycle 1: the maps are read
            T.eq(1, listReads(r, "VirtualMachine"), "read once");
            vc.add(P2Fixtures.vmSparse("vm-9", "new")); // a new VM that Ops does not know yet
            r.run(); // cycle 2: it is new: the list is read again
            T.eq(2, listReads(r, "VirtualMachine"), "a new object justifies a read");
            r.ops.add("VirtualMachine", "res-vm-9", "vm-9", FakeVc.INSTANCE_UUID); // Ops learns of it
            r.run(); // cycle 3: one cycle after a read that found nothing: not yet
            T.eq(2, listReads(r, "VirtualMachine"), "a read that found nothing is followed by a wait");
            T.check(r.ops.pushedProps.get(2).get("res-vm-9") == null, "so the VM has no keys yet");
            r.run(); // cycle 4: two cycles after: read again, and the VM is there
            T.eq(3, listReads(r, "VirtualMachine"), "read again two cycles later");
            T.eq("gray", r.ops.pushedProps.get(3).get("res-vm-9").get(V + "Configuration|Config Status"),
                    "and the VM is collected within a few cycles of Ops knowing it, not an hour");
            // a VM that Ops never models costs a list read at 2, 4 and 8 cycles and then at the 12th cycle
            vc.add(P2Fixtures.vmSparse("vm-10", "template-like"));
            int before = listReads(r, "VirtualMachine");
            for (int i = 5; i <= 28; i++) r.run();
            int extra = listReads(r, "VirtualMachine") - before;
            T.check(extra >= 2 && extra <= 7, "a persistent miss is read for now and then, not every cycle: " + extra);
        }
        // a failed list: the kind is held back, counted, read again next cycle
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.listFails.put("HostSystem", new IOException("the list is not available"));
            r.run();
            T.check(r.ops.pushedProps.get(0).get(H) == null, "no host key without a host list");
            T.contains(firstFailure(r, 0), "F-OPS-MAP-HostSystem", "named");
            T.eq("FAILED", r.health(0, "Collector:host|Status"), "the host collector failed");
            eq(r, VM, "Configuration|Firmware", "efi");
            T.check(r.health(0, "Objects Not Matched") == null, "no count when a map is missing");
            r.ops.listFails.clear();
            r.run();
            T.eq("green", r.p(1, H, V + "Configuration|Config Status"), "read again on the next cycle");
            // 12 cycles later the refresh fails: the previous list is kept and used
            for (int i = 3; i <= 13; i++) r.run();
            r.ops.listFails.put("HostSystem", new IOException("down"));
            vc.byType.get("HostSystem").get(0).put("configStatus", SoapFx.en("ManagedEntityStatus", "red"));
            r.run();
            int last = r.ops.pushedProps.size() - 1;
            T.eq("red", r.ops.pushedProps.get(last).get(H).get(V + "Configuration|Config Status"),
                    "the previous list is used when the refresh fails");
            T.contains(firstFailure(r, last), "F-OPS-MAP-HostSystem", "and the failed refresh is counted");
            T.eq("PARTIAL", r.health(last, "Collector:host|Status"), "partly");
        }
        // an unknown vCenter UUID holds everything back
        vc = dataset();
        vc.forced.put("RetrieveServiceContent", new FakeVc.Resp(200, null, SoapFx.envelope(
                "<RetrieveServiceContentResponse xmlns=\"urn:vim25\"><returnval>"
                + "<rootFolder type=\"Folder\">group-d1</rootFolder>"
                + "<propertyCollector type=\"PropertyCollector\">propertyCollector</propertyCollector>"
                + "<about><fullName>vCenter</fullName></about>"
                + "<sessionManager type=\"SessionManager\">SessionManager</sessionManager>"
                + "</returnval></RetrieveServiceContentResponse>")));
        try (Rig r = standard(fake, vc)) {
            r.run();
            T.eq(0, r.ops.pushes(), "nothing is pushed for an unknown vCenter");
            T.eq(0, r.count("RetrievePropertiesEx"), "and nothing is read");
        }
    }

    // ------------------------------------------------------------------ certificate

    static final String T1 = "ab".repeat(32);
    static final String T2 = "cd".repeat(32);

    static String readBack(String thumb, String changed) {
        StringBuilder b = new StringBuilder("{\"values\":[{\"resourceId\":\"vc-res\",\"property-contents\":{\"property-content\":[");
        boolean first = true;
        if (thumb != null) {
            b.append("{\"statKey\":\"vCommunity|Certificate|SHA-256 Thumbprint\",\"timestamps\":[1],\"values\":[\"")
                    .append(thumb).append("\"]}");
            first = false;
        }
        if (changed != null) {
            if (!first) b.append(',');
            b.append("{\"statKey\":\"vCommunity|Certificate|Last Changed\",\"timestamps\":[1],\"values\":[\"")
                    .append(changed).append("\"]}");
        }
        return b.append("]}}]}").toString();
    }

    static final String CERT = "Certificate|";

    /** The Suite API reads that ask for the certificate keys (the derived phase makes others of its own). */
    static List<String> certPosts(Rig r) {
        List<String> out = new ArrayList<>();
        for (String p : r.ops.posts) if (p.contains("vCommunity|Certificate|")) out.add(p);
        return out;
    }

    static void certificate(FakeVc fake) throws Exception {
        // first sight, then unchanged, then changed (the previous one and the time are sent), through one engine
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            eqAt(r, 0, VC, CERT + "Status", "Recorded"); // first sight
            eqAt(r, 0, VC, CERT + "SHA-256 Thumbprint", T1); // the thumbprint
            T.check(r.p(0, VC, V + CERT + "Previous SHA-256 Thumbprint") == null, "no previous");
            T.eq(1, certPosts(r).size(), "one read-back after the start");
            T.contains(certPosts(r).get(0), "/api/resources/properties/latest/query", "to the properties query");
            T.contains(certPosts(r).get(0), "\"vc-res\"", "of the vCenter object");
            T.contains(certPosts(r).get(0), "vCommunity|Certificate|Last Changed", "for the two keys");
            r.run();
            eqAt(r, 1, VC, CERT + "Status", "Unchanged"); // same certificate
            T.eq(1, certPosts(r).size(), "the read-back is not repeated: the state is in memory");
            r.presented = T2;
            r.run();
            eqAt(r, 2, VC, CERT + "Status", "Changed"); // a different certificate
            eqAt(r, 2, VC, CERT + "SHA-256 Thumbprint", T2); // the new thumbprint
            eqAt(r, 2, VC, CERT + "Previous SHA-256 Thumbprint", T1); // the one before
            eqAt(r, 2, VC, CERT + "Last Changed", "2023-11-14 22:13:20 UTC"); // when it was seen
            T.contains(r.log.all(), "The vCenter certificate has changed", "and said in the log");
            // 24 hours later it is Unchanged again, with no re-send of the change
            r.clock.addAndGet(23L * 3600 * 1000);
            r.run();
            T.check(r.p(3, VC, V + CERT + "Status") == null, "still Changed within 24 hours: nothing new to send");
            r.clock.addAndGet(2L * 3600 * 1000);
            r.run();
            eqAt(r, 4, VC, CERT + "Status", "Unchanged"); // after 24 hours
            T.check(r.p(4, VC, V + CERT + "Previous SHA-256 Thumbprint") == null, "the change is not published again");
        }
        // a restart: the state comes back from the vCenter object
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.readBack = readBack(T1, null);
            r.run();
            eqAt(r, 0, VC, CERT + "Status", "Unchanged"); // the recorded one is the presented one
        }
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.readBack = readBack(T1, "2023-11-14 20:00:00 UTC"); // a change two hours before the clock
            r.run();
            eqAt(r, 0, VC, CERT + "Status", "Changed"); // a change within 24 hours is still shown after a restart
            T.check(r.p(0, VC, V + CERT + "Previous SHA-256 Thumbprint") == null, "the same certificate is not a new change");
        }
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.readBack = readBack(T2, null);
            r.run();
            eqAt(r, 0, VC, CERT + "Status", "Changed"); // recorded differs from presented
            eqAt(r, 0, VC, CERT + "Previous SHA-256 Thumbprint", T2); // previous is the recorded one
        }
        // a failed read-back must not read as a first sight (DV-23)
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.readBack = new com.vcfcf.adapters.vcommunity.ops.OpsHttpException("down", 500, null);
            r.run();
            T.check(r.p(0, VC, V + CERT + "Status") == null, "no status when the state could not be read back");
            T.check(r.p(0, VC, V + CERT + "SHA-256 Thumbprint") == null, "and no thumbprint recorded over it");
            T.contains(firstFailure(r, 0), "F-CERT-READBACK", "named");
            r.ops.readBack = "{\"values\":[]}";
            r.run();
            eqAt(r, 1, VC, CERT + "Status", "Recorded"); // read again next cycle: now a first sight
        }
        // a malformed read-back is a failed one
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.readBack = "not json";
            r.run();
            T.check(r.p(0, VC, V + CERT + "Status") == null, "malformed read-back: no status");
        }
        // a failed probe sends no status and does not touch what is recorded
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            r.presented = null;
            r.run();
            T.check(r.p(1, VC, V + CERT + "Status") == null, "no status without a presented thumbprint");
            T.check(r.p(1, VC, V + CERT + "SHA-256 Thumbprint") == null, "and no thumbprint");
            r.presented = T1;
            r.run();
            T.check(r.p(2, VC, V + CERT + "Status") == null || "Unchanged".equals(r.p(2, VC, V + CERT + "Status")),
                    "the recorded thumbprint was kept: still the same certificate");
        }
        // a push that did not land does not advance the state
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.ops.outcome = new PushOutcome(0, 0, 1, 0, 0, false);
            r.run();
            r.ops.outcome = new PushOutcome(2, 1, 0, 0, 0, false);
            r.run();
            eqAt(r, 1, VC, CERT + "Status", "Recorded"); // still a first sight: the first Recorded did not land
            r.run();
            eqAt(r, 2, VC, CERT + "Status", "Unchanged"); // now recorded
        }
    }

    // ------------------------------------------------------------------ changes, deadline, scale

    static void onChange(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            r.run();
            r.run();
            T.check(r.ops.pushedProps.get(1).get(H) == null && r.ops.pushedProps.get(1).get(VM) == null,
                    "unchanged properties are not pushed again");
            T.eq(5.0, r.ops.pushedStats.get(1).get(VM).get(V + "Configuration|SCSI Controllers|Count"),
                    "statistics are pushed every cycle");
            vc.byType.get("VirtualMachine").get(0).put("config.firmware", SoapFx.str("bios"));
            r.run();
            Map<String, Object> third = r.ops.pushedProps.get(2).get(VM);
            T.eq(1, third.size(), "only the changed property is pushed");
            T.eq("bios", third.get(V + "Configuration|Firmware"), "with its new value");
        }
    }

    static void deadline(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        // the deadline is far enough for the other reads to finish on a loaded machine, and the VM read is far past it
        vc.delayMs.put("RetrievePropertiesEx:VirtualMachine", 15000);
        try (Rig r = standard(fake, vc)) {
            CycleEngine.Result res = r.engine.run(r.clock.get() + 4000);
            Coverage.record(r.ops);
            T.check(res.summary.deadlineHit, "the deadline was reached");
            T.check(r.ops.pushedProps.get(0).get(VM) == null && r.ops.pushedStats.get(0).get(VM) == null,
                    "an unfinished read sends nothing");
            eq(r, H, "Configuration|Config Status", "green"); // what completed is pushed
            T.eq("NOT RUN", r.health(0, "Collector:vm|Status"), "the VM collector did not finish");
            T.contains(firstFailure(r, 0), "F-DEADLINE", "named");
            T.eq(1.0, r.ops.pushedStats.get(0).get(VC).get(CollectionHealth.PREFIX + "Collector:vm|Failures"), "counted");
            T.check(r.health(0, "Objects Not Matched") == null, "no unmatched count from a cut cycle");
        }
        // a deadline already passed: no property read at all
        vc = dataset();
        try (Rig r = standard(fake, vc)) {
            CycleEngine.Result res = r.engine.run(r.clock.get() - 1);
            T.check(res.summary.deadlineHit, "deadline hit");
            T.eq(0, r.count("RetrievePropertiesEx"), "nothing read");
        }
    }

    static final java.util.regex.Pattern ROWNODES = java.util.regex.Pattern.compile(
            "(R-[A-Z]+) pages=(\\d+) rows=(\\d+) bytes=(\\d+) calls=(\\d+) maxRowNodes=(\\d+)");

    static int[] stats(Rig r, String read) {
        for (String l : r.log.infos) {
            java.util.regex.Matcher m = ROWNODES.matcher(l);
            if (m.find() && m.group(1).equals(read)) {
                return new int[] {Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))};
            }
        }
        throw new AssertionError("no stats line for " + read);
    }

    static SoapFx.Obj vmLight(int i) {
        return new SoapFx.Obj("VirtualMachine", "vm-" + (1000 + i)).name("light" + i)
                .put("configStatus", SoapFx.en("ManagedEntityStatus", "green"))
                .put("config.firmware", SoapFx.str(i % 2 == 0 ? "efi" : "bios"))
                .put("config.hardware.device", SoapFx.arr("ArrayOfVirtualDevice",
                        SoapFx.typed("VirtualDevice", "VirtualDisk", SoapFx.e("key", "2000")
                                + SoapFx.el("deviceInfo", SoapFx.e("label", "Hard disk 1") + SoapFx.e("summary", "s"))
                                + SoapFx.el("backing", SoapFx.e("fileName", "[ds] a/b.vmdk") + SoapFx.e("split", "false")
                                        + SoapFx.e("writeThrough", "false") + SoapFx.e("eagerlyScrub", "false"))
                                + SoapFx.e("controllerKey", "1000") + SoapFx.e("capacityInKB", "1"))));
    }

    static void paging(FakeVc fake) throws Exception {
        int vms = 2500;
        FakeVcenter vc = new FakeVcenter();
        for (int i = 0; i < vms; i++) vc.add(vmLight(i));
        // one host with a very long option list, only a few of which are wanted
        SoapFx.Obj h = P2Fixtures.hostFull("host-1", "big", "imgmgr-1");
        StringBuilder opts = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            opts.append(SoapFx.typed("OptionValue", "OptionValue", SoapFx.e("key", "Misc.Noise" + i)
                    + "<value xsi:type=\"xsd:long\">" + i + "</value>"));
        }
        opts.append(SoapFx.typed("OptionValue", "OptionValue", SoapFx.e("key", "VMFS3.UseATSForHBOnVMFS5")
                + "<value xsi:type=\"xsd:long\">1</value>"));
        h.put("config.option", SoapFx.arr("ArrayOfOptionValue", opts.toString()));
        vc.add(h);
        vc.installDate.put("imgmgr-1", "2025-01-02T03:04:05Z");
        vc.packages.put("imgmgr-1", "");
        vc.licenses = "";
        vc.options = "";
        try (Rig r = new Rig(fake, vc)) {
            mapAll(r.ops, vc);
            files(r.ops, "", "", "", "");
            long t0 = System.currentTimeMillis();
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure");
            int[] vmStats = stats(r, "R-VM");
            T.eq(vms, vmStats[1], "every VM was read");
            T.eq((vms + 49) / 50, vmStats[0], "in pages of 50");
            T.check(vmStats[4] < 60, "one VM's elements held at a time, not a page's: " + vmStats[4]);
            int[] hoo = stats(r, "R-HOO");
            T.check(hoo[2] > 1_000_000, "the option answer was large: " + hoo[2]);
            T.check(hoo[4] < 30, "yet one host held a handful of elements, not 20000: " + hoo[4]);
            T.eq("1", r.p(0, "res-host-1", V + "Configuration|VAAI|ATS Heartbeat"), "the wanted option was kept");
            for (int i = 0; i < vms; i += 499) {
                T.eq(i % 2 == 0 ? "efi" : "bios", r.p(0, "res-vm-" + (1000 + i), V + "Configuration|Firmware"), "VM " + i);
            }
            T.eq(vms, countWith(r, "Configuration|Config Status", "res-vm-"), "every VM carries its status");
            T.check(System.currentTimeMillis() - t0 < 180_000, "the cycle finished (a bound against a hang, not a speed claim)");
        }
    }

    static int countWith(Rig r, String key, String ridPrefix) {
        int n = 0;
        for (Map.Entry<String, Map<String, Object>> e : r.ops.pushedProps.get(0).entrySet()) {
            if (e.getKey().startsWith(ridPrefix) && e.getValue().containsKey(V + key)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ licenses

    static void licenseEdges(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.licenses = P2Fixtures.license("host-1", null, "KEY00-KEY00-KEY00-KEY00-KEY99", "ed", "2030-01-01T00:00:00Z") // no name
                + P2Fixtures.license("host-1", "Naive", "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE", "ed", "2030-01-01T00:00:00") // no offset
                + P2Fixtures.license("host-1", "Expired", "11111-11111-11111-11111-11111", "ed", "2020-01-01T00:00:00Z")
                + P2Fixtures.license("host-1", "Dup", "FIRST-FIRST-FIRST-FIRST-FIRST", "e1", null)
                + P2Fixtures.license("host-1", "Dup", "LAST0-LAST0-LAST0-LAST0-LAST0", null, null)
                + P2Fixtures.license("host-1", "Eval", "00000-00000-00000-00000-00000", null, null)
                + P2Fixtures.license("host-1", "NoKey", null, null, null)
                + P2Fixtures.license("not-a-host", "Stranger", "ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ", "z", null);
        try (Rig r = standard(fake, vc)) {
            r.run();
            for (String k : r.ops.pushedProps.get(0).get(H).keySet()) {
                T.check(!k.contains("Licensing:Unknown") && !k.contains("Licensing:null") && !k.contains("Licensing:|"),
                        "an unnamed license gets no instance: " + k);
                T.check(!k.contains("Stranger"), "another entity's license is not on this host: " + k);
            }
            eq(r, H, "Licensing:Naive|License Expiration Date", "2030-01-01 00:00:00"); // naive, as Python prints it
            none(r, H, "Licensing:Naive|Remaining Days"); // cannot be computed from a time with no offset
            long gone = Math.floorDiv(Instant.parse("2020-01-01T00:00:00Z").getEpochSecond() - NOW / 1000, 86400);
            T.check(gone < 0, "the license expired");
            eq(r, H, "Licensing:Expired|Remaining Days", Double.valueOf(gone)); // negative once expired
            eq(r, H, "Licensing:Dup|License Key", "*****-*****-*****-*****-LAST0"); // the last one wins
            eq(r, H, "Licensing:Dup|Edition Key", "e1"); // and keeps what only the first had
            eq(r, H, "Licensing:Eval|License Key", "*****-*****-*****-*****-00000");
            none(r, H, "Licensing:Eval|Edition Key");
            eq(r, H, "Licensing:NoKey|Name", "NoKey");
            none(r, H, "Licensing:NoKey|License Key");
        }
        // the license manager does not name its assignment manager
        vc = dataset();
        vc.byType.get("LicenseManager").get(0).vals.clear();
        try (Rig r = standard(fake, vc)) {
            r.run();
            for (String k : r.ops.pushedProps.get(0).get(H).keySet()) T.check(!k.contains("Licensing:"), k);
            T.contains(firstFailure(r, 0), "F-SOAP-R-LIC", "named");
        }
    }

    // ------------------------------------------------------------------ review pass (build 6)

    /** Q4: a string that is present and blank is pushed as upstream pushes it, never replaced or dropped. */
    static void blanks(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        SoapFx.Obj h = new SoapFx.Obj("HostSystem", "host-20").name("blank");
        h.put("runtime.connectionState", SoapFx.en("HostSystemConnectionState", "connected"));
        h.put("summary.maxEVCModeKey", SoapFx.str(""));
        h.put("config.network.pnic", SoapFx.arr("ArrayOfPhysicalNic", SoapFx.el("PhysicalNic",
                SoapFx.e("device", "vmnic9") + SoapFx.e("driverVersion", "") + SoapFx.e("mac", " ") + SoapFx.e("pci", ""))));
        h.put("config.storageDevice.hostBusAdapter", SoapFx.arr("ArrayOfHostHostBusAdapter",
                SoapFx.el("HostHostBusAdapter", SoapFx.e("key", "k-vmhba5") + SoapFx.e("device", "vmhba5")
                        + SoapFx.e("model", "") + SoapFx.e("status", "  ") + SoapFx.e("pci", " p "))));
        vc.add(h);
        vc.add(new SoapFx.Obj("VirtualMachine", "vm-20").name("v")
                .put("guest.customizationInfo.customizationStatus", SoapFx.str("  "))
                .put("guest.guestDetailedData", SoapFx.str("buildNumber='  ' distroName=''"))
                .put("config.hardware.device", SoapFx.arr("ArrayOfVirtualDevice", SoapFx.typed("VirtualDevice", "VirtualVmxnet3",
                        SoapFx.e("key", "4000") + SoapFx.el("deviceInfo", SoapFx.e("label", "  ")) + SoapFx.e("addressType", "")))));
        vc.add(new SoapFx.Obj("VirtualMachine", "vm-21").name("w")
                .put("guest.customizationInfo.customizationStatus", SoapFx.str("")));
        vc.add(new SoapFx.Obj("ClusterComputeResource", "domain-c20").name("c")
                .put("summary", SoapFx.obj("ClusterComputeResourceSummary", SoapFx.e("currentEVCModeKey", "  "))));
        vc.patchLevel = "";
        vc.licenses = P2Fixtures.license("host-20", "L", "", "", null);
        try (Rig r = new Rig(fake, vc)) {
            mapAll(r.ops, vc);
            files(r.ops, "", "", "", "");
            r.run();
            String h20 = "res-host-20";
            eq(r, h20, "Configuration|Max EVC", "");
            eq(r, h20, "Network|Device:vmnic9|Driver Version", "");
            eq(r, h20, "Network|Device:vmnic9|MAC", " ");
            eq(r, h20, "Network|Device:vmnic9|PCI", "");
            eq(r, h20, "Storage Adapter:vmhba5|Model", "null"); // an empty value is falsy for upstream: its "null"
            eq(r, h20, "Storage Adapter:vmhba5|Status", ""); // spaces strip to the empty string
            eq(r, h20, "Storage Adapter:vmhba5|PCI", "p");
            eq(r, h20, "Licensing:L|License Key", "");
            eq(r, h20, "Licensing:L|Edition Key", "");
            eq(r, "res-vm-20", "Guest|Customization Status", "");
            eq(r, "res-vm-20", "Guest|Detailed Data|Build Number", "");
            eq(r, "res-vm-20", "Guest|Detailed Data|Distro Name", "null"); // an empty value is "null"
            eq(r, "res-vm-20", "Network|Network Adapters:|Type", "");
            eq(r, "res-vm-21", "Guest|Customization Status", "null"); // empty: falsy, "null"
            eq(r, "res-domain-c20", "Cluster Configuration|EVC|Enabled", "True"); // a non-empty string is true
            eq(r, "res-domain-c20", "Cluster Configuration|EVC|Mode", "  ");
            eq(r, VC, "Configuration|Patch Level", "");
        }
    }

    /** W5, W7: tokens are released, a missing-set fault is a failed read, a stopped read stops. */
    static void tokensMissingStop(FakeVc fake) throws Exception {
        // the probe over two or more VMs leaves a token; it is cancelled
        FakeVcenter vc = dataset();
        try (Rig r = standard(fake, vc)) {
            vc.add(P2Fixtures.vmSparse("vm-30", "x"));
            r.run();
            T.check(r.count("CancelRetrievePropertiesEx") >= 1, "the probe's token is released");
        }
        // a property vCenter could not read is a failed read of those values
        vc = dataset();
        SoapFx.Obj host = vc.byType.get("HostSystem").get(0);
        host.vals.remove("hardware.biosInfo.vendor");
        host.missing("hardware.biosInfo.vendor");
        try (Rig r = standard(fake, vc)) {
            r.run();
            none(r, H, "Hardware|BIOS Vendor");
            eq(r, H, "Capability|Storage vMotion Supported", "True");
            T.contains(firstFailure(r, 0), "F-SOAP-R-HO", "counted as a failed read");
            T.contains(r.log.all(), "hardware.biosInfo.vendor (NoPermission)", "with what vCenter said");
            T.eq("PARTIAL", r.health(0, "Collector:host|Status"), "partly collected");
        }
        // a read cut at the deadline pages no further and releases what it holds
        vc = new FakeVcenter();
        for (int i = 0; i < 150; i++) vc.add(vmLight(i));
        vc.delayMs.put("RetrievePropertiesEx:VirtualMachine", 6000);
        try (Rig r = new Rig(fake, vc)) {
            mapAll(r.ops, vc);
            files(r.ops, "", "", "", "");
            CycleEngine.Result res = r.engine.run(r.clock.get() + 2000);
            T.check(res.summary.deadlineHit, "deadline");
            // the abandoned read gets its first page, sees it was stopped, and releases what it holds: wait for
            // that to be seen (up to 60 s) rather than for a fixed time
            long until = System.currentTimeMillis() + 60_000;
            while ((r.count("CancelRetrievePropertiesEx") < 1 || r.count("DestroyView") < 1)
                    && System.currentTimeMillis() < until) {
                Thread.sleep(100);
            }
            T.eq(0, r.count("ContinueRetrievePropertiesEx"), "no page after the deadline");
            T.check(r.count("CancelRetrievePropertiesEx") >= 1, "the token it holds is released");
            T.check(r.count("DestroyView") >= 1, "and the view");
        }
        // a failed page also releases its token
        vc = dataset();
        for (int i = 100; i < 160; i++) vc.add(P2Fixtures.vmSparse("vm-" + i, "bulk" + i));
        vc.failContinueAt.put("VirtualMachine", 1);
        try (Rig r = standard(fake, vc)) {
            r.run();
            T.check(r.count("CancelRetrievePropertiesEx") >= 1, "a failed page releases its token");
        }
    }

    /** B3: an entry whose answer fails the VM page costs the entry, not the VM keys. */
    static void optionFailsPage(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.forcedPathMain.put("bigopt", new FakeVc.Resp(200, null, "<oops>"));
        try (Rig r = standard(fake, vc)) {
            files(r.ops, "", "", "", "config.version,bigopt");
            r.run();
            eq(r, VM, "Configuration|Firmware", "efi"); // every base VM key is there
            eq(r, "res-vm-2", "Configuration|Config Status", "gray");
            none(r, VM, "Options|config.version"); // the entries are off until the list changes
            T.contains(r.log.all(), "reading the VMs again without them", "said");
            T.contains(firstFailure(r, 0), "F-SOAP-R-VM", "the failed read is counted");
            T.eq("PARTIAL", r.health(0, "Collector:vm|Status"), "partly collected");
            int asked = countAsking(r, "bigopt");
            r.run();
            T.eq(asked, countAsking(r, "bigopt"), "and not asked for again");
        }
    }

    /** W9: when the one call for every assignment fails, each host is asked alone. */
    static void licensesPerHost(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.licensesNeedEntity = true;
        try (Rig r = standard(fake, vc)) {
            r.run();
            eq(r, H, "Licensing:VMware vSphere 8 Enterprise Plus|License Key", "*****-*****-*****-*****-MN90P");
            eq(r, H, "Licensing:VMware vSAN|Edition Key", "vsan.std");
            T.contains(firstFailure(r, 0), "F-SOAP-R-LIC", "the failed single call is counted");
            T.check(r.count("QueryAssignedLicenses") >= 4, "one call, then one per connected host: " + r.count("QueryAssignedLicenses"));
            for (FakeVc.Req q : r.reqs("QueryAssignedLicenses")) {
                if (q.body.contains("<entityId>")) T.notContains(q.body, "host-3", "not for a host that is not connected");
            }
        }
    }

    /** N8: a host that left between the two host reads costs its own options, not every host's. */
    static void hostLeaves(FakeVc fake) throws Exception {
        FakeVcenter vc = dataset();
        vc.forcedPath.put("config.option", FakeVc.fault("ManagedObjectNotFound", "the host is gone"));
        try (Rig r = standard(fake, vc)) {
            r.run();
            int single = 0;
            for (FakeVc.Req q : r.reqs("RetrievePropertiesEx")) {
                if (q.body.contains("config.option") && q.body.split("<objectSet>").length == 2) single++;
            }
            T.check(single >= 2, "after the refusal each host is asked for alone: " + single);
            eq(r, H, "Configuration|Config Status", "green"); // the rest of the host is untouched
            T.contains(firstFailure(r, 0), "F-SOAP-R-HOO", "counted");
        }
    }
}
