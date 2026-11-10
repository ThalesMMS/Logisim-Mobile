import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "summarize-android-readiness.py"
SPEC = importlib.util.spec_from_file_location("readiness_summary", SCRIPT)
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


def stat(pid=42, start=100, user=9, system=11, threads=1):
    return (f"{pid} (worker ) name) R 1 1 0 0 -1 4194624 123 0 7 0 {user} {system} "
            f"0 0 10 -10 {threads} 0 {start} 123456 100 0 0 0")


def probe(*, start=100, user=9, system=11, vector=False, read=1000, written=2000):
    return (f"PIDS:42\nPROCESS:42\n{stat(start=start, user=user, system=system)}\n"
            f"rchar: 3000\nwchar: 4000\nread_bytes: {read}\nwrite_bytes: {written}\nVmRSS:\t120 kB\n" +
            ("THREADS:42\n" if vector else "THREAD:/proc/42/task/42\n") +
            stat(start=start, user=user, system=system) + ("\n" if vector else "\nfutex_wait_queue\n"))


class AndroidReadinessSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.root = self.base / "raw"
        (self.root / "passive").mkdir(parents=True)
        self.write("passive/receipt.json", json.dumps({"sourceRevision": "a" * 40, "hostLoad": "capture load",
            "arguments": {"expected_apk_sha256": "b" * 64},
            "apkBefore": {"sha256": "b" * 64}, "apkAfter": {"sha256": "b" * 64}}))
        self.write("events.json", json.dumps([
            {"event": "launch-request", "monotonicNs": 0},
            {"event": "am-start-returned", "monotonicNs": 10_000_000_000},
            {"event": "hierarchy", "monotonicNs": 33_700_000_000, "elapsedSeconds": 33.64, "loading": True},
            {"event": "sigquit-loading", "monotonicNs": 35_000_000_000},
            {"event": "editor-timeout", "monotonicNs": 40_600_000_000}]))
        self.write("guest-hz.txt", "100\n\nSTDERR\n")
        self.write("guest-vcpus.txt", "2\n\nSTDERR\n")
        self.write("host-uptime.txt", "post-capture load\n\nSTDERR\n")

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def samples(self, raws, windows=None):
        windows = windows or [(index * 10, index * 10 + 2) for index in range(len(raws))]
        metadata = []
        for index, (raw, window) in enumerate(zip(raws, windows)):
            name = f"sample-{index:04d}.txt"
            self.write("passive/" + name, raw)
            metadata.append({"file": name, "beforeMonotonicNs": round(window[0] * 1e9),
                             "afterMonotonicNs": round(window[1] * 1e9), "error": None})
        self.write("passive/samples.json", json.dumps(metadata))

    def test_stat_uses_last_parenthesis_and_literal_proc_fields(self):
        parsed = summary.proc_stat(stat())
        self.assertEqual({"id": 42, "name": "worker ) name", "state": "R", "utimeTicks": 9,
                          "stimeTicks": 11, "startTicks": 100, "threadCount": 1}, parsed)

    def test_legacy_and_vector_thread_formats_preserve_counters(self):
        legacy, formats = summary.parse_probe(probe())
        vector, vector_formats = summary.parse_probe(probe(vector=True))
        self.assertEqual({"legacy-per-thread"}, formats)
        self.assertEqual({"vector"}, vector_formats)
        self.assertEqual(9, vector[42]["threads"][0]["utimeTicks"])
        self.assertEqual("futex_wait_queue", legacy[42]["threads"][0]["wchan"])
        self.assertNotIn("wchan", vector[42]["threads"][0])
        self.assertEqual({"rchar": 3000, "wchar": 4000, "read_bytes": 1000, "write_bytes": 2000}, vector[42]["io"])

    def test_cpu_io_bounds_cadence_events_and_hashes_are_reproduced(self):
        self.samples([probe(), probe(user=69, system=51, read=7000, written=9000)], [(0, 2), (12, 15)])
        data = summary.summarize(self.root, 42, 100)
        cpu = data["processes"][0]["cpu"]
        self.assertEqual({"user": 60, "system": 40, "total": 100}, cpu["deltaTicks"])
        self.assertEqual({"lowerBound": 10.0, "midpointEstimate": 12.5, "upperBound": 15.0}, cpu["wallSeconds"])
        self.assertEqual(8.0, cpu["oneCorePercent"]["midpointEstimate"])
        self.assertAlmostEqual(6.533333333333333, cpu["oneCorePercent"]["lowerBound"])
        self.assertAlmostEqual(10.2, cpu["oneCorePercent"]["upperBound"])
        self.assertEqual(6000, data["processes"][0]["ioDelta"]["read_bytes"])
        self.assertEqual(7000, data["processes"][0]["ioDelta"]["write_bytes"])
        self.assertEqual(2.5, data["probes"]["durationSeconds"]["median"])
        self.assertEqual(12.5, data["probes"]["midpointSpacingSeconds"]["median"])
        self.assertTrue(data["apk"]["verifiedBeforeAndAfter"])
        self.assertFalse(data["readiness"]["readyEventRecorded"])
        self.assertEqual([33.64], data["readiness"]["freshLoadingElapsedSeconds"])
        self.assertAlmostEqual(30.6, data["timeline"][-1]["sinceAmStartReturnSeconds"])
        self.assertEqual(2, data["guestVcpus"])
        self.assertEqual("capture load", data["hostLoadAtCapture"])
        self.assertEqual("post-capture load", data["supplementalMetadata"]["host-uptime.txt"]["text"])
        self.assertEqual(summary.sha(self.root / "passive/sample-0000.txt"), data["inputSha256"]["passive/sample-0000.txt"])

    def test_overlapping_probe_envelopes_have_no_finite_upper_rate(self):
        self.samples([probe(), probe(user=109)], [(0, 8), (6, 15)])
        cpu = summary.summarize(self.root, 42, 100)["processes"][0]["cpu"]
        self.assertEqual(0, cpu["wallSeconds"]["lowerBound"])
        self.assertIsNone(cpu["oneCorePercent"]["upperBound"])
        self.assertTrue(cpu["upperRateUnbounded"])

    def test_same_pid_with_new_start_ticks_is_not_subtracted_across_restart(self):
        self.samples([probe(user=100), "PIDS:\n", probe(start=101, user=1, system=1)])
        data = summary.summarize(self.root, 42, 100)
        self.assertEqual([{"pid": 42, "startTicks": 100}, {"pid": 42, "startTicks": 101}], data["selectedIdentities"])
        self.assertEqual([0, 0], [group["cpu"]["deltaTicks"]["total"] for group in data["processes"]])
        self.assertEqual(["sample-0001.txt"], data["probes"]["empty"])

    def test_counter_regression_within_identity_is_rejected(self):
        self.samples([probe(), probe(user=8)])
        with self.assertRaisesRegex(ValueError, "counter decreased"):
            summary.summarize(self.root, 42, 100)

    def test_saved_hz_must_match_requested_conversion(self):
        self.samples([probe()])
        with self.assertRaisesRegex(ValueError, "guest-hz"):
            summary.summarize(self.root, 42, 250)

    def test_file_changes_and_old_collector_are_reported_without_claiming_readiness(self):
        self.samples([probe(), probe(user=19)])
        self.write("private-file-hashes-before.txt", "a  files/project.workspace\nb  files/profileInstalled\n")
        self.write("private-file-hashes-after.txt", "a  files/project.workspace\nc  files/profileInstalled\nd  files/window-link\n")
        self.write("observer-source-at-capture.py", "# captured legacy collector\n")
        path = self.root / "passive/receipt.json"
        receipt = json.loads(path.read_text())
        receipt["observerSha256"] = summary.sha(self.root / "observer-source-at-capture.py")
        path.write_text(json.dumps(receipt))
        data = summary.summarize(self.root, 42, 100)
        self.assertEqual({"unchanged": ["files/project.workspace"], "changed": ["files/profileInstalled"],
                          "added": ["files/window-link"], "removed": []}, data["privateFiles"])
        self.assertTrue(data["observer"]["sourceMatchesReceipt"])
        self.assertIn("older collector", " ".join(data["limitations"]))

    def test_cli_writes_only_a_new_path_outside_raw_input(self):
        self.samples([probe(), probe(user=19)])
        before = {p.relative_to(self.root): summary.sha(p) for p in self.root.rglob("*") if p.is_file()}
        command = [sys.executable, str(SCRIPT), "--input", str(self.root), "--pid", "42", "--hz", "100", "--output"]
        invalid = subprocess.run(command + [str(self.root / "summary.json")], capture_output=True, text=True)
        self.assertNotEqual(0, invalid.returncode)
        self.assertFalse((self.root / "summary.json").exists())
        output = self.base / "summary.json"
        valid = subprocess.run(command + [str(output)], capture_output=True, text=True)
        self.assertEqual(0, valid.returncode, valid.stderr)
        self.assertEqual("summarized", json.loads(output.read_text())["status"])
        old = output.read_bytes()
        duplicate = subprocess.run(command + [str(output)], capture_output=True, text=True)
        self.assertNotEqual(0, duplicate.returncode)
        self.assertEqual(old, output.read_bytes())
        after = {p.relative_to(self.root): summary.sha(p) for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
