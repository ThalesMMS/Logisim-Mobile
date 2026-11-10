#!/usr/bin/env python3
"""Probe actual private readiness using the literal R3 tree, without a device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

BASE = "91bc58e38e0171777df5951b018a23ac6da8da69"
EDITOR = "scripts/ios_e2e_ui/EditorEndToEndTests.swift"
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
LITERALS = {
    "r3-tree.txt": "c2cb79f20864a7dc835a1d7f92dc34d9a7e16037494ce6dc95b573aec10b15aa",
    "r3-observation.txt": "d56c61dc955dcd0a39e4bd21435c5f14a5059170c3bbded6fc5420380b181aa8",
    "canonical-labels.json": "e169da87187f243cc9d20442f3e33b95301aeedaae7da099ff76584f18a813d9",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    paths = [ROOT / EDITOR, Path(__file__).resolve(), HERE / "r3-tree.txt",
             HERE / "r3-observation.txt", HERE / "canonical-labels.json"]
    before = {str(path): path.read_bytes() for path in paths}
    for name, expected in LITERALS.items():
        assert hashlib.sha256(before[str(HERE / name)]).hexdigest() == expected, name
    source = before[str(ROOT / EDITOR)].decode()
    baseline = subprocess.check_output(["git", "show", f"{BASE}:{EDITOR}"], cwd=ROOT).decode()
    start = source.index("    private func explorerHeaderReadiness(")
    end = source.index("    private struct ExplorerHeaderObservation", start)
    function = source[start:end]
    old_query = baseline[baseline.index("            let controls: [XCUIElement]"):baseline.index("            let frame = control?.frame")]
    new_query = source[source.index("            let controls: [XCUIElement]"):source.index("            let frame = control?.frame")]
    assert new_query == old_query, "Query, native roles or ambiguity guard changed"
    prefix = "    private func explorerSearch(_ query: String)"
    suffix = "    private func closeExplorer()"
    assert source[:source.index(prefix)] == baseline[:baseline.index(prefix)]
    assert source[source.index(suffix):] == baseline[baseline.index(suffix):]

    tree = before[str(HERE / "r3-tree.txt")].decode()
    candidates = [line for line in tree.splitlines() if "identifier: 'a11y-circuit-search'" in line]
    assert len(candidates) == 1 and "TextView," in candidates[0]
    number = r"(-?[0-9]+(?:\.[0-9]+)?)"
    frame_pattern = r"\{\{" + number + r", " + number + r"\}, \{" + number + r", " + number + r"\}\}"
    frame = tuple(map(float, re.search(frame_pattern, candidates[0]).groups()))
    assert frame == (24, 158, 342, 24) and "label:" not in candidates[0]
    observation = before[str(HERE / "r3-observation.txt")].decode()
    viewport = tuple(map(float, re.search(r"viewport=\(" + number + r", " + number + r", " + number + r", " + number + r"\)", observation).groups()))
    assert viewport == (24, 182, 342, 604)
    assert "count=1;" in observation and "label=; enabled=true;" in observation
    labels = json.loads(before[str(HERE / "canonical-labels.json")])["a11y_explorer_search"]
    assert labels == {"en": "Find a component, terminal, or identifier",
                      "pt": "Buscar componente, terminal ou identificação"}
    duplicate_inventory = candidates + candidates
    assert len(duplicate_inventory) == 2
    # This inventories a literal duplicate. The byte comparison above preserves
    # the actual XCUI query guard; no host model claims to execute that query.
    assert "guard controls.count <= 1 else" in new_query

    literal = lambda value: json.dumps(value, ensure_ascii=False)
    probe = "import Foundation\nimport CoreGraphics\n" + function.replace("    private func", "    func", 1)
    probe += f"""
let viewport = CGRect(x: {viewport[0]}, y: {viewport[1]}, width: {viewport[2]}, height: {viewport[3]})
let clipped = CGRect(x: {frame[0]}, y: {frame[1]}, width: {frame[2]}, height: {frame[3]})
let visible = CGRect(x: 24, y: 262, width: 342, height: 88)
let labels = [{literal(labels['en'])}, {literal(labels['pt'])}]
var checks = 0
func expect(_ name: String, _ frame: CGRect, _ label: String, _ enabled: Bool,
            _ expected: (Bool, Bool)?) {{
    let result = explorerHeaderReadiness(frame: frame, viewport: viewport,
                                        canonical: labels.contains(label), enabled: enabled)
    if let expected {{
        precondition(result?.fullyVisible == expected.0 && result?.canSkipSelected == expected.1, name)
    }} else {{ precondition(result == nil, name) }}
    checks += 1
}}
expect("literal R3 clipped Search only prepares", clipped, "", true, (false, false))
expect("revealed canonical Search at 48 points admits", CGRect(x:24,y:262,width:342,height:48), labels[0], true, (true, true))
expect("visible empty label rejects", visible, "", true, nil)
expect("visible wrong label rejects", visible, "wrong label", true, nil)
expect("visible undersized canonical field rejects", CGRect(x:24,y:262,width:342,height:24), labels[0], true, nil)
expect("disabled clipped target rejects", clipped, "", false, nil)
expect("NaN origin rejects", CGRect(x:CGFloat.nan,y:158,width:342,height:24), "", true, nil)
expect("infinite width rejects", CGRect(x:24,y:158,width:CGFloat.infinity,height:24), "", true, nil)
expect("negative raw width rejects", CGRect(x:24,y:158,width:-342,height:24), "", true, nil)
expect("zero height rejects", CGRect(x:24,y:158,width:342,height:0), "", true, nil)
expect("canonical offscreen selection eligibility preserved", CGRect(x:24,y:120,width:135,height:48), labels[0], true, (false, true))
expect("offscreen wrong label cannot skip", CGRect(x:24,y:120,width:342,height:48), "wrong label", true, (false, false))
expect("offscreen undersized canonical target cannot skip", clipped, labels[0], true, (false, false))
expect("partial clipped target only prepares", CGRect(x:24,y:170,width:342,height:24), "", true, (false, false))
expect("minimum full target admits", CGRect(x:24,y:262,width:48,height:48), labels[0], true, (true, true))
expect("contained narrow target rejects", CGRect(x:24,y:262,width:44,height:48), labels[0], true, nil)
expect("disabled visible target rejects", visible, labels[0], false, nil)
print("\\(checks) readiness cases passed; no native execution")
"""
    probe_path = output / "readiness-probe.swift"
    probe_path.write_text(probe)
    probe_bytes = probe_path.read_bytes()
    with (output / "readiness.log").open("wb") as log:
        result = subprocess.run([sys.executable, "/tmp/logisim-gradle-serial.py", "xcrun", "swift", str(probe_path)],
                                cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    assert all(Path(path).read_bytes() == data for path, data in before.items()), "Probe input changed"
    assert probe_path.read_bytes() == probe_bytes, "Generated probe changed"
    if result.returncode == 0:
        assert (output / "readiness.log").read_text() == "17 readiness cases passed; no native execution\n"
    receipt = {"status": "PASS" if result.returncode == 0 else "FAIL", "nativeStatus": "NOT_RUN",
               "baseOriginal": BASE, "readinessCases": 17, "probeExitCode": result.returncode,
               "queryAndAmbiguityGuardByteIdentical": True, "duplicateLiteralInventoryCount": 2,
               "duplicateXCUIQueryExecuted": False, "outsidePrivateHelperBodiesByteIdentical": True,
               "inputsByteIdenticalBeforeAfter": True,
               "probeSHA256": hashlib.sha256(probe_bytes).hexdigest(),
               "inputSHA256": {str(Path(path).relative_to(ROOT)): hashlib.sha256(data).hexdigest() for path, data in before.items()}}
    (output / "readiness-validation.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({key: receipt[key] for key in ("status", "readinessCases", "nativeStatus")}))
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
