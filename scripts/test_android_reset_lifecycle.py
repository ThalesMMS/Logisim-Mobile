#!/usr/bin/env python3
"""Check clean-run lifecycle bookkeeping without Android processes or UI access."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('reset_lifecycle_runner', SCRIPTS / 'e2e-android.py')
RUNNER = importlib.util.module_from_spec(spec)
spec.loader.exec_module(RUNNER)
PACKAGE = 'thalesmms.logisim.mobile.e2e'
OLD = '* Task{cf4ef7c #17 type=standard A=10207:thalesmms.logisim.mobile.e2e U=0 visible=true sz=1}'


class LifecycleTests(unittest.TestCase):
    def test_only_the_exact_isolated_app_affinity_is_selected(self):
        data = '\n'.join([OLD, OLD, '* Task{other #3 type=standard A=10099:thalesmms.logisim.mobile U=0}',
                          '* Task{test #9 type=standard A=10208:thalesmms.logisim.mobile.e2e.test U=0}'])
        self.assertEqual({'17'}, RUNNER.owned_task_ids(data, PACKAGE))

    def test_reset_waits_for_preexisting_tasks_then_leaves_new_or_other_tasks_alone(self):
        class Ui:
            package = PACKAGE
            states = [OLD, '* Task{new #18 type=standard A=10207:thalesmms.logisim.mobile.e2e U=0}']
            def adb(self, *args):
                self.asserted = args
                return self.states.pop(0)
        ui = Ui()
        with patch.object(RUNNER.time, 'sleep'):
            result = RUNNER.await_reset_tasks_removed(ui, {'17'})
        self.assertEqual([{'previousTaskIdsStillPresent': ['17']}, {'previousTaskIdsStillPresent': []}], result)
        self.assertEqual(('shell', 'dumpsys', 'activity', 'activities'), ui.asserted)

    def test_no_previous_task_needs_no_device_query(self):
        ui = type('Ui', (), {'adb': lambda *_: self.fail('Unexpected device query')})()
        self.assertEqual([], RUNNER.await_reset_tasks_removed(ui, set()))

    def test_removal_timeout_fails_before_a_new_launch(self):
        ui = type('Ui', (), {'package': PACKAGE, 'adb': lambda *_: OLD})()
        ticks = iter(range(100))
        with patch.object(RUNNER.time, 'monotonic', side_effect=lambda: next(ticks)), \
                patch.object(RUNNER.time, 'sleep'), self.assertRaisesRegex(AssertionError, 'finished removal'):
            RUNNER.await_reset_tasks_removed(ui, {'17'}, timeout=2)

    def test_launch_result_is_preserved_and_a_system_start_timeout_is_not_an_editor_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            ui = type('Ui', (), {'package': PACKAGE, 'output': Path(temporary), 'adb': lambda *_: 'Status: timeout\n'})()
            case = object.__new__(RUNNER.Scenarios)
            case.ui = ui
            case.wait_editor = lambda: self.fail('Do not query AX after a failed activity start')
            with self.assertRaisesRegex(AssertionError, 'am start successfully'):
                case.launch()
            self.assertEqual('Status: timeout\n', (Path(temporary) / 'launch-01.txt').read_text())


if __name__ == '__main__':
    with patch.object(subprocess, 'Popen', side_effect=AssertionError('No processes allowed')):
        unittest.main()
