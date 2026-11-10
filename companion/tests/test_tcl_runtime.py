import base64
import hashlib
from pathlib import Path
import shutil
import tempfile
import threading
import time
import unittest

from companion import host_isolation
from companion.api import JobContext, JobFailure, OperationRegistry
from companion.server import CompanionService, Configuration, default_registry
from companion.tcl_runtime import register, run_console, run_components


@unittest.skipUnless(shutil.which("tclsh"), "tclsh is required for the execution fixtures")
class TclRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.inputs = {}
        self.cancelled = threading.Event()

    def tearDown(self):
        self.directory.cleanup()

    def context(self, source, others=None, timeout=10):
        for name, text in {"script.tcl": source, **(others or {})}.items():
            path = self.root / name
            path.write_bytes(text.encode("utf-8") if isinstance(text, str) else text)
            self.inputs[name] = path
        return JobContext(self.root, self.inputs, {"tclsh": shutil.which("tclsh")}, self.cancelled, timeout)

    def test_explicit_console_preserves_source_and_unicode_result(self):
        source = 'puts "olá"; expr {6 * 7}'
        result = run_console(self.context(source), {})
        self.assertEqual(result["result"], "42")
        self.assertEqual(result["stdout"], "olá\n")
        self.assertEqual(result["sourceSha256"], hashlib.sha256(source.encode()).hexdigest())
        self.assertEqual(self.inputs["script.tcl"].read_text(), source)
        self.assertEqual(result["mode"], "safe-tcl-v1")

    def test_only_explicitly_supplied_source_files_are_available(self):
        result = run_console(self.context('source shared.tcl; twice 9',
            {"shared.tcl": 'proc twice {value} {expr {$value * 2}}'}), {})
        self.assertEqual(result["result"], "18")
        result = run_console(self.context('source shared.data; twice 8',
            {"shared.data": 'proc twice {value} {expr {$value * 2}}'}), {})
        self.assertEqual(result["result"], "16")
        with self.assertRaises(JobFailure) as caught:
            run_console(self.context('source /etc/passwd'), {})
        self.assertEqual(caught.exception.code, "tcl-script-error")
        self.assertIn("not supplied", str(caught.exception))

    def test_console_no_newline_and_stderr_cannot_corrupt_the_result_protocol(self):
        result = run_console(self.context('puts -nonewline "olá"; puts stderr "erro"; puts "mundo"; expr 42'), {})
        self.assertEqual(result["result"], "42")
        self.assertEqual(result["stdout"], "olámundo\n")
        self.assertEqual(result["stderr"], "erro\n")

    def test_safe_interpreter_rejects_external_execution_io_and_timing(self):
        for source in ("exec echo unsafe", "open /etc/passwd", "socket localhost 80", "clock seconds", "after 1",
                       "::tcl::clock::seconds", "::tcl::clock::getenv HOME", "time {expr 1}", "interp create -safe child"):
            with self.subTest(source=source), self.assertRaises(JobFailure) as caught:
                run_console(self.context(source), {})
            self.assertEqual(caught.exception.code, "tcl-script-error")
        self.assertFalse((self.root / "unexpected").exists())

    def test_repeated_jobs_have_a_fixed_initial_random_seed(self):
        first = run_console(self.context('expr {rand()}'), {})
        second = run_console(self.context('expr {rand()}'), {})
        self.assertEqual(first["result"], second["result"])

    def test_console_and_component_syntax_errors_are_reported(self):
        with self.assertRaises(JobFailure) as caught:
            run_console(self.context("set a {"), {})
        self.assertEqual(caught.exception.code, "tcl-script-error")
        with self.assertRaises(JobFailure) as caught:
            run_components(self.context("set a 1"), {"ports": self.ports(), "frames": [self.frame("0")]})
        self.assertEqual(caught.exception.code, "tcl-script-error")
        self.assertIn("callback", str(caught.exception))

    @staticmethod
    def ports():
        return [{"name": "sysclk_i", "direction": "input", "width": 1},
                {"name": "val_i", "direction": "input", "width": 4},
                {"name": "cpt_o", "direction": "output", "width": 4}]

    @staticmethod
    def frame(clock, value="0000"):
        return {"sysclk_i": clock, "val_i": value}

    def test_component_replays_state_and_both_clock_edges_with_examine_hold(self):
        source = '''
set count 0
proc enableLogisim {enabled} {set ::enabled $enabled}
proc logisimExamine {} {force cpt_o 1111}
proc logisimForce {} {
    incr ::count
    set bits ""
    for {set i 3} {$i >= 0} {incr i -1} {append bits [expr {($::count >> $i) & 1}]}
    force cpt_o $bits
    if {$::count == 2} {run}
}
'''
        result = run_components(self.context(source), {"ports": self.ports(), "frames": [
            self.frame("0"), self.frame("0", "1111"), self.frame("1"), self.frame("0")]})
        self.assertEqual([step["forced"] for step in result["steps"]], [True, False, True, True])
        self.assertEqual([step["outputs"]["cpt_o"] for step in result["steps"]], ["0001", "0001", "0010", "0011"])
        self.assertEqual([step["requestedTick"] for step in result["steps"]], [False, False, True, False])
        self.assertEqual(result["outputs"], {"cpt_o": "0011"})
        self.assertEqual(result["delay"], 1)

    def test_component_examine_force_aliases_and_unknown_padding_match_desktop(self):
        source = '''
proc enableLogisim {enabled} {}
proc logisimExamine {} {}
proc logisimForce {} {force -deposit /component/cpt_o [examine -radix /component/val_i]}
'''
        result = run_components(self.context(source), {"ports": self.ports(), "frames": [self.frame("0", "E0U1")]})
        self.assertEqual(result["outputs"], {"cpt_o": "?0?1"})
        for raw, expected in [("1", "???1"), ("110101", "0101"), ("E0X1", "?0?1")]:
            source = f'proc enableLogisim {{x}} {{}}; proc logisimExamine {{}} {{}}; proc logisimForce {{}} {{force cpt_o {raw}}}'
            result = run_components(self.context(source), {"ports": self.ports(), "frames": [self.frame("0")]})
            self.assertEqual(result["outputs"]["cpt_o"], expected)

    def test_multiple_or_initialization_tick_requests_fail_explicitly(self):
        for body in ('proc logisimForce {} {run; run}', 'run; proc logisimForce {} {}'):
            source = 'proc enableLogisim {x} {}; proc logisimExamine {} {}; ' + body
            with self.subTest(body=body), self.assertRaises(JobFailure) as caught:
                run_components(self.context(source), {"ports": self.ports(), "frames": [self.frame("0")]})
            self.assertEqual(caught.exception.code, "tcl-script-error")
            self.assertIn("run", str(caught.exception))

    def test_missing_clock_invalid_frames_and_incompatible_parameters_do_not_launch(self):
        context = self.context("error should-not-run")
        with self.assertRaises(JobFailure) as caught:
            run_components(context, {"ports": self.ports()[1:], "frames": []})
        self.assertEqual(caught.exception.code, "missing-clock")
        with self.assertRaises(JobFailure) as caught:
            run_components(context, {"ports": self.ports(), "frames": [{"sysclk_i": "0"}]})
        self.assertEqual(caught.exception.code, "invalid-frames")
        with self.assertRaises(JobFailure) as caught:
            run_console(context, {"command": "/bin/sh"})
        self.assertEqual(caught.exception.code, "invalid-parameters")
        self.assertEqual(context._command_sequence, 0)

    def test_replay_bounds_account_for_interface_size_before_starting_tcl(self):
        context = self.context("error should-not-run")
        ordinary = self.ports()
        wide = [{"name": "sysclk_i", "direction": "input", "width": 1}] + [
            {"name": f"bus_{index}_" + "a" * 100, "direction": "inout", "width": 64}
            for index in range(511)]
        for ports, count, limit in [(ordinary, 1025, 1024), (wide, 257, 256)]:
            with self.subTest(count=count), self.assertRaises(JobFailure) as caught:
                run_components(context, {"ports": ports, "frames": [{}] * count})
            self.assertEqual(caught.exception.code, "invalid-frames")
            self.assertIn(f"1 to {limit}", str(caught.exception))
        self.assertEqual(context._command_sequence, 0)

    def test_source_encoding_output_limits_and_command_limits_are_enforced(self):
        with self.assertRaises(JobFailure) as caught:
            run_console(self.context(b"\xff"), {})
        self.assertEqual(caught.exception.code, "invalid-source")
        with self.assertRaises(JobFailure) as caught:
            run_console(self.context('puts [string repeat x 70000]'), {})
        self.assertIn("output limit", str(caught.exception))
        with self.assertRaises(JobFailure) as caught:
            run_console(self.context('while {1} {}'), {"commandLimit": 100})
        self.assertIn("limit", str(caught.exception))

    def test_cancellation_terminates_actual_tcl_execution(self):
        context = self.context('while {1} {incr count}')
        timer = threading.Timer(0.005, self.cancelled.set)
        timer.start()
        try:
            with self.assertRaises(JobFailure) as caught:
                run_console(context, {"commandLimit": 1_000_000})
            self.assertEqual(caught.exception.code, "cancelled")
        finally:
            timer.cancel()

    def test_operation_registration_has_no_script_execution(self):
        registry = OperationRegistry()
        register(registry)
        tools = {"tcl-run": "tclsh", "tcl-step": "tclsh", "tcl-timed-run": "tclsh-isolated",
                 "tcl-timed-step": "tclsh-isolated", "tcl-tk-run": "bwrap", "tcl-tk-live": "bwrap"}
        self.assertEqual(set(registry.operations), set(tools))
        for name, operation in registry.operations.items():
            self.assertEqual(operation.isolated, name not in ("tcl-run", "tcl-step"))
            self.assertEqual(operation.requires_confirmation, operation.isolated)
            self.assertEqual(operation.required_tools, (tools[name],))
        self.assertEqual(list(self.root.iterdir()), [])


@unittest.skipUnless(host_isolation.available() and Path("/usr/bin/tclsh").is_file(),
                     "macOS sandbox and system Tcl required")
class TimedTclRuntimeTest(unittest.TestCase):
    def setUp(self):
        token = "synthetic-token-for-timed-tcl-fixtures"
        self.service = CompanionService(Configuration(
            token, {"tcl-timed-run", "tcl-timed-step"}, tool_paths={"tclsh-isolated": "/usr/bin/tclsh"}),
            default_registry())
        self.addCleanup(self.service.close)

    @staticmethod
    def request(operation, source, **parameters):
        data = source.encode()
        return {"operation": operation, "parameters": parameters, "inputs": [
            {"name": "script.tcl", "base64": base64.b64encode(data).decode(), "sha256": hashlib.sha256(data).hexdigest()}
        ]}

    def submit(self, request):
        plan = self.service.plan(request)
        self.assertEqual(plan["osSandbox"], "macos-seatbelt-v1")
        self.assertTrue(plan["requiresConfirmation"])
        return self.service.submit({**request, "planToken": plan["planToken"]})["jobId"]

    def wait_for_job(self, job_id):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            status = self.service.poll(job_id)
            if status["state"] not in ("queued", "running"):
                return status
            time.sleep(0.025)
        self.fail("Tcl worker did not finish")

    def test_plan_binds_source_and_allows_timers_without_host_io(self):
        request = self.request("tcl-timed-run", """
after 10 {set answer 42}
vwait answer
list $answer [catch {open /etc/passwd}] [catch {socket localhost 80}] [catch {exec /usr/bin/true}]
""")
        with self.assertRaises(JobFailure) as caught:
            self.service.submit(request)
        self.assertEqual(caught.exception.code, "confirmation-required")
        token = self.service.plan(request)["planToken"]
        with self.assertRaises(JobFailure) as caught:
            self.service.submit({**self.request("tcl-timed-run", "set answer 0"), "planToken": token})
        self.assertEqual(caught.exception.code, "confirmation-required")
        job = self.wait_for_job(self.submit(request))
        self.assertEqual(job["state"], "succeeded", job)
        self.assertEqual(job["result"]["mode"], "timed-tcl-v1")
        self.assertEqual(job["result"]["result"], "42 1 1 1")

    def test_component_callback_can_wait_for_timer(self):
        job = self.wait_for_job(self.submit(self.request("tcl-timed-step", """
proc enableLogisim {enabled} {}
proc logisimExamine {} {}
proc logisimForce {} {
    after 10 {set ::ready 1010}
    vwait ::ready
    force cpt_o $::ready
}
""", ports=TclRuntimeTest.ports(), frames=[TclRuntimeTest.frame("0")])))
        self.assertEqual(job["state"], "succeeded", job)
        self.assertEqual(job["result"]["mode"], "timed-replay-v1")
        self.assertEqual(job["result"]["outputs"], {"cpt_o": "1010"})

    def test_event_wait_obeys_host_timeout(self):
        request = self.request("tcl-timed-run", "vwait never", timeoutMillis=200)
        job = self.wait_for_job(self.submit(request))
        self.assertEqual(job["state"], "failed", job)
        self.assertEqual(job["error"]["code"], "timeout")


if __name__ == "__main__":
    unittest.main()
