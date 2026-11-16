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
     * nothing" answer). Only the text keys: a figure that was not returned follows
     * {@link #UNSET_AS_UPSTREAM} (the zero upstream sends), and a value that a failed read left
     * unknown is never sent as anything, with either setting.
     */
    public static final boolean ABSENT_OWN_TEXT_IS_EMPTY = true;

    /**
     * Q9 (P2 live differential, 2026-11-13): a value that was <em>read</em> and is <em>unset</em> is
     * published the way upstream prints it, not left out. {@code str(None)} is the text {@code None};
     * the original's own placeholders are {@code null} (an unset EVC mode, a HA key while HA is off, a
     * missing vCenter setting, no install date, no HBA model), {@code []} (no maximum EVC mode),
     * {@code N/A} (a NIC with no PCI device), {@code Unknown} (a disk with no backing or shares), and
     * {@code 0} for a count, a timeout, a size. Which key prints which is decided where the key is built, from the
     * original's code. {@code false}: nothing is sent for an unset value (DV-08 to DV-16, DV-11, DV-18).
     * Three keys no longer follow this switch (DV-111): DRS Score (nothing while DRS is off or the score is
     * negative), and the HBA and SCSI Controllers counts (nothing when the list was not returned), because their
     * 0 stood for "does not apply" or "unknown". A value that vCenter did not return because the
     * <em>read failed</em> is never one of these: it is withheld and counted, with either setting.
     */
    public static final boolean UNSET_AS_UPSTREAM = true;

    /**
     * Q10, R39 Duplex: upstream publishes {@code half-duplex} for a NIC whose link is down (no link speed),
     * and for one whose speed has no duplex flag; only {@code full-duplex} says the flag is true.
     * {@code false}: nothing for a link that is down (DV-15).
     */
    public static final boolean DUPLEX_HALF_WHEN_DOWN = true;

    /**
     * Q11, R249 Items: upstream counts the items by the length of the list of identifiers the REST API
     * answers, so an identifier listed twice is counted twice (and its size, and its not-stored and
     * not-compliant flags, with it), though it is one item and one object. {@code false}: each item once.
     */
    public static final boolean REPEATED_ITEM_COUNTED_TWICE = true;

    /**
     * Q12, R197 Total Network Speed Degradation on a Datacenter: upstream counts the member hosts below 100 %
     * and a host with no comparable NIC counts as none, so a Datacenter whose hosts all lack a value is
     * published as {@code 0} (provided some host somewhere has NIC data). {@code true}: that 0, as upstream,
     * when every read worked. {@code false}: nothing for a Datacenter with no member host that has a value
     * (DV-22). A Datacenter with a member host whose read failed is never published, with either setting.
     */
    public static final boolean EMPTY_DATACENTER_COUNT_IS_ZERO = true;

    /**
     * Q13, every KPI input: upstream uses a latest statistic as it reads it, so the value {@code -1}
     * (the platform's "no sample" marker for a counter) is scored as a reading, and scores a lower-is-better
     * row as perfect. {@code true}: used as read. {@code false}: a latest statistic of exactly -1 is no
     * sample, except the two usable-capacity statistics, which can legitimately be negative.
     */
    public static final boolean NEGATIVE_ONE_STAT_IS_A_VALUE = true;

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

    /**
     * Q16, R211 Total Network Speed Degradation on the vSphere World: upstream publishes the count (0) whenever the
     * answer names any host, even when the only stored values are older than ten minutes (a host's stored speed
     * stays until the platform is told otherwise), so after the host values stop it goes on publishing 0.
     * {@code true}: as upstream. {@code false}: a count only while some host has a fresh stored value.
     */
    public static final boolean STALE_HOST_VALUE_COUNTS_AS_ZERO = true;

    /** The original's {@code str(None)}. */
    public static final String NONE = "None";
    /** The original's {@code NULL_STATUS}. */
    public static final String NULL = "null";

    /** Q9: {@code str(value)} of a property that may be unset on a read that worked. */
    public static String orNone(String v) {
        return orText(v, NONE, UNSET_AS_UPSTREAM);
    }

    /** Q9: the original's {@code "null"} placeholder. */
    public static String orNull(String v) {
        return orText(v, NULL, UNSET_AS_UPSTREAM);
    }

    /** Q9: a placeholder of the original's for an unset value, when the switch is on; else nothing. */
    public static String orText(String v, String placeholder, boolean on) {
        return v != null ? v : on ? placeholder : null;
    }

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
