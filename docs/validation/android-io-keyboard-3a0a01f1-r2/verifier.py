"""Replay this retained keyboard case without Android, Git, or current sources."""
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
SNAPSHOT_PIN = "5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c"
HARNESS = "b56aa75e776477833cc038f8d6cb5ea28d1f598d"
SNAPSHOT_MANIFEST_SHA = "6217ae14db5a8fe7d108b150f4b6f97f6dfa9a14ee69f4842b78dee9d39e8c99"
APK_HASHES = {PACKAGE: "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
              PACKAGE + ".test": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
              HELPER: "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c"}
SOURCE_HASHES = {
    "e2e-android.py": "2d5578df30d1e32d0a6e29c2890c78f48f8792cffab84cc76761c2f2db169466",
    "android_ui.py": "8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634",
    "android_editing_scenarios.py": "0b97319487924cf371eec29b8f0b9c7ec7a553230ea5ebd9149ff5d041556673",
    "user_scenario_contract.py": "858dc5e43c70a28898afaa16341b786b203696153cc33c2e43c8314a4572c9eb",
    "android_inspector_scenarios.py": "74e686a0448e4e6c29729f19cbf04c67922368bab65ebb69a7d238004582f40c",
    "android_io_showcase_scenarios.py": "d6a8c2c58b671cdb5a3a87e0189883e4bc60e7e27f4e6c5539ece4320f023396",
    "android_keyboard_controls.py": "f26132adcf2bb4fb04430ed22df782a384d171089a21b64d49e933624d4a1612",
    "android_portable_scenarios.py": "6e5888e73ddb558d038f22c45b517e182a46a991f43d878427a65f724ee5c7b4",
    "android_visual_export34_scenarios.py": "aa2c353709fd35398f39a32592a4e8b1a2029a21e042702678b14b772a036763"}
NOT_SELECTED = ["signals", "dip-last", "pointer-controls", "dimensions"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text())


def inventory(base):
    recorded = load(base / "inventory.json")
    assert len(recorded) == 155
    actual = set()
    for directory in (base / "raw", base / "oracle-snapshot"):
        assert directory.is_dir() and not directory.is_symlink()
        for path in directory.rglob("*"):
            assert not path.is_symlink(), "Evidence cannot use symlinks"
            if path.is_file():
                actual.add(path.relative_to(base).as_posix())
    assert actual == set(recorded), "Evidence inventory differs"
    for name, expected in recorded.items():
        relative = Path(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        path = base / relative
        assert path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"], name
    return len(actual)


def snapshots(base):
    root = base / "oracle-snapshot"
    manifest = load(root / "manifest.json")
    assert digest(root / "manifest.json") == SNAPSHOT_MANIFEST_SHA, "Historical snapshot manifest changed"
    assert manifest["schemaVersion"] == 1 and manifest["sourcePin"] == SNAPSHOT_PIN
    assert len(manifest["files"]) == 5
    for path, expected in manifest["files"].items():
        assert digest(root / path) == expected, path
    return root, runpy.run_path(str(root / "scripts/io_showcase_contract.py"))


def packages(entries):
    assert len(entries) == 3
    result = {entry["package"]: entry["sha256"] for entry in entries}
    assert result == APK_HASHES, "Installed APK identity changed"
    return result


def rect(node):
    values = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    assert values
    result = tuple(map(int, values.groups()))
    assert result[0] < result[2] and result[1] < result[3]
    return result


def inside(bounds, point):
    return bounds[0] <= point[0] < bounds[2] and bounds[1] <= point[1] < bounds[3]


def action_tree(raw, record, label_text):
    root = ET.parse(raw / record["tree"]).getroot()
    assert root.tag == "hierarchy"
    scopes = [n for n in root.iter("node") if n.get("resource-id", "").split(":id/")[-1] == "editor-inspector-sheet"]
    assert len(scopes) == 1
    scope = scopes[0]
    labels = [n for n in scope.iter("node") if n.get("text") == label_text]
    assert len(labels) == 1
    label = labels[0]
    parents = {child: parent for parent in root.iter() for child in parent}
    chain = []
    current = label
    while current is not scope:
        chain.append(current)
        assert current in parents
        current = parents[current]
    assert all(n.get("package") == PACKAGE and n.get("enabled") != "false" and
               n.get("visible-to-user") != "false" for n in chain)
    target = next((n for n in chain if n.get("clickable") == "true"), None)
    row = next((n for n in chain if n.get("class", "").endswith("HorizontalScrollView") and n.get("scrollable") == "true"), None)
    assert target is not None and row is not None and chain.index(target) < chain.index(row)
    assert target.get("enabled") == "true" and label.get("enabled") == "true"
    lr, tr, rr = rect(label), rect(target), rect(row)
    assert label.get("bounds") == record["labelBounds"] and target.get("bounds") == record["targetBounds"] and row.get("bounds") == record["rowBounds"]
    assert tr[0] <= lr[0] < lr[2] <= tr[2] and tr[1] <= lr[1] < lr[3] <= tr[3]
    assert rr[1] < lr[1] < lr[3] < rr[3]
    assert record["commandCompleted"] is True
    if record["stage"] == "tap":
        assert rr[0] < lr[0] < lr[2] < rr[2], "Tap label is horizontally clipped"
        point = ((lr[0] + lr[2]) // 2, (lr[1] + lr[3]) // 2)
        assert list(point) == record["point"] and inside(tr, point)
        assert all(inside(rect(n), point) for n in chain if n.get("scrollable") == "true")
    else:
        assert record["stage"] == "reveal-horizontal" and lr[2] == rr[2], "Reveal must use the observed right boundary"
        assert record["start"][1] == record["end"][1] == (rr[1] + rr[3]) // 2
        assert record["start"][0] > record["end"][0]
        assert inside(rr, record["start"]) and inside(rr, record["end"])
    return root


def sdk(raw):
    files = sorted(raw.glob("focus-snapshot-*.json"))
    assert len(files) == 26
    nonces, keyboard_values = set(), set()
    for path in files:
        record = load(path)
        assert record["status"] == "complete" and record["adbReturncode"] == 0
        assert record["expectedPackage"] == PACKAGE
        assert record["target"] == HELPER + "/dev.logisim.mobile.documents.UiSnapshotInstrumentation"
        assert record["appPidBefore"] == record["appPidAfter"] == "25265"
        nonce = record["nonce"]
        assert re.fullmatch(r"[0-9a-f]{32}", nonce) and nonce not in nonces
        nonces.add(nonce)
        assert record["instrumentationCodes"] == ["-1"] and not record["duplicateBundleKeys"]
        values = record["bundle"]
        assert values["snapshotNonce"] == nonce and values["snapshotExpectedPackage"] == PACKAGE
        assert values["snapshotCaptureState"] == "COMPLETE" and "snapshotError" not in values
        assert values["snapshotIncompleteTreeAttempts"] == "0"
        result = (raw / record["stdout"]).read_text()
        assert re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", result, re.MULTILINE) == ["-1"]
        assert not re.search(r"INSTRUMENTATION_(?:FAILED|ABORTED)|shortMsg=|Process crashed|INSTRUMENTATION_STATUS: Error", result)
        assert not (raw / record["stderr"]).read_text().strip()
        terminal = re.search(r"^INSTRUMENTATION_CODE: -1\s*$", result, re.MULTILINE)
        assert terminal and not re.search(r"^INSTRUMENTATION_", result[terminal.end():], re.MULTILINE)
        pairs = re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)$", result, re.MULTILINE)
        assert len({k for k, _ in pairs}) == len(pairs) and dict(pairs) == values
        tree = ET.fromstring(base64.b64decode(values["snapshotXmlBase64"], validate=True))
        saved = ET.parse(raw / record["xml"]).getroot()
        assert ET.tostring(tree) == ET.tostring(saved)
        assert tree.tag == "hierarchy" and tree.get("snapshot-nonce") == nonce
        assert tree.get("uptime-millis") == values["snapshotUptimeMillis"]
        scopes = [n for n in saved.iter("node") if n.get("resource-id", "").split(":id/")[-1] == "editor-inspector-sheet"]
        assert len(scopes) <= 1
        if scopes:
            edits = [n for n in scopes[0].iter("node") if n.get("class", "").endswith("EditText")]
            keyboards = [(n, [c.get("text", "") for c in n.iter("node")]) for n in edits
                         if any(re.fullmatch(r"IO_KEYBOARD · \d+/\d+", c.get("text", "")) for c in n.iter("node"))]
            labels = [n for n in edits if any(c.get("text") == "Label" for c in n.iter("node"))]
            assert len(keyboards) == len(labels) == 1, "Inspector field roles are ambiguous"
            assert labels[0].get("text") == "IO_KEYBOARD", "Component Label field changed"
            field, captions = keyboards[0]
            assert field.get("package") == PACKAGE and field.get("enabled") == "true"
            for value, caption in (("AB", "IO_KEYBOARD · 2/16"), ("ABCD", "IO_KEYBOARD · 4/4")):
                if field.get("text") == value and caption in captions:
                    keyboard_values.add(value)
    assert keyboard_values == {"AB", "ABCD"}, "Direct values AB and ABCD in the captioned Keyboard field are required"
    return len(files)


def verify(base=None):
    base = Path(base or Path(__file__).resolve().parent)
    file_count = inventory(base)
    historical_root, historical = snapshots(base)
    raw = base / "raw"
    manifest, preflight = load(raw / "manifest.json"), load(raw / "preflight-before-ui.json")
    assert manifest["package"] == PACKAGE and manifest["serial"] == "emulator-5556"
    assert manifest["gitHead"] == PRODUCER and manifest["harnessGitHead"] == HARNESS
    assert manifest["deviceApi"] == "35" and manifest["appLanguage"] == "en" and manifest["sourceChanges"] == []
    assert (manifest["appSha256"], manifest["testSha256"], manifest["snapshotSha256"]) == tuple(APK_HASHES.values())
    selection = {"selectionScope": "selected-cases", "selectedCases": ["keyboard-tty"], "notSelectedCases": NOT_SELECTED}
    assert manifest["ioCaseSelection"] == selection
    assert preflight["harnessHead"] == HARNESS and preflight["catalogKeys"] == 2497 and preflight["clientMatchesSource"] == SNAPSHOT_PIN
    assert preflight["androidUiBlob"] == "b9a770448c8dd8b21e721141ce14a5a025dc90d4"
    assert list(preflight["artifactHashes"].values()) == list(APK_HASHES.values())
    assert manifest["scenarioSources"] == SOURCE_HASHES
    for name, expected in SOURCE_HASHES.items():
        assert digest(raw / ("source-" + name)) == expected, "Frozen source changed: " + name
    assert manifest["scenarioContractSha256"] == digest(historical_root / "fixtures/user-scenarios/editor-actions-v1.json")
    packages(manifest["installedArtifactsBefore"])
    packages(manifest["installedArtifactsAfter"])
    cleanup = load(raw / "post-run-readback-cleanup.json")
    packages(cleanup["installedArtifactsAfter"])
    assert cleanup["appPidBeforeCleanup"] == "25265" and cleanup["appPidAfterCleanup"] == ""
    assert cleanup["cleanup"]["package"] == PACKAGE and cleanup["cleanup"]["operation"] == "am force-stop only isolated app after preserving final state"
    build = load(raw / "build-receipt.json")
    assert build["gitHead"] == PRODUCER and build["appSha256"] == APK_HASHES[PACKAGE]
    assert build["productionEquivalentSource"] == build["snapshotHelperSource"] == SNAPSHOT_PIN
    assert build["rootManifestSha256"] == digest(raw / "root-artifact-manifest.json")
    root_manifest = load(raw / "root-artifact-manifest.json")
    assert root_manifest["source"] == SNAPSHOT_PIN and root_manifest["app_producer_source"] == PRODUCER
    assert root_manifest["app_production_equivalence"]["changed_files"] == []
    result = load(raw / "results.json")
    assert result == [{"scenario": "io-showcase-keyboard-tty", "status": "passed", "seconds": 223.79}]
    observations = load(raw / "io-showcase-observations.json")
    assert observations["scope"] == "native-mounted-editor"
    assert {k: observations[k] for k in selection} == selection
    assert observations["caseResults"] == [{"case": "keyboard-tty", "scenario": "io-showcase-keyboard-tty", "status": "passed"}]
    assert observations["qualification"] == "No overall #44 completion is inferred from automated probes alone."
    records = observations["automatedObservations"]
    assert [r["recordType"] for r in records] == ["native-canvas-probes", "native-canvas-probes", "native-canvas-probes", "visual-review-required", "native-canvas-probes", "visual-review-required", "keyboard-control-observation", "native-canvas-probes", "visual-review-required", "keyboard-control-observation", "keyboard-control-observation", "native-canvas-probes", "visual-review-required"]
    contract = historical["load_io_contract"]()
    probes = [contract["baselineOutputs"], {"OUT_KEYBOARD_DATA": "1000001", "OUT_KEYBOARD_AVAILABLE": "1"}, {"OUT_KEYBOARD_DATA": "1000010", "OUT_KEYBOARD_AVAILABLE": "1"}, {"OUT_KEYBOARD_DATA": "0000000", "OUT_KEYBOARD_AVAILABLE": "0"}, {"OUT_KEYBOARD_DATA": "0001000", "OUT_KEYBOARD_AVAILABLE": "1"}, {"OUT_KEYBOARD_DATA": "0001100", "OUT_KEYBOARD_AVAILABLE": "1"}]
    for record, expected in zip((r for r in records if r["recordType"] == "native-canvas-probes"), probes):
        assert record["expected"] == expected
        historical["assert_probe_description"](record["observedDescription"], expected)
    assert len(contract["baselineOutputs"]) == 19
    assert [r["expectedRows"] for r in records if r["recordType"] == "visual-review-required"] == [["A", "", ""], ["AB", "", ""], ["A", "", ""], ["", "", ""]]
    controls = [r for r in records if r["recordType"] == "keyboard-control-observation"]
    assert [(r["key"], r["stage"]) for r in controls] == [("io_ascii_backspace", "tap"), ("io_ascii_form_feed", "reveal-horizontal"), ("io_ascii_form_feed", "tap")]
    assert controls[0]["point"] == [656, 1184] and controls[1]["start"] == [851, 1184] and controls[1]["end"] == [229, 1184] and controls[2]["point"] == [865, 1184]
    for control, label in zip(controls, ("ASCII backspace", "ASCII form feed", "ASCII form feed")):
        action_tree(raw, control, label)
    assert controls[1]["tree"] != controls[2]["tree"] and digest(raw / controls[1]["tree"]) != digest(raw / controls[2]["tree"])
    fixture = (historical_root / contract["fixture"]).read_bytes()
    assert observations["fixtureSha256"] == hashlib.sha256(fixture).hexdigest()
    oracle = historical["IoShowcaseOracle"](fixture, load(historical_root / "examples/io-showcase.targets.json"), contract)
    baseline = (raw / "io-baseline.circ").read_bytes()
    assert baseline == fixture
    oracle.assert_document(baseline.decode())
    oracle.expect({"IO_KEYBOARD": {"buflen": "4"}})
    capacity = (raw / "io-keyboard-capacity.circ").read_bytes()
    oracle.assert_document(capacity.decode())
    assert capacity == (raw / "last-document.circ").read_bytes()
    for payload in (baseline, capacity):
        project = ET.fromstring(payload)
        assert len(project.findall(".//comp")) == 54 and len(project.findall(".//wire")) == 64
    for stem, data, available in (("afterFirst", "1000010", "1"), ("afterSecond", "0000000", "0"), ("backspace", "0000000", "0"), ("formFeed", "0000000", "0")):
        tree = ET.parse(raw / ("io-tty-" + stem + "-ui.xml")).getroot()
        descriptions = [n.get("content-desc", "") for n in tree.iter("node") if n.get("content-desc", "").startswith("Circuit area.")]
        assert len(descriptions) == 1
        expected = dict(contract["baselineOutputs"], OUT_KEYBOARD_DATA=data, OUT_KEYBOARD_AVAILABLE=available)
        historical["assert_probe_description"](descriptions[0], expected)
    sdk_count = sdk(raw)
    receipt = load(base / "receipt.json")
    assert receipt.get("status") == "PASS" and receipt.get("qualification") == "keyboard-tty-case-only", "Receipt must not promote full issue44"
    assert receipt.get("scope") == "native-mounted-editor-keyboard-tty"
    return {"status": "PASS", "scope": "retained-keyboard-tty-case-only", "filesVerified": file_count, "sdkComplete": sdk_count, "sdkTyped": 0, "backspaceReveals": 0, "formFeedReveals": 1, "fullIssue44Qualified": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path)
    print(json.dumps(verify(parser.parse_args().base), indent=2))
