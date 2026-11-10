#!/usr/bin/env python3
"""Verify the retained DIAG969 evidence using only portable archived inputs.

The default path never launches a command, reads an original producer path, or
loads an SDK. --host-replay separately compiles two Foundation-only Swift files.
This archive cannot establish native execution, discovery, or device behavior.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import re
import shutil
import subprocess
import tempfile


SOURCE = "969f2d08010a22abbd8620f87ca9e382684b4b3f"
BASE = "601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd"
MANIFEST_HASHES = {
    "producer969": "0b8a220201e98ee40e5f0b8a4b56fda97464d97719ae3804f493d3dff8169763",
    "reference601": "c2e976f7ed41dfa60fcd48ef8a4e8defcaadbb7f8042ebd08669dbdf4b778159",
}
GROUP = "group.thalesmms.logisim.validation.revocationfixture"
GROUP_KEY = "com.apple.security.application-groups"
PREFIX = "P4VGX44GD8."
FIELDS = ["contents", "filename", "parentItemIdentifier", "lastUsedDate", "tagData",
          "favoriteRank", "creationDate", "contentModificationDate", "fileSystemFlags",
          "extendedAttributes", "typeAndCreator"]
POLICIES = ["inherited", "downloadLazily", "downloadLazilyAndEvictOnRemoteUpdate",
            "downloadEagerlyAndKeepDownloaded"]
OPTIONS = ["mayAlreadyExist", "failOnConflict", "isImmediateUploadRequestByPresentingApplication"]
SECTIONS = ("__entitlements", "__ents_der")
WORK_HASH = "d4a8b5c759f149b7a5b380247787a6e422eb253dc45a0b284dbb70566d11b53e"


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def relative_path(value):
    require(isinstance(value, str) and value and "\\" not in value and "\0" not in value,
            "Invalid relative path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and path.as_posix() == value and
            all(part not in (".", "..") for part in path.parts), "Unsafe relative path: " + value)
    return path


def read(root, relative):
    path = Path(root).joinpath(*relative_path(relative).parts)
    require(not path.is_symlink() and path.is_file(), "Missing regular file: " + relative)
    current = path.parent
    while current != Path(root):
        require(not current.is_symlink(), "Symlink in archive path: " + relative)
        current = current.parent
    return path.read_bytes()


def load_json(root, relative):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(read(root, relative), object_pairs_hook=unique)


def validate_record(record, context):
    require(type(record.get("bytes")) is int and record["bytes"] >= 0,
            context + ": invalid byte count")
    require(isinstance(record.get("sha256"), str) and
            re.fullmatch(r"[0-9a-f]{64}", record["sha256"]) is not None,
            context + ": invalid SHA256")


def check_bytes(data, record, context):
    validate_record(record, context)
    require(len(data) == record["bytes"] and sha(data) == record["sha256"],
            context + ": size/SHA256 mismatch")


def verify_inventory(root):
    inventory = load_json(root, "inventory.json")
    require(inventory.get("schemaVersion") == 1 and isinstance(inventory.get("files"), dict),
            "Unsupported inventory schema")
    actual = set()
    for path in Path(root).rglob("*"):
        if "__pycache__" in path.parts:
            continue
        require(not path.is_symlink(), "Archive must not contain symlinks")
        if path.is_file() and path.name != "inventory.json":
            actual.add(path.relative_to(root).as_posix())
    require(set(inventory["files"]) == actual, "Inventory must cover every archive file exactly")
    require("verify.py" in actual and "test_verify.py" in actual, "Inventory omits verifier sources")
    for name, record in inventory["files"].items():
        check_bytes(read(root, name), record, "Inventory " + name)
    return {"status": "PASS", "fileCount": len(actual)}


def verify_claims(manifest):
    expected = {"status": "BUILD_AND_ADHOC_SIGN_PASS", "nativeExecution": "NOT_RUN",
                "domainRegistration": "NOT_RUN", "physicalDevice": False,
                "simulatorEntitlementReviewRequired": True, "appGroup": GROUP,
                "architecture": "arm64", "sdk": "27.2"}
    for name, value in expected.items():
        require(type(manifest.get(name)) is type(value) and manifest[name] == value,
                "Manifest claim changed: " + name)
    require(manifest.get("signing") == "Xcode ad-hoc Simulator signing, fixture source group only",
            "Unexpected signing claim")


def verify_manifests(root):
    result = []
    for directory, source in (("producer969", SOURCE), ("reference601", BASE)):
        data = read(root, directory + "/artifact.json")
        manifest = load_json(root, directory + "/artifact.json")
        verify_claims(manifest)
        require(sha(data) == MANIFEST_HASHES[directory], "Literal manifest SHA256 pin changed: " + directory)
        require(manifest["source"] == source, "Source commit pin changed")
        fixture = directory + "/fixture-source/"
        files = manifest["sourceFiles"]
        actual = {path.relative_to(Path(root) / fixture).as_posix()
                  for path in (Path(root) / fixture).rglob("*") if path.is_file()}
        require(actual == set(files), "Exported source inventory mismatch: " + directory)
        for name, digest in files.items():
            require(sha(read(root, fixture + name)) == digest, "Exported source hash mismatch: " + name)
        require(files["build-simulator-fixture.py"] == manifest["buildDriverSHA256"] and
                files["simulator_entitlements.py"] == manifest["simulatorEntitlementVerifierSHA256"] and
                manifest["driverSourceMatchesExportedPin"] is True, "Driver source pin mismatch")
        result.append(manifest)
    return result


def verify_commands(root, manifest):
    commands = manifest["commands"]
    names = ["configuration.log", "xcodegen.log", "build.log", "verify-Provider.log",
             "signature-Provider.log", "verify-App.log", "signature-App.log"]
    require(len(commands) == 7 and [x["log"] for x in commands] == names,
            "Expected the seven original build/signature commands")
    require([x["arguments"][0] for x in commands] ==
            ["python3", "xcodegen", "xcodebuild", "codesign", "codesign", "codesign", "codesign"],
            "Unexpected build command sequence")
    require(all(type(x["exitCode"]) is int and x["exitCode"] == 0 for x in commands),
            "Original command failed")
    build = commands[2]["arguments"]
    for flag in ["ARCHS=arm64", "ONLY_ACTIVE_ARCH=YES", "CODE_SIGNING_ALLOWED=YES",
                 "CODE_SIGNING_REQUIRED=YES", "CODE_SIGN_STYLE=Manual", "DEVELOPMENT_TEAM=", "CODE_SIGN_IDENTITY=-"]:
        require(build.count(flag) == 1, "Missing ad-hoc build flag: " + flag)
    require(build[build.index("-sdk") + 1] == "iphonesimulator" and build[-1] == "build",
            "Unexpected build SDK/action")
    require(all("--sign" not in x["arguments"] and "-s" not in x["arguments"] for x in commands),
            "Unexpected post-build signing command")
    for command in commands:
        require(read(root, "producer969/logs/" + command["log"]), "Empty command log")
    log = read(root, "producer969/logs/build.log").decode()
    require("** BUILD SUCCEEDED **" in log, "Build success missing from retained log")
    for label in ("App", "Provider"):
        signature = read(root, "producer969/logs/signature-" + label + ".log").decode()
        require("Signature=adhoc" in signature and "TeamIdentifier=not set" in signature and
                "flags=0x2(adhoc)" in signature, "Signature log is not ad-hoc/no-Team")
        require("valid on disk" in read(root, "producer969/logs/verify-" + label + ".log").decode(),
                "Signature verification result missing")
    return {"status": "PASS", "commandCount": 7, "postBuildSigning": False}


def between(data, start, end):
    require(data.count(start) == 1, "Source proof delimiter missing or duplicated")
    return data.split(start, 1)[1].split(end, 1)[0]


def verify_source_proof(root, manifest, reference):
    provider_name = "Provider/RevocationFileProvider.swift"
    protected = set(reference["sourceFiles"]) - {provider_name}
    require(len(protected) == 22, "Expected 22 protected baseline files")
    for name in protected:
        require(read(root, "producer969/fixture-source/" + name) ==
                read(root, "reference601/fixture-source/" + name), "Protected file raw bytes changed: " + name)
    old = read(root, "reference601/fixture-source/" + provider_name)
    new = read(root, "producer969/fixture-source/" + provider_name)
    marker, end = b"{ beginCommit in\n", b"\n    func deleteItem("
    old_work, new_work = between(old, marker, end), between(new, marker, end)
    require(old_work == new_work and len(new_work) == 2045 and sha(new_work) == WORK_HASH,
            "Mutation work body raw-byte proof failed")
    replacement = (
        b'        return perform("provider.modify", identifier: identifier, failure: { error -> MutationResult in (nil, changedFields, false, error) },\n',
        b'        let diagnostics = RevocationModifyDiagnostics.details(\n'
        b'            changedFieldsRaw: changedFields.rawValue, newContentsPresent: newContents != nil, optionsRaw: options.rawValue,\n'
        b'            requestedContentPolicyRaw: item.contentPolicy?.rawValue,\n'
        b'            fixtureDeclaredContentPolicyRaw: NSFileProviderContentPolicy.downloadLazilyAndEvictOnRemoteUpdate.rawValue)\n'
        b'        return perform("provider.modify", identifier: identifier, details: diagnostics,\n'
        b'                       failure: { error -> MutationResult in (nil, changedFields, false, error) },\n')
    expected = old
    for before, after in [replacement,
        (b"identifier: NSFileProviderItemIdentifier,\n                                failure:",
         b"identifier: NSFileProviderItemIdentifier,\n                                details requestDetails: [String: String] = [:],\n                                failure:"),
        (b'let details = ["requestID": requestID]',
         b'let details = requestDetails.merging(["requestID": requestID]) { _, new in new }')]:
        require(expected.count(before) == 1, "Source proof replacement is ambiguous")
        expected = expected.replace(before, after, 1)
    require(new == expected, "Provider changed beyond diagnostics and private perform details")
    require(b"guard changedFields.contains(.contents), let newContents" in new,
            "Original contents/newContents guard changed")
    helper = read(root, "producer969/fixture-source/Provider/RevocationModifyDiagnostics.swift").decode()
    require(not any(s in helper for s in ("FileManager", "Data(contentsOf:", "snapshot(", "activeStore(")),
            "Diagnostics helper adds explicit I/O")
    require('"requestedContentPolicyPresent"' in helper and '?? "absent"' in helper and
            '"fixtureDeclaredContentPolicyRaw"' in helper and 'storedContentPolicyRaw' not in helper,
            "Optional/source-declared policy distinction changed")
    return {"status": "PASS", "protectedFilesByteIdentical": 22, "workBodyBytes": 2045,
            "workBodySHA256": WORK_HASH, "rawBytesCompared": True}


def decode_fields(raw):
    require(type(raw) is int and 0 <= raw < (1 << 64), "Field mask must be a UInt64 integer")
    return {"rawDecimal": str(raw), "names": [name for bit, name in enumerate(FIELDS) if raw & (1 << bit)],
            "unknownDecimal": str(raw & (((1 << 64) - 1) ^ 2047))}


def verify_sdk_review(root):
    declarations = load_json(root, "sdk-review/sdk-declarations.json")
    spec = load_json(root, "sdk-review/spec.json")
    texts = {name: "\n".join(row["text"] for row in value["lineRecords"])
             for name, value in declarations.items()}
    for name, declaration in declarations.items():
        records = declaration["lineRecords"]
        require([r["line"] for r in records] == sorted(set(r["line"] for r in records)),
                "Duplicate or unordered SDK line records")
        require(spec["sources"][name]["sha256"] == declaration["fullSourceSHA256"],
                "SDK source hash declarations disagree")
    def lower(name):
        return name[0].lower() + name[1:]
    fields = [(1 << int(bit), lower(name)) for name, bit in
              re.findall(r"NSFileProviderItem(\w+)[^=\n]*= 1 << (\d+)", texts["NSFileProviderItem.h"])]
    require(fields == [(1 << i, name) for i, name in enumerate(FIELDS)], "SDK field declarations changed")
    require(spec["fieldBits"] == [{"mask": bit, "name": name} for bit, name in fields] and
            spec["fieldsKnownMask"] == 2047, "SDK field specification mismatch")
    options = [(1 << int(bit), lower(name)) for name, bit in
               re.findall(r"NSFileProviderModifyItem(\w+)[^=\n]*= 1 << (\d+)", texts["NSFileProviderModifyItemOptions.h"])]
    require(options == [(1 << i, name) for i, name in enumerate(OPTIONS)], "SDK option declarations changed")
    require(spec["optionBits"] == [{"mask": 1 << i, "name": name,
             "iOSAvailability": "16.0+" if i == 0 else "26.0+"} for i, name in enumerate(OPTIONS)],
            "SDK option availability mismatch")
    policy_names = [lower(n) for n in re.findall(r"NSFileProviderContentPolicy(\w+) FILEPROVIDER_API_", texts["NSFileProviderItem.h"])]
    require(policy_names == POLICIES, "SDK policy declarations changed")
    require(spec["policyValues"] == [{"raw": i, "name": name, "iOSAvailability":
             "16.0+" if i in (0, 2) else "not available to iOS in SDK27.2"} for i, name in enumerate(POLICIES)],
            "SDK policy availability mismatch")
    defines = texts["NSFileProviderDefines.h"]
    require("FILEPROVIDER_API_AVAILABILITY_V5_0 API_AVAILABLE(macos(13.0)) API_UNAVAILABLE(watchos, tvos) API_UNAVAILABLE(ios, macCatalyst)" in defines and
            "FILEPROVIDER_API_AVAILABILITY_V5_0_IOS API_AVAILABLE(macos(13.0), ios(16.0))" in defines and
            "FILEPROVIDER_API_AVAILABILITY_SYNC_CONTROLS API_AVAILABLE(macos(26.0), ios(26.0))" in defines,
            "SDK availability macros changed")
    require("@optional" in texts["NSFileProviderItem.h"] and
            "@property (nonatomic, readonly) NSFileProviderContentPolicy contentPolicy" in texts["NSFileProviderItem.h"],
            "Incoming contentPolicy optional declaration missing")
    helper = read(root, "producer969/fixture-source/Provider/RevocationModifyDiagnostics.swift").decode()
    require([(1 << int(bit), name) for bit, name in re.findall(r'\(1 << (\d+), "([^"]+)"\)', helper)] == fields,
            "Swift helper field table differs from SDK")
    require([(int(raw), name) for raw, name in re.findall(r'case (\d+): return "([^"]+)"', helper)] == list(enumerate(POLICIES)),
            "Swift helper policy table differs from SDK")
    require(spec["sources"]["helper"]["sha256"] == sha(helper.encode()) and
            spec["actualAuth004Fields"] == "UNKNOWN_UNTIL_FUTURE_DIAGNOSTIC" and
            spec["pureChecksPassed"] == 23, "SDK source-only scope changed")
    for raw in [0, 2047, 1 << 11, 1 << 63, (1 << 64) - 1]:
        decoded = decode_fields(raw)
        require(int(decoded["unknownDecimal"]) == raw & (((1 << 64) - 1) ^ 2047), "Unknown mask lost")
    return {"status": "PASS", "fieldCount": 11, "policyCount": 4, "optionCount": 3,
            "review": "SOURCE_ONLY", "actualAuth004Fields": "UNKNOWN_UNTIL_FUTURE_DIAGNOSTIC"}


def der_decoder(root):
    """Load only the pinned parser function and its pure dependencies, never writers."""
    source = read(root, "producer969/fixture-source/simulator_entitlements.py")
    tree = ast.parse(source)
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and
                 node.name in ("require", "der_entitlements")]
    require(len(functions) == 2, "Archived DER decoder functions missing")
    namespace = {"MAXIMUM_ENTITLEMENT_BYTES": 1024 * 1024}
    exec(compile(ast.Module(body=functions, type_ignores=[]), "archived-pure-der-decoder", "exec"), namespace)
    return namespace["der_entitlements"]


def verify_entitlements(root, manifest, reference):
    decode = der_decoder(root)
    for label in ("App", "Provider"):
        report = manifest["simulatorEntitlements"][label]
        baseline = reference["simulatorEntitlements"][label]
        bundle_id = manifest[label.lower()]["bundle_id"]
        expected = {"application-identifier": PREFIX + bundle_id, GROUP_KEY: [GROUP]}
        require(report["simulatedEntitlements"] == baseline["simulatedEntitlements"] == expected,
                "Simulator entitlement dictionary changed")
        require(report["generatedAdditionalKeys"] == baseline["generatedAdditionalKeys"] ==
                {"application-identifier": PREFIX + bundle_id}, "Generated additional keys changed")
        require(report["bundleIdentifier"] == bundle_id and report["sectionOwners"] == ["main"] and
                report["nativeExecution"] == "NOT_RUN" and report["generatedEntitlementsReview"] == "REQUIRED_BEFORE_NATIVE" and
                report["mainHasSectionPair"] is True, "Manifest entitlement scope/status changed")
        require([x["image"] for x in report["images"]] == ["main", "debug-dylib"] and
                report["images"][1]["sections"] == {}, "Unexpected declared image scope")
        main = report["images"][0]
        require(set(main["sections"]) == set(SECTIONS), "Incomplete main XML/DER pair")
        pair = {}
        for name in SECTIONS:
            section, packaging = main["sections"][name], report["packagingInputs"][name]
            section_data = read(root, "producer969/retained/" + section["path"])
            generated_data = read(root, "producer969/retained/" + packaging["path"])
            require(section_data == generated_data, "Section/generated input mismatch: " + label + name)
            check_bytes(section_data, section, "Section " + label + name)
            check_bytes(generated_data, packaging, "Generated " + label + name)
            require(section["sha256"] == baseline["images"][0]["sections"][name]["sha256"],
                    "601/969 section digest differs")
            pair[name] = section_data
        try:
            xml, der = plistlib.loads(pair["__entitlements"]), decode(pair["__ents_der"])
        except (ValueError, UnicodeError, plistlib.InvalidFileException) as error:
            raise VerificationError("Entitlement decode failed: " + str(error)) from error
        require(xml == der == expected, "XML/DER semantic entitlement mismatch")
        source = plistlib.loads(read(root, "producer969/fixture-source/Config/" + label + ".entitlements"))
        require(source == {GROUP_KEY: [GROUP]}, "Source entitlement scope changed")
        signature = plistlib.loads(read(root, "producer969/retained/entitlements-" + label + ".stdout.plist"))
        require(signature == report["codeSignatureEntitlements"] == {}, "Code-signature entitlements must be empty")
        stderr = read(root, "producer969/retained/entitlements-" + label + ".stderr.log").decode()
        require(stderr == "Executable=" + main["path"] + "\n" +
                "warning: Specifying ':' in the path is deprecated and will not work in a future release\n",
                "Unexpected entitlement display stderr")
    require(sha(read(root, "producer969/retained/artifact-reader.py")) == manifest["artifactReaderSHA256"],
            "Retained artifact reader hash changed")
    return {"status": "PASS", "mainSectionPairs": 2, "generatedInputPairs": 2,
            "codeSignatureEntitlements": {}, "prior601AuthorizationReused": False}


def verify_bundle_index(root, manifest, reference=None):
    if reference is None:
        reference = load_json(root, "reference601/artifact.json")
    index = load_json(root, "bundle-index.json")
    require(index["schemaVersion"] == 1, "Unsupported bundle-index schema")
    expected_files = {"provider": {"Info.plist", "RevocationProvider", "RevocationProvider.debug.dylib",
                      "__preview.dylib", "_CodeSignature/CodeResources"}}
    expected_files["app"] = {"Info.plist", "PkgInfo", "RevocationFixture", "RevocationFixture.debug.dylib",
                             "__preview.dylib", "_CodeSignature/CodeResources"} | {
                                 "PlugIns/RevocationProvider.appex/" + p for p in expected_files["provider"]}
    for tag, current in (("DIAG969", manifest), ("ORIGINAL601", reference)):
        artifact = index["artifacts"][tag]
        require(artifact["sourceCommit"] == current["source"], "Index source commit mismatch")
        directory = "producer969" if tag == "DIAG969" else "reference601"
        check_bytes(read(root, directory + "/artifact.json"), artifact["manifest"], "Index manifest")
        require(artifact["manifest"]["sha256"] == MANIFEST_HASHES[directory], "Index manifest pin mismatch")
        require(artifact["manifestAssertions"] == {k: current[k] for k in artifact["manifestAssertions"]},
                "Index manifest assertions mismatch")
        for kind in ("app", "provider"):
            bundle = artifact["bundles"][kind]
            records = bundle["files"]
            paths = [record["relativePath"] for record in records]
            for path in paths:
                relative_path(path)
            require(len(paths) == len(set(paths)) and set(paths) == expected_files[kind],
                    "Index file inventory incomplete or duplicate: " + tag + " " + kind)
            require(paths == sorted(paths), "Index records must be ordered by relative path")
            for record in records:
                validate_record(record, "Bundle index record")
                require(record["path"] == bundle["path"] + "/" + record["relativePath"],
                        "Index original path/relative path mismatch")
            tree = sha(b"".join(record["relativePath"].encode() + b"\0" + record["sha256"].encode() + b"\n"
                                for record in records))
            require(tree == bundle["bundleTreeSHA256"] == current[kind]["bundle_tree_sha256"],
                    "Index bundle tree hash mismatch")
            require(bundle["regularFileCount"] == len(records) and
                    bundle["totalRegularFileBytes"] == sum(r["bytes"] for r in records) and
                    bundle["otherEntries"] == [], "Index bundle count/type mismatch")
            by_name = {r["relativePath"]: r for r in records}
            executable = bundle["executable"]
            require(executable == by_name[bundle["infoPlist"]["CFBundleExecutable"]] and
                    executable["sha256"] == current[kind]["executable_sha256"], "Index executable hash mismatch")
            info_data = read(root, "producer969/bundle-metadata/" + kind + "/Info.plist")
            check_bytes(info_data, by_name["Info.plist"], "Archived Info.plist")
            info = plistlib.loads(info_data)
            for field in ("CFBundleIdentifier", "CFBundleExecutable", "CFBundleShortVersionString", "CFBundleVersion"):
                require(info[field] == bundle["infoPlist"][field], "Index Info.plist value mismatch")
            require(info["CFBundleIdentifier"] == current[kind]["bundle_id"] and
                    info["CFBundleShortVersionString"] == current[kind]["version"] and
                    info["CFBundleVersion"] == current[kind]["build"], "Bundle metadata mismatch")
            if tag == "DIAG969":
                check_bytes(read(root, "producer969/bundle-metadata/" + kind + "/_CodeSignature/CodeResources"),
                            by_name["_CodeSignature/CodeResources"], "Archived CodeResources")
            label = kind.capitalize()
            entitlements = artifact["entitlements"][label]
            images = entitlements["images"]
            require([i["image"] for i in images] == ["main", "debug-dylib", "__preview.dylib"],
                    "Index must include main, debug and preview images")
            require(entitlements["allImmediateMachOSectionOwners"] == ["main", "__preview.dylib"] and
                    entitlements["sectionOwnersWithinManifestImageScope"] == ["main"] and
                    entitlements["manifestReportedImageNames"] == ["main", "debug-dylib"] and
                    entitlements["unreportedMachOImageNames"] == ["__preview.dylib"] and
                    entitlements["manifestImageInventoryCompleteForAllImmediateMachO"] is False,
                    "Index hides preview section coverage finding")
            for image in images:
                record = by_name[image["relativePath"]]
                require(image["sha256"] == record["sha256"] and image["bytes"] == record["bytes"],
                        "Indexed Mach-O digest differs from bundle inventory")
                sections = image["sections"]
                require(set(sections) == (set() if image["image"] == "debug-dylib" else set(SECTIONS)),
                        "Index XML/DER owner pair mismatch")
                for section_name, section in sections.items():
                    anchor = current["simulatorEntitlements"][label]["images"][0]["sections"][section_name]
                    require(section["bytes"] == anchor["bytes"] and section["sha256"] == anchor["sha256"],
                            "Index preview/main entitlement digest mismatch")
            require(bundle["manifest"] == current[kind], "Index bundle manifest differs")
        app_records = {r["relativePath"]: r for r in artifact["bundles"]["app"]["files"]}
        for record in artifact["bundles"]["provider"]["files"]:
            app_record = app_records["PlugIns/RevocationProvider.appex/" + record["relativePath"]]
            require((record["bytes"], record["sha256"]) == (app_record["bytes"], app_record["sha256"]),
                    "Embedded provider inventory mismatch")
    for comparison in index["comparisonTo601"].values():
        require(comparison["priorApprovalReused"] is False, "601 approval cannot authorize DIAG969")
    require(index["summary"]["mainOnlyAcrossAllBundleMachO"] is False and
            index["summary"]["producerImageInventoryCompleteForAllImmediateMachO"] is False,
            "Index summary overstates manifest image coverage")
    return {"status": "PASS", "appFiles": 11, "providerFiles": 5,
            "allImmediateMachOSectionOwners": ["main", "__preview.dylib"],
            "binaryBytesRetained": False, "verification": "index metadata against pinned manifest hashes"}


def verify_addendum(root):
    prefix = "post-build-test-addendum-0605/"
    provenance = load_json(root, prefix + "provenance.json")
    require(provenance["source"] == "0605b5a58c9539509c287134bc36d28c3aeec778" and
            provenance["runtimeProducer"] == SOURCE and provenance["rebuilt"] is False,
            "Test addendum must remain separate from runtime producer")
    patch = read(root, prefix + "original-commit.patch")
    require(patch.startswith(b"commit 0605b5a58c9539509c287134bc36d28c3aeec778\n") and
            set(re.findall(rb"^diff --git a/(\S+) b/\S+$", patch, re.M)) ==
            {name.encode() for name in provenance["files"]}, "Post-build patch scope changed")
    fixed = read(root, prefix + "files/scripts/ios_fileprovider_revocation/run-modify-diagnostic-checks.py")
    require(b".read_bytes()" in fixed and b"def verify_source" in fixed, "Raw-byte test fix missing")
    commands = load_json(root, prefix + "check-logs/commands.json")
    require(len(commands) == 2 and all(c["exitCode"] == 0 for c in commands), "Addendum checks failed")
    logs = [read(root, prefix + "check-logs/" + c["log"]).decode() for c in commands]
    require("Ran 28 tests" in logs[0] and "OK" in logs[0] and "79" in logs[1] and
            '"sourceProof": "PASS"' in logs[1], "Addendum historical check results missing")
    return {"status": "PASS", "source": provenance["source"], "rebuilt": False,
            "historicalPythonTests": 28, "historicalHostChecks": 79}


def verify_archive(root):
    root = Path(root).resolve()
    inventory = verify_inventory(root)
    manifest, reference = verify_manifests(root)
    return {"status": "PASS", "source": SOURCE, "mode": "PORTABLE_ARCHIVE_ONLY",
            "inventory": inventory, "commands": verify_commands(root, manifest),
            "sourceProof": verify_source_proof(root, manifest, reference),
            "sdkReview": verify_sdk_review(root),
            "entitlements": verify_entitlements(root, manifest, reference),
            "bundleIndex": verify_bundle_index(root, manifest, reference),
            "postBuildTestAddendum": verify_addendum(root),
            "nativeExecution": "NOT_RUN", "domainRegistration": "NOT_RUN",
            "physicalDevice": False, "simulatorEntitlementReviewRequired": True,
            "prior601AuthorizationReused": False}


def host_replay(root):
    """Compile only the two archived Foundation files, using host swiftc directly."""
    compiler = shutil.which("swiftc")
    require(compiler is not None, "Host swiftc unavailable; portable verification needs no Swift")
    relative_files = ["Provider/RevocationModifyDiagnostics.swift", "ModifyDiagnosticsChecks.swift"]
    manifest = load_json(root, "producer969/artifact.json")
    with tempfile.TemporaryDirectory(prefix="diag969-host-replay-") as temporary:
        sources = []
        for name in relative_files:
            data = read(root, "producer969/fixture-source/" + name)
            require(sha(data) == manifest["sourceFiles"][name], "Host replay source pin mismatch")
            require(set(re.findall(rb"^import (\w+)", data, re.M)) <= {b"Foundation"},
                    "Host replay may import Foundation only")
            destination = Path(temporary) / Path(name).name
            destination.write_bytes(data)
            sources.append(str(destination))
        executable = Path(temporary) / "checks"
        compilation = subprocess.run([compiler, "-warnings-as-errors", *sources, "-o", str(executable)],
                                     cwd=temporary, capture_output=True, text=True, timeout=60)
        require(compilation.returncode == 0, "Host Swift compilation failed: " + compilation.stderr)
        execution = subprocess.run([str(executable)], cwd=temporary, capture_output=True, text=True, timeout=30)
        require(execution.returncode == 0 and "79" in execution.stdout and "PASS" in execution.stdout,
                "Host diagnostic checks failed: " + execution.stdout + execution.stderr)
        return {"status": "PASS", "checks": 79, "compiler": "host swiftc", "output": execution.stdout.strip(),
                "nativeExecution": "NOT_RUN", "fileProviderImported": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-replay", action="store_true", help="also compile/run the two archived Foundation-only Swift files")
    arguments = parser.parse_args()
    try:
        root = Path(__file__).resolve().parent
        report = verify_archive(root)
        if arguments.host_replay:
            report["hostReplay"] = host_replay(root)
        print(json.dumps(report, indent=2))
    except (VerificationError, KeyError, TypeError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
