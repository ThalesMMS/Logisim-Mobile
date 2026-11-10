"""Verify preserved evidence only. No Android/device process is invoked."""

import hashlib
import json
import importlib.util
import xml.etree.ElementTree as ET
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parent


def load(name):
    return json.loads((ROOT / name).read_text())


def verify():
    archive = load("archive.json")
    for item in archive["files"]:
        path = ROOT / item["path"]
        content = path.read_bytes()
        assert len(content) == item["bytes"] and hashlib.sha256(content).hexdigest() == item["sha256"], item["path"]
    preflight, manifest, cleanup = load("preflight.json"), load("run/manifest.json"), load("cleanup.json")
    assert preflight["status"] == "PASS" and preflight["catalogMessages"] == 2497
    assert preflight["serial"] == manifest["serial"] == "emulator-5558"
    assert manifest["gitHead"] == preflight["appProducer"] == "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
    assert manifest["harnessGitHead"] == preflight["harness"] == "1c1c2721aca4c8c1d4a11c15e13721a4e09c0c08"
    assert preflight["helperSource"] == "5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c"
    assert manifest["sourceChanges"] == []
    expected = {
        "thalesmms.logisim.mobile.e2e": "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
        "thalesmms.logisim.mobile.e2e.test": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
        "thalesmms.logisim.mobile.snapshot.e2e.test": "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
    }
    for installed in (manifest["installedArtifactsBefore"], cleanup["installedAfter"]):
        assert len(installed) == 3 and {entry["package"]: entry["sha256"] for entry in installed} == expected
    for key in ("wm_size", "wm_density", "system", "services"):
        before = load("inventory-before-ui.json")[key]
        assert load("inventory-after-run.json")[key] == load("inventory-final.json")[key] == before, key
    assert cleanup["unitAttempts"] == 1 and cleanup["nativeExitCode"] == 1 and cleanup["nativeStatus"] == "FAIL"
    assert cleanup["restorationStatus"] == "PASS" and cleanup["finalDifferences"] == cleanup["differencesBeforeOuterCleanup"] == {}
    assert cleanup["forceStoppedPackage"] == "thalesmms.logisim.mobile.e2e"
    records = load("run/layout12-observations.json")["records"]
    assert [record["name"] for record in records] == ["initial-configuration", "window-before-layout", "applied-configuration"]
    assert records[1]["status"] == "CAPTURED" and records[1]["window"]["insetsStatus"] == "AVAILABLE"
    assert records[2]["expectedDisplayDp"] == [320, 568] and records[2]["fontScale"] == 1.0
    assert records[2]["displayMode"] == "simulated-small-phone"
    error = load("run/results.json")[0]["error"]
    assert "adb failed (255)" in error and "input_method" in error
    trace = (ROOT / "run/failure-error.txt").read_text()
    primary = "AssertionError: A toolbar menu is still open while waiting for the editor"
    secondary = "RuntimeError: adb failed (255)"
    assert primary in trace and secondary in trace and trace.index(primary) < trace.index(secondary)
    assert "During handling of the above exception" in trace
    capture = load("run/layout12-small-phone-artifacts.json")
    assert "device offline" in capture["collection_errors"]["screenshot"]
    path = ROOT / "run/layout12-001-window-before-layout-window.txt"
    raw = path.read_text()
    assert "Frames:" in raw and "mFullConfiguration=" in raw
    spec = importlib.util.spec_from_file_location("frozen_layout_window", ROOT / "run/source-android_layout12_window.py")
    parser = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parser)
    observed = parser.parse_window_observation(raw, "thalesmms.logisim.mobile.e2e", tuple(records[1]["displayPixels"]),
        activity_dump=(ROOT / "run/layout12-001-window-before-layout-activity.txt").read_text())
    assert observed == records[1]["window"]
    assert load("run/incoming-open-1.json")["observedStatus"].endswith(" opened.")
    project = ET.parse(ROOT / "run/failure-checkpoint-8.circ")
    components = project.findall("circuit/comp")
    assert len(project.findall("circuit")) == 1 and len(components) == 3 and not project.findall("circuit/wire")
    assert [(c.get("name"), c.get("loc")) for c in components] == [("Image", "(100,100)"), ("Image", "(260,180)"), ("Pin", "(200,160)")]
    assert [{a.get("name"): a.get("val") for a in c.findall("a")}["label"] for c in components if c.get("name") == "Pin"] == ["Reset"]
    assert not any("baseline.circ" in item["path"] for item in archive["files"])
    return {"status": "PASS", "artifactHashes": len(archive["files"]), "native": "FAIL during baseline preparation",
            "verboseWindowReplay": "PASS for the initial frame only", "restoration": "PASS", "attempts": 1}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
