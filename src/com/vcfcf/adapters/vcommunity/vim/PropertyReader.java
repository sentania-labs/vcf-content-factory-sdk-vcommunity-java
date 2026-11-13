package com.vcfcf.adapters.vcommunity.vim;

import com.vcfcf.adapters.vcommunity.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Paged property collector reads over a {@link VimReader} (design 4.1): one
 * container view per read, {@code RetrievePropertiesEx} with a page size,
 * {@code ContinueRetrievePropertiesEx} until no token, the view destroyed in
 * a {@code finally}. The answer is parsed as a stream: each object is built
 * from its elements, handed to the sink and dropped, so the heap holds one
 * object at a time whatever the inventory (heap rule, design 9.3).
 *
 * <p>A failure at any page fails the whole read; the caller discards what the
 * sink already received, so a partial list is never read as complete. A path
 * vCenter refuses as an invalid property is dropped and the read repeated, at
 * most {@value #MAX_DROPS} times; the dropped paths are returned in the
 * stats and nothing is made up for them.
 */
public final class PropertyReader {

    public static final int MAX_DROPS = 20;
    /** The most bytes one page may answer (design 9.3). */
    public static final long MAX_PAGE_BYTES = 16L * 1024 * 1024;
    private static final int PAGE_READ_MS = VimSoapClient.READ_TIMEOUT_MS;
    private static final int SHORT_READ_MS = VimSoapClient.SHORT_READ_TIMEOUT_MS;

    private final VimReader vim;
    private final long maxPageBytes;
    private final BooleanSupplier stop;
    private final Log log;

    public PropertyReader(VimReader vim) {
        this(vim, MAX_PAGE_BYTES);
    }

    /** @param maxPageBytes the most bytes one page may answer */
    public PropertyReader(VimReader vim, long maxPageBytes) {
        this(vim, maxPageBytes, () -> false, null);
    }

    /**
     * @param stop true when the read must go no further (the cycle deadline passed, the thread was
     *             interrupted): checked before every page and after every object; the read then
     *             releases its continuation token and its view and fails with fault class {@code Stopped}
     * @param log  where a view or token that could not be released is said (INFO); may be null
     */
    public PropertyReader(VimReader vim, long maxPageBytes, BooleanSupplier stop, Log log) {
        this.vim = vim;
        this.maxPageBytes = maxPageBytes;
        this.stop = stop;
        this.log = log == null ? Log.NONE : log;
    }

    /** Thrown inside a page to end it when {@link #stop} says so. */
    private static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stopped() {
            super("stopped", null, false, false);
        }
    }

    /**
     * Read every object of the request's type (or its explicit objects).
     *
     * @param knownInvalid paths already found invalid on this vCenter: not asked for again
     * @param sink         receives each object; called from the calling thread
     */
    public VimResult<RetrieveStats> retrieve(RetrieveRequest req, Set<String> knownInvalid,
            RowSink sink) {
        ServiceRefs refs = vim.refs();
        if (refs == null || refs.propertyCollector == null) {
            return VimResult.fault("Parse", "the vCenter did not name its property collector",
                    null, 0, null);
        }
        RetrieveStats stats = new RetrieveStats();
        Map<String, PathSpec> paths = new LinkedHashMap<>(req.paths());
        for (String k : knownInvalid) {
            if (paths.remove(k) != null) {
                stats.pathsKnownInvalid++;
            }
        }
        MoRef view = null;
        try {
            if (req.objects == null) {
                if (refs.viewManager == null || refs.rootFolder == null) {
                    return VimResult.fault("Parse", "the vCenter did not name its view manager "
                            + "or root folder", null, 0, null);
                }
                VimResult<MoRef> v = createView(refs, req.type, stats);
                if (!v.isOk()) {
                    return VimResult.fault(v.faultClass(), v.message(), v.invalidPropertyPath(),
                            stats.bytes, v.cause());
                }
                view = v.value();
            }
            for (int attempt = 0; ; attempt++) {
                if (stop.getAsBoolean()) return stopped(stats);
                VimResult<String> first = page(refs, buildRetrieve(refs, req, view, paths), "RetrievePropertiesEx",
                        paths, sink, stats);
                if (first.isOk()) {
                    String token = first.value();
                    if (req.isFirstPageOnly()) return VimResult.ok(stats, stats.bytes); // the token is released below
                    while (token != null) {
                        if (stop.getAsBoolean()) return stopped(stats);
                        VimResult<String> next = page(refs, buildContinue(refs, token),
                                "ContinueRetrievePropertiesEx", paths, sink, stats);
                        if (!next.isOk()) {
                            return stop.getAsBoolean() ? stopped(stats) : fail(next, stats);
                        }
                        token = next.value();
                    }
                    return VimResult.ok(stats, stats.bytes);
                }
                if (stop.getAsBoolean()) return stopped(stats);
                String bad = first.invalidPropertyPath();
                if ("InvalidProperty".equals(first.faultClass()) && bad != null
                        && paths.containsKey(bad) && attempt < MAX_DROPS && stats.rows == 0) {
                    paths.remove(bad);
                    stats.dropped(bad);
                    continue;
                }
                return fail(first, stats);
            }
        } finally {
            // a read that ends with the server still holding its result set (a probe of one page, a failed
            // or stopped page) releases it; one that read to the end holds nothing
            if (stats.openToken != null) cancel(refs, stats.openToken);
            if (view != null) destroy(view, stats);
        }
    }

    private static VimResult<RetrieveStats> stopped(RetrieveStats stats) {
        return VimResult.fault("Stopped", "the read was stopped before it finished (the cycle deadline "
                + "passed or the cycle was ended)", null, stats.bytes, null);
    }

    /** Release the server's result set; best effort, said at INFO when it fails. */
    private void cancel(ServiceRefs refs, String token) {
        try {
            VimResult<XmlNode> r = vim.call("<CancelRetrievePropertiesEx xmlns=\"urn:vim25\"><_this type=\""
                    + VimSoapClient.xml(refs.propertyCollector.type) + "\">"
                    + VimSoapClient.xml(refs.propertyCollector.value) + "</_this><token>"
                    + VimSoapClient.xml(token) + "</token></CancelRetrievePropertiesEx>",
                    "CancelRetrievePropertiesEx", SHORT_READ_MS);
            if (!r.isOk()) {
                log.info("A property collector result set could not be released (" + r.faultClass()
                        + "); it expires with the session");
            }
        } catch (RuntimeException ignored) {
            // it expires with the session
        }
    }

    private static <T> VimResult<RetrieveStats> fail(VimResult<T> r, RetrieveStats stats) {
        return VimResult.fault(r.faultClass(), r.message(), r.invalidPropertyPath(), stats.bytes,
                r.cause());
    }

    // ------------------------------------------------------------------ one page

    /** One call, its objects to the sink; the value is the continuation token or null. */
    private VimResult<String> page(ServiceRefs refs, String body, String operation,
            Map<String, PathSpec> paths, RowSink sink, RetrieveStats stats) {
        String[] token = new String[1];
        stats.calls++;
        VimResult<Long> r = vim.stream(body, operation, PAGE_READ_MS, maxPageBytes, reader -> {
            readPage(reader, paths, sink, stats, token);
        });
        if (!r.isOk()) {
            return VimResult.fault(r.faultClass(), r.message(), r.invalidPropertyPath(), r.bytes(),
                    r.cause());
        }
        stats.pages++;
        stats.bytes += r.value();
        stats.openToken = token[0];
        return VimResult.ok(token[0], r.value());
    }

    private void readPage(XMLStreamReader r, Map<String, PathSpec> paths, RowSink sink,
            RetrieveStats stats, String[] token) throws XMLStreamException {
        java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                String parent = open.peek();
                if ("returnval".equals(parent) && "token".equals(n)) {
                    String t = r.getElementText().trim();
                    token[0] = t.isEmpty() ? null : t;
                    stats.openToken = token[0];
                    continue;
                }
                if ("returnval".equals(parent) && "objects".equals(n)) {
                    BulkRow row = readObject(r, paths, stats);
                    stats.rows++;
                    sink.row(row);
                    if (stop.getAsBoolean()) throw new Stopped();
                    continue;
                }
                open.push(n);
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                open.pop();
            }
        }
    }

    /** The {@code objects} element the reader is on; the reader ends on its end element. */
    private static BulkRow readObject(XMLStreamReader r, Map<String, PathSpec> paths,
            RetrieveStats stats) throws XMLStreamException {
        MoRef obj = null;
        int[] nodes = new int[1];
        Map<String, XmlNode> vals = new LinkedHashMap<>();
        List<String[]> miss = new ArrayList<>();
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "obj": {
                    String type = r.getAttributeValue(null, "type");
                    obj = new MoRef(type == null ? "" : type, r.getElementText().trim());
                    break;
                }
                case "propSet":
                    readPropSet(r, paths, nodes, vals);
                    break;
                case "missingSet": {
                    // path, then a fault whose inner element's xsi:type names what went wrong
                    String path = null;
                    String faultType = null;
                    int depth = 1;
                    while (depth > 0 && r.hasNext()) {
                        int e2 = r.next();
                        if (e2 == XMLStreamConstants.START_ELEMENT) {
                            depth++;
                            if ("path".equals(r.getLocalName())) {
                                path = r.getElementText().trim();
                                depth--;
                            } else if ("fault".equals(r.getLocalName())) {
                                String t = r.getAttributeValue("http://www.w3.org/2001/XMLSchema-instance", "type");
                                if (t != null && !t.equals("LocalizedMethodFault")) faultType = t;
                            }
                        } else if (e2 == XMLStreamConstants.END_ELEMENT) {
                            depth--;
                        }
                    }
                    if (path != null) miss.add(new String[] {path, faultType});
                    break;
                }
                default:
                    XmlNode.skipElement(r);
            }
        }
        if (obj == null) throw new XMLStreamException("an object with no reference");
        BulkRow row = new BulkRow(obj);
        for (Map.Entry<String, XmlNode> e : vals.entrySet()) row.put(e.getKey(), e.getValue());
        for (String[] m : miss) row.miss(m[0], m[1]);
        stats.totalNodes += nodes[0];
        if (nodes[0] > stats.maxRowNodes) stats.maxRowNodes = nodes[0];
        return row;
    }

    private static void readPropSet(XMLStreamReader r, Map<String, PathSpec> paths, int[] nodes,
            Map<String, XmlNode> vals) throws XMLStreamException {
        String name = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.END_ELEMENT) return;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            String n = r.getLocalName();
            if ("name".equals(n)) {
                name = r.getElementText().trim();
            } else if ("val".equals(n)) {
                PathSpec spec = name == null ? null : paths.get(name);
                XmlNode v = XmlNode.readElement(r, spec, nodes);
                if (name != null) vals.put(name, v);
            } else {
                XmlNode.skipElement(r);
            }
        }
    }

    // ------------------------------------------------------------------ bodies

    private VimResult<MoRef> createView(ServiceRefs refs, String type, RetrieveStats stats) {
        stats.calls++;
        VimResult<XmlNode> r = vim.call("<CreateContainerView xmlns=\"urn:vim25\">"
                + "<_this type=\"ViewManager\">" + VimSoapClient.xml(refs.viewManager.value) + "</_this>"
                + "<container type=\"" + VimSoapClient.xml(refs.rootFolder.type) + "\">"
                + VimSoapClient.xml(refs.rootFolder.value) + "</container>"
                + "<type>" + VimSoapClient.xml(type) + "</type><recursive>true</recursive>"
                + "</CreateContainerView>", "CreateContainerView", SHORT_READ_MS);
        if (!r.isOk()) {
            return VimResult.fault(r.faultClass(), r.message(), r.invalidPropertyPath(), r.bytes(),
                    r.cause());
        }
        XmlNode rv = r.value().find("returnval");
        if (rv == null || rv.text().isEmpty()) {
            return VimResult.fault("Parse", "the container view answer names no view", null,
                    r.bytes(), null);
        }
        return VimResult.ok(new MoRef(rv.attr("type") == null ? "ContainerView" : rv.attr("type"),
                rv.text()), r.bytes());
    }

    private void destroy(MoRef view, RetrieveStats stats) {
        try {
            stats.calls++;
            VimResult<XmlNode> r = vim.call("<DestroyView xmlns=\"urn:vim25\"><_this type=\""
                    + VimSoapClient.xml(view.type) + "\">" + VimSoapClient.xml(view.value)
                    + "</_this></DestroyView>", "DestroyView", SHORT_READ_MS);
            if (!r.isOk()) {
                log.info("A container view could not be released (" + r.faultClass()
                        + "); it expires with the session");
            }
        } catch (RuntimeException ignored) {
            // an unreleased view expires with the session
        }
    }

    static String buildRetrieve(ServiceRefs refs, RetrieveRequest req, MoRef view,
            Map<String, PathSpec> paths) {
        StringBuilder b = new StringBuilder(512);
        b.append("<RetrievePropertiesEx xmlns=\"urn:vim25\"><_this type=\"")
                .append(VimSoapClient.xml(refs.propertyCollector.type)).append("\">")
                .append(VimSoapClient.xml(refs.propertyCollector.value)).append("</_this><specSet>");
        b.append("<propSet><type>").append(VimSoapClient.xml(req.type)).append("</type><all>false</all>");
        b.append("<pathSet>name</pathSet>");
        for (String p : paths.keySet()) {
            if (!"name".equals(p)) b.append("<pathSet>").append(VimSoapClient.xml(p)).append("</pathSet>");
        }
        b.append("</propSet>");
        if (view != null) {
            b.append("<objectSet><obj type=\"").append(VimSoapClient.xml(view.type)).append("\">")
                    .append(VimSoapClient.xml(view.value)).append("</obj><skip>false</skip>"
                    + "<selectSet xsi:type=\"TraversalSpec\"><name>traverseEntities</name>"
                    + "<type>ContainerView</type><path>view</path><skip>false</skip></selectSet>"
                    + "</objectSet>");
        } else {
            for (MoRef o : req.objects) {
                b.append("<objectSet><obj type=\"").append(VimSoapClient.xml(o.type)).append("\">")
                        .append(VimSoapClient.xml(o.value)).append("</obj><skip>false</skip></objectSet>");
            }
        }
        b.append("</specSet><options><maxObjects>").append(req.maxObjects)
                .append("</maxObjects></options></RetrievePropertiesEx>");
        return b.toString();
    }

    static String buildContinue(ServiceRefs refs, String token) {
        return "<ContinueRetrievePropertiesEx xmlns=\"urn:vim25\"><_this type=\""
                + VimSoapClient.xml(refs.propertyCollector.type) + "\">"
                + VimSoapClient.xml(refs.propertyCollector.value) + "</_this><token>"
                + VimSoapClient.xml(token) + "</token></ContinueRetrievePropertiesEx>";
    }
}
