#!/usr/bin/env python3
"""Check native editor harness selectors against the canonical localization catalog."""

import json
import os
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "scripts/ios_e2e_ui"
CATALOG_ROOT = Path(os.environ.get("LOGISIM_TEST_CATALOG_ROOT", ROOT)).resolve()


class IosEditorSelectorTests(unittest.TestCase):
    def test_every_literal_editor_selector_has_a_canonical_catalog_entry(self):
        catalog = {}
        for path in sorted((CATALOG_ROOT / "localization/messages").glob("*.json")):
            catalog.update(json.loads(path.read_text()))
        names = re.findall(r'^\s+- ([A-Za-z0-9_]+\.swift)\s*$', (SOURCE_DIR / "project.yml").read_text(), re.MULTILINE)
        sources = []
        for name in names:
            path = SOURCE_DIR / name
            if not path.exists():
                path = ROOT / "scripts/ios_native_ui" / name
            sources.append(path.read_text())
        source = "\n".join(sources)
        keys = set(re.findall(r'\b(?:values|labels|localizedValues|translations|click|editableField|tapKey|namedField)\(\s*"([a-z][a-z0-9_]*)"\s*[,)]', source))
        keys.update(re.findall(r'\bnestedKey:\s*"([a-z][a-z0-9_]*)"', source))
        # The named insertion fields are iterated as (key, value) pairs.
        keys.update(re.findall(r'\("(a11y_explorer_insert_(?:kind|x|y))"\s*,', source))
        contract = json.loads((ROOT / "fixtures/user-scenarios/editor-actions-v1.json").read_text())
        for scenario in contract["scenarios"]:
            for step in scenario["steps"]:
                if step["action"] == "insertComponent":
                    keys.add("component_" + step["kind"])
                if step["action"] == "setFacing":
                    keys.add("direction_" + step["direction"])
        self.assertGreater(len(keys), 30, "The check must cover the actual harness selector set")
        self.assertEqual([], sorted(keys - catalog.keys()))
        for key in keys:
            with self.subTest(key=key):
                self.assertIsInstance(catalog[key]["en"], str)
                self.assertIsInstance(catalog[key]["pt"], str)


if __name__ == "__main__":
    unittest.main()
