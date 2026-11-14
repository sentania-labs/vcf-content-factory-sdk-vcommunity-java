package com.vcfcf.adapters.vcommunity.guest;

import com.vcfcf.adapters.vcommunity.Log;
import com.vcfcf.adapters.vcommunity.ops.OpsEvent;
import com.vcfcf.adapters.vcommunity.vim.VimResult;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * What one VM's guest gives, step by step (design 8.2). For each program: a directory the guest names, the
 * input as a file, the program, a wait for it, the output as a file, then the parse. The directory is removed
 * on every path out (success, a failed step, a timeout, an interrupt), and a failure of any step is recorded
 * with its own cause and ends only that program.
 *
 * <p>Nothing here holds a credential outside {@link GuestOpsClient}; every message that leaves is already
 * scrubbed by it, and a message built here names a step, a status code or a count and never a URL or a file
 * body.
 */
public final class GuestRunner {

    /** Pauses between looks at the process (the original's): 1, 2, 4, 8, then 10 seconds. */
    static long delayMs(int look) {
        switch (look) {
            case 0: return 1_000;
            case 1: return 2_000;
            case 2: return 4_000;
            case 3: return 8_000;
            default: return 10_000;
        }
    }

    /** Sleeps; a test replaces it with a clock that moves instead of waiting. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /** The limits of one run. */
    public static final class Limits {
        /** How long one program may run (the original's 300 s). */
        public long scriptMs = 300_000;
        /** The most output read back. */
        public long maxOutput = 1024 * 1024;
        /** Failed looks at the process in a row that end the wait. */
        public int pollFailures = 3;
    }

    /** One VM to ask, with what the programs need. */
    public static final class Job {
        public String vm;
        public String resourceId;
        public String hostMoref;
        public String hostName;
        public GuestPlan plan;
        /** How far back the events program looks, in minutes. */
        public int eventWindowMinutes = GuestScripts.DEFAULT_WINDOW_MINUTES;
        /** The inventory name of a host from its MoRef value (used when the VM has moved), or null. */
        public java.util.function.Function<String, String> hostNameOf;
        /** Directories an earlier run could not remove from this guest (a guest that was unreachable then). */
        public List<String> leftover = new ArrayList<>();
    }

    /** What came back from one VM, and what went wrong. */
    public static final class Outcome {
        /** The six operating system values by their key's last segment; null when the script gave none. */
        public Map<String, String> operatingSystem;
        /** The services the script found; empty when it found none or did not run. */
        public final List<GuestRows.Service> services = new ArrayList<>();
        public final List<OpsEvent> events = new ArrayList<>();
        public int eventsOver;
        public final List<GuestFailure> failures = new ArrayList<>();
        /** The scripts that ended with their output parsed. */
        public int scriptsOk;
        public int scriptsTried;
        /** Directories that could not be removed from the guest, to be tried again at the next run on this VM. */
        public final List<String> leftBehind = new ArrayList<>();
        /** True once a call with the account was accepted by the guest's logon (the account is good on this VM). */
        public boolean loggedOn;
        /** True when the events program ended with its output read (so the window it covered is known to be done). */
        public boolean eventsOk;
        /** Set by a transfer that was refused for a certificate other than the recorded one (for the moved-VM retry). */
        boolean mismatch;
        /** The first failure that makes every other script of this VM pointless (account, tools, certificate). */
        public GuestStep fatal;

        void fail(GuestStep step, String script, String message) {
            failures.add(new GuestFailure(step, script, message));
            if (step == GuestStep.AUTH || step == GuestStep.UNAVAILABLE || step == GuestStep.TOOLS
                    || step == GuestStep.TLS) {
                if (fatal == null) fatal = step;
            }
        }
    }

    private static final Pattern DIRECTORY = Pattern.compile(
            "C:\\\\Windows\\\\Temp\\\\VCFOperationsvCommunity-[A-Za-z0-9._-]{1,64}-(OSInfo|Services|EventLog)-TEMP",
            Pattern.CASE_INSENSITIVE);

    private final GuestOpsClient ops;
    private final GuestHttp http;
    private final GuestTrust trust;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Log log;
    private final Limits limits;

    public GuestRunner(GuestOpsClient ops, GuestHttp http, GuestTrust trust, LongSupplier clock, Sleeper sleeper,
            Log log, Limits limits) {
        this.ops = ops;
        this.http = http;
        this.trust = trust;
        this.clock = clock;
        this.sleeper = sleeper;
        this.log = log == null ? Log.NONE : log;
        this.limits = limits;
    }

    /**
     * Run the programs of the plan on one VM, in the original's order: services, operating system, events.
     *
     * @param stopMs when this VM's time is up (epoch ms)
     * @throws InterruptedException the thread was interrupted; the directory in the guest is gone by then
     */
    public Outcome run(Job job, long stopMs) throws InterruptedException {
        Outcome o = new Outcome();
        GuestPlan plan = job.plan;
        ops.limitTo(stopMs);
        try {
            removeLeftovers(job, o);
            if (plan.services && o.fatal == null) script(GuestScripts.Kind.SERVICES, job, plan.serviceFile(), o, stopMs);
            if (plan.operatingSystem && o.fatal == null) script(GuestScripts.Kind.OS, job, null, o, stopMs);
            if (plan.events && o.fatal == null) script(GuestScripts.Kind.EVENTS, job, plan.eventFile(), o, stopMs);
        } finally {
            ops.limitTo(0);
        }
        return o;
    }

    /** Try again to remove what an earlier run left in this guest. */
    private void removeLeftovers(Job job, Outcome o) throws InterruptedException {
        boolean stopped = false;
        for (String d : job.leftover) {
            if (!DIRECTORY.matcher(d).matches()) continue; // never delete a path of any other form
            if (stopped) {
                o.leftBehind.add(d); // not tried this time (the guest refused us); still there
                continue;
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            VimResult<Void> r = ops.deleteDirectory(job.vm, d);
            if (r.isOk() || "FileNotFound".equals(r.faultClass())) {
                o.loggedOn = true;
                continue;
            }
            o.leftBehind.add(d);
            GuestStep why = GuestOpsClient.classify(r, GuestStep.CLEANUP);
            o.fail(why, "cleanup", "a directory an earlier run left in the guest "
                    + "could not be removed: " + r.message());
            if (o.fatal != null) stopped = true;
        }
    }

    private static String name(GuestScripts.Kind k) {
        return k.name().toLowerCase(java.util.Locale.ROOT);
    }

    private void script(GuestScripts.Kind kind, Job job, byte[] input, Outcome o, long stopMs)
            throws InterruptedException {
        o.scriptsTried++;
        String dir = null;
        Long pid = null;
        boolean finished = false;
        String which = name(kind);
        try {
            if (!time(stopMs, o, which)) return;
            VimResult<String> d = ops.createTemporaryDirectory(job.vm, GuestScripts.DIRECTORY_PREFIX,
                    kind.directorySuffix(), GuestScripts.TEMP_PARENT);
            if (!d.isOk()) {
                o.fail(GuestOpsClient.classify(d, GuestStep.DIRECTORY), which, "the temporary directory could not be "
                        + "made: " + d.message());
                return;
            }
            o.loggedOn = true;
            if (!DIRECTORY.matcher(d.value()).matches()) {
                o.fail(GuestStep.DIRECTORY, which, "the guest named a temporary directory this port does not use; "
                        + "nothing was run and the directory was left alone");
                return;
            }
            dir = d.value();
            // every file is one the guest names inside the directory it named: nothing has a name chosen here
            String in = null;
            if (input != null) {
                in = tempFile(job, dir, GuestScripts.INPUT_PREFIX, GuestScripts.INPUT_SUFFIX, o, which, stopMs);
                if (in == null || !upload(job, in, input, o, which, stopMs)) return;
            }
            String out = tempFile(job, dir, GuestScripts.OUTPUT_PREFIX, GuestScripts.OUTPUT_SUFFIX, o, which, stopMs);
            if (out == null) return;

            if (!time(stopMs, o, which)) return;
            VimResult<Long> p = ops.startProgram(job.vm, GuestScripts.POWERSHELL,
                    GuestScripts.arguments(GuestScripts.program(kind, in, out, job.eventWindowMinutes)), GuestScripts.TEMP_PARENT);
            if (!p.isOk()) {
                o.fail(GuestOpsClient.classify(p, GuestStep.START), which, "the program could not be started: "
                        + p.message());
                return;
            }
            pid = p.value();
            boolean ended = await(job, pid, o, which, stopMs);
            finished = true; // await returned: it stopped the program itself wherever it had to
            if (!ended) return;

            byte[] bytes = download(job, out, o, which, stopMs);
            if (bytes == null) return;
            parse(kind, job, bytes, o);
        } finally {
            cleanup(job, dir, pid, finished, which, o);
        }
    }

    // ------------------------------------------------------------------ steps

    /** False (with the failure recorded) when this VM's time is up. */
    private boolean time(long stopMs, Outcome o, String which) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (clock.getAsLong() >= stopMs) {
            o.fail(GuestStep.TIMEOUT, which, "the time for this VM ran out before the step");
            return false;
        }
        return true;
    }

    /** A file the guest makes in the directory; null (with the failure recorded) when it did not, or named it unexpectedly. */
    private String tempFile(Job job, String dir, String prefix, String suffix, Outcome o, String which, long stopMs)
            throws InterruptedException {
        if (!time(stopMs, o, which)) return null;
        VimResult<String> f = ops.createTemporaryFile(job.vm, prefix, suffix, dir);
        if (!f.isOk()) {
            o.fail(GuestOpsClient.classify(f, GuestStep.DIRECTORY), which, "a file could not be made in the temporary "
                    + "directory: " + f.message());
            return null;
        }
        String p = f.value();
        String leaf = p.regionMatches(true, 0, dir + "\\", 0, dir.length() + 1) ? p.substring(dir.length() + 1) : null;
        if (leaf == null || !leaf.matches("[A-Za-z0-9._-]{1,64}")) {
            o.fail(GuestStep.DIRECTORY, which, "the guest named a file this port does not use; nothing was sent to it");
            return null;
        }
        return p;
    }

    private boolean upload(Job job, String path, byte[] bytes, Outcome o, String which, long stopMs)
            throws InterruptedException {
        if (!time(stopMs, o, which)) return false;
        GuestHttp.Response r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            VimResult<String> u = ops.initiateFileTransferToGuest(job.vm, path, bytes.length);
            if (!u.isOk()) {
                o.fail(GuestOpsClient.classify(u, GuestStep.UPLOAD), which, "the file could not be sent to the guest: "
                        + u.message());
                return false;
            }
            r = transfer(job, u.value(), bytes, 0, GuestStep.UPLOAD, o, which, stopMs);
            if (r == null && retryOnMovedVm(job, o, attempt)) continue;
            break;
        }
        if (r == null) return false;
        if (r.status != 200 && r.status != 201) {
            o.fail(GuestStep.UPLOAD, which, "the ESXi host answered HTTP " + r.status + " to the file transfer");
            return false;
        }
        if (!time(stopMs, o, which)) return false;
        VimResult<Map<String, Long>> l = ops.listFiles(job.vm, path.substring(0, path.lastIndexOf('\\')));
        if (!l.isOk()) {
            o.fail(GuestOpsClient.classify(l, GuestStep.UPLOAD), which, "the sent file could not be looked for: "
                    + l.message());
            return false;
        }
        String leaf = path.substring(path.lastIndexOf('\\') + 1);
        Long size = null;
        for (Map.Entry<String, Long> n : l.value().entrySet()) {
            String x = n.getKey().substring(Math.max(n.getKey().lastIndexOf('\\'), n.getKey().lastIndexOf('/')) + 1);
            if (x.equalsIgnoreCase(leaf)) size = n.getValue();
        }
        if (size == null) {
            o.fail(GuestStep.UPLOAD, which, "the file sent to the guest is not there");
            return false;
        }
        if (size >= 0 && size != bytes.length) {
            o.fail(GuestStep.UPLOAD, which, "the file in the guest is " + size + " bytes, not the " + bytes.length
                    + " that were sent");
            return false;
        }
        return true;
    }

    /**
     * After a refusal for a host certificate other than the recorded one: when the VM has moved to another host since the
     * cycle read it (a vMotion), it is asked about again on the new host, once. The refusal is then not recorded.
     */
    private boolean retryOnMovedVm(Job job, Outcome o, int attempt) {
        if (attempt > 0 || !o.mismatch) return false;
        o.mismatch = false;
        String now = ops.currentHost(job.vm);
        if (now == null || now.equals(job.hostMoref)) return false;
        job.hostMoref = now;
        job.hostName = job.hostNameOf == null ? null : job.hostNameOf.apply(now);
        o.failures.remove(o.failures.size() - 1);
        if (o.fatal == GuestStep.TLS) o.fatal = null;
        return true;
    }

    /** One HTTPS transfer: null (with the failure recorded) when it was refused or failed. */
    private GuestHttp.Response transfer(Job job, String url, byte[] put, long maxGet, GuestStep dflt, Outcome o,
            String which, long stopMs) {
        String checked;
        try {
            checked = TransferUrl.check(url, job.hostName);
        } catch (IllegalArgumentException e) {
            o.fail(dflt, which, e.getMessage());
            return null;
        }
        GuestTrust.Channel ch = trust.forHost(job.hostMoref);
        if (ch.socketFactory() == null) {
            o.fail(GuestStep.TLS, which, ch.refusal());
            return null;
        }
        // the transfer is given no more time than this VM has left
        int left = (int) Math.max(1_000, Math.min(Integer.MAX_VALUE, stopMs - clock.getAsLong()));
        try {
            return put != null ? http.put(checked, put, ch, left) : http.get(checked, ch, maxGet, left);
        } catch (GuestTlsException e) {
            o.mismatch = e.isMismatch();
            o.fail(GuestStep.TLS, which, e.getMessage());
        } catch (SocketTimeoutException e) {
            o.fail(GuestStep.TIMEOUT, which, "the ESXi host did not answer the file transfer in time");
        } catch (IOException e) {
            o.fail(dflt, which, e.getMessage() == null ? "the file transfer failed" : e.getMessage());
        }
        return null;
    }

    /** Wait for the program: true when it ended in a way that leaves output to read. */
    private boolean await(Job job, long pid, Outcome o, String which, long stopMs) throws InterruptedException {
        long limit = Math.min(clock.getAsLong() + limits.scriptMs, stopMs);
        int bad = 0;
        for (int look = 0;; look++) {
            sleeper.sleep(delayMs(look));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            VimResult<GuestOpsClient.ProcessState> st = ops.process(job.vm, pid);
            if (!st.isOk()) {
                GuestStep why = GuestOpsClient.classify(st, GuestStep.CALL);
                if (why != GuestStep.CALL && why != GuestStep.TIMEOUT || ++bad >= limits.pollFailures) {
                    o.fail(why, which, "the program could not be followed: " + st.message());
                    stop(job, pid);
                    return false;
                }
            } else {
                bad = 0;
                GuestOpsClient.ProcessState s = st.value();
                if (s.gone || s.exited) {
                    if (s.exitCode != null && s.exitCode != 0) {
                        o.fail(GuestStep.EXIT, which, "the program in the guest ended with code " + s.exitCode);
                        return false;
                    }
                    return true;
                }
            }
            if (clock.getAsLong() >= limit) {
                o.fail(GuestStep.TIMEOUT, which, "the program in the guest was still running when its time was up; "
                        + "it was stopped");
                stop(job, pid);
                return false;
            }
        }
    }

    private void stop(Job job, long pid) {
        VimResult<Void> t = ops.terminate(job.vm, pid);
        if (!t.isOk()) log.info("A program in a guest could not be stopped: " + t.message());
    }

    private byte[] download(Job job, String path, Outcome o, String which, long stopMs) throws InterruptedException {
        if (!time(stopMs, o, which)) return null;
        GuestHttp.Response r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            VimResult<GuestOpsClient.Transfer> t = ops.initiateFileTransferFromGuest(job.vm, path);
            if (!t.isOk()) {
                o.fail(GuestOpsClient.classify(t, GuestStep.DOWNLOAD), which, "the output could not be read back: "
                        + t.message());
                return null;
            }
            if (t.value().size > limits.maxOutput) {
                o.fail(GuestStep.TOOLARGE, which, "the output is " + t.value().size + " bytes, over the limit of "
                        + limits.maxOutput);
                return null;
            }
            r = transfer(job, t.value().url, null, limits.maxOutput, GuestStep.DOWNLOAD, o, which, stopMs);
            if (r == null && retryOnMovedVm(job, o, attempt)) continue;
            break;
        }
        if (r == null) return null;
        if (r.tooLarge) {
            o.fail(GuestStep.TOOLARGE, which, "the output is over the limit of " + limits.maxOutput + " bytes");
            return null;
        }
        if (r.status != 200) {
            o.fail(GuestStep.DOWNLOAD, which, "the ESXi host answered HTTP " + r.status + " to the output transfer");
            return null;
        }
        return r.body;
    }

    private void parse(GuestScripts.Kind kind, Job job, byte[] bytes, Outcome o) {
        String which = name(kind);
        GuestCsv.Table t;
        try {
            t = GuestCsv.parse(bytes);
        } catch (GuestCsv.CsvException e) {
            o.fail(GuestStep.PARSE, which, "the output could not be read: " + e.getMessage());
            return;
        }
        if (t == null) {
            // the output is made before the program runs; a program that wrote nothing is not "found none"
            o.fail(GuestStep.PARSE, which, "the program wrote no output (not even the header)");
            return;
        }
        try {
            switch (kind) {
                case OS: {
                    GuestRows.Parsed<Map<String, String>> p = GuestRows.operatingSystem(t);
                    if (p.rows.isEmpty()) {
                        o.fail(GuestStep.PARSE, which, "the output has no operating system row");
                        return;
                    }
                    o.operatingSystem = new LinkedHashMap<>(p.rows.get(p.rows.size() - 1));
                    skipped(o, which, p.skipped);
                    break;
                }
                case SERVICES: {
                    java.util.Set<String> asked = new java.util.HashSet<>();
                    for (String n : job.plan.serviceNames) asked.add(n.toLowerCase(java.util.Locale.ROOT));
                    GuestRows.Parsed<GuestRows.Service> p = GuestRows.services(t, asked);
                    o.services.addAll(p.rows);
                    skipped(o, which, p.skipped);
                    break;
                }
                default: {
                    GuestRows.Parsed<GuestRows.EventRow> p = GuestRows.events(t);
                    long now = clock.getAsLong();
                    int n = 0;
                    // each query gives at most one record, so no more events than queries can be true
                    int most = Math.min(WindowsEvents.MAX_PER_VM, job.plan.queries.size());
                    for (GuestRows.EventRow r : p.rows) {
                        if (n++ >= most) {
                            o.eventsOver++;
                            continue;
                        }
                        o.events.add(WindowsEvents.of(job.resourceId, r, now));
                    }
                    skipped(o, which, p.skipped);
                    queryFailures(job, o, p.failedQueries);
                    o.eventsOk = true;
                    break;
                }
            }
        } catch (GuestRows.HeaderException e) {
            o.fail(GuestStep.HEADER, which, e.getMessage());
            return;
        }
        o.scriptsOk++;
    }

    /** The event queries the guest could not run, named by the log of each entry of our own list. */
    private static void queryFailures(Job job, Outcome o, List<Integer> positions) {
        if (positions.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        int shown = 0;
        for (Integer pos : positions) {
            if (pos < 1 || pos > job.plan.queries.size()) continue;
            if (shown++ < 5) {
                if (names.length() > 0) names.append(", ");
                names.append(job.plan.queries.get(pos - 1).log);
            }
        }
        o.fail(GuestStep.QUERY, "events", positions.size() + " event query(ies) could not be run in the guest (a log the "
                + "account may not read, or one that is not there): " + names + (shown > 5 ? ", ..." : "")
                + ". The events that were found are sent.");
    }

    private static void skipped(Outcome o, String which, int n) {
        if (n > 0) o.fail(GuestStep.ROW, which, n + " row(s) of the output were left out (too short, or a value "
                + "that cannot be a property)");
    }

    /**
     * Remove what the program left: stop it if it is still running, then delete the directory. Runs on every
     * path out of {@link #script}, with the thread's interrupt put aside for its own calls and given back after.
     */
    private void cleanup(Job job, String dir, Long pid, boolean finished, String which, Outcome o) {
        boolean interrupted = Thread.interrupted();
        try {
            if (pid != null && !finished) stop(job, pid);
            if (dir == null) return;
            VimResult<Void> d = ops.deleteDirectory(job.vm, dir);
            if (!d.isOk()) {
                // a process the guest was slow to end may still hold the directory: one more try
                try {
                    sleeper.sleep(1_000);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                d = ops.deleteDirectory(job.vm, dir);
            }
            if (!d.isOk() && !"FileNotFound".equals(d.faultClass())) {
                o.leftBehind.add(dir);
                o.failures.add(new GuestFailure(GuestStep.CLEANUP, which, "the temporary directory could not be "
                        + "removed from the guest: " + d.message()));
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
