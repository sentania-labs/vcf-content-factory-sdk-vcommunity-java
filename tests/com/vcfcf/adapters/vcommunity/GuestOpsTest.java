package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.VCommunityConfig.GuestTlsMode;
import com.vcfcf.adapters.vcommunity.VCommunityConfig.TlsMode;
import com.vcfcf.adapters.vcommunity.guest.GuestAuth;
import com.vcfcf.adapters.vcommunity.guest.GuestFailure;
import com.vcfcf.adapters.vcommunity.guest.GuestHttp;
import com.vcfcf.adapters.vcommunity.guest.GuestOpsClient;
import com.vcfcf.adapters.vcommunity.guest.GuestPlan;
import com.vcfcf.adapters.vcommunity.guest.GuestRunner;
import com.vcfcf.adapters.vcommunity.guest.GuestStep;
import com.vcfcf.adapters.vcommunity.guest.GuestText;
import com.vcfcf.adapters.vcommunity.guest.GuestTrust;
import com.vcfcf.adapters.vcommunity.guest.HttpsGuestHttp;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;
import com.vcfcf.adapters.vcommunity.tls.VcenterTls;
import com.vcfcf.adapters.vcommunity.vim.VimSoapClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One VM's guest operations through the real vim25 client, the real TLS transfer and a fake vCenter and ESXi host
 * that answer as the real ones do (design 8.2, 8.4): what a healthy, stopped, missing and malformed guest gives,
 * what each failure leaves behind in the guest (nothing), and that no secret reaches a log, a message or a
 * command line.
 */
public final class GuestOpsTest {

    static final String VM = "vm-1";
    static final String SECRET = "S3cret-Pass!";
    static final String USER = "svc-guest";

    static final String SERVICES_OK = "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n"
            + "\"BITS\",\"Background Intelligent Transfer Service\",\"Running\",\"Automatic\"\r\n"
            + "\"W32Time\",\"Windows Time\",\"Stopped\",\"Manual\"\r\n"
            + "\"wuauserv\",\"Windows Update\",\"Stopped\",\"Disabled\"\r\n";

    static final String OS_OK = "\"Name\",\"Version\",\"BuildNumber\",\"OSArchitecture\",\"LastBootUpTime\",\"ReleaseId\"\r\n"
            + "\"Microsoft Windows Server 2019 Datacenter\",\"10.0.17763\",\"17763\",\"64-bit\",\"11/12/2026 8:15:00 AM\",\"1809\"\r\n";

    static final String EVENTS_HEADER = "\"Level\",\"LevelNumber\",\"Event\",\"Failed\"\r\n";

    static final String EVENTS_OK = EVENTS_HEADER
            + "\"Error\",\"2\",\"[Timestamp=11/13/2026 9:58:01 AM] [Category=System] [EventID=7036] [Source=Service Control Manager] [Message=The Background Intelligent Transfer Service service entered the stopped state.]\",\"\"\r\n"
            + "\"Warning\",\"3\",\"[Timestamp=11/13/2026 9:57:00 AM] [Category=Application] [EventID=1000] [Source=Contoso] [Message=line one\r\nline two]\",\"\"\r\n";

    static final String EVENT_XML = "<Events><Log Name=\"System\"><EventID>7036</EventID></Log>"
            + "<Log Name=\"Application\"><EventID>1000</EventID></Log></Events>";

    /** A vCenter with guest operations, an ESXi host behind it, and a runner over them. */
    static final class Rig implements AutoCloseable {
        final FakeVc vc;
        final GuestFx fx;
        final VimSoapClient vim;
        final T.RecLog log = new T.RecLog();
        final GuestRunner.Limits limits = new GuestRunner.Limits();
        final GuestOpsClient ops;
        GuestTrust trust;
        GuestRunner runner;
        final List<Long> naps = Collections.synchronizedList(new ArrayList<>());

        Rig() throws Exception {
            fx = new GuestFx(FakeVc.happy());
            vc = FakeVc.withSharedCertificate(fx);
            vim = new VimSoapClient("localhost:" + vc.port(), "u", "p",
                    VcenterTls.create(TlsMode.TRUST_ON_FIRST_USE, "", null, null), log, 5000, 20000, 20000);
            T.check(vim.ensureSession().isOk(), "login");
            ops = new GuestOpsClient(vim, new GuestAuth(USER, SECRET));
            limits.scriptMs = 400;
            trust = new GuestTrust(GuestTlsMode.PINNED, Map.of("host-1", fx.esxiSha1()));
            build(new HttpsGuestHttp(2000, 5000));
            GuestFx.Guest g = fx.guest(VM);
            g.csv.put("services", SERVICES_OK);
            g.csv.put("os", OS_OK);
            g.csv.put("events", EVENTS_OK);
        }

        void build(GuestHttp http) {
            runner = new GuestRunner(ops, http, trust, System::currentTimeMillis,
                    ms -> {
                        naps.add(ms);
                        Thread.sleep(Math.min(ms, 15));
                    }, log, limits);
        }

        GuestRunner.Job job(GuestPlan plan) {
            GuestRunner.Job j = new GuestRunner.Job();
            j.vm = VM;
            j.resourceId = "rid-1";
            j.hostMoref = "host-1";
            j.hostName = "localhost";
            j.plan = plan;
            return j;
        }

        GuestRunner.Outcome run(GuestPlan plan) throws Exception {
            return runner.run(job(plan), System.currentTimeMillis() + 60_000);
        }

        @Override
        public void close() {
            vim.close("test finished");
            vc.close();
            fx.close();
        }
    }

    /** An events output row: level, number, event text, failed query position. */
    static String evRow(String level, String number, String event, String failed) {
        return "\"" + level + "\",\"" + number + "\",\"" + event.replace("\"", "\"\"") + "\",\"" + failed + "\"\r\n";
    }

    static GuestPlan plan() {
        return GuestPlan.create(true, Arrays.asList("BITS", "W32Time", "wuauserv", "Missing Service"), true, EVENT_XML);
    }

    static String causes(GuestRunner.Outcome o) {
        StringBuilder b = new StringBuilder();
        for (GuestFailure f : o.failures) b.append(f.step.name()).append(' ');
        return b.toString().trim();
    }

    static boolean has(GuestRunner.Outcome o, GuestStep s) {
        for (GuestFailure f : o.failures) if (f.step == s) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        healthy();
        stoppedAndMissing();
        malformedOutput();
        eventWindows();
        hostileOutput();
        authAndAvailability();
        timeoutAndExit();
        failuresLeaveNothing();
        cleanupOnInterrupt();
        leftovers();
        review();
        tlsModes();
        transferUrls();
        noSecretAnywhere();
        System.out.println("guest operations: all passed");
    }

    // ------------------------------------------------------------------ the three programs on a healthy guest

    static void healthy() throws Exception {
        try (Rig r = new Rig()) {
            GuestRunner.Outcome o = r.run(plan());
            T.eq("", causes(o), "a healthy guest has no failure");
            T.eq(3, o.scriptsOk, "three programs gave output");
            T.eq(3, o.services.size(), "the three services that exist");
            T.eq("Background Intelligent Transfer Service", o.services.get(0).displayName, "display name");
            T.eq("Running", o.services.get(0).status, "status");
            T.eq("Automatic", o.services.get(0).startType, "start type");
            T.eq("Microsoft Windows Server 2019 Datacenter", o.operatingSystem.get("OS Name"), "OS name");
            T.eq("17763", o.operatingSystem.get("OS BuildNumber"), "OS build");
            T.eq("11/12/2026 8:15:00 AM", o.operatingSystem.get("OS Last Boot Up Time"), "boot time as the guest wrote it");
            T.eq("1809", o.operatingSystem.get("OS Release ID"), "release id");
            T.eq(2, o.events.size(), "two events");
            T.check(o.events.get(0).message.startsWith("[WindowsEvent-Error [Timestamp=11/13/2026 9:58:01 AM] [Category=System]"),
                    "event text: " + o.events.get(0).message);
            T.check(o.events.get(0).message.endsWith("entered the stopped state.]]"), "closed bracket: " + o.events.get(0).message);
            T.eq(OpsEvent.Severity.IMMEDIATE, o.events.get(0).severity, "Error is immediate");
            T.eq(OpsEvent.Severity.WARNING, o.events.get(1).severity, "Warning is a warning");
            T.check(o.events.get(1).message.contains("line one\r\nline two"), "a line break inside the message is kept as it was written");
            T.eq("rid-1", o.events.get(0).resourceId, "the event is for the VM's object");
            // the order of the original: services, operating system, events
            List<String> progs = r.fx.programs;
            T.check(progs.get(0).contains("Get-Service") && progs.get(1).contains("Win32_OperatingSystem")
                    && progs.get(2).contains("Get-WinEvent"), "order of the programs");
            T.eq(0, r.fx.liveDirs().size(), "no directory is left in the guest");
            T.eq(3, r.fx.deletes.get(), "one directory made and removed per program");
            // the input files the programs read
            T.eq(2, r.fx.count("ListFilesInGuest"), "the two input files were looked for once each");
            T.eq(5, r.fx.count("CreateTemporaryFileInGuest"), "every file is one the guest names: two inputs, three outputs");
        }
        T.done("guest: healthy guest, the three programs, in order, nothing left behind");
    }

    static void stoppedAndMissing() throws Exception {
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n"
                    + "\"W32Time\",\"Windows Time\",\"Stopped\",\"Manual\"\r\n");
            GuestRunner.Outcome o = r.run(plan());
            T.eq(1, o.services.size(), "only the service that exists");
            T.eq("Stopped", o.services.get(0).status, "a stopped service is a reading: Stopped");
            T.eq("", causes(o), "a missing service is no failure");
        }
        // none of the listed services exists: no rows, not a failure and not "stopped"
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n");
            GuestRunner.Outcome o = r.run(plan());
            T.eq(0, o.services.size(), "no service published");
            T.eq("", causes(o), "an output of its header only is none found, not a failure");
        }
        // a program that wrote nothing, not even the header, is a failure and never "none found"
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "");
            GuestRunner.Outcome o = r.run(plan());
            T.check(has(o, GuestStep.PARSE), "wrote nothing: " + causes(o));
            T.eq(0, o.services.size(), "no service published");
        }
        // an older PowerShell: no StartType column, the other two keys are real
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\"\r\n\"BITS\",\"Background Intelligent Transfer Service\",\"Running\"\r\n");
            GuestRunner.Outcome o = r.run(plan());
            T.eq(1, o.services.size(), "service without StartType");
            T.eq(null, o.services.get(0).startType, "no start type is sent");
            T.eq("Running", o.services.get(0).status, "status kept");
        }
        // an empty service list runs no services program; the OS information still runs
        try (Rig r = new Rig()) {
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Collections.<String>emptyList(), false, null));
            T.eq(0L, r.fx.programs.stream().filter(p -> p.contains("Get-Service")).count(), "no services program");
            T.check(o.operatingSystem != null, "operating system still read");
        }
        T.done("guest: stopped service is a reading, a missing one is nothing, old PowerShell, empty list");
    }

    static void malformedOutput() throws Exception {
        String[][] bad = {
            {"\"Name\",\"DisplayName\"\r\n\"a\",\"b\"\r\n", "HEADER"},          // Status column missing
            {"\"Name\",\"DisplayName\",\"Status\"\r\n\"a\",\"b", "PARSE"},        // a quote that never closes
            {"\"Name\",\"DisplayName\",\"Status\"\r\n\"a\"x,\"b\",\"c\"\r\n", "PARSE"}, // text after a closing quote
            {"Name,Display\"Name,Status\r\n", "PARSE"},                            // a quote inside a plain field
        };
        for (String[] b : bad) {
            try (Rig r = new Rig()) {
                r.fx.guest(VM).csv.put("services", b[0]);
                GuestRunner.Outcome o = r.run(GuestPlan.create(true, Arrays.asList("a"), false, null));
                T.check(has(o, GuestStep.valueOf(b[1])), "expected " + b[1] + " got " + causes(o) + " for " + b[0]);
                T.eq(0, o.services.size(), "nothing published from unreadable output");
                T.eq(0, r.fx.liveDirs().size(), "directory removed after an unreadable output");
            }
        }
        // short rows are left out and counted, whole rows stay
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n"
                    + "\"a\",\"A\",\"Running\"\r\n\"b\",\"B\",\"Running\",\"Auto\"\r\n");
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Arrays.asList("a", "b"), false, null));
            T.eq(1, o.services.size(), "the whole row only");
            T.check(has(o, GuestStep.ROW), "the short row is counted: " + causes(o));
        }
        // OS output with a column missing: nothing for the six keys
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("os", "\"Name\",\"Version\"\r\n\"x\",\"1\"\r\n");
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Collections.<String>emptyList(), false, null));
            T.eq(null, o.operatingSystem, "no operating system value from a short header");
            T.check(has(o, GuestStep.HEADER), "header failure");
        }
        // a program that wrote an empty file for the OS script: unreadable, not "no OS"
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("os", "");
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Collections.<String>emptyList(), false, null));
            T.check(has(o, GuestStep.PARSE), "an empty OS output is unparseable: " + causes(o));
        }
        // a BOM before the header is dropped
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("os", "\uFEFF" + OS_OK);
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Collections.<String>emptyList(), false, null));
            T.eq("10.0.17763", o.operatingSystem.get("OS Version"), "header read after a byte order mark");
        }
        T.done("guest: malformed output refused or counted, never read as data");
    }

    static void eventWindows() throws Exception {
        GuestPlan ev = GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML);
        // empty window: header only, and zero bytes
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", EVENTS_HEADER);
            GuestRunner.Outcome o = r.run(ev);
            T.eq(0, o.events.size(), "no events in an empty window");
            T.eq("", causes(o), "an empty window is no failure and is not an event");
        }
        // zero bytes: the program did not write; that is not an empty window
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", "");
            GuestRunner.Outcome o = r.run(ev);
            T.check(has(o, GuestStep.PARSE), "a program that wrote nothing: " + causes(o));
        }
        // a large window: 100 are sent, the rest counted
        try (Rig r = new Rig()) {
            StringBuilder xml = new StringBuilder("<Events>");
            for (int i = 0; i < 150; i++) xml.append("<Log Name=\"L").append(i).append("\"><EventID>1</EventID></Log>");
            xml.append("</Events>");
            StringBuilder b = new StringBuilder(EVENTS_HEADER);
            for (int i = 0; i < 300; i++) b.append(evRow("Information", "4", "[Timestamp=t" + i + "]", ""));
            r.fx.guest(VM).csv.put("events", b.toString());
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, xml.toString()));
            T.eq(100, o.events.size(), "100 per VM");
            T.eq(200, o.eventsOver, "the rest is counted");
            T.eq(OpsEvent.Severity.INFORMATION, o.events.get(0).severity, "level 4 is information");
        }
        // B2: each query gives at most one record, so a guest cannot send more events than there are queries
        try (Rig r = new Rig()) {
            StringBuilder b = new StringBuilder(EVENTS_HEADER);
            for (int i = 0; i < 9; i++) b.append(evRow("Information", "4", "[Timestamp=t" + i + "]", ""));
            r.fx.guest(VM).csv.put("events", b.toString());
            GuestRunner.Outcome o = r.run(ev);
            T.eq(2, o.events.size(), "two queries: at most two events");
            T.eq(7, o.eventsOver, "the rest is counted");
        }
        // non-ASCII, in the text and the level (a localised guest)
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", EVENTS_HEADER
                    + evRow("Fehler", "2", "[Message=\u00dcbertragung f\u00fcr \u65e5\u672c\u8a9e \ud83d\ude80 \u043f\u0440\u0438\u0432\u0435\u0442]", ""));
            GuestRunner.Outcome o = r.run(ev);
            T.eq(1, o.events.size(), "one event");
            T.contains(o.events.get(0).message, "\u00dcbertragung f\u00fcr \u65e5\u672c\u8a9e \ud83d\ude80 \u043f\u0440\u0438\u0432\u0435\u0442", "non-ASCII kept");
            T.contains(o.events.get(0).message, "[WindowsEvent-Fehler ", "the guest's own level text");
            T.eq(OpsEvent.Severity.IMMEDIATE, o.events.get(0).severity, "severity from the number, not the language");
        }
        // no level number (an older script): the English display names of the original
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", "\"Level\",\"Event\",\"Failed\"\r\n\"Critical\",\"a\",\"\"\r\n\"Verbose\",\"b\",\"\"\r\n\"Weird\",\"c\",\"\"\r\n");
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true,
                    "<Events><Log Name=\"System\"><EventID>1</EventID><EventID>2</EventID><EventID>3</EventID></Log></Events>"));
            T.eq(OpsEvent.Severity.CRITICAL, o.events.get(0).severity, "Critical");
            T.eq(OpsEvent.Severity.INFORMATION, o.events.get(1).severity, "Verbose is information");
            T.eq(OpsEvent.Severity.INFORMATION, o.events.get(2).severity, "anything else is information");
        }
        // the program is told what to look for: log TAB id per line, nothing else
        try (Rig r = new Rig()) {
            r.run(ev);
            Map<String, byte[]> files = new HashMap<>();
            T.eq(1L, r.fx.programs.stream().filter(p -> p.contains("Get-WinEvent")).count(), "one events program");
            T.contains(r.fx.programs.get(0), "$InputFile = 'C:\\Windows\\Temp\\VCFOperationsvCommunity-", "input path bound");
            T.contains(r.fx.programs.get(0), "$WindowMinutes = 5\n", "the original's five minutes unless a window is asked for");
        }
        T.done("guest: event windows: empty, large, non-ASCII, localised, no level number");
    }

    static void hostileOutput() throws Exception {
        GuestPlan both = GuestPlan.create(true, Arrays.asList("ok", "n1", "n2", "n3", "n4", "n5", "n6"), true, EVENT_XML);
        try (Rig r = new Rig()) {
            StringBuilder s = new StringBuilder("\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n");
            s.append("\"ok\",\"Fine\",\"Running\",\"Auto\"\r\n");
            s.append("\"n1\",\"Evil|Operating System|OS Name\",\"Running\",\"Auto\"\r\n");          // would start another key level
            s.append("\"n2\",\"Bad\u0007Bell\",\"Running\",\"Auto\"\r\n");                            // a control character
            s.append("\"n3\",\"Over\u202eride\",\"Running\",\"Auto\"\r\n");                           // a bidi override
            s.append("\"n4\",\"").append("L".repeat(500)).append("\",\"Running\",\"Auto\"\r\n");      // too long for a key
            s.append("\"n5\",\"\",\"Running\",\"Auto\"\r\n");                                          // blank instance (DV-99)
            s.append("\"n6\",\"Fine2\",\"").append("S".repeat(600)).append("\",\"Auto\"\r\n");       // value too long
            r.fx.guest(VM).csv.put("services", s.toString());
            GuestRunner.Outcome o = r.run(both);
            T.eq(1, o.services.size(), "only the clean row is published");
            T.eq("Fine", o.services.get(0).displayName, "the clean row");
            T.check(has(o, GuestStep.ROW), "the refused rows are counted");
        }
        try (Rig r = new Rig()) {
            String evil = "[Message=a\u0000b\u001b[31mred\u0007\r\n\u2028INJECTED\u202e\ud800 end]" + "x".repeat(5000);
            r.fx.guest(VM).csv.put("events", EVENTS_HEADER + evRow("Err\u0001or", "2", evil, ""));
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML));
            String m = o.events.get(0).message;
            T.notContains(m, "\u0000", "no NUL");
            T.notContains(m, "\u202e", "no bidi override");
            T.notContains(m, "\ud800", "no lone surrogate");
            T.check(m.length() <= GuestText.MAX_EVENT + 100, "bounded: " + m.length());
            T.contains(m, "red\u0007\r\n\u2028INJECTED", "everything else is the text as the guest wrote it, line breaks included");
            T.contains(m, "x".repeat(5000), "and is not cut short of the field limit");
            T.check(m.startsWith("[WindowsEvent-Error "), "the level is cleaned too: " + m.substring(0, 30));
            // nothing the guest wrote reaches a log line
            T.notContains(r.log.all(), "INJECTED", "no guest text in the log");
            T.notContains(r.log.all(), "red", "no guest text in the log");
        }
        // too many rows, too many columns, too long a field
        try (Rig r = new Rig()) {
            StringBuilder b = new StringBuilder(EVENTS_HEADER);
            for (int i = 0; i < 12_000; i++) b.append("a,1,b,\r\n");
            r.fx.guest(VM).csv.put("events", b.toString());
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML));
            T.check(has(o, GuestStep.PARSE), "more rows than the limit is refused whole: " + causes(o));
            T.eq(0, o.events.size(), "none of it is an event");
        }
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", EVENTS_HEADER + "a,1,\"" + "z".repeat(40_000) + "\",\r\n");
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML));
            T.check(has(o, GuestStep.PARSE), "a field over the limit is refused");
        }
        // output over the size limit is not downloaded
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\"\r\n" + "a,b,c\r\n".repeat(160_000));
            int before = r.fx.esxiRequests().size();
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Arrays.asList("a"), false, null));
            T.check(has(o, GuestStep.TOOLARGE), "too large: " + causes(o));
            long gets = r.fx.esxiRequests().stream().filter(x -> x.startsWith("REST GET")).count();
            // the only GET is the operating system program's output; the services output was not fetched
            T.eq(1L, gets, "an output the call says is too large is never downloaded");
        }
        T.done("guest: hostile output cannot inject a key, an event, a log line, or grow without bound");
    }

    static void authAndAvailability() throws Exception {
        // a wrong password: one failure, the other programs are not tried, nothing left behind
        try (Rig r = new Rig()) {
            r.fx.guest(VM).pass = "something-else";
            GuestRunner.Outcome o = r.run(plan());
            T.eq(GuestStep.AUTH, o.fatal, "authentication refused");
            T.eq(1, o.failures.size(), "one failure, not one per program");
            T.eq(1, r.fx.count("CreateTemporaryDirectoryInGuest"), "the other programs are not tried");
            T.eq(0, o.services.size() + o.events.size(), "nothing published");
            T.eq(null, o.operatingSystem, "nothing published");
            T.eq(0, r.fx.starts.get(), "no program started");
        }
        for (String[] f : new String[][] {{"GuestOperationsUnavailable", "UNAVAILABLE"}, {"InvalidState", "TOOLS"},
            {"GuestPermissionDenied", "AUTH"}}) {
            try (Rig r = new Rig()) {
                r.fx.guest(VM).fault = f[0];
                GuestRunner.Outcome o = r.run(plan());
                T.eq(GuestStep.valueOf(f[1]), o.fatal, f[0]);
                T.eq(1, o.failures.size(), f[0] + ": one failure");
            }
        }
        T.done("guest: refused account, no guest operations, tools down: one counted cause each");
    }

    static void timeoutAndExit() throws Exception {
        // a hung services program: stopped, directory removed, the next program still runs
        try (Rig r = new Rig()) {
            r.fx.guest(VM).hang.add("services");
            GuestRunner.Outcome o = r.run(plan());
            T.check(has(o, GuestStep.TIMEOUT), "timeout: " + causes(o));
            T.eq(1, r.fx.count("TerminateProcessInGuest"), "the hung program was stopped");
            T.check(o.operatingSystem != null, "the operating system program still ran");
            T.eq(2, o.events.size(), "and the events program");
            T.eq(0, o.services.size(), "no service is published from a program that did not finish");
            T.eq(0, r.fx.liveDirs().size(), "the directory of the hung program is removed");
            T.check(r.naps.get(0) == 1000 && r.naps.get(1) == 2000 && r.naps.get(2) == 4000 && r.naps.get(3) == 8000,
                    "the waits are 1, 2, 4, 8 seconds: " + r.naps.subList(0, 4));
        }
        // a program that ends with a failure code
        try (Rig r = new Rig()) {
            r.fx.guest(VM).exit.put("os", 2);
            GuestRunner.Outcome o = r.run(plan());
            T.check(has(o, GuestStep.EXIT), "exit code: " + causes(o));
            T.eq(null, o.operatingSystem, "the output of a program that failed is not read");
            T.eq(3, o.services.size(), "the other programs are unaffected");
        }
        // a program gone from the process list counts as finished (the original's rule)
        try (Rig r = new Rig()) {
            r.fx.guest(VM).vanish = true;
            GuestRunner.Outcome o = r.run(plan());
            T.eq("", causes(o), "a vanished process is finished");
            T.eq(3, o.scriptsOk, "and its output is read");
        }
        // the VM's own time runs out
        try (Rig r = new Rig()) {
            GuestRunner.Outcome o = r.runner.run(r.job(plan()), System.currentTimeMillis() - 1);
            T.check(has(o, GuestStep.TIMEOUT), "no time left: " + causes(o));
            T.eq(0, r.fx.count("CreateTemporaryDirectoryInGuest"), "no call is made after the time is up");
        }
        T.done("guest: timeouts stop the program and clean up; exit codes; vanished process; no time left");
    }

    static void failuresLeaveNothing() throws Exception {
        String[] steps = {"noStart", "uploadStatus", "noOutput", "oddDirectory", "oddFile", "failDelete"};
        for (String step : steps) {
            try (Rig r = new Rig()) {
                GuestFx.Guest g = r.fx.guest(VM);
                switch (step) {
                    case "noStart": g.noStart.addAll(Arrays.asList("services", "os", "events")); break;
                    case "uploadStatus": g.uploadStatus = 500; break;
                    case "noOutput": g.csv.clear(); break;
                    case "oddDirectory": g.oddDirectory = "C:\\Windows\\System32\\Evil"; break;
                    case "oddFile": g.oddFile = "C:\\Windows\\System32\\drivers\\etc\\hosts"; break;
                    default: g.failDelete = true;
                }
                GuestRunner.Outcome o = r.run(plan());
                switch (step) {
                    case "noStart": T.check(has(o, GuestStep.START), causes(o)); break;
                    case "uploadStatus": T.check(has(o, GuestStep.UPLOAD), causes(o)); break;
                    case "noOutput": T.check(has(o, GuestStep.PARSE), causes(o)); break;
                    case "oddDirectory":
                        T.check(has(o, GuestStep.DIRECTORY), causes(o));
                        T.eq(0, r.fx.starts.get(), "nothing is run in a directory the port does not recognise");
                        T.eq(0, r.fx.deletes.get(), "and nothing is deleted at a path the guest made up");
                        break;
                    case "oddFile":
                        T.check(has(o, GuestStep.DIRECTORY), causes(o));
                        T.eq(0, r.fx.esxi.requests.size(), "nothing is sent to a file the guest named outside the directory");
                        T.eq(0, r.fx.starts.get(), "and nothing is run");
                        break;
                    default:
                        T.check(has(o, GuestStep.CLEANUP), causes(o));
                        T.eq(3, o.scriptsOk, "the data is still good");
                        T.eq(6, r.fx.count("DeleteDirectoryInGuest"), "each removal tried twice");
                }
                if (!step.equals("oddDirectory") && !step.equals("failDelete")) { // (odd directories are never touched)
                    T.eq(0, r.fx.liveDirs().size(), step + ": nothing left in the guest");
                }
            }
        }
        T.done("guest: every failing step leaves no directory behind");
    }

    static void leftovers() throws Exception {
        try (Rig r = new Rig()) {
            // the guest cannot be reached for the removal (it was suspended, say): the directories are remembered
            r.fx.guest(VM).failDelete = true;
            GuestRunner.Outcome o = r.run(plan());
            T.eq(3, o.leftBehind.size(), "three directories could not be removed");
            T.eq(3, r.fx.liveDirs().size(), "and are still in the guest");
            r.fx.guest(VM).failDelete = false;
            GuestRunner.Job j = r.job(plan());
            j.leftover = new ArrayList<>(o.leftBehind);
            GuestRunner.Outcome again = r.runner.run(j, System.currentTimeMillis() + 60_000);
            T.eq(0, again.leftBehind.size(), "removed the next time the VM is asked");
            T.eq("", causes(again), "and that is no failure");
            T.eq(0, r.fx.liveDirs().size(), "nothing is left in the guest");
            // a path of any other form is never deleted, whatever it is called
            GuestRunner.Job k = r.job(plan());
            k.leftover = new ArrayList<>(Arrays.asList("C:\\Windows\\System32", "C:\\Windows\\Temp\\other"));
            int before = r.fx.deletes.get();
            r.runner.run(k, System.currentTimeMillis() + 60_000);
            T.eq(before + 3, r.fx.deletes.get(), "only the three directories of its own programs were deleted");
            // a directory that is already gone is not a failure
            GuestRunner.Job g = r.job(plan());
            g.leftover = new ArrayList<>(Arrays.asList("C:\\Windows\\Temp\\VCFOperationsvCommunity-1-OSInfo-TEMP"));
            T.eq("", causes(r.runner.run(g, System.currentTimeMillis() + 60_000)), "gone already");
        }
        T.done("guest: a directory that could not be removed is removed the next time the VM is asked");
    }

    /** The findings of the P5 review that are about one VM (B2, B3, W3, W4, W6, W7, W9, W10, N4, N6). */
    static void review() throws Exception {
        // B2: a service nobody asked for is not published, and a name gives one row
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n"
                    + "\"BITS\",\"Background Intelligent Transfer Service\",\"Running\",\"Auto\"\r\n"
                    + "\"bits\",\"Second row of one name\",\"Stopped\",\"Auto\"\r\n"
                    + "\"Planted1\",\"Planted one\",\"Running\",\"Auto\"\r\n\"Planted2\",\"Planted two\",\"Running\",\"Auto\"\r\n"
                    + "\"w32time\",\"Windows Time\",\"Running\",\"Auto\"\r\n");
            GuestRunner.Outcome o = r.run(plan());
            T.eq(2, o.services.size(), "BITS and W32Time (the name matches whatever its case), nothing else");
            T.eq("Background Intelligent Transfer Service", o.services.get(0).displayName, "the first row of a name");
            T.check(has(o, GuestStep.ROW), "the rest is counted");
        }
        // B3: an event query that failed in the guest is named; the events that were found are still sent
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", EVENTS_HEADER
                    + evRow("Error", "2", "[Timestamp=t] [Category=System] [EventID=7036] [Message=found]", "")
                    + evRow("", "", "", "2"));
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML));
            T.eq(1, o.events.size(), "the event that was found is sent");
            T.check(has(o, GuestStep.QUERY), "the failed query is a failure: " + causes(o));
            T.contains(o.failures.get(0).message, "Application", "named by the log of the entry (the second)");
            T.eq(1, o.scriptsOk, "the output was read");
        }
        // an output with a failed-query position that is not one of ours is a row left out, not a name
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("events", EVENTS_HEADER + evRow("", "", "", "99999"));
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML));
            T.check(has(o, GuestStep.QUERY), causes(o));
            T.notContains(o.failures.get(0).message, "99999", "a position that names nothing is not repeated");
        }
        // W3: an output cut short is refused, never read as a row with an empty value
        try (Rig r = new Rig()) {
            r.fx.guest(VM).csv.put("services", "\"Name\",\"DisplayName\",\"Status\",\"StartType\"\r\n"
                    + "\"BITS\",\"Background Intelligent Transfer Service\",\"Running\",");
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Arrays.asList("BITS"), false, null));
            T.check(has(o, GuestStep.PARSE), "cut short: " + causes(o));
            T.eq(0, o.services.size(), "no service with an empty start type is published");
        }
        // W4: a password that straddles the cut of a fault text is scrubbed before the cut
        try (Rig r = new Rig()) {
            GuestFx.Guest g = r.fx.guest(VM);
            g.echoSecrets = true;
            g.echoPad = 160;
            g.pass = "wrong";
            r.fx.guest(VM).user = USER;
            // the server echoes the account and the password it was given, which is the one being tried
            GuestAuth auth = new GuestAuth(USER, "S3cretLongPasswordValue");
            GuestOpsClient c = new GuestOpsClient(r.vim, auth);
            g.pass = "other";
            com.vcfcf.adapters.vcommunity.vim.VimResult<String> d = c.createTemporaryDirectory(VM, "p", "s", "C:\\Windows\\Temp");
            T.check(!d.isOk(), "refused");
            T.notContains(d.message(), "S3cret", "no start of the password");
            T.notContains(d.message(), "LongPassword", "no end of the password");
            T.notContains(d.message(), USER, "nor the account");
        }
        // W6: no SHA-1 in the JVM (a FIPS provider): said once, before any connection
        try (Rig r = new Rig()) {
            r.trust = new GuestTrust(GuestTlsMode.PINNED, Map.of("host-1", r.fx.esxiSha1()), true, () -> false);
            r.build(new HttpsGuestHttp(2000, 5000));
            GuestRunner.Outcome o = r.run(plan());
            T.eq(GuestStep.TLS, o.fatal, "refused");
            T.contains(o.failures.get(0).message, "SHA-1", "the reason");
            T.eq(0, r.fx.esxi.connections.get(), "no connection");
        }
        // N5: a host read that did not finish is not "vCenter records no thumbprint"
        try (Rig r = new Rig()) {
            r.trust = new GuestTrust(GuestTlsMode.PINNED, Collections.<String, String>emptyMap(), false, () -> true);
            r.build(new HttpsGuestHttp(2000, 5000));
            GuestRunner.Outcome o = r.run(plan());
            T.contains(o.failures.get(0).message, "did not finish", "told as what it is");
        }
        // W7: the VM moved to another host after the cycle read it: asked again on the new host, no refusal recorded
        try (Rig r = new Rig(); FakeVc esxi2 = new FakeVc(q -> new FakeVc.Resp(404, null, ""))) {
            esxi2.setRestHandler(r.fx::transfer);
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte x : sha.digest(esxi2.cert.getEncoded())) hex.append(String.format("%02x", x & 0xff));
            GuestFx.Guest g = r.fx.guest(VM);
            g.urlPort = esxi2.port();
            g.currentHost = "host-2";
            r.trust = new GuestTrust(GuestTlsMode.PINNED, Map.of("host-1", r.fx.esxiSha1(), "host-2", hex.toString()));
            r.build(new HttpsGuestHttp(2000, 5000));
            GuestRunner.Job j = r.job(plan());
            j.hostNameOf = h -> "localhost";
            GuestRunner.Outcome o = r.runner.run(j, System.currentTimeMillis() + 60_000);
            T.eq("", causes(o), "after the move the transfers went to the new host and passed its pin");
            T.eq("host-2", j.hostMoref, "the new host is the one used");
            T.eq(3, o.scriptsOk, "all three programs");
        }
        // ... and a certificate that is wrong while the VM has not moved is still a refusal
        try (Rig r = new Rig(); GuestFx other = new GuestFx(FakeVc.happy(), true)) {
            r.fx.guest(VM).currentHost = "host-1";
            r.trust = new GuestTrust(GuestTlsMode.PINNED, Map.of("host-1", other.esxiSha1()));
            r.build(new HttpsGuestHttp(2000, 5000));
            GuestRunner.Outcome o = r.run(plan());
            T.eq(GuestStep.TLS, o.fatal, "not moved: still refused");
        }
        // W9: a directory path in another case is still ours, and is removed
        try (Rig r = new Rig()) {
            r.fx.guest(VM).oddDirectory = "c:\\windows\\temp\\vcfoperationsvcommunity-77-osinfo-temp";
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Collections.<String>emptyList(), false, null));
            T.eq("", causes(o), "the path form of another case is accepted");
            T.check(o.operatingSystem != null, "and the program ran");
            T.eq(0, r.fx.liveDirs().size(), "and the directory is removed");
        }
        // N4: when the guest refuses the account while leftovers are being removed, every leftover is kept
        try (Rig r = new Rig()) {
            r.fx.guest(VM).pass = "changed";
            GuestRunner.Job j = r.job(plan());
            j.leftover = new ArrayList<>(Arrays.asList("C:\\Windows\\Temp\\VCFOperationsvCommunity-1-OSInfo-TEMP",
                    "C:\\Windows\\Temp\\VCFOperationsvCommunity-2-Services-TEMP"));
            GuestRunner.Outcome o = r.runner.run(j, System.currentTimeMillis() + 60_000);
            T.eq(GuestStep.AUTH, o.fatal, "refused");
            T.eq(2, o.leftBehind.size(), "both are remembered for the next time");
            T.eq(false, o.loggedOn, "a refused logon is not proof that the account works");
        }
        // N6: a VM powered off in the middle, and the other refusals of guest operations
        for (String[] f : new String[][] {{"InvalidPowerState", "TOOLS"}, {"GuestComponentsOutOfDate", "UNAVAILABLE"},
            {"OperationDisabledByGuest", "UNAVAILABLE"}}) {
            try (Rig r = new Rig()) {
                r.fx.guest(VM).fault = f[0];
                T.eq(GuestStep.valueOf(f[1]), r.run(plan()).fatal, f[0]);
            }
        }
        // W10: a transfer is given no more time than the VM has left
        try (Rig r = new Rig()) {
            r.fx.esxi.setRestHandler(q -> {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new FakeVc.Resp(200, null, "");
            });
            long t0 = System.currentTimeMillis();
            GuestRunner.Outcome o = r.runner.run(r.job(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML)),
                    t0 + 1500);
            long took = System.currentTimeMillis() - t0;
            T.check(has(o, GuestStep.TIMEOUT), "timed out: " + causes(o));
            T.check(took < 3900, "within the VM's own time, not the transfer's five seconds: " + took + " ms");
            T.eq(0, r.fx.liveDirs().size(), "and cleaned up");
        }
        T.done("guest: the review's findings for one VM");
    }

    static void cleanupOnInterrupt() throws Exception {
        try (Rig r = new Rig()) {
            r.fx.guest(VM).hang.add("services");
            r.limits.scriptMs = 600_000; // it would wait for ten minutes
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    r.runner.run(r.job(plan()), System.currentTimeMillis() + 600_000);
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            t.start();
            long end = System.currentTimeMillis() + 10_000;
            while (r.fx.starts.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10);
            T.check(r.fx.starts.get() > 0, "the program started");
            t.interrupt();
            t.join(20_000);
            T.check(!t.isAlive(), "the worker ended");
            T.check(thrown.get() instanceof InterruptedException, "the interrupt was passed on: " + thrown.get());
            T.eq(0, r.fx.liveDirs().size(), "the directory is removed after a cancel");
            T.eq(1, r.fx.count("TerminateProcessInGuest"), "the program is stopped after a cancel");
        }
        T.done("guest: a cancelled run stops its program and removes its directory");
    }

    static void tlsModes() throws Exception {
        // right certificate: SHA-1 as vCenter writes it, and SHA-256
        for (int i = 0; i < 2; i++) {
            try (Rig r = new Rig()) {
                r.trust = new GuestTrust(GuestTlsMode.PINNED, Map.of("host-1", i == 0 ? r.fx.esxiSha1() : r.fx.esxiSha256()));
                r.build(new HttpsGuestHttp(2000, 5000));
                GuestRunner.Outcome o = r.run(plan());
                T.eq("", causes(o), "the pinned certificate is accepted (" + (i == 0 ? "SHA-1" : "SHA-256") + ")");
            }
        }
        // another host's certificate: nothing is sent, in Pinned and in Strict
        for (GuestTlsMode mode : new GuestTlsMode[] {GuestTlsMode.PINNED, GuestTlsMode.STRICT}) {
            try (Rig r = new Rig(); GuestFx other = new GuestFx(FakeVc.happy(), true)) {
                r.trust = new GuestTrust(mode, Map.of("host-1", other.esxiSha1()));
                r.build(new HttpsGuestHttp(2000, 5000));
                GuestRunner.Outcome o = r.run(plan());
                T.eq(GuestStep.TLS, o.fatal, mode + ": a certificate that is not the recorded one");
                T.eq(0, r.fx.esxi.requests.size(), mode + ": not one request reached the ESXi host");
                String m = o.failures.get(0).message;
                T.contains(m, "other than the one vCenter has recorded", "the message says why");
                T.notContains(m, "secret-ticket", "the ticket in the URL is not in a message");
                T.eq(0, r.fx.liveDirs().size(), "directory removed after a refused transfer");
                T.eq(1, r.fx.count("CreateTemporaryDirectoryInGuest"), "the other programs are not tried: same host, same certificate");
            }
        }
        // no recorded thumbprint: refused in Pinned and Strict, with no connection at all
        for (GuestTlsMode mode : new GuestTlsMode[] {GuestTlsMode.PINNED, GuestTlsMode.STRICT}) {
            try (Rig r = new Rig()) {
                r.trust = new GuestTrust(mode, Collections.<String, String>emptyMap());
                r.build(new HttpsGuestHttp(2000, 5000));
                GuestRunner.Outcome o = r.run(plan());
                T.eq(GuestStep.TLS, o.fatal, mode + ": no thumbprint");
                T.eq(0, r.fx.esxi.connections.get(), mode + ": no connection was made");
                T.contains(o.failures.get(0).message, "no usable certificate thumbprint", "the reason");
            }
        }
        // a thumbprint that is not hex is not a thumbprint
        try (Rig r = new Rig()) {
            r.trust = new GuestTrust(GuestTlsMode.PINNED, Map.of("host-1", "not a thumbprint"));
            r.build(new HttpsGuestHttp(2000, 5000));
            T.eq(GuestStep.TLS, r.run(plan()).fatal, "garbage thumbprint");
            T.eq(0, r.fx.esxi.connections.get(), "no connection");
        }
        // Disabled: the administrator asked for no check
        try (Rig r = new Rig(); GuestFx other = new GuestFx(FakeVc.happy(), true)) {
            r.trust = new GuestTrust(GuestTlsMode.DISABLED, Map.of("host-1", other.esxiSha1()));
            r.build(new HttpsGuestHttp(2000, 5000));
            T.eq("", causes(r.run(plan())), "Disabled accepts any certificate");
        }
        // an answer that redirects is not followed
        try (Rig r = new Rig()) {
            r.fx.esxi.setRestHandler(q -> new FakeVc.Resp(302, null, "").withLocation("https://127.0.0.1:"
                    + r.fx.esxi.port() + "/elsewhere"));
            GuestRunner.Outcome o = r.run(GuestPlan.create(false, Collections.<String>emptyList(), true, EVENT_XML));
            T.check(has(o, GuestStep.UPLOAD), "a redirect is a failed transfer: " + causes(o));
            T.eq(1, r.fx.esxi.requests.size(), "and was not followed");
        }
        T.done("guest: TLS to the ESXi host: pinned SHA-1 and SHA-256, wrong certificate sends nothing, no thumbprint, Disabled");
    }

    static void transferUrls() throws Exception {
        // a host of "*": the ESXi host's name takes its place
        try (Rig r = new Rig()) {
            r.fx.guest(VM).urlHost = "*";
            T.eq("", causes(r.run(plan())), "the * host is replaced by the host's name");
        }
        try (Rig r = new Rig()) {
            r.fx.guest(VM).urlHost = "*";
            GuestRunner.Job j = r.job(plan());
            j.hostName = null;
            GuestRunner.Outcome o = r.runner.run(j, System.currentTimeMillis() + 60_000);
            T.check(has(o, GuestStep.UPLOAD), "no host name to replace it with: " + causes(o));
            T.eq(0, r.fx.esxi.connections.get(), "no connection");
        }
        // user information in the URL
        try (Rig r = new Rig()) {
            r.fx.guest(VM).urlHost = "attacker:pw@127.0.0.1";
            GuestRunner.Outcome o = r.run(GuestPlan.create(true, Arrays.asList("a"), false, null));
            T.check(has(o, GuestStep.UPLOAD), "user information is refused: " + causes(o));
            T.eq(0, r.fx.esxi.connections.get(), "no connection");
            T.notContains(o.failures.get(0).message, "attacker", "and not repeated");
        }
        T.done("guest: transfer URLs: * host replaced, user information refused");
    }

    static void noSecretAnywhere() throws Exception {
        try (Rig r = new Rig()) {
            // a server that repeats the account and the password in its fault text
            r.fx.guest(VM).echoSecrets = true;
            r.fx.guest(VM).pass = "wrong";
            GuestRunner.Outcome o = r.run(plan());
            for (GuestFailure f : o.failures) {
                T.notContains(f.message, SECRET, "the password is not in a failure");
                T.notContains(f.toString(), SECRET, "the password is not in a failure");
            }
            T.check(o.failures.get(0).message.contains("***"), "it was scrubbed: " + o.failures.get(0).message);
            T.notContains(r.log.all(), SECRET, "the password is not in the log");
            T.notContains(r.log.all(), "wrong", "nor the one that was tried");
        }
        try (Rig r = new Rig()) {
            r.run(plan());
            for (String a : r.fx.argumentStrings) {
                T.notContains(a, SECRET, "no password on a command line");
                T.notContains(a, USER, "no account name on a command line");
                T.check(a.startsWith("-NoLogo -NoProfile -NonInteractive -EncodedCommand "), a);
            }
            for (String p : r.fx.programs) {
                T.notContains(p, SECRET, "no password in a program text");
                T.notContains(p, USER, "no account name in a program text");
            }
            // the password is in the request to vCenter and nowhere else it should not be
            int withPassword = 0;
            for (String b : r.fx.bodies) if (b.contains(SECRET)) withPassword++;
            T.eq(r.fx.bodies.size(), withPassword, "every guest request carries the account in its auth element");
            for (String b : r.fx.bodies) {
                String stripped = b.replaceAll("<auth .*?</auth>", "");
                T.notContains(stripped, SECRET, "the password is only in the auth element");
            }
            T.notContains(r.log.all(), SECRET, "the password is not in the log");
            T.notContains(new GuestAuth(USER, SECRET).toString(), SECRET, "toString is silent");
            // the file names in the guest: no account, no password, a directory the guest named
            for (String l : r.fx.log) T.notContains(l, SECRET, "operation log");
        }
        T.done("guest: the account and password reach only the auth element of a vCenter request");
    }
}
