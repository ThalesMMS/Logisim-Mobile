#!/usr/bin/env python3
"""Verify retained Missing Files runs only; no live paths, device, build or logs."""
from pathlib import Path
import hashlib
import json
import math
import re
import struct
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
RECEIPT = ROOT.parent.parent / "ios-missing-file-selected-runs.json"
TEST = "EditorEndToEndTests/FilesEndToEndTests/testMissingExternalDocumentReportsFailureAndPreservesProject"
EXPECTED = {
    "6cef-fail": {
        "source": "6cef05429cf66be23569aaebc3c72950cf3fe53a", "status": "FAIL", "gate": "NATIVE_FAIL",
        "qualification": "FAIL", "duration": 82.204, "exit": 65, "run": "2b70fa63bb48", "failures": 1,
        "appManifest": "6006751321c48752f5676ddaba3b6ae20cbc7f10f43f60a5000c855a1e34ac17",
        "tree": "d987d43d6587c0139ff0ecddca2d4ae95c9bad66ba6cd19d29c6452214be3080",
        "executable": "198a06dc847cc0fd8717b7c05b06f334b8a1e701aaf63aed5a6012699be5b946",
        "input": "355af6af15ba4781174d7aa3534aa3967491e7c93cc62a1483eced24d6c9cd31",
        "xml": "6902ee08e63496955ee44e6d4a4402c6e42b1732e32ce8a9a1984d1ce040fb47",
        "message": "The file doesn’t exist.", "png": (1668, 2420), "retained": 15, "total": 101,
        "stage": "contextual-error guard", "after": "NOT_RUN",
    },
    "ce3-pass": {
        "source": "ce3e061d38cd9851038d1cd8a71557ede9add641", "status": "PASS", "gate": "NATIVE_PASS",
        "qualification": "PARTIAL", "duration": 169.212, "exit": 0, "run": "c702d6c5c277", "failures": 0,
        "appManifest": "3b48f3ca39420de5e36f6ff5e1a8304c1bbb6bfafb953acd57e7adf5dc09fd2d",
        "tree": "f392420c7f33834a7cf851b643bca64011fd62a7f7d49cfee760e7ad6dcc911f",
        "executable": "833ac07f15eab61ee059f4cc97ea6fd9e4cb2477425cf7f7b831a932c73788cc",
        "input": "b97c12341ff44a655a750f9ec21c2597c2d76478efa93522076b01fc229278ff",
        "xml": "f1797171784e749359236dfcc86c70b64f71e258416cba76d7e7641f30496894",
        "message": "Could not access missing-external.circ: The file doesn’t exist.", "png": (1170, 2532),
        "retained": 24, "total": 63, "stage": "post-acknowledgement XML, control, missing-file and editor checks complete",
        "after": "EXACT_MATCH",
    },
}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def alert_body(tree, expected):
    lines = tree.splitlines()
    title, = [index for index, line in enumerate(lines) if "StaticText," in line and line.endswith("label: 'Could not complete action'")]
    indent = len(lines[title]) - len(lines[title].lstrip())
    parent = next(index for index in range(title - 1, -1, -1)
                  if len(lines[index]) - len(lines[index].lstrip()) < indent)
    level = len(lines[parent]) - len(lines[parent].lstrip())
    end = next((index for index in range(title + 1, len(lines))
                if lines[index].strip() and len(lines[index]) - len(lines[index].lstrip()) <= level), len(lines))
    section = lines[parent:end]
    message, = [line for line in section if "StaticText," in line and line.endswith("label: '" + expected + "'")]
    assert len(message) - len(message.lstrip()) > level
    assert any("Button," in line and line.endswith("label: 'Got it'") for line in section)
    return "\n".join(section)


def document(raw, title):
    assert b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper()
    project = ET.fromstring(raw)
    circuit, = project.findall("circuit")
    assert project.find("main").get("name") == circuit.get("name") == title
    components = circuit.findall("comp")
    assert [component.get("name") for component in components] == ["Pin", "NOT Gate", "Pin"]
    assert [component.get("loc") for component in components] == ["(100,100)", "(200,100)", "(300,100)"]
    assert [component.find("a[@name='label']").get("val") for component in components] == ["E2E_IN", "E2E_NOT", "E2E_OUT"]
    assert [(wire.get("from"), wire.get("to")) for wire in circuit.findall("wire")] == [
        ("(100,100)", "(170,100)"), ("(200,100)", "(300,100)")]


def rectangle(text, key):
    match = re.search(re.escape(key) + r"\(([^)]+)\)", text)
    assert match, key
    values = tuple(float(value) for value in match[1].split(","))
    assert len(values) == 4 and all(math.isfinite(value) for value in values)
    return values


def verify():
    receipt = json.loads(RECEIPT.read_text())
    manifest_raw = (ROOT / "manifest.json").read_bytes()
    assert digest(manifest_raw) == receipt["evidenceManifestSHA256"]
    manifest = json.loads(manifest_raw)
    data = {}
    for name, entry in manifest["files"].items():
        assert not Path(name).is_absolute() and ".." not in Path(name).parts
        path = ROOT / name
        assert not path.is_symlink()
        raw = path.read_bytes()
        assert len(raw) == entry["bytes"] and digest(raw) == entry["sha256"], name
        data[name] = raw
    assert len(data) == receipt["rawFileCount"] == 74
    assert sum(map(len, data.values())) == receipt["rawFileBytes"]
    assert receipt["test"] == TEST and set(receipt["cases"]) == set(EXPECTED)
    assert receipt["nativeRerun"] == "NOT_RUN" and receipt["controlledAB"] is False
    assert receipt["qualifiedScenarios"] == {"systemOpen": False, "revokedAuthorization": False,
                                             "cloudOrRemoteProvider": False, "fileProviderExtension": False,
                                             "physicalDevice": False, "allIssue26Or36": False}
    harness = []
    for case, expected in EXPECTED.items():
        summary = receipt["cases"][case]
        def raw(name):
            return data[case + "/" + name]
        results = json.loads(raw("results.json"))
        run = json.loads(raw("harness/Run.json"))
        producer = json.loads(raw("app-artifact.json"))
        harness.append(results["harness_sources"])
        assert summary["status"] == results["status"] == results["execution"]["status"] == expected["status"]
        assert summary["nativeExecutionGate"] == results["native_execution_gate"] == expected["gate"]
        assert summary["wholeSuiteQualification"] == results["qualification_gate"] == expected["qualification"]
        assert summary["wrapperExitCode"] == results["execution"]["exit_code"] == expected["exit"]
        assert summary["durationSeconds"] == expected["duration"]
        assert summary["selectedTests"] == 1 and summary["failedTests"] == expected["failures"]
        assert summary["stageReached"] == expected["stage"] and summary["afterAcknowledgementXML"] == expected["after"]
        assert results["selected_tests"] == results["execution"]["selection"] == [TEST]
        assert summary["sourceAppCommit"] == results["declared_baseline_sha"] == producer["source_sha"] == expected["source"]
        assert summary["runID"] == results["run_id"] == run["run_id"] == expected["run"]
        assert run == results["configuration"]
        assert results["alpha"] is False and results["physical_device"] is False
        assert summary["device"] == {"name": results["device"]["name"], "model": results["device"]["deviceTypeIdentifier"],
                                      "udid": results["simulator"], "runtime": results["device"]["runtime"], "physicalDevice": False}
        assert results["simulator"] == run["simulatorUDID"]
        assert digest(raw("app-artifact.json")) == summary["appArtifactManifestSHA256"] == expected["appManifest"]
        assert summary["appBundleTreeSHA256"] == expected["tree"] and summary["appExecutableSHA256"] == expected["executable"]
        for identity in [results["artifact"], results["main_app_installation"]["installed"], producer["artifact"]]:
            assert identity["bundle_tree_sha256"] == expected["tree"] and identity["executable_sha256"] == expected["executable"]
        for name in ["FilesEndToEndTests.swift", "NativeUIHarness.swift", "NativeTextReplacement.swift", "ObservedProjectXML.swift",
                     "NativeFilesPeerNavigator.swift", "NativeFilesDestinationNavigator.swift", "ClipboardReceiverUI.swift"]:
            assert digest(raw("harness/" + name)) == results["harness_sources"][name]
        assert digest(raw("harness/Catalog.json")) == results["catalog"]["sha256"]

        attachment_manifest, = json.loads(raw("attachments/manifest.json"))
        assert attachment_manifest["testIdentifier"] == "FilesEndToEndTests/testMissingExternalDocumentReportsFailureAndPreservesProject()"
        entries = attachment_manifest["attachments"]
        assert len(entries) == summary["totalAttachmentsInRawManifest"] == expected["total"]
        retained_roles = {}
        for entry in entries:
            name = "attachments/" + entry["exportedFileName"]
            if case + "/" + name in data:
                assert entry["deviceId"] == results["simulator"]
                role = entry["suggestedHumanReadableName"].split("_0_")[0]
                retained_roles.setdefault(role, []).append(name)
        for names in retained_roles.values():
            names.sort()
        assert retained_roles == summary["retainedAttachmentRoles"]
        assert sum(map(len, retained_roles.values())) == summary["retainedAttachments"] == expected["retained"]
        def attachment(role):
            name, = retained_roles[role]
            return raw(name)
        def has_role(role):
            return any(entry["suggestedHumanReadableName"].startswith(role + "_0_") for entry in entries)

        original = attachment("missing-external-original")
        assert original == raw("staged-external.circ") and len(original) == 1391 and digest(original) == expected["input"]
        before = attachment("before-missing-external")
        assert len(before) == 1591 and digest(before) == expected["xml"]
        document(before, run["titles"]["external"])
        setup = attachment("missing-external-picker-setup").decode()
        assert setup == ("route=Files Edit external\nfilename=missing-external.circ\nfixture=" + run["documents"]
                         + "/missing-external.circ\nexternalAssociation=Save File enabled before deletion")
        assert "peer=Logisim Mobile, run=" + expected["run"] in attachment("files-peer-requested-document").decode()
        assert "identifier=Logisim Mobile, Container" in attachment("files-peer-peer-application-folder-control").decode()
        log = raw("test.log").decode()
        assert 'Tap "Edit external file"' in log
        assert 'Tap "Save to file"' in log
        save_tail = log.rsplit('Tap "Save to file"', 1)[1]
        assert summary["observedDialogMessage"] == expected["message"]
        tree = attachment("missing-external-error-tree" if case == "ce3-pass" else "native-tree").decode()
        alert_body(tree, expected["message"])
        for role in ["missing-external-error", "final-screen"] if case == "ce3-pass" else ["final-screen"]:
            png = attachment(role)
            assert png.startswith(b"\x89PNG\r\n\x1a\n") and struct.unpack(">II", png[16:24]) == expected["png"]

        if case == "6cef-fail":
            assert not has_role("after-missing-external") and not has_role("geometry-canvas-after-missing-external")
            assert 'Tap "Got it"' not in save_tail
            assert "FilesEndToEndTests.swift:183: error:" in log and "failed (82.204 seconds)." in log
            assert "Executed 1 test, with 1 failure" in log
            assert "else -> LocalizedText.literal(error.localizedDescription)" in raw("producer/IosDocumentErrorBoundary.kt").decode()
            excluded = summary["excludedBoundedLog"]
            assert excluded["bytes"] == 2601103 and excluded["sha256"] == "96c8b634397fd18eb1ce03bd4091a5fa00a1fb0c0ef5d75197ada11fb0e5b270"
            assert excluded["retained"] == "LOCAL_ONLY" and excluded["nativeErrorDomainAndCode"] == "UNKNOWN"
        else:
            assert attachment("after-missing-external") == before
            assert 'Tap "Got it"' in save_tail and "passed (169.212 seconds)." in log
            assert "Executed 1 test, with 0 failures" in log
            assert log.index("Added attachment named 'after-missing-external.circ'") > log.index('Tap "Got it"')
            assert "message(Messages.IosDocumentAccessFailed" in raw("producer/IosDocumentErrorBoundary.kt").decode()
            catalog = json.loads(raw("harness/Catalog.json"))
            context = catalog["ios_document_access_failed"]["en"].replace("{name}", "missing-external.circ").replace("{reason}", "The file doesn’t exist.")
            assert context == expected["message"]
            postflight = json.loads(raw("independent-postflight.json"))
            assert postflight["status"] == "PASS" and postflight["suite_qualification"] == "PARTIAL"
            assert postflight["case_native_duration_seconds"] == expected["duration"]
            assert postflight["checks"] == {"before_after_UI_XML_exact": "PASS", "source_control_original": "PASS",
                                            "removed_file_remains_absent": "PASS", "typed_error_names_file": "PASS"}
            assert postflight["ui_xml"] == receipt["ce3UIXML"] == {"bytes": len(before), "sha256": expected["xml"]}
            assert postflight["source"] == {"bytes": len(original), "sha256": expected["input"]}
            assert receipt["ce3UntouchedControl"] == {"file": "staged-external.circ", "bytes": len(original), "sha256": expected["input"]}
            assert postflight["installed_after"]["bundle_tree_sha256"] == expected["tree"]
            assert postflight["installed_after"]["executable_sha256"] == expected["executable"]
            geometry = attachment("geometry-canvas-after-missing-external").decode()
            assert rectangle(geometry, "frame=") == rectangle(geometry, "visible=") == (0, 243, 390, 484)
            assert rectangle(geometry, "app=") == (0, 0, 390, 844)
            assert "identifier: 'editor-canvas'" in attachment("native-tree").decode()
            assert "label: 'Could not complete action'" not in attachment("native-tree").decode()
    assert harness[0] == harness[1] and len(harness[0]) == 17
    return {"offlineReceiptVerification": "PASS", "rawFiles": len(data), "6cefNativeCase": "FAIL",
            "ce3SelectedNativeCase": "PASS", "ce3WholeSuiteQualification": "PARTIAL", "ce3UIXMLBytes": 1591,
            "nativeRerun": "NOT_RUN", "controlledAB": False, "revokedAuthorizationQualified": False}


if __name__ == "__main__":
    print(json.dumps(verify(), indent=2))
