#!/usr/bin/env python3
"""Verify this folder only. Recorded host and Simulator paths are never opened."""
import base64
from datetime import datetime
import gzip
import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct

ROOT = Path(__file__).resolve().parent
DEVICE = "AD28C68F-21D3-4FA6-BCD7-383AE2A1D372"
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
EXT = "thalesmms.logisim.validation.revocationfixture.provider"
MANIFEST = "bda24c74410814276f00474a689ae95508deb7330cc0eacae9f55bf54ec7388a"
BACKEND = "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88"


def read(name):
    p = ROOT / name
    assert p.resolve().is_relative_to(ROOT) and p.is_file() and not p.is_symlink(), name
    return p.read_bytes()


def load(name):
    return json.loads(read(name))


def stamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def snapshot(name):
    return json.loads(read("evidence/attachments/" + name).decode().split("\nSNAPSHOT\n", 1)[1])


def verify():
    checksums = {}
    for line in read("SHA256SUMS").decode().splitlines():
        digest, name = line.split("  ", 1)
        assert re.fullmatch(r"[0-9a-f]{64}", digest) and name not in checksums
        assert not Path(name).is_absolute() and ".." not in Path(name).parts
        assert hashlib.sha256(read(name)).hexdigest() == digest, name
        checksums[name] = digest
    assert set(checksums) == {str(p.relative_to(ROOT)) for p in ROOT.rglob("*") if p.is_file()} - {"SHA256SUMS"}
    inventory = load("inventory.json")
    inventory_paths = [item["path"] for item in inventory["files"]]
    assert len(inventory_paths) == len(set(inventory_paths)), "Duplicate inventory path"
    assert set(inventory_paths) == {name for name in checksums if name.startswith("evidence/")}, "Inventory coverage mismatch"
    for item in inventory["files"]:
        b = read(item["path"])
        assert len(b) == item["bytes"] and hashlib.sha256(b).hexdigest() == item["sha256"], item["path"]
    raw = {}
    for name, item in inventory["compressedRawJSON"].items():
        compressed = read(item["path"])
        assert int.from_bytes(compressed[4:8], "little") == 0
        value = gzip.decompress(compressed)
        assert len(value) == item["originalBytes"] and hashlib.sha256(value).hexdigest() == item["originalSHA256"]
        raw[name] = value
    def output(name):
        return raw[name] if name in raw else read("evidence/" + name)
    commands = load("evidence/commands.json")
    for c in commands:
        for stream in ("stdout", "stderr"):
            b = output(c[stream]["path"])
            assert len(b) == c[stream]["bytes"] and hashlib.sha256(b).hexdigest() == c[stream]["sha256"]
        assert c["exitCode"] == 0, c["name"]
    install = [c for c in commands if c["name"] == "reinstall-exact-fixture"]
    assert len(install) == 1 and install[0]["argv"][2:4] == ["install", DEVICE]
    install = install[0]
    terminate = next(c for c in commands if c["name"] == "fixture-terminate")
    assert terminate["argv"][2:] == ["terminate", DEVICE, "thalesmms.logisim.validation.revocationfixture"]
    terminal, receipt = load("evidence/terminal.json"), load("receipt.json")
    assert terminal["status"] == receipt["status"] == "FAIL" and terminal["testExitCode"] == 65
    assert terminal["noRetry"] and terminal["fixtureTerminated"] and terminal["deviceReturned"] == DEVICE
    assert receipt["caseSeconds"] == 16.731 and receipt["limits"]["rootCause"] == "UNDETERMINED"
    assert receipt["limits"]["revocationIssue26"] == "NOT_QUALIFIED"
    summary = load("evidence/xcresult-summary.stdout")
    counts = {"totalTestCount": 1, "passedTests": 0, "failedTests": 1, "skippedTests": 0}
    assert summary["result"] == "Failed" and all(summary[k] == v for k, v in counts.items()) and receipt["counts"] == counts
    assert "failed (16.731 seconds)." in read("evidence/test.log").decode()
    assert read("evidence/execution-driver.py") == read("evidence/driver/logisim-provider-existing-group-ad28-002.py")
    execution = load("evidence/execution.json")
    assert execution["exit_code"] == 65 and execution["command"] == load("evidence/test-process.json")["command"]
    assert "test-without-building" in execution["command"] and f"platform=iOS Simulator,id={DEVICE}" in execution["command"]
    manifest_bytes = read("evidence/source-artifact.json")
    assert hashlib.sha256(manifest_bytes).hexdigest() == MANIFEST
    assert manifest_bytes == read("evidence/harness/ProvenanceSignedFixture.json") == read("evidence/signed-build/artifact.json")
    manifest = json.loads(manifest_bytes)
    assert manifest["source"] == "11fc44d53b229558fbfc57b66e0b2f2d11a0037b"
    pre = load("evidence/preflight.json")
    assert pre["status"] == "PASS" and pre["manifestSHA256"] == MANIFEST
    assert hashlib.sha256(read("evidence/harness/FileProviderBootstrapTests.swift")).hexdigest() == pre["stagedHarnessSHA256"]
    compiled = {item["path"]: item["sha256"] for item in pre["compiledHarnessFiles"]}
    assert compiled["ProvenanceSignedFixture.json"] == MANIFEST
    installed = [load(f"evidence/{name}-installed.json") for name in ("before", "after-install", "after")]
    for product in ("app", "provider"):
        for record in installed + [load("evidence/source-products-after.json")]:
            assert all(record[product][k] == manifest[product][k] for k in ("bundle_id", "executable_sha256", "bundle_tree_sha256", "version", "build"))
        assert installed[0][product]["files"] == installed[1][product]["files"] == installed[2][product]["files"]
        expected = {"com.apple.security.application-groups": [GROUP]}
        for phase in ("before", "after-install", "after"):
            assert plistlib.loads(read(f"evidence/{phase}-{product}-entitlements.stdout")) == expected
    assert installed[0]["app"]["path"] != installed[1]["app"]["path"] == installed[2]["app"]["path"]
    for phase in ("before", "after"):
        device = load(f"evidence/{phase}-device.json")
        assert device["device"]["udid"] == DEVICE and device["runtime"]["version"] == "27.0" and device["runtime"]["buildversion"] == "24A434"
    groups = [load(f"evidence/{phase}-group.json") for phase in ("before", "after-install", "after")]
    before, after_install, after = groups
    assert stamp(before["queriedUTC"]) < stamp(install["startedUTC"]) < stamp(install["finishedUTC"]) < stamp(after_install["queriedUTC"])
    assert stamp(before["directoryBirthUTC"]) < stamp(before["queriedUTC"])
    assert before["backendBytes"] == before["state"]["backendByteCount"] == 552
    assert before["backendSHA256"] == before["state"]["backendSHA256"] == BACKEND
    for g in groups:
        assert g["path"].endswith("/5076E0F2-CD1B-4A1F-9E4D-DD7A771F94F4")
        assert g["metadata"]["MCMMetadataIdentifier"] == GROUP and g["metadata"]["MCMMetadataUUID"] == "C4A78BD1-44B3-4590-B045-A3AC1883D566"
        assert all(g[k] == before[k] for k in ("path", "metadata", "metadataSHA256", "directoryBirthUTC", "state", "backendBytes", "backendSHA256"))
    databases = [load(f"evidence/{phase}-fixture-state.json") for phase in ("before", "after-install", "after")]
    assert read("evidence/before-fixture-state.json") == read("evidence/after-install-fixture-state.json")
    assert databases[0]["events"] == databases[1]["events"] == databases[2]["events"][:3]
    assert [e["sequence"] for e in databases[2]["events"]] == [1, 2, 3, 4, 5, 6]
    assert databases[0]["nextSequence"] == 4 and databases[2]["nextSequence"] == 7
    for phase, db, group in zip(("before", "after-install", "after"), databases, groups):
        b = base64.b64decode(db["contents"], validate=True)
        assert b == read(f"evidence/backend-{phase}.circ") and len(b) == 552 and hashlib.sha256(b).hexdigest() == BACKEND
        assert db["state"] == group["state"] and db["events"] == group["events"]
        assert hashlib.sha256(read(f"evidence/{phase}-fixture-state.json")).hexdigest() == group["stateSHA256"]
        assert hashlib.sha256(read(f"evidence/{phase}-group-metadata.plist")).hexdigest() == group["metadataSHA256"]
        assert plistlib.loads(read(f"evidence/{phase}-group-metadata.plist")) == group["metadata"]
    baseline = snapshot("612F21E9-982A-4C8C-A893-00DBFD3CDEBD.txt")
    prepared = snapshot("78902D97-B2D4-4825-A48F-3DE9B2893C1E.txt")
    failed = snapshot("E077909C-7D88-466C-89C8-900CA7E99D8B.txt")
    assert baseline["events"] == databases[0]["events"] and prepared["events"] == databases[2]["events"][:4]
    assert failed == snapshot("67952F8E-E351-4769-9F2B-D22F7B726E5E.txt")
    assert failed["events"] == databases[2]["events"] and baseline["state"] == prepared["state"] == failed["state"] == before["state"]
    assert [(e["sequence"], e["kind"]) for e in failed["events"][3:]] == [(4, "prepare"), (5, "domain.registration.requested"), (6, "domain.registration.failed")]
    assert all(e["process"] == "RevocationFixture:86977" for e in failed["events"][3:])
    diagnostic = failed["events"][-1]["errorDiagnostic"]
    assert [(e["domain"], e["code"]) for e in diagnostic["chain"]] == [("NSFileProviderErrorDomain", -2001), ("NSFileProviderErrorDomain", -2014)] and diagnostic["termination"] == "complete"
    records = json.loads(raw["new-discovery-after-reinstall.stdout"])
    selected = load("discovery-selected.json")
    assert len(records) == selected["sourceRecords"] == 976 and selected["indexBase"] == 0
    assert [e["index"] for e in selected["events"]] == [631, 958, 960, 961, 962, 963]
    assert all(records[e["index"]] == e["record"] for e in selected["events"])
    assert "F8E78B69-61F8-495B-A993-E0BB85E025F4" in records[631]["eventMessage"] and "plugin INSTALLED" in records[631]["eventMessage"]
    assert EXT in records[631]["eventMessage"] and installed[1]["provider"]["path"] in records[631]["eventMessage"]
    assert records[631]["processImagePath"].endswith("/pkd")
    assert stamp(install["startedUTC"]) < stamp(records[631]["timestamp"]) < stamp(install["finishedUTC"])
    assert stamp(after_install["queriedUTC"]) < stamp(records[958]["timestamp"]) < stamp(records[961]["timestamp"]) < stamp(records[962]["timestamp"]) < stamp(records[963]["timestamp"]) < stamp(execution["started"])
    for i in (958, 961, 962, 963):
        assert records[i]["processID"] == 68875 and records[i]["threadID"] == 9903950 and records[i]["processImagePath"].endswith("/fileproviderd")
    assert GROUP in records[958]["eventMessage"] and "class = 7" in records[958]["eventMessage"]
    assert GROUP in records[961]["eventMessage"] and before["path"] in records[961]["eventMessage"] and before["metadata"]["MCMMetadataUUID"] in records[961]["eventMessage"]
    assert "doesn't have a group container" in records[962]["eventMessage"] and "Ignoring the extension" in records[962]["eventMessage"]
    assert EXT in records[963]["eventMessage"] and "Failed to create descriptor" in records[963]["eventMessage"]
    full = json.loads(raw["full-differential-history.stdout"])
    assert len(full) == selected["installationStructure"]["sourceRecords"] == 2005
    assert all(full[e["index"]] == e["record"] for e in selected["installationStructure"]["events"])
    assert [e["index"] for e in selected["installationStructure"]["events"]] == [2, 17, 18]
    message = full[17]["eventMessage"]
    assert full[17]["processImagePath"].endswith("/installcoordinationd") and "NSExtensionAttributes" not in message
    assert "NSExtensionFileProviderDocumentGroup" in message and "NSExtensionFileProviderSupportsEnumeration" in message
    assert all("had no entitlements" in full[i]["eventMessage"] and full[i]["processImagePath"].endswith("/CoreSimulatorBridge") for i in (2, 18))
    config = load("configuration-structure.json")["producerInfo"]["NSExtension"]
    assert config["NSExtensionFileProviderDocumentGroup"] == GROUP and "NSExtensionAttributes" not in config
    pngs = 0
    for item in inventory["attachments"]:
        if item["archived"] and item["path"].endswith(".png"):
            b = read(item["path"])
            assert b[:8] == b"\x89PNG\r\n\x1a\n" and all(struct.unpack(">II", b[16:24]))
            pngs += 1
    return {"archiveVerification": "PASS", "nativeResult": "FAIL", "counts": counts, "evidenceFiles": len(inventory["files"]), "screenshots": pngs, "events": "prefix3 preserved; new4/5/6", "cause": "UNDETERMINED", "deviceAccess": False}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
