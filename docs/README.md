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

## Quick Reference

- **Adapter kind:** `vcfcf_vcommunity`
- **Version:** 0.0.0.5
- **Traversal spec:** (none)
- **Resource kinds:** 4
- **Metrics pushed onto:** VMwareAdapter Instance (VMWARE), ClusterComputeResource (VMWARE), HostSystem (VMWARE), VirtualMachine (VMWARE), Datastore (VMWARE) (see [Metrics Pushed Onto](#metrics-pushed-onto))
