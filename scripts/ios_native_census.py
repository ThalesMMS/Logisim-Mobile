#!/usr/bin/env python3
"""Summarize exported XCTest native trees without querying a device.

Read manifest.json groups containing testIdentifier and attachments. Select
attachments by suggestedHumanReadableName matching native-tree*.txt, then read
their exportedFileName. Never select opaque exported filenames by their UUID.

Parse only the Element subtree section of XCUIApplication.debugDescription.
Attributes, Path to element, and Query chain repeat elements and are excluded.
Each node starts with a class and a hexadecimal address. Native frames use
{{x, y}, {width, height}} in XCTest points. Unrecognized lines are counted,
not interpreted as nodes. Relevant records retain their original line and
one-based source line number, plus ancestors for inspecting native viewports.

This is an evidence inventory, not an accessibility pass/fail result. In
particular, zero-size padding keys can be intentional framework elements.
The source manifest and attachments are never modified. --output writes a
separate JSON result; omitting it prints JSON to stdout.
"""

from __future__ import annotations

import argparse
from collections import Counter
import fnmatch
import json
import math
from pathlib import Path
import re
import sys


NODE = re.compile(r"^\s*(?:→)?(?P<class>[A-Za-z][A-Za-z0-9 ]*?)(?:\s+\([^)]*\))?,\s+0x[0-9a-fA-F]+\b")
FRAME = re.compile(r"\{\{\s*([^,{}]+),\s*([^{}]+)\},\s*\{\s*([^,{}]+),\s*([^{}]+)\}\}")
IDENTIFIER = re.compile(r"\bidentifier: '([^']*)'")
WATCH_IDS = ("editor-tool-sheet", "editor-tool-menu", "a11y-circuit-search")
LABELS = {
    "project_tools": re.compile(r"\b(?:Project tools|Ferramentas do projeto)\b", re.I),
    "close": re.compile(r"\b(?:Close|Fechar)\b", re.I),
    "explorer": re.compile(r"\b(?:Explorer|Explorador)\b", re.I),
}


def parse_frame(line):
    found = FRAME.search(line)
    if not found:
        return None, ["missing_or_unrecognized_frame"]
    try:
        values = [float(value.strip()) for value in found.groups()]
    except ValueError:
        return None, ["invalid_numeric_frame"]
    if not all(math.isfinite(value) for value in values):
        return None, ["non_finite_frame"]
    x, y, width, height = values
    issues = []
    if width == 0 or height == 0:
        issues.append("zero_extent")
    if width < 0 or height < 0:
        issues.append("negative_extent")
    if not math.isfinite(x + width) or not math.isfinite(y + height):
        issues.append("overflowing_frame")
    return {"x": x, "y": y, "width": width, "height": height}, issues


def parse_tree(text, source):
    classes, keyboard_classes = Counter(), Counter()
    ids = {identifier: [] for identifier in WATCH_IDS}
    labels = {name: [] for name in LABELS}
    keyboard_issues, keyboard_lines, ancestors = [], [], []
    inside, found_subtree, unparsed = False, False, []
    for number, original in enumerate(text.splitlines(), 1):
        if original.strip() == "Element subtree:":
            inside = found_subtree = True
            continue
        if inside and original.strip() in ("Path to element:", "Query chain:"):
            break
        if not inside:
            continue
        matched = NODE.match(original)
        if not matched:
            if original.strip():
                unparsed.append({"line_number": number, "original_line": original})
            continue
        kind = matched.group("class").strip()
        frame, issues = parse_frame(original)
        identifier_match = IDENTIFIER.search(original)
        record = {"source": source, "line_number": number, "class": kind,
                  "identifier": identifier_match.group(1) if identifier_match else None,
                  "frame": frame, "original_line": original}
        indentation = len(original) - len(original.lstrip())
        while ancestors and ancestors[-1][0] >= indentation:
            ancestors.pop()
        record["ancestors"] = [entry for _, entry in ancestors]
        # Do not nest ancestor lists recursively; source lines are sufficient evidence.
        ancestors.append((indentation, {key: value for key, value in record.items() if key != "ancestors"}))
        classes[kind] += 1
        if record["identifier"] in ids:
            ids[record["identifier"]].append(record)
        for name, pattern in LABELS.items():
            if pattern.search(original):
                labels[name].append(record)
        if kind in ("Keyboard", "Key"):
            keyboard_classes[kind] += 1
            keyboard_lines.append(record)
            if issues:
                keyboard_issues.append({**record, "frame_issues": issues})
    return {"source": source, "element_subtree_found": found_subtree,
            "class_counts": dict(sorted(classes.items())), "identifiers": ids,
            "label_lines": labels, "keyboard": {"class_counts": dict(sorted(keyboard_classes.items())),
                "lines": keyboard_lines, "invalid_or_zero_frames": keyboard_issues},
            "unparsed_subtree_lines": unparsed}


def census(directory):
    directory = directory.resolve()
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, list):
        raise ValueError("Expected an xcresulttool attachment manifest containing a list of test groups")
    cases, source_paths = {}, {manifest_path}
    for group in manifest:
        identifier = group["testIdentifier"]
        case = cases.setdefault(identifier, {"test_identifier": identifier,
            "test_identifier_url": group.get("testIdentifierURL"), "trees": []})
        for attachment in group["attachments"]:
            path = (directory / attachment["exportedFileName"]).resolve()
            if not path.is_relative_to(directory):
                raise ValueError(f"Exported attachment escapes its directory: {path}")
            source_paths.add(path)
            suggested = attachment.get("suggestedHumanReadableName", "")
            if not fnmatch.fnmatchcase(suggested, "native-tree*.txt"):
                continue
            source = {"exported_file_name": attachment["exportedFileName"],
                      "suggested_human_readable_name": suggested, "timestamp": attachment.get("timestamp")}
            tree = parse_tree(path.read_text(), source)
            tree["attachment_metadata"] = attachment
            case["trees"].append(tree)
    for case in cases.values():
        counts = Counter()
        for tree in case["trees"]:
            counts.update(tree["class_counts"])
        case["tree_count"] = len(case["trees"])
        case["class_counts_across_snapshots"] = dict(sorted(counts.items()))
        case["identifier_counts_across_snapshots"] = {
            identifier: sum(len(tree["identifiers"][identifier]) for tree in case["trees"])
            for identifier in WATCH_IDS}
        case["keyboard_invalid_or_zero_frame_count"] = sum(
            len(tree["keyboard"]["invalid_or_zero_frames"]) for tree in case["trees"])
    result = {"schema": 1, "attachments_directory": str(directory), "manifest": str(manifest_path),
              "frame_units": "XCTest native points", "attachment_selection": "suggestedHumanReadableName: native-tree*.txt",
              "test_cases": list(cases.values())}
    result["summary"] = {"test_case_count": len(cases),
        "native_tree_count": sum(case["tree_count"] for case in cases.values()),
        "tests_without_native_tree": [case["test_identifier"] for case in cases.values() if not case["trees"]],
        "keyboard_invalid_or_zero_frame_count": sum(case["keyboard_invalid_or_zero_frame_count"] for case in cases.values())}
    return result, source_paths


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--attachments", required=True, type=Path, help="Exported attachment directory containing manifest.json")
    parser.add_argument("--output", type=Path, help="Separate JSON artifact; stdout when omitted")
    args = parser.parse_args(argv)
    try:
        result, source_paths = census(args.attachments)
        data = json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
        if args.output:
            target = args.output.resolve()
            if target in source_paths:
                raise ValueError("The output must not overwrite the manifest or a source native tree")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(data)
        else:
            sys.stdout.write(data)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Census failed: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
