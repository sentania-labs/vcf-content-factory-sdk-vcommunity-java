# VCF Content Factory vCommunity: Reference

Derived from `describe.xml` and `resources.properties` (the generated reference does not render nested groups, a second identifier or the adapter instance's own metrics, so this file is kept by hand; `DescribeTest` pins describe.xml to the original pack).

## Adapter

| Field | Value |
|---|---|
| Adapter Kind | `vcfcf_vcommunity` |
| Tier | 2 (Java SDK) |
| Monitoring Interval | 5 minutes |
| License Required | No |

### Credentials (kind `vsphere_user`)

| Field | Key | Required | Type |
|---|---|---|---|
| vCenter User Name | `user` | Yes | string |
| vCenter Password | `password` | Yes | string (masked) |
| Windows User Name | `winUser` | No | string |
| Windows Password | `winPass` | No | string (masked) |

### Account fields

Main form = part of the account identity; Advanced = under Advanced Settings (`identType` 2).

| Field | Key | Type | Required | Default | Choices | Form |
|---|---|---|---|---|---|---|
| vCenter Server | `host` | string | Yes | - | - | Main |
| ESXi Advanced System Settings Config File | `esxi_adv_settings_config_file` | string | No | esxi_advanced_system_settings | - | Main |
| ESXi Software Packages Config File | `esxi_vib_driver_config_file` | string | No | esxi_packages | - | Main |
| VM Advanced Parameters Config File | `vm_adv_settings_config_file` | string | No | vm_advanced_parameters | - | Main |
| VM Options Config File | `vm_configuration_config_file` | string | No | vm_options | - | Main |
| Port | `port` | integer | Yes | 443 | - | Advanced |
| vCenter TLS Validation | `vcenter_tls_validation` | string | No | Trust on First Use | Trust on First Use, Enabled, Pinned | Advanced |
| vCenter Thumbprint (SHA-256) | `vcenter_thumbprint` | string | No | - | - | Advanced |
| Cluster Check Period (hours) | `cluster_check_period_hours` | string | No | 24 | 01, 02, 04, 06, 08, 12, 24 | Advanced |
| Cluster Check Time (UTC) | `periodic_cluster_check_time` | string | No | 00:00 | - | Advanced |
| Cluster Check Worst Percent | `cluster_check_worst_percent` | integer | No | 5 | - | Advanced |
| Monster VM CPU (% of host cores) | `monster_vm_cpu_percent_of_cores` | integer | No | 50 | - | Advanced |
| Monster VM Memory (% of host DRAM) | `monster_vm_memory_percent_of_dram` | integer | No | 40 | - | Advanced |
| Monster VM Memory Minimum (GB) | `monster_vm_memory_min_gb` | integer | No | 256 | - | Advanced |
| Windows Service Configuration File | `win_service_config_file` | string | No | windows_service_list | - | Advanced |
| Windows Event Log Configuration File | `win_event_config_file` | string | No | windows_event_list | - | Advanced |
| Guest OS Service Monitoring Status | `serviceMonitoring` | string | No | Disabled | Enabled, Disabled | Advanced |
| Guest File Transfer TLS Validation | `guest_tls_validation` | string | No | Pinned | Pinned, Strict, Disabled | Advanced |
| Windows Event Log Monitoring Status | `winEventMonitoring` | string | No | Disabled | Enabled, Disabled | Advanced |

## Self metrics (on the adapter instance)

Pushed every cycle. A figure that could not be measured is not sent: `Collection|checklist_entries_skipped` only when every check-list file read was parsed (or absent), `Collection|objects_unmatched` only when the vCenter object lookup completed, `Collection|series_pushed` and `Collection|properties_pushed` only when the push was clean.

| Key | Label | Type | Unit |
|---|---|---|---|
| `Collection|cycle_seconds` | Cycle Seconds | metric | sec |
| `Collection|reads_failed` | Reads Failed | metric | - |
| `Collection|values_withheld` | Values Withheld | metric | - |
| `Collection|objects_unmatched` | Objects Not Matched | metric | - |
| `Collection|checklist_entries_skipped` | Check-List Entries Skipped | metric | - |
| `Collection|series_pushed` | Series Pushed | metric | - |
| `Collection|properties_pushed` | Properties Pushed | metric | - |
| `Collection|push_batches_failed` | Push Batches Failed | metric | - |
| `Collection|deadline_hit` | Deadline Hit | metric | - |
| `Collection|vcenter_object_resolved` | vCenter Object Resolved | metric | - |
| `Collection|guest_vms_done` | Guest VMs Done | metric | - |
| `Collection|guest_vms_pending` | Guest VMs Pending | metric | - |
| `Collection|last_error` | Last Error | property | - |

`Collection|guest_vms_done` and `Collection|guest_vms_pending` belong to the guest phase and stay empty until it exists.

## Collection Health keys (pushed onto the vCenter's own VMWARE object)

Not declared here (another adapter's kind). Pushed when the vCenter object is matched by its instance UUID. `Check-List Entries Skipped` and `Objects Not Matched` follow the same only-when-measured rule as their `Collection|` twins.

| Key | Type |
|---|---|
| `vCommunity|Collection Health|Warnings in Last Cycle` | metric |
| `vCommunity|Collection Health|First Warning` | property |
| `vCommunity|Collection Health|Reads Failed in Last Cycle` | metric |
| `vCommunity|Collection Health|Values Withheld in Last Cycle` | metric |
| `vCommunity|Collection Health|Check-List Entries Skipped` | metric |
| `vCommunity|Collection Health|Objects Not Matched` | metric |
| `vCommunity|Collection Health|Cycle Seconds` | metric |
| `vCommunity|Collection Health|First Failure` | property |
| `vCommunity|Collection Health|Collector:<name>|Status` | property |
| `vCommunity|Collection Health|Collector:<name>|Failures` | metric |

`<name>` is a collector that exists in this build (`check_lists`). Other collectors report a status once they exist.

## Object Types

### VCF Content Factory vCommunity Collector

**Identifiers**: `vcenter`, `config_files`

No metrics.

### Content Library

**Identifiers**: `Library ID`

| Key | Type |
|---|---|
| `vCommunity|Content Library|Type` | property |
| `vCommunity|Content Library|Subscription URL` | property |
| `vCommunity|Content Library|Automatic Sync` | property |
| `vCommunity|Content Library|State` | property |
| `vCommunity|Content Library|Items` | metric |
| `vCommunity|Content Library|Items Not Stored Locally` | metric |
| `vCommunity|Content Library|Items Not Security Compliant` | metric |
| `vCommunity|Content Library|Total Size (GB)` | metric |

### Library Item

**Identifiers**: `Item ID`

| Key | Type |
|---|---|
| `vCommunity|Library Item|Type` | property |
| `vCommunity|Library Item|Description` | property |
| `vCommunity|Library Item|Version` | property |
| `vCommunity|Library Item|Content Version` | property |
| `vCommunity|Library Item|Stored Locally` | property |
| `vCommunity|Library Item|Security Compliant` | property |
| `vCommunity|Library Item|Certificate Verification` | property |
| `vCommunity|Library Item|Last Modified` | property |
| `vCommunity|Library Item|Last Sync` | property |
| `vCommunity|Library Item|Source Item ID` | property |
| `vCommunity|Library Item|Size (GB)` | metric |

### VM Template

**Identifiers**: `VMEntityObjectID`, `VMEntityVCID`

| Key | Type |
|---|---|
| `vCommunity|VM Template|Guest OS` | property |
| `vCommunity|VM Template|Hardware Version` | property |
| `vCommunity|VM Template|vCPUs` | property |
| `vCommunity|VM Template|Memory (GB)` | property |
| `vCommunity|VM Template|Datastore` | property |
| `vCommunity|VM Template|VM Folder` | property |
| `vCommunity|VM Template|Disk Used (GB)` | metric |

No traversal spec yet: Content Library > Library Item arrives with the library phase.
