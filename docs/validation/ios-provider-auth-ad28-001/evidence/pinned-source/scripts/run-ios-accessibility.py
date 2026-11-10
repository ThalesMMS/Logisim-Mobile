#!/usr/bin/env python3
"""Run an isolated native UI harness against an explicit Simulator and app artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import plistlib
import re
import shutil
import signal
import subprocess
import traceback
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/ios_accessibility_ui"
SHARED_SOURCE = ROOT / "scripts/ios_native_ui"
CATEGORIES = ("large", "accessibility-extra-extra-extra-large")


def run(arguments, *, log=None):
    if log is None:
        result = subprocess.run(arguments, check=True, capture_output=True, text=True)
        return result.stdout.strip()
    with Path(log).open("w") as stream:
        result = subprocess.run(arguments, stdout=stream, stderr=subprocess.STDOUT, check=False)
    return result.returncode


def prepare(output: Path, catalog_root: Path, bundle_id: str, component_search: str) -> Path:
    project = output / "harness"
    project.mkdir(parents=True, exist_ok=True)
    catalog = {}
    for path in sorted((catalog_root / "localization/messages").glob("*.json")):
        data = json.loads(path.read_text())
        overlap = catalog.keys() & data.keys()
        if overlap:
            raise ValueError(f"Duplicate localization keys in {path}: {sorted(overlap)}")
        catalog.update(data)
    for key in ("a11y_project_tools", "a11y_explorer_title", "a11y_explorer_edit", "a11y_canvas", "action_close", "a11y_fit_selection"):
        if key not in catalog:
            raise ValueError(f"The consolidated catalog is missing {key}")
    (project / "Catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    for name in ("project.yml", "AccessibilityUITests.swift"):
        shutil.copyfile(SOURCE / name, project / name)
    shutil.copyfile(SHARED_SOURCE / "NativeUIHarness.swift", project / "NativeUIHarness.swift")
    # Store exact input values as plist data, not interpolated shell or YAML fragments.
    info = {"CFBundleDevelopmentRegion": "en", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
            "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": "$(PRODUCT_NAME)", "CFBundlePackageType": "BNDL",
            "CFBundleShortVersionString": "1.0", "CFBundleVersion": "1",
            "TargetAppBundleIdentifier": bundle_id, "ComponentSearch": component_search}
    run(["xcodegen", "generate", "--spec", str(project / "project.yml")])
    # XcodeGen generated a build-setting template; replace only this test bundle's local plist.
    with (project / "TestInfo.plist").open("wb") as stream:
        plistlib.dump(info, stream)
    return project / "LogisimAccessibilityHarness.xcodeproj"


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact(app: Path, expected_bundle_id: str) -> dict:
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if info.get("CFBundleIdentifier") != expected_bundle_id:
        raise ValueError("The supplied app's bundle identifier differs from --bundle-id")
    executable = app / info["CFBundleExecutable"]
    bundle_hash = hashlib.sha256()
    for path in sorted(app.rglob("*")):
        if path.is_file():
            bundle_hash.update(path.relative_to(app).as_posix().encode() + b"\0")
            bundle_hash.update(file_hash(path).encode() + b"\n")
    return {"path": str(app), "bundle_id": expected_bundle_id,
            "executable_sha256": file_hash(executable), "bundle_tree_sha256": bundle_hash.hexdigest(),
            "version": info.get("CFBundleShortVersionString"), "build": info.get("CFBundleVersion")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator", required=True, help="Reserved Simulator UUID; never use 'booted'")
    parser.add_argument("--bundle-id", required=True)
    parser.add_argument("--app", type=Path, help="Consolidated .app to fingerprint and install; omitted for prepare-only")
    parser.add_argument("--baseline-sha", required=True, help="Source commit used to build --app")
    parser.add_argument("--catalog-root", type=Path, default=ROOT)
    parser.add_argument("--component-search", default="Reset", help="Label present in the fixture's active circuit")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--derived-data", type=Path, help="Reuse an explicit harness build cache while preserving a new results directory")
    parser.add_argument("--prepare-only", action="store_true", help="Generate the standalone harness without device actions")
    parser.add_argument("--category", action="append", choices=CATEGORIES)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9A-Fa-f-]{36}", args.simulator):
        parser.error("--simulator must be an explicit UUID")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)+", args.bundle_id):
        parser.error("Invalid --bundle-id")
    if not re.fullmatch(r"[0-9a-fA-F]{7,40}", args.baseline_sha):
        parser.error("--baseline-sha must identify a Git source commit")
    if not args.prepare_only and args.app is None:
        parser.error("--app is required for execution so the actual artifact is fingerprinted")
    output = args.output.resolve()
    derived_data = args.derived_data.resolve() if args.derived_data else output / "DerivedData"
    output.mkdir(parents=True, exist_ok=True)
    project = prepare(output, args.catalog_root.resolve(), args.bundle_id, args.component_search)
    if args.prepare_only:
        (output / "preparation.json").write_text(json.dumps({
            "baseline_sha": args.baseline_sha, "project": str(project), "simulator": args.simulator,
            "native_execution_gate": "NATIVE_NOT_RUN", "physical_device": False,
            "reason": "The harness was prepared without installing or launching the target app.",
            "voiceover": "NOT RUN: unavailable in iOS Simulator",
        }, ensure_ascii=False, indent=2) + "\n")
        print(project)
        return 0

    app_info = artifact(args.app.resolve(), args.bundle_id)
    devices = json.loads(run(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
    device = next((dict(item, runtime=runtime) for runtime, items in devices.items() for item in items
                   if item["udid"].lower() == args.simulator.lower()), None)
    if device is None or device.get("state") != "Booted":
        parser.error("The explicitly reserved Simulator must already be booted")
    original = {key: run(["xcrun", "simctl", "ui", args.simulator, key]) for key in ("content_size", "increase_contrast")}
    if original["content_size"] in ("unknown", "unsupported") or original["increase_contrast"] not in ("enabled", "disabled"):
        parser.error("Cannot restore unrecognized Simulator accessibility settings")
    report = {"started": datetime.now(timezone.utc).isoformat(), "baseline_sha": args.baseline_sha,
              "artifact": app_info, "device": device, "physical_device": False, "original_settings": original,
              "layer": "native UI automation and accessibility audit", "derived_data": str(derived_data),
              "native_execution_gate": "NATIVE_NOT_RUN",
              "cases": [], "restoration": {},
              "voiceover": {"status": "NOT RUN", "reason": "VoiceOver is unavailable in iOS Simulator; use physical hardware at the final stage.",
                            "source": "https://developer.apple.com/documentation/accessibility/performing-accessibility-testing-for-your-app"}}
    result_path = output / "results.json"

    def write_report():
        result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    def interrupted(_signum, _frame):
        raise KeyboardInterrupt

    previous_sigterm = signal.signal(signal.SIGTERM, interrupted)
    common = ["xcodebuild", "-project", str(project), "-scheme", "AccessibilityHarness",
              "-destination", f"platform=iOS Simulator,id={args.simulator}",
              "-derivedDataPath", str(derived_data), "CODE_SIGNING_ALLOWED=NO"]
    exit_code = 0
    try:
        write_report()
        run(["xcrun", "simctl", "install", args.simulator, str(args.app.resolve())])
        code = run([*common, "build-for-testing"], log=output / "build.log")
        if code:
            report["build"] = {"status": "FAIL", "exit_code": code, "log": str(output / "build.log")}
            exit_code = code
        else:
            report["build"] = {"status": "PASS", "log": str(output / "build.log")}
            resource = derived_data / "Build/Products/Debug-iphonesimulator/AccessibilityUITests-Runner.app/PlugIns/AccessibilityUITests.xctest/Catalog.json"
            if not resource.is_file():
                raise RuntimeError("The native test bundle is missing Catalog.json; no app scenarios were run")
        for category in (args.category or CATEGORIES) if code == 0 else ():
            run(["xcrun", "simctl", "ui", args.simulator, "content_size", category])
            contrast = "enabled" if category.startswith("accessibility-") else original["increase_contrast"]
            run(["xcrun", "simctl", "ui", args.simulator, "increase_contrast", contrast])
            actual_category = run(["xcrun", "simctl", "ui", args.simulator, "content_size"])
            actual_contrast = run(["xcrun", "simctl", "ui", args.simulator, "increase_contrast"])
            if actual_category != category or actual_contrast != contrast:
                raise RuntimeError("Simulator did not apply the requested text or contrast setting")
            bundle = output / f"{category}.xcresult"
            if bundle.exists():
                raise ValueError(f"Preserve the previous run and use a new output directory: {bundle}")
            code = run([*common, "test-without-building", "-parallel-testing-enabled", "NO",
                        "-resultBundlePath", str(bundle)], log=output / f"{category}.log")
            report["cases"].append({"content_size": category, "increase_contrast": contrast,
                                    "status": "PASS" if code == 0 else "FAIL", "exit_code": code,
                                    "xcresult": str(bundle), "log": str(output / f"{category}.log")})
            write_report()
        if any(case["status"] != "PASS" for case in report["cases"]):
            exit_code = 1
    except (Exception, KeyboardInterrupt) as error:
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report["execution_error"] = {"type": type(error).__name__, "message": str(error)}
        (output / "execution-error.txt").write_text(traceback.format_exc())
    finally:
        deferred_signals = []

        def defer_interrupt(signum, _frame):
            deferred_signals.append(signum)

        # This runner is macOS-only. Block both signals while replacing handlers so
        # an interrupt cannot leave half of the Simulator settings unrestored.
        blocked = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGINT, signal.SIGTERM})
        previous_sigint = signal.signal(signal.SIGINT, defer_interrupt)
        signal.signal(signal.SIGTERM, defer_interrupt)
        signal.pthread_sigmask(signal.SIG_SETMASK, blocked)
        try:
            for key, value in original.items():
                try:
                    run(["xcrun", "simctl", "ui", args.simulator, key, value])
                    actual = run(["xcrun", "simctl", "ui", args.simulator, key])
                    report["restoration"][key] = {"expected": value, "actual": actual, "status": "PASS" if actual == value else "FAIL"}
                except (Exception, KeyboardInterrupt) as error:
                    report["restoration"][key] = {"status": "FAIL", "error": str(error)}
            if deferred_signals:
                report["deferred_cleanup_signals"] = deferred_signals
                exit_code = exit_code or 130
            if any(result["status"] != "PASS" for result in report["restoration"].values()):
                exit_code = exit_code or 1
            report["status"] = "PASS" if exit_code == 0 else "FAIL"
            report["native_execution_gate"] = "NATIVE_PASS" if exit_code == 0 else (
                "NATIVE_FAIL" if report["cases"] else "NATIVE_NOT_RUN")
            report["finished"] = datetime.now(timezone.utc).isoformat()
            write_report()
            print(result_path)
        finally:
            blocked = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGINT, signal.SIGTERM})
            signal.signal(signal.SIGINT, previous_sigint)
            signal.signal(signal.SIGTERM, previous_sigterm)
            signal.pthread_sigmask(signal.SIG_SETMASK, blocked)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
