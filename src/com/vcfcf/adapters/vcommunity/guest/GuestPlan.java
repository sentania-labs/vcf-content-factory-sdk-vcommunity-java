package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.VCommunityConfig;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.EventListValidator;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.list.ServiceNameFilter;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLStreamException;

/**
 * What the guest phase is to do this cycle, from the account and the two lists (design 8.1). With both
 * switches off the plan is empty and no guest operation of any kind is made. It also says why the phase is off
 * when a prerequisite is missing: no Windows account (DV-44) or a guest TLS mode that is not one of the three
 * (DV-29).
 */
public final class GuestPlan {

    /** Why the phase does nothing. */
    public enum Off {
        /** Both switches are Disabled. */
        SWITCHED_OFF,
        /** A switch is Enabled but the account has no Windows user name or password. */
        NO_CREDENTIAL,
        /** The Guest File Transfer TLS Validation field holds a value that is not one of the three. */
        BAD_TLS_MODE,
        /** The vCenter connection's certificate is not checked (Allow Insecure SSL) and the Windows account would cross it. */
        VCENTER_TLS,
        /** Event monitoring is the only switch on and the event list has nothing to look for. */
        NOTHING_TO_DO
    }

    /** One log and event id to look for. */
    public static final class Query {
        public final String log;
        public final String id;

        Query(String log, String id) {
            this.log = log;
            this.id = id;
        }
    }

    public static final int MAX_QUERIES = 500;

    /** Null when the phase runs. */
    public final Off off;
    public final String message;
    public final boolean services;
    public final boolean operatingSystem;
    public final boolean events;
    public final List<String> serviceNames;
    public final List<Query> queries;
    /** Event list entries left out (a log name the guest file cannot carry, or more than {@value #MAX_QUERIES}). */
    public final int queriesSkipped;

    private GuestPlan(Off off, String message, boolean services, boolean os, boolean events, List<String> names,
            List<Query> queries, int skipped) {
        this.off = off;
        this.message = message;
        this.services = services;
        this.operatingSystem = os;
        this.events = events;
        this.serviceNames = Collections.unmodifiableList(names);
        this.queries = Collections.unmodifiableList(queries);
        this.queriesSkipped = skipped;
    }

    private static GuestPlan off(Off why, String message) {
        return new GuestPlan(why, message, false, false, false, new ArrayList<>(), new ArrayList<>(), 0);
    }

    public static GuestPlan of(VCommunityConfig cfg, Map<ListKind, CheckListStore.Entry> lists) {
        if (!cfg.serviceMonitoring && !cfg.eventMonitoring) {
            return off(Off.SWITCHED_OFF, "guest monitoring is switched off");
        }
        if (cfg.guestTlsError != null) {
            return off(Off.BAD_TLS_MODE, cfg.guestTlsError + "; guest operations stay off until it is fixed");
        }
        // the Windows account goes through the vCenter connection: never to one whose certificate is not checked (DV-103)
        if (cfg.allowInsecure) {
            return off(Off.VCENTER_TLS, "guest monitoring is enabled but Allow Insecure SSL is on, so the Windows "
                    + "account would be sent to a vCenter whose certificate is not checked; turn Allow Insecure SSL "
                    + "off (and accept the vCenter certificate in Validate Connection) to use guest monitoring");
        }
        if (!cfg.hasWindowsCredential()) {
            return off(Off.NO_CREDENTIAL, "guest monitoring is enabled but the account has no Windows user name "
                    + "and password; no guest operation is made");
        }
        List<String> names = new ArrayList<>();
        String eventXml = null;
        if (cfg.serviceMonitoring) {
            CheckListStore.Entry e = lists.get(ListKind.WIN_SERVICES);
            if (e != null && e.outcome != CheckListStore.Outcome.NOT_CONFIGURED) names.addAll(e.items);
        }
        if (cfg.eventMonitoring) {
            CheckListStore.Entry e = lists.get(ListKind.WIN_EVENTS);
            if (e != null) eventXml = e.rawXml;
        }
        return create(cfg.serviceMonitoring, names, cfg.eventMonitoring, eventXml);
    }

    /**
     * The plan for switches that are on and an account that is set (the prerequisites {@link #of} checks).
     *
     * @param names    the service list in force (names the guest may be given are kept, the rest dropped)
     * @param eventXml the event list in force, or null when there is none
     */
    public static GuestPlan create(boolean serviceMonitoring, List<String> serviceList, boolean eventMonitoring,
            String eventXml) {
        List<String> names = new ArrayList<>();
        for (String n : serviceList) {
            if (ServiceNameFilter.isSafe(n)) names.add(n);
        }
        List<Query> queries = new ArrayList<>();
        int skipped = 0;
        boolean events = false;
        if (eventMonitoring && eventXml != null && EventListValidator.validate(eventXml).ok()) {
            int[] counts = new int[1];
            queries.addAll(queriesOf(eventXml, counts));
            skipped = counts[0];
            events = !queries.isEmpty();
        }
        if (!serviceMonitoring && !events) {
            return off(Off.NOTHING_TO_DO, "Windows event monitoring is enabled but the event list has nothing to look for");
        }
        return new GuestPlan(null, null, serviceMonitoring && !names.isEmpty(), serviceMonitoring, events,
                names, queries, skipped);
    }

    /** The (log, event id) pairs of a validated list; a name the line file cannot carry is left out and counted. */
    static List<Query> queriesOf(String xml, int[] skipped) {
        List<Query> out = new ArrayList<>();
        XmlNode root;
        try {
            root = XmlNode.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (XMLStreamException | RuntimeException e) {
            return out;
        }
        for (XmlNode log : root.children()) {
            String name = log.attr("Name");
            for (XmlNode id : log.children()) {
                boolean fits = name != null && !name.isEmpty() && name.length() <= 256 && GuestText.isSafeValue(name)
                        && name.indexOf('\t') < 0 && name.equals(name.trim());
                if (!fits || out.size() >= MAX_QUERIES) {
                    skipped[0]++;
                    continue;
                }
                out.add(new Query(name, id.text()));
            }
        }
        return out;
    }

    /** True when the phase makes guest operations. */
    public boolean runs() {
        return off == null;
    }

    /** The service names as the guest file holds them: one per line, UTF-8, no byte order mark. */
    public byte[] serviceFile() {
        return String.join("\n", serviceNames).concat("\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The event list as the guest file holds it: {@code log TAB id} per line. */
    public byte[] eventFile() {
        StringBuilder b = new StringBuilder();
        for (Query q : queries) b.append(q.log).append('\t').append(q.id).append('\n');
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }
}
