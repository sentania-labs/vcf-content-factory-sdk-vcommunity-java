# Install guide

For the long form (every message text, measurement and edge case) see the [detailed reference](installing-reference.md).

Menu paths are as seen on VCF Operations 9.1. They differ slightly on 8.18 and 9.0.

## Before you start

- VCF Operations 8.18 or later.
- The built-in vCenter adapter already collecting the vCenter. This pak adds data to its objects.
- A vCenter account with read access. For license and host package data it also needs Global > Licenses,
  Global > Settings and Host > Configuration > Image configuration.
- The collector must reach the vCenter on port 443.
- Coming from the Python vCommunity pack? Read [Moving from the Python pack](#moving-from-the-python-pack) first.

## Install

1. Go to **Administration > Integrations > Repository** and click **Add**.
2. Tick **Ignore the PAK file signature checking**, then upload the pak. The pak is unsigned; without the tick
   the upload fails with only "Upload failed."
3. Accept the license, confirm the unsigned-pak prompt with **Yes**, and wait for **Finish**. This takes six to
   eight minutes.
4. Go to **Administration > Integrations > Accounts**, click **Add**, and pick **VCF Content Factory vCommunity**.
5. Enter a name, the **vCenter Server**, and a credential (the **+** next to the credential field creates one).
   Enter the vCenter by a name that is on its certificate, not an IP address. This field cannot be changed later.
6. Click **Validate Connection**. Review and **accept** the certificate when asked. You should see
   "Test connection successful."
7. Click **Add**. Collection starts within seconds, and the account shows "Collecting" after a few minutes.

Leave Advanced Settings alone unless you need them. Every setting has a working default.

## After installing

Three things are off until you turn them on:

| What | How to turn it on |
|---|---|
| Most dashboards (40 of 52) | Enable the ones you want in the dashboard list |
| The 4 alerts and 3 super metrics | **Administration > Configurations > Policy Definition**, edit your active policy: **Alerts and Symptoms** for the alerts, **Metrics and Properties** for the super metrics |
| Network Speed Degradation | See [below](#network-speed-degradation) |

On dashboards with a picker, click a row and then choose **Internal interaction** to drive the other widgets.

## Certificates

The vCenter certificate is verified, the same way the built-in adapters do it.

| If you see | Do this |
|---|---|
| "certificate is not trusted by this collector" | Edit the account, click Validate Connection, accept the certificate, save |
| "certificate does not carry the name ..." | The vCenter was entered by an IP or a name that isn't on its certificate. Create the account again using a name on the certificate |
| "certificate is expired" | Renew it on the vCenter |
| The account says "Collecting" but there is no data | The certificate was never accepted. Edit the account and click Validate Connection ([#34](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/34)) |

For a lab, **Allow Insecure SSL** (Advanced Settings) set to `true` turns the checks off. Guest monitoring will
not run while it is on.

## Account settings

| Setting | Default | What it does |
|---|---|---|
| vCenter Server | | Name of the vCenter, as it appears on its certificate. Cannot be changed later |
| Port | 443 | vCenter HTTPS port |
| Four "Config File" fields | the shipped file names | Which [check list](#check-lists) to use. Cannot be changed later |
| Allow Insecure SSL | false | `true` turns off certificate checks (lab only) |
| Cluster Check Period | 24 hours | How often the periodic cluster check closes a window |
| Cluster Check Time (UTC) | 00:00 | When a window ends |
| Cluster Check Worst Percent | 5 | Share of the worst samples set aside |
| Monster VM CPU / Memory / Memory Minimum | 50 % / 40 % / 256 GB | What counts as a monster VM |
| Guest OS Service Monitoring Status | Disabled | See [Guest monitoring](#guest-monitoring) |
| Windows Event Log Monitoring Status | Disabled | See [Guest monitoring](#guest-monitoring) |
| Guest File Transfer TLS Validation | Pinned | Leave as is |
| Windows Service / Event Log Configuration File | the shipped file names | Check lists for guest monitoring |

If a setting has a value that isn't allowed, the default is used and the vCenter object's property
`vCommunity|Collection Health|Configuration Warnings` names it.

## Check lists

Some data is only collected for items you list: ESXi advanced settings, ESXi packages, VM advanced parameters
and VM options. The pak ships one file for each, with every entry commented out.

Do not edit the shipped files. An upgrade overwrites them. Make a copy instead:

1. Go to **Administration > Configurations > Management Packs Configuration**. The shipped files are under
   **System Defined**.
2. Select a file, for example `esxi_packages.xml`, and choose **Clone**.
3. Name the copy and **type the `.xml` yourself**, for example `esxi_packages_mine.xml`. Without `.xml` the
   adapter cannot find it.
4. The copy appears under **User Defined**. Edit it and uncomment the entries you want, separated by commas.
5. Edit the account and enter the name **without** `.xml` in the matching field, for example `esxi_packages_mine`.

The nenic, nfnic and VIB Info views need `nenic`, `nfnic` and `nsx-host` in the packages list.

## Network Speed Degradation

This compares each host NIC's actual speed with its configured speed. It needs two things:

1. The host property **Network | Configured Speed** (`net|configuredSpeed`) enabled in your policy
   (Policy Definition > edit the policy > Metrics and Properties). It is off by default.
2. NICs with a fixed speed set in vCenter. A NIC on auto-negotiate has no configured speed and is skipped.

Hosts with no qualifying NIC show no value.

## Guest monitoring

**Preview only. Off by default. Not yet verified.** It logs in to Windows guests through VMware Tools to read
services and event logs.

Know this before turning it on:

- It tries **every** powered-on Windows VM with VMware Tools running, including domain controllers. There is
  no way to exclude VMs ([#17](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/17)).
- If two VMs refuse the account, it stops for all VMs for 24 hours. This protects the account from lockout,
  but one domain controller refusing a non-admin account is enough to trigger it
  ([#28](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/28)).
- The Windows rights the account needs are not yet determined
  ([#30](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/30)).
- Use a dedicated account, never one whose lockout would hurt.

To try it: add a Windows user name (`user@domain`) and password to the credential, list services in a copy of
`windows_service_list` and events in a copy of `windows_event_list`, then set the two monitoring settings to
Enabled. Results and failures appear in the `Collector:guest` keys on the vCenter object. The
[detailed reference](installing-reference.md#guest-monitoring-off-by-default) covers lockout numbers, security
product allow-listing and failure codes.

## Upgrading

Upgrade in place by uploading the new pak. Shipped check-list files are overwritten; your copies are kept.

## Uninstalling

**Administration > Integrations > Repository**, the pak's **...** menu, **Uninstall**. It takes about two minutes.

- Removed: the account, its credential, the pak's dashboards, views, reports, alerts and super metrics, and
  the six shipped check-list files.
- Kept: your check-list copies, the built-in views, your policies, and the data already collected.

## Moving from the Python pack

Running both packs side by side is not supported. Both write the same keys to the same objects.

1. Save any check-list files you edited.
2. Uninstall the Python pack.
3. If built-in dashboards now show "The view does not exist", [restore the built-in views](#restoring-built-in-views).
4. Install this pak and add the account.
5. Re-apply your check lists as copies.

What changes:

- **Metric history carries over.** The key names are the same.
- **Content is replaced** by this pak's copies under the same names. Re-point anything you cloned.
- **Certificate handling is stricter.** The Python pack accepted any certificate by default. This pak verifies it.

### Restoring built-in views

Some Python pack versions overwrote VCF Operations' built-in views (Onur's 0.1.5 to 0.3.1 and Iwan's 0.4.2 to
0.8.1). Uninstalling those versions deletes the built-in views.

You need an export of the built-in views from a clean instance of the same VCF Operations version. Ask the
maintainer for one; a script will ship with the public release
([#32](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/32)).

1. Go to **Administration > Control Panel > Content Management**, **Import** tab.
2. Browse to the export zip.
3. Leave **Overwrite existing content** selected and import.
4. Check that every view shows as imported.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Upload failed." | Tick "Ignore the PAK file signature checking" before uploading |
| Certificate messages | See [Certificates](#certificates) |
| Account down | The collector cannot reach the vCenter: check name, route, port, firewall |
| No data on vSphere objects | The built-in vCenter adapter is not collecting this vCenter, or two accounts point at it |
| A check-list copy is ignored | It was named without `.xml`, or the account field includes `.xml` |
| Views or columns are empty | Many depend on policy settings or check lists. See [After installing](#after-installing) and [Check lists](#check-lists) |

The account's log is on the collector node that runs it:
`/storage/log/vcops/log/adapters/VCommunityAdapter/VCommunityAdapter_<id>.log`. Health is on the vCenter object
under `vCommunity|Collection Health`.

Anything else: [open an issue](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues).
