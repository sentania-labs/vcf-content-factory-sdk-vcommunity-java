package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.ops.OpsHttpException;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** The cycle skeleton and Test Connection, against fakes. */
public final class EngineTest {

    static final String VCID = "VC-UUID-1";
    static final String HEALTH = "vCommunity|Collection Health|";

    static final class FakeVim implements VimSession {
        VimResult<About> next = VimResult.ok(new About(VCID, "VMware vCenter Server 8.0.3", "8.0.3", "8.0.3.0", "1", null), 10);
        int cycles;
        boolean closed;

        @Override public void beginCycle() { cycles++; }
        @Override public VimResult<About> ensureSession() { return next; }
        @Override public void close() { closed = true; }
    }

    /** A Suite API with one vCenter object and scriptable check-list files. */
    static final class FakeOps implements OpsApi {
        final Map<String, Object> files = new HashMap<>(); // name -> body | Exception
        String vcenterRows = OpsTest.page(1, OpsTest.row("vc-res-1", null, VCID));
        IOException resourceFailure;
        RuntimeException resourceRuntime;
        IOException versionFailure;
        PushOutcome outcome = new PushOutcome(2, 1, 0, 0, 0, false);
        final List<Map<String, Map<String, String>>> pushedProps = new ArrayList<>();
        final List<Map<String, Map<String, Double>>> pushedStats = new ArrayList<>();
        final List<Long> pushedAt = new ArrayList<>();
        final List<String> paths = new ArrayList<>();

        @Override
        public String get(String path, String accept) throws IOException {
            paths.add(path);
            if (path.startsWith("/api/versions/current")) {
                if (versionFailure != null) throw versionFailure;
                return "{}";
            }
            if (path.startsWith("/api/resources?")) {
                if (resourceFailure != null) throw resourceFailure;
                if (resourceRuntime != null) throw resourceRuntime;
                return path.endsWith("page=0") ? vcenterRows : OpsTest.page(0);
            }
            if (path.startsWith("/api/configurations/files?path=SolutionConfig/")) {
                String name = path.substring(path.indexOf("SolutionConfig/") + 15, path.length() - 4);
                Object r = files.get(name);
                if (r instanceof IOException) throw (IOException) r;
                if (r == null) throw new OpsHttpException("missing", 404, null);
                return (String) r;
            }
            throw new IOException("unexpected " + path);
        }

        @Override
        public PushOutcome pushBatch(Map<String, Map<String, String>> properties,
                Map<String, Map<String, Double>> stats, long ts) {
            pushedProps.add(properties);
            pushedStats.add(stats);
            pushedAt.add(ts);
            return outcome;
        }

        int pushes() {
            return pushedAt.size();
        }
    }

    static VCommunityConfig cfg(String... kv) {
        Map<String, String> m = new HashMap<>();
        m.put("host", "vc.example.test");
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return VCommunityConfig.parse(m::get, "u", "p", null, null);
    }

    static final class Rig {
        final FakeVim vim = new FakeVim();
        final FakeOps ops = new FakeOps();
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        final T.RecLog log = new T.RecLog();
        final WarnCounter warns = new WarnCounter(log);
        ThumbprintProbe.Probe probe = (h, p, t) -> ThumbprintProbe.Result.found("ab".repeat(32));
        CycleEngine engine;
        boolean noOps;

        Rig(VCommunityConfig c) {
            ops.files.put("esxi_advanced_system_settings", "<L>Net.A</L>");
            ops.files.put("esxi_packages", "<L></L>");
            ops.files.put("vm_advanced_parameters", "<L>x</L>");
            ops.files.put("vm_options", "<L>config.version</L>");
            engine = new CycleEngine(c, vim, (h, p, t) -> probe.probe(h, p, t), ops,
                    new CheckListStore(warns), warns, clock::get);
        }

        Rig(VCommunityConfig c, boolean withoutOps) {
            ops.files.put("esxi_advanced_system_settings", "<L>Net.A</L>");
            engine = new CycleEngine(c, vim, (h, p, t) -> probe.probe(h, p, t), null,
                    new CheckListStore(warns), warns, clock::get);
            noOps = true;
        }

        CycleEngine.Result run() throws Exception {
            return engine.run(clock.get() + 240_000);
        }
    }

    public static void main(String[] args) throws Exception {
        happy();
        pushOnChange();
        loginFailure();
        vcenterObjectMissing();
        suiteApiMissing();
        checkListProblems();
        pushFailure();
        probeFailure();
        deadline();
        gatedLists();
        tester();
        cycleSummaryLine();
    }

    static void happy() throws Exception {
        Rig r = new Rig(cfg());
        long t0 = r.clock.get();
        CycleEngine.Result res = r.run();
        T.check(res.failure == null, "cycle ok");
        T.eq(VCID, res.about.instanceUuid, "about");
        T.eq("ab".repeat(32), res.presentedThumbprint, "presented thumbprint kept");
        T.eq(1, r.ops.pushes(), "one push");
        T.eq(t0, (long) r.ops.pushedAt.get(0), "every value carries the cycle start");
        Map<String, Double> stats = r.ops.pushedStats.get(0).get("vc-res-1");
        T.check(stats != null, "pushed onto the vCenter object");
        T.eq(0.0, stats.get(HEALTH + "Warnings in Last Cycle"), "no warnings");
        T.eq(0.0, stats.get(HEALTH + "Reads Failed in Last Cycle"), "no failed reads");
        T.eq(0.0, stats.get(HEALTH + "Objects Not Matched"), "all matched");
        T.eq("OK", r.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Status"), "check lists ok");
        T.eq(0.0, stats.get(HEALTH + "Collector:check_lists|Failures"), "no check-list failures");
        T.eq("None in the last cycle", r.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "First Failure"), "none");
        Map<String, Double> m = res.metrics();
        T.eq(1.0, m.get("Collection|vcenter_object_resolved"), "resolved");
        T.eq(0.0, m.get("Collection|reads_failed"), "reads failed");
        T.check(m.containsKey("Collection|series_pushed") && m.containsKey("Collection|properties_pushed"), "counts sent when clean");
        T.eq(0.0, m.get("Collection|push_batches_failed"), "no failed batches");
        T.eq("None in the last cycle", res.lastErrorProperty(), "no error");
        T.eq(4, res.lists.size(), "the four lists were read");
        T.eq(1, r.vim.cycles, "session told the cycle began");
        for (Map<String, Double> per : r.ops.pushedStats.get(0).values()) {
            for (Double d : per.values()) T.check(d != null && !d.isNaN() && !d.isInfinite(), "finite");
        }
        T.done("engine: a clean cycle");
    }

    static void pushOnChange() throws Exception {
        Rig r = new Rig(cfg());
        r.run();
        int firstProps = r.ops.pushedProps.get(0).get("vc-res-1").size();
        T.check(firstProps >= 3, "first cycle sends the properties");
        r.clock.addAndGet(300_000);
        r.run();
        Map<String, Map<String, String>> second = r.ops.pushedProps.get(1);
        T.check(second.isEmpty(), "second cycle: no property changed, none sent: " + second);
        T.check(!r.ops.pushedStats.get(1).isEmpty(), "stats are sent every cycle");
        // a failure shows up in the property, which then changes
        r.ops.files.put("vm_options", new java.net.ConnectException("x"));
        r.clock.addAndGet(300_000);
        r.run();
        Map<String, String> third = r.ops.pushedProps.get(2).get("vc-res-1");
        T.check(third != null && third.containsKey(HEALTH + "First Failure"), "changed property sent: " + third);
        // every 12th cycle everything is sent again
        for (int i = 4; i < 12; i++) {
            r.clock.addAndGet(300_000);
            r.run();
        }
        r.clock.addAndGet(300_000);
        r.run(); // cycle 12
        T.check(r.ops.pushedProps.get(11).get("vc-res-1").size() >= firstProps, "the 12th cycle refreshes all properties");
        T.done("engine: properties on change, stats every cycle, full refresh every 12th");
    }

    static void loginFailure() throws Exception {
        Rig r = new Rig(cfg());
        r.vim.next = VimResult.fault("InvalidLogin", "login failed: vCenter rejected the user name or password", null, 0, null);
        CycleEngine.Result res = r.run();
        T.contains(res.failure, "rejected the user name or password", "the cycle fails with the reason");
        T.eq(0, r.ops.pushes(), "nothing is pushed without a VCID");
        T.eq(0, r.ops.paths.size(), "the Suite API is not even asked");
        Map<String, Double> m = res.metrics();
        T.eq(1.0, m.get("Collection|reads_failed"), "the login is a failed read");
        T.eq(0.0, m.get("Collection|vcenter_object_resolved"), "not resolved");
        T.contains(res.lastErrorProperty(), "F-LOGIN", "last error names the cause id");
        T.notContains(res.lastErrorProperty(), "password=", "no secret");
        T.check(!m.containsKey("Collection|series_pushed"), "nothing pushed, nothing claimed");
        T.check(!m.containsKey("Collection|objects_unmatched"), "the lookup never ran: no count");
        T.check(!m.containsKey("Collection|checklist_entries_skipped"), "the lists were never read: no count");
        // an unreachable vCenter keeps its cause so the adapter can map DOWN
        Throwable cause = new java.net.ConnectException("refused");
        r.vim.next = VimResult.fault("Transport", "the vCenter refused or did not accept the connection", null, 0, cause);
        T.check(r.run().failureCause == cause, "cause kept");
        T.done("engine: a failed login fails the cycle and sends nothing");
    }

    static void vcenterObjectMissing() throws Exception {
        Rig r = new Rig(cfg());
        r.ops.vcenterRows = OpsTest.page(1, OpsTest.row("other", null, "SOME-OTHER-VC"));
        CycleEngine.Result res = r.run();
        T.check(res.failure == null, "the cycle still succeeds");
        Map<String, Double> m = res.metrics();
        T.eq(0.0, m.get("Collection|vcenter_object_resolved"), "not resolved");
        T.eq(1.0, m.get("Collection|objects_unmatched"), "counted");
        T.eq(0, r.ops.pushes(), "no vCenter keys pushed: no guess");
        T.check(r.log.warns.stream().anyMatch(s -> s.contains("carries this vCenter's instance UUID") || s.contains("carry this vCenter's")), "warned");
        T.check(!m.containsKey("Collection|series_pushed"), "no push, no claim");

        r.ops.vcenterRows = OpsTest.page(2, OpsTest.row("a", null, VCID), OpsTest.row("b", null, VCID));
        r.clock.addAndGet(300_000);
        // the map was not found before, so it is read again at once
        CycleEngine.Result dup = r.run();
        T.eq(0.0, dup.metrics().get("Collection|vcenter_object_resolved"), "two objects: not guessed");
        T.eq(1.0, dup.metrics().get("Collection|objects_unmatched"), "counted");
        T.check(r.log.warns.stream().anyMatch(s -> s.contains("2 VCF Operations objects")), "duplicate account named");

        // no UUID from the vCenter: nothing can match
        Rig noUuid = new Rig(cfg());
        noUuid.vim.next = VimResult.ok(new About(null, "vc", "8", "8", "1", null), 1);
        CycleEngine.Result nu = noUuid.run();
        T.check(nu.failure == null, "ok");
        T.eq(0, noUuid.ops.pushes(), "no UUID, no push");
        T.eq(0.0, nu.metrics().get("Collection|vcenter_object_resolved"), "not resolved");
        // a failed lookup is a failed read, not an unmatched object, and nothing in the stage can end the cycle
        Rig lf = new Rig(cfg());
        lf.ops.resourceFailure = new java.net.SocketTimeoutException("slow");
        CycleEngine.Result lr = lf.run();
        T.check(lr.failure == null, "cycle survives");
        T.check(!lr.metrics().containsKey("Collection|objects_unmatched"), "a failed lookup measured nothing");
        T.contains(lr.lastErrorProperty(), "F-OPS-MAP-VMwareAdapter Instance", "counted as a failed read");
        Rig bug = new Rig(cfg());
        bug.ops.resourceFailure = new IOException("x") { };
        CycleEngine.Result br = bug.run();
        T.check(br.failure == null, "ok");
        Rig rt = new Rig(cfg());
        rt.ops.vcenterRows = "{ not json";
        T.check(rt.run().failure == null, "a malformed answer does not end the cycle");
        Rig rx = new Rig(cfg());
        rx.ops.resourceRuntime = new IllegalStateException("bug");
        CycleEngine.Result xr = rx.run();
        T.check(xr.failure == null, "a runtime exception in the lookup does not end the cycle");
        T.contains(xr.lastErrorProperty(), "F-OPS-MAP", "counted");
        T.check(!xr.metrics().containsKey("Collection|objects_unmatched"), "unmeasured");
        T.done("engine: the vCenter object is matched by UUID or the keys are held back and counted");
    }

    static void suiteApiMissing() throws Exception {
        Rig r = new Rig(cfg(), true);
        CycleEngine.Result res = r.run();
        T.check(res.failure == null, "the cycle survives a missing Suite API client");
        T.contains(res.lastErrorProperty(), "F-CHECKLIST", "says check lists could not be read");
        T.eq(0.0, res.metrics().get("Collection|vcenter_object_resolved"), "not resolved");
        T.check(res.lists.isEmpty(), "no lists read");
        T.check(!res.metrics().containsKey("Collection|objects_unmatched"), "no lookup, no count");
        T.check(!res.metrics().containsKey("Collection|checklist_entries_skipped"), "no lists, no count");
        T.done("engine: no Suite API client is reported, not hidden");
    }

    static void checkListProblems() throws Exception {
        Rig r = new Rig(cfg());
        r.ops.files.put("vm_options", "<L>config.version, bad path, a[0]</L>");
        r.ops.files.put("esxi_packages", new OpsHttpException("x", 404, null));
        CycleEngine.Result res = r.run();
        Map<String, Double> m = res.metrics();
        T.eq(2.0, m.get("Collection|checklist_entries_skipped"), "two entries skipped");
        T.eq(1.0, m.get("Collection|reads_failed"), "only the absent file is a failed read; skipped entries are not");
        T.eq("PARTIAL", r.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Status"), "partial");
        T.eq(1.0, r.ops.pushedStats.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Failures"), "failures");
        T.eq(2.0, r.ops.pushedStats.get(0).get("vc-res-1").get(HEALTH + "Check-List Entries Skipped"), "skipped on the vCenter");
        T.check(r.warns.count() >= 2, "the problems were warned about");
        T.eq(2, res.summary.warnings, "warnings counted: one line per cause");

        Rig all = new Rig(cfg());
        all.ops.files.clear();
        CycleEngine.Result none = all.run();
        T.eq("FAILED", all.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Status"), "every file failed");
        T.eq(4.0, none.metrics().get("Collection|reads_failed"), "four failed reads");
        T.eq(0.0, none.metrics().get("Collection|checklist_entries_skipped"), "absent files hold no entries: a measured zero");

        // a skipped entry alone is not a failed read and does not make the stage FAILED
        Rig only = new Rig(cfg());
        only.ops.files.put("vm_options", "<L>bad path</L>");
        CycleEngine.Result os = only.run();
        T.eq(0.0, os.metrics().get("Collection|reads_failed"), "no failed read");
        T.eq(1.0, os.metrics().get("Collection|checklist_entries_skipped"), "one skipped");
        T.eq("PARTIAL", only.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Status"), "partial, not FAILED");

        // files that could not be read leave the skipped count unknown, and nothing is sent for it
        Rig down = new Rig(cfg());
        for (String k : new String[] {"esxi_advanced_system_settings", "esxi_packages", "vm_advanced_parameters", "vm_options"}) {
            down.ops.files.put(k, new java.net.SocketTimeoutException("slow"));
        }
        CycleEngine.Result dr = down.run();
        T.eq(4.0, dr.metrics().get("Collection|reads_failed"), "four failed reads");
        T.check(!dr.metrics().containsKey("Collection|checklist_entries_skipped"), "unknown, not 0 (self)");
        T.check(!down.ops.pushedStats.get(0).get("vc-res-1").containsKey(HEALTH + "Check-List Entries Skipped"), "unknown, not 0 (vCenter)");
        T.eq("FAILED", down.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Status"), "nothing in force: FAILED");
        // with last good lists in force it is PARTIAL
        down.ops.files.put("esxi_advanced_system_settings", "<L>a</L>");
        down.ops.files.put("esxi_packages", "<L>a</L>");
        down.ops.files.put("vm_advanced_parameters", "<L>a</L>");
        down.ops.files.put("vm_options", "<L>a</L>");
        down.clock.addAndGet(300_000);
        down.run();
        for (String k : new String[] {"esxi_advanced_system_settings", "esxi_packages", "vm_advanced_parameters", "vm_options"}) {
            down.ops.files.put(k, new java.net.SocketTimeoutException("slow"));
        }
        down.clock.addAndGet(300_000);
        down.run();
        T.eq("PARTIAL", down.ops.pushedProps.get(2).get("vc-res-1").getOrDefault(HEALTH + "Collector:check_lists|Status", "unchanged"), "last good in force: PARTIAL, not FAILED");
        T.done("engine: check-list problems are counted and the entries skipped are reported");
    }

    static void pushFailure() throws Exception {
        Rig r = new Rig(cfg());
        r.ops.outcome = new PushOutcome(1, 0, 1, 0, 0, false);
        CycleEngine.Result res = r.run();
        Map<String, Double> m = res.metrics();
        T.eq(1.0, m.get("Collection|push_batches_failed"), "failed batch counted");
        T.check(!m.containsKey("Collection|series_pushed"), "an unclean push does not claim a count");
        T.check(!m.containsKey("Collection|properties_pushed"), "nor for properties");
        T.contains(res.lastErrorProperty(), "F-PUSH", "cause named");
        // the properties were not committed, so they are sent again
        r.ops.outcome = new PushOutcome(2, 1, 0, 0, 0, false);
        r.clock.addAndGet(300_000);
        r.run();
        T.check(!r.ops.pushedProps.get(1).isEmpty(), "uncommitted properties are resent");
        // a skipped (not failed) outcome is also not clean
        Rig s = new Rig(cfg());
        s.ops.outcome = new PushOutcome(0, 0, 0, 1, 0, true);
        CycleEngine.Result sk = s.run();
        T.check(!sk.metrics().containsKey("Collection|series_pushed"), "stopped push claims nothing");
        T.eq(0.0, sk.metrics().get("Collection|push_batches_failed"), "no failed batch, one stopped");
        T.contains(sk.lastErrorProperty(), "F-PUSH", "still a failure");
        T.done("engine: a push that was not clean is counted and retried");
    }

    static void probeFailure() throws Exception {
        Rig r = new Rig(cfg());
        r.probe = (h, p, t) -> ThumbprintProbe.Result.failed("SocketTimeoutException during the TLS handshake");
        CycleEngine.Result res = r.run();
        T.check(res.failure == null, "a failed probe does not fail the cycle");
        T.check(res.presentedThumbprint == null, "no thumbprint is invented");
        T.contains(res.lastErrorProperty(), "F-CERT-PROBE", "counted");
        T.eq(1.0, res.metrics().get("Collection|reads_failed"), "one failed read");
        T.done("engine: a failed certificate probe is counted, never replaced");
    }

    static void deadline() throws Exception {
        Rig r = new Rig(cfg());
        CycleEngine.Result res = r.engine.run(r.clock.get() - 1); // already past
        T.check(res.failure == null, "ok");
        T.eq(1.0, res.metrics().get("Collection|deadline_hit"), "deadline hit");
        T.check(res.lists.isEmpty(), "nothing read after the deadline");
        T.contains(res.lastErrorProperty(), "F-DEADLINE", "named");
        T.check(!res.metrics().containsKey("Collection|objects_unmatched"), "deadline: no lookup, no count");
        T.check(!res.metrics().containsKey("Collection|checklist_entries_skipped"), "deadline: no lists, no count");
        T.eq(0, r.ops.paths.size(), "nothing asked of the Suite API");
        T.done("engine: the deadline stops new work and says so");
    }

    static void gatedLists() throws Exception {
        Rig r = new Rig(cfg());
        r.run();
        for (String p : r.ops.paths) {
            T.check(!p.contains("windows_service_list") && !p.contains("windows_event_list"), "guest lists not read: " + p);
        }
        Rig on = new Rig(cfg("serviceMonitoring", "Enabled", "winEventMonitoring", "Enabled"));
        on.ops.files.put("windows_service_list", "<L>Dhcp</L>");
        on.ops.files.put("windows_event_list", "<Events><Log Name=\"System\"><EventID>1</EventID></Log></Events>");
        CycleEngine.Result res = on.run();
        T.eq(6, res.lists.size(), "six lists with both switches on");
        T.eq("OK", on.ops.pushedProps.get(0).get("vc-res-1").get(HEALTH + "Collector:check_lists|Status"), "all fine");
        T.done("engine: the guest lists are read only when their switch is on");
    }

    /** The one INFO line a cycle leaves (finding 1 of the 0.0.0.2 live run). */
    static void cycleSummaryLine() throws Exception {
        Rig r = new Rig(cfg());
        r.ops.files.put("vm_options", "<L>config.version</L>");
        r.run();
        long n = r.log.infos.stream().filter(l -> l.startsWith("Collection cycle ")).count();
        T.eq(1L, n, "one summary line per cycle: " + r.log.infos);
        String line = r.log.infos.stream().filter(l -> l.startsWith("Collection cycle ")).findFirst().get();
        T.contains(line, "Collection cycle 1 finished in 0.0 s:", "number and duration");
        T.contains(line, "vCenter object matched=yes", "objects");
        T.contains(line, "objects not matched=0", "unmatched");
        T.contains(line, "series pushed=", "series");
        T.contains(line, "reads failed=0", "reads failed");
        T.notContains(line, "n/a", "everything was measured in a clean cycle: " + line);
        r.run();
        T.contains(r.log.infos.get(r.log.infos.size() - 1), "Collection cycle 2 finished", "the second cycle counts on");

        // a failed login still leaves its line, with the credential-free reason
        Rig f = new Rig(cfg());
        f.vim.next = VimResult.fault("InvalidLogin", "login failed: vCenter rejected the user name or password", null, 0, null);
        f.run();
        String fl = f.log.infos.get(f.log.infos.size() - 1);
        T.contains(fl, "Collection cycle 1 failed after", "failure line");
        T.contains(fl, "rejected the user name or password", "why");
        T.contains(fl, "series pushed=n/a", "unmeasured is said, not zero");
        T.done("engine: one summary line per cycle at INFO");
    }

    static void tester() throws Exception {
        VCommunityConfig c = cfg();
        Rig r = new Rig(c);
        r.ops.files.put("vm_options", "<L>config.version, a b</L>");
        r.ops.files.put("vm_advanced_parameters", new OpsHttpException("x", 404, null));
        ConnectionTester.Report rep = ConnectionTester.run(c, r.vim, (h, p, t) -> ThumbprintProbe.Result.found("cd".repeat(32)),
                r.ops, new CheckListStore(r.log));
        String text = rep.text();
        T.contains(text, "Logged in to VMware vCenter Server 8.0.3", "login");
        T.contains(text, "Trust on First Use", "mode");
        T.contains(text, "cd".repeat(32), "thumbprint shown");
        T.contains(text, "The VCF Operations Suite API answered", "suite api");
        T.contains(text, "ESXi Advanced System Settings 'esxi_advanced_system_settings': OK, 1 item(s)", "a file with its count");
        T.contains(text, "VM Options 'vm_options': OK, 1 item(s), 1 skipped", "skipped entries");
        T.contains(text, "VM Advanced Parameters 'vm_advanced_parameters': ABSENT", "absent file");
        T.notContains(text, "svc", "no account detail beyond what is needed");
        T.check(rep.needsAttention(), "a missing file and a skipped entry need attention");
        T.eq(2, rep.attentionLines().size(), "exactly the two lines that need it: " + rep.attentionLines());
        String attn = String.join("|", rep.attentionLines());
        T.contains(attn, "VM Options 'vm_options'", "the skipped entry's file");
        T.contains(attn, "VM Advanced Parameters 'vm_advanced_parameters'", "the absent file");
        T.notContains(attn, "ESXi Advanced System Settings", "a file that is fine is not an attention line");
        Rig clean = new Rig(c);
        ConnectionTester.Report ok = ConnectionTester.run(c, clean.vim, (h, p, t) -> ThumbprintProbe.Result.found("cd".repeat(32)),
                clean.ops, new CheckListStore(clean.log));
        T.check(!ok.needsAttention(), "a clean report is not a warning: " + ok.text());

        // a login failure fails the test and keeps the cause
        Rig bad = new Rig(c);
        Throwable cause = new java.net.ConnectException("x");
        bad.vim.next = VimResult.fault("Transport", "the vCenter refused or did not accept the connection", null, 0, cause);
        try {
            ConnectionTester.run(c, bad.vim, bad.probe, bad.ops, new CheckListStore(bad.log));
            throw new AssertionError("login failure passed");
        } catch (IOException e) {
            T.contains(e.getMessage(), "refused", "message");
            T.check(e.getCause() == cause, "cause kept");
        }
        // a Suite API that does not answer does not fail the test
        Rig noSuite = new Rig(c);
        noSuite.ops.versionFailure = new java.net.SocketTimeoutException("slow");
        ConnectionTester.Report r2 = ConnectionTester.run(c, noSuite.vim, noSuite.probe, noSuite.ops, new CheckListStore(noSuite.log));
        T.contains(r2.text(), "did not answer", "said");
        T.check(r2.needsAttention(), "a client that was built and did not answer IS a warning");
        T.contains(String.join("|", r2.attentionLines()), "WARNING: the VCF Operations Suite API did not answer", "attention line");
        T.contains(r2.text(), "Check-list files were not read", "and the files were not claimed");
        ConnectionTester.Report r3 = ConnectionTester.run(c, noSuite.vim, noSuite.probe, null, new CheckListStore(noSuite.log));
        // No Suite API client is what a test normally has (the account is not configured): it is
        // said as a plain line, not a warning, and the files are not claimed to have been read.
        T.contains(r3.text(), "not checked during a test", "no client is said plainly");
        T.contains(r3.text(), "Check-list files were not read", "and the files were not read");
        T.notContains(r3.text(), "WARNING", "not presented as a warning");
        T.check(!r3.needsAttention() && r3.attentionLines().isEmpty(), "nothing needs attention: " + r3.attentionLines());
        // a probe that fails is said, not hidden
        ConnectionTester.Report r4 = ConnectionTester.run(c, r.vim, (h, p, t) -> ThumbprintProbe.Result.failed("no route"),
                r.ops, new CheckListStore(r.log));
        T.contains(r4.text(), "could not be read (no route)", "probe failure shown");
        // gating shows only the lists in use
        VCommunityConfig gated = cfg("serviceMonitoring", "Enabled");
        Rig g = new Rig(gated);
        g.ops.files.put("windows_service_list", "<L>Dhcp</L>");
        ConnectionTester.Report r5 = ConnectionTester.run(gated, g.vim, g.probe, g.ops, new CheckListStore(g.log));
        T.contains(r5.text(), "Windows Services 'windows_service_list': OK, 1 item(s)", "service list");
        T.notContains(r5.text(), "Windows Event Log", "event list off");
        T.done("test connection reports login, certificate, Suite API and each file");
    }
}
