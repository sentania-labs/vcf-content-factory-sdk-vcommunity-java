# Review: fix/install-guide-and-enum-defaults (ce4049b) against fix/tls-verify-by-default (ef2df8b)

Reviewer: sdk-adapter-reviewer, 2026-11-16. Static, read-only, one round. Build reviewed: 28 (3.0.0.28).

**Verdict: CHANGES REQUESTED.** 2 BLOCKING, 9 WARNING, 10 NIT.

## Independent checks

- `ci/run_java_tests.sh`: exit 0. `config unset values`, `config whole numbers`, `describe: defaults and choices equal
  the config class` all pass. WireBodyTest skipped (VCFCF_WIRE_CP unset), as before.
- `validate-sdk <worktree>`: OK. The 8.18.7 and 9.1.1.0100 SDK compat checks were skipped (no jars on this machine),
  same as the base. validate-sdk does not check describe.xml against the XSD.
- Describe form: the vendor XSD (`describeSchema.xsd`, `enumType`) declares `default` as optional `xs:boolean`,
  documented as "If true the enumeration entry is selected by default". The vendor guide
  `docs/guides/adding_to_an_adapter.md:167-168` uses `<enum default="false|true" value=...>`. So does the working MPB
  Synology describe, `knowledge/context/mpb/wire_reference/synology_nas_working_describe.xml:26-38`. One choice is flagged
  per field, and it matches the identifier's `default` (DescribeTest checks this). The form is correct. That the 9.1.1
  form now preselects the choice has **not** been seen live.
- Registry (`knowledge/context/defects.md`): no open defect has `Affects: vcommunity-java`.
- Issues #7-#30 (`gh issue list`): every number cited in README / installing.md matches its title and label.

## Enum defaults and unset values: what was proved safe

- **Allow Insecure SSL**: only `"true"` (any case, not trimmed) turns verification off. `null`, blank, absent and any
  other text verify (VCommunityConfig.java:87, :218). It cannot become less safe.
- **Guest switches**: `null`, blank and any text other than `Enabled` all mean Disabled (VCommunityConfig.java:369-374).
  It cannot become less safe.
- **guest_tls_validation**: `null` now gives Pinned, where it used to switch guest operations off. Pinned is the documented
  default, the same result as absent or blank. It only matters when a switch was explicitly Enabled, and it refuses a host
  with no recorded thumbprint. An unknown value still switches guest operations off (`TLS_CONFIG` failure, guest
  collector FAILED every cycle). This is not less safe than absent.
- **"Once per configuration change"**: the warnings are logged in `configureAdapter` (VCommunityAdapter.java:180). That
  runs only from `onConfigure`: an account edit, an adapter start or a collector restart. The docs say "saved, or the
  adapter started", which is accurate. The per-cycle `log.warn` in CycleEngine and the BAD_TLS_MODE warn in GuestPhase
  are removed.
- **Check-list file exception**: the code (`fileField`, VCommunityConfig.java:208-211) matches installing.md:225 and the
  API note at :686-687. A blank field reads no file; `null` means the default. ConfigTest covers both.
- **What stopped counting**: only `cfg.warnings` (out-of-range period, time, percentages, sizes, switches) and the
  per-cycle BAD_TLS_MODE line. The BAD_TLS_MODE failure still counts. See W1 for what was lost with `cfg.warnings`.

## BLOCKING

- **B1** [docs/installing.md:202] skill dimension 11 (docs contradict behavior): the field table says vCenter Server is
  "FQDN or IP of the vCenter". Under the verified-by-default posture, an IP fails the name check. The field is also part of
  the account identity, so an account entered by IP cannot be repaired by editing it. That is the worst first-install trap
  in the pak. → Fix: "A name that is on the vCenter certificate (an IP works only if the certificate lists it); part of the
  account identity, see TLS."
- **B2** [README.md:36, docs/overview.md:209] gap honesty (`knowledge/rules/no-fabricated-metrics.md` spirit) and
  dimension 11: both say "Build 0.0.0.26 (the code of this release) ... collected with every collector OK and no
  warnings". The release is build 28, with new TLS code (27) and new config parsing (28). The live proof is credited to code
  that is not what ships. → Fix: "Build 0.0.0.26 (before the TLS change of build 27 and the setting handling of build 28)".

## WARNING

- **W1** [src/.../VCommunityAdapter.java:180] dimension 6 (log must tell "evaluated" from "couldn't use"): an invalid typed
  value now leaves only one WARN line per configure. That line is in a per-instance log on a collector node, under an id
  the UI does not show (B10). Test Connection does not report `cfg.warnings`. The shipped "vCommunity Collection Warnings"
  alert keys on `Warnings in Last Cycle` (symptoms/vcommunity-collection-warnings.yaml:12), so it no longer fires for a bad
  setting. Nothing durable is left. → Fix: publish a separate self property/metric every cycle, outside `Warnings in Last
  Cycle` (e.g. `Collection|config_warnings` count plus the first text), and add `cfg.warnings` to the Test Connection
  attention lines. Document both in installing.md:227-232.
- **W2** [README.md:48] "Every item is a tracker issue" is false: Network Speed Degradation (:66), Alerts and super metrics
  (:69) and Restoring the built-in views (:72) have no issue. → Fix: file issues and link them, or say "Most items are
  tracker issues".
- **W3** [README.md:46-73] first-install trips missing from Known issues: the pak is unsigned (tick "Ignore the PAK file
  signature checking", or the upload fails with only "Upload failed."), the vCenter must be entered by a name on its
  certificate (B1), and the build-28 enum preselect is not yet seen on 9.1.1. → Fix: add one linked line each (installing.md
  step 2, TLS, Configuration Fields). Extend the "pending live proof" sentence at README.md:12, :16 and installing.md:14 to
  cover the build-28 settings change.
- **W4** [docs/installing.md:682] "The UI fills in the describe defaults" is contradicted by A1 (9.1.1 preselected no enum
  choice and stored `null`). → Fix: "The UI fills in the text field defaults; on 9.1.1 before build 28 it preselected no
  enumerated choice (stored as `null`, read as not set); the REST API applies none."
- **W5** [docs/installing.md:676-678] says the invalid-value warning comes "right after" the logout line. In code it is
  logged before `VCommunityAdapter configured` (VCommunityAdapter.java:180 vs :186), and the logout comes from `finally`
  after both. This is the one place the docs tell the operator to look. → Fix: "A setting with a value that is not allowed
  is warned about just before the `configured` line, once."
- **W6** [docs/installing.md:447-452, README.md:69-71] D20 only partly addressed: the policy path is given for the NIC
  property (:112-114) but not for enabling the alerts ("Alerts and Symptoms" tile) or the super metrics (Configurations >
  Super Metrics, and their policy enablement). → Fix: add both 9.1.1 paths with the same "seen on 9.1.1, editor opened,
  nothing changed" hedge.
- **W7** [CHANGELOG.md:14-16] the release notes say key history continuity "was not measured on an instance".
  installing.md:34-35 and README.md:87 say it was measured on 8.18.7. → Fix: align CHANGELOG to "measured on 8.18.7: one
  continuous series per key".
- **W8** [CHANGELOG.md:6-68] the 3.0.0.28 preview notes still describe build 27. They omit DV-115 (untouched settings
  silent, an invalid On/Off now warned where the Python pack silently disabled it) and the first live guest run ("not ready
  for general use", #28-#30). "a wrong password cannot lock a domain account" (:54) overstates: a lockout threshold of 2 or
  less would. "Known gaps" (:68) names a README heading that is now "Known issues and unverified". → Fix: add a DV-115
  bullet and a guest-status bullet, and change "cannot lock" to "costs at most two refused logons per 24 hours".
- **W9** [README.md:88-89] "uninstalling a pak removes the check-list files it installed (measured for this pak on 9.1.1)"
  reads as covering the Python pack in a section about the Python pack. installing.md:43-45 correctly says not measured for
  the Python pack. → Fix: append "not measured for the Python pack's own files".

## NIT

- **N1** [src/.../guest/GuestPhase.java:365-369] VCENTER_TLS and NO_CREDENTIAL still WARN every cycle and count, the same
  per-cycle config noise DV-115 removed for BAD_TLS_MODE. → Fix: state in installing.md that these two count on purpose,
  or move them to configure-time.
- **N2** [src/.../VCommunityAdapter.java:456] the collector object's `config_files` identifier uses parsed names. An
  account whose file field stored `null` now resolves to the default name, so after upgrade it gets a new collector object
  identity. → Fix: one CHANGELOG line, or accept and note in the review.
- **N3** [src/.../VCommunityConfig.java:339] `certificateCheckUrl` does not apply `unset` to host, so a `null` host gives
  `https://null:443`. Host is required, so this is harmless. → Fix: `trimToNull(unset(...))` for parity.
- **N4** [docs/overview.md:227] stray ";." at the end of the bullet. → Fix: end the sentence with ".".
- **N5** [docs/p5-live-proof.md:5] "Until then, nothing else in phase P5 has run" has no antecedent. → Fix: "Apart from
  that run, nothing in phase P5 has run against a real Windows guest."
- **N6** [docs/p5-live-proof.md:33-41] tells the tester to fill the default-named shipped files, contrary to installing.md
  "Clone a shipped file; do not edit it". → Fix: use clones (`windows_service_list_p5.xml`) and point the account at them.
- **N7** [docs/installing.md:703-704] "see 'Upgrading from a development build' below": the section is above (:461). →
  Fix: "above".
- **N8** [docs/installing.md:647-649] "the node the account runs on, which is not always the primary" repeats the sentence
  before it. → Fix: delete the repeat.
- **N9** [docs/installing.md:596, :605, :609] "the vCommunity tile" (the tile is "VCF Content Factory vCommunity"); the
  removed-content list does not say the recommendation and inventory tree were not counted; "the 120 built-in views" is that
  lab's restored set. → Fix: name the tile, add "(the recommendation and inventory tree were not counted)", and change to
  "the built-in views (120 on that instance)".
- **N10** [docs/README.md:121, docs/inventory-tree.md:3, CHANGELOG.md:80, tests/.../DescribeTest.java:176] generated docs
  still stamp 0.0.0.26. "a working Synology pak" is the MPB Synology describe, not `content/sdk-adapters/synology` (which has
  no enum). → Fix: regenerate with `build-sdk` before tagging, and name the source
  (`knowledge/context/mpb/wire_reference/synology_nas_working_describe.xml`).

## Defect list coverage (docwalk-defects.md)

A1, A2: addressed in code and at installing.md:221-232 (but see W1). B3-B13: addressed (installing.md:14, 340-353,
540-541, 573-592, 640, 644-649, 676). C14-C18: addressed (:518-565, p5-live-proof.md:33). D19: addressed (:594-614).
D20: partial (W6). E21: addressed (:62-63). F22: addressed (README.md:42-44, overview.md:209). G23-G28: addressed
(installing.md:264-298). The 9.1.1-only UI paths are hedged as such, except the step that names "the vCommunity tile"
(N9).

## If shipped as-is

An evaluator who follows the field table enters the vCenter by IP and gets an account that fails every cycle and cannot be
edited back into shape. An invalid setting stops raising any Collection Health signal or alert. The release notes credit
live proof to a build that is not the one shipped.
