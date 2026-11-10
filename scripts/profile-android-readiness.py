#!/usr/bin/env python3
"""Observe process/thread CPU and I/O without launching or changing an Android app."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time


def utc():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--initial-pid", type=int, required=True)
    parser.add_argument("--seconds", type=float, default=90)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.serial) or not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("Invalid device or package identifier")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_revision) or not re.fullmatch(r"[0-9a-f]{64}", args.expected_apk_sha256):
        parser.error("Provide full source revision and expected APK SHA-256")
    if not 1 <= args.initial_pid <= 2**31 - 1 or not 1 <= args.seconds <= 180 or not .2 <= args.interval <= 10:
        parser.error("Invalid PID or sampling duration/interval")
    args.output.mkdir(parents=True, exist_ok=False)
    commands = []

    def adb(*parts, timeout=10):
        command = [str(args.adb), "-s", args.serial, *parts]
        before = time.monotonic_ns()
        result = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
        commands.append({"argv": command, "startedMonotonicNs": before,
                         "durationNs": time.monotonic_ns() - before, "exitCode": result.returncode,
                         "stderr": result.stderr})
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return result.stdout

    def apk():
        paths = adb("shell", "pm", "path", args.package).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise ValueError("Expected exactly one installed APK")
        path = paths[0][8:]
        if not re.fullmatch(r"/[A-Za-z0-9._=/+~-]+", path):
            raise ValueError("Unsafe installed APK path")
        value = adb("shell", "sha256sum", path).split()[0]
        if value != args.expected_apk_sha256:
            raise ValueError("Installed APK hash differs from the declared artifact")
        return {"path": path, "sha256": value}

    receipt = {"schemaVersion": 1, "sourceRevision": args.source_revision,
               "sourceAttestation": "The operator declares the installed artifact's producing revision.",
               "startedAt": utc(), "observerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "arguments": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
               "commands": commands, "scope": "Passive process/thread diagnostics, not an app readiness assertion or device budget."}
    try:
        receipt["apkBefore"] = apk()
        initial = adb("shell", "pidof", args.package).split()
        if initial != [str(args.initial_pid)]:
            raise ValueError("Initial process differs from the operator's handoff")
        receipt["appIdentity"] = adb("shell", "run-as", args.package, "id").strip()
        receipt["deviceClock"] = adb("shell", "date", "+%s").strip()
        receipt["guestClockTicksPerSecond"] = int(adb("shell", "getconf", "CLK_TCK").strip())
        receipt["guestOnlineProcessors"] = int(adb("shell", "getconf", "_NPROCESSORS_ONLN").strip())
        receipt["hostLogicalProcessors"] = os.cpu_count()
        receipt["hostLoad"] = Path("/proc/loadavg").read_text() if Path("/proc/loadavg").exists() else subprocess.check_output(["uptime"], text=True).strip()
        # Package is validated above. This shell runs as the app's own UID and
        # reads only that package's current PID; it never sends a signal.
        probe = f'''pids=$(pidof {args.package}); echo PIDS:$pids
for p in $pids; do
 echo PROCESS:$p
 cat /proc/$p/stat /proc/$p/io /proc/$p/status /proc/$p/schedstat
 echo THREADS:$p
 cat /proc/$p/task/*/stat
done'''
        start = time.monotonic()
        samples = []
        while time.monotonic() - start < args.seconds:
            before = time.monotonic_ns()
            try:
                raw = adb("shell", "run-as", args.package, "sh", "-c", shlex.quote(probe))
                if not samples and f"PROCESS:{args.initial_pid}\n" not in raw:
                    raise ValueError("Initial process is missing from the passive probe")
                error = None
            except (RuntimeError, subprocess.TimeoutExpired) as failure:
                raw, error = "", str(failure)
            path = args.output / f"sample-{len(samples):04d}.txt"
            path.write_text(raw)
            samples.append({"file": path.name, "utc": utc(), "beforeMonotonicNs": before,
                            "afterMonotonicNs": time.monotonic_ns(), "error": error})
            (args.output / "samples.json").write_text(json.dumps(samples, indent=2) + "\n")
            time.sleep(max(0, args.interval - (time.monotonic_ns() - before) / 1e9))
        receipt["samples"] = len(samples)
        receipt["apkAfter"] = apk()
        receipt["status"] = "captured"
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as failure:
        receipt["status"], receipt["error"] = "failed", str(failure)
    finally:
        receipt["finishedAt"] = utc()
        (args.output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(receipt["status"], args.output / "receipt.json")
    return 0 if receipt["status"] == "captured" else 1


if __name__ == "__main__":
    raise SystemExit(main())
