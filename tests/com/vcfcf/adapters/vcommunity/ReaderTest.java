package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.BulkRow;
import com.vcfcf.adapters.vcommunity.vim.PathSpec;
import com.vcfcf.adapters.vcommunity.vim.PropertyReader;
import com.vcfcf.adapters.vcommunity.vim.RetrieveRequest;
import com.vcfcf.adapters.vcommunity.vim.RetrieveStats;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The streamed property collector read: paging, what is kept of a value, what an absent
 * path, an empty array and a missing-set entry each look like, refused paths, a failure
 * on any page, size and depth limits, and a hostile document.
 */
public final class ReaderTest {

    static VimSoapClient client(FakeVc fake, FakeVcenter vc) {
        fake.setHandler(vc);
        VimSoapClient c = new VimSoapClient("localhost:" + fake.port(), "u", "p",
                VcenterTls.create(true, null, null), new T.RecLog(),
                5000, 20000, 20000);
        T.check(c.ensureSession().isOk(), "login");
        return c;
    }

    public static void main(String[] args) throws Exception {
        pathSpec();
        try (FakeVc fake = new FakeVc(FakeVc.happy())) {
            absentEmptyMissing(fake);
            paging(fake);
            refused(fake);
            limits(fake);
            hostile(fake);
            relogin(fake);
        }
    }

    static void pathSpec() throws Exception {
        PathSpec s = PathSpec.keeping("a.b", "c");
        T.check(s.keepPath("a") && s.keepPath("a.b") && s.keepPath("a.b.x") && s.keepPath("c") && s.keepPath("c.d"),
                "a path, its parents and its descendants are kept");
        T.check(!s.keepPath("a.z") && !s.keepPath("z") && !s.keepPath("cc"), "others are not");
        PathSpec arr = PathSpec.keepingInElements("device", "x.y");
        T.check(arr.keepPath("PhysicalNic") && arr.keepPath("PhysicalNic.device") && arr.keepPath("PhysicalNic.x")
                && arr.keepPath("PhysicalNic.x.y") && !arr.keepPath("PhysicalNic.other"),
                "the element's own name is not part of the path");
        // pruning builds only what is kept, and counts it
        String xml = "<val xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"ArrayOfT\">"
                + "<T><device>d1</device><junk><deep><deeper>1</deeper></deep></junk><x><y>1</y><z>2</z></x></T>"
                + "<T><device>d2</device></T></val>";
        javax.xml.stream.XMLStreamReader r = XmlNode.reader(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        while (r.next() != javax.xml.stream.XMLStreamConstants.START_ELEMENT) { /* to the root */ }
        int[] n = new int[1];
        XmlNode v = XmlNode.readElement(r, arr, n);
        T.eq(2, v.children().size(), "two elements");
        T.check(v.children().get(0).child("junk") == null && v.children().get(0).child("x").child("z") == null, "junk skipped");
        T.eq("1", v.children().get(0).child("x").childText("y"), "the kept path");
        T.eq(7, n[0], "only kept elements are counted: root, T, device, x, y, T, device");
        // an element filter drops a finished element and uncounts it
        PathSpec only = PathSpec.elements((Predicate<XmlNode>) e -> "keep".equals(e.childText("key")));
        String xml2 = "<val><o><key>drop</key><value>1</value></o><o><key>keep</key><value>2</value></o></val>";
        javax.xml.stream.XMLStreamReader r2 = XmlNode.reader(new ByteArrayInputStream(xml2.getBytes(StandardCharsets.UTF_8)));
        while (r2.next() != javax.xml.stream.XMLStreamConstants.START_ELEMENT) { /* root */ }
        int[] n2 = new int[1];
        XmlNode v2 = XmlNode.readElement(r2, only, n2);
        T.eq(1, v2.children().size(), "one element kept");
        T.eq(4, n2[0], "its three nodes and the root");
        T.done("reader: what is kept of a value");
    }

    static void absentEmptyMissing(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        vc.add(new SoapFx.Obj("HostSystem", "host-1").name("a")
                .put("config.network.pnic", SoapFx.arr("ArrayOfPhysicalNic", "")) // returned, empty
                .put("configStatus", SoapFx.en("ManagedEntityStatus", "green")));
        vc.add(new SoapFx.Obj("HostSystem", "host-2").name("b")); // nothing returned
        vc.add(new SoapFx.Obj("HostSystem", "host-3").name("c").missing("configStatus")); // named in the missing set
        try (VimSoapClient c = client(fake, vc)) {
            List<BulkRow> rows = new ArrayList<>();
            VimResult<RetrieveStats> r = new PropertyReader(c).retrieve(new RetrieveRequest("R-HO", "HostSystem", 10, null)
                    .path("config.network.pnic").path("configStatus"), Collections.<String>emptySet(), rows::add);
            T.check(r.isOk(), "ok: " + r.message());
            T.eq(3, rows.size(), "three hosts");
            T.check(rows.get(0).has("config.network.pnic") && rows.get(0).val("config.network.pnic").children().isEmpty(),
                    "an empty array is returned, with no elements");
            T.check(!rows.get(1).has("config.network.pnic") && !rows.get(1).has("configStatus"), "an unset path is absent");
            T.check(!rows.get(2).has("configStatus") && rows.get(2).missing().contains("configStatus"),
                    "a missing-set path is absent and listed");
            T.eq("a", rows.get(0).name(), "the name is always read");
            T.eq(1, vc.destroyed.get(), "the view is destroyed");
            T.eq(1, vc.views.get(), "one view per read");
        }
        // no objects at all: an answer with no result
        FakeVcenter none = new FakeVcenter();
        try (VimSoapClient c = client(fake, none)) {
            int[] n = new int[1];
            VimResult<RetrieveStats> r = new PropertyReader(c).retrieve(new RetrieveRequest("R-HO", "HostSystem", 10, null)
                    .path("configStatus"), Collections.<String>emptySet(), row -> n[0]++);
            T.check(r.isOk() && n[0] == 0 && r.value().rows == 0, "no objects is an empty, successful read");
        }
        T.done("reader: absent, empty and missing paths");
    }

    static void paging(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        for (int i = 0; i < 130; i++) {
            vc.add(new SoapFx.Obj("Datastore", "datastore-" + i).name("d" + i)
                    .put("overallStatus", SoapFx.en("ManagedEntityStatus", "green")));
        }
        try (VimSoapClient c = client(fake, vc)) {
            Set<String> seen = new HashSet<>();
            VimResult<RetrieveStats> r = new PropertyReader(c).retrieve(new RetrieveRequest("R-DS", "Datastore", 25, null)
                    .path("overallStatus"), Collections.<String>emptySet(), row -> seen.add(row.obj.value));
            T.check(r.isOk(), "ok");
            T.eq(130, seen.size(), "every object once");
            T.eq(6, r.value().pages, "in pages of 25");
            // a failure on the third page fails the read, and the caller learns that rows were already delivered
            vc.resetContinues();
            vc.failContinueAt.put("Datastore", 2);
            List<String> delivered = new ArrayList<>();
            VimResult<RetrieveStats> bad = new PropertyReader(c).retrieve(new RetrieveRequest("R-DS", "Datastore", 25, null)
                    .path("overallStatus"), Collections.<String>emptySet(), row -> delivered.add(row.obj.value));
            T.check(!bad.isOk(), "the read failed");
            T.check(bad.message() != null && bad.message().contains("could not be produced"), "with the server's reason: " + bad.message());
            T.check(delivered.size() == 50, "two pages had been delivered: " + delivered.size());
            T.eq(2, vc.destroyed.get(), "the view is destroyed even then");
        }
        T.done("reader: pages and a failed page");
    }

    static void refused(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        vc.add(new SoapFx.Obj("VirtualMachine", "vm-1").name("v").put("configStatus", SoapFx.en("ManagedEntityStatus", "green")));
        for (int i = 0; i < 25; i++) vc.invalid.add("bad.path" + i);
        try (VimSoapClient c = client(fake, vc)) {
            RetrieveRequest req = new RetrieveRequest("R-VM", "VirtualMachine", 10, null).path("configStatus");
            for (int i = 0; i < 3; i++) req.path("bad.path" + i);
            List<BulkRow> rows = new ArrayList<>();
            VimResult<RetrieveStats> r = new PropertyReader(c).retrieve(req, Collections.<String>emptySet(), rows::add);
            T.check(r.isOk(), "refused paths are dropped");
            T.eq(3, r.value().droppedPaths().size(), "three of them");
            T.check(rows.get(0).has("configStatus"), "the valid one is read");
            // paths known to be invalid are not asked for
            int before = vc.pages.get();
            Set<String> known = new HashSet<>(r.value().droppedPaths());
            VimResult<RetrieveStats> again = new PropertyReader(c).retrieve(req, known, rows::add);
            T.check(again.isOk() && again.value().droppedPaths().isEmpty() && again.value().pathsKnownInvalid == 3, "known paths skipped");
            T.eq(before + 1, vc.pages.get(), "one call, no fault round trips");
            // more than twenty refusals: the read fails with the fault
            RetrieveRequest many = new RetrieveRequest("R-VM", "VirtualMachine", 10, null).path("configStatus");
            for (int i = 0; i < 25; i++) many.path("bad.path" + i);
            int reqsBefore = fake.seen("RetrievePropertiesEx").size();
            VimResult<RetrieveStats> fail = new PropertyReader(c).retrieve(many, Collections.<String>emptySet(), row -> { });
            T.check(!fail.isOk() && "InvalidProperty".equals(fail.faultClass()), "twenty drops at most");
            T.check(fail.invalidPropertyPath().startsWith("bad.path"), "the refused path is reported");
            T.eq(21, fake.seen("RetrievePropertiesEx").size() - reqsBefore, "the first call and twenty repeats");
            // an invalid property that is not one of ours (a name we did not ask for) is a failure
            FakeVcenter odd = new FakeVcenter();
            odd.add(new SoapFx.Obj("VirtualMachine", "vm-1"));
            odd.forcedType.put("VirtualMachine", FakeVcenter.invalidProperty("something.else"));
            fake.setHandler(odd);
            VimResult<RetrieveStats> o = new PropertyReader(c).retrieve(new RetrieveRequest("R-VM", "VirtualMachine", 10, null)
                    .path("configStatus"), Collections.<String>emptySet(), row -> { });
            T.check(!o.isOk() && "InvalidProperty".equals(o.faultClass()), "a refusal of a path we did not ask for fails the read");
        }
        T.done("reader: refused paths");
    }

    static void limits(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        for (int i = 0; i < 40; i++) {
            vc.add(new SoapFx.Obj("Datastore", "datastore-" + i).name("d" + i)
                    .put("overallStatus", SoapFx.en("ManagedEntityStatus", "green")));
        }
        try (VimSoapClient c = client(fake, vc)) {
            VimResult<RetrieveStats> r = new PropertyReader(c, 1500).retrieve(
                    new RetrieveRequest("R-DS", "Datastore", 40, null).path("overallStatus"),
                    Collections.<String>emptySet(), row -> { });
            T.check(!r.isOk() && "TooLarge".equals(r.faultClass()), "a page over the cap fails: " + r.faultClass());
            // nesting beyond the limit is refused, not followed
            StringBuilder deep = new StringBuilder();
            for (int i = 0; i < 40; i++) deep.append("<a>");
            for (int i = 0; i < 40; i++) deep.append("</a>");
            FakeVcenter nested = new FakeVcenter();
            nested.add(new SoapFx.Obj("Datastore", "datastore-1").name("x").put("info", SoapFx.obj("T", deep.toString())));
            fake.setHandler(nested);
            VimResult<RetrieveStats> d = new PropertyReader(c).retrieve(new RetrieveRequest("R-DS", "Datastore", 10, null)
                    .path("info"), Collections.<String>emptySet(), row -> { });
            T.check(!d.isOk() && "Parse".equals(d.faultClass()), "a value nested too deep fails the read: " + d.faultClass());
            // the token may come after the objects
            FakeVcenter late = new FakeVcenter();
            for (int i = 0; i < 4; i++) late.add(new SoapFx.Obj("Datastore", "datastore-" + i).name("n"));
            late.rewrite.put("Datastore", s -> s.replaceFirst("(<token>[^<]*</token>)(.*)(</returnval>)", "$2$1$3"));
            fake.setHandler(late);
            Set<String> seen = new HashSet<>();
            VimResult<RetrieveStats> t = new PropertyReader(c).retrieve(new RetrieveRequest("R-DS", "Datastore", 2, null)
                    .path("overallStatus"), Collections.<String>emptySet(), row -> seen.add(row.obj.value));
            T.check(t.isOk() && seen.size() == 4, "the token is found wherever it is: " + seen.size());
        }
        T.done("reader: size, depth and token position");
    }

    static void hostile(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        vc.add(new SoapFx.Obj("Datastore", "datastore-1").name("x"));
        vc.rewrite.put("Datastore", s -> s.replace("<RetrievePropertiesExResponse",
                "<!DOCTYPE r [<!ENTITY boom \"BOOM\">]><RetrievePropertiesExResponse").replace("datastore-1", "&boom;"));
        try (VimSoapClient c = client(fake, vc)) {
            List<String> morefs = new ArrayList<>();
            VimResult<RetrieveStats> r = new PropertyReader(c).retrieve(new RetrieveRequest("R-DS", "Datastore", 10, null)
                    .path("overallStatus"), Collections.<String>emptySet(), row -> morefs.add(row.obj.value));
            T.check(morefs.isEmpty() || !morefs.get(0).contains("BOOM"), "an entity declared in the document is never expanded");
        }
        T.done("reader: a document type declaration is not honoured");
    }

    static void relogin(FakeVc fake) throws Exception {
        FakeVcenter vc = new FakeVcenter();
        vc.add(new SoapFx.Obj("Datastore", "datastore-1").name("x").put("overallStatus", SoapFx.en("ManagedEntityStatus", "green")));
        try (VimSoapClient c = client(fake, vc)) {
            // the session expires: the first retrieve is refused as NotAuthenticated, the client logs in once more
            int[] calls = new int[1];
            fake.setHandler(r -> {
                if (r.action.equals("RetrievePropertiesEx") && calls[0]++ == 0) {
                    return new FakeVc.Resp(500, null, SoapFx.envelope("<soapenv:Fault><faultcode>ServerFaultCode</faultcode>"
                            + "<faultstring>session</faultstring><detail><NotAuthenticatedFault xmlns=\"urn:vim25\" "
                            + "xsi:type=\"NotAuthenticated\"/></detail></soapenv:Fault>"));
                }
                return vc.handle(r);
            });
            List<String> got = new ArrayList<>();
            VimResult<RetrieveStats> r = new PropertyReader(c).retrieve(new RetrieveRequest("R-DS", "Datastore", 10, null)
                    .path("overallStatus"), Collections.<String>emptySet(), row -> got.add(row.obj.value));
            T.check(r.isOk() && got.size() == 1, "one new login, and the read goes through: " + r.message());
        }
        T.done("reader: a streamed call logs in again once");
    }
}
