"""Replay the retained Android editing failure without Android, Git or current sources."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import runpy
import xml.etree.ElementTree as ET

PACKAGE = "thalesmms.logisim.mobile.e2e"
HELPER = "thalesmms.logisim.mobile.snapshot.e2e.test"
PRODUCER = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
HARNESS = "bdc390d1a23f87c9429537db5bca6b2e05f88f1b"
APK_HASHES = {PACKAGE: "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    PACKAGE + ".test": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    HELPER: "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c"}
ORACLE_HASHES = {
    "scripts/user_scenario_contract.py": "858dc5e43c70a28898afaa16341b786b203696153cc33c2e43c8314a4572c9eb",
    "fixtures/user-scenarios/editor-actions-v1.json": "9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text())


def inventory(base):
    expected = load(base / "inventory.json")
    assert len(expected) == 358
    actual = {"source-preparation.json", "local-tar-references.json", "final-private-checkpoint.workspace"}
    for name in ("raw", "controller", "oracle-snapshot", "source-snapshot"):
        root = base / name
        assert root.is_dir() and not root.is_symlink()
        for path in root.rglob("*"):
            assert not path.is_symlink()
            if path.is_file():
                assert path.suffix not in {".apk", ".jar", ".tar", ".gz"}
                actual.add(path.relative_to(base).as_posix())
    assert set(expected) == actual
    for name, value in expected.items():
        relative = Path(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        path = base / relative
        assert path.stat().st_size == value["bytes"] and digest(path) == value["sha256"], name
    assert (base / "SHA256SUMS").read_text() == "".join(f'{value["sha256"]}  {name}\n' for name, value in sorted(expected.items()))


def packages(entries):
    assert len(entries) == 3 and {item["package"]: item["sha256"] for item in entries} == APK_HASHES


def sdk(raw):
    files = list(raw.glob("focus-snapshot-*.json"))
    assert len(files) == 42
    nonces = set()
    for path in files:
        value = load(path)
        assert value["status"] == "complete" and value["adbReturncode"] == 0
        assert value["expectedPackage"] == PACKAGE and value["appPidBefore"] == value["appPidAfter"] == "5182"
        assert value["target"] == HELPER + "/dev.logisim.mobile.documents.UiSnapshotInstrumentation"
        nonce = value["nonce"]
        assert re.fullmatch(r"[0-9a-f]{32}", nonce) and nonce not in nonces
        nonces.add(nonce)
        bundle = value["bundle"]
        assert bundle["snapshotNonce"] == nonce and bundle["snapshotExpectedPackage"] == PACKAGE
        assert bundle["snapshotCaptureState"] == "COMPLETE" and bundle["snapshotIncompleteTreeAttempts"] == "0"
        assert "snapshotError" not in bundle
        stdout = (raw / value["stdout"]).read_text()
        assert re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", stdout, re.MULTILINE) == ["-1"]
        pairs = re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)$", stdout, re.MULTILINE)
        assert len({key for key, _ in pairs}) == len(pairs) and dict(pairs) == bundle
        assert not (raw / value["stderr"]).read_text().strip()
        tree = ET.fromstring(base64.b64decode(bundle["snapshotXmlBase64"], validate=True))
        assert ET.tostring(tree) == ET.tostring(ET.parse(raw / value["xml"]).getroot())
        assert tree.get("snapshot-nonce") == nonce and tree.get("uptime-millis") == bundle["snapshotUptimeMillis"]
    return len(files)


def verify(base=None):
    base = Path(base or Path(__file__).resolve().parent)
    inventory(base)
    root = base / "oracle-snapshot"
    for name, expected in ORACLE_HASHES.items():
        assert digest(root / name) == expected, "Historical oracle changed"
    # Both historical inputs are authenticated before executing the oracle.
    oracle = runpy.run_path(str(root / "scripts/user_scenario_contract.py"))
    contract = oracle["load_contract"](root / "fixtures/user-scenarios/editor-actions-v1.json")
    raw, control = base / "raw", base / "controller"
    unit, manifest = load(control / "unit.json"), load(raw / "manifest.json")
    assert unit["status"] == "FAIL" and unit["nativeInvocations"] == 1 and unit["nativeExitCode"] == 1
    assert unit["scenario"] == "editing" and unit["source"] == HARNESS
    assert unit["elapsedSeconds"] == 205.80498083299608
    assert digest(control / "driver.py") == unit["driverSHA256"]
    assert digest(base / "source-preparation.json") == "8b075ea6921c86145d0814f2230014e42825a18a249e623ffedca12889434882"
    prep = load(base / "source-preparation.json")
    assert unit["sourceFilesBefore"] == unit["sourceFilesAfter"] == prep["preparedSources"] | prep["unchangedOracleInputs"]
    for name, value in prep["preparedSources"].items():
        assert digest(base / "source-snapshot" / name) == value["sha256"]
    for phase in ("before", "after"):
        state = unit[phase]
        assert state == load(control / phase / "inventory.json")
        for name, observed in state.items():
            if name != "installed":
                assert observed == (control / phase / (name + ".stdout")).read_text().strip()
                assert not (control / phase / (name + ".stderr")).read_text().strip()
        packages(state["installed"])
        assert state["accessibility-enabled"] == "0" and state["enabled-services"] in ("", "null")
        assert "touchExplorationEnabled=false" in state["accessibility"] and "Bound services:{}" in state["accessibility"]
    assert unit["settingsDifferences"] == {} and unit["anrChanged"] is False and "cleanupError" not in unit
    for key in ("wm-size", "wm-density", "font-scale", "accelerometer-rotation", "user-rotation", "accessibility-enabled", "enabled-services", "last-anr"):
        assert unit["before"][key] == unit["after"][key]
    assert unit["cleanup"]["pidAfter"] == ""
    assert manifest["gitHead"] == PRODUCER and manifest["harnessGitHead"] == HARNESS
    assert manifest["scenario"] == "editing" and manifest["sourceChanges"] == []
    assert manifest["package"] == PACKAGE and manifest["serial"] == "emulator-5556" and manifest["deviceApi"] == "35"
    assert (manifest["appSha256"], manifest["testSha256"], manifest["snapshotSha256"]) == tuple(APK_HASHES.values())
    build = load(raw / "build-receipt.json")
    assert build["gitHead"] == PRODUCER and build["appSha256"] == APK_HASHES[PACKAGE]
    assert build["snapshotHelperSource"] == "5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c"
    packages(manifest["installedArtifactsBefore"])
    for name, expected in manifest["scenarioSources"].items():
        assert digest(raw / ("source-" + name)) == expected
    for name, expected in prep["preparedSources"].items():
        if not Path(name).name.startswith("test_"):
            assert manifest["scenarioSources"][Path(name).name] == expected["sha256"]
    results = load(raw / "results.json")
    assert len(results) == 1 and results[0]["scenario"] == "editing-create-properties" and results[0]["status"] == "failed"
    assert "No fresh Android UI hierarchy within 0.46215945898438804s" in results[0]["error"]
    trace = (raw / "failure-error.txt").read_text()
    assert results[0]["error"] in trace
    for marker in ('in create_and_properties', 'self.assert_document("editing-property-north", "north")', 'in assert_document', 'in export', 'in export_xml', 'self.wait_editor()', 'in wait_editor', 'in _screen_uiautomator'):
        assert marker in trace, marker
    assert 'def wait_editor(self, *, timeout=30)' in (raw / 'source-e2e-android.py').read_text()
    document_results = {}
    for name, checkpoint in (("editing-empty.circ", "empty"), ("editing-components.circ", "components"), ("failure-checkpoint-36.circ", "north")):
        result = oracle["assert_document"]((raw / name).read_bytes(), contract, checkpoint)
        document_results[name] = {"checkpoint": checkpoint, "componentCount": result["componentCount"], "wireCount": result["wireCount"], "sha256": digest(raw / name)}
    assert (raw / "last-document.circ").read_bytes() == (raw / "editing-components.circ").read_bytes()
    assert not (raw / "editing-property-north.circ").exists(), "North Copy XML was not completed"
    refs = load(base / "local-tar-references.json")
    assert len(refs) == 2
    member = base / "final-private-checkpoint.workspace"
    for value in refs:
        assert value["bytes"] == 86586 and value["sha256"] == "796d0b3c091e397eea0f00fe1ae75546bc94f6c802885e648b91f76041b6d34b"
        assert value["memberBytes"] == member.stat().st_size and value["memberSHA256"] == digest(member)
    payload = member.read_text()
    private_xml = payload[payload.index("<?xml"):payload.index("</project>") + len("</project>")]
    assert private_xml.encode() == (raw / "failure-checkpoint-36.circ").read_bytes()
    metadata = load(raw / "failure-checkpoint-documents.json")
    assert metadata[-1]["archive_member"] == refs[0]["member"] and Path(metadata[-1]["document"]).name == "failure-checkpoint-36.circ"
    tree = ET.parse(raw / "failure-ui.xml").getroot()
    scopes = [node for node in tree.iter("node") if node.get("resource-id") == "editor-inspector-sheet" and node.get("package") == PACKAGE]
    assert len(scopes) == 1
    for text in ("E2E_NOT", "Facing", "North"):
        assert len([node for node in scopes[0].iter("node") if node.get("text") == text]) == 1
    requests = [json.loads(line) for line in (control / "adb-command-requests.jsonl").read_text().splitlines()]
    requested = []
    for elapsed, suffix in ((167.788970915979, ["shell", "input", "tap", "818", "1615"]), (167.8144408329972, ["shell", "input", "keyevent", "4"]), (167.83958729097503, ["shell", "am", "start", "-W", "-n", PACKAGE + ".test/dev.logisim.mobile.documents.DocumentClipboardReaderActivity", "--es", "prepareCopy", "2c169d739686470baf5c673c46bc1b01"])):
        found = [record for record in requests if record["elapsedSeconds"] == elapsed]
        assert len(found) == 1 and found[0]["argv"][3:] == suffix
        requested.append({"elapsedSeconds": elapsed, "utc": found[0]["utc"], "request": suffix})
    return {"status": "FAIL_PRESERVED", "inventoryEntries": 358, "sdkCompleteCaptures": sdk(raw),
            "documentChecks": document_results, "requestsOnly": requested,
            "notRun": ["editing-wires-truth-table", "editing-selection-history", "editing-viewport-touch-parity"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive), indent=2))
