package com.vcfcf.adapters.vcommunity.list;

import java.util.regex.Pattern;

/**
 * A VM Options item is read as a vim25 property path, so it must be one:
 * segments separated by dots, each a letter followed by letters and digits,
 * at most 256 characters. Anything else (spaces, {@code [0]}, a slash, a
 * trailing dot) is skipped and reported before any call is made (design 5.5).
 */
public final class VmOptionPath {

    private static final Pattern PATH = Pattern.compile("[A-Za-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)*");

    private VmOptionPath() {}

    public static boolean isValid(String item) {
        return item != null && item.length() <= 256 && PATH.matcher(item).matches();
    }
}
