# Installing & Configuring: VCF Content Factory vCommunity

## Prerequisites

- VCF Operations 8.18, 9.0 or 9.1 (the lines this pak targets) with a collector (or Cloud Proxy) that can
  reach the vCenter's HTTPS port (443 unless the account says otherwise).
- The vCenter adapter (VMWARE) collecting the vCenter, so its objects exist in
  VCF Operations to receive the keys.
- A vCenter account the pak logs in with. It needs read access to the inventory
  it collects; later phases state what each collector reads.
- Optionally, a Windows account for the guest checks (a later phase).

**Do not install it next to the original vCommunity pack on the same VCF
Operations instance.** The two are different adapter kinds and would write the
same keys. This pak is not an upgrade of the original: its objects are new
objects with no history carried over, and uninstalling the original removes the
built-in views it shipped. The migration guide arrives with the content phase.

## Permissions Required

Read-only access to the vCenter inventory. Guest operations (a later phase,
off by default) need the Windows account in the credential and the guest
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
| Trust on First Use (default) | Accepts any certificate and any name. The account's credentials go to whatever answers, before any certificate check. This build reads the presented thumbprint every cycle but stores and compares nothing: recording it and reporting a change (the Certificate keys) arrives with the vCenter collector phase. |
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
| Cluster Check Period (hours) | `cluster_check_period_hours` | No | 24 | One of 01, 02, 04, 06, 08, 12, 24 (a later phase). |
| Cluster Check Time (UTC) | `periodic_cluster_check_time` | No | 00:00 | Time of day, `HH:MM` UTC, at which a window ends (a later phase). |
| Cluster Check Worst Percent | `cluster_check_worst_percent` | No | 5 | 0 to 50: the share of a window's worst samples set aside (a later phase). |
| Monster VM CPU (% of host cores) | `monster_vm_cpu_percent_of_cores` | No | 50 | 1 to 100 (a later phase). |
| Monster VM Memory (% of host DRAM) | `monster_vm_memory_percent_of_dram` | No | 40 | 1 to 100 (a later phase). |
| Monster VM Memory Minimum (GB) | `monster_vm_memory_min_gb` | No | 256 | 1 or more (a later phase). |
| Windows Service Configuration File | `win_service_config_file` | No | `windows_service_list` | Check-list file naming the Windows services to check; read only when service monitoring is Enabled. |
| Windows Event Log Configuration File | `win_event_config_file` | No | `windows_event_list` | Event log list for the guests; read only when event monitoring is Enabled. |
| Guest OS Service Monitoring Status | `serviceMonitoring` | No | Disabled | Enabled or Disabled (case sensitive). Guest checks are a later phase. |
| Guest File Transfer TLS Validation | `guest_tls_validation` | No | Pinned | Pinned, Strict or Disabled. A wrong value switches the guest phase off and the rest collects. |
| Windows Event Log Monitoring Status | `winEventMonitoring` | No | Disabled | Enabled or Disabled (case sensitive). |

A setting that is out of range (a period not in the list, a time that is not
`HH:MM`, a percentage outside its range) falls back to its default with one
warning in the log. A blank value falls back silently.

Credential (kind `vsphere_user`): vCenter User Name, vCenter Password, and
optionally Windows User Name and Windows Password.

## Check-list files

Each file is an XML document whose text is a comma separated list, stored in the
VCF Operations configuration-file store under `SolutionConfig/<name>.xml`.
Items are separated by commas, **not** by line breaks: two items on two lines
with no comma between them are one item with a line break in it, which is
skipped and reported. XML comments are ignored, so a file with every entry
commented out is an empty list. Clone a reference file and enter the clone's
name in the account; do not edit the reference file.

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

A re-login in the middle of a cycle (a call finding the session gone) will write
only a WARN, `session not authenticated on <operation>; logging in again`. No
vim25 read exists in this phase to cause it; the row is added when the first one
lands (P2).

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
