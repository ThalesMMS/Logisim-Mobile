"""Capture read-only Simulator PNGs with conservative wall-clock time intervals.

Run only inside the device owner's existing lease. This script sends no input,
launches no app and makes no assertion about the circuit's output.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time


def capture_frame(udid, destination, *, test_case, run=subprocess.run, wall=time.time, monotonic=time.monotonic):
    destination = destination.resolve()
    command = ["xcrun", "simctl", "io", udid, "screenshot", "--type=png", str(destination)]
    started = wall()
    monotonic_started = monotonic()
    result = run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=10)
    monotonic_returned = monotonic()
    returned = wall()
    elapsed = monotonic_returned - monotonic_started
    record = {"recordType": "ios-probe-frame-capture", "schemaVersion": 1,
              "testCase": test_case, "simulatorUDID": udid,
              "timeSource": "time.time Unix seconds on the iOS Simulator host",
              "frame": str(destination.resolve()), "captureStartedUnixSeconds": started,
              "captureReturnedUnixSeconds": returned, "captureElapsedMonotonicSeconds": elapsed,
              "captureCommand": command, "captureCommandOutput": result.stdout,
              "captureExitCode": result.returncode, "collectionStatus": "failed"}
    if result.returncode != 0:
        record["error"] = "simctl screenshot failed"
    elif returned < started or elapsed < 0 or abs((returned - started) - elapsed) > .05:
        record["error"] = "The wall clock changed relative to the monotonic clock during capture"
    elif not destination.is_file() or not destination.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
        record["error"] = "simctl did not return a PNG file"
    else:
        record.update({"collectionStatus": "captured", "frameSha256": hashlib.sha256(destination.read_bytes()).hexdigest()})
    return record


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--udid", required=True, help="Explicit Simulator UDID owned by the running test")
    parser.add_argument("--test-case", required=True, help="Run-qualified XCTest case receiving these frames")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--duration-seconds", type=float, default=30)
    parser.add_argument("--interval-seconds", type=float, default=.15)
    args = parser.parse_args(arguments)
    if any(not math.isfinite(value) or value <= 0 for value in [args.duration_seconds, args.interval_seconds]):
        parser.error("Capture duration and interval must be finite and positive")
    args.output_dir.mkdir(parents=True, exist_ok=False)
    record = {"recordType": "ios-probe-capture-session", "schemaVersion": 1, "simulatorUDID": args.udid,
              "testCase": args.test_case, "nativeExecution": "not-inferred", "collectionStatus": "recording",
              "timeSource": "time.time Unix seconds on the iOS Simulator host",
              "timingMeaning": "The PNG capture occurred between command start and return. This is not a hardware contact timestamp.",
              "inputActions": [], "samples": []}
    deadline = time.monotonic() + args.duration_seconds
    try:
        with (args.output_dir / "captures.jsonl").open("x") as journal:
            while time.monotonic() < deadline:
                cycle_started = time.monotonic()
                sample = capture_frame(args.udid, args.output_dir / f"{len(record['samples']):05d}.png", test_case=args.test_case)
                Path(sample["frame"]).with_suffix(".capture.json").write_text(json.dumps(sample, indent=2) + "\n")
                record["samples"].append(sample)
                journal.write(json.dumps(sample) + "\n")
                journal.flush()
                if sample["collectionStatus"] != "captured":
                    record["collectionStatus"] = "failed"
                    break
                delay = min(args.interval_seconds - (time.monotonic() - cycle_started), deadline - time.monotonic())
                if delay > 0:
                    time.sleep(delay)
            else:
                record["collectionStatus"] = "complete"
    except (OSError, subprocess.SubprocessError) as error:
        record.update({"collectionStatus": "failed", "error": str(error), "errorType": type(error).__name__})
    (args.output_dir / "capture-session.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"collectionStatus": record["collectionStatus"], "frames": len(record["samples"]),
                      "record": str(args.output_dir / "capture-session.json"), "nativeExecution": "not-inferred"}))
    return 0 if record["collectionStatus"] == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
