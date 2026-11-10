#!/usr/bin/env python3
"""Run HDL import/co-simulation through production compact-sheet controls on an explicit iPhone Simulator."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import re
import secrets
import shutil
import sys
import threading
import traceback
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from companion.api import OperationRegistry
from companion import hdl_import, hdl_sim
from companion.server import Configuration, make_http_server


def support():
    spec = importlib.util.spec_from_file_location("hdl_native_support", ROOT / "scripts/run-ios-accessibility.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(output: Path, bundle_id: str, configuration: dict, catalog_root: Path = ROOT) -> Path:
    common = support()
    project = common.prepare(output, catalog_root, bundle_id, "HDL").parent
    source = ROOT / "scripts/ios_hdl_ui"
    for name in ("project.yml", "HdlEndToEndTests.swift"):
        shutil.copyfile(source / name, project / name)
    shutil.copyfile(ROOT / "scripts/ios_native_ui/NativeTextReplacement.swift", project / "NativeTextReplacement.swift")
    (project / "Run.json").write_text(json.dumps(configuration, indent=2) + "\n")
    common.run(["xcodegen", "generate", "--spec", str(project / "project.yml")])
    info = {"CFBundleDevelopmentRegion": "en", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
            "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": "$(PRODUCT_NAME)", "CFBundlePackageType": "BNDL",
            "CFBundleShortVersionString": "1.0", "CFBundleVersion": "1",
            "TargetAppBundleIdentifier": bundle_id}
    with (project / "TestInfo.plist").open("wb") as stream:
        plistlib.dump(info, stream)
    return project / "LogisimHdlHarness.xcodeproj"


class AuditedCompanion:
    """Real handlers with an ephemeral loopback credential; receipts never contain the token."""
    def __init__(self):
        self.events = []
        self.lock = threading.Lock()
        registry = OperationRegistry()
        hdl_import.register(registry)
        hdl_sim.register(registry)
        for name, operation in list(registry.operations.items()):
            def audited(context, parameters, original=operation):
                event = {"operation": original.name, "started": datetime.now(timezone.utc).isoformat(),
                         "input_hashes": {name: hashlib.sha256(path.read_bytes()).hexdigest()
                                          for name, path in context.input_files.items()},
                         "source_sha256": hashlib.sha256(parameters["source"].encode()).hexdigest() if "source" in parameters else None}
                with self.lock:
                    self.events.append(event)
                try:
                    result = original.handler(context, parameters)
                    event["result"] = {key: result[key] for key in (
                        "ok", "code", "format", "top", "timeNs", "sourceSha256", "outputs", "sourceNames") if key in result}
                    event["status"] = "RETURNED"
                    return result
                except Exception as error:
                    event["status"] = "RAISED"
                    event["error_code"] = getattr(error, "code", type(error).__name__)
                    raise
                finally:
                    event["finished"] = datetime.now(timezone.utc).isoformat()
            registry.operations[name] = replace(operation, handler=audited)
        self.token = secrets.token_hex(32)
        self.server = make_http_server(Configuration(token=self.token, host="127.0.0.1", port=0,
                                                    allowed_operations=set(registry.operations)), registry)
        missing = {"nvc", "yosys"} - self.server.service.tools.keys()
        if missing:
            self.server.server_close()
            self.server.service.close()
            raise ValueError("The native HDL fixture requires actual tools: " + repr(sorted(missing)))
        self.tool_identities = {name: {"path": self.server.service.tools[name],
                                      "sha256": hashlib.sha256(Path(self.server.service.tools[name]).read_bytes()).hexdigest()}
                                for name in ("nvc", "yosys")}
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=0.05), daemon=True)
        self.thread.start()
        self.endpoint = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.server.service.close()
        self.thread.join(timeout=5)
        if self.thread.is_alive():
            raise RuntimeError("The test companion did not stop")


def verify_audit(events: list[dict]) -> dict:
    counts = {name: sum(event["operation"] == name for event in events) for name in (
        "yosys-import", "hdl.start", "hdl.step", "hdl.reset", "hdl.cancel")}
    required = {"yosys-import": 3, "hdl.start": 2, "hdl.step": 3, "hdl.reset": 1, "hdl.cancel": 1}
    if any(counts[name] < number for name, number in required.items()):
        raise ValueError("Native tests did not execute every required companion operation: " + repr(counts))
    imports = [event for event in events if event["operation"] == "yosys-import"]
    if not any(event.get("error_code") == "synthesis-failed" for event in imports):
        raise ValueError("The UI did not exercise the real Yosys failure")
    samples = [event.get("result", {}) for event in events if event["operation"] == "hdl.step"]
    seen = [(sample.get("timeNs"), sample.get("outputs", {}).get("y", {}).get("logic")) for sample in samples]
    if len(seen) != 3 or any(not isinstance(time, int) or time < 1 or value != format(time % 4, "02b") for time, value in seen):
        raise ValueError("The companion samples differ from the literal time-modulo-four VHDL fixture: " + repr(seen))
    resets = [event.get("result", {}) for event in events if event["operation"] == "hdl.reset"]
    if not any(result.get("ok") is True and result.get("timeNs") == 0 for result in resets):
        raise ValueError("The real companion reset did not return to time zero")
    if not any(event.get("result", {}).get("ok") is False and event["result"].get("code") == "analysis_failed" for event in events if event["operation"] == "hdl.start"):
        raise ValueError("The UI did not exercise an actual HDL compiler failure")
    return {"counts": counts, "samples": seen, "status": "PASS"}


def verify_summary(summary: dict, expected_count: int = 2) -> None:
    expected = {"totalTestCount": expected_count, "passedTests": expected_count, "failedTests": 0, "skippedTests": 0}
    if any(summary.get(key) != value for key, value in expected.items()):
        raise ValueError("Both native HDL scenarios must run and pass without skips: " + repr(summary))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator", required=True, help="Explicitly reserved iPhone UUID; never 'booted'")
    parser.add_argument("--bundle-id", default="thalesmms.logisim.mobile")
    parser.add_argument("--baseline-sha", required=True)
    parser.add_argument("--app", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--derived-data", type=Path)
    parser.add_argument("--catalog-root", type=Path, default=ROOT, help="Localization checkout matching the supplied app producer")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--alpha", action="store_true", help="Debugging only; never a qualification pass")
    parser.add_argument("--only-testing", action="append", default=[], choices=(
        "testCompactCosimulationSamplesResetStopAndCompilerFailure", "testCompactImportFailureAndVerilogBlifApply",
        "testCompactKeyboardActionPreservesExistingHdlSource"),
        help="Run a selected debug scenario; requires --alpha and never qualifies the full HDL workflow")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", args.simulator):
        parser.error("Reserve an explicit iPhone Simulator UUID")
    if not re.fullmatch(r"[0-9a-fA-F]{7,40}", args.baseline_sha):
        parser.error("Identify the supplied artifact's source commit")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)+", args.bundle_id):
        parser.error("Invalid bundle identifier")
    if args.only_testing and not args.alpha:
        parser.error("Partial scenario selection requires --alpha")
    if len(args.only_testing) != len(set(args.only_testing)):
        parser.error("Select each debug scenario once")
    if not args.prepare_only and not args.build_only and args.app is None:
        parser.error("Supply the actual app artifact for execution")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Use a new output directory; previous evidence is preserved")
    output.mkdir(parents=True, exist_ok=True)
    common = support()
    derived = (args.derived_data or output / "DerivedData").resolve()
    report = {"started": datetime.now(timezone.utc).isoformat(), "baseline_sha": args.baseline_sha,
              "simulator": args.simulator, "physical_device": False, "alpha": args.alpha, "selected_tests": args.only_testing,
              "native_execution_gate": "NATIVE_NOT_RUN", "qualification_gate": "NOT_QUALIFIED"}
    config = {"run_id": uuid.uuid4().hex[:10], "companion_endpoint": "http://127.0.0.1:8042",
              "companion_token": "PREPARATION_ONLY_NO_LIVE_CREDENTIAL"}
    host = None
    exit_code = 0
    try:
        if not args.prepare_only and not args.build_only:
            report["artifact"] = common.artifact(args.app.resolve(), args.bundle_id)
            devices = json.loads(common.run(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
            device = next((dict(item, runtime=runtime) for runtime, items in devices.items() for item in items
                           if item["udid"].lower() == args.simulator.lower()), None)
            if not device or device["state"] != "Booted" or "iPhone" not in device["name"]:
                raise ValueError("The reserved iPhone must already be booted")
            report["device"] = device
            host = AuditedCompanion()
            config.update(companion_endpoint=host.endpoint, companion_token=host.token)
        project = prepare(output, args.bundle_id, config, args.catalog_root.resolve())
        report["catalog_root"] = str(args.catalog_root.resolve())
        report["catalog_sha256"] = hashlib.sha256((project.parent / "Catalog.json").read_bytes()).hexdigest()
        report["configuration"] = {key: value for key, value in config.items() if key != "companion_token"}
        if args.prepare_only:
            report["status"] = "PREPARED"
            return 0
        command = ["xcodebuild", "-project", str(project), "-scheme", "HdlHarness",
                   "-destination", "generic/platform=iOS Simulator" if args.build_only else f"platform=iOS Simulator,id={args.simulator}",
                   "-derivedDataPath", str(derived), "CODE_SIGNING_ALLOWED=NO"]
        code = common.run([*command, "build-for-testing"], log=output / "build.log")
        report["build"] = {"exit_code": code, "status": "PASS" if code == 0 else "FAIL"}
        exit_code = code
        if code == 0:
            relative = "Build/Products/Debug-iphonesimulator/HdlEndToEndTests-Runner.app/PlugIns/HdlEndToEndTests.xctest"
            # Xcode 27 can keep the products beside the generated project.
            bundle = next(path for path in (derived / relative, project.parent / relative) if path.exists())
            for name in ("Catalog.json", "Run.json"):
                json.loads((bundle / name).read_text())
            report["resource_preflight"] = "PASS"
        if code == 0 and not args.build_only:
            common.run(["xcrun", "simctl", "install", args.simulator, str(args.app.resolve())])
            installed = Path(common.run(["xcrun", "simctl", "get_app_container", args.simulator, args.bundle_id, "app"]))
            report["installed_artifact"] = common.artifact(installed, args.bundle_id)
            if report["installed_artifact"]["bundle_tree_sha256"] != report["artifact"]["bundle_tree_sha256"]:
                raise ValueError("The installed app differs from the supplied artifact")
            report["companion_tools"] = host.tool_identities
            report["native_execution_gate"] = "NATIVE_RUNNING"
            (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
            result = output / "hdl.xcresult"
            selected = args.only_testing or ["testCompactCosimulationSamplesResetStopAndCompilerFailure",
                                              "testCompactImportFailureAndVerilogBlifApply"]
            selection = ["-only-testing:HdlEndToEndTests/HdlEndToEndTests/" + name for name in selected]
            code = common.run([*command, "test-without-building", *selection, "-parallel-testing-enabled", "NO",
                               "-collect-test-diagnostics", "never", "-test-timeouts-enabled", "YES",
                               "-default-test-execution-time-allowance", "600", "-resultBundlePath", str(result)], log=output / "test.log")
            report["execution"] = {"exit_code": code, "xcresult": str(result)}
            if code == 0:
                summary = json.loads(common.run(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(result)]))
                report["test_summary"] = summary
                verify_summary(summary, len(args.only_testing) if args.only_testing else 2)
            report["native_execution_gate"] = "NATIVE_PASS" if code == 0 else "NATIVE_FAIL"
            exit_code = code
            common.run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(result),
                        "--output-path", str(output / "attachments")], log=output / "attachments.log")
        if exit_code == 0 and not args.build_only:
            report["companion_audit"] = ({"status": "PARTIAL_ALPHA_ONLY", "events": len(host.events)}
                                         if args.only_testing else verify_audit(host.events))
            report["qualification_gate"] = "ALPHA_ONLY" if args.alpha else "HDL_COMPACT_SIMULATOR_PASS"
        report["status"] = "BUILT" if args.build_only and exit_code == 0 else "ALPHA_ONLY" if args.alpha and exit_code == 0 else "PASS" if exit_code == 0 else "FAIL"
    except (Exception, KeyboardInterrupt) as error:
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report["status"] = "FAIL"
        if report["native_execution_gate"] == "NATIVE_RUNNING":
            report["native_execution_gate"] = "NATIVE_INCOMPLETE"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        (output / "execution-error.txt").write_text(traceback.format_exc())
    finally:
        if host:
            try:
                host.close()
            except Exception as error:
                report["cleanup_error"] = str(error)
                report["status"] = "FAIL";report["qualification_gate"] = "FAIL";exit_code = 1
            (output / "companion-audit.json").write_text(json.dumps(host.events, indent=2) + "\n")
        if args.app and not args.prepare_only and not args.build_only:
            try:
                report["artifact_after"] = common.artifact(args.app.resolve(), args.bundle_id)
                if report["artifact_after"]["bundle_tree_sha256"] != report["artifact"]["bundle_tree_sha256"]:
                    raise ValueError("The supplied artifact changed during native execution")
            except Exception as error:
                report["artifact_error"] = str(error)
                report["status"] = "FAIL";report["qualification_gate"] = "FAIL";exit_code = 1
            for name, command in [
                ("screenshot.log", ["xcrun", "simctl", "io", args.simulator, "screenshot", str(output / "last-screen.png")]),
                ("simulator.log", ["xcrun", "simctl", "spawn", args.simulator, "log", "show", "--last", "15m", "--style", "compact", "--predicate", 'process == "LogisimMobile"']),
            ]:
                try:
                    common.run(command, log=output / name)
                except Exception as error:
                    report.setdefault("capture_errors", []).append(str(error))
        report["finished"] = datetime.now(timezone.utc).isoformat()
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
