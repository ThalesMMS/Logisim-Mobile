import io
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from companion import host_isolation, tk_worker
from companion.api import JobFailure, Operation, OperationRegistry
from companion.server import CompanionService, Configuration
from companion.tk_session import TkSession


NONCE = "a" * 32


class TkSessionTest(unittest.TestCase):
    def test_two_inputs_keep_the_same_inode_and_retries_are_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            session = TkSession(directory / "input")
            output = directory / "stdout"
            session.attach_output(output)
            session.configure(NONCE, lambda fields: fields[2], "source-digest")
            output.write_bytes(f"{NONCE}|frame|0|first\n".encode())
            self.assertEqual(session.latest(1024), {"sequence": 0, "windowPreview": "first"})
            tap = {"sequence": 1, "type": "tap", "x": 639, "y": 479}
            key = {"sequence": 2, "type": "key", "key": "a"}
            inode = session.path.stat().st_ino
            with session.path.open("rb") as worker:
                self.assertEqual(session.offer(tap), 1)
                self.assertEqual(session.offer(tap), 1)
                self.assertEqual(tk_worker.read_input(worker, b"", 1), (tap, b""))
                self.assertEqual(tk_worker.read_input(worker, b"", 2), (None, b""))
                with self.assertRaises(JobFailure):
                    session.offer(key)
                with output.open("ab") as stream:
                    stream.write(f"{NONCE}|frame|1|sec".encode())
                    stream.flush()
                    self.assertEqual(session.latest(1024)["sequence"], 0)
                    stream.write(b"ond\n")
                self.assertEqual(session.latest(1024), {"sequence": 1, "windowPreview": "second"})
                self.assertEqual(session.offer(key), 2)
                self.assertEqual(tk_worker.read_input(worker, b"", 2), (key, b""))
                self.assertEqual(session.path.stat().st_ino, inode)
                self.assertLess(session.path.stat().st_size, 256)

    def test_worker_waits_for_newline_and_rejects_untyped_input(self):
        first = io.BytesIO(b'{"sequence":1,"type":"key",')
        event, partial = tk_worker.read_input(first, b"", 1)
        self.assertIsNone(event)
        event, remaining = tk_worker.read_input(io.BytesIO(b'"key":"Return"}\n'), partial, 1)
        self.assertEqual(event, {"sequence": 1, "type": "key", "key": "Return"})
        self.assertEqual(remaining, b"")
        with self.assertRaises(ValueError):
            tk_worker.read_input(io.BytesIO(b"x" * 128), b"", 1)
        with self.assertRaises(ValueError):
            tk_worker.read_input(io.BytesIO(b'{"sequence":1,"type":"key","key":"/tmp/file"}\n'), b"", 1)

    def test_service_hides_frames_and_rejects_input_after_lease_or_cancel(self):
        started = threading.Event()
        release = threading.Event()

        def run(context, parameters):
            session = context.tk_session
            session.configure(NONCE, lambda fields: fields[2], "source-digest")
            output = context.output_path("worker-output")
            output.write_text(f"{NONCE}|frame|0|first\n")
            session.attach_output(output)
            started.set()
            release.wait(2)
            context.check_cancelled()
            return {}

        registry = OperationRegistry()
        registry.register(Operation("tcl-tk-live", run, requires_confirmation=True, isolated=True,
                                    isolation_profile=host_isolation.TK_PROFILE))
        service = CompanionService(Configuration("synthetic-tk-session-token-for-tests", {"tcl-tk-live"}), registry)
        self.addCleanup(service.close)
        self.addCleanup(release.set)
        payload = {"operation": "tcl-tk-live"}
        tap = {"sequence": 1, "type": "tap", "x": 0, "y": 0}
        with patch.object(host_isolation, "available_for", return_value=True):
            with self.assertRaises(JobFailure) as missing:
                service.submit(payload)
            self.assertEqual(missing.exception.code, "confirmation-required")
            plan = service.plan(payload)
            job = service.submit({**payload, "planToken": plan["planToken"]})["jobId"]
        self.assertTrue(started.wait(1))
        self.assertEqual(service.poll(job)["result"]["frame"]["sequence"], 0)
        self.assertEqual(service.send_tk_input(job, tap), {"sequence": 1})
        with self.assertRaises(JobFailure) as wrong_route:
            service.input(job, tap)
        self.assertEqual(wrong_route.exception.code, "live-input-unavailable")
        with patch.object(service._jobs[job]["isolationLease"], "expired", return_value=True):
            self.assertIsNone(service.poll(job)["result"])
            with self.assertRaises(JobFailure) as expired:
                service.send_tk_input(job, tap)
            self.assertEqual(expired.exception.code, "tk-input-unavailable")
        self.assertIsNone(service.cancel(job)["result"])
        self.assertIsNone(service.poll(job)["result"])
        with self.assertRaises(JobFailure) as cancelled:
            service.send_tk_input(job, tap)
        self.assertEqual(cancelled.exception.code, "tk-input-unavailable")
