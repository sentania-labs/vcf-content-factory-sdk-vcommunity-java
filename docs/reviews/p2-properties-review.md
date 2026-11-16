# P2 property collectors review (build 5)

- Adapter: `vcfcf_vcommunity` (`vcf-content-factory-sdk-vcommunity-java`), branch `p2/properties`
- Diff reviewed: `9f618bc..4b51963` (includes the isolated check-list fix `ad44175`), dev pak `dist/vcfcf_sdk_vcommunity.0.0.0.5.pak`
- Upstream compared: e1-rahabok vCommunity `v0.8.1`, read with `git show v0.8.1:<path>` (no checkout)
- Reviewer: `sdk-adapter-reviewer`, 2026-11-13, static only (nothing installed, no live instance touched)
- Verdict: **CHANGES REQUESTED**: 3 BLOCKING, 11 WARNING, 10 NIT

The code is careful and mostly faithful. The three blocking items are two places where upstream does
something the key map did not record, so the Java port publishes values upstream never sends. The third
is a VM Options entry that can still take out every VM key, which the P2 acceptance row says must not
happen.

## Independent re-runs

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` at `4b51963` | all pass: 104 `ok` lines, including the 19 `properties:` suites, `key coverage table equals the key map and the tests`, `ConfigFileErrorTest`, BCJSSE |
| coverage TSV | the verify mode of `Coverage.verifyOrWrite` passed, so `docs/p2-key-coverage.tsv` regenerates byte-identical; the tree stayed clean. 146 P2 rows `yes` (VM 53, host 47, cluster 22, vCenter 15, datastore 9) confirmed |
| `validate-sdk` | OK. The 8.18.7 and 9.1.1 SDK compat checks were **skipped** (jars not in `adapter_runtime/`), same as P1 |
| `build-sdk` to scratch | builds `0.0.0.5`. Every class in `vcfcf_vcommunity.jar` is byte-identical to the author's `dist/` pak, so the pak comes from this commit (zip and overview timestamps differ only) |
| `pak-compare` vs `tmp/vcommunity_ref9/vcfcf_sdk_vcommunity.1.0.0.9.pak` | PASS, 0 BLOCKING / 1 WARNING (`adapter instance identifier count 19 vs 11`, expected) / 278 INFO. Same score for the rebuild and the author's pak |
| key strings | every literal segment of the 146 P2 keys appears verbatim in the upstream `app/` source (scratch script). The tests check the shape of every pushed key against the key map |
| LastPushed heap (scratch probe, production class) | 2,000 VMs x 59 keys: 17 MB retained; 10,000 VMs: 87 MB retained (see W8) |

What the coverage file proves: a test pushed a key of that shape. It does not prove the value, the
formatting or the condition matches upstream. That is what this review checks by reading.

## Registry check (`knowledge/context/defects.md`)

No defect, open or closed, has `Affects: vcommunity-java`. The open defects are DEF-004 (`vcommunity-os`),
DEF-019 (`factory:extractor`), DEF-025 and DEF-026 (`compliance`). None touches this pak. No
`defects.local.md` exists. Registration candidates: B3 and W4 (see the end).

## BLOCKING

### B1. Tools Sync Time and Tools Upgrade Policy: upstream always sends `None`; Java sends the real value (unregistered)

- Upstream `properties/vm/vm_configurations.py:34-36` reads `prop_dict.get("config.tools")`. The VM read
  (`collectors/vm/collectVMData.py:82-83`) asks only for the leaf paths `config.tools.syncTimeWithHost` and
  `config.tools.toolsUpgradePolicy`. A propSet is keyed by the path asked for (`vi_property_collector.py`,
  `prop_dict = {p.name: p.val}`), so `"config.tools"` is never a key. `tools_info` is always `None`, and
  lines 111-112 push `str(None)` = `"None"` for both keys on every VM. The one exception is a VM Options
  list that holds exactly `config.tools`.
- Java `VmCollector.java:117-118` reads the leaf paths and sends `True`/`False` and `manual`/`upgradeAtPowerCycle`.
- Key map rows R105/R106 (`vcommunity-e1-key-map.tsv:106-107`) name the leaf paths as the source. The map
  is wrong; the port followed it.
- Concrete input: any VM with `syncTimeWithHost=false`, `toolsUpgradePolicy=manual`. Upstream: `None`, `None`.
  Java: `False`, `manual`. The differential shows this on 100% of VMs.
- Fix: do not copy `None` (that is a DV-08 sentinel). Add a DV row ("upstream always sends `None` from a
  lookup of a path it never reads; Java sends the value") and correct R105/R106 in the map.

### B2. Virtual Disk keys on a VM with no network adapter: upstream sends none; Java sends seven per disk (unregistered)

- Upstream `properties/vm/vm_disk.py:40-43`: `if not vnics: return` comes **before** the disk loop
  (57-81). A VM with no `VirtualEthernetCard` gets no `vCommunity|Virtual Disk:*` key at all.
- Java `VmCollector.java:161-184` handles disks without regard to NICs.
- Concrete input: VM with `Hard disk 1` and no vNIC (an isolated appliance, a template stripped of its NIC).
  Upstream: no `Virtual Disk:Hard disk 1|*`. Java: Label, Key, Controller Key, Eagerly Scrub, Split, Write
  Through, Storage Allocation Share Level.
- Not in the key map rows R124-R130 or in the collection map.
- Fix: an owner decision, then either copy the gate (one line) or add a DV row. Either way, correct the map.

### B3. A valid but non-scalar VM Options entry can still fail the whole VM read (5.5 rule 3, P2 acceptance row)

- `VmCollector.java:79-81` adds each accepted Options path with `PathSpec.all()`, so the whole value is
  built as a tree for every VM. `XmlNode.java:147-148` throws `XMLStreamException` at 32 levels.
  `VimSoapClient.streamOnce` turns that into a `Parse` fault. `PropertyReader.java:89/101` fails the read,
  and `PropertyCollection.java:565` returns null for the type, so **every VM key on every VM is withheld**.
  The 16 MiB page cap (`PropertyReader.java:30`) fails the read the same way.
- Concrete inputs that pass `VmOptionPath.isValid`, then reach this:
  - `snapshot` on a VM with a snapshot tree about 30 deep (`rootSnapshotList > childSnapshotList > ...`);
  - `config` (or `config.hardware`) on a VM whose disk backing `parent` chain is 27 or more deep;
  - `config` on a page of 50 VMs whose full configs exceed 16 MiB.

  The depth case is rare in practice but it is deterministic, and it repeats every cycle.
- Also, a path found non-scalar is only logged (`PropertyCollection.java:569-574`). It stays in the read
  every cycle, which costs bytes and keeps the failure live.
- This breaks the P2 phase-table acceptance ("a deliberately bad VM Options entry costs only its key") and
  5.5 rule 3 ("the base VM keys are never lost to an options entry").
- Fix: read Options paths with a depth-1 spec (keep the `val` element, skip its children, but note that
  children existed). Class a value as non-scalar by that flag or by an `ArrayOf*`/data-object `xsi:type`.
  After the first non-scalar finding, drop the path from later reads, as a refused path is dropped.

## WARNING

- **W1. Item 2, blank-string withholding, is a mismatch by construction and is not registered.**
  `Vals.nonBlank`/`stripped` and `isEmpty` checks withhold a value where upstream pushes `""` (or the
  unstripped spaces):
  - host: `HostCollector.java:123` (Max EVC), `194-197` (HBA Model/PCI/Status/Type when whitespace-only),
    `219-220,222-223,227,229` (NIC Driver/Firmware Version, PCI Vendor/Device Name, MAC, PCI), `251-258`
    (package fields), `277-278` (License Key, Edition Key);
  - VM: `VmCollector.java:147-148` (Customization Status `"  "` gives `""` upstream), `214,220,237`
    (vNIC label, vNIC Type, disk share level), `DetailedDataParser.java:32-34` (a value of spaces gives `""`
    upstream);
  - vCenter: `VCenterCollector.java:294` (Patch Level `""`).

  DV-17 covers only template and library fields. What the differential will show: on those objects
  upstream has the key with an empty string sample; Java has no sample (the key is absent on a new object,
  or keeps its previous value). The likeliest live hits are `Firmware Version`/`Driver Version` on nested or
  virtual NICs and `Patch Level`. Meanwhile scalar paths read through `Vals.scalar` (VM Path Name, Firmware,
  ...) do push `""`, so the policy is not uniform either. Fix: a DV row listing these keys, or push `""`
  where upstream does (it is the reading, not a placeholder).
- **W2. Item 3, a ManagedObjectReference Options value is skipped: unregistered, and the message is wrong.**
  `PyText.java:223` returns null, and `PropertyCollection.java:571` logs "an object or a list". pyVmomi's
  `str()` of a MoRef is `'vim.HostSystem:host-12'` (quotes included). It can be reproduced, but only with a
  wire-type to pyVmomi-name table (`DistributedVirtualPortgroup` becomes `vim.dvs.DistributedVirtualPortgroup`).
  DV-26 says "object values". Fix: name MoRefs in DV-26 and give them their own log text. Options `runtime.host`
  and `resourcePool` are plausible operator entries. `xsd:base64Binary` renders as base64 text, where Python
  gives `b'...'` (N7).
- **W3. Item 4, the Ops list refresh can leave a new object keyless for up to 12 cycles. Unregistered, and
  overclaimed in the docs.** `OpsMapCache.java:76-79` with `PropertyCollection.java:645-651`: a MoRef the map
  lacks triggers one re-read the first cycle it is seen. If the VMware adapter has not discovered it yet, the
  next cycle it is in `lastSeen`, so `anyNew` is false and nothing is read until `cycle - loadedCycle >= 12`.
  Concrete: a VM created at T, discovered by the VMware adapter at T+7 min, has no vCommunity keys until
  about T+60 min. Upstream reads the Ops resources every cycle. `overview.md:79-81` says the lists are read
  "again when a new object turns up". Fix: re-read on a persistent miss at a shorter bound (2 to 3 cycles),
  or register the delay and correct the doc.
- **W4. The deadline abandons reads that keep running.** `PropertyCollection.java:255-262` does `f.cancel(true)`,
  but `PropertyReader.java:81-92` never checks for an interrupt between pages, and `HttpURLConnection` is not
  interruptible. A timed-out R-VM keeps issuing `ContinueRetrievePropertiesEx` to the end in a daemon thread,
  overlapping the next cycle. Against a vCenter that is always slower than the interval, up to 4 read threads
  plus 8 per-host threads per cycle pile up. Fix: check `Thread.interrupted()` and the deadline before each
  page and each per-host call; cancel the token (W5).
- **W5. Retrieve tokens are never cancelled.** The probe (`PropertyReader.java:85`, `firstPageOnly` with
  `maxObjects=1`) leaves a token on any vCenter with 2 or more VMs. A failed continuation page (`:89`) leaves
  one too. vCenter keeps the remaining result set until `CancelRetrievePropertiesEx` or the session ends, and
  DV-38 keeps the session for the life of the adapter. Bounded (once per list change, once per failed page),
  but it is a server-side leak. Fix: `CancelRetrievePropertiesEx(token)` in both paths. The `DestroyView`
  result is also ignored silently (N2).
- **W6. A systematic collector failure reads as PARTIAL.** `guarded` (`PropertyCollection.java:280-289`)
  counts each object with `keysWithheld = 0`. `mark` (`:739-746`) gives PARTIAL whenever the main read
  returned, even when every object threw. Concrete: a collector bug that throws on every VM gives
  `Collector:vm|Status = PARTIAL` and `values_withheld` unchanged by those VMs. Fix: FAILED when rows > 0 and
  no object succeeded, and count an estimate of the withheld keys (or the row count) for a failed object.
- **W7. missingSet entries are counted as absent, not as failed reads.** `PropertyReader.java:178-187` keeps
  only the path and drops the fault. In RetrievePropertiesEx an unset optional property is simply omitted; a
  `missingSet` entry exists only when reading that property raised a fault (`NoPermission`, `SystemError`).
  Design 5.1 rule 2 groups "path in missingSet" with absent, so the code follows the design. The design rule
  hides partial-permission reads from `reads_failed`. No value is made up; the count is wrong. Fix: count a
  missingSet entry as a rule-1 failure (one per cause per type), and correct 5.1.
- **W8. LastPushed exceeds the section 9.3 heap budget at the scale section 9.4 tests.** Measured with the
  production class: 2,000 VMs x 59 keys retain 17 MB (the design line is 6 MB "keyed by interned resource and
  key ids"); 10,000 VMs retain 87 MB against a 35 MB retained budget for the whole instance. Every key String
  is a per-object concatenation (`VmCollector.java:92` and onward) and is held per resource
  (`LastPushed.java:151`). Fix: intern the key strings (a static key table, or `String.intern()` at
  `commit`), or hash (resource, key) to one `long` key.
- **W9. One capped stream for every license assignment.** `PropertyCollection.java:496-500`: a single
  `QueryAssignedLicenses` with the full `assignedLicense.properties` (feature lists) of every entity, capped at
  16 MiB (`:76`). Pruning shrinks the tree, not the bytes on the wire. On a large vCenter (about 1,500 or more
  assignments at roughly 10 KB each) the cap fails R-LIC and no host gets licensing keys, every cycle.
  Measure the bytes per assignment live; raise the cap for this call or query per host batch.
- **W10. The eleven decisions did not reach the design or the register.** `docs/design/` is unchanged in the
  range.
  - Design 4.5 says templates get no VM keys; the code (decision 1) publishes them, as upstream does when
    Ops models the template as a VirtualMachine.
  - DV-33 is still "only if E13 shows upstream is negative". The wire `vendorId` is a signed `xsd:short`, so
    upstream sends `-7f7a` for Intel `0x8086`, and the differential will show `8086` vs `-7f7a` on every NIC
    with an id of `0x8000` or above.
  - DV-59 ("withheld until T3") is obsolete now that R43 goes as a numeric property.
  - Decisions 4, 5, 6, 8 and 11 have no DV or design note (4 and 6 change timing and counting).

  Fix: one design pass that records them. The gate is "equal or registered".
- **W11. `ad44175`: a 404 on the JSON retry clears the list.** `ConfigFileFetch.java:28` rethrows the second
  answer when it has any message. `CheckListStore.isAbsent` (`:268-271`) accepts a bare 404. So a first
  refusal of 400 with no code, followed by a JSON answer of 404 with a message, is classed ABSENT and drops the
  last good list (DV-27). That is a decision taken on the evidence of a request other than the one refused.
  The reported status is also the second request's. Fix: take only `apiErrorCode == 1501` from the retry;
  otherwise keep the first refusal. Otherwise `ad44175` is sound. The retry is limited to 4xx with no code, a
  RuntimeException in it is caught per file (`CheckListStore.java:166-169`), the excerpt is framework-bounded
  and then cut to 120 characters with control characters removed, and XML parsing has DTDs and external
  entities off. "Never markup" holds for XML bodies but not for a JSON `message` that contains `<` or a plain
  text body (`ErrorText.java:31`) (N10).

## NIT

- N1. `guarded` reports under `FailureCause.soap(read)`, the same id as a whole-read SOAP failure, so a
  collector bug and a vCenter fault cannot be told apart in health.
- N2. `PropertyReader.destroy` ignores a `DestroyView` fault; a leaked view is invisible.
- N3. `newSession` keys on `instanceUuid|version|build`, not on a relogin; 5.5 says to probe again after a
  reconnect.
- N4. Package `version`/`acceptanceLevel`/`type`/`vendor` and `editionKey`/`licenseKey` go through `childText`
  (trimmed); upstream sends them raw.
- N5. EVC `currentEVCModeKey = "  "`: upstream `Enabled=True`, `Mode="  "`; Java `Enabled=False`, Mode withheld
  (`ClusterCollector.java:125-128`).
- N6. `place()` re-reads a map and `vcenter()` reads the certificate back on the caller thread with no
  deadline check.
- N7. `xsd:base64Binary` Options values: Java base64 text vs Python `b'...'`.
- N8. R-HOO uses an explicit objectSet. A host removed between R-HO and R-HOO can fault the whole options
  read (`ManagedObjectNotFound`) instead of one host. A race; confirm live.
- N9. The coverage `test` column names the first scenario that pushed a key shape (VM rows show
  `PropertiesTest: hosts`); it proves shape, not values.
- N10. `ErrorText` returns a JSON `message` or a plain-text body as is (markup inside it survives; a
  BOM-prefixed HTML body is not recognised as markup).

## The eleven author decisions

| # | Decision | Exact match? | Registered? | Safe? |
|---|---|---|---|---|
| 1 | templates publish VM keys | yes, matches upstream (upstream adds every matched VirtualMachine, templates included) | design 4.5 says the opposite (W10) | yes |
| 2 | blank strings withheld where upstream pushes `""` | **no**, by construction (W1) | no (DV-17 covers templates/libraries only) | honest, but visible |
| 3 | MoRef-valued Options skipped | no: upstream `'vim.HostSystem:host-12'` | only loosely under DV-26 (W2) | yes |
| 4 | Ops list refresh policy | timing deviation: new objects up to 12 cycles late (W3) | no | yes, with the delay |
| 5 | Objects Not Matched includes foreign objects | not an upstream key (additive) | additive per 5.2 | yes |
| 6 | missing R-HOO row counts as failed | values same (VAAI/advanced withheld); counting is additive | no | yes; see N8 |
| 7 | VM Options probe on the first page of a container view | keys same | yes (5.5) | leaks a token (W5); InvalidProperty with zero VMs is a live item |
| 8 | dotted `dasConfig`/`drsConfig` read as pruned subtrees | yes, equivalent to upstream's leaf paths | not needed | yes |
| 9 | R43 numeric property | yes, upstream `with_property(bool)` becomes `numberValue` 1.0/0.0 | DV-59 now stale (W10) | yes |
| 10 | PCI ids unsigned 4-digit hex | **no** for ids of `0x8000` or above (Intel `0x8086`: `-7f7a` upstream) | DV-33, still conditional (W10) | yes |
| 11 | option reads folded into streamed reads | yes for values | n/a | the cap: W9 |

Item 2, what the differential shows: for each key in W1, upstream holds an empty string sample (Ops shows a
blank but present property); Java holds no sample, so the key is absent on a fresh object, or shows its last
non-blank value forever on an object that had one. Whether Ops stores an empty string property at all is
itself a live question; if it drops them, item 2 becomes invisible.

## Sampled keys

Legend: **match** = same key, type, text and publish condition on real data; **reg** = differs only in a
registered deviation; **MISMATCH** = unregistered difference. Upstream paths are under `Management Pack/app/`.

### VirtualMachine (19)

| Key | Java | Upstream | Result |
|---|---|---|---|
| Configuration\|NUMA\|Hot Add Exposed | VmCollector:93 | vm_configurations.py:88 | match (`True`/`False`); unset: `None` vs absent, reg DV-08 |
| Configuration\|CPU Allocation Share Level | :94 | :89 | match (enum text) |
| Configuration\|Boot Options\|Delay | :95 | :90 | match (`xsd:long` decimal) |
| Configuration\|Boot Options\|Retry Enabled | :97 | :92 | match |
| Configuration\|Change Version | :99 | :94 | match (string kept raw, not parsed as a date) |
| Configuration\|Flags\|Disk UUID Enabled | :101 | :96 | match; unset (common) `None` vs absent, reg DV-08 |
| Configuration\|Op Notification Timeout | :108, 138-143 | :33, 102 | match incl. negative to `0`; absent `0` vs absent, reg DV-11 |
| Guest\|Tools Sync Time | :117 | :34-35, 111 | **MISMATCH** B1 |
| Guest\|Tools Upgrade Policy | :118 | :36, 112 | **MISMATCH** B1 |
| Guest\|Customization Status | :122, 145-150 | :42-46, 116 | match (strip, replace all); `"  "` gives `""` upstream vs absent (W1) |
| Guest\|Detailed Data\|Pretty Name | :152-159, DetailedDataParser | :52-85, 124 | match (same regex, last wins, strip); empty `null` vs absent reg DV-09; spaces W1 |
| Guest\|Heartbeat Status | :123 | :117 | match |
| Configuration\|SCSI Controllers\|Count | :163, 183 (metric) | vm_disk.py:13, 18 (metric) | match; absent list `0` vs absent, reg DV-11 |
| Configuration\|SCSI Controllers:{bus}\|Type | :175, 203-211 | vm_disk.py:20-34 | match for the 4 subtypes; base class `vim.vm.device.VirtualSCSIController` vs `VirtualSCSIController`, reg DV-34 |
| Network\|Network Adapters:{label}\|Type | :219-220 | vm_disk.py:47-53 | match (label stripped); unset `None` vs absent, reg DV-08 |
| Network\|Network Adapters:{label}\|Starts Connected | :221 | :51, 54 | match |
| Virtual Disk:{label}\|Key / Eagerly Scrub | :232, 234 | vm_disk.py:76, 78 | values match; **MISMATCH** on a VM with no vNIC, B2 |
| Configuration\|Advanced Parameters\|{key} | :240-252 | vm_extra_config.py:15-21 | match (exact key, `str()`) |
| Options\|{path} | :254-265 | vmConfig.py:10-16 | match for bool/int/long/float (repr)/dateTime/enum/string; MoRef W2; non-scalar reg DV-26 (but B3) |

### HostSystem (17)

| Key | Java | Upstream | Result |
|---|---|---|---|
| Configuration\|Install Date\|UTC | PropertyCollection:435-445, PyText.isoUtc | host_install_date.py:18-21 | match (`isoformat`, fraction omitted when zero); failure `null` vs absent and counted, reg DV-10 |
| Licensing:{name}\|License Key | HostCollector:277 | host_licensing.py:21, 36 | reg DV-25 (masked) |
| Licensing:{name}\|Edition Key | :278 | :22, 37 | match (blank: W1) |
| Licensing:{name}\|License Expiration Date | :280 | :26-29, 40 | match (`str(datetime)`, `Z` as `+00:00`, `.123` as `.123000`) |
| Licensing:{name}\|Remaining Days (metric) | :281-286 | :33, 43 | match (floor, `timedelta.days`); "now" is cycle start, not call time (off by seconds at a day boundary) |
| Storage Adapter\|Number of HBA | :175-180 | host_configurations.py:41, 72 | match (string); absent `0` vs absent, reg DV-11 |
| Storage Adapter:{dev}\|Device | :182-192 | :45-48, 65 | match incl. `key` last-segment fallback |
| Storage Adapter:{dev}\|Model | :194 | :53-54, 67 | match; `""` gives `null` reg DV-09; `"  "` gives `""` upstream, W1 |
| Configuration\|VAAI\|ATS Heartbeat | :151 | :33-36, 73 | match (`str(long)`); absent `null` vs absent reg DV-09 |
| Configuration\|Advanced System Settings\|{key} | :154-157 | host_advanced_settings.py:18-21 | match |
| Configuration\|Max EVC | :123 | :19, 76 | match; absent `[]` reg DV-12; `""` W1 |
| Network\|Search Domain | :160-172 | :23-31, 78 | match (strip each, drop empties, `", "`); none `null` reg DV-09 |
| Configuration\|Time Zone\|GMT Offset | :129 | :14, 81 | match |
| Runtime\|Boot Time | :130 | :15, 82 | match (`str(datetime)`, pyVmomi truncates the fraction to 6 digits as Java does) |
| Network\|Device:{nic}\|Status | :217, 221 | host_uplink.py:33, 68 | match |
| Network\|Device:{nic}\|PCI Vendor ID (VID) | :224, 236-244 | :40-44, 73 | reg DV-33 (conditional, W10): ids of `0x8000` or above differ |
| Network\|Device:{nic}\|Duplex | :230-231 | :61-62, 80 | match while up; down `half-duplex` vs absent, reg DV-15 |
| Configuration\|Packages:{name}\|Maintenance Mode Required | :253-255 (numeric property) | host_software_packages.py:20 (bool becomes `numberValue`) | match |

### ClusterComputeResource (11)

| Key | Java | Upstream | Result |
|---|---|---|---|
| Cluster Configuration\|Configuration Status | ClusterCollector:52 | cluster_configuration_properties.py:19 | match |
| Cluster Configuration\|Number of Effective Hosts (metric) | :55 | :16, 20 | match; absent `0` reg DV-11 |
| vSphere HA\|Host Monitoring | :64-90 | ha_properties.py:36-71 | match incl. both suppression branches; off `null` reg DV-09 |
| vSphere HA\|Response \\ Host Isolation | same | same | match (key with literal backslash) |
| vSphere HA\|Failure Interval | same | :30, 68 | match (int) |
| vSphere HA\|Heartbeat Datastore | same | :29, 67 | match |
| DRS\|Proactive DRS | :94-117 | drs_properties.py:19-20, 32 | match incl. "no proactive config sends none of the four" |
| DRS\|CPU Over-Commitment | same | :26-34 | match (`int(opt.value)`, first `MaxVcpusPerCore`, a bad int sends none of the four) |
| EVC\|Enabled / EVC\|Mode | :119-129 | evc_properties.py:14-25 | match; Mode off `null` reg DV-09; N5 |
| DRS\|DRS Score (metric) | :131-143 | drs_metrics.py:21-35 | match when on; off/negative `0` reg DV-16 |
| DPM\|Host Power Action Rate | :145-149 | dpm_properties.py:13-17 | match; unset rate `None` vs absent, reg DV-08 |

### VMwareAdapter Instance (7)

| Key | Java | Upstream | Result |
|---|---|---|---|
| Configuration\|User Directory Timeout | VCenterCollector:260, 278-281 | collect_vcenter_properties.py:22; collectvCenterData.py:104-117 | match if the all-options `QueryOptions` returns `ads.timeout` (live) |
| Configuration\|Enable MOB | :261 | :23 | same; `config.vpxd.*` is the likeliest to be missing from the unnamed query (live) |
| Configuration\|Database\|Event retention (days) | :262 | :24 | match (`str(int)`) |
| Configuration\|Database\|Task Cleanup Enabled | :265 | :27 | match either way: `xsd:boolean` gives `True`/`False` in both, `xsd:string` `"true"` stays `true` in both |
| Configuration\|Runtime\|vCenter Unique ID | :266 | :28 | match |
| Configuration\|Log Level | :269 | :31 | match; blank `null` reg DV-37 |
| Configuration\|Patch Level | :293-294 | collectvCenterData.py:58-59 | match when set; `""` W1; absent `None` reg DV-13 |

### Datastore (all 9)

| Key | Java | Upstream | Result |
|---|---|---|---|
| Summary\|MOID | DatastoreCollector:204 | collect_ds_summary_properties.py:18 | match |
| Summary\|Status | :205 | :36 | match |
| Summary\|SIOC Enabled | :206 | :37 | match; unset (vSAN, vVol) `None` reg DV-08 |
| Summary\|SIOC Threshold | :207 | :38 | match; same |
| Summary\|Block Size | :214 | :21-23 | match (VMFS only, same condition) |
| Summary\|Max Blocks | :215 | :25-27 | match |
| Summary\|VMFS Upgradable | :216 | :29-31 | match |
| Identifiers\|Number of Extents | :209-212, 223 | collect_ds_identifiers_properties.py:24-28 | match on VMFS; NFS/vSAN/vVol `0` vs absent, reg DV-11 (visible on every non-VMFS datastore) |
| Identifiers\|Extent:{n}\|ID | :224-226 | :29-31 | match (1-based, diskName non-empty, order kept) |

## Honesty, scale and state

- **Failed or partial reads.** No path found that publishes a value on a failed read. Page failures discard
  the type (`simpleRead`, `hosts`, `vms` return null before anything is placed). A failed R-LIC/R-OPT/R-HOO
  withholds and counts. Gaps: W6 (all-fail reads as PARTIAL), W7 (missingSet faults counted as absent).
- **Container views.** Destroyed in `finally` on every path the reader controls (`PropertyReader.java:103-105`).
  Gaps: W4 (abandoned threads destroy late), W5 (tokens), N2 (destroy fault silent). After a relogin the old
  view dies with the old session.
- **Session under paging.** A NotAuthenticated mid-paging relogs once and retries the continuation with the
  old token. That fails, and the type fails honestly.
- **InvalidProperty.** Dropped and retried only while no row was handed out (`stats.rows == 0`), at most 20
  times, remembered per read label for the session, reported once. Correct.
- **LastPushed.** Value comparison is by 64-bit FNV-1a. A suppressed change needs a collision (about 2^-64
  per change) and is repaired by the 12th-cycle full refresh. A restart or reconfigure builds a new engine, so
  everything is sent. A clean-but-dropped push (a stale id) is resent at the latest on the next full refresh.
  A pruned object that comes back has no hash and is sent. Commit happens only on a clean push
  (`isClean` requires no skipped or dropped values). Sound; heap is W8.

## Copying (MIT port of a PolyForm Noncommercial original)

Nothing found that looks transcribed. The structure is original: pure collector functions over `BulkRow`,
`ObjectOut`, streamed `PathSpec` pruning, no `prop_dict`/try-per-function shape. Comments are new prose. No
upstream identifiers (`NULL_STATUS`, `guestInfoFields`, `pretty_type`, `hbaCount`) or log strings appear.
What carries over is facts needed for exact match: key strings, the Detailed Data regex, the four
controller display names, the HA/DRS suppression conditions. Expected and unavoidable.

## Only a live differential can settle

1. PCI id sign (E13): confirm upstream sends `-7f7a` for `0x8086`; the Java side then needs DV-33 made unconditional.
2. Whether Ops stores an empty string property (decides whether W1 is visible at all).
3. Whether one unnamed `QueryOptions` returns all ten settings, `config.vpxd.enableDebugBrowse` and `instance.id` in particular.
4. `about.patchLevel` and NIC `firmwareVersion`/`driverVersion` blank on the lab's vCenter and nested hosts.
5. Whether the VMware adapter models templates as VirtualMachine (decision 1), and how many vCenter objects count as not matched.
6. Bytes per page for R-VM (with extraConfig on), R-HOO at 25 hosts, and R-LIC per assignment against the 16 MiB caps (W9).
7. The VM Options probe with zero VMs, and InvalidProperty behaviour on the oldest supported vCenter (`guest.guestDetailedData`).
8. R43 landing as a numeric property and the history type Ops gives it.
9. B1 and B2 on real VMs (they are certain from the source; the differential will show them first).
10. The race in N8.

## Registration candidates (RULE-012)

If shipped as-is: B3 (one Options entry can blank every VM key) and W4 (zombie reads under a slow vCenter)
are the two likeliest to survive acceptance and hurt an operator. B1 and B2 are fixes to the register, not
to code.
