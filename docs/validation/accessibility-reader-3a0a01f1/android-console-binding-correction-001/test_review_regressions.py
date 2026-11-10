import json
import signal
import unittest
from unittest.mock import patch
import run_contact as w
from test_run_contact import FakeRuntime, FakeMonitor

class ReviewRegressions(unittest.TestCase):
    def setUp(self):
        self.no_popen = patch.object(w.subprocess, 'Popen', side_effect=AssertionError('Real Popen prohibited'))
        self.no_live_run = patch.object(w.LiveRuntime, 'run', side_effect=AssertionError('Live run prohibited'))
        self.no_popen.start()
        self.no_live_run.start()
        self.addCleanup(self.no_live_run.stop)
        self.addCleanup(self.no_popen.stop)

    def test_signal_already_delivered_by_monitor_prevents_next_move(self):
        for signum in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=signum):
                runtime = FakeRuntime()
                def healthy(monitor):
                    if monitor.runtime.frame_names == ['down']:
                        monitor.runtime.stop.receive(signum)
                    return True
                with patch.object(FakeMonitor, 'healthy', healthy):
                    w.execute(lease_path='lease', plan_path='plan', output='unused-fake-output', runtime=runtime)
                self.assertEqual(runtime.frame_names, ['down', 'up'])

    def test_same_authorization_at_second_path_cannot_start_second_contact(self):
        runtime = FakeRuntime()
        original_read = runtime.read
        def read(path):
            if str(path) in ('lease-a', 'lease-b'):
                return json.dumps(runtime.lease).encode()
            return original_read(path)
        runtime.read = read
        w.execute(lease_path='lease-a', plan_path='plan', output='unused-fake-output-a', runtime=runtime)
        before = list(runtime.frame_names)
        self.assertEqual(len(before), 10)
        runtime.read_count = 0  # A second fresh command sequence, preserving authorization claims.
        w.execute(lease_path='lease-b', plan_path='plan', output='unused-fake-output-b', runtime=runtime)
        self.assertEqual(runtime.frame_names, before)
