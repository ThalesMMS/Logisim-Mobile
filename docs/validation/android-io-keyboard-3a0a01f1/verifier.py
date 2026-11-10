#!/usr/bin/env python3
"""Verify the retained partial Keyboard attempt without Android or application imports."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
PACKAGE = "thalesmms.logisim.mobile.e2e"
APP_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
HELPER_SOURCE = "5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c"
HARNESS = "bbbf1e9e1caa38bc8608c994e050aba171879899"
PID = "23045"
ARTIFACTS = {
    "appSha256": "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    "testSha256": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    "snapshotSha256": "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
}
INPUTS = {
    "inputs/io-actions-v1.json": "8939f027d22e20173fa575eb17eb19508bb8a440039574be11902f5030f92cfb",
    "inputs/io-control-messages.json": "6d1d002ff1190d4f73739dc045be00a2bfa1b2dc28f4d75b26f69e65ce4fe946",
    "inputs/io-showcase.circ": "a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9",
    "inputs/io-showcase.targets.json": "4917b9e3bf386cdc555ae544725e9ee2f420beac1462a5a88e0c27d1d6e0cf15",
}
SOURCES = {
    "e2e-android.py": "d28e2473df36c503a0963c6b6720623a83c9e82bbd809d85df4decc4326fc27b",
    "android_ui.py": "8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634",
    "android_editing_scenarios.py": "0b97319487924cf371eec29b8f0b9c7ec7a553230ea5ebd9149ff5d041556673",
    "user_scenario_contract.py": "858dc5e43c70a28898afaa16341b786b203696153cc33c2e43c8314a4572c9eb",
    "android_inspector_scenarios.py": "74e686a0448e4e6c29729f19cbf04c67922368bab65ebb69a7d238004582f40c",
    "android_io_showcase_scenarios.py": "4aa42d7f7d3bd95922cef3cf9609db9dc1d57d77a701619a5e90c71a59c983e8",
    "android_portable_scenarios.py": "6e5888e73ddb558d038f22c45b517e182a46a991f43d878427a65f724ee5c7b4",
    "android_visual_export34_scenarios.py": "aa2c353709fd35398f39a32592a4e8b1a2029a21e042702678b14b772a036763",
}
BASELINE = {
    "OUT_BUTTON": "0", "OUT_DIP0": "0", "OUT_DIP1": "0", "OUT_JOY_X": "1000",
    "OUT_JOY_Y": "1000", "OUT_RGB_R": "1", "OUT_RGB_G": "1", "OUT_RGB_B": "0",
    "OUT_BAR_VALUE": "10100101", "OUT_SEGMENTS": "00111111", "OUT_KEYBOARD_DATA": "0000000",
    "OUT_KEYBOARD_AVAILABLE": "0", "OUT_CLOCK": "0", "OUT_MATRIX_ROW0": "01110",
    "OUT_KEYPAD_C0": "1", "OUT_KEYPAD_C1": "1", "OUT_KEYPAD_C2": "1", "OUT_KEYPAD_C3": "1",
    "OUT_LED_LEVEL": "0",
}
PNG_HASHES = {
    "raw/io-tty-afterFirst.png": "b0f80b70168f083bec7c6ecefad77bd70d5ce087b46593eb2d550392ee4cc535",
    "raw/io-tty-afterSecond.png": "1ea3d6ad6e9650d43a5b14e243bd145dfa60fedd534179b3fb77a6b960b3331a",
}
NOT_RUN = ["ASCII backspace click", "ASCII form feed", "capacity change/truncation", "Home", "End"]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text())


def inventory(base: Path, receipt: dict) -> None:
    entries = receipt["files"]
    assert len(entries) == 78, "Unexpected retained inventory"
    actual = set()
    for folder in ("raw", "inputs", "analysis"):
        root = base / folder
        assert not root.is_symlink(), "Evidence folder cannot be an external link"
        for path in root.rglob("*"):
            assert not path.is_symlink(), "Evidence cannot use symlinks"
            if path.is_file():
                actual.add(path.relative_to(base).as_posix())
    assert actual == set(entries), "Missing or extra retained evidence"
    for name, entry in entries.items():
        relative = PurePosixPath(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        path = base / name
        assert path.stat().st_size == entry["bytes"] and digest(path) == entry["sha256"], name
    sums = dict((name, sha) for sha, name in (line.split("  ", 1) for line in
                                            (base / "SHA256SUMS").read_text().splitlines()))
    assert sums == {name: entry["sha256"] for name, entry in entries.items()}, "Hash manifest mismatch"


def artifact_map(records: list[dict]) -> dict:
    assert len(records) == 3
    return {item["package"]: item["sha256"] for item in records}


def verify_producer(base: Path, receipt: dict, manifest: dict) -> None:
    producer = receipt["producer"]
    assert producer == {"appSource": APP_SOURCE, "productionEquivalentSource": HELPER_SOURCE,
        "helperSource": HELPER_SOURCE, "harnessSource": HARNESS, "package": PACKAGE,
        "serial": "emulator-5556", "language": "en", "deviceApi": "35", "catalogCountReportedByProducer": 2497}
    assert receipt["expectedArtifacts"] == ARTIFACTS
    assert manifest["gitHead"] == APP_SOURCE and manifest["harnessGitHead"] == HARNESS
    assert manifest["sourceChanges"] == []
    for key, expected in ARTIFACTS.items():
        assert manifest[key] == expected, key
    assert manifest["package"] == PACKAGE and manifest["deviceApi"] == "35"
    assert manifest["appLanguage"] == "en" and manifest["scenario"] == "io-showcase"
    assert manifest["scenarioSources"] == SOURCES
    for name, expected in SOURCES.items():
        assert digest(base / "raw" / ("source-" + name)) == expected, "Frozen source changed: " + name
    for name, expected in INPUTS.items():
        assert digest(base / name) == expected, "Pinned input changed: " + name
    expected = {PACKAGE: ARTIFACTS["appSha256"], PACKAGE + ".test": ARTIFACTS["testSha256"],
                "thalesmms.logisim.mobile.snapshot.e2e.test": ARTIFACTS["snapshotSha256"]}
    assert artifact_map(manifest["installedArtifactsBefore"]) == expected
    post = read_json(base / "raw/post-run-readback-cleanup.json")
    assert artifact_map(post["installedArtifactsAfter"]) == expected
    assert post["appPidBeforeCleanup"] == PID and post["appPidAfterCleanup"] == ""
    assert post["cleanup"] == receipt["cleanup"]
    assert post["cleanup"]["package"] == PACKAGE
    build = read_json(base / "raw/build-receipt.json")
    assert build["gitHead"] == APP_SOURCE and build["appSha256"] == ARTIFACTS["appSha256"]
    assert build["productionEquivalentSource"] == HELPER_SOURCE and build["snapshotHelperSource"] == HELPER_SOURCE
    pre = read_json(base / "raw/preflight-before-adb.json")
    assert pre["appProducerSource"] == APP_SOURCE and pre["artifactManifestSource"] == HELPER_SOURCE
    assert pre["harnessGitHead"] == HARNESS
    assert {name: item["sha256"] for name, item in pre["files"].items()} == {
        "androidApp-e2e.apk": ARTIFACTS["appSha256"],
        "androidApp-e2e-androidTest.apk": ARTIFACTS["testSha256"],
        "androidSnapshot-debug.apk": ARTIFACTS["snapshotSha256"]}
    identities = manifest["apkManifestIdentities"]
    assert identities == pre["identities"]
    for role, package, target in (("app", PACKAGE, PACKAGE), ("test", PACKAGE + ".test", PACKAGE),
             ("snapshot", "thalesmms.logisim.mobile.snapshot.e2e.test", "thalesmms.logisim.mobile.snapshot.e2e.test")):
        assert identities[role]["package"] == package
        if role != "app":
            assert identities[role]["instrumentations"]
        assert all(item["targetPackage"] == target for item in identities[role]["instrumentations"])
    root = read_json(base / "raw/root-manifest.json")
    assert root["source"] == HELPER_SOURCE and root["app_producer_source"] == APP_SOURCE
    assert root["app_production_equivalence"]["changed_files"] == [] and root["localization"] == 2497


def verify_probe(record: dict, expected: dict) -> None:
    assert record["recordType"] == "native-canvas-probes" and record["expected"] == expected
    description = record["observedDescription"]
    for label, value in expected.items():
        observed = re.findall(r"(?<![A-Za-z0-9_])" + re.escape(label) + r": ([01?E]+)(?=,|\.)", description)
        assert observed == [value], "Unexpected real probe: " + label


def verify_progress(base: Path, receipt: dict, manifest: dict) -> None:
    assert receipt["status"] == "FAILED" and receipt["qualification"] == "PARTIAL_PROGRESS_ONLY"
    assert receipt["issue"] == 44 and receipt["scope"] == "single selected Android keyboard-tty case; no overall issue completion"
    case = receipt["case"]
    assert case["name"] == "keyboard-tty" and case["status"] == "failed"
    assert case["selectedCases"] == ["keyboard-tty"] and case["notRun"] == NOT_RUN
    assert case["baselineProbeCount"] == 19 and case["retainedTypedText"] == "AB"
    assert case["keyboardDataSequence"] == ["1000001", "1000010", "0000000"]
    assert case["availableSequence"] == ["1", "1", "0"]
    contract = read_json(base / "inputs/io-actions-v1.json")
    assert contract["baselineOutputs"] == BASELINE and contract["keyboard"]["text"] == "AB"
    selection = manifest["ioCaseSelection"]
    assert selection["selectedCases"] == ["keyboard-tty"] and selection["selectionScope"] == "selected-cases"
    observations = read_json(base / "raw/io-showcase-observations.json")
    assert observations["selectedCases"] == ["keyboard-tty"]
    assert observations["caseResults"] == [{"case": "keyboard-tty", "scenario": "io-showcase-keyboard-tty", "status": "failed"}]
    records = observations["automatedObservations"]
    assert len(records) == 6
    verify_probe(records[0], BASELINE)
    verify_probe(records[1], {"OUT_KEYBOARD_DATA": "1000001", "OUT_KEYBOARD_AVAILABLE": "1"})
    verify_probe(records[2], {"OUT_KEYBOARD_DATA": "1000010", "OUT_KEYBOARD_AVAILABLE": "1"})
    verify_probe(records[4], {"OUT_KEYBOARD_DATA": "0000000", "OUT_KEYBOARD_AVAILABLE": "0"})
    assert records[3] == {"recordType": "visual-review-required", "component": "IO_TTY", "expectedRows": ["A", "", ""]}
    assert records[5] == {"recordType": "visual-review-required", "component": "IO_TTY", "expectedRows": ["AB", "", ""]}
    results = read_json(base / "raw/results.json")
    assert len(results) == 1 and results[0]["status"] == "failed" and results[0]["scenario"] == "io-showcase-keyboard-tty"
    assert "No fresh Android UI hierarchy" in results[0]["error"]
    trace = (base / "raw/io-showcase-keyboard-tty-error.txt").read_text()
    assert 'self.click(case[checkpoint]["controlKey"])' in trace and "wait_node" in trace
    fixture = ET.parse(base / "inputs/io-showcase.circ").getroot()
    assert len(fixture.findall("circuit/comp")) == 54 and len(fixture.findall("circuit/wire")) == 64
    for name in ("io-baseline.circ", "last-document.circ"):
        actual = ET.parse(base / "raw" / name).getroot()
        assert ET.tostring(actual) == ET.tostring(fixture), "Complete exported document changed: " + name
    for phase, steps, row in (("afterFirst", 2, "A"), ("afterSecond", 4, "AB")):
        review = receipt["manualVisualReview"][phase]
        assert review == {"screenshot": "raw/io-tty-" + phase + ".png", "expectedSteps": steps, "observedFirstTtyRow": row}
        tree = ET.parse(base / "raw" / ("io-tty-" + phase + "-ui.xml")).getroot()
        assert sum(n.get("text") == "Steps " + str(steps) for n in tree.iter("node")) == 1
    assert receipt["manualVisualReview"]["method"] == "view_image on original full PNGs; no crop, edit or automated pixel oracle"
    for name, expected in PNG_HASHES.items():
        assert digest(base / name) == expected, "Reviewed image changed: " + name


def snapshot_number(path: Path) -> int:
    return int(path.stem.rsplit("-", 1)[1])


def verify_snapshots(base: Path, receipt: dict) -> None:
    paths = sorted((base / "raw").glob("focus-snapshot-*.json"), key=snapshot_number)
    assert len(paths) == 10
    nonces = set()
    ab_captures = []
    for path in paths:
        record = read_json(path)
        assert record["status"] == "complete" and record["appPidBefore"] == record["appPidAfter"] == PID
        assert record["expectedPackage"] == PACKAGE and record["expectedScreenPid"] is None
        assert record["adbReturncode"] == 0 and record["instrumentationCodes"] == ["-1"]
        assert record["duplicateBundleKeys"] == [] and 0 < record["instrumentationTimeoutSeconds"] <= 8
        nonce = record["nonce"]
        assert re.fullmatch(r"[0-9a-f]{32}", nonce) and nonce not in nonces
        nonces.add(nonce)
        stdout = (path.parent / record["stdout"]).read_text()
        assert not (path.parent / record["stderr"]).read_bytes()
        assert re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", stdout, re.MULTILINE) == ["-1"]
        lines = stdout.splitlines()
        terminal = next(i for i, line in enumerate(lines) if line.strip() == "INSTRUMENTATION_CODE: -1")
        assert not any(line.startswith("INSTRUMENTATION_") for line in lines[terminal + 1:]), "Protocol after terminal"
        assert not re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg=|Process crashed", stdout)
        fields = re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)$", stdout, re.MULTILINE)
        assert len(fields) == len(dict(fields))
        bundle = dict(fields)
        assert bundle == record["bundle"]
        assert bundle["snapshotNonce"] == nonce and bundle["snapshotExpectedPackage"] == PACKAGE
        assert bundle["snapshotCaptureState"] == "COMPLETE" and bundle["snapshotIncompleteTreeAttempts"] == "0"
        assert "snapshotError" not in bundle
        xml = ET.fromstring(base64.b64decode(bundle["snapshotXmlBase64"], validate=True))
        saved = ET.parse(path.parent / record["xml"]).getroot()
        assert ET.tostring(xml) == ET.tostring(saved)
        assert xml.get("snapshot-nonce") == nonce and xml.get("uptime-millis") == bundle["snapshotUptimeMillis"]
        assert int(bundle["snapshotTraversalNodeCount"]) == len(list(xml.iter("node")))
        fields = [n for n in saved.iter("node") if n.get("class", "").endswith("EditText")]
        if snapshot_number(path) in (5, 6):
            assert all(n.get("package") == PACKAGE for n in fields), "AB editor belongs to another app"
            assert [(n.get("text"), n.get("focused")) for n in fields] == [("AB", "true"), ("IO_KEYBOARD", "false")]
            ab_captures.append(snapshot_number(path))
    assert ab_captures == [5, 6]
    assert receipt["snapshotSummary"] == {"completeCaptures": 10, "typedRootUnavailable": 0,
        "typedIncompleteTree": 0, "pidBeforeAfterAllCaptures": PID, "sameScreenRetryClaimed": False}


def rectangle(node) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    assert match, "Missing observed bounds"
    result = tuple(map(int, match.groups()))
    assert result[2] > result[0] and result[3] > result[1]
    return result


def contains(outer, inner) -> bool:
    return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


def verify_selector(base: Path, receipt: dict) -> None:
    finding = receipt["selectorFinding"]
    assert finding["scope"] == "deterministic replay on both retained final hierarchies only"
    assert finding["label"] == "ASCII backspace" and finding["runtimeBackspaceClickClaimed"] is False
    assert finding["originalNodesMatches"] == 0 and finding["offlineOnlyScrollBottomPlusOneMatches"] == 1
    assert finding["labelBounds"] == [518, 1158, 795, 1211]
    assert finding["clickableAncestorBounds"] == [455, 1122, 858, 1248]
    assert finding["scrollBounds"] == [21, 1121, 1059, 1247]
    census = read_json(base / "analysis/selector-census.json")
    assert len(census) == 2
    for filename, replay in zip(("failure-ui.xml", "io-showcase-keyboard-tty-ui.xml"), census):
        root = ET.parse(base / "raw" / filename).getroot()
        parents = {child: parent for parent in root.iter() for child in parent}
        scopes = [n for n in root.iter("node") if n.get("resource-id") == "editor-inspector-sheet" and n.get("package") == PACKAGE]
        assert len(scopes) == 1
        scope = scopes[0]
        labels = [n for n in scope.iter("node") if n.get("text") == "ASCII backspace"]
        assert len(labels) == 1
        label = labels[0]
        assert label.get("package") == PACKAGE and label.get("enabled") == "true"
        target = label
        while target.get("clickable") != "true" and target in parents:
            target = parents[target]
        assert target is not label and target.get("clickable") == "true" and target.get("enabled") == "true"
        assert target.get("package") == PACKAGE and any(n is target for n in scope.iter())
        scroll = parents[target]
        assert scroll.get("class") == "android.widget.HorizontalScrollView" and scroll.get("scrollable") == "true"
        assert rectangle(label) == tuple(finding["labelBounds"])
        assert rectangle(target) == tuple(finding["clickableAncestorBounds"])
        assert rectangle(scroll) == tuple(finding["scrollBounds"])
        ancestor = label
        while ancestor is not None:
            assert ancestor.get("visible-to-user") != "false" and ancestor.get("enabled") != "false"
            if ancestor is not label and ancestor.get("scrollable") == "true":
                assert contains(rectangle(ancestor), rectangle(label)), "Label itself is clipped"
            ancestor = parents.get(ancestor)
        window = root if root.get("bounds") else next(root.iter("node"), None)
        assert window is not None and contains(rectangle(window), rectangle(label)), "Label is outside its window"
        assert contains(rectangle(target), rectangle(label)) and not contains(rectangle(scroll), rectangle(target))
        l, t, r, b = rectangle(scroll)
        assert contains((l, t, r, b + 1), rectangle(target))
        assert replay["file"] == filename and replay["label"] == label.attrib and replay["clickTarget"] == target.attrib
        assert replay["scrollAncestor"] == scroll.attrib and replay["scopeTag"] == "editor-inspector-sheet"
        assert replay["scopeContainsTarget"] and replay["labelVisible"] and not replay["targetVisible"]
        assert replay["nodesBefore"] == 0 and replay["nodesAfterOnlyScrollBottomPlusOne"] == 1


def verify(base: Path = BASE) -> dict:
    base = Path(base)
    receipt = read_json(base / "receipt.json")
    inventory(base, receipt)
    manifest = read_json(base / "raw/manifest.json")
    verify_producer(base, receipt, manifest)
    verify_progress(base, receipt, manifest)
    verify_snapshots(base, receipt)
    verify_selector(base, receipt)
    return {"verification": "PASS", "retainedCaseStatus": "FAILED", "qualification": "PARTIAL_PROGRESS_ONLY",
            "files": len(receipt["files"]), "completeSnapshots": 10, "abSnapshots": [5, 6],
            "note": "No Native execution, helper retry, pixel oracle or full issue completion is inferred."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=BASE)
    print(json.dumps(verify(parser.parse_args().base), indent=2))
