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
     * @return field name to stripped value; a field that came back empty or blank is the empty text,
     *         the last of a repeated name wins, as in the original
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new HashMap<>();
        if (text == null) return out;
        Matcher m = CUT_AT_FIRST_QUOTE.matcher(PyText.strip(text));
        while (m.find()) {
            // a field the text returned with an empty value is a value, the empty text (DV-112); the last one wins
            out.put(m.group(1), Quirks.stripped(m.group(2)));
        }
        return out;
    }
}
