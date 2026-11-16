package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.ops.OpsEvent;

/**
 * The event a Windows log record becomes (design 8.6, DV-42): the message {@code [WindowsEvent-<Level> <Event>]}
 * of the original, with the text cleaned and cut, and a severity taken from the numeric Windows level when the
 * script gave one (the level's display name is in the guest's language) and from the English display names of
 * the original when it did not.
 */
public final class WindowsEvents {

    private WindowsEvents() {}

    /** One event per row, at most this many per VM per cycle; the rest is counted. */
    public static final int MAX_PER_VM = 100;

    public static OpsEvent.Severity severity(int levelNumber, String levelText) {
        switch (levelNumber) {
            case 1: return OpsEvent.Severity.CRITICAL;
            case 2: return OpsEvent.Severity.IMMEDIATE;
            case 3: return OpsEvent.Severity.WARNING;
            case 0:
            case 4:
            case 5: return OpsEvent.Severity.INFORMATION;
            default: break;
        }
        String t = levelText == null ? "" : levelText.trim();
        switch (t) {
            case "Warning": return OpsEvent.Severity.WARNING;
            case "Error": return OpsEvent.Severity.IMMEDIATE;
            case "Critical": return OpsEvent.Severity.CRITICAL;
            default: return OpsEvent.Severity.INFORMATION;
        }
    }

    /** {@code [WindowsEvent-<Level> <Event>]}, the whole text safe to carry. */
    public static String message(String level, String event) {
        String l = GuestText.lineText(level == null ? "" : level, 40).replace('\n', ' ').replace('\t', ' ');
        String e = GuestText.eventText(event == null ? "" : event, GuestText.MAX_EVENT);
        return "[WindowsEvent-" + l + " " + e + "]";
    }

    public static OpsEvent of(String resourceId, GuestRows.EventRow row, long nowMs) {
        return new OpsEvent(resourceId, message(row.level, row.event), severity(row.levelNumber, row.level), nowMs);
    }
}
