# P1 live-proof fixes review (build 3)

- Adapter: `vcfcf_vcommunity` (`vcf-content-factory-sdk-vcommunity-java`), branch `p1/skeleton`
- Diff reviewed: `25f9dc0..9a0d9c4`, dev pak `dist/vcfcf_sdk_vcommunity.0.0.0.3.pak`
- Reviewer: `sdk-adapter-reviewer`, 2026-11-13, static only (nothing installed, no live instance touched)
- Verdict: **APPROVE**: 0 BLOCKING, 3 WARNING, 6 NIT

## Independent re-runs

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` at `9a0d9c4` | all pass (every suite prints `ok`, including the four new `TlsFailureTest` groups, `cycleSummaryLine` and the extended session test) |
| `validate-sdk` | OK. The 8.18.7 and 9.1.1 SDK compat checks were **skipped** (jars not in `adapter_runtime/`), same as for the author |
| `build-sdk` into a scratch dir | builds `0.0.0.3`. Every class in the adapter jar, `describe.xml` and the `.properties` files are byte-identical to the author's `dist/` pak, so the pak comes from this commit. Regenerated docs left the tree clean |
| `pak-compare` vs `tmp/vcommunity_ref9/vcfcf_sdk_vcommunity.1.0.0.9.pak` | PASS, 0 BLOCKING / 1 WARNING / 278 INFO. Same score for the author's pak and the rebuild |
| **Real BCJSSE FIPS handshake** (reviewer probe, scratch only) | Used the appliance's own `bc-fips-2.1.2`, `bctls-fips-2.1.23`, `bcutil-fips-2.1.6` and `bcpkix-fips-2.1.11`, with BCJSSE in FIPS mode as the default TLS provider, against the test `FakeVc`. Results below |

Probe results (production `VcenterTls.create` + `VimSoapClient`, nothing mocked except the server):

- Pinned, wrong pin: the provider throws `org.bouncycastle.tls.TlsFatalAlert: certificate_unknown(46)`, which is **not** an `SSLException`. `PinMismatchException` is its **direct cause**. `TlsFailure.findPin` finds it. The per-thread record is also set on the calling thread, so the handshake runs on the caller's thread. `ensureSession()` returns the full pin text, and **0 requests reached the server** (no Login, no credential).
- Enabled, untrusted chain: `TlsFatalAlert` → `CertificateException("Unable to construct a valid chain")` → `CertPathBuilderException`. The result is the "not trusted by this collector ... No credential was sent." text.

This confirms the root cause behind live finding 2: the old code caught only `SSLException`. It also shows the new cause walk is enough on this BC version; the per-thread record is a backstop that was not needed here.

## Registry check (`knowledge/context/defects.md`)

The tag gate derives the scope token `vcommunity-java` from the repo name (`build-pak-on-tag.yml:154-158`). **No defect, open or closed, has `Affects: vcommunity-java`.** The open defects are DEF-004 (`vcommunity-os`, the retired pak, a different repo), DEF-019 (`factory:extractor`), DEF-025 and DEF-026 (`compliance`). None of them touches this pak. No `defects.local.md` exists. Note: this pak is not yet in `knowledge/context/managed_paks.md` (only the retired `vcommunity*` entries are). That is the orchestrator's call; it is outside this diff.

## The probes the brief asked for

### Per-thread record in `PinnedTrustManager`

- **Stale state across connections on a pooled thread:** cannot cause a false attribution. `postOnce` calls `tls.clearPinMismatch()` (`VimSoapClient.java:349`) before every connection. `takePinMismatch()` is called only in that same method's catch block (`:401`), on the same thread. Nothing else reads the record. A later, unrelated failure on the same thread therefore always starts from a cleared record.
- **Wrong host or instance:** the `ThreadLocal` is an instance field of a `PinnedTrustManager`. Each `VcenterTls` builds its own (`VcenterTls.java:60-61`), and each account and each Test Connection builds its own `VcenterTls`. Two accounts on one thread use separate slots.
- **Cleanup on every path:** not complete. See N1. The record is left behind when the cause walk finds the pin first, and the success path never needs a cleanup. The leftover is bounded and never misread, so this is a NIT.
- **Handshake on another thread:** the fallback then returns null, and the failure is described as "the TLS connection to the vCenter failed (TlsFatalAlert: certificate_unknown(46)); vCenter TLS Validation: Pinned". That is still a failure and still sends nothing; it just loses the thumbprint. The probe shows BCJSSE handshakes on the caller's thread, and the walk alone already finds the pin.

### Softer reporting, credentials after a mismatch, a TLS failure as success

- Every transport failure goes through `postOnce`'s catch (`:395-404`): the cause walk, then the per-thread record, then `PinMismatchWrapper`. `post()` turns that into a fault (`:322-323`). No path returns OK on an exception.
- `login()` stops after a failed `RetrieveServiceContent` (`:159-164`). `Login` is a separate `postOnce`, and the TLS handshake (pin check, chain check, then `HttpsURLConnection`'s host-name verifier) finishes before `getOutputStream()` writes the body (`:365`). So a mismatch or an untrusted chain on the `Login` connection also means the password was not written.
- **"No credential was sent" is true whenever it is printed.** It appears in `PinMismatchException` (thrown only from the handshake) and in the Enabled untrusted-chain text (`TlsFailure.java:42-46`), which is matched only on CertPath/PKIX/`CustomCertificateException` shapes that come from the handshake. When it shows up on a keepalive or a Logout after a mid-session certificate change, it is still true for that connection: the cookie was not sent either.

### The `TlsFailure` walk

- **Cycles:** an identity set makes it cycle safe (`:62`). Tested with a suppressed loop.
- **Depth bound:** `MAX_NODES = 64` visited nodes, breadth first. A pin deeper than that is not claimed (tested at 200 wrappers). A real chain is 2 to 3 deep (probe).
- **When several causes qualify:** the order is pin, then (Enabled only) an untrusted chain, then the first `SSLException` or `TlsFatalAlert*` breadth first, which is the outermost one. So a pin is never hidden behind a generic alert.
- **Injection:** the pin text is built locally (computed hex). The kind-2 text includes `alert.getMessage()`, capped at 200 characters but with **control characters not stripped** (N3). JSSE and BC alert messages are local strings (alert names, the configured host name), so I found no route for certificate subject text in the modes that reach this branch. It still ends up unsanitised in the cycle summary line and the Test Connection dialog. `Collection|last_error` is safe: it goes through `Failures.bound`, which strips control characters (`Failures.java:101-105`).

### Server-text excerpt in check-list errors

- Source: the framework's `SuiteApiHttpException.getBodyExcerpt()`. It is capped at `ERROR_BODY_EXCERPT_MAX`, and control and format characters are replaced with `?` at construction (`SuiteApiHttpException.java:60-66`, `SuiteApiStitchClient.java:2576`). The adapter cuts it again to 120 characters (`CheckListStore.java:295-297`).
- Into `Collection|last_error`: through `failures.add` (`CycleEngine.java:301-302`), then `Failures.bound` (250 characters, control characters stripped). Single line, bounded.
- Into the log: through `Failures.logSummary` (bounded, stripped), and in Test Connection through `e.summary()` (already sanitised by the framework).
- Tokens or credentials: the body is a Suite API error document from a `GET /api/configurations/files...`. The request carries the token only in a header, and the server does not echo headers. I see no route for a credential. Acceptable.

### New log lines

- **Secrets:** none. The login, reuse and renewal lines carry `About.describe()` (product, version, API version). The logout line carries only the reason. The summary line carries counts and the failure text, which is credential-free by contract. `TlsAndSessionTest` asserts there is no password, cookie or user name in any of these lines.
- **Volume:** 2 INFO lines per cycle per account (reuse and summary), plus 1 per login, logout or renewal. At a 5-minute interval that is about 576 lines a day per account. Acceptable.
- **Is "session reused" true when printed?** It is checked against vCenter, not taken from a cached object: it is printed only after a `CurrentTime` round trip that carried the cookie answered 200 (`VimSoapClient.java:118-123`). Its truth depends on vCenter answering `NotAuthenticated` to `CurrentTime` on a dead session. The documented privilege is System.View, not System.Anonymous, so it should. The compliance triage (#23) also recorded this as plausible but unproven. **Live-only** (see the end of this report). In P1 no other authenticated vim25 call exists that would expose a wrong assumption.

### Logout on reconfigure or stop

- **Ordering and races:** `close(reason)` is `synchronized` like `ensureSession()`, so it waits for an in-flight session check or login. `closed = true` makes the in-flight cycle's later calls refuse. A login that finishes after `close` logs straight out (`:214-217`). `configureAdapter` swaps `runtime` before `release(old)` in `finally` (`VCommunityAdapter.java:102-136`).
- **Double logout:** the second close finds `cookie == null`. It sends nothing and logs nothing (tested).
- **Failure swallowed without masking the reconfigure:** yes. `close` never throws, and `release` catches `RuntimeException`. **But "logged out" can be printed when the logout failed** (W1).

### Finding 4 (Suite API in the test context)

- **A misconfiguration now passes Test Connection silently.** Previously, a Suite API client that could not be built gave a WARN in Test Connection. Now every `RuntimeException` from `SuiteApiStitcher.create` during a test becomes a plain INFO line. The exception class is not kept anywhere (`VCommunityAdapter.java:166-167`). The INFO line is written nowhere during a test.
- **Is the distinction sound?** The split is by phase (test or collection), not by cause. "Cannot be built here" and "broken" are told apart only by assuming that a test can never build the client. Collection still reports the failure (a WARN at configure, a FAILED check-list collector, `last_error`), so it is not silent end to end. The test, though, no longer tells the two apart. See W2.

### The `forTesting` seam

- No production caller: `grep` finds it only in `tests/`. No config or reflection path reaches it. It is public API in the shipped jar, and `forTesting(ENABLED, ...)` would throw a `NullPointerException` in `secure()` because the securer is null (N4).

### The tests

- `TlsFailureTest.throughTheClient` runs the real `VimSoapClient.postOnce` → `post` → `login` path and the real `PinnedTrustManager.checkServerTrusted`. Only the socket factory is fake (it throws a chosen shape from `createSocket`). The "FIPS" alert is a test class named `TlsFatalAlert`, matched by simple name. The real-handshake cases in the suite use SunJSSE. **No test in CI runs BCJSSE** (W3). The reviewer probe shows the code is right on the appliance's BC FIPS jars.

## Findings

### BLOCKING

None.

### WARNING

**W1. "vCenter session logged out" is printed when the logout was refused** (`VimSoapClient.java:283-298`, `:271-279`)
Authority: author hard rules, logging quality ("logs must let an operator tell what happened"); the method's own javadoc ("false when the logout could not be sent **or failed**").
`logoutAndClear` sets `ok = false` only when `postOnce` throws. A Logout that vCenter answers with an HTTP 500 SOAP fault returns normally through `faultResult` (`:393-394`), so `ok` stays true.
Failing scenario: the vCenter restarts, or an admin kills the session in the vSphere Client, between cycles. The admin then edits the account. Logout answers `NotAuthenticated`, and the log says `vCenter session logged out (reason: reconfigure)` for a session that was already gone. Any other fault (for example `NotSupported` behind a proxy) also reads as a clean logout.
Fix: use the returned `VimResult`. Set `ok = r.isOk()`, and give `NotAuthenticated` its own wording ("the session had already ended"). Add a FakeVc case where Logout answers with a fault.

**W2. Test Connection drops every Suite API build failure without a trace** (`VCommunityAdapter.java:163-173`, `ConnectionTester.java:88-95`)
Authority: skill §Logging, consequence (1) (diagnostics a user must see go in the result or at WARN); author hard rules on failure granularity (no broad catch that turns a real error into a silent pass).
`openStitcher(false)` returns null for any `RuntimeException` and keeps nothing. The report then states a cause as fact ("its client uses a credential the platform gives a configured account").
Failing scenario: on a collector where the client also cannot be built during collection (a remote collector without the ambient credential or fallback files, or a framework regression in `SuiteApiStitchClient.build()`), Test Connection is green with no WARN. The first sign is the configure WARN and a FAILED check-list collector after the account is saved. Neither the dialog nor the log says whether the test hit the expected "no configured account" case or something else.
Fix (smallest): put the exception's simple class name in the plain line ("not checked during a test (IllegalStateException)"). Better: treat only the known no-credential exception or message from the builder as "not checked" and keep WARN for anything else. Also prove on a live instance whether a Test Connection on an **existing** account (Edit → Validate) can build the client.

**W3. The FIPS fix has no real-provider regression test** (`tests/.../TlsFailureTest.java`)
Authority: the reference idiom, compliance's `BcHandshakeTest` with `ci/run_java_tests.sh` (BCJSSE from Maven Central, checksum pinned, required in CI). This build's own CHANGELOG ties the defect to the provider's exception shape.
The CI tests only rebuild that shape by hand: an `IOException` subclass named `TlsFatalAlert`, thrown by a fake socket factory. If BC changes the shape (cause moved to suppressed, alert renamed, a wrapper that is an `SSLException` but drops the cause), CI stays green and live finding 2 comes back.
Failing scenario: a 9.1.x patch ships a bctls that wraps the alert differently. Pinned with a changed certificate goes back to `TlsFatalAlert: certificate_unknown(46)`, and nothing in CI notices.
Fix: port `BcHandshakeTest`: run Pinned with a wrong pin and Enabled with an untrusted chain under BCJSSE, through `VimSoapClient`, and assert the pin text and 0 requests reached the server. The reviewer probe (same assertions, on the appliance's FIPS jars) passed, so this is regression cover, not a correctness gap today.

### NIT

**N1.** `VimSoapClient.java:400-401`: when `findPin(e)` finds the mismatch, `takePinMismatch()` is never called, so the record (a `PinMismatchException` with its stack) stays in the collector thread's `ThreadLocalMap` until that thread's next `postOnce`. If the trust manager is discarded on reconfigure, it stays until the map expunges stale entries. The leak is bounded and never misread. Fix: always take the record (`PinMismatchException rec = tls.takePinMismatch(); pm = found != null ? found : rec;`), or clear it in a `finally`. Also add a negative test: record a mismatch, then make an unrelated failure on the same thread, and assert it is not reported as a pin mismatch. That is the property the design depends on, and it is currently guaranteed only by the clear at `:349`.

**N2.** `TlsFailure.java:72-77`: kind 1 also matches `CertPathValidatorException` for an **expired or revoked** certificate (JDK: "validity check failed"). The advice "Accept the certificate in Validate Connection" may not help there. Consider using the validator's reason (`getReason()`) to say "expired" or "revoked".

**N3.** `CycleEngine.java:242` (`bound`, `:346-347`) and `TlsFailure.java:49-52`: server text (a vCenter `faultstring` from a non-`InvalidLogin` login fault, or the TLS alert text) goes into the INFO summary line and the Test Connection result with newlines kept. `Failures.bound` already strips control characters. Use it, or the same regex, in both places so one log line stays one line.

**N4.** `VcenterTls.java:83`: `forTesting` is public in the shipped jar. With `ENABLED` it builds a posture whose `secure()` throws `NullPointerException`. Refuse `ENABLED` in it, or move the test into the `tls` package and make the method package-private.

**N5.** `docs/installing.md:105`: "a wrong pin, in every TLS mode and under FIPS" is muddled, because a pin exists only in Pinned. "Under FIPS" is proven here on the appliance's BC jars offline, not yet on a live 9.1.1 collector. Reword to "a wrong pin (Pinned) or an untrusted certificate (Enabled), also under FIPS".

**N6.** `docs/installing.md:136-141`: a re-login during a call (`call()` → `login(null)`) writes only the WARN "session not authenticated on <op>; logging in again", with no login or renewal line. The Logs table does not mention it. No such call exists in P1; add the row when the first vim25 read lands.

## If shipped as-is

An operator gets the promised session and cycle lines and, under FIPS, the correct pin text with nothing sent. The probe confirms this on the appliance's BC jars. They may see "logged out" for a logout vCenter refused. A Suite API client that cannot be built passes Test Connection without a word, and the problem shows up only after the account is saved.

## Still to prove on a live instance

1. **9.1.1 FIPS, Pinned, wrong thumbprint:** the dialog and the account log show the full pin text, and the vCenter session list shows no new login from the collector.
2. **Session reuse is real:** with a session held, terminate it in vSphere Client (Administration → Sessions). The next cycle must log `renewed`, not `reused`. If it logs `reused`, `CurrentTime` is not authenticated, and the reuse line and the keepalive design are both wrong.
3. **INFO lines reach the account log** on 9.0.2 and 9.1.1 (through `conf/logging.properties`) and on 8.18.7 (through the framework's raise-to-INFO, since the 8.18 SDK never reads the file; skill §Logging says 8.18 routing is unmeasured).
4. **Logout on edit and stop:** the line appears once with the right reason, and the session disappears from vCenter's session list. Also test editing after the session was killed (the W1 case).
5. **Finding 4:** run Test Connection on a new account and on an existing one (Edit → Validate) on each version, and record whether the Suite API client builds. The "always no" premise in `openStitcher` rests on this.
6. **8.18.7 check-list HTTP 400 with no `apiErrorCode`:** the new excerpt shows what the server says, so the file can be classed as missing or as a bad request.
7. SDK compat against the 8.18.7 and 9.1.1 jars: skipped in this review and in the author's run (jars not present).
