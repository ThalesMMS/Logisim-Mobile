"""Offline selector, preflight and failed-capture behavior. No device processes."""

from contextlib import redirect_stderr
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import android_layout12_scenarios as layout
from android_ui import AndroidUi


PACKAGE = "thalesmms.logisim.mobile.e2e"


def tree(children):
    return ET.fromstring('<hierarchy><node package="' + PACKAGE +
                         '" bounds="[0,0][320,568]">' + children + '</node></hierarchy>')


def element(**attributes):
    return ET.tostring(ET.Element("node", {"package": PACKAGE, "bounds": "[10,100][250,156]", **attributes}), encoding="unicode")


def field(value="Re", *, package=PACKAGE, focused="true"):
    return element(**{"class": "android.widget.EditText", "resource-id": "a11y-circuit-search",
                      "package": package, "focused": focused, "text": value})


def instance(ui):
    value = layout.Layout12.__new__(layout.Layout12)
    value.ui, value.s, value.unit, value.fonts = ui, Mock(), "small-phone", (1.0,)
    value.records, value.counter = [], 0
    return value


class LayoutSelectorTests(unittest.TestCase):
    def test_fixture_contains_the_literal_painted_geometry_and_known_input(self):
        content = layout.fixture_bytes()
        root = ET.fromstring(content)
        images = [node for node in root.findall("circuit/comp") if node.get("name") == "Image"]
        self.assertEqual(["(100,100)", "(260,180)"], [node.get("loc") for node in images])
        self.assertEqual([("32", "24"), ("40", "32")], [(node.find("a[@name='width']").get("val"),
                                                          node.find("a[@name='height']").get("val")) for node in images])
        pin = root.find("circuit/comp[@name='Pin']")
        self.assertEqual("Reset", pin.find("a[@name='label']").get("val"))
        self.assertEqual("false", pin.find("a[@name='output']").get("val"))
        contract = json.loads(layout.CONTRACT.read_text())
        decoder = layout.runpy.run_path(str(SCRIPTS / "test-android-assets.py"))["decode_android_png"]
        with tempfile.TemporaryDirectory() as directory:
            for image, marker in zip(images, contract["markers"]):
                encoded = image.find("a[@name='image']").get("val").removeprefix("data:image/png;base64,")
                content = base64.b64decode(encoded, validate=True)
                self.assertEqual(marker["embeddedPngSha256"], hashlib.sha256(content).hexdigest())
                path = Path(directory) / (marker["id"] + ".png")
                path.write_bytes(content)
                width, height, channels, pixels = decoder(path)
                self.assertEqual((1, 1, 4), (width, height, channels))
                self.assertEqual(bytes([*marker["rgb"], 255]), pixels)

    def test_same_identity_and_selected_state_must_survive_without_reselection(self):
        selected = element(**{"resource-id": "canvas-component:p1", "selected": "true"})
        self.assertEqual("canvas-component:p1", layout.selected_canvas_component(tree(selected), PACKAGE, "p1").get("resource-id"))
        for content in (selected.replace('selected="true"', 'selected="false"'),
                        selected.replace("canvas-component:p1", "canvas-component:p2"), selected + selected):
            with self.subTest(content=content), self.assertRaises(AssertionError):
                layout.selected_canvas_component(tree(content), PACKAGE, "p1")

    def test_exact_search_value_and_focus_are_observed(self):
        root = tree(field())
        editor = layout.unique_editor(root, PACKAGE, "a11y-circuit-search", "Re", focused=True)
        self.assertEqual("Re", editor.get("text"))

    def test_other_field_or_foreign_package_cannot_supply_search(self):
        for content in (field(package=PACKAGE + ".other"), element(**{"class": "android.widget.EditText", "text": "Re"})):
            with self.subTest(content=content), self.assertRaisesRegex(AssertionError, "exact.*tag"):
                layout.unique_editor(tree(content), PACKAGE, "a11y-circuit-search", "Re", focused=True)

    def test_duplicate_tag_caption_and_lost_focus_fail(self):
        for root, expected in ((tree(field() + field()), "tag"), (tree(field("Re\nFind")), "Expected search"),
                               (tree(field(focused="false")), "lost focus")):
            with self.subTest(expected=expected), self.assertRaisesRegex(AssertionError, expected):
                layout.unique_editor(root, PACKAGE, "a11y-circuit-search", "Re", focused=True)

    def test_view_tag_must_own_one_editor_without_global_fallback(self):
        root = tree('<node package="' + PACKAGE + '" resource-id="a11y-circuit-search">' +
                    element(**{"class": "android.widget.EditText", "text": "Re"}) + '</node>' +
                    element(**{"class": "android.widget.EditText", "text": "wrong"}))
        self.assertEqual("Re", layout.unique_editor(root, PACKAGE, "a11y-circuit-search", "Re").get("text"))
        empty_tag = tree(element(**{"resource-id": "a11y-circuit-search"}) + field().replace('resource-id="a11y-circuit-search"', 'resource-id="other"'))
        with self.assertRaisesRegex(AssertionError, "own one EditText"):
            layout.unique_editor(empty_tag, PACKAGE, "a11y-circuit-search", "Re")

    def test_open_toolbar_menu_owns_actions_instead_of_background_duplicate(self):
        foreground = '<node package="' + PACKAGE + '" resource-id="editor-command-toolbar-menu">' + element(text="Properties") + '</node>'
        root = tree(foreground + element(text="Properties"))
        ui = Mock(package=PACKAGE)
        ui.tagged.side_effect = lambda root, tag: AndroidUi.tagged(ui, root, tag)
        owner, tag = instance(ui).scope(root)
        self.assertEqual("editor-command-toolbar-menu", tag)
        self.assertEqual(1, sum(n.get("text") == "Properties" for n in owner.iter("node")))

    def test_two_presented_toolbar_menus_are_rejected(self):
        root = tree(element(**{"resource-id": "editor-command-toolbar-menu"}) +
                    element(**{"resource-id": "editor-project-toolbar-menu"}))
        ui = Mock(package=PACKAGE)
        ui.tagged.side_effect = lambda root, tag: AndroidUi.tagged(ui, root, tag)
        with self.assertRaisesRegex(AssertionError, "Only one"):
            instance(ui).scope(root)


class LayoutCaptureTests(unittest.TestCase):
    def test_raw_dumps_survive_an_unrecognized_focused_window_format(self):
        with tempfile.TemporaryDirectory() as directory:
            ui = Mock(package=PACKAGE, output=Path(directory))
            root = tree("")
            ui.screen.return_value = root
            ui.adb.side_effect = lambda *args, **kwargs: {
                ("shell", "pidof", PACKAGE): "1234\n",
                ("shell", "dumpsys", "window", "-a"): "new unrecognized raw window format\n",
                ("shell", "dumpsys", "activity", "activities"): "raw activity\n",
                ("shell", "wm", "density"): "Physical density: 160\n",
            }[args]
            screenshot = Path(directory) / "capture.png"
            screenshot.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + (320).to_bytes(4, "big") + (568).to_bytes(4, "big"))
            hierarchy = Path(directory) / "capture.xml"
            hierarchy.write_text(ET.tostring(root, encoding="unicode"))
            ui.capture.return_value = {"screenshot": str(screenshot), "hierarchy": str(hierarchy)}
            probe = instance(ui)
            with self.assertRaisesRegex(AssertionError, "WindowManager focus"):
                probe.record("unknown-format")
            report = json.loads((ui.output / "layout12-observations.json").read_text())
            record = report["records"][0]
            self.assertEqual("FAILED", record["status"])
            self.assertEqual("new unrecognized raw window format\n", Path(record["artifacts"]["window"]).read_text())
            self.assertEqual("raw activity\n", Path(record["artifacts"]["activity"]).read_text())
            self.assertEqual("1234\n", Path(record["artifacts"]["pid-before"]).read_text())
            self.assertEqual("1234\n", Path(record["artifacts"]["pid-after"]).read_text())
            self.assertEqual("NOT_RUN", report["splitScreen"])

    def test_clipped_or_nonclickable_action_is_never_tapped(self):
        root = tree(element(text="Tools", clickable="false", enabled="true"))
        node = next(n for n in root.iter("node") if n.get("text") == "Tools")
        ui = Mock(package=PACKAGE)
        ui.visible_node.return_value = True
        probe = instance(ui)
        probe.reveal = Mock(return_value=node)
        probe.record = Mock(return_value=(root, {}))
        probe.nodes = Mock(return_value=[node])
        with self.assertRaisesRegex(AssertionError, "clickable"):
            probe.press("a11y_project_tools", description=True, toolbar=True)
        ui.tap.assert_not_called()
        self.assertEqual("top", probe.reveal.call_args.kwargs["toolbar"])

    def test_tablet_with_natural_landscape_maps_to_portrait_rotation_one(self):
        settings = {"accelerometer_rotation": "0", "user_rotation": "0", "font_scale": "1.0"}
        commands = []
        def adb(*args):
            commands.append(args)
            if args == ("shell", "wm", "size"):
                return "Physical size: 2560x1600\n"
            if args == ("shell", "wm", "density"):
                return "Physical density: 320\n"
            if args[:4] == ("shell", "settings", "get", "system"):
                return settings[args[4]]
            if args[:4] == ("shell", "settings", "put", "system"):
                settings[args[4]] = args[5]
                return ""
            raise AssertionError("Unexpected device command in offline fake")
        with tempfile.TemporaryDirectory() as directory:
            probe = instance(Mock(output=Path(directory)))
            probe.unit = "tablet"
            probe.ui.adb.side_effect = adb
            with patch.object(layout, "wait_display", return_value={"density": 320, "font_scale": 1.0}) as wait:
                probe.configure(0, 1.0, editor_ready=False)
                self.assertEqual((800, 1280, 1.0, 320), wait.call_args.args[1:])
                self.assertEqual("1", settings["user_rotation"])
                probe.configure(1, 1.0, editor_ready=False)
                self.assertEqual((1280, 800, 1.0, 320), wait.call_args.args[1:])
                self.assertEqual("0", settings["user_rotation"])
            self.assertFalse(any(command[:2] == ("shell", "wm") and len(command) == 4 for command in commands))
            probe.s.wait_editor.assert_not_called()


class LayoutCliTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("layout_e2e_runner", SCRIPTS / "e2e-android.py")
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)

    def test_invalid_layout_options_fail_before_artifact_or_device_process(self):
        cases = (("--scenario", "files", "--layout12-unit", "tablet"),
                 ("--scenario", "layout12", "--layout12-unit", "split"),
                 ("--scenario", "layout12", "--layout12-font", "3"),
                 ("--scenario", "layout12"))
        for arguments in cases:
            with self.subTest(arguments=arguments), patch.dict("os.environ", {"ANDROID_SERIAL": ""}), \
                    patch.object(sys, "argv", ["runner", *arguments]), \
                    patch.object(self.runner.subprocess, "run", side_effect=AssertionError("No processes permitted")), \
                    patch.object(self.runner.subprocess, "check_output", side_effect=AssertionError("No processes permitted")), \
                    redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as result:
                self.runner.main()
            self.assertEqual(2, result.exception.code)


def explorer(search_bounds=None, list_bounds="[24,154][544,236]"):
    search = "" if search_bounds is None else element(**{"class": "android.widget.EditText",
                                                         "resource-id": "a11y-circuit-search", "bounds": search_bounds})
    return ET.fromstring('<hierarchy><node package="' + PACKAGE + '" bounds="[0,0][568,320]">' +
                         element(**{"resource-id": "editor-tool-sheet", "bounds": "[0,0][568,320]"})[:-2] + ">" +
                         element(**{"resource-id": "a11y-circuit-list", "scrollable": "true", "bounds": list_bounds})[:-2] +
                         ">" + search + "</node></node></node></hierarchy>")


class ExplorerSearchRevealTests(unittest.TestCase):
    def probe(self, roots):
        ui = Mock(package=PACKAGE)
        ui.tagged.side_effect = lambda root, tag: AndroidUi.tagged(ui, root, tag)
        ui.visible_node.side_effect = lambda root, node, within=None: AndroidUi.visible_node(ui, root, node, within)
        ui._rectangle = AndroidUi._rectangle
        queue = list(roots)
        ui.screen.side_effect = lambda *args, **kwargs: queue.pop(0)
        value = instance(ui)
        value.press = Mock()
        value.wait = Mock(side_effect=lambda predicate, description: predicate(queue.pop(0)))
        return value, ui

    def swipes(self, ui):
        return [tuple(map(int, call.args[3:7])) for call in ui.adb.call_args_list if call.args[:3] == ("shell", "input", "swipe")]

    def test_a_settled_list_scrolls_up_to_compose_the_search_below(self):
        value, ui = self.probe([explorer(), explorer(), explorer("[56,173][544,230]")])
        value.open_explorer()
        self.assertEqual([(284, 219, 284, 170)], self.swipes(ui))

    def test_a_composed_search_partly_below_the_list_needs_no_swipe(self):
        # Focusing the field brings it into view; swiping would overshoot it.
        value, ui = self.probe([explorer("[56,208][544,256]", "[56,148][544,252]")])
        value.open_explorer()
        self.assertEqual([], self.swipes(ui))

    def test_a_list_still_changing_is_read_again_before_any_swipe(self):
        value, ui = self.probe([explorer(list_bounds="[24,200][544,280]"), explorer(), explorer(),
                                explorer("[56,173][544,230]")])
        value.open_explorer()
        self.assertEqual([(284, 219, 284, 170)], self.swipes(ui))


class LayoutExportPreparationTests(unittest.TestCase):
    def test_receiver_returns_to_editor_before_xml_navigation(self):
        spec = importlib.util.spec_from_file_location("layout_export_runner", SCRIPTS / "e2e-android.py")
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        with tempfile.TemporaryDirectory() as directory:
            ui = Mock(package=PACKAGE, output=Path(directory), adb_path="/forbidden/adb", serial="offline")
            scenarios = runner.Scenarios(ui)
            state = {"menu": False, "receiver": False}
            canvas_prefix = scenarios.catalog["a11y_canvas"]["en"].split("{outputs}", 1)[0].strip()

            def screen(*args, **kwargs):
                self.assertFalse(state["receiver"], "The public receiver must finish before checking editor readiness")
                content = element(**{"content-desc": canvas_prefix, "resource-id": "editor-canvas"})
                if state["menu"]:
                    content += element(**{"resource-id": "editor-project-toolbar-menu"})
                return tree(content)

            def adb(*arguments, **kwargs):
                if arguments[:5] == ("shell", "am", "start", "-W", "-n"):
                    state["receiver"] = True
                    return ""
                self.assertEqual(("shell", "run-as", PACKAGE + ".test"), arguments[:3])
                return ""

            def click(**selector):
                self.assertIn(selector, ({"text": "Prepare XML copy"}, {"text": "Read copied XML"}))
                self.assertTrue(state["receiver"])
                state["receiver"] = False

            ui.adb.side_effect = adb
            ui.click.side_effect = click
            ui.screen.side_effect = screen
            ui.tagged.side_effect = lambda root, tag: AndroidUi.tagged(ui, root, tag)
            ui.visible_node.return_value = True
            scenarios.click_key = Mock()
            # The XML dialog's Close is located only to wait for the clipboard overlay.
            scenarios.find_key = Mock()
            probe = instance(ui)
            probe.s = scenarios
            probe.reveal = Mock(side_effect=lambda *args, **kwargs: state.update(menu=True))
            # A presented menu prevents the receiver from returning to an editor.
            state["menu"] = True
            with self.assertRaisesRegex(AssertionError, "toolbar menu is still open"):
                scenarios.export_xml("obstructed-editor")
            state["menu"] = False
            fixture = layout.fixture_bytes()
            with patch.object(runner.subprocess, "run", return_value=SimpleNamespace(stdout=fixture)):
                for name, export in (("editing-baseline", lambda value: layout._Editing.export(probe, value)),
                                     ("layout-baseline", probe.export)):
                    xml, project = export(name)
                    self.assertEqual(fixture.decode(), xml)
                    self.assertEqual(3, len(project.findall("circuit/comp")))
                    self.assertEqual(fixture, (ui.output / f"{name}.circ").read_bytes())
            self.assertFalse(state["menu"])
            probe.reveal.assert_not_called()

    def test_cleanup_getter_error_does_not_replace_primary_scenario_failure(self):
        original = {"wm_size": {"override": None}, "wm_density": {"override": None},
                    "system": {"font_scale": "1.0", "accelerometer_rotation": "0", "user_rotation": "0"}, "services": {}}
        with tempfile.TemporaryDirectory() as directory:
            probe = instance(Mock(package=PACKAGE, output=Path(directory)))
            probe.record = Mock(return_value=(None, {"window": {"windowingMode": "fullscreen"}}))
            probe.configure = Mock(side_effect=ValueError("primary layout setup failure"))
            with patch.object(layout, "inventory", side_effect=[original, RuntimeError("secondary getter failure")]):
                with self.assertRaisesRegex(ValueError, "primary layout setup failure"):
                    probe.run()
            data = json.loads((probe.ui.output / "layout12-observations.json").read_text())
            self.assertEqual("primary layout setup failure", data["records"][-2]["error"])
            cleanup = data["records"][-1]
            self.assertEqual("FAIL", cleanup["status"])
            self.assertEqual("secondary getter failure", cleanup["verificationError"])
            self.assertEqual(5, len(cleanup["commands"]))
            self.assertTrue(all(command["status"] == "RESTORED" for command in cleanup["commands"]))

    def test_unverified_cleanup_cannot_qualify_a_successful_scenario(self):
        original = {"wm_size": {"override": None}, "wm_density": {"override": None},
                    "system": {"font_scale": "1.0", "accelerometer_rotation": "0", "user_rotation": "0"}, "services": {}}
        with tempfile.TemporaryDirectory() as directory:
            probe = instance(Mock(package=PACKAGE, output=Path(directory)))
            with patch.object(layout, "inventory", side_effect=RuntimeError("cleanup unavailable")):
                with self.assertRaisesRegex(AssertionError, "did not verify restoration"):
                    probe.restore_configuration(original, require_success=True)
            self.assertEqual("FAIL", probe.records[-1]["status"])


if __name__ == "__main__":
    unittest.main()
