#!/usr/bin/env python3
"""Capture Android layout checkpoints in an isolated .e2e application.

Examples:
  python3 scripts/android_accessibility_layout.py --self-test
  python3 scripts/android_accessibility_layout.py --serial emulator-5554 \
    --package thalesmms.logisim.mobile.e2e --output /tmp/logisim-layout \
    --catalog-root /path/to/logisim-mobile --inventory-only
  python3 scripts/android_accessibility_layout.py --serial emulator-5554 \
    --package thalesmms.logisim.mobile.e2e --output /tmp/logisim-layout \
    --catalog-root /path/to/logisim-mobile --component-name E2E_NOT

The default flow opens Circuit explorer, selects the named component, opens
its properties, and checks that its label editor receives focus above the IME.
The canvas must expose at least 48 by 48 dp before a modal panel opens.
The retention flow expects the installed isolated app to contain the SAP-1
fixture with a Reset component. --component-search changes that ASCII search.
It checks search focus/text and selected component identity across rotation
without restarting the process. It does not prove retained world coordinates.
An absent hook is BLOCKED. The runner does not enable or operate TalkBack.
UIAutomator evidence does not establish screen-reader speech or gestures.

--scenario accepts {"steps": [...]} instead of the default flow. Each step has
a unique "name" and an "action": "tap", "back", "expect", "search_component",
or "focus_editor". search_component requires the expected query in "text".
Tap and back require an "expect" selector proving the resulting UI state.
Selectors use a canonical catalog "key", an "attribute" of "text" or
"content-desc", optional "arguments", and optional exact "attributes" checks.
"any_attributes" accepts alternative state mappings, such as checked/selected.
"resource_id" can replace "key" when the app exports a stable test tag.
An expect-only step requires "min_dp": [width, height] or an attribute check.
Scroll axes default to ["vertical", "horizontal"]. Set "scroll_axes": [] to
prohibit scrolling, or provide an ordered subset. No label fallback is guessed.
Tools and Properties use tagged overflow menus when available. Older artifacts
retain their scrolling toolbar route. Explorer scrolling uses a11y-circuit-list,
which identifies the list viewport rather than its sheet and header.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import struct
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


class Blocked(RuntimeError):
    """The app or environment does not expose a required test checkpoint."""


@dataclass(frozen=True)
class WmValue:
    physical: str
    override: str | None = None

    @property
    def effective(self):
        return self.override or self.physical


def parse_wm(text, kind):
    value = r"\d+x\d+" if kind == "size" else r"\d+"
    physical = re.search(rf"^Physical {kind}:\s*({value})\s*$", text, re.M)
    override = re.search(rf"^Override {kind}:\s*({value})\s*$", text, re.M)
    if not physical:
        raise ValueError(f"Cannot parse wm {kind}: {text!r}")
    return WmValue(physical.group(1), override.group(1) if override else None)


def setting_command(namespace, key, value):
    if value == "null":
        return ("shell", "settings", "delete", namespace, key)
    return ("shell", "settings", "put", namespace, key, value)


def png_size(data):
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("Screenshot has no PNG IHDR")
    width, height = struct.unpack(">II", data[16:24])
    if width <= 0 or height <= 0:
        raise ValueError("Screenshot has empty dimensions")
    return width, height


def bounds(value):
    found = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", value or "")
    if not found:
        raise Blocked(f"Missing usable bounds: {value!r}")
    left, top, right, bottom = map(int, found.groups())
    if right <= left or bottom <= top:
        raise Blocked(f"Empty bounds: {value}")
    return left, top, right, bottom


def intersection(a, b):
    left, top, right, bottom = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    return (left, top, right, bottom) if right > left and bottom > top else None


def visible_bounds(root, node, display):
    rect = bounds(node.get("bounds"))
    visible = intersection(rect, (0, 0, *display))
    parents = {child: parent for parent in root.iter() for child in parent}
    ancestor = parents.get(node)
    while visible and ancestor is not None:
        # Ordinary Compose container bounds do not imply clipping.
        if ancestor.get("scrollable") == "true" and ancestor.get("bounds"):
            visible = intersection(visible, bounds(ancestor.get("bounds")))
        ancestor = parents.get(ancestor)
    if visible != rect or node.get("visible-to-user") == "false":
        raise Blocked(f"Control is clipped or outside the display: {node.attrib}")
    return rect


def measure_node(root, node, display, density, *, minimum=(0, 0), ime=None):
    rect = visible_bounds(root, node, display)
    if node.get("enabled") == "false":
        raise Blocked("Control is disabled")
    size_dp = [(rect[2] - rect[0]) * 160 / density, (rect[3] - rect[1]) * 160 / density]
    if any(actual + 0.5 < required for actual, required in zip(size_dp, minimum)):
        raise AssertionError(f"Control is smaller than {minimum} dp: {size_dp}")
    if ime and intersection(rect, ime):
        raise AssertionError(f"The IME covers the control: control={rect}, IME={ime}")
    return {"bounds_px": list(rect), "size_dp": size_dp, "attributes": dict(node.attrib)}


def ime_bounds(window_dump, display=None):
    for block in re.split(r"(?=\s*Window #\d+\s)", window_dump):
        if not re.search(r"(?:InputMethod|ty=INPUT_METHOD|type=INPUT_METHOD)", block):
            continue
        if re.search(r"(?:isVisible|isOnScreen|mHasSurface)=false", block):
            continue
        match = re.search(r"(?:mFrame|\bframe)=?(\[-?\d+,-?\d+\]\[-?\d+,-?\d+\])", block)
        if match:
            frame = bounds(match.group(1))
            # Some IME windows cover the display, with the keyboard confined by insets.
            insets = re.search(r"mGiven(?:Visible|Content)Insets=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
            if insets:
                left, top, right, bottom = map(int, insets.groups())
                inset_frame = (frame[0] + left, frame[1] + top, frame[2] - right, frame[3] - bottom)
                if inset_frame[2] <= inset_frame[0] or inset_frame[3] <= inset_frame[1]:
                    return None
                frame = inset_frame
            if display and frame == (0, 0, *display):
                return None  # A full-display frame alone does not locate the visible keyboard.
            return frame
    return None


SYSTEM_KEYS = ("font_scale", "accelerometer_rotation", "user_rotation")
SERVICE_KEYS = ("enabled_accessibility_services", "accessibility_enabled", "touch_exploration_enabled")
TOOLBAR_ROUTES = {
    "a11y_project_tools": ("editor-project-toolbar", "editor-command-toolbar"),
    "a11y_open_properties": ("editor-command-toolbar",),
}
TOOLBAR_IDS = ("editor-project-toolbar", "editor-command-toolbar", "editor-simulation-toolbar")


def inventory(ui):
    """Use device getters only. AndroidUi.screen() writes a remote dump file."""
    size_raw, density_raw = ui.adb("shell", "wm", "size"), ui.adb("shell", "wm", "density")
    return {
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "serial": ui.serial, "package": ui.package,
        "wm_size": asdict(parse_wm(size_raw, "size")),
        "wm_density": asdict(parse_wm(density_raw, "density")),
        "wm_size_raw": size_raw, "wm_density_raw": density_raw,
        "system": {key: ui.adb("shell", "settings", "get", "system", key).strip() for key in SYSTEM_KEYS},
        "services": {key: ui.adb("shell", "settings", "get", "secure", key).strip() for key in SERVICE_KEYS},
        "window_displays": ui.adb("shell", "dumpsys", "window", "displays"),
        "input_method": ui.adb("shell", "dumpsys", "input_method"),
    }


def restore(ui, original):
    """Try every restoration, including when an earlier adb command fails."""
    commands = [
        ("shell", "wm", "size", original["wm_size"]["override"] or "reset"),
        ("shell", "wm", "density", original["wm_density"]["override"] or "reset"),
        *(setting_command("system", key, original["system"][key]) for key in SYSTEM_KEYS),
    ]
    outcomes = []
    for command in commands:
        try:
            ui.adb(*command)
            outcomes.append({"command": list(command), "status": "RESTORED"})
        except (Exception, KeyboardInterrupt) as error:
            outcomes.append({"command": list(command), "status": "FAILED", "error": str(error)})
    return outcomes


def restoration_differences(before, after):
    keys = ("wm_size", "wm_density", "system", "services")
    return {key: {"before": before[key], "after": after[key]} for key in keys if before[key] != after[key]}


class Catalog:
    def __init__(self, root):
        directory = root / "localization" / "messages"
        if not directory.is_dir():
            directory = root
        files = sorted(directory.glob("*.json"))
        if not files:
            raise ValueError(f"No canonical localization JSON files in {directory}")
        self.entries = {}
        for path in files:
            for key, entry in json.loads(path.read_text()).items():
                if key in self.entries:
                    raise ValueError(f"Duplicate localization key: {key}")
                self.entries[key] = entry

    def labels(self, key, arguments):
        if key not in self.entries:
            raise Blocked(f"Canonical label {key!r} is absent from the supplied catalog")
        labels = []
        for language, template in self.entries[key].items():
            if isinstance(template, str):
                values = {name: value.get(language, "") if isinstance(value, dict) else value
                          for name, value in arguments.items()}
                try:
                    labels.append(template.format(**values))
                except KeyError as error:
                    raise Blocked(f"Provide an argument for {key}: {error}") from error
        if not labels:
            raise Blocked(f"Canonical label {key!r} requires plural parameters not supported by this selector")
        return labels

    def patterns(self, key, arguments):
        """Match unknown dynamic fields without replacing any canonical words."""
        import string
        if key not in self.entries:
            raise Blocked(f"Canonical label {key!r} is absent from the supplied catalog")
        patterns = []
        for language, template in self.entries[key].items():
            if not isinstance(template, str):
                continue
            parts = []
            for literal, field, _format_spec, _conversion in string.Formatter().parse(template):
                parts.append(re.escape(literal))
                if field is not None:
                    value = arguments.get(field)
                    if isinstance(value, dict):
                        value = value.get(language)
                    parts.append(re.escape(str(value)) if value is not None else r"[\s\S]*?")
            patterns.append(re.compile("".join(parts)))
        if not patterns:
            raise Blocked(f"Canonical label {key!r} has no string template")
        return patterns

    def nodes(self, root, selector):
        attribute = selector.get("attribute", "text")
        if attribute not in ("text", "content-desc"):
            raise ValueError("Selector attribute must be text or content-desc")
        if "resource_id" in selector:
            matches = lambda node: node.get("resource-id") == selector["resource_id"]
        elif selector.get("template"):
            patterns = self.patterns(selector["key"], selector.get("arguments", {}))
            matches = lambda node: any(pattern.fullmatch(node.get(attribute, "")) for pattern in patterns)
        else:
            labels = self.labels(selector["key"], selector.get("arguments", {}))
            matches = lambda node: node.get(attribute, "") in labels
        return [node for node in root.iter("node") if matches(node)]


class Checkpoints:
    def __init__(self, ui, catalog, display, density):
        self.ui, self.catalog, self.display, self.density = ui, catalog, display, density

    def find(self, selector, *, root=None, within=None):
        root = root if root is not None else self.ui.screen()
        found = self.catalog.nodes(within if within is not None else root, selector)
        if len(found) > 1:
            raise Blocked(f"Ambiguous selector: {selector}, {len(found)} matches")
        if not found:
            raise Blocked(f"Missing selector: {selector}")
        node = found[0]
        if node.get("package") != self.ui.package:
            raise Blocked(f"Selector belongs to another application: {node.attrib}")
        measure_node(root, node, self.display, self.density, minimum=selector.get("min_dp", (0, 0)))
        if within is not None:
            frame = bounds(node.get("bounds"))
            if intersection(frame, visible_bounds(root, within, self.display)) != frame:
                raise Blocked("Control is clipped by the identified scrolling viewport")
        for name, expected in selector.get("attributes", {}).items():
            if node.get(name) != expected:
                raise AssertionError(f"Expected {name}={expected!r}, got {node.get(name)!r}")
        if "any_attributes" in selector and not any(
                all(node.get(name) == value for name, value in alternative.items())
                for alternative in selector["any_attributes"]):
            raise AssertionError(f"No expected state mapping matched: {selector['any_attributes']}, got {node.attrib}")
        return root, node

    def tagged(self, root, identifier):
        nodes = [node for node in root.iter("node") if node.get("resource-id") == identifier
                 and node.get("package") == self.ui.package]
        if len(nodes) > 1:
            raise Blocked(f"Ambiguous container tag: {identifier}")
        return nodes[0] if nodes else None

    def action_node(self, root, node):
        parents = {child: parent for parent in root.iter() for child in parent}
        while node.get("clickable") != "true" and node in parents:
            node = parents[node]
        if node.get("clickable") != "true" or node.get("package") != self.ui.package:
            raise Blocked("The selected label has no clickable application control")
        return node

    def reveal(self, selector, axes, *, interactive=False):
        root = self.ui.screen()
        toolbars = selector.get("toolbars", TOOLBAR_ROUTES.get(selector.get("key"), ()))
        if toolbars:
            found = self.reveal_toolbar(selector, toolbars, root, interactive)
            if found is not None:
                return found
            # Old artifacts have no overflow tags and retain horizontal scrolling.
        region = selector.get("viewport")
        if region is None and (selector.get("resource_id") == "a11y-circuit-search" or
                               selector.get("key", "").startswith("a11y_explorer_")):
            if self.tagged(root, "a11y-circuit-list") is not None:
                region = "a11y-circuit-list"
        return self.reveal_scrolling(selector, axes, region_id=region, root=root, interactive=interactive)

    def find_reachable(self, root, selector, within, interactive):
        root, node = self.find(selector, root=root, within=within)
        if interactive:
            target = self.action_node(root, node)
            measure_node(root, target, self.display, self.density, minimum=(48, 48))
            if within is not None:
                frame = bounds(target.get("bounds"))
                if intersection(frame, visible_bounds(root, within, self.display)) != frame:
                    raise Blocked("Clickable control is clipped by the identified scrolling viewport")
        return root, node

    def reveal_toolbar(self, selector, toolbars, root, interactive):
        # An open modal menu owns navigation; background bounds do not prove hittability.
        open_menus = [toolbar for toolbar in set(TOOLBAR_IDS) | set(toolbars)
                      if self.tagged(root, toolbar + "-menu") is not None]
        if len(open_menus) > 1:
            raise Blocked(f"Ambiguous open toolbar menus: {open_menus}")
        if any(toolbar not in toolbars for toolbar in open_menus):
            raise Blocked(f"Another toolbar menu is already open: {open_menus}")
        toolbars = tuple(toolbar for toolbar in toolbars if toolbar in open_menus) + tuple(
            toolbar for toolbar in toolbars if toolbar not in open_menus)
        if not open_menus:
            # Prefer a whole visible action. Never swipe a toolbar with an overflow button.
            try:
                return self.find_reachable(root, selector, None, interactive)
            except Blocked as error:
                if "Ambiguous" in str(error) or "Canonical label" in str(error):
                    raise
            except AssertionError:
                pass  # A legacy action may need to be scrolled fully into view.
        observed_overflow = False
        for toolbar in toolbars:
            menu_id = toolbar + "-menu"
            menu = self.tagged(root, menu_id)
            overflow = self.tagged(root, toolbar + "-overflow")
            if menu is None and overflow is None:
                continue
            observed_overflow = True
            if menu is None:
                self.click(root, overflow)
                root, _ = self.expect({"resource_id": menu_id})
            try:
                return self.reveal_scrolling(selector, ["vertical"], region_id=menu_id,
                                             root=root, interactive=interactive)
            except Blocked as error:
                if "Ambiguous" in str(error) or "Canonical label" in str(error):
                    raise
                # Project actions move into the command toolbar at minimal heights.
                self.ui.key(4)
                root = self.ui.screen()
                if self.tagged(root, menu_id) is not None:
                    raise Blocked(f"Overflow menu did not close: {menu_id}")
                # Closing a different menu can reveal an action placed directly on the next toolbar.
                if not any(self.tagged(root, name + "-menu") is not None for name in TOOLBAR_IDS):
                    try:
                        return self.find_reachable(root, selector, None, interactive)
                    except Blocked as failure:
                        if "Ambiguous" in str(failure) or "Canonical label" in str(failure):
                            raise
                    except AssertionError:
                        pass
        if observed_overflow:
            raise Blocked(f"Action was not reachable in the toolbar overflow menus: {selector}")
        return None

    def reveal_scrolling(self, selector, axes, *, region_id=None, root=None, interactive=False):
        last_error, observed_failure = None, None
        for axis, reverse in [(None, False)] + [(axis, reverse) for axis in axes for reverse in (False, True)]:
            for attempt in range(8 if axis else 1):
                root = root if root is not None else self.ui.screen()
                region = self.tagged(root, region_id) if region_id else None
                if region_id and region is None:
                    raise Blocked(f"The identified scrolling viewport disappeared: {region_id}")
                try:
                    return self.find_reachable(root, selector, region, interactive)
                except (Blocked, AssertionError) as error:
                    if "Ambiguous" in str(error) or "Canonical label" in str(error):
                        raise
                    last_error = error
                    if isinstance(error, AssertionError):
                        observed_failure = error
                if not axis:
                    root = None
                    break
                scrollers = [region] if region is not None else [node for node in root.iter("node")
                    if node.get("scrollable") == "true" and node.get("package") == self.ui.package
                    and not node.get("class", "").endswith("EditText")]
                frames = []
                for node in scrollers:
                    try:
                        frame = visible_bounds(root, node, self.display)
                    except Blocked:
                        continue
                    if frame[2] - frame[0] >= 48 and frame[3] - frame[1] >= 32:
                        frames.append(frame)
                if not frames:
                    break
                # Only legacy artifacts need discovery of an untagged scroll container.
                rect = sorted(set(frames), key=lambda r: (r[3] - r[1]) * (r[2] - r[0]), reverse=True)[attempt % len(set(frames))]
                left, top, right, bottom = rect
                if axis == "horizontal":
                    start, end = (left + (right-left)*0.8, (top+bottom)/2), (left + (right-left)*0.2, (top+bottom)/2)
                else:
                    start, end = ((left+right)/2, top+(bottom-top)*0.8), ((left+right)/2, top+(bottom-top)*0.2)
                if reverse:
                    start, end = end, start
                self.ui.adb("shell", "input", "swipe", *(str(round(p)) for p in (*start, *end)), "250")
                root = None
        if observed_failure is not None:
            raise AssertionError(f"Control never reached its required size after scrolling: {observed_failure}")
        raise Blocked(f"Control was not reachable after bounded scrolling: {last_error}")

    def click(self, root, node):
        node = self.action_node(root, node)
        measurement = measure_node(root, node, self.display, self.density, minimum=(48, 48))
        self.ui.tap(node)
        return measurement

    def expect(self, selector, timeout=10):
        deadline, last_error = time.monotonic() + timeout, None
        observed_failure = None
        while time.monotonic() < deadline:
            try:
                root, node = self.find(selector)
                return root, node
            except (Blocked, AssertionError) as error:
                last_error = error
                if isinstance(error, AssertionError):
                    observed_failure = error
                time.sleep(0.15)
        if observed_failure is not None:
            raise AssertionError(f"Postcondition failed after observing its control: {observed_failure}")
        raise Blocked(f"Postcondition was not reached: {last_error}")

    def focus_editor(self, artifact):
        root = self.ui.screen()
        fields = [node for node in root.iter("node") if node.get("class", "").endswith("EditText")
                  and node.get("package") == self.ui.package and node.get("enabled") != "false"]
        if len(fields) != 1:
            raise Blocked(f"Properties must expose one unambiguous editor, got {len(fields)}")
        self.click(root, fields[0])
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            root = self.ui.screen()
            focused = [node for node in root.iter("node") if node.get("package") == self.ui.package
                       and node.get("class", "").endswith("EditText") and node.get("focused") == "true"]
            method = self.ui.adb("shell", "dumpsys", "input_method")
            windows = self.ui.adb("shell", "dumpsys", "window", "windows")
            frame = ime_bounds(windows, self.display)
            shown = re.search(r"(?:mInputShown|inputShown|mIsInputViewShown|isInputViewShown)=true\b", method)
            if len(focused) == 1 and shown and frame:
                evidence = self.save_ime_evidence(artifact, method, windows)
                measurement = measure_node(root, focused[0], self.display, self.density, minimum=(48, 48), ime=frame)
                return root, {"editor": measurement, "ime_bounds_px": list(frame), "focused": True, "evidence": evidence}
            time.sleep(0.15)
        self.save_ime_evidence(artifact, method, windows)
        raise Blocked("Could not prove focused properties editor and visible IME bounds; hardware keyboard or dump format may differ")

    def search_component(self, query):
        selector = {"resource_id": "a11y-circuit-search"}
        root, field = self.reveal(selector, ["vertical"], interactive=True)
        if not field.get("class", "").endswith("EditText"):
            raise Blocked("The search tag does not expose an editable field")
        self.ui.input_text(field, query)
        root, field = self.expect({**selector, "attributes": {"text": query, "focused": "true"}})
        measurement = measure_node(root, field, self.display, self.density)
        method = self.ui.adb("shell", "dumpsys", "input_method")
        if re.search(r"(?:mInputShown|inputShown|mIsInputViewShown|isInputViewShown)=true\b", method):
            self.ui.key(4)
        return self.ui.screen(), {"query": query, "field_after_typing": measurement}

    def save_ime_evidence(self, artifact, method, windows):
        paths = {"input_method": self.ui.output / f"{artifact}-input-method.txt",
                 "windows": self.ui.output / f"{artifact}-windows.txt"}
        paths["input_method"].write_text(method)
        paths["windows"].write_text(windows)
        return {name: str(path) for name, path in paths.items()}

    def run(self, step, artifact):
        action = step["action"]
        result = {"name": step["name"], "action": action}
        if action == "focus_editor":
            root, result["measurement"] = self.focus_editor(artifact)
        elif action == "search_component":
            root, result["measurement"] = self.search_component(step["text"])
        else:
            if action == "back":
                self.ui.key(4)
            else:
                root, node = self.reveal(step["selector"], step.get("scroll_axes", ["vertical", "horizontal"]), interactive=action == "tap")
                result["measurement"] = measure_node(root, node, self.display, self.density,
                                                     minimum=step["selector"].get("min_dp", (0, 0)))
                if action == "tap":
                    result["action_target"] = self.click(root, node)
            if "expect" in step:
                root, node = self.expect(step["expect"])
                result["postcondition"] = measure_node(root, node, self.display, self.density,
                                                        minimum=step["expect"].get("min_dp", (0, 0)))
        result["artifacts"] = self.ui.capture(artifact, root)
        result["status"] = "PASS"
        return result


def default_steps(component_name):
    steps = [
        {"name": "canvas-usable", "action": "expect", "selector": {
            "key": "a11y_canvas", "attribute": "content-desc", "template": True, "min_dp": [48, 48]}},
        {"name": "open-tools", "action": "tap", "selector": {"key": "a11y_project_tools", "attribute": "content-desc"},
         "expect": {"key": "project_tools_title"}},
        {"name": "open-explorer", "action": "tap", "selector": {"key": "a11y_explorer_title"},
         "expect": {"resource_id": "editor-tool-sheet", "min_dp": [48, 48]}},
    ]
    if component_name:
        component = {"key": "a11y_explorer_select", "attribute": "content-desc", "arguments": {"name": component_name}}
        steps += [
            {"name": "find-component", "action": "search_component", "text": component_name},
            {"name": "select-component", "action": "tap", "selector": component,
             "expect": {**component, "any_attributes": [{"selected": "true"}, {"checked": "true"}], "min_dp": [48, 48]}},
            {"name": "open-properties", "action": "tap",
             "selector": {"key": "a11y_explorer_edit", "arguments": {"name": component_name}},
             "expect": {"key": "property_label"}},
            {"name": "properties-ime", "action": "focus_editor"},
        ]
    return steps


def validate_steps(steps):
    names = set()
    if not isinstance(steps, list) or not steps:
        raise ValueError("Scenario steps must be a nonempty list")
    for step in steps:
        name, action = step.get("name", ""), step.get("action")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name) or name in names:
            raise ValueError("Checkpoint names must be unique simple filenames")
        names.add(name)
        if action not in ("tap", "back", "expect", "focus_editor", "search_component"):
            raise ValueError(f"Unsupported checkpoint action: {action}")
        if action == "search_component" and (not isinstance(step.get("text"), str) or not step["text"]):
            raise ValueError("Search checkpoints require a nonempty query in text")
        if action in ("tap", "back") and "expect" not in step:
            raise ValueError("Tap and back checkpoints require an explicit postcondition")
        if action == "expect" and not (step["selector"].get("attributes") or step["selector"].get("any_attributes") or step["selector"].get("min_dp")):
            raise ValueError("Expect checkpoints need a size or attribute assertion")
        for selector in (step.get("selector"), step.get("expect")):
            if selector is None:
                continue
            if ("resource_id" in selector) == ("key" in selector):
                raise ValueError("Each selector needs exactly one canonical key or resource_id")
            if "any_attributes" in selector and (not isinstance(selector["any_attributes"], list) or not selector["any_attributes"] or any(
                    not isinstance(alternative, dict) or not alternative for alternative in selector["any_attributes"])):
                raise ValueError("any_attributes requires nonempty alternative attribute maps")
            if "min_dp" in selector:
                size = selector["min_dp"]
                if not isinstance(size, list) or len(size) != 2 or any(
                        isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 < value < 10000 for value in size):
                    raise ValueError("min_dp must contain two positive dimensions")
        if any(axis not in ("horizontal", "vertical") for axis in step.get("scroll_axes", [])):
            raise ValueError("Scroll axes must be horizontal or vertical")
    return steps


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def configure(ui, rotation, font_scale, density):
    for command in (
        ("shell", "settings", "put", "system", "accelerometer_rotation", "0"),
        ("shell", "settings", "put", "system", "user_rotation", str(rotation)),
        ("shell", "settings", "put", "system", "font_scale", str(font_scale)),
        ("shell", "wm", "density", str(density)),
        ("shell", "wm", "size", f"{round(320*density/160)}x{round(568*density/160)}"),
    ):
        ui.adb(*command)


def wait_display(ui, width_dp, height_dp, font_scale, density):
    deadline, observed = time.monotonic() + 25, None
    while time.monotonic() < deadline:
        display = png_size(ui.adb("exec-out", "screencap", "-p", text=False))
        actual_density = int(parse_wm(ui.adb("shell", "wm", "density"), "density").effective)
        actual_font = float(ui.adb("shell", "settings", "get", "system", "font_scale").strip())
        actual_dp = [dimension * 160 / actual_density for dimension in display]
        observed = {"display_px": list(display), "display_dp": actual_dp,
                    "density": actual_density, "font_scale": actual_font}
        if actual_density == density and abs(actual_font-font_scale) < 0.01 and all(
                abs(actual-expected) <= 1 for actual, expected in zip(actual_dp, (width_dp, height_dp))):
            return observed
        time.sleep(0.25)
    raise Blocked(f"Requested display matrix was not applied: {observed}")


def retention(ui, catalog, density, query):
    """Keep the same process while checking the focused field and selected ID."""
    result = {"status": "RUNNING", "search": query, "checkpoints": [],
              "viewport_world_coordinates": {"status": "NOT_RUN", "reason": "No public UI hook exposes world coordinates"}}
    try:
        configure(ui, 0, 1.0, density)
        display = wait_display(ui, 320, 568, 1.0, density)
        ui.adb("shell", "am", "force-stop", ui.package)
        ui.adb("shell", "am", "start", "-W", "-n", f"{ui.package}/dev.logisim.mobile.MainActivity")
        driver = Checkpoints(ui, catalog, tuple(display["display_px"]), density)
        for step in default_steps(None):
            result["checkpoints"].append(driver.run(step, f"retention-{step['name']}"))
        search = {"resource_id": "a11y-circuit-search"}
        root, field = driver.reveal(search, ["vertical"])
        if not field.get("class", "").endswith("EditText"):
            raise Blocked("The stable search tag does not expose an editable field")
        prefix, suffix = query[:2], query[2:]
        ui.input_text(field, prefix)
        before_selector = {**search, "attributes": {"focused": "true", "text": prefix}, "min_dp": [48, 48]}
        root, field = driver.expect(before_selector)
        result["search_before"] = measure_node(root, field, driver.display, density, minimum=(48, 48))
        result["search_before"]["artifacts"] = ui.capture("retention-search-before-rotation", root)
        pid = ui.adb("shell", "pidof", ui.package).strip()
        if not pid:
            raise Blocked("The app has no observable process ID")
        result["process_before"] = pid

        configure(ui, 1, 1.0, density)
        display = wait_display(ui, 568, 320, 1.0, density)
        driver.display = tuple(display["display_px"])
        driver.expect(before_selector)
        after_pid = ui.adb("shell", "pidof", ui.package).strip()
        if set(pid.split()) != set(after_pid.split()):
            raise AssertionError(f"App process changed across rotation: {pid!r} to {after_pid!r}")
        # Deliberately do not tap or call input_text(), which would restore focus.
        ui.adb("shell", "input", "text", suffix)
        after_selector = {**search, "attributes": {"focused": "true", "text": query}, "min_dp": [48, 48]}
        root, field = driver.expect(after_selector)
        result["search_after"] = measure_node(root, field, driver.display, density, minimum=(48, 48))
        result["search_after"]["artifacts"] = ui.capture("retention-search-after-rotation", root)
        method = ui.adb("shell", "dumpsys", "input_method")
        if re.search(r"(?:mInputShown|inputShown|mIsInputViewShown|isInputViewShown)=true\b", method):
            ui.key(4)
        root = ui.screen()
        # A search also matches terminal names, so require the exact component name.
        named = catalog.nodes(root, {"key": "a11y_explorer_select", "attribute": "content-desc",
                                     "arguments": {"name": query}})
        candidates = [node for node in named if node.get("package") == ui.package
                      and node.get("resource-id", "").startswith("component:")]
        if len(candidates) != 1:
            raise Blocked(f"Search {query!r} must expose one stable component tag, got {len(candidates)}; verify the installed fixture")
        component_tag = candidates[0].get("resource-id")
        result["component_id"] = component_tag.removeprefix("component:")
        selected = {"any_attributes": [{"selected": "true"}, {"checked": "true"}], "min_dp": [48, 48]}
        driver.click(root, candidates[0])
        root, node = driver.expect({"resource_id": component_tag, **selected})
        result["selection_explorer"] = measure_node(root, node, driver.display, density, minimum=(48, 48))
        canvas_selector = {"resource_id": "canvas-" + component_tag, **selected}
        result["checkpoints"].append(driver.run({"name": "close-explorer", "action": "tap",
            "selector": {"key": "action_close"}, "expect": canvas_selector}, "retention-selected-canvas-landscape"))

        configure(ui, 0, 1.0, density)
        display = wait_display(ui, 320, 568, 1.0, density)
        driver.display = tuple(display["display_px"])
        root, node = driver.expect(canvas_selector)
        final_pid = ui.adb("shell", "pidof", ui.package).strip()
        if set(pid.split()) != set(final_pid.split()):
            raise AssertionError(f"App process changed before final selection check: {pid!r} to {final_pid!r}")
        result["selection_canvas_after"] = measure_node(root, node, driver.display, density, minimum=(48, 48))
        result["selection_canvas_after"]["artifacts"] = ui.capture("retention-selected-canvas-portrait", root)
        result["process_after"] = final_pid
        result["status"] = "PASS"
    except Exception as error:
        result.update(status="BLOCKED" if isinstance(error, Blocked) else "FAIL", error=str(error))
        try:
            result["failure_artifacts"] = ui.capture("retention-stopped")
        except Exception as capture_error:
            result["capture_error"] = str(capture_error)
    return result


def run_matrix(ui, catalog, steps, output, density, complete_flow, component_search=None):
    original = inventory(ui)
    write_json(output / "inventory-before.json", original)
    report = {"schema": 1, "scope": "Android UI automation, layout and focus checkpoints",
              "assistive_technology": {"status": "NOT_RUN", "reason": "UIAutomator does not prove TalkBack speech or gestures; no service was enabled or operated"},
              "orientation_retention": {"status": "NOT_RUN", "reason": "No component search was supplied"},
              "cases": [], "restoration": {"status": "NOT_RUN"}}
    previous_handlers = {}
    def interrupted(signum, _frame):
        raise KeyboardInterrupt(f"Received signal {signum}")
    for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        previous_handlers[signum] = signal.signal(signum, interrupted)
    try:
        for width, height, rotation in ((320, 568, 0), (568, 320, 1)):
            for font_scale in (1.0, 2.0):
                name = f"{width}x{height}-font{font_scale:g}"
                case = {"name": name, "requested_dp": [width, height], "requested_font_scale": font_scale, "checkpoints": []}
                report["cases"].append(case)
                try:
                    configure(ui, rotation, font_scale, density)
                    case["observed"] = wait_display(ui, width, height, font_scale, density)
                    case["configuration"] = inventory(ui)
                    ui.adb("shell", "am", "force-stop", ui.package)
                    ui.adb("shell", "am", "start", "-W", "-n", f"{ui.package}/dev.logisim.mobile.MainActivity")
                    driver = Checkpoints(ui, catalog, tuple(case["observed"]["display_px"]), density)
                    for step in steps:
                        case["checkpoints"].append(driver.run(step, f"{name}-{step['name']}"))
                    if not complete_flow:
                        raise Blocked("Supply --component-name or --scenario to prove selection and properties, beyond opening Explorer")
                    case["status"] = "PASS"
                except Exception as error:
                    case["status"] = "BLOCKED" if isinstance(error, Blocked) else "FAIL"
                    case["error"] = str(error)
                    try:
                        case["failure_artifacts"] = ui.capture(f"{name}-stopped")
                    except Exception as capture_error:
                        case["capture_error"] = str(capture_error)
                finally:
                    write_json(output / "results.json", report)
        if component_search:
            report["orientation_retention"] = retention(ui, catalog, density, component_search)
    finally:
        # A second interrupt must not abandon restoration halfway through.
        for signum in previous_handlers:
            signal.signal(signum, signal.SIG_IGN)
        outcomes = restore(ui, original)
        report["restoration"]["commands"] = outcomes
        try:
            after = inventory(ui)
            write_json(output / "inventory-after.json", after)
            report["restoration"]["differences"] = restoration_differences(original, after)
            report["restoration"]["status"] = "PASS" if not report["restoration"]["differences"] and all(
                item["status"] == "RESTORED" for item in outcomes) else "FAIL"
        except Exception as error:
            report["restoration"].update(status="FAIL", error=str(error))
        for signum, handler in previous_handlers.items():
            signal.signal(signum, handler)
        report["status"] = "FAIL" if report["restoration"]["status"] != "PASS" or report["orientation_retention"]["status"] == "FAIL" or any(
            case.get("status") == "FAIL" for case in report["cases"]) else "BLOCKED" if any(
            case.get("status") != "PASS" for case in report["cases"]) or report["orientation_retention"]["status"] == "BLOCKED" else "PASS"
        write_json(output / "results.json", report)
    return report


def load_android_ui(directory):
    candidates = [directory] if directory else [Path(__file__).resolve().parent,
        Path(__file__).resolve().parents[2] / "android-files" / "scripts"]
    for candidate in candidates:
        path = candidate / "android_ui.py"
        if path.is_file():
            spec = importlib.util.spec_from_file_location("logisim_external_android_ui", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module.AndroidUi
    raise ValueError("Provide --harness-dir containing the existing android_ui.py")


class HermeticTests(unittest.TestCase):
    def setUp(self):
        self.process = patch("subprocess.run", side_effect=AssertionError("Devices are forbidden in self-tests"))
        self.process.start()
        self.addCleanup(self.process.stop)

    def test_wm_parser_preserves_override(self):
        self.assertEqual(WmValue("1080x1920", "720x1280"), parse_wm("Physical size: 1080x1920\nOverride size: 720x1280\n", "size"))
        self.assertEqual("420", parse_wm("Physical density: 420\n", "density").effective)
        with self.assertRaises(ValueError):
            parse_wm("Error: permission denied", "size")

    def test_null_setting_is_deleted(self):
        self.assertEqual(("shell", "settings", "delete", "system", "font_scale"), setting_command("system", "font_scale", "null"))
        self.assertEqual("1.25", setting_command("system", "font_scale", "1.25")[-1])

    def test_restore_attempts_every_command_after_failure(self):
        class FakeUi:
            def __init__(self): self.calls = []
            def adb(self, *command):
                self.calls.append(command)
                if len(self.calls) == 1: raise RuntimeError("fixture failure")
        ui = FakeUi()
        before = {"wm_size": {"override": "720x1280"}, "wm_density": {"override": None},
                  "system": {"font_scale": "null", "accelerometer_rotation": "1", "user_rotation": "3"}}
        outcomes = restore(ui, before)
        self.assertEqual(5, len(ui.calls))
        self.assertEqual(("shell", "wm", "size", "720x1280"), ui.calls[0])
        self.assertEqual(("shell", "wm", "density", "reset"), ui.calls[1])
        self.assertEqual(("shell", "settings", "delete", "system", "font_scale"), ui.calls[2])
        self.assertEqual("3", ui.calls[-1][-1])
        self.assertEqual(["FAILED", "RESTORED", "RESTORED", "RESTORED", "RESTORED"], [item["status"] for item in outcomes])

    def test_restore_continues_after_keyboard_interrupt(self):
        class FakeUi:
            calls = []
            def adb(self, *command):
                self.calls.append(command)
                if len(self.calls) == 1: raise KeyboardInterrupt("fixture interrupt")
        before = {"wm_size": {"override": None}, "wm_density": {"override": "240"},
                  "system": {"font_scale": "1.25", "accelerometer_rotation": "null", "user_rotation": "0"}}
        result = restore(FakeUi(), before)
        self.assertEqual(5, len(result))
        self.assertEqual("FAILED", result[0]["status"])
        self.assertTrue(all(item["status"] == "RESTORED" for item in result[1:]))

    @staticmethod
    def settings_ui():
        class FakeUi:
            serial, package = "fixture", "dev.logisim.mobile.e2e"
            def __init__(self):
                self.calls = []
                self.system = {"font_scale": "1.25", "accelerometer_rotation": "null", "user_rotation": "3"}
                self.overrides = {"size": "720x1280", "density": None}
            def adb(self, *command):
                self.calls.append(command)
                if command[:2] == ("shell", "wm"):
                    kind = command[2]
                    if len(command) == 4:
                        self.overrides[kind] = None if command[3] == "reset" else command[3]
                        return ""
                    physical = "1080x1920" if kind == "size" else "420"
                    return f"Physical {kind}: {physical}\n" + (f"Override {kind}: {self.overrides[kind]}\n" if self.overrides[kind] else "")
                if command[:2] == ("shell", "settings"):
                    action, namespace, key = command[2:5]
                    if action == "get":
                        return self.system.get(key, "null") if namespace == "system" else "null"
                    self.system[key] = "null" if action == "delete" else command[5]
                    return ""
                if command[:2] == ("shell", "dumpsys"):
                    return "fixture dump"
                raise AssertionError(f"Unexpected fake adb command: {command}")
            def capture(self, _name):
                return {"fixture": "No real screenshot"}
        return FakeUi()

    def test_inventory_is_device_getters_only(self):
        ui = self.settings_ui()
        result = inventory(ui)
        self.assertEqual("720x1280", result["wm_size"]["override"])
        self.assertTrue(all(command[:3] == ("shell", "settings", "get") or
                            command[:2] == ("shell", "dumpsys") or
                            command in (("shell", "wm", "size"), ("shell", "wm", "density"))
                            for command in ui.calls))

    def test_matrix_finally_restores_after_partial_configuration_failure(self):
        import tempfile
        ui = self.settings_ui()
        before = inventory(ui)
        def broken_configure(driver, *_args):
            driver.adb("shell", "settings", "put", "system", "font_scale", "2.0")
            raise RuntimeError("fixture configuration failed after mutation")
        with tempfile.TemporaryDirectory() as directory, patch(f"{__name__}.configure", side_effect=broken_configure):
            report = run_matrix(ui, None, [], Path(directory), 160, True)
        self.assertEqual(4, len(report["cases"]))
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("PASS", report["restoration"]["status"])
        self.assertEqual({}, restoration_differences(before, inventory(ui)))

    def test_failed_observed_state_is_fail_and_absent_hook_is_blocked(self):
        clock = iter((0, 0, 2))
        driver = Checkpoints(None, None, (320, 568), 160)
        with patch.object(driver, "find", side_effect=AssertionError("checked stayed false")), patch("time.monotonic", side_effect=lambda: next(clock)), patch("time.sleep"):
            with self.assertRaisesRegex(AssertionError, "observing its control"):
                driver.expect({}, timeout=1)
        clock = iter((0, 0, 2))
        with patch.object(driver, "find", side_effect=Blocked("hook missing")), patch("time.monotonic", side_effect=lambda: next(clock)), patch("time.sleep"):
            with self.assertRaises(Blocked):
                driver.expect({}, timeout=1)

    def test_catalog_alternative_checked_mapping_still_requires_state_change(self):
        catalog = Catalog.__new__(Catalog)
        catalog.entries = {"select": {"en": "Select {name}", "pt": "Selecionar {name}"}}
        root = ET.fromstring('<hierarchy><node text="Select A" checked="true" selected="false" package="dev.logisim.mobile.e2e" bounds="[0,0][100,48]"/></hierarchy>')
        ui = self.settings_ui()
        driver = Checkpoints(ui, catalog, (320, 568), 160)
        selector = {"key": "select", "arguments": {"name": "A"}, "any_attributes": [{"selected": "true"}, {"checked": "true"}]}
        self.assertEqual("true", driver.find(selector, root=root)[1].get("checked"))
        root[0].set("checked", "false")
        with self.assertRaises(AssertionError):
            driver.find(selector, root=root)
        root.append(ET.fromstring('<node text="Select A"/>'))
        with self.assertRaisesRegex(Blocked, "Ambiguous"):
            driver.find(selector, root=root)

    def test_retention_never_repairs_focus_after_rotation(self):
        import itertools
        import tempfile
        catalog = Catalog.__new__(Catalog)
        catalog.entries = {"a11y_explorer_select": {"en": "Select {name}"}}
        for lose_focus in (False, True):
            with self.subTest(lose_focus=lose_focus), tempfile.TemporaryDirectory() as directory:
                root = ET.fromstring('<hierarchy><node class="android.widget.EditText" resource-id="a11y-circuit-search" package="dev.logisim.mobile.e2e" bounds="[0,0][200,48]" focused="true"/><node resource-id="component:reset-17" content-desc="Select Reset" package="dev.logisim.mobile.e2e" bounds="[0,50][200,98]" clickable="true" checked="false"/><node resource-id="component:other-reset-port" content-desc="Select DFF" package="dev.logisim.mobile.e2e" bounds="[0,150][200,198]" clickable="true"/><node resource-id="canvas-component:reset-17" package="dev.logisim.mobile.e2e" bounds="[0,100][48,148]" checked="true"/></hierarchy>')
                class FakeUi:
                    package = "dev.logisim.mobile.e2e"
                    output = Path(directory)
                    def __init__(self): self.inputs, self.suffixes = [], []
                    def screen(self, **_options): return root
                    def input_text(self, node, text):
                        self.inputs.append(text)
                        node.set("text", text)
                        node.set("focused", "true")
                    def adb(self, *command):
                        if command[:3] == ("shell", "input", "text"):
                            self.suffixes.append(command[3])
                            root[0].set("text", root[0].get("text", "") + command[3])
                        if command[:2] == ("shell", "pidof"): return "123"
                        if command[:3] == ("shell", "dumpsys", "input_method"): return "mInputShown=false"
                        return ""
                    def tap(self, node): node.set("checked", "true")
                    def capture(self, _name, _root=None): return {"fixture": "No real screenshot"}
                ui, rotations = FakeUi(), []
                def rotate(_ui, rotation, *_args):
                    rotations.append(rotation)
                    if lose_focus and len(rotations) == 2: root[0].set("focused", "false")
                clock = itertools.count()
                with patch(f"{__name__}.configure", side_effect=rotate), patch(f"{__name__}.wait_display", side_effect=lambda _ui, w, h, *_: {"display_px": [w, h]}), patch.object(Checkpoints, "run", return_value={"status": "PASS"}), patch("time.monotonic", side_effect=lambda: next(clock)), patch("time.sleep"):
                    result = retention(ui, catalog, 160, "Reset")
                self.assertEqual(["Re"], ui.inputs)
                self.assertEqual([] if lose_focus else ["set"], ui.suffixes)
                self.assertEqual("FAIL" if lose_focus else "PASS", result["status"])
                if not lose_focus:
                    self.assertEqual("reset-17", result["component_id"])
                    self.assertEqual("123", result["process_after"])

    def test_offscreen_center_and_ancestor_clipping_are_rejected(self):
        root = ET.fromstring('<hierarchy><node bounds="[0,0][100,100]" scrollable="true"><node bounds="[80,80][150,150]" enabled="true"/></node></hierarchy>')
        with self.assertRaises(Blocked):
            measure_node(root, root[0][0], (200, 200), 160)
        root = ET.fromstring('<hierarchy><node bounds="[80,80][150,150]"/></hierarchy>')
        with self.assertRaises(Blocked):
            measure_node(root, root[0], (100, 100), 160)

    def test_non_scrolling_container_does_not_imply_clipping(self):
        root = ET.fromstring('<hierarchy><node bounds="[0,265][1080,2337]"><node bounds="[21,2230][1059,2356]" enabled="true"/></node></hierarchy>')
        measurement = measure_node(root, root[0][0], (1080, 2400), 420)
        self.assertEqual([21, 2230, 1059, 2356], measurement["bounds_px"])

    def test_measurements_use_density_and_ime_overlap(self):
        root = ET.fromstring('<hierarchy><node bounds="[0,0][96,96]" enabled="true"/></hierarchy>')
        self.assertEqual([48, 48], measure_node(root, root[0], (320, 568), 320, minimum=(48, 48))["size_dp"])
        with self.assertRaises(AssertionError):
            measure_node(root, root[0], (320, 568), 320, minimum=(49, 48))
        with self.assertRaises(AssertionError):
            measure_node(root, root[0], (320, 568), 160, ime=(0, 80, 320, 568))

    def test_png_and_ime_parsers(self):
        png = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 568, 320)
        self.assertEqual((568, 320), png_size(png))
        self.assertEqual((0, 300, 320, 568), ime_bounds("Window #0 Window{InputMethod}:\n mFrame=[0,300][320,568]\n isVisible=true"))
        self.assertIsNone(ime_bounds("Window #0 Window{InputMethod}:\n mFrame=[0,300][320,568]\n isVisible=false"))
        self.assertEqual((0, 300, 320, 568), ime_bounds("Window #0 Window{InputMethod}:\n mFrame=[0,0][320,568]\n mGivenVisibleInsets=[0,300][0,0]\n isVisible=true", (320, 568)))
        self.assertIsNone(ime_bounds("Window #0 Window{InputMethod}:\n mFrame=[0,0][320,568]\n isVisible=true", (320, 568)))

    def test_scenarios_require_outcomes_and_unique_names(self):
        with self.assertRaises(ValueError):
            validate_steps([{"name": "tap", "action": "tap", "selector": {"key": "x"}}])
        with self.assertRaises(ValueError):
            validate_steps([{"name": "exists", "action": "expect", "selector": {"key": "x"}}])
        self.assertEqual(7, len(validate_steps(default_steps("A"))))

    def test_search_filters_before_selection_and_hides_visible_ime(self):
        root = ET.fromstring('<hierarchy><node resource-id="a11y-circuit-search" package="dev.logisim.mobile.e2e" class="android.widget.EditText" bounds="[0,0][200,48]" text="" focused="false" clickable="true"/></hierarchy>')
        class FakeUi:
            package = "dev.logisim.mobile.e2e"
            def __init__(self): self.inputs, self.keys = [], []
            def screen(self): return root
            def input_text(self, field, query):
                self.inputs.append(query)
                field.set("text", query)
                field.set("focused", "true")
            def adb(self, *command):
                if command == ("shell", "dumpsys", "input_method"): return "isInputViewShown=true"
                raise AssertionError(f"Unexpected fake command: {command}")
            def key(self, code): self.keys.append(code)
        ui = FakeUi()
        driver = Checkpoints(ui, Catalog.__new__(Catalog), (320, 568), 160)
        _, result = driver.search_component("Reset")
        self.assertEqual(["Reset"], ui.inputs)
        self.assertEqual([4], ui.keys)
        self.assertEqual("Reset", result["field_after_typing"]["attributes"]["text"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    parser.add_argument("--package")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--catalog-root", type=Path)
    parser.add_argument("--harness-dir", type=Path)
    parser.add_argument("--adb", default=str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb"))
    parser.add_argument("--inventory-only", action="store_true")
    parser.add_argument("--component-name")
    parser.add_argument("--component-search", default="Reset", help="ASCII search word for retention; default Reset requires the installed SAP-1 fixture")
    parser.add_argument("--scenario", type=Path)
    parser.add_argument("--density", type=int, default=160)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return 0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(HermeticTests)).wasSuccessful() else 1
    if not all((args.serial and args.serial.strip(), args.package, args.output, args.catalog_root)):
        parser.error("--serial, --package, --output, and --catalog-root are required")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\.e2e", args.package):
        parser.error("--package must be a valid application ID ending in .e2e")
    if not 72 <= args.density <= 1000:
        parser.error("--density must be between 72 and 1000")
    if not re.fullmatch(r"[A-Za-z0-9_-]{3,80}", args.component_search):
        parser.error("--component-search must be an ASCII search word of 3 to 80 letters, digits, underscores, or hyphens")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    catalog = Catalog(args.catalog_root.resolve())
    AndroidUi = load_android_ui(args.harness_dir.resolve() if args.harness_dir else None)
    ui = AndroidUi(args.adb, args.serial, args.package, output)
    if args.inventory_only:
        write_json(output / "inventory.json", inventory(ui))
        print("INVENTORY: device getters only; no UI or settings changed")
        return 0
    steps = validate_steps(json.loads(args.scenario.read_text())["steps"] if args.scenario else default_steps(args.component_name or args.component_search))
    report = run_matrix(ui, catalog, steps, output, args.density, True, args.component_search)
    print(f"{report['status']}: {output / 'results.json'}; TalkBack remains NOT_RUN")
    return {"PASS": 0, "FAIL": 1, "BLOCKED": 2}[report["status"]]


if __name__ == "__main__":
    raise SystemExit(main())
