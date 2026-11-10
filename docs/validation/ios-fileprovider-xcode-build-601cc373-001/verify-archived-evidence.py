#!/usr/bin/env python3
"""Verify retained bytes with stdlib only; never run archived helpers or host paths."""
import hashlib
import json
from pathlib import Path
import plistlib
import re
import struct

ROOT = Path(__file__).resolve().parent
SOURCE = "601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd"
MANIFEST = "c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159"
GROUP_KEY = "com.apple.security.application-groups"
GROUP = "group.thalesmms.logisim.validation.revocationfixture"


def read(name):
    p = ROOT / name
    assert p.resolve().is_relative_to(ROOT) and p.is_file() and not p.is_symlink(), name
    return p.read_bytes()


def load(name):
    return json.loads(read(name))


def decode_der(blob):
    """Strict bounded decoder for the observed version-1 string/array dictionaries."""
    assert 0 < len(blob) <= 1048576
    nodes = 0
    def node(pos, end, depth):
        nonlocal nodes
        nodes += 1
        assert nodes <= 256 and depth <= 12 and pos + 2 <= end
        tag, length = blob[pos:pos + 2]
        pos += 2
        assert tag & 31 != 31
        if length & 128:
            width = length & 127
            assert 1 <= width <= 4 and pos + width <= end and blob[pos] != 0
            length = int.from_bytes(blob[pos:pos + width], "big")
            assert length >= 128
            pos += width
        limit = pos + length
        assert limit <= end
        if tag & 32:
            value = []
            while pos < limit:
                child, pos = node(pos, limit, depth + 1)
                value.append(child)
        else:
            value = blob[pos:limit]
        return (tag, value), limit
    root, end = node(0, len(blob), 0)
    assert end == len(blob) and root[0] == 0x70
    assert len(root[1]) == 2 and root[1][0] == (2, b"\x01") and root[1][1][0] == 0xB0
    def value(item):
        tag, payload = item
        if tag == 0x0C:
            return payload.decode("utf-8", "strict")
        assert tag == 0x30
        return [value(child) for child in payload]
    result = {}
    for tag, pair in root[1][1][1]:
        assert tag == 0x30 and len(pair) == 2 and pair[0][0] == 0x0C
        key = pair[0][1].decode("utf-8", "strict")
        assert key not in result
        result[key] = value(pair[1])
    return result


def verify():
    sums = {}
    for line in read("SHA256SUMS").decode().splitlines():
        digest, name = line.split("  ", 1)
        assert re.fullmatch(r"[0-9a-f]{64}", digest) and name not in sums
        assert not Path(name).is_absolute() and ".." not in Path(name).parts
        assert hashlib.sha256(read(name)).hexdigest() == digest, name
        sums[name] = digest
    assert set(sums) == {str(p.relative_to(ROOT)) for p in ROOT.rglob("*") if p.is_file()} - {"SHA256SUMS"}
    inventory = load("inventory.json")["files"]
    paths = [e["path"] for e in inventory]
    assert len(paths) == len(set(paths)) and set(paths) == {p for p in sums if p.startswith("evidence/")}
    for e in inventory:
        b = read(e["path"])
        assert len(b) == e["bytes"] and hashlib.sha256(b).hexdigest() == e["sha256"], e["path"]
    manifest_bytes = read("evidence/artifact/artifact.json")
    assert hashlib.sha256(manifest_bytes).hexdigest() == MANIFEST
    assert manifest_bytes == read("evidence/independent-build-review/artifact.json")
    m, receipt = json.loads(manifest_bytes), load("receipt.json")
    assert m["source"] == receipt["source"] == SOURCE
    assert m["status"] == receipt["status"] == "BUILD_AND_ADHOC_SIGN_PASS"
    assert m["simulatorEntitlementReviewRequired"] is receipt["simulatorEntitlementReviewRequired"] is True
    assert receipt["additionalEntitlementReview"] == "PENDING_ROOT_REVIEW"
    assert receipt["prefixApprovedAsTeam"] is receipt["prefixOriginEstablished"] is False
    assert all(m[k] == receipt[k] == "NOT_RUN" for k in ("nativeExecution", "domainRegistration"))
    assert receipt["installation"] == "NOT_RUN" and receipt["buildPerformedByArchivalWorker"] is False
    assert all(c["exitCode"] == 0 for c in m["commands"])
    build = next(c for c in m["commands"] if c["arguments"][0] == "xcodebuild")
    assert all(flag in build["arguments"] for flag in ("CODE_SIGNING_ALLOWED=YES", "CODE_SIGNING_REQUIRED=YES", "CODE_SIGN_IDENTITY=-", "DEVELOPMENT_TEAM="))
    post = m["commands"][m["commands"].index(build) + 1:]
    assert all(c["arguments"][0] == "codesign" and "--sign" not in c["arguments"] and "--force" not in c["arguments"] for c in post)
    comparison = load("source-comparison.json")
    prior = load("evidence/prior-11fc-artifact.json")
    for name, digest in m["sourceFiles"].items():
        assert hashlib.sha256(read("evidence/pinned-fixture-source/" + name)).hexdigest() == digest
    for name, digest in comparison["unchangedSwiftConfigAndProjectSHA256"].items():
        assert prior["sourceFiles"][name] == m["sourceFiles"][name] == digest
    for path, key in (("build-simulator-fixture.py", "buildDriverSHA256"), ("simulator_entitlements.py", "simulatorEntitlementVerifierSHA256")):
        assert hashlib.sha256(read("evidence/pinned-fixture-source/" + path)).hexdigest() == m[key]
    assert read("evidence/pinned-artifact-reader.py") == read("evidence/artifact/artifact-reader.py")
    assert hashlib.sha256(read("evidence/pinned-artifact-reader.py")).hexdigest() == m["artifactReaderSHA256"]
    review = load("evidence/independent-build-review/byte-review.json")
    assert review["manifestSHA256"] == MANIFEST and review["prefixApprovedAsTeam"] is review["prefixOriginEstablished"] is False
    assert review["nativeExecution"] == review["domainRegistration"] == "NOT_RUN"
    for label, key in (("App", "app"), ("Provider", "provider")):
        report = m["simulatorEntitlements"][label]
        assert report["sectionOwners"] == ["main"] and report["mainHasSectionPair"] is True
        assert report["generatedEntitlementsReview"] == "REQUIRED_BEFORE_NATIVE"
        main = next(i for i in report["images"] if i["image"] == "main")
        debug = next(i for i in report["images"] if i["image"] == "debug-dylib")
        assert not debug["sections"] and set(main["sections"]) == {"__entitlements", "__ents_der"}
        section_bytes = {}
        for section, record in main["sections"].items():
            b = read("evidence/artifact/" + record["path"])
            assert len(b) == record["bytes"] and hashlib.sha256(b).hexdigest() == record["sha256"]
            generated = report["packagingInputs"][section]
            assert b == read("evidence/artifact/" + generated["path"])
            assert generated["bytes"] == len(b) and generated["sha256"] == record["sha256"]
            section_bytes[section] = b
        xml, der = plistlib.loads(section_bytes["__entitlements"]), decode_der(section_bytes["__ents_der"])
        expected = {"application-identifier": "P4VGX44GD8." + m[key]["bundle_id"], GROUP_KEY: [GROUP]}
        assert xml == der == report["simulatedEntitlements"] == receipt["products"][label]["sectionDictionary"] == expected
        assert report["generatedAdditionalKeys"] == {"application-identifier": expected["application-identifier"]}
        assert "com.apple.developer.team-identifier" not in xml
        assert plistlib.loads(read(f"evidence/artifact/entitlements-{label}.stdout.plist")) == report["codeSignatureEntitlements"] == {}
        assert plistlib.loads(read(f"evidence/independent-build-review/{label}-main-entitlements.xml")) == {}
        assert decode_der(read(f"evidence/independent-build-review/{label}-main-entitlements.der")) == {}
        assert main["sha256"] == m[key]["executable_sha256"] != prior[key]["executable_sha256"]
        independent = review["bundles"][label]
        assert independent["treeSHA256"] == m[key]["bundle_tree_sha256"] and independent["mainSHA256"] == main["sha256"]
        assert not independent["images"]["debug-dylib"]["pair"]
        assert independent["images"]["main"]["pair"]["decodedEntitlements"] == expected
        for cd in independent["codeDirectories"]:
            assert cd["adhocFlag"] and cd["teamIdentifier"] is None and cd["allCodePageDigestsMatch"]
        signature = read(f"evidence/artifact/signature-{label}.log").decode()
        assert "Signature=adhoc" in signature and "TeamIdentifier=not set" in signature
    return {"archiveVerification": "PASS", "recordedBuild": m["status"], "evidenceFiles": len(inventory), "owners": "MAIN / MAIN", "sectionInputPairs": 4,
            "decodedActualDictionaries": "XML == DER; application-identifier plus one exact group", "extraReview": "PENDING_ROOT_REVIEW", "nativeExecution": "NOT_RUN", "domainRegistration": "NOT_RUN"}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
