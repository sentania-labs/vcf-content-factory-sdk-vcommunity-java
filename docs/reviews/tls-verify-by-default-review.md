# Review: vCenter certificate verified by default (`fix/tls-verify-by-default`)

Reviewed: `af69cac` against `release/3.0.0.1` (`c8ddf6f`), static only (no install, no live instance).
Reviewer: `sdk-adapter-reviewer`. One round: every finding below gets fixed with no second internal pass.

**Verdict: CHANGES REQUESTED.** 2 BLOCKING / 9 WARNING / 10 NIT.

The code change holds up. In the default setting, no credential can reach the vCenter before the chain and the name
are verified. The trust-all context exists only behind `allowInsecure` and in the probe. The probe sends nothing and
cannot be reused. The guest phase refuses before the Windows account is touched. Nothing reads the old two fields.
Both BLOCKING findings are docs that contradict what the code does. The WARNINGs are about failure messages, test
depth, upgrade and API guidance, and unproven "tested" claims.

## Independent verification

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` (worktree) | exit 0. All suites pass, including `BcHandshakeTest` (BCJSSE 1.82: untrusted chain, insecure, name mismatch). `WireBodyTest` skipped (no `VCFCF_WIRE_CP`), as in CI |
| `validate-sdk` (worktree) | OK, 130 sources, 9.0 compat compile OK. 8.18.7 and 9.1.1 compat SKIPPED (jars not on this workstation; environment, not this branch) |
| `build-sdk` head and base (scratch output dirs) | both build `0.0.0.1`. The build rewrote the version stamp in `docs/README.md` / `docs/inventory-tree.md` (0.0.0.26 to 0.0.0.1, already stale on the base). I reverted that, so the worktree is unchanged apart from this report |
| `pak-compare` head vs base | PASS, 0 BLOCKING / 1 WARNING (identifier count 18 vs 19, expected) |
| Built describe.xml / resources | only the two identifiers removed, `allowInsecure` added (`identType="2"`, default `false`, nameKey 14), resources 14/15 replaced, 8 reworded. No alert, symptom or `SymptomSets` change (dimension 13 not triggered) |
| Registry (`knowledge/context/defects.md`) | no open defect names `vcommunity-java`. `defect-gate --pak vcommunity-java`: "no open blocking defects" |

## 1. Credential exposure (default setting)

Every connection the adapter opens was traced:

- **SOAP** (`VimSoapClient.open`, :457-486): every connection, whether login, keepalive, relogin (`reloginIfNeeded`
  :270), stream retry or logout (:408), goes through `tls.secure(conn)`. `setInstanceFollowRedirects(false)`. The cookie is
  only set on authenticated calls. `RetrieveServiceContent` (unauthenticated) goes first, so a bad certificate fails
  before `Login` builds the password body. A TLS failure does not set `loginFailure`, so it retries each cycle,
  still sending nothing.
- **REST** (`RestClient.send`, :134-169): the same `VcenterTls`, no redirects, and the Basic header only on `POST
  /session`. `LibraryReader` builds its `RestClient` from the account's one `VcenterTls` (`VCommunityAdapter.java:168-171`).
  No other HTTP client exists: `openConnection`, `SSLContext.getInstance`, `createSocket` and `setSSLSocketFactory`
  appear only in VimSoapClient, RestClient, ThumbprintProbe, VcenterTls, GuestTrust and HttpsGuestHttp (ESXi, out
  of scope).
- **Verified posture**: `getPlatformSslContext(cfg.dialledHost())` (framework `NameCheckingTrustManager`: platform
  chain first, then the name, during the handshake) plus `CertificateNames::secure` (always-false verifier, so the
  JDK's post-handshake check is final). `create(false, …)` throws when either is missing, and configure turns that
  into `configError`: fail closed. The keep-alive cache is keyed by socket factory, and the probe and insecure
  factories are separate objects. No session can be resumed across contexts.
- **Probe** (`ThumbprintProbe.probe`): a fresh `SSLContext` per call, a raw socket, `startHandshake`, read the peer
  certificate, then try-with-resources close. Nothing is written. Neither the context nor the socket outlives the
  call. It is not reachable as a data connection.
- **Guest**: `GuestPlan.of` returns `VCENTER_TLS` on `cfg.allowInsecure` *before* the credential check (:88-92).
  `CycleEngine.guest` returns on `!plan.runs()` before `GuestAuth` is built (:567-577), and guest calls use the
  same `vim.reader()`.
- **Remaining trust-all**: only `VcenterTls.create(true, …)`, the probe, and `GuestTrust` Disabled (ESXi). One latent
  fail-open path is W3.

## 2. Parsing of `allowInsecure`

`"true".equalsIgnoreCase(raw)`, untrimmed, in both `parse` and `certificateCheckUrl` (one function), so there is
no split brain. null, absent, blank, `false`, `yes`, `1` and ` true` all verify, and only `true` in any case turns
checks off. A stray space can only make an account more secure, never less. Identical to unifi
(`UniFiConfig.java:30`) and synology (`SynologyConfig.java:55`). The framework's `parseAllowInsecure` trims, and its
javadoc names the vcommunity paks as keeping the untrimmed legacy parse. This adapter never calls the framework
parser, and the framework uses `allowInsecure` only through the describe (`allowInsecureDescribedSafe`). Either
choice is safe. Matching the siblings is right. `ConfigTest.allowInsecure` covers every case, including old values
being ignored.

## 3. Wiring compared with unifi and synology

| | unifi / synology | vcommunity-java |
|---|---|---|
| identifier | `allowInsecure`, string, `default="false"`, **no identType** (identifying, main form) | same key, label, type and default, `identType="2"` (Advanced, not identity). Correct here: adding an identifying field would change existing accounts' identity |
| trust | `HttpClientBuilder.platformSsl(this)`: chain only, no name check | `getPlatformSslContext(host)` + `CertificateNames.secure`: chain and name (the compliance idiom). Stricter: an IP-entered account fails here, and passes in unifi |
| `certificateCheckUrls` | from the unsaved config, empty on insecure, blank host or bad port | same contract (`VCommunityConfig.certificateCheckUrl`), same host and port as the dialled `hostPort` (IDN, IPv6 brackets) |
| `certificateFailureMessage` | not overridden | overridden (W1) |

For the dialog to appear, the framework needs `certificateCheckUrls` to return the endpoint, and nothing else
(`getConnectionURLs`, buildkit at least 1.0.11; the pinned kit is 1.0.29). This adapter provides it, and on a new
account too, because a missing identifier reads as `false`. Whether the platform then shows it, and loads the accepted
leaf into `getAdapterTrustManager()` for the same unsaved test, is live-only (list below).

## 4. FIPS

`TrustAllManager` (probe, insecure) is the path the old Trust on First Use default ran on 9.1.1. `PinnedTrustManager`
is now used only by `GuestTrust`, with SHA-1 gated by `GuestTrust::sha1Available`. `Thumbprints` uses SHA-256. Nothing new
calls an API the FIPS provider rejects. The verified path on BCJSSE has not been proven live for this pak (the base's
Q6 said the dialog was "not proven on all three lines").

## 5. Upgrade

The identType reasoning holds: both removed identifiers and the added one are `identType="2"`, so the identity tuple
(host and four file names) is unchanged. That is a lower risk than compliance build 92, which removed an *identifying*
field. The stored old values are not dropped at upgrade, though (W5). Fail closed holds: the handshake fails in the
trust manager before any HTTP byte. The cause is `F-LOGIN` with the sentence on the adapter instance
(`Collection|last_error`, failure causes), the account goes ERROR, and a WARN line is written each cycle
(`Failures.logSummary`). The `vCommunity|Collection Health|…` keys on the vCenter object are not refreshed on a
login failure (no VCID; existing behaviour), so they show the last good cycle.

## Findings

### BLOCKING

**B1** [`docs/installing.md:172-174`, `docs/design/vcommunity-java-port-design.md:233-235`; dimension 11, contradiction]
"With verification on, a certificate the collector does not trust makes the connection fail, and the probe still
records what it saw" / "and still when a verified connection fails on an unaccepted certificate". False. The probe is
step A2, after a successful login (`CycleEngine.java:219-239`). A failed login returns at :222 with
`presented = null`, and with no VCID there is nowhere to push. In the default setting a renewed, untrusted vCenter
certificate shows as a login failure, never as `Changed`.
Fix: delete both clauses. State that the Certificate keys are published on every cycle that connects, so with
verification on `Changed` only appears for a new certificate the collector already trusts. Qualify "published in both
settings" (`CHANGELOG.md:57`, `docs/overview.md:216`) the same way.

**B2** [`docs/installing.md:153`, also :517-520; dimension 11, contradiction] "the same sentence in Validate Connection
and in the cycle's failure". False. For a trust or name failure, the framework's `onTest` (`VcfCfAdapter.java:904-916`)
replaces the tester's message with its own text (`certificateTrustFailureMessage` / `certificateNameFailureMessage`),
then appends this pak's `certificateFailureMessage`. The table's sentences are what the cycle reports (account status,
`Collection|last_error`, log). Fix: say so, and describe the Validate Connection text as VCF Operations' certificate
message followed by the pak's advice.

### WARNING

**W1** [`VCommunityAdapter.java:252-266`; skill, Validate Connection hook; installing.md table row 3]
`certificateFailureMessage` appends "Review the vCenter certificate here and accept it" to every non-name failure. That
includes an expired, not-yet-valid or revoked certificate, which the framework classes as a trust failure (it is a
`CertPathValidatorException`) and which the docs say to renew. It also includes the framework's "no usable host"
refusal, which the default text already explains. In the name case, the appended text repeats the framework's.
Fix: move the logic into an SDK-free static (for example in `TlsFailure`). Return `defaultMessage` unchanged for
`isNoUsableHost` and for a name mismatch. Return the expired sentence for kind 3. Append the accept advice only for an
untrusted chain. Unit-test it.

**W2** [`TlsFailure.java:149-157`; evidence `knowledge/context/reviews/vsanperf-t5-devel-8.18-build-7.md:14`] On 8.18.7
the platform trust manager with no accepted certificate fails with "SSLHandshakeException: … the trustAnchors parameter
must be non-empty" (`InvalidAlgorithmParameterException`). Kind 1 does not match it, so on 8.18 an upgraded or new
account fails with the generic "the TLS connection to the vCenter failed (…)" and no instruction to accept the
certificate. Fix: match `InvalidAlgorithmParameterException` or that text as untrusted, and add a test. The
framework's `findCertificateTrustFailure` has the same gap: file a `tooling` follow-up.

**W3** [`tls/VcenterTls.java:61-67`, `:87-94`; dimension 1 by construction] `forTesting(boolean, factory)` is public and
unused (no caller in `src/` or `tests/`). It builds a posture that reports `insecure()==false` with no name check, and
`secure()` then falls back to `ACCEPT_ANY_NAME` whenever `securer == null`. Nothing reaches it today, but it is a
verified-labelled, fail-open constructor. Fix: delete `forTesting`, and in `secure()` throw `IllegalStateException`
when `!insecure && securer == null`.

**W4** [`tests/.../TlsAndSessionTest.java:160-180`, `TlsFailureTest.java:137-146`; doctrine, "tests prove failing
closed"] Untrusted fails closed for real (JDK and BCJSSE, `requests.size()` unchanged). The name check runs through a
stand-in (JSSE endpoint identification), not the framework's `NameCheckingTrustManager`, so the production failure text
("No subject alternative name matching <h> in the target certificate", `MISMATCH_HOST[0]`) is never exercised under
either provider. Expired is a fixture exception only. `buildTls`, `certificateCheckUrls` and `certificateFailureMessage`
(the overrides) are not tested. Fix: add a fixture with the framework's exact text wrapped as BCJSSE does
(`TlsFatalAlert(certificate_unknown)` over a `CertificateException`), or compile `CertificateNames` from the kit when
`VCFCF_WIRE_CP` is set, as `WireBodyTest` does. Add a real expired handshake (`keytool -startdate -2d -validity 1`)
that asserts the expired sentence and zero requests. Test the W1 static.

**W5** [`docs/installing.md:373-374`; `knowledge/context/approvals/2026-10-13-compliance-v1.0.0.93-prod-install.md:39`,
`knowledge/context/tier2_architecture.md:1346`] "A removed identifier's stored value is dropped by the platform on
upgrade (seen on another pak's upgrade)". Repo evidence says the value stays stored until the account is saved ("after
each save those identifiers were gone"). Fix: "it stays stored until the account is next saved, and this build never
reads it".

**W6** [`docs/installing.md:365-391`, upgrade section] It does not cover development accounts entered by IP. They now
fail the name check, accepting does not help, and the host is both the account identity and the collector object's
identifier (`VCommunityAdapter.java:459`), so changing it can recreate both. Fix: add a bullet that names the
outcome (enter the name on the certificate, which may recreate the account and its own objects, or use Allow Insecure
SSL for a lab).

**W7** [`docs/installing.md:578-589`, :505-513, :18-60] The API section does not say that an API-created account now
fails every cycle until its certificate is accepted (`POST /api/adapters/testConnection` then `PATCH /api/adapters`,
per `vsanperf-t5-devel-9.1-build-7.md:16`). Step-by-step step 4 omits the accept dialog. "Moving from the Python pack"
does not say the TLS setting does not carry over. Fix: one bullet or clause in each.

**W8** [`CHANGELOG.md:10,16`; `README.md:12,17`; `docs/overview.md:12`; `docs/installing.md:14`; brief item 7]
"Tested on 8.18.7, 9.0.2 and 9.1.1" now covers a default TLS path that has run on none of them, and `CHANGELOG.md:16`
still says the account fields "are the same". Fix: add "not yet verified live" to the DV-114 CHANGELOG entry and the
installing TLS section until the devel proof below is done, and correct line 16.

**W9** [`adapter.yaml:34`; author hard rule 9, `adapter.yaml:6` comment] `build_number` is not bumped and the
CHANGELOG has no build line. A devel install of this branch over the `0.0.0.1` the live tests installed has the same
version. Fix: bump to 2 with its CHANGELOG line, or record the decision to fold this into an untagged 3.0.0.1 and how
devel gets the new jar.

### NIT

- **N1** `TlsFailure.java:86-87`: an orphaned javadoc ("The name a mismatch failure says…") sits above `hostOf`. Move it to `mismatchedHost`.
- **N2** `tls/PinMismatchException.java:16-20`: the message names the removed "vCenter Thumbprint … (vCenter TLS Validation: Pinned)". It is now used only for the ESXi channel. Make it neutral.
- **N3** "19 fields": `README.md:115`, `VCommunityConfig.java:13`, `tests/.../ConfigTest.java:9`, design `:410`. Now 18.
- **N4** Stale wording: `RestClient.java:19` ("and the certificate probe": the probe is not on `VcenterTls`), `ThumbprintProbe.java:14-15` ("Run in every mode"), design `:156` ("Thumbprint handling:").
- **N5** Design `:229` credits the name check to `CertificateNames.secure`. The check is the trust manager from `getPlatformSslContext(dialledHost)` (`VCommunityAdapter.java:210`), and `secure` adds the strict verifier.
- **N6** DV-114 and Q6 are dated 2026-11-17 (design `:895`, `:1611`), but the commit is 2026-11-16.
- **N7** `describe.xml:70-71`: dispOrder goes from 7 to 9. nameKey 14 is reused with a new meaning, where compliance retires keys.
- **N8** `docs/installing.md:166,220`: "Test Connection warns". The test passes, and the warning is a WARN line in `collector.log` (as :533-538 explains). Say so.
- **N9** `TlsFailure.java:76-81` copies the framework's `MISMATCH_HOST_PATTERNS`. Add a comment naming the source so they do not drift.
- **N10** `docs/installing.md:365`: the TLS upgrade subsection sits inside "## Content", so "Two dashboards without Section rows" (:393) now falls under it, and Troubleshooting (:598) says "below" for a section above. Make it its own `##`.

## Registry

None of the open defects (DEF-004 vcommunity-os, DEF-019 factory:extractor, DEF-025/026 compliance) affect
`vcommunity-java`. Registration candidate: W2, if it survives acceptance on 8.18.7 (framework-wide, affects every pak
that uses `getPlatformSslContext`).

## Only a live test can settle

1. On 8.18.7, 9.0.2 and 9.1.1 (FIPS), with a new account in the UI: Validate Connection shows the accept dialog for a vCenter whose CA the cluster does not trust. After Accept, the same test passes (the accepted leaf reaches `getAdapterTrustManager()` for the unsaved account), and the first cycle collects. With a cluster CA (9.0.2 devel), there is no dialog and it passes.
2. The real failure shape of the platform trust manager in the *cycle* on each line: 9.1.1 `certificate_unknown(46)` / `CustomCertificateException`, 8.18.7 "trustAnchors parameter must be non-empty" (W2). Check that the account row and `Collection|last_error` show the intended sentence.
3. The name check on 9.1.1 BCJSSE through `HttpsURLConnection` with the framework trust manager and strict verifier: an IP-entered account fails with the name sentence, and a name-entered one passes.
4. An expired vCenter certificate: what the dialog offers, whether it can be accepted, and whether the platform trust manager then admits it.
5. In-place upgrade from the installed dev build: same account id, `allowInsecure` shown under Advanced with `false`, the old values still stored until a save, the account fails closed, and **no new vCenter session from the collector** (vCenter session list or SSO audit) until accept and save. After that it collects. Also check the remote-collector "Unknown adapter type" race (compliance 93 prod approval) and a same-version reinstall (W9).
6. API path: `POST /api/adapters` with default identifiers fails closed, and `testConnection` + `PATCH /api/adapters` accepts the leaf.
7. Allow Insecure SSL `true` on all three lines: connects (including FIPS), configure WARN in the account log, Test Connection WARN in `collector.log`, `F-GUEST-TLS-INSECURE` with a guest switch on, and no guest task in vCenter.
8. Certificate keys in verified mode on 9.1.1: the trust-all probe works under FIPS, and `Recorded`/`Unchanged` are published. After a renewal by a trusted CA, `Changed` appears. After an untrusted renewal there are login failures and no `Changed` (confirms B1's corrected text).
9. The exact text the Validate Connection dialog displays for untrusted, name mismatch and expired (B2, W1).
10. The guest phase runs with the default (P5 proof), and the Windows account crosses only the verified channel.
