"""Pure archive checks for the selected Editor36 workflow; no live device access."""
from collections import Counter
import json
import math
import re
import xml.etree.ElementTree as ET


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def point(raw):
    match = re.fullmatch(r"\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)", raw or "")
    assert match, ("invalid circuit point", raw)
    return [int(match[1]), int(match[2])]


def parse_document(raw):
    assert b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper()
    project = ET.fromstring(raw)
    assert project.tag == "project"
    circuits = project.findall("circuit")
    names = [circuit.get("name") for circuit in circuits]
    assert len(names) == 1 and names[0]
    assert project.find("main").get("name") == names[0]
    components, wires, identities = [], [], {}
    for circuit in circuits:
        for component in circuit.findall("comp"):
            attributes = {}
            for attribute in component.findall("a"):
                name = attribute.get("name")
                assert name and name not in attributes and "val" in attribute.attrib
                assert not (attribute.text or "").strip()
                attributes[name] = attribute.get("val")
            at = point(component.get("loc"))
            identity = component.get("logisim-mobile-id")
            assert identity and identity not in identities.values() and tuple(at) not in identities
            identities[tuple(at)] = identity
            components.append({"name": component.get("name"), "location": at, "attributes": attributes})
        for wire in circuit.findall("wire"):
            wires.append([point(wire.get("from")), point(wire.get("to"))])
    return {"circuitCount": len(circuits), "components": components, "wires": wires}, identities


def check_xml(raw, expected, checkpoint):
    observed, identities = parse_document(raw)
    assert observed["circuitCount"] == expected["circuitCount"] == 1
    for key in ("components", "wires"):
        assert Counter(map(canonical, observed[key])) == Counter(map(canonical, expected[key])), (checkpoint, key)
    # These literal terminal coordinates come from the fixed three-component contract.
    terminals = []
    if checkpoint in ("wired", "pasted"):
        terminals.append(frozenset(((100, 100), (170, 100))))
    if checkpoint in ("wired", "pasted", "wire-removed"):
        terminals.append(frozenset(((200, 100), (300, 100))))
    if checkpoint == "pasted":
        terminals += [frozenset(((120, 120), (190, 120))), frozenset(((220, 120), (320, 120)))]
    assert Counter(frozenset(map(tuple, pair)) for pair in observed["wires"]) == Counter(terminals)
    expected_locations = {tuple(component["location"]) for component in expected["components"]}
    assert set(identities) == expected_locations
    return observed, identities


def check_step_notes(notes, steps, native_pass):
    """notes is [(step-number, raw attachment bytes)], sorted in observed order."""
    assert [number for number, _ in notes] == list(range(1, len(notes) + 1))
    assert 0 <= len(notes) <= len(steps) == 62
    for number, raw in notes:
        match = re.fullmatch(r"step=(\d+), action=(\w+), elapsed=([0-9.]+), status=passed", raw.decode())
        assert match and int(match[1]) == number and match[2] == steps[number - 1]["action"]
        assert math.isfinite(float(match[3])) and float(match[3]) >= 0
    if native_pass:
        assert len(notes) == 62
    return len(notes)


def check_zoom(observed, steps):
    """Use native observations at the assertion steps; no iPad pixel/percent constants."""
    assert steps[52]["action"] == "captureZoom"
    assert steps[54]["action"] == "assertZoomGreater"
    assert steps[56]["action"] == "assertZoomEqual"
    assert steps[59]["action"] == "assertZoomGreater"
    assert set(observed) == {53, 55, 57, 60}
    assert all(type(value) is int and value > 0 for value in observed.values())
    assert observed[55] > observed[53]
    assert abs(observed[57] - observed[53]) <= steps[56]["tolerancePercent"]
    assert observed[60] > observed[53]


def check_identity_history(snapshots):
    """snapshots is [(checkpoint, location-to-ID mapping)] in export order."""
    original_locations = {(100, 100), (200, 100), (300, 100)}
    copy_locations = {(120, 120), (220, 120), (320, 120)}
    originals = copies = None
    for checkpoint, identities in snapshots:
        if checkpoint == 'empty':
            assert not identities
            continue
        if originals is None:
            assert checkpoint == 'components' and set(identities) == original_locations
            originals = dict(identities)
        assert {at: identities[at] for at in original_locations} == originals
        if checkpoint == 'pasted':
            assert set(identities) == original_locations | copy_locations
            current_copies = {at: identities[at] for at in copy_locations}
            assert not set(current_copies.values()) & set(originals.values())
            assert len(set(current_copies.values())) == 3
            if copies is None:
                copies = current_copies
            assert current_copies == copies
        else:
            assert set(identities) == original_locations
    return originals, copies
