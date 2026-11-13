package com.vcfcf.adapters.vcommunity.list;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads a check-list file: an XML document whose root element's text is a
 * comma separated list (design 4.10). Items are the root's text before its
 * first child element; comments are dropped and the text either side of one
 * is joined; the text is split on commas, each item stripped, empties dropped,
 * duplicates collapsed keeping the first. A newline is not a separator: two
 * items on two lines with no comma are one item with a line break in it,
 * which is skipped and reported (DV-60), not used.
 *
 * <p>DTDs and external entities are switched off; the document must be
 * well-formed to the end or the whole file is a parse failure.
 */
public final class CheckListParser {

    private CheckListParser() {}

    /** The items found and the ones skipped, each with its reason. */
    public static final class Parsed {
        public final List<String> items = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
    }

    /** The file is not a well-formed XML document. */
    public static final class ParseFailure extends Exception {
        private static final long serialVersionUID = 1L;

        ParseFailure(String message) {
            super(message);
        }
    }

    /** The root element's own text, before its first child; the document is read to its end. */
    static String rootText(String xml) throws ParseFailure {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        XMLStreamReader r = null;
        try {
            r = f.createXMLStreamReader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            int depth = 0;
            boolean sawChild = false;
            boolean sawRoot = false;
            StringBuilder text = new StringBuilder();
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1) sawRoot = true;
                    if (depth == 2) sawChild = true;
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA
                        || ev == XMLStreamConstants.SPACE) && depth == 1 && !sawChild) {
                    text.append(r.getText());
                }
            }
            if (!sawRoot) throw new ParseFailure("no root element");
            return text.toString();
        } catch (XMLStreamException e) {
            String m = e.getMessage();
            if (m != null) m = m.replaceAll("[\\p{Cntrl}]+", " ").trim();
            throw new ParseFailure(m == null || m.isEmpty() ? "not well-formed" : cap(m));
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release
                }
            }
        }
    }

    /**
     * @throws ParseFailure the text is not well-formed XML
     */
    public static Parsed parse(String xml) throws ParseFailure {
        Parsed out = new Parsed();
        if (xml == null || xml.trim().isEmpty()) return out;
        String text = rootText(xml);
        Set<String> seen = new LinkedHashSet<>();
        for (String part : text.split(",", -1)) {
            String item = part.strip();
            if (item.isEmpty()) continue;
            if (hasControl(item)) {
                out.skipped.add("an item contains a line break, tab or other control character "
                        + "(separate items with commas): '" + shownItem(item) + "'");
                continue;
            }
            if (seen.add(item)) out.items.add(item);
        }
        return out;
    }

    static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) return true;
        }
        return false;
    }

    /** An item for a message: one line, bounded. */
    public static String shownItem(String s) {
        String t = s.replaceAll("\\p{Cntrl}+", " ").trim();
        return t.length() > 60 ? t.substring(0, 60) + "..." : t;
    }

    private static String cap(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
