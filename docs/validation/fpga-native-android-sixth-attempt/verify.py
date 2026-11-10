#!/usr/bin/env python3
"""Verify the sixth FPGA attempt without a device or external process."""

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
SOURCE = "515983508151c2adc52e443655c584ad7057855e"
APP_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
PACKAGE = "thalesmms.logisim.mobile.e2e"
APKS = [
    "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(name):
    return json.loads((ROOT / name).read_text())


def verify(check_originals=False):
    listed = {}
    for line in (ROOT / "SHA256SUMS").read_text().splitlines():
        sha, name = line.split("  ", 1)
        relative = Path(name)
        assert not relative.is_absolute() and ".." not in relative.parts and name not in listed
        path = ROOT / relative
        assert path.is_file() and not path.is_symlink() and digest(path) == sha, name
        listed[name] = sha
    assert set(listed) == {str(path.relative_to(ROOT)) for path in ROOT.rglob("*")
                           if path.is_file() and path.name != "SHA256SUMS"}
    chain = load("copy-chain.json")
    assert chain["sourceFilesCopied"] == len(chain["files"]) == 142
    assert chain["bytesCopied"] == sum(entry["bytes"] for entry in chain["files"]) == 2264846
    assert chain["originalsModified"] is False
    for entry in chain["files"]:
        path = ROOT / entry["archive"]
        assert path.stat().st_size == entry["bytes"]
        assert digest(path) == entry["sha256"] == entry["sourceSha256AfterCopy"]
        if check_originals:
            original = Path(entry["source"])
            assert original.stat().st_size == entry["bytes"] and digest(original) == entry["sha256"]

    summary = load("attempt-summary.json")
    assert summary["status"] == "FAIL_PRESERVED" and summary["qualifiedCases"] == 0
    assert summary["caseResults"] == {"synthesis": "FAIL_BEFORE_PIN_MAPPING",
                                      "tool-error": "NOT_RUN", "cancel": "NOT_RUN"}
    invocation = load("raw/invocation/fpga006-native-invocation-001.json")
    assert invocation["singleInvocation"] is True and invocation["invocationCount"] == 1
    assert invocation["exitCode"] == 1 and invocation["source"] == SOURCE
    assert invocation["elapsedSeconds"] == summary["elapsedSeconds"] == 112.84384241700172
    assert invocation["serial"] == "emulator-5558"
    assert invocation["argv"][invocation["argv"].index("--case") + 1] == "all"
    run = load("raw/native/fpga-run.json")
    assert (run["status"], run["nativeExecutionGate"], run["qualificationGate"]) == (
        "FAIL", "NATIVE_FAIL", "NOT_QUALIFIED")
    assert run["harnessSource"] == SOURCE and run["artifactSource"] == APP_SOURCE
    assert run["selectedCases"] == ["synthesis", "tool-error", "cancel"] and not run.get("cleanupErrors")
    assert [entry["sha256"] for entry in run["installedBefore"]] == APKS
    assert len(run["sources"]) == 11
    for name, sha in run["sources"].items():
        assert digest(ROOT / "raw/native/source" / name) == sha
    audit = load("raw/native/companion-audit.json")
    assert audit == run["companionAudit"]
    assert audit["operations"] == ["fpga-synthesize"] and audit["programmingRegistered"] is False
    assert audit["requests"] == audit["events"] == audit["jobs"] == []

    board = load("raw/native/board-FPGA_E2E_HX1K_29ae52c5.json")
    xml = ROOT / "raw/native/board-FPGA_E2E_HX1K_29ae52c5.xml"
    assert xml.stat().st_size == board["stagedXmlBytes"] == 536
    assert digest(xml) == board["stagedXmlSha256"] == board["uploadedSha256"] == board["sourceAfterSha256"]
    assert ET.parse(xml).getroot().tag == board["boardIdentity"] == "FPGA_E2E_HX1K_29ae52c5"
    assert board["status"] == "PASS" and board["published"] is True
    imported = ET.parse(ROOT / "raw/native/fpga-board-imported-FPGA_E2E_HX1K_29ae52c5-ui.xml").getroot()
    sheets = [node for node in imported.iter("node") if node.get("resource-id") == "editor-tool-sheet"]
    assert len(sheets) == 1
    texts = [node.get("text") for node in sheets[0].iter("node") if node.get("package") == PACKAGE]
    assert texts.count("FPGA_E2E_HX1K_29ae52c5") == 1
    assert texts.count("OPEN_FPGA · ice40 · hx1k · tq144") == 1
    final = ET.parse(ROOT / "raw/native/fpga-failure-ui.xml").getroot()
    assert not any(node.get("resource-id") == "editor-tool-sheet" for node in final.iter("node"))
    assert sum(node.get("text") == "Operation cancelled." for node in final.iter("node")) == 1
    failure = (ROOT / "raw/native/fpga-synthesis-error.txt").read_text()
    assert "self.validate(valid=False)" in failure and "node = self.e.reveal(key)" in failure
    assert run["error"]["message"] == "The active editor-tool-sheet viewport disappeared"

    trace = load("raw/native/downloads-navigation-079d8c0d303240a585a6c6163e750476.json")
    assert trace["status"] == "PASSED" and trace["transitionLimitSeconds"] == 30
    assert trace["totalLimitSeconds"] == 90
    assert not any(event["kind"] == "tap" for event in trace["events"])
    observed = [event for event in trace["events"] if event["kind"] == "already-at-downloads"]
    assert len(observed) == 1 and observed[0]["seconds"] < 30
    assert observed[0]["breadcrumb"]["text"] == "Downloads"
    assert sum(event["kind"] == "screen" for event in trace["events"]) == 1
    assert trace["events"][-1]["kind"] == "finish" and trace["events"][-1]["actions"] == 0
    navigation = ET.parse(ROOT / "raw/native" / observed[0]["sourceHierarchy"]).getroot()
    native = [node for node in navigation.iter("node") if node.get("package") in {
        "com.android.documentsui", "com.google.android.documentsui"}]
    assert sum(node.get("resource-id", "").endswith(":id/roots_list") for node in native) == 0
    assert sum(node.get("resource-id", "").endswith(":id/dir_list") for node in native) == 1
    assert sum(node.get("resource-id", "").endswith(":id/breadcrumb_text") for node in native) == 1

    preflight = load("raw/preflight/preflight.json")
    cleanup = load("raw/native/lease-return.json")
    assert preflight["status"] == "PASS" and preflight["nativeCaseInvocations"] == 0
    assert cleanup["cleanupVerified"] is True and cleanup["retryPerformed"] is False
    assert cleanup["caseInvocationCount"] == 1 and cleanup["ownHostPort"] == run["reversePort"] == 62546
    assert cleanup["ownHostListenerPresent"] is False and cleanup["ownReverseMappingPresent"] is False
    assert cleanup["configurationAfter"] == preflight["configurationBefore"]
    assert [entry["sha256"] for entry in cleanup["installedAfter"]] == APKS
    assert cleanup["sourceInputsUnchanged"] is True and cleanup["sourceAfter"] == run["sources"]
    assert cleanup["hostRequests"] == cleanup["hostJobs"] == 0
    assert cleanup["normalAppTouched"] is False and cleanup["otherDeviceTouched"] is False
    assert (ROOT / "raw/preflight/last-anr-before.txt").read_bytes() == (
        ROOT / "raw/native/last-anr-after.txt").read_bytes()
    print(f"Verified {len(listed)} archive files, 142 original copies, and the unqualified sixth attempt.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-originals", action="store_true")
    verify(parser.parse_args().check_originals)
