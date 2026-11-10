"""Exercise the source proof against changed runtime bytes, without building the fixture."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("modify_proof", HERE / "run-modify-diagnostic-checks.py")
PROOF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROOF)


class SourceProofTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="logisim-modify-proof-")
        self.addCleanup(self.temporary.cleanup)
        self.fixture = Path(self.temporary.name) / "fixture"
        shutil.copytree(HERE, self.fixture, ignore=shutil.ignore_patterns("__pycache__"))
        self.provider = self.fixture / "Provider/RevocationFileProvider.swift"

    def verify(self):
        with patch.object(PROOF, "ROOT", self.fixture):
            return PROOF.verify_source()

    def test_original_bytes_pass(self):
        self.assertTrue(self.verify()["mutationBodyByteIdentical"])

    def test_crlf_mutation_body_is_rejected(self):
        data = self.provider.read_bytes()
        marker = b"{ beginCommit in\n"
        start = data.index(marker) + len(marker)
        end = data.index(b"\n    func deleteItem(", start)
        body = data[start:end].replace(b"\n", b"\r\n")
        self.provider.write_bytes(data[:start] + body + data[end:])
        with self.assertRaisesRegex(AssertionError, "Mutation body changed"):
            self.verify()

    def test_changed_content_guard_is_rejected(self):
        data = self.provider.read_bytes().replace(b"guard changedFields.contains(.contents), let newContents else",
                                                 b"guard let newContents else", 1)
        self.provider.write_bytes(data)
        with self.assertRaisesRegex(AssertionError, "Mutation body changed"):
            self.verify()

    def test_changed_store_is_rejected(self):
        store = self.fixture / "Shared/FixtureStore.swift"
        store.write_bytes(store.read_bytes() + b"\n")
        with self.assertRaisesRegex(AssertionError, "Shared/FixtureStore.swift"):
            self.verify()

    def test_changed_policy_is_rejected(self):
        item = self.fixture / "Provider/RevocationProviderItem.swift"
        item.write_bytes(item.read_bytes().replace(b".downloadLazilyAndEvictOnRemoteUpdate", b".inherited"))
        with self.assertRaisesRegex(AssertionError, "Provider/RevocationProviderItem.swift"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
