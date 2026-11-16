package com.vcfcf.adapters.vcommunity.list;

import com.vcfcf.adapters.vcommunity.json.Json;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * The server's message out of the start of an error body: the {@code message}
 * of a JSON object, or the text of the {@code message} element (any namespace
 * prefix) or attribute of an XML error. Markup is never returned; a body that
 * holds no message, or that is cut before it, gives "".
 */
final class ErrorText {

    private ErrorText() {}

    static String of(String excerpt) {
        if (excerpt == null) return "";
        String t = excerpt.replace("\uFEFF", "").trim();
        if (t.isEmpty()) return "";
        String m;
        if (t.startsWith("{")) {
            m = json(t);
        } else if (t.startsWith("<")) {
            m = xml(t);
        } else {
            m = t;
        }
        if (m == null) return "";
        m = m.replaceAll("\\p{Cntrl}+", " ").trim();
        // a message that holds markup (an HTML error page, a tag in a JSON text) is not quoted
        return m.indexOf('<') >= 0 || m.indexOf('>') >= 0 ? "" : m;
    }

    private static String json(String t) {
        try {
            return Json.str(Json.get(Json.parse(t), "message"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String xml(String t) {
        XMLStreamReader r = null;
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            r = f.createXMLStreamReader(new ByteArrayInputStream(t.getBytes(StandardCharsets.UTF_8)));
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue;
                String a = r.getAttributeValue(null, "message");
                if (a != null) return a;
                if ("message".equals(r.getLocalName())) return r.getElementText();
            }
        } catch (Exception e) {
            // cut off or malformed: whatever was read before is all there is
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                    // nothing to release
                }
            }
        }
        return null;
    }
}
