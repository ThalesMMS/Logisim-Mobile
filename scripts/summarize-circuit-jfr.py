#!/usr/bin/env python3
"""Summarize core stacks from JFR JSON, without copying environment or process events."""
import argparse
from collections import Counter
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("recording_json", type=Path)
args = parser.parse_args()
recording = json.loads(args.recording_json.read_text())
samples, allocations = Counter(), Counter()
for event in recording["recording"]["events"]:
    frames = (event["values"].get("stackTrace") or {}).get("frames", [])
    method = next((frame["method"]["type"]["name"] + "." + frame["method"]["name"] for frame in frames
                   if frame["method"]["type"]["name"].startswith("dev/logisim/mobile/core/")
                   and "Benchmark" not in frame["method"]["type"]["name"]
                   and "PerformanceCircuits" not in frame["method"]["type"]["name"]), None)
    if not method:
        continue
    if event["type"] == "jdk.ExecutionSample":
        samples[method] += 1
    elif event["type"] == "jdk.ObjectAllocationSample":
        allocations[method] += event["values"]["weight"]
print(json.dumps({"core_execution_samples": samples.most_common(),
                  "core_allocation_sample_weights": allocations.most_common()}, indent=2))
