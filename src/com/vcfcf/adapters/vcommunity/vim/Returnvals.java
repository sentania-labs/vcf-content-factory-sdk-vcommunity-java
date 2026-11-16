package com.vcfcf.adapters.vcommunity.vim;

import java.util.function.Consumer;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Walks the answer of a method that returns an array: one {@code returnval}
 * element per item. Each item is built with the prune and handed on at once,
 * so the answer is never held whole (design 4.1).
 */
public final class Returnvals {

    private Returnvals() {}

    /**
     * @param prune null keeps each item whole
     * @param each  called once per item, from the reading thread
     */
    public static void forEach(XMLStreamReader r, XmlNode.Prune prune, Consumer<XmlNode> each)
            throws XMLStreamException {
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if ("returnval".equals(r.getLocalName())) {
                    each.accept(XmlNode.readElement(r, prune, null));
                }
            }
        }
    }
}
