#!/usr/bin/env python3
"""Verify the portable auth004 archive without reading a device or original output."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re

RUN_ID = "a26a02940004"
HARNESS = "3f7ba0b4f0a9c48aff144bd578a11bab69f7b908"
MAIN_ID = "thalesmms.logisim.mobile"
FIXTURE_ID = "thalesmms.logisim.validation.revocationfixture"
SELECTOR = "ProviderAuthenticationEndToEndTests/ProviderAuthenticationEndToEndTests/testAuthenticationDenialAfterGrantPreservesProject"
INITIAL = "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88"
WRAPPER = "61fb2ff0e8e1969b80c2880424683834a5dcd3cdcf79450a40dba15dc4f6471b"
TREES = {"Main": "813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef",
         "App": "e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5",
         "Provider": "e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f"}
EXECUTABLES = {"Main": "72f025668535a86c479eda81c2bbcba9fb65e0f1cef29598d869dd78c4977d48",
               "App": "39480047426a291e21ca98998e8a7cb4718a021d4ea1f921c9f339ca748a9b49",
               "Provider": "8174906909373b28923998e19c18e8228f1e049fb875c585b6a3e1e1b3f6e393"}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contained(root, relative):
    path = Path(relative)
    require(not path.is_absolute() and ".." not in path.parts, "Unsafe archive path")
    result = root / path
    require(result.resolve().is_relative_to(root.resolve()) and not result.is_symlink(), "Archive path escaped or is a symlink")
    return result


def verify(root):
    root = Path(root).resolve()
    sums = {}
    for line in (root / "SHA256SUMS").read_text().splitlines():
        expected, relative = line.split("  ", 1)
        require(re.fullmatch("[0-9a-f]{64}", expected), "Invalid checksum")
        require(relative not in sums, "Duplicate checksum path")
        require(digest(contained(root, relative).read_bytes()) == expected, "Checksum mismatch: " + relative)
        sums[relative] = expected
    actual_files = {f.relative_to(root).as_posix() for f in root.rglob("*") if f.is_file() and f.name != "SHA256SUMS"}
    require(set(sums) == actual_files, "Checksum census is incomplete")
    inventory = json.loads((root / "inventory.json").read_bytes())
    receipt = json.loads((root / "receipt.json").read_bytes())
    entries = inventory["archived"]
    require(len(entries) == 97 and len({e["key"] for e in entries}) == 97, "Archived evidence census changed")
    raw = {}
    for item in entries:
        stored = contained(root, item["path"]).read_bytes()
        require(len(stored) == item["storedBytes"] and digest(stored) == item["storedSHA256"], "Stored evidence mismatch")
        require(0 <= item["bytes"] <= 8_000_000, "Unexpected evidence size")
        if item["compression"] == "gzip":
            with gzip.GzipFile(fileobj=io.BytesIO(stored)) as stream:
                data = stream.read(item["bytes"] + 1)
        else:
            require(item["compression"] is None, "Unknown evidence compression")
            data = stored
        require(len(data) == item["bytes"] and digest(data) == item["sha256"], "Original evidence mismatch: " + item["key"])
        raw[item["key"]] = data

    def document(key):
        return json.loads(raw[key])

    results = document("run/results.json")
    commands = document("run/commands.json")
    run = document("run/harness/Run.json")
    lease = document("run/RootLease.json")
    require(results["runID"] == run["runID"] == lease["runID"] == receipt["runID"] == RUN_ID, "Run identity changed")
    require(results["harness"]["commit"] == HARNESS and results["harness"]["dirty"] is False, "Harness provenance changed")
    gates = {"status": "FAIL", "native_execution_gate": "NATIVE_INCOMPLETE", "test_execution_gate": "NATIVE_INCOMPLETE", "qualification_gate": "NOT_QUALIFIED"}
    require(all(results[k] == value for k, value in gates.items()) and receipt["rawGates"] == gates, "Raw failure gates were reclassified")
    require(receipt["status"] == "FAIL" and receipt["archiveKind"] == "DOCS_ONLY_FAILED_SELECTED_CASE", "Archive must remain a failed selected case")
    require(not any(receipt["claims"].values()) and receipt["failure"]["productDefectEstablished"] is False, "Unsupported success or defect claim")
    require(results["selectedTests"] == [SELECTOR] and results["execution"]["exitCode"] == 65, "XCTest invocation changed")
    summary = document("run/015-xcresult-summary.stdout")
    require(summary == results["testSummary"], "Summary differs from runner record")
    require([summary[k] for k in ["totalTestCount", "failedTests", "passedTests", "skippedTests"]] == [1, 1, 0, 0], "Unexpected test counts")
    require("The fixture did not expose its expected successful callback status" in summary["testFailures"][0]["failureText"], "Different assertion failure")
    test_log = raw["run/014-auth-test.stdout"].decode()
    require("failed (61.284 seconds)" in test_log, "Missing method duration")
    require("** TEST BUILD SUCCEEDED **" in raw["run/013-harness-build.stdout"].decode(), "Build did not succeed")
    terminal = document("attestations/runner-terminal.json")
    require(terminal["runnerExitCode"] == 1 and terminal["kind"] == "RUNTIME_COORDINATOR_ATTESTATION" and terminal["consoleIsExitProof"] is False, "Runner exit must retain its attestation qualifier")
    require(raw["siblings/ios-provider-auth-ad28-004.console.log"].decode().strip().endswith("/results.json"), "Unexpected console record")
    require(raw["siblings/ios-provider-auth-ad28-004-root-lease.json"] == raw["run/RootLease.json"], "Lease copies differ")
    require(lease == run["rootLease"] == results["rootLease"]["review"], "Lease binding differs")
    require(lease["harnessSources"] == results["harness"]["sources"] and lease["selector"] == SELECTOR, "Lease source or selector binding changed")
    for path, expected in results["harness"]["sources"].items():
        key = "source/" + path if path.endswith(".py") else "run/harness/" + Path(path).name
        require(digest(raw[key]) == expected, "Executed source hash differs: " + path)
    require(document("run/harness/Run.json") == results["configuration"], "Run configuration differs")
    require(len(document("run/harness/Catalog.json")) == 2497, "Unexpected catalog")
    for name, expected in results["bundledResources"].items():
        require(digest(raw["run/harness/" + name]) == expected == results["staged"]["resources"][name], "Staged or bundled resource hash differs")

    for command in commands:
        for direction in ["stdout", "stderr", "stdin"]:
            if direction in command:
                evidence = command[direction]
                data = raw["run/" + evidence["file"]]
                require(len(data) == evidence["bytes"] and digest(data) == evidence["sha256"], "Command stream evidence differs")
    require(len(commands) == 19, "Command count changed")
    build, test = commands[13], commands[14]
    require(build["exitCode"] == 0 and build["timeoutSeconds"] is None and not build["interruptionDeferred"], "Serialized build completion differs")
    require(build["arguments"][1:3] == ["/tmp/logisim-gradle-serial.py", "xcodebuild"] and build["arguments"][-1] == "build-for-testing", "Build was not serialized")
    require(test["arguments"][0] == "xcodebuild" and "test-without-building" in test["arguments"] and "-only-testing:" + SELECTOR in test["arguments"], "Test scope differs")
    require("/tmp/logisim-gradle-serial.py" not in test["arguments"] and test["timeoutSeconds"] == 1380, "Test must run outside the build lock")
    require(digest(raw["run/BuildSerializationWrapper.py"]) == WRAPPER, "Wrapper differs")
    require(results["buildSerialization"]["wrapperProcessCompleted"] is True and results["buildSerialization"]["exitCode"] == 0, "Wrapper completion differs")
    installs = [c for c in commands if c["arguments"][:3] == ["xcrun", "simctl", "install"]]
    require(not installs, "Unexpected installation")
    applications = document("run/009-installed-applications-json.stdout")
    require(len(applications) == 46 and FIXTURE_ID in applications and MAIN_ID in applications, "Pre-install inventory changed")
    require(raw["run/009-installed-applications-json.stdin"] == raw["run/008-installed-applications.stdout"], "Inventory conversion changed raw input")
    for role in TREES:
        before, after = results["installedBeforeTest"][role], results["installedAfterTest"][role]
        require(before == after and before["bundle_tree_sha256"] == TREES[role] and before["executable_sha256"] == EXECUTABLES[role], "Installed bundle fingerprint differs: " + role)
    context = results["installationContextChange"]
    require(context["main"] == "REUSED_EXACT_INSTALLED_MAIN" and context["fixture"] == "REUSED_EXACT_REGISTERED_FIXTURE" and context["fixtureInstalledOrRefreshed"] is False, "Integration cohort differs")

    baseline = document(receipt["publicBaseline"]["snapshot"])
    public = raw[receipt["publicBaseline"]["publicText"]].decode()
    require(json.loads(public.split("SNAPSHOT\n", 1)[1]) == baseline, "Snapshot differs from public UITextView")
    expected_state = {"authenticationAllowed": True, "backendByteCount": 552, "backendSHA256": INITIAL, "generation": 1, "schemaVersion": 1}
    require(baseline["state"] == expected_state, "Public baseline differs")
    require([e["sequence"] for e in baseline["events"]] == list(range(1, 81)) and baseline["events"][-1]["kind"] == "provider.invalidated", "Baseline history differs")
    scenario = document(receipt["publicBaseline"]["scenario"])
    require(scenario["status"] == "INCOMPLETE_OR_FAILED" and scenario["phase"] == "initial-before-preparatory-eviction" and len(scenario["observations"]) == 1, "Scenario must remain incomplete at its only baseline")
    require(scenario["observations"][0] == {"authenticationAllowed": True, "backendBytes": 552, "backendSHA256": INITIAL, "generation": 1, "lastSequence": 80, "phase": "initial-before-preparatory-eviction"}, "Unexpected scenario observation")
    failure_public = raw[receipt["publicFailure"]["publicText"]].decode()
    failure = json.loads(failure_public.split("SNAPSHOT\n", 1)[1])
    require(failure["state"] == expected_state, "Failure snapshot state differs")
    require(failure["events"][:80] == baseline["events"] and [e["sequence"] for e in failure["events"]] == list(range(1, 86)), "Preparatory event window differs")
    requested, initialized, started, failed, eviction_failed = failure["events"][80:]
    prep = receipt["preparation"]
    require([e["kind"] for e in [requested, initialized, started, failed, eviction_failed]] == ["eviction.requested", "provider.initialized", "provider.modify.started", "provider.modify.failed", "eviction.failed"], "Preparatory callback sequence differs")
    require(requested["details"] == {"backendSHA256": INITIAL, "denyAfterCompletion": "false"} and prep["denyAfterCompletion"] is False, "Preparatory eviction must not request denial")
    require(requested["process"] == eviction_failed["process"] == prep["fixtureProcess"] == "RevocationFixture:32596" and initialized["process"] == started["process"] == failed["process"] == prep["providerProcess"] == "RevocationProvider:8735", "Preparatory process linkage differs")
    require(all(e["itemIdentifier"] == "revocation-circuit-v1" for e in [requested, started, failed, eviction_failed]), "Preparatory item linkage differs")
    require(started["details"] == failed["details"] == {"domain": "logisim-revocation-fixture-v1", "requestID": "961C9B1C-1E0E-433B-B27B-275A7ED07291"} and prep["requestID"] == started["details"]["requestID"], "Preparatory request linkage differs")
    require(all(e["errorDomain"] == "NSCocoaErrorDomain" and e["errorCode"] == 3328 and e["errorDiagnostic"]["termination"] == "complete" for e in [failed, eviction_failed]), "Preparatory error identity differs")
    old_started, old_failed = baseline["events"][77:79]
    require(old_started["sequence"] == 78 and old_failed["sequence"] == 79 and old_started["details"]["requestID"] == old_failed["details"]["requestID"] == "951FCE5C-D7DC-49E5-905F-44DC46562029" and old_started["details"]["requestID"] != started["details"]["requestID"], "Historical and current requests were conflated")
    require(test_log.count('Tap "fixture.evict" Button') == 1 and test_log.index("Terminate thalesmms.logisim.mobile:") < test_log.index('Tap "fixture.evict" Button'), "Preparatory UI action differs")
    tree = raw[receipt["failure"]["tree"]].decode()
    require("Operation failed: NSCocoaErrorDomain / 3328" in tree and "The fixture does not commit metadata-only changes." in tree, "Missing public fixture guard status")
    for key, expected in receipt["fixtureSourceHashes"].items():
        require(digest(raw[key]) == expected, "Pinned fixture source differs")
    provider_source = raw["fixture-source/scripts/ios_fileprovider_revocation/Provider/RevocationFileProvider.swift"].decode()
    require("guard changedFields.contains(.contents), let newContents else" in provider_source and "The fixture does not commit metadata-only changes." in provider_source, "Fixture guard source differs")
    require(prep["requestedChangedFields"] == prep["newContentsURLPresence"] == "NOT_RECORDED", "Unobserved modify arguments were inferred")
    require(digest(raw[receipt["failure"]["screenshot"]]) == "43fc21a3d09652af649eaf5390882be142347a3b2081570fafa4a713a2589d78", "Critical screenshot differs")
    require(receipt["publicFailure"]["scope"] == "CAPTURED_AT_CALLBACK_FAILURE_ONLY" and receipt["postflight"]["providerBackendState"] == receipt["postflight"]["providerAuthenticationState"] == "FAILURE_SNAPSHOT_ONLY" and receipt["postflight"]["afterFailureSnapshot"] == "NOT_OBSERVED", "Failure snapshot was promoted to a later post-state")

    attachments = [a for t in document("run/attachments/manifest.json") for a in t["attachments"]]
    require(len(attachments) == 26, "Attachment census differs")
    archived = {k.removeprefix("run/attachments/") for k in raw if k.startswith("run/attachments/") and not k.endswith("manifest.json")}
    local = {a["file"] for a in inventory["localOnlyAttachments"]}
    require(len(archived) == 16 and len(local) == 10 and archived.isdisjoint(local), "Attachment disposition census differs")
    require(archived | local == {a["exportedFileName"] for a in attachments}, "Attachments are missing from both dispositions")
    require(not any(k.endswith(".circ") for k in raw), "Unexpected XML proof")
    for directory in inventory["localOnlyDirectories"]:
        details = document("local-only/" + Path(directory["originalPath"]).name + ".inventory.json")
        tree_hash = hashlib.sha256()
        for f in details["files"]:
            require(not Path(f["path"]).is_absolute() and ".." not in Path(f["path"]).parts and re.fullmatch("[0-9a-f]{64}", f["sha256"]), "Invalid local-only inventory")
            tree_hash.update(f["path"].encode() + b"\0" + f["sha256"].encode() + b"\n")
        require(tree_hash.hexdigest() == directory["contentTreeSHA256"] == details["contentTreeSHA256"] and len(details["files"]) == directory["fileCount"] and sum(f["bytes"] for f in details["files"]) == directory["bytes"], "Local-only tree inventory differs")
    return {"status": "ARCHIVE_REPLAY_PASS", "nativeRun": "FAILED_SELECTED_CASE", "newNativeExecution": "NOT_RUN", "archivedEvidenceFiles": 97, "archivedAttachments": 16, "localOnlyAttachments": 10}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", nargs="?", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    print(json.dumps(verify(args.package), indent=2))
