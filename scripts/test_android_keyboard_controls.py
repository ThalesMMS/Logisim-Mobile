"""Replay observed keyboard controls without invoking a device or process."""

import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from android_editing_scenarios import _Editing
from android_keyboard_controls import (KeyboardLabelTap, KeyboardRowReveal,
                                       click_keyboard_control, keyboard_control_plan)
from android_ui import AndroidUi

FIXTURE = Path(__file__).parent / "fixtures/io-keyboard-backspace-3a.xml"
PACKAGE = "thalesmms.logisim.mobile.e2e"
BACKSPACE = "io_ascii_backspace"
FORM_FEED = "io_ascii_form_feed"
CATALOG = {BACKSPACE: {"en": "ASCII backspace"}, FORM_FEED: {"en": "ASCII form feed"}}


def tree():
    return ET.fromstring(FIXTURE.read_bytes())


def label(root, text="ASCII backspace"):
    return next(node for node in root.iter("node") if node.get("text") == text)


def parents(root):
    return {child: parent for parent in root.iter() for child in parent}


def revealed_form_feed():
    # A separate hypothetical next observation, not coordinates predicted by the planner.
    root = tree()
    text = label(root, "ASCII form feed")
    owner = parents(root)[text]
    text.set("bounds", "[723,1158][995,1211]")
    owner.set("bounds", "[660,1122][1059,1248]")
    return root


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakeUi(AndroidUi):
    def __init__(self, output):
        super().__init__("/FORBIDDEN/adb", "offline", PACKAGE, output)
        self.calls = []
        self.roots = []
        self.screen_timeouts = []
        self.clock = None
        self.screen_cost = 0

    def _run(self, *args, **options):
        raise AssertionError("A real process must never be invoked")

    def screen(self, timeout=45):
        self.screen_timeouts.append(timeout)
        if self.clock:
            self.clock.now += self.screen_cost
        if len(self.roots) > 1:
            return self.roots.pop(0)
        return self.roots[0]

    def tap(self, node, *, timeout=30):
        self._require_test_app()
        self.calls.append(("tap", node, ((self._bounds(node)[0] + self._bounds(node)[2]) // 2,
                                         (self._bounds(node)[1] + self._bounds(node)[3]) // 2), timeout))

    def adb(self, *args, timeout=30, **options):
        self.calls.append(("adb", args, timeout))
        return ""


class KeyboardControlsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.ui = FakeUi(self.temp.name)
        self.editor = _Editing(SimpleNamespace(ui=self.ui, catalog=CATALOG))
        self.editor.records = []
        self.block = patch("subprocess.run", side_effect=AssertionError("No process allowed"))
        self.block.start()
        self.addCleanup(self.block.stop)

    def plan(self, root, key=BACKSPACE):
        return keyboard_control_plan(self.editor, root, key)

    def test_observed_tree_reproduces_old_rejection_and_resolves_original_label(self):
        root = tree()
        before = ET.tostring(root)
        self.assertEqual([], self.editor.nodes(root, BACKSPACE))
        found = self.plan(root)
        self.assertIsInstance(found, KeyboardLabelTap)
        self.assertIs(found.label, label(root))
        self.assertIs(found.target, parents(root)[found.label])
        self.assertEqual((656, 1184), found.point)
        self.assertFalse(self.ui.visible_node(root, found.target))
        self.assertTrue(self.ui.visible_node(root, found.label))
        self.assertEqual(before, ET.tostring(root))

    def test_valid_tap_uses_original_label_and_separate_owner_without_geometry_changes(self):
        root = tree()
        original = ET.tostring(root)
        self.ui.roots = [root]
        click_keyboard_control(self.editor, BACKSPACE)
        self.assertEqual("tap", self.ui.calls[0][0])
        self.assertIs(self.ui.calls[0][1], label(root))
        self.assertEqual((656, 1184), self.ui.calls[0][2])
        self.assertEqual(original, ET.tostring(root))
        record = self.editor.records[0]
        self.assertEqual("[455,1122][858,1248]", record["targetBounds"])
        self.assertEqual([656, 1184], record["point"])
        self.assertTrue(record["commandCompleted"])
        self.assertTrue((Path(self.temp.name) / record["tree"]).is_file())

    def test_form_feed_at_clip_edge_is_navigation_not_a_valid_label_tap(self):
        found = self.plan(tree(), FORM_FEED)
        self.assertIsInstance(found, KeyboardRowReveal)
        self.assertEqual((851, 1184), found.start)
        self.assertEqual((229, 1184), found.end)

    def test_form_feed_reveal_requires_a_new_observed_interior_label(self):
        self.ui.roots = [tree(), revealed_form_feed()]
        click_keyboard_control(self.editor, FORM_FEED)
        self.assertEqual(2, len(self.ui.calls))
        self.assertEqual(("shell", "input", "swipe", "851", "1184", "229", "1184", "250"), self.ui.calls[0][1])
        self.assertEqual((859, 1184), self.ui.calls[1][2])
        self.assertEqual(["reveal-horizontal", "tap"], [r["stage"] for r in self.editor.records])
        self.assertTrue(all(r["commandCompleted"] for r in self.editor.records))

    def test_only_one_reveal_and_one_deadline_even_if_the_next_tree_remains_clipped(self):
        clock = Clock()
        self.ui.clock = clock
        self.ui.screen_cost = 1
        self.ui.roots = [tree()]
        with patch("android_keyboard_controls.time.monotonic", clock.monotonic), \
                patch("android_keyboard_controls.time.sleep", clock.sleep):
            with self.assertRaisesRegex(AssertionError, "Timed out"):
                click_keyboard_control(self.editor, FORM_FEED, timeout=3)
        self.assertEqual(1, len(self.ui.calls))
        self.assertEqual("adb", self.ui.calls[0][0])
        self.assertEqual(3, self.ui.screen_timeouts[0])
        self.assertTrue(all(a > b for a, b in zip(self.ui.screen_timeouts, self.ui.screen_timeouts[1:])))
        self.assertTrue(all(value <= 3 for value in self.ui.screen_timeouts))

    def test_late_tree_cannot_trigger_an_input_after_the_deadline(self):
        clock = Clock()
        self.ui.clock = clock
        self.ui.screen_cost = 4
        self.ui.roots = [tree()]
        with patch("android_keyboard_controls.time.monotonic", clock.monotonic):
            with self.assertRaisesRegex(AssertionError, "Timed out"):
                click_keyboard_control(self.editor, BACKSPACE, timeout=3)
        self.assertEqual([], self.ui.calls)

    def test_deadline_is_rechecked_after_artifact_serialization(self):
        clock = Clock()
        self.ui.roots = [tree()]
        tostring = ET.tostring
        def delayed(*args, **options):
            clock.now += 4
            return tostring(*args, **options)
        with patch("android_keyboard_controls.time.monotonic", clock.monotonic), \
                patch("android_keyboard_controls.ET.tostring", delayed):
            with self.assertRaisesRegex(AssertionError, "Timed out"):
                click_keyboard_control(self.editor, BACKSPACE, timeout=3)
        self.assertEqual([], self.ui.calls)
        self.assertFalse(self.editor.records[0]["commandCompleted"])

    def test_disabled_hidden_foreign_and_editable_controls_are_rejected(self):
        for part, attribute, value in [("owner", "enabled", "false"), ("owner", "package", "other.app"),
                                       ("label", "enabled", "false"), ("label", "visible-to-user", "false"),
                                       ("label", "class", "android.widget.EditText")]:
            with self.subTest(part=part, attribute=attribute):
                root = tree()
                item = label(root)
                if part == "owner":
                    item = parents(root)[item]
                item.set(attribute, value)
                self.assertIsNone(self.plan(root))

    def test_disabled_scope_and_missing_or_truncated_scope_are_rejected(self):
        root = tree()
        scope, _ = self.editor.scope(root, BACKSPACE)
        scope.set("enabled", "false")
        self.assertIsNone(self.plan(root))
        scope.set("enabled", "true")
        scope.set("resource-id", "other")
        self.assertIsNone(self.plan(root))
        self.assertIsNone(self.plan(label(tree())))
        with self.assertRaises(ET.ParseError):
            ET.fromstring(FIXTURE.read_bytes()[:-20])

    def test_partial_vertical_label_or_point_outside_scroll_never_taps_or_reveals(self):
        for bounds in ("[518,1200][795,1250]", "[518,1247][795,1258]"):
            with self.subTest(bounds=bounds):
                root = tree()
                text = label(root)
                text.set("bounds", bounds)
                parents(root)[text].set("bounds", "[455,1122][858,1260]")
                self.assertIsNone(self.plan(root))

    def test_vertical_edge_bounds_do_not_prove_a_complete_label(self):
        for bounds in ("[518,1121][795,1211]", "[518,1158][795,1247]"):
            with self.subTest(bounds=bounds):
                root = tree()
                text = label(root)
                text.set("bounds", bounds)
                parents(root)[text].set("bounds", "[455,1121][858,1248]")
                self.assertIsNone(self.plan(root))

    def test_a_clickable_scroll_row_cannot_replace_the_missing_button(self):
        root = tree()
        text = label(root)
        owner = parents(root)[text]
        row = parents(root)[owner]
        owner.remove(text)
        row.remove(owner)
        row.append(text)
        row.set("clickable", "true")
        self.assertIsNone(self.plan(root))

    def test_wide_label_touching_both_horizontal_edges_is_rejected(self):
        root = tree()
        text = label(root)
        text.set("bounds", "[21,1158][1059,1211]")
        parents(root)[text].set("bounds", "[21,1122][1059,1248]")
        self.assertIsNone(self.plan(root))

    def test_owner_must_contain_label_and_the_point(self):
        root = tree()
        parents(root)[label(root)].set("bounds", "[700,1122][858,1248]")
        self.assertIsNone(self.plan(root))

    def test_duplicate_sibling_is_ambiguous_before_any_input(self):
        root = tree()
        owner = parents(root)[label(root)]
        parents(root)[owner].append(copy.deepcopy(owner))
        self.ui.roots = [root]
        with self.assertRaisesRegex(AssertionError, "Ambiguous"):
            click_keyboard_control(self.editor, BACKSPACE)
        self.assertEqual([], self.ui.calls)

    def test_sdk_or_parser_errors_are_not_retried(self):
        self.ui.screen = lambda **options: (_ for _ in ()).throw(AssertionError("typed capture rejected"))
        with self.assertRaisesRegex(AssertionError, "typed capture rejected"):
            click_keyboard_control(self.editor, BACKSPACE)
        self.assertEqual([], self.ui.calls)

    def test_other_controls_are_outside_this_helper(self):
        with self.assertRaises(ValueError):
            click_keyboard_control(self.editor, "io_home")
        self.assertEqual([], self.ui.calls)


if __name__ == "__main__":
    unittest.main()
