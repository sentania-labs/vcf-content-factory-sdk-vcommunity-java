#!/usr/bin/env python3
"""Generate docs/design/dashboards-port-comparison.md.

The comparison of the ported dashboards with the source pack's dashboards.
It is generated, never written by hand:

  * the converted dashboards      dashboards/*.yaml
  * the importer's verification   docs/design/inputs/import-report/report.json
                                  (50 dashboards, pak layout, target 8.18.7,
                                  --ids mint --mint-namespace vcfcf_vcommunity)
                                  docs/design/inputs/import-report/
                                  section-dashboards-report.json
                                  (the 2 Section dashboards, extracted from
                                  section-dashboards-source-report.json, the
                                  full third-party-layout run; see its "note")
  * the id mapping                docs/design/inputs/import-report/id_map.tsv
                                  (source id -> id minted in the pak's own namespace)
  * the built-in view drops       docs/design/inputs/dropped.tsv
  * the accepted differences of        docs/design/inputs/dashboard_signoffs.tsv
    section 4 (dashboard, difference, statement, date; a row signs off
    the section 4 entry of that dashboard name, any other stays "open")

Per dashboard it prints the dashboard-level settings and the widgets. A
widget is "identical" unless the importer's report records a difference at a
path under that widget (`widget[N:Type]...`); a dashboard-level setting is
"identical" unless a difference is recorded at `dashboard.<key>`. Nothing is
inferred beyond that: the importer compared every source key (accounting),
rendered the YAML and compared it with the source.

Usage (from anywhere; stdlib + PyYAML only):

    python3 docs/design/tools/dashboards_port_comparison.py            # writes the note
    python3 docs/design/tools/dashboards_port_comparison.py --stdout   # prints it
    python3 docs/design/tools/dashboards_port_comparison.py --check    # exit 1 if the note is stale

Re-run after any edit to dashboards/*.yaml or either report. The "seen
rendering" table is not generated: it is filled in by hand
after the live install and is preserved across runs (the text between the
OPEN-TABLE markers is carried over from the existing note).
"""
import argparse
import collections
import csv
import json
import re
import sys
from pathlib import Path

import yaml

SOURCE = {
    "pack": "vCommunity (Onur Yuzseven / vmbro), as continued by e1-rahabok",
    "version": "0.8.1",
    "pak_file": "vCommunity_0.8.1-fix-builtin-view-ids-e1-releasedimage.pak",
    "sha256": "7cc81338614a185bf3546f1d9112eb2e1a9c19c5a507eddad64a637339b05780",
}
SIGNOFF_DATE = "2026-11-13"
SECTION_FLOOR = "9.1.1"
MARK_BEGIN = "<!-- OPEN-TABLE-BEGIN: filled by hand after the live install -->"
MARK_END = "<!-- OPEN-TABLE-END -->"

OPEN_TABLE = """| Product line | Instance (host, version, build) | Date seen | Dashboards seen rendering | Seen by | Result |
|---|---|---|---|---|---|
| 8.18.x (8.18.7) | _open_ | _open_ | _open: all 52, the two Section dashboards without Section rows_ | _open_ | _open_ |
| 9.0.x (9.0.2) | _open_ | _open_ | _open: all 52, the two Section dashboards without Section rows_ | _open_ | _open_ |
| 9.1.x (9.1.1) | _open_ | _open_ | _open: all 52, the two Section dashboards with their Section rows (the Section variant)_ | _open_ | _open_ |
"""


def load_signoffs(root):
    """dashboard name -> (difference, statement, date) from inputs/dashboard_signoffs.tsv."""
    path = root / "docs/design/inputs/dashboard_signoffs.tsv"
    out = {}
    if not path.exists():
        return out
    with path.open(newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            out[row["dashboard"]] = (row["difference"], row["statement"], row["date"])
    return out


def signoff_text(signoffs, name):
    s = signoffs.get(name)
    if not s:
        return "**Open**"
    diff, said, date = s
    return f"**Accepted** ({diff}; links that could not fire were not carried)"


def cell(v):
    return str(v).replace("|", "\\|").replace("\n", " ").strip()


def load_reports(root):
    objs, detail = {}, {}
    for name in ("report.json", "section-dashboards-report.json"):
        p = root / "docs/design/inputs/import-report" / name
        r = json.loads(p.read_text())
        for o in r["objects"]:
            if o["type"] == "dashboard":
                objs[o["file"]] = o
        for d in r["verify"]["objects_detail"]:
            if d["type"] == "dashboard":
                detail[d["file"]] = d
    return objs, detail


def load_id_map(root):
    """file -> (old_id, new_id) for every object in id_map.tsv (comment lines skipped)."""
    p = root / "docs/design/inputs/import-report/id_map.tsv"
    rows = [l for l in p.read_text().splitlines() if not l.startswith("#")]
    return {r["file"]: (r["old_id"], r["new_id"]) for r in csv.DictReader(rows, delimiter="\t")}


def load_builtin_ids(root):
    out = {}
    with open(root / "docs/design/inputs/dropped.tsv", newline="") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            out[row["id"]] = row["builtin_name_9_1_1"]
    return out


def view_names(root):
    return {yaml.safe_load(p.read_text())["name"] for p in (root / "views").glob("*.yaml")}


UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def bound(w, builtin, names):
    t = w["type"]
    if t == "View":
        v = w["view"]
        if v in names:
            return f"view `{v}`"
        if UUID.match(str(v)):
            nm = builtin.get(v)
            return f"built-in view by id `{v}`" + (f" ({nm})" if nm else " (product view the source pack never shipped)")
        return f"view `{v}` (DANGLING)"
    if t == "TextDisplay":
        return "text/html block"
    if t == "Section":
        return f"members {', '.join(w.get('widgets', []))}"
    keys = []

    def walk(o):
        if isinstance(o, dict):
            if "metric_key" in o and o["metric_key"] not in keys:
                keys.append(o["metric_key"])
            for k, v in o.items():
                if k not in ("navigations", "ui"):
                    walk(v)
        elif isinstance(o, list):
            for x in o:
                walk(x)

    walk(w)
    kind = w.get("resource_kind") or ""
    s = f"{len(keys)} metric(s)" if keys else "no metric key"
    if keys:
        s += ": " + ", ".join(f"`{k}`" for k in keys[:3]) + (f" +{len(keys) - 3}" if len(keys) > 3 else "")
    if kind:
        s = f"{kind}; " + s
    return s


def pin(w):
    p = w.get("pin")
    sp = w.get("self_provider")
    if not p and not sp:
        return "none"
    if not p:
        return "self-provider, no pin"
    return "pin " + "/".join(str(p.get(k)) for k in ("adapter_kind", "resource_kind") if p.get(k)) + (
        f" ({p['traversal_spec']})" if p.get("traversal_spec") else ""
    ) + "".join(f" {k}={p[k]}" for k in p if k not in ("adapter_kind", "resource_kind", "traversal_spec"))


def grid(c):
    if not c:
        return "-"
    return f"x{c.get('x', '?')} y{c.get('y', '?')} {c.get('w', 12)}x{c.get('h', 1)}"


def omitted_layout(widgets):
    """Rows close up when Section rows go: y minus the Section rows above it.
    A Section row some remaining widget still covers stays open (the converter
    rule, dashboard_fidelity_wire_format.md 'Rows close up')."""
    secs = [w for w in widgets if w["type"] == "Section"]
    rest = [w for w in widgets if w["type"] != "Section"]
    closed = []
    for s in secs:
        sy = s["coords"]["y"]
        if not any(w["coords"]["y"] < sy < w["coords"]["y"] + w["coords"].get("h", 1) for w in rest):
            closed.append(sy)
    return {w["id"]: dict(w["coords"], y=w["coords"]["y"] - sum(1 for y in closed if y < w["coords"]["y"])) for w in rest}


WIDGET_PATH = re.compile(r"^widget\[(\d+):")


def classify(obj, det):
    """-> (widget_losses {idx: set(class)}, dashboard_diffs [(path, kind, class)], target [(path, kind, text)])"""
    cls_at = {}
    for l in obj["losses"]:
        for a in l["at"]:
            cls_at[a] = l["class"]
    wl = collections.defaultdict(set)
    dd = []
    unknown = []
    for path, kind, _val in det["at_recorded_losses"]:
        c = cls_at.get(path, "UNCLASSIFIED")
        m = WIDGET_PATH.match(path)
        if m:
            wl[int(m.group(1))].add(c)
        else:
            dd.append((path, kind, c))
    tgt = [(p, k) for p, k, _ in det.get("at_target_deviations", [])]
    if det["differences"]:
        unknown = det["differences"]
    return wl, dd, tgt, unknown


def dash_key(path):
    return re.sub(r"^dashboard\.", "", path).split(".")[0].split("[")[0]


def render(root):
    objs, detail = load_reports(root)
    builtin = load_builtin_ids(root)
    idmap = load_id_map(root)
    names = view_names(root)
    files = sorted((root / "dashboards").glob("*.yaml"))
    out = []
    summary = collections.OrderedDict(
        zero=[], forced_only=[], loss_dash_only=[], loss_widget=[], layout=[]
    )
    loss_kinds = collections.defaultdict(lambda: [0, 0])  # class -> [dashboards, occurrences]
    sections = []
    all_widgets = 0
    builtin_refs = collections.Counter()
    titles = collections.defaultdict(set)
    problems = []
    n_unrecorded = sum(len(d["differences"]) for d in detail.values())
    id_rows = {}  # file -> (source id, new id, where the pair is recorded)

    for f in files:
        rel = f"dashboards/{f.name}"
        d = yaml.safe_load(f.read_text())
        if rel not in objs or rel not in detail:
            problems.append(f"{rel}: no importer report entry")
            continue
        obj, det = objs[rel], detail[rel]
        wl, dd, tgt, unknown = classify(obj, det)
        if d["id"] != obj["id"]:
            problems.append(f"{rel}: YAML id {d['id']} differs from the report's minted id {obj['id']}")
        if rel in idmap:
            if idmap[rel] != (obj["source_id"], d["id"]):
                problems.append(f"{rel}: id_map.tsv {idmap[rel]} disagrees with the report/YAML ({obj['source_id']}, {d['id']})")
            id_rows[rel] = (obj["source_id"], d["id"], "id_map.tsv")
        else:
            id_rows[rel] = (obj["source_id"], d["id"], "section-dashboards-report.json")
        if obj["source_id"] == d["id"]:
            problems.append(f"{rel}: id was not re-minted (source id == id)")
        if unknown:
            problems.append(f"{rel}: report shows unrecorded differences")
        widgets = d["widgets"]
        n_sec = sum(1 for w in widgets if w["type"] == "Section")
        all_widgets += len(widgets)
        classes = collections.Counter()
        for _p, _k, c in dd:
            classes[c] += 1
        for s in wl.values():
            for c in s:
                pass
        for l in obj["losses"]:
            classes[l["class"]] += 0
        occ = collections.Counter()
        for l in obj["losses"]:
            occ[l["class"]] += len(l["at"])
        for c, n in occ.items():
            loss_kinds[c][0] += 1
            loss_kinds[c][1] += n
        # status bucket
        if n_sec:
            summary["layout"].append(d["name"])
        if occ and (wl or n_sec):
            summary["loss_widget"].append(d["name"])
        elif occ:
            summary["loss_dash_only"].append(d["name"])
        elif tgt:
            summary["forced_only"].append(d["name"])
        elif not n_sec:
            summary["zero"].append(d["name"])
        # ---- section
        src_line = f.read_text().splitlines()[0]
        src = src_line.split("!", 1)[-1] if "!" in src_line else src_line
        out.append(f"### {cell(d['name'])}\n")
        out.append(f"File `{rel}`; id `{d['id']}` (newly minted; source id `{obj['source_id']}`); source `{src}`.\n")
        out.append(f"Importer verification: **{det['status']}** ({det['fields']} fields compared).\n")
        # dashboard-level settings
        out.append("Dashboard-level settings\n")
        out.append("| Setting | Value | Compared with source |")
        out.append("|---|---|---|")
        skip = {"widgets", "interactions", "description", "id", "name"}
        for k, v in d.items():
            if k in skip:
                continue
            if k == "section_fallback":
                out.append(f"| `{k}` | `{v}` | added by the port, not a source key ; no effect on a target that imports Section |")
                continue
            out.append(f"| `{k}` | `{cell(v)}` | identical |")
        out.append(f"| id | `{d['id']}` | **different**, intended: newly minted in the pak's own namespace; source id `{obj['source_id']}` |")
        out.append(f"| interactions | {len(d.get('interactions') or [])} | identical |")
        out.append(f"| name | `{cell(d['name'])}` | identical" + (" (name_path carried in `name_path`)" if d.get("name_path") else "") + " |")
        out.append(f"| description | {len(d.get('description') or '')} chars | identical" + (" (surrounding whitespace stripped by the loader: recorded loss)" if any("Description surrounding" in c for c in occ) else "") + " |")
        seen = set()
        for path, kind, c in dd:
            key = (dash_key(path), c)
            if key in seen:
                continue
            seen.add(key)
            n = sum(1 for p, _k, cc in dd if dash_key(p) == key[0] and cc == c)
            out.append(f"| `{key[0]}` | (source-only key, {n} path(s)) | **different**: recorded loss, {c} |")
        for p, k in tgt:
            out.append(f"| `{p}` | {k} | **different**: forced by target 8.18.7 (`omit:` entry whose floor is above the target is not emitted; the render carries the key where the source has none), set aside by the importer |")
        if n_sec:
            out.append(f"| widgets | {len(widgets)} with {n_sec} Section row(s) | **different** on targets below {SECTION_FLOOR}: the {n_sec} Section widget(s) are omitted (`section_fallback: omit`) |")
        out.append("")
        has_widget_diff = bool(wl) or n_sec > 0
        w_ids = {w["id"]: w for w in widgets}
        inbound, outbound = collections.defaultdict(list), collections.defaultdict(list)
        for i in d.get("interactions") or []:
            outbound[i["from"]].append(i["to"])
            inbound[i["to"]].append(i["from"])
        for w in widgets:
            if "time_range" in w:
                problems.append(f"{rel}: {w['id']} carries its own time_range (generator does not tabulate it)")
        if not has_widget_diff:
            out.append(f"{len(widgets)} widgets, all identical.\n")
        else:
            out.append(f"Widgets ({len(widgets)}; time range is dashboard-level: `{d.get('time_range', 'product default')}`)\n")
            if n_sec:
                out.append("| id | type | grid (as ported) | grid on targets below 9.1.1 | title | bound view or metrics | interactions | pins | compared with source |")
                out.append("|---|---|---|---|---|---|---|---|---|")
                om = omitted_layout(widgets)
            else:
                out.append("| id | type | grid | title | bound view or metrics | interactions | pins | compared with source |")
                out.append("|---|---|---|---|---|---|---|---|")
            for idx, w in enumerate(widgets):
                if w["type"] == "View" and UUID.match(str(w["view"])):
                    builtin_refs[w["view"]] += 1
                ia = []
                if inbound.get(w["id"]):
                    ia.append("from " + ",".join(inbound[w["id"]]))
                if outbound.get(w["id"]):
                    ia.append("to " + ",".join(outbound[w["id"]]))
                ia = "; ".join(ia) or "-"
                if w.get("navigations"):
                    ia += f"; {len(w['navigations'])} navigation(s)"
                if w["type"] == "Section":
                    mark = f"**different**: omitted below {SECTION_FLOOR}"
                    if idx in wl:
                        mark += "; also recorded loss: " + ", ".join(sorted(wl[idx]))
                elif idx in wl:
                    mark = "**different**: recorded loss, " + ", ".join(sorted(wl[idx]))
                else:
                    mark = "identical"
                    if n_sec and om[w["id"]] != w["coords"]:
                        mark += " (grid row moves up where Section rows close)"
                row = [w["id"], w["type"], grid(w["coords"])]
                if n_sec:
                    row.append("omitted" if w["type"] == "Section" else grid(om[w["id"]]))
                row += [cell(w.get("title", "")), cell(bound(w, builtin, names)), cell(ia), cell(pin(w)), mark]
                out.append("| " + " | ".join(row) + " |")
            out.append("")
        if wl and n_sec == 0:
            pass
        # builtin refs in widgets not in a detailed table
        if not has_widget_diff:
            for w in widgets:
                if w["type"] == "View" and UUID.match(str(w["view"])):
                    builtin_refs[w["view"]] += 1
        for w in widgets:
            if w["type"] == "View" and not UUID.match(str(w["view"])) and w["view"] not in names:
                problems.append(f"{rel}: {w['id']} view `{w['view']}` is not in views/")
            if w["type"] == "View" and UUID.match(str(w["view"])):
                titles[w["view"]].add(w.get("title", ""))
        if n_sec:
            sections.append((d["name"], n_sec, len(widgets), len(widgets) - n_sec))

    # ---- head
    head = []
    head.append("# Dashboards port comparison \n")
    head.append("Generated by `docs/design/tools/dashboards_port_comparison.py`; do not edit by hand (re-run it). "
                "The table in section 6 (seen rendering) is the one hand-filled table, kept across runs.\n")
    head.append("## 1. Source\n")
    head.append(f"- Pack: {SOURCE['pack']}, version **{SOURCE['version']}**")
    head.append(f"- Pak file: `{SOURCE['pak_file']}`")
    head.append(f"- sha256: `{SOURCE['sha256']}`")
    head.append("- Summary-tab binding: none. The source pak has no `dashboards.properties`, and neither has this pak; the six \"Object Summary Page for ...\" dashboards are plain dashboards (no `summary_for`). The importer no longer infers Summary bindings (the run report's `summary_bindings` is empty).")
    head.append("- Converted offline by `python3 -m vcfcf_extractor import-local` (pak layout, `--ids mint --mint-namespace vcfcf_vcommunity`, `--naming unprefixed`, `--target-min 8.18.7`) "
                "and verified by it: it renders each converted YAML through the renderer and compares it key by key with the source JSON. "
                "The two Section dashboards the pak layout refuses below 9.1.1 were converted from the same pak in the third-party layout "
                "(`--allow-partial`, same `--ids mint` namespace) and carry `section_fallback: omit`; their verification is `docs/design/inputs/import-report/section-dashboards-report.json`.")
    head.append("- **Every dashboard id is newly minted in the pak's own namespace**: this is an intended change, not a defect. "
                "Decision: the pak's symptoms and dashboards carry ids unique to it (namespace `vcfcf_vcommunity`, "
                "uuid5 `5d8c5726-50f2-5afb-b27d-015fe871ae98`, deterministic: the same run mints the same ids) so they cannot collide with the "
                "source pack's content, because the pak is a new major line (3.x). The source id is shown beside each new id below; "
                "the pairs for 50 dashboards are in `docs/design/inputs/import-report/id_map.tsv`, those for the two Section dashboards in "
                "`section-dashboards-report.json` (`source_id`). Names, widget ids, and every other key are unchanged by the minting. "
                "References to product (built-in) views by id (section 5) are not ours and are not re-minted.\n")
    head.append("| # | Dashboard | New id (minted) | Source id | Pair recorded in | File |")
    head.append("|---|---|---|---|---|---|")
    for i, f in enumerate(files, 1):
        d = yaml.safe_load(f.read_text())
        r = id_rows.get(f"dashboards/{f.name}")
        if r:
            head.append(f"| {i} | {cell(d['name'])} | `{d['id']}` | `{r[0]}` | `{r[2]}` | `dashboards/{f.name}` |")
    head.append("")
    # summary
    nloss = len(summary["loss_widget"]) + len(summary["loss_dash_only"])
    head.append("## 2. Summary\n")
    head.append(f"- Dashboards: **{len(files)}**; widgets: **{all_widgets}** (Section widgets included).")
    head.append(f"- Dashboard ids newly minted in the pak's own namespace (intended change): **{len(id_rows)}** of {len(files)}; "
                f"pairs from `id_map.tsv`: {sum(1 for r in id_rows.values() if r[2] == 'id_map.tsv')}, from the Section-dashboards report: {sum(1 for r in id_rows.values() if r[2] != 'id_map.tsv')}.")
    head.append(f"- Zero differences, not even a forced target key: **{len(summary['zero'])}**")
    head.append(f"- Identical apart from forced target keys only (`omit: temporary` is not emitted for 8.18.7; the render carries `temporary` where the source has none): **{len(summary['forced_only'])}**")
    head.append(f"- Differences at recorded losses only: **{nloss}** (of which {len(summary['loss_widget'])} at widget level or with a layout difference, {len(summary['loss_dash_only'])} at dashboard level only, widgets all identical)")
    head.append(f"- Layout differences (widget count, grid rows): **{len(summary['layout'])}**, the two Section dashboards (section 3)")
    head.append(f"- Unrecorded differences (importer `differ` count, summed over both reports): **{n_unrecorded}**; every bucket above counts the intended id change aside\n")
    head.append("Recorded-loss kinds (a class the converter does not model; each is a difference the importer recorded when it dropped or altered something):\n")
    head.append("| Kind | Dashboards | Occurrences | Level |")
    head.append("|---|---|---|---|")
    levels = {
        "adapterName": "dashboard: pak ownership is set by the pak, not carried in the YAML",
        "config.widgetId naming no widget of the dashboard": "widget: a stale internal id inside the source widget config; the renderer writes the widget's own id",
        "Description surrounding whitespace": "dashboard or widget description: leading/trailing whitespace stripped by the loader",
        "navigation keyed by a widget not on the dashboard": "dashboard: navigation entries in the source that name a widget the dashboard does not have (stale); see section 4",
        "dashboard label surrounding whitespace": "widget: Scoreboard label whitespace",
        "Heatmap tab name surrounding whitespace": "widget: Heatmap tab name whitespace",
    }
    for c, (nd, no) in sorted(loss_kinds.items(), key=lambda kv: -kv[1][0]):
        head.append(f"| {c} | {nd} | {no} | {levels.get(c, '')} |")
    head.append("")
    head.append("Dashboards by bucket:\n")
    for k, label in (("zero", "zero differences"), ("forced_only", "forced target keys only"),
                     ("loss_dash_only", "recorded losses at dashboard level only"),
                     ("loss_widget", "recorded losses at widget level, or Section layout difference"),
                     ("layout", "layout difference (Section widgets)")):
        head.append(f"- {label} ({len(summary[k])}): " + "; ".join(summary[k]))
    head.append("")
    # section signoff
    head.append("## 3. Section dashboards: the only layout difference\n")
    head.append(f"Section needs VCF Operations {SECTION_FLOOR} or later; 8.18.7 and 9.0.x cannot import it (a dashboard carrying one aborts the whole import). "
                "Decision (design section 10.3, \"Section widget\"): build the two dashboards without sections while 8.x and 9.0 are supported, and keep a Section version committed and producible.\n")
    head.append("The committed YAML keeps every Section widget and sets `section_fallback: omit`. A build below 9.1.1 (the pak's minimum version is 8.18.0) "
                f"ships the dashboard without its Section widgets, rows closed up; raising the minimum to {SECTION_FLOOR} ships the Section variant from the same source with no content edit. "
                "The omitted Section rows are the only layout difference on targets below 9.1.1; every other widget keeps its id, type, width, height, column, title, view or metrics, interactions and pins, and only its row number can move up (the per-widget tables below show both grids).\n")
    head.append("| Dashboard | Widgets as ported | Section widgets omitted below 9.1.1 | Widgets shipped below 9.1.1 |")
    head.append("|---|---|---|---|")
    for n, ns, tot, rest in sections:
        head.append(f"| {cell(n)} | {tot} | {ns} | {rest} |")
    head.append("")
    # builtin refs
    head.append("## 4. Other differences (other than Section)\n")
    head.append("Differences in interaction wiring or subject. Listed from the importer's recorded losses, none removed by the port:\n")
    signoffs = load_signoffs(root)
    nav = [(objs[f"dashboards/{f.name}"]["name"], objs[f"dashboards/{f.name}"]) for f in files
           if any(l["class"].startswith("navigation keyed") for l in objs[f"dashboards/{f.name}"]["losses"])]
    for n, o in nav:
        at = [a for l in o["losses"] if l["class"].startswith("navigation keyed") for a in l["at"]]
        head.append(f"- **{n}**: {len(at)} recorded path(s), source navigation entries keyed by a widget that is not on the dashboard (stale in the source; they cannot fire there); the YAML does not carry them. {signoff_text(signoffs, n)}")
    if "vSphere Cluster Capacity 2.0" in [n for n, *_ in sections]:
        head.append(f"- **vSphere Cluster Capacity 2.0**: the source dashboard name has no folder prefix while its `namePath` is `vCommunity`; the target 8.18.7 does not emit the `folder_in_name` omit (floor unknown), so the render carries the folder in the name (`dashboard.name`, set aside by the importer). Folder placement, not grid or wiring. {signoff_text(signoffs, 'vSphere Cluster Capacity 2.0')}")
    head.append("")
    head.append("## 5. References to built-in views by id\n")
    dropped_ids = [k for k in builtin_refs if k in builtin]
    other_ids = [k for k in builtin_refs if k not in builtin]
    head.append(f"{sum(builtin_refs.values())} View widgets reference {len(builtin_refs)} distinct views by a bare uuid in `view:` (no view of that id is in `views/`; the loader accepts a raw id, and the importer notes that it must exist on the target). "
                f"{len(dropped_ids)} of them are among the 19 built-in views the port deliberately does not ship (`docs/design/inputs/dropped.tsv`); "
                f"the other {len(other_ids)} are product views the source pak never carried either (the source dashboards reference them by id); their existence on 8.18.7, 9.0.2 and 9.1.1 is not checked here.\n")
    head.append("| Built-in view id | Class | Built-in name (9.1.1, from dropped.tsv) | Widget title(s) | Widgets referencing it |")
    head.append("|---|---|---|---|---|")
    for k, n in sorted(builtin_refs.items(), key=lambda kv: (kv[0] not in builtin, builtin.get(kv[0], ""), kv[0])):
        head.append(f"| `{k}` | {'one of the 19 not shipped' if k in builtin else 'other product view, not shipped by the source pak'} | {cell(builtin.get(k, '-'))} | {cell('; '.join(sorted(titles[k])))} | {n} |")
    head.append("")
    head.append("## 6. Seen rendering on every product line\n")
    head.append("Open until the live install. Fill the table by hand; the generator keeps what is between the markers.\n")
    head.append(MARK_BEGIN)
    head.append("@@OPEN@@")
    head.append(MARK_END)
    head.append("")
    head.append("## 7. Per-dashboard comparison\n")
    head.append("Widget-level rows are printed for a dashboard whose widgets differ at some path or that carries Section widgets; "
                "a dashboard whose only differences are at dashboard level, or none, is \"N widgets, all identical\". "
                "No widget carries its own time range in the YAML; the time range is the dashboard-level `time_range`. "
                "\"identical\" means the importer compared every key of the widget (type, grid, title, bound view or metrics, interactions, pin, ...) with the source and found it equal, ids renumbered by the product set aside.\n")
    body = "\n".join(head) + "\n" + "\n".join(out)
    return body, problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[3]))
    ap.add_argument("--stdout", action="store_true")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    root = Path(a.root)
    note = root / "docs/design/dashboards-port-comparison.md"
    body, problems = render(root)
    open_table = OPEN_TABLE
    if note.exists():
        m = re.search(re.escape(MARK_BEGIN) + r"\n(.*?)\n" + re.escape(MARK_END), note.read_text(), re.S)
        if m:
            open_table = m.group(1).rstrip("\n") + "\n"
    body = body.replace("@@OPEN@@", open_table.rstrip("\n"))
    for p in problems:
        print("PROBLEM:", p, file=sys.stderr)
    if a.stdout:
        print(body)
    elif a.check:
        if not note.exists() or note.read_text() != body:
            print("stale: re-run the generator", file=sys.stderr)
            return 1
    else:
        note.write_text(body)
        print(f"wrote {note} ({len(body.splitlines())} lines)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
