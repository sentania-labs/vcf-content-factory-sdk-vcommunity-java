package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.guest.GuestRunner;
import com.vcfcf.adapters.vcommunity.guest.HttpsGuestHttp;
import com.vcfcf.adapters.vcommunity.health.CollectionHealth;
import com.vcfcf.adapters.vcommunity.health.WarnCounter;
import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;
import com.vcfcf.adapters.vcommunity.ops.PushOutcome;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The guest phase through the cycle engine (design 8.1, 8.6, 4.10): the property reads that pick the Windows VMs,
 * the phase, the keys and events it sends, its health, and what each of the failures does to the rest of the
 * cycle. A real vim25 client and TLS transfer against {@link GuestFx}; the Suite API is {@link P2Ops}.
 */
public final class GuestEngineTest {

    static final long NOW = 1_700_000_000_000L;
    static final String OS = "vCommunity|Guest OS|Operating System|";
    static final String SV = "vCommunity|Guest OS|Services:";
    static final String COLLECTOR_KEY = CollectionHealth.PREFIX + "Collector:guest|";

    /** A cycle's whole setting. */
    static final class Rig implements AutoCloseable {
        final FakeVcenter vcenter = new FakeVcenter();
        final GuestFx fx;
        final FakeVc vc;
        final P2Ops ops = new P2Ops();
        final T.RecLog log = new T.RecLog();
        final WarnCounter warns = new WarnCounter(log);
        final AtomicLong clock = new AtomicLong(NOW);
        final VimSoapClient vim;
        final CheckListStore store = new CheckListStore(warns);
        CycleEngine engine;
        VCommunityConfig cfg;
        final GuestRunner.Limits limits = new GuestRunner.Limits();
        boolean clockMovesWhenWaiting;

        Rig(int windowsVms, String... kv) throws Exception {
            this(windowsVms, true, kv);
        }

        Rig(int windowsVms, boolean account, String... kv) throws Exception {
            fx = new GuestFx(vcenter);
            vc = FakeVc.withSharedCertificate(fx);
            vcenter.add(P2Fixtures.clusterFull("domain-c1", "Prod"));
            vcenter.add(P2Fixtures.hostFull("host-1", "esx1.lab.test", "imgmgr-1")
                    .put("summary.config.sslThumbprint", SoapFx.str(fx.esxiSha1())));
            vcenter.installDate.put("imgmgr-1", "2025-01-02T03:04:05.123456Z");
            for (int i = 1; i <= windowsVms; i++) {
                vcenter.add(windows("vm-" + i, "win" + i, "toolsOk", "windowsGuest", "poweredOn"));
                GuestFx.Guest g = fx.guest("vm-" + i);
                g.csv.put("os", GuestOpsTest.OS_OK);
                g.csv.put("services", GuestOpsTest.SERVICES_OK);
                g.csv.put("events", GuestOpsTest.EVENTS_OK);
            }
            vcenter.add(P2Fixtures.dsVmfs("datastore-1", "ds1"));
            PropertiesTest.mapAll(ops, vcenter);
            PropertiesTest.files(ops, "Net.A", "esx-base", "disk.EnableUUID", "config.version");
            ops.files.put("windows_service_list", "<L>BITS,W32Time,wuauserv,Missing Service</L>");
            ops.files.put("windows_event_list", GuestOpsTest.EVENT_XML);
            vc.setHandler(fx);
            Map<String, String> m = new HashMap<>();
            m.put("host", "vc.example.test");
            for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            cfg = account ? VCommunityConfig.parse(m::get, "u", "p", GuestOpsTest.USER, GuestOpsTest.SECRET)
                    : VCommunityConfig.parse(m::get, "u", "p", null, null);
            vim = new VimSoapClient("localhost:" + vc.port(), "u", "p",
                    VcenterTls.create(TlsMode.TRUST_ON_FIRST_USE, "", null, null), warns, 5000, 20000, 20000);
            newEngine();
        }

        /** A new engine over the same session, as after a restart: its memories are empty. */
        void newEngine() {
            engine = new CycleEngine(cfg, vim, (h, p, t) -> ThumbprintProbe.Result.found("ab".repeat(32)), ops,
                    store, warns, clock::get);
            limits.scriptMs = 400;
            engine.guestWiring(new HttpsGuestHttp(2000, 5000), ms -> {
                if (clockMovesWhenWaiting) clock.addAndGet(ms);
                Thread.sleep(Math.min(ms, 5));
            }, limits);
        }

        CycleEngine.Result run() throws Exception {
            return engine.run(clock.get() + 240_000);
        }

        /** The last value pushed for a property of a resource, over every push of the run; null when none. */
        Object prop(String rid, String key) {
            Object v = null;
            for (Map<String, Map<String, Object>> p : ops.pushedProps) {
                Map<String, Object> m = p.get(rid);
                if (m != null && m.containsKey(key)) v = m.get(key);
            }
            return v;
        }

        Double stat(String rid, String key) {
            Double v = null;
            for (Map<String, Map<String, Double>> p : ops.pushedStats) {
                Map<String, Double> m = p.get(rid);
                if (m != null && m.containsKey(key)) v = m.get(key);
            }
            return v;
        }

        Object health(String key) {
            return prop("vc-res", COLLECTOR_KEY + key);
        }

        List<String> allText() {
            List<String> out = new ArrayList<>();
            for (Map<String, Map<String, Object>> p : ops.pushedProps) {
                for (Map<String, Object> m : p.values()) for (Object o : m.values()) out.add(String.valueOf(o));
            }
            for (List<OpsEvent> l : ops.eventPushes) for (OpsEvent e : l) out.add(e.message);
            out.add(log.all());
            return out;
        }

        @Override
        public void close() {
            vim.close("test finished");
            vc.close();
            fx.close();
        }
    }

    static SoapFx.Obj windows(String moid, String name, String tools, String family, String power) {
        SoapFx.Obj v = P2Fixtures.vmFull(moid, name);
        v.put("guest.toolsStatus", SoapFx.en("VirtualMachineToolsStatus", tools));
        v.put("guest.guestFamily", SoapFx.str(family));
        v.put("runtime.powerState", SoapFx.en("VirtualMachinePowerState", power));
        return v;
    }

    static void check(String kind, String key) {
        Coverage5.asserted(kind, key);
    }

    static void run(String name, ThrowingRunnable r) throws Exception {
        Coverage5.scenario = "GuestEngineTest: " + name;
        r.run();
        T.done("guest engine: " + name);
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        run("switched off: no guest operation of any kind, nothing extra read", GuestEngineTest::off);
        run("keys of a Windows VM", GuestEngineTest::keys);
        run("the VMs that are not asked", GuestEngineTest::whoIsAsked);
        run("windows events once, and again after a restart", GuestEngineTest::events);
        run("an unsent events push is sent again", GuestEngineTest::eventsNotClean);
        run("one VM's failure is its own", GuestEngineTest::isolation);
        run("the account is not tried on every VM when it is refused", GuestEngineTest::accountProtection);
        run("prerequisites: no account, a bad TLS mode", GuestEngineTest::prerequisites);
        run("the cycle's time", GuestEngineTest::deadline);
        run("a cancelled cycle removes what it made", GuestEngineTest::cancelled);
        run("a directory the guest could not remove is removed next time", GuestEngineTest::leftover);
        run("configuration warning events", GuestEngineTest::configEvents);
        run("an event list entry the guest cannot carry is counted", GuestEngineTest::skippedEventEntries);
        run("nothing secret anywhere", GuestEngineTest::secrets);
        Coverage5.scenario = "coverage";
        Coverage5.verifyOrWrite();
        System.out.println("guest engine: all passed");
    }

    // ------------------------------------------------------------------ scenarios

    static void off() throws Exception {
        try (Rig r = new Rig(2)) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            T.eq(0, r.fx.log.size(), "no guest operation was made");
            T.eq(0, r.fx.esxi.requests.size(), "no file transfer");
            T.eq(0, r.fx.esxi.connections.get(), "no connection to an ESXi host");
            for (FakeVc.Req q : r.vc.requests) {
                T.notContains(q.body, "guest.toolsStatus", "the VM read asks for nothing the guest phase needs");
                T.notContains(q.body, "sslThumbprint", "nor the host read");
            }
            T.eq("SKIPPED", r.health("Status"), "the health key says so");
            T.eq(null, res.metrics().get("Collection|guest_vms_done"), "no guest figures");
            T.eq(0, r.ops.eventPushes.size(), "no events");
            T.eq(null, r.prop("res-vm-1", OS + "OS Name"), "no guest key");
        }
        // service files are not even read while monitoring is off
        try (Rig r = new Rig(1)) {
            r.run();
            for (String p : r.ops.paths) T.notContains(p, "windows_", "the Windows lists are not read while Disabled");
        }
    }

    static void keys() throws Exception {
        try (Rig r = new Rig(2, "serviceMonitoring", "Enabled")) {
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure: " + res.failure);
            for (String vm : new String[] {"res-vm-1", "res-vm-2"}) {
                T.eq("Microsoft Windows Server 2019 Datacenter", r.prop(vm, OS + "OS Name"), "OS Name");
                T.eq("10.0.17763", r.prop(vm, OS + "OS Version"), "OS Version");
                T.eq("17763", r.prop(vm, OS + "OS BuildNumber"), "OS BuildNumber");
                T.eq("64-bit", r.prop(vm, OS + "OS Architecture"), "OS Architecture");
                T.eq("11/12/2026 8:15:00 AM", r.prop(vm, OS + "OS Last Boot Up Time"), "OS Last Boot Up Time");
                T.eq("1809", r.prop(vm, OS + "OS Release ID"), "OS Release ID");
                T.eq("BITS", r.prop(vm, SV + "Background Intelligent Transfer Service|Service Name"), "Service Name");
                T.eq("Running", r.prop(vm, SV + "Background Intelligent Transfer Service|Service Status"), "Service Status");
                T.eq("Automatic", r.prop(vm, SV + "Background Intelligent Transfer Service|Service Start Type"), "Service Start Type");
                T.eq("Stopped", r.prop(vm, SV + "Windows Time|Service Status"), "a stopped service is a reading");
                T.eq("Disabled", r.prop(vm, SV + "Windows Update|Service Start Type"), "a disabled start type");
                T.eq(null, r.prop(vm, SV + "Missing Service|Service Status"), "a service that does not exist is nothing, not stopped");
            }
            String vm = "VirtualMachine";
            check(vm, "vCommunity|Guest OS|Operating System|OS Name");
            check(vm, "vCommunity|Guest OS|Operating System|OS Version");
            check(vm, "vCommunity|Guest OS|Operating System|OS BuildNumber");
            check(vm, "vCommunity|Guest OS|Operating System|OS Architecture");
            check(vm, "vCommunity|Guest OS|Operating System|OS Last Boot Up Time");
            check(vm, "vCommunity|Guest OS|Operating System|OS Release ID");
            check(vm, "vCommunity|Guest OS|Services:{service.display_name}|Service Name");
            check(vm, "vCommunity|Guest OS|Services:{service.display_name}|Service Status");
            check(vm, "vCommunity|Guest OS|Services:{service.display_name}|Service Start Type");
            T.eq("OK", r.health("Status"), "guest collector OK");
            T.eq(2.0, res.metrics().get("Collection|guest_vms_done"), "two VMs done");
            T.eq(0.0, res.metrics().get("Collection|guest_vms_pending"), "none pending");
            T.eq("None in the last cycle", r.health("Causes"), "no causes");
            T.eq(0.0, r.stat("vc-res", COLLECTOR_KEY + "Failures"), "no failures");
            T.eq(0, r.fx.liveDirs().size(), "nothing left in a guest");
            // the events are off: no events program ran
            T.eq(0L, r.fx.programs.stream().filter(p -> p.contains("Get-WinEvent")).count(), "no events program");
            T.eq(0, r.ops.eventPushes.size(), "no events");
            // the vCenter read took the guest paths this once
            boolean asked = false;
            for (FakeVc.Req q : r.vc.requests) asked |= q.body.contains("guest.toolsStatus") && q.body.contains("guest.guestFamily");
            T.check(asked, "the VM read asks for the tools state and the family");
            // a second cycle: the Windows VMs are asked again, properties are sent again (as when read)
            r.run();
            T.eq(4L, r.fx.programs.stream().filter(p -> p.contains("Win32_OperatingSystem")).count(),
                    "both VMs are asked again in the second cycle");
            int tofu = 0;
            for (String w : r.log.warns) if (w.contains("over a connection whose certificate is not checked")) tofu++;
            T.eq(1, tofu, "the account travelling over an unchecked vCenter connection is said once, not every cycle");
        }
    }

    static void whoIsAsked() throws Exception {
        try (Rig r = new Rig(1, "serviceMonitoring", "Enabled")) {
            r.vcenter.add(windows("vm-10", "linux", "toolsOk", "linuxGuest", "poweredOn"));
            r.vcenter.add(windows("vm-11", "down", "toolsNotRunning", "windowsGuest", "poweredOn"));
            r.vcenter.add(windows("vm-12", "off", "toolsNotRunning", "windowsGuest", "poweredOff"));
            r.vcenter.add(windows("vm-13", "tmpl", "toolsOk", "windowsGuest", "poweredOff").put("config.template", SoapFx.bool(true)));
            r.vcenter.add(windows("vm-14", "nomatch", "toolsOk", "windowsGuest", "poweredOn"));
            PropertiesTest.mapAll(r.ops, r.vcenter);
            // vm-14 has no Operations resource: it is the one that cannot be matched
            r.ops.resources.get("VirtualMachine").removeIf(x -> x[1].equals("vm-14"));
            for (String id : new String[] {"vm-10", "vm-11", "vm-12", "vm-13", "vm-14"}) r.fx.guest(id);
            CycleEngine.Result res = r.run();
            T.check(r.fx.log.size() > 0, "the Windows VM is asked");
            for (String l : r.fx.log) T.check(l.endsWith("vm-1"), "only vm-1 is asked: " + l);
            T.eq("PARTIAL", r.health("Status"), "a powered-on Windows VM with no tools is a failed one");
            T.contains(String.valueOf(r.health("Causes")), "F-GUEST-TOOLS x1", "named: tools down, once (the powered-off one is not counted)");
            T.eq(1.0, res.metrics().get("Collection|guest_vms_done"), "one VM done");
        }
    }

    static void events() throws Exception {
        try (Rig r = new Rig(2, "winEventMonitoring", "Enabled")) {
            r.run();
            T.eq(1, r.ops.eventPushes.size(), "one send");
            List<OpsEvent> sent = r.ops.eventPushes.get(0);
            T.eq(4, sent.size(), "two events for each of two VMs");
            for (OpsEvent e : sent) {
                T.check(e.resourceId.equals("res-vm-1") || e.resourceId.equals("res-vm-2"), "on the VM's own object: " + e.resourceId);
                T.check(e.message.startsWith("[WindowsEvent-"), e.message);
            }
            // a program for events only: no services, no OS keys
            T.eq(null, r.prop("res-vm-1", OS + "OS Name"), "event monitoring alone reads no operating system keys");
            T.eq("OK", r.health("Status"), "health OK");
            // the same window again, the next cycle: nothing is sent again
            r.clock.addAndGet(300_000);
            r.run();
            T.eq(1, r.ops.eventPushes.size(), "the same window is not sent twice");
            // a new event appears in the window
            for (String vm : new String[] {"vm-1", "vm-2"}) {
                r.fx.guest(vm).csv.put("events", GuestOpsTest.EVENTS_OK
                        + "\"Error\",\"2\",\"[Timestamp=11/13/2026 10:03:00 AM] [Category=System] [EventID=7036] [Message=new]\"\r\n");
            }
            r.clock.addAndGet(300_000);
            r.run();
            T.eq(2, r.ops.eventPushes.size(), "a new event is sent");
            T.eq(2, r.ops.eventPushes.get(1).size(), "only the new one, once per VM");
            T.contains(r.ops.eventPushes.get(1).get(0).message, "[Message=new]", "the new one");
            // later than the time to live the memory forgets
            r.clock.addAndGet(31 * 60_000);
            r.run();
            T.eq(3, r.ops.eventPushes.size(), "after the memory's time an event still in view is sent once more");
            T.eq(6, r.ops.eventPushes.get(2).size(), "all three of each VM");
            // a restart forgets everything, as the original does
            r.newEngine();
            r.clock.addAndGet(60_000);
            r.run();
            T.eq(4, r.ops.eventPushes.size(), "after a restart the events in view are sent once more");
        }
    }

    static void eventsNotClean() throws Exception {
        try (Rig r = new Rig(1, "winEventMonitoring", "Enabled")) {
            r.ops.eventOutcome = new PushOutcome(0, 0, 1, 0, 0, false);
            CycleEngine.Result res = r.run();
            T.eq(1, r.ops.eventPushes.size(), "tried");
            T.contains(String.valueOf(r.health("Causes")), "F-EVENTS", "counted");
            T.check(res.failure == null, "the cycle does not fail");
            r.ops.eventOutcome = null;
            r.run();
            T.eq(2, r.ops.eventPushes.size(), "sent again, because the first send was not remembered");
            T.eq(2, r.ops.eventPushes.get(1).size(), "both events");
            r.run();
            T.eq(2, r.ops.eventPushes.size(), "and remembered after the clean send");
        }
    }

    static void isolation() throws Exception {
        try (Rig r = new Rig(3, "serviceMonitoring", "Enabled", "winEventMonitoring", "Enabled")) {
            r.clockMovesWhenWaiting = true;                       // a program that hangs ends when its waits add up
            r.fx.guest("vm-2").pass = "not-the-password";          // refused
            r.fx.guest("vm-3").hang.add("services");              // hangs
            r.fx.guest("vm-3").csv.put("events", "\"Level\",\"Event\"\r\n\"Err\"\r\n"); // a header that lacks no column but has a short row
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "the cycle goes on: " + res.failure);
            T.eq("Running", r.prop("res-vm-1", SV + "Background Intelligent Transfer Service|Service Status"), "VM 1 is untouched");
            T.eq(null, r.prop("res-vm-2", OS + "OS Name"), "a refused VM has no guest key");
            T.eq(null, r.prop("res-vm-2", SV + "Background Intelligent Transfer Service|Service Status"), "and is not 'stopped'");
            T.eq(null, r.prop("res-vm-3", SV + "Background Intelligent Transfer Service|Service Status"), "a program that hung gave nothing");
            T.eq("Microsoft Windows Server 2019 Datacenter", r.prop("res-vm-3", OS + "OS Name"), "but the next program on that VM ran");
            String causes = String.valueOf(r.health("Causes"));
            T.contains(causes, "F-GUEST-AUTH x1", "the refusal is named");
            T.contains(causes, "F-GUEST-TIMEOUT x1", "the timeout is named");
            T.eq("PARTIAL", r.health("Status"), "partial");
            for (OpsEvent e : r.ops.eventPushes.get(0)) T.check(!e.resourceId.equals("res-vm-2"), "no event is invented for a refused VM");
            T.eq(0, r.fx.liveDirs().size(), "nothing left in any guest");
            T.check(r.stat("vc-res", COLLECTOR_KEY + "Failures") >= 2, "failures counted");
            // the cycle's other keys are all there
            T.check(r.prop("res-host-1", "vCommunity|Configuration|Config Status") != null, "the host's keys are sent");
        }
    }

    static void accountProtection() throws Exception {
        try (Rig r = new Rig(10, "serviceMonitoring", "Enabled")) {
            for (int i = 1; i <= 10; i++) r.fx.guest("vm-" + i).pass = "wrong";
            r.run();
            int tries = r.fx.count("CreateTemporaryDirectoryInGuest");
            T.check(tries >= 3 && tries <= 6, "the account is tried on a few VMs only: " + tries);
            T.eq("FAILED", r.health("Status"), "every VM that was asked refused the account");
            T.contains(String.valueOf(r.health("Causes")), "F-GUEST-AUTH", "named");
            int after = r.fx.log.size();
            for (int c = 0; c < 12; c++) {
                r.clock.addAndGet(300_000);
                r.run();
                T.eq(after, r.fx.log.size(), "paused: no guest operation in collection " + (c + 2));
                T.eq("SKIPPED", r.health("Status"), "and it says so");
            }
            for (int i = 1; i <= 10; i++) r.fx.guest("vm-" + i).pass = GuestOpsTest.SECRET;
            r.clock.addAndGet(300_000);
            r.run();
            T.check(r.fx.log.size() > after, "after the pause the phase tries again");
            T.eq("OK", r.health("Status"), "and with the right password it is fine");
        }
    }

    static void prerequisites() throws Exception {
        try (Rig r = new Rig(1, false, "serviceMonitoring", "Enabled")) {
            r.run();
            T.eq(0, r.fx.log.size(), "no account: no guest operation");
            T.eq("SKIPPED", r.health("Status"), "skipped");
            int warns = 0;
            for (String w : r.log.warns) if (w.contains("no Windows user name")) warns++;
            T.eq(1, warns, "one clear warning");
            T.check(r.prop("res-host-1", "vCommunity|Configuration|Config Status") != null, "the rest of the cycle collects");
        }
        try (Rig r = new Rig(1, "serviceMonitoring", "Enabled", "guest_tls_validation", "Sometimes")) {
            r.run();
            T.eq(0, r.fx.log.size(), "a bad TLS mode: no guest operation");
            T.eq("FAILED", r.health("Status"), "failed, said so");
            T.contains(String.valueOf(r.health("Causes")), "F-TLS-CONFIG", "named");
            T.check(r.prop("res-host-1", "vCommunity|Configuration|Config Status") != null, "the rest of the cycle collects");
        }
        // the VM read failed: there is no list of Windows VMs
        try (Rig r = new Rig(1, "serviceMonitoring", "Enabled")) {
            r.vcenter.forcedType.put("VirtualMachine", FakeVcenter.serverFault("boom"));
            r.run();
            T.eq(0, r.fx.log.size(), "no VM list, no guest operation");
            T.eq("SKIPPED", r.health("Status"), "skipped");
        }
    }

    static void deadline() throws Exception {
        try (Rig r = new Rig(8, "serviceMonitoring", "Enabled")) {
            r.clockMovesWhenWaiting = true;
            for (int i = 1; i <= 8; i++) r.fx.guest("vm-" + i).hang.add("services");
            r.limits.scriptMs = 400_000; // fake milliseconds: four hung programs use up the cycle's 240 s between them
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "no failure");
            double done = res.metrics().get("Collection|guest_vms_done");
            double pending = res.metrics().get("Collection|guest_vms_pending");
            T.check(pending > 0, "some VMs were left for the next cycle: pending " + pending);
            T.eq(8.0, done + pending, "every VM is one or the other");
            T.eq(1.0, res.metrics().get("Collection|deadline_hit"), "the cycle says it stopped at its deadline");
            T.eq(0, r.fx.liveDirs().size(), "nothing left in a guest");
            T.check(r.fx.maxActive.get() <= 4, "never more than four at once: " + r.fx.maxActive.get());
            // the next cycle starts where this one stopped: a VM left over is the first
            int startedFirst = r.fx.count("CreateTemporaryDirectoryInGuest");
            for (int i = 1; i <= 8; i++) r.fx.guest("vm-" + i).hang.clear();
            r.fx.log.clear();
            r.clock.addAndGet(300_000);
            r.run();
            T.check(r.fx.log.size() > 0, "asked again");
            String first = r.fx.log.get(0).substring(r.fx.log.get(0).lastIndexOf(' ') + 1);
            T.check(!first.equals("vm-1"), "the VMs not reached come first: " + first);
        }
        // never more than four at a time
        try (Rig r = new Rig(12, "serviceMonitoring", "Enabled")) {
            r.fx.startDelayMs = 60;
            r.run();
            T.check(r.fx.maxActive.get() >= 2 && r.fx.maxActive.get() <= 4, "concurrency: " + r.fx.maxActive.get());
        }
    }

    static void cancelled() throws Exception {
        try (Rig r = new Rig(4, "serviceMonitoring", "Enabled")) {
            for (int i = 1; i <= 4; i++) r.fx.guest("vm-" + i).hang.add("services");
            r.limits.scriptMs = 600_000;
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    r.engine.run(System.currentTimeMillis() + 600_000L);
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            r.clock.set(System.currentTimeMillis());
            t.start();
            long end = System.currentTimeMillis() + 20_000;
            while (r.fx.starts.get() < 4 && System.currentTimeMillis() < end) Thread.sleep(10);
            T.check(r.fx.starts.get() >= 1, "the programs started");
            t.interrupt();
            t.join(30_000);
            T.check(!t.isAlive(), "the cycle ended");
            T.check(thrown.get() instanceof InterruptedException, "the cancel is passed on: " + thrown.get());
            T.eq(0, r.fx.liveDirs().size(), "no directory is left in a guest after a cancel");
        }
    }

    static void leftover() throws Exception {
        try (Rig r = new Rig(1, "serviceMonitoring", "Enabled")) {
            r.fx.guest("vm-1").failDelete = true;
            r.run();
            T.eq(2, r.fx.liveDirs().size(), "the guest was not reachable for the removal: two directories remain");
            T.contains(String.valueOf(r.health("Causes")), "F-GUEST-CLEANUP", "counted");
            T.eq("PARTIAL", r.health("Status"), "and the collector says so");
            T.eq("Microsoft Windows Server 2019 Datacenter", r.prop("res-vm-1", OS + "OS Name"), "the data of that cycle is good");
            r.fx.guest("vm-1").failDelete = false;
            r.clock.addAndGet(300_000);
            r.run();
            T.eq(0, r.fx.liveDirs().size(), "the next collection removes them");
            T.eq("OK", r.health("Status"), "and is clean");
        }
    }

    static void configEvents() throws Exception {
        try (Rig r = new Rig(1, "winEventMonitoring", "Enabled")) {
            r.ops.files.remove("windows_event_list");
            r.run();
            T.check(r.ops.eventPushes.size() >= 1, "an event for the missing file");
            OpsEvent e = r.ops.eventPushes.get(0).get(0);
            T.eq("vc-res", e.resourceId, "on the vCenter's object");
            T.eq(OpsEvent.Severity.WARNING, e.severity, "a warning");
            T.check(e.message.startsWith("vCommunity configuration: Configuration file 'windows_event_list.xml'"), e.message);
            T.eq(0, r.fx.log.size(), "an event list that is not there starts no guest program");
            T.eq("SKIPPED", r.health("Status"), "the guest collector has nothing to do without the list");
        }
        // the Windows lists are not read, so not reported, while their switch is off
        try (Rig r = new Rig(1)) {
            r.ops.files.remove("windows_event_list");
            r.ops.files.remove("windows_service_list");
            r.run();
            T.eq(0, r.ops.eventPushes.size(), "no event about files nobody uses");
        }
        // an event push that fails is counted and does not fail the cycle
        try (Rig r = new Rig(1)) {
            r.ops.files.remove("vm_options");
            r.ops.eventOutcome = new PushOutcome(0, 0, 1, 0, 0, false);
            CycleEngine.Result res = r.run();
            T.check(res.failure == null, "the cycle goes on");
            T.contains(res.failureCausesProperty(), "F-EVENTS", "counted");
        }
    }

    static void skippedEventEntries() throws Exception {
        try (Rig r = new Rig(1, "winEventMonitoring", "Enabled")) {
            r.ops.files.put("windows_event_list", "<Events><Log Name=\"System\"><EventID>7036</EventID></Log>"
                    + "<Log Name=\"Bad&#9;Name\"><EventID>1</EventID></Log></Events>");
            CycleEngine.Result res = r.run();
            T.eq(1.0, res.metrics().get("Collection|checklist_entries_skipped"), "the entry the guest file cannot carry is counted");
            boolean said = false;
            for (String w : r.log.warns) said |= w.contains("cannot be sent to a guest");
            T.check(said, "and said once");
            T.check(r.fx.programs.size() == 1, "the rest of the list is used");
        }
    }

    static void secrets() throws Exception {
        try (Rig r = new Rig(2, "serviceMonitoring", "Enabled", "winEventMonitoring", "Enabled")) {
            r.fx.guest("vm-2").pass = "nope";
            r.fx.guest("vm-2").echoSecrets = true;
            r.fx.guest("vm-1").csv.put("services", "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n"
                    + "\"x\",\"Evil|Operating System|OS Name\",\"Running\",\"Auto\"\r\n\"y\",\"Fine\",\"Running\",\"Auto\"\r\n");
            CycleEngine.Result res = r.run();
            for (String s : r.allText()) {
                T.notContains(s, GuestOpsTest.SECRET, "the password is nowhere in what was sent or logged");
                T.notContains(s, "nope", "nor the one that was tried");
            }
            T.notContains(String.valueOf(res.failure), GuestOpsTest.SECRET, "nor in a failure");
            T.notContains(res.failureCausesProperty(), GuestOpsTest.SECRET, "nor in the causes");
            T.notContains(res.lastErrorProperty(), GuestOpsTest.SECRET, "nor in the last error");
            T.eq("Running", r.prop("res-vm-1", SV + "Fine|Service Status"), "the clean row is sent");
            for (Map<String, Map<String, Object>> push : r.ops.pushedProps) {
                for (String k : push.getOrDefault("res-vm-1", new HashMap<>()).keySet()) {
                    T.notContains(k, "Evil", "no key was made from a name that holds a separator");
                }
            }
        }
    }
}
