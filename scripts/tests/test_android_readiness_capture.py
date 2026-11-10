import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "profile-android-readiness.py"


class AndroidReadinessCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.log = self.root / "calls.jsonl"
        self.adb = self.root / "fake-adb"
        self.adb.write_text("""#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
a=sys.argv[3:]
with Path(os.environ['FAKE_ADB_LOG']).open('a') as stream: stream.write(json.dumps(a)+'\\n')
if a[:3]==['shell','pm','path']: print('package:/data/app/fixture/base.apk')
elif a[:2]==['shell','sha256sum']: print('a'*64+'  /data/app/fixture/base.apk')
elif a[:2]==['shell','pidof']: print(os.environ.get('FAKE_PID','8880'))
elif a[-1:] == ['id']: print('uid=10207(app) gid=10207(app)')
elif a[:2]==['shell','date']: print('1793827000')
elif a[:2]==['shell','getconf']: print('100' if a[-1]=='CLK_TCK' else '2')
elif a[:2]==['shell','run-as']:
 print('PIDS:8880')
 if not os.environ.get('FAKE_MISSING_PROCESS'): print('PROCESS:8880\\n8880 (app) S\\nTHREADS:8880')
else: raise SystemExit('Unexpected adb command: '+repr(a))
""")
        self.adb.chmod(0o755)

    def capture(self, *, expected="a" * 64, **environment):
        output = self.root / "capture"
        env = dict(os.environ, FAKE_ADB_LOG=str(self.log), **environment)
        result = subprocess.run([sys.executable, str(SCRIPT), "--adb", str(self.adb),
            "--serial", "emulator-5558", "--package", "example.app.e2e",
            "--source-revision", "1" * 40, "--expected-apk-sha256", expected,
            "--initial-pid", "8880", "--seconds", "1", "--interval", "0.2",
            "--output", str(output)], capture_output=True, text=True, env=env, timeout=8)
        receipt = json.loads((output / "receipt.json").read_text())
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        return result, receipt, calls

    def test_artifact_mismatch_stops_before_accessing_the_app_process(self):
        result, receipt, calls = self.capture(expected="b" * 64)
        self.assertEqual(1, result.returncode)
        self.assertEqual("failed", receipt["status"])
        self.assertIn("APK hash differs", receipt["error"])
        self.assertEqual([["shell", "pm", "path", "example.app.e2e"],
                          ["shell", "sha256sum", "/data/app/fixture/base.apk"]], calls)

    def test_wrong_initial_process_stops_before_the_probe(self):
        result, receipt, calls = self.capture(FAKE_PID="9999")
        self.assertEqual(1, result.returncode)
        self.assertIn("Initial process differs", receipt["error"])
        self.assertFalse(any("run-as" in call for call in calls))

    def test_missing_initial_probe_process_cannot_report_success(self):
        result, receipt, _ = self.capture(FAKE_MISSING_PROCESS="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("Initial process is missing", receipt["error"])

    def test_capture_preserves_raw_samples_and_records_target_clock_and_cpus(self):
        result, receipt, calls = self.capture()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("captured", receipt["status"])
        self.assertEqual(100, receipt["guestClockTicksPerSecond"])
        self.assertEqual(2, receipt["guestOnlineProcessors"])
        self.assertEqual(receipt["apkBefore"], receipt["apkAfter"])
        self.assertIn("PROCESS:8880\n", (self.root / "capture/sample-0000.txt").read_text())
        self.assertGreaterEqual(receipt["samples"], 2)
        self.assertFalse(any(token in call for call in calls for token in ("am", "input", "kill", "root")))


if __name__ == "__main__":
    unittest.main()
