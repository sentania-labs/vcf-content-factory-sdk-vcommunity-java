# Review: P5 guest operations and events (build 12)

- **Adapter:** `content/sdk-adapters/vcommunity-java` (kind `vcfcf_vcommunity`), branch `p5/guest`
- **Delta reviewed:** `git diff b48bcab..219b5b1` (5 commits, 62 files, +5469/-75)
- **Pak:** `dist/vcfcf_sdk_vcommunity.0.0.0.12.pak`
- **Reviewer:** `sdk-adapter-reviewer`, 2026-11-13. Read-only on source; nothing installed; no live Ops, vCenter or guest touched.
- **Report location:** `docs/reviews/`, as the brief asks and as P1 to P4 did (the agent default is the factory's
  `knowledge/context/reviews/`).
- **Upstream:** read only at tag `v0.8.1` (`git -C ../vcommunity show v0.8.1:<path>`).

## Verdict: CHANGES REQUESTED

3 BLOCKING, 14 WARNING, 9 NIT.

The work is careful. Default-off holds. The wire names are right. Credentials stay inside the `auth` element. The
transfer TLS fails closed. The CSV parser is strict and bounded, cleanup runs on every path, and the scripts are
independently written. Three things stop it shipping, even disabled by default:

- **B1.** The account-lockout protection (DV-97) does not protect the case it exists for. With one or two Windows VMs
  (the "monitor my domain controllers" case) the pause never trips, so a wrong password is tried every cycle. On a
  larger fleet the four parallel workers overshoot the limit to 6 refusals per cycle. Either way a directory account
  is locked under a CIS-style threshold of 5.
- **B2.** A guest that controls its own output can make the collector create up to 10,000 new property instances per
  cycle on its VM, with no bound across cycles. The services rows are published whether or not they were asked for.
- **B3.** A Windows event query that fails (access denied, a missing log, any error other than "no events") is written
  to the guest's host stream and dropped. The VM shows no events and the guest health says OK. The shipped
  `installing.md` says this cannot happen.

## Independent re-runs

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` | 3 serial runs, `exit=0` each, 218 `ok` lines each, no failure. BCJSSE ran (`BcHandshakeTest: all assertions passed`). The tree was clean afterwards |
| coverage TSVs | `Coverage.verifyOrWrite`, `Coverage3`, `Coverage4` and `Coverage5` passed in verify mode in all 3 runs, so `docs/p2-` to `p5-key-coverage.tsv` regenerate byte-identical. P5 has 9 rows (R72 to R80) |
| `validate-sdk` | OK. Both SDK compat checks (8.18.7, 9.1.1.0100) were **skipped**: no jar in `adapter_runtime/`, the same as P1 to P4 |
| `build-sdk` to scratch | builds `0.0.0.12`. All 371 entries match the author's `dist/` pak, including every class inside the nested jar. Only `adapters.zip`, the jar container and `overview.packed` differ (build stamps) |
| `pak-compare` vs `tmp/vcommunity_ref9/vcfcf_sdk_vcommunity.1.0.0.9.pak` | PASS: 0 BLOCKING, 2 WARNING (identifier count 19 vs 11, TraversalSpecKind 1 vs 0, both as in build 9), 279 INFO |
| PowerShell parser | `pwsh` 7.5.1 `Parser.ParseFile` on the three program texts that `GuestScripts.program` generates: **0 parse errors** each. Argument strings are 3,403 (OS), 3,591 (services) and 4,867 (events) characters |
| PowerShell behaviour | Ran under pwsh 7.5.1 with stand-in cmdlets (scratch harness, see below). (a) A `StartType` getter that throws (.NET `Win32Exception(5)`) is swallowed the same way by the port and by upstream's `Select-Object`: same CSV. (b) String concatenation and interpolation of a `DateTime` both give invariant culture under `de-DE`: same `[Timestamp=...]`. (c) Confirmed B3: an `UnauthorizedAccessException` gives exit 0 and no row. (d) Confirmed W1: `[Category=system]` for a list entry `system` |
| vim25 wire names | Checked against pyVmomi `_typeinfo_vim.py`, operation names and parameter order: `CreateTemporaryDirectoryInGuest(vm, auth, prefix, suffix, directoryPath)`, `CreateTemporaryFileInGuest(...)`, `InitiateFileTransferToGuest(vm, auth, guestFilePath, fileAttributes, fileSize, overwrite)`, `InitiateFileTransferFromGuest(vm, auth, guestFilePath)` returning `FileTransferInformation{attributes,size,url}`, `ListFilesInGuest(vm, auth, filePath, index, maxResults)`, `DeleteDirectoryInGuest(vm, auth, directoryPath, recursive)`, `StartProgramInGuest(vm, auth, spec{programPath, arguments, workingDirectory})`, `ListProcessesInGuest(vm, auth, pids)` returning `GuestProcessInfo{..., endTime, exitCode}`, `TerminateProcessInGuest(vm, auth, pid)`. Types: `NamePasswordAuthentication` (base field `interactiveSession` first, then `username`, `password`), `GuestFileAttributes`, `GuestOperationsManager.fileManager/processManager`. **All correct, case included.** The envelope declares `xsi`, so the `xsi:type` attributes resolve |
| Suite API events | `POST /api/events/bulk` with body `{"event":[...]}` (framework `SuiteApiStitchClient`). Fields `eventType: DIAGNOSTIC`, `diagnosticSubType: LOG`, `severity` in {`INFORMATION`, `WARNING`, `IMMEDIATE`, `CRITICAL`}, `managedExternally: false`, `startTimeUTC`, `resourceId`, `message`. All present with these names and enum values in `reference/docs/operations-api-8.18.json` and `operations-api-9.1.json` |
| defect registry | `knowledge/context/defects.md`: no open DEF has `Affects: vcommunity-java`. DEF entries for `vcommunity-os` and `vcommunity-vsphere` are other paks |

Scratch harness (not in the repo): a `Dump` class compiled against `GuestScripts.java` writes the three program texts.
`getter.ps1` and `events.ps1` define stand-ins for `Get-Service` and `Get-WinEvent` and run the programs.

## Security review

### Credentials

| Path | Finding |
|---|---|
| Logs at any level, Collection Health strings, event text | Clean. Only `GuestAuth.element()` reads the password, and only `GuestOpsClient.head` calls it, into the request body. No log or health string carries a request body. `GuestAuth.toString` and `VCommunityConfig.toString` are redacted. Event text comes from the guest, not from us |
| Exception messages and cause chains | `VimResult.cause()` is carried but never formatted into a message or log line in the guest package. `HttpsGuestHttp.described` rewraps every `IOException` as class-name-only text. **W4:** a vCenter fault string is cut at 200 characters *before* `GuestAuth.scrub` runs, so a password the server echoes across the cut is only partly masked |
| Process command line in the guest | Only switches and the encoded program. The program text holds two guest-named paths and no account (`GuestOpsTest.noSecretAnywhere` decodes and checks) |
| Script bodies, file names, temp files on the collector | None hold a secret. Nothing is written to the collector's disk |
| Heap dumps | The password lives as a `String` in `VCommunityConfig`, in `GuestAuth`, and transiently in each request body. This is unavoidable with the SDK credential API, and upstream is the same |
| SOAP request logging | `VimSoapClient` never logs a body (P1-reviewed) |
| Sent before vCenter TLS validation? | **Yes, in Trust on First Use, the default vCenter mode** (`VcenterTls` is trust-all there). Every guest call carries the Windows account over that channel every cycle. The port warns once per engine (`CycleEngine.java:568-572`) and `installing.md` step 3 says so. **W5** |

### Guest file transfer

- **URL host.** `TransferUrl.check` requires HTTPS, no user info and printable ASCII. A `*` host becomes the inventory
  name of the VM's ESXi host (restricted to `[A-Za-z0-9._-]`). Any other host is accepted as given, which is safe because
  the pin is the identity.
- **Handshake before bytes.** `HttpsGuestHttp` calls `connect()`, which does the TLS handshake, before
  `getOutputStream()` or `getResponseCode()`. So neither the PUT body nor the ticket-bearing request line is sent until
  `PinnedTrustManager` has accepted the leaf. Redirects are off, and a 3xx is a failure. No auth header ever goes to
  ESXi (the ticket is in the URL). A refused channel throws before opening a socket.
- **SHA-1 pin.** Sound for this use. It needs a second preimage against the *existing* host certificate, which is
  infeasible; SHA-1's collision weakness needs the attacker to shape the legitimate certificate. The expected thumbprint
  comes from `summary.config.sslThumbprint` in this cycle's host read, over the vCenter session. So a MITM of
  *vCenter* (possible in TOFU, see W5) can supply both the thumbprint and the URL. Guest-transfer TLS is never stronger
  than vCenter TLS. A network MITM between the collector and ESXi alone cannot win.
- **Absent thumbprint (DV-95).** Refused in Pinned and Strict, with no connection made. Correct and security-justified.
- **FIPS (E15).** BC-FIPS allows a SHA-1 `MessageDigest` in approved mode for non-signature use, as far as I know. It is
  unproven on 9.1.1. If the digest is missing, `Thumbprints.digest` throws `IllegalStateException` inside the trust
  manager. The handshake then fails closed, but as `F-GUEST-UPLOAD` with "the transfer to the ESXi host failed
  (SSLException)", with no hint of the cause. **W6**
- **vMotion between the host read and the transfer** makes a pin mismatch that reads like a MITM. **W7**

### Command injection and argument handling

- Nothing operator-editable, nor any inventory text, reaches the program text or the command line.
  - The program text binds two paths. Both are guest-named and pass `DIRECTORY` / leaf regexes of `[A-Za-z0-9._-]`
    before `literal()` single-quotes them.
  - Service names and `(log, id)` pairs travel as **data** in a file: `Get-Content -LiteralPath`, then
    `Get-Service -Name $name` and `FilterHashtable @{LogName=$log; Id=[int]$id}`. Quotes, backticks, `$()`, semicolons
    and newlines are inert in a variable value.
  - Service names still pass upstream's safe-name rule, so no wildcard reaches `-Name`.
  - Log names with a control character, tab, separator, bidi override, surrounding spaces, or over 256 characters are
    refused (DV-94). A `*` log name is passed through as a FilterHashtable wildcard; upstream does the same.
  - Event ids are digits only (`EventListValidator`). An id over `[int]` range fails its own query only.
  - VM names never reach the guest (the job carries the MoRef).
- The program path is fixed and absolute: `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`. The working
  directory is `C:\Windows\Temp`.
- **Execution policy.** `-EncodedCommand` is command text, so a MachinePolicy or UserPolicy setting of AllSigned or
  Restricted does not apply. No policy change is needed or made.
- **CLM.** The bodies use cmdlets, `[PSCustomObject]` and the core-type casts `[char]` and `[int]`. The only .NET calls
  (`New-Object UTF8Encoding`, `[IO.File]::WriteAllLines`) are inside a `try` with an `Out-File -LiteralPath -Encoding
  utf8` fallback. The resulting BOM is dropped by the parser. This should run under CLM; it is unproven (G2).
- **AppLocker/WDAC exe rules** that deny `powershell.exe` to the account surface as `F-GUEST-START` or `F-GUEST-EXIT`,
  which is honest.
- **EDR.** `-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand` is a high-fidelity detection pattern.
  **W8**

### Files in the guest

- **Where and how named.** A directory under `C:\Windows\Temp`, named by Tools as our prefix, a random number and our
  suffix. Both files are named by `CreateTemporaryFileInGuest` inside it. Nothing has a name chosen by the collector.
- **Permissions (inherited, to be confirmed live).** `C:\Windows\Temp` normally grants `BUILTIN\Users` only
  `(CI)(S,WD,AD,X)`, with no list right, and CREATOR OWNER full control on objects. So a low-privileged local user
  cannot list `Temp` to find the random name. That user cannot read the output (Security-log text lives there briefly)
  or modify the input. The output exists before the program runs and is owned by the account, so it cannot be swapped.
  There is no script file to swap; the TOCTOU of upstream's uploaded `.ps1` is gone.
- **Residual exposure.** Users *can* add a subdirectory or junction inside our directory if they know its name.
  Whether Tools' recursive `DeleteDirectoryInGuest` follows a junction is a live question (L10). The exposure is the
  same as upstream's.
- **Cleanup.** Runs on success, every failed step, timeout (process terminated first), interrupt and cancel, with one
  retry after 1 s. A failed removal is remembered (10 per VM, 2,000 VMs) and retried at that VM's next run, with paths
  re-checked against the regex before deletion.
- **Cleanup gaps.**
  - Collector restart: leftovers are lost. This is registered as not built.
  - A VM powered off mid-run: retried later, which is correct.
  - **N4:** leftovers after the first fatal one are forgotten.
  - **W9:** a directory whose returned path does not match the case-sensitive regex is left behind every cycle.

### Untrusted guest output

- **Bounds.** 1 MiB (from `FileTransferInformation.size` and the streaming cap), 10,000 rows, 32 columns, 32 KiB per
  field, and strict UTF-8 (`REPORT` on malformed input).
- **Parsing.** One leading BOM is dropped. RFC 4180 quoting with embedded CR/LF is handled. A quote inside an unquoted
  field, text after a closing quote, or an unclosed quote refuses the whole text.
- **Values.** NUL and other controls, C1, U+2028/2029, bidi overrides and isolates, and lone surrogates make a value
  unsafe, so its row is skipped. Instance names must be non-blank, at most 128 characters, with no `|`.
- **Event text.** Cleaned: CRLF becomes LF and controls are dropped. Cut at 2,000 characters. The level is cut at 40
  characters, one line.
- **No forging across objects.** Events can only target the job's resource id, and keys can only fall under
  `vCommunity|Guest OS|Operating System|<six fixed>` or `...|Services:<instance>|<three fixed>`.
- **Nothing a guest wrote is logged.** All parse errors are fixed strings.
- **But B2:** the services instances are unbounded across cycles and not tied to the requested names.

### Account lockout and blast radius

B1 below. The other figures:

- 4 workers.
- Each SOAP call 30 s, each program 300 s, each VM 600 s.
- No VM starts after the deadline (interval minus 60 s).
- Hard stop at deadline plus 45 s, then a 20 s cleanup wait. **W10:** this can overrun the cycle interval.
- The main push is already done when the phase starts (DV-100), so the phase cannot delay the main keys. It can delay
  the *next* cycle (W10).

### Default-off guarantee: proven from the code

- `GuestPlan.of` returns `SWITCHED_OFF` when both switches are off (`GuestPlan.java:224`).
- `CycleEngine` passes `plan.runs()` (false) into `PropertyCollection.Input.guest`. So the VM and host reads ask for
  none of `guest.toolsStatus`, `guest.guestFamily`, `runtime.powerState` or `summary.config.sslThumbprint`
  (`VmCollector.request`, `HostCollector.request`).
- No `GuestRoster` is created.
- `guest()` returns at its first line (`if (!plan.runs()) return;`) before `new GuestAuth(cfg.winUser,
  cfg.winPassword())`. So the password is never handed to the guest package, and no `GuestOpsClient`, `GuestTrust` or
  `HttpsGuestHttp` call is made.
- `VCommunityConfig.filesInUse` does not fetch the two Windows lists while their switch is off.
- `GuestEngineTest.off` asserts zero guest operations, zero ESXi connections, no extra read paths, no list fetch and no
  event push.
- One caveat (N1): the password is still parsed into `VCommunityConfig` at configure time, so "no credential read" is
  true of the guest package, not of the account object. Upstream is the same.
- **Enabling one switch does not do the other's work:**
  - `serviceMonitoring` only: `events = false`, and the event list is not fetched.
  - `winEventMonitoring` only: `operatingSystem = serviceMonitoring = false` and `services = false`.
  - Verified in `GuestPlan.create` and `filesInUse`.

## BLOCKING

### B1. The account-lockout protection (DV-97) does not prevent a lockout in the two common shapes

- **Where:** `guest/GuestPhase.java:228` (the gate is read only when a task *starts*), `:246` (the count rises only
  when a VM *finishes*), `:321-322` (the pause needs `refused >= 3` within one collection), `:95`
  (`pausedUntilCycle` is in memory only). Also the shipped claims in `docs/installing.md:118` and `docs/overview.md:165`.
- **Authority:** brief "Account lockout and blast radius" (security, blocking); DV-97's own rationale ("a wrong
  password on every VM of a fleet locks a directory account"); skill § Unreadable is NOT compliant does not apply, but
  doctrine dimension 11 does: the docs promise "so a wrong password does not lock the account".
- **Scenario 1, small fleet (the DC case).**
  - Setup: two Windows domain controllers, a domain account, and a CIS lockout policy (threshold 5 within 15 minutes).
  - Each cycle has 2 refusals, below 3, so the pause never trips.
  - Over 15 minutes that is 3 cycles × 2 = 6 failed logons, and the account is locked.
  - With one VM the account is tried 3 times per 15 minutes, or 6 per 30 minutes. That locks it under any threshold of
    6 or fewer with a 30-minute window.
  - The test (`GuestEngineTest.accountProtection`) uses 10 VMs, so it never sees this.
- **Scenario 2, overshoot.**
  - Four workers start VMs 1 to 4 at once, and all are refused.
  - VM 1 returns (count 1), and its worker starts VM 5 because the gate reads 1 < 3. VM 2 returns (count 2), and its
    worker starts VM 6.
  - That gives 6 refusals in seconds; the test itself asserts `tries <= 6`.
  - Under a threshold of 5 the account is locked in the first cycle.
- **Scenario 3, restart.** The pause is memory only. Any adapter or collector restart (an Ops node restart, an
  instance stop/start, the platform restarting a failing adapter) clears it and allows another burst.
- **Smallest fix:**
  1. Count refusals across cycles, per account generation: any refusal starts the pause, or "3 refusals in the last
     N collections" without reset per cycle.
  2. Make the first VM of a cycle (and the first after any refusal) run alone, and fan out to 4 only after one
     successful authentication in this account generation.
  3. Optionally keep the pause across restarts in an adapter-instance property.
  4. Fix the two doc sentences, and register the exact behaviour in DV-97.
  5. Add tests with 1 VM and 2 VMs over 4 cycles, and one asserting at most `AUTH_FAILURES_BEFORE_STOP` refusals.
- **Upstream:** v0.8.1 tries every VM every cycle (worse). Matching it is not an option here: this is the
  security-overrides-match case.

### B2. A guest controls how many property instances the collector creates on its VM

- **Where:** `guest/GuestRows.java:224-251` (every row becomes a `Service`, and nothing ties it to the requested names);
  `guest/GuestPhase.java:365-370` (every one is published as three properties); `guest/GuestCsv.java:20` (the only cap,
  10,000 rows).
- **Authority:** brief "can guest-controlled text create arbitrary metric/property keys" (security, blocking); the
  class comment on `GuestRows` ("None of it can name a key the table did not earn") is not true across cycles.
- **Scenario:**
  - Anyone with admin rights in a monitored guest controls what comes back: a tenant VM owner, a compromised server, or
    a hooked `vmtoolsd` that answers the transfer.
  - Each cycle the attacker returns a 400 KB CSV of 10,000 rows with new display names (`x<cycle>-<n>`).
  - The port publishes 30,000 new property keys on that VM per cycle. That is about 8.6 million a day, and they are
    never removed ("Once collected, a property stays with the object forever").
  - This is a storage and performance denial of service against the Ops cluster from one guest.
  - Upstream has the same exposure. That does not make it acceptable here.
- **Smallest fix:**
  1. Publish a service row only when its `Name` equals, case-insensitively, a name the plan sent. `Get-Service -Name`
     with wildcard-free names returns at most one service per name, so an honest guest is unaffected.
  2. Count the rest as `F-GUEST-ROW`.
  3. Cap services at `plan.serviceNames.size()`.
  4. Cap events at `min(MAX_PER_VM, plan.queries.size())`, since each query yields at most one row (`-MaxEvents 1`).
     This bounds events too.
  5. Add a test in `GuestOpsTest.hostileOutput` with unrequested service rows.

### B3. A Windows event query that fails is silently "no events", and the health says OK

- **Where:** `guest/GuestScripts.java:107-113`. The `catch` writes `query failed: ...` with `Write-Host`, which goes to
  the guest host stream and is never collected, then `continue`. The program exits 0. `GuestRunner.parse` then sees a
  header-only table and counts `scriptsOk++`, and `GuestPhase.account` marks the VM `OK`. The contradicted doc is
  `docs/installing.md:126-127`: "a window is never shown as having no events, because a read failed".
- **Authority:** skill § Unreadable is NOT compliant (a failed read becomes a pass); reviewer dimension 3 (a swallowed
  error that yields a pass is BLOCKING); dimension 11 (docs contradict behaviour); the brief's honesty item ("auth
  failure ... published as 'no events'").
- **Concrete input:**
  - List entry `<Log Name="Security"><EventID>4625</EventID></Log>` (the default list has `Security/4025`).
  - Account: a local admin that is not RID 500, whose Tools logon gets a UAC-filtered token, or any non-admin account.
  - `Get-WinEvent` throws `UnauthorizedAccessException`, which I reproduced with a stand-in: `exit=0`, no row.
  - Result: no Security event ever reaches the VM, and `Collector:guest|Status` = `OK` every cycle.
  - The same happens for a misspelled or absent log (`There is not an event log on the localhost computer that matches
    "Sytem"`).
  - Upstream does the same (parity). But the port's own docs promise otherwise, and the cardinal rule outranks parity
    for health.
- **Smallest fix:**
  1. In the events program, count failed queries and write them to the output: either a trailing comment-free sentinel
     row the parser recognises, or a second small output file. Alternatively end with `exit 3` when any query failed,
     after writing the found rows.
  2. In `GuestRunner`, treat exit 3 as "parsed, plus `F-GUEST-QUERY` with the count". Still send the events that were
     found.
  3. Add a `GuestOpsTest` case.
  4. Keep the event text unchanged.

## WARNING

**W1. `[Category=...]` is the configured log name, not the record's (a visible, unregistered mismatch).**
- **Where:** `GuestScripts.java:118` uses `$log`. Upstream `getWindowsEventLogs.ps1:51` uses `$firstEvent.LogName`.
- **Concrete input:** `<Log Name="system">` gives the port `[Category=system]` and upstream `[Category=System]`
  (reproduced under pwsh). A wildcard log name `Microsoft-Windows-*` gives the port the pattern; upstream gives the
  real log.
- **Effect:** the message is also the event's identity, so a differential sees a different event.
- **Fix:** `$record.LogName`. No security reason prevents it.

**W2. DV-99 changes published event text without a security reason (match first).**
- **Where:** `GuestText.eventText`, `WindowsEvents.java:40`.
- **Why it matters:**
  - CRLF becomes LF and the text is cut at 2,000 characters.
  - Neither CR nor length is an injection vector into an Ops event message: the text is never logged, and the JSON
    encoder escapes it.
  - The default list's `Application/1026` (.NET Runtime) events routinely exceed 2,000 characters (stack traces), and
    so do many Security events.
- **Fix:** keep CRLF. Keep text up to a limit that is justified by a measured Ops maximum (add it to the live proof) or
  by the 32 KiB field cap. Strip only NUL and bidi controls, and register that narrower set.

**W3. A truncated output can publish a false empty value (Q4 extension).**
- **Where:** `GuestRunner.java:315-320` and `GuestCsv.java:115-118`.
- **How it happens:**
  - A process that is "gone" (no `exitCode`) is treated as success. This is parity, but in the port it means the
    output is parsed whatever its state.
  - The parser accepts a last record without a line terminator.
- **Concrete input:**
  - EDR, a Tools restart or memory pressure ends `powershell.exe` while `WriteAllLines` is flushing its buffer. The
    file ends `..."Running",`.
  - The last row has 4 fields and passes the length check. `StartType` = `""` is published as an empty Start Type.
  - A cut between rows silently drops the later services. This is less harmful, because there is no false value.
- **Fix:** both programs always end with a newline (`WriteAllLines` and `Out-File` do), so refuse a text whose last
  record is not newline-terminated (`F-GUEST-PARSE`). Optionally, when the process is gone with no exit code, require
  that.

**W4. The fault text is cut before it is scrubbed.**
- **Where:** `vim/VimSoapClient.java:619` cuts `faultstring` at 200 characters. Only then does
  `GuestOpsClient.clean` (`:266-270`) run `GuestAuth.scrub`.
- **Concrete input:** a server echoes `... user svc_ops password Pa55-word-long ...` with the password straddling
  character 200. The unmasked prefix `Pa55-word-l` goes into the failure message, Collection Health and the log.
- **Test gap:** `noSecretAnywhere` echoes the secret early, so it never meets the cut.
- **Fix:** scrub in `VimSoapClient.faultResult` before cutting, by passing a scrubber in, or have `GuestOpsClient`
  keep the raw fault text. Add a test with the secret straddling the cut.

**W5. Under Trust on First Use (the default vCenter mode) the Windows account goes to vCenter unverified every cycle.**
- **Where:** `CycleEngine.java:568-572` warns once per engine and proceeds. It is documented (`installing.md` step 3),
  and owner decision Q6 covers the vCenter account.
- **Why it matters:** a Windows account for DC monitoring is usually more privileged than the read-only vCenter
  account.
- **Fix:** under the "security overrides match" direction, either refuse the guest phase in TOFU (health `SKIPPED`,
  cause "use Pinned or Enabled for vCenter") as a registered DV, or register the decision to allow it explicitly in 5.7.
  Today it is only a log line.

**W6. A missing SHA-1 digest on a FIPS collector (E15) is undiagnosable.**
- **Where:** `tls/Thumbprints.java:23-26`. The `IllegalStateException` thrown inside the trust manager surfaces as
  `F-GUEST-UPLOAD`, "the transfer to the ESXi host failed (SSLException)", on every VM.
- **Fix:** probe `MessageDigest.getInstance("SHA-1")` once when `GuestTrust` is built for a 40-digit pin. When it is
  missing, refuse with `F-GUEST-TLS` "SHA-1 is not available in this JVM (FIPS?)".
- **Test gap:** BcHandshakeTest uses non-FIPS BC jars, so it cannot settle this. E15 must.

**W7. A vMotion between the host read and the transfer reads as a MITM.**
- **Where:** `GuestPhase.java:237` takes `hostMoref` from this cycle's VM read. The guest phase runs after the main
  push, seconds to minutes later.
- **Concrete input:** DRS moves the VM. vCenter returns host B's URL, the port pins host A's thumbprint, and the result
  is `F-GUEST-TLS` "presented a certificate ... other than the one vCenter has recorded".
- **Fix:** on a mismatch, re-read `runtime.host` for that VM (one call). If it changed, use the new host's pin or say
  "the VM moved". At minimum soften the message.
- Related, N5: a host whose read failed is reported as "no usable thumbprint".

**W8. `-EncodedCommand` with `-ExecutionPolicy Bypass` is a standard EDR detection.**
- **Where:** `GuestScripts.java:136`.
- **Scenario:** on Defender for Endpoint or CrowdStrike estates, every guest raises an alert every 5 minutes, or the
  process is blocked. A block surfaces as `F-GUEST-EXIT` or a gone process (W3).
- **Fix:** not a code fix today. Document it in `installing.md` (expected command line, so SOCs can allow-list it by
  parent `vmtoolsd.exe` and account). Add it to the live proof. `-ExecutionPolicy Bypass` is redundant with
  `-EncodedCommand` and could be dropped to reduce the signature.

**W9. A directory path in an unexpected form is left in the guest every cycle.**
- **Where:** `GuestRunner.java:91` (a case-sensitive `C:\Windows\Temp\...` regex) and `:163-166` ("left alone").
- **Scenario:** Tools returns `c:\windows\temp\...` or a short-name form. Every cycle leaves 3 empty directories per VM,
  864 a day, and they are never cleaned up.
- **Fix:** match case-insensitively. When the path is under the requested parent with our prefix and suffix, delete it
  even though nothing ran. Record the real form in the live proof.

**W10. The guest phase can push a cycle past its interval.**
- **Where:**
  - Deadline = start + interval - 60 s (`VCommunityAdapter.java:540`).
  - `hardStopMs = deadline + 45 s` (`CycleEngine.java:595`).
  - Then `CLEANUP_WAIT_MS = 20 s` (`GuestPhase.java:48`).
  - Then the account bookkeeping, the event push and the guest push. Each Suite API call has a 30 s timeout.
  - A worker in an HTTPS read is not interruptible and has a 300 s read timeout (`HttpsGuestHttp.java:160`).
- **Concrete input:** a 300 s interval and a guest mid-download at 285 s. `awaitTermination` waits 20 s, and the pushes
  take 2 s, so `collect()` returns at about 307 s. The next cycle is delayed or skipped, and every key slips. DV-96's
  "the next cycle must start" is not guaranteed.
- **Fix:**
  1. Make the grace plus the cleanup wait fit inside the 60 s margin, minus a push budget (for example 25 s plus 10 s).
  2. Bound each transfer's read timeout by the VM's remaining stop time.
  3. Bound the SOAP `callMs` by the same.

**W11. Configuration warning events are re-sent every cycle, while DV-98 says re-sending storms.**
- **Where:** `CycleEngine.java:527`, `ConfigProblemEvents.of`.
- **The inconsistency:**
  - If the platform does not merge REST DIAGNOSTIC events by message, a missing list file makes 288 events a day on the
    vCenter object. That is the storm DV-98 was built to stop.
  - If it does merge them, DV-98 is unnecessary for Windows events and changes the visible lifecycle.
- **Fix:** let G5 decide both together. Add "count the configuration events after 3 collections" to the live proof
  (section 5 checks only that one appears).

**W12. Windows events are silently missed at scale.**
- **Where:** `GuestScripts.java:99` (a fixed 5-minute guest-side window) with the rotating cursor (DV-43).
- **Concrete input:** with 300 Windows VMs and about 30 reached per cycle, a VM is asked every 10 cycles (50 minutes)
  and only its last 5 minutes are looked at. 90% of matching events are never seen, and nothing says so. Upstream
  misses too, but serially and less.
- **Fix:** widen the window to the time since the VM was last asked, plus a margin, capped. The dedup of DV-98
  already handles overlap. Or state in `installing.md` and DV-43 that events of unasked cycles are not looked at, and
  count it.

**W13. DV-96 and DV-97 are registered inaccurately.**
- **DV-96:** says a VM "is stopped (program terminated, directory removed) at 45 s past it". A worker blocked in a SOAP
  call or an HTTPS read is not stopped by the interrupt. It runs on, on a daemon thread, for up to 300 s, and its
  cleanup happens then.
- **DV-97:** says "after three refusals ... no further VM is started", but up to 6 can be refused (B1).
- **Fix:** correct both rows when B1 and W10 are fixed.

**W14. Live-proof safety and gaps.** The additions are in "Live proof additions" below. The safety-critical ones:
- The wrong-password row (section 6) must use a **dedicated throwaway account** and record the domain lockout policy
  first. As written, run with a real domain account on the hardened DC, it can lock that account (B1).
- Count `4625` and `4771`/`4776`/`4740` on the domain controllers, not only on the guest.

## NIT

- **N1.** "No credential read when off" is true of the guest package. `VCommunityConfig` parses `winPass` at
  configure time regardless (upstream does too). Say "not used" rather than "not read" in the docs and claims.
- **N2.** DV-94 says skipped log names are "reported once". `CycleEngine.java:271-279` warns every cycle they persist.
- **N3.** `docs/p5-live-proof.md:68` says "`OS Release ID` may be absent ... (an empty value sends nothing)". Under Q4
  the empty cell is published as `""`, and the program always writes the column. Correct the expectation.
- **N4.** `GuestRunner.java:139`: when a leftover delete is refused fatally (a changed password), the later leftovers of
  that VM are not added to `leftBehind`. `remember()` then replaces the list, so they are forgotten.
- **N5.** `GuestTrust.forHost` reports a host missing from a failed host read as "vCenter records no usable
  certificate thumbprint". `GuestRoster.hostsRead()` is never consulted, so a read failure is told as a vCenter fact.
- **N6.** `GuestOpsClient.classify` matches `InvalidState` exactly. Its subclass `InvalidPowerState` (a VM powered off
  mid-run) falls to the step default (`F-GUEST-DIRECTORY` and so on), and the VM's remaining programs are still tried.
  Map `InvalidPowerState` to `TOOLS`, and `GuestComponentsOutOfDate` and `OperationDisabledByGuest` to `UNAVAILABLE`.
- **N7.** Items marked cut after the cleanup wait (`GuestPhase.java:214-218`) have their properties discarded but
  their events sent (`sendEvents` iterates `finished`, including cut items with an outcome). Pick one. If
  `awaitTermination` times out, those fields are also read without a happens-before.
- **N8.** DV-41's description of upstream ("CSV without `StartType` ... nothing published") is wrong: upstream's
  `Select-Object Name, DisplayName, Status, StartType` always creates the column, empty on an old PowerShell, so
  upstream publishes `""`. The port's program also always writes the column, so both publish `""`. The register text,
  and 8.8's "a column that is absent withholds the key", describe a path that never runs.
- **N9.** A blank display name: the port drops the row (`isSafeInstance` requires non-blank). Upstream publishes
  `Services:|Service Name`. This is unregistered. Register it under DV-99 or match it.

## Correctness and match

### Per-row match table (R72 to R80, events, configuration events)

Upstream reference:
- Rows: `properties/vm/vmOSInformation.py:46-51`, `vmService.py:63-65`, `events/vm/collect_windows_event_logs.py`.
- Scripts: `getWindowsOSInformation.ps1`, `getWindowsServices.ps1`, `getWindowsEventLogs.ps1`.

| Row | Port | Upstream 0.8.1 | Match? | Concrete difference |
|---|---|---|---|---|
| R72 OS Name | `$os.Caption`, ConvertTo-Csv, last valid row, `""` kept (Q4) | the same object and property, Export-Csv, last row | yes, value for value | an unsafe cell (control character, more than 512 characters) drops the row (DV-99) |
| R73 OS Version / R74 BuildNumber / R75 Architecture | as R72 | as R72 | yes | as R72 |
| R76 OS Last Boot Up Time | `DateTime` rendered by ConvertTo-Csv in the guest culture | the same (Export-Csv shares the converter) | yes | none found; L7 confirms on a localised guest |
| R77 OS Release ID | registry `ReleaseId`, `""` when absent (Q4) | the same | yes | live-proof text wrong (N3) |
| R78 Service Name | `Name`, instance `DisplayName` | the same | yes for an honest guest | `|`, a control character, more than 128 characters (instance) or more than 512 (value), or a blank display name drops the row (DV-99, N9). After B2's fix, unrequested rows are dropped (no change for an honest guest) |
| R79 Service Status | enum text `Running`, `Stopped` | the same | yes | as R78 |
| R80 Service Start Type | enum text; `""` on an old PowerShell or a refused getter (both swallow the getter error, reproduced) | the same | yes | DV-41 register text wrong (N8) |
| Windows event message | `[WindowsEvent-<LevelDisplayName> [Timestamp=<inv>] [Category=<configured log>] [EventID=<trimmed id>] [Source=..] [Message=..]]`, CRLF to LF, controls stripped, cut at 2,000 | `$firstEvent.LogName`, raw message | **no** | `Category` (W1, unregistered); CRLF and the cut (DV-99, W2); an id padded with whitespace is trimmed (`<EventID> 1000 </EventID>`: port `1000`, upstream ` 1000 `; edge) |
| Event severity | from the `Level` number (DV-42) | from the English `LevelDisplayName` | registered | a non-English guest: port correct, upstream INFO |
| Event cadence | once per record for 30 minutes (DV-98) | re-sent every cycle while in the window, platform auto-cancel | registered | G5 decides the visible lifecycle |
| Event target | the job's VM resource id | the VM object | yes | |
| Configuration events | `vCommunity configuration: <P2 sentence>`, WARNING, vCenter object, every cycle, only for files read | same prefix and severity; upstream's sentences; also for the two Windows lists while their switch is off | registered (4.10, DV-28) | text per P2's `CheckListStore`; W11 on the cadence |

Overall caveat for the differential: on Windows PowerShell 5.1, upstream's `Export-Csv -Encoding UTF8` writes a BOM.
If `requests` decodes the body as `utf_8` without stripping it, upstream's header first cell is `\ufeff"Name"`, and
OS, services *and* events all fail their column check. Upstream would then publish nothing on most guests (the
"Windows services empty" finding). DV-40 registers that the port publishes where upstream did not. A differential may
compare the port against nothing; G1 must record upstream's actual behaviour on the same guest.

### DV-94 to DV-102

| DV | Justified by | Registered accurately? | What a differential would show |
|---|---|---|---|
| DV-94 lines file instead of XML | security (no XML parser in the guest, names as data) | mostly. "Reported once" is per cycle (N2) | none for a normal list. W1's `Category` is **not** a consequence of DV-94, but an extra change |
| DV-95 refuse without a thumbprint | security | yes | a VM on a host vCenter has no thumbprint for: port nothing (`F-GUEST-TLS`), upstream keys (unchecked) |
| DV-96 deadline and hard stop | necessity (the 5-minute cycle) | **no** (W10, W13) | at scale fewer VMs per cycle than upstream's 1,800 s serial run |
| DV-97 pause after refusals | security | **no**, and it does not achieve its purpose (B1) | after a wrong password: keys stop for an hour; upstream keeps trying |
| DV-98 send once | necessity (REST events vs SDK events), pending G5 | yes | one event per record instead of a refreshed event; restart re-sends once |
| DV-99 bounds | security for the size, row, column and instance rules; **not** for CRLF and the 2,000 cut (W2) | yes, but over-broad | `|` names missing; long messages cut; CRLF becomes LF |
| DV-100 separate guest push | necessity | yes | the timing of the guest keys only |
| DV-101 tools-down counted | honesty | yes | health only (`PARTIAL` in most fleets) |
| DV-102 no script file, guest-named files, the header always written | security (TOCTOU, policy) and honesty | yes | none in values; keys where upstream was blocked by the execution policy |

No DV silently changes a published *property* value without a security reason. DV-99's event-text half does (W2). W1
changes event text with no DV at all.

### Honesty

| Failure | Published as | Verdict |
|---|---|---|
| Tools not running | nothing, `F-GUEST-TOOLS`, `PARTIAL` | honest |
| Account refused | nothing for that VM, `F-GUEST-AUTH` | honest (B1 is about lockout, not honesty) |
| Timeout | the program terminated, nothing published, `F-GUEST-TIMEOUT` | honest |
| Non-zero exit | nothing, `F-GUEST-EXIT` | honest |
| No output at all | `F-GUEST-PARSE` ("not even the header"), never "none found", because the output file exists before the run | honest |
| Malformed or oversized CSV | nothing, `F-GUEST-PARSE` / `TOOLARGE` / `HEADER` | honest |
| Truncated at a field boundary | **a false `""`** (W3) | gap |
| One event query refused | **"no events", status OK** (B3) | gap |
| A Get-Service lookup refused | "not found", no key | acceptable (no false value; SCM enumeration rights are broad) |
| Events in unasked cycles | not looked at, silently (W12) | gap at scale |

### Events

- **Dedup across cycles and overlapping windows.** The hash is the resource id plus the message, and the message
  carries the record's own `TimeCreated` at second resolution. So overlap is deduplicated and a recurring id with new
  records is sent each time. This is correct.
- **Clocks.** The guest window is guest-relative and the dedup uses guest text only, so collector and vCenter skew
  are irrelevant.
- **Time zones and DST.** The timestamp is guest-local with no zone (parity). In the repeated fall-back hour, two
  records an hour apart with the same text are both sent, because the 30-minute TTL has expired. `StartTime` in the
  ambiguous hour may shift the window (parity with upstream).
- **The 30-minute no-resend rule** is longer than the 5-minute window, so a record is never re-sent while visible.
  Upstream re-sends and lets the platform merge; G5 decides.
- **Storms.** 100 per VM per cycle; B2's fix tightens this to the query count. The 50,000-hash memory evicts the
  oldest. With a fleet producing more than 50,000 events in 30 minutes, eviction re-sends records still in view (edge).
- **Restart** re-sends the in-window records once (registered).
- **A failed push** remembers nothing, so the next cycle re-sends everything, including the part that may have landed
  (acceptable).

### Scripts: independently written?

Yes, in my judgement.

- **Structure differs entirely:** a shared prelude with `Save-Csv`, one outer `try` with `exit 0/2`, paths bound as
  literals, input from a lines file, and the header written when empty. Upstream has `param()` blocks,
  `return $output`, `Export-Csv` in the caller's command and an XML input.
- **No comments.** Different variable names: `$found`, `$name`, `$record`, `$since`, `$parts`, against upstream's
  `$output`, `$service`, `$events`, `$firstEvent`, `$startTime`.
- **Different messages:** `query failed:` against `Failed to read event details`.
- **What is shared** is dictated by the output contract or by Windows: the property names and their order (the key
  map's columns), the `Win32_OperatingSystem` and registry path, the `FilterHashtable` keys, `-MaxEvents 1` (reproduced
  behaviour), the `NoMatchingEventsFound*` error id (a Windows fact) and the event string format (the published value).
- **The Java side** carries no upstream comment phrasing. I checked 12 distinctive upstream phrases, with no hits in
  P5 files. `NOTICE` says so accurately.

## Live proof additions (`docs/p5-live-proof.md`)

The procedure is well built and mostly safe. Add or change the following.

1. **Section 0 / section 6 (safety).** The wrong-password test must use a dedicated test account on every guest,
   never the production DC account. Record the domain lockout threshold and window first. Count events on the DCs:
   `4625` on the guest, plus `4771`, `4776` and `4740` (lockout) on the DCs. Add rows with **one** and **two** Windows
   VMs (B1).
2. **Default-off proof in the guest.** With both switches Disabled, the guest Security log shows **no** `4624` or
   `4625` for the account over 3 collections. vCenter's task list alone does not prove the absence of a logon.
3. **UAC and non-admin account.** Use a non-RID-500 local admin, and separately a non-admin, with `Security/4625` in
   the list. Record whether the Security query fails and what the health says (B3).
4. **EDR.** On a guest with Defender for Endpoint, or the estate's EDR, record alerts and blocks over 3 collections
   (W8).
5. **Directory and file name forms.** Record the exact strings `CreateTemporaryDirectoryInGuest` and
   `CreateTemporaryFileInGuest` return (W9). Check the ACL of the created directory (`icacls`): Users must have no read
   and no list. Try, as a low-privileged user, to create a junction inside a live run's directory pointing at a test
   folder, and check whether `DeleteDirectoryInGuest` deletes the junction target's contents (L10).
6. **ScriptBlock and transcription logging** on the hardened DC: confirm event `4104` holds the program and no account
   or password.
7. **Long event message.** A message of 32 KB: does Ops accept it? Find Ops' maximum message length, to set the DV-99
   limit (W2). Check that the CLM `Out-File` fallback does not wrap or cut long lines (a CSV broken there is an honest
   `F-GUEST-PARSE`, but record it).
8. **vMotion.** vMotion VM A between the collection's host read and its guest step (a manual migrate as the cycle
   starts). Record the cause (W7).
9. **Configuration events cadence.** With a missing `windows_event_list.xml`, count the vCenter object's configuration
   events after 3 collections (W11, G5).
10. **Interval.** With a slow guest (CPU pressure) at the end of the cycle's time, record `Collection|cycle_seconds`
    and whether the next collection starts on time (W10).
11. **Upstream baseline for the differential.** On the same Windows Server 2019 VM, run upstream 0.8.1. Record whether
    it publishes any OS or service key at all (the BOM question in the match caveat), so DV-40's effect is measured
    rather than assumed.
12. **Timestamp culture.** On VM D (localised), compare the `[Timestamp=...]` text with `"$($e.TimeCreated)"` run in
    Windows PowerShell 5.1 on that guest.
13. **FIPS.** On a 9.1.1 FIPS collector: run section 1, then section 3, pinning positive (E15, W6).
14. **N3.** Fix the `OS Release ID` expectation.

## What only a live Windows guest (and platform) can settle

- **L1 (G1).** BOM presence; whether the .NET writer runs or the fallback does; whether upstream's `requests` keeps
  the BOM.
- **L2 (G2).** `-EncodedCommand` under GPO AllSigned or Restricted, and under AppLocker or WDAC CLM; whether the
  fallback writer runs under CLM; whether AppLocker exe rules deny `powershell.exe` to the account.
- **L3 (G3).** The `GuestProgramSpec.arguments` length limit (the longest is 4,867 characters).
- **L4 (G4, G7).** The transfer URL host form (name, IP, `*`), and reachability from a Cloud Proxy.
- **L5 (G5, G9).** The DIAGNOSTIC lifecycle with `managedExternally:false`; visibility on 8.18.7, 9.0.2 and 9.1.1;
  whether the platform merges identical messages (W11).
- **L6 (G6).** The numeric level on a localised guest.
- **L7.** The culture rendering of `LastBootUpTime` and `TimeCreated` in Windows PowerShell 5.1 (pwsh 7 verified only).
- **L8 (G8).** vCenter or Tools throttling at 4 parallel guests.
- **L9 (E15).** A SHA-1 `MessageDigest` under BC-FIPS approved mode on 9.1.1.
- **L10.** The ACL inherited by the Tools-created directory, and whether recursive `DeleteDirectoryInGuest` follows a
  user-planted junction.
- **L11.** How many failed logons a single `InvalidGuestLogin` costs (Tools may retry), so B1's arithmetic can be
  confirmed.
- **L12.** EDR reaction to the command line (W8).
- **L13.** UAC-filtered tokens through guest-operations logon (B3).
- **L14.** The exact path forms Tools returns (W9).

## Registry check (`knowledge/context/defects.md`)

None affect this pak: no open DEF has `Affects: vcommunity-java`. Registration candidates if they survive acceptance:
B1 (account lockout), B2 (key bloat from a guest) and B3 (silent event-query failure).
