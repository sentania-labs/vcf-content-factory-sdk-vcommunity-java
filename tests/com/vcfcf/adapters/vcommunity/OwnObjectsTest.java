package com.vcfcf.adapters.vcommunity;

import static com.vcfcf.adapters.vcommunity.SoapFx.arr;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.coll.LibraryLinks;
import com.vcfcf.adapters.vcommunity.coll.OwnObject;
import com.vcfcf.adapters.vcommunity.coll.OwnObjects;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth.Status;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.rest.LibraryData;
import com.vcfcf.adapters.vcommunity.rest.LibraryReader;
import com.vcfcf.adapters.vcommunity.rest.RestClient;
import com.vcfcf.adapters.vcommunity.rest.RestResult;
import com.vcfcf.adapters.vcommunity.text.Quirks;
import com.vcfcf.adapters.vcommunity.tls.Thumbprints;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase P3, the pak's own objects, through the real entry point: a cycle of the engine over the
 * real vim25 client, the real REST client, the real parsers and collectors, against a vCenter
 * built from hand-written SOAP answers and a content library API built from hand-written JSON,
 * both served over TLS. What the cycle delivers as own objects, links and kinds read in full is
 * what is asserted, and every key of the three own kinds has its value compared with what the
 * original gives (the coverage table is generated from that).
 */
public final class OwnObjectsTest {

    static final String V = "vCommunity|";
    static final long NOW = 1_700_000_000_000L;
    static final long GIB = 1024L * 1024 * 1024;
    static final String LIB = V + "Content Library|";
    static final String ITM = V + "Library Item|";
    static final String TPL = V + "VM Template|";

    // ------------------------------------------------------------------ the rig

    static final class Rig implements AutoCloseable {
        final FakeVc fake;
        final FakeVcenter vc;
        final FakeLibraries libs;
        final P2Ops ops = new P2Ops();
        final VimSoapClient vim;
        final LibraryReader reader;
        final CycleEngine engine;
        final T.RecLog log = new T.RecLog();
        final WarnCounter warns = new WarnCounter(log);
        final AtomicLong clock = new AtomicLong(NOW);

        Rig(FakeVc fake, FakeVcenter vc, FakeLibraries libs) {
            this(fake, vc, libs, TlsAndSessionTest.tofu());
        }

        Rig(FakeVc fake, FakeVcenter vc, FakeLibraries libs, VcenterTls tls) {
            this.fake = fake;
            this.vc = vc;
            this.libs = libs;
            fake.setHandler(vc);
            fake.setRestHandler(libs);
            vim = new VimSoapClient("localhost:" + fake.port(), "u", "p", tls, warns, 5000, 20000, 20000);
            reader = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER, FakeLibraries.PASSWORD,
                    tls, warns, 5000, 20000, 4);
            engine = new CycleEngine(EngineTest.cfg(), vim,
                    (h, p, t) -> ThumbprintProbe.Result.found("ab".repeat(32)), ops,
                    new CheckListStore(warns), warns, clock::get, reader);
            PropertiesTest.mapAll(ops, vc);
        }

        CycleEngine.Result run() throws Exception {
            return engine.run(clock.get() + 240_000);
        }

        @Override
        public void close() {
            vim.close("test finished");
        }
    }

    /** A vCenter with only what the template tests need: datastores, folders, a plain VM. */
    static FakeVcenter vcenter() {
        FakeVcenter vc = new FakeVcenter();
        vc.add(P2Fixtures.dsVmfs("datastore-1", "vsan-ds")).add(P2Fixtures.dsNfs("datastore-2", "nfs-ds"));
        vc.add(new SoapFx.Obj("Folder", "group-v1").name("vm"));
        vc.add(new SoapFx.Obj("Folder", "group-v2").name("Templates"));
        vc.add(new SoapFx.Obj("Folder", "group-v3").name("Other"));
        vc.add(new SoapFx.Obj("VirtualMachine", "vm-1").name("web01").put("config.template", SoapFx.bool(false)));
        return vc;
    }

    static String dsRefs(String... ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            b.append("<ManagedObjectReference type=\"Datastore\" xsi:type=\"ManagedObjectReference\">")
                    .append(id).append("</ManagedObjectReference>");
        }
        return arr("ArrayOfManagedObjectReference", b.toString());
    }

    /** A template with everything a template object uses. */
    static SoapFx.Obj template(String moid, String name, String folder, String guest, String version, int cpu,
            int memoryMb, long committed, String... datastores) {
        SoapFx.Obj o = new SoapFx.Obj("VirtualMachine", moid).name(name);
        o.put("config.template", SoapFx.bool(true));
        if (guest != null) o.put("config.guestFullName", SoapFx.str(guest));
        if (version != null) o.put("config.version", SoapFx.str(version));
        o.put("summary.config.numCpu", SoapFx.intv(cpu));
        o.put("summary.config.memorySizeMB", SoapFx.intv(memoryMb));
        o.put("summary.storage.committed", SoapFx.longv(committed));
        o.put("datastore", dsRefs(datastores));
        if (folder != null) o.put("parent", SoapFx.moref("Folder", folder));
        return o;
    }

    // ------------------------------------------------------------------ the libraries of the examples

    static String subscription(boolean auto) {
        return FakeLibraries.mRaw("subscription_info", "{\"subscription_url\":\"https://example.test/lib.json\","
                + "\"automatic_sync_enabled\":" + auto + ",\"on_demand\":true}");
    }

    /** One local library with every shape of item, one subscribed, one subscribed and empty, one bare. */
    static FakeLibraries libraries() {
        FakeLibraries l = new FakeLibraries();
        l.lib("lib-a", FakeLibraries.libraryJson("lib-a", "cl-local", FakeLibraries.m("type", "LOCAL"),
                        FakeLibraries.mRaw("state_info", "{\"state\":\"ACTIVE\"}")),
                "item-a1", "item-a2", "item-a3");
        l.item("item-a1", FakeLibraries.itemJson("item-a1", "ubuntu-24", FakeLibraries.m("type", "ovf"),
                FakeLibraries.m("description", "Ubuntu 24.04 template"), FakeLibraries.m("version", "1"),
                FakeLibraries.m("content_version", "3"), FakeLibraries.mRaw("cached", "true"),
                FakeLibraries.mRaw("security_compliance", "true"),
                FakeLibraries.mRaw("certificate_verification_info", "{\"status\":\"INTERNAL\"}"),
                FakeLibraries.m("last_modified_time", "2026-11-01T09:01:58.636Z"),
                FakeLibraries.mRaw("size", String.valueOf(2 * GIB)), FakeLibraries.mRaw("metadata_version", "\"9\"")));
        // an item with no files: size 0, an empty description, no content version
        l.item("item-a2", FakeLibraries.itemJson("item-a2", "tools.iso", FakeLibraries.m("type", "iso"),
                FakeLibraries.m("description", ""), FakeLibraries.m("version", "1"),
                FakeLibraries.mRaw("cached", "true"), FakeLibraries.mRaw("security_compliance", "true"),
                FakeLibraries.mRaw("certificate_verification_info", "{\"status\":\"NOT_AVAILABLE\"}"),
                FakeLibraries.m("last_modified_time", "2026-11-02T10:00:00.000Z"), FakeLibraries.mRaw("size", "0")));
        // 0.125 GiB: a tie, which Python's round gives to the even digit
        l.item("item-a3", FakeLibraries.itemJson("item-a3", "half-gib", FakeLibraries.m("type", "other"),
                FakeLibraries.m("version", "2"), FakeLibraries.m("content_version", "4"),
                FakeLibraries.mRaw("cached", "true"), FakeLibraries.mRaw("security_compliance", "true"),
                FakeLibraries.mRaw("size", String.valueOf(GIB / 8))));

        l.lib("lib-b", FakeLibraries.libraryJson("lib-b", "cl-subscribed", FakeLibraries.m("type", "SUBSCRIBED"),
                        subscription(true), FakeLibraries.mRaw("state_info", "{\"state\":\"ACTIVE\"}")),
                "item-b1", "item-b2");
        l.item("item-b1", FakeLibraries.itemJson("item-b1", "photon", FakeLibraries.m("type", "ovf"),
                FakeLibraries.m("version", "1"), FakeLibraries.m("content_version", "7"),
                FakeLibraries.mRaw("cached", "false"), FakeLibraries.mRaw("security_compliance", "true"),
                FakeLibraries.m("last_sync_time", "2026-11-12T03:00:00.000Z"), FakeLibraries.m("source_id", "src-b1"),
                FakeLibraries.mRaw("size", "0")));
        l.item("item-b2", FakeLibraries.itemJson("item-b2", "alpine", FakeLibraries.m("type", "ovf"),
                FakeLibraries.m("version", "1"), FakeLibraries.mRaw("cached", "false"),
                FakeLibraries.mRaw("security_compliance", "false"), FakeLibraries.m("source_id", "src-b2"),
                FakeLibraries.mRaw("size", String.valueOf(GIB))));

        l.lib("lib-c", FakeLibraries.libraryJson("lib-c", "cl-subscribed-empty", FakeLibraries.m("type", "SUBSCRIBED"),
                subscription(false), FakeLibraries.mRaw("state_info", "{\"state\":\"IN_MAINTENANCE\"}")));

        l.lib("lib-d", FakeLibraries.libraryJson("lib-d", "cl-bare"), "item-d1");
        l.item("item-d1", FakeLibraries.itemJson("item-d1", "bare-item"));
        return l;
    }

    // ------------------------------------------------------------------ helpers

    static OwnObject find(OwnObjects own, String kind, String idKey, String id) {
        for (OwnObject o : own.objects()) {
            if (o.kind.equals(kind) && id.equals(o.identifiers().get(idKey))) return o;
        }
        throw new AssertionError("no " + kind + " with " + idKey + "=" + id + " in " + describe(own));
    }

    static String describe(OwnObjects own) {
        StringBuilder b = new StringBuilder();
        for (OwnObject o : own.objects()) b.append(o.kind).append('/').append(o.name).append(' ');
        return b.toString();
    }

    static OwnObject lib(OwnObjects own, String id) {
        return find(own, OwnObjects.LIBRARY, "Library ID", id);
    }

    static OwnObject item(OwnObjects own, String id) {
        return find(own, OwnObjects.ITEM, "Item ID", id);
    }

    static OwnObject tpl(OwnObjects own, String moid) {
        return find(own, OwnObjects.TEMPLATE, "VMEntityObjectID", moid);
    }

    static boolean has(OwnObjects own, String kind, String idKey, String id) {
        for (OwnObject o : own.objects()) {
            if (o.kind.equals(kind) && id.equals(o.identifiers().get(idKey))) return true;
        }
        return false;
    }

    /** Assert the value of a key on an object, and count the key as value-tested. */
    static void eq(OwnObject o, String key, Object want) {
        Object got = o.props().containsKey(key) ? o.props().get(key) : o.stats().get(key);
        T.eq(want, got, o.kind + " '" + o.name + "' " + key);
        Coverage3.asserted(o.kind, key);
    }

    static void none(OwnObject o, String key) {
        T.check(!o.props().containsKey(key) && !o.stats().containsKey(key),
                o.kind + " '" + o.name + "' must not carry " + key + " but has "
                        + o.props().get(key) + o.stats().get(key));
    }

    static Set<String> kinds(CycleEngine.Result r) {
        return new HashSet<>(r.own.kindsRead());
    }

    static LibraryLinks links(OwnObjects own, String libId) {
        for (LibraryLinks l : own.links()) if (l.libraryId.equals(libId)) return l;
        return null;
    }

    static Set<String> childIds(LibraryLinks l) {
        Set<String> s = new HashSet<>();
        for (LibraryLinks.Child c : l.children()) s.add(c.id);
        return s;
    }

    // ------------------------------------------------------------------ main

    interface Scenario {
        void go() throws Exception;
    }

    static void run(String name, Scenario s) throws Exception {
        Coverage3.scenario = "OwnObjectsTest: " + name;
        long t0 = System.currentTimeMillis();
        s.go();
        T.done("own objects: " + name + " (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    public static void main(String[] args) throws Exception {
        try (FakeVc fake = new FakeVc(FakeVc.happy())) {
            run("libraries and items: every key, local and subscribed, empty, bare", () -> libraryValues(fake));
            run("the answers inside a value envelope read the same", () -> envelope(fake));
            run("templates: every key, folders, datastore order, same names", () -> templateValues(fake));
            run("templates do not need the vCenter adapter to know them", () -> templatesWithoutOps(fake));
            run("identity: kind, name and identifiers, never the Ops id", () -> identity(fake));
            run("links: one full child set per library that was read whole", () -> linkSets(fake));
            run("an absent text is the empty text, an absent figure is not a zero", () -> bare(fake));
            run("the absent-text switch off: nothing for a field that was not returned", () -> absentTextOff(fake));
            run("a denied library is skipped and counted, the rest is delivered", () -> deniedLibrary(fake));
            run("a failed item withholds it and its library's counts", () -> failedItem(fake));
            run("a failed list or login delivers nothing and says so", () -> failedList(fake));
            run("kinds read in full: what proves a vanished object", () -> kindsRead(fake));
            run("template reads that fail withhold one property, not the object", () -> templateReadFailures(fake));
            run("paging and large counts", () -> largeCounts(fake));
            run("the cycle deadline stops the read and proves nothing", () -> deadline(fake));
            run("TLS modes: the REST session goes through the account's posture", () -> tlsModes());
            run("session: one login, the session on every read, one logout, no secret in a line", () -> session(fake));
            run("the absent-text switch", OwnObjectsTest::quirkSeam);
        }
        Coverage3.scenario = "coverage";
        Coverage3.verifyOrWrite();
        T.done("P3 key coverage table equals the key map and the tests");
    }

    // ------------------------------------------------------------------ values

    static void libraryValues(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        try (Rig r = new Rig(fake, vcenter(), l)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            OwnObjects own = res.own;

            // Content Library: local, with a state and no subscription (upstream sends the empty text, Q8)
            OwnObject a = lib(own, "lib-a");
            T.eq("cl-local", a.name, "library name");
            eq(a, LIB + "Type", "LOCAL");
            eq(a, LIB + "State", "ACTIVE");
            eq(a, LIB + "Subscription URL", "");
            eq(a, LIB + "Automatic Sync", "");
            eq(a, LIB + "Items", 3.0);
            eq(a, LIB + "Items Not Stored Locally", 0.0);
            eq(a, LIB + "Items Not Security Compliant", 0.0);
            // (2 GiB + 0 + 0.125 GiB) / GiB = 2.125, a tie: round half to even gives 2.12
            eq(a, LIB + "Total Size (GB)", 2.12);

            // Content Library: subscribed
            OwnObject b = lib(own, "lib-b");
            eq(b, LIB + "Type", "SUBSCRIBED");
            eq(b, LIB + "Subscription URL", "https://example.test/lib.json");
            eq(b, LIB + "Automatic Sync", "True");
            eq(b, LIB + "State", "ACTIVE");
            eq(b, LIB + "Items", 2.0);
            eq(b, LIB + "Items Not Stored Locally", 2.0);
            eq(b, LIB + "Items Not Security Compliant", 1.0);
            eq(b, LIB + "Total Size (GB)", 1.0);

            // a subscribed library with no items: zero of everything that was read
            OwnObject c = lib(own, "lib-c");
            eq(c, LIB + "Automatic Sync", "False");
            eq(c, LIB + "State", "IN_MAINTENANCE");
            eq(c, LIB + "Items", 0.0);
            eq(c, LIB + "Items Not Stored Locally", 0.0);
            eq(c, LIB + "Items Not Security Compliant", 0.0);
            eq(c, LIB + "Total Size (GB)", 0.0);

            // Library Item: every property, as the original's str()
            OwnObject a1 = item(own, "item-a1");
            T.eq("ubuntu-24", a1.name, "item name");
            eq(a1, ITM + "Type", "ovf");
            eq(a1, ITM + "Description", "Ubuntu 24.04 template");
            eq(a1, ITM + "Version", "1");
            eq(a1, ITM + "Content Version", "3");
            eq(a1, ITM + "Stored Locally", "True");
            eq(a1, ITM + "Security Compliant", "True");
            eq(a1, ITM + "Certificate Verification", "INTERNAL");
            eq(a1, ITM + "Last Modified", "2026-11-01T09:01:58.636Z");
            eq(a1, ITM + "Last Sync", ""); // a local item was never synced: the empty text, as upstream (Q8)
            eq(a1, ITM + "Source Item ID", "");
            eq(a1, ITM + "Size (GB)", 2.0);

            // an item with no files and an empty description: size 0 is a reading, "" is sent as it is
            OwnObject a2 = item(own, "item-a2");
            eq(a2, ITM + "Type", "iso");
            eq(a2, ITM + "Description", "");
            eq(a2, ITM + "Content Version", "");
            eq(a2, ITM + "Certificate Verification", "NOT_AVAILABLE");
            eq(a2, ITM + "Size (GB)", 0.0);

            // 0.125 GiB is a tie
            eq(item(own, "item-a3"), ITM + "Size (GB)", 0.12);
            eq(item(own, "item-a3"), ITM + "Certificate Verification", "");

            // a subscribed library's items: not stored, not compliant, synced from a source
            OwnObject b1 = item(own, "item-b1");
            eq(b1, ITM + "Stored Locally", "False");
            eq(b1, ITM + "Security Compliant", "True");
            eq(b1, ITM + "Last Sync", "2026-11-12T03:00:00.000Z");
            eq(b1, ITM + "Source Item ID", "src-b1");
            eq(b1, ITM + "Size (GB)", 0.0);
            eq(item(own, "item-b2"), ITM + "Security Compliant", "False");
            eq(item(own, "item-b2"), ITM + "Size (GB)", 1.0);

            T.eq(4, count(own, OwnObjects.LIBRARY), "four libraries");
            T.eq(6, count(own, OwnObjects.ITEM), "six items");
            T.eq(new HashSet<>(List.of(OwnObjects.LIBRARY, OwnObjects.ITEM, OwnObjects.TEMPLATE)), kinds(res),
                    "all three kinds were read in full");
            T.eq(Status.OK, res.summary.collectorStatus.get("library"), "library status");
            T.eq(Status.OK, res.summary.collectorStatus.get("template"), "template status");
        }
    }

    static int count(OwnObjects own, String kind) {
        int n = 0;
        for (OwnObject o : own.objects()) if (o.kind.equals(kind)) n++;
        return n;
    }

    static void envelope(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        l.envelope = true;
        try (Rig r = new Rig(fake, vcenter(), l)) {
            CycleEngine.Result res = r.run();
            T.eq(10, res.own.objects().size() - count(res.own, OwnObjects.TEMPLATE), "the same objects");
            eq(lib(res.own, "lib-b"), LIB + "Subscription URL", "https://example.test/lib.json");
            eq(item(res.own, "item-a1"), ITM + "Size (GB)", 2.0);
        }
    }

    static void templateValues(FakeVc fake) throws Exception {
        FakeVcenter vc = vcenter();
        vc.add(template("vm-10", "tmpl-photon", "group-v2", "VMware Photon OS (64-bit)", "vmx-21", 4, 8192,
                9 * GIB, "datastore-2", "datastore-1"));
        // the same name in another folder: two objects, told apart by the MoRef
        vc.add(template("vm-11", "tmpl-photon", "group-v1", "Ubuntu Linux (64-bit)", "vmx-19", 1, 1536,
                GIB * 3 / 8, "datastore-1"));
        // 128 MB is 0.125 GB, a tie; no datastore at all (an empty list), zero CPUs
        vc.add(template("vm-12", "tmpl-tiny", "group-v3", "Other (64-bit)", "vmx-13", 0, 128, 0));
        try (Rig r = new Rig(fake, vc, libraries())) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            OwnObjects own = res.own;
            T.eq(3, count(own, OwnObjects.TEMPLATE), "three templates, the plain VM is not one");

            OwnObject t1 = tpl(own, "vm-10");
            T.eq("tmpl-photon", t1.name, "the VM's name is the object's name");
            T.eq(FakeVc.INSTANCE_UUID, t1.identifiers().get("VMEntityVCID"), "the vCenter's instance UUID");
            eq(t1, TPL + "Guest OS", "VMware Photon OS (64-bit)");
            eq(t1, TPL + "Hardware Version", "vmx-21");
            eq(t1, TPL + "vCPUs", "4");
            eq(t1, TPL + "Memory (GB)", "8.0");
            eq(t1, TPL + "Datastore", "nfs-ds, vsan-ds"); // the order the VM lists them
            eq(t1, TPL + "VM Folder", "Templates");
            eq(t1, TPL + "Disk Used (GB)", 9.0);

            OwnObject t2 = tpl(own, "vm-11");
            T.eq("tmpl-photon", t2.name, "the same name");
            eq(t2, TPL + "Memory (GB)", "1.5");
            eq(t2, TPL + "Datastore", "vsan-ds");
            eq(t2, TPL + "VM Folder", "vm");
            eq(t2, TPL + "Disk Used (GB)", 0.38); // 0.375 GiB: a tie, to the even digit
            T.check(!t1.identity().equals(t2.identity()), "told apart by identifiers");

            OwnObject t3 = tpl(own, "vm-12");
            eq(t3, TPL + "vCPUs", "0");
            eq(t3, TPL + "Memory (GB)", "0.12"); // 0.125: a tie
            eq(t3, TPL + "Datastore", ""); // a returned empty list is no datastore, as read
            eq(t3, TPL + "Disk Used (GB)", 0.0);
            T.check(kinds(res).contains(OwnObjects.TEMPLATE), "every VM was told apart: templates read in full");
        }
    }

    static void templatesWithoutOps(FakeVc fake) throws Exception {
        FakeVcenter vc = vcenter();
        vc.add(template("vm-10", "tmpl-photon", "group-v2", "VMware Photon OS (64-bit)", "vmx-21", 4, 8192, GIB,
                "datastore-1"));
        try (Rig r = new Rig(fake, vc, new FakeLibraries())) {
            // the Suite API knows no virtual machine at all (DV-47: the vCenter adapter's model is not a gate)
            r.ops.resources.remove("VirtualMachine");
            CycleEngine.Result res = r.run();
            T.eq(1, count(res.own, OwnObjects.TEMPLATE), "the template is delivered anyway");
            T.check(kinds(res).contains(OwnObjects.TEMPLATE), "and read in full");
        }
    }

    static void identity(FakeVc fake) throws Exception {
        FakeVcenter vc = vcenter();
        vc.add(template("vm-10", "t", "group-v2", "g", "vmx-21", 1, 1024, GIB, "datastore-1"));
        try (Rig r = new Rig(fake, vc, libraries())) {
            CycleEngine.Result res = r.run();
            for (OwnObject o : res.own.objects()) {
                switch (o.kind) {
                    case "Content Library": T.eq(List.of("Library ID"), new ArrayList<>(o.identifiers().keySet()), "library id"); break;
                    case "Library Item": T.eq(List.of("Item ID"), new ArrayList<>(o.identifiers().keySet()), "item id"); break;
                    case "VM Template":
                        T.eq(List.of("VMEntityObjectID", "VMEntityVCID"), new ArrayList<>(o.identifiers().keySet()),
                                "template ids, in the order the describe lists them");
                        break;
                    default: throw new AssertionError("an object of the kind " + o.kind);
                }
                T.check(!o.name.isEmpty(), "every object has a name");
            }
            Set<String> identities = new HashSet<>();
            for (OwnObject o : res.own.objects()) identities.add(o.identity());
            T.eq(res.own.objects().size(), identities.size(), "no two objects share an identity");
            // an own object's keys all start with the group of its kind
            for (OwnObject o : res.own.objects()) {
                String prefix = V + o.kind + "|";
                for (String k : o.props().keySet()) T.check(k.startsWith(prefix), k + " belongs to " + o.kind);
                for (String k : o.stats().keySet()) T.check(k.startsWith(prefix), k + " belongs to " + o.kind);
            }
        }
    }

    static void linkSets(FakeVc fake) throws Exception {
        try (Rig r = new Rig(fake, vcenter(), libraries())) {
            OwnObjects own = r.run().own;
            T.eq(Set.of("item-a1", "item-a2", "item-a3"), childIds(links(own, "lib-a")), "library a's children");
            T.eq(Set.of("item-b1", "item-b2"), childIds(links(own, "lib-b")), "library b's children");
            T.eq(Set.of(), childIds(links(own, "lib-c")), "an empty library has an empty set");
            T.eq(Set.of("item-d1"), childIds(links(own, "lib-d")), "library d's child");
            T.eq("ubuntu-24", links(own, "lib-a").children().get(0).name, "a child carries the item's name");
            T.eq("cl-local", links(own, "lib-a").libraryName, "and the library its name");
            // the cap leaves a set out whole, never cuts it
            int[] skipped = new int[1];
            List<LibraryLinks> fit = own.linksWithin(3, skipped);
            T.eq(List.of("lib-a", "lib-c"), List.of(fit.get(0).libraryId, fit.get(1).libraryId),
                    "within a cap of 3 edges: a (3 edges) and the empty c, in order; b and d would pass it");
            T.eq(2, fit.size(), "two sets");
            T.eq(2, skipped[0], "b and d are left out whole, not cut");
            T.eq(4, own.linksWithin(100, skipped).size(), "nothing left out when they fit");
            T.eq(0, skipped[0], "none skipped");
        }
    }

    static void bare(FakeVc fake) throws Exception {
        FakeVcenter vc = vcenter();
        vc.add(new SoapFx.Obj("VirtualMachine", "vm-10").name("only-a-name").put("config.template", SoapFx.bool(true)));
        try (Rig r = new Rig(fake, vc, libraries())) {
            CycleEngine.Result res = r.run();
            OwnObjects own = res.own;
            // a library with only an id and a name: its texts are the empty text (Q8), and the counts
            // that would need an item to have answered are not sent as a zero (its one item has
            // nothing but an id and a name)
            OwnObject d = lib(own, "lib-d");
            T.eq(4, d.props().size(), "the four texts");
            for (String v : d.props().values()) T.eq("", v, "an empty text, not a placeholder");
            eq(d, LIB + "Items", 1.0);
            none(d, LIB + "Items Not Stored Locally");
            none(d, LIB + "Items Not Security Compliant");
            none(d, LIB + "Total Size (GB)");
            OwnObject di = item(own, "item-d1");
            T.eq(10, di.props().size(), "the ten texts");
            for (String v : di.props().values()) T.eq("", v, "an empty text, not a placeholder");
            T.eq(0, di.stats().size(), "and no size: not a 0.0");
            OwnObject t = tpl(own, "vm-10");
            T.eq(4, t.props().size(), "the four texts of a template; the two figures are not there");
            for (String v : t.props().values()) T.eq("", v, "an empty text");
            none(t, TPL + "vCPUs");
            none(t, TPL + "Memory (GB)");
            T.eq(0, t.stats().size(), "and no disk figure: not a 0.0");
            T.check(res.summary.valuesWithheld >= 3 + 1 + 3, "the withheld figures are counted: " + res.summary.valuesWithheld);
            for (OwnObject o : own.objects()) {
                for (String v : o.props().values()) T.check(v != null, "no null value");
            }
        }
    }

    /** With the switch off, the original's empty text for a field that was not returned is not sent. */
    static void absentTextOff(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        fake.setRestHandler(l);
        LibraryData d = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER, FakeLibraries.PASSWORD,
                TlsAndSessionTest.tofu(), new T.RecLog()).read(Long.MAX_VALUE, () -> NOW);
        com.vcfcf.adapters.vcommunity.health.Failures f = new com.vcfcf.adapters.vcommunity.health.Failures();
        OwnObjects own = com.vcfcf.adapters.vcommunity.coll.LibraryCollector.collect(d, f, false).own;
        OwnObject a = lib(own, "lib-a");
        T.eq("LOCAL", a.props().get(LIB + "Type"), "a returned text");
        T.check(!a.props().containsKey(LIB + "Subscription URL"), "a local library has none: nothing sent");
        T.check(!a.props().containsKey(LIB + "Automatic Sync"), "nor an automatic sync");
        T.eq("", item(own, "item-a2").props().get(ITM + "Description"), "a returned empty text is sent as it is, either way (Q4)");
        T.check(!item(own, "item-a1").props().containsKey(ITM + "Last Sync"), "no last sync, nothing sent");
        T.check(!item(own, "item-a2").props().containsKey(ITM + "Content Version"), "no content version, nothing sent");
        T.eq(0, item(own, "item-d1").props().size(), "a bare item sends nothing");
        T.eq(0, lib(own, "lib-d").props().size(), "a bare library sends nothing");
        int withheld = 0;
        for (OwnObject o : own.objects()) withheld += o.withheld();
        T.check(withheld >= 4 + 10 + 3 + 1, "and counts what it did not send: " + withheld);
        // the same for a template
        com.vcfcf.adapters.vcommunity.coll.TemplateScan scan = new com.vcfcf.adapters.vcommunity.coll.TemplateScan();
        com.vcfcf.adapters.vcommunity.coll.TemplateScan.Row row = new com.vcfcf.adapters.vcommunity.coll.TemplateScan.Row();
        row.moref = "vm-1";
        row.name = "bare";
        scan.rows.add(row);
        OwnObject t = com.vcfcf.adapters.vcommunity.coll.TemplateCollector.collect(scan, FakeVc.INSTANCE_UUID,
                new java.util.HashMap<>(), new java.util.HashMap<>(), new com.vcfcf.adapters.vcommunity.health.Failures(),
                false).own.objects().get(0);
        T.eq(0, t.props().size() + t.stats().size(), "a bare template sends nothing");
        OwnObject t2 = com.vcfcf.adapters.vcommunity.coll.TemplateCollector.collect(scan, FakeVc.INSTANCE_UUID,
                new java.util.HashMap<>(), new java.util.HashMap<>(), new com.vcfcf.adapters.vcommunity.health.Failures(),
                true).own.objects().get(0);
        T.eq(4, t2.props().size(), "with the switch on: the four texts");
        T.eq(0, t2.stats().size(), "and never a zero for a figure");
    }

    // ------------------------------------------------------------------ failures

    static void deniedLibrary(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        l.status.put("/api/content/library/lib-b", 403);
        try (Rig r = new Rig(fake, vcenter(), l)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "the cycle goes on: " + res.failure);
            OwnObjects own = res.own;
            T.check(!has(own, OwnObjects.LIBRARY, "Library ID", "lib-b"), "the denied library has no object");
            T.check(!has(own, OwnObjects.ITEM, "Item ID", "item-b1"), "nor do its items");
            T.check(links(own, "lib-b") == null, "nor a child set: its links stay as they were");
            T.check(has(own, OwnObjects.LIBRARY, "Library ID", "lib-a"), "the others are delivered");
            eq(item(own, "item-a1"), ITM + "Size (GB)", 2.0);
            T.check(links(own, "lib-a") != null, "and their links");
            T.check(!kinds(res).contains(OwnObjects.LIBRARY) && !kinds(res).contains(OwnObjects.ITEM),
                    "neither kind is read in full, so nothing is marked as gone");
            T.eq(Status.PARTIAL, res.summary.collectorStatus.get("library"), "partial");
            T.check(res.summary.collectorFailures.get("library") >= 1, "counted");
            T.contains(res.summary.firstFailure == null ? "" : res.summary.firstFailure, "F-REST-LIB", "the cause id");
            T.contains(r.log.all(), "F-REST-LIB", "one WARN names it");
            T.notContains(r.log.all(), FakeLibraries.PASSWORD, "log");
        }
    }

    static void failedItem(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        l.status.put("/api/content/library/item/item-a2", 403);
        try (Rig r = new Rig(fake, vcenter(), l)) {
            CycleEngine.Result res = r.run();
            OwnObjects own = res.own;
            T.check(!has(own, OwnObjects.ITEM, "Item ID", "item-a2"), "the failed item has no object");
            T.check(has(own, OwnObjects.ITEM, "Item ID", "item-a1"), "its neighbours do");
            OwnObject a = lib(own, "lib-a");
            T.eq("LOCAL", a.props().get(LIB + "Type"), "the library's own properties were read");
            T.eq(0, a.stats().size(), "but none of its four counts: they would count the items that answered");
            T.check(links(own, "lib-a") == null, "and no child set: the failed item keeps its link");
            T.check(links(own, "lib-b") != null, "another library is whole");
            eq(lib(own, "lib-b"), LIB + "Items", 2.0);
            T.check(kinds(res).contains(OwnObjects.LIBRARY), "every library was read, so that kind is whole");
            T.check(!kinds(res).contains(OwnObjects.ITEM), "an item was not, so that kind is not");
            T.contains(res.summary.firstFailure, "F-REST-ITEM", "cause id");
        }
        // the item list of a library fails: the library has its properties and no counts, no items
        FakeLibraries l2 = libraries();
        l2.status.put("/api/content/library/item?library_id=lib-b", 500);
        try (Rig r = new Rig(fake, vcenter(), l2)) {
            OwnObjects own = r.run().own;
            T.eq("SUBSCRIBED", lib(own, "lib-b").props().get(LIB + "Type"), "library kept");
            T.eq(0, lib(own, "lib-b").stats().size(), "no counts");
            T.check(!has(own, OwnObjects.ITEM, "Item ID", "item-b1"), "its items are not known");
            T.check(links(own, "lib-b") == null, "no links");
        }
        // an item that answers with something that is not an item
        FakeLibraries l3 = libraries();
        l3.body.put("/api/content/library/item/item-a1", "[1,2]");
        try (Rig r = new Rig(fake, vcenter(), l3)) {
            OwnObjects own = r.run().own;
            T.check(!has(own, OwnObjects.ITEM, "Item ID", "item-a1"), "not an item: not an object");
        }
        // a library with an item without a name is not given an object either
        FakeLibraries l4 = libraries();
        l4.item("item-a3", FakeLibraries.itemJson("item-a3", null, FakeLibraries.m("type", "other")));
        try (Rig r = new Rig(fake, vcenter(), l4)) {
            CycleEngine.Result res = r.run();
            T.check(!has(res.own, OwnObjects.ITEM, "Item ID", "item-a3"), "an item with no name has no object");
            T.eq(0, lib(res.own, "lib-a").stats().size(), "and its library's counts are withheld");
            T.check(!kinds(res).contains(OwnObjects.ITEM), "and items are not read in full");
        }
    }

    static void failedList(FakeVc fake) throws Exception {
        // a server that echoes the session id and the password in its error text does not get them into a message
        FakeLibraries echo = libraries();
        echo.status.put("/api/content/library/lib-d", 500);
        echo.echoSecrets = true;
        try (Rig r = new Rig(fake, vcenter(), echo)) {
            CycleEngine.Result res = r.run();
            String all = r.log.all() + res.summary.firstFailure;
            T.notContains(all, FakeLibraries.PASSWORD, "an echoed password");
            T.notContains(all, FakeLibraries.SESSION, "an echoed session id");
            T.contains(all, "<session>", "the secret is replaced, not just dropped");
        }
        FakeLibraries l = libraries();
        l.status.put("/api/content/library", 500);
        try (Rig r = new Rig(fake, vcenter(), l)) {
            CycleEngine.Result res = r.run();
            T.eq(0, count(res.own, OwnObjects.LIBRARY) + count(res.own, OwnObjects.ITEM), "nothing from a failed list");
            T.check(res.own.links().isEmpty(), "no links, so none are replaced");
            T.eq(Status.FAILED, res.summary.collectorStatus.get("library"), "failed");
            T.check(!kinds(res).contains(OwnObjects.LIBRARY), "no kind is read in full");
            T.contains(res.summary.firstFailure, "F-REST-LIST", "cause id");
            T.eq(1, l.logouts.get(), "the session was logged out all the same");
        }
        FakeLibraries refused = libraries();
        refused.refuseLogin = true;
        try (Rig r = new Rig(fake, vcenter(), refused)) {
            CycleEngine.Result res = r.run();
            T.eq(0, count(res.own, OwnObjects.LIBRARY), "no login, no library");
            T.contains(res.summary.firstFailure, "REST login", "says it is the login");
            T.eq(0, refused.count("GET"), "nothing was asked without a session");
            T.eq(0, refused.logouts.get(), "no session to log out");
            T.notContains(r.log.all(), FakeLibraries.PASSWORD, "log");
            T.check(res.failure == null, "the cycle does not fail for it");
        }
        FakeLibraries notList = libraries();
        notList.body.put("/api/content/library", "{\"unexpected\":true}");
        try (Rig r = new Rig(fake, vcenter(), notList)) {
            CycleEngine.Result res = r.run();
            T.eq(Status.FAILED, res.summary.collectorStatus.get("library"), "an answer that is not a list is a failed read");
        }
    }

    static void kindsRead(FakeVc fake) throws Exception {
        // a successful empty list is the reading "no libraries": both kinds are read in full
        try (Rig r = new Rig(fake, vcenter(), new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            T.check(kinds(res).contains(OwnObjects.LIBRARY) && kinds(res).contains(OwnObjects.ITEM),
                    "an empty list that was read proves there is none");
            T.eq(0, count(res.own, OwnObjects.LIBRARY), "no object");
            T.eq(Status.OK, res.summary.collectorStatus.get("library"), "ok");
        }
        // a VM whose config.template was not returned may be a template: that kind is not proven
        FakeVcenter vc = vcenter();
        vc.add(new SoapFx.Obj("VirtualMachine", "vm-20").name("inaccessible"));
        try (Rig r = new Rig(fake, vc, new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            T.check(!kinds(res).contains(OwnObjects.TEMPLATE), "an unknown VM: templates are not read in full");
            T.eq(0, count(res.own, OwnObjects.TEMPLATE), "no template");
        }
        // a failed VM read: no template, no proof
        FakeVcenter vc2 = vcenter();
        vc2.forcedType.put("VirtualMachine", FakeVcenter.serverFault("boom"));
        try (Rig r = new Rig(fake, vc2, new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            T.check(!kinds(res).contains(OwnObjects.TEMPLATE), "a failed VM read proves nothing");
            T.eq(Status.FAILED, res.summary.collectorStatus.get("template"), "template status");
        }
        // a template with no name cannot be an object and the kind is not whole
        FakeVcenter vc3 = vcenter();
        vc3.add(new SoapFx.Obj("VirtualMachine", "vm-30").put("config.template", SoapFx.bool(true)));
        try (Rig r = new Rig(fake, vc3, new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            T.eq(0, count(res.own, OwnObjects.TEMPLATE), "no object for a nameless template");
            T.check(!kinds(res).contains(OwnObjects.TEMPLATE), "and the kind is not read in full");
        }
        // a failed cycle (login refused) delivers nothing and proves nothing
        FakeVcenter vc4 = vcenter();
        vc4.forced.put("Login", FakeVc.fault("InvalidLogin", "Cannot complete login"));
        try (Rig r = new Rig(fake, vc4, libraries())) {
            CycleEngine.Result res = r.run();
            T.check(res.failure != null, "the cycle fails");
            T.eq(0, res.own.objects().size(), "no own object");
            T.eq(0, res.own.kindsRead().size(), "no kind read in full");
        }
    }

    static void templateReadFailures(FakeVc fake) throws Exception {
        FakeVcenter vc = vcenter();
        vc.add(template("vm-10", "tmpl-photon", "group-v2", "g", "vmx-21", 4, 8192, 9 * GIB, "datastore-2"));
        vc.forcedType.put("Datastore", FakeVcenter.serverFault("datastores broke"));
        try (Rig r = new Rig(fake, vc, new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            OwnObject t = tpl(res.own, "vm-10");
            none(t, TPL + "Datastore");
            eq(t, TPL + "VM Folder", "Templates");
            eq(t, TPL + "Memory (GB)", "8.0");
            T.eq(Status.PARTIAL, res.summary.collectorStatus.get("template"), "partial: a name could not be read");
            T.eq(Status.FAILED, res.summary.collectorStatus.get("datastore"), "and the datastore read says so");
            T.check(kinds(res).contains(OwnObjects.TEMPLATE), "a failed datastore name does not make the template vanish");
            T.check(res.summary.valuesWithheld >= 1, "withheld and counted");
        }
        FakeVcenter vc2 = vcenter();
        vc2.add(template("vm-10", "tmpl-photon", "group-v2", "g", "vmx-21", 4, 8192, 9 * GIB, "datastore-2"));
        vc2.forcedType.put("Folder", FakeVcenter.serverFault("folders broke"));
        try (Rig r = new Rig(fake, vc2, new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            OwnObject t = tpl(res.own, "vm-10");
            none(t, TPL + "VM Folder");
            eq(t, TPL + "Datastore", "nfs-ds");
            T.eq(Status.PARTIAL, res.summary.collectorStatus.get("template"), "partial");
            T.contains(res.summary.firstFailure == null ? "" : res.summary.firstFailure, "R-FO", "the folder read is named");
        }
        // the folder went between the two reads: the whole list is refused; each is asked alone
        FakeVcenter vc3 = vcenter();
        vc3.add(template("vm-10", "tmpl-photon", "group-v2", "g", "vmx-21", 4, 8192, 9 * GIB, "datastore-2"));
        vc3.add(template("vm-11", "tmpl-other", "group-v3", "g", "vmx-21", 1, 1024, GIB, "datastore-1"));
        vc3.forcedType.put("Folder", new FakeVc.Resp(500, null, SoapFx.envelope("<soapenv:Fault><faultcode>"
                + "ServerFaultCode</faultcode><faultstring>gone</faultstring><detail><ManagedObjectNotFoundFault "
                + "xmlns=\"urn:vim25\" xsi:type=\"ManagedObjectNotFound\"/></detail></soapenv:Fault>")));
        try (Rig r = new Rig(fake, vc3, new FakeLibraries())) {
            CycleEngine.Result res = r.run();
            T.eq(2, count(res.own, OwnObjects.TEMPLATE), "both templates stay");
            none(tpl(res.own, "vm-10"), TPL + "VM Folder");
            none(tpl(res.own, "vm-11"), TPL + "VM Folder");
        }
    }

    // ------------------------------------------------------------------ scale and time

    static void largeCounts(FakeVc fake) throws Exception {
        FakeLibraries l = new FakeLibraries();
        int bigItems = 2500;
        String[] ids = new String[bigItems];
        for (int i = 0; i < bigItems; i++) {
            ids[i] = "big-" + i;
            l.item(ids[i], FakeLibraries.itemJson(ids[i], "iso-" + i, FakeLibraries.m("type", "iso"),
                    FakeLibraries.mRaw("cached", "true"), FakeLibraries.mRaw("security_compliance", i % 100 == 0 ? "false" : "true"),
                    FakeLibraries.mRaw("size", String.valueOf(GIB))));
        }
        l.lib("lib-big", FakeLibraries.libraryJson("lib-big", "big", FakeLibraries.m("type", "LOCAL")), ids);
        int small = 40;
        for (int n = 0; n < small; n++) {
            String id = "lib-s" + n;
            String[] its = {"s" + n + "-1", "s" + n + "-2", "s" + n + "-3"};
            l.lib(id, FakeLibraries.libraryJson(id, "small-" + n, FakeLibraries.m("type", "LOCAL")), its);
            for (String it : its) l.item(it, FakeLibraries.itemJson(it, it, FakeLibraries.m("type", "ovf"),
                    FakeLibraries.mRaw("cached", "true"), FakeLibraries.mRaw("security_compliance", "true"),
                    FakeLibraries.mRaw("size", String.valueOf(GIB / 2))));
        }
        FakeVcenter vc = vcenter();
        for (int i = 0; i < 700; i++) {
            vc.add(template("vm-t" + i, "tpl-" + i, "group-v" + (1 + i % 3), "g", "vmx-21", 2, 2048, GIB,
                    "datastore-" + (1 + i % 2)));
        }
        try (Rig r = new Rig(fake, vc, l)) {
            long t0 = System.currentTimeMillis();
            CycleEngine.Result res = r.run();
            long ms = System.currentTimeMillis() - t0;
            OwnObjects own = res.own;
            T.eq(2500 + small * 3, count(own, OwnObjects.ITEM), "every item has an object");
            T.eq(1 + small, count(own, OwnObjects.LIBRARY), "every library");
            T.eq(700, count(own, OwnObjects.TEMPLATE), "every template, across 15 pages of VMs");
            OwnObject big = lib(own, "lib-big");
            eq(big, LIB + "Items", 2500.0);
            T.eq(Double.valueOf(25.0), big.stats().get(LIB + "Items Not Security Compliant"), "25 of 2500 not compliant");
            T.eq(Double.valueOf(2500.0), big.stats().get(LIB + "Total Size (GB)"), "2500 GiB");
            T.eq(Double.valueOf(1.5), lib(own, "lib-s7").stats().get(LIB + "Total Size (GB)"), "1.5 GiB");
            T.eq(2500, childIds(links(own, "lib-big")).size(), "every child of the big library");
            T.check(kinds(res).containsAll(List.of(OwnObjects.LIBRARY, OwnObjects.ITEM, OwnObjects.TEMPLATE)), "all kinds read in full");
            T.eq(1, l.logins.get(), "one login");
            T.eq(1, l.logouts.get(), "one logout");
            T.eq(1, l.exact("GET /api/content/library"), "one list call");
            T.eq(1 + small, l.count("GET /api/content/library/lib-"), "one detail call per library");
            T.eq(1 + small, l.count("GET /api/content/library/item?library_id="), "one item list per library");
            T.eq(2500 + small * 3, l.count("GET /api/content/library/item/"), "one call per item");
            T.check(ms < 60_000, "a read of 2,620 items finishes in time: " + ms + " ms");
            // the VM pages: 700 templates + 1 VM at 50 per page
            T.check(r.vc.pages.get() >= 15, "the VMs came in pages: " + r.vc.pages.get());
        }
    }

    static void deadline(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        try (Rig r = new Rig(fake, vcenter(), l)) {
            // the clock passes the deadline when the first item is asked for
            l.onGet = p -> {
                if (p.startsWith("/api/content/library/item/")) r.clock.set(NOW + 10_000_000L);
            };
            CycleEngine.Result res = r.run();
            T.check(res.summary.deadlineHit, "the deadline was hit");
            T.check(!kinds(res).contains(OwnObjects.LIBRARY) || !kinds(res).contains(OwnObjects.ITEM),
                    "a read cut short proves nothing");
            T.check(!kinds(res).contains(OwnObjects.ITEM), "items are not read in full");
            T.check(res.summary.collectorStatus.get("library") != Status.OK, "the status is not ok");
            // a library whose items were not all read keeps its links
            int whole = 0;
            for (LibraryLinks lk : res.own.links()) whole += lk.children().size() > 0 ? 1 : 0;
            T.check(whole < 3, "libraries with unread items have no child set: " + whole);
            T.eq(1, l.logouts.get(), "logged out all the same");
        }
    }

    // ------------------------------------------------------------------ TLS and the session

    static void tlsModes() throws Exception {
        try (FakeVc fake = new FakeVc(FakeVc.happy())) {
            FakeLibraries l = libraries();
            fake.setRestHandler(l);
            // Trust on First Use reads a self-signed certificate
            LibraryData d = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER,
                    FakeLibraries.PASSWORD, TlsAndSessionTest.tofu(), new T.RecLog()).read(Long.MAX_VALUE, () -> NOW);
            T.check(d.listFailure == null && d.libraries.size() == 4, "TOFU reads: " + d.listFailure);
            // Pinned: the right pins (SHA-256 and SHA-512) read, a wrong one reads nothing and sends nothing
            for (String pin : new String[] {Thumbprints.of(fake.cert, 256), Thumbprints.of(fake.cert, 512)}) {
                LibraryData p = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER,
                        FakeLibraries.PASSWORD, TlsAndSessionTest.pinned(pin), new T.RecLog()).read(Long.MAX_VALUE, () -> NOW);
                T.check(p.listFailure == null, "Pinned with the right " + pin.length() * 4 + " bit pin: " + p.listFailure);
            }
            int before = fake.requests.size();
            int callsBefore = l.calls.size();
            T.RecLog log = new T.RecLog();
            LibraryData bad = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER,
                    FakeLibraries.PASSWORD, TlsAndSessionTest.pinned("a".repeat(64)), log).read(Long.MAX_VALUE, () -> NOW);
            T.check(bad.listFailure != null, "a wrong pin fails");
            T.contains(bad.listFailure, "differs from the vCenter Thumbprint", "the pin text, as the SOAP session gives it");
            T.eq(callsBefore, l.calls.size(), "no request reached the REST API: the password was never sent");
            T.eq(before, fake.requests.size(), "nothing at all");
            T.notContains(bad.listFailure + log.all(), FakeLibraries.PASSWORD, "failure");
            T.eq(0, bad.libraries.size(), "no library");
            T.eq(1, bad.failures.size(), "one counted failure");
            // Enabled: the platform trust refuses a certificate it does not know, and nothing is sent
            VcenterTls enabled = VcenterTls.create(TlsMode.ENABLED, "", javax.net.ssl.SSLContext.getDefault(),
                    (conn, factory) -> conn.setSSLSocketFactory(factory));
            int calls2 = l.calls.size();
            LibraryData refused = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER,
                    FakeLibraries.PASSWORD, enabled, new T.RecLog()).read(Long.MAX_VALUE, () -> NOW);
            T.check(refused.listFailure != null, "Enabled refuses an untrusted certificate");
            T.contains(refused.listFailure, "not trusted", "says so");
            T.eq(calls2, l.calls.size(), "and sends nothing");
        }
        // Enabled with a trusted certificate that names the host reads; one that does not name it does not
        try (FakeVc fake = new FakeVc(FakeVc.happy(), "dns:localhost")) {
            FakeLibraries l = libraries();
            fake.setRestHandler(l);
            VcenterTls tls = VcenterTls.create(TlsMode.ENABLED, "", fake.trustingContext(), TlsAndSessionTest.nameChecking());
            LibraryData ok = new LibraryReader("localhost:" + fake.port(), FakeLibraries.USER,
                    FakeLibraries.PASSWORD, tls, new T.RecLog()).read(Long.MAX_VALUE, () -> NOW);
            T.check(ok.listFailure == null && ok.libraries.size() == 4, "Enabled reads through a trusted certificate: " + ok.listFailure);
            int before = l.calls.size();
            LibraryData bad = new LibraryReader("127.0.0.1:" + fake.port(), FakeLibraries.USER,
                    FakeLibraries.PASSWORD, tls, new T.RecLog()).read(Long.MAX_VALUE, () -> NOW);
            T.check(bad.listFailure != null, "the name is checked");
            T.eq(before, l.calls.size(), "and nothing was sent");
        }
    }

    static void session(FakeVc fake) throws Exception {
        FakeLibraries l = libraries();
        try (Rig r = new Rig(fake, vcenter(), l)) {
            r.run();
            T.eq(1, l.logins.get(), "one login per read");
            T.eq(1, l.logouts.get(), "one logout per read");
            T.eq("Basic " + java.util.Base64.getEncoder().encodeToString((FakeLibraries.USER + ":"
                    + FakeLibraries.PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)), l.authorizations.get(0),
                    "the account's own credential, once");
            T.eq(1, l.authorizations.size(), "the password is sent once");
            for (String s : l.sessionsSeen) T.eq(FakeLibraries.SESSION, s, "every read carries the session");
            T.check(l.sessionsSeen.size() > 10, "and there were reads");
            T.notContains(r.log.all(), FakeLibraries.PASSWORD, "log");
            T.notContains(r.log.all(), FakeLibraries.SESSION, "log");
            T.notContains(r.log.all(), java.util.Base64.getEncoder().encodeToString((FakeLibraries.USER + ":"
                    + FakeLibraries.PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "log");
            // a second cycle makes its own session
            r.run();
            T.eq(2, l.logins.get(), "a new session each cycle");
            T.eq(2, l.logouts.get(), "closed each cycle");
        }
        // a client that is closed without a session does nothing
        RestClient c = new RestClient("localhost:" + fake.port(), "u", "p", TlsAndSessionTest.tofu(), null);
        int before = l.calls.size();
        c.close();
        T.eq(before, l.calls.size(), "closing without a session sends nothing");
        RestResult none = c.get("/content/library");
        T.check(!none.isOk(), "no read without a session");
        T.eq(before, l.calls.size(), "and sends nothing");
    }

    // ------------------------------------------------------------------ the switch

    static void quirkSeam() {
        T.eq(null, Quirks.ownText(null, false), "not returned: nothing when the switch is off (DV-17)");
        T.eq("", Quirks.ownText(null, true), "not returned: the empty text, as the original, when the switch is on (Q8)");
        T.eq("", Quirks.ownText("", false), "returned and empty: as it is (Q4)");
        T.eq("  ", Quirks.ownText("  ", true), "returned and blank: as it is (Q4)");
        T.eq("x", Quirks.ownText("x", false), "a value");
        T.check(Quirks.ABSENT_OWN_TEXT_IS_EMPTY, "the shipped setting is the match (Q8)");
    }
}
