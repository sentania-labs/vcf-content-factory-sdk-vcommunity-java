package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.vim.MoRef;
import com.vcfcf.adapters.vcommunity.vim.ServiceRefs;
import com.vcfcf.adapters.vcommunity.vim.VimReader;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimXml;
import com.vcfcf.adapters.vcommunity.vim.XmlNode;

import java.net.SocketTimeoutException;

/**
 * The calls of the guest file and process managers, over the vCenter session the property reads use. Every
 * call is one vim25 operation (the wire names are the managers' method names with a capital first letter,
 * as for any other vim25 operation), carries the Windows account in its {@code auth} argument and returns its
 * own {@link VimResult}: nothing is shared between the calls of different VMs, so a fault on one cannot be
 * read as another's. A fault message is passed through {@link GuestAuth#scrub} before it is stored.
 */
public final class GuestOpsClient {

    /** How long one call may take. */
    public static final int CALL_MS = 30_000;
    private static final int MAX_FILES = 100;

    /** The two managers of the guest operations manager. */
    static final class Managers {
        final MoRef files;
        final MoRef processes;

        Managers(MoRef files, MoRef processes) {
            this.files = files;
            this.processes = processes;
        }
    }

    /** What a file transfer call answers: where to send the HTTPS request (an ESXi host's URL), and the size when named. */
    public static final class Transfer {
        public final String url;
        public final long size;

        Transfer(String url, long size) {
            this.url = url;
            this.size = size;
        }
    }

    /** What a process listing says about one process. */
    public static final class ProcessState {
        /** The process is no longer listed. */
        public final boolean gone;
        public final boolean exited;
        /** The exit code, or null when the guest gave none. */
        public final Integer exitCode;

        ProcessState(boolean gone, boolean exited, Integer exitCode) {
            this.gone = gone;
            this.exited = exited;
            this.exitCode = exitCode;
        }
    }

    private final VimReader vim;
    private final GuestAuth auth;
    private final int callMs;
    /** The time this thread's VM runs out (epoch ms), or 0: a call is never allowed past it. */
    private final ThreadLocal<long[]> stop = ThreadLocal.withInitial(() -> new long[] {0});
    /**
     * True once a call of this thread's VM run was refused for the account: no other call of that run carries the account
     * (DV-97: a refusal ends the run, and the cleanup, the process polls and the retries must not each be another refused
     * logon).
     */
    private final ThreadLocal<boolean[]> refused = ThreadLocal.withInitial(() -> new boolean[] {false});
    private final java.util.function.LongSupplier clock;

    private long managersGeneration = -1;
    private Managers managers;

    public GuestOpsClient(VimReader vim, GuestAuth auth) {
        this(vim, auth, CALL_MS);
    }

    public GuestOpsClient(VimReader vim, GuestAuth auth, int callMs) {
        this(vim, auth, callMs, System::currentTimeMillis);
    }

    public GuestOpsClient(VimReader vim, GuestAuth auth, int callMs, java.util.function.LongSupplier clock) {
        this.vim = vim;
        this.auth = auth;
        this.callMs = callMs;
        this.clock = clock;
    }

    /** The shortest a call is given, so that a removal after the time is up still has a moment to be made. */
    static final int MIN_CALL_MS = 3_000;

    /** Calls made by this thread are given no more time than is left until {@code stopMs}; 0 removes the limit. */
    public void limitTo(long stopMs) {
        stop.get()[0] = stopMs;
        refused.get()[0] = false; // a new run (or the end of one) starts with the account trusted again
    }

    /** A call that carries the account: not sent after the account was refused in this run. */
    private VimResult<XmlNode> withAccount(String body, String operation) {
        if (refused.get()[0]) {
            return VimResult.fault("InvalidGuestLogin", "not sent: the Windows account was refused earlier in this run "
                    + "of this VM", null, 0, null);
        }
        VimResult<XmlNode> r = vim.call(body, operation, timeout());
        if (!r.isOk() && classify(r, null) == GuestStep.AUTH) refused.get()[0] = true;
        return r;
    }

    /** How long one call may take: the call limit, or the time left for this VM if that is shorter (at least a moment). */
    int timeout() {
        long s = stop.get()[0];
        if (s <= 0) return callMs;
        long left = s - clock.getAsLong();
        return (int) Math.max(MIN_CALL_MS, Math.min(callMs, left));
    }

    /** The MoRef value of the host the VM runs on now, or null when it cannot be read. */
    public String currentHost(String vm) {
        ServiceRefs refs = vim.refs();
        if (refs == null || refs.propertyCollector == null) return null;
        String body = "<RetrievePropertiesEx xmlns=\"urn:vim25\"><_this type=\"PropertyCollector\">"
                + VimXml.escape(refs.propertyCollector.value) + "</_this><specSet><propSet><type>VirtualMachine</type>"
                + "<all>false</all><pathSet>runtime.host</pathSet></propSet><objectSet><obj type=\"VirtualMachine\">"
                + VimXml.escape(vm) + "</obj><skip>false</skip></objectSet></specSet><options/></RetrievePropertiesEx>";
        VimResult<XmlNode> r = vim.call(body, "RetrievePropertiesEx", timeout());
        if (!r.isOk()) return null;
        XmlNode objects = r.value().find("objects");
        if (objects == null) return null;
        for (XmlNode ps : objects.children()) {
            if (!"propSet".equals(ps.name)) continue;
            XmlNode val = ps.child("val");
            if (val != null && !val.text().isEmpty()) return val.text();
        }
        return null;
    }

    public GuestAuth auth() {
        return auth;
    }

    // ------------------------------------------------------------------ managers

    /** The file and process managers of this session, asked for once per login. */
    synchronized VimResult<Managers> managers() {
        ServiceRefs refs = vim.refs();
        if (refs == null || refs.guestOperationsManager == null || refs.propertyCollector == null) {
            return VimResult.fault("Unavailable", "this vCenter session names no guest operations manager",
                    null, 0, null);
        }
        long gen = vim.generation();
        if (managers != null && gen == managersGeneration) return VimResult.ok(managers, 0);
        MoRef g = refs.guestOperationsManager;
        String body = "<RetrievePropertiesEx xmlns=\"urn:vim25\"><_this type=\"PropertyCollector\">"
                + VimXml.escape(refs.propertyCollector.value) + "</_this><specSet><propSet>"
                + "<type>GuestOperationsManager</type><all>false</all><pathSet>fileManager</pathSet>"
                + "<pathSet>processManager</pathSet></propSet><objectSet><obj type=\"GuestOperationsManager\">"
                + VimXml.escape(g.value) + "</obj><skip>false</skip></objectSet></specSet><options/>"
                + "</RetrievePropertiesEx>";
        VimResult<XmlNode> r = vim.call(body, "RetrievePropertiesEx", timeout());
        if (!r.isOk()) return VimResult.fault(r.faultClass(), clean(r), null, 0, r.cause());
        MoRef files = null;
        MoRef procs = null;
        XmlNode objects = r.value().find("objects");
        if (objects != null) {
            for (XmlNode ps : objects.children()) {
                if (!"propSet".equals(ps.name)) continue;
                XmlNode val = ps.child("val");
                if (val == null || val.text().isEmpty()) continue;
                String name = ps.childText("name");
                String type = val.attr("type");
                if ("fileManager".equals(name)) files = new MoRef(type == null ? "GuestFileManager" : type, val.text());
                if ("processManager".equals(name)) procs = new MoRef(type == null ? "GuestProcessManager" : type, val.text());
            }
        }
        if (files == null || procs == null) {
            return VimResult.fault("Unavailable", "the guest operations manager has no file or process manager",
                    null, 0, null);
        }
        managers = new Managers(files, procs);
        managersGeneration = gen;
        return VimResult.ok(managers, 0);
    }

    // ------------------------------------------------------------------ files

    /** A new directory in {@code parent}, named by the guest from the prefix and suffix; its path. */
    public VimResult<String> createTemporaryDirectory(String vm, String prefix, String suffix, String parent) {
        return string("CreateTemporaryDirectoryInGuest", vm, "<prefix>" + VimXml.escape(prefix)
                + "</prefix><suffix>" + VimXml.escape(suffix) + "</suffix><directoryPath>"
                + VimXml.escape(parent) + "</directoryPath>");
    }

    /** A new empty file in {@code directory}, named by the guest from the prefix and suffix; its path. */
    public VimResult<String> createTemporaryFile(String vm, String prefix, String suffix, String directory) {
        return string("CreateTemporaryFileInGuest", vm, "<prefix>" + VimXml.escape(prefix) + "</prefix><suffix>"
                + VimXml.escape(suffix) + "</suffix><directoryPath>" + VimXml.escape(directory) + "</directoryPath>");
    }

    /** The ESXi host's URL to which the file's content is PUT over HTTPS. */
    public VimResult<String> initiateFileTransferToGuest(String vm, String path, long size) {
        return string("InitiateFileTransferToGuest", vm, "<guestFilePath>" + VimXml.escape(path)
                + "</guestFilePath><fileAttributes xsi:type=\"GuestFileAttributes\"/><fileSize>" + size
                + "</fileSize><overwrite>true</overwrite>");
    }

    /** The ESXi host's URL from which the file's content is GET over HTTPS, with its size when named. */
    public VimResult<Transfer> initiateFileTransferFromGuest(String vm, String path) {
        VimResult<XmlNode> r = file("InitiateFileTransferFromGuest", vm, "<guestFilePath>"
                + VimXml.escape(path) + "</guestFilePath>");
        if (!r.isOk()) return VimResult.fault(r.faultClass(), clean(r), null, 0, r.cause());
        XmlNode rv = r.value().find("returnval");
        String url = rv == null ? null : rv.childText("url");
        if (url == null || url.isEmpty()) {
            return VimResult.fault("Parse", "InitiateFileTransferFromGuest answered no URL", null, 0, null);
        }
        long size = -1;
        String s = rv.childText("size");
        if (s != null && s.matches("[0-9]{1,18}")) size = Long.parseLong(s);
        return VimResult.ok(new Transfer(url, size), 0);
    }

    /** The files in a directory (at most 100) as the guest lists them: name to size, -1 when the size is not given. */
    public VimResult<java.util.Map<String, Long>> listFiles(String vm, String directory) {
        VimResult<XmlNode> r = file("ListFilesInGuest", vm, "<filePath>" + VimXml.escape(directory)
                + "</filePath><index>0</index><maxResults>" + MAX_FILES + "</maxResults>");
        if (!r.isOk()) return VimResult.fault(r.faultClass(), clean(r), null, 0, r.cause());
        java.util.Map<String, Long> names = new java.util.LinkedHashMap<>();
        XmlNode rv = r.value().find("returnval");
        if (rv != null) {
            for (XmlNode f : rv.children()) {
                if (!"files".equals(f.name)) continue;
                String p = f.childText("path");
                String size = f.childText("size");
                if (p != null) names.put(p, size != null && size.matches("[0-9]{1,18}") ? Long.valueOf(size) : Long.valueOf(-1));
            }
        }
        return VimResult.ok(names, 0);
    }

    /** Remove a directory with everything in it. */
    public VimResult<Void> deleteDirectory(String vm, String directory) {
        VimResult<XmlNode> r = file("DeleteDirectoryInGuest", vm, "<directoryPath>" + VimXml.escape(directory)
                + "</directoryPath><recursive>true</recursive>");
        return r.isOk() ? VimResult.<Void>ok(null, 0) : VimResult.<Void>fault(r.faultClass(), clean(r), null, 0, r.cause());
    }

    // ------------------------------------------------------------------ processes

    /** Start a program; its process id. The arguments must carry no secret (they are listed in the guest). */
    public VimResult<Long> startProgram(String vm, String program, String arguments, String workingDirectory) {
        VimResult<Managers> m = managers();
        if (!m.isOk()) return VimResult.fault(m.faultClass(), m.message(), null, 0, m.cause());
        VimResult<XmlNode> r = withAccount("<StartProgramInGuest xmlns=\"urn:vim25\">" + head(m.value().processes, vm)
                + "<spec><programPath>" + VimXml.escape(program) + "</programPath><arguments>"
                + VimXml.escape(arguments) + "</arguments><workingDirectory>" + VimXml.escape(workingDirectory)
                + "</workingDirectory></spec></StartProgramInGuest>", "StartProgramInGuest");
        if (!r.isOk()) return VimResult.fault(r.faultClass(), clean(r), null, 0, r.cause());
        XmlNode rv = r.value().find("returnval");
        String t = rv == null ? null : rv.text();
        if (t == null || !t.matches("[0-9]{1,18}")) {
            return VimResult.fault("Parse", "StartProgramInGuest answered no process id", null, 0, null);
        }
        return VimResult.ok(Long.valueOf(t), 0);
    }

    /** What the guest says about one process. */
    public VimResult<ProcessState> process(String vm, long pid) {
        VimResult<Managers> m = managers();
        if (!m.isOk()) return VimResult.fault(m.faultClass(), m.message(), null, 0, m.cause());
        VimResult<XmlNode> r = withAccount("<ListProcessesInGuest xmlns=\"urn:vim25\">" + head(m.value().processes, vm)
                + "<pids>" + pid + "</pids></ListProcessesInGuest>", "ListProcessesInGuest");
        if (!r.isOk()) return VimResult.fault(r.faultClass(), clean(r), null, 0, r.cause());
        XmlNode rv = r.value().find("returnval");
        if (rv != null && String.valueOf(pid).equals(rv.childText("pid"))) {
            String code = rv.childText("exitCode");
            boolean ended = rv.child("endTime") != null || code != null;
            Integer c = code != null && code.matches("-?[0-9]{1,10}") ? parseInt(code) : null;
            return VimResult.ok(new ProcessState(false, ended, c), 0);
        }
        return VimResult.ok(new ProcessState(true, true, null), 0);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Stop a process the guest is still running. */
    public VimResult<Void> terminate(String vm, long pid) {
        VimResult<Managers> m = managers();
        if (!m.isOk()) return VimResult.fault(m.faultClass(), m.message(), null, 0, m.cause());
        VimResult<XmlNode> r = withAccount("<TerminateProcessInGuest xmlns=\"urn:vim25\">" + head(m.value().processes, vm)
                + "<pid>" + pid + "</pid></TerminateProcessInGuest>", "TerminateProcessInGuest");
        return r.isOk() ? VimResult.<Void>ok(null, 0) : VimResult.<Void>fault(r.faultClass(), clean(r), null, 0, r.cause());
    }

    // ------------------------------------------------------------------ plumbing

    private String head(MoRef manager, String vm) {
        return "<_this type=\"" + VimXml.escape(manager.type) + "\">" + VimXml.escape(manager.value)
                + "</_this><vm type=\"VirtualMachine\">" + VimXml.escape(vm) + "</vm>" + auth.element();
    }

    private VimResult<XmlNode> file(String operation, String vm, String args) {
        VimResult<Managers> m = managers();
        if (!m.isOk()) return VimResult.fault(m.faultClass(), m.message(), null, 0, m.cause());
        return withAccount("<" + operation + " xmlns=\"urn:vim25\">" + head(m.value().files, vm) + args + "</"
                + operation + ">", operation);
    }

    private VimResult<String> string(String operation, String vm, String args) {
        VimResult<XmlNode> r = file(operation, vm, args);
        if (!r.isOk()) return VimResult.fault(r.faultClass(), clean(r), null, 0, r.cause());
        XmlNode rv = r.value().find("returnval");
        if (rv == null || rv.text().isEmpty()) {
            return VimResult.fault("Parse", operation + " answered nothing", null, 0, null);
        }
        return VimResult.ok(rv.text(), 0);
    }

    /**
     * The fault's text with the account and password taken out: the whole text is scrubbed first and only then cut to
     * size, so a secret that straddles the cut cannot leave its first half in the message.
     */
    private String clean(VimResult<?> r) {
        if (r.detail() == null) {
            String m = auth.scrub(r.message());
            return m == null ? r.faultClass() : m;
        }
        String message = r.message() == null ? r.faultClass() : r.message();
        int colon = message.indexOf(": ");
        String prefix = colon > 0 ? message.substring(0, colon) : message; // the operation and the HTTP status
        String text = auth.scrub(r.detail());
        return prefix + ": " + (text.length() > 200 ? text.substring(0, 200) + "..." : text);
    }

    /**
     * What a failed call means: the Windows account was refused, guest operations are not available, the VM is in no
     * state for them, or it took too long. {@code otherwise} when it is none of those.
     */
    public static GuestStep classify(VimResult<?> r, GuestStep otherwise) {
        String f = r.faultClass();
        if (f == null) return otherwise;
        switch (f) {
            case "InvalidGuestLogin":
            case "GuestPermissionDenied":
            case "GuestAuthenticationChallenge":
                return GuestStep.AUTH;
            case "GuestOperationsUnavailable":
            case "GuestComponentsOutOfDate":
            case "OperationDisabledByGuest":
            case "Unavailable":
                return GuestStep.UNAVAILABLE;
            case "InvalidState":
            case "InvalidPowerState":
                return GuestStep.TOOLS;
            default:
                break;
        }
        if (r.cause() instanceof SocketTimeoutException) return GuestStep.TIMEOUT;
        return otherwise;
    }
}
