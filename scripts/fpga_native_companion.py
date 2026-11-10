"""Loopback FPGA host for native UI scenarios. Importing this module starts nothing."""

from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timezone
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from companion.api import OperationRegistry, safe_name
from companion import fpga
from companion.server import Configuration, make_http_server


TOOLS = ("yosys", "nextpnr-ice40", "icepack", "ghdl")


def _now():
    return datetime.now(timezone.utc).isoformat()


def _artifact_metadata(result):
    """Keep the handler's outcome and artifact identities without binary payloads."""
    if not isinstance(result, dict):
        return None
    retained = {name: deepcopy(result[name]) for name in (
        "synthesized", "placedAndRouted", "bitstreamGenerated", "programmed",
        "hardwareVerified", "flow", "top") if name in result}
    retained["artifacts"] = [{name: artifact[name] for name in ("name", "size", "sha256")}
                             for artifact in result.get("artifacts", [])]
    return retained


class _AuditedContext:
    def __init__(self, context, event, record):
        self._context, self._event, self._record = context, event, record

    def __getattr__(self, name):
        return getattr(self._context, name)

    def emit(self, stream, text):
        if stream == "stage":
            self._record(self._event, "stage", text)
        self._context.emit(stream, text)

    def run(self, argv, *args, **kwargs):
        result = self._context.run(argv, *args, **kwargs)
        self._record(self._event, "stdout", result.stdout)
        self._record(self._event, "stderr", result.stderr)
        return result


class FpgaNativeCompanion:
    """Run the production synthesis handler with explicitly identified local tools.

    The token is available to the UI driver only in memory. ``audit()`` removes
    it from every retained string. Only ``fpga-synthesize`` is registered, so
    this host cannot advertise or execute physical programming.
    """

    def __init__(self, tool_paths=None):
        configured = dict(tool_paths or {})
        if set(configured) - set(TOOLS):
            raise ValueError("The native FPGA host accepts only Yosys, nextpnr-ice40, icepack and GHDL paths")
        tools = {}
        for name in TOOLS:
            candidate = configured.get(name) or shutil.which(name) or f"/opt/homebrew/bin/{name}"
            path = Path(candidate).expanduser().resolve()
            if not path.is_file() or not os.access(path, os.X_OK):
                raise ValueError(f"The native FPGA host requires an executable {name}")
            tools[name] = str(path)
        self.token = secrets.token_hex(32)
        self._lock = threading.RLock()
        self._events, self._requests, self._job_ids = [], [], set()
        self._artifacts = {}
        self._closed = False
        self._log_limit = 256 * 1024
        self.hold_seconds = 0
        self.tool_identities = {name: {"path": path, "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest()}
                                for name, path in tools.items()}
        registry = OperationRegistry()
        fpga.register(registry)
        operation = registry.operations["fpga-synthesize"]
        registry.operations = {operation.name: replace(operation, handler=self._audited_handler(operation.handler))}
        self.server = make_http_server(Configuration(
            token=self.token, host="127.0.0.1", port=0,
            allowed_operations={"fpga-synthesize"}, tool_paths=tools), registry)
        service = self.server.service
        for action in ("plan", "submit", "cancel"):
            original = getattr(service, action)
            setattr(service, action, self._audited_request(action, original))
        self.endpoint = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=0.05), daemon=True)
        try:
            self.thread.start()
        except BaseException:
            self.server.server_close()
            service.close()
            raise

    def _redact(self, value):
        if isinstance(value, str):
            return value.replace(self.token, "[REDACTED_EPHEMERAL_TOKEN]")
        if isinstance(value, dict):
            return {self._redact(key): self._redact(item) for key, item in value.items()}
        if isinstance(value, list):
            return [self._redact(item) for item in value]
        return value

    def _record(self, event, stream, text):
        if not text:
            return
        encoded = str(text).encode("utf-8")
        with self._lock:
            remaining = max(0, self._log_limit - event["logBytes"])
            retained = encoded[:remaining]
            event["logBytes"] += len(retained)
            event["logBytesDropped"] += len(encoded) - len(retained)
            if retained:
                event["logs"].append({"stream": stream, "text": retained.decode("utf-8", errors="replace")})

    def _audited_handler(self, handler):
        def execute(context, parameters):
            inputs = {}
            for name, path in context.input_files.items():
                content = path.read_bytes()
                inputs[name] = {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                if path.suffix.lower() in (".v", ".sv", ".vhd", ".vhdl", ".pcf", ".lpf"):
                    try:
                        inputs[name]["text"] = content.decode("utf-8", errors="strict")
                    except UnicodeDecodeError:
                        inputs[name]["textEncoding"] = "INVALID_UTF8"
            event = {"operation": "fpga-synthesize", "jobId": context.working_directory.name,
                     "started": _now(), "parameters": deepcopy(parameters),
                     "inputs": inputs,
                     "logs": [], "logBytes": 0, "logBytesDropped": 0}
            with self._lock:
                self._events.append(event)
                hold = self.hold_seconds
            try:
                # A fast toolchain can finish before the UI shows the job: keep it cancellable for the cancel case.
                release = time.monotonic() + hold
                while time.monotonic() < release:
                    context.check_cancelled()
                    time.sleep(0.05)
                result = handler(_AuditedContext(context, event, self._record), parameters)
                with self._lock:
                    event.update(status="RETURNED", result=_artifact_metadata(result))
                    self._artifacts[event["jobId"]] = {
                        artifact["name"]: base64.b64decode(artifact["base64"], validate=True)
                        for artifact in result.get("artifacts", [])}
                return result
            except Exception as error:
                with self._lock:
                    event.update(status="RAISED", error={"code": getattr(error, "code", type(error).__name__),
                                                          "message": str(error)})
                raise
            finally:
                with self._lock:
                    event["finished"] = _now()
        return execute

    def _audited_request(self, action, original):
        def invoke(value):
            event = {"action": action, "started": _now()}
            if action == "cancel":
                event["jobId"] = value
            elif isinstance(value, dict):
                inputs = value.get("inputs")
                event.update(operation=value.get("operation"), parameters=deepcopy(value.get("parameters", {})),
                             requestId=value.get("requestId"),
                             inputs=[{key: item.get(key) for key in ("name", "sha256")}
                                     for item in inputs if isinstance(item, dict)] if isinstance(inputs, list) else [])
            with self._lock:
                self._requests.append(event)
            try:
                result = original(value)
                with self._lock:
                    event["status"] = "RETURNED"
                    if "planToken" in result:
                        event["planTokenSha256"] = hashlib.sha256(result["planToken"].encode()).hexdigest()
                    if "jobId" in result:
                        self._job_ids.add(result["jobId"])
                        event.update(jobId=result["jobId"], observedState=result["state"])
                return result
            except Exception as error:
                with self._lock:
                    event.update(status="RAISED", error={"code": getattr(error, "code", type(error).__name__),
                                                          "message": str(error)})
                raise
            finally:
                with self._lock:
                    event["finished"] = _now()
        return invoke

    def audit(self):
        """Snapshot actual terminal states; a cancel request alone is not success."""
        with self._lock:
            jobs = []
            for job_id in sorted(self._job_ids):
                snapshot = self.server.service.snapshot(job_id)
                jobs.append({"jobId": job_id, "operation": snapshot["operation"], "state": snapshot["state"],
                             "result": _artifact_metadata(snapshot["result"]), "error": snapshot["error"],
                             "logs": snapshot["logs"]})
            return self._redact({"host": "127.0.0.1", "endpoint": self.endpoint,
                                 "operations": ["fpga-synthesize"], "programmingRegistered": False,
                                 "tools": deepcopy(self.tool_identities), "requests": deepcopy(self._requests),
                                 "events": deepcopy(self._events), "jobs": jobs})

    def export_artifacts(self, directory):
        """Save actual bytes for succeeded jobs to a fresh evidence directory."""
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=False)
        files = []
        with self._lock:
            for job in self.audit()["jobs"]:
                if job["state"] != "succeeded":
                    continue
                target = directory / job["jobId"]
                target.mkdir()
                for name, content in self._artifacts.get(job["jobId"], {}).items():
                    path = target / safe_name(name)
                    path.write_bytes(content)
                    files.append({"jobId": job["jobId"], "name": name,
                                  "path": str(path.relative_to(directory)), "bytes": len(content),
                                  "sha256": hashlib.sha256(content).hexdigest()})
        (directory / "manifest.json").write_text(json.dumps(files, indent=2) + "\n")
        return files

    def close(self):
        if self._closed:
            return
        self._closed = True
        try:
            self.server.shutdown()
            self.server.server_close()
        finally:
            try:
                self.server.service.close()
            finally:
                self.thread.join(timeout=5)
        if self.thread.is_alive():
            raise RuntimeError("The native FPGA host thread did not stop")

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
