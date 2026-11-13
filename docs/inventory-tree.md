# Inventory Tree: VCF Content Factory vCommunity

> Generated from `describe.xml` v0.0.0.1. Do not edit: regenerated on every build.

## Traversal Tree



> \* = identifying (unique) key

## Resource Kinds Reference

| Kind | Display Label | Identifying Keys | Parent(s) |
|------|--------------|-----------------|-----------|
| `vCommunityCollector` | VCF Content Factory vCommunity Collector | `vcenter` *, `config_files` * | - |
| `Content Library` | Content Library | `Library ID` * | - |
| `Library Item` | Library Item | `Item ID` * | - |
| `VM Template` | VM Template | `VMEntityObjectID` *, `VMEntityVCID` * | - |

## Metrics Pushed Onto

This pak pushes keys onto resource kinds owned by other management packs, through the Suite API at collection time. `describe.xml` cannot declare keys on another adapter's kinds, so the targets are declared in `adapter.yaml` (`stitch_targets`) and listed here.

### VMwareAdapter Instance (VMWARE)

- **Matched on:** VMEntityVCID (the vCenter's about.instanceUuid); exactly one object must carry it, otherwise the keys are held back and counted

| Key group |
|-----------|
| `vCommunity\|Collection Health\|*` |
