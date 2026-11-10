#!/usr/bin/env python3
"""Check the focused-field snapshot boundary without an Android process."""

import base64
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from android_ui import AndroidUi


PACKAGE = 'dev.logisim.mobile.e2e'


def incomplete_result(fields):
    error = ('IncompleteTreeException: Accessibility child is unavailable at index 11 of 15 under 0/0, '
             'windowId=9484, nodeCount=42')
    attempts = [{
        'attempt': 1, 'startedUptimeMillis': 1000, 'elapsedMillis': 1796,
        'state': 'INCOMPLETE_TREE', 'error': error, 'parentPath': '0/0',
        'childIndex': 11, 'parentChildCount': 15, 'windowId': 9484, 'nodeCount': 42,
    }]
    return {
        'snapshotNonce': fields['snapshotNonce'], 'snapshotExpectedPackage': PACKAGE,
        'snapshotRootAttempts': '3', 'snapshotRootWaitMillis': '96',
        'snapshotRootSource': 'active-window', 'snapshotRootWindowId': '9484',
        'snapshotCaptureAttempts': '1', 'snapshotCaptureState': 'INCOMPLETE_TREE',
        'snapshotCaptureElapsedMillis': '1796', 'snapshotCaptureOverrunMillis': '796',
        'snapshotIncompleteTreeAttempts': '1', 'snapshotTraversalNodeCount': '42',
        'snapshotCaptureAttemptRecordsDropped': '0',
        'snapshotLastIncompleteParentPath': '0/0', 'snapshotLastIncompleteChildIndex': '11',
        'snapshotLastIncompleteParentChildCount': '15', 'snapshotLastIncompleteWindowId': '9484',
        'snapshotLastIncompleteNodeCount': '42',
        'snapshotCaptureAttemptRecordsBase64': base64.b64encode(json.dumps(attempts).encode()).decode(),
        'snapshotError': error,
    }


def unavailable_root_result(fields):
    error = ('RootUnavailableException: UiAutomation returned no active root or active/focused '
             'application window root for ' + PACKAGE + ' before the capture retry deadline')
    records = [{'attempt': 1, 'startedUptimeMillis': 1000, 'elapsedMillis': 1003,
                'state': 'ROOT_UNAVAILABLE', 'error': error}]
    return {
        'snapshotNonce': fields['snapshotNonce'], 'snapshotExpectedPackage': PACKAGE,
        'snapshotRootAttempts': '9', 'snapshotRootWaitMillis': '1003',
        'snapshotRootSource': 'unavailable', 'snapshotRootWindowId': '-1',
        'snapshotCaptureAttempts': '1', 'snapshotCaptureState': 'ROOT_UNAVAILABLE',
        'snapshotCaptureElapsedMillis': '1003', 'snapshotCaptureOverrunMillis': '3',
        'snapshotIncompleteTreeAttempts': '0', 'snapshotTraversalNodeCount': '0',
        'snapshotCaptureAttemptRecordsDropped': '0',
        'snapshotCaptureAttemptRecordsBase64': base64.b64encode(json.dumps(records).encode()).decode(),
        'snapshotError': error,
    }


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class SnapshotUi(AndroidUi):
    def __init__(self, output):
        super().__init__('/fixture/adb', 'fixture-device', PACKAGE, output, focus_snapshots=True)
        self.calls = []
        self.foreground = PACKAGE
        self.pids = ['123', '123']
        self.mutate = lambda fields: fields
        self.instrumentation_code = -1
        self.adb_returncode = 0
        self.instrumentation_stderr = ''
        self.instrumentation_suffix = ''
        self.instrumentation_timeout = None
        self.instrumentation_responses = []
        self.instrumentation_calls = []
        self.clock = None
        self.instrumentation_seconds = 0

    def _run(self, args, *, text=True, **options):
        args = tuple(args)
        self.calls.append(args)
        if args[:3] == ('shell', 'dumpsys', 'activity'):
            stdout = 'topResumedActivity=ActivityRecord{abcd u0 ' + self.foreground + '/ExampleActivity t2}'
        elif args[:2] == ('shell', 'pidof'):
            stdout = self.pids.pop(0)
        elif args[:3] == ('shell', 'am', 'instrument'):
            self.instrumentation_calls.append({'nonce': args[7], 'timeout': options['timeout']})
            if self.clock is not None:
                self.clock.advance(min(self.instrumentation_seconds, options['timeout']))
            if self.instrumentation_timeout:
                raise self.instrumentation_timeout
            nonce = args[7]
            xml = (f'<hierarchy rotation="0" snapshot-nonce="{nonce}" uptime-millis="2345">'
                   f'<node package="{PACKAGE}" class="android.widget.EditText" text="1" '
                   'focused="true" bounds="[10,100][300,150]"/></hierarchy>')
            mutate, code = (self.instrumentation_responses.pop(0) if self.instrumentation_responses
                            else (self.mutate, self.instrumentation_code))
            fields = mutate({'snapshotNonce': nonce, 'snapshotExpectedPackage': PACKAGE,
                             'snapshotXmlBase64': base64.b64encode(xml.encode()).decode(),
                             'snapshotUptimeMillis': '2345', 'snapshotRotation': '0'})
            stdout = '\n'.join('INSTRUMENTATION_RESULT: ' + key + '=' + value for key, value in fields.items())
            stdout += f'\nINSTRUMENTATION_CODE: {code}\n' + self.instrumentation_suffix
        else:
            raise AssertionError(f'Unexpected device command: {args}')
        instrumentation = args[:3] == ('shell', 'am', 'instrument')
        return subprocess.CompletedProcess(args, self.adb_returncode if instrumentation else 0, stdout,
                                           self.instrumentation_stderr if instrumentation else '')


class FocusSnapshotTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.ui = SnapshotUi(Path(temporary.name))
        guard = patch('subprocess.run', side_effect=AssertionError('Real device access is forbidden'))
        guard.start()
        self.addCleanup(guard.stop)

    def test_unavailable_root_then_incomplete_then_complete_uses_fresh_nonces_and_same_pid(self):
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123'] * 6
        self.ui.instrumentation_responses = [(unavailable_root_result, 0), (incomplete_result, 0),
                                             (lambda fields: fields, -1)]
        root = self.ui.screen(timeout=45)
        self.assertEqual('1', root.find('node').get('text'))
        self.assertEqual(3, len(self.ui.instrumentation_calls))
        self.assertEqual(3, len({call['nonce'] for call in self.ui.instrumentation_calls}))
        receipts = [json.loads(path.read_text()) for path in sorted(self.ui.output.glob('focus-snapshot-*.json'))]
        self.assertEqual(['root-unavailable', 'incomplete-tree', 'complete'], [r['status'] for r in receipts])
        self.assertTrue(receipts[0]['validatedRootUnavailable'])
        self.assertEqual([None, '123', '123'], [r['expectedScreenPid'] for r in receipts])
        self.assertEqual(['123'] * 3, [r['appPidAfter'] for r in receipts])
        self.assertNotIn('xml', receipts[0])
        self.assertNotIn('snapshotXmlBase64', receipts[0]['bundle'])
        self.assertEqual(1, len(list(self.ui.output.glob('*.xml'))))
        self.assertFalse(any('uiautomator' in call for call in self.ui.calls))

    def test_unavailable_root_retries_share_the_original_screen_deadline(self):
        clock = FakeClock()
        self.ui.clock = clock
        self.ui.instrumentation_seconds = 8
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123'] * 16
        self.ui.mutate = unavailable_root_result
        self.ui.instrumentation_code = 0
        with patch('android_ui.time.monotonic', side_effect=clock.monotonic):
            with self.assertRaisesRegex(AssertionError, 'existing hierarchy deadline'):
                self.ui.screen(timeout=45)
        self.assertEqual(45, clock.now)
        self.assertEqual([8, 8, 8, 8, 8, 5], [call['timeout'] for call in self.ui.instrumentation_calls])
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_unavailable_root_rejects_changed_foreground_instead_of_switching_to_cli(self):
        self.ui._focused_snapshot_active = True
        def root_before_external_activity(fields):
            self.ui.foreground = 'com.google.android.documentsui'
            return unavailable_root_result(fields)
        self.ui.instrumentation_responses = [(root_before_external_activity, 0)]
        with patch.object(self.ui, '_screen_uiautomator') as cli:
            with self.assertRaisesRegex(AssertionError, 'Foreground package changed'):
                self.ui.screen(timeout=45)
        cli.assert_not_called()
        self.assertEqual(1, len(self.ui.instrumentation_calls))

    def test_unavailable_root_rejects_changed_pid_before_another_instrumentation(self):
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123', '123', '456']
        self.ui.instrumentation_responses = [(unavailable_root_result, 0), (lambda fields: fields, -1)]
        with self.assertRaisesRegex(AssertionError, 'retry changed the app process'):
            self.ui.screen(timeout=45)
        self.assertEqual(1, len(self.ui.instrumentation_calls))
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_unavailable_root_with_invalid_identity_state_or_success_metadata_is_terminal(self):
        cases = (
            ('nonce', {'snapshotNonce': 'old-request'}, 0, ['123', '123']),
            ('package', {'snapshotExpectedPackage': 'another.app.e2e'}, 0, ['123', '123']),
            ('pid', {}, 0, ['123', '456']),
            ('success-code', {}, -1, ['123', '123']),
            ('partial-xml', {'snapshotXmlBase64': 'PHBhcnRpYWwvPg=='}, 0, ['123', '123']),
            ('success-uptime', {'snapshotUptimeMillis': '1000'}, 0, ['123', '123']),
            ('success-rotation', {'snapshotRotation': '0'}, 0, ['123', '123']),
            ('active-root', {'snapshotRootSource': 'active-window'}, 0, ['123', '123']),
            ('root-window', {'snapshotRootWindowId': '123'}, 0, ['123', '123']),
            ('nodes', {'snapshotTraversalNodeCount': '1'}, 0, ['123', '123']),
            ('no-attempt', {'snapshotRootAttempts': '0'}, 0, ['123', '123']),
            ('short-budget', {'snapshotCaptureElapsedMillis': '999'}, 0, ['123', '123']),
            ('bad-overrun', {'snapshotCaptureOverrunMillis': '0'}, 0, ['123', '123']),
            ('wait-over-total', {'snapshotRootWaitMillis': '1004'}, 0, ['123', '123']),
            ('mixed-history', {'snapshotCaptureAttempts': '2', 'snapshotIncompleteTreeAttempts': '1'}, 0, ['123', '123']),
            ('stale-incomplete', {'snapshotLastIncompleteNodeCount': '0'}, 0, ['123', '123']),
            ('dropped-record', {'snapshotCaptureAttemptRecordsDropped': '1'}, 0, ['123', '123']),
            ('diagnostics-error', {'snapshotCaptureDiagnosticsError': 'broken'}, 0, ['123', '123']),
            ('bad-records', {'snapshotCaptureAttemptRecordsBase64': 'not-base64'}, 0, ['123', '123']),
            ('no-records', {'snapshotCaptureAttemptRecordsBase64': 'W10='}, 0, ['123', '123']),
            ('generic-error', {'snapshotError': 'IllegalStateException: no root'}, 0, ['123', '123']),
            ('generic-state', {'snapshotCaptureState': 'ERROR'}, 0, ['123', '123']),
        )
        for name, changes, code, pids in cases:
            with self.subTest(case=name):
                ui = SnapshotUi(self.ui.output / name)
                ui._focused_snapshot_active = True
                ui.pids = pids
                ui.instrumentation_code = code
                ui.mutate = lambda fields, changes=changes: dict(unavailable_root_result(fields), **changes)
                with self.assertRaises(AssertionError) as rejected:
                    ui.screen(timeout=45)
                self.assertNotEqual('SnapshotRootUnavailable', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))
                receipt = json.loads(next(ui.output.glob('focus-snapshot-*.json')).read_text())
                self.assertEqual('rejected', receipt['status'])
                self.assertNotIn('validatedRootUnavailable', receipt)
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_unavailable_root_requires_a_consistent_single_attempt_record(self):
        base = unavailable_root_result({'snapshotNonce': 'fixture'})
        original = json.loads(base64.b64decode(base['snapshotCaptureAttemptRecordsBase64']))[0]
        cases = ({'attempt': True}, {'attempt': 2}, {'startedUptimeMillis': -1},
                 {'elapsedMillis': 1002}, {'elapsedMillis': '1003'}, {'state': 'ERROR'},
                 {'error': 'RootUnavailableException: unrelated'}, {'nodeCount': 0})
        for index, changes in enumerate(cases):
            with self.subTest(changes=changes):
                ui = SnapshotUi(self.ui.output / str(index))
                ui._focused_snapshot_active = True
                ui.instrumentation_code = 0
                history = base64.b64encode(json.dumps([dict(original, **changes)]).encode()).decode()
                ui.mutate = lambda fields, history=history: dict(unavailable_root_result(fields),
                    snapshotCaptureAttemptRecordsBase64=history)
                with self.assertRaises(AssertionError) as rejected:
                    ui.screen(timeout=45)
                self.assertNotEqual('SnapshotRootUnavailable', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))

    def test_root_wait_can_be_shorter_than_the_capture_when_preparation_consumes_budget(self):
        self.ui.instrumentation_code = 0
        self.ui.mutate = lambda fields: dict(unavailable_root_result(fields), snapshotRootWaitMillis='900')
        with self.assertRaises(AssertionError) as root:
            self.ui._screen_instrumentation(8)
        self.assertEqual('SnapshotRootUnavailable', type(root.exception).__name__)
        self.assertEqual('123', root.exception.app_pid)

    def test_root_state_cannot_bypass_fatal_instrumentation_protocol(self):
        for name, suffix, stderr in (
            ('after-terminal', 'INSTRUMENTATION_RESULT: late=value\n', ''),
            ('stderr-aborted', '', 'INSTRUMENTATION_ABORTED: Process crashed.\n'),
            ('duplicate-terminal', 'INSTRUMENTATION_CODE: 0\n', ''),
        ):
            with self.subTest(case=name):
                ui = SnapshotUi(self.ui.output / name)
                ui._focused_snapshot_active = True
                ui.instrumentation_code = 0
                ui.instrumentation_suffix = suffix
                ui.instrumentation_stderr = stderr
                ui.mutate = unavailable_root_result
                with self.assertRaises(AssertionError) as rejected:
                    ui.screen(timeout=45)
                self.assertNotEqual('SnapshotRootUnavailable', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))

    def test_recorded_legacy_root_errors_remain_terminal_without_reclassification(self):
        recordings = Path(__file__).resolve().parents[1] / 'docs/validation/android-snapshot-root-unavailable'
        for case in ('external61', 'fpga65'):
            with self.subTest(case=case):
                original_receipt = json.loads((recordings / case / 'failure.json').read_text())
                stdout = (recordings / case / 'failure.stdout.txt').read_text()
                stderr = (recordings / case / 'failure.stderr.txt').read_text()
                ui = SnapshotUi(self.ui.output / case)
                ui.package = ui.foreground = original_receipt['expectedPackage']
                ui.snapshot_package = ui.package.removesuffix('.e2e') + '.snapshot.e2e.test'
                ui._focused_snapshot_active = True
                ui.pids = [original_receipt['appPidBefore'], original_receipt['appPidAfter']]
                run = ui._run
                def recorded(args, **options):
                    result = run(args, **options)
                    if tuple(args[:3]) == ('shell', 'am', 'instrument'):
                        result.stdout, result.stderr = stdout, stderr
                    return result
                with patch.object(ui, '_run', side_effect=recorded), patch('android_ui.uuid.uuid4') as nonce:
                    nonce.return_value.hex = original_receipt['nonce']
                    with self.assertRaisesRegex(AssertionError, 'IllegalStateException: UiAutomation returned no active root'):
                        ui.screen(timeout=45)
                self.assertEqual(1, len(ui.instrumentation_calls))
                receipt = json.loads(next(ui.output.glob('focus-snapshot-*.json')).read_text())
                self.assertEqual('rejected', receipt['status'])
                self.assertNotIn('validatedRootUnavailable', receipt)
                self.assertEqual('ERROR', receipt['bundle']['snapshotCaptureState'])
                self.assertEqual(stdout, (ui.output / receipt['stdout']).read_text())
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_authenticated_incomplete_tree_retries_with_a_fresh_nonce_and_retains_both_receipts(self):
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123', '123', '123', '123']
        self.ui.instrumentation_responses = [(incomplete_result, 0), (lambda fields: fields, -1)]
        root = self.ui.screen(timeout=45)
        self.assertEqual('1', root.find('node').get('text'))
        self.assertEqual(2, len(self.ui.instrumentation_calls))
        first, second = self.ui.instrumentation_calls
        self.assertNotEqual(first['nonce'], second['nonce'])
        self.assertLessEqual(first['timeout'], 8)
        self.assertLessEqual(second['timeout'], 8)
        receipts = [json.loads(path.read_text()) for path in sorted(self.ui.output.glob('focus-snapshot-*.json'))]
        self.assertEqual(['incomplete-tree', 'complete'], [receipt['status'] for receipt in receipts])
        self.assertNotIn('xml', receipts[0])
        self.assertNotIn('snapshotXmlBase64', receipts[0]['bundle'])
        self.assertEqual('INCOMPLETE_TREE', receipts[0]['bundle']['snapshotCaptureState'])
        self.assertEqual(1, len(list(self.ui.output.glob('*.xml'))))
        for receipt in receipts:
            self.assertTrue((self.ui.output / receipt['stdout']).is_file())
            self.assertTrue((self.ui.output / receipt['stderr']).is_file())
            self.assertEqual('123', receipt['appPidAfter'])
        self.assertEqual(2, sum(command[:3] == ('shell', 'dumpsys', 'activity') for command in self.ui.calls))

    def test_app_window_without_bounds_is_read_again_with_the_same_process(self):
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123'] * 4

        def relayout(fields):
            xml = (f'<hierarchy rotation="0" snapshot-nonce="{fields["snapshotNonce"]}" uptime-millis="2345">'
                   f'<node package="{PACKAGE}" class="android.widget.FrameLayout" bounds="[0,0][0,0]">'
                   f'<node package="{PACKAGE}" class="android.view.View" resource-id="a11y-circuit-list" '
                   'bounds="[64,689][1016,2220]"/></node></hierarchy>')
            return {**fields, 'snapshotXmlBase64': base64.b64encode(xml.encode()).decode()}

        self.ui.instrumentation_responses = [(relayout, -1), (lambda fields: fields, -1)]
        root = self.ui.screen(timeout=45)
        self.assertEqual('1', root.find('node').get('text'))
        self.assertEqual(2, len(self.ui.instrumentation_calls))
        receipts = [json.loads(path.read_text()) for path in sorted(self.ui.output.glob('focus-snapshot-*.json'))]
        self.assertEqual(['transient-layout', 'complete'], [receipt['status'] for receipt in receipts])
        self.assertIn('[0,0][0,0]', receipts[0]['error'])

    def test_app_window_without_bounds_still_ends_at_the_screen_deadline(self):
        clock = FakeClock()
        self.ui.clock = clock
        self.ui.instrumentation_seconds = 8
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123'] * 16
        empty = '<node package="' + PACKAGE + '" class="android.widget.FrameLayout" bounds="[0,0][0,0]"/>'
        self.ui.mutate = lambda fields: {**fields, 'snapshotXmlBase64': base64.b64encode(
            (f'<hierarchy rotation="0" snapshot-nonce="{fields["snapshotNonce"]}" uptime-millis="2345">'
             + empty + '</hierarchy>').encode()).decode()}
        with patch('android_ui.time.monotonic', side_effect=clock.monotonic):
            with self.assertRaises(AssertionError):
                self.ui.screen(timeout=45)
        self.assertEqual(45, clock.now)

    def test_incomplete_retries_share_the_original_45_second_deadline(self):
        clock = FakeClock()
        self.ui.clock = clock
        self.ui.instrumentation_seconds = 8
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123'] * 16
        self.ui.mutate = incomplete_result
        self.ui.instrumentation_code = 0
        with patch('android_ui.time.monotonic', side_effect=clock.monotonic):
            with self.assertRaises(AssertionError):
                self.ui.screen(timeout=45)
        self.assertEqual(45, clock.now)
        self.assertEqual([8, 8, 8, 8, 8, 5], [call['timeout'] for call in self.ui.instrumentation_calls])
        self.assertEqual(6, len({call['nonce'] for call in self.ui.instrumentation_calls}))
        self.assertEqual([], list(self.ui.output.glob('*.xml')))
        receipts = [json.loads(path.read_text()) for path in sorted(self.ui.output.glob('focus-snapshot-*.json'))]
        self.assertEqual(6, len(receipts))
        self.assertEqual(['incomplete-tree'] * 5, [receipt['status'] for receipt in receipts[:5]])

    def test_authenticated_incompleteness_exposes_the_verified_app_pid(self):
        self.ui.mutate = incomplete_result
        self.ui.instrumentation_code = 0
        with self.assertRaises(AssertionError) as incomplete:
            self.ui._screen_instrumentation(8)
        self.assertEqual('IncompleteSnapshotTree', type(incomplete.exception).__name__)
        self.assertEqual('123', incomplete.exception.app_pid)

    def test_retry_rechecks_foreground_and_hands_documents_ui_to_the_cli(self):
        self.ui._focused_snapshot_active = True

        def incomplete_before_external_activity(fields):
            self.ui.foreground = 'com.google.android.documentsui'
            return incomplete_result(fields)

        self.ui.instrumentation_responses = [(incomplete_before_external_activity, 0)]
        documents = ET.fromstring('<hierarchy><node package="com.google.android.documentsui" text="Save"/></hierarchy>')
        with patch.object(self.ui, '_screen_uiautomator', return_value=documents) as cli:
            self.assertIs(documents, self.ui.screen(timeout=45))
        cli.assert_called_once()
        cli_timeout = cli.call_args.args[0] if cli.call_args.args else cli.call_args.kwargs['timeout']
        self.assertLessEqual(cli_timeout, 45)
        self.assertGreater(cli_timeout, 0)
        self.assertFalse(self.ui._focused_snapshot_active)
        self.assertEqual(1, len(self.ui.instrumentation_calls))
        self.assertEqual(2, sum(command[:3] == ('shell', 'dumpsys', 'activity') for command in self.ui.calls))
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual('incomplete-tree', receipt['status'])

    def test_incomplete_tree_with_bad_nonce_package_or_pid_is_not_retried(self):
        cases = (
            ('nonce', {'snapshotNonce': 'old-request'}, ['123', '123']),
            ('package', {'snapshotExpectedPackage': 'another.app.e2e'}, ['123', '123']),
            ('pid', {}, ['123', '456']),
        )
        for name, changes, pids in cases:
            with self.subTest(case=name):
                ui = SnapshotUi(self.ui.output / name)
                ui._focused_snapshot_active = True
                ui.pids = pids
                ui.instrumentation_code = 0
                ui.mutate = lambda fields, changes=changes: dict(incomplete_result(fields), **changes)
                with self.assertRaises(AssertionError) as rejected:
                    ui.screen(timeout=45)
                self.assertNotEqual('IncompleteSnapshotTree', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))
                receipt = json.loads(next(ui.output.glob('focus-snapshot-*.json')).read_text())
                self.assertEqual('rejected', receipt['status'])
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_retry_pins_the_app_pid_across_separate_capture_invocations(self):
        self.ui._focused_snapshot_active = True
        self.ui.pids = ['123', '123', '456', '456']
        self.ui.instrumentation_responses = [(incomplete_result, 0), (lambda fields: fields, -1)]
        with self.assertRaises(AssertionError) as rejected:
            self.ui.screen(timeout=45)
        self.assertNotEqual('IncompleteSnapshotTree', type(rejected.exception).__name__)
        self.assertEqual(1, len(self.ui.instrumentation_calls))
        self.assertEqual(2, sum(command[:3] == ('shell', 'dumpsys', 'activity') for command in self.ui.calls))
        receipts = [json.loads(path.read_text()) for path in sorted(self.ui.output.glob('focus-snapshot-*.json'))]
        self.assertEqual(['incomplete-tree', 'rejected'], [receipt['status'] for receipt in receipts])
        self.assertEqual('123', receipts[1]['expectedScreenPid'])
        self.assertEqual('456', receipts[1]['appPidBefore'])
        self.assertEqual({}, receipts[1]['bundle'])
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_untyped_sdk_error_is_terminal(self):
        for state in ('ERROR', 'SDK_ERROR', 'INCOMPLETE_TREE'):
            with self.subTest(state=state):
                ui = SnapshotUi(self.ui.output / state)
                ui._focused_snapshot_active = True
                ui.instrumentation_code = 0
                ui.mutate = lambda fields, state=state: dict(incomplete_result(fields), snapshotCaptureState=state,
                    snapshotError='IllegalStateException: Accessibility connection unavailable')
                with self.assertRaises(AssertionError) as rejected:
                    ui.screen(timeout=45)
                self.assertNotEqual('IncompleteSnapshotTree', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_incomplete_descendant_window_can_differ_from_the_root_window(self):
        self.ui.instrumentation_code = 0
        self.ui.mutate = lambda fields: dict(incomplete_result(fields), snapshotRootWindowId='9000')
        with self.assertRaises(AssertionError) as incomplete:
            self.ui._screen_instrumentation(8)
        self.assertEqual('IncompleteSnapshotTree', type(incomplete.exception).__name__)
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual('9000', receipt['bundle']['snapshotRootWindowId'])
        self.assertEqual('9484', receipt['bundle']['snapshotLastIncompleteWindowId'])

    def test_incomplete_history_timings_must_fit_the_capture(self):
        cases = (
            ('duration-over-total', 1796, [(1000, 999999)], False),
            ('overlap', 1000, [(1000, 400), (1250, 550)], False),
            ('span-over-total', 1000, [(1000, 400), (1600, 550)], False),
            ('valid-history', 1000, [(1000, 400), (1450, 550)], True),
        )
        for name, elapsed, timings, valid in cases:
            with self.subTest(case=name):
                ui = SnapshotUi(self.ui.output / name)
                ui.instrumentation_code = 0

                def metadata(fields):
                    values = incomplete_result(fields)
                    original = json.loads(base64.b64decode(values['snapshotCaptureAttemptRecordsBase64']))[0]
                    records = [dict(original, attempt=index + 1, startedUptimeMillis=start, elapsedMillis=duration)
                               for index, (start, duration) in enumerate(timings)]
                    values.update(snapshotCaptureAttempts=str(len(records)), snapshotIncompleteTreeAttempts=str(len(records)),
                                  snapshotCaptureElapsedMillis=str(elapsed), snapshotCaptureOverrunMillis=str(max(0, elapsed - 1000)),
                                  snapshotCaptureAttemptRecordsBase64=base64.b64encode(json.dumps(records).encode()).decode())
                    return values

                ui.mutate = metadata
                with self.assertRaises(AssertionError) as result:
                    ui._screen_instrumentation(8)
                self.assertEqual(valid, type(result.exception).__name__ == 'IncompleteSnapshotTree')
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_incomplete_tree_with_partial_xml_or_invalid_protocol_is_terminal(self):
        partial_xml = base64.b64encode(b'<hierarchy><node').decode()
        history = json.loads(base64.b64decode(incomplete_result({'snapshotNonce': 'fixture'})[
            'snapshotCaptureAttemptRecordsBase64']))
        history[0]['windowId'] = 9999
        inconsistent_history = base64.b64encode(json.dumps(history).encode()).decode()
        cases = (
            ('partial-xml', {'snapshotXmlBase64': partial_xml}, 0, ''),
            ('success-code', {}, -1, ''),
            ('duplicate-code', {}, 0, 'INSTRUMENTATION_CODE: 0\n'),
            ('duplicate-field', {}, 0, 'INSTRUMENTATION_RESULT: snapshotRootAttempts=3\n'),
            ('invalid-number', {'snapshotCaptureElapsedMillis': 'not-a-number'}, 0, ''),
            ('invalid-history', {'snapshotCaptureAttemptRecordsBase64': 'not-base64'}, 0, ''),
            ('inconsistent-history', {'snapshotCaptureAttemptRecordsBase64': inconsistent_history}, 0, ''),
        )
        for name, changes, code, suffix in cases:
            with self.subTest(case=name):
                ui = SnapshotUi(self.ui.output / name)
                ui._focused_snapshot_active = True
                ui.instrumentation_code = code
                ui.instrumentation_suffix = suffix
                ui.mutate = lambda fields, changes=changes: dict(incomplete_result(fields), **changes)
                with self.assertRaises(AssertionError) as rejected:
                    ui.screen(timeout=45)
                self.assertNotEqual('IncompleteSnapshotTree', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_contradictory_instrumentation_protocol_cannot_request_an_incomplete_retry(self):
        cases = (
            ('late-result', lambda text: text + 'INSTRUMENTATION_RESULT: unexpected=after-terminal\n', ''),
            ('terminal-first', lambda text: 'INSTRUMENTATION_CODE: 0\n' + text.replace('INSTRUMENTATION_CODE: 0\n', ''), ''),
            ('failed', lambda text: 'INSTRUMENTATION_FAILED: Process crashed.\n' + text, ''),
            ('aborted', lambda text: 'INSTRUMENTATION_ABORTED: Process crashed.\n' + text, ''),
            ('short-message', lambda text: 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n' + text, ''),
            ('status-error', lambda text: 'INSTRUMENTATION_STATUS: Error=Process crashed.\n' + text, ''),
            ('stderr-failed', lambda text: text, 'INSTRUMENTATION_FAILED: Process crashed.\n'),
        )
        for name, transform, stderr in cases:
            with self.subTest(case=name):
                ui = SnapshotUi(self.ui.output / name)
                ui.instrumentation_code = 0
                ui.instrumentation_stderr = stderr
                ui.mutate = incomplete_result
                original = ui._run

                def observed(args, **options):
                    response = original(args, **options)
                    if tuple(args[:3]) == ('shell', 'am', 'instrument'):
                        response.stdout = transform(response.stdout)
                    return response

                with patch.object(ui, '_run', side_effect=observed):
                    with self.assertRaises(AssertionError) as rejected:
                        ui._screen_instrumentation(8)
                self.assertNotEqual('IncompleteSnapshotTree', type(rejected.exception).__name__)
                self.assertEqual(1, len(ui.instrumentation_calls))
                receipt = json.loads(next(ui.output.glob('focus-snapshot-*.json')).read_text())
                self.assertEqual('rejected', receipt['status'])
                self.assertNotIn('validatedIncompleteTree', receipt)
                self.assertEqual('INCOMPLETE_TREE', receipt['bundle']['snapshotCaptureState'])
                self.assertTrue((ui.output / receipt['stdout']).is_file())
                self.assertEqual(stderr, (ui.output / receipt['stderr']).read_text())
                self.assertEqual([], list(ui.output.glob('*.xml')))

    def test_timeout_without_a_bundle_is_not_retried(self):
        self.ui._focused_snapshot_active = True
        self.ui.instrumentation_timeout = subprocess.TimeoutExpired('fixture instrumentation', 8)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.ui.screen(timeout=45)
        self.assertEqual(1, len(self.ui.instrumentation_calls))
        self.assertEqual(1, sum(command[:2] == ('shell', 'pidof') for command in self.ui.calls))
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual('timed-out', receipt['status'])
        self.assertEqual({}, receipt['bundle'])
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_focused_snapshot_is_fresh_and_preserves_the_app_pid(self):
        self.ui._focused_snapshot_active = True
        root = self.ui.screen(timeout=8)
        self.assertEqual('1', root.find('node').get('text'))
        self.assertEqual('true', root.find('node').get('focused'))
        self.assertEqual('2345', root.get('uptime-millis'))
        self.assertEqual(2, sum(command[:2] == ('shell', 'pidof') for command in self.ui.calls))
        command = next(command for command in self.ui.calls if command[:3] == ('shell', 'am', 'instrument'))
        self.assertEqual('dev.logisim.mobile.snapshot.e2e.test/dev.logisim.mobile.documents.UiSnapshotInstrumentation', command[-1])
        self.assertFalse(any('uiautomator' in command for command in self.ui.calls))
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual('complete', receipt['status'])
        self.assertEqual('123', receipt['appPidAfter'])
        self.assertEqual(0, receipt['adbReturncode'])
        self.assertEqual(['-1'], receipt['instrumentationCodes'])
        self.assertEqual(receipt['nonce'], receipt['bundle']['snapshotNonce'])
        self.assertIn('INSTRUMENTATION_CODE: -1', (self.ui.output / receipt['stdout']).read_text())
        self.assertEqual('', (self.ui.output / receipt['stderr']).read_text())

    def test_failed_capture_preserves_all_metadata_and_raw_output_without_publishing_xml(self):
        self.ui.instrumentation_code = 0
        self.ui.instrumentation_stderr = 'fixture instrumentation diagnostic\n'
        self.ui.mutate = lambda fields: {
            'snapshotNonce': fields['snapshotNonce'],
            'snapshotExpectedPackage': PACKAGE,
            'snapshotRootSource': 'active-window',
            'snapshotRootWindowId': '79',
            'snapshotCaptureAttempts': '2',
            'snapshotCaptureState': 'INCOMPLETE_TREE',
            'snapshotCaptureElapsedMillis': '1001',
            'snapshotCaptureOverrunMillis': '1',
            'snapshotFutureProbeFlag': 'kept',
            'snapshotXmlBase64': base64.b64encode(b'<hierarchy><node').decode(),
            'snapshotError': 'IllegalStateException: Accessibility child is unavailable at index 7',
        }
        with self.assertRaisesRegex(AssertionError, 'Accessibility child is unavailable at index 7'):
            self.ui._screen_instrumentation(8)
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual('rejected', receipt['status'])
        self.assertEqual(['0'], receipt['instrumentationCodes'])
        self.assertEqual('123', receipt['appPidBefore'])
        self.assertEqual('123', receipt['appPidAfter'])
        self.assertEqual('2', receipt['bundle']['snapshotCaptureAttempts'])
        self.assertEqual('INCOMPLETE_TREE', receipt['snapshotCaptureState'])
        self.assertEqual('kept', receipt['bundle']['snapshotFutureProbeFlag'])
        self.assertEqual('kept', receipt['snapshotFutureProbeFlag'])
        self.assertIn('snapshotError=IllegalStateException', (self.ui.output / receipt['stdout']).read_text())
        self.assertEqual('fixture instrumentation diagnostic\n', (self.ui.output / receipt['stderr']).read_text())
        self.assertNotIn('xml', receipt)
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_failed_attempt_receipt_is_not_overwritten_by_the_next_success(self):
        self.ui.mutate = lambda fields: dict(fields, snapshotError='Incomplete tree')
        with self.assertRaisesRegex(AssertionError, 'Incomplete tree'):
            self.ui._screen_instrumentation(8)
        self.ui.pids = ['123', '123']
        self.ui.mutate = lambda fields: fields
        self.ui._screen_instrumentation(8)
        receipts = sorted(self.ui.output.glob('focus-snapshot-*.json'))
        self.assertEqual(2, len(receipts))
        self.assertEqual(['rejected', 'complete'], [json.loads(path.read_text())['status'] for path in receipts])
        self.assertEqual(1, len(list(self.ui.output.glob('*.xml'))))

    def test_nonzero_adb_exit_preserves_the_process_result(self):
        self.ui.adb_returncode = 9
        self.ui.instrumentation_stderr = 'fixture adb rejection\n'
        with self.assertRaisesRegex(RuntimeError, 'adb failed \\(9\\)'):
            self.ui._screen_instrumentation(8)
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual(9, receipt['adbReturncode'])
        self.assertEqual('rejected', receipt['status'])
        self.assertIsNone(receipt['appPidAfter'])
        self.assertEqual('fixture adb rejection\n', (self.ui.output / receipt['stderr']).read_text())
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_timeout_preserves_partial_output_without_another_pid_query(self):
        self.ui.instrumentation_timeout = subprocess.TimeoutExpired(
            'fixture instrumentation', 8,
            output=b'INSTRUMENTATION_RESULT: snapshotCaptureAttempts=2\n', stderr=b'fixture timeout\xff\n')
        with self.assertRaises(subprocess.TimeoutExpired):
            self.ui._screen_instrumentation(8)
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual('timed-out', receipt['status'])
        self.assertEqual(8, receipt['timeoutSeconds'])
        self.assertEqual({'snapshotCaptureAttempts': '2'}, receipt['bundle'])
        self.assertIsNone(receipt['adbReturncode'])
        self.assertIsNone(receipt['appPidAfter'])
        self.assertEqual(1, sum(command[:2] == ('shell', 'pidof') for command in self.ui.calls))
        self.assertEqual(b'fixture timeout\xff\n', (self.ui.output / receipt['stderr']).read_bytes())
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_duplicate_fields_are_preserved_and_rejected(self):
        self.ui.instrumentation_suffix = 'INSTRUMENTATION_RESULT: snapshotNonce=duplicate\n'
        with self.assertRaisesRegex(AssertionError, 'Duplicate snapshot result field: snapshotNonce'):
            self.ui._screen_instrumentation(8)
        receipt = json.loads(next(self.ui.output.glob('focus-snapshot-*.json')).read_text())
        self.assertEqual(['snapshotNonce'], receipt['duplicateBundleKeys'])
        self.assertEqual(receipt['nonce'], receipt['bundle']['snapshotNonce'])
        self.assertIn('snapshotNonce=duplicate', (self.ui.output / receipt['stdout']).read_text())
        self.assertEqual([], list(self.ui.output.glob('*.xml')))

    def test_fixture_activity_keeps_the_cli_backend_and_is_not_instrumented(self):
        self.ui._focused_snapshot_active = True
        self.ui.foreground = PACKAGE + '.test'
        current = ET.fromstring('<hierarchy><node text="Read copied XML"/></hierarchy>')
        with patch.object(self.ui, '_screen_uiautomator', return_value=current) as cli:
            self.assertIs(current, self.ui.screen(timeout=8))
        cli.assert_called_once()
        self.assertFalse(self.ui._focused_snapshot_active)
        self.assertFalse(any(command[:3] == ('shell', 'am', 'instrument') for command in self.ui.calls))

    def test_changed_app_pid_fails_instead_of_reusing_the_new_tree(self):
        self.ui.pids = ['123', '456']
        with self.assertRaisesRegex(AssertionError, 'changed the app process'):
            self.ui._screen_instrumentation(8)

    def test_old_nonce_is_rejected(self):
        self.ui.mutate = lambda fields: dict(fields, snapshotNonce='old-request')
        with self.assertRaisesRegex(AssertionError, 'old or missing nonce'):
            self.ui._screen_instrumentation(8)

    def test_invalid_xml_and_inconsistent_metadata_are_rejected(self):
        for changes in ({'snapshotXmlBase64': 'not base64'}, {'snapshotRotation': '1'}, {'snapshotUptimeMillis': '9'}):
            with self.subTest(changes=changes):
                self.ui.pids = ['123', '123']
                self.ui.mutate = lambda fields, changes=changes: dict(fields, **changes)
                with self.assertRaises(AssertionError):
                    self.ui._screen_instrumentation(8)

    def test_unchanged_text_does_not_tap_or_start_a_snapshot(self):
        node = ET.fromstring(f'<node package="{PACKAGE}" class="android.widget.EditText" text="100"/>')
        self.ui.input_text(node, '100')
        self.assertEqual([], self.ui.calls)

    def test_normal_app_cannot_enable_focus_snapshots(self):
        with self.assertRaisesRegex(ValueError, 'isolated .e2e'):
            AndroidUi('/fixture/adb', 'device', 'dev.logisim.mobile', self.ui.output, focus_snapshots=True)


if __name__ == '__main__':
    unittest.main()
