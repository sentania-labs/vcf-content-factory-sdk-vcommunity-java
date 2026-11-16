# Review: P6 content assembly (build 14)

- **Adapter:** `content/sdk-adapters/vcommunity-java` (kind `vcfcf_vcommunity`), branch `p6/content`
- **Delta reviewed:** `git diff 8df74bd..ffd14c1` (5 commits, 282 files, +43269/-31; one Java change, the test
  `CheckListTest.shipped`)
- **Pak:** `dist/vcfcf_sdk_vcommunity.0.0.0.14.pak` (sha256 `c765344e...`)
- **Source pak:** `vCommunity_0.8.1-fix-builtin-view-ids-e1-releasedimage.pak`, sha256 `7cc81338...` (checked, matches)
- **Reviewer:** `sdk-adapter-reviewer`, 2026-11-13. Read-only on source; nothing installed; no live instance touched.
  The build was re-run on a scratch copy of the project, because `build-sdk` writes generated docs into the
  project directory.
- **Report location:** `docs/reviews/`, as the brief asks and as P1 to P5 did.
- **Upstream text:** read only at tag `v0.8.1` (`git -C ../vcommunity show v0.8.1:<path>`).

## Verdict: CHANGES REQUESTED

2 BLOCKING, 7 WARNING, 8 NIT.

The conversion holds up at the wire level. All 170 views, 52 dashboards, 16 reports, 2 super metrics, 10 symptoms,
4 alerts and the recommendation carry the source ids. Every reference between objects matches the source: dashboard
to view, report to view or dashboard, alert to symptom, and navigation targets. Every content key under
`vCommunity|` is one the Java adapter publishes, on the object kind the content reads it from. The two Section
dashboards close up their rows correctly. The check-list files parse clean with zero active entries.

Two things stop it:

- **B1.** The pak binds six dashboards to the Summary tab of six vCenter-adapter object kinds at install time.
  The source pak does not do this. Upstream 0.8.1 says in its own docs that the binding "is not in the repo's pak".
  The binding comes from an importer inference that the importer's verification never compares, so no report
  records it.
- **B2.** RULE-011 condition 3. Three dashboards have interaction-wiring differences, and the comparison note
  still shows "Sign-off needed: _open_" for each. Under the rule, those dashboards go back through the wireframe
  gate until the owner signs off.

## Independent re-runs

| Check | Result | Author's claim |
|---|---|---|
| `ci/run_java_tests.sh` | exit 0, 222 `ok` lines incl. `shipped check-list files: six, zero active entries, no warning, examples valid when switched on` | confirmed |
| `validate-sdk` | `OK: ... valid Tier 2 SDK adapter project`. Prints SECTIONS OMITTED for the two Section dashboards and one DEF-012 distribution warning on "ESXi vMotion Status" (a false positive, see N7). 8.18.7 and 9.1.1 compat checks skipped (no jars on this workstation). | confirmed |
| `build-sdk` (scratch copy, `-o` scratch) | `content/`, `manifest.txt`, `resources/`, `eula.txt` byte-identical to the dev pak. `adapters.zip`, `overview.packed` and `views.zip` differ only in zip metadata: unpacked trees are identical, jar classes included. The build's own stock-id guard: 252 definitions, 0 stock ids. | reproduces |
| `pak-compare` vs `tmp/vcommunity_ref9/vcfcf_sdk_vcommunity.1.0.0.9.pak` | PASS, 0 BLOCKING, 2 WARNING (W1 identifier count 19 vs 11, W2 TraversalSpecKind 1 vs 0), 380 INFO | confirmed |
| `stock-id-check` on the dev pak | 0 stock ids among 252 definitions. Super metrics and the recommendation are not covered (no baseline ids). | confirmed |

## Registry check (`knowledge/context/defects.md`)

No open defect's `Affects:` names `vcommunity-java`. DEF-004 (`vcommunity-os`) is the retired split pak, not this
one. Note: `vcommunity-java` has no entry in `knowledge/context/managed_paks.md`, so the defect gate
(`registered_pak_names()`) cannot attribute a defect to it until the orchestrator registers it (N6).

Registration candidates (RULE-012), both against the factory importer, not this pak:

- B1's root cause: `src/vcfcf_core/importer/dashboards.py:48-52,199-200` turns any pak
  `dashboards/<Folder>/dashboard.json` into `summary_for: VMWARE:<Folder>`. The platform binds a Summary page only
  through `content/dashboards/dashboards.properties` (`knowledge/context/api-surface/summary_dashboard_pak_binding.md`
  §Install-time behavior, step 3). The verification does not compare that binding.
- W7: a duplicate symptom id with conflicting names, resolved silently.

## 1. Does the content work with this adapter's data?

Method: every `vCommunity|` string in every view, dashboard, report, super metric, symptom and alert of the built pak
(207 references, 65 distinct keys). Each was matched against the 257 rows of `docs/p2..p5-key-coverage.tsv`
(placeholders such as `{license_name}` read as one instance segment). Dashboard widgets were resolved to their kind
through the dashboard's `entries.resourceKind` pool. Each key family was also looked for in the three live dumps
(`java-devel_9_1-*`, `java-devel-*`, `baseline-python-devel_8_18-*`).

**Result: every key is published by the Java adapter, on the kind the content reads it from, with one inherited
exception (Tier Performance, below). No content references the source adapter kind `vCommunity`, its
`vCommunity_adapter_instance` kind, or any of the three own kinds.** I grepped both paks for `adapterKind="vCommunity"`,
`adapterKindKey: vCommunity`, `Library Item`, `Content Library` and `VM Template` in content: zero hits. So the
`vCommunity:Library Item` empty-widget risk does not arise. The Java describe keeps the three own kind keys unchanged
(`Content Library`, `Library Item`, `VM Template`). The source describe's `vCommunity_adapter_instance`,
`Cluster Compute Resource`, `Host System`, `Virtual Machine`, `vCommunity-Datastore` and the other definition-only
kinds have no counterpart, and no content uses them.

| Key family (content refs) | Kind in content | Java row(s) / phase | Live dump (Java 9.1 / Java 9.0 / Python 8.18) | Note |
|---|---|---|---|---|
| Cluster Performance / Risk / Consumption scores and inputs (32 keys) | ClusterComputeResource | R153-R184, P4 | present, except `Highest ESXi Bad Network Packets` absent on 9.1 (and on Python 8.18) | input-dependent |
| Resource Pools (2) | ClusterComputeResource | R218-R219, P4 | present | |
| Periodic Cluster Check (4, view "vSphere Clusters Capacity List") | ClusterComputeResource | R222-R225, P4 | absent on both Java dumps, present on Python | periodic, not yet fired at dump time (final-summary) |
| World figures: Worst/10% Cluster Performance/Risk, Highest 10% Utilization, Cluster Delta Open Count, Number of Port Groups, Availability (9) | vSphere World | R198-R210, P4 | present | |
| Total Network Speed Degradation (2 dashboards) | Datacenter | R197 (E1, live-unproven) | absent on all three dumps, Python included | the Datacenter identity (E1) |
| Business Application (3, "Critical Business Applications") | Container BusinessService / Enterprise; Tier Performance read on **Container:Manual Application** | R215 Tier, R216, R217 | absent everywhere (no Tier/BusinessService objects in the labs) | the "Tiers in the selected Applications" widget lists Manual Application objects with a key that lands on Tier; identical in the source |
| VM scores, VM Guest OS KPI, Share per vCPU/MB (5) | VirtualMachine | R70, R71, R157, R158, R214 | present | |
| Licensing:<name> (5 keys, incl. 7 symptoms) | HostSystem | R3-R6, P2; Remaining Days is a metric, as the symptoms' `type="metric"` expects | present; lab license names are `VMware Cloud Foundation (cores)` and `Evaluation Mode` | W6 |
| Network Device:<vmnic> (3, incl. the NIC symptom) | HostSystem | R28-R30, P2 | present | value text: below |
| Configuration Install Date, Packages:<name> (3) | HostSystem | R1, R40, R41, P2 | present | Packages needs a check-list entry |
| Collection Health Warnings in Last Cycle (symptom) | VMwareAdapter Instance | R253, metric | present, value 1.0 on both Java labs | live item 9 |
| Guest OS Services:<display name> (3, view + symptom) | VirtualMachine | R78-R80, **P5, off by default** | absent (guest off) | the only guest-phase references |

Super metrics reference only VMWARE built-ins (`cpu|utilization_average`, `cpu|capacity_provisioned`,
`cpu|haTotalCapacity_average`, `badge|health`). They do not depend on this adapter.

**Symptoms and alerts against what Java sends:**

- **NIC (R30).** `!= "Connected"`, property, string, instanced. Java sends exactly `"Connected"` or `"Disconnected"`
  (`HostCollector.java:255`). Lines up.
- **Remaining Days (R6).** `type="metric"`, numeric. Java sends it as a stat (the floor of the days). Lines up. The
  key is a fixed, non-instanced license name (W6).
- **Service Status (R79).** `!= "Running"`, property. Java sends the guest's `Status` column as `ConvertTo-Csv`
  renders the enum (`GuestScripts.java:88-97`, fixture value `Running`). Lines up. It fires only with guest
  monitoring on, and only for the service whose display name is `DHCP Client` (instanced on that key, as upstream).
- **Warnings (R253).** `> 0`, metric. Java sends the count each cycle (`CollectionHealth.java:66`). Lines up. The
  recommendation attached to that alert does not (W1).

## 2. Manifest

- `bundled_content`: 255 entries, every file under the seven folders exactly once, none missing, none in the wrong
  folder, no duplicates. Equal to `docs/design/inputs/import-report/bundled_content.yaml`.
- `content_foreign_adapter_kinds: [VMWARE]` is enough. Every symptom and alert is on VMWARE. Nothing that needs
  Container was skipped. The five views on VMSP (four VCF management services lists) and APPLICATIONDISCOVERY
  ("Guest OS List of Services") are in the pak, and so are the two dashboards on Container/VMSP ("Critical Business
  Applications", "VCF Management Service"). All are identical to the source apart from the recorded description and
  title changes.
- `content_naming: unprefixed`: RULE-006 carve-out applies; validate-sdk enforces it.
- Declared minimum: the manifest says `vcops_minimum_version: 8.0.0` (W5).

## 3. The built pak against the source, at the wire level

I compared every object, not a sample. Renumbered ids, server metadata and number spelling were set aside. JSON key
order inside `SubjectType filter` was normalized. The pak's own `SubjectType vcfcf_vcommunity:vCommunityCollector`,
which the builder injects into every ViewDef (`render.py` owning-subject, spec A2), was dropped before comparing.

- **Views (170/170 ids).** The only differences are the 101 replaced descriptions (= `view_describe.tsv`), 9 retitles
  (= `view_rename.tsv`), 11 titles with trailing whitespace stripped (recorded as "surrounding whitespace the loader
  strips"), and the recorded losses: buckets-control on 5 list views, a localizationKey and an empty `preferredUnitId`
  on "VCF Management Services Components View". The 11 embedded copies of views inside reports are identical to
  the standalone ones. No shipped title equals a built-in view title in the stock baseline.
- **Dashboards (52/52 ids).** Widget by widget, after mapping renumbered widget ids by grid position: no type, title,
  size, view id, metric, threshold or pin differs. Remaining differences: Section rows removed on the two Section
  dashboards, with the rows below shifted up by exactly the removed rows (0 grid mismatches, 15 of 15 interactions
  and the Overall Daily Check navigations to "vSphere Cluster Capacity 2.0" / "vSphere Cluster Performance 2.0"
  resolve to the same target widgets). Also: label whitespace (recorded), `temporary: false` on 11 (recorded target
  deviation), `adapterName` (recorded on 32; the other 20 gain `vcfcf_vcommunity`, a builder constant), stale
  navigations dropped on 3 (recorded, B2), and resource-kind pools emitted without the unused entries (every
  referenced pool entry resolves to the same kind). **Not recorded anywhere: `content/dashboards/dashboards.properties`
  (B1).**
- **Folder and sharing.** `namePath`, `shared` and `hidden` are equal on all 52: 34 "VCF Optimization Assessment"
  (23 hidden), 10 "vCommunity", 1 "Capacity". One difference: "vSphere Cluster Capacity 2.0" is named
  `vCommunity/vSphere Cluster Capacity 2.0`, where the source name has no folder (recorded deviation `folder_in_name`;
  sign-off open; N5).
- **View references by raw id.** 29 widgets name 25 views not in the pak, exactly as in the source: 11 widgets on 9
  of the 19 dropped built-ins, and 18 widgets on 16 other product views. All 25 ids are in the stock baseline for
  8.18.7, 9.0.2 and 9.1.1, so the comparison doc's "not checked here" can be upgraded. 10 of the 19 dropped ids are
  referenced by nothing. Reports reference only shipped views and dashboards, plus `COVER_PAGE` / `TABLE_OF_CONTENTS`.
- **Reports (16/16 ids).** Identical apart from description whitespace (recorded).
- **Super metrics.** Same ids (`99152539-e2c3-473d-a4bf-26fc56988f50`, `3ec095da-c812-4baf-b0fa-3c2413392518`), same
  formulas apart from one trailing space (recorded), same resource kinds (ClusterComputeResource;
  DistributedVirtualPortgroup + VmwareDistributedVirtualSwitch).
- **Alerts and recommendation.** Identical. All `SymptomSet` refs resolve. The one `<SymptomSets>` ("ESXi Host
  License Expiring") has 4 children; the others are bare (dimension 13 holds).
- **Symptoms.** Identical, except W7.

## 4. Co-install and upgrade hazards

What the pak shares with each upstream build, by id (the platform tracks pak content by id; investigation
`2026-11-12-vcommunity-upstream-builtin-view-ids.md`):

| | released 0.8.1 | corrected 0.8.1 (the conversion source; = upstream once PR #92 merges) |
|---|---|---|
| Views | 69 of 170 (0.8.1 ships the other 101 under 101 built-in ids, plus 19 more built-in ids) | 170 of 170 |
| Dashboards, reports, super metrics, symptoms, alerts, recommendation | all | all |
| Check-list files (store key = lowercased title) | all 6 | all 6 |
| `vCommunity|` keys on the same VMWARE objects | yes | yes |

What follows. Single-pak behavior was measured; the two-pak cases were never run and are inferred from it:

- **Install of the second pak** overwrites the first pak's definitions under every shared id. The view titles and
  descriptions flip to the last installer's. Check-list files are the exception: without force, the second import is
  refused and the first pak's files stay.
- **Uninstall of either** (remove content) deletes every shared id. The remaining pak loses all its dashboards,
  reports, super metrics (and their policy enablement), symptoms and alerts, plus the 69 or 170 shared views. The
  check-list files go if the uninstalled pak recorded them, which is the one installed first.
- **Migration "install this, then remove the original"** (the natural order) therefore deletes this pak's content.
  With released 0.8.1 it also deletes the 120 built-in views 0.8.1 overwrote. 9 of those are the in-place views this
  pak's 11 widgets use, which then show "The view does not exist" until restored from a clean export.
- **Order "remove the original, then install this"** (what "do not install next to" implies) still deletes the 120
  built-in views with 0.8.1. This pak's 11 in-place widgets break until the restore. The original's check-list files
  are deleted too, so this pak installs its own.
- **Installing this next to released 0.8.1** (no uninstall) puts 92 view titles in the views list twice, because the
  0.8.1 copies sit under built-in ids with the same titles.
- **Super metrics collide by id** with every upstream line (same two ids). Uninstalling either pak removes them for
  both.
- **Same adapter kind as the retired port.** `vcfcf_vcommunity` was the retired 2026 Java port's kind (design Q8).
  Its `1.0.0.9` pak ships 63 views under stock ids, 33 view ids this pak also ships, and 2 shared symptom ids. On an
  instance that still has it, this install is an upgrade. On 9.x that deletes what this version no longer ships,
  including those 63 built-in views. The user docs do not mention it (N8).

`docs/installing.md:13-21` is accurate but not strong enough (W2). "Keeps the original pack's ids for most views" is
69/170 for one upstream build and 170/170 for the other. It does not say that uninstalling either pak deletes the
other's content, that the natural migration order destroys this pak's content, or that 11 widgets of this pak
break when the original 0.8.1 is removed. It also omits the restore step.

## 5. Check-list files

- **Parse.** All six read OK through the real `CheckListStore` with zero items, zero skipped and nothing logged
  (test re-run). The event list validates with 0 Log / 0 EventID.
- **Encoding.** ASCII, no BOM, XML declaration UTF-8, one comment each, no `--` inside a comment.
- **Examples.** Every comma-list example passes the adapter's own rules once switched on. I re-checked the VM Options
  paths against `VmOptionPath` and the service names against `ServiceNameFilter` independently. The two event-log
  examples validate.
- **Headers vs the parser.** Comma rule, line-break rule, comment rule, service-name character set and 256 limit,
  VM Options path shape, event list structure: all match `CheckListParser`, `ServiceNameFilter`, `VmOptionPath` and
  `EventListValidator`. The field names in the headers equal the `resources.properties` labels 9-12, 29 and 30. Two
  small omissions (N3).
- **Upgrade statement.** "A forced upgrade of the pack replaces it and your edits are lost" matches the factory's
  measurement-by-code (`summary_dashboard_pak_binding.md` §Short answers: force overwrites; non-force refuses and
  keeps the edit; uninstall with remove-content deletes recorded files). `installing.md` states the same and hedges
  correctly (9.0.2 code only, untested here). The headers state it without the hedge (N4).

## 6. Text and licensing

- **Shared runs** (6-word shingles, against all 301 text files at tag v0.8.1 plus every source view title and
  description). The 101 replaced descriptions have **zero** 6-word runs in common with the source. The highest
  sequence similarity to the description each replaces is 0.39 (Guest OS from Tools: shared domain words, different
  structure). The six check-list files share only the XML declaration. README / installing / overview / NOTICE:
  longest runs are 8-10 words, all account field labels, option values (`01 02 04 06 08 12 24`), URLs or "Trust on
  First Use". Facts, not expression. Spot check of 4 descriptions against their view definitions: accurate.
- **`carried-text-report.md` vs `report.json`.** Recomputed: 671 fields / 43,881 characters, per-type figures equal,
  101 replaced, 0 replaced views still carrying a source description. Matches.
- **NOTICE.** It says nothing false about the code. On the content, two points (W3, W4).

## Findings

### BLOCKING

**B1. The pak binds six Summary tabs that the source pak does not bind.**
- **Where:** built pak `content/dashboards/dashboards.properties`; source `dashboards/object-summary-page-for-*.yaml`
  (`summary_for: VMWARE:<Folder>`, six files). It binds DatastoreFolder, DistributedVirtualPortgroup, HostFolder,
  NetworkFolder, VMFolder and VmwareDistributedVirtualSwitch.
- **Authority:** the acceptance criterion (exact match of v0.8.1); dimension 9, an unrecorded behavior change; the
  factory's `summary_dashboard_pak_binding.md`.
- **The source:** the source pak has no `dashboards.properties`. Without it the installer imports the
  `VMFolder/` etc. dashboards as plain dashboards (§Install-time behavior, step 3). Upstream at v0.8.1 says so itself:
  `docs/development-guide/pak-summary-binding.md` reads "That step is tested but not yet in the build script, so it
  is not in the repo's pak". Upstream's way is the opt-in script in `docs/configuration-guide/summary-dashboards.md`.
- **Why no report shows it:** the importer adds `summary_for` from the folder name (`importer/dashboards.py:199-200`),
  and its verification compares dashboard JSON only. The comparison doc marks `summary_for` "identical".
- **What a user sees:** on install, the Summary tab of every VM, host, datastore and network folder, distributed port
  group and distributed switch changes from the built-in page to the vCommunity dashboard. These are objects this pak
  does not own. On 9.0.2 the template-clone race can leave those Summary tabs loading or empty. Uninstall cleanup was
  measured only by upstream, on 9.1.1.
- **Fix:** remove `summary_for` from the six YAMLs so the pak matches the source. If the owner wants the binding,
  record it as a signed-off intended change and document it in `installing.md`, with the per-line behavior.

**B2. RULE-011 condition 3: three dashboards still need the owner's sign-off.**
- **Where:** `docs/design/dashboards-port-comparison.md` §4.
- **Authority:** `knowledge/rules/wireframe-before-dashboard.md`, faithful-port exemption, condition 3.
- **The problem:** "Critical Business Applications", "VM Capacity (new)" and "vSphere Cluster Performance 2.0" drop
  source navigations (interaction wiring). Each still reads "Sign-off needed: _open_". Without the sign-off, the rule
  sends those dashboards back through the wireframe gate.
- **Fix:** record the owner's dated sign-off in the note. The dropped navigations are keyed by widgets that are not on
  the dashboards, so they cannot fire in the source either. Condition 4 (seen rendering on each line) is also still
  owed; it is live-only.

### WARNING

**W1. The Collection Warnings recommendation describes the Python adapter.**
- **Where:** `recommendations/vcommunitycollectionwarnings.yaml`.
- **The problem:** it tells the operator to search `adapter.log` for 'Failed to', 'Skipping' and 'Unexpected
  collection error' and to read a traceback. The Java adapter writes none of these strings (grep of `src/`). Its log
  is `VCommunityAdapter_<id>.log`, and it has no traceback.
- **What a user sees:** an operator who follows the alert's own advice finds nothing.
- **Fix:** rewrite it as a recorded intended change. Point at `First Warning`, `Collector:*|Causes`,
  `Collection|failure_causes` and the Java log path.

**W2. The co-install and migration warning understates the consequences.**
- **Where:** `docs/installing.md:13-21` and §Check-list files.
- **The problem:** the concrete outcomes from section 4 are missing. Uninstalling either pak deletes the other's
  shared content; the natural migration order deletes this pak's content; removing 0.8.1 breaks 11 of this pak's
  widgets until a restore from a clean export; the check-list file ownership stays with whichever pak was installed
  first. The id statement is also imprecise.
- **Fix:** state these outcomes and a safe order (remove the original, restore the built-in views, install this
  pak), and mark which parts are unmeasured.

**W3. The 101 view ids are not new, though the docs say they are.**
- **Where:** design §10.1, DV-54 (`vcommunity-java-port-design.md:826,1411`), NOTICE ("new ids as well", listed
  under "written for this pak"), `installing.md:173`, `overview.md:188`, `CHANGELOG.md:12`.
- **The problem:** the design and docs say the ids are new, minted by the factory. They are the corrected source's
  ids (`--ids keep`; `id_map.tsv` is the PR #92 fix), so nothing on the id side was written for this pak.
- **Consequence:** once PR #92 merges, the collision with upstream covers every view, not 69.
- **Fix:** either mint ids as the design says, or record the deviation and correct the wording.

**W4. The licensing notice does not ship in the pak.**
- **Where:** NOTICE and the built pak.
- **The problem:** the pak ships `eula.txt` = MIT and content derived from a PolyForm Noncommercial 1.0.0 work, with
  neither the terms URL nor the licensor's `Required Notice:` line (`LICENSE` at v0.8.1, Notices clause). NOTICE is
  in the repo only. NOTICE also calls the source "the vCommunity 0.8.1 pack image". It is a factory build of the
  unmerged fix (branch `fix/builtin-view-ids-e1`) on the released 0.8.1 adapter image.
- **Fix:** name the source exactly (file and sha256) and carry the notice inside the pak, pending the owner's
  licensing record. NOTICE already blocks a release on that record.

**W5. The pak declares a minimum of 8.0.0.**
- **Where:** built `manifest.txt` `vcops_minimum_version: 8.0.0` (`sdk_builder.py:2852`, a factory constant);
  `dashboards-port-comparison.md` §3.
- **The problem:** the content was converted and gated for an 8.18.7 floor, upstream declares 8.18.0, and the
  comparison doc says the floor "is 8.18.7".
- **What a user sees:** an 8.10-8.17 instance accepts the pak.
- **Fix:** a tooling follow-up for the manifest constant; correct the comparison doc's statement now.

**W6. ESXi Host License Expiring is keyed to one license name.**
- **Where:** all 7 condition symptoms (inherited, identical to the source).
- **The problem:** the key is `vCommunity|Licensing:vSphere 8 Enterprise Plus for VCF|Remaining Days`, not
  instanced. Java publishes Remaining Days per license name.
- **What a user sees:** the alert never fires for any other license name. In the labs that is every host
  (`VMware Cloud Foundation (cores)`, `Evaluation Mode`).
- **Fix:** document it as a known limitation in `installing.md`; it is parity, not a port bug.

**W7. A conflicting duplicate symptom was resolved without a record.**
- **Where:** `symptoms/windows-service-status-down.yaml`; `report.json` `merged_duplicates`.
- **The problem:** the source pak defines `SymptomDefinition-7675759b-...` twice under two names: "Windows Service
  Down" (`symptomdefs/Windows Service Down Symptom.xml`) and "Windows Service Status Down" (inside
  `alertdefs/Windows Service Down.xml`). The pak ships only the second. `merged_duplicates` lists only the identical
  NIC pair, so the importer's accounting contract missed it.
- **What a user sees:** the symptom name may differ from what an install of the source shows.
- **Fix:** record the choice in the design inputs; register a factory:extractor candidate.

### NIT

- **N1.** RULE-011 condition 1 names `knowledge/designs/dashboards/<slug>.md`. The batch note lives in the pak repo,
  which is gitignored in the factory. The orchestrator decides whether that satisfies it.
- **N2.** The new description of "VM Memory Limit" ships an inherited defect as user text ("reads the CPU limit
  value ... the slices need checking before use"). Accurate, but a known-issue note may suit it better.
- **N3.** Two check-list headers are slightly incomplete. `windows_service_list.xml` does not mention the `$(`
  exclusion (`ServiceNameFilter.java`). `windows_event_list.xml` says "the last good list stays in force", which holds
  only until a collector restart (`CheckListStore.java:33-35`).
- **N4.** The check-list headers state the forced-upgrade overwrite without the "read from 9.0.2 code" hedge that
  `installing.md` carries.
- **N5.** "vSphere Cluster Capacity 2.0" carries its folder in its name (`folder_in_name` sign-off open). It is
  live-only whether it lands in the same "vCommunity" folder with the same display name.
- **N6.** `vcommunity-java` is not in `managed_paks.md`, so `defect-gate --pak vcommunity-java` sees nothing.
- **N7.** validate-sdk's DEF-012 warning on "ESXi vMotion Status" is a false positive: `buckets: {dynamic: true,
  calc_function: DISCRETE}` is already set. Factory follow-up.
- **N8.** `CHANGELOG.md` / DV-54 say the 101 views are "re-authored" and "have new titles". They were converted with
  new descriptions, and 9 have new titles. The user docs also do not mention the retired port with the same adapter
  kind (section 4).

## What only the live install can settle

1. **Summary tabs (B1).** Whether the six bind, and what uninstall leaves, on each line, if the binding stays.
2. **Section-less dashboards.** Whether they import on 8.18.7 and 9.0.2, and whether "Overall Daily Check" still
   navigates.
3. **Kinds and in-place views.** Whether the VMSP and APPLICATIONDISCOVERY views, "VCF Management Service" and the
   NamespaceV2 CSV view import (or are dropped) on 8.18.7. Whether the 25 raw-id views resolve on each line (the
   baseline says yes; never looked at on an instance).
4. **Total Network Speed Degradation** on Datacenter (E1). Absent on all three dumps, Python's included.
5. **Business Applications.** Values on Tier/BusinessService/Enterprise (no such objects in the labs), and whether
   "Tiers in the selected Applications" (kind Manual Application) shows anything; it does not in the source either.
6. **Inputs not yet seen.** Periodic Cluster Check after its window fires; `Highest ESXi Bad Network Packets` on 9.1.
7. **Super metrics** compute once enabled in policy.
8. **Check-list files.** Store behavior on 8.18.7 and 9.1.x (only 9.0.2 code was read).
9. **Collection Warnings.** Both Java labs report `Warnings in Last Cycle = 1` every cycle ("No NIC speeds for any
   host"), so the alert will stand on those vCenters from the second sample. Decide whether that is intended before
   shipping the alert.
10. **Co-install and migration** (section 4): every two-pak outcome is inferred, not measured.
11. **RULE-011 condition 4:** each dashboard seen rendering on 8.18.7, 9.0.2 and 9.1.1.
