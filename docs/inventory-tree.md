# Inventory Tree: VCF Content Factory vCommunity

> Generated from `describe.xml` v0.0.0.8. Do not edit: regenerated on every build.

**Traversal Spec:** vCommunity Content Libraries

## Traversal Tree

- **Content Library** (`Content Library`)
  - **Library Item** (`Library Item`)

> \* = identifying (unique) key

## Resource Kinds Reference

| Kind | Display Label | Identifying Keys | Parent(s) |
|------|--------------|-----------------|-----------|
| `vCommunityCollector` | VCF Content Factory vCommunity Collector | `vcenter` *, `config_files` * | - |
| `Content Library` | Content Library | `Library ID` * | - |
| `Library Item` | Library Item | `Item ID` * | Content Library |
| `VM Template` | VM Template | `VMEntityObjectID` *, `VMEntityVCID` * | - |

## Metrics Pushed Onto

This pak pushes keys onto resource kinds owned by other management packs, through the Suite API at collection time. `describe.xml` cannot declare keys on another adapter's kinds, so the targets are declared in `adapter.yaml` (`stitch_targets`) and listed here.

### VMwareAdapter Instance (VMWARE)

- **Matched on:** VMEntityVCID (the vCenter's about.instanceUuid); exactly one object must carry it, otherwise the keys are held back and counted

| Key group |
|-----------|
| `vCommunity\|Certificate\|*` |
| `vCommunity\|Configuration\|*` |
| `vCommunity\|Collection Health\|*` |

### ClusterComputeResource (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID (the MoRef and the vCenter's instance UUID, never a name)

| Key group |
|-----------|
| `vCommunity\|Cluster Configuration\|*` |

### HostSystem (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID

| Key group |
|-----------|
| `vCommunity\|Configuration\|*` |
| `vCommunity\|Licensing:<license name>\|*` |
| `vCommunity\|Storage Adapter\|*` |
| `vCommunity\|Storage Adapter:<hba>\|*` |
| `vCommunity\|Network\|*` |
| `vCommunity\|Network\|Device:<vmnic>\|*` |
| `vCommunity\|Runtime\|*` |
| `vCommunity\|Hardware\|*` |
| `vCommunity\|Capability\|*` |

### VirtualMachine (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID

| Key group |
|-----------|
| `vCommunity\|Configuration\|*` |
| `vCommunity\|Guest\|*` |
| `vCommunity\|Runtime\|*` |
| `vCommunity\|Summary\|*` |
| `vCommunity\|Network\|Network Adapters:<label>\|*` |
| `vCommunity\|Virtual Disk:<label>\|*` |
| `vCommunity\|Options\|*` |

### Datastore (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID

| Key group |
|-----------|
| `vCommunity\|Identifiers\|*` |
| `vCommunity\|Summary\|*` |
