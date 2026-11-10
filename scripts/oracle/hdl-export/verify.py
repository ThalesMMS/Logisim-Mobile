#!/usr/bin/env python3
"""Compile and execute emitted RTL using independent Verilog and VHDL tools."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def run(command, directory):
    result = subprocess.run(command, cwd=directory, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=60)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stdout}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--iverilog", default="iverilog")
    parser.add_argument("--vvp", default="vvp")
    parser.add_argument("--nvc", default="nvc")
    args = parser.parse_args()
    destination = args.artifacts.resolve()
    (destination / "result.json").unlink(missing_ok=True)
    tools = {name: shutil.which(getattr(args, name)) for name in ("iverilog", "vvp", "nvc")}
    if not all(tools.values()):
        raise SystemExit("Install Icarus Verilog (iverilog/vvp) and NVC, or pass their executable paths.")
    manifest = json.loads((destination / "fixtures.json").read_text())
    results = []
    for fixture in manifest["fixtures"]:
        directory = destination / fixture["directory"]
        if fixture["language"] == "VERILOG":
            output = run([tools["iverilog"], "-g2012", "-s", "tb", "-o", "simulation.vvp", *fixture["files"]], directory)
            output += run([tools["vvp"], "simulation.vvp"], directory)
        else:
            output = run([tools["nvc"], "--std=2008", "-a", *fixture["files"]], directory)
            output += run([tools["nvc"], "--std=2008", "-e", "tb"], directory)
            output += run([tools["nvc"], "--std=2008", "-r", "tb"], directory)
        marker = f"HDL_EXPORT_PASS {fixture['fixture']}"
        if marker not in output:
            raise RuntimeError(f"Simulation did not reach the final assertion: {fixture}\n{output}")
        (directory / "tool-output.log").write_text(output)
        results.append({**fixture, "passed": True})
    sources = sorted({destination / fixture["directory"] / filename
                      for fixture in manifest["fixtures"]
                      for filename in [*fixture["files"], "source.circ"]})
    report = {"schema": 1, "toolPaths": tools,
              "toolVersions": {"iverilog": run([tools["iverilog"], "-V"], destination).splitlines()[0],
                               "nvc": run([tools["nvc"], "--version"], destination).splitlines()[0]},
              "fixtures": results, "outputComparisons": sum(x["outputChecks"] for x in results),
              "sourceSha256": {str(p.relative_to(destination)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in sources}}
    (destination / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {len(results)} independently compiled and simulated designs; "
          f"{report['outputComparisons']} literal output comparisons")


if __name__ == "__main__":
    main()
