#!/usr/bin/env python3
"""Prepare an isolated provider-auth harness; Native requires a run-bound ROOT lease."""
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
import sys
import traceback
import uuid


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/ios_provider_auth_ui"
MAIN_ID = "thalesmms.logisim.mobile"
FIXTURE_ID = "thalesmms.logisim.validation.revocationfixture"
PROVIDER_ID = FIXTURE_ID + ".provider"
GROUP_ID = "group." + FIXTURE_ID
MAIN_SOURCE = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
FIXTURE_SOURCE = "601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd"
MAIN_MANIFEST_SHA = "7e83088f9d1ccee3c268e9dec954db9c351b47db9cd01b9fb652e8f1235b7b76"
FIXTURE_MANIFEST_SHA = "c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159"
REVIEW_SHA = "254c199cefd0f1659825d8c81bae5ea1504735265314ddeea33bbd3ff22a8ae8"
VERIFIER_SHA = "123bae1c9879682785bcf77d4f817bdae5c5c80ac6970bb5231a285c2766ec89"
MAIN_TREE = "813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef"
FIXTURE_TREE = "e24e3fb58b07439ba1f9e50e955c76db72509dbf07ba0d8ea19ecce7f3f038d5"
PROVIDER_TREE = "e91733f45ac08a55374bce04083b333004ae4eda1d7bd83f758f84956ecc9f7f"
SELECTOR = "ProviderAuthenticationEndToEndTests/ProviderAuthenticationEndToEndTests/testAuthenticationDenialAfterGrantPreservesProject"
OPERATION = "MAIN_PLUS_PROVIDER_AUTH_AFTER_GRANT"
TARGET = "ProviderAuthenticationEndToEndTests"
BUILD_WRAPPER = Path("/tmp/logisim-gradle-serial.py")
BUILD_WRAPPER_SHA = "61fb2ff0e8e1969b80c2880424683834a5dcd3cdcf79450a40dba15dc4f6471b"
BUILD_LOCK = Path("/tmp/logisim-gradle.lock")
RESOURCE_NAMES = ("Catalog.json", "Run.json", "ProvenanceMainArtifact.json", "ProvenanceSignedFixture.json", "ProvenanceArtifactReview.json")
PROVENANCE_PINS = {"ProvenanceMainArtifact.json": MAIN_MANIFEST_SHA, "ProvenanceSignedFixture.json": FIXTURE_MANIFEST_SHA,
                   "ProvenanceArtifactReview.json": REVIEW_SHA}
SOURCES = {"NativeUIHarness.swift": ROOT / "scripts/ios_native_ui/NativeUIHarness.swift",
           "NativeFilesDestinationNavigator.swift": ROOT / "scripts/ios_e2e_ui/NativeFilesDestinationNavigator.swift",
           **{name: SOURCE / name for name in ("ProviderAuthenticationEndToEndTests.swift", "ProviderAuthenticationOracle.swift",
                                               "ProviderFixtureController.swift", "ProviderFilesNavigator.swift")}}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def support():
    return module(ROOT / "scripts/run-ios-accessibility.py", "provider_auth_support")


class BuildInterrupted(KeyboardInterrupt):
    def __init__(self, outcome):
        super().__init__("Build interruption deferred until the lock-owning wrapper completed")
        self.outcome = outcome


class Commands:
    """Record commands and keep every device/build operation behind the execution gate."""
    def __init__(self, output: Path, allow_native=False):
        self.output = Path(output)
        self.allow_native = allow_native
        self.records = []

    def run(self, arguments, label, check=True, timeout=60, input_bytes=None):
        arguments = [str(value) for value in arguments]
        require(re.fullmatch(r"[a-z0-9-]+", label), "Invalid evidence command label")
        require(input_bytes is None or isinstance(input_bytes, bytes), "Command input must preserve its raw bytes")
        native = Path(arguments[0]).name in {"xcodebuild", "xcrun"} or str(BUILD_WRAPPER) in arguments[:2]
        require(not native or self.allow_native, "Native/build command requires an admitted ROOT lease")
        wrapped_build = len(arguments) > 1 and arguments[1] == str(BUILD_WRAPPER)
        require(not wrapped_build or timeout is None, "The shared build wrapper must finish without an outer timeout")
        require(not wrapped_build or input_bytes is None, "The build wrapper does not accept command input")
        started = datetime.now(timezone.utc).isoformat()
        prefix = f"{len(self.records):03d}-{label}"
        interrupted = False
        try:
            if wrapped_build:
                process = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                while True:
                    try:
                        stdout, stderr = process.communicate()
                        break
                    except KeyboardInterrupt:
                        interrupted = True
                result = subprocess.CompletedProcess(arguments, process.returncode, stdout, stderr)
            else:
                options = {"input": input_bytes} if input_bytes is not None else {}
                result = subprocess.run(arguments, capture_output=True, timeout=timeout, check=False, **options)
            stdout, stderr, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr, code = error.stdout or b"", error.stderr or b"", None
            self._record(arguments, prefix, started, stdout, stderr, code, timeout, input_bytes=input_bytes)
            raise
        self._record(arguments, prefix, started, stdout, stderr, code, timeout, interrupted, input_bytes)
        if interrupted:
            raise BuildInterrupted(result)
        if check and code != 0:
            raise subprocess.CalledProcessError(code, arguments, output=stdout, stderr=stderr)
        return result

    def _record(self, arguments, prefix, started, stdout, stderr, code, timeout, interrupted=False, input_bytes=None):
        for suffix, data in [("stdout", stdout), ("stderr", stderr)]:
            (self.output / (prefix + "." + suffix)).write_bytes(data)
        record = {"arguments": arguments, "startedUTC": started, "exitCode": code, "timeoutSeconds": timeout,
                  "interruptionDeferred": interrupted,
                  "stdout": {"file": prefix + ".stdout", "bytes": len(stdout), "sha256": sha(stdout)},
                  "stderr": {"file": prefix + ".stderr", "bytes": len(stderr), "sha256": sha(stderr)}}
        if input_bytes is not None:
            (self.output / (prefix + ".stdin")).write_bytes(input_bytes)
            record["stdin"] = {"file": prefix + ".stdin", "bytes": len(input_bytes), "sha256": sha(input_bytes)}
        self.records.append(record)
        write_json(self.output / "commands.json", self.records)


def pinned_json(path, expected):
    data = Path(path).read_bytes()
    require(sha(data) == expected, "Provenance resource differs from its approved checksum: " + Path(path).name)
    return json.loads(data)


def provenance(args):
    main = pinned_json(args.main_manifest, MAIN_MANIFEST_SHA)
    fixture = pinned_json(args.fixture_manifest, FIXTURE_MANIFEST_SHA)
    review = pinned_json(args.fixture_review, REVIEW_SHA)
    require(main["source_sha"] == MAIN_SOURCE and main["source_dirty"] is False and main["build_status"] == "PASS", "Main producer source/status")
    require(main["artifact"]["bundle_id"] == MAIN_ID and main["artifact"]["bundle_tree_sha256"] == MAIN_TREE, "Main identity")
    require(fixture["source"] == FIXTURE_SOURCE and fixture["status"] == "BUILD_AND_ADHOC_SIGN_PASS", "Fixture producer source/status")
    require(fixture["app"]["bundle_id"] == FIXTURE_ID and fixture["app"]["bundle_tree_sha256"] == FIXTURE_TREE, "Fixture app identity")
    require(fixture["provider"]["bundle_id"] == PROVIDER_ID and fixture["provider"]["bundle_tree_sha256"] == PROVIDER_TREE, "Provider identity")
    require(fixture["simulatorEntitlementReviewRequired"] is True, "Keep producer review requirement intact")
    require(review["approvalAuthority"] == "ROOT" and review["scope"] == "LOCAL_SIMULATOR_ONLY", "Internal artifact review scope")
    require(review["operation"] == "ONE_PREPARE_REGISTER_INSPECT", "Preserve the original artifact-review operation")
    require(review["developerTeamApproved"] is False and review["releaseApproved"] is False, "No Team or release approval")
    require(review["manifestSHA256"] == FIXTURE_MANIFEST_SHA and review["source"] == FIXTURE_SOURCE, "Artifact review binding")
    require(set(review["approvedSimulatedEntitlements"]) == {"App", "Provider"}, "Exact reviewed roles")
    for name, bundle_id in [("App", FIXTURE_ID), ("Provider", PROVIDER_ID)]:
        expected = {"application-identifier": "P4VGX44GD8." + bundle_id, "com.apple.security.application-groups": [GROUP_ID]}
        role = fixture["simulatorEntitlements"][name]
        require(role["simulatedEntitlements"] == review["approvedSimulatedEntitlements"][name] == expected, "Exact reviewed Simulator dictionary")
        require(role["generatedAdditionalKeys"] == {"application-identifier": expected["application-identifier"]}, "Only reviewed generated app identifier")
        require(role["codeSignatureEntitlements"] == {}, "Empty producer code-signature entitlement dictionary")
    return main, fixture, review


def signature(path, label, commands):
    commands.run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", path], label + "-cs-verify")
    result = commands.run(["codesign", "--display", "--entitlements", ":-", path], label + "-cs-entitlements")
    entitlements = plistlib.loads(result.stdout) if result.stdout.strip() else {}
    require(isinstance(entitlements, dict), "Code-signature entitlements must be an object")
    return entitlements


def preflight(args, output, commands):
    main, fixture, review = provenance(args)
    common = support()
    main_app = (args.main_app or Path(main["artifact"]["path"])).resolve()
    fixture_app = (args.fixture_app or Path(fixture["app"]["path"])).resolve()
    provider = fixture_app / "PlugIns/RevocationProvider.appex"
    identities = {"Main": common.artifact(main_app, MAIN_ID), "App": common.artifact(fixture_app, FIXTURE_ID),
                  "Provider": common.artifact(provider, PROVIDER_ID)}
    for name, expected in [("Main", main["artifact"]), ("App", fixture["app"]), ("Provider", fixture["provider"])]:
        for key in ["bundle_id", "bundle_tree_sha256", "executable_sha256"]:
            require(identities[name][key] == expected[key], "Artifact fingerprint changed: " + name + "/" + key)
    destination = output / "static-preflight"
    destination.mkdir()
    verifier_path = Path(fixture["sourceRoot"]) / "scripts/ios_fileprovider_revocation/simulator_entitlements.py"
    require(common.file_hash(verifier_path) == VERIFIER_SHA == fixture["simulatorEntitlementVerifierSHA256"], "Pinned section verifier source")
    verifier = module(verifier_path, "provider_auth_sections")
    sections = {}
    for name, bundle, bundle_id in [("App", fixture_app, FIXTURE_ID), ("Provider", provider, PROVIDER_ID)]:
        observed = verifier.verify_bundle(bundle, bundle_id, GROUP_ID, destination, name)
        pinned = fixture["simulatorEntitlements"][name]
        require(observed["sectionOwners"] == ["main"] and observed["mainHasSectionPair"] is True, "Main-only section pair")
        require(observed["simulatedEntitlements"] == review["approvedSimulatedEntitlements"][name], "Unreviewed simulated entitlement")
        require(observed["generatedAdditionalKeys"] == pinned["generatedAdditionalKeys"], "Generated entitlement changed")
        observed["codeSignatureEntitlements"] = signature(bundle, name.lower(), commands)
        require(observed["codeSignatureEntitlements"] == pinned["codeSignatureEntitlements"] == {}, "Unexpected fixture code-signature entitlement")
        for key, metadata in pinned["packagingInputs"].items():
            data = Path(metadata["sourcePath"]).read_bytes()
            require(len(data) == metadata["bytes"] and sha(data) == metadata["sha256"], "Generated packaging input changed")
            (destination / (name + "-generated-" + key + ".bin")).write_bytes(data)
            image, = [image for image in observed["images"] if image["image"] == "main"]
            embedded = destination / image["sections"][key]["path"]
            require(embedded.read_bytes() == data, "Embedded/generated sections disagree")
        sections[name] = observed
    # Main is admitted by its complete approved bundle hash, without inventing a new entitlement allowlist.
    # The pinned debug dylib exceeds the fixture verifier's 64 MiB bound; raise only this local parser instance
    # to the already verified byte count, then retain any sections as evidence.
    main_images = {}
    main_file_pins = {entry["path"]: entry for entry in main["artifact"]["files"]}
    info = plistlib.loads((main_app / "Info.plist").read_bytes())
    for name in [info["CFBundleExecutable"], info["CFBundleExecutable"] + ".debug.dylib", "__preview.dylib"]:
        image = main_app / name
        if not image.is_file():
            continue
        data = image.read_bytes()
        require(len(data) == main_file_pins[name]["bytes"] and sha(data) == main_file_pins[name]["sha256"], "Main image pin")
        verifier.MAXIMUM_IMAGE_BYTES = max(verifier.MAXIMUM_IMAGE_BYTES, len(data))
        found = verifier.macho_sections(data)
        main_images[name] = {}
        for section, evidence in found.items():
            retained = destination / ("Main-" + name + "-" + section + ".bin")
            retained.write_bytes(evidence["data"])
            main_images[name][section] = {"bytes": len(evidence["data"]), "sha256": sha(evidence["data"]), "file": retained.name}
    main_cs = signature(main_app, "main", commands)
    catalog_root = (args.catalog_root or Path(main["source_root"])).resolve()
    for entry in main["catalog"]["files"]:
        relative = Path(entry["path"]).relative_to(main["source_root"])
        require(common.file_hash(catalog_root / relative) == entry["sha256"], "Main catalog artifact source changed")
    state = subprocess.check_output(["git", "-C", str(catalog_root), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(catalog_root), "status", "--porcelain", "--", "localization/messages"], text=True)
    require(state == MAIN_SOURCE and not dirty, "Use clean catalog messages from approved Main source")
    result = {"main": {"bundleID": MAIN_ID, "source": MAIN_SOURCE, "artifactManifestSHA256": MAIN_MANIFEST_SHA,
                       "bundleTreeSHA256": MAIN_TREE, "executableSHA256": identities["Main"]["executable_sha256"]},
              "fixture": {"bundleID": FIXTURE_ID, "providerBundleID": PROVIDER_ID, "source": FIXTURE_SOURCE,
                          "artifactManifestSHA256": FIXTURE_MANIFEST_SHA, "appTreeSHA256": FIXTURE_TREE,
                          "providerTreeSHA256": PROVIDER_TREE, "entitlementReviewSHA256": REVIEW_SHA},
              "artifactPaths": {"main": str(main_app), "fixture": str(fixture_app), "provider": str(provider)},
              "catalogRoot": str(catalog_root), "identities": identities,
              "staticEvidence": {"fixtureSimulatorSections": sections, "mainSimulatorSections": main_images,
                                 "mainCodeSignatureEntitlements": main_cs}}
    write_json(output / "preflight.json", result)
    return result


def source_state():
    paths = [Path(__file__).resolve(), ROOT / "scripts/run-ios-accessibility.py", SOURCE / "project.yml", *SOURCES.values(),
             *(SOURCE / name for name in RESOURCE_NAMES if name not in ["Catalog.json", "Run.json"])]
    require(all(path.is_file() for path in paths), "Complete all six owned/shared Swift sources before preparing")
    relative = [path.relative_to(ROOT).as_posix() for path in paths]
    commit = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()
    status = subprocess.check_output(["git", "-C", str(ROOT), "status", "--porcelain", "--", *relative], text=True)
    return {"commit": commit, "dirty": bool(status), "status": status,
            "sources": {name: sha(path.read_bytes()) for name, path in zip(relative, paths)}}


def validate_lease(lease, *, run_id, simulator, output, bindings, harness):
    require(lease.get("schemaVersion") == 1 and lease.get("authority") == "ROOT", "ROOT lease authority")
    require(lease.get("nativeAuthorized") is True, "Native auth operation is not authorized")
    require(lease.get("scope") == "LOCAL_SIMULATOR_MAIN_PLUS_PROVIDER" and lease.get("operation") == OPERATION, "Main+Provider operation lease")
    require(isinstance(lease.get("leaseID"), str) and lease["leaseID"].strip(), "Recorded ROOT lease ID")
    require(lease.get("simulatorUDID", "").lower() == simulator.lower(), "Lease UUID binding")
    require(lease.get("runID") == run_id and lease.get("outputDirectory") == str(output.resolve()), "Single run/output lease binding")
    require(lease.get("selector") == SELECTOR, "Exactly one auth selector")
    require(lease.get("main") == bindings["main"] and lease.get("fixture") == bindings["fixture"], "Lease artifact bindings")
    require(harness["dirty"] is False and lease.get("harnessCommit") == harness["commit"], "Clean committed harness")
    require(lease.get("harnessSources") == harness["sources"], "Lease source fingerprints")
    return lease


def prepare(output, catalog_root, configuration, commands):
    project = output / "harness"
    project.mkdir()
    catalog = {}
    for file in sorted((catalog_root / "localization/messages").glob("*.json")):
        entries = json.loads(file.read_text())
        require(not catalog.keys() & entries.keys(), "Duplicate catalog entries")
        catalog.update(entries)
    require(len(catalog) == 2497, "Use the approved Main catalog with 2497 keys")
    for key in ["a11y_project_tools", "a11y_explorer_title", "a11y_canvas", "action_close", "a11y_fit_selection"]:
        require(key in catalog, "Missing shared harness catalog key: " + key)
    write_json(project / "Catalog.json", catalog)
    write_json(project / "Run.json", configuration)
    for name, source in SOURCES.items():
        require(source.is_file(), "Missing auth/shared source: " + name)
        shutil.copyfile(source, project / name)
    for name in ["project.yml", *RESOURCE_NAMES[2:]]:
        if name in PROVENANCE_PINS:
            pinned_json(SOURCE / name, PROVENANCE_PINS[name])
        shutil.copyfile(SOURCE / name, project / name)
    commands.run(["xcodegen", "generate", "--spec", project / "project.yml"], "xcodegen", timeout=60)
    info = {"CFBundleDevelopmentRegion": "en", "CFBundleExecutable": "$(EXECUTABLE_NAME)",
            "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)", "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": "$(PRODUCT_NAME)", "CFBundlePackageType": "BNDL", "CFBundleShortVersionString": "1.0",
            "CFBundleVersion": "1", "TargetAppBundleIdentifier": MAIN_ID, "ComponentSearch": "REVOCATION_INPUT"}
    (project / "TestInfo.plist").write_bytes(plistlib.dumps(info))
    metadata = {"sources": {name: sha((project / name).read_bytes()) for name in SOURCES},
                "resources": {name: sha((project / name).read_bytes()) for name in RESOURCE_NAMES}}
    return project / "LogisimProviderAuthenticationHarness.xcodeproj", metadata


def verify_bundled_resources(project_dir, bundle):
    resources = {}
    for name in RESOURCE_NAMES:
        staged, packaged = project_dir / name, bundle / name
        require(packaged.is_file() and staged.read_bytes() == packaged.read_bytes(), "Missing/stale bundled resource: " + name)
        if name in PROVENANCE_PINS:
            pinned_json(packaged, PROVENANCE_PINS[name])
        json.loads(packaged.read_bytes())
        resources[name] = sha(packaged.read_bytes())
    info = plistlib.loads((bundle / "Info.plist").read_bytes())
    require(info.get("TargetAppBundleIdentifier") == MAIN_ID, "Bundled TestInfo main identity")
    return resources


def installed(commands, bundle_id):
    result = commands.run(["xcrun", "simctl", "get_app_container", commands.simulator, bundle_id, "app"], "installed-" + ("main" if bundle_id == MAIN_ID else "fixture"))
    path = Path(result.stdout.decode().strip())
    require(path.is_absolute() and path.is_dir(), "Installed app path unavailable")
    return path


def check_installed(commands, bindings):
    common = support()
    main = installed(commands, MAIN_ID)
    fixture = installed(commands, FIXTURE_ID)
    result = {"Main": common.artifact(main, MAIN_ID), "App": common.artifact(fixture, FIXTURE_ID),
              "Provider": common.artifact(fixture / "PlugIns/RevocationProvider.appex", PROVIDER_ID)}
    for name, actual in result.items():
        for key in ["bundle_id", "bundle_tree_sha256", "executable_sha256"]:
            require(actual[key] == bindings["identities"][name][key], "Installed fingerprint changed: " + name)
    return result


def decode_installed_applications(payload, commands):
    converted = commands.run(["plutil", "-convert", "json", "-o", "-", "-"],
                             "installed-applications-json", input_bytes=payload)
    applications = json.loads(converted.stdout)
    require(isinstance(applications, dict), "Installed applications must be a property-list dictionary")
    require(all(isinstance(info, dict) and info.get("CFBundleIdentifier") == bundle_id
                for bundle_id, info in applications.items()), "Installed application identifiers disagree with their records")
    return applications


def admit_installed_apps(commands, bindings):
    """Keep the registered fixture intact; install only an absent, approved Main."""
    common = support()
    payload = commands.run(["xcrun", "simctl", "listapps", commands.simulator], "installed-applications").stdout
    applications = decode_installed_applications(payload, commands)
    require(isinstance(applications, dict) and FIXTURE_ID in applications, "The approved fixture must already be installed; do not register or refresh it here")
    fixture = installed(commands, FIXTURE_ID)
    fixture_identities = {"App": common.artifact(fixture, FIXTURE_ID),
                          "Provider": common.artifact(fixture / "PlugIns/RevocationProvider.appex", PROVIDER_ID)}
    for name, actual in fixture_identities.items():
        for key in ["bundle_id", "bundle_tree_sha256", "executable_sha256"]:
            require(actual[key] == bindings["identities"][name][key], "Registered fixture fingerprint changed: " + name)
    main_action = "REUSED_EXACT_INSTALLED_MAIN"
    if MAIN_ID not in applications:
        commands.run(["xcrun", "simctl", "install", commands.simulator, bindings["artifactPaths"]["main"]], "install-main")
        main_action = "INSTALLED_APPROVED_MAIN"
    # An installed, divergent Main is rejected rather than silently replaced.
    identities = check_installed(commands, bindings)
    return identities, {"main": main_action, "fixture": "REUSED_EXACT_REGISTERED_FIXTURE",
                        "fixtureInstalledOrRefreshed": False,
                        "context": "Main+Provider; this is no longer the previous UIKit-only fixture context."}


def execute(args, output, project, bindings, report, commands):
    commands.simulator = args.simulator
    devices = json.loads(commands.run(["xcrun", "simctl", "list", "devices", "available", "-j"], "devices").stdout)
    device = next((dict(item, runtime=runtime) for runtime, items in devices["devices"].items() for item in items
                   if item["udid"].lower() == args.simulator.lower()), None)
    require(device is not None and device.get("state") == "Booted", "The explicitly leased Simulator must already be booted")
    report["device"] = device
    report["installedBeforeTest"], report["installationContextChange"] = admit_installed_apps(commands, bindings)
    try:
        execute_test(args, output, project, report, commands)
    except (Exception, KeyboardInterrupt):
        try:
            report["installedAfterTest"] = check_installed(commands, bindings)
        except (Exception, KeyboardInterrupt) as postflight_error:
            report["installedAfterTestError"] = {"type": type(postflight_error).__name__, "message": str(postflight_error)}
        raise
    report["installedAfterTest"] = check_installed(commands, bindings)
    report["native_execution_gate"] = "NATIVE_PASS"
    report["test_execution_gate"] = "NATIVE_PASS"
    report["status"] = "PASS"
    report["qualification_gate"] = "PARTIAL"


def validated_build_wrapper(output):
    require(BUILD_WRAPPER.is_file() and not BUILD_WRAPPER.is_symlink(), "Expected the regular shared build wrapper")
    data = BUILD_WRAPPER.read_bytes()
    require(sha(data) == BUILD_WRAPPER_SHA, "Shared build wrapper differs from its reviewed checksum")
    retained = output / "BuildSerializationWrapper.py"
    retained.write_bytes(data)
    return {"wrapper": str(BUILD_WRAPPER), "sha256": BUILD_WRAPPER_SHA, "retained": retained.name,
            "lock": str(BUILD_LOCK), "scope": "BUILD_FOR_TESTING_ONLY", "wrapperProcessCompleted": False,
            "interruptionDeferred": False}


def execute_test(args, output, project, report, commands):
    derived = (args.derived_data or output / "DerivedData").resolve()
    base = ["xcodebuild", "-project", project, "-scheme", "ProviderAuthenticationHarness", "-destination",
            "platform=iOS Simulator,id=" + args.simulator, "-derivedDataPath", derived, "CODE_SIGNING_ALLOWED=NO"]
    report["buildSerialization"] = validated_build_wrapper(output)
    build_arguments = [sys.executable, str(BUILD_WRAPPER), *base, "build-for-testing"]
    try:
        build = commands.run(build_arguments, "harness-build", check=False, timeout=None)
    except BuildInterrupted as interrupted:
        report["buildSerialization"].update(wrapperProcessCompleted=True, exitCode=interrupted.outcome.returncode,
                                            interruptionDeferred=True)
        raise
    report["buildSerialization"]["wrapperProcessCompleted"] = True
    report["buildSerialization"]["exitCode"] = build.returncode
    require(build.returncode == 0, "The serialized harness build failed with exit code " + str(build.returncode))
    bundle = derived / f"Build/Products/Debug-iphonesimulator/{TARGET}-Runner.app/PlugIns/{TARGET}.xctest"
    report["bundledResources"] = verify_bundled_resources(project.parent, bundle)
    report["test_execution_gate"] = "NATIVE_RUNNING"
    write_json(output / "results.json", report)
    result = output / "auth.xcresult"
    outcome = commands.run([*base, "test-without-building", "-parallel-testing-enabled", "NO", "-collect-test-diagnostics", "never",
                            "-test-timeouts-enabled", "YES", "-default-test-execution-time-allowance", str(args.test_timeout),
                            "-resultBundlePath", result, "-only-testing:" + SELECTOR], "auth-test", check=False,
                           timeout=args.test_timeout + 180)
    report["execution"] = {"exitCode": outcome.returncode, "selector": SELECTOR, "xcresult": str(result)}
    summary = json.loads(commands.run(["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", result], "xcresult-summary").stdout)
    report["testSummary"] = summary
    commands.run(["xcrun", "xcresulttool", "export", "attachments", "--path", result, "--output-path", output / "attachments"], "attachments")
    attachment_manifest = json.loads((output / "attachments/manifest.json").read_text())
    expected_id = "ProviderAuthenticationEndToEndTests/testAuthenticationDenialAfterGrantPreservesProject()"
    require({entry["testIdentifier"] for entry in attachment_manifest} == {expected_id}, "Unexpected or absent executed test identifier")
    require(outcome.returncode == 0 and summary.get("totalTestCount") == 1 and summary.get("passedTests") == 1 and
            summary.get("failedTests") == 0 and summary.get("skippedTests") == 0, "Exactly one passing auth case is required")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--execute", action="store_true", help="Requires a separate explicit ROOT lease; not authorized by prepare")
    parser.add_argument("--simulator")
    parser.add_argument("--root-lease", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--main-app", type=Path)
    parser.add_argument("--fixture-app", type=Path)
    parser.add_argument("--catalog-root", type=Path)
    parser.add_argument("--main-manifest", type=Path, default=SOURCE / "ProvenanceMainArtifact.json")
    parser.add_argument("--fixture-manifest", type=Path, default=SOURCE / "ProvenanceSignedFixture.json")
    parser.add_argument("--fixture-review", type=Path, default=SOURCE / "ProvenanceArtifactReview.json")
    parser.add_argument("--derived-data", type=Path)
    parser.add_argument("--test-timeout", type=int, default=1200)
    args = parser.parse_args(argv)
    if args.simulator:
        try:
            require(str(uuid.UUID(args.simulator)).lower() == args.simulator.lower(), "Use an explicit canonical Simulator UUID")
        except (ValueError, AttributeError):
            parser.error("--simulator must be an explicit UUID, never booted")
    if args.execute and (not args.simulator or not args.root_lease or not args.run_id):
        parser.error("--execute requires --simulator, --root-lease and --run-id")
    if not args.execute and args.root_lease:
        parser.error("A ROOT lease is consumed only by explicit --execute")
    run_id = args.run_id or uuid.uuid4().hex[:12]
    if not re.fullmatch(r"[0-9a-f]{12}", run_id) or not 0 < args.test_timeout <= 3600:
        parser.error("Use a 12-hex run ID and a positive timeout no larger than 3600 seconds")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Preserve the previous evidence and choose a fresh output directory")
    output.mkdir(parents=True, exist_ok=True)
    commands = Commands(output)
    report = {"schemaVersion": 1, "runID": run_id, "startedUTC": datetime.now(timezone.utc).isoformat(),
              "status": "PREPARING", "native_execution_gate": "NATIVE_NOT_RUN", "test_execution_gate": "NATIVE_NOT_RUN",
              "qualification_gate": "NOT_QUALIFIED",
              "physicalDevice": False, "selectedTests": [SELECTOR], "rootLease": None,
              "nativeOperationAuthorizedByCurrentArtifactReview": False}
    code = 0
    try:
        bindings = preflight(args, output, commands)
        harness = source_state()
        report.update(preflight=bindings, harness=harness)
        lease = None
        if args.execute:
            raw_lease = args.root_lease.read_bytes()
            lease = validate_lease(json.loads(raw_lease), run_id=run_id, simulator=args.simulator,
                                   output=output, bindings=bindings, harness=harness)
            (output / "RootLease.json").write_bytes(raw_lease)
            report["rootLease"] = {"file": str(args.root_lease.resolve()), "sha256": sha(raw_lease), "review": lease}
        receipts = output / "receipts" / run_id
        receipts.mkdir(parents=True)
        configuration = {"schemaVersion": 1, "runID": run_id, "receiptDirectory": str(receipts), "simulatorUDID": args.simulator,
                         "main": bindings["main"], "fixture": bindings["fixture"], "fileName": "revocation.circ",
                         "providerDisplayName": "Logisim Revocation Fixture",
                         "initialSHA256": "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88",
                         "inputLabel": "REVOCATION_INPUT", "rootLease": lease}
        project, staged = prepare(output, Path(bindings["catalogRoot"]), configuration, commands)
        for name, path in SOURCES.items():
            require(staged["sources"][name] == harness["sources"][path.relative_to(ROOT).as_posix()],
                    "Source changed while staging: " + name)
        require(source_state() == harness, "Harness sources changed during preparation")
        report.update(configuration=configuration, staged=staged, status="PREPARED")
        if args.execute:
            commands.allow_native = True
            report["native_execution_gate"] = "NATIVE_RUNNING"
            write_json(output / "results.json", report)
            execute(args, output, project, bindings, report, commands)
    except (Exception, KeyboardInterrupt) as error:
        code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report["status"] = "FAIL"
        if report["native_execution_gate"] == "NATIVE_RUNNING":
            report["native_execution_gate"] = "NATIVE_INCOMPLETE"
        if report["test_execution_gate"] == "NATIVE_RUNNING":
            report["test_execution_gate"] = "NATIVE_INCOMPLETE"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        (output / "execution-error.txt").write_text(traceback.format_exc())
    finally:
        # No finally path invokes Simulator tools, launches apps, or writes app/provider state.
        report["finishedUTC"] = datetime.now(timezone.utc).isoformat()
        write_json(output / "results.json", report)
        print(output / "results.json")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
