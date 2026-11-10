"""Authenticated desktop jobs. Start this module explicitly; importing it opens no socket."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import socket
import sys
import tempfile
import threading
import time
import uuid

from companion import fpga_sof, host_isolation
from companion.api import JobContext, JobFailure, Operation, OperationRegistry, safe_name

PROTOCOL_VERSION = 1
DEFAULT_MODULES = ("companion.hdl_import", "companion.hdl_sim", "companion.tcl_runtime", "companion.fpga",
                   "companion.telnet", "companion.jar_runtime")


@dataclass
class Configuration:
    token: str
    allowed_operations: set[str] = field(default_factory=lambda: {"verify-inputs"})
    host: str = "127.0.0.1"
    port: int = 0
    tool_paths: dict[str, str] = field(default_factory=dict)
    max_request_bytes: int = 6 * 1024 * 1024
    max_input_bytes: int = 4 * 1024 * 1024
    max_output_bytes: int = 2 * 1024 * 1024
    max_log_bytes: int = 256 * 1024
    max_jobs: int = 64
    max_request_receipts: int = 4096
    max_pending_jobs: int = 8
    request_timeout_seconds: float = 10
    timeout_seconds: float = 60
    plan_lifetime_seconds: float = 60
    memory_limit_bytes: int = 2 * 1024 * 1024 * 1024


def verify_inputs(context, parameters):
    delay = parameters.get("delaySeconds", 0)
    if isinstance(delay, bool) or not isinstance(delay, (int, float)) or not 0 <= delay <= 5:
        raise JobFailure("invalid-parameters", "delaySeconds must be between zero and five.")
    until = time.monotonic() + delay
    while time.monotonic() < until:
        context.check_cancelled()
        time.sleep(0.01)
    return {"files": [{"name": name, "size": path.stat().st_size,
                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                      for name, path in sorted(context.input_files.items())]}


def default_registry(allow_lan_resource_listeners=False, program_targets=None):
    registry = OperationRegistry()
    registry.register(Operation("verify-inputs", verify_inputs, description="Verify uploaded file hashes without executing source."))
    for module_name in DEFAULT_MODULES:
        try:
            module = importlib.import_module(module_name)
        except ModuleNotFoundError as error:
            if error.name != module_name:
                raise
            continue
        if module_name == "companion.telnet":
            module.register(registry, allow_lan=allow_lan_resource_listeners)
        elif module_name == "companion.fpga":
            module.register(registry, program_targets=program_targets)
        else:
            module.register(registry)
    return registry


def canonical_digest(request):
    material = {key: value for key, value in request.items() if key not in ("planToken", "requestId")}
    return hashlib.sha256(json.dumps(material, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


class CompanionService:
    def __init__(self, configuration: Configuration, registry: OperationRegistry):
        if len(configuration.token) < 32:
            raise ValueError("Use a random authentication token of at least 32 characters.")
        self.configuration, self.registry = configuration, registry
        tool_names = {name for operation in registry.operations.values() for name in operation.required_tools}
        tool_names.update(name for operation in registry.operations.values() for group in operation.tool_alternatives for name in group)
        tool_names.update(profile[key] for operation in registry.operations.values()
                          for profile in operation.program_target_profiles.values()
                          for key in ("tool", "flashTool") if profile.get(key) is not None)
        self.tools = {}
        for name in tool_names | set(configuration.tool_paths):
            if name == "python-usbtmc":
                candidate = sys.executable if name in tool_names and sys.platform.startswith("linux") else None
            else:
                candidate = configuration.tool_paths.get(name) or shutil.which(name)
            if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
                self.tools[name] = str(Path(candidate).resolve())
        self._temporary = tempfile.TemporaryDirectory(prefix="logisim-companion-")
        self._root = Path(self._temporary.name)
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="logisim-job")
        self._jobs, self._requests, self._plans = {}, {}, {}

    def capabilities(self):
        operations = []
        for name, operation in sorted(self.registry.operations.items()):
            missing = sorted(set(operation.required_tools) - set(self.tools))
            alternatives = [{"tools": list(group), "available": all(tool in self.tools for tool in group)}
                            for group in operation.tool_alternatives]
            available = not missing and (not alternatives or any(group["available"] for group in alternatives))
            capability = {"name": name, "allowed": name in self.configuration.allowed_operations,
                          "available": available, "missingTools": missing, "toolAlternatives": alternatives,
                          "requiresConfirmation": operation.requires_confirmation, "description": operation.description}
            if operation.isolated:
                capability["osSandbox"] = operation.isolation_profile
                capability["leaseSeconds"] = host_isolation.LEASE_SECONDS
                capability["available"] = available and host_isolation.available_for(operation.isolation_profile)
            if operation.accepts_input:
                capability["acceptsInput"] = True
            if operation.program_targets:
                capability["programTargets"] = list(operation.program_targets)
            selectable = operation.program_targets
            if operation.program_target_profiles:
                profiles, selectable = {}, []
                for target, profile in operation.program_target_profiles.items():
                    tool = profile["tool"]
                    reason = None
                    if profile.get("transport") == "usbtmc" and not sys.platform.startswith("linux"):
                        reason = "usbtmc-requires-linux"
                    elif tool not in self.tools:
                        reason = f"missing-tool:{tool}"
                    if reason is None:
                        selectable.append(target)
                    if "fingerprint" in profile:
                        profiles[target] = {key: value for key, value in profile.items() if key not in ("tool", "flashTool")}
                        profiles[target]["unavailableReason"] = reason
                capability["available"] = capability["available"] and bool(selectable)
                capability["programTargets"] = [target for target in operation.program_targets
                                                if target in profiles or target in selectable]
                capability["programTargetProfiles"] = profiles
            if operation.flash_program_targets:
                tools = {None, *self.tools}
                capability["flashProgramTargets"] = [
                    target for target in operation.flash_program_targets
                    if target in selectable and operation.program_target_profiles.get(target, {}).get("flashTool") in tools
                ]
            operations.append(capability)
        limits = {"requestBytes": self.configuration.max_request_bytes, "inputBytes": self.configuration.max_input_bytes,
                  "outputBytes": self.configuration.max_output_bytes, "timeoutSeconds": self.configuration.timeout_seconds,
                  "retainedJobs": self.configuration.max_jobs, "requestReceipts": self.configuration.max_request_receipts,
                  "requestSeconds": self.configuration.request_timeout_seconds,
                  "processMemoryBytes": self.configuration.memory_limit_bytes,
                  "memoryEnforcement": "supervised-process-group" if sys.platform == "darwin" else "address-space-and-supervised-process-group",
                  "osSandbox": False}
        allowed = self.configuration.allowed_operations
        program = self.registry.operations.get("fpga-program")
        if ("fpga-quartus-build" in allowed and "fpga-quartus-build" in self.registry.operations or
                "fpga-program" in allowed and program is not None and
                any("sofPart" in profile for profile in program.program_target_profiles.values())):
            limits["quartusSofBytes"] = fpga_sof.MAX_SOF_BYTES
        return {"protocolVersion": PROTOCOL_VERSION, "operations": operations, "limits": limits}

    def _job_request(self, request):
        if not isinstance(request, dict) or set(request) - {"operation", "inputs", "parameters", "requestId", "planToken"}:
            raise JobFailure("invalid-request", "Unknown job envelope field.")
        name = request.get("operation")
        operation = self.registry.operations.get(name) if isinstance(name, str) else None
        if operation is None:
            raise JobFailure("unknown-operation", "The host does not implement this operation.")
        if name not in self.configuration.allowed_operations:
            raise JobFailure("operation-disabled", "The host operator has not enabled this operation.")
        capability = next(item for item in self.capabilities()["operations"] if item["name"] == name)
        if operation.isolated and not host_isolation.available_for(operation.isolation_profile):
            raise JobFailure("sandbox-unavailable", "This host cannot provide the required sandbox.")
        if not capability["available"]:
            raise JobFailure("missing-tool", "Required host tools are unavailable.", capability)
        parameters = request.get("parameters", {})
        if not isinstance(parameters, dict):
            raise JobFailure("invalid-parameters", "parameters must be an object.")
        if operation.validate_parameters is not None:
            operation.validate_parameters(parameters)
        if operation.flash_program_targets and parameters.get("flash") is True and (
                parameters.get("target") not in capability["flashProgramTargets"]):
            raise JobFailure("missing-tool", "Required host flash tools are unavailable.", capability)
        inputs = request.get("inputs", [])
        if not isinstance(inputs, list) or len(inputs) > 64:
            raise JobFailure("invalid-inputs", "Provide at most 64 input files.")
        return operation, parameters, inputs

    def _validate(self, request):
        operation, parameters, inputs = self._job_request(request)
        decoded, names, total = {}, set(), 0
        for item in inputs:
            if not isinstance(item, dict) or set(item) != {"name", "sha256", "base64"}:
                raise JobFailure("invalid-input", "Each input requires name, sha256 and base64.")
            filename = safe_name(item["name"])
            folded = filename.lower()
            if folded in names:
                raise JobFailure("duplicate-input", "Input file names must be unique ignoring ASCII case on every host.")
            names.add(folded)
            if not isinstance(item["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
                raise JobFailure("invalid-hash", "Use a lowercase SHA-256 digest.")
            try:
                data = base64.b64decode(item["base64"], validate=True)
            except (ValueError, TypeError):
                raise JobFailure("invalid-base64", "Input is not valid base64.") from None
            total += len(data)
            if total > self.configuration.max_input_bytes:
                raise JobFailure("input-limit", "The upload exceeds the configured input limit.")
            if not hmac.compare_digest(hashlib.sha256(data).hexdigest(), item["sha256"]):
                raise JobFailure("hash-mismatch", f"The input hash does not match: {filename}")
            decoded[filename] = data
        return operation, parameters, decoded

    def plan(self, request):
        operation, parameters, decoded = self._validate(request)
        inputs = [{"name": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                  for name, data in sorted(decoded.items())]
        return self._plan(request, operation, parameters, inputs)

    def _plan(self, request, operation, parameters, inputs):
        token = secrets.token_urlsafe(32)
        with self._lock:
            now = time.monotonic()
            self._plans = {key: value for key, value in self._plans.items() if value[1] > now}
            if len(self._plans) >= self.configuration.max_jobs:
                raise JobFailure("plan-limit", "Too many unconsumed plans.")
            self._plans[token] = (canonical_digest(request), now + self.configuration.plan_lifetime_seconds)
        plan = {"planToken": token, "expiresInSeconds": self.configuration.plan_lifetime_seconds,
                "operation": operation.name, "parameters": parameters,
                "inputs": inputs,
                "requiresConfirmation": operation.requires_confirmation}
        if operation.isolated:
            plan["osSandbox"] = operation.isolation_profile
            plan["leaseSeconds"] = host_isolation.LEASE_SECONDS
        return plan

    def submit(self, request):
        operation, parameters, decoded = self._validate(request)
        return self._submit(request, operation, parameters, decoded)

    def _sof_request(self, request):
        operation, parameters, inputs = self._job_request(request)
        if (operation.name != "fpga-program" or not operation.requires_confirmation or
                set(parameters) != {"target", "bitstream", "profile", "flash"} or parameters["flash"] is not False):
            raise JobFailure("invalid-fpga-parameter", "Large SOF transfer requires a volatile Quartus program request.")
        profile = operation.program_target_profiles.get(parameters["target"], {})
        if profile.get("transport") != "quartus" or "sofPart" not in profile:
            raise JobFailure("invalid-fpga-parameter", "Select a Quartus target configured with the expected SOF part.")
        if profile.get("tool") not in self.tools:
            raise JobFailure("missing-tool", "The configured Quartus programming tool is unavailable.")
        if len(inputs) != 1 or not isinstance(inputs[0], dict) or set(inputs[0]) != {"name", "size", "sha256"}:
            raise JobFailure("invalid-input", "Provide one SOF name, size and SHA-256.")
        descriptor = inputs[0]
        name = safe_name(descriptor["name"])
        size, sha = descriptor["size"], descriptor["sha256"]
        if name != parameters["bitstream"] or Path(name).suffix.lower() != ".sof":
            raise JobFailure("invalid-bitstream", "The SOF name must match the reviewed programming file.")
        if type(size) is not int or not fpga_sof.INLINE_PROGRAM_BYTES < size <= fpga_sof.MAX_SOF_BYTES:
            raise JobFailure("input-limit", "Large SOF transfer requires a file over 4 MiB and up to 8 MiB.")
        if not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{64}", sha):
            raise JobFailure("invalid-hash", "Use a lowercase SHA-256 digest.")
        return operation, parameters, descriptor, profile["sofPart"]

    def plan_sof(self, request):
        if not isinstance(request, dict) or set(request) != {"operation", "inputs", "parameters"}:
            raise JobFailure("invalid-request", "Provide an exact large SOF plan descriptor.")
        operation, parameters, descriptor, _ = self._sof_request(request)
        return self._plan(request, operation, parameters, [descriptor])

    def submit_sof(self, request, read_body):
        operation, parameters, descriptor, part = self._sof_request(request)
        request_id = self._request_id(request)
        if request_id is None:
            raise JobFailure("invalid-request-id", "Large SOF submission requires a retry identifier.")
        digest = canonical_digest(request)
        with self._lock:
            if self._retry(request_id, digest) is None:
                self._confirmation(request, digest)
        body = read_body(descriptor["size"])
        fpga_sof.verify_upload(body, descriptor, part)
        return self._submit(request, operation, parameters, {descriptor["name"]: body})

    def quartus_sof(self, job_id):
        with self._lock:
            job = self._jobs.get(job_id)
            if (job is None or job["state"] != "succeeded" or job["operation"] != "fpga-quartus-build" or
                    not isinstance(job["result"], dict)):
                raise JobFailure("invalid-artifact", "No completed Quartus SOF is available for this job.")
            artifacts = job["result"].get("artifacts")
            if not isinstance(artifacts, list) or len(artifacts) != 1 or not isinstance(artifacts[0], dict):
                raise JobFailure("invalid-artifact", "No binary Quartus SOF is available for this job.")
            artifact = artifacts[0]
            size = artifact.get("size")
            sha = artifact.get("sha256")
            if (artifact.get("name") != fpga_sof.SOF_ARTIFACT or artifact.get("transport") != "binary" or
                    type(size) is not int or not fpga_sof.INLINE_ARTIFACT_BYTES < size <= fpga_sof.MAX_SOF_BYTES or
                    not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{64}", sha)):
                raise JobFailure("invalid-artifact", "No binary Quartus SOF is available for this job.")
            directory = self._root / job_id
            path = directory / "quartus-output" / "build.sof"
            if path.is_symlink() or not path.resolve().is_relative_to(directory.resolve()) or not path.is_file():
                raise JobFailure("invalid-artifact", "The retained Quartus SOF is unavailable.")
            with path.open("rb") as source:
                data = source.read(size + 1)
            if len(data) != size or not hmac.compare_digest(hashlib.sha256(data).hexdigest(), sha):
                raise JobFailure("invalid-artifact", "The retained Quartus SOF failed its size or SHA-256 check.")
            return data

    @staticmethod
    def _request_id(request):
        request_id = request.get("requestId")
        if request_id is not None and (not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", request_id)):
            raise JobFailure("invalid-request-id", "Invalid retry identifier.")
        return request_id

    def _retry(self, request_id, digest):
        if request_id not in self._requests:
            return None
        old_digest, job_id = self._requests[request_id]
        if digest != old_digest:
            raise JobFailure("request-conflict", "The retry identifier already belongs to a different request.")
        if job_id not in self._jobs:
            raise JobFailure("job-expired", "The original result expired. This retry will not repeat the operation.", {"jobId": job_id})
        return self.snapshot(job_id)

    def _confirmation(self, request, digest):
        token = request.get("planToken")
        plan = self._plans.get(token) if isinstance(token, str) else None
        if plan is None or plan[0] != digest or plan[1] <= time.monotonic():
            raise JobFailure("confirmation-required", "Review a matching unexpired plan before submitting this operation.")
        return token

    def _submit(self, request, operation, parameters, decoded):
        digest = canonical_digest(request)
        request_id = self._request_id(request)
        with self._lock:
            retry = self._retry(request_id, digest)
            if retry is not None:
                return retry
            if request_id is not None and len(self._requests) >= self.configuration.max_request_receipts:
                raise JobFailure("receipt-limit", "The retry receipt limit was reached. Review results before restarting the host.")
            if sum(job["state"] in ("queued", "running") for job in self._jobs.values()) >= self.configuration.max_pending_jobs:
                raise JobFailure("queue-full", "The job queue is full.")
            job_to_evict = None
            if len(self._jobs) >= self.configuration.max_jobs:
                job_to_evict = next((key for key, job in self._jobs.items()
                                     if job["state"] in ("succeeded", "failed", "cancelled")), None)
                if job_to_evict is None:
                    raise JobFailure("job-limit", "All retained jobs are still active.")
            token = self._confirmation(request, digest) if operation.requires_confirmation else None
            job_id = uuid.uuid4().hex
            directory = self._root / job_id
            input_files = {}
            job = {"jobId": job_id, "operation": operation.name, "state": "queued", "result": None,
                   "error": None, "logs": [], "logBytes": 0, "cancelled": threading.Event()}
            lease = host_isolation.Lease() if operation.isolated else None
            if lease is not None:
                job["isolationLease"] = lease
            try:
                directory.mkdir(mode=0o700)
                inputs_dir = directory / "inputs"
                inputs_dir.mkdir()
                for filename, data in decoded.items():
                    path = inputs_dir / filename
                    path.write_bytes(data)
                    input_files[filename] = path
                if operation.name == "tcl-tk-live":
                    from companion.tk_session import TkSession
                    job["tkSession"] = TkSession(directory / "tk-input")
                context = JobContext(directory, input_files, self.tools, job["cancelled"],
                                     self.configuration.timeout_seconds,
                                     lambda stream, text: self._log(job_id, stream, text),
                                     token is not None, self.configuration.max_output_bytes,
                                     memory_limit_bytes=self.configuration.memory_limit_bytes, isolation_lease=lease,
                                     publish_live=(lambda value: self._publish_live(job_id, value)) if operation.accepts_input else None,
                                     isolation_profile=operation.isolation_profile, tk_session=job.get("tkSession"))
                if job_to_evict is not None:
                    shutil.rmtree(self._root / job_to_evict)
            except OSError as error:
                shutil.rmtree(directory, ignore_errors=True)
                raise JobFailure("job-storage", "The companion could not retain the reviewed job files.") from error
            if job_to_evict is not None:
                self._jobs.pop(job_to_evict)
            if token is not None:
                self._plans.pop(token)
            self._jobs[job_id] = job
            if request_id is not None:
                self._requests[request_id] = (digest, job_id)
            if operation.accepts_input:
                job["inputContext"] = context
            job["future"] = self._executor.submit(self._execute, job_id, operation, context, parameters)
            return self.snapshot(job_id)

    def input(self, job_id, payload):
        with self._lock:
            job = self._jobs.get(job_id)
            context = job.get("inputContext") if job and job["state"] == "running" else None
        if context is None:
            raise JobFailure("live-input-unavailable", "This job has no active live input.")
        return context.accept_live_input(payload)

    def _publish_live(self, job_id, value):
        if len(json.dumps(value, allow_nan=False).encode()) > self.configuration.max_output_bytes:
            raise JobFailure("output-limit", "The live output exceeded the output limit.")
        with self._lock:
            job = self._jobs[job_id]
            if job["state"] != "running" or job["cancelled"].is_set():
                raise JobFailure("cancelled", "The job was cancelled.")
            job["live"] = value

    def _log(self, job_id, stream, text):
        with self._lock:
            job = self._jobs[job_id]
            remaining = self.configuration.max_log_bytes - job["logBytes"]
            if remaining <= 0:
                return
            data = str(text).encode()[:remaining]
            job["logBytes"] += len(data)
            job["logs"].append({"stream": str(stream), "text": data.decode(errors="replace")})

    def _execute(self, job_id, operation, context, parameters):
        with self._lock:
            job = self._jobs[job_id]
            job["state"] = "running"
        try:
            context.check_cancelled()
            result = operation.handler(context, parameters)
            context.check_cancelled()
            if len(json.dumps(result, allow_nan=False).encode()) > self.configuration.max_output_bytes:
                raise JobFailure("output-limit", "The operation result exceeded the output limit.")
            with self._lock:
                context.check_cancelled()
                job["state"], job["result"] = "succeeded", result
        except JobFailure as error:
            with self._lock:
                job["state"] = "cancelled" if error.code in ("cancelled", "lease-expired") else "failed"
                job["error"] = error.as_dict()
        except Exception:
            with self._lock:
                job["state"] = "failed"
                job["error"] = {"code": "operation-failed", "message": "The host operation failed.", "details": None}
        finally:
            with self._lock:
                job.pop("inputContext", None)
                job.pop("live", None)

    def snapshot(self, job_id):
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                raise JobFailure("unknown-job", "The job is not retained on this host session.")
            snapshot = {**{key: job[key] for key in ("jobId", "operation", "state", "result", "error")},
                        "logs": list(job["logs"])}
            lease = job.get("isolationLease")
            if (lease is not None and job["state"] == "running" and
                    not job["cancelled"].is_set() and not lease.expired()):
                if "live" in job:
                    snapshot["live"] = job["live"]
                session = job.get("tkSession")
                if session is not None:
                    frame = session.latest(self.configuration.max_output_bytes)
                    if frame is not None:
                        snapshot["result"] = {"mode": "tk-live-v1", "sourceSha256": session.source_sha256, "frame": frame}
            return snapshot

    def send_tk_input(self, job_id, event):
        with self._lock:
            job = self._jobs.get(job_id)
            lease = job.get("isolationLease") if job else None
            if (lease is None or job.get("tkSession") is None or job["state"] != "running" or
                    job["cancelled"].is_set() or lease.expired()):
                raise JobFailure("tk-input-unavailable", "The private Tk job is not active.")
            session = job["tkSession"]
            session.latest(self.configuration.max_output_bytes)
            return {"sequence": session.offer(event)}

    def poll(self, job_id):
        with self._lock:
            snapshot = self.snapshot(job_id)
            job = self._jobs[job_id]
            lease = job.get("isolationLease")
            if lease is not None and job["state"] in ("queued", "running") and not job["cancelled"].is_set():
                lease.renew()
            return snapshot

    def cancel(self, job_id):
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                raise JobFailure("unknown-job", "The job does not exist on this host session.")
            if job["state"] in ("queued", "running"):
                job["cancelled"].set()
                if job["future"].cancel():
                    job["state"] = "cancelled"
                    job["error"] = {"code": "cancelled", "message": "The job was cancelled.", "details": None}
                    job.pop("inputContext", None)
            return self.snapshot(job_id)

    def close(self):
        with self._lock:
            for job in self._jobs.values():
                job["cancelled"].set()
        self._executor.shutdown(wait=True, cancel_futures=True)
        try:
            self.registry.close()
        finally:
            self._temporary.cleanup()


def make_http_server(configuration, registry, tls_context=None):
    address = ipaddress.ip_address(configuration.host)
    if not address.is_loopback and tls_context is None:
        raise ValueError("Non-loopback API listeners require TLS.")
    service = CompanionService(configuration, registry)

    class Handler(BaseHTTPRequestHandler):
        server_version = "LogisimCompanion/1"

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def handle(self):
            def interrupt():
                try:
                    self.connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            timer = threading.Timer(configuration.request_timeout_seconds, interrupt)
            timer.daemon = True
            timer.start()
            try:
                super().handle()
            except (ConnectionError, TimeoutError, ssl.SSLError):
                pass
            finally:
                timer.cancel()

        def log_message(self, format, *args):
            pass

        def send_json(self, status, data):
            encoded = json.dumps(data, separators=(",", ":"), allow_nan=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        def send_sof(self, data):
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def authenticated(self):
            if self.headers.get("Origin") is not None:
                self.send_json(403, {"error": {"code": "browser-origin-denied", "message": "Browser-origin requests are not accepted."}})
                return False
            supplied = self.headers.get("Authorization", "")
            if not hmac.compare_digest(supplied.encode("utf-8"), ("Bearer " + configuration.token).encode("utf-8")):
                self.send_json(401, {"error": {"code": "unauthorized", "message": "Pair with this host before sending requests."}})
                return False
            return True

        def request_json(self, max_bytes=None):
            try:
                length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                raise JobFailure("invalid-length", "Provide a valid Content-Length.") from None
            if not 0 < length <= min(configuration.max_request_bytes, max_bytes or configuration.max_request_bytes):
                raise JobFailure("request-limit", "The request is empty or exceeds the configured limit.")
            def pairs(items):
                result = {}
                for key, value in items:
                    if key in result:
                        raise ValueError("Duplicate JSON key")
                    result[key] = value
                return result
            try:
                def finite_float(value):
                    parsed = float(value)
                    if not math.isfinite(parsed):
                        raise ValueError("Non-finite number")
                    return parsed
                return json.loads(self.rfile.read(length).decode("utf-8"), object_pairs_hook=pairs,
                                  parse_float=finite_float,
                                  parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Non-finite number")))
            except (ValueError, UnicodeDecodeError, RecursionError):
                raise JobFailure("invalid-json", "Provide one finite UTF-8 JSON object without duplicate keys.") from None

        def sof_submission(self):
            def header(name):
                values = self.headers.get_all(name, ())
                if len(values) != 1 or not 0 < len(values[0]) <= 256 or any(not 32 <= ord(char) <= 126 for char in values[0]):
                    raise JobFailure("invalid-request", f"Provide one valid {name} header.")
                return values[0]
            if self.headers.get("Transfer-Encoding") is not None or header("Content-Type") != "application/octet-stream":
                raise JobFailure("invalid-request", "Upload the reviewed SOF as fixed-length application/octet-stream.")
            length = header("Content-Length")
            if not re.fullmatch(r"[0-9]{1,8}", length):
                raise JobFailure("invalid-length", "Provide a valid SOF Content-Length.")
            name = header("X-Logisim-Sof-Name")
            return {"operation": "fpga-program", "inputs": [
                {"name": name, "size": int(length), "sha256": header("X-Logisim-Sof-Sha256")}],
                "parameters": {"target": header("X-Logisim-Fpga-Target"), "bitstream": name,
                               "profile": header("X-Logisim-Fpga-Profile"), "flash": False},
                "planToken": header("X-Logisim-Plan-Token"), "requestId": header("X-Logisim-Request-Id")}

        def dispatch(self):
            if not self.authenticated():
                return
            try:
                if self.command == "GET" and self.path == "/v1/capabilities":
                    self.send_json(200, service.capabilities())
                elif self.command == "POST" and self.path == "/v1/plans":
                    self.send_json(200, service.plan(self.request_json()))
                elif self.command == "POST" and self.path == "/v1/jobs":
                    self.send_json(202, service.submit(self.request_json()))
                elif self.command == "POST" and self.path == "/v1/fpga-sof/plans":
                    content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                    if (content_type != "application/json" or self.headers.get("Transfer-Encoding") is not None or
                            len(self.headers.get_all("Content-Length", ())) != 1):
                        raise JobFailure("invalid-request", "Provide a fixed-length JSON plan for the SOF.")
                    self.send_json(200, service.plan_sof(self.request_json(4096)))
                elif self.command == "POST" and self.path == "/v1/fpga-sof/jobs":
                    self.send_json(202, service.submit_sof(self.sof_submission(), self.rfile.read))
                elif self.command == "GET" and re.fullmatch(r"/v1/jobs/[a-f0-9]{32}/artifacts/design\.sof", self.path):
                    self.send_sof(service.quartus_sof(self.path.split("/")[3]))
                elif self.command == "POST" and re.fullmatch(r"/v1/jobs/[a-f0-9]{32}/input", self.path):
                    self.send_json(202, service.input(self.path.split("/")[-2], self.request_json()))
                elif self.command == "POST" and re.fullmatch(r"/v1/jobs/[a-f0-9]{32}/tk-input", self.path):
                    self.send_json(200, service.send_tk_input(self.path.split("/")[-2], self.request_json(256)))
                elif re.fullmatch(r"/v1/jobs/[a-f0-9]{32}", self.path) and self.command in ("GET", "DELETE"):
                    job_id = self.path.rsplit("/", 1)[1]
                    self.send_json(200, service.poll(job_id) if self.command == "GET" else service.cancel(job_id))
                else:
                    self.send_json(404, {"error": {"code": "not-found", "message": "Unknown endpoint."}})
            except JobFailure as error:
                self.send_json(400, {"error": error.as_dict()})

        do_GET = do_POST = do_DELETE = dispatch

    try:
        class AddressServer(HTTPServer):
            address_family = socket.AF_INET6 if address.version == 6 else socket.AF_INET

            def get_request(self):
                connection, peer = super().get_request()
                connection.settimeout(5)
                if tls_context is not None:
                    try:
                        connection = tls_context.wrap_socket(connection, server_side=True)
                    except BaseException:
                        connection.close()
                        raise
                return connection, peer

        server = AddressServer((configuration.host, configuration.port), Handler)
    except BaseException:
        service.close()
        raise
    server.service = service
    return server


def load_token(path):
    path = Path(path).expanduser()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.exists():
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as destination:
            destination.write(secrets.token_urlsafe(32) + "\n")
    if os.name == "posix" and path.stat().st_mode & 0o077:
        raise ValueError("The authentication token file must be readable only by its owner.")
    token = path.read_text().strip()
    if len(token) < 32:
        raise ValueError("The authentication token is too short.")
    return token


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token-file", type=Path, default=Path.home() / ".config/logisim-mobile/companion-token")
    parser.add_argument("--allow", action="append", default=[], help="Enable one fixed registered operation; repeat as needed.")
    parser.add_argument("--tool", action="append", default=[], help="Configure a tool as NAME=/absolute/path.")
    parser.add_argument("--job-timeout-seconds", type=int, default=60, metavar="1–1800",
                        help="Set the wall-clock limit for each job (default: 60).")
    parser.add_argument("--job-memory-mib", type=int, default=2048, metavar="256–65536",
                        help="Set the supervised memory limit for each job (default: 2048 MiB).")
    parser.add_argument("--job-output-mib", type=int, default=2, metavar="1–7",
                        help="Set the command output and result limit (default: 2 MiB).")
    parser.add_argument("--tls-cert", type=Path)
    parser.add_argument("--tls-key", type=Path)
    parser.add_argument("--allow-lan-resource-listeners", action="store_true", help="Allow explicitly started Telnet listeners outside loopback.")
    parser.add_argument("--fpga-targets", type=Path, help="Read physical board/cable identities from an explicit host-only JSON file.")
    args = parser.parse_args()
    for option, value, minimum, maximum in (
        ("--job-timeout-seconds", args.job_timeout_seconds, 1, 1800),
        ("--job-memory-mib", args.job_memory_mib, 256, 65536),
        ("--job-output-mib", args.job_output_mib, 1, 7),
    ):
        if not minimum <= value <= maximum:
            parser.error(f"{option} must be between {minimum} and {maximum}")
    tool_paths = {}
    for value in args.tool:
        if "=" not in value:
            parser.error("--tool requires NAME=/absolute/path")
        name, path = value.split("=", 1)
        if not Path(path).is_absolute():
            parser.error("Tool paths must be absolute")
        tool_paths[name] = path
    tls = None
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error("Provide both --tls-cert and --tls-key")
    if args.tls_cert:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(args.tls_cert, args.tls_key)
    program_targets = None
    if args.fpga_targets:
        try:
            from companion.fpga_host_config import load_program_targets
            program_targets = load_program_targets(args.fpga_targets)
        except (ModuleNotFoundError, OSError, ValueError) as error:
            parser.error(f"Cannot load host FPGA targets: {error}")
    configuration = Configuration(load_token(args.token_file), set(args.allow) | {"verify-inputs"},
                                  args.host, args.port, tool_paths, timeout_seconds=args.job_timeout_seconds,
                                  memory_limit_bytes=args.job_memory_mib * 1024 * 1024,
                                  max_output_bytes=args.job_output_mib * 1024 * 1024)
    registry = default_registry(args.allow_lan_resource_listeners, program_targets)
    unknown = configuration.allowed_operations - set(registry.operations)
    if unknown:
        parser.error("Unknown operations: " + ", ".join(sorted(unknown)))
    server = make_http_server(configuration, registry, tls)
    scheme = "https" if tls else "http"
    print(f"Logisim companion listening at {scheme}://{args.host}:{server.server_port}")
    print(f"Pairing token file: {args.token_file.expanduser()}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        server.service.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
