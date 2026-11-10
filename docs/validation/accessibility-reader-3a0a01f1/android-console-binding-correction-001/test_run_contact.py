import contextlib
import io
import json
from pathlib import Path
import signal
import subprocess
import unittest
from unittest.mock import patch

import run_contact as w

from test_check_binding import ACTIVITY, REALISTIC_FOOTER_A11Y

READER = ("ACCESSIBILITY MANAGER\ncurrentUserId=0\n" + REALISTIC_FOOTER_A11Y + "Global Info [ ]\n").encode()
ACTIVITY_BYTES = ACTIVITY.encode()
CAPS = Path(__file__).with_name('numeric-capabilities.fixture.txt').read_bytes()
DISPLAY = ("mViewports=[DisplayViewport{type=INTERNAL, valid=true, isActive=true, displayId=0, "
           "orientation=0, logicalFrame=Rect(0, 0 - 1080, 2400), physicalFrame=Rect(0, 0 - 1080, 2400), "
           "deviceWidth=1080, deviceHeight=2400}]\n").encode()
HEADERS = b'add device 1: /dev/input/event1\n  name: "virtio_input_multi_touch_1"\n'


class FakeMonitor:
    def __init__(self, runtime):
        self.runtime = runtime
        self.started = runtime.now
        self.ready = runtime.ready
    def await_ready(self, stop):
        return self.ready and not stop.requested
    def healthy(self):
        return True
    def finish(self):
        return {"argv": w.MONITOR_ARGV, "host_pid": -1, "start": self.started,
                "end": self.runtime.now, "returncode": 124 if self.runtime.terminal else None,
                "terminal_observed": self.runtime.terminal, "capture_error": None,
                "capture_complete": self.runtime.terminal, "ready": self.ready,
                "stdout": HEADERS, "stderr": b"", "wrapper_forced_stop_attempted": False}


class FakeRuntime:
    """Past-dated in-memory lease and fake subprocesses only; no lease file exists."""
    def __init__(self):
        self.now = 0.0
        self.epoch = 946684810  # 2000; always expired in the real live clock.
        self.calls = []
        self.read_count = 0
        self.frame_names = []
        self.claimed = set()
        self.persisted = None
        self.ready = True
        self.terminal = True
        self.hook = lambda name: None
        self.sleep_hook = lambda seconds: None
        self.monitors_started = 0
        self.contact_raw = w.DEFAULT_CONTACT.read_bytes()
        self.wrapper_raw = Path(w.__file__).read_bytes()
        self.plan_raw = b'{"synthetic":"offline-only"}'
        self.helper_raw = Path(w.BINDING_HELPER).read_bytes()
        self.lease = {"schema_version": 1, "issuer": "/root", "scope": "one-console-contact",
                      "serial": w.SERIAL, "product_source": w.PRODUCT_SOURCE, "lease_id": "expired-test-fixture",
                      "contact_sha256": w.digest(self.contact_raw), "wrapper_sha256": w.digest(self.wrapper_raw),
                      "plan_sha256": w.digest(self.plan_raw), "not_before_unix": 946684800,
                      "expires_unix": 946684860, "max_contacts": 1}
        self.responses = {"reader-before": READER, "reader-after": READER,
                          "system-server-pid": b'777\n', "talkback-service": ACTIVITY_BYTES, "caps": CAPS,
                          "size": b'Physical size: 1080x2400\n', "display": DISPLAY}
        module, frames, commands = w.load_contact(self.contact_raw)
        self.names = {tuple(argv): name for name, argv in commands.items()}
    def monotonic(self): return self.now
    def wall(self): return self.epoch + self.now
    def sleep(self, seconds):
        self.sleep_hook(seconds)
        self.now += max(seconds, 0.000000001)
    def read(self, path):
        if str(path) in ('lease', 'lease-copy'): return json.dumps(self.lease).encode()
        if str(path) == 'plan': return self.plan_raw
        if Path(path) == w.BINDING_HELPER: return self.helper_raw
        if Path(path) == Path(w.__file__): return self.wrapper_raw
        if Path(path) == w.DEFAULT_CONTACT: return self.contact_raw
        raise OSError('synthetic missing file')
    def claim(self, path, digest):
        if path in self.claimed: raise FileExistsError()
        self.claimed.add(path)
    @contextlib.contextmanager
    def signals(self, stop):
        self.stop = stop
        yield
    def run(self, argv, timeout):
        self.calls.append((tuple(argv), timeout))
        if self.read_count < len(w.READONLY) and argv == w.READONLY[self.read_count][1]:
            label = w.READONLY[self.read_count][0]
            self.read_count += 1
            self.now += 0.001
            return subprocess.CompletedProcess(argv, 0, self.responses[label], b'')
        name = self.names[argv]
        self.frame_names.append(name)
        self.hook(name)
        return subprocess.CompletedProcess(argv, 0, b'OK\r\n', b'')
    def start_monitor(self):
        self.monitors_started += 1
        return FakeMonitor(self)
    def reserve(self, output):
        pass
    def persist(self, output, records, monitor, receipt):
        self.persisted = (list(records), monitor, dict(receipt))


def execute(runtime, on_record=lambda record: None):
    return w.execute(lease_path='lease', plan_path='plan', output='unused-fake-output',
                     runtime=runtime, on_record=on_record)


class WrapperTests(unittest.TestCase):
    def test_default_only_describes_and_cannot_call_adb(self):
        with patch.object(w.LiveRuntime, 'run', side_effect=AssertionError('no command')):
            output = io.StringIO()
            with contextlib.redirect_stdout(output): self.assertEqual(w.main([]), 0)
        result = json.loads(output.getvalue())
        self.assertEqual(result['adb_calls'], 0)
        self.assertEqual(result['status'], 'PREPARED_DESCRIPTION_ONLY')

    def test_missing_stale_wrong_scope_and_wrong_hash_leases_call_nothing(self):
        for changed in ({'expires_unix': 946684809}, {'issuer': 'someone'}, {'serial': 'emulator-5558'},
                        {'contact_sha256': '0'*64}, {'plan_sha256': '0'*64}, {'wrapper_sha256': '0'*64}):
            with self.subTest(changed=changed):
                runtime = FakeRuntime(); runtime.lease.update(changed)
                result = execute(runtime)
                self.assertEqual(runtime.calls, [])
                self.assertFalse(result['down_possible'])
                self.assertEqual(runtime.monitors_started, 0)

    def test_duplicate_lease_key_is_not_authorization(self):
        runtime = FakeRuntime()
        raw = json.dumps(runtime.lease).encode().replace(b'"max_contacts": 1', b'"max_contacts": 1, "max_contacts": 1')
        with self.assertRaises(w.Refused):
            w.parse_lease(raw, runtime.wall(), contact_hash=w.digest(runtime.contact_raw),
                          wrapper_hash=w.digest(runtime.wrapper_raw), plan_hash=w.digest(runtime.plan_raw))

    def test_reader_setting_or_registered_client_is_not_bound_service(self):
        runtime = FakeRuntime()
        runtime.responses['reader-before'] = READER.replace(b'Bound services:{Service[label=Screen reader, feedbackType[FEEDBACK_SPOKEN, FEEDBACK_HAPTIC], capabilities=3, eventTypes=TYPE_VIEW_CLICKED, notificationTimeout=100, requestA11yBtn=false]}', b'Bound services:{}')
        result = execute(runtime)
        self.assertEqual(result['error'], 'READER_PROOF_NO_UNIQUE_BOUND_RECORD')
        self.assertEqual(len(runtime.calls), 3)
        self.assertEqual(runtime.frame_names, [])

    def test_wrong_caps_selected_pressure_or_rotation_fail_before_monitor(self):
        for label, original, replacement in [('caps', b'002f  : value 0', b'002f  : value 1'),
                                              ('caps', b'003a  : value 0', b'003a  : value 64'),
                                              ('display', b'orientation=0', b'orientation=1')]:
            runtime = FakeRuntime(); runtime.responses[label] = runtime.responses[label].replace(original, replacement)
            result = execute(runtime)
            self.assertEqual(runtime.frame_names, [])
            self.assertEqual(runtime.monitors_started, 0)
            self.assertEqual(result['initial_tracking_activity'], 'UNKNOWN')

    def test_no_header_readiness_means_no_contact(self):
        runtime = FakeRuntime(); runtime.ready = False
        result = execute(runtime)
        self.assertEqual(runtime.frame_names, [])
        self.assertEqual(result['error'], 'MONITOR_NOT_READY')

    def test_success_is_one_contact_and_exact_raw_capture_not_reader_pass(self):
        runtime = FakeRuntime(); result = execute(runtime)
        self.assertEqual(runtime.frame_names, ['down'] + [f'move-{i}' for i in range(1, 9)] + ['up'])
        self.assertTrue(result['up_acknowledged'])
        self.assertFalse(result['reader_qualified'])
        self.assertFalse(result['kernel_close_proven'])
        self.assertFalse(result['global_absence_of_contacts_proven'])
        self.assertTrue(all(timeout == 1 for argv, timeout in runtime.calls if argv in runtime.names))
        self.assertEqual(runtime.persisted[0][-1].stdout, b'OK\r\n')
        self.assertIsNone(runtime.persisted[0][-1].start)  # no callback before cleanup subprocess.

    def test_zero_exit_ko_after_down_still_gets_one_up(self):
        runtime = FakeRuntime(); original = runtime.run
        def run(argv, timeout):
            result = original(argv, timeout)
            return subprocess.CompletedProcess(argv, 0, b'KO: no\r\n', b'') if runtime.names.get(argv) == 'down' else result
        runtime.run = run
        result = execute(runtime)
        self.assertEqual(runtime.frame_names, ['down', 'up'])
        self.assertTrue(result['up_attempted'])

    def test_callback_failure_after_down_or_up_does_not_block_or_repeat_close(self):
        for failure_frame in ('down', 'up'):
            runtime = FakeRuntime()
            def record(record):
                if record.label == failure_frame: raise OSError('synthetic log failed')
            result = execute(runtime, record)
            self.assertEqual(runtime.frame_names.count('up'), 1)
            self.assertTrue(result['up_attempted'])
            self.assertTrue(result['up_acknowledged'])

    def test_term_and_int_after_down_prevent_more_move_commands(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            runtime = FakeRuntime()
            runtime.hook = lambda name: runtime.stop.receive(signum) if name == 'down' else None
            result = execute(runtime)
            self.assertEqual(runtime.frame_names, ['down', 'up'])
            self.assertEqual(result['signals_received'], [signum])

    def test_final_sleep_exception_still_records_release_wait_error_and_up(self):
        runtime = FakeRuntime()
        def sleep(seconds):
            if len(runtime.frame_names) == 9: raise KeyboardInterrupt()
        runtime.sleep_hook = sleep
        result = execute(runtime)
        self.assertEqual(runtime.frame_names.count('up'), 1)
        self.assertEqual(result['contact']['release_wait_error'], 'KeyboardInterrupt')
        self.assertTrue(result['up_acknowledged'])

    def test_failed_up_ack_is_not_retried(self):
        runtime = FakeRuntime(); original = runtime.run
        def run(argv, timeout):
            result = original(argv, timeout)
            if runtime.names.get(argv) == 'up': raise subprocess.TimeoutExpired(argv, 1, b'partial\r\n', b'error\r\n')
            return result
        runtime.run = run
        result = execute(runtime)
        self.assertEqual(runtime.frame_names.count('up'), 1)
        self.assertTrue(result['up_attempted']); self.assertFalse(result['up_acknowledged'])
        self.assertEqual(runtime.persisted[0][-1].stdout, b'partial\r\n')

    def test_emergency_up_is_only_for_no_prior_up_attempt(self):
        runtime = FakeRuntime(); original_load = w.load_contact
        def broken_load(raw):
            module, sequence, commands = original_load(raw)
            def transmit(send, clock, sleep):
                send(sequence[0]); raise RuntimeError('synthetic omitted module finally')
            module.transmit = transmit
            return module, sequence, commands
        with patch.object(w, 'load_contact', broken_load): result = execute(runtime)
        self.assertEqual(runtime.frame_names, ['down', 'up'])
        self.assertTrue(result['up_acknowledged'])

    def test_nonterminal_monitor_keeps_lease_without_forced_stop(self):
        runtime = FakeRuntime(); runtime.terminal = False
        result = execute(runtime)
        self.assertEqual(result['status'], 'MONITOR_NON_TERMINAL_KEEP_LEASE')
        self.assertFalse(result['monitor']['wrapper_forced_stop_attempted'])
        self.assertFalse(result['lease_return_allowed'])

    def test_same_lease_cannot_start_a_second_contact(self):
        runtime = FakeRuntime(); execute(runtime); previous = len(runtime.calls)
        result = execute(runtime)
        self.assertEqual(len(runtime.calls), previous)
        self.assertEqual(result['error'], 'FileExistsError')

    def test_passive_monitor_reads_fake_headers_and_never_kills(self):
        runtime = FakeRuntime()
        class Process:
            pid = -1
            returncode = None
            stdout = io.BytesIO(HEADERS)
            stderr = io.BytesIO(b'')
            def poll(self): return self.returncode
            def wait(self, timeout): self.returncode = 124
        with patch.object(w.subprocess, 'Popen', return_value=Process()) as popen:
            monitor = w.PassiveMonitor(runtime)
            for thread in monitor.threads: thread.join()
            self.assertTrue(monitor.await_ready(w.StopState()))
            result = monitor.finish()
        self.assertEqual(popen.call_args.args[0], w.MONITOR_ARGV)
        self.assertTrue(result['terminal_observed'])
        self.assertEqual(result['stdout'], HEADERS)
        self.assertFalse(result['wrapper_forced_stop_attempted'])


    def test_cancellation_inside_health_callback_blocks_the_next_dispatch(self):
        for signum in (signal.SIGINT, signal.SIGTERM):
            runtime = FakeRuntime()
            original_monitor = runtime.start_monitor
            def start_monitor():
                monitor = original_monitor()
                def healthy():
                    if runtime.frame_names:
                        runtime.stop.receive(signum)
                    return True
                monitor.healthy = healthy
                return monitor
            runtime.start_monitor = start_monitor
            result = execute(runtime)
            self.assertEqual(runtime.frame_names, ['down', 'up'])
            self.assertTrue(result['up_acknowledged'])

    def test_copied_lease_json_is_still_a_single_authorization(self):
        runtime = FakeRuntime(); execute(runtime)
        first_count = len(runtime.calls)
        result = w.execute(lease_path='lease-copy', plan_path='plan', output='other-fake-output', runtime=runtime)
        self.assertEqual(len(runtime.calls), first_count)
        self.assertEqual(runtime.frame_names.count('down'), 1)
        self.assertEqual(result['error'], 'FileExistsError')

    def test_unreservable_output_fails_before_any_adb_and_never_persists_into_it(self):
        for reason in (FileExistsError, FileNotFoundError):
            runtime = FakeRuntime()
            def reserve(output): raise reason()
            runtime.reserve = reserve
            result = execute(runtime)
            self.assertEqual(runtime.calls, [])
            self.assertEqual(runtime.monitors_started, 0)
            self.assertIsNone(runtime.persisted)
            self.assertFalse(result['output_reserved'])

    def test_late_persistence_failure_still_follows_the_single_up_attempt(self):
        runtime = FakeRuntime()
        def persist(*args): raise OSError('synthetic late write failure')
        runtime.persist = persist
        result = execute(runtime)
        self.assertEqual(runtime.frame_names.count('up'), 1)
        self.assertTrue(result['up_attempted'])
        self.assertTrue(result['up_acknowledged'])
        self.assertFalse(result['raw_artifacts_complete'])

    def test_lost_readiness_blocks_moves_and_still_allows_release(self):
        runtime = FakeRuntime()
        def start_monitor():
            monitor = FakeMonitor(runtime)
            def healthy(): return not runtime.frame_names
            monitor.healthy = healthy
            return monitor
        runtime.start_monitor = start_monitor
        result = execute(runtime)
        self.assertEqual(runtime.frame_names, ['down', 'up'])
        self.assertTrue(result['up_acknowledged'])

    def test_fallible_monitor_allocation_precedes_popen(self):
        runtime = FakeRuntime()
        with patch.object(w.threading, 'Lock', side_effect=RuntimeError('synthetic lock failure')):
            with patch.object(w.subprocess, 'Popen') as popen:
                with self.assertRaises(RuntimeError): w.PassiveMonitor(runtime)
                popen.assert_not_called()

    def test_thread_start_failure_retains_the_owned_monitor_for_finish(self):
        runtime = FakeRuntime()
        class Process:
            pid = -1
            returncode = None
            stdout = io.BytesIO(HEADERS)
            stderr = io.BytesIO(b'')
            def poll(self): return self.returncode
            def wait(self, timeout): self.returncode = 124
        with patch.object(w.subprocess, 'Popen', return_value=Process()):
            with patch.object(w.threading.Thread, 'start', side_effect=RuntimeError('synthetic start failure')):
                monitor = w.PassiveMonitor(runtime)
                self.assertFalse(monitor.await_ready(w.StopState()))
                result = monitor.finish()
        self.assertTrue(result['terminal_observed'])
        self.assertFalse(result['capture_complete'])
        self.assertFalse(result['wrapper_forced_stop_attempted'])


    def test_other_current_user_cannot_borrow_a_user_zero_block(self):
        runtime = FakeRuntime()
        runtime.responses['reader-before'] = READER.replace(b'currentUserId=0', b'currentUserId=10')
        result = execute(runtime)
        self.assertEqual(result['error'], 'CURRENT_ANDROID_USER_NOT_ZERO_OR_UNKNOWN')
        self.assertEqual(len(runtime.calls), 1)
        self.assertEqual(runtime.frame_names, [])
        self.assertEqual(runtime.monitors_started, 0)

    def test_pinned_helper_is_checked_before_import_and_before_any_adb(self):
        runtime = FakeRuntime()
        runtime.helper_raw = b"raise AssertionError('this must never execute')"
        result = execute(runtime)
        self.assertEqual(result['error'], 'BINDING_HELPER_NOT_PINNED_OR_CHANGED')
        self.assertEqual(runtime.calls, [])
        self.assertEqual(runtime.frame_names, [])

    def test_missing_ambiguous_or_wrong_system_pid_never_reaches_down(self):
        for value in (b'0\n', b'777 778\n', b'not-a-pid\n', b'778\n'):
            runtime = FakeRuntime()
            runtime.responses['system-server-pid'] = value
            result = execute(runtime)
            self.assertEqual(runtime.frame_names, [])
            self.assertEqual(runtime.monitors_started, 0)

    def test_binding_getters_are_exact_filtered_server_side_commands(self):
        runtime = FakeRuntime()
        result = execute(runtime)
        commands = [argv for argv, timeout in runtime.calls]
        self.assertIn((w.ADB, '-s', w.SERIAL, 'shell', 'pidof', 'system_server'), commands)
        activity = (w.ADB, '-s', w.SERIAL, 'shell', 'dumpsys', 'activity', 'services', w.TALKBACK_COMPONENT)
        self.assertIn(activity, commands)
        self.assertNotIn('-c', activity)
        self.assertNotIn('-p', activity)
        proof = result['preflight']['reader_after']
        self.assertEqual(proof['ams']['system_server_pid'], 777)
        self.assertIsNone(proof['accessibility']['component_in_bound_dump'])
        self.assertIn('CORRELATION_ONLY', proof['identity_join'])
        self.assertFalse(result['reader_qualified'])


if __name__ == '__main__': unittest.main()
