# Install guide: detailed reference

This is the long form: every measurement, message text and edge case. Start with the short [install guide](installing.md).

## Prerequisites

- VCF Operations 8.18.x or later (the 8.18, 9.0 and 9.1 lines are the ones this pak targets) with a collector (or Cloud Proxy) that can
  reach the vCenter's HTTPS port (443 unless the account says otherwise).
- The vCenter adapter (VMWARE) collecting the vCenter, so its objects exist in
  VCF Operations to receive the keys.
- A vCenter account the pak logs in with. It needs read access to the inventory
  it collects; later phases state what each collector reads.
- Optionally, a Windows account for the guest checks (off by default; see Guest monitoring below).

**The minimum version.** The content of this pak is converted and gated for VCF
Operations 8.18.x; that is the minimum version (owner decision, 2026-11-14). Collection was tested on 8.18.7, 9.0.2 and 9.1.1; what was seen live for the certificate handling and the settings is under "TLS: the vCenter certificate" (build 29 has been installed on 9.1.1 only). The pak's manifest says `vcops_minimum_version: 8.18.0`, so an older instance refuses the install.

## Moving from the Python pack

**Uninstall the Python pack first, then install this one.** The Python pack is the vCommunity management pack, versions 0.1 to 0.8.1. Running it and this pak
side by side is not supported: both write the same `vCommunity|...` keys onto the same vSphere
objects, and both ship content with the same names (dashboards, views, reports, alerts), so
every list shows twice and the two collect over each other. There is no way to make that work
from this pak's side. This pak is a separate adapter kind (`vcfcf_vcommunity`), and its
content is minted under ids of its own, so it does not overwrite the Python pack's content and
uninstalling one does not delete the other's.

**The TLS setting does not carry over.** The Python pack's vCenter TLS Validation (Trust on First Use by default) and
vCenter Thumbprint fields do not exist here: a new account verifies the vCenter certificate (accept it in Validate
Connection) and the vCenter must be entered by a name on its certificate.

What carries over (owner decision, 2026-11-14: names stay, ids are our own):

- **Metrics and properties.** The keys are the same names on the same vSphere objects, so the
  history of those keys continues across the move. Measured on 8.18.7 (2026-11-16): after the
  Python pack was uninstalled and this pak installed, each key kept one continuous series.
- **Dashboards, views, reports, super metrics, symptoms, alerts and the recommendation** are
  replaced by this pak's copies, under new ids and with the same names. Anything you cloned
  or customized from the Python pack's content is yours to keep or to re-point at this pak's
  copies. The alert history of the Python pack's alert definitions stays with those
  definitions (they go away with the uninstall), not with this pak's alerts.
- **Check-list files.** This pak ships the same six file names as the Python pack
  (`esxi_advanced_system_settings`, `esxi_packages`, `vm_advanced_parameters`, `vm_options`,
  `windows_service_list`, `windows_event_list`). Uninstalling a pak removes the
  check-list files it installed (measured for this pak on 9.1.1, see "Uninstalling"; not measured for the Python
  pack's own files), and this pak's pak upgrade replaces them too (below). **Save your edited check lists before the
  move and re-apply them** in copies under other names (see "Check-list files").

**Built-in views.** Some versions of the Python pack ship views under VCF Operations'
own built-in view ids: 0.1.5 (41 views), 0.1.6 to 0.3.1 (117), and 0.4.2 to
0.8.1 (120). Installing such a version overwrote those built-in views, and **uninstalling it
deletes them** (measured on 8.18.7, 9.0.2 and 9.1.1; factory note
`2026-11-12-vcommunity-upstream-builtin-view-ids`). Built-in dashboards that use them then show
"The view does not exist". Before you uninstall one of those versions, have a clean export of the built-in
views of the same VCF Operations version at hand, taken from an instance that never had the
Python pack: an export from the affected instance holds the pack's copies. The export has to
include out-of-the-box content; the default export scope leaves built-in views out. After the
uninstall, import that export:

1. Sign in as an administrator and open **Administration > Control Panel > Content Management**
   (the tile under "Content and Support"), then the **Import** tab. The path is the same on
   8.18.7, 9.0.2 and 9.1.1 (seen on all three).
2. Browse to the export zip. The "File Content" table shows one row per content type with a total (for example
   "Views 120"), not the individual views.
3. Leave "In case of conflict" on **Overwrite existing content** (the default) and import.
4. The results table should show every view imported, none skipped or failed.

Measured on 9.1.1 with vCommunity 0.8.1 (2026-11-16): the uninstall deleted all 120 built-in views,
the import brought all 120 back (identical to before apart from whitespace in 12 descriptions),
and the built-in dashboards rendered again. The same import was run on 9.0.2 and 8.18.7 (117
views each) and is safe to repeat. Not verified: whether a restored view is protected from
editing and deletion the way a never-deleted built-in view is. This pak never uses a built-in view id for its own content, so it neither overwrites nor
deletes a built-in view; the 19 built-in views its dashboards and reports use in place must
exist on your instance, which is why the restore matters.

**Supported path: uninstall, then install fresh.** This is the only supported way onto this pak
from the Python pack, and from any earlier dev build of this pak that used different content ids
(builds up to 0.0.0.16). An in-place upgrade across an id change is not supported. Normal upgrades
between releases that keep their ids are in-place as usual.

**A safe order:** (1) save your edited check-list files and note anything you changed in the Python pack's content;
(2) have the clean built-in views export ready if your version is one of those named above;
(3) uninstall the Python pack; (4) restore the built-in views if it
shipped any; (5) install this pak; (6) re-apply your check lists.
Steps 3 to 5 were run in this order on 9.1.1 (uninstall of vCommunity 0.8.1, restore of the
built-in views, fresh install of this pak, which then collected normally and left the built-in
views untouched). Steps 1 and 6, the check lists, were not part of that run.

**Same adapter kind as the retired earlier port.** `vcfcf_vcommunity` was also the
adapter kind of the retired 2026 Java port of this pack. On an instance that still has
that port (its `1.0.0.9` pak ships 63 views under built-in ids), installing this pak is an upgrade of it, not
a second pack. On 9.x an upgrade deletes the content the new version no longer ships,
including those 63 built-in views and everything shipped under ids this pak no longer uses.
Inferred, not measured. Remove the earlier port first, and restore the built-in views, as above.

## Network Speed Degradation

**Limitation:** NICs on auto-negotiate (the normal setting for 10 GbE and faster) have no configured speed and are
skipped, so this figure covers only fixed-speed NICs. Do not set a fixed speed to get it. See issue
[#39](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/39).

Network Speed Degradation (host), Total Network Speed Degradation (Datacenter and vSphere World)
compare each host NIC's actual link speed with its configured speed. Both are properties of the host's
NICs in VCF Operations, one pair per NIC: `net:<nic>|speed` (Network | Actual Speed) and
`net:<nic>|configuredSpeed` (Network | Configured Speed, for example `net:vmnic0|configuredSpeed`).

The vSphere adapter's stock policy does **not** collect the configured speed: in the VMWARE
`HostSystem` describe the property `net|configuredSpeed` has `default_monitored: false` (`net|speed` is
collected). Until it is enabled, no host reports a configured NIC speed, so the figure is not sent for any
host, Datacenter or the World. That is not a collection failure: `kpi` status, causes, warnings and the withheld
count are unchanged, and the adapter log notes it a single time at INFO (`F-POLICY-NIC-CONFIGURED-SPEED`) on the first
cycle and again when it changes. A NIC with no value, a zero, a value that is not a number, or no link (a
disconnected NIC reports a speed of 0) does not count; a host none of whose NICs has both values gets no figure,
and only connected hosts count in the Datacenter and World numbers.

To get the figure, enable the property `net|configuredSpeed` (Network | Configured Speed) as a HostSystem
attribute in the policy that applies to your hosts: **Administration > Configurations > Policy Definition**, select
the policy (the Active one), **Edit**, then the **Metrics and Properties** tile (seen on 9.1.1; the editor was
opened and nothing changed, so the exact toggle was not exercised, and 8.18.7 and 9.0.2 were not looked at). Measured live on 9.0.2 (build 22): after the property was enabled in policy, hosts whose NICs have
a fixed speed in vCenter had `net:<nic>|configuredSpeed` by the second collection and published 100.0.

A NIC whose link speed is left on auto-negotiate in vCenter gets **no** configured-speed property at all (not a 0),
even with the property enabled. Such a NIC does not count, so a host whose NICs are all on auto-negotiate publishes no
figure. (In the lab, 30 of 36 NICs are on auto-negotiate.) Only 9.0.2 was measured; 8.18.7 and 9.1.1 are not.

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

**The vCenter certificate is verified by default**, as the built-in adapters do it. Nothing needs setting: add the
account, click **Validate Connection**, and VCF Operations shows the vCenter's certificate in its standard review
dialog. Review it and accept it; from then on the connection is checked against the certificate you accepted, with the
platform's own trust (a certificate that chains to a CA the collector already trusts needs no accepting). The
certificate must also carry the name you entered in **vCenter Server**, and credentials are sent only after both checks
pass.

**Enter the vCenter by a name that is on its certificate.** An IP address works only if the certificate lists that
address; most vCenter certificates carry names, so an address fails the name check. Accepting the certificate in Validate
Connection does not change a name mismatch.

What you see when it is not right (no credential was sent in any of them). In the **cycle** (the account's status,
`Collection|last_error`, the log) the account reports the sentences below. In **Validate Connection**, VCF Operations
shows its own review dialog and certificate message first, and the pak adds advice after it where it helps (accept the certificate
for an untrusted one; renew it for an expired one; enter a name on the certificate for a name mismatch). For a name mismatch the
review dialog still appears first, and the pak's message comes after you click ACCEPT (accepting does not fix it); VCF Operations
then asks "Proceed anyway?" and lets you save the account, which then fails every cycle:

| Situation | What the account reports | What to do |
|---|---|---|
| The certificate has not been accepted and the collector does not trust it | "the vCenter's certificate is not trusted by this collector. Edit the account, click Validate Connection, review the certificate and accept it ..." | Click Validate Connection and accept the certificate. |
| The name entered is not on the certificate | "the vCenter's certificate does not carry the name '<name>' ... Enter the vCenter by a name that is on its certificate ..." | Enter a name that is on the certificate (or reissue the certificate with the name or address). |
| The certificate is expired, not yet valid or revoked | "the vCenter's certificate is expired, not yet valid or revoked. Renew the certificate on the vCenter." | Renew it on the vCenter. |
| The vCenter certificate is renewed later | the connection fails as in the first row | Validate Connection again and accept the new certificate. |

**The name check stays on, and it means an account entered by IP address fails.** An account whose vCenter was entered by
IP address, or by any name that is not on the certificate, fails every cycle until the vCenter is entered by a name the
certificate carries. Accepting the certificate does not help. **The vCenter Server field is the account's identity** (with
the four check-list file names), so changing it means a **new account** (and the content libraries, items and templates of
the old one are recreated under it); you cannot repair such an account by editing the field. Plan the account with the
certificate name from the start.

**Allow Insecure SSL** (Advanced Settings, default `false`, the same setting as in the other VCF Content Factory paks)
turns the certificate checks off, trust and name together. Only the text `true` does it. With it on, the vCenter user name
and password go to whatever answers at that address, **guest monitoring does not run** (the Windows account would go the
same way), the adapter logs a warning each time the account is configured, and Test Connection writes a WARN line to
`collector.log` (the test itself passes). Use it for a lab only.

**The certificate keys are published in both settings, on every cycle that connects.** After the login, the adapter reads the
thumbprint the vCenter presents (one handshake that sends nothing) and compares it with the one recorded on the vCenter object:
`vCommunity|Certificate|Status` is `Recorded` (first sight), `Unchanged` or `Changed` (a different certificate, shown for 24
hours, with the previous thumbprint and the time). A report, not a refusal; if the Suite API cannot give back what was
recorded, the status is left out rather than guessed. With verification on, a certificate the collector does not trust
fails the login, so nothing is published that cycle (the vCenter object is not known without the login): `Changed` appears
only for a new certificate the collector already trusts. A renewal by an untrusted certificate shows as a login failure
with the "not trusted" sentence, never as `Changed`. After a reinstall the status reads `Unchanged` straight away, because the
recorded thumbprint stays on the vCenter object; `Recorded` is only for a vCenter never seen before (it was not seen in the live runs).

**What was seen live.** With dev build 0.0.0.27 on **8.18.7** (a collector that trusts only leaf certificates) and **9.0.2**, and with
build 0.0.0.29 on **9.1.1**. Each line is for the version named; nothing is claimed for the others.

- **8.18.7, build 27, upgraded account:** the account upgraded in place from 0.0.0.26 kept its adapter instance id, listed
  `allowInsecure` = `false` at once, and no longer listed `vcenter_tls_validation` or `vcenter_thumbprint` (the old values did
  not stay until the next save). The first cycle failed closed after 0.0 s with no vCenter login (checked in the vCenter's
  events) and the account showed ERROR with the pak's "not trusted" sentence. Edit account, Validate Connection showed "Review
  and Accept Certificate" ("Untrusted certificate found.", the thumbprint, who it was issued to and by, the expiry, buttons
  CANCEL and ACCEPT) with no password re-entry; after ACCEPT it said "Test connection successful."; saving gave a verified login
  and clean cycles. The raw platform failure there is `the trustAnchors parameter must be non-empty` (inside
  `CustomTrustManager$CustomCertificateException`); the operator sees the pak's sentence instead.
- **9.0.2, build 27, upgraded account:** the lab's CA was already in the instance's trust, so there was no dialog: the account
  logged in verified on its first cycle and Validate Connection went straight to "Info: Test connection successful." Right
  after the install the account read FAILED/NONE and needed a start. Name mismatch (the vCenter entered by IP): the review dialog
  came first, then after ACCEPT the error "The target's TLS certificate does not name <ip>. Open the account and enter the host
  by a name on the certificate ... Accepting the certificate does not fix a name mismatch ...". On an account whose chain is also
  untrusted the cycle reports "not trusted" first; the name message appears once the chain is trusted.
- **9.1.1, build 29, fresh install on a FIPS collector, following this guide:** the Add Account form showed every dropdown on
  its default (see "Configuration Fields"); Validate Connection on the new account showed "Review and Accept Certificate"
  ("Untrusted certificate found.", CANCEL and ACCEPT), and after ACCEPT "Info: Test connection successful."; four clean
  cycles followed with 0 warnings, 0 failed reads, 0 values withheld, every collector OK (guest SKIPPED) and `verifyCertificate=true`
  in the log. Name mismatch (IP): the review dialog, then the name error with the pak's advice, then "Proceed anyway?" let the
  account be saved; its cycles then failed after 0.0 s with no login (see the two behaviours below).
- **Allow Insecure SSL** (8.18.7, 9.0.2 and 9.1.1): `true` collects with the configure WARN in the account log, `false` again is
  verified on the next cycle without accepting the certificate again. On 9.1.1 Validate Connection with it on has no dialog
  and succeeds at once; the WARN line is in `collector.log` on the node that runs the account's collector (found on 9.0.2 and
  9.1.1, not found in any log on 8.18.7).
- **Not seen:** `Recorded` (first sight) as a certificate status; an expired or revoked certificate on a live instance.

**Three behaviours to know about (preview).** A **new** account whose certificate is not trusted looks healthy: it stays
"Collecting" with an empty status message and publishes no `Collection|*` keys; only the account log says why (an account with
existing resources does show ERROR, "Collection failed for all N resource(s)"). So **always click Validate Connection and
accept the certificate before saving a new account**; tracker issue [#34](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/34). In the cycle log the certificate sentences are cut
off before their end (the "not trusted" one loses its last sentence, the name one is cut mid-word); the table above has the full
text; [#35](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/35). And **Validate Connection with Allow Insecure SSL on shows only "Test connection successful."**: the warning is in
`collector.log`, not in the dialog; [#36](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/36).

The ESXi host certificates used by guest file transfers are a separate setting (**Guest File Transfer TLS Validation**).

## Configuration Fields

The first five are in the main form and part of the account's identity. The
rest are under Advanced Settings.

| Field | Key | Required | Default | Notes |
|-------|-----|----------|---------|-------|
| vCenter Server | `host` | Yes | | A name that is on the vCenter certificate (an IP address works only if the certificate lists it). Part of the account's identity: changing it means a new account (see "TLS: the vCenter certificate"). |
| ESXi Advanced System Settings Config File | `esxi_adv_settings_config_file` | No | `esxi_advanced_system_settings` | Name (no `.xml`) of the check-list file naming the ESXi advanced settings to collect. |
| ESXi Software Packages Config File | `esxi_vib_driver_config_file` | No | `esxi_packages` | Check-list file naming the ESXi packages (VIBs, drivers) whose versions are collected. |
| VM Advanced Parameters Config File | `vm_adv_settings_config_file` | No | `vm_advanced_parameters` | Check-list file naming the VM advanced parameters (`.vmx` keys) to collect. |
| VM Options Config File | `vm_configuration_config_file` | No | `vm_options` | Check-list file naming the VM configuration properties to collect, as dotted property paths. |
| Port | `port` | Yes | 443 | HTTPS port of the vCenter. |
| Allow Insecure SSL | `allowInsecure` | No | `false` | Only the text `true` turns off the certificate checks (see "TLS: the vCenter certificate"). Guest monitoring does not run while it is on. |
| Cluster Check Period (hours) | `cluster_check_period_hours` | No | 24 | One of 01, 02, 04, 06, 08, 12, 24; the form shows them with a unit, for example "24 hours". |
| Cluster Check Time (UTC) | `periodic_cluster_check_time` | No | 00:00 | Time of day, `HH:MM` UTC, at which a window ends. |
| Cluster Check Worst Percent | `cluster_check_worst_percent` | No | 5 | 0 to 50: the share of a window's worst samples set aside. |
| Monster VM CPU (% of host cores) | `monster_vm_cpu_percent_of_cores` | No | 50 | 1 to 100. |
| Monster VM Memory (% of host DRAM) | `monster_vm_memory_percent_of_dram` | No | 40 | 1 to 100. On a host with memory tiering the percentage is of DRAM plus the NVMe tier (DV-109). |
| Monster VM Memory Minimum (GB) | `monster_vm_memory_min_gb` | No | 256 | 1 or more. |
| Windows Service Configuration File | `win_service_config_file` | No | `windows_service_list` | Check-list file naming the Windows services to check; read only when service monitoring is Enabled. |
| Windows Event Log Configuration File | `win_event_config_file` | No | `windows_event_list` | Event log list for the guests; read only when event monitoring is Enabled. |
| Guest OS Service Monitoring Status | `serviceMonitoring` | No | Disabled | Enabled or Disabled (case sensitive). Enabled reads the Windows service list and asks Windows guests for their operating system and the listed services. |
| Guest File Transfer TLS Validation | `guest_tls_validation` | No | Pinned | Pinned, Strict or Disabled. A wrong value switches the guest phase off and the rest collects. |
| Windows Event Log Monitoring Status | `winEventMonitoring` | No | Disabled | Enabled or Disabled (case sensitive). |

**A setting that was left alone takes its default, silently.** The form on 9.1.1 shows "--Select--" for an
enumerated field nobody touched and VCF Operations stores the text `null` for it; an absent value, a blank one and the
text `null` (any case, spaces around it) all mean "not set" for every optional field above, and the default applies
with no warning. The enumerated fields declare their default as the preselected choice. **Seen on 9.1.1 with build 29:** the Add Account form,
untouched, showed Cluster Check Period "24 hours", Guest OS Service Monitoring Status "Disabled", Guest File Transfer TLS
Validation "Pinned" and Windows Event Log Monitoring Status "Disabled", with Allow Insecure SSL `false` and no "--Select--"; the stored values
after saving were `24`, `Disabled`, `Pinned`, `Disabled` and `false`. On 9.0.2 with build 27 (before the fix) the form still showed
"--Select--". The preselection was not looked at on 8.18.7 or 9.0.2 with build 29. (A blank check-list file field is the one exception: it reads no file; the text `null` there is "not set".)

**A value that is not allowed** (a period not in the list, a time that is not `HH:MM`, a percentage outside its range,
a switch that is neither Enabled nor Disabled, a guest TLS mode that is not one of the three) falls back to the default
(a wrong guest TLS mode switches the guest phase off instead) and is reported by **one WARN line in the account log each
time the account is configured** (saved, or the adapter started). It is not repeated every cycle and does not raise
`Warnings in Last Cycle` or `First Warning` of Collection Health. It stays visible three ways: the property
**`vCommunity|Collection Health|Configuration Warnings`** on the vCenter object (one text naming each setting that is not
allowed, its value and what is used instead; `None in the configuration` when every setting is usable; it goes out with the
other Collection Health properties, on change and in the full refresh every 12th cycle, and keeps its value in between);
the lines of **Test Connection** (each as a WARNING in the result and a WARN in `collector.log`); and the WARN line in the
account log. The shipped alert "vCommunity Collection Warnings" does not look at this property. Seen on 9.1.1 with build 29
(Cluster Check Period set to `7` through the API): one WARN at configure and none on later cycles, `Warnings in Last Cycle`
stayed 0, the property named the setting about a minute after the cycle that computed it, and after the value was restored it
cleared after the next cycle plus about a minute and a half. (Test Connection's WARN in `collector.log` was seen; the dialog
itself was not looked at.) (Builds before 28 warned every
cycle, so Collection Health showed a warning for as long as the value stood.)

**Two every-cycle warnings stay on purpose.** Guest monitoring switched on while Allow Insecure SSL is on, and guest
monitoring switched on with no Windows credential, each log a WARN every cycle and count in `Warnings in Last Cycle`: the
guest collector is not running although you asked for it, and the alert should keep telling you.

Credential (kind `vsphere_user`): vCenter User Name, vCenter Password, and
optionally Windows User Name and Windows Password.

## Guest monitoring (off by default)

Both switches (Guest OS Service Monitoring Status, Windows Event Log Monitoring Status) are Disabled until
you enable them. With both Disabled no guest operation of any kind is made, the Windows password is not used (it is held with the
rest of the account, as any credential field is) and the collector asks vCenter for nothing it needs only for guests. To use them:

1. Leave **Allow Insecure SSL** off (the default) and accept the vCenter certificate in Validate Connection: guest monitoring
   needs no other TLS setup. With Allow Insecure SSL on, guest monitoring does not run: the Windows account would be sent to a
   vCenter whose certificate nobody checked. The collector says so in `Collector:guest|Status` (`FAILED`,
   `F-GUEST-TLS-INSECURE`) and Test Connection writes a WARN line (the test itself passes). (The original sends the account regardless; DV-103.)
2. Fill the credential's Windows User Name and Windows Password fields. **Use an account made for this, never an account whose
   lockout would hurt** (see "Account lockout" below). The user name is passed to the guest as you type it (the adapter does not
   change or check its form). The UPN form (`user@domain`) was accepted on two member servers (live run on 9.0.2); other
   forms (local account, `DOMAIN\user`) were not tested. Which Windows rights the account needs is not settled (issue
   [#30](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/30)). The vCenter account needs the
   `VirtualMachine.GuestOperations.Query`, `.Modify` and `.Execute` privileges on the VMs. The Windows account is sent to vCenter only, in
   the guest operations calls.
3. Fill `windows_service_list` (service names, comma separated; only letters, digits, space and `_ . $ # @ ( ) + -`) and, for
   events, `windows_event_list` (an `Events` root, see "Check-list files"). Only the services you list
   are published, one row for each name.
4. Leave the TLS setting for guest transfers on Pinned (or Strict). The file transfer goes to the VM's ESXi
   host on port 443; the host's certificate must be the one vCenter has recorded for it. Pinned and Strict
   refuse a host with no recorded thumbprint (the original went on unchecked); Disabled accepts any
   certificate and is logged as a warning every collection.
5. Read the result in the `Collector:guest|Status`, `|Failures` and `|Causes` keys of the vCenter object and
   in `Collection|guest_vms_done` and `guest_vms_pending` of the adapter instance.

**What the first live run showed (9.0.2, build 26).** Guest collection has been run once against real Windows guests, and
it is not ready for general use:

- **Every powered-on Windows VM with VMware Tools running is in scope**, domain controllers included. There is no per-VM
  filter or exclusion list, and no control over which VM goes first (the order is by vCenter object id). There is no supported way to keep one VM out;
  if that matters, leave guest monitoring off. Only the services and events you list are collected.
- **One refusal can stop everything.** The account (UPN form) was accepted on two member servers, and the collection ran
  there. Two domain controllers refused it (the account has no local logon right on a domain controller), and that paused
  guest collection for all VMs (see "Account lockout"). The values published for the two accepted VMs were not checked.
- **The log and the health keys do not name the VM that refused** (`guest: F-GUEST-AUTH x2` and nothing more); issue
  [#28](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/28).
- **`Collector:guest|Status` read SKIPPED with no causes** right after the refused run (seen once, shortly before guest
  monitoring was switched off again; it may be timing).
- **A restart or a reconfigure of the adapter forgets the gate state** (below).
- Whether guest collection should be its own pak, so that the rest does not carry this risk: issue
  [#29](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/29). What Windows rights the
  account needs, and how domain controllers and non-admin accounts behave: issue
  [#30](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/30); design questions about the
  gate: issue [#17](https://github.com/sentania-labs/vcf-content-factory-sdk-vcommunity-java/issues/17).

### Account lockout

A wrong Windows password must not lock the account. One rule decides whether a VM is asked (DV-97): a VM is started only while the
refused logons of the last 24 hours plus the VMs whose first logon is not yet answered stay below two. So the adapter causes at
most **two refused logons in 24 hours** for one credential, however many VMs there are. Until a logon has worked since the adapter
started, **the first VM is asked alone**; once a logon is accepted, up to four run in parallel, and after a refusal none
is started in that collection (one refused logon in the first collection, a second in the next at most); **the second refusal pauses guest monitoring for 24 hours** (or until the account is edited, which starts the
adapter again). After the pause one VM is tried alone, and one refusal pauses it again. A VM that refused the account is not asked
again for 24 hours, and a refusal ends that VM's run (no cleanup retry carries the account again). An accepted logon does not wipe a
refusal: two VMs that refuse within 24 hours pause the phase, as a changed credential does. A restart or a reconfigure of the adapter forgets all this
(the framework keeps no state for an adapter), so it starts again with one VM alone: a restart loop costs one refused logon per
restart. Tell your directory administrators these numbers: a wrong password, or a credential changed before a collection, costs at most
two refused logons per start of the adapter and per day after that; a credential changed while VMs are running costs at most five once
(each running VM refuses once). The adapter stops after two refused logons in a run; that limits the damage but is not a guarantee: a directory with a lockout threshold of two or fewer locks the account. Windows records each refused logon as event 4625 on the
guest (and 4771 / 4776 on the domain controllers); `vmtoolsd` may retry once per call.

### Security products

Guest monitoring runs PowerShell inside the guest: `powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand <text>`,
started by `vmtoolsd.exe` as the Windows account. Endpoint security products commonly flag or block that pattern, and
guest monitoring will not work where it is blocked. Review it with your security team before turning it on. What they
will want to know: there are three fixed programs; each reads one file it was given and writes one file, in a directory
under `C:\Windows\Temp` named `VCFOperationsvCommunity-<number>-<program>-TEMP`; the program text holds no account or
password and can be decoded with `[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('<text>'))`; PowerShell
script-block logging (event 4104) records it.

### Failure causes

The guest phase runs after the cycle's other keys are sent. Four VMs are asked at a time once the account has worked; a VM that is not
reached before the cycle's time is up is the first one the next collection asks. What each cause means:

| Cause | Meaning |
|---|---|
| `F-GUEST-TLS-INSECURE` | Allow Insecure SSL is on: nothing is sent (see step 1) |
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
| Report templates | 16 | The "VOA" report templates (capacity, configuration, inventory, performance, executive summary and the CSV export reports). Below 9.0.2 the five summary reports ship without their cover page text color (below). |
| Super metrics | 3 | See "Super metrics" below. |
| Symptom definitions | 10 | On ESXi hosts, virtual machines and the vCenter (VMwareAdapter Instance) objects. |
| Alert definitions | 4 | ESXi Host NIC Disconnected, ESXi Host License Expiring, Windows Service Down and vCommunity Collection Warnings. |
| Recommendations | 1 | Ships with the vCommunity Collection Warnings alert. |
| Inventory trees | 1 | |
| Configuration files | 6 | The check-list files, see "Check-list files" below. |

The Content tab of the pak on 9.1.1 after the install showed these counts: 52 dashboards, 170 views, 16 report templates,
4 alerts, 1 recommendation, 10 symptoms, 3 super metrics, 1 inventory tree, 6 configuration files.

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

**ESXi Host License Expiring covers every license.** The alert's symptoms read the generic key
`vCommunity|Licensing|Remaining Days` as an instanced key, so the alert covers each license a host holds that has an
expiration date (a perpetual license publishes no Remaining Days and never raises it). When a host holds several dated
licenses, the alert's severity is the band of the license nearest to expiring. This departs from the original pack,
whose symptoms named one fixed license (design 5.8, Q17).

**Upgrading from an earlier build or from the Python pack: some values keep their last stored value.** A key that this
build no longer sends is not deleted in VCF Operations (a pushed key cannot be deleted), so it keeps what it had.
- From build 25 on a value vCenter answered without is not sent at all (DV-112). Placeholders that the Python pack or
  an earlier build sent (the texts `None`, `null`, `[]`, `N/A`, `Unknown`, an empty text, and a 0 for an unset count,
  size or timeout) therefore stay on existing objects until a real value overwrites them; they are not cleared by the
  upgrade. A new object simply has no such key. A chart or a filter over those keys can still show the old
  placeholder text on objects that were collected before.
- Every cluster that has DRS off keeps the DRS Score of 0 it had, every host whose adapter list was not returned keeps its
  "Number of HBA" of 0, and a cluster that turns DRS off later keeps its last real score (DV-111).
- Worst ESXi Memory Tiering Latency changes unit at the upgrade: it was the raw statistic (nanoseconds, so about 283,000
  for a healthy host) and is now in microseconds (about 283), so its history shows a step down by a factor of 1,000
  (DV-113). The Periodic Cluster Check builds its worst-5 % risk and its closed-for-business flag on Cluster Risk
  Score samples, so the scores that were pinned before the upgrade colour one check window afterwards.
- NIC Duplex, Tools Sync Time and Tools Upgrade Policy had placeholder values before (`half-duplex` for a link that
  is down, `None`); the Tools keys are overwritten by the real value, a Duplex key that is no longer sent keeps its last
  value.
They stop being updated; the old value stays until it ages out of a widget's time window or is overwritten.

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

**Super metrics.** "CPU Usable Capacity Utilization" (vSphere clusters),
"Number of VM" (distributed port groups and distributed switches) and "vSphere Clusters
not Green" (the vSphere World; it counts the clusters whose Cluster Performance Score is
below 75, and does not count a cluster that has no score this cycle) are on objects
of the vCenter adapter. A super metric computes only for an object whose
policy has it enabled: turn all three on in whichever policy governs those objects,
or they show no values. The first two ship **not enabled** in the default policy, as in the
original pack (measured on all three lab instances); "vSphere Clusters not Green" is
expected to behave the same (not measured). The alert "vCommunity Collection
Warnings" also ships not enabled in the default policy, as in the original; enable it
in the policy that governs the vCenter adapter's objects. Seen on 9.1.1, with the editor opened and nothing changed: the
super metrics are listed under **Administration > Configurations > Super Metrics**, and a policy is edited under
**Administration > Configurations > Policy Definition**, select the policy, **Edit**, with the **Metrics and Properties**
tile (super metrics) and the **Alerts and Symptoms** tile (alert definitions); the toggles themselves were not exercised, and
8.18.7 and 9.0.2 were not looked at. The three stay super metrics,
not computed metrics of this pak (a percentile has no form in a computed metric, and
they are the original pack's own content).

**Text from the original pack.** The names and descriptions that ship as the
original pack wrote them are listed, by type and field, in
`docs/design/inputs/carried-text-report.md`. The 101 views that began as edited copies of built-in views have
descriptions written for this pak.

## Upgrading from a development build (the TLS settings changed)

No release has used the old settings: the three-value **vCenter TLS Validation** (Trust on First Use, Enabled, Pinned) and
**vCenter Thumbprint (SHA-256)** are gone, replaced by **Allow Insecure SSL** (default `false`). An account made on
development build 0.0.0.26 or earlier, upgraded in place:

- **The account survives.** Its identity is the vCenter Server field and the four check-list file names; the removed and
  the added fields are all Advanced Settings and not part of it, so the platform does not need to recreate it.
- **The old values are gone.** Seen on 8.18.7 and 9.0.2 (0.0.0.26 to 0.0.0.27, same adapter instance id): immediately after the
  upgrade the API listed `allowInsecure` = `false` and no longer listed `vcenter_tls_validation` or `vcenter_thumbprint`. The
  build never reads them anyway, so a Trust on First Use or Pinned account does **not** stay insecure or pinned.
- **It becomes verified, silently.** The new field has no stored value, which reads as `false`: the account verifies the
  vCenter certificate from the first collection after the upgrade. If the collector does not already trust that
  certificate (a lab vCenter with a self-signed certificate, which the old default accepted), every cycle fails with
  "the vCenter's certificate is not trusted by this collector" until the certificate is accepted. Nothing is collected in
  between, and no credential is sent.
- **What to do:** edit the account, click **Validate Connection**, review and accept the certificate, save. A lab that
  wants the old behaviour sets **Allow Insecure SSL** to `true` (guest monitoring then stays off). An account that had used
  Enabled and had accepted the certificate keeps working, because the accepted certificate is stored on the account.
  Seen on 8.18.7 with build 27: the upgraded account failed closed on its first cycle, Edit account, Validate Connection offered
  the certificate with no password re-entry, ACCEPT then "Test connection successful.", and saving gave a verified login. On 9.0.2
  (build 27) the lab CA was already trusted, so the account verified on its first cycle with no dialog. **If the dialog
  does not offer the certificate, or the new field is missing from the edit form, delete the account and create it again**
  (its content-library, item and template objects are recreated on the next collection; the keys on the vCenter objects are
  kept in VCF Operations).
- **An account entered by IP address, or by a name not on the certificate, fails the name check** and accepting the
  certificate does not change that. Enter the vCenter by a name on the certificate: the vCenter Server field is part of the
  account's identity, so this means a new account (with the content-library, item and template objects of the old one
  recreated under it); or set **Allow Insecure SSL** to `true` for a lab (guest monitoring then stays off).
- **Guest monitoring** that was refused under Trust on First Use (`F-GUEST-TLS-MODE`) runs after the certificate is accepted
  and the Windows account is filled in; the cause is now named `F-GUEST-TLS-INSECURE` and appears only with Allow
  Insecure SSL on.

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

Some content depends on a list: the five package views ("nenic VIB Vendor Distribution", "nenic VIB
Version Distribution", "nfnic VIB Vendor Distribution", "nfnic VIB Version Distribution" and "VIB Info")
show no data until the packages they chart (`nenic`, `nfnic` and `nsx-host`) are named in your copy of
`esxi_packages`.

The list files (all but the event list) hold a comma separated list. Items are
separated by commas, **not** by line breaks: two items on two lines with no comma
between them are one item with a line break in it, which is skipped and reported.
XML comments are ignored, so a file with every entry commented out is an empty
list. The Windows event list is a different shape: an `Events` root with one `Log`
element per event log and one `EventID` per number.

**Clone a shipped file; do not edit it.** A pak upgrade replaces every one of the six files that has a default name,
even without "force content update" (measured on 9.0.2 and 9.1.1 on 2026-11-14: build 15 installed over an earlier build
overwrote four operator-edited files byte for byte). So an edit to a file with a default name is lost on every upgrade.
Work on a copy under another name and point the account's field at it. The copy can only be made in the VCF Operations
user interface: there is no API for cloning or creating one of these files.

Where the files are (seen on 9.1.1): **Administration > Configurations**, the **Management Packs Configuration** tile (it is below
the fold on that page); the page shows the **System Defined** and **User Defined** folders directly, with no "Configuration
Files" header. The six shipped files are under **System Defined**. Their menu offers Edit, Delete, Clone, Move,
Export, Import and Create New Folder. (Not looked at on 8.18.7 or 9.0.2; the older wording "Infrastructure Operations" is
not a menu on 9.1.1.)

Three things about cloning that are easy to get wrong (measured on 9.1.1):

1. **Type the `.xml` extension when you name the copy.** The default name the dialog offers is `<file>.xml_Copy`. The
   adapter reads `SolutionConfig/<name>.xml`: a copy saved as `esxi_packages_walk` is **not found** (the log says
   `Configuration file 'esxi_packages_walk.xml' does not exist` and the cycle counts one failed read), while a copy saved
   as `esxi_packages_walk2.xml` is read.
2. **The copy lands in the "User Defined" folder**, even though the clone dialog shows System Defined. That is where to
   look for it afterwards.
3. **The account field takes the name without `.xml`.** For the copy `esxi_packages_walk2.xml`, enter
   `esxi_packages_walk2`.

Validate Connection does not read the check-list files: it passed while the account pointed at a copy that could not be read.
The first collection does: look for `F-CHECKLIST-<name>` among the causes, or `reads failed` in the cycle line.

A worked example, for the package list: (1) open the Configuration Files section and select `esxi_packages.xml` under
System Defined; (2) **Clone**, name the copy `esxi_packages_mine.xml`; (3) find it under User Defined, **Edit** it, and
move the entries you want out of the comment, with commas between them; (4) edit the account, set **ESXi Software Packages
Config File** to `esxi_packages_mine` (no `.xml`), and save; (5) the next collection reads it.

The six account fields, each taking the name without `.xml`:

| Shipped file | Account field (key) |
|---|---|
| `esxi_advanced_system_settings` | ESXi Advanced System Settings Config File (`esxi_adv_settings_config_file`) |
| `esxi_packages` | ESXi Software Packages Config File (`esxi_vib_driver_config_file`) |
| `vm_advanced_parameters` | VM Advanced Parameters Config File (`vm_adv_settings_config_file`) |
| `vm_options` | VM Options Config File (`vm_configuration_config_file`) |
| `windows_service_list` | Windows Service Configuration File (`win_service_config_file`) |
| `windows_event_list` | Windows Event Log Configuration File (`win_event_config_file`) |

Two shapes, for the two lists that are not like the others. The service list has a `windowsServices` root holding a
comma separated list of service names: `<windowsServices>Spooler,W32Time,wuauserv</windowsServices>`. The event list has
an `Events` root with one `Log` per event log and one `EventID` per number:
`<Events><Log Name="System"><EventID>7036</EventID></Log></Events>`. Back the files up before every upgrade, whichever
names you use. Uninstalling the pak deletes the six files it installed (see "Uninstalling"); a copy under another name is
not one of them. If the Python pack was installed before, its six files (the same names) may be in the store, and
installing this pak replaces them the same way.

**The two Tools keys need no list.** `Guest|Tools Sync Time` and `Guest|Tools Upgrade Policy` carry the VM's real
values on every VM whatever the check lists hold (from build 26; the original pack, and builds before 26, sent the
text `None` unless `config.tools` was listed in the VM options file). A VM with no tools configuration has neither
key. The old `None` is overwritten by the real value on the next collection, not left behind. A VM whose tools
settings could not be read is counted as a withheld value.

## Step-by-Step Installation

The menu paths below were seen on 9.1.1. 8.18.7 and 9.0.2 may word them differently. Two things to know before you start:
the pak is unsigned (step 2), and the vCenter must be entered by a name on its certificate (an IP address fails the name check
and the field cannot be edited later).

1. **Administration > Integrations > Repository**, then **ADD**. (A path that does not exist on 9.1.1:
   "Administration > Solutions > Add".)
2. **The pak is unsigned: tick "Ignore the PAK file signature checking" before you click Upload.** Without it the upload fails
   with only "Upload failed." Then upload; VCF Operations shows "Staging..." for about 30 seconds. Tick "I accept" on the End User
   License Agreement page before **NEXT**, then confirm "You have selected a management pack that is unsigned. Install this
   management pack?" with **YES**. **FINISH** appears only after the install reads "Applied and Cleaned (Completed)".
3. **Administration > Integrations > Accounts > ADD**, then the tile **VCF Content Factory vCommunity**. (There is no "Data
   Sources" menu on 9.1.1.)
4. Give the account a **Name** (required) and fill in **vCenter Server**. The credential field is a dropdown with a **+** icon
   to create one: give it a **Credential name**, the vCenter user name and password; Windows User Name and Windows Password are optional. Open **Advanced
   Settings** for the rest; leave a field alone and its default applies.
5. Click **Validate Connection**. If the collector does not already trust the vCenter certificate, VCF Operations shows it
   in a review dialog: check it and **accept** it, then the test runs and shows "Info: Test connection successful."
   Then click **Add**. (The vCenter Server field must carry a name that is on the certificate; see "TLS: the vCenter
   certificate".)
6. The first collection runs about a second after Add, not at the next five-minute interval (after an **Edit** and save, the first
   cycle came either seconds later or at the next tick, up to a few minutes). The account shows "Warning" (State: Collecting,
   Status: None) for the first minutes, then "Collecting". On 9.0.2 an account read FAILED/NONE right after an install and needed
   a start.

## Uninstalling

Seen on 9.1.1: **Administration > Integrations > Repository**, the **VCF Content Factory vCommunity** tile, its **...** menu, **Uninstall**. The
dialog has no option about content: it only asks you to tick "I understand the risk and agree" and says that associated
data and out-of-the-box content will be deleted. The uninstall took about two minutes (2 min 17 s on 9.1.1); an install took six to eight minutes.

What the uninstall removed (measured on 9.1.1):

- the adapter instance (the account) and its credential;
- all six shipped check-list files, **including any edit you made in a file with a default name** (the API still listed them for
  about a minute afterwards: it lags the UI by up to a minute); a copy under another name is not one of them and stayed;
- all the content of the pak: 170 views, 52 dashboards, 16 report templates, 3 super metrics, 10 symptoms and 4 alerts (the recommendation and the inventory tree were not counted).

What it left:

- the built-in views (120 on that instance) and the policies, untouched;
- the metrics and properties already on the objects: old vCommunity keys stay where they were pushed, and stop being
  updated.

Back up edited check lists before an uninstall, and see "Moving from the Python pack" for the case where a Python pack
version had overwritten built-in views.

## What Test Connection checks

Validate Connection first offers the certificate review dialog (not when Allow Insecure SSL is on), then logs in to the
vCenter and reads its certificate. A failed login fails the test and the dialog shows why: for a certificate not yet
accepted, a name the certificate does not carry or an expired certificate it shows VCF Operations' own certificate message,
with the pak's advice after it where it helps (see "TLS: the vCenter certificate"); other failures show the pak's sentence.
With Allow Insecure SSL on, the test passes by not checking the certificate, and **writes a WARN line** to `collector.log` that
the certificate is not verified (the test itself passes); with guest monitoring switched on as well, a second line says guest
monitoring will not run.

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
A passing test therefore leaves no log line; the dialog shows "Info: Test connection successful." (seen on 9.1.1).

## Logs

The account's own log is
`/storage/log/vcops/log/adapters/VCommunityAdapter/VCommunityAdapter_<id>.log`
on the **collector node** that runs the account (the cluster node, or the Cloud Proxy or remote collector the account is
assigned to), not on the node whose UI you are using. `<id>` is the adapter instance's internal numeric id (for example 3349), **not** the UUID the UI shows, and the UI does not
show it: list the directory and read the newest file, or match it against the account by the `host` in the
`VCommunityAdapter configured` line. The pak writes INFO lines to it, and the build ships
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

If an account is edited the log shows `VCommunityAdapter configured` first and the logout line (`reason: reconfigure`)
second; the new login appears at the next cycle. A setting with a value that is not allowed is warned about
just before the `configured` line, once.

## Creating an account through the API

The UI fills in the text field defaults (on 9.1.1 before build 28 it preselected no enumerated choice and stored the text
`null`, which this build reads as not set); the REST API applies none.

- **Send every non-credential identifier explicitly** (the describe declares 18) on
  `POST /api/adapters`: the server applies no `default=` from the describe, so an
  omitted identifier is not defaulted by the platform. (The pak reads an omitted
  check-list file field as its default name, and an empty string as "no list".)
- **The certificate must be accepted before the account can collect.** An account made through the API with the default
  Allow Insecure SSL (`false`) fails closed every cycle until the vCenter certificate is accepted: run
  `POST /api/adapters/testConnection` (it returns 201 with `adapter-certificates`) and then `PATCH /api/adapters` with the accepted
  certificate (the UI's Validate Connection does the same). **The PATCH body that works is the testConnection response itself, sent
  back with `id` set to the real adapter instance id** (seen on 9.0.2); a flat body fails with 400 "'AdapterInstanceInfoDto.certificates'
  cannot be null". Sending `allowInsecure` as `true` skips the checks (a lab only; guest monitoring stays off).
- **To change an identifier of an existing account**, use `PUT /api/adapters` with the GET body minus `links` and `collectorGroupId`; the
  accepted certificate survives that PUT (seen on 9.0.2).
- **A second account for the same vCenter with the same host and the same four check-list file names is refused** (POST 500, "Resource ... already exists").
- **The API lags the UI** for check-list files by up to about a minute.
- **A new instance starts STOPPED.** Start it with
  `PUT /api/adapters/{id}/monitoringstate/start`.
- **`POST /api/adapters/testConnection` stores the credential** it was given as a
  side effect. Delete it afterwards if the test was a probe.

## Troubleshooting

- **"the vCenter's certificate is not trusted by this collector"**: edit the account, click Validate Connection and accept
  the certificate. If the vCenter's certificate was renewed, accept the new one.
- **"the vCenter's certificate does not carry the name ..."**: you entered the vCenter by a name (or an IP address) that is
  not on its certificate. Enter a name that is on it. Accepting the certificate does not help.
- **The account used to work and now fails after an upgrade from a development build**: see "Upgrading from a
  development build" above.
- **Account down**: the vCenter cannot be reached from the collector (host name,
  route, port or firewall).
- **`Collection|vcenter_object_resolved` is 0**: no object of kind
  VMwareAdapter Instance carries the vCenter's instance UUID (the vCenter is not
  collected by the VMWARE adapter), or two do (a duplicate vCenter account). The
  log line says which.
- **`Collection|reads_failed` is not 0**: `Collection|last_error` names the first
  cause; the collector log carries one WARN line per cause with the first object.
