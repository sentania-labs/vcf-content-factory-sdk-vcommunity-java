package com.vcfcf.adapters.vcommunity.coll;

import com.vcfcf.adapters.vcommunity.text.PyText;
import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.Collections;
import java.util.List;

/**
 * Reads a returned element tree reflection-tolerantly: a missing element,
 * a value that does not parse as its type and an unexpected shape are all
 * {@code null}, which a collector reads as "absent" and withholds. Nothing here
 * throws and nothing supplies a default (design rule 2.2).
 */
final class Vals {

    private Vals() {}

    /** {@code str(value)} of a requested path whose value is a single typed scalar. */
    static String scalar(BulkRow row, String path) {
        return PyText.scalar(row.val(path));
    }

    /**
     * The scalar at a requested path, as a property. A value that is not there is not sent, with no placeholder: when
     * the read worked and the path is unset that is simply an absent value (not counted); when the path could not
     * be read, or holds more than one value, it is a value withheld and counted.
     */
    static void prop(ObjectOut o, BulkRow row, String key, String path) {
        String v = scalar(row, path);
        if (v != null) {
            o.prop(key, v);
        } else if (!row.isUnset(path)) {
            o.withhold(1);
        }
    }

    /** The element at a dotted path below {@code root} (first child of each name), or null. */
    static XmlNode at(XmlNode root, String dotted) {
        XmlNode n = root;
        if (n == null) return null;
        for (String part : dotted.split("\\.")) {
            n = n.child(part);
            if (n == null) return null;
        }
        return n;
    }

    /** The children of an array value, empty for a null or empty array. */
    static List<XmlNode> items(XmlNode array) {
        return array == null ? Collections.<XmlNode>emptyList() : array.children();
    }

    /** True for an array-typed value (an empty one included). */
    static boolean isArray(XmlNode val) {
        String t = val == null ? null : val.xsiType();
        return t != null && t.startsWith("ArrayOf");
    }

    /** A nested string field exactly as sent, or null when the element is absent. */
    static String str(XmlNode root, String dotted) {
        XmlNode n = at(root, dotted);
        return n == null ? null : n.rawText();
    }

    /** A nested boolean field as {@code True}/{@code False}, or null. */
    static String bool(XmlNode root, String dotted) {
        XmlNode n = at(root, dotted);
        return n == null ? null : PyText.bool(n.text());
    }

    /** A nested integer field as decimal text, or null. */
    static String integer(XmlNode root, String dotted) {
        XmlNode n = at(root, dotted);
        return n == null ? null : PyText.integer(n.text());
    }

    /** {@code str().strip()} that treats a blank result as no value. */
    static String stripped(String s) {
        if (s == null) return null;
        String t = PyText.strip(s);
        return t.isEmpty() ? null : t;
    }

    /** A non-blank value or null. */
    static String nonBlank(String s) {
        return s == null || PyText.strip(s).isEmpty() ? null : s;
    }

    static final String P = "vCommunity|";
}
