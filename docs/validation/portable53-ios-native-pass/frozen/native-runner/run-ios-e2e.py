#!/usr/bin/env python3
"""Run native editor scenarios against an explicit, fingerprinted iPhone/iPad artifact."""

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
import subprocess
import traceback
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/ios_e2e_ui"
CONTRACT = ROOT / "fixtures/user-scenarios/editor-actions-v1.json"
FIXTURE = ROOT / "fixtures/user-scenarios/editing-not.circ"
IO_CONTRACT = ROOT / "fixtures/user-scenarios/io-actions-v1.json"
CLIPBOARD_RECEIVER_BUNDLE_ID = "thalesmms.logisim.validation.clipboardreceiver"


def support():
    spec = importlib.util.spec_from_file_location("ios_accessibility_support", ROOT / "scripts/run-ios-accessibility.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture_xml(title: str) -> bytes:
    root = ET.fromstring(FIXTURE.read_bytes())
    circuits = root.findall("circuit")
    if len(circuits) != 1:
        raise ValueError("The Files workflow requires a single-circuit shared fixture")
    old = circuits[0].attrib["name"]
    circuits[0].set("name", title)
    for main in root.findall("main"):
        main.set("name", title)
    for component in root.findall("circuit/comp"):
        if component.attrib.get("name") == old:
            component.set("name", title)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def prepare(output: Path, bundle_id: str, run_id: str, documents: Path, *,
            source_pin: str, build_sha256: str, workspace: Path, catalog_root: Path, asset_cache: Path, simulator_udid: str) -> tuple[Path, dict]:
    common = support()
    # Reuse canonical catalog generation, plist escaping and shared native helpers.
    existing = common.prepare(output, catalog_root, bundle_id, "I")
    project = existing.parent
    shutil.copyfile(ROOT / "scripts/ios_native_ui/NativeTextReplacement.swift", project / "NativeTextReplacement.swift")
    for name in ("project.yml", "FilesEndToEndTests.swift", "EditorEndToEndTests.swift", "ObservedProjectXML.swift",
                 "IoEndToEndTests.swift", "InspectorEndToEndTests.swift",
                 "NativeFilesDestinationNavigator.swift", "DestinationExportEndToEndTests.swift",
                 "VisualExportEndToEndTests.swift", "XmlDialogEndToEndTests.swift", "ClipboardReceiverUI.swift",
                 "PortableProjectEndToEndTests.swift", "PortableWorkspaceEvidence.swift",
                 "ExternalProviderEndToEndTests.swift", "NativeFilesPeerNavigator.swift"):
        shutil.copyfile(SOURCE / name, project / name)
    shutil.copytree(SOURCE / "ClipboardReceiver", project / "ClipboardReceiver")
    shutil.copyfile(CONTRACT, project / CONTRACT.name)
    shutil.copyfile(IO_CONTRACT, project / IO_CONTRACT.name)
    titles = {name: f"IOS-E2E-{run_id}-{name}" for name in ("cold", "warm", "external")}
    files = {name: str(documents / f"{name}.circ") for name in (*titles, "incompatible")}
    io = documents / "io-showcase.circ"
    shutil.copyfile(ROOT / "examples/io-showcase.circ", io)
    io_held = documents / "io-held-output.circ"
    shutil.copyfile(ROOT / "examples/io-held-output.circ", io_held)
    io_held_targets = documents / "io-held-output.targets.json"
    shutil.copyfile(ROOT / "examples/io-held-output.targets.json", io_held_targets)
    portable = documents / "portable-projects"
    portable.mkdir()
    shutil.copyfile(ROOT / "fixtures/portable-projects/portable53.lsebdl", portable / "portable53.lsebdl")
    shutil.copytree(ROOT / "fixtures/portable-projects/sources", portable / "sources")
    files.update(io=str(io), ioHeld=str(io_held), ioHeldTargets=str(io_held_targets), portableBundle=str(portable / "portable53.lsebdl"), portableSources=str(portable / "sources"))
    visual = documents / f"visual-export-{run_id}.circ"
    visual_bytes = (ROOT / "fixtures/visual-export/qualification.circ").read_bytes()
    visual.write_bytes(visual_bytes)
    visual_main = ET.fromstring(visual_bytes).find("main")
    if visual_main is None or not visual_main.get("name"):
        raise ValueError("The visual export fixture must name its main circuit")
    files["visualExport"] = str(visual)
    titles["visualExport"] = visual_main.attrib["name"]
    visual_contract = (ROOT / "fixtures/visual-export/contract.json").read_text()
    configuration = {"run_id": run_id, "simulatorUDID": simulator_udid, "fixtures": files, "titles": titles,
                     "documents": str(documents), "contract": "editor-actions-v1",
                     "workspace": str(workspace),
                     "visualExport": {"outputDirectory": str(documents),
                                      "assetCacheDirectory": str(asset_cache),
                                      "contractJSON": visual_contract,
                                      "contractSHA256": hashlib.sha256(visual_contract.encode("utf-8")).hexdigest(),
                                      "fixtureSHA256": hashlib.sha256(visual_bytes).hexdigest(),
                                      "sourcePin": source_pin, "buildSHA256": build_sha256}}
    (project / "Run.json").write_text(json.dumps(configuration, indent=2) + "\n")
    common.run(["xcodegen", "generate", "--spec", str(project / "project.yml")])
    # XcodeGen can regenerate the plist. Keep input values as actual plist data.
    info = {"CFBundleDevelopmentRegion": "en", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
            "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": "$(PRODUCT_NAME)", "CFBundlePackageType": "BNDL",
            "CFBundleShortVersionString": "1.0", "CFBundleVersion": "1",
            "TargetAppBundleIdentifier": bundle_id, "ComponentSearch": "I"}
    with (project / "TestInfo.plist").open("wb") as stream:
        plistlib.dump(info, stream)
    return project / "LogisimEditorHarness.xcodeproj", configuration


def ensure_main_app_installed(common, simulator: str, app: Path, requested: dict, evidence: dict) -> None:
    """Keep an identical installed app and verify every installation before using its data container."""
    keys = ("bundle_id", "executable_sha256", "bundle_tree_sha256")
    if any(not isinstance(requested.get(key), str) or not requested[key] for key in keys):
        raise ValueError("The requested main app fingerprint is incomplete")
    bundle_id = requested["bundle_id"]

    def installed_artifact() -> dict:
        raw = common.run(["xcrun", "simctl", "get_app_container", simulator, bundle_id, "app"])
        installed = Path(raw)
        if not installed.is_absolute() or not installed.is_dir():
            raise ValueError("The installed main app container is unavailable")
        fingerprint = common.artifact(installed, bundle_id)
        if not isinstance(fingerprint, dict) or any(
                not isinstance(fingerprint.get(key), str) or not fingerprint[key] for key in keys):
            raise ValueError("The installed main app fingerprint is incomplete")
        return fingerprint

    probe_errors = (subprocess.CalledProcessError, OSError, ValueError, KeyError, TypeError, AttributeError)
    evidence["status"] = "CHECKING_INSTALLED"
    try:
        evidence["before"] = installed_artifact()
    except probe_errors as error:
        evidence["before_error"] = {"type": type(error).__name__, "message": str(error)}
    else:
        if all(evidence["before"][key] == requested[key] for key in keys):
            evidence.update(status="REUSED_IDENTICAL", installed=evidence["before"])
            return

    evidence["status"] = "INSTALLING"
    try:
        common.run(["xcrun", "simctl", "install", simulator, str(app)])
    except (subprocess.CalledProcessError, OSError):
        evidence["status"] = "INSTALL_FAILED"
        raise
    evidence["status"] = "VERIFYING_INSTALLED"
    try:
        evidence["installed"] = installed_artifact()
    except probe_errors as error:
        evidence["status"] = "VERIFICATION_FAILED"
        evidence["verification_error"] = {"type": type(error).__name__, "message": str(error)}
        raise RuntimeError("The installed main app fingerprint is unavailable after installation") from error
    if any(evidence["installed"][key] != requested[key] for key in keys):
        evidence["status"] = "VERIFICATION_FAILED"
        raise RuntimeError("The installed main app fingerprint differs from the requested artifact")
    evidence["status"] = "INSTALLED_VERIFIED"


def stage_external_peer(common, simulator: str, receiver: Path, primary_container: Path,
                        documents: Path, run_id: str) -> dict:
    """Stage a separate Files provider document; a Logisim-owned fixture cannot prove external writes."""
    if not re.fullmatch(r"[0-9a-f]{12}", run_id):
        raise ValueError("The external peer requires a canonical run ID")
    with (receiver / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if info.get("CFBundleIdentifier") != CLIPBOARD_RECEIVER_BUNDLE_ID:
        raise ValueError("The external peer bundle identifier differs from the installed receiver")
    if info.get("UIFileSharingEnabled") is not True or info.get("LSSupportsOpeningDocumentsInPlace") is not True:
        raise ValueError("The external peer receiver must expose Documents for in-place Files access")
    display_name = info.get("CFBundleDisplayName") or info.get("CFBundleName")
    if not isinstance(display_name, str) or not display_name.strip() or "$(" in display_name or "${" in display_name:
        raise ValueError("The external peer has no resolved display name")
    peer_raw = Path(common.run(["xcrun", "simctl", "get_app_container", simulator, CLIPBOARD_RECEIVER_BUNDLE_ID, "data"]))
    if not peer_raw.is_absolute() or not primary_container.is_absolute() or not documents.is_absolute():
        raise ValueError("External peer provenance requires absolute container paths")
    peer_container = peer_raw.resolve()
    primary = primary_container.resolve()
    expected_documents = (primary / "Documents" / "LogisimE2E" / run_id).resolve()
    if (not peer_container.is_dir() or not expected_documents.is_relative_to(primary) or
            documents.resolve() != expected_documents or not documents.is_dir()):
        raise ValueError("The external peer must be staged beside the declared primary run")
    if peer_container.is_relative_to(primary) or primary.is_relative_to(peer_container):
        raise ValueError("The external peer container must be separate from the Logisim container")
    peer_documents = (peer_container / "Documents" / "LogisimE2E" / run_id).resolve()
    if not peer_documents.is_relative_to(peer_container) or peer_documents.is_relative_to(primary):
        raise ValueError("The external peer Documents path escaped its own container")
    sidecar = documents / "external-peer.json"
    if peer_documents.exists() or sidecar.exists():
        raise ValueError("Use a new external peer run; previous fixture and control evidence is preserved")
    payload = fixture_xml(f"IOS-PEER-{run_id}")
    peer_documents.mkdir(parents=True, exist_ok=False)
    fixture = peer_documents / "peer.circ"
    control = peer_documents / "control.circ"
    with fixture.open("xb") as stream:
        stream.write(payload)
    with control.open("xb") as stream:
        stream.write(payload)
    metadata = {"bundleID": CLIPBOARD_RECEIVER_BUNDLE_ID, "displayName": display_name,
                "documents": str(peer_documents), "fixture": str(fixture), "control": str(control),
                "originalSHA256": hashlib.sha256(payload).hexdigest()}
    with sidecar.open("x") as stream:
        stream.write(json.dumps({"externalPeer": metadata}, indent=2) + "\n")
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator", required=True)
    parser.add_argument("--bundle-id", default="thalesmms.logisim.mobile")
    parser.add_argument("--baseline-sha", required=True, help="Declared source of the supplied build, recorded with its actual hash")
    parser.add_argument("--catalog-root", type=Path, default=ROOT,
                        help="Localization source of the supplied app; harness sources remain in this checkout")
    parser.add_argument("--app", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--derived-data", type=Path, help="Reuse only build cache; each output/evidence directory stays new")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--build-only", action="store_true")
    parser.add_argument("--alpha", action="store_true", help="Debugging only; never produce a qualification pass")
    parser.add_argument("--only-testing", action="append", default=[])
    parser.add_argument("--test-timeout", type=int, default=600,
                        help="Overall XCTest case allowance; individual UI readiness assertions keep their own limits")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", args.simulator):
        parser.error("Reserve an explicit Simulator UUID")
    if not re.fullmatch(r"[0-9a-fA-F]{7,40}", args.baseline_sha):
        parser.error("--baseline-sha must identify the source build")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)+", args.bundle_id):
        parser.error("Invalid bundle identifier")
    if not args.prepare_only and args.app is None:
        parser.error("Supply the actual app artifact")
    if args.test_timeout <= 0:
        parser.error("--test-timeout must be positive")
    common = support()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Use a new output directory; previous evidence is preserved")
    output.mkdir(parents=True, exist_ok=True)
    derived_data = (args.derived_data or output / "DerivedData").resolve()
    run_id = uuid.uuid4().hex[:12]
    report = {"started": datetime.now(timezone.utc).isoformat(), "run_id": run_id,
              "declared_baseline_sha": args.baseline_sha, "alpha": args.alpha, "simulator": args.simulator,
              "derived_data": str(derived_data), "physical_device": False, "native_execution_gate": "NATIVE_NOT_RUN",
              "test_timeout_seconds": args.test_timeout,
              "qualification_gate": "ALPHA_ONLY" if args.alpha else "NOT_QUALIFIED",
              "selected_tests": args.only_testing, "external_peer_preflight": "NOT_RUN",
              "main_app_installation": {"status": "NOT_RUN"}}
    report_path = output / "results.json"
    documents = output / "prepare-only-documents"
    workspace = output / "prepare-only-workspace"
    asset_cache = output / "prepare-only-asset-cache"
    exit_code = 0
    external_peer = None
    try:
        if not args.prepare_only:
            report["artifact"] = common.artifact(args.app.resolve(), args.bundle_id)
            devices = json.loads(common.run(["xcrun", "simctl", "list", "devices", "available", "-j"]))["devices"]
            device = next((dict(item, runtime=runtime) for runtime, items in devices.items()
                           for item in items if item["udid"].lower() == args.simulator.lower()), None)
            if not device or device["state"] != "Booted":
                raise ValueError("The reserved Simulator must already be booted")
            report["device"] = device
            ensure_main_app_installed(common, args.simulator, args.app.resolve(), report["artifact"],
                                      report["main_app_installation"])
            container = Path(common.run(["xcrun", "simctl", "get_app_container", args.simulator, args.bundle_id, "data"]))
            documents = container / "Documents" / "LogisimE2E" / run_id
            workspace = container / "Library/Application Support/LogisimMobile/workspace"
            asset_cache = container / "Library/Caches/LogisimAssetExports"
        documents.mkdir(parents=True, exist_ok=False)
        project, configuration = prepare(output, args.bundle_id, run_id, documents,
                                         source_pin=args.baseline_sha,
                                         build_sha256=report.get("artifact", {}).get("bundle_tree_sha256", "NOT_RUN"),
                                         workspace=workspace, catalog_root=args.catalog_root.resolve(), asset_cache=asset_cache,
                                         simulator_udid=report.get("device", {}).get("udid", args.simulator))
        report["configuration"] = configuration
        report["catalog"] = {"source_root": str(args.catalog_root.resolve()),
                             "sha256": hashlib.sha256((project.parent / "Catalog.json").read_bytes()).hexdigest()}
        report["harness_sources"] = {
            source.name: hashlib.sha256(source.read_bytes()).hexdigest()
            for source in sorted(project.parent.glob("*.swift"))
        }
        for name, title in configuration["titles"].items():
            if name == "visualExport":
                continue
            Path(configuration["fixtures"][name]).write_bytes(fixture_xml(title))
        Path(configuration["fixtures"]["incompatible"]).write_bytes(b"This is deliberately not a compatible circuit format.\n")
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        if args.prepare_only:
            report["status"] = "PREPARED"
            return 0
        command = ["xcodebuild", "-project", str(project), "-scheme", "EditorHarness",
                   "-destination", f"platform=iOS Simulator,id={args.simulator}",
                   "-derivedDataPath", str(derived_data), "CODE_SIGNING_ALLOWED=NO"]
        code = common.run([*command, "build-for-testing"], log=output / "build.log")
        report["build"] = {"status": "PASS" if code == 0 else "FAIL", "exit_code": code}
        exit_code = code
        if code == 0:
            test_bundle = derived_data / "Build/Products/Debug-iphonesimulator/EditorEndToEndTests-Runner.app/PlugIns/EditorEndToEndTests.xctest"
            for name in ("Catalog.json", "Run.json", "editor-actions-v1.json", "io-actions-v1.json"):
                resource = test_bundle / name
                if not resource.is_file():
                    raise RuntimeError(f"The test bundle is missing {name}; no app scenarios were run")
                json.loads(resource.read_text())
            report["resource_preflight"] = "PASS"
            receiver = derived_data / "Build/Products/Debug-iphonesimulator/ClipboardReceiver.app"
            report["clipboard_receiver"] = common.artifact(receiver, CLIPBOARD_RECEIVER_BUNDLE_ID)
            common.run(["xcrun", "simctl", "install", args.simulator, str(receiver)])
            external_peer = stage_external_peer(common, args.simulator, receiver, container, documents, run_id)
            report["external_peer"] = external_peer
            report["external_peer_preflight"] = "PASS"
        if code == 0 and not args.build_only:
            result = output / "editor.xcresult"
            selection = [f"-only-testing:{name}" for name in args.only_testing]
            report["native_execution_gate"] = "NATIVE_RUNNING"
            report["execution"] = {"status": "RUNNING", "xcresult": str(result), "selection": args.only_testing}
            report_path.write_text(json.dumps(report, indent=2) + "\n")
            code = common.run([*command, "test-without-building", "-parallel-testing-enabled", "NO", "-collect-test-diagnostics", "never",
                               "-test-timeouts-enabled", "YES", "-default-test-execution-time-allowance", str(args.test_timeout),
                               "-resultBundlePath", str(result), *selection], log=output / "test.log")
            report["execution"] = {"status": "PASS" if code == 0 else "FAIL", "exit_code": code,
                                   "xcresult": str(result), "selection": args.only_testing}
            report["native_execution_gate"] = "NATIVE_PASS" if code == 0 else "NATIVE_FAIL"
            common.run(["xcrun", "xcresulttool", "export", "attachments", "--path", str(result),
                        "--output-path", str(output / "attachments")], log=output / "attachments.log")
            exit_code = code
            report["qualification_gate"] = "ALPHA_ONLY" if args.alpha else "FAIL" if code != 0 else "PARTIAL" if args.only_testing else "PLATFORM_RUN_PASS"
        report["status"] = "ALPHA_ONLY" if args.alpha and exit_code == 0 else (
            "PASS" if exit_code == 0 and not args.build_only else "BUILT" if exit_code == 0 else "FAIL")
    except (Exception, KeyboardInterrupt) as error:
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report["status"] = "FAIL"
        if report["native_execution_gate"] == "NATIVE_RUNNING":
            report["native_execution_gate"] = "NATIVE_INCOMPLETE"
            report["execution"]["status"] = "INCOMPLETE"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        (output / "execution-error.txt").write_text(traceback.format_exc())
    finally:
        if documents.exists():
            shutil.copytree(documents, output / "documents", dirs_exist_ok=True)
        if external_peer is not None:
            peer_documents = Path(external_peer["documents"])
            if peer_documents.exists():
                shutil.copytree(peer_documents, output / "external-peer-documents", dirs_exist_ok=True)
        if not args.prepare_only:
            common.run(["xcrun", "simctl", "io", args.simulator, "screenshot", str(output / "last-screen.png")],
                       log=output / "screenshot.log")
            common.run(["xcrun", "simctl", "spawn", args.simulator, "log", "show", "--last", "15m",
                        "--style", "compact", "--predicate", 'process == "LogisimMobile"'],
                       log=output / "simulator.log")
        report["finished"] = datetime.now(timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(report_path)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
