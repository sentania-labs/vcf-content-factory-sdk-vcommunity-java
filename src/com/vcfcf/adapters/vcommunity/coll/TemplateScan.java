package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The virtual machines that are templates, taken from the rows of the one VM read as they
 * stream past (design 4.11: no read of its own). A row keeps only what a template object uses.
 * A VM whose {@code config.template} was not returned is counted as unknown: vCenter leaves
 * {@code config} unset on a VM it cannot read (an inaccessible or orphaned one), and such a VM
 * may be a template, so a read with one is not proof that a template is gone.
 */
public final class TemplateScan {

    /** The paths of the VM read that the template object needs, on top of the VM keys' own. */
    public static final String[] PATHS = {"config.template", "config.guestFullName", "config.version",
        "summary.config.numCpu", "summary.config.memorySizeMB", "summary.storage.committed",
        "datastore", "parent"};

    /** One template as read; every field is the text as returned, or null when it was not returned. */
    public static final class Row {
        public String moref;
        public String name;
        public String guestOs;
        public String hardwareVersion;
        public String numCpu;
        public String memoryMb;
        public String committedBytes;
        /** The datastore MoRef values in the order given; null when the path was not returned. */
        public List<String> datastores;
        /** The parent folder's MoRef value, or null. */
        public String parent;
    }

    public final List<Row> rows = new ArrayList<>();
    /** VMs whose {@code config.template} was not returned: not known to be templates or not. */
    public int unknown;
    /** Rows this class failed to read (a defect, counted so the read is not called whole). */
    public int broken;

    /** Forget everything: the VM read is being made again. */
    public void reset() {
        rows.clear();
        unknown = 0;
        broken = 0;
    }

    /** Note one VM row: a template is kept, any other VM is not. Never throws. */
    public void take(BulkRow row) {
        try {
            XmlNode flag = row.val("config.template");
            String b = flag == null ? null : PyText.bool(flag.text());
            if (b == null) {
                unknown++;
                return;
            }
            if (!b.equals("True")) return;
            Row r = new Row();
            r.moref = row.obj.value;
            r.name = row.name();
            r.guestOs = Vals.scalar(row, "config.guestFullName");
            r.hardwareVersion = Vals.scalar(row, "config.version");
            r.numCpu = Vals.scalar(row, "summary.config.numCpu");
            r.memoryMb = Vals.scalar(row, "summary.config.memorySizeMB");
            r.committedBytes = Vals.scalar(row, "summary.storage.committed");
            XmlNode ds = row.val("datastore");
            if (ds != null) {
                r.datastores = new ArrayList<>();
                for (XmlNode d : Vals.items(ds)) {
                    if (!d.text().isEmpty()) r.datastores.add(d.text());
                }
            }
            XmlNode p = row.val("parent");
            r.parent = p == null || p.text().isEmpty() ? null : p.text();
            rows.add(r);
        } catch (RuntimeException e) {
            broken++;
        }
    }
}
