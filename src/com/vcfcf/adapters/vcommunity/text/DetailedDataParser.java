package com.vcfcf.adapters.vcommunity.text;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code name='value'} pairs of {@code guest.guestDetailedData}
 * with the same pattern the original pack applies, so a value that holds a
 * quote is cut at the first quote just as it is there (design 4.5). Python's
 * {@code \w} is Unicode-aware and its {@code .} stops only at a line feed,
 * which the flags reproduce.
 */
public final class DetailedDataParser {

    private static final Pattern PAIR = Pattern.compile("(\\w+)=['\"](.*?)['\"]",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);

    private DetailedDataParser() {}

    /**
     * @return field name to stripped value; a field whose value is empty after
     *         stripping is left out (it carries nothing), the last of a repeated
     *         name wins, as in the original
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new HashMap<>();
        if (text == null) return out;
        Matcher m = PAIR.matcher(PyText.strip(text));
        while (m.find()) {
            String v = PyText.strip(m.group(2));
            if (v.isEmpty()) {
                out.remove(m.group(1));
            } else {
                out.put(m.group(1), v);
            }
        }
        return out;
    }
}
