#!/usr/bin/env python3
"""Verify the retained, partial SAP-1 Simulator evidence without Git or Apple tools."""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import xml.etree.ElementTree as ET

PINS = {
    "appProducer": "6cef05429cf66be23569aaebc3c72950cf3fe53a",
    "appTestValidation": "85da031e4ed11474c2e763976392729810ef142d",
    "uiHarness": "ba9d9682248fca28adbf5fc050f018156c7edc5c",
}
FINGERPRINT = {
    "bundle_id": "thalesmms.logisim.mobile",
    "executable_sha256": "198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946",
    "bundle_tree_sha256": "d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080",
}
FIXTURE_SHA = "2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac"
CONTRACT_SHA = "9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9"
CATALOG_SHA = "83a6f69e2a6d530a8afff326250dcd2c09416d4deeb35187d03117b2b5f6c6ca"
HARNESS_BUNDLE_SHA = "6bb0b7c50ff418cfe5ead624b9c3ec789a3d4047652904e0f6e9d06f5e95d67d"
APP_SOURCE_BUNDLE_SHA = "6f7a7c99b975f3a4bb1a12fd3ea71b91640a5925f3a72cc0e0b25b44b09005e8"
ARTIFACT_SHA = "6006751321c48752f5676ddaba3b6ae20cbc7f10f43f60a5000c855a1e34ac17"
DEVICE = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"
CASES = {
    "sequential": ("testSap1SequentialExecution", "1b224cef7bca", 140.001, 56, 57),
    "automatic": ("testSap1AutomaticRunPause", "d870b0afd63d", 28.827, 0, 1),
}
LIMITS = [
    "Selected test only; issue #36 remains partial",
    "Simulator only; no physical device",
    "No project XML was copied or compared by this selected case",
    "Pause checks two counter readings around a requested 2500 ms interval, not every internal state or continuous stability",
    "Semantic display states are not an independent pixel oracle",
    "No other projects, Save/restart, SystemOpen or multiwindow qualified",
]


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def equal(actual, expected, label):
    # JSON equality keeps booleans distinct from integers and disallows NaN.
    require(json.dumps(actual, sort_keys=True, allow_nan=False) ==
            json.dumps(expected, sort_keys=True, allow_nan=False), label)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def decode_json(data):
    return json.loads(data, object_pairs_hook=unique_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(VerificationError("Non-finite JSON: " + value)))


def payload_path(root, relative):
    require(isinstance(relative, str) and bool(relative), "Empty payload path")
    path = PurePosixPath(relative)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in relative and
            str(path) == relative and "\0" not in relative, "Unsafe payload path: " + relative)
    candidate = root / relative
    require(candidate.is_file() and not candidate.is_symlink(), "Missing or linked payload file: " + relative)
    require(candidate.resolve().is_relative_to(root), "Payload path escapes root: " + relative)
    return candidate


def read(root, relative):
    return payload_path(root, relative).read_bytes()


def document(root, relative):
    return decode_json(read(root, relative))


def fingerprint(value, label):
    equal({key: value.get(key) for key in FINGERPRINT}, FINGERPRINT, label + " fingerprint")
    equal(value.get("version"), "0.2.0", label + " version")
    equal(value.get("build"), "2", label + " build")


def bundle_digest(root, files, prefix):
    rows = "".join(name + "\0" + digest(read(root, name)) + "\n"
                   for name in sorted(files) if name.startswith(prefix))
    return digest(rows.encode())


def verify_shared(root, manifest):
    equal(manifest.get("pins"), PINS, "Distinct producer/validation/harness pins")
    equal(manifest.get("appFingerprint"), FINGERPRINT, "Manifest app fingerprint")
    contract_bytes = read(root, "shared/harness/editor-actions-v1.json")
    require(digest(contract_bytes) == CONTRACT_SHA, "Pinned executed contract hash")
    contract = decode_json(contract_bytes)
    equal(contract["sap1"], {
        "source": "builtin-sap1", "circuitCount": 7, "componentCount": 156, "wireCount": 318,
        "resetInput": "Reset", "manualHalfCycles": 56, "assertCounterEvery": 8,
        "displayNibbles": ["0000", "1000"], "runMilliseconds": 1500,
        "pauseObservationMilliseconds": 2500,
    }, "Literal SAP-1 contract")
    require(digest(read(root, "shared/harness/Catalog.json")) == CATALOG_SHA, "Pinned catalog hash")
    require(bundle_digest(root, manifest["files"], "shared/harness/") == HARNESS_BUNDLE_SHA,
            "Pinned captured harness bundle")
    require(bundle_digest(root, manifest["files"], "shared/app-source/") == APP_SOURCE_BUNDLE_SHA,
            "Pinned producer source bundle")
    source_names = set(manifest["harnessGitPaths"])
    require(len(source_names) == 17 and all(name.endswith(".swift") for name in source_names),
            "17 recorded Swift snapshots")
    target = read(root, "shared/harness/project.yml").decode()
    target_names = re.findall(r"^\s+- ([A-Za-z0-9_]+\.swift)\s*$", target, re.MULTILINE)
    require(len(target_names) == len(set(target_names)) == 16, "16 distinct target Swift sources")
    equal(sorted(target_names), sorted(source_names - {"AccessibilityUITests.swift"}), "Target source membership")
    for name, path in manifest["harnessGitPaths"].items():
        origin = manifest["files"]["shared/harness/" + name]["origin"]
        equal(origin.get("gitPin"), PINS["uiHarness"], "Captured source pin: " + name)
        equal(origin.get("gitPath"), path, "Captured source path: " + name)
    artifact_bytes = read(root, "shared/app-artifact.json")
    require(digest(artifact_bytes) == ARTIFACT_SHA, "Pinned build-only app artifact manifest")
    artifact = decode_json(artifact_bytes)
    equal(artifact["source_sha"], PINS["appProducer"], "Artifact producer")
    equal(artifact["app_producer_source"], PINS["appProducer"], "App producer source")
    equal(artifact["test_validation_source"], PINS["appTestValidation"], "KMP validation source")
    equal(artifact["runtime_status"], "NOT_RUN", "Producer build-only scope")
    fingerprint(artifact["artifact"], "Producer artifact")
    fixture = manifest["fixture"]
    equal(fixture["sha256"], FIXTURE_SHA, "Fixture declared hash")
    equal(fixture["upstreamRepository"], "https://github.com/ThalesMMS/SAP-1-Logisim", "Fixture upstream repository")
    equal(fixture["upstreamRevision"], "ddb9dd86d601ac217157beedeb88464ee0128c87", "Fixture upstream revision")
    xml = read(root, fixture["path"])
    require(digest(xml) == FIXTURE_SHA, "Pinned SAP-1 fixture")
    generated = read(root, fixture["embeddedSourcePath"]).decode()
    match = re.search(r'CircuitExample\("sap1",\n[^\n]*\n[^\n]*\n("(?:\\.|[^"\\])*")', generated)
    require(match is not None and decode_json(match.group(1)).encode() == xml,
            "Generated Library SAP-1 XML equals the retained source fixture")
    tree = ET.fromstring(xml)
    circuits = tree.findall("circuit")
    main = [circuit for circuit in circuits if circuit.get("name") == "main"]
    equal([len(circuits), len(tree.findall(".//comp")), len(tree.findall(".//wire"))],
          [7, 156, 318], "Fixture totals, not assertions of the selected UI cases")
    require(len(main) == 1, "One main circuit")
    equal([len(main[0].findall("comp")), len(main[0].findall("wire"))], [111, 220], "Main circuit counts")
    app = read(root, fixture["libraryActionPath"]).decode()
    require(re.search(r'SmallAction\("SAP-1", strings\(Messages\.A11yLoadSap1\)\).*CircuitExamples\.find\("sap1"\)', app),
            "Real Library shortcut resolves the bundled SAP-1 example")
    return source_names


def verify_attachments(root, case, receipt, results):
    entries = document(root, case + "/attachments/manifest.json")
    require(len(entries) == 1, "One selected attachment test")
    entry = entries[0]
    equal(entry["testIdentifier"], receipt["testIdentifier"], "Attachment test identity")
    equal(entry["testIdentifierURL"].rsplit("/", 1)[-1], CASES[case][0], "Attachment test URL")
    mapping = {}
    exported = set()
    start = datetime.datetime.fromisoformat(results["started"]).timestamp()
    finish = datetime.datetime.fromisoformat(results["finished"]).timestamp()
    timestamps = {}
    for item in entry["attachments"]:
        name = item["suggestedHumanReadableName"].split("_0_", 1)[0]
        require(name not in mapping and item["exportedFileName"] not in exported, "Duplicate attachment identity")
        relative = case + "/attachments/" + item["exportedFileName"]
        payload_path(root, relative)
        mapping[name] = relative
        exported.add(item["exportedFileName"])
        equal(item["deviceId"], DEVICE, "Attachment device")
        equal(item["deviceName"], "iPhone 17e", "Attachment device name")
        equal(item["isAssociatedWithFailure"], False, "Passing attachment association")
        timestamp = item["timestamp"]
        require(type(timestamp) in (int, float) and math.isfinite(timestamp) and start <= timestamp <= finish,
                "Attachment timestamp within recorded run")
        timestamps[name] = timestamp
        if relative.endswith(".png"):
            data = read(root, relative)
            require(len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR", "PNG header")
            require(all(0 < value < 20000 for value in struct.unpack(">II", data[16:24])), "PNG dimensions")
    equal(mapping, receipt["attachments"], "Receipt attachment mapping")
    require(not any(name.endswith((".circ", ".xml")) for name in exported), "No invented XML capture")
    observed = read(root, mapping["sap1-run-pause-observation"]).decode()
    started, paused = CASES[case][3:]
    equal(observed, f"startedAt={started}, pausedAt={paused}", "Exact run/pause counter observation")
    native = read(root, mapping["native-tree"]).decode()
    require("label: 'Steps " + str(paused) + "'" in native, "Final native counter")
    for identifier in ["canvas-component:import-104", "canvas-component:import-105"]:
        require(native.count("identifier: '" + identifier + "'") == 1, "Distinct native display: " + identifier)
    if case == "sequential":
        canvas = read(root, mapping["sap1-half-cycle-56-canvas"]).decode()
        outputs = re.findall(r"Output: ([01?E]{4})(?=,|\.|$)", canvas)
        equal(outputs[-2:], ["0000", "1000"], "Canvas nibbles at 56")
        for digit, identifier in [("0", "canvas-component:import-104"), ("8", "canvas-component:import-105")]:
            value = read(root, mapping["sap1-display-" + digit]).decode()
            expected = f"identifier={identifier}, value=Hex digit display. Digit {digit}; decimal point off., expected="
            require(value.startswith(expected), "Real display identity/state " + digit)
            states = decode_json(value[len(expected):])
            require(f"Hex digit display. Digit {digit}; decimal point off." in states, "Expected display state " + digit)
        require(timestamps["sap1-run-pause-observation"] > timestamps["sap1-half-cycle-56-canvas"],
                "Run/pause follows the 56-step observation")
    else:
        require(not any(name.startswith("sap1-display-") or name == "sap1-half-cycle-56-canvas" for name in mapping),
                "Automatic case does not claim the sequential display oracle")
    return mapping


def verify_case(root, manifest, case, source_names):
    test, run_id, duration, started, paused = CASES[case]
    receipt = document(root, manifest["cases"][case])
    selected = "EditorEndToEndTests/EditorEndToEndTests/" + test
    for key, expected in [("schema", 1), ("case", case), ("status", "PASS"), ("qualification_gate", "PARTIAL"),
                          ("selected_test", selected), ("testIdentifier", "EditorEndToEndTests/" + test + "()"),
                          ("durationSeconds", duration), ("run_id", run_id), ("pins", PINS), ("limits", LIMITS)]:
        equal(receipt.get(key), expected, case + " receipt " + key)
    equal(receipt["route"], "Library SAP-1 shortcut, " + ("Reset/Step/Run/Pause" if case == "sequential" else "Reset/Run/Pause") + " UI controls; no SystemOpen", "Selected route")
    for key, filename in [("results", "results.json"), ("installedAfter", "installed-after.json"),
                          ("testLog", "test.log"), ("runConfiguration", "Run.json")]:
        equal(receipt[key], case + "/" + filename, "Case payload reference " + key)
    results = document(root, receipt["results"])
    for key, expected in [("status", "PASS"), ("qualification_gate", "PARTIAL"), ("alpha", False),
                          ("physical_device", False), ("native_execution_gate", "NATIVE_PASS"),
                          ("selected_tests", [selected]), ("run_id", run_id), ("simulator", DEVICE),
                          ("declared_baseline_sha", PINS["appProducer"]), ("resource_preflight", "PASS")]:
        equal(results.get(key), expected, case + " results " + key)
    equal(results["execution"]["status"], "PASS", "Execution status")
    equal(results["execution"]["exit_code"], 0, "Execution exit code")
    equal(results["execution"]["selection"], [selected], "Execution selection")
    equal(results["build"], {"status": "PASS", "exit_code": 0}, "Recorded harness build")
    equal(results["device"]["name"], "iPhone 17e", "Recorded device name")
    equal(results["device"]["udid"], DEVICE, "Recorded device UDID")
    equal(results["device"]["runtime"], "com.apple.CoreSimulator.SimRuntime.iOS-27-0", "Simulator runtime")
    fingerprint(results["artifact"], "Requested app")
    installation = results["main_app_installation"]
    equal(installation["status"], "REUSED_IDENTICAL", "Installed app identity gate")
    fingerprint(installation["before"], "Installed before")
    fingerprint(installation["installed"], "Installed during run")
    after = document(root, receipt["installedAfter"])
    equal(after["status"], "MATCH", "Read-only installed-after gate")
    fingerprint(after["artifact"], "Installed after both cases")
    run = document(root, receipt["runConfiguration"])
    equal(run, results["configuration"], "Executed Run configuration")
    equal(run["run_id"], run_id, "Executed Run identity")
    equal(run["simulatorUDID"], DEVICE, "Executed Run simulator")
    equal(run["contract"], "editor-actions-v1", "Executed contract resource name")
    equal(results["catalog"]["sha256"], CATALOG_SHA, "Executed catalog")
    equal(sorted(results["harness_sources"]), sorted(source_names), "17 captured Swift source names")
    for name, recorded_hash in results["harness_sources"].items():
        require(digest(read(root, "shared/harness/" + name)) == recorded_hash, "Recorded source hash: " + name)
    expected_obs = {"manualHalfCycles": 56 if case == "sequential" else None,
                    "assertCounterEvery": 8 if case == "sequential" else None,
                    "canvasOutputSuffix": ["0000", "1000"] if case == "sequential" else None,
                    "hexDisplays": [{"identifier": "canvas-component:import-104", "digit": "0"},
                                    {"identifier": "canvas-component:import-105", "digit": "8"}] if case == "sequential" else [],
                    "runStartedAt": started, "pausedAt": paused, "requestedRunMilliseconds": 1500,
                    "pauseObservationMilliseconds": 2500}
    equal(receipt["observations"], expected_obs, "Literal observations and scope")
    verify_attachments(root, case, receipt, results)
    log = read(root, receipt["testLog"]).decode()
    passed = re.findall(r"Test Case '-\[EditorEndToEndTests\.EditorEndToEndTests (\w+)\]' passed \(([0-9.]+) seconds\)\.", log)
    equal(passed, [(test, f"{duration:.3f}")], "One native test and literal duration")
    require("Executed 1 test, with 0 failures (0 unexpected)" in log and "** TEST EXECUTE SUCCEEDED **" in log,
            "Native zero-failure terminal result")
    require(" failed " not in log and "SystemOpen" not in log, "No failed case or SystemOpen route")
    for label in ["Open circuits and library, Library", "Load SAP-1 example, SAP-1", "Reset simulation, Reset",
                  "Toggle input Reset: 0, Reset 0", "Toggle input Reset: 1, Reset 1", "▶ Run, ▶ Run", "Ⅱ Pause, Ⅱ Pause"]:
        require('Tap "' + label + '" Button' in log, "Real UI action: " + label)
    taps = re.findall(r'\bt =\s+[0-9.]+s Tap "Advance the clock one half-cycle, Step" Button', log)
    equal(len(taps), 56 if case == "sequential" else 0, "Manual Step tap count")
    queries = re.findall(r"t =\s+([0-9.]+)s Get all elements bound by index for: Elements matching predicate 'label MATCHES \"\^(?:Steps|Passos)", log)
    require(len(queries) >= 2, "Two final counter queries")
    gap = float(queries[-1]) - float(queries[-2])
    require(gap >= 2.5, "Final counter queries separated by at least the requested 2500 ms")
    return {"status": "PASS", "qualification_gate": "PARTIAL", "durationSeconds": duration,
            "runStartedAt": started, "pausedAt": paused, "finalCounterQueryGapSeconds": round(gap, 2)}


def verify(root):
    root = Path(root)
    require(not root.is_symlink(), "Linked archive root")
    root = root.resolve(strict=True)
    manifest = document(root, "manifest.json")
    equal(manifest.get("schema"), 1, "Manifest schema")
    equal(manifest.get("qualification_gate"), "PARTIAL", "Manifest remains partial")
    equal(manifest.get("scope"), "Selected SAP-1 iPhone 17e Simulator cases only", "Selected scope")
    equal(manifest.get("cases"), {name: name + "/receipt.json" for name in CASES}, "Only two retained cases")
    require(isinstance(manifest.get("files"), dict) and len(manifest["files"]) >= 64, "Payload inventory")
    for path in root.rglob("*"):
        require(not path.is_symlink(), "Symlink in archive: " + str(path.relative_to(root)))
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            require(relative in manifest["files"] or relative in {"manifest.json", "verify.py", "test_verify.py", "README.md", "sequential/README.md", "automatic/README.md", ".gitattributes"},
                    "Uninventoried file: " + relative)
    for name, expected in manifest["files"].items():
        data = read(root, name)
        equal(len(data), expected.get("bytes"), "Payload length: " + name)
        equal(digest(data), expected.get("sha256"), "Payload hash: " + name)
    names = verify_shared(root, manifest)
    return {"status": "PASS", "qualification_gate": "PARTIAL", "files": len(manifest["files"]),
            "cases": {case: verify_case(root, manifest, case, names) for case in CASES}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args(argv)
    try:
        result = verify(args.root)
    except (VerificationError, OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
