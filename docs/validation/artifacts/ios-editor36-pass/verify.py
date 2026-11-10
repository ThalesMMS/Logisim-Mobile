#!/usr/bin/env python3
"""Recheck the archived selected Editor36 case without live app or device access."""
from collections import Counter
from pathlib import Path
import hashlib
import json
import math
import re
import struct
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
RECEIPT = ROOT.parent.parent / "ios-editor36-pass.json"
TEST = "EditorEndToEndTests/EditorEndToEndTests/testEditingConnectionsHistoryAndViewport"
CONTRACT_SHA = "9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9"
SNAPSHOT_SHA = {
    "empty": "c9ae3bb10cf2ea2b72180e899ee0c18c991ae20f26684fad55756eaab78dd481",
    "components": "049e8daa354dd0ed76a918b6358845c46ec23fbc7082019e3926bb2380d4d9b9",
    "north": "5acc9b6d2200c8b6084fd825f9037521cae4fc155ecf0917ef791af4ad06582e",
    "wired": "2c60986d3c68d32e41c929dc3eaa75390c1c871fd937666a1972e90751dae35b",
    "wire-removed": "4008e0f30e62cddd078fc049b928f2c16b9f0b1429fb62ac4eb9c8dc79f54dfe",
    "pasted": "3a8f05d3e2833e7b736c92262a752a027c7ffc0f951cfed49867584e6aba5779",
}
ORIGINAL_IDS = {(100, 100): "c1", (200, 100): "c2", (300, 100): "c3"}
COPIED_IDS = {(120, 120): "c4", (220, 120): "c5", (320, 120): "c6"}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def point(text):
    match = re.fullmatch(r"\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)", text or "")
    assert match, text
    return [int(match[1]), int(match[2])]


def document(raw):
    assert b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper()
    project = ET.fromstring(raw)
    assert project.tag == "project"
    circuits = project.findall("circuit")
    names = [circuit.get("name") for circuit in circuits]
    assert None not in names and len(set(names)) == len(names)
    assert project.find("main").get("name") in names
    components, wires, identities = [], [], {}
    for circuit in circuits:
        for component in circuit.findall("comp"):
            attributes = {}
            for attribute in component.findall("a"):
                name = attribute.get("name")
                assert name is not None and name not in attributes and "val" in attribute.attrib
                attributes[name] = attribute.get("val")
            location = point(component.get("loc"))
            identity = component.get("logisim-mobile-id")
            assert identity and identity not in identities.values() and tuple(location) not in identities
            identities[tuple(location)] = identity
            components.append({"name": component.get("name"), "location": location, "attributes": attributes})
        for wire in circuit.findall("wire"):
            wires.append([point(wire.get("from")), point(wire.get("to"))])
    return {"circuitCount": len(circuits), "components": components, "wires": wires}, identities


def assert_snapshot(raw, expected, checkpoint):
    observed, identities = document(raw)
    assert observed["circuitCount"] == expected["circuitCount"] == 1
    for key in ["components", "wires"]:
        assert Counter(map(canonical, observed[key])) == Counter(map(canonical, expected[key])), (checkpoint, key)
    expected_ids = {} if checkpoint == "empty" else ORIGINAL_IDS
    if checkpoint == "pasted":
        expected_ids = ORIGINAL_IDS | COPIED_IDS
    assert identities == expected_ids, (checkpoint, identities)

    # Literal port locations for the two NOT chains; do not infer ports from production code.
    nets = Counter(frozenset(tuple(end) for end in wire) for wire in observed["wires"])
    literal_nets = []
    if checkpoint in ["wired", "pasted"]:
        literal_nets.append(frozenset([(100, 100), (170, 100)]))
    if checkpoint in ["wired", "pasted", "wire-removed"]:
        literal_nets.append(frozenset([(200, 100), (300, 100)]))
    if checkpoint == "pasted":
        literal_nets += [frozenset([(120, 120), (190, 120)]), frozenset([(220, 120), (320, 120)])]
    assert nets == Counter(literal_nets), (checkpoint, "complete wire connectivity")
    return observed


def verify():
    receipt = json.loads(RECEIPT.read_text())
    manifest_raw = (ROOT / "manifest.json").read_bytes()
    assert digest(manifest_raw) == receipt["evidenceManifestSHA256"]
    manifest = json.loads(manifest_raw)
    data = {}
    for name, entry in manifest["files"].items():
        path = ROOT / name
        assert not Path(name).is_absolute() and ".." not in Path(name).parts
        assert all(not ancestor.is_symlink() for ancestor in [path, *path.parents] if ancestor != ROOT.parent)
        raw = path.read_bytes()
        assert len(raw) == entry["bytes"] and digest(raw) == entry["sha256"], name
        data[name] = raw
    assert len(data) == receipt["rawFileCount"] == 131
    assert sum(map(len, data.values())) == receipt["rawFileBytes"]

    results = json.loads(data["results.json"])
    run = json.loads(data["harness/Run.json"])
    postflight = json.loads(data["independent-postflight.json"])
    app_manifest = json.loads(data["app-artifact.json"])
    assert receipt["status"] == results["status"] == postflight["status"] == "PASS"
    assert receipt["nativeExecutionGate"] == results["native_execution_gate"] == "NATIVE_PASS"
    assert receipt["wholeSuiteQualification"] == results["qualification_gate"] == postflight["suite_qualification"] == "PARTIAL"
    assert receipt["test"] == TEST and results["selected_tests"] == results["execution"]["selection"] == [TEST]
    assert results["execution"]["status"] == "PASS" and results["execution"]["exit_code"] == receipt["wrapperExitCode"] == 0
    assert receipt["durationSeconds"] == postflight["native_case_duration_seconds"] == 618.164
    assert receipt["selectedTests"] == 1 and receipt["failedTests"] == 0
    assert results["alpha"] is False and results["physical_device"] is False
    assert receipt["device"]["udid"] == results["simulator"] == run["simulatorUDID"]
    assert receipt["device"] == {"name": results["device"]["name"], "model": results["device"]["deviceTypeIdentifier"],
                                  "udid": results["simulator"], "runtime": results["device"]["runtime"], "physicalDevice": False}
    assert receipt["runID"] == results["run_id"] == run["run_id"] == "334469d00ee8"
    assert results["configuration"] == run and run["contract"] == "editor-actions-v1"
    assert receipt["sourceAppCommit"] == results["declared_baseline_sha"] == app_manifest["source_sha"]
    assert receipt["validationCommit"] == app_manifest["test_validation_source"]
    assert digest(data["app-artifact.json"]) == receipt["appArtifactManifestSHA256"]
    identities = [results["artifact"], results["main_app_installation"]["before"],
                  results["main_app_installation"]["installed"], postflight["installed_after"], app_manifest["artifact"]]
    assert results["main_app_installation"]["status"] == "REUSED_IDENTICAL"
    for identity in identities:
        assert identity["bundle_tree_sha256"] == receipt["appBundleTreeSHA256"]
        assert identity["executable_sha256"] == receipt["appExecutableSHA256"]
    for name in ["EditorEndToEndTests.swift", "NativeUIHarness.swift", "NativeTextReplacement.swift",
                 "ObservedProjectXML.swift", "ClipboardReceiverUI.swift"]:
        assert digest(data["harness/" + name]) == results["harness_sources"][name]
    assert digest(data["harness/Catalog.json"]) == results["catalog"]["sha256"]

    contract_raw = data["harness/editor-actions-v1.json"]
    assert digest(contract_raw) == CONTRACT_SHA == receipt["contractSHA256"] == postflight["contract_sha256"]
    contract = json.loads(contract_raw)
    assert contract["schemaVersion"] == 1 and contract["provenance"]["kind"] == "literal-expectations"
    scenario, = contract["scenarios"]
    assert scenario["id"] == "editing-connections-history-viewport"
    steps = scenario["steps"]
    assert len(steps) == receipt["completedSteps"] == 62
    assert postflight["completed_step_attachments"] == list(range(1, 63))

    attachments, = json.loads(data["attachments/manifest.json"])
    assert attachments["testIdentifier"] == "EditorEndToEndTests/testEditingConnectionsHistoryAndViewport()"
    assert attachments["testIdentifierURL"].endswith("/EditorEndToEndTests/EditorEndToEndTests/testEditingConnectionsHistoryAndViewport")
    entries = attachments["attachments"]
    assert len(entries) == 117 and len({entry["exportedFileName"] for entry in entries}) == 117
    assert all(not entry["isAssociatedWithFailure"] for entry in entries)
    assert set("attachments/" + entry["exportedFileName"] for entry in entries) == {
        name for name in data if name.startswith("attachments/") and name != "attachments/manifest.json"}

    def attachment(prefix):
        entry, = [entry for entry in entries if entry["suggestedHumanReadableName"].startswith(prefix + "_0_")]
        assert entry["deviceId"] == receipt["device"]["udid"]
        return entry, data["attachments/" + entry["exportedFileName"]]

    log = data["test.log"].decode()
    matches = list(re.finditer(r"^\s+t =\s+[0-9.]+s contract step ([0-9]+): (\w+)\s*$", log, re.M))
    assert [(int(match[1]), match[2]) for match in matches] == [
        (index, step["action"]) for index, step in enumerate(steps, 1)]
    blocks = {int(match[1]): log[match.end():matches[index + 1].start() if index + 1 < len(matches) else len(log)]
              for index, match in enumerate(matches)}
    timestamps = []
    snapshots = []
    for number, step in enumerate(steps, 1):
        entry, raw = attachment("contract-step-" + str(number))
        text = raw.decode()
        match = re.fullmatch(r"step=(\d+), action=(\w+), elapsed=([0-9.]+), status=passed", text)
        assert match and int(match[1]) == number and match[2] == step["action"]
        assert math.isfinite(float(match[3])) and float(match[3]) >= 0
        assert "Added attachment named 'contract-step-" + str(number) + "'" in blocks[number]
        timestamps.append(entry["timestamp"])
        if step["action"] == "assertDocument":
            export = len(snapshots) + 1
            xml_entry, raw_xml = attachment("observed-editor-export-" + str(export))
            checkpoint = step["checkpoint"]
            assert digest(raw_xml) == SNAPSHOT_SHA[checkpoint]
            assert_snapshot(raw_xml, contract["snapshots"][checkpoint], checkpoint)
            assert "Added attachment named 'observed-editor-export-" + str(export) + ".circ'" in blocks[number]
            snapshots.append({"step": number, "exportIndex": export, "checkpoint": checkpoint,
                              "file": "attachments/" + xml_entry["exportedFileName"],
                              "bytes": len(raw_xml), "sha256": digest(raw_xml)})
    assert timestamps == sorted(timestamps)
    assert snapshots == receipt["documentSnapshots"] and len(snapshots) == 20

    # History returns to the entire literal document, not merely the same counts.
    expected_history = {10: "components", 12: "north", 14: "components", 26: "wired", 28: "wire-removed",
                        30: "wired", 37: "wired", 39: "pasted", 41: "wired", 46: "wired", 48: "empty", 50: "wired"}
    checkpoint_by_step = {entry["step"]: entry["checkpoint"] for entry in snapshots}
    for number, checkpoint in expected_history.items():
        assert steps[number - 1]["action"] in ["undo", "redo"]
        assert checkpoint_by_step[number + 1] == checkpoint

    assert receipt["nativeCopyIdentities"] == {"E2E_IN": ["c1", "c4"], "E2E_NOT": ["c2", "c5"], "E2E_OUT": ["c3", "c6"]}
    for label, expected_ids in receipt["nativeCopyIdentities"].items():
        _, raw = attachment("distinct-" + label + "-native-identities")
        observed = {}
        for line in raw.decode().splitlines():
            match = re.fullmatch(r"(c[0-9]+): " + re.escape(label) + r" \((-?[0-9]+), (-?[0-9]+)\), (c[0-9]+)", line)
            assert match and match[1] == match[4] and match[1] not in observed
            observed[match[1]] = (int(match[2]), int(match[3]))
        assert sorted(observed) == expected_ids
        assert set(observed.values()) == {location for location, identity in (ORIGINAL_IDS | COPIED_IDS).items()
                                          if identity in expected_ids}

    catalog = json.loads(data["harness/Catalog.json"])
    assert receipt["outputs"] == [{"step": number, "label": "E2E_OUT", "value": value,
                                    "source": "test.log native canvas label within that step"}
                                   for number, value in [(19, "1"), (21, "0"), (23, "1")]]
    for number, expected in [(19, "1"), (21, "0"), (23, "1")]:
        assert steps[number - 1] == {"action": "assertOutput", "label": "E2E_OUT", "value": expected}
        observed = re.findall(r'Find the "(Circuit area\.[^"\n]+)" Other', blocks[number])
        assert observed and set(observed) == {
            catalog["a11y_canvas"]["en"].replace("{outputs}", "E2E_OUT: " + expected)}
    assert receipt["zoomPercentByStep"] == {"53": 179, "55": 224, "57": 179, "60": 400}
    for number, expected in [(53, 179), (55, 224), (57, 179), (60, 400)]:
        observed = {int(value) for value in re.findall(r'Find the "Fit circuit, ([0-9]+)%" Button', blocks[number])}
        assert observed == {expected} and receipt["zoomPercentByStep"][str(number)] == expected
    assert 224 > 179 and abs(179 - 179) <= steps[56]["tolerancePercent"] and 400 > 179
    assert steps[60]["action"] == "fitCircuit" and snapshots[-1]["checkpoint"] == "wired"

    _, native_tree = attachment("native-tree")
    tree_text = native_tree.decode()
    assert "label: 'Circuit area. E2E_OUT: 1." in tree_text
    assert re.search(r"identifier: 'canvas-component:c2', label: 'E2E_NOT', value: Selected\.[^\n]+, Selected$", tree_text, re.M)
    assert "identifier: 'canvas-component:c3', label: 'E2E_OUT', value: Output. High, 1." in tree_text
    assert "label: 'Fit circuit, 350%'" in tree_text and receipt["finalZoomPercent"] == 350
    _, orientation = attachment("observed-orientation")
    assert orientation.decode() == "requested=1, actual app frame=(0.0, 0.0, 834.0, 1210.0)"
    for name in ["final-screen", "editing-final-wired-document"]:
        _, png = attachment(name)
        assert png.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", png[16:24]) == (1668, 2420)
    assert "passed (618.164 seconds)." in log and "Executed 1 test, with 0 failures" in log
    return {"offlineReceiptVerification": "PASS", "rawFiles": len(data), "steps": 62, "xmlSnapshots": 20,
            "literalSnapshotTypes": sorted(SNAPSHOT_SHA), "nativeOutputs": ["1", "0", "1"],
            "nativeZoomPercent": [179, 224, 179, 400], "priorNativeCase": "PASS",
            "wholeSuiteQualification": "PARTIAL", "nativeRerun": "NOT_RUN"}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
