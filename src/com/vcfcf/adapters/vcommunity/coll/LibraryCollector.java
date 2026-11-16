package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.rest.LibraryData;
import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.PyText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Content Library and Library Item objects (design 4.11, rows R234 to R252): one object per
 * library and one per item, an item the child of its library. A pure function of what the REST
 * read returned.
 *
 * <p>Honest by construction. A library that could not be read gets no object; an item that could
 * not be read gets none and withholds the aggregates of its library, which would otherwise count
 * the items that happened to answer. A text or figure the answer does not have is not sent (DV-112). Items Not
 * Stored Locally and Total Size are sent only when every item carries the field (a partial figure would read as the
 * whole); Items Not Security Compliant counts the items that have a compliance verdict and is not sent when none has
 * one. A returned empty text is sent as it is (Q4), and a library with no items has zero of everything, which is
 * what was read.
 */
public final class LibraryCollector {

    public static final String COLLECTOR = "library";
    public static final String LIB = Vals.P + "Content Library|";
    public static final String ITEM = Vals.P + "Library Item|";

    private LibraryCollector() {}

    /** What the libraries gave. */
    public static final class Out {
        public final OwnObjects own = OwnObjects.none();
        public Status status = Status.OK;
    }

    public static Out collect(LibraryData d, Failures failures) {
        Out out = new Out();
        for (LibraryData.ReadFailure f : d.failures) {
            failures.add(COLLECTOR, f.cause, f.object, f.keysWithheld, f.message);
        }
        if (d.listFailure != null) {
            out.status = d.deadlineHit ? Status.NOT_RUN : Status.FAILED;
            return out;
        }
        if (d.deadlineHit) {
            failures.add(COLLECTOR, FailureCause.DEADLINE, "content libraries", 0,
                    "the cycle deadline was reached while the content libraries were being read");
        }
        boolean librariesRead = !d.deadlineHit;
        boolean itemsRead = !d.deadlineHit;
        for (LibraryData.Library lib : d.libraries) {
            if (lib.gone) continue; // deleted since the list: not a library any more, and not a failed read
            if (lib.notRun) {
                librariesRead = false;
                itemsRead = false;
                continue;
            }
            if (lib.detail == null) { // its failure is counted from the read
                librariesRead = false;
                itemsRead = false;
                continue;
            }
            String name = PyText.json(lib.detail.get("name"));
            if (name == null || name.trim().isEmpty()) {
                failures.add(COLLECTOR, FailureCause.REST_LIB, "library " + lib.id, 8,
                        "the library has no name, so it cannot be given an object");
                librariesRead = false;
                itemsRead = false;
                continue;
            }
            boolean whole = library(out, lib, name, failures);
            if (!whole) itemsRead = false;
        }
        if (librariesRead) out.own.read(OwnObjects.LIBRARY);
        if (itemsRead) out.own.read(OwnObjects.ITEM);
        out.status = failures.forCollector(COLLECTOR) == 0 && !d.deadlineHit ? Status.OK : Status.PARTIAL;
        return out;
    }

    /** One library and its items; true when every one of its items was read and given an object. */
    private static boolean library(Out out, LibraryData.Library lib, String name, Failures failures) {
        OwnObject o = new OwnObject(OwnObjects.LIBRARY, name).id("Library ID", lib.id);
        Object sub = lib.detail.get("subscription_info");
        Object state = lib.detail.get("state_info");
        o.prop(LIB + "Type", PyText.json(lib.detail.get("type")));
        o.prop(LIB + "Subscription URL", PyText.json(get(sub, "subscription_url")));
        o.prop(LIB + "Automatic Sync", PyText.json(get(sub, "automatic_sync_enabled")));
        o.prop(LIB + "State", PyText.json(get(state, "state")));

        // the items that were read and can be given an object
        List<OwnObject> items = new ArrayList<>();
        LibraryLinks links = new LibraryLinks(lib.id, name);
        int nameless = 0;
        for (Map.Entry<String, Map<String, Object>> e : lib.items.entrySet()) {
            String itemName = PyText.json(e.getValue().get("name"));
            if (itemName == null || itemName.trim().isEmpty()) {
                nameless++;
                failures.add(COLLECTOR, FailureCause.REST_ITEM, "item " + e.getKey(), 11,
                        "the item has no name, so it cannot be given an object");
                continue;
            }
            items.add(item(e.getKey(), itemName, e.getValue()));
            links.add(e.getKey(), itemName);
        }
        boolean whole = lib.complete() && nameless == 0;
        if (whole) {
            aggregates(o, lib);
        } else {
            o.withhold(4); // Items, Items Not Stored Locally, Items Not Security Compliant, Total Size
        }
        out.own.add(o);
        for (OwnObject i : items) out.own.add(i);
        // only a library whose items were all read has its full child set; else the links stay as they were
        if (whole) out.own.addLinks(links);
        return whole;
    }

    private static Object get(Object map, String key) {
        return map instanceof Map ? ((Map<?, ?>) map).get(key) : null;
    }

    /** The four counts of a library whose items were all read (R249 to R252). */
    private static void aggregates(OwnObject o, LibraryData.Library lib) {
        // by the list of identifiers, as upstream's len(): an identifier listed twice counts twice (Q11)
        List<Map<String, Object>> listed = new ArrayList<>();
        for (String id : lib.itemIds) listed.add(lib.items.get(id));
        int n = listed.size();
        o.stat(LIB + "Items", (double) n);
        int notStored = 0;
        int notCompliant = 0;
        int knownCached = 0;
        int knownCompliant = 0;
        int sized = 0;
        double bytes = 0;
        for (Map<String, Object> item : listed) {
            // an item that lacks a flag or a size says nothing about it: it is left out of that count or sum (DV-112)
            Object cached = item.get("cached");
            if (cached != null) {
                knownCached++;
                if (!truthy(cached)) notStored++;
            }
            Object compliant = item.get("security_compliance");
            if (compliant != null) {
                knownCompliant++;
                if (!truthy(compliant)) notCompliant++;
            }
            Object size = item.get("size");
            if (size instanceof Double) {
                sized++;
                bytes += (Double) size;
            }
        }
        // a library with no item has a real 0 of each; a library whose items say nothing of a field has no figure for it
        // Not Stored Locally and Total Size are sent only when every item carries the field (a partial figure would
        // read as the whole); a library with no item has a real 0. Not Security Compliant counts the items that have
        // a compliance verdict: an item with no verdict cannot be non-compliant, so it is "of the items with a verdict"
        if (n == 0 || knownCached == n) o.stat(LIB + "Items Not Stored Locally", (double) notStored);
        if (n == 0 || knownCompliant > 0) o.stat(LIB + "Items Not Security Compliant", (double) notCompliant);
        if (n == 0 || sized == n) o.stat(LIB + "Total Size (GB)", PyRound.gib(bytes));
    }

    /** Python truthiness of a JSON value. */
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Double) return (Double) v != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Map) return !((Map<?, ?>) v).isEmpty();
        if (v instanceof List) return !((List<?>) v).isEmpty();
        return true;
    }

    private static OwnObject item(String id, String name, Map<String, Object> m) {
        OwnObject o = new OwnObject(OwnObjects.ITEM, name).id("Item ID", id);
        o.prop(ITEM + "Type", text(m, "type"));
        o.prop(ITEM + "Description", text(m, "description"));
        o.prop(ITEM + "Version", text(m, "version"));
        o.prop(ITEM + "Content Version", text(m, "content_version"));
        o.prop(ITEM + "Stored Locally", text(m, "cached"));
        o.prop(ITEM + "Security Compliant", text(m, "security_compliance"));
        o.prop(ITEM + "Certificate Verification", PyText.json(get(m.get("certificate_verification_info"), "status")));
        o.prop(ITEM + "Last Modified", text(m, "last_modified_time"));
        o.prop(ITEM + "Last Sync", text(m, "last_sync_time"));
        o.prop(ITEM + "Source Item ID", text(m, "source_id"));
        Object size = m.get("size");
        // a size the item lacks sends nothing
        if (size instanceof Double) o.stat(ITEM + "Size (GB)", PyRound.gib((Double) size));
        return o;
    }

    private static String text(Map<String, Object> m, String key) {
        return PyText.json(m.get(key));
    }
}
