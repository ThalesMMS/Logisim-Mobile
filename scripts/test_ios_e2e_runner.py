#!/usr/bin/env python3
"""Check editor runner evidence gates with fake native tools and filesystem artifacts."""

import contextlib
import hashlib
import importlib.util
import io
import json
import plistlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("ios_editor_runner", Path(__file__).with_name("run-ios-e2e.py"))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
SIMULATOR = "20A4D87D-A2A6-46BD-A889-C4888CE1E152"
BUNDLE_ID = "thalesmms.logisim.mobile"
RECEIVER_ID = "thalesmms.logisim.validation.clipboardreceiver"
SELECTION = "EditorEndToEndTests/testEditorActions"
DIAGNOSTIC = "EditorEndToEndTests/VisualExportEndToEndTests/testVisualExportFieldCensus"


class IosEditorRunnerTests(unittest.TestCase):
    def execute(self, *, interrupt=False, alpha=False, selected=(), test_exit=0,
                fail_install_verification=False, prepare_only=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = Path(directory.name).resolve()
        output = base / "evidence"
        application = base / "Fake.app"
        application.mkdir()
        installed_application = base / "Installed.app"
        installed_application.mkdir()
        container = base / "container"
        peer_container = base / "receiver-container"
        peer_container.mkdir()
        commands = []
        running_reports = []
        artifact_calls = []
        receiver = output / "DerivedData/Build/Products/Debug-iphonesimulator/ClipboardReceiver.app"
        fingerprint = {"bundle_id": BUNDLE_ID, "executable_sha256": "a" * 64,
                       "bundle_tree_sha256": "b" * 64}
        installed_fingerprint = dict(fingerprint, path=str(installed_application), version="different-metadata")
        if fail_install_verification:
            installed_fingerprint["bundle_tree_sha256"] = "e" * 64

        def artifact(path, bundle_id):
            artifact_calls.append((path, bundle_id))
            if bundle_id == RECEIVER_ID:
                self.assertEqual(receiver, path)
                return {"bundle_id": bundle_id, "executable_sha256": "c" * 64,
                        "bundle_tree_sha256": "d" * 64}
            if path == installed_application:
                return installed_fingerprint.copy()
            self.assertEqual(application, path)
            return fingerprint.copy()

        def prepare(output_path, catalog_root, bundle_id, component_search):
            self.assertEqual(runner.ROOT, catalog_root)
            self.assertEqual(BUNDLE_ID, bundle_id)
            self.assertEqual("I", component_search)
            project = output_path / "harness"
            project.mkdir()
            (project / "Catalog.json").write_text(json.dumps({"a11y_canvas": {"en": "Canvas", "pt": "Circuito"}}))
            return project / "AccessibilityHarness.xcodeproj"

        def command(arguments, *, log=None):
            commands.append(list(arguments))
            if log is not None:
                Path(log).write_text("Fake native tool output.\n")
            if arguments[:4] == ["xcrun", "simctl", "list", "devices"]:
                return json.dumps({"devices": {"iOS-27": [
                    {"udid": SIMULATOR, "state": "Booted", "name": "Fake iPhone"},
                ]}})
            if arguments[:3] == ["xcrun", "simctl", "install"]:
                self.assertIn(arguments[-1], (str(application), str(receiver)))
                return ""
            if arguments[:3] == ["xcrun", "simctl", "get_app_container"]:
                if arguments[4:] == [BUNDLE_ID, "app"]:
                    return str(installed_application)
                if arguments[4] == RECEIVER_ID:
                    self.assertTrue(any(command[:3] == ["xcrun", "simctl", "install"] and command[-1] == str(receiver)
                                        for command in commands), "Install the peer before resolving its data container")
                    return str(peer_container)
                return str(container)
            if arguments[0] == "xcodegen":
                self.assertTrue(Path(arguments[-1]).is_file())
                return ""
            if arguments[0] == "xcodebuild" and "build-for-testing" in arguments:
                derived = Path(arguments[arguments.index("-derivedDataPath") + 1])
                resources = derived / "Build/Products/Debug-iphonesimulator/EditorEndToEndTests-Runner.app/PlugIns/EditorEndToEndTests.xctest"
                resources.mkdir(parents=True)
                for name in ("Catalog.json", "Run.json", "editor-actions-v1.json", "io-actions-v1.json", "legacy-original-actions-v1.json"):
                    shutil.copyfile(output / "harness" / name, resources / name)
                receiver.mkdir()
                with (receiver / "Info.plist").open("wb") as stream:
                    plistlib.dump({"CFBundleIdentifier": RECEIVER_ID, "CFBundleDisplayName": "Clipboard Receiver",
                                   "UIFileSharingEnabled": True, "LSSupportsOpeningDocumentsInPlace": True}, stream)
                return 0
            if arguments[0] == "xcodebuild" and "test-without-building" in arguments:
                running_reports.append(json.loads((output / "results.json").read_text()))
                staged = running_reports[-1]
                self.assertEqual("PASS", staged["external_peer_preflight"])
                sidecar = Path(staged["configuration"]["documents"]) / "external-peer.json"
                self.assertEqual({"externalPeer": staged["external_peer"]}, json.loads(sidecar.read_text()))
                peer = staged["external_peer"]
                self.assertFalse(Path(peer["fixture"]).is_relative_to(container))
                self.assertEqual(Path(peer["fixture"]).read_bytes(), Path(peer["control"]).read_bytes())
                if interrupt:
                    raise KeyboardInterrupt("Interrupted fake native execution")
                return test_exit
            if arguments[:4] == ["xcrun", "xcresulttool", "export", "attachments"]:
                return 0
            if arguments[:3] == ["xcrun", "simctl", "io"] and "screenshot" in arguments:
                return 0
            if arguments[:3] == ["xcrun", "simctl", "spawn"] and "log" in arguments:
                return 0
            raise AssertionError(f"Unexpected native command: {arguments}")

        common = SimpleNamespace(run=command, prepare=prepare, artifact=artifact)
        argv = ["runner", "--simulator", SIMULATOR, "--bundle-id", BUNDLE_ID,
                "--baseline-sha", "123abcd", "--app", str(application), "--output", str(output)]
        if alpha:
            argv.append("--alpha")
        if prepare_only:
            argv.append("--prepare-only")
        for selection in selected:
            argv.extend(("--only-testing", selection))
        stdout = io.StringIO()
        with patch.object(sys, "argv", argv), patch.object(runner, "support", return_value=common), \
                patch.object(subprocess, "run", side_effect=AssertionError("A test attempted to start a real native tool")), \
                contextlib.redirect_stdout(stdout):
            code = runner.main()
        self.assertEqual(str(output / "results.json"), stdout.getvalue().strip())
        report = json.loads((output / "results.json").read_text())
        if prepare_only:
            self.assertEqual([], artifact_calls)
            self.assertFalse(any(command[:2] == ["xcrun", "simctl"] for command in commands))
            self.assertEqual({"status": "NOT_RUN"}, report["main_app_installation"])
            self.assertEqual("PREPARED", report["status"])
            self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
            return code, report, commands, output
        if fail_install_verification:
            self.assertEqual([(application, BUNDLE_ID), (installed_application, BUNDLE_ID),
                              (installed_application, BUNDLE_ID)], artifact_calls)
            self.assertEqual("VERIFICATION_FAILED", report["main_app_installation"]["status"])
            self.assertEqual(installed_fingerprint, report["main_app_installation"]["installed"])
            self.assertEqual("FAIL", report["status"])
            self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
            self.assertNotIn("configuration", report)
            self.assertFalse(any(command[0] in ("xcodegen", "xcodebuild") for command in commands))
            installs = [command for command in commands if command[:3] == ["xcrun", "simctl", "install"]]
            self.assertEqual([["xcrun", "simctl", "install", SIMULATOR, str(application)]], installs)
            return code, report, commands, output
        self.assertEqual([(application, BUNDLE_ID), (installed_application, BUNDLE_ID), (receiver, RECEIVER_ID)], artifact_calls)
        self.assertEqual("REUSED_IDENTICAL", report["main_app_installation"]["status"])
        self.assertEqual(installed_fingerprint, report["main_app_installation"]["installed"])
        self.assertFalse(any(command[:3] == ["xcrun", "simctl", "install"] and command[-1] == str(application)
                             for command in commands))
        self.assertEqual(fingerprint, report["artifact"])
        self.assertEqual(SIMULATOR, report["configuration"]["simulatorUDID"])
        held_path = Path(report["configuration"]["fixtures"]["ioHeld"])
        self.assertEqual((runner.ROOT / "examples/io-held-output.circ").read_bytes(), held_path.read_bytes())
        targets = Path(report["configuration"]["fixtures"]["ioHeldTargets"])
        self.assertEqual((runner.ROOT / "examples/io-held-output.targets.json").read_bytes(), targets.read_bytes())
        visual = report["configuration"]["visualExport"]
        self.assertEqual(str(container / "Library/Caches/LogisimAssetExports"), visual["assetCacheDirectory"])
        contract_text = (runner.ROOT / "fixtures/visual-export/contract.json").read_text()
        self.assertEqual(contract_text, visual["contractJSON"])
        self.assertEqual(hashlib.sha256(contract_text.encode("utf-8")).hexdigest(), visual["contractSHA256"])
        self.assertEqual(RECEIVER_ID, report["clipboard_receiver"]["bundle_id"])
        self.assertTrue(any(command[:3] == ["xcrun", "simctl", "install"] and command[-1] == str(receiver)
                            for command in commands), "Install the foreground paste receiver before native execution")
        self.assertEqual("PASS", report["resource_preflight"])
        self.assertEqual("PASS", report["external_peer_preflight"])
        self.assertEqual(str(peer_container / "Documents/LogisimE2E" / report["run_id"]), report["external_peer"]["documents"])
        self.assertEqual(Path(report["external_peer"]["control"]).read_bytes(),
                         (output / "external-peer-documents/control.circ").read_bytes())
        self.assertEqual(1, len(running_reports))
        self.assertEqual("NATIVE_RUNNING", running_reports[0]["native_execution_gate"])
        self.assertEqual("RUNNING", running_reports[0]["execution"]["status"])
        self.assertEqual(list(selected), running_reports[0]["execution"]["selection"])
        self.assertTrue(any(command[:3] == ["xcrun", "simctl", "io"] for command in commands))
        self.assertTrue(any(command[:3] == ["xcrun", "simctl", "spawn"] for command in commands))
        self.assertTrue(report.get("finished"))
        self.assertEqual(str(container / "Library/Application Support/LogisimMobile/workspace"),
                         report["configuration"]["workspace"])
        fixtures = report["configuration"]["fixtures"]
        inspector = Path(fixtures["inspector"])
        self.assertEqual(Path(report["configuration"]["documents"]), inspector.parent)
        self.assertEqual("inspector-width.circ", inspector.name)
        inspector_bytes = inspector.read_bytes()
        inspector_xml = ET.fromstring(inspector_bytes)
        inspector_title = "IOS_E2E_" + report["run_id"] + "_inspector"
        inspector_label = "INSPECTOR_" + report["run_id"]
        self.assertEqual(inspector_title, report["configuration"]["titles"]["inspector"])
        self.assertEqual(inspector_title, inspector_xml.find("main").get("name"))
        self.assertEqual([inspector_title], [item.get("name") for item in inspector_xml.findall("circuit")])
        self.assertEqual(1, len(inspector_xml.findall("circuit/comp")))
        self.assertEqual([], inspector_xml.findall("circuit/wire"))
        pin = inspector_xml.find("circuit/comp")
        self.assertEqual({"lib": "0", "loc": "(100,100)", "name": "Pin"}, pin.attrib)
        self.assertEqual({"type": "input", "width": "1", "label": inspector_label},
                         {item.get("name"): item.get("val") for item in pin.findall("a")})
        self.assertEqual({"label": inspector_label, "fixtureSHA256": hashlib.sha256(inspector_bytes).hexdigest()},
                         report["configuration"]["inspector"])
        self.assertEqual((runner.ROOT / "examples/io-showcase.circ").read_bytes(), Path(fixtures["io"]).read_bytes())
        legacy = json.loads((runner.ROOT / "fixtures/user-scenarios/legacy-original-actions-v1.json").read_text())
        self.assertEqual((runner.ROOT / legacy["fixture"]).read_bytes(), Path(fixtures["legacyOriginal"]).read_bytes())
        self.assertEqual(Path(report["configuration"]["documents"]), Path(fixtures["legacyOriginal"]).parent)
        self.assertEqual((runner.ROOT / "fixtures/portable-projects/portable53.lsebdl").read_bytes(),
                         Path(fixtures["portableBundle"]).read_bytes())
        self.assertTrue((Path(fixtures["portableSources"]) / "library/cell.circ").is_file())
        self.assertEqual((runner.ROOT / "fixtures/visual-export/qualification.circ").read_bytes(),
                         Path(fixtures["visualExport"]).read_bytes())
        self.assertEqual(SIMULATOR, report["configuration"]["simulatorUDID"])
        held_path = Path(report["configuration"]["fixtures"]["ioHeld"])
        self.assertEqual((runner.ROOT / "examples/io-held-output.circ").read_bytes(), held_path.read_bytes())
        targets = Path(report["configuration"]["fixtures"]["ioHeldTargets"])
        self.assertEqual((runner.ROOT / "examples/io-held-output.targets.json").read_bytes(), targets.read_bytes())
        visual = report["configuration"]["visualExport"]
        self.assertEqual("b" * 64, visual["buildSHA256"])
        self.assertEqual("123abcd", visual["sourcePin"])
        self.assertEqual(report["configuration"]["documents"], visual["outputDirectory"])
        self.assertIn("IoEndToEndTests.swift", report["harness_sources"])
        self.assertIn("InspectorEndToEndTests.swift", report["harness_sources"])
        return code, report, commands, output

    def test_interrupt_after_native_start_records_incomplete_instead_of_not_run(self):
        for alpha in (False, True):
            with self.subTest(alpha=alpha):
                code, report, commands, output = self.execute(interrupt=True, alpha=alpha, selected=(SELECTION,))
                self.assertEqual(130, code)
                self.assertEqual("FAIL", report["status"])
                self.assertEqual("NATIVE_INCOMPLETE", report["native_execution_gate"])
                self.assertNotEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
                self.assertEqual("INCOMPLETE", report["execution"]["status"])
                self.assertEqual([SELECTION], report["execution"]["selection"])
                self.assertNotIn("exit_code", report["execution"])
                self.assertEqual({"status": "PASS", "exit_code": 0}, report["build"])
                self.assertEqual("ALPHA_ONLY" if alpha else "NOT_QUALIFIED", report["qualification_gate"])
                self.assertEqual("KeyboardInterrupt", report["error"]["type"])
                self.assertEqual("Interrupted fake native execution", report["error"]["message"])
                self.assertIn("KeyboardInterrupt", (output / "execution-error.txt").read_text())
                self.assertFalse(any("xcresulttool" in command for command in commands))
                self.assertTrue((output / "documents" / "cold.circ").is_file())

    def test_successful_selected_tests_remain_partial(self):
        code, report, commands, _ = self.execute(selected=(SELECTION,))
        self.assertEqual(0, code)
        self.assertEqual("PASS", report["status"])
        self.assertEqual("NATIVE_PASS", report["native_execution_gate"])
        self.assertEqual("PASS", report["execution"]["status"])
        self.assertEqual("PARTIAL", report["qualification_gate"])
        self.assertEqual([SELECTION], report["selected_tests"])
        self.assertEqual([SELECTION], report["execution"]["selection"])
        native = next(command for command in commands if "test-without-building" in command)
        self.assertIn(f"-only-testing:{SELECTION}", native)

    def test_alpha_cannot_qualify_even_when_native_tests_pass(self):
        for selected in ((), (SELECTION,)):
            with self.subTest(selected=selected):
                code, report, _, _ = self.execute(alpha=True, selected=selected)
                self.assertEqual(0, code)
                self.assertTrue(report["alpha"])
                self.assertEqual("NATIVE_PASS", report["native_execution_gate"])
                self.assertEqual("ALPHA_ONLY", report["status"])
                self.assertEqual("ALPHA_ONLY", report["qualification_gate"])

    def test_explicit_diagnostic_requires_alpha_before_any_tool_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "must-not-exist"
            argv = ["run-ios-e2e.py", "--simulator", SIMULATOR, "--baseline-sha", "123abcd",
                    "--output", str(output), "--prepare-only", "--only-testing", DIAGNOSTIC]
            stderr = io.StringIO()
            with patch.object(sys, "argv", argv), patch.object(runner, "support") as support, \
                    contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as raised:
                runner.main()
            self.assertEqual(2, raised.exception.code)
            self.assertIn("require --alpha", stderr.getvalue())
            support.assert_not_called()
            self.assertFalse(output.exists())

    def test_qualifying_platform_run_skips_diagnostic_method(self):
        code, report, commands, _ = self.execute()
        self.assertEqual(0, code)
        self.assertEqual([DIAGNOSTIC], report["skipped_alpha_tests"])
        native = next(command for command in commands if "test-without-building" in command)
        self.assertIn(f"-skip-testing:{DIAGNOSTIC}", native)

    def test_explicit_alpha_diagnostic_cannot_qualify_and_is_not_skipped(self):
        code, report, commands, _ = self.execute(alpha=True, selected=(DIAGNOSTIC,))
        self.assertEqual(0, code)
        self.assertEqual("ALPHA_ONLY", report["qualification_gate"])
        self.assertEqual([], report["skipped_alpha_tests"])
        native = next(command for command in commands if "test-without-building" in command)
        self.assertIn(f"-only-testing:{DIAGNOSTIC}", native)
        self.assertFalse(any(argument.startswith("-skip-testing:") for argument in native))

    def test_failed_native_execution_remains_failed_with_alpha(self):
        code, report, _, _ = self.execute(alpha=True, test_exit=65)
        self.assertEqual(65, code)
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("NATIVE_FAIL", report["native_execution_gate"])
        self.assertEqual("FAIL", report["execution"]["status"])
        self.assertEqual("ALPHA_ONLY", report["qualification_gate"])

    def test_failed_selection_is_not_classified_as_partial_success(self):
        code, report, _, _ = self.execute(selected=(SELECTION,), test_exit=65)
        self.assertEqual(65, code)
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("NATIVE_FAIL", report["native_execution_gate"])
        self.assertEqual("FAIL", report["qualification_gate"])

    def test_wrong_fingerprint_after_install_aborts_before_preparation_and_build(self):
        code, report, _, _ = self.execute(fail_install_verification=True)
        self.assertEqual(1, code)
        self.assertEqual("RuntimeError", report["error"]["type"])

    def test_prepare_only_does_not_probe_or_install_the_main_app(self):
        code, _, _, _ = self.execute(prepare_only=True)
        self.assertEqual(0, code)


class MainAppInstallationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        native = patch.object(subprocess, "run", side_effect=AssertionError("A unit test attempted a native process"))
        native.start()
        self.addCleanup(native.stop)
        self.requested_app = self.root / "Requested.app"
        self.installed_app = self.root / "Installed.app"
        self.write_bundle(self.requested_app, executable=b"requested executable", resource=b"requested resource")
        self.requested = self.fingerprint(self.requested_app, BUNDLE_ID)
        self.commands = []
        self.install_count = 0
        self.install_behavior = "copy"
        self.incomplete_before = False
        self.before_bundle_id = None
        self.before_path = None
        self.data_file = self.root / "DataContainer/Documents/keep.circ"
        self.data_file.parent.mkdir(parents=True)
        self.data_file.write_bytes(b"user checkpoint must survive application installation")

        def artifact(path, bundle_id):
            self.assertEqual(self.installed_app, path)
            value = self.fingerprint(path, bundle_id)
            if self.install_count == 0 and self.incomplete_before or self.install_count > 0 and self.install_behavior == "incomplete":
                value.pop("bundle_tree_sha256")
            if self.install_count == 0 and self.before_bundle_id is not None:
                value["bundle_id"] = self.before_bundle_id
            return value

        def command(arguments):
            self.commands.append(list(arguments))
            if arguments == ["xcrun", "simctl", "get_app_container", SIMULATOR, BUNDLE_ID, "app"]:
                if self.install_count == 0 and self.before_path is not None:
                    return str(self.before_path)
                if not self.installed_app.exists():
                    raise subprocess.CalledProcessError(2, arguments, stderr="Application is not installed")
                return str(self.installed_app)
            self.assertEqual(["xcrun", "simctl", "install", SIMULATOR, str(self.requested_app)], list(arguments))
            self.install_count += 1
            if self.installed_app.exists():
                shutil.rmtree(self.installed_app)
            if self.install_behavior != "missing":
                shutil.copytree(self.requested_app, self.installed_app)
                if self.install_behavior == "different":
                    (self.installed_app / "Main").write_bytes(b"wrong executable after installation")
            return ""

        self.common = SimpleNamespace(run=command, artifact=artifact)

    @staticmethod
    def write_bundle(path, *, executable, resource):
        path.mkdir()
        (path / "Main").write_bytes(executable)
        (path / "Payload").write_bytes(resource)
        with (path / "Info.plist").open("wb") as stream:
            plistlib.dump({"CFBundleIdentifier": BUNDLE_ID, "CFBundleExecutable": "Main",
                           "CFBundleShortVersionString": "1", "CFBundleVersion": "1"}, stream)

    @staticmethod
    def fingerprint(path, bundle_id):
        return runner.support().artifact(path, bundle_id)

    def ensure(self):
        evidence = {"status": "NOT_RUN"}
        result = runner.ensure_main_app_installed(self.common, SIMULATOR, self.requested_app, self.requested, evidence)
        self.assertIsNone(result)
        self.assertEqual(b"user checkpoint must survive application installation", self.data_file.read_bytes())
        self.assertFalse(any("uninstall" in command for command in self.commands))
        return evidence

    def assert_verified(self, evidence):
        self.assertEqual("INSTALLED_VERIFIED", evidence["status"])
        installed = self.fingerprint(self.installed_app, BUNDLE_ID)
        self.assertEqual(installed, evidence["installed"])
        for key in ("bundle_id", "executable_sha256", "bundle_tree_sha256"):
            self.assertEqual(self.requested[key], evidence["installed"][key])
        self.assertEqual(1, self.install_count)

    def test_identical_installed_bits_are_reused_despite_path_and_version_metadata(self):
        shutil.copytree(self.requested_app, self.installed_app)
        self.requested.update(path="/different/source/path.app", version="different requested metadata")
        evidence = self.ensure()
        self.assertEqual("REUSED_IDENTICAL", evidence["status"])
        self.assertEqual(self.fingerprint(self.installed_app, BUNDLE_ID), evidence["before"])
        self.assertEqual(evidence["before"], evidence["installed"])
        self.assertEqual(0, self.install_count)
        self.assertEqual([["xcrun", "simctl", "get_app_container", SIMULATOR, BUNDLE_ID, "app"]], self.commands)

    def test_absent_app_is_installed_once_and_its_actual_bundle_is_verified(self):
        evidence = self.ensure()
        self.assert_verified(evidence)
        self.assertEqual("CalledProcessError", evidence["before_error"]["type"])
        self.assertNotIn("before", evidence)

    def test_changed_executable_is_replaced_and_previous_fingerprint_is_retained(self):
        self.write_bundle(self.installed_app, executable=b"old executable", resource=b"requested resource")
        before = self.fingerprint(self.installed_app, BUNDLE_ID)
        evidence = self.ensure()
        self.assert_verified(evidence)
        self.assertEqual(before, evidence["before"])
        self.assertNotEqual(before["executable_sha256"], evidence["installed"]["executable_sha256"])

    def test_equal_executable_with_changed_bundle_resource_is_reinstalled(self):
        self.write_bundle(self.installed_app, executable=b"requested executable", resource=b"old resource")
        before = self.fingerprint(self.installed_app, BUNDLE_ID)
        self.assertEqual(self.requested["executable_sha256"], before["executable_sha256"])
        evidence = self.ensure()
        self.assert_verified(evidence)
        self.assertEqual(before, evidence["before"])
        self.assertNotEqual(before["bundle_tree_sha256"], evidence["installed"]["bundle_tree_sha256"])

    def test_different_bundle_identifier_does_not_reuse_matching_hashes(self):
        shutil.copytree(self.requested_app, self.installed_app)
        self.before_bundle_id = "dev.logisim.different-app"
        evidence = self.ensure()
        self.assert_verified(evidence)
        self.assertEqual("dev.logisim.different-app", evidence["before"]["bundle_id"])

    def test_incomplete_installed_fingerprint_triggers_one_verified_install(self):
        shutil.copytree(self.requested_app, self.installed_app)
        self.incomplete_before = True
        evidence = self.ensure()
        self.assert_verified(evidence)
        self.assertEqual("ValueError", evidence["before_error"]["type"])

    def test_invalid_before_container_path_is_repaired_by_install_and_reprobe(self):
        self.before_path = Path("relative/Installed.app")
        evidence = self.ensure()
        self.assert_verified(evidence)
        self.assertEqual("ValueError", evidence["before_error"]["type"])

    def test_missing_incomplete_or_different_app_after_install_fails_verification(self):
        for behavior in ("missing", "incomplete", "different"):
            with self.subTest(behavior=behavior):
                if self.installed_app.exists():
                    shutil.rmtree(self.installed_app)
                self.install_count = 0
                self.install_behavior = behavior
                evidence = {"status": "NOT_RUN"}
                with self.assertRaises(RuntimeError):
                    runner.ensure_main_app_installed(self.common, SIMULATOR, self.requested_app, self.requested, evidence)
                self.assertEqual("VERIFICATION_FAILED", evidence["status"])
                self.assertEqual(1, self.install_count)
                self.assertEqual(b"user checkpoint must survive application installation", self.data_file.read_bytes())
                if behavior == "different":
                    self.assertNotEqual(self.requested["executable_sha256"], evidence["installed"]["executable_sha256"])
                else:
                    self.assertIn("verification_error", evidence)

    def test_repeating_an_installation_converges_to_reuse_without_touching_data(self):
        first = self.ensure()
        self.assert_verified(first)
        second = self.ensure()
        self.assertEqual("REUSED_IDENTICAL", second["status"])
        self.assertEqual(first["installed"], second["installed"])
        self.assertEqual(1, self.install_count)


class BuildLockTests(unittest.TestCase):
    def test_lock_is_released_after_success_and_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "build.lock"
            with runner.build_lock(lock):
                self.assertTrue(lock.is_dir())
            self.assertFalse(lock.exists())
            with self.assertRaises(RuntimeError):
                with runner.build_lock(lock):
                    raise RuntimeError("build failed")
            self.assertFalse(lock.exists())

    def test_waits_for_another_holder(self):
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "build.lock"
            lock.mkdir()
            sleeps = []
            with patch.object(runner.time, "sleep", side_effect=lambda seconds: (sleeps.append(seconds), lock.rmdir())):
                with runner.build_lock(lock, poll_seconds=7):
                    self.assertTrue(lock.is_dir())
            self.assertEqual(sleeps, [7])
            self.assertFalse(lock.exists())


if __name__ == "__main__":
    unittest.main()
