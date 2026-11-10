#!/usr/bin/env python3
"""Verify archived files only, without following recorded host/Simulator paths."""
import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct

ROOT = Path(__file__).resolve().parent
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
DEVICE = "AD28C68F-21D3-4FA6-BCD7-383AE2A1D372"
SOURCE = "11fc44d53b229558fbfc57b66e0b2f2d11a0037b"
TEST = "6630378c89a0c9e0a6b15ee8b6ec275a5cd1aaca"
MANIFEST = "bda24c74410814276f00474a689ae95508deb7330cc0eacae9f55bf54ec7388a"
IDENTITY = ("bundle_id", "executable_sha256", "bundle_tree_sha256", "version", "build")


def read(name):
    p = ROOT / name
    assert p.is_file() and not p.is_symlink() and p.resolve().is_relative_to(ROOT), name
    return p.read_bytes()


def load(name):
    return json.loads(read(name))


def identity(actual, expected):
    assert all(actual[k] == expected[k] for k in IDENTITY), (actual, expected)


def snapshot(name):
    value = read("evidence/attachments/" + name).decode()
    assert f"App Group: {GROUP}\nDomain: logisim-revocation-fixture-v1\nItem: revocation-circuit-v1" in value
    return value, json.loads(value.split("\nSNAPSHOT\n", 1)[1])


def verify():
    sums = {}
    for line in read("SHA256SUMS").decode().splitlines():
        digest, name = line.split("  ", 1)
        assert re.fullmatch(r"[0-9a-f]{64}", digest)
        assert name not in sums and not Path(name).is_absolute() and ".." not in Path(name).parts
        assert hashlib.sha256(read(name)).hexdigest() == digest, name
        sums[name] = digest
    files = {str(p.relative_to(ROOT)) for p in ROOT.rglob("*") if p.is_file()}
    assert set(sums) == files - {"SHA256SUMS"}
    inventory = load("inventory.json")
    for entry in inventory["files"]:
        b = read(entry["path"])
        assert len(b) == entry["bytes"] and hashlib.sha256(b).hexdigest() == entry["sha256"], entry["path"]
    receipt = load("receipt.json")
    assert receipt["status"] == "FAIL" and receipt["caseSeconds"] == 17.003 and receipt["exitCode"] == 65
    assert receipt["fixtureSource"] == SOURCE and receipt["testSource"] == TEST
    summary = load("evidence/xcresult-summary.json")
    assert (summary["result"], summary["totalTestCount"], summary["passedTests"], summary["failedTests"], summary["skippedTests"]) == ("Failed", 1, 0, 1, 0)
    assert receipt["counts"] == {k: summary[k] for k in ("totalTestCount", "passedTests", "failedTests", "skippedTests")}
    log = read("evidence/test.log").decode()
    assert "failed (17.003 seconds)." in log and "** TEST EXECUTE FAILED **" in log
    assert log.count('Tap "fixture.prepare" Button') == log.count('Tap "fixture.domain.add" Button') == 1
    assert 'Tap "fixture.domain.inspect" Button' not in log
    execution = load("evidence/execution.json")
    assert execution["exit_code"] == 65 and f"platform=iOS Simulator,id={DEVICE}" in execution["command"]
    manifest_bytes = read("evidence/signed-build/artifact.json")
    assert hashlib.sha256(manifest_bytes).hexdigest() == MANIFEST
    assert read("evidence/harness/ProvenanceSignedFixture.json") == manifest_bytes
    manifest = json.loads(manifest_bytes)
    assert manifest["source"] == SOURCE and manifest["status"] == "BUILD_AND_ADHOC_SIGN_PASS"
    assert all(c["exitCode"] == 0 for c in manifest["commands"])
    pre, post = load("evidence/preflight.json"), load("evidence/postflight.json")
    assert pre["manifest_sha256"] == MANIFEST and pre["fixture_source"] == SOURCE
    for key in ("udid", "name", "deviceTypeIdentifier", "runtime"):
        assert pre["device"][key] == post["device"][key]
    assert pre["device"]["udid"] == DEVICE
    assert pre["runtime"]["version"] == "27.0" and pre["runtime"]["buildversion"] == "24A434"
    installed = load("evidence/installation.json")
    assert installed["exit_code"] == 0 and installed["command"][2:4] == ["install", DEVICE]
    for product in ("app", "provider"):
        for actual in (pre["checks"][product]["artifact"], installed["installed_verified"][product], post[product], receipt[product]):
            identity(actual, manifest[product])
        expected = {"com.apple.security.application-groups": [GROUP]}
        assert pre["checks"][product]["entitlements"] == expected
        assert plistlib.loads(read(f"evidence/{product}-entitlements.plist")) == expected
        assert plistlib.loads(read(f"evidence/signed-build/entitlements-{product.title()}.stdout.plist")) == expected
    cleanup = load("evidence/cleanup.json")
    assert cleanup["exit_code"] == post["cleanup_exit"] == 0
    assert cleanup["command"][2:] == ["terminate", DEVICE, "thalesmms.logisim.validation.revocationfixture"]
    harness = load("evidence/harness-source.json")
    assert harness["ui_source"] == TEST and harness["fixture_source"] == SOURCE
    for name, digest in harness["files"].items():
        assert hashlib.sha256(read("evidence/harness/" + name)).hexdigest() == digest
    for path in (ROOT / "evidence/fixture-source").rglob("*"):
        if path.is_file():
            name = str(path.relative_to(ROOT / "evidence/fixture-source"))
            assert hashlib.sha256(path.read_bytes()).hexdigest() == manifest["sourceFiles"][name]
    resolved = load("evidence/signed-build/info-resolved.json")
    extension = resolved["provider"]["values"]["NSExtension"]
    assert extension["NSExtensionPointIdentifier"] == "com.apple.fileprovider-nonui"
    assert extension["NSExtensionPrincipalClass"] == "RevocationProvider.RevocationFileProvider"
    assert extension["NSExtensionFileProviderDocumentGroup"] == GROUP

    _, prepared = snapshot("41FFAB13-A6FD-499D-BD4E-13AA4EFEB868.txt")
    text, failed = snapshot("47D5794A-6AFF-40A3-AB0C-D9CD97C215B1.txt")
    _, terminal = snapshot("832BD07F-97F9-4923-9467-67FF79FBB3D9.txt")
    assert terminal == failed
    state = {"authenticationAllowed": True, "backendByteCount": 552,
             "backendSHA256": "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88", "generation": 1, "schemaVersion": 1}
    assert prepared["state"] == failed["state"] == receipt["publicObservations"]["backendStateAfterFailure"] == state
    assert [e["kind"] for e in prepared["events"]] == ["prepare"]
    assert [e["kind"] for e in failed["events"]] == ["prepare", "domain.registration.requested", "domain.registration.failed"]
    assert [e["sequence"] for e in failed["events"]] == [1, 2, 3]
    assert all(e["process"] == "RevocationFixture:69225" for e in failed["events"])
    last = failed["events"][-1]
    assert last["errorDomain"] == "NSFileProviderErrorDomain" and last["errorCode"] == -2001
    diagnostic = last["errorDiagnostic"]
    assert diagnostic == receipt["publicObservations"]["errorDiagnostic"]
    assert diagnostic["termination"] == "complete"
    assert [(e["domain"], e["code"]) for e in diagnostic["chain"]] == [("NSFileProviderErrorDomain", -2001), ("NSFileProviderErrorDomain", -2014)]
    assert "Operation failed: NSFileProviderErrorDomain / -2001" in text
    assert receipt["classification"]["underlying"]["symbol"] == "NSFileProviderErrorApplicationExtensionNotFound"
    assert receipt["classification"]["underlyingCause"] == "UNDETERMINED" and receipt["limits"]["domainCount"] == "NOT_OBSERVED"
    prior_manifest = load("evidence/prior-harness/ProvenanceSignedFixture.json")
    old = read("evidence/prior-harness/FileProviderBootstrapTests.swift").decode()
    replacements = [(hashlib.sha256(read("evidence/prior-harness/ProvenanceSignedFixture.json")).hexdigest(), MANIFEST), (prior_manifest["source"], SOURCE)]
    replacements += [(prior_manifest[p]["bundle_tree_sha256"], manifest[p]["bundle_tree_sha256"]) for p in ("app", "provider")]
    for before, after in replacements:
        assert old.count(before) == 1
        old = old.replace(before, after)
    assert old == read("evidence/harness/FileProviderBootstrapTests.swift").decode()
    previous = load("evidence/previous-failures-reference.json")
    assert previous["status"] == "FAIL" and previous["device"] != DEVICE
    pngs = 0
    for entry in inventory["attachments"]:
        if entry["archived"] and entry["path"].endswith(".png"):
            b = read(entry["path"])
            assert b[:8] == b"\x89PNG\r\n\x1a\n" and all(struct.unpack(">II", b[16:24]))
            pngs += 1
    return {"archiveVerification": "PASS", "nativeResult": "FAIL", "counts": receipt["counts"], "copiedEvidenceFiles": len(inventory["files"]),
            "screenshots": pngs, "observedChain": [-2001, -2014], "cause": "UNDETERMINED", "deviceAccess": False}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
