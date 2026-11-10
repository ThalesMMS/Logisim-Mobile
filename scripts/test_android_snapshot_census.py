#!/usr/bin/env python3
"""Check the static snapshot census with literal observations, without adb."""

import base64
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from android_snapshot_census import parse_processes, parse_series_result, parse_snapshot_result, run_census


PACKAGE = "dev.logisim.mobile.e2e"
SERIAL = "emulator-5556"
PROCESS_LIST = """PID PPID NAME ARGS
401 1 dev.logisim.mobile.e2e dev.logisim.mobile.e2e
402 1 dev.logisim.mobile.e2e:provider dev.logisim.mobile.e2e:provider
403 1 dev.logisim.mobile.e2e.test dev.logisim.mobile.e2e.test
404 1 dev.logisim.mobile.e2e.test:fixture-provider dev.logisim.mobile.e2e.test:fixture-provider
405 1 dev.logisim.mobile.snapshot.e2e.test dev.logisim.mobile.snapshot.e2e.test
406 1 dev.logisim.mobile.snapshot.e2e.test:provider dev.logisim.mobile.snapshot.e2e.test:provider
407 1 uiautomator uiautomator dump
408 1 app_process /system/bin/app_process /system/bin com.android.commands.uiautomator.Launcher dump
409 1 dev.logisim.mobile.e2e.other dev.logisim.mobile.e2e
"""
IDLE_PROCESS_LIST = """PID PPID NAME ARGS
401 1 dev.logisim.mobile.e2e dev.logisim.mobile.e2e
403 1 dev.logisim.mobile.e2e.test dev.logisim.mobile.e2e.test
404 1 dev.logisim.mobile.e2e.test:fixture-provider dev.logisim.mobile.e2e.test:fixture-provider
"""
WINDOWS_BASE64 = base64.b64encode(b'[{"id":4,"type":1,"active":true,"focused":true}]').decode("ascii")
SNAPSHOT_XML = b'<hierarchy rotation="0" snapshot-nonce="probe-a" uptime-millis="1000"><node package="dev.logisim.mobile.e2e" bounds="[0,0][600,1000]"/></hierarchy>'
XML_BASE64 = "PGhpZXJhcmNoeSByb3RhdGlvbj0iMCIgc25hcHNob3Qtbm9uY2U9InByb2JlLWEiIHVwdGltZS1taWxsaXM9IjEwMDAiPjxub2RlIHBhY2thZ2U9ImRldi5sb2dpc2ltLm1vYmlsZS5lMmUiIGJvdW5kcz0iWzAsMF1bNjAwLDEwMDBdIi8+PC9oaWVyYXJjaHk+"
SUCCESS_RESULT = """INSTRUMENTATION_RESULT: snapshotNonce=probe-a
INSTRUMENTATION_RESULT: snapshotExpectedPackage=dev.logisim.mobile.e2e
INSTRUMENTATION_RESULT: snapshotRootAttempts=1
INSTRUMENTATION_RESULT: snapshotRootWaitMillis=23
INSTRUMENTATION_RESULT: snapshotRootWindowId=4
INSTRUMENTATION_RESULT: snapshotRotation=0
INSTRUMENTATION_RESULT: snapshotUptimeMillis=1000
INSTRUMENTATION_RESULT: snapshotRootSource=active-window
INSTRUMENTATION_RESULT: snapshotXmlBase64=PGhpZXJhcmNoeSByb3RhdGlvbj0iMCIgc25hcHNob3Qtbm9uY2U9InByb2JlLWEiIHVwdGltZS1taWxsaXM9IjEwMDAiPjxub2RlIHBhY2thZ2U9ImRldi5sb2dpc2ltLm1vYmlsZS5lMmUiIGJvdW5kcz0iWzAsMF1bNjAwLDEwMDBdIi8+PC9oaWVyYXJjaHk+
INSTRUMENTATION_CODE: -1
"""
ACTOR_ARGUMENTS = [
    ["shell", "ps", "-A", "-o", "PID,PPID,NAME,ARGS"],
    ["shell", "dumpsys", "accessibility"],
    ["shell", "dumpsys", "window", "windows"],
    ["shell", "dumpsys", "input_method"],
]


def result_with_xml(xml):
    return SUCCESS_RESULT.replace(XML_BASE64, base64.b64encode(xml).decode("ascii"))


def result_for_probe(nonce, uptime):
    xml = SNAPSHOT_XML.replace(b"probe-a", nonce.encode()).replace(b'"1000"', f'"{uptime}"'.encode())
    return result_with_xml(xml).replace("snapshotNonce=probe-a", f"snapshotNonce={nonce}").replace(
        "snapshotUptimeMillis=1000", f"snapshotUptimeMillis={uptime}")


def with_capture_metadata(stdout, fields, category="RESULT"):
    marker = "INSTRUMENTATION_STATUS_CODE:" if category == "STATUS" else "INSTRUMENTATION_CODE:"
    lines = "".join(f"INSTRUMENTATION_{category}: {key}={value}\n" for key, value in fields.items())
    return stdout.replace(marker, lines + marker)


def series_for_probe(nonce, count=1, start_uptime=1000, failed_index=None, windows_error=False):
    blocks = []
    for index in range(count):
        status = result_for_probe(f"{nonce}-{index}", start_uptime + index).replace(
            "INSTRUMENTATION_RESULT:", "INSTRUMENTATION_STATUS:").replace("INSTRUMENTATION_CODE: -1\n", "")
        if index == failed_index:
            status = "\n".join(line for line in status.splitlines() if "snapshotXmlBase64=" not in line) + "\n"
            status += "INSTRUMENTATION_STATUS: snapshotError=No root\n"
        status += f"""INSTRUMENTATION_STATUS: snapshotBaseNonce={nonce}
INSTRUMENTATION_STATUS: snapshotQueryIndex={index}
INSTRUMENTATION_STATUS: snapshotQueryCount={count}
INSTRUMENTATION_STATUS: snapshotHelperPid=501
INSTRUMENTATION_STATUS: snapshotConnectionUptimeMillis=900
INSTRUMENTATION_STATUS_CODE: {0 if index == failed_index else -1}
"""
        blocks.append(status)
    failures = int(failed_index is not None)
    blocks.append(f"""INSTRUMENTATION_RESULT: snapshotNonce={nonce}
INSTRUMENTATION_RESULT: snapshotBaseNonce={nonce}
INSTRUMENTATION_RESULT: snapshotExpectedPackage=dev.logisim.mobile.e2e
INSTRUMENTATION_RESULT: snapshotQueryCount={count}
INSTRUMENTATION_RESULT: snapshotHelperPid=501
INSTRUMENTATION_RESULT: snapshotConnectionUptimeMillis=900
INSTRUMENTATION_RESULT: snapshotQueryFailures={failures}
INSTRUMENTATION_RESULT: snapshotQuerySuccesses={count - failures}
""")
    blocks.append("INSTRUMENTATION_RESULT: snapshotWindowsError=Window census unavailable\n" if windows_error else
                  f"INSTRUMENTATION_RESULT: snapshotWindowsBase64={WINDOWS_BASE64}\n")
    blocks.append(f"INSTRUMENTATION_CODE: {0 if failures or windows_error else -1}\n")
    return "".join(blocks)


class CensusTests(unittest.TestCase):
    def setUp(self):
        process = patch("subprocess.run", side_effect=AssertionError("Device processes are forbidden in census tests"))
        process.start()
        self.addCleanup(process.stop)

    def test_processes_distinguish_the_exact_editor_from_fixture_and_helper_providers(self):
        actors = parse_processes(PROCESS_LIST, PACKAGE)
        self.assertEqual(401, actors["editorPid"])
        self.assertEqual([401], [row["pid"] for row in actors["editorProcesses"]])
        self.assertEqual([403, 404], [row["pid"] for row in actors["fixtureProcesses"]])
        self.assertEqual([405, 406], [row["pid"] for row in actors["helperProcesses"]])
        self.assertEqual([407, 408], [row["pid"] for row in actors["uiautomatorProcesses"]])
        self.assertEqual({"pid": 401, "parentPid": 1, "name": "dev.logisim.mobile.e2e",
                          "args": "dev.logisim.mobile.e2e"}, actors["editorProcesses"][0])

    def test_processes_require_one_exact_editor_and_the_requested_ps_columns(self):
        duplicate = PROCESS_LIST + "410 1 dev.logisim.mobile.e2e dev.logisim.mobile.e2e\n"
        self.assertIsNone(parse_processes(duplicate, PACKAGE)["editorPid"])
        self.assertIsNone(parse_processes("PID PPID NAME ARGS\n402 1 dev.logisim.mobile.e2e:provider provider\n", PACKAGE)["editorPid"])
        for invalid in ("PID NAME ARGS\n401 editor editor\n", "PID PPID NAME ARGS\nnot-a-pid 1 editor editor\n"):
            with self.subTest(ps=invalid), self.assertRaises(ValueError):
                parse_processes(invalid, PACKAGE)

    def test_snapshot_success_returns_literal_metadata_and_xml(self):
        values, xml = parse_snapshot_result(SUCCESS_RESULT, 0, "probe-a", PACKAGE, previous_uptime=999)
        self.assertEqual({"snapshotNonce": "probe-a", "snapshotExpectedPackage": "dev.logisim.mobile.e2e",
                          "snapshotRootAttempts": 1, "snapshotRootWaitMillis": 23, "snapshotRootWindowId": 4,
                          "snapshotRotation": 0, "snapshotUptimeMillis": 1000, "snapshotRootSource": "active-window"}, values)
        self.assertEqual(SNAPSHOT_XML, xml)

    def test_snapshot_rejects_failed_commands_codes_errors_duplicates_and_nonce(self):
        cases = (
            (SUCCESS_RESULT, 1, "probe-a", "adb command exited with 1"),
            (SUCCESS_RESULT.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), 0, "probe-a", "instrumentation failed"),
            (SUCCESS_RESULT + "INSTRUMENTATION_CODE: -1\n", 0, "probe-a", "instrumentation failed"),
            (SUCCESS_RESULT.replace("INSTRUMENTATION_CODE: -1\n", ""), 0, "probe-a", "instrumentation failed"),
            (SUCCESS_RESULT + "INSTRUMENTATION_RESULT: snapshotError=No root\n", 0, "probe-a", "No root"),
            (SUCCESS_RESULT + "INSTRUMENTATION_RESULT: snapshotNonce=probe-a\n", 0, "probe-a", "Duplicate snapshot result field"),
            (SUCCESS_RESULT, 0, "another-probe", "nonce or expected package"),
            (SUCCESS_RESULT.replace("snapshotExpectedPackage=dev.logisim.mobile.e2e", "snapshotExpectedPackage=android"),
             0, "probe-a", "nonce or expected package"),
        )
        for stdout, code, nonce, message in cases:
            with self.subTest(code=code, nonce=nonce, message=message), self.assertRaisesRegex(ValueError, message):
                parse_snapshot_result(stdout, code, nonce, PACKAGE)

    def test_snapshot_rejects_missing_or_invalid_metadata(self):
        cases = (
            ("snapshotRootAttempts=1", "snapshotRootAttempts=0", "snapshotRootAttempts"),
            ("snapshotRootWaitMillis=23", "snapshotRootWaitMillis=-1", "snapshotRootWaitMillis"),
            ("INSTRUMENTATION_RESULT: snapshotRootWindowId=4\n", "", "snapshotRootWindowId"),
            ("snapshotRotation=0", "snapshotRotation=4", "rotation or root source"),
            ("snapshotUptimeMillis=1000", "snapshotUptimeMillis=not-a-number", "snapshotUptimeMillis"),
            ("snapshotRootSource=active-window", "snapshotRootSource=cached-root", "rotation or root source"),
        )
        for old, new, message in cases:
            with self.subTest(field=old), self.assertRaisesRegex(ValueError, message):
                parse_snapshot_result(SUCCESS_RESULT.replace(old, new), 0, "probe-a", PACKAGE)
        with self.assertRaisesRegex(ValueError, "uptime moved backwards"):
            parse_snapshot_result(SUCCESS_RESULT, 0, "probe-a", PACKAGE, previous_uptime=1001)

    def test_snapshot_parses_optional_complete_capture_metadata(self):
        fields = {"snapshotCaptureState": "COMPLETE", "snapshotCaptureElapsedMillis": "1000",
                  "snapshotCaptureOverrunMillis": "0"}
        values, xml = parse_snapshot_result(with_capture_metadata(SUCCESS_RESULT, fields), 0, "probe-a", PACKAGE)
        self.assertEqual("COMPLETE", values["snapshotCaptureState"])
        self.assertEqual(1000, values["snapshotCaptureElapsedMillis"])
        self.assertEqual(0, values["snapshotCaptureOverrunMillis"])
        self.assertEqual(SNAPSHOT_XML, xml)

    def test_snapshot_rejects_partial_or_malformed_capture_metadata(self):
        valid = {"snapshotCaptureState": "COMPLETE", "snapshotCaptureElapsedMillis": "5",
                 "snapshotCaptureOverrunMillis": "0"}
        cases = [{key: value for key, value in valid.items() if key != missing} for missing in valid]
        cases += [valid | {"snapshotCaptureState": state} for state in ("", "ERROR", "INCOMPLETE_TREE", "complete")]
        for key in ("snapshotCaptureElapsedMillis", "snapshotCaptureOverrunMillis"):
            cases += [valid | {key: value} for value in ("", "-1", "1.5", "bad", "true")]
        for fields in cases:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                parse_snapshot_result(with_capture_metadata(SUCCESS_RESULT, fields), 0, "probe-a", PACKAGE)

    def test_snapshot_rejects_invalid_base64_xml_root_package_and_xml_metadata(self):
        invalid_xml = (
            b"not xml",
            b"\xff",
            b'<document rotation="0" snapshot-nonce="probe-a" uptime-millis="1000"><node package="dev.logisim.mobile.e2e"/></document>',
            b'<hierarchy rotation="0" snapshot-nonce="probe-a" uptime-millis="1000"/>',
            b'<hierarchy rotation="0" snapshot-nonce="probe-a" uptime-millis="1000"><node package="android"/></hierarchy>',
            b'<hierarchy rotation="0" snapshot-nonce="another-probe" uptime-millis="1000"><node package="dev.logisim.mobile.e2e"/></hierarchy>',
            b'<hierarchy rotation="0" snapshot-nonce="probe-a" uptime-millis="999"><node package="dev.logisim.mobile.e2e"/></hierarchy>',
            b'<hierarchy rotation="1" snapshot-nonce="probe-a" uptime-millis="1000"><node package="dev.logisim.mobile.e2e"/></hierarchy>',
        )
        for xml in invalid_xml:
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                parse_snapshot_result(result_with_xml(xml), 0, "probe-a", PACKAGE)
        for stdout in (SUCCESS_RESULT.replace(XML_BASE64, "not base64!"),
                       SUCCESS_RESULT.replace("INSTRUMENTATION_RESULT: snapshotXmlBase64=" + XML_BASE64 + "\n", "")):
            with self.subTest(stdout=stdout), self.assertRaisesRegex(ValueError, "invalid XML"):
                parse_snapshot_result(stdout, 0, "probe-a", PACKAGE)

    @staticmethod
    def actor_result(command, **kwargs):
        arguments = command[3:]
        if arguments == ACTOR_ARGUMENTS[0]:
            stdout = IDLE_PROCESS_LIST
        elif arguments in ACTOR_ARGUMENTS[1:]:
            stdout = "observed " + " ".join(arguments) + "\n"
        else:
            raise AssertionError(f"Unexpected actor command: {command}")
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr="")

    def test_static_census_only_observes_and_snapshots_without_input_commands(self):
        def observed(command, **kwargs):
            if command[3:6] == ["shell", "am", "instrument"]:
                nonce = command[command.index("snapshotNonce") + 1]
                return subprocess.CompletedProcess(command, 0, stdout=series_for_probe(nonce, start_uptime=1000 if nonce == "probe-a" else 1001), stderr="")
            return self.actor_result(command, **kwargs)

        with tempfile.TemporaryDirectory() as directory, \
                patch("android_snapshot_census.uuid.uuid4", side_effect=[SimpleNamespace(hex="probe-a"), SimpleNamespace(hex="probe-b")]), \
                patch("android_snapshot_census.subprocess.run", side_effect=observed) as process:
            output = Path(directory) / "new-census"
            census = run_census("fake-adb", SERIAL, PACKAGE, output, invocations=2)
            self.assertEqual("passed", census["status"])
            self.assertEqual(["passed", "passed"], [row["status"] for row in census["probes"]])
            self.assertEqual([1000, 1001], [row["queries"][0]["result"]["snapshotUptimeMillis"] for row in census["probes"]])
            expected = []
            for nonce in ("probe-a", "probe-b"):
                expected.extend(ACTOR_ARGUMENTS)
                expected.append(["shell", "am", "instrument", "-w", "-r", "-e", "snapshotNonce", nonce,
                                 "-e", "snapshotExpectedPackage", "dev.logisim.mobile.e2e",
                                 "-e", "snapshotQueryCount", "1",
                                 "dev.logisim.mobile.snapshot.e2e.test/dev.logisim.mobile.documents.UiSnapshotSeriesInstrumentation"])
                expected.extend(ACTOR_ARGUMENTS)
            self.assertEqual([["fake-adb", "-s", "emulator-5556", *arguments] for arguments in expected],
                             [call.args[0] for call in process.call_args_list])
            self.assertEqual(census, json.loads((output / "census.json").read_text()))
            self.assertEqual(SNAPSHOT_XML.replace(b"probe-a", b"probe-a-0"), (output / "probe-0-query-0.xml").read_bytes())
            self.assertIn(b'snapshot-nonce="probe-b-0"', (output / "probe-1-query-0.xml").read_bytes())

    def test_snapshot_timeouts_preserve_failed_rows_actor_censuses_and_partial_evidence(self):
        def observed(command, **kwargs):
            if command[3:6] == ["shell", "am", "instrument"]:
                self.assertEqual(8, kwargs["timeout"])
                raise subprocess.TimeoutExpired(command, 8, output=b"partial snapshot output\n", stderr=b"helper stalled\n")
            return self.actor_result(command, **kwargs)

        with tempfile.TemporaryDirectory() as directory, \
                patch("android_snapshot_census.subprocess.run", side_effect=observed):
            output = Path(directory) / "timeout-census"
            census = run_census("fake-adb", SERIAL, PACKAGE, output, invocations=2)
            self.assertEqual("failed", census["status"])
            self.assertEqual(["failed", "failed"], [row["status"] for row in census["probes"]])
            self.assertEqual(census, json.loads((output / "census.json").read_text()))
            for index, row in enumerate(census["probes"]):
                self.assertEqual(401, row["actorsAfter"]["editorPid"])
                self.assertTrue(row["editorPidUnchanged"])
                self.assertEqual("TimeoutExpired", row["snapshotCommand"]["error"])
                self.assertRegex(row["errors"][0], "TimeoutExpired")
                self.assertEqual("partial snapshot output\n", (output / f"probe-{index}-result-stdout.txt").read_text())
                self.assertEqual("helper stalled\n", (output / f"probe-{index}-result-stderr.txt").read_text())
                self.assertEqual(IDLE_PROCESS_LIST, (output / f"probe-{index}-after-processes-stdout.txt").read_text())
                self.assertFalse((output / f"probe-{index}-query-0.xml").exists())

    def test_actor_timeout_still_records_after_state_and_skips_instrumentation(self):
        process_reads = 0

        def observed(command, **kwargs):
            nonlocal process_reads
            if command[3:] == ACTOR_ARGUMENTS[0]:
                process_reads += 1
                if process_reads in (1, 3):
                    raise subprocess.TimeoutExpired(command, 15, output=b"partial ps\n", stderr=b"ps stalled\n")
            return self.actor_result(command, **kwargs)

        with tempfile.TemporaryDirectory() as directory, \
                patch("android_snapshot_census.subprocess.run", side_effect=observed) as process:
            output = Path(directory) / "actor-timeout-census"
            census = run_census("fake-adb", SERIAL, PACKAGE, output, invocations=2)
            self.assertEqual("failed", census["status"])
            self.assertEqual(["failed", "failed"], [row["status"] for row in census["probes"]])
            self.assertEqual(census, json.loads((output / "census.json").read_text()))
            self.assertEqual(ACTOR_ARGUMENTS * 4, [call.args[0][3:] for call in process.call_args_list])
            for index, row in enumerate(census["probes"]):
                self.assertEqual(["processes: TimeoutExpired"], row["actorsBefore"]["errors"])
                self.assertEqual(401, row["actorsAfter"]["editorPid"])
                self.assertIn("Expected a successful actor census", row["errors"][0])
                self.assertNotIn("snapshotCommand", row)
                self.assertEqual("partial ps\n", (output / f"probe-{index}-before-processes-stdout.txt").read_text())

    def test_series_validates_four_queries_on_one_connection_and_the_final_window_census(self):
        samples, result, xmls = parse_series_result(series_for_probe("probe-a", 4), 0, "probe-a", PACKAGE, 4)
        self.assertEqual(["passed"] * 4, [sample["status"] for sample in samples])
        self.assertEqual([0, 1, 2, 3], [sample["result"]["snapshotQueryIndex"] for sample in samples])
        self.assertEqual([501] * 4, [sample["result"]["snapshotHelperPid"] for sample in samples])
        self.assertEqual([900] * 4, [sample["result"]["snapshotConnectionUptimeMillis"] for sample in samples])
        self.assertEqual(4, result["snapshotQuerySuccesses"])
        self.assertEqual(0, result["snapshotQueryFailures"])
        self.assertEqual([{"id": 4, "type": 1, "active": True, "focused": True}], result["windows"])
        self.assertEqual(SNAPSHOT_XML.replace(b"probe-a", b"probe-a-3").replace(b'"1000"', b'"1003"'), xmls[3])

    def test_shared_connection_runner_records_four_queries_from_one_static_invocation(self):
        def observed(command, **kwargs):
            if command[3:6] == ["shell", "am", "instrument"]:
                self.assertEqual("4", command[command.index("snapshotQueryCount") + 1])
                nonce = command[command.index("snapshotNonce") + 1]
                return subprocess.CompletedProcess(command, 0, stdout=series_for_probe(nonce, 4), stderr="")
            return self.actor_result(command, **kwargs)

        with tempfile.TemporaryDirectory() as directory, patch("android_snapshot_census.subprocess.run", side_effect=observed):
            output = Path(directory) / "shared-census"
            census = run_census("fake-adb", SERIAL, PACKAGE, output, query_count=4, invocations=1)
            self.assertEqual("passed", census["status"])
            self.assertEqual((4, 1), (census["queryCount"], census["invocations"]))
            self.assertEqual(1, len(census["probes"]))
            self.assertEqual([0, 1, 2, 3], [query["result"]["snapshotQueryIndex"] for query in census["probes"][0]["queries"]])
            self.assertEqual(["probe-0-query-0.xml", "probe-0-query-1.xml", "probe-0-query-2.xml", "probe-0-query-3.xml"],
                             sorted(path.name for path in output.glob("*.xml")))

    def test_static_census_enforces_capture_budget_even_when_root_wait_is_small(self):
        for elapsed, overrun, expected in (("1000", "0", True), ("1100", "100", False),
                                           ("1100", "0", False), ("500", "1", False)):
            with self.subTest(elapsed=elapsed, overrun=overrun):
                def observed(command, **kwargs):
                    if command[3:6] == ["shell", "am", "instrument"]:
                        nonce = command[command.index("snapshotNonce") + 1]
                        stdout = series_for_probe(nonce).replace("snapshotRootWaitMillis=23", "snapshotRootWaitMillis=5")
                        stdout = with_capture_metadata(stdout, {"snapshotCaptureState": "COMPLETE",
                            "snapshotCaptureElapsedMillis": elapsed, "snapshotCaptureOverrunMillis": overrun}, "STATUS")
                        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr="")
                    return self.actor_result(command, **kwargs)

                with tempfile.TemporaryDirectory() as directory, \
                        patch("android_snapshot_census.subprocess.run", side_effect=observed):
                    output = Path(directory) / "capture-budget-census"
                    census = run_census("fake-adb", SERIAL, PACKAGE, output, invocations=1)
                    row = census["probes"][0]
                    self.assertEqual("passed" if expected else "failed", census["status"])
                    self.assertEqual(expected, row["withinSnapshotBudget"])
                    self.assertEqual(5, row["queries"][0]["result"]["snapshotRootWaitMillis"])
                    self.assertEqual(int(elapsed), row["queries"][0]["result"]["snapshotCaptureElapsedMillis"])
                    self.assertEqual(int(overrun), row["queries"][0]["result"]["snapshotCaptureOverrunMillis"])
                    self.assertEqual(census, json.loads((output / "census.json").read_text()))

    def test_series_rejects_wrong_order_identity_connection_counts_and_window_protocol(self):
        valid = series_for_probe("probe-a", 2)
        final = valid.index("INSTRUMENTATION_RESULT:")
        code_first = valid[:final] + "INSTRUMENTATION_CODE: -1\n" + valid[final:].replace("INSTRUMENTATION_CODE: -1\n", "")
        cases = (
            code_first,
            valid.replace("snapshotQueryIndex=1", "snapshotQueryIndex=0"),
            valid.replace("snapshotNonce=probe-a-1", "snapshotNonce=probe-a-0"),
            valid.replace("snapshotHelperPid=501", "snapshotHelperPid=502", 1),
            valid.replace("snapshotConnectionUptimeMillis=900", "snapshotConnectionUptimeMillis=901", 1),
            valid.replace("snapshotQuerySuccesses=2", "snapshotQuerySuccesses=1"),
            valid.replace(WINDOWS_BASE64, "invalid base64!"),
            valid.replace(WINDOWS_BASE64, base64.b64encode(b'[{"id":true,"type":1,"active":true,"focused":true}]').decode()),
            valid.replace("INSTRUMENTATION_STATUS_CODE: -1", "INSTRUMENTATION_STATUS: snapshotWindowsBase64=W10=\nINSTRUMENTATION_STATUS_CODE: -1", 1),
            valid.replace("INSTRUMENTATION_STATUS_CODE: -1", "INSTRUMENTATION_STATUS_CODE: 0", 1),
            valid.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
        )
        for stdout in cases:
            with self.subTest(stdout=stdout), self.assertRaises(ValueError):
                parse_series_result(stdout, 0, "probe-a", PACKAGE, 2)

    def test_series_records_capture_and_final_census_errors_without_accepting_them_as_success(self):
        samples, result, xmls = parse_series_result(series_for_probe("probe-a", 2, failed_index=1), 0, "probe-a", PACKAGE, 2)
        self.assertEqual(["passed", "failed"], [sample["status"] for sample in samples])
        self.assertEqual("No root", samples[1]["result"]["snapshotError"])
        self.assertEqual(23, samples[1]["result"]["snapshotRootWaitMillis"])
        self.assertEqual((1, 1), (result["snapshotQuerySuccesses"], result["snapshotQueryFailures"]))
        self.assertEqual([0], list(xmls))
        samples, result, _ = parse_series_result(series_for_probe("probe-a", 2, windows_error=True), 0, "probe-a", PACKAGE, 2)
        self.assertEqual(["passed", "passed"], [sample["status"] for sample in samples])
        self.assertEqual("Window census unavailable", result["snapshotWindowsError"])
        self.assertEqual(2, result["snapshotQuerySuccesses"])

    def test_partial_timeout_preserves_a_completed_query_and_blocks_a_surviving_helper(self):
        process_reads = 0
        instrument_calls = 0

        def observed(command, **kwargs):
            nonlocal process_reads, instrument_calls
            if command[3:] == ACTOR_ARGUMENTS[0]:
                process_reads += 1
                text = IDLE_PROCESS_LIST
                if process_reads > 1:
                    text += "501 1 dev.logisim.mobile.snapshot.e2e.test dev.logisim.mobile.snapshot.e2e.test\n"
                return subprocess.CompletedProcess(command, 0, stdout=text, stderr="")
            if command[3:6] == ["shell", "am", "instrument"]:
                instrument_calls += 1
                self.assertEqual(8, kwargs["timeout"])
                nonce = command[command.index("snapshotNonce") + 1]
                partial = series_for_probe(nonce, 4).split("INSTRUMENTATION_STATUS_CODE: -1\n", 1)[0] + "INSTRUMENTATION_STATUS_CODE: -1\n"
                raise subprocess.TimeoutExpired(command, 8, output=partial.encode(), stderr=b"helper stalled\n")
            return self.actor_result(command, **kwargs)

        with tempfile.TemporaryDirectory() as directory, \
                patch("android_snapshot_census.uuid.uuid4", return_value=SimpleNamespace(hex="probe-a")), \
                patch("android_snapshot_census.subprocess.run", side_effect=observed):
            output = Path(directory) / "partial-census"
            census = run_census("fake-adb", SERIAL, PACKAGE, output, query_count=4, invocations=2)
            self.assertEqual(["failed", "skipped"], [row["status"] for row in census["probes"]])
            self.assertEqual(1, instrument_calls)
            self.assertEqual([0], [sample["result"]["snapshotQueryIndex"] for sample in census["probes"][0]["queries"]])
            self.assertEqual([501], [process["pid"] for process in census["probes"][0]["actorsAfter"]["helperProcesses"]])
            self.assertIn("still owns the guest connection", census["probes"][1]["errors"][0])
            self.assertNotIn("snapshotCommand", census["probes"][1])
            self.assertEqual(SNAPSHOT_XML.replace(b"probe-a", b"probe-a-0"), (output / "probe-0-query-0.xml").read_bytes())
            self.assertEqual(census, json.loads((output / "census.json").read_text()))

    def test_existing_uiautomator_observer_prevents_instrumentation(self):
        def observed(command, **kwargs):
            if command[3:] == ACTOR_ARGUMENTS[0]:
                return subprocess.CompletedProcess(command, 0, stdout=IDLE_PROCESS_LIST +
                    "407 1 sh /system/bin/sh /system/bin/uiautomator dump\n", stderr="")
            return self.actor_result(command, **kwargs)

        with tempfile.TemporaryDirectory() as directory, patch("android_snapshot_census.subprocess.run", side_effect=observed):
            census = run_census("fake-adb", SERIAL, PACKAGE, Path(directory) / "busy-census", invocations=1)
            self.assertEqual("failed", census["status"])
            self.assertEqual("skipped", census["probes"][0]["status"])
            self.assertEqual([407], [row["pid"] for row in census["probes"][0]["actorsBefore"]["uiautomatorProcesses"]])

    def test_census_guards_package_serial_and_new_output_before_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            for package, serial in (("dev.logisim.mobile", SERIAL), ("dev.logisim.mobile.e2e;input", SERIAL),
                                    (PACKAGE, ""), (PACKAGE, "   ")):
                output = Path(directory) / "not-created"
                with self.subTest(package=package, serial=serial), self.assertRaises(ValueError):
                    run_census("fake-adb", serial, package, output)
                self.assertFalse(output.exists())
            output = Path(directory) / "already-exists"
            output.mkdir()
            (output / "keep.txt").write_text("existing evidence")
            with self.assertRaises(FileExistsError):
                run_census("fake-adb", SERIAL, PACKAGE, output)
            self.assertEqual("existing evidence", (output / "keep.txt").read_text())
            self.assertFalse((output / "census.json").exists())


if __name__ == "__main__":
    unittest.main()
