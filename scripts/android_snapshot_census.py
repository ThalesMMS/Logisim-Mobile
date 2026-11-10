#!/usr/bin/env python3
"""Compare static SDK snapshots across fresh and shared UiAutomation connections."""

import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


SNAPSHOT_BUDGET_MILLISECONDS = 1000
CLIENT_DEADLINE_SECONDS = 8


def validate_package(package):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)*\.e2e", package):
        raise ValueError("Only an isolated Android .e2e package is permitted")
    return package


def parse_processes(text, package):
    validate_package(package)
    lines = text.splitlines()
    if not lines or lines[0].split() != ["PID", "PPID", "NAME", "ARGS"]:
        raise ValueError("Expected ps columns PID, PPID, NAME, ARGS")
    helper = package.removesuffix(".e2e") + ".snapshot.e2e.test"
    groups = {name: [] for name in ("editorProcesses", "fixtureProcesses", "helperProcesses", "uiautomatorProcesses")}
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split(maxsplit=3)
        if len(parts) != 4 or not parts[0].isdigit() or not parts[1].isdigit():
            raise ValueError(f"Invalid ps row: {line!r}")
        pid, parent, name, args = parts
        process = {"pid": int(pid), "parentPid": int(parent), "name": name, "args": args}
        if name == package:
            groups["editorProcesses"].append(process)
        elif name == package + ".test" or name.startswith(package + ".test:"):
            groups["fixtureProcesses"].append(process)
        elif name == helper or name.startswith(helper + ":"):
            groups["helperProcesses"].append(process)
        if name == "uiautomator" or any(token.rsplit("/", 1)[-1] == "uiautomator" or token.startswith("com.android.commands.uiautomator.")
                                         for token in args.split()):
            groups["uiautomatorProcesses"].append(process)
    editors = groups["editorProcesses"]
    groups["editorPid"] = editors[0]["pid"] if len(editors) == 1 else None
    return groups


def parse_snapshot_result(stdout, returncode, nonce, package, previous_uptime=None):
    validate_package(package)
    if returncode != 0:
        raise ValueError(f"Snapshot adb command exited with {returncode}")
    values = {}
    for key, value in re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9]*)=(.*)$", stdout, re.MULTILINE):
        if key in values:
            raise ValueError(f"Duplicate snapshot result field: {key}")
        values[key] = value
    codes = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", stdout, re.MULTILINE)
    if codes != ["-1"] or "snapshotError" in values:
        raise ValueError("Snapshot instrumentation failed: " + values.get("snapshotError", f"codes={codes!r}"))
    return validate_snapshot_values(values, nonce, package, previous_uptime)


def validate_snapshot_values(values, nonce, package, previous_uptime=None):
    values = values.copy()
    if values.get("snapshotNonce") != nonce or values.get("snapshotExpectedPackage") != package:
        raise ValueError("Snapshot nonce or expected package does not match this probe")
    numeric = {"snapshotRootAttempts": 1, "snapshotRootWaitMillis": 0, "snapshotRootWindowId": 0,
               "snapshotRotation": 0, "snapshotUptimeMillis": 0}
    if any(key in values for key in ("snapshotCaptureState", "snapshotCaptureElapsedMillis", "snapshotCaptureOverrunMillis")):
        if values.get("snapshotCaptureState") != "COMPLETE":
            raise ValueError("Invalid or missing snapshot capture state")
        numeric.update(snapshotCaptureElapsedMillis=0, snapshotCaptureOverrunMillis=0)
    for key, minimum in numeric.items():
        raw = values.get(key, "")
        if not isinstance(raw, str) or not re.fullmatch(r"\d+", raw) or int(raw) < minimum:
            raise ValueError(f"Invalid or missing snapshot metadata: {key}")
        values[key] = int(raw)
    if values["snapshotRotation"] > 3 or values.get("snapshotRootSource") not in {
            "active-window", "window-active", "window-focused"}:
        raise ValueError("Invalid snapshot rotation or root source")
    if previous_uptime is not None and values["snapshotUptimeMillis"] < previous_uptime:
        raise ValueError("Snapshot uptime moved backwards")
    try:
        xml = base64.b64decode(values.pop("snapshotXmlBase64"), validate=True)
        root = ET.fromstring(xml.decode("utf-8"))
    except (KeyError, ValueError, UnicodeError, ET.ParseError) as error:
        raise ValueError("Snapshot returned invalid XML") from error
    first = next(root.iter("node"), None)
    if root.tag != "hierarchy" or root.get("snapshot-nonce") != nonce or \
            root.get("uptime-millis") != str(values["snapshotUptimeMillis"]) or \
            root.get("rotation") != str(values["snapshotRotation"]) or \
            first is None or first.get("package") != package:
        raise ValueError("Snapshot XML identity or metadata does not match this probe")
    return values, xml


def parse_series_blocks(stdout, complete):
    samples, fields, result, codes = [], {}, {}, []
    for line in stdout.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_(STATUS|RESULT): ([A-Za-z][A-Za-z0-9]*)=(.*)", line)
        if match:
            category, key, value = match.groups()
            if category == "STATUS" and (result or codes):
                raise ValueError("Snapshot status appeared after the final result")
            if category == "RESULT" and codes:
                raise ValueError("Snapshot result appeared after the final code")
            target = fields if category == "STATUS" else result
            if key in target:
                raise ValueError(f"Duplicate snapshot {category.lower()} field: {key}")
            target[key] = value
            continue
        match = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if match:
            if result or codes:
                raise ValueError("Snapshot status code appeared after the final result")
            samples.append((int(match.group(1)), fields))
            fields = {}
            continue
        match = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", line)
        if match:
            codes.append(int(match.group(1)))
    if complete and (fields or len(codes) != 1):
        raise ValueError("Incomplete snapshot series protocol")
    return samples, result, codes


def series_identity(values, nonce, package, query_count, index=None):
    values = values.copy()
    expected_nonce = nonce if index is None else f"{nonce}-{index}"
    if values.get("snapshotNonce") != expected_nonce or values.get("snapshotBaseNonce") != nonce or \
            values.get("snapshotExpectedPackage") != package:
        raise ValueError("Snapshot series nonce or package does not match this invocation")
    for key, minimum in (("snapshotQueryCount", 1), ("snapshotHelperPid", 1), ("snapshotConnectionUptimeMillis", 0)):
        raw = values.get(key, "")
        if not re.fullmatch(r"\d+", raw) or int(raw) < minimum:
            raise ValueError(f"Invalid snapshot series metadata: {key}")
        values[key] = int(raw)
    if values["snapshotQueryCount"] != query_count:
        raise ValueError("Snapshot query count does not match this invocation")
    if index is not None:
        if values.get("snapshotQueryIndex") != str(index):
            raise ValueError("Snapshot query indices are missing, repeated, or out of order")
        values["snapshotQueryIndex"] = index
    return values


def parse_windows(encoded):
    try:
        windows = json.loads(base64.b64decode(encoded, validate=True).decode("utf-8"))
    except (TypeError, ValueError, UnicodeError) as error:
        raise ValueError("Invalid final window census JSON") from error
    if not isinstance(windows, list) or any(not isinstance(window, dict) or
            type(window.get("id")) is not int or window["id"] < 0 or
            type(window.get("type")) is not int or window["type"] < 0 or
            type(window.get("active")) is not bool or type(window.get("focused")) is not bool for window in windows):
        raise ValueError("Invalid final window census fields")
    return windows


def parse_series_result(stdout, returncode, nonce, package, query_count, previous_uptime=None, *, complete=True):
    validate_package(package)
    if type(query_count) is not int or not 1 <= query_count <= 4:
        raise ValueError("Snapshot query count must be between 1 and 4")
    blocks, result, codes = parse_series_blocks(stdout, complete)
    if complete and returncode != 0:
        raise ValueError(f"Snapshot adb command exited with {returncode}")
    if complete and result.get("snapshotError"):
        raise ValueError("Snapshot series setup failed: " + result["snapshotError"])
    if len(blocks) > query_count or complete and len(blocks) != query_count:
        raise ValueError("Snapshot series returned an unexpected number of queries")
    samples, xmls, connection = [], {}, None
    for index, (code, values) in enumerate(blocks):
        values = series_identity(values, nonce, package, query_count, index)
        observed = values["snapshotHelperPid"], values["snapshotConnectionUptimeMillis"]
        if connection is not None and observed != connection:
            raise ValueError("Snapshot helper PID or connection uptime changed within one invocation")
        connection = observed
        if "snapshotWindowsBase64" in values or "snapshotWindowsError" in values:
            raise ValueError("The diagnostic window census must appear only in the final result")
        failed = bool(values.get("snapshotError"))
        if code != (0 if failed else -1):
            raise ValueError("Snapshot status code disagrees with its capture result")
        if not failed:
            values, xmls[index] = validate_snapshot_values(values, f"{nonce}-{index}", package, previous_uptime)
            previous_uptime = values["snapshotUptimeMillis"]
            if values["snapshotConnectionUptimeMillis"] > previous_uptime:
                raise ValueError("Snapshot connection uptime follows the capture uptime")
        else:
            values.pop("snapshotXmlBase64", None)
            for key, minimum in (("snapshotRootAttempts", 0), ("snapshotRootWaitMillis", 0),
                                 ("snapshotRootWindowId", -1), ("snapshotRotation", 0), ("snapshotUptimeMillis", 0)):
                if key not in values:
                    continue
                if not re.fullmatch(r"-?\d+", values[key]) or int(values[key]) < minimum:
                    raise ValueError(f"Invalid failed query metadata: {key}")
                values[key] = int(values[key])
        samples.append({"status": "failed" if failed else "passed", "statusCode": code, "result": values})
    if not complete:
        return samples, None, xmls
    result = series_identity(result, nonce, package, query_count)
    if connection != (result["snapshotHelperPid"], result["snapshotConnectionUptimeMillis"]):
        raise ValueError("Final snapshot helper PID or connection uptime does not match the queries")
    failures = sum(sample["status"] == "failed" for sample in samples)
    for key, expected in (("snapshotQueryFailures", failures), ("snapshotQuerySuccesses", query_count - failures)):
        if result.get(key) != str(expected):
            raise ValueError(f"Final snapshot count does not match the queries: {key}")
        result[key] = expected
    windows_error = result.get("snapshotWindowsError")
    if windows_error:
        if "snapshotWindowsBase64" in result:
            raise ValueError("Final window census returned both success and an error")
    else:
        result["windows"] = parse_windows(result.pop("snapshotWindowsBase64", None))
    if codes != [0 if failures or windows_error else -1]:
        raise ValueError("Final instrumentation code disagrees with the query or window census results")
    return samples, result, xmls


def observe(adb_path, serial, arguments, timeout):
    started = time.monotonic()
    command = [str(adb_path), "-s", serial, *arguments]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
        receipt = {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    except subprocess.TimeoutExpired as error:
        def decoded(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
        receipt = {"returncode": None, "stdout": decoded(error.stdout), "stderr": decoded(error.stderr),
                   "error": "TimeoutExpired", "message": str(error)}
    except OSError as error:
        receipt = {"returncode": None, "stdout": "", "stderr": "", "error": type(error).__name__, "message": str(error)}
    return {**receipt, "command": command, "seconds": round(time.monotonic() - started, 3)}


def save_observation(output, prefix, result):
    stdout = prefix + "-stdout.txt"
    stderr = prefix + "-stderr.txt"
    (output / stdout).write_text(result["stdout"])
    (output / stderr).write_text(result["stderr"])
    return {key: value for key, value in result.items() if key not in ("stdout", "stderr")} | {
        "stdout": stdout, "stderr": stderr}


def actors(adb_path, serial, package, output, prefix):
    result = {"commands": {}, "errors": [], "editorPid": None}
    for name, arguments in (("processes", ("shell", "ps", "-A", "-o", "PID,PPID,NAME,ARGS")),
                            ("accessibility", ("shell", "dumpsys", "accessibility")),
                            ("window", ("shell", "dumpsys", "window", "windows")),
                            ("ime", ("shell", "dumpsys", "input_method"))):
        receipt = observe(adb_path, serial, arguments, timeout=15)
        result["commands"][name] = save_observation(output, prefix + "-" + name, receipt)
        if receipt["returncode"] != 0:
            result["errors"].append(f"{name}: {receipt.get('error', 'exit ' + str(receipt['returncode']))}")
        elif name == "processes":
            try:
                result.update(parse_processes(receipt["stdout"], package))
            except ValueError as error:
                result["errors"].append(str(error))
    return result


def save_census(output, census):
    temporary = output / "census.json.tmp"
    temporary.write_text(json.dumps(census, indent=2) + "\n")
    temporary.replace(output / "census.json")


def run_census(adb_path, serial, package, output, *, query_count=1, invocations=4):
    validate_package(package)
    if not serial or not serial.strip():
        raise ValueError("An explicit Android device serial is required")
    if type(query_count) is not int or not 1 <= query_count <= 4 or type(invocations) is not int or not 1 <= invocations <= 4:
        raise ValueError("Query count and invocations must each be between 1 and 4")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    census = {"schemaVersion": 2, "premise": "Compare fresh and shared observer connections using the same snapshot acquisition algorithm and deadline.",
              "mode": "static-series", "queryCount": query_count, "invocations": invocations,
              "windowCensusPhase": "after-all-queries", "editorPackage": package, "serial": serial, "status": "running", "probes": [],
              "snapshotBudgetMilliseconds": SNAPSHOT_BUDGET_MILLISECONDS, "clientDeadlineSeconds": CLIENT_DEADLINE_SECONDS}
    save_census(output, census)
    previous_uptime = None
    for index in range(invocations):
        prefix = f"probe-{index}"
        row = {"probe": index, "phase": "static", "status": "failed", "errors": [],
               "actorsBefore": actors(adb_path, serial, package, output, prefix + "-before")}
        try:
            if row["actorsBefore"]["errors"] or row["actorsBefore"]["editorPid"] is None:
                raise ValueError("Expected a successful actor census with one exact editor process before the snapshot")
            if row["actorsBefore"]["helperProcesses"] or row["actorsBefore"]["uiautomatorProcesses"]:
                row["status"] = "skipped"
                raise ValueError("An existing snapshot helper or UiAutomator observer still owns the guest connection")
            nonce = uuid.uuid4().hex
            row["nonce"] = nonce
            component = package.removesuffix(".e2e") + ".snapshot.e2e.test/dev.logisim.mobile.documents.UiSnapshotSeriesInstrumentation"
            receipt = observe(adb_path, serial, ("shell", "am", "instrument", "-w", "-r", "-e", "snapshotNonce", nonce,
                              "-e", "snapshotExpectedPackage", package, "-e", "snapshotQueryCount", str(query_count), component), CLIENT_DEADLINE_SECONDS)
            row["snapshotCommand"] = save_observation(output, prefix + "-result", receipt)
            row["seconds"] = receipt["seconds"]
            complete = not receipt.get("error") and receipt["returncode"] == 0
            samples, result, xmls = parse_series_result(receipt["stdout"], receipt["returncode"], nonce,
                                                       package, query_count, previous_uptime, complete=complete)
            row["queries"], row["result"] = samples, result
            for query_index, xml in xmls.items():
                (output / f"{prefix}-query-{query_index}.xml").write_bytes(xml)
                previous_uptime = samples[query_index]["result"]["snapshotUptimeMillis"]
            if receipt.get("error"):
                raise ValueError(receipt["error"] + ": " + receipt["message"])
            if receipt["returncode"] != 0:
                raise ValueError(f"Snapshot adb command exited with {receipt['returncode']}")
            row["withinSnapshotBudget"] = all(sample["status"] == "passed" and
                sample["result"]["snapshotRootWaitMillis"] <= SNAPSHOT_BUDGET_MILLISECONDS and
                sample["result"].get("snapshotCaptureElapsedMillis", 0) <= SNAPSHOT_BUDGET_MILLISECONDS and
                sample["result"].get("snapshotCaptureOverrunMillis", 0) == 0 for sample in samples)
            row["withinClientDeadline"] = receipt["seconds"] <= CLIENT_DEADLINE_SECONDS
            if not row["withinSnapshotBudget"] or not row["withinClientDeadline"]:
                raise ValueError("A query failed or exceeded its recorded acquisition budget, capture budget, or client deadline")
            if result.get("snapshotWindowsError"):
                raise ValueError("Final window census failed: " + result["snapshotWindowsError"])
            row["status"] = "passed"
        except (ValueError, OSError) as error:
            row["errors"].append(f"{type(error).__name__}: {error}")
        finally:
            row["actorsAfter"] = actors(adb_path, serial, package, output, prefix + "-after")
            row["editorPidUnchanged"] = row["actorsBefore"]["editorPid"] is not None and \
                row["actorsBefore"]["editorPid"] == row["actorsAfter"]["editorPid"]
            if row["actorsAfter"]["errors"] or not row["editorPidUnchanged"]:
                row["status"] = "failed"
                row["errors"].append("Actor census failed or the exact editor process changed after the snapshot")
            census["probes"].append(row)
            save_census(output, census)
    census["status"] = "passed" if all(row["status"] == "passed" for row in census["probes"]) else "failed"
    save_census(output, census)
    return census


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--query-count", type=int, choices=range(1, 5), default=1,
                        help="Snapshots per observer connection, from 1 to 4")
    parser.add_argument("--invocations", type=int, choices=range(1, 5), default=4,
                        help="Fresh observer connections, from 1 to 4")
    arguments = parser.parse_args()
    try:
        census = run_census(arguments.adb, arguments.serial, arguments.package, arguments.output,
                            query_count=arguments.query_count, invocations=arguments.invocations)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(arguments.output / "census.json")
    return 0 if census["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
