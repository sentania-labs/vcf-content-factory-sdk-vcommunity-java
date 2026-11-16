package com.vcfcf.adapters.vcommunity.coll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The full child set of one content library (design 3.4): every item it holds, by identifier
 * and name. Only a library whose items were all read has one; a library that was read in part
 * has none, so the platform keeps the links it has rather than replace them with a short set.
 */
public final class LibraryLinks {

    /** One item as a child. */
    public static final class Child {
        public final String id;
        public final String name;

        Child(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public final String libraryId;
    public final String libraryName;
    private final List<Child> children = new ArrayList<>();

    LibraryLinks(String libraryId, String libraryName) {
        this.libraryId = libraryId;
        this.libraryName = libraryName;
    }

    void add(String id, String name) {
        children.add(new Child(id, name));
    }

    public List<Child> children() {
        return Collections.unmodifiableList(children);
    }
}
