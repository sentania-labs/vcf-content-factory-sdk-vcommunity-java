# VCF Content Factory vCommunity Documentation

> Generated index. The SVG diagram and per-kind table are regenerated on every
> build; prose sections (overview, installing) are hand-curated.

## Contents

| Section | Description |
|---------|-------------|
| [Overview](overview.md) | What's in the pack, resource kinds, cross-adapter notes |
| [Installing & Configuring](installing.md) | Prerequisites, configuration fields, step-by-step guide |
| [Inventory Tree](inventory-tree.md) | Traversal spec, per-kind table with identifying keys |
| [Metrics Reference](../REFERENCE.md) | Full metrics and properties reference (generated) |

## Inventory Tree

![Inventory Tree](inventory-tree.svg)

## Metrics Pushed Onto

This pak pushes keys onto resource kinds owned by other management packs, through the Suite API at collection time. `describe.xml` cannot declare keys on another adapter's kinds, so the targets are declared in `adapter.yaml` (`stitch_targets`) and listed here.

### VMwareAdapter Instance (VMWARE)

- **Matched on:** VMEntityVCID (the vCenter's about.instanceUuid); exactly one object must carry it, otherwise the keys are held back and counted

| Key group |
|-----------|
| `vCommunity\|Certificate\|*` |
| `vCommunity\|Configuration\|*` |
| `vCommunity\|Collection Health\|*` |
| `vCommunity\|Availability\|Worst Cluster Operational Availability` |
| `vCommunity\|Number of Port Groups` |

### ClusterComputeResource (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID (the MoRef and the vCenter's instance UUID, never a name)

| Key group |
|-----------|
| `vCommunity\|Cluster Configuration\|*` |
| `vCommunity\|Cluster Performance\|*` |
| `vCommunity\|Cluster Risk\|*` |
| `vCommunity\|Cluster Consumption\|*` |
| `vCommunity\|Availability\|*` |
| `vCommunity\|Periodic Cluster Check\|*` |
| `vCommunity\|Resource Pools\|*` |

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
| `vCommunity\|ESXi Performance\|*` |
| `vCommunity\|Availability\|*` |

### VirtualMachine (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID

| Key group |
|-----------|
| `vCommunity\|Configuration\|*` |
| `vCommunity\|Guest\|*` |
| `vCommunity\|Guest OS\|*` |
| `vCommunity\|Runtime\|*` |
| `vCommunity\|Summary\|*` |
| `vCommunity\|Network\|Network Adapters:<label>\|*` |
| `vCommunity\|Virtual Disk:<label>\|*` |
| `vCommunity\|Options\|*` |
| `vCommunity\|VM Performance\|*` |
| `vCommunity\|Guest OS Performance\|*` |
| `vCommunity\|VM Guest OS KPI` |

### Datastore (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID

| Key group |
|-----------|
| `vCommunity\|Identifiers\|*` |
| `vCommunity\|Summary\|*` |

### Datacenter (VMWARE)

- **Matched on:** VMEntityObjectID + VMEntityVCID (live-unproven: E1)

| Key group |
|-----------|
| `vCommunity\|Network\|Total Network Speed Degradation` |

### vSphere World (VMWARE)

- **Matched on:** the one object of the kind; none or several: held back and counted

| Key group |
|-----------|
| `vCommunity\|*` |

### Tier, BusinessService, Enterprise (Container)

- **Matched on:** the Suite API resource id from the Container tree

| Key group |
|-----------|
| `vCommunity\|Business Application\|*` |

## Quick Reference

- **Adapter kind:** `vcfcf_vcommunity`
- **Version:** 0.0.0.16
- **Traversal spec:** vCommunity Content Libraries
- **Resource kinds:** 4
- **Metrics pushed onto:** VMwareAdapter Instance (VMWARE), ClusterComputeResource (VMWARE), HostSystem (VMWARE), VirtualMachine (VMWARE), Datastore (VMWARE), Datacenter (VMWARE), vSphere World (VMWARE), Tier, BusinessService, Enterprise (Container) (see [Metrics Pushed Onto](#metrics-pushed-onto))
