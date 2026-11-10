#!/usr/bin/env python3
"""Verify the preserved R2 iPhone Editor36 failure without accessing a live device."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import tarfile

ROOT = Path(__file__).resolve().parent
APP_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
HARNESS_SOURCE = "e8354b17a2d1fcb5b0592c4810cd5a3d95622571"
MANIFEST_SHA = "7e83088f9d1ccee3c268e9dec954db9c351b47db9cd01b9fb652e8f1235b7b76"
BUNDLE_SHA = "813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef"
EXECUTABLE_SHA = "72f025668535a86c479eda81c2bbcba9fb65e0f1cef29598d869dd78c4977d48"
CONTRACT_SHA = "9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9"
SWIFT_MAP_SHA = "7b87312b65803a558ce2033a2ce2f7ef8caa7bce1a8173c6c45ca92c48137e6a"
UUID = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"
SELECTOR = "EditorEndToEndTests/EditorEndToEndTests/testEditingConnectionsHistoryAndViewport"
CHECKPOINTS = [(2, "empty"), (7, "components"), (9, "north"),
               (11, "components"), (13, "north"), (15, "components")]

spec = importlib.util.spec_from_file_location("editor36_archive_oracle", ROOT / "oracle.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def decode(data):
    return json.loads(data)


def check_bytes(data, record):
    assert len(data) == record["bytes"] and sha(data) == record["sha256"], "Byte identity mismatch"


def safe_name(name):
    p = PurePosixPath(name)
    assert name and not p.is_absolute() and ".." not in p.parts and p.as_posix() == name
    return name


def read_archive(path, index, record):
    check_bytes(path.read_bytes(), record)
    assert record["path"] == "raw.tar.gz"
    assert record["memberCount"] == len(index) == 715
    members = {}
    with tarfile.open(path, "r:gz") as archive:
        for item in archive:
            name = safe_name(item.name)
            assert item.isfile() and not item.issym() and not item.islnk()
            assert name not in members and name in index, "Unexpected or duplicate archive member"
            assert 0 <= item.size <= 2_000_000, "Oversized archive member"
            data = archive.extractfile(item).read()
            check_bytes(data, index[name])
            members[name] = data
    assert members.keys() == index.keys()
    assert sum(len(x) for x in members.values()) < 10_000_000
    return members


def check_device(device):
    assert device["udid"] == UUID
    assert device["deviceTypeIdentifier"] == "com.apple.CoreSimulator.SimDeviceType.iPhone-17e"
    assert device["runtime"] == "com.apple.CoreSimulator.SimRuntime.iOS-27-0"
    assert device["state"] == "Booted"


def check_artifact(artifact):
    assert artifact["bundle_id"] == "thalesmms.logisim.mobile"
    assert artifact["bundle_tree_sha256"] == BUNDLE_SHA
    assert artifact["executable_sha256"] == EXECUTABLE_SHA
    assert artifact["version"] == "0.2.0" and artifact["build"] == "2"


def verify_evidence(receipt, members):
    """Check semantics independently of the mutable archive hash inventory."""
    r = decode(members["results.json"])
    pre = decode(members["preflight/preflight.json"])
    post = decode(members["independent-postflight.json"])
    producer_data = members["preflight/producer-artifact.json"]
    assert sha(producer_data) == MANIFEST_SHA
    producer = decode(producer_data)
    run = decode(members["harness/Run.json"])
    assert receipt["schema"] == 1 and receipt["issue"] == 36
    for key, value in {
        "status": "FAIL", "nativeExecutionGate": "NATIVE_FAIL", "exitCode": 65,
        "testCount": 1, "failureCount": 1, "nativeRunCount": 1,
        "passedSteps": 16, "attemptedFailureStep": 17, "failureAction": "connectPorts",
        "testTimeoutSeconds": 900, "selectedTest": SELECTOR, "caseSeconds": 419.831,
        "harnessSource": HARNESS_SOURCE, "appProducerSource": APP_SOURCE,
        "producerManifestSHA256": MANIFEST_SHA,
    }.items():
        assert receipt[key] == value, ("Receipt identity or scope changed", key)
    assert receipt["qualification"] == "FAIL; selected case, no issue completion"
    assert r["status"] == r["qualification_gate"] == r["execution"]["status"] == "FAIL"
    assert r["native_execution_gate"] == "NATIVE_FAIL" and r["execution"]["exit_code"] == 65
    assert r["selected_tests"] == r["execution"]["selection"] == [SELECTOR]
    assert r["alpha"] is False and r["physical_device"] is False
    assert r["test_timeout_seconds"] == 900 and r["declared_baseline_sha"] == APP_SOURCE
    assert r["build"] == {"status": "PASS", "exit_code": 0}
    assert r["resource_preflight"] == r["external_peer_preflight"] == "PASS"
    assert r["simulator"] == run["simulatorUDID"] == UUID
    assert receipt["runId"] == r["run_id"] == run["run_id"] == "0905d533c03f"
    assert receipt["started"] == r["started"] and receipt["finished"] == r["finished"]
    assert receipt["device"] == r["device"]
    for d in [receipt["device"], pre["device"], post["device"]]:
        check_device(d)
    for a in [receipt["appArtifact"], r["artifact"], r["main_app_installation"]["installed"]]:
        check_artifact(a)
    assert r["main_app_installation"]["status"] == "REUSED_IDENTICAL"
    assert pre["status"] == post["status"] == "PASS"
    assert pre["harnessSource"] == HARNESS_SOURCE
    assert pre["producerSource"] == producer["source_sha"] == producer["app_producer_source"] == APP_SOURCE
    assert producer["source_dirty"] is False
    assert pre["manifestSHA256"] == post["manifestSHA256"] == MANIFEST_SHA
    assert pre["selectedTest"] == SELECTOR and pre["testTimeoutSeconds"] == 900
    assert pre["contractSHA256"] == CONTRACT_SHA
    assert post["sourceChecks"] == {
        "harnessSource": HARNESS_SOURCE, "harnessClean": True,
        "producerSource": APP_SOURCE, "producerClean": True,
    }
    file_records = producer["artifact"]["files"]
    assert len(file_records) == producer["artifact"]["file_count"] == 12
    assert len({f["path"] for f in file_records}) == 12
    tree = hashlib.sha256()
    for f in sorted(file_records, key=lambda item: PurePosixPath(item["path"]).parts):
        safe_name(f["path"])
        assert re.fullmatch("[0-9a-f]{64}", f["sha256"]) and f["bytes"] > 0
        tree.update((f["path"] + "\0" + f["sha256"] + "\n").encode())
    assert tree.hexdigest() == BUNDLE_SHA
    assert pre["artifact"]["files"] == file_records
    for key in ["installed", "producer"]:
        assert post[key]["fileCount"] == 12 and post[key]["files"] == file_records
        assert post[key]["bundleSHA256"] == BUNDLE_SHA and post[key]["signature"] == "PASS"
    assert post["catalogChecks"] == producer["catalog"]["files"] == pre["catalog"]["files"]
    assert producer["catalog"]["message_count"] == pre["catalog"]["message_count"] == 2497
    assert len(post["catalogChecks"]) == 3
    catalog = members["harness/Catalog.json"]
    assert sha(catalog) == r["catalog"]["sha256"] and len(decode(catalog)) == 2497
    source_map = r["harness_sources"]
    assert sha(json.dumps(source_map, sort_keys=True, separators=(",", ":")).encode()) == SWIFT_MAP_SHA
    actual_sources = {PurePosixPath(n).name: sha(data) for n, data in members.items()
                      if n.startswith("harness/") and n.count("/") == 1 and n.endswith(".swift")}
    assert len(actual_sources) == 18 and source_map == actual_sources
    compiled_names = re.findall(r"^\s+- ([A-Za-z0-9]+\.swift)$", members["harness/project.yml"].decode(), re.M)
    assert len(compiled_names) == len(set(compiled_names)) == 17
    compiled = {name: actual_sources[name] for name in compiled_names}
    assert compiled == pre["swiftSources"]
    compiled_sha = sha(json.dumps(compiled, sort_keys=True, separators=(",", ":")).encode())
    assert compiled_sha == "2398eb0627b40c68139f3d7c0fb94dcb6f18e8e4bb940d1bea19f848f327a413"
    assert receipt["swiftSources"] == {"compiledCount": 17, "preparedCount": 18,
        "compiledMapSHA256": compiled_sha, "preparedMapSHA256": SWIFT_MAP_SHA}
    contract_bytes = members["harness/editor-actions-v1.json"]
    assert sha(contract_bytes) == CONTRACT_SHA
    contract = decode(contract_bytes)
    assert contract["schemaVersion"] == 1 and len(contract["scenarios"]) == 1
    steps = contract["scenarios"][0]["steps"]
    assert len(steps) == 62 and steps[15]["action"] == "connectPorts"
    attachment_groups = decode(members["attachments/manifest.json"])
    attachments = [a for group in attachment_groups for a in group.get("attachments", [])]
    assert len(attachments) == receipt["attachments"]["nativeTotal"] == 669
    assert receipt["attachments"]["archivedExcludingVideo"] == 668
    assert receipt["attachments"]["videoRetainedLocally"] is True
    assert len({a["exportedFileName"] for a in attachments}) == 669
    exports, notes = [], []
    for a in attachments:
        assert a["deviceId"] == UUID and a["deviceName"] == "iPhone 17e"
        name = a["exportedFileName"]
        title = a["suggestedHumanReadableName"]
        safe_name(name)
        if name.endswith(".mp4"):
            assert "attachments/" + name not in members
            continue
        assert "attachments/" + name in members
        if found := re.match(r"observed-editor-export-(\d+)_", title):
            exports.append((int(found[1]), name))
        if found := re.match(r"contract-step-(\d+)_", title):
            notes.append((int(found[1]), name))
    exports.sort()
    notes.sort()
    assert [n for n, _ in exports] == list(range(1, 7))
    assert [n for n, _ in notes] == list(range(1, 17))
    assert receipt["stepNotes"] == [{"step": n, "attachment": name} for n, name in notes]
    assert oracle.check_step_notes([(n, members["attachments/" + name]) for n, name in notes],
                                   steps, native_pass=False) == 16
    assert len(receipt["exports"]) == len(CHECKPOINTS)
    history, by_checkpoint = [], {}
    for index, ((export_index, name), (step, checkpoint), record) in enumerate(
            zip(exports, CHECKPOINTS, receipt["exports"]), 1):
        assert record["exportIndex"] == export_index == index
        assert (record["contractStep"], record["checkpoint"]) == (step, checkpoint)
        assert record["attachment"] == name
        assert steps[step - 1] == {"action": "assertDocument", "checkpoint": checkpoint}
        raw = members["attachments/" + name]
        check_bytes(raw, record)
        _, identities = oracle.check_xml(raw, contract["snapshots"][checkpoint], checkpoint)
        history.append((checkpoint, identities))
        if checkpoint in by_checkpoint:
            assert raw == by_checkpoint[checkpoint], "Undo/Redo did not restore complete XML"
        by_checkpoint[checkpoint] = raw
    oracle.check_identity_history(history)
    log = members["test.log"].decode()
    observed_steps = re.findall(r"(?m)^\s+t =\s+[0-9.]+s contract step (\d+): (\w+)$", log)
    assert observed_steps == [(str(i), steps[i - 1]["action"]) for i in range(1, 18)]
    assert re.search(r"NativeUIHarness\.swift:118: error:.*Failed to get matching snapshot:.*a11y-circuit-list", log)
    assert "failed (419.831 seconds)" in log and "** TEST EXECUTE FAILED **" in log
    assert "Executed 1 test, with 1 failure (0 unexpected)" in log
    assert not re.search(r"Test Case .* passed \(", log)
    final_tree = members["attachments/5BD8707B-B8BA-4884-8C02-20D6D8971206.txt"].decode()
    assert "pid: 4675" in final_tree and "editor-canvas" in final_tree
    assert "a11y-circuit-list" not in final_tree and "editor-tool-sheet" not in final_tree
    native_identities = {
        (100, 100): ("c1", "E2E_IN"),
        (200, 100): ("c2", "E2E_NOT"),
        (300, 100): ("c3", "E2E_OUT"),
    }
    for location, (identity, label) in native_identities.items():
        assert history[-1][1][location] == identity
        assert f"identifier: 'canvas-component:{identity}', label: '{label}'" in final_tree
    assert 'Tap "port:2:c1:out" Button' in log
    assert 'Tap "port:2:c2:in" Button' in log
    assert 'Tap "port:2:c2:out" Button' in log
    assert 'Tap "port:2:c3:in" Button' not in log
    assert receipt["runLabel"] == "R2"
    return {"status": "FAIL_PRESERVED", "nativeTests": 1, "failures": 1,
            "completedSteps": 16, "xmlExports": 6, "nextStep": 17}


def verify_package(root=ROOT):
    root = Path(root)
    receipt = decode((root / "receipt.json").read_bytes())
    for name, info in receipt["publicFiles"].items():
        path = root / safe_name(name)
        assert path.is_file() and not path.is_symlink()
        check_bytes(path.read_bytes(), info)
    index = decode((root / "raw-members.json").read_bytes())
    members = read_archive(root / "raw.tar.gz", index, receipt["rawArchive"])
    verify_source_comparison(decode((root / "source-comparison.json").read_bytes()), members)
    return verify_evidence(receipt, members)


def verify_source_comparison(comparison, members):
    """Bind the eight retained source differences without requiring the R1 repository."""
    prepared = decode(members["results.json"])["harness_sources"]
    pre = decode(members["preflight/preflight.json"])
    assert comparison["R1HarnessSource"] == "ba9d9682248fca28adbf5fc050f018156c7edc5c"
    assert comparison["R1OriginalReceiptCommit"] == "4c60e16e"
    assert comparison["R2HarnessSource"] == HARNESS_SOURCE
    assert comparison["compiledSwiftCount"] == 17 and comparison["preparedSwiftCount"] == 18
    assert comparison["compiledSwift"] == pre["swiftSources"]
    assert comparison["preparedSwift"] == prepared
    deltas = comparison["differingPreparedSources"]
    assert len(deltas) == 8
    assert {d["name"] for d in deltas} == {
        "AccessibilityUITests.swift", "EditorEndToEndTests.swift", "FilesEndToEndTests.swift",
        "FocusEndToEndTests.swift", "IoEndToEndTests.swift", "NativeTextReplacement.swift",
        "NativeUIHarness.swift", "VisualExportEndToEndTests.swift",
    }
    previous = dict(prepared)
    for delta in deltas:
        assert delta["R2"] == prepared[delta["name"]]
        if delta["R1"] is None:
            assert delta["name"] == "FocusEndToEndTests.swift"
            del previous[delta["name"]]
        else:
            assert re.fullmatch("[0-9a-f]{64}", delta["R1"]) and delta["R1"] != delta["R2"]
            previous[delta["name"]] = delta["R1"]
    assert sha(json.dumps(previous, sort_keys=True, separators=(",", ":")).encode()) == \
        "2ff6fcb23df8eca80c7369f6c0db793859fb0f672e61b665055f8a255316b7c1"
    assert comparison["scope"] == (
        "Retained source comparison only. R2 includes other harness changes; "
        "it is not an isolated A/B test of one fix."
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    print(json.dumps(verify_package(args.root), indent=2))
