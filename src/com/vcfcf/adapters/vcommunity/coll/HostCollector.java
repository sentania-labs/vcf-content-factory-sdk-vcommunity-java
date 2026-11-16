package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.LicenseKeyMask;
import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The host keys (design 4.4, rows R1-R46, R255). A host that is not connected
 * gets none (parity, issue 86). A value the read found unset is published as the
 * original prints it (Q9: {@code None}, {@code null}, {@code N/A}, {@code []}, a
 * count of 0); one whose read failed is withheld, and a failed per-host call is
 * reported by the caller, not turned into a value.
 */
public final class HostCollector {

    public static final String KIND = "HostSystem";
    public static final String READ = "R-HO";
    public static final String READ_OPTIONS = "R-HOO";
    public static final int PAGE = 50;
    public static final int PAGE_OPTIONS = 25;
    /** The two VAAI options (rows R8, R9). */
    public static final String ATS_HEARTBEAT = "VMFS3.UseATSForHBOnVMFS5";
    public static final String ATS_LOCKING = "VMFS3.HardwareAcceleratedLocking";

    private HostCollector() {}

    private static final String CONF = Vals.P + "Configuration|";
    private static final String NET = Vals.P + "Network|";

    public static RetrieveRequest request() {
        return request(false);
    }

    /** @param guest true when the guest phase is on: the thumbprint vCenter recorded for the host is read too */
    public static RetrieveRequest request(boolean guest) {
        RetrieveRequest r = new RetrieveRequest(READ, KIND, PAGE, null);
        if (guest) r.path(com.vcfcf.adapters.vcommunity.guest.GuestRoster.HOST_PATH);
        return r
                .path("runtime.connectionState").path("runtime.bootTime")
                .path("configStatus").path("summary.maxEVCModeKey")
                .path("config.network.pnic", PathSpec.keepingInElements("device", "driverVersion",
                        "firmwareVersion", "linkSpeed.duplex", "pci", "mac", "wakeOnLanSupported"))
                .path("config.network.dnsConfig", PathSpec.keeping("dhcp", "searchDomain"))
                .path("config.dateTimeInfo.timeZone.name")
                .path("config.dateTimeInfo.timeZone.description")
                .path("config.dateTimeInfo.timeZone.gmtOffset")
                .path("config.storageDevice.hostBusAdapter", PathSpec.keepingInElements("device", "key", "bus",
                        "model", "pci", "status", "storageProtocol"))
                .path("hardware.biosInfo.vendor")
                .path("hardware.pciDevice", PathSpec.keepingInElements("id", "vendorId", "deviceId",
                        "subVendorId", "vendorName", "deviceName"))
                .path("capability.storageVMotionSupported")
                .path("configManager.imageConfigManager")
                // for the cluster membership and the port group count (P4); not keys of their own
                .path("parent", PathSpec.shallow())
                .path("config.network.portgroup", PathSpec.keepingInElements("spec.name"));
    }

    /** The option read: only the options these keys use are kept, the rest of each host's list is skipped. */
    public static RetrieveRequest optionsRequest(List<com.vcfcf.adapters.vcommunity.vim.MoRef> hosts,
            java.util.Set<String> wanted) {
        return new RetrieveRequest(READ_OPTIONS, KIND, PAGE_OPTIONS, hosts)
                .path("config.option", PathSpec.elements(e -> wanted.contains(e.childText("key"))));
    }

    /** True for a host that is connected; the read returned its state. */
    public static boolean isConnected(BulkRow row) {
        return "connected".equals(Vals.scalar(row, "runtime.connectionState"));
    }

    /** One license assignment of a host, as the license manager returned it. */
    public static final class Assignment {
        public final String name;
        public final String licenseKey;
        public final String editionKey;
        public final String expiration;

        public Assignment(String name, String licenseKey, String editionKey, String expiration) {
            this.name = name;
            this.licenseKey = licenseKey;
            this.editionKey = editionKey;
            this.expiration = expiration;
        }
    }

    /** One software package of a host. */
    public static final class Pkg {
        public final String name;
        public final String version;
        public final String acceptanceLevel;
        public final Boolean maintenanceModeRequired;
        public final String summary;
        public final String type;
        public final String vendor;

        public Pkg(String name, String version, String acceptanceLevel,
                Boolean maintenanceModeRequired, String summary, String type, String vendor) {
            this.name = name;
            this.version = version;
            this.acceptanceLevel = acceptanceLevel;
            this.maintenanceModeRequired = maintenanceModeRequired;
            this.summary = summary;
            this.type = type;
            this.vendor = vendor;
        }
    }

    /** What the per-host calls and the option read gave; a field is null when its read did not return. */
    public static final class Extras {
        /** The install date as ISO text, or null (not returned, or the call failed). */
        public String installDate;
        /** The host has no image manager, or the call was made and answered: a null date is then "no date", not a failure. */
        public boolean installDateRead;
        /** The wanted options this host has, by key; null when the option read of this host failed. */
        public Map<String, String> options;
        /** The listed packages this host has; null when the call was not made or failed. */
        public List<Pkg> packages;
    }

    /**
     * The keys the host's own read gives. A host that is not connected gets none (null).
     */
    public static ObjectOut collectRow(BulkRow row) {
        if (!isConnected(row)) return null;
        ObjectOut o = new ObjectOut(KIND, row.obj.value);
        o.propY(CONF + "Config Status", Vals.scalarOrNone(row, "configStatus"));
        // no maximum EVC mode: the original's default, the text of an empty list (Q9)
        String maxEvc = Vals.scalar(row, "summary.maxEVCModeKey");
        o.propY(CONF + "Max EVC", maxEvc != null ? Quirks.text(maxEvc)
                : row.isUnset("summary.maxEVCModeKey") ? Quirks.orText(null, "[]", Quirks.UNSET_AS_UPSTREAM) : null);
        XmlNode dns = row.val("config.network.dnsConfig");
        boolean dnsRead = dns != null || row.isUnset("config.network.dnsConfig");
        // no DNS configuration: the original's "null"; no search domain: "null" too
        // no DNS configuration at all: the original's "null"; one without the flag: str(None)
        o.propY(NET + "DHCP", dns == null ? (dnsRead ? Quirks.orNull(null) : null)
                : Quirks.orNone(Vals.bool(dns, "dhcp")));
        o.propY(NET + "Search Domain", dnsRead ? Quirks.orNull(searchDomain(dns)) : null);
        o.propY(CONF + "Time Zone|Name", Vals.scalarOrNone(row, "config.dateTimeInfo.timeZone.name"));
        o.propY(CONF + "Time Zone|Description", Vals.scalarOrNone(row, "config.dateTimeInfo.timeZone.description"));
        o.propY(CONF + "Time Zone|GMT Offset", Vals.scalarOrNone(row, "config.dateTimeInfo.timeZone.gmtOffset"));
        o.propY(Vals.P + "Runtime|Boot Time", Vals.scalarOrNone(row, "runtime.bootTime"));
        o.propY(Vals.P + "Hardware|BIOS Vendor", Vals.scalarOrNone(row, "hardware.biosInfo.vendor"));
        o.propY(Vals.P + "Capability|Storage vMotion Supported",
                Vals.scalarOrNone(row, "capability.storageVMotionSupported"));
        adapters(o, row, row.val("config.storageDevice.hostBusAdapter"));
        uplinks(o, row, row.val("config.network.pnic"), row.val("hardware.pciDevice"));
        return o;
    }

    /**
     * Add the keys the per-host calls, the option read and the license read gave.
     *
     * @param advSettings the ESXi Advanced System Settings list in force (empty: no block)
     */
    public static void addExtras(ObjectOut o, Extras x, List<String> advSettings) {
        // a host with no image manager, or one that answered no date, is the original's "null" (Q9); a
        // call that failed is not an answer and sends nothing
        o.propY(CONF + "Install Date|UTC", x.installDate != null ? x.installDate
                : x.installDateRead ? Quirks.orNull(null) : null);
        options(o, x.options, advSettings);
        packages(o, x.packages);
    }

    private static void options(ObjectOut o, Map<String, String> options, List<String> adv) {
        // an option the host does not list is the original's "null" (Q9); no answer at all sends nothing
        o.propY(CONF + "VAAI|ATS Heartbeat", options == null ? null : Quirks.orNull(options.get(ATS_HEARTBEAT)));
        o.propY(CONF + "VAAI|ATS Locking", options == null ? null : Quirks.orNull(options.get(ATS_LOCKING)));
        if (options == null) return;
        for (String item : adv) {
            String v = options.get(item);
            if (v != null) o.prop(CONF + "Advanced System Settings|" + item, v);
        }
    }

    private static String searchDomain(XmlNode dns) {
        XmlNode list = dns;
        if (list == null) return null;
        StringBuilder b = new StringBuilder();
        for (XmlNode d : list.children()) {
            if (!"searchDomain".equals(d.name)) continue;
            String t = PyText.strip(d.rawText());
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(t);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static void adapters(ObjectOut o, BulkRow row, XmlNode list) {
        if (list == null) {
            // the list was not returned (unset, or not read): no count is known, and a 0 would stand for it (DV-111).
            // A list that came back empty is a real count of 0 and is published below.
            o.propY(Vals.P + "Storage Adapter|Number of HBA", null);
            return;
        }
        List<XmlNode> hbas = Vals.items(list);
        o.prop(Vals.P + "Storage Adapter|Number of HBA", Integer.toString(hbas.size()));
        for (XmlNode h : hbas) {
            String dev = Vals.str(h, "device");
            if (dev == null || dev.isEmpty() || dev.equals("null")) {
                String key = Vals.str(h, "key");
                dev = key == null ? null : key.substring(key.lastIndexOf('-') + 1);
            }
            if (dev == null || dev.isEmpty() || dev.equals("unknown_hba")) {
                o.withhold(6); // an adapter we cannot name is not published under an invented name
                continue;
            }
            String p = Vals.P + "Storage Adapter:" + dev + "|";
            o.prop(p + "Device", dev);
            // a field the adapter does not have is the original's "null" (Q9)
            o.propY(p + "Bus", Quirks.orNull(Vals.integer(h, "bus")));
            o.propY(p + "Model", Quirks.orNull(Quirks.strippedIfTruthy(Vals.str(h, "model"))));
            o.propY(p + "PCI", Quirks.orNull(Quirks.strippedIfTruthy(Vals.str(h, "pci"))));
            o.propY(p + "Status", Quirks.orNull(Quirks.strippedIfTruthy(Vals.str(h, "status"))));
            o.propY(p + "Type", Quirks.orNull(Quirks.strippedIfTruthy(Vals.str(h, "storageProtocol"))));
        }
    }

    /** {@code N/A}: the original's text for a NIC's PCI field when the NIC has no PCI device, or the device lacks it (Q9). */
    private static String na(String v) {
        return Quirks.orText(v, "N/A", Quirks.UNSET_AS_UPSTREAM);
    }

    private static void uplinks(ObjectOut o, BulkRow row, XmlNode pnics, XmlNode pciList) {
        if (pnics == null) return;
        boolean pciRead = pciList != null || row.isUnset("hardware.pciDevice");
        Map<String, XmlNode> pci = new HashMap<>();
        for (XmlNode d : Vals.items(pciList)) {
            String id = Vals.str(d, "id");
            if (id != null && !id.isEmpty()) pci.put(id, d);
        }
        for (XmlNode n : Vals.items(pnics)) {
            String dev = Vals.str(n, "device");
            if (dev == null || dev.isEmpty()) {
                o.withhold(13);
                continue;
            }
            String p = NET + "Device:" + dev + "|";
            String pciId = Vals.str(n, "pci");
            XmlNode pd = pciId == null || pciId.isEmpty() ? null : pci.get(pciId);
            boolean up = n.child("linkSpeed") != null;
            o.prop(p + "Device Name", dev);
            o.prop(p + "Driver Version", Quirks.text(Vals.str(n, "driverVersion")));
            o.prop(p + "Firmware Version", Quirks.text(Vals.str(n, "firmwareVersion")));
            o.prop(p + "Status", up ? "Connected" : "Disconnected");
            // no PCI device for the NIC: the original's "N/A" (Q9), unless the device list itself did not read
            if (pd == null) {
                String none = pciRead ? na(null) : null;
                o.propY(p + "PCI Vendor Name", none);
                o.propY(p + "PCI Device Name", none);
                o.propY(p + "PCI Vendor ID (VID)", none);
                o.propY(p + "PCI Device ID (DID)", none);
                o.propY(p + "PCI SubVendor ID (SVID)", none);
            } else {
                o.propY(p + "PCI Vendor Name", Quirks.text(Vals.str(pd, "vendorName")));
                o.propY(p + "PCI Device Name", Quirks.text(Vals.str(pd, "deviceName")));
                o.propY(p + "PCI Vendor ID (VID)", na(Quirks.pciId(Vals.str(pd, "vendorId"))));
                o.propY(p + "PCI Device ID (DID)", na(Quirks.pciId(Vals.str(pd, "deviceId"))));
                o.propY(p + "PCI SubVendor ID (SVID)", na(Quirks.pciId(Vals.str(pd, "subVendorId"))));
            }
            o.propY(p + "MAC", Quirks.orNone(Quirks.text(Vals.str(n, "mac"))));
            o.propY(p + "Wake On Lan Supported", Quirks.orNone(Vals.bool(n, "wakeOnLanSupported")));
            o.propY(p + "PCI", Quirks.orNone(Quirks.text(pciId)));
            // full duplex only when the link has a speed and says so; a link that is down is half-duplex
            // to the original (the text for both, and for a link with no duplex flag)
            String duplex = up ? Vals.bool(n.child("linkSpeed"), "duplex") : null;
            o.prop(p + "Duplex", Quirks.DUPLEX_HALF_WHEN_DOWN || up
                    ? ("True".equals(duplex) ? "full-duplex" : "half-duplex")
                    : duplex == null ? null : duplex.equals("True") ? "full-duplex" : "half-duplex");
        }
    }

    private static void packages(ObjectOut o, List<Pkg> packages) {
        if (packages == null) return;
        for (Pkg k : packages) {
            String p = CONF + "Packages:" + k.name + "|";
            o.prop(p + "Package Name", k.name);
            o.prop(p + "Package Version", Quirks.text(k.version));
            o.prop(p + "Acceptance Level", Quirks.text(k.acceptanceLevel));
            if (k.maintenanceModeRequired != null) {
                o.numProp(p + "Maintenance Mode Required", k.maintenanceModeRequired ? 1.0 : 0.0);
            }
            o.prop(p + "Package Summary", Quirks.text(k.summary));
            o.prop(p + "Package Type", Quirks.text(k.type));
            o.prop(p + "Package Vendor", Quirks.text(k.vendor));
        }
    }

    /**
     * Add the license keys of the host.
     *
     * @param list  the host's assignments; null when the license read failed (nothing is sent)
     * @param nowMs the cycle's clock, for the days a license has left
     */
    public static void addLicenses(ObjectOut o, List<Assignment> list, long nowMs) {
        if (list == null) return;
        for (Assignment a : list) {
            if (a.name == null) {
                o.withhold(5); // no name, no instance to publish under (DV-13)
                continue;
            }
            String p = Vals.P + "Licensing:" + a.name + "|";
            o.prop(p + "Name", a.name);
            o.propY(p + "License Key", LicenseKeyMask.mask(a.licenseKey));
            o.propY(p + "Edition Key", Quirks.text(a.editionKey));
            if (a.expiration == null) continue; // perpetual or evaluation: nothing to publish
            o.prop(p + "License Expiration Date", PyText.dateTime(a.expiration));
            Instant exp = PyText.instant(a.expiration);
            if (exp != null) {
                long days = Math.floorDiv(Duration.between(Instant.ofEpochMilli(nowMs), exp).getSeconds(),
                        86400L);
                o.stat(p + "Remaining Days", days);
            }
        }
    }
}
