# P5 live proof: guest operations and events on a real Windows VM

Nothing in phase P5 has run against a real Windows guest. The fixture tests (`GuestOpsTest`, `GuestEngineTest`,
`GuestUnitTest`) prove the port against a fake vCenter, a fake ESXi host and the program texts it sends; they cannot
prove what PowerShell, VMware Tools and Operations do with them. This is the run that does, written to be carried
out without the author. Design reference: section 8 of `docs/design/vcommunity-java-port-design.md`; the
experiment names G1 to G9 are its section 8.7.

Do not run it on a production Operations instance. The pak and the account are test fixtures; the Windows account
needs rights in the guest, and every run below leaves nothing in the guest that is not listed under "Clean-up".

## 0. What you need

| Item | Detail |
|---|---|
| Pak | build 13 or later (`0.0.0.13`), installed on 9.0.2 devel; the account described in `docs/installing.md` already collecting properties (P2 to P4), with **Allow Insecure SSL off (the default) and the vCenter certificate accepted in Validate Connection** (guest monitoring does not run with Allow Insecure SSL on) |
| Windows VM A | Windows Server 2019 or 2022 or Windows 11, VMware Tools running, guest family shown as Windows by vCenter, on a vCenter host the collector can reach on port 443 |
| Windows VM B | a hardened guest: a domain controller (or a member server) with a GPO setting PowerShell execution policy to AllSigned or Restricted at the MachinePolicy scope |
| Windows VM C | a guest under AppLocker or WDAC in Constrained Language Mode (`$ExecutionContext.SessionState.LanguageMode` shows `ConstrainedLanguage`) |
| Windows VM D | a guest with a non-English display language (German or Japanese) |
| Windows account | a local or domain account made for this test that may log on to those guests and read the event logs and the service list. For VM B and C, the account guest operations use on them. Never an account whose lockout would hurt: the wrong-password steps need a throwaway local account on each guest |
| vCenter privileges | on the account in the pak: `VirtualMachine.GuestOperations.Query`, `.Modify` and `.Execute` on the VMs |
| Network | collector to the ESXi hosts of the VMs on 443 (file transfer); collector to vCenter 443 |
| Collector log | read access to the adapter instance log on the collector, to search it for secrets |

The adapter account fields to set (Advanced Settings): Windows User Name, Windows Password (credential);
Guest OS Service Monitoring Status `Enabled`; Windows Event Log Monitoring Status `Enabled`; Guest File Transfer
TLS Validation `Pinned`; Windows Service Configuration File `windows_service_list`; Windows Event Log Configuration
File `windows_event_list`.

The two lists (Administration, Configuration Files, `SolutionConfig`):

- `windows_service_list.xml`: `<Services>Spooler,W32Time,wuauserv,NoSuchService</Services>` (one name that does not
  exist on purpose)
- `windows_event_list.xml`:
  ```xml
  <Events>
    <Log Name="System"><EventID>7036</EventID><EventID>7045</EventID></Log>
    <Log Name="Application"><EventID>1000</EventID></Log>
  </Events>
  ```

How to read the results:

- Properties: `POST /suite-api/api/resources/properties/latest/query` with
  `{"resourceIds":["<vm resource id>"],"propertyKeys":["vCommunity|Guest OS|Operating System|OS Name", ...]}`
  (token in the `Authorization` header; keep it out of any file you save). Or the VM's Properties tab.
- Health: the vCenter object's properties `vCommunity|Collection Health|Collector:guest|Status`, `...|Failures`,
  `...|Causes`, and the adapter instance's metrics `Collection|guest_vms_done` and `Collection|guest_vms_pending`.
- Events: the VM's Events tab in the Operations UI (Environment, the VM, Events) and, for the vCenter object, its
  Events tab. They are diagnostic log events, not notifications and not alerts.
- Collector side: the adapter instance's log (`vcfcf_vcommunity` section).
- Guest side: remote desktop or the console, `Get-ChildItem C:\Windows\Temp -Filter 'VCFOperationsvCommunity-*'`
  and Task Manager or `Get-CimInstance Win32_Process -Filter "Name='powershell.exe'"`.

Write each result in the table at the end. A step that does not give the expected result is a finding, not a retry:
copy the failure cause (`F-GUEST-...`) from the health key and the log lines around it.

## 1. A healthy guest (VM A): keys and health

1. Wait two collections (10 minutes) after saving the account.
2. Read the properties of VM A.

Expected:

- `vCommunity|Guest OS|Operating System|OS Name` is the Windows caption, for example `Microsoft Windows Server 2022
  Standard`; `OS Version`, `OS BuildNumber`, `OS Architecture` (`64-bit`), `OS Last Boot Up Time` (the guest's own
  date text), `OS Release ID` are present. On a build with no `ReleaseId` value in the registry `OS Release ID` is the
  empty text (quirk Q4: the program always writes the column and an empty cell is published as it is), not absent.
- For each of `Spooler`, `W32Time`, `wuauserv` that exists: `vCommunity|Guest OS|Services:<display name>|Service
  Name`, `Service Status` (`Running` or `Stopped`) and `Service Start Type` (`Automatic`, `Manual` or `Disabled`).
  A stopped service shows `Stopped`. There is no key for `NoSuchService`.
- `Collector:guest|Status` is `OK` (or `PARTIAL` if another Windows VM of the vCenter has failures), `Causes` names the
  failures of the cycle; `Collection|guest_vms_done` counts the Windows VMs whose tools are running.
- `Windows Event` monitoring: see section 5.

Compare each value with what the guest says: `Get-Service Spooler | Format-List *`, `Get-CimInstance
Win32_OperatingSystem | Select Caption,Version,BuildNumber,OSArchitecture,LastBootUpTime`. The values must be the same
text.

## 2. G1 to G3: the transfer and the program (VMs A, B, C)

| Experiment | Steps | Pass |
|---|---|---|
| G1 byte order mark | stop the service `Spooler` on VM A; wait one collection; then on VM A run `Get-Service Spooler`; note the properties. On any one VM, use the debug facility of the adapter (or a packet capture on the collector for the ESXi transfer: the GET body of `output.csv`) to look at the first bytes of the output | the output starts with `"Name"` (no `EF BB BF`) when the .NET writer ran, or with the BOM when only the cmdlet fallback ran (VM C); in both cases the keys land, because the header is read after dropping a BOM |
| G2 hardened DC | VM B: wait two collections | the keys of section 1 land on VM B; `Causes` has no `F-GUEST-EXIT` and no `F-GUEST-HEADER`. The programs run as `-EncodedCommand`; the MachinePolicy execution policy does not block them. If they are blocked, record the policy and the exit code (`F-GUEST-EXIT` says it) |
| G2 CLM | VM C: wait two collections | the keys land; if the .NET writer is refused under Constrained Language Mode, the cmdlet fallback writes the file and the header still parses |
| G3 argument length | any VM | the longest of the three programs (events) starts: `Causes` has no `F-GUEST-START`. The argument string is about 5 KB; the Windows limit is 32,767 characters. If a VM refuses it, record the `StartProgramInGuest` fault text from the log |

## 3. G4 and G7: the transfer URL and the ESXi host

1. G4: enable debug logging for the adapter instance is not needed; look for the URL host in a packet capture on the
   collector, or in the ESXi host's `hostd.log` (`/var/run/log/hostd.log`, search `guestFile`). Record the host
   part of the URL vCenter returns for `InitiateFileTransferToGuest`: the ESXi host's name, its IP address or `*`.
   Behind a Cloud Proxy, do this on the proxy.
   - Pass: the host part is recorded for each of the cases (collector in the vCenter's network, Cloud Proxy). If it is
     `*`, the keys still land (the port replaces `*` by the ESXi host's name from the vCenter inventory).
2. G7: with the collector on a Cloud Proxy, repeat section 1.
   - Pass: transfers answer HTTP 200 or 201 and the keys land; if not, `Causes` shows `F-GUEST-UPLOAD` or
     `F-GUEST-DOWNLOAD` with the HTTP status, or `F-GUEST-TLS` when the host's certificate was not accepted.
3. Certificate pinning, positive and negative:
   - Pinned (default): keys land.
   - On the ESXi host of VM A, replace the host's machine certificate (or on a test host use a self-signed one) and
     wait for vCenter to record the new thumbprint (`summary.config.sslThumbprint`): the keys still land (the
     recorded thumbprint is the identity).
   - To see the refusal, make the collector reach the host through something that presents another certificate (a TLS
     proxy or a port forward to a different host on 443 in the collector's hosts file, on a test host), so that the
     certificate on the wire is not the one vCenter recorded. Expected: `F-GUEST-TLS` for the VMs on that host, no
     the `input...txt` or `output...csv` file is sent or fetched (the proxy's log shows the handshake and no HTTP request), the other
     hosts' VMs are unaffected, and the log line says the host presented a certificate other than the recorded one, with
     its thumbprint.
   - Strict: the same as Pinned for a host with a recorded thumbprint; for a host vCenter has no thumbprint for (a
     disconnected host) the VM is skipped with `F-GUEST-TLS`.
   - Disabled: keys land whatever the certificate; the log has one warning per collection that the certificate is not
     checked.

## 4. G6 and G8: a localised guest, and concurrency

| Experiment | Steps | Pass |
|---|---|---|
| G6 localised guest | VM D with the events section (5) enabled. Produce an error event in the System log (`eventcreate /l System /t ERROR /id 7036 /so test /d "p5 proof"`) | the event reaches the VM with the severity `IMMEDIATE` (red) although the guest's level text is not English; the message starts `[WindowsEvent-<the guest's level word> ` |
| G8 four at a time | an Operations instance with at least 8 Windows VMs on one vCenter, tools running | `Collection|guest_vms_done` is the number of Windows VMs within three collections; the collector log shows no vCenter fault about too many operations; vCenter's task list shows at most four guest operations at a time from the account. Record the cycle's `Collection|cycle_seconds` with the guest phase on |

## 5. Windows events, G5 and G9

1. Create a recognisable event on VM A inside the last five minutes: `eventcreate /l Application /t ERROR /id 1000
   /so p5proof /d "P5 live proof one"` (the list above covers Application 1000; `eventcreate` ids are limited to
   1 to 1000).
2. Wait one collection.

Expected:

- The VM's Events tab shows one event whose message starts `[WindowsEvent-Error [Timestamp=...] [Category=Application]
  [EventID=1000] [Source=p5proof] [Message=P5 live proof one]]`.
- The event's severity is Immediate for Error, Warning for Warning, Information for Information; Critical for a
  critical event.
- G9: the event is visible in a browser on 8.18.7, 9.0.2 and 9.1.1 (one pass per line; the first is 9.0.2).
- The same event is not sent again in the next collections while it is inside the five-minute window: count the
  events with that message after three collections. Exactly one.
- G5 (the lifecycle of the event, answered by looking, once per line): record, with timestamps, when the event stops
  being listed as active after it was sent once: after one collection, after three, or never. If it never goes away,
  or if a second identical event appears when the same text is sent again, the design's fall-back is needed
  (`managedExternally: true` with an explicit cancel after three quiet collections, section 8.6): report it, do not
  build around it here.
- Restart check: restart the adapter instance (edit and save the account without changes). The event still in the
  five-minute window is sent once more, because the memory of what was sent is not kept across a restart (DV-98).
  Expected: one more event of the same text, not a storm; record how many events with that message exist.
- Limits: create 150 different events across the listed logs within five minutes and check that at most 100 events
  of that VM arrive in one collection, and the collector log says how many were over.

Configuration warning events (design 4.10): rename `windows_event_list.xml` in the configuration files, wait one
collection. Expected: the vCenter object's Events tab shows a warning `vCommunity configuration: Configuration file
'windows_event_list.xml' ...`, and `Collector:check_lists|Status` is not OK. The warning is sent once and not again for two
hours (DV-98), so count the events with that message after three collections: exactly one. G5 decides together with the
Windows events whether the platform merges identical messages; if the single event ends after one collection while the
file is still missing, record it (the problem is then said only at the start of each two hours). Restore the file: no
further warning.

## 6. Failures that must be visible and harmless

| Case | Steps | Expected |
|---|---|---|
| Wrong password (throwaway account only) | **never use a production or domain account**: make a local account on each test guest for this, record the guest's and the domain's lockout threshold and observation window first, then set a wrong password in the pak's credential and save it; wait three collections | `Collector:guest|Status` `FAILED` then `SKIPPED`; `Causes` has `F-GUEST-AUTH`; the Security log of the guests shows event 4625 **once** in the first collection (one VM alone) and once more in the second at most (a different VM), then none for 24 hours (count 4625, and on a domain controller 4771, 4776 and 4740: no lockout). Restart the adapter instance (edit and save without change) and count again: one 4625 per restart. Put the right password back and save: the account works at once |
| Tools not running | stop VMware Tools in VM A (`Stop-Service VMTools`) | `Causes` has `F-GUEST-TOOLS`; VM A's old guest properties stay as they were (no `Stopped`, no blank), and no event is invented |
| Tools running, guest operations refused | a VM whose tools policy disables guest operations | `F-GUEST-UNAVAILABLE`; nothing published |
| Unreachable guest, and the leftover directory | suspend VM A (vSphere client, Suspend) just after a collection starts, wait one collection, resume it | `F-GUEST-TIMEOUT` or `F-GUEST-TOOLS` for VM A in that collection, and no stopped service or empty event list is shown; if a directory `VCFOperationsvCommunity-*` was made and could not be removed (`F-GUEST-CLEANUP`), the next collection after the resume removes it (look in `C:\Windows\Temp`) |
| A program that does not end | on VM A hold the program: start `powershell.exe` with a debugger attached, or use a guest under heavy CPU load so the program takes more than five minutes | after 300 s the program is stopped (`F-GUEST-TIMEOUT`) and the directory is removed; the other programs of the VM still run |
| Powered off VM | power off VM A | no guest operation, no `F-GUEST-TOOLS` for a powered-off VM |
| Large log | a Windows event whose message is over 2,000 characters | the event arrives cut at 2,000 characters with `...` |
| Hostile service name | on VM A create a service whose display name contains `|` (`sc.exe create p5pipe binPath= C:\Windows\notepad.exe DisplayName= "A|B"`) and add `p5pipe` to the list | no `Services:A|B` key; `F-GUEST-ROW` appears in `Causes`; the other services are published. Delete the service after |
| Disabled again | set both monitoring switches back to `Disabled` | after one collection no guest operation is made: the collector log shows none, vCenter's task list shows no `StartProgramInGuest`, and the old guest properties stay as they were |

## 7. Secrets and clean-up (every run)

1. Search the adapter instance log for the password and the account name: `grep -c '<password>' <log>` must give 0, and
   no log line holds the password. A vCenter operation fault text must show `***` if it carried either.
2. On VM A, while a collection runs, list the processes with their command lines:
   `Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Select CommandLine`. The command line is
   `... -NoLogo -NoProfile -NonInteractive -EncodedCommand <base64>`. Decode the text:
   `[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('<base64>'))`. It holds the two paths and the program,
   and no account name and no password.
3. After a collection and after a cancelled collection (restart the adapter instance while the phase runs):
   `Get-ChildItem C:\Windows\Temp -Filter 'VCFOperationsvCommunity-*'` returns nothing. A directory left over from a crash of
   the collector (kill the collector service during a run) is not removed by the port: record it, and remove it by hand.
4. On the collector, no file with the guest's output or the password exists on disk (the port writes none).

## 8. Checks the review of build 12 added

1. **One and two Windows VMs.** With exactly one Windows VM, then exactly two, with a wrong password on a throwaway account: count 4625 over
   four collections. One VM: one. Two VMs: two. Then nothing.
2. **Default off leaves no logon.** With both switches Disabled for three collections the Security log of a monitored guest shows **no**
   4624 or 4625 for the account (the vCenter task list alone does not show it).
3. **UAC and non-admin.** Use a local administrator that is not RID 500 (its logon may get a filtered token), and separately a non-admin
   account, with `<Log Name="Security"><EventID>4625</EventID></Log>` in the event list. Record whether the query fails and
   what the health says: it must be `F-GUEST-QUERY` naming Security, with the other events still sent, not an OK collector with no events.
4. **A log that is not there.** List `<Log Name="Sytem">`: `F-GUEST-QUERY`. List `<Log Name="system">`: the event's text has
   `[Category=System]`, the record's own name.
5. **Services not asked for.** Create a service the list does not name: no key for it. Create two services with the same name in different
   case (not possible on Windows: skip) or the same display name: the later row wins, as upstream.
6. **Security products.** On a guest with Defender for Endpoint or the estate's EDR: record alerts and blocks over three collections for the
   command line in installing.md. Confirm that allow-listing by `vmtoolsd.exe` as parent, the account and the program text works. A
   block shows as `F-GUEST-EXIT` or `F-GUEST-START` and nothing is published.
7. **Directory and file name forms.** Record exactly what `CreateTemporaryDirectoryInGuest` and `CreateTemporaryFileInGuest` return
   (case, short names). Check the ACL (`icacls`) of the directory: `Users` must have no read and no list. As a low-privileged
   user, try to plant a junction inside a live run's directory (the name is unknown unless it can be listed) and check whether
   `DeleteDirectoryInGuest` deletes the junction target's contents.
8. **Script-block logging.** On the hardened DC with ScriptBlock and Transcription logging on: event 4104 holds the program text and
   no account name or password.
9. **Long event messages.** A message of about 30 KB (`eventcreate` is limited to a short text: write the event with
   `Write-EventLog` from a script): Operations accepts it, or find its maximum message length and set the bound
   (`GuestText.MAX_EVENT`, DV-99) to it. Check that the CLM fallback writer (`Out-File`) does not wrap or cut a long line.
10. **vMotion.** vMotion VM A as a collection starts: the transfer is asked again on the new host; no `F-GUEST-TLS` for it.
11. **Interval.** With a slow guest at the end of the cycle's time: `Collection|cycle_seconds` stays under the interval and the next
    collection starts on time (DV-96).
12. **Upstream baseline on the same guest.** Run upstream 0.8.1 on the same Windows Server 2019 VM first: does it publish any OS or
    service key at all (the byte order mark question: Windows PowerShell 5.1 writes a BOM with `Export-Csv -Encoding UTF8`)? Record it, so DV-40's effect is
    measured. Compare `[Timestamp=...]` with `"$($e.TimeCreated)"` run in Windows PowerShell 5.1 on a localised guest.
13. **FIPS.** On a 9.1.1 FIPS collector run sections 1 and 3: a missing SHA-1 shows as `F-GUEST-TLS` naming SHA-1, once, not as a
    failed upload per VM.
14. **Allow Insecure SSL.** With Allow Insecure SSL set to `true` and a guest switch on: `F-GUEST-TLS-INSECURE`, no
    guest operation, no request with the Windows account (vCenter's task list shows none), and Test Connection warns.
15. **Properties.** After a collection, read `vCommunity|Options|config.tools` of a VM that has it in its VM Options list and
    compare its text with the Python pack's on the same VM: the last field ends `fault =  } }` (two spaces) in Python.
    Record what Operations stores for the Java pak (note L1 in the design; a run of spaces lost after the push).
16. **Cluster Delta once.** After a restart of the adapter across a window end, the two Cluster Delta counts are published
    once in that window (L2, DV-105), not at two consecutive collections.

## 9. Results

| Step | Result (pass / finding) | Date, line (8.18.7 / 9.0.2 / 9.1.1) | Notes: values, `F-GUEST-*` causes, log lines |
|---|---|---|---|
| 1 healthy guest keys | | | |
| G1 byte order mark | | | |
| G2 hardened DC | | | |
| G2 CLM | | | |
| G3 argument length | | | |
| G4 transfer URL host | | | |
| G5 event lifecycle | | | |
| G6 localised guest | | | |
| G7 Cloud Proxy transfer | | | |
| G8 four at a time | | | |
| G9 events seen in a browser | | | |
| pinning positive / negative | | | |
| wrong password and pause | | | |
| secrets and clean-up | | | |
| review checks 1 to 16 | | | |
