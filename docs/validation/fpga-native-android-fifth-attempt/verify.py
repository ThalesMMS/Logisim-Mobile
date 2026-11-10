#!/usr/bin/env python3
"""Verify the retained fifth FPGA attempt without a device or external process."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
SOURCE = "aea3a63f5c2d90acec8786456c328cb0d2821f9f"
APP_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
APKS = [
    "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
]
NATIVE = {"com.google.android.documentsui", "com.android.documentsui"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(relative):
    return json.loads((ROOT / relative).read_text())


def picker_counts(path):
    nodes = [node for node in ET.parse(path).getroot().iter("node") if node.get("package") in NATIVE]
    return {
        "roots": sum(node.get("resource-id", "").endswith(":id/roots_list") for node in nodes),
        "directories": sum(node.get("resource-id", "").endswith(":id/dir_list") for node in nodes),
        "downloadsBreadcrumbs": sum(node.get("resource-id", "").endswith(":id/breadcrumb_text")
                                   and node.get("text") == "Downloads" for node in nodes),
    }


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
    assert chain["sourceFilesCopied"] == len(chain["files"]) == 136
    assert chain["bytesCopied"] == sum(entry["bytes"] for entry in chain["files"]) == 2369131
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
    assert summary["caseResults"] == {"synthesis": "FAIL_BEFORE_BOARD_SELECTION",
                                      "tool-error": "NOT_RUN", "cancel": "NOT_RUN"}
    invocation = load("raw/invocation/fpga005-native-invocation-001.json")
    assert invocation["singleInvocation"] is True and invocation["invocationCount"] == 1
    assert invocation["exitCode"] == 1 and invocation["source"] == SOURCE
    assert invocation["elapsedSeconds"] == summary["elapsedSeconds"] == 130.269223625015
    assert invocation["argv"][invocation["argv"].index("--case") + 1] == "all"
    assert invocation["serial"] == "emulator-5558"
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

    board = load("raw/native/board-FPGA_E2E_HX1K_48957ff6.json")
    xml = ROOT / "raw/native/board-FPGA_E2E_HX1K_48957ff6.xml"
    assert xml.stat().st_size == board["stagedXmlBytes"] == 536
    assert digest(xml) == board["stagedXmlSha256"] == board["uploadedSha256"]
    assert ET.parse(xml).getroot().tag == board["boardIdentity"] == "FPGA_E2E_HX1K_48957ff6"
    assert board["status"] == "FAIL" and board["published"] is True
    assert "sourceAfterSha256" not in board
    for pattern in (r"\bmime_type=text/xml\b", r"\bis_download=1\b", r"\bis_pending=0\b"):
        assert re.search(pattern, board["mediaStore"])
    failure = (ROOT / "raw/native/fpga-synthesis-error.txt").read_text()
    assert "self.s.choose_downloads()" in failure and "choose_native_file" not in failure
    assert "1.1225581250037067s" in run["error"]["message"]

    trace_name = "downloads-navigation-a4648a10f9324b2c838e15f2f24f554a"
    trace = load("raw/native/" + trace_name + ".json")
    assert trace["status"] == "FAILED" and trace["transitionLimitSeconds"] == 30
    assert trace["totalLimitSeconds"] == 90 and trace["maximumActions"] == 3
    taps = [event for event in trace["events"] if event["kind"] == "tap"]
    assert len(taps) == 2 and [event["action"] for event in taps] == [1, 2]
    assert taps[0]["description"] == "Show roots" and taps[1]["text"] == "Downloads"
    for event in taps:
        assert event["windowContext"]["wmTokenObservedAtTap"] is False
        assert event["windowContext"]["pidObservedAtTap"] is False
    screens = [event for event in trace["events"] if event["kind"] == "screen"]
    assert len(screens) == 15 and screens[-1]["hierarchy"] is None
    assert picker_counts(ROOT / "raw/native" / screens[0]["hierarchy"]) == {
        "roots": 0, "directories": 1, "downloadsBreadcrumbs": 1}
    for event in screens[2:14]:
        counts = picker_counts(ROOT / "raw/native" / event["hierarchy"])
        assert counts["roots"] == 1 and counts["directories"] == 0

    preflight = load("raw/preflight/preflight.json")
    cleanup = load("raw/native/lease-return.json")
    assert preflight["status"] == "PASS" and preflight["nativeCaseInvocations"] == 0
    assert cleanup["cleanupVerified"] is True and cleanup["retryPerformed"] is False
    assert cleanup["caseInvocationCount"] == 1 and cleanup["ownHostPort"] == run["reversePort"] == 55307
    assert cleanup["ownHostListenerPresent"] is False and cleanup["ownReverseMappingPresent"] is False
    assert cleanup["configurationAfter"] == preflight["configurationBefore"]
    assert [entry["sha256"] for entry in cleanup["installedAfter"]] == APKS
    assert cleanup["sourceInputsUnchanged"] is True and cleanup["sourceAfter"] == run["sources"]
    assert cleanup["hostRequests"] == cleanup["hostJobs"] == 0
    assert cleanup["normalAppTouched"] is False and cleanup["otherDeviceTouched"] is False
    assert (ROOT / "raw/preflight/last-anr-before.txt").read_bytes() == (
        ROOT / "raw/native/last-anr-after.txt").read_bytes()
    print(f"Verified {len(listed)} archive files, 136 original copies, and the unqualified fifth attempt.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-originals", action="store_true")
    verify(parser.parse_args().check_originals)
