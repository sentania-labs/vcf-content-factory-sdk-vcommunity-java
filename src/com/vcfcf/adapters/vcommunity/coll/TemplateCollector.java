package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.PyText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The VM Template objects (design 4.11, rows R227 to R233): one per virtual machine that vCenter
 * marks as a template, built from the VM read and the datastore and folder names read beside
 * it. Every template of the vCenter gets one, whether or not the vCenter adapter also models it
 * as a VM (DV-47; in the lab's Python baseline all ten were). A pure function of what was read.
 *
 * <p>Honest by construction: a property whose read failed (a missing-set fault, a refused path), and
 * a datastore or folder whose name a failed read left unknown, are withheld and counted. A property
 * that was read and is unset is not sent and not counted (DV-112).
 */
public final class TemplateCollector {

    public static final String COLLECTOR = "template";
    public static final String T = Vals.P + "VM Template|";
    private static final double MB_PER_GB = 1024.0;

    private TemplateCollector() {}

    /** What the templates gave. */
    public static final class Out {
        public final OwnObjects own = OwnObjects.none();
        public Status status = Status.OK;
    }

    /**
     * @param scan        the templates the VM read found
     * @param vcid        the vCenter's instance UUID (part of every template's identity)
     * @param dsNames     datastore MoRef value to name; null when the datastore read failed
     * @param folderNames folder MoRef value to name; null when the folder read failed
     */
    public static Out collect(TemplateScan scan, String vcid, Map<String, String> dsNames,
            Map<String, String> folderNames, Failures failures) {
        Out out = new Out();
        if (vcid == null || vcid.trim().isEmpty()) {
            out.status = Status.SKIPPED;
            return out;
        }
        boolean whole = scan.unknown == 0 && scan.broken == 0;
        boolean partial = false;
        if (scan.broken > 0) {
            failures.add(COLLECTOR, FailureCause.collect("R-VM"), "templates", scan.broken * 7,
                    scan.broken + " virtual machine row(s) could not be read as templates");
        }
        for (TemplateScan.Row r : scan.rows) {
            if (r.name == null || r.name.trim().isEmpty()) {
                whole = false;
                failures.add(COLLECTOR, FailureCause.soap("R-VM"), r.moref, 7,
                        "the template has no name, so it cannot be given an object");
                continue;
            }
            out.own.add(one(r, vcid, dsNames, folderNames, failures));
            // a name that a failed read left unknown is a withheld value, whatever collector counted the read
            if (dsNames == null && r.datastores != null) partial = true;
            if (folderNames == null && r.parent != null) partial = true;
            if (!r.unread.isEmpty()) partial = true; // a property vCenter could not read for this template
        }
        if (whole) out.own.read(OwnObjects.TEMPLATE);
        out.status = !partial && failures.forCollector(COLLECTOR) == 0 ? Status.OK : Status.PARTIAL;
        return out;
    }

    private static OwnObject one(TemplateScan.Row r, String vcid, Map<String, String> dsNames,
            Map<String, String> folderNames, Failures failures) {
        OwnObject o = new OwnObject(OwnObjects.TEMPLATE, r.name)
                .id("VMEntityObjectID", r.moref).id("VMEntityVCID", vcid);
        // a path whose read did not work is unknown: it is withheld and counted. A path that read and has no value
        // sends nothing and is not counted (DV-112)
        if (!r.read("config.guestFullName")) o.withhold(1); else o.prop(T + "Guest OS", r.guestOs);
        if (!r.read("config.version")) o.withhold(1); else o.prop(T + "Hardware Version", r.hardwareVersion);
        if (r.numCpu != null) o.prop(T + "vCPUs", r.numCpu); else if (!r.read("summary.config.numCpu")) o.withhold(1);
        if (r.memoryMb != null) {
            String m = memory(r.memoryMb);
            if (m == null) o.withhold(1); else o.prop(T + "Memory (GB)", m);
        } else if (!r.read("summary.config.memorySizeMB")) {
            o.withhold(1);
        }
        if (!r.read("datastore")) {
            o.withhold(1);
        } else if (r.datastores != null) {
            String d = datastores(r, dsNames, failures);
            if (d == null) o.withhold(1); else o.prop(T + "Datastore", d);
        }
        if (!r.read("parent")) {
            o.withhold(1);
        } else if (r.parent != null) {
            String f = folder(r, folderNames, failures);
            if (f == null) o.withhold(1); else o.prop(T + "VM Folder", f);
        }
        if (r.committedBytes != null) {
            Double d = disk(r.committedBytes);
            if (d == null) o.withhold(1); else o.stat(T + "Disk Used (GB)", d);
        } else if (!r.read("summary.storage.committed")) {
            o.withhold(1);
        }
        return o;
    }

    /** {@code str(round(MB / 1024, 2))}: {@code 8.0}, {@code 0.5}, {@code 1.75}; null when MB is not a number. */
    static String memory(String mb) {
        Double v = number(mb);
        if (v == null) return null;
        return PyText.repr(PyRound.round2(v / MB_PER_GB));
    }

    /** {@code round(bytes / 1024**3, 2)}; null when the text is not a number. */
    static Double disk(String bytes) {
        Double v = number(bytes);
        return v == null ? null : PyRound.gib(v);
    }

    private static Double number(String integerText) {
        if (integerText == null) return null;
        try {
            return Double.parseDouble(integerText);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The names of the datastores in the order the VM lists them, joined with a comma and a space. */
    private static String datastores(TemplateScan.Row r, Map<String, String> dsNames, Failures failures) {
        if (dsNames == null) return null; // the datastore read failed; counted there
        List<String> names = new ArrayList<>();
        for (String id : r.datastores) {
            String n = dsNames.get(id);
            if (n == null) {
                failures.add(COLLECTOR, FailureCause.soap("R-DS"), id, 1,
                        "the datastore " + id + " of template " + r.moref + " has no name in the datastore read");
                return null;
            }
            names.add(n);
        }
        return String.join(", ", names);
    }

    private static String folder(TemplateScan.Row r, Map<String, String> folderNames, Failures failures) {
        if (folderNames == null) return null; // the folder read failed; counted there
        String n = folderNames.get(r.parent);
        if (n == null) {
            failures.add(COLLECTOR, FailureCause.soap("R-FO"), r.parent, 1,
                    "the folder " + r.parent + " of template " + r.moref + " has no name in the folder read");
        }
        return n;
    }
}
