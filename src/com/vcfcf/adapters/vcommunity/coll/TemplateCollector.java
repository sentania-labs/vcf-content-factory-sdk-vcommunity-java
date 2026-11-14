package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.text.Quirks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The VM Template objects (design 4.11, rows R227 to R233): one per virtual machine that vCenter
 * marks as a template, built from the VM read and the datastore and folder names read beside
 * it. Every template of the vCenter gets one, whether or not the vCenter adapter also models it
 * as a VM (DV-47; in the lab's Python baseline all ten were). A pure function of what was read.
 *
 * <p>Honest by construction: a datastore or folder whose name a failed read left unknown
 * withholds that property and is counted; a figure the VM read did not return is not sent as
 * zero (DV-11). A text it did not return is the empty text, as the original sends it (Q8,
 * {@link Quirks#ABSENT_OWN_TEXT_IS_EMPTY}).
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
        return collect(scan, vcid, dsNames, folderNames, failures, Quirks.ABSENT_OWN_TEXT_IS_EMPTY);
    }

    /** {@link #collect(TemplateScan, String, Map, Map, Failures)} with the absent-text switch given. */
    public static Out collect(TemplateScan scan, String vcid, Map<String, String> dsNames,
            Map<String, String> folderNames, Failures failures, boolean absentTextIsEmpty) {
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
            out.own.add(one(r, vcid, dsNames, folderNames, failures, absentTextIsEmpty));
            // a name that a failed read left unknown is a withheld value, whatever collector counted the read
            if (dsNames == null && r.datastores != null) partial = true;
            if (folderNames == null && r.parent != null) partial = true;
        }
        if (whole) out.own.read(OwnObjects.TEMPLATE);
        out.status = !partial && failures.forCollector(COLLECTOR) == 0 ? Status.OK : Status.PARTIAL;
        return out;
    }

    private static OwnObject one(TemplateScan.Row r, String vcid, Map<String, String> dsNames,
            Map<String, String> folderNames, Failures failures, boolean empty) {
        OwnObject o = new OwnObject(OwnObjects.TEMPLATE, r.name)
                .id("VMEntityObjectID", r.moref).id("VMEntityVCID", vcid);
        o.propY(T + "Guest OS", Quirks.ownText(r.guestOs, empty));
        o.propY(T + "Hardware Version", Quirks.ownText(r.hardwareVersion, empty));
        // a figure the VM read did not return is the original's 0 (Q9), as text: "0", "0.0", and 0.0
        o.propY(T + "vCPUs", r.numCpu != null ? r.numCpu : Quirks.orText(null, "0", Quirks.UNSET_AS_UPSTREAM));
        o.propY(T + "Memory (GB)", memory(r.memoryMb));
        o.propY(T + "Datastore", datastores(r, dsNames, failures, empty));
        o.propY(T + "VM Folder", folder(r, folderNames, failures, empty));
        o.statY(T + "Disk Used (GB)", disk(r.committedBytes));
        return o;
    }

    /** {@code str(round(MB / 1024, 2))}: {@code 8.0}, {@code 0.5}, {@code 1.75}. */
    static String memory(String mb) {
        Double v = number(mb);
        if (v == null) return Quirks.orText(null, "0.0", Quirks.UNSET_AS_UPSTREAM);
        return PyText.repr(PyRound.round2(v / MB_PER_GB));
    }

    /** {@code round(bytes / 1024**3, 2)}. */
    static Double disk(String bytes) {
        Double v = number(bytes);
        return v == null ? (Quirks.UNSET_AS_UPSTREAM ? Double.valueOf(0) : null) : PyRound.gib(v);
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
    private static String datastores(TemplateScan.Row r, Map<String, String> dsNames, Failures failures,
            boolean empty) {
        if (r.datastores == null) return Quirks.ownText(null, empty); // the path was not returned
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

    private static String folder(TemplateScan.Row r, Map<String, String> folderNames, Failures failures,
            boolean empty) {
        if (r.parent == null) return Quirks.ownText(null, empty); // no parent was returned
        if (folderNames == null) return null; // the folder read failed; counted there
        String n = folderNames.get(r.parent);
        if (n == null) {
            failures.add(COLLECTOR, FailureCause.soap("R-FO"), r.parent, 1,
                    "the folder " + r.parent + " of template " + r.moref + " has no name in the folder read");
        }
        return n;
    }
}
