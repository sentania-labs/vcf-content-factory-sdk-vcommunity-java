package com.vcfcf.adapters.vcommunity.json;

/**
 * A pull reader over one JSON text, for answers too big to build as a tree: the caller asks for the next token and
 * skips what it does not want, so memory is the text itself and the value being looked at. It checks that braces and
 * brackets pair, that names sit in objects, and that every string and number is well formed; anything else throws
 * {@link IllegalArgumentException}, so a cut or garbled answer is never read as a shorter one (the caller must act
 * on the values only after the whole text has been read). Plain JDK.
 */
public final class JsonPull {

    /** What {@link #next} found. */
    public enum Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private final String s;
    private int i;
    private int depth;
    /** One entry per open container: true for an object, false for an array. */
    private final java.util.BitSet isObject = new java.util.BitSet();
    private final java.util.BitSet needsName = new java.util.BitSet();
    private boolean expectValue = true;
    private String text;
    private double number;

    public JsonPull(String s) {
        if (s == null) throw new IllegalArgumentException("no JSON text");
        this.s = s;
    }

    /** The text of the NAME or STRING token just read. */
    public String text() {
        return text;
    }

    /** The value of the NUMBER token just read. */
    public double number() {
        return number;
    }

    public Token next() {
        skipSpace();
        if (i >= s.length()) {
            if (depth != 0 || expectValue && !started) throw bad("unexpected end");
            return Token.END;
        }
        char c = s.charAt(i);
        if (depth > 0 && c == ',') {
            i++;
            skipSpace();
            if (i >= s.length()) throw bad("unexpected end");
            c = s.charAt(i);
            if (c == '}' || c == ']') throw bad("trailing comma");
        }
        if (depth > 0 && isObject.get(depth - 1) && needsName.get(depth - 1) && c != '}') {
            if (c != '"') throw bad("a name expected");
            text = string();
            skipSpace();
            if (i >= s.length() || s.charAt(i) != ':') throw bad("':' expected");
            i++;
            needsName.clear(depth - 1);
            return Token.NAME;
        }
        started = true;
        switch (c) {
            case '{':
                i++;
                isObject.set(depth);
                needsName.set(depth);
                depth++;
                return Token.BEGIN_OBJECT;
            case '[':
                i++;
                isObject.clear(depth);
                needsName.clear(depth);
                depth++;
                return Token.BEGIN_ARRAY;
            case '}':
                if (depth == 0 || !isObject.get(depth - 1)) throw bad("'}' without an object");
                i++;
                depth--;
                afterValue();
                return Token.END_OBJECT;
            case ']':
                if (depth == 0 || isObject.get(depth - 1)) throw bad("']' without an array");
                i++;
                depth--;
                afterValue();
                return Token.END_ARRAY;
            case '"':
                text = string();
                afterValue();
                return Token.STRING;
            case 't':
                literal("true");
                afterValue();
                return Token.BOOLEAN;
            case 'f':
                literal("false");
                afterValue();
                return Token.BOOLEAN;
            case 'n':
                literal("null");
                afterValue();
                return Token.NULL;
            default:
                number = readNumber();
                afterValue();
                return Token.NUMBER;
        }
    }

    private boolean started;

    /** A value ended: inside an object the next token is a name again. */
    private void afterValue() {
        if (depth > 0 && isObject.get(depth - 1)) needsName.set(depth - 1);
    }

    /**
     * Skip the value that starts with the token just returned: nothing for a scalar, to the matching end for an
     * object or an array.
     */
    public void skip(Token t) {
        if (t != Token.BEGIN_OBJECT && t != Token.BEGIN_ARRAY) return;
        int target = depth - 1;
        while (depth > target) {
            if (next() == Token.END) throw bad("unexpected end");
        }
    }

    private void skipSpace() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private String string() {
        i++;
        int start = i;
        // the common case: no escape
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                String r = s.substring(start, i);
                i++;
                return r;
            }
            if (c == '\\' || c < 0x20) break;
            i++;
        }
        StringBuilder b = new StringBuilder(s.substring(start, Math.min(i, s.length())));
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

    private double readNumber() {
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

    private IllegalArgumentException bad(String what) {
        return new IllegalArgumentException("invalid JSON at " + i + ": " + what);
    }
}
