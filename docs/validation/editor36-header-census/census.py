#!/usr/bin/env python3
"""Census of retained XCTest requests, not a reconstruction of gesture delivery."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import tarfile

HERE = Path(__file__).resolve().parent
ACTORS = {
    "Components": ["a11y_explorer_components"],
    "Search": ["a11y_explorer_search"],
    "Ports": ["a11y_explorer_ports", "a11y_explorer_hide_ports"],
    "Start": ["a11y_explorer_start_connection"],
    "Complete": ["a11y_explorer_complete_connection"],
}
POSITION = {
    "Search": ('item(key = "search")', "LazyColumn item 2, before commands"),
    "Components": ('item(key = "commands")', "commands item 3; selected semantics = !showWires"),
    "Ports": ('"a11y_explorer_hide_ports"', "inside a filtered component card, after header items"),
    "Start": ('"a11y_explorer_start_connection"', "expanded component terminal, after header items"),
    "Complete": ('"a11y_explorer_complete_connection"', "same terminal action switches with pending start"),
}


def load_evidence(path):
    archive = path / "raw.tar.gz" if path.is_dir() else path
    if archive.is_file() and archive.suffix == ".gz":
        with tarfile.open(archive, "r:gz") as stream:
            members = {m.name: m for m in stream.getmembers()}
            assert len(members) == len(stream.getmembers())
            def read(name):
                member = members[name]
                assert member.isfile() and member.size < 5_000_000
                return stream.extractfile(member).read()
            return collect(read)
    return collect(lambda name: (path / name).read_bytes())


def collect(read):
    records = json.loads(read("attachments/manifest.json"))
    attachments = [a for case in records for a in case.get("attachments", [])]
    kept = {}
    for entry in attachments:
        filename = entry["exportedFileName"]
        assert filename and "/" not in filename and "\\" not in filename and filename not in (".", "..")
        name = entry.get("suggestedHumanReadableName", "")
        if name.startswith(("native-tree_", "editor-search-before-", "geometry-editor-explorer-search_")):
            assert filename not in kept
            kept[filename] = {
                "name": name,
                "timestamp": entry.get("timestamp"),
                "raw": read("attachments/" + filename).decode("utf-8"),
            }
    return {"results": json.loads(read("results.json")), "log": read("test.log").decode("utf-8"),
            "catalog": json.loads(read("harness/Catalog.json")), "retained": kept,
            "editor": read("harness/EditorEndToEndTests.swift").decode("utf-8"),
            "base": read("harness/NativeUIHarness.swift").decode("utf-8")}


def classify(text, catalog):
    for actor, keys in ACTORS.items():
        for key in keys:
            for template in catalog[key].values():
                prefix = re.split(r"\{[^}]+\}", template)[0]
                if prefix and prefix in text:
                    return actor
    if '"a11y-circuit-search" IN identifiers' in text:
        return "Search"
    return None


def analyze(label, evidence):
    rows = {actor: {"queries": [], "taps": [], "drags": []} for actor in ACTORS}
    step, current = 0, None
    failure = None
    returns = []
    measured = []
    measurement_gaps = []
    names = ("editor-search-before-text-entry", "editor-search-before-backward-scroll")
    queues = {name: sorted([(filename,item) for filename,item in evidence["retained"].items()
                           if item["name"].startswith(name + "_")],
                          key=lambda x:x[1]["timestamp"] if type(x[1]["timestamp"]) in (int,float) else -1)
              for name in names}
    counts = Counter(re.findall(r"Added attachment named '([^']+)'", evidence["log"]))
    ambiguous = {}
    for name, queue in queues.items():
        times = [item["timestamp"] for _,item in queue]
        if len(queue) != counts[name] or any(type(t) not in (int,float) or not math.isfinite(t) or t < 0 for t in times) or \
                any(b <= a for a,b in zip(times,times[1:])):
            ambiguous[name] = "Cardinality or timestamp order does not permit an unambiguous log-to-file association"
            queues[name] = []
    for line in evidence["log"].splitlines():
        stamp = re.search(r"t =\s*([0-9.]+)s", line)
        seconds = float(stamp[1]) if stamp else None
        if match := re.search(r"contract step (\d+): (\w+)", line):
            step = int(match[1])
        if match := re.search(r"Added attachment named '([^']+)'", line):
            if match[1] in queues:
                if queues[match[1]]:
                    filename,item = queues[match[1]].pop(0)
                    if step >= 16:
                        measured.append((filename,item,step,seconds))
                elif step >= 16:
                    measurement_gaps.append({"name": match[1], "step": step, "seconds": seconds,
                        "reason":ambiguous.get(match[1], "Retained attachment missing")})
        if step < 16:
            continue
        if "Get all elements bound by index" in line:
            if actor := classify(line, evidence["catalog"]):
                current = actor
                rows[actor]["queries"].append({"seconds": seconds, "step": step, "request": line.strip()})
        if match := re.search(r'Tap "([^"]+)" (Button|TextView|TextField)', line):
            target = match[1]
            actor = classify(target, evidence["catalog"])
            if target == "a11y-circuit-search":
                actor = "Search"
            if target.startswith("port:") and current in ("Start", "Complete"):
                actor = current
            if actor:
                rows[actor]["taps"].append({"seconds": seconds, "step": step, "target": target})
            if target == "Return":
                returns.append({"seconds": seconds, "step": step})
        if "Press Application" in line and "then drag to" in line and current:
            points = re.findall(r"-> \((-?[0-9.]+), (-?[0-9.]+)\)", line)
            if len(points) == 2:
                first, last = [tuple(map(float, p)) for p in points]
                rows[current]["drags"].append({"seconds": seconds, "step": step,
                    "start": first, "end": last,
                    "direction": "up" if last[1] < first[1] else "down" if last[1] > first[1] else "horizontal"})
        if "error:" in line and "a11y-circuit-list" in line:
            failure = {"step": step, "actorBeingQueried": current, "message": line.strip()}
    final_trees = [x["raw"] for x in evidence["retained"].values() if x["name"].startswith("native-tree_")]
    final = final_trees[0] if final_trees else ""
    measurements = []
    for filename, item, step, seconds in measured:
        rects = dict(re.findall(r"(field|viewport)=\(([^)]+)\)", item["raw"]))
        contained = None
        if set(rects) == {"field", "viewport"}:
            f, v = [tuple(map(float, rects[k].split(","))) for k in ("field", "viewport")]
            if len(f) == len(v) == 4 and all(math.isfinite(n) for n in f + v):
                contained = f[2] >= 48 and f[3] >= 48 and v[0] <= f[0] and v[1] <= f[1] and f[0]+f[2] <= v[0]+v[2] and f[1]+f[3] <= v[1]+v[3]
        measurements.append({"file": filename, "name": item["name"], "step": step, "seconds": seconds, "raw": item["raw"],
                             "fullFieldInsideRecordedViewport": contained})
    for actor, row in rows.items():
        row["visibility"] = "NOT_MEASURED_BY_RETAINED_FRAME"
        row["viewport"] = "NOT_RETAINED_FOR_THIS_ACTOR"
        if actor == "Search" and measurements:
            if any(item["fullFieldInsideRecordedViewport"] is not None for item in measurements):
                row["visibility"] = "SEARCH_FRAME_RECORDED_BEFORE_ENTRY; see each measurement, not continuous visibility"
            row["viewport"] = measurements
        row["keyboard"] = "No per-action input-frame census; Return requests do not prove subsequent keyboard absence"
        row["selectedState"] = "NOT_RECORDED"
        row["tapMeaning"] = "XCTest tap request only; no application receive/consume receipt"
    return {"run": label, "appProducer": evidence["results"]["declared_baseline_sha"],
            "preparedSwiftCount": len(evidence["results"]["harness_sources"]), "fromContractStep": 16,
            "status": evidence["results"]["status"], "actors": rows, "failure": failure,
            "keyboardReturnRequests": returns, "missingGeometryAttachments": measurement_gaps,
            "finalTreeRetained": bool(final),
            "sheetPresentInFinalTree": "identifier: 'editor-tool-sheet'" in final if final else None,
            "listPresentInFinalTree": "identifier: 'a11y-circuit-list'" in final if final else None,
            "editorSearchSource": snippet(evidence["editor"], "private func explorerSearch(", "private func closeExplorer()"),
            "connectSource": snippet(evidence["editor"], "private func connect(", "private func removeWire(")}


def snippet(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))].strip()


def census(r1, r2):
    index = json.loads((HERE / "source-index.json").read_text())
    for name, record in index.items():
        data = (HERE / "source" / name).read_bytes()
        assert hashlib.sha256(data).hexdigest() == record["sha256"] and len(data) == record["bytes"]
    source = (HERE / "source/AccessibleCircuitExplorer.kt").read_text().splitlines()
    positions = {actor: {"relationship": relationship, "line": next(i for i,line in enumerate(source, 1) if needle in line),
                         "file": "source/AccessibleCircuitExplorer.kt", "pin": index["AccessibleCircuitExplorer.kt"]["pin"]}
                 for actor, (needle, relationship) in POSITION.items()}
    app = (HERE / "source/App.kt").read_text().splitlines()
    modal_line = next(i for i,line in enumerate(app, 1) if 'testTag("editor-tool-sheet")' in line)
    focus_line = next(i for i,line in enumerate(source, 1) if "listState.scrollToItem(2)" in line)
    return {"premise": "A generic forward-then-backward reveal can reach navigation controls earlier in the same LazyColumn.",
            "scope": "Retained evidence of connection preparation from contract step16 onward; absent measurements stay unknown.",
            "sourcePositions": positions,
            "sourceDependencies": {"modalSheet": {"file":"source/App.kt", "line":modal_line,
                "context":"\n".join(app[modal_line-1:modal_line+17])},
                "searchShortcut": {"file":"source/AccessibleCircuitExplorer.kt", "line":focus_line,
                "text":source[focus_line-1]}},
            "runs": [analyze("R1",load_evidence(r1)), analyze("R2",load_evidence(r2))],
            "limits": ["No intermediate full AX tree was retained for these header transitions.",
                       "Selected state was not read back; source semantics are eligibility for a future check, not observed runtime state.",
                       "Drag/tap records show requested input, not delivery or nested-scroll consumption.",
                       "Final-tree absence is not a timestamped dismissal callback.",
                       "R1ba9 and R2e835/Root2d4d differ in more than the private helper; no isolated causal A/B claim."]}


def markdown(data):
    lines = ["# Editor36 header census", "", data["premise"], "", data["scope"], "",
             "| Run | Actor | Queries | Taps | Requested drags in order | Visibility / viewport |", "| --- | --- | ---: | ---: | --- | --- |"]
    for run in data["runs"]:
        for actor, row in run["actors"].items():
            drags = ", ".join(f"{x['seconds']:.2f}:{x['direction']}" for x in row["drags"]) or "none recorded"
            visible = "retained measurements; JSON distinguishes field/viewport" if isinstance(row["viewport"],list) else "not measured"
            lines.append(f"| {run['run']} | {actor} | {len(row['queries'])} | {len(row['taps'])} | {drags} | {visible} |")
    final_note = "Both retained final trees lack the sheet/list. This does not establish when or how they were dismissed." \
        if all(r["finalTreeRetained"] and r["sheetPresentInFinalTree"] is False and r["listPresentInFinalTree"] is False for r in data["runs"]) \
        else "Final-tree presence or retention differs; missing trees remain unknown. See JSON per run."
    lines += ["", final_note, "", "Source positions:", ""]
    lines += [f"- {actor}: {item['relationship']}; {item['file']}:{item['line']} at {item['pin']}." for actor,item in data["sourcePositions"].items()]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--r1", type=Path, required=True)
    parser.add_argument("--r2", type=Path, required=True)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()
    result = census(args.r1, args.r2)
    print(markdown(result) if args.markdown else json.dumps(result,indent=2), end="" if args.markdown else "\n")
