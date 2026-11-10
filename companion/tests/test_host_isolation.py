import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from companion import host_isolation
from companion.api import JobContext, JobFailure, Operation, OperationRegistry
from companion.server import CompanionService, Configuration, default_registry, make_http_server


TCL = "/usr/bin/tclsh"
TOKEN = "synthetic-token-for-host-isolation-tests"


class HostIsolationPolicyTest(unittest.TestCase):
    def test_isolation_cannot_be_registered_without_confirmation(self):
        with self.assertRaises(ValueError):
            Operation("test-isolated", lambda ctx, params: {}, isolated=True)

    def test_default_registry_requires_explicit_enablement_and_approval_for_isolated_workers(self):
        registry = default_registry()
        default_config = Configuration(TOKEN)
        for operation in registry.operations.values():
            if operation.isolated:
                self.assertNotIn(operation.name, default_config.allowed_operations)
                self.assertTrue(operation.requires_confirmation)

    def test_default_configuration_disables_isolated_operations(self):
        service = CompanionService(Configuration(TOKEN, {"verify-inputs"}), default_registry())
        self.addCleanup(service.close)
        isolated = [op for op in service.capabilities()["operations"] if "osSandbox" in op]
        self.assertTrue(all(op["requiresConfirmation"] and not op["allowed"] for op in isolated))

    def test_lease_renews_only_while_it_is_live(self):
        with patch("companion.host_isolation.time.monotonic", return_value=10) as clock:
            lease = host_isolation.Lease(seconds=5)
            clock.return_value = 14
            lease.renew()
            clock.return_value = 18
            self.assertFalse(lease.expired())
            clock.return_value = 19
            lease.renew()
            self.assertTrue(lease.expired())

    def test_missing_sandbox_prevents_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            context = JobContext(Path(directory), {}, {"probe": "/usr/bin/true"},
                                 threading.Event(), user_confirmed=True,
                                 isolation_lease=host_isolation.Lease())
            with patch("companion.host_isolation.available", return_value=False), \
                    patch("companion.api.subprocess.Popen") as popen, self.assertRaises(JobFailure) as caught:
                context.run([context.tool("probe")])
            self.assertEqual("sandbox-unavailable", caught.exception.code)
            popen.assert_not_called()


@unittest.skipUnless(host_isolation.available() and Path(TCL).is_file(), "macOS sandbox and system Tcl required")
class HostIsolationIntegrationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.outside = Path(directory.name).resolve() / "not-granted.txt"
        self.outside.write_text("ungranted canary")

        def execute(context, parameters):
            result = context.run([context.tool("tclsh"), str(context.input_path("probe.tcl")),
                                  str(context.input_path("granted.txt")), str(self.outside),
                                  str(parameters.get("port", 0))])
            return {"code": result.returncode, "stdout": result.stdout, "stderr": result.stderr}

        registry = OperationRegistry()
        registry.register(Operation("test-isolated", execute, ("tclsh",),
                                    requires_confirmation=True, isolated=True))
        self.server = make_http_server(Configuration(TOKEN, {"test-isolated"}, tool_paths={"tclsh": TCL}), registry)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(self.server.service.close)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(self, method, path, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, json.dumps(body) if body is not None else None,
                               {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def start(self, source, **parameters):
        inputs = []
        for name, data in (("probe.tcl", source.encode()), ("granted.txt", b"approved input")):
            inputs.append({"name": name, "sha256": hashlib.sha256(data).hexdigest(),
                           "base64": base64.b64encode(data).decode()})
        body = {"operation": "test-isolated", "parameters": parameters, "inputs": inputs}
        status, response = self.request("POST", "/v1/jobs", body)
        self.assertEqual((400, "confirmation-required"), (status, response["error"]["code"]))
        status, plan = self.request("POST", "/v1/plans", body)
        self.assertEqual(200, status)
        self.assertEqual("macos-seatbelt-v1", plan["osSandbox"])
        self.assertEqual(host_isolation.LEASE_SECONDS, plan["leaseSeconds"])
        body["planToken"] = plan["planToken"]
        status, job = self.request("POST", "/v1/jobs", body)
        self.assertEqual(202, status, job)
        status, replay = self.request("POST", "/v1/jobs", body)
        self.assertEqual((400, "confirmation-required"), (status, replay["error"]["code"]))
        return job["jobId"]

    def wait_job(self, job_id, seconds=3):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            status, job = self.request("GET", "/v1/jobs/" + job_id)
            self.assertEqual(200, status)
            if job["state"] not in ("queued", "running"):
                return job
            time.sleep(0.025)
        self.fail("Job did not finish")

    def test_rejected_sandbox_profile_fails_the_job_before_the_worker_runs(self):
        with patch("companion.host_isolation._PROFILE", "(version 1) (invalid-profile-rule)"):
            job_id = self.start('puts "worker started"')
            job = self.wait_job(job_id)
        self.assertEqual(("failed", "isolated-command-failed"), (job["state"], (job["error"] or {}).get("code")), job)
        self.assertIsNone(job["result"])
        self.assertFalse(any("worker started" in entry["text"] for entry in job["logs"]))

    def test_tcl_can_read_granted_input_but_cannot_read_write_connect_listen_or_spawn(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            job_id = self.start("""
set allowed [lindex $argv 0]
set outside [lindex $argv 1]
set port [lindex $argv 2]
set channel [open $allowed r]
puts [read $channel]
close $channel
puts [list [catch {open $outside r}] [catch {open $allowed w}] \
           [catch {open $outside w}] [catch {socket 127.0.0.1 $port}] \
           [catch {socket -server accept 0}] [catch {exec /usr/bin/true &}]]
""", port=listener.getsockname()[1])
            job = self.wait_job(job_id)
            self.assertEqual("succeeded", job["state"], job)
            self.assertEqual((0, "approved input\n1 1 1 1 1 1\n"), (job["result"]["code"], job["result"]["stdout"]),
                             job["result"])
        self.assertEqual("ungranted canary", self.outside.read_text())

    def test_approved_jar_runs_under_the_same_file_network_and_process_policy(self):
        java_home = subprocess.run(["/usr/libexec/java_home", "-F", "-v", "21"], text=True,
                                   capture_output=True, timeout=5)
        # A Homebrew JDK is not registered with java_home; scripts/java-home.sh exports it.
        located = java_home.stdout.strip() if java_home.returncode == 0 else os.environ.get("JAVA_HOME", "")
        if not located:
            self.skipTest("A macOS JDK 21 is required for the Java isolation probe")
        jdk = Path(located).resolve()
        if not (jdk / "bin/javac").is_file():
            self.skipTest("The Java isolation probe requires javac")
        with tempfile.TemporaryDirectory() as temporary, socket.socket() as listener:
            work = Path(temporary).resolve()
            source = work / "Probe.java"
            source.write_text("""
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

public class Probe {
    interface Action { void run() throws IOException; }
    static void expectDenied(Action action) throws IOException {
        try {
            action.run();
        } catch (IOException denied) {
            return;
        }
        throw new AssertionError("Unexpected host access");
    }
    public static void main(String[] args) throws IOException {
        Path allowed = Path.of(args[0]);
        Path outside = Path.of(args[1]);
        System.out.println(Files.readString(allowed));
        // AWT text metrics load libfontmanager and the libraries it links outside the JDK.
        var graphics = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
        System.out.println(graphics.getFontMetrics().stringWidth("Logisim") > 0 ? "fonts" : "no fonts");
        expectDenied(() -> Files.readString(outside));
        expectDenied(() -> Files.writeString(allowed, "changed"));
        expectDenied(() -> Files.writeString(outside, "changed"));
        expectDenied(() -> {
            try (Socket connection = new Socket()) {
                connection.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[2])), 1000);
            }
        });
        expectDenied(() -> { try (ServerSocket server = new ServerSocket(0)) {} });
        expectDenied(() -> new ProcessBuilder("/usr/bin/true").start());
        System.out.println("six denials");
    }
}
""")
            subprocess.run([jdk / "bin/javac", "-J-XX:ActiveProcessorCount=2", str(source)],
                           check=True, capture_output=True, timeout=25)
            jar = work / "probe.jar"
            with ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: Probe\n\n")
                for compiled in work.glob("*.class"):
                    archive.write(compiled, compiled.name)
            approved = work / "approved.txt"
            approved.write_text("approved input")
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            java = str(jdk / "bin/java")
            context = JobContext(work, {"probe.jar": jar, "approved.txt": approved}, {"java": java},
                                 threading.Event(), user_confirmed=True, isolation_lease=host_isolation.Lease())
            result = context.run([java, "-XX:-UsePerfData", "-Djava.awt.headless=true", f"-Duser.home={work}",
                                  "-jar", str(jar), str(approved), str(self.outside), str(listener.getsockname()[1])],
                                 java_home=jdk)
            self.assertEqual((0, "approved input\nfonts\nsix denials\n"), (result.returncode, result.stdout), result.stderr)
            self.assertEqual("approved input", approved.read_text())
            self.assertEqual("ungranted canary", self.outside.read_text())

    def test_cancel_and_disconnect_terminate_an_observed_worker(self):
        for reason in ("cancelled", "lease-expired"):
            with self.subTest(reason=reason):
                job_id = self.start('puts [pid]; flush stdout; after 60000')
                stdout = self.server.service._root / job_id / "command-1.stdout"
                deadline = time.monotonic() + 2
                pid = None
                while time.monotonic() < deadline:
                    if stdout.is_file() and (contents := stdout.read_text().strip()).isdecimal():
                        pid = int(contents)
                        break
                    time.sleep(0.025)
                self.assertIsNotNone(pid, "Tcl did not report its live process ID")
                os.kill(pid, 0)
                if reason == "cancelled":
                    self.assertEqual(200, self.request("DELETE", "/v1/jobs/" + job_id)[0])
                else:
                    # The previous HTTP connection has closed. The worker can only
                    # stay live if an authenticated client continues polling.
                    time.sleep(host_isolation.LEASE_SECONDS + 0.2)
                job = self.wait_job(job_id)
                self.assertEqual(("cancelled", reason), (job["state"], job["error"]["code"]), job)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
