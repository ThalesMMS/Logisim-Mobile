"""Audit one retained Android dimensions run without contacting Android."""

import argparse
import hashlib
import json
from pathlib import Path
import runpy
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BUNDLE = ROOT / "docs/validation/io44-android-dimensions-85da"
ORACLE_PIN = "5cb2fead2743cbba84f0efbad51e8bca822c7f05"
ORACLE_MANIFEST_SHA256 = "e6d8fc0bf11cfac2c97e2c2cbfaacbd28e8417b0df29999453af836f9aadef7a"
PRODUCER = "85da031e4ed11474c2e763976392729810ef142d"
EXECUTOR = "a32d33020f1508276ebe9ed6fa83f0dfc4d50d4d"
PRODUCTION_SOURCE = "6cef05429cf66be23569aaebc3c72950cf3fe53a"
CLAIM = {
    "case": "dimensions", "caseStatus": "passed", "issue44": "partial",
    "allIssue44CasesPassed": False, "iosExecution": "not-inferred",
    "physicalHardware": "not-inferred", "visualParity": "not-inferred",
}
SELECTION = {
    "selectionScope": "selected-cases", "selectedCases": ["dimensions"],
    "notSelectedCases": ["signals", "dip-last", "pointer-controls", "keyboard-tty"],
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_oracle_snapshot(bundle, receipt):
    snapshot = bundle / "oracle-snapshot"
    assert not snapshot.is_symlink(), "Historical oracle must be stored inside the proof bundle"
    paths = list(snapshot.rglob("*"))
    assert not any(path.is_symlink() for path in paths), "Historical oracle cannot use symlinks"
    manifest_path = snapshot / "manifest.json"
    assert manifest_path.is_file(), "Historical oracle manifest is missing"
    manifest = json.loads(manifest_path.read_text())
    assert receipt["auditBasePin"] == manifest["sourcePin"] == ORACLE_PIN, "Historical oracle pin changed"
    assert sha256(manifest_path) == ORACLE_MANIFEST_SHA256, "Historical oracle manifest changed"
    assert manifest == {"schemaVersion": 1, "sourcePin": ORACLE_PIN, "files": receipt["oracleInputs"]}, \
        "Historical oracle manifest does not match the receipt"
    expected_paths = {"manifest.json", *manifest["files"]}
    assert {path.relative_to(snapshot).as_posix() for path in paths if path.is_file()} == expected_paths, \
        "Historical oracle inventory is incomplete or has extra files"
    for name, expected in manifest["files"].items():
        assert sha256(snapshot / name) == expected, f"Historical oracle hash mismatch: {name}"
    # Authenticate every input before running the archived Python oracle.
    oracle = runpy.run_path(str(snapshot / "scripts/io_showcase_contract.py"))
    return snapshot, oracle


def checkpoints(contract):
    yield "io-baseline", {}
    for edit in contract["dimensionEdits"]:
        stem = edit["label"].lower() + "-" + edit["name"]
        yield "io-dimension-" + stem, {edit["label"]: {edit["name"]: edit["value"]}}
        yield "io-restored-" + stem, {edit["label"]: {edit["name"]: edit["restore"]}}
    yield "io-dimensions-restored", {}


def verify_bundle(bundle=DEFAULT_BUNDLE):
    bundle = Path(bundle)
    evidence = bundle / "evidence"
    receipt = json.loads((bundle / "receipt.json").read_text())
    assert receipt["schemaVersion"] == 1
    assert receipt["scope"] == "io44-android-dimensions-retained-native-run"
    assert receipt["claim"] == CLAIM, "This receipt qualifies dimensions only, never full issue #44"
    oracle_root, historical = load_oracle_snapshot(bundle, receipt)
    contract = historical["load_io_contract"]()
    assert_probe_description = historical["assert_probe_description"]
    manifest = json.loads((evidence / "manifest.json").read_text())
    build = json.loads((evidence / "build-receipt.json").read_text())
    observations = json.loads((evidence / "io-showcase-observations.json").read_text())
    results = json.loads((evidence / "results.json").read_text())
    expected_files = {"manifest.json", "build-receipt.json", "results.json",
                      "io-showcase-observations.json", "independent-provenance.json"}
    expected_files.update("source-" + name for name in manifest["scenarioSources"])
    expected_files.update(name + ".circ" for name, _ in checkpoints(contract))
    expected_files.update(name + "-ui.xml" for name, _ in checkpoints(contract) if name != "io-baseline")
    assert set(receipt["artifacts"]) == {"evidence/" + name for name in expected_files}, "Incomplete evidence inventory"
    for name, expected in receipt["artifacts"].items():
        assert sha256(bundle / name) == expected, f"Artifact hash mismatch: {name}"
    assert manifest["gitHead"] == build["gitHead"] == build["appProducerHead"] == PRODUCER
    assert manifest["harnessGitHead"] == EXECUTOR
    assert build["productionSourceIdenticalTo"] == PRODUCTION_SOURCE
    assert manifest["appSha256"] == build["appSha256"]
    assert manifest["sourceChanges"] == []
    assert manifest["ioCaseSelection"] == SELECTION
    assert {key: observations[key] for key in SELECTION} == SELECTION, "Selection is dimensions only"
    assert observations["scope"] == "native-mounted-editor"
    assert observations["caseResults"] == [{"case": "dimensions", "scenario": "io-showcase-dimensions", "status": "passed"}]
    assert len(results) == 1 and results[0]["scenario"] == "io-showcase-dimensions" and results[0]["status"] == "passed"
    assert results[0]["seconds"] == receipt["originalRun"]["durationSeconds"]
    assert receipt["originalRun"]["producerPin"] == PRODUCER and receipt["originalRun"]["executorPin"] == EXECUTOR
    assert manifest["scenarioContractSha256"] == sha256(oracle_root / "fixtures/user-scenarios/editor-actions-v1.json")
    for name, expected in manifest["scenarioSources"].items():
        assert sha256(evidence / ("source-" + name)) == expected, f"Frozen executor mismatch: {name}"

    packages = {manifest["package"]: manifest["appSha256"],
                manifest["package"] + ".test": manifest["testSha256"],
                manifest["snapshotPackage"]: manifest["snapshotSha256"]}
    assert len(packages) == 3
    for phase in ("installedArtifactsBefore", "installedArtifactsAfter"):
        installed = manifest[phase]
        assert len(installed) == 3 and {entry["package"]: entry["sha256"] for entry in installed} == packages, phase
    records = observations["automatedObservations"]
    assert observations["fixtureSha256"] == sha256(oracle_root / contract["fixture"])
    assert len(records) == 1 + len(contract["dimensionEdits"])
    for record in records:
        assert record["recordType"] == "native-canvas-probes"
        assert record["expected"] == contract["baselineOutputs"], "All nineteen literal baseline probes are required"
        assert_probe_description(record["observedDescription"], contract["baselineOutputs"])

    oracle = historical["IoShowcaseOracle"]((oracle_root / contract["fixture"]).read_bytes(),
                             json.loads((oracle_root / "examples/io-showcase.targets.json").read_text()), contract)
    verified = []
    for name, changes in checkpoints(contract):
        oracle.expect(changes)
        project = oracle.assert_document((evidence / (name + ".circ")).read_bytes())
        if name != "io-baseline":
            hierarchy = ET.parse(evidence / (name + "-ui.xml")).getroot()
            canvases = [node for node in hierarchy.iter("node") if node.get("resource-id", "").endswith("editor-canvas")]
            assert len(canvases) == 1, f"Expected one native Canvas at {name}"
            matches = []
            for node in canvases[0].iter("node"):
                try:
                    assert_probe_description(node.get("content-desc", ""), contract["baselineOutputs"])
                except AssertionError:
                    continue
                matches.append(node)
            assert len(matches) == 1, f"Expected one complete native nineteen-probe description at {name}"
        verified.append({"checkpoint": name, "components": len(project.findall("circuit/comp")),
                         "wireSegments": len(project.findall("circuit/wire")), "expectedChanges": changes})
    return {"status": "passed", "scope": "dimensions-only", "issue44": "partial",
            "verification": "offline-audit-of-retained-native-owner-execution",
            "nativeReexecuted": False, "baselineProbes": len(contract["baselineOutputs"]),
            "nativeProbeRecords": len(records), "installedApkReceipts": 6, "checkpoints": verified}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, default=DEFAULT_BUNDLE)
    args = parser.parse_args()
    print(json.dumps(verify_bundle(args.bundle), indent=2))


if __name__ == "__main__":
    main()
