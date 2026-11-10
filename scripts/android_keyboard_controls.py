"""Resolve the two keyboard control buttons from an observed Inspector tree."""

from dataclasses import dataclass
from pathlib import Path
import time
import xml.etree.ElementTree as ET


CONTROL_KEYS = frozenset(("io_ascii_backspace", "io_ascii_form_feed"))


@dataclass(frozen=True)
class KeyboardLabelTap:
    label: ET.Element
    target: ET.Element
    row: ET.Element
    point: tuple[int, int]


@dataclass(frozen=True)
class KeyboardRowReveal:
    label: ET.Element
    target: ET.Element
    row: ET.Element
    start: tuple[int, int]
    end: tuple[int, int]


def _inside(rect, point):
    return rect[0] <= point[0] < rect[2] and rect[1] <= point[1] < rect[3]


def keyboard_control_plan(editor, root, key):
    """Keep the button owner separate from its fully visible label touch point."""
    if key not in CONTROL_KEYS:
        raise ValueError("Only the contracted ASCII keyboard controls are supported")
    if root.tag != "hierarchy":
        return None
    scope, tag = editor.scope(root, key)
    if tag is None:
        # A wide window docks the inspector beside the canvas instead of a sheet.
        scope, tag = editor.ui.inspector(root)
    if tag not in ("editor-inspector-sheet", "editor-inspector-inline") or scope.get("enabled") == "false":
        return None
    ui = editor.ui
    parents = {child: parent for parent in root.iter() for child in parent}
    owned = set(scope.iter())
    plans = []
    for label in scope.iter("node"):
        if label.get("package") != ui.package or label.get("enabled") != "true" or \
                label.get("class", "").endswith("EditText") or not editor.matches(label, key):
            continue
        target = editor.action_node(root, label)
        if target not in owned or target.get("package") != ui.package or \
                target.get("clickable") != "true" or target.get("enabled") != "true" or \
                target.get("class", "").endswith("EditText"):
            continue
        chain = []
        current = label
        while current is not None and current is not scope:
            chain.append(current)
            current = parents.get(current)
        if current is not scope or any(node.get("enabled") == "false" or
                node.get("visible-to-user") == "false" or
                node.get("package") != ui.package for node in chain):
            continue
        row = next((node for node in chain if node.get("class", "").endswith("HorizontalScrollView")
                    and node.get("scrollable") == "true"), None)
        if row is None or target not in chain[:chain.index(row)] or not ui.visible_node(root, row):
            continue
        label_rect, target_rect, clip = (ui._rectangle(node) for node in (label, target, row))
        if label_rect is None or target_rect is None or clip is None or not (
                target_rect[0] <= label_rect[0] < label_rect[2] <= target_rect[2] and
                target_rect[1] <= label_rect[1] < label_rect[3] <= target_rect[3] and
                clip[1] < label_rect[1] < label_rect[3] < clip[3]):
            continue
        # Bounds at the horizontal edge may already be clipped by Android.
        # Only an interior label proves the entire label is observable.
        point = ((label_rect[0] + label_rect[2]) // 2, (label_rect[1] + label_rect[3]) // 2)
        if clip[0] < label_rect[0] < label_rect[2] < clip[2] and \
                editor.visible_in_scope(root, label, scope) and _inside(target_rect, point) and \
                all(_inside(ui._rectangle(node), point) for node in chain
                    if node.get("scrollable") == "true" and ui._rectangle(node) is not None):
            plans.append(KeyboardLabelTap(label, target, row, point))
            continue
        if not (label_rect[0] < clip[2] and label_rect[2] > clip[0]):
            continue
        right_edge = label_rect[2] >= clip[2] and label_rect[0] > clip[0]
        left_edge = label_rect[0] <= clip[0] and label_rect[2] < clip[2]
        if not (right_edge or left_edge):
            continue
        left = round(clip[0] + (clip[2] - clip[0]) * .2)
        right = round(clip[0] + (clip[2] - clip[0]) * .8)
        y = (clip[1] + clip[3]) // 2
        start, end = ((right, y), (left, y)) if right_edge else ((left, y), (right, y))
        if start != end and _inside(clip, start) and _inside(clip, end):
            plans.append(KeyboardRowReveal(label, target, row, start, end))
    if len(plans) > 1:
        raise AssertionError(f"Ambiguous {key}: {len(plans)} observed controls")
    return plans[0] if plans else None


def click_keyboard_control(editor, key, timeout=20):
    """Observe, optionally reveal once, then tap under one existing deadline."""
    if key not in CONTROL_KEYS:
        raise ValueError("Only the contracted ASCII keyboard controls are supported")
    deadline = time.monotonic() + timeout
    revealed = None  # The swipe direction of the first reveal.
    corrected = False

    def remaining():
        value = deadline - time.monotonic()
        if value <= 0:
            raise AssertionError(f"Timed out waiting for the visible {key} label")
        return value

    while True:
        root = editor.ui.screen(timeout=remaining())
        plan = keyboard_control_plan(editor, root, key)
        remaining()
        # In a narrow docked inspector the first swipe can carry the label past the opposite edge.
        overshot = isinstance(plan, KeyboardRowReveal) and revealed is not None and not corrected \
            and (plan.start[0] > plan.end[0]) != revealed
        if plan is not None and (isinstance(plan, KeyboardLabelTap) or revealed is None or overshot):
            stage = "tap" if isinstance(plan, KeyboardLabelTap) else "reveal-horizontal"
            filename = f"io-keyboard-{key}-{stage}.xml"
            (Path(editor.ui.output) / filename).write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
            record = {"recordType": "keyboard-control-observation", "key": key, "stage": stage,
                      "labelBounds": plan.label.get("bounds"), "targetBounds": plan.target.get("bounds"),
                      "rowBounds": plan.row.get("bounds"), "tree": filename, "commandCompleted": False}
            editor.records.append(record)
            if isinstance(plan, KeyboardLabelTap):
                record["point"] = list(plan.point)
                editor.ui.tap(plan.label, timeout=remaining())
                record["commandCompleted"] = True
                return
            start, end = plan.start, plan.end
            if overshot:
                end = ((start[0] + end[0]) // 2, end[1])
                corrected = True
            record.update(start=list(start), end=list(end))
            editor.ui._require_test_app()
            editor.ui.adb("shell", "input", "swipe", *(str(value) for value in (*start, *end)),
                          "250", timeout=remaining())
            record["commandCompleted"] = True
            if revealed is None:
                revealed = start[0] > end[0]
        else:
            time.sleep(min(.2, remaining()))
