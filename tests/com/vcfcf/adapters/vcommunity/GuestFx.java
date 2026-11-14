package com.vcfcf.adapters.vcommunity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake vCenter guest operations service and the ESXi file transfer endpoint behind it, for the guest tests. The
 * vCenter side wraps another handler (the property collector fake) and answers only the guest operations, with
 * their exact vim25 names: a request under any other spelling is a fault, as it is on a real vCenter. The ESXi
 * side is a {@link FakeVc} of its own (a real TLS server with its own certificate) that takes PUTs and serves GETs.
 *
 * <p>Each VM has a {@link Guest}: its account, what its scripts write, whether they hang, whether it refuses. A
 * script's behaviour is chosen by what the started program's decoded text names, so the fake sees the same
 * program a real guest would run.
 */
final class GuestFx implements FakeVc.Handler, AutoCloseable {

    static final String PS = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    /** What one guest does. */
    static final class Guest {
        String user = "svc-guest";
        String pass = "S3cret-Pass!";
        /** Fault class for every call (GuestOperationsUnavailable, InvalidState), or null. */
        String fault;
        /** Output of each script by kind (os, services, events); null: the program writes nothing. */
        final Map<String, String> csv = new HashMap<>();
        /** The kinds whose program never ends. */
        final Set<String> hang = new HashSet<>();
        /** Exit code by kind (default 0). */
        final Map<String, Integer> exit = new HashMap<>();
        /** The kinds whose program cannot be started. */
        final Set<String> noStart = new HashSet<>();
        boolean failDelete;
        /** A fault text that repeats the account and the password, as a careless server might. */
        boolean echoSecrets;
        /** Characters of filler before the echoed password, to make it straddle the cut of a fault text. */
        int echoPad;
        /** The host the VM runs on now (a RetrievePropertiesEx of its runtime.host answers it), or null. */
        String currentHost;
        /** The port of the ESXi host the transfer URLs name, or 0 for this fake's own. */
        int urlPort;
        /** A file transfer URL for the output that names this host (or "*"), instead of the fake ESXi host. */
        String urlHost;
        /** Fail every upload with this HTTP status instead of storing it. */
        int uploadStatus;
        /** Make the directory name something the port must not trust. */
        String oddDirectory;
        /** Make the name of a file the guest creates something the port must not trust. */
        String oddFile;
        /** Report the program as gone from the process list at once (no exit code). */
        boolean vanish;
    }

    private static final class Dir {
        final String vm;
        final String kind;
        final Map<String, byte[]> files = new TreeMap<>();

        Dir(String vm, String kind) {
            this.vm = vm;
            this.kind = kind;
        }
    }

    private static final class Proc {
        final String vm;
        final String dir;
        final String kind;
        boolean terminated;
        boolean hang;
        int exit;

        Proc(String vm, String dir, String kind) {
            this.vm = vm;
            this.dir = dir;
            this.kind = kind;
        }
    }

    final FakeVc.Handler inner;
    final FakeVc esxi;
    final Map<String, Guest> guests = new ConcurrentHashMap<>();
    /** Every guest operation, as {@code Name vm}. */
    final List<String> log = new CopyOnWriteArrayList<>();
    /** The decoded program of every started process, and its argument string as sent. */
    final List<String> programs = new CopyOnWriteArrayList<>();
    final List<String> argumentStrings = new CopyOnWriteArrayList<>();
    /** The request bodies of every guest operation (for the secret scan). */
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final Map<String, Dir> dirs = new ConcurrentHashMap<>();
    private final Map<Long, Proc> procs = new ConcurrentHashMap<>();
    private final Map<String, String[]> tickets = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1000);
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger starts = new AtomicInteger();
    /** The most StartProgramInGuest calls being answered at once (the fake holds each for {@link #startDelayMs}). */
    final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    /** Sleep this long inside every StartProgramInGuest (to hold a worker). */
    volatile int startDelayMs;

    GuestFx(FakeVc.Handler inner) throws Exception {
        this(inner, false);
    }

    /** @param ownCertificate true: the ESXi host has a certificate of its own, not the run's shared one */
    GuestFx(FakeVc.Handler inner, boolean ownCertificate) throws Exception {
        this.inner = inner;
        FakeVc.Handler none = r -> new FakeVc.Resp(404, null, "");
        this.esxi = ownCertificate ? new FakeVc(none) : FakeVc.withSharedCertificate(none);
        esxi.setRestHandler(this::transfer);
    }

    /** A guest with the default account. */
    Guest guest(String vm) {
        return guests.computeIfAbsent(vm, k -> new Guest());
    }

    /** The ESXi thumbprint vCenter would record: SHA-1 of the fake host's certificate, as colon separated hex. */
    String esxiSha1() throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(esxi.cert.getEncoded());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) b.append(i == 0 ? "" : ":").append(String.format("%02X", d[i] & 0xff));
        return b.toString();
    }

    String esxiSha256() throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(esxi.cert.getEncoded());
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x & 0xff));
        return b.toString();
    }

    /** The directories made and not yet removed. */
    List<String> liveDirs() {
        return new ArrayList<>(dirs.keySet());
    }

    int count(String op) {
        int n = 0;
        for (String l : log) if (l.startsWith(op + " ")) n++;
        return n;
    }

    // ------------------------------------------------------------------ vCenter side

    private static final Pattern VM = Pattern.compile("<vm type=\"VirtualMachine\">([^<]*)</vm>");
    private static final Pattern USER = Pattern.compile("<username>([^<]*)</username>");
    private static final Pattern PASS = Pattern.compile("<password>([^<]*)</password>");

    private static String first(String body, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(body);
        return m.find() ? unesc(m.group(1)) : null;
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static FakeVc.Resp ok(String inner) {
        return new FakeVc.Resp(200, null, SoapFx.envelope(inner));
    }

    @Override
    public FakeVc.Resp handle(FakeVc.Req r) {
        switch (r.action) {
            case "RetrieveServiceContent": {
                FakeVc.Resp base = inner.handle(r);
                return new FakeVc.Resp(base.status, base.setCookie, base.body.replace("</returnval>",
                        "<guestOperationsManager type=\"GuestOperationsManager\">guestOperationsManager"
                        + "</guestOperationsManager></returnval>"), base.contentType);
            }
            case "RetrievePropertiesEx":
                if (r.body.contains("<type>GuestOperationsManager</type>")) {
                    return ok("<RetrievePropertiesExResponse xmlns=\"urn:vim25\"><returnval><objects>"
                            + "<obj type=\"GuestOperationsManager\">guestOperationsManager</obj>"
                            + "<propSet><name>fileManager</name><val type=\"GuestFileManager\" "
                            + "xsi:type=\"ManagedObjectReference\">guestFileManager</val></propSet>"
                            + "<propSet><name>processManager</name><val type=\"GuestProcessManager\" "
                            + "xsi:type=\"ManagedObjectReference\">guestProcessManager</val></propSet>"
                            + "</objects></returnval></RetrievePropertiesExResponse>");
                }
                if (r.body.contains("<type>VirtualMachine</type>") && r.body.contains("<pathSet>runtime.host</pathSet>")) {
                    Matcher mo = Pattern.compile("<obj type=\"VirtualMachine\">([^<]*)</obj>").matcher(r.body);
                    Guest g = mo.find() ? guests.get(mo.group(1)) : null;
                    if (g != null && g.currentHost != null) {
                        return ok("<RetrievePropertiesExResponse xmlns=\"urn:vim25\"><returnval><objects>"
                                + "<obj type=\"VirtualMachine\">" + mo.group(1) + "</obj><propSet><name>runtime.host</name>"
                                + "<val type=\"HostSystem\" xsi:type=\"ManagedObjectReference\">" + g.currentHost
                                + "</val></propSet></objects></returnval></RetrievePropertiesExResponse>");
                    }
                }
                return inner.handle(r);
            case "CreateTemporaryDirectoryInGuest":
            case "CreateTemporaryFileInGuest":
            case "InitiateFileTransferToGuest":
            case "InitiateFileTransferFromGuest":
            case "ListFilesInGuest":
            case "DeleteDirectoryInGuest":
            case "StartProgramInGuest":
            case "ListProcessesInGuest":
            case "TerminateProcessInGuest":
                return guestOp(r);
            default:
                // a guest operation under any other spelling is refused, as a real vCenter refuses it
                if (r.action.toLowerCase().endsWith("inguest")) {
                    return FakeVcenter.serverFault("The method " + r.action + " is not found");
                }
                return inner.handle(r);
        }
    }

    private FakeVc.Resp guestOp(FakeVc.Req r) {
        bodies.add(r.body);
        Matcher mv = VM.matcher(r.body);
        String vm = mv.find() ? mv.group(1) : null;
        log.add(r.action + " " + vm);
        Guest g = vm == null ? null : guests.get(vm);
        if (g == null) return FakeVc.fault("ManagedObjectNotFound", "no such VM");
        Matcher mu = USER.matcher(r.body);
        Matcher mp = PASS.matcher(r.body);
        String user = mu.find() ? unesc(mu.group(1)) : "";
        String pass = mp.find() ? unesc(mp.group(1)) : "";
        String echo = g.echoSecrets ? " (account " + user + ", " + "z".repeat(g.echoPad) + "password " + pass + ")" : "";
        if (g.fault != null) return FakeVc.fault(g.fault, "guest operation refused" + echo);
        if (!g.user.equals(user) || !g.pass.equals(pass)) {
            return FakeVc.fault("InvalidGuestLogin", "Failed to authenticate with the guest operating system "
                    + "using the supplied credentials." + echo);
        }
        switch (r.action) {
            case "CreateTemporaryDirectoryInGuest": {
                String path = g.oddDirectory != null ? g.oddDirectory : first(r.body, "directoryPath") + "\\"
                        + first(r.body, "prefix") + seq.incrementAndGet() + first(r.body, "suffix");
                String suffix = first(r.body, "suffix");
                dirs.put(path, new Dir(vm, suffix.contains("Services") ? "services"
                        : suffix.contains("EventLog") ? "events" : "os"));
                return ok("<CreateTemporaryDirectoryInGuestResponse xmlns=\"urn:vim25\"><returnval>" + SoapFx.esc(path)
                        + "</returnval></CreateTemporaryDirectoryInGuestResponse>");
            }
            case "CreateTemporaryFileInGuest": {
                String dir = first(r.body, "directoryPath");
                Dir d = dirs.get(dir);
                if (d == null) return FakeVc.fault("FileNotFound", "no such directory");
                if (g.oddFile != null) {
                    return ok("<CreateTemporaryFileInGuestResponse xmlns=\"urn:vim25\"><returnval>" + SoapFx.esc(g.oddFile)
                            + "</returnval></CreateTemporaryFileInGuestResponse>");
                }
                String leaf = first(r.body, "prefix") + seq.incrementAndGet() + first(r.body, "suffix");
                d.files.put(leaf, new byte[0]);
                return ok("<CreateTemporaryFileInGuestResponse xmlns=\"urn:vim25\"><returnval>" + SoapFx.esc(dir + "\\" + leaf)
                        + "</returnval></CreateTemporaryFileInGuestResponse>");
            }
            case "InitiateFileTransferToGuest": {
                String path = first(r.body, "guestFilePath");
                String token = "u" + seq.incrementAndGet();
                tickets.put(token, new String[] {"PUT", vm, path});
                return ok("<InitiateFileTransferToGuestResponse xmlns=\"urn:vim25\"><returnval>" + SoapFx.esc(url(g, token))
                        + "</returnval></InitiateFileTransferToGuestResponse>");
            }
            case "InitiateFileTransferFromGuest": {
                String path = first(r.body, "guestFilePath");
                Dir d = dirs.get(path.substring(0, path.lastIndexOf('\\')));
                byte[] data = d == null ? null : d.files.get(path.substring(path.lastIndexOf('\\') + 1));
                if (data == null) return FakeVc.fault("FileNotFound", "the file does not exist");
                String token = "d" + seq.incrementAndGet();
                tickets.put(token, new String[] {"GET", vm, path});
                return ok("<InitiateFileTransferFromGuestResponse xmlns=\"urn:vim25\"><returnval>"
                        + "<attributes xsi:type=\"GuestFileAttributes\"/><size>" + data.length + "</size><url>"
                        + SoapFx.esc(url(g, token)) + "</url></returnval></InitiateFileTransferFromGuestResponse>");
            }
            case "ListFilesInGuest": {
                Dir d = dirs.get(first(r.body, "filePath"));
                if (d == null) return FakeVc.fault("FileNotFound", "no such directory");
                StringBuilder b = new StringBuilder();
                for (String n : d.files.keySet()) {
                    b.append("<files><path>").append(SoapFx.esc(n)).append("</path><type>file</type><size>")
                            .append(d.files.get(n).length).append("</size></files>");
                }
                return ok("<ListFilesInGuestResponse xmlns=\"urn:vim25\"><returnval>" + b
                        + "<remaining>0</remaining></returnval></ListFilesInGuestResponse>");
            }
            case "DeleteDirectoryInGuest": {
                String path = first(r.body, "directoryPath");
                if (g.failDelete) return FakeVc.fault("FileFault", "the directory is in use");
                deletes.incrementAndGet();
                if (dirs.remove(path) == null) return FakeVc.fault("FileNotFound", "no such directory");
                return ok("<DeleteDirectoryInGuestResponse xmlns=\"urn:vim25\"/>");
            }
            case "StartProgramInGuest": {
                int d = startDelayMs;
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    if (d > 0) Thread.sleep(d);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                String program = first(r.body, "programPath");
                String args = first(r.body, "arguments");
                argumentStrings.add(args);
                String text = decode(args);
                programs.add(text);
                String kind = text.contains("Win32_OperatingSystem") ? "os" : text.contains("Get-Service") ? "services"
                        : text.contains("Get-WinEvent") ? "events" : "unknown";
                if (!PS.equals(program) || g.noStart.contains(kind)) {
                    return FakeVc.fault("GuestOperationsFault", "the program could not be started");
                }
                String wd = first(r.body, "workingDirectory");
                Matcher inFile = Pattern.compile("\\$OutputFile = '([^']*)'").matcher(text);
                String out = inFile.find() ? inFile.group(1) : null;
                long pid = seq.incrementAndGet();
                String dir = out == null ? wd : out.substring(0, out.lastIndexOf('\\'));
                Proc p = new Proc(vm, dir, kind);
                p.hang = g.hang.contains(kind);
                p.exit = g.exit.getOrDefault(kind, 0);
                procs.put(pid, p);
                starts.incrementAndGet();
                Dir dd = dirs.get(dir);
                String csv = g.csv.get(kind);
                if (!p.hang && csv != null && dd != null) {
                    dd.files.put(out.substring(out.lastIndexOf('\\') + 1), csv.getBytes(StandardCharsets.UTF_8));
                }
                return ok("<StartProgramInGuestResponse xmlns=\"urn:vim25\"><returnval>" + pid
                        + "</returnval></StartProgramInGuestResponse>");
            }
            case "ListProcessesInGuest": {
                Matcher mp2 = Pattern.compile("<pids>(\\d+)</pids>").matcher(r.body);
                long pid = mp2.find() ? Long.parseLong(mp2.group(1)) : -1;
                Proc p = procs.get(pid);
                if (p == null || g.vanish) return ok("<ListProcessesInGuestResponse xmlns=\"urn:vim25\"/>");
                if (p.hang && !p.terminated) {
                    return ok("<ListProcessesInGuestResponse xmlns=\"urn:vim25\"><returnval><name>powershell.exe</name>"
                            + "<pid>" + pid + "</pid><owner>x</owner><cmdLine>x</cmdLine><startTime>2026-11-13T10:00:00Z"
                            + "</startTime></returnval></ListProcessesInGuestResponse>");
                }
                return ok("<ListProcessesInGuestResponse xmlns=\"urn:vim25\"><returnval><name>powershell.exe</name>"
                        + "<pid>" + pid + "</pid><owner>x</owner><cmdLine>x</cmdLine><startTime>2026-11-13T10:00:00Z"
                        + "</startTime><endTime>2026-11-13T10:00:01Z</endTime><exitCode>" + (p.terminated ? -1 : p.exit)
                        + "</exitCode></returnval></ListProcessesInGuestResponse>");
            }
            case "TerminateProcessInGuest": {
                Matcher mp2 = Pattern.compile("<pid>(\\d+)</pid>").matcher(r.body);
                long pid = mp2.find() ? Long.parseLong(mp2.group(1)) : -1;
                Proc p = procs.get(pid);
                if (p != null) p.terminated = true;
                return ok("<TerminateProcessInGuestResponse xmlns=\"urn:vim25\"/>");
            }
            default:
                return FakeVcenter.serverFault("not a guest operation");
        }
    }

    private String url(Guest g, String token) {
        String host = g.urlHost != null ? g.urlHost : "127.0.0.1";
        return "https://" + host + ":" + (g.urlPort != 0 ? g.urlPort : esxi.port()) + "/guestFile?id=" + token + "&token=secret-ticket-" + token;
    }

    private static String decode(String args) {
        int i = args.lastIndexOf("-EncodedCommand ");
        if (i < 0) return "";
        return new String(Base64.getDecoder().decode(args.substring(i + 16).trim()), StandardCharsets.UTF_16LE);
    }

    // ------------------------------------------------------------------ ESXi side

    FakeVc.Resp transfer(FakeVc.Req r) {
        Matcher m = Pattern.compile("id=([a-z0-9]+)").matcher(r.path);
        String[] t = m.find() ? tickets.get(m.group(1)) : null;
        if (t == null || !t[0].equals(r.method)) return new FakeVc.Resp(404, null, "");
        String path = t[2];
        Dir d = dirs.get(path.substring(0, path.lastIndexOf('\\')));
        String leaf = path.substring(path.lastIndexOf('\\') + 1);
        if ("PUT".equals(r.method)) {
            Guest g = guests.get(t[1]);
            if (g != null && g.uploadStatus != 0) return new FakeVc.Resp(g.uploadStatus, null, "");
            if (d == null) return new FakeVc.Resp(404, null, "");
            d.files.put(leaf, r.body.getBytes(StandardCharsets.UTF_8));
            return new FakeVc.Resp(200, null, "");
        }
        byte[] data = d == null ? null : d.files.get(leaf);
        if (data == null) return new FakeVc.Resp(404, null, "");
        return new FakeVc.Resp(200, null, new String(data, StandardCharsets.UTF_8), "application/octet-stream");
    }

    /** What the ESXi host received (method and path), without the ticket. */
    List<String> esxiRequests() {
        List<String> out = new ArrayList<>();
        for (FakeVc.Req q : esxi.requests) out.add(q.action);
        return Collections.unmodifiableList(out);
    }

    @Override
    public void close() {
        esxi.close();
    }
}
