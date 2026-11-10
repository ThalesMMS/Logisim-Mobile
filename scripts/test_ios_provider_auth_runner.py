#!/usr/bin/env python3
"""Check provider-auth runner boundaries without starting native tools."""

from __future__ import annotations

import importlib.util
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch


SPEC = importlib.util.spec_from_file_location(
    "ios_provider_auth_runner", Path(__file__).with_name("run-ios-provider-auth.py")
)
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

SIMULATOR = "AD28C68F-21D3-4FA6-BCD7-383AE2A1D372"
RUN_ID = "012345abcdef"
SELECTOR = (
    "ProviderAuthenticationEndToEndTests/ProviderAuthenticationEndToEndTests/"
    "testAuthenticationDenialAfterGrantPreservesProject"
)
RESOURCE_NAMES = {
    "Catalog.json",
    "Run.json",
    "ProvenanceMainArtifact.json",
    "ProvenanceSignedFixture.json",
    "ProvenanceArtifactReview.json",
}
LISTAPPS_FIXTURE = Path(__file__).parent / "ios_provider_auth_ui/tests/fixtures/listapps-ad28-001.plist"
LISTAPPS_SHA256 = "3c665ecc5253c87abfd631dd5483c12b1333dcea042771221c9b8d07ceb9f3d5"


class ProviderAuthRunnerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()

    def commands(self, *, allow_native=False):
        output = self.root / "commands"
        output.mkdir(exist_ok=True)
        return runner.Commands(output, allow_native=allow_native)

    def resource_pair(self):
        project = self.root / "harness"
        bundle = self.root / "Tests.xctest"
        project.mkdir()
        bundle.mkdir()
        for name in RESOURCE_NAMES:
            contents = ((runner.SOURCE / name).read_bytes() if name.startswith("Provenance") else
                        json.dumps({"resource": name, "value": "expected"}).encode() + b"\n")
            (project / name).write_bytes(contents)
            (bundle / name).write_bytes(contents)
        (bundle / "Info.plist").write_bytes(plistlib.dumps({
            "TargetAppBundleIdentifier": "thalesmms.logisim.mobile",
        }))
        return project, bundle

    def bindings(self):
        return {
            "main": {"bundleID": "thalesmms.logisim.mobile", "source": "a" * 40,
                     "artifactManifestSHA256": "b" * 64, "bundleTreeSHA256": "c" * 64,
                     "executableSHA256": "d" * 64},
            "fixture": {"bundleID": "thalesmms.logisim.validation.revocationfixture",
                        "providerBundleID": "thalesmms.logisim.validation.revocationfixture.provider",
                        "source": "e" * 40, "artifactManifestSHA256": "f" * 64,
                        "appTreeSHA256": "1" * 64, "providerTreeSHA256": "2" * 64,
                        "entitlementReviewSHA256": "3" * 64},
        }

    def harness(self, sources=None):
        return {"commit": "4" * 40, "dirty": False,
                "sources": sources or {"scripts/run-ios-provider-auth.py": "5" * 64}}

    def lease(self, output):
        bindings, harness = self.bindings(), self.harness()
        return {"schemaVersion": 1, "authority": "ROOT", "nativeAuthorized": True,
                "scope": "LOCAL_SIMULATOR_MAIN_PLUS_PROVIDER",
                "operation": "MAIN_PLUS_PROVIDER_AUTH_AFTER_GRANT", "leaseID": "root-test-lease",
                "simulatorUDID": SIMULATOR, "runID": RUN_ID,
                "outputDirectory": str(output.resolve()), "selector": SELECTOR,
                "main": bindings["main"], "fixture": bindings["fixture"],
                "harnessCommit": harness["commit"], "harnessSources": harness["sources"]}

    @contextlib.contextmanager
    def prepared_inputs(self):
        """Exercise real prepare logic with local catalog/source inputs, not a fake build."""
        catalog_root = self.root / "catalog"
        messages = catalog_root / "localization/messages"
        messages.mkdir(parents=True)
        required = {"a11y_project_tools", "a11y_explorer_title", "a11y_canvas",
                    "action_close", "a11y_fit_selection"}
        catalog = {key: {"en": key, "pt": key} for key in required}
        catalog.update({f"test_catalog_{index}": {"en": str(index), "pt": str(index)}
                        for index in range(2497 - len(catalog))})
        (messages / "test.json").write_text(json.dumps(catalog))
        source = self.root / "sources"
        source.mkdir()
        shutil.copyfile(runner.SOURCE / "project.yml", source / "project.yml")
        paths = {}
        for name in runner.SOURCES:
            paths[name] = source / name
            paths[name].write_text("// Local preparation input: " + name + "\n")
        for name in RESOURCE_NAMES - {"Catalog.json", "Run.json"}:
            shutil.copyfile(runner.SOURCE / name, source / name)
        with patch.object(runner, "ROOT", self.root), patch.object(runner, "SOURCE", source), \
                patch.object(runner, "SOURCES", paths):
            yield catalog_root, source, paths

    @contextlib.contextmanager
    def installation(self, *, main_present=True, fixture_present=True, changed=None):
        main = self.root / "installed-main.app"
        fixture = self.root / "installed-fixture.app"
        provider = fixture / "PlugIns/RevocationProvider.appex"
        main.mkdir()
        provider.mkdir(parents=True)
        requested_main = self.root / "approved-main.app"
        requested_main.mkdir()
        expected = {
            "Main": {"bundle_id": runner.MAIN_ID, "executable_sha256": "a" * 64,
                     "bundle_tree_sha256": "b" * 64},
            "App": {"bundle_id": runner.FIXTURE_ID, "executable_sha256": "c" * 64,
                    "bundle_tree_sha256": "d" * 64},
            "Provider": {"bundle_id": runner.PROVIDER_ID, "executable_sha256": "e" * 64,
                         "bundle_tree_sha256": "f" * 64},
        }
        observed = copy.deepcopy(expected)
        if changed:
            observed[changed]["bundle_tree_sha256"] = "0" * 64
        paths = {runner.MAIN_ID: main, runner.FIXTURE_ID: fixture, runner.PROVIDER_ID: provider}
        roles = {runner.MAIN_ID: "Main", runner.FIXTURE_ID: "App", runner.PROVIDER_ID: "Provider"}
        calls = []
        applications = {}
        if fixture_present:
            applications[runner.FIXTURE_ID] = {"CFBundleIdentifier": runner.FIXTURE_ID}
        if main_present:
            applications[runner.MAIN_ID] = {"CFBundleIdentifier": runner.MAIN_ID}
        payload = plistlib.dumps(applications)

        def command(arguments, label, **kwargs):
            arguments = [str(value) for value in arguments]
            calls.append(arguments)
            if arguments[:3] == ["xcrun", "simctl", "listapps"]:
                return subprocess.CompletedProcess(arguments, 0, payload, b"")
            if arguments == ["plutil", "-convert", "json", "-o", "-", "-"]:
                self.assertEqual("installed-applications-json", label)
                self.assertEqual(payload, kwargs["input_bytes"])
                return subprocess.CompletedProcess(arguments, 0, json.dumps(plistlib.loads(payload)).encode(), b"")
            if arguments[:3] == ["xcrun", "simctl", "get_app_container"]:
                return subprocess.CompletedProcess(arguments, 0, str(paths[arguments[4]]).encode(), b"")
            if arguments[:3] == ["xcrun", "simctl", "install"]:
                self.assertEqual("install-main", label)
                self.assertEqual(str(requested_main), arguments[-1], "Never install or refresh the registered fixture")
                return subprocess.CompletedProcess(arguments, 0, b"", b"")
            raise AssertionError("Unexpected mocked installation command: " + repr(arguments))

        def artifact(path, bundle_id):
            self.assertEqual(paths[bundle_id], path)
            return observed[roles[bundle_id]].copy()

        bindings = {"identities": expected, "artifactPaths": {"main": str(requested_main)}}
        commands = SimpleNamespace(run=command, simulator=SIMULATOR)
        with patch.object(runner, "support", return_value=SimpleNamespace(artifact=artifact)), \
                patch.object(subprocess, "run", side_effect=AssertionError("A mocked installation escaped to Native")):
            yield commands, bindings, expected, calls

    def test_archived_listapps_fixture_is_the_exact_failed_run_payload(self):
        payload = LISTAPPS_FIXTURE.read_bytes()
        self.assertEqual(58955, len(payload))
        self.assertEqual(LISTAPPS_SHA256, hashlib.sha256(payload).hexdigest())

    @unittest.skipUnless(shutil.which("plutil"), "Host plutil is unavailable")
    def test_archived_openstep_listapps_decodes_on_host_without_querying_a_device(self):
        applications = runner.decode_installed_applications(LISTAPPS_FIXTURE.read_bytes(), self.commands())
        self.assertEqual(44, len(applications))
        fixture_id = "thalesmms.logisim.validation.revocationfixture"
        self.assertEqual(fixture_id, applications[fixture_id]["CFBundleIdentifier"])
        self.assertEqual("com.apple.DocumentsApp", applications["com.apple.DocumentsApp"]["CFBundleIdentifier"])
        self.assertNotIn("thalesmms.logisim.mobile", applications)
        self.assertTrue(all(isinstance(info, dict) and info.get("CFBundleIdentifier") == key
                            for key, info in applications.items()))

    @unittest.skipUnless(shutil.which("plutil"), "Host plutil is unavailable")
    def test_malformed_openstep_listapps_fails_conversion(self):
        commands = self.commands()
        with self.assertRaises(subprocess.CalledProcessError):
            runner.decode_installed_applications(b"{ invalid = (\n", commands)
        records = json.loads((commands.output / "commands.json").read_text())
        self.assertNotEqual(0, records[0]["exitCode"])

    def test_converted_listapps_must_have_a_dictionary_top_level(self):
        payload = b"unparsed local input"
        for converted in [[], ["app"], "app", None, 7]:
            with self.subTest(converted=converted):
                def conversion(arguments, label, **kwargs):
                    self.assertEqual(["plutil", "-convert", "json", "-o", "-", "-"], arguments)
                    self.assertEqual("installed-applications-json", label)
                    self.assertEqual(payload, kwargs["input_bytes"])
                    return subprocess.CompletedProcess(arguments, 0, json.dumps(converted).encode(), b"")

                with patch.object(subprocess, "run", side_effect=AssertionError("Conversion mock escaped")), \
                        self.assertRaises(ValueError):
                    runner.decode_installed_applications(payload, SimpleNamespace(run=conversion))

    def test_converted_listapps_requires_metadata_identifiers_matching_each_key(self):
        for metadata in [None, [], "app", {}, {"CFBundleIdentifier": "other.app"}, {"CFBundleIdentifier": None}]:
            with self.subTest(metadata=metadata):
                converted = {"valid.app": {"CFBundleIdentifier": "valid.app"}, "expected.app": metadata}
                completed = subprocess.CompletedProcess(["plutil"], 0, json.dumps(converted).encode(), b"")
                with patch.object(subprocess, "run", side_effect=AssertionError("Conversion mock escaped")), \
                        self.assertRaises(ValueError):
                    runner.decode_installed_applications(b"input", SimpleNamespace(run=lambda *args, **kwargs: completed))

    def test_exact_installed_main_and_registered_fixture_are_reused(self):
        with self.installation() as (commands, bindings, expected, calls):
            identities, context = runner.admit_installed_apps(commands, bindings)
        self.assertEqual(expected, identities)
        self.assertEqual("REUSED_EXACT_INSTALLED_MAIN", context["main"])
        self.assertEqual("REUSED_EXACT_REGISTERED_FIXTURE", context["fixture"])
        self.assertFalse(context["fixtureInstalledOrRefreshed"])
        self.assertFalse(any(command[2] == "install" for command in calls))

    def test_absent_main_installs_only_main_and_preserves_registered_fixture(self):
        with self.installation(main_present=False) as (commands, bindings, expected, calls):
            identities, context = runner.admit_installed_apps(commands, bindings)
        self.assertEqual(expected, identities)
        self.assertEqual("INSTALLED_APPROVED_MAIN", context["main"])
        self.assertFalse(context["fixtureInstalledOrRefreshed"])
        self.assertEqual([["xcrun", "simctl", "install", SIMULATOR, bindings["artifactPaths"]["main"]]],
                         [command for command in calls if command[2] == "install"])

    def test_absent_registered_fixture_blocks_main_installation(self):
        with self.installation(main_present=False, fixture_present=False) as (commands, bindings, _, calls):
            with self.assertRaises(ValueError):
                runner.admit_installed_apps(commands, bindings)
        self.assertEqual([["xcrun", "simctl", "listapps", SIMULATOR],
                          ["plutil", "-convert", "json", "-o", "-", "-"]], calls)

    def test_divergent_installed_main_is_rejected_without_replacement(self):
        with self.installation(changed="Main") as (commands, bindings, _, calls):
            with self.assertRaises(ValueError):
                runner.admit_installed_apps(commands, bindings)
        self.assertFalse(any(command[2] == "install" for command in calls))

    def test_divergent_fixture_is_rejected_before_installing_an_absent_main(self):
        for role in ["App", "Provider"]:
            with self.subTest(changed=role):
                # Keep each simulated installation separate, like separate device records.
                original_root = self.root
                self.root = original_root / role
                self.root.mkdir()
                try:
                    with self.installation(main_present=False, changed=role) as (commands, bindings, _, calls):
                        with self.assertRaises(ValueError):
                            runner.admit_installed_apps(commands, bindings)
                    self.assertFalse(any(command[2] == "install" for command in calls))
                finally:
                    self.root = original_root

    def test_matching_run_bound_lease_is_accepted_without_commands(self):
        output = self.root / "evidence"
        lease = self.lease(output)
        with patch.object(subprocess, "run", side_effect=AssertionError("Lease checks must be local")):
            accepted = runner.validate_lease(lease, run_id=RUN_ID, simulator=SIMULATOR,
                                             output=output, bindings=self.bindings(), harness=self.harness())
        self.assertEqual(lease, accepted)

    def test_lease_cannot_be_reused_for_another_run_artifact_or_harness(self):
        output = self.root / "evidence"
        changes = {
            "authority": "other", "nativeAuthorized": False,
            "scope": "LOCAL_SIMULATOR_ONLY", "operation": "ONE_PREPARE_REGISTER_INSPECT",
            "leaseID": "", "simulatorUDID": "20A4D87D-A2A6-46BD-A889-C4888CE1E152",
            "runID": "abcdef012345", "outputDirectory": str(self.root / "other"),
            "selector": SELECTOR + ",EditorEndToEndTests/testOther",
            "main": dict(self.bindings()["main"], bundleTreeSHA256="0" * 64),
            "fixture": dict(self.bindings()["fixture"], providerTreeSHA256="0" * 64),
            "harnessCommit": "0" * 40, "harnessSources": {},
        }
        for key, value in changes.items():
            with self.subTest(changed=key):
                lease = self.lease(output)
                lease[key] = value
                with self.assertRaises(ValueError):
                    runner.validate_lease(lease, run_id=RUN_ID, simulator=SIMULATOR,
                                          output=output, bindings=self.bindings(), harness=self.harness())
        with self.assertRaises(ValueError):
            runner.validate_lease(self.lease(output), run_id=RUN_ID, simulator=SIMULATOR,
                                  output=output, bindings=self.bindings(),
                                  harness=dict(self.harness(), dirty=True))

    def test_real_prepare_stages_only_auth_inputs_and_rewrites_testinfo_after_generation(self):
        output = self.root / "prepared"
        output.mkdir()
        configuration = {"runID": RUN_ID, "simulatorUDID": SIMULATOR, "rootLease": None}
        calls = []

        def generation(arguments, label, **kwargs):
            calls.append([str(value) for value in arguments])
            (output / "harness/TestInfo.plist").write_bytes(plistlib.dumps({
                "TargetAppBundleIdentifier": "wrong-before-rewrite",
            }))
            return subprocess.CompletedProcess(arguments, 0, b"", b"")

        with self.prepared_inputs() as (catalog_root, source, paths), \
                patch.object(subprocess, "run", side_effect=AssertionError("Unexpected external command")):
            project, metadata = runner.prepare(output, catalog_root, configuration,
                                                SimpleNamespace(run=generation))
            staged = project.parent
            self.assertEqual(["xcodegen"], [arguments[0] for arguments in calls])
            self.assertEqual(set(paths), {path.name for path in staged.glob("*.swift")})
            self.assertEqual(RESOURCE_NAMES, {path.name for path in staged.glob("*.json")})
            self.assertEqual(configuration, json.loads((staged / "Run.json").read_text()))
            self.assertEqual(2497, len(json.loads((staged / "Catalog.json").read_text())))
            info = plistlib.loads((staged / "TestInfo.plist").read_bytes())
            self.assertEqual("thalesmms.logisim.mobile", info["TargetAppBundleIdentifier"])
            self.assertEqual("REVOCATION_INPUT", info["ComponentSearch"])
            self.assertEqual(set(paths), set(metadata["sources"]))
            self.assertEqual({name: hashlib.sha256((staged / name).read_bytes()).hexdigest()
                              for name in RESOURCE_NAMES}, metadata["resources"])
            self.assertEqual([], [path for path in output.rglob("*.circ")])
            self.assertFalse((staged / "ClipboardReceiver").exists())

    @unittest.skipUnless(shutil.which("xcodegen"), "XcodeGen is unavailable for source-generation-only validation")
    def test_source_generation_puts_all_five_jsons_in_the_pbx_resource_phase(self):
        """Generate project text only; this does not build or run a test bundle."""
        output = self.root / "generated"
        output.mkdir()
        with self.prepared_inputs() as (catalog_root, _, _):
            project, _ = runner.prepare(output, catalog_root, {"runID": RUN_ID}, runner.Commands(output))
        text = (project / "project.pbxproj").read_text()
        section = re.search(r"/\* Begin PBXResourcesBuildPhase section \*/(.*?)/\* End PBXResourcesBuildPhase section \*/",
                            text, re.S)
        self.assertIsNotNone(section)
        resources = re.findall(r"/\* ([^*/]+\.json) in Resources \*/", section[1])
        self.assertEqual(RESOURCE_NAMES, set(resources))
        self.assertEqual(5, len(resources))
        self.assertNotIn("TestInfo.plist in Resources", section[1])
        self.assertIn("INFOPLIST_FILE = TestInfo.plist;", text)

    def test_default_cli_prepares_with_no_native_authorization(self):
        output = self.root / "evidence"
        with self.prepared_inputs() as (catalog_root, _, paths):
            bindings = dict(self.bindings(), catalogRoot=str(catalog_root))
            harness = self.harness({path.relative_to(self.root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                                    for path in paths.values()})

            def source_command(arguments, **kwargs):
                self.assertEqual("xcodegen", arguments[0], "Default must not start Native or build tools")
                return subprocess.CompletedProcess(arguments, 0, b"generated", b"")

            with patch.object(runner, "preflight", return_value=bindings), \
                    patch.object(runner, "source_state", return_value=harness), \
                    patch.object(runner, "execute", side_effect=AssertionError("Default must never execute")) as execute, \
                    patch.object(runner, "validated_build_wrapper", side_effect=AssertionError("Prepare accessed the build wrapper or lock")) as wrapper, \
                    patch.object(subprocess, "run", side_effect=source_command) as process, \
                    contextlib.redirect_stdout(io.StringIO()):
                code = runner.main(["--output", str(output)])
            self.assertEqual(0, code)
            self.assertEqual(1, process.call_count)
            execute.assert_not_called()
            wrapper.assert_not_called()
        report = json.loads((output / "results.json").read_text())
        self.assertEqual("PREPARED", report["status"])
        self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
        self.assertEqual("NOT_QUALIFIED", report["qualification_gate"])
        self.assertEqual([SELECTOR], report["selectedTests"])
        self.assertIsNone(report["rootLease"])
        self.assertFalse(report["physicalDevice"])
        self.assertFalse(report["nativeOperationAuthorizedByCurrentArtifactReview"])

    def test_harness_drift_during_preparation_blocks_execution(self):
        output = self.root / "evidence"
        with self.prepared_inputs() as (catalog_root, _, paths):
            bindings = dict(self.bindings(), catalogRoot=str(catalog_root))
            before = self.harness({path.relative_to(self.root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                                   for path in paths.values()})
            after = dict(before, commit="0" * 40)
            with patch.object(runner, "preflight", return_value=bindings), \
                    patch.object(runner, "source_state", side_effect=[before, after]), \
                    patch.object(runner, "execute", side_effect=AssertionError("Changed harness reached execution")) as execute, \
                    patch.object(subprocess, "run", return_value=subprocess.CompletedProcess(["xcodegen"], 0, b"", b"")), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = runner.main(["--output", str(output)])
        self.assertNotEqual(0, code)
        execute.assert_not_called()
        report = json.loads((output / "results.json").read_text())
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])

    def execute_boundary(self, *, stale_resource=False, build_failure=False, build_interrupted=False):
        """Use a local resource fixture and stop before any mocked XCTest result."""
        project_dir, resource_bundle = self.resource_pair()
        output = self.root / "execution"
        output.mkdir()
        derived = self.root / "DerivedData"
        expected_bundle = derived / (
            "Build/Products/Debug-iphonesimulator/ProviderAuthenticationEndToEndTests-Runner.app/"
            "PlugIns/ProviderAuthenticationEndToEndTests.xctest"
        )
        expected_bundle.parent.mkdir(parents=True)
        shutil.move(str(resource_bundle), expected_bundle)
        if stale_resource:
            file = expected_bundle / "Catalog.json"
            file.write_bytes(file.read_bytes() + b" \n")
        calls = []

        def command(arguments, label, **kwargs):
            arguments = [str(value) for value in arguments]
            calls.append((label, arguments))
            if label == "harness-build":
                self.assertEqual([sys.executable, str(runner.BUILD_WRAPPER), "xcodebuild"], arguments[:3])
                self.assertEqual("build-for-testing", arguments[-1])
                self.assertIsNone(kwargs["timeout"], "Do not timeout only the wrapper while its build child still owns the lock")
                self.assertFalse(kwargs["check"])
                self.assertFalse(report["buildSerialization"]["wrapperProcessCompleted"])
                if build_interrupted:
                    raise runner.BuildInterrupted(subprocess.CompletedProcess(arguments, 0, b"finished", b""))
                if build_failure:
                    return subprocess.CompletedProcess(arguments, 7, b"", b"mock build failed")
                return subprocess.CompletedProcess(arguments, 0, b"", b"")
            if label == "auth-test":
                self.assertEqual("xcodebuild", arguments[0])
                self.assertNotIn(str(runner.BUILD_WRAPPER), arguments)
                self.assertEqual(200, kwargs["timeout"])
                self.assertTrue(report["buildSerialization"]["wrapperProcessCompleted"])
                raise RuntimeError("Stopped at the mocked XCTest boundary")
            raise AssertionError("Unexpected mocked execution command: " + repr(arguments))

        args = SimpleNamespace(simulator=SIMULATOR, derived_data=derived, test_timeout=20)
        report = {"status": "PREPARED", "native_execution_gate": "NATIVE_RUNNING",
                  "test_execution_gate": "NATIVE_NOT_RUN"}
        wrapper_metadata = {"wrapper": str(runner.BUILD_WRAPPER), "sha256": runner.BUILD_WRAPPER_SHA,
                            "retained": "BuildSerializationWrapper.py", "lock": str(runner.BUILD_LOCK),
                            "scope": "BUILD_FOR_TESTING_ONLY", "wrapperProcessCompleted": False}
        resource_verifier = runner.verify_bundled_resources

        def verify_resources(project, bundle):
            self.assertTrue(report["buildSerialization"]["wrapperProcessCompleted"])
            return resource_verifier(project, bundle)

        with patch.object(runner, "validated_build_wrapper", return_value=wrapper_metadata) as wrapper, \
                patch.object(runner, "verify_bundled_resources", side_effect=verify_resources) as resources, \
                patch.object(subprocess, "run", side_effect=AssertionError("Execution mock escaped to Native")):
            expected_error = (runner.BuildInterrupted if build_interrupted else
                              ValueError if build_failure or stale_resource else RuntimeError)
            with self.assertRaises(expected_error):
                runner.execute_test(args, output, project_dir / "Harness.xcodeproj", report,
                                    SimpleNamespace(run=command))
            wrapper.assert_called_once_with(output)
            if build_failure or build_interrupted:
                resources.assert_not_called()
            else:
                resources.assert_called_once()
        return calls, report

    def test_stale_bundled_resource_blocks_xctest_after_mock_build(self):
        calls, report = self.execute_boundary(stale_resource=True)
        self.assertEqual(["harness-build"], [label for label, _ in calls])
        self.assertEqual("NATIVE_RUNNING", report["native_execution_gate"])
        self.assertEqual("NATIVE_NOT_RUN", report["test_execution_gate"])
        self.assertTrue(report["buildSerialization"]["wrapperProcessCompleted"])
        self.assertEqual(0, report["buildSerialization"]["exitCode"])

    def test_failed_serialized_build_never_checks_resources_or_starts_xctest(self):
        calls, report = self.execute_boundary(build_failure=True)
        self.assertEqual(["harness-build"], [label for label, _ in calls])
        self.assertTrue(report["buildSerialization"]["wrapperProcessCompleted"])
        self.assertEqual(7, report["buildSerialization"]["exitCode"])
        self.assertEqual("NATIVE_NOT_RUN", report["test_execution_gate"])

    def test_deferred_build_interruption_records_completion_but_does_not_start_xctest(self):
        calls, report = self.execute_boundary(build_interrupted=True)
        self.assertEqual(["harness-build"], [label for label, _ in calls])
        self.assertTrue(report["buildSerialization"]["wrapperProcessCompleted"])
        self.assertEqual(0, report["buildSerialization"]["exitCode"])
        self.assertTrue(report["buildSerialization"]["interruptionDeferred"])
        self.assertEqual("NATIVE_NOT_RUN", report["test_execution_gate"])
        self.assertNotEqual("PASS", report["status"])

    def test_execution_requests_only_the_literal_auth_selector(self):
        calls, report = self.execute_boundary()
        requests = [arguments for label, arguments in calls if label == "auth-test"]
        self.assertEqual(1, len(requests))
        self.assertEqual(["-only-testing:" + SELECTOR],
                         [argument for argument in requests[0] if argument.startswith("-only-testing:")])
        self.assertEqual("NATIVE_RUNNING", report["test_execution_gate"])

    def test_wrapper_validation_rejects_symlinks_without_archiving_them(self):
        target = self.root / "fixture-wrapper.py"
        target.write_bytes(b"# Local test input, never executed.\n")
        link = self.root / "wrapper-link.py"
        link.symlink_to(target)
        with patch.object(runner, "BUILD_WRAPPER", link), self.assertRaises(ValueError):
            runner.validated_build_wrapper(self.root)
        self.assertFalse((self.root / "BuildSerializationWrapper.py").exists())

    def test_wrapper_validation_rejects_unreviewed_literal_bytes(self):
        wrapper = self.root / "fixture-wrapper.py"
        wrapper.write_bytes(b"# Unreviewed local test input, never executed.\n")
        with patch.object(runner, "BUILD_WRAPPER", wrapper), self.assertRaises(ValueError):
            runner.validated_build_wrapper(self.root)
        self.assertFalse((self.root / "BuildSerializationWrapper.py").exists())

    def test_postflight_is_required_and_cannot_turn_a_failed_case_into_pass(self):
        for case_failed, postflight_failed in [(True, False), (True, True), (False, True), (False, False)]:
            with self.subTest(case_failed=case_failed, postflight_failed=postflight_failed):
                devices = {"devices": {"iOS-27": [{"udid": SIMULATOR, "state": "Booted"}]}}
                calls = []

                def command(arguments, label, **kwargs):
                    self.assertEqual("devices", label)
                    calls.append([str(value) for value in arguments])
                    return subprocess.CompletedProcess(arguments, 0, json.dumps(devices).encode(), b"")

                report = {"status": "PREPARED", "native_execution_gate": "NATIVE_RUNNING",
                          "test_execution_gate": "NATIVE_NOT_RUN"}
                after = {"Main": "exact", "App": "exact", "Provider": "exact"}
                args = SimpleNamespace(simulator=SIMULATOR)
                with patch.object(runner, "admit_installed_apps", return_value=({}, {"fixtureInstalledOrRefreshed": False})), \
                        patch.object(runner, "execute_test", side_effect=ValueError("case rejected") if case_failed else None), \
                        patch.object(runner, "check_installed", return_value=after,
                                     side_effect=ValueError("postflight mismatch") if postflight_failed else None) as postflight, \
                        patch.object(subprocess, "run", side_effect=AssertionError("Postflight mock escaped to Native")):
                    if case_failed or postflight_failed:
                        with self.assertRaisesRegex(ValueError, "case rejected" if case_failed else "postflight mismatch"):
                            runner.execute(args, self.root, self.root / "Harness.xcodeproj", self.bindings(), report,
                                           SimpleNamespace(run=command))
                    else:
                        runner.execute(args, self.root, self.root / "Harness.xcodeproj", self.bindings(), report,
                                       SimpleNamespace(run=command))
                    postflight.assert_called_once()
                self.assertEqual(1, len(calls))
                if not postflight_failed:
                    self.assertEqual(after, report["installedAfterTest"])
                if case_failed and postflight_failed:
                    self.assertEqual("postflight mismatch", report["installedAfterTestError"]["message"])
                self.assertEqual(not case_failed and not postflight_failed, report["status"] == "PASS")
                self.assertEqual(not case_failed and not postflight_failed,
                                 report["native_execution_gate"] == "NATIVE_PASS")

    def test_execute_requires_explicit_uuid_lease_and_run_id_before_preflight(self):
        invalid = [
            ["--execute"],
            ["--execute", "--simulator", SIMULATOR],
            ["--execute", "--root-lease", str(self.root / "lease.json"), "--run-id", RUN_ID],
            ["--execute", "--simulator", "booted", "--root-lease", str(self.root / "lease.json"), "--run-id", RUN_ID],
            ["--execute", "--simulator", SIMULATOR, "--root-lease", str(self.root / "lease.json"), "--run-id", "bad"],
        ]
        with patch.object(runner, "preflight", side_effect=AssertionError("Invalid execution reached preflight")) as preflight, \
                patch.object(subprocess, "run", side_effect=AssertionError("Invalid execution started a command")) as process, \
                contextlib.redirect_stderr(io.StringIO()):
            for index, arguments in enumerate(invalid):
                with self.subTest(arguments=arguments), self.assertRaises(SystemExit) as error:
                    runner.main(["--output", str(self.root / f"invalid-{index}"), *arguments])
                self.assertEqual(2, error.exception.code)
            preflight.assert_not_called()
            process.assert_not_called()

    def test_rejected_lease_never_reaches_prepare_execute_or_native_cleanup(self):
        output = self.root / "evidence"
        lease = self.lease(output)
        lease["runID"] = "abcdef012345"
        file = self.root / "lease.json"
        file.write_text(json.dumps(lease))
        with patch.object(runner, "preflight", return_value=self.bindings()), \
                patch.object(runner, "source_state", return_value=self.harness()), \
                patch.object(runner, "prepare", side_effect=AssertionError("Lease rejection reached prepare")) as prepare, \
                patch.object(runner, "execute", side_effect=AssertionError("Lease rejection reached execute")) as execute, \
                patch.object(subprocess, "run", side_effect=AssertionError("Lease rejection started a command")) as process, \
                contextlib.redirect_stdout(io.StringIO()):
            code = runner.main(["--output", str(output), "--execute", "--simulator", SIMULATOR,
                                "--root-lease", str(file), "--run-id", RUN_ID])
        self.assertNotEqual(0, code)
        prepare.assert_not_called()
        execute.assert_not_called()
        process.assert_not_called()
        report = json.loads((output / "results.json").read_text())
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("NATIVE_NOT_RUN", report["native_execution_gate"])
        self.assertIsNone(report["rootLease"])

    def test_native_commands_require_explicit_admission_before_subprocess(self):
        commands = self.commands()
        native = [
            ["xcodebuild", "build-for-testing"],
            ["xcrun", "simctl", "list", "devices"],
            ["xcrun", "xcresulttool", "export", "attachments"],
            [sys.executable, str(runner.BUILD_WRAPPER), "xcodebuild", "build-for-testing"],
            [str(runner.BUILD_WRAPPER), "xcodebuild", "build-for-testing"],
        ]
        with patch.object(subprocess, "run", side_effect=AssertionError("Native subprocess escaped its gate")) as process, \
                patch.object(subprocess, "Popen", side_effect=AssertionError("Native process escaped its gate")) as popen:
            for arguments in native:
                with self.subTest(arguments=arguments):
                    with self.assertRaises((PermissionError, RuntimeError, ValueError)):
                        commands.run(arguments, "native-denied")
            process.assert_not_called()
            popen.assert_not_called()

    def test_command_result_keeps_stdout_and_stderr_bytes(self):
        commands = self.commands()
        arguments = ["xcodegen", "generate", "--spec", "project.yml"]
        completed = subprocess.CompletedProcess(arguments, 0, b"generated\n", b"warning\n")
        with patch.object(subprocess, "run", return_value=completed) as process:
            result = commands.run(arguments, "source-generation", timeout=7)
        self.assertIsInstance(result, subprocess.CompletedProcess)
        self.assertEqual((0, b"generated\n", b"warning\n"),
                         (result.returncode, result.stdout, result.stderr))
        self.assertEqual(7, process.call_args.kwargs["timeout"])

    def test_command_input_is_forwarded_and_archived_byte_for_byte(self):
        commands = self.commands()
        arguments = ["plutil", "-convert", "json", "-o", "-", "-"]
        for payload in [b'{ "fixture" = "input"; }\n', b""]:
            with self.subTest(payload=payload):
                completed = subprocess.CompletedProcess(arguments, 0, b"{}", b"")
                with patch.object(subprocess, "run", return_value=completed) as process:
                    commands.run(arguments, "conversion", input_bytes=payload)
                self.assertEqual(payload, process.call_args.kwargs["input"])
                records = json.loads((commands.output / "commands.json").read_text())
                stdin = records[-1]["stdin"]
                self.assertEqual(len(payload), stdin["bytes"])
                self.assertEqual(hashlib.sha256(payload).hexdigest(), stdin["sha256"])
                self.assertEqual(payload, (commands.output / stdin["file"]).read_bytes())

    def test_command_text_input_is_rejected_before_starting_a_subprocess(self):
        with patch.object(subprocess, "run", side_effect=AssertionError("Text input started a subprocess")) as process, \
                self.assertRaises(ValueError):
            self.commands().run(["plutil"], "conversion", input_bytes="not bytes")
        process.assert_not_called()

    def test_build_wrapper_rejects_stdin_before_starting_a_process(self):
        arguments = [sys.executable, str(runner.BUILD_WRAPPER), "xcodebuild", "build-for-testing"]
        with patch.object(subprocess, "Popen", side_effect=AssertionError("Wrapper with stdin was started")) as launch, \
                patch.object(subprocess, "run", side_effect=AssertionError("Wrapper with stdin escaped")) as direct, \
                self.assertRaises(ValueError):
            self.commands(allow_native=True).run(arguments, "serialized-build", timeout=None, input_bytes=b"input")
        launch.assert_not_called()
        direct.assert_not_called()

    def test_wrapper_interruption_waits_for_natural_completion_before_propagating(self):
        commands = self.commands(allow_native=True)
        arguments = [sys.executable, str(runner.BUILD_WRAPPER), "xcodebuild", "build-for-testing"]
        process = MagicMock()
        process.communicate.side_effect = [KeyboardInterrupt(), (b"out", b"err")]
        process.returncode = 0
        with patch.object(subprocess, "Popen", return_value=process) as launch, \
                patch.object(subprocess, "run", side_effect=AssertionError("A wrapped build used subprocess.run")) as direct, \
                self.assertRaises(runner.BuildInterrupted) as interrupted:
            commands.run(arguments, "serialized-build", check=False, timeout=None)
        launch.assert_called_once_with(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        direct.assert_not_called()
        self.assertEqual(2, process.communicate.call_count)
        self.assertTrue(all(call.kwargs.get("timeout") is None for call in process.communicate.call_args_list))
        process.kill.assert_not_called()
        process.terminate.assert_not_called()
        process.wait.assert_not_called()
        outcome = interrupted.exception.outcome
        self.assertEqual((0, b"out", b"err"), (outcome.returncode, outcome.stdout, outcome.stderr))
        records = json.loads((commands.output / "commands.json").read_text())
        self.assertEqual(1, len(records))
        self.assertTrue(records[0]["interruptionDeferred"])
        self.assertEqual(0, records[0]["exitCode"])
        self.assertIsNone(records[0]["timeoutSeconds"])
        self.assertEqual(b"out", (commands.output / records[0]["stdout"]["file"]).read_bytes())
        self.assertEqual(b"err", (commands.output / records[0]["stderr"]["file"]).read_bytes())

    def test_wrapper_rejects_an_outer_timeout_before_starting_a_process(self):
        commands = self.commands(allow_native=True)
        arguments = [sys.executable, str(runner.BUILD_WRAPPER), "xcodebuild", "build-for-testing"]
        with patch.object(subprocess, "Popen", side_effect=AssertionError("Timeout rejection started the wrapper")) as launch, \
                patch.object(subprocess, "run", side_effect=AssertionError("Timeout rejection started a subprocess")) as direct, \
                self.assertRaises(ValueError):
            commands.run(arguments, "serialized-build", check=False, timeout=1)
        launch.assert_not_called()
        direct.assert_not_called()

    def test_failed_command_can_be_recorded_without_becoming_success(self):
        commands = self.commands()
        arguments = ["xcodegen", "generate", "--spec", "project.yml"]
        completed = subprocess.CompletedProcess(arguments, 7, b"", b"invalid spec\n")
        with patch.object(subprocess, "run", return_value=completed):
            result = commands.run(arguments, "failed-generation", check=False)
        self.assertEqual(7, result.returncode)
        self.assertEqual(b"invalid spec\n", result.stderr)

    def test_resource_contract_contains_only_the_five_auth_inputs(self):
        self.assertEqual(RESOURCE_NAMES, set(runner.RESOURCE_NAMES))
        self.assertEqual(SELECTOR, runner.SELECTOR)

    def test_matching_resource_bytes_are_accepted(self):
        project, bundle = self.resource_pair()
        with patch.object(subprocess, "run", side_effect=AssertionError("Resource checks must be local")):
            result = runner.verify_bundled_resources(project, bundle)
        self.assertIsInstance(result, dict)
        self.assertEqual(RESOURCE_NAMES, set(result))

    def test_bundled_testinfo_must_identify_the_approved_main(self):
        project, bundle = self.resource_pair()
        (bundle / "Info.plist").write_bytes(plistlib.dumps({"TargetAppBundleIdentifier": "other.app"}))
        with self.assertRaises(ValueError):
            runner.verify_bundled_resources(project, bundle)

    def test_each_missing_resource_blocks_execution(self):
        project, bundle = self.resource_pair()
        for name in sorted(RESOURCE_NAMES):
            with self.subTest(resource=name):
                contents = (bundle / name).read_bytes()
                (bundle / name).unlink()
                try:
                    with self.assertRaises((OSError, RuntimeError, ValueError)):
                        runner.verify_bundled_resources(project, bundle)
                finally:
                    (bundle / name).write_bytes(contents)

    def test_valid_json_with_stale_bytes_is_rejected_for_every_resource(self):
        project, bundle = self.resource_pair()
        for name in sorted(RESOURCE_NAMES):
            with self.subTest(resource=name):
                original = (bundle / name).read_bytes()
                stale = original + b" \n"
                self.assertEqual(json.loads(original), json.loads(stale))
                (bundle / name).write_bytes(stale)
                try:
                    with self.assertRaises((RuntimeError, ValueError)):
                        runner.verify_bundled_resources(project, bundle)
                finally:
                    (bundle / name).write_bytes(original)


if __name__ == "__main__":
    unittest.main()
