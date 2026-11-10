"""Verify preserved evidence only. No Android/device process is invoked."""

import hashlib
import json
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
    assert manifest["harnessGitHead"] == preflight["harness"] == "fc6e2fbf4452e0336c67be05ec55e5c20abf22a0"
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
    assert [record["name"] for record in records] == ["initial-configuration", "window-before-layout"]
    assert records[1]["status"] == "FAILED" and records[1]["error"] == "Focused window has no unique frame observation"
    assert load("run/results.json") == [{"scenario": "layout12-small-phone", "status": "failed", "error": records[1]["error"]}]
    raw = (ROOT / "run/layout12-001-window-before-layout-window.txt").read_text()
    token, title = re.findall(r"(?m)^\s*mCurrentFocus=Window\{(\S+) u\d+ ([^{}\n]+)\}\s*$", raw)[0]
    assert title == "thalesmms.logisim.mobile.e2e/dev.logisim.mobile.MainActivity"
    assert re.search(r"Window #\d+ Window\{" + re.escape(token) + r" u0 " + re.escape(title), raw)
    assert "Frames:" not in raw and "mFullConfiguration=" not in raw
    return {"status": "PASS", "artifactHashes": len(archive["files"]), "native": "FAIL before layout/fixture configuration",
            "restoration": "PASS", "attempts": 1}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
