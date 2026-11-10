#!/usr/bin/env python3
"""Check APK identity and build receipts before any Android device access."""

from contextlib import ExitStack, redirect_stderr
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parent
PACKAGE = "thalesmms.logisim.mobile.e2e"
APP_MANIFEST = '''N: android=http://schemas.android.com/apk/res/android (line=1)
  E: manifest (line=1)
    A: package="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")
    E: application (line=2)
      A: http://schemas.android.com/apk/res/android:label(0x01010001)="Logisim Mobile" (Raw: "Logisim Mobile")
'''
TEST_MANIFEST = '''N: android=http://schemas.android.com/apk/res/android (line=1)
  E: manifest (line=1)
    A: package="thalesmms.logisim.mobile.e2e.test" (Raw: "thalesmms.logisim.mobile.e2e.test")
    E: application (line=2)
    E: instrumentation (line=3)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.logisim.mobile.documents.FixtureInstrumentation" (Raw: "dev.logisim.mobile.documents.FixtureInstrumentation")
      A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")
'''
SNAPSHOT_MANIFEST = '''N: android=http://schemas.android.com/apk/res/android (line=1)
  E: manifest (line=1)
    A: package="thalesmms.logisim.mobile.snapshot.e2e.test" (Raw: "thalesmms.logisim.mobile.snapshot.e2e.test")
    E: application (line=2)
    E: instrumentation (line=3)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="dev.logisim.mobile.documents.UiSnapshotSeriesInstrumentation" (Raw: "dev.logisim.mobile.documents.UiSnapshotSeriesInstrumentation")
      A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="thalesmms.logisim.mobile.snapshot.e2e.test" (Raw: "thalesmms.logisim.mobile.snapshot.e2e.test")
'''


def forbid_process(*args, **kwargs):
    raise AssertionError("Artifact preflight tests must not launch processes")


def load_runner():
    spec = importlib.util.spec_from_file_location("android_artifact_preflight_runner", SCRIPTS / "e2e-android.py")
    runner = importlib.util.module_from_spec(spec)
    with ExitStack() as guards:
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            guards.enter_context(patch.object(subprocess, name, side_effect=forbid_process))
        guards.enter_context(patch.object(sys, "path", [str(SCRIPTS), *sys.path]))
        spec.loader.exec_module(runner)
    return runner


RUNNER = load_runner()


class ArtifactPreflightTests(unittest.TestCase):
    def setUp(self):
        guards = ExitStack()
        self.addCleanup(guards.close)
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            guards.enter_context(patch.object(subprocess, name, side_effect=forbid_process))
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def artifacts(self, directory=None):
        directory = directory or self.root / "artifacts"
        directory.mkdir(parents=True)
        paths = tuple(directory / name for name in ("androidApp-e2e.apk", "androidApp-e2e-androidTest.apk", "androidSnapshot-debug.apk"))
        for path, content in zip(paths, (b"external editor apk", b"external test apk", b"external snapshot apk")):
            path.write_bytes(content)
        return paths

    def aapt2(self):
        path = self.root / "aapt2"
        path.write_text("not executed by these tests\n")
        path.chmod(0o755)
        return path

    def receipt(self, app, **updates):
        data = {"appSha256": hashlib.sha256(app.read_bytes()).hexdigest(), "gitHead": "a" * 40}
        data.update(updates)
        path = self.root / "build-receipt.json"
        path.write_text(json.dumps(data) + "\n")
        return path

    def manifest_output(self, paths, manifests=None):
        manifests = manifests or (APP_MANIFEST, TEST_MANIFEST, SNAPSHOT_MANIFEST)
        by_path = dict(zip(map(str, paths), manifests))

        def output(command, **kwargs):
            self.assertEqual(["dump", "xmltree"], command[1:3])
            self.assertEqual(["--file", "AndroidManifest.xml"], command[4:])
            self.assertIn(command[3], by_path)
            return by_path[command[3]]

        return output

    def test_artifact_paths_preserve_defaults_and_support_three_flat_external_apks(self):
        self.assertEqual((RUNNER.ROOT / "androidApp/build/outputs/apk/e2e/androidApp-e2e.apk",
                          RUNNER.ROOT / "androidApp/build/outputs/apk/androidTest/e2e/androidApp-e2e-androidTest.apk",
                          RUNNER.ROOT / "androidSnapshot/build/outputs/apk/debug/androidSnapshot-debug.apk"), RUNNER.artifact_paths())
        external = self.artifacts()
        self.assertEqual(external, RUNNER.artifact_paths(external[0].parent))

    def test_aapt2_discovery_orders_sdk_versions_numerically(self):
        sdk = self.root / "sdk"
        for version in ("9.0.0", "36.0.0"):
            binary = sdk / "build-tools" / version / "aapt2"
            binary.parent.mkdir(parents=True)
            binary.write_text("not executed\n")
            binary.chmod(0o755)
        self.assertEqual(sdk / "build-tools/36.0.0/aapt2", RUNNER.find_aapt2(sdk))
        override = self.aapt2()
        self.assertEqual(override, RUNNER.find_aapt2(sdk, override))

    def test_manifest_parser_reads_full_android_attribute_uris(self):
        self.assertEqual({"package": "thalesmms.logisim.mobile.e2e", "instrumentations": []},
                         RUNNER.parse_apk_manifest(APP_MANIFEST))
        self.assertEqual({"package": "thalesmms.logisim.mobile.e2e.test", "instrumentations": [
            {"name": "dev.logisim.mobile.documents.FixtureInstrumentation", "targetPackage": "thalesmms.logisim.mobile.e2e"}]},
            RUNNER.parse_apk_manifest(TEST_MANIFEST))

    def test_nested_attributes_and_instrumentation_cannot_replace_direct_manifest_identity(self):
        nested = APP_MANIFEST + '      A: package="foreign.package" (Raw: "foreign.package")\n'
        self.assertEqual({"package": "thalesmms.logisim.mobile.e2e", "instrumentations": []}, RUNNER.parse_apk_manifest(nested))
        nested += '''      E: instrumentation (line=4)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="ForeignRunner" (Raw: "ForeignRunner")
        A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="foreign.package" (Raw: "foreign.package")
'''
        with self.assertRaises(ValueError):
            RUNNER.parse_apk_manifest(nested)

    def test_manifest_parser_rejects_duplicate_or_nonliteral_identity(self):
        cases = (
            APP_MANIFEST.replace('    E: application', '    A: package="foreign.package" (Raw: "foreign.package")\n    E: application'),
            APP_MANIFEST.replace('package="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")',
                                 'package=(type 0x1)0x7f010001'),
            TEST_MANIFEST + '      A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="foreign.package" (Raw: "foreign.package")\n',
            TEST_MANIFEST.replace('targetPackage(0x01010021)="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")',
                                  'targetPackage(0x01010021)=(type 0x1)0x7f010002'),
            TEST_MANIFEST.replace('name(0x01010003)="dev.logisim.mobile.documents.FixtureInstrumentation" (Raw: "dev.logisim.mobile.documents.FixtureInstrumentation")',
                                  'name(0x01010003)=(type 0x1)0x7f010003'),
        )
        for manifest in cases:
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                RUNNER.parse_apk_manifest(manifest)

    def test_all_three_apk_manifests_are_read_and_recorded_before_device_access(self):
        paths = self.artifacts()
        binary = self.aapt2()
        with patch.object(subprocess, "check_output", side_effect=self.manifest_output(paths)) as output:
            identities = RUNNER.validate_apk_manifests(binary, *paths, PACKAGE)
        self.assertEqual({"app", "test", "snapshot"}, set(identities))
        self.assertEqual("thalesmms.logisim.mobile.e2e", identities["app"]["package"])
        self.assertEqual("thalesmms.logisim.mobile.e2e.test", identities["test"]["package"])
        self.assertEqual("thalesmms.logisim.mobile.snapshot.e2e.test", identities["snapshot"]["package"])
        self.assertEqual("dev.logisim.mobile.documents.UiSnapshotSeriesInstrumentation", identities["snapshot"]["instrumentations"][0]["name"])
        self.assertEqual([[str(binary), "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"] for path in paths],
                         [call.args[0] for call in output.call_args_list])

    def test_apk_validation_rejects_normal_app_test_target_and_foreign_helper_target(self):
        paths = self.artifacts()
        cases = (
            (APP_MANIFEST.replace("thalesmms.logisim.mobile.e2e", "thalesmms.logisim.mobile"), TEST_MANIFEST, SNAPSHOT_MANIFEST),
            (APP_MANIFEST, TEST_MANIFEST.replace('targetPackage(0x01010021)="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")',
                                                'targetPackage(0x01010021)="android" (Raw: "android")'), SNAPSHOT_MANIFEST),
            (APP_MANIFEST, TEST_MANIFEST, SNAPSHOT_MANIFEST.replace('targetPackage(0x01010021)="thalesmms.logisim.mobile.snapshot.e2e.test" (Raw: "thalesmms.logisim.mobile.snapshot.e2e.test")',
                                                                  'targetPackage(0x01010021)="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")')),
        )
        for manifests in cases:
            with self.subTest(manifests=manifests), \
                    patch.object(subprocess, "check_output", side_effect=self.manifest_output(paths, manifests)), \
                    self.assertRaises(ValueError):
                RUNNER.validate_apk_manifests(self.aapt2(), *paths, PACKAGE)

    def test_build_receipt_returns_original_bytes_and_rejects_mismatched_or_invalid_identity(self):
        app, _, _ = self.artifacts()
        digest = hashlib.sha256(app.read_bytes()).hexdigest()
        path = self.receipt(app)
        receipt, raw = RUNNER.load_build_receipt(path, digest)
        self.assertEqual({"appSha256": digest, "gitHead": "a" * 40}, receipt)
        self.assertEqual(path.read_bytes(), raw)
        for updates in ({"appSha256": "b" * 64}, {"appSha256": "not-a-sha"}, {"gitHead": "a" * 39},
                        {"gitHead": "g" * 40}, {"gitHead": 1111111111111111111111111111111111111111}):
            with self.subTest(updates=updates), self.assertRaises(ValueError):
                RUNNER.load_build_receipt(self.receipt(app, **updates), digest)
        with self.assertRaises(ValueError):
            RUNNER.load_build_receipt(self.receipt(app, appSha256="not-a-sha"), "not-a-sha")
        with self.assertRaises(OSError):
            RUNNER.load_build_receipt(self.root / "missing-receipt.json", digest)

    def assert_main_rejects_before_android(self, case, *, receipt_updates=None, manifests=None, receipt_present=True):
        repo = self.root / case
        repo.mkdir()
        (repo / "app.properties").write_text("app.androidId=thalesmms.logisim.mobile\n")
        local_metadata = repo / "androidApp/build/outputs/apk/e2e/output-metadata.json"
        local_metadata.parent.mkdir(parents=True)
        local_metadata.write_text('{"applicationId":"thalesmms.logisim.mobile.e2e"}\n')
        paths = self.artifacts(repo / "external")
        receipt = self.receipt(paths[0], **(receipt_updates or {}))
        arguments = ["e2e-android.py", "--serial", "emulator-5556", "--scenario", "editing", "--artifacts", str(paths[0].parent),
                     "--aapt2", str(self.aapt2()), "--output", str(repo / "output")]
        if receipt_present:
            arguments.extend(["--build-receipt", str(receipt)])
        with patch.object(RUNNER, "ROOT", repo), patch.object(sys, "argv", arguments), \
                patch.object(RUNNER, "AndroidUi") as ui, \
                patch.object(subprocess, "check_output", side_effect=self.manifest_output(paths, manifests)), \
                redirect_stderr(io.StringIO()), self.assertRaises((ValueError, SystemExit, OSError)):
            RUNNER.main()
        ui.assert_not_called()

    def test_external_receipt_is_required_and_must_match_before_android_construction(self):
        self.assert_main_rejects_before_android("missing-receipt", receipt_present=False)
        self.assert_main_rejects_before_android("mismatched-receipt", receipt_updates={"appSha256": "b" * 64})

    def test_normal_apk_is_rejected_despite_local_e2e_metadata_before_android_construction(self):
        self.assert_main_rejects_before_android("normal-apk", manifests=(
            APP_MANIFEST.replace("thalesmms.logisim.mobile.e2e", "thalesmms.logisim.mobile"), TEST_MANIFEST, SNAPSHOT_MANIFEST))

    def test_foreign_test_and_helper_targets_are_rejected_before_android_construction(self):
        self.assert_main_rejects_before_android("foreign-test", manifests=(APP_MANIFEST,
            TEST_MANIFEST.replace('targetPackage(0x01010021)="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")',
                                  'targetPackage(0x01010021)="android" (Raw: "android")'), SNAPSHOT_MANIFEST))
        self.assert_main_rejects_before_android("foreign-helper", manifests=(APP_MANIFEST, TEST_MANIFEST,
            SNAPSHOT_MANIFEST.replace('targetPackage(0x01010021)="thalesmms.logisim.mobile.snapshot.e2e.test" (Raw: "thalesmms.logisim.mobile.snapshot.e2e.test")',
                                      'targetPackage(0x01010021)="thalesmms.logisim.mobile.e2e" (Raw: "thalesmms.logisim.mobile.e2e")')))


if __name__ == "__main__":
    unittest.main()
