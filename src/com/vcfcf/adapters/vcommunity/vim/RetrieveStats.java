package com.vcfcf.adapters.vcommunity.vim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one paged read cost, for the log and the heap tests. */
public final class RetrieveStats {
    /** The continuation token the server still holds for this read, or null; released when the read ends. */
    String openToken;
    public int pages;
    public int rows;
    public long bytes;
    /** The most elements held for one object, and in all. */
    public int maxRowNodes;
    public long totalNodes;
    /** Calls made, the container view and its release included. */
    public int calls;
    private final List<String> droppedPaths = new ArrayList<>();
    /** Paths that were asked for after the read had already dropped them (remembered from earlier cycles). */
    public int pathsKnownInvalid;

    void dropped(String path) {
        droppedPaths.add(path);
    }

    /** Paths vCenter refused as invalid properties and the read left out. */
    public List<String> droppedPaths() {
        return Collections.unmodifiableList(droppedPaths);
    }

    @Override
    public String toString() {
        return "pages=" + pages + " rows=" + rows + " bytes=" + bytes + " calls=" + calls
                + " maxRowNodes=" + maxRowNodes + " droppedPaths=" + droppedPaths.size();
    }
}
