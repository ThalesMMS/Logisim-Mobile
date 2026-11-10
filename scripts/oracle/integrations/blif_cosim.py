#!/usr/bin/env python3
"""Compare BLIF co-simulation in the Mobile companion with Evolution's BLIF simulator.

For each BLIF fixture, BlifStepOracle.java runs a deterministic input sequence through
Evolution's BlifParser/DenseLogicCircuit and records the outputs per step. The same steps
go, in the same order, to the companion's `hdl.start`/`hdl.step` handler (Yosys + Icarus),
with the ports declared by Mobile's HdlIpDeclarations. Every output of every step must match.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))

from companion.hdl_sim import HdlSimulationManager  # noqa: E402


def mobile_logic(value: str) -> str:
    # Companion "logic": 0/1, '?' for no driver or unknown, 'E' for contention.
    return value


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default="java")
    parser.add_argument("--classpath", required=True)
    parser.add_argument("fixtures", nargs="+", type=Path)
    args = parser.parse_args()
    total = 0
    failures = []
    for fixture in args.fixtures:
        if any(line.strip().startswith(".latch") for line in fixture.read_text().splitlines()):
            print(f"SKIP {fixture.name}: Evolution's BlifParser ignores .latch lines (only DFF/DFFSR/latch cells)")
            continue
        result = subprocess.run([args.java, "-Djava.awt.headless=true", "--class-path", args.classpath,
                                 str(HERE / "BlifStepOracle.java"), str(fixture)],
                                text=True, capture_output=True, timeout=120)
        if result.returncode and "odd .names" in result.stderr:
            print(f"SKIP {fixture.name}: Evolution's BlifParser rejects .names logic tables (Mobile accepts them)")
            continue
        if result.returncode:
            raise SystemExit(f"BlifStepOracle failed for {fixture}:\n{result.stderr}")
        record = json.loads(result.stdout)
        manager = HdlSimulationManager()
        try:
            def start():
                started = manager.handle_request("hdl.start", {"backend": "blif", "source": fixture.read_text(),
                                                                "topLevel": record["top"], "ports": record["ports"]})
                if not started.get("ok"):
                    raise SystemExit(f"companion hdl.start failed for {fixture}: {started}")
                return started["sessionId"]
            # A companion session accepts at most 256 steps. Stateless (combinational) fixtures
            # continue in a fresh session; sequential ones must fit in one session.
            sequential = any(cell in fixture.read_text() for cell in ("DFF", "LATCH"))
            if sequential and len(record["steps"]) > 256:
                raise SystemExit(f"{fixture}: sequential sequence longer than one companion session")
            session = start()
            mismatches = 0
            for index, step in enumerate(record["steps"]):
                if index and index % 256 == 0:
                    session = start()
                response = manager.handle_request("hdl.step", {"sessionId": session, "inputs": step["inputs"]})
                if not response.get("ok"):
                    raise SystemExit(f"companion hdl.step failed for {fixture} step {index}: {response}")
                observed = {name: mobile_logic(value["logic"]) for name, value in response["outputs"].items()}
                if observed != step["desktop"]:
                    mismatches += 1
                    if mismatches <= 5:
                        failures.append(f"{fixture.name} step {index} inputs {step['inputs']}: Evolution {step['desktop']} Mobile {observed}")
            total += len(record["steps"])
            print(f"{'SAME' if mismatches == 0 else 'DIFF'} {fixture.name}: {len(record['steps'])} steps, {mismatches} mismatches")
        finally:
            manager.close()
    for failure in failures:
        print("  " + failure)
    print(f"{'PASS' if not failures else 'FAIL'}: {total} BLIF co-simulation steps compared with Evolution")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
