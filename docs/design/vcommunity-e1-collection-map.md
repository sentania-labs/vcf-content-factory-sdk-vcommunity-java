# API Map: vCommunity (e1-rahabok 0.8.1 line) collection, for the Java SDK port

What the Python pack `e1-rahabok/VCF-vCommunity-Adapter` calls (vCenter vim25 SOAP, vSphere Automation REST,
VMware Tools guest operations, the VCF Operations Suite API), mapped to every key it publishes. Written so that
`mp-designer` and `sdk-adapter-author` can work without reopening the Python. Intent:
`knowledge/designs/managementpacks/vcommunity-java-port-2026-11.md`.

## Provenance

- **Authored by:** api-cartographer
- **Target instance:** none contacted. Source tree of `e1-rahabok/VCF-vCommunity-Adapter` at commit `0ef8586`
  (0.8.1 plus 27 commits), project under `Management Pack/`, docs under `docs/`, plus the vendored Python
  Integration SDK runtime library 1.0.1 (`aria.ops`) that the pack pins.
- **Last updated:** 2026-11-12
- **Update history:**
  - 2026-11-12 — initial mapping. Source reading only, no live calls. Every one of the 254 published keys from the
    prior inventory (`published_keys.tsv`) re-checked against the publishing call site and annotated; the three
    check-list key families added; companion table `vcommunity-e1-key-map.tsv` written beside this file.
- **Evidence basis:** source reading of the pack at `0ef8586` and of its pinned SDK runtime library; the session's
  prior inventory (`published_keys.tsv`, `definition_keys.tsv`, `definition_only_keys.tsv`, `kpi_tables.tsv`,
  `open_issues.txt`) used as an index and verified line by line; vSphere API data-type knowledge where marked
  `[inferred from docs]`.
- **Notes:** Nothing here was seen on a wire. A claim tagged `[src]` means `[documented in source 0ef8586]` at the
  `file:line` cited (paths relative to `Management Pack/app/`). Behaviour the source cannot settle is tagged
  `[unverified]` and collected under Gaps / Questions. No lab hostnames, addresses, credentials or customer names
  appear here. The upstream inventory TSVs live in session scratch, not in the repo; the facts they carried that
  matter are restated here.

## How to use this map

- **Per-key detail lives in `vcommunity-e1-key-map.tsv`** (257 rows: the 254 published keys plus the three
  check-list families). Columns: object kind, adapter kind, key, metric/property, the value type actually sent,
  value semantics, the unit declared in `definition.py`, source class, exact source (vim25 path or method, REST
  endpoint and JSON field, guest CSV column, Suite API key and kind), transform, behaviour when the source is
  absent or the call fails, a `Y:` flag where that becomes a definite value, the Python `file:line`, the instance
  name rule, and notes.
- This file carries everything that does not fit a row: connections, cycle order, configuration, binding,
  instance naming, the derived-metric formulas, state across cycles, guest operations, call volume, the
  failure-becomes-value catalogue, inherited issues and the live questions.

## Summary for the designer

- One collection is one Python process (`commands.cfg`): nothing survives between cycles except what the pack
  writes into Operations and reads back `[src]`. The Java adapter is long-lived; any cache it adds is new
  behaviour, not parity.
- 228 of 254 keys land on objects the pack does not own (VMWARE and Container kinds). It finds them through the
  Suite API and re-sends them with extra keys. 26 keys land on three own kinds (Content Library, Library Item,
  VM Template) `[src]`.
- 73 of the 85 metrics are not collected from vCenter at all: they are computed from stats and properties the
  VMware adapter already stored, read back through the Suite API, and some from the pack's own earlier output.
- 152 of the 257 rows in the TSV turn an absent property, an unset field or a failed call into a definite value
  (`"null"`, `"None"`, `"0"`, `"Unknown"`, `""`, 0 or 100). Parity means reproducing those strings; the design must
  decide per family whether to keep them.
- Three joins are by display name, not id: VM to host, host to cluster, host to Datacenter. Duplicate cluster
  names across Datacenters of one vCenter merge clusters silently.

| Kind (adapter:object) | keys | metrics | properties |
|---|---|---|---|
| VMWARE:ClusterComputeResource | 58 | 38 | 20 |
| VMWARE:HostSystem | 58 | 13 | 45 |
| VMWARE:VirtualMachine | 66 | 7 | 59 |
| VMWARE:VMwareAdapter Instance (the vCenter object) | 19 | 3 | 16 |
| VMWARE:Datastore | 9 | 0 | 9 |
| VMWARE:vSphere World | 14 | 14 | 0 |
| VMWARE:Datacenter | 1 | 1 | 0 |
| Container:Tier / BusinessService / Enterprise | 3 | 3 | 0 |
| vCommunity:Content Library | 8 | 4 | 4 |
| vCommunity:Library Item | 11 | 1 | 10 |
| vCommunity:VM Template | 7 | 1 | 6 |
| **Total** | **254** | **85** | **169** |

Plus three check-list families with open key sets: `vCommunity|Configuration|Advanced System Settings|<item>`
(HostSystem), `vCommunity|Configuration|Advanced Parameters|<item>` and `vCommunity|Options|<item>`
(VirtualMachine) `[src]`.

## Connection

### vCenter vim25 SOAP (pyVmomi 8.0.3.0.1)

- One `SmartConnect(host, port, user, pwd, <tls args>)` per cycle, from the `host`, `port`, `user`, `password`
  fields `[src adapter.py:380-403]`. The service instance is shared by all nine collector jobs across a five-thread
  pool `[src adapter.py:304]`. Logout is an `atexit` hook, so it runs when the process ends `[src adapter.py:401]`.
- `RetrieveContent()` once; `content.about` (ServiceContent.about) is read from that object with no further call
  `[src adapter.py:234]`.
- Bulk reads use one helper: a recursive `ContainerView` on `rootFolder` for one managed-object type, a
  `traverseEntities` TraversalSpec over `view`, `RetrievePropertiesEx` with `maxObjects=100`, then
  `ContinueRetrievePropertiesEx` until no token, then `DestroyView` `[src utils/vi_property_collector.py:10-86]`.
  `name` is always added to the path set and the set is de-duplicated. A failed read raises, so callers publish
  nothing rather than "no objects" (issue 62 fixed in source).
- Other vim25 calls, all per cycle: `LicenseAssignmentManager.QueryAssignedLicenses()` (no entity),
  `HostImageConfigManager.installDate()` per connected host, `HostImageConfigManager.fetchSoftwarePackages()` per
  connected host when the packages list is non-empty, `OptionManager.QueryOptions(name)` ten times on
  `content.setting`, a per-Datacenter PropertyCollector pass for port groups, and the guest-operations managers
  (section Guest operations) `[src]`.
- **Hidden round trips.** pyVmomi fetches a managed-object property on every attribute access. The pack does this in
  loops: `vm.guest.toolsStatus`, `vm.guest.guestFamily` and `vm.name` per VM per guest script, `vm.runtime.host`
  and `host.summary` per file transfer, `ds.name` per datastore and `vm.parent`, `parent.name` per template
  `[src properties/vm/vmService.py:74-76, utils/guest_http.py:98-125, collectors/library/collectTemplateData.py:58-59]`.
  Each `vm.name` inside a log f-string is a SOAP call even at INFO level `[inferred from pattern]`.

### vSphere Automation REST (content libraries only)

- Base `https://<host>:<port>/api` `[src collectors/library/collectLibraryData.py:40]`.
- Login `POST /api/session` with HTTP Basic (`user`, `password`); the JSON body is the session id, sent as header
  `vmware-api-session-id` on every later call; logout `DELETE /api/session` in a finally path that swallows errors
  `[src collectLibraryData.py:48-59]`. 60 s timeout on every request, no retry.
- Reads: `GET /api/content/library` (ids), then per library `GET /api/content/library/{id}` and
  `GET /api/content/library/item?library_id={id}` (ids), then per item `GET /api/content/library/item/{id}`
  `[src collectLibraryData.py:74-83]`. Bodies are unwrapped from a `value` envelope when present.
- Any non-2xx raises and aborts the whole library collection for the cycle (all libraries, not just one); one
  warning is logged `[src collectLibraryData.py:158-178]`. Libraries are read every cycle; there is no cache
  (issue 54 fixed in source).

### VCF Operations Suite API (SDK `SuiteApiClient`, runtime lib 1.0.1)

- The collector's own Suite API client. Token: `POST /api/auth/token/acquire` on entering a `with` block, released
  on exit `[src pylib aria/ops/suite_api_client.py:73-129]`. Header `Authorization: vRealizeOpsToken <t>`,
  `verify=False`, **no request timeout** (the SDK passes none) and no retry.
- Each of the six configuration-file reads opens and closes its own `with` block, so it costs an acquire and a
  release around one GET `[src adapter.py:91-101]`. The main collection holds one more token for the rest of the
  cycle `[src adapter.py:253]`.
- `paged_post` pages with `page`/`pageSize=1000`; a failed first page returns an empty list, indistinguishable from
  "no resources" `[src pylib suite_api_client.py:296-336]`. A failed later page is not handled (it re-reads the
  previous page body or raises) `[inferred from pattern]`.
- `query_for_resources` swallows every exception and returns `[]` `[src pylib suite_api_client.py:245-294]`. The
  pack uses it for the per-kind object lookups, so a failed lookup means "nothing to publish", never an error.
- Endpoints the pack calls, all `api/...`:

| Endpoint | Method | Used for |
|---|---|---|
| `resources/query` | POST, paged | every resource lookup (by adapterKind, resourceKind, optional adapterInstanceId) |
| `resources/stats/latest/query` | POST, 1000 ids per request | latest stats, and latest-sample timestamps |
| `resources/stats/query` | POST, 100 ids per request | 5-minute AVG history (Periodic Cluster Check, Cluster Delta) |
| `resources/properties/latest/query` | POST, 1000 ids per request | latest properties |
| `resources/{id}/relationships?relationshipType=PARENT\|CHILD` | GET, one per resource | resource pool parents, Container trees |
| `adapters?adapterKindKey=VMWARE` | GET | adapter instance id of the matched vCenter adapter instance |
| `configurations/files?path=SolutionConfig/<name>.xml` | GET | the six check-list files |

- Response parsing quirks to keep: stats come under `stat-list` on the wire (the code also accepts `stats`)
  `[src utils/ops_stats.py:162-163]`; a property sample is taken from `values` (text) or `data` (number), last
  element, stringified `[src ops_stats.py:262-269]`; the latest-stats response also returns every instance of a
  requested key (`cpu:26|utilization_average`), dropped unless instances are asked for `[src ops_stats.py:171-176]`.

### Results back to Operations

- `CollectResult` with `update_relationships = PER_OBJECT` `[src adapter.py:214]`: only objects given a parent or
  child in this result have their links replaced. AUTO would delete Business Application links the pack does not
  send.
- Foreign objects are added with the exact key the Suite API returned (`key_to_object`: adapter kind, kind, name,
  every identifier with its `isPartOfUniqueness`) `[src pylib suite_api_client.py:383-397]`.
- A non-string property value is sent as `numberValue` (the SDK calls `float()` at send time); `None` would make
  serialization fail `[src pylib aria/ops/data.py:88-103]`. Rows that pass a raw bool or a possibly-unset field are
  flagged in the TSV notes.
- `result.with_error(...)` only on two paths: no matching vCenter adapter instance, or an exception escaping the
  cycle (bad port, bad TLS mode, vCenter login failure). Either empties the whole result `[src adapter.py:209-259]`.

## Cycle structure and ordering

Order inside `collect()` `[src adapter.py:209-370]`:

1. Start the warning counter (root logger handler, WARNING and above).
2. SmartConnect, RetrieveContent.
3. Raw TLS handshake to `host:port` for the presented SHA-256 thumbprint (every mode).
4. Set the guest TLS mode (an unknown value raises here and fails the cycle).
5. Read the six check-list files (event list first, services, then ESXi settings, ESXi packages, VM advanced
   parameters, VM options). Problems are collected as strings.
6. Open the main Suite API token. Resolve the vCenter adapter instance id; none: `with_error`, stop.
7. Compute, once for the vCenter, Operational Availability (hosts and clusters) and Network Speed Degradation
   (hosts and per-Datacenter counts). Both are in memory for the jobs below.
8. Submit nine jobs to a 5-worker pool, in this order: cluster, host, VM, datastore, vCenter, World, Datacenter,
   content libraries, Business Applications. The first five start at once; the rest start as workers free up.
   A job's exception is logged and the others go on.
9. After all jobs end: publish Collection Health on the vCenter object, if the vCenter job added it.
10. Stop the counter, log timings, send results.

No job reads another job's output in the same cycle. Everything the pack reads back from Operations is therefore
**at least one cycle old**: the World roll-ups, the Business Application roll-ups, the certificate state, the
Periodic Cluster Check and the Cluster Delta counts (details under State carried across cycles). Inside one job,
Cluster Consumption reuses the host stats Cluster Risk just read, and VM Guest OS KPI reuses the two VM KPIs just
computed `[src collectors/cluster/collectClusterData.py:159-166, collectors/vm/collectVMData.py:156-160]`.

## Configuration

### Instance fields (19)

All read with `get_identifier_value` `[src adapter.py, definition.py:68-293]`. Defaults are definition defaults;
the code adds its own fallbacks where noted.

| # | Field key | Type, default | Consumed by | Validation and failure |
|---|---|---|---|---|
| 1 | `host` | string, required | SmartConnect host; raw thumbprint handshake; REST base URL; exact string match with the VMWARE adapter instance identifier `VCURL` | No validation. A mismatch with `VCURL` (FQDN vs IP vs case) fails the cycle with "No vCenter Adapter Instance found" |
| 2 | `port` | int, 443, advanced | SmartConnect, handshake, REST | `int()`; a non-integer raises and fails the cycle |
| 3 | `vcenter_tls_validation` | enum `Trust on First Use` / `Enabled` / `Pinned`, default TOFU, advanced | SmartConnect TLS args; REST verify | Case-insensitive; empty = TOFU; unknown raises and fails the cycle |
| 4 | `vcenter_thumbprint` | string, advanced | Pinned mode only | Strip whitespace, `:` and `-`, lowercase, hex only, 64 or 128 chars (SOAP); REST requires exactly 64. Invalid in Pinned: SOAP connect raises (cycle fails); REST raises (libraries skipped) |
| 5 | `esxi_adv_settings_config_file` | string, `esxi_advanced_system_settings` | check-list read, host collector | file name without `.xml` |
| 6 | `esxi_vib_driver_config_file` | string, `esxi_packages` | check-list read, host collector | as 5 |
| 7 | `vm_adv_settings_config_file` | string, `vm_advanced_parameters` | check-list read, VM collector | as 5 |
| 8 | `vm_configuration_config_file` | string, `vm_options` | check-list read, VM pathSet | as 5 |
| 9 | `cluster_check_period_hours` | enum `01`,`02`,`04`,`06`,`08`,`12`,`24` (zero-padded so the UI sorts), default `24`, advanced | Periodic Cluster Check, Cluster Delta | `"6"` and `"06"` both accepted; anything else: 24 with a warning (counted in Collection Health, logged twice per cycle because two jobs parse it) |
| 10 | `periodic_cluster_check_time` | string `HH:MM` UTC, `00:00`, advanced | as 9 | invalid: 00:00 with a warning |
| 11 | `cluster_check_worst_percent` | int, 5, advanced | Periodic Cluster Check | digits, 0 to 50; else 5 with a warning |
| 12 | `monster_vm_cpu_percent_of_cores` | int, 50, advanced | Cluster Risk row 11 | digits, 1 to 100; else 50 with a warning |
| 13 | `monster_vm_memory_percent_of_dram` | int, 40, advanced | Cluster Risk row 12 | 1 to 100; else 40 |
| 14 | `monster_vm_memory_min_gb` | int, 256, advanced | Cluster Risk row 12 | 1 or more; else 256 |
| 15 | `win_service_config_file` | string, `windows_service_list`, advanced | Windows service list | **read every cycle even with service monitoring Disabled**, so a missing file raises a vCenter event anyway |
| 16 | `win_event_config_file` | string, `windows_event_list`, advanced | Windows event list (raw XML to the guest) | read every cycle, as 15 |
| 17 | `serviceMonitoring` | enum `Enabled`/`Disabled`, `Disabled`, advanced | gates the services and OS-information scripts | compared with `== "Enabled"` (case-sensitive) |
| 18 | `guest_tls_validation` | enum `Pinned`/`Strict`/`Disabled`, `Pinned`, advanced | guest file-transfer TLS | case-insensitive; empty = Pinned; **unknown raises and fails the whole cycle even with guest monitoring off** |
| 19 | `winEventMonitoring` | enum `Enabled`/`Disabled`, `Disabled`, advanced | gates the event-log script | `== "Enabled"`; also skipped when the event list is empty |

### Credential (one type `vsphere_user`, 4 fields)

| Field | Required | Consumed by |
|---|---|---|
| `user` | yes | SmartConnect, REST Basic auth |
| `password` | yes (password) | as above |
| `winUser` | no | `NamePasswordAuthentication` for every guest-operations call |
| `winPass` | no (password) | as above |

Guest monitoring Enabled with no Windows credential: each Windows VM fails at the first guest call and logs an
error every cycle `[inferred from pattern]`.

### The six check-list files

Fetch: `GET api/configurations/files?path=SolutionConfig/<field value>.xml`; the body text is the XML file
`[src adapter.py:91-101]`. The pak ships the six reference files under `content/files/solutionconfig/`, every entry
commented out, with a "clone it, do not edit" header.

Parse, for the five list files `[src utils/config_files.py:18-24]`:

- XML parse of the body; the items are the root element's text split on `,`, each stripped, empties dropped.
- XML comments are dropped by the parser and the text on both sides of a comment is joined, so `<!-- item, -->`
  lines contribute nothing and uncommented lines contribute their text `[observed with Python 3 ElementTree in
  scratch, 2026-11-12]`.
- Only text before the first child element counts (text after a child is a tail and is ignored).
- **A newline is not a separator.** Two items on two lines without a comma become one item containing a newline.
  The shipped `windows_service_list.xml` has commented examples without commas, so uncommenting them as they are
  yields one invalid name.
- Items are used as given: case-sensitive exact matches, dots kept, any character except a comma allowed.

The event list is not parsed into items: the whole XML text is checked for well-formedness and passed to the guest
as a file. Its structure (`Events/Log[@Name]/EventID`) is only read by the PowerShell script, so a wrong structure
is silent `[src adapter.py:124-140, events/vm/getWindowsEventLogs.ps1:11-18]`.

Failure handling, per file, never fatal `[src adapter.py:91-140, utils/config_files.py:36-51]`:

| Condition | Result for that file | Problem text (event on the vCenter object) |
|---|---|---|
| read raised (connection) | `[]` / `""` | "could not be read from VCF Operations (<repr>). Nothing is collected from it this cycle." |
| non-2xx | `[]` / `""` | "could not be read from VCF Operations (HTTP <code>)..." |
| not well-formed XML | `[]` / `""` | "is not valid XML (<reason>)..." |

Each problem becomes one vCenter-object event, message `vCommunity configuration: <problem>`, criticality WARNING,
`auto_cancel` true, `watch_wait_cycle` 1, `cancel_wait_cycle` 3, `update_date` now `[src
collectors/vcenter/collectvCenterData.py:69-80]`. Events are only sent if the vCenter object itself is found.

How each list feeds the collection:

| List | Effect |
|---|---|
| ESXi Advanced System Settings | Host `config.option` is requested **always** (the full option array of every host, whatever the list). Empty list: the settings block is skipped. Each item found as an option key: `Advanced System Settings|<item>` |
| ESXi Software Packages | Non-empty: `fetchSoftwarePackages()` on every connected host, filtered by exact `name` |
| VM Advanced Parameters | Non-empty: `config.extraConfig` added to the VM path set; matched by exact key |
| VM Options | Each item appended verbatim to the VM PropertyCollector path set. **One invalid path raises `InvalidProperty` and fails the VM read for every VM this cycle**, so every VM key is lost (the job logs one error) |
| Windows Services | Filtered by a safe-name pattern (letters, digits, space, `_ . $ # @ ( ) + -`, first char alnum or `_`, max 256, no `$(`); rejects logged once; survivors become a PowerShell array literal of single-quoted strings `[src utils/guest_args.py:18-54]` |
| Windows Events | Raw XML written to a temp file in the guest and passed to the script |

## TLS and connection behaviour

### vCenter, three modes `[src utils/tls.py]`

| Mode | SOAP (pyVmomi) | REST (libraries) | On the wire |
|---|---|---|---|
| Trust on First Use (default) | `disableSslCertValidation=True` | `verify=False` | no check before login; the certificate is watched afterwards (Certificate keys) |
| Enabled | `ssl.create_default_context()`, TLS 1.2 minimum, hostname checked, system trust store | `verify=True`, which in `requests` means the certifi bundle, not the system store `[inferred from docs]` | refuse on chain or hostname failure |
| Pinned | `disableSslCertValidation=True` plus `thumbprint=<normalized>`; pyVmomi compares before sending anything | urllib3 `assert_fingerprint` on a mounted adapter; SHA-256 only | refuse on mismatch, before credentials are sent |

- The presented thumbprint is read in every mode by a separate raw TLS handshake (no verification, nothing sent,
  10 s timeout), SHA-256 of the DER certificate, lowercase hex `[src tls.py:140-160]`. Any error: logged, `None`.
- Thumbprint storage: the pinned value lives in the instance field; the TOFU value lives in Operations as the
  vCenter-object property `vCommunity|Certificate|SHA-256 Thumbprint`, read back each cycle. Nothing is stored in
  the adapter.
- Connect errors are rewritten into an actionable message when they look like a thumbprint mismatch or a
  `CERTIFICATE_VERIFY_FAILED` `[src tls.py:177-203]`.

### Guest file transfer, three modes `[src utils/guest_http.py]`

| Mode | Behaviour |
|---|---|
| Pinned (default) | Pin the HTTPS PUT/GET to the ESXi host's `summary.config.sslThumbprint` (as vCenter recorded it; SHA-1, 40 hex, or SHA-256). No thumbprint or host not found: transfer unchecked, one warning per host per cycle |
| Strict | As Pinned, but no thumbprint: refuse, VM skipped |
| Disabled | No check, `InsecureRequestWarning` suppressed |

A different certificate than recorded is refused in Pinned and Strict. `verify=False` is passed on every call so
that a `REQUESTS_CA_BUNDLE` variable cannot re-enable chain checks. A fresh `requests.Session` per transfer. The
thumbprint cache is per host MoRef and cleared every cycle `[src guest_http.py:67-200]`.

### Timeouts and retries per client

| Client | Timeout | Retry |
|---|---|---|
| pyVmomi SOAP | none set by the pack (pyVmomi default) `[unverified]` | none |
| raw thumbprint handshake | 10 s | none |
| REST libraries | 60 s per request | none |
| Suite API (SDK) | none (SDK passes none) | none |
| guest HTTPS PUT/GET | 10 s connect, 300 s read | none |
| guest process | 300 s, then `TerminateProcessInGuest` | none |
| guest budget | 1800 s across all VMs per cycle | skipped VMs wait for next cycle |

## Object binding

### The vCenter adapter instance

1. `query_for_resources(adapterKind VMWARE, resourceKind "VMwareAdapter Instance")`, no instance filter.
2. First object whose identifier `VCURL` equals the `host` field, exact string `[src adapter.py:406-422]`.
3. `GET api/adapters?adapterKindKey=VMWARE`; the entry whose `resourceKey` equals that object's key gives the
   adapter instance id `[src adapter.py:425-436]`.

Wrong-match cases: two VMWARE adapter instances with the same `VCURL` (first wins); `host` typed as IP while
the VMware account uses the FQDN (no match, cycle fails). All later lookups are scoped with
`adapterInstanceId=[that id]` unless noted.

### Foreign objects

| Target | Lookup | Match | Can go wrong |
|---|---|---|---|
| ClusterComputeResource, HostSystem, VirtualMachine, Datastore | `query_for_resources` scoped to the instance | identifier `VMEntityObjectID` == vim25 MoRef | A vCenter object missing in Ops is skipped. Hosts not `connected` in vCenter are skipped entirely (no host keys at all that cycle) |
| VMwareAdapter Instance (vCenter) | scoped query | identifier `VMEntityVCID` == `about.instanceUuid` | none found: no vCenter keys, no config events, no Collection Health |
| Datacenter | scoped query | **Ops resource name == host property `summary|parentDatacenter`** | two Datacenters of one name in different folders share a count |
| vSphere World | unscoped query, first result | n/a | one World expected; every adapter instance publishes the same values onto it |
| Container Tier / BusinessService / Enterprise | `resources/query` on adapterKind `Container`, all instances | by Ops resource id, then CHILD relationships | n/a |

Name-based joins used by the KPIs `[src utils/ops_inventory.py:9-11, 115-130]`:

- VM to host by the VM property `summary|parentHost` (host display name).
- Host to cluster by the host property `summary|parentCluster` (cluster display name), compared with the cluster's
  Ops resource name.
- Consequence: **two clusters with the same name in two Datacenters of one vCenter get each other's hosts and VMs**
  in Cluster Performance, Risk, Consumption and Operational Availability, and the usable-capacity stats of one of
  them (`consumption_stats_by_cluster` is keyed by name, last wins). Two hosts with the same display name in two
  Datacenters (rare with FQDNs) merge their VMs. Multiple vCenters are safe here because every read is scoped to one
  adapter instance; the World roll-ups are by resource id.
- Periodic Cluster Check results and resource-pool counts are keyed by MoRef or Ops id, so they are not affected.

### Own object types (adapter kind `vCommunity`)

| Kind | Key name | Identifiers (all part of uniqueness) | Relationship |
|---|---|---|---|
| `Content Library` | library `name` | `Library ID` = library `id` (UUID) | parent of its items |
| `Library Item` | item `name` | `Item ID` = item `id` (UUID) | child of its library |
| `VM Template` | VM name | `VMEntityObjectID` = MoRef, `VMEntityVCID` = vCenter instanceUuid | none |

`[src collectors/library/collectLibraryData.py:115-150, collectTemplateData.py:32-63, definition.py:634-661]`

- The only relationship the pack writes is Content Library to Library Item (`add_child`). Under PER_OBJECT each
  library sent replaces its child set, so an item that disappears loses its link on the next good cycle. The item
  object itself is never deleted by the pack: it stops receiving data and ages out under the Operations
  object-removal policy `[inferred from pattern]`.
- A failed library collection sends no library at all, so links stay as they were.
- Library objects carry no vCenter identifier and no link to the vCenter; libraries of several vCenters are told
  apart only by UUID.
- VM Template objects are created only for VMs that are templates in vCenter **and** found among the VMWARE
  VirtualMachine resources of the instance (the VM loop skips unmatched MoRefs first) `[src collectVMData.py:135-179]`.
  Whether the VMware adapter models templates as VirtualMachine resources decides whether any template object
  appears `[unverified]`. A template converted back to a VM stops getting data; its template object stays. No
  parent link is written (writing one would replace the folder's other children under PER_OBJECT).

## Instanced keys

Operations reads `group:instance|metric`; an instance name containing `|` or `:` would split wrongly. None of the
derivations below escape anything `[src]`.

| Family | Instance name | Fallbacks | Care |
|---|---|---|---|
| `Licensing:{n}` (host, 5 keys) | `assignedLicense.name` | `"Unknown"` if the attribute is missing | two assignments with one name on a host overwrite; names can carry parentheses and non-ASCII |
| `Storage Adapter:{d}` (host, 6) | `hba.device` (`vmhba0`) | if empty or `"null"`: last `-`-separated segment of `hba.key` | stable per host |
| `Network|Device:{d}` (host, 13) | `pnic.device` (`vmnic0`) | none in practice | stable |
| `Configuration|Packages:{p}` (host, 7) | `package.name` == the list item | n/a | exact, case-sensitive |
| `Identifiers|Extent:{n}` (datastore, 1) | 1-based position in `info.vmfs.extent` | n/a | positional, renumbers if extents reorder |
| `Guest OS|Services:{s}` (VM, 3) | CSV `DisplayName` | n/a | localized, can contain `:` `|` `,`; duplicates collide; keyed by display name while the list is by short name |
| `Configuration|SCSI Controllers:{b}` (VM, 1) | `busNumber` | `"unknown"` | stable |
| `Network|Network Adapters:{l}` (VM, 2) | `deviceInfo.label` stripped (`Network adapter 1`) | `"Unknown Adapter"` | renumbers when a NIC is removed |
| `Virtual Disk:{l}` (VM, 7) | `deviceInfo.label` (`Hard disk 1`) | `"unknown"` (no label), `"Unknown"` (no deviceInfo) | renumbers when a disk is removed |
| check-list families (3) | not instanced: the list item is a key path segment | n/a | an item containing `|` or `:` creates groups or instances by accident |

## Derived metrics (the `metrics/` tree)

Shared rules `[src metrics/kpi_score.py, utils/ops_stats.py]`:

- **Scoring.** Each row has five limits (best, green, yellow, orange, zero-at) scoring 100, 75, 50, 25, 0, linear
  inside a tier, 0 beyond zero-at. Higher-is-better rows (zero-at below best) are negated first. A tier of zero
  width is skipped (a value off "best" starts at 75). Weight `2^((100-score)/25)`. KPI = weighted mean, rounded 2.
  Values are rounded to 2 before scoring, so the KPI matches the published rows.
- **Skip when green.** A row flagged `skip_when_green` is left out while its value is at or below best, or below
  green. If every row with data was skipped, the KPI is 100 (so 0 after the higher-is-worse flip).
- **No data.** A row with no value is left out, never 0. No row with data: no KPI.
- **Higher is worse.** Cluster Risk and Cluster Consumption publish `100 - score`.
- **Staleness.** Every latest-stats read drops samples older than 10 minutes (two 5-minute cycles); Number of Port
  Groups on World uses 15 minutes. Properties are never filtered (last known value) `[src ops_stats.py:24-37]`.
- **Percentiles.** Nearest rank everywhere: 90th = `sorted[ceil(0.9 n) - 1]` (with fewer than 10 values it is the
  max); World 10th = `sorted[ceil(0.1 n) - 1]`; Periodic Cluster Check p = `sorted[max(ceil(p/100 n),1) - 1]`.
- **Tiers and weights.** The limits are the rows of `kpi_tables.tsv` (49 rows); the table below names the row group.
  `published_by_pack=False` rows are scored but not published (the VM already has the stat).

### D1 Cluster Performance (per cluster, 9 rows, kpi_tables "Cluster Performance Score")

- Scope: powered-on VMs (`summary|runtime|powerState == "Powered On"`) whose `summary|parentHost` is a host whose
  `summary|parentCluster` equals the cluster name; hosts by the same join.
- Rows 1-8 from VirtualMachine latest stats `cpu|20_sec_peak_readyPct`, `cpu|20_sec_peak_costopPct`,
  `mem|20_sec_peak_host_contentionPct`, `virtualDisk|20_sec_peak_totalLatency_average`: worst = max, broad = 90th
  percentile. One request per cluster (1000 VMs per batch).
- Row 9 Highest ESXi Bad Network Packets: per host `(errorsRx+errorsTx+droppedRx+droppedTx)/(packetsRx+packetsTx)
  x 100` from the six `net|*_summation_sum` HostSystem stats; any missing or zero packets: host left out; max over
  hosts. Skip when green (green is exactly 0). One request per cluster.

### D2 Cluster Risk (per cluster, 12 rows, higher is worse)

- One latest-stats request per cluster for the cluster's hosts with: `cpu|utilization_average`,
  `cpu|coreUtilization_average`, `mem:DRAM|memory_tier_total_capacity` (KB, fallback `mem|host_provisioned`),
  `mem|consumed_average`, `mem|vmmemctl_average`, `mem|compressed_average`, `mem|swapout_average`,
  `mem:NVMe|latency.read_latest` (µs), `cpu|reservedCapacity_average`, `cpu|totalCapacity_average` (MHz),
  `mem|reservedCapacity_average`, `cpu|vcpus_allocated_on_all_powered_on_vms`,
  `mem|memory_allocated_on_all_powered_on_vms` (KB).
- Host properties (from the shared inventory read): `hardware|cpuInfo|numCpuCores`, `config|hyperThread|available`
  and `active` (text `"true"`; threads = 2 x cores when both true).
- Each row is computed per host, then the cluster value is the max over hosts. Formulas per row are in the TSV.
  Monster rows use the instance fields 12-14; memory monster size = `min(percent x DRAM MB, min GB x 1024)`.

### D3 Cluster Consumption (per cluster, 5 rows, higher is worse)

- Cluster stats, one request for all clusters: `cpu|demand|usableCapacity`, `cpu|capacity_provisioned` (MHz),
  `mem|demand|usableCapacity`, `mem|host_provisioned` (KB). Usable % = `max(usable,0)/total x 100`.
- Host stats: the D2 stats of this cycle, plus one request for all hosts of `mem:NVMe|tier.usage` (MB) and
  `mem:NVMe|memory_tier_size` (KB).
- A host counts only while running: connection state `connected` (or unknown), power state not `poweredoff` or
  `standby`, not `inmaintenance` (case and spaces removed), and the stat in question present. Host states come from
  the latest properties (last known value).
- Capacity in threads: usable % x sum of threads of running hosts; DRAM: usable % x sum of DRAM; NVMe: usable mem %
  x sum of tier size. Sums over hosts refuse partial data (any running host missing a stat: no row).
- No running host: nothing published. CPU or memory usable % exactly 0: rows still published, KPI forced to 100.

### D4 ESXi Performance (per connected host, 9 rows)

- D1 rows over the powered-on VMs on this host (name join), and the host's own bad-packet %. Stats read once for
  all hosts in two bulk requests. Published only for hosts the host collector did not skip.

### D5 VM Performance (per powered-on VM, 9 rows; only Memory Zip Swap is published)

- VirtualMachine latest stats `cpu|20_sec_peak_costopPct`, `cpu|20_sec_peak_readyPct`, `cpu|20_sec_peak_overlap`,
  `mem|20_sec_peak_host_contentionPct`, `virtualDisk|20_sec_peak_totalLatency_average`, `net|droppedTxPct_max`,
  `cpu|net_run`, `mem|balloonPct` as they are; Memory Zip Swap =
  `(mem|compressed_average + mem|swapped_average) / guest|used_memory x 100`. One bulk request with D6.
- Power state from the Ops property, not vCenter.

### D6 Guest OS Performance (per powered-on VM, 4 rows; only the KPI is published)

- `guest|20_sec_peak_cpu_queue`, `guest|20_sec_peak_disk_queue`, `guest|mem.free_latest` (KB, divided by 1024
  for the MB limits, higher is better), `guest|page.outRate_latest`.

### D7 VM Guest OS KPI (per powered-on VM)

- Mean of D5 and D6 KPIs, rounded 2; D5 alone when D6 is missing; nothing when D5 is missing.

### D8 Operational Availability

- Host: 25 points each for `sys|poweredOn == 1` (latest stat, 10-minute rule), `runtime|connectionState ==
  connected`, `runtime|maintenanceState == notInMaintenance`, `runtime|quarantineState == notInQuarantine` (latest
  properties). Missing data = condition not met. Computed for every host of the instance, published only on hosts
  the host collector keeps (connected).
- Cluster: N = `summary|total_number_hosts` (latest stat) or the hosts found if larger; k =
  `configuration|dasConfig|hostFailoverLevel` (stat, else property, else 0). Values of hosts found (name join),
  padded with 0 for N minus found, sorted; `min(100, (sum - k lowest)/(N - k))`; none when N - k <= 0.
- vCenter: min over this vCenter's clusters (this cycle). World: min of stored cluster values and mean of stored
  host values, all vCenters, previous cycle.

### D9 Periodic Cluster Check and Close for Business

- Window: ends at the latest `HH:MM` UTC plus k x period at or before now (period divides 24); begins one period
  earlier.
- Due test per cluster, from one latest-stats request with timestamps for the four published keys and the three
  KPI keys: due when the newest published-key sample is older than window end **and** some KPI has a sample at or
  after window start. Timestamp read failure: nothing this cycle.
- At most 500 due clusters per cycle, rotated by `(cycles since window end x 500) mod due`, sorted by Ops id.
- History: `resources/stats/query`, `rollUpType AVG`, `intervalType MINUTES`, `intervalQuantifier 5`, window bounds,
  100 clusters per request. A cluster in a failed batch stays due.
- Values: Worst Performance = worst-percent from the low end of Cluster Performance Score samples; Worst Risk and
  Worst Consumption = (100 - worst%)th percentile. Rounded 2.
- Close for Business (only when all three exist): health = value (Performance) or 100 - value (Risk, Consumption);
  closure red below 20, orange below 40. 1 when Performance or Consumption is red, or both are orange and Risk is
  orange or red; else 0.
- Published metric timestamps are the state: the next cycle sees them after window end and stops treating the
  cluster as due.

### D10 Cluster Delta Close and Open counts (World, once per window)

- Skip when World's own two counts already have a sample at or after window end, or any cluster of any vCenter is
  still due (D9 test), or a timestamp read fails.
- Two history reads of Close for Business for all clusters: [window start, window end] and [window end, now]; last
  sample of each; count 0 to 1 and 1 to 0. Any failed batch: nothing this cycle.

### D11 World roll-ups (previous cycle, all vCenters)

- Six cluster summaries from stored Cluster Performance, Risk, Consumption scores (10th percentile and min for
  Performance; 90th and max for the others). Network count from stored host Network Speed Degradation (< 100).
  Port groups: sum of stored vCenter counts within 15 minutes. VMware Tools counts from VM properties (all VMs).
- Every adapter instance of the pack publishes the same World values, each from its own view of Operations
  `[src metrics/world/cluster_summary_metrics.py:20-27]`.

### D12 Network Speed Degradation, Datacenter count, port groups, resource pools, Business Applications

- Host: per NIC `net:<nic>|speed` and `net:<nic>|configuredSpeed` (latest instance stats of `net|speed`,
  `net|configuredSpeed`); NICs with both > 0; `min(100, sum speed / sum configured x 100)`.
- Datacenter: count of this cycle's host values below 100, grouped by host property `summary|parentDatacenter`.
- vCenter port groups: per Datacenter, distinct names over all hosts' `config.network.portgroup[].spec.name` and
  all `DistributedVirtualPortgroup` names under the network folder; summed over Datacenters. Any read raising: not
  published.
- Resource pools: every ResourcePool of the instance, one PARENT relationship GET each; level = hops to a
  ClusterComputeResource parent (cycle-safe); count levels 1-3 and 2-5 per cluster. Any GET failing: none for any
  cluster.
- Business Applications: Tier = mean of `vCommunity|VM Guest OS KPI` of CHILD VMs; BusinessService =
  `max(namespace Performance|Performance values + [min(Tier values)])` where namespaces are K8S-Namespace CHILDren or
  grandchildren through non-Tier children; Enterprise = mean of CHILD BusinessService values. A failure stops the
  remaining levels for the cycle.

## State carried across cycles

Every read of the pack's own output from Operations:

| Read | Keys and kind | Used for | First cycle / no history |
|---|---|---|---|
| Certificate | `vCommunity|Certificate|SHA-256 Thumbprint`, `Last Changed` on the instance's VMwareAdapter Instance (latest properties) | compare with the presented thumbprint; keep Changed for 24 h | nothing recorded: status Recorded, thumbprint published |
| Periodic Cluster Check due test | latest timestamps of the 4 Periodic keys and the 3 cluster KPI keys | which clusters are due | no KPI sample in window: not due; KPIs exist: due at the first window end |
| Periodic Cluster Check values | history of the 3 cluster KPI keys | the window values | needs the window's samples; a partial first window gives values from what exists |
| Cluster Delta | latest timestamps of World's 2 counts; Close for Business timestamps and history, all clusters | once-per-window gate and the transition counts | needs Close for Business in two consecutive windows; until then 0 and 0 are published once per window |
| World cluster summaries | 3 cluster KPI scores, all clusters | 6 World metrics | nothing stored: not published |
| World availability | Cluster Operational Availability, ESXi Host Operational Availability | 2 World metrics | not published |
| World network | host Network Speed Degradation | World count | not published |
| World port groups | vCenter Number of Port Groups (15-minute rule) | World sum | not published |
| Business Applications | `vCommunity|VM Guest OS KPI` on VMs | Tier, then BusinessService and Enterprise | not published |

The Java port must keep these as read-backs, or keep them in adapter memory with the same first-cycle and
after-restart behaviour. The certificate and Periodic Cluster Check state are designed to survive restarts and
upgrades because they live in Operations.

## Guest operations

Gate per VM, in order `[src collectors/vm/collectVMData.py:163-173]`: a switch is on (service monitoring
Enabled, or event monitoring Enabled with a non-empty event list); the per-cycle 1800 s budget (started when the VM
collector starts) still has time; then per script `vm.guest.toolsStatus == "toolsOk"` and `vm.guest.guestFamily ==
"windowsGuest"` read live. This runs for every VM in the VM loop, powered on or off, Windows or not, so a Linux VM
still costs the two property reads per script. Order: services, OS information, events. A started VM is not
interrupted by the budget.

Common constants: temp parent `C:\Windows\Temp`, interpreter
`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`, temp directory prefix `VCFOperationsvCommunity-`,
suffix `-Services-TEMP` / `-OSInfo-TEMP` / `-EventLog-TEMP` `[src utils/guest_ops.py:22-34]`.

### Call sequence (services and OS information)

1. `FileManager.CreateTemporaryDirectoryInGuest(vm, auth, prefix, suffix, C:\Windows\Temp)`.
2. `FileManager.InitiateFileTransferToGuest(vm, auth, <tempDir>\<script>.ps1, FileAttributes(), size,
   overwrite=True)` returns a URL on the ESXi host; HTTPS PUT of the script bytes (TLS per guest mode); 200 = ok.
3. `ProcessManager.StartProgramInGuest(vm, auth, ProgramSpec(programPath=<powershell>, arguments=-Command
   "<command>"))`. The command pipes the script output to `Export-Csv -Path <tempDir>\<csv> -NoTypeInformation
   -Encoding UTF8`. The command string already carries its own outer quotes, so the arguments end up double-quoted
   `[unverified on a live guest]`.
4. Poll `ListProcessesInGuest(vm, auth, [pid])` with sleeps 1, 2, 4, 8, 10, 10 ... s until `exitCode` is set or
   300 s; on timeout `TerminateProcessInGuest` and skip the VM's script. A process no longer listed counts as done.
   The exit code is ignored.
5. `InitiateFileTransferFromGuest(vm, auth, <tempDir>\<csv>)`; HTTPS GET of `.url`; 200 = text, else `None` and the
   script's keys are skipped.
6. Parse the CSV with a real CSV reader (issues 55 to 58 fixed in source), publish.
7. Finally `DeleteDirectoryInGuest(vm, auth, tempDir, recursive=True)`, errors logged as warnings.

Events add, after step 1: `CreateTemporaryFileInGuest(prefix "events", suffix ".xml", directoryPath tempDir)`, an
`InitiateFileTransferToGuest` plus PUT of the event-list XML, and a `ListFilesInGuest(tempDir, index 0,
maxResults 100)` name check (case-insensitive), before the script transfer `[src
events/vm/collect_windows_event_logs.py:102-144]`.

### Scripts

| Script | Input | Output CSV columns | Published |
|---|---|---|---|
| `getWindowsOSInformation.ps1` | none | `Name` (Caption), `Version`, `BuildNumber`, `OSArchitecture`, `LastBootUpTime`, `ReleaseId` (registry) | 6 `Guest OS|Operating System|*` properties; every data row published, last wins |
| `getWindowsServices.ps1` | `-serviceList` string array (mandatory) | `Name`, `DisplayName`, `Status`, `StartType` (`Get-Service`, not-found names skipped) | `Guest OS|Services:<DisplayName>|Service Name/Status/Start Type`; rows shorter than the header skipped |
| `getWindowsEventLogs.ps1` | path of the event-list XML | `Level` (`LevelDisplayName`), `Event` (`[Timestamp=..] [Category=<log>] [EventID=..] [Source=..] [Message=..]`) | events on the VM |

The event script looks back a fixed 5 minutes and returns at most the newest event per (log, event id)
(`-MaxEvents 1`); "no events found" is not an error; other read errors are printed to the host stream and skipped
(issue 64 fixed in source) `[src getWindowsEventLogs.ps1:10-62]`.

A missing column in the header: error logged, nothing published for that script. An empty service list still runs
the script with an empty array against a mandatory parameter `[unverified]`.

### Events from guests

On the VMWARE VirtualMachine object, one per CSV row, at most 100 per VM per cycle (the rest counted in a
warning): message `[WindowsEvent-<Level> <Event>]`, criticality `Information`/`Verbose` INFO, `Warning` WARNING,
`Error` IMMEDIATE, `Critical` CRITICAL, anything else INFO; `auto_cancel` true, `watch_wait_cycle` 1,
`cancel_wait_cycle` 3, `update_date` now `[src collect_windows_event_logs.py:25-99]`. The message carries the event
timestamp, so each new occurrence is a new event. `LevelDisplayName` is localized, so a non-English guest maps every
level to INFO `[inferred from docs]`.

Configuration-problem events on the vCenter object: see Configuration (WARNING, auto-cancel, 1 / 3).

## Call volume model

Symbols: C clusters, H hosts, Hc connected hosts, V VMs, Von powered-on VMs, Vx VMs reaching the guest gate (all V
when a guest switch is on), Vw Windows VMs with Tools OK, T templates found in Ops, ds datastores per template, DC
Datacenters, L libraries, I items, P resource pools, Ndue due clusters; World-wide Cw, Hw, Vw_all, VCw vCenter
objects; Tr tiers, A business applications, Ac their non-Tier children, E enterprises. `pg(n)` = `ceil(n/1000)`
pages; `pc(n)` = `ceil(n/100)` PropertyCollector pages. **Bold** = per-object.

### vim25 (per cycle)

| Item | Calls |
|---|---|
| connect + content + logout | about 4 + 1, plus 1 raw TLS handshake |
| cluster / host / datastore / VM bulk reads | each 2 + pc(n) (view create, retrieve + continues, destroy) |
| licensing | 2 |
| host install date | **Hc** |
| host software packages (list non-empty) | **Hc** (heavy) |
| vCenter options | 10 |
| port groups | about 2 + 1 + **DC x (2 + 2 x (2 + pc(n)))** |
| templates | **T x (ds + 2)** |
| guest gate | **Vx x 2 per enabled script** (3 with the skip log's `vm.name`) |
| guest, per Windows VM per script | about 5 manager calls + polls (3 for a 3 s script) + 1 to 2 thumbprint reads + about 4 `vm.name` reads + 2 HTTPS; events add 3 manager calls and 1 HTTPS |

### Suite API (per cycle)

| Item | Calls |
|---|---|
| check-list files | 6 x (token acquire + GET + release) = 18 |
| main token | 2 |
| adapter instance id | 1 query + 1 GET |
| Operational Availability (pre-pool) | hosts query + props + stats + props + clusters query + stats + props: about 7 |
| Network Speed (pre-pool) | hosts query + instance stats + props: 3 |
| cluster job | clusters query x 2, read_hosts 2, read_vms 1 + pg(V), consumption 2, pools query + **P GETs**, periodic 1 + 1 + ceil(Ndue/100), **per cluster 3** (VM contention, host packets, host risk) |
| host job | hosts query, read_hosts 2, read_vms 1 + pg(V), 2 bulk stats |
| VM job | VMs query, read_vms 1 + pg(V), pg(Von) perf stats, pg(V) shares props, shares stats fallback for VMs missing a share |
| datastore, Datacenter | 1 each |
| vCenter job | 1 query + certificate (1 query + 1 props) |
| World job | about 14 queries and reads over Cw, Hw, Vw_all, VCw; Cluster Delta when due adds 2 + 2 x ceil(Cw/100) |
| Business Applications | 3 queries + **Tr + A + Ac + E relationship GETs** + 2 stats |

### REST (per cycle)

`2 + 1 + 2L + I` (login, logout, list, per library 2, **per item 1**).

### Redundant reads (same data read more than once per cycle)

- Hosts with their 7 inventory properties: 3 times (availability, cluster job, host job); VMs with 4 properties:
  3 times (cluster, host, VM jobs). Cluster resources: 4 to 5 times scoped plus 2 to 3 unscoped in World.
- VM contention stats for the same powered-on VMs: per cluster (D1) and in bulk (D4). Host packet counters: per
  cluster and in bulk.
- Host `config.network` (host job) already contains `portgroup`, which the port-group pass reads again per
  Datacenter.
- VM `guest` is in the bulk read, yet the guest gate re-reads `vm.guest` twice per script per VM.
- The vCenter object is looked up twice (vCenter job and certificate).
- `config.option` (all host advanced options) is always read even with an empty settings list.

## Failure-becomes-value catalogue

Mechanisms, each tied to TSV rows flagged `Y:` (152 rows). The port must choose per mechanism: keep for parity,
or publish nothing.

1. `str(None)` of an absent PropertyCollector path gives the text `"None"` (host time zone, boot time, BIOS, config
   status, storage vMotion; datastore status and SIOC; cluster statuses and HA values; about 30 VM configuration
   keys; vNIC type; disk controller key and backing flags).
2. Explicit `"null"` placeholders: host VAAI options, DHCP, search domain, HBA fields; cluster HA when HA is off or
   host monitoring disabled; DRS when DRS is off; EVC mode; VM customization status and the 7 Detailed Data keys;
   vNIC starts-connected; the 10 vCenter options when the call fails or the value is empty.
3. A failed `installDate()` call gives `"null"`.
4. Counts that read 0 when the source is missing: HBA count `"0"`, extents `"0"`, SCSI controller count 0,
   effective hosts 0, op notification timeout `"0"`, template vCPUs `"0"`, memory `"0.0"`, disk used 0.0, library
   and item sizes 0.
5. Default containers stringified: Max EVC `"[]"`.
6. `"Unknown"`: license name/key/edition when missing, Patch Level when the attribute is missing, disk share level
   and disk backing when no backing.
7. `"N/A"` for PCI fields when the NIC has no PCI match.
8. Link-state stand-ins: NIC Status `"Disconnected"` and Duplex `"half-duplex"` when the link is down.
9. DRS Score 0 when DRS is disabled or the score is negative.
10. Empty strings: template guest OS, hardware version, datastore list, folder; library and item text fields.
11. Library counts treat a missing `cached` or `security_compliance` field as not stored / not compliant.
12. Resource pool counts 0 for a cluster that is not matched to an Ops id, and for every cluster when the pool query
    silently returns no pools.
13. VMware Tools counts 0 and 0 when the unscoped VM query silently returns nothing.
14. Host Operational Availability: missing data scores 0 for that condition; a failed stats batch drops every
    host to at most 75. Cluster availability pads hosts missing from the name join with 0.
15. Datacenter network count 0 when its hosts have no value.
16. Certificate Status `"Recorded"` (and the recorded thumbprint overwritten) when the read-back answers non-200 or
    finds no resource; `"Unknown"` when it raises.
17. KPI 100 (or 0 after the flip) when every row with data is green-skipped; Cluster Consumption 100 when usable
    capacity is 0.
18. Collection Health 0 and `"None in the last cycle"`, published every cycle.
19. Partial batches (issue 60): a failed stats or properties batch removes those resources, and max, percentile,
    mean and count aggregates are then published over the rest (D1, D2, D3, D4, D8, D11, D12, Business
    Applications).

## Known-wrong or undecided behaviour the port inherits

| Issue | State in source at `0ef8586` | Keys or paths |
|---|---|---|
| 85 keys declared in `definition.py` differ from published keys | Open. The definition declares own kinds named "Cluster Compute Resource", "Host System", "Virtual Machine", "vCommunity-Datastore", "vCenter", "Datacenter", "vSphere World" and the three Container kinds under adapter kind `vCommunity`; the keys are published on VMWARE and Container kinds. 62 declared keys are never published (`Time Zone Name`, flat `Licensing|*`, `Host Network Device:*`, `hbaDevice:*`, `DS *`, `VM *` names, and others) | describe.xml of the port: declare only the own kinds; units and labels for keys on VMWARE objects need a live answer |
| 49 Share per vCPU / per MB: no value in the UI, count unit not recognised | Open; published as metrics, no unit | the two share keys |
| 86 disconnected hosts keep their last value and drop out of the World average | Open. The host job skips any host whose `runtime.connectionState` is not `connected`, so host Availability, Network Speed, ESXi Performance and every host property stop updating; the 10-minute rule then drops them from World | host keys; World ESXi Hosts Operational Availability |
| 60 partial stats reads feed aggregates | Open (warning only) | mechanism 19 above |
| 6 Business Application Performance max vs min | Undecided; code takes the max of namespace values and the lowest tier | BusinessService key |
| 8 relationship counts (VMs, port groups, resource pools) | Undecided; the pack computes port groups (vCenter, World) and resource pools (cluster), not VM counts | those 4 keys |
| 14 Utilization should consider Buffer | Open; Cluster Consumption already divides by `*|demand|usableCapacity` (Total minus HA minus Buffer), World "Utilization" keys are the Consumption score | Cluster Consumption rows, World Highest Cluster Utilization keys |
| 1 rename Daily Cluster Check, drop Window Hours / Window End | Done in code and content (group is `Periodic Cluster Check`; no Window keys anywhere); issue still open | none |
| 2 verify on a live system | Open | every `[unverified]` item below |
| 42 cycle time at scale | Open, no measurement | call volume model |
| 53 guest polling timeout | Fixed: 300 s and terminate | guest |
| 54 library cache | Fixed: no cache, read every cycle | libraries |
| 55-58 guest CSV, None download, temp file, message bracket | Fixed: CSV reader, `None` checks, status check, bracket closed | guest keys and events |
| 59 unbound `vm_name` | Fixed | VM loop |
| 61 return inside finally | Fixed | n/a |
| 62 failed vCenter read returned `[]` | Fixed: raises | bulk reads |
| 63 event-list read error discarded the cycle | Fixed: isolated per file | config events |
| 64 event query hid errors, no cap | Fixed: `-MaxEvents 1`, 100 per VM | events |
| 65 REST login not pinned in Pinned mode | Fixed: urllib3 fingerprint on the session (SHA-256 only) | libraries |
| 87, 91, 25, 41 | Content only (dashboards, views, report inputs), no collection effect | n/a |

Also inherited, with no issue filed:

- An invalid VM Options path fails the whole VM read (every VM key lost for the cycle).
- An unknown guest TLS mode fails the whole cycle even with guest monitoring off.
- Optional vim25 fields passed without `str()` (NIC driver and firmware versions, PCI names, package
  `maintenanceModeRequired`) would reach the SDK as `None`, which fails result serialization `[inferred from SDK
  source]`; `maintenanceModeRequired` lands as a numeric property 1.0 / 0.0.
- PCI vendor and device ids are signed shorts; ids above `0x7FFF` would format as negative hex `[inferred from docs]`.
- The full host license key is published as a property.
- The SCSI controller fallback text is a Python class name.

## Object Model Candidates

### Content Library (own)

- Source: REST `GET /api/content/library/{id}` plus the item list. Identifier `Library ID`. 4 properties, 4
  metrics. Parent of Library Item.

### Library Item (own)

- Source: REST `GET /api/content/library/item/{id}`. Identifier `Item ID`. 10 properties, 1 metric.

### VM Template (own)

- Source: the VM PropertyCollector read (template paths `config.template`, `config.guestFullName`,
  `config.version`, `summary.config.numCpu`, `summary.config.memorySizeMB`, `summary.storage.committed`,
  `datastore`) plus per-object `ds.name`, `vm.parent.name`. Identifiers `VMEntityObjectID`, `VMEntityVCID`.
  6 properties, 1 metric. No relationships.

### Foreign objects

- VMWARE ClusterComputeResource, HostSystem, VirtualMachine, Datastore, VMwareAdapter Instance, Datacenter,
  vSphere World and Container Tier, BusinessService, Enterprise, all matched as in Object binding.

## Cross-Request Join Keys

- vim25 MoRef `_moId` = Ops identifier `VMEntityObjectID` → binds PropertyCollector results to cluster, host, VM,
  datastore resources (scoped to one adapter instance).
- `ServiceContent.about.instanceUuid` = Ops identifier `VMEntityVCID` → binds to the VMwareAdapter Instance object;
  also stamped on VM Template.
- Instance field `host` = VMWARE adapter instance identifier `VCURL` → adapter instance; its `resourceKey` = an
  `adapterInstancesInfoDto` entry of `GET api/adapters` → adapter instance id.
- VM property `summary|parentHost` = host Ops resource name → VM to host (KPIs).
- Host property `summary|parentCluster` = cluster Ops resource name → host to cluster (KPIs, availability).
- Host property `summary|parentDatacenter` = Datacenter Ops resource name → Datacenter count.
- Ops resource id (SELF link in relationship responses) → resource pool tree, Container tree.
- `pnic.pci` = `hardware.pciDevice[].id` → NIC to PCI device.
- `LicenseAssignment.entityId` = host MoRef → licenses to host.
- `vm.runtime.host` MoRef → ESXi thumbprint for guest transfer pinning.
- REST library `id` = item list `library_id` query parameter → items of a library.

## Gaps / Questions

Source-only round; nothing was probed. These need a live answer:

1. Do the kind-level declarations in `definition.py` (own kinds named like VMware kinds) give units or labels to
   the keys the pack publishes on VMWARE and Container objects? If not, how does Operations type and label them,
   and what does the Java describe.xml need for the units to show (issue 49, 85)?
2. Does a property sent as a number (`maintenanceModeRequired`) display as 1/0 today, and does an optional NIC field
   ever arrive unset (which would crash the Python send)?
3. Default socket and read timeouts of pyVmomi 8.0.3 `SmartConnect` as the pack calls it, and of the Python SDK
   Suite API client in the Cloud Proxy container; and the equivalents in the Java SDK clients the port will use.
4. Does `InitiateFileTransferToGuest` / `FromGuest` ever return a URL with `*` as the host (which the Python does
   not replace), behind a Cloud Proxy?
5. Does the `Export-Csv -Encoding UTF8` byte-order mark reach the CSV header as `\ufeff` after `requests` decodes
   the body? If so, the first column (`Name`, `Level`) fails the header check and no service, OS or event data is
   ever published.
6. Does the doubled quoting of the `-Command` argument run as intended on Windows PowerShell 5.1?
7. With an empty service list, does the mandatory `[string[]]` parameter fail, and is an empty or missing CSV the
   result?
8. Localized `LevelDisplayName` on non-English guests: confirm the INFO fallback.
9. Does the VMware adapter create VirtualMachine resources for templates (otherwise no VM Template object ever
   appears)?
10. Are `assignedLicense.properties[expirationDate]` values timezone-aware in the SDK the port uses?
11. PCI `vendorId` for 0x8086 NICs: negative on the wire as expected?
12. Is `mem:DRAM|memory_tier_total_capacity` present on 8.18 instances (the declared minimum), or does every
    memory row run on the `mem|host_provisioned` fallback there? Same for `mem:NVMe|*` and the 20-second-peak keys.
13. Does the latest-stats query return per-instance `net:<nic>|speed` values when asked for `net|speed` on 8.18 and
    9.x (the Network Speed rows depend on it)? The policy must enable these counters; is that state visible?
14. Is `summary.drsScore` present on every supported vCenter, and `configurationEx.proactiveDrsConfig` never unset
    (an unset value skips all four DRS properties)?
15. `VCURL` format on VMWARE adapter instances (bare FQDN, IP, URL) across versions, to set the matching rule.
16. Real cycle time and Suite API load at scale (issue 42), with the per-pool, per-cluster, per-item and per-VM
    guest calls above.
17. Does the Java SDK's handling of foreign objects (identifier sets and uniqueness flags copied from a resource
    query) accept the VMWARE and Container keys the Python sends, including `VMwareAdapter Instance`?
18. Under the Java SDK, are metric timestamps the collection time, so the Periodic Cluster Check timestamp-as-state
    design carries over?
19. Does writing properties to the VMWARE VMwareAdapter Instance and vSphere World objects from two adapter
    instances (two vCenters) behave as the Python assumes (same values, no conflict)?
