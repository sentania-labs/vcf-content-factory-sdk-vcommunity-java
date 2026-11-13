package com.vcfcf.adapters.vcommunity.text;

/**
 * The places where this port reproduces what upstream 0.8.1 publishes although it is not
 * what a careful reader would want (owner direction 2026-11-13: match first, then iterate).
 * Each quirk has one constant and, where it needs logic, one method here; flipping the
 * constant changes the behaviour everywhere. The list, with the key rows, what upstream does
 * and what would be correct, is the design's "Upstream quirks reproduced for the match".
 *
 * <p>Q1 and Q2 are read where they apply ({@code VmCollector.QUIRK_*} points here); Q6, the
 * detailed-data cut, lives in {@link DetailedDataParser#CUT_AT_FIRST_QUOTE}.
 *
 * <p>{@link #ABSENT_OWN_TEXT_IS_EMPTY} (Q8) is the one quirk that stands against an owner answer
 * (DV-17, "report honestly"): it is on for the match, and one edit turns it off.
 */
public final class Quirks {

    private Quirks() {}

    /** Q1, R105 and R106: upstream always publishes {@code None} for Tools Sync Time and Tools Upgrade Policy. */
    public static final boolean TOOLS_ALWAYS_NONE = true;

    /** Q2, R124-R130: upstream publishes no Virtual Disk key on a VM that has no network adapter. */
    public static final boolean DISKS_NEED_A_VNIC = true;

    /** Q3, R33-R35: upstream formats the signed 16-bit PCI id, so 0x8086 is {@code -7f7a}. */
    public static final boolean PCI_IDS_SIGNED = true;

    /** Q4: a string that is present and blank is pushed as it is; correct would be to send nothing. */
    public static final boolean BLANK_STRINGS_PUSHED = true;

    /** Q5, R96: a negative VM operation notification timeout (-1, none) is published as 0. */
    public static final boolean NEGATIVE_OP_TIMEOUT_IS_ZERO = true;

    /**
     * Q8, R227-R232, R234-R243 and R245-R248, the text keys of the three own kinds: upstream sends
     * {@code ""} when a library, an item or a template does not have the field (a local library has
     * no subscription URL, none of its items has a last sync time or a source item; a template
     * with no parent folder has an empty folder name), where a returned empty string is the same
     * text. {@code true}: the empty text, as upstream (the live Python baseline shows it on every
     * object: 2 of the 4 library properties and 2 of the 10 item properties). {@code false}:
     * nothing is sent for a field that was not returned (DV-17, the owner's "a missing read sends
     * nothing" answer). Only the text keys: a figure that was not returned is never sent as the
     * zero upstream sends (vCPUs, Memory, sizes, counts: DV-11, DV-18), and a value that a failed
     * read left unknown is never sent as anything.
     */
    public static final boolean ABSENT_OWN_TEXT_IS_EMPTY = true;

    /** The text of an own-kind property: the value as returned, or per {@link #ABSENT_OWN_TEXT_IS_EMPTY} when it was not. */
    public static String ownText(String raw) {
        return ownText(raw, ABSENT_OWN_TEXT_IS_EMPTY);
    }

    /** {@link #ownText(String)} with the switch given, so a test can show both settings. */
    public static String ownText(String raw, boolean absentIsEmpty) {
        if (raw == null) return absentIsEmpty ? "" : null;
        return text(raw);
    }

    /** Q4: {@code str(x)} of a string that was returned; null only for "not returned". */
    public static String text(String raw) {
        if (raw == null) return null;
        if (BLANK_STRINGS_PUSHED) return raw;
        return PyText.strip(raw).isEmpty() ? null : raw;
    }

    /**
     * Q4: {@code str(x).strip() if x else "null"}: an empty value is no value, a value of spaces is
     * pushed as the empty string.
     */
    public static String strippedIfTruthy(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = PyText.strip(raw);
        return BLANK_STRINGS_PUSHED || !s.isEmpty() ? s : null;
    }

    /** Q3: {@code f"{v:04x}"} of a signed short; null when the text is not an integer. */
    public static String pciId(String shortText) {
        String t = PyText.integer(shortText);
        if (t == null) return null;
        long v;
        try {
            v = Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!PCI_IDS_SIGNED) return String.format("%04x", v & 0xFFFF);
        if (v >= 0) return String.format("%04x", v);
        StringBuilder h = new StringBuilder(Long.toHexString(-v));
        while (h.length() < 3) h.insert(0, '0'); // Python pads the whole field, sign included, to 4
        return "-" + h;
    }
}
