import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "generate-legal-notices.py"
SPEC = importlib.util.spec_from_file_location("legal_inventory", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LegalInventoryTest(unittest.TestCase):
    def test_license_is_inherited_from_the_exact_maven_parent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / "example" / "parent" / "2" / "hash" / "parent-2.pom"
            child = root / "example" / "child" / "1" / "hash" / "child-1.pom"
            parent.parent.mkdir(parents=True)
            child.parent.mkdir(parents=True)
            parent.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license>'
                              '<name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url>'
                              '</license></licenses><url>https://example.test/project</url></project>')
            child.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><parent><groupId>example</groupId>'
                             '<artifactId>parent</artifactId><version>2</version></parent></project>')
            licenses, url = MODULE.pom_metadata(child)
            self.assertEqual([{"name": "Apache-2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0"}], licenses)
            self.assertEqual("https://example.test/project", url)

    def test_generated_notices_match_checked_in_sources(self):
        for path, expected in MODULE.outputs().items():
            self.assertEqual(expected, path.read_text(), str(path))


if __name__ == "__main__":
    unittest.main()
