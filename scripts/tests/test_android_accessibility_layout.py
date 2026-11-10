"""Check Android layout navigation against XML fixtures, without adb or a device."""

import importlib.util
import itertools
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location(
    "android_accessibility_layout", Path(__file__).parents[1] / "android_accessibility_layout.py")
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)

# Include the original parser, restoration, matrix, and focus contracts in discovery.
ExistingContractTests = runner.HermeticTests
PACKAGE = "dev.logisim.mobile.e2e"


def node(identifier="", description="", rect="[20,200][280,248]", children="", **attributes):
    values = {"package": PACKAGE, "bounds": rect, "enabled": "true", **attributes}
    if identifier:
        values["resource-id"] = identifier
    if description:
        values["content-desc"] = description
    element = ET.Element("node", values)
    for child in ET.fromstring("<children>" + children + "</children>"):
        element.append(child)
    return ET.tostring(element, encoding="unicode")


def hierarchy(children):
    return ET.fromstring("<hierarchy>" + node(rect="[0,0][320,568]", children=children) + "</hierarchy>")


def action(description, rect="[20,200][280,248]"):
    return node(description=description, rect=rect, clickable="true")


def overflow(toolbar, rect="[270,40][318,88]"):
    return node(toolbar + "-overflow", rect=rect, clickable="true")


def menu(toolbar, children):
    return hierarchy(node(toolbar + "-menu", rect="[20,140][300,420]", children=children, scrollable="true"))


class FakeUi:
    package = PACKAGE

    def __init__(self, root):
        self.root = root
        self.taps, self.swipes, self.keys = [], [], []
        self.on_tap = lambda _node: None
        self.on_swipe = lambda _command: None
        self.on_back = lambda: None

    def screen(self):
        return self.root

    def tap(self, target):
        self.taps.append(target.get("resource-id") or target.get("content-desc"))
        self.on_tap(target)

    def adb(self, *command):
        if command[:3] != ("shell", "input", "swipe"):
            raise AssertionError(f"Unexpected fake adb call: {command}")
        self.swipes.append(command)
        self.on_swipe(command)

    def key(self, code):
        if code != 4:
            raise AssertionError(f"Unexpected key: {code}")
        self.keys.append(code)
        self.on_back()


class AndroidOverflowTests(unittest.TestCase):
    def setUp(self):
        self.process = patch("subprocess.run", side_effect=AssertionError("No device process is allowed in these tests"))
        self.process.start()
        self.addCleanup(self.process.stop)
        self.catalog = runner.Catalog.__new__(runner.Catalog)
        self.catalog.entries = {
            "a11y_project_tools": {"en": "Open project tools"},
            "a11y_open_properties": {"en": "Open properties"},
            "a11y_explorer_select": {"en": "Select {name}"},
            "simulation_options_title": {"en": "Simulation options"},
        }

    def driver(self, ui):
        return runner.Checkpoints(ui, self.catalog, (320, 568), 160)

    @staticmethod
    def selector(key):
        return {"key": key, "attribute": "content-desc"}

    def assert_vertical_inside(self, command, rectangle):
        x1, y1, x2, y2 = map(int, command[3:7])
        left, top, right, bottom = rectangle
        self.assertEqual(x1, x2)
        self.assertTrue(left < x1 < right)
        self.assertTrue(top < y1 < bottom and top < y2 < bottom)

    def test_tools_opens_overflow_then_scrolls_only_its_menu(self):
        toolbar = "editor-project-toolbar"
        ui = FakeUi(hierarchy(overflow(toolbar)))
        ui.on_tap = lambda _target: setattr(ui, "root", menu(toolbar, action("Open project tools", "[30,440][280,488]")))
        ui.on_swipe = lambda _command: setattr(ui, "root", menu(toolbar, action("Open project tools")))
        _, found = self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual("Open project tools", found.get("content-desc"))
        self.assertEqual([toolbar + "-overflow"], ui.taps)
        self.assertEqual(1, len(ui.swipes))
        self.assert_vertical_inside(ui.swipes[0], (20, 140, 300, 420))

    def test_minimal_height_tools_uses_command_overflow(self):
        toolbar = "editor-command-toolbar"
        ui = FakeUi(hierarchy(overflow(toolbar)))
        ui.on_tap = lambda _target: setattr(ui, "root", menu(toolbar, action("Open project tools")))
        self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual([toolbar + "-overflow"], ui.taps)
        self.assertEqual([], ui.swipes)

    def test_properties_uses_command_menu_without_opening_project_menu(self):
        project, command = "editor-project-toolbar", "editor-command-toolbar"
        ui = FakeUi(hierarchy(overflow(project) + overflow(command, "[270,100][318,148]")))
        ui.on_tap = lambda _target: setattr(ui, "root", menu(command, action("Open properties")))
        self.driver(ui).reveal(self.selector("a11y_open_properties"), ["horizontal"], interactive=True)
        self.assertEqual([command + "-overflow"], ui.taps)

    def test_simulation_menu_can_be_selected_explicitly(self):
        toolbar = "editor-simulation-toolbar"
        ui = FakeUi(hierarchy(overflow(toolbar)))
        ui.on_tap = lambda _target: setattr(ui, "root", menu(toolbar, action("Simulation options")))
        selector = {**self.selector("simulation_options_title"), "toolbars": [toolbar]}
        self.driver(ui).reveal(selector, ["horizontal"], interactive=True)
        self.assertEqual([toolbar + "-overflow"], ui.taps)

    def test_legacy_toolbar_still_scrolls_horizontally_without_overflow(self):
        def old_toolbar(rectangle):
            return hierarchy(node("editor-project-toolbar", rect="[0,40][320,88]",
                                  children=action("Open project tools", rectangle), scrollable="true"))
        ui = FakeUi(old_toolbar("[340,40][440,88]"))
        ui.on_swipe = lambda _command: setattr(ui, "root", old_toolbar("[200,40][300,88]"))
        self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual([], ui.taps)
        self.assertEqual(1, len(ui.swipes))
        x1, y1, x2, y2 = map(int, ui.swipes[0][3:7])
        self.assertNotEqual(x1, x2)
        self.assertEqual(y1, y2)

    def test_explorer_scrolls_list_viewport_and_excludes_sheet_header(self):
        def explorer(rectangle):
            body = node("a11y-circuit-list", rect="[12,100][308,420]",
                        children=action("Select Reset", rectangle), scrollable="true")
            return hierarchy(node("editor-tool-sheet", rect="[0,20][320,560]", children=body))
        ui = FakeUi(explorer("[20,450][280,498]"))
        ui.on_swipe = lambda _command: setattr(ui, "root", explorer("[20,250][280,298]"))
        selector = {**self.selector("a11y_explorer_select"), "arguments": {"name": "Reset"}}
        self.driver(ui).reveal(selector, ["vertical"], interactive=True)
        self.assertEqual(1, len(ui.swipes))
        self.assert_vertical_inside(ui.swipes[0], (12, 100, 308, 420))

    def test_whole_clickable_parent_is_revealed_before_accepting_visible_label(self):
        def explorer(parent_rect, label_rect):
            label = node(description="Select Reset", rect=label_rect)
            parent = node(rect=parent_rect, children=label, clickable="true")
            # The known viewport must constrain the target even without a scrollable attribute.
            return hierarchy(node("a11y-circuit-list", rect="[12,100][308,420]", children=parent))
        ui = FakeUi(explorer("[20,380][280,440]", "[24,390][250,410]"))
        ui.on_swipe = lambda _command: setattr(ui, "root", explorer("[20,300][280,360]", "[24,310][250,330]"))
        selector = {**self.selector("a11y_explorer_select"), "arguments": {"name": "Reset"}}
        self.driver(ui).reveal(selector, ["vertical"], interactive=True)
        self.assertEqual(1, len(ui.swipes))

    def test_missing_new_menu_tag_is_blocked_without_legacy_swipes(self):
        toolbar = "editor-project-toolbar"
        ui = FakeUi(hierarchy(overflow(toolbar)))
        ui.on_tap = lambda _target: setattr(ui, "root", hierarchy(""))
        clock = itertools.count(0, 3)
        with patch("time.monotonic", side_effect=lambda: next(clock)), patch("time.sleep"):
            with self.assertRaises(runner.Blocked):
                self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual([toolbar + "-overflow"], ui.taps)
        self.assertEqual([], ui.swipes)

    def test_visible_whole_action_does_not_open_overflow(self):
        ui = FakeUi(hierarchy(overflow("editor-project-toolbar") + action("Open project tools")))
        self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual([], ui.taps)
        self.assertEqual([], ui.swipes)

    def test_open_menu_ignores_duplicate_target_in_background(self):
        toolbar = "editor-project-toolbar"
        root = menu(toolbar, action("Open project tools"))
        root[0].append(ET.fromstring(action("Open project tools", "[20,40][200,88]")))
        ui = FakeUi(root)
        _, found = self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual("[20,200][280,248]", found.get("bounds"))
        self.assertEqual([], ui.taps)
        self.assertEqual([], ui.keys)

    def test_open_command_menu_is_searched_before_background_project_overflow(self):
        root = menu("editor-command-toolbar", action("Open project tools"))
        root[0].append(ET.fromstring(overflow("editor-project-toolbar")))
        ui = FakeUi(root)
        ui.on_tap = lambda _target: self.fail("An overflow behind the active command menu must not be tapped")
        _, found = self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual("Open project tools", found.get("content-desc"))
        self.assertEqual([], ui.taps)

    def test_background_target_is_only_accepted_after_menu_has_closed(self):
        toolbar = "editor-project-toolbar"
        root = menu(toolbar, action("Other action"))
        root[0].append(ET.fromstring(action("Open project tools", "[20,40][200,88]")))
        ui = FakeUi(root)
        ui.on_back = lambda: setattr(ui, "root", hierarchy(action("Open project tools", "[20,40][200,88]")))
        _, found = self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual([4], ui.keys)
        self.assertGreater(len(ui.swipes), 0)
        self.assertEqual("Open project tools", found.get("content-desc"))
        self.assertTrue(all(command[3] == command[5] for command in ui.swipes))

    def test_unrelated_open_menu_blocks_background_toolbar_action(self):
        root = menu("editor-simulation-toolbar", action("Other action"))
        root[0].append(ET.fromstring(action("Open project tools", "[20,40][200,88]")))
        ui = FakeUi(root)
        with self.assertRaisesRegex(runner.Blocked, "Another toolbar menu"):
            self.driver(ui).reveal(self.selector("a11y_project_tools"), ["horizontal"], interactive=True)
        self.assertEqual([], ui.taps)
        self.assertEqual([], ui.swipes)


if __name__ == "__main__":
    unittest.main()
