"""Negative controls mutate raw meaning and refresh the checksum inventory."""

import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


BASE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("sap_receipt_verifier", BASE / "verifier.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def inventory(base):
    lines = [hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(base).as_posix()
             for path in sorted((base / "raw").rglob("*")) if path.is_file()]
    (base / "SHA256SUMS").write_text("\n".join(lines) + "\n")


class ReceiptControls(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        shutil.copytree(BASE / "raw", self.base / "raw")
        inventory(self.base)

    def mutate_json(self, name, change):
        path = self.base / "raw" / name
        value = json.loads(path.read_text())
        change(value)
        path.write_text(json.dumps(value, indent=2) + "\n")

    def rejected(self, message):
        inventory(self.base)
        with self.assertRaisesRegex(VERIFIER.VerificationError, message):
            VERIFIER.verify(self.base)

    def test_archived_receipt_passes(self):
        result = VERIFIER.verify(self.base)
        self.assertEqual(result["status"], "PASS")
        self.assertFalse(result["pausedCounterSeparatelyRecorded"])
        self.assertFalse(result["kernelStateRestorationQualified"])

    def test_failed_execution_is_rejected(self):
        self.mutate_json("results.json", lambda rows: rows[0].update(status="failed"))
        self.rejected("did not pass")

    def test_wrong_installed_apk_is_rejected(self):
        self.mutate_json("manifest.json", lambda value: value["installedArtifactsAfter"][0].update(sha256="0" * 64))
        self.rejected("installed APK hash")

    def test_short_pause_contract_is_rejected_with_refreshed_contract_hash(self):
        self.mutate_json("runtime-contract.json", lambda value: value["sap1"].update(pauseObservationMilliseconds=750))
        checksum = hashlib.sha256((self.base / "raw/runtime-contract.json").read_bytes()).hexdigest()
        self.mutate_json("manifest.json", lambda value: value.update(scenarioContractSha256=checksum))
        self.rejected("Pause contract")

    def test_wrong_manual_checkpoint_is_rejected(self):
        self.mutate_json("simulation-steps-56.json", lambda value: value["observations"][-1].update(count=55))
        self.rejected("does not reach")

    def test_wrong_display_nibble_is_rejected(self):
        path = self.base / "raw/sap1-at-half-cycle-56-ui.xml"
        source = path.read_text()
        self.assertIn("Output: 1000.", source)
        path.write_text(source.replace("Output: 1000.", "Output: 1110.", 1))
        self.rejected("display nibbles")

    def test_restart_document_difference_is_rejected(self):
        path = self.base / "raw/sequential-restored.circ"
        path.write_bytes(path.read_bytes() + b'\n')
        self.rejected("not byte-identical")

    def test_wrong_structure_is_rejected_even_when_both_documents_match(self):
        for name in ("sequential-sap1.circ", "sequential-restored.circ"):
            path = self.base / "raw" / name
            source = path.read_text()
            start = source.index("<wire ")
            end = source.index("/>", start) + 2
            path.write_text(source[:start] + source[end:])
        self.rejected("project structure")

    def test_hardcoded_pause_is_rejected_with_refreshed_source_hash(self):
        path = self.base / "raw/source-e2e-android.py"
        source = path.read_text()
        before = 'time.sleep(self.contract["sap1"]["pauseObservationMilliseconds"] / 1000)'
        self.assertIn(before, source)
        path.write_text(source.replace(before, "time.sleep(0.75)", 1))
        checksum = hashlib.sha256(path.read_bytes()).hexdigest()
        self.mutate_json("manifest.json", lambda value: value["scenarioSources"].update({"e2e-android.py": checksum}))
        self.rejected("pauseObservationMilliseconds")


if __name__ == "__main__":
    unittest.main()
