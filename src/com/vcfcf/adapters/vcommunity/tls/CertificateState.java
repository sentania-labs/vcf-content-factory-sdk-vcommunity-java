package com.vcfcf.adapters.vcommunity.tls;

import com.vcfcf.adapters.vcommunity.json.Json;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Remembers the vCenter certificate between cycles (design 6, rows R56-R59).
 * The state is in memory; after a start it is read back once from the
 * vCenter object's own properties, which is where the last cycle left it. A
 * read-back that fails leaves the state unknown, and an unknown state sends no
 * Status: it is never read as a first sight of the certificate (DV-23).
 *
 * <p>Not thread safe: the cycle thread uses it.
 */
public final class CertificateState {

    public static final String PREFIX = "vCommunity|Certificate|";
    public static final String STATUS = PREFIX + "Status";
    public static final String THUMBPRINT = PREFIX + "SHA-256 Thumbprint";
    public static final String PREVIOUS = PREFIX + "Previous SHA-256 Thumbprint";
    public static final String LAST_CHANGED = PREFIX + "Last Changed";

    public static final String RECORDED = "Recorded";
    public static final String UNCHANGED = "Unchanged";
    public static final String CHANGED = "Changed";

    /** A change shows as Changed for this long. */
    static final long SHOWN_MS = 24L * 3600 * 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",
            Locale.ROOT);
    private static final String PATH = "/api/resources/properties/latest/query";

    /** The outcome of one comparison; a null field is not sent. */
    public static final class Check {
        public final String status;
        public final String thumbprint;
        public final String previous;
        public final String lastChanged;

        Check(String status, String thumbprint, String previous, String lastChanged) {
            this.status = status;
            this.thumbprint = thumbprint;
            this.previous = previous;
            this.lastChanged = lastChanged;
        }
    }

    private boolean known;
    private String recorded;
    private String lastChanged;

    /** Whether the state is known (read back, or built up by this run). */
    public boolean isKnown() {
        return known;
    }

    /** Forget everything; the next cycle reads it back again. */
    public void reset() {
        known = false;
        recorded = null;
        lastChanged = null;
    }

    /** Set the state from the read-back; {@code null} values mean none was ever recorded. */
    public void load(String recordedThumbprint, String lastChangedText) {
        known = true;
        recorded = blankToNull(recordedThumbprint);
        lastChanged = blankToNull(lastChangedText);
    }

    /**
     * Read the two properties back from the vCenter object.
     *
     * @throws IOException a failed read: a non-2xx answer, a network failure or an answer that is not
     *                     the expected JSON; the state stays unknown
     */
    public void readBack(OpsApi ops, String vcenterResourceId)
            throws IOException, InterruptedException {
        String body = "{\"resourceIds\":[" + Json.quote(vcenterResourceId) + "],\"propertyKeys\":["
                + Json.quote(THUMBPRINT) + "," + Json.quote(LAST_CHANGED) + "]}";
        String answer = ops.post(PATH, body);
        String[] got = parse(answer, vcenterResourceId);
        load(got[0], got[1]);
    }

    /** The recorded thumbprint and the last-changed text of one resource; either may be null. */
    static String[] parse(String json, String resourceId) throws IOException {
        Object root;
        try {
            root = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IOException("the properties answer is not valid JSON");
        }
        if (!(root instanceof java.util.Map)) {
            throw new IOException("the properties answer is not the expected JSON object");
        }
        String[] out = new String[2];
        for (Object r : Json.list(Json.get(root, "values"))) {
            if (!resourceId.equals(Json.str(Json.get(r, "resourceId")))) continue;
            Object contents = Json.get(Json.get(r, "property-contents"), "property-content");
            for (Object c : Json.list(contents)) {
                String key = Json.str(Json.get(c, "statKey"));
                String v = lastSample(c);
                if (THUMBPRINT.equals(key)) out[0] = v;
                else if (LAST_CHANGED.equals(key)) out[1] = v;
            }
        }
        return out;
    }

    /** A text property has {@code values}, a number property {@code data}: the last of either. */
    private static String lastSample(Object content) {
        List<Object> values = Json.list(Json.get(content, "values"));
        if (values.isEmpty()) values = Json.list(Json.get(content, "data"));
        if (values.isEmpty()) return null;
        Object last = values.get(values.size() - 1);
        String s = Json.str(last);
        return s;
    }

    /**
     * Compare the certificate presented now with the one recorded. Needs a known state; with an unknown
     * state, or no presented thumbprint, nothing is concluded and nothing is sent (DV-23).
     */
    public Check check(String presented, long nowMs) {
        if (!known || presented == null || presented.isEmpty()) {
            return new Check(null, null, null, null);
        }
        if (recorded == null) return new Check(RECORDED, presented, null, null);
        if (!presented.equals(recorded)) {
            return new Check(CHANGED, presented, recorded, format(nowMs));
        }
        Long at = parse(lastChanged);
        if (at != null && nowMs - at < SHOWN_MS) return new Check(CHANGED, presented, null, null);
        return new Check(UNCHANGED, presented, null, null);
    }

    /** Remember what was sent; call it only after the push was accepted. */
    public void commit(Check c) {
        if (c == null || c.thumbprint == null) return;
        recorded = c.thumbprint;
        if (c.lastChanged != null) lastChanged = c.lastChanged;
    }

    static String format(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).format(TIME) + " UTC";
    }

    private static Long parse(String text) {
        if (text == null || !text.endsWith(" UTC")) return null;
        try {
            return LocalDateTime.parse(text.substring(0, text.length() - 4), TIME)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
