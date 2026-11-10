import importlib.util
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "run-android-mixed-input.py"
SPEC = importlib.util.spec_from_file_location("android_mixed_input", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

PACKAGE = "thalesmms.logisim.mobile.e2e"
SHA = "a" * 64
APP_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
TEST_SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"


def output(cases=MODULE.CASES, qualification="SYNTHESIZED_SYSTEM_INPUT_PASS"):
    return "INSTRUMENTATION_RESULT: stream=\nMIXED_INPUT_RESULT=" + json.dumps({
        "status": "PASS", "packageName": PACKAGE, "qualification": qualification,
        "cases": [{"case": name, "status": "PASS"} for name in cases],
        "artifact": {"sha256Before": SHA, "sha256After": SHA},
    }) + "\nINSTRUMENTATION_CODE: -1\n"


class FakeAdb:
    def __init__(self, *, native_status="PASS", native_error=None, before_app=b"", after_app=b"",
                 after_anr="No ANR", collection_errors=()):
        self.native_status = native_status
        self.native_error = native_error
        self.before_app = before_app
        self.after_app = after_app
        self.after_anr = after_anr
        self.collection_errors = collection_errors
        self.installed_reads = {PACKAGE: 0, PACKAGE + ".test": 0}
        self.anr_reads = 0
        self.native_attempts = 0
        self.cleanup_calls = 0

    def installed_apk(self, package):
        self.installed_reads[package] += 1
        after = self.installed_reads[package] == 2
        if after and package in self.collection_errors:
            raise RuntimeError("Cannot collect " + package)
        if package == PACKAGE:
            return self.after_app if after else self.before_app
        return b"test"

    def run(self, *args, **kwargs):
        if args == ("shell", "getprop", "ro.build.fingerprint"):
            return "fake-android-build"
        if args == ("shell", "dumpsys", "activity", "lastanr"):
            self.anr_reads += 1
            if self.anr_reads == 2 and "lastanr" in self.collection_errors:
                raise RuntimeError("Cannot collect lastanr")
            return self.after_anr if self.anr_reads == 2 else "No ANR"
        if args[:3] == ("shell", "am", "instrument"):
            self.native_attempts += 1
            if self.native_error:
                raise self.native_error
            result = {"status": self.native_status, "qualification": "PARTIAL", "packageName": PACKAGE,
                      "cases": [{"case": "cancel", "status": self.native_status}],
                      "artifact": {"sha256Before": APP_SHA, "sha256After": APP_SHA},
                      "evidenceDirectory": "/data/user/0/" + PACKAGE + "/files/mixed-input/00000000-1111-2222-3333-444444444444"}
            return "MIXED_INPUT_RESULT=" + json.dumps(result) + "\nINSTRUMENTATION_CODE: -1\n"
        if args[:3] == ("exec-out", "run-as", PACKAGE):
            payload = io.BytesIO()
            with tarfile.open(fileobj=payload, mode="w") as archive:
                entry = tarfile.TarInfo("native-result.json")
                entry.size = 2
                archive.addfile(entry, io.BytesIO(b"{}"))
            return payload.getvalue()
        if args == ("shell", "am", "force-stop", PACKAGE):
            self.cleanup_calls += 1
            return ""
        raise AssertionError(f"Unexpected adb command: {args!r}")


class AndroidMixedInputRunnerTest(unittest.TestCase):
    def run_main(self, **options):
        adb = FakeAdb(**options)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app, test, destination = root / "app.apk", root / "test.apk", root / "result"
            app.write_bytes(b"")
            test.write_bytes(b"test")
            argv = [str(SCRIPT), "--serial", "fake-device", "--apk", str(app), "--test-apk", str(test),
                    "--source-revision", "b" * 40, "--case", "cancel", "--output", str(destination)]
            with patch.object(MODULE, "Adb", return_value=adb), patch.object(sys, "argv", argv), \
                    patch.object(MODULE.subprocess, "run", side_effect=AssertionError("Real ADB is forbidden")), \
                    redirect_stdout(io.StringIO()):
                code = MODULE.main()
            report = json.loads((destination / "manifest.json").read_text())
            after = destination / "lastanr-after.txt"
            return code, report, adb, after.read_text() if after.exists() else None

    def test_complete_native_proof_has_all_nine_cases(self):
        result = MODULE.parse_native_result(output(), "all", PACKAGE, SHA)
        self.assertEqual(9, len(result["cases"]))
        self.assertEqual(list(MODULE.CASES), [case["case"] for case in result["cases"]])
        self.assertEqual("SYNTHESIZED_SYSTEM_INPUT_PASS", result["qualification"])

    def test_selected_success_is_only_partial(self):
        result = MODULE.parse_native_result(output(("cancel",), "PARTIAL"), "cancel", PACKAGE, SHA)
        self.assertEqual("PARTIAL", result["qualification"])
        with self.assertRaisesRegex(ValueError, "exactly the requested"):
            MODULE.parse_native_result(output(("cancel",), "PARTIAL"), "all", PACKAGE, SHA)

    def test_missing_or_duplicate_case_cannot_qualify(self):
        for cases in (MODULE.CASES[:-1], MODULE.CASES[:-1] + ("cancel",)):
            with self.subTest(cases=cases), self.assertRaisesRegex(ValueError, "exactly the requested"):
                MODULE.parse_native_result(output(cases), "all", PACKAGE, SHA)

    def test_failure_and_changed_apk_cannot_qualify(self):
        failed = output().replace('"status": "PASS"', '"status": "FAIL"', 1)
        with self.assertRaisesRegex(ValueError, "failed"):
            MODULE.parse_native_result(failed, "all", PACKAGE, SHA)
        changed = output().replace('"sha256After": "' + SHA + '"', '"sha256After": "' + "b" * 64 + '"')
        with self.assertRaisesRegex(ValueError, "identity"):
            MODULE.parse_native_result(changed, "all", PACKAGE, SHA)

    def test_absent_or_multiple_native_receipts_are_rejected(self):
        for text in ("INSTRUMENTATION_CODE: -1\n", output() + output()):
            with self.subTest(text=text[:30]), self.assertRaisesRegex(ValueError, "exactly one"):
                MODULE.decode_native_result(text)

    def test_native_pass_cannot_override_a_crash_or_failed_terminal(self):
        for text in (
                output().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
                output() + "INSTRUMENTATION_FAILED: Process crashed.\n",
                output() + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"):
            with self.subTest(text=text[-100:]), self.assertRaises(ValueError):
                MODULE.parse_native_result(text, "all", PACKAGE, SHA)

    def test_terminal_result_must_be_present_and_unique(self):
        for text in (
                output().replace("INSTRUMENTATION_CODE: -1\n", ""),
                output() + "INSTRUMENTATION_CODE: -1\n"):
            with self.subTest(text=text[-100:]), self.assertRaisesRegex(ValueError, "end once"):
                MODULE.parse_native_result(text, "all", PACKAGE, SHA)

    def test_native_protocol_cannot_continue_after_its_terminal(self):
        receipt = output().removesuffix("INSTRUMENTATION_CODE: -1\n")
        for text in (
                "INSTRUMENTATION_CODE: -1\n" + receipt,
                output() + "INSTRUMENTATION_RESULT: tests=8\n",
                output() + "INSTRUMENTATION_STATUS: tests=8\n"):
            with self.subTest(text=text[-100:]), self.assertRaisesRegex(ValueError, "after its terminal"):
                MODULE.parse_native_result(text, "all", PACKAGE, SHA)

    def test_only_isolated_test_package_is_allowed(self):
        self.assertEqual(PACKAGE, MODULE.isolated_package(PACKAGE))
        for value in ("thalesmms.logisim.mobile", PACKAGE + ".test", PACKAGE + ";input tap 0 0"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MODULE.isolated_package(value)

    def test_artifact_directory_is_bounded_to_target_test_uuid(self):
        suffix = "mixed-input/00000000-1111-2222-3333-444444444444"
        self.assertEqual("files/" + suffix,
                         MODULE.artifact_relative_path("/data/user/0/" + PACKAGE + "/files/" + suffix, PACKAGE))
        for value in ("/sdcard/" + suffix, "/data/user/0/another.e2e/files/" + suffix,
                      "/data/user/0/" + PACKAGE + "/files/" + suffix + "/../../private"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                MODULE.artifact_relative_path(value, PACKAGE)

    def test_archive_extraction_preserves_receipt_and_rejects_escape(self):
        for name, safe in (("./result.json", True), ("../outside", False)):
            payload = io.BytesIO()
            with tarfile.open(fileobj=payload, mode="w") as archive:
                entry = tarfile.TarInfo(name)
                entry.size = 2
                archive.addfile(entry, io.BytesIO(b"{}"))
            with tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "native"
                if safe:
                    MODULE.extract_evidence(payload.getvalue(), target)
                    self.assertEqual("{}", (target / "result.json").read_text())
                else:
                    with self.assertRaisesRegex(ValueError, "Unsafe"):
                        MODULE.extract_evidence(payload.getvalue(), target)
                    self.assertFalse((Path(directory) / "outside").exists())

    def test_native_failure_still_records_final_artifacts_and_lastanr_once(self):
        code, report, adb, after = self.run_main(native_status="FAIL")
        self.assertEqual(1, code)
        self.assertEqual(("FAIL", "NOT_QUALIFIED"), (report["status"], report["qualification"]))
        self.assertEqual("One or more native input scenarios failed", report["error"])
        self.assertEqual({"appSha256": APP_SHA, "testSha256": TEST_SHA}, report["installedArtifactsAfter"])
        self.assertEqual("No ANR", after)
        self.assertTrue(report["lastAnrUnchanged"])
        self.assertEqual({PACKAGE: 2, PACKAGE + ".test": 2}, adb.installed_reads)
        self.assertEqual((2, 0), (adb.anr_reads, adb.cleanup_calls))

    def test_post_collection_errors_preserve_the_native_error_and_collect_independently(self):
        code, report, adb, _ = self.run_main(native_status="FAIL", collection_errors=(PACKAGE, "lastanr"))
        self.assertEqual(1, code)
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("ValueError", report["errorType"])
        self.assertEqual("One or more native input scenarios failed", report["error"])
        self.assertIn("Cannot collect " + PACKAGE, report["postVerificationError"])
        self.assertIn("Cannot collect lastanr", report["postVerificationError"])
        self.assertEqual({"testSha256": TEST_SHA}, report["installedArtifactsAfter"])
        self.assertEqual({PACKAGE: 2, PACKAGE + ".test": 2}, adb.installed_reads)
        self.assertEqual(2, adb.anr_reads)

    def test_success_keeps_qualification_and_performs_each_post_check_once(self):
        code, report, adb, after = self.run_main()
        self.assertEqual((0, "PASS", "PARTIAL"), (code, report["status"], report["qualification"]))
        self.assertEqual({"appSha256": APP_SHA, "testSha256": TEST_SHA}, report["installedArtifactsAfter"])
        self.assertEqual("No ANR", after)
        self.assertEqual({PACKAGE: 2, PACKAGE + ".test": 2}, adb.installed_reads)
        self.assertEqual(2, adb.anr_reads)

    def test_post_verification_divergence_cannot_keep_a_native_pass(self):
        for options, message in (({"after_app": b"changed"}, "An installed artifact changed during execution"),
                                 ({"after_anr": "New native ANR"}, "The system recorded a new ANR during this run")):
            with self.subTest(options=options):
                code, report, adb, after = self.run_main(**options)
                self.assertEqual((1, "FAIL", "NOT_QUALIFIED"), (code, report["status"], report["qualification"]))
                self.assertEqual(message, report["error"])
                self.assertIn(message, report["postVerificationError"])
                self.assertEqual(TEST_SHA, report["installedArtifactsAfter"]["testSha256"])
                self.assertEqual({PACKAGE: 2, PACKAGE + ".test": 2}, adb.installed_reads)
                self.assertEqual(options.get("after_anr", "No ANR"), after)

    def test_pre_native_failure_does_not_start_final_adb_queries(self):
        code, report, adb, after = self.run_main(before_app=b"wrong APK")
        self.assertEqual((1, "FAIL"), (code, report["status"]))
        self.assertEqual("Installed application does not match --apk", report["error"])
        self.assertEqual({PACKAGE: 1, PACKAGE + ".test": 0}, adb.installed_reads)
        self.assertEqual((0, 0), (adb.native_attempts, adb.anr_reads))
        self.assertIsNone(after)
        self.assertNotIn("installedArtifactsAfter", report)

    def test_native_command_error_preserves_existing_cleanup_and_final_provenance(self):
        code, report, adb, after = self.run_main(native_error=RuntimeError("Native connection failed"))
        self.assertEqual((1, "FAIL"), (code, report["status"]))
        self.assertEqual("Native connection failed", report["error"])
        self.assertEqual({"appSha256": APP_SHA, "testSha256": TEST_SHA}, report["installedArtifactsAfter"])
        self.assertEqual((1, 1, 2), (adb.native_attempts, adb.cleanup_calls, adb.anr_reads))
        self.assertEqual("No ANR", after)


if __name__ == "__main__":
    unittest.main()
