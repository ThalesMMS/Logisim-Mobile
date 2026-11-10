#!/usr/bin/env python3
"""Verify the preserved R3 record without Git, a device, or local-only recordings."""
from collections import Counter, defaultdict
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path, PurePosixPath
import re
import tarfile

ROOT = Path(__file__).resolve().parent
APP = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
HARNESS = "91bc58e38e0171777df5951b018a23ac6da8da69"
BUNDLE = "813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef"
EXE = "72f025668535a86c479eda81c2bbcba9fb65e0f1cef29598d869dd78c4977d48"
MANIFEST = "7e83088f9d1ccee3c268e9dec954db9c351b47db9cd01b9fb652e8f1235b7b76"
CONTRACT = "9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9"
COMPILED = "0937bf9c115ecfe46b8e66a94fd619f7d914a5d2883b3f3a93dc8aef2acd87db"
STAGED = "e69a36025c0c7396292d9dfafaa6495f598e41efe1baa77b711a5890419ea2c0"
INPUTS = "ca55d6f0efb5047d897441fbe3feea8d74c2db11fa7b3f80a9d0c9817dda7d2d"
CATALOG_FILES = "d128a5ea4d3b01ad346ada980afa7447b5406ca7906611f462e36528f6bf2117"
UUID = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"
RUN_ID = "f2167ca1720d"
SELECTOR = "EditorEndToEndTests/EditorEndToEndTests/testEditingConnectionsHistoryAndViewport"
CHECKPOINTS = [(2, "empty"), (7, "components"), (9, "north"),
               (11, "components"), (13, "north"), (15, "components")]

spec = importlib.util.spec_from_file_location("editor36_r3_oracle", ROOT / "oracle.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def decode(data):
    return json.loads(data)


def map_sha(value):
    return sha(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


def check_bytes(data, record):
    assert len(data) == record["bytes"] and sha(data) == record["sha256"], "Byte identity mismatch"


def safe_name(name):
    p = PurePosixPath(name)
    assert name and not p.is_absolute() and ".." not in p.parts and p.as_posix() == name
    return name


def read_archive(path, index, record):
    check_bytes(path.read_bytes(), record)
    assert record["path"] == "raw.tar.gz"
    assert record["memberCount"] == len(index) == 669
    members = {}
    with tarfile.open(path, "r:gz") as archive:
        for item in archive:
            name = safe_name(item.name)
            assert item.isfile() and not item.issym() and not item.islnk()
            assert name not in members and name in index
            # Includes two original pre/postflight framebuffer PNGs of about3.4MB.
            assert 0 <= item.size <= 5_000_000
            data = archive.extractfile(item).read()
            check_bytes(data, index[name])
            members[name] = data
    assert members.keys() == index.keys()
    assert sum(map(len, members.values())) < 20_000_000
    return members


def check_artifact(value):
    assert value["bundle_id"] == "thalesmms.logisim.mobile"
    assert value["bundle_tree_sha256"] == BUNDLE and value["executable_sha256"] == EXE
    assert value["version"] == "0.2.0" and value["build"] == "2"


def check_device(value):
    assert value["udid"] == UUID and value["state"] == "Booted"
    assert value["deviceTypeIdentifier"] == "com.apple.CoreSimulator.SimDeviceType.iPhone-17e"
    assert value["runtime"] == "com.apple.CoreSimulator.SimRuntime.iOS-27-0"


def rect(value):
    found = re.fullmatch(r"\(([-+0-9.eE]+), ([-+0-9.eE]+), ([-+0-9.eE]+), ([-+0-9.eE]+)\)", value)
    assert found, ("Invalid rectangle", value)
    result = tuple(map(float, found.groups()))
    assert all(math.isfinite(v) for v in result)
    return result


def contains(a, b):
    return a[0] <= b[0] and a[1] <= b[1] and a[0] + a[2] >= b[0] + b[2] and a[1] + a[3] >= b[1] + b[3]


def parse_fields(payload):
    pairs = re.findall(r"(?:^|; )([A-Za-z][A-Za-z0-9]*)=(.*?)(?=; [A-Za-z][A-Za-z0-9]*=|$)", payload)
    assert len(pairs) == len({k for k, _ in pairs})
    return dict(pairs)


def verify_headers(receipt, members, attachments):
    observed, decisions = [], []
    for item in attachments:
        title = item["suggestedHumanReadableName"]
        if title.startswith("editor-header-observation_"):
            destination = observed
        elif title.startswith("editor-header-decision_"):
            destination = decisions
        else:
            continue
        text = members["attachments/" + item["exportedFileName"]].decode()
        destination.append({"attachment": item["exportedFileName"], "timestamp": item["timestamp"],
                            "payload": text, "fields": parse_fields(text)})
    assert len(observed) == 20 and len(decisions) == 7
    summary = decode(members["header-evidence.json"])
    for key, values in [("observations", observed), ("decisions", decisions)]:
        assert {r["attachment"]: r for r in summary[key]} == {r["attachment"]: r for r in values}
    groups = defaultdict(list)
    for item in observed:
        f = item["fields"]
        assert re.fullmatch(r"[0-9A-F-]{36}", f["invocation"])
        assert f["target"] in {"components", "search"}
        assert f["count"] == "1" and f["attempt"] == "0" and f["enabled"] == "true"
        assert f["inputs"] == ""  # No matching input regions were observed, not universal absence.
        frame, viewport, window, region = map(rect, (f["field"], f["viewport"], f["window"], f["list"]))
        assert viewport == region == (24.0, 182.0, 342.0, 604.0)
        assert window == (0.0, 0.0, 390.0, 844.0)
        assert contains(window, viewport)
        if f["fullyVisible"] == "true":
            assert frame[2] >= 48 and frame[3] >= 48 and contains(viewport, frame)
            assert f["hittable"] == "true"
        else:
            assert f["fullyVisible"] == "false" and f["hittable"] == "unqueried"
        if f["target"] == "components":
            assert f["role"] == "9" and f["label"] == "Components" and f["selected"] == "true"
            assert frame[2] >= 48 and frame[3] >= 48
        else:
            assert f["role"] == "52" and f["selected"] == "unobserved"
        groups[f["invocation"]].append(item)
    assert len(groups) == 14
    components, searches = [], []
    for rows in groups.values():
        rows.sort(key=lambda x: int(x["fields"]["order"]))
        assert [int(x["fields"]["order"]) for x in rows] == list(range(len(rows)))
        assert [x["timestamp"] for x in rows] == sorted(x["timestamp"] for x in rows)
        assert len({x["fields"]["target"] for x in rows}) == 1
        f = rows[0]["fields"]
        assert f["phase"] == "inspect"
        if f["target"] == "components":
            assert len(rows) == 1
            components.append(rows[0])
        else:
            searches.append(rows)
    assert len(components) == len(searches) == 7
    for rows in [rows for rows in searches if len(rows) == 2]:
        assert [r["fields"]["phase"] for r in rows] == ["inspect", "before-action"]
        assert all(r["fields"]["label"] == "Find a component, terminal, or identifier" and
                   r["fields"]["fullyVisible"] == "true" for r in rows)
    assert Counter(map(len, searches)) == {2: 6, 1: 1}
    last = max(observed, key=lambda r: r["timestamp"])
    f = last["fields"]
    assert f["target"] == "search" and f["phase"] == "inspect" and f["label"] == ""
    assert rect(f["field"]) == (24.0, 158.0, 342.0, 24.0)
    assert f["fullyVisible"] == "false" and f["hittable"] == "unqueried"
    assert f["selected"] == "unobserved" and f["count"] == "1"
    for decision in decisions:
        f = decision["fields"]
        assert set(f) == {"invocation", "target", "decision", "tapRequests"}
        assert f["target"] == "components" and f["decision"] == "skip-publicly-selected" and f["tapRequests"] == "0"
        rows = groups[f["invocation"]]
        assert len(rows) == 1 and rows[0]["fields"]["target"] == "components"
        assert rows[0]["timestamp"] <= decision["timestamp"]
    assert len({d["fields"]["invocation"] for d in decisions}) == 7
    assert receipt["headerEvidence"] == {
        "observations": 20, "decisions": 7, "skipPubliclySelected": 7, "tapRequests": 0,
        "shortBackwardScrollRequests": 0, "lastObservation": last,
        "scope": "Observed public Components selection and Search snapshots, not callback delivery or universal input absence.",
    }


def verify_evidence(receipt, members):
    r = decode(members["results.json"])
    pre = decode(members["preflight/preflight.json"])
    post = decode(members["postflight/preflight.json"])
    producer_bytes = members["preflight/producer-artifact.json"]
    assert sha(producer_bytes) == MANIFEST
    assert producer_bytes == members["postflight/producer-artifact.json"]
    producer = decode(producer_bytes)
    for key, value in {"schema": 1, "issue": 36, "runLabel": "R3", "status": "FAIL",
        "nativeExecutionGate": "NATIVE_FAIL", "caseSeconds": 300.753, "testCount": 1,
        "failureCount": 1, "exitCode": 65, "nativeRunCount": 1, "testTimeoutSeconds": 900,
        "selectedTest": SELECTOR, "harnessSource": HARNESS, "appProducerSource": APP,
        "producerManifestSHA256": MANIFEST, "runId": RUN_ID, "passedSteps": 15,
        "attemptedFailureStep": 16, "failureAction": "connectPorts"}.items():
        assert receipt[key] == value, ("Scope or identity changed", key)
    assert receipt["qualification"] == "FAIL; selected case, no issue completion"
    assert r["status"] == r["qualification_gate"] == r["execution"]["status"] == "FAIL"
    assert r["native_execution_gate"] == "NATIVE_FAIL" and r["execution"]["exit_code"] == 65
    assert r["selected_tests"] == r["execution"]["selection"] == [SELECTOR]
    assert r["alpha"] is False and r["physical_device"] is False and r["test_timeout_seconds"] == 900
    assert r["build"] == {"status": "PASS", "exit_code": 0}
    assert r["resource_preflight"] == r["external_peer_preflight"] == "PASS"
    assert r["declared_baseline_sha"] == APP and r["simulator"] == UUID
    run = decode(members["harness/Run.json"])
    assert r["run_id"] == run["run_id"] == RUN_ID and run["simulatorUDID"] == UUID
    assert receipt["started"] == r["started"] and receipt["finished"] == r["finished"]
    assert receipt["device"] == r["device"]
    for d in [r["device"], pre["device"], post["device"]]:
        check_device(d)
    assert r["main_app_installation"]["status"] == "REUSED_IDENTICAL"
    for a in [receipt["appArtifact"], r["artifact"], pre["artifact"], post["artifact"],
              r["main_app_installation"]["before"], r["main_app_installation"]["installed"]]:
        check_artifact(a)
    assert producer["source_sha"] == producer["app_producer_source"] == APP
    assert producer["source_dirty"] is False
    expected_files = producer["artifact"]["files"]
    assert len(expected_files) == 12 and len({x["path"] for x in expected_files}) == 12
    tree = hashlib.sha256()
    for f in sorted(expected_files, key=lambda x: PurePosixPath(x["path"]).parts):
        safe_name(f["path"])
        tree.update((f["path"] + "\0" + f["sha256"] + "\n").encode())
    assert tree.hexdigest() == BUNDLE
    assert post["prePostInputsIdentical"] is True
    for item in [pre, post]:
        assert item["status"] == "PASS" and item["harnessSource"] == HARNESS
        assert item["producerSource"] == APP and item["manifestSHA256"] == MANIFEST
        assert item["producerFiles"] == expected_files
        assert item["selectedTest"] == SELECTOR and item["testTimeoutSeconds"] == 900
        assert item["contractSteps"] == 62 and item["contractSHA256"] == CONTRACT
        assert item["readerState"] is False and item["voiceOverTouchEnabled"] == "0"
        assert item["catalogMessages"] == producer["catalog"]["message_count"] == 2497
        assert len(item["catalogFiles"]) == 64 and map_sha(item["catalogFiles"]) == CATALOG_FILES
        assert item["framebuffer"] == {"width": 1170, "height": 2532, "portrait": True}
        assert item["compiledCount"] == 17 and item["stagedCount"] == 18
        assert map_sha(item["compiledSources"]) == COMPILED and map_sha(item["stagedSources"]) == STAGED
        assert map_sha(item["inputs"]) == INPUTS and len(item["inputs"]) == 50
        for name, digest in item["inputs"].items():
            assert sha(members["input-source/" + safe_name(name)]) == digest
    assert pre["inputs"] == post["inputs"] and pre["compiledSources"] == post["compiledSources"]
    assert pre["stagedSources"] == post["stagedSources"] == r["harness_sources"]
    actual_sources = {PurePosixPath(n).name: sha(v) for n, v in members.items()
                      if n.startswith("harness/") and n.count("/") == 1 and n.endswith(".swift")}
    assert actual_sources == pre["stagedSources"]
    names = re.findall(r"^\s+- ([A-Za-z0-9_]+\.swift)\s*$", members["harness/project.yml"].decode(), re.M)
    assert len(names) == len(set(names)) == 17
    assert {n: actual_sources[n] for n in names} == pre["compiledSources"]
    assert set(actual_sources) - set(names) == {"AccessibilityUITests.swift"}
    assert receipt["swiftSources"] == {"compiledCount": 17, "preparedCount": 18,
        "compiledMapSHA256": COMPILED, "preparedMapSHA256": STAGED}
    pin = decode(members["staged-compiled-pin.json"])
    assert pin["status"] == "PASS" and pin["harnessSource"] == HARNESS
    assert pin["compiledSources"] == pre["compiledSources"] and pin["stagedSources"] == actual_sources
    assert pin["compiledCount"] == 17 and pin["stagedCount"] == 18
    assert len(decode(members["harness/Catalog.json"])) == 2497
    assert sha(members["harness/Catalog.json"]) == r["catalog"]["sha256"] == pin["catalogSHA256"]
    assert sha(members["harness/editor-actions-v1.json"]) == CONTRACT == pin["contractSHA256"]
    contract = decode(members["harness/editor-actions-v1.json"])
    steps = contract["scenarios"][0]["steps"]
    assert len(steps) == 62 and steps[15]["action"] == "connectPorts"
    setup = decode(members["setup-diagnostics/003/setup-error.json"])
    assert setup["status"] == "FAIL" and setup["phase"] == "preflight-setup"
    assert setup["nativeRunCount"] == 0 and setup["errorType"] == "KeyError"
    assert "AssertionError" in members["setup-diagnostics/003b/setup-error.log"].decode()
    assert all(x["nativeRunCount"] == 0 for x in receipt["setupDiagnostics"].values())
    attachments = [a for g in decode(members["attachments/manifest.json"]) for a in g.get("attachments", [])]
    assert len(attachments) == receipt["attachments"]["nativeTotal"] == 556
    assert receipt["attachments"]["archivedExcludingVideo"] == 555
    assert receipt["attachments"]["videoRetainedLocally"] is True
    assert len({a["exportedFileName"] for a in attachments}) == 556
    exports, notes = [], []
    for a in attachments:
        assert a["deviceId"] == UUID and a["deviceName"] == "iPhone 17e"
        name, title = a["exportedFileName"], a["suggestedHumanReadableName"]
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
    assert [n for n, _ in notes] == list(range(1, 16))
    assert receipt["stepNotes"] == [{"step": n, "attachment": name} for n, name in notes]
    assert oracle.check_step_notes([(n, members["attachments/" + name]) for n, name in notes],
                                   steps, native_pass=False) == 15
    history, repeated = [], {}
    assert len(receipt["exports"]) == 6
    for index, ((number, name), (step, checkpoint), record) in enumerate(
            zip(exports, CHECKPOINTS, receipt["exports"]), 1):
        assert record["exportIndex"] == number == index
        assert (record["contractStep"], record["checkpoint"]) == (step, checkpoint)
        assert record["attachment"] == name
        assert steps[step - 1] == {"action": "assertDocument", "checkpoint": checkpoint}
        raw = members["attachments/" + name]
        check_bytes(raw, record)
        _, identities = oracle.check_xml(raw, contract["snapshots"][checkpoint], checkpoint)
        history.append((checkpoint, identities))
        if checkpoint in repeated:
            assert repeated[checkpoint] == raw
        repeated[checkpoint] = raw
    oracle.check_identity_history(history)
    assert history[-1][1] == {(100, 100): "c1", (200, 100): "c2", (300, 100): "c3"}
    log = members["test.log"].decode()
    actual_steps = re.findall(r"(?m)^\s+t =\s+[0-9.]+s contract step (\d+): (\w+)$", log)
    assert actual_steps == [(str(i), steps[i - 1]["action"]) for i in range(1, 17)]
    assert "failed (300.753 seconds)" in log and "Executed 1 test, with 1 failure (0 unexpected)" in log
    assert "EditorEndToEndTests.swift:529: error:" in log
    assert "Explorer header control has an invalid label, state or frame" in log
    assert "** TEST EXECUTE FAILED **" in log
    assert 'Tap "port:2:c1:out" Button' in log and 'Tap "port:2:c2:in" Button' not in log
    verify_headers(receipt, members, attachments)
    for name in [receipt["nativeTreeAttachment"], receipt["headerFailureTreeAttachment"]]:
        tree = members["attachments/" + name].decode()
        assert "editor-tool-sheet" in tree and "a11y-circuit-list" in tree and "a11y-circuit-search" in tree
        assert "pid: 51827" in tree
        assert "identifier: 'component:c1', label: 'Select E2E_IN, E2E_IN'" in tree
    stop = decode(members["explicit-termination.json"])
    assert stop["exitCode"] == 3
    return {"status": "FAIL_PRESERVED", "nativeTests": 1, "failures": 1,
        "completedSteps": 15, "xmlExports": 6, "nextStep": 16,
        "headerObservations": 20, "headerSkips": 7, "headerTapRequests": 0, "headerScrollRequests": 0}


def verify_package(root=ROOT):
    root = Path(root)
    receipt = decode((root / "receipt.json").read_bytes())
    for name, record in receipt["publicFiles"].items():
        path = root / safe_name(name)
        assert path.is_file() and not path.is_symlink()
        check_bytes(path.read_bytes(), record)
    index = decode((root / "raw-members.json").read_bytes())
    return verify_evidence(receipt, read_archive(root / "raw.tar.gz", index, receipt["rawArchive"]))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    print(json.dumps(verify_package(parser.parse_args().root), indent=2))
