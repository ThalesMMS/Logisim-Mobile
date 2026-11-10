#!/usr/bin/env python3
"""Compare observed document exports with the shared native UI scenario contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "fixtures/user-scenarios/editor-actions-v1.json"
MAX_XML_BYTES = 64 * 1024 * 1024


class DocumentMismatch(AssertionError):
    def __init__(self, checkpoint, expected, observed):
        self.checkpoint = checkpoint
        self.expected = expected
        self.observed = observed
        super().__init__(f"Document differs from {checkpoint}: expected "
                         f"{json.dumps(expected, ensure_ascii=False, sort_keys=True)}, observed "
                         f"{json.dumps(observed, ensure_ascii=False, sort_keys=True)}")


def load_contract(path=DEFAULT_CONTRACT):
    contract = json.loads(Path(path).read_text(encoding="utf-8"))
    if contract.get("schemaVersion") != 1:
        raise ValueError("Unsupported user scenario contract version")
    if not isinstance(contract.get("snapshots"), dict) or not isinstance(contract.get("sap1"), dict):
        raise ValueError("The contract must define snapshots and SAP-1 expectations")
    return contract


def _point(value):
    match = re.fullmatch(r"\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)", value or "")
    if match is None:
        raise ValueError(f"Invalid circuit point: {value!r}")
    return [int(match.group(1)), int(match.group(2))]


def _sorted(values):
    return sorted(values, key=lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True))


def normalize_document(xml):
    """Retain exact component/attribute/wire multiplicities while ignoring XML element order."""
    content = xml.encode("utf-8") if isinstance(xml, str) else xml
    if not isinstance(content, bytes):
        raise TypeError("The observed document must be XML text or bytes")
    if len(content) > MAX_XML_BYTES:
        raise ValueError("The observed document exceeds 64 MiB")
    if b"<!DOCTYPE" in content.upper() or b"<!ENTITY" in content.upper():
        raise ValueError("Document type and entity declarations are not accepted")
    project = ET.fromstring(content)
    if project.tag != "project":
        raise ValueError("The observed export has no project root")
    circuits = project.findall("circuit")
    names = [circuit.get("name") for circuit in circuits]
    main = project.find("main")
    if main is None or main.get("name") not in names or len(set(names)) != len(names) or None in names:
        raise ValueError("The observed export has invalid or ambiguous circuit names")
    components = []
    wires = []
    for circuit in circuits:
        for component in circuit.findall("comp"):
            if component.get("name") is None:
                raise ValueError("A component has no serialized name")
            attributes = {}
            for attribute in component.findall("a"):
                key = attribute.get("name")
                if key is None or key in attributes:
                    raise ValueError("A component has missing or repeated attribute names")
                attributes[key] = attribute.get("val") if "val" in attribute.attrib else ET.tostring(attribute, encoding="unicode")
            components.append({"name": component.get("name"), "location": _point(component.get("loc")),
                               "attributes": attributes})
        for wire in circuit.findall("wire"):
            wires.append([_point(wire.get("from")), _point(wire.get("to"))])
    return {"circuitCount": len(circuits), "componentCount": len(components), "wireCount": len(wires),
            "components": _sorted(components), "wires": _sorted(wires)}


def assert_document(xml, contract, checkpoint):
    """Assert one complete UI export against a literal checkpoint, without inferring UI execution."""
    if checkpoint not in contract["snapshots"]:
        raise ValueError(f"Unknown document checkpoint: {checkpoint}")
    snapshot = contract["snapshots"][checkpoint]
    expected = {"circuitCount": snapshot["circuitCount"], "components": _sorted(snapshot["components"]),
                "wires": _sorted(snapshot["wires"])}
    normalized = normalize_document(xml)
    observed = {key: normalized[key] for key in expected}
    if observed != expected:
        raise DocumentMismatch(checkpoint, expected, observed)
    return normalized


def assert_sap1(xml, contract):
    """Assert exported SAP-1 structure counts; clock/display assertions belong to the native runner."""
    expected = {key: contract["sap1"][key] for key in ("circuitCount", "componentCount", "wireCount")}
    normalized = normalize_document(xml)
    observed = {key: normalized[key] for key in expected}
    if observed != expected:
        raise DocumentMismatch("sap1", expected, observed)
    return observed


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--document", required=True, type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--checkpoint")
    group.add_argument("--sap1", action="store_true")
    parser.add_argument("--output", required=True, type=Path, help="Retain the exact input file and assertion record here")
    args = parser.parse_args(arguments)
    args.output.mkdir(parents=True, exist_ok=True)
    record = {"recordType": "document-assertion", "nativeExecution": "not-inferred",
              "timestamp": datetime.now(timezone.utc).isoformat(),
              "contract": str(args.contract.resolve()), "input": str(args.document.resolve()),
              "checkpoint": "sap1" if args.sap1 else args.checkpoint}
    try:
        retained = args.output / "observed.circ"
        if args.document.resolve() != retained.resolve():
            shutil.copyfile(args.document, retained)
        content = retained.read_bytes()
        record.update({"observedFile": str(retained.resolve()), "inputBytes": len(content),
                       "inputSha256": hashlib.sha256(content).hexdigest(),
                       "contractSha256": hashlib.sha256(args.contract.read_bytes()).hexdigest()})
        contract = load_contract(args.contract)
        observed = assert_sap1(content, contract) if args.sap1 else assert_document(content, contract, args.checkpoint)
        record.update({"status": "passed", "observed": observed})
    except Exception as error:
        record.update({"status": "failed", "errorType": type(error).__name__, "error": str(error)})
        if isinstance(error, DocumentMismatch):
            record.update({"expected": error.expected, "observed": error.observed})
    (args.output / "assertion.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": record["status"], "record": str((args.output / "assertion.json").resolve())}))
    return 0 if record["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
