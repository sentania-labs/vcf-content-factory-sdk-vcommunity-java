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
    private String xsiType;
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

    /** The text of the first direct child with this name exactly as sent, or null. */
    public String rawChildText(String localName) {
        XmlNode c = child(localName);
        return c == null ? null : c.rawText();
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

    /** This element's own text exactly as sent (a string value keeps its spaces). */
    public String rawText() {
        return text.toString();
    }

    /** An attribute by local name, from no namespace ({@code type} of a MoRef reads that, not {@code xsi:type}), or null. */
    public String attr(String localName) {
        return attributes.get(localName);
    }

    /** The {@code xsi:type} of this element as sent (prefix kept, {@code xsd:boolean}), or null. */
    public String xsiType() {
        return xsiType;
    }

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private void readAttributes(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            String ns = r.getAttributeNamespace(i);
            if (XSI_NS.equals(ns)) {
                if ("type".equals(r.getAttributeLocalName(i))) xsiType = r.getAttributeValue(i);
            } else {
                attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
            }
        }
    }

    /** What to keep of a value read with {@link #readElement}. */
    public interface Prune {
        /**
         * Whether to keep the descendant at this path below the element read,
         * the local names joined by {@code .}; an array repeats one name, so no
         * index is part of the path.
         */
        boolean keepPath(String relativePath);

        /** Whether to keep a finished direct child (an array element). */
        boolean keepElement(XmlNode element);
    }

    /**
     * Build the subtree of the element the reader is on (a START_ELEMENT) and
     * leave the reader on its END_ELEMENT. Descendants {@code prune} refuses are
     * skipped without being built, so the tree holds only what is kept.
     *
     * @param prune   null keeps everything
     * @param counter {@code counter[0]} is raised by every element built
     */
    public static XmlNode readElement(XMLStreamReader r, Prune prune, int[] counter)
            throws XMLStreamException {
        XmlNode root = new XmlNode(r.getLocalName());
        root.readAttributes(r);
        if (counter != null) counter[0]++;
        Deque<XmlNode> stack = new ArrayDeque<>();
        Deque<String> paths = new ArrayDeque<>();
        stack.push(root);
        paths.push("");
        while (!stack.isEmpty()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String parentPath = paths.peek();
                String rel = parentPath.isEmpty() ? r.getLocalName() : parentPath + "." + r.getLocalName();
                if (prune != null && !prune.keepPath(rel)) {
                    skipElement(r);
                    continue;
                }
                if (stack.size() >= MAX_DEPTH) {
                    throw new XMLStreamException("elements nested deeper than " + MAX_DEPTH);
                }
                XmlNode n = new XmlNode(r.getLocalName());
                n.readAttributes(r);
                if (counter != null) counter[0]++;
                stack.peek().children.add(n);
                stack.push(n);
                paths.push(rel);
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                XmlNode done = stack.pop();
                paths.pop();
                if (stack.size() == 1 && prune != null && !prune.keepElement(done)) {
                    List<XmlNode> sib = stack.peek().children;
                    sib.remove(sib.size() - 1);
                    if (counter != null) counter[0] -= countNodes(done);
                }
            } else if ((ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA)
                    && !stack.isEmpty()) {
                stack.peek().text.append(r.getText());
            }
        }
        return root;
    }

    private static int countNodes(XmlNode n) {
        int c = 1;
        for (XmlNode k : n.children) c += countNodes(k);
        return c;
    }

    /** Skip the element the reader is on, to its END_ELEMENT. */
    public static void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) depth++;
            else if (ev == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    /** A new reader over a stream: DTDs and external entities are off. */
    public static XMLStreamReader reader(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        return f.createXMLStreamReader(in);
    }

    /** Read one document; DTDs and external entities are off. */
    public static XmlNode parse(InputStream in) throws XMLStreamException {
        XMLStreamReader r = reader(in);
        try {
            Deque<XmlNode> stack = new ArrayDeque<>();
            XmlNode root = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    XmlNode n = new XmlNode(r.getLocalName());
                    n.readAttributes(r);
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
