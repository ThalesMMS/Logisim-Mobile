import importlib.util
import hashlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

from android_fpga_scenarios import FpgaScenarios, assert_project_archive, assert_review, diagnostic_nodes, job_identifier, labelled_editor, reviewed_parameters, signal_row, verify_native_audit

PACKAGE = "thalesmms.logisim.mobile.e2e"


def tree(body):
    return ET.fromstring('<hierarchy><node package="' + PACKAGE + '">' + body + '</node></hierarchy>')


def node(text, children="", kind="android.widget.TextView"):
    element = ET.Element("node", {"package": PACKAGE, "text": text, "class": kind})
    if children:
        element.append(ET.fromstring(children))
    return ET.tostring(element, encoding="unicode")


def parameters(goal="synthesis"):
    value = dict(flow="ice40", part="hx1k", package="tq144", top="logisim_fpga_top", language="verilog",
                 goal=goal, sources=["source-0000.v", "source-0001.v"])
    if goal == "bitstream":
        value["constraint"] = "pins.pcf"
    return value


class SelectorsTest(unittest.TestCase):
    def test_signal_controls_belong_to_the_named_row(self):
        root = tree('<node>' + node("p_a · Input") + node("Connection: Unmapped") + '</node><node>' +
                    node("p_b · Input") + node("Connection: Unmapped") + '</node>')
        row = signal_row(root, PACKAGE, {"p_b · Input"}, {"Connection"})
        self.assertEqual(["p_b · Input", "Connection: Unmapped"], [item.get("text") for item in row])

    def test_a_flat_column_is_split_at_the_signal_headings(self):
        body = (node("p_a · Input") + node("Connection: Physical") + node("Pin") +
                node("p_b · Input") + node("Connection: Unmapped"))
        first = signal_row(tree(body), PACKAGE, {"p_a · Input"}, {"Connection"})
        self.assertEqual(["p_a · Input", "Connection: Physical", "Pin"], [item.get("text") for item in first])
        last = signal_row(tree(body), PACKAGE, {"p_b · Input"}, {"Connection"})
        self.assertEqual(["p_b · Input", "Connection: Unmapped"], [item.get("text") for item in last])

    def test_a_row_cut_by_the_viewport_does_not_borrow_another_rows_connection(self):
        body = node("p_a · Input") + node("Connection: Physical") + node("p_b · Input") + node("Input type: Signal")
        with self.assertRaisesRegex(AssertionError, "found 0"):
            signal_row(tree(body), PACKAGE, {"p_b · Input"}, {"Connection"})

    def test_duplicate_headings_are_rejected(self):
        body = node("p_a · Input") + node("p_a · Input") + node("Connection: Unmapped")
        with self.assertRaises(AssertionError):
            signal_row(tree(body), PACKAGE, {"p_a · Input"}, {"Connection"})

    def test_reviewed_plan_is_read_from_one_native_text_object(self):
        root = tree(node(json.dumps(parameters())))
        self.assertEqual(parameters(), reviewed_parameters(root, PACKAGE))
        with self.assertRaises(AssertionError):
            reviewed_parameters(tree(node(json.dumps(parameters())) * 2), PACKAGE)

    def test_wrong_device_programming_and_unsafe_sources_are_rejected(self):
        assert_review(parameters(), "synthesis")
        assert_review(parameters("bitstream"), "bitstream")
        for change in (dict(part="85k"), dict(package="wrong"), dict(target="board"), dict(flash=True),
                       dict(sources=["../bad.v"]), dict(goal="bitstream")):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                assert_review(parameters() | change, "synthesis")

    def test_job_identifier_must_be_unique(self):
        job = "a" * 32
        self.assertEqual(job, job_identifier(tree(node("Job: " + job)), PACKAGE, ["Job: {id}"]))
        with self.assertRaises(AssertionError):
            job_identifier(tree(node("Job: " + job) * 2), PACKAGE, ["Job: {id}"])

    def test_board_xml_label_does_not_select_an_existing_pin_editor(self):
        root = tree('<node>' + node("Board XML") + node("old board", kind="android.widget.EditText") + '</node>' +
                    '<node>' + node("Physical pin") + node("21", kind="android.widget.EditText") + '</node>')
        self.assertEqual("old board", labelled_editor(root, PACKAGE, {"Board XML"}).get("text"))
        with self.assertRaises(AssertionError):
            labelled_editor(tree(node("Board XML") + node("old", kind="android.widget.EditText") + node("21", kind="android.widget.EditText")),
                            PACKAGE, {"Board XML"})



def tagged(text, tag):
    element = ET.Element("node", {"package": PACKAGE, "text": text, "class": "android.widget.TextView", "resource-id": tag})
    return ET.tostring(element, encoding="unicode")


class DiagnosticTest(unittest.TestCase):
    @staticmethod
    def flow(root):
        flow = FpgaScenarios.__new__(FpgaScenarios)
        flow.ui = SimpleNamespace(package=PACKAGE, visible_node=lambda root, item: True)
        flow.seek = lambda predicate, description, **kwargs: predicate(root)
        return flow

    def test_a_mapping_problem_row_is_found_by_its_tag_without_a_code_prefix(self):
        root = tree(tagged("p_led: 4 bits unmapped", "fpga-diagnostic-unmapped-port-p_led") +
                    tagged("Pin U16 is used twice", "fpga-diagnostic-pin-already-used-p_y-0"))
        self.assertIs(root, self.flow(root).diagnostic("unmapped-port"))
        self.assertIs(root, self.flow(root).diagnostic("pin-already-used"))
        self.assertEqual([], diagnostic_nodes(tree(node("Unmapped port p_led")), PACKAGE, "unmapped-port"),
                         "An untagged row is matched only by its leading code")

    def test_a_companion_job_error_is_found_by_its_tag_or_its_code_text(self):
        tagged_error = tree(tagged("fpga-stage-failed: nextpnr could not place the design", "fpga-diagnostic-fpga-stage-failed"))
        self.assertIs(tagged_error, self.flow(tagged_error).diagnostic("fpga-stage-failed"))
        untagged = tree(node("fpga-stage-failed: nextpnr could not place the design"))
        self.assertIs(untagged, self.flow(untagged).diagnostic("fpga-stage-failed"))

    def test_another_code_or_package_is_not_a_match(self):
        root = tree(tagged("fpga-tool-missing: yosys", "fpga-diagnostic-fpga-tool-missing"))
        self.assertIsNone(self.flow(root).diagnostic("fpga-stage-failed"))
        other = ET.fromstring('<hierarchy><node package="other" text="fpga-stage-failed: x"/></hierarchy>')
        self.assertEqual([], diagnostic_nodes(other, PACKAGE, "fpga-stage-failed"))


class ArchiveTest(unittest.TestCase):
    @staticmethod
    def archive(changes=None):
        files = {"c_main.v": "module c_main(input p_a,p_b, output p_y); assign p_y=p_a & p_b; endmodule\n",
                 "logisim_fpga_top.v": "module logisim_fpga_top; endmodule\n",
                 "pins.pcf": "set_io fpga_p0_b0 21\nset_io fpga_p1_b0 22\nset_io fpga_p2_b0 23\n",
                 "synthesis.ys": "synth_ice40 -top logisim_fpga_top -json synthesized.json\n"}
        files.update(changes or {})
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name, value in files.items():
                archive.writestr(name, value)
        return output.getvalue()

    def test_zip_checks_real_contents_and_all_paths(self):
        self.assertEqual(4, len(assert_project_archive(self.archive())))
        for change in ({"pins.pcf": "set_io fpga_p0_b0 999\n"}, {"../escape": "x"}, {"synthesis.ys": "wrong"}):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                assert_project_archive(self.archive(change))


class PairingFlowTest(unittest.TestCase):
    def flow(self, *, token_text="", password="true", visible=True):
        address = ET.Element("node", {"package": PACKAGE, "class": "android.widget.EditText", "text": "http://127.0.0.1:8042", "enabled": "true"})
        ET.SubElement(address, "node", {"package": PACKAGE, "class": "android.view.View", "text": "HTTPS address or local loopback"})
        token = ET.Element("node", {"package": PACKAGE, "class": "android.widget.EditText", "text": token_text, "password": password, "enabled": "true"})
        ET.SubElement(token, "node", {"package": PACKAGE, "class": "android.view.View", "content-desc": "Pairing token, confidential field"})
        root = tree(ET.tostring(address, encoding="unicode") + ET.tostring(token, encoding="unicode"))
        labels = {"companion_ui_address": {"HTTPS address or local loopback"},
                  "companion_ui_token_a11y": {"Pairing token, confidential field"}}
        flow = FpgaScenarios.__new__(FpgaScenarios)
        flow.ui = SimpleNamespace(package=PACKAGE, screen=lambda: root, visible_node=lambda root, node: visible,
                                  input_text=unittest.mock.Mock(), capture=unittest.mock.Mock())
        flow.e = SimpleNamespace(scope=lambda root: (root, "editor-tool-sheet"), values=lambda key: labels[key],
                                 hide_keyboard=unittest.mock.Mock(), click=unittest.mock.Mock(), reveal=unittest.mock.Mock())
        flow.s = SimpleNamespace(catalog={"additional_companion_available": {"en": "available", "pt": "disponível"},
                                          "companion_operation_fpga_synthesize": {"en": "Synthesize HDL with Yosys", "pt": "Sintetizar HDL com Yosys"}})
        flow.host = SimpleNamespace(endpoint="http://127.0.0.1:54321", token="a" * 64)
        flow.open_tool = unittest.mock.Mock()
        flow.close_tool = unittest.mock.Mock()
        def reveal(predicate, description, **kwargs):
            value = predicate(root)
            assert value is not None, "The requested editor is clipped or absent"
            return value
        flow.await_reveal = reveal
        return flow

    def test_pairing_resolves_the_confidential_label_inside_the_native_edit_text(self):
        flow = self.flow()
        flow.pair()
        self.assertEqual([flow.host.endpoint, flow.host.token], [call.args[1] for call in flow.ui.input_text.call_args_list])
        self.assertEqual("true", flow.ui.input_text.call_args_list[1].args[0].get("password"))
        self.assertEqual(2, flow.e.hide_keyboard.call_count)

    def test_pairing_preserves_nonempty_unmasked_or_clipped_token_fields(self):
        for changes in ({"token_text": "existing"}, {"password": "false"}, {"visible": False}):
            with self.subTest(changes=changes):
                flow = self.flow(**changes)
                with self.assertRaises(AssertionError):
                    flow.pair()
                flow.ui.input_text.assert_not_called()

    def test_pairing_waits_for_the_connected_tool_card_instead_of_the_disposed_form_notice(self):
        flow = self.flow()
        def visible_card(key, **kwargs):
            assert key in {"additional_companion_verified", "additional_companion_operation"}, "The active panel exposes no scrollable content"
        flow.e.reveal.side_effect = visible_card
        flow.pair()
        self.assertEqual("additional_companion_verified", flow.e.reveal.call_args_list[0].args[0])
        self.assertEqual("additional_companion_operation", flow.e.reveal.call_args_list[1].args[0])
        self.assertEqual(flow.s.catalog["companion_operation_fpga_synthesize"], flow.e.reveal.call_args_list[1].kwargs["operation"])
        self.assertEqual(flow.s.catalog["additional_companion_available"], flow.e.reveal.call_args_list[1].kwargs["status"])


class BoardFileImportTest(unittest.TestCase):
    def flow(self, directory, *, corrupt_read=None, stale_board=False, published=True):
        flow = FpgaScenarios.__new__(FpgaScenarios)
        flow.fixtures = Path(__file__).resolve().parents[1] / "fixtures/user-scenarios"
        uploaded = {}
        state = SimpleNamespace(root=tree(""), reads=0)
        def adb(*args, **kwargs):
            if args[0] == "push":
                uploaded[args[2]] = Path(args[1]).read_bytes()
                return "pushed"
            if args[:3] == ("shell", "am", "broadcast"):
                self.assertTrue(args[-1].startswith("file:///storage/emulated/0/Download/"))
                return "Broadcast completed: result=0"
            if args[0] == "shell":
                self.assertIn("content query", args[1])
                self.assertIn("/storage/emulated/0/Download/logisim-fpga-board-", args[1])
                self.assertGreater(kwargs["timeout"], 0)
                self.assertLessEqual(kwargs["timeout"], 30)
                return "Row: 0 mime_type=text/xml, is_download=1, is_pending=" + ("0" if published else "1")
            self.assertEqual(("exec-out", "cat"), args[:2])
            self.assertFalse(kwargs["text"])
            state.reads += 1
            data = uploaded[args[2]]
            return data[:245] if state.reads == corrupt_read else data
        def choose(name):
            self.assertTrue(name.startswith("logisim-fpga-board-FPGA_E2E_HX1K_"))
            board = ET.fromstring(next(iter(uploaded.values())))
            observed = "old-board" if stale_board else board.tag
            state.root = tree(node(observed) + node("Open FPGA (Yosys/nextpnr) · ice40 · hx1k · tq144"))
        def wait(predicate, description, timeout):
            self.assertEqual(30, timeout)
            result = predicate(state.root)
            assert result is not None, "The imported FPGA board identity was not observed"
            return result
        flow.ui = SimpleNamespace(package=PACKAGE, output=directory, screen=lambda: state.root, adb=adb,
                                  visible_node=lambda root, item: True, capture=unittest.mock.Mock(),
                                  input_text=unittest.mock.Mock(side_effect=AssertionError("Board setup must use its complete native file")))
        flow.s = SimpleNamespace(incoming=unittest.mock.Mock(), fixture=lambda value: "content://fixture/circuit",
                                 choose_downloads=unittest.mock.Mock(), choose_native_file=unittest.mock.Mock(side_effect=choose))
        vendors = {"fpga_vendor_open_fpga": {"Open FPGA (Yosys/nextpnr)", "FPGA aberto (Yosys/nextpnr)"}}
        flow.e = SimpleNamespace(export=lambda name: (b"unchanged-circuit", None), nodes=lambda root, key: [],
                                 click=unittest.mock.Mock(), scope=lambda root: (root, "editor-tool-sheet"), wait=wait,
                                 values=lambda key, **arguments: vendors[key])
        flow.open_tool = unittest.mock.Mock()
        return flow, uploaded

    def test_native_file_import_confirms_complete_bytes_and_public_board_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            flow, uploaded = self.flow(Path(directory))
            self.assertEqual(b"unchanged-circuit", flow.load(flow.fixtures / "fpga-hx1k.xml"))
            flow.e.click.assert_called_once_with("fpga_import_board_file", scroll=True)
            flow.s.choose_downloads.assert_called_once_with()
            flow.s.choose_native_file.assert_called_once()
            flow.ui.input_text.assert_not_called()
            receipt = json.loads(next(Path(directory).glob("board-*.json")).read_text())
            source = next(iter(uploaded.values()))
            self.assertGreater(len(source), 245)
            self.assertEqual("PASS", receipt["status"])
            self.assertEqual("native-open-document", receipt["transport"])
            self.assertTrue(receipt["published"])
            digest = hashlib.sha256(source).hexdigest()
            self.assertEqual([digest] * 3, [receipt[key] for key in ("stagedXmlSha256", "uploadedSha256", "sourceAfterSha256")])
            expected = ET.parse(flow.fixtures / "fpga-hx1k.xml").getroot()
            observed = ET.fromstring(source)
            self.assertEqual(receipt["boardIdentity"], observed.tag)
            observed.tag = expected.tag
            self.assertEqual(ET.tostring(expected), ET.tostring(observed))

    def test_truncated_source_before_or_after_import_cannot_reach_mapping(self):
        for corrupt_read in (1, 2):
            with self.subTest(read=corrupt_read), tempfile.TemporaryDirectory() as directory:
                flow, _ = self.flow(Path(directory), corrupt_read=corrupt_read)
                with self.assertRaisesRegex(AssertionError, "complete staged XML|Import changed"):
                    flow.load(flow.fixtures / "fpga-hx1k.xml")
                receipt = json.loads(next(Path(directory).glob("board-*.json")).read_text())
                self.assertEqual("FAIL", receipt["status"])
                self.assertNotIn("fpga_validate_mapping", [call.args[0] for call in flow.e.click.call_args_list])
                if corrupt_read == 1:
                    flow.s.choose_native_file.assert_not_called()
                flow.ui.capture.assert_not_called()

    def test_an_old_board_card_cannot_confirm_a_new_import(self):
        with tempfile.TemporaryDirectory() as directory:
            flow, _ = self.flow(Path(directory), stale_board=True)
            with self.assertRaisesRegex(AssertionError, "identity was not observed"):
                flow.load(flow.fixtures / "fpga-hx1k.xml")
            self.assertEqual("FAIL", json.loads(next(Path(directory).glob("board-*.json")).read_text())["status"])
            flow.ui.capture.assert_not_called()

    def test_unpublished_xml_cannot_open_the_native_picker(self):
        with tempfile.TemporaryDirectory() as directory:
            flow, _ = self.flow(Path(directory), published=False)
            clock = iter([0, 0, 0, 0, 0, 31])
            with patch("android_fpga_scenarios.time.monotonic", side_effect=lambda: next(clock)), \
                    patch("android_fpga_scenarios.time.sleep"), self.assertRaisesRegex(AssertionError, "not published"):
                flow.load(flow.fixtures / "fpga-hx1k.xml")
            flow.e.click.assert_not_called()
            flow.s.choose_downloads.assert_not_called()
            receipt = json.loads(next(Path(directory).glob("board-*.json")).read_text())
            self.assertEqual("FAIL", receipt["status"])
            self.assertFalse(receipt["published"])

    def test_late_publication_metadata_cannot_renew_the_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            flow, _ = self.flow(Path(directory))
            clock = iter([0, 0, 0, 31])
            with patch("android_fpga_scenarios.time.monotonic", side_effect=lambda: next(clock)), \
                    self.assertRaisesRegex(AssertionError, "deadline expired"):
                flow.load(flow.fixtures / "fpga-hx1k.xml")
            flow.e.click.assert_not_called()
            flow.s.choose_downloads.assert_not_called()
            receipt = json.loads(next(Path(directory).glob("board-*.json")).read_text())
            self.assertEqual("FAIL", receipt["status"])
            self.assertFalse(receipt["published"])


class TerminalTest(unittest.TestCase):
    def flow(self, values):
        clock = SimpleNamespace(now=0.0)
        catalog = {"fpga_companion_state_" + value: {"en": value} for value in ("queued", "running", "succeeded", "failed", "cancelled")}
        def screen(**kwargs):
            clock.now += 5
            return tree(node(values.pop(0) if len(values) > 1 else values[0]))
        flow = FpgaScenarios.__new__(FpgaScenarios)
        flow.s = SimpleNamespace(catalog=catalog)
        flow.ui = SimpleNamespace(package=PACKAGE, screen=screen, visible_node=lambda root, item: True)
        flow.e = SimpleNamespace(values=lambda key, state: {"Status: " + state["en"]})
        flow.seek = lambda predicate, description, **kwargs: None
        return flow, clock

    def test_stable_running_tree_keeps_polling_until_success(self):
        flow, clock = self.flow(["Status: running"] * 4 + ["Status: succeeded"])
        with patch("android_fpga_scenarios.time.monotonic", side_effect=lambda: clock.now), patch("android_fpga_scenarios.time.sleep"):
            self.assertEqual("Status: succeeded", flow.terminal("succeeded").get("text"))
        self.assertEqual(25, clock.now)

    def test_cancel_cannot_pass_after_success(self):
        flow, clock = self.flow(["Status: succeeded"])
        with patch("android_fpga_scenarios.time.monotonic", side_effect=lambda: clock.now), patch("android_fpga_scenarios.time.sleep"):
            with self.assertRaisesRegex(AssertionError, "while expecting cancelled"):
                flow.terminal("cancelled")

    def test_pending_job_stops_at_the_original_deadline(self):
        flow, clock = self.flow(["Status: running"])
        with patch("android_fpga_scenarios.time.monotonic", side_effect=lambda: clock.now), patch("android_fpga_scenarios.time.sleep"):
            with self.assertRaisesRegex(AssertionError, "fixed 60 second deadline"):
                flow.terminal("succeeded")
        self.assertEqual(60, clock.now)

    def test_pending_http_response_polls_a_stable_tree_without_more_search_swipes(self):
        clock = SimpleNamespace(now=0.0)
        values = ["Waiting"] * 4 + ["Review ready"]
        def screen(**kwargs):
            clock.now += 1
            return tree(node(values.pop(0)))
        flow = FpgaScenarios.__new__(FpgaScenarios)
        flow.ui = SimpleNamespace(screen=screen)
        flow.seek = unittest.mock.Mock(side_effect=AssertionError("not rendered yet"))
        with patch("android_fpga_scenarios.time.monotonic", side_effect=lambda: clock.now), patch("android_fpga_scenarios.time.sleep"):
            result = flow.await_reveal(lambda root: root if any(item.get("text") == "Review ready" for item in root.iter("node")) else None,
                                       "review", timeout=30)
        self.assertIsNotNone(result)
        self.assertEqual(1, flow.seek.call_count)


class AuditTest(unittest.TestCase):
    def test_cancel_requires_actual_cancelled_state_and_request(self):
        job = "a" * 32
        audit = {"operations": ["fpga-synthesize"], "programmingRegistered": False, "events": [],
                 "jobs": [{"jobId": job, "operation": "fpga-synthesize", "state": "cancelled"}],
                 "requests": [{"action": "cancel", "jobId": job, "status": "RETURNED"},
                              {"action": "submit", "jobId": job, "status": "RETURNED"}]}
        observed = [{"case": "cancel", "jobId": job, "reviewedParameters": parameters("bitstream"),
                     "observedNonterminalState": "running"}]
        self.assertEqual("PASS", verify_native_audit(audit, observed)["status"])
        for change in ({"jobs": audit["jobs"] + audit["jobs"]}, {"requests": []},
                       {"jobs": [audit["jobs"][0] | {"state": "succeeded"}]}, {"programmingRegistered": True}):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                verify_native_audit(audit | change, observed)


class RunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("fpga_runner_test", Path(__file__).with_name("run-android-fpga.py"))
        cls.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.runner)

    def test_reverse_ownership_uses_the_local_port(self):
        raw = "UsbFfs tcp:123 tcp:456\n"
        self.assertEqual("tcp:456", self.runner.reverse_target(raw, 123))
        self.assertIsNone(self.runner.reverse_target(raw, 456))
        with self.assertRaises(ValueError):
            self.runner.reverse_target(raw * 2, 123)

    def test_prepare_only_never_starts_adb_host_or_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "prepared"
            with patch.object(self.runner, "AndroidUi", side_effect=AssertionError("ADB must not start")), \
                    patch.object(self.runner, "FpgaNativeCompanion", side_effect=AssertionError("Host must not start")):
                self.assertEqual(0, self.runner.main(["--prepare-only", "--output", str(output)]))
            receipt = json.loads((output / "fpga-run.json").read_text())
            self.assertEqual("NATIVE_NOT_RUN", receipt["nativeExecutionGate"])
            self.assertEqual("NOT_QUALIFIED", receipt["qualificationGate"])
            self.assertFalse(receipt["programmingEnabled"])

    def test_failed_audit_and_export_still_close_the_host_and_owned_reverse(self):
        calls = []
        class Host:
            endpoint = "http://127.0.0.1:5467"
            token = "x" * 64
            def audit(self):
                raise ValueError("audit failed")
            def export_artifacts(self, path):
                raise ValueError("export failed")
            def close(self):
                calls.append("closed")
        class Ui:
            package = PACKAGE
            snapshot_package = "thalesmms.logisim.mobile.snapshot.e2e.test"
            reverse_installed = False
            def adb(self, *args):
                calls.append(args)
                if args == ("shell", "settings", "get", "system", "system_locales"):
                    return "en-US"
                if args == ("reverse", "--list"):
                    return "UsbFfs tcp:5467 tcp:5467\n" if self.reverse_installed else ""
                if args[:2] == ("reverse", "--no-rebind"):
                    self.reverse_installed = True
                return "fixture"
            def verify_installed_apk(self, *args):
                return {"verified": True}
            def fail_artifacts(self, *args):
                calls.append("failure retained")
        setup = {"sdk": Path("/unused-sdk"), "package": PACKAGE, "apks": (Path("app"), Path("test"), Path("snapshot")),
                 "receipt": {"gitHead": "a" * 40}, "receipt_bytes": b"{}", "identities": {}}
        common = SimpleNamespace(Scenarios=lambda ui: SimpleNamespace(wait_editor=lambda: None))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "execution"
            with patch.object(self.runner, "support", return_value=common), patch.object(self.runner, "preflight", return_value=setup), \
                    patch.object(self.runner, "AndroidUi", return_value=Ui()), patch.object(self.runner, "FpgaNativeCompanion", return_value=Host()), \
                    patch.object(self.runner, "run_fpga", side_effect=ValueError("primary UI failure")):
                self.assertEqual(1, self.runner.main(["--execute", "--serial", "emulator-5556", "--output", str(output)]))
            report = json.loads((output / "fpga-run.json").read_text())
        self.assertEqual("primary UI failure", report["error"]["message"])
        self.assertEqual(2, len(report["cleanupErrors"]))
        self.assertIn("closed", calls)
        self.assertIn(("reverse", "--remove", "tcp:5467"), calls)


if __name__ == "__main__":
    unittest.main()
