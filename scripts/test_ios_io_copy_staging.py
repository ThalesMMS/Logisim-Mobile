#!/usr/bin/env python3
"""Check I/O Files Copy payloads and runner resources without native tools."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("ios_io_copy_runner", Path(__file__).with_name("run-ios-e2e.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
RUN_ID = "0123456789ab"
SIMULATOR = "B25F12A5-62D8-4E9E-964E-96519DB6FB69"
SELECTION = "EditorEndToEndTests/IoEndToEndTests/testIoKeyboardTtyAndCapacity"
FIXTURE_SHA256 = "a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9"


class IoCopySourceTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.primary = self.root / "primary"
        self.primary.mkdir()
        self.documents = self.root / "receiver/Documents/LogisimE2E" / RUN_ID
        self.documents.mkdir(parents=True)
        self.peer = {"bundleID": runner.CLIPBOARD_RECEIVER_BUNDLE_ID,
                     "displayName": "Clipboard Receiver", "documents": str(self.documents)}

    def test_stages_the_exact_approved_bytes_and_declares_the_files_route(self):
        configuration = runner.stage_io_copy_source(self.peer, self.primary, RUN_ID)
        expected = self.documents / "io-showcase-source.circ"
        self.assertEqual({"route": "files-copy", "peerDisplayName": "Clipboard Receiver", "runID": RUN_ID,
                          "documents": str(self.documents), "fixture": str(expected),
                          "fixtureSHA256": FIXTURE_SHA256}, configuration)
        self.assertEqual((runner.ROOT / "examples/io-showcase.circ").read_bytes(), expected.read_bytes())
        self.assertEqual(FIXTURE_SHA256, hashlib.sha256(expected.read_bytes()).hexdigest())
        self.assertEqual([expected], list(self.documents.iterdir()))
        self.assertEqual([], list(self.primary.iterdir()))

    def test_does_not_overwrite_a_previous_source(self):
        fixture = self.documents / "io-showcase-source.circ"
        fixture.write_bytes(b"Preserve previous evidence")
        with self.assertRaises(FileExistsError):
            runner.stage_io_copy_source(self.peer, self.primary, RUN_ID)
        self.assertEqual(b"Preserve previous evidence", fixture.read_bytes())

    def test_rejects_an_altered_fixture_before_writing(self):
        changed = self.root / "changed.circ"
        changed.write_bytes(runner.IO_FIXTURE.read_bytes() + b"\n")
        with patch.object(runner, "IO_FIXTURE", changed), self.assertRaisesRegex(ValueError, "approved bytes"):
            runner.stage_io_copy_source(self.peer, self.primary, RUN_ID)
        self.assertEqual([], list(self.documents.iterdir()))

    def test_rejects_a_wrong_run_directory(self):
        for suffix in ("another-run", "0123456789ac"):
            with self.subTest(suffix=suffix):
                wrong = self.documents.parent / suffix
                wrong.mkdir()
                peer = dict(self.peer, documents=str(wrong))
                with self.assertRaisesRegex(ValueError, "Documents/LogisimE2E/runID"):
                    runner.stage_io_copy_source(peer, self.primary, RUN_ID)
                self.assertEqual([], list(wrong.iterdir()))

    def test_rejects_a_source_inside_the_main_app(self):
        wrong = self.primary / "Documents/LogisimE2E" / RUN_ID
        wrong.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "separate"):
            runner.stage_io_copy_source(dict(self.peer, documents=str(wrong)), self.primary, RUN_ID)
        self.assertEqual([], list(wrong.iterdir()))

    def test_rejects_noncanonical_and_symlinked_directories(self):
        alias = self.root / "alias"
        alias.symlink_to(self.documents.parent, target_is_directory=True)
        for path in (str(alias / RUN_ID), str(self.documents.parent / ".." / "LogisimE2E" / RUN_ID)):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "canonical"):
                runner.stage_io_copy_source(dict(self.peer, documents=path), self.primary, RUN_ID)
        self.assertEqual([], list(self.documents.iterdir()))

    def test_rejects_invalid_receiver_metadata(self):
        changes = ({"bundleID": "thalesmms.logisim.mobile"}, {"displayName": ""},
                   {"displayName": "$(PRODUCT_NAME)"}, {"documents": "relative/Documents/LogisimE2E/" + RUN_ID})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                runner.stage_io_copy_source(dict(self.peer, **change), self.primary, RUN_ID)
        self.assertEqual([], list(self.documents.iterdir()))

    def test_rejects_invalid_run_ids(self):
        for run_id in ("", "0123456789AB", "../0123456789ab", "0123456789a", "0123456789abc"):
            with self.subTest(run_id=run_id), self.assertRaisesRegex(ValueError, "canonical run ID"):
                runner.stage_io_copy_source(self.peer, self.primary, run_id)
        self.assertEqual([], list(self.documents.iterdir()))


class IoCopyRunnerTests(unittest.TestCase):
    def execute(self, *, setup="files-copy", prepare_only=True):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()
        output = root / "output"
        primary = root / "primary"
        primary.mkdir()
        peer = root / "receiver"
        peer.mkdir()
        installed = root / "Installed.app"
        installed.mkdir()
        application = root / "Supplied.app"
        application.mkdir()
        commands = []
        fingerprint = {"bundle_id": "thalesmms.logisim.mobile", "executable_sha256": "a" * 64,
                       "bundle_tree_sha256": "b" * 64}

        def artifact(path, bundle_id):
            if prepare_only:
                self.fail("prepare-only must not inspect an installed artifact")
            if bundle_id == runner.CLIPBOARD_RECEIVER_BUNDLE_ID:
                return {"bundle_id": bundle_id, "executable_sha256": "c" * 64, "bundle_tree_sha256": "d" * 64}
            self.assertIn(path, (installed, application))
            return fingerprint.copy()

        def common_prepare(output_path, catalog_root, bundle_id, component_search):
            harness = output_path / "harness"
            harness.mkdir()
            (harness / "Catalog.json").write_text(json.dumps({"a11y_canvas": {"en": "Canvas", "pt": "Circuito"}}))
            return harness / "AccessibilityHarness.xcodeproj"

        def command(arguments, *, log=None):
            commands.append(arguments)
            if arguments[0] == "xcodegen":
                return 0
            if prepare_only:
                self.fail("prepare-only attempted a device or build command: " + repr(arguments))
            if log is not None:
                Path(log).write_text("Mocked tool; no native operation.\n")
            if arguments[:4] == ["xcrun", "simctl", "list", "devices"]:
                return json.dumps({"devices": {"iOS-27": [{"udid": SIMULATOR, "state": "Booted"}]}})
            if arguments[:3] == ["xcrun", "simctl", "get_app_container"]:
                if arguments[4:] == ["thalesmms.logisim.mobile", "app"]:
                    return str(installed)
                return str(peer if arguments[4] == runner.CLIPBOARD_RECEIVER_BUNDLE_ID else primary)
            if arguments[:3] == ["xcrun", "simctl", "install"]:
                return ""
            if arguments[0] == "xcodebuild" and "build-for-testing" in arguments:
                products = output / "DerivedData/Build/Products/Debug-iphonesimulator"
                resources = products / "EditorEndToEndTests-Runner.app/PlugIns/EditorEndToEndTests.xctest"
                resources.mkdir(parents=True)
                for name in ("Catalog.json", "Run.json", "editor-actions-v1.json", "io-actions-v1.json",
                             "legacy-original-actions-v1.json"):
                    shutil.copyfile(output / "harness" / name, resources / name)
                receiver = products / "ClipboardReceiver.app"
                receiver.mkdir()
                with (receiver / "Info.plist").open("wb") as stream:
                    plistlib.dump({"CFBundleIdentifier": runner.CLIPBOARD_RECEIVER_BUNDLE_ID,
                                   "CFBundleDisplayName": "Clipboard Receiver", "UIFileSharingEnabled": True,
                                   "LSSupportsOpeningDocumentsInPlace": True}, stream)
                return 0
            if arguments[:3] == ["xcrun", "simctl", "io"] or arguments[:3] == ["xcrun", "simctl", "spawn"]:
                return 0
            self.fail("Unexpected mocked tool: " + repr(arguments))

        argv = ["runner", "--simulator", SIMULATOR, "--baseline-sha", "123abcd", "--app", str(application),
                "--output", str(output), "--only-testing", SELECTION]
        if setup is not None:
            argv.extend(("--io-setup", setup))
        argv.append("--prepare-only" if prepare_only else "--build-only")
        common = SimpleNamespace(run=command, prepare=common_prepare, artifact=artifact)
        with patch.object(sys, "argv", argv), patch.object(runner, "support", return_value=common), \
                patch.object(subprocess, "run", side_effect=AssertionError("No real native tools are allowed")), \
                contextlib.redirect_stdout(io.StringIO()):
            code = runner.main()
        report = json.loads((output / "results.json").read_text())
        self.assertEqual(0, code, report.get("error"))
        self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
        self.assertEqual(600, report["test_timeout_seconds"])
        self.assertEqual([SELECTION], report["selected_tests"])
        self.assertFalse(any("test-without-building" in args for args in commands))
        if prepare_only:
            self.assertFalse(any(args[:2] == ["xcrun", "simctl"] for args in commands))
            self.assertEqual("NOT_RUN", report["external_peer_preflight"])
            self.assertEqual({"status": "NOT_RUN"}, report["main_app_installation"])
        return output, report

    def assert_source_contract(self, output, report):
        setup = report["configuration"]["ioSetup"]
        documents = Path(setup["documents"])
        self.assertEqual("files-copy", setup["route"])
        self.assertEqual("Clipboard Receiver", setup["peerDisplayName"])
        self.assertEqual(report["run_id"], setup["runID"])
        self.assertEqual(("Documents", "LogisimE2E", report["run_id"]), documents.parts[-3:])
        self.assertEqual(str(documents / "io-showcase-source.circ"), setup["fixture"])
        self.assertEqual(FIXTURE_SHA256, setup["fixtureSHA256"])
        self.assertEqual(runner.IO_FIXTURE.read_bytes(), Path(setup["fixture"]).read_bytes())
        staged = json.loads((output / "harness/Run.json").read_text())
        self.assertEqual(setup, staged["ioSetup"])
        primary_documents = Path(report["configuration"]["documents"])
        self.assertFalse(documents.is_relative_to(primary_documents.parents[2]))
        self.assertEqual(runner.IO_FIXTURE.read_bytes(), Path(staged["fixtures"]["io"]).read_bytes())
        return setup

    def test_prepare_only_declares_the_receiver_payload_without_a_device(self):
        output, report = self.execute()
        self.assert_source_contract(output, report)
        self.assertEqual("PREPARED_ONLY", report["io_setup_preflight"])
        self.assertEqual("PREPARED", report["status"])
        self.assertTrue((output / "prepare-only-receiver").is_dir())

    def test_build_only_updates_staged_and_test_bundle_resources_after_peer_validation(self):
        output, report = self.execute(prepare_only=False)
        setup = self.assert_source_contract(output, report)
        resources = output / "DerivedData/Build/Products/Debug-iphonesimulator/EditorEndToEndTests-Runner.app/PlugIns/EditorEndToEndTests.xctest"
        self.assertEqual(setup, json.loads((resources / "Run.json").read_text())["ioSetup"])
        self.assertEqual("PASS", report["external_peer_preflight"])
        self.assertEqual("PASS", report["io_setup_preflight"])
        self.assertEqual("BUILT", report["status"])
        self.assertEqual(Path(setup["fixture"]).read_bytes(), (output / "external-peer-documents/io-showcase-source.circ").read_bytes())

    def test_default_and_explicit_system_open_leave_configuration_unchanged(self):
        for setup in (None, "system-open"):
            with self.subTest(setup=setup):
                output, report = self.execute(setup=setup)
                self.assertNotIn("ioSetup", report["configuration"])
                self.assertNotIn("io_setup_preflight", report)
                self.assertNotIn("ioSetup", json.loads((output / "harness/Run.json").read_text()))
                self.assertFalse((output / "prepare-only-receiver").exists())
                self.assertEqual(str(output / "prepare-only-documents"), report["configuration"]["documents"])

    def test_rejects_empty_duplicate_and_non_io_selections_before_any_tool(self):
        selections = [[], [SELECTION, SELECTION], ["EditorEndToEndTests"],
                      ["EditorEndToEndTests/InspectorEndToEndTests/testInvalidWidthPreservesProjectAndValidWidthIsOneUndoAction"],
                      ["EditorEndToEndTests/IoEndToEndTests/testIoDipLastExportAndReopen"],
                      [SELECTION + "Typo"]]
        for selected in selections:
            with self.subTest(selected=selected), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "must-not-exist"
                argv = ["runner", "--simulator", SIMULATOR, "--baseline-sha", "123abcd", "--prepare-only",
                        "--io-setup", "files-copy", "--output", str(output)]
                for item in selected:
                    argv.extend(("--only-testing", item))
                with patch.object(sys, "argv", argv), \
                        patch.object(runner, "support", side_effect=AssertionError("Validation must precede tool access")), \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                    runner.main()
                self.assertEqual(2, raised.exception.code)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
