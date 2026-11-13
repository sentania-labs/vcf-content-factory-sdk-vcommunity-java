package com.vcfcf.adapters.vcommunity.vim;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A small element tree, at most {@value #MAX_DEPTH} levels deep, for the short SOAP answers (service content, a fault,
 * a keepalive). Read by local name; a missing element is {@code null}, never
 * an exception. Bulk answers are not read through this: they stream.
 */
public final class XmlNode {

    /** Elements nested deeper than this are refused; a service answer is a few levels deep. */
    public static final int MAX_DEPTH = 32;

    public final String name;
    private final Map<String, String> attributes = new HashMap<>();
    private final List<XmlNode> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    private XmlNode(String name) {
        this.name = name;
    }

    /** The first direct child with this local name, or null. */
    public XmlNode child(String localName) {
        for (XmlNode c : children) {
            if (c.name.equals(localName)) return c;
        }
        return null;
    }

    public List<XmlNode> children() {
        return Collections.unmodifiableList(children);
    }

    /** The text of the first direct child with this name, trimmed, or null. */
    public String childText(String localName) {
        XmlNode c = child(localName);
        return c == null ? null : c.text();
    }

    /**
     * The first element with this local name, depth first, this node included,
     * or null. Iterative, so the tree's depth cannot overflow the stack.
     */
    public XmlNode find(String localName) {
        Deque<XmlNode> todo = new ArrayDeque<>();
        todo.push(this);
        while (!todo.isEmpty()) {
            XmlNode n = todo.pop();
            if (n.name.equals(localName)) return n;
            for (int i = n.children.size() - 1; i >= 0; i--) todo.push(n.children.get(i));
        }
        return null;
    }

    /** This element's own text, trimmed. */
    public String text() {
        return text.toString().trim();
    }

    /** An attribute by local name ({@code type} reads {@code xsi:type}), or null. */
    public String attr(String localName) {
        return attributes.get(localName);
    }

    /** Read one document; DTDs and external entities are off. */
    public static XmlNode parse(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        XMLStreamReader r = f.createXMLStreamReader(in);
        try {
            Deque<XmlNode> stack = new ArrayDeque<>();
            XmlNode root = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    XmlNode n = new XmlNode(r.getLocalName());
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        n.attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                    }
                    if (stack.size() >= MAX_DEPTH) {
                        throw new XMLStreamException("elements nested deeper than " + MAX_DEPTH);
                    }
                    if (stack.isEmpty()) {
                        root = n;
                    } else {
                        stack.peek().children.add(n);
                    }
                    stack.push(n);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    stack.pop();
                } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA)
                        && !stack.isEmpty()) {
                    stack.peek().text.append(r.getText());
                }
            }
            if (root == null) throw new XMLStreamException("no root element");
            return root;
        } finally {
            r.close();
        }
    }
}
