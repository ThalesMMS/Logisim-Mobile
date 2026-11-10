"""Verify the retained DIP case using only this archive and Python's standard library."""
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
ORACLE_MANIFEST = "f0e00eb688cfcb67fa08d5b0f563d9378b0686300ca5f669cba2e1ab94c18844"
PREPARATION = "8b075ea6921c86145d0814f2230014e42825a18a249e623ffedca12889434882"
APK_HASHES = {
    PACKAGE: "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    PACKAGE + ".test": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    HELPER: "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
}
SELECTION = {"selectionScope": "selected-cases", "selectedCases": ["dip-last"],
             "notSelectedCases": ["signals", "pointer-controls", "keyboard-tty", "dimensions"]}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text())


def inventory(base):
    recorded = load(base / "inventory.json")
    assert len(recorded) == 218, "Unexpected retained file count"
    actual = {"source-preparation.json"}
    for directory in ("raw", "controller", "oracle-snapshot", "source-snapshot"):
        root = base / directory
        assert root.is_dir() and not root.is_symlink()
        for path in root.rglob("*"):
            assert not path.is_symlink(), "Evidence cannot use symlinks"
            if path.is_file():
                assert path.suffix.lower() not in {".apk", ".tar", ".jar", ".gz"}
                actual.add(path.relative_to(base).as_posix())
    assert actual == set(recorded), "Retained inventory differs"
    for name, expected in recorded.items():
        relative = Path(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        path = base / relative
        assert path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"], name
    sums = "".join(f'{value["sha256"]}  {name}\n' for name, value in sorted(recorded.items()))
    assert (base / "SHA256SUMS").read_text() == sums
    return len(actual)


def packages(entries):
    assert len(entries) == 3
    assert {item["package"]: item["sha256"] for item in entries} == APK_HASHES


def oracle(base):
    root = base / "oracle-snapshot"
    assert digest(root / "manifest.json") == ORACLE_MANIFEST, "Historical oracle manifest changed"
    manifest = load(root / "manifest.json")
    assert manifest["sourcePin"] == HARNESS and len(manifest["files"]) == 5
    for name, expected in manifest["files"].items():
        assert digest(root / name) == expected, "Historical oracle changed: " + name
    # Check all five pinned files before executing the historical oracle.
    functions = runpy.run_path(str(root / "scripts/io_showcase_contract.py"))
    contract = functions["load_io_contract"](root / "fixtures/user-scenarios/io-actions-v1.json")
    instance = functions["IoShowcaseOracle"]((root / contract["fixture"]).read_bytes(),
                  load(root / "examples/io-showcase.targets.json"), contract)
    return root, functions, contract, instance


def sdk(raw):
    files = sorted(raw.glob("focus-snapshot-*.json"))
    assert len(files) == 22
    nonces = set()
    for path in files:
        record = load(path)
        assert record["status"] == "complete" and record["adbReturncode"] == 0
        assert record["expectedPackage"] == PACKAGE
        assert record["target"] == HELPER + "/dev.logisim.mobile.documents.UiSnapshotInstrumentation"
        assert record["appPidBefore"] == record["appPidAfter"] == "32167"
        nonce = record["nonce"]
        assert re.fullmatch(r"[0-9a-f]{32}", nonce) and nonce not in nonces
        nonces.add(nonce)
        values = record["bundle"]
        assert values["snapshotNonce"] == nonce and values["snapshotExpectedPackage"] == PACKAGE
        assert values["snapshotCaptureState"] == "COMPLETE" and "snapshotError" not in values
        assert values["snapshotIncompleteTreeAttempts"] == "0"
        stdout = (raw / record["stdout"]).read_text()
        assert re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", stdout, re.MULTILINE) == ["-1"]
        assert not re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg=|Process crashed", stdout)
        assert not (raw / record["stderr"]).read_text().strip()
        pairs = re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)$", stdout, re.MULTILINE)
        assert len({key for key, _ in pairs}) == len(pairs) and dict(pairs) == values
        decoded = base64.b64decode(values["snapshotXmlBase64"], validate=True)
        tree = ET.fromstring(decoded)
        assert ET.tostring(tree) == ET.tostring(ET.parse(raw / record["xml"]).getroot())
        assert tree.tag == "hierarchy" and tree.get("snapshot-nonce") == nonce
        assert tree.get("uptime-millis") == values["snapshotUptimeMillis"]
    for capture in (14, 15):
        matches = list(raw.glob(f"focus-snapshot-*-{capture}.xml"))
        assert len(matches) == 1
        root = ET.parse(matches[0]).getroot()
        fields = [node for node in root.iter("node") if node.get("class", "").endswith("EditText")]
        assert len(fields) == 1 and fields[0].get("text") == "0" and fields[0].get("focused") == "true"
        assert len([node for node in root.iter("node") if node.get("text") == "Invalid value for number: 0."]) == 1
        leaves = [node for node in root.iter("node") if node.get("text") == "Apply"]
        assert len(leaves) == 1 and leaves[0].get("enabled") == "true"
        parents = {child: parent for parent in root.iter() for child in parent}
        owner = leaves[0]
        while owner.get("clickable") != "true":
            assert owner in parents
            owner = parents[owner]
        assert owner.get("package") == PACKAGE and owner.get("enabled") == "false", "Invalid zero must disable the clickable Apply owner"
    return len(files)


def verify(base=None):
    base = Path(base or Path(__file__).resolve().parent)
    count = inventory(base)
    historical, functions, contract, model = oracle(base)
    raw, control = base / "raw", base / "controller"
    assert digest(base / "source-preparation.json") == PREPARATION, "Historical preparation changed"
    prep = load(base / "source-preparation.json")
    assert prep["status"] == "SOURCE_PREPARED" and prep["nativeStatus"] == "NOT_RUN"
    unit, manifest = load(control / "unit.json"), load(raw / "manifest.json")
    assert unit["status"] == "PASS" and unit["nativeInvocations"] == 1 and unit["nativeExitCode"] == 0
    assert unit["scenario"] == "dip-last" and unit["source"] == HARNESS[:8]
    assert unit["driverSHA256"] == digest(control / "driver.py")
    expected_sources = prep["preparedSources"] | prep["unchangedOracleInputs"]
    assert unit["sourceFilesBefore"] == unit["sourceFilesAfter"] == expected_sources
    for section, folder in (("preparedSources", "source-snapshot"), ("unchangedOracleInputs", "oracle-snapshot")):
        for name, expected in prep[section].items():
            path = base / folder / name
            assert digest(path) == expected["sha256"] and path.stat().st_size == expected["bytes"]
    for phase in ("before", "after"):
        state = unit[phase]
        assert state == load(control / phase / "inventory.json")
        for name, observed in state.items():
            if name != "installed":
                assert observed == (control / phase / (name + ".stdout")).read_text().strip(), (phase, name)
                assert not (control / phase / (name + ".stderr")).read_text().strip(), (phase, name)
        packages(state["installed"])
        assert state["accessibility-enabled"] == "0" and state["enabled-services"] in ("", "null")
        assert "touchExplorationEnabled=false" in state["accessibility"] and "Bound services:{}" in state["accessibility"]
    assert unit["settingsDifferences"] == {} and unit["anrChanged"] is False
    settings = ("wm-size", "wm-density", "font-scale", "accelerometer-rotation", "user-rotation", "accessibility-enabled", "enabled-services")
    assert all(unit["before"][key] == unit["after"][key] for key in settings)
    assert unit["before"]["last-anr"] == unit["after"]["last-anr"]
    assert unit["cleanup"]["pidAfter"] == "" and "cleanupError" not in unit
    assert manifest["package"] == PACKAGE and manifest["serial"] == "emulator-5556"
    assert manifest["gitHead"] == PRODUCER and manifest["harnessGitHead"] == HARNESS
    assert manifest["deviceApi"] == "35" and manifest["appLanguage"] == "en" and manifest["sourceChanges"] == []
    assert manifest["ioCaseSelection"] == SELECTION
    assert (manifest["appSha256"], manifest["testSha256"], manifest["snapshotSha256"]) == tuple(APK_HASHES.values())
    for phase in ("Before", "After"):
        packages(manifest["installedArtifacts" + phase])
    for name, expected in manifest["scenarioSources"].items():
        assert digest(raw / ("source-" + name)) == expected
    for name, expected in prep["preparedSources"].items():
        if not Path(name).name.startswith("test_"):
            assert manifest["scenarioSources"][Path(name).name] == expected["sha256"]
    assert manifest["scenarioContractSha256"] == digest(historical / "fixtures/user-scenarios/editor-actions-v1.json")
    build = load(raw / "build-receipt.json")
    assert build["gitHead"] == PRODUCER and build["appSha256"] == APK_HASHES[PACKAGE]
    assert load(raw / "results.json") == [{"scenario": "io-showcase-dip-last", "status": "passed", "seconds": 284.82}]
    observations = load(raw / "io-showcase-observations.json")
    assert {key: observations[key] for key in SELECTION} == SELECTION
    assert observations["caseResults"] == [{"case": "dip-last", "scenario": "io-showcase-dip-last", "status": "passed"}]
    records = observations["automatedObservations"]
    assert len(records) == 4
    for index, record in enumerate(records):
        expected = contract["baselineOutputs"] if index == 0 else contract["dip"]["outputs"]
        assert record["recordType"] == "native-canvas-probes" and record["expected"] == expected
        functions["assert_probe_description"](record["observedDescription"], expected)
    exports = []
    for filename, attrs in (("io-baseline.circ", {}), ("io-dip-last.circ", {"positions": "2"}),
            ("io-dip-four.circ", {"positions": "2", "number": "4"}),
            ("io-dip-invalid-preserved.circ", {"positions": "2", "number": "4"}),
            ("io-dip-export.circ", {"positions": "2", "number": "8"}),
            ("io-dip-reopened.circ", {"positions": "2", "number": "8"})):
        model.expect({"IO_DIP": attrs})
        project = model.assert_document((raw / filename).read_bytes())
        assert len(project.findall("circuit/comp")) == 54 and len(project.findall("circuit/wire")) == 64
        exports.append({"file": filename, "sha256": digest(raw / filename)})
    assert (raw / "io-dip-four.circ").read_bytes() == (raw / "io-dip-invalid-preserved.circ").read_bytes()
    assert (raw / "io-dip-export.circ").read_bytes() == (raw / "io-dip-reopened.circ").read_bytes() == (raw / "last-document.circ").read_bytes()
    return {"status": "PASS", "retainedFiles": count, "sdkCompleteCaptures": sdk(raw), "probeRecords": 4,
            "exports": exports, "scope": "One Android DIP case; no overall issue44 qualification"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive), indent=2))
