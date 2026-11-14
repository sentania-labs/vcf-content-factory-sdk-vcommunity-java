package com.vcfcf.adapters.vcommunity.coll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one cycle read of the pak's own kinds: the objects to deliver, the links between them,
 * and which kinds were read <em>in full</em>. The last is what lets the platform mark an object
 * that vCenter no longer has (design 3.4 and the vanished-object rule): a kind is named only
 * when every read that could have contained one of its objects succeeded, so a failed or
 * partial read never makes a live object look gone.
 */
public final class OwnObjects {

    public static final String LIBRARY = "Content Library";
    public static final String ITEM = "Library Item";
    public static final String TEMPLATE = "VM Template";

    private final List<OwnObject> objects = new ArrayList<>();
    private final List<LibraryLinks> links = new ArrayList<>();
    private final Set<String> kindsRead = new LinkedHashSet<>();

    /** A cycle that read nothing: no object, no link, no kind read in full. */
    public static OwnObjects none() {
        return new OwnObjects();
    }

    public OwnObjects add(OwnObject o) {
        objects.add(o);
        return this;
    }

    public OwnObjects addLinks(LibraryLinks l) {
        links.add(l);
        return this;
    }

    /** Take over everything another result holds. */
    public OwnObjects merge(OwnObjects other) {
        objects.addAll(other.objects);
        links.addAll(other.links);
        kindsRead.addAll(other.kindsRead);
        return this;
    }

    /** The kind is not proven read in full after all (the guard of an abrupt drop to none). */
    public OwnObjects unread(String kind) {
        kindsRead.remove(kind);
        return this;
    }

    /** How many objects of the kind this cycle read. */
    public int count(String kind) {
        int n = 0;
        for (OwnObject o : objects) if (o.kind.equals(kind)) n++;
        return n;
    }

    /** The kind was read in full this cycle. */
    public OwnObjects read(String kind) {
        kindsRead.add(kind);
        return this;
    }

    public List<OwnObject> objects() {
        return Collections.unmodifiableList(objects);
    }

    public List<LibraryLinks> links() {
        return Collections.unmodifiableList(links);
    }

    /**
     * The libraries' child sets that fit in {@code maxEdges} edges together, in order. A set that
     * would not fit is left out whole, never cut: a library sent with a short child set would
     * lose the links of the items left off, where one not sent keeps the links it has.
     *
     * @param skipped receives the number of sets left out
     */
    public List<LibraryLinks> linksWithin(int maxEdges, int[] skipped) {
        List<LibraryLinks> out = new ArrayList<>();
        long used = 0;
        int left = 0;
        for (LibraryLinks l : links) {
            if (used + l.children().size() > maxEdges) {
                left++;
                continue;
            }
            used += l.children().size();
            out.add(l);
        }
        if (skipped != null && skipped.length > 0) skipped[0] = left;
        return out;
    }

    public Set<String> kindsRead() {
        return Collections.unmodifiableSet(kindsRead);
    }
}
