#!/usr/bin/env python3
"""Verify retained AD28 discovery evidence offline; this does not diagnose its cause."""
import argparse
import base64
import datetime
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import plistlib
import re
import struct
import sys

APP = "thalesmms.logisim.validation.revocationfixture"
EXTENSION = APP + ".provider"
GROUP = "group." + APP
DEVICE = "AD28C68F-21D3-4FA6-BCD7-383AE2A1D372"
PRODUCER = "11fc44d53b229558fbfc57b66e0b2f2d11a0037b"
PRIOR_ARCHIVE = "124d5ef4f937780f3d972c390d492e3f2c8b965a"
DIRECTORY_UUID = "5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4"
METADATA_UUID = "C4A78BD1-44B3-4590-B045-A3AC1883D566"
INSTALLED_APP = "/Users/thales/Library/Developer/CoreSimulator/Devices/" + DEVICE + "/data/Containers/Bundle/Application/F2F94231-CEFA-4635-BEEE-31BBEBBD65DF/RevocationFixture.app"
GROUP_PATH = "/Users/thales/Library/Developer/CoreSimulator/Devices/" + DEVICE + "/data/Containers/Shared/AppGroup/" + DIRECTORY_UUID
BACKEND_SHA = "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88"
STATE_SHA = "5d70dcefd804d8ac2113e532a52c7d7091a0510f318233ec20373d36c56bdf35"
METADATA_SHA = "8c201b2817be6f80df980e79e8f90e6e2cd9dd0c22ad25220f9fc4eda5852158"
RAW_SHA = "251208aa01519842b005dd2b50b5454427b8e04f70965d9238cc3d7eb3a03596"
RAW_BYTES = 2859605
GZIP_SHA = "2644c8e0b7420b99e42595dc74520271164946fb738607a203d15950831d4348"
INDICES = [2, 18, 474, 527, 559, 560, 758, 759, 760, 761, 762, 1257, 1258, 1588]
STATE = {"authenticationAllowed": True, "backendByteCount": 552, "backendSHA256": BACKEND_SHA,
         "generation": 1, "schemaVersion": 1}
LIMITS = {"initialFailedFetchResolvedPath": False, "permissionOrSigningCauseEstablished": False,
          "mainQualified": False, "newExperimentExecutedByCensus": False}
PYTHON_ARGV_HASHES = {
    "exact-appgroup-current-metadata": "02c762112a0bbb9b05f723cdc289051541562ce1d2548c3d5d1c336ebbbc79d9",
    "exact-appgroup-existing-fixture-state": "7d5217eb0f11ac97c1c7721e7c180e9070c7128945b2b8f215fd05f7ab12bb05",
    "exact-appgroup-unchanged-raw-copy": "3f575915c5120d07bc2ba69362bdf6ad288c2ec46c2f886521600936e07231d3",
}


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def equal(actual, expected, label):
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


def decode(data):
    return json.loads(data, object_pairs_hook=unique_object)


def path(root, relative):
    require(isinstance(relative, str) and bool(relative), "Empty payload path")
    value = PurePosixPath(relative)
    require(not value.is_absolute() and ".." not in value.parts and "\\" not in relative and
            str(value) == relative and "\0" not in relative, "Unsafe payload path: " + relative)
    target = root / relative
    require(target.is_file() and not target.is_symlink() and target.resolve().is_relative_to(root),
            "Missing, linked or escaping payload: " + relative)
    return target


def read(root, relative):
    return path(root, relative).read_bytes()


def document(root, relative):
    return decode(read(root, relative))


def timestamp(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def readonly_commands():
    sim = ["xcrun", "simctl"]
    spawn = sim + ["spawn", DEVICE]
    match = spawn + ["pluginkit", "-m", "-A", "-D", "-v"]
    return {
        "simctl-help-get-app-container": sim + ["help", "get_app_container"],
        "installed-app-container": sim + ["get_app_container", DEVICE, APP, "app"],
        "simctl-help-appinfo": sim + ["help", "appinfo"],
        "simctl-help-spawn": sim + ["help", "spawn"],
        "installed-appinfo": sim + ["appinfo", DEVICE, APP],
        "device-pluginkit-help": spawn + ["pluginkit", "-h"],
        "device-log-show-help": spawn + ["log", "help", "show"],
        "provider-registry-match-id": match + ["-i", EXTENSION],
        "provider-registry-match-id-and-point": match + ["-p", "com.apple.fileprovider-nonui", "-i", EXTENSION],
        "installed-app-group-containers": sim + ["get_app_container", DEVICE, APP, "groups"],
        "host-pluginkit-man": ["/usr/bin/man", "-P", "cat", "pluginkit"],
        "provider-registry-match-verbose3": match + ["-v", "-v", "-i", EXTENSION],
        "historical-registration-and-lookup-exact-ids": spawn + [
            "log", "show", "--style", "json", "--info", "--debug", "--start", "2026-11-05 06:57:30+0000",
            "--end", "2026-11-05 06:58:34+0000", "--timezone", "UTC", "--predicate",
            'eventMessage CONTAINS "' + APP + '" OR eventMessage CONTAINS "' + EXTENSION + '"'],
    }


def verify_streams(root, inventory):
    commands = document(root, inventory["commands"])
    known = readonly_commands()
    equal(sorted(command["name"] for command in commands), sorted([*known, *PYTHON_ARGV_HASHES]),
          "Exactly 13 native read-only queries and 3 bounded Python reads")
    expected_streams = {command["name"] + "." + channel for command in commands for channel in ["stdout", "stderr"]}
    equal(sorted(inventory["streamFiles"]), sorted(expected_streams), "Every command stdout/stderr is retained")
    streams = {}
    for command in commands:
        name, argv = command["name"], command["argv"]
        if name in known:
            equal(argv, known[name], "Read-only native argv whitelist: " + name)
        else:
            require(argv[:2] == ["python3", "-c"] and len(argv) == 3, "Bounded Python read argv")
            serialized = json.dumps(argv, separators=(",", ":"), ensure_ascii=False).encode()
            equal(digest(serialized), PYTHON_ARGV_HASHES[name], "Read-only Python script whitelist: " + name)
        equal(command["exit_code"], {"device-pluginkit-help": 2, "device-log-show-help": 64}.get(name, 0),
              "Retained command exit code: " + name)
        if "startedUTC" in command:
            require(timestamp(command["startedUTC"]) <= timestamp(command["finishedUTC"]), "Command chronology")
        for channel in ["stdout", "stderr"]:
            key = name + "." + channel
            entry = inventory["streamFiles"][key]
            data = read(root, entry["path"])
            if entry["encoding"] == "gzip":
                equal(key, "historical-registration-and-lookup-exact-ids.stdout", "Only the historical stream uses gzip")
                require(len(data) >= 10 and data[:3] == b"\x1f\x8b\x08" and struct.unpack("<I", data[4:8])[0] == 0,
                        "Historical gzip header has mtime 0")
                with gzip.GzipFile(fileobj=io.BytesIO(data)) as source:
                    data = source.read(RAW_BYTES + 1)
                require(len(data) <= RAW_BYTES, "Historical gzip exceeds retained size")
            else:
                equal(entry["encoding"], "identity", "Stream encoding")
            equal(len(data), entry["rawBytes"], "Stream raw length: " + key)
            equal(digest(data), entry["rawSHA256"], "Stream raw hash: " + key)
            equal(len(data), command[channel + "Bytes"], "Command stream length: " + key)
            equal(digest(data), command[channel + "SHA256"], "Command stream hash: " + key)
            streams[key] = data
    return streams


def verify_group(root, inventory, streams):
    group = inventory["group"]
    for key, expected in [("directoryUUID", DIRECTORY_UUID), ("metadataUUID", METADATA_UUID), ("path", GROUP_PATH),
                          ("backendBytes", 552), ("backendSHA256", BACKEND_SHA)]:
        equal(group[key], expected, "Exact group " + key)
    raw_metadata, raw_state = read(root, group["metadata"]), read(root, group["state"])
    equal(digest(raw_metadata), METADATA_SHA, "Retained old metadata bytes")
    equal(digest(raw_state), STATE_SHA, "Retained 001 state, excluding followup 002")
    metadata = plistlib.loads(raw_metadata)
    for key, expected in [("MCMMetadataIdentifier", GROUP), ("MCMMetadataUUID", METADATA_UUID),
                          ("MCMMetadataCreator", APP), ("MCMMetadataContentClass", 7), ("MCMMetadataSchemaVersion", 2)]:
        equal(metadata[key], expected, "MCM metadata " + key)
    current = document(root, group["metadataQuery"])
    equal(current["root"], GROUP_PATH, "As-of-query metadata path")
    equal(current["groupIdentifier"], GROUP, "As-of-query group identifier")
    equal(current["exists"], True, "As-of-query group existence")
    root_attributes = current["rootAttributes"]
    equal(root_attributes["path"], GROUP_PATH, "Retained group root attribute path")
    equal(root_attributes["relative"], ".", "Retained group root relative path")
    equal(root_attributes["birthtimeUTC"], "2026-11-05T06:58:17.691188+00:00", "Retained group root birthtime")
    entries = {entry["relative"]: entry for entry in current["entries"]}
    metadata_entry = entries[".com.apple.mobile_container_manager.metadata.plist"]
    equal(metadata_entry["path"], GROUP_PATH + "/.com.apple.mobile_container_manager.metadata.plist", "Retained metadata entry path")
    equal(metadata_entry["birthtimeUTC"], "2026-11-05T06:58:17.691401+00:00", "Retained metadata entry birthtime")
    equal(metadata_entry["metadata"], metadata, "Query/raw plist agreement")
    equal(metadata_entry["sha256"], METADATA_SHA, "Query/raw plist hash")
    require(current["queriedUTC"].startswith("2026-11-05T07:17:"), "Metadata is the retained 07:17 query")
    state = decode(raw_state)
    equal(state["state"], STATE, "Retained backend state")
    equal(state["nextSequence"], 4, "Only three original events")
    equal([event["sequence"] for event in state["events"]], [1, 2, 3], "Original sequence 1/2/3 only")
    equal([event["kind"] for event in state["events"]],
          ["prepare", "domain.registration.requested", "domain.registration.failed"], "Original event kinds")
    equal([event["timestamp"] for event in state["events"]],
          ["2026-11-05T06:58:24.548Z", "2026-11-05T06:58:29.879Z", "2026-11-05T06:58:29.901Z"], "Original event timestamps")
    require(all(event["process"] == "RevocationFixture:69225" for event in state["events"]), "Original fixture process")
    equal(state["events"][1]["details"], {"domain": "logisim-revocation-fixture-v1"}, "Original requested domain")
    failure = state["events"][2]
    equal(failure["errorDomain"], "NSFileProviderErrorDomain", "Original error domain")
    equal(failure["errorCode"], -2001, "Original root code")
    equal([(item["domain"], item["code"]) for item in failure["errorDiagnostic"]["chain"]],
          [("NSFileProviderErrorDomain", -2001), ("NSFileProviderErrorDomain", -2014)], "Original -2001/-2014 chain")
    equal(failure["errorDiagnostic"]["termination"], "complete", "Bounded traversal termination")
    backend = base64.b64decode(state["contents"], validate=True)
    equal([len(backend), digest(backend)], [552, BACKEND_SHA], "Raw state base64 backend")
    query = document(root, group["stateQuery"])
    equal([query["bytes"], query["sha256"]], [len(raw_state), STATE_SHA], "State query/raw-copy agreement")
    equal(query["decoded552ByteLeaves"], [{"path": "contents", "encoding": "base64", "bytes": 552, "sha256": BACKEND_SHA}],
          "State query decoded backend")
    require(query["queriedUTC"].startswith("2026-11-05T07:18:"), "State is the retained 07:18 query")
    copy = decode(streams["exact-appgroup-unchanged-raw-copy.stdout"])
    for name, raw in [(".com.apple.mobile_container_manager.metadata.plist", raw_metadata),
                      ("RevocationFixture/fixture-state.json", raw_state)]:
        equal([copy[name]["bytes"], copy[name]["sha256"]], [len(raw), digest(raw)], "Unchanged raw-copy metadata")
        equal(base64.b64decode(copy[name]["dataBase64"], validate=True).hex(), raw.hex(), "Unchanged raw-copy bytes")
    return state


def verify_history(root, inventory, streams, state):
    history = inventory["historical"]
    equal(history["indices"], INDICES, "Exact retained historical indices")
    raw = streams[history["rawStream"]]
    equal([len(raw), digest(raw)], [RAW_BYTES, RAW_SHA], "Pinned complete historical JSON stream")
    compressed = read(root, inventory["streamFiles"][history["rawStream"]]["path"])
    equal(digest(compressed), GZIP_SHA, "Pinned deterministic gzip bytes")
    events = decode(raw)
    equal(len(events), 1632, "Complete historical event count")
    equal(history["rawEventCount"], 1632, "Declared historical event count")
    selected = document(root, history["selected"])
    expected = [{"index": index, "timestamp": events[index]["timestamp"],
                 "process": events[index]["processImagePath"].rsplit("/", 1)[-1],
                 **{key: events[index][key] for key in ["subsystem", "category", "eventMessage"]}} for index in INDICES]
    equal(selected, expected, "Selected events equal their exact raw indices")
    by_index = {event["index"]: event for event in selected}
    for index, text in [(527, "plugin INSTALLED"), (760, "Error fetching group container for " + GROUP),
                        (761, "Cannot deduce the provided items URL"), (762, "Failed to create descriptor"),
                        (1257, "Successfully updated schema"), (1258, GROUP_PATH), (1588, "No provider found with identifier (null)")]:
        require(text in by_index[index]["eventMessage"], "Observed historical event " + str(index))
    times = [timestamp(by_index[index]["timestamp"]) for index in [527, 760, 761, 762, 1257, 1258]]
    current = document(root, inventory["group"]["metadataQuery"])
    metadata_entry = next(entry for entry in current["entries"] if entry["relative"] == ".com.apple.mobile_container_manager.metadata.plist")
    times += [timestamp(current["rootAttributes"]["birthtimeUTC"]), timestamp(metadata_entry["birthtimeUTC"])]
    times += [timestamp(state["events"][0]["timestamp"]), timestamp(state["events"][1]["timestamp"]),
              timestamp(by_index[1588]["timestamp"]), timestamp(state["events"][2]["timestamp"])]
    require(all(first < second for first, second in zip(times, times[1:])),
            "Install/fetch/descriptor/MCM/Prepare/Register chronology")


def verify_sources_and_identity(root, inventory, streams, state):
    app_info = plistlib.loads(read(root, "app-installed-Info.plist"))
    provider_info = plistlib.loads(read(root, "provider-installed-Info.plist"))
    equal(app_info["CFBundleIdentifier"], APP, "Installed app ID")
    equal(provider_info["CFBundleIdentifier"], EXTENSION, "Installed extension ID")
    equal(provider_info["NSExtension"]["NSExtensionFileProviderDocumentGroup"], GROUP, "Installed extension group ID")
    equal(provider_info["NSExtension"]["NSExtensionPointIdentifier"], "com.apple.fileprovider-nonui", "Extension point")
    producer = document(root, "fixture-producer-artifact.json")
    equal(producer["source"], PRODUCER, "Producer source pin")
    equal(producer["nativeExecution"], "NOT_RUN", "Producer build-only scope")
    equal(producer["domainRegistration"], "NOT_RUN", "Producer did not register a domain")
    equal(producer["appGroup"], GROUP, "Producer group")
    for filename, source_key in [("source-FixtureStore.swift", "Shared/FixtureStore.swift"),
                                 ("source-ControlApp.swift", "ControlApp/RevocationFixtureApp.swift")]:
        data = read(root, filename)
        equal(digest(data), producer["sourceFiles"][source_key], "Producer snapshot hash: " + filename)
        equal(inventory["files"][filename]["origin"]["gitPin"], PRODUCER, "Producer snapshot pin")
    source = read(root, "source-FixtureStore.swift").decode()
    equal(re.findall(r'static let appGroupIdentifier\s*=\s*"([^"\n]+)"', source), [GROUP],
          "Producer source App Group constant")
    initializers = re.findall(r'convenience init\(\) throws \{(.*?)\n    \}', source, re.DOTALL)
    require(len(initializers) == 1 and
            re.search(r'guard let container = FileManager\.default\.containerURL\(\s*forSecurityApplicationGroupIdentifier: FixtureConfiguration\.appGroupIdentifier\) else \{', initializers[0]) and
            re.search(r'self\.init\(directory: container\.appendingPathComponent\("RevocationFixture", isDirectory: true\)\)', initializers[0]),
            "Producer default initializer uses the exact App Group and RevocationFixture directory")
    literal = re.search(r'static let initialCircuit = """\n(.*?)\n([ ]*)"""', source, re.DOTALL)
    require(literal is not None, "Producer initialCircuit literal")
    indent = literal.group(2)
    lines = literal.group(1).splitlines()
    require(all(line.startswith(indent) for line in lines), "Swift literal closing indentation")
    circuit = "\n".join(line[len(indent):] for line in lines).encode()
    equal([len(circuit), digest(circuit)], [552, BACKEND_SHA], "Producer literal is the retained 552-byte backend")
    equal(circuit.hex(), base64.b64decode(state["contents"], validate=True).hex(), "Producer literal/raw state bytes")
    paths = document(root, "installed-paths.json")
    equal(paths["app"], INSTALLED_APP, "Exact retained AD28 installed app path")
    require(paths["provider"] == paths["app"] + "/PlugIns/RevocationProvider.appex", "Embedded provider path")
    equal(streams["installed-app-container.stdout"].decode().strip(), paths["app"], "Queried installed app path")
    require(streams["installed-app-group-containers.stdout"] == b"", "Retained empty group-container query")
    for name in ["provider-registry-match-id", "provider-registry-match-id-and-point", "provider-registry-match-verbose3"]:
        text = streams[name + ".stdout"].decode()
        require(EXTENSION in text and paths["provider"] in text and "E8471350-97AE-41DE-B670-F66B2FE4EA4B" in text and
                "(1 plug-in)" in text, "Registry query observed one exact extension: " + name)
    verbose = streams["provider-registry-match-verbose3.stdout"].decode()
    for field, expected in [("SDK", "com.apple.fileprovider-nonui"), ("Parent Bundle", paths["app"]),
                            ("Path", paths["provider"]), ("UUID", "E8471350-97AE-41DE-B670-F66B2FE4EA4B"),
                            ("Timestamp", "2026-11-05 06:57:46 +0000")]:
        values = re.findall(r"^\s*" + re.escape(field) + r"\s*=\s*(.*?)\s*$", verbose, re.MULTILINE)
        equal(values, [expected], "Unique exact registry verbose field: " + field)
    return producer


def verify_original_failure(root, inventory, producer, state):
    original = inventory["originalFailure"]
    for key, value in [("status", "FAIL"), ("caseSeconds", 17.003), ("exitCode", 65), ("errorCodes", [-2001, -2014])]:
        equal(original[key], value, "Original failure " + key)
    receipt, execution = document(root, original["receipt"]), document(root, original["execution"])
    equal(receipt["status"], "FAIL", "Failure receipt remains FAIL")
    equal(receipt["caseSeconds"], 17.003, "Original native case duration")
    equal(receipt["exitCode"], 65, "Original native case exit")
    equal(receipt["counts"], {"totalTestCount": 1, "passedTests": 0, "failedTests": 1, "skippedTests": 0}, "Original failed test counts")
    equal(receipt["fixtureSource"], PRODUCER, "Failure producer pin")
    equal(receipt["device"]["udid"], DEVICE, "Failure cohort identity")
    equal(receipt["classification"]["underlyingCause"], "UNDETERMINED", "Failure cause remains undetermined")
    equal(receipt["limits"]["successfulRegistration"], "NOT_OBSERVED", "No successful registration claim")
    equal(receipt["publicObservations"]["backendStateAfterFailure"], state["state"], "Original failure/backend agreement")
    equal(receipt["publicObservations"]["errorDiagnostic"], state["events"][2]["errorDiagnostic"], "Original error chain agreement")
    for key in ["app", "provider"]:
        equal(receipt[key], producer[key], "Original/producer artifact identity: " + key)
    equal(execution["exit_code"], 65, "Original xcodebuild exit code")
    terminal = read(root, original["terminal"]).decode()
    operation = read(root, original["operation"]).decode()
    for text in [terminal, operation]:
        require("status=Operation failed: NSFileProviderErrorDomain / -2001" in text, "Original failure UI status")
        snapshot = decode(text.split("\nSNAPSHOT\n", 1)[1])
        equal(snapshot, {"events": state["events"], "state": state["state"]}, "Original terminal snapshot")
    log = read(root, original["testLog"]).decode()
    require("failed (17.003 seconds)." in log and "Executed 1 test, with 1 failure" in log and
            "** TEST EXECUTE FAILED **" in log, "Original XCTest terminal failure")


def verify(root):
    root = Path(root)
    require(not root.is_symlink(), "Linked archive root")
    root = root.resolve(strict=True)
    inventory = document(root, "inventory.json")
    equal(inventory["schema"], 1, "Inventory schema")
    equal(inventory["status"], "DIAGNOSTIC_ONLY", "Discovery is diagnostic only")
    equal(inventory["scope"], "Read-only AD28 File Provider discovery evidence; no new native execution or Main qualification", "Discovery scope")
    for key, expected in [("device", DEVICE), ("appID", APP), ("extensionID", EXTENSION), ("groupID", GROUP),
                          ("producerPin", PRODUCER), ("sourceFailureArchive", PRIOR_ARCHIVE),
                          ("excludedFollowupRun", "ios-provider-existing-group-ad28-002")]:
        equal(inventory[key], expected, "Discovery identity: " + key)
    equal(inventory["limits"], LIMITS, "No unsupported causal or qualification claim")
    require(len(inventory["files"]) >= 242, "Complete retained discovery inventory")
    allowed = {"inventory.json", "verify-archived-evidence.py", "test_verify.py", "README.md", "plan.md", "differential-plan.md", ".gitattributes"}
    for candidate in root.rglob("*"):
        require(not candidate.is_symlink(), "Symlink in retained archive")
        if candidate.is_file():
            relative = candidate.relative_to(root).as_posix()
            require(relative in inventory["files"] or relative in allowed, "Uninventoried payload: " + relative)
    for name, record in inventory["files"].items():
        data = read(root, name)
        equal(len(data), record["bytes"], "Payload length: " + name)
        equal(digest(data), record["sha256"], "Payload hash: " + name)
    streams = verify_streams(root, inventory)
    state = verify_group(root, inventory, streams)
    verify_history(root, inventory, streams, state)
    producer = verify_sources_and_identity(root, inventory, streams, state)
    verify_original_failure(root, inventory, producer, state)
    return {"status": "PASS", "scope": "Archive consistency only", "discoveryStatus": "DIAGNOSTIC_ONLY",
            "commands": {"nativeReadOnly": 13, "pythonReads": 3}, "rawHistoricalEvents": 1632,
            "selectedHistoricalEvents": 14, "backendBytes": 552, "originalNativeStatus": "FAIL",
            "originalCaseSeconds": 17.003, "originalExitCode": 65, "causalAttribution": "NOT_ESTABLISHED",
            "mainQualified": False, "newExperimentExecutedByCensus": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args(argv)
    try:
        result = verify(args.root)
    except (VerificationError, OSError, ValueError, KeyError, TypeError, IndexError, EOFError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
