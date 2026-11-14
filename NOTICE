VCF Content Factory vCommunity
Copyright (c) 2026 the VCF Content Factory authors. Licensed under the MIT
License (see LICENSE).

This pak is an independent Java reimplementation of the vCommunity management
pack for VCF Operations. The design it follows (the vCommunity| key tree, the
account fields, the KPI tables, the content and the check-list files) comes from:

  - Onur Yuzseven and the contributors to vCommunity,
    https://github.com/vmbro/VCF-Operations-vCommunity
  - e1-rahabok, VCF-vCommunity-Adapter (a continuation of vCommunity),
    https://github.com/e1-rahabok/VCF-vCommunity-Adapter

No source code of those projects is copied into this pak. That includes the PowerShell programs the guest phase sends
to Windows guests: they are written for this pak (`GuestScripts`) from the columns the key map names, and produce the
same keys and values; the column names and the registry and file paths they use are facts of Windows and of the key map.

Content (views, dashboards, reports, super metrics, symptoms, alerts, recommendation)
------------------------------------------------------------------------------------

The content this pak installs is NOT independently written. It is converted, by an
offline tool, from the content files of the source pack (the vCommunity 0.8.1 pack
image of the e1-rahabok continuation, built from the sources named above): 170 views,
52 dashboards, 16 reports, 2 super metrics, 10 symptom definitions, 4 alert
definitions and 1 recommendation. It derives from those files. Most of it keeps their
ids, names, descriptions, widget titles and layout as the source wrote them; the
amount of free text kept as written is counted in
docs/design/inputs/carried-text-report.md. The conversion and its checks against the
source are in docs/design/inputs/import-report/.

What was written for this pak and is not carried from the source:

  - the descriptions of the 101 views that began as edited copies of VCF Operations'
    own views (new ids as well), and the new titles of nine of them
    (docs/design/inputs/view_rename.tsv, view_describe.tsv);
  - the six check-list files under content/files/solutionconfig/: the header text
    and examples are written from this pak's own parser; the file names, the
    account fields that name them and the public vSphere and Windows setting names in
    their examples are facts the source also has;
  - the 19 views that only renamed a VCF Operations view are not shipped at all.

The source pack is distributed under the PolyForm Noncommercial License 1.0.0
(its LICENSE file, copyright Onur Yuzseven). This pak's own code is MIT (LICENSE).
The MIT license does not by itself grant rights in content derived from a source under
other terms. THE TERMS UNDER WHICH THE CONVERTED CONTENT MAY BE REDISTRIBUTED ARE NOT
SETTLED: they wait for the owner's confirmation, and nothing here is a statement that
the content is MIT licensed or that the source's terms permit this use.

PLACEHOLDER: the final wording of this notice, and the licensing terms under
which the original design material and the converted content are reused, wait for the
owner's licensing record. Until that record is complete this repository stays private
and no release is to be tagged.
