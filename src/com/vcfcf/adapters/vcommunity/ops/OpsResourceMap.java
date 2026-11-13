package com.vcfcf.adapters.vcommunity.ops;

import com.vcfcf.adapters.vcommunity.json.Json;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which VCF Operations resource carries which vCenter object. Identity is the
 * vCenter's instance UUID plus the MoRef (the MOID trap: a MoRef is only
 * unique within one vCenter), never a name (design 3.1). Two loads, both from
 * the paged {@code GET /api/resources?adapterKind=VMWARE&resourceKind=...}:
 *
 * <ul>
 *   <li>{@link #load}: every object of one kind that carries this vCenter's
 *       UUID, by MoRef. A row without an id, a MoRef or a UUID, or of another
 *       vCenter, is not indexed. A MoRef carried by two different resources of
 *       this vCenter matches neither.</li>
 *   <li>{@link #findVcenterObject}: the VMwareAdapter Instance of this vCenter,
 *       the one row of that kind whose UUID matches. Exactly one is a match; none
 *       or several is no match, and both are reported, never guessed.</li>
 * </ul>
 * A failed or short page throws {@link IOException}: the caller keeps the
 * previous map rather than read a partial list as complete.
 */
public final class OpsResourceMap {

    public static final int PAGE_SIZE = 5000;
    public static final int VM_PAGE_SIZE = 500;
    public static final int MAX_PAGES = 80;
    public static final String ID_MOREF = "VMEntityObjectID";
    public static final String ID_VCID = "VMEntityVCID";
    public static final String VCENTER_KIND = "VMwareAdapter Instance";

    private final String kind;
    private final Map<String, String> byMoref;
    private final Set<String> ambiguous;
    private final int rows;
    private final int otherVcenter;
    private final int notIndexed;
    private final int pages;

    private OpsResourceMap(String kind, Map<String, String> byMoref, Set<String> ambiguous,
            int rows, int otherVcenter, int notIndexed, int pages) {
        this.kind = kind;
        this.byMoref = Collections.unmodifiableMap(byMoref);
        this.ambiguous = Collections.unmodifiableSet(ambiguous);
        this.rows = rows;
        this.otherVcenter = otherVcenter;
        this.notIndexed = notIndexed;
        this.pages = pages;
    }

    /** The resource id for a MoRef of this vCenter, or null. */
    public String resourceId(String moref) {
        return moref == null ? null : byMoref.get(moref);
    }

    public String kind() {
        return kind;
    }

    public int size() {
        return byMoref.size();
    }

    /** MoRefs carried by two resources of this vCenter, matched to neither. */
    public Set<String> ambiguous() {
        return ambiguous;
    }

    public int rows() {
        return rows;
    }

    public int otherVcenter() {
        return otherVcenter;
    }

    public int notIndexed() {
        return notIndexed;
    }

    public int pages() {
        return pages;
    }

    @Override
    public String toString() {
        return kind + ": " + byMoref.size() + " matched of " + rows + " rows, " + otherVcenter
                + " of other vCenters, " + notIndexed + " without identity, " + ambiguous.size()
                + " ambiguous, " + pages + " page(s)";
    }

    /** The request path of one page of {@code kind}. */
    public static String path(String kind, int page) {
        return "/api/resources?adapterKind=VMWARE&resourceKind=" + encode(kind) + "&pageSize="
                + ("VirtualMachine".equals(kind) ? VM_PAGE_SIZE : PAGE_SIZE) + "&page=" + page;
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One resource row: id and the identifier values by name. */
    private static final class Row {
        final String id;
        final Map<String, String> identifiers = new HashMap<>();

        Row(Object r) {
            this.id = Json.str(Json.get(r, "identifier"));
            for (Object ri : Json.list(Json.get(Json.get(r, "resourceKey"), "resourceIdentifiers"))) {
                String k = Json.str(Json.get(Json.get(ri, "identifierType"), "name"));
                String v = Json.str(Json.get(ri, "value"));
                if (k != null && v != null) identifiers.put(k, v);
            }
        }
    }

    /** Pages of one kind, each row handed to the visitor. Returns the page count. */
    private static int read(OpsApi ops, String kind, java.util.function.Consumer<Row> visit)
            throws IOException, InterruptedException {
        int rows = 0;
        int pagesRead = 0;
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES) {
                throw new IOException(kind + " list is longer than " + MAX_PAGES + " pages");
            }
            pagesRead++;
            Object root;
            try {
                root = Json.parse(getPage(ops, kind, page));
            } catch (IllegalArgumentException e) {
                throw new IOException(kind + " list page " + page + " is not valid JSON");
            }
            Object list = Json.get(root, "resourceList");
            Double total = Json.num(Json.get(Json.get(root, "pageInfo"), "totalCount"));
            if (!(list instanceof List)) {
                if (total != null && total == 0) break;
                throw new IOException(kind + " list page " + page + " has no resourceList");
            }
            int n = 0;
            for (Object r : (List<?>) list) {
                n++;
                rows++;
                visit.accept(new Row(r));
            }
            if (total == null) break; // no pageInfo: one page is the whole list
            if (rows >= total) break;
            if (n == 0) {
                throw new IOException(kind + " list ended after " + rows + " of "
                        + total.longValue() + " rows");
            }
        }
        return pagesRead;
    }

    private static String getPage(OpsApi ops, String kind, int page)
            throws IOException, InterruptedException {
        try {
            return ops.get(path(kind, page), null);
        } catch (OpsHttpException e) {
            throw e;
        } catch (IOException e) {
            // an idle keep-alive connection can be dropped: one more try
            return ops.get(path(kind, page), null);
        }
    }

    /**
     * Index one kind of this vCenter by MoRef.
     *
     * @throws IOException a failed or incomplete read; the vCenter UUID is unknown
     */
    public static OpsResourceMap load(OpsApi ops, String vcid, String kind)
            throws IOException, InterruptedException {
        if (vcid == null || vcid.isEmpty()) {
            throw new IOException("the vCenter instance UUID is unknown; objects are matched on "
                    + "MoRef plus vCenter UUID only");
        }
        Map<String, String> byMoref = new HashMap<>();
        Set<String> ambiguous = new TreeSet<>();
        int[] c = new int[3]; // rows, other, notIndexed
        int pages = read(ops, kind, row -> {
            c[0]++;
            String vc = row.identifiers.get(ID_VCID);
            String moref = row.identifiers.get(ID_MOREF);
            if (vc != null && !vc.isEmpty() && !vcid.equals(vc)) {
                c[1]++;
                return;
            }
            if (vc == null || vc.isEmpty() || row.id == null || row.id.isEmpty() || moref == null
                    || moref.isEmpty()) {
                c[2]++;
                return;
            }
            if (ambiguous.contains(moref)) return;
            String have = byMoref.get(moref);
            if (have != null) {
                // offset paging over a changing list can return a row twice; only a
                // different resource id for the same MoRef is ambiguous
                if (have.equals(row.id)) return;
                byMoref.remove(moref);
                ambiguous.add(moref);
                return;
            }
            byMoref.put(moref, row.id);
        });
        return new OpsResourceMap(kind, byMoref, ambiguous, c[0], c[1], c[2], pages);
    }

    /** The outcome of looking for this vCenter's own VMwareAdapter Instance object. */
    public static final class VcenterObject {
        private final String resourceId;
        private final int matches;

        VcenterObject(String resourceId, int matches) {
            this.resourceId = resourceId;
            this.matches = matches;
        }

        /** The resource id when exactly one object carries the UUID, else null. */
        public String resourceId() {
            return resourceId;
        }

        /** How many objects of the kind carry the UUID. */
        public int matches() {
            return matches;
        }

        public boolean resolved() {
            return resourceId != null;
        }
    }

    /**
     * The VMwareAdapter Instance whose vCenter UUID equals {@code vcid}.
     *
     * @throws IOException a failed or incomplete read, or an unknown UUID
     */
    public static VcenterObject findVcenterObject(OpsApi ops, String vcid)
            throws IOException, InterruptedException {
        if (vcid == null || vcid.isEmpty()) {
            throw new IOException("the vCenter instance UUID is unknown; the vCenter object "
                    + "cannot be matched");
        }
        Set<String> ids = new TreeSet<>();
        read(ops, VCENTER_KIND, row -> {
            if (vcid.equals(row.identifiers.get(ID_VCID)) && row.id != null && !row.id.isEmpty()) {
                ids.add(row.id);
            }
        });
        return new VcenterObject(ids.size() == 1 ? ids.iterator().next() : null, ids.size());
    }
}
