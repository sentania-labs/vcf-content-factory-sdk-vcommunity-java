package com.vcfcf.adapters.vcommunity.vim;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * What the P2 collectors need of a vCenter session beyond logging in: its
 * service references, one short method call, and one streamed call whose
 * answer is read element by element and never held whole. {@link
 * VimSoapClient} is the real one; tests use it against a local server.
 */
public interface VimReader {

    /** The service references of the current session, or null before a login. */
    ServiceRefs refs();

    /** Counts the logins this client has made; a change means the session is a new one. */
    default long generation() {
        return 0;
    }

    /** The vCenter's {@code about} of the current session, or null. */
    About about();

    /**
     * One authenticated call whose answer is small.
     *
     * @param readTimeoutMs how long to wait for the answer
     */
    VimResult<XmlNode> call(String bodyXml, String operation, int readTimeoutMs);

    /** Reads a streamed answer; it must read to the end of the document. */
    interface StreamHandler {
        void handle(XMLStreamReader r) throws XMLStreamException;
    }

    /**
     * One authenticated call whose answer is parsed as a stream.
     *
     * @param maxBytes the most bytes of answer read; more fails the call
     * @return the bytes read, or the fault
     */
    VimResult<Long> stream(String bodyXml, String operation, int readTimeoutMs, long maxBytes,
            StreamHandler handler);
}
