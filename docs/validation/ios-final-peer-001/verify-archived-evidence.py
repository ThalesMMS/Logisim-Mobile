#!/usr/bin/env python3
"""Recheck only the archived copied evidence; never follow Simulator paths in metadata."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ARCHIVE = Path(__file__).resolve().parent
checksum_names = []
for line in (ARCHIVE / "SHA256SUMS").read_text().splitlines():
    digest, name = line.split("  ", 1)
    path = (ARCHIVE / name).resolve()
    assert path.is_relative_to(ARCHIVE)
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, name
    checksum_names.append(name)
assert set(checksum_names) == {p.relative_to(ARCHIVE).as_posix() for p in ARCHIVE.rglob("*") if p.is_file() and p.name != "SHA256SUMS"}
ledger = json.loads((ARCHIVE / "minimal-artifacts.json").read_text())
for entry in ledger["artifacts"]:
    path = (ARCHIVE / entry["copy"]).resolve()
    assert path.is_relative_to(ARCHIVE), "Evidence must stay inside the copied evidence"
    data = path.read_bytes()
    assert len(data) == entry["bytes"], entry["copy"]
    assert hashlib.sha256(data).hexdigest() == entry["sha256"], entry["copy"]

evidence = ARCHIVE / "evidence"
report = json.loads((evidence / "results.json").read_text())
summary = json.loads((evidence / "xcresult-summary.json").read_text())
native = json.loads((evidence / "native-peer-receipt.json").read_text())
peer = json.loads((evidence / "peer-configuration.json").read_text())["externalPeer"]
assert report["status"] == report["execution"]["status"] == "PASS"
assert report["native_execution_gate"] == "NATIVE_PASS"
assert report["qualification_gate"] == "PARTIAL"
assert report["execution"]["exit_code"] == 0
assert report["selected_tests"] == ["EditorEndToEndTests/ExternalProviderEndToEndTests/testPeerDocumentCopyEditRestartAndConflict"]
assert native["executedViaUi"] is True and native["scope"] == "separate-app-local-Files"
assert len(native["observations"]) == 4
assert (summary["totalTestCount"], summary["passedTests"], summary["failedTests"], summary["skippedTests"]) == (1, 1, 0, 0)
assert native["result"] == "PASSED" and native["grantRevocation"] == "NOT_TESTED"
assert native["runID"] == report["run_id"]


def xml(name):
    return (evidence / "xml" / (name + ".circ")).read_bytes()


def peer_bytes(name):
    return (evidence / "peer" / (name + ".circ")).read_bytes()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def shape(node):
    text = node.text.strip() if node.text and node.text.strip() else ""
    return node.tag, tuple(sorted(node.attrib.items())), text, tuple(shape(child) for child in node)


original = peer_bytes("original")
assert original == peer_bytes("control-final") == peer_bytes("control-final-attachment")
assert sha(original) == peer["originalSHA256"] == native["originalSha256"] == native["controlSha256"]
assert xml("peer-copy-baseline") == xml("peer-external-baseline")
assert xml("peer-external-edited") == xml("peer-restored-after-restart")
assert xml("peer-restarted-edited") == xml("peer-local-after-conflict")
assert peer_bytes("peer-final") == peer_bytes("conflicting-revision") == peer_bytes("peer-final-attachment")
comment = f'\n<!-- Peer provider revision {report["run_id"]}: preserve exactly -->\n'.encode()
assert peer_bytes("peer-final") == xml("peer-restarted-edited") + comment

for before, after, prefix in [
    ("peer-copy-baseline", "peer-copy-edited", "PEER_COPY_"),
    ("peer-external-baseline", "peer-external-edited", "PEER_EDIT_"),
    ("peer-external-edited", "peer-restarted-edited", "PEER_RESTART_"),
]:
    expected = ET.fromstring(xml(before))
    inputs = [c for c in expected.findall("circuit/comp") if c.get("name") == "Pin" and c.get("loc") == "(100,100)"]
    assert len(inputs) == 1
    labels = [a for a in inputs[0].findall("a") if a.get("name") == "label"]
    assert len(labels) == 1
    old_label = labels[0].get("val")
    needle = ('val="' + old_label + '"').encode()
    replacement = ('val="' + prefix + report["run_id"] + '"').encode()
    assert xml(before).count(needle) == 1
    assert xml(before).replace(needle, replacement) == xml(after), after
    labels[0].set("val", prefix + report["run_id"])
    actual = ET.fromstring(xml(after))
    assert len(actual.findall("circuit")) == 1
    assert len(actual.findall("circuit/comp")) == 3 and len(actual.findall("circuit/wire")) == 2
    assert shape(expected) == shape(actual), after

for observation, xml_name, source in [
    (native["observations"][0], "peer-copy-edited", original),
    (native["observations"][1], "peer-external-edited", xml("peer-external-edited")),
    (native["observations"][2], "peer-restarted-edited", xml("peer-restarted-edited")),
    (native["observations"][3], "peer-local-after-conflict", peer_bytes("peer-final")),
]:
    assert observation["xmlSha256"] == sha(xml(xml_name))
    assert observation["sourceSha256"] == sha(source) and observation["sourceBytes"] == len(source)

assert len(list((evidence / "harness").glob("*.swift"))) == 6
for source in (evidence / "harness").glob("*.swift"):
    assert sha(source.read_bytes()) == report["harness_sources"][source.name]
assert report["run_id"] == "5f364710641a"
assert report["declared_baseline_sha"] == "6cef05429cf66be23569aaebc3c72950cf3fe53a"
expected_identity = {
    "bundle_id": "thalesmms.logisim.mobile",
    "executable_sha256": "198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946",
    "bundle_tree_sha256": "d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080",
}
postflight = json.loads((evidence / "independent-postflight.json").read_text())
assert postflight["status"] == "PASS"
assert postflight["checks"] == {
    "untouched_control_equals_original": "PASS",
    "local_after_conflict_equals_second_saved_xml": "PASS",
    "peer_equals_deliberately_injected_revision": "PASS",
}
assert postflight["native_receipt"] == native
assert json.loads((evidence / "native-peer-receipt-attachment.txt").read_text()) == native
assert (evidence / "native-peer-receipt-attachment.txt").read_bytes() == (evidence / "native-peer-receipt.json").read_bytes()
for observed in [report["artifact"], report["main_app_installation"]["before"],
                 report["main_app_installation"]["installed"], postflight["installed_after"]]:
    assert {key: observed[key] for key in expected_identity} == expected_identity
assert report["main_app_installation"]["status"] == "REUSED_IDENTICAL"
for key, data in [("original", original), ("control_after", peer_bytes("control-final")),
                  ("local_after_conflict", xml("peer-local-after-conflict")),
                  ("peer_after_conflict", peer_bytes("peer-final"))]:
    assert postflight["hashes"][key] == {"bytes": len(data), "sha256": sha(data)}
assert len(original) == 1375 and sha(original) == "f0cd987911c1a965ca95f1a42c3287bee70ba3d08900a5d09a7d33eea0da1604"
assert sha(xml("peer-local-after-conflict")) == "77113b1fb40fd34f87eab526654d2515a17916820f30634790d504b186f39c15"
assert sha(peer_bytes("peer-final")) == "a0ea1ac33f3041d3fc2a05c089a2f4d096e12f38648b71cfda3de20a793362d7"
run = json.loads((evidence / "harness/Run.json").read_text())
assert run["run_id"] == report["run_id"]
assert report["external_peer"]["originalSHA256"] == sha(original)
assert Path(peer["fixture"]).name == "peer.circ" and Path(peer["control"]).name == "control.circ"
assert Path(peer["documents"]).name == report["run_id"]
assert Path(run["documents"]).name == report["run_id"]
assert Path(peer["documents"]).parents[2] != Path(run["documents"]).parents[2]
journal = (evidence / "test.log").read_text()
assert "passed (274.482 seconds)" in journal
assert 'Tap "Import a copy" Button' in journal and 'Tap "Edit external file" Button' in journal
positions = []
start = 0
while True:
    start = journal.find('Tap "Save to file" Button', start)
    if start == -1: break
    positions.append(start)
    start += 1
assert len(positions) == 3
terminate = journal.index("Terminate thalesmms.logisim.mobile", positions[0])
launch = journal.index("Launch thalesmms.logisim.mobile", terminate)
assert positions[0] < terminate < launch < positions[1] < positions[2]
assert "Opening default application for URL" not in journal[terminate:positions[1]]
assert "File peer.circ saved." in (evidence / "ui/first-save-tree.txt").read_text()
assert "File peer.circ saved." in (evidence / "ui/second-save-tree.txt").read_text()
assert "Another app changed this file." in (evidence / "ui/conflict-tree.txt").read_text()
for mode in ["copy", "edit"]:
    picker = (evidence / "ui" / (mode + "-native-picker.txt")).read_text()
    assert picker.count("identifier: 'peer, circ'") == 1
    assert "DOC.browsingRoot Source: com.apple.FileProvider.LocalStorage, Title: 5f364710641a" in picker
assert "grantRevocation=NOT_TESTED" in (evidence / "ui/completed-phase.txt").read_text()
receipt = json.loads((ARCHIVE / "receipt.json").read_text())
assert receipt["status"] == "PASS" and receipt["qualificationGate"] == "PARTIAL"
assert receipt["sourcePin"] == report["declared_baseline_sha"] and receipt["runID"] == report["run_id"]
assert receipt["originalResultsSHA256"] == sha((evidence / "results.json").read_bytes())
assert receipt["postflightSHA256"] == sha((evidence / "independent-postflight.json").read_bytes())
assert receipt["byteChecks"] == postflight["hashes"]
assert receipt["observations"] == native["observations"]
assert receipt["counts"] == {key: summary[key] for key in ["totalTestCount", "passedTests", "failedTests", "skippedTests"]}
print(f"PASS: {len(ledger['artifacts'])} FINAL artifacts; seven XMLs/only-label deltas; two writes across restart; control/local/revision postflight; before/after installed identity; selected PASS with PARTIAL suite scope.")
