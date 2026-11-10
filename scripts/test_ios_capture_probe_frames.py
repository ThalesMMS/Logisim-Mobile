"""Collector timing/receipt tests with fake commands; no Simulator is contacted."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from ios_capture_probe_frames import capture_frame


class CaptureFrameTest(unittest.TestCase):
    def capture(self, *, wall=(100, 100.1), monotonic=(20, 20.1), exit_code=0, content=b"\x89PNG\r\n\x1a\nfixture"):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "frame.png"
            commands = []

            def run(command, **arguments):
                commands.append(command)
                if content is not None:
                    output.write_bytes(content)
                return subprocess.CompletedProcess(command, exit_code, "fake screenshot result")

            result = capture_frame("EXPLICIT-UDID", output, test_case="IoEndToEndTests.testCanvas", run=run, wall=iter(wall).__next__,
                                   monotonic=iter(monotonic).__next__)
            return result, commands

    def test_capture_has_a_conservative_interval_and_only_a_read_only_command(self):
        result, commands = self.capture()
        self.assertEqual("captured", result["collectionStatus"])
        self.assertEqual("ios-probe-frame-capture", result["recordType"])
        self.assertEqual("IoEndToEndTests.testCanvas", result["testCase"])
        self.assertEqual(100, result["captureStartedUnixSeconds"])
        self.assertEqual(100.1, result["captureReturnedUnixSeconds"])
        self.assertEqual(["xcrun", "simctl", "io", "EXPLICIT-UDID", "screenshot", "--type=png"], commands[0][:-1])
        self.assertEqual(64, len(result["frameSha256"]))

    def test_failed_command_is_not_a_captured_frame(self):
        result, _ = self.capture(exit_code=1)
        self.assertEqual("failed", result["collectionStatus"])
        self.assertNotIn("frameSha256", result)

    def test_success_without_a_png_file_is_rejected(self):
        for content in [None, b"not a PNG"]:
            with self.subTest(content=content):
                result, _ = self.capture(content=content)
                self.assertEqual("failed", result["collectionStatus"])

    def test_wall_clock_jump_is_rejected(self):
        for wall in [(100, 99), (100, 102)]:
            with self.subTest(wall=wall):
                result, _ = self.capture(wall=wall)
                self.assertEqual("failed", result["collectionStatus"])
                self.assertIn("wall clock", result["error"])


if __name__ == "__main__":
    unittest.main()
