#!/usr/bin/env python3
"""Replay frozen Android editing evidence without Android, Git or network access."""

import base64
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
SOURCE = "da4ffddb9e44e2686695d37e3e24a507fd6111c8"
APP_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
DRIVER_SHA = "99b35ef7f4ceb87761092bf720334b2863905e26d0c87407212a77112979679c"
SNAPSHOT_MANIFEST_SHA = "a84dc7c0613dc541ec11879b18a0c86067b3200c26b9cb962a96a7621b58d307"
PACKAGE = "thalesmms.logisim.mobile.e2e"
APKS = {
    PACKAGE: "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    PACKAGE + ".test": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    "thalesmms.logisim.mobile.snapshot.e2e.test": "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
}
GROUPS = ["editing-create-properties", "editing-wires-truth-table", "editing-selection-history", "editing-viewport-touch-parity"]
CHECKPOINTS = {
    "empty": "empty", "components": "components", "property-north": "north",
    "property-undo": "components", "property-redo": "north", "property-restored": "components",
    "wired-not": "wired", "wire-removed": "wire-removed", "wire-undo": "wired",
    "wire-redo": "wire-removed", "wire-restored": "wired", "pasted": "pasted",
    "paste-undo": "wired", "paste-redo": "pasted", "paste-restored": "wired",
    "deleted": "empty", "delete-undo": "wired", "delete-redo": "empty",
    "delete-restored": "wired", "viewport-unchanged": "wired", "touch-undo": "wired", "final": "wired",
}
PREFIX = "focus-snapshot-b4965009830c4b1ebaa26418682b77e3-"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def safe_files(folder):
    require(folder.is_dir() and not folder.is_symlink(), f"Missing or linked directory: {folder.name}")
    paths = list(folder.rglob("*"))
    require(not any(p.is_symlink() for p in paths), "Evidence must not contain links")
    return [p for p in paths if p.is_file()]


def authenticate(base):
    require(base.is_dir() and not base.is_symlink(), "The packet must be a physical directory")
    inv = read_json(base / "inventory.json")
    actual = {str(p.relative_to(base)): p for d in ("raw", "control", "oracle-snapshot") for p in safe_files(base / d)}
    require(set(actual) == set(inv["files"]) and len(actual) == inv["fileCount"] == 494, "Evidence inventory differs")
    require(sum(p.stat().st_size for p in actual.values()) == inv["bytes"], "Evidence byte count differs")
    for name, path in actual.items():
        require(inv["files"][name] == {"bytes": path.stat().st_size, "sha256": digest(path)}, f"Evidence changed: {name}")
    snapshot = base / "oracle-snapshot"
    require(digest(snapshot / "manifest.json") == SNAPSHOT_MANIFEST_SHA, "Historical source manifest changed")
    manifest = read_json(snapshot / "manifest.json")
    require(manifest["source"] == SOURCE, "Wrong historical source pin")
    require({str(p.relative_to(snapshot)) for p in safe_files(snapshot)} == set(manifest["files"]) | {"manifest.json"}, "Historical source inventory differs")
    for name, expected in manifest["files"].items():
        p = snapshot / name
        require({"bytes": p.stat().st_size, "sha256": digest(p)} == expected, f"Historical source changed: {name}")
    return snapshot, manifest


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def envelope(project):
    """Check metadata and library ownership omitted by the historical component oracle."""
    require(project.attrib == {"source": "3.8.0", "version": "1.0"}, "Project header changed")
    require(all(n.tag in {"lib", "options", "main", "circuit"} for n in project), "Unexpected project element")
    require(len(project.findall("main")) == len(project.findall("options")) == len(project.findall("circuit")) == 1, "Ambiguous project envelope")
    require(project.find("main").attrib == {"name": "Sem título"}, "Main circuit changed")
    circuit = project.find("circuit")
    require(circuit.attrib == {"name": "Sem título", "id": "Sem título"}, "Circuit metadata changed")
    require(all(n.tag in {"a", "comp", "wire"} for n in circuit), "Unexpected circuit element")
    require([n.attrib for n in circuit.findall("a")] == [{"name": "simulationFrequency", "val": "4.0"}], "Circuit options changed")
    expected_options = {"simlimit": "256", "memoryStartupUnknown": "false", "gateUndefined": "ignore", "tickFrequencyHz": "4.0", "autoPropagating": "true"}
    options = project.find("options")
    require(not options.attrib and len(options) == 5 and all(n.tag == "a" and set(n.attrib) == {"name", "val"} and len(n) == 0 for n in options), "Malformed project options")
    require({n.get("name"): n.get("val") for n in options} == expected_options, "Project options changed")
    components = circuit.findall("comp")
    expected_libs = [{"desc": "#Wiring", "name": "0"}, {"desc": "#Gates", "name": "1"}] if components else []
    require([n.attrib for n in project.findall("lib")] == expected_libs, "Library ownership changed")
    ids = [n.get("logisim-mobile-id") for n in components]
    require(all(ids) and len(set(ids)) == len(ids), "Component identities are missing or duplicated")
    for n in components:
        require(set(n.attrib) == {"loc", "logisim-mobile-id", "name", "lib"}, "Unexpected component XML attributes")
        require(n.get("lib") == ("0" if n.get("name") == "Pin" else "1"), "Component library changed")
        require(all(a.tag == "a" and set(a.attrib) == {"name", "val"} and len(a) == 0 for a in n), "Unexpected component child")
    require(all(set(n.attrib) == {"from", "to"} and len(n) == 0 for n in circuit.findall("wire")), "Unexpected wire metadata")


def rectangle(node):
    m = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    require(m is not None, "Missing retained node geometry")
    return tuple(map(int, m.groups()))


def tagged(root, key):
    values = [n for n in root.iter("node") if n.get("resource-id") == key and n.get("package") == PACKAGE]
    require(len(values) == 1, f"Expected one retained {key}")
    return values[0]


def verify(base=BASE):
    base = Path(base)
    snapshot, source_manifest = authenticate(base)
    # These modules contain only definitions. Authentication happens before import.
    oracle = load_module("historical_editor_contract_r3", snapshot / "scripts/user_scenario_contract.py")
    previous = sys.modules.get("user_scenario_contract")
    sys.modules["user_scenario_contract"] = oracle
    try:
        editing = load_module("historical_android_editing_r3", snapshot / "scripts/android_editing_scenarios.py")
    finally:
        if previous is None:
            del sys.modules["user_scenario_contract"]
        else:
            sys.modules["user_scenario_contract"] = previous
    raw, control = base / "raw", base / "control"
    manifest, unit = read_json(raw / "manifest.json"), read_json(control / "unit.json")
    require(manifest["gitHead"] == APP_SOURCE and manifest["harnessGitHead"] == SOURCE and not manifest["sourceChanges"], "Producer identity differs")
    require(manifest["scenario"] == unit["scenario"] == "editing" and unit["source"] == SOURCE, "Wrong selected scenario")
    require(manifest["serial"] == unit["serial"] == "emulator-5558" and manifest["package"] == unit["package"] == PACKAGE, "Wrong Android cohort")
    require(unit["status"] == "PASS" and unit["nativeInvocations"] == 1 and unit["nativeExitCode"] == 0 and unit["preflightPassed"], "The original native unit did not pass once")
    require(unit["settingsDifferences"] == {} and unit["anrChanged"] is False and unit["cleanup"]["pidAfter"] == "", "Postflight or cleanup differs")
    require(unit["sourceFilesBefore"] == unit["sourceFilesAfter"], "Source changed during Native")
    for name, expected in unit["sourceFilesBefore"].items():
        require(source_manifest["files"][name] == expected, f"Pre/post source differs from historical pin: {name}")
    for name, sha in manifest["scenarioSources"].items():
        require(digest(raw / ("source-" + name)) == sha == source_manifest["files"]["scripts/" + name]["sha256"], f"Retained executor changed: {name}")
    for phase in ("installedArtifactsBefore", "installedArtifactsAfter"):
        require({x["package"]: x["sha256"] for x in manifest[phase]} == APKS, "Installed APK triplet differs")
    for phase in ("before", "after"):
        values = unit[phase]
        require(read_json(control / phase / "inventory.json") == values, "Controller inventory differs from its raw census")
        require({x["package"]: x["sha256"] for x in values["installed"]} == APKS, "Controller APK readback differs")
        for key, value in values.items():
            if isinstance(value, str):
                require((control / phase / (key + ".stdout")).read_text().strip() == value and not (control / phase / (key + ".stderr")).read_bytes(), f"Controller getter differs: {phase}/{key}")
        require(values["accessibility-enabled"] == "0" and values["enabled-services"] == "null" and "touchExplorationEnabled=false" in values["accessibility"], "Reader state differs")
    stable_settings = ("sdk", "fingerprint", "locale", "persist-locale", "product-locale", "wm-size", "wm-density", "font-scale", "accelerometer-rotation", "user-rotation", "accessibility-enabled", "enabled-services", "last-anr")
    require(all(unit["before"][k] == unit["after"][k] for k in stable_settings), "Raw settings or ANR readback changed")
    require(manifest["appSha256"] == APKS[PACKAGE] and manifest["testSha256"] == APKS[PACKAGE + ".test"] and manifest["snapshotSha256"] == APKS["thalesmms.logisim.mobile.snapshot.e2e.test"], "Supplied APK triplet differs")
    results = read_json(raw / "results.json")
    require([r["scenario"] for r in results] == GROUPS and all(r["status"] == "passed" and r["seconds"] > 0 for r in results), "The four original groups did not all pass")
    require(digest(control / "driver.py") == unit["driverSHA256"] == DRIVER_SHA, "Controller source differs")
    contract_path = snapshot / "fixtures/user-scenarios/editor-actions-v1.json"
    require(digest(contract_path) == manifest["scenarioContractSha256"], "Wrong scenario contract")
    contract = oracle.load_contract(contract_path)
    observed = {}
    for stem, checkpoint in CHECKPOINTS.items():
        path = raw / ("editing-" + stem + ".circ")
        observed[stem] = oracle.assert_document(path.read_bytes(), contract, checkpoint)
    baseline = ET.fromstring((raw / "editing-viewport-unchanged.circ").read_bytes())
    inserted = [ET.fromstring((raw / ("editing-" + name + ".circ")).read_bytes()) for name in ("touch-or", "accessible-or")]
    positions = [editing._assert_or_insertion(p, baseline) for p in inserted]
    require(positions[0] == positions[1] and editing._components(inserted[0]) == editing._components(inserted[1]) and editing._wires(inserted[0]) == editing._wires(inserted[1]), "Touch and Explorer insertion differ")
    expected_xmls = {"editing-" + n + ".circ" for n in CHECKPOINTS} | {"editing-touch-or.circ", "editing-accessible-or.circ", "last-document.circ"}
    require({p.name for p in raw.glob("*.circ")} == expected_xmls, "The complete checkpoint inventory differs")
    for name in expected_xmls:
        envelope(ET.fromstring((raw / name).read_bytes()))
    require((raw / "last-document.circ").read_bytes() == (raw / "editing-final.circ").read_bytes() == (raw / "editing-wired-not.circ").read_bytes(), "The final full document was not restored")
    captures = sorted(raw.glob("focus-snapshot-*.json"))
    require(len(captures) == 82, "SDK capture inventory differs")
    for p in captures:
        d = read_json(p)
        require(d["status"] == "complete" and d["snapshotCaptureState"] == "COMPLETE" and d["expectedPackage"] == PACKAGE, "SDK capture is incomplete or belongs to another app")
        require(d["appPidBefore"] == d["appPidAfter"] and d["appPidBefore"], "SDK sampled PID identity differs")
        tree = ET.fromstring((raw / d["xml"]).read_bytes())
        encoded = ET.fromstring(base64.b64decode(d["bundle"]["snapshotXmlBase64"], validate=True))
        require(ET.tostring(tree) == ET.tostring(encoded), "SDK XML differs from its returned Bundle")
        require(tree.get("snapshot-nonce") == d["nonce"] == d["snapshotNonce"] and int(d["snapshotTraversalNodeCount"]) == len(list(tree.iter("node"))), "SDK nonce or node census differs")
    truth = []
    for number, value, states in [(64, "0", ["Input. High, 1.", "NOT. Low, 0.", "Output. Low, 0."]), (68, "1", ["Input. Low, 0.", "NOT. High, 1.", "Output. High, 1."])]:
        root = ET.fromstring((raw / (PREFIX + str(number) + ".xml")).read_bytes())
        canvases = [n for n in root.iter("node") if n.get("content-desc", "").startswith("Circuit area.")]
        require(len(canvases) == 1 and re.search(r"\bE2E_OUT: " + value + r"(?=,|\.|$)", canvases[0].get("content-desc", "")), "Retained Canvas truth probe differs")
        require([tagged(root, "canvas-component:c" + str(i)).get("state-desc") for i in (1, 2, 3)] == states, "Retained NOT component states differ")
        truth.append({"capture": number, "output": value})
    journals = sorted(raw.glob("editing-search-reveal-*.json"))
    require(len(journals) == 10, "Search journal inventory differs")
    movements = Counter()
    for p in journals:
        journal = read_json(p)
        records = journal["records"]
        require(journal["status"] == "COMPLETE" and 1 <= len(records) <= 18 and records[-1].get("reached") is True, "Search did not reach its field")
        for entry in records:
            tree = ET.fromstring((raw / entry["tree"]).read_bytes())
            viewport = tagged(tree, "a11y-circuit-list")
            require(tuple(entry["viewport"]) == rectangle(viewport), "Recorded viewport differs from the retained tree")
            if "request" in entry:
                motion = editing._search_backward_motion(tree, viewport)
                request = entry["request"]
                require(tuple(request["start"]) == motion[:2] and tuple(request["end"]) == motion[2:4] and request["durationMs"] == motion[4] and request["commandReturned"] is True, "Search motion differs from the bounded source")
                movements[journal["query"]] += 1
        final = ET.fromstring((raw / records[-1]["tree"]).read_bytes())
        field, viewport = rectangle(tagged(final, "a11y-circuit-search")), rectangle(tagged(final, "a11y-circuit-list"))
        window = rectangle(next(final.iter("node")))
        require(all(frame[0] <= field[0] < field[2] <= frame[2] and frame[1] <= field[1] < field[3] <= frame[3] for frame in (viewport, window)), "Final Search field is clipped")
    require(movements == Counter({"E2E_NOT": 1, "E2E_OUT": 6}), "Observed Search requests differ")
    requests = [json.loads(line) for line in (control / "adb-command-requests.jsonl").read_text().splitlines()]
    require(len(requests) == 1813 and all(r["argv"][1:3] == ["-s", "emulator-5558"] for r in requests), "Controller request census differs")
    require(sum("prepareCopy" in r["argv"] for r in requests) == 24, "Copy XML preparation request count differs")
    return {"status": "PASS", "nativeReplay": "NOT_RUN", "originalNativeInvocations": 1, "groups": 4,
            "literalCheckpoints": 22, "orInsertionCheckpoints": 2, "lastDocumentRestored": True,
            "orInsertionWorldPoint": positions[0], "completeSdkCaptures": 82, "retainedTruthRows": truth,
            "searchJournals": 10, "searchGestureRequests": dict(movements), "inventoryFiles": 494,
            "scope": "One original Android editing unit only. No Files workflow or full issue35 completion."}


if __name__ == "__main__":
    try:
        print(json.dumps(verify(), indent=2))
    except (AssertionError, KeyError, ValueError, OSError, ET.ParseError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}))
        sys.exit(1)
