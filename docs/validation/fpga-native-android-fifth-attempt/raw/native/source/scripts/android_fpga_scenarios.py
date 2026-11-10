"""FPGA mapping and companion checks through the isolated Android editor's UI."""
from __future__ import annotations

import json
import hashlib
import io
import re
import shlex
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

from android_editing_scenarios import _Editing


def signal_row(root, package, titles, connection_labels):
    """Require one signal's own controls, never the first repeated Connection field."""
    headings = [node for node in root.iter("node")
                if node.get("package") == package and node.get("text") in titles]
    assert len(headings) == 1, f"Expected one signal heading {sorted(titles)!r}, found {len(headings)}"
    parents = {child: parent for parent in root.iter() for child in parent}
    scope = parents.get(headings[0])
    while scope is not None:
        connections = [node for node in scope.iter("node") if node.get("package") == package
                       and any(node.get("text", "").startswith(label + ": ") for label in connection_labels)]
        if connections:
            assert len(connections) == 1, "Signal controls were flattened together with another mapping row"
            assert sum(node.get("text") in titles for node in scope.iter("node")) == 1
            return scope
        scope = parents.get(scope)
    raise AssertionError("The signal heading has no associated Connection control")


def reviewed_parameters(root, package):
    found = []
    for node in root.iter("node"):
        if node.get("package") != package:
            continue
        try:
            value = json.loads(node.get("text", ""))
        except (ValueError, TypeError):
            continue
        if isinstance(value, dict) and value.get("flow") in ("ice40", "ecp5"):
            found.append(value)
    assert len(found) == 1, f"Expected one reviewed FPGA parameter object, found {len(found)}"
    return found[0]


def labelled_editor(root, package, labels):
    parents = {child: parent for parent in root.iter() for child in parent}
    candidates = []
    for label in root.iter("node"):
        if label.get("package") != package or not any(label.get(key) in labels for key in ("text", "content-desc", "hint", "hint-text")):
            continue
        scope = label
        while scope is not None:
            fields = [node for node in scope.iter("node") if node.get("package") == package
                      and node.get("class", "").endswith("EditText")]
            if fields:
                assert len(fields) == 1, "The board XML label is not associated with a unique editor"
                if fields[0] not in candidates:
                    candidates.append(fields[0])
                break
            scope = parents.get(scope)
    assert len(candidates) == 1, "The board XML editor has no unique public label"
    return candidates[0]


def assert_review(parameters, goal):
    assert parameters.get("flow") == "ice40"
    assert parameters.get("part") == "hx1k"
    assert parameters.get("package") == "tq144"
    assert parameters.get("top") == "logisim_fpga_top"
    assert parameters.get("language") == "verilog"
    assert parameters.get("goal") == goal
    sources = parameters.get("sources")
    assert isinstance(sources, list) and len(sources) >= 2 and len(set(sources)) == len(sources)
    assert all(isinstance(name, str) and re.fullmatch(r"source-[0-9]{4}\.v", name) for name in sources)
    assert "target" not in parameters and "flash" not in parameters, "Synthesis must not request programming"
    if goal == "bitstream":
        assert parameters.get("constraint") == "pins.pcf"
    else:
        assert "constraint" not in parameters


def assert_project_archive(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        expected = {"c_main.v", "logisim_fpga_top.v", "pins.pcf", "synthesis.ys"}
        assert len(archive.namelist()) == len(expected) and set(archive.namelist()) == expected
        assert archive.testzip() is None, "The FPGA project ZIP failed its CRC check"
        files = {name: archive.read(name) for name in expected}
    assert files["pins.pcf"].decode("utf-8") == (
        "set_io fpga_p0_b0 21\nset_io fpga_p1_b0 22\nset_io fpga_p2_b0 23\n")
    combined = files["c_main.v"].decode("utf-8") + files["logisim_fpga_top.v"].decode("utf-8")
    assert all(port in combined for port in ("p_a", "p_b", "p_y"))
    assert "synth_ice40 -top logisim_fpga_top" in files["synthesis.ys"].decode("utf-8")
    return {name: {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()} for name, value in files.items()}


def job_identifier(root, package, templates):
    found = []
    for node in root.iter("node"):
        if node.get("package") != package:
            continue
        for template in templates:
            before, after = template.split("{id}")
            text = node.get("text", "")
            if text.startswith(before) and (not after or text.endswith(after)):
                value = text[len(before):len(text) - len(after) if after else None]
                if re.fullmatch(r"[0-9a-f]{32}", value):
                    found.append(value)
    assert len(found) == 1, f"Expected one visible companion job ID, found {found}"
    return found[0]


def verify_native_audit(audit, observations):
    assert audit.get("operations") == ["fpga-synthesize"] and audit.get("programmingRegistered") is False
    jobs = {job["jobId"]: job for job in audit["jobs"]}
    assert len(jobs) == len(audit["jobs"]), "The host returned duplicate job receipts"
    assert len(jobs) == len(observations), "A native action submitted an unexpected number of FPGA jobs"
    assert len({item["jobId"] for item in observations}) == len(observations)
    for item in observations:
        job = jobs[item["jobId"]]
        assert job["operation"] == "fpga-synthesize"
        submissions = [request for request in audit["requests"] if request["action"] == "submit"
                       and request.get("jobId") == item["jobId"] and request.get("status") == "RETURNED"]
        assert len(submissions) == 1, "Each native case requires exactly one submitted job"
        events = [event for event in audit["events"] if event["jobId"] == item["jobId"]]
        if item["case"] == "cancel":
            assert item.get("observedNonterminalState") in ("queued", "running"), "The UI did not observe an active job before Cancel"
            assert job["state"] == "cancelled", "A cancel request did not cancel the native job"
            assert any(request["action"] == "cancel" and request.get("jobId") == item["jobId"]
                       and request.get("status") == "RETURNED" for request in audit["requests"])
            continue  # A genuinely cancelled queued job need not enter the tool handler.
        assert len(events) == 1, "The native job did not enter the real synthesis handler exactly once"
        event = events[0]
        assert event["parameters"] == item["reviewedParameters"], "Submitted parameters differ from the native reviewed plan"
        sources = event["parameters"]["sources"]
        assert set(event["inputs"]) == set(sources) | ({"pins.pcf"} if item["case"] == "tool-error" else set())
        source = "\n".join(event["inputs"][name]["text"] for name in sources)
        assert all(port in source for port in ("p_a", "p_b", "p_y")), "Native HDL lost the fixture ports"
        assert any(log["stream"] == "stage" and log["text"] == "synthesize" for log in event["logs"])
        if item["case"] == "synthesis":
            assert job["state"] == "succeeded" and event["status"] == "RETURNED"
            result = job["result"]
            assert result["synthesized"] is True
            assert all(result[key] is False for key in ("placedAndRouted", "bitstreamGenerated", "programmed", "hardwareVerified"))
            assert [artifact["name"] for artifact in result["artifacts"]] == ["synthesized.json"]
            exported = item["projectArchive"]["files"]
            assert sorted(event["inputs"][name]["sha256"] for name in sources) == sorted(
                exported[name]["sha256"] for name in ("c_main.v", "logisim_fpga_top.v")), "The companion HDL differs from the app's exported project"
        else:
            assert item["case"] == "tool-error" and job["state"] == "failed"
            assert job["error"]["code"] == "fpga-stage-failed"
            assert job["error"]["details"]["stage"] == "place-route"
            assert " 999\n" in event["inputs"]["pins.pcf"]["text"], "The intended invalid package pin never reached nextpnr"
    return {"status": "PASS", "cases": [item["case"] for item in observations], "jobs": len(jobs)}


class FpgaScenarios:
    def __init__(self, scenarios, host, fixtures):
        self.s, self.ui, self.host = scenarios, scenarios.ui, host
        self.e = _Editing(scenarios)
        self.fixtures = fixtures
        self.observations = []

    def visible_text(self, root, values):
        return [node for node in root.iter("node") if node.get("package") == self.ui.package
                and node.get("text") in values and self.ui.visible_node(root, node)]

    def global_click(self, values):
        def locate(root):
            candidates = []
            for node in self.visible_text(root, values):
                action = self.e.action_node(root, node)
                if action.get("clickable") == "true" and action not in candidates:
                    candidates.append(action)
            assert len(candidates) <= 1, f"Ambiguous visible action: {sorted(values)!r}"
            return candidates[0] if candidates else None
        self.ui.tap(self.e.wait(locate, "unique visible FPGA action"))

    def seek(self, predicate, description, *, timeout=30):
        deadline = time.monotonic() + timeout
        last = None
        for reverse in (False, True):
            previous = None
            for _ in range(9):
                assert time.monotonic() < deadline, f"Timed out revealing {description}: {last}"
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                try:
                    found = predicate(root)
                    if found is not None and found is not False:
                        return found
                except AssertionError as error:
                    last = str(error)
                signature = tuple((node.get("text"), node.get("bounds")) for node in root.iter("node"))
                if signature == previous:
                    break
                previous = signature
                self.e.swipe(self.e.scroll_region(root, False), reverse=reverse)
        raise AssertionError(f"Could not reveal {description}: {last}")

    def diagnostic(self, code):
        def locate(root):
            found = [node for node in root.iter("node") if node.get("package") == self.ui.package
                     and node.get("text", "").startswith(code + ":") and self.ui.visible_node(root, node)]
            return root if found else None
        return self.seek(locate, code + " diagnostic")

    def await_reveal(self, predicate, description, *, timeout=30):
        """Poll an unchanged pending UI without repeating search swipes or renewing its deadline."""
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                return self.seek(predicate, description, timeout=max(0.1, deadline - time.monotonic()))
            except AssertionError as error:
                last = str(error)
            previous = None
            while time.monotonic() < deadline:
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                result = predicate(root)
                if result is not None and result is not False:
                    return result
                signature = tuple((node.get("text"), node.get("bounds")) for node in root.iter("node"))
                if previous is not None and signature != previous:
                    break
                previous = signature
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
        raise AssertionError(f"Timed out waiting for {description}: {last}")

    def open_tool(self, key):
        self.e.click("a11y_project_tools", description=True)
        self.e.click(key, scroll=True)

    def close_tool(self):
        self.e.hide_keyboard()
        if self.e.nodes(self.ui.screen(), "fpga_companion_title"):
            self.e.click("fpga_companion_back", scroll=True)
        self.e.click("action_close", scroll=True)
        self.s.wait_editor()

    def pair(self):
        self.open_tool("companion_ui_title")
        def editor(key, *, confidential=False):
            def locate(root):
                scope, _ = self.e.scope(root)
                field = labelled_editor(scope, self.ui.package, self.e.values(key))
                assert field.get("enabled") == "true", "The pairing editor is disabled"
                if confidential:
                    assert field.get("password") == "true", "The confidential token editor must mask its value"
                    assert field.get("text", "") == "", "Pairing requires an empty confidential field"
                return field if self.ui.visible_node(root, field) else None
            return self.await_reveal(locate, key)
        editor("companion_ui_token_a11y", confidential=True)
        self.ui.input_text(editor("companion_ui_address"), self.host.endpoint)
        self.e.hide_keyboard()
        self.ui.input_text(editor("companion_ui_token_a11y", confidential=True), self.host.token)
        self.e.hide_keyboard()
        self.e.click("companion_ui_verify", scroll=True)
        self.e.reveal("additional_companion_verified")
        self.e.reveal("additional_companion_operation", operation="fpga-synthesize",
                      status=self.s.catalog["additional_companion_available"])
        self.ui.capture("fpga-host-verified")
        self.close_tool()

    def load(self, board_file):
        source = (self.fixtures / "fpga-and.circ").read_bytes()
        self.s.incoming(self.s.fixture(source))
        before, _ = self.e.export("fpga-before-" + board_file.stem)
        self.open_tool("fpga_title")
        if self.e.nodes(self.ui.screen(), "fpga_companion_title"):
            self.e.click("fpga_companion_back", scroll=True)
        board = ET.fromstring(board_file.read_bytes())
        # A new, visible board identity resets its public mapping session between cases.
        board.tag += "_" + uuid.uuid4().hex[:8]
        payload = ET.tostring(board, encoding="utf-8") + b"\n"
        staged = self.ui.output / ("board-" + board.tag + ".xml")
        staged.write_bytes(payload)
        remote = "/sdcard/Download/logisim-fpga-board-" + board.tag + ".xml"
        canonical = remote.replace("/sdcard/", "/storage/emulated/0/", 1)
        receipt = {
            "fixture": board_file.name, "fixtureSha256": hashlib.sha256(board_file.read_bytes()).hexdigest(),
            "transport": "native-open-document", "remote": remote, "canonicalRemote": canonical,
            "stagedXmlSha256": hashlib.sha256(payload).hexdigest(), "stagedXmlBytes": len(payload),
            "boardIdentity": board.tag, "change": "The public board name includes a per-case nonce; electrical/device/pin data are preserved.",
            "status": "STAGED",
        }
        receipt_path = self.ui.output / ("board-" + board.tag + ".json")
        try:
            self.ui.adb("push", str(staged), remote)
            uploaded = self.ui.adb("exec-out", "cat", remote, text=False)
            receipt["uploadedSha256"] = hashlib.sha256(uploaded).hexdigest()
            assert uploaded == payload, "The native board source differs from its complete staged XML"
            receipt["publicationBroadcast"] = self.ui.adb("shell", "am", "broadcast", "-a",
                "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + canonical)
            deadline = time.monotonic() + 30
            receipt["published"] = False
            while time.monotonic() < deadline:
                command = shlex.join(["content", "query", "--uri", "content://media/external/file",
                    "--projection", "_id:_display_name:mime_type:is_download:is_pending", "--where", "_data='" + canonical + "'"])
                metadata = self.ui.adb("shell", command, timeout=max(0.1, deadline - time.monotonic()))
                receipt["mediaStore"] = metadata
                assert time.monotonic() < deadline, "The board publication deadline expired before its metadata arrived"
                if (re.search(r"\bis_download=1\b", metadata) and re.search(r"\bis_pending=0\b", metadata)
                        and re.search(r"\bmime_type=(?:application|text)/xml\b", metadata)):
                    receipt["published"] = True
                    break
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
            assert receipt["published"], "The complete board XML is not published in Downloads"
            self.e.click("fpga_import_board_file", scroll=True)
            self.s.choose_downloads()
            self.s.choose_native_file(remote.rsplit("/", 1)[1])
            # FpgaBoardCodec maps XML OPENFPGA to the OPEN_FPGA enum shown by FpgaPanel.
            identity = "OPEN_FPGA · ice40 · hx1k · tq144"
            def imported(root):
                scope, tag = self.e.scope(root)
                if tag != "editor-tool-sheet":
                    return None
                names = [item for item in self.visible_text(root, {board.tag}) if item in scope.iter("node")]
                devices = [item for item in self.visible_text(root, {identity}) if item in scope.iter("node")]
                assert len(names) <= 1 and len(devices) <= 1, "Ambiguous imported FPGA board identity"
                return root if names and devices else None
            self.e.wait(imported, "the imported FPGA board identity and device", timeout=30)
            original = self.ui.adb("exec-out", "cat", remote, text=False)
            receipt["sourceAfterSha256"] = hashlib.sha256(original).hexdigest()
            assert original == payload, "Import changed the native board source"
            self.ui.capture("fpga-board-imported-" + board.tag)
            receipt["status"] = "PASS"
        except Exception as error:
            receipt.update(status="FAIL", error=str(error))
            raise
        finally:
            receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
        return before

    def row(self, port, direction, predicate):
        titles = {port + " · " + value for value in self.e.values("fpga_direction_" + direction)}
        deadline = time.monotonic() + 30
        for reverse in (False, True):
            previous = None
            for _ in range(9):
                assert time.monotonic() < deadline, f"Timed out revealing the {port} mapping row"
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                try:
                    scope = signal_row(root, self.ui.package, titles, self.e.values("fpga_connection"))
                    value = predicate(root, scope)
                    if value is not None:
                        return value
                except AssertionError as error:
                    if "found 0" not in str(error):
                        raise
                signature = tuple((node.get("text"), node.get("bounds")) for node in root.iter("node"))
                if signature == previous:
                    break
                previous = signature
                self.e.swipe(self.e.scroll_region(root, False), reverse=reverse)
        raise AssertionError(f"Could not expose the complete FPGA signal row for {port}")

    def map_pin(self, port, direction, pin):
        def connection(root, scope):
            values = self.e.values("fpga_choice_label", label=self.s.catalog["fpga_connection"],
                                   selection=self.s.catalog["fpga_unmapped"])
            found = [node for node in scope.iter("node") if node.get("text") in values]
            assert len(found) == 1, f"Expected one unmapped Connection in {port}"
            action = self.e.action_node(root, found[0])
            return action if self.ui.visible_node(root, action) else None
        self.ui.tap(self.row(port, direction, connection))
        self.global_click(self.e.values("fpga_physical_pin"))
        self.edit_pin(port, direction, pin)

    def edit_pin(self, port, direction, pin):
        def field(root, scope):
            found = [node for node in scope.iter("node") if node.get("class", "").endswith("EditText")]
            assert len(found) <= 1, f"Ambiguous physical pin editor in {port}"
            return found[0] if found and self.ui.visible_node(root, found[0]) else None
        self.ui.input_text(self.row(port, direction, field), pin)
        self.e.hide_keyboard()

    def validate(self, *, valid):
        self.e.click("fpga_validate_mapping", scroll=True)
        if valid:
            self.e.reveal("fpga_mapping_valid")
        for key in ("fpga_export_project", "fpga_prepare_synthesis"):
            node = self.e.reveal(key)
            assert (node.get("enabled") == "true") == valid, f"{key} readiness differs from mapping validity"
        self.ui.capture("fpga-mapping-" + ("valid" if valid else "rejected"))

    def export_project(self):
        filename = "logisim-fpga-native-" + uuid.uuid4().hex + ".zip"
        self.e.click("fpga_export_project", scroll=True)
        self.s.choose_downloads()
        root = self.ui.screen()
        fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"
                  and node.get("package") in ("com.google.android.documentsui", "com.android.documentsui")]
        assert len(fields) == 1, "CreateDocument must expose one native filename field"
        self.ui.input_text(fields[0], filename)
        self.e.hide_keyboard()
        root = self.ui.screen()
        self.ui.tap(self.ui.find(root, resource_id="android:id/button1"))
        remote = "/sdcard/Download/" + filename
        deadline = time.monotonic() + 15
        last = None
        while time.monotonic() < deadline:
            result = self.ui._run(("exec-out", "cat", remote), text=False,
                                  timeout=max(0.1, min(5, deadline - time.monotonic())), check=False)
            content = result.stdout
            (self.ui.output / filename).write_bytes(content)
            if result.returncode == 0:
                try:
                    files = assert_project_archive(content)
                    self.e.reveal("fpga_mapping_valid")
                    self.ui.capture("fpga-project-exported")
                    return {"path": filename, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "files": files}
                except (AssertionError, ValueError, zipfile.BadZipFile) as error:
                    last = str(error)
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))
        raise AssertionError("The app did not export its complete FPGA ZIP: " + str(last))

    def mapping(self, *, invalid_pin=False, export=False):
        jobs_before = len(self.host.audit()["jobs"])
        board = self.fixtures / ("fpga-hx1k-invalid-pin.xml" if invalid_pin else "fpga-hx1k.xml")
        before = self.load(board)
        self.validate(valid=False)
        self.diagnostic("unmapped-port")
        self.map_pin("p_a", "input", "21")
        self.map_pin("p_b", "input", "21")
        self.map_pin("p_y", "output", "999" if invalid_pin else "23")
        self.validate(valid=False)
        self.diagnostic("pin-already-used")
        self.edit_pin("p_b", "input", "22")
        self.validate(valid=True)
        assert len(self.host.audit()["jobs"]) == jobs_before, "Mapping or validation implicitly submitted an external job"
        archive = self.export_project() if export else None
        self.e.click("fpga_prepare_synthesis", scroll=True)
        return before, archive

    def execute(self, goal, *, cancel=False):
        def goal_button(root):
            values = self.e.values("fpga_companion_goal_" + goal) | self.e.values(
                "fpga_companion_selected_goal", goal=self.s.catalog["fpga_companion_goal_" + goal])
            found = self.visible_text(root, values)
            assert len(found) <= 1, "Ambiguous FPGA build goal"
            return self.e.action_node(root, found[0]) if found else None
        self.ui.tap(self.seek(goal_button, "FPGA build goal"))
        self.e.click("fpga_companion_prepare", scroll=True)
        self.await_reveal(lambda root: self.e.nodes(root, "fpga_companion_review") or None, "reviewed FPGA plan")
        root = self.ui.screen()
        parameters = reviewed_parameters(root, self.ui.package)
        assert_review(parameters, goal)
        self.ui.capture("fpga-reviewed-" + goal, root)
        previous_jobs = {job["jobId"] for job in self.host.audit()["jobs"]}
        self.e.click("fpga_companion_execute", scroll=True)
        def new_job(root):
            try:
                observed = job_identifier(root, self.ui.package, self.s.catalog["fpga_companion_job"].values())
            except AssertionError:
                return None
            return root if observed not in previous_jobs else None
        root = self.await_reveal(new_job, "new companion job ID")
        job = job_identifier(root, self.ui.package, self.s.catalog["fpga_companion_job"].values())
        if cancel:
            states = {name: self.e.values("fpga_companion_job_status", state=self.s.catalog["fpga_companion_state_" + name])
                      for name in ("queued", "running")}
            observed = [name for name, values in states.items() if self.visible_text(root, values)]
            assert len(observed) == 1, "Cancellation requires one observed native queued/running state"
            self.cancel_observed_state = observed[0]
            controls = self.e.nodes(root, "fpga_companion_cancel")
            assert len(controls) == 1, "Cancellation lost the race to a terminal job; the cancel case is not qualified"
            (self.ui.output / "fpga-before-cancel-ui.xml").write_bytes(
                ET.tostring(root, encoding="utf-8", xml_declaration=True))
            self.ui.tap(controls[0])
        return job, parameters

    def terminal(self, state):
        expected = self.e.values("fpga_companion_job_status", state=self.s.catalog["fpga_companion_state_" + state])
        states = {name: self.e.values("fpga_companion_job_status", state=self.s.catalog["fpga_companion_state_" + name])
                  for name in ("queued", "running", "succeeded", "failed", "cancelled")}
        all_states = set().union(*states.values())
        def status(root):
            found = self.visible_text(root, all_states)
            assert len(found) <= 1, "Ambiguous terminal FPGA job status"
            return found[0] if found else None
        deadline = time.monotonic() + 60
        self.seek(status, "FPGA job status", timeout=min(30, deadline - time.monotonic()))
        while time.monotonic() < deadline:
            root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
            node = status(root)
            if node is not None:
                if node.get("text") in expected:
                    return node
                unexpected = set().union(*(states[name] for name in ("succeeded", "failed", "cancelled") if name != state))
                assert node.get("text") not in unexpected, f"FPGA job reached {node.get('text')} while expecting {state}"
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))
        raise AssertionError(f"The FPGA job did not reach {state} within the fixed 60 second deadline")

    def preserved(self, before, name):
        self.close_tool()
        after, _ = self.e.export("fpga-after-" + name)
        assert after == before, "FPGA mapping or external synthesis changed the circuit document"

    def success(self):
        before, archive = self.mapping(export=True)
        job, parameters = self.execute("synthesis")
        self.terminal("succeeded")
        self.e.reveal("fpga_companion_log_entry", stream="stage", detail="synthesize")
        self.e.reveal("fpga_companion_outcome_flag", label=self.s.catalog["fpga_companion_synthesized"], value=self.s.catalog["fpga_companion_yes"])
        for key in ("placed_and_routed", "bitstream_generated", "programmed", "hardware_verified"):
            self.e.reveal("fpga_companion_outcome_flag", label=self.s.catalog["fpga_companion_" + key], value=self.s.catalog["fpga_companion_no"])
        receipt = next(item for item in self.host.audit()["jobs"] if item["jobId"] == job)
        assert receipt["state"] == "succeeded"
        for artifact in receipt["result"]["artifacts"]:
            self.e.reveal("fpga_companion_artifact_digest", name=artifact["name"], size=artifact["size"], sha256=artifact["sha256"])
        self.ui.capture("fpga-synthesis-success")
        exported = self.host.export_artifacts(self.ui.output / "native-synthesis-artifacts")
        netlist_path = next(self.ui.output / "native-synthesis-artifacts" / item["path"]
                            for item in exported if item["jobId"] == job and item["name"] == "synthesized.json")
        netlist = json.loads(netlist_path.read_text())
        assert set(netlist["modules"]["logisim_fpga_top"]["ports"]) == {"fpga_p0_b0", "fpga_p1_b0", "fpga_p2_b0"}
        self.observations.append({"case": "synthesis", "jobId": job, "reviewedParameters": parameters,
                                  "projectArchive": archive})
        self.preserved(before, "synthesis")

    def tool_error(self):
        before, _ = self.mapping(invalid_pin=True)
        job, parameters = self.execute("bitstream")
        self.terminal("failed")
        root = self.diagnostic("fpga-stage-failed")
        self.ui.capture("fpga-placement-error", root)
        self.observations.append({"case": "tool-error", "jobId": job, "reviewedParameters": parameters})
        self.preserved(before, "tool-error")

    def cancel(self):
        before, _ = self.mapping()
        job, parameters = self.execute("bitstream", cancel=True)
        self.terminal("cancelled")
        self.ui.capture("fpga-cancelled")
        self.observations.append({"case": "cancel", "jobId": job, "reviewedParameters": parameters,
                                  "observedNonterminalState": self.cancel_observed_state})
        self.preserved(before, "cancel")


def run_fpga(scenarios, host, fixtures, selected):
    assert scenarios.ui.package.endswith(".e2e"), "FPGA mutation requires the isolated E2E app"
    flow = FpgaScenarios(scenarios, host, fixtures)
    flow.pair()
    actions = {"synthesis": flow.success, "tool-error": flow.tool_error, "cancel": flow.cancel}
    for name in selected:
        scenarios.run("fpga-" + name, actions[name])
    return flow.observations
