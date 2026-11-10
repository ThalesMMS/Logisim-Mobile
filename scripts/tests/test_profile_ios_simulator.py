"""Synthetic fixtures only. These tests never query a Simulator or process."""
import argparse
import ctypes
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import plistlib
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "profile-ios-simulator.py"
SPEC = importlib.util.spec_from_file_location("profile_ios_simulator", SCRIPT)
profile = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = profile
SPEC.loader.exec_module(profile)

UDID = "11111111-2222-3333-4444-555555555555"
UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"


def snapshot(user=0, system=0, start=100, executable_uuid=UUID, exit_ticks=0, rss=1000, footprint=2000):
    return profile.Snapshot(executable_uuid, start, exit_ticks, user, system, rss, footprint)


class ProfileIosSimulatorTest(unittest.TestCase):
    def test_native_abi_matches_v0_sdk_layout(self):
        self.assertEqual(96, ctypes.sizeof(profile.RUsageInfoV0))
        self.assertEqual(16, profile.RUsageInfoV0.ri_user_time.offset)
        self.assertEqual(64, profile.RUsageInfoV0.ri_resident_size.offset)
        self.assertEqual(72, profile.RUsageInfoV0.ri_phys_footprint.offset)
        self.assertEqual(80, profile.RUsageInfoV0.ri_proc_start_abstime.offset)

    def test_cpu_uses_mach_timebase_and_all_threads(self):
        first = snapshot(user=24_000_000)
        current = snapshot(user=96_000_000, system=24_000_000)
        row = profile.sample_row(current, first, first, 2_000_000_000, 1_000_000_000,
                                 1_000_000_000, 125, 3)
        self.assertEqual(5_000_000_000, row["cpu_total_ns"])
        self.assertEqual(400.0, row["cpu_interval_percent_one_core"])
        self.assertEqual(1000, row["rss_bytes"])
        self.assertEqual(2000, row["physical_footprint_bytes"])
        self.assertEqual(10**24 * 125 // 3, profile.cpu_nanoseconds(10**24, 125, 3))

    def test_reused_pid_changed_executable_zombie_and_counter_reset_are_rejected(self):
        first = snapshot(user=10, system=10)
        for invalid in (snapshot(start=101), snapshot(executable_uuid="ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"),
                        snapshot(exit_ticks=200), snapshot(user=9, system=20)):
            with self.subTest(invalid=invalid), self.assertRaises(profile.CaptureError):
                profile.verify_snapshot(invalid, first, first)

    def test_summary_keeps_peak_rss_and_footprint_separate(self):
        first = snapshot()
        rows = [profile.sample_row(snapshot(user=n * 24_000_000, rss=rss, footprint=footprint),
                                    first, None, n * 1_000_000_000, None, 0, 125, 3)
                for n, rss, footprint in ((0, 100, 500), (1, 300, 400), (2, 200, 600))]
        result = profile.summarize(rows)
        self.assertEqual(100.0, result["cpu_percent_one_core"])
        self.assertEqual(2.0, result["cpu_seconds"])
        self.assertEqual({"median": 200, "p95_nearest_rank": 300, "maximum": 300}, result["rss_bytes"])
        self.assertEqual(600, result["physical_footprint_bytes"]["maximum"])

    def test_cli_requires_explicit_device_target_and_expected_artifact(self):
        base = ["--udid", UDID, "--pid", "123", "--expected-executable-sha256", "a" * 64,
                "--expected-bundle-sha256", "b" * 64, "--fresh-launch-confirmed",
                "--build-id", "artifact-A", "--build-commit", "HEAD", "--base-commit", "HEAD",
                "--scenario", "idle", "--circuit", "/tmp/fixture-corpus.circ", "--output-dir", "/tmp/unused-profile-fixture"]
        self.assertEqual(123, profile.arguments(base).pid)
        without_circuit = base.copy()
        index = without_circuit.index("--circuit")
        del without_circuit[index:index + 2]
        with patch("sys.stderr"), self.assertRaises(SystemExit):
            profile.arguments(without_circuit)
        for key, value in (("--udid", "booted"), ("--pid", "0"),
                           ("--expected-executable-sha256", "old-build"), ("--duration-seconds", "nan")):
            invalid = base.copy()
            if key in invalid:
                invalid[invalid.index(key) + 1] = value
            else:
                invalid.extend((key, value))
            with self.subTest(key=key), patch("sys.stderr"), self.assertRaises(SystemExit):
                profile.arguments(invalid)

    def fixture(self, directory):
        device_set = directory / "Devices"
        root = device_set / UDID
        app = root / "data/Containers/Bundle/Application/fixture/Logisim.app"
        app.mkdir(parents=True)
        (root / "device.plist").write_bytes(plistlib.dumps({"name": "Fixture iPad", "runtime": "fixture-runtime",
                                                           "deviceType": "fixture-ipad"}))
        (app / "Info.plist").write_bytes(plistlib.dumps({"CFBundleExecutable": "Logisim", "CFBundleIdentifier": "dev.logisim.mobile"}))
        executable = app / "Logisim"
        executable.write_bytes(b"synthetic-artifact-not-a-runtime-binary")
        output = directory / "output"
        output.mkdir()
        circuit = directory / "corpus.circ"
        circuit.write_bytes(b"<project/>")
        args = argparse.Namespace(udid=UDID, device_set=device_set, pid=123, bundle_id=None,
            build_commit="HEAD", base_commit="HEAD", expected_executable_sha256=profile.sha256(executable),
            expected_bundle_sha256=profile.bundle_fingerprint(app)[0],
            duration_seconds=1, interval_seconds=.5, output_dir=output, circuit=circuit)
        return args, executable

    def run_fixture_capture(self, args, executable, loaded_uuid=UUID):
        class FakeLibproc:
            numer, denom = 125, 3

            def __init__(self):
                self.user = 0

            def executable(self, pid):
                return executable.resolve()

            def snapshot(self, pid):
                self.user += 24_000_000
                return snapshot(user=self.user, executable_uuid=loaded_uuid)

        def fake_command(command, history, cwd=None):
            history.append({"argv": command})
            if command[0] == "git":
                return "" if command[1] == "status" else "a" * 40
            if command[:3] == ["xcrun", "dwarfdump", "--uuid"]:
                return f"UUID: {UUID.upper()} (arm64) {executable}"
            if command[:2] == ["xcrun", "simctl"]:
                return str(executable.parent)
            if command == ["ps", "-axo", "pid="]:
                return "123"
            if command[0] == "ps":
                self.fail("The sampler must not query process arguments")
            self.fail(f"Unexpected command, including any runtime interaction: {command}")

        manifest, rows = {"commands": []}, []
        ticks = iter(range(0, 10_000_000_000, 250_000_000))
        with patch.object(profile, "Libproc", FakeLibproc), patch.object(profile, "run_command", fake_command), \
             patch.object(profile.time, "monotonic_ns", lambda: next(ticks)), patch.object(profile.time, "sleep"):
            profile.capture(args, manifest, rows)
        return manifest, rows

    def test_capture_preserves_verified_target_identity_and_raw_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            manifest, rows = self.run_fixture_capture(args, executable)
            self.assertEqual(123, manifest["process"]["pid"])
            self.assertEqual(UUID, manifest["process"]["loaded_uuid"])
            self.assertEqual(args.expected_executable_sha256, manifest["process"]["sha256"])
            self.assertTrue(manifest["artifact_verified_after_capture"])
            self.assertEqual(str(executable.resolve()), manifest["process"]["executable"])
            self.assertNotIn("command_line", manifest["process"])
            self.assertIs(False, manifest["process"]["arguments_collected"])
            self.assertEqual(2, len(rows))
            self.assertIn("physical_footprint_bytes", (args.output_dir / "samples.csv").read_text())
            self.assertEqual("mach_absolute_time_ticks", manifest["timebase"]["raw_cpu_unit"])
            self.assertEqual(str(args.circuit.resolve()), manifest["circuit"]["path"])
            self.assertEqual(hashlib.sha256(b"<project/>").hexdigest(), manifest["circuit"]["sha256"])
            self.assertEqual(10, manifest["circuit"]["size_bytes"])
            self.assertEqual("operator declaration; the app's open document is not inspected", manifest["circuit"]["provenance"])

    def test_process_discovery_enumerates_pids_without_querying_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            args.pid = None
            args.bundle_id = "dev.logisim.mobile"
            manifest, _ = self.run_fixture_capture(args, executable)
            ps_calls = [entry["argv"] for entry in manifest["commands"] if entry["argv"][0] == "ps"]
            self.assertEqual([["ps", "-axo", "pid="]], ps_calls)
            self.assertEqual(123, manifest["process"]["pid"])
            self.assertNotIn("command_line", manifest["process"])
            self.assertIs(False, manifest["process"]["arguments_collected"])

    def test_capture_rejects_stale_artifact_hash_and_loaded_uuid(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            args.expected_executable_sha256 = "0" * 64
            with self.assertRaisesRegex(profile.CaptureError, "SHA-256"):
                self.run_fixture_capture(args, executable)
            args.expected_executable_sha256 = profile.sha256(executable)
            with self.assertRaisesRegex(profile.CaptureError, "loaded executable UUID"):
                self.run_fixture_capture(args, executable, "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee")

    def test_another_device_cannot_be_profiled_by_name_or_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            other_device = args.device_set / "99999999-2222-3333-4444-555555555555"
            (other_device / "data").mkdir(parents=True)
            with self.assertRaisesRegex(profile.CaptureError, "selected Simulator"):
                profile.app_identity(executable, other_device)

    def test_malformed_app_metadata_has_a_capture_error(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            for invalid in ([], {}, {"CFBundleExecutable": 123},
                            {"CFBundleExecutable": "../Logisim", "CFBundleIdentifier": "dev.logisim.mobile"},
                            {"CFBundleExecutable": "Logisim", "CFBundleIdentifier": "dev.logisim.mobile", "CFBundleVersion": 123}):
                (executable.parent / "Info.plist").write_bytes(plistlib.dumps(invalid))
                with self.subTest(invalid=invalid), self.assertRaises(profile.CaptureError):
                    profile.app_identity(executable, args.device_set / UDID)

    def test_bundle_digest_ignores_location_and_mtime_but_tracks_resources_and_internal_links(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            app = executable.parent
            resource = app / "Resources/data.txt"
            resource.parent.mkdir()
            resource.write_text("first payload")
            (app / "resource-alias").symlink_to("Resources/data.txt")
            (app / "directory-alias").symlink_to("Resources", target_is_directory=True)
            digest, entries = profile.bundle_fingerprint(app)
            os.utime(resource, (1, 1))
            self.assertEqual(digest, profile.bundle_fingerprint(app)[0])
            renamed = app.with_name("Relocated.app")
            app.rename(renamed)
            self.assertEqual(digest, profile.bundle_fingerprint(renamed)[0])
            alias = next(entry for entry in entries if entry["path"] == "resource-alias")
            self.assertEqual("Resources/data.txt", alias["symlink_target"])
            self.assertEqual(profile.sha256(renamed / "Resources/data.txt"), alias["sha256"])
            (renamed / "Resources/data.txt").write_text("changed payload")
            self.assertNotEqual(digest, profile.bundle_fingerprint(renamed)[0])

    def test_bundle_digest_rejects_external_broken_cyclic_and_special_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            app = executable.parent
            outside = Path(directory) / "outside.txt"
            outside.write_text("outside content")
            link = app / "invalid-link"
            for target in (outside, app / "missing", link):
                link.symlink_to(target)
                with self.subTest(target=target), self.assertRaises(profile.CaptureError):
                    profile.bundle_fingerprint(app)
                link.unlink()
            os.mkfifo(app / "fifo")
            with self.assertRaisesRegex(profile.CaptureError, "special file"):
                profile.bundle_fingerprint(app)

    def test_bundle_digest_never_silently_omits_an_unreadable_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            resource = executable.parent / "Resources"
            resource.mkdir()
            (resource / "hidden.txt").write_text("must be included in the artifact")
            resource = resource.resolve()
            original_scandir = os.scandir

            def scandir(path):
                if Path(path) == resource:
                    raise PermissionError("fixture prevents directory traversal")
                return original_scandir(path)

            with patch.object(profile.os, "scandir", scandir), self.assertRaisesRegex(profile.CaptureError, "complete app bundle"):
                profile.bundle_fingerprint(executable.parent)

    def test_hash_utility_never_calls_libproc_or_subprocess(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            output = io.StringIO()
            with patch.object(profile, "Libproc", side_effect=AssertionError("No process calls")), \
                 patch.object(profile.subprocess, "run", side_effect=AssertionError("No commands")), patch("sys.stdout", output):
                result = profile.main(["--hash-app", str(executable.parent)])
            self.assertEqual(0, result)
            value = json.loads(output.getvalue())
            self.assertEqual(args.expected_bundle_sha256, value["bundle_sha256"])
            self.assertEqual(args.expected_executable_sha256, value["executable_sha256"])

    def test_capture_rejects_changed_bundle_even_when_main_executable_is_identical(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            (executable.parent / "new-resource.txt").write_text("resource from a different artifact")
            with self.assertRaisesRegex(profile.CaptureError, "Bundle SHA-256"):
                self.run_fixture_capture(args, executable)

    def test_failed_capture_preserves_failure_manifest_and_leaves_app_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            args, executable = self.fixture(Path(directory))
            output = Path(directory) / "failed-run"
            argv = ["--udid", UDID, "--pid", "123", "--expected-executable-sha256", args.expected_executable_sha256,
                    "--expected-bundle-sha256", args.expected_bundle_sha256, "--fresh-launch-confirmed",
                    "--build-id", "fixture", "--build-commit", "HEAD", "--base-commit", "HEAD",
                    "--scenario", "idle", "--circuit", str(args.circuit), "--output-dir", str(output)]
            original = executable.read_bytes()
            with patch.object(profile.sys, "platform", "darwin"), patch.object(profile, "capture", side_effect=profile.CaptureError("invalid metadata")), \
                 patch.object(profile.os, "getloadavg", side_effect=[(1, 2, 3), (4, 5, 6)]), \
                 patch("sys.stdout", io.StringIO()), patch("sys.stderr", io.StringIO()):
                self.assertEqual(1, profile.main(argv))
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual("failed", manifest["status"])
            self.assertEqual("invalid metadata", manifest["error"])
            self.assertFalse(manifest["energy_measured"])
            self.assertEqual([1, 2, 3], manifest["host"]["load_average_start"])
            self.assertEqual([4, 5, 6], manifest["host"]["load_average_end"])
            self.assertEqual(original, executable.read_bytes())


if __name__ == "__main__":
    unittest.main()
