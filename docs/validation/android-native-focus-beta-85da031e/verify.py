#!/usr/bin/env python3
"""Verify the retained selected-case receipt, identities and complete XML transitions."""
from pathlib import Path
import collections
import copy
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
RUN = ROOT / "raw/run"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical(root):
    return ET.canonicalize(ET.tostring(root, encoding="unicode"))


def components(root):
    values = root.findall(".//comp")
    result = {node.get("logisim-mobile-id"): node for node in values}
    assert len(result) == len(values)
    return result


for line in (ROOT / "SHA256SUMS").read_text().splitlines():
    expected, relative = line.split("  ", 1)
    assert digest(ROOT / relative) == expected, relative

manifest = json.loads((RUN / "manifest.json").read_text())
assert manifest["status"] == "PASS" and manifest["qualification"] == "PARTIAL"
assert manifest["requestedCases"] == "focus"
assert manifest["artifacts"] == {
    "appSha256": "67e81d691beb3830cf49325c81ccffca9b66f2bf8aa98fdc46c9ee7d1f9e5e2c",
    "testSha256": "04a4fdecd9dfdae6577ce040a7595e8c96bb62ac821080ee6faa0f1de7cde52f",
}
assert manifest["installedArtifactsAfter"] == manifest["artifacts"]
assert (RUN / "lastanr-before.txt").read_bytes() == (RUN / "lastanr-after.txt").read_bytes()
case, = manifest["native"]["cases"]
assert case["case"] == "focus" and case["status"] == "PASS"
events = manifest["native"]["events"]
assert collections.Counter(item["kind"] for item in events) == {
    "input-session": 1, "foreground-guard": 7, "motion": 4, "key": 16,
}
assert all(item["accepted"] is True for item in events if item["kind"] != "input-session")

fixture, cleared, duplicated = [ET.parse(RUN / "native" / name).getroot() for name in (
    "0-fixture.circ", "1-focus-after.circ", "2-focus-canvas-duplicate.circ",
)]
assert set(components(fixture)) == set(components(cleared)) == {"mi-input", "mi-not", "mi-output"}
assert all(len(root.findall(".//wire")) == 2 for root in (fixture, cleared, duplicated))
assert components(cleared)["mi-not"].find("a[@name='label']").get("val") == ""
restored = copy.deepcopy(cleared)
components(restored)["mi-not"].find("a[@name='label']").set(
    "val", components(fixture)["mi-not"].find("a[@name='label']").get("val"),
)
assert canonical(restored) == canonical(fixture), "Label clear changed another part of the complete project"
assert set(components(duplicated)) == {"mi-input", "mi-not", "mi-output", "c1"}
assert components(duplicated)["mi-not"].get("loc") == "(200,100)"
assert components(duplicated)["c1"].get("name") == "NOT Gate"
assert components(duplicated)["c1"].get("loc") == "(220,120)"
without_clone = copy.deepcopy(duplicated)
without_clone.find("circuit").remove(components(without_clone)["c1"])
assert canonical(without_clone) == canonical(cleared), "Canvas duplication changed another part of the complete project"

proof = case["focusViewportProof"]
assert proof["before"]["frame"]["windowId"] == proof["after"]["windowId"] == 10673
assert proof["before"]["frame"]["canvas"] == proof["after"]["canvas"]
assert proof["anchorDisplacements"] == {"input": {"x": 0, "y": -27}, "output": {"x": 0, "y": -27}}
assert proof["before"]["targetPoint"] == {"x": 697, "y": 1321}
assert proof["translatedPoint"] == {"x": 697, "y": 1294}
print("PASS: retained hashes, identities, complete XML transitions and selected-case frame proof")
