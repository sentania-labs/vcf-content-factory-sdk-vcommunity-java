VCF Content Factory vCommunity
Copyright (c) 2026 Scott Bowe. The whole pak, the adapter code and all of the
bundled content, is licensed under the MIT License (see LICENSE and "Terms of
the bundled content" below).

Portions are derived from the vCommunity Management Pack for VCF Operations by
its authors, named below, and are included here under the MIT License with
their permission.

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

Acknowledgments
---------------

The vCommunity Management Pack for VCF Operations is the work of the people below,
credited as the upstream project credits itself and as its commit history records them:

  - Onur Yuzseven: the original vCommunity Management Pack and its adapter.
  - Iwan "e1" Rahabok: the dashboards, reports, super metrics and the pack's design,
    and the continuation of the pack (VCF-vCommunity-Adapter).
  - Brock Peterson: contributions to the pack, and the blog series that explains it
    and its use cases.
  - Mark Arbogast and Robert Mesropyan: credited by the upstream project as assisting
    Onur Yuzseven.
  - Other contributors in the upstream history: Dave Kaber, Alan Renouf and the
    contributor recorded as atm8119.

Content (views, dashboards, reports, super metrics, symptoms, alerts, recommendation)
------------------------------------------------------------------------------------

The content this pak installs was converted, by an offline tool, from the content
files of this source, which is acknowledged here as the origin of that content:

  Project:   VCF Operations vCommunity Management Pack ("vCommunity"), version 0.8.1
             (https://github.com/vmbro/VCF-Operations-vCommunity), as continued by
             e1-rahabok (https://github.com/e1-rahabok/VCF-vCommunity-Adapter)
  Build:     vCommunity_0.8.1-fix-builtin-view-ids-e1-releasedimage.pak, sha256
             7cc81338614a185bf3546f1d9112eb2e1a9c19c5a507eddad64a637339b05780: a factory
             build of the corrected content (the fix sent upstream as pull request 92 to
             e1-rahabok/VCF-vCommunity-Adapter) on the released 0.8.1 adapter image
  Upstream license: PolyForm Noncommercial License 1.0.0
  Terms:     https://polyformproject.org/licenses/noncommercial/1.0.0

  Required Notice: Copyright (c) 2026 Onur Yuzseven — VCF Operations vCommunity Management Pack (https://github.com/vmbro/VCF-Operations-vCommunity)

The converted content is 170 views, 52 dashboards, 16 reports, 2 super metrics, 10
symptom definitions, 4 alert definitions and 1 recommendation. It derives from those
files. Most of it keeps their names, descriptions, widget titles and layout as the
source wrote them, under ids minted for this pak (namespace vcfcf_vcommunity; no id is shared
with the source pack); the amount of free text kept as written is counted in
docs/design/inputs/carried-text-report.md. The conversion and its checks against the
source are in docs/design/inputs/import-report/.

What was written for this pak and is not carried from the source:

  - the descriptions of the 101 views that began as edited copies of VCF Operations'
    own views, and the new titles of nine of them (docs/design/inputs/view_rename.tsv,
    view_describe.tsv);
  - the ids of all of the content, minted by the offline tool in this pak's own namespace
    (docs/design/inputs/import-report/id_map.tsv maps each source id to its new id);
  - the six check-list files under content/files/solutionconfig/: the header text
    and examples are written from this pak's own parser; the file names, the
    account fields that name them and the public vSphere and Windows setting names in
    their examples are facts the source also has;
  - the 19 views that only renamed a VCF Operations view are not shipped at all.

Terms of the bundled content
----------------------------

The whole pak is licensed under the MIT License (LICENSE): the adapter code (the Java
source, the build files, the check-list file mechanism) and all of the bundled content
(views, dashboards, reports, super metrics, symptoms, alerts, the recommendation),
whether it was authored for this project or converted from the content files of the
source pack named above. The adapter itself is an independent Java implementation.

Provenance of the converted content: the dashboards, views, reports, super metrics,
symptoms and alerts were converted from the content of the upstream vCommunity pack
(VCF Operations vCommunity Management Pack, version 0.8.1, by Onur Yuzseven and the
contributors to vCommunity, as continued by e1-rahabok), under ids and, for 101 views,
descriptions of this project's own.

The upstream pack is published under its own license, the PolyForm Noncommercial
License 1.0.0 (its LICENSE file at tag v0.8.1). The converted content is released here
under the MIT License by decision of this project's owner, who holds the record of the
upstream authors' agreement. The upstream authors are acknowledged above and the
upstream Required Notice line is kept as the attribution it is:

  Required Notice: Copyright (c) 2026 Onur Yuzseven — VCF Operations vCommunity Management Pack (https://github.com/vmbro/VCF-Operations-vCommunity)
