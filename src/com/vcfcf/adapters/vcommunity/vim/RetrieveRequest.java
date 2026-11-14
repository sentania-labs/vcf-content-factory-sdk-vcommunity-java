package com.vcfcf.adapters.vcommunity.vim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A property collector read of one managed object type: the paths, how much
 * of each to keep, the page size, and either every object of the type below
 * the root folder (a container view) or an explicit list of objects.
 */
public final class RetrieveRequest {
    public final String label;
    public final String type;
    public final int maxObjects;
    /** Null: every object of the type, through a recursive container view. */
    public final List<MoRef> objects;
    private final Map<String, PathSpec> paths = new LinkedHashMap<>();
    private boolean firstPageOnly;
    private boolean withoutName;

    /** @param label the read's id for messages ({@code R-HO}); never sent */
    public RetrieveRequest(String label, String type, int maxObjects, List<MoRef> objects) {
        this.label = label;
        this.type = type;
        this.maxObjects = maxObjects;
        this.objects = objects == null ? null : Collections.unmodifiableList(new ArrayList<>(objects));
    }

    /**
     * Do not ask for {@code name}: the object is not a managed entity (the license manager has no such
     * property, and vCenter refuses the whole read with an InvalidProperty fault).
     */
    public RetrieveRequest withoutName() {
        this.withoutName = true;
        return this;
    }

    public boolean asksName() {
        return !withoutName;
    }

    /** Ask for a path and keep all of its value. */
    public RetrieveRequest path(String path) {
        paths.put(path, PathSpec.all());
        return this;
    }

    /** Ask for a path and keep only what the spec accepts. */
    public RetrieveRequest path(String path, PathSpec spec) {
        paths.put(path, spec);
        return this;
    }

    /** Read the first page only: a probe of whether the paths are valid, not an inventory. */
    public RetrieveRequest firstPageOnly() {
        this.firstPageOnly = true;
        return this;
    }

    public boolean isFirstPageOnly() {
        return firstPageOnly;
    }

    /** The requested paths with their specs, in request order; {@code name} is always added by the reader. */
    public Map<String, PathSpec> paths() {
        return Collections.unmodifiableMap(paths);
    }
}
