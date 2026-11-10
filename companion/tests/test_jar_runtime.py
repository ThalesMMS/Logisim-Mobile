import base64
import hashlib
import io
import os
from pathlib import Path
import threading
import time
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from companion.api import JobFailure, Operation, OperationRegistry
from companion import host_isolation
from companion.jar_runtime import JarLiveStream, _encoded, _records
from companion.server import CompanionService, Configuration, default_registry


def uploaded(name, data):
    return {"name": name, "sha256": hashlib.sha256(data).hexdigest(),
            "base64": base64.b64encode(data).decode()}


class JarTraceProtocolTest(unittest.TestCase):
    def records(self, *, samples=True, driver=None):
        def record(kind, *fields):
            return "\t".join(["LOGISIM_JAR_V1", kind, *(_encoded(str(value)) for value in fields)])

        lines = [
            record("port", "Y", "output", 1),
            record("instance", "/200,180/40,50", "/200,180", "inner", "Counter", 40, 50, 10, 35, 30, 30),
            record("terminal", "/200,180/40,50", 0, 10, 50, "input", 1, "false"),
            record("terminal", "/200,180/40,50", 1, 40, 50, "output", 4, "true"),
            record("output", 0, "Y", "1"),
            record("terminal-value", 0, "/200,180/40,50", 0, "1"),
        ]
        if samples:
            lines.append(record("terminal-value", 0, "/200,180/40,50", 1, "10xE"))
        if driver is not None:
            lines.append(record("driver-value", 0, "/200,180/40,50", 1, driver))
        lines.append(record("complete", "demo.Library", "Library", "main", 1))
        return "\n".join(lines)

    def test_trace_preserves_hierarchy_and_terminal_order(self):
        result = _records(self.records(), "demo.Library", 1, instance_trace=True)["instanceTrace"]
        instance, = result["instances"]
        self.assertEqual([[200, 180]], instance["path"])
        self.assertEqual([10, 35, 30, 30], instance["bounds"])
        self.assertEqual({"position": [40, 50], "direction": "output", "width": 4, "exclusive": True},
                         instance["terminals"][1])
        self.assertEqual([{"/200,180/40,50": ["1", "10?E"]}], result["frames"])

    def test_trace_distinguishes_released_driver_and_resolved_signal(self):
        reply = _records(self.records(driver="Z"), "demo.Library", 1, instance_trace=True, driver_trace=True)
        self.assertEqual([{"/200,180/40,50": [None, "Z"]}], reply["instanceTrace"]["drives"])
        self.assertEqual([{"/200,180/40,50": ["1", "10?E"]}], reply["instanceTrace"]["frames"])
        with self.assertRaises(JobFailure) as missing:
            _records(self.records(), "demo.Library", 1, instance_trace=True, driver_trace=True)
        self.assertEqual("invalid-jar-result", missing.exception.code)

    def test_trace_rejects_partial_samples_and_unsolicited_records(self):
        for enabled, source in ((True, self.records(samples=False)), (False, self.records())):
            with self.subTest(instance_trace=enabled), self.assertRaises(JobFailure) as failure:
                _records(source, "demo.Library", 1, instance_trace=enabled)
            self.assertEqual("invalid-jar-result", failure.exception.code)


class JarLiveProtocolTest(unittest.TestCase):
    def setUp(self):
        class Context:
            def check_cancelled(self):
                pass

            def publish_live(self, result):
                self.latest = result

        self.context = Context()
        self.stream = JarLiveStream(self.context, "demo.Library", 3, False)
        self.record("port", "CLK", "input", 1)
        self.record("port", "Q", "output", 1)
        self.record("ready", "demo.Library", "Library", "main")

    def record(self, kind, *values):
        line = "\t".join(["LOGISIM_JAR_V1", kind, *(_encoded(str(value)) for value in values)])
        self.stream._record(line)

    def test_sequence_conflict_retry_and_revision_receipt(self):
        first = {"sequence": 0, "revision": 7, "inputs": {"CLK": "1"}}
        receipt = self.stream.accept(first)
        self.assertEqual({"sequence": 0, "accepted": True}, receipt)
        self.assertEqual(receipt, self.stream.accept(dict(first)))
        with self.assertRaises(JobFailure) as mutation:
            self.stream.accept({**first, "inputs": {"CLK": "0"}})
        self.assertEqual("live-input-conflict", mutation.exception.code)
        with self.assertRaises(JobFailure) as reordered:
            self.stream.accept({"sequence": 1, "revision": 8, "inputs": {"CLK": "0"}})
        self.assertEqual("live-input-order", reordered.exception.code)

        self.stream.sent, self.stream.pending = 0, None
        self.record("output", 0, "Q", "1")
        self.record("frame-complete", 0)
        self.assertEqual({"sequence": 0, "revision": 7, "outputs": {"Q": "1"}}, {
            key: value for key, value in self.context.latest.items() if key != "ports"
        })
        with self.assertRaises(JobFailure) as stale_revision:
            self.stream.accept({"sequence": 1, "revision": 7, "inputs": {"CLK": "0"}})
        self.assertEqual("invalid-live-input", stale_revision.exception.code)
        with self.assertRaises(ValueError):
            self.record("frame-complete", 0)

    def test_width_and_live_frame_budget_are_enforced_before_enqueue(self):
        for request in ({"sequence": 0, "revision": 0, "inputs": {"CLK": "01"}},
                        {"sequence": 0, "revision": 0, "inputs": {"other": "0"}},
                        {"sequence": 3, "revision": 0, "inputs": {"CLK": "0"}},
                        {"sequence": 0, "finish": True}):
            with self.subTest(request=request), self.assertRaises(JobFailure) as failure:
                self.stream.accept(request)
            self.assertEqual("invalid-live-input", failure.exception.code)
            self.assertIsNone(self.stream.pending)


class JarLiveJobGateTest(unittest.TestCase):
    def open_job(self, *, lease_seconds=2):
        ready = threading.Event()
        registry = OperationRegistry()

        def execute(context, _):
            context.bind_live_input(lambda value: {"accepted": value == {"step": 1}})
            context.publish_live({"ready": True})
            ready.set()
            while True:
                context.check_cancelled()
                time.sleep(.01)

        registry.register(Operation("test-live", execute, requires_confirmation=True, isolated=True, accepts_input=True))
        registry.register(Operation("plain", lambda context, _: {}))
        service = CompanionService(Configuration("synthetic-local-live-gate-test-token", {"test-live", "plain"}), registry)
        self.addCleanup(service.close)
        request = {"operation": "test-live"}
        lease = host_isolation.Lease
        with patch("companion.server.host_isolation.available", return_value=True), \
             patch("companion.server.host_isolation.Lease", side_effect=lambda: lease(lease_seconds)):
            with self.assertRaises(JobFailure) as unapproved:
                service.submit(request)
            self.assertEqual("confirmation-required", unapproved.exception.code)
            plan = service.plan(request)
            job = service.submit({**request, "planToken": plan["planToken"]})
        self.assertTrue(ready.wait(timeout=1))
        self.assertEqual({"ready": True}, service.snapshot(job["jobId"])["live"])
        return service, job["jobId"]

    def test_only_active_approved_jobs_accept_input_and_cancel_hides_receipts(self):
        service, job_id = self.open_job()
        self.assertEqual({"accepted": True}, service.input(job_id, {"step": 1}))
        with self.assertRaises(JobFailure) as wrong_route:
            service.send_tk_input(job_id, {"sequence": 1, "type": "tap", "x": 0, "y": 0})
        self.assertEqual("tk-input-unavailable", wrong_route.exception.code)
        plain = service.submit({"operation": "plain"})
        for other_id in ("a" * 32, plain["jobId"]):
            with self.assertRaises(JobFailure) as unavailable:
                service.input(other_id, {"step": 1})
            self.assertEqual("live-input-unavailable", unavailable.exception.code)
        self.assertNotIn("live", service.cancel(job_id))
        with self.assertRaises(JobFailure) as cancelled:
            service.input(job_id, {"step": 1})
        self.assertIn(cancelled.exception.code, {"cancelled", "live-input-unavailable"})

    def test_expired_lease_hides_receipt_before_worker_teardown(self):
        service, job_id = self.open_job()
        # The worker must not finish before the poll observes the expired lease.
        with service._lock, patch.object(service._jobs[job_id]["isolationLease"], "expired", return_value=True):
            job = service.poll(job_id)
            self.assertEqual("running", job["state"])
            self.assertNotIn("live", job)

    def test_input_traffic_does_not_renew_the_worker_lease(self):
        service, job_id = self.open_job(lease_seconds=.2)
        self.assertEqual({"accepted": True}, service.input(job_id, {"step": 1}))
        deadline = time.monotonic() + 1
        while service.snapshot(job_id)["state"] == "running" and time.monotonic() < deadline:
            time.sleep(.01)
        job = service.snapshot(job_id)
        self.assertEqual(("cancelled", "lease-expired"), (job["state"], job["error"]["code"]))
        self.assertNotIn("live", job)


@unittest.skipUnless(
    host_isolation.available() and all(os.environ.get(name) for name in (
        "LOGISIM_COMPANION_EVOLUTION_JAR", "LOGISIM_TEST_JAR_JAVA",
    )),
    "Set the Evolution runtime and dedicated JDK java to exercise the real sandbox.",
)
class JarRuntimeIntegrationTest(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("LOGISIM_TEST_GRAY_JAR"), "Set the pinned Gray library JAR.")
    def test_gray_counters_keep_independent_state_and_project_bytes(self):
        project = Path(__file__).with_name("fixtures").joinpath("gray-two-counters.circ").read_bytes()
        library = Path(os.environ["LOGISIM_TEST_GRAY_JAR"]).read_bytes()
        config = Configuration("synthetic-authentication-for-jar-runtime", {"jar-run"},
                               tool_paths={"jar-java": os.environ["LOGISIM_TEST_JAR_JAVA"]})
        host = CompanionService(config, default_registry())
        self.addCleanup(host.close)
        request = {
            "operation": "jar-run",
            "inputs": [uploaded("project.circ", project), uploaded("library.jar", library)],
            "parameters": {"libraryClass": "com.cburch.gray.Components", "instanceTrace": True,
                           "frames": [dict(zip("AB", bits)) for bits in ("00", "10", "00", "10", "01", "00", "01")]},
        }
        with self.assertRaises(JobFailure) as rejected:
            host.submit(request)
        self.assertEqual("confirmation-required", rejected.exception.code)
        job = self.wait_for_result(host, self.submit(host, request))
        self.assertEqual("succeeded", job["state"], job["error"])
        result = job["result"]
        self.assertEqual(("Components", "com.cburch.gray.Components", hashlib.sha256(library).hexdigest()),
                         (result["libraryId"], result["libraryClass"], result["librarySha256"]))
        self.assertEqual({("A", "input", 1), ("B", "input", 1), ("X", "output", 4), ("Y", "output", 4)},
                         {(port["name"], port["direction"], port["width"]) for port in result["ports"]})
        self.assertEqual([dict(zip("XY", values)) for values in (
            ("0000", "0000"), ("0001", "0000"), ("0001", "0000"), ("0011", "0000"),
            ("0011", "0001"), ("0011", "0001"), ("0011", "0011"),
        )], result["frames"])
        self.assertEqual(project, base64.b64decode(result["project"]["base64"]))
        trace = result["instanceTrace"]
        self.assertEqual([("/200,100", "Gray Counter", [170, 85, 30, 30]),
                          ("/200,180", "Gray Counter", [170, 165, 30, 30])],
                         [(item["id"], item["factory"], item["bounds"]) for item in trace["instances"]])
        for item, y in zip(trace["instances"], (100, 180)):
            self.assertEqual([{"position": [170, y], "direction": "input", "width": 1, "exclusive": False},
                              {"position": [200, y], "direction": "output", "width": 4, "exclusive": True}],
                             item["terminals"])
        self.assertEqual([
            {"/200,100": [a, expected["X"]], "/200,180": [b, expected["Y"]]}
            for (a, b), expected in zip(("00", "10", "00", "10", "01", "00", "01"), result["frames"])
        ], trace["frames"])

        # This class is also bundled in the desktop engine. It must not satisfy a
        # request whose explicitly approved upload has no implementation of it.
        empty_archive = io.BytesIO()
        with ZipFile(empty_archive, "w"):
            pass
        wrong_library = {**request, "inputs": [
            uploaded("project.circ", project), uploaded("library.jar", empty_archive.getvalue()),
        ]}
        failed = self.wait_for_result(host, self.submit(host, wrong_library))
        self.assertEqual(("failed", "incompatible-jar"), (failed["state"], failed["error"]["code"]))
        self.assertIsNone(failed["result"])

        active = self.submit(host, request)
        command_stdout = host._root / active["jobId"] / "command-1.stdout"
        deadline = time.monotonic() + 10
        while not command_stdout.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
            host.poll(active["jobId"])
        self.assertTrue(command_stdout.exists())
        cancelled = self.wait_for_result(host, host.cancel(active["jobId"]))
        self.assertEqual(("cancelled", "cancelled"), (cancelled["state"], cancelled["error"]["code"]))
        self.assertIsNone(cancelled["result"])

    @unittest.skipUnless(os.environ.get("LOGISIM_TEST_DRIVE_JAR"), "Build DriveLibrary.java for the driver proof.")
    def test_live_driver_trace_separates_contention_release_unknown_and_error(self):
        project = Path(__file__).with_name("fixtures").joinpath("jar-drive-contention.circ").read_bytes()
        library = Path(os.environ["LOGISIM_TEST_DRIVE_JAR"]).read_bytes()
        config = Configuration("synthetic-authentication-for-jar-driver", {"jar-live"},
                               tool_paths={"jar-java": os.environ["LOGISIM_TEST_JAR_JAVA"]})
        host = CompanionService(config, default_registry())
        self.addCleanup(host.close)
        request = {"operation": "jar-live", "inputs": [uploaded("project.circ", project), uploaded("library.jar", library)],
                   "parameters": {"libraryClass": "dev.logisim.fixture.DriveLibrary", "maxFrames": 4,
                                  "instanceTrace": True, "driverTrace": True}}
        job_id = self.submit(host, request)["jobId"]

        def wait_live(predicate):
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                job = host.poll(job_id)
                live = job.get("live", {})
                if predicate(live):
                    return live
                self.assertIn(job["state"], {"queued", "running"}, job["error"])
                time.sleep(.025)
            self.fail("The isolated driver did not publish a live response.")

        wait_live(lambda live: "ports" in live)
        expected = (("0", "1", "E"), ("Z", "1", "1"), ("E", "Z", "E"), ("?", "Z", "?"))
        for sequence, (bits, values) in enumerate(zip(("0111", "0011", "E110", "?110"), expected)):
            item = {"sequence": sequence, "revision": sequence + 10, "inputs": dict(zip(("A", "EA", "B", "EB"), bits))}
            self.assertEqual({"sequence": sequence, "accepted": True}, host.input(job_id, item))
            live = wait_live(lambda result: result.get("sequence") == sequence)
            self.assertEqual(sequence + 10, live["revision"])
            trace = live["instanceTrace"]
            self.assertEqual(["output", "inout"], [item["terminals"][2]["direction"] for item in trace["instances"]])
            frame, = trace["drives"]
            resolved, = trace["frames"]
            self.assertEqual({"/200,100": [None, None, values[0]], "/200,180": [None, None, values[1]]}, frame)
            self.assertEqual([values[2], values[2], values[2]],
                             [resolved["/200,100"][2], resolved["/200,180"][2], live["outputs"]["Y"]])
        host.input(job_id, {"sequence": 4, "finish": True})
        job = self.wait_for_result(host, {"jobId": job_id, "state": "running"})
        self.assertEqual("succeeded", job["state"], job["error"])
        self.assertEqual(project, base64.b64decode(job["result"]["project"]["base64"]))

    def submit(self, host, request):
        plan = host.plan(request)
        self.assertEqual("macos-seatbelt-v1", plan["osSandbox"])
        return host.submit({**request, "planToken": plan["planToken"]})

    def wait_for_result(self, host, job):
        deadline = time.monotonic() + 40
        while job["state"] in {"queued", "running"} and time.monotonic() < deadline:
            time.sleep(0.05)
            job = host.poll(job["jobId"])
        return job
