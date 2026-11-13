package com.vcfcf.adapters.vcommunity.text;

/**
 * The host license key as it is published (design 5.4, DV-25): every character
 * of every group but the last is {@code *}, hyphens stay, and the last group is
 * kept. The full key is never logged, never part of an exception message and
 * never pushed.
 */
public final class LicenseKeyMask {

    private LicenseKeyMask() {}

    /** @return the masked key, or null when there is no key to mask */
    public static String mask(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.isEmpty()) return null;
        int last = k.lastIndexOf('-');
        StringBuilder b = new StringBuilder(k.length());
        if (last >= 0) {
            for (int i = 0; i <= last; i++) {
                char c = k.charAt(i);
                b.append(c == '-' ? '-' : '*');
            }
            b.append(k, last + 1, k.length());
            return b.toString();
        }
        int keep = Math.min(5, k.length());
        // a key of five characters or fewer has no group to keep: fully masked
        if (k.length() <= 5) keep = 0;
        for (int i = 0; i < k.length() - keep; i++) b.append('*');
        b.append(k, k.length() - keep, k.length());
        return b.toString();
    }
}
