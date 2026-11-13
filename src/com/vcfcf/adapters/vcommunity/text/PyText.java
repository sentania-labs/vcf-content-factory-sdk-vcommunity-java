package com.vcfcf.adapters.vcommunity.text;

import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a Python {@code str()} would give for a value read from vim25, so
 * that a key carries the same characters as the original pack's (design 0.6
 * and appendix B). Every method returns {@code null} for input it cannot
 * render: a value that cannot be read is withheld by the caller, never
 * replaced by a placeholder.
 */
public final class PyText {

    private PyText() {}

    private static final Pattern DATE_TIME = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})?");
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    /** {@code true} / {@code false} (also 1 / 0) as {@code True} / {@code False}; else null. */
    public static String bool(String xsd) {
        if (xsd == null) return null;
        String t = xsd.trim();
        if (t.equals("true") || t.equals("1")) return "True";
        if (t.equals("false") || t.equals("0")) return "False";
        return null;
    }

    /** Decimal integer text without a plus sign or leading zeros; else null. */
    public static String integer(String xsd) {
        if (xsd == null) return null;
        String t = xsd.trim();
        if (!INTEGER.matcher(t).matches()) return null;
        try {
            return new java.math.BigInteger(t).toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Python {@code repr(float)} of an {@code xsd:double} or {@code xsd:float}; else null. */
    public static String floating(String xsd) {
        if (xsd == null) return null;
        String t = xsd.trim();
        switch (t) {
            case "NaN": return "nan";
            case "INF": return "inf";
            case "-INF": return "-inf";
            default:
        }
        double d;
        try {
            d = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
        return repr(d);
    }

    /**
     * Python's {@code repr()} of a float: the shortest digits that read back as
     * the same number; fixed notation for a decimal exponent from -4 up to 15,
     * else scientific with a signed exponent of at least two digits; a whole
     * value keeps {@code .0}.
     */
    public static String repr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        String sci = null;
        for (int p = 0; p < 17; p++) {
            String s = String.format(Locale.ROOT, "%." + p + "e", d);
            if (Double.parseDouble(s) == d) {
                sci = s;
                break;
            }
        }
        if (sci == null) sci = String.format(Locale.ROOT, "%.16e", d);
        boolean neg = sci.startsWith("-");
        if (neg) sci = sci.substring(1);
        int e = sci.indexOf('e');
        String mant = sci.substring(0, e);
        int exp = Integer.parseInt(sci.substring(e + 1));
        String digits = mant.replace(".", "");
        // drop trailing zeros of the digit string (the shortest form has none, but %.0e of 1e22 etc.)
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        digits = digits.substring(0, end);
        StringBuilder b = new StringBuilder();
        if (neg) b.append('-');
        if (exp >= -4 && exp < 16) {
            if (exp >= 0) {
                if (digits.length() <= exp + 1) {
                    b.append(digits);
                    for (int i = digits.length(); i < exp + 1; i++) b.append('0');
                    b.append(".0");
                } else {
                    b.append(digits, 0, exp + 1).append('.').append(digits.substring(exp + 1));
                }
            } else {
                b.append("0.");
                for (int i = 0; i < -exp - 1; i++) b.append('0');
                b.append(digits);
            }
        } else {
            b.append(digits.charAt(0));
            if (digits.length() > 1) b.append('.').append(digits.substring(1));
            b.append('e').append(exp < 0 ? '-' : '+');
            int ae = Math.abs(exp);
            if (ae < 10) b.append('0');
            b.append(ae);
        }
        return b.toString();
    }

    /**
     * Python {@code str(datetime)} of an {@code xsd:dateTime}:
     * {@code YYYY-MM-DD HH:MM:SS[.ffffff][+HH:MM]}. An offset (or {@code Z}, as
     * {@code +00:00}) is kept as sent; a value with none stays naive, as Python
     * keeps it. Null when the text is not a date time.
     */
    public static String dateTime(String xsd) {
        return renderDateTime(xsd, ' ', false);
    }

    /**
     * Python {@code astimezone(utc).isoformat()}: {@code YYYY-MM-DDTHH:MM:SS[.ffffff]+00:00}.
     * A value with no offset is taken as already UTC. Null when not a date time.
     */
    public static String isoUtc(String xsd) {
        return renderDateTime(xsd, 'T', true);
    }

    private static String renderDateTime(String xsd, char sep, boolean toUtc) {
        if (xsd == null) return null;
        Matcher m = DATE_TIME.matcher(xsd.trim());
        if (!m.matches()) return null;
        int y = Integer.parseInt(m.group(1));
        int mo = Integer.parseInt(m.group(2));
        int d = Integer.parseInt(m.group(3));
        int h = Integer.parseInt(m.group(4));
        int mi = Integer.parseInt(m.group(5));
        int s = Integer.parseInt(m.group(6));
        if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59) return null;
        String frac = m.group(7);
        String micro = "";
        if (frac != null) {
            String f6 = frac.length() >= 6 ? frac.substring(0, 6) : frac + "000000".substring(frac.length());
            if (!f6.equals("000000")) micro = "." + f6;
        }
        String off = m.group(8);
        String zone;
        if (toUtc) {
            if (off != null && !off.equals("Z") && !off.equals("+00:00") && !off.equals("-00:00")) {
                java.time.LocalDateTime ldt;
                try {
                    ldt = java.time.LocalDateTime.of(y, mo, d, h, mi, s)
                            .minusSeconds(offsetSeconds(off));
                } catch (java.time.DateTimeException e) {
                    return null;
                }
                y = ldt.getYear();
                mo = ldt.getMonthValue();
                d = ldt.getDayOfMonth();
                h = ldt.getHour();
                mi = ldt.getMinute();
                s = ldt.getSecond();
            }
            zone = "+00:00";
        } else if (off == null) {
            zone = "";
        } else if (off.equals("Z")) {
            zone = "+00:00";
        } else {
            zone = off;
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d%c%02d:%02d:%02d%s%s", y, mo, d, sep, h, mi,
                s, micro, zone);
    }

    /** The instant of an {@code xsd:dateTime} that carries an offset; null for one without (naive) or unparseable. */
    public static java.time.Instant instant(String xsd) {
        if (xsd == null) return null;
        try {
            return java.time.OffsetDateTime.parse(xsd.trim()).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static int offsetSeconds(String off) {
        int sign = off.charAt(0) == '-' ? -1 : 1;
        int hh = Integer.parseInt(off.substring(1, 3));
        int mm = Integer.parseInt(off.substring(4, 6));
        return sign * (hh * 3600 + mm * 60);
    }

    /**
     * {@code str(value)} for a scalar the property collector sent with its
     * {@code xsi:type}. A boolean is {@code True}/{@code False}, an integer is
     * decimal, a float is Python's repr, a date time is {@code str(datetime)};
     * a string, an enum or anything with no type is its text. Null when the
     * element is not a single value (it has child elements, or is an array) or
     * the text does not match its type.
     */
    public static String scalar(XmlNode val) {
        if (val == null) return null;
        if (!val.children().isEmpty()) return null;
        String type = val.xsiType();
        if (type != null && type.startsWith("ArrayOf")) return null;
        String text = val.text();
        if (type == null) return val.rawText();
        switch (stripPrefix(type)) {
            case "boolean": return bool(text);
            case "int": case "long": case "short": case "byte": return integer(text);
            case "double": case "float": return floating(text);
            case "dateTime": return dateTime(text);
            // pyVmomi's text for an object reference is not one this port reproduces
            case "ManagedObjectReference": return null;
            default: return val.rawText();
        }
    }

    /** {@code xsd:string} keeps its text as sent; this is the one place that decides. */
    private static String stripPrefix(String type) {
        int c = type.indexOf(':');
        return c < 0 ? type : type.substring(c + 1);
    }

    /** Python {@code str.strip()}: removes the characters {@code str.isspace()} accepts. */
    public static String strip(String s) {
        if (s == null) return null;
        int a = 0;
        int b = s.length();
        while (a < b && isPySpace(s.charAt(a))) a++;
        while (b > a && isPySpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    static boolean isPySpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }
}
