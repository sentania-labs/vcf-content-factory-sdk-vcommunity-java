package com.vcfcf.adapters.vcommunity.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the Suite API bodies. {@link #parse}
 * returns {@link Map}, {@link List}, {@link String}, {@link Double},
 * {@link Boolean} or {@code null}; anything malformed, trailing, or nested
 * deeper than {@link #MAX_DEPTH} throws {@link IllegalArgumentException}, so a
 * cut body is never read as a shorter document. Plain JDK.
 */
public final class Json {

    public static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parse one JSON document. */
    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("no JSON text");
        Json p = new Json(text);
        p.ws();
        Object v = p.value(1);
        p.ws();
        if (p.i != p.s.length()) throw p.bad("trailing characters");
        return v;
    }

    /** {@code m.get(key)} when {@code m} is a map, else null. */
    public static Object get(Object m, String key) {
        return m instanceof Map ? ((Map<?, ?>) m).get(key) : null;
    }

    /** The value as text when it is a string or a number, else null. */
    public static String str(Object v) {
        if (v instanceof String) return (String) v;
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return null;
    }

    /** The value as a list, else an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    /** The value as a number, else null. */
    public static Double num(Object v) {
        return v instanceof Double ? (Double) v : null;
    }

    /** {@code s} as a JSON string literal, quotes included; null as {@code null}. */
    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw bad("nested too deep");
        if (i >= s.length()) throw bad("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw bad("object key expected");
            String k = string();
            ws();
            if (next() != ':') throw bad("':' expected");
            ws();
            m.put(k, value(depth + 1));
            ws();
            int c = next();
            if (c == '}') return m;
            if (c != ',') throw bad("',' or '}' expected");
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            int c = next();
            if (c == ']') return l;
            if (c != ',') throw bad("',' or ']' expected");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw bad("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw bad("control character in string");
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw bad("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw bad("short \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw bad("bad \\u escape");
                    }
                    i += 4;
                    break;
                default: throw bad("bad escape");
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw bad("value expected");
        try {
            double d = Double.parseDouble(s.substring(start, i));
            if (Double.isNaN(d) || Double.isInfinite(d)) throw bad("number out of range");
            return d;
        } catch (NumberFormatException e) {
            throw bad("bad number");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw bad("bad literal");
        i += word.length();
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private int peek() {
        return i < s.length() ? s.charAt(i) : -1;
    }

    private int next() {
        return i < s.length() ? s.charAt(i++) : -1;
    }

    private IllegalArgumentException bad(String what) {
        return new IllegalArgumentException("invalid JSON at " + i + ": " + what);
    }
}
