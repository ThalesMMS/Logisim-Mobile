"""Exercise the simulator bootstrap CLI without accessing CoreSimulator."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci-boot-ios-simulator.py"
IOS_26_4 = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
IOS_26_3 = "com.apple.CoreSimulator.SimRuntime.iOS-26-3"
UUID_A = "10000000-0000-0000-0000-000000000001"
UUID_B = "10000000-0000-0000-0000-000000000002"
UUID_C = "10000000-0000-0000-0000-000000000003"
UUID_D = "10000000-0000-0000-0000-000000000004"


def runtime(identifier=IOS_26_4, version="26.4", available=True):
    return {"identifier": identifier, "version": version, "isAvailable": available}


def device(udid=UUID_A, name="iPhone 17", state="Shutdown", available=True):
    return {"udid": udid, "name": name, "state": state, "isAvailable": available}


def inventory(devices=None, runtimes=None):
    return {
        "devices": devices if devices is not None else {IOS_26_4: [device()]},
        "runtimes": runtimes if runtimes is not None else [runtime()],
    }


class SimulatorBootstrapCliTest(unittest.TestCase):
    def run_cli(self, source=None, *, mode="ready", timeout=3):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        binary = root / "bin"
        binary.mkdir()
        source_file = root / "inventory.json"
        source_file.write_text(json.dumps(source if source is not None else inventory()))
        commands = root / "commands.jsonl"
        diagnostics = root / "diagnostics.json"
        fake = binary / "xcrun"
        fake.write_text(
            f"#!{sys.executable}\n"
            "import json, os, pathlib, sys, time\n"
            "args = sys.argv[1:]\n"
            "with open(os.environ['FAKE_SIMCTL_COMMANDS'], 'a') as output:\n"
            "    output.write(json.dumps(args) + '\\n')\n"
            "mode = os.environ['FAKE_SIMCTL_MODE']\n"
            "if args == ['simctl', 'list', '--json']:\n"
            "    print(pathlib.Path(os.environ['FAKE_SIMCTL_INVENTORY']).read_text())\n"
            "elif len(args) == 4 and args[:2] == ['simctl', 'bootstatus'] and args[3] == '-b':\n"
            "    if mode == 'bootstatus-failure':\n"
            "        print('fake bootstatus failed: simulator backend unavailable', file=sys.stderr)\n"
            "        sys.exit(72)\n"
            "    if mode == 'bootstatus-timeout':\n"
            "        time.sleep(5)\n"
            "    print('fake bootstatus progress')\n"
            "else:\n"
            "    print('unexpected fake xcrun arguments: ' + repr(args), file=sys.stderr)\n"
            "    sys.exit(73)\n"
        )
        fake.chmod(0o755)
        env = os.environ.copy()
        env.update(
            PATH=f"{binary}:{env.get('PATH', '')}",
            FAKE_SIMCTL_INVENTORY=str(source_file),
            FAKE_SIMCTL_COMMANDS=str(commands),
            FAKE_SIMCTL_MODE=mode,
        )
        started = time.monotonic()
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--timeout", str(timeout), "--diagnostics", str(diagnostics)],
            env=env, capture_output=True, text=True, timeout=8,
        )
        elapsed = time.monotonic() - started
        calls = [json.loads(line) for line in commands.read_text().splitlines()] if commands.exists() else []
        report = json.loads(diagnostics.read_text()) if diagnostics.exists() else None
        return result, report, calls, elapsed

    def assert_ready(self, result, report, expected_uuid):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(expected_uuid + "\n", result.stdout)
        self.assertIsNotNone(report)
        self.assertEqual("ready", report["status"])
        self.assertEqual(expected_uuid, report["selection"]["udid"])
        self.assertIn("inventory", report)
        for key in ("name", "runtime", "state"):
            self.assertIn(key, report["selection"])

    def assert_error(self, result, report):
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertTrue(result.stderr.strip())
        self.assertIsNotNone(report)
        self.assertEqual("error", report["status"])
        self.assertTrue(report["error"])
        self.assertIn("inventory", report)

    def test_shutdown_device_is_started_and_waited_for_by_bootstatus_before_returning_its_uuid(self):
        result, report, calls, _ = self.run_cli()
        self.assert_ready(result, report, UUID_A)
        self.assertEqual([
            ["simctl", "list", "--json"],
            ["simctl", "bootstatus", UUID_A, "-b"],
        ], calls)

    def test_an_already_booted_device_is_preferred_over_a_newer_shutdown_runtime(self):
        source = inventory(
            {IOS_26_4: [device(UUID_A)], IOS_26_3: [device(UUID_B, state="Booted")]},
            [runtime(), runtime(IOS_26_3, "26.3")],
        )
        result, report, calls, _ = self.run_cli(source)
        self.assert_ready(result, report, UUID_B)
        self.assertEqual(IOS_26_3, report["selection"]["runtime"])
        self.assertEqual([
            ["simctl", "list", "--json"],
            ["simctl", "bootstatus", UUID_B, "-b"],
        ], calls)

    def test_newest_runtime_is_selected_before_the_device_name(self):
        source = inventory(
            {IOS_26_3: [device(UUID_A, name="iPhone 15")], IOS_26_4: [device(UUID_B, name="iPhone 17")]},
            [runtime(IOS_26_3, "26.3"), runtime()],
        )
        result, report, _, _ = self.run_cli(source)
        self.assert_ready(result, report, UUID_B)

    def test_runtime_versions_are_compared_numerically(self):
        older = "com.apple.CoreSimulator.SimRuntime.iOS-26-9"
        newer = "com.apple.CoreSimulator.SimRuntime.iOS-26-10"
        source = inventory(
            {older: [device(UUID_A)], newer: [device(UUID_B)]},
            [runtime(older, "26.9"), runtime(newer, "26.10")],
        )
        result, report, _, _ = self.run_cli(source)
        self.assert_ready(result, report, UUID_B)

    def test_non_ios_runtimes_are_ignored_even_when_their_devices_are_booted(self):
        watch = "com.apple.CoreSimulator.SimRuntime.watchOS-27-0"
        tv = "com.apple.CoreSimulator.SimRuntime.tvOS-27-0"
        source = inventory(
            {watch: [device(UUID_B, name="Apple Watch", state="Booted")],
             tv: [device(UUID_C, name="Apple TV", state="Booted")], IOS_26_4: [device(UUID_A)]},
            [runtime(watch, "27.0"), runtime(tv, "27.0"), runtime()],
        )
        result, report, _, _ = self.run_cli(source)
        self.assert_ready(result, report, UUID_A)

    def test_unavailable_devices_and_runtimes_are_ignored(self):
        source = inventory(
            {IOS_26_4: [device(UUID_A, state="Booted", available=False)],
             IOS_26_3: [device(UUID_B, state="Booted")],
             "com.apple.CoreSimulator.SimRuntime.iOS-26-2": [device(UUID_C)]},
            [runtime(), runtime(IOS_26_3, "26.3", available=False),
             runtime("com.apple.CoreSimulator.SimRuntime.iOS-26-2", "26.2")],
        )
        result, report, _, _ = self.run_cli(source)
        self.assert_ready(result, report, UUID_C)

    def test_invalid_uuid_and_transitional_states_are_not_selected(self):
        source = inventory({IOS_26_4: [
            device("not-a-uuid", state="Booted"),
            device(UUID_B, state="Booting"),
            device(UUID_A),
        ]})
        result, report, _, _ = self.run_cli(source)
        self.assert_ready(result, report, UUID_A)

    def test_iphone_name_and_uuid_ties_are_deterministic_independent_of_inventory_order(self):
        candidates = [
            device(UUID_D, name="iPad Pro"),
            device(UUID_C, name="iPhone 17 Pro"),
            device(UUID_B, name="iPhone 17"),
            device(UUID_A, name="iPhone 17"),
        ]
        for ordered in (candidates, list(reversed(candidates))):
            with self.subTest(order=[item["udid"] for item in ordered]):
                result, report, _, _ = self.run_cli(inventory({IOS_26_4: ordered}))
                self.assert_ready(result, report, UUID_A)

    def test_no_available_ios_device_fails_with_diagnostics_and_does_not_boot(self):
        result, report, calls, _ = self.run_cli(inventory({IOS_26_4: [device(available=False)]}))
        self.assert_error(result, report)
        self.assertIn("ios", result.stderr.lower())
        self.assertEqual([["simctl", "list", "--json"]], calls)

    def test_bootstatus_failure_preserves_the_selected_device_in_diagnostics(self):
        result, report, calls, _ = self.run_cli(mode="bootstatus-failure")
        self.assert_error(result, report)
        self.assertIn("bootstatus", result.stderr.lower())
        self.assertEqual(UUID_A, report["selection"]["udid"])
        self.assertEqual(["simctl", "bootstatus", UUID_A, "-b"], calls[-1])

    def test_bootstatus_timeout_is_bounded_and_saved_in_diagnostics(self):
        result, report, calls, elapsed = self.run_cli(mode="bootstatus-timeout", timeout=1)
        self.assert_error(result, report)
        self.assertIn("tim", result.stderr.lower())
        self.assertIn("tim", report["error"].lower())
        self.assertEqual(UUID_A, report["selection"]["udid"])
        self.assertEqual(["simctl", "bootstatus", UUID_A, "-b"], calls[-1])
        self.assertLess(elapsed, 4, "The fake five-second boot must be interrupted by the one-second timeout")

    def test_invalid_timeout_is_rejected_before_querying_simulators(self):
        for value in (0, -1, "invalid"):
            with self.subTest(timeout=value):
                result, _, calls, _ = self.run_cli(timeout=value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("timeout", result.stderr.lower())
                self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
