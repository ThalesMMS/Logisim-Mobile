#!/usr/bin/env python3
"""Run native editor scenarios against an explicit, fingerprinted iPhone/iPad artifact."""

from __future__ import annotations

import argparse
import contextlib
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/ios_e2e_ui"
CONTRACT = ROOT / "fixtures/user-scenarios/editor-actions-v1.json"
FIXTURE = ROOT / "fixtures/user-scenarios/editing-not.circ"
INSPECTOR_FIXTURE = ROOT / "fixtures/user-scenarios/inspector-width.circ"
IO_CONTRACT = ROOT / "fixtures/user-scenarios/io-actions-v1.json"
IO_FIXTURE = ROOT / "examples/io-showcase.circ"
IO_FIXTURE_SHA256 = "a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9"
CLIPBOARD_RECEIVER_BUNDLE_ID = "thalesmms.logisim.validation.clipboardreceiver"
ALPHA_ONLY_TESTS = ("EditorEndToEndTests/VisualExportEndToEndTests/testVisualExportFieldCensus",)
IO_FILES_COPY_TESTS = frozenset(
    "EditorEndToEndTests/IoEndToEndTests/" + name for name in (
        "testIoDisplaySignalsAndNativeDrawing", "testIoDimensionChangesRemainExportable",
        "testIoMomentaryControlsAndJoystick", "testIoKeyboardTtyAndCapacity",
    )
)


def support():
    spec = importlib.util.spec_from_file_location("ios_accessibility_support", ROOT / "scripts/run-ios-accessibility.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture_xml(title: str, *, tools: bool = False) -> bytes:
    root = ET.fromstring(FIXTURE.read_bytes())
    if tools:
        # A file without <toolbar> has no tools, as in Evolution (#344); the peer flow switches Poke and Edit.
        index = list(root).index(root.find("main"))
        root.insert(index, ET.Element("lib", {"desc": "#Base", "name": "2"}))
        toolbar = ET.Element("toolbar")
        for name in ("Poke Tool", "Edit Tool"):
            ET.SubElement(toolbar, "tool", {"lib": "2", "name": name})
        root.insert(index + 2, toolbar)
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


def inspector_fixture_xml(title: str, label: str) -> bytes:
    root = ET.fromstring(INSPECTOR_FIXTURE.read_bytes())
    circuits = root.findall("circuit")
    pins = root.findall("circuit/comp")
    main = root.find("main")
    if len(circuits) != 1 or len(pins) != 1 or main is None or pins[0].get("name") != "Pin":
        raise ValueError("The Inspector fixture must contain exactly one circuit and one Pin")
    labels = pins[0].findall("a[@name='label']")
    if len(labels) != 1 or pins[0].find("a[@name='width'][@val='1']") is None:
        raise ValueError("The Inspector fixture must have a single label and literal width 1")
    main.set("name", title)
    circuits[0].set("name", title)
    labels[0].set("val", label)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def prepare(output: Path, bundle_id: str, run_id: str, documents: Path, *,
            source_pin: str, build_sha256: str, workspace: Path, catalog_root: Path, asset_cache: Path, simulator_udid: str) -> tuple[Path, dict]:
    common = support()
    # Reuse canonical catalog generation, plist escaping and shared native helpers.
    existing = common.prepare(output, catalog_root, bundle_id, "I")
    project = existing.parent
    shutil.copyfile(ROOT / "scripts/ios_native_ui/NativeTextReplacement.swift", project / "NativeTextReplacement.swift")
    for name in ("DocumentWindowRules.swift", "DocumentWindowScope.swift", "FilesCopySource.swift", "NativeFilesCopySetup.swift"):
        shutil.copyfile(ROOT / "scripts/ios_native_ui" / name, project / name)
    for name in ("project.yml", "FilesEndToEndTests.swift", "EditorEndToEndTests.swift", "ObservedProjectXML.swift",
                 "IoEndToEndTests.swift", "InspectorEndToEndTests.swift",
                 "NativeFilesDestinationNavigator.swift", "DestinationExportEndToEndTests.swift",
                 "VisualExportEndToEndTests.swift", "XmlDialogEndToEndTests.swift", "ClipboardReceiverUI.swift",
                 "PortableProjectEndToEndTests.swift", "PortableWorkspaceEvidence.swift",
                 "ExternalProviderEndToEndTests.swift", "NativeFilesPeerNavigator.swift", "FocusEndToEndTests.swift",
                 "DisplayPreferencesEndToEndTests.swift", "UnicodeLabelsEndToEndTests.swift"):
        shutil.copyfile(SOURCE / name, project / name)
    shutil.copytree(SOURCE / "ClipboardReceiver", project / "ClipboardReceiver")
    shutil.copyfile(CONTRACT, project / CONTRACT.name)
    shutil.copyfile(IO_CONTRACT, project / IO_CONTRACT.name)
    legacy_contract = ROOT / "fixtures/user-scenarios/legacy-original-actions-v1.json"
    shutil.copyfile(legacy_contract, project / legacy_contract.name)
    titles = {name: f"IOS_E2E_{run_id}_{name}" for name in ("cold", "warm", "external")}
    files = {name: str(documents / f"{name}.circ") for name in (*titles, "incompatible")}
    legacy = json.loads(legacy_contract.read_text())
    legacy_path = documents / "legacy-original.circ"
    shutil.copyfile(ROOT / legacy["fixture"], legacy_path)
    files["legacyOriginal"] = str(legacy_path)
    mouse = ET.fromstring(legacy_path.read_bytes())
    mouse_title = f"IOS_E2E_{run_id}_mouse_mappings"
    mouse.find("main").set("name", mouse_title)
    mouse_circuit = mouse.find("circuit")
    mouse_circuit.set("name", mouse_title)
    mouse_circuit.find("a[@name='circuit']").set("val", mouse_title)
    mouse.insert(0, ET.Element("lib", {"desc": "#Base", "name": "6"}))
    mouse_mappings = mouse.find("mappings")
    ET.SubElement(mouse_mappings, "tool", {"lib": "6", "map": "Button3", "name": "Poke Tool"})
    pin_tool = ET.SubElement(mouse_mappings, "tool", {"lib": "0", "map": "Button1", "name": "Pin"})
    ET.SubElement(pin_tool, "a", {"name": "width", "val": "8"})
    # A file without <toolbar> tools offers no Wire tool (#344); the mappings must override a selected tool.
    ET.SubElement(mouse.find("toolbar"), "tool", {"lib": "6", "name": "Wiring Tool"})
    mouse_circuit.find("comp[@name='Pin'][@loc='(100,100)']").set("logisim-mobile-id", f"mouse-in-{run_id}")
    mouse_circuit.find("comp[@name='Pin'][@loc='(300,100)']").set("logisim-mobile-id", f"mouse-out-{run_id}")
    mouse_path = documents / "mouse-mappings.circ"
    mouse_path.write_bytes(ET.tostring(mouse, encoding="utf-8", xml_declaration=True))
    files["mouseMappings"] = str(mouse_path)
    titles["mouseMappings"] = mouse_title
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
    inspector = documents / "inspector-width.circ"
    titles["inspector"] = f"IOS_E2E_{run_id}_inspector"
    inspector_label = f"INSPECTOR_{run_id}"
    inspector_bytes = inspector_fixture_xml(titles["inspector"], inspector_label)
    inspector.write_bytes(inspector_bytes)
    files["inspector"] = str(inspector)
    visual_contract = (ROOT / "fixtures/visual-export/contract.json").read_text()
    configuration = {"run_id": run_id, "simulatorUDID": simulator_udid, "fixtures": files, "titles": titles,
                     "documents": str(documents), "contract": "editor-actions-v1",
                     "workspace": str(workspace),
                     "inspector": {"label": inspector_label,
                                   "fixtureSHA256": hashlib.sha256(inspector_bytes).hexdigest()},
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
    payload = fixture_xml(f"IOS_PEER_{run_id}", tools=True)
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


def stage_io_copy_source(peer: dict, primary_container: Path, run_id: str) -> dict:
    """Write the approved I/O source only to this receiver's separate run folder."""
    if not re.fullmatch(r"[0-9a-f]{12}", run_id):
        raise ValueError("The I/O Files Copy source requires a canonical run ID")
    if peer.get("bundleID") != CLIPBOARD_RECEIVER_BUNDLE_ID:
        raise ValueError("The I/O Files Copy source requires the validated receiver")
    display_name = peer.get("displayName")
    if not isinstance(display_name, str) or not display_name.strip() or "$(" in display_name or "${" in display_name:
        raise ValueError("The I/O Files Copy source requires an exact receiver display name")
    declared = peer.get("documents")
    if not isinstance(declared, str) or not Path(declared).is_absolute() or not primary_container.is_absolute():
        raise ValueError("The I/O Files Copy source requires absolute container paths")
    documents = Path(declared)
    resolved = documents.resolve(strict=True)
    primary = primary_container.resolve(strict=True)
    if (documents != resolved or not documents.is_dir() or not primary.is_dir() or
            documents.parts[-3:] != ("Documents", "LogisimE2E", run_id)):
        raise ValueError("The I/O source must use the receiver's canonical Documents/LogisimE2E/runID folder")
    peer_container = documents.parents[2]
    if peer_container.is_relative_to(primary) or primary.is_relative_to(peer_container):
        raise ValueError("The I/O source receiver must be separate from the main app container")
    payload = IO_FIXTURE.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if digest != IO_FIXTURE_SHA256:
        raise ValueError("The I/O Files Copy fixture differs from the approved bytes")
    fixture = documents / "io-showcase-source.circ"
    with fixture.open("xb") as stream:
        stream.write(payload)
    return {"route": "files-copy", "peerDisplayName": display_name, "runID": run_id,
            "documents": str(documents), "fixture": str(fixture), "fixtureSHA256": digest}


@contextlib.contextmanager
def build_lock(path: Path | None, poll_seconds: float = 20.0):
    """Hold a host-wide mkdir lock only for the build step; test execution runs without it."""
    if path is None:
        yield
        return
    while True:
        try:
            path.mkdir()
            break
        except FileExistsError:
            time.sleep(poll_seconds)
    try:
        yield
    finally:
        path.rmdir()


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
    parser.add_argument("--io-setup", choices=("system-open", "files-copy"), default="system-open",
                        help="Explicit I/O fixture entry route; Files Copy requires selected I/O methods")
    parser.add_argument("--build-lock", type=Path,
                        help="Directory lock (mkdir) held only around build-for-testing, shared by every build on the host")
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
    if not args.alpha and any(test in ALPHA_ONLY_TESTS for test in args.only_testing):
        parser.error("Diagnostic census tests require --alpha; they cannot qualify export")
    if args.io_setup == "files-copy" and (
            not args.only_testing or len(args.only_testing) != len(set(args.only_testing)) or
            any(test not in IO_FILES_COPY_TESTS for test in args.only_testing)):
        parser.error("Files Copy requires unique, explicit selectors from the four I/O methods")
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
              "skipped_alpha_tests": [] if args.alpha else list(ALPHA_ONLY_TESTS),
              "main_app_installation": {"status": "NOT_RUN"}}
    report_path = output / "results.json"
    documents = output / "prepare-only-documents"
    workspace = output / "prepare-only-workspace"
    asset_cache = output / "prepare-only-asset-cache"
    exit_code = 0
    external_peer = None
    if args.prepare_only and args.io_setup == "files-copy":
        container = output / "prepare-only-primary"
        documents = container / "Documents" / "LogisimE2E" / run_id
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
        for name in ("cold", "warm", "external"):
            title = configuration["titles"][name]
            Path(configuration["fixtures"][name]).write_bytes(fixture_xml(title))
        Path(configuration["fixtures"]["incompatible"]).write_bytes(b"This is deliberately not a compatible circuit format.\n")
        if args.prepare_only and args.io_setup == "files-copy":
            peer_documents = output / "prepare-only-receiver" / "Documents" / "LogisimE2E" / run_id
            peer_documents.mkdir(parents=True, exist_ok=False)
            with (SOURCE / "ClipboardReceiver/Info.plist").open("rb") as stream:
                peer_info = plistlib.load(stream)
            virtual_peer = {"bundleID": CLIPBOARD_RECEIVER_BUNDLE_ID,
                            "displayName": peer_info.get("CFBundleDisplayName") or peer_info.get("CFBundleName"),
                            "documents": str(peer_documents)}
            configuration["ioSetup"] = stage_io_copy_source(virtual_peer, container, run_id)
            (project.parent / "Run.json").write_text(json.dumps(configuration, indent=2) + "\n")
            report["io_setup_preflight"] = "PREPARED_ONLY"
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        if args.prepare_only:
            report["status"] = "PREPARED"
            return 0
        command = ["xcodebuild", "-project", str(project), "-scheme", "EditorHarness",
                   "-destination", f"platform=iOS Simulator,id={args.simulator}",
                   "-derivedDataPath", str(derived_data), "CODE_SIGNING_ALLOWED=NO",
                   # A host Xcode preference for workspace-relative build locations would otherwise move products.
                   f"SYMROOT={derived_data / 'Build/Products'}", f"OBJROOT={derived_data / 'Build/Intermediates.noindex'}"]
        with build_lock(args.build_lock):
            code = common.run([*command, "build-for-testing"], log=output / "build.log")
        report["build"] = {"status": "PASS" if code == 0 else "FAIL", "exit_code": code}
        exit_code = code
        if code == 0:
            test_bundle = derived_data / "Build/Products/Debug-iphonesimulator/EditorEndToEndTests-Runner.app/PlugIns/EditorEndToEndTests.xctest"
            for name in ("Catalog.json", "Run.json", "editor-actions-v1.json", "io-actions-v1.json", "legacy-original-actions-v1.json"):
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
            if args.io_setup == "files-copy":
                configuration["ioSetup"] = stage_io_copy_source(external_peer, container, run_id)
                for path in (project.parent / "Run.json", test_bundle / "Run.json"):
                    path.write_text(json.dumps(configuration, indent=2) + "\n")
                report["io_setup_preflight"] = "PASS"
        if code == 0 and not args.build_only:
            result = output / "editor.xcresult"
            selection = [f"-only-testing:{name}" for name in args.only_testing]
            exclusions = [f"-skip-testing:{name}" for name in report["skipped_alpha_tests"]]
            report["native_execution_gate"] = "NATIVE_RUNNING"
            report["execution"] = {"status": "RUNNING", "xcresult": str(result), "selection": args.only_testing}
            report_path.write_text(json.dumps(report, indent=2) + "\n")
            code = common.run([*command, "test-without-building", "-parallel-testing-enabled", "NO", "-collect-test-diagnostics", "never",
                               "-test-timeouts-enabled", "YES", "-default-test-execution-time-allowance", str(args.test_timeout),
                               "-resultBundlePath", str(result), *selection, *exclusions], log=output / "test.log")
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
