#!/usr/bin/env python3
"""Verify only the archived Phone005 evidence. No device, build, or live app access."""
from pathlib import Path
import hashlib
import json
import math
import re
import struct
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
RECEIPT = ROOT.parent.parent / "ios-inspector-phone005-pass.json"
TEST = "EditorEndToEndTests/InspectorEndToEndTests/testInvalidWidthPreservesProjectAndValidWidthIsOneUndoAction"
BASE_SHA = "2d0c873e42b8a636a73aec2859a069a036983ae33420de501f36cb82b4b1bc71"
FOUR_SHA = "047088d76cb36f19f98ebc8d277f25e3b0cc6046021603f33ad57454ac44cfcb"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def tree(data):
    return ET.fromstring(data, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True, insert_pis=True)))


def rectangle(text, key):
    match = re.search(re.escape(key) + r"\s*\(([^)]+)\)", text)
    assert match, key
    result = tuple(float(value) for value in match[1].split(","))
    assert len(result) == 4 and all(math.isfinite(value) for value in result)
    return result


def contains(outer, inner):
    x, y, width, height = outer
    a, b, w, h = inner
    return w > 0 and h > 0 and a >= x and b >= y and a + w <= x + width + 1e-6 and b + h <= y + height + 1e-6


def verify():
    receipt = json.loads(RECEIPT.read_text())
    raw_manifest = (ROOT / "manifest.json").read_bytes()
    assert digest(raw_manifest) == receipt["evidenceManifestSHA256"]
    manifest = json.loads(raw_manifest)
    data = {}
    for role, entry in manifest["files"].items():
        path = ROOT / entry["file"]
        assert path.parent == ROOT and not path.is_symlink(), role
        raw = path.read_bytes()
        assert len(raw) == entry["bytes"] and digest(raw) == entry["sha256"], role
        data[role] = raw

    results = json.loads(data["results"])
    run = json.loads(data["run"])
    postflight = json.loads(data["postflight"])
    app_manifest = json.loads(data["appArtifact"])
    assert receipt["status"] == results["status"] == postflight["status"] == "PASS"
    assert receipt["nativeExecutionGate"] == results["native_execution_gate"] == "NATIVE_PASS"
    assert receipt["test"] == TEST and receipt["durationSeconds"] == 173.818
    assert results["status"] == "PASS" and results["native_execution_gate"] == "NATIVE_PASS"
    assert results["qualification_gate"] == receipt["wholeSuiteQualification"] == "PARTIAL"
    assert results["selected_tests"] == results["execution"]["selection"] == [TEST]
    assert results["execution"]["exit_code"] == receipt["wrapperExitCode"] == 0
    assert results["alpha"] is False and results["physical_device"] is False
    assert receipt["device"]["udid"] == results["simulator"] == run["simulatorUDID"]
    assert receipt["sourceAppCommit"] == results["declared_baseline_sha"] == app_manifest["source_sha"]
    assert digest(data["appArtifact"]) == receipt["appArtifactManifestSHA256"]
    assert results["run_id"] == run["run_id"] == receipt["runID"] == "0f173a9f1502"
    assert results["configuration"] == run
    assert digest(data["staged"]) == run["inspector"]["fixtureSHA256"]
    assert digest(data["testSource"]) == results["harness_sources"]["InspectorEndToEndTests.swift"] == receipt["inspectorHarnessSHA256"]
    assert 'field.value as? String == ""' in data["testSource"].decode()
    assert results["main_app_installation"]["status"] == "REUSED_IDENTICAL"
    identities = [results["artifact"], results["main_app_installation"]["before"],
                  results["main_app_installation"]["installed"], postflight["artifact"],
                  postflight["installed_after"], app_manifest["artifact"]]
    for identity in identities:
        assert identity["bundle_tree_sha256"] == receipt["appBundleTreeSHA256"]
        assert identity["executable_sha256"] == receipt["appExecutableSHA256"]

    for role in ["before", "invalid", "undo"]:
        assert len(data[role]) == 481 and digest(data[role]) == BASE_SHA
    assert data["before"] == data["invalid"] == data["undo"]
    assert len(data["width4"]) == 481 and digest(data["width4"]) == FOUR_SHA
    changes = [index for index, pair in enumerate(zip(data["before"], data["width4"])) if pair[0] != pair[1]]
    assert changes == [387]
    assert data["before"][387:388] == b"1" and data["width4"][387:388] == b"4"
    original = tree(data["before"])
    widths = original.findall("circuit/comp/a[@name='width']")
    assert len(widths) == 1 and widths[0].get("val") == "1"
    assert len(original.findall("circuit")) == 1 and len(original.findall("circuit/comp")) == 1
    assert original.findall("circuit/wire") == []
    assert original.find("main").get("name") == run["titles"]["inspector"]
    assert original.find("circuit/comp/a[@name='label']").get("val") == run["inspector"]["label"]
    widths[0].set("val", "4")
    assert ET.tostring(original) == ET.tostring(tree(data["width4"]))
    for role in ["before", "invalid", "width4", "undo"]:
        independent = postflight["xml"][role]
        assert independent["sha256"] == digest(data[role]) and independent["bytes"] == len(data[role])
        assert independent["file"] == Path(manifest["files"][role]["sourceRelativePath"]).name

    root_values = [("root0Before", "1"), ("root0Empty", None), ("root0Typed", "0"),
                   ("root4Before", "1"), ("root4Empty", None), ("root4Typed", "4")]
    for role, expected in root_values:
        text = data[role].decode()
        assert text.startswith("Attributes: TextView, ") and text.endswith(", Keyboard Focused")
        value = re.search(r", value: ([^,]*), Keyboard Focused$", text)
        assert (value[1] if value else None) == expected
        if expected is None:
            assert ", value:" not in text  # Omitted in the dump; exact empty String was asserted at runtime.

    for role, expected_gap in [("apply0", 11.6666259765625), ("apply4", 25.6666259765625)]:
        text = data[role].decode()
        frame = rectangle(text, "frame=")
        assert frame == rectangle(text, "visible=") and contains(rectangle(text, "app="), frame)
        input_view = rectangle(text, '"inputView":')
        assert frame[2] >= 44 and frame[3] >= 44 and frame[1] + frame[3] < input_view[1]
        assert math.isclose(input_view[1] - frame[1] - frame[3], expected_gap, abs_tol=1e-6)
    for role in ["delete0", "delete4"]:
        text = data[role].decode()
        assert "fieldRole=52, fieldID=," in text
        assert contains(rectangle(text, "keyboard="), rectangle(text, "deleteFrame="))
    invalid_tree = data["invalidTree"].decode()
    assert "label: 'Invalid value for width: 0.'" in invalid_tree
    assert invalid_tree.count("label: 'Apply', Disabled") == 1
    assert "value: 0, Keyboard Focused" in invalid_tree
    assert "Key," in invalid_tree and "label: 'Delete'" in invalid_tree
    png = data["invalidPNG"]
    assert png.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", png[16:24]) == (1170, 2532)
    log = data["log"].decode()
    assert "passed (173.818 seconds)." in log and "Executed 1 test, with 0 failures" in log
    assert len(re.findall(r'\bTap "Delete" Key\s*$', log, re.M)) == 2
    assert receipt["selectedTests"] == 1 and receipt["failedTests"] == 0
    return {"offlineReceiptVerification": "PASS", "rawFiles": len(data), "uiXMLBytes": 481,
            "widthOnlyByteOffset": 387, "priorNativeCase": "PASS", "wholeSuiteQualification": "PARTIAL",
            "nativeRerun": "NOT_RUN", "scope": "Archived selected Phone005 case only; no live binary or device access"}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
