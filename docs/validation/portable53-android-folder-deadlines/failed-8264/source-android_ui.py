"""Small adb UI driver and failure artifacts for an isolated Android test app."""

from __future__ import annotations

import io
import base64
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time
import traceback
import uuid
import xml.etree.ElementTree as ET


class UiNodeNotFound(AssertionError):
    pass


class AndroidUi:
    def __init__(self, adb_path, serial, package, output, *, focus_snapshots=False):
        if not serial or not str(serial).strip():
            raise ValueError("An explicit Android device serial is required")
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", package):
            raise ValueError("Invalid Android package name")
        self.adb_path = str(adb_path)
        self.serial = str(serial)
        self.package = package
        self.output = Path(output).resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self._token = uuid.uuid4().hex
        self._attempt = 0
        if focus_snapshots and not package.endswith(".e2e"):
            raise ValueError("Focus snapshots require an isolated .e2e app and its test APK")
        self._focus_snapshots = bool(focus_snapshots)
        self._focused_snapshot_active = False
        self._snapshot_uptime = -1
        self._snapshot_count = 0
        self.snapshot_package = package.removesuffix(".e2e") + ".snapshot.e2e.test"

    def _run(self, args, *, text=True, timeout=30, check=True):
        command = [self.adb_path, "-s", self.serial, *map(str, args)]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=text, timeout=timeout, check=False)
        if check and result.returncode:
            stderr = result.stderr if text else result.stderr.decode("utf-8", errors="replace")
            raise RuntimeError(f"adb failed ({result.returncode}): {command!r}\n{stderr[-4000:]}")
        return result

    def adb(self, *args, text=True):
        return self._run(args, text=text).stdout

    def verify_installed_apk(self, apk, package=None):
        """Require the device's monolithic APK to match the artifact recorded by a run."""
        package = package or self.package
        if package not in (self.package, self.package + ".test", self.snapshot_package):
            raise ValueError("APK verification is restricted to the current app and its isolated helpers")
        lines = self.adb("shell", "pm", "path", package).strip().splitlines()
        if len(lines) != 1 or not re.fullmatch(r"package:/data/app/[A-Za-z0-9_/.=+~-]+/base\.apk", lines[0]):
            raise AssertionError(f"Expected one installed base APK for {package}: {lines!r}")
        path = lines[0].removeprefix("package:")
        result = self.adb("shell", "sha256sum", path).strip()
        match = re.fullmatch(r"([0-9a-f]{64})\s+" + re.escape(path), result)
        if match is None:
            raise AssertionError(f"Android did not report the installed APK hash for {package}: {result!r}")
        expected = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
        if match.group(1) != expected:
            raise AssertionError(f"Installed APK mismatch for {package}: expected {expected}, observed {match.group(1)}")
        return {"package": package, "path": path, "sha256": expected}

    def screen(self, timeout=45):
        """Read a fresh hierarchy; callers decide which application or dialog is ready."""
        if self._focused_snapshot_active:
            deadline = time.monotonic() + timeout
            top = self._run(("shell", "dumpsys", "activity", "activities"), timeout=min(5, timeout)).stdout
            resumed = re.findall(r"(?:topResumedActivity|mResumedActivity).*?\bu\d+\s+([A-Za-z0-9_.]+)/", top)
            if not resumed:
                raise AssertionError("Cannot confirm the foreground package before a focus snapshot")
            if resumed[0] == self.package:
                root = self._screen_instrumentation(max(0.1, deadline - time.monotonic()))
                self._focused_snapshot_active = any(node.get("package") == self.package
                    and node.get("class", "").endswith("EditText") and node.get("focused") == "true"
                    for node in root.iter("node"))
                return root
            # External activities retain the established CLI observation path;
            # the SDK path is specifically for focused Compose editor fields.
            self._focused_snapshot_active = False
            timeout = max(0.1, deadline - time.monotonic())
        return self._screen_uiautomator(timeout)

    def _screen_instrumentation(self, timeout):
        deadline = time.monotonic() + timeout
        nonce = uuid.uuid4().hex
        def remaining(limit):
            value = deadline - time.monotonic()
            if value <= 0:
                raise AssertionError("Focus snapshot exceeded its existing hierarchy deadline")
            return min(limit, value)
        def app_pid():
            value = self._run(("shell", "pidof", self.package), timeout=remaining(3)).stdout.strip()
            if not re.fullmatch(r"[0-9]+", value):
                raise AssertionError(f"Expected one live app process during the focus snapshot: {value!r}")
            return value
        before = app_pid()
        component = self.snapshot_package + "/dev.logisim.mobile.documents.UiSnapshotInstrumentation"
        self._snapshot_count += 1
        prefix = self.output / f"focus-snapshot-{self._token}-{self._snapshot_count}"
        receipt = {
            "nonce": nonce, "expectedPackage": self.package, "target": component,
            "appPidBefore": before, "appPidAfter": None, "adbReturncode": None,
            "stdout": prefix.with_suffix(".stdout.txt").name,
            "stderr": prefix.with_suffix(".stderr.txt").name,
        }
        result = stderr = ""
        raw_stdout = raw_stderr = None

        def save_receipt(status, error=None):
            values, duplicates = {}, []
            for key, value in re.findall(r"^INSTRUMENTATION_RESULT: ([A-Za-z][A-Za-z0-9_]*)=(.*)$",
                                         result, re.MULTILINE):
                if key in values:
                    duplicates.append(key)
                else:
                    values[key] = value
            receipt.update(status=status, bundle=values, duplicateBundleKeys=duplicates,
                           instrumentationCodes=re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$",
                                                            result, re.MULTILINE))
            receipt.update((key, value) for key, value in values.items()
                           if key.startswith("snapshot") and key != "snapshotXmlBase64")
            if error is not None:
                receipt["error"] = error
            prefix.with_suffix(".stdout.txt").write_bytes(
                raw_stdout if isinstance(raw_stdout, bytes) else result.encode("utf-8"))
            prefix.with_suffix(".stderr.txt").write_bytes(
                raw_stderr if isinstance(raw_stderr, bytes) else stderr.encode("utf-8"))
            prefix.with_suffix(".json").write_text(json.dumps(receipt, indent=2) + "\n")
            return values, duplicates

        try:
            response = self._run(("shell", "am", "instrument", "-w", "-r", "-e", "snapshotNonce", nonce,
                                  "-e", "snapshotExpectedPackage", self.package, component),
                                 timeout=remaining(timeout), check=False)
        except subprocess.TimeoutExpired as error:
            raw_stdout, raw_stderr = error.stdout, error.stderr
            result = error.stdout.decode("utf-8", errors="replace") if isinstance(error.stdout, bytes) else error.stdout or ""
            stderr = error.stderr.decode("utf-8", errors="replace") if isinstance(error.stderr, bytes) else error.stderr or ""
            receipt["timeoutSeconds"] = error.timeout
            save_receipt("timed-out", str(error))
            raise
        result, stderr = response.stdout, response.stderr
        receipt["adbReturncode"] = response.returncode
        values, duplicates = save_receipt("received")
        try:
            if response.returncode:
                raise RuntimeError(f"adb failed ({response.returncode}) during the focus snapshot:\n{stderr[-4000:]}")
            receipt["appPidAfter"] = app_pid()
            if receipt["appPidAfter"] != before:
                raise AssertionError("Snapshot instrumentation changed the app process")
            if duplicates:
                raise AssertionError(f"Duplicate snapshot result field: {duplicates[0]}")
            if "snapshotError" in values or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", result, re.MULTILINE):
                raise AssertionError("Focus snapshot failed: " + values.get("snapshotError", result[-1000:]))
            if values.get("snapshotNonce") != nonce:
                raise AssertionError("Focus snapshot returned an old or missing nonce")
            try:
                root = ET.fromstring(base64.b64decode(values["snapshotXmlBase64"], validate=True).decode("utf-8"))
                uptime = int(values["snapshotUptimeMillis"])
                rotation = int(values["snapshotRotation"])
            except (KeyError, ValueError, UnicodeError, ET.ParseError) as error:
                raise AssertionError("Focus snapshot returned invalid XML or metadata") from error
            if root.tag != "hierarchy" or root.get("snapshot-nonce") != nonce or \
                    root.get("uptime-millis") != str(uptime) or root.get("rotation") != str(rotation) or \
                    not 0 <= rotation <= 3 or uptime < 0 or uptime < self._snapshot_uptime:
                raise AssertionError("Focus snapshot XML and result metadata do not match")
            self._snapshot_uptime = uptime
            prefix.with_suffix(".xml").write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            receipt.update(uptimeMillis=uptime, rotation=rotation, xml=prefix.with_suffix(".xml").name)
        except Exception as error:
            save_receipt("rejected", str(error))
            raise
        save_receipt("complete")
        return root

    def _screen_uiautomator(self, timeout):
        deadline = time.monotonic() + timeout
        last_error = "no hierarchy returned"
        while time.monotonic() < deadline:
            self._attempt += 1
            remote = f"/sdcard/logisim-e2e-{self._token}-{self._attempt}.xml"
            try:
                remaining = deadline - time.monotonic()
                # The CLI can spend ten seconds waiting for idle before returning;
                # leave room for its process startup instead of killing that wait.
                result = self._run(("shell", "uiautomator", "dump", "--compressed", remote),
                                   timeout=max(0.1, min(15, remaining)), check=False)
                if result.returncode or "dumped to" not in result.stdout:
                    last_error = (result.stderr or result.stdout).strip() or "UiAutomator returned a null root"
                else:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    xml = self._run(("shell", "cat", remote), timeout=min(5, remaining)).stdout
                    root = ET.fromstring(xml)
                    if root.tag != "hierarchy":
                        raise ValueError(f"Unexpected UI root: {root.tag}")
                    return root
            except (subprocess.TimeoutExpired, RuntimeError, ET.ParseError, ValueError) as error:
                last_error = str(error)
            finally:
                try:
                    self._run(("shell", "rm", "-f", remote), timeout=1, check=False)
                except (subprocess.TimeoutExpired, OSError):
                    pass
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))
        raise AssertionError(f"No fresh Android UI hierarchy within {timeout}s: {last_error}")

    @staticmethod
    def find(root, *, text=None, description=None, resource_id=None, prefix=False):
        if text is None and description is None and resource_id is None:
            raise ValueError("Provide a text, description, or resource ID selector")

        def matches(actual, expected):
            return expected is None or (actual.startswith(expected) if prefix else actual == expected)

        found = [node for node in root.iter("node")
                 if matches(node.get("text", ""), text)
                 and matches(node.get("content-desc", ""), description)
                 and (resource_id is None or node.get("resource-id") == resource_id)]
        selector = dict(text=text, description=description, resource_id=resource_id, prefix=prefix)
        if not found:
            raise UiNodeNotFound(f"Visible control missing: {selector}")
        if len(found) != 1:
            raise AssertionError(f"Ambiguous control ({len(found)} matches): {selector}")
        return found[0]

    @classmethod
    def optional(cls, root, **selector):
        try:
            return cls.find(root, **selector)
        except UiNodeNotFound:
            return None

    def _require_test_app(self):
        if not self.package.endswith(".e2e"):
            raise ValueError("Mutating UI actions require an application ID ending in .e2e")

    @staticmethod
    def _bounds(node):
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if not match:
            raise AssertionError(f"Control has no usable bounds: {node.attrib}")
        left, top, right, bottom = map(int, match.groups())
        if right <= left or bottom <= top or (left + right) // 2 < 0 or (top + bottom) // 2 < 0:
            raise AssertionError(f"Control is outside the visible screen: {node.attrib}")
        return left, top, right, bottom

    @staticmethod
    def _rectangle(node):
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if match is None:
            return None
        left, top, right, bottom = map(int, match.groups())
        return (left, top, right, bottom) if right > left and bottom > top else None

    def visible_node(self, root, node, within=None):
        """Require the complete control inside its window and scrolling viewports."""
        parents = {child: parent for parent in root.iter() for child in parent}
        if node is not root and node not in parents:
            return False
        window = root if root.get("bounds") else next(root.iter("node"), None)
        frame = self._rectangle(node)
        window_frame = self._rectangle(window) if window is not None else None
        if frame is None or window_frame is None or frame[0] < 0 or frame[1] < 0:
            return False

        def contains(outer, inner):
            return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]

        if not contains(window_frame, frame):
            return False
        ancestor = node
        while ancestor is not None:
            if ancestor.get("visible-to-user") == "false":
                return False
            if ancestor is not node and ancestor.get("scrollable") == "true":
                clip = self._rectangle(ancestor)
                if clip is None or not contains(clip, frame):
                    return False
            ancestor = parents.get(ancestor)
        if within is not None:
            if not any(candidate is node for candidate in within.iter()):
                return False
            clip = self._rectangle(within)
            if clip is None or not contains(clip, frame):
                return False
        return True

    def tagged(self, root, identifier):
        """Find one exact app-owned test tag, without accepting another package."""
        nodes = [node for node in root.iter("node")
                 if node.get("resource-id") == identifier and node.get("package") == self.package]
        if len(nodes) > 1:
            raise AssertionError(f"Ambiguous app tag {identifier}: {len(nodes)} controls")
        return nodes[0] if nodes else None

    def toolbar_action(self, toolbars, *, matches, timeout=30, root=None):
        """Return a reachable action, opening only an allowed toolbar's overflow.

        None permits a caller's legacy scrolling route when no allowed route
        exposes an overflow or menu. An open menu owns the entire search.
        """
        toolbars = (toolbars,) if isinstance(toolbars, str) else tuple(toolbars)
        if not toolbars or len(set(toolbars)) != len(toolbars) or not all(isinstance(value, str) and value for value in toolbars):
            raise ValueError("Provide unique toolbar tag identifiers in route order")
        if not callable(matches):
            raise ValueError("Provide an exact action predicate")
        if not 0 < timeout <= 30:
            raise ValueError("Toolbar navigation timeout must be greater than zero and at most 30 seconds")
        deadline = time.monotonic() + timeout
        known = set(toolbars) | {"editor-project-toolbar", "editor-command-toolbar", "editor-simulation-toolbar"}

        def remaining():
            value = deadline - time.monotonic()
            if value <= 0:
                raise AssertionError("Toolbar navigation exceeded its deadline")
            return value

        def fresh():
            return self.screen(timeout=remaining())

        def menu_owner(current):
            identifiers = {node.get("resource-id") for node in current.iter("node")
                           if node.get("package") == self.package
                           and node.get("resource-id", "").endswith("-toolbar-menu")}
            identifiers.update(toolbar + "-menu" for toolbar in known)
            opened = [(identifier, self.tagged(current, identifier)) for identifier in sorted(identifiers)]
            opened = [(identifier, menu) for identifier, menu in opened if menu is not None]
            if len(opened) > 1:
                raise AssertionError(f"Ambiguous open toolbar menus: {[identifier for identifier, _ in opened]}")
            if opened and opened[0][0] not in {toolbar + "-menu" for toolbar in toolbars}:
                raise AssertionError(f"Another toolbar menu is already open: {opened[0][0]}")
            return opened[0] if opened else None

        def control(current, node, scope, label):
            parents = {child: parent for parent in current.iter() for child in parent}
            action = node
            while action.get("clickable") != "true" and action is not scope and action in parents:
                action = parents[action]
            if action.get("clickable") != "true" or action.get("package") != self.package or action is scope:
                raise AssertionError(f"{label} has no clickable application control inside its container")
            ancestor = node
            while ancestor is not None:
                if ancestor.get("enabled") == "false":
                    raise AssertionError(f"{label} is disabled: {ancestor.attrib}")
                ancestor = parents.get(ancestor)
            return action

        def target(current, scope):
            found = [node for node in scope.iter("node") if node.get("package") == self.package and matches(node)]
            if len(found) > 1:
                raise AssertionError(f"Ambiguous toolbar action: {len(found)} controls")
            return control(current, found[0], scope, "Toolbar action") if found else None

        current = root if root is not None else fresh()
        opened = menu_owner(current)
        if opened is None:
            routes = [(identifier, self.tagged(current, identifier)) for identifier in toolbars]
            direct = [(identifier, bar, target(current, bar)) for identifier, bar in routes if bar is not None]
            candidates = [(identifier, bar, node) for identifier, bar, node in direct if node is not None]
            if len(candidates) > 1:
                raise AssertionError(f"Ambiguous toolbar action across routes: {[identifier for identifier, _, _ in candidates]}")
            if candidates and self.visible_node(current, candidates[0][2], within=candidates[0][1]):
                return candidates[0][2]
            # Prefer the route containing a clipped direct action, then the
            # caller's explicit order. Never search unrelated controls.
            ordered = [item for item in routes if candidates and item[0] == candidates[0][0]] + [
                item for item in routes if not candidates or item[0] != candidates[0][0]]
            for identifier, bar in ordered:
                overflow = self.tagged(current, identifier + "-overflow")
                if overflow is None:
                    continue
                if bar is None or not any(candidate is overflow for candidate in bar.iter()):
                    raise AssertionError(f"Toolbar overflow is outside its toolbar: {identifier}")
                overflow = control(current, overflow, bar, "Toolbar overflow")
                if not self.visible_node(current, overflow, within=bar):
                    raise AssertionError(f"Toolbar overflow is outside its toolbar: {identifier}")
                self._require_test_app()
                left, top, right, bottom = self._bounds(overflow)
                self._run(("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2)), timeout=remaining())
                expected = identifier + "-menu"
                while True:
                    current = fresh()
                    opened = menu_owner(current)
                    if opened is not None:
                        if opened[0] != expected:
                            raise AssertionError(f"Overflow opened inconsistent menu: expected {expected}, found {opened[0]}")
                        break
                    time.sleep(min(0.1, remaining()))
                break
            else:
                return None

        menu_id = opened[0]
        for reverse in (False, True):
            previous = None
            for attempt in range(9):
                remaining()
                opened = menu_owner(current)
                if opened is None or opened[0] != menu_id:
                    raise AssertionError(f"The active toolbar menu disappeared or changed: {menu_id}")
                menu = opened[1]
                node = target(current, menu)
                if node is not None and self.visible_node(current, node, within=menu):
                    return node
                if not self.visible_node(current, menu) or menu.get("enabled") == "false":
                    raise AssertionError(f"Toolbar menu has no visible scrolling bounds: {menu_id}")
                signature = ET.tostring(menu, encoding="unicode")
                if attempt == 8 or signature == previous:
                    break
                previous = signature
                left, top, right, bottom = self._bounds(menu)
                start, end = (top + (bottom - top) * .8, top + (bottom - top) * .2)
                if reverse:
                    start, end = end, start
                self._require_test_app()
                self._run(("shell", "input", "swipe", str((left + right) // 2), str(round(start)),
                           str((left + right) // 2), str(round(end)), "250"), timeout=remaining())
                current = fresh()
        raise UiNodeNotFound(f"Action was not reachable in the active toolbar menu: {menu_id}")

    def tap(self, node):
        self._require_test_app()
        if node.get("enabled") == "false":
            raise AssertionError(f"Control is disabled: {node.attrib}")
        left, top, right, bottom = self._bounds(node)
        self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def click(self, *, text=None, description=None, resource_id=None, prefix=False, timeout=30):
        deadline = time.monotonic() + timeout
        last_error = None
        while time.monotonic() < deadline:
            root = self.screen(timeout=max(0.1, deadline - time.monotonic()))
            try:
                node = self.find(root, text=text, description=description, resource_id=resource_id, prefix=prefix)
            except UiNodeNotFound as error:
                last_error = error
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
                continue
            self.tap(node)
            return node
        raise UiNodeNotFound(f"Control did not appear within {timeout}s: {last_error}")

    def key(self, code):
        self._require_test_app()
        if not isinstance(code, int) or isinstance(code, bool) or code < 0:
            raise ValueError("Android key code must be a nonnegative integer")
        self.adb("shell", "input", "keyevent", str(code))

    def input_text(self, node, value):
        """Replace the focused EditText with printable ASCII; fixtures carry larger XML."""
        self._require_test_app()
        if any(ord(char) < 32 or ord(char) > 126 for char in value):
            raise ValueError("input_text accepts printable ASCII; use a document fixture for Unicode or multiline text")
        if not node.get("class", "").endswith("EditText"):
            raise ValueError("input_text requires a current EditText node")
        if node.get("text", "") == value:
            return
        self.tap(node)
        deadline = time.monotonic() + 8
        while True:
            if self._focus_snapshots and node.get("package") == self.package:
                self._focused_snapshot_active = True
            root = self.screen(timeout=max(0.1, deadline - time.monotonic()))
            focused = [item for item in root.iter("node")
                       if item.get("class", "").endswith("EditText") and item.get("focused") == "true"]
            if len(focused) == 1:
                old_text = focused[0].get("text", "")
                break
            if time.monotonic() >= deadline:
                raise AssertionError("The selected EditText did not receive focus")
        self.key(123)  # KEYCODE_MOVE_END
        for start in range(0, len(old_text), 128):
            self.adb("shell", "input", "keyevent", *(["67"] * min(128, len(old_text) - start)))
        # Android input converts %s to a space. Separate literal percent signs so %s survives verbatim.
        chunks = value.split("%")
        for index, chunk in enumerate(chunks):
            if index:
                self.adb("shell", "input text " + shlex.quote("%"))
            if chunk:
                self.adb("shell", "input text " + shlex.quote(chunk.replace(" ", "%s")))

    def _path(self, name, suffix):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name):
            raise ValueError("Artifact names must be simple filenames")
        return self.output / f"{name}{suffix}"

    def capture(self, name, root=None):
        png = self.adb("exec-out", "screencap", "-p", text=False)
        if not png.startswith(b"\x89PNG\r\n\x1a\n"):
            raise AssertionError("screencap did not return a PNG image")
        image = self._path(name, ".png")
        image.write_bytes(png)
        root = root if root is not None else self.screen()
        hierarchy = self._path(name, "-ui.xml")
        hierarchy.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
        return {"screenshot": str(image), "hierarchy": str(hierarchy)}

    def fail_artifacts(self, name, error):
        """Collect independently, retaining the scenario error if one collector also fails."""
        result = {"scenario_error": str(error), "artifacts": {}, "collection_errors": {}}
        error_path = self._path(name, "-error.txt")
        error_path.write_text("".join(traceback.format_exception(type(error), error, error.__traceback__))
                              if isinstance(error, BaseException) else str(error))
        result["artifacts"]["error"] = str(error_path)

        def collect(kind, action):
            try:
                result["artifacts"][kind] = action()
            except Exception as failure:
                result["collection_errors"][kind] = str(failure)

        def screenshot():
            path = self._path(name, ".png")
            png = self.adb("exec-out", "screencap", "-p", text=False)
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                raise AssertionError("screencap did not return a PNG")
            path.write_bytes(png)
            return str(path)

        def hierarchy():
            path = self._path(name, "-ui.xml")
            root = self.screen(timeout=8)
            path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            for item in root.iter("node"):
                text = item.get("text", "")
                if "<project" in text:
                    try:
                        ET.fromstring(text)
                        self._path(name, "-visible-document.circ").write_text(text)
                    except ET.ParseError:
                        pass
            return str(path)

        def logcat():
            pids = self._run(("shell", "pidof", self.package), check=False).stdout.split()
            logs = []
            if not pids:
                lookup = self._run(("shell", "logcat", "-d", "-b", "crash", "-v", "brief", "-t", "2000", "-e", re.escape(self.package)), check=False)
                logs.append(lookup.stdout)
                pids = re.findall(r"PID:\s*(\d+)", lookup.stdout)
            for pid in dict.fromkeys(pids):
                if pid.isdigit():
                    logs.append(self.adb("shell", "logcat", "-d", "--pid=" + pid, "-v", "threadtime", "-t", "2000"))
            path = self._path(name, "-logcat.txt")
            path.write_text("\n".join(logs) or "No app process or matching crash entry was available.\n")
            return str(path)

        def private_files():
            self._require_test_app()
            archive = self._run(("exec-out", "run-as", self.package, "tar", "-cf", "-", "files", "shared_prefs", "databases"),
                                text=False, check=False)
            if not archive.stdout:
                raise RuntimeError(archive.stderr.decode("utf-8", errors="replace"))
            path = self._path(name, "-private-files.tar")
            path.write_bytes(archive.stdout)
            self._path(name, "-private-files-status.json").write_text(json.dumps({
                "returncode": archive.returncode,
                "stderr": archive.stderr.decode("utf-8", errors="replace"),
                "package": self.package,
            }, indent=2) + "\n")
            documents = []
            with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as contents:
                for member in contents.getmembers():
                    if not member.isfile() or member.size > 20 * 1024 * 1024 or not member.name.endswith((".workspace", ".circ")):
                        continue
                    stream = contents.extractfile(member)
                    if stream is None:
                        continue
                    payload = stream.read().decode("utf-8", errors="replace")
                    start = payload.find("<?xml")
                    if start < 0:
                        start = payload.find("<project")
                    end = payload.find("</project>", max(start, 0))
                    if start < 0 or end < 0:
                        continue
                    xml = payload[start:end + len("</project>")]
                    try:
                        ET.fromstring(xml)
                    except ET.ParseError:
                        continue
                    destination = self._path(name, f"-checkpoint-{len(documents) + 1}.circ")
                    destination.write_text(xml)
                    documents.append({"archive_member": member.name, "document": str(destination)})
            self._path(name, "-checkpoint-documents.json").write_text(json.dumps(documents, indent=2) + "\n")
            return str(path)

        def anr():
            details = self.adb("shell", "dumpsys", "activity", "lastanr")
            path = self._path(name, "-last-anr.txt")
            if f"packageName={self.package} " in details:
                path.write_text(details.split("  ResumedActivity:", 1)[0])
            else:
                path.write_text("No recorded ANR belongs to this isolated application.\n")
            return str(path)

        collect("screenshot", screenshot)
        collect("hierarchy", hierarchy)
        collect("logcat", logcat)
        if self.package.endswith(".e2e"):
            collect("anr", anr)
            collect("private_files", private_files)
        else:
            result["collection_errors"]["private_files"] = "Skipped: private-data capture is restricted to .e2e packages"
        report = self._path(name, "-artifacts.json")
        report.write_text(json.dumps(result, indent=2) + "\n")
        return result
