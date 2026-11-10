from pathlib import Path
import errno
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

from companion.api import JobContext, JobFailure, Operation, OperationRegistry, safe_name


class HandlerApiTest(unittest.TestCase):
    def context(self, path, **kwargs):
        return JobContext(Path(path), {}, {"python": sys.executable}, threading.Event(), **kwargs)

    def test_configured_command_returns_stdout_stderr_and_failure_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary)
            result = context.run([context.tool("python"), "-c", "import sys; print('value=8'); print('diagnostic',file=sys.stderr); sys.exit(3)"])
            self.assertEqual(3, result.returncode)
            self.assertEqual("value=8\n", result.stdout)
            self.assertEqual("diagnostic\n", result.stderr)

    def test_timeout_terminates_a_real_child_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary)
            with self.assertRaises(JobFailure) as failure:
                context.run([context.tool("python"), "-c", "import time; time.sleep(60)"], timeout_seconds=0.05)
            self.assertEqual("timeout", failure.exception.code)

    def test_cancellation_interrupts_a_running_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary)
            timer = threading.Timer(0.05, context.cancelled.set)
            timer.start()
            try:
                with self.assertRaises(JobFailure) as failure:
                    context.run([context.tool("python"), "-c", "import time; time.sleep(60)"])
                self.assertEqual("cancelled", failure.exception.code)
            finally:
                timer.cancel()

    def test_completion_cleans_up_children_after_the_leader_exits(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary)
            program = "import os,time; pid=os.fork(); time.sleep(.5) if pid==0 else None; open('escaped-child','w').write('alive') if pid==0 else None"
            self.assertEqual(0, context.run([context.tool("python"), "-c", program]).returncode)
            time.sleep(.6)
            self.assertFalse((Path(temporary) / "escaped-child").exists())

    @unittest.skipUnless(sys.platform == "linux", "Requires the Linux proc filesystem")
    def test_linux_cleanup_has_stopped_child_before_returning(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary)
            program = """
import os, signal, time
from pathlib import Path
pid_file = Path('child.pid')
if os.fork() == 0:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    ready = Path('child.ready')
    ready.write_text(str(os.getpid()))
    ready.replace(pid_file)
    time.sleep(60)
else:
    while not pid_file.exists():
        time.sleep(.01)
"""
            self.assertEqual(0, context.run([context.tool("python"), "-c", program]).returncode)
            child = int((Path(temporary) / "child.pid").read_text())
            try:
                stat = Path(f"/proc/{child}/stat").read_text()
            except FileNotFoundError:
                return
            self.assertIn(stat.rsplit(") ", 1)[1][0], {"X", "Z"})

    def test_cleanup_waits_for_confirmed_group_absence_after_eperm(self):
        process = subprocess.Popen([sys.executable, "-c", "pass"], start_new_session=True)
        process.wait()
        remaining_zombie_probes = 2

        def signal_group(pgid, sig):
            nonlocal remaining_zombie_probes
            if sig == signal.SIGTERM:
                return
            if sig == 0:
                if remaining_zombie_probes == 0:
                    raise ProcessLookupError(errno.ESRCH, "Group was reaped")
                remaining_zombie_probes -= 1
            raise PermissionError(errno.EPERM, "Group contains an exiting zombie")

        with patch("companion.api.os.killpg", side_effect=signal_group):
            JobContext._terminate(process)
        self.assertEqual(0, process.returncode)

    def test_cleanup_reaps_leader_that_exits_as_group_rejects_sigterm(self):
        process = Mock(spec=subprocess.Popen, pid=1)
        process.poll.return_value = None
        process.wait.return_value = 0
        with patch("companion.api.os.killpg", side_effect=[
            PermissionError(errno.EPERM, "Group is exiting"),
            ProcessLookupError(errno.ESRCH, "Group was reaped"),
        ]) as kill_group:
            JobContext._terminate(process)
        process.wait.assert_called_once()
        self.assertEqual([(1, signal.SIGTERM), (1, 0)], [call.args for call in kill_group.call_args_list])

    def test_cleanup_rejects_groups_that_remain_after_eperm(self):
        process = subprocess.Popen([sys.executable, "-c", "pass"], start_new_session=True)
        process.wait()
        for probe_denied in (False, True):
            with self.subTest(probe_denied=probe_denied):
                def signal_group(pgid, sig):
                    if sig != 0 or probe_denied:
                        raise PermissionError(errno.EPERM, "Group remains unsignalable")

                with patch("companion.api.os.killpg", side_effect=signal_group):
                    with self.assertRaises(JobFailure) as failure:
                        JobContext._terminate(process)
                self.assertEqual("termination-failed", failure.exception.code)

    def test_cleanup_rejects_eperm_while_its_leader_is_alive(self):
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=True)
        try:
            with patch("companion.api.os.killpg", side_effect=PermissionError(errno.EPERM, "Leader remains alive")):
                with self.assertRaises(JobFailure) as failure:
                    JobContext._terminate(process)
            self.assertEqual("termination-failed", failure.exception.code)
            self.assertIsNone(process.poll())
        finally:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()

    def test_linux_cleanup_waits_until_only_dead_group_members_remain(self):
        process = Mock(spec=subprocess.Popen, pid=123)
        active = subprocess.CompletedProcess([], 0, "  123 D\n  123 Z\n  456 R\n", "")
        dead = subprocess.CompletedProcess([], 0, "  123 Z+\n  456 R\n", "")
        with patch("companion.api.sys.platform", "linux"), patch("companion.api.os.killpg") as kill_group, \
             patch("companion.api.subprocess.run", side_effect=[active, dead]) as inspect:
            JobContext._terminate(process)
        self.assertEqual([(123, signal.SIGTERM), (123, signal.SIGKILL), (123, 0), (123, 0)],
                         [call.args for call in kill_group.call_args_list])
        self.assertEqual(2, inspect.call_count)

    def test_linux_cleanup_rejects_an_unverifiable_process_group(self):
        with patch("companion.api.os.killpg"), \
             patch("companion.api.subprocess.run", side_effect=subprocess.TimeoutExpired("ps", 0.5)):
            with self.assertRaises(JobFailure) as failure:
                JobContext._wait_for_linux_group_exit(123)
        self.assertEqual("termination-failed", failure.exception.code)

    def test_command_output_is_bounded(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary, max_output_bytes=1024)
            with self.assertRaises(JobFailure) as failure:
                context.run([context.tool("python"), "-c", "print('x'*4096)"])
            self.assertEqual("output-limit", failure.exception.code)

    def test_command_output_is_checked_after_group_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary, max_output_bytes=1024)
            terminate = context._terminate

            def write_during_cleanup(process):
                # Model buffered output written by a surviving descendant after the leader exits.
                (Path(temporary) / "command-1.stdout").write_text("x" * 4096)
                terminate(process)

            with patch.object(context, "_terminate", side_effect=write_during_cleanup) as cleanup:
                with self.assertRaises(JobFailure) as failure:
                    context.run([context.tool("python"), "-c", "pass"])
            self.assertEqual("output-limit", failure.exception.code)
            cleanup.assert_called_once()

    def test_process_group_memory_is_supervised(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary, memory_limit_bytes=32 * 1024 * 1024)
            with self.assertRaises(JobFailure) as failure:
                context.run([context.tool("python"), "-c", "import time; data=bytes(128*1024*1024); data=bytearray(data); time.sleep(60)"])
            self.assertEqual("memory-limit", failure.exception.code)

    def test_failed_resource_sampling_is_typed_and_terminates_the_tool(self):
        with tempfile.TemporaryDirectory() as temporary:
            context = self.context(temporary)
            processes = []
            original = subprocess.Popen
            def capture(*args, **kwargs):
                process = original(*args, **kwargs)
                processes.append(process)
                return process
            with patch("companion.api.subprocess.Popen", side_effect=capture), \
                 patch("companion.api.subprocess.run", side_effect=subprocess.TimeoutExpired("ps", 2)):
                with self.assertRaises(JobFailure) as failure:
                    context.run([context.tool("python"), "-c", "import time; time.sleep(60)"])
            self.assertEqual("resource-limits-unavailable", failure.exception.code)
            self.assertEqual(1, len(processes))
            self.assertIsNotNone(processes[0].poll())

    def test_unconfigured_commands_and_external_artifacts_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, tempfile.TemporaryDirectory() as external:
            context = self.context(temporary)
            with self.assertRaises(JobFailure) as failure:
                context.run(["/bin/sh", "-c", "exit 0"])
            self.assertEqual("unconfigured-tool", failure.exception.code)
            path = Path(external) / "outside.txt"
            path.write_text("outside")
            with self.assertRaises(JobFailure) as failure:
                context.artifact(path)
            self.assertEqual("invalid-artifact", failure.exception.code)

    def test_registry_rejects_ambiguous_names_and_runs_cleanup(self):
        registry = OperationRegistry()
        operation = Operation("verify-inputs", lambda context, parameters: {})
        registry.register(operation)
        with self.assertRaises(ValueError):
            registry.register(operation)
        closed = []
        registry.on_shutdown(lambda: closed.append("closed"))
        registry.close()
        self.assertEqual(["closed"], closed)
        for name in ["../escape", ".", "a/b", "a\0b"]:
            with self.assertRaises(JobFailure):
                safe_name(name)


if __name__ == "__main__":
    unittest.main()
