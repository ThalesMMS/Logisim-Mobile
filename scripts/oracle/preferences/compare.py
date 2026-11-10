#!/usr/bin/env python3
"""Joins fixtures/preferences/evolution.tsv (PreferencesOracle, Evolution d58a61e0) with mobile.tsv
(EditorPreferencesEvolutionTest) per parity-matrix row of the Preferences group.

A row is SAME when every Evolution fact for its preference has the same Mobile value. Facts:
- pref default / dark / options: the default value, the dark-theme default and the choices, in Evolution's vocabulary;
- char: the 4-bit 1010, unknown, error and don't-care rendering with each value character;
- poke: the Wire Poke reading for each radix as primary and as secondary;
- vhdl: each reserved word with upper and lower case keywords;
- pin: the pin dot diameter and offset;
- drc: which warning groups (open pins, gated clocks) the FPGA review reports with each suppression setting;
- board: the board selected before the user chooses one, and the built-in boards;
- locale-default: the gate shape default with a Russian system language;
- template: the project template choices.
Panel rows take the worst result of the preferences their Evolution source edits.

Usage: compare.py [--output comparison.tsv]   (exit status 0; the TSV lists SAME and DIFF rows)
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "fixtures/preferences"

OPEN = {"NetList_UnconnectedInputs", "NetList_UnconnectedInput", "NetList_UnconnectedOutput",
        "fpga_core_terminal_open", "fpga_core_circuit_input_open", "fpga_core_circuit_output_open"}
GATED = {"NetList_GatedClock", "NetList_GatedClockSink", "NetList_GatedClockInt", "NetList_GatedClockSource",
         "NetList_PossibleGatedClock", "NetList_CircuitGatedNotGated", "NetList_CircuitGated", "NetList_CircuitNotGated",
         "fpga_core_clock_gated", "fpga_core_clock_external", "fpga_core_clock_mixed_instances"}


# Choices shown as a list whose order is the panel's, not a setting (languages, toolbar sides).
UNORDERED = {"LOCALE", "TOOLBAR_PLACEMENT"}


def load(path: Path) -> dict[tuple[str, str, str], str]:
    facts = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        section, key, field, value = (line.split("\t") + ["", "", "", ""])[:4]
        facts[(section, key, field)] = value
    return facts


def drc_groups(value: str) -> str:
    """Warning groups present ("open", "gated") or "other:" keys, ignoring how many messages each group has."""
    keys = re.findall(r"([\w:]+)=\d+", value.split(" ", 1)[1] if value.startswith("result=") else value)
    groups = set()
    for key in keys:
        groups.add("open" if key in OPEN else "gated" if key in GATED else "other:" + key)
    return ",".join(sorted(groups)) or "none"


def facts_for(name: str, evolution: dict) -> list[tuple[str, str, str]]:
    keys = [k for k in evolution if k[0] == "pref" and k[1] == name and k[2] != "type"]
    keys += [k for k in evolution if k[0] == "char" and k[1] == name]
    keys += [k for k in evolution if k[0] == "poke" and k[1].startswith(name + "=")]
    if name in ("SuppressGatedClockWarnings", "SuppressOpenPinWarnings"):
        keys += [k for k in evolution if k[0] == "drc"]
    if name == "VhdlKeywordsUpperCase":
        keys += [k for k in evolution if k[0] == "vhdl"]
    if name == "PinAppearance":
        keys += [k for k in evolution if k[0] == "pin"]
    if name == "SelectedBoard":
        keys += [k for k in evolution if k[0] == "board"]
    if name == "GATE_SHAPE":
        keys += [k for k in evolution if k[0] == "locale-default"]
    return keys


def compare(name: str, evolution: dict, mobile: dict) -> tuple[str, list[str]]:
    keys = facts_for(name, evolution)
    if not keys:
        return "NO_FACTS", []
    differences = []
    for key in keys:
        want, got = evolution[key], mobile.get(key)
        if key[0] == "pref" and key[2] == "options" and key[1] in UNORDERED:
            want, got = ",".join(sorted(want.split(","))), (",".join(sorted(got.split(","))) if got is not None else None)
        if key[0] == "drc":
            want, got = drc_groups(want), (drc_groups(got) if got is not None else None)
        if got != want:
            differences.append(f"{'/'.join(key)}: Evolution {want!r}, Mobile {got!r}")
    return ("SAME" if not differences else "DIFF"), differences


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--rows", nargs="*", help="matrix keys (default: every preference row of the reference)")
    args = parser.parse_args()
    evolution = load(FIXTURES / "evolution.tsv")
    mobile = load(FIXTURES / "mobile.tsv")
    reference = json.loads((ROOT / "parity/evolution-d58a61e0.json").read_text())
    keys = args.rows or [entry["key"] for entry in reference["preferences"]]
    lines = ["row\tresult\tfacts\tdifferences"]
    results = {}
    for entry in reference["preferences"]:
        kind, name = entry["key"].split("/", 1)
        if kind == "preference":
            result, differences = compare(name, evolution, mobile)
            results[entry["key"]] = result
            if entry["key"] in keys:
                lines.append(f"{entry['key']}\t{result}\t{len(facts_for(name, evolution))}\t{' ; '.join(differences)}")
    # Members outside the scope of the campaign (desktop-only rows) do not decide a panel.
    status = {row["key"]: row["status"] for row in json.loads((ROOT / "docs/parity.json").read_text())["product"]}
    for key in keys:
        kind, name = key.split("/", 1)
        if kind != "preference-panel":
            continue
        source = evolution.get(("panel-source", name, "members"), "")
        members = [f"preference/{m}" for m in source.split(",") if f"preference/{m}" in results]
        if name == "HotkeyOptions":
            # HotkeyOptions lists every PrefMonitorKeyStroke of AppPreferences by reflection.
            members = [key for key in results if key.startswith("preference/HOTKEY_")]
        members = [m for m in members if status.get(m) != "fora_de_escopo"]
        own = [f"{m.split('/')[1]}={results[m]}" for m in members]
        if name == "TemplateOptions":
            want = evolution.get(("template", "TEMPLATE_TYPE", "options"))
            got = mobile.get(("template", "TEMPLATE_TYPE", "options"))
            own.append(f"TEMPLATE_TYPE={'SAME' if want == got else 'DIFF'}")
            if want != got:
                own[-1] += f" (Evolution {want!r}, Mobile {got!r})"
        result = "SAME" if own and all(o.split("=", 1)[1].startswith("SAME") for o in own) else "DIFF"
        lines.append(f"{key}\t{result}\t{len(own)}\t{' ; '.join(own)}")
    text = "\n".join(lines) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
