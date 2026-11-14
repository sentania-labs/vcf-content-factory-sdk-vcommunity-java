package com.vcfcf.adapters.vcommunity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A vCenter that answers the property collector from stored objects: it
 * returns only the paths a request asks for, pages by {@code maxObjects} with
 * continuation tokens, refuses paths it is told to refuse with an
 * InvalidProperty fault, and answers the method calls the property phase makes.
 * Served through {@link FakeVc}, so the real client, TLS and stream parser run.
 */
final class FakeVcenter implements FakeVc.Handler {

    final Map<String, List<SoapFx.Obj>> byType = new LinkedHashMap<>();
    /** Paths vCenter refuses as invalid properties. */
    final Set<String> invalid = new HashSet<>();
    /** Whole-answer overrides by SOAP action (a forced fault or any other answer). */
    final Map<String, FakeVc.Resp> forced = new HashMap<>();
    /** Per object type: answer a RetrievePropertiesEx of that type with this instead. */
    final Map<String, FakeVc.Resp> forcedType = new HashMap<>();
    /** Like {@link #forcedPath} but only for a read of more than one object (not the one-object probe). */
    final Map<String, FakeVc.Resp> forcedPathMain = new HashMap<>();
    /** QueryAssignedLicenses with no entityId fails; with one it answers that entity's assignments. */
    volatile boolean licensesNeedEntity;
    /** Answer a retrieve that asks for this path with this instead. */
    final Map<String, FakeVc.Resp> forcedPath = new HashMap<>();
    /** Per object type: fail the Nth continuation (1-based) of that type with a server fault. */
    final Map<String, Integer> failContinueAt = new HashMap<>();
    private final Map<String, AtomicInteger> continuesByType = new HashMap<>();
    /** Wait this long before answering: key is an action, or {@code RetrievePropertiesEx:<type>}. */
    final Map<String, Integer> delayMs = new HashMap<>();
    /** Rewrite the finished answer of a retrieve of this type (for shapes the builder does not make). */
    final Map<String, java.util.function.UnaryOperator<String>> rewrite = new HashMap<>();
    /** Image config manager value to its install date ({@code null} entry: answers nothing; absent: fault). */
    final Map<String, String> installDate = new HashMap<>();
    final Map<String, String> packages = new HashMap<>();
    String licenses = "";
    String options = "";
    String patchLevel = "8.0.3.00200";

    final AtomicInteger pages = new AtomicInteger();
    final AtomicInteger views = new AtomicInteger();
    final AtomicInteger destroyed = new AtomicInteger();
    final AtomicInteger continues = new AtomicInteger();
    private final AtomicInteger tokenSeq = new AtomicInteger();
    private final Map<String, Page> tokens = new HashMap<>();

    private static final class Page {
        final List<SoapFx.Obj> list;
        final int next;
        final Set<String> asked;
        final int max;
        final String type;

        Page(List<SoapFx.Obj> list, int next, Set<String> asked, int max, String type) {
            this.list = list;
            this.next = next;
            this.asked = asked;
            this.max = max;
            this.type = type;
        }
    }

    FakeVcenter() {
        add(new SoapFx.Obj("LicenseManager", "LicenseManager")
                .put("licenseAssignmentManager", SoapFx.moref("LicenseAssignmentManager", "LicenseAssignmentManager")));
    }

    /** Start counting continuations again (for {@link #failContinueAt}). */
    void resetContinues() {
        synchronized (continuesByType) {
            continuesByType.clear();
        }
    }

    FakeVcenter add(SoapFx.Obj o) {
        byType.computeIfAbsent(o.type, k -> new ArrayList<>()).add(o);
        return this;
    }

    static FakeVc.Resp invalidProperty(String path) {
        return new FakeVc.Resp(500, null, SoapFx.envelope("<soapenv:Fault><faultcode>ServerFaultCode</faultcode>"
                + "<faultstring>The property " + path + " is not valid</faultstring><detail>"
                + "<InvalidPropertyFault xmlns=\"urn:vim25\" xsi:type=\"InvalidProperty\"><name>" + path
                + "</name></InvalidPropertyFault></detail></soapenv:Fault>"));
    }

    static FakeVc.Resp serverFault(String text) {
        return new FakeVc.Resp(500, null, SoapFx.envelope("<soapenv:Fault><faultcode>ServerFaultCode</faultcode>"
                + "<faultstring>" + text + "</faultstring><detail><RuntimeFault xmlns=\"urn:vim25\" "
                + "xsi:type=\"RuntimeFault\"/></detail></soapenv:Fault>"));
    }

    private static FakeVc.Resp ok(String inner) {
        return new FakeVc.Resp(200, null, SoapFx.envelope(inner));
    }

    private static String first(String body, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public FakeVc.Resp handle(FakeVc.Req r) {
        FakeVc.Resp f = forced.get(r.action);
        if (f != null) return f;
        Integer d = delayMs.get(r.action);
        if (d == null && r.action.equals("RetrievePropertiesEx")) {
            String t = first(r.body, "<propSet><type>(\\w+)</type>");
            d = delayMs.get("RetrievePropertiesEx:" + t);
        }
        if (d != null) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        switch (r.action) {
            case "RetrieveServiceContent": return serviceContent();
            case "Login": return FakeVc.loginOk();
            case "CurrentTime": return FakeVc.currentTime();
            case "CreateContainerView": {
                views.incrementAndGet();
                String type = first(r.body, "<type>(\\w+)</type>");
                return ok("<CreateContainerViewResponse xmlns=\"urn:vim25\"><returnval type=\"ContainerView\">"
                        + "view-" + type + "</returnval></CreateContainerViewResponse>");
            }
            case "DestroyView":
                destroyed.incrementAndGet();
                return ok("<DestroyViewResponse xmlns=\"urn:vim25\"/>");
            case "RetrievePropertiesEx": return retrieve(r.body);
            case "ContinueRetrievePropertiesEx": return cont(r.body);
            // the operation is named installDate on the wire; the capitalised name is refused, as vCenter does
            case "installDate": return installDate(r.body);
            case "InstallDate": return serverFault("The method InstallDate is not found");
            // the operation is named fetchSoftwarePackages on the wire; the capitalised name is refused
            case "FetchSoftwarePackages": return serverFault("The method FetchSoftwarePackages is not found");
            case "fetchSoftwarePackages": {
                String v = first(r.body, "<_this[^>]*>([^<]*)</_this>");
                String p = packages.get(v);
                if (p == null) return serverFault("no packages for " + v);
                return ok("<fetchSoftwarePackagesResponse xmlns=\"urn:vim25\">" + p + "</fetchSoftwarePackagesResponse>");
            }
            case "QueryAssignedLicenses": {
                String entity = first(r.body, "<entityId>([^<]*)</entityId>");
                if (licensesNeedEntity && entity == null) return serverFault("too many assignments");
                String body = licenses;
                if (entity != null) {
                    StringBuilder b = new StringBuilder();
                    Matcher m = Pattern.compile("<returnval>.*?</returnval>", Pattern.DOTALL).matcher(licenses);
                    while (m.find()) {
                        if (m.group().contains("<entityId>" + entity + "</entityId>")) b.append(m.group());
                    }
                    body = b.toString();
                }
                return ok("<QueryAssignedLicensesResponse xmlns=\"urn:vim25\">" + body
                        + "</QueryAssignedLicensesResponse>");
            }
            case "QueryOptions":
                return ok("<QueryOptionsResponse xmlns=\"urn:vim25\">" + options + "</QueryOptionsResponse>");
            default:
                return ok("<" + r.action + "Response xmlns=\"urn:vim25\"/>");
        }
    }

    private FakeVc.Resp serviceContent() {
        return ok("<RetrieveServiceContentResponse xmlns=\"urn:vim25\"><returnval>"
                + "<rootFolder type=\"Folder\">group-d1</rootFolder>"
                + "<propertyCollector type=\"PropertyCollector\">propertyCollector</propertyCollector>"
                + "<viewManager type=\"ViewManager\">ViewManager</viewManager>"
                + "<setting type=\"OptionManager\">VpxSettings</setting>"
                + "<about><name>VMware vCenter Server</name>"
                + "<fullName>VMware vCenter Server 8.0.3 build-24022515</fullName>"
                + "<version>8.0.3</version><build>24022515</build><apiVersion>8.0.3.0</apiVersion>"
                + "<instanceUuid>" + FakeVc.INSTANCE_UUID + "</instanceUuid>"
                + (patchLevel == null ? "" : "<patchLevel>" + patchLevel + "</patchLevel>") + "</about>"
                + "<licenseManager type=\"LicenseManager\">LicenseManager</licenseManager>"
                + "<sessionManager type=\"SessionManager\">SessionManager</sessionManager>"
                + "</returnval></RetrieveServiceContentResponse>");
    }

    private FakeVc.Resp installDate(String body) {
        String v = first(body, "<_this[^>]*>([^<]*)</_this>");
        if (!installDate.containsKey(v)) return serverFault("no install date for " + v);
        String d = installDate.get(v);
        return ok("<installDateResponse xmlns=\"urn:vim25\">"
                + (d == null ? "" : "<returnval>" + d + "</returnval>") + "</installDateResponse>");
    }

    private FakeVc.Resp retrieve(String body) {
        Set<String> asked = new HashSet<>();
        Matcher pm = Pattern.compile("<pathSet>([^<]*)</pathSet>").matcher(body);
        while (pm.find()) asked.add(pm.group(1));
        for (String bad : invalid) {
            if (asked.contains(bad)) return invalidProperty(bad);
        }
        for (Map.Entry<String, FakeVc.Resp> fp : forcedPath.entrySet()) {
            if (asked.contains(fp.getKey())) return fp.getValue();
        }
        if (!body.contains("<maxObjects>1</maxObjects>")) {
            for (Map.Entry<String, FakeVc.Resp> fp : forcedPathMain.entrySet()) {
                if (asked.contains(fp.getKey())) return fp.getValue();
            }
        }
        String type = first(body, "<propSet><type>(\\w+)</type>");
        // the license manager is not a managed entity: it has no name property, and asking for it fails the read
        if ("LicenseManager".equals(type) && asked.contains("name")) return invalidProperty("name");
        FakeVc.Resp ft = forcedType.get(type);
        if (ft != null) return ft;
        int max = Integer.parseInt(first(body, "<maxObjects>(\\d+)</maxObjects>"));
        List<SoapFx.Obj> all = byType.getOrDefault(type, new ArrayList<>());
        List<SoapFx.Obj> list = all;
        if (!body.contains("<obj type=\"ContainerView\"")) {
            list = new ArrayList<>();
            Matcher om = Pattern.compile("<objectSet><obj type=\"" + type + "\">([^<]*)</obj>").matcher(body);
            while (om.find()) {
                for (SoapFx.Obj o : all) if (o.moid.equals(om.group(1))) list.add(o);
            }
        }
        pages.incrementAndGet();
        return page(new Page(list, 0, asked, max, type));
    }

    private FakeVc.Resp cont(String body) {
        continues.incrementAndGet();
        String t = first(body, "<token>([^<]*)</token>");
        Page p;
        synchronized (tokens) {
            p = tokens.remove(t);
        }
        if (p == null) return serverFault("unknown token");
        int n;
        synchronized (continuesByType) {
            n = continuesByType.computeIfAbsent(p.type, k -> new AtomicInteger()).incrementAndGet();
        }
        Integer at = failContinueAt.get(p.type);
        if (at != null && at == n) return serverFault("the page could not be produced");
        pages.incrementAndGet();
        return page(p);
    }

    private FakeVc.Resp page(Page p) {
        if (p.list.isEmpty()) return ok("<RetrievePropertiesExResponse xmlns=\"urn:vim25\"/>");
        int end = Math.min(p.list.size(), p.next + p.max);
        StringBuilder b = new StringBuilder("<RetrievePropertiesExResponse xmlns=\"urn:vim25\"><returnval>");
        if (end < p.list.size()) {
            String t = "tok-" + tokenSeq.incrementAndGet();
            synchronized (tokens) {
                tokens.put(t, new Page(p.list, end, p.asked, p.max, p.type));
            }
            b.append("<token>").append(t).append("</token>");
        }
        for (int i = p.next; i < end; i++) b.append(p.list.get(i).render(p.asked));
        b.append("</returnval></RetrievePropertiesExResponse>");
        java.util.function.UnaryOperator<String> rw = rewrite.get(p.type);
        return ok(rw == null ? b.toString() : rw.apply(b.toString()));
    }
}
