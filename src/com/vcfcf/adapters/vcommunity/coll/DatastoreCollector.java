package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The datastore keys (design 4.6, rows R47-R55). VMFS-only keys are sent for
 * a datastore that has VMFS information and for no other; the extent count is
 * a count of the extents read, so a datastore with no VMFS information has
 * none to count and sends nothing (DV-11).
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
        o.propY(SUM + "Status", Vals.scalar(row, "overallStatus"));
        o.propY(SUM + "SIOC Enabled", Vals.scalar(row, "iormConfiguration.enabled"));
        o.propY(SUM + "SIOC Threshold", Vals.scalar(row, "iormConfiguration.congestionThreshold"));
        XmlNode vmfs = Vals.at(row.val("info"), "vmfs");
        if (vmfs == null) {
            // Not VMFS, or no information returned: the extent count and the VMFS keys have no source.
            o.propY(ID + "Number of Extents", null);
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
