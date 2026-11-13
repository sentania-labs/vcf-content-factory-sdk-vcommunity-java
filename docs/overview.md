# Overview: VCF Content Factory vCommunity

## What's in the Pack

VCF Content Factory vCommunity is a Java port of the vCommunity management
pack. It reads a vCenter over the vim25 SOAP API (and, in a later phase, the
vSphere Automation REST API for content libraries) and pushes the `vCommunity|`
keys onto the objects the vCenter adapter already has in VCF Operations: hosts,
clusters, virtual machines, datastores, datacenters, the vCenter object and the
vSphere World. It also creates three object kinds of its own.

This build is the second phase of the port. It provides the account, the vCenter
TLS modes, the session, Test Connection, the collection cycle with its health
keys, and the property keys of clusters, hosts, virtual machines, datastores and
the vCenter. The derived metrics, the own objects, the guest checks and the
content come in later phases; `p2-key-coverage.tsv` in this folder's parent
(`docs/`) says, key by key, what this build collects.

### Resource kinds

| Kind | Key | What it represents |
|------|-----|--------------------|
| VCF Content Factory vCommunity Collector | `vCommunityCollector` | One per account. The collection cycle runs for it, so an unreachable vCenter shows the account as down. Carries no metrics. |
| Content Library | `Content Library` | A content library of the vCenter (later phase). |
| Library Item | `Library Item` | An item of a content library (later phase). |
| VM Template | `VM Template` | A VM that is a template in the vCenter (later phase). |

### Metrics scope

The own kinds declare the same keys as the original pack (26 keys). The keys
pushed onto the vCenter adapter's objects cannot be declared here: a pak cannot
declare keys on another adapter's kinds, and a pushed key carries no label or
unit of its own. `REFERENCE.md` lists what is declared; `adapter.yaml`
(`stitch_targets`) lists where keys land.

In this build the adapter pushes the property keys (see below) and its own
health figures:

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

- **A failed read sends nothing.** A value that could not be read is never
  replaced by a zero, `False`, `Unknown` or any other placeholder; it is counted
  in `Collection|reads_failed` and `Collection|values_withheld`, and the first
  cause is in `Collection|last_error`. A figure the cycle could not measure (for
  example the number of series sent when a push failed) is not sent either.
- **A value that is not there is not sent.** An unset property, a switched-off
  feature (high availability, DRS, EVC), a link that is down, a license with no
  expiry: the key is left out, where the original pack sent `None`, `null`,
  `Unknown`, `N/A`, `0` or `half-duplex`. The key then keeps its last value in
  VCF Operations (a pushed key cannot be deleted), so a view can show an old
  setting for a feature that was since turned off; whether to blank such keys
  once is an open owner question (Q1). A count of what was read and found empty
  (no adapters, no extents on a VMFS datastore) is sent as `0`.
- **Unmatched objects.** An object of the vCenter whose Ops resource cannot be
  found by instance UUID plus MoRef, or is claimed by two resources, gets no
  keys and is counted in `Collection|objects_unmatched`. The Ops lists are read
  once, again every 12th cycle, and again when a new object turns up that the
  list does not have.
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
- **vCenter TLS.** Three modes, set on the account: Trust on First Use (the
  default; connects whatever the certificate), Enabled (the collector's trust,
  with the certificate name checked), Pinned (the vCenter thumbprint). See
  `installing.md`.
- **Secrets stay out of logs and messages.** The vCenter and Windows passwords,
  session ids and tokens are never written to a log line or an error message.

## Known Limitations

- Only the property keys are collected in this build; the derived metrics, the own objects, the guest checks and the content are later phases (`docs/p2-key-coverage.tsv`).
- Trust on First Use sends the credentials before any certificate check, as the
  original pack did. The certificate it presents is compared after the login with
  the one recorded on the vCenter object, and `vCommunity|Certificate|Status`
  says `Recorded`, `Unchanged` or `Changed` (for 24 hours); it is a report, not a
  refusal. Use Pinned or Enabled on a network that is not fully trusted.
- Test Connection: the pak logs in to the vCenter and reads its certificate.
  The Suite API and the check-list files are checked only where the test can
  build the Suite API client, which is normally not the case (no configured
  account yet); the report says so and it is not a warning. A passing test
  writes its INFO line nowhere (no per-account log exists during a test, and
  only WARN reaches `collector.log`); what needs attention is one WARN line each.
  Whether the dialog can show text for a passing test is **unverified**.
- Not yet proven on a live instance: see the phase 1 exit proof in the design.
