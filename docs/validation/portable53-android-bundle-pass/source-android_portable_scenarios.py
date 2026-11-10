"""Portable-project scenarios for the existing AndroidUi/Scenarios driver.

Call run_portable(scenarios) from the Android runner after installing its isolated
.e2e app. This module does not install, launch, or contact a device on import.
The source tree and bundle must already have been generated under
fixtures/portable-projects. System DocumentsUI uses the runner's Downloads and
exact filename selectors, with no fixed screen coordinates.

Folder confirmation needs one owner-supplied operation:
scenarios.confirm_native_directory(). It must confirm the currently displayed
sources folder using observed native controls and handle any permission dialog.
The current Scenarios API has no OpenDocumentTree confirmation operation. Its
absence fails the folder scenario explicitly, after the bundle scenario runs.

Receipts cover native picker routing, workspace identity, durable source bytes,
transitive source structure, restart, export, and reopening as a fresh copy.
The active window is established from the app's own pending CreateDocument JSON,
whose filename contains the same session ID passed to AndroidWorkspaceApp.
An optional scenarios.portable_window_id adds an owner-provided snapshot check;
without it, snapshots only record window candidates until an export proves one.
The scenario waits for the real canvas to show independent outputs 8 and 3.
The final desktop oracle must still consume each actual app-exported archive.
"""

from __future__ import annotations

import base64
import hashlib
import io
import json
from pathlib import Path
import posixpath
import re
import shlex
import tarfile
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures" / "portable-projects"
SOURCE_PATHS = ("main.circ", "library/bridge.circ", "library/cell.circ")
LABELS = ("FIRST53", "SECOND53")
ID_ATTRIBUTE = "logisim-mobile-id"


class PortableScenarioHandoff(AssertionError):
    """A missing native operation prevents this scenario from being qualified."""


def _sha(content):
    return hashlib.sha256(content).hexdigest()


def _fnv(content):
    value = 0xCBF29CE484222325
    for byte in content:
        value = ((value ^ byte) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return format(value, "x")


def _tokens(raw, kind, version, *, link=False):
    header, body = raw.decode("utf-8").split("\n\n", 1)
    lines = header.split("\n")
    content = body.encode("utf-8")
    checksum = f"{len(content)}-{_fnv(content)}" if link else _fnv(content)
    assert lines == [f"LOGISIM-{kind}", version, checksum], f"Invalid {kind} envelope"
    # Kotlin String.length counts UTF-16 code units, including two per astral character.
    data = body.encode("utf-16-le")
    values = []
    offset = 0
    while offset < len(data):
        digits = []
        while offset + 2 <= len(data) and 48 <= int.from_bytes(data[offset:offset + 2], "little") <= 57:
            digits.append(chr(int.from_bytes(data[offset:offset + 2], "little")))
            offset += 2
        assert digits and len(digits) <= 9 and data[offset:offset + 2] == b":\x00", "Invalid token length"
        offset += 2
        end = offset + int("".join(digits)) * 2
        assert end <= len(data), "Truncated workspace token"
        values.append(data[offset:end].decode("utf-16-le"))
        offset = end
    return iter(values)


def _info(tokens):
    identifier, name, revision, created, updated, deleted, previous = (next(tokens) for _ in range(7))
    history = [int(next(tokens)) for _ in range(int(next(tokens)))]
    assert re.fullmatch(r"[A-Za-z0-9_-]{1,80}", identifier), "Invalid workspace ID"
    assert int(revision) > 0 and deleted in ("true", "false"), "Invalid workspace info"
    return {"id": identifier, "name": name, "revision": int(revision), "deleted": deleted == "true",
            "created": int(created), "updated": int(updated), "previous": int(previous), "history": history}


def _finished(tokens):
    assert next(tokens, None) is None, "Unconsumed workspace fields"


def _instance_ids(project):
    main = project.find("main")
    assert main is not None, "Missing main circuit"
    circuit = next(node for node in project.findall("circuit") if node.get("name") == main.get("name"))
    result = {}
    for label in LABELS:
        matches = [node for node in circuit.findall("comp")
                   if any(a.get("name") == "label" and a.get("val") == label for a in node.findall("a"))]
        assert len(matches) == 1, f"Expected one {label} instance"
        result[label] = matches[0].get(ID_ATTRIBUTE)
        assert result[label], f"Missing stable component identity for {label}"
    assert len(set(result.values())) == 2, "The two instances share a component identity"
    assert result == {"FIRST53": "instance-first53", "SECOND53": "instance-second53"}, "Fixture instance identities changed"
    return result


def _bundle(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate bundle entries"
        files = {name: archive.read(name) for name in names if not name.endswith("/")}
    manifest = ET.fromstring(files["manifest.xml"])
    mains = manifest.findall("./project/files/file[@main='true']")
    assert len(mains) == 1 and mains[0].text, "Invalid bundle manifest"
    main_path = mains[0].text.strip()
    projects = {}
    graph = {}

    def visit(source):
        if source in projects:
            return
        assert len(projects) < 32 and source in files, "Missing or excessive source graph"
        projects[source] = ET.fromstring(files[source])
        graph[source] = []
        for library in projects[source].findall("lib"):
            description = library.get("desc", "")
            if description.startswith("file#"):
                relative = description[5:].replace("\\", "/")
                assert not relative.startswith("/") and ":" not in relative, "Nonportable source reference"
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(source), relative))
                assert not resolved.startswith("../"), "Source reference escapes the bundle"
                graph[source].append(resolved)
                visit(resolved)

    visit(main_path)
    definitions = [(circuit.get("name"), name) for name, project in projects.items() for circuit in project.findall("circuit")]
    owners = dict(definitions)
    assert len(definitions) == len(owners) == 3 and set(owners) == {"Top53", "Bridge53", "Cell53"}, "Transitive definitions changed"
    assert owners["Top53"] == main_path, "Top53 is not the bundle's main source"
    assert owners["Bridge53"] in graph[owners["Top53"]], "Missing Top53 to Bridge53 source reference"
    assert owners["Cell53"] in graph[owners["Bridge53"]], "Missing Bridge53 to Cell53 source reference"
    return {"sha256": _sha(content), "bytes": len(content), "sourceOwners": owners,
            "instanceIds": _instance_ids(projects[main_path]),
            "opaque": {name: _sha(raw) for name, raw in files.items() if name not in projects and name != "manifest.xml"},
            "files": {name: _sha(raw) for name, raw in sorted(files.items())}}


def _assert_fresh_copy(previous, current):
    previous_ids = set(previous["catalogProjectIds"])
    current_ids = set(current["catalogProjectIds"])
    identifier = current["project"]["id"]
    assert identifier not in previous_ids, "The copy reused an existing catalog workspace identity"
    assert identifier in current_ids, "The active copy is absent from its catalog"
    assert previous_ids <= current_ids, "Import removed an earlier catalog workspace"


class _Portable:
    def __init__(self, scenarios):
        self.s = scenarios
        self.ui = scenarios.ui
        self.token = uuid.uuid4().hex
        self.remote_name = "logisim-portable53-" + self.token
        self.remote = "/sdcard/Download/" + self.remote_name
        self.observed_window_id = None
        self.evidence = []

    def capture(self, name, root=None):
        captured = self.ui.capture(f"portable-{len(self.evidence):03d}-{name}", root)
        self.evidence.extend(str(Path(value).relative_to(self.ui.output)) for value in captured.values())

    def publish_staged_files(self, mode, files):
        records = []
        receipt = self.ui.output / f"portable-{mode}-source-publication.json"
        try:
            for relative, source in files:
                path = "/storage/emulated/0/Download/" + self.remote_name + "/" + relative
                expected = _sha(source.read_bytes())
                actual = self.ui.adb("shell", "sha256sum", path).split()[0]
                assert actual == expected, f"The staged source bytes differ from the fixture: {relative}"
                broadcast = self.ui.adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                                        "-d", "file://" + path)
                record = {"relativePath": relative, "physicalPath": path, "sha256": actual,
                          "publicationBroadcast": broadcast, "published": False}
                records.append(record)
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    command = shlex.join(["content", "query", "--uri", "content://media/external/file",
                        "--projection", "_id:_display_name:mime_type:is_download:is_pending", "--where", "_data='" + path + "'"])
                    metadata = self.ui.adb("shell", command)
                    record["mediaStore"] = metadata
                    if re.search(r"\bis_download=1\b", metadata) and re.search(r"\bis_pending=0\b", metadata):
                        record["published"] = True
                        break
                    time.sleep(0.2)
                assert record["published"], f"The staged source is not published in Downloads: {relative}"
        finally:
            receipt.write_text(json.dumps({"mode": mode, "files": records,
                "scope": "Test fixture publication before opening the production native picker; no app hook or reencoded source"}, indent=2) + "\n")

    def expect_canvas(self, name):
        deadline = time.monotonic() + 30
        description = ""
        while time.monotonic() < deadline:
            try:
                description = self.s.canvas().get("content-desc", "")
                if all(re.search(re.escape(label) + r"\s*:\s*" + bits + r"(?![01])", description)
                       for label, bits in (("OUT_FIRST53", "00001000"), ("OUT_SECOND53", "00000011"))):
                    self.capture(name)
                    return
            except AssertionError:
                pass
            time.sleep(0.2)
        raise AssertionError(f"The actual canvas did not show the portable project's independent outputs: {description!r}")

    def snapshot(self, name, *, portable=True):
        raw = self.ui.adb("exec-out", "run-as", self.ui.package, "tar", "-cf", "-", "files/workspace", text=False)
        (self.ui.output / f"{name}-workspace.tar").write_bytes(raw)
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            files = {}
            for member in archive.getmembers():
                if not member.isfile():
                    continue
                assert member.size <= 100 * 1024 * 1024, "Unexpected workspace member size"
                filename = Path(member.name).name
                assert filename not in files, "Duplicate native workspace filename"
                files[filename] = archive.extractfile(member).read()
        tokens = _tokens(files["catalog.workspace"], "CATALOG", "1")
        active = next(tokens)
        assert next(tokens) in ("true", "false"), "Invalid legacy migration flag"
        infos = [_info(tokens) for _ in range(int(next(tokens)))]
        _finished(tokens)
        info = next(value for value in infos if value["id"] == active and not value["deleted"])
        payload_name = f"project-{active}-{info['revision']}.workspace"
        tokens = _tokens(files[payload_name], "PROJECT", "4")
        saved = _info(tokens)
        assert saved["id"] == active and saved["revision"] == info["revision"], "Catalog/payload identity mismatch"
        xml = next(tokens)
        next(tokens)  # Active circuit.
        for _ in range(int(next(tokens))):
            next(tokens)  # Selection.
        assert next(tokens) in ("LEGACY", "LENGTH_PREFIXED"), "Unknown viewport key format"
        for _ in range(int(next(tokens))):
            for _ in range(4):
                next(tokens)
        for _ in range(int(next(tokens))):
            next(tokens)  # Instance path.
        for _ in range(int(next(tokens))):
            for _ in range(4):
                next(tokens)  # Wire coordinates.
        attachments = {}
        for _ in range(int(next(tokens))):
            key, filename, mime, size, digest = (next(tokens) for _ in range(5))
            assert key not in attachments, "Duplicate attachment key"
            attachments[key] = {"name": filename, "mime": mime, "bytes": int(size), "sha256": digest}
        _finished(tokens)
        windows = {}
        for filename, content in files.items():
            if filename.startswith("window-") and filename.endswith(".workspace-link"):
                link = _tokens(content, "LINK", "1", link=True)
                window, project = next(link), next(link)
                _finished(link)
                windows[window] = project
        window = getattr(self.s, "portable_window_id", None)
        if window is not None:
            assert isinstance(window, str) and window, "The owner-provided window ID must be a nonempty string"
            assert windows.get(window) == active, f"Owner-provided window {window!r} does not restore the active project"
        result = {"project": info, "catalogProjectIds": sorted(value["id"] for value in infos),
                  "payload": payload_name, "windows": windows, "attachments": attachments,
                  "ownerWindowId": window,
                  "windowCandidatesForActiveProject": sorted(key for key, value in windows.items() if value == active)}
        (self.ui.output / f"{name}.circ").write_text(xml, encoding="utf-8")
        if portable:
            assert f"external-{active}.workspace-link" not in files, "The new copy inherited an editable native reference"
            reference = attachments["portable-source"]
            assert reference["mime"] == "application/x-logisim-bundle", "Wrong portable-source MIME type"
            blob = files[f"attachment-{reference['sha256']}.workspace"]
            header = b"LOGISIM-WORKSPACE-ATTACHMENT\n1\n"
            assert blob.startswith(header), "Invalid source blob header"
            source = base64.b64decode(blob[len(header):], validate=True)
            assert len(source) == reference["bytes"] and _sha(source) == reference["sha256"], "Source blob/descriptor mismatch"
            (self.ui.output / f"{name}-source.lsebdl").write_bytes(source)
            result["source"] = _bundle(source)
            project = ET.fromstring(xml)
            result["instanceIds"] = _instance_ids(project)
            result["circuitIds"] = sorted(circuit.get("id") for circuit in project.findall("circuit"))
            assert len(result["circuitIds"]) == len(set(result["circuitIds"])) == 3, "Invalid persisted circuit identities"
        (self.ui.output / f"{name}-workspace.json").write_text(json.dumps(result, indent=2) + "\n")
        return result

    def wait_snapshot(self, name, *, previous):
        deadline = time.monotonic() + 30
        last_error = None
        while time.monotonic() < deadline:
            try:
                result = self.snapshot(name)
                _assert_fresh_copy(previous, result)
                return result
            except (AssertionError, KeyError, StopIteration, ET.ParseError) as error:
                last_error = error
                time.sleep(0.2)
        raise AssertionError(f"The portable workspace was not committed within 30s: {last_error}")

    def close_tool(self):
        if self.ui.tagged(self.ui.screen(), "editor-tool-sheet") is not None:
            self.s.click_key("action_close")
        self.s.wait_editor()

    def open_tool(self):
        self.close_tool()
        self.s.click_key("a11y_project_tools", description=True)
        self.s.click_key("portable_title", reveal=True)
        self.s.wait_key("portable_open_bundle")

    def open_bundle(self, filename, *, staged=False):
        self.open_tool()
        self.s.click_key("portable_open_bundle")
        self.s.choose_downloads()
        if staged:
            self.s.choose_native_file(self.remote_name)
        self.capture("bundle-open-picker")
        self.s.choose_native_file(filename)
        # The old canvas may remain visible while sources are parsed. The caller
        # must await the committed new project before dismissing its tool scope.

    def observe_export_window(self, name):
        self.observed_window_id = None
        listing = self.ui.adb("exec-out", "run-as", self.ui.package, "ls", "files")
        candidates = []
        observed = []
        for filename in listing.splitlines():
            match = re.fullmatch(r"asset-([A-Za-z0-9_-]{1,100})-create\.json", filename)
            if match is None:
                continue
            raw = self.ui.adb("exec-out", "run-as", self.ui.package, "cat", "files/" + filename)
            if len(raw.encode("utf-8")) > 32 * 1024:
                raise PortableScenarioHandoff(f"Installed APK exposed oversized create metadata: {filename}")
            try:
                operation = json.loads(raw)
            except (ValueError, TypeError) as error:
                raise PortableScenarioHandoff(f"Installed APK exposed unreadable create metadata: {filename}") from error
            if not isinstance(operation, dict):
                raise PortableScenarioHandoff(f"Installed APK exposed an unknown create metadata schema: {filename}")
            observed.append({"file": filename, "phase": operation.get("phase"), "mime": operation.get("mime")})
            if operation.get("phase") == "picker" and operation.get("mime") == "application/x-logisim-bundle":
                if not isinstance(operation.get("name"), str) or not operation["name"].lower().endswith(".lsebdl"):
                    raise PortableScenarioHandoff(f"Installed APK exposed a bundle picker with an invalid name: {filename}")
                try:
                    payload = operation["payload"]
                    assert isinstance(payload, str) and str(uuid.UUID(payload)) == payload
                except (KeyError, ValueError, AssertionError, AttributeError) as error:
                    raise PortableScenarioHandoff(f"Installed APK exposed an unknown create payload schema: {filename}") from error
                candidates.append({"file": filename, "session": match.group(1), "operation": operation})
        if len(candidates) != 1:
            raise PortableScenarioHandoff(
                "Expected exactly one app-owned asset-<session>-create.json with phase=picker and "
                f"mime=application/x-logisim-bundle; observed {observed!r}. "
                "Qualify the installed APK metadata before inferring a window identity.")
        proof = candidates[0]
        self.observed_window_id = proof["session"]
        (self.ui.output / f"portable-{name}-create-operation.json").write_text(json.dumps(proof, indent=2) + "\n")
        return self.observed_window_id

    def export(self, name):
        self.open_tool()
        self.s.click_key("portable_export", reveal=True)
        self.s.choose_downloads()
        root = self.ui.screen()
        fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"]
        assert len(fields) == 1, "CreateDocument did not expose one filename field"
        self.observe_export_window(name)
        self.capture(name + "-save-picker", root)
        filename = f"{self.remote_name}-{name}.lsebdl"
        self.ui.input_text(fields[0], filename)
        ime = self.ui.adb("shell", "dumpsys", "input_method")
        if re.search(r"mInputShown=true|isInputViewShown=true", ime):
            self.ui.key(4)
        self.ui.tap(self.ui.find(self.ui.screen(), resource_id="android:id/button1"))
        self.s.wait_key("portable_saved")
        self.capture(name + "-saved")
        self.close_tool()
        content = self.ui.adb("exec-out", "cat", "/sdcard/Download/" + filename, text=False)
        path = self.ui.output / filename
        path.write_bytes(content)
        return filename, _bundle(content)

    def roundtrip(self, mode, before):
        evidence_start = len(self.evidence)
        imported = self.wait_snapshot(f"portable-{mode}-imported", previous=before)
        self.close_tool()
        self.expect_canvas(mode + "-imported-canvas")
        filename, exported = self.export(mode + "-before-restart")
        first_window = self.observed_window_id
        assert first_window and imported["windows"].get(first_window) == imported["project"]["id"], \
            "The actual export session is not linked to the imported workspace"
        assert exported["opaque"] == imported["source"]["opaque"], "Export changed opaque source archive entries"
        self.ui.adb("shell", "am", "force-stop", self.ui.package)
        stopped = self.ui._run(("shell", "pidof", self.ui.package), check=False)
        assert not stopped.stdout.strip(), "The original app process survived force-stop"
        self.observed_window_id = None
        self.s.launch()
        self.expect_canvas(mode + "-restored-canvas")
        restored = self.snapshot(f"portable-{mode}-restored")
        assert restored["project"]["id"] == imported["project"]["id"], "Restart restored another workspace"
        assert set(imported["catalogProjectIds"]) <= set(restored["catalogProjectIds"]), "Restart removed earlier workspaces"
        assert restored["attachments"] == imported["attachments"], "Restart changed source archive metadata"
        assert restored["instanceIds"] == imported["instanceIds"], "Restart changed the root instance identities"
        assert restored["circuitIds"] == imported["circuitIds"], "Restart changed circuit identities"
        restored_filename, restored_export = self.export(mode + "-after-restart")
        restored_window = self.observed_window_id
        assert restored_window and restored["windows"].get(restored_window) == restored["project"]["id"], \
            "The actual post-restart export session is not linked to the restored workspace"
        assert exported["files"] == restored_export["files"], "Restart changed the exported source graph"
        self.open_bundle(restored_filename)
        reopened = self.wait_snapshot(f"portable-{mode}-reopened", previous=restored)
        self.close_tool()
        self.expect_canvas(mode + "-reopened-canvas")
        assert reopened["source"]["sha256"] == restored_export["sha256"], "Reopened copy did not persist the exported archive"
        assert reopened["instanceIds"] == restored["instanceIds"], "Reopening changed the root instance identities"
        assert reopened["circuitIds"] == restored["circuitIds"], "Reopening changed circuit identities"
        manifest = json.loads((self.ui.output / "manifest.json").read_text())
        application_hash = manifest.get("appSha256", "")
        if not re.fullmatch(r"[0-9a-f]{64}", application_hash):
            raise PortableScenarioHandoff("The native run manifest must fingerprint the installed app before a UI receipt is emitted")
        receipt = {"schema": 1, "fixture": "portable53-v1", "platform": "android", "status": "PASSED", "executedViaUi": True,
                   "scope": "native UI picker/export/restart/reopen, canvas outputs, and durable storage",
                   "desktopOracle": "required separately on these actual app exports", "mode": mode, "device": self.ui.serial,
                   "appArtifactSha256": application_hash,
                   "importedProjectId": imported["project"]["id"], "restartedProjectId": restored["project"]["id"],
                   "reopenedProjectId": reopened["project"]["id"],
                   "catalogProjectIds": {"before": before["catalogProjectIds"], "imported": imported["catalogProjectIds"],
                       "restarted": restored["catalogProjectIds"], "reopened": reopened["catalogProjectIds"]},
                   "exports": [{"path": filename, "sha256": exported["sha256"], "afterRestart": False},
                               {"path": restored_filename, "sha256": restored_export["sha256"], "afterRestart": True}],
                   "observedWindowBeforeRestart": first_window, "observedWindowAfterRestart": restored_window,
                   "fixturePublication": f"portable-{mode}-source-publication.json",
                   "evidence": self.evidence[evidence_start:],
                   "workspaceSnapshots": {"before": f"portable-{mode}-before-workspace.tar", "imported": f"portable-{mode}-imported-workspace.tar",
                       "restarted": f"portable-{mode}-restored-workspace.tar", "reopened": f"portable-{mode}-reopened-workspace.tar"}}
        (self.ui.output / f"portable-{mode}-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")

    def bundle(self):
        before = self.snapshot("portable-bundle-before", portable=False)
        self.ui.adb("shell", "mkdir", "-p", self.remote)
        self.ui.adb("push", str(FIXTURES / "portable53.lsebdl"), self.remote + "/portable53.lsebdl")
        self.publish_staged_files("bundle", [("portable53.lsebdl", FIXTURES / "portable53.lsebdl")])
        self.open_bundle("portable53.lsebdl", staged=True)
        self.roundtrip("bundle", before)

    def folder(self):
        confirm = getattr(self.s, "confirm_native_directory", None)
        if not callable(confirm):
            raise PortableScenarioHandoff(
                "Folder qualification needs scenarios.confirm_native_directory(): confirm the observed "
                "DocumentsUI sources folder and any permission dialog. AndroidUi/Scenarios currently "
                "provides file selection only; no folder confirmation coordinates are assumed.")
        before = self.snapshot("portable-folder-before", portable=False)
        self.ui.adb("shell", "mkdir", "-p", self.remote)
        self.ui.adb("push", str(FIXTURES / "sources"), self.remote + "/")
        self.publish_staged_files("folder", [("sources/" + name, FIXTURES / "sources" / name) for name in SOURCE_PATHS])
        self.open_tool()
        self.s.click_key("portable_open_directory")
        self.s.choose_downloads()
        self.s.choose_native_file(self.remote_name)
        self.s.choose_native_file("sources")
        confirm()
        self.ui.click(text="main.circ")
        self.roundtrip("folder", before)


def run_portable(scenarios):
    """Register real picker/storage scenarios; missing required operations fail loudly."""
    assert scenarios.ui.package.endswith(".e2e"), "Portable scenarios require the isolated E2E application"
    for path in (FIXTURES / "portable53.lsebdl", *(FIXTURES / "sources" / name for name in SOURCE_PATHS)):
        if not path.is_file():
            raise PortableScenarioHandoff(f"Generate the portable53 fixture before running native scenarios: {path}")
    for key in ("portable_title", "portable_open_bundle", "portable_open_directory", "portable_export", "portable_saved"):
        assert key in scenarios.catalog, f"The runner's localization catalog lacks {key}"
    portable = _Portable(scenarios)
    scenarios.run("portable-bundle-storage-roundtrip", portable.bundle)
    scenarios.run("portable-folder-storage-roundtrip", portable.folder)
