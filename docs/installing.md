# Installing & Configuring: VCF Content Factory vCommunity

## Prerequisites

- VCF Operations 8.18.x or later (the 8.18, 9.0 and 9.1 lines are the ones this pak targets) with a collector (or Cloud Proxy) that can
  reach the vCenter's HTTPS port (443 unless the account says otherwise).
- The vCenter adapter (VMWARE) collecting the vCenter, so its objects exist in
  VCF Operations to receive the keys.
- A vCenter account the pak logs in with. It needs read access to the inventory
  it collects; later phases state what each collector reads.
- Optionally, a Windows account for the guest checks (off by default; see Guest monitoring below).

**The minimum version.** The content of this pak is converted and gated for VCF
Operations 8.18.x; that is the minimum version (owner decision, 2026-11-14). It was tested on 8.18.7, 9.0.2 and 9.1.1. The `manifest.txt` the pak carries says
`vcops_minimum_version 8.0.0` (a constant of the factory builder, not a decision of this pak), so an
8.10 to 8.17 instance will accept the install: do not install it on anything older than 8.18.x.

## Moving from the Python pack

**Uninstall the Python pack first, then install this one.** The Python pack is Onur Yuzseven's
1.x "VCF Operations vCommunity" or Ewan's 2.x "vCommunity" adapter. Running it and this pak
side by side is not supported: both write the same `vCommunity|...` keys onto the same vSphere
objects, and both ship content with the same names (dashboards, views, reports, alerts), so
every list shows twice and the two collect over each other. There is no way to make that work
from this pak's side. This pak is a separate adapter kind (`vcfcf_vcommunity`), and its
content is minted under ids of its own, so it does not overwrite the Python pack's content and
uninstalling one does not delete the other's.

What carries over (owner decision, 2026-11-14: names stay, ids are our own):

- **Metrics and properties.** The keys are the same names on the same vSphere objects, so the
  history of those keys continues across the move. (The keys are the same by construction and
  by the key comparison in `docs/design/`; the continuation of history across an uninstall and
  install was not measured on an instance.)
- **Dashboards, views, reports, super metrics, symptoms, alerts and the recommendation** are
  replaced by this pak's copies, under new ids and with the same names. Anything you cloned
  or customized from the Python pack's content is yours to keep or to re-point at this pak's
  copies. The alert history of the Python pack's alert definitions stays with those
  definitions (they go away with the uninstall), not with this pak's alerts.
- **Check-list files.** This pak ships the same six file names as the Python pack
  (`esxi_advanced_system_settings`, `esxi_packages`, `vm_advanced_parameters`, `vm_options`,
  `windows_service_list`, `windows_event_list`). Uninstalling the Python pack with the
  remove-content option removes its copies of those names, and this pak's pak upgrade replaces
  them too (below). **Save your edited check lists before the move and re-apply them** in
  copies under other names (see "Check-list files"). That the uninstall removes the files is
  inferred from the platform's code on 9.0.2 and from the measured behavior of the source pack,
  not measured for the Python pack's own files.

**Built-in views.** Some versions of the Python pack ship views under VCF Operations'
own built-in view ids: Onur's 0.1.5 (41 views) and 0.1.6 to 0.3.1 (117), and Ewan's 0.4.2 to
0.8.1 (120). Installing such a version overwrote those built-in views, and **uninstalling it
deletes them** (measured on 8.18.7, 9.0.2 and 9.1.1; factory note
`2026-11-12-vcommunity-upstream-builtin-view-ids`). Built-in dashboards that use them then show
"The view does not exist". Before you uninstall one of those versions, have a clean export of the built-in
views of the same VCF Operations version at hand (taken from an instance that never had the
Python pack: an export from the affected instance holds the pack's copies). After the uninstall,
import the views from that export (Content > Import, views); this was measured to restore
them. This pak never uses a built-in view id for its own content, so it neither overwrites nor
deletes a built-in view; the 19 built-in views its dashboards and reports use in place must
exist on your instance, which is why the restore matters.

**Supported path: uninstall, then install fresh.** This is the only supported way onto this pak
from the Python pack, and from any earlier dev build of this pak that used different content ids
(builds up to 0.0.0.16). An in-place upgrade across an id change is not supported. Normal upgrades
between releases that keep their ids are in-place as usual.

**A safe order:** (1) save your edited check-list files and note anything you changed in the Python pack's content;
(2) have the clean built-in views export ready if your version is one of those named above;
(3) uninstall the Python pack with remove-content; (4) restore the built-in views if it
shipped any; (5) install this pak; (6) re-apply your check lists.
The order is a recommendation. It was not run as a whole on an instance with this pak.

**Same adapter kind as the retired earlier port.** `vcfcf_vcommunity` was also the
adapter kind of the retired 2026 Java port of this pack. On an instance that still has
that port (its `1.0.0.9` pak ships 63 views under built-in ids), installing this pak is an upgrade of it, not
a second pack. On 9.x an upgrade deletes the content the new version no longer ships,
including those 63 built-in views and everything shipped under ids this pak no longer uses.
Inferred, not measured. Remove the earlier port first, and restore the built-in views, as above.

## Permissions Required

Read-only access to the vCenter inventory. The license assignments and the
vCenter settings are read with the privileges of the account in the credential
(Global > Licenses and Global > Settings in a custom role; a read-only role may
not have them: a refused read is counted as `F-SOAP-R-LIC` or `F-SOAP-R-OPT` and
withholds only those keys; this is not yet checked on a live vCenter). The host install
date and the ESXi software packages need `Host.Config.Image` on the hosts (Host >
Configuration > Image configuration): without it each is one failed read per cycle
(`F-HOST-INSTALLDATE`, `F-HOST-PACKAGES`) that names the privilege, and those keys are not sent.
The content libraries are listed only as far as the account may see them; a library the account
may not read is skipped and counted (`F-REST-LIB`). Guest operations (off by default) need the credential's Windows User Name and Windows Password and the guest
operations privileges on the VMs it checks.

## Network Requirements

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| 443  | HTTPS    | Collector to vCenter | vim25 SOAP (`/sdk`), the certificate probe; later the REST API |
| 443  | HTTPS    | Collector to the local VCF Operations | Suite API (push, resource lookup, configuration files) |

## TLS: the vCenter certificate

The account's **vCenter TLS Validation** (Advanced Settings) has three modes.

| Mode | What it does |
|------|--------------|
| Trust on First Use (default) | Accepts any certificate and any name. The account's credentials go to whatever answers, before any certificate check. The presented thumbprint is read every cycle and compared with the one recorded on the vCenter object: `vCommunity|Certificate|Status` is `Recorded` (first sight), `Unchanged` or `Changed` (a different certificate, shown for 24 hours, with the previous thumbprint and the time). A report, not a refusal; if the Suite API cannot give back what was recorded, the status is left out rather than guessed. |
| Enabled | Uses the collector's own trust, including certificates accepted in Validate Connection, and checks that the certificate names the host entered. Enter the host by a name on the certificate. |
| Pinned | Compares the SHA-256 (64 hex digits) or SHA-512 (128) thumbprint of the vCenter's certificate with **vCenter Thumbprint (SHA-256)**, during the handshake and before any credential is sent. The chain and the name are not checked. |

An unknown mode, or Pinned with an empty or malformed thumbprint, fails the
account with a message that names the field. A wrong pin fails with the
thumbprint the server presented.

In Enabled mode, Validate Connection offers the usual certificate review dialog.

## Configuration Fields

The first five are in the main form and part of the account's identity. The
rest are under Advanced Settings.

| Field | Key | Required | Default | Notes |
|-------|-----|----------|---------|-------|
| vCenter Server | `host` | Yes | | FQDN or IP of the vCenter, as it should be dialled. |
| ESXi Advanced System Settings Config File | `esxi_adv_settings_config_file` | No | `esxi_advanced_system_settings` | Name (no `.xml`) of the check-list file naming the ESXi advanced settings to collect. |
| ESXi Software Packages Config File | `esxi_vib_driver_config_file` | No | `esxi_packages` | Check-list file naming the ESXi packages (VIBs, drivers) whose versions are collected. |
| VM Advanced Parameters Config File | `vm_adv_settings_config_file` | No | `vm_advanced_parameters` | Check-list file naming the VM advanced parameters (`.vmx` keys) to collect. |
| VM Options Config File | `vm_configuration_config_file` | No | `vm_options` | Check-list file naming the VM configuration properties to collect, as dotted property paths. |
| Port | `port` | Yes | 443 | HTTPS port of the vCenter. |
| vCenter TLS Validation | `vcenter_tls_validation` | No | Trust on First Use | Trust on First Use, Enabled or Pinned (see above). |
| vCenter Thumbprint (SHA-256) | `vcenter_thumbprint` | No | | The pin, used only in Pinned mode. Colons, dashes and spaces are ignored. |
| Cluster Check Period (hours) | `cluster_check_period_hours` | No | 24 | One of 01, 02, 04, 06, 08, 12, 24. |
| Cluster Check Time (UTC) | `periodic_cluster_check_time` | No | 00:00 | Time of day, `HH:MM` UTC, at which a window ends. |
| Cluster Check Worst Percent | `cluster_check_worst_percent` | No | 5 | 0 to 50: the share of a window's worst samples set aside. |
| Monster VM CPU (% of host cores) | `monster_vm_cpu_percent_of_cores` | No | 50 | 1 to 100. |
| Monster VM Memory (% of host DRAM) | `monster_vm_memory_percent_of_dram` | No | 40 | 1 to 100. |
| Monster VM Memory Minimum (GB) | `monster_vm_memory_min_gb` | No | 256 | 1 or more. |
| Windows Service Configuration File | `win_service_config_file` | No | `windows_service_list` | Check-list file naming the Windows services to check; read only when service monitoring is Enabled. |
| Windows Event Log Configuration File | `win_event_config_file` | No | `windows_event_list` | Event log list for the guests; read only when event monitoring is Enabled. |
| Guest OS Service Monitoring Status | `serviceMonitoring` | No | Disabled | Enabled or Disabled (case sensitive). Enabled reads the Windows service list and asks Windows guests for their operating system and the listed services. |
| Guest File Transfer TLS Validation | `guest_tls_validation` | No | Pinned | Pinned, Strict or Disabled. A wrong value switches the guest phase off and the rest collects. |
| Windows Event Log Monitoring Status | `winEventMonitoring` | No | Disabled | Enabled or Disabled (case sensitive). |

A setting that is out of range (a period not in the list, a time that is not
`HH:MM`, a percentage outside its range) falls back to its default with one
warning in the log. A blank value falls back silently.

Credential (kind `vsphere_user`): vCenter User Name, vCenter Password, and
optionally Windows User Name and Windows Password.

## Guest monitoring (off by default)

Both switches (Guest OS Service Monitoring Status, Windows Event Log Monitoring Status) are Disabled until
you enable them. With both Disabled no guest operation of any kind is made, the Windows password is not used (it is held with the
rest of the account, as any credential field is) and the collector asks vCenter for nothing it needs only for guests. To use them:

1. Set **vCenter TLS Validation to Enabled or Pinned** (with the vCenter Thumbprint). In Trust on First Use, the default, guest
   monitoring does not run: the Windows account would be sent to a vCenter whose certificate nobody checked. The
   collector says so in `Collector:guest|Status` (`FAILED`, `F-GUEST-TLS-MODE`) and Test Connection warns. (The original sends
   the account regardless; DV-103.)
2. Fill the credential's Windows User Name and Windows Password fields. **Use an account made for this, never an account whose
   lockout would hurt** (see "Account lockout" below). The vCenter account needs the
   `VirtualMachine.GuestOperations.Query`, `.Modify` and `.Execute` privileges on the VMs. The Windows account is sent to vCenter only, in
   the guest operations calls.
3. Fill `windows_service_list` (service names, comma separated; only letters, digits, space and `_ . $ # @ ( ) + -`) and, for
   events, `windows_event_list` (`<Events><Log Name="System"><EventID>7036</EventID></Log></Events>`). Only the services you list
   are published, one row for each name.
4. Leave the TLS setting for guest transfers on Pinned (or Strict). The file transfer goes to the VM's ESXi
   host on port 443; the host's certificate must be the one vCenter has recorded for it. Pinned and Strict
   refuse a host with no recorded thumbprint (the original went on unchecked); Disabled accepts any
   certificate and is logged as a warning every collection.
5. Read the result in the `Collector:guest|Status`, `|Failures` and `|Causes` keys of the vCenter object and
   in `Collection|guest_vms_done` and `guest_vms_pending` of the adapter instance.

### Account lockout

A wrong Windows password must not lock the account. Until a logon has worked since the adapter started, **one VM is asked alone**; a
refused logon counts, and **the second one pauses guest monitoring for 24 hours** (or until the account is edited, which starts the adapter
again). A VM that refused the account is not asked again for 24 hours. Once a logon has worked, four VMs are asked at a time; if two
refuse in one collection the credential is taken for changed and the phase pauses the same way (the VMs already started may be
refused too: up to five refused logons at most). A restart of the adapter forgets all this (the framework keeps no state for an
adapter), so it starts again with one VM alone: a restart loop costs one refused logon per restart. Tell your directory
administrators these numbers: a wrong password costs at most two refused logons per start of the adapter and per day after that,
a credential changed behind the adapter's back costs at most five once. Windows records each refused logon as event 4625 on the
guest (and 4771 / 4776 on the domain controllers); `vmtoolsd` may retry once per call.

### Security products

The programs run as `powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand <text>`, started by `vmtoolsd.exe` as the Windows
account. That command line is what endpoint detection products look for. Allow it by its parent (`vmtoolsd.exe`), the account and the
program's text (it holds no account or password; decode it with `[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('<text>'))`):
three fixed programs, each of which reads one file it was given and writes one file, in a directory under `C:\Windows\Temp` named
`VCFOperationsvCommunity-<number>-<program>-TEMP`. PowerShell script-block logging (event 4104) will record the program text.

### Failure causes

The guest phase runs after the cycle's other keys are sent. Four VMs are asked at a time once the account has worked; a VM that is not
reached before the cycle's time is up is the first one the next collection asks. What each cause means:

| Cause | Meaning |
|---|---|
| `F-GUEST-TLS-MODE` | vCenter TLS Validation is Trust on First Use: nothing is sent (see step 1) |
| `F-GUEST-TOOLS` | a powered-on Windows VM with its tools not running, or a VM in no state for guest operations |
| `F-GUEST-AUTH` | the guest or vCenter refused the Windows account, or the phase is paused or leaves that VM alone after a refusal (see Account lockout) |
| `F-GUEST-UNAVAILABLE` | guest operations are not available on that VM |
| `F-GUEST-TIMEOUT` | a call, a transfer or the program took too long (the program is stopped and its directory removed) |
| `F-GUEST-EXIT`, `-START` | the program ended with a failure code, or could not be started |
| `F-GUEST-QUERY` | an event query could not be run in the guest (a log the account may not read, or one that is not there); named by its log, the events that were found are sent |
| `F-GUEST-WINDOW` | the VM was last read more than an hour ago: the events before the last hour were not looked at |
| `F-GUEST-UPLOAD`, `-DOWNLOAD`, `-TLS` | a file transfer failed; `-TLS` is a host certificate that is not the recorded one, no usable recorded thumbprint, or no SHA-1 in this JVM |
| `F-GUEST-TOOLARGE`, `-PARSE`, `-HEADER`, `-ROW` | the output is over 1 MiB, is not readable CSV (or was cut short, or has no header), lacks a needed column, or had rows left out (too short, not asked for, or text that cannot be a property or a key) |
| `F-GUEST-DIRECTORY`, `-CLEANUP` | the guest named a directory or file the port does not use (nothing is run or deleted there), or the directory could not be removed (it is tried again the next time that VM is asked) |

A VM that failed sends nothing for the failed program: a service is never shown as stopped because a read failed, and a window shown
as having no events is one whose queries all ran. A service that does not exist has no key.

Windows events are diagnostic events on the VM (its Events tab), not notifications and not alerts. Each is
sent once and not again for two hours, at most 100 per VM per collection and no more than there are entries in the event list. The
program looks back at least five minutes, and as far as the time since the VM was last read (an hour at most). After a restart of the
adapter the events still in view are sent once more. The text is as the guest wrote it, line breaks included.

## Content

The pak installs the content of the original pack with it. After the install it
holds:

| Content | Count | Notes |
|---|---:|---|
| Views | 170 | Carried over from the original pack. 101 of them began as edited copies of VCF Operations' own views; they carry new descriptions (nine also have new titles, below) and are shipped under ids minted for this pak (see "Moving from the Python pack"). |
| Dashboards | 52 | The original pack's, including the "Report:" and "Object Summary Page" dashboards. |
| Reports | 16 | The "VOA" report templates (capacity, configuration, inventory, performance, executive summary and the CSV export reports). Below 9.0.2 the five summary reports ship without their cover page text color (below). |
| Super metrics | 2 | See "Super metrics" below. |
| Symptom definitions | 10 | On ESXi hosts, virtual machines and the vCenter (VMwareAdapter Instance) objects. |
| Alert definitions | 4 | ESXi Host NIC Disconnected, ESXi Host License Expiring, Windows Service Down and vCommunity Collection Warnings. One recommendation ships with the last. |
| Check-list files | 6 | See "Check-list files" below. |

Alert definitions that read a key the pak publishes on a new object start from the
second collection, because a new key has no earlier value to compare.

**Views that were renamed.** Nine views had a title equal to one of VCF
Operations' own views (on 8.18.7, 9.0.2 or 9.1.1) and were retitled so the two
can be told apart in the views list. `docs/design/inputs/view_rename.tsv` has the old and new
title of each:

| Old title | New title |
|---|---|
| Guest OS Distribution | VM Distribution by Guest OS Name |
| Guest OS Memory Paging Trend | Guest OS Page In and Page Out Trend |
| Guest OS Partition List | Guest OS Root Partition Usage List |
| vSphere Cluster Performance Profiling | vSphere Cluster Worst-Case Contention Profile |
| vSphere ESXi Memory Utilization | vSphere ESXi Memory Capacity and Use Trend |
| vSphere Performance Profiling | vSphere VM 99th Percentile Performance Profile |
| VCF Management Services Nodes View | VCF Management Services Node Resource List |
| VCF Management Services Volumes View | VCF Management Services Volume Usage List |
| VCF Management Services Components View | VCF Management Services Component Health List |

**Built-in views used in place.** Nineteen views of the original pack only renamed
a view VCF Operations already has. The pak does not install them: the dashboards
and reports that used them point at the built-in view by its id
(`docs/design/inputs/dropped.tsv` lists the 19). All 19 ids are in a clean
export of each of 8.18.7, 9.0.2 and 9.1.1, as recorded in the factory's stock
id baseline; they were not looked at on a running instance.

**No Summary pages are bound.** Six dashboards are named ". Object Summary Page for ..."
(compute folder, distributed port groups, distributed switch, network folder, storage folder, VM
folder). In the original pack they are plain dashboards, and so they are here: the pak has no
`content/dashboards/dashboards.properties`, so nothing replaces the built-in Summary tab of those
object kinds. Binding one to a Summary tab is a step of its own (the original's
`docs/configuration-guide/summary-dashboards.md`); this pak does not do it.

**ESXi Host License Expiring covers one license name.** As in the original pack, the
alert's symptoms read `vCommunity|Licensing:vSphere 8 Enterprise Plus for VCF|Remaining Days`,
the key of one fixed license name. The alert does not fire for a host holding any other license
(for example "VMware Cloud Foundation (cores)" or "Evaluation Mode"). It is reproduced for the match
with the original and is a candidate to fix (design 5.8).

**Two dashboards without Section rows.** "Overall Daily Check" (6 Section rows)
and "vSphere Cluster Capacity 2.0" (2) use the Section widget, which VCF
Operations imports only from 9.1.1. The pak is built for a floor below that, so
these two install **without** their Section header rows and their other widgets
as they were. A pak built for 9.1.1 and later keeps the Section rows; no content
change is needed for that, only a build for that floor.

**Five reports without their cover page text color on versions below 9.0.2.** The cover
page of the five summary reports (VOA Capacity, Configuration, Executive Summary,
Inventory and Performance) carries a text color. VCF Operations 8.18.7 rejects the
template that holds it (`Invalid content was found starting with element 'FontColor'`;
measured on a fresh install of build 18, 2026-11-15 UTC: 11 of 16 report templates
existed), so from build 19 these five ship without the color element and all 16
import on 8.18.7. On 9.0.2 and 9.1.1 the pak still carries the color. The loss is cosmetic:
the cover page text uses the default color, nothing else of the reports changes.

**Notes for 8.18.7.** In the lab, a collection cycle took about 13 to 22 s on 8.18.7,
against about 2 s on 9.0.2 and 8 s on 9.1.1 (same vCenter and content; the
`Collection cycle <n> finished in <s> s` line in the account log). After check lists
were re-applied, the first cycle logged `VM Advanced Parameters item 'ide0:0.autodetect'
contains '|' or ':'; it is published under exactly that key`. That is a note, not a
failure, and the entry is **not skipped**: only the VM Options list (an item must be a
dotted property path) and the Windows services list (an unsafe service name) reject
entries. The note exists because `|` and `:` mean something in a VCF Operations key
(`|` separates groups), so the item is collected and shows as the property
`Configuration|Advanced Parameters|ide0:0.autodetect` on the VM, spelled as listed. It is
written once per item for the life of the adapter instance. If the key is not wanted,
remove the item from your copy of the file.

**Super metrics.** "CPU Usable Capacity Utilization" (vSphere clusters) and
"Number of VM" (distributed port groups and distributed switches) are on objects
of the vCenter adapter. A super metric computes only for an object whose
policy has it enabled: turn both on in whichever policy governs those objects,
or they show no values. They ship **not enabled** in the default policy, as in the
original pack (measured on all three lab instances). The alert "vCommunity Collection
Warnings" also ships not enabled in the default policy, as in the original; enable it
in the policy that governs the vCenter adapter's objects. The two stay super metrics,
not computed metrics of this pak (a percentile has no form in a computed metric, and
they are the original pack's own content).

**Text from the original pack.** The names and descriptions that ship as the
original pack wrote them are listed, by type and field, in
`docs/design/inputs/carried-text-report.md`. The 101 views that began as edited copies of built-in views have
descriptions written for this pak.

## Check-list files

Six files name what to collect (ESXi advanced system settings, ESXi packages,
VM advanced parameters, VM options, Windows services, Windows event logs). Each is
an XML document stored in the VCF Operations configuration-file store under
`SolutionConfig/<name>.xml`, and the pak installs all six with these names, the
names the account fields default to: `esxi_advanced_system_settings`,
`esxi_packages`, `vm_advanced_parameters`, `vm_options`, `windows_service_list`
and `windows_event_list` (each with `.xml`).

As shipped, every entry in every file is commented out, so nothing is collected
until you list something. Each file explains its own format in its header and
gives examples, one per line.

The list files (all but the event list) hold a comma separated list. Items are
separated by commas, **not** by line breaks: two items on two lines with no comma
between them are one item with a line break in it, which is skipped and reported.
XML comments are ignored, so a file with every entry commented out is an empty
list. The Windows event list is a different shape: an `Events` root with one `Log`
element per event log and one `EventID` per number.

**Clone a shipped file; do not edit it.** In VCF Operations 9.0 the files are under
Infrastructure Operations, Configurations, Management Packs Configuration, in
System Defined; clone one, give the copy a name that differs from the shipped one
in more than letter case, edit the copy, and enter the copy's name (no `.xml`) in
the account field. Why: **a pak upgrade replaces every one of the six files that
has a default name, even without "force content update"**. Measured on VCF Operations
9.0.2 and 9.1.1 on 2026-11-14 (build 15 installed over an earlier build): four
operator-edited files were overwritten byte for byte. So an edit to a file with a
default name is lost on every pak upgrade. The supported way is:

- copy the file under another name and point the account's field at the copy. Each
  file name is configurable in the account (the field takes the name without `.xml`):

  | Shipped file | Account field (key) |
  |---|---|
  | `esxi_advanced_system_settings` | ESXi Advanced System Settings Config File (`esxi_adv_settings_config_file`) |
  | `esxi_packages` | ESXi Software Packages Config File (`esxi_vib_driver_config_file`) |
  | `vm_advanced_parameters` | VM Advanced Parameters Config File (`vm_adv_settings_config_file`) |
  | `vm_options` | VM Options Config File (`vm_configuration_config_file`) |
  | `windows_service_list` | Windows Service Configuration File (`win_service_config_file`) |
  | `windows_event_list` | Windows Event Log Configuration File (`win_event_config_file`) |

- back the files up before every upgrade, whichever names you use;
- uninstalling with the remove-content option deletes the files the pak installed
  (read from the platform's code on 9.0.2; not measured). A copy under another name
  is not one of them.

If the Python pack was installed on the instance before, its six files
(the same names) may already be in the store, and installing this pak replaces them
the same way; uninstalling the Python pack removes its copies (inferred, see "Moving from the Python pack").

**With the shipped check lists, two keys read `None`.** Every entry is commented out
as shipped, so `Guest|Tools Sync Time` and `Guest|Tools Upgrade Policy` read the text
`None` on every VM (design quirk Q1: the original pack looks them up under
`config.tools`, a path its read never has unless `config.tools` is listed in the VM
options file). List `config.tools` in your copy of the VM options file to get the
real values.

## Step-by-Step Installation

1. Install the `.pak` file via **Administration > Solutions > Add**.
2. Open **Data Sources > Integrations > Accounts**, click **Add Account** and
   select **VCF Content Factory vCommunity**.
3. Fill in **vCenter Server** and the credential. Open **Advanced Settings** for
   the rest.
4. Click **Validate Connection**, then **Add**.
5. The cycle starts at the next collection (five minutes by default).

## What Test Connection checks

Validate Connection logs in to the vCenter and reads its certificate. A failed
login (including a wrong pin in Pinned, or an untrusted certificate in Enabled,
also under FIPS) fails the test and the dialog shows why.

It does **not** check the Suite API or read the check-list files. A test runs
before the account exists, and the Suite API client uses a credential the
platform gives a configured account, so the client cannot be built during a test
(the report says "not checked during a test", not a warning; any other reason
the client cannot be built is still a warning, with the exception class). If the Suite API
client can be built where the test runs, the test uses it: it then asks whether
the API answers and reads each check-list file in use, and a file that is
missing, unreadable or invalid is reported, never fatal. Collection reports the
Suite API's state every cycle (the Collection Health keys, the log).

What the test logs, and where: its success line is written at INFO, and **a test
has no per-account log**, so under the pak's logging setup an INFO line from a
test is not written to any file. Only what needs attention is written, at WARN,
one line each (`Test Connection: ...`), into the collector's `collector.log`.
A passing test therefore leaves no log line; the dialog is its result. (Showing a
success message in the dialog is not done: it is not known whether the dialog
displays one for a passing test.)

## Logs

The account's own log is
`/storage/log/vcops/log/adapters/VCommunityAdapter/VCommunityAdapter_<id>.log`
on the node that collects. The pak writes INFO lines to it, and the build ships
`conf/logging.properties` (`com.vcfcf.adapters.vcommunity=INFO`) so the account
log is at INFO by default (8.18 ignores that file, and the adapter raises its own
logger to INFO there). These lines are always written, no secret, no object
names:

| Event | Line (start) | When |
|-------|--------------|------|
| Login | `vCenter login succeeded (new session): <product> <version>` | the first cycle, and after a failed or lost session |
| Reuse | `vCenter session reused (kept alive, no new login): <product> <version>` | every cycle that keeps the session |
| Renewal | `vCenter session renewed (the held session had expired; logged in again)` | the vCenter ended the session |
| Cycle | `Collection cycle <n> finished in <s> s: ... series pushed=..., reads failed=...` | every cycle (`failed after` when the login failed) |
| Logout | `vCenter session logged out (reason: reconfigure)` or `(reason: stop)` | the account was edited, or the adapter instance was stopped or removed |

A re-login in the middle of a cycle (a call finding the session gone) writes
only a WARN, `session not authenticated on <operation>; logging in again`, and
the call is repeated once.

Each property read also writes one INFO line, for example `R-VM pages=50
rows=2500 bytes=... calls=... maxRowNodes=... droppedPaths=0`: its pages, objects
and bytes, and the most elements held for one object (the heap check). A property
vCenter refuses writes `F-SOAP-INVALIDPROP` once, with the path.

A logout the vCenter refuses reads `vCenter logout did not complete (reason: ...)`,
and one for a session that had already ended reads `vCenter session had already
ended (reason: ...)`; neither says "logged out".

If an account is edited you should see the logout line, then `VCommunityAdapter
configured` and a new-session login line on the next cycle.

## Creating an account through the API

The UI fills in the describe defaults; the REST API does not.

- **Send every non-credential identifier explicitly** (the describe declares 19; the live 0.0.0.2 run needed 18 of them) on
  `POST /api/adapters`: the server applies no `default=` from the describe, so an
  omitted identifier is not defaulted by the platform. (The pak reads an omitted
  check-list file field as its default name, and an empty string as "no list".)
- **A new instance starts STOPPED.** Start it with
  `PUT /api/adapters/{id}/monitoringstate/start`.
- **`POST /api/adapters/testConnection` stores the credential** it was given as a
  side effect. Delete it afterwards if the test was a probe.

## Troubleshooting

- **Account shows an error with "vCenter TLS Validation ... is not one of"** or
  "Thumbprint": the field named in the message is wrong; fix it on the account.
- **A wrong pin**: the message carries the thumbprint the server presented.
  Compare it with the certificate you trust before changing the pin.
- **Account down**: the vCenter cannot be reached from the collector (host name,
  route, port or firewall).
- **`Collection|vcenter_object_resolved` is 0**: no object of kind
  VMwareAdapter Instance carries the vCenter's instance UUID (the vCenter is not
  collected by the VMWARE adapter), or two do (a duplicate vCenter account). The
  log line says which.
- **`Collection|reads_failed` is not 0**: `Collection|last_error` names the first
  cause; the collector log carries one WARN line per cause with the first object.
