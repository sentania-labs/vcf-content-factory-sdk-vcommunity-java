# Overview: VCF Content Factory vCommunity

## What's in the Pack

VCF Content Factory vCommunity is a Java port of the vCommunity management
pack. It reads a vCenter over the vim25 SOAP API (and, in a later phase, the
vSphere Automation REST API for content libraries) and pushes the `vCommunity|`
keys onto the objects the vCenter adapter already has in VCF Operations: hosts,
clusters, virtual machines, datastores, datacenters, the vCenter object and the
vSphere World. It also creates three object kinds of its own.

This build is the first phase of the port. It provides the account, the vCenter
TLS modes, the session, Test Connection and the collection cycle with its
health keys. It collects no `vCommunity|` key yet.

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

In this build the adapter pushes only its own health figures:

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

- No `vCommunity|` key is collected in this build.
- Trust on First Use sends the user name and password before the certificate is
  checked, as the original pack did. Use Pinned or Enabled on a network that is
  not fully trusted.
- The platform shows no text for a passing Test Connection. The summary of what
  the test found (login, certificate thumbprint, Suite API, each check-list file
  with its outcome and item count) is written to the collector log at WARN, where
  it can be found; a failing test shows its message in the dialog.
- Not yet proven on a live instance: see the phase 1 exit proof in the design.
