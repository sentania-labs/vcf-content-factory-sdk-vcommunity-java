# View text notes

Counts
- `view_describe.tsv`: 101 rows (98 from `id_map.tsv`, 3 from `id_map_e1_extra.tsv`), one fresh description each, written from subject, columns, filters, buckets and time settings only.
- `view_rename.tsv`: 9 rows (title changes). The other 92 keep their current name.

Source for built-in titles
- `src/vcfcf_core/stock_ids/stock_ids_baseline.json` (`ids.view`, 771 entries). It keeps one display name per stock id, seen in 8.18.7, 9.0.2 and/or 9.1.1 content exports. Every one of the 101 `old_id` values is in it.
- A title was treated as colliding when its name, compared case-insensitively, equals any baseline view name (not only the view's own old id). Six collide with names seen in all three versions (Guest OS Distribution, Guest OS Memory Paging Trend, Guest OS Partition List, vSphere Cluster Performance Profiling, vSphere ESXi Memory Utilization, vSphere Performance Profiling) and three with names seen only in 9.1.1 (the three VCF Management Services views).
- Limits: the baseline holds one name per id, so a stock view whose title differs between versions is matched on the name recorded there. Titles of views that are not in any export used to build it cannot be checked. Renames were checked to be unique among all 170 view names and absent from the baseline.

Thin or odd definitions
- ESXi Switch property (`f62618d5-...`): columns are bound to one specific host instance (`host:10.156.17.100`), so the description says the host columns are bound to a single host instance.
- Guest OS Partition List, VM Network Card Configuration, VM Snapshots List, VM Virtual Disks: columns are bound to one instance (root partition, first adapter, `snapshot-1`, `scsi0:0`). Descriptions say so.
- VM Memory Limit (`223a2177-...`): the source charted the CPU limit under a memory-limit filter; the shipped view charts the memory limit (`config|memoryAllocation|limit`, in GB) and its description says so.
- VCF Management Services Components View and vSphere Data Centers Inventory: no subject in the definition. Described from the columns.
- VM Distribution by Disk Size: filter is "disk space greater than 500" with no unit in the definition; described without a unit.
- VM Network Top Talkers: filter "average network throughput greater than 12", unit not stated in the definition.

Identical definitions
- No two of the 101 have identical definitions apart from id, name and description.
- Near twins (differ only in a column label or roll-up), which got similar but not identical descriptions: ESXi BIOS version distribution / ESXi BIOS Versions; Guest OS Distribution / Guest OS from Tools.
