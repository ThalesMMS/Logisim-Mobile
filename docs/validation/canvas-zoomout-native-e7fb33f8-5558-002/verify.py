#!/usr/bin/env python3
"""Verify the archived failed Native run; no device or copied script is executed."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET

SOURCE = "e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630"
PRODUCER = "b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e"
INVENTORY_SHA = "a74ce6618dd7f9030721db1624ebc1419bd9376d072a91bce833a23a4a10e278"
DIAGNOSTIC = ["c12be1b537b1e02744f057fb36372f3669df25e3385e9c2e7e4fda8a8fc1484b",
              "33b888b38368b6ea56bc512bcef7e5c1d20d62fb8ab461e7f8da457800ba962a",
              "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c"]
STANDARD = ["10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
            "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b", DIAGNOSTIC[2]]
PRIVATE_FILES = {"0-fixture.circ", "1-pinch-before.circ", "canvas-input-trace.json", "pinch-failure-tree.txt",
                 "pinch-failure.png", "result.json", "zoom-out-observations.jsonl"}
NATIVE = "native002/pinch-once/native/"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def verify(root):
    root = Path(root)
    index = json.loads((root / "index.json").read_bytes())
    require(index["nativeStatus"] == "FAIL" and index["qualification"] == "NOT_QUALIFIED"
            and index["nativeCalls"] == 1 and index["cancelledPreparationNativeCalls"] == 0, "Archive outcome changed")
    require(index["source"] == SOURCE and index["appProducer"] == PRODUCER, "Archive producer changed")
    names = "\n".join(sorted(index["files"])) + "\n"
    require(len(index["files"]) == 185 and sha(names.encode()) == INVENTORY_SHA, "Raw inventory changed")
    raw, paths = {}, set()
    for logical, entry in index["files"].items():
        zipped = logical.endswith(".json") and entry["rawBytes"] > 65536
        relative = PurePosixPath(entry["archive"])
        require(relative.as_posix() == "raw/" + logical + (".gz" if zipped else ""), "Raw path changed")
        require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe raw path")
        path = root
        require(not path.is_symlink(), "Symlink archive root")
        for part in relative.parts:
            path = path / part
            require(not path.is_symlink(), "Symlink raw input")
        archived = path.read_bytes()
        require(len(archived) == entry["archiveBytes"] and sha(archived) == entry["archiveSha256"], "Archived hash changed: " + logical)
        require(entry["encoding"] == ("gzip" if zipped else "identity") and 0 <= entry["rawBytes"] <= 8 * 1024 * 1024, "Encoding or size changed")
        if zipped:
            require(archived[:2] == b"\x1f\x8b" and archived[4:8] == b"\0" * 4 and not archived[3] & 8, "Gzip metadata changed")
            with gzip.GzipFile(fileobj=io.BytesIO(archived)) as stream:
                value = stream.read(entry["rawBytes"] + 1)
        else:
            value = archived
        require(len(value) == entry["rawBytes"] and sha(value) == entry["rawSha256"], "Raw hash changed: " + logical)
        raw[logical] = value
        paths.add(relative.as_posix())
    require(paths == {p.relative_to(root).as_posix() for p in (root / "raw").rglob("*") if p.is_file()}, "Unindexed raw input")
    data = lambda name: json.loads(raw[name])
    cancelled, unit = data("cancelled001/unit.json"), data("native002/unit.json")
    require(cancelled["status"] == "CANCELLED_BEFORE_DEVICE_ACCESS" and cancelled["nativeCalls"] == 0
            and not cancelled["commands"] and cancelled["nativeQualification"] == "NOT_QUALIFIED", "Cancelled preparation was promoted")
    require(unit["status"] == "DIAGNOSTIC_CASE_FAIL_RESTORED" and unit["nativeCalls"] == 1
            and unit["nativeQualification"] == "NOT_QUALIFIED" and unit["runnerExitCode"] == 1, "Native failure was promoted")
    for prefix, record in (("cancelled001", cancelled), ("native002", unit)):
        require(record["source"] == SOURCE and record["appProducer"] == PRODUCER and record["case"] == "pinch"
                and record["serial"] == "emulator-5558" and record["driverSha256"] == sha(raw[prefix + "/driver.py"]), "Driver or source binding changed")
        require([x["sha256"] for x in record["diagnosticTriplet"]] == DIAGNOSTIC
                and [x["sha256"] for x in record["restoreTriplet"]] == STANDARD, "APK triplet identity changed")
    manifest = data("native002/pinch-once/manifest.json")
    result = data(NATIVE + "result.json")
    require(unit["runnerManifestSha256"] == sha(raw["native002/pinch-once/manifest.json"])
            and unit["runnerResult"] == manifest and manifest["native"] == result, "Runner result binding changed")
    require(manifest["status"] == result["status"] == "FAIL" and manifest["qualification"] == result["qualification"] == "NOT_QUALIFIED", "Recorded failure was reclassified")
    require(manifest["requestedCases"] == "pinch" and manifest["canvasInputTraceRequested"] is True
            and manifest["zoomOutObservationsRequested"] is True and len(result["cases"]) == 1, "Native selection changed")
    case = result["cases"][0]
    require(case["case"] == "pinch" and case["status"] == "FAIL" and case["elapsedMillis"] == 10854
            and "Touch pinch did not preserve the injected span ratio" in case["error"], "Original case failure changed")
    motions = [event for event in result["events"] if event["kind"] == "motion" and event["operation"] == "pinch"]
    require(len(motions) == 17 and all(event["accepted"] is True and event["status"] == "accepted" for event in motions), "Accepted injection record changed")
    require([event["actionMasked"] for event in motions] == [0, 5] + [2] * 13 + [6, 1]
            and unit["injectedPinchMotionEvents"] == unit["acceptedPinchMotionEvents"] == 17, "Pinch event sequence changed")
    require(re.findall(r"(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", raw["native002/pinch-once/instrumentation.txt"].decode()) == ["0"], "Cancelled instrumentation terminal changed")
    private = {name[len(NATIVE):] for name in raw if name.startswith(NATIVE)}
    require(private == PRIVATE_FILES and raw[NATIVE + "0-fixture.circ"] == raw[NATIVE + "1-pinch-before.circ"], "Private file/preceding XML boundary changed")
    lines = raw[NATIVE + "zoom-out-observations.jsonl"].splitlines(keepends=True)
    zoom = [json.loads(line) for line in lines]
    header, summary, records = zoom[0], zoom[-1], zoom[1:-1]
    require(header["schema"] == "zoom-out-observations-v1" and len(records) == summary["recordCount"] == 117
            and sum(map(len, lines[1:-1])) == summary["recordBytes"] == 28298, "ZoomOut retained count/bytes changed")
    require(summary["groupsStarted"] == 4 and summary["incomplete"] is False
            and all(summary[key] == 0 for key in ("droppedRecords", "oversizedRecords", "invalidRecords", "encodingErrors"))
            and all(len(line) <= 512 for line in lines[1:-1]), "ZoomOut collection loss changed")
    for ordinal, count in enumerate((1, 1, 1, 102), 1):
        group = [row for row in records if row["z"] == ordinal]
        require([row["k"] for row in group] == ["B", "A"] + ["P"] * count + ["T"], "ZoomOut lifecycle changed")
        before, action, terminal = group[0], group[1], group[-1]
        polls = group[2:-1]
        require(action["emitted"] is True and action["accepted"] is True and action["blocked"] is False
                and terminal["n"] == count and terminal["phase"] == "COMPLETE" and terminal["ok"] is True, "ZoomOut completion changed")
        require([row["n"] for row in polls] == list(range(1, count + 1)) and all(row["lt"] == before["s"] - 1
                and row["ok"] is (row["s"] < row["lt"]) for row in polls)
                and [row["ok"] for row in polls] == [False] * (count - 1) + [True], "Retained comparison changed")
    trace = data(NATIVE + "canvas-input-trace.json")
    require(trace["input"]["total"] == trace["input"]["count"] == len(trace["input"]["entries"]) == 34
            and trace["input"]["dropped"] == trace["input"]["oversized"] == 0, "Input trace count changed")
    require(trace["state"]["total"] == 198 and trace["state"]["count"] == len(trace["state"]["entries"]) == 192
            and trace["state"]["dropped"] == 6 and trace["state"]["oversized"] == 0, "State trace loss changed")
    sequences = {entry["sequence"] for channel in ("input", "state") for entry in trace[channel]["entries"]}
    require(trace["lastSequence"] == 232 and set(range(1, 233)) - sequences == set(range(2, 8)), "Trace gap changed")
    fixture_name = ET.fromstring(raw[NATIVE + "0-fixture.circ"]).find("main").get("name")
    fixture_sequences = [entry["sequence"] for entry in trace["state"]["entries"] if entry["record"].get("documentId") == fixture_name]
    require(min(fixture_sequences) == 13, "First retained fixture record changed")
    require(result["canvasInputTrace"]["inputDropped"] == 0 and result["canvasInputTrace"]["stateDropped"] == 6, "Trace receipt hid loss")
    require(unit["restorationStatus"] == "PASS" and not unit["cleanupErrors"] and unit["configurationUnchanged"] is True
            and unit["configurationBefore"] == unit["configurationAfter"] and unit["lastAnrUnchanged"] is True, "Restoration result changed")
    require(raw["native002/raw/before-lastanr.txt"] == raw["native002/raw/after-lastanr.txt"], "ANR observation changed")
    for key, expected in (("standardInstalledBefore", STANDARD), ("standardInstalledAfter", STANDARD),
                          ("diagnosticInstalledBefore", DIAGNOSTIC), ("diagnosticInstalledAfter", DIAGNOSTIC)):
        require([item["sha256"] for item in unit[key]] == expected, "Installed APK observation changed: " + key)
    require(len(unit["ownedPidsAfter"]) == 3 and set(unit["ownedPidsAfter"].values()) == {""}
            and len(unit["finalCurrentFocus"]) == 1 and "NexusLauncherActivity" in unit["finalCurrentFocus"][0], "Handoff state changed")
    require(unit["configurationAfter"]["touchExplorationOffObserved"] is True
            and unit["configurationAfter"]["enabledServicesSettingEmpty"] is True, "Reader-off observation changed")
    analysis_prefix = "offline-analysis/"
    analysis_raw = raw[analysis_prefix + "analysis-index.json"]
    require(sha(analysis_raw) == "e554e7b0c128a2a5fdeb9d76084ef6fc7a53762f64811ca77e520f38ad7db56d", "Offline analysis index changed")
    analysis = json.loads(analysis_raw)
    require(analysis["status"] == "OFFLINE_ANALYSIS_COMPLETE" and analysis["nativeResultPreserved"] == "FAIL_NOT_QUALIFIED"
            and analysis["literalRatioAssertionOperands"] == "NOT_SERIALIZED" and analysis["nextAnchorAssertion"].startswith("NOT_RUN"), "Offline analysis was promoted")
    require(all(analysis[key] is False for key in ("deviceQueries", "nativeRun", "cacheCalls", "gradleOrAppBuild", "oracleOrProductEdits")), "Offline scope changed")
    require(len(analysis["files"]) == 10 and {name[len(analysis_prefix):] for name in raw if name.startswith(analysis_prefix)}
            == set(analysis["files"]) | {"analysis-index.json"}, "Offline file selection changed")
    for name, identity in analysis["files"].items():
        require(len(raw[analysis_prefix + name]) == identity["bytes"] and sha(raw[analysis_prefix + name]) == identity["sha256"], "Offline file identity changed: " + name)
    math = data(analysis_prefix + "math-index.json")
    replay_raw = gzip.decompress(raw[analysis_prefix + "replay-report.json.gz"])
    require(len(replay_raw) == math["reportRawBytes"] and sha(replay_raw) == math["reportRawSha256"], "Offline replay raw identity changed")
    replay = json.loads(replay_raw)
    require(math["status"] == "PASS_OFFLINE_REPLAY_ONLY" and math["nativeResultPreserved"] == "FAIL" and math["inputFiles"] == 1867
            and math["inputBytesUnchanged"] is True and replay["status"] == "PASS_OFFLINE_ONLY" and replay["nativeStatus"] == "NOT_RUN"
            and replay["inputsUnchanged"] is True and len(replay["inputsBefore"]) == 1867 and replay["inputsBefore"] == replay["inputsAfter"], "Offline replay scope or inputs changed")
    return len(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    try:
        count = verify(args.archive)
    except (KeyError, ValueError, OSError, TypeError, EOFError, ET.ParseError) as error:
        parser.exit(1, "INVALID_ARCHIVE: " + str(error) + "\n")
    print(f"VERIFIED_ARCHIVE_ONLY files={count} native=FAIL qualification=NOT_QUALIFIED calls=1 cancelledPreparationCalls=0")


if __name__ == "__main__":
    main()
