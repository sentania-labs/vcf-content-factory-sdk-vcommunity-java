# Review: fix/unset-values (build 25, commit ebce4fa)

Base: `fix/upstream-bugs` at `1c40ec0` (build 24, reviewed, PR 4, out of scope). Static review only; no live
instance was touched. Reviewer: `sdk-adapter-reviewer`, 2026-11-16.

**Verdict: CHANGES REQUESTED.** 2 BLOCKING, 6 WARNING, 9 NIT.

The core change is sound. Every `Vals.prop` / `withhold` site I traced keeps a failed read apart from an unset
one (invariant a), nothing now dereferences a null the old placeholder used to absorb (d), no key changed type,
and the turnstile neither deadlocks nor weakens the lockout bound. The two BLOCKING items are placeholders the
branch leaves in place although the owner's rule covers them. The warnings are one more placeholder-like path,
one dropped returned value, the library lower bounds, and tests and docs that assert the rule without exercising it.

## Claims check

| Claim | Result |
|---|---|
| `ci/run_java_tests.sh` green | Confirmed 3 of 3 runs, exit 0. WireBodyTest skipped (no `VCFCF_WIRE_CP`); BcHandshakeTest passed |
| `validate-sdk` | `OK: ... valid Tier 2 SDK adapter project`. The 8.18.7 and 9.1.1.0100 compat checks were SKIPPED (jars not on this workstation); only 9.0 compat ran |
| pak-compare | `dist/...0.0.0.25.pak` against `0.0.0.24.pak`: 0 BLOCKING, 0 WARNING, 0 INFO. The 25 pak carries `GuestPhase$Turnstile` and the new `Quirks` (14:33, the commit time). I did not rebuild: build-sdk writes into the project tree |
| GuestEngineTest stable | 60 of 60 sequential runs passed, and 12 of 12 more with four copies running in parallel under load. No failure |
| 133 keys (VM 44, host 28, cluster 21, vCenter 11, Library Item 11, VM Template 7, Content Library 7, datastore 4) | The TSV counts match exactly. They come from a hand-written row list (`Coverage.unsetSendsNothing`), not from observed behaviour: see W4 |
| defect registry | `defect-gate --pak vcommunity-java`: no open blocking defects. No entry in `knowledge/context/defects.md` names `vcommunity-java` |

## BLOCKING

**B1. Q1: Tools Sync Time and Tools Upgrade Policy still publish the text `None` on every VM**
(`src/.../coll/VmCollector.java:184-187`, `text/Quirks.java:20-21`, `VmCollector.java:95-97`). This is a
placeholder for a value vCenter has and the adapter does not ask for: the owner's DV-112 rule, and
`knowledge/rules/no-fabricated-metrics.md`. The design itself records that upstream has fixed Q1 since
(design line 899). What has to change to bring it in line:
1. `Quirks.java:20-21`: delete `TOOLS_ALWAYS_NONE` and drop the Q1 mention in the class Javadoc (line 10).
2. `VmCollector.request` (`:95-97`): always add `TOOLS_PATHS` to the read.
3. `VmCollector.tools` (`:170-191`): drop both `TOOLS_ALWAYS_NONE` branches. Use `yv(...)` on `TOOLS_PATHS[0]`
   and `[1]` for every VM, so unset sends nothing and a failed read is withheld. `config.tools` stays a VM
   Options entry with its own `Options|config.tools` key. The Javadoc at `:47-51` and `:162-166` goes too.
4. Tests: `PropertiesTest.java:386-387` and `:467` (expect the fixture values, or `none` for a VM without tools
   configuration), and `:1008-1013` (the "edge of Q1" scenario becomes the ordinary case). The VM fixtures must
   serve `config.tools.syncTimeWithHost` / `toolsUpgradePolicy`. Add one test for a failed read of those paths.
5. Coverage: R105 and R106 go into `Coverage.unsetSendsNothing`, with notes. Regenerate `docs/p2-key-coverage.tsv`.
6. Docs: design §5.8 Q1 row (`:903`) and `:480`, the "Not changed: Q1" sentence in DV-112 (`:885`), the
   `overview.md:210` limitation, an `installing.md` upgrade note (the old `None` is overwritten by the real
   value, not left behind), and a CHANGELOG line.

**B2. Q10: Duplex publishes `half-duplex` for a NIC with no link speed, and for a link with no duplex flag**
(`coll/HostCollector.java:265-271`, `Quirks.DUPLEX_HALF_WHEN_DOWN`). With the link down, vCenter answers without
`linkSpeed`, so it says nothing about duplex. `half-duplex` is invented text, the same class as Q1 and the same
owner rule. DV-15 planned "nothing while down" (design `:787`). DV-112 says it "restores DV-08 to DV-18 as first
planned" but leaves DV-15 under Q10 (`:889`), which contradicts that. Fix: set `DUPLEX_HALF_WHEN_DOWN = false`
or delete it. Publish only `full-duplex` / `half-duplex` from a returned `linkSpeed.duplex`, and nothing when
`linkSpeed` or its flag is absent. Then update R39 in Coverage, the TSV, design Q10 and DV-15, and the overview
line "A NIC whose link is down is half-duplex". If the owner means to keep Q10, that has to be an explicit carve-out
recorded in DV-112. As written, the rule and the code disagree. (NIC Status `Disconnected` is different: an
absent `linkSpeed` is vCenter's documented signal that the link is down, so it is a reading.)

## WARNING

**W1. A Detailed Data field that vCenter returned empty is dropped, not sent as the empty text**
(`text/DetailedDataParser.java:36-40`). `distroName=''` is removed by `strippedIfTruthy`, while
`buildNumber='  '` is sent as `""`. That breaks invariant (b), and it is inconsistent with Customization Status
and the HBA fields, which now send a returned empty text. `strippedIfTruthy` was there to feed upstream's `"null"`.
The tests call the dropped value "unset", which it is not (`PropertiesTest.java:1576`, `:398` with fixture
`P2Fixtures.java:275` `distroAddlVersion=''`). Fix: use `Quirks.stripped` in the parser, so `''` becomes `""`
and the last occurrence still wins. Expect `""` in both tests.

**W2. EVC Enabled is published as `False` when the cluster summary is unset** (`coll/ClusterCollector.java:144-153`).
`summaryRead` is true for an unset `summary`, so a cluster that answered without any summary gets `False`. That
stands in for a reading that never happened. The `docs/p2-key-coverage.tsv` R144 note says "unset: nothing is
sent", which this contradicts. No test covers it: bare cluster `res-domain-c4` never asserts EVC Enabled.
Fix: `if (summary == null) { if (!summaryRead) o.withhold(2); return; }`. `False` stays only for a returned
summary that names no mode (Q2). Add `none(r, "res-domain-c4", "...EVC|Enabled")`.

**W3. The Content Library lower bounds are published as plain numbers, and their rationale is unproven**
(`coll/LibraryCollector.java:128-161`). With 0 < known < n, Items Not Stored Locally and Total Size (GB) are
partial sums with nothing to say so. A view that divides by Items, or sums sizes, under-reports without warning.
The design says the fields are "the normal answer for some item types" (DV-112), but E-L1 ("how often items omit
`cached` / `security_compliance`", design `:1631`) is still open, so that rests on no evidence. The edge cases are
right and tested: an empty library publishes 0, 0 and 0.0 (`lib-c`), and a library whose items lack all three
fields publishes none of them (`lib-d`). The mixed case, which is the new behaviour, has no test.
My judgement: the security-compliance count is acceptable as "not compliant among the items that carry a
verdict", since an item with no verdict cannot be non-compliant, but it should be documented that way. Not
stored and Total Size are misleading as partial figures. Fix: send `Items Not Stored Locally` and
`Total Size (GB)` only when `n == 0` or every listed item carries the field. Keep the compliance rule, with the
note reworded to "of the items with a compliance verdict" (Coverage3 R250-R252, overview, DV-112). Add a test of
a mixed library (some items with `cached` / `size` / `security_compliance`, some without).

**W4. The tests assert the rule from lists and fixtures; nothing sweeps the behaviour.**
- `PropertiesTest.SENTINELS` (`:621`) is dead code: nothing reads it. `noSentinels` (`:630-670`) checks
  instance names only, and its Javadoc (`:626-628`) still says placeholders are "the original's own text
  (Q9)". The cheapest proof of the owner's rule is missing: over the sparse and bare fixtures, assert that no
  pushed property value is in `SENTINELS` (or is `[]`), and that no unset stat is 0.
- `Coverage.unsetSendsNothing` (`tests/.../Coverage.java:114-119`) adds "unset: nothing is sent (DV-112)" to
  133 rows from a hand-written range, so the TSV states the behaviour without anything observing it. It is
  already wrong on R144 (W2) and R20 (an unset HBA device falls back to the key, and an unnameable adapter is
  withheld and counted, `HostCollector.java:210-216`). Of 17 rows sampled, 13 have no `none(...)` assertion on
  an unset fixture (Time Zone, Boot Time, Latency Sensitivity, Heartbeat Datastore, User Directory Timeout and
  others).
- `VmCollector.java:136-142`, Op Notification Timeout, has its own logic (unset sends nothing; a returned value
  that is not a whole number is withheld) and neither branch is tested.
Fix: add the value sweep. Derive the "unset" note from rows a test actually saw unset (record it in
`asserted`/`none`), or at least fix R20 and R144. Add the two Op Notification cases.

**W5. The turnstile has no direct test** (`guest/GuestPhase.java:278-295`, `:297-313`). It is the
account-lockout ordering, and the only evidence for it is the end-to-end GuestEngineTest. Add a unit test
with W > 1 workers and a gate stub. It should show that admission order equals list order under random
scheduling, that a turn ending early (deadline, interrupt) does not strand later turns, and that an interrupted
waiter leaves through `done`.

**W6. The design register contradicts the shipped behaviour in six places** (`docs/design/vcommunity-java-port-design.md`).
§4.11 (`:566-575`: "Superseded by Q8 and Q9 ... empty text ... 0"); §5.3 rows 4, 10 and 11 (`:711`, `:717`,
`:718`: "superseded by Q9: 0", "the empty text, as upstream"); DV-18 (`:790`: "library count withheld", which
is not the shipped lower-bound rule); DV-80 (`:853`: "a missing size as 0"); the row-count sentence (`:891`:
"DV-17 is superseded for text by quirk Q8", and the count does not include DV-109 to DV-113); and DV-112's
"restores DV-08 to DV-18 as first planned" (DV-15 is not restored, and DV-18 is implemented differently). Fix:
rewrite each to the DV-112 rule, or mark it "superseded by DV-112".

## NIT

- **N1. Stale comments:** `ClusterCollector.java:103` ("the original says null") and `:159` ("Q9");
  `PropertyCollection.java:470` and `:499` ("null"); the `OwnObject.java` class Javadoc ("the flagged ones are
  counted"); `PropertiesTest.java:305, 344, 363, 394, 454, 514, 540, 790` (Q9 / "the text the original prints");
  `OwnObjectsTest.java:260`, a scenario named "an absent text is the empty text", and the method name
  `absentTextOff` (`:522`). Reword each to DV-112.
- **N2.** `VmCollector.customization(String)` (`:220-223`) is dead and still uses the old falsy rule: delete it.
- **N3.** The CHANGELOG files the turnstile under `fix(test)`, but it changes adapter behaviour (admission order):
  file it under `fix(adapter)`.
- **N4.** The DV-113 upgrade note (`installing.md:315`) leaves out the Periodic Cluster Check. Its worst-5% risk
  and its closed-for-business flag are built on Cluster Risk Score samples (`PeriodicCheck.java:56`), so the
  pinned scores from before the upgrade colour one window. Add a sentence.
- **N5.** Q13 interplay: a `-1` "no sample" latency now publishes as `0.0` (`-0.001` rounded by `BigDecimal`),
  where it used to show the recognisable `-1`, so it cannot be told from esx01's real 0.0. Say so in Q13 or DV-113.
- **N6.** `Turnstile.done` (`GuestPhase.java:291-294`) can move `next` past a lower turn that is still inside
  `admit`, but only on the deadline and interrupt exits. That is safe because the gate is the bound and is
  needed for liveness. Say so in the comment, so nobody "fixes" it into a stall.
- **N7.** `docs/p2-key-coverage.tsv` R120 and R151 read "metric; metric;" (the base note repeats the prefix):
  drop "metric; " from those two base notes.
- **N8. Invariant (a), pre-existing:** `HostCollector.uplinks` (`:232`, `pnics == null`) and `ClusterCollector.dpm`
  (`dpm == null`) count no withheld value when the path read failed. The failure still reaches
  `Reads Failed in Last Cycle` through the read-level `:missing` accounting (`PropertyCollection.java:427-455`),
  so it is not silent, but `values_withheld` undercounts. Count it in a later build.
- **N9.** `overview.md` does not mention DV-113 or that the latency row is in microseconds. Add one line under
  the derived metrics.

## Invariants walked

**(a) A failed read stays a failure.** `Vals.prop` withholds on `!isUnset` (not asked, missing-set fault,
non-scalar). The bespoke sites (HA, DRS, EVC, DRS Score, DNS, install date, options, HBA list, PCI list, device
list, detailed data, customization, op timeout, vCenter settings and `about`, template paths, library item and
list failures) each withhold on the failed branch only. Path-level failures are also counted by `read()`. The
gaps found are N8, which is pre-existing.

**(b) A returned value is still sent.** HBA texts, customization, vCenter blank settings, patch level and library
texts all send a returned empty or blank text. A returned 0 (DRS Score with DRS on, effective hosts), `False`,
an empty HBA list and an empty SCSI list are all still sent. The exception is W1.

**(c) No key lost a real value or changed type.** All properties are still strings and all stats doubles.
Cluster `res-domain-c5` now gains Scale Descendants Shares, which vCenter returned. Template Memory that is not a
number is now withheld where it used to be `0.0`. NFS extents are no longer `0`: there is no list to count, so
this is consistent with the rule. `Quirks.text` is the identity while `BLANK_STRINGS_PUSHED` is true, so the
places that dropped it did not change any text.

**(d) Nothing new throws on null.** `PyText.json(null)`, `Vals.str/bool/integer(null, ...)`,
`searchDomain(null)` and `LicenseKeyMask.mask` all take null as before. `Quirks.stripped(v).replace` is only
reached with `v` non-null.

**(e) Library counts.** See W3.

**(f) Bare own objects.** A library, item or template always has a name and its identifiers. A nameless one is
not created and is counted as a failure (`LibraryCollector.java:66-71` and `:100-104`, TemplateCollector). Relationships are built from
identifiers only (`VCommunityAdapter.buildRelationships`). No shipped view, dashboard, report, super metric or
symptom references a changed key, and none filters on a placeholder text. A bare item or template is created
with no data and may show in Operations as not receiving data. That is cosmetic, and unlikely with a real
vCenter.

**(g) Q1.** See B1.

## DV-113

The only consumer of `mem:NVMe|latency.read_latest` is `ClusterRisk.hostValues` (`:64-67`). It divides by 1000
before `put`; the cluster value is the maximum over hosts, scored against `KpiTable.R_TIERING` (0, 150, 300,
450, 600 µs) and rounded at publish (61,692 ns gives 61.69). No other code, view or super metric reads the stat.
The `-1` filter (Q13, `OpsReads`) runs on the raw ns value, before the division, so flipping Q13 would still work.
The rollups use the score, not the row. Notes: N4, N5 and N9.

## Guest turnstile

- **Ordering, and no deadlock:** tasks go to the fixed pool in list order, and the pool takes them first in,
  first out. A task waiting in `await(k)` therefore waits only on lower turns that have already been taken
  and are running. Every path out of the admission `try` (an early return at the deadline, the interrupt check,
  `await` false, `InterruptedException`, a runtime exception, a normal admit) runs `done(order)` in `finally`.
  Queued tasks that `shutdownNow` drains all have higher turns than anything waiting, so dropping them strands
  nobody. `shutdownNow` interrupts the waiters, and `wait(20)` throws. No lock is held across `gate.admit`
  (only the Turnstile monitor while waiting), so the turnstile and the gate cannot deadlock on each other.
- **Serialisation:** only the `admit` call is serialised, and `done` runs before `start()`. The runs still
  overlap up to W. Admission was already throttled by the gate (`refusals + unresolved < T`, T = 2, one VM alone
  during the probe), so cycle time on a large fleet does not change measurably. The waiters wake on `notifyAll`
  and poll every 20 ms, which costs nothing.
- **Lockout bound:** `LogonGate` is still the only authority, and it is fully synchronised and independent of
  arrival order. The turnstile can only reduce how many tasks call `admit` at once. So the bound (at most T = 2
  refused logons per credential generation per 24 h, at most T + W - 1 = 5 when the credential changes
  mid-run) holds under every interleaving. What the turnstile adds is determinism: the probe is the first VM in
  cursor order, not whichever thread ran first.
- **Filtered VMs:** VMs with tools down, VMs left alone after a refusal, and VMs with no resource id are removed
  before `cursor.order` and never get a turn.
- **Cancellation:** an interrupted collector thread runs `shutdownNow` in `finally` and waits for cleanup. The
  workers leave through the paths above.

## Copied upstream prose

None found in the new text. The comments that quote Python (`str(x).strip() if x else None`) are describing
code, not copying it.

## Registry

No open defect in `knowledge/context/defects.md` names `vcommunity-java`. `defect-gate` agrees. If B1 and B2 are
carved out by an owner decision instead of being fixed, they are registration candidates (RULE-012).
