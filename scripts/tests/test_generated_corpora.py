"""Check actual frozen inputs against embedded data without a JVM or Gradle."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


examples = load("example_generator", "generate_examples.py")
temporal = load("temporal_generator", "generate-temporal-fixtures.py")


class GeneratedCorporaTest(unittest.TestCase):
    def test_example_catalog_matches_the_actual_xml_and_actions(self):
        self.assertEqual(examples.TARGET.read_text(), examples.generate(),
                         "Run python3 scripts/generate_examples.py after changing the example inputs")

    def test_temporal_catalog_matches_the_actual_circuits_operations_and_desktop_records(self):
        self.assertEqual(temporal.TARGET.read_text(), temporal.generated(),
                         "Run python3 scripts/generate-temporal-fixtures.py after reviewing temporal inputs")

    def test_changed_example_xml_is_not_hidden_by_an_unchanged_embedded_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            catalog_path = root / "examples/catalog.json"
            catalog_path.parent.mkdir()
            shutil.copyfile(ROOT / "examples/catalog.json", catalog_path)
            catalog = json.loads(catalog_path.read_text())
            for item in catalog["examples"]:
                target = root / item["path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / item["path"], target)
            with patch.object(examples, "ROOT", root):
                self.assertEqual(examples.TARGET.read_text(), examples.generate())
                fixture = root / catalog["examples"][0]["path"]
                fixture.write_text(fixture.read_text() + "\n<!-- changed fixture metadata -->\n")
                self.assertNotEqual(examples.TARGET.read_text(), examples.generate())

    def test_changed_temporal_operations_or_reference_are_not_hidden_by_embedded_data(self):
        with tempfile.TemporaryDirectory() as directory:
            fixtures = Path(directory) / "temporal"
            shutil.copytree(temporal.DIRECTORY, fixtures)
            with patch.object(temporal, "DIRECTORY", fixtures):
                self.assertEqual(temporal.TARGET.read_text(), temporal.generated())
                operation = next(fixtures.glob("*.ops.tsv"))
                original = operation.read_text()
                operation.write_text(original + "\nD\n")
                self.assertNotEqual(temporal.TARGET.read_text(), temporal.generated())
                operation.write_text(original)
                reference = next(fixtures.glob("*.desktop.tsv"))
                reference.write_text(reference.read_text() + "\nE\tnew\t1\tQ\t1\n")
                self.assertNotEqual(temporal.TARGET.read_text(), temporal.generated())


if __name__ == "__main__":
    unittest.main()
