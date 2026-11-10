import base64
import http.client
import json
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from companion.api import Operation, OperationRegistry
from companion.server import CompanionService, Configuration, default_registry, load_token, make_http_server, verify_inputs

TOKEN = "synthetic-test-token-not-a-production-secret"
HELLO_HASH = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"


class CompanionHttpTest(unittest.TestCase):
    def setUp(self):
        registry = OperationRegistry()
        registry.register(Operation("verify-inputs", verify_inputs))
        registry.register(Operation("disabled", lambda context, parameters: {"unexpected": True}))
        registry.register(Operation("program-test", lambda context, parameters: {"confirmed": context.user_confirmed},
                                    requires_confirmation=True))
        configuration = Configuration(TOKEN, {"verify-inputs", "program-test"})
        self.server = make_http_server(configuration, registry)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.server.service.close()
        self.thread.join()

    def request(self, method, path, body=None, token=TOKEN, origin=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        headers = {"Authorization": "Bearer " + token}
        if origin:
            headers["Origin"] = origin
        if body is not None:
            body = body if isinstance(body, str) else json.dumps(body)
            headers["Content-Type"] = "application/json"
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        status, data = response.status, json.loads(response.read())
        connection.close()
        return status, data

    def wait_job(self, job_id):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            status, job = self.request("GET", f"/v1/jobs/{job_id}")
            self.assertEqual(200, status)
            if job["state"] not in ("queued", "running"):
                return job
            time.sleep(0.01)
        self.fail("Job did not finish")

    @staticmethod
    def upload():
        return {"operation": "verify-inputs", "inputs": [
            {"name": "main.circ", "sha256": HELLO_HASH, "base64": base64.b64encode(b"hello").decode()}], "parameters": {}}

    def test_pairing_and_browser_origins_are_enforced(self):
        status, response = self.request("GET", "/v1/capabilities", token="wrong")
        self.assertEqual((401, "unauthorized"), (status, response["error"]["code"]))
        status, response = self.request("GET", "/v1/capabilities", origin="https://example.invalid")
        self.assertEqual((403, "browser-origin-denied"), (status, response["error"]["code"]))
        status, response = self.request("GET", "/v1/capabilities")
        self.assertEqual(200, status)
        self.assertEqual(1, response["protocolVersion"])
        self.assertFalse(response["limits"]["osSandbox"])

    def test_uploaded_source_is_hashed_without_execution(self):
        status, job = self.request("POST", "/v1/jobs", self.upload())
        self.assertEqual(202, status)
        result = self.wait_job(job["jobId"])
        self.assertEqual("succeeded", result["state"])
        self.assertEqual({"files": [{"name": "main.circ", "size": 5, "sha256": HELLO_HASH}]}, result["result"])

    def test_path_traversal_and_changed_hashes_are_rejected(self):
        request = self.upload()
        request["inputs"][0]["name"] = "../outside.circ"
        status, result = self.request("POST", "/v1/jobs", request)
        self.assertEqual((400, "invalid-name"), (status, result["error"]["code"]))
        request = self.upload()
        request["inputs"][0]["sha256"] = "0" * 64
        status, result = self.request("POST", "/v1/jobs", request)
        self.assertEqual((400, "hash-mismatch"), (status, result["error"]["code"]))

    def test_case_collisions_are_rejected_before_staging_on_every_host(self):
        request = self.upload()
        request["inputs"][0]["name"] = "A.v"
        request["inputs"].append(request["inputs"][0] | {"name": "a.v"})
        status, result = self.request("POST", "/v1/jobs", request)
        self.assertEqual((400, "duplicate-input"), (status, result["error"]["code"]))
        self.assertEqual({}, self.server.service._jobs)

    def test_disabled_operation_and_invalid_json_do_not_run(self):
        status, result = self.request("POST", "/v1/jobs", {"operation": "disabled"})
        self.assertEqual((400, "operation-disabled"), (status, result["error"]["code"]))
        status, result = self.request("POST", "/v1/jobs", '{"operation":"verify-inputs","operation":"disabled"}')
        self.assertEqual((400, "invalid-json"), (status, result["error"]["code"]))

    def test_request_and_decoded_input_limits_are_distinct(self):
        self.server.service.configuration.max_input_bytes = 4
        status, result = self.request("POST", "/v1/jobs", self.upload())
        self.assertEqual((400, "input-limit"), (status, result["error"]["code"]))
        self.server.service.configuration.max_request_bytes = 8
        status, result = self.request("POST", "/v1/jobs", self.upload())
        self.assertEqual((400, "request-limit"), (status, result["error"]["code"]))

    def test_jar_and_tk_inputs_use_separate_authenticated_routes(self):
        job = "a" * 32
        jar_path = f"/v1/jobs/{job}/input"
        tk_path = f"/v1/jobs/{job}/tk-input"
        jar_event = {"sequence": 1, "inputs": {"wide": "0" * 256}}
        tk_event = {"sequence": 1, "type": "tap", "x": 1, "y": 2}
        service = self.server.service
        jar_receipt = {"accepted": True, "sequence": 1}
        tk_receipt = {"sequence": 1}
        with patch.object(service, "input", return_value=jar_receipt) as jar_input, \
             patch.object(service, "send_tk_input", return_value=tk_receipt) as tk_input:
            for route in (jar_path, tk_path):
                status, result = self.request("POST", route, tk_event, token="wrong")
                self.assertEqual((401, "unauthorized"), (status, result["error"]["code"]))
            status, result = self.request("POST", tk_path, jar_event)
            self.assertEqual((400, "request-limit"), (status, result["error"]["code"]))
            self.assertEqual((202, jar_receipt), self.request("POST", jar_path, jar_event))
            self.assertEqual((200, tk_receipt), self.request("POST", tk_path, tk_event))
        jar_input.assert_called_once_with(job, jar_event)
        tk_input.assert_called_once_with(job, tk_event)

    def test_non_utf8_json_is_rejected(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request("POST", "/v1/jobs", '{"operation":"verify-inputs"}'.encode("utf-16"),
                           {"Authorization": "Bearer " + TOKEN})
        response = connection.getresponse()
        self.assertEqual(400, response.status)
        self.assertEqual("invalid-json", json.loads(response.read())["error"]["code"])
        connection.close()

    def test_overflowed_json_number_is_a_typed_error(self):
        status, result = self.request("POST", "/v1/jobs", '{"operation":"verify-inputs","parameters":{"delaySeconds":1e999}}')
        self.assertEqual((400, "invalid-json"), (status, result["error"]["code"]))

    def test_slow_headers_cannot_hold_the_dispatcher_indefinitely(self):
        self.server.service.configuration.request_timeout_seconds = 0.2
        slow = socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2)
        slow.sendall(b"GET /v1/capabilities HTTP/1.1\r\nX-Slow: ")
        def drip():
            try:
                for _ in range(30):
                    slow.sendall(b"x")
                    time.sleep(0.05)
            except OSError:
                pass
        thread = threading.Thread(target=drip, daemon=True)
        thread.start()
        try:
            time.sleep(0.35)
            started = time.monotonic()
            status, result = self.request("GET", "/v1/capabilities")
            self.assertEqual(200, status)
            self.assertEqual(1, result["protocolVersion"])
            self.assertLess(time.monotonic() - started, 0.7)
        finally:
            slow.close()
            thread.join(timeout=2)

    def test_cancellation_stops_a_job_and_reports_its_state(self):
        status, job = self.request("POST", "/v1/jobs", {"operation": "verify-inputs", "parameters": {"delaySeconds": 5}})
        self.assertEqual(202, status)
        self.request("DELETE", f"/v1/jobs/{job['jobId']}")
        result = self.wait_job(job["jobId"])
        self.assertEqual("cancelled", result["state"])
        self.assertEqual("cancelled", result["error"]["code"])

    def test_retries_do_not_repeat_a_job_or_change_its_request(self):
        request = self.upload() | {"requestId": "retry-1"}
        _, first = self.request("POST", "/v1/jobs", request)
        _, repeated = self.request("POST", "/v1/jobs", request)
        self.assertEqual(first["jobId"], repeated["jobId"])
        request["parameters"] = {"delaySeconds": 1}
        status, result = self.request("POST", "/v1/jobs", request)
        self.assertEqual((400, "request-conflict"), (status, result["error"]["code"]))

    def test_long_sessions_prune_results_without_repeating_expired_retries(self):
        self.server.service.configuration.max_jobs = 2
        request = self.upload() | {"requestId": "first-request"}
        _, first = self.request("POST", "/v1/jobs", request)
        self.wait_job(first["jobId"])
        first_directory = self.server.service._root / first["jobId"]
        self.assertTrue(first_directory.is_dir())
        for _ in range(4):
            status, job = self.request("POST", "/v1/jobs", self.upload())
            self.assertEqual(202, status)
            self.assertEqual("succeeded", self.wait_job(job["jobId"])["state"])
        self.assertEqual(2, len(self.server.service._jobs))
        self.assertFalse(first_directory.exists())
        status, result = self.request("POST", "/v1/jobs", request)
        self.assertEqual((400, "job-expired"), (status, result["error"]["code"]))
        self.assertEqual(first["jobId"], result["error"]["details"]["jobId"])
        status, result = self.request("POST", "/v1/jobs", request | {"parameters": {"changed": True}})
        self.assertEqual((400, "request-conflict"), (status, result["error"]["code"]))

    def test_retry_receipt_limit_does_not_block_non_idempotent_polls(self):
        self.server.service.configuration.max_request_receipts = 1
        _, first = self.request("POST", "/v1/jobs", self.upload() | {"requestId": "first"})
        self.wait_job(first["jobId"])
        status, result = self.request("POST", "/v1/jobs", self.upload() | {"requestId": "second"})
        self.assertEqual((400, "receipt-limit"), (status, result["error"]["code"]))
        status, job = self.request("POST", "/v1/jobs", self.upload())
        self.assertEqual(202, status)
        self.assertEqual("succeeded", self.wait_job(job["jobId"])["state"])

    def test_confirmation_is_request_bound_and_one_shot(self):
        request = {"operation": "program-test", "parameters": {"board": "synthetic"}}
        status, result = self.request("POST", "/v1/jobs", request)
        self.assertEqual((400, "confirmation-required"), (status, result["error"]["code"]))
        _, plan = self.request("POST", "/v1/plans", request)
        changed = request | {"planToken": plan["planToken"], "parameters": {"board": "another"}}
        status, result = self.request("POST", "/v1/jobs", changed)
        self.assertEqual((400, "confirmation-required"), (status, result["error"]["code"]))
        confirmed = request | {"planToken": plan["planToken"]}
        status, job = self.request("POST", "/v1/jobs", confirmed)
        self.assertEqual(202, status)
        self.assertEqual({"confirmed": True}, self.wait_job(job["jobId"])["result"])
        status, result = self.request("POST", "/v1/jobs", confirmed)
        self.assertEqual((400, "confirmation-required"), (status, result["error"]["code"]))

    def test_non_loopback_api_requires_tls_before_binding(self):
        with self.assertRaisesRegex(ValueError, "require TLS"):
            make_http_server(Configuration(TOKEN, host="0.0.0.0"), OperationRegistry())

    def test_expired_plans_cannot_authorize_a_job(self):
        self.server.service.configuration.plan_lifetime_seconds = -1
        request = {"operation": "program-test", "parameters": {}}
        _, plan = self.request("POST", "/v1/plans", request)
        status, result = self.request("POST", "/v1/jobs", request | {"planToken": plan["planToken"]})
        self.assertEqual((400, "confirmation-required"), (status, result["error"]["code"]))

    def test_one_available_tool_backend_does_not_require_other_backends(self):
        import sys
        registry = OperationRegistry()
        registry.register(Operation("alternative", lambda context, parameters: {},
                                    tool_alternatives=(("available",), ("absent",))))
        service = CompanionService(Configuration(TOKEN, {"alternative"}, tool_paths={"available": sys.executable}), registry)
        try:
            capability = service.capabilities()["operations"][0]
            self.assertTrue(capability["available"])
            self.assertEqual([{"tools": ["available"], "available": True}, {"tools": ["absent"], "available": False}],
                             capability["toolAlternatives"])
            self.assertEqual("alternative", service.submit({"operation": "alternative"})["operation"])
        finally:
            service.close()

    def test_new_pairing_tokens_are_stored_with_owner_only_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "token"
            first = load_token(path)
            self.assertGreaterEqual(len(first), 32)
            self.assertEqual(first, load_token(path))
            self.assertEqual(0, path.stat().st_mode & 0o077)

    def test_cleanup_continues_after_a_resource_callback_fails(self):
        registry = OperationRegistry()
        closed = []
        registry.on_shutdown(lambda: closed.append("released"))
        def failing_callback():
            raise RuntimeError("Synthetic cleanup failure")
        registry.on_shutdown(failing_callback)
        service = CompanionService(Configuration(TOKEN), registry)
        directory = service._root
        with self.assertRaisesRegex(RuntimeError, "Synthetic cleanup failure"):
            service.close()
        self.assertEqual(["released"], closed)
        self.assertFalse(directory.exists())
        service.close()
        self.assertEqual(["released"], closed)


class CompanionTlsTest(unittest.TestCase):
    def test_tls_requires_trust_and_serves_an_authenticated_client(self):
        openssl = shutil.which("openssl")
        if openssl is None:
            self.skipTest("The TLS fixture requires openssl")
        with tempfile.TemporaryDirectory() as temporary:
            certificate, key = Path(temporary) / "certificate.pem", Path(temporary) / "key.pem"
            subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
                            "-keyout", str(key), "-out", str(certificate)], check=True, capture_output=True)
            tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            tls.minimum_version = ssl.TLSVersion.TLSv1_2
            tls.load_cert_chain(certificate, key)
            server = make_http_server(Configuration(TOKEN), OperationRegistry(), tls)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                untrusted = http.client.HTTPSConnection("127.0.0.1", server.server_port, timeout=5)
                try:
                    with self.assertRaises(ssl.SSLCertVerificationError):
                        untrusted.request("GET", "/v1/capabilities", headers={"Authorization": "Bearer " + TOKEN})
                finally:
                    untrusted.close()
                trusted = http.client.HTTPSConnection("127.0.0.1", server.server_port, timeout=5,
                                                      context=ssl.create_default_context(cafile=certificate))
                try:
                    trusted.request("GET", "/v1/capabilities", headers={"Authorization": "Bearer " + TOKEN})
                    response = trusted.getresponse()
                    self.assertEqual(200, response.status)
                    self.assertEqual(1, json.loads(response.read())["protocolVersion"])
                finally:
                    trusted.close()
            finally:
                server.shutdown()
                server.server_close()
                server.service.close()
                thread.join()


class CompanionHostConfigurationTest(unittest.TestCase):
    def test_explicit_host_targets_do_not_implicitly_enable_programming(self):
        targets = {"bench": object()}
        observed = []
        def register(registry, *, program_targets):
            observed.append(program_targets)
            if program_targets:
                registry.register(Operation("fpga-program", lambda context, parameters: {}, requires_confirmation=True))
        module = SimpleNamespace(register=register)
        with patch("companion.server.DEFAULT_MODULES", ("companion.fpga",)), \
             patch("companion.server.importlib.import_module", return_value=module):
            empty = default_registry()
            self.assertNotIn("fpga-program", empty.operations)
            empty.close()
            registry = default_registry(program_targets=targets)
        self.assertEqual([None, targets], observed)
        service = CompanionService(Configuration(TOKEN), registry)
        try:
            capability = next(item for item in service.capabilities()["operations"] if item["name"] == "fpga-program")
            self.assertFalse(capability["allowed"])
            self.assertTrue(capability["requiresConfirmation"])
        finally:
            service.close()


if __name__ == "__main__":
    unittest.main()
