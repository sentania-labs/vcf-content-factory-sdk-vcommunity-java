# Changelog

Releases are `v<version>.<build_number>` tags built by this repository's CI. Development builds are stamped
`0.0.0.<build>` and are kept below, under "Development builds".

## 3.0.0.1 (preview)

The first public preview of VCF Content Factory vCommunity, a Java port of the vCommunity management pack. It descends
from Onur Yuzseven's vCommunity (the 1.x line) as continued by Ewan (e1-rahabok, 0.8.1), and is the 3.x line. It needs
VCF Operations 8.18 or later and was tested on 8.18.7, 9.0.2 and 9.1.1. Send feedback as GitHub issues on this repository.

**What stays the same as the Python pack 0.8.1**
- The metric and property key names (`vCommunity|...`) are the same, on the same vSphere objects, so existing
  dashboards, views, reports and super metrics that read them keep working (whether the history of a key continues across
  the move was not measured on an instance).
- The account fields, the six check-list files and the content (170 views, 52 dashboards, 16 reports, 3 super metrics,
  10 symptoms, 4 alerts with 1 recommendation) are the same, converted.
- All content is installed under this pak's own ids, so it never overwrites or deletes the Python pack's content or a
  built-in view. The adapter kind is its own (`vcfcf_vcommunity`): this is not an upgrade of the Python pack. Uninstall
  the Python pack first, then install this one (`docs/installing.md`).

**Where it deliberately behaves differently from the Python pack** (each is a numbered decision in the design,
section 5.7)
- **Network Speed Degradation works.** The Python pack read NIC speeds as statistics, which are properties, so it
  never published. This build reads them as properties, per NIC, for hosts that are connected. It needs the host
  property `net|configuredSpeed` enabled in the active policy, and only NICs with a fixed speed in vCenter carry it
  (DV-108).
- **Memory consumed on hosts with memory tiering** is measured against DRAM plus the NVMe tier, and so are the other
  memory rows of a tiered host, so it no longer passes 100 % (DV-109). Confirmed against the stock usage figure on
  9.1.1.
- **Worst ESXi Memory Tiering Latency is in microseconds** (the statistic is in nanoseconds), where the Python pack
  scored the raw number and pinned the row at the worst score (DV-113).
- **Host Operational Availability on 8.18** no longer caps a healthy host at 75: that version has no quarantine
  property, so the condition is left out there (DV-110).
- **DRS Score, Number of HBA and SCSI Controllers Count** send nothing when DRS is off or the list was not returned,
  instead of a 0 that reads as the worst score or an empty set (DV-111).
- **A value vCenter answered without is not published at all**: no `None`, `null`, `[]`, `N/A`, `Unknown`, empty text
  or 0 stands in for it, as the stock vSphere adapter does. A returned value, even 0 or `False`, is sent. A value that
  could not be read is counted in Collection Health instead (DV-112). This includes the Tools Sync Time and Tools
  Upgrade Policy keys (real values, not `None`) and the NIC Duplex key (only from a returned flag). Because a pushed
  key cannot be deleted, an old placeholder stays on an object until a real value replaces it.
- **ESXi Host License Expiring** covers every license that has an expiration date, not only one named license; its
  severity follows the license nearest to expiring (Q17).
- **The package views** (nenic and nfnic vendor and version distributions, VIB Info) read the keys the adapter
  publishes. They show data once `nenic`, `nfnic` and `nsx-host` are named in the packages list.
- **vSphere Clusters not Green** is a shipped super metric that counts clusters whose Cluster Performance Score is below
  75 (enable it in the policy on the vSphere World); two report dashboards chart it.
- **Two views were corrected**: VM Memory Limit charts the memory limit (it charted the CPU limit), and the namespace CSV
  view reads the namespace memory limit.
- **Guest logon lockout protection**: the guest checks (off by default) ask for at most two refused logons per
  credential in 24 hours and pause after that, so a wrong password cannot lock a domain account (DV-97).
- **The vCenter certificate is verified by default** (the Python pack's default sent the credentials before any check).
  A new account uses the collector's own trust with a name check: click Validate Connection, review and accept the
  certificate in the standard dialog, and it is checked from then on. One advanced setting, **Allow Insecure SSL** (default
  false, the same as the other VCF Content Factory paks), turns the checks off. The Python pack's Trust on First Use and
  Pinned modes and its thumbprint field are removed (DV-114). Guest monitoring does not run while Allow Insecure SSL is on.
  The `vCommunity|Certificate|...` keys are still published in both settings. Enter the vCenter by a name on its
  certificate: an IP address fails the name check unless the certificate lists it.
- **Collection Health keys**: the adapter reports its own health (`vCommunity|Collection Health|...`: per-collector
  status, causes, values withheld, warnings), and a failed read is withheld and counted, never turned into a value.

**Known gaps** are listed in `README.md` and `docs/overview.md`.

## Development builds

Before the first release the pak was built as development builds 1 to 26 (stamped `0.0.0.<build>`, under the version
`1.0.0` in `adapter.yaml`). Their entries follow, newest first.

## 1.0.0.26 (Unreleased)

- fix(adapter): review of build 25 (DV-112 follow-up). Tools Sync Time and Tools Upgrade Policy are the VM's real values on every VM, nothing when unset, counted when the read failed (Q1 removed). Duplex only from a returned flag (Q10 removed). Three more seams that invented a value for an input that is unset or does not apply are removed: a Datacenter count of 0 when no member host was measured (Q12), the platform's -1 "no sample" marker scored as a reading (Q13; a -1 memory tier latency is no reading), and a World count of 0 when no stored host value is fresh (Q16). EVC keys are not sent when the cluster has no summary; a Detailed Data field that came back empty is the empty text; Items Not Stored Locally and Total Size are sent only when every item carries the field, Items Not Security Compliant is "of the items with a compliance verdict"; a failed NIC-list or DPM-config read is counted as withheld. Unused code removed. Build 26.
- fix(test): the wrong-pin tests built a "wrong" pin by putting `00` in front of the real thumbprint, which equals it when the certificate of that run starts with `00` (1 run in 256); they now pick a prefix that differs.
- test: the unset rule is swept over the full and bare objects (no placeholder value pushed; the coverage notes come from what was observed), the turnstile has its own test, and the operation notification timeout, the Tools read failure and a mixed library are covered.
- docs: the design register, `overview.md` and the installing guide agree with DV-112 (upgrade notes for the Tools and Duplex keys and for the Periodic Cluster Check after DV-113).

## 1.0.0.25

- fix(adapter): a value vCenter answered without is not published at all, with no placeholder: no `None`, `null`, `[]`, `N/A`, `Unknown`, empty text or 0 (DV-112; 133 keys on 8 object kinds, listed in the coverage TSVs). The Q8 and Q9 seams and their helpers are deleted. A failed read is still withheld and counted; an unset value is not counted and raises no warning; a returned 0, `False`, empty list or empty text is still sent. Content Library counts of items not stored locally and not compliant count only the items that carry the flag, and Total Size adds only the items that carry a size. Placeholders sent earlier stay on existing objects until overwritten (installing guide, upgrade notes).
- fix(adapter): Worst ESXi Memory Tiering Latency is converted from nanoseconds to microseconds before it is scored and published; it was pinned at the worst score on every tiered host (DV-113). The published figure steps down by a factor of 1,000.
- docs: DV-109 records the live reconciliation of the memory divisor against the stock usage figure (within 0.006 points on tiered and non-tiered hosts); the missing input of Cluster Memory Tiering Consumption is listed as a known gap.
- fix(adapter): the guest phase hands the VMs of a batch to the logon gate in the cursor's order, so the VM that is asked alone first (the probe) is the first on the list and not whichever thread ran first; two guest tests that assumed vm-1 went first failed at random (`isolation`, and the 16 VM refusal count). Build 25.

## 1.0.0.24

- fix(adapter): whether the version has the quarantine property is decided from every host whose properties were read, not only the hosts whose statistics were read too (external review of PR 4): a statistics failure on the hosts that carry it no longer turns the others to the three-condition score. Statistics failures still withhold the affected hosts' own availability.
- review of builds 23 (DV-109 to DV-111): every memory row of a tiered host divides by DRAM plus the NVMe tier, not only consumed memory (ballooned, zip and swap, reservation, over-commit, monster VM; non-tiered hosts unchanged); whether the quarantine property exists is decided once per cycle from the whole answer, so a 9.x host with no value for it scores 75, not 100; the three keys that no longer send a 0 keep their last value in Operations after an upgrade (DV-111, installing guide); "vSphere Clusters not Green" is listed in `bundled_content` (3 super metrics, counts and enablement text corrected); docs corrected for the license alert, the unset-value text and the package views, which need `nenic`, `nfnic` and `nsx-host` named in the packages list (`nsx-host` added as a commented example); the super metric header and carried-text report corrected; recorded 8.18.7 and 9.0.2 host properties as fixtures; build 24.

## 1.0.0.23

- fix(adapter): three upstream defects the port carried (docs/reviews/upstream-bug-audit-2026-11-16.md): memory consumed on a memory-tiering host is divided by DRAM plus the NVMe tier, not DRAM alone, so it no longer passes 100 % (DV-109, Q18); host availability leaves out the quarantine condition when the host has no such property (8.18), so a healthy host is 100, not 75 (DV-110, Q19); no DRS Score while DRS is off or negative, and no HBA or SCSI Controllers count when the list was not returned, instead of a 0 (DV-111, Q9); build 23.
- content: the five package views (nenic and nfnic vendor and version distributions, VIB Info) read the keys the adapter publishes; VM Memory Limit charts the memory limit (it charted the CPU limit); the namespace limit column of the namespace CSV view reads `mem|limit`; the seven license symptoms of "ESXi Host License Expiring" read the generic instanced key, so the alert covers every license with an expiration date (Q17 fixed); the super metric "vSphere Clusters not Green" is shipped and charted by the dashboards "Report: Executive Summary (page 3)" and "Report: vSphere Compute Performance" (it counts clusters whose Cluster Performance Score is below 75; it computes only when enabled in the policy on the vSphere World).

## 1.0.0.22

- fix(adapter): review of the NIC speed change (DV-108): a Datacenter is never published as 0 when no host was measured and a NIC read failed (B1); the opt-in `net|configuredSpeed` property being off is no longer PARTIAL, a cause or a warning, only an INFO line on the first cycle and on change (`F-POLICY-NIC-CONFIGURED-SPEED`); only connected hosts count in the Datacenter and World counts; the unused instance-statistics reader is removed; tests for an unreadable NIC list (alone and with the stock policy), the 200-host heap run with NIC names, and recorded Suite API answers (`tests/fixtures/nic-properties/`); build 22.

## 1.0.0.21

- fix(adapter): Network Speed Degradation (and the Datacenter and World counts) read each NIC's `net:<nic>|speed` and `net:<nic>|configuredSpeed` as properties, by the NIC names vCenter gives, not as statistics (DV-108, Q15); build 21.

## 1.0.0.20
- docs(adapter): Scott Bowe is the MIT copyright holder in `NOTICE`, `docs/NOTICE.md` and `EULA.txt` (they said "the VCF Content Factory authors"); a "portions derived from" line and an Acknowledgments section credit the upstream authors by name and role; README gains Credits and a License line naming the holder; the decision is recorded in the design (10.1a). No code change.
- fix(adapter): the account lockout protection (DV-97) is one rule in one place, `LogonGate`, that every VM start goes through (pull request 2, external review finding on `GuestPhase`: in the first collection after a start or a pause the fan-out was not held to the refusal limit; 16 VMs, the first accepting and the others refusing, caused 4 refused logons in that collection and 5 more in the next, then no pause). A VM starts only while the refused logons of the last 24 hours plus the VMs whose first logon is unanswered stay below two, so a credential causes at most two refused logons in 24 hours on every path (probe, proving collection, later collections, after a pause, per run after a restart); the phase pauses at the second. The runner tells the gate of a logon when it happens, not when the VM ends. After a refusal no further call with the account is sent in that VM's run (the cleanup and the process polls were each another refused logon: up to four per VM). An accepted logon no longer wipes a refusal: two VMs refusing within 24 hours pause the phase. A credential changed while VMs are running costs at most five. Tests count refused logons at the fake guest over at least three collections for 1, 2 and 16 VMs, an account that worked then changed, restarts, a pause and a change mid-collection; `LogonGateTest` stresses the gate with random answers and threads. DV-97 and the installing guide restated with the bound per path.
- docs: the owner signed off the dead navigation links on Critical Business Applications, VM Capacity (new) and vSphere Cluster Performance 2.0 (2026-11-16, "All good."); recorded in `docs/design/inputs/dashboard_signoffs.tsv`, which the comparison generator reads. vSphere Cluster Capacity 2.0 (folder in the name) stays open.
- build: `0.0.0.20`.

## 1.0.0.19 (Unreleased)

- fix(adapter): the five summary reports (`voa-capacity`, `voa-configuration`, `voa-executive-summary`, `voa-inventory`, `voa-performance`) opt in to
  `feature_fallback: {font_color: omit}`: VCF Operations 8.18.7 rejected their templates on the cover page `FontColor` element (11 of 16 reports installed;
  measured 2026-11-15 UTC). Below 9.0.2 they ship without the cover page text color (cosmetic); 9.0.2 and 9.1.1 are unchanged. Design 10.1d, DV-107.
- docs: installing guide notes for 8.18.7 (cycle time, the `ide0:0.autodetect` check-list note).
- docs: licensing decided (owner, 2026-11-16): the whole pak, adapter code and all bundled content, is MIT. `NOTICE`, `docs/NOTICE.md` and `EULA.txt` drop the "terms pending" wording and the "no release tag until the owner confirms" gate, keep the acknowledgment of the upstream authors, the upstream license name and its Required Notice line. README, design and carried-text report updated. Documentation only; picked up by the next build.
- build: `0.0.0.19`; built with factory main `0a8a4b7a`.

## 1.0.0.18 (Unreleased)

- fix(adapter): the recommendation id is `Recommendation-df-VMWARE-ec5f14b5-b009-564e-8c31-66f205a839b1`; VCF Operations 9.0.2 refused the build 17 id at import
  ("It should start with Recommendation-df- or Recommendation-ud-"). Import report and id map regenerated.
- docs: uninstall then fresh install is the only supported way onto this pak from the Python pack and from dev builds up to 0.0.0.16 (different content
  ids); in-place upgrade across an id change is not supported (owner decision, design 10.1c; build 17 observation recorded there).
- build: `0.0.0.18`; built with factory main `7cd551ee` (content gate, one definition per alert id, minimum version 8.18.0).

## 1.0.0.17 (Unreleased)

Content under ids of our own (owner decisions, 2026-11-14; design 10.1, "Versioning").

- feat(adapter): all 253 content objects (170 views, 52 dashboards, 16 reports, 2 super metrics, 10 symptoms, 4 alerts, 1 recommendation) are re-converted
  with minted ids in the namespace `vcfcf_vcommunity` (deterministic; map in `docs/design/inputs/import-report/id_map.tsv`). Names and the metric and
  property keys are unchanged, so history continues. Built-in views referenced in place keep the product ids. Symptom ids no longer meet the
  tombstoned or purged ids of the Python packs (a deleted symptom id becomes unusable on an instance about 45 days after deletion).
- docs: "Moving from the Python pack" in the README and `docs/installing.md`: uninstall the Python pack first (Onur's 1.x or Ewan's 2.x), side by side is
  unsupported; what carries over; save check lists first; have a clean export of the built-in views at hand for the versions that ship built-in view ids.
  The statements that the content keeps the source pack's ids are removed; `NOTICE`, `docs/NOTICE.md`, `EULA.txt` say the ids are the pak's own.
- docs: the minimum product version is VCF Operations 8.18.x (owner decision); 8.18.7 stays as a tested version.
- feat(adapter): the two Section dashboards carry `section_fallback: omit`; the Collection Warnings recommendation text comes from `docs/design/inputs/recommendation_describe.tsv`.
- build: `0.0.0.17`.

## 1.0.0.16 (Unreleased)

Live install of build 15 (9.0.2, 9.1.1) and the factory pak-build fixes.

- fix(adapter): the upgrade statement for the six check-list files is the measured one (2026-11-14, 9.0.2 and 9.1.1): a pak upgrade
  replaces every file with a default name even without force content update, so operator edits to those files are lost; copy the file
  under another name, point the account's field at the copy (each field named in `docs/installing.md`) and back the files up. The six file
  headers, `docs/installing.md` and the design (10.4) say it; the earlier bytecode-derived statement is withdrawn.
- docs: `docs/installing.md` says the shipped (all-commented) lists leave `Guest|Tools Sync Time` and `Guest|Tools Upgrade Policy` at `None`
  (Q1) and that the super metrics and the Collection Warnings alert ship not enabled in the default policy, as in the source.
- test(adapter): `WireBodyTest` captures the request body of the real Suite API client for `Options|config.tools` and asserts the two spaces of
  `fault =  } }` in the bytes sent (design Q12): the adapter sends them, nothing between the formatter and the socket changes them. It needs the
  SDK, framework and log4j jars (`VCFCF_WIRE_CP`) and is skipped, with a notice, by `ci/run_java_tests.sh` without them.
- build: first pak built with the factory fix for empty localization values and for content ids defined twice (every symptom was in two files).

## 1.0.0.15 (Unreleased)

Review of build 14 (`docs/reviews/p6-content-review.md`).

- fix(adapter): B1 the six "Object Summary Page for ..." dashboards no longer carry `summary_for`; the pak has no
  `content/dashboards/dashboards.properties`, as the source pak; `docs/installing.md` says no Summary page is bound.
- fix(adapter): W1 the Collection Warnings recommendation names the Java adapter's keys and log (`First Warning`, `Collector:<name>|Causes`,
  `Collection|failure_causes`, `VCommunityAdapter_<id>.log`).
- fix(adapter): W2 the co-install and migration warning in `docs/installing.md` states the outcomes of shared ids, a safe order from the
  original pack, and that every statement is inferred from measured behavior of the source pack; W5 the real floor (8.18.7) is stated, the
  manifest says 8.0.0 (factory builder constant); W6 the license-name limit of "ESXi Host License Expiring" is documented (design Q17).
- fix(adapter): W3 the 101 views keep the ids of the corrected upstream build (pull request 92); the docs no longer say they are new ids
  (design DV-106). W7 the duplicate symptom id and the name kept are recorded in the design (10.1a).
- fix(adapter): W4 the pak's `eula.txt` is now `EULA.txt`: the notice (source name and version, PolyForm Noncommercial 1.0.0, terms URL,
  the source's Required Notice line, content terms pending the owner) followed by the MIT text; the same notice is in `NOTICE` and `docs/NOTICE.md`.
- fix(adapter): check-list file headers: the `$(` exclusion of service names, a restart forgets the last good event list, the 9.0.2 hedge on the
  forced-upgrade statement.
- docs: the dashboards comparison states the content floor honestly and where the note lives; the build is `0.0.0.15`.

## 1.0.0.14 (Unreleased)

Phase P6: content (design section 10).

- feat(adapter): the content ships in the pak: 170 views, 52 dashboards, 16 reports, 2 super metrics, 10 symptoms, 4 alerts and 1
  recommendation, converted from the source pack's content files by the factory's offline importer (`docs/design/inputs/import-report/`).
  101 views began as edited copies of built-in views and carry new descriptions (their ids are those of the corrected upstream build, pull request 92, kept as they are), nine of them retitled (`docs/design/inputs/view_rename.tsv`); 19 views that only
  renamed a built-in view are not shipped and are referenced by the built-in's id. Names are unprefixed.
- feat(adapter): `adapter.yaml` `bundled_content`; `content_foreign_adapter_kinds` is `[VMWARE]` (all 10 symptoms and 4 alerts are on VMWARE
  objects; Container, VMSP and APPLICATIONDISCOVERY are named only by views and dashboards, which the builder does not check against it).
- feat(adapter): "Overall Daily Check" and "vSphere Cluster Capacity 2.0" ship without their Section rows (`section_fallback: omit`) while the
  floor is below VCF Operations 9.1.1; the build prints `SECTIONS OMITTED`.
- feat(adapter): the six check-list files under `content/files/solutionconfig/` (`esxi_advanced_system_settings.xml`, `esxi_packages.xml`,
  `vm_advanced_parameters.xml`, `vm_options.xml`, `windows_service_list.xml`, `windows_event_list.xml`): own header text, every entry commented out,
  examples one per line. Test `CheckListTest.shipped` pins six files, zero active entries, no warning, and valid examples once switched on.
- docs: installing (Content, check-list files and what an upgrade does to them), overview, README, NOTICE (the content derives from the source
  pack's content files; its terms need the owner's confirmation), `docs/design/inputs/carried-text-report.md`.

## 1.0.0.13 (Unreleased)

Review of build 12 (`docs/reviews/p5-guest-review.md`) and the third live differential; design 8.9.

- fix(adapter): B1 the Windows account is protected from locking: refusals are counted across collections; one VM alone until a logon works, a
  cap of two refused logons, a 24 hour pause, a VM that refused is left alone for 24 hours, a restart is one VM alone (DV-97 rewritten).
- fix(adapter): B2 only the services in the list are published, one row a name; no more events than queries (DV-99).
- fix(adapter): B3 an event query that failed in the guest is `F-GUEST-QUERY` (the events found are still sent).
- fix(adapter): W1 `[Category=...]` is the record's own log name; W2 event text as written (CRLF kept, cut only at 32 KiB); W3 an output cut
  short is refused; W4 a fault text is scrubbed before it is cut; W6 SHA-1 checked once (`F-GUEST-TLS`); W7 a VM that moved is asked on its
  new host; W8 no `-ExecutionPolicy`; W9 the directory path is matched without regard to case; W10 calls and transfers are bounded by the VM's
  time, the grace is 20 s and the cleanup wait 10 s; W11 configuration warning events once; W12 the events window follows the time since the VM was last read.
- feat(adapter): W5 the guest phase does not run while vCenter TLS Validation is Trust on First Use (`F-GUEST-TLS-MODE`, Test Connection warns; DV-103,
  `GuestPlan.REQUIRE_CHECKED_VCENTER_TLS`).
- fix(adapter): N4 to N9 (leftovers kept after a refusal, a host read that did not finish, `InvalidPowerState` and two more guest faults, a cut VM
  loses its events too, the register text of DV-41, a blank display name).
- fix(adapter): L2 the Cluster Delta counts are not published twice when the read-back lags a push (DV-105). L1: the formatter of
  `Options|config.tools` already has the two spaces; the test now compares the Python dump's exact string, and the loss is after the push (design 5.8, note L1).
- docs: the open note about two powered-off VMs (L3) is removed; installing (guest monitoring, account lockout, security products), overview, live proof.

## 1.0.0.12 (Unreleased)

Phase P5: guest operations and events (design 8.8, `docs/p5-key-coverage.tsv`, `docs/p5-live-proof.md`).

- feat(adapter): guest monitoring for Windows VMs, off by default (Guest OS Service Monitoring, Windows Event Log
  Monitoring): the operating system (R72 to R77) and the listed services (R78 to R80) as properties, event log records
  as diagnostic events on the VM, through the vSphere guest operations; four VMs at a time, a rotating cursor, a hard stop
  45 s past the cycle's deadline, the directory in the guest removed on every path.
- feat(adapter): security of the guest phase: the Windows account only in the `auth` element of a vCenter request and
  scrubbed from every message; programs sent as `-EncodedCommand` with no secret; file transfer to the ESXi host over TLS
  pinned to the thumbprint vCenter recorded (SHA-1, SHA-256 or SHA-512; Pinned and Strict refuse a host with none, DV-95;
  Disabled is the only mode that accepts any certificate); output bounded and read as untrusted (DV-99); the account is not
  tried on every VM after three refusals, and the phase pauses for 12 collections (DV-97).
- feat(adapter): Windows events and the configuration warnings of an unusable check-list file go to Operations as DIAGNOSTIC
  events (`OpsApi.pushEvents`, T2); an event is sent once while it stays in view (DV-98); health `Collector:guest|*`
  and the self metrics `guest_vms_done` and `guest_vms_pending`.
- chore(adapter): `PinnedTrustManager` accepts a 40 digit (SHA-1) pin for the ESXi hosts; the vCenter modes are unchanged.
- test(adapter): `GuestOpsTest` (a fake vCenter guest operations service and ESXi host over real TLS: healthy, stopped,
  missing and malformed services, event windows empty, large, duplicated, non-ASCII and hostile, timeouts, tools down,
  refused account, wrong and missing certificates, cleanup after every failure and after a cancel, no secret anywhere),
  `GuestEngineTest`, `GuestUnitTest`; `docs/p5-key-coverage.tsv` for R72 to R80 with a staleness test.
- docs: design 8.8, DV-94 to DV-102, quirk Q4 extended to the guest output, `docs/p5-live-proof.md` (G1 to G9 on a real guest).
  DV-55 (blank once on switch-off) is not built: Q1 is still undecided.

## 1.0.0.11 (Unreleased)

Review of build 10 (`docs/reviews/p4-derived-review.md`) and the second live differential.

- fix(adapter): the pool counts (R218, R219) leave out Supervisor namespaces and everything under them: vCenter holds
  them as pools, Operations as `Namespace` objects (`ResourcePool.namespace` is read); a fixture with namespace pools.
- fix(adapter): comments rewritten in the port's own words and the derived helpers renamed from the design's vocabulary;
  the order of the arithmetic is unchanged (the 1,243 KPI checks and the baseline re-score are as before).
- fix(adapter): R211 counts as upstream does (0 while the answer names hosts, even with only stale values), as quirk Q16.
- fix(adapter): the statistics answers are read as a stream, in batches of 50 hosts, so the per-thread and per-NIC series
  the platform adds to every answer cost no heap (the heap test now carries them, in 48 MB).
- fix(adapter): a failed World read marks the cluster, host and VM lists for a new read; a VM whose host vCenter would not
  read is placed by Operations' host name; a failed fallback read withholds only the share that needed it; the relationship
  reads ask for 1000 per page.
- fix(adapter): the two warnings of the original (no NIC speed for any host, no availability data for any host) are logged,
  so Warnings in Last Cycle and First Warning agree with it.
- feat(adapter): with `config.tools` in the VM Options list, Tools Sync Time and Tools Upgrade Policy carry the real values
  (the edge of Q1); a list of datastores and the tools configuration are published as pyVmomi prints them (DV-93).
- fix(adapter): Check-List Entries Skipped counts the VM Options entries skipped now, every cycle.
- docs: DV-35, DV-87, DV-91, DV-93, Q1, Q15, Q16; the open difference L3 for two lab VMs (design 5.8).

## 1.0.0.10 (Unreleased)

Phase P4, the derived metrics (design 7, 4.3 to 4.9, 4.12; rows R70, R71, R153 to R219, R221 to R225).

- feat(adapter): the cluster, host and VM scores and their rows (Cluster Performance, Risk, Consumption, ESXi
  Performance, VM Performance, Guest OS Performance, VM Guest OS KPI), computed from what the VMware adapter
  already stores, read back once per data set in batches of 500; membership by MoRef (VM to host, host to
  cluster, host to Datacenter).
- feat(adapter): Operational Availability of hosts, clusters and the vCenter's worst cluster; Network Speed
  Degradation of hosts and the Datacenter counts; the share per vCPU and per MB of memory; Number of Port Groups
  and the resource pool counts from the vCenter's own lists.
- feat(adapter): the Periodic Cluster Check (worst values and Close for Business once per window) and the Cluster
  Delta counts, from the stored history; no state is held, so a restart or a second collector loses nothing and
  publishes nothing twice.
- feat(adapter): the vSphere World roll-ups (cluster summaries, availability, network, port groups, VMware Tools
  counts) and the Business Application figures (Tier, Business Application, Enterprise), read back from the
  previous cycle's stored values.
- fix(framework): a read-back that fails (HTTP, network, malformed answer, a sample that is not a number)
  withholds the figures that stood on it and is counted (`F-OPS-STATS-*`, `F-OPS-PROPS-*`, `F-OPS-HISTORY`,
  `F-OPS-REL`); a KPI is never scored over some of its rows; the deadline and an interrupt stop between batches.
- feat(adapter): the collectors `kpi`, `datacenter`, `world` and `business_applications` report their status,
  failures and causes in Collection Health.
- feat(adapter): quirks Q12 to Q15 registered with one seam each (`Quirks`); the map's Close for Business bands
  (20 and 40) corrected to the code's (25 and 50, M1).
- test: `KpiTest` (the worked examples of upstream and every limit pinned to its table), `OpsReadsTest`,
  `DerivedTest` through the engine with a vCenter and an Operations that make their answers, `DerivedHeapTest`
  (10,000 VMs in a 48 MB heap), `docs/p4-key-coverage.tsv` (74 rows, a value assertion each, stale-checked); the
  keytool password of the fake vCenter is hex (a URL-safe one could start with a dash and fail the run).

## 1.0.0.9 (Unreleased)

Review of builds 7 and 8 (`docs/reviews/p3-objects-review.md`).

- fix(adapter): a read that FAILED no longer gets a placeholder. The cluster DRS keys and DRS Score, the
  host ATS options, the install date and every template figure and text now tell a path that was read and
  is unset (the original's text) from one that vCenter refused or could not read (nothing sent, counted).
  A host that left between the host read and the option read sends no ATS value. A test per family.
- fix(adapter): a failed cycle fails every object of the account again, own objects included, so an
  unreachable vCenter sets the account DOWN as in P1.
- fix(adapter): `fetchSoftwarePackages` is lower case on the wire, like `installDate`; the ESXi package keys
  were never collected. The fixture refuses the capitalised names.
- fix(adapter): a refusal for lack of `Host.Config.Image` is one failed read per cycle naming the privilege.
- fix(adapter): a kind that had objects and reads as none is believed only after 12 collections in a row (a
  narrowed role must not delete history); an identifier listed twice counts twice (Q11); an item or library
  deleted between the list and its read is gone, not a failed read; no worker outlives the logout.
- fix(adapter): the DNS configuration without a dhcp flag is `None`; an object or list in a library answer
  prints as Python's repr.
- feat(adapter): `Collection Health|Collector:<name>|Causes` and `Collection|failure_causes` list the distinct
  failure causes with counts, so a run can say which reads failed from the health keys alone.
- docs: overview and installing rewritten for Q9/Q10, `Host.Config.Image` and the role guard; the design's
  superseded rows marked inline.
- test: the three timing candidates are polls or generous bounds.

## 1.0.0.8 (Unreleased)

Fixes from the first live differential of build 6 (P2 against the Python 0.8.1 baseline).

- fix(adapter) P2 live: the host license read asked the license manager for its `name`, which it does not have
  (it is not a managed entity), so vCenter refused it and no host had a license key;
  the read asks for `licenseAssignmentManager` only.
- fix(adapter) P2 live: the install date call is `installDate` on the wire, not `InstallDate`
  (vCenter refused it for every host).
- fix(adapter) P2 live: an unset value on a read that worked is published as the original prints
  it (quirk Q9): `None`, `null`, `[]`, `N/A`, 0 (VM, host, cluster, datastore, vCenter and the own kinds);
  a read that failed still sends nothing and is counted. One switch, `Quirks.UNSET_AS_UPSTREAM`.
- fix(adapter) P2 live: a NIC whose link is down is `half-duplex` (Q10); a datastore with no
  extents has `Number of Extents` 0; DRS Score is 0 while DRS is off or negative.

- fix(adapter): the own kinds follow the same rule: a figure that was read and is missing is 0 (Q9).

## 1.0.0.7 (Unreleased)

Phase P3, the pak's own objects (design 4.11, rows R227 to R252).

- feat(adapter): Content Library and Library Item objects, read from the vSphere
  Automation REST API (login, list, library, item list, item; logout in a
  `finally`) over the same TLS posture as the vim25 session, so the three vCenter
  TLS modes apply to it and a wrong pin sends no password. An item is a child of its
  library. A library or item that cannot be read is skipped and counted
  (`F-REST-LIST`, `F-REST-LIB`, `F-REST-ITEM`); its library's counts are not sent
  rather than counted over the items that answered.
- feat(adapter): VM Template objects, one per VM that vCenter marks as a template,
  from the VM read (no read of their own) with the datastore and folder names beside
  it. Identified by MoRef and vCenter UUID, so two templates of one name are two
  objects. Delivered whether or not the vCenter adapter also models the VM (DV-47).
- feat(adapter): the Content Libraries traversal spec (Content Library > Library Item).
- feat(adapter): an own object that vCenter no longer has is marked not existing by
  the platform after 12 complete cycles, and only for a kind that was read in full:
  a failed or partial read (a denied library, a failed item, the deadline, a VM with
  no `config.template`) proves nothing. Renamed objects take their new name.
- feat(adapter): the cycle now runs where the own objects are enumerated, at the top
  of the collection; the values of an object that is new start with the next cycle.
- fix(adapter): own objects reproduce upstream where it shows (Q8, one switch in
  `text/Quirks.java`): a text the answer does not have is the empty text; a figure it
  does not have is not sent as the zero upstream sends.
- test: `OwnObjectsTest` (libraries local, subscribed, empty, bare; items without files;
  templates in folders and with the same name; a denied library; failed items, list
  and login; kinds read in full; 2,620 items and 700 templates; the deadline; the TLS
  modes; the session), the describe test for the traversal spec, Python rounding and
  JSON text vectors, and `docs/p3-key-coverage.tsv`, generated from the key map with a
  value assertion for each of its 26 rows.

## 1.0.0.6 (Unreleased)

P2 review pass (`docs/reviews/p2-properties-review.md`).

- fix(adapter): match upstream 0.8.1 where it shows in the keys, for now: Tools Sync
  Time and Tools Upgrade Policy are the text `None`; a VM with no network adapter
  has no Virtual Disk keys; PCI ids are the signed form (`-7f7a` for 0x8086);
  blank strings are pushed as upstream pushes them, not withheld; a negative
  operation notification timeout is `0`. All behind switches in `text/Quirks.java`,
  listed in design section 5.8, with the key map notes corrected.
- fix(adapter): a VM Options entry is read as a value of one element, so one that is
  an object, a snapshot tree or a whole config cannot fail the VM page; one found
  not to be a single value is dropped from later reads; if the page fails anyway
  with entries in it, the VMs are read again without them. References of known
  types print as pyVmomi prints them, bytes as `b'...'`.
- fix(adapter): a read stops paging at the deadline and releases its server-side
  result set and view (`CancelRetrievePropertiesEx`), the probe's and a failed
  page's included; per-host calls stop too.
- fix(adapter): a `missingSet` fault is a failed read, not an absent value; a read
  in which every object threw is FAILED and its values counted; a collector
  fault is `F-COLLECT-<read>`.
- fix(adapter): a new object is no longer keyless for up to 12 cycles: the Ops list
  is read again for it at once, then after 2, 4, 8 cycles.
- fix(adapter): the pushed-value table takes 19 MB at 10,000 VMs (was 87 MB); a
  heap-capped test runs it. License assignments fall back to one call per host
  when the one call fails.
- fix(adapter): a 404 on the check-list JSON look no longer makes a file absent or
  clears the list; only its apiErrorCode is used; markup is never quoted.
- fix(adapter): a host that left between the two host reads no longer loses every
  host's options; package and license text is as sent; an EVC mode of spaces is a
  mode; the VM Options probe runs again after a new login.
- docs: design updated (4.5, 5.1, 5.5, register DV-68 to DV-77, new section 5.8);
  the coverage table now needs a test that compared each key's value.

## 1.0.0.5 (Unreleased)

Phase P2, the property keys.

- feat(adapter): clusters, hosts, virtual machines, datastores and the vCenter
  object get their `vCommunity|` property keys (146 rows of the key map; the
  count per row is in `docs/p2-key-coverage.tsv`, generated from the key map and
  checked against the tests). Each cycle reads the objects of a type through the
  property collector in pages, as a stream: one object is held at a time, and
  of a large value only what a key uses is kept. A page that fails fails its
  whole type (nothing from it is sent); an object with no unique Ops resource
  gets no keys and is counted; a failed read, a refused property path and a
  value that is not there are each withheld and counted, never sent as `None`,
  `null`, `Unknown`, `N/A`, `0` or `False`.
- feat(adapter): host license keys are masked to their last group; the full key
  is never pushed or logged (DV-25). PCI ids are four hex digits of the unsigned
  value (DV-33). A VM Options entry vCenter refuses is dropped and reported and
  costs only its own key (DV-26); a value that is an object or a list is skipped
  and reported once.
- feat(adapter): the certificate status (`Recorded`, `Unchanged`, `Changed`,
  previous thumbprint, last changed) is kept in memory and read back from the
  vCenter object once after a start; a failed read-back or probe sends no status
  and never records over what is there (DV-23).
- feat(adapter): `Maintenance Mode Required` is pushed as a numeric property
  (the framework's typed push, 1.0.27).
- feat(adapter): properties are sent when they change, after a start and in a
  full refresh every 12th cycle; numbers go every cycle (DV-49).
- fix(adapter): an absent check-list file is absent on 8.18.7 as on 9.x (the XML
  error there carries no code the framework reads; a refusal with none is asked
  about once more as JSON), and the `it said:` text is the server's message, never
  markup. An unexpected failure on one object is counted for that object and does
  not fail its type. A full refresh forgets pushed-value hashes of objects that
  are gone.
- test: fixture tests through the engine, the real vim25 client and a local
  server that answers hand-built SOAP: paged and large inventories (heap shape
  checked), optional parts missing, empty values, refused paths, failed pages,
  per-host call failures, the deadline, matching by UUID and MoRef.

## 1.0.0.4 (Unreleased)

- fix(adapter): a logout the vCenter refuses is no longer logged as "logged out":
  a refusal says `vCenter logout did not complete (...)`, and a session that had
  already ended says so (review W1).
- fix(adapter): Test Connection treats only the known no-credential failure of the
  Suite API client as a plain "not checked" line; any other reason the client
  cannot be built is a WARN naming the exception class, and the class is in the
  plain line too (W2).
- test: `BcHandshakeTest` runs real handshakes under BouncyCastle JSSE (jars
  fetched from Maven Central, SHA-256 pinned, test only, same as the compliance
  adapter): a wrong pin and an untrusted chain, through the adapter's own client
  (W3). The provider throws `TlsFatalAlert`, an IOException, with the pin
  mismatch as its direct cause.
- fix(adapter): the per-thread pin record is always taken, never left behind,
  and a stale one is not reported on a later failure (N1); an expired, not yet
  valid or revoked certificate no longer gets the "accept the certificate"
  advice (N2); server and provider text in the cycle summary and in TLS
  failures is one bounded line (N3); the test seam `VcenterTls.forTesting` refuses
  Enabled (N4).
- docs: wording for the TLS failures, the mid-cycle re-login (P2), and deviation
  DV-67 (Test Connection on an unsaved account reads no check-list files) (N5, N6).

## 1.0.0.3 (Unreleased)

- fix(adapter): the session is visible in the account log at INFO, one line each:
  login (vCenter product and version, new session), reuse each cycle, renewal
  when the held session expired, logout with its reason (`reconfigure` or
  `stop`); and one cycle summary line (duration, vCenter object matched, objects
  not matched, series and properties pushed, reads failed, values withheld,
  warnings). Before, only the expiry line existed, so a live run could not show a
  session being opened, reused or ended.
- fix(adapter): a pin mismatch keeps its message under FIPS. The provider's alert
  is an IOException that is not an SSLException, so the cause walk was skipped;
  the walk now covers every IOException, causes and suppressed, and the pinned
  trust manager records its decision per thread as a last resort. Other TLS
  failures name the alert and the mode; an untrusted chain in Enabled says to
  accept the certificate in Validate Connection or use Pinned.
- fix(adapter): Test Connection logs success at INFO only (never WARN); each thing
  needing attention is its own WARN line. A Suite API client that cannot be built
  during a test is a plain "not checked during a test" line, not a warning.
- fix(adapter): a failed check-list read shows the answer's own words (bounded),
  so an HTTP 400 with no apiErrorCode (seen on 8.18.7) can be told from a missing
  file next time; it is still a failed read, not an absent file.
- docs: log lines, Test Connection logging, creating an account through the API.

## 1.0.0.2 (Unreleased)

- fix(adapter): the session cookie is found whatever the case of the
  `Set-Cookie` header (a proxy in front of the vCenter may send it lower case);
  before, every cycle failed and left an authenticated session behind.
- fix(adapter): a count that was not measured is not sent: check-list entries
  skipped (unless every file read was parsed), objects not matched (unless the
  vCenter object lookup completed), on the adapter instance and on the vCenter
  object.
- fix(adapter): the period choices carry their labels (`01 hour` ... `24 hours`),
  every account field has a description, and the Trust on First Use text says
  plainly that credentials are sent before any certificate check; enums carry no
  `displayOrder` (not in the schema).
- fix(adapter): SOAP answers built into a tree are capped at 1 MiB and 32
  levels; redirects are not followed; a page of resources with no total is not
  taken for the whole list; a Unicode host is converted to punycode for the name
  check.
- fix(adapter): a skipped check-list entry is a reported problem, not a failed
  read; a file name is refused only when it is a path or a query; no runtime
  exception in the vCenter object lookup can end the cycle; a clean Test
  Connection logs at INFO.
- test: lower case headers, the real certificate name check in Enabled mode,
  oversized and deep answers, every unmeasured-count path.
- docs: the design copy is corrected (own-kind units, self-metric transport) and
  deviations DV-61 to DV-66 are registered; `docs/reviews/p1-skeleton-review.md`.

## 1.0.0.1

- feat(adapter): phase 1, skeleton and configuration parity. The adapter kind
  is `vcfcf_vcommunity`. describe.xml declares the 19 account fields and the
  `vsphere_user` credential of the original pack (keys, labels, defaults,
  choices, main form versus Advanced Settings), the `vCommunityCollector`
  object, the three own kinds with their 26 keys, and the `Collection|` self
  metrics on the adapter instance.
- feat(adapter): `VCommunityConfig` with the original pack's fallbacks and one
  warning per field; failures name the field and quote no secret.
- feat(adapter): the three vCenter TLS modes (trust on first use, platform
  trust with the certificate name checked, pinned SHA-256 or SHA-512), the
  certificate thumbprint probe, and a raw SOAP vim25 session (no JAX-WS)
  with keepalive, one relogin on a lapsed session, and one login attempt per
  cycle after a failure.
- feat(adapter): the collection cycle skeleton: session, probe, check-list
  files, the vCenter's own object matched by instance UUID, and the
  `vCommunity|Collection Health|` keys and `Collection|` self metrics. A failed
  read sends nothing and is counted; no value is ever sent that was not read.
- feat(adapter): Test Connection reports the login, the certificate
  thumbprint, whether the Suite API answers, and each check-list file's outcome
  and item count (in the adapter log).
- feat(adapter): check-list files: secure parse, per-list validation (VM option
  paths, Windows service names, the event list structure), last good list kept
  on a read or parse failure, an absent file empties its list.
- test: plain-main tests for every class outside the two SDK-facing ones,
  including the TLS modes and the session against a local TLS server with a
  certificate made at run time, and describe.xml pinned to the original pack.
- docs: README, overview, installing guide, reference and NOTICE (placeholder
  wording; the final text waits on the licensing record).
