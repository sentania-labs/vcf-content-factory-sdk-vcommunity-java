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
