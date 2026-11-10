#!/usr/bin/env python3
"""Offline checks and rehashed negative controls for the retained SAP-1 receipt."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("sap1_receipt_verify", ROOT / "verify.py")
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class Sap1ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="sap1-receipt-test-")
        self.root = Path(self.directory.name) / "relocated"
        shutil.copytree(ROOT, self.root)
        self.addCleanup(self.directory.cleanup)

    def edit_json(self, relative, action):
        path = self.root / relative
        data = json.loads(path.read_text())
        action(data)
        path.write_text(json.dumps(data, indent=2) + "\n")
        if relative != "manifest.json":
            self.rehash(relative)

    def edit_text(self, relative, old, new):
        path = self.root / relative
        text = path.read_text()
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1))
        self.rehash(relative)

    def rehash(self, relative):
        data = (self.root / relative).read_bytes()
        def change(manifest):
            manifest["files"][relative]["bytes"] = len(data)
            manifest["files"][relative]["sha256"] = verifier.digest(data)
        self.edit_json("manifest.json", change)

    def receipt(self, case):
        return json.loads((self.root / case / "receipt.json").read_text())

    def rejected(self, expected):
        with self.assertRaisesRegex(verifier.VerificationError, expected):
            verifier.verify(self.root)

    def test_valid_archive(self):
        result = verifier.verify(self.root)
        self.assertEqual("PARTIAL", result["qualification_gate"])
        self.assertEqual({"sequential", "automatic"}, set(result["cases"]))
        self.assertEqual(57, result["cases"]["sequential"]["pausedAt"])
        self.assertEqual(1, result["cases"]["automatic"]["pausedAt"])
        self.assertEqual(2.57, result["cases"]["automatic"]["finalCounterQueryGapSeconds"])

    def test_relocated_cli_uses_only_retained_payload(self):
        process = subprocess.run([sys.executable, "-B", str(self.root / "verify.py"), "--root", str(self.root)],
                                 cwd=self.directory.name, capture_output=True, text=True)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual("PARTIAL", json.loads(process.stdout)["qualification_gate"])

    def test_original_absolute_paths_are_not_opened(self):
        def unreachable(manifest):
            for record in manifest["files"].values():
                record["origin"]["path"] = "/this/original/location/does/not/exist"
        self.edit_json("manifest.json", unreachable)
        self.assertEqual("PASS", verifier.verify(self.root)["status"])

    def test_corrupted_bytes_without_rehash_fail(self):
        with (self.root / "automatic/test.log").open("ab") as stream:
            stream.write(b"changed")
        self.rejected("Payload length")

    def test_missing_file_fails(self):
        (self.root / "sequential/installed-after.json").unlink()
        self.rejected("Missing or linked payload")

    def test_uninventoried_file_fails(self):
        (self.root / "unexpected.txt").write_text("extra")
        self.rejected("Uninventoried file")

    def test_escape_path_fails(self):
        self.edit_json("manifest.json", lambda value: value["files"].update({"../outside": {"bytes": 1, "sha256": "0" * 64}}))
        self.rejected("Unsafe payload path")

    def test_symlink_fails(self):
        path = self.root / "automatic/installed-after.json"
        path.unlink()
        path.symlink_to(self.root / "sequential/installed-after.json")
        self.rejected("Symlink in archive")

    def test_linked_archive_root_fails(self):
        alias = self.root.with_name("alias")
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(verifier.VerificationError, "Linked archive root"):
            verifier.verify(alias)

    def test_rehashed_partial_promotion_fails(self):
        self.edit_json("automatic/results.json", lambda value: value.update(qualification_gate="COMPLETE"))
        self.rejected("automatic results qualification_gate")

    def test_rehashed_extra_selection_fails(self):
        self.edit_json("sequential/results.json", lambda value: value["selected_tests"].append("EditorEndToEndTests/testAnotherProject"))
        self.rejected("sequential results selected_tests")

    def test_rehashed_physical_device_promotion_fails(self):
        self.edit_json("automatic/results.json", lambda value: value.update(physical_device=True))
        self.rejected("automatic results physical_device")

    def test_rehashed_installed_fingerprint_fails(self):
        self.edit_json("automatic/installed-after.json", lambda value: value["artifact"].update(bundle_tree_sha256="0" * 64))
        self.rejected("Installed after both cases fingerprint")

    def test_rehashed_contract_pause_change_fails(self):
        self.edit_json("shared/harness/editor-actions-v1.json", lambda value: value["sap1"].update(pauseObservationMilliseconds=0))
        self.rejected("Pinned executed contract hash")

    def test_rehashed_run_contract_reference_fails(self):
        self.edit_json("automatic/Run.json", lambda value: value.update(contract="different-resource"))
        self.edit_json("automatic/results.json", lambda value: value["configuration"].update(contract="different-resource"))
        self.rejected("Executed contract resource name")

    def test_rehashed_canvas_suffix_fails(self):
        path = self.receipt("sequential")["attachments"]["sap1-half-cycle-56-canvas"]
        self.edit_text(path, "Output: 0000, Output: 1000.", "Output: 0000, Output: 1110.")
        self.rejected("Canvas nibbles at 56")

    def test_rehashed_display_actual_fails_with_expected_list_untouched(self):
        path = self.receipt("sequential")["attachments"]["sap1-display-8"]
        self.edit_text(path, "value=Hex digit display. Digit 8;", "value=Hex digit display. Digit E;")
        self.rejected("Real display identity/state 8")

    def test_rehashed_duplicate_display_identity_fails(self):
        path = self.receipt("sequential")["attachments"]["sap1-display-8"]
        self.edit_text(path, "identifier=canvas-component:import-105", "identifier=canvas-component:import-104")
        self.rejected("Real display identity/state 8")

    def test_rehashed_run_observation_fails(self):
        path = self.receipt("automatic")["attachments"]["sap1-run-pause-observation"]
        self.edit_text(path, "startedAt=0, pausedAt=1", "startedAt=0, pausedAt=0")
        self.rejected("Exact run/pause counter observation")

    def test_rehashed_automatic_sequential_claim_fails(self):
        self.edit_json("automatic/receipt.json", lambda value: value["observations"].update(manualHalfCycles=56, canvasOutputSuffix=["0000", "1000"]))
        self.rejected("Literal observations and scope")

    def test_rehashed_native_duration_fails(self):
        self.edit_text("automatic/test.log", "passed (28.827 seconds).", "passed (2.827 seconds).")
        self.rejected("One native test and literal duration")

    def test_rehashed_shortened_observation_interval_fails(self):
        self.edit_text("automatic/test.log", "t =    27.07s Get all elements", "t =    25.07s Get all elements")
        self.rejected("Final counter queries separated")

    def test_rehashed_fixture_change_fails(self):
        path = "shared/app-source/examples/sap1.circ"
        self.edit_text(path, '<main name="main"/>', '<main name="changed"/>')
        self.rejected("Pinned producer source bundle")

    def test_distinct_source_pin_roles_cannot_be_merged(self):
        self.edit_json("manifest.json", lambda value: value["pins"].update(uiHarness=value["pins"]["appTestValidation"]))
        self.rejected("Distinct producer/validation/harness pins")


if __name__ == "__main__":
    unittest.main()
