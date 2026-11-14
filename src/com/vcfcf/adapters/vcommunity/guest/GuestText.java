package com.vcfcf.adapters.vcommunity.guest;

/**
 * What may come out of a guest's text and into a key, a property or an event (design 8.5, 8.6). The guest is
 * untrusted: text that is read into a key name or a property is checked and refused, and text that is read
 * into an event message is cleaned and cut. Nothing from a guest is ever written to the log.
 */
public final class GuestText {

    public static final int MAX_VALUE = 512;
    public static final int MAX_INSTANCE = 128;
    public static final int MAX_EVENT = 2000;

    private GuestText() {}

    /** Characters that make a line of text mean something else: controls, line and paragraph separators, bidi overrides. */
    private static boolean unsafe(char c) {
        return c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == 0x2028 || c == 0x2029
                || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069);
    }

    private static boolean loneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        if (Character.isLowSurrogate(c)) return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        return false;
    }

    /** True when the text can be a property value: not too long, nothing in it that {@link #unsafe} names. */
    public static boolean isSafeValue(String s) {
        if (s == null || s.length() > MAX_VALUE) return false;
        for (int i = 0; i < s.length(); i++) {
            if (unsafe(s.charAt(i)) || loneSurrogate(s, i)) return false;
        }
        return true;
    }

    /**
     * True when the text can be an instance name of a key ({@code Services:<name>}): a safe value that is not
     * blank, is short, and holds no {@code |}, which would start another level of the key.
     */
    public static boolean isSafeInstance(String s) {
        return s != null && s.length() <= MAX_INSTANCE && !s.trim().isEmpty() && s.indexOf('|') < 0
                && isSafeValue(s);
    }

    /**
     * Event text made fit to carry: line breaks become one line feed, tabs stay, every other control character,
     * separator, override and unpaired surrogate is dropped, and it is cut at {@code max} characters (with
     * {@code ...} when it was longer).
     */
    public static String eventText(String s, int max) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), max + 3));
        boolean cut = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\n') continue;
                c = '\n';
            }
            if (c != '\n' && c != '\t' && (unsafe(c) || loneSurrogate(s, i))) continue;
            if (b.length() >= max) {
                cut = true;
                break;
            }
            b.append(c);
        }
        if (cut) {
            // never end on half a surrogate pair
            if (b.length() > 0 && Character.isHighSurrogate(b.charAt(b.length() - 1))) b.setLength(b.length() - 1);
            b.append("...");
        }
        return b.toString();
    }
}
