package com.vcfcf.adapters.vcommunity.text;

/**
 * The places where this port reproduces what upstream 0.8.1 publishes although it is not
 * what a careful reader would want (owner direction 2026-11-13: match first, then iterate).
 * Each quirk has one constant and, where it needs logic, one method here; flipping the
 * constant changes the behaviour everywhere. The list, with the key rows, what upstream does
 * and what would be correct, is the design's "Upstream quirks reproduced for the match".
 *
 * <p>Q2 is read where it applies; Q6, the detailed-data cut, lives in
 * {@link DetailedDataParser#CUT_AT_FIRST_QUOTE}.
 *
 * <p>A value vCenter answered without, or that does not apply, is never replaced by a placeholder or a made-up
 * number (DV-112). That removed the switches of Q1, Q8, Q9, Q10, Q12, Q13 and Q16 for good; what stays here
 * changes how a real value is read or counted, never what stands in for a missing one.
 */
public final class Quirks {

    private Quirks() {}

    /** Q2, R124-R130: upstream publishes no Virtual Disk key on a VM that has no network adapter. */
    public static final boolean DISKS_NEED_A_VNIC = true;

    /** Q3, R33-R35: upstream formats the signed 16-bit PCI id, so 0x8086 is {@code -7f7a}. */
    public static final boolean PCI_IDS_SIGNED = true;

    /** Q4: a string that is present and blank is pushed as it is; correct would be to send nothing. */
    public static final boolean BLANK_STRINGS_PUSHED = true;

    /** Q5, R96: a negative VM operation notification timeout (-1, none) is published as 0. */
    public static final boolean NEGATIVE_OP_TIMEOUT_IS_ZERO = true;

    /**
     * Q11, R249 Items: upstream counts the items by the length of the list of identifiers the REST API
     * answers, so an identifier listed twice is counted twice (and its size, and its not-stored and
     * not-compliant flags, with it), though it is one item and one object. {@code false}: each item once.
     */
    public static final boolean REPEATED_ITEM_COUNTED_TWICE = true;

    /**
     * Q14, R215-R217 Business Applications: the Business Application value is the HIGHER of its namespaces'
     * performance values and the lowest of its tiers' values (upstream issue 6), so a healthy namespace hides
     * an unhealthy tier. {@code true}: as upstream. {@code false}: the lowest of all the parts.
     */
    public static final boolean APPLICATION_TAKES_HIGHER = true;

    /**
     * Q15, R195, R196, R197, R212, R221: upstream publishes no availability for any host, cluster or vCenter
     * when no host answered either read, and no Network Speed Degradation for any host or Datacenter when no
     * host has any NIC speed ("No NIC speeds for any host"), instead of 0 %. {@code true}: nothing, as
     * upstream, when the reads worked and answered nothing for any host. {@code false}: availability is
     * computed with every host at 0 %.
     */
    public static final boolean NOTHING_WHEN_NO_HOST_HAS_DATA = true;

    /** Q4: {@code str(x)} of a string that was returned; null only for "not returned". */
    public static String text(String raw) {
        if (raw == null) return null;
        if (BLANK_STRINGS_PUSHED) return raw;
        return PyText.strip(raw).isEmpty() ? null : raw;
    }

    /**
     * {@code str(x).strip()} of a string that was returned: null only for "not returned". An empty or blank
     * string that vCenter did return is a value and comes back as the empty string.
     */
    public static String stripped(String raw) {
        return raw == null ? null : PyText.strip(raw);
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
