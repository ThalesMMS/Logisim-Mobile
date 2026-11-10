#!/usr/bin/env python3
"""Verify archived evidence integrity offline. A verified archive does not pass a failed case."""

import argparse
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys


SOURCE = "b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e"
TREE = "e24981d26d6d293763966caa9961657a94ce27f6"
BASE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
APP = "thalesmms.logisim.mobile.e2e"
TEST = APP + ".test"
HELPER = "thalesmms.logisim.mobile.snapshot.e2e.test"
SERIAL = "emulator-5558"
FINGERPRINT = "google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys"
APK_HASHES = {
    APP: "c12be1b537b1e02744f057fb36372f3669df25e3385e9c2e7e4fda8a8fc1484b",
    TEST: "f0883691aea034441b4eda8375e424662e78e9a420e7b5f12d225f26e8d45c31",
    HELPER: "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
}
CLOCKS = {
    "eventTime/downTime/changeUptime/receiverUptimeMillis": "UPTIME_MILLIS",
    "receiverElapsedMillis": "ELAPSED_REALTIME_MILLIS",
}
CASES = [
    {"id": "scroll-once", "case": "scroll", "status": "PASS", "qualification": "PARTIAL",
     "instrumentationCode": -1, "inputRecords": 13, "stateRecords": 107, "motionEvents": 3},
    {"id": "pinch-once", "case": "pinch", "status": "FAIL", "qualification": "NOT_QUALIFIED",
     "instrumentationCode": 0, "inputRecords": 0, "stateRecords": 73, "motionEvents": 0,
     "failureStage": "PREPARATION_ZOOM_OUT_BEFORE_MOTION_EVENTS"},
]
ROOT_FILES = {
    "preflight.json", "preflight-initial-guard-failure.json", "postflight-cleanup.json",
    "lastanr-preflight.txt", "lastanr-final.txt", "scroll-once-runner.log", "pinch-once-runner.log",
} | {f"apk-{index}-{kind}.txt" for index in range(3) for kind in ("badging", "manifest")}
CASE_FILES = {
    "scroll-once": {"0-fixture.circ", "1-scroll-before.circ", "2-scroll-after.circ", "scroll-complete.png"},
    "pinch-once": {"0-fixture.circ", "1-pinch-before.circ", "pinch-failure.png", "pinch-failure-tree.txt"},
}
REQUIRED_FILES = {"data/" + name for name in ROOT_FILES} | {
    "data/producer/artifact.json", "data/producer/independent-source-review.json",
    "data/api-audit/audit-provenance.json", "data/api-audit/SDK35-UiAutomation.javap.txt",
    "data/api-audit/SDK35-AccessibilityNodeInfo.javap.txt",
}
for case_id, extra in CASE_FILES.items():
    REQUIRED_FILES.update(f"data/{case_id}/{name}" for name in
                          ("manifest.json", "instrumentation.txt", "lastanr-before.txt", "lastanr-after.txt"))
    REQUIRED_FILES.update(f"data/{case_id}/native/{name}" for name in
                          extra | {"result.json", "canvas-input-trace.json"})


class VerificationError(Exception):
    pass


def check(condition, message):
    if not condition:
        raise VerificationError(message)


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        check(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nonnegative_integer(value, label):
    check(type(value) is int and value >= 0, f"Invalid nonnegative integer: {label}")


def verify_inventory(root, evidence):
    inventory = evidence["files"]
    paths = [entry["path"] for entry in inventory]
    check(len(paths) == len(set(paths)), "Duplicate file in evidence inventory")
    check(set(paths) == REQUIRED_FILES, "Evidence inventory differs from the fixed raw dataset")
    actual = {path.relative_to(root).as_posix() for path in (root / "data").rglob("*") if path.is_file()}
    check(actual == REQUIRED_FILES, "Archived raw files differ from the inventory")
    total_bytes = 0
    for entry in inventory:
        relative = PurePosixPath(entry["path"])
        check(not relative.is_absolute() and ".." not in relative.parts, "Unsafe archived path")
        path = root.joinpath(*relative.parts)
        check(not path.is_symlink() and root.resolve() in path.resolve().parents,
              f"Archived path is not a local regular copy: {relative}")
        nonnegative_integer(entry["bytes"], str(relative))
        check(re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]) is not None, "Invalid SHA-256")
        check(path.stat().st_size == entry["bytes"], f"Size mismatch: {relative}")
        check(digest(path) == entry["sha256"], f"SHA-256 mismatch: {relative}")
        check(isinstance(entry["sourceLocalPath"], str) and entry["sourceLocalPath"].startswith("/"),
              f"Missing original local provenance: {relative}")
        total_bytes += entry["bytes"]
    return total_bytes


def verify_command_clocks(document, label):
    for command in document.get("commands", []):
        start = datetime.fromisoformat(command["startedUTC"])
        finish = datetime.fromisoformat(command["finishedUTC"])
        check(start.utcoffset() == timedelta(0) and finish.utcoffset() == timedelta(0),
              f"Command clock is not declared UTC: {label}")
        check(finish >= start, f"Command finished before it started: {label}")


def keyed_unique(items, field, label):
    result = {item[field]: item for item in items}
    check(len(result) == len(items), f"Duplicate {field}: {label}")
    return result


def verify_provenance(root):
    data = root / "data"
    producer = read_json(data / "producer/artifact.json")
    review = read_json(data / "producer/independent-source-review.json")
    check((producer["source_sha"], producer["source_tree"], producer["base_source"]) == (SOURCE, TREE, BASE),
          "Producer source pins differ")
    check(producer["source_dirty"] is False and producer["build_status"] == "PASS", "Producer build receipt differs")
    check(producer["runtime_status"] == "NOT_RUN" and producer["qualification"] == "DIAGNOSTIC_BUILD_ONLY",
          "Producer receipt must remain a build-only claim")
    check(producer["opt_in_argument"] == "canvasInputTrace=1", "Producer opt-in argument differs")
    check(producer["trace_limits"] == {"input_records": 64, "input_bytes": 65536, "state_records": 192,
          "state_bytes": 131072, "record_bytes": 8192, "pointer_limit": 4, "field_limit": 24},
          "Producer trace limits differ")
    check((review["source_sha"], review["source_tree"], review["base"], review["result"]) ==
          (SOURCE, TREE, BASE, "PASS"), "Independent source review pins differ")
    check(producer["independent_source_review"]["sha256"] == digest(data / "producer/independent-source-review.json"),
          "Independent review hash does not match producer receipt")
    built = keyed_unique(producer["artifacts"], "package", "producer artifacts")
    check(set(built) == {APP, TEST}, "Producer app/test artifacts differ")
    for package, item in built.items():
        check(item["sha256"] == APK_HASHES[package] and item["producer_source"] == SOURCE,
              "Producer app/test hash or source differs")
    helper = producer["snapshot_helper"]
    check(helper["package"] == HELPER and helper["sha256"] == APK_HASHES[HELPER] and
          helper["source"] == "5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c" and helper["rebuilt"] is False,
          "Imported helper identity differs")
    preflight = read_json(data / "preflight.json")
    initial = read_json(data / "preflight-initial-guard-failure.json")
    postflight = read_json(data / "postflight-cleanup.json")
    for document, label in ((preflight, "preflight"), (initial, "initial preflight"), (postflight, "postflight")):
        check(document["serial"] == SERIAL and document["source"] == SOURCE, f"Source/device differs: {label}")
        verify_command_clocks(document, label)
    check(preflight["status"] == "PASS" and initial["status"] == "FAIL" and postflight["status"] == "PASS",
          "Preflight correction or cleanup outcomes differ")
    check(preflight["tree"] == TREE and preflight["fingerprint"] == FINGERPRINT, "Preflight source/device pins differ")
    check(preflight["producerManifestSha256"] == digest(data / "producer/artifact.json") ==
          initial["producerManifestSha256"], "Preflight producer hash differs")
    prior = preflight["priorPreflightGuardFailure"]
    check(prior["file"] == "preflight-initial-guard-failure.json" and
          prior["sha256"] == digest(data / prior["file"]) and "No input case attempted" in prior["reason"],
          "Initial preflight guard failure is not preserved in the final chain")
    check(initial["error"] == f"AssertionError(['{HELPER}', '{HELPER}'])", "Initial helper guard failure differs")
    artifacts = keyed_unique(preflight["artifacts"], "package", "preflight readbacks")
    readbacks = keyed_unique(postflight["readbacks"], "package", "postflight readbacks")
    check(set(artifacts) == set(readbacks) == set(APK_HASHES), "Expected exactly three installed APK readbacks")
    for package, expected in APK_HASHES.items():
        check(artifacts[package]["sha256"] == artifacts[package]["installedSha256"] ==
              readbacks[package]["sha256"] == readbacks[package]["expected"] == expected,
              f"Installed readback differs: {package}")
        check(readbacks[package]["pidAfterCleanup"] == "", f"Process remained after cleanup: {package}")
    check(artifacts[TEST]["targetPackages"] == [APP], "Test APK target differs")
    check(artifacts[HELPER]["targetPackages"] == [HELPER, HELPER] and
          artifacts[HELPER]["instrumentationNames"] == ["dev.logisim.mobile.documents.UiSnapshotInstrumentation",
          "dev.logisim.mobile.documents.UiSnapshotSeriesInstrumentation"], "Helper must retain both self-target runners")
    for index, package in enumerate((APP, TEST, HELPER)):
        check(f"package: name='{package}'" in (data / f"apk-{index}-badging.txt").read_text(encoding="utf-8"),
              "APK badging package differs")
        manifest = (data / f"apk-{index}-manifest.txt").read_text(encoding="utf-8")
        check(package in manifest, "APK manifest package differs")
    check(postflight["lastAnrUnchangedAcrossLease"] is True and postflight["leaseReleased"] is True,
          "Cleanup/ANR receipt differs")
    anr = [path.read_bytes() for path in data.rglob("lastanr-*.txt")]
    check(len(anr) == 6 and len(set(anr)) == 1, "Preserved ANR snapshots differ")


def verify_trace(trace, expected):
    check(trace["schema"] == "canvas-input-trace-v1" and trace["clocks"] == CLOCKS, "Trace schema or clock domains differ")
    check((trace["pointerLimit"], trace["fieldLimit"], trace["maxRecordBytes"]) == (4, 24, 8192), "Trace limits differ")
    check(trace["loggingErrors"] == trace["hookCallbackFailures"] == 0, "Trace reported a diagnostic error")
    entries = []
    for name, count, max_count, max_bytes in (("input", expected["inputRecords"], 64, 65536),
                                             ("state", expected["stateRecords"], 192, 131072)):
        channel = trace[name]
        check((channel["maxCount"], channel["maxBytes"]) == (max_count, max_bytes), f"{name} channel limits differ")
        check(channel["count"] == len(channel["entries"]) == count and channel["total"] == count,
              f"{name} retained/total counts differ")
        check(channel["dropped"] == channel["oversized"] == 0, f"{name} channel omitted records")
        check(channel["bytes"] == sum(item["utf8Bytes"] for item in channel["entries"]) <= max_bytes,
              f"{name} channel bytes differ")
        check([entry["sequence"] for entry in channel["entries"]] ==
              sorted(entry["sequence"] for entry in channel["entries"]), f"{name} arrival order differs")
        for entry in channel["entries"]:
            encoded = json.dumps(entry["record"], ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
            check(len(encoded) == entry["utf8Bytes"] <= 8192, "Record UTF-8 size differs")
            record = entry["record"]
            for clock in ("receiverUptimeMillis", "receiverElapsedMillis"):
                nonnegative_integer(record[clock], clock)
            check(record.get("fieldsOmitted", 0) == record.get("fieldValuesTruncated", 0) == 0 and
                  record.get("documentIdTruncated", False) is False, "Scalar record fields were omitted/truncated")
            if "composePointers" in record:
                check(record["composePointersOmitted"] == 0 and record["composePointerCount"] ==
                      len(record["composePointers"]) <= 4, "Compose pointer fields were omitted")
                for pointer in record["composePointers"]:
                    check(0 <= pointer["uptimeMillis"] <= record["receiverUptimeMillis"], "Compose uptime order differs")
            native = record.get("native")
            if native is not None:
                check(native["pointersOmitted"] == 0 and native["pointerCount"] == len(native["pointers"]) <= 4,
                      "Native pointer fields were omitted")
                check(0 <= native["downTimeUptimeMillis"] <= native["eventTimeUptimeMillis"] <= record["receiverUptimeMillis"],
                      "Native uptime order differs")
            entries.append(entry)
    entries.sort(key=lambda entry: entry["sequence"])
    check(trace["lastSequence"] == len(entries) and [entry["sequence"] for entry in entries] ==
          list(range(1, len(entries) + 1)), "Global trace sequence is incomplete or duplicated")
    for clock in ("receiverUptimeMillis", "receiverElapsedMillis"):
        values = [entry["record"][clock] for entry in entries]
        check(values == sorted(values), f"Trace chronology differs within its declared {clock} domain")


def verify_case(root, expected):
    directory = root / "data" / expected["id"]
    manifest = read_json(directory / "manifest.json")
    result = read_json(directory / "native/result.json")
    trace = read_json(directory / "native/canvas-input-trace.json")
    check(manifest["native"] == result, "Manifest native result differs from the raw result")
    for document in (manifest, result):
        check((document["status"], document["qualification"], document["declaredSourceRevision"]) ==
              (expected["status"], expected["qualification"], SOURCE), "Case outcome/source differs")
    check((manifest["package"], result["packageName"], manifest["serial"], manifest["androidFingerprint"]) ==
          (APP, APP, SERIAL, FINGERPRINT), "Case package/device differs")
    check(manifest["requestedCases"] == expected["case"] and manifest["canvasInputTraceRequested"] is True,
          "Case selection or requested trace differs")
    check(manifest["artifacts"] == manifest["installedArtifactsAfter"] ==
          {"appSha256": APK_HASHES[APP], "testSha256": APK_HASHES[TEST]}, "Case APK readbacks differ")
    check(manifest["lastAnrUnchanged"] is True and result["artifact"]["sha256Before"] == APK_HASHES[APP],
          "Case ANR/artifact receipt differs")
    check(len(result["cases"]) == 1 and result["cases"][0]["case"] == expected["case"] and
          result["cases"][0]["status"] == expected["status"], "Case is repeated or renamed")
    case = result["cases"][0]
    check(case["finishedAtElapsedMillis"] - case["startedAtElapsedMillis"] == case["elapsedMillis"],
          "Case elapsed clock accounting differs")
    instrumentation = (directory / "instrumentation.txt").read_text(encoding="utf-8")
    codes = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", instrumentation, re.MULTILINE)
    check(codes == [str(expected["instrumentationCode"])], "Instrumentation terminal outcome differs")
    receipts = re.findall(r"^MIXED_INPUT_RESULT=(.+)$", instrumentation, re.MULTILINE)
    check(len(receipts) == 1 and json.loads(receipts[0], object_pairs_hook=unique_fields) == result,
          "Instrumentation result differs from the archived native result")
    summary = result["canvasInputTrace"]
    check(summary == {"enabled": True, "captureId": trace["captureId"], "file": "canvas-input-trace.json",
          "inputRecords": expected["inputRecords"], "stateRecords": expected["stateRecords"],
          "inputDropped": 0, "stateDropped": 0, "loggingErrors": 0, "hookCallbackFailures": 0,
          "status": "COLLECTED"}, "Requested trace was not collected completely")
    verify_trace(trace, expected)
    motions = [event for event in result["events"] if event["kind"] == "motion"]
    check(len(motions) == expected["motionEvents"], "Injected MotionEvent count differs")
    if expected["case"] == "scroll":
        check(result["artifact"]["sha256After"] == APK_HASHES[APP], "Scroll final app hash differs")
        check(all(event["accepted"] is True and event["action"] == "ACTION_SCROLL" for event in motions),
              "Scroll injection outcomes differ")
    else:
        check([event["kind"] for event in result["events"]] == ["input-session"] and
              "Timed out waiting for zoom out applied" in result["error"], "Pinch preparation failure differs")
        wait = result["readinessDiagnostics"]["waitSummaries"]["records"][-1]["record"]
        check((wait["description"], wait["attempts"], wait["lastOutcome"], wait["elapsedMillis"]) ==
              ("zoom out applied", 273, "RETURNED_FALSE", 15050), "Pinch preparation guard outcome differs")
        state = [entry["record"] for entry in trace["state"]["entries"]]
        check(sum(record["stage"] == "viewport-request" and record.get("fields", {}).get("reason") == "zoom"
                  for record in state) == 4, "Pinch preparation zoom request count differs")
        composed = [record["after"]["scale"] for record in state if record["stage"] == "composed"]
        check(composed[-4:] == [1.8434782, 1.4747826, 1.1798261, 0.94386095], "Pinch composed zoom sequence differs")
        check('"text":"118%"' in (directory / "native/pinch-failure-tree.txt").read_text(encoding="utf-8"),
              "Separate accessibility failure capture no longer contains 118%")
    return dict(expected, traceStatus="COLLECTED", loggingErrors=0, hookCallbackFailures=0, traceDropped=0)


def verify(root):
    evidence = read_json(root / "evidence.json")
    check(evidence["schemaVersion"] == 1 and evidence["verificationScope"] == "ARCHIVED_EVIDENCE_INTEGRITY_ONLY",
          "Evidence scope differs")
    check(evidence["source"] == {"commit": SOURCE, "tree": TREE, "base": BASE} and
          evidence["target"] == {"package": APP, "serial": SERIAL, "fingerprint": FINGERPRINT} and
          evidence["apkSha256"] == APK_HASHES, "Evidence fixed pins differ")
    check(evidence["cases"] == CASES, "Evidence case list/outcomes differ or a case was repeated")
    check(evidence["captureSeparation"] == {"pinchAccessibilityText": "118%", "pinchLastComposedScale": 0.94386095,
          "capturesAtomic": False, "pngVerification": "FILE_SHA256_ONLY"}, "Capture interpretation differs")
    total_bytes = verify_inventory(root, evidence)
    verify_provenance(root)
    cases = [verify_case(root, expected) for expected in CASES]
    return {"evidenceStatus": "VERIFIED", "verificationScope": "ARCHIVED_EVIDENCE_INTEGRITY_ONLY",
            "verifiedFiles": len(evidence["files"]), "verifiedBytes": total_bytes, "source": SOURCE,
            "cases": cases, "captureSeparation": evidence["captureSeparation"],
            "meaning": "Integrity verified. Scroll remains PASS/PARTIAL; pinch remains FAIL/NOT_QUALIFIED before MotionEvents."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent,
                        help="Archive directory containing evidence.json and data/")
    arguments = parser.parse_args()
    try:
        result = verify(arguments.root)
    except (VerificationError, OSError, ValueError, KeyError, TypeError) as error:
        print(json.dumps({"evidenceStatus": "INVALID", "error": str(error)}, indent=2), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
