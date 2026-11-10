#!/usr/bin/env python3
"""Compare the Evolution and Mobile results of each menu action.

Both documents have been rewritten by Evolution's own writer (``MenuActionsOracle canon``), so the
comparison is textual after sorting the order-insensitive children of each circuit (components and
wires). Appearance objects keep their order, because the z-order is what Raise and Lower change.
"""
import argparse
import difflib
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


# Edit Circuit Appearance: Evolution builds its default drawing with OFFS = 50 and the "Courier 10 Pitch" font,
# which Java reports as its fallback family when that font is not installed; Mobile builds the same drawing at
# the origin with "Monospaced". The comparison is made relative to the anchor and without the font family.
FONT_AGNOSTIC = {"project-appearance"}


def shift_numbers(text, dx, dy):
    import re
    index = [0]

    def repl(match):
        value = float(match.group(0)) - (dx if index[0] % 2 == 0 else dy)
        index[0] += 1
        return ("%d" % value) if value == int(value) else repr(value)
    return re.sub(r"-?\d+(?:\.\d+)?", repl, text)


def relative_to_anchor(appear, font_agnostic):
    anchor = appear.find("circ-anchor")
    if anchor is None:
        return
    dx, dy = float(anchor.get("x")), float(anchor.get("y"))
    for child in appear:
        for key in ("x", "cx", "x1", "x2"):
            if key in child.attrib:
                child.set(key, shift_numbers(child.get(key), dx, dx))
        for key in ("y", "cy", "y1", "y2"):
            if key in child.attrib:
                child.set(key, shift_numbers(child.get(key), dy, dy))
        for key in ("points", "d"):
            if key in child.attrib:
                child.set(key, shift_numbers(child.get(key), dx, dy))
        if font_agnostic and "font-family" in child.attrib:
            child.set("font-family", "*")


def canonical(path: Path) -> str:
    root = ET.parse(path).getroot()
    for appear in root.iter("appear"):
        relative_to_anchor(appear, path.stem in FONT_AGNOSTIC)
    root.text = None
    for circuit in root.iter("circuit"):
        # Evolution's simulator stamps its default 1.0 Hz on whichever circuit it opened as root;
        # the canon pass reopens the file, so this stamp says nothing about the action.
        for a in list(circuit.findall("a")):
            if a.get("name") == "simulationFrequency" and a.get("val") == "1.0":
                circuit.remove(a)
        children = list(circuit)
        fixed = [c for c in children if c.tag not in ("comp", "wire")]
        sortable = sorted((c for c in children if c.tag in ("comp", "wire")),
                          key=lambda c: (c.tag, ET.tostring(c, encoding="unicode")))
        for child in children:
            circuit.remove(child)
        for child in fixed + sortable:
            circuit.append(child)
    for appear in root.iter("appear"):
        # The anchor and the ports are AppearanceElements: CircuitAppearance.paintSubcircuit skips them, so
        # only the order of the drawn shapes matters. Keep those in order and the elements as a sorted set.
        children = list(appear)
        markers = sorted((c for c in children if c.tag in ("circ-anchor", "circ-port")),
                         key=lambda c: ET.tostring(c, encoding="unicode"))
        for child in children:
            appear.remove(child)
        for child in [c for c in children if c.tag not in ("circ-anchor", "circ-port")] + markers:
            appear.append(child)
    ET.indent(root)
    return ET.tostring(root, encoding="unicode") + "\n"


def sum_of_products(text):
    """Evolution prints ~A⋅B+A⋅~B, Mobile prints !A & B | A & !B: compare the set of product terms."""
    if text is None:
        return None
    import re
    terms = re.split(r"\s*(?:\+|\||∨)\s*", text.strip())
    result = set()
    for term in terms:
        literals = [t for t in re.split(r"\s*(?:⋅|&|∧|\*|\s)\s*", term.strip("() ")) if t]
        result.add(frozenset(l.replace("~", "!").replace("¬", "!") for l in literals))
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evolution", type=Path)
    parser.add_argument("mobile", type=Path, help="Mobile documents after Evolution's canon pass")
    parser.add_argument("mobile_state", type=Path, help="directory with the Mobile state-*.json files")
    parser.add_argument("--expect", type=Path, help="JSON map case -> expected difference id")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    expected = json.loads(args.expect.read_text()) if args.expect else {}
    results = {}
    failed = False
    for evolution in sorted(args.evolution.glob("*.circ")):
        case = evolution.stem
        mobile = args.mobile / evolution.name
        if not mobile.exists():
            results[case] = {"result": "MISSING"}
            failed = True
            continue
        a, b = canonical(evolution), canonical(mobile)
        if a == b:
            results[case] = {"result": "SAME"}
        else:
            diff = list(difflib.unified_diff(a.splitlines(), b.splitlines(), "evolution", "mobile", lineterm="", n=1))
            results[case] = {"result": "DIFF", "diff": diff}
    evo_state = json.loads((args.evolution / "state.json").read_text())
    mob_state = {}
    for part in sorted(args.mobile_state.glob("state-*.json")):
        mob_state.update(json.loads(part.read_text()))
    for key in sorted(set(evo_state) | set(mob_state)):
        same = evo_state.get(key) == mob_state.get(key)
        if key.startswith("analyze.minimal.") and not same:
            same = sum_of_products(evo_state.get(key)) == sum_of_products(mob_state.get(key))
        results["state:" + key] = {"result": "SAME" if same else "DIFF",
                                   "evolution": evo_state.get(key), "mobile": mob_state.get(key)}
    for case, result in results.items():
        explained = expected.get(case)
        if result["result"] != "SAME":
            if explained:
                result["accepted"] = explained
            else:
                failed = True
        elif explained:
            result["note"] = "expected a difference that did not occur: " + explained
            failed = True
        status = result["result"] + (" (accepted: %s)" % explained if explained and result["result"] != "SAME" else "")
        print(f"{status:40} {case}")
        if result["result"] == "DIFF" and "diff" in result and not explained:
            print("\n".join("    " + line for line in result["diff"][:60]))
    if args.report:
        args.report.write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
