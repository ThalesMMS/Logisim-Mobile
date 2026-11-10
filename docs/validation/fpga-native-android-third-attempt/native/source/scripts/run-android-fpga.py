#!/usr/bin/env python3
"""Prepare or execute FPGA UI scenarios using an explicitly reserved Android E2E app."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from android_ui import AndroidUi
from android_fpga_scenarios import run_fpga, verify_native_audit
from fpga_native_companion import FpgaNativeCompanion

CASES = ("synthesis", "tool-error", "cancel")


def support(catalog_root):
    spec = importlib.util.spec_from_file_location("fpga_android_support", ROOT / "scripts/e2e-android.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.ROOT = catalog_root
    return module


def reverse_target(raw, port):
    local = "tcp:" + str(port)
    found = [fields[2] for line in raw.splitlines() if len(fields := line.split()) == 3 and fields[1] == local]
    if len(found) > 1:
        raise ValueError("The reverse port has ambiguous transport ownership")
    return found[0] if found else None


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def preflight(args, common):
    if not args.serial or not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        raise ValueError("Execution requires one explicitly reserved Android serial")
    if args.artifacts is None or args.build_receipt is None:
        raise ValueError("Execution requires the frozen APK triplet and its app build receipt")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    package = common.expected_e2e_package()
    if not package.endswith(".e2e"):
        raise ValueError("Only the isolated E2E package may run FPGA scenarios")
    app, tests, snapshot = common.artifact_paths(args.artifacts)
    receipt, payload = common.load_build_receipt(args.build_receipt, sha256(app))
    catalog_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.catalog_root, text=True).strip()
    if receipt["gitHead"] != catalog_head:
        raise ValueError("Use the immutable catalog checkout matching the supplied app producer")
    aapt2 = common.find_aapt2(sdk, args.aapt2)
    identities = common.validate_apk_manifests(aapt2, app, tests, snapshot, package)
    return {"sdk": sdk, "package": package, "apks": (app, tests, snapshot), "receipt": receipt,
            "receipt_bytes": payload, "identities": identities, "aapt2": aapt2}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-only", action="store_true", help="Freeze inputs without starting a host, ADB, tools or app")
    mode.add_argument("--execute", action="store_true", help="Requires an existing exclusive device lease and already installed matching APKs")
    parser.add_argument("--serial", help="Explicit device serial; there is no discovery or 'booted' fallback")
    parser.add_argument("--artifacts", type=Path, help="Frozen app, androidTest and snapshot APK triplet")
    parser.add_argument("--build-receipt", type=Path)
    parser.add_argument("--catalog-root", type=Path, default=ROOT)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--case", choices=("all", *CASES), default="all")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.catalog_root = args.catalog_root.resolve()
    output = args.output.resolve()
    if output.exists():
        parser.error("Use a new output directory; existing evidence is preserved")
    common = support(args.catalog_root)
    try:
        setup = preflight(args, common) if args.execute else None
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    output.mkdir(parents=True)
    selected = list(CASES) if args.case == "all" else [args.case]
    report = {"started": datetime.now(timezone.utc).isoformat(), "status": "PREPARED",
              "nativeExecutionGate": "NATIVE_NOT_RUN", "qualificationGate": "NOT_QUALIFIED",
              "physicalHardware": False, "programmingEnabled": False, "selectedCases": selected,
              "harnessSource": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "catalogRoot": str(args.catalog_root), "serial": args.serial, "sources": {}}
    fixtures = ROOT / "fixtures/user-scenarios"
    sources = [ROOT / "scripts" / name for name in (
        "run-android-fpga.py", "android_fpga_scenarios.py", "fpga_native_companion.py",
        "e2e-android.py", "android_ui.py", "android_editing_scenarios.py", "user_scenario_contract.py")]
    sources += [fixtures / name for name in (
        "fpga-actions-v1.json", "fpga-and.circ", "fpga-hx1k.xml", "fpga-hx1k-invalid-pin.xml")]
    for source in sources:
        relative = source.relative_to(ROOT)
        destination = output / "source" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        report["sources"][str(relative)] = sha256(destination)
    report_path = output / "fpga-run.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    if args.prepare_only:
        print(f"Prepared FPGA sources; Native NOT_RUN: {output}")
        return 0
    ui = AndroidUi(setup["sdk"] / "platform-tools/adb", args.serial, setup["package"], output,
                   focus_snapshots=True)
    host = None
    reverse_port = None
    exit_code = 1
    try:
        report["artifactSource"] = setup["receipt"]["gitHead"]
        (output / "build-receipt.json").write_bytes(setup["receipt_bytes"])
        report["apkIdentities"] = setup["identities"]
        report["installedBefore"] = [ui.verify_installed_apk(path, package) for path, package in zip(
            setup["apks"], (ui.package, ui.package + ".test", ui.snapshot_package))]
        report["deviceFingerprint"] = ui.adb("shell", "getprop", "ro.build.fingerprint").strip()
        locale = ui.adb("shell", "settings", "get", "system", "system_locales").strip()
        if locale in ("", "null"):
            locale = ui.adb("shell", "getprop", "persist.sys.locale").strip() or ui.adb("shell", "getprop", "ro.product.locale").strip()
        report["systemLocales"] = locale
        if not locale.lower().startswith("en"):
            raise ValueError("The native document picker scenario requires an English system locale")
        host = FpgaNativeCompanion()
        port = urlsplit(host.endpoint).port
        if reverse_target(ui.adb("reverse", "--list"), port) is not None:
            raise ValueError("The selected loopback reverse port already belongs to another session")
        ui.adb("reverse", "--no-rebind", "tcp:" + str(port), "tcp:" + str(port))
        reverse_port = port
        report["reversePort"] = port
        scenarios = common.Scenarios(ui)
        scenarios.wait_editor()
        report["nativeExecutionGate"] = "NATIVE_RUNNING"
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        observations = run_fpga(scenarios, host, fixtures, selected)
        report["observations"] = observations
        report["companionVerification"] = verify_native_audit(host.audit(), observations)
        report["installedAfter"] = [ui.verify_installed_apk(path, package) for path, package in zip(
            setup["apks"], (ui.package, ui.package + ".test", ui.snapshot_package))]
        report.update(status="PASS", nativeExecutionGate="NATIVE_PASS",
                      qualificationGate="FPGA_SOFTWARE_SIMULATOR_PASS" if args.case == "all" else "PARTIAL_NATIVE_PASS")
        exit_code = 0
    except (Exception, KeyboardInterrupt) as error:
        token = host.token if host else ""
        message = str(error).replace(token, "[REDACTED_EPHEMERAL_TOKEN]") if token else str(error)
        report.update(status="FAIL", error={"type": type(error).__name__, "message": message})
        if report["nativeExecutionGate"] == "NATIVE_RUNNING":
            report["nativeExecutionGate"] = "NATIVE_FAIL"
        report["qualificationGate"] = "NOT_QUALIFIED"
        ui.fail_artifacts("fpga-failure", RuntimeError(message))
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
    finally:
        cleanup_errors = []
        def cleanup(name, action):
            try:
                action()
            except Exception as error:
                token = host.token if host else ""
                message = str(error).replace(token, "[REDACTED_EPHEMERAL_TOKEN]") if token else str(error)
                cleanup_errors.append({"action": name, "type": type(error).__name__, "message": message})
        if host:
            def audit():
                report["companionAudit"] = host.audit()
                (output / "companion-audit.json").write_text(json.dumps(report["companionAudit"], indent=2) + "\n")
            cleanup("companion-audit", audit)
            cleanup("companion-artifacts", lambda: host.export_artifacts(output / "companion-artifacts"))
            cleanup("companion-close", host.close)
        if reverse_port is not None:
            def remove_reverse():
                target = reverse_target(ui.adb("reverse", "--list"), reverse_port)
                if target is None:
                    return
                if target != "tcp:" + str(reverse_port):
                    raise ValueError("The reverse target changed ownership; it was preserved")
                ui.adb("reverse", "--remove", "tcp:" + str(reverse_port))
            cleanup("owned-reverse-remove", remove_reverse)
        if cleanup_errors:
            report.update(status="FAIL", qualificationGate="NOT_QUALIFIED", cleanupErrors=cleanup_errors)
            exit_code = 1
        report["finished"] = datetime.now(timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    print(f"FPGA {report['status']}: {output}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
