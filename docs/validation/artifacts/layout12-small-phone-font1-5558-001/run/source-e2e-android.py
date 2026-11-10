#!/usr/bin/env python3
"""Run deterministic Android UI scenarios in the isolated .e2e application."""
import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

from android_ui import AndroidUi
from android_editing_scenarios import run_editing
from user_scenario_contract import load_contract, assert_sap1 as verify_sap1

ROOT = Path(__file__).resolve().parents[1]
PROJECT_TOOLBARS = ("editor-project-toolbar", "editor-command-toolbar")
SIMULATION_TOOLBARS = ("editor-simulation-toolbar", "editor-command-toolbar")
COMMAND_TOOLBARS = ("editor-command-toolbar",)
PROJECT_ACTIONS = {"a11y_project_files", "a11y_import_export_project", "a11y_project_tools",
                   "action_undo", "action_redo", "a11y_open_help"}
SIMULATION_ACTIONS = {"a11y_step_clock", "a11y_reset_simulation", "a11y_change_frequency", "a11y_simulation_event_step"}
COMMAND_ACTIONS = {"a11y_open_library", "a11y_fit_circuit", "a11y_fit_selection", "a11y_open_properties",
                   "a11y_select_all", "a11y_copy_selection", "a11y_cut_selection", "a11y_duplicate_selection",
                   "a11y_delete_selection", "a11y_paste_components"}


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def owned_task_ids(raw, package):
    pattern = re.compile(r"\bTask\{[^\n]*? #([0-9]+) [^\n]*?\bA=[0-9]+:" + re.escape(package) + r"(?:\s|})")
    return set(pattern.findall(raw))


def await_reset_tasks_removed(ui, previous, *, timeout=15):
    deadline = time.monotonic() + timeout
    observed = []
    while previous:
        current = owned_task_ids(ui.adb("shell", "dumpsys", "activity", "activities"), ui.package)
        remaining = previous & current
        observed.append({"previousTaskIdsStillPresent": sorted(remaining)})
        if not remaining:
            return observed
        assert time.monotonic() < deadline, "The isolated app's pre-reset tasks have not finished removal"
        time.sleep(0.1)
    return observed


def artifact_paths(override=None):
    if override is not None:
        directory = Path(override)
        return (directory / "androidApp-e2e.apk", directory / "androidApp-e2e-androidTest.apk",
                directory / "androidSnapshot-debug.apk")
    return (ROOT / "androidApp/build/outputs/apk/e2e/androidApp-e2e.apk",
            ROOT / "androidApp/build/outputs/apk/androidTest/e2e/androidApp-e2e-androidTest.apk",
            ROOT / "androidSnapshot/build/outputs/apk/debug/androidSnapshot-debug.apk")


def expected_e2e_package():
    properties = dict(line.split("=", 1) for line in (ROOT / "app.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    base = properties.get("app.androidId", "").strip()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", base):
        raise ValueError("app.properties must declare a valid app.androidId")
    return base + ".e2e"


def find_aapt2(sdk, override=None):
    if override is not None:
        candidate = Path(override).expanduser()
        if not candidate.is_file() or not os.access(candidate, os.X_OK):
            raise ValueError("--aapt2 must name an executable local aapt2")
        return candidate
    candidates = []
    for candidate in (Path(sdk) / "build-tools").glob("*/aapt2"):
        version = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-.+)?", candidate.parent.name)
        if version and candidate.is_file() and os.access(candidate, os.X_OK):
            candidates.append(((*map(int, version.groups()), "-" not in candidate.parent.name), candidate))
    if not candidates:
        raise ValueError("No SDK build-tools aapt2 found; provide --aapt2")
    return max(candidates)[1]


def parse_apk_manifest(text):
    stack, instrumentations = [], []
    package = None
    root_seen = False
    for line in text.splitlines():
        element = re.match(r"^(\s*)E: ([^\s(]+)", line)
        if element:
            indent, name = len(element[1]), element[2]
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if not stack:
                if root_seen or name != "manifest":
                    raise ValueError("Expected one compiled manifest root")
                root_seen = True
            elif name == "manifest":
                raise ValueError("Unexpected nested manifest")
            data = None
            if name == "instrumentation":
                if len(stack) != 1 or stack[-1][1] != "manifest":
                    raise ValueError("Instrumentation must be a direct manifest child")
                data = {}
                instrumentations.append(data)
            stack.append((indent, name, data))
            continue
        attribute = re.match(r"^\s*A: ([^\s=(]+)(?:\([^)]*\))?\s*=\s*(.*)$", line)
        if attribute is None or not stack:
            continue
        name, expression = attribute.groups()
        element = stack[-1][1]
        key = "package" if element == "manifest" and name == "package" else None
        if element == "instrumentation":
            for field in ("name", "targetPackage"):
                if name in ("android:" + field, "http://schemas.android.com/apk/res/android:" + field):
                    key = field
        if key is None:
            continue
        value = re.match(r'^"([^\"]+)"(?:\s|$)', expression)
        if value is None:
            raise ValueError(f"APK identity must be a literal string: {key}")
        if key == "package":
            if package is not None:
                raise ValueError("Duplicate manifest package")
            package = value[1]
        else:
            if key in stack[-1][2]:
                raise ValueError(f"Duplicate instrumentation {key}")
            stack[-1][2][key] = value[1]
    if not root_seen or package is None:
        raise ValueError("Compiled manifest has no package")
    if any(set(item) != {"name", "targetPackage"} for item in instrumentations):
        raise ValueError("Instrumentation must declare its name and targetPackage")
    if len({item["name"] for item in instrumentations}) != len(instrumentations):
        raise ValueError("Duplicate instrumentation declaration")
    return {"package": package, "instrumentations": instrumentations}


def validate_apk_manifests(aapt2, app, tests, snapshot, package):
    helper = package.removesuffix(".e2e") + ".snapshot.e2e.test"
    identities = {}
    for role, path, expected_package, target in (("app", app, package, package),
            ("test", tests, package + ".test", package), ("snapshot", snapshot, helper, helper)):
        if not path.is_file():
            raise ValueError(f"Missing {role} APK: {path}")
        text = subprocess.check_output([str(aapt2), "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"],
                                       text=True, stderr=subprocess.STDOUT, timeout=15)
        identity = parse_apk_manifest(text)
        if identity["package"] != expected_package:
            raise ValueError(f"The {role} APK package must be {expected_package}; found {identity['package']}")
        if role != "app" and not identity["instrumentations"]:
            raise ValueError(f"The {role} APK has no instrumentation target")
        if any(item["targetPackage"] != target for item in identity["instrumentations"]):
            raise ValueError(f"The {role} APK instrumentation must target {target}")
        identities[role] = identity
    return identities


def load_build_receipt(path, app_sha):
    payload = path.read_bytes()
    receipt = json.loads(payload)
    if not isinstance(receipt, dict) or not re.fullmatch(r"[0-9a-f]{64}", str(receipt.get("appSha256", ""))) or \
            receipt["appSha256"] != app_sha:
        raise ValueError("The build receipt belongs to a different editor APK")
    if not isinstance(receipt.get("gitHead"), str) or not re.fullmatch(r"[0-9a-f]{40}", receipt["gitHead"]):
        raise ValueError("The build receipt must contain a complete gitHead")
    return receipt, payload


def fixture_document_name(package, uri):
    match = re.fullmatch(re.escape(f"content://{package}.test.documents/document/") +
                         r"((?:writable|e2e-[a-f0-9]{32})\.circ)", uri)
    if match is None:
        raise AssertionError("Only an owned circuit fixture URI may launch an incoming document")
    return match.group(1)


def mark_import_source(content, token):
    root = ET.fromstring(content)
    assert root.tag == "project", "Only complete circuit projects can be marked for import"
    root.set("e2e-import-token", token)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


class Scenarios:
    def __init__(self, ui):
        self.ui = ui
        self.results = []
        self.import_report_count = 0
        self.unready_simulation_count = 0
        self.incoming_launch_count = 0
        self.contract = load_contract(ROOT / "fixtures/user-scenarios/editor-actions-v1.json")
        self.catalog = {}
        for path in sorted((ROOT / "localization/messages").glob("*.json")):
            self.catalog.update(json.loads(path.read_text()))

    def wait_key(self, key, *, timeout=30, **arguments):
        if key in PROJECT_ACTIONS and arguments.get("description"):
            return self.project_action(key, timeout=timeout)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                # A native parse error may appear above the restored project's
                # report. Waiting for that error must never dismiss any window.
                if key != "action_understood" and self.acknowledge_import_report(
                        root, timeout=max(0.1, deadline - time.monotonic())):
                    continue
                return self.find_key(key, root=root, **arguments)
            except AssertionError:
                time.sleep(0.2)
        raise AssertionError(f"The {key} control did not appear within {timeout}s")

    def wait_editor(self, *, timeout=30):
        """Observe editor readiness without opening an overflow as a side effect."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
            if self.acknowledge_import_report(root, timeout=max(0.1, deadline - time.monotonic())):
                continue
            if any(self.ui.tagged(root, toolbar + "-menu") is not None
                   for toolbar in set(PROJECT_TOOLBARS + SIMULATION_TOOLBARS)):
                raise AssertionError("A toolbar menu is still open while waiting for the editor")
            try:
                canvas = self.canvas(root)
            except AssertionError:
                time.sleep(0.2)
                continue
            if self.ui.visible_node(root, canvas):
                return root
            time.sleep(0.2)
        raise AssertionError(f"The editor canvas did not become ready within {timeout}s")

    def key_values(self, key, **arguments):
        return {template.format(**{name: value.get(language, value.get("en")) if isinstance(value, dict) else value
                                   for name, value in arguments.items()})
                for language, template in self.catalog[key].items()}

    def toolbar_action(self, key, toolbars, *, root=None, timeout=30, description=True, **arguments):
        values = self.key_values(key, **arguments)
        attribute = "content-desc" if description else "text"
        return self.ui.toolbar_action(toolbars, matches=lambda node: node.get(attribute) in values,
                                      root=root, timeout=timeout)

    def project_action(self, key, *, timeout=30):
        deadline = time.monotonic() + timeout
        for reverse in (False, True):
            for _ in range(8):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError(f"The {key} project toolbar action did not appear within {timeout}s")
                root = self.ui.screen(timeout=remaining)
                if self.acknowledge_import_report(root, timeout=max(0.1, deadline - time.monotonic())):
                    continue
                modern = self.toolbar_action(key, PROJECT_TOOLBARS, root=root, timeout=deadline - time.monotonic())
                if modern is not None:
                    return modern
                try:
                    item = self.find_key(key, root=root, description=True)
                    assert self.ui.visible_node(root, item), "The project toolbar control is clipped"
                    return item
                except AssertionError as error:
                    if not any(reason in str(error) for reason in ("found 0", "outside the visible", "clipped")):
                        raise
                bar = self.ui.optional(root, resource_id="editor-project-toolbar")
                if bar is None:
                    bar = self.ui.optional(root, resource_id="editor-command-toolbar")
                if bar is None:
                    time.sleep(0.2)
                    continue
                left, top, right, bottom = self.ui._bounds(bar)
                inner_left = round(left + (right - left) * 0.15)
                inner_right = round(left + (right - left) * 0.85)
                start, end = (inner_right, inner_left) if not reverse else (inner_left, inner_right)
                self.ui.adb("shell", "input", "swipe", str(start), str((top + bottom) // 2),
                            str(end), str((top + bottom) // 2), "250")
        raise AssertionError(f"The {key} project toolbar action could not be reached")

    def is_import_report(self, root):
        titles = self.catalog["import_report_title"].values()
        texts = [node.get("text") for node in root.iter("node") if node.get("text")]
        summaries = [re.escape(value).replace(re.escape("{version}"), ".+")
                     .replace(re.escape("{count}"), r"\d+")
                     for value in self.catalog["import_report_source"].values()]
        error_controls = set(self.catalog["action_understood"].values()) | set(self.catalog["action_failed_title"].values())
        if any(text in error_controls for text in texts):
            return False
        return bool(texts and texts[0] in titles and any(
            re.fullmatch(pattern, text) for pattern in summaries for text in texts)
            and self.ui.optional(root, resource_id="editor-canvas") is None)

    def acknowledge_import_report(self, root, *, timeout=30):
        if not self.is_import_report(root):
            return False
        self.import_report_count += 1
        self.ui.capture(f"import-report-{self.import_report_count}", root)
        current = self.ui.screen(timeout=timeout)
        if not self.is_import_report(current):
            return False
        self.ui.tap(self.find_key("action_close", root=current))
        return True

    def find_key(self, key, *, root=None, description=False, prefix=False, **arguments):
        root = self.ui.screen() if root is None else root
        values = self.key_values(key, **arguments)
        attribute = "content-desc" if description else "text"
        found = [node for node in root.iter("node") if any(
            node.get(attribute, "").startswith(value) if prefix else node.get(attribute) == value
            for value in values)]
        if len(found) != 1:
            raise AssertionError(f"Expected one visible {key} control, found {len(found)}")
        return found[0]

    def reveal_key(self, key, *, description=False, **arguments):
        for reverse in (False, True):
            previous = None
            for _ in range(8):
                root = self.ui.screen()
                try:
                    return self.find_key(key, root=root, description=description, **arguments)
                except AssertionError as error:
                    if "found 0" not in str(error):
                        raise
                visible = ET.tostring(root)
                if visible == previous:
                    break
                previous = visible
                regions = [self.ui._bounds(node) for node in root.iter("node")
                           if node.get("scrollable") == "true" and not node.get("class", "").endswith("EditText")]
                regions = [rect for rect in regions if rect[3] - rect[1] > 100 and rect[2] - rect[0] > 100]
                if not regions:
                    break
                left, top, right, bottom = max(regions, key=lambda rect: (rect[2] - rect[0]) * (rect[3] - rect[1]))
                start, end = (bottom - 40, top + 40) if not reverse else (top + 40, bottom - 40)
                self.ui.adb("shell", "input", "swipe", str((left + right) // 2), str(start),
                            str((left + right) // 2), str(end), "300")
        raise AssertionError(f"The {key} action could not be reached by scrolling")

    def click_key(self, key, *, description=False, reveal=False, **arguments):
        if description and key in PROJECT_ACTIONS:
            node = self.project_action(key)
        elif description and key in SIMULATION_ACTIONS:
            node = self.simulation_action(key)
        elif description and key in COMMAND_ACTIONS:
            node = self.command_action(key, **arguments)
        else:
            node = self.reveal_key(key, description=description, **arguments) if reveal else \
                self.find_key(key, description=description, **arguments)
        self.ui.tap(node)

    def command_action(self, key, **arguments):
        deadline = time.monotonic() + 30
        root = self.ui.screen(timeout=30)
        modern = self.toolbar_action(key, COMMAND_TOOLBARS, root=root,
                                     timeout=deadline - time.monotonic(), **arguments)
        if modern is not None:
            return modern
        # Earlier artifacts used one horizontally scrolling command row.
        for reverse in (False, True):
            previous = None
            for _ in range(8):
                if time.monotonic() >= deadline:
                    raise AssertionError(f"The {key} command toolbar action did not appear within 30s")
                try:
                    node = self.find_key(key, root=root, description=True, **arguments)
                    if self.ui.visible_node(root, node):
                        return node
                except AssertionError as error:
                    if "found 0" not in str(error):
                        raise
                current = ET.tostring(root)
                if current == previous:
                    break
                previous = current
                bar = self.ui.optional(root, resource_id="editor-command-toolbar")
                if bar is None:
                    break
                left, top, right, bottom = self.ui._bounds(bar)
                start, end = (right - 20, left + 20) if not reverse else (left + 20, right - 20)
                self.ui.adb("shell", "input", "swipe", str(start), str((top + bottom) // 2),
                            str(end), str((top + bottom) // 2), "250")
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
        raise AssertionError(f"The {key} command toolbar action could not be reached")

    def scroll_simulation_row(self, root, reverse=False):
        if any(self.ui.tagged(root, toolbar + suffix) is not None
               for toolbar in SIMULATION_TOOLBARS for suffix in ("-overflow", "-menu")):
            # Menus own their scroll viewport. A missing counter/anchor requires
            # a fresh hierarchy, never a gesture through the active window.
            self.unready_simulation_count += 1
            self.ui.capture(f"simulation-toolbar-not-ready-{self.unready_simulation_count}", root)
            return False
        anchor = None
        for key in ("a11y_step_clock", "a11y_reset_simulation", "a11y_change_frequency", "a11y_simulation_event_step"):
            try:
                anchor = self.find_key(key, root=root, description=True)
                break
            except AssertionError:
                continue
        if anchor is None:
            self.unready_simulation_count += 1
            self.ui.capture(f"simulation-toolbar-not-ready-{self.unready_simulation_count}", root)
            return False
        left, _, right, _ = self.ui._bounds(next(root.iter("node")))
        _, top, _, bottom = self.ui._bounds(anchor)
        inner_left, inner_right = round(left + (right - left) * 0.15), round(left + (right - left) * 0.85)
        start, end = (inner_left, inner_right) if reverse else (inner_right, inner_left)
        self.ui.adb("shell", "input", "swipe", str(start), str((top + bottom) // 2),
                    str(end), str((top + bottom) // 2), "250")
        return True

    def simulation_action(self, key):
        deadline = time.monotonic() + 30
        for reverse in (False, True):
            previous = None
            scrolls = 0
            while scrolls < 4:
                if time.monotonic() >= deadline:
                    raise AssertionError(f"The {key} simulation action did not become ready within 30s")
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                modern = self.toolbar_action(key, SIMULATION_TOOLBARS, root=root,
                                             timeout=deadline - time.monotonic())
                if modern is not None:
                    return modern
                try:
                    node = self.find_key(key, root=root, description=True)
                    if self.ui.visible_node(root, node):
                        return node
                except AssertionError as error:
                    if "found 0" not in str(error):
                        raise
                if not self.scroll_simulation_row(root, reverse):
                    time.sleep(0.2)
                    continue
                scrolls += 1
                visible = ET.tostring(root)
                if visible == previous:
                    break
                previous = visible
        raise AssertionError(f"The {key} simulation action could not be reached")

    def library_click(self, key, **arguments):
        try:
            target = self.find_key(key, description=True, **arguments)
        except AssertionError:
            self.click_key("a11y_open_library", description=True)
            target = self.find_key(key, description=True, **arguments)
        self.ui.tap(target)

    def export_xml(self, name="document"):
        self.ui.adb("shell", "am", "start", "-W", "-n",
                    f"{self.ui.package}.test/dev.logisim.mobile.documents.DocumentClipboardReaderActivity",
                    "--es", "prepareCopy", uuid.uuid4().hex)
        self.ui.click(text="Prepare XML copy")
        self.wait_editor()
        self.click_key("a11y_import_export_project", description=True)
        self.ui.adb("shell", "run-as", self.ui.package + ".test", "rm", "-f", "files/clipboard-project.circ")
        self.click_key("action_copy", reveal=True)
        self.click_key("action_close")
        self.ui.adb("shell", "am", "start", "-W", "-n",
                    f"{self.ui.package}.test/dev.logisim.mobile.documents.DocumentClipboardReaderActivity")
        self.ui.click(text="Read copied XML")
        self.wait_editor()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            read = subprocess.run([self.ui.adb_path, "-s", self.ui.serial, "exec-out", "run-as",
                                   self.ui.package + ".test", "cat", "files/clipboard-project.circ"],
                                  capture_output=True, timeout=10)
            try:
                candidate = read.stdout.decode("utf-8")
                assert ET.fromstring(candidate).tag == "project"
                xml = candidate
                break
            except (UnicodeDecodeError, ET.ParseError, AssertionError):
                pass
            time.sleep(0.2)
        else:
            raise AssertionError("The foreground test receiver did not read the copied project XML")
        project = ET.fromstring(xml)
        (self.ui.output / f"{name}.circ").write_text(xml)
        (self.ui.output / "last-document.circ").write_text(xml)
        return xml, project

    def canvas(self, root=None):
        root = self.ui.screen() if root is None else root
        prefixes = [value.split("{outputs}", 1)[0].strip() for value in self.catalog["a11y_canvas"].values()]
        nodes = [node for node in root.iter("node") if any(node.get("content-desc", "").startswith(value) for value in prefixes)]
        assert len(nodes) == 1, "The canvas must have one accessible description"
        return nodes[0]

    def action_with_icon(self, key):
        deadline = time.monotonic() + 30
        values = set(self.catalog[key].values())
        root = self.ui.screen(timeout=30)
        matches = lambda node: node.get("text", "").lstrip("▶Ⅱ⏸ ").strip() in values
        modern = self.ui.toolbar_action(SIMULATION_TOOLBARS, matches=matches, root=root,
                                        timeout=deadline - time.monotonic())
        nodes = [modern] if modern is not None else [node for node in root.iter("node")
                 if matches(node) and self.ui.visible_node(root, node)]
        assert len(nodes) == 1, f"Expected one {key} action, found {len(nodes)}"
        self.ui.tap(nodes[0])
        return nodes[0]

    def simulation_steps(self, root=None):
        supplied = root is not None
        root = self.ui.screen() if root is None else root
        patterns = [re.escape(value).replace(re.escape("{count}"), r"(\d+)") + r"(?: · .*)?"
                    for value in self.catalog["simulation_steps"].values()]
        def counters(current):
            # Whole-toolbar layouts keep the counter in their leading slot;
            # opening an overflow menu must never turn a menu caption into it.
            scope = self.ui.tagged(current, "editor-simulation-toolbar")
            scope = current if scope is None else scope
            return [int(match.group(1)) for node in scope.iter("node") for pattern in patterns
                    if self.ui.visible_node(current, node)
                    and (match := re.fullmatch(pattern, node.get("text", "")))]
        values = counters(root)
        if not values and not supplied:
            for _ in range(4):
                self.scroll_simulation_row(root)
                root = self.ui.screen()
                values = counters(root)
                if values:
                    break
        assert len(set(values)) == 1, f"Expected one simulation counter, got {values}"
        return values[0]

    def wait_simulation_steps(self, expected, timeout=30):
        started = time.monotonic()
        deadline = started + timeout
        record = {"expected": expected, "timeoutSeconds": timeout, "observations": [], "status": "failed"}
        try:
            while time.monotonic() < deadline:
                try:
                    root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                    observed = self.simulation_steps(root)
                except AssertionError as error:
                    record["observations"].append({"error": str(error)})
                else:
                    record["observations"].append({"count": observed})
                    if expected > 0 and observed > expected:
                        raise AssertionError(f"Simulation advanced past {expected} steps: observed {observed}")
                    if observed == expected:
                        record["status"] = "passed"
                        return observed
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
            raise AssertionError(f"Simulation did not reach {expected} steps within {timeout}s: {record['observations']}")
        finally:
            (self.ui.output / f"simulation-steps-{expected}.json").write_text(json.dumps(record, indent=2) + "\n")

    def sequential(self):
        self.library_click("a11y_load_sap1")
        self.wait_key("a11y_toggle_input", description=True, name="Reset", value="0")
        self.click_key("a11y_reset_simulation", description=True)
        self.click_key("a11y_toggle_input", description=True, name="Reset", value="0")
        self.click_key("a11y_toggle_input", description=True, name="Reset", value="1")
        self.wait_simulation_steps(0)
        for count in range(1, 57):
            self.click_key("a11y_step_clock", description=True)
            if count % 8 == 0:
                self.wait_simulation_steps(count)
                print(f"SAP-1 manual half-cycles: {count}/56", flush=True)
        self.wait_simulation_steps(56)
        description = self.canvas().get("content-desc", "")
        digits = re.findall(r"(?:Saída|Output): ([01?E]{4})(?=,|\.)", description)[-2:]
        assert digits == ["0000", "1000"], f"Expected the visible SAP-1 displays to show 08, got {digits}"
        self.ui.capture("sap1-at-half-cycle-56")
        self.action_with_icon("action_run")
        time.sleep(1.5)
        # Run can live in a menu which closes on activation. Locate the current
        # Pause action again rather than reusing a node from the previous window.
        self.action_with_icon("action_pause")
        paused = self.simulation_steps()
        assert paused > 56, "Run did not advance the sequential simulation"
        time.sleep(self.contract["sap1"]["pauseObservationMilliseconds"] / 1000)
        assert self.simulation_steps() == paused, "The clock continued after Pause"
        paused_digits = re.findall(r"(?:Saída|Output): ([01?E]{4})(?=,|\.)", self.canvas().get("content-desc", ""))[-2:]
        assert len(paused_digits) == 2, "The paused SAP-1 display values were not exposed"
        xml, project = self.export_xml("sequential-sap1")
        self.assert_sap1(project)
        resumed_digits = re.findall(r"(?:Saída|Output): ([01?E]{4})(?=,|\.)", self.canvas().get("content-desc", ""))[-2:]
        assert resumed_digits == paused_digits, "The XML copy changed the paused sequential state"
        self.ui.adb("shell", "am", "force-stop", self.ui.package)
        self.launch()
        restored, _ = self.export_xml("sequential-restored")
        assert restored == xml, "Restart changed the sequential project document"

    def layout(self):
        original = {key: self.ui.adb("shell", "settings", "get", "system", key).strip()
                    for key in ("accelerometer_rotation", "user_rotation", "font_scale")}
        before, _ = self.export_xml("before-layout")
        try:
            self.ui.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            self.ui.adb("shell", "settings", "put", "system", "user_rotation", "1")
            self.ui.adb("shell", "settings", "put", "system", "font_scale", "1.6")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                root = self.ui.screen()
                if root.get("rotation") == "1":
                    break
                time.sleep(0.25)
            else:
                raise AssertionError("Android did not enter landscape")
            self.wait_editor()

            self.canvas()
            root = self.ui.screen()
            library = [node for node in root.iter("node")
                       if node.get("content-desc") in self.catalog["a11y_library"].values()]
            assert len(library) <= 1, "The layout exposed duplicate component libraries"
            if not library:
                self.click_key("a11y_open_library", description=True)
                self.find_key("a11y_library", description=True)
                self.ui.key(4)
            self.click_key("a11y_project_files", description=True)
            self.reveal_key("action_save_as")
            self.ui.capture("landscape-large-font-file-actions")
            self.click_key("action_close")
            after, _ = self.export_xml("after-layout")
            assert before == after, "Rotation or large text changed the document"
        except Exception as error:
            self.ui.fail_artifacts("layout-before-restoration", error)
            raise
        finally:
            for key, value in original.items():
                if value == "null":
                    self.ui.adb("shell", "settings", "delete", "system", key)
                else:
                    self.ui.adb("shell", "settings", "put", "system", key, value)
            self.wait_editor()

    def unicode_labels(self):
        name = "Visão → \u202bاختبار\u202c"
        label = "E2E — ligação longa → entrada / \u202bمرحبا\u202c"
        project = ET.Element("project", {"version": "1.0"})
        ET.SubElement(project, "lib", {"name": "0", "desc": "#Wiring"})
        ET.SubElement(project, "main", {"name": name})
        circuit = ET.SubElement(project, "circuit", {"name": name})
        component = ET.SubElement(circuit, "comp", {"lib": "0", "loc": "(100,100)", "name": "Pin"})
        ET.SubElement(component, "a", {"name": "label", "val": label})
        self.incoming(self.fixture(ET.tostring(project, encoding="utf-8", xml_declaration=True)))
        _, exported = self.export_xml("unicode-labels")
        assert exported.find("circuit").get("name") == name, "The circuit name lost Unicode or directional controls"
        assert exported.find("circuit/comp/a[@name='label']").get("val") == label, "The component label changed"
        button = self.find_key("a11y_toggle_input", description=True, name=label, value="0")
        self.ui._bounds(button)
        self.wait_editor()

    def run(self, name, action):
        started = time.monotonic()
        try:
            action()
            self.ui.capture(name)
            self.results.append({"scenario": name, "status": "passed", "seconds": round(time.monotonic() - started, 2)})
            print(f"PASS: {name}", flush=True)
        except Exception as error:
            self.ui.fail_artifacts(name, error)
            self.results.append({"scenario": name, "status": "failed", "error": str(error)})
            raise
        finally:
            (self.ui.output / "results.json").write_text(json.dumps(self.results, indent=2) + "\n")

    def launch(self):
        launched = self.ui.adb("shell", "am", "start", "-W", "-n", f"{self.ui.package}/dev.logisim.mobile.MainActivity")
        self.launch_count = getattr(self, "launch_count", 0) + 1
        (self.ui.output / f"launch-{self.launch_count:02d}.txt").write_text(launched)
        assert re.search(r"^Status: ok\s*$", launched, re.MULTILINE), "The isolated app did not complete am start successfully"
        self.wait_editor()

    def fixture(self, content):
        test_package = self.ui.package + ".test"
        receiver = f"{test_package}/dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver"
        result = self.ui.adb("shell", "am", "broadcast", "-n", receiver)
        if "result=-1" not in result:
            raise AssertionError("External fixture provider did not initialize")
        name = "e2e-" + uuid.uuid4().hex + ".circ"
        command = f"run-as {shlex.quote(test_package)} sh -c {shlex.quote('cat > files/document-fixtures/' + name)}"
        subprocess.run([self.ui.adb_path, "-s", self.ui.serial, "shell", command], input=content, check=True, timeout=15)
        return f"content://{test_package}.documents/document/{name}"

    def incoming(self, uri, action="android.intent.action.VIEW", expect_error=False):
        name = fixture_document_name(self.ui.package, uri)
        self.click_key("a11y_reset_simulation", description=True)
        launch = "share" if action == "android.intent.action.SEND" else "view"
        launched = self.ui.adb("shell", "am", "start", "-W", "-f", "0x18000000", "-n",
                    f"{self.ui.package}.test/dev.logisim.mobile.documents.DocumentFixtureLauncherActivity",
                    "--es", "action", launch, "--es", "fixtureUri", uri)
        self.incoming_launch_count += 1
        (self.ui.output / f"incoming-launch-{self.incoming_launch_count}.txt").write_text(launched)
        assert f"Activity: {self.ui.package}.test/dev.logisim.mobile.documents.DocumentFixtureLauncherActivity" in launched, \
            "Android did not foreground the external fixture activity"
        self.ui.click(text="Share fixture" if launch == "share" else "Open fixture")
        if expect_error:
            self.wait_key("action_understood")
        else:
            opened = self.wait_key("status_file_opened", name=name)
            (self.ui.output / f"incoming-open-{self.incoming_launch_count}.json").write_text(json.dumps({
                "action": action, "uri": uri, "expectedName": name, "observedStatus": opened.get("text"),
            }, indent=2) + "\n")

    def confirm_native_directory(self):
        root = self.ui.screen()
        native = {"com.android.documentsui", "com.google.android.documentsui"}
        texts = {node.get("text") for node in root.iter("node") if node.get("package") in native}
        assert {"main.circ", "library"}.issubset(texts), "The native picker is not displaying the expected sources folder"
        controls = [node for node in root.iter("node") if node.get("package") in native
                    and node.get("text", "").casefold() == "use this folder" and node.get("enabled") != "false"]
        assert len(controls) == 1, "The sources folder has no unique enabled native confirmation action"
        self.ui.capture("portable-directory-before-grant", root)
        self.ui.tap(controls[0])
        root = self.ui.screen()
        allow = [node for node in root.iter("node") if node.get("package") in native
                 and node.get("text", "").casefold() == "allow" and node.get("enabled") != "false"]
        assert len(allow) == 1, "The native sources-folder permission prompt has no unique Allow action"
        self.ui.capture("portable-directory-permission", root)
        self.ui.tap(allow[0])

    def seed_catalog(self):
        self.library_click("a11y_new_circuit")
        self.wait_editor()
        patterns = [re.escape(text).replace(re.escape("{revision}"), r"\d+")
                    for text in self.catalog["workspace_saved_revision"].values()]
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
            if any(re.fullmatch(pattern, node.get("text", "")) for node in root.iter("node") for pattern in patterns):
                return
        raise AssertionError("The seeded workspace was not confirmed saved within 30s")

    def assert_sap1(self, project):
        verify_sap1(ET.tostring(project, encoding="utf-8"), self.contract)

    def choose_downloads(self, *, timeout=30):
        assert timeout > 0, "The native navigation timeout must be positive"
        native = {"com.android.documentsui", "com.google.android.documentsui"}
        started = time.monotonic()
        total_deadline = started + timeout * 3
        phase_deadline = started + timeout
        phase = "presentation"
        actions = 0
        trace_name = f"downloads-navigation-{uuid.uuid4().hex}"
        queries = 0
        last_hierarchy = None
        last_window_context = None
        trace = {"transitionLimitSeconds": timeout, "totalLimitSeconds": timeout * 3,
                 "maximumActions": 3, "events": [], "status": "RUNNING"}

        def event(kind, **fields):
            trace["events"].append({"seconds": round(time.monotonic() - started, 4),
                                    "phase": phase, "kind": kind, **fields})

        def remaining():
            now = time.monotonic()
            assert now < total_deadline, "DocumentsUI navigation exceeded its total deadline"
            assert now < phase_deadline, f"DocumentsUI {phase} transition exceeded its deadline"
            return min(phase_deadline, total_deadline) - now

        def screen():
            nonlocal queries, last_hierarchy, last_window_context
            limit = remaining()
            query_started = time.monotonic()
            query_finished = None
            queries += 1
            last_hierarchy = None
            last_window_context = None
            try:
                root = self.ui.screen(timeout=limit)
                query_finished = time.monotonic()
                last_hierarchy = f"{trace_name}-{queries:02d}.xml"
                (self.ui.output / last_hierarchy).write_bytes(ET.tostring(root, encoding="utf-8"))
                last_window_context = {
                    "source": "UIAUTOMATOR_XML_ONLY", "hierarchyAttributes": dict(root.attrib),
                    "topLevelNodes": [{key: node.get(key) for key in ("package", "class", "bounds")}
                                      for node in root if node.tag == "node"],
                    "wmToken": None, "pid": None,
                    "wmTokenObservedAtTap": False, "pidObservedAtTap": False,
                }
            finally:
                finished = time.monotonic() if query_finished is None else query_finished
                event("screen", querySeconds=round(finished - query_started, 4),
                      timeoutSeconds=round(limit, 4), hierarchy=last_hierarchy)
            remaining()  # A hierarchy returned after its deadline cannot satisfy readiness.
            return root

        def tap(node, next_phase):
            nonlocal phase, phase_deadline, actions
            remaining()
            assert actions < 3, "DocumentsUI navigation exceeded its action limit"
            actions += 1
            phase = next_phase
            phase_deadline = time.monotonic() + timeout
            event("tap", action=actions, text=node.get("text"), description=node.get("content-desc"),
                  resourceId=node.get("resource-id"), package=node.get("package"), bounds=node.get("bounds"),
                  enabled=node.get("enabled"), sourceHierarchy=last_hierarchy, windowContext=last_window_context)
            action_started = time.monotonic()
            try:
                self.ui.tap(node, timeout=remaining())
            finally:
                event("tap-finished", actionSeconds=round(time.monotonic() - action_started, 4))
            remaining()

        def roots_in(root):
            return [node for node in root.iter("node") if node.get("package") in native
                    and node.get("resource-id", "").endswith(":id/roots_list")]

        def directories_in(root):
            return [node for node in root.iter("node") if node.get("package") in native
                    and node.get("resource-id", "").endswith(":id/dir_list")]

        def pause():
            time.sleep(min(0.2, remaining()))

        try:
            event("start")
            root = screen()
            roots = roots_in(root)
            requested = False
            while not roots:
                opener = self.ui.optional(root, description="Show roots")
                if not requested and opener is not None:
                    assert opener.get("package") in native, "The roots action does not belong to DocumentsUI"
                    tap(opener, "roots-open")
                    requested = True
                else:
                    pause()
                root = screen()
                roots = roots_in(root)
            assert len(roots) == 1, "DocumentsUI did not expose one roots list"
            downloads = self.ui.optional(roots[0], text="Downloads")
            through_storage = downloads is None
            if through_storage:
                # OPEN_DOCUMENT_TREE omits the shortcut on the observed SDK.
                query_started = time.monotonic()
                try:
                    model = self.ui.adb("shell", "getprop", "ro.product.model", timeout=remaining()).strip()
                finally:
                    event("device-model", querySeconds=round(time.monotonic() - query_started, 4))
                remaining()
                assert model, "The native device storage label is unavailable"
                downloads = self.ui.find(roots[0], text=model)
            assert downloads.get("enabled") != "false", "The native storage location is disabled"
            tap(downloads, "location-content")
            while True:
                root = screen()
                directories = directories_in(root)
                if not roots_in(root) and len(directories) == 1:
                    break
                pause()
            if through_storage:
                # Reuse the fresh location hierarchy; do not start a nested file-picker deadline.
                while True:
                    download = self.ui.optional(directories[0], text="Download")
                    if download is not None:
                        tap(download, "download-content")
                        break
                    pause()
                    root = screen()
                    directories = directories_in(root)
                    assert not roots_in(root) and len(directories) == 1, "DocumentsUI lost its device-storage file list"
                while True:
                    root = screen()
                    crumbs = [node for node in root.iter("node") if node.get("package") in native
                              and node.get("resource-id", "").endswith(":id/breadcrumb_text")]
                    if not roots_in(root) and len(directories_in(root)) == 1 and crumbs and crumbs[-1].get("text") == "Download":
                        break
                    pause()
            remaining()
            trace["status"] = "PASSED"
        except Exception as error:
            trace.update(status="FAILED", error=str(error))
            raise
        finally:
            event("finish", actions=actions)
            (self.ui.output / f"{trace_name}.json").write_text(json.dumps(trace, indent=2) + "\n")

    def choose_native_file(self, name):
        deadline = time.monotonic() + 30
        scrolls = 0
        while time.monotonic() < deadline and scrolls < 12:
            root = self.ui.screen()
            item = self.ui.optional(root, text=name)
            if item is not None:
                self.ui.tap(item)
                return
            lists = [node for node in root.iter("node") if node.get("resource-id", "").endswith(":id/dir_list")]
            assert len(lists) == 1, "DocumentsUI did not expose its file list"
            if lists[0].get("scrollable") != "true":
                if time.monotonic() < deadline:
                    time.sleep(0.2)
                    continue
                raise AssertionError(f"The local fixture did not load in the native file list: {name}")
            left, top, right, bottom = self.ui._bounds(lists[0])
            # The native grid can extend behind the system gesture bar. Keep the swipe inside its central content.
            height = bottom - top
            self.ui.adb("shell", "input", "swipe", str((left + right) // 2), str(top + height * 7 // 10),
                        str((left + right) // 2), str(top + height * 3 // 10), "350")
            scrolls += 1
        raise AssertionError(f"The local fixture did not appear in DocumentsUI: {name}")

    def picker_export(self, filename, kill_process=False):
        expected_xml, expected_project = self.export_xml(
            "before-picker-recovery" if kill_process else "before-picker-export")
        self.assert_sap1(expected_project)
        expected_bytes = expected_xml.encode("utf-8")
        expected_digest = hashlib.sha256(expected_bytes).hexdigest()
        self.click_key("a11y_project_files", description=True)
        self.click_key("action_save_as")
        self.choose_downloads()
        root = self.ui.screen()
        fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"]
        assert len(fields) == 1, "CreateDocument did not expose a filename field"
        self.ui.input_text(fields[0], filename)
        ime = self.ui.adb("shell", "dumpsys", "input_method")
        if re.search(r"mInputShown=true|isInputViewShown=true", ime):
            self.ui.key(4)
        root = self.ui.screen()
        before = self.ui.adb("shell", "pidof", self.ui.package).strip()
        if kill_process:
            self.ui.capture("create-before-process-death", root)
            self.ui.adb("shell", "am", "kill", self.ui.package)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                running = subprocess.run([self.ui.adb_path, "-s", self.ui.serial, "shell", "pidof", self.ui.package],
                                         capture_output=True, text=True, timeout=10)
                if not running.stdout.strip():
                    break
                time.sleep(0.2)
            else:
                raise AssertionError("Android did not stop the background application process")
            root = self.ui.screen()
        self.ui.tap(self.ui.find(root, resource_id="android:id/button1"))
        self.wait_editor()
        if kill_process:
            after = self.ui.adb("shell", "pidof", self.ui.package).strip()
            assert before and after and before != after, "CreateDocument did not recreate the application process"
        deadline = time.monotonic() + 10
        remote = "/sdcard/Download/" + filename
        observed = b""
        while time.monotonic() < deadline:
            result = subprocess.run([self.ui.adb_path, "-s", self.ui.serial, "exec-out", "cat", remote], capture_output=True, timeout=10)
            observed = result.stdout
            (self.ui.output / filename).write_bytes(observed)
            try:
                assert result.returncode == 0, "The exported file could not be read"
                project = ET.fromstring(observed)
                self.assert_sap1(project)
            except (ET.ParseError, AssertionError):
                time.sleep(0.25)
                continue
            observed_digest = hashlib.sha256(observed).hexdigest()
            assert observed == expected_bytes and observed_digest == expected_digest, (
                f"Export changed the document: expected {len(expected_bytes)} bytes / SHA-256 {expected_digest}, "
                f"read {len(observed)} bytes / SHA-256 {observed_digest}")
            return remote
        raise AssertionError(
            f"Export did not finish with the expected {len(expected_bytes)} bytes / SHA-256 {expected_digest}; "
            f"last read {len(observed)} bytes / SHA-256 {hashlib.sha256(observed).hexdigest()}")

    def files(self):
        source = (ROOT / "examples/sap1.circ").read_bytes()
        view_token = uuid.uuid4().hex
        view_source = mark_import_source(source, view_token)
        uri = self.fixture(view_source)
        self.incoming(uri)
        _, project = self.export_xml("incoming-sap1")
        self.assert_sap1(project)
        assert project.get("e2e-import-token") == view_token, "VIEW did not install the newly marked project"
        fixture_bytes = subprocess.check_output([self.ui.adb_path, "-s", self.ui.serial, "exec-out", "run-as",
                                                self.ui.package + ".test", "cat", "files/document-fixtures/" + fixture_document_name(self.ui.package, uri)], timeout=10)
        assert fixture_bytes == view_source, "Import changed the external source"

        def incoming_share():
            token = uuid.uuid4().hex
            self.incoming(self.fixture(mark_import_source(source, token)), action="android.intent.action.SEND")
            _, shared = self.export_xml("incoming-share")
            self.assert_sap1(shared)
            assert shared.get("e2e-import-token") == token, "SEND did not install its newly marked project"
        self.run("incoming-share-imports-a-copy", incoming_share)

        def local_import():
            name = f"logisim-e2e-source-{self.ui.output.name}.circ"
            remote = "/sdcard/Download/" + name
            token = uuid.uuid4().hex
            marked = mark_import_source(source, token)
            staged = self.ui.output / name
            staged.write_bytes(marked)
            self.ui.adb("push", str(staged), remote)
            self.ui.adb("shell", "touch", remote)
            self.click_key("a11y_project_files", description=True)
            self.click_key("action_import_copy")
            self.choose_downloads()
            self.choose_native_file(name)
            self.wait_key("status_file_opened", name=name)
            _, imported = self.export_xml("local-import")
            self.assert_sap1(imported)
            assert imported.get("e2e-import-token") == token, "SAF did not install its newly marked project"
            original = subprocess.check_output([self.ui.adb_path, "-s", self.ui.serial, "exec-out", "cat", remote], timeout=10)
            assert original == marked, "Local import changed the source"
        self.run("saf-local-import-preserves-source", local_import)

        def cancellation():
            before, _ = self.export_xml("before-cancel")
            self.click_key("a11y_project_files", description=True)
            self.click_key("action_import_copy")
            self.ui.screen()
            self.ui.key(4)
            after, _ = self.export_xml("after-cancel")
            assert before == after, "Canceling OpenDocument changed the project"
        self.run("cancel-open-preserves-project", cancellation)

        def cancel_export():
            before, _ = self.export_xml("before-cancel-export")
            self.click_key("a11y_project_files", description=True)
            self.click_key("action_save_as")
            self.ui.screen()
            self.ui.key(4)
            after, _ = self.export_xml("after-cancel-export")
            assert before == after, "Canceling CreateDocument changed the project"
        self.run("cancel-export-preserves-project", cancel_export)

        def share(cancel=False):
            before, _ = self.export_xml("before-share")
            self.ui.adb("shell", "run-as", self.ui.package + ".test", "rm", "-f", "files/share-result.json")
            self.click_key("a11y_project_files", description=True)
            self.click_key("action_share")
            chooser = self.ui.screen()
            if cancel:
                self.ui.key(4)
            else:
                self.ui.tap(self.ui.find(chooser, text=f"Document test receiver: {self.ui.package}.test"))
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    result = subprocess.run([self.ui.adb_path, "-s", self.ui.serial, "exec-out", "run-as",
                                             self.ui.package + ".test", "cat", "files/share-result.json"], capture_output=True, timeout=10)
                    try:
                        received = json.loads(result.stdout)
                    except json.JSONDecodeError:
                        time.sleep(0.25)
                        continue
                    (self.ui.output / "share-receiver.json").write_text(json.dumps(received, indent=2) + "\n")
                    expected = before.encode("utf-8")
                    assert received["readable"] and received["circuits"] == 7, "The receiver could not parse the shared project"
                    assert received["bytes"] == len(expected), "Sharing truncated or extended the project"
                    assert received["sha256"] == hashlib.sha256(expected).hexdigest(), "Sharing changed the project's exact bytes"
                    assert received["uri"].startswith(f"content://{self.ui.package}.files/circuits/")
                    break
                else:
                    raise AssertionError("The external receiver did not read the temporary shared URI")
            after, _ = self.export_xml("after-share")
            assert before == after, "Sharing changed the project"
        self.run("cancel-share-preserves-project", lambda: share(cancel=True))
        self.run("external-receiver-reads-share-grant", share)

        self.run("invalid-and-incompatible-files-preserve-project", self.rejected_inputs)

        stamp = self.ui.output.name
        self.run("save-as-local-document", lambda: self.picker_export(f"logisim-e2e-{stamp}.circ"))
        self.run("recover-create-document-after-process-death",
                 lambda: self.picker_export(f"logisim-e2e-recovered-{stamp}.circ", kill_process=True))

    def rejected_inputs(self):
        before, _ = self.export_xml("before-invalid")
        incompatible = (b'<project version="1.0"><lib name="8" desc="file#missing-library.circ"/>'
                        b'<main name="main"/><circuit name="main"><comp lib="8" loc="(100,100)" '
                        b'name="UnavailableGate"/></circuit></project>')
        for name, payload in [("invalid-xml", b"<broken/>"), ("unavailable-library", incompatible),
                              ("incompatible-encoding", bytes([0xc3, 0x28]))]:
            self.incoming(self.fixture(payload), expect_error=True)
            self.click_key("action_understood")
            after, _ = self.export_xml(name)
            assert before == after, f"{name} replaced the valid project"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--scenario", choices=["files", "rejected", "editing", "sequential", "layout", "unicode",
                        "inspector", "io-showcase", "portable", "visual-export34", "layout12", "all"], default="all")
    parser.add_argument("--build-receipt", type=Path, help="Verified app APK build receipt; required with --artifacts")
    parser.add_argument("--portable-mode", choices=["all", "bundle", "folder"], default="all",
                        help="Select a portable group without rerunning an already qualified group")
    parser.add_argument("--artifacts", type=Path, help="Directory containing the three APKs with their standard filenames")
    parser.add_argument("--aapt2", type=Path, help="Local aapt2 executable; otherwise use SDK build-tools")
    parser.add_argument("--language", choices=["en", "pt"], help="Initialize the isolated app's personal language preference")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--layout12-unit", choices=("small-phone", "tablet"),
                        help="Select one layout unit; wm overrides never qualify an actual tablet or split screen")
    parser.add_argument("--layout12-font", choices=("1", "2", "both"),
                        help="Run only the requested font-scale cell instead of repeating a qualified cell")
    args = parser.parse_args()
    if args.scenario != "layout12" and (args.layout12_unit is not None or args.layout12_font is not None):
        parser.error("--layout12-unit and --layout12-font require --scenario layout12")
    if args.scenario == "layout12":
        if not args.serial:
            parser.error("The layout unit requires an explicit reserved --serial")
        from android_layout12_scenarios import fixture_bytes
        fixture_bytes()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    if args.artifacts is not None and args.build_receipt is None:
        parser.error("--artifacts requires --build-receipt")
    try:
        package = expected_e2e_package()
        app, tests, snapshot = artifact_paths(args.artifacts)
        app_sha, test_sha, snapshot_sha = map(file_hash, (app, tests, snapshot))
        receipt, receipt_bytes = load_build_receipt(args.build_receipt, app_sha) if args.build_receipt else (None, None)
        aapt2 = find_aapt2(sdk, args.aapt2)
        apk_identities = validate_apk_manifests(aapt2, app, tests, snapshot, package)
    except (ValueError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        parser.error(f"APK preflight failed: {error}")
    adb = str(sdk / "platform-tools/adb")
    if not args.serial:
        attached = subprocess.check_output([adb, "devices"], text=True, timeout=10)
        serials = [line.split()[0] for line in attached.splitlines()[1:] if line.endswith("\tdevice")]
        if len(serials) != 1:
            parser.error("Connect one device or set ANDROID_SERIAL/--serial")
        args.serial = serials[0]
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = args.output or ROOT / "outputs/android-e2e" / timestamp
    output.mkdir(parents=True, exist_ok=False)
    ui = AndroidUi(adb, args.serial, package, output, focus_snapshots=True)
    manifest = {"package": package, "serial": args.serial, "utc": timestamp, "appSha256": app_sha,
                "testSha256": test_sha, "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "deviceFingerprint": ui.adb("shell", "getprop", "ro.build.fingerprint").strip()}
    manifest["scenario"] = args.scenario
    manifest["portableMode"] = args.portable_mode if args.scenario == "portable" else None
    manifest["layout12Unit"] = (args.layout12_unit or "small-phone") if args.scenario == "layout12" else None
    manifest["layout12Font"] = (args.layout12_font or "both") if args.scenario == "layout12" else None
    manifest["artifactPaths"] = {"app": str(app.resolve()), "test": str(tests.resolve()), "snapshot": str(snapshot.resolve())}
    manifest["apkManifestIdentities"] = apk_identities
    manifest["aapt2"] = str(aapt2.resolve())
    if receipt is not None:
        manifest["harnessGitHead"] = manifest["gitHead"]
        manifest["gitHead"] = receipt["gitHead"]
        (output / "build-receipt.json").write_bytes(receipt_bytes)
    manifest["sourceChanges"] = subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True).splitlines()
    manifest["deviceApi"] = ui.adb("shell", "getprop", "ro.build.version.sdk").strip()
    manifest["screenSize"] = ui.adb("shell", "wm", "size").strip()
    manifest["screenDensity"] = ui.adb("shell", "wm", "density").strip()
    manifest["systemLocales"] = ui.adb("shell", "settings", "get", "system", "system_locales").strip()
    manifest["appLanguage"] = args.language or "system"
    manifest["focusSnapshots"] = "Dedicated SDK snapshot APK during focused app EditText; CLI for external activities"
    manifest["snapshotPackage"] = ui.snapshot_package
    manifest["snapshotSha256"] = snapshot_sha
    manifest["scenarioSources"] = {}
    for name in ("e2e-android.py", "android_ui.py", "android_editing_scenarios.py", "user_scenario_contract.py",
                 "android_inspector_scenarios.py", "android_io_showcase_scenarios.py", "android_portable_scenarios.py",
                 "android_visual_export34_scenarios.py", "android_layout12_scenarios.py",
                 "android_layout12_geometry.py", "android_layout12_window.py",
                 "android_accessibility_layout.py", "test-android-assets.py"):
        source = ROOT / "scripts" / name
        retained = output / ("source-" + name)
        retained.write_bytes(source.read_bytes())
        manifest["scenarioSources"][name] = file_hash(retained)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    manifest["scenarioContractSha256"] = file_hash(ROOT / "fixtures/user-scenarios/editor-actions-v1.json")
    if args.scenario == "layout12":
        for name in ("layout12.circ", "contract.json"):
            source = ROOT / "fixtures/user-scenarios/layout12" / name
            retained = output / ("source-layout12-" + name)
            retained.write_bytes(source.read_bytes())
            manifest["scenarioSources"]["fixtures/user-scenarios/layout12/" + name] = file_hash(retained)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    scenarios = Scenarios(ui)
    try:
        locale = manifest["systemLocales"]
        if locale in ("", "null"):
            locale = ui.adb("shell", "getprop", "persist.sys.locale").strip() or ui.adb("shell", "getprop", "ro.product.locale").strip()
        if not locale.lower().startswith("en"):
            raise AssertionError(f"The native DocumentsUI fixture requires an English system locale; found {locale!r}. App UI supports PT and EN.")
        previous_tasks = owned_task_ids(ui.adb("shell", "dumpsys", "activity", "activities"), package)
        manifest["resetTaskIdsBefore"] = sorted(previous_tasks)
        ui.adb("install", "-r", str(app))
        ui.adb("install", "-r", str(tests))
        ui.adb("install", "-r", str(snapshot))
        manifest["installedArtifactsBefore"] = [ui.verify_installed_apk(app),
            ui.verify_installed_apk(tests, package + ".test"), ui.verify_installed_apk(snapshot, ui.snapshot_package)]
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        ui.adb("shell", "pm", "clear", package)
        ui.adb("shell", "pm", "clear", package + ".test")
        manifest["resetTaskRemoval"] = await_reset_tasks_removed(ui, previous_tasks)
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        if args.language:
            preferences = ET.Element("map")
            serialized = ET.SubElement(preferences, "string", {"name": "serialized"})
            serialized.text = "LOGISIM-EDITOR-PREFERENCES\n2\nlocale=" + ("pt-BR" if args.language == "pt" else "en") + "\n"
            command = f"run-as {shlex.quote(package)} sh -c {shlex.quote('mkdir -p shared_prefs' + chr(10) + 'cat > shared_prefs/editor-preferences.xml')}"
            subprocess.run([adb, "-s", args.serial, "shell", command],
                           input=ET.tostring(preferences, encoding="utf-8", xml_declaration=True), check=True, timeout=15)
        scenarios.launch()
        if args.language:
            expected = scenarios.catalog["a11y_canvas"][args.language].split("{outputs}", 1)[0]
            assert scenarios.canvas().get("content-desc", "").startswith(expected), "The app did not apply the requested language preference"
        if args.scenario in ("sequential", "all"):
            scenarios.run("sequential-simulation-and-recovery", scenarios.sequential)
        if args.scenario in ("files", "all"):
            scenarios.run("native-file-workflow", scenarios.files)
        if args.scenario == "rejected":
            scenarios.run("invalid-and-incompatible-files-preserve-project", scenarios.rejected_inputs)
        if args.scenario in ("editing", "all"):
            run_editing(scenarios)
        if args.scenario in ("layout", "all"):
            scenarios.run("landscape-and-large-text-actions", scenarios.layout)
        if args.scenario in ("unicode", "all"):
            scenarios.run("unicode-and-directional-labels", scenarios.unicode_labels)
        if args.scenario == "inspector":
            from android_inspector_scenarios import run_inspector
            run_inspector(scenarios)
        if args.scenario == "io-showcase":
            from android_io_showcase_scenarios import run_io_showcase
            run_io_showcase(scenarios)
        if args.scenario == "portable":
            from android_portable_scenarios import run_portable
            scenarios.seed_catalog()
            run_portable(scenarios, mode=args.portable_mode)
        if args.scenario == "visual-export34":
            from android_visual_export34_scenarios import run_visual_export34
            scenarios.run("visual-export34", lambda: run_visual_export34(scenarios))
        if args.scenario == "layout12":
            from android_layout12_scenarios import run_layout12
            selected_fonts = (1.0, 2.0) if args.layout12_font in (None, "both") else (float(args.layout12_font),)
            run_layout12(scenarios, unit=args.layout12_unit or "small-phone", fonts=selected_fonts)
        manifest["installedArtifactsAfter"] = [ui.verify_installed_apk(app),
            ui.verify_installed_apk(tests, package + ".test"), ui.verify_installed_apk(snapshot, ui.snapshot_package)]
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(f"E2E evidence: {output}")
    except Exception as error:
        ui.fail_artifacts("failure", error)
        print(f"FAIL: {error}\nEvidence: {output}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
