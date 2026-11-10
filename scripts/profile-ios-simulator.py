#!/usr/bin/env python3
r"""Passively sample one already running iOS Simulator app. Never launches an app.

Example, after the owner installs and starts the exact artifact:
  python3 scripts/profile-ios-simulator.py --udid <UDID> --bundle-id dev.logisim.mobile \
    --expected-executable-sha256 <SHA256> --expected-bundle-sha256 <BUNDLE_SHA256> \
    --fresh-launch-confirmed --build-id <ARTIFACT_LABEL> \
    --build-commit <COMMIT> --base-commit <BASELINE_COMMIT> --scenario idle --circuit <CORPUS.circ> \
    --output-dir outputs/performance/ios-current-idle-01

CPU is host-process CPU, with 100% meaning one logical core. Memory is the
host process's RSS and physical footprint in bytes. These are simulator
measurements, not physical-device energy or whole-device resource usage.
"""
import argparse
import csv
import ctypes
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import plistlib
import re
import shlex
import stat
import statistics
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
XNU = "https://github.com/apple-oss-distributions/xnu/blob/f6217f891ac0bb64f3d375211650a4c1ff8ca1ea"


class CaptureError(Exception):
    pass


class RUsageInfoV0(ctypes.Structure):
    # ABI from the macOS SDK's sys/resource.h, flavor RUSAGE_INFO_V0.
    _fields_ = [("ri_uuid", ctypes.c_uint8 * 16)] + [(name, ctypes.c_uint64) for name in (
        "ri_user_time", "ri_system_time", "ri_pkg_idle_wkups", "ri_interrupt_wkups",
        "ri_pageins", "ri_wired_size", "ri_resident_size", "ri_phys_footprint",
        "ri_proc_start_abstime", "ri_proc_exit_abstime")]


class MachTimebase(ctypes.Structure):
    _fields_ = [("numer", ctypes.c_uint32), ("denom", ctypes.c_uint32)]


@dataclass(frozen=True)
class Snapshot:
    executable_uuid: str
    start_ticks: int
    exit_ticks: int
    user_ticks: int
    system_ticks: int
    rss_bytes: int
    footprint_bytes: int

    @property
    def cpu_ticks(self):
        return self.user_ticks + self.system_ticks


class Libproc:
    def __init__(self):
        self.lib = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
        self.lib.proc_pid_rusage.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.POINTER(RUsageInfoV0)]
        self.lib.proc_pid_rusage.restype = ctypes.c_int
        self.lib.proc_pidpath.argtypes = [ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
        self.lib.proc_pidpath.restype = ctypes.c_int
        system = ctypes.CDLL("/usr/lib/libSystem.B.dylib")
        system.mach_timebase_info.argtypes = [ctypes.POINTER(MachTimebase)]
        system.mach_timebase_info.restype = ctypes.c_int
        timebase = MachTimebase()
        if system.mach_timebase_info(ctypes.byref(timebase)) != 0 or not timebase.numer or not timebase.denom:
            raise CaptureError("mach_timebase_info failed")
        self.numer, self.denom = timebase.numer, timebase.denom

    def executable(self, pid):
        buffer = ctypes.create_string_buffer(4096)  # PROC_PIDPATHINFO_MAXSIZE
        if self.lib.proc_pidpath(pid, buffer, len(buffer)) <= 0:
            raise CaptureError(f"Cannot read executable for PID {pid}: {os.strerror(ctypes.get_errno())}")
        return Path(os.fsdecode(buffer.value)).resolve(strict=True)

    def snapshot(self, pid):
        value = RUsageInfoV0()
        if self.lib.proc_pid_rusage(pid, 0, ctypes.byref(value)) != 0:
            raise CaptureError(f"Cannot sample PID {pid}: {os.strerror(ctypes.get_errno())}")
        return Snapshot(str(uuid.UUID(bytes=bytes(value.ri_uuid))), value.ri_proc_start_abstime,
                        value.ri_proc_exit_abstime, value.ri_user_time, value.ri_system_time,
                        value.ri_resident_size, value.ri_phys_footprint)


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="microseconds")


def run_command(command, history, cwd=None):
    entry = {"argv": [str(part) for part in command], "started_utc": utc_now()}
    history.append(entry)
    start = time.monotonic_ns()
    try:
        result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=30)
        entry["returncode"] = result.returncode
    except (OSError, subprocess.TimeoutExpired) as error:
        entry["error"] = type(error).__name__
        raise
    finally:
        entry.update(ended_utc=utc_now(), elapsed_ns=time.monotonic_ns() - start)
    if result.returncode:
        raise CaptureError(f"Command failed: {shlex.join(entry['argv'])}: {result.stderr.strip()[:2048]}")
    return result.stdout.strip()


def resolve_commit(ref, history):
    return run_command(["git", "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"], history, ROOT)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cannot_enumerate_bundle(error):
    raise CaptureError(f"Cannot enumerate the complete app bundle: {error}") from error


def bundle_fingerprint(app):
    app = app.resolve(strict=True)
    if not app.is_dir() or app.suffix != ".app":
        raise CaptureError("The artifact must be an existing .app directory")
    entries = []
    for directory, dirs, files in os.walk(app, followlinks=False, onerror=cannot_enumerate_bundle):
        for name in sorted(dirs + files):
            path = Path(directory) / name
            relative = path.relative_to(app).as_posix()
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                try:
                    target = path.resolve(strict=True)
                except (OSError, RuntimeError) as error:
                    raise CaptureError(f"Bundle symlink cannot be resolved: {relative}") from error
                if not target.is_relative_to(app):
                    raise CaptureError(f"Bundle symlink escapes the artifact: {relative}")
                if not target.is_file() and not target.is_dir():
                    raise CaptureError(f"Bundle symlink targets a special file: {relative}")
                entry = {"path": relative, "symlink_target": target.relative_to(app).as_posix()}
                if target.is_file():
                    entry["sha256"] = sha256(target)
                entries.append(entry)
            elif stat.S_ISREG(mode):
                entries.append({"path": relative, "sha256": sha256(path)})
            elif not stat.S_ISDIR(mode):
                raise CaptureError(f"Bundle contains a special file: {relative}")
    entries.sort(key=lambda entry: entry["path"])
    canonical = json.dumps(entries, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    return hashlib.sha256(canonical).hexdigest(), entries


def executable_uuids(path, history, required=True):
    result = run_command(["xcrun", "dwarfdump", "--uuid", str(path)], history)
    values = re.findall(r"UUID: ([0-9A-Fa-f-]{36}) \(([^)]+)\)", result)
    if not values and required:
        raise CaptureError("The executable has no Mach-O UUID; cannot verify the loaded build")
    return {str(uuid.UUID(value)): arch for value, arch in values}


def read_app_info(app):
    info_path = (app / "Info.plist").resolve(strict=True)
    if not info_path.is_relative_to(app.resolve(strict=True)):
        raise CaptureError("The app Info.plist escapes its bundle")
    with info_path.open("rb") as source:
        info = plistlib.load(source)
    if not isinstance(info, dict):
        raise CaptureError("The app Info.plist is not a dictionary")
    executable = info.get("CFBundleExecutable")
    if not isinstance(executable, str) or not executable or executable in (".", "..") or Path(executable).name != executable:
        raise CaptureError("The app has no valid executable file name")
    if not isinstance(info.get("CFBundleIdentifier"), str) or not info["CFBundleIdentifier"]:
        raise CaptureError("The app has no bundle identifier")
    for key in ("CFBundleVersion", "CFBundleShortVersionString"):
        if key in info and not isinstance(info[key], str):
            raise CaptureError(f"The app has an invalid {key}")
    return info


def app_identity(executable, device_root):
    executable = executable.resolve(strict=True)
    if not executable.is_relative_to((device_root / "data").resolve(strict=True)):
        raise CaptureError("The PID does not belong to the selected Simulator device directory")
    app = next((path for path in executable.parents if path.suffix == ".app"), None)
    if app is None:
        raise CaptureError("The selected process is not an application executable")
    info = read_app_info(app)
    if (app / info["CFBundleExecutable"]).resolve() != executable:
        raise CaptureError("The PID is not the app's main executable")
    return app, {"bundle_id": info["CFBundleIdentifier"], "bundle_version": info.get("CFBundleVersion"),
                 "short_version": info.get("CFBundleShortVersionString"), "executable": str(executable)}


def framework_identities(app, history):
    frameworks = []
    for directory, _, _ in os.walk(app, followlinks=False, onerror=cannot_enumerate_bundle):
        framework = Path(directory)
        if framework.suffix != ".framework":
            continue
        info = read_app_info(framework) if (framework / "Info.plist").is_file() else None
        binary = (framework / (info["CFBundleExecutable"] if info else framework.stem)).resolve(strict=True)
        if not binary.is_relative_to(app):
            raise CaptureError("A framework executable escapes the artifact")
        frameworks.append({"path": framework.relative_to(app).as_posix(), "sha256": sha256(binary),
                           "file_uuids": executable_uuids(binary, history, required=False)})
    return sorted(frameworks, key=lambda item: item["path"])


def verify_snapshot(current, initial, previous=None):
    if current.start_ticks != initial.start_ticks or current.executable_uuid != initial.executable_uuid:
        raise CaptureError("The PID was reused or its executable changed; capture stopped")
    if not current.start_ticks or current.exit_ticks:
        raise CaptureError("The sampled process has exited or has no valid start identity")
    if previous and (current.user_ticks < previous.user_ticks or current.system_ticks < previous.system_ticks):
        raise CaptureError("CPU counters decreased; capture stopped")


def cpu_nanoseconds(ticks, numer, denom):
    # XNU task_power_info_locked returns Mach ticks, not ns. Python ints avoid overflow.
    # Apple conversion: https://developer.apple.com/library/archive/qa/qa1398/_index.html
    return ticks * numer // denom


def sample_row(snapshot, initial, previous, monotonic_ns, previous_ns, started_ns, numer, denom):
    verify_snapshot(snapshot, initial, previous)
    delta = snapshot.cpu_ticks - previous.cpu_ticks if previous else 0
    wall = monotonic_ns - previous_ns if previous_ns is not None else 0
    return {"timestamp_utc": utc_now(), "monotonic_ns": monotonic_ns,
            "elapsed_seconds": (monotonic_ns - started_ns) / 1e9,
            "process_start_ticks": snapshot.start_ticks, "executable_uuid": snapshot.executable_uuid,
            "cpu_user_ticks": snapshot.user_ticks, "cpu_system_ticks": snapshot.system_ticks,
            "cpu_total_ns": cpu_nanoseconds(snapshot.cpu_ticks, numer, denom),
            "cpu_interval_percent_one_core": cpu_nanoseconds(delta, numer, denom) / wall * 100 if wall > 0 else None,
            "rss_bytes": snapshot.rss_bytes, "physical_footprint_bytes": snapshot.footprint_bytes}


def summarize(rows):
    if not rows:
        return {"sample_count": 0}
    summary = {"sample_count": len(rows), "observed_seconds": rows[-1]["elapsed_seconds"]}
    if len(rows) > 1:
        wall_ns = rows[-1]["monotonic_ns"] - rows[0]["monotonic_ns"]
        cpu_ns = rows[-1]["cpu_total_ns"] - rows[0]["cpu_total_ns"]
        summary.update(cpu_seconds=cpu_ns / 1e9, cpu_percent_one_core=cpu_ns / wall_ns * 100)
    for key in ("rss_bytes", "physical_footprint_bytes"):
        values = sorted(row[key] for row in rows)
        summary[key] = {"median": statistics.median(values), "p95_nearest_rank": values[math.ceil(len(values) * .95) - 1],
                        "maximum": max(values)}
    return summary


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--hash-app", type=Path, help="Only hash an on-disk artifact; no process or Simulator calls")
    parser.add_argument("--udid", help="Exact Simulator UUID; 'booted' is rejected")
    parser.add_argument("--bundle-id", help="Installed app identifier, resolved only inside this device")
    parser.add_argument("--pid", type=int, help="Already running host PID of the Simulator app")
    parser.add_argument("--device-set", type=Path, default=Path.home() / "Library/Developer/CoreSimulator/Devices")
    parser.add_argument("--expected-executable-sha256", help="Hash supplied from the intended build artifact")
    parser.add_argument("--expected-bundle-sha256", help="Whole-bundle digest from --hash-app before installation")
    parser.add_argument("--fresh-launch-confirmed", action="store_true", help="Owner confirms a new launch of that installed build")
    parser.add_argument("--build-id", help="Artifact/build label, supplied by its builder")
    parser.add_argument("--build-commit", help="Declared source commit of that artifact")
    parser.add_argument("--base-commit", help="Source baseline for the comparison")
    parser.add_argument("--scenario", help="Exact flow/phase, driven separately by the owner")
    parser.add_argument("--circuit", type=Path, help="Local corpus file declared by the operator; the app's open document is not inspected")
    parser.add_argument("--duration-seconds", type=float, default=30)
    parser.add_argument("--interval-seconds", type=float, default=.5)
    parser.add_argument("--output-dir", type=Path, help="New directory; existing directories are rejected")
    args = parser.parse_args(argv)
    required = ("udid", "expected_executable_sha256", "expected_bundle_sha256", "build_id", "build_commit",
                "base_commit", "scenario", "circuit", "output_dir")
    if args.hash_app:
        if any(getattr(args, name) is not None for name in required) or args.pid is not None or args.bundle_id:
            parser.error("--hash-app is a separate filesystem-only mode")
        return args
    missing = ["--" + name.replace("_", "-") for name in required if getattr(args, name) is None]
    if missing:
        parser.error("capture requires " + ", ".join(missing))
    if not args.fresh_launch_confirmed:
        parser.error("capture requires --fresh-launch-confirmed from the app owner")
    try:
        args.udid = str(uuid.UUID(args.udid)).upper()
    except ValueError:
        parser.error("--udid must be an explicit UUID")
    if not args.bundle_id and args.pid is None:
        parser.error("provide --bundle-id or --pid, or both")
    if args.pid is not None and not 1 <= args.pid <= 2**31 - 1:
        parser.error("--pid must be a positive process ID")
    if args.bundle_id and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,254}", args.bundle_id):
        parser.error("invalid --bundle-id")
    for name in ("expected_executable_sha256", "expected_bundle_sha256"):
        if not re.fullmatch(r"[a-fA-F0-9]{64}", getattr(args, name)):
            parser.error("--" + name.replace("_", "-") + " must contain 64 hexadecimal digits")
        setattr(args, name, getattr(args, name).lower())
    if not (1 <= args.duration_seconds <= 3600 and .05 <= args.interval_seconds <= 10 and
            args.interval_seconds <= args.duration_seconds):
        parser.error("duration must be 1..3600 seconds; interval must be .05..10 seconds and <= duration")
    if not args.build_id.strip() or not args.scenario.strip():
        parser.error("--build-id and --scenario must not be blank")
    return args


def capture(args, manifest, rows):
    history = manifest["commands"]
    circuit = args.circuit.expanduser().resolve(strict=True)
    if not circuit.is_file():
        raise CaptureError("The declared circuit must be a regular file")
    manifest["circuit"] = {"path": str(circuit), "sha256": sha256(circuit), "size_bytes": circuit.stat().st_size,
                           "provenance": "operator declaration; the app's open document is not inspected"}
    manifest["source"] = {"base_commit": resolve_commit(args.base_commit, history),
                          "declared_build_commit": resolve_commit(args.build_commit, history),
                          "capture_checkout_commit": resolve_commit("HEAD", history),
                          "checkout_status": run_command(["git", "status", "--porcelain=v1"], history, ROOT),
                          "script_sha256": sha256(Path(__file__))}
    device_root = args.device_set.resolve(strict=True) / args.udid
    with (device_root / "device.plist").open("rb") as source:
        device_info = plistlib.load(source)
    if not isinstance(device_info, dict) or any(device_info.get(key) is not None and not isinstance(device_info[key], str)
                                                 for key in ("name", "runtime", "deviceType")):
        raise CaptureError("The Simulator device metadata has invalid field types")
    manifest["simulator"] = {"udid": args.udid, "device_set": str(args.device_set.resolve()),
                             "name": device_info.get("name"), "runtime": device_info.get("runtime"),
                             "device_type": device_info.get("deviceType")}
    sampler = Libproc()
    installed_app = None
    if args.bundle_id:
        installed_app = Path(run_command(["xcrun", "simctl", "--set", str(args.device_set.resolve()),
                             "get_app_container", args.udid, args.bundle_id, "app"], history)).resolve(strict=True)
    if args.pid is None:
        expected = (installed_app / read_app_info(installed_app)["CFBundleExecutable"]).resolve(strict=True)
        matches = []
        for value in run_command(["ps", "-axo", "pid="], history).split():
            try:
                if sampler.executable(int(value)) == expected:
                    matches.append(int(value))
            except (CaptureError, OSError):
                pass
        if len(matches) != 1:
            raise CaptureError(f"Expected exactly one running app process, found {len(matches)}; supply --pid explicitly")
        args.pid = matches[0]
    executable = sampler.executable(args.pid)
    app, identity = app_identity(executable, device_root)
    if args.bundle_id and (identity["bundle_id"] != args.bundle_id or app != installed_app):
        raise CaptureError("The PID and installed bundle do not match")
    actual_hash = sha256(executable)
    bundle_hash, bundle_files = bundle_fingerprint(app)
    manifest["artifact"] = {"bundle_sha256": bundle_hash, "executable_sha256": actual_hash,
                            "bundle_files": bundle_files,
                            "digest_format": "SHA-256 of ASCII canonical JSON entries, path-sorted; each regular file has its SHA-256; internal symlinks include canonical relative target"}
    if actual_hash != args.expected_executable_sha256:
        raise CaptureError("Executable SHA-256 differs from the intended build artifact")
    if bundle_hash != args.expected_bundle_sha256:
        raise CaptureError("Bundle SHA-256 differs from the intended build artifact")
    uuids = executable_uuids(executable, history)
    initial = sampler.snapshot(args.pid)
    verify_snapshot(initial, initial)
    if initial.executable_uuid not in uuids:
        raise CaptureError("The PID has a different loaded executable UUID than the verified artifact")
    manifest["process"] = {**identity, "pid": args.pid, "app_path": str(app),
                           "arguments_collected": False,
                           "start_ticks": initial.start_ticks, "loaded_uuid": initial.executable_uuid,
                           "architecture": uuids[initial.executable_uuid], "sha256": actual_hash,
                           "file_uuids": uuids}
    manifest["frameworks_on_disk"] = framework_identities(app, history)
    manifest["timebase"] = {"numer": sampler.numer, "denom": sampler.denom, "raw_cpu_unit": "mach_absolute_time_ticks"}
    manifest["sampling_started_utc"] = utc_now()
    started_ns = time.monotonic_ns()
    deadline = started_ns + int(args.duration_seconds * 1e9)
    next_sample = started_ns
    previous, previous_ns = None, None
    with (args.output_dir / "samples.csv").open("w", newline="") as output:
        writer = None
        while True:
            time.sleep(max(0, next_sample - time.monotonic_ns()) / 1e9)
            if sampler.executable(args.pid) != executable:
                raise CaptureError("The PID executable path changed; capture stopped")
            before_ns = time.monotonic_ns()
            current = sampler.snapshot(args.pid)
            now_ns = time.monotonic_ns()
            row = sample_row(current, initial, previous, now_ns, previous_ns, started_ns, sampler.numer, sampler.denom)
            row.update(pid=args.pid, sample_read_ns=now_ns - before_ns, scheduled_monotonic_ns=next_sample,
                       schedule_lateness_ns=max(0, before_ns - next_sample))
            if writer is None:
                writer = csv.DictWriter(output, fieldnames=list(row))
                writer.writeheader()
            writer.writerow(row)
            output.flush()
            rows.append(row)
            previous, previous_ns = current, now_ns
            if now_ns >= deadline:
                break
            next_sample = min(deadline, max(next_sample + int(args.interval_seconds * 1e9), now_ns))
    if len(rows) < 2:
        raise CaptureError("Fewer than two samples were captured; no CPU interval can be measured")
    if bundle_fingerprint(app)[0] != bundle_hash or sha256(executable) != actual_hash:
        raise CaptureError("The app bundle changed during the capture")
    verify_snapshot(sampler.snapshot(args.pid), initial, previous)
    if sampler.executable(args.pid) != executable:
        raise CaptureError("The PID executable path changed during the capture")
    manifest["artifact_verified_after_capture"] = True


def main(argv=None):
    args = arguments(argv)
    if args.hash_app:
        try:
            app = args.hash_app.expanduser().resolve(strict=True)
            info = read_app_info(app)
            bundle_hash, entries = bundle_fingerprint(app)
            print(json.dumps({"app_path": str(app), "bundle_sha256": bundle_hash,
                              "executable_sha256": sha256(app / info["CFBundleExecutable"]),
                              "bundle_entry_count": len(entries)}, indent=2))
            return 0
        except (CaptureError, OSError, ValueError) as error:
            print(str(error), file=sys.stderr)
            return 1
    if sys.platform != "darwin":
        raise SystemExit("This sampler requires macOS and an already running iOS Simulator app")
    try:
        args.output_dir = args.output_dir.expanduser().resolve()
        args.output_dir.mkdir(parents=True, exist_ok=False)
    except OSError as error:
        raise SystemExit(f"Cannot create a new output directory: {error}")
    manifest = {"schema_version": 1, "status": "preparing", "started_utc": utc_now(),
                "invocation": [sys.executable, str(Path(__file__).resolve()), *(sys.argv[1:] if argv is None else argv)],
                "build_id": args.build_id, "scenario": args.scenario,
                "expected_executable_sha256": args.expected_executable_sha256,
                "expected_bundle_sha256": args.expected_bundle_sha256,
                "fresh_launch_confirmed_by_owner": args.fresh_launch_confirmed,
                "requested_duration_seconds": args.duration_seconds, "requested_interval_seconds": args.interval_seconds,
                "host": {"platform": platform.platform(), "machine": platform.machine(), "logical_cpu_count": os.cpu_count(),
                         "python": sys.version, "load_average_windows_minutes": [1, 5, 15],
                         "load_average_start": list(os.getloadavg())}, "commands": [],
                "metric_scope": "one Simulator app host process, all of its threads; excludes other processes",
                "cpu_percent_definition": "100 percent equals one logical core; may exceed 100 percent",
                "memory_unit": "bytes", "energy_measured": False,
                "source_commit_provenance": "build commit is declared; loaded main Mach-O UUID and expected executable/bundle SHA-256 are verified",
                "framework_uuid_scope": "on-disk files only; loaded images are not enumerated, so the owner must confirm a fresh launch",
                "references": [XNU + "/osfmk/kern/bsd_kern.c#L1193-L1211",
                               XNU + "/osfmk/kern/task.c#L6387-L6392",
                               "https://developer.apple.com/library/archive/qa/qa1398/_index.html"]}
    rows, code = [], 0
    try:
        capture(args, manifest, rows)
        manifest["status"] = "completed"
    except KeyboardInterrupt:
        manifest.update(status="interrupted", error="Capture interrupted; the app was not terminated")
        code = 130
    except (CaptureError, OSError, ValueError, subprocess.SubprocessError) as error:
        manifest.update(status="failed", error=str(error))
        code = 1
    finally:
        manifest["host"]["load_average_end"] = list(os.getloadavg())
        manifest.update(ended_utc=utc_now(), summary=summarize(rows))
        (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(args.output_dir / "manifest.json")
    if code:
        print(manifest["error"], file=sys.stderr)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
