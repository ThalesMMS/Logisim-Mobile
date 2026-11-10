import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "profile-android-circuit.py"
spec = importlib.util.spec_from_file_location("android_circuit_profile", SCRIPT)
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


def stat_line():
    fields = ["0"] * 22
    fields[0], fields[11], fields[12], fields[19], fields[21] = "S", "10", "20", "555", "100"
    return "123 (app (worker)) " + " ".join(fields)


FRAME_TEXT = """app/MainActivity/android.view.ViewRootImpl@abc (visibility=0)
Window: app/MainActivity
---PROFILEDATA---
Flags,IntendedVsync,FrameDeadline,FrameCompleted,GpuCompleted,
0,100,110,112,109,
1,200,210,215,215,
0,300,310,310,310,
0,400,410,9223372036854775807,409,
---PROFILEDATA---
"""


class AndroidPerformanceCaptureTest(unittest.TestCase):
    def test_parses_stat_with_parentheses_and_memory_output_versions(self):
        self.assertEqual({"cpu_ticks": 30, "start_ticks": 555, "rss_pages": 100}, profile.proc_stat(stat_line()))
        self.assertEqual({"pss_kib": 1234, "rss_kib": 5678, "swap_pss_kib": 9},
            profile.memory_totals("TOTAL PSS: 1234 TOTAL RSS: 5678 TOTAL SWAP PSS: 9"))
        self.assertEqual({"pss_kib": 42}, profile.memory_totals("  TOTAL 42 32 1 2 3\n"))
        self.assertEqual({}, profile.memory_totals("unsupported output"))

    def test_accepts_only_observed_valid_frames_and_uses_reported_deadlines(self):
        frames = profile.frame_rows(FRAME_TEXT)
        self.assertEqual([12, 10], [frame["completion_span_ns"] for frame in frames])
        self.assertEqual([110, 310], [frame["deadline_ns"] for frame in frames])
        self.assertEqual([109, 310], [frame["gpu_completed_ns"] for frame in frames])
        self.assertEqual([False, True], [frame["gpu_deadline_missed"] for frame in frames])
        two_renderers = profile.frame_rows(FRAME_TEXT + FRAME_TEXT.replace("@abc", "@def"))
        self.assertEqual(2, len({frame["renderer"] for frame in two_renderers}))
        self.assertEqual(12, profile.percentile([9, 12], .95))
        self.assertIsNone(profile.percentile([], .95))

    def test_cpu_timestamps_follow_the_counter_read_and_report_sampling_uncertainty(self):
        samples = [{"cpu_ticks": 0, "cpu_stat_before_ns": 0, "cpu_stat_after_ns": 2_000_000_000},
                   {"cpu_ticks": 400, "cpu_stat_before_ns": 4_000_000_000, "cpu_stat_after_ns": 6_000_000_000}]
        result = profile.cpu_summary(samples, 100)
        self.assertEqual(100, result["process_cpu_percent_one_core_estimate"])
        self.assertAlmostEqual(66.3333333333, result["process_cpu_percent_one_core_lower_bound"])
        self.assertEqual(201, result["process_cpu_percent_one_core_upper_bound"])
        self.assertEqual(2, result["process_cpu_quantization_allowance_ticks"])
        self.assertEqual({}, profile.cpu_summary(samples, None))
        samples[-1]["cpu_ticks"] = 0
        self.assertEqual(0, profile.cpu_summary(samples, 100)["process_cpu_percent_one_core_estimate"])
        self.assertGreater(profile.cpu_summary(samples, 100)["process_cpu_percent_one_core_upper_bound"], 0)

    def test_capture_is_passive_checks_artifact_and_preserves_raw_measurements(self):
        calls = []
        state = {"restart": False, "restart_on_meminfo": False, "unavailable": False, "multiple": False}
        def fake_run(command, **kwargs):
            calls.append(command)
            args = command[3:]
            if args[:3] == ["shell", "pm", "path"]:
                text = "package:/data/app/fake/base.apk\n"
            elif args[:2] == ["shell", "sha256sum"]:
                text = "a" * 64 + "  /data/app/fake/base.apk\n"
            elif args[:2] == ["shell", "pidof"]:
                text = "456\n" if state["restart"] else "123 456\n" if state["multiple"] else "123\n"
            elif args[:2] == ["shell", "getprop"]:
                text = "1\n" if args[-1] == "ro.kernel.qemu" else "fixture\n"
            elif args[:2] == ["shell", "getconf"]:
                text = "100\n"
            elif args[:2] == ["shell", "cat"]:
                if state["unavailable"]:
                    return subprocess.CompletedProcess(command, 1, "", "not readable")
                text = stat_line()
            elif args[:3] == ["shell", "dumpsys", "meminfo"]:
                if state["restart_on_meminfo"]:
                    state["restart"] = True
                text = "unsupported output" if state["unavailable"] else "TOTAL PSS: 1234 TOTAL RSS: 5678\n"
            elif args[-1] == "framestats":
                text = FRAME_TEXT
            else:
                text = "fixture\n"
            return subprocess.CompletedProcess(command, 0, text, "")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            circuit = root / "circuit.circ"
            circuit.write_text("fixture")
            output = root / "capture"
            arguments = [str(SCRIPT), "--adb", "fake-adb", "--serial", "fixture", "--package", "test.app",
                "--expected-apk-sha256", "a" * 64, "--build-commit", "new", "--base-commit", "base",
                "--fresh-launch-confirmed",
                "--scenario", "pan-zoom", "--circuit", str(circuit), "--seconds", "1", "--interval", ".2",
                "--output-dir", str(output)]
            with patch.object(sys, "argv", arguments), patch.object(profile.subprocess, "run", fake_run), \
                 patch.object(profile.platform, "platform", return_value="synthetic test host"), contextlib.redirect_stdout(io.StringIO()):
                profile.main()
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertIsNone(manifest["failure"])
            self.assertEqual(2, manifest["observed_frames"])
            self.assertEqual(1, manifest["observed_gpu_deadline_misses"])
            self.assertEqual("completed", manifest["status"])
            self.assertFalse(manifest["physical_energy_measured"])
            self.assertEqual(1234, manifest["samples"][0]["pss_kib"])
            self.assertEqual(FRAME_TEXT, (output / "gfxinfo-0000.txt").read_text())
            self.assertFalse(any("input" in command or "install" in command or "am" in command for command in calls))
            self.assertTrue(all(command[6] == "123" for command in calls if command[3:6] in
                (["shell", "dumpsys", "meminfo"], ["shell", "dumpsys", "gfxinfo"])))
            arguments[arguments.index("a" * 64)] = "b" * 64
            arguments[-1] = str(root / "wrong-build")
            with patch.object(sys, "argv", arguments), patch.object(profile.subprocess, "run", fake_run):
                with self.assertRaisesRegex(RuntimeError, "consolidated artifact"):
                    profile.main()
            self.assertFalse((root / "wrong-build").exists())
            arguments[arguments.index("b" * 64)] = "a" * 64
            arguments[-1] = str(root / "multiple")
            state["multiple"] = True
            with patch.object(sys, "argv", arguments), patch.object(profile.subprocess, "run", fake_run):
                with self.assertRaisesRegex(RuntimeError, "Multiple package processes"):
                    profile.main()
            self.assertFalse((root / "multiple").exists())
            state["multiple"] = False
            arguments[-1] = str(root / "no-reset")
            passive = arguments + ["--no-gfx-reset"]
            calls.clear()
            with patch.object(sys, "argv", passive), patch.object(profile.subprocess, "run", fake_run), \
                 patch.object(profile.platform, "platform", return_value="synthetic test host"), contextlib.redirect_stdout(io.StringIO()):
                profile.main()
            no_reset = json.loads((root / "no-reset/manifest.json").read_text())
            self.assertFalse(no_reset["gfxinfo_reset"])
            self.assertEqual(2, no_reset["initial_observed_frames_excluded"])
            self.assertEqual(0, no_reset["observed_frames"])
            self.assertFalse(any("reset" in command for command in calls))
            arguments[-1] = str(root / "restarted")
            state["restart_on_meminfo"] = True
            with patch.object(sys, "argv", arguments), patch.object(profile.subprocess, "run", fake_run), \
                 patch.object(profile.platform, "platform", return_value="synthetic test host"):
                with self.assertRaisesRegex(SystemExit, "Target process changed"):
                    profile.main()
            manifest = json.loads((root / "restarted/manifest.json").read_text())
            self.assertEqual("failed", manifest["status"])
            self.assertEqual([], manifest["samples"])
            self.assertEqual(0, manifest["observed_frames"])
            state.update(restart=False, restart_on_meminfo=False, unavailable=True)
            arguments[-1] = str(root / "unavailable")
            with patch.object(sys, "argv", arguments), patch.object(profile.subprocess, "run", fake_run), \
                 patch.object(profile.platform, "platform", return_value="synthetic test host"), contextlib.redirect_stdout(io.StringIO()):
                profile.main()
            manifest = json.loads((root / "unavailable/manifest.json").read_text())
            self.assertEqual("partial", manifest["status"])
            self.assertFalse(manifest["metric_availability"]["process_cpu"])
            self.assertEqual(0, manifest["metric_availability"]["pss_samples"])
            self.assertIn("memory_unavailable", manifest["samples"][0])


if __name__ == "__main__":
    unittest.main()
