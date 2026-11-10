#!/usr/bin/env python3
"""Check the literal Inspector input and unique staging without native tools."""

import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location("ios_inspector_runner", Path(__file__).with_name("run-ios-e2e.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class InspectorFixtureTests(unittest.TestCase):
    def test_literal_input_is_one_width_one_input_pin(self):
        root = ET.fromstring(runner.INSPECTOR_FIXTURE.read_bytes())
        self.assertEqual("inspector21", root.find("main").get("name"))
        self.assertEqual(["inspector21"], [item.get("name") for item in root.findall("circuit")])
        self.assertEqual(1, len(root.findall("circuit/comp")))
        self.assertEqual([], root.findall("circuit/wire"))
        pin = root.find("circuit/comp")
        self.assertEqual({"lib": "0", "loc": "(100,100)", "name": "Pin"}, pin.attrib)
        self.assertEqual({"type": "input", "width": "1", "label": "INSPECTOR_WIDTH_INPUT"},
                         {item.get("name"): item.get("val") for item in pin.findall("a")})

    def test_staging_changes_only_circuit_name_and_label_and_preserves_source_bytes(self):
        original = runner.INSPECTOR_FIXTURE.read_bytes()
        staged = []
        for suffix in ("first", "second"):
            title = "IOS_E2E_" + suffix + "_inspector"
            label = "INSPECTOR_" + suffix + "_<&>"
            data = runner.inspector_fixture_xml(title, label)
            root = ET.fromstring(data)
            self.assertEqual(title, root.find("main").get("name"))
            self.assertEqual([title], [item.get("name") for item in root.findall("circuit")])
            root.find("main").set("name", "inspector21")
            root.find("circuit").set("name", "inspector21")
            self.assertEqual(label, root.find("circuit/comp/a[@name='label']").get("val"))
            root.find("circuit/comp/a[@name='label']").set("val", "INSPECTOR_WIDTH_INPUT")
            self.assertEqual(ET.tostring(ET.fromstring(original)), ET.tostring(root))
            staged.append(data)
        self.assertNotEqual(*staged)
        self.assertEqual(original, runner.INSPECTOR_FIXTURE.read_bytes())


if __name__ == "__main__":
    unittest.main()
