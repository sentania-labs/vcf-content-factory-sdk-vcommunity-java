package com.vcfcf.adapters.vcommunity.list;

import java.util.regex.Pattern;

/**
 * Which Windows service names may reach a command line in a guest: letters,
 * digits, space and {@code _ . $ # @ ( ) + -}, the first character a letter,
 * digit or underscore, at most 256 characters, and never {@code $(}. A name
 * with a quote, a backtick, a separator or a wildcard is left out and
 * reported, because the name is placed between quotes in a command that runs
 * in every monitored VM.
 */
public final class ServiceNameFilter {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9 _.$#@()+\\-]{0,255}");

    private ServiceNameFilter() {}

    public static boolean isSafe(String name) {
        return name != null && SAFE.matcher(name).matches() && !name.contains("$(");
    }
}
