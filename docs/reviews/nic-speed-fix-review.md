# Review: Network Speed Degradation read as properties (DV-108)

- Commit: `0817e05` on `fix/nic-speed-degradation`, diffed against `main` (`c2d6e85`)
- Build: 21 (`adapter.yaml`)
- Reviewer: `sdk-adapter-reviewer`, static, read-only, no live instance touched
- Date: 2026-11-16

## Verdict

**CHANGES REQUESTED.** 1 BLOCKING, 6 WARNING, 5 NIT.

The property read itself is sound: the read is per batch, a failed batch is counted, a NIC with no usable pair
gives no value, and `"0.0"` (a link that is down, seen on devel) is left out. One path still produces a made-up
number. When the stock policy leaves `net|configuredSpeed` off, and any one host's NIC list or NIC read fails,
every other Datacenter publishes `Total Network Speed Degradation = 0`. That is "no degraded hosts" when no
host was measured, and in the same case the configured-speed warning and the PARTIAL disappear. A scratch
probe against this branch reproduced it (below).

## Claims check

| Claim | Result |
|---|---|
| `ci/run_java_tests.sh` passes | Confirmed. Exit 0, every suite ok, `KpiTest: 1258 checks passed`. WireBodyTest skipped (no `VCFCF_WIRE_CP`), as on main. |
| `validate-sdk` clean | Confirmed. `OK: ... valid Tier 2 SDK adapter project`. The 8.18.7 and 9.1.1 SDK compat checks were skipped because the jars are not on this machine; this is the same on main. |
| Published keys and formula unchanged | Confirmed. `HOST_NETWORK_SPEED` and `DC_NETWORK_SPEED` are untouched. `NetworkSpeed.degradation` is unchanged, and `parseSpeed` drops values of zero or below before it runs, which is what the formula's both-above-zero rule already did. |
| No usable pair gives no value, never a made-up number | **Refuted for the Datacenter count** (B1). It holds for the host value. |
| "Not collected" shows once per cycle and as PARTIAL | Confirmed for the plain case (one warning; `Collector:kpi|Status` PARTIAL). In that state `Causes` reads "None in the last cycle" (W4). If any host NIC read fails in the same cycle, both are lost (B1). |
| `build-sdk` / `pak-compare` | Not re-run. The brief did not ask for them, and the commit touches no describe, content or packaging. Re-run them before the tag. |

Registry (`knowledge/context/defects.md`): no open defect names `vcommunity-java`. Nothing to re-assert, and no
closure to propose.

## Probe evidence

These are scratch classes under the session scratchpad, compiled with `-sourcepath src:tests` against this
branch. They reuse `DerivedTest.Lab`. No adapter file was edited.

| Scenario | Result |
|---|---|
| P1: `configuredSpeed` removed on all hosts, `host-1` `config.network.pnic` missing (NoPermission), `host-4` has `net:vmnic0|speed` | `DC2 Total NSD = 0.0`; `kpi` status **OK**; no configured-speed warning (First Warning is the R-HO read) |
| P2: `configuredSpeed` removed on all hosts, nothing else wrong | `kpi` PARTIAL, `Causes = None in the last cycle`, `First Failure = None`, DC2 nothing |
| P3: only `host-1` NIC list unreadable | H1 and DC1 withheld; `F-SOAP-R-HO` counted on the host collector; `kpi` OK |
| Q: cluster list refused, configured speed present / removed | `kpi` PARTIAL / **FAILED** (host availability still published in both) |

## Findings

### BLOCKING

**B1. Datacenter count published as 0 when nothing was measured.**
`src/com/vcfcf/adapters/vcommunity/coll/ObjectKpis.java:397` (consumed at `:678` and `:694-707`).
Authority: skill, *Unreadable is NOT compliant*; `knowledge/rules/no-fabricated-metrics.md`.
`networkNoData` requires `!nicFailed`. If any host has `nicFailed` (its NIC list is unreadable, which this
commit adds as a new trigger at `:343-345`, or its batch failed at `:360`), the "nothing anywhere" case is
skipped even though `anyPair` is false. Every host then gets `speed = null`, and each Datacenter without the
failed host falls into Q12 (`EMPTY_DATACENTER_COUNT_IS_ZERO`) and publishes `0`. The `!anyConfigured` branch
also never runs, so the operator gets neither the warning nor the PARTIAL. With the stock policy this needs
only one host with a NIC read problem (probe P1).
**Fix:** decide "nothing anywhere" on `!anyPair` alone: drop `&& !nicFailed`. Keep the per-host withholding for
failed hosts, and still raise the configured-speed gap when `anyActual && !anyConfigured`. Add a test with P1's
shape.

### WARNING

**W1. Whether `properties/latest/query` answers an instanced key is unproven, and the tests cannot tell.**
`ObjectKpis.java:349-355`, `src/com/vcfcf/adapters/vcommunity/ops/OpsReads.java:468`,
`tests/com/vcfcf/adapters/vcommunity/P4Data.java:199`, `tests/com/vcfcf/adapters/vcommunity/kpi/KpiTest.java:741-742`.
Authority: reviewer doctrine (skeptic default); skill, *Gaps*.
What the evidence shows:
- The devel harvest (`/mnt/holding/.../audit/harvest_devel.json`, taken with `GET /api/resources/{id}/properties`)
  holds `net:vmnic0|speed` as a property, with values `"1000.0"`, `"10000.0"` and `"0.0"`. So the key exists
  in this form and the unit is Mbps text.
- No recorded `POST /api/resources/properties/latest/query` payload with an instanced key exists in the repo,
  in the validation dumps, or in `knowledge/context/api-surface/suite_api_batch_push.md`.
- The spec has an `instanced` flag ("forcibly treated as instanced properties"). The adapter omits it; the one
  recorded call sends `false`.
- The fake echoes any key exactly, and `KpiTest` checks `speedKey()` against its own format. Any key form
  passes the tests.

If the form is wrong, devel (where `net|speed` is collected) logs "No NIC speeds for any host": the same as the
broken state before this commit, with no PARTIAL.
**Fix:** before the tag, send one read-only `properties/latest/query` with `net:vmnic0|speed` to devel and
devel_8_18. Record the answer as an `OpsReadsTest` fixture.

**W2. `configuredSpeed` has never been seen: its unit and auto-negotiated NICs are unproven.**
`ObjectKpis.java:401-407`; `docs/installing.md:88-98`. Authority: `knowledge/rules/no-fabricated-metrics.md`;
skill, *Gaps*.
- Both keys have `unit: null` in `catalog_devel.json` and `catalog_devel_8_18.json`.
- `configuredSpeed` is `dm:false` and has no sample in any dump.
- vSphere leaves `PhysicalNic.spec.linkSpeed` unset when a NIC auto-negotiates. If the VMware adapter fills
  `configuredSpeed` from that field, most NICs will have no value even with the property on.

In that case the warning and the installing guide send operators to enable a property they already enabled,
and PARTIAL stays.
**Fix:** enable the property for one devel host and capture real values (unit, auto-negotiated NICs) before
the tag. Until then, word the warning and the guide as "no host reports it", and name auto-negotiation as a
possible reason.

**W3. The new gap turns `kpi` FAILED after a cluster list failure.**
`src/com/vcfcf/adapters/vcommunity/coll/DerivedPhase.java:127-129`, `ObjectKpis.java:403`. Authority: dimension 3
(failure granularity); design 5.2 status meanings.
`gaps >= 3` means "none of the three lists was read". A cluster list failure already adds two gaps
(ObjectKpis plus PeriodicStage). With the stock policy the NIC gap makes three, so `kpi` reports FAILED while
the host and VM figures are published (probe Q).
**Fix:** count the policy gap apart from the list gaps, so it can only add PARTIAL.

**W4. PARTIAL without a cause, in every stock install.**
`ObjectKpis.java:403-404`. Authority: dimension 6 (an operator must be able to tell "couldn't read" from
anything else).
With the default policy, every install shows `kpi` PARTIAL with `Causes = None in the last cycle` on every
cycle. On top of that:
- Values Withheld never reaches 0.
- First Warning often holds this line instead of a later, real warning.
- A genuine partial failure of `kpi` reads the same as this one.

There is precedent for a configuration-based PARTIAL (DV-101), but that one names a cause.
**Fix:** register a cause id (for example `F-POLICY-NIC-CONFIGURED-SPEED`) so `Causes` names it, and assert it
in `DerivedTest`. Whether PARTIAL is right at all for an opt-in figure is an owner decision; record it in DV-108.

**W5. The path where the NIC list cannot be read has no test.**
`src/com/vcfcf/adapters/vcommunity/coll/Membership.java:154-162`, `ObjectKpis.java:342-345`. Authority:
dimension 9 (prove behaviour, do not assert it).
Probe P3 shows the path behaves on its own: host withheld, Datacenter withheld, read counted on the host
collector. Combined with the stock policy it gives B1. **Fix:** add the P3 and P1 scenarios to `DerivedTest`.

**W6. A design row contradicts DV-108.**
`docs/design/vcommunity-java-port-design.md:1001` still says "per-NIC instance stats ... (latest-stats request
asking for instances)". Authority: dimension 11. **Fix:** say "host properties `net:<nic>|speed` /
`net:<nic>|configuredSpeed`, by `config.network.pnic` device names (DV-108)". For the orchestrator: the
factory's `knowledge/context/api-maps/vcommunity-e1-collection-map.md:511` says the same.

### NIT

- **N1** `OpsReads.java:388-416`: `latestInstanceStats`, `InstanceSink` and `splitInstance` no longer have a
  production caller. Remove them, or note that they are kept on purpose.
- **N2** `Membership.java:168-170`: the 5-argument `addHost` defaults NICs to "read, none". As a result
  `DerivedHeapTest` (200 hosts) never sends the NIC request, so its scale is untested. My analysis says it is
  bounded: at most 500 ids per request, keys = 2 x distinct NIC names across the fleet, typically 2 to 4
  requests for 1,000 hosts. Give the heap test NIC names.
- **N3** `CHANGELOG.md:8`: the build 21 entry sits under `## 1.0.0.20`. The file says one entry per build, so
  add a `## 1.0.0.21 (Unreleased)` heading.
- **N4** design `:881` (DV-108) says "8.18.7, 9.0.2 and 9.1.1 (describe cache)". The cache's sources are devel
  (9.0.2) and 8.18 only, and there is no 9.1 catalog in the dumps. Drop 9.1.1 or cite a source for it.
- **N5** Properties are stored only on change and have no age limit; the statistic read they replace had
  `STALE_MS`. A host that is `notResponding` but still listed in vCenter keeps its frozen speeds in the
  Datacenter count. Either count only connected hosts in `datacenters()`, or note this in DV-108.

## Checked and found sound

- **Units.** `net|speed` is Mbps text (`"1000.0"`) on devel. `parseSpeed` accepts it and drops `"0.0"`. The
  upstream super metric divides the two keys directly. Unit equality for `configuredSpeed` is open (W2).
- **Batching.** One request per 500 hosts, only hosts with a non-empty NIC list, keys de-duplicated, bodies a
  few tens of KB. A failed batch marks only its own ids. A deadline stop is counted.
- **Other collectors.** `Membership.Host` gains a field, and its constructor is package-private and called only
  from `Membership`. `HostCollector` already requests `config.network.pnic` with `device` kept, so no new vCenter
  path. `portGroupCount`, `GuestRoster` and the cluster code do not read `nics`. Removing `NIC_STATS` compiles
  clean. Its only other user, `OpsReadsTest`, was adjusted without a change in meaning.
- **NIC names.** vCenter's `PhysicalNic.device` (`vmnic0`) matches the VMware adapter's instance names in the
  harvest. The NIC read sends only the names vCenter lists, so NICs that were removed but still hold old
  property values are ignored.
- **Logging.** No secrets. One warning per cycle, not one per NIC. The failure text names the read.
- **Prose.** The only upstream string is "No NIC speeds for any host", which predates this commit and is kept
  on purpose (L6). The new warning, DV-108 and the installing section are original.
- **Docs parity.** The README, `installing.md`, DV-108, the Q15 note, the coverage TSV and `Coverage4` agree
  (apart from W6). The README and inventory version moved to 0.0.0.21.
