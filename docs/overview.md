# Overview: VCF Content Factory vCommunity

## What's in the Pack

VCF Content Factory vCommunity is a Java port of the vCommunity management
pack. It reads a vCenter over the vim25 SOAP API and the vSphere Automation REST
API (content libraries) and pushes the `vCommunity|` keys onto the objects the
vCenter adapter already has in VCF Operations: hosts, clusters, virtual machines,
datastores, datacenters, the vCenter object and the vSphere World. It also
creates three object kinds of its own.

This is **3.0.0.29, a preview release** (minimum VCF Operations 8.18; collection tested on 8.18.7, 9.0.2 and 9.1.1 with earlier
builds, the verified-by-default certificate handling and the setting handling of this build pending live proof; send feedback as GitHub issues on this
repository). It provides the account, the vCenter
TLS (verified by default), the session, Test Connection, the collection cycle with its health
keys, the property keys of clusters, hosts, virtual machines, datastores and the
vCenter, the three own kinds with their keys and relationship, and the derived
metrics (scores, availability, network speed, shares, port groups, resource
pools, the periodic cluster check, the vSphere World roll-ups and the Business
Application figures). When switched on in the account (both switches are off by
default), it also asks the Windows guests for their operating system, their
services and their event logs, and sends the events (see "Guest monitoring"
below). It also ships the content of the original pack (phase 6): 170 views, 52
dashboards, 16 reports, 3 super metrics, 10 symptoms, 4 alerts with 1 recommendation,
and the six check-list files (see "Content" below and `installing.md`);
`p2-key-coverage.tsv`, `p3-key-coverage.tsv`, `p4-key-coverage.tsv` and
`p5-key-coverage.tsv` in this folder's parent (`docs/`) say, key by key, what this
build collects.

### Resource kinds

| Kind | Key | What it represents |
|------|-----|--------------------|
| VCF Content Factory vCommunity Collector | `vCommunityCollector` | One per account. The collection cycle runs for it, so an unreachable vCenter shows the account as down. Carries no metrics. |
| Content Library | `Content Library` | A content library of the vCenter. Identified by its library ID; the parent of its items. |
| Library Item | `Library Item` | An item of a content library, of any type. Identified by its item ID. |
| VM Template | `VM Template` | A VM that is a template in the vCenter. Identified by its MoRef and the vCenter UUID, so two templates with one name are two objects. |

### Metrics scope

The own kinds declare the same keys as the original pack (26 keys). The keys
pushed onto the vCenter adapter's objects cannot be declared here: a pak cannot
declare keys on another adapter's kinds, and a pushed key carries no label or
unit of its own. `REFERENCE.md` lists what is declared; `adapter.yaml`
(`stitch_targets`) lists where keys land.

In this build the adapter pushes the property keys and the derived metrics (see
below) and its own health figures:

- on the pak's adapter instance, the `Collection|` self metrics (cycle time,
  failed reads, values withheld, objects not matched, check-list entries
  skipped, push counts, whether the deadline was hit, whether the vCenter object
  was matched) and `Collection|last_error`;
- on the vCenter's own object, the `vCommunity|Collection Health|` keys.

## Cross-Adapter Behavior

The pak pushes onto objects owned by the vCenter adapter (VMWARE) through the
local VCF Operations Suite API. An object is found by the vCenter's instance
UUID plus the object's MoRef, never by name: a MoRef such as `host-12` exists in
every vCenter, and names can repeat. A MoRef carried by two objects of the same
vCenter matches neither, and the object is counted as not matched.

The vCenter's own object (kind `VMwareAdapter Instance`) is the one object of
that kind whose `VMEntityVCID` equals the vCenter's instance UUID. If none, or
more than one, carries it, the keys for that object are held back and counted
(`Collection|objects_unmatched`), and the log says which.

If the Suite API client is not available to the instance, the cycle says so and
pushes nothing onto the vCenter's objects.

## Notable Behaviors

- **A failed read is counted; a value vCenter answered without is not sent.** A value that could not be read
  (a path vCenter refused, a property it could not read for that object, a call that failed, a content library
  answer that did not come) is never replaced by a zero, `False`, `Unknown` or any other placeholder: it is
  counted in `Collection|reads_failed` and `Collection|values_withheld`, and the causes are in
  `Collection|failure_causes` (every collector, with counts) and `Collection Health|Collector:<name>|Causes`.
  A value that was read and is simply not set (unset, not returned, null) is not sent either, as the stock
  vSphere adapter does it (DV-112): no `None`, `null`, `[]`, `N/A`, `Unknown`, no empty text and no 0 stands
  in for it, and it is not counted as withheld and raises no warning. A returned value stays, even a 0, `False`,
  an empty list (a count of 0) or an empty text. The same goes for a setting that does not apply (HA, DRS or
  EVC switched off, DRS Score while DRS is off). A key that is not sent keeps its last value in VCF Operations
  (a pushed key cannot be deleted), so a placeholder sent earlier by the Python pack or an earlier build stays
  on an existing object until a real value overwrites it. A NIC whose link is down, or whose link has no duplex flag, has no Duplex key (DV-112). The
  Content Library figures follow the same rule: Items Not Stored Locally and Total Size are sent only when every
  item of the library carries the field (an empty library has a real 0), and Items Not Security Compliant counts
  "of the items with a compliance verdict" and is not sent when none has one.
- **Worst ESXi Memory Tiering Latency is in microseconds.** The statistic it reads is in nanoseconds; the row is
  divided by 1,000 before it is scored and published (DV-113). A -1 ("no sample") is no reading and is left out; a 0.0
  is a real reading.
- **Derived metrics are read back from VCF Operations.** The scores, availability,
  network speed and shares are computed from the statistics and properties the
  VMware adapter already stores, read once per cycle in batches of 500, with the
  membership (which VM is on which host, which host in which cluster and
  Datacenter) taken from the vCenter by MoRef, never by name. A latest sample
  older than 10 minutes (15 for the port group counts) is no data. A KPI is the
  weighted mean of its rows; a row with no data is left out, and a KPI whose
  inputs could not all be read is not sent (never a score over some of the rows).
  The vSphere World and Business Application figures read the values stored by
  the previous cycle, so they are one cycle behind. The Periodic Cluster Check
  and the Cluster Delta counts are published once per window (the window ends at
  the account's Cluster Check Time, UTC, every Cluster Check Period) from the
  stored history; the pak keeps no state for them, so a restart or a second
  collector neither loses nor repeats a window. Where the original publishes
  something a careful reader would not (quirks Q12 to Q15: a Datacenter with no
  comparable NIC is 0, a latest statistic of -1 is a reading, the Business
  Application takes the higher of its namespaces and its lowest tier, nothing
  is sent for any host when none answered), the pak does the same, each behind one
  switch in `text/Quirks.java`.
- **Privileges for the install date and the software packages.** Both calls need
  `Host.Config.Image` on the hosts. Without it every host's call is refused: that
  is one failed read per cycle (`F-HOST-INSTALLDATE`, `F-HOST-PACKAGES`) that
  names the privilege, and those keys are not sent.
- **Deleted libraries, items and templates.** One that vCenter no longer has is
  marked not existing after 12 complete collections, and only when everything that
  could contain it was read. A role narrowed so that the account no longer sees
  the libraries or the templates looks the same as their deletion (the API lists
  only what the account may see), so a kind that had objects and reads as none is
  not believed until it has read as none 12 collections in a row; grant the rights
  back before then. After a restart the pak has no memory of earlier objects and
  believes an empty list at once.
- **Unmatched objects.** An object of the vCenter whose Ops resource cannot be
  found by instance UUID plus MoRef, or is claimed by two resources, gets no
  keys and is counted in `Collection|objects_unmatched`. The Ops lists are read
  once, again every 12th cycle, and again for objects they do not know: at once
  for a new object, then after 2, 4 and 8 cycles and every 12th at most while it
  stays missing, so a VM the vCenter adapter discovers a few minutes late has
  keys within a cycle or two of Ops knowing it.
- **Matches the original, quirks included.** For now the pak reproduces what the
  original 0.8.1 publishes even where it looks wrong, so the first comparison
  against it is exact: a VM with no
  network adapter has no Virtual Disk keys, PCI ids above 0x7FFF are signed
  (`-7f7a`), blank strings are pushed as they are, and so on. Each is a
  one-edit switch in `text/Quirks.java` and is listed in the design (section 5.8)
  to be fixed after the first differential.
- **A page that fails fails its type.** A cluster, host, VM or datastore read is
  paged; if any page fails, nothing from that type is sent that cycle and the
  failure is counted (`F-SOAP-R-CL`, `-R-HO`, `-R-VM`, `-R-DS`). The hosts' option
  list, license assignments and vCenter settings are separate reads: a failure
  there withholds only their keys.
- **Properties are sent when they change.** After a start and in a full refresh
  every 12th cycle everything is sent; between, only what changed. Numbers are
  sent every cycle.
- **License keys are masked.** A host license key is pushed as `*****-*****-*****-*****-MN90P`
  (every group but the last replaced); the full key is never sent or logged.
- **The cycle always reports.** A failed login fails the cycle and the account
  shows an error, or down when the vCenter cannot be reached; the self metrics
  still land.
- **Check-list files.** Six XML files in the VCF Operations configuration-file
  store name what to collect. A bad entry is skipped and reported; a file that
  cannot be read this cycle leaves the last good list in force; a file that does
  not exist empties its list. The Windows service and event files are read only
  when their monitoring switch is on.
- **vCenter TLS.** The vCenter certificate is verified by default, as the built-in adapters do it: the collector's own
  trust (the certificate you review and accept in Validate Connection) and a check that the certificate carries the name
  entered. One advanced setting, Allow Insecure SSL (default false, the same as the other VCF Content Factory paks),
  turns both checks off; guest monitoring does not run while it is on. The Python pack's three modes (Trust on First
  Use, Enabled, Pinned) and its thumbprint field are gone (DV-114). See `installing.md`.
- **Guest monitoring (off by default).** With Guest OS Service Monitoring or Windows
  Event Log Monitoring set to Enabled, a Windows account in the credential and Allow Insecure SSL off (the default), the collector asks each Windows VM that has its tools running, as far as the cycle's time allows,
  through the vSphere guest operations: a directory the guest names, a small PowerShell program sent as command text (no
  script file, no secret in its arguments), the output read back and the directory removed. Services (only the
  ones listed) and the operating system become properties of the VM; event log records become diagnostic events on it,
  once each. Everything the guest writes is checked before it is used (size, shape, characters, which services), a
  failure of one VM is counted with its cause (`F-GUEST-*`, in the `Collector:guest` health keys) and never shown as a stopped
  service or as no events. The Windows account is protected from locking: one VM alone until a logon has worked, two refused
  logons pause the phase for 24 hours. The ESXi host's certificate is checked against the thumbprint vCenter has
  recorded for it (Guest File Transfer TLS Validation: Pinned and Strict). Nothing here has run on a real Windows guest yet:
  `docs/p5-live-proof.md` is the run that proves it.
- **Secrets stay out of logs and messages.** The vCenter and Windows passwords,
  session ids and tokens are never written to a log line or an error message.

## Content

The pak carries the content of the original pack as VCF Operations content, installed with it.

| Content | Count |
|---|---:|
| Views | 170 |
| Dashboards | 52 |
| Reports | 16 |
| Super metrics | 3 |
| Symptom definitions | 10 |
| Alert definitions | 4 (with 1 recommendation) |
| Check-list files | 6 |

- The content is converted from the original pack's content files by the factory's offline importer and
  checked against them field by field (`docs/design/inputs/import-report/`). Names, descriptions, widget titles and layout are the
  original's; every id is minted for this pak (namespace `vcfcf_vcommunity`), so none is shared with the Python pack.
  The 101 views that began as edited copies of VCF Operations' own views have descriptions written
  for this pak, and nine of them new titles. Install this pak only after uninstalling the Python pack (`installing.md`,
  "Moving from the Python pack"); the two side by side are unsupported.
- On versions below 9.0.2 (8.18.7 measured) the five summary reports (capacity, configuration, executive summary, inventory,
  performance) ship without their cover page text color, which the product rejects there; cosmetic only (`installing.md`, "Content").
- 19 views only renamed a built-in view; they are not shipped, and the dashboards and reports that used them
  point at the built-in view by id.
- Five views and two dashboards name objects of kinds other than the vCenter adapter's: four views (the VCF
  management services lists) on VMSP, one view (Guest OS List of Services) on APPLICATIONDISCOVERY, and two
  dashboards (Critical Business Applications, VCF Management Service) on Container and VMSP. The builder does not
  check those kinds; it checks only symptoms and alerts, all of which are on VMWARE.
- The two Section dashboards ("Overall Daily Check", "vSphere Cluster Capacity 2.0") install without their
  Section rows while the pak's minimum version (8.18.x) is below VCF Operations 9.1.1.
- The three super metrics need enabling in the policy ("CPU Usable Capacity Utilization", "Number of VM" and "vSphere Clusters not Green", the last on the vSphere World).
- How the check-list files behave across an upgrade is in `installing.md`.

## Known Limitations

- This build collects the property keys, the three own kinds, the derived metrics and, when switched on, the guest keys and Windows events, and ships the converted content (`docs/p2-key-coverage.tsv`, `docs/p3-key-coverage.tsv`, `docs/p4-key-coverage.tsv`, `docs/p5-key-coverage.tsv`). The development builds have been installed and have collected on lab instances of 8.18.7, 9.0.2 and 9.1.1. Verified live: Network Speed Degradation on 9.0.2 (build 22); the memory tiering divisor and the tiering latency unit on 9.1.1; the migration from a Python pack version (uninstall, restore the built-in views) on 9.1.1. Build 0.0.0.26 (before the certificate change of build 27 and the setting handling of build 28) was installed over earlier builds on 8.18.7, 9.0.2 and 9.1.1 on 2026-11-16 and collected with every collector OK and no warnings, failed reads or withheld values. Seen live: host availability on 8.18.7 back at 100 (was 75); on 9.1.1 the memory rows and the Cluster Consumption score equal to what 9.0.2 publishes for the same cluster (consumed 60.40 against 60.38), Cluster Risk within about one point, and tiering latency in microseconds; VMware Tools properties as real values; no placeholder value pushed after the upgrade; the license symptom form matching a real license instance (proved with a temporary symptom); and "vSphere Clusters not Green" counting 1 of the scored clusters. The "vSphere Cluster Performance 2.0" dashboard was seen working on 9.1.1 (on a picker, click the row, then choose "Internal interaction" from the menu that opens). Not seen: the five package views with data (no lab host runs those packages). The built-in dashboards and the converted content were not all looked at in a browser on every product line (`docs/design/dashboards-port-comparison.md`, section 6).
- Guest monitoring has had one live run (9.0.2, build 26): it collected on two member servers and was refused on two domain controllers, which paused it for every VM; the values it published were not checked. The tracker issues #28, #29, #30 and #17 cover what is open (`installing.md`, "Guest monitoring"; `docs/p5-live-proof.md` lists the experiments).
- The Datacenter count of Network Speed Degradation matches the Datacenter object by MoRef plus vCenter UUID; whether Operations gives a Datacenter those identifiers is unproven (experiment E1). Until it is, a Datacenter without them gets no count and the objects-not-matched figure is unaffected.
- The derived metrics were compared with the Python pack on the lab instances; the differences are the deliberate ones in `CHANGELOG.md` (DV-108 to DV-113). A 24 hour comparison of the whole set on the build that is released is not part of this preview.
- Cluster Memory Tiering Consumption (`vCommunity|Cluster Consumption|Cluster Memory Tiering Consumption`) is never published: its input statistic `mem:NVMe|tier.usage` does not exist in VCF Operations. The real source is the vCenter counter `mem.tier.consumed` (MB, instances DRAM and NVMe), which needs a performance query this adapter does not make. This is a known gap (design, DV-109).
- A new library, item or template is created at once and gets its values on the next collection (five minutes later). One that vCenter no longer has is marked not existing after 12 complete collections, and only when everything that could contain it was read.
- The vCenter certificate presented each cycle is compared with the one recorded on the vCenter object, and
  `vCommunity|Certificate|Status` says `Recorded`, `Unchanged` or `Changed` (for 24 hours); it is a report, not a
  refusal. It is published whether or not Allow Insecure SSL is on, on every cycle that connects: with verification on, a
  certificate the collector does not trust fails the login first, so `Changed` appears only for a new certificate it trusts. With Allow Insecure SSL on the credentials go to
  whatever answers at the address: use it for a lab only.
- Test Connection: the pak logs in to the vCenter and reads its certificate.
  The Suite API and the check-list files are checked only where the test can
  build the Suite API client, which is normally not the case (no configured
  account yet); the report says so and it is not a warning. A passing test
  writes its INFO line nowhere (no per-account log exists during a test, and
  only WARN reaches `collector.log`); what needs attention is one WARN line each.
  The dialog shows "Info: Test connection successful." for a passing test (seen on 9.1.1).
- The phase 1 behaviours (login, session, Test Connection) have run on the lab instances through the development builds.
