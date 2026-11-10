"""Verify preserved evidence only. No Android/device process is invoked."""

import hashlib
import json
from pathlib import Path


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
    assert manifest["harnessGitHead"] == preflight["harness"] == "68a3138307638dad928b184a0d3ec30c8eb30ead"
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
    assert [r["name"] for r in records] == ["initial-configuration", "window-before-layout", "applied-configuration", "scenario-failure", "restoration"]
    assert records[1]["status"] == "CAPTURED" and records[1]["window"]["insetsStatus"] == "AVAILABLE"
    config = records[2]
    assert config["expectedDisplayDp"] == [320, 568] and config["fontScale"] == 1.0
    assert config["observedDisplay"] == {"display_px": [320, 568], "display_dp": [320.0, 568.0], "density": 160, "font_scale": 1.0}
    error = load("run/results.json")[0]["error"]
    assert error == records[3]["error"] and error.startswith("No fresh Android UI hierarchy within ")
    assert "uiautomator" in error and "timed out" in error
    assert records[4]["status"] == "PASS" and records[4]["differences"] == {}
    assert all(command["status"] == "RESTORED" for command in records[4]["commands"])
    trace = (ROOT / "run/failure-error.txt").read_text()
    assert "export_xml" in trace and "line 471" in trace and "self.wait_editor()" in trace
    assert "During handling of the above exception" not in trace
    assert not any("baseline.circ" in item["path"] for item in archive["files"])
    assert not any(Path(item["path"]).name.startswith("focus-snapshot-") for item in archive["files"])
    return {"status": "PASS", "artifactHashes": len(archive["files"]), "native": "FAIL before baseline after Read copied XML",
            "innerRestoration": "PASS", "outerRestoration": "PASS", "attempts": 1,
            "scope": "Evidence integrity and recorded failure/restoration only; no cause assigned."}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
