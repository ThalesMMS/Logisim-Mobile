#!/usr/bin/env python3
"""Verify toolbar routing with literal hierarchies and no Android processes."""

from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from android_ui import AndroidUi, UiNodeNotFound


PACKAGE = "dev.logisim.mobile.e2e"
PROJECT = "editor-project-toolbar"
COMMAND = "editor-command-toolbar"
ACTION = '<node package="dev.logisim.mobile.e2e" resource-id="tools" content-desc="Tools" clickable="true" enabled="true" bounds="[10,10][120,55]"/>'
CLIPPED = '<node package="dev.logisim.mobile.e2e" resource-id="tools" content-desc="Tools" clickable="true" enabled="true" bounds="[300,10][450,55]"/>'
OVERFLOW = '<node package="dev.logisim.mobile.e2e" resource-id="editor-project-toolbar-overflow" clickable="true" enabled="true" bounds="[350,5][398,55]"/>'
MENU_ACTION = '<node package="dev.logisim.mobile.e2e" resource-id="menu-tools" content-desc="Tools" clickable="true" enabled="true" bounds="[60,120][300,165]"/>'


def hierarchy(body):
    return ET.fromstring('<hierarchy><node package="dev.logisim.mobile.e2e" bounds="[0,0][400,800]">'
                         + body + '</node></hierarchy>')


def toolbar(body, identifier=PROJECT):
    return '<node package="dev.logisim.mobile.e2e" resource-id="' + identifier + \
        '" scrollable="true" bounds="[0,0][400,60]">' + body + '</node>'


def menu(body, identifier=PROJECT):
    return '<node package="dev.logisim.mobile.e2e" resource-id="' + identifier + \
        '-menu" scrollable="true" bounds="[40,100][360,500]">' + body + '</node>'


class FakeUi(AndroidUi):
    def __init__(self, output, frames=()):
        super().__init__("/forbidden/adb", "fixture-device", PACKAGE, output)
        self.frames = list(frames)
        self.calls = []
        self.screen_timeouts = []

    def screen(self, timeout=30):
        self.screen_timeouts.append(timeout)
        if not self.frames:
            raise AssertionError("No literal hierarchy remains")
        return self.frames.pop(0) if len(self.frames) > 1 else self.frames[0]

    def _run(self, args, *, timeout=30, **options):
        args = tuple(args)
        if args[:3] not in (("shell", "input", "tap"), ("shell", "input", "swipe"), ("shell", "input", "keyevent")):
            raise AssertionError(f"Unexpected driver command: {args}")
        self.calls.append((args, timeout))
        return subprocess.CompletedProcess(args, 0, "", "")


class ToolbarTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        process = patch("subprocess.run", side_effect=AssertionError("Real subprocess forbidden"))
        process.start()
        self.addCleanup(process.stop)

    @staticmethod
    def matches(node):
        return node.get("content-desc") == "Tools"

    def test_whole_visible_direct_action_returns_without_input(self):
        root = hierarchy(toolbar(ACTION + OVERFLOW))
        ui = FakeUi(self.output)
        self.assertEqual("tools", ui.toolbar_action((PROJECT,), matches=self.matches, root=root).get("resource-id"))
        self.assertEqual([], ui.calls)
        self.assertEqual([], ui.screen_timeouts)

    def test_clipped_direct_action_uses_only_its_toolbar_overflow(self):
        ui = FakeUi(self.output, [hierarchy(menu(MENU_ACTION))])
        node = ui.toolbar_action((PROJECT, COMMAND), matches=self.matches, root=hierarchy(toolbar(CLIPPED + OVERFLOW)))
        self.assertEqual("menu-tools", node.get("resource-id"))
        self.assertEqual([("shell", "input", "tap", "374", "30")], [call for call, _ in ui.calls])
        self.assertTrue(all(0 < timeout <= 30 for _, timeout in ui.calls))
        self.assertTrue(all(0 < timeout <= 30 for timeout in ui.screen_timeouts))

    def test_visible_caption_inside_a_clipped_button_does_not_prove_a_reachable_action(self):
        button = '<node package="dev.logisim.mobile.e2e" resource-id="button" clickable="true" enabled="true" bounds="[300,5][450,55]">' \
            '<node package="dev.logisim.mobile.e2e" content-desc="Tools" enabled="true" bounds="[310,10][380,45]"/></node>'
        ui = FakeUi(self.output, [hierarchy(menu(MENU_ACTION))])
        self.assertEqual("menu-tools", ui.toolbar_action((PROJECT,), matches=self.matches,
                         root=hierarchy(toolbar(button + OVERFLOW))).get("resource-id"))
        self.assertEqual([("shell", "input", "tap", "374", "30")], [call for call, _ in ui.calls])

    def test_visible_caption_returns_its_whole_clickable_button(self):
        button = '<node package="dev.logisim.mobile.e2e" resource-id="button" clickable="true" enabled="true" bounds="[5,5][150,55]">' \
            '<node package="dev.logisim.mobile.e2e" content-desc="Tools" enabled="true" bounds="[10,10][120,45]"/></node>'
        ui = FakeUi(self.output)
        self.assertEqual("button", ui.toolbar_action((PROJECT,), matches=self.matches,
                         root=hierarchy(toolbar(button))).get("resource-id"))
        self.assertEqual([], ui.calls)

    def test_enabled_caption_cannot_hide_a_disabled_button(self):
        button = '<node package="dev.logisim.mobile.e2e" resource-id="button" clickable="true" enabled="false" bounds="[5,5][150,55]">' \
            '<node package="dev.logisim.mobile.e2e" content-desc="Tools" enabled="true" bounds="[10,10][120,45]"/></node>'
        ui = FakeUi(self.output)
        with self.assertRaisesRegex(AssertionError, "disabled"):
            ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(toolbar(button + OVERFLOW)))
        self.assertEqual([], ui.calls)

    def test_an_open_allowed_menu_owns_the_search_despite_background_action(self):
        root = hierarchy(toolbar(ACTION) + menu(MENU_ACTION, COMMAND))
        ui = FakeUi(self.output)
        node = ui.toolbar_action((PROJECT, COMMAND), matches=self.matches, root=root)
        self.assertEqual("menu-tools", node.get("resource-id"))
        self.assertEqual([], ui.calls)

    def test_an_open_unrelated_menu_blocks_background_action(self):
        root = hierarchy(toolbar(ACTION) + menu(MENU_ACTION, "editor-simulation-toolbar"))
        ui = FakeUi(self.output)
        with self.assertRaisesRegex(AssertionError, "Another toolbar menu"):
            ui.toolbar_action((PROJECT,), matches=self.matches, root=root)
        self.assertEqual([], ui.calls)

    def test_duplicate_menu_tag_or_multiple_menus_fail_without_input(self):
        for body in (menu(MENU_ACTION) + menu(MENU_ACTION),
                     menu(MENU_ACTION) + menu(MENU_ACTION, COMMAND)):
            with self.subTest(body=body):
                ui = FakeUi(self.output)
                with self.assertRaisesRegex(AssertionError, "Ambiguous"):
                    ui.toolbar_action((PROJECT, COMMAND), matches=self.matches, root=hierarchy(body))
                self.assertEqual([], ui.calls)

    def test_duplicate_action_fails_in_direct_route_or_menu(self):
        for body in (toolbar(ACTION + ACTION + OVERFLOW), menu(MENU_ACTION + MENU_ACTION),
                     toolbar(ACTION) + toolbar(ACTION, COMMAND)):
            with self.subTest(body=body):
                ui = FakeUi(self.output)
                with self.assertRaisesRegex(AssertionError, "Ambiguous toolbar action"):
                    ui.toolbar_action((PROJECT, COMMAND), matches=self.matches, root=hierarchy(body))
                self.assertEqual([], ui.calls)

    def test_overflow_with_a_correct_tag_outside_its_toolbar_is_rejected(self):
        ui = FakeUi(self.output)
        with self.assertRaisesRegex(AssertionError, "outside its toolbar"):
            ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(toolbar(CLIPPED) + OVERFLOW))
        self.assertEqual([], ui.calls)

    def test_overflow_opening_a_different_allowed_menu_is_rejected(self):
        ui = FakeUi(self.output, [hierarchy(menu(MENU_ACTION, COMMAND))])
        with self.assertRaisesRegex(AssertionError, "inconsistent menu"):
            ui.toolbar_action((PROJECT, COMMAND), matches=self.matches, root=hierarchy(toolbar(OVERFLOW)))
        self.assertEqual([("shell", "input", "tap", "374", "30")], [call for call, _ in ui.calls])

    def test_menu_scrolls_only_its_viewport_to_reveal_the_exact_action(self):
        clipped_action = MENU_ACTION.replace("[60,120][300,165]", "[60,510][300,555]")
        root = hierarchy(toolbar(ACTION) + menu(clipped_action))
        ui = FakeUi(self.output, [hierarchy(menu(MENU_ACTION))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=root)
        self.assertEqual("[60,120][300,165]", node.get("bounds"))
        self.assertEqual([("shell", "input", "swipe", "200", "420", "200", "180", "250")],
                         [call for call, _ in ui.calls])

    def test_menu_scroll_searches_both_directions(self):
        empty = hierarchy(menu('<node package="dev.logisim.mobile.e2e" text="Other" bounds="[60,120][300,165]"/>'))
        ui = FakeUi(self.output, [empty, hierarchy(menu(MENU_ACTION))])
        self.assertEqual("menu-tools", ui.toolbar_action((PROJECT,), matches=self.matches, root=empty).get("resource-id"))
        self.assertEqual([("shell", "input", "swipe", "200", "420", "200", "180", "250"),
                          ("shell", "input", "swipe", "200", "180", "200", "420", "250")],
                         [call for call, _ in ui.calls])

    def test_missing_action_never_falls_through_to_background_or_another_route(self):
        root = hierarchy(toolbar(ACTION) + menu('<node package="dev.logisim.mobile.e2e" text="Other" bounds="[60,120][300,165]"/>'))
        ui = FakeUi(self.output, [root])
        with self.assertRaises(UiNodeNotFound):
            ui.toolbar_action((PROJECT, COMMAND), matches=self.matches, root=root)
        self.assertEqual(2, len(ui.calls))
        self.assertTrue(all(call[:3] == ("shell", "input", "swipe") for call, _ in ui.calls))
        for call, _ in ui.calls:
            self.assertEqual("200", call[3])
            self.assertEqual("200", call[5])
            self.assertTrue(100 <= int(call[4]) <= 500 and 100 <= int(call[6]) <= 500)

    def test_disabled_action_and_overflow_fail_without_input(self):
        for body in (toolbar(ACTION.replace('enabled="true"', 'enabled="false"') + OVERFLOW),
                     menu(MENU_ACTION.replace('enabled="true"', 'enabled="false"')),
                     toolbar(OVERFLOW.replace('enabled="true"', 'enabled="false"'))):
            with self.subTest(body=body):
                ui = FakeUi(self.output)
                with self.assertRaisesRegex(AssertionError, "disabled"):
                    ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(body))
                self.assertEqual([], ui.calls)

    def test_absent_overflow_permits_only_the_legacy_fallback(self):
        for body in (ACTION, toolbar(CLIPPED), toolbar('<node package="dev.logisim.mobile.e2e" text="Other" bounds="[10,10][120,55]"/>')):
            with self.subTest(body=body):
                ui = FakeUi(self.output)
                self.assertIsNone(ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(body)))
                self.assertEqual([], ui.calls)

    def test_tagged_and_action_predicates_reject_other_packages(self):
        foreign = ACTION.replace(PACKAGE, "other.application")
        root = hierarchy(toolbar(foreign + ACTION) + toolbar("", "foreign"))
        ui = FakeUi(self.output)
        self.assertEqual("tools", ui.toolbar_action((PROJECT,), matches=self.matches, root=root).get("resource-id"))
        root = hierarchy('<node package="other.application" resource-id="editor-project-toolbar" bounds="[0,0][400,60]"/>')
        self.assertIsNone(ui.tagged(root, PROJECT))
        root = hierarchy(toolbar("") + toolbar(""))
        with self.assertRaisesRegex(AssertionError, "Ambiguous app tag"):
            ui.tagged(root, PROJECT)

    def test_visibility_requires_the_whole_control_within_every_scroll_ancestor(self):
        ui = FakeUi(self.output)
        root = hierarchy(toolbar(CLIPPED))
        self.assertFalse(ui.visible_node(root, ui.find(root, resource_id="tools")))
        root = hierarchy('<node scrollable="true" bounds="[0,0][90,60]">' + toolbar(ACTION) + '</node>')
        self.assertFalse(ui.visible_node(root, ui.find(root, resource_id="tools")))
        root = hierarchy(toolbar(ACTION) + '<node package="dev.logisim.mobile.e2e" resource-id="unrelated" bounds="[0,0][400,60]"/>')
        action = ui.find(root, resource_id="tools")
        self.assertTrue(ui.visible_node(root, action, within=ui.tagged(root, PROJECT)))
        self.assertFalse(ui.visible_node(root, action, within=ui.tagged(root, "unrelated")))
        action.set("bounds", "[-1,10][120,55]")
        self.assertFalse(ui.visible_node(root, action))
        action.set("bounds", "[10,10][120,805]")
        self.assertFalse(ui.visible_node(root, action))

    def test_a_missing_menu_respects_the_total_deadline(self):
        ui = FakeUi(self.output, [hierarchy(toolbar(OVERFLOW))])
        clock = [0.0]

        def now():
            clock[0] += .25
            return clock[0]

        with patch("android_ui.time.monotonic", side_effect=now), patch("android_ui.time.sleep"):
            with self.assertRaisesRegex(AssertionError, "deadline"):
                ui.toolbar_action((PROJECT,), matches=self.matches, timeout=2, root=hierarchy(toolbar(OVERFLOW)))
        self.assertEqual(1, len(ui.calls))
        self.assertTrue(all(0 < timeout <= 2 for timeout in ui.screen_timeouts))
        with self.assertRaises(ValueError):
            ui.toolbar_action((PROJECT,), matches=self.matches, timeout=31)

    def test_icon_only_button_matches_a_text_predicate_through_its_description(self):
        icon = '<node package="dev.logisim.mobile.e2e" resource-id="tool-run" content-desc="Run" clickable="true" enabled="true" bounds="[10,10][50,50]"/>'
        ui = FakeUi(self.output)
        node = ui.toolbar_action((COMMAND,), matches=lambda candidate: candidate.get("text") == "Run",
                                 root=hierarchy(toolbar(icon, COMMAND)))
        self.assertEqual("tool-run", node.get("resource-id"))
        self.assertEqual([], ui.calls)

    def test_simulation_controls_nested_in_the_tool_toolbar_are_one_action(self):
        run = '<node package="dev.logisim.mobile.e2e" resource-id="tool-run" content-desc="Tools" clickable="true" enabled="true" bounds="[300,10][340,50]"/>'
        nested = toolbar('<node package="dev.logisim.mobile.e2e" resource-id="editor-simulation-toolbar" bounds="[290,5][398,55]">'
                         + run + '</node>', COMMAND)
        ui = FakeUi(self.output)
        node = ui.toolbar_action(("editor-simulation-toolbar", COMMAND), matches=self.matches, root=hierarchy(nested))
        self.assertEqual("tool-run", node.get("resource-id"))

    def test_overflow_that_expands_in_place_returns_the_revealed_action(self):
        expand = OVERFLOW.replace(PROJECT, COMMAND)
        collapsed = hierarchy(toolbar(expand, COMMAND))
        expanded = hierarchy(toolbar(ACTION + expand.replace("[350,5][398,55]", "[350,65][398,115]"), COMMAND))
        ui = FakeUi(self.output, [expanded])
        node = ui.toolbar_action((COMMAND,), matches=self.matches, root=collapsed)
        self.assertEqual("tools", node.get("resource-id"))
        self.assertEqual([("shell", "input", "tap", "374", "30")], [call for call, _ in ui.calls])

    def test_menu_bar_titles_are_searched_and_unmatched_menus_dismissed(self):
        header = toolbar('<node package="dev.logisim.mobile.e2e" resource-id="menu-title-file" content-desc="File" clickable="true" enabled="true" bounds="[10,10][60,50]"/>'
                         '<node package="dev.logisim.mobile.e2e" resource-id="menu-title-edit" content-desc="Edit" clickable="true" enabled="true" bounds="[70,10][120,50]"/>')
        popup = lambda name, body: '<node package="dev.logisim.mobile.e2e" resource-id="editor-menu-' + name + \
            '" scrollable="true" bounds="[40,100][360,500]">' + body + '</node>'
        other = '<node package="dev.logisim.mobile.e2e" resource-id="menu-new" content-desc="New" clickable="true" enabled="true" bounds="[60,120][300,165]"/>'
        ui = FakeUi(self.output, [hierarchy(header + popup("file", other)), hierarchy(header),
                                  hierarchy(header + popup("edit", MENU_ACTION))])
        node = ui.toolbar_action((COMMAND,), matches=self.matches, root=hierarchy(header))
        self.assertEqual("menu-tools", node.get("resource-id"))
        # Menus that show every item are not scrolled.
        self.assertEqual([("shell", "input", "tap", "35", "30"), ("shell", "input", "keyevent", "4"),
                          ("shell", "input", "tap", "95", "30")], [call for call, _ in ui.calls])

    def test_compact_header_menu_drills_into_its_submenus(self):
        top = menu('<node package="dev.logisim.mobile.e2e" resource-id="menu-title-edit" content-desc="Edit" clickable="true" enabled="true" bounds="[60,120][300,165]"/>')
        ui = FakeUi(self.output, [hierarchy(toolbar(OVERFLOW) + top), hierarchy(toolbar(OVERFLOW) + menu(MENU_ACTION))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(toolbar(OVERFLOW)))
        self.assertEqual("menu-tools", node.get("resource-id"))
        self.assertEqual([("shell", "input", "tap", "374", "30"), ("shell", "input", "tap", "180", "142")],
                         [call for call, _ in ui.calls])

    def test_nested_simulation_row_shorter_than_its_button_uses_the_outer_toolbar(self):
        button = '<node package="dev.logisim.mobile.e2e" resource-id="tools" content-desc="Tools" clickable="true" enabled="true" bounds="[300,2][380,58]"/>'
        nested = '<node package="dev.logisim.mobile.e2e" resource-id="editor-simulation-toolbar" bounds="[280,8][400,52]">' + button + '</node>'
        ui = FakeUi(self.output)
        node = ui.toolbar_action(("editor-simulation-toolbar", COMMAND), matches=self.matches,
                                 root=hierarchy(toolbar(nested, COMMAND)))
        self.assertEqual("tools", node.get("resource-id"))
        self.assertEqual([], ui.calls)

    def test_a_scrolling_menu_clipped_at_its_lower_edge_is_scrolled(self):
        clipped = '<node package="dev.logisim.mobile.e2e" resource-id="menu-new" content-desc="New" clickable="true" enabled="true" bounds="[60,440][300,496]"/>'
        ui = FakeUi(self.output, [hierarchy(toolbar(OVERFLOW) + menu(clipped)),
                                  hierarchy(toolbar(OVERFLOW) + menu(MENU_ACTION))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(toolbar(OVERFLOW)))
        self.assertEqual("menu-tools", node.get("resource-id"))
        self.assertEqual("swipe", ui.calls[1][0][2])

    def test_compact_menu_returns_to_its_titles_between_submenus(self):
        titles = '<node package="dev.logisim.mobile.e2e" resource-id="menu-title-file" content-desc="File" clickable="true" enabled="true" bounds="[60,120][300,165]"/>' \
            '<node package="dev.logisim.mobile.e2e" resource-id="menu-title-edit" content-desc="Edit" clickable="true" enabled="true" bounds="[60,170][300,215]"/>'
        back = '<node package="dev.logisim.mobile.e2e" resource-id="menu-back" content-desc="Back" clickable="true" enabled="true" bounds="[60,120][300,165]"/>'
        other = '<node package="dev.logisim.mobile.e2e" resource-id="menu-new" content-desc="New" clickable="true" enabled="true" bounds="[60,170][300,215]"/>'
        top = hierarchy(toolbar(OVERFLOW) + menu(titles))
        ui = FakeUi(self.output, [top, hierarchy(toolbar(OVERFLOW) + menu(back + other)), top,
                                  hierarchy(toolbar(OVERFLOW) + menu(back + MENU_ACTION.replace("[60,120][300,165]", "[60,170][300,215]")))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(toolbar(OVERFLOW)))
        self.assertEqual("menu-tools", node.get("resource-id"))
        self.assertEqual([("shell", "input", "tap", "374", "30"), ("shell", "input", "tap", "180", "142"),
                          ("shell", "input", "tap", "180", "142"), ("shell", "input", "tap", "180", "192")],
                         [call for call, _ in ui.calls])

    def test_an_item_cut_at_a_scrolling_menu_edge_is_scrolled_inside_before_returning(self):
        # UIAutomator reports a partly scrolled item as the visible sliver only (#239, layout12 small phone).
        sliver = MENU_ACTION.replace("[60,120][300,165]", "[60,486][300,500]")
        whole = MENU_ACTION.replace("[60,120][300,165]", "[60,400][300,445]")
        ui = FakeUi(self.output, [hierarchy(menu(whole))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(menu(sliver)))
        self.assertEqual("[60,400][300,445]", node.get("bounds"))
        self.assertEqual([("shell", "input", "swipe", "200", "420", "200", "180", "250")],
                         [call for call, _ in ui.calls])

    def test_an_edge_item_that_no_longer_scrolls_is_whole(self):
        last = MENU_ACTION.replace("[60,120][300,165]", "[60,455][300,500]")
        ui = FakeUi(self.output, [hierarchy(menu(last))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(menu(last)))
        self.assertEqual("[60,455][300,500]", node.get("bounds"))
        self.assertEqual(1, len(ui.calls))
    def test_clipboard_overlay_regions_are_read_only_while_visible(self):
        windows = ("  Window #1 Window{f78c82b u0 ScreenDecorOverlay}:\n    isVisible=true\n"
                   "    touchable region=SkRegion((0,0,320,10))\n"
                   "  Window #2 Window{1e00289 u0 ClipboardOverlay}:\n    mTouchableInsets=3\n"
                   "    touchable region=SkRegion((8,422,120,454)(-12,454,260,542))\n    isOnScreen=true  isVisible=true\n"
                   "  Window #3 Window{cf59091 u0 EdgeBackGestureHandler0}:\n")
        self.assertEqual([(8, 422, 120, 454), (-12, 454, 260, 542)],
                         AndroidUi.overlay_touch_regions(windows, "ClipboardOverlay"))
        self.assertEqual([], AndroidUi.overlay_touch_regions(windows.replace("isVisible=true", "isVisible=false"),
                                                             "ClipboardOverlay"))
    def test_compact_menu_searches_the_named_submenu_first(self):
        titles = '<node package="dev.logisim.mobile.e2e" resource-id="menu-title-file" content-desc="File" clickable="true" enabled="true" bounds="[60,120][300,165]"/>' \
            '<node package="dev.logisim.mobile.e2e" resource-id="menu-title-view" content-desc="View" clickable="true" enabled="true" bounds="[60,170][300,215]"/>'
        ui = FakeUi(self.output, [hierarchy(toolbar(OVERFLOW) + menu(titles)), hierarchy(toolbar(OVERFLOW) + menu(MENU_ACTION))])
        node = ui.toolbar_action((PROJECT,), matches=self.matches, root=hierarchy(toolbar(OVERFLOW)), menu="menu-title-view")
        self.assertEqual("menu-tools", node.get("resource-id"))
        self.assertEqual([("shell", "input", "tap", "374", "30"), ("shell", "input", "tap", "180", "192")],
                         [call for call, _ in ui.calls])


if __name__ == "__main__":
    unittest.main()
