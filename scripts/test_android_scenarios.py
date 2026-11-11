#!/usr/bin/env python3
"""Check scenario dialog boundaries with literal UI XML and no device access."""

from contextlib import ExitStack
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parent


def forbid_process(*args, **kwargs):
    raise AssertionError("Scenario unit tests must not launch subprocesses")


def load_runner():
    spec = importlib.util.spec_from_file_location("android_scenarios_runner_under_test", SCRIPTS / "e2e-android.py")
    runner = importlib.util.module_from_spec(spec)
    with ExitStack() as guards:
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            guards.enter_context(patch.object(subprocess, name, side_effect=forbid_process))
        guards.enter_context(patch.object(sys, "path", [str(SCRIPTS), *sys.path]))
        spec.loader.exec_module(runner)
    return runner


RUNNER = load_runner()

REPORT_EN = """<hierarchy rotation="0">
  <node class="android.view.View" bounds="[20,100][400,700]">
    <node text="Import report" bounds="[40,120][380,160]"/>
    <node resource-id="" bounds="[40,170][380,580]">
      <node text="Source version: 3.8.0. Circuits: 1." bounds="[50,180][370,220]"/>
    </node>
    <node text="Close" clickable="true" enabled="true" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""

REPORT_PT = """<hierarchy rotation="0">
  <node class="android.view.View" bounds="[20,100][400,700]">
    <node text="Relatório de importação" bounds="[40,120][380,160]"/>
    <node resource-id="" bounds="[40,170][380,580]">
      <node text="Versão de origem: 3.8.0. Circuitos: 1." bounds="[50,180][370,220]"/>
    </node>
    <node text="Fechar" clickable="true" enabled="true" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""

THEME_CHOOSER = """<hierarchy rotation="0">
  <node package="thalesmms.logisim.mobile.e2e" resource-id="first-launch-theme" bounds="[20,100][400,700]">
    <node package="thalesmms.logisim.mobile.e2e" resource-id="first-launch-theme-system" text="Sistema"
          checkable="true" checked="true" clickable="true" bounds="[40,180][380,240]"/>
    <node package="thalesmms.logisim.mobile.e2e" resource-id="first-launch-theme-continue" text="Continuar"
          clickable="true" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""

EDITOR_WITH_REPORT_CAPTION = """<hierarchy rotation="0">
  <node bounds="[0,0][420,800]">
    <node resource-id="editor-project-toolbar" scrollable="true" bounds="[0,20][420,70]">
      <node text="Import report" content-desc="Import report" clickable="true" bounds="[250,20][410,70]"/>
    </node>
    <node resource-id="editor-canvas" content-desc="Circuit area. E2E_OUT: 1." bounds="[0,180][420,750]"/>
  </node>
</hierarchy>"""

ERROR_MODAL = """<hierarchy rotation="0">
  <node bounds="[20,100][400,700]">
    <node text="File not imported" bounds="[40,120][380,160]"/>
    <node text="The project XML is invalid." bounds="[40,170][380,240]"/>
    <node text="Close" clickable="true" enabled="true" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""

UNKNOWN_MODAL = """<hierarchy rotation="0">
  <node resource-id="editor-help" bounds="[20,100][400,700]">
    <node text="Help" bounds="[40,120][380,160]"/>
    <node text="Choose a component to inspect its properties." bounds="[40,170][380,240]"/>
    <node text="Close" clickable="true" enabled="true" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""

UNKNOWN_MODAL_MENTIONING_REPORT = """<hierarchy rotation="0">
  <node resource-id="editor-help" bounds="[20,100][400,700]">
    <node text="Help" bounds="[40,120][380,160]"/>
    <node text="Import report" bounds="[40,170][380,210]"/>
    <node text="Open this action to inspect migration details." bounds="[40,220][380,280]"/>
    <node text="Close" clickable="true" enabled="true" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""

SIMULATION_NOT_READY = """<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" bounds="[0,0][420,800]">
    <node text="Loading project…" bounds="[60,350][360,410]"/>
  </node>
</hierarchy>"""

SIMULATION_READY = """<hierarchy rotation="0">
  <node class="android.widget.FrameLayout" bounds="[0,0][420,800]">
    <node bounds="[0,100][420,160]">
      <node text="Step" content-desc="Advance the clock one half-cycle" clickable="true"
            enabled="true" bounds="[180,100][260,160]"/>
    </node>
    <node resource-id="editor-canvas" bounds="[0,180][420,750]"/>
  </node>
</hierarchy>"""

SIMULATION_OVERFLOW = """<hierarchy rotation="0">
  <node package="thalesmms.logisim.mobile.e2e" bounds="[0,0][420,800]">
    <node package="thalesmms.logisim.mobile.e2e" resource-id="editor-simulation-toolbar" bounds="[0,100][420,160]">
      <node package="thalesmms.logisim.mobile.e2e" text="Steps 56 · Insert OR" bounds="[10,110][260,150]"/>
      <node package="thalesmms.logisim.mobile.e2e" resource-id="editor-simulation-toolbar-overflow"
            text="More" clickable="true" enabled="true" bounds="[360,100][420,160]"/>
    </node>
    <node package="thalesmms.logisim.mobile.e2e" resource-id="editor-canvas"
          content-desc="Circuit area. E2E_OUT: 1. Use two fingers to zoom and pan." bounds="[0,180][420,750]"/>
  </node>
</hierarchy>"""

RUN_MENU = """<hierarchy rotation="0">
  <node package="thalesmms.logisim.mobile.e2e" bounds="[20,100][400,700]">
    <node package="thalesmms.logisim.mobile.e2e" text="Simulation options" bounds="[40,110][380,160]"/>
    <node package="thalesmms.logisim.mobile.e2e" resource-id="editor-simulation-toolbar-menu"
          scrollable="true" bounds="[40,180][380,580]">
      <node package="thalesmms.logisim.mobile.e2e" text="▶ Run" content-desc="▶ Run"
            enabled="true" clickable="true" bounds="[50,200][370,260]"/>
    </node>
    <node package="thalesmms.logisim.mobile.e2e" text="Close" bounds="[270,610][380,660]"/>
  </node>
</hierarchy>"""


class FakeAndroidUi(RUNNER.AndroidUi):
    def __init__(self):
        self.package = "thalesmms.logisim.mobile.e2e"
        self.captures = []
        self.taps = []
        self.events = []

    def optional(self, root, **selector):
        return RUNNER.AndroidUi.optional(root, **selector)

    def capture(self, name, root=None):
        self.captures.append((name, root))
        self.events.append(("capture", name))

    def tap(self, node, *, timeout=30):
        self.taps.append(node)
        self.events.append(("tap", node.get("text")))

    def adb(self, *args, **kwargs):
        raise AssertionError("Dialog recognition must not use adb")

    def _run(self, *args, **kwargs):
        raise AssertionError("Scenario unit tests must not use the adb process")

    def screen(self, *args, **kwargs):
        if self.captures:
            return self.captures[-1][1]
        raise AssertionError("Dialog recognition must not query an unrecognized window")


class QueuedAndroidUi(FakeAndroidUi):
    def __init__(self, roots):
        super().__init__()
        self.roots = list(roots)
        self.screen_requests = []

    def screen(self, *args, **kwargs):
        self.screen_requests.append((args, kwargs))
        if not self.roots:
            raise AssertionError("The scenario requested an unexpected extra hierarchy")
        self.current_root = self.roots.pop(0)
        return self.current_root


class OverflowAndroidUi(QueuedAndroidUi):
    def _run(self, args, **kwargs):
        # The overflow navigator must tap only the current toolbar's More
        # button. Every other adb operation, including a swipe, is forbidden.
        if args[:3] != ("shell", "input", "tap"):
            raise AssertionError(f"Unexpected adb operation: {args}")
        x, y = map(int, args[3:])
        buttons = [node for node in self.current_root.iter("node")
                   if node.get("resource-id", "").endswith("-toolbar-overflow")]
        self._require_test_app()
        candidates = [node for node in buttons if self._bounds(node)[0] <= x < self._bounds(node)[2]
                      and self._bounds(node)[1] <= y < self._bounds(node)[3]]
        if len(candidates) != 1:
            raise AssertionError("Overflow navigation attempted to tap outside its current More button")
        self.tap(candidates[0])


class ImportReportBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.guards = ExitStack()
        self.addCleanup(self.guards.close)
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            self.guards.enter_context(patch.object(subprocess, name, side_effect=forbid_process))
        self.ui = FakeAndroidUi()
        self.scenarios = RUNNER.Scenarios(self.ui)

    def assert_untouched(self, xml):
        self.assertFalse(self.scenarios.acknowledge_import_report(ET.fromstring(xml)))
        self.assertEqual([], self.ui.captures)
        self.assertEqual([], self.ui.taps)
        self.assertEqual(0, self.scenarios.import_report_count)

    def test_known_english_report_is_captured_before_its_unique_close_is_tapped(self):
        root = ET.fromstring(REPORT_EN)
        self.assertTrue(self.scenarios.acknowledge_import_report(root))
        self.assertEqual([("capture", "import-report-1"), ("tap", "Close")], self.ui.events)
        self.assertIs(root, self.ui.captures[0][1])
        self.assertEqual(1, self.scenarios.import_report_count)

    def test_incoming_fixture_names_distinguish_each_open_request(self):
        prefix = "content://thalesmms.logisim.mobile.e2e.test.documents/document/"
        first = "e2e-" + "a" * 32 + ".circ"
        second = "e2e-" + "b" * 32 + ".circ"
        self.assertEqual(first, RUNNER.fixture_document_name(self.ui.package, prefix + first))
        self.assertEqual(second, RUNNER.fixture_document_name(self.ui.package, prefix + second))
        for uri in (prefix + "../writable.circ", prefix + first + "?old=1", prefix.replace(".test.", ".foreign.") + first):
            with self.subTest(uri=uri), self.assertRaises(AssertionError):
                RUNNER.fixture_document_name(self.ui.package, uri)

    def test_import_marker_changes_content_identity_without_changing_circuit_values(self):
        original = b'<project version="1.0"><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(100,100)"/></circuit></project>'
        first = ET.fromstring(RUNNER.mark_import_source(original, "VIEW-FIRST"))
        second = ET.fromstring(RUNNER.mark_import_source(original, "SEND-SECOND"))
        self.assertEqual("VIEW-FIRST", first.get("e2e-import-token"))
        self.assertEqual("SEND-SECOND", second.get("e2e-import-token"))
        self.assertNotIn(b'e2e-import-token', original)
        self.assertEqual({'lib':'0', 'name':'Pin', 'loc':'(100,100)'}, second.find('circuit/comp').attrib)

    def test_known_portuguese_report_is_acknowledged(self):
        self.assertTrue(self.scenarios.acknowledge_import_report(ET.fromstring(REPORT_PT)))
        self.assertEqual([("capture", "import-report-1"), ("tap", "Fechar")], self.ui.events)

    def test_report_artifact_names_advance_for_each_modal(self):
        for xml in (REPORT_EN, REPORT_PT):
            self.assertTrue(self.scenarios.acknowledge_import_report(ET.fromstring(xml)))
        self.assertEqual(["import-report-1", "import-report-2"], [name for name, _ in self.ui.captures])
        self.assertEqual(2, self.scenarios.import_report_count)

    def test_toolbar_report_caption_with_editor_canvas_is_left_intact(self):
        self.assert_untouched(EDITOR_WITH_REPORT_CAPTION)

    def test_error_modal_is_not_closed_even_when_it_has_a_close_button(self):
        self.assert_untouched(ERROR_MODAL)

    def test_unknown_modal_is_left_intact(self):
        self.assert_untouched(UNKNOWN_MODAL)

    def test_help_modal_mentioning_report_is_not_acknowledged(self):
        self.assert_untouched(UNKNOWN_MODAL_MENTIONING_REPORT)

    def test_report_body_without_known_title_is_left_intact(self):
        self.assert_untouched(REPORT_EN.replace('text="Import report"', 'text="Unexpected dialog"'))

    def test_report_title_without_known_summary_is_left_intact(self):
        self.assert_untouched(REPORT_EN.replace('text="Source version: 3.8.0. Circuits: 1."',
                                               'text="Unrecognized details"'))

    def test_ambiguous_close_fails_without_tapping_either_control(self):
        root = ET.fromstring(REPORT_EN)
        ET.SubElement(root.find("node"), "node", text="Close", bounds="[140,610][250,660]")
        with self.assertRaisesRegex(AssertionError, "action_close.*found 2"):
            self.scenarios.acknowledge_import_report(root)
        self.assertEqual([], self.ui.taps)
        self.assertEqual(["import-report-1"], [name for name, _ in self.ui.captures])

    def test_missing_close_fails_without_tapping(self):
        root = ET.fromstring(REPORT_EN)
        root.find("node").remove(root.find("node/node[@text='Close']"))
        with self.assertRaisesRegex(AssertionError, "action_close.*found 0"):
            self.scenarios.acknowledge_import_report(root)
        self.assertEqual([], self.ui.taps)

    def test_error_appearing_after_report_capture_is_not_dismissed_outside(self):
        report = ET.fromstring(REPORT_EN)
        error = ET.fromstring(ERROR_MODAL.replace('text="Close"', 'text="Got it"'))
        ui = QueuedAndroidUi([error])
        scenarios = RUNNER.Scenarios(ui)
        self.assertFalse(scenarios.acknowledge_import_report(report))
        self.assertEqual([("capture", "import-report-1")], ui.events)
        self.assertEqual([], ui.taps)
        self.assertEqual([], ui.roots)

    def test_error_control_above_report_prevents_automatic_acknowledgement(self):
        report = ET.fromstring(REPORT_EN)
        ET.SubElement(report, "node", text="Got it", clickable="true", bounds="[100,300][200,350]")
        self.assertFalse(self.scenarios.acknowledge_import_report(report))
        self.assertEqual([], self.ui.events)

    def test_wait_for_import_error_never_acknowledges_an_underlying_report(self):
        report = ET.fromstring(REPORT_EN)
        error = ET.fromstring(ERROR_MODAL.replace('text="Close"', 'text="Got it"'))
        ui = QueuedAndroidUi([report, error])
        scenarios = RUNNER.Scenarios(ui)
        with patch.object(RUNNER.time, "sleep"):
            node = scenarios.wait_key("action_understood")
        self.assertEqual("Got it", node.get("text"))
        self.assertEqual([], ui.events)
        self.assertEqual([], ui.roots)

    def test_simulation_click_captures_unknown_screen_then_taps_only_ready_step(self):
        first = ET.fromstring(SIMULATION_NOT_READY)
        second = ET.fromstring(SIMULATION_READY)
        ui = QueuedAndroidUi([first, second])
        scenarios = RUNNER.Scenarios(ui)
        with patch.object(RUNNER.time, "sleep") as sleep:
            scenarios.click_key("a11y_step_clock", description=True)
        self.assertEqual([("capture", "simulation-toolbar-not-ready-1"), ("tap", "Step")], ui.events)
        self.assertIs(first, ui.captures[0][1])
        self.assertEqual([second.find("node/node/node")], ui.taps)
        self.assertEqual(1, scenarios.unready_simulation_count)
        self.assertEqual(2, len(ui.screen_requests))
        self.assertEqual([], ui.roots)
        sleep.assert_called_once_with(0.2)

    def test_step_in_overflow_is_found_inside_the_current_simulation_menu(self):
        menu = RUN_MENU.replace('text="▶ Run" content-desc="▶ Run"',
                                'text="Step" content-desc="Advance the clock one half-cycle"')
        ui = OverflowAndroidUi([ET.fromstring(SIMULATION_OVERFLOW), ET.fromstring(menu)])
        RUNNER.Scenarios(ui).click_key("a11y_step_clock", description=True)
        self.assertEqual([("tap", "More"), ("tap", "Step")], ui.events)
        self.assertEqual(2, len(ui.screen_requests))

    def test_run_and_pause_use_fresh_menus_after_each_action_closes_its_window(self):
        run = ET.fromstring(RUN_MENU)
        pause = ET.fromstring(RUN_MENU.replace("▶ Run", "Ⅱ Pause"))
        ui = OverflowAndroidUi([ET.fromstring(SIMULATION_OVERFLOW), run,
                                ET.fromstring(SIMULATION_OVERFLOW), pause])
        scenarios = RUNNER.Scenarios(ui)
        scenarios.action_with_icon("action_run")
        scenarios.action_with_icon("action_pause")
        self.assertEqual([("tap", "More"), ("tap", "▶ Run"), ("tap", "More"), ("tap", "Ⅱ Pause")], ui.events)
        self.assertIs(run.find("node/node[@resource-id='editor-simulation-toolbar-menu']/node"), ui.taps[1])
        self.assertIs(pause.find("node/node[@resource-id='editor-simulation-toolbar-menu']/node"), ui.taps[3])
        self.assertEqual([], ui.roots)

    def test_counter_in_toolbar_leading_slot_does_not_open_its_overflow(self):
        root = ET.fromstring(SIMULATION_OVERFLOW)
        self.assertEqual(56, self.scenarios.simulation_steps(root))
        self.assertEqual([], self.ui.events)

    def test_editor_readiness_does_not_open_hidden_project_actions(self):
        root = ET.fromstring(SIMULATION_OVERFLOW)
        ui = QueuedAndroidUi([root])
        self.assertIs(root, RUNNER.Scenarios(ui).wait_editor())
        self.assertEqual([], ui.events)

    def test_fresh_setup_confirms_system_then_acknowledges_the_import_report(self):
        chooser = ET.fromstring(THEME_CHOOSER)
        report = ET.fromstring(REPORT_PT)
        editor = ET.fromstring(SIMULATION_OVERFLOW)
        ui = QueuedAndroidUi([chooser, chooser, chooser, report, report, editor])
        with patch.object(RUNNER.time, "sleep"):
            self.assertIs(editor, RUNNER.Scenarios(ui).wait_editor(complete_first_launch=True))
        self.assertEqual([("tap", "Sistema"), ("tap", "Continuar"),
                          ("capture", "import-report-1"), ("tap", "Fechar")], ui.events)
        self.assertEqual([], ui.roots)

    def test_editor_wait_outside_fresh_setup_does_not_confirm_the_theme(self):
        editor = ET.fromstring(SIMULATION_OVERFLOW)
        ui = QueuedAndroidUi([ET.fromstring(THEME_CHOOSER), editor])
        with patch.object(RUNNER.time, "sleep"):
            self.assertIs(editor, RUNNER.Scenarios(ui).wait_editor())
        self.assertEqual([], ui.events)


class SwipeRecordingAndroidUi(QueuedAndroidUi):
    def __init__(self, roots):
        super().__init__(roots)
        self.swipes = []

    def adb(self, *args, **kwargs):
        assert args[:3] == ("shell", "input", "swipe"), args
        self.swipes.append(tuple(map(int, args[3:7])))
        return ""


SHORT_XML_DIALOG = """<hierarchy rotation="1">
  <node class="android.widget.FrameLayout" bounds="[140,28][460,296]">
    <node text="Project .circ" bounds="[164,52][293,84]"/>
    <node resource-id="editor-xml-body" class="android.widget.ScrollView" scrollable="true" bounds="[164,100][436,200]">
      <node text="Copy the XML to save the project" bounds="[164,100][436,132]"/>
      {copy}
    </node>
    <node text="Close" clickable="true" bounds="[253,238][291,258]"/>
  </node>
</hierarchy>"""


class RevealKeyTests(unittest.TestCase):
    def setUp(self):
        self.guards = ExitStack()
        self.addCleanup(self.guards.close)
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            self.guards.enter_context(patch.object(subprocess, name, side_effect=forbid_process))

    def test_a_100_px_landscape_dialog_body_is_scrolled_to_its_copy_action(self):
        hidden = ET.fromstring(SHORT_XML_DIALOG.format(copy=""))
        shown = ET.fromstring(SHORT_XML_DIALOG.format(
            copy='<node text="Copy" clickable="true" bounds="[164,150][240,198]"/>'))
        ui = SwipeRecordingAndroidUi([hidden, shown, shown])  # The second read confirms the fling has stopped.
        node = RUNNER.Scenarios(ui).reveal_key("action_copy")
        self.assertEqual("Copy", node.get("text"))
        self.assertEqual([(300, 180, 300, 115)], ui.swipes)

    def test_a_stable_dialog_without_the_action_still_fails(self):
        hidden = SHORT_XML_DIALOG.format(copy="")
        ui = SwipeRecordingAndroidUi([ET.fromstring(hidden) for _ in range(18)])
        ui.output = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: [path.unlink() for path in ui.output.iterdir()] and None)
        with self.assertRaisesRegex(AssertionError, "could not be reached by scrolling"):
            RUNNER.Scenarios(ui).reveal_key("action_copy")
        self.assertEqual(18, len(list(ui.output.glob("reveal-action_copy-*.xml"))))

    def test_swipes_inside_a_clipped_text_block_continue_until_the_action_appears(self):
        hidden = SHORT_XML_DIALOG.format(copy="")
        shown = SHORT_XML_DIALOG.format(copy='<node text="Copy" clickable="true" bounds="[164,150][240,198]"/>')
        ui = SwipeRecordingAndroidUi([ET.fromstring(hidden) for _ in range(8)] + [ET.fromstring(shown)] * 2)
        self.assertEqual("Copy", RUNNER.Scenarios(ui).reveal_key("action_copy").get("text"))
        self.assertEqual(8, len(ui.swipes))

    def test_a_control_still_moving_after_a_swipe_is_read_again_at_its_final_position(self):
        hidden = ET.fromstring(SHORT_XML_DIALOG.format(copy=""))
        moving = ET.fromstring(SHORT_XML_DIALOG.format(copy='<node text="Copy" clickable="true" bounds="[164,170][240,198]"/>'))
        final = ET.fromstring(SHORT_XML_DIALOG.format(copy='<node text="Copy" clickable="true" bounds="[164,150][240,198]"/>'))
        ui = SwipeRecordingAndroidUi([hidden, moving, final, final])
        self.assertEqual("[164,150][240,198]", RUNNER.Scenarios(ui).reveal_key("action_copy").get("bounds"))
        self.assertEqual(1, len(ui.swipes))


class SimulationCounterAcknowledgementTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name)

    @staticmethod
    def counter(value):
        return ET.fromstring(f'''<hierarchy><node package="thalesmms.logisim.mobile.e2e" bounds="[0,0][420,800]">
          <node package="thalesmms.logisim.mobile.e2e" resource-id="editor-simulation-toolbar" bounds="[0,100][420,180]">
            <node package="thalesmms.logisim.mobile.e2e" text="Passos {value}" bounds="[10,110][140,160]"/>
          </node></node></hierarchy>''')

    def scenario(self, counts):
        ui = QueuedAndroidUi([self.counter(value) for value in counts])
        ui.output = self.output
        return RUNNER.Scenarios(ui), ui

    def test_late_publication_waits_for_the_requested_step_without_another_input(self):
        scenarios, ui = self.scenario([15, 16])
        with patch.object(RUNNER.time, "sleep"):
            self.assertEqual(16, scenarios.wait_simulation_steps(16))
        receipt = json.loads((self.output / "simulation-steps-16.json").read_text())
        self.assertEqual([{"count": 15}, {"count": 16}], receipt["observations"])
        self.assertEqual("passed", receipt["status"])
        self.assertEqual([], ui.events)

    def test_a_reset_can_publish_zero_after_the_old_positive_count(self):
        scenarios, ui = self.scenario([56, 0])
        with patch.object(RUNNER.time, "sleep"):
            self.assertEqual(0, scenarios.wait_simulation_steps(0))
        self.assertEqual([], ui.events)

    def test_advancing_past_a_positive_checkpoint_fails_instead_of_accepting_it(self):
        scenarios, ui = self.scenario([17])
        with self.assertRaisesRegex(AssertionError, "past 16 steps: observed 17"):
            scenarios.wait_simulation_steps(16)
        receipt = json.loads((self.output / "simulation-steps-16.json").read_text())
        self.assertEqual("failed", receipt["status"])
        self.assertEqual([{"count": 17}], receipt["observations"])
        self.assertEqual([], ui.events)

    def test_the_existing_deadline_records_lower_counts_and_fails(self):
        scenarios, ui = self.scenario([15] * 20)
        clock = iter(range(100))
        with patch.object(RUNNER.time, "monotonic", side_effect=lambda: next(clock) / 5), \
                patch.object(RUNNER.time, "sleep"), \
                self.assertRaisesRegex(AssertionError, "did not reach 16 steps within 1s"):
            scenarios.wait_simulation_steps(16, timeout=1)
        receipt = json.loads((self.output / "simulation-steps-16.json").read_text())
        self.assertTrue(receipt["observations"])
        self.assertEqual({15}, {entry["count"] for entry in receipt["observations"]})
        self.assertEqual("failed", receipt["status"])
        self.assertEqual([], ui.events)


if __name__ == "__main__":
    unittest.main()
