"""Run the FPGA Clock tick (#307) and shortcut remap (#189) scenarios through production controls on an explicit iPad Simulator."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import re
import shutil
import sys
import traceback

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/ios_fpga_ui"
TESTS = ("FpgaTickEndToEndTests/testTickFrequencyIsShownSavedAndRestored",
         "FpgaTickEndToEndTests/testMapsThatBindTheClockKeepThePhysicalInput",
         "ShortcutRemapEndToEndTests/testRemapPersistConflictAndRestoreWithHardwareKeys",
         "ShortcutRemapEndToEndTests/testEvolutionHotkeysWithHardwareKeys")


def support():
    spec = importlib.util.spec_from_file_location("fpga_native_support", ROOT / "scripts/run-ios-accessibility.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixtures() -> dict:
    tick = (SOURCE / "fpga-tick.circ").read_text()
    legacy = tick.replace('<mc key="/source_clock" pmap="u" mobilepins="" mobileclockhz="+0"/>',
                          '<mc key="/source_clock" pmap="u" mobileclockpin="W5" mobileclockhz="100000000"/>')
    if legacy == tick:
        raise ValueError("The legacy fixture must bind the Clock to W5")
    return {"tick_xml": tick, "legacy_xml": legacy}


def prepare(output: Path, bundle_id: str, catalog_root: Path) -> Path:
    common = support()
    project = common.prepare(output, catalog_root, bundle_id, "FPGA").parent
    for name in ("project.yml", "FpgaTickEndToEndTests.swift", "ShortcutRemapEndToEndTests.swift"):
        shutil.copyfile(SOURCE / name, project / name)
    shutil.copyfile(ROOT / "scripts/ios_native_ui/NativeTextReplacement.swift", project / "NativeTextReplacement.swift")
    (project / "Run.json").write_text(json.dumps(fixtures(), indent=2) + "\n")
    common.run(["xcodegen", "generate", "--spec", str(project / "project.yml")])
    info = {"CFBundleDevelopmentRegion": "en", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
            "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": "$(PRODUCT_NAME)", "CFBundlePackageType": "BNDL",
            "CFBundleShortVersionString": "1.0", "CFBundleVersion": "1",
            "TargetAppBundleIdentifier": bundle_id}
    with (project / "TestInfo.plist").open("wb") as stream:
        plistlib.dump(info, stream)
    return project / "LogisimFpgaHarness.xcodeproj"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator", required=True, help="Explicitly reserved, booted iPad UUID")
    parser.add_argument("--bundle-id", default="thalesmms.logisim.mobile")
    parser.add_argument("--baseline-sha", required=True)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--only-testing", action="append", default=[], choices=TESTS)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", args.simulator):
        parser.error("Reserve an explicit iPad Simulator UUID")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Use a new output directory; previous evidence is preserved")
    output.mkdir(parents=True, exist_ok=True)
    common = support()
    derived = output / "DerivedData"
    report = {"started": datetime.now(timezone.utc).isoformat(), "baseline_sha": args.baseline_sha,
              "simulator": args.simulator, "physical_device": False, "selected_tests": args.only_testing or list(TESTS)}
    code = 1
    try:
        report["artifact"] = common.artifact(args.app.resolve(), args.bundle_id)
        devices = json.loads(common.run(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
        device = next((dict(item, runtime=runtime) for runtime, items in devices.items() for item in items
                       if item["udid"].lower() == args.simulator.lower()), None)
        if not device or device["state"] != "Booted" or "iPad" not in device["name"]:
            raise ValueError("The reserved iPad must already be booted")
        report["device"] = device
        project = prepare(output, args.bundle_id, ROOT)
        report["fixtures_sha256"] = {name: hashlib.sha256(text.encode()).hexdigest() for name, text in fixtures().items()}
        command = ["xcodebuild", "-project", str(project), "-scheme", "FpgaHarness",
                   "-destination", f"platform=iOS Simulator,id={args.simulator}",
                   "-derivedDataPath", str(derived), "CODE_SIGNING_ALLOWED=NO"]
        code = common.run([*command, "build-for-testing"], log=output / "build.log")
        report["build"] = "PASS" if code == 0 else "FAIL"
        if code == 0:
            common.run(["xcrun", "simctl", "install", args.simulator, str(args.app.resolve())])
            result = output / "fpga.xcresult"
            selection = ["-only-testing:FpgaTickEndToEndTests/" + name
                         for name in (args.only_testing or TESTS)]
            code = common.run([*command, "test-without-building", *selection, "-parallel-testing-enabled", "NO",
                               "-test-timeouts-enabled", "YES", "-default-test-execution-time-allowance", "600",
                               "-resultBundlePath", str(result)], log=output / "test.log")
            report["execution"] = {"exit_code": code, "xcresult": str(result)}
            if result.exists():
                report["test_summary"] = json.loads(common.run(
                    ["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(result)]))
                common.run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(result),
                            "--output-path", str(output / "attachments")], log=output / "attachments.log")
        report["status"] = "PASS" if code == 0 else "FAIL"
    except (Exception, KeyboardInterrupt) as error:
        report["status"] = "FAIL"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        (output / "execution-error.txt").write_text(traceback.format_exc())
        code = 1
    finally:
        report["finished"] = datetime.now(timezone.utc).isoformat()
        (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
