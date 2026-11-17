# Intent

Why this pak exists, what it set out to do, and the decisions that shaped it. The technical design is in
[vcommunity-java-port-design.md](vcommunity-java-port-design.md).

## Why

vCommunity is a management pack for VCF Operations that adds hundreds of vSphere metrics and properties, scores,
dashboards, views and reports. Onur Yuzseven created it; Iwan "e1" Rahabok continued it (0.8.1 is the version this pak
follows). It is written in Python for the Operations integration SDK.

This pak is a new implementation of the same pack in Java, on the Operations adapter SDK this project builds against. The
aim is a pak that collects the same data, ships the same content, and is built and released from one repository.

## Goals

- **Same keys, so history carries over.** Every metric and property keeps the key name, instance name and target object it
  has in vCommunity 0.8.1 (the `vCommunity|...` tree). Dashboards, super metrics and reports written against vCommunity keep
  working, and the history of a key continues across the move.
- **Own content ids, so nothing built-in is overwritten.** Every dashboard, view, report, super metric, symptom and alert is
  published under ids minted for this pak. Some vCommunity versions ship views under built-in view ids, so installing them
  overwrites built-in views and uninstalling them deletes those views. This pak never does either. Content names stay the
  same, except where a re-authored view would share a title with a built-in view.
- **Match 0.8.1 first, then improve deliberately.** The first target is the 0.8.1 behaviour, field for field: the same
  account settings, credential, configuration files, scores and formulas. Where this pak differs, the difference is a recorded
  decision with a reason (the register in the design document), not a side effect.
- **Say what could not be read.** A value that could not be read is left out and counted in the collection health keys. It is
  never replaced by a zero, `False` or placeholder text. A bad entry in a configuration file is skipped and reported, and does
  not stop the cycle.
- **Minimum VCF Operations 8.18.** Collection is tested on 8.18.7, 9.0.2 and 9.1.1.
- **MIT.** The adapter code and all bundled content are MIT licensed. `NOTICE` credits the vCommunity authors.

## Decisions

- **A separate adapter kind (`vcfcf_vcommunity`), not an upgrade of the Python pack.** Operations cannot upgrade a Python
  pack in place into a Java adapter. The supported path is to uninstall the Python pack and install this one fresh. Running
  both side by side is not supported: they write the same keys onto the same objects and ship content with the same names.
- **The key tree and instance names stay exactly as they are.** History matters more than tidier names.
- **Re-author the views that began as edited built-in views.** The 101 views get ids of their own and new descriptions, so an
  upgrade of this pak can never overwrite or remove a built-in view. Nineteen views that only renamed a built-in view are
  not shipped; the dashboards and reports that used them point at the built-in view.
- **Verify the vCenter certificate by default.** vCommunity trusts the certificate on first use. This pak verifies it, with an
  Allow Insecure SSL setting for labs. See the design document, section 2.5.
- **Guest monitoring is off by default** and protects the Windows account from lockout before anything else.
- **Metrics and properties are written to the objects the built-in vCenter adapter already has.** The pak adds three object
  kinds of its own (content libraries, library items, VM templates).
- **Version 3.x.** vCommunity 0.1 to 0.8.1 is the Python line; this pak is the 3.x line, so a version number never collides.
- **Dashboards without a Section widget on versions that cannot import one.** Two dashboards use a widget that VCF Operations
  imports only from 9.1.1. They install without their Section header rows below that, and the rest is unchanged.
- **Write the documentation new.** The Python pack's documentation is a reference for behaviour only.

## Out of scope

- The hardware vCommunity pack.
- Content and keys that vCommunity itself dropped before 0.8.1.
- An in-place upgrade from the Python pack, and workarounds for one.
- Changing a vCommunity score or formula for its own sake. A change needs a reason and a register entry.
