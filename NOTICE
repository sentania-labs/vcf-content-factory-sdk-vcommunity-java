PRIVATE. NO RELEASE TAG until the owner confirms the terms of the bundled content
(see "Terms of the bundled content" below). This repository stays private until then.

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
offline tool, from the content files of this source:

  Project:   VCF Operations vCommunity Management Pack ("vCommunity"), version 0.8.1
             (https://github.com/vmbro/VCF-Operations-vCommunity), as continued by
             e1-rahabok (https://github.com/e1-rahabok/VCF-vCommunity-Adapter)
  Build:     vCommunity_0.8.1-fix-builtin-view-ids-e1-releasedimage.pak, sha256
             7cc81338614a185bf3546f1d9112eb2e1a9c19c5a507eddad64a637339b05780: a factory
             build of the corrected content (the fix sent upstream as pull request 92 to
             e1-rahabok/VCF-vCommunity-Adapter) on the released 0.8.1 adapter image
  License:   PolyForm Noncommercial License 1.0.0
  Terms:     https://polyformproject.org/licenses/noncommercial/1.0.0

  Required Notice: Copyright (c) 2026 Onur Yuzseven — VCF Operations vCommunity Management Pack (https://github.com/vmbro/VCF-Operations-vCommunity)

The converted content is 170 views, 52 dashboards, 16 reports, 2 super metrics, 10
symptom definitions, 4 alert definitions and 1 recommendation. It derives from those
files. Most of it keeps their ids, names, descriptions, widget titles and layout as the
source wrote them; the amount of free text kept as written is counted in
docs/design/inputs/carried-text-report.md. The conversion and its checks against the
source are in docs/design/inputs/import-report/.

What was written for this pak and is not carried from the source:

  - the descriptions of the 101 views that began as edited copies of VCF Operations'
    own views, and the new titles of nine of them (docs/design/inputs/view_rename.tsv,
    view_describe.tsv). Their ids are NOT written for this pak: they are the ids minted
    for the corrected upstream build (pull request 92, above), and kept as they are;
  - the six check-list files under content/files/solutionconfig/: the header text
    and examples are written from this pak's own parser; the file names, the
    account fields that name them and the public vSphere and Windows setting names in
    their examples are facts the source also has;
  - the 19 views that only renamed a VCF Operations view are not shipped at all.

Terms of the bundled content
----------------------------

The adapter code of this pak (the Java source, the build files, the check-list file
mechanism) is MIT licensed (LICENSE). The bundled content (views, dashboards, reports,
super metrics, symptoms, alerts, the recommendation) derives from the source named above,
which is distributed under the PolyForm Noncommercial License 1.0.0 (its LICENSE file,
with the Required Notice line above, copyright Onur Yuzseven). The MIT license does not
by itself grant rights in content derived from a source under other terms. THE TERMS
UNDER WHICH THE CONVERTED CONTENT MAY BE REDISTRIBUTED ARE PENDING THE OWNER'S
CONFIRMATION: nothing here is a statement that the content is MIT licensed or that the
source's terms permit this use. Anyone who receives a copy of any part of that content
from this pak also receives the terms URL and the Required Notice line above.

PLACEHOLDER: the final wording of this notice, and the licensing terms under
which the original design material and the converted content are reused, wait for the
owner's licensing record. Until that record is complete this repository stays private
and no release is to be tagged.
