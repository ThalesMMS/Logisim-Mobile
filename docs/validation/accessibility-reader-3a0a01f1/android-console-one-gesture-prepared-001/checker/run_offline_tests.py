"""Run only literal-text tests with process/network boundaries blocked."""
from pathlib import Path
import datetime
import json
import os
import socket
import subprocess
import time
import unittest

out = Path(__file__).resolve().parent
attempts = []
def denied(*args, **kwargs):
    attempts.append("external process/network boundary")
    raise AssertionError("Offline test guard: process/network operation forbidden")

subprocess.Popen = subprocess.run = subprocess.call = subprocess.check_call = subprocess.check_output = denied
os.system = denied
socket.socket = denied
started = time.monotonic()
utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
suite = unittest.defaultTestLoader.loadTestsFromName("test_check_typeb_trace")
with (out / "unittest.log").open("w") as log:
    result = unittest.TextTestRunner(stream=log, verbosity=2).run(suite)
receipt = {
    "scope": "Pure offline literal trace tests only",
    "started_utc": utc,
    "duration_seconds": round(time.monotonic() - started, 6),
    "tests": result.testsRun,
    "failures": len(result.failures),
    "errors": len(result.errors),
    "skipped": len(result.skipped),
    "external_process_and_network_attempts": attempts,
    "guarded_boundaries": ["subprocess.Popen/run/call/check_call/check_output", "os.system", "socket.socket"],
    "result": "PASS" if result.wasSuccessful() and not attempts else "FAIL",
    "device_runtime": "NOT_RUN",
}
(out / "test-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps(receipt))
raise SystemExit(0 if receipt["result"] == "PASS" else 1)
