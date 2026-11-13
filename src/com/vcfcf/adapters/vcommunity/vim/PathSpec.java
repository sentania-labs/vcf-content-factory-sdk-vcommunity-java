package com.vcfcf.adapters.vcommunity.vim;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What to keep of one requested property while its answer is read. A
 * property such as a cluster's {@code configurationEx} or a host's
 * {@code config.option} is far larger than the few fields a collector uses;
 * the unused part is skipped as it streams past, so no tree of it is ever
 * built (design 4.1, heap rule 9.3).
 */
public final class PathSpec implements XmlNode.Prune {

    private static final PathSpec ALL = new PathSpec(null, null);

    private final Set<String> keep;
    private final Predicate<XmlNode> element;
    /** True when the value is an array: the first segment of a path is the element's own name. */
    private final boolean array;

    private PathSpec(Set<String> keep, Predicate<XmlNode> element) {
        this(keep, element, false);
    }

    private PathSpec(Set<String> keep, Predicate<XmlNode> element, boolean array) {
        this.keep = keep;
        this.element = element;
        this.array = array;
    }

    /**
     * Keep the value element and its text only: descendants are skipped as they stream past (the
     * element is then marked {@link XmlNode#hasSkipped}). For a path that is wanted only when it is a
     * single value, so one that turns out to be an object or a list costs no tree and no depth.
     */
    public static PathSpec shallow() {
        return new PathSpec(Collections.<String>emptySet(), null);
    }

    /** Keep the whole value. */
    public static PathSpec all() {
        return ALL;
    }

    /**
     * Keep only the descendants on these dotted paths, relative to the value
     * (an array repeats one name, so a path carries no index).
     */
    public static PathSpec keeping(String... relativePaths) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, relativePaths);
        return new PathSpec(s, null);
    }

    /**
     * For an array value: keep every element, and of each only these dotted paths
     * (relative to the element, whatever type name it carries).
     */
    public static PathSpec keepingInElements(String... relativePaths) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, relativePaths);
        return new PathSpec(s, null, true);
    }

    /** Keep only the array elements the test accepts, as complete elements. */
    public static PathSpec elements(Predicate<XmlNode> test) {
        return new PathSpec(null, test);
    }

    /** Both: only these paths of only the elements the test accepts. */
    public PathSpec andElements(Predicate<XmlNode> test) {
        return new PathSpec(keep, test, array);
    }

    @Override
    public boolean keepPath(String path) {
        if (keep == null) return true;
        String p = path;
        if (array) {
            int dot = path.indexOf('.');
            if (dot < 0) return true; // the element itself
            p = path.substring(dot + 1);
        }
        for (String k : keep) {
            if (k.equals(p) || k.startsWith(p + ".") || p.startsWith(k + ".")) return true;
        }
        return false;
    }

    @Override
    public boolean keepElement(XmlNode e) {
        return element == null || element.test(e);
    }
}
