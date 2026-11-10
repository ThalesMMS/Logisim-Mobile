#!/usr/bin/env python3
"""Run system-input editor scenarios on an already-installed isolated Android APK."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import time

CASES = ("cancel", "mixed", "context", "focus", "scroll", "pinch", "focusloss", "io-release")
RUNNER = "dev.logisim.mobile.documents.DocumentIntegrationInstrumentation"


def isolated_package(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\.e2e", value):
        raise ValueError("The target package must be an isolated .e2e application")
    return value


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def decode_native_result(output: str) -> dict:
    records = [line.partition("=")[2] for line in output.splitlines()
               if line.strip().startswith("MIXED_INPUT_RESULT=")]
    if len(records) != 1:
        raise ValueError("Instrumentation did not return exactly one mixed-input result")
    result = json.loads(records[0])
    if not isinstance(result, dict):
        raise ValueError("Instrumentation result is not an object")
    return result


def parse_native_result(output: str, requested: str, package: str, apk_sha: str) -> dict:
    terminals = list(re.finditer(r"(?m)^INSTRUMENTATION_CODE:[ \t]*(-?\d+)[ \t\r]*$", output))
    if [terminal.group(1) for terminal in terminals] != ["-1"]:
        raise ValueError("Instrumentation must end once with Activity.RESULT_OK (-1)")
    if re.search(r"(?m)^\s*(?:INSTRUMENTATION_|MIXED_INPUT_RESULT=)", output[terminals[0].end():]):
        raise ValueError("Instrumentation protocol continued after its terminal result")
    if re.search(r"(?m)^INSTRUMENTATION_(?:FAILED|ABORTED)\b", output) or \
            re.search(r"(?m)^INSTRUMENTATION_(?:RESULT|STATUS):\s*(?:shortMsg|longMsg|Error)\s*=", output):
        raise ValueError("Instrumentation reported a failure or crash")
    result = decode_native_result(output)
    expected = set(CASES if requested == "all" else (requested,))
    cases = result.get("cases", [])
    if len(cases) != len(expected) or {case.get("case") for case in cases} != expected:
        raise ValueError("Instrumentation did not execute exactly the requested cases")
    if result.get("status") != "PASS" or any(case.get("status") != "PASS" for case in cases):
        raise ValueError("One or more native input scenarios failed")
    if result.get("packageName") != package:
        raise ValueError("Instrumentation returned another target package")
    artifact = result.get("artifact", {})
    if artifact.get("sha256Before") != apk_sha or artifact.get("sha256After") != apk_sha:
        raise ValueError("Native APK identity did not remain stable")
    qualification = "SYNTHESIZED_SYSTEM_INPUT_PASS" if requested == "all" else "PARTIAL"
    if result.get("qualification") != qualification:
        raise ValueError("Native qualification does not match the requested scope")
    return result


def artifact_relative_path(value: str, package: str) -> str:
    pattern = (r"/data/(?:user/0|data)/" + re.escape(package) +
               r"/files/(mixed-input/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})")
    match = re.fullmatch(pattern, value)
    if not match:
        raise ValueError("The evidence path is outside this test's private directory")
    return "files/" + match.group(1)


def extract_evidence(payload: bytes, destination: Path) -> None:
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:") as archive:
        members = archive.getmembers()
        if len(members) > 256 or sum(member.size for member in members) > 128 * 1024 * 1024:
            raise ValueError("The evidence archive exceeds the input-test limits")
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError("Unsafe evidence archive entry")
        archive.extractall(destination, members=members, filter="data")


class Adb:
    def __init__(self, executable: str, serial: str):
        self.prefix = [executable, "-s", serial]

    def run(self, *args: str, timeout: float = 30, binary: bool = False):
        result = subprocess.run(self.prefix + list(args), capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace").strip() or
                               "adb failed with exit code " + str(result.returncode))
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def installed_apk(self, package: str) -> bytes:
        paths = [line.removeprefix("package:") for line in
                 self.run("shell", "pm", "path", package).splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_~./+=:-]+\.apk", paths[0]):
            raise ValueError("Expected one installed base APK with a standard Android path")
        return self.run("exec-out", "cat", paths[0], binary=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="thalesmms.logisim.mobile.e2e", type=isolated_package)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--case", choices=("all",) + CASES, default="all")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=float, default=360)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_revision):
        parser.error("--source-revision must be a complete commit SHA")
    if not 1 <= args.timeout <= 900:
        parser.error("--timeout must be between 1 and 900 seconds")
    if args.output.exists() and any(args.output.iterdir()):
        parser.error("--output must be a new or empty directory")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"status": "NOT_RUN", "qualification": "NOT_QUALIFIED",
              "declaredSourceRevision": args.source_revision, "package": args.package,
              "serial": args.serial, "requestedCases": args.case,
              "scope": "Synthesized Android system input, not physical-device ergonomics."}
    report_path = args.output / "manifest.json"
    adb = Adb(args.adb, args.serial)
    native_started = False
    native_attempted = False
    started = time.monotonic()
    code = 1
    try:
        apk_sha = digest(args.apk.read_bytes())
        test_sha = digest(args.test_apk.read_bytes())
        report["artifacts"] = {"appSha256": apk_sha, "testSha256": test_sha}
        if digest(adb.installed_apk(args.package)) != apk_sha:
            raise ValueError("Installed application does not match --apk")
        if digest(adb.installed_apk(args.package + ".test")) != test_sha:
            raise ValueError("Installed instrumentation does not match --test-apk")
        report["androidFingerprint"] = adb.run("shell", "getprop", "ro.build.fingerprint").strip()
        before_anr = adb.run("shell", "dumpsys", "activity", "lastanr")
        (args.output / "lastanr-before.txt").write_text(before_anr)
        report["status"] = "NATIVE_RUNNING"
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        native_started = True
        native_attempted = True
        output = adb.run("shell", "am", "instrument", "-w", "-r",
                         "-e", "mixedInputCase", args.case,
                         "-e", "sourceRevision", args.source_revision,
                         "-e", "expectedApkSha256", apk_sha,
                         args.package + ".test/" + RUNNER, timeout=args.timeout)
        (args.output / "instrumentation.txt").write_text(output)
        native_started = False
        result = decode_native_result(output)
        report["native"] = result
        if result.get("packageName") != args.package:
            raise ValueError("Instrumentation returned another target package")
        relative = artifact_relative_path(result["evidenceDirectory"], args.package)
        evidence = adb.run("exec-out", "run-as", args.package, "tar", "-C", relative, "-cf", "-", ".", binary=True)
        extract_evidence(evidence, args.output / "native")
        result = parse_native_result(output, args.case, args.package, apk_sha)
        report.update(status="PASS", qualification=result["qualification"])
        code = 0
    except (Exception, KeyboardInterrupt) as error:
        report.update(status="FAIL", qualification="NOT_QUALIFIED",
                      errorType=type(error).__name__, error=str(error))
        if native_started:
            try:
                adb.run("shell", "am", "force-stop", args.package)
                report["cleanup"] = "Stopped only the isolated target started by this instrumentation"
            except Exception as cleanup_error:
                report["cleanupError"] = str(cleanup_error)
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
    finally:
        if native_attempted:
            post_errors = []
            installed_after = report["installedArtifactsAfter"] = {}
            for key, package, expected in (("appSha256", args.package, apk_sha),
                                           ("testSha256", args.package + ".test", test_sha)):
                try:
                    installed_after[key] = digest(adb.installed_apk(package))
                    if installed_after[key] != expected:
                        raise ValueError("An installed artifact changed during execution")
                except (Exception, KeyboardInterrupt) as error:
                    post_errors.append(error)
            try:
                after_anr = adb.run("shell", "dumpsys", "activity", "lastanr")
                (args.output / "lastanr-after.txt").write_text(after_anr)
                report["lastAnrUnchanged"] = before_anr == after_anr
                if not report["lastAnrUnchanged"]:
                    raise ValueError("The system recorded a new ANR during this run")
            except (Exception, KeyboardInterrupt) as error:
                post_errors.append(error)
            if post_errors:
                report["postVerificationError"] = "; ".join(f"{type(error).__name__}: {error}" for error in post_errors)
                if report["status"] != "FAIL":
                    error = post_errors[0]
                    report.update(status="FAIL", qualification="NOT_QUALIFIED",
                                  errorType=type(error).__name__, error=str(error))
                    code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(report_path)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
