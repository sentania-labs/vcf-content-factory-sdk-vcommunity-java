# Review: branch `fix/upstream-bugs` (build 23)

- Reviewer: `sdk-adapter-reviewer`, static, read-only, no live instance.
- Delta: `e0d41ce..ba4bb8c`. Commits `36655d6` (Java, DV-109..DV-111) and `ba4bb8c` (content).
- Base `fix/nic-speed-degradation` (PR 3) is out of scope.
- Verdict: **CHANGES REQUESTED**. 3 BLOCKING, 9 WARNING, 9 NIT.

## Claims check (re-run independently)

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` (run on a scratch copy) | exit 0. KpiTest 1274 checks, DerivedTest, PropertiesTest, BcHandshakeTest all pass. WireBodyTest skipped (no `VCFCF_WIRE_CP`), same as CI. |
| `validate-sdk` | OK. Only the two SDK compat skips, which are environmental. |
| `build-sdk` (scratch copy, scratch output) | Built `0.0.0.23`. The `content/` tree is identical to the author's `dist/vcfcf_sdk_vcommunity.0.0.0.23.pak`. Only `adapters.zip` (jar, views.zip) and `overview.packed` differ, which fits build timestamps. Stock-id guard clean. |
| pak-compare | Skipped: no reference pak under `tmp/reference_paks`. It was not run, so it is not confirmed. |
| Bundled super metrics | **2**, not 3: `vsphere-clusters-not-green.yaml` is not bundled (known gap). The two widgets point at `sm_9008afb4…`, which the pak does not ship. Neither validate-sdk nor build-sdk said so (see W7). |
| `defect-gate --pak vcommunity-java` | "no open blocking defects affecting vcommunity-java". |

## Registry check (`knowledge/context/defects.md`)

No open defect has `Affects: vcommunity-java`. The open entries are DEF-004 (vcommunity-os), DEF-019 (factory:extractor), DEF-025 and DEF-026 (compliance), all out of scope. Registration candidates are listed at the end.

## BLOCKING

**B1. A missing quarantine value on 9.x now scores as a pass.**
- Where: `src/com/vcfcf/adapters/vcommunity/kpi/Availability.java:34-38`, fed by `ops/OpsReads.java:434-438` and `coll/ObjectKpis.java:371`.
- Authority: skill section *Unreadable is NOT compliant*, review dimension 1.
- The "does not exist" decision is made per host, per cycle, from one properties answer. Three cases come out the same: the property does not exist (8.18), the property exists but this host has no stored value, and the property was returned with an empty `values`/`data` array or a JSON null (`OpsReads` drops all of these at 436-438).
- So a 9.x host whose quarantine value is missing is scored 3 of 3 = 100. Before this change it scored 75. A host that is really in quarantine but whose value did not come back now reads fully healthy, and the cluster, vCenter worst-cluster and World figures inherit that.
- A failed read is still told apart correctly: a failed batch sets `propsFailed` and the value is withheld. That part was checked and is fine.
- Tests: none cover "the platform has the property, one host lacks it" or "the property came back with an empty array". The DerivedTest DV-110 case (`DerivedTest.java:805-826`) strips the property from every host, so it only proves the 8.18 path.
- Fix: decide platform absence once per cycle. If no host in the answer carries `runtime|quarantineState`, leave the condition out for every host. Otherwise a host that lacks it scores the condition as not met, as before. Add a test for one host lacking it while the others carry it (expect 75).

**B2. `docs/installing.md:300-304` contradicts the shipped alert.**
- Authority: review dimension 11 (docs that contradict behaviour are BLOCKING).
- The page still says the alert reads `vCommunity|Licensing:vSphere 8 Enterprise Plus for VCF|Remaining Days` and does not fire for any other license. Since `ba4bb8c` the seven symptoms match any license instance (instanced generic key).
- Fix: replace the paragraph. The alert now covers every license that has an expiration date, and its severity is the band of the license nearest to expiring.

**B3. `docs/overview.md:84` contradicts DV-111.**
- Authority: review dimension 11.
- The page says an unset value is published as "`0` for a count, a DRS score while DRS is off". Build 23 sends nothing for DRS off, for a negative score, and for an HBA or SCSI list that was not returned.
- Fix: name the DV-111 exceptions there. Also note that these three no longer follow `Quirks.UNSET_AS_UPSTREAM` (`overview.md:86`).

## WARNING

**W1. Design doc no longer matches the code.**
- `docs/design/vcommunity-java-port-design.md:916`: Q17 still reads as a reproduced quirk. It is fixed by `ba4bb8c` and should say so, the same way Q18 and Q19 do.
- `:886`: still lists DV-16 as "Superseded by quirk Q9". DV-111 brings DV-16 back.
- `:1005`: still says "three runtime properties, 25 each".
- `:884` (DV-111): the sentence "Collection Health counts the missing DRS score and counts only when a summary or DRS setting could not be read, and counts…" is garbled.
- Fix: correct all four rows.

**W2. DV-109 fixes one memory row and leaves the others version-dependent. Its stated effect claims more than it does.**
- Where: `kpi/ClusterRisk.java:57-71`, DV-109 at design `:882`, `CHANGELOG.md:8`.
- Measured in the validation dumps for the same tiered cluster `vcf-lab-mgmt-cl01`. The 9.0.2 instance has no `mem:DRAM` stat, so it falls back to `mem|host_provisioned` (both tiers). The 9.1.1 instance divides by DRAM alone.

  | Row | 9.0.2 | 9.1.1 |
  |---|---|---|
  | Worst ESXi Memory Ballooned | 2.36 | 4.73 |
  | Highest ESXi Memory Overcommit Ratio | 0.50 | 0.99 |
  | Highest Memory Monster VM to Host Ratio | 0.95 | 1.91 |

- Reservation and zip/swap follow the same path.
- So "stay on DRAM alone, as the original states" is only true where the DRAM stat exists. "Risk drops to what the memory really is" also overstates it: the monster row (1.91) and UB-5 latency still pin Risk on tiered clusters.
- Fix: get the owner's decision on DRAM-only for these five rows, and state the version dependence in DV-109, Q18 and the CHANGELOG.

**On whether the DV-109 evidence is enough (asked in the brief):** yes, enough to ship.
- Consumed above the DRAM tier proves the old divisor wrong.
- DRAM + tier equals `mem|host_provisioned` to within 1,600 KB.
- There is also a cross-version check the author did not cite. The new formula on 9.1.1 gives 116.48 × 131,784,480 / 263,568,960 = 58.24. The 9.0.2 instance publishes 58.23 for the same hosts. Cluster-wide gives 132.05 / 2 = 66.03 against 66.03.
- What is still unproven is the stock usage percentage, and the only hosts this was checked on have a 1:1 tier (the 4:1 and 3:1 wld clusters are not in the dumps).

**W3. DV-111 on an upgraded install: the old zeros stay.**
- Authority: `docs/overview.md:87-88`, "a key that is not sent keeps its last value".
- After upgrading to build 23:
  - every DRS-off cluster keeps its DRS Score of 0 as its latest value, until it ages out of each widget's window;
  - every host whose HBA list is not returned keeps the property "0" forever;
  - a cluster that turns DRS off keeps its last real score, which reads as well balanced.
- The DV-111 effect line ("no longer counts a DRS-off cluster as badly balanced") does not hold for latest-value consumers.
- Fix: say this in DV-111 and in the installing guide's upgrade notes.

**W4. `CHANGELOG.md:8` describes only the Java commit.**
- Authority: author hard rule 8, review dimension 9.
- None of the user-visible content changes in `ba4bb8c` have a line: the five package views, VM Memory Limit, the namespace Memory Limit, the license alert covering any license, and the new super metric with its two widgets.
- Fix: add a content line under 1.0.0.23.

**W5. The known super metric gap is wider than `adapter.yaml`.**
- Where: `adapter.yaml:54-56`.
- Besides listing the file under `bundled_content.supermetrics`, the fix must also update:
  - counts and the policy-enablement text: `docs/installing.md:261` and `:335-344`, `docs/overview.md:22`, `:181`, `:202`, `README.md:25`;
  - the design: `docs/design/vcommunity-java-port-design.md:1416`, `:1436`, `:1531`, `:1551`;
  - `docs/design/inputs/import-report/bundled_content.yaml:5-7`.
- The new super metric computes nothing until it is enabled in the policy on vSphere World, the same as the other two.
- Fix: list the file and update every count and enablement note.

**W6. `where="<75"` is an unverified form.**
- Where: `supermetrics/vsphere-clusters-not-green.yaml:7-10`.
- Authority: `vcfops-supermetric-dsl` skill (Where clauses); `reference/docs/vcf9/supermetrics.md` (documents `where=($value==1)`).
- The bare `"<75"` appears in neither, and the factory's own count super metrics use `where=($value==1)`.
- DEF-010 proves the upstream super metric of the same shape *computes*. It does not prove it *filters*: a count that ignores the clause would also compute.
- Fix: use `where=($value < 75)`, or prove the filter with a live preview against clusters whose scores are known.

**W7. Nothing catches a dashboard that charts a super metric the pak does not ship.**
- Where: `src/vcfcf_managementpacks/sdk_builder.py` (factory).
- `report-executive-summary-page-3.yaml:56` and `report-vsphere-compute-performance.yaml:144` chart `sm_9008afb4…`, which is not in the pak. Before this branch they charted `sm_dda97f70…`, which was not shipped either.
- validate-sdk, build-sdk and the content gate were silent both times.
- This is a factory tooling gap, not this pak's. Fix: give it to `tooling` as a content-gate check (dashboard super metric reference must be a bundled super metric or a declared external one).

**W8. The package views are empty as shipped, and the docs do not say so.**
- Authority: review dimension 11.
- `views/nenic-*`, `views/nfnic-*` and `views/vib-info.yaml` now read the right keys (`HostCollector.java:286-295`, and the vmbro 0.2.5 rename in `docs/design/inputs/vmbro_keys_missing_in_e1.tsv:54-60`).
- But `content/files/solutionconfig/esxi_packages.xml` ships with every entry commented out, and `nsx-host` is not even among its examples. All five views show no data until the operator adds `nenic`, `nfnic` and `nsx-host` to a copy of the file.
- Fix: one sentence in installing.md's content notes, and add `nsx-host` to the commented examples.

**W9. Design inputs still describe the old VM Memory Limit view.**
- Where: `docs/design/inputs/view_describe.tsv:55`, `docs/design/inputs/view_text_notes.md:15`.
- Both still say the view charts the CPU limit. A regeneration from these inputs would bring the wrong description back.
- Fix: update both. The new column (`config|memoryAllocation|limit`, unit `gb`) matches the vendor's own `preferredUnitId="gb"` for that key, and `mem|limit` is the documented Namespace key (`reference/docs/vcf9/metrics-properties.md`).

## NIT

**N1.** `Availability.java:14`: `POINTS_PER_CONDITION = 25.0` is no longer used anywhere, and it is now misleading. Remove it.

**N2.** Two comments are now wrong. `ClusterRisk.java:10-12` says the class gives "the original's value for every input", which is no longer true for consumed. `text/Quirks.java:57` says "DV-08 to DV-16", and DV-16 is back. Fix both.

**N3.** `KpiTest.java:532-541` and `DerivedTest.java:828-838` test the host R170 path with a 1:1 tier only, so a `2 × DRAM` bug would pass. (The cluster test at `KpiTest.java:650-659` does use a 2:1 tier.) Add a host case with wld01-esx01's 66,904,440 + 267,617,760 KB.

**N4.** `supermetrics/vsphere-clusters-not-green.yaml:1`: the header says "Converted offline by vcfcf_extractor import-local", but the formula was edited by hand. Its description carries upstream prose ("The values should match your operational expectation"), and `docs/design/inputs/carried-text-report.md` was not regenerated to count it. Fix the header and regenerate the report.

**N5.** The super metric counts only clusters that have a score. A cluster whose score is withheld (unreadable) is not counted, so it looks green. Upstream does the same. Say it in the description.

**N6.** License alert with several dated licenses: the severity bands **were checked and are correct**.
- With `instanced="true"`, each AND set fires when the largest Remaining Days is at or above its lower bound and the smallest is below its upper bound.
- So the fired sets run from the nearest-expiring license's band up to the farthest one's. Automatic severity takes the most severe, which is the nearest-expiring license. Perpetual licenses publish no Remaining Days (`HostCollector.java:316`).
- The rendered XML is well formed: `instanced="true" thresholdType="static" valueType="numeric"`, the same shape as VMware's `SymptomDefinition-VMWARE-GuestPartitionUsageCritical` (`guestfilesystem|percentage`).
- Residual: a license instance that stops being published (the product name changed) keeps its last value. Whether its symptom then stays active needs a live check.

**N7.** Two follow-ons for DV-109, for later. Once UB-3 is fixed, NVMe pressure would count twice in the Consumption Score, once in R181 (which now includes the tier) and once in R182. And the first Periodic window after the upgrade mixes the inflated and the fixed consumed values.

**N8.** `ClusterCollector.java:155` and `:176` say a negative `drsScore` is "vCenter's no score" without a source. Cite one or soften the wording.

**N9.** DV-110 infers that 8.18 has no quarantine property from the 75 scores. No 8.18 properties answer was recorded. Record one as a fixture, as `tests/fixtures/nic-properties/` does for DV-108.

## The "0 for unset" keys DV-111 left alone

- None of them is charted by shipped content. The same is true of DRS Score, HBA count and SCSI count, so the harm in all cases is to content operators build.
- R251 / R250 (Items Not Security Compliant / Not Stored Locally) are the closest to DRS in kind: an item that lacks the field is counted as a failure, so an ISO item shows as non-compliant.
- R151 (Number of Effective Hosts, 0 when not returned) can read as "no usable hosts".
- R233, R244 and R252 (sizes 0.0) understate totals.
- R47 (extents 0 on NFS) is a true value.
- R96 is cosmetic (Q5).
- None is as harmful as a 0 on the 0-100 DRS scale averaged across clusters. R251 is the one to take next.

## Registration candidates (RULE-012, if they survive acceptance unfixed)

- B1 (`vcommunity-java`).
- W7 (`factory:packaging`): the content gate does not check super metric references.
- W2 (`vcommunity-java`), if the owner leaves the five DRAM rows version-dependent without documenting it.

## If shipped as-is

- On 9.x, a host whose quarantine value is missing reads 100 % available, including one that really is in quarantine.
- The installing guide and the overview tell operators the license alert covers one name and that DRS-off clusters read 0. Both are false in this build.
- The Clusters-not-Green widgets chart a super metric the pak does not install.
