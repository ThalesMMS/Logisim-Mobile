#!/usr/bin/env python3
"""Exercise observed DocumentsUI routing without any Android processes or UI access."""
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
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("downloads_navigation_runner", SCRIPTS / "e2e-android.py")
RUNNER = importlib.util.module_from_spec(spec)
spec.loader.exec_module(RUNNER)
from android_ui import AndroidUi

NATIVE = "com.google.android.documentsui"
OPEN_ROOTS = ET.parse(SCRIPTS.parent / "fixtures/native-documentsui/roots-open-google-api35.xml").getroot()
CLOSED = ET.fromstring(f'<hierarchy><node package="{NATIVE}" content-desc="Show roots"/></hierarchy>')
CONTENT = ET.fromstring(f'<hierarchy><node package="{NATIVE}" resource-id="{NATIVE}:id/dir_list"/></hierarchy>')
EMPTY_STAGED = ET.parse(SCRIPTS.parent / "fixtures/native-documentsui/empty-staged-google-api35.xml").getroot()
TREE_ROOTS = ET.parse(SCRIPTS.parent / "fixtures/native-documentsui/tree-roots-google-api35.xml").getroot()
STORAGE_CONTENT = ET.fromstring(f'<hierarchy><node package="{NATIVE}" resource-id="{NATIVE}:id/dir_list">'
                                 '<node text="Download"/></node></hierarchy>')
DOWNLOAD_CONTENT = ET.fromstring(f'<hierarchy><node package="{NATIVE}" resource-id="{NATIVE}:id/dir_list"/>'
                                  f'<node package="{NATIVE}" resource-id="{NATIVE}:id/breadcrumb_text" text="Download"/></hierarchy>')
DOWNLOADS_ALREADY = ET.parse(SCRIPTS.parent / "fixtures/native-documentsui/downloads-content-google-api35.xml").getroot()
FILE = ET.fromstring('<hierarchy><node text="portable53.lsebdl"/></hierarchy>')


class FakeUi:
    find = staticmethod(AndroidUi.find)
    optional = staticmethod(AndroidUi.optional)
    _bounds = staticmethod(AndroidUi._bounds)
    model = 'sdk_gphone64_arm64'

    def __init__(self, roots=OPEN_ROOTS, initial=None, content=CONTENT):
        self.roots = roots
        self.current = initial if initial is not None else roots
        self.content = content
        self.taps = []
        self.swipes = []

    def screen(self, *, timeout=45):
        return self.current

    def tap(self, node, *, timeout=30):
        label = node.get("content-desc") or node.get("text")
        self.taps.append(label)
        if label == "Show roots":
            self.current = self.roots
        elif label == "Downloads":
            self.current = self.content
        elif label == "portable53.lsebdl":
            pass
        elif label == 'sdk_gphone64_arm64':
            self.current = STORAGE_CONTENT
        elif label in ('Download', 'Download crumb row'):
            self.current = DOWNLOAD_CONTENT
        else:
            raise AssertionError("Unexpected native action")

    def adb(self, *arguments, timeout=30):
        if arguments == ('shell', 'getprop', 'ro.product.model'):
            return self.model + '\n'
        assert arguments[:3] == ('shell', 'input', 'swipe')
        self.swipes.append(arguments)
        self.current = FILE


class DownloadsNavigationTests(unittest.TestCase):
    def setUp(self):
        guards = ExitStack()
        self.addCleanup(guards.close)
        for name in ("Popen", "run", "call", "check_call", "check_output"):
            guards.enter_context(patch.object(subprocess, name, side_effect=AssertionError("No Android processes allowed")))
        self.output = Path(guards.enter_context(tempfile.TemporaryDirectory()))

    def choose(self, ui, **options):
        scenarios = object.__new__(RUNNER.Scenarios)
        scenarios.ui = ui
        ui.output = self.output
        scenarios.choose_downloads(**options)

    def test_observed_initially_open_roots_selects_downloads_and_waits_for_file_list(self):
        ui = FakeUi()
        self.choose(ui)
        self.assertEqual(["Downloads"], ui.taps)
        self.assertIs(CONTENT, ui.current)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        tap = next(item for item in trace['events'] if item['kind'] == 'tap')
        observed = ET.parse(self.output / tap['sourceHierarchy']).getroot()
        downloads = ui.find(observed, text='Downloads')
        self.assertEqual('[168,617][714,668]', tap['bounds'])
        self.assertEqual(downloads.get('bounds'), tap['bounds'])
        self.assertEqual(NATIVE, tap['package'])
        self.assertEqual(downloads.get('enabled'), tap['enabled'])
        context = tap['windowContext']
        self.assertEqual('UIAUTOMATOR_XML_ONLY', context['source'])
        self.assertEqual(dict(observed.attrib), context['hierarchyAttributes'])
        self.assertEqual(NATIVE, context['topLevelNodes'][0]['package'])
        self.assertIsNone(context['wmToken'])
        self.assertIsNone(context['pid'])
        self.assertFalse(context['wmTokenObservedAtTap'])
        self.assertFalse(context['pidObservedAtTap'])
        for query in (item for item in trace['events'] if item['kind'] == 'screen'):
            self.assertTrue((self.output / query['hierarchy']).is_file())

    def test_closed_roots_opens_then_selects_downloads(self):
        ui = FakeUi(initial=CLOSED)
        self.choose(ui)
        self.assertEqual(["Show roots", "Downloads"], ui.taps)
        self.assertIs(CONTENT, ui.current)

    def test_observed_downloads_content_needs_no_navigation_tap(self):
        ui = FakeUi(initial=DOWNLOADS_ALREADY)
        self.choose(ui)
        self.assertEqual([], ui.taps)
        self.assertIs(DOWNLOADS_ALREADY, ui.current)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        self.assertEqual('PASSED', trace['status'])
        observed = next(item for item in trace['events'] if item['kind'] == 'already-at-downloads')
        self.assertEqual('Downloads', observed['breadcrumb']['text'])
        self.assertEqual(NATIVE, observed['package'])
        self.assertTrue((self.output / observed['sourceHierarchy']).is_file())
        self.assertEqual(1, sum(item['kind'] == 'screen' for item in trace['events']))

    def test_device_storage_download_content_also_needs_no_navigation_tap(self):
        ui = FakeUi(initial=DOWNLOAD_CONTENT)
        self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_aosp_downloads_content_uses_the_same_native_location_contract(self):
        root = ET.fromstring(ET.tostring(DOWNLOADS_ALREADY))
        for node in root.iter('node'):
            if node.get('package') == NATIVE:
                node.set('package', 'com.android.documentsui')
                node.set('resource-id', node.get('resource-id', '').replace(NATIVE + ':', 'com.android.documentsui:'))
        ui = FakeUi(initial=root)
        self.choose(ui)
        self.assertEqual([], ui.taps)

    def reject_unobserved_downloads_content(self, root):
        ui = FakeUi(initial=root)
        self.clocked(ui, [])
        with self.assertRaisesRegex(AssertionError, 'presentation transition'):
            self.choose(ui, timeout=1)
        self.assertEqual([], ui.taps)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        self.assertEqual('FAILED', trace['status'])
        self.assertFalse(any(item['kind'] == 'already-at-downloads' for item in trace['events']))

    def test_downloads_heading_without_file_list_does_not_establish_location(self):
        root = ET.fromstring(f'<hierarchy><node package="{NATIVE}" resource-id="{NATIVE}:id/breadcrumb_text" text="Downloads"/></hierarchy>')
        self.reject_unobserved_downloads_content(root)

    def test_downloads_title_is_not_a_breadcrumb(self):
        root = ET.fromstring(ET.tostring(CONTENT))
        root.append(ET.fromstring(f'<node package="{NATIVE}" resource-id="android:id/title" text="Downloads"/>'))
        self.reject_unobserved_downloads_content(root)

    def test_unrelated_package_breadcrumb_does_not_establish_native_location(self):
        root = ET.fromstring(ET.tostring(CONTENT))
        root.append(ET.fromstring('<node package="thalesmms.logisim.mobile.e2e" resource-id="thalesmms.logisim.mobile.e2e:id/breadcrumb_text" text="Downloads"/>'))
        self.reject_unobserved_downloads_content(root)

    def test_breadcrumb_and_file_list_from_different_native_packages_are_not_combined(self):
        root = ET.fromstring(ET.tostring(DOWNLOAD_CONTENT))
        next(node for node in root.iter('node') if node.get('text') == 'Download').set('package', 'com.android.documentsui')
        self.reject_unobserved_downloads_content(root)

    def test_a_downloads_subfolder_is_left_through_its_breadcrumb_before_the_location_is_accepted(self):
        # DocumentsUI reopens the last folder; choosing the Downloads root from inside it is ignored (#239).
        # As observed on API 35: the first crumb's text view is disabled inside an enabled row.
        root = ET.fromstring(f'<hierarchy><node package="{NATIVE}" resource-id="{NATIVE}:id/dir_list"/>'
                             f'<node package="{NATIVE}" resource-id="{NATIVE}:id/horizontal_breadcrumb">'
                             f'<node package="{NATIVE}" enabled="true" content-desc="Download crumb row">'
                             f'<node package="{NATIVE}" resource-id="{NATIVE}:id/breadcrumb_text" enabled="false" text="Download"/></node>'
                             f'<node package="{NATIVE}" enabled="true"><node package="{NATIVE}" resource-id="{NATIVE}:id/breadcrumb_text" text="Subfolder"/></node>'
                             f'</node></hierarchy>')
        ui = FakeUi(initial=root)
        self.choose(ui)
        self.assertEqual(['Download crumb row'], ui.taps)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        kinds = [item['kind'] for item in trace['events']]
        self.assertLess(kinds.index('tap'), kinds.index('already-at-downloads'))

    def test_multiple_file_lists_do_not_establish_a_unique_downloads_location(self):
        root = ET.fromstring(ET.tostring(DOWNLOAD_CONTENT))
        root.append(ET.fromstring(f'<node package="{NATIVE}" resource-id="{NATIVE}:id/dir_list"/>'))
        self.reject_unobserved_downloads_content(root)

    def test_drawer_downloads_row_does_not_establish_an_already_open_file_list(self):
        root = ET.fromstring(ET.tostring(OPEN_ROOTS))
        root.extend(ET.fromstring(ET.tostring(DOWNLOAD_CONTENT)))
        ui = FakeUi(roots=root)
        self.choose(ui)
        self.assertEqual(['Downloads'], ui.taps)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        self.assertFalse(any(item['kind'] == 'already-at-downloads' for item in trace['events']))

    def test_ambiguous_drawers_cannot_be_hidden_by_downloads_content(self):
        root = ET.fromstring(ET.tostring(OPEN_ROOTS))
        drawer = next(node for node in root.iter('node') if node.get('resource-id', '').endswith(':id/roots_list'))
        root.append(ET.fromstring(ET.tostring(drawer)))
        root.extend(ET.fromstring(ET.tostring(DOWNLOAD_CONTENT)))
        ui = FakeUi(roots=root)
        with self.assertRaisesRegex(AssertionError, 'one roots list'):
            self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_already_open_downloads_still_obeys_the_presentation_deadline(self):
        ui = FakeUi(initial=DOWNLOADS_ALREADY)
        self.clocked(ui, [1.01])
        with self.assertRaisesRegex(AssertionError, 'presentation transition'):
            self.choose(ui, timeout=1)
        self.assertEqual([], ui.taps)

    def test_delayed_native_downloads_content_is_admitted_before_opening_roots(self):
        class DelayedUi(FakeUi):
            frames = [ET.fromstring('<hierarchy><node package="thalesmms.logisim.mobile.e2e"/></hierarchy>'), DOWNLOADS_ALREADY]

            def screen(self, *, timeout=45):
                return self.frames.pop(0) if self.frames else super().screen(timeout=timeout)

        ui = DelayedUi(initial=DOWNLOADS_ALREADY)
        self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_native_picker_controls_may_appear_after_the_initial_screen(self):
        class DelayedUi(FakeUi):
            frames = [ET.fromstring('<hierarchy><node package="thalesmms.logisim.mobile.e2e"/></hierarchy>'), CLOSED]

            def screen(self, *, timeout=45):
                return self.frames.pop(0) if self.frames else super().screen(timeout=timeout)

        ui = DelayedUi(initial=CLOSED)
        self.choose(ui)
        self.assertEqual(['Show roots', 'Downloads'], ui.taps)

    def test_missing_native_picker_does_not_tap_an_unrelated_screen(self):
        empty = ET.fromstring('<hierarchy><node package="thalesmms.logisim.mobile.e2e"/></hierarchy>')
        ui = FakeUi(initial=empty)
        ticks = iter(range(100))
        with patch.object(RUNNER.time, 'monotonic', side_effect=lambda: next(ticks)), \
                patch.object(RUNNER.time, 'sleep'), self.assertRaisesRegex(AssertionError, 'presentation transition'):
            self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_ambiguous_roots_fail_without_a_tap(self):
        root = ET.fromstring(ET.tostring(OPEN_ROOTS))
        original = next(node for node in root.iter('node') if node.get('resource-id', '').endswith(':id/roots_list'))
        root.append(ET.fromstring(ET.tostring(original)))
        ui = FakeUi(roots=root)
        with self.assertRaisesRegex(AssertionError, "one roots list"):
            self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_missing_downloads_and_device_storage_fail_without_selecting_an_unrelated_location(self):
        root = ET.fromstring(ET.tostring(OPEN_ROOTS))
        for node in root.iter('node'):
            if node.get('text') == 'Downloads':
                node.set('text', 'Other location')
            if node.get('text') == 'sdk_gphone64_arm64':
                node.set('text', 'Other device')
        ui = FakeUi(roots=root)
        with self.assertRaises(AssertionError):
            self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_observed_tree_roots_reach_download_through_the_device_storage(self):
        ui = FakeUi(roots=TREE_ROOTS)
        self.choose(ui)
        self.assertEqual(['sdk_gphone64_arm64', 'Download'], ui.taps)
        self.assertIs(DOWNLOAD_CONTENT, ui.current)

    def test_device_storage_label_must_match_the_actual_model_not_the_test_provider(self):
        ui = FakeUi(roots=TREE_ROOTS)
        ui.model = 'A different device'
        with self.assertRaises(AssertionError):
            self.choose(ui)
        self.assertEqual([], ui.taps)

    def test_selection_does_not_succeed_until_roots_close_and_file_list_loads(self):
        ui = FakeUi(content=OPEN_ROOTS)
        ticks = iter(range(100))
        with patch.object(RUNNER.time, 'monotonic', side_effect=lambda: next(ticks)), \
                patch.object(RUNNER.time, 'sleep'), self.assertRaisesRegex(AssertionError, 'location-content transition'):
            self.choose(ui)
        # A drawer that stays open gets the bounded lost-tap retries, within the action limit, and still fails.
        self.assertEqual(['Downloads'] * 3, ui.taps)

    def test_a_tap_lost_while_the_roots_drawer_stays_open_is_repeated_once(self):
        ui = FakeUi()
        delivered = ui.tap
        taps = []

        def lose_first(node, *, timeout=30):
            taps.append(node.get("text"))
            if len(taps) > 1:
                delivered(node, timeout=timeout)

        ui.tap = lose_first
        self.clocked(ui, [0, 9, 0, 0])
        self.choose(ui)
        self.assertEqual(['Downloads', 'Downloads'], taps)
        self.assertIs(CONTENT, ui.current)

    def clocked(self, ui, delays):
        clock = [0.0]
        waits = iter(delays)
        original = ui.screen

        def screen(*, timeout=45):
            clock[0] += next(waits, 0)
            return original(timeout=timeout)

        ui.screen = screen
        self.enterContext(patch.object(RUNNER.time, 'monotonic', side_effect=lambda: clock[0]))
        self.enterContext(patch.object(RUNNER.time, 'sleep', side_effect=lambda seconds: clock.__setitem__(0, clock[0] + seconds)))
        return clock

    def test_each_real_action_gets_a_transition_budget_with_one_total_cap(self):
        ui = FakeUi(roots=TREE_ROOTS, initial=CLOSED)
        clock = self.clocked(ui, [.8, .8, .8, .3])
        self.choose(ui, timeout=1)
        self.assertAlmostEqual(2.7, clock[0])
        self.assertEqual(['Show roots', 'sdk_gphone64_arm64', 'Download'], ui.taps)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        self.assertEqual('PASSED', trace['status'])
        self.assertEqual(3, trace['totalLimitSeconds'])
        self.assertEqual(4, sum(item['kind'] == 'screen' for item in trace['events']))

    def test_ready_hierarchy_returned_after_presentation_deadline_is_rejected(self):
        ui = FakeUi()
        self.clocked(ui, [1.01])
        with self.assertRaisesRegex(AssertionError, 'presentation transition'):
            self.choose(ui, timeout=1)
        self.assertEqual([], ui.taps)

    def test_open_roots_transition_does_not_reset_on_poll(self):
        class StuckRoots(FakeUi):
            def tap(self, node, *, timeout=30):
                self.taps.append(node.get('content-desc'))

        ui = StuckRoots(initial=CLOSED)
        self.clocked(ui, [0])
        with self.assertRaisesRegex(AssertionError, 'roots-open transition'):
            self.choose(ui, timeout=1)
        self.assertEqual(['Show roots'], ui.taps)

    def test_location_hierarchy_returned_after_its_deadline_is_rejected(self):
        ui = FakeUi()
        self.clocked(ui, [0, 1.01])
        with self.assertRaisesRegex(AssertionError, 'location-content transition'):
            self.choose(ui, timeout=1)
        self.assertEqual(['Downloads'], ui.taps)

    def test_download_hierarchy_returned_after_its_deadline_is_rejected(self):
        ui = FakeUi(roots=TREE_ROOTS)
        self.clocked(ui, [0, 0, 1.01])
        with self.assertRaisesRegex(AssertionError, 'download-content transition'):
            self.choose(ui, timeout=1)
        self.assertEqual(['sdk_gphone64_arm64', 'Download'], ui.taps)

    def test_total_deadline_rejects_ready_content_even_when_each_transition_is_within_limit(self):
        ui = FakeUi(roots=TREE_ROOTS, initial=CLOSED)
        self.clocked(ui, [.9, .9, .9, .4])
        with self.assertRaisesRegex(AssertionError, 'total deadline'):
            self.choose(ui, timeout=1)
        self.assertEqual(['Show roots', 'sdk_gphone64_arm64', 'Download'], ui.taps)

    def test_late_tap_stops_before_observing_the_next_state(self):
        ui = FakeUi()
        clock = self.clocked(ui, [])
        original = ui.tap

        def tap(node, *, timeout=30):
            original(node, timeout=timeout)
            clock[0] += 1.01

        ui.tap = tap
        with self.assertRaisesRegex(AssertionError, 'location-content transition'):
            self.choose(ui, timeout=1)
        trace = json.loads(next(self.output.glob('downloads-navigation-*.json')).read_text())
        self.assertEqual(1, sum(item['kind'] == 'screen' for item in trace['events']))

    def test_late_device_model_query_stops_before_selecting_storage(self):
        ui = FakeUi(roots=TREE_ROOTS)
        clock = self.clocked(ui, [])
        original = ui.adb

        def adb(*args, timeout=30):
            result = original(*args, timeout=timeout)
            clock[0] += 1.01
            return result

        ui.adb = adb
        with self.assertRaisesRegex(AssertionError, 'presentation transition'):
            self.choose(ui, timeout=1)
        self.assertEqual([], ui.taps)

    def test_tap_forwards_explicit_deadline_and_retains_the_legacy_default(self):
        ui = object.__new__(AndroidUi)
        ui.package = 'thalesmms.logisim.mobile.e2e'
        node = ET.fromstring('<node bounds="[0,0][100,100]"/>')
        with patch.object(ui, '_run') as run:
            ui.tap(node)
            self.assertEqual(30, run.call_args.kwargs['timeout'])
            ui.tap(node, timeout=.4)
            self.assertEqual(.4, run.call_args.kwargs['timeout'])

    def test_failed_blocking_queries_remain_in_the_navigation_trace(self):
        for operation, event in (('screen', 'screen'), ('tap', 'tap-finished'), ('adb', 'device-model')):
            with self.subTest(operation=operation):
                ui = FakeUi(roots=TREE_ROOTS)
                with patch.object(ui, operation, side_effect=TimeoutError(operation + ' timed out')):
                    with self.assertRaisesRegex(TimeoutError, operation + ' timed out'):
                        self.choose(ui)
                traces = list(self.output.glob('downloads-navigation-*.json'))
                self.assertEqual(1, len(traces))
                trace = json.loads(traces[0].read_text())
                self.assertEqual('FAILED', trace['status'])
                self.assertEqual(operation + ' timed out', trace['error'])
                self.assertEqual(1, sum(item['kind'] == event for item in trace['events']))
                recorded = next(item for item in trace['events'] if item['kind'] == event)
                field = 'actionSeconds' if operation == 'tap' else 'querySeconds'
                self.assertIsInstance(recorded[field], (int, float))
                self.assertGreaterEqual(recorded[field], 0)
                traces[0].unlink()

    def test_download_row_outside_native_file_list_is_not_selected(self):
        content = ET.fromstring(ET.tostring(CONTENT))
        content.append(ET.fromstring('<node text="Download"/>'))

        class UnrelatedDownload(FakeUi):
            def tap(self, node, *, timeout=30):
                super().tap(node, timeout=timeout)
                if node.get('text') == self.model:
                    self.current = content

        ui = UnrelatedDownload(roots=TREE_ROOTS)
        self.clocked(ui, [])
        with self.assertRaisesRegex(AssertionError, 'location-content transition'):
            self.choose(ui, timeout=1)
        self.assertEqual(['sdk_gphone64_arm64'], ui.taps)

    def test_observed_empty_folder_waits_without_swiping_into_the_system_gesture_area(self):
        ui = FakeUi(initial=EMPTY_STAGED)
        scenarios = object.__new__(RUNNER.Scenarios)
        scenarios.ui = ui
        ticks = iter(range(100))
        with patch.object(RUNNER.time, 'monotonic', side_effect=lambda: next(ticks)), \
                patch.object(RUNNER.time, 'sleep'), self.assertRaises(AssertionError):
            scenarios.choose_native_file('portable53.lsebdl')
        self.assertEqual([], ui.swipes)
        self.assertEqual([], ui.taps)

    def test_file_scroll_uses_central_grid_coordinates_then_selects_the_file(self):
        root = ET.fromstring(f'<hierarchy><node package="{NATIVE}" resource-id="{NATIVE}:id/dir_list" '
                             'bounds="[0,128][1080,2400]" scrollable="true"/></hierarchy>')
        ui = FakeUi(initial=root)
        scenarios = object.__new__(RUNNER.Scenarios)
        scenarios.ui = ui
        scenarios.choose_native_file('portable53.lsebdl')
        self.assertEqual([('shell', 'input', 'swipe', '540', '1718', '540', '809', '350')], ui.swipes)
        self.assertEqual(['portable53.lsebdl'], ui.taps)


if __name__ == '__main__':
    unittest.main()
