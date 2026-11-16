# Upstream bug audit: defects the port carries over (2026-11-16)

Scope: upstream e1-rahabok 0.8.1 (`git archive v0.8.1 "Management Pack/app"` from the `rahabok` remote of
`content/sdk-adapters/vcommunity`) compared with the Java port at its current working tree. Read-only static audit.
Evidence comes from the code on both sides, `knowledge/context/adapter_describe_cache/VMWARE/*.json`, and the
2026-11-16 validation capture (`/mnt/holding/vcf-content-factory/backups/vcommunity-java-validation-2026-11-16/A/`:
the `java-*.json` dumps, `input_metrics_check.json`, which holds every latest `mem:*`, `net|packets*` and NIC-speed
stat of every lab host per instance, and `key_map_coverage.tsv`). No live instance was queried.

Excluded: Network Speed Degradation (Q15, R196/R197/R211, `fix/nic-speed-degradation`), including its in-flight edits
in `coll/ObjectKpis.java`.

Python paths are relative to `Management Pack/app/` at tag v0.8.1. Java paths are relative to
`src/com/vcfcf/adapters/vcommunity/`.

## Findings

| id | key-map rows / published keys | upstream file:function | our file:line | what is wrong | evidence | effect on a user | confidence | suggested fix |
|---|---|---|---|---|---|---|---|---|
| UB-1 (HIGH) | R170 `Cluster Risk\|Highest ESXi Memory Consumed`, R181 `Cluster Consumption\|Cluster-wide ESXi Memory Consumed`, so R154 Risk Score, R155 Consumption Score, R222-R224 Periodic, R205-R208 World | `metrics/cluster/cluster_risk_metrics.py`: `dram_capacity_kb` (l.51-59), `memory_consumed_pct` (l.171-177); `cluster_consumption_metrics.py`: `usable_capacity` / `memory_consumed` (l.147-151, 217-218) | `kpi/ClusterRisk.java:23-28, 40-41`; `kpi/ClusterConsumption.java:81-87, 132`; `kpi/StatKeys.java:66-68` | On a host with memory tiering, the denominator is DRAM only (`mem:DRAM\|memory_tier_total_capacity`) but the numerator `mem\|consumed_average` includes memory held in the NVMe tier. So "consumed %" goes past 100 and pins the row at score 0 | Upstream comment l.49-50: "DRAM ... only, never DRAM plus a memory tier". 9.1.1 lab host `vcf-lab-mgmt-esx01`: `mem:DRAM\|memory_tier_total_capacity` = 131784480 KB, `mem:NVMe\|memory_tier_size` = 131784480 KB (1:1 tier). Java dump, same `vcf-lab-mgmt-cl01` cluster and hosts: Highest ESXi Memory Consumed **116.48 %** on 9.1.1 against 58.23 % on 9.0.2; Cluster-wide Memory Consumed **132.05 %** against 66.03 %. A DRAM-only consumption cannot exceed 100 %. Offline re-score with upstream `kpi_score` reproduces the published Risk 82.14 exactly; with the consumed row at its 9.0.2 value Risk is 76.49 | Every cluster with memory tiering (vSphere 9 / VCF 9.1) reads high risk and nearly full: in the lab Risk went from 57 to 82 and Consumption from 18 to **80**, and Periodic Worst Consumption is 81. Consumption on its own then sits in the red band, enough to set Close for Business = 1. The Capacity 2.0 dashboard shows all of these. The validation explained the 9.1 differences away as "9.1 memory tier input" | CONFIRMED from code and live output (one 9.1.1 lab cluster) | Measure consumed against DRAM + `mem:NVMe\|memory_tier_size` (or `mem\|host_provisioned`, which includes the tier), and keep DRAM-only for the monster and reservation rows |
| UB-2 (HIGH) | R195 `Availability\|ESXi Host Operational Availability`, R221 Cluster, R212 vCenter Worst Cluster, R209/R210 World | `metrics/host/host_availability.py`: `HOST_CONDITIONS` / `host_availability` (l.25-38) | `kpi/Availability.java:24-30`; `kpi/StatKeys.java:95`; read at `coll/ObjectKpis.java:371` | The fourth condition, `runtime\|quarantineState == notInQuarantine`, counts as not met when the property is absent. The 8.18 VMware adapter does not store it, so a healthy host can never score above 75 | Java dump: 8.18 `{75.0: 9}` hosts, `{75.0: 2}` clusters, World and vCenter 75.0. The **same** nine hosts are `{100.0: 9}` on 9.0.2 and 9.1.1. `key_map_coverage.tsv` R195 devel_8_18: java 75.0 = python 75.0 (upstream does the same). The describe cache is a merged 9.x + 8.18 union (`merged_from`), so it lists the key but cannot show which version has it | On every 8.18 install, all hosts and clusters show 75 % "Operational Availability" all the time, and so does the "Overall Daily Check" dashboard. A real 25-point drop (for example maintenance) shows as 50, and 75 no longer means anything is wrong | CONFIRMED from live output. Optional live check: `GET /api/resources/{host}/properties` on the 8.18 instance has no `runtime\|quarantineState` | Treat an absent quarantine property as "not in quarantine", or score over the conditions that are present |
| UB-3 (MEDIUM) | R182 `Cluster Consumption\|Cluster Memory Tiering Consumption` | `metrics/cluster/cluster_consumption_metrics.py`: `NVME_TIER_USAGE` (l.65), `usable_capacity` (l.153-157), `memory_tiering_consumption` (l.228-230) | `kpi/StatKeys.java:78`; `kpi/ClusterConsumption.java:88-96, 133-134` | The input stat `mem:NVMe\|tier.usage` does not exist. Hosts are kept in the tier set only when that stat has a value, so the set is always empty and the row is never computed. Dead on every version measured, like NIC speed was | Describe cache: no key containing `tier` on HostSystem. 9.1.1 lab (every latest `mem:*` stat of every host): `mem:NVMe\|latency.read_latest`, `latency.write_latest`, `bandwidth.read_latest`, `bandwidth.write_latest`, `memory_tier_size`, `memory_tier_total_capacity`, and no `tier.usage`. 8.18 and 9.0.2 have no `mem:NVMe` at all. The validation filed it as NONE ("no mem:NVMe\|tier.usage") | The tiering row never appears, so Cluster Consumption never accounts for NVMe-tier pressure. This is the one saturation signal for tiered clusters, which is where UB-1 already distorts the score | CONFIRMED (cache plus full 9.1.1 inventory); NEEDS LIVE CHECK only for a newer product line | Find the real usage counter (for example derive it from `mem\|consumed_average` minus DRAM in use, or a 9.1 tier-active counter) or withdraw the row and name the gap |
| UB-4 (MEDIUM) | R193 `ESXi Performance\|ESXi Bad Network Packets`, R167 `Cluster Performance\|Highest ESXi Bad Network Packets` (and the network part of R153/R156) | `metrics/host/host_bad_network_packets.py`: `HOST_PACKET_STATS` / `bad_packet_pct` (l.16-37) | `kpi/StatKeys.java:59-62`; `kpi/VmContention.java:46-54`; `coll/ObjectKpis.java:494, 565` | The denominator stats `net\|packetsRx_summation_sum` and `net\|packetsTx_summation_sum` are **off by default** on HostSystem. Without them `has_all` fails and the row is withheld. It only works where someone enabled both in the policy | Describe cache HostSystem: `net\|packetsRx_summation_sum` dm=False, `net\|packetsTx_summation_sum` dm=False (also `packetsRx_summation`, `packetsRxPerSec` dm=False); the four error/drop counters are dm=True. Live: present on 9.0.2 lab hosts only, absent from all 13 hosts on 8.18 and on 9.1.1. The validation filed it as "packet counters exist on 9.0.2 only" | Under the default policy the network row of both performance KPIs never appears, so a host dropping packets cannot lower Cluster or ESXi Performance. The Capacity and Performance 2.0 dashboards show an empty widget | CONFIRMED (cache default flag plus two of three lab instances) | Warn and count a gap when the error counters exist but the packet counters do not (as the NIC fix does for `configuredSpeed`), and say in the docs that both must be enabled |
| UB-5 (MEDIUM) | R173 `Cluster Risk\|Worst ESXi Memory Tiering Latency` (and R154) | `metrics/cluster/cluster_risk_metrics.py`: `TIERING_LATENCY` (l.219-231, "in µs (confirmed)", limits 150/300/450/600) | `kpi/KpiTable.java:61`; `kpi/ClusterRisk.java:46`; `kpi/StatKeys.java:72` | The scale looks wrong: live `mem:NVMe\|latency.read_latest` values are about 200,000 to 257,000 against limits of 150 to 600 µs. A value in µs would mean 0.2 to 0.26 s per NVMe read, which is not plausible for a running host. The likely unit is ns | 9.1.1 lab: `vcf-lab-mgmt-esx02` 257447.06; published row 199830.06 on `vcf-lab-mgmt-cl01`; other hosts 0. Offline re-score: Risk 82.14 as published, 74.44 with the value / 1000 | On tiered clusters the row is pinned at score 0 (weight 16). That adds about 8 points of Cluster Risk to the UB-1 inflation in the lab, and keeps adding them whenever the tier is read | NEEDS LIVE CHECK: at the same moment, compare this stat on `vcf-lab-mgmt-esx02` with the vCenter performance chart (or esxtop memory-tier panel) read latency and its unit | Scale to µs at read time once the unit is known, or rescale the limits |
| UB-6 (LOW) | R148 `Cluster Configuration\|Distributed Resource Scheduler\|CPU Over-Commitment` | `properties/cluster/drs_properties.py`: `collect_drs_properties` (l.26-29, `opt.key == "MaxVcpusPerCore"`) | `coll/ClusterCollector.java:122` | It reads the DRS option `MaxVcpusPerCore`. The DRS "Additional Options > CPU Over-Commitment" setting in the vSphere Client is, to the auditor's knowledge, stored as `MaxVcpusPerClusterPct`, which is never read. The comparison is also case-exact | Live: `null` on every cluster of all three instances (`key_map_coverage.tsv` R148), so the lab never exercised it. No local vendor source confirms either option name | A cluster with CPU Over-Commitment set in the UI still shows `null` | NEEDS LIVE CHECK: tick CPU Over-Commitment on one lab DRS cluster, then read `configurationEx.drsConfig.option` and see which key appears | Read the key the UI writes (and compare without case) |

## Quirks register sanity (Q1-Q17)

| Q | Cosmetic or functional | Does the description understate it? |
|---|---|---|
| Q1 | Functional: two keys say `None` on every VM unless `config.tools` is in the Options list | No |
| Q2 | Functional: a VM without a vNIC loses all its Virtual Disk keys | No |
| Q3 | Functional, data: PCI IDs above 0x7FFF come out wrong (`-7f7a` for Intel), which breaks any lookup by ID | No |
| Q4 | Cosmetic | No |
| Q5 | Functional, minor | Slightly. An **unset** timeout is also sent as `0` (through Q9, `VmCollector.java:138`), so `0` cannot be told apart from "unset" or "no timeout". All 45 lab VMs read `0` |
| Q6 | Functional, minor: values containing a quote are cut short | No |
| Q7 | Functional: fields of two licenses can mix in one instance | No |
| Q8 | Cosmetic | No |
| Q9 | Mostly cosmetic | **Yes for the metrics.** DRS Score is published as `0` when DRS is off (R220). On the 0-100 DRS scale that is the **worst** score, so a chart or average over clusters treats DRS-off clusters as badly balanced. Number of HBA and SCSI Controllers Count `0` have the same no-data-as-number issue. These are numbers, not text placeholders |
| Q10 | Cosmetic. Verified live: 17 Connected = full-duplex, 11 Disconnected = half-duplex | No |
| Q11 | Functional, minor | No |
| Q12, Q15 (network part), Q16 | Moot today: the NIC speed source was dead (being fixed). Once the fix lands they become live functional behaviour | Q12 and Q16 should be re-read once the fix lands, because a stale or empty count of 0 reads as "no degraded host" |
| Q13 | Functional: a "no sample" stat reads as a perfect score | No |
| Q14 | Functional | No |
| Q15 (availability part) | Functional | No |
| Q17 | Functional: the alert can never fire in the labs (no host carries that license name) | No |

## Families audited and found clean (no carried defect beyond the register)

- **Suite API stat/property key existence and type**: every key in `kpi/StatKeys.java` was checked against the describe
  cache. VM KPI stats (15), VM props (6), host stats and host props, and cluster stats are present and of the kind read.
  The exceptions are UB-1, UB-3, UB-4 and UB-5 above. `configuration|dasConfig|hostFailoverLevel` is a property but is
  also requested as a stat; both sides fall back to the property (`ops_inventory.read_cluster_ha`,
  `ObjectKpis.java` `availability`), so it is harmless.
- **VM Performance, Guest OS Performance, VM Guest OS KPI**: units consistent (KB/KB, MB conversion of
  `guest|mem.free_latest`). Powered-on test, rounding and averaging match upstream.
- **Cluster Performance and ESXi Performance contention rows**: max and nearest-rank P90 match; only the bad-packets
  row is affected (UB-4).
- **Cluster Risk CPU rows, overcommit and monster ratios** (KB/MB/GB conversions checked). DRAM-denominator rows other
  than Consumed follow upstream's stated intent.
- **KPI scoring** (`kpi_score.py` against `KpiScorer`): tiers, weights, skip-when-green, higher-is-worse flip.
- **Cluster Availability**: N, k, Python slice semantics and the 0-fill for missing hosts.
- **Periodic Cluster Check and Cluster Delta counts**: window math (UTC, floor division), due test, percentile, bands,
  transition counting.
- **World summaries** (cluster scores P10/P90/min/max, Tools counts, port group total): the Tools property keys and
  values exist and give live non-zero counts.
- **Resource pool levels** (Ops PARENT chain in upstream, vim25 parent chain in the port): the root-pool offset is
  equivalent.
- **vim25 property paths** of host, VM, cluster, datastore and vCenter (`ads.timeout`, `config.vpxd.enableDebugBrowse`,
  `event/task.maxAge*`, `instance.id`, `VirtualCenter.*`, `log.level`): all resolve; the live distributions of Detailed
  Data, Customization, Heartbeat, Min EVC, Extents, Block Size and SIOC look real.
- **Licensing** (R2-R6) and **Install Date** (R1): resolve live. Remaining Days uses floor days on both sides.
- **Certificate** (R56-R59): the logic matches. R58/R59 can publish (on a thumbprint change), so they are not dead.
  Upstream's "read failure is treated as first run" is a documented limit; the port withholds instead.
- **Content Library, Library Item, VM Template** figures (bytes to GiB, MB to GB); counts are covered by Q8/Q11.
- **Business Application** (R215-R217): the tree walk and aggregation are equivalent; only Q14 applies. The
  `K8S-Namespace` `Performance|Performance` stat could not be checked against a describe cache, because no Kubernetes
  adapter is cached.
- **Guest OS and Windows events** (R72-R80, events): read for parsing and criticality only. They are off by default
  and unproven on a real guest, so nothing beyond Q4, the "Reproduced in P5" notes and DV-41/42 could be judged.

## Already registered, not re-filed

- Disconnected hosts stop publishing Availability, which raises the World average (issue 86; design Q3 and DV-58).
- Name-keyed placement upstream (cluster or Datacenter names that repeat within one vCenter merge their hosts): the
  port already uses MoRef identity (DV-03 to DV-05), so the defect is upstream-only and not carried.
- Network Speed Degradation (excluded, in flight).

## Notes for the validation classification

Two of its explanations hide defects. "9.1 memory tier input" (R175, R177, R179 and the 9.1 score differences) is
UB-1. "Packet counters exist on 9.0.2 only" (R167, R193) is UB-4, a default-off policy, not a lab gap. R182's NONE
class is UB-3.
