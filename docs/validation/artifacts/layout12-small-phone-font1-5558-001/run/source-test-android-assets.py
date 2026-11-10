#!/usr/bin/env python3
"""Verify native binary file operations in the isolated Android .e2e fixture."""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import struct
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zlib

from android_ui import AndroidUi

ROOT = Path(__file__).resolve().parents[1]
CASE_NAMES = ("opens", "cancellations", "exports", "shares", "recovery", "print")


def digest(content):
    return hashlib.sha256(content).hexdigest()


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAXIMUM_PNG_BYTES = 128 * 1024 * 1024
MAXIMUM_DECODED_BYTES = 64 * 1024 * 1024
FIXTURE_BLUE = (38, 99, 235)


def decode_android_png(path):
    """Return (width, height, channels, pixels) for an 8-bit RGB/RGBA noninterlaced PNG."""
    path = Path(path)
    if path.stat().st_size > MAXIMUM_PNG_BYTES:
        raise ValueError("PNG exceeds the input-size limit")
    encoded = path.read_bytes()
    if not encoded.startswith(PNG_SIGNATURE):
        raise ValueError("Invalid PNG signature")
    offset = len(PNG_SIGNATURE)
    header = None
    compressed = bytearray()
    saw_data = False
    data_ended = False
    saw_end = False
    while offset < len(encoded):
        if len(encoded) - offset < 12:
            raise ValueError("Truncated PNG chunk header")
        length, kind = struct.unpack_from(">I4s", encoded, offset)
        end = offset + 12 + length
        if end > len(encoded):
            raise ValueError("Truncated PNG chunk body")
        payload = encoded[offset + 8:offset + 8 + length]
        checksum = struct.unpack_from(">I", encoded, offset + 8 + length)[0]
        if zlib.crc32(kind + payload) & 0xffffffff != checksum:
            raise ValueError("PNG chunk CRC mismatch")
        if header is None and kind != b"IHDR":
            raise ValueError("IHDR must be the first PNG chunk")
        if kind == b"IHDR":
            if header is not None or length != 13:
                raise ValueError("Invalid or duplicate IHDR")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            if width < 1 or height < 1 or depth != 8 or color not in (2, 6):
                raise ValueError("Only nonempty 8-bit RGB/RGBA PNG images are supported")
            if compression != 0 or filtering != 0 or interlace != 0:
                raise ValueError("Unsupported PNG compression, filtering, or interlace method")
            channels = 3 if color == 2 else 4
            stride = width * channels
            expected = height * (stride + 1)
            if expected > MAXIMUM_DECODED_BYTES:
                raise ValueError("PNG exceeds the decoded-size limit")
            header = (width, height, channels, stride, expected)
        elif kind == b"IDAT":
            if data_ended:
                raise ValueError("IDAT chunks must be consecutive")
            saw_data = True
            compressed.extend(payload)
        elif kind == b"IEND":
            if length != 0 or not saw_data or end != len(encoded):
                raise ValueError("Invalid IEND or trailing PNG data")
            saw_end = True
            break
        else:
            if saw_data:
                data_ended = True
            if kind != b"PLTE" and not (kind[0] & 32):
                raise ValueError("Unsupported critical PNG chunk")
        offset = end
    if header is None or not saw_data or not saw_end:
        raise ValueError("PNG is missing a required chunk")
    width, height, channels, stride, expected = header
    decoder = zlib.decompressobj()
    try:
        filtered = decoder.decompress(compressed, expected + 1)
    except zlib.error as error:
        raise ValueError("Invalid PNG zlib stream") from error
    if len(filtered) != expected or not decoder.eof or decoder.unconsumed_tail or decoder.unused_data:
        raise ValueError("PNG scanline length or compressed-stream boundary mismatch")
    pixels = bytearray()
    previous = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        method = filtered[start]
        row = bytearray(filtered[start + 1:start + 1 + stride])
        if method > 4:
            raise ValueError("Invalid PNG scanline filter")
        if method != 0:
            for index in range(stride):
                left = row[index - channels] if index >= channels else 0
                up = previous[index]
                upper_left = previous[index - channels] if index >= channels else 0
                if method == 1:
                    predictor = left
                elif method == 2:
                    predictor = up
                elif method == 3:
                    predictor = (left + up) // 2
                else:
                    p = left + up - upper_left
                    distance_left = abs(p - left)
                    distance_up = abs(p - up)
                    distance_corner = abs(p - upper_left)
                    if distance_left <= distance_up and distance_left <= distance_corner:
                        predictor = left
                    elif distance_up <= distance_corner:
                        predictor = up
                    else:
                        predictor = upper_left
                row[index] = (row[index] + predictor) & 255
        pixels.extend(row)
        previous = row
    return width, height, channels, bytes(pixels)


def print_preview_pixels(path, bounds, minimum_ratio=0.1):
    """Measure the fixture's blue rectangle inside a native preview page."""
    width, height, channels, pixels = decode_android_png(path)
    if len(bounds) != 4 or any(not isinstance(value, int) or isinstance(value, bool) for value in bounds):
        raise ValueError("Crop bounds must be four integer pixel coordinates")
    left, top, right, bottom = bounds
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise ValueError("Crop bounds are empty or outside the screenshot")
    if not 0 <= minimum_ratio <= 1:
        raise ValueError("Blue-pixel ratio must be between zero and one")
    blue = 0
    for y in range(top, bottom):
        for x in range(left, right):
            index = (y * width + x) * channels
            red, green, value_blue = pixels[index:index + 3]
            if channels == 4 and pixels[index + 3] != 255:
                alpha = pixels[index + 3]
                red = (red * alpha + 255 * (255 - alpha) + 127) // 255
                green = (green * alpha + 255 * (255 - alpha) + 127) // 255
                value_blue = (value_blue * alpha + 255 * (255 - alpha) + 127) // 255
            if value_blue > 160 and 2 * value_blue > 3 * red and 10 * value_blue > 13 * green:
                blue += 1
    total = (right - left) * (bottom - top)
    ratio = blue / total
    return {
        "screenshotSha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(),
        "width": width, "height": height, "channels": channels, "crop": list(bounds),
        "fixtureBlueRgb": list(FIXTURE_BLUE), "bluePixels": blue, "cropPixels": total,
        "blueRatio": ratio, "minimumRatio": minimum_ratio, "passed": ratio >= minimum_ratio,
        "criterion": "b > 160 and b > r * 1.5 and b > g * 1.3; RGBA composited on white",
    }


class NativeAssets:
    def __init__(self, ui, run_id):
        self.ui = ui
        self.run_id = run_id
        self.private = f"files/native-asset-fixture/{run_id}"
        self.manifest = None
        self.sources = {}
        self.report = []

    def command(self, *arguments, input_bytes=None, timeout=20, check=True):
        result = subprocess.run([self.ui.adb_path, "-s", self.ui.serial, *map(str, arguments)],
                                input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, check=False)
        if check and result.returncode:
            raise AssertionError(f"adb exited {result.returncode}: {arguments!r}\n"
                                 + result.stderr.decode("utf-8", errors="replace")[-3000:])
        return result

    def private_bytes(self, path, package=None, check=True):
        return self.command("exec-out", "run-as", package or self.ui.package, "cat", path, check=check).stdout

    def private_json(self, path, package=None):
        content = self.private_bytes(path, package)
        if len(content) > 1024 * 1024:
            raise AssertionError("The fixture's JSON protocol exceeded 1 MiB")
        return json.loads(content)

    def poll_json(self, path, predicate, *, timeout=30, package=None):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            try:
                value = self.private_json(path, package)
                last = value
                if predicate(value):
                    return value
            except (AssertionError, json.JSONDecodeError, UnicodeDecodeError) as error:
                last = str(error)
            time.sleep(0.2)
        raise AssertionError(f"Fixture protocol did not become ready: {path}; last={str(last)[:1500]}")

    def events(self):
        try:
            events = self.private_json(f"{self.private}/results.json")
        except (AssertionError, json.JSONDecodeError):
            return []
        if not isinstance(events, list) or not all(isinstance(item, dict) and "id" in item for item in events):
            raise AssertionError("Fixture results.json must be an event array with IDs")
        if len({str(item["id"]) for item in events}) != len(events):
            raise AssertionError("Fixture event IDs must be unique within the run")
        return events

    def await_event(self, operation, before, status, *, source="callback", timeout=30):
        deadline = time.monotonic() + timeout
        last = []
        while time.monotonic() < deadline:
            last = self.events()
            found = [item for item in last if str(item["id"]) not in before and item.get("operation") == operation]
            if found:
                if len(found) != 1:
                    raise AssertionError(f"Operation {operation} completed more than once: {found}")
                event = found[0]
                if event.get("status") != status:
                    raise AssertionError(f"Expected {operation}={status}, received {event}")
                if event.get("source") != source or event.get("mainThread") is not True:
                    raise AssertionError(f"Incorrect callback ownership or thread: {event}")
                (self.ui.output / "native-events.json").write_text(json.dumps(last, indent=2) + "\n")
                return event
            time.sleep(0.2)
        raise AssertionError(f"No fresh {operation}={status} event appeared; events={last}")

    def fixture_button(self, operation):
        deadline = time.monotonic() + 30
        for reverse in (False, True):
            previous = None
            while time.monotonic() < deadline:
                root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                node = self.ui.optional(root, description=f"fixture:{operation}")
                if node is not None and node.get("enabled") != "false":
                    return node
                scrolls = [item for item in root.iter("node") if item.get("scrollable") == "true"]
                if not scrolls:
                    time.sleep(0.2)
                    continue
                current = ET.tostring(root)
                if current == previous:
                    break
                previous = current
                left, top, right, bottom = self.ui._bounds(max(scrolls,
                    key=lambda item: (self.ui._bounds(item)[2] - self.ui._bounds(item)[0])
                    * (self.ui._bounds(item)[3] - self.ui._bounds(item)[1])))
                start, end = (bottom - 50, top + 50) if not reverse else (top + 50, bottom - 50)
                self.ui.adb("shell", "input", "swipe", str((left + right) // 2), str(start),
                            str((left + right) // 2), str(end), "250")
        raise AssertionError(f"The fixture button {operation} did not become reachable and enabled")

    def begin(self, operation):
        before = {str(item["id"]) for item in self.events()}
        self.ui.tap(self.fixture_button(operation))
        return before

    def run(self, name, action):
        started = time.monotonic()
        try:
            result = action()
            self.ui.capture(name)
            self.report.append({"scenario": name, "status": "passed", "seconds": round(time.monotonic() - started, 2),
                                "result": result})
            print(f"PASS: {name}", flush=True)
        except Exception as error:
            self.ui.fail_artifacts(name, error)
            self.report.append({"scenario": name, "status": "failed", "error": str(error)})
            raise
        finally:
            (self.ui.output / "results.json").write_text(json.dumps(self.report, indent=2) + "\n")

    def launch(self):
        self.ui.adb("shell", "am", "force-stop", self.ui.package)
        self.ui.adb("shell", "am", "start", "-W", "-n",
                    f"{self.ui.package}/dev.logisim.mobile.NativeAssetFixtureActivity",
                    "-d", f"logisim-native-assets://fixture/{self.run_id}", "--es", "fixtureRun", self.run_id)
        self.manifest = self.poll_json(f"{self.private}/manifest.json", lambda item: isinstance(item, dict)
            and item.get("runId") == self.run_id and item.get("packageName") == self.ui.package)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
            if self.ui.optional(root, description="Native asset fixture status", text="Ready") is not None:
                break
        else:
            raise AssertionError("The native fixture did not expose Ready controls")
        if self.manifest.get("pdfPageCount") != 3 or self.manifest.get("pngWidth") != 1 or self.manifest.get("pngHeight") != 1:
            raise AssertionError("The native fixture must contain the literal 1x1 PNG and three-page PDF")
        for kind in ("png", "svg", "pdf", "memory"):
            fixture = self.manifest["fixtures"][kind]
            path = PurePosixPath(fixture["path"])
            if path.is_absolute() or ".." in path.parts or not re.fullmatch(r"[A-Za-z0-9_./-]+", str(path)):
                raise AssertionError(f"Unsafe fixture protocol path: {path}")
            content = self.private_bytes(f"{self.private}/{path}")
            if len(content) != fixture["bytes"] or digest(content) != fixture["sha256"]:
                raise AssertionError(f"The native source fixture {kind} failed its hash/size check")
            self.sources[kind] = content
            (self.ui.output / f"source-{kind}-{path.name}").write_bytes(content)
        literal_png_sha = "ec643b38c8bdaa10d9460deb46d42d0fb815c1852472106cc78ddccadcf94058"
        if digest(self.sources["png"]) != literal_png_sha or len(self.sources["png"]) != 70:
            raise AssertionError("The PNG fixture differs from the agreed 70-byte literal")
        if ET.fromstring(self.sources["svg"]).tag != "{http://www.w3.org/2000/svg}svg":
            raise AssertionError("The SVG source fixture is not a valid SVG document")
        if self.sources["memory"] != b"v2.0 raw\n00 7f 80 ff\n":
            raise AssertionError("The memory fixture differs from its literal hexadecimal contents")
        (self.ui.output / "fixture-manifest.json").write_text(json.dumps(self.manifest, indent=2) + "\n")
        receiver = f"{self.ui.package}.test/dev.logisim.mobile.documents.DocumentFixtureBootstrapReceiver"
        if "result=-1" not in self.ui.adb("shell", "am", "broadcast", "-n", receiver):
            raise AssertionError("The separate test provider did not initialize")

    def choose_root(self, label):
        deadline = time.monotonic() + 45
        while deadline - time.monotonic() > 4:
            root = self.ui.screen(timeout=deadline - time.monotonic())
            drawer = any(node.get("resource-id", "").endswith(":id/drawer_roots") for node in root.iter("node"))
            selected = any(node.get("resource-id", "").endswith(":id/breadcrumb_text")
                           and node.get("text") == label for node in root.iter("node"))
            if not drawer and selected:
                return
            if drawer:
                target = self.ui.optional(root, text=label)
                if target is not None:
                    self.ui.tap(target)
            else:
                opener = self.ui.optional(root, description="Show roots")
                if opener is not None:
                    self.ui.tap(opener)
            time.sleep(0.2)
        raise AssertionError(f"The document picker did not confirm the selected root: {label}")

    def choose_document(self, name):
        deadline = time.monotonic() + 30
        while deadline - time.monotonic() > 4:
            root = self.ui.screen(timeout=deadline - time.monotonic())
            exact = self.ui.optional(root, text=name)
            if exact is not None:
                self.ui.tap(exact)
                return
            # Grid image tiles merge the filename, size, and date into their description.
            matches = [node for node in root.iter("node")
                       if node.get("content-desc", "").startswith(name + ",")
                       and node.get("enabled") != "false"]
            if len(matches) > 1:
                raise AssertionError(f"The document picker exposed an ambiguous file: {name}")
            if matches:
                self.ui.tap(matches[0])
                return
            time.sleep(0.2)
        raise AssertionError(f"The document picker did not expose {name}")

    def downloads_source(self, kind, suffix=None):
        fixture = self.manifest["fixtures"][kind]
        suffix = suffix or Path(fixture["name"]).suffix
        name = f"native-input-{self.run_id}-{kind}{suffix}"
        remote = f"/sdcard/Download/{name}"
        source = self.ui.output / f"input-{kind}{suffix}"
        source.write_bytes(self.sources[kind])
        self.command("push", str(source), remote)
        return name, remote

    def assert_open(self, operation, kind, *, external=False, suffix=None, tiny=False):
        fixture = self.manifest["fixtures"][kind]
        name, remote = self.downloads_source(kind, suffix)
        if external:
            name = f"native-external-{self.run_id}-{kind}{Path(name).suffix}"
            command = f"run-as {shlex.quote(self.ui.package + '.test')} sh -c " + shlex.quote(f"cat > files/document-fixtures/{name}")
            self.command("shell", command, input_bytes=self.sources[kind])
        before = self.begin(operation)
        self.choose_root(f"Document test provider: {self.ui.package}.test" if external else "Downloads")
        self.choose_document(name)
        event = self.await_event(operation, before, "failure" if tiny else "opened")
        if tiny:
            if event.get("noticeKey") != "android_asset_too_large":
                raise AssertionError(f"The tiny limit was not reported correctly: {event}")
        else:
            asset = event.get("asset", {})
            if asset.get("bytes") != len(self.sources[kind]) or asset.get("sha256") != digest(self.sources[kind]):
                raise AssertionError(f"Import changed the {kind} payload: {event}")
            if asset.get("name") != name:
                raise AssertionError(f"Import changed the requested source filename: {event}")
            expected_mimes = {fixture["mimeType"]}
            if kind == "memory":
                # .hex has no standard Android MIME mapping; a generic binary import accepts either.
                expected_mimes = {"application/octet-stream"} if suffix == ".bin" else {"text/plain", "application/octet-stream"}
            if asset.get("mimeType") not in expected_mimes:
                raise AssertionError(f"Import returned an unexpected MIME type: {event}")
            imported_path = PurePosixPath(asset.get("path", ""))
            if not str(imported_path) or imported_path.is_absolute() or ".." in imported_path.parts or not re.fullmatch(
                    r"[A-Za-z0-9_./-]+", str(imported_path)):
                raise AssertionError(f"Unsafe opened-asset protocol path: {imported_path}")
            imported = self.private_bytes(f"{self.private}/{imported_path}")
            if imported != self.sources[kind]:
                raise AssertionError("The opened callback's saved bytes differ from the actual source")
            (self.ui.output / f"opened-{event['id']}.bin").write_bytes(imported)
        if external:
            original = self.private_bytes(f"files/document-fixtures/{name}", self.ui.package + ".test")
            if original != self.sources[kind]:
                raise AssertionError("Opening the document changed its external provider source")
        original = self.command("exec-out", "cat", remote).stdout
        if original != self.sources[kind]:
            raise AssertionError("Opening the document changed its Downloads source")
        return event

    def cancellations(self):
        for operation in ("open-png", "create-svg"):
            def cancel(operation=operation):
                before = self.begin(operation)
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    root = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
                    if any("documentsui" in node.get("package", "").lower() for node in root.iter("node")):
                        break
                else:
                    raise AssertionError("Cancellation did not reach the native document picker")
                for _ in range(3):
                    root = self.ui.screen()
                    if self.ui.optional(root, description="Native asset fixture status") is not None:
                        break
                    if not any("documentsui" in node.get("package", "").lower() for node in root.iter("node")):
                        raise AssertionError("Cancellation did not reach the native document picker")
                    # Back can first dismiss the keyboard or roots drawer before canceling the picker.
                    self.ui.key(4)
                return self.await_event(operation, before, "cancelled")
            self.run(f"cancel-{operation}", cancel)

    def save(self, kind, *, recover=False):
        operation = f"create-{kind}"
        fixture = self.manifest["fixtures"][kind]
        before = self.begin(operation)
        self.choose_root("Downloads")
        root = self.ui.screen()
        fields = [node for node in root.iter("node") if node.get("class") == "android.widget.EditText"]
        if len(fields) != 1 or (not recover and fields[0].get("text") != fixture["name"]):
            raise AssertionError(f"CreateDocument did not preserve the default {kind} filename")
        name = fixture["name"]
        old_pid = self.command("shell", "pidof", self.ui.package).stdout.decode().strip()
        if recover:
            name = f"fixture-recovered-{self.run_id}.svg"
            self.ui.input_text(fields[0], name)
            ime = self.ui.adb("shell", "dumpsys", "input_method")
            if re.search(r"mInputShown=true|isInputViewShown=true", ime):
                self.ui.key(4)
            self.ui.capture("recovery-picker-before-kill")
            self.ui.adb("shell", "am", "kill", self.ui.package)
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if not self.command("shell", "pidof", self.ui.package, check=False).stdout.strip():
                    break
                time.sleep(0.2)
            else:
                raise AssertionError("Android did not kill the background fixture process")
            root = self.ui.screen()
        self.ui.tap(self.ui.find(root, resource_id="android:id/button1"))
        event = self.await_event(operation, before, "completed", source="recovered" if recover else "callback", timeout=45)
        if recover and (str(event.get("pid")) == old_pid or not old_pid):
            raise AssertionError("CreateDocument did not recover in a different process")
        content = self.command("exec-out", "cat", f"/sdcard/Download/{name}").stdout
        if content != self.sources[kind]:
            raise AssertionError(f"The exported {kind} bytes differ from their source fixture")
        if Path(name).suffix != Path(fixture["name"]).suffix:
            raise AssertionError("Save As changed the requested format's extension")
        (self.ui.output / f"export-{name}").write_bytes(content)
        return event

    def choose_share_receiver(self, label):
        deadline = time.monotonic() + 45
        while deadline - time.monotonic() > 4:
            root = self.ui.screen(timeout=deadline - time.monotonic())
            target = self.ui.optional(root, text=label)
            if target is not None:
                self.ui.tap(target)
                return
            choices = self.ui.optional(root, resource_id="android:id/resolver_list")
            if choices is not None:
                left, top, right, bottom = self.ui._bounds(choices)
                # Android initially presents only the first row of share targets.
                self.ui.adb("shell", "input", "swipe", str((left + right) // 2), str(bottom - 90),
                            str((left + right) // 2), str(max(100, top - 700)), "400")
            time.sleep(0.2)
        raise AssertionError(f"The share chooser did not expose its unique receiver: {label}")

    def share(self, kind):
        operation = f"share-{kind}"
        fixture = self.manifest["fixtures"][kind]
        test_package = self.ui.package + ".test"
        self.ui.adb("shell", "run-as", test_package, "rm", "-f", "files/share-result.json")
        before = self.begin(operation)
        event = self.await_event(operation, before, "completed")
        self.choose_share_receiver(f"Document test receiver: {test_package}")
        receipt = self.poll_json("files/share-result.json", lambda item: isinstance(item, dict), package=test_package)
        if receipt.get("readable") is not True or "error" in receipt:
            raise AssertionError(f"The external receiver could not read the granted URI: {receipt}")
        if receipt.get("bytes") != len(self.sources[kind]) or receipt.get("sha256") != digest(self.sources[kind]):
            raise AssertionError(f"The share receiver read different {kind} bytes: {receipt}")
        if receipt.get("mimeType") != fixture["mimeType"] or receipt.get("displayName") != fixture["name"]:
            raise AssertionError(f"Sharing changed the declared format or filename: {receipt}")
        accepted_provider_types = {fixture["mimeType"]}
        if kind == "memory":
            accepted_provider_types.add("application/octet-stream")
        if receipt.get("providerMimeType") not in accepted_provider_types:
            raise AssertionError(f"The FileProvider announced an unexpected MIME type: {receipt}")
        if receipt.get("prefixBase64") != base64.b64encode(self.sources[kind][:32]).decode():
            raise AssertionError("The share receiver's byte prefix is incorrect")
        uri = receipt.get("uri", "")
        if not uri.startswith(f"content://{self.ui.package}.files/circuits/") or "/data/" in uri:
            raise AssertionError(f"The shared URI exposed an unexpected route: {uri}")
        (self.ui.output / f"share-{kind}.json").write_text(json.dumps(receipt, indent=2) + "\n")
        return {"event": event, "receipt": receipt}

    def print_preview(self):
        if self.manifest.get("supportsPrint") is not True:
            raise AssertionError("Printing was requested but this device does not advertise native print support")
        before = self.begin("print-pdf")
        event = self.await_event("print-pdf", before, "completed")
        deadline = time.monotonic() + 45
        root = None
        while time.monotonic() < deadline:
            candidate = self.ui.screen(timeout=max(0.1, deadline - time.monotonic()))
            if any(node.get("package") == "com.android.printspooler" for node in candidate.iter("node")):
                texts = " ".join(node.get("text", "") for node in candidate.iter("node")).lower()
                if any(error in texts for error in ("couldn't print", "cannot print", "print error", "document error",
                    "error printing", "didn't work", "couldn't load", "unable to", "failed", "something went wrong")):
                    raise AssertionError(f"Native print preview reported an error: {texts}")
                pages = [node for node in candidate.iter("node")
                         if node.get("resource-id") == "com.android.printspooler:id/preview_page"
                         and node.get("content-desc") == "Page 1 of 3"]
                progress = any(node.get("class", "").endswith("ProgressBar")
                               for node in candidate.iter("node"))
                if len(pages) == 1 and not progress and not any(pending in texts for pending in ("preparing", "loading")):
                    preview_image = self.ui.output / "native-print-preview-rendered.png"
                    preview_image.write_bytes(self.ui.adb("exec-out", "screencap", "-p", text=False))
                    preview = print_preview_pixels(preview_image, self.ui._bounds(pages[0]))
                    if preview["passed"]:
                        root = candidate
                        break
            time.sleep(0.2)
        if root is None:
            raise AssertionError("The native print spooler's preview did not become visible")
        (self.ui.output / "native-print-pixels.json").write_text(json.dumps(preview, indent=2) + "\n")
        self.ui.capture("native-print-preview", root)
        self.ui.key(4)
        self.fixture_button("print-pdf")
        return {"event": event, "preview": preview}

    def assert_sources_preserved(self):
        for kind, content in self.sources.items():
            path = self.manifest["fixtures"][kind]["path"]
            if self.private_bytes(f"{self.private}/{path}") != content:
                raise AssertionError(f"A native operation changed the original {kind} fixture")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--skip-print", action="store_true")
    parser.add_argument("--cases", default=",".join(CASE_NAMES), help="Comma-separated: " + ", ".join(CASE_NAMES))
    args = parser.parse_args()
    cases = args.cases.split(",")
    if not cases or len(cases) != len(set(cases)) or any(case not in CASE_NAMES for case in cases):
        parser.error("--cases must name unique scenarios from " + ", ".join(CASE_NAMES))
    if args.skip_print:
        cases = [case for case in cases if case != "print"]
    if not cases:
        parser.error("No scenarios remain after applying --skip-print")
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = str(sdk / "platform-tools/adb")
    if not args.serial:
        connected = subprocess.check_output([adb, "devices"], text=True, timeout=15)
        devices = [line.split()[0] for line in connected.splitlines()[1:] if line.endswith("\tdevice")]
        if len(devices) != 1:
            parser.error("Connect one test device or provide --serial/ANDROID_SERIAL")
        args.serial = devices[0]
    app_directory = ROOT / "androidApp/build/outputs/apk/e2e"
    test_directory = ROOT / "androidApp/build/outputs/apk/androidTest/e2e"
    app_metadata = json.loads((app_directory / "output-metadata.json").read_text())
    test_metadata = json.loads((test_directory / "output-metadata.json").read_text())
    package = app_metadata["applicationId"]
    if not package.endswith(".e2e") or test_metadata["applicationId"] != package + ".test":
        parser.error("Only matching isolated .e2e and .e2e.test APKs may be installed or controlled")
    app = app_directory / app_metadata["elements"][0]["outputFile"]
    tests = test_directory / test_metadata["elements"][0]["outputFile"]
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    output = (args.output or ROOT / "outputs/android-assets" / run_id).resolve()
    output.mkdir(parents=True, exist_ok=False)
    ui = AndroidUi(adb, args.serial, package, output)
    scenarios = NativeAssets(ui, run_id)
    provenance = {"runId": run_id, "package": package, "testPackage": package + ".test", "serial": args.serial,
                  "scope": "native-document-adapter-only",
                  "cases": cases, "skipInstall": args.skip_install, "skipPrint": args.skip_print,
                  "applicationSha256": digest(app.read_bytes()), "testApplicationSha256": digest(tests.read_bytes()),
                  "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, timeout=10).strip(),
                  "sourceChanges": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True, timeout=10).splitlines()}
    (output / "run-manifest.json").write_text(json.dumps(provenance, indent=2) + "\n")
    try:
        if any(case in cases for case in ("opens", "cancellations", "exports", "recovery")):
            locale = ui.adb("shell", "settings", "get", "system", "system_locales").strip()
            if locale in ("", "null"):
                locale = ui.adb("shell", "getprop", "persist.sys.locale").strip() or ui.adb("shell", "getprop", "ro.product.locale").strip()
            if not locale.lower().startswith("en"):
                raise AssertionError(f"The native DocumentsUI fixture requires English system labels; found {locale!r}")
        provenance["deviceFingerprint"] = ui.adb("shell", "getprop", "ro.build.fingerprint").strip()
        provenance["deviceApi"] = ui.adb("shell", "getprop", "ro.build.version.sdk").strip()
        (output / "run-manifest.json").write_text(json.dumps(provenance, indent=2) + "\n")
        if not args.skip_install:
            scenarios.command("install", "-r", str(app), timeout=90)
            scenarios.command("install", "-r", str(tests), timeout=90)
        provenance["installedArtifactsBefore"] = [ui.verify_installed_apk(app),
            ui.verify_installed_apk(tests, package + ".test")]
        (output / "run-manifest.json").write_text(json.dumps(provenance, indent=2) + "\n")
        scenarios.launch()
        if "opens" in cases:
            scenarios.run("open-png-local-downloads", lambda: scenarios.assert_open("open-png", "png"))
            scenarios.run("open-png-external-provider", lambda: scenarios.assert_open("open-png", "png", external=True))
            scenarios.run("open-memory-hex", lambda: scenarios.assert_open("open-raw", "memory", suffix=".hex"))
            scenarios.run("open-memory-hex-external-provider", lambda: scenarios.assert_open("open-raw", "memory", suffix=".hex", external=True))
            scenarios.run("open-memory-bin", lambda: scenarios.assert_open("open-raw", "memory", suffix=".bin"))
            scenarios.run("reject-png-over-requested-byte-limit", lambda: scenarios.assert_open("open-tiny-png", "png", tiny=True))
        if "cancellations" in cases:
            scenarios.cancellations()
        if "exports" in cases:
            for kind in ("png", "svg", "pdf", "memory"):
                scenarios.run(f"save-{kind}-exact-bytes", lambda kind=kind: scenarios.save(kind))
        if "shares" in cases:
            for kind in ("png", "svg", "pdf", "memory"):
                scenarios.run(f"share-{kind}-external-receiver", lambda kind=kind: scenarios.share(kind))
        if "recovery" in cases:
            scenarios.run("recover-svg-create-after-process-death", lambda: scenarios.save("svg", recover=True))
        if "print" in cases:
            scenarios.run("native-pdf-preview-and-cancel", scenarios.print_preview)
        scenarios.assert_sources_preserved()
        provenance["installedArtifactsAfter"] = [ui.verify_installed_apk(app),
            ui.verify_installed_apk(tests, package + ".test")]
        (output / "run-manifest.json").write_text(json.dumps(provenance, indent=2) + "\n")
        print(f"ASSET_ADAPTER_TESTS_PASSED={len(scenarios.report)}")
        print(f"Native asset evidence: {output}")
        return 0
    except Exception as error:
        ui.fail_artifacts("failure", error)
        print(f"FAIL: {error}\nEvidence: {output}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
