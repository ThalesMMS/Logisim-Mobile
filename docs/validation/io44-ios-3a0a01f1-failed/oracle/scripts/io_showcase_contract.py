"""Shared I/O fixture oracle and native XML postflight. No UI execution is inferred."""

from pathlib import Path
from copy import deepcopy
import argparse
import hashlib
import json
import math
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def load_io_contract(path=None):
    contract = json.loads((path or ROOT / "fixtures/user-scenarios/io-actions-v1.json").read_text())
    if contract.get("schemaVersion") != 1 or contract.get("scope") != "native-mounted-editor":
        raise ValueError("Unsupported I/O native scenario contract")
    if len(set(contract["componentTypes"].values())) != 12:
        raise ValueError("The scenario must cover the twelve I/O component types")
    for label, bits in contract["baselineOutputs"].items():
        if not label.startswith("OUT_") or re.fullmatch(r"[01]+", bits) is None:
            raise ValueError("Expected literal named binary probe values")
    for axis, expected_raw in contract["pointer"]["joystick"]["rawHeld"].items():
        fraction = contract["pointer"]["joystick"]["drag"][axis]
        raw = max(-14, min(14, math.floor((fraction - 0.5) * 28 + 0.5)))
        if raw != expected_raw:
            raise ValueError(f"Joystick {axis} gesture does not reach raw {expected_raw}: {raw}")
    return contract


def component_attributes(xml, label):
    project = ET.fromstring(xml)
    found = [node for node in project.findall("circuit/comp")
             if any(a.get("name") == "label" and a.get("val") == label for a in node.findall("a"))]
    if len(found) != 1:
        raise AssertionError(f"Expected one exported component labelled {label}, found {len(found)}")
    return found[0].get("name"), {a.get("name"): a.get("val") for a in found[0].findall("a")}


def _point(text):
    match = re.fullmatch(r"\(\s*(-?\d+),\s*(-?\d+)\s*\)", text or "")
    if match is None:
        raise AssertionError(f"Invalid circuit point {text!r}")
    return tuple(map(int, match.groups()))


def _project_parts(xml):
    project = ET.fromstring(xml)
    circuits = project.findall("circuit")
    assert len(circuits) == 1, "The I/O showcase must retain its single circuit"
    components = {}
    for node in circuits[0].findall("comp"):
        attrs = {a.get("name"): a.get("val") for a in node.findall("a")}
        assert len(attrs) == len(node.findall("a")), "Duplicate component attributes"
        label = attrs.get("label")
        assert label and label not in components, "Fixture labels must remain unique"
        components[label] = {"kind": node.get("name"), "at": _point(node.get("loc")), "attributes": attrs}
    wires = [(_point(node.get("from")), _point(node.get("to"))) for node in circuits[0].findall("wire")]
    for first, last in wires:
        assert first != last and (first[0] == last[0] or first[1] == last[1]), "Wires must remain nonzero and orthogonal"
    main = project.find("main")
    assert main is not None and main.get("name") == circuits[0].get("name"), "The main circuit must be preserved"
    return project, components, wires, circuits[0].get("name")


def _port_partition(wires, layouts):
    ports = {(label, port["name"]): tuple(port["at"]) for label, entries in layouts.items() for port in entries}
    points = set(ports.values()) | {point for wire in wires for point in wire}
    parents = {point: point for point in points}

    def root(point):
        while parents[point] != point:
            parents[point] = parents[parents[point]]
            point = parents[point]
        return point

    for first, last in wires:
        connected = [point for point in points if
                     (first[0] == last[0] == point[0] and min(first[1], last[1]) <= point[1] <= max(first[1], last[1])) or
                     (first[1] == last[1] == point[1] and min(first[0], last[0]) <= point[0] <= max(first[0], last[0]))]
        for point in connected:
            parents[root(point)] = root(first)
    groups = {}
    for key, point in ports.items():
        groups.setdefault(root(point), set()).add(key)
    return {frozenset(group) for group in groups.values()}


class IoShowcaseOracle:
    """Track intended attribute edits and preserve complete components and terminal connectivity."""
    def __init__(self, fixture_xml, targets, contract):
        _, self.components, self.wires, self.circuit = _project_parts(fixture_xml)
        self.layouts = {item["label"]: deepcopy(item["ports"]) for item in targets["targets"]}
        assert set(self.components) == set(self.layouts), "The target manifest must cover every fixture component"
        self.partition = _port_partition(self.wires, self.layouts)
        self.contract = contract
        self.overrides = {}

    def expect(self, changes):
        for label, attrs in changes.items():
            assert label in self.components, f"Unknown fixture component {label}"
            self.overrides.setdefault(label, {}).update(attrs)

    def assert_document(self, xml):
        project, observed, wires, circuit = _project_parts(xml)
        expected = deepcopy(self.components)
        for label, attrs in self.overrides.items():
            expected[label]["attributes"].update(attrs)
        assert circuit == self.circuit, "The I/O circuit name changed"
        assert observed == expected, "Components, positions or attributes differ from the intended I/O edits"
        for label, kind in self.contract["componentTypes"].items():
            assert observed[label]["kind"] == kind, f"{label} changed component type"
        layouts = deepcopy(self.layouts)
        for variant in self.contract["portVariants"]:
            attrs = expected[variant["label"]]["attributes"]
            if all(attrs.get(name) == value for name, value in variant["attributes"].items()):
                layouts[variant["label"]] = deepcopy(variant["ports"])
        retained = {(label, port["name"]) for label, ports in layouts.items() for port in ports}
        expected_partition = {frozenset(group & retained) for group in self.partition if group & retained}
        original_ports = set().union(*self.partition)
        expected_partition.update(frozenset([port]) for port in retained - original_ports)
        assert _port_partition(wires, layouts) == expected_partition, "I/O wires lost or changed terminal connectivity"
        return project


def assert_io_document(xml, contract, attributes=None):
    oracle = IoShowcaseOracle((ROOT / contract["fixture"]).read_bytes(),
                              json.loads((ROOT / "examples/io-showcase.targets.json").read_text()), contract)
    oracle.expect(attributes or {})
    return oracle.assert_document(xml)


def assert_probe_description(description, outputs):
    for label, bits in outputs.items():
        pattern = r"(?<![A-Za-z0-9_])" + re.escape(label) + r": " + re.escape(bits) + r"(?=,|\.|$)"
        assert re.search(pattern, description), f"Missing actual native probe {label}={bits}: {description}"
    return description


def pointer_screen_point(node_bounds, target, world):
    left, top, right, bottom = node_bounds
    x1, y1, x2, y2 = target["bounds"]
    assert right > left and bottom > top and x2 > x1 and y2 > y1
    assert x1 <= world[0] <= x2 and y1 <= world[1] <= y2, "Pointer point is outside its component"
    return round(left + (world[0] - x1) / (x2 - x1) * (right - left)), round(top + (world[1] - y1) / (y2 - y1) * (bottom - top))


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--document", required=True, type=Path)
    parser.add_argument("--document-attachment-name", help="Original .xcresult name if the extracted filename is a UUID")
    parser.add_argument("--contract", type=Path, default=ROOT / "fixtures/user-scenarios/io-actions-v1.json")
    parser.add_argument("--targets", type=Path, default=ROOT / "examples/io-showcase.targets.json")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(arguments)
    record = {"recordType": "io-document-postflight", "nativeExecution": "not-inferred", "status": "failed"}
    try:
        metadata_bytes = args.metadata.read_bytes()
        document_bytes = args.document.read_bytes()
        metadata = json.loads(metadata_bytes)
        assert metadata.get("schemaVersion") == 1 and metadata.get("scope") == "native-mounted-editor", "Invalid native checkpoint metadata"
        assert metadata.get("postflightRequired") is True, "The native checkpoint must require complete document validation"
        document_name = args.document_attachment_name or args.document.name
        assert metadata.get("document") == document_name, "The metadata and actual XML attachment names differ"
        contract = load_io_contract(args.contract)
        fixture = ROOT / contract["fixture"]
        targets_bytes = args.targets.read_bytes()
        oracle = IoShowcaseOracle(fixture.read_bytes(), json.loads(targets_bytes), contract)
        oracle.expect(metadata["expectedAttributes"])
        project = oracle.assert_document(document_bytes)
        record.update({"status": "passed", "expectedAttributes": metadata["expectedAttributes"],
                       "documentAttachmentName": document_name, "components": len(project.findall("circuit/comp")),
                       "wireSegments": len(project.findall("circuit/wire")),
                       "sha256": {"metadata": hashlib.sha256(metadata_bytes).hexdigest(),
                                  "document": hashlib.sha256(document_bytes).hexdigest(),
                                  "fixture": hashlib.sha256(fixture.read_bytes()).hexdigest(),
                                  "targets": hashlib.sha256(targets_bytes).hexdigest(),
                                  "contract": hashlib.sha256(args.contract.read_bytes()).hexdigest()}})
    except Exception as error:
        record.update({"error": str(error), "errorType": type(error).__name__})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": record["status"], "nativeExecution": record["nativeExecution"], "record": str(args.output)}))
    return 0 if record["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
