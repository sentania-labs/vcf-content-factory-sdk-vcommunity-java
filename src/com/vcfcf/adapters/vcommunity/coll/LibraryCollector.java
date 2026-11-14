package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.FailureCause;
import com.vcfcf.adapters.vcommunity.health.Failures;
import com.vcfcf.adapters.vcommunity.rest.LibraryData;
import com.vcfcf.adapters.vcommunity.text.PyRound;
import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.text.Quirks;

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
 * the items that happened to answer. A text the answer does not have is the empty text, as the
 * original sends it (Q8, {@link Quirks#ABSENT_OWN_TEXT_IS_EMPTY}); a figure it does not have is
 * not sent as the zero the original sends (DV-11), nor is a count that has an input missing
 * (DV-18). A returned empty text is sent as it is (Q4), and a library with no items has zero of
 * everything, which is what was read.
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
        return collect(d, failures, Quirks.ABSENT_OWN_TEXT_IS_EMPTY);
    }

    /** {@link #collect(LibraryData, Failures)} with the absent-text switch given, so a test can show both settings. */
    public static Out collect(LibraryData d, Failures failures, boolean absentTextIsEmpty) {
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
            boolean whole = library(out, lib, name, failures, absentTextIsEmpty);
            if (!whole) itemsRead = false;
        }
        if (librariesRead) out.own.read(OwnObjects.LIBRARY);
        if (itemsRead) out.own.read(OwnObjects.ITEM);
        out.status = failures.forCollector(COLLECTOR) == 0 && !d.deadlineHit ? Status.OK : Status.PARTIAL;
        return out;
    }

    /** One library and its items; true when every one of its items was read and given an object. */
    private static boolean library(Out out, LibraryData.Library lib, String name, Failures failures,
            boolean empty) {
        OwnObject o = new OwnObject(OwnObjects.LIBRARY, name).id("Library ID", lib.id);
        Object sub = lib.detail.get("subscription_info");
        Object state = lib.detail.get("state_info");
        o.propY(LIB + "Type", Quirks.ownText(PyText.json(lib.detail.get("type")), empty));
        o.propY(LIB + "Subscription URL", Quirks.ownText(PyText.json(get(sub, "subscription_url")), empty));
        o.propY(LIB + "Automatic Sync", Quirks.ownText(PyText.json(get(sub, "automatic_sync_enabled")), empty));
        o.propY(LIB + "State", Quirks.ownText(PyText.json(get(state, "state")), empty));

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
            items.add(item(e.getKey(), itemName, e.getValue(), empty));
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
        int n = lib.items.size();
        o.stat(LIB + "Items", (double) n);
        boolean allCached = true;
        boolean allCompliant = true;
        boolean allSized = true;
        int notStored = 0;
        int notCompliant = 0;
        double bytes = 0;
        for (Map<String, Object> item : lib.items.values()) {
            Object cached = item.get("cached");
            if (cached == null && !Quirks.UNSET_AS_UPSTREAM) allCached = false;
            else if (!truthy(cached)) notStored++;
            Object compliant = item.get("security_compliance");
            if (compliant == null && !Quirks.UNSET_AS_UPSTREAM) allCompliant = false;
            else if (!truthy(compliant)) notCompliant++;
            Object size = item.get("size");
            if (size instanceof Double) bytes += (Double) size;
            else if (!Quirks.UNSET_AS_UPSTREAM) allSized = false;
        }
        // Q9: an item that was read and lacks the field counts as the original counts it: "not stored" and
        // "not compliant" (a falsy field), and a missing size as 0. With the switch off (DV-18) a count with
        // such an item in it is not sent. An item that could not be read is not here at all, and the whole
        // block is withheld (the caller)
        o.statY(LIB + "Items Not Stored Locally", allCached ? (Double) (double) notStored : null);
        o.statY(LIB + "Items Not Security Compliant", allCompliant ? (Double) (double) notCompliant : null);
        o.statY(LIB + "Total Size (GB)", allSized ? (Double) PyRound.gib(bytes) : null);
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

    private static OwnObject item(String id, String name, Map<String, Object> m, boolean empty) {
        OwnObject o = new OwnObject(OwnObjects.ITEM, name).id("Item ID", id);
        o.propY(ITEM + "Type", text(m, "type", empty));
        o.propY(ITEM + "Description", text(m, "description", empty));
        o.propY(ITEM + "Version", text(m, "version", empty));
        o.propY(ITEM + "Content Version", text(m, "content_version", empty));
        o.propY(ITEM + "Stored Locally", text(m, "cached", empty));
        o.propY(ITEM + "Security Compliant", text(m, "security_compliance", empty));
        o.propY(ITEM + "Certificate Verification",
                Quirks.ownText(PyText.json(get(m.get("certificate_verification_info"), "status")), empty));
        o.propY(ITEM + "Last Modified", text(m, "last_modified_time", empty));
        o.propY(ITEM + "Last Sync", text(m, "last_sync_time", empty));
        o.propY(ITEM + "Source Item ID", text(m, "source_id", empty));
        Object size = m.get("size");
        // a size the item lacks is the original's 0 (Q9), else nothing
        o.statY(ITEM + "Size (GB)", size instanceof Double ? (Double) PyRound.gib((Double) size)
                : Quirks.UNSET_AS_UPSTREAM ? Double.valueOf(0) : null);
        return o;
    }

    private static String text(Map<String, Object> m, String key, boolean empty) {
        return Quirks.ownText(PyText.json(m.get(key)), empty);
    }
}
