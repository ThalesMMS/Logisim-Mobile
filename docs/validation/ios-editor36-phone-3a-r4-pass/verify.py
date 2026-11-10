#!/usr/bin/env python3
"""Verify the preserved R4 selected-case PASS record without Git, a device, or local-only recordings."""
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
HARNESS = "f25facb3f53a166c375cf2379b0e49839ecec022"
BUNDLE = "813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef"
EXE = "72f025668535a86c479eda81c2bbcba9fb65e0f1cef29598d869dd78c4977d48"
MANIFEST = "7e83088f9d1ccee3c268e9dec954db9c351b47db9cd01b9fb652e8f1235b7b76"
CONTRACT = "9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9"
COMPILED = "0d1fe96369953969e4e5e974e2719021251dd76d62c4fb4017ff6adb3b0c9339"
STAGED = "b0c4e96c2026d6e385281b27593b27f26258ed52a8ea12a9e5483c03879ca366"
INPUTS = "1e773c4a32481f39a29cbc1b6531e46f3115b94861b6dad7ba8392562822a400"
CATALOG_FILES = "d128a5ea4d3b01ad346ada980afa7447b5406ca7906611f462e36528f6bf2117"
UUID = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"
RUN_ID = "0a10c9841e58"
SELECTOR = "EditorEndToEndTests/EditorEndToEndTests/testEditingConnectionsHistoryAndViewport"
CHECKPOINTS = [(2, 'empty'), (7, 'components'), (9, 'north'), (11, 'components'), (13, 'north'), (15, 'components'), (18, 'wired'), (25, 'wire-removed'), (27, 'wired'), (29, 'wire-removed'), (31, 'wired'), (35, 'pasted'), (38, 'wired'), (40, 'pasted'), (42, 'wired'), (45, 'empty'), (47, 'wired'), (49, 'empty'), (51, 'wired'), (62, 'wired')]

spec = importlib.util.spec_from_file_location("editor36_r4_oracle", ROOT / "oracle.py")
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
    assert record["memberCount"] == len(index) == 336
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
    groups = defaultdict(list)
    observations, decisions = [], []
    for item in attachments:
        title = item["suggestedHumanReadableName"]
        if not title.startswith(("editor-header-observation_", "editor-header-decision_")):
            continue
        kind = "observation" if title.startswith("editor-header-observation_") else "decision"
        payload = members["attachments/" + item["exportedFileName"]].decode()
        row = {"attachment": item["exportedFileName"], "timestamp": item["timestamp"],
               "payload": payload, "kind": kind, "fields": parse_fields(payload)}
        f = row["fields"]
        assert re.fullmatch(r"[0-9A-F-]{36}", f["invocation"])
        groups[f["invocation"]].append(row)
        (observations if kind == "observation" else decisions).append(row)
    assert len(observations) == 53 and len(decisions) == 19 and len(groups) == 30
    for rows in groups.values():
        rows.sort(key=lambda r: (r["timestamp"], r["attachment"]))
    assert dict(groups) == decode(members["draft-inventory.json"])["headerByInvocation"]
    for row in observations:
        f = row["fields"]
        assert f["target"] in {"components", "search"} and f["inputs"] == ""
        assert rect(f["viewport"]) == rect(f["list"]) == (24.0, 182.0, 342.0, 604.0)
        assert rect(f["window"]) == (0.0, 0.0, 390.0, 844.0)
        if f["count"] == "0":
            assert f["target"] == "components" and all(f[k] == "unobserved" for k in
                ("selected", "role", "label", "enabled", "field", "fullyVisible"))
            assert f["hittable"] == "unqueried"
            continue
        assert f["count"] == "1" and f["enabled"] == "true"
        frame = rect(f["field"])
        assert frame[2] > 0 and frame[3] > 0
        if f["target"] == "components":
            assert (f["selected"], f["role"], f["label"]) == ("true", "9", "Components")
        else:
            assert (f["selected"], f["role"]) == ("unobserved", "52")
            assert f["label"] in {"", "Find a component, terminal, or identifier"}
        if f["fullyVisible"] == "true":
            assert min(frame[2:]) >= 48 and contains(rect(f["viewport"]), frame)
            assert f["hittable"] == "true"
        else:
            assert f["fullyVisible"] == "false" and f["hittable"] == "unqueried"
    recovered = []
    for invocation, rows in groups.items():
        obs = sorted((r for r in rows if r["kind"] == "observation"), key=lambda r: int(r["fields"]["order"]))
        dec = [r for r in rows if r["kind"] == "decision"]
        assert [int(r["fields"]["order"]) for r in obs] == list(range(len(obs)))
        assert [r["timestamp"] for r in obs] == sorted(r["timestamp"] for r in obs)
        target = obs[0]["fields"]["target"]
        assert all(r["fields"]["target"] == target for r in rows)
        scrolls = [r for r in dec if r["fields"]["decision"] == "short-backward-scroll"]
        if scrolls:
            recovered.append(invocation)
        expected = []
        for attempt in range(len(scrolls)):
            expected += [("inspect", str(attempt)), ("before-backward-scroll", str(attempt))]
        expected.append(("inspect", str(len(scrolls))))
        if target == "search":
            expected.append(("before-action", str(len(scrolls))))
        assert [(r["fields"]["phase"], r["fields"]["attempt"]) for r in obs] == expected
        last = obs[-1]["fields"]
        assert last["count"] == "1" and last["enabled"] == "true"
        assert last["fullyVisible"] == last["hittable"] == "true"
        if target == "components":
            assert len(dec) == len(scrolls) + 1 and dec[-1]["fields"]["decision"] == "skip-publicly-selected"
        else:
            assert len(dec) == len(scrolls) and last["label"] == "Find a component, terminal, or identifier"
        for decision in dec:
            f = decision["fields"]
            previous = [r for r in obs if r["timestamp"] <= decision["timestamp"]][-1]
            before = previous["fields"]
            if f["decision"] == "skip-publicly-selected":
                assert set(f) == {"invocation", "target", "decision", "tapRequests"}
                assert f["tapRequests"] == "0" and target == "components"
                assert before["selected"] == "true" and before["phase"] == "inspect"
                assert before["fullyVisible"] == "true" and min(rect(before["field"])[2:]) >= 48
            else:
                assert f["decision"] == "short-backward-scroll"
                assert set(f) == {"invocation", "target", "decision", "start", "end"}
                assert f["start"] == "(195.0, 436.0)" and f["end"] == "(195.0, 532.0)"
                assert before["phase"] == "before-backward-scroll" and before["fullyVisible"] != "true"
                following = obs[int(before["order"]) + 1]
                assert following["fields"]["phase"] == "inspect"
                assert int(following["fields"]["attempt"]) == int(before["attempt"]) + 1
                assert following["timestamp"] >= decision["timestamp"]
    assert len(recovered) == 3
    counts = {"headerObservationCount": len(observations),
        "headerDecisionCounts": dict(Counter(r["fields"]["decision"] for r in decisions)),
        "headerSelectedStates": dict(Counter(r["fields"]["selected"] for r in observations)),
        "headerHittableStates": dict(Counter(r["fields"]["hittable"] for r in observations))}
    assert counts == receipt["headerEvidence"] == {"headerObservationCount": 53,
        "headerDecisionCounts": {"skip-publicly-selected": 15, "short-backward-scroll": 4},
        "headerSelectedStates": {"unobserved": 36, "true": 17},
        "headerHittableStates": {"true": 45, "unqueried": 8}}
    first = groups["D34D1EE1-2917-43F5-B3D1-740AF5A3A31B"]
    assert receipt["firstClippedSearchRecovery"] == first
    obs = sorted((r["fields"] for r in first if r["kind"] == "observation"), key=lambda f: int(f["order"]))
    assert [rect(f["field"]) for f in obs] == [(24., 158., 342., 24.)] * 2 + [(24., 204., 342., 64.)] * 2
    assert [f["label"] for f in obs] == ["", ""] + ["Find a component, terminal, or identifier"] * 2
    assert [f["fullyVisible"] for f in obs] == ["false", "false", "true", "true"]
    log = members["test.log"].decode()
    presses = re.findall(r"(?m)^.*Press Application .*? -> \(195\.0, 436\.0\).*?then drag.*? -> \(195\.0, 532\.0\).*$", log)
    assert len(presses) == 4
    assert [re.search(r"t =\s*([0-9.]+)s", line)[1] for line in presses] == ["301.22", "349.79", "351.26", "352.74"]


def verify_runtime(receipt, members, attachments, steps, history):
    log = members["test.log"].decode()
    starts = list(re.finditer(r"(?m)^\s+t =\s+[0-9.]+s contract step (\d+): (\w+)$", log))
    windows = {i + 1: log[start.end():starts[i + 1].start() if i + 1 < len(starts) else len(log)]
               for i, start in enumerate(starts)}
    zoom = {}
    for step in (53, 55, 57, 60):
        values = re.findall(r'Find the "Fit circuit, (\d+)%" Button', windows[step])
        assert len(values) == 3 and len(set(values)) == 1
        zoom[step] = int(values[0])
    oracle.check_zoom(zoom, steps)
    assert zoom == {53: 172, 55: 215, 57: 172, 60: 400}
    outputs = {}
    for step in (19, 21, 23):
        values = re.findall(r'Find the "Circuit area\. E2E_OUT: ([01])\. Use two fingers to zoom and pan\." Other', windows[step])
        assert values == [steps[step - 1]["value"]]
        outputs[step] = values[0]
    pasted = next(ids for checkpoint, ids in history if checkpoint == "pasted")
    notes = {int(re.match(r"contract-step-(\d+)_", a["suggestedHumanReadableName"])[1]): a["timestamp"]
             for a in attachments if a["suggestedHumanReadableName"].startswith("contract-step-")}
    captions = {}
    for label, x in (("E2E_IN", 100), ("E2E_NOT", 200), ("E2E_OUT", 300)):
        candidates = [a for a in attachments if a["suggestedHumanReadableName"].startswith("distinct-" + label + "-native-identities_")]
        assert len(candidates) == 1
        item = candidates[0]
        assert notes[35] <= item["timestamp"] <= notes[36]
        raw = members["attachments/" + item["exportedFileName"]].decode()
        expected = f"{pasted[(x, 100)]}: {label} ({x}, 100), {pasted[(x, 100)]}\n" + f"{pasted[(x + 20, 120)]}: {label} ({x + 20}, 120), {pasted[(x + 20, 120)]}"
        assert raw == expected and pasted[(x, 100)] != pasted[(x + 20, 120)]
        captions[label] = {"attachment": item["exportedFileName"], "payload": raw, "timestamp": item["timestamp"]}
    assert receipt["runtimeEvidence"] == {"zoomPercent": {str(k): v for k, v in zoom.items()},
        "outputs": {str(k): v for k, v in outputs.items()}, "distinctCaptions": captions}


def verify_evidence(receipt, members):
    r = decode(members["results.json"])
    pre = decode(members["preflight/preflight.json"])
    post = decode(members["postflight/preflight.json"])
    producer_bytes = members["preflight/producer-artifact.json"]
    assert sha(producer_bytes) == MANIFEST
    assert producer_bytes == members["postflight/producer-artifact.json"]
    producer = decode(producer_bytes)
    for key, value in {"schema": 1, "issue": 36, "runLabel": "R4", "status": "PASS",
        "nativeExecutionGate": "NATIVE_PASS", "wrapperQualification": "PARTIAL", "caseSeconds": 629.969,
        "testCount": 1, "failureCount": 0, "exitCode": 0, "nativeRunCount": 1,
        "testTimeoutSeconds": 900, "selectedTest": SELECTOR, "harnessSource": HARNESS,
        "appProducerSource": APP, "producerManifestSHA256": MANIFEST, "runId": RUN_ID,
        "passedSteps": 62, "inputSourceMapSHA256": INPUTS}.items():
        assert receipt[key] == value, ("Scope or identity changed", key)
    assert receipt["qualification"] == "Selected Editor case PASS; wrapper PARTIAL; no all-suite or issue-completion claim"
    assert r["status"] == r["execution"]["status"] == "PASS" and r["qualification_gate"] == "PARTIAL"
    assert r["native_execution_gate"] == "NATIVE_PASS" and r["execution"]["exit_code"] == 0
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
    attachments = [a for g in decode(members["attachments/manifest.json"]) for a in g.get("attachments", [])]
    assert len(attachments) == receipt["attachments"]["nativeTotal"] == 206
    assert receipt["attachments"]["archivedExcludingVideo"] == 206
    assert receipt["attachments"]["videoAttachments"] == 0
    assert len({a["exportedFileName"] for a in attachments}) == 206
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
    assert [n for n, _ in exports] == list(range(1, 21))
    assert [n for n, _ in notes] == list(range(1, 63))
    assert [(item["step"], item["attachment"]) for item in receipt["stepNotes"]] == notes
    attachment_metadata = {a["exportedFileName"]: a for a in attachments}
    note_times = [attachment_metadata[name]["timestamp"] for _, name in notes]
    assert note_times == sorted(note_times)
    assert [item["timestamp"] for item in receipt["stepNotes"]] == note_times
    assert oracle.check_step_notes([(n, members["attachments/" + name]) for n, name in notes],
                                   steps, native_pass=True) == 62
    history, repeated = [], {}
    assert len(receipt["exports"]) == 20
    for index, ((number, name), (step, checkpoint), record) in enumerate(
            zip(exports, CHECKPOINTS, receipt["exports"]), 1):
        assert record["exportIndex"] == number == index
        assert (record["contractStep"], record["checkpoint"]) == (step, checkpoint)
        assert record["attachment"] == name
        assert record["timestamp"] == attachment_metadata[name]["timestamp"]
        assert record["oracleStatus"] == "PASS"
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
    assert actual_steps == [(str(i), steps[i - 1]["action"]) for i in range(1, 63)]
    assert "passed (629.969 seconds)" in log and "Executed 1 test, with 0 failures (0 unexpected)" in log
    assert "** TEST EXECUTE SUCCEEDED **" in log and ": error:" not in log
    assert "Terminate thalesmms.logisim.mobile:79099" in log and "Setting device orientation to Portrait" in log
    verify_headers(receipt, members, attachments)
    verify_runtime(receipt, members, attachments, steps, history)
    tree = members["attachments/" + receipt["nativeTreeAttachment"]].decode()
    assert "pid: 79099" in tree and "canvas-component:c1" in tree
    assert "canvas-component:c2" in tree and "canvas-component:c3" in tree
    stop = decode(members["explicit-termination.json"])
    assert stop["exitCode"] == 3 and "found nothing to terminate" in stop["stderr"]
    return {"status": "SELECTED_CASE_PASS", "wrapperQualification": "PARTIAL",
        "nativeTests": 1, "failures": 0, "completedSteps": 62, "xmlExports": 20,
        "zoomPercent": {"53": 172, "55": 215, "57": 172, "60": 400},
        "outputs": ["1", "0", "1"], "headerObservations": 53, "headerSkips": 15,
        "headerTapRequests": 0, "headerScrollRequests": 4}


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
