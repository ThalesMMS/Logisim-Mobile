#!/usr/bin/env python3
"""Run the actual companion server with deterministic integration-test operations."""
import os
import importlib.util
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import time

sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
from companion.api import Operation
from companion.server import Configuration, default_registry, make_http_server


def artifact(context, parameters):
    source = context.input_path("input.txt")
    output = context.output_path("result.bin")
    output.write_bytes(source.read_bytes())
    context.emit("stdout", "artifact ready")
    return {"artifact": context.artifact(output), "confirmed": context.user_confirmed}


registry = default_registry()
fpga_source = os.environ.get("LOGISIM_COMPANION_FPGA_SOURCE")
if fpga_source and "fpga-synthesize" not in registry.operations:
    spec = importlib.util.spec_from_file_location("companion.fpga", Path(fpga_source) / "companion/fpga.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.register(registry)
registry.register(Operation("test-artifact", artifact))
registry.register(Operation("test-confirmed", artifact, requires_confirmation=True))
configuration = Configuration(os.environ["LOGISIM_COMPANION_TEST_TOKEN"],
    allowed_operations={"verify-inputs", "test-artifact", "test-confirmed", "fpga-synthesize"})
certificates = tempfile.TemporaryDirectory(prefix="logisim-companion-test-tls-")
tls = None
if len(sys.argv) > 2 and sys.argv[2] == "tls":
    certificate = Path(certificates.name) / "certificate.pem"
    key = Path(certificates.name) / "key.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", "/CN=localhost", "-keyout", str(key), "-out", str(certificate)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(certificate, key)
server = make_http_server(configuration, registry, tls)
base_handler = server.RequestHandlerClass


class FixtureHandler(base_handler):
    def do_GET(self):
        if self.path == "/v1/test-slow":
            if self.authenticated():
                time.sleep(2)
                try:
                    self.send_json(200, {"ok": True})
                except (BrokenPipeError, ConnectionResetError):
                    pass
        elif self.path == "/v1/test-large":
            self.send_response(200)
            self.send_header("Content-Length", "10485760")
            self.end_headers()
        elif self.path == "/v1/test-trickle":
            self.send_response(200)
            self.send_header("Content-Length", "20")
            self.end_headers()
            try:
                for _ in range(20):
                    self.wfile.write(b"x")
                    self.wfile.flush()
                    time.sleep(0.1)
            except (BrokenPipeError, ConnectionResetError):
                pass
        elif self.path == "/v1/test-redirect":
            self.send_response(302)
            self.send_header("Location", "/v1/capabilities")
            self.send_header("Content-Length", "0")
            self.end_headers()
        else:
            super().do_GET()


server.RequestHandlerClass = FixtureHandler
print(server.server_address[1], flush=True)
try:
    server.serve_forever(poll_interval=0.05)
finally:
    server.service.close()
    server.server_close()
    certificates.cleanup()
