import base64
from hashlib import sha256
from http.client import HTTPConnection
from io import BytesIO
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from companion import fpga_sof
from companion.api import JobFailure
from companion.fpga_host_config import load_program_targets
from companion.fpga_sof import MAX_SOF_BYTES, verify_part
from companion.server import Configuration, default_registry, make_http_server


SIZE = 7_449_702
DEVICE = "5CSEBA6U23I7"
TOKEN = "large-sof-fixture-token-0000000000000000"


def sof_bytes(part=DEVICE, size=SIZE):
    value = part.encode("ascii") + b"\0"
    header = b"SOF\0" + struct.pack("<IIHI", 0, 2, 2, len(value)) + value
    data_size = size - len(header) - 6
    return header + struct.pack("<HI", 17, data_size) + b"\xa5" * data_size


class LargeQuartusSofTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source_sof = self.root / "large.sof"
        self.pause_programmer = self.root / "pause-programmer"
        self.programmer_started = self.root / "programmer-started"
        self.payload = sof_bytes()
        self.source_sof.write_bytes(self.payload)
        compiler = self.tool("quartus-sh-fixture", "from pathlib import Path\n"
                             "import shutil, sys\n"
                             "assert sys.argv[1:] == ['--flow', 'compile', 'build']\n"
                             f"assert 'DEVICE {DEVICE}' in Path('build.qsf').read_text()\n"
                             "Path('quartus-output').mkdir()\n"
                             f"shutil.copyfile({str(self.source_sof)!r}, 'quartus-output/build.sof')\n")
        programmer = self.tool("quartus-pgm-fixture", "from pathlib import Path\n"
                               "import hashlib, sys, time\n"
                               "assert sys.argv[1:6] == ['-c', 'USB-Blaster [1-1]', '-m', 'jtag', '-o']\n"
                               "assert sys.argv[6] == 'P;inputs/design.sof@1'\n"
                               "print(hashlib.sha256(Path('inputs/design.sof').read_bytes()).hexdigest())\n"
                               f"if Path({str(self.pause_programmer)!r}).exists():\n"
                               f"    Path({str(self.programmer_started)!r}).touch()\n"
                               "    time.sleep(20)\n")
        targets_file = self.root / "fpga-targets.json"
        targets_file.write_text(json.dumps({"version": 1, "targets": {"fixture": {
            "board": "TERASIC_DE10_NANO", "transport": "quartus", "cable": "USB-Blaster [1-1]",
            "chainPosition": 0, "sofPart": DEVICE}}}))
        targets = load_program_targets(targets_file)
        self.target = targets["fixture"]
        registry = default_registry(program_targets=targets)
        configuration = Configuration(TOKEN, {"fpga-quartus-build", "fpga-program"},
                                      tool_paths={"quartus_sh": str(compiler), "quartus_pgm": str(programmer)})
        self.server = make_http_server(configuration, registry)
        self.addCleanup(self.server.service.close)
        self.addCleanup(self.server.server_close)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join, 3)
        self.addCleanup(self.server.shutdown)

    def tool(self, name, script):
        tool = self.root / name
        tool.write_text(f"#!{sys.executable}\n" + script)
        tool.chmod(0o755)
        return tool

    def exchange(self, method, path, body=None, headers=None, *, authenticated=True):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=4)
        headers = dict(headers or {})
        if authenticated:
            headers["Authorization"] = f"Bearer {TOKEN}"
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            return response.status, response.getheader("Content-Type"), response.read()
        finally:
            connection.close()

    def api(self, method, path, data=None, status=200):
        code, mime, body = self.exchange(method, path, json.dumps(data).encode() if data is not None else None,
                                         {"Content-Type": "application/json"})
        self.assertEqual(status, code, body[:400])
        self.assertEqual("application/json; charset=utf-8", mime)
        return json.loads(body)

    def terminal(self, job):
        path = "/v1/jobs/" + job["jobId"]
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            state = self.api("GET", path)
            if state["state"] in ("succeeded", "failed", "cancelled"):
                return state
            time.sleep(.01)
        self.fail("Companion job did not terminate")

    def build_request(self, binary=True):
        sources = {
            "source-0000.v": b"module logisim_fpga_top(input fpga_p0_b0, output fpga_p1_b0); assign fpga_p1_b0=fpga_p0_b0; endmodule\n",
            "pins.qsf": b'set_location_assignment PIN_V11 -to fpga_p0_b0\nset_location_assignment PIN_W15 -to fpga_p1_b0\n'
                        b'set_global_assignment -name RESERVE_ALL_UNUSED_PINS "AS INPUT TRI-STATED"\n',
            "timing.sdc": b"derive_clock_uncertainty\n",
        }
        parameters = {"language": "verilog", "sources": ["source-0000.v"], "target": "5cseba6u23i7"}
        if binary:
            parameters["binarySof"] = True
        return {"operation": "fpga-quartus-build", "parameters": parameters, "inputs": [
            {"name": name, "sha256": sha256(data).hexdigest(), "base64": base64.b64encode(data).decode("ascii")}
            for name, data in sources.items()]}

    def download_round_trip(self):
        legacy = self.build_request(binary=False)
        approval = self.api("POST", "/v1/plans", legacy)
        rejected = self.terminal(self.api("POST", "/v1/jobs", legacy | {"planToken": approval["planToken"]}, status=202))
        self.assertEqual("output-limit", rejected["error"]["code"])
        request = self.build_request()
        approval = self.api("POST", "/v1/plans", request)
        job = self.terminal(self.api("POST", "/v1/jobs", request | {"planToken": approval["planToken"]}, status=202))
        self.assertEqual("succeeded", job["state"], job)
        result = job["result"]
        self.assertEqual(DEVICE, result["part"])
        self.assertEqual([{"name": "design.sof", "size": SIZE, "sha256": sha256(self.payload).hexdigest(),
                           "transport": "binary"}], result["artifacts"])
        path = f"/v1/jobs/{job['jobId']}/artifacts/design.sof"
        self.assertEqual(401, self.exchange("GET", path, authenticated=False)[0])
        self.assertEqual(404, self.exchange("GET", path.replace("design.sof", "build.sof"))[0])
        status, mime, body = self.exchange("GET", path)
        self.assertEqual((200, "application/octet-stream"), (status, mime))
        self.assertEqual(self.payload, body)
        return body

    def upload_plan(self, payload):
        digest = sha256(payload).hexdigest()
        parameters = {"target": "fixture", "profile": self.target.fingerprint, "bitstream": "design.sof", "flash": False}
        descriptor = {"name": "design.sof", "size": len(payload), "sha256": digest}
        request = {"operation": "fpga-program", "inputs": [descriptor], "parameters": parameters}
        plan = self.api("POST", "/v1/fpga-sof/plans", request)
        self.assertEqual([descriptor], plan["inputs"])
        self.assertEqual(parameters, plan["parameters"])
        self.assertTrue(plan["requiresConfirmation"])
        return {"Content-Type": "application/octet-stream", "X-Logisim-Sof-Name": "design.sof",
                "X-Logisim-Sof-Sha256": digest, "X-Logisim-Fpga-Target": "fixture",
                "X-Logisim-Fpga-Profile": self.target.fingerprint, "X-Logisim-Plan-Token": plan["planToken"],
                "X-Logisim-Request-Id": "sof-fixture"}

    def upload(self, payload, headers, status=202):
        code, _, body = self.exchange("POST", "/v1/fpga-sof/jobs", payload, headers)
        self.assertEqual(status, code, body[:400])
        return json.loads(body)

    def test_binary_build_download_and_confirmed_program_upload(self):
        capabilities = self.api("GET", "/v1/capabilities")
        self.assertEqual(MAX_SOF_BYTES, capabilities["limits"]["quartusSofBytes"])
        self.assertEqual(6 * 1024 * 1024, capabilities["limits"]["requestBytes"])
        self.assertEqual(4 * 1024 * 1024, capabilities["limits"]["inputBytes"])
        programmer = next(item for item in capabilities["operations"] if item["name"] == "fpga-program")
        self.assertEqual(DEVICE, programmer["programTargetProfiles"]["fixture"]["sofPart"])
        payload = self.download_round_trip()
        headers = self.upload_plan(payload)
        changed = payload[:-1] + b"\0"
        self.assertEqual("hash-mismatch", self.upload(changed, headers, status=400)["error"]["code"])
        job = self.terminal(self.upload(payload, headers))
        self.assertEqual("succeeded", job["state"], job)
        self.assertFalse(job["result"]["hardwareVerified"])
        self.assertIn(sha256(payload).hexdigest(), "".join(item["text"] for item in job["logs"]))
        self.assertEqual(job["jobId"], self.upload(payload, headers)["jobId"])
        self.assertEqual("hash-mismatch", self.upload(changed, headers, status=400)["error"]["code"])

    def test_rejects_wrong_part_and_bounds_before_tool(self):
        wrong = sof_bytes(part="5CSEBA6U23I9")
        headers = self.upload_plan(wrong)
        self.assertEqual("fpga-part-mismatch", self.upload(wrong, headers, status=400)["error"]["code"])
        code, _, reply = self.exchange("POST", "/v1/fpga-sof/jobs", headers=headers |
                                       {"Content-Length": str(MAX_SOF_BYTES + 1)})
        self.assertEqual(400, code)
        self.assertEqual("input-limit", json.loads(reply)["error"]["code"])
        code, _, reply = self.exchange("POST", "/v1/fpga-sof/jobs", headers=headers |
                                       {"Content-Length": str(len(wrong)), "X-Logisim-Plan-Token": "missing"})
        self.assertEqual(400, code)
        self.assertEqual("confirmation-required", json.loads(reply)["error"]["code"])

    def test_expired_upload_approval_keeps_retained_sof(self):
        service = self.server.service
        service.configuration.max_jobs = 1
        request = self.build_request()
        approval = self.api("POST", "/v1/plans", request)
        build = self.terminal(self.api("POST", "/v1/jobs", request | {"planToken": approval["planToken"]}, status=202))
        self.assertEqual("succeeded", build["state"], build)
        artifact_path = f"/v1/jobs/{build['jobId']}/artifacts/design.sof"
        headers = self.upload_plan(self.payload)
        token = headers["X-Logisim-Plan-Token"]
        verify_upload = fpga_sof.verify_upload

        def expire_during_upload(data, descriptor, part):
            verify_upload(data, descriptor, part)
            with service._lock:
                digest, _ = service._plans[token]
                service._plans[token] = (digest, time.monotonic() - 1)

        with patch.object(fpga_sof, "verify_upload", expire_during_upload):
            rejected = self.upload(self.payload, headers, status=400)
        self.assertEqual("confirmation-required", rejected["error"]["code"])
        status, mime, retained = self.exchange("GET", artifact_path)
        self.assertEqual((200, "application/octet-stream"), (status, mime))
        self.assertEqual(sha256(self.payload).hexdigest(), sha256(retained).hexdigest())

        accepted = self.terminal(self.upload(self.payload, self.upload_plan(self.payload)))
        self.assertEqual("succeeded", accepted["state"], accepted)
        status, _, reply = self.exchange("GET", artifact_path)
        self.assertEqual((400, "invalid-artifact"), (status, json.loads(reply)["error"]["code"]))

    def test_cancelled_program_retry_keeps_the_original_terminal_job(self):
        self.pause_programmer.touch()
        headers = self.upload_plan(self.payload)
        submitted = self.upload(self.payload, headers)
        path = "/v1/jobs/" + submitted["jobId"]
        deadline = time.monotonic() + 3
        while not self.programmer_started.exists() and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertTrue(self.programmer_started.exists(), self.api("GET", path))
        self.api("DELETE", path)
        cancelled = self.terminal(submitted)
        self.assertEqual("cancelled", cancelled["state"])
        self.assertIsNone(cancelled["result"])
        self.assertEqual(cancelled["jobId"], self.upload(self.payload, headers)["jobId"])
        self.assertEqual("cancelled", self.api("GET", path)["state"])

    @unittest.skipUnless(os.environ.get("LOGISIM_TEST_OFFICIAL_DE10_SOF"), "Supply the retained official DE10-Nano fixture.")
    def test_official_de10_nano_round_trip_without_hardware(self):
        self.payload = Path(os.environ["LOGISIM_TEST_OFFICIAL_DE10_SOF"]).read_bytes()
        self.assertEqual(SIZE, len(self.payload))
        self.assertEqual("b46b1a8c8e81f848d31785cc723bce959176d360150c1cacb756800bcf18c81c",
                         sha256(self.payload).hexdigest())
        verify_part(BytesIO(self.payload), SIZE, DEVICE)
        self.source_sof.write_bytes(self.payload)
        payload = self.download_round_trip()
        job = self.terminal(self.upload(payload, self.upload_plan(payload)))
        self.assertEqual("succeeded", job["state"], job)
        self.assertFalse(job["result"]["hardwareVerified"])
