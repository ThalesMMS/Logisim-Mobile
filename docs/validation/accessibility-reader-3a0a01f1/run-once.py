#!/usr/bin/env python3
"""Arm the prepared single-method probe; RUN on stdin starts the authorized attempt."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time


base = Path(__file__).resolve().parent
plan = json.loads((base / "ios-runtime-plan.json").read_text())
run = Path(sys.argv[1]).resolve()
run.mkdir(parents=True, exist_ok=False)
xctestrun = Path(plan["xctestrun"])
assert hashlib.sha256(xctestrun.read_bytes()).hexdigest() == plan["xctestrun_sha256"]
source = base / "VoiceOverServiceProbeUITests.swift"
assert hashlib.sha256(source.read_bytes()).hexdigest() == plan["probe_source_sha256"]
command = [str(run / "run.xcresult") if item == "<NEW_ROOT_APPROVED_OUTPUT>/run.xcresult" else item
           for item in plan["runtime_argv"]]
(run / "command.json").write_text(json.dumps(command, indent=2) + "\n")
print("ARMED. No reader command has executed. Send RUN only after the lane lease and app preflight.", flush=True)
if input().strip() != "RUN":
    raise SystemExit("Not started")
started = time.monotonic()
try:
    with (run / "test.log").open("w") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    receipt = {"exit_code": result.returncode, "duration_seconds": round(time.monotonic() - started, 3),
               "command": command, "attempts": 1,
               "lease_returned": False, "note": "Inspect restoration attachments and app/orientation before lane return."}
except subprocess.TimeoutExpired:
    receipt = {"exit_code": None, "duration_seconds": round(time.monotonic() - started, 3),
               "command": command, "attempts": 1, "timed_out": True,
               "lease_returned": False, "note": "Restoration is unknown; retain the lane and notify Root."}
(run / "execution.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt), flush=True)
raise SystemExit(receipt["exit_code"] if receipt["exit_code"] is not None else 1)
