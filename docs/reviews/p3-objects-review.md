# P3 own objects and P2 live fixes review (builds 7 and 8)

- Adapter: `vcfcf_vcommunity` (`vcf-content-factory-sdk-vcommunity-java`), branch `p3/objects`
- Diff reviewed: `9201363..d725121` (10 commits, 48 files), dev pak `dist/vcfcf_sdk_vcommunity.0.0.0.8.pak`
- Upstream compared: e1-rahabok vCommunity `v0.8.1`, read only with `git archive v0.8.1` into scratch (no checkout of the clone)
- Ground truth: `/mnt/holding/vcf-content-factory/backups/vcommunity-java-p2-2026-11-13/diff/` (Python baseline
  `baseline-python-devel_8_18-20261113T235553Z.json`, `patterns-devel_9_1.tsv`)
- Reviewer: `sdk-adapter-reviewer`, 2026-11-13, static only (nothing installed, no live instance touched)
- Verdict: **CHANGES REQUESTED**: 6 BLOCKING, 6 WARNING, 7 NIT

Against real lab data the new code gets the values right. Every one of the 26 P3 rows and the 30 Q9
keys I sampled from the Python baseline comes out as the same string (table below). The library total
is a good example: upstream sums raw bytes and then rounds, and the port does the same (the rounded
item sizes add up to 162.57; the baseline says 162.58).

The blocking items are the ones the brief asked me to hunt. In four places a read that **failed**
still gets the Q9 placeholder. The guard (`BulkRow.readable` / `isUnset`) is applied only where a
key reads its own path, and these four do not:

- cluster DRS
- host ATS options
- install date
- the template figures and texts

The fifth: moving the cycle into `enumerateResources` (DV-78) silently loses "unreachable vCenter
shows the account DOWN" once own objects exist. The sixth: the shipped `docs/overview.md` still
says the opposite of what Q9 and Q10 now do.

## Independent re-runs

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` at `d725121` | 6 serial runs, all pass, 134 `ok` lines each, BCJSSE included; tree clean after (`git status` empty) |
| flake hunt | 12 more full runs in 4 parallel copies (4 cores) and 12 runs of `PropertiesTest` + `OwnObjectsTest` with 6 CPU burners: 30/30 green. **Not reproduced**; see N7 for the candidates |
| coverage TSVs | `Coverage.verifyOrWrite` and `Coverage3.verifyOrWrite` passed in every run and the tree stayed clean, so `docs/p2-key-coverage.tsv` and `docs/p3-key-coverage.tsv` regenerate byte-identical. 26 P3 rows `yes` |
| `validate-sdk` | OK. 8.18.7 and 9.1.1 SDK compat checks **skipped** (jars not in `adapter_runtime/`), as in P1 and P2 |
| `build-sdk` to scratch | builds `0.0.0.8`; every class in `vcfcf_vcommunity.jar` and `lib/vcfcf-adapter-base.jar` is byte-identical to the author's `dist/` pak |
| `pak-compare` vs `tmp/vcommunity_ref9/vcfcf_sdk_vcommunity.1.0.0.9.pak` | PASS, 0 BLOCKING / 2 WARNING (identifier count 19 vs 11, expected; TraversalSpecKind 1 vs 0, the new spec) / 279 INFO, the same for the rebuild and the author's pak |
| wire names | checked against pyVmomi's `_typeinfo_vim.py` on this host: `installDate` and `fetchSoftwarePackages` are both lower case on the wire, both need `Host.Config.Image`; `LicenseManager` derives from `vmodl.ManagedObject` (no `name`) and has `licenseAssignmentManager`; `QueryAssignedLicenses` is correct |

## Registry check (`knowledge/context/defects.md`)

No defect, open or closed, has `Affects: vcommunity-java`. The open ones are DEF-004 (`vcommunity-os`),
DEF-019 (`factory:extractor`), DEF-025 and DEF-026 (`compliance`); none touches this pak. Registration
candidates if they survive acceptance: B1 to B5 (a failed read published as a value; the account not going DOWN).

## BLOCKING

### B1. Cluster DRS: a failed `configuration.drsConfig` read publishes four `null`s and a DRS Score of 0

- `coll/ClusterCollector.java:65` passes `row.val("configuration.drsConfig")` to `drs()`. On a missing-set
  fault, or an InvalidProperty drop, that value is null, so `:112` makes `on = false`. Then `:129-132` publish
  `Quirks.orNull(null)` = `"null"` for Proactive DRS, Scale Descendants Shares, CPU Over-Commitment and
  Virtual Machine Automation Enabled. `drsScore` (`:169`) sends `0.0` because `!drsOn`. `bad` only looks at
  `configurationEx`, and nothing calls `row.isUnset("configuration.drsConfig")`. `ha()` right above does
  this correctly (`:82`).
- Failing input: a cluster row whose `missingSet` names `configuration.drsConfig` (NoPermission), with
  `configurationEx.proactiveDrsConfig` returned. Today's result: `DRS Score` 0.0 and four `"null"`. It should
  be nothing, counted.
- Authority: design 5.8 Q9 ("A value whose READ FAILED is never one of these"); skill § *Unreadable is NOT
  compliant*; brief ("a fabricated 0 or null from a failed read is blocking").
- Fix: `boolean drsRead = drsCfg != null || row.isUnset("configuration.drsConfig");` and, when it is false,
  withhold all five DRS keys.

### B2. Host ATS Heartbeat / ATS Locking: a failed option read publishes `null`

- `coll/PropertyCollection.java:469-474` builds a map for every R-HOO row from `Vals.items(row.val("config.option"))`.
  On a missing-set fault that is an empty map. `HostCollector.java:165-166` then publishes
  `Quirks.orNull(options.get(...))` = `"null"`. `read()` counts the missing set as a failure, so the value
  is counted **and** fabricated.
- A second way in: a host that disconnects between R-HO (connected) and R-HOO. `config` is then unset,
  so the result is `"null"` on a host upstream would skip.
- Failing input: R-HOO row for `host-12` with `missingSet` `config.option`. Today's result: `VAAI|ATS Heartbeat` = `"null"`.
- Fix: in the sink, `if (!row.readable("config.option")) return;`, which leaves no row, already counted as
  "returned no row".

### B3. Install Date: a host whose image manager could not be read publishes `null`

- `coll/PropertyCollection.java:449`: `installDateRead = !imageMgr.containsKey(host) || answered`. A host is
  missing from `imageMgr` both when `configManager.imageConfigManager` is unset (upstream's `null`, correct)
  and when that path failed (missing set, or dropped as invalid). `HostCollector.java:158` then sends `"null"`.
- Failing input: R-HO row with `missingSet` `configManager.imageConfigManager`. Today's result: `Install Date|UTC` = `"null"`.
- Fix: `installDateRead = row.isUnset("configManager.imageConfigManager") || answered`. Record the
  unset-ness per host while the R-HO rows stream, as `imageMgr` is filled.

### B4. VM Template: every figure and text is filled from a failed read (`0`, `0.0`, `""`)

- `coll/TemplateScan.java:65-78` keeps `Vals.scalar(...)` / `row.val(...)` and drops whether the path was
  readable. `TemplateCollector.java:88` (`vCPUs` `"0"`), `:97-99` (`Memory` `"0.0"`), `:106` (`Disk Used` 0.0),
  `:121` and `:138` (Datastore / VM Folder `""`), and the Q8 texts at `:86-87` (`""`) cannot tell unset from
  failed.
- So a missing set (or an InvalidProperty drop) on `summary.config.numCpu`, `summary.config.memorySizeMB`,
  `summary.storage.committed`, `config.guestFullName`, `config.version`, `datastore` or `parent` publishes
  upstream's default. Design 4.11 / Q8 say that case must send nothing.
- `templateReadFailures` covers only the datastore and folder **name** reads, not the VM paths.
- Failing input: a template row with `missingSet` `summary.storage.committed` (fault) and `summary.config.numCpu`.
  Today's result: `Disk Used (GB)` 0.0, `vCPUs` `"0"`.
- Fix: in `TemplateScan.take`, store `null` vs "unset" per field from `row.readable(path)` / `row.isUnset(path)`
  (for example a `Boolean` for read-ok next to each value). In `TemplateCollector`, use the placeholder only
  when the field was unset.

### B5. DV-78: once own objects exist, an unreachable vCenter (failed login) no longer shows the account DOWN

- `VCommunityAdapter.java:578-590` `collectOwn` returns normally when the cycle failed (`rec.result == null`,
  or the object is absent because the login failed). The framework then sets DATA_RECEIVING on every own object
  (`VcfCfAdapter.onCollect`, the status after `collector.collect`). Only the collector object throws.
- `adapterInstanceStatus` (`VcfCfAdapter.java:3609`) raises the instance only when
  `failed == attempted`. In the lab that is 1 of 34 attempted, so the account stays green.
- The 33 own objects also show "collecting" while nothing is read. Design 2.3 (`:185-187`) put the collector
  anchor in place precisely so that "an unreachable vCenter could show DOWN"; the class javadoc still promises it.
- Failing input: vCenter down, 1 library, 22 items, 10 templates known. The collector throws a
  `Reachability`-DOWN IOException; 33 objects return nothing. The account is not DOWN, and the 33 are DATA_RECEIVING.
- Fix: in `collectOwn`, when `rec == null`, or `rec.result == null`, or `rec.result.failure != null`, throw
  the same exception the collector throws (so `mapCollectException` gives DOWN for all). Consider also throwing
  (NO_DATA) when the object's kind was not read this cycle and the object is absent.

### B6. Shipped docs contradict the behaviour: `docs/overview.md:69-75`

- "A value that is not there is not sent. An unset property, a switched-off feature (HA, DRS, EVC), a link
  that is down ...: the key is left out, where the original pack sent `None`, `null`, `Unknown`, `N/A`, `0`
  or `half-duplex`." Build 8 sends exactly those (Q9, Q10).
- `:63-66` ("A failed read sends nothing ... never replaced by a zero, `Unknown` or any other placeholder")
  is the promise B1 to B4 break.
- Authority: review dimension 11 (docs that contradict behaviour are BLOCKING).
- Fix: rewrite the bullet around Q9/Q10 (an unset value is published as upstream prints it; a failed
  read is not), and name the switch.

## WARNING

- **W1. `fetchSoftwarePackages` has the same wire-name bug the build fixed for `installDate`**
  (`coll/PropertyCollection.java:564-566` sends `FetchSoftwarePackages`). pyVmomi:
  `("fetchSoftwarePackages", "fetchSoftwarePackages", "vim.version.version11", ...)`. vCenter will refuse
  it on every host, so R40-R46 (Packages) are never collected when the ESXi VIB list is set. The lab
  baseline has no package keys, which is why the differential did not show it. Fix: lower-case the element
  and the operation label, and fix the fixture that accepts the capitalised name.
- **W2. Stale internal contracts.** These still say what Q9 reversed:
  - the class javadoc of `TemplateCollector.java:23-24` and `LibraryCollector.java:22-26` ("not sent as the
    zero ... DV-11, DV-18")
  - `Quirks.java:43-45`
  - design 4.11 `:564-570`
  - design 5.3 rows 4, 10 and 11
  - design 5.7 DV-17 ("Figures stay withheld") and DV-80 ("Total Size also withheld when any item has no size")
  - design 2.3 `:185` ("the whole cycle runs in its `collect()`")

  The 5.7 trailer supersedes them in one sentence, but anyone reading a row gets the wrong rule. Fix:
  mark each row superseded inline.
- **W3. A narrowed role deletes history.** A read-only change to the account's content-library rights makes
  `GET /api/content/library` answer `[]` (only visible libraries are listed). The same goes for losing VM
  folder rights for templates. That is a "successful empty read" (`LibraryCollector.java:84-85`), so after 12
  cycles every library and item is marked NOT_EXISTING, and deleted after retention. Upstream never deletes.
  This is inherent to DV-79, but DV-79 and overview.md name only "vCenter no longer has it". Fix: say so in
  DV-79 / overview.md. Optionally, do not count a whole kind going from N>0 to 0 without the owner's say.
- **W4. Library counts and duplicate list entries.** `LibraryReader.idList` (`:321-330`) de-duplicates item
  ids. Upstream's `len(items)` counts a repeated id twice (and publishes the object twice). That is a theoretical
  mismatch on R249 only; register it or drop the de-duplication for the count.
- **W5. Benign races count as failures.** An item deleted between the item list and its GET answers 404.
  That becomes `F-REST-ITEM`, the library's four counts are withheld, and `Library Item` is not read in full
  that cycle. This is honest, but in a busy library it is a WARN line and PARTIAL often. Consider treating
  404 on an item GET as "gone" (no object, not a failure; the kind is still read in full).
- **W6. Install date and packages need `Host.Config.Image`** (pyVmomi privilege column). With a read-only
  service account, every connected host every cycle is an `F-HOST-INSTALLDATE` failure, so the health
  stays PARTIAL with permanent warnings. Upstream logs at debug and sends `"null"` (DV-10 keeps this
  withheld). Fix: name the privilege in REFERENCE/installing, or count it once per cycle.

## NIT

- N1. `HostCollector.java:134-135`: `dnsConfig` returned but `dhcp` unset gives `"null"`. Upstream gives `"None"`
  (`getattr(dnsConfig, "dhcp", "null")` finds the attribute, value None). The field is required, so this
  practically never happens.
- N2. `PyText.json` returns null for a JSON object or list, so Q8 publishes `""` where upstream publishes
  `str(dict)`. No current field is an object.
- N3. `LibraryReader.read` (`:81-84`): `shutdownNow` does not wait, so a worker already inside a GET can outlive
  the read (at most the 60 s read timeout) and is cut by the logout. Harmless; a short `awaitTermination` would
  make the "one session per cycle" claim exact.
- N4. `VCommunityAdapter.onCycleStart` nulls `cycle`. Two `onCollect` calls on one instance overlapping (not
  expected from the platform) would make the first one's collector report "the collection cycle did not run".
- N5. A library with no items left keeps its last links until the items are marked and deleted (4.11.1 says
  so). This is visible in the tree for 12 cycles plus retention.
- N6. `rec.aborted` makes the collector return normally (DATA_RECEIVING) on a stop-before-cycle. Cosmetic.
- N7. The flake was not reproduced in 30 runs. Candidates, from most to least likely:
  - `PropertiesTest.java:1429-1438` asserts Cancel/DestroyView happened inside a fixed `Thread.sleep(4000)`
    after a 2.5 s server delay (real time, pre-P3).
  - `PropertiesTest.deadline` (`:1214`) relies on a 6000 ms delay versus a 1500 ms deadline.
  - `OwnObjectsTest` `largeCounts` has `ms < 60_000` (observed max 4.7 s).

  The deadline and session scenarios of `OwnObjectsTest` reason out as robust: 6 items on 4 workers, and
  the counters are concurrent. If it recurs, keep the failing log line. Without it the cause is a guess.

## Sampled keys (baseline value, predicted Java value)

Prediction = the Java code path traced on the lab condition the baseline implies (all reads succeed).

### P3 rows (26)

| Row | Object (baseline) | Baseline | Java | |
|---|---|---|---|---|
| R227 Guest OS | VM Template vcf-services-platform-template-9.0.2.0.25068117 | VMware Photon OS (64-bit) | same (`config.guestFullName` text) | match |
| R228 Hardware Version | same | vmx-21 | vmx-21 | match |
| R229 vCPUs | same | 4 | 4 | match |
| R230 Memory (GB) | same | 8.0 | `repr(round2(8192/1024))` = 8.0 | match |
| R231 Datastore | same | vcf-lab-mgmt-cl01-vsan | join of R-DS names in VM order | match |
| R232 VM Folder | same | vcf-management-services | R-FO name of `parent` | match |
| R233 Disk Used (GB) | same | 8.58 (float32 8.5799999) | `PyRound.gib(committed)` 8.58 | match |
| R234 Type | Library Item centos-stream9 | ovf | ovf | match |
| R235 Description | centos-stream9 / rhel-9.8-x86_64-dvd | Packer imported ... / "" | same / "" (absent or empty) | match |
| R236 Version | centos-stream9 | 1 | 1 (JSON string) | match |
| R237 Content Version | centos-stream9 | 3 | 3 | match |
| R238 Stored Locally | centos-stream9 | True | `json(true)` = True | match |
| R239 Security Compliant | centos-stream9 | True | True | match |
| R240 Certificate Verification | centos-stream9 / rhel-9.8 | INTERNAL / NOT_AVAILABLE | `certificate_verification_info.status` | match |
| R241 Last Modified | centos-stream9 | 2026-11-01T09:01:58.636Z | JSON string as is | match |
| R242 Last Sync | every item | "" | Q8 "" | match |
| R243 Source Item ID | every item | "" | Q8 "" | match |
| R244 Size (GB) | centos-stream9 | 2.07 | `gib(size)` | match |
| R245 Type | Content Library vcf-lab-mgmt-contentlibrary | LOCAL | LOCAL | match |
| R246 Subscription URL | same | "" | no `subscription_info`: "" | match |
| R247 Automatic Sync | same | "" | "" | match |
| R248 State | same | ACTIVE | `state_info.state` | match |
| R249 Items | same | 22.0 | 22 (all 22 read; else withheld, DV-80) | match |
| R250 Items Not Stored Locally | same | 0.0 | 0 | match |
| R251 Items Not Security Compliant | same | 0.0 | 0 | match |
| R252 Total Size (GB) | same | 162.58 | sum of bytes, then round: 162.58 (sum of rounded sizes would be 162.57) | match |

Identity: the baseline's three template name pairs (for example `...-9.1.1.0.25714471` in vcf-automation and
vcf-management-services) are two objects each in Java (MoRef + VCID). Match.

### Q9 / Q10 / fixed keys (30)

| Key (kind, object) | Baseline | Java (unset on a read that worked) | |
|---|---|---|---|
| DRS CPU Over-Commitment (Cluster mgmt-cl01, osa-probe-cl01) | null, null | DRS on without the option / off: `"null"` | match |
| DRS Score (osa-probe-cl01, DRS off) | 0.0 | `zero` | match |
| Proactive DRS (osa-probe-cl01) | null | `"null"` | match |
| Scale Descendants Shares (osa-probe-cl01) | null | `"null"` | match |
| VM Automation Enabled (osa-probe-cl01) | null | `"null"` | match |
| EVC Mode (both clusters) | null | summary read, no mode: `"null"` | match |
| HA Host Monitoring (osa-probe-cl01, HA off) | null | `off`: `"null"` | match |
| HA Response \ Host Isolation | null | `"null"` | match |
| HA Response \ Default VM Restart Priority | null | `"null"` | match |
| HA Response \ Datastore APD / PDL | null / null | `"null"` | match |
| HA VM Monitoring / Heartbeat Datastore | null / null | `"null"` | match |
| HA Failure Interval / Max Failure Window / Max Failures / Minimum Up Time | null x4 | `"null"` | match |
| Number of Extents (vsanDatastore and 2 more) | 0 | not VMFS, info read: `"0"` | match |
| BIOS Vendor (3 hosts, e.g. mgmt-esx02) | None | `scalarOrNone`: `"None"` | match |
| NIC Duplex (11 NICs on 6 hosts) | half-duplex | Q10: `"half-duplex"` | match |
| Install Date UTC (9 hosts) | 2026-02-02T07:22:17+00:00 | `installDate` now answered; `isoUtc` gives no microseconds when zero | match (needs `Host.Config.Image`, W6) |
| Licensing Name (9 hosts) | VMware Cloud Foundation (cores) | fixed R-LIC: same | match |
| Licensing Edition Key | esx.vcf.entitlement.cpuCoreMin | same | match |
| Licensing License Key | SUBSC-00000-00000-00000-RIBED | `*****-*****-*****-*****-RIBED` | **mismatch, registered DV-25** |
| Licensing Expiration Date | 2027-04-24 00:00:00+00:00 | `PyText.dateTime` | match |
| Licensing Remaining Days | 161.0 | `floorDiv` days | match (time-dependent) |
| vCenter managed address (vCenter) | null | blank setting: `"null"` | match |
| VM Hardware Upgrade Target Version (45 VMs) | None | `"None"` | match |
| VM NUMA Hot Add Exposed (10) | None | `"None"` | match |
| VM Reboot PowerOff (10) | None | `"None"` | match |
| VM Guest Application Status (11) | None | `"None"` | match |
| VM Guest Customization Status (11) | null | unset: `"null"` | match |
| VM Detailed Data Build Number / CPE / Distro Add. / Name / Version / Kernel / Pretty (15-45) | null | unset or no match: `"null"` | match |
| VM Kernel Crash State / DAS protection / Min Required EVC Mode Key | None | `"None"` | match |
| VM Op Notification Timeout (45) | 0 | unset: `"0"` | match |

Under a **failed** read, the DRS, ATS, Install Date and template rows above become fabricated values (B1 to B4).
The rest are guarded and send nothing.

## What only the next live differential can settle

- Whether any lab object hits a missing set on the B1 to B4 paths (expected: none, which is why B1 to B4 would
  not show in the differential; they are latent).
- E-N2: same-cycle metric data for a just-registered resource (DV-78 lag), and E-N3: START of a returned object
  and the deletion after 12 cycles.
- The account and objects status during a vCenter outage (B5): take vCenter (or the account's network)
  away for two cycles after the fix.
- That `Content Library > Library Item` renders in the tree and the traversal spec root is reachable; rename
  propagation (DV-82) on a library and a template.
- The packages call after W1 (needs an ESXi VIB list on the account) and the install date with the lab
  account's privileges.
- E-L1: how often an item lacks `cached` / `security_compliance` (now counted as upstream does).

## Copying (MIT port vs PolyForm upstream)

No transcription found. The REST reader (stages, pool, isolation), the library and template collectors and
the quirk seams are structured differently from `collectLibraryData.py` / `collectTemplateData.py`, and the
comments are original. What is shared is behaviour the match requires: key strings, the `round(x / 1024**3, 2)`
arithmetic, and the Q6 regex `(\w+)=['"](.*?)['"]` (functional, 20 characters, already registered).
