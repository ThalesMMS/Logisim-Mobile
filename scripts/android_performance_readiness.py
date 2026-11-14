"""Pure checks for a future historical Android driver. No UI actions or process calls."""
from dataclasses import dataclass
from collections import Counter
import re
import xml.etree.ElementTree as ET


class QualificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise QualificationError(message)


def evolution_label(initial):
    """Mobile's EvolutionNames.validLabel (#321): Evolution d58a61e0 XmlReader.generateValidVHDLLabel with the
    random 8-hex suffix replaced by FNV-1a of the old text, so the renamed name is predictable."""
    trimmed = initial.strip("".join(map(chr, range(33))))
    label = (trimmed or "L_").replace("!", "NOT_").replace("~", "NOT_")
    if not re.match(r"[A-Za-z]", label):
        label = "L_" + label
    label = re.sub(r"_+", "_", re.sub(r"[^A-Za-z0-9_]", "_", label)).removesuffix("_")
    if label == trimmed:
        return label
    digest = 0x811C9DC5
    for byte in initial.encode():
        digest = ((digest ^ byte) * 0x01000193) & 0xFFFFFFFF
    return f"{label}_{digest:08x}".replace("-", "_")


# independent-16 names its circuit and pins with "-", which Evolution (and Mobile since #321) renames on open.
SOURCE_CIRCUIT = "gates-16"
IMPORTED_CIRCUIT = evolution_label(SOURCE_CIRCUIT)


@dataclass(frozen=True)
class ReadinessText:
    report_title: str
    source_summary: str
    document_template: str
    circuit_summary: str
    close: str
    canvas_prefix: str
    run: str
    pause: str
    steps_template: str
    error_controls: frozenset[str]
    rename_notice: str | None = None
    circuit: str = IMPORTED_CIRCUIT

    @classmethod
    def from_catalog(cls, catalog, locale, *, renamed=True, counter_key="simulation_ticks"):
        """Select one explicit locale from the caller's existing message catalog. ``renamed=False`` describes
        builds before #321, which kept the source name ``gates-16``; ``counter_key="simulation_steps"`` builds
        before the simulation controls named their counter "Ticks N"."""
        def text(key):
            value = catalog[key][locale]
            require(isinstance(value, str) and value, f"missing localized text: {key}/{locale}")
            return value
        circuit = IMPORTED_CIRCUIT if renamed else SOURCE_CIRCUIT
        return cls(text("import_report_title"), text("import_report_source").format(version="3.8.0", count=1),
                   text("import_report_origin"), text("import_report_circuit").format(name=circuit),
                   text("action_close"), text("a11y_canvas").split("{outputs}", 1)[0].strip(),
                   text("action_run"), text("action_pause"), text(counter_key),
                   frozenset(value for key in ("action_failed_title", "action_understood")
                             for value in catalog[key].values()),
                   text("evolution_name_renamed_on_load").format(old=SOURCE_CIRCUIT, new=IMPORTED_CIRCUIT) if renamed else None,
                   circuit)


def _parse(data, tag):
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise QualificationError(f"invalid {tag} XML") from error
    require(root.tag == tag, f"expected {tag} XML")
    return root


def _rectangle(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if match:
        left, top, right, bottom = map(int, match.groups())
        if right > left and bottom > top:
            return left, top, right, bottom
    return None


def _visible_nodes(root):
    parents = {child: parent for parent in root.iter() for child in parent}
    window = next(root.iter("node"), None)
    window_bounds = _rectangle(window) if window is not None else None
    def contains(outer, inner):
        return outer is not None and inner is not None and outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]
    def visible(node):
        frame = _rectangle(node)
        if not contains(window_bounds, frame):
            return False
        ancestor = node
        while ancestor is not None:
            if ancestor.get("visible-to-user") == "false":
                return False
            if ancestor is not node and ancestor.get("scrollable") == "true" and not contains(_rectangle(ancestor), frame):
                return False
            ancestor = parents.get(ancestor)
        return True
    return [node for node in root.iter("node") if visible(node)]


def _screen(data, text, package):
    nodes = _visible_nodes(_parse(data, "hierarchy"))
    system_errors = {"android:id/alertTitle", "android:id/aerr_close", "android:id/aerr_wait", "android:id/message"}
    require(not any(node.get("text") in text.error_controls or node.get("content-desc") in text.error_controls or
                    node.get("resource-id") in system_errors or node.get("class") == "android.app.Dialog"
                    for node in nodes), "visible error dialog blocks qualification")
    return [node for node in nodes if node.get("package") == package]


def _one(nodes, predicate, label):
    matches = [node for node in nodes if predicate(node)]
    require(len(matches) == 1, f"expected one visible {label}, found {len(matches)}")
    return matches[0]


def qualify_import_report(ui_xml, *, fixture_name, text, package):
    """Recognize only the expected fixture's report. Do not dismiss or wait for it.

    Since C145 only behavior-changing adjustments open the report by itself; independent-16 qualifies because its
    circuit and pin names are renamed on open (#321). A fixture with version defaults alone would open the editor
    with an adjustments status instead, and this check would rightly find no report."""
    require(re.fullmatch(r"e2e-[0-9a-f]{32}\.circ", fixture_name), "expected an exact provider fixture UUID filename")
    nodes = _screen(ui_xml, text, package)
    report = _one(nodes, lambda node: node.get("resource-id") == "editor-import-report", "import report")
    _one(nodes, lambda node: node.get("text") == text.report_title, "localized report title")
    _one(nodes, lambda node: node.get("text") == text.close, "localized Close control")
    report_nodes = [node for node in report.iter("node") if node in nodes]
    for label, expected in (("source summary", text.source_summary), ("fixture UUID", text.document_template.format(origin=fixture_name))):
        _one(report_nodes, lambda node: node.get("text") == expected, label)
    if text.rename_notice:
        # The rename groups come first and each repeats the circuit summary, so it is not unique.
        _one(report_nodes, lambda node: node.get("text") == text.rename_notice, "circuit rename notice")
        require(any(node.get("text") == text.circuit_summary for node in report_nodes), "expected visible circuit summary")
    else:
        _one(report_nodes, lambda node: node.get("text") == text.circuit_summary, "circuit summary")
    for node in nodes:
        for attribute in ("text", "content-desc"):
            names = re.findall(r"e2e-[0-9a-f]{32}\.circ", node.get(attribute, ""))
            require(all(name == fixture_name for name in names), "another fixture UUID is visible")
    require(not any(node.get("content-desc", "").startswith(text.canvas_prefix) for node in nodes), "editor Canvas is visible behind the report")
    return {"screen": "expected_import_report", "fixture_name": fixture_name, "circuit": text.circuit, "source_version": "3.8.0"}


def _point(value):
    match = re.fullmatch(r"\s*\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)\s*", value) if isinstance(value, str) else None
    require(match is not None, "invalid model location")
    return tuple(map(int, match.groups()))


def qualify_independent16_model(project_xml, *, renamed=True):
    """Check shape and topology, allowing added default attributes. This is not XML equivalence.

    ``renamed`` expects the names Mobile exports after opening the fixture (#321); ``False`` checks the source fixture."""
    name = evolution_label if renamed else (lambda label: label)
    circuit = name(SOURCE_CIRCUIT)
    project = _parse(project_xml, "project")
    require(project.get("source") == "3.8.0", "expected source version 3.8.0")
    mains, circuits = project.findall("main"), project.findall("circuit")
    require(len(mains) == 1 and mains[0].get("name") == circuit and len(circuits) == 1 and circuits[0].get("name") == circuit, f"expected only the {circuit} circuit")
    libraries = {node.get("name"): node.get("desc") for node in project.findall("lib")}
    require(set(libraries.values()) == {"#Wiring", "#Gates"} and len(libraries) == 2, "model libraries differ")
    components, wires = circuits[0].findall("comp"), circuits[0].findall("wire")
    require(len(components) == 64 and len(wires) == 16, "expected 64 components and 16 wires")
    observed = []
    for component in components:
        attrs = component.findall("a")
        require(len({node.get("name") for node in attrs}) == len(attrs), "duplicate component attribute")
        values = {node.get("name"): node.get("val") for node in attrs}
        kind = component.get("name")
        require((kind == "Pin" and libraries.get(component.get("lib")) == "#Wiring") or
                (kind == "AND Gate" and libraries.get(component.get("lib")) == "#Gates"), "expected only Wiring Pin and Gates AND components")
        common = (kind, _point(component.get("loc")), values.get("label", ""), values.get("width", "1"))
        observed.append(common + ((values.get("type", "input"), values.get("facing", "east")) if kind == "Pin" else
                                  (values.get("size", "50"), values.get("inputs", "2"), values.get("facing", "east"))))
    expected, expected_wires = [], []
    for index in range(16):
        x, y = 100 + index % 32 * 200, 100 + index // 32 * 100
        expected.extend([("Pin", (x - 50, y - 20), name(f"a-{index}"), "8", "input", "east"),
                         ("Pin", (x - 50, y + 20), name(f"b-{index}"), "8", "input", "east"),
                         ("Pin", (x + 40, y), name(f"out-{index}"), "8", "output", "west"),
                         ("AND Gate", (x, y), "", "8", "50", "2", "east")])
        expected_wires.append(tuple(sorted(((x, y), (x + 40, y)))))
    require(Counter(observed) == Counter(expected), "component types, labels, widths, facing, or locations differ from independent-16")
    observed_wires = [tuple(sorted((_point(wire.get("from")), _point(wire.get("to"))))) for wire in wires]
    require(Counter(observed_wires) == Counter(expected_wires), "wire endpoints differ from independent-16")
    return {"circuit": circuit, "components": 64, "pins": 48, "and_gates": 16, "wires": 16}


def qualify_editor(ui_xml, project_xml, *, text, package, renamed=True):
    """Qualify supplied editor/model snapshots, without proving their live correlation."""
    nodes = _screen(ui_xml, text, package)
    require(not any(node.get("resource-id") == "editor-import-report" for node in nodes), "import report is still visible")
    _one(nodes, lambda node: node.get("content-desc", "").startswith(text.canvas_prefix), "Canvas")
    action = lambda value: value.lstrip("▶Ⅱ⏸ ").strip()
    run = _one(nodes, lambda node: action(node.get("content-desc", "")) == text.run, "Run control")
    require(run.get("enabled") != "false", "Run control is disabled")
    require(not any(action(node.get(attribute, "")) == text.pause for node in nodes for attribute in ("text", "content-desc")), "Pause control is visible")
    counter = re.escape(text.steps_template).replace(re.escape("{count}"), r"([0-9]+)")
    steps = _one(nodes, lambda node: re.fullmatch(counter, node.get("text", "")), "Steps counter")
    require(steps.get("text") == text.steps_template.format(count=0), "expected Steps 0")
    return {"screen": "paused_editor_and_supplied_model", "steps": 0, **qualify_independent16_model(project_xml, renamed=renamed)}
