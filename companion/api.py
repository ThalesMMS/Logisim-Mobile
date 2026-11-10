"""Handler API for authenticated companion jobs."""
from dataclasses import dataclass, field
import base64
import hashlib
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import time
from typing import Callable

from companion import host_isolation


class JobFailure(Exception):
    def __init__(self, code: str, message: str, details=None):
        super().__init__(message)
        self.code, self.message, self.details = code, message, details

    def as_dict(self):
        return {"code": self.code, "message": self.message, "details": self.details}


def safe_name(name: str) -> str:
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name):
        raise JobFailure("invalid-name", "Use a plain file name with letters, digits, dots, underscores or hyphens.")
    return name


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True)
class Operation:
    name: str
    handler: Callable
    required_tools: tuple[str, ...] = ()
    description: str = ""
    requires_confirmation: bool = False
    tool_alternatives: tuple[tuple[str, ...], ...] = ()
    program_targets: tuple[str, ...] = ()
    isolated: bool = False
    program_target_profiles: dict = field(default_factory=dict)
    flash_program_targets: tuple[str, ...] = ()
    validate_parameters: Callable[[dict], None] | None = None
    accepts_input: bool = False
    isolation_profile: str = host_isolation.PROFILE_NAME

    def __post_init__(self):
        if self.isolated and not self.requires_confirmation:
            raise ValueError("Host-isolated operations require an approved plan.")
        if self.accepts_input and not self.isolated:
            raise ValueError("Live inputs require a leased and approved worker.")
        if self.isolation_profile != host_isolation.PROFILE_NAME and not self.isolated:
            raise ValueError("A custom sandbox profile requires isolated execution.")


class OperationRegistry:
    def __init__(self):
        self.operations: dict[str, Operation] = {}
        self._shutdown = []

    def register(self, operation: Operation):
        if not re.fullmatch(r"[a-z][a-z0-9.-]{0,63}", operation.name):
            raise ValueError("Invalid operation name")
        if operation.name in self.operations:
            raise ValueError(f"Duplicate operation: {operation.name}")
        self.operations[operation.name] = operation

    def on_shutdown(self, callback: Callable):
        self._shutdown.append(callback)

    def close(self):
        callbacks, self._shutdown = self._shutdown, []
        failure = None
        for callback in reversed(callbacks):
            try:
                callback()
            except Exception as error:
                if failure is None:
                    failure = error
        if failure is not None:
            raise failure


class JobContext:
    def __init__(self, working_directory: Path, input_files: dict[str, Path], tool_paths: dict[str, str],
                 cancelled: threading.Event, timeout_seconds: float = 60, emit: Callable | None = None,
                 user_confirmed: bool = False, max_output_bytes: int = 2 * 1024 * 1024,
                 memory_limit_bytes: int = 2 * 1024 * 1024 * 1024,
                 isolation_lease: host_isolation.Lease | None = None, publish_live: Callable | None = None,
                 isolation_profile: str = host_isolation.PROFILE_NAME, tk_session=None):
        self.working_directory = working_directory.resolve()
        self.output_directory = self.working_directory / "outputs"
        self.output_directory.mkdir(exist_ok=True)
        self.input_files = dict(input_files)
        self.cancelled = cancelled
        self.user_confirmed = user_confirmed
        self._isolation_lease = isolation_lease
        self._isolation_profile = isolation_profile
        self.tk_session = tk_session
        self.max_output_bytes = max_output_bytes
        self.memory_limit_bytes = memory_limit_bytes
        self._tools = dict(tool_paths)
        self._deadline = time.monotonic() + timeout_seconds
        self._emit = emit or (lambda stream, text: None)
        self._command_sequence = 0
        self._publish_live = publish_live
        self._live_input = None

    def check_cancelled(self):
        if self.cancelled.is_set():
            raise JobFailure("cancelled", "The job was cancelled.")
        if self._isolation_lease is not None and self._isolation_lease.expired():
            raise JobFailure("lease-expired", "The client stopped renewing the isolated job.")
        if time.monotonic() >= self._deadline:
            raise JobFailure("timeout", "The job exceeded its time limit.")

    def bind_live_input(self, receive: Callable):
        if self._publish_live is None or self._isolation_lease is None or not self.user_confirmed or self._live_input is not None:
            raise JobFailure("live-input-unavailable", "This job has no approved live input.")
        self._live_input = receive

    def accept_live_input(self, value):
        self.check_cancelled()
        receive = self._live_input
        if receive is None:
            raise JobFailure("live-input-not-ready", "The job has not opened live input.")
        return receive(value)

    def publish_live(self, value):
        self.check_cancelled()
        if self._publish_live is None:
            raise JobFailure("live-input-unavailable", "This job has no approved live input.")
        self._publish_live(value)

    def input_path(self, name: str) -> Path:
        safe_name(name)
        try:
            return self.input_files[name]
        except KeyError:
            raise JobFailure("missing-input", f"Input not provided: {name}") from None

    def output_path(self, name: str) -> Path:
        return self.output_directory / safe_name(name)

    def tool(self, name: str) -> str:
        path = self._tools.get(name)
        if not path:
            raise JobFailure("missing-tool", f"The host has not configured the tool: {name}")
        return path

    def emit(self, stream: str, text: str):
        self._emit(stream, str(text))

    @staticmethod
    def _wait_for_linux_group_exit(pgid):
        deadline = time.monotonic() + 0.5
        while True:
            try:
                os.killpg(pgid, 0)
            except ProcessLookupError:
                return
            try:
                snapshot = subprocess.run(["/bin/ps", "-axo", "pgid=,stat="], capture_output=True,
                                          text=True, check=True, timeout=max(0.01, deadline - time.monotonic()),
                                          env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"})
            except (OSError, subprocess.SubprocessError) as error:
                raise JobFailure("termination-failed", "Unable to verify tool process-group exit.") from error
            states = [fields[1][0] for line in snapshot.stdout.splitlines()
                      if len(fields := line.split()) == 2 and fields[0] == str(pgid)]
            # An orphan already marked dead can remain visible until PID 1 reaps it.
            if states and all(state in {"X", "Z"} for state in states):
                return
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise JobFailure("termination-failed", "Tool process-group exit was not confirmed.")
            time.sleep(min(0.01, remaining))

    @staticmethod
    def _terminate(process):
        def reap():
            try:
                process.wait(timeout=0.5)
            except subprocess.TimeoutExpired as error:
                raise JobFailure("termination-failed", "Unable to reap the tool process.") from error

        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGTERM)
            else:
                process.terminate()
            try:
                process.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                pass
            # A leader can exit while children still hold the process group.
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            elif process.poll() is None:
                process.kill()
            reap()
            if sys.platform == "linux":
                JobContext._wait_for_linux_group_exit(process.pid)
        except ProcessLookupError:
            reap()
        except PermissionError as error:
            # Darwin reports EPERM while a group contains only exiting zombies.
            # Reap the leader even if its exit was not yet observable at killpg.
            # Require confirmed group absence before considering cleanup complete.
            if os.name == "posix":
                deadline = time.monotonic() + 0.5
                try:
                    process.wait(timeout=max(0, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    pass
                else:
                    while True:
                        try:
                            os.killpg(process.pid, 0)
                        except ProcessLookupError:
                            return
                        except PermissionError:
                            pass
                        except OSError:
                            break
                        if time.monotonic() >= deadline:
                            break
                        time.sleep(0.01)
            raise JobFailure("termination-failed", "Unable to terminate the tool process group.") from error

    def run(self, argv, timeout_seconds: float | None = None, stdin: str | None = None, *,
            java_home: Path | None = None, pump: Callable | None = None, log_stdout: bool = True) -> CommandResult:
        self.check_cancelled()
        if not isinstance(argv, (list, tuple)) or not argv or any(not isinstance(value, str) or "\0" in value for value in argv):
            raise JobFailure("invalid-command", "Handlers must supply an argument list.")
        executable = str(Path(argv[0]).resolve())
        if executable not in {str(Path(value).resolve()) for value in self._tools.values()}:
            raise JobFailure("unconfigured-tool", "The executable was not configured by the host operator.")
        cwd = self.working_directory
        if self._isolation_lease is not None:
            if not self.user_confirmed:
                raise JobFailure("confirmation-required", "Isolated execution requires an approved plan.")
            try:
                argv = host_isolation.command_for(self._isolation_profile, executable, argv[1:], cwd,
                                                  self.input_files.values(), java_home,
                                                  self.tk_session.path if self.tk_session else None)
            except (ValueError, OSError) as error:
                raise JobFailure("sandbox-unavailable", "The host could not apply its execution sandbox.") from error
            cwd = Path("/")
        elif java_home is not None:
            raise JobFailure("sandbox-unavailable", "Runtime grants require an isolated job.")
        if pump is not None and (stdin is not None or self._live_input is None):
            raise JobFailure("live-input-unavailable", "A supervised input stream requires an approved live input.")
        self._command_sequence += 1
        prefix = self.working_directory / f"command-{self._command_sequence}"
        stdout_path, stderr_path, stdin_path = [prefix.with_suffix(suffix) for suffix in (".stdout", ".stderr", ".stdin")]
        if self.tk_session:
            self.tk_session.attach_output(stdout_path)
        stdin_path.write_text(stdin or "")
        deadline = min(self._deadline, time.monotonic() + timeout_seconds) if timeout_seconds is not None else self._deadline
        environment = {"PATH": os.environ.get("PATH", ""), "HOME": str(self.working_directory),
                       "TMPDIR": str(self.working_directory), "LANG": "C", "LC_ALL": "C"}
        if self._isolation_lease is not None:
            environment["PATH"] = "/usr/bin:/bin"
        if os.name != "posix":
            raise JobFailure("resource-limits-unavailable", "External tools require the supported POSIX host resource limits.")
        command = [sys.executable, str(Path(__file__).with_name("limits.py")),
                   "--cpu-seconds", str(max(1, int(deadline - time.monotonic()) + 1)),
                   "--memory-bytes", str(self.memory_limit_bytes), "--file-bytes", str(64 * 1024 * 1024),
                   "--", *argv]
        with stdin_path.open("rb") as source, stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            try:
                process = subprocess.Popen(command, cwd=cwd, env=environment,
                                           stdin=subprocess.PIPE if pump is not None else source,
                                           stdout=stdout, stderr=stderr,
                                           start_new_session=os.name == "posix")
            except OSError as error:
                raise JobFailure("launch-failed", str(error)) from error
            try:
                if pump is not None:
                    os.set_blocking(process.stdin.fileno(), False)
                next_memory_check = 0.0
                while process.poll() is None:
                    self.check_cancelled()
                    if time.monotonic() >= deadline:
                        raise JobFailure("timeout", "The command exceeded its time limit.")
                    if stdout_path.stat().st_size + stderr_path.stat().st_size > self.max_output_bytes:
                        raise JobFailure("output-limit", "The command exceeded the output limit.")
                    if time.monotonic() >= next_memory_check:
                        next_memory_check = time.monotonic() + 0.1
                        try:
                            memory = subprocess.run(["/bin/ps", "-axo", "pgid=,rss="], capture_output=True, text=True,
                                                    timeout=min(2, max(0.01, deadline - time.monotonic())))
                        except (subprocess.TimeoutExpired, OSError) as error:
                            self.check_cancelled()
                            if time.monotonic() >= deadline:
                                raise JobFailure("timeout", "The command exceeded its time limit.") from error
                            raise JobFailure("resource-limits-unavailable", "Unable to inspect the tool process-group memory.") from error
                        if memory.returncode != 0:
                            raise JobFailure("resource-limits-unavailable", "Unable to inspect the tool process-group memory.")
                        rss = sum(int(fields[1]) * 1024 for line in memory.stdout.splitlines()
                                  if len(fields := line.split()) == 2 and fields[0] == str(process.pid))
                        if rss > self.memory_limit_bytes:
                            raise JobFailure("memory-limit", "The tool process group exceeded its supervised resident-memory limit.")
                    if pump is not None:
                        pump(process.stdin, stdout_path)
                    time.sleep(0.02)
                if stdout_path.stat().st_size + stderr_path.stat().st_size > self.max_output_bytes:
                    raise JobFailure("output-limit", "The command exceeded the output limit.")
                if pump is not None:
                    pump(None, stdout_path)
            finally:
                try:
                    self._terminate(process)
                finally:
                    if process.stdin is not None:
                        process.stdin.close()
        # A descendant can write after the command leader exits. Recheck the
        # streams after the group is terminated, before loading them in memory.
        if stdout_path.stat().st_size + stderr_path.stat().st_size > self.max_output_bytes:
            raise JobFailure("output-limit", "The command exceeded the output limit.")
        result = CommandResult(process.returncode, stdout_path.read_text(errors="replace"), stderr_path.read_text(errors="replace"))
        if result.returncode == 125 and result.stderr.startswith("LOGISIM_RESOURCE_LIMIT_ERROR:"):
            raise JobFailure("resource-limits-unavailable", result.stderr.strip())
        if result.returncode in (-signal.SIGXCPU, -signal.SIGXFSZ):
            raise JobFailure("resource-limit", "The command exceeded a CPU or file-size resource limit.")
        if log_stdout and result.stdout:
            self.emit("stdout", result.stdout)
        if result.stderr:
            self.emit("stderr", result.stderr)
        if self._isolation_lease is not None and result.returncode != 0:
            raise JobFailure("isolated-command-failed", "The isolated worker could not start or exited unsuccessfully.",
                             {"returncode": result.returncode})
        return result

    def artifact(self, path: Path, name: str | None = None) -> dict:
        resolved = Path(path).resolve()
        if not resolved.is_relative_to(self.working_directory) or not resolved.is_file():
            raise JobFailure("invalid-artifact", "Artifacts must be regular files inside the job directory.")
        if resolved.stat().st_size > self.max_output_bytes:
            raise JobFailure("output-limit", "The artifact exceeded the output limit.")
        data = resolved.read_bytes()
        return {"name": safe_name(name or resolved.name), "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(), "base64": base64.b64encode(data).decode("ascii")}
