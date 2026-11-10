#!/usr/bin/env python3
"""Compare the native Files actors preserved in XCTest hierarchy attachments."""
import argparse
import hashlib
import json
from pathlib import Path
import re

PREMISE = "Every picker state exposes the same directory-root identity and a local-device row that needs tapping."
NODE = re.compile(r"^(\s*)(Window(?: \(Main\))?|NavigationBar|StaticText|Cell|Other|Button),")
VALUE = {key: re.compile(key + r": '([^']*)'") for key in ("identifier", "label")}


def census(path):
    raw = path.read_bytes()
    nodes = []
    for number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
        match = NODE.match(line)
        if not match:
            continue
        node = {"line": number, "indent": len(match[1]), "role": match[2],
                "selected": ", Selected" in line, "disabled": ", Disabled" in line}
        for key, pattern in VALUE.items():
            value = pattern.search(line)
            node[key] = value[1] if value else ""
        nodes.append(node)
    bars = []
    for index, node in enumerate(nodes):
        if node["role"] != "NavigationBar":
            continue
        labels = []
        actions = []
        for child in nodes[index + 1:]:
            if child["indent"] <= node["indent"]:
                break
            if child["role"] == "StaticText":
                labels.append(child["label"])
            elif child["role"] == "Button" and not child["identifier"]:
                actions.append(child["label"])
        bars.append({**node, "descendantHeadings": labels, "unidentifiedTitleButtons": actions})
    return {"path": str(path.resolve()), "sha256": hashlib.sha256(raw).hexdigest(),
            "windows": [n for n in nodes if n["role"].startswith("Window")],
            "directoryRoots": [n for n in nodes if n["identifier"].startswith("DOC.browsingRoot Source: ")],
            "navigationBars": bars,
            "selectedCells": [n for n in nodes if n["role"] == "Cell" and n["selected"]],
            "containerCells": [n for n in nodes if n["role"] == "Cell" and n["identifier"].endswith(", Container")],
            "hittability": "NOT_RECORDED_IN_HIERARCHY_TEXT"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trees", nargs="+", type=Path)
    args = parser.parse_args()
    print(json.dumps({"premise": PREMISE, "observations": [census(path) for path in args.trees]}, indent=2))
