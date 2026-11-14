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


class IncompleteSnapshotTree(AssertionError):
    def __init__(self, message, app_pid):
        super().__init__(message)
        self.app_pid = app_pid


class SnapshotRootUnavailable(AssertionError):
    def __init__(self, message, app_pid):
        super().__init__(message)
        self.app_pid = app_pid


class TransientLayoutSnapshot(AssertionError):
    """A complete traversal taken while the app window had no laid-out bounds."""

    def __init__(self, message, app_pid):
        super().__init__(message)
        self.app_pid = app_pid


class AndroidUi:
    def __init__(self, adb_path, serial, package, output, *, focus_snapshots=False,
                 preserve_accessibility_services=False):
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
        if preserve_accessibility_services and not package.endswith(".e2e"):
            raise ValueError("Accessibility-preserving snapshots require an isolated .e2e app and its helper APK")
        self._focus_snapshots = bool(focus_snapshots)
        self._preserve_accessibility_services = bool(preserve_accessibility_services)
        self._focused_snapshot_active = False
        self._snapshot_uptime = -1
        self._snapshot_count = 0
        self.snapshot_package = package.removesuffix(".e2e") + ".snapshot.e2e.test"

    def _run(self, args, *, text=True, timeout=30, check=True):
        command = [self.adb_path, "-s", self.serial, *map(str, args)]
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=text, timeout=timeout, check=False)
        if check and result.returncode:
            stderr = result.stderr if text else result.stderr.decode("utf-8", errors="replace")
            raise RuntimeError(f"adb failed ({result.returncode}): {command!r}\n{stderr[-4000:]}")
        return result

    def adb(self, *args, text=True, timeout=30):
        return self._run(args, text=text, timeout=timeout).stdout

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
        """Read a fresh hierarchy; preserving services requires the app's SDK observer only."""
        if self._preserve_accessibility_services or self._focused_snapshot_active:
            deadline = time.monotonic() + timeout
            expected_pid = None
            root_unavailable = False
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError("No complete focus snapshot within its existing hierarchy deadline")
                top = self._run(("shell", "dumpsys", "activity", "activities"), timeout=min(5, remaining)).stdout
                resumed = re.findall(r"(?:topResumedActivity|mResumedActivity).*?\bu\d+\s+([A-Za-z0-9_.]+)/", top)
                if not resumed:
                    raise AssertionError("Cannot confirm the foreground package before a focus snapshot")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError("No complete focus snapshot within its existing hierarchy deadline")
                if resumed[0] == self.package:
                    try:
                        root = self._screen_instrumentation(min(8, remaining), expected_pid=expected_pid,
                                                            absolute_deadline=deadline)
                    except (IncompleteSnapshotTree, TransientLayoutSnapshot) as error:
                        expected_pid = error.app_pid
                        continue
                    except SnapshotRootUnavailable as error:
                        expected_pid = error.app_pid
                        root_unavailable = True
                        continue
                    self._focused_snapshot_active = any(node.get("package") == self.package
                        and node.get("class", "").endswith("EditText") and node.get("focused") == "true"
                        for node in root.iter("node"))
                    return root
                if self._preserve_accessibility_services:
                    raise AssertionError("Accessibility-preserving snapshot requires the app in the foreground")
                if root_unavailable:
                    raise AssertionError("Foreground package changed while recovering an unavailable focus snapshot root")
                # External activities retain the established CLI observation path.
                self._focused_snapshot_active = False
                timeout = remaining
                break
        return self._screen_uiautomator(timeout)

    @staticmethod
    def _validate_snapshot_protocol(stdout, stderr):
        terminals = list(re.finditer(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)[ \t\r]*$", stdout))
        if len(terminals) != 1:
            raise AssertionError("Focus snapshot must return exactly one terminal instrumentation code")
        if re.search(r"(?m)^\s*INSTRUMENTATION_", stdout[terminals[0].end():]):
            raise AssertionError("Focus snapshot protocol continued after its terminal result")
        for channel in (stdout, stderr):
            if re.search(r"(?m)^\s*INSTRUMENTATION_(?:FAILED|ABORTED)\b", channel) or \
                    re.search(r"(?m)^\s*INSTRUMENTATION_(?:RESULT|STATUS):\s*(?:shortMsg|longMsg|Error)\s*=", channel):
                raise AssertionError("Focus snapshot instrumentation reported a failure or crash")

    @staticmethod
    def _validate_root_unavailable_snapshot(values):
        forbidden = {"snapshotXmlBase64", "snapshotUptimeMillis", "snapshotRotation", "snapshotCaptureDiagnosticsError"}
        if forbidden.intersection(values) or any(key.startswith("snapshotLastIncomplete") for key in values):
            raise AssertionError("Unavailable root returned partial XML, success metadata or incomplete-tree diagnostics")
        fixed = {"snapshotCaptureState": "ROOT_UNAVAILABLE", "snapshotRootSource": "unavailable",
                 "snapshotRootWindowId": "-1", "snapshotTraversalNodeCount": "0",
                 "snapshotCaptureAttempts": "1", "snapshotIncompleteTreeAttempts": "0",
                 "snapshotCaptureAttemptRecordsDropped": "0"}
        if any(values.get(key) != expected for key, expected in fixed.items()):
            raise AssertionError("Inconsistent unavailable-root capture state")
        numeric = {"snapshotRootAttempts": 1, "snapshotRootWaitMillis": 0,
                   "snapshotCaptureElapsedMillis": 1000, "snapshotCaptureOverrunMillis": 0}
        parsed = {}
        for key, minimum in numeric.items():
            raw = values.get(key, "")
            if not re.fullmatch(r"\d+", raw) or int(raw) < minimum:
                raise AssertionError("Invalid unavailable-root metadata: " + key)
            parsed[key] = int(raw)
        elapsed = parsed["snapshotCaptureElapsedMillis"]
        if parsed["snapshotRootWaitMillis"] > elapsed or parsed["snapshotCaptureOverrunMillis"] != elapsed - 1000:
            raise AssertionError("Inconsistent unavailable-root capture timing")
        expected_error = ("RootUnavailableException: UiAutomation returned no active root or active/focused "
                          "application window root for " + values["snapshotExpectedPackage"]
                          + " before the capture retry deadline")
        if values.get("snapshotError") != expected_error:
            raise AssertionError("Unavailable-root capture does not contain its typed error")
        try:
            records = json.loads(base64.b64decode(values.get("snapshotCaptureAttemptRecordsBase64", ""),
                                                  validate=True).decode("utf-8"))
        except (ValueError, UnicodeError) as error:
            raise AssertionError("Invalid unavailable-root attempt records") from error
        if not isinstance(records, list) or len(records) != 1 or not isinstance(records[0], dict):
            raise AssertionError("Unavailable root requires one retained capture attempt")
        record = records[0]
        if type(record.get("attempt")) is not int or record["attempt"] != 1 or \
                type(record.get("startedUptimeMillis")) is not int or record["startedUptimeMillis"] < 0 or \
                type(record.get("elapsedMillis")) is not int or record["elapsedMillis"] != elapsed or \
                record.get("state") != "ROOT_UNAVAILABLE" or record.get("error") != expected_error or \
                any(key in record for key in ("parentPath", "childIndex", "parentChildCount", "windowId", "nodeCount")):
            raise AssertionError("Inconsistent unavailable-root attempt record")

    @staticmethod
    def _validate_incomplete_snapshot(values):
        if "snapshotXmlBase64" in values or "snapshotCaptureDiagnosticsError" in values:
            raise AssertionError("Incomplete snapshot returned partial XML or invalid diagnostics")
        numeric = {"snapshotRootAttempts": 1, "snapshotRootWaitMillis": 0, "snapshotRootWindowId": 0,
                   "snapshotCaptureAttempts": 1, "snapshotCaptureElapsedMillis": 1000,
                   "snapshotCaptureOverrunMillis": 0, "snapshotIncompleteTreeAttempts": 1,
                   "snapshotTraversalNodeCount": 1, "snapshotCaptureAttemptRecordsDropped": 0,
                   "snapshotLastIncompleteChildIndex": 0, "snapshotLastIncompleteParentChildCount": 1,
                   "snapshotLastIncompleteWindowId": 0, "snapshotLastIncompleteNodeCount": 1}
        parsed = {}
        for key, minimum in numeric.items():
            raw = values.get(key, "")
            if not re.fullmatch(r"\d+", raw) or int(raw) < minimum:
                raise AssertionError("Invalid incomplete snapshot metadata: " + key)
            parsed[key] = int(raw)
        if values.get("snapshotRootSource") not in {"active-window", "window-active", "window-focused"} or \
                parsed["snapshotIncompleteTreeAttempts"] != parsed["snapshotCaptureAttempts"] or \
                parsed["snapshotRootAttempts"] < parsed["snapshotCaptureAttempts"] or \
                parsed["snapshotRootWaitMillis"] > parsed["snapshotCaptureElapsedMillis"] or \
                parsed["snapshotCaptureOverrunMillis"] != max(0, parsed["snapshotCaptureElapsedMillis"] - 1000):
            raise AssertionError("Inconsistent incomplete snapshot state or timing")
        try:
            records = json.loads(base64.b64decode(values.get("snapshotCaptureAttemptRecordsBase64", ""),
                                                  validate=True).decode("utf-8"))
        except (ValueError, UnicodeError) as error:
            raise AssertionError("Invalid incomplete snapshot attempt records") from error
        dropped = parsed["snapshotCaptureAttemptRecordsDropped"]
        if not isinstance(records, list) or not 1 <= len(records) <= 16 or \
                len(records) + dropped != parsed["snapshotCaptureAttempts"]:
            raise AssertionError("Invalid incomplete snapshot attempt counts")
        first_start = None
        previous_end = -1
        retained_elapsed = 0
        for index, record in enumerate(records):
            if not isinstance(record, dict) or record.get("state") != "INCOMPLETE_TREE":
                raise AssertionError("Invalid incomplete snapshot attempt state")
            for key, minimum in (("attempt", 1), ("startedUptimeMillis", 0), ("elapsedMillis", 0),
                                 ("childIndex", 0), ("parentChildCount", 1), ("windowId", 0), ("nodeCount", 1)):
                if type(record.get(key)) is not int or record[key] < minimum:
                    raise AssertionError("Invalid incomplete snapshot attempt metadata: " + key)
            if record["attempt"] != dropped + index + 1 or record["startedUptimeMillis"] < previous_end or \
                    record["childIndex"] >= record["parentChildCount"] or \
                    not isinstance(record.get("parentPath"), str) or \
                    not re.fullmatch(r"0(?:/\d+)*", record["parentPath"]) or \
                    not isinstance(record.get("error"), str) or not record["error"].startswith("IncompleteTreeException: "):
                raise AssertionError("Inconsistent incomplete snapshot attempt context")
            if first_start is None:
                first_start = record["startedUptimeMillis"]
            previous_end = record["startedUptimeMillis"] + record["elapsedMillis"]
            retained_elapsed += record["elapsedMillis"]
        if retained_elapsed > parsed["snapshotCaptureElapsedMillis"] or \
                previous_end - first_start > parsed["snapshotCaptureElapsedMillis"]:
            raise AssertionError("Incomplete snapshot attempt timings exceed its capture time")
        last = records[-1]
        context = {"snapshotLastIncompleteParentPath": "parentPath", "snapshotLastIncompleteChildIndex": "childIndex",
                   "snapshotLastIncompleteParentChildCount": "parentChildCount", "snapshotLastIncompleteWindowId": "windowId",
                   "snapshotLastIncompleteNodeCount": "nodeCount", "snapshotTraversalNodeCount": "nodeCount"}
        if values.get("snapshotError") != last["error"] or any(
                (parsed[key] if key in parsed else values.get(key)) != last[field] for key, field in context.items()):
            raise AssertionError("Incomplete snapshot does not match its final attempt")

    def _screen_instrumentation(self, timeout, *, expected_pid=None, absolute_deadline=None):
        deadline = absolute_deadline if absolute_deadline is not None else time.monotonic() + timeout
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
            "expectedScreenPid": expected_pid,
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

        if expected_pid is not None and before != expected_pid:
            error = AssertionError("Snapshot retry changed the app process")
            save_receipt("rejected", str(error))
            raise error
        try:
            receipt["instrumentationTimeoutSeconds"] = remaining(min(8, timeout))
            response = self._run(("shell", "am", "instrument", "-w", "-r", "-e", "snapshotNonce", nonce,
                                  "-e", "snapshotExpectedPackage", self.package, component),
                                 timeout=receipt["instrumentationTimeoutSeconds"], check=False)
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
            self._validate_snapshot_protocol(result, stderr)
            if values.get("snapshotNonce") != nonce:
                raise AssertionError("Focus snapshot returned an old or missing nonce")
            if values.get("snapshotExpectedPackage") != self.package:
                raise AssertionError("Focus snapshot returned a different or missing expected package")
            codes = receipt["instrumentationCodes"]
            if codes not in (["-1"], ["0"]):
                raise AssertionError("Focus snapshot returned invalid instrumentation codes")
            if "snapshotError" in values:
                if codes == ["0"] and values.get("snapshotCaptureState") == "ROOT_UNAVAILABLE":
                    self._validate_root_unavailable_snapshot(values)
                    receipt["validatedRootUnavailable"] = True
                    raise SnapshotRootUnavailable("Focus snapshot root unavailable: " + values["snapshotError"], before)
                if codes == ["0"] and values.get("snapshotCaptureState") == "INCOMPLETE_TREE" and \
                        values["snapshotError"].startswith("IncompleteTreeException: "):
                    self._validate_incomplete_snapshot(values)
                    receipt["validatedIncompleteTree"] = True
                    raise IncompleteSnapshotTree("Focus snapshot incomplete: " + values["snapshotError"], before)
                raise AssertionError("Focus snapshot failed: " + values["snapshotError"])
            if codes != ["-1"] or values.get("snapshotCaptureState", "COMPLETE") != "COMPLETE":
                raise AssertionError("Focus snapshot failed or returned a non-complete capture state")
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
            # Mid-relayout, the accessibility tree can report the app window
            # itself as [0,0][0,0] while children keep screen bounds. That is
            # not an observable screen; read again with the same process.
            empty = [node.get("bounds") for node in root if node.get("package") == self.package
                     and self._rectangle(node) is None]
            if empty:
                raise TransientLayoutSnapshot("Focus snapshot app window had no bounds: " + empty[0], before)
        except IncompleteSnapshotTree as error:
            save_receipt("incomplete-tree", str(error))
            raise
        except TransientLayoutSnapshot as error:
            save_receipt("transient-layout", str(error))
            raise
        except SnapshotRootUnavailable as error:
            save_receipt("root-unavailable", str(error))
            raise
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

    def inspector(self, root):
        """The open property inspector: a modal sheet, or the panel docked beside a wide canvas."""
        for tag in ("editor-inspector-sheet", "editor-inspector-inline"):
            panel = self.tagged(root, tag)
            if panel is not None:
                return panel, tag
        return None, None

    def dismiss_inspector(self, root=None):
        """Back closes the inspector sheet. The docked inspector stays, and Back would leave the app."""
        _, tag = self.inspector(self.screen() if root is None else root)
        if tag == "editor-inspector-sheet":
            self.key(4)
        return tag

    HEADER = "editor-project-toolbar"
    MENU_TITLE = "menu-title-"
    MENU_POPUP = "editor-menu-"
    # The File and Project menus carry these tags instead of the generic popup tags.
    ITEM_MENUS = ("editor-file-menu", "editor-tool-menu")

    def toolbar_action(self, toolbars, *, matches, timeout=30, root=None, menu=None):
        """Navigate as `_toolbar_action`; keep its observations when navigation fails."""
        trace, saved = [], {name: self.__dict__[name] for name in ("screen", "_run") if name in self.__dict__}
        screen, run = self.screen, self._run

        def observed_screen(*args, **kwargs):
            started = time.monotonic()
            current = screen(*args, **kwargs)
            trace.append({"screenSeconds": round(time.monotonic() - started, 2), "tags": [
                node.get("resource-id") for node in current.iter("node")
                if node.get("package") == self.package and node.get("resource-id", "").startswith(("editor-", "menu-"))]})
            return current

        def observed_run(args, **kwargs):
            if tuple(args[:2]) == ("shell", "input"):
                trace.append({"input": list(args[2:])})
            return run(args, **kwargs)

        self.screen, self._run = observed_screen, observed_run
        try:
            return self._toolbar_action(toolbars, matches=matches, timeout=timeout, root=root, menu=menu)
        except AssertionError as error:
            output = getattr(self, "output", None)
            if output is None:
                raise
            path = output / f"toolbar-action-{uuid.uuid4().hex[:8]}.json"
            path.write_text(json.dumps({"toolbars": list(toolbars) if not isinstance(toolbars, str) else [toolbars],
                                        "menu": menu, "error": str(error), "trace": trace}, indent=2) + "\n")
            raise
        finally:
            for name in ("screen", "_run"):
                if name in saved:
                    setattr(self, name, saved[name])
                else:
                    self.__dict__.pop(name, None)

    def _toolbar_action(self, toolbars, *, matches, timeout=30, root=None, menu=None):
        """Return a reachable action, opening only an allowed toolbar's overflow.

        None permits a caller's legacy scrolling route when no allowed route
        exposes an overflow or menu. An open menu owns the entire search.

        The Evolution-style chrome adds three routes: an overflow that expands
        its toolbar in place, the header's menu bar (each title opens an
        ``editor-menu-<id>`` popup), and the compact header menu whose titles
        drill down inside ``editor-project-toolbar-menu``. Icon-only buttons
        expose their name as content-desc, so a text predicate also matches it.
        """
        preferred = menu
        toolbars = (toolbars,) if isinstance(toolbars, str) else tuple(toolbars)
        if not toolbars or len(set(toolbars)) != len(toolbars) or not all(isinstance(value, str) and value for value in toolbars):
            raise ValueError("Provide unique toolbar tag identifiers in route order")
        if not callable(matches):
            raise ValueError("Provide an exact action predicate")
        if not 0 < timeout <= 30:
            raise ValueError("Toolbar navigation timeout must be greater than zero and at most 30 seconds")
        deadline = time.monotonic() + timeout
        known = set(toolbars) | {self.HEADER, "editor-command-toolbar", "editor-simulation-toolbar"}
        exact = matches

        def matches(node):
            if exact(node):
                return True
            if node.get("text") or not node.get("content-desc"):
                return False
            alias = ET.Element(node.tag, dict(node.attrib))
            alias.set("text", node.get("content-desc"))
            return bool(exact(alias))

        def remaining():
            value = deadline - time.monotonic()
            if value <= 0:
                raise AssertionError("Toolbar navigation exceeded its deadline")
            return value

        def fresh():
            return self.screen(timeout=remaining())

        def press(node):
            self._require_test_app()
            left, top, right, bottom = self._bounds(node)
            self._run(("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2)), timeout=remaining())

        def dismiss():
            self._require_test_app()
            self._run(("shell", "input", "keyevent", "4"), timeout=remaining())

        def menu_owner(current):
            identifiers = {node.get("resource-id") for node in current.iter("node")
                           if node.get("package") == self.package
                           and node.get("resource-id", "").endswith("-toolbar-menu")}
            identifiers.update(toolbar + "-menu" for toolbar in known)
            opened = [(identifier, self.tagged(current, identifier)) for identifier in sorted(identifiers)]
            opened = [(identifier, menu) for identifier, menu in opened if menu is not None]
            if len(opened) > 1:
                raise AssertionError(f"Ambiguous open toolbar menus: {[identifier for identifier, _ in opened]}")
            if opened and opened[0][0] not in {toolbar + "-menu" for toolbar in toolbars} \
                    and opened[0][0] != self.HEADER + "-menu":
                raise AssertionError(f"Another toolbar menu is already open: {opened[0][0]}")
            return opened[0] if opened else None

        def popup_owner(current):
            popups = [node.get("resource-id") for node in current.iter("node")
                      if node.get("package") == self.package
                      and (node.get("resource-id", "").startswith(self.MENU_POPUP)
                           or node.get("resource-id") in self.ITEM_MENUS)]
            if len(popups) > 1:
                raise AssertionError(f"Ambiguous open menus: {popups}")
            return popups[0] if popups else None

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

        def fits(current, menu):
            """Whether every item lies inside the menu viewport, so scrolling reveals nothing."""
            left, top, right, bottom = self._bounds(menu)
            items = [self._bounds(node) for node in menu.iter("node") if node is not menu and node.get("bounds")]
            if menu.get("scrollable") == "true" and items and max(item[3] for item in items) >= bottom - 16:
                # A scrolling popup whose last item reaches its lower edge omits the items
                # below it from the tree; the bounds of the visible ones still fit.
                return False
            for node in menu.iter("node"):
                if node is menu or not node.get("bounds"):
                    continue
                inner = self._bounds(node)
                if inner[0] < left or inner[1] < top or inner[2] > right or inner[3] > bottom:
                    return False
            return True

        def clipped(menu, node):
            """Whether a scrolling menu may cut this item at its viewport edge.

            UIAutomator reports only the visible part of a partly scrolled item,
            so a sliver at the edge still looks whole inside the menu bounds.
            """
            if menu.get("scrollable") != "true":
                return False
            _, top, _, bottom = self._bounds(menu)
            _, item_top, _, item_bottom = self._bounds(node)
            return item_bottom >= bottom - 1 or item_top <= top + 1

        def scan(current, identifier):
            """Search one menu viewport, scrolling it in both directions."""
            swiped = False
            for reverse in (False, True):
                previous = None
                edge = None
                for attempt in range(9):
                    remaining()
                    menu = self.tagged(current, identifier)
                    if menu is None:
                        raise AssertionError(f"The active toolbar menu disappeared or changed: {identifier}")
                    node = target(current, menu)
                    if node is not None and self.visible_node(current, node, within=menu):
                        if not clipped(menu, node):
                            if not swiped:
                                return node, current
                            # After a swipe the list can still be flinging, and a tap on it then only stops it
                            # (layout12's long File menu): return the item once a second frame shows it still.
                            following = fresh()
                            again = self.tagged(following, identifier)
                            still = target(following, again) if again is not None else None
                            if still is not None and still.get("bounds") == node.get("bounds"):
                                return still, following
                            current, swiped = following, True
                            continue
                        # Scroll it inside the viewport; an item that no longer moves is whole.
                        edge = node
                        _, top, _, bottom = self._bounds(menu)
                        if reverse != (self._bounds(node)[1] <= top + 1):
                            break
                    # Legacy dialog menus keep scrolling; the chrome's popups list every item they hold.
                    modern = identifier.startswith(self.MENU_POPUP) or identifier in self.ITEM_MENUS or titles(menu) \
                        or any(node.get("resource-id") == "menu-back" for node in menu.iter("node"))
                    if node is None and attempt == 0 and not reverse and modern and fits(current, menu):
                        return None, current
                    if not self.visible_node(current, menu) or menu.get("enabled") == "false":
                        raise AssertionError(f"Toolbar menu has no visible scrolling bounds: {identifier}")
                    signature = ET.tostring(menu, encoding="unicode")
                    if edge is not None and signature == previous:
                        return edge, current
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
                    swiped = True
            return None, current

        def wait_for(predicate):
            while True:
                current = fresh()
                if predicate(current):
                    return current
                time.sleep(min(0.1, remaining()))

        def titles(scope):
            return [node.get("resource-id") for node in scope.iter("node")
                    if node.get("package") == self.package and node.get("resource-id", "").startswith(self.MENU_TITLE)]

        def menu_bar(current):
            """Open each header menu title in turn; return a matching item with its menu open."""
            header = self.tagged(current, self.HEADER)
            for title in titles(header) if header is not None else ():
                press(control(current, self.tagged(current, title), header, "Menu title"))
                current = wait_for(lambda frame: popup_owner(frame) is not None)
                node, current = scan(current, popup_owner(current))
                if node is not None:
                    return node
                dismiss()
                current = wait_for(lambda frame: popup_owner(frame) is None)
            overflow = self.tagged(current, self.HEADER + "-overflow")
            if header is not None and overflow is not None and not titles(header):
                # Narrow windows fold the menu bar into the header's compact menu.
                menu_id = self.HEADER + "-menu"
                press(control(current, overflow, header, "Toolbar overflow"))
                current = wait_for(lambda frame: self.tagged(frame, menu_id) is not None)
                node, current = scan(current, menu_id)
                return node if node is not None else drill_down(current, menu_id)
            return None

        def drill_down(current, menu_id):
            """Search the compact header menu's submenus, reopening it between them."""
            menu = self.tagged(current, menu_id)
            # The caller may name the submenu that holds the action (a menu-title-* id); search it first.
            order = titles(menu) if menu is not None else []
            order = sorted(order, key=lambda title: title != preferred)
            for title in order:
                item = self.tagged(current, title)
                if item is None:
                    continue
                press(control(current, item, menu, "Menu title"))
                current = wait_for(lambda frame: self.tagged(frame, title) is None and (
                    self.tagged(frame, menu_id) is not None or popup_owner(frame) is not None))
                node, current = scan(current, menu_id if self.tagged(current, menu_id) is not None else popup_owner(current))
                if node is not None:
                    return node
                back = self.tagged(current, "menu-back")
                if back is not None:
                    # Return to the submenu titles inside the same popup.
                    press(control(current, back, current, "Menu back"))
                    current = wait_for(lambda frame: self.tagged(frame, menu_id) is not None
                                       and self.tagged(frame, title) is not None)
                    continue
                dismiss()
                current = wait_for(lambda frame: self.tagged(frame, menu_id) is None and popup_owner(frame) is None)
                overflow = self.tagged(current, self.HEADER + "-overflow")
                if overflow is None:
                    break
                press(control(current, overflow, self.tagged(current, self.HEADER), "Toolbar overflow"))
                current = wait_for(lambda frame: self.tagged(frame, menu_id) is not None)
            return None

        current = root if root is not None else fresh()
        popup = popup_owner(current)
        if popup is not None:
            node, current = scan(current, popup)
            if node is not None:
                return node
            dismiss()
            current = wait_for(lambda frame: popup_owner(frame) is None)
        opened = menu_owner(current)
        if opened is None:
            routes = [(identifier, self.tagged(current, identifier)) for identifier in toolbars]
            direct = [(identifier, bar, target(current, bar)) for identifier, bar in routes if bar is not None]
            candidates = []
            for identifier, bar, node in direct:
                # The simulation controls may sit inside the tool toolbar: one control, two routes.
                if node is not None and not any(node is other for _, _, other in candidates):
                    candidates.append((identifier, bar, node))
            if len(candidates) > 1:
                raise AssertionError(f"Ambiguous toolbar action across routes: {[identifier for identifier, _, _ in candidates]}")
            # A nested simulation row can be drawn a few pixels shorter than its buttons;
            # the control is reachable when one of the toolbars holding it shows it whole.
            if candidates and any(other is candidates[0][2] and self.visible_node(current, other, within=bar)
                                  for _, bar, other in direct):
                return candidates[0][2]
            if not candidates and self.HEADER not in toolbars:
                # Library and properties buttons moved into the header on narrow windows.
                header = self.tagged(current, self.HEADER)
                node = target(current, header) if header is not None else None
                if node is not None and self.visible_node(current, node, within=header):
                    return node
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
                before = ET.tostring(bar, encoding="unicode")
                press(overflow)
                expected = identifier + "-menu"
                expanded = None
                while True:
                    current = fresh()
                    opened = menu_owner(current)
                    if opened is not None:
                        if opened[0] != expected:
                            raise AssertionError(f"Overflow opened inconsistent menu: expected {expected}, found {opened[0]}")
                        break
                    after = self.tagged(current, identifier)
                    if after is not None and self.tagged(current, identifier + "-overflow") is not None \
                            and ET.tostring(after, encoding="unicode") != before:
                        expanded = after
                        break
                    time.sleep(min(0.1, remaining()))
                if expanded is None:
                    break
                # The toolbar unfolded into extra rows in place.
                node = target(current, expanded)
                if node is not None and self.visible_node(current, node, within=expanded):
                    return node
                # Fold the rows back before searching the menus: left open they shrink the
                # canvas, and a later fit or zoom would measure that transient layout.
                fold = self.tagged(current, identifier + "-overflow")
                if fold is not None:
                    press(control(current, fold, expanded, "Toolbar overflow"))
                    current = wait_for(lambda frame: self.tagged(frame, identifier) is not None
                                       and ET.tostring(self.tagged(frame, identifier), encoding="unicode") != ET.tostring(expanded, encoding="unicode")
                                       and self.tagged(frame, identifier + "-overflow") is not None)
            else:
                return menu_bar(current)
            if opened is None:
                return menu_bar(current)

        menu_id = opened[0]
        node, current = scan(current, menu_id)
        if node is not None:
            return node
        if menu_id == self.HEADER + "-menu":
            node = drill_down(current, menu_id)
            if node is not None:
                return node
        raise UiNodeNotFound(f"Action was not reachable in the active toolbar menu: {menu_id}")

    @staticmethod
    def overlay_touch_regions(windows, title):
        """Touchable rectangles of a visible system window from ``dumpsys window windows``."""
        regions = []
        for section in re.split(r"\n(?=  Window #)", windows):
            if not re.match(r"\s*Window #\d+ Window\{\S+ u\d+ " + re.escape(title) + r"\}:", section):
                continue
            if "isVisible=true" not in section:
                continue
            match = re.search(r"touchable region=SkRegion\(((?:\(-?\d+,-?\d+,-?\d+,-?\d+\))*)\)", section)
            if match is None:
                continue
            regions.extend(tuple(map(int, rect)) for rect in
                           re.findall(r"\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)", match.group(1)))
        return regions

    def wait_overlay_clear(self, node, *, title="ClipboardOverlay", timeout=20):
        """Wait while a system overlay intercepts touches at a control's tap point.

        Android shows its clipboard editor above the app after a copy. On small
        windows it covers dialog buttons until it hides by itself.
        """
        left, top, right, bottom = self._bounds(node)
        x, y = (left + right) // 2, (top + bottom) // 2
        deadline = time.monotonic() + timeout
        while True:
            windows = self._run(("shell", "dumpsys", "window", "windows"), timeout=10).stdout
            covering = [rect for rect in self.overlay_touch_regions(windows, title)
                        if rect[0] <= x < rect[2] and rect[1] <= y < rect[3]]
            if not covering:
                return
            if time.monotonic() >= deadline:
                raise AssertionError(f"{title} kept covering the control at {x},{y} for {timeout}s")
            time.sleep(0.5)

    def clear_download(self, name):
        """Remove a stale Downloads file so CreateDocument writes this exact name, not "name (1)".

        Downloads outlives app data, and runner filenames repeat across runs that reuse an
        output folder name. Reading the name afterwards must observe this run's bytes.
        """
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name):
            raise ValueError("Download names must be simple filenames")
        remote = "/sdcard/Download/" + name
        self.adb("shell", "rm", "-f", remote)
        listed = self._run(("shell", "ls", remote), check=False)
        if listed.returncode == 0:
            raise AssertionError(f"Could not remove the stale download {name}")

    def tap(self, node, *, timeout=30):
        self._require_test_app()
        if node.get("enabled") == "false":
            raise AssertionError(f"Control is disabled: {node.attrib}")
        left, top, right, bottom = self._bounds(node)
        self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2), timeout=timeout)

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
        # Each focused read starts a separate instrumentation (0.5-3 s on a loaded host), and a read
        # during a sheet's opening transition finds no active root and is retried. Allow several reads.
        deadline = time.monotonic() + 20
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
