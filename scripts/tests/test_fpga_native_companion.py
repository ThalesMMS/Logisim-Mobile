import base64
import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fpga_native_companion", ROOT / "scripts/fpga_native_companion.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

TOOL_PATHS = {name: shutil.which(name) or f"/opt/homebrew/bin/{name}" for name in fixture.TOOLS}
TOOLS_AVAILABLE = all(Path(path).is_file() for path in TOOL_PATHS.values())


def request(host, method, path, body=None, token=None):
    connection = http.client.HTTPConnection("127.0.0.1", host.server.server_port, timeout=10)
    headers = {"Authorization": "Bearer " + (host.token if token is None else token)}
    if body is not None:
        body = json.dumps(body)
        headers["Content-Type"] = "application/json"
    try:
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        return response.status, json.loads(response.read())
    finally:
        connection.close()


def upload(source):
    content = source.encode()
    return {"operation": "fpga-synthesize", "inputs": [
        {"name": "design.v", "sha256": hashlib.sha256(content).hexdigest(),
         "base64": base64.b64encode(content).decode()}],
        "parameters": {"flow": "ice40", "part": "hx1k", "package": "tq144",
                       "top": "user_top", "sources": ["design.v"], "language": "verilog", "goal": "synthesis"}}


def wait_job(host, job_id):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        status, job = request(host, "GET", "/v1/jobs/" + job_id)
        if status != 200:
            raise AssertionError((status, job))
        if job["state"] not in ("queued", "running"):
            return job
        time.sleep(0.01)
    raise AssertionError("The actual FPGA tool job did not finish within the test deadline")


class FpgaNativeHostConfigurationTests(unittest.TestCase):
    def test_programmer_and_unknown_tools_are_rejected_before_starting_a_host(self):
        for name in ("openFPGALoader", "shell"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "accepts only"):
                fixture.FpgaNativeCompanion({name: "/bin/true"})

    def test_a_missing_configured_tool_is_an_explicit_preflight_failure(self):
        with self.assertRaisesRegex(ValueError, "requires an executable yosys"):
            fixture.FpgaNativeCompanion({"yosys": "/does-not-exist/yosys"})


@unittest.skipUnless(TOOLS_AVAILABLE, "Real Yosys, nextpnr-ice40 and icepack are required")
class FpgaNativeHostHttpTests(unittest.TestCase):
    def setUp(self):
        self.host = fixture.FpgaNativeCompanion(TOOL_PATHS)
        self.addCleanup(self.host.close)

    def test_only_synthesis_is_advertised_and_close_stops_the_owned_thread(self):
        status, capabilities = request(self.host, "GET", "/v1/capabilities")
        self.assertEqual(200, status)
        self.assertEqual(["fpga-synthesize"], [operation["name"] for operation in capabilities["operations"]])
        self.assertEqual("127.0.0.1", self.host.server.server_address[0])
        self.assertEqual(f"http://127.0.0.1:{self.host.server.server_port}", self.host.endpoint)
        self.assertFalse(self.host.audit()["programmingRegistered"])
        self.host.close()
        self.host.close()
        self.assertFalse(self.host.thread.is_alive())

    def test_real_yosys_synthesis_retains_hashes_stages_and_separate_hardware_flags(self):
        source = "module user_top(input a, input b, output y); assign y=a&b; endmodule\n"
        body = upload(source)
        status, plan = request(self.host, "POST", "/v1/plans", body)
        self.assertEqual(200, status)
        self.assertFalse(plan["requiresConfirmation"])
        status, queued = request(self.host, "POST", "/v1/jobs", body)
        self.assertEqual(202, status)
        job = wait_job(self.host, queued["jobId"])
        self.assertEqual("succeeded", job["state"], job.get("error"))
        artifact = job["result"]["artifacts"][0]
        netlist = json.loads(base64.b64decode(artifact["base64"]))
        self.assertEqual({"a", "b", "y"}, set(netlist["modules"]["user_top"]["ports"]))
        self.assertEqual({"SB_LUT4"}, {cell["type"] for cell in netlist["modules"]["user_top"]["cells"].values()})

        audit = self.host.audit()
        self.assertEqual(["plan", "submit"], [item["action"] for item in audit["requests"]])
        event = audit["events"][0]
        self.assertEqual({"bytes": len(source.encode()), "sha256": hashlib.sha256(source.encode()).hexdigest(),
                          "text": source}, event["inputs"]["design.v"])
        self.assertEqual(["synthesize"], [log["text"] for log in event["logs"] if log["stream"] == "stage"])
        self.assertEqual("RETURNED", event["status"])
        self.assertEqual(0, event["logBytesDropped"])
        self.assertTrue(event["result"]["synthesized"])
        for flag in ("placedAndRouted", "bitstreamGenerated", "programmed", "hardwareVerified"):
            self.assertFalse(event["result"][flag])
        self.assertEqual([{key: artifact[key] for key in ("name", "size", "sha256")}], event["result"]["artifacts"])
        self.assertEqual("succeeded", audit["jobs"][0]["state"])
        self.assertNotIn("base64", json.dumps(audit))
        self.assertNotIn(self.host.token, json.dumps(audit))
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "artifacts"
            files = self.host.export_artifacts(directory)
            self.assertEqual(1, len(files))
            self.assertEqual(artifact["sha256"], files[0]["sha256"])
            self.assertEqual(base64.b64decode(artifact["base64"]), (directory / files[0]["path"]).read_bytes())
            with self.assertRaises(FileExistsError):
                self.host.export_artifacts(directory)

    def test_real_compiler_failure_and_cancel_of_a_terminal_job_remain_distinct(self):
        body = upload("module user_top( syntax error ! endmodule")
        status, queued = request(self.host, "POST", "/v1/jobs", body)
        self.assertEqual(202, status)
        failed = wait_job(self.host, queued["jobId"])
        self.assertEqual("failed", failed["state"])
        self.assertEqual("fpga-stage-failed", failed["error"]["code"])
        self.assertEqual("synthesize", failed["error"]["details"]["stage"])
        self.assertIn("syntax error", failed["error"]["details"]["stderr"])
        status, cancelled = request(self.host, "DELETE", "/v1/jobs/" + queued["jobId"])
        self.assertEqual(200, status)
        self.assertEqual("failed", cancelled["state"])
        audit = self.host.audit()
        self.assertEqual("RAISED", audit["events"][0]["status"])
        self.assertEqual("fpga-stage-failed", audit["events"][0]["error"]["code"])
        self.assertEqual("cancel", audit["requests"][-1]["action"])
        self.assertEqual("failed", audit["requests"][-1]["observedState"])
        self.assertEqual("failed", audit["jobs"][0]["state"])

    def test_malformed_requests_keep_production_typed_errors_and_redact_ephemeral_token(self):
        for body in ([], {"operation": "fpga-synthesize", "inputs": None},
                     {"operation": self.host.token, "parameters": {self.host.token: self.host.token}}):
            with self.subTest(body_type=type(body).__name__):
                status, error = request(self.host, "POST", "/v1/jobs", body)
                self.assertEqual(400, status)
                self.assertIn(error["error"]["code"], ("invalid-request", "invalid-inputs", "unknown-operation"))
        audit = self.host.audit()
        self.assertNotIn(self.host.token, json.dumps(audit))
        self.assertIn("[REDACTED_EPHEMERAL_TOKEN]", json.dumps(audit))


if __name__ == "__main__":
    unittest.main()
