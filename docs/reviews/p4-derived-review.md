# P4 derived metrics review (build 10)

- Adapter: `vcfcf_vcommunity` (`vcf-content-factory-sdk-vcommunity-java`), branch `p4/derived`
- Diff reviewed: `4c87c96..176140e` (6 commits, 60 files, +7,415/-163), dev pak `dist/vcfcf_sdk_vcommunity.0.0.0.10.pak`
- Upstream compared: e1-rahabok vCommunity `v0.8.1`, read only via `git archive v0.8.1` into scratch
- Ground truth: `/mnt/holding/vcf-content-factory/backups/vcommunity-java-p3-2026-11-13/diff/baseline-python-devel_8_18-20261114T014525Z.json`
  (newer than the p2 dump; I checked both, and they agree on every structural check below)
- Reviewer: `sdk-adapter-reviewer`, 2026-11-13. Static review only: nothing installed, no live instance touched.
- Verdict: **CHANGES REQUESTED**: 2 BLOCKING, 3 WARNING, 7 NIT

The arithmetic is right. I checked all 48 KPI rows against upstream's own `Metric(...)` lines: 0 differences. I also
re-scored every KPI the Python baseline publishes from its own published rows. Both upstream's `kpi_score.py` and the
port's `KpiScorer` give the stored value, 11 of 11 KPIs and 32 of 32 `VM Guest OS KPI`. All of these match upstream
line by line:

- the populations
- the `>=` and `<` operators
- truncation, slicing and the order of the sums
- the Close for Business bands
- every key string

M1 and M2 hold. The baseline's own `Close for Business = 1` on `vcf-lab-mgmt-cl01` (Worst Performance 22.35,
Consumption 13.94) can only come from a red limit of 25, not 20.

There are two blockers:

- **B1.** The resource-pool counts take a different population from upstream. Any cluster running a Supervisor gets a
  different number, and the lab differential as set up cannot see it.
- **B2.** Upstream's comment prose was carried into the MIT source nearly word for word. `docs/NOTICE.md` says no source
  was copied.

## Independent re-runs

| Check | Result |
|---|---|
| `ci/run_java_tests.sh` at `176140e` | 3 serial runs: all `exit=0`, 177 `ok` lines each, BCJSSE included. No keytool or password failure in any run (the hex-password fix holds). Tree clean afterwards |
| coverage TSVs | `Coverage.verifyOrWrite`, `Coverage3.verifyOrWrite` and `Coverage4.verifyOrWrite` passed in all 3 runs, in verify mode, so `docs/p2-`, `p3-` and `p4-key-coverage.tsv` regenerate byte-identical. P4: 74 rows |
| `validate-sdk` | OK. The 9.1.1 SDK compat check was **skipped** (the jar is not in `adapter_runtime/`), as in P1 to P3 |
| `build-sdk` to scratch | builds `0.0.0.10`. Every entry, including the classes inside the nested jars, has the same CRC as the author's `dist/` pak, except `overview.packed` (a build stamp) |
| `pak-compare` vs `tmp/vcommunity_ref9/vcfcf_sdk_vcommunity.1.0.0.9.pak` | PASS: 0 BLOCKING, 2 WARNING (identifier count 19 vs 11; TraversalSpecKind 1 vs 0; both as in build 9), 279 INFO |
| KPI tables | All 48 rows of `KpiTable` parsed against every `Metric(...)` in upstream `metrics/**` at the tag: limits, skip-when-green and published flags all equal. The rows are 9 + 12 + 5 + 9 + 9 + 4 = 48, so **M2 is right** |
| baseline re-score | Rows read from the dump, rounded to 2 decimals. Upstream `kpi_score.kpi_score`: 11 of 11 KPIs equal the stored value. Java `KpiScorer.compute` (scratch harness compiled against `src/`): 11 of 11. `VM Guest OS KPI = round((VM + Guest)/2, 2)`: 32 of 32 |
| vim25 wire names | Checked against pyVmomi `_typeinfo_vim.py`: `CreateContainerView(container, type[], recursive)`, `HostSystem.parent`, `HostSystem.config.network.portgroup[].spec.name`, `VirtualMachine.runtime.host`, `Datacenter.networkFolder`, `ResourcePool.parent`, `DistributedVirtualPortgroup.name`. All correct, case included |
| Suite API names | Checked against `reference/docs/operations-api-8.18.json`: `stats/latest/query {resourceId, statKey}`, `stats/query {resourceId, statKey, begin, end, rollUpType, intervalType, intervalQuantifier}`, `properties/latest/query {resourceIds, propertyKeys}`, `GET resources/{id}/relationships?relationshipType`, `GET resources?adapterKind&resourceKind&page&pageSize`. All correct |
| defect registry | `knowledge/context/defects.md`: no open DEF has `Affects: vcommunity-java`. DEF-004 (`vcommunity-os`) is a different, retired pak |

## BLOCKING

### B1. Resource Pool counts (R218, R219) count Supervisor namespaces that upstream cannot see

- **Where:** `coll/Membership.java:322-347` (`poolCounts`), fed by `PropertyCollection.pools` (R-RP, every `ResourcePool` in vim25).
- **Authority:** design 4.3 and DV-35, which say "same values if E9 confirms; else fallback"; owner direction "match first".
- **What upstream does** (`metrics/cluster/resource_pool_counts.py`): it counts VCF Ops resources of kind
  `ResourcePool` and walks their `PARENT` relationships.
- **What Ops models:** each vSphere namespace is a VMWARE `Namespace` resource, not a `ResourcePool`. The baseline has
  13 `Namespace` resources on wld01, and one `ResourcePool` called `Namespaces`.
- **What vim25 holds:** each namespace is a `ResourcePool` object under the `Namespaces` pool. It carries the
  `ResourcePool.namespace` property (pyVmomi: `("namespace", "string", "vim.version.v7_0", F_OPTIONAL)`).

**Concrete input:** a cluster with a Supervisor and N namespaces.

| | Upstream | Java |
|---|---|---|
| Pools counted | the `Namespaces` pool only (level 1) | `Namespaces` (level 1) and the N namespace pools (level 2) |
| Number of Resource Pools | 1 more than the user pools | N more than upstream |
| Cascading Resource Pools | unchanged | N more than upstream |

In this lab that cluster is `wld01-cl01-supervisor`: 13 namespaces, so +13 on both keys. The pak's account is on the
mgmt vCenter only (the baseline has P4 keys only on mgmt objects), so the 24-hour differential cannot catch this and E9
would look green.

The baseline does confirm the simple case: `vcf-lab-mgmt-cl01` = 3/0 from three level-1 pools. That matches the
port's rule of not counting the cluster's root pool.

**Smallest fix:**

- Read `namespace` with `parent` in R-RP, and leave out a pool whose `namespace` is set, together with everything under
  it.
- Or use the DV-35 fallback (upstream's Ops `PARENT` walk) for any cluster that has a namespace pool.
- Register it under DV-35 and add an E9 case for a Supervisor cluster.
- Before calling E9 settled, add a wld01 account to the lab.

### B2. Upstream prose carried into the MIT source; `docs/NOTICE.md` says nothing was copied

- **Authority:** the brief says that facts (thresholds, key strings) are fine and that transcribed structure or prose is
  not. Upstream is PolyForm Noncommercial 1.0.0. The port is MIT. `docs/NOTICE.md` says "No source code of those
  projects is copied".
- **What I found:** the comments below are near-verbatim reshapes of upstream sentences.

| Java location | Upstream source (v0.8.1) |
|---|---|
| `kpi/KpiScorer.java:26` | `kpi_score.is_green` docstring (identical sentence) |
| `kpi/KpiScorer.java:51` | `_score`: "A tier with no width, such as green when it is a single value, holds no value" |
| `kpi/PeriodicWindow.java:53-54` | `this_cycle` docstring ("starts limit further along the list, wrapping around … No state is needed: the position comes from the time since the window end") |
| `kpi/Percentile.java:45` | `worst` docstring ("0 is the single worst sample") |
| `kpi/Availability.java:34` | `cluster_availability` docstring |
| `kpi/ClusterConsumption.java:43, 103` | `usable_percent` and `_add_up` docstrings ("0 is a real answer", "never a partial sum") |
| `coll/ObjectKpis.java:552, 556` | `collect_cluster_consumption_metrics` comments ("no VM runs, so nothing is consumed … whatever the rows say") |
| `coll/PeriodicStage.java:60, 80` | `read_periodic_cluster_check` comments |
| `coll/WorldStage.java:196, 218, 233, 247` | `vsphere_network_degradation`, `cluster_close_changes` comments (233 is word for word) |
| `coll/BusinessStage.java:148` | `business_app_performance` level-2 comment |

The code is also decomposed the way upstream's is:

- `ClusterConsumption`'s `hostIsGone`, `hostIsRunning`, `usablePercent`, `hasNoUsableCapacity`, `apply`, `addUp` and
  `consumed` map one to one onto `host_is_gone`, `host_is_running`, `usable_percent`, `has_no_usable_capacity`,
  `_apply`, `_add_up` and `_consumed`.
- `KpiScorer.compute` follows `publish_kpi`.
- `PeriodicWindow.isDue` and `thisCycle` follow `is_due` and `this_cycle`.

The order of the arithmetic has to stay the same, because the floating-point results must match. The prose and the
naming do not have to.

This blocks a release, not a dev install or the differential. The repository is private and `NOTICE.md` is still
marked PLACEHOLDER.

**Fix:**

- Rewrite those comments in the port's own words, citing design 7.1 or the map row rather than upstream's text.
- Name and group the helpers from the design's terms, keeping only the order of operations that the floating-point
  match needs.
- Re-check `NOTICE.md` against the result before any tag.

## WARNING

### W1. R211 (World Total Network Speed Degradation): an unregistered deviation

- **Where:** `coll/WorldStage.java:183-200`.
- **What upstream does** (`world/vsphere_network_degradation.py`): it returns early only when the latest-stats answer
  names **no** host (`if not stats`). Upstream's `_query_stats` makes an entry for every resource the answer names,
  even when the 10-minute filter leaves it no sample. So when hosts have a stored value that is older than 10 minutes,
  upstream publishes `degraded_count = 0`.
- **What Java does:** it publishes only when some host has a **fresh** value (`answered[0] == 0 → return`).
- **Concrete input:** hosts once had Network Speed Degradation; then NIC instance stats stop (Q15 suppresses the host
  values) or the pak stops for more than 10 minutes. Upstream publishes 0 on vSphere World every cycle from then on,
  because the latest query returns the old value however old it is. Java publishes nothing.
- **The lab:** it shows no R211 on the Python side because no host there ever had a value (Q15 holds on devel_8_18).
  That is consistent with Ops leaving out a resource that has no stored sample.

**Fix:** either reproduce it (count the hosts the sink is called for, not the fresh values) or register it as quirk
Q16 behind a seam. Either way, register it.

### W2. The heap test does not model the instance series the Suite API returns

- **Where:** `tests/.../coll/DerivedHeapTest.java:123-152`. Design 7.4 claims "peak 46 MB, 10 MB retained".
- **Why it matters:** upstream `utils/ops_stats.py:171-173` says the latest query also returns every instance of a
  requested stat (`cpu:26|utilization_average`). The fake answers only the exact keys.
- **What the port does with the answer:** `OpsReads.post` parses each batch answer into a whole DOM (`Json.parse`).
  The host statistics are one batch of up to 500 hosts:
  - per-thread `cpu:N|utilization_average`
  - per-core `cpu:N|coreUtilization_average`
  - six `net:vmnicN|*` per NIC, on top of 23 keys

  For 200 hosts with 128 threads that is roughly 50,000 series, about 10 MB of JSON and several times that as a DOM.
  VM batches also carry per-vCPU, per-disk and per-vNIC instances.
- **Status:** unmeasured. It depends on which instance metrics the Ops policy collects. Against design 9.3's 65 MB
  peak, it is a risk the test hides.

**Fix:**

- Add instance series to the fake (for example 128 cpu instances per host, 4 vmnics, 2 disks and a vNIC per VM) and
  re-measure.
- If it breaks the budget, lower the host batch (to 50 or 100) or stream the stat list.
- Add "instance series per batch" to the 9.4 scale run.

### W3. The World lists of clusters and hosts are never marked stale; the DV-87 effect is understated

- **Where:** `coll/WorldStage.java:79-80, 145-154`. Only the VM list calls `markStale` (`WorldStage.java:304`).
- **The premise:** the port's own reason for marking the VM list is that a read naming a VM that has gone can fail. If
  that is true, a cluster or host removed from **any** vCenter fails the cluster-summary, availability, network and
  Cluster Delta reads. R199 to R211 are then withheld for up to 12 cycles (1 hour), where upstream re-lists every cycle
  and publishes.
- **Second effect:** with two pak instances whose lists have different ages, both push the same World keys every cycle
  with different populations. The values alternate between two numbers for up to an hour. Upstream instances always
  agree, because they both list afresh.

**Fix:**

- Mark `ClusterComputeResource` and `HostSystem` stale on a failed World read, as is done for VMs.
- Add both effects to DV-87's "user-visible effect" column.
- Add a live experiment for what `stats/latest/query` does with an unknown id.

## NIT

- **N1** `coll/ObjectKpis.java:334-352`. The Q15 seam's test is not upstream's.
  - Java's "answered" means any of 23 host stats or 7 properties is non-empty after the age filter.
  - Upstream's means the answer for `sys|poweredOn` and the 3 condition properties names any host, stale or not.
  - With realistic data both are true (`runtime|connectionState` is always stored), so nothing visible today. Still,
    align the test or document the difference in Q15.
- **N2** `ops/OpsReads.java:381`. The relationships GET sends no `pageSize`.
  - A Tier with more children than the server's default page throws, and the Tier is withheld every cycle.
  - Upstream averages the first page.
  - Pass `pageSize`, or register the difference.
- **N3** `coll/Membership.java:222`, `coll/ObjectKpis.java:421`. One VM whose `runtime.host` is in a `missingSet` turns
  `vmsRead()` false.
  - That withholds every host and cluster Performance and Risk KPI of the vCenter. Upstream (placement from Ops) is
    unaffected.
  - Honest, but the blast radius is the whole vCenter. Note it under DV-91.
- **N4** `docs/design/vcommunity-java-port-design.md:1447` still says "the 49 limit rows" (M2).
- **N5** `coll/ObjectKpis.java:237-240`. When only the memory-share fallback statistics batch fails, **both** share keys
  are withheld.
  - Share per vCPU is withheld although its inputs (CPU shares and vCPUs from the properties) were read.
  - Upstream (a non-200 batch) still sends it.
  - Withhold per key.
- **N6** `kpi/ClusterRisk.java:94`. Unreachable with valid settings:
  - When `monsterMb == 0`, Java drops one row.
  - Upstream raises `ZeroDivisionError` and loses the whole Risk family for that cluster.
- **N7** `ops/OpsReads.java:185-195`. Unreachable on real answers: a non-numeric sample fails one batch in Java, while
  upstream's `float()` aborts the whole read.

## The questions the brief asked

### DV-88: withholding a KPI when not every row is known

Every path that sets `unknownRows > 0`, or withholds a whole family, is a **failed read**:

- `ObjectKpis.java:444, 486, 520, 525, 541, 593`:
  - a VM properties or statistics batch failed (`hostsWithUnknownVmProps`, `…Stats`)
  - a host statistics or properties batch failed
  - a cluster statistics batch failed
  - the vCenter VM or host read failed
  - a VM's host could not be read
  - a host's connection state could not be read
- A resource an answer leaves out, an empty answer, and a sample older than 10 minutes are not failures. In those cases
  Java scores over the rows it has (`publishKpi(..., 0)`), exactly as upstream does.

So in healthy cycles the differential shows nothing. In a cycle where a Suite API batch fails:

- Python publishes:
  - Cluster and ESXi Performance over the VMs that answered, with their 8 contention rows
  - Risk over the rows that need no statistics (the CPU monster row; upstream's own "failed stats" example)
  - availability with a lower N
- Java publishes:
  - the bad-packet row only
  - no Risk or Consumption at all
  - no availability for the affected hosts and clusters

That is the registered effect. No change is needed.

### DV-86, DV-89, DV-90

- **Stateless.** Upstream is stateless too: it reads the timestamps. The two match on:
  - the due test, a `max` with a default of 0, against `end` with `>=`
  - the window arithmetic in UTC: `floorDiv`, anchor minus one day, the same as `now.replace() - 1 day`. No daylight
    saving time is involved.
  - the rotation, `floor((now - end) / 300 s) * 500 mod due`
  - a restart, a reconfiguration, a skipped cycle or a failed push: still due next cycle, nothing published twice
- **Different times and decisions** come only from "now = cycle start":
  1. A cycle that starts before the window end but reads after it. Python publishes in that cycle and Java one cycle
     later, with the same window. The sample time differs by one interval. The KPI sample stamped at the cycle start
     can also land on the other side of the window edge from Python's, so the 5% worst value can move by one sample
     (registered in 7.4).
  2. The 10-minute age is measured from `T`, not from the read time `T + δ`. δ is the length of the property phase,
     which can be minutes at scale. A sample aged between `10 min` and `10 min + δ` at read time is kept by Java and
     dropped by Python. So Java can publish a row or KPI for an object whose VMware collection just stopped
     (registered in DV-90).
  3. Clock skew between the pak's collector and the VMware adapter's collector moves the staleness decision. It is the
     same exposure for both, and only live data can measure it.
- **DV-89 has no visible effect.**
  - Upstream computes a ghost cluster's check but publishes only inside the loop over vCenter clusters. So the ghost
    stays due in both, and both World Cluster Delta stages wait for the same window. Parity.
  - The only difference is that upstream reads the ghost's history every cycle and Java does not.

### DV-87 and multi-vCenter

- **Double counting:** none. Every roll-up reads the clusters, hosts, VMs, vCenters and Container objects of all
  vCenters, as upstream does. Neither side scopes them.
- **Other vCenters' objects:** included only where upstream includes them too.
- **Several adapter instances:** they all push the same World and Business keys, as upstream does (Q4).
- **The one new failure mode:** the list ages (W3).

### DV-91: membership by MoRef

- **vMotion and hosts moving between clusters:** Java sees the move one VMware cycle sooner. The host figures differ
  for that cycle (DV-03 and DV-04).
- **MoRef reuse across vCenters:** safe. Membership comes from this vCenter's reads, and ids from the map filtered by
  VCID.
- **The lab's ghost objects:** the dump has four HostSystem objects named `vcf-lab-osa-esx01` without a parent vCenter.
  They are not in this adapter instance, so neither side uses them.

### Honesty

I found no path where a failed batch, a deadline, an interrupt or a missing resource becomes 0, 100, "Open" or green.

- Close for Business needs all three histories.
- Availability is 0 only for a host that answered nothing after a successful read. That is upstream's definition and
  is kept by DV-21.
- Consumption 100 comes only from a usable capacity of exactly 0 that was actually read (5.3 #17).

**Q13:** upstream's `_query_stats` applies no `-1` filter to any key, so every statistic read through
`read_latest_stats` scores `-1` as a reading. The quirk is right, and it is on.

### Scale (10,000 VMs, 200 hosts, 20 clusters; per instance per cycle)

About 100 Suite API requests:

| Read | Requests |
|---|---|
| VM properties | 20 |
| Shares fallback | up to 20 |
| VM statistics | 20 |
| Hosts | 3 |
| Clusters | 2 |
| Periodic timestamps | 1 |
| World lists | 2 |
| World statistics | 4 |
| Cluster Delta timestamps | 1 |
| Port groups | 1 |
| Tools properties (all VMs, all vCenters) | 20 |
| Business lists | 3 |

Plus one relationships GET per Tier, Business Application, non-Tier child and Enterprise (unbounded, as upstream).

- **Payload:** at the exact keys, about 63,000 series. With instance series this is unmeasured (W2).
- **Compared with upstream:** lighter. Upstream reads VM properties three times and VM statistics three times per cycle.
- **Throttling:** HTTP 429 or a slow answer fails the batch and its figures are withheld. There are no retries.
- **Paging:** none needed. The latest and properties queries are not paged.
- **Deadline:** checked between batches.
- **What is unmeasured:** whether the derived phase, which runs after the property phase, still fits in the interval
  at scale.

## Per-row table (74 rows)

How to read it:

- **Baseline** is `baseline-python-devel_8_18-20261114T014525Z.json`, showing how many objects carry the key and up
  to three values.
- **"= baseline"** means the code is equivalent and every derivable input agrees, but the VMware inputs are not in the
  dump.
- **Verified** means recomputed from the dump itself.

| Row | Key (object) | Upstream v0.8.1 | Verdict | Baseline | Java predicted |
|---|---|---|---|---|---|
| R70 | Share per vCPU (VM) | `properties/vm/vm_shares.py` `per_unit`, `collect_vm_shares` | match (N5 on failure) | 45: 1000, 500, 1000 | = baseline |
| R71 | Share per MB of Memory (VM) | same | match | 45: 10, 10, 10 | = baseline |
| R153 | Cluster Performance Score | `cluster_performance_metrics.py` + `kpi_score.publish_kpi` | match, verified | 1: mgmt-cl01 57.56 | 57.56 (re-scored from rows) |
| R154 | Cluster Risk Score | `cluster_risk_metrics.py` | match, verified | 2: 53.69, 0.0 | 53.69, 0.0 |
| R155 | Cluster Consumption Score | `cluster_consumption_metrics.py` | match, verified | 2: 13.7, 5.22 | 13.7, 5.22 |
| R156 | ESXi Performance Score | `esxi_performance_metrics.py` | match, verified | 6: 27.59, 97.18, 84.62 | 6/6 equal |
| R157 | VM Performance Score | `vm_performance_metrics.py` | match (rows not in the dump) | 32: 99.01, 24.11, 95.81 | = baseline |
| R158 | Guest OS Performance Score | `guest_os_performance_metrics.py` | match | 29 | = baseline |
| R159 | Worst VM CPU Ready (cluster) | `contention_by_vms.worst_and_percentile` | match (cluster = max of its hosts, consistent) | 9.9 | 9.9 |
| R160 | Worst VM CPU Co-Stop | same | match | 2.34 | 2.34 |
| R161 | Worst VM Memory Latency | same | match | 0.06 | 0.06 |
| R162 | Worst VM Disk Latency | same | match | 128 | 128 |
| R163 | 90th Percentile VM CPU Ready | `kpi_score.percentile_90` | match | 1.65 | = baseline |
| R164 | 90th Percentile VM CPU Co-Stop | same | match | 0.05 | = baseline |
| R165 | 90th Percentile VM Memory Latency | same | match | 0.0 | = baseline |
| R166 | 90th Percentile VM Disk Latency | same | match | 16 | = baseline |
| R167 | Highest ESXi Bad Network Packets | `highest_host_bad_packet_pct`, `bad_packet_pct` | match | absent (the 6 counters are not all stored) | absent |
| R168 | Highest ESXi CPU Thread Utilization | `cluster_risk_metrics` row 1 | match | 23.91, 18.64 | = baseline |
| R169 | Worst ESXi CPU Imbalance | row 2 | match | 1.23, 1.0 | = baseline |
| R170 | Highest ESXi Memory Consumed | row 3 | match | 59.8, 32.2 | = baseline |
| R171 | Worst ESXi Memory Ballooned | row 4 | match | 1.32, 0.0 | = baseline |
| R172 | Worst ESXi Memory Zip Swap | row 5 (`swapout_average`) | match | 0.17, 0.0 | = baseline |
| R173 | Worst ESXi Memory Tiering Latency | row 6 | match | absent (no NVMe) | absent |
| R174 | Highest ESXi CPU Reservation | row 7 | match | 0.0, 0.0 | = baseline |
| R175 | Highest ESXi Memory Reservation | row 8 | match | 4.8, 0.0 | = baseline |
| R176 | Highest ESXi CPU Overcommit Ratio | row 9 | match | 2.38, 0.0 | = baseline |
| R177 | Highest ESXi Memory Overcommit Ratio | row 10 | match | 0.52, 0.0 | = baseline |
| R178 | Highest CPU Monster VM to Host Ratio | row 11 | match | 3.0 (mgmt only; osa has no VMs) | 3.0, absent on osa |
| R179 | Highest Memory Monster VM to Host Ratio | row 12 | match | 0.95 | = baseline |
| R180 | Cluster-wide ESXi CPU Thread Utilization | `cpu_thread_utilization` | match | 24.34, 14.9 | = baseline |
| R181 | Cluster-wide ESXi Memory Consumed | `memory_consumed` | match | 67.62, 32.07 | = baseline |
| R182 | Cluster Memory Tiering Consumption | `memory_tiering_consumption` | match | absent | absent |
| R183 | Cluster-wide ESXi Memory Ballooned | `memory_ballooned` | match | 0.33, 0.0 | = baseline |
| R184 | Cluster-wide ESXi Memory Zip Swap | `memory_zip_swap` | match | 0.05, 0.0 | = baseline |
| R185 | ESXi Worst VM CPU Ready | `esxi_performance_values` | match (connected hosts only, in both) | 6: 9.9, 0.4, 1.65 | = baseline |
| R186 | ESXi Worst VM CPU Co-Stop | same | match | 6 | = baseline |
| R187 | ESXi Worst VM Memory Latency | same | match | 6 | = baseline |
| R188 | ESXi Worst VM Disk Latency | same | match | 6 | = baseline |
| R189 | ESXi 90th Percentile CPU Ready | same | match (fewer than 10 VMs: equals worst, consistent) | 6 | = baseline |
| R190 | ESXi 90th Percentile Co-Stop | same | match | 6 | = baseline |
| R191 | ESXi 90th Percentile Memory Latency | same | match | 6 | = baseline |
| R192 | ESXi 90th Percentile Disk Latency | same | match | 6 | = baseline |
| R193 | ESXi Bad Network Packets | `bad_packet_pct` | match | absent | absent |
| R194 | VM Memory Zip Swap | `memory_zip_swap_pct` | match | 29: 0.0 | = baseline |
| R195 | ESXi Host Operational Availability | `host_availability.py` | match | 9: 75 everywhere | 75 |
| R196 | Network Speed Degradation (host) | `host_network_speed.py` | match (Q15 holds in the lab) | absent | absent |
| R197 | Total Network Speed Degradation (Datacenter) | `datacenter_network_degradation.py` | match (Q12, Q15) | absent | absent |
| R198 | Number of Port Groups (World) | `world/port_group_counts.py` | match | 18 | 18 (sum of one vCenter at 18) |
| R199 | Cluster Delta Close Count | `world/cluster_close_changes.py` | match | 0 | = baseline (once per window) |
| R200 | Cluster Delta Open Count | same | match | 0 | = baseline |
| R201 | Number of VM without Tools | `world/vm_tools_counts.py` | match | 2 | = baseline |
| R202 | Number of VMware Tools down | same | match | 1 | = baseline |
| R203 | Worst 10 Percent Cluster Performance | `world/cluster_summary_metrics.py` | match (one cluster, p10 = min) | 30.7 | previous cycle's mgmt score |
| R204 | Worst Cluster Performance | same | match | 30.7 | same |
| R205 | Worst 10 Percent Cluster Risk | same | match (2 clusters, p90 = max) | 54.1 | same |
| R206 | Worst Cluster Risk | same | match | 54.1 | same |
| R207 | Highest 10 Percent Cluster Utilization | same | match | 13.79 | same |
| R208 | Highest Cluster Utilization | same | match | 13.79 | same |
| R209 | World Worst Cluster Operational Availability | `world/availability_summary_metrics.py` | match | 75 | min(75, 75) = 75 |
| R210 | World ESXi Hosts Operational Availability | same | match | 75 | average of the fresh host values = 75 |
| R211 | World Total Network Speed Degradation | `world/vsphere_network_degradation.py` | **mismatch on stale-only input (W1)** | absent | absent here; differs once host values exist and then go stale |
| R212 | vCenter Worst Cluster Operational Availability | `vcenter/vcenter_availability.py` | match | 75 | 75 |
| R213 | vCenter Number of Port Groups | `vcenter/vcenter_port_groups.py` | match | 18 | not derivable from the dump |
| R214 | VM Guest OS KPI | `vm/vm_guest_os_kpi.py` | match, verified | 32: 99.02, 32.52, 82.84 | 32/32 equal |
| R215 | Tier Performance | `container/business_app_performance.py` | match | absent (no Containers in the lab) | absent |
| R216 | Business Application Performance | same (Q14) | match | absent | absent |
| R217 | Business Applications Performance | same | match | absent | absent |
| R218 | Number of Resource Pools | `cluster/resource_pool_counts.py` | **mismatch on Supervisor clusters (B1)**, match otherwise | 3, 0 | 3, 0 here; +N on a Supervisor cluster |
| R219 | Cascading Resource Pools | same | **mismatch on Supervisor clusters (B1)** | 0, 0 | 0, 0 here; +N on a Supervisor cluster |
| R221 | Cluster Operational Availability | `cluster/cluster_availability.py` | match | 75, 75 | 75, 75 |
| R222 | Periodic Worst Cluster Performance | `cluster/periodic_cluster_check.py` | match (history not in the dump) | 22.35 | = baseline for the same history |
| R223 | Periodic Worst Cluster Risk | same | match | 54.5, 0.0 | = baseline |
| R224 | Periodic Worst Cluster Consumption | same | match | 13.94, 5.72 | = baseline |
| R225 | Close for Business | same (bands 25/50/75) | match, verified | 1 (mgmt); absent on osa (no Performance) | decide(22.35, 54.5, 13.94) = 1; absent on osa |

Rows observed in the baseline: 64 of 74. The other 10 are absent on the Python side too, and the port predicts absent.

## What only a 24-hour live differential can settle

1. **Timing.**
   - The periodic and Cluster Delta sample times: Java's cycle start against Python's mid-cycle time.
   - Which window edge the KPI sample at the boundary falls on.
   - The 10-minute age decisions inside δ.
   - Clock skew between collectors.
2. **Instance series.** Whether the latest query returns them (W2), and why `net|speed` instances are absent on
   devel_8_18 (Q15 holds).
3. **E9 for the simple pool trees.** The namespace case needs an account on wld01 (B1).
4. **Unknown ids.** What the Suite API does with an id that no longer exists in a latest stats or properties query (W3).
5. **Side by side.** Python and Java write the same keys on the same VMware objects. If both run at once, every
   read-back family (World, Business, Periodic, Delta) reads the other pak's pushes. Run them alternately, or compare
   only the families that need no read-back.
6. **Real data for the empty families.** Business Applications and NVMe tiering, which the lab has none of.
7. **Scale.** Whether the derived phase still fits after the property phase on a large vCenter.

## Registry check

There is no open defect whose `Affects:` names this pak. Registration candidates if they are not fixed before
acceptance:

- B1 (wrong pool counts on Supervisor clusters)
- W1 (unregistered R211 deviation)
