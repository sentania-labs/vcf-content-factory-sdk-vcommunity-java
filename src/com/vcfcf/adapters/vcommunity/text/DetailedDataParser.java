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

    /**
     * Q6 (rows R112-R118): upstream's pattern ends a value at the first quote of either kind, so a value
     * that holds a quote is cut there. Correct would be to end it at the quote that opened it.
     */
    static final Pattern CUT_AT_FIRST_QUOTE = Pattern.compile("(\\w+)=['\"](.*?)['\"]",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);

    private DetailedDataParser() {}

    /**
     * @return field name to stripped value; a field whose value is empty is left out
     *         (a value of spaces is the empty string, see {@link Quirks#BLANK_STRINGS_PUSHED}),
     *         the last of a repeated name wins, as in the original
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new HashMap<>();
        if (text == null) return out;
        Matcher m = CUT_AT_FIRST_QUOTE.matcher(PyText.strip(text));
        while (m.find()) {
            String v = Quirks.strippedIfTruthy(m.group(2));
            if (v == null) {
                out.remove(m.group(1));
            } else {
                out.put(m.group(1), v);
            }
        }
        return out;
    }
}
