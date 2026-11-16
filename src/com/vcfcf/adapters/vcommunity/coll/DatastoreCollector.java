package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The datastore keys (design 4.6, rows R47-R55). VMFS-only keys are sent for
 * a datastore that has VMFS information and for no other; the extent count is
 * a count of the extents read; a datastore with no VMFS information (NFS, vSAN, vVol) has no count and sends
 * none (DV-112).
 */
public final class DatastoreCollector {

    public static final String KIND = "Datastore";
    public static final String READ = "R-DS";
    public static final int PAGE = 100;

    private DatastoreCollector() {}

    private static final String ID = Vals.P + "Identifiers|";
    private static final String SUM = Vals.P + "Summary|";

    public static RetrieveRequest request() {
        return new RetrieveRequest(READ, KIND, PAGE, null)
                .path("overallStatus")
                .path("iormConfiguration.enabled")
                .path("iormConfiguration.congestionThreshold")
                .path("info", PathSpec.keeping("vmfs.extent.diskName", "vmfs.blockSizeMb",
                        "vmfs.maxBlocks", "vmfs.vmfsUpgradable"));
    }

    public static ObjectOut collect(BulkRow row) {
        ObjectOut o = new ObjectOut(KIND, row.obj.value);
        o.prop(SUM + "MOID", row.obj.value);
        Vals.prop(o, row, SUM + "Status", "overallStatus");
        Vals.prop(o, row, SUM + "SIOC Enabled", "iormConfiguration.enabled");
        Vals.prop(o, row, SUM + "SIOC Threshold", "iormConfiguration.congestionThreshold");
        XmlNode vmfs = Vals.at(row.val("info"), "vmfs");
        if (vmfs == null) {
            // Not VMFS (NFS, vSAN, vVol), or no information on a read that worked: there is no extent list to
            // count, so nothing is sent (DV-112); the VMFS keys have no source either. An info that could not be
            // read is withheld and counted.
            boolean read = row.val("info") != null || row.isUnset("info");
            if (!read) o.withhold(1);
            return o;
        }
        o.prop(SUM + "Block Size", Vals.integer(vmfs, "blockSizeMb"));
        o.prop(SUM + "Max Blocks", Vals.integer(vmfs, "maxBlocks"));
        o.prop(SUM + "VMFS Upgradable", Vals.bool(vmfs, "vmfsUpgradable"));
        List<String> disks = new ArrayList<>();
        for (XmlNode e : Vals.items(vmfs)) {
            if (!"extent".equals(e.name)) continue;
            String d = Vals.str(e, "diskName");
            if (d != null && !d.isEmpty()) disks.add(d);
        }
        o.prop(ID + "Number of Extents", Integer.toString(disks.size()));
        for (int i = 0; i < disks.size(); i++) {
            o.prop(ID + "Extent:" + (i + 1) + "|ID", disks.get(i));
        }
        return o;
    }
}
