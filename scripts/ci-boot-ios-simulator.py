#!/usr/bin/env python3
"""Select an available iOS Simulator and wait for that UUID to finish booting."""
import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
from uuid import UUID


class SimulatorError(Exception):
    pass


@dataclass(frozen=True)
class Simulator:
    udid: str
    name: str
    runtime: str
    state: str
    version: tuple[int, ...]


def select_simulator(inventory):
    if not isinstance(inventory, dict) or not isinstance(inventory.get("devices"), dict):
        raise SimulatorError("simctl inventory has no devices object")
    if not isinstance(inventory.get("runtimes"), list):
        raise SimulatorError("simctl inventory has no runtimes list")
    candidates = []
    for runtime in inventory["runtimes"]:
        if not isinstance(runtime, dict) or runtime.get("isAvailable") is not True:
            continue
        identifier = runtime.get("identifier", "")
        version = str(runtime.get("version", ""))
        if not isinstance(identifier, str) or not identifier.startswith("com.apple.CoreSimulator.SimRuntime.iOS-"):
            continue
        if not re.fullmatch(r"\d+(?:\.\d+)*", version):
            continue
        version_parts = tuple(int(part) for part in version.split("."))
        devices = inventory["devices"].get(identifier, [])
        if not isinstance(devices, list):
            continue
        for device in devices:
            if not isinstance(device, dict) or device.get("isAvailable") is not True:
                continue
            if device.get("state") not in ("Booted", "Shutdown"):
                continue
            udid, name = device.get("udid"), device.get("name")
            if not isinstance(udid, str) or not isinstance(name, str):
                continue
            try:
                UUID(udid)
            except ValueError:
                continue
            candidates.append(Simulator(udid, name, identifier, device["state"], version_parts))
    if not candidates:
        raise SimulatorError("No available iOS Simulator with a valid UUID was found")
    return min(candidates, key=lambda device: (
        device.state != "Booted",
        tuple(-part for part in (device.version + (0, 0, 0))[:3]),
        not device.name.startswith("iPhone"), device.name.casefold(), device.udid,
    ))


def run_simctl(arguments, timeout):
    command = ["xcrun", "simctl", *arguments]
    try:
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired as error:
        raise SimulatorError(f"{' '.join(command)} timed out after {timeout} seconds") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        raise SimulatorError(f"{' '.join(command)} failed (exit {error.returncode}): {detail}") from error
    except OSError as error:
        raise SimulatorError(f"Cannot run xcrun simctl: {error}") from error


def positive_seconds(value):
    seconds = int(value)
    if seconds <= 0:
        raise argparse.ArgumentTypeError("timeout must be a positive number of seconds")
    return seconds


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=positive_seconds, default=180, help="maximum boot wait in seconds")
    parser.add_argument("--diagnostics", type=Path, required=True, help="JSON report retained even when boot fails")
    args = parser.parse_args(argv)
    report = {"status": "listing"}
    try:
        report["inventory"] = json.loads(run_simctl(["list", "--json"], timeout=30))
        selected = select_simulator(report["inventory"])
        report["selection"] = asdict(selected)
        print(f"Waiting for {selected.name} ({selected.udid}), {selected.runtime}, state={selected.state}", file=sys.stderr)
        report["boot_log"] = run_simctl(["bootstatus", selected.udid, "-b"], timeout=args.timeout)
        report["status"] = "ready"
    except (SimulatorError, json.JSONDecodeError) as error:
        report.update(status="error", error=str(error))
        print(f"iOS Simulator preparation failed: {error}", file=sys.stderr)
    finally:
        args.diagnostics.parent.mkdir(parents=True, exist_ok=True)
        args.diagnostics.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    if report["status"] != "ready":
        return 1
    print(selected.udid)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
