#!/usr/bin/env python3
"""Offline relocation and rehashed negative controls for AD28 discovery evidence."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parent
CLI = "verify-archived-evidence.py"
SPEC = importlib.util.spec_from_file_location("ad28_discovery_verify", ROOT / CLI)
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


class DiscoveryReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="provider-discovery-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "relocated"
        shutil.copytree(ROOT, self.root)

    def edit_json(self, relative, action):
        target = self.root / relative
        data = json.loads(target.read_text())
        action(data)
        target.write_text(json.dumps(data, indent=2) + "\n")
        if relative != "inventory.json":
            self.rehash(relative)

    def rehash(self, relative):
        data = (self.root / relative).read_bytes()
        self.edit_json("inventory.json", lambda value: value["files"][relative].update(bytes=len(data), sha256=verifier.digest(data)))

    def stream_json(self, key, action):
        inventory = json.loads((self.root / "inventory.json").read_text())
        relative = inventory["streamFiles"][key]["path"]
        self.edit_json(relative, action)
        self.sync_stream(key)

    def sync_stream(self, key):
        inventory = json.loads((self.root / "inventory.json").read_text())
        entry = inventory["streamFiles"][key]
        data = (self.root / entry["path"]).read_bytes()
        self.assertEqual("identity", entry["encoding"])
        entry.update(rawBytes=len(data), rawSHA256=verifier.digest(data))
        inventory["files"][entry["path"]].update(bytes=len(data), sha256=verifier.digest(data))
        commands_path = self.root / inventory["commands"]
        commands = json.loads(commands_path.read_text())
        name, channel = key.rsplit(".", 1)
        command = next(value for value in commands if value["name"] == name)
        command[channel + "Bytes"] = len(data)
        command[channel + "SHA256"] = verifier.digest(data)
        commands_path.write_text(json.dumps(commands, indent=2) + "\n")
        raw_commands = commands_path.read_bytes()
        inventory["files"][inventory["commands"]].update(bytes=len(raw_commands), sha256=verifier.digest(raw_commands))
        (self.root / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")

    def rejected(self, message):
        with self.assertRaisesRegex(verifier.VerificationError, message):
            verifier.verify(self.root)

    def test_baseline_preserves_failure_and_unknown_cause(self):
        result = verifier.verify(self.root)
        self.assertEqual("PASS", result["status"])
        self.assertEqual("DIAGNOSTIC_ONLY", result["discoveryStatus"])
        self.assertEqual("FAIL", result["originalNativeStatus"])
        self.assertEqual(17.003, result["originalCaseSeconds"])
        self.assertEqual(65, result["originalExitCode"])
        self.assertEqual("NOT_ESTABLISHED", result["causalAttribution"])
        self.assertFalse(result["mainQualified"])
        self.assertFalse(result["newExperimentExecutedByCensus"])

    def test_relocated_cli_without_original_paths(self):
        process = subprocess.run([sys.executable, "-B", str(self.root / CLI), "--root", str(self.root)],
                                 cwd=self.temp.name, capture_output=True, text=True)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual({"nativeReadOnly": 13, "pythonReads": 3}, json.loads(process.stdout)["commands"])

    def test_import_does_not_verify_or_read_payload(self):
        spec = importlib.util.spec_from_file_location("ad28_discovery_import_only", self.root / CLI)
        module = importlib.util.module_from_spec(spec)
        output = io.StringIO()
        with mock.patch.object(Path, "read_bytes", side_effect=AssertionError("Payload read during import")), contextlib.redirect_stdout(output):
            spec.loader.exec_module(module)
        self.assertEqual("", output.getvalue())

    def test_origin_paths_are_metadata_only(self):
        def change(value):
            for record in value["files"].values():
                record["origin"]["path"] = "/unavailable/original/retained-only"
        self.edit_json("inventory.json", change)
        self.assertEqual("PASS", verifier.verify(self.root)["status"])

    def test_corrupt_payload_without_rehash_fails(self):
        with (self.root / "provider-registry-match-id.stdout").open("ab") as stream:
            stream.write(b"changed")
        self.rejected("Payload length")

    def test_unsafe_inventory_path_fails(self):
        self.edit_json("inventory.json", lambda value: value["files"].update({"../escape": {"bytes": 1, "sha256": "0" * 64}}))
        self.rejected("Unsafe payload path")

    def test_symlink_fails(self):
        target = self.root / "installed-paths.json"
        target.unlink()
        target.symlink_to(self.root / "commands.json")
        self.rejected("Symlink in retained archive")

    def test_linked_root_fails(self):
        alias = self.root.with_name("alias")
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(verifier.VerificationError, "Linked archive root"):
            verifier.verify(alias)

    def test_rehashed_command_registration_flag_fails(self):
        def change(commands):
            command = next(value for value in commands if value["name"] == "provider-registry-match-id")
            command["argv"][5] = "-a"
        self.edit_json("commands.json", change)
        self.rejected("Read-only native argv whitelist")

    def test_rehashed_python_write_script_fails(self):
        def change(commands):
            command = next(value for value in commands if value["name"] == "exact-appgroup-current-metadata")
            command["argv"][2] += "\nPath('/tmp/forbidden').write_text('changed')\n"
        self.edit_json("commands.json", change)
        self.rejected("Read-only Python script whitelist")

    def test_missing_stderr_stream_fails(self):
        self.edit_json("inventory.json", lambda value: value["streamFiles"].pop("device-log-show-help.stderr"))
        self.rejected("Every command stdout/stderr")

    def test_rehashed_help_failure_cannot_be_promoted(self):
        self.edit_json("commands.json", lambda values: next(value for value in values if value["name"] == "device-log-show-help").update(exit_code=0))
        self.rejected("Retained command exit code")

    def test_rehashed_gzip_mtime_fails(self):
        relative = "historical-registration-and-lookup-exact-ids.stdout.gz"
        data = bytearray((self.root / relative).read_bytes())
        data[4:8] = b"\x01\x00\x00\x00"
        (self.root / relative).write_bytes(data)
        self.rehash(relative)
        self.rejected("gzip header has mtime 0")

    def test_rehashed_selected_raw_index_mismatch_fails(self):
        self.edit_json("historical-registry-relevant-events.json", lambda values: values[8].update(index=759))
        self.rejected("Selected events equal their exact raw indices")

    def test_rehashed_selected_timestamp_mismatch_fails(self):
        self.edit_json("historical-registry-relevant-events.json", lambda values: values[8].update(timestamp="2026-11-05 06:58:30.000000+0000"))
        self.rejected("Selected events equal their exact raw indices")

    def test_group_directory_and_metadata_uuids_are_not_interchangeable(self):
        self.edit_json("inventory.json", lambda value: value["group"].update(directoryUUID=verifier.METADATA_UUID))
        self.rejected("Exact group directoryUUID")

    def test_rehashed_group_query_cannot_claim_current_absence(self):
        self.stream_json("exact-appgroup-current-metadata.stdout", lambda value: value.update(exists=False))
        self.rejected("As-of-query group existence")

    def test_rehashed_group_metadata_creator_change_fails(self):
        self.stream_json("exact-appgroup-current-metadata.stdout", lambda value: value["entries"][0]["metadata"].update(MCMMetadataCreator="another.app"))
        self.rejected("Query/raw plist agreement")

    def test_rehashed_root_birthtime_contradicting_chronology_fails(self):
        self.stream_json("exact-appgroup-current-metadata.stdout",
                         lambda value: value["rootAttributes"].update(birthtimeUTC="2026-11-05T06:00:00+00:00"))
        self.rejected("Retained group root birthtime")

    def test_rehashed_verbose_parent_bundle_fails(self):
        relative = "provider-registry-match-verbose3.stdout"
        target = self.root / relative
        text = target.read_text()
        self.assertIn("Parent Bundle = " + verifier.INSTALLED_APP, text)
        target.write_text(text.replace("Parent Bundle = " + verifier.INSTALLED_APP, "Parent Bundle = /tmp/Wrong.app", 1))
        self.rehash(relative)
        self.sync_stream(relative)
        self.rejected("Unique exact registry verbose field: Parent Bundle")

    def test_rehashed_state_cannot_include_followup_002(self):
        self.edit_json("appgroup-fixture-state.json", lambda value: value["events"].append({"sequence": 4, "kind": "prepare", "timestamp": "2026-11-05T08:00:00.000Z"}))
        self.rejected("Retained 001 state, excluding followup 002")

    def test_rehashed_backend_summary_fails(self):
        self.stream_json("exact-appgroup-existing-fixture-state.stdout", lambda value: value["decoded552ByteLeaves"][0].update(sha256="0" * 64))
        self.rejected("State query decoded backend")

    def test_rehashed_literal_changed_even_with_artifact_rehash_fails(self):
        source = self.root / "source-FixtureStore.swift"
        text = source.read_text()
        source.write_text(text.replace('val="REVOCATION_INPUT"', 'val="REVOCATION_OTHER"', 1))
        self.rehash(source.name)
        self.edit_json("fixture-producer-artifact.json", lambda value: value["sourceFiles"].update({"Shared/FixtureStore.swift": verifier.digest(source.read_bytes())}))
        self.rejected("Producer literal is the retained 552-byte backend")

    def test_rehashed_source_group_changed_even_with_artifact_rehash_fails(self):
        source = self.root / "source-FixtureStore.swift"
        text = source.read_text()
        old = 'static let appGroupIdentifier = "group.thalesmms.logisim.validation.revocationfixture"'
        self.assertIn(old, text)
        source.write_text(text.replace(old, 'static let appGroupIdentifier = "group.changed.other"', 1))
        self.rehash(source.name)
        self.edit_json("fixture-producer-artifact.json", lambda value: value["sourceFiles"].update({"Shared/FixtureStore.swift": verifier.digest(source.read_bytes())}))
        self.rejected("Producer source App Group constant")

    def test_rehashed_failure_receipt_cannot_be_promoted(self):
        self.edit_json("original-failure-receipt.json", lambda value: value.update(status="PASS"))
        self.rejected("Failure receipt remains FAIL")

    def test_rehashed_original_error_chain_cannot_be_dropped(self):
        self.edit_json("original-failure-receipt.json", lambda value: value["publicObservations"]["errorDiagnostic"]["chain"].pop())
        self.rejected("Original error chain agreement")

    def test_rehashed_causal_or_main_claim_is_rejected(self):
        self.edit_json("inventory.json", lambda value: value["limits"].update(permissionOrSigningCauseEstablished=True, mainQualified=True))
        self.rejected("No unsupported causal or qualification claim")


if __name__ == "__main__":
    unittest.main()
