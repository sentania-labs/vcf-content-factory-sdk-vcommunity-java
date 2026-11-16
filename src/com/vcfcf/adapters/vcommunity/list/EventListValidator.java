package com.vcfcf.adapters.vcommunity.list;

import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLStreamException;

/**
 * Checks the Windows event list before it is handed to a guest (design 4.10):
 * well-formed, the root is {@code Events}, every child is a {@code Log} with a
 * non-empty {@code Name}, and every {@code EventID} under it is a whole
 * number. Anything else is a reported problem and the list is not used. An
 * {@code Events} root with no {@code Log} (the shipped reference file, every
 * entry commented out) is valid and empty.
 */
public final class EventListValidator {

    private EventListValidator() {}

    /** The outcome: {@code problem} is null when the list is usable. */
    public static final class Result {
        public final String problem;
        public final int logs;
        public final int eventIds;

        Result(String problem, int logs, int eventIds) {
            this.problem = problem;
            this.logs = logs;
            this.eventIds = eventIds;
        }

        public boolean ok() {
            return problem == null;
        }
    }

    public static Result validate(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return new Result("the file is empty", 0, 0);
        }
        XmlNode root;
        try {
            root = XmlNode.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (XMLStreamException | RuntimeException e) {
            return new Result("it is not well-formed XML", 0, 0);
        }
        if (!"Events".equals(root.name)) {
            return new Result("the root element is <" + root.name + ">, not <Events>", 0, 0);
        }
        int logs = 0;
        int ids = 0;
        for (XmlNode log : root.children()) {
            if (!"Log".equals(log.name)) {
                return new Result("<Events> holds <" + log.name + ">, only <Log> is allowed", logs, ids);
            }
            String name = log.attr("Name");
            if (name == null || name.trim().isEmpty()) {
                return new Result("a <Log> has no Name attribute", logs, ids);
            }
            logs++;
            for (XmlNode id : log.children()) {
                if (!"EventID".equals(id.name)) {
                    return new Result("<Log Name=\"" + CheckListParser.shownItem(name) + "\"> holds <"
                            + id.name + ">, only <EventID> is allowed", logs, ids);
                }
                if (!id.text().matches("[0-9]{1,10}")) {
                    return new Result("an <EventID> under Log \"" + CheckListParser.shownItem(name)
                            + "\" is not a whole number: '" + CheckListParser.shownItem(id.text())
                            + "'", logs, ids);
                }
                ids++;
            }
        }
        return new Result(null, logs, ids);
    }
}
