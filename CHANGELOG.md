# Changelog

One entry per build. Dev builds are stamped `0.0.0.<build>`; the release is the
`v*` tag built by this repository's CI.

## 1.0.0.13 (Unreleased)

Review of build 12 (`docs/reviews/p5-guest-review.md`) and the third live differential; design 8.9.

- fix(adapter): B1 the Windows account is protected from locking: refusals are counted across collections; one VM alone until a logon works, a
  cap of two refused logons, a 24 hour pause, a VM that refused is left alone for 24 hours, a restart is one VM alone (DV-97 rewritten).
- fix(adapter): B2 only the services in the list are published, one row a name; no more events than queries (DV-99).
- fix(adapter): B3 an event query that failed in the guest is `F-GUEST-QUERY` (the events found are still sent).
- fix(adapter): W1 `[Category=...]` is the record's own log name; W2 event text as written (CRLF kept, cut only at 32 KiB); W3 an output cut
  short is refused; W4 a fault text is scrubbed before it is cut; W6 SHA-1 checked once (`F-GUEST-TLS`); W7 a VM that moved is asked on its
  new host; W8 no `-ExecutionPolicy`; W9 the directory path is matched without regard to case; W10 calls and transfers are bounded by the VM's
  time, the grace is 20 s and the cleanup wait 10 s; W11 configuration warning events once; W12 the events window follows the time since the VM was last read.
- feat(adapter): W5 the guest phase does not run while vCenter TLS Validation is Trust on First Use (`F-GUEST-TLS-MODE`, Test Connection warns; DV-103,
  `GuestPlan.REQUIRE_CHECKED_VCENTER_TLS`).
- fix(adapter): N4 to N9 (leftovers kept after a refusal, a host read that did not finish, `InvalidPowerState` and two more guest faults, a cut VM
  loses its events too, the register text of DV-41, a blank display name).
- fix(adapter): L2 the Cluster Delta counts are not published twice when the read-back lags a push (DV-105). L1: the formatter of
  `Options|config.tools` already has the two spaces; the test now compares the Python dump's exact string, and the loss is after the push (design 5.8, note L1).
- docs: the open note about two powered-off VMs (L3) is removed; installing (guest monitoring, account lockout, security products), overview, live proof.

## 1.0.0.12 (Unreleased)

Phase P5: guest operations and events (design 8.8, `docs/p5-key-coverage.tsv`, `docs/p5-live-proof.md`).

- feat(adapter): guest monitoring for Windows VMs, off by default (Guest OS Service Monitoring, Windows Event Log
  Monitoring): the operating system (R72 to R77) and the listed services (R78 to R80) as properties, event log records
  as diagnostic events on the VM, through the vSphere guest operations; four VMs at a time, a rotating cursor, a hard stop
  45 s past the cycle's deadline, the directory in the guest removed on every path.
- feat(adapter): security of the guest phase: the Windows account only in the `auth` element of a vCenter request and
  scrubbed from every message; programs sent as `-EncodedCommand` with no secret; file transfer to the ESXi host over TLS
  pinned to the thumbprint vCenter recorded (SHA-1, SHA-256 or SHA-512; Pinned and Strict refuse a host with none, DV-95;
  Disabled is the only mode that accepts any certificate); output bounded and read as untrusted (DV-99); the account is not
  tried on every VM after three refusals, and the phase pauses for 12 collections (DV-97).
- feat(adapter): Windows events and the configuration warnings of an unusable check-list file go to Operations as DIAGNOSTIC
  events (`OpsApi.pushEvents`, T2); an event is sent once while it stays in view (DV-98); health `Collector:guest|*`
  and the self metrics `guest_vms_done` and `guest_vms_pending`.
- chore(adapter): `PinnedTrustManager` accepts a 40 digit (SHA-1) pin for the ESXi hosts; the vCenter modes are unchanged.
- test(adapter): `GuestOpsTest` (a fake vCenter guest operations service and ESXi host over real TLS: healthy, stopped,
  missing and malformed services, event windows empty, large, duplicated, non-ASCII and hostile, timeouts, tools down,
  refused account, wrong and missing certificates, cleanup after every failure and after a cancel, no secret anywhere),
  `GuestEngineTest`, `GuestUnitTest`; `docs/p5-key-coverage.tsv` for R72 to R80 with a staleness test.
- docs: design 8.8, DV-94 to DV-102, quirk Q4 extended to the guest output, `docs/p5-live-proof.md` (G1 to G9 on a real guest).
  DV-55 (blank once on switch-off) is not built: Q1 is still undecided.

## 1.0.0.11 (Unreleased)

Review of build 10 (`docs/reviews/p4-derived-review.md`) and the second live differential.

- fix(adapter): the pool counts (R218, R219) leave out Supervisor namespaces and everything under them: vCenter holds
  them as pools, Operations as `Namespace` objects (`ResourcePool.namespace` is read); a fixture with namespace pools.
- fix(adapter): comments rewritten in the port's own words and the derived helpers renamed from the design's vocabulary;
  the order of the arithmetic is unchanged (the 1,243 KPI checks and the baseline re-score are as before).
- fix(adapter): R211 counts as upstream does (0 while the answer names hosts, even with only stale values), as quirk Q16.
- fix(adapter): the statistics answers are read as a stream, in batches of 50 hosts, so the per-thread and per-NIC series
  the platform adds to every answer cost no heap (the heap test now carries them, in 48 MB).
- fix(adapter): a failed World read marks the cluster, host and VM lists for a new read; a VM whose host vCenter would not
  read is placed by Operations' host name; a failed fallback read withholds only the share that needed it; the relationship
  reads ask for 1000 per page.
- fix(adapter): the two warnings of the original (no NIC speed for any host, no availability data for any host) are logged,
  so Warnings in Last Cycle and First Warning agree with it.
- feat(adapter): with `config.tools` in the VM Options list, Tools Sync Time and Tools Upgrade Policy carry the real values
  (the edge of Q1); a list of datastores and the tools configuration are published as pyVmomi prints them (DV-93).
- fix(adapter): Check-List Entries Skipped counts the VM Options entries skipped now, every cycle.
- docs: DV-35, DV-87, DV-91, DV-93, Q1, Q15, Q16; the open difference L3 for two lab VMs (design 5.8).

## 1.0.0.10 (Unreleased)

Phase P4, the derived metrics (design 7, 4.3 to 4.9, 4.12; rows R70, R71, R153 to R219, R221 to R225).

- feat(adapter): the cluster, host and VM scores and their rows (Cluster Performance, Risk, Consumption, ESXi
  Performance, VM Performance, Guest OS Performance, VM Guest OS KPI), computed from what the VMware adapter
  already stores, read back once per data set in batches of 500; membership by MoRef (VM to host, host to
  cluster, host to Datacenter).
- feat(adapter): Operational Availability of hosts, clusters and the vCenter's worst cluster; Network Speed
  Degradation of hosts and the Datacenter counts; the share per vCPU and per MB of memory; Number of Port Groups
  and the resource pool counts from the vCenter's own lists.
- feat(adapter): the Periodic Cluster Check (worst values and Close for Business once per window) and the Cluster
  Delta counts, from the stored history; no state is held, so a restart or a second collector loses nothing and
  publishes nothing twice.
- feat(adapter): the vSphere World roll-ups (cluster summaries, availability, network, port groups, VMware Tools
  counts) and the Business Application figures (Tier, Business Application, Enterprise), read back from the
  previous cycle's stored values.
- fix(framework): a read-back that fails (HTTP, network, malformed answer, a sample that is not a number)
  withholds the figures that stood on it and is counted (`F-OPS-STATS-*`, `F-OPS-PROPS-*`, `F-OPS-HISTORY`,
  `F-OPS-REL`); a KPI is never scored over some of its rows; the deadline and an interrupt stop between batches.
- feat(adapter): the collectors `kpi`, `datacenter`, `world` and `business_applications` report their status,
  failures and causes in Collection Health.
- feat(adapter): quirks Q12 to Q15 registered with one seam each (`Quirks`); the map's Close for Business bands
  (20 and 40) corrected to the code's (25 and 50, M1).
- test: `KpiTest` (the worked examples of upstream and every limit pinned to its table), `OpsReadsTest`,
  `DerivedTest` through the engine with a vCenter and an Operations that make their answers, `DerivedHeapTest`
  (10,000 VMs in a 48 MB heap), `docs/p4-key-coverage.tsv` (74 rows, a value assertion each, stale-checked); the
  keytool password of the fake vCenter is hex (a URL-safe one could start with a dash and fail the run).

## 1.0.0.9 (Unreleased)

Review of builds 7 and 8 (`docs/reviews/p3-objects-review.md`).

- fix(adapter): a read that FAILED no longer gets a placeholder. The cluster DRS keys and DRS Score, the
  host ATS options, the install date and every template figure and text now tell a path that was read and
  is unset (the original's text) from one that vCenter refused or could not read (nothing sent, counted).
  A host that left between the host read and the option read sends no ATS value. A test per family.
- fix(adapter): a failed cycle fails every object of the account again, own objects included, so an
  unreachable vCenter sets the account DOWN as in P1.
- fix(adapter): `fetchSoftwarePackages` is lower case on the wire, like `installDate`; the ESXi package keys
  were never collected. The fixture refuses the capitalised names.
- fix(adapter): a refusal for lack of `Host.Config.Image` is one failed read per cycle naming the privilege.
- fix(adapter): a kind that had objects and reads as none is believed only after 12 collections in a row (a
  narrowed role must not delete history); an identifier listed twice counts twice (Q11); an item or library
  deleted between the list and its read is gone, not a failed read; no worker outlives the logout.
- fix(adapter): the DNS configuration without a dhcp flag is `None`; an object or list in a library answer
  prints as Python's repr.
- feat(adapter): `Collection Health|Collector:<name>|Causes` and `Collection|failure_causes` list the distinct
  failure causes with counts, so a run can say which reads failed from the health keys alone.
- docs: overview and installing rewritten for Q9/Q10, `Host.Config.Image` and the role guard; the design's
  superseded rows marked inline.
- test: the three timing candidates are polls or generous bounds.

## 1.0.0.8 (Unreleased)

Fixes from the first live differential of build 6 (P2 against the Python 0.8.1 baseline).

- fix(adapter) P2 live: the host license read asked the license manager for its `name`, which it does not have
  (it is not a managed entity), so vCenter refused it and no host had a license key;
  the read asks for `licenseAssignmentManager` only.
- fix(adapter) P2 live: the install date call is `installDate` on the wire, not `InstallDate`
  (vCenter refused it for every host).
- fix(adapter) P2 live: an unset value on a read that worked is published as the original prints
  it (quirk Q9): `None`, `null`, `[]`, `N/A`, 0 (VM, host, cluster, datastore, vCenter and the own kinds);
  a read that failed still sends nothing and is counted. One switch, `Quirks.UNSET_AS_UPSTREAM`.
- fix(adapter) P2 live: a NIC whose link is down is `half-duplex` (Q10); a datastore with no
  extents has `Number of Extents` 0; DRS Score is 0 while DRS is off or negative.

- fix(adapter): the own kinds follow the same rule: a figure that was read and is missing is 0 (Q9).

## 1.0.0.7 (Unreleased)

Phase P3, the pak's own objects (design 4.11, rows R227 to R252).

- feat(adapter): Content Library and Library Item objects, read from the vSphere
  Automation REST API (login, list, library, item list, item; logout in a
  `finally`) over the same TLS posture as the vim25 session, so the three vCenter
  TLS modes apply to it and a wrong pin sends no password. An item is a child of its
  library. A library or item that cannot be read is skipped and counted
  (`F-REST-LIST`, `F-REST-LIB`, `F-REST-ITEM`); its library's counts are not sent
  rather than counted over the items that answered.
- feat(adapter): VM Template objects, one per VM that vCenter marks as a template,
  from the VM read (no read of their own) with the datastore and folder names beside
  it. Identified by MoRef and vCenter UUID, so two templates of one name are two
  objects. Delivered whether or not the vCenter adapter also models the VM (DV-47).
- feat(adapter): the Content Libraries traversal spec (Content Library > Library Item).
- feat(adapter): an own object that vCenter no longer has is marked not existing by
  the platform after 12 complete cycles, and only for a kind that was read in full:
  a failed or partial read (a denied library, a failed item, the deadline, a VM with
  no `config.template`) proves nothing. Renamed objects take their new name.
- feat(adapter): the cycle now runs where the own objects are enumerated, at the top
  of the collection; the values of an object that is new start with the next cycle.
- fix(adapter): own objects reproduce upstream where it shows (Q8, one switch in
  `text/Quirks.java`): a text the answer does not have is the empty text; a figure it
  does not have is not sent as the zero upstream sends.
- test: `OwnObjectsTest` (libraries local, subscribed, empty, bare; items without files;
  templates in folders and with the same name; a denied library; failed items, list
  and login; kinds read in full; 2,620 items and 700 templates; the deadline; the TLS
  modes; the session), the describe test for the traversal spec, Python rounding and
  JSON text vectors, and `docs/p3-key-coverage.tsv`, generated from the key map with a
  value assertion for each of its 26 rows.

## 1.0.0.6 (Unreleased)

P2 review pass (`docs/reviews/p2-properties-review.md`).

- fix(adapter): match upstream 0.8.1 where it shows in the keys, for now: Tools Sync
  Time and Tools Upgrade Policy are the text `None`; a VM with no network adapter
  has no Virtual Disk keys; PCI ids are the signed form (`-7f7a` for 0x8086);
  blank strings are pushed as upstream pushes them, not withheld; a negative
  operation notification timeout is `0`. All behind switches in `text/Quirks.java`,
  listed in design section 5.8, with the key map notes corrected.
- fix(adapter): a VM Options entry is read as a value of one element, so one that is
  an object, a snapshot tree or a whole config cannot fail the VM page; one found
  not to be a single value is dropped from later reads; if the page fails anyway
  with entries in it, the VMs are read again without them. References of known
  types print as pyVmomi prints them, bytes as `b'...'`.
- fix(adapter): a read stops paging at the deadline and releases its server-side
  result set and view (`CancelRetrievePropertiesEx`), the probe's and a failed
  page's included; per-host calls stop too.
- fix(adapter): a `missingSet` fault is a failed read, not an absent value; a read
  in which every object threw is FAILED and its values counted; a collector
  fault is `F-COLLECT-<read>`.
- fix(adapter): a new object is no longer keyless for up to 12 cycles: the Ops list
  is read again for it at once, then after 2, 4, 8 cycles.
- fix(adapter): the pushed-value table takes 19 MB at 10,000 VMs (was 87 MB); a
  heap-capped test runs it. License assignments fall back to one call per host
  when the one call fails.
- fix(adapter): a 404 on the check-list JSON look no longer makes a file absent or
  clears the list; only its apiErrorCode is used; markup is never quoted.
- fix(adapter): a host that left between the two host reads no longer loses every
  host's options; package and license text is as sent; an EVC mode of spaces is a
  mode; the VM Options probe runs again after a new login.
- docs: design updated (4.5, 5.1, 5.5, register DV-68 to DV-77, new section 5.8);
  the coverage table now needs a test that compared each key's value.

## 1.0.0.5 (Unreleased)

Phase P2, the property keys.

- feat(adapter): clusters, hosts, virtual machines, datastores and the vCenter
  object get their `vCommunity|` property keys (146 rows of the key map; the
  count per row is in `docs/p2-key-coverage.tsv`, generated from the key map and
  checked against the tests). Each cycle reads the objects of a type through the
  property collector in pages, as a stream: one object is held at a time, and
  of a large value only what a key uses is kept. A page that fails fails its
  whole type (nothing from it is sent); an object with no unique Ops resource
  gets no keys and is counted; a failed read, a refused property path and a
  value that is not there are each withheld and counted, never sent as `None`,
  `null`, `Unknown`, `N/A`, `0` or `False`.
- feat(adapter): host license keys are masked to their last group; the full key
  is never pushed or logged (DV-25). PCI ids are four hex digits of the unsigned
  value (DV-33). A VM Options entry vCenter refuses is dropped and reported and
  costs only its own key (DV-26); a value that is an object or a list is skipped
  and reported once.
- feat(adapter): the certificate status (`Recorded`, `Unchanged`, `Changed`,
  previous thumbprint, last changed) is kept in memory and read back from the
  vCenter object once after a start; a failed read-back or probe sends no status
  and never records over what is there (DV-23).
- feat(adapter): `Maintenance Mode Required` is pushed as a numeric property
  (the framework's typed push, 1.0.27).
- feat(adapter): properties are sent when they change, after a start and in a
  full refresh every 12th cycle; numbers go every cycle (DV-49).
- fix(adapter): an absent check-list file is absent on 8.18.7 as on 9.x (the XML
  error there carries no code the framework reads; a refusal with none is asked
  about once more as JSON), and the `it said:` text is the server's message, never
  markup. An unexpected failure on one object is counted for that object and does
  not fail its type. A full refresh forgets pushed-value hashes of objects that
  are gone.
- test: fixture tests through the engine, the real vim25 client and a local
  server that answers hand-built SOAP: paged and large inventories (heap shape
  checked), optional parts missing, empty values, refused paths, failed pages,
  per-host call failures, the deadline, matching by UUID and MoRef.

## 1.0.0.4 (Unreleased)

- fix(adapter): a logout the vCenter refuses is no longer logged as "logged out":
  a refusal says `vCenter logout did not complete (...)`, and a session that had
  already ended says so (review W1).
- fix(adapter): Test Connection treats only the known no-credential failure of the
  Suite API client as a plain "not checked" line; any other reason the client
  cannot be built is a WARN naming the exception class, and the class is in the
  plain line too (W2).
- test: `BcHandshakeTest` runs real handshakes under BouncyCastle JSSE (jars
  fetched from Maven Central, SHA-256 pinned, test only, same as the compliance
  adapter): a wrong pin and an untrusted chain, through the adapter's own client
  (W3). The provider throws `TlsFatalAlert`, an IOException, with the pin
  mismatch as its direct cause.
- fix(adapter): the per-thread pin record is always taken, never left behind,
  and a stale one is not reported on a later failure (N1); an expired, not yet
  valid or revoked certificate no longer gets the "accept the certificate"
  advice (N2); server and provider text in the cycle summary and in TLS
  failures is one bounded line (N3); the test seam `VcenterTls.forTesting` refuses
  Enabled (N4).
- docs: wording for the TLS failures, the mid-cycle re-login (P2), and deviation
  DV-67 (Test Connection on an unsaved account reads no check-list files) (N5, N6).

## 1.0.0.3 (Unreleased)

- fix(adapter): the session is visible in the account log at INFO, one line each:
  login (vCenter product and version, new session), reuse each cycle, renewal
  when the held session expired, logout with its reason (`reconfigure` or
  `stop`); and one cycle summary line (duration, vCenter object matched, objects
  not matched, series and properties pushed, reads failed, values withheld,
  warnings). Before, only the expiry line existed, so a live run could not show a
  session being opened, reused or ended.
- fix(adapter): a pin mismatch keeps its message under FIPS. The provider's alert
  is an IOException that is not an SSLException, so the cause walk was skipped;
  the walk now covers every IOException, causes and suppressed, and the pinned
  trust manager records its decision per thread as a last resort. Other TLS
  failures name the alert and the mode; an untrusted chain in Enabled says to
  accept the certificate in Validate Connection or use Pinned.
- fix(adapter): Test Connection logs success at INFO only (never WARN); each thing
  needing attention is its own WARN line. A Suite API client that cannot be built
  during a test is a plain "not checked during a test" line, not a warning.
- fix(adapter): a failed check-list read shows the answer's own words (bounded),
  so an HTTP 400 with no apiErrorCode (seen on 8.18.7) can be told from a missing
  file next time; it is still a failed read, not an absent file.
- docs: log lines, Test Connection logging, creating an account through the API.

## 1.0.0.2 (Unreleased)

- fix(adapter): the session cookie is found whatever the case of the
  `Set-Cookie` header (a proxy in front of the vCenter may send it lower case);
  before, every cycle failed and left an authenticated session behind.
- fix(adapter): a count that was not measured is not sent: check-list entries
  skipped (unless every file read was parsed), objects not matched (unless the
  vCenter object lookup completed), on the adapter instance and on the vCenter
  object.
- fix(adapter): the period choices carry their labels (`01 hour` ... `24 hours`),
  every account field has a description, and the Trust on First Use text says
  plainly that credentials are sent before any certificate check; enums carry no
  `displayOrder` (not in the schema).
- fix(adapter): SOAP answers built into a tree are capped at 1 MiB and 32
  levels; redirects are not followed; a page of resources with no total is not
  taken for the whole list; a Unicode host is converted to punycode for the name
  check.
- fix(adapter): a skipped check-list entry is a reported problem, not a failed
  read; a file name is refused only when it is a path or a query; no runtime
  exception in the vCenter object lookup can end the cycle; a clean Test
  Connection logs at INFO.
- test: lower case headers, the real certificate name check in Enabled mode,
  oversized and deep answers, every unmeasured-count path.
- docs: the design copy is corrected (own-kind units, self-metric transport) and
  deviations DV-61 to DV-66 are registered; `docs/reviews/p1-skeleton-review.md`.

## 1.0.0.1

- feat(adapter): phase 1, skeleton and configuration parity. The adapter kind
  is `vcfcf_vcommunity`. describe.xml declares the 19 account fields and the
  `vsphere_user` credential of the original pack (keys, labels, defaults,
  choices, main form versus Advanced Settings), the `vCommunityCollector`
  object, the three own kinds with their 26 keys, and the `Collection|` self
  metrics on the adapter instance.
- feat(adapter): `VCommunityConfig` with the original pack's fallbacks and one
  warning per field; failures name the field and quote no secret.
- feat(adapter): the three vCenter TLS modes (trust on first use, platform
  trust with the certificate name checked, pinned SHA-256 or SHA-512), the
  certificate thumbprint probe, and a raw SOAP vim25 session (no JAX-WS)
  with keepalive, one relogin on a lapsed session, and one login attempt per
  cycle after a failure.
- feat(adapter): the collection cycle skeleton: session, probe, check-list
  files, the vCenter's own object matched by instance UUID, and the
  `vCommunity|Collection Health|` keys and `Collection|` self metrics. A failed
  read sends nothing and is counted; no value is ever sent that was not read.
- feat(adapter): Test Connection reports the login, the certificate
  thumbprint, whether the Suite API answers, and each check-list file's outcome
  and item count (in the adapter log).
- feat(adapter): check-list files: secure parse, per-list validation (VM option
  paths, Windows service names, the event list structure), last good list kept
  on a read or parse failure, an absent file empties its list.
- test: plain-main tests for every class outside the two SDK-facing ones,
  including the TLS modes and the session against a local TLS server with a
  certificate made at run time, and describe.xml pinned to the original pack.
- docs: README, overview, installing guide, reference and NOTICE (placeholder
  wording; the final text waits on the licensing record).
