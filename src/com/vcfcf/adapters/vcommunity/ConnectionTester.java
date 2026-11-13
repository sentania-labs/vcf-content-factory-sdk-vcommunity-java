package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.list.CheckListStore;
import com.vcfcf.adapters.vcommunity.list.ConfigFileFetch;
import com.vcfcf.adapters.vcommunity.list.ListKind;
import com.vcfcf.adapters.vcommunity.ops.OpsApi;
import com.vcfcf.adapters.vcommunity.tls.ThumbprintProbe;
import com.vcfcf.adapters.vcommunity.vim.About;
import com.vcfcf.adapters.vcommunity.vim.VimResult;
import com.vcfcf.adapters.vcommunity.vim.VimSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test Connection, SDK-free (design section 11, P1): log in to the vCenter,
 * read what it presents, ask whether the Suite API answers, and read each
 * check-list file in use, saying for each what came of it and how many items
 * it holds.
 *
 * <p>A login that fails fails the test (the exception carries the cause, so
 * the platform's certificate advice still applies). A Suite API that does not
 * answer, or a check-list file that is absent, unreadable or invalid, does
 * not: the test can run on another node than collection does, and the
 * original pack treats a bad file as a reported problem, never fatal. They
 * are in the report, which the caller logs where it can be found.
 */
public final class ConnectionTester {

    private ConnectionTester() {}

    /** What the test found, one line per item, in order. */
    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        private final List<String> attentionLines = new ArrayList<>();

        /** True when something in the report needs the admin's attention. */
        public boolean needsAttention() {
            return !attentionLines.isEmpty();
        }

        /** The lines of the report that need the admin's attention, each as it appears in it. */
        public List<String> attentionLines() {
            return attentionLines;
        }

        /** Add a line that needs attention. */
        void flag(String line) {
            lines.add(line);
            attentionLines.add(line);
        }

        /** The lines as one text. */
        public String text() {
            return String.join("; ", lines);
        }
    }

    /**
     * @param ops null when the Suite API client could not be built
     * @throws Exception the login failed (the message names the cause; the
     *         original failure is attached)
     */
    public static Report run(VCommunityConfig cfg, VimSession vim, ThumbprintProbe.Probe probe,
            OpsApi ops, CheckListStore store) throws Exception {
        return run(cfg, vim, probe, ops, store, null, true);
    }

    /** The text the framework's client builder starts with when no Suite API credential resolves. */
    static final String NO_CREDENTIAL = "SuiteApiStitchClient: cannot resolve Suite API credentials";

    /**
     * Whether a failure to build the Suite API client is the one expected during a test (the
     * account is not configured, so no credential resolves). Anything else is a real problem.
     */
    public static boolean isNoCredentialInTest(RuntimeException e) {
        return e instanceof IllegalStateException && e.getMessage() != null
                && e.getMessage().startsWith(NO_CREDENTIAL);
    }

    /**
     * @param notBuilt the class of the exception that stopped the Suite API client being built
     *        ({@code ops} is then null), or null
     * @param expected whether that failure is the known no-credential case of a test
     */
    public static Report run(VCommunityConfig cfg, VimSession vim, ThumbprintProbe.Probe probe,
            OpsApi ops, CheckListStore store, String notBuilt, boolean expected) throws Exception {
        Report r = new Report();
        vim.beginCycle();
        VimResult<About> session = vim.ensureSession();
        if (!session.isOk()) {
            throw new IOException(session.message(), session.cause());
        }
        About about = session.value();
        r.lines.add("Logged in to " + (about == null ? "the vCenter" : about.describe()));
        if (about == null || about.instanceUuid == null || about.instanceUuid.trim().isEmpty()) {
            r.flag("WARNING: the vCenter did not report an instance UUID, so its objects "
                    + "cannot be matched in VCF Operations");
        }

        ThumbprintProbe.Result pr = probe.probe(cfg.asciiHost(), cfg.port, 10_000);
        String tlsLine = "vCenter TLS Validation: " + cfg.tlsMode.label + "; "
                + (pr.ok() ? "the certificate's SHA-256 thumbprint is " + pr.thumbprint()
                        : "the certificate thumbprint could not be read (" + pr.failure() + ")");
        if (pr.ok()) r.lines.add(tlsLine);
        else r.flag(tlsLine);

        boolean suite = false;
        if (ops == null) {
            // Not a warning: during a test the account is not configured, so the Suite API client
            // cannot be built here (the credential it uses during collection is supplied by the
            // platform to a configured account; the files it falls back to are not readable from
            // every node). Collection reports its own Suite API state every cycle.
            if (expected || notBuilt == null) {
                r.lines.add("The VCF Operations Suite API is not checked during a test (its client "
                        + "uses a credential the platform gives a configured account"
                        + (notBuilt == null ? "" : "; " + notBuilt) + "); collection reports its "
                        + "state in the Collection Health keys");
            } else {
                r.flag("WARNING: the VCF Operations Suite API client could not be built during "
                        + "this test (" + notBuilt + "), and that is not the usual 'no configured "
                        + "account yet' case; collection pushes through it");
            }
        } else {
            try {
                ops.get("/api/versions/current", null);
                suite = true;
                r.lines.add("The VCF Operations Suite API answered");
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                r.flag("WARNING: the VCF Operations Suite API did not answer from where this "
                        + "test ran (" + e.getClass().getSimpleName() + "); collection pushes "
                        + "through it");
            }
        }

        if (suite) {
            Map<ListKind, CheckListStore.Entry> entries = store.refresh(cfg.filesInUse(),
                    path -> ConfigFileFetch.fetch(ops, path));
            for (CheckListStore.Entry e : entries.values()) {
                r.lines.add(e.summary());
                if (!e.ok() && e.outcome != CheckListStore.Outcome.NOT_CONFIGURED) {
                    r.attentionLines.add(e.summary());
                }
            }
        } else if (ops == null) {
            String why = "Check-list files were not read (they come through the Suite API, which "
                    + "is not checked during a test)";
            if (expected || notBuilt == null) r.lines.add(why);
            else r.flag(why);
        } else {
            r.flag("Check-list files were not read (the Suite API did not answer)");
        }
        return r;
    }
}
