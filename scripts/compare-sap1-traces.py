#!/usr/bin/env python3
"""Compare all visible SAP-1 outputs in independently generated desktop/mobile traces."""
import csv
import json
import sys
from pathlib import Path

folder = Path(sys.argv[1] if len(sys.argv) > 1 else "outputs/sap1-validation")
with (folder / "mobile-trace.tsv").open() as source:
    mobile = list(csv.DictReader(source, delimiter="\t"))
with (folder / "desktop-trace.tsv").open() as source:
    desktop = list(csv.DictReader(source, delimiter="\t"))
assert len(mobile) == len(desktop) == 161, "Both traces must cover ticks 0 through 160"


def binary(value, width):
    bits = "".join("????" if digit == "U" else "EEEE" if digit == "E"
                   else f"{int(digit, 16):04b}" for digit in value)
    return bits[-width:]


differences = []
comparisons = 0
for actual, expected in zip(mobile, desktop):
    assert actual["tick"] == expected["tick"], "Clock snapshots differ"
    pins = actual.keys() - {"tick", "diagnostics", "(600,340)/"}
    required = expected.keys() - {"tick", "display", "(540,420)/Reset"}
    assert pins == required, f"SAP-1 output columns differ: missing {required - pins}, extra {pins - required}"
    for pin in actual.keys() & expected.keys() - {"tick", "diagnostics"}:
        comparisons += 1
        if actual[pin] != binary(expected[pin], len(actual[pin])):
            differences.append({"tick": int(actual["tick"]), "pin": pin,
                                "mobile": actual[pin], "desktop": expected[pin]})
    if actual["(600,340)/"] != binary(expected["display"], 8):
        differences.append({"tick": int(actual["tick"]), "pin": "display",
                            "mobile": actual["(600,340)/"], "desktop": expected["display"]})
    value = 0 if int(actual["tick"]) < 56 else 8
    assert expected["display"] == f"{value:02x}", "Desktop output differs from the SAP-1 program result"
summary = {"snapshots": len(mobile), "pin_comparisons": comparisons,
           "display_comparisons": len(mobile), "differences": len(differences),
           "first_differences": differences[:20],
           "diagnostics": sum(row["diagnostics"] != "[]" for row in mobile)}
(folder / "comparison.json").write_text(json.dumps(summary, indent=2) + "\n")
assert not differences, json.dumps(summary, indent=2)
assert not summary["diagnostics"], "Mobile simulation reported diagnostics"
print(f"PASS: {comparisons} pin values and {len(mobile)} output-register values match the desktop")
print("PASS: output changes from 00 to 08 at tick 56 and remains 08 through tick 160")
