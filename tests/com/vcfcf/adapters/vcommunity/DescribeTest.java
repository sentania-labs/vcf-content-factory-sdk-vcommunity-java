package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.health.CollectionHealth;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * describe.xml against the original pack's account (field for field, except that the vCenter TLS pair is
 * replaced by Allow Insecure SSL as in the other paks), the
 * config class's defaults, the self metrics the cycle pushes, and the own
 * kinds against the key map. Run from the repository root.
 */
public final class DescribeTest {

    /** One expected account field of the original pack 0.8.1. */
    static final class F {
        final String key, label, type, dflt;
        final boolean required, advanced;
        final String[] enums;

        F(String key, String label, String type, boolean required, String dflt, String[] enums, boolean advanced) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.required = required;
            this.dflt = dflt;
            this.enums = enums;
            this.advanced = advanced;
        }
    }

    static final F[] FIELDS = {
        new F("host", "vCenter Server", "string", true, null, null, false),
        new F("esxi_adv_settings_config_file", "ESXi Advanced System Settings Config File", "string", false, "esxi_advanced_system_settings", null, false),
        new F("esxi_vib_driver_config_file", "ESXi Software Packages Config File", "string", false, "esxi_packages", null, false),
        new F("vm_adv_settings_config_file", "VM Advanced Parameters Config File", "string", false, "vm_advanced_parameters", null, false),
        new F("vm_configuration_config_file", "VM Options Config File", "string", false, "vm_options", null, false),
        new F("port", "Port", "integer", true, "443", null, true),
        new F("allowInsecure", "Allow Insecure SSL", "string", false, "false", null, true),
        new F("cluster_check_period_hours", "Cluster Check Period (hours)", "string", false, "24",
                new String[] {"01", "02", "04", "06", "08", "12", "24"}, true),
        new F("periodic_cluster_check_time", "Cluster Check Time (UTC)", "string", false, "00:00", null, true),
        new F("cluster_check_worst_percent", "Cluster Check Worst Percent", "integer", false, "5", null, true),
        new F("monster_vm_cpu_percent_of_cores", "Monster VM CPU (% of host cores)", "integer", false, "50", null, true),
        new F("monster_vm_memory_percent_of_dram", "Monster VM Memory (% of host DRAM)", "integer", false, "40", null, true),
        new F("monster_vm_memory_min_gb", "Monster VM Memory Minimum (GB)", "integer", false, "256", null, true),
        new F("win_service_config_file", "Windows Service Configuration File", "string", false, "windows_service_list", null, true),
        new F("win_event_config_file", "Windows Event Log Configuration File", "string", false, "windows_event_list", null, true),
        new F("serviceMonitoring", "Guest OS Service Monitoring Status", "string", false, "Disabled", new String[] {"Enabled", "Disabled"}, true),
        new F("guest_tls_validation", "Guest File Transfer TLS Validation", "string", false, "Pinned",
                new String[] {"Pinned", "Strict", "Disabled"}, true),
        new F("winEventMonitoring", "Windows Event Log Monitoring Status", "string", false, "Disabled", new String[] {"Enabled", "Disabled"}, true),
    };

    static Map<Integer, String> labels = new HashMap<>();
    static Map<Integer, String> descriptions = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream("resources/resources.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String k : p.stringPropertyNames()) {
            if (k.endsWith(".description")) {
                descriptions.put(Integer.valueOf(k.substring(0, k.length() - ".description".length())), p.getProperty(k));
            } else {
                labels.put(Integer.valueOf(k), p.getProperty(k));
            }
        }

        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new File("describe.xml"));
        Element root = doc.getDocumentElement();
        T.eq("vcfcf_vcommunity", root.getAttribute("key"), "adapter kind");
        T.eq("VCF Content Factory vCommunity", label(root), "adapter display name");
        T.eq("VCF Content Factory vCommunity", read("adapter.yaml", "name: "), "adapter.yaml name");
        T.eq("vcfcf_vcommunity", read("adapter.yaml", "adapter_kind: "), "adapter.yaml kind");

        instanceFields(root);
        credential(root);
        selfMetrics(root);
        ownKinds(root);
        nameKeys(doc);
        defaultsMatchConfig(root);
    }

    static String read(String file, String prefix) throws Exception {
        for (String l : Files.readAllLines(new File(file).toPath(), StandardCharsets.UTF_8)) {
            if (l.startsWith(prefix)) return l.substring(prefix.length()).trim();
        }
        throw new AssertionError("no " + prefix + " in " + file);
    }

    static String label(Element e) {
        String nk = e.getAttribute("nameKey");
        T.check(!nk.isEmpty(), "nameKey on " + e.getTagName() + " " + e.getAttribute("key"));
        String l = labels.get(Integer.valueOf(nk));
        T.check(l != null, "nameKey " + nk + " has a label");
        return l;
    }

    static List<Element> kids(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) out.add((Element) n);
        }
        return out;
    }

    static Element kind(Element root, String key) {
        for (Element rk : kids(kids(root, "ResourceKinds").get(0), "ResourceKind")) {
            if (rk.getAttribute("key").equals(key)) return rk;
        }
        throw new AssertionError("no ResourceKind " + key);
    }

    static void instanceFields(Element root) {
        Element inst = kind(root, "vcfcf_vcommunity");
        T.eq("7", inst.getAttribute("type"), "adapter instance kind type");
        T.eq("vsphere_user", inst.getAttribute("credentialKind"), "credential kind");
        T.eq("5", inst.getAttribute("monitoringInterval"), "five minute default");
        List<Element> ids = kids(inst, "ResourceIdentifier");
        T.eq(18, ids.size(), "18 account fields");
        T.eq(18, FIELDS.length, "18 expected");
        int lastOrder = 0;
        for (int i = 0; i < FIELDS.length; i++) {
            F want = FIELDS[i];
            Element got = ids.get(i);
            String at = "field " + (i + 1) + " " + want.key;
            T.eq(want.key, got.getAttribute("key"), at + " key (order)");
            T.eq(want.label, label(got), at + " label");
            T.eq(want.type, got.getAttribute("type"), at + " type");
            T.eq(want.required ? "true" : "false", got.getAttribute("required"), at + " required");
            if (want.dflt == null) {
                T.check(!got.hasAttribute("default"), at + " has no default");
            } else {
                T.eq(want.dflt, got.getAttribute("default"), at + " default");
            }
            // main form versus Advanced Settings: identType 2 is advanced, absent is the main form
            if (want.advanced) T.eq("2", got.getAttribute("identType"), at + " advanced");
            else T.check(!got.hasAttribute("identType"), at + " main form (no identType)");
            int order = Integer.parseInt(got.getAttribute("dispOrder"));
            T.check(order > lastOrder, at + " display order increases");
            lastOrder = order;
            List<Element> enums = kids(got, "enum");
            if (want.enums == null) {
                T.check(enums.isEmpty() && !got.hasAttribute("enum"), at + " no choices");
            } else {
                T.eq("true", got.getAttribute("enum"), at + " enum flag");
                T.eq(want.enums.length, enums.size(), at + " choice count");
                for (int j = 0; j < want.enums.length; j++) {
                    T.eq(want.enums[j], enums.get(j).getAttribute("value"), at + " choice " + j);
                    // the form preselects the choice flagged default="true" (the vendor example and the working MPB Synology
                    // describe, knowledge/context/mpb/wire_reference/synology_nas_working_describe.xml, do this); one flagged
                    // choice, and it is the identifier's default
                    T.eq(want.enums[j].equals(want.dflt) ? "true" : "false",
                            enums.get(j).getAttribute("default"), at + " choice " + j + " default flag");
                    T.check(!enums.get(j).hasAttribute("displayOrder"), at + " choice has no displayOrder (not in the schema)");
                    if (want.key.equals("cluster_check_period_hours")) {
                        String v = want.enums[j];
                        T.eq(v + (v.equals("01") ? " hour" : " hours"), label(enums.get(j)), at + " choice label");
                    } else {
                        T.check(!enums.get(j).hasAttribute("nameKey"), at + " choice shows its value");
                    }
                }
                // the default is one of the choices
                T.check(Arrays.asList(want.enums).contains(want.dflt), at + " default is a choice");
            }
            // every field has a description in our own words; the TLS one warns about credentials
            String desc = descriptions.get(Integer.valueOf(got.getAttribute("nameKey")));
            T.check(desc != null && desc.length() > 30, at + " has a description");
            if (want.key.equals("allowInsecure")) {
                T.contains(desc, "without any certificate check", "the opt-out warns that credentials go unchecked");
                T.contains(desc, "WARNING", "plain warning");
                T.contains(desc, "default", "says false is the default");
            }
            // an advanced field must have a default or be optional, or it stays in the main form
            if (want.advanced) T.check(!want.required || want.dflt != null, at + " lands under Advanced Settings");
        }
        T.done("describe: 18 fields: the original pack's, with the vCenter TLS pair replaced by Allow Insecure SSL");
    }

    static void credential(Element root) {
        Element ck = kids(kids(root, "CredentialKinds").get(0), "CredentialKind").get(0);
        T.eq("vsphere_user", ck.getAttribute("key"), "credential kind key");
        List<Element> fs = kids(ck, "CredentialField");
        String[][] want = {
            {"user", "vCenter User Name", "true", "false"},
            {"password", "vCenter Password", "true", "true"},
            {"winUser", "Windows User Name", "false", "false"},
            {"winPass", "Windows Password", "false", "true"},
        };
        T.eq(4, fs.size(), "four credential fields");
        for (int i = 0; i < 4; i++) {
            Element e = fs.get(i);
            T.eq(want[i][0], e.getAttribute("key"), "credential key " + i);
            T.eq(want[i][1], label(e), "credential label " + i);
            T.eq(want[i][2], e.getAttribute("required"), "credential required " + i);
            T.eq(want[i][3].equals("true") ? "true" : "", e.getAttribute("password"), "credential password flag " + i);
        }
        T.done("describe: credential vsphere_user");
    }

    static void selfMetrics(Element root) {
        Element inst = kind(root, "vcfcf_vcommunity");
        Element group = null;
        for (Element g : kids(inst, "ResourceGroup")) if (g.getAttribute("key").equals("Collection")) group = g;
        T.check(group != null, "Collection group on the adapter instance");
        Set<String> declared = new TreeSet<>();
        for (Element a : kids(group, "ResourceAttribute")) {
            declared.add("Collection|" + a.getAttribute("key"));
            if (a.getAttribute("key").equals("last_error") || a.getAttribute("key").equals("failure_causes")) {
                T.eq("true", a.getAttribute("isProperty"), a.getAttribute("key") + " is a property");
            } else {
                T.eq("false", a.getAttribute("isProperty"), a.getAttribute("key") + " is a metric");
                T.eq("float", a.getAttribute("dataType"), a.getAttribute("key") + " is a number");
                T.eq("true", a.getAttribute("defaultMonitored"), a.getAttribute("key") + " monitored");
            }
        }
        Set<String> pushed = new TreeSet<>(CollectionHealth.selfMetrics(fullSummary()).keySet());
        pushed.add(CollectionHealth.LAST_ERROR);
        pushed.add(CollectionHealth.FAILURE_CAUSES);
        Set<String> guest = new TreeSet<>(Arrays.asList("Collection|guest_vms_done", "Collection|guest_vms_pending"));
        Set<String> need = new TreeSet<>(pushed);
        need.addAll(guest);
        T.eq(need, declared, "the declared self metrics are exactly the ones the cycle pushes, plus the two guest figures");
        // every key the cycle pushes is declared
        for (String k : pushed) T.check(declared.contains(k), "declared: " + k);
        T.done("describe: self metrics match what the cycle pushes");
    }

    static CollectionHealth.Summary fullSummary() {
        CollectionHealth.Summary s = new CollectionHealth.Summary();
        s.seriesPushed = 1;
        s.propertiesPushed = 1;
        s.checklistSkipped = 0;
        s.objectsUnmatched = 0;
        return s;
    }

    static void ownKinds(Element root) throws Exception {
        Element col = kind(root, "vCommunityCollector");
        T.eq("1", col.getAttribute("type"), "collector is an ordinary kind");
        List<Element> cids = kids(col, "ResourceIdentifier");
        T.eq(2, cids.size(), "collector identifiers");
        T.eq("vcenter", cids.get(0).getAttribute("key"), "collector id 1");
        T.eq("config_files", cids.get(1).getAttribute("key"), "collector id 2");
        for (Element i : cids) T.eq("true", i.getAttribute("required"), "collector identifiers are required");
        T.check(kids(col, "ResourceGroup").isEmpty(), "collector has no metrics");
        T.eq("VCF Content Factory vCommunity Collector", label(col), "collector label");

        // own kinds: identifiers as designed, attributes as rows 227-252 of the key map
        Map<String, String[]> idents = new LinkedHashMap<>();
        idents.put("Content Library", new String[] {"Library ID"});
        idents.put("Library Item", new String[] {"Item ID"});
        idents.put("VM Template", new String[] {"VMEntityObjectID", "VMEntityVCID"});
        List<String> rows = Files.readAllLines(new File("docs/design/vcommunity-e1-key-map.tsv").toPath(), StandardCharsets.UTF_8);
        int n = 0;
        Map<String, Map<String, String>> wantAttrs = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] c = rows.get(i).split("\t", -1);
            if (idents.containsKey(c[0])) {
                n++;
                wantAttrs.computeIfAbsent(c[0], k -> new LinkedHashMap<>()).put(c[2], c[3]);
            }
        }
        T.eq(26, n, "26 own keys in the key map");
        for (Map.Entry<String, String[]> e : idents.entrySet()) {
            Element k = kind(root, e.getKey());
            T.eq("1", k.getAttribute("type"), e.getKey() + " type");
            T.eq(e.getKey(), label(k), e.getKey() + " label");
            List<Element> ids = kids(k, "ResourceIdentifier");
            T.eq(e.getValue().length, ids.size(), e.getKey() + " identifier count");
            for (int i = 0; i < ids.size(); i++) {
                T.eq(e.getValue()[i], ids.get(i).getAttribute("key"), e.getKey() + " identifier");
                T.eq("true", ids.get(i).getAttribute("required"), e.getKey() + " identifier required");
            }
            Map<String, String> got = new LinkedHashMap<>();
            Element top = kids(k, "ResourceGroup").get(0);
            T.eq("vCommunity", top.getAttribute("key"), e.getKey() + " top group");
            Element sub = kids(top, "ResourceGroup").get(0);
            T.eq(e.getKey(), sub.getAttribute("key"), e.getKey() + " sub group");
            for (Element a : kids(sub, "ResourceAttribute")) {
                got.put("vCommunity|" + e.getKey() + "|" + a.getAttribute("key"),
                        a.getAttribute("isProperty").equals("true") ? "property" : "metric");
                T.check(!a.hasAttribute("unit"), "no unit: the key map declares none (the unit is in the key name)");
            }
            T.eq(wantAttrs.get(e.getKey()), got, e.getKey() + " attributes (key and kind) equal rows of the key map");
        }
        traversal(root);
        T.done("describe: collector and own kinds equal the key map");
    }

    /** The one traversal spec: rooted at Content Library, reaching Library Item, nothing else (design 2.3). */
    static void traversal(Element root) {
        org.w3c.dom.NodeList specs = root.getElementsByTagName("TraversalSpecKind");
        T.eq(1, specs.getLength(), "one traversal spec");
        Element spec = (Element) specs.item(0);
        T.eq("vCommunity Content Libraries", spec.getAttribute("name"), "its name");
        T.eq("vcfcf_vcommunity", spec.getAttribute("rootAdapterKind"), "rooted in this pak");
        T.eq("Content Library", spec.getAttribute("rootResourceKind"),
                "rooted at Content Library: the collector and the adapter instance have no children, so a first hop from them never matches");
        T.eq("GENERIC_RELATION", spec.getAttribute("filterType"), "filter type");
        List<Element> paths = kids(spec, "ResourcePath");
        T.eq(1, paths.size(), "one path");
        T.eq("vcfcf_vcommunity::Content Library||vcfcf_vcommunity::Library Item::child", paths.get(0).getAttribute("path"),
                "the path starts at the root kind itself and reaches its items as children");
        // every kind the path names is declared here, with the same key
        Set<String> declared = new HashSet<>();
        for (Element k : kids(kids(root, "ResourceKinds").get(0), "ResourceKind")) declared.add(k.getAttribute("key"));
        for (String hop : paths.get(0).getAttribute("path").split("\\|\\|")) {
            String kind = hop.substring(hop.indexOf("::") + 2).replace("::child", "");
            T.check(declared.contains(kind), "the path names a declared kind: " + kind);
        }
    }

    static void nameKeys(Document doc) {
        Set<Integer> used = new HashSet<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.hasAttribute("nameKey")) {
                int k = Integer.parseInt(e.getAttribute("nameKey"));
                T.check(labels.containsKey(k), "label for nameKey " + k);
                used.add(k);
            }
        }
        for (Integer d : descriptions.keySet()) T.check(used.contains(d), "description for an unused nameKey " + d);
        Set<Integer> unused = new TreeSet<>(labels.keySet());
        unused.removeAll(used);
        T.check(unused.isEmpty(), "labels nothing refers to: " + unused);
        T.done("describe: every nameKey resolves and none is unused");
    }

    static void defaultsMatchConfig(Element root) {
        Map<String, String> byKey = new HashMap<>();
        for (Element i : kids(kind(root, "vcfcf_vcommunity"), "ResourceIdentifier")) {
            if (i.hasAttribute("default")) byKey.put(i.getAttribute("key"), i.getAttribute("default"));
        }
        T.eq(String.valueOf(VCommunityConfig.DEFAULT_PORT), byKey.get(VCommunityConfig.F_PORT), "port default");
        T.eq("false", byKey.get(VCommunityConfig.F_ALLOW_INSECURE), "Allow Insecure SSL defaults to false, as in the other paks");
        T.check(!byKey.containsKey("vcenter_tls_validation") && !byKey.containsKey("vcenter_thumbprint"), "the removed fields are not described");
        T.eq(VCommunityConfig.DEFAULT_ESXI_SETTINGS_FILE, byKey.get(VCommunityConfig.F_ESXI_SETTINGS_FILE), "esxi settings default");
        T.eq(VCommunityConfig.DEFAULT_ESXI_PACKAGES_FILE, byKey.get(VCommunityConfig.F_ESXI_PACKAGES_FILE), "esxi packages default");
        T.eq(VCommunityConfig.DEFAULT_VM_PARAMS_FILE, byKey.get(VCommunityConfig.F_VM_PARAMS_FILE), "vm params default");
        T.eq(VCommunityConfig.DEFAULT_VM_OPTIONS_FILE, byKey.get(VCommunityConfig.F_VM_OPTIONS_FILE), "vm options default");
        T.eq(String.valueOf(VCommunityConfig.DEFAULT_PERIOD_HOURS), byKey.get(VCommunityConfig.F_PERIOD), "period default");
        T.eq(VCommunityConfig.DEFAULT_CHECK_TIME, byKey.get(VCommunityConfig.F_CHECK_TIME), "time default");
        T.eq(String.valueOf(VCommunityConfig.DEFAULT_WORST_PERCENT), byKey.get(VCommunityConfig.F_WORST_PERCENT), "worst default");
        T.eq(String.valueOf(VCommunityConfig.DEFAULT_MONSTER_CPU_PERCENT), byKey.get(VCommunityConfig.F_MONSTER_CPU), "monster cpu default");
        T.eq(String.valueOf(VCommunityConfig.DEFAULT_MONSTER_MEM_PERCENT), byKey.get(VCommunityConfig.F_MONSTER_MEM), "monster mem default");
        T.eq(String.valueOf(VCommunityConfig.DEFAULT_MONSTER_MEM_MIN_GB), byKey.get(VCommunityConfig.F_MONSTER_MEM_MIN), "monster mem min default");
        T.eq(VCommunityConfig.DEFAULT_WIN_SERVICE_FILE, byKey.get(VCommunityConfig.F_WIN_SERVICE_FILE), "service file default");
        T.eq(VCommunityConfig.DEFAULT_WIN_EVENT_FILE, byKey.get(VCommunityConfig.F_WIN_EVENT_FILE), "event file default");
        T.eq(VCommunityConfig.GuestTlsMode.DEFAULT.label, byKey.get(VCommunityConfig.F_GUEST_TLS), "guest tls default");
        T.eq("Disabled", byKey.get(VCommunityConfig.F_SERVICE_MONITORING), "service monitoring default");
        T.eq("Disabled", byKey.get(VCommunityConfig.F_EVENT_MONITORING), "event monitoring default");
        // the choices in the account are exactly the ones the config class accepts
        T.eq(Arrays.asList(VCommunityConfig.PERIOD_CHOICES), Arrays.asList(FIELDS[7].enums), "period choices");
        for (String m : FIELDS[16].enums) T.check(VCommunityConfig.GuestTlsMode.parse(m) != null, "config accepts " + m);
        T.done("describe: defaults and choices equal the config class");
    }
}
