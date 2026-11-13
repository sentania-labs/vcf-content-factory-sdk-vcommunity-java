package com.vcfcf.adapters.vcommunity.list;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the check-list files from the VCF Operations configuration-file
 * store ({@code GET /api/configurations/files?path=SolutionConfig/<name>.xml})
 * and keeps what is in force (design 4.10). One entry per file per refresh:
 *
 * <ul>
 *   <li>{@code OK}: read and parsed; the items are in force.</li>
 *   <li>{@code ABSENT}: HTTP 404, or HTTP 400 with apiErrorCode 1501 (the store's
 *       answer for a missing file); the list is empty, the last good one dropped.</li>
 *   <li>{@code FETCH_FAILED}: any other read error; the last good list stays in force.</li>
 *   <li>{@code PARSE_FAILED}: not well-formed (or, for the event list, not a valid
 *       event structure); the last good list stays in force (DV-27).</li>
 *   <li>{@code NOT_CONFIGURED}: the account's field is blank; nothing is read.</li>
 *   <li>{@code INVALID_NAME}: the field is not a plain file name; nothing is read.</li>
 * </ul>
 * The last good list is remembered per list and file name, in memory: a start
 * loses it, and a fetch failure on the first cycle after a start leaves that
 * list empty and says so. SDK-free; the fetch is a {@link Fetcher}.
 */
public final class CheckListStore {

    /** Reads one store path; throws {@link OpsHttpException} for a non-2xx answer. */
    public interface Fetcher {
        String fetch(String path) throws IOException, InterruptedException;
    }

    public enum Outcome { OK, ABSENT, FETCH_FAILED, PARSE_FAILED, NOT_CONFIGURED, INVALID_NAME }

    /** What one file gave this cycle. */
    public static final class Entry {
        public final ListKind kind;
        public final String fileName;
        public final Outcome outcome;
        /** The items in force after this refresh (this read's, or the last good list's). */
        public final List<String> items;
        /** The Windows event list text in force (event list only), else null. */
        public final String rawXml;
        /** True when {@link #items} are the last good list, not this read's. */
        public final boolean lastGood;
        /** Entries skipped this read, each with its reason. */
        public final List<String> skipped;
        /** A sentence naming the file and what is wrong, or null when all is well. */
        public final String problem;
        /** For the event list: logs and event ids found. */
        public final int logs;
        public final int eventIds;

        Entry(ListKind kind, String fileName, Outcome outcome, List<String> items, String rawXml,
                boolean lastGood, List<String> skipped, String problem, int logs, int eventIds) {
            this.kind = kind;
            this.fileName = fileName;
            this.outcome = outcome;
            this.items = Collections.unmodifiableList(items);
            this.rawXml = rawXml;
            this.lastGood = lastGood;
            this.skipped = Collections.unmodifiableList(skipped);
            this.problem = problem;
            this.logs = logs;
            this.eventIds = eventIds;
        }

        public boolean ok() {
            return outcome == Outcome.OK && skipped.isEmpty();
        }

        /** The count Test Connection shows: items, or logs and event ids for the event list. */
        public String countText() {
            if (kind.isEventList()) {
                return logs + " log(s), " + eventIds + " event id(s)";
            }
            return items.size() + " item(s)";
        }

        /** One line for Test Connection and the log. */
        public String summary() {
            StringBuilder b = new StringBuilder();
            b.append(kind.title).append(" '").append(fileName).append("': ").append(outcome);
            if (outcome == Outcome.OK || lastGood) {
                b.append(", ").append(countText());
                if (lastGood) b.append(" (last good list kept)");
            }
            if (!skipped.isEmpty()) b.append(", ").append(skipped.size()).append(" skipped");
            return b.toString();
        }
    }

    private final Map<String, List<String>> lastGoodItems = new HashMap<>();
    private final Map<String, String> lastGoodRaw = new HashMap<>();
    private final Map<String, int[]> lastGoodCounts = new HashMap<>();
    private final Set<String> logged = new HashSet<>();
    private final Log log;

    public CheckListStore(Log log) {
        this.log = log == null ? Log.NONE : log;
    }

    private static String key(ListKind k, String name) {
        return k.id + "\u0000" + name;
    }

    /**
     * Read the given files. {@code files} maps each list to read to the file
     * name the account holds for it; a list the caller does not name is not
     * read (its gate is off).
     *
     * @throws InterruptedException the thread was interrupted; nothing is changed
     */
    public synchronized Map<ListKind, Entry> refresh(Map<ListKind, String> files, Fetcher fetcher)
            throws InterruptedException {
        Map<ListKind, Entry> out = new EnumMap<>(ListKind.class);
        for (Map.Entry<ListKind, String> f : files.entrySet()) {
            out.put(f.getKey(), one(f.getKey(), f.getValue() == null ? "" : f.getValue().trim(),
                    fetcher));
        }
        return out;
    }

    private Entry one(ListKind kind, String name, Fetcher fetcher) throws InterruptedException {
        if (name.isEmpty()) {
            return new Entry(kind, name, Outcome.NOT_CONFIGURED, new ArrayList<>(), null, false,
                    new ArrayList<>(), null, 0, 0);
        }
        if (!isPlainName(name)) {
            String p = "The file name set on the account for " + kind.title + " ('"
                    + CheckListParser.shownItem(name) + "') is not a plain file name: use the "
                    + "name without a path or .xml.";
            return new Entry(kind, name, Outcome.INVALID_NAME, new ArrayList<>(), null, false,
                    new ArrayList<>(), p, 0, 0);
        }
        String body;
        try {
            body = fetcher.fetch(path(name));
        } catch (OpsHttpException e) {
            if (isAbsent(e)) {
                forget(kind, name);
                return new Entry(kind, name, Outcome.ABSENT, new ArrayList<>(), null, false,
                        new ArrayList<>(), "Configuration file '" + name + ".xml' does not exist in "
                        + "VCF Operations, so its list is empty.", 0, 0);
            }
            // The answer's own words are kept, bounded: an HTTP 400 with no apiErrorCode (seen on
            // 8.18.7) cannot be told from a missing file without them.
            String said = e.bodyExcerpt().isEmpty() ? "" : "; it said: "
                    + (e.bodyExcerpt().length() > 120 ? e.bodyExcerpt().substring(0, 120) + "..."
                            : e.bodyExcerpt());
            return failed(kind, name, Outcome.FETCH_FAILED, "VCF Operations answered HTTP "
                    + e.status() + (e.apiErrorCode() == null ? "" : " (error " + e.apiErrorCode() + ")")
                    + said);
        } catch (InterruptedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return failed(kind, name, Outcome.FETCH_FAILED, e.getClass().getSimpleName()
                    + " reading it");
        }
        return parsed(kind, name, body);
    }

    private Entry parsed(ListKind kind, String name, String body) {
        List<String> skipped = new ArrayList<>();
        if (kind.isEventList()) {
            EventListValidator.Result r = EventListValidator.validate(body);
            if (!r.ok()) {
                return failed(kind, name, Outcome.PARSE_FAILED, r.problem);
            }
            lastGoodRaw.put(key(kind, name), body);
            lastGoodCounts.put(key(kind, name), new int[] {r.logs, r.eventIds});
            return new Entry(kind, name, Outcome.OK, new ArrayList<>(), body, false, skipped, null,
                    r.logs, r.eventIds);
        }
        CheckListParser.Parsed p;
        try {
            p = CheckListParser.parse(body);
        } catch (CheckListParser.ParseFailure e) {
            return failed(kind, name, Outcome.PARSE_FAILED, "it is not valid XML: " + e.getMessage());
        }
        List<String> items = new ArrayList<>();
        skipped.addAll(p.skipped);
        for (String item : p.items) {
            String why = reject(kind, item);
            if (why != null) {
                skipped.add(why + ": '" + CheckListParser.shownItem(item) + "'");
            } else {
                items.add(item);
                if ((item.indexOf('|') >= 0 || item.indexOf(':') >= 0)
                        && logged.add(kind.id + "\u0000" + item)) {
                    log.info(kind.title + " item '" + CheckListParser.shownItem(item)
                            + "' contains '|' or ':'; it is published under exactly that key");
                }
            }
        }
        lastGoodItems.put(key(kind, name), new ArrayList<>(items));
        String problem = null;
        if (!skipped.isEmpty()) {
            problem = skipped.size() + " entr" + (skipped.size() == 1 ? "y" : "ies")
                    + " of '" + name + ".xml' skipped: " + skipped.get(0)
                    + (skipped.size() > 1 ? " (and " + (skipped.size() - 1) + " more)" : "");
        }
        return new Entry(kind, name, Outcome.OK, items, null, false, skipped, problem, 0, 0);
    }

    private Entry failed(ListKind kind, String name, Outcome outcome, String reason) {
        String k = key(kind, name);
        List<String> keptItems = lastGoodItems.get(k);
        String keptRaw = lastGoodRaw.get(k);
        boolean kept = kind.isEventList() ? keptRaw != null : keptItems != null;
        String what = outcome == Outcome.PARSE_FAILED ? "is not usable" : "could not be fetched";
        String problem = "Configuration file '" + name + ".xml' " + what + " (" + reason + "). "
                + (kept ? "The last good list stays in use." : "No list is in use for it yet.");
        int[] counts = lastGoodCounts.get(k);
        return new Entry(kind, name, outcome,
                kept && !kind.isEventList() ? new ArrayList<>(keptItems) : new ArrayList<>(),
                kept && kind.isEventList() ? keptRaw : null, kept, new ArrayList<>(), problem,
                counts == null ? 0 : counts[0], counts == null ? 0 : counts[1]);
    }

    private void forget(ListKind kind, String name) {
        String k = key(kind, name);
        lastGoodItems.remove(k);
        lastGoodRaw.remove(k);
        lastGoodCounts.remove(k);
    }

    /** Why an item is not usable for this list, or null. */
    static String reject(ListKind kind, String item) {
        switch (kind) {
            case VM_OPTIONS:
                return VmOptionPath.isValid(item) ? null
                        : "not a property path (letters and digits in segments separated by dots)";
            case WIN_SERVICES:
                return ServiceNameFilter.isSafe(item) ? null
                        : "a service name may hold only letters, digits, space and _ . $ # @ ( ) + -";
            default:
                return null;
        }
    }

    /**
     * A file name and not a path or a query: no slash, backslash, question mark
     * or control character, and not {@code .} or {@code ..} on their own. Other
     * characters are fine, the name is percent-encoded when it is sent.
     */
    static boolean isPlainName(String name) {
        if (name.length() > 200 || name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || c == '/' || c == '\\' || c == '?') return false;
        }
        return true;
    }

    /** HTTP 404, or HTTP 400 with apiErrorCode 1501: the store's answers for a missing file. */
    static boolean isAbsent(OpsHttpException e) {
        if (e.status() == 404) return true;
        return e.status() == 400 && e.apiErrorCode() != null && e.apiErrorCode() == 1501;
    }

    static String path(String name) {
        try {
            return "/api/configurations/files?path=SolutionConfig/"
                    + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + ".xml";
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
