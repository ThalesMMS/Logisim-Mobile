#!/usr/bin/env python3
"""Passively sample a verified Android app during an operator-controlled circuit scenario."""
import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time


def proc_stat(text):
    """Linux stat fields after comm, which can itself contain spaces and parentheses."""
    fields = text[text.rfind(")") + 2:].split()
    if len(fields) < 22:
        raise ValueError("Incomplete process stat")
    return {"cpu_ticks": int(fields[11]) + int(fields[12]),
            "start_ticks": int(fields[19]), "rss_pages": int(fields[21])}


def memory_totals(text):
    result = {}
    for label, name in (("TOTAL PSS", "pss_kib"), ("TOTAL RSS", "rss_kib"),
                        ("TOTAL SWAP PSS", "swap_pss_kib")):
        match = re.search(r"\b" + label + r":\s*(\d+)", text)
        if match:
            result[name] = int(match[1])
    if "pss_kib" not in result:
        match = re.search(r"^\s*TOTAL\s+(\d+)\s+", text, re.MULTILINE)
        if match:
            result["pss_kib"] = int(match[1])
    return result


def frame_rows(text):
    """Keep only valid observed frames; gfxinfo's ring buffer is not a full trace."""
    window = "unknown"
    renderer = None
    header = None
    active = False
    frames = []
    for line in text.splitlines():
        stripped = line.strip()
        descriptor = re.fullmatch(r".*/([^\s/]+@[0-9a-fA-F]+)\s+\(visibility=\d+\)", stripped)
        if descriptor:
            renderer = descriptor.group(1)
        elif stripped.startswith("Window:"):
            window = stripped[7:].strip()
        elif line.strip() == "---PROFILEDATA---":
            active = not active
            header = None
        elif active and line.startswith("Flags,"):
            header = line.rstrip(",").split(",")
        elif active and header and line.strip():
            values = line.rstrip(",").split(",")
            if len(values) != len(header):
                continue
            try:
                row = {key: int(value) for key, value in zip(header, values)}
                start, end = row["IntendedVsync"], row["FrameCompleted"]
                if row["Flags"] != 0 or not 0 < start <= end < 2**63 - 1:
                    continue
                deadline = row.get("FrameDeadline", 0)
                gpu = row.get("GpuCompleted", 0)
                deadline = deadline if 0 < deadline < 2**63 - 1 else None
                gpu = gpu if start <= gpu < 2**63 - 1 else None
                frames.append({"window": window, "renderer": renderer, "intended_vsync_ns": start,
                    "frame_completed_ns": end, "completion_span_ns": end - start,
                    "deadline_ns": deadline, "gpu_completed_ns": gpu,
                    "gpu_deadline_missed": gpu >= deadline if gpu is not None and deadline is not None else None})
            except (ValueError, KeyError):
                continue
    return frames


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def cpu_summary(samples, tick_rate):
    valid = [sample for sample in samples if "cpu_stat_before_ns" in sample]
    if len(valid) < 2 or not tick_rate:
        return {}
    first, last = valid[0], valid[-1]
    delta = last["cpu_ticks"] - first["cpu_ticks"]
    if delta < 0:
        raise ValueError("Process CPU counters decreased")
    first_mid = (first["cpu_stat_before_ns"] + first["cpu_stat_after_ns"]) / 2
    last_mid = (last["cpu_stat_before_ns"] + last["cpu_stat_after_ns"]) / 2
    minimum_wall = last["cpu_stat_before_ns"] - first["cpu_stat_after_ns"]
    maximum_wall = last["cpu_stat_after_ns"] - first["cpu_stat_before_ns"]
    if last_mid <= first_mid or maximum_wall <= 0:
        raise ValueError("Process CPU sampling timestamps did not advance")
    numerator = 100 * delta / tick_rate * 1e9
    # utime and stime are separately quantized to clock ticks at both endpoints.
    lower = 100 * max(0, delta - 2) / tick_rate * 1e9
    upper = 100 * (delta + 2) / tick_rate * 1e9
    return {"process_cpu_percent_one_core_estimate": numerator / (last_mid - first_mid),
            "process_cpu_percent_one_core_lower_bound": lower / maximum_wall,
            "process_cpu_percent_one_core_upper_bound": upper / minimum_wall if minimum_wall > 0 else None,
            "process_cpu_quantization_allowance_ticks": 2,
            "process_cpu_valid_samples": len(valid),
            "process_cpu_window_monotonic_ns": [first_mid, last_mid]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--pid", type=int, help="Exact PID when multiple users run the package")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--fresh-launch-confirmed", required=True, action="store_true",
                        help="Owner confirms a new launch of the expected installed artifact")
    parser.add_argument("--build-commit", required=True, help="Declared source revision of the expected APK")
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--scenario", required=True, choices=("idle", "draw", "pan-zoom", "continuous", "background", "import", "serialize"))
    parser.add_argument("--circuit", required=True, type=Path, help="Exact local circuit presented by the operator")
    parser.add_argument("--seconds", type=float, default=20)
    parser.add_argument("--interval", type=float, default=1)
    parser.add_argument("--no-gfx-reset", action="store_true",
                        help="Keep diagnostic buffers unchanged and exclude the initial observed frames")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--adb", default=str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb") if os.environ.get("ANDROID_HOME") else shutil.which("adb"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.package) or not re.fullmatch(r"[a-f0-9]{64}", args.expected_apk_sha256):
        parser.error("Use a package identifier and a lowercase APK SHA-256")
    if not args.adb or not 1 <= args.seconds <= 3600 or not 0.2 <= args.interval <= 60 or not args.circuit.is_file():
        parser.error("Provide adb, an existing circuit, seconds 1..3600 and interval 0.2..60")
    if args.pid is not None and not 1 <= args.pid <= 2**31 - 1:
        parser.error("pid must be a positive process identifier")
    if args.output_dir.exists():
        parser.error("output-dir must be new to preserve previous measurements")
    commands = []

    def adb(*arguments, required=True):
        command = [args.adb, "-s", args.serial, *arguments]
        before = time.monotonic_ns()
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        commands.append({"argv": command, "started_monotonic_ns": before,
            "duration_ns": time.monotonic_ns() - before, "returncode": result.returncode})
        if required and result.returncode:
            raise RuntimeError(f"adb {arguments[0]} failed: {result.stderr.strip()}")
        return result.stdout if result.returncode == 0 else None

    def verify_apk():
        paths = adb("shell", "pm", "path", args.package).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("Expected one installed base APK; split APK capture needs a complete artifact set")
        path = paths[0][8:]
        if not re.fullmatch(r"/[A-Za-z0-9._=/+~-]+", path):
            raise RuntimeError("Unsafe installed APK path")
        digest = adb("shell", "sha256sum", path).split()[0]
        if digest != args.expected_apk_sha256:
            raise RuntimeError("Installed APK does not match the consolidated artifact; no measurement was taken")
        return {"path": path, "sha256": digest}

    installed = verify_apk()
    pids = adb("shell", "pidof", args.package).split()
    if not pids or not all(pid.isdecimal() for pid in pids):
        raise RuntimeError("Start the verified app and select the circuit before capture")
    if args.pid is None and len(pids) != 1:
        raise RuntimeError("Multiple package processes are running; supply the exact --pid")
    pid = str(args.pid) if args.pid is not None else pids[0]
    if pid not in pids:
        raise RuntimeError("The supplied PID does not match the package process")
    args.output_dir.mkdir(parents=True)
    manifest = {"schema": 1, "runtime": "Android", "serial": args.serial, "package": args.package,
        "pid": int(pid), "installed_apk": installed, "build_commit_declared": args.build_commit,
        "fresh_launch_confirmed_by_owner": args.fresh_launch_confirmed,
        "base_commit_declared": args.base_commit, "scenario_declared": args.scenario,
        "circuit": {"path": str(args.circuit.resolve()), "sha256": hashlib.sha256(args.circuit.read_bytes()).hexdigest()},
        "started_utc": datetime.now(timezone.utc).isoformat(), "requested_seconds": args.seconds,
        "interval_seconds": args.interval, "physical_energy_measured": False,
        "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "host_platform": platform.platform(), "host_cpu_count": os.cpu_count(),
        "host_load_average_start": list(os.getloadavg()) if hasattr(os, "getloadavg") else None,
        "limitations": ["Scenario and selected circuit are operator declarations, not instrumented app events",
            "Observed frame ring buffers can omit frames between polls; no full-frame trace or input latency is claimed",
            "dumpsys sampling adds host and target overhead", "Simulator CPU and memory do not define physical device budgets"],
        "commands": commands}
    samples, frames = [], {}
    initial_frames = set()
    unidentified_frame_rows = 0
    failure = None
    try:
        manifest["android_release"] = adb("shell", "getprop", "ro.build.version.release").strip()
        manifest["device_model"] = adb("shell", "getprop", "ro.product.model").strip()
        manifest["emulator"] = adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
        (args.output_dir / "package.txt").write_text(adb("shell", "dumpsys", "package", args.package))
        ticks = adb("shell", "getconf", "CLK_TCK", required=False)
        tick_rate = int(ticks.strip()) if ticks and ticks.strip().isdecimal() else None
        manifest["clock_ticks_per_second"] = tick_rate
        manifest["gfxinfo_reset"] = not args.no_gfx_reset
        if args.no_gfx_reset:
            baseline = adb("shell", "dumpsys", "gfxinfo", pid, "framestats")
            (args.output_dir / "gfxinfo-baseline.txt").write_text(baseline)
            initial_frames = {(frame["renderer"], frame["intended_vsync_ns"], frame["frame_completed_ns"])
                              for frame in frame_rows(baseline) if frame["renderer"] is not None}
            manifest["initial_observed_frames_excluded"] = len(initial_frames)
        else:
            adb("shell", "dumpsys", "gfxinfo", pid, "reset")
        started = time.monotonic()
        original_start = None

        def verify_process():
            if pid not in adb("shell", "pidof", args.package).split():
                raise RuntimeError("Target process changed during capture")
            if original_start is not None:
                raw = adb("shell", "cat", f"/proc/{pid}/stat", required=False)
                if not raw or proc_stat(raw)["start_ticks"] != original_start:
                    raise RuntimeError("Target process start identity changed or became unreadable")

        while True:
            before = time.monotonic_ns()
            verify_process()
            sample = {"monotonic_ns": before, "elapsed_seconds": time.monotonic() - started}
            stat_before = time.monotonic_ns()
            raw_stat = adb("shell", "cat", f"/proc/{pid}/stat", required=False)
            stat_after = time.monotonic_ns()
            if raw_stat:
                stat = proc_stat(raw_stat)
                original_start = original_start if original_start is not None else stat["start_ticks"]
                if stat["start_ticks"] != original_start:
                    raise RuntimeError("Target PID was reused during capture")
                sample.update(stat)
                sample.update(cpu_stat_before_ns=stat_before, cpu_stat_after_ns=stat_after,
                              cpu_stat_read_duration_ns=stat_after - stat_before)
            else:
                sample["cpu_unavailable"] = "Process stat is not readable by adb shell"
            raw_memory = adb("shell", "dumpsys", "meminfo", pid)
            totals = memory_totals(raw_memory)
            sample.update(totals)
            if not totals:
                sample["memory_unavailable"] = "No supported TOTAL fields found; raw meminfo was preserved"
            index = len(samples)
            (args.output_dir / f"meminfo-{index:04}.txt").write_text(raw_memory)
            raw_frames = adb("shell", "dumpsys", "gfxinfo", pid, "framestats")
            (args.output_dir / f"gfxinfo-{index:04}.txt").write_text(raw_frames)
            verify_process()
            for frame in frame_rows(raw_frames):
                if frame["renderer"] is None:
                    unidentified_frame_rows += 1
                    continue
                identity = (frame["renderer"], frame["intended_vsync_ns"], frame["frame_completed_ns"])
                if identity not in initial_frames:
                    frames[identity] = frame
            sample["capture_duration_ns"] = time.monotonic_ns() - before
            samples.append(sample)
            if time.monotonic() - started >= args.seconds:
                break
            time.sleep(min(args.interval, max(0, started + args.seconds - time.monotonic())))
        manifest["installed_apk_after"] = verify_apk()
        verify_process()
    except KeyboardInterrupt:
        failure = "Capture interrupted; the app was not terminated"
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        failure = str(error)
    finally:
        durations = [frame["completion_span_ns"] for frame in frames.values()]
        deadlines = [frame for frame in frames.values() if frame["deadline_ns"] is not None and frame["gpu_completed_ns"] is not None]
        manifest.update({"finished_utc": datetime.now(timezone.utc).isoformat(), "failure": failure,
            "host_load_average_end": list(os.getloadavg()) if hasattr(os, "getloadavg") else None,
            "samples": samples, "observed_frames": len(frames), "unidentified_frame_rows": unidentified_frame_rows,
            "observed_completion_span_p50_ns": percentile(durations, 0.5),
            "observed_completion_span_p95_ns": percentile(durations, 0.95),
            "observed_frames_with_gpu_deadline": len(deadlines),
            "observed_gpu_deadline_misses": sum(frame["gpu_deadline_missed"] for frame in deadlines) if deadlines else None})
        try:
            manifest.update(cpu_summary(samples, tick_rate if samples else None))
        except ValueError as error:
            failure = str(error)
            manifest["failure"] = failure
        cpu_available = "process_cpu_percent_one_core_estimate" in manifest
        manifest["metric_availability"] = {"process_cpu": cpu_available,
            "process_cpu_reason": None if cpu_available else "Requires two valid process-stat samples and CLK_TCK",
            "pss_samples": sum("pss_kib" in sample for sample in samples),
            "rss_samples": sum("rss_kib" in sample for sample in samples),
            "frame_duration_samples": len(frames), "gpu_deadline_samples": len(deadlines)}
        manifest["status"] = "failed" if failure else "completed" if cpu_available and manifest["metric_availability"]["pss_samples"] else "partial"
        (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        with (args.output_dir / "observed-frames.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=("window", "renderer", "intended_vsync_ns", "frame_completed_ns", "completion_span_ns", "deadline_ns", "gpu_completed_ns", "gpu_deadline_missed"))
            writer.writeheader(); writer.writerows(frames.values())
    if failure:
        raise SystemExit(failure)
    print(args.output_dir / "manifest.json")


if __name__ == "__main__":
    main()
