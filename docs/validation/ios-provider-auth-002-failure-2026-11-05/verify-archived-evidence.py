#!/usr/bin/env python3
"""Verify the portable auth002 archive without reading a device or original output."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re

RUN_ID = "a26a02940002"
HARNESS = "92f739a68de9bab12cbaaccbf6cb23da2c4aecb3"
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
    require(len(entries) == 122 and len({e["key"] for e in entries}) == 122, "Archived evidence census changed")
    raw = {}
    for item in entries:
        stored = contained(root, item["path"]).read_bytes()
        require(len(stored) == item["storedBytes"] and digest(stored) == item["storedSHA256"], "Stored evidence mismatch")
        require(0 <= item["bytes"] <= 2_000_000, "Unexpected evidence size")
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
    summary = document("run/016-xcresult-summary.stdout")
    require(summary == results["testSummary"], "Summary differs from runner record")
    require([summary[k] for k in ["totalTestCount", "failedTests", "passedTests", "skippedTests"]] == [1, 1, 0, 0], "Unexpected test counts")
    require(summary["testFailures"][0]["failureText"] == "failed - The current picker did not expose provider directory heading.", "Different assertion failure")
    test_log = raw["run/015-auth-test.stdout"].decode()
    require("failed (80.406 seconds)" in test_log, "Missing method duration")
    require("** TEST BUILD SUCCEEDED **" in raw["run/014-harness-build.stdout"].decode(), "Build did not succeed")
    terminal = document("attestations/runner-terminal.json")
    require(terminal["runnerExitCode"] == 1 and terminal["kind"] == "RUNTIME_COORDINATOR_ATTESTATION" and terminal["consoleIsExitProof"] is False, "Runner exit must retain its attestation qualifier")
    require(raw["siblings/ios-provider-auth-ad28-002.console.log"].decode().strip().endswith("/results.json"), "Unexpected console record")
    require(raw["siblings/ios-provider-auth-ad28-002-root-lease.json"] == raw["run/RootLease.json"], "Lease copies differ")
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
    require(len(commands) == 20, "Command count changed")
    build, test = commands[14], commands[15]
    require(build["exitCode"] == 0 and build["timeoutSeconds"] is None and not build["interruptionDeferred"], "Serialized build completion differs")
    require(build["arguments"][1:3] == ["/tmp/logisim-gradle-serial.py", "xcodebuild"] and build["arguments"][-1] == "build-for-testing", "Build was not serialized")
    require(test["arguments"][0] == "xcodebuild" and "test-without-building" in test["arguments"] and "-only-testing:" + SELECTOR in test["arguments"], "Test scope differs")
    require("/tmp/logisim-gradle-serial.py" not in test["arguments"] and test["timeoutSeconds"] == 1380, "Test must run outside the build lock")
    require(digest(raw["run/BuildSerializationWrapper.py"]) == WRAPPER, "Wrapper differs")
    require(results["buildSerialization"]["wrapperProcessCompleted"] is True and results["buildSerialization"]["exitCode"] == 0, "Wrapper completion differs")
    installs = [c for c in commands if c["arguments"][:3] == ["xcrun", "simctl", "install"]]
    require(len(installs) == 1 and installs[0]["exitCode"] == 0 and installs[0]["arguments"][-1].endswith("/LogisimMobile.app"), "Unexpected installation")
    applications = document("run/009-installed-applications-json.stdout")
    require(len(applications) == 44 and FIXTURE_ID in applications and MAIN_ID not in applications, "Pre-install inventory changed")
    require(raw["run/009-installed-applications-json.stdin"] == raw["run/008-installed-applications.stdout"], "Inventory conversion changed raw input")
    for role in TREES:
        before, after = results["installedBeforeTest"][role], results["installedAfterTest"][role]
        require(before == after and before["bundle_tree_sha256"] == TREES[role] and before["executable_sha256"] == EXECUTABLES[role], "Installed bundle fingerprint differs: " + role)
    context = results["installationContextChange"]
    require(context["main"] == "INSTALLED_APPROVED_MAIN" and context["fixture"] == "REUSED_EXACT_REGISTERED_FIXTURE" and context["fixtureInstalledOrRefreshed"] is False, "Integration cohort differs")

    baseline = document(receipt["publicBaseline"]["snapshot"])
    public = raw[receipt["publicBaseline"]["publicText"]].decode()
    require(json.loads(public.split("SNAPSHOT\n", 1)[1]) == baseline, "Snapshot differs from public UITextView")
    require(baseline["state"] == {"authenticationAllowed": True, "backendByteCount": 552, "backendSHA256": INITIAL, "generation": 1, "schemaVersion": 1}, "Public baseline differs")
    require([e["sequence"] for e in baseline["events"]] == list(range(1, 10)) and baseline["events"][-1]["kind"] == "domain.registration.completed", "Baseline history differs")
    scenario = document(receipt["publicBaseline"]["scenario"])
    require(scenario["status"] == "INCOMPLETE_OR_FAILED" and scenario["phase"] == "baseline-before-files" and len(scenario["observations"]) == 1, "Scenario must remain incomplete at its only baseline")
    require(scenario["observations"][0] == {"authenticationAllowed": True, "backendBytes": 552, "backendSHA256": INITIAL, "generation": 1, "lastSequence": 9, "phase": "baseline-before-files"}, "Unexpected post-baseline proof")
    tree = raw[receipt["failure"]["tree"]].decode()
    require("Alert," in tree and "label: 'Turn On “Logisim Revocation Fixture”?'" in tree and "label: 'Turn On'" in tree and "label: 'Cancel'" in tree, "Missing native activation alert")
    require(digest(raw[receipt["failure"]["screenshot"]]) == "e58952b2a31efeaa2ce01dab8d35bf9f2b18ccb188ac553a8c458517544f81d9", "Critical screenshot differs")
    require(receipt["publicBaseline"]["postState"] is False and receipt["postflight"]["providerBackendState"] == receipt["postflight"]["providerAuthenticationState"] == "NOT_OBSERVED_AFTER_BASELINE", "Baseline was promoted to post-state")

    attachments = [a for t in document("run/attachments/manifest.json") for a in t["attachments"]]
    require(len(attachments) == 78, "Attachment census differs")
    archived = {k.removeprefix("run/attachments/") for k in raw if k.startswith("run/attachments/") and not k.endswith("manifest.json")}
    local = {a["file"] for a in inventory["localOnlyAttachments"]}
    require(len(archived) == 43 and len(local) == 35 and archived.isdisjoint(local), "Attachment disposition census differs")
    require(archived | local == {a["exportedFileName"] for a in attachments}, "Attachments are missing from both dispositions")
    require(not any(k.endswith(".circ") for k in raw), "Unexpected XML proof")
    for directory in inventory["localOnlyDirectories"]:
        details = document("local-only/" + Path(directory["originalPath"]).name + ".inventory.json")
        tree_hash = hashlib.sha256()
        for f in details["files"]:
            require(not Path(f["path"]).is_absolute() and ".." not in Path(f["path"]).parts and re.fullmatch("[0-9a-f]{64}", f["sha256"]), "Invalid local-only inventory")
            tree_hash.update(f["path"].encode() + b"\0" + f["sha256"].encode() + b"\n")
        require(tree_hash.hexdigest() == directory["contentTreeSHA256"] == details["contentTreeSHA256"] and len(details["files"]) == directory["fileCount"] and sum(f["bytes"] for f in details["files"]) == directory["bytes"], "Local-only tree inventory differs")
    return {"status": "ARCHIVE_REPLAY_PASS", "nativeRun": "FAILED_SELECTED_CASE", "newNativeExecution": "NOT_RUN", "archivedEvidenceFiles": 122, "archivedAttachments": 43, "localOnlyAttachments": 35}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", nargs="?", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    print(json.dumps(verify(args.package), indent=2))
