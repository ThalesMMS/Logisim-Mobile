#!/usr/bin/env python3
"""Pure document assertion checks; these do not execute either mobile application."""

import contextlib
import io
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from user_scenario_contract import DocumentMismatch, assert_document, assert_sap1, load_contract, main, normalize_document

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "fixtures/user-scenarios/editing-not.circ"


class UserScenarioContractTests(unittest.TestCase):
    def setUp(self):
        self.contract = load_contract()
        self.xml = FIXTURE.read_bytes()

    def test_literal_fixture_matches_the_shared_wired_checkpoint(self):
        observed = assert_document(self.xml, self.contract, "wired")
        self.assertEqual(1, observed["circuitCount"])
        self.assertEqual(3, observed["componentCount"])
        self.assertEqual(2, observed["wireCount"])

    def test_xml_formatting_and_component_order_do_not_change_the_assertion(self):
        project = ET.fromstring(self.xml)
        circuit = project.find("circuit")
        circuit[:] = reversed(list(circuit))
        assert_document(ET.tostring(project), self.contract, "wired")

    def test_same_counts_with_a_wrong_component_location_fail(self):
        changed = self.xml.replace(b'loc="(200,100)"', b'loc="(210,100)"')
        with self.assertRaises(DocumentMismatch) as failed:
            assert_document(changed, self.contract, "wired")
        self.assertEqual("wired", failed.exception.checkpoint)
        self.assertNotEqual(failed.exception.expected, failed.exception.observed)

    def test_same_counts_with_a_wrong_attribute_fail(self):
        changed = self.xml.replace(b'name="label" val="E2E_OUT"', b'name="label" val="wrong"')
        with self.assertRaises(DocumentMismatch):
            assert_document(changed, self.contract, "wired")

    def test_wire_direction_and_endpoint_changes_fail(self):
        for changed in (self.xml.replace(b'from="(100,100)" to="(170,100)"', b'from="(170,100)" to="(100,100)"'),
                        self.xml.replace(b'to="(170,100)"', b'to="(160,100)"')):
            with self.assertRaises(DocumentMismatch):
                assert_document(changed, self.contract, "wired")

    def test_duplicate_components_are_not_collapsed(self):
        project = ET.fromstring(self.xml)
        circuit = project.find("circuit")
        circuit.append(ET.fromstring(ET.tostring(circuit.find("comp"))))
        self.assertEqual(4, normalize_document(ET.tostring(project))["componentCount"])
        with self.assertRaises(DocumentMismatch):
            assert_document(ET.tostring(project), self.contract, "wired")

    def test_actual_sap1_example_has_the_literal_project_counts(self):
        observed = assert_sap1((ROOT / "examples/sap1.circ").read_bytes(), self.contract)
        self.assertEqual({"circuitCount": 7, "componentCount": 156, "wireCount": 318}, observed)
        self.assertEqual(["0000", "1000"], self.contract["sap1"]["displayNibbles"])
        self.assertEqual(56, self.contract["sap1"]["manualHalfCycles"])

    def test_unknown_checkpoints_and_invalid_xml_fail(self):
        with self.assertRaisesRegex(ValueError, "Unknown document checkpoint"):
            assert_document(self.xml, self.contract, "missing")
        with self.assertRaises(ET.ParseError):
            normalize_document(b"<project>")
        with self.assertRaisesRegex(ValueError, "invalid or ambiguous circuit names"):
            normalize_document(b'<project><circuit name="main"/></project>')
        with self.assertRaisesRegex(ValueError, "entity declarations"):
            normalize_document(b'<!DOCTYPE project [<!ENTITY x "expanded">]><project/>')

    def test_failed_cli_assertion_retains_the_exact_bytes_and_failure_record(self):
        payload = b"<project>\xffbroken"
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "invalid.circ"
            output = Path(directory) / "evidence"
            source.write_bytes(payload)
            with contextlib.redirect_stdout(io.StringIO()):
                status = main(["--document", str(source), "--checkpoint", "wired", "--output", str(output)])
            self.assertEqual(1, status)
            self.assertEqual(payload, (output / "observed.circ").read_bytes())
            record = json.loads((output / "assertion.json").read_text())
            self.assertEqual("failed", record["status"])
            self.assertEqual("not-inferred", record["nativeExecution"])
            self.assertEqual(len(payload), record["inputBytes"])


if __name__ == "__main__":
    unittest.main()
