package com.vcfcf.adapters.vcommunity;

import com.vcfcf.adapters.vcommunity.list.CheckListStore;
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
        private boolean attention;

        /** True when something in the report needs the admin's attention. */
        public boolean needsAttention() {
            return attention;
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
        Report r = new Report();
        vim.beginCycle();
        VimResult<About> session = vim.ensureSession();
        if (!session.isOk()) {
            throw new IOException(session.message(), session.cause());
        }
        About about = session.value();
        r.lines.add("Logged in to " + (about == null ? "the vCenter" : about.describe()));
        if (about == null || about.instanceUuid == null || about.instanceUuid.trim().isEmpty()) {
            r.attention = true;
            r.lines.add("WARNING: the vCenter did not report an instance UUID, so its objects "
                    + "cannot be matched in VCF Operations");
        }

        ThumbprintProbe.Result pr = probe.probe(cfg.asciiHost(), cfg.port, 10_000);
        if (!pr.ok()) r.attention = true;
        r.lines.add("vCenter TLS Validation: " + cfg.tlsMode.label + "; "
                + (pr.ok() ? "the certificate's SHA-256 thumbprint is " + pr.thumbprint()
                        : "the certificate thumbprint could not be read (" + pr.failure() + ")"));

        boolean suite = false;
        if (ops == null) {
            r.attention = true;
            r.lines.add("WARNING: the VCF Operations Suite API is not available to this instance");
        } else {
            try {
                ops.get("/api/versions/current", null);
                suite = true;
                r.lines.add("The VCF Operations Suite API answered");
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                r.attention = true;
                r.lines.add("WARNING: the VCF Operations Suite API did not answer from where this "
                        + "test ran (" + e.getClass().getSimpleName() + "); collection pushes "
                        + "through it");
            }
        }

        if (suite) {
            Map<ListKind, CheckListStore.Entry> entries = store.refresh(cfg.filesInUse(),
                    path -> ops.get(path, "*/*"));
            for (CheckListStore.Entry e : entries.values()) {
                r.lines.add(e.summary());
                if (!e.ok() && e.outcome != CheckListStore.Outcome.NOT_CONFIGURED) r.attention = true;
            }
        } else {
            r.attention = true;
            r.lines.add("Check-list files were not read (the Suite API did not answer)");
        }
        return r;
    }
}
