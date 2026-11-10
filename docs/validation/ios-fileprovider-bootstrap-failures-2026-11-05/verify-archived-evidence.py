#!/usr/bin/env python3
"""Verify this archive with Python's standard library; never follow recorded host paths."""
import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct

ROOT = Path(__file__).resolve().parent
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
IDENTITY = ("bundle_id", "executable_sha256", "bundle_tree_sha256", "version", "build")
PINS = {
    "001": ("ff99aadb4991933774eed684dda70805de9d0055", "10099e21e2f46d811effd495c3f28bce34b10172", 36.779,
            "92a7137d1dc28cc094c41d5012343f0ed3fd155730b032949ba1831ff631e6eb"),
    "002": ("48412de3dae41e97fba38623f1dc466f41bbe441", "628562d52e3b0c957be67a43ac2851b7f84c23c5", 16.147,
            "7a6e5491f880d1427b677fc425f8c20f68c953720e49c1f44c769dfffb5f1400"),
}


def data(relative):
    path = ROOT / relative
    assert path.is_file() and not path.is_symlink(), relative
    assert path.resolve().is_relative_to(ROOT), relative
    return path.read_bytes()


def load(relative):
    return json.loads(data(relative))


def identity(actual, expected):
    assert all(actual[key] == expected[key] for key in IDENTITY), (actual, expected)


def public_snapshot(filename):
    text = data("evidence/002/attachments/" + filename).decode()
    assert f"App Group: {GROUP}\nDomain: logisim-revocation-fixture-v1\nItem: revocation-circuit-v1" in text
    return text, json.loads(text.split("\nSNAPSHOT\n", 1)[1])


def verify():
    sums = {}
    for line in data("SHA256SUMS").decode().splitlines():
        digest, name = line.split("  ", 1)
        assert re.fullmatch(r"[0-9a-f]{64}", digest)
        assert name not in sums and not Path(name).is_absolute() and ".." not in Path(name).parts
        assert hashlib.sha256(data(name)).hexdigest() == digest, name
        sums[name] = digest
    files = {str(p.relative_to(ROOT)) for p in ROOT.rglob("*") if p.is_file()}
    assert set(sums) == files - {"SHA256SUMS"}, "Unlisted or missing archive files"
    inventory = load("inventory.json")
    for entry in inventory["files"]:
        value = data(entry["path"])
        assert len(value) == entry["bytes"] and hashlib.sha256(value).hexdigest() == entry["sha256"], entry["path"]

    receipt = load("receipt.json")
    assert receipt["status"] == "FAIL" and len(receipt["cases"]) == 2
    manifests = {}
    for case, (fixture, test, seconds, digest) in PINS.items():
        prefix = f"evidence/{case}/"
        summary = load(prefix + "xcresult-summary.json")
        assert (summary["result"], summary["totalTestCount"], summary["failedTests"], summary["passedTests"], summary["skippedTests"]) == ("Failed", 1, 1, 0, 0)
        assert load(prefix + "execution.json")["exit_code"] == 65
        log = data(prefix + "test.log").decode()
        assert f"failed ({seconds} seconds)." in log and "** TEST EXECUTE FAILED **" in log
        preflight = load(prefix + "preflight.json")
        assert (preflight["fixture_source"], preflight["test_source"]) == (fixture, test)
        manifest_bytes = data(prefix + "harness/ProvenanceSignedFixture.json")
        assert hashlib.sha256(manifest_bytes).hexdigest() == digest
        assert manifest_bytes == data(prefix + "signed-build/artifact.json")
        manifest = json.loads(manifest_bytes)
        manifests[case] = manifest
        assert manifest["source"] == fixture and manifest["status"] == "BUILD_AND_ADHOC_SIGN_PASS"
        assert all(command["exitCode"] == 0 for command in manifest["commands"])
        installed = load(prefix + "installation.json")
        assert installed["exit_code"] == 0
        for product in ("app", "provider"):
            identity(preflight["checks"][product]["artifact"], manifest[product])
            identity(installed["installed_verified"][product], manifest[product])
            expected = {"com.apple.security.application-groups": [GROUP]}
            assert preflight["checks"][product]["entitlements"] == expected
            assert plistlib.loads(data(prefix + product + "-actual-entitlements.plist")) == expected
            assert plistlib.loads(data(prefix + f"signed-build/entitlements-{product.title()}.stdout.plist")) == expected
        for path in (ROOT / prefix / "fixture-source").rglob("*"):
            if path.is_file():
                relative = str(path.relative_to(ROOT / prefix / "fixture-source"))
                assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest["sourceFiles"][relative], relative
        case_receipt = next(item for item in receipt["cases"] if item["id"] == case)
        assert case_receipt["status"] == "FAIL" and case_receipt["seconds"] == seconds
        assert case_receipt["counts"] == {"total": 1, "passed": 0, "failed": 1, "skipped": 0}

    first = data("evidence/001/RevocationFixture-original.ips").decode()
    decoder = json.JSONDecoder()
    _, offset = decoder.raw_decode(first)
    crash = json.loads(first[offset:])
    assert crash["pid"] == 11986 and crash["exception"]["signal"] == "SIGTRAP"
    assert crash["threads"][crash["faultingThread"]]["frames"][0]["symbol"] == "___UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke"
    assert 'Tap "fixture.prepare" Button' not in data("evidence/001/test.log").decode()
    second_log = data("evidence/002/test.log").decode()
    assert 'Tap "fixture.prepare" Button' in second_log and 'Tap "fixture.domain.add" Button' in second_log
    assert 'Tap "fixture.domain.inspect" Button' not in second_log
    _, prepared = public_snapshot("314FED51-DE97-4EBE-A2FD-FEA15F3E469C.txt")
    failed_text, failed = public_snapshot("30C62F3C-AEC4-4300-9141-34EDB6A71DE2.txt")
    _, terminal = public_snapshot("EE3F1633-7083-483E-BFBD-1877FF32FBAA.txt")
    assert failed == terminal
    expected_state = {"authenticationAllowed": True, "backendByteCount": 552,
                      "backendSHA256": "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88",
                      "generation": 1, "schemaVersion": 1}
    assert prepared["state"] == failed["state"] == expected_state
    assert [event["kind"] for event in prepared["events"]] == ["prepare"]
    assert [event["kind"] for event in failed["events"]] == ["prepare", "domain.registration.requested", "domain.registration.failed"]
    assert [event["sequence"] for event in failed["events"]] == [1, 2, 3]
    assert all(event["process"] == "RevocationFixture:34488" for event in failed["events"])
    assert failed["events"][-1]["errorCode"] == -2001 and failed["events"][-1]["errorDomain"] == "NSFileProviderErrorDomain"
    assert "Operation failed: NSFileProviderErrorDomain / -2001\nThe application cannot be used right now." in failed_text
    boundary = receipt["cases"][1]["boundary"]
    assert boundary["errorCode"] == -2001 and boundary["domainCount"] == "NOT_OBSERVED" and boundary["cause"] == "UNDETERMINED"
    cleanup = load("evidence/002/cleanup.json")
    assert cleanup["exit_code"] == 0 and cleanup["command"][-1] == "thalesmms.logisim.validation.revocationfixture"
    after = load("evidence/002/installed-after.json")
    identity(after["fixture"], manifests["002"]["app"])
    reference = load("evidence/main-final-peer-reference.json")
    assert reference["sourcePin"] == "6cef05429cf66be23569aaebc3c72950cf3fe53a"
    identity(after["main"], reference["app"])

    first_source = data("evidence/001/harness/FileProviderBootstrapTests.swift").decode()
    second_source = data("evidence/002/harness/FileProviderBootstrapTests.swift").decode()
    replacements = [(PINS["001"][3], PINS["002"][3]), (PINS["001"][0], PINS["002"][0])]
    replacements += [(manifests["001"][p]["bundle_tree_sha256"], manifests["002"][p]["bundle_tree_sha256"]) for p in ("app", "provider")]
    for before, after_value in replacements:
        assert first_source.count(before) == 1
        first_source = first_source.replace(before, after_value)
    assert first_source == second_source
    assert data("evidence/001/harness/project.yml") == data("evidence/002/harness/project.yml")
    pngs = 0
    for attachments in inventory["attachments"].values():
        for attachment in attachments:
            if attachment["archived"]:
                payload = data(attachment["path"])
                assert len(payload) == attachment["bytes"] and hashlib.sha256(payload).hexdigest() == attachment["sha256"]
                if attachment["path"].endswith(".png"):
                    assert payload[:8] == b"\x89PNG\r\n\x1a\n" and all(struct.unpack(">II", payload[16:24]))
                    pngs += 1
    return {"archiveVerification": "PASS", "historicalNativeResults": "FAIL / FAIL", "copiedEvidenceFiles": len(inventory["files"]), "screenshots": pngs,
            "registration": "callback error -2001; domain count and cause undetermined", "deviceAccess": False}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
