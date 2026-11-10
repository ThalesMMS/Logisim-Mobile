#!/usr/bin/env python3
"""Verify the retained preflight failure using Python's standard library only."""
from datetime import datetime
from pathlib import Path, PurePosixPath
import hashlib
import json
import plistlib
import re
import sys

sys.dont_write_bytecode = True
from openstep_inventory import parse

SOURCE = "ab6d78ce96e93794bae25855210b9c9200ebd9ad"
MAIN = "thalesmms.logisim.mobile"
FIXTURE = "thalesmms.logisim.validation.revocationfixture"
PROVIDER = FIXTURE + ".provider"
GROUP = "group." + FIXTURE
RAW_SHA = "3c665ecc5253c87abfd631dd5483c12b1333dcea042771221c9b8d07ceb9f3d5"
MAIN_TREE = "813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef"
APP_TREE = "e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5"
PROVIDER_TREE = "e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def relative(name):
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and all(x not in (".", "..", "") for x in name.split("/")) and
            "\\" not in name, "Unsafe archive path")
    return path


def verify(folder):
    folder = Path(folder).resolve()
    require(not any(p.is_symlink() for p in folder.rglob("*")), "Symlinks are not evidence")
    expected = {}
    for line in (folder / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        relative(name)
        require(re.fullmatch(r"[0-9a-f]{64}", digest) and name not in expected, "Invalid or duplicate checksum entry")
        path = folder / name
        require(path.is_file() and not path.is_symlink() and sha(path.read_bytes()) == digest, "Changed archive file: " + name)
        expected[name] = digest
    all_files = {p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file() and p != folder / "SHA256SUMS"}
    require(set(expected) == all_files, "Absent or unlisted archive file")
    manifest = json.loads((folder / "manifest.json").read_text())
    names = set()
    for entry in manifest["files"]:
        name = entry["path"]
        relative(name)
        require(name.startswith("evidence/") and name not in names, "Invalid or duplicate evidence entry")
        names.add(name)
        raw = (folder / name).read_bytes()
        require(len(raw) == entry["bytes"] and sha(raw) == entry["sha256"], "Evidence manifest mismatch: " + name)
    require(names == {n for n in all_files if n.startswith("evidence/")}, "Evidence inventory is incomplete")
    run = folder / "evidence/run"
    read = lambda name: json.loads((run / name).read_text())
    receipt = json.loads((folder / "receipt.json").read_text())
    results, commands, preflight = read("results.json"), read("commands.json"), read("preflight.json")
    require(receipt["status"] == results["status"] == "FAIL", "The failed outcome must remain FAIL")
    require(results["native_execution_gate"] == receipt["nativeExecutionGate"] == "NATIVE_INCOMPLETE" and
            results["test_execution_gate"] == receipt["testExecutionGate"] == "NATIVE_NOT_RUN" and
            results["qualification_gate"] == receipt["qualificationGate"] == "NOT_QUALIFIED", "Execution gates changed")
    require(receipt["testCounts"] is None and receipt["scope"] == "PRE_NATIVE_PREFLIGHT_FAILURE", "An unexecuted test cannot gain counts or qualification")
    require(receipt["phaseOccurred"] == {"signatureRead": True, "resourceStaging": True, "projectGeneration": True,
            "inventoryQueries": True, "installation": False, "compilation": False, "buildWrapper": False,
            "XCTest": False, "FixtureUI": False, "MainLaunch": False} and
            receipt["cohortTransitionOccurred"] is False and receipt["retryIncluded"] is False and
            receipt["authorizationFailure"] is False and receipt["backendAuthenticationState"] == "NOT_OBSERVED" and
            receipt["newProductCrash"] == "NOT_OBSERVED", "The attempted phase or causal scope changed")
    require(results["error"] == {"type": "JSONDecodeError", "message": "Expecting ':' delimiter: line 2 column 24 (char 25)"}, "Error boundary changed")
    require(not {"execution", "testSummary", "buildSerialization", "installedBeforeTest", "installationContextChange"}.intersection(results),
            "A later phase was incorrectly attached to this attempt")
    require(results["harness"]["commit"] == SOURCE and results["harness"]["dirty"] is False and results["harness"]["status"] == "", "Clean source pin changed")
    require(sha((run / "results.json").read_bytes()) == receipt["originalResultsSHA256"], "Original results bytes changed")
    elapsed = (datetime.fromisoformat(results["finishedUTC"]) - datetime.fromisoformat(results["startedUTC"])).total_seconds()
    require(elapsed == 0.888792 and elapsed == receipt["resultTimestampIntervalSeconds"], "Result timestamp interval changed")
    require(receipt["coordinatorReportedCLI"] == {"exitCode": 1,
            "provenance": "Coordinator observed exec_command return; not encoded in the retained console or results JSON"}, "Reported CLI observation changed")
    require(results["preflight"] == preflight, "Preflight snapshots disagree")
    require(len(commands) == 9 and [c["arguments"][0] for c in commands] == ["codesign"] * 6 + ["xcodegen", "xcrun", "xcrun"], "Unexpected command count/order")
    require([c["arguments"][1:5] for c in commands[:6]] == [
        ["--verify", "--deep", "--strict", "--verbose=2"], ["--display", "--entitlements", ":-", preflight["artifactPaths"]["fixture"]],
        ["--verify", "--deep", "--strict", "--verbose=2"], ["--display", "--entitlements", ":-", preflight["artifactPaths"]["provider"]],
        ["--verify", "--deep", "--strict", "--verbose=2"], ["--display", "--entitlements", ":-", preflight["artifactPaths"]["main"]]], "Unexpected signature command")
    require(commands[6]["arguments"][:3] == ["xcodegen", "generate", "--spec"] and
            commands[7]["arguments"] == ["xcrun", "simctl", "list", "devices", "available", "-j"] and
            commands[8]["arguments"] == ["xcrun", "simctl", "listapps", results["device"]["udid"]], "Only retained preparation/inventory commands are admissible")
    for command in commands:
        require(command["exitCode"] == 0 and command["interruptionDeferred"] is False, "A retained subprocess failed")
        for stream in ("stdout", "stderr"):
            item = command[stream]
            relative(item["file"])
            raw = (run / item["file"]).read_bytes()
            require(len(raw) == item["bytes"] and sha(raw) == item["sha256"], "Command output fingerprint changed")
    require("applications = json.loads(commands.run" in (run / "execution-error.txt").read_text(), "Traceback no longer shows the inventory parser boundary")
    raw = (run / "008-installed-applications.stdout").read_bytes()
    require(len(raw) == 58955 and sha(raw) == RAW_SHA, "The original inventory bytes changed")
    try:
        json.loads(raw)
    except json.JSONDecodeError as error:
        require(str(error) == results["error"]["message"], "JSON failure does not reproduce offline")
    else:
        raise ValueError("The raw inventory unexpectedly parses as JSON")
    inventory = parse(raw.decode())
    converted = read("offline-listapps-conversion.stdout.json")
    require(inventory == converted and MAIN not in inventory and FIXTURE in inventory, "Retained OpenStep conversion or Main absence changed")
    summary, post = read("offline-listapps-summary.json"), read("filesystem-postflight.json")
    require(receipt["postflightIdentities"] == post["artifacts"], "Receipt and captured artifact identities disagree")
    require(summary["rawSHA256"] == RAW_SHA and summary["format"] == "OpenStep property list" and
            summary["ids"] == {MAIN: None, FIXTURE: inventory[FIXTURE]}, "Offline inventory summary changed")
    require(post["cohortTransition"] == "NOT_OCCURRED; Main was not installed or launched" and
            post["installedMain"] == "ABSENT in captured listapps; no install command occurred", "Main phase must remain unstarted")
    identities = preflight["identities"]
    for label, actual, expected_id, tree in [("Main", "sourceMain", MAIN, MAIN_TREE),
            ("App", "installedFixture", FIXTURE, APP_TREE), ("Provider", "installedProvider", PROVIDER, PROVIDER_TREE)]:
        before, after = identities[label], post["artifacts"][actual]
        require(before["bundle_id"] == after["bundle_id"] == expected_id and
                before["bundle_tree_sha256"] == after["bundle_tree_sha256"] == tree and
                before["executable_sha256"] == after["executable_sha256"], "Captured artifact identity changed: " + label)
    require(post["artifacts"]["installedFixture"]["path"] == inventory[FIXTURE]["Path"] and
            post["artifacts"]["installedProvider"]["path"] == inventory[FIXTURE]["Path"] + "/PlugIns/RevocationProvider.appex" and
            inventory[FIXTURE]["GroupContainers"][GROUP].endswith("/5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4/"), "Installed path/group associations changed")
    device = results["device"]
    devices = read("007-devices.stdout")["devices"]
    observed = [d for cohort in devices.values() for d in cohort if d["udid"] == device["udid"]]
    require(len(observed) == 1 and all(observed[0][k] == device[k] for k in observed[0]) and
            device["runtime"] == "com.apple.CoreSimulator.SimRuntime.iOS-27-0" and
            device["deviceTypeIdentifier"] == "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-M5-12GB", "Captured cohort changed")
    lease = read("RootLease.json")
    require((run / "RootLease.json").read_bytes() == (folder / "evidence/ios-provider-auth-ad28-001-root-lease.json").read_bytes() and
            lease == results["configuration"]["rootLease"] == results["rootLease"]["review"] and
            sha((run / "RootLease.json").read_bytes()) == results["rootLease"]["sha256"] and
            lease["nativeAuthorized"] is True and lease["singleAttempt"] is True and lease["harnessCommit"] == SOURCE and
            lease["simulatorUDID"] == device["udid"] and lease["runID"] == results["runID"] and
            results["nativeOperationAuthorizedByCurrentArtifactReview"] is False, "Captured lease or separate historical review flag changed")
    for name, digest in results["harness"]["sources"].items():
        relative(name)
        require(sha((folder / "evidence/pinned-source" / name).read_bytes()) == digest == lease["harnessSources"][name], "Pinned source bytes changed: " + name)
    for category in ("sources", "resources"):
        for name, digest in results["staged"][category].items():
            require(sha((run / "harness" / name).read_bytes()) == digest, "Staged harness bytes changed: " + name)
    for label, prefix in [("App", FIXTURE), ("Provider", PROVIDER)]:
        sections = preflight["staticEvidence"]["fixtureSimulatorSections"][label]
        require(sections["sectionOwners"] == ["main"] and sections["codeSignatureEntitlements"] == {}, "Captured entitlement owner or CodeSignature changed")
        main = sections["images"][0]
        for section, item in main["sections"].items():
            content = (run / "static-preflight" / item["path"]).read_bytes()
            require(len(content) == item["bytes"] and sha(content) == item["sha256"] and
                    content == (run / "static-preflight" / f"{label}-generated-{section}.bin").read_bytes(), "Simulator section/generated bytes differ")
        actual = plistlib.loads((run / "static-preflight" / f"{label}-main-__entitlements.bin").read_bytes())
        require(actual == sections["simulatedEntitlements"] == {"application-identifier": "P4VGX44GD8." + prefix,
                "com.apple.security.application-groups": [GROUP]}, "Captured simulator entitlement dictionary changed")
    require(not any(p.suffix in (".xcresult", ".png", ".ips", ".mp4") for p in run.rglob("*")), "Unexpected later-phase artifacts")
    require(receipt["unqualified"] == ["FixtureUI", "FilesProviderLocation", "Fetch", "Upload", "Eviction", "AuthenticationDenial",
            "SaveError", "LocalXMLPreservation", "BackendState", "Revocation", "Issue26", "Cloud", "PhysicalDevice"], "Scope exclusions changed")
    return {"status": "PASS", "scope": receipt["scope"], "originalOutcome": "FAIL", "testExecution": "NATIVE_NOT_RUN",
            "evidenceFiles": len(names), "retainedCommands": len(commands), "rawInventorySHA256": RAW_SHA}


if __name__ == "__main__":
    print(json.dumps(verify(Path(__file__).resolve().parent), indent=2))
