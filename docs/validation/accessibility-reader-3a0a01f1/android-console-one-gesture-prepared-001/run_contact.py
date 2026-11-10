"""Prepared one-contact executor. Default CLI mode makes no ADB calls.

No lease is shipped. Root must supply an explicit, current lease after review.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass, field
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import threading
import time
import types

ADB = "/Users/thales/Library/Android/sdk/platform-tools/adb"
SERIAL = "emulator-5556"
DEVICE = "/dev/input/event1"
DEVICE_NAME = "virtio_input_multi_touch_1"
PRODUCT_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
TALKBACK_COMPONENT = "com.google.android.marvin.talkback/.TalkBackService"
BINDING_HELPER = Path(__file__).with_name("check_binding.py")
BINDING_HELPER_SHA256 = "b91247f094e6d8d8e8ed7a64f6ca655fef2d49f5569e7c325b3988cdc6530fe3"
DEFAULT_CONTACT = Path(__file__).with_name("contact.py")
CLAIM_DIRECTORY = Path(f"/tmp/logisim-mobile-a11y-contact-claims-{os.getuid()}")
MONITOR_ARGV = (ADB, "-s", SERIAL, "shell", "-T", "timeout", "-s", "INT", "-k", "1", "8",
                "getevent", "-t", "-v6", DEVICE)
READONLY = (
    ("reader-before", (ADB, "-s", SERIAL, "shell", "dumpsys", "accessibility")),
    ("system-server-pid", (ADB, "-s", SERIAL, "shell", "pidof", "system_server")),
    ("talkback-service", (ADB, "-s", SERIAL, "shell", "dumpsys", "activity", "services", TALKBACK_COMPONENT)),
    ("caps", (ADB, "-s", SERIAL, "shell", "getevent", "-p", DEVICE)),
    ("size", (ADB, "-s", SERIAL, "shell", "wm", "size")),
    ("display", (ADB, "-s", SERIAL, "shell", "dumpsys", "display")),
    ("reader-after", (ADB, "-s", SERIAL, "shell", "dumpsys", "accessibility")),
)
LEASE_FIELDS = {"schema_version", "issuer", "scope", "serial", "product_source", "lease_id",
                "contact_sha256", "wrapper_sha256", "plan_sha256", "not_before_unix",
                "expires_unix", "max_contacts"}


class Refused(Exception):
    """Only static reason codes are emitted; external exception text is not."""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refused("DUPLICATE_LEASE_KEY")
        result[key] = value
    return result


def parse_lease(raw, now, *, contact_hash, wrapper_hash, plan_hash):
    if len(raw) > 16384:
        raise Refused("LEASE_TOO_LARGE")
    try:
        lease = json.loads(raw, object_pairs_hook=unique_object)
    except Refused:
        raise
    except Exception:
        raise Refused("INVALID_LEASE_JSON") from None
    if not isinstance(lease, dict) or set(lease) != LEASE_FIELDS:
        raise Refused("LEASE_SCHEMA_MISMATCH")
    if (type(lease["schema_version"]) is not int or lease["schema_version"] != 1
            or lease["issuer"] != "/root" or lease["scope"] != "one-console-contact"
            or lease["serial"] != SERIAL or lease["product_source"] != PRODUCT_SOURCE
            or type(lease["max_contacts"]) is not int or lease["max_contacts"] != 1
            or not isinstance(lease["lease_id"], str)
            or not re.fullmatch(r"[A-Za-z0-9._-]{1,96}", lease["lease_id"])):
        raise Refused("LEASE_SCOPE_MISMATCH")
    for key, expected in (("contact_sha256", contact_hash), ("wrapper_sha256", wrapper_hash),
                          ("plan_sha256", plan_hash)):
        if lease[key] != expected:
            raise Refused("LEASE_ARTIFACT_MISMATCH")
    for key in ("not_before_unix", "expires_unix"):
        value = lease[key]
        if type(value) not in (int, float) or not math.isfinite(value):
            raise Refused("INVALID_LEASE_TIME")
    if not (lease["not_before_unix"] <= now < lease["expires_unix"]
            and 0 < lease["expires_unix"] - lease["not_before_unix"] <= 120):
        raise Refused("LEASE_NOT_CURRENT")
    return lease


def current_user_zero(text):
    if re.findall(r"(?m)^currentUserId=(\d+)\s*$", text) != ["0"]:
        raise Refused("CURRENT_ANDROID_USER_NOT_ZERO_OR_UNKNOWN")


def system_pid(text):
    if not re.fullmatch(r"[1-9]\d{0,9}", text.strip()) or int(text.strip()) > 2147483647:
        raise Refused("SYSTEM_SERVER_PID_NOT_UNIQUE_OR_VALID")
    return int(text.strip())


def load_binding_helper(raw):
    if not re.fullmatch(r"[0-9a-f]{64}", BINDING_HELPER_SHA256) or digest(raw) != BINDING_HELPER_SHA256:
        raise Refused("BINDING_HELPER_NOT_PINNED_OR_CHANGED")
    name = "_root_binding_proof_" + BINDING_HELPER_SHA256[:12]
    module = types.ModuleType(name)
    sys.modules[name] = module
    exec(compile(raw, "<Root-pinned-binding-proof>", "exec"), module.__dict__)
    return module


def reader_proof(helper, activity, accessibility, pid):
    current_user_zero(accessibility)
    try:
        result = helper.check_cross_evidence(activity, accessibility, system_server_pid=pid, user_id=0)
    except helper.BindingError as error:
        raise Refused("READER_PROOF_" + error.code.upper()) from None
    if not isinstance(result, dict) or result.get("result") != "PASS_AMS_BINDING_AND_A11Y_STATE_ONLY":
        raise Refused("READER_PROOF_CONTRACT_CHANGED")
    return result


def numeric_caps(text):
    headers = re.findall(r"(?m)^add device \d+: (.+)$", text)
    if headers != [DEVICE] or re.findall(r'(?m)^\s*name:\s*"([^"]+)"\s*$', text) != [DEVICE_NAME]:
        raise Refused("TOUCH_DEVICE_MISMATCH")
    axes = {}
    for line in text.splitlines():
        match = re.fullmatch(r"\s*(?:ABS \(0003\):\s*)?([0-9a-fA-F]{4})\s*:\s*value (-?\d+), min (-?\d+), max (-?\d+),.*", line)
        if match:
            code = int(match[1], 16)
            if code in axes:
                raise Refused("DUPLICATE_NUMERIC_AXIS")
            axes[code] = tuple(int(match[i]) for i in (2, 3, 4))
    expected = {47: (0, 10), 53: (0, 32767), 54: (0, 32767), 55: (0, 15),
                57: (0, 65535), 58: (0, 1024)}
    if any(code not in axes or axes[code][1:] != bounds for code, bounds in expected.items()):
        raise Refused("NUMERIC_CAPABILITY_MISMATCH")
    if axes[47][0] != 0 or axes[58][0] != 0:
        raise Refused("SELECTED_SLOT_OR_PRESSURE_MISMATCH")
    return {"device": DEVICE, "name": DEVICE_NAME, "numeric_axes": axes,
            "selected_slot": 0, "selected_pressure": 0,
            "tracking_axis_value": axes[57][0], "initial_tracking_activity": "UNKNOWN",
            "global_absence_of_contacts_proven": False}


def display_state(size, display):
    if size.strip() != "Physical size: 1080x2400":
        raise Refused("FRAMEBUFFER_SIZE_MISMATCH")
    views = re.findall(r"DisplayViewport\{([^}]+)\}", display)
    active = [v for v in views if re.search(r"\bdisplayId=0(?:,|\s)", v)
              and "type=INTERNAL" in v and "isActive=true" in v]
    if len(active) != 1:
        raise Refused("ACTIVE_DISPLAY_UNRECOGNIZED")
    v = active[0]
    if not ("orientation=0," in v and "deviceWidth=1080" in v and "deviceHeight=2400" in v
            and re.search(r"logicalFrame=Rect\(0, 0 - 1080, 2400\)", v)
            and re.search(r"physicalFrame=Rect\(0, 0 - 1080, 2400\)", v)):
        raise Refused("DISPLAY_GEOMETRY_MISMATCH")
    return {"display_id": 0, "width": 1080, "height": 2400, "rotation": 0}


@dataclass
class StopState:
    requested: bool = False
    signals: list[int] = field(default_factory=list)

    def receive(self, signum, _frame=None):
        self.requested = True
        self.signals.append(signum)


@dataclass
class Record:
    label: str
    argv: tuple[str, ...]
    start: float | None
    end: float | None
    returncode: int | None
    stdout: bytes
    stderr: bytes
    error_type: str | None = None
    output_may_be_partial: bool = False
    clock_error: str | None = None


def invoke(runtime, label, argv, timeout, start):
    # In particular for UP, this function's first callback is the subprocess.
    result, error = None, None
    try:
        result = runtime.run(tuple(argv), timeout)
    except BaseException as caught:
        error = caught
    clock_error = None
    try:
        end = runtime.monotonic()
    except BaseException as caught:
        end, clock_error = None, type(caught).__name__
    if error is not None:
        return Record(label, tuple(argv), start, end, None,
                      getattr(error, "stdout", None) or b"", getattr(error, "stderr", None) or b"",
                      type(error).__name__, True, clock_error)
    return Record(label, tuple(argv), start, end, result.returncode,
                  result.stdout, result.stderr, clock_error=clock_error)


class PassiveMonitor:
    """Owns only this host adb child; never kills a process or sends device input."""
    def __init__(self, runtime):
        self.runtime = runtime
        self.stdout = bytearray()
        self.stderr = bytearray()
        self.capture_error = None
        self.ready = False
        self.lock = threading.Lock()
        # Allocate fallible Python helpers before creating the owned process.
        self.threads = [threading.Thread(target=self._pump, args=("stdout", self.stdout, True), daemon=True),
                        threading.Thread(target=self._pump, args=("stderr", self.stderr, False), daemon=True)]
        self.start = runtime.monotonic()
        self.process = subprocess.Popen(MONITOR_ARGV, stdin=subprocess.DEVNULL,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            for thread in self.threads:
                thread.start()
        except BaseException as error:
            # Retain the owned process so the outer finally can observe its end.
            self.capture_error = type(error).__name__

    def _pump(self, channel, target, stdout):
        stream = None
        try:
            stream = getattr(self.process, channel)
            for line in iter(lambda: stream.readline(65537), b""):
                with self.lock:
                    if len(target) + len(line) > 2097152:
                        self.capture_error = "MONITOR_CAPTURE_LIMIT"
                    elif not self.capture_error:
                        target.extend(line)
                    if stdout and not self.capture_error:
                        text = bytes(target).decode("utf-8", errors="replace")
                        self.ready = (re.findall(r"(?m)^add device \d+: (.+?)\r?$", text) == [DEVICE]
                                      and re.findall(r'(?m)^\s*name:\s*"([^"]+)"\s*$', text) == [DEVICE_NAME])
        except BaseException as error:
            self.capture_error = type(error).__name__
        finally:
            try:
                if stream is not None:
                    stream.close()
            except BaseException as error:
                self.capture_error = type(error).__name__

    def await_ready(self, stop):
        deadline = min(self.start + 2, self.start + 9)
        while self.runtime.monotonic() < deadline:
            if stop.requested or self.capture_error or self.process.poll() is not None:
                return False
            if self.ready:
                return True
            self.runtime.sleep(0.01)
        return False

    def healthy(self):
        return self.ready and not self.capture_error and self.process.poll() is None

    def finish(self):
        remaining = max(0, self.start + 9 - self.runtime.monotonic())
        try:
            self.process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            pass
        for thread in self.threads:
            if thread.ident is not None:
                thread.join(timeout=max(0, self.start + 9 - self.runtime.monotonic()))
        terminal = self.process.poll() is not None and all(not t.is_alive() for t in self.threads)
        return {"argv": MONITOR_ARGV, "host_pid": self.process.pid, "start": self.start,
                "end": self.runtime.monotonic(), "returncode": self.process.poll(), "ready": self.ready,
                "terminal_observed": terminal, "capture_error": self.capture_error,
                "stdout": bytes(self.stdout), "stderr": bytes(self.stderr),
                "capture_complete": terminal and not self.capture_error,
                "wrapper_forced_stop_attempted": False,
                "guest_timeout_policy": {"duration_seconds": 8, "signal": "INT", "kill_after_seconds": 1},
                "guest_timeout_execution_proven": False}


class LiveRuntime:
    wall = staticmethod(time.time)
    monotonic = staticmethod(time.monotonic)
    sleep = staticmethod(time.sleep)

    @staticmethod
    def read(path):
        return Path(path).read_bytes()

    @staticmethod
    def run(argv, timeout):
        return subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=timeout, check=False)

    @staticmethod
    def claim(lease_id, lease_hash):
        CLAIM_DIRECTORY.mkdir(mode=0o700, exist_ok=True)
        info = CLAIM_DIRECTORY.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise Refused("CLAIM_DIRECTORY_NOT_PRIVATE")
        identity = digest(("/root\0one-console-contact\0" + SERIAL + "\0" + lease_id).encode("ascii"))
        marker = CLAIM_DIRECTORY / (identity + ".claimed")
        fd = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(lease_hash + "\n")

    @staticmethod
    def reserve(output):
        Path(output).mkdir(mode=0o700, parents=False, exist_ok=False)

    def start_monitor(self):
        return PassiveMonitor(self)

    @staticmethod
    @contextmanager
    def signals(stop):
        previous = {s: signal.getsignal(s) for s in (signal.SIGINT, signal.SIGTERM)}
        try:
            for s in previous:
                signal.signal(s, stop.receive)
            yield
        finally:
            for s, handler in previous.items():
                signal.signal(s, handler)

    @staticmethod
    def persist(output, records, monitor, receipt):
        output = Path(output)
        for index, record in enumerate(records):
            prefix = f"{index:03d}-{record.label}"
            (output / (prefix + ".stdout.bin")).write_bytes(record.stdout)
            (output / (prefix + ".stderr.bin")).write_bytes(record.stderr)
        if monitor:
            (output / "monitor.stdout.bin").write_bytes(monitor["stdout"])
            (output / "monitor.stderr.bin").write_bytes(monitor["stderr"])
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")


def load_contact(raw):
    name = "_root_prepared_contact_" + digest(raw)[:12]
    module = types.ModuleType(name)
    sys.modules[name] = module
    exec(compile(raw, "<Root-hash-pinned-contact>", "exec"), module.__dict__)
    sequence = module.frames()
    module.validate(sequence)
    if [frame.offset_ms for frame in sequence] != list(range(0, 161, 20)) + [180]:
        raise Refused("UNEXPECTED_CONTACT_SCHEDULE")
    commands = {}
    for frame in sequence:
        argv = tuple(frame.argv())
        if argv[:6] != (ADB, "-s", SERIAL, "emu", "event", "send") or argv[6:] != tuple(
                f"{kind}:{code}:{value}" for kind, code, value in frame.events):
            raise Refused("UNEXPECTED_CONTACT_COMMAND")
        if frame.name in commands:
            raise Refused("DUPLICATE_FRAME_NAME")
        commands[frame.name] = argv
    return module, sequence, commands


def record_info(record):
    return {"label": record.label, "argv": record.argv, "start": record.start, "end": record.end,
            "returncode": record.returncode, "error_type": record.error_type,
            "clock_error": record.clock_error,
            "output_may_be_partial": record.output_may_be_partial,
            "stdout_bytes": len(record.stdout), "stdout_sha256": digest(record.stdout),
            "stderr_bytes": len(record.stderr), "stderr_sha256": digest(record.stderr)}


def execute(*, lease_path, plan_path, contact_path=DEFAULT_CONTACT, output,
            runtime=None, on_record=lambda record: None):
    runtime = runtime or LiveRuntime()
    stop = StopState()
    records, monitor, monitor_result = [], None, None
    state = {"down_possible": False, "up_attempted": False, "up_acknowledged": False}
    receipt = {"status": "NOT_RUN", "reader_qualified": False, "kernel_close_proven": False,
               "global_absence_of_contacts_proven": False, "initial_tracking_activity": "UNKNOWN",
               "lease_return_allowed": False, "output_reserved": False}
    contact_result, error_code = None, None
    try:
        raw_lease = runtime.read(lease_path)
        raw_contact = runtime.read(contact_path)
        raw_plan = runtime.read(plan_path)
        raw_wrapper = runtime.read(Path(__file__))
        lease = parse_lease(raw_lease, runtime.wall(), contact_hash=digest(raw_contact),
                            wrapper_hash=digest(raw_wrapper), plan_hash=digest(raw_plan))
        binding_helper = load_binding_helper(runtime.read(BINDING_HELPER))
        module, sequence, commands = load_contact(raw_contact)
        runtime.reserve(output)
        receipt["output_reserved"] = True
        runtime.claim(lease["lease_id"], digest(raw_lease))
        receipt["lease"] = {"id": lease["lease_id"], "sha256": digest(raw_lease),
                            "product_source": PRODUCT_SOURCE, "contact_sha256": digest(raw_contact),
                            "wrapper_sha256": digest(raw_wrapper), "plan_sha256": digest(raw_plan),
                            "binding_helper_sha256": BINDING_HELPER_SHA256}
        preflight = {}
        times = []

        def allowed():
            now = runtime.wall()
            if not lease["not_before_unix"] <= now < lease["expires_unix"]:
                raise Refused("LEASE_EXPIRED")
            if stop.requested:
                raise Refused("CANCELLED")

        def readonly(label, argv):
            allowed()
            record = invoke(runtime, label, argv, 2, runtime.monotonic())
            records.append(record)
            on_record(record)
            if record.error_type or record.clock_error or record.returncode != 0 or record.stderr:
                raise Refused("READONLY_COMMAND_FAILED")
            times.append(record.end)
            return record.stdout.decode("utf-8", errors="strict")

        def wire(frame):
            start = None
            if frame.name != "up":
                allowed()
                if not monitor.healthy():
                    raise Refused("MONITOR_NOT_RUNNING")
                if runtime.monotonic() - min(times) > 3:
                    raise Refused("PREFLIGHT_NOT_FRESH")
                start = runtime.monotonic()
                # Final gate follows every monitor/clock callback. invoke's
                # first callback below is the subprocess, never a logger.
                allowed()
                if frame.name == "down":
                    state["down_possible"] = True
            else:
                if not state["down_possible"]:
                    raise Refused("NO_POSSIBLE_CONTACT_TO_RELEASE")
                state["up_attempted"] = True
            record = invoke(runtime, frame.name, commands[frame.name], 1, start)
            records.append(record)
            ack = not record.error_type and not record.stderr and module.console_ok(record.returncode, record.stdout)
            if frame.name == "up":
                state["up_acknowledged"] = ack
            on_record(record)
            if not ack or record.clock_error:
                raise Refused("CONSOLE_COMMAND_NOT_ACKNOWLEDGED")

        def cancellable_sleep(seconds):
            end = runtime.monotonic() + seconds
            while not stop.requested and runtime.monotonic() < end:
                runtime.sleep(min(0.01, end - runtime.monotonic()))

        with runtime.signals(stop):
            try:
                reader_before = readonly(*READONLY[0])
                current_user_zero(reader_before)
                pid = system_pid(readonly(*READONLY[1]))
                activity = readonly(*READONLY[2])
                preflight["reader_before"] = reader_proof(binding_helper, activity, reader_before, pid)
                preflight["system_server_pid"] = pid
                preflight["caps"] = numeric_caps(readonly(*READONLY[3]))
                size = readonly(*READONLY[4])
                preflight["display"] = display_state(size, readonly(*READONLY[5]))
                preflight["reader_after"] = reader_proof(binding_helper, activity, readonly(*READONLY[6]), pid)
                receipt["preflight"] = preflight
                allowed()
                monitor = runtime.start_monitor()
                if not monitor.await_ready(stop):
                    raise Refused("MONITOR_NOT_READY")
                allowed()
                contact_result = module.transmit(wire, runtime.monotonic, cancellable_sleep)
            finally:
                # This is only an emergency first attempt, never a retry of UP.
                # No receipt/callback/file write occurs before that attempt.
                if state["down_possible"] and not state["up_attempted"]:
                    try:
                        wire(sequence[-1])
                    except BaseException as release_error:
                        receipt["emergency_release_error"] = type(release_error).__name__
                if monitor is not None:
                    monitor_result = monitor.finish()
    except BaseException as error:
        error_code = str(error) if isinstance(error, Refused) else type(error).__name__
    receipt.update(state)
    receipt["error"] = error_code
    receipt["contact"] = contact_result
    receipt["signals_received"] = stop.signals
    receipt["records"] = [record_info(record) for record in records]
    if monitor_result:
        receipt["monitor"] = {key: value for key, value in monitor_result.items() if key not in ("stdout", "stderr")}
        for channel in ("stdout", "stderr"):
            data = monitor_result[channel]
            receipt["monitor"][channel + "_bytes"] = len(data)
            receipt["monitor"][channel + "_sha256"] = digest(data)
    module_error = contact_result and any(contact_result.get(key) for key in
                                          ("operation_error", "release_wait_error", "release_error"))
    if monitor is not None and (not monitor_result or not monitor_result["terminal_observed"]):
        receipt["status"] = "MONITOR_NON_TERMINAL_KEEP_LEASE"
    elif state["down_possible"]:
        receipt["status"] = "COMMAND_AND_KERNEL_CAPTURE_REQUIRES_REVIEW"
        if (error_code or module_error or not state["up_acknowledged"]
                or not monitor_result or not monitor_result.get("capture_complete", False)):
            receipt["status"] = "COMMAND_FAILED_KERNEL_CAPTURE_REQUIRES_REVIEW"
    else:
        receipt["status"] = "NOT_RUN_NO_CONTACT"
    receipt["raw_artifacts_complete"] = receipt["output_reserved"]
    if receipt["output_reserved"]:
        try:
            runtime.persist(output, records, monitor_result, receipt)
        except BaseException as error:
            receipt["artifact_write_error"] = type(error).__name__
            receipt["raw_artifacts_complete"] = False
    return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--lease")
    parser.add_argument("--plan")
    parser.add_argument("--contact", default=str(DEFAULT_CONTACT))
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    if not args.execute:
        result = {"status": "PREPARED_DESCRIPTION_ONLY", "adb_calls": 0, "contacts": 0,
                  "serial": SERIAL, "lease_fields": sorted(LEASE_FIELDS),
                  "reader_qualified": False, "lease_created": False}
    elif not all((args.lease, args.plan, args.output)):
        result = {"status": "NOT_RUN_MISSING_EXPLICIT_LEASE_PLAN_OR_OUTPUT", "adb_calls": 0,
                  "reader_qualified": False}
    else:
        result = execute(lease_path=args.lease, plan_path=args.plan, contact_path=args.contact,
                         output=args.output)
    print(json.dumps(result, indent=2))
    return 0 if result["status"] == "PREPARED_DESCRIPTION_ONLY" else 2


if __name__ == "__main__":
    raise SystemExit(main())
