#!/usr/bin/env python3
"""Replay the frozen navigation census using only local files and standard Python."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent
RAW = ROOT / "failed-14368"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def census():
    invocations = []
    for path in sorted(RAW.glob("downloads-navigation-*.json")):
        trace = json.loads(path.read_text())
        assert trace["transitionLimitSeconds"] == 30 and trace["totalLimitSeconds"] == 90
        events = trace["events"]
        assert events[-1]["kind"] == "finish"
        assert all(left["seconds"] <= right["seconds"] for left, right in zip(events, events[1:]))
        phases = []
        for phase in dict.fromkeys(event["phase"] for event in events):
            group = [event for event in events if event["phase"] == phase]
            queries = [event for event in group if event["kind"] in ("screen", "device-model")]
            taps = [event for event in group if event["kind"] == "tap"]
            phases.append({"phase": phase, "actions": len(taps),
                "screenQueries": sum(event["kind"] == "screen" for event in queries),
                "modelQueries": sum(event["kind"] == "device-model" for event in queries),
                "querySeconds": round(sum(event["querySeconds"] for event in queries), 4),
                "actionSeconds": round(sum(event.get("actionSeconds", 0) for event in group), 4),
                "firstRecordedSeconds": group[0]["seconds"], "lastRecordedSeconds": group[-1]["seconds"],
                "taps": taps})
        invocations.append({"file": path.name, "status": trace["status"],
            "finalPhase": events[-1]["phase"], "actions": events[-1]["actions"],
            "finishedSeconds": events[-1]["seconds"], "error": trace.get("error"), "phases": phases})
    assert [item["status"] for item in invocations] == ["PASSED", "PASSED", "PASSED", "FAILED"]
    failed = invocations[-1]
    location = next(item for item in failed["phases"] if item["phase"] == "location-content")
    assert location["screenQueries"] == 13 and location["taps"][0]["seconds"] == 6.4758
    assert failed["finishedSeconds"] == 36.5346 and "timed out" in failed["error"]

    final = ET.parse(RAW / "failure-ui.xml").getroot()
    nodes = list(final.iter("node"))
    roots = [node.attrib for node in nodes if node.get("resource-id", "").endswith(":id/roots_list")]
    directories = [node.attrib for node in nodes if node.get("resource-id", "").endswith(":id/dir_list")]
    assert len(roots) == 1 and not directories
    final_ui = {"file": "failure-ui.xml", "packages": sorted({node.get("package") for node in nodes}),
        "rootsLists": roots, "directoryLists": directories,
        "downloadsNodes": [node.attrib for node in nodes if node.get("text") == "Downloads"]}
    focus = {}
    for name in ("census-activity.txt", "census-window.txt", "census-documentsui-package.txt"):
        focus[name] = [line.strip() for line in (RAW / name).read_text().splitlines()
            if re.search(r"mCurrentFocus=|mFocusedApp=|topResumedActivity=|versionCode=|versionName=|Package \[", line)]

    snapshots = []
    for stage in ("before", "imported", "restored"):
        name = f"portable-folder-{stage}-workspace"
        recorded = json.loads((RAW / f"{name}.json").read_text())
        snapshots.append({"stage": stage, "tar": f"{name}.tar", "tarSha256": sha(RAW / f"{name}.tar"),
            "recordedProject": recorded["project"], "recordedWindows": recorded["windows"]})
    assert not list(RAW.glob("*reopened*")) and not list(RAW.glob("portable-*-receipt.json"))
    exports = []
    for path in sorted(RAW.glob("*-restart.lsebdl")):
        assert path.stat().st_size == 4899 and path.read_bytes().startswith(b"PK\x03\x04")
        with zipfile.ZipFile(path) as archive:
            assert archive.testzip() is None
            entries = archive.namelist()
            assert len(entries) == len(set(entries))
        exports.append({"file": path.name, "bytes": path.stat().st_size, "sha256": sha(path), "zipCrcsValid": True})
    assert len(exports) == 2
    result = json.loads((RAW / "results.json").read_text())
    assert len(result) == 1 and result[0]["status"] == "failed"
    return {"status": "FAILED_AFTER_EXPORTS_BEFORE_REOPEN", "navigationInvocations": invocations,
        "finalNativeUi": final_ui, "nativeCensusLines": focus, "snapshotInventory": snapshots,
        "partialAppExports": exports, "nativeResult": result,
        "evidenceLimit": "Each trace records query timing, not each hierarchy. The final XML is the retained hierarchy. Snapshot metadata here is the runner's recorded JSON, not a strict storage oracle."}


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--format", choices=("json", "markdown"), default="json")
args = parser.parse_args()
report = census()
if args.format == "json":
    print(json.dumps(report, indent=2))
else:
    print("# Frozen navigation census\n")
    print("| Invocation | Status | Phase | Taps | Screen queries | Model queries | Query seconds | Action seconds | First event s | Last event s |")
    print("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for invocation in report["navigationInvocations"]:
        for phase in invocation["phases"]:
            identifier = invocation["file"].removeprefix("downloads-navigation-").removesuffix(".json")[:8]
            print(f"| {identifier} | {invocation['status']} | {phase['phase']} | {phase['actions']} | {phase['screenQueries']} | {phase['modelQueries']} | {phase['querySeconds']} | {phase['actionSeconds']} | {phase['firstRecordedSeconds']} | {phase['lastRecordedSeconds']} |")
    print("\nQuery counts include failed attempts whose duration was recorded in finally. The final location phase has 12 completed reads followed by one timed-out attempt. The trace does not preserve the content of every read.\n")
    print("Final XML packages: " + ", ".join(report["finalNativeUi"]["packages"]) + ".")
    print(f"\nFinal XML contains {len(report['finalNativeUi']['rootsLists'])} roots_list and {len(report['finalNativeUi']['directoryLists'])} dir_list.\n")
    for index, (name, lines) in enumerate(report["nativeCensusLines"].items()):
        if index:
            print()
        print(f"From `{name}`:\n")
        for line in lines:
            print("- `" + line + "`")
