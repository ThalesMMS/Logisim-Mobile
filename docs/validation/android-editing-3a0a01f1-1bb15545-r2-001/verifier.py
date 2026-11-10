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
HARNESS = "1bb155453a05acca8585d5da15c182c08bda41d0"
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
    assert len(expected) == 417
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
    assert len(files) == 50
    nonces = set()
    for path in files:
        value = load(path)
        assert value["status"] == "complete" and value["adbReturncode"] == 0
        assert value["expectedPackage"] == PACKAGE and value["appPidBefore"] == value["appPidAfter"] == "29923"
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


def rectangle(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    assert match
    return tuple(map(int, match.groups()))


def verify(base=None):
    base = Path(base or Path(__file__).resolve().parent)
    inventory(base)
    root = base / "oracle-snapshot"
    for name, expected in ORACLE_HASHES.items():
        assert digest(root / name) == expected, "Historical oracle changed"
    oracle = runpy.run_path(str(root / "scripts/user_scenario_contract.py"))
    contract = oracle["load_contract"](root / "fixtures/user-scenarios/editor-actions-v1.json")
    raw, control = base / "raw", base / "controller"
    unit, manifest = load(control / "unit.json"), load(raw / "manifest.json")
    assert unit["status"] == "FAIL" and unit["nativeInvocations"] == 1 and unit["nativeExitCode"] == 1
    assert unit["scenario"] == "editing" and unit["source"] == HARNESS
    assert unit["serial"] == "emulator-5558" and unit["elapsedSeconds"] == 292.6869110839907
    assert digest(control / "driver.py") == unit["driverSHA256"]
    assert digest(base / "source-preparation.json") == "a133510bb1c7fa2431294f74b20bc5e2ff10b1ceef3ac47e30dc3d5336784a18"
    prep = load(base / "source-preparation.json")
    assert prep["status"] == "SOURCE_PREPARED" and prep["nativeStatus"] == "NOT_RUN"
    assert unit["sourceFilesBefore"] == unit["sourceFilesAfter"] and len(unit["sourceFilesBefore"]) == 10
    for name, expected in unit["sourceFilesBefore"].items():
        path = base / "source-snapshot" / name
        assert path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"]
    for name, expected in prep["files"].items():
        path = base / "source-snapshot" / name
        if not path.exists():
            path = root / name
        assert path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"]
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
    assert manifest["package"] == PACKAGE and manifest["serial"] == "emulator-5558" and manifest["deviceApi"] == "35"
    assert (manifest["appSha256"], manifest["testSha256"], manifest["snapshotSha256"]) == tuple(APK_HASHES.values())
    packages(manifest["installedArtifactsBefore"])
    build = load(raw / "build-receipt.json")
    assert build["gitHead"] == PRODUCER and build["appSha256"] == APK_HASHES[PACKAGE]
    assert build["snapshotHelperSource"] == "5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c"
    for name, expected in manifest["scenarioSources"].items():
        assert digest(raw / ("source-" + name)) == expected
    for name, value in prep["files"].items():
        if name.startswith("scripts/") and not Path(name).name.startswith("test_"):
            assert manifest["scenarioSources"][Path(name).name] == value["sha256"]
    assert load(raw / "results.json") == [
        {"scenario": "editing-create-properties", "status": "passed", "seconds": 249.87},
        {"scenario": "editing-wires-truth-table", "status": "failed", "error": "The circuit search viewport disappeared"}]
    trace = (raw / "failure-error.txt").read_text()
    for marker in ('in wiring_and_truth_table', 'self.connect("E2E_IN", "out", "E2E_NOT", "in")', 'in connect', 'self.search(destination)', 'in search', 'The circuit search viewport disappeared'):
        assert marker in trace, marker
    documents = {}
    pairs = (("editing-empty.circ", "empty"), ("editing-components.circ", "components"),
        ("editing-property-north.circ", "north"), ("editing-property-undo.circ", "components"),
        ("editing-property-redo.circ", "north"), ("editing-property-restored.circ", "components"),
        ("failure-checkpoint-44.circ", "components"))
    for name, checkpoint in pairs:
        observed = oracle["assert_document"]((raw / name).read_bytes(), contract, checkpoint)
        documents[name] = {"checkpoint": checkpoint, "componentCount": observed["componentCount"], "wireCount": observed["wireCount"], "sha256": digest(raw / name)}
    assert (raw / "editing-components.circ").read_bytes() == (raw / "editing-property-undo.circ").read_bytes() == (raw / "editing-property-restored.circ").read_bytes() == (raw / "last-document.circ").read_bytes()
    assert (raw / "editing-property-north.circ").read_bytes() == (raw / "editing-property-redo.circ").read_bytes()
    assert not (raw / "editing-wired-not.circ").exists()
    refs = load(base / "local-tar-references.json")
    assert len(refs) == 2
    member = base / "final-private-checkpoint.workspace"
    for value in refs:
        assert value["bytes"] == 106042 and value["sha256"] == "bd417d069a1ceb87aee8acec85b4e1605cb8042a595a92f4f249d828e51a2577"
        assert value["memberBytes"] == member.stat().st_size and value["memberSHA256"] == digest(member)
    payload = member.read_text()
    private_xml = payload[payload.index("<?xml"):payload.index("</project>") + len("</project>")]
    assert private_xml.encode() == (raw / "failure-checkpoint-44.circ").read_bytes()
    metadata = load(raw / "failure-checkpoint-documents.json")
    assert metadata[-1]["archive_member"] == refs[0]["member"] and Path(metadata[-1]["document"]).name == "failure-checkpoint-44.circ"
    search_observations = []
    for capture in (49, 50):
        paths = list(raw.glob(f"focus-snapshot-*-{capture}.xml"))
        assert len(paths) == 1
        tree = ET.parse(paths[0]).getroot()
        viewports = [node for node in tree.iter("node") if node.get("resource-id") == "a11y-circuit-list"]
        fields = [node for node in tree.iter("node") if node.get("resource-id") == "a11y-circuit-search"]
        assert len(viewports) == 1
        assert any(node.get("text") == "Connection starts at E2E_IN, out. Choose a destination terminal." for node in tree.iter("node"))
        viewport = rectangle(viewports[0])
        if capture == 49:
            assert len(fields) == 1 and fields[0].get("text") == "E2E_IN"
            field = rectangle(fields[0])
            assert viewport == (64, 689, 1016, 2273) and field == (64, 643, 1016, 811)
            assert viewport[1] - field[1] == 46
        else:
            assert fields == []
            field = None
        search_observations.append({"capture": capture, "viewportBounds": viewport, "searchBounds": field})
    final = ET.parse(raw / "failure-ui.xml").getroot()
    assert len([node for node in final.iter("node") if node.get("resource-id") == "editor-canvas"]) == 1
    assert not any(node.get("resource-id") in {"editor-tool-sheet", "a11y-circuit-list"} for node in final.iter("node"))
    requests = [json.loads(line) for line in (control / "adb-command-requests.jsonl").read_text().splitlines()]
    swipes = [record for record in requests if record["argv"][3:6] == ["shell", "input", "swipe"]]
    assert len(swipes) == 4
    for index, record in enumerate(swipes):
        expected = ["540", "2035", "540", "1085", "250"] if index < 3 else ["540", "1085", "540", "2035", "250"]
        assert record["argv"][6:] == expected
    assert [record["elapsedSeconds"] for record in swipes] == sorted(record["elapsedSeconds"] for record in swipes)
    return {"status": "PARTIAL_PASS_FAILURE_PRESERVED", "inventoryEntries": 417, "sdkCompleteCaptures": sdk(raw),
        "completedGroup": "editing-create-properties", "completedGroupSeconds": 249.87,
        "failedGroup": "editing-wires-truth-table", "documentChecks": documents,
        "searchObservations": search_observations, "swipeRequestsOnly": swipes,
        "notRun": ["editing-selection-history", "editing-viewport-touch-parity"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive), indent=2))
