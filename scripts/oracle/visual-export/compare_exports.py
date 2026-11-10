#!/usr/bin/env python3
"""Compare Mobile GIF, SVG and TikZ exports with Logisim Evolution d58a61e0 (#350, #351, #352).

Evolution side: EvolutionVisualExport.java (headless copy of ExportImage) from the fat JAR, at scale 1 for the
vector fixtures: TikZWriter writes both TikZ and SVG from one recording of the Graphics2D calls.

Mobile side:
  * GIF: GifPalette.java runs core VisualGif on the whole-circuit rasters saved by the mounted Android and iOS
    editors (the pixels the GIF exporter encodes) and compares the decoded pixels with ImageIO's GIF writer, which
    Evolution uses. RasterCompare.java (from run.py) checks the 78 contract regions and the embedded image colours.
  * SVG and TikZ: `./gradlew :composeApp:jvmTest --tests '*EvolutionVectorExportHostTest'` writes them into
    composeApp/build/evolution-vector-export through buildVisualExportAsset. The JVM host draws with Skia like iOS.

Structural checks, world coordinates (output pixels plus each frame origin, scale 1):
  wire-edges    stroked, non-black, width >= 2, axis-aligned runs on the 10-unit grid, as sets of 10-unit edges
  port-markers  filled circles of radius 2 (terminal dots), centres within 0.5
  texts         the multiset of text strings (surrounding spaces stripped); anchors within 12 units, else DIFF:
                anchors follow the canvas fonts, not the exporter (#355); svg-labels.circ holds the labelled gates,
                buffers and tunnels of #356 and the parity gates, tunnel terminals and control strokes of #358
  images        embedded raster count and placement (SVG data URI; TikZ placeholder) within 1 unit
  tikz-svg      Mobile TikZ and SVG are written from the same recording: same shape, text and image counts
  terminal-strokes  unit points under the straight 3-wide strokes (gate input lines, control strokes; Evolution's
                wires are 3 wide too and may share a path with an input line, so points on the fixture's wires are
                removed): svg-printer.circ in the printer view keeps only the lines of connected inputs (#360)

Usage: python3 -I scripts/oracle/visual-export/compare_exports.py --evolution-jar JAR [--out DIR]
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = ROOT / "scripts/oracle/visual-export"
BUILT = ROOT / "composeApp/build/evolution-vector-export"
TIMEOUT = 180
NS = "{http://www.w3.org/2000/svg}"
XLINK = "{http://www.w3.org/1999/xlink}href"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(command, log):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=TIMEOUT)
    log.write(f"$ {' '.join(map(str, command))}\nexit {result.returncode}\n--- stdout\n{result.stdout}--- stderr\n{result.stderr}\n")
    return result


def stdlib_jar():
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob(f"*/kotlin-stdlib-{version}.jar"))


# ---------- structure ----------

def on_grid(*values):
    return all(abs(v - round(v / 10) * 10) < 1e-6 for v in values)


def edges(points):
    """Axis-aligned polyline in world coordinates -> 10-unit grid edges (only runs with both ends on the grid)."""
    result = set()
    for (x1, y1), (x2, y2) in zip(points, points[1:]):
        if not on_grid(x1, y1, x2, y2) or (x1 != x2 and y1 != y2):
            continue
        if x1 == x2:
            for k in range(int(round(min(y1, y2))), int(round(max(y1, y2))), 10):
                result.add(((round(x1), k), (round(x1), k + 10)))
        else:
            for k in range(int(round(min(x1, x2))), int(round(max(x1, x2))), 10):
                result.add(((k, round(y1)), (k + 10, round(y1))))
    return result


def svg_polyline(d, ox, oy):
    """M/L/H/V-only path data -> absolute points, or None for curves or several subpaths."""
    tokens = re.findall(r"[MLHVQCZAmlhvqcza]|-?[\d.]+(?:e-?\d+)?", d)
    points, i, x, y = [], 0, None, None
    while i < len(tokens):
        op = tokens[i]
        if op == "M" and not points:
            x, y = float(tokens[i + 1]), float(tokens[i + 2]); i += 3
        elif op == "L":
            x, y = float(tokens[i + 1]), float(tokens[i + 2]); i += 3
        elif op == "H":
            x = float(tokens[i + 1]); i += 2
        elif op == "V":
            y = float(tokens[i + 1]); i += 2
        else:
            return None
        points.append((x + ox, y + oy))
    return points


def unit_points(points):
    """Axis-aligned polyline -> the integer points it covers (empty for curves)."""
    result = set()
    for (x1, y1), (x2, y2) in zip(points or [], (points or [])[1:]):
        if x1 != x2 and y1 != y2:
            continue
        x1, y1, x2, y2 = (int(round(v)) for v in (x1, y1, x2, y2))
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                result.add((x, y))
    if points and len(points) == 1:
        result.add(tuple(int(round(v)) for v in points[0]))
    return result


def fixture_wire_points(circuit):
    points = set()
    for x1, y1, x2, y2 in re.findall(r'<wire from="\((-?\d+),(-?\d+)\)" to="\((-?\d+),(-?\d+)\)"', (ROOT / circuit).read_text()):
        points |= unit_points([(int(x1), int(y1)), (int(x2), int(y2))])
    return points


def parse_svg(path, origin):
    root = ET.parse(path).getroot()
    ox, oy = origin
    shape = {"wires": set(), "ports": [], "texts": [], "images": [], "shapes": Counter(), "strokes3": set()}
    for element in root.iter():
        tag = element.tag.replace(NS, "")
        if tag in ("svg", "g", "clipPath") or element in root.findall(f".//{NS}clipPath/*"):
            continue
        stroke, fill = element.get("stroke", "none"), element.get("fill", "none")
        if tag in ("path", "rect", "circle", "ellipse"):
            shape["shapes"]["stroke" if stroke != "none" else "fill"] += 1
        if tag == "path" and stroke != "none" and float(element.get("stroke-width", "1")) == 3:
            shape["strokes3"] |= unit_points(svg_polyline(element.get("d"), ox, oy))
        if tag == "path" and stroke not in ("none", "#000000") and float(element.get("stroke-width", "1")) >= 2:
            points = svg_polyline(element.get("d"), ox, oy)
            if points:
                shape["wires"] |= edges(points)
        elif tag == "circle" and fill != "none" and stroke == "none" and float(element.get("r")) == 2:
            shape["ports"].append((float(element.get("cx")) + ox, float(element.get("cy")) + oy))
        elif tag == "text":
            shape["texts"].append(((element.text or "").strip(), float(element.get("x")) + ox, float(element.get("y")) + oy))
        elif tag == "image":
            matrix = [float(v) for v in re.findall(r"-?[\d.]+", element.get("transform", "matrix(1 0 0 1 0 0)"))]
            shape["images"].append((matrix[4] + ox, matrix[5] + oy, int(element.get("width")), int(element.get("height"))))
    return shape


TIKZ_POINT = r"\((-?[\d.]+),(-?[\d.]+)\)"


def unescape_tex(text):
    text = text.replace("\\ ", " ").replace("\\textbackslash{}", "\\").replace("\\textasciicircum{}", "^")
    text = text.replace("\\textasciitilde{}", "~").replace("\\quad{}", "\t")
    return re.sub(r"\\([{}#$%&_])", r"\1", text)


def tikz_polyline(geometry):
    """TikZ path of points joined by --, -| and |- -> the corner points."""
    parts = re.split(r" (--|-\||\|-) ", geometry)
    points = [tuple(map(float, re.match(TIKZ_POINT, parts[0]).groups()))]
    for op, target in zip(parts[1::2], parts[2::2]):
        x, y = map(float, re.match(TIKZ_POINT, target).groups())
        px, py = points[-1]
        if op == "-|":
            points.append((x, py))
        elif op == "|-":
            points.append((px, y))
        points.append((x, y))
    return points


def parse_tikz(path, origin):
    ox, oy = origin
    colours = dict(re.findall(r"\\definecolor\{(custcol_\w+)\}\{HTML\}\{(\w+)\}", Path(path).read_text()))
    shape = {"wires": set(), "ports": [], "texts": [], "images": [], "shapes": Counter(), "strokes3": set()}
    for line in Path(path).read_text().splitlines():
        command = re.match(r"\\(draw|fill) \[([^\]]*)\] (.*);", line)
        if command and "[Image:" not in line:
            kind, options, geometry = command.groups()
            shape["shapes"]["stroke" if kind == "draw" else "fill"] += 1
            colour = colours.get(next((o.strip() for o in options.split(",") if o.strip().startswith("custcol_")), ""), "")
            width = float(re.search(r"line width=([\d.]+)pt", options).group(1))
            straight = re.fullmatch(r"(" + TIKZ_POINT + r"(?: (?:--|-\||\|-) )?)+", geometry)
            if kind == "draw" and width == 3 and straight:
                shape["strokes3"] |= unit_points([(x + ox, y + oy) for x, y in tikz_polyline(geometry)])
            if kind == "fill" and re.fullmatch(TIKZ_POINT + r" circle \(2\)", geometry):
                x, y = map(float, re.match(TIKZ_POINT, geometry).groups())
                shape["ports"].append((x + ox, y + oy))
            elif kind == "draw" and colour != "000000" and width >= 2 and straight:
                shape["wires"] |= edges([(x + ox, y + oy) for x, y in tikz_polyline(geometry)])
        node = re.search(r"\\node\[[^\]]*anchor=base west[^\]]*\] at ?" + TIKZ_POINT + r" ?\{(.*)\};\}$", line)
        if node:
            x, y, text = node.groups()
            shape["texts"].append((unescape_tex(text).strip(), float(x) + ox, float(y) + oy))
        image = re.search(r"\\node\[anchor=north west, font=\\small\] at ?" + TIKZ_POINT + r" ?\{\[Image: (\d+)x(\d+)\]\}", line)
        if image:
            x, y, w, h = image.groups()
            shape["images"].append((float(x) + ox, float(y) + oy, int(w), int(h)))
    return shape


def compare(evolution, mobile, *, images=True, wire_points=None):
    def close(a, b, tolerance):
        a, b = sorted(a), sorted(b)
        return len(a) == len(b) and all(all(abs(p - q) <= tolerance for p, q in zip(u, v)) for u, v in zip(a, b))
    checks = []
    missing, extra = sorted(evolution["wires"] - mobile["wires"]), sorted(mobile["wires"] - evolution["wires"])
    checks.append(("wire-edges", not missing and not extra,
                   f"{len(evolution['wires'])} Evolution / {len(mobile['wires'])} Mobile 10-unit edges; missing {missing[:4]} extra {extra[:4]}"))
    e_ports, m_ports = sorted(set(evolution["ports"])), sorted(set(mobile["ports"]))
    checks.append(("port-markers", close(e_ports, m_ports, 0.5), f"{len(e_ports)} Evolution / {len(m_ports)} Mobile terminal dots"))
    e_texts, m_texts = Counter(t for t, _, _ in evolution["texts"] if t), Counter(t for t, _, _ in mobile["texts"] if t)
    checks.append(("texts", e_texts == m_texts, f"{sum(e_texts.values())} Evolution / {sum(m_texts.values())} Mobile strings; "
                   f"only Evolution {sorted((e_texts - m_texts).elements())[:5]} only Mobile {sorted((m_texts - e_texts).elements())[:5]}"))
    if not e_texts - m_texts:  # every Evolution text has a Mobile counterpart to anchor against
        worst = 0.0
        pool = {}
        for t, x, y in mobile["texts"]:
            pool.setdefault(t, []).append((x, y))
        for t, x, y in evolution["texts"]:
            if not t:
                continue
            candidates = pool[t]
            best = min(candidates, key=lambda p: abs(p[0] - x) + abs(p[1] - y))
            candidates.remove(best)
            worst = max(worst, abs(best[0] - x), abs(best[1] - y))
        checks.append(("text-anchors", worst <= 12, f"largest base-west anchor offset {worst:.2f} units (fonts differ; limit 12)"))
    if wire_points is not None:
        e_strokes, m_strokes = evolution["strokes3"] - wire_points, mobile["strokes3"] - wire_points
        checks.append(("terminal-strokes", e_strokes == m_strokes,
                       f"{len(e_strokes)} Evolution / {len(m_strokes)} Mobile points off the wires; only Evolution "
                       f"{sorted(e_strokes - m_strokes)[:6]} only Mobile {sorted(m_strokes - e_strokes)[:6]}"))
    if images:
        checks.append(("images", close([i[:2] for i in evolution["images"]], [i[:2] for i in mobile["images"]], 1)
                       and sorted(i[2:] for i in evolution["images"]) == sorted(i[2:] for i in mobile["images"]),
                       f"Evolution {evolution['images']} / Mobile {mobile['images']}"))
    return checks


# Text anchors follow the canvas drawing, which both exporters record unchanged: Mobile draws default component
# labels at 11 px plain (#202070) where Evolution uses SansSerif bold 16 blue, and IEC gate symbols 15.76 units lower.
CANVAS_TEXT = "; canvas text placement differs from Evolution"


# Drawing gaps tracked in separate issues, by case and check.
# Empty since #361 (printer-view terminal markers); kept for the next tracked gap.
KNOWN = {}


def status(check, ok, case=None):
    if ok:
        return "PASS"
    return "DIFF" if check == "text-anchors" or check in KNOWN.get(case, {}) else "FAIL"


def note(check, ok, case):
    if status(check, ok, case) != "DIFF":
        return ""
    return f"; tracked in {KNOWN[case][check]}" if check in KNOWN.get(case, {}) else CANVAS_TEXT


def frame_origin(path):
    fields = Path(path).read_text().split("\n")[0].split("\t")
    return float(fields[1]), float(fields[2]), int(fields[3]), int(fields[4])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evolution-jar", required=True)
    parser.add_argument("--mobile-jar", default=str(ROOT / "core/build/libs/core-jvm.jar"))
    parser.add_argument("--stdlib-jar")
    parser.add_argument("--out", default=str(ROOT / "outputs/visual-export-compare"))
    args = parser.parse_args()
    args.stdlib_jar = args.stdlib_jar or str(stdlib_jar())
    out = Path(args.out).resolve()
    logs = out / "logs"
    for name in ("evolution", "mobile", "logs"):
        shutil.rmtree(out / name, ignore_errors=True)
    logs.mkdir(parents=True)
    results = []
    with tempfile.TemporaryDirectory() as classes:
        with open(logs / "compile.txt", "w") as log:
            for classpath, sources in [
                ("", ["scripts/oracle/project-options/MemoryPreferencesFactory.java"]),
                (args.evolution_jar, ["scripts/oracle/visual-export/EvolutionVisualExport.java"]),
                (f"{args.mobile_jar}:{args.stdlib_jar}", ["scripts/oracle/visual-export/RasterCompare.java", "scripts/oracle/visual-export/GifPalette.java"]),
            ]:
                if run(["javac", "-d", classes] + (["-cp", classpath] if classpath else []) + sources, log).returncode:
                    raise SystemExit(f"javac failed; see {logs / 'compile.txt'}")
        evolution_dir = out / "evolution"
        cases = {
            # case: (fixture, circuit, scale, gate shape[, printer view])
            "qualification-raster": ("fixtures/visual-export/qualification.circ", "visual_export_qualification", "0.25", None),
            "qualification": ("fixtures/visual-export/qualification.circ", "visual_export_qualification", "1", None),
            "svg-gates-ansi": ("scripts/oracle/visual-export/svg-gates.circ", "main", "1", None),
            "svg-gates-iec": ("scripts/oracle/visual-export/svg-gates.circ", "main", "1", "rectangular"),
            # #356: labelled gates, buffers and tunnels in the four facings
            "svg-labels-ansi": ("scripts/oracle/visual-export/svg-labels.circ", "main", "1", None),
            "svg-labels-iec": ("scripts/oracle/visual-export/svg-labels.circ", "main", "1", "rectangular"),
            # #360: printer view, input lines only on inputs with a wire or another component's end
            "svg-printer": ("scripts/oracle/visual-export/svg-printer.circ", "main", "1", None, "true"),
        }
        bounds = {}
        with open(logs / "evolution-export.txt", "w") as log:
            for case, (circuit, name, scale, shape, *printer) in cases.items():
                command = ["perl", "-e", f"alarm {TIMEOUT}; exec @ARGV", "java", "-Djava.awt.headless=true",
                           "-Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory", "-cp", f"{classes}:{args.evolution_jar}",
                           "EvolutionVisualExport", circuit, name, scale, printer[0] if printer else "false", str(evolution_dir / case)] + ([shape] if shape else [])
                result = run(command, log)
                if result.returncode:
                    raise SystemExit(f"Evolution export failed; see {logs / 'evolution-export.txt'}")
                x, y = re.search(r"bounds\t(-?\d+)\t(-?\d+)", result.stdout).groups()
                bounds[case] = (float(x), float(y))

        # ----- GIF (#350)
        rasters = {"android": ROOT / "docs/validation/artifacts/android-export34-final-002/whole.png",
                   "ios": ROOT / "docs/validation/artifacts/ios-export34-final-6cef-002/whole.png"}
        with open(logs / "gif-palette.txt", "w") as log:
            result = run(["java", "-Djava.awt.headless=true", "-cp", f"{classes}:{args.mobile_jar}:{args.stdlib_jar}", "GifPalette"]
                         + [str(p.relative_to(ROOT)) for p in rasters.values()], log)
        palette = [line.split("\t") for line in result.stdout.splitlines() if line.startswith("gif\t")]
        for platform, png in rasters.items():
            target = evolution_dir / "qualification-raster/visual_export_qualification.gif"
            with open(logs / f"raster-{platform}-gif.txt", "w") as log:
                raster = run(["java", "-Djava.awt.headless=true", "-cp", f"{classes}:{args.mobile_jar}:{args.stdlib_jar}", "RasterCompare",
                              "fixtures/visual-export/contract.json", str(png.relative_to(ROOT)), str(target), "-5", "-5", "--gif-roundtrip"], log)
            checks = [line.split("\t") for line in raster.stdout.splitlines() if line.count("\t") >= 2 and not line.startswith("summary")]
            folder = png.parent.name
            checks += [c for c in palette if c[1].startswith(folder + ":")]
            # RasterCompare's quantisation limit describes the former fixed palette; the palette checks replace it.
            checks = [c for c in checks if c[1] != "mobile-quantisation"]
            results.append({"row": "format/image-gif", "case": f"{platform}-whole-gif",
                            "mobile": {"png": str(png.relative_to(ROOT)), "sha256": sha256(png)},
                            "evolution": {"file": str(target.relative_to(out)), "sha256": sha256(target)},
                            "checks": [{"check": c[0], "name": c[1], "result": c[2], "detail": c[3] if len(c) > 3 else ""} for c in checks],
                            "failed": [f"{c[0]}:{c[1]}" for c in checks if c[2] == "FAIL"]})

    # ----- SVG (#351) and TikZ (#352)
    mobile_dir = out / "mobile"
    mobile_dir.mkdir()
    if not (BUILT / "svg-gates.svg").is_file():
        raise SystemExit(f"Missing {BUILT}; run ./gradlew :composeApp:jvmTest --tests '*EvolutionVectorExportHostTest'")
    for file in BUILT.iterdir():
        shutil.copy(file, mobile_dir / file.name)
    vector_cases = [
        ("qualification", "visual_export_qualification", "visual_export_qualification", ("svg", "tex")),
        ("svg-gates-ansi", "main", "svg-gates", ("svg", "tex")),
        ("svg-gates-iec", "main", "svg-gates-iec", ("tex",)),  # the IEC SVG is the vector scene (compared by run.py)
        ("svg-labels-ansi", "main", "svg-labels", ("svg", "tex")),
        ("svg-labels-iec", "main", "svg-labels-iec", ("svg", "tex")),
        ("svg-printer", "main", "svg-printer", ("svg", "tex")),
    ]
    for case, evolution_name, mobile_name, formats in vector_cases:
        left, top, width, height = frame_origin(mobile_dir / f"{mobile_name}.frame.txt")
        parsed = {}
        for fmt in formats:
            parse = parse_svg if fmt == "svg" else parse_tikz
            evolution_file = evolution_dir / case / f"{evolution_name}.{fmt}"
            mobile_file = mobile_dir / f"{mobile_name}.{fmt}"
            e = parse(evolution_file, bounds[case])
            m = parse(mobile_file, (left, top))
            parsed[fmt] = m
            checks = compare(e, m, wire_points=fixture_wire_points(cases[case][0]) if cases[case][3] is None else None)
            checks.append(("size", True, f"Evolution {e and ''}frame from bounds {bounds[case]} / Mobile {width}x{height} from {left},{top} "
                           "(Mobile bounds include strokes and terminal dots)"))
            checks.append(("shape-counts", True, f"Evolution {dict(e['shapes'])} / Mobile {dict(m['shapes'])} (canvas styles differ)"))
            row = "format/image-svg" if fmt == "svg" else "format/image-tikz"
            results.append({"row": row, "case": case,
                            "mobile": {"file": f"mobile/{mobile_file.name}", "sha256": sha256(mobile_file)},
                            "evolution": {"file": str(evolution_file.relative_to(out)), "sha256": sha256(evolution_file)},
                            "checks": [{"check": c, "result": status(c, ok, case), "detail": d + note(c, ok, case)}
                                       for c, ok, d in checks],
                            "failed": [c for c, ok, _ in checks if status(c, ok, case) == "FAIL"],
                            "differences": [c for c, ok, _ in checks if status(c, ok, case) == "DIFF"]})
        if len(parsed) == 2:
            s, t = parsed["svg"], parsed["tex"]
            same = (s["shapes"] == t["shapes"] and len(s["texts"]) == len(t["texts"]) and len(s["images"]) == len(t["images"])
                    and s["wires"] == t["wires"] and sorted(s["ports"]) == sorted(t["ports"]))
            results[-1]["checks"].append({"check": "tikz-svg", "result": "PASS" if same else "FAIL",
                                          "detail": f"SVG {dict(s['shapes'])}, {len(s['texts'])} texts / TikZ {dict(t['shapes'])}, {len(t['texts'])} texts"})
            if not same:
                results[-1]["failed"].append("tikz-svg")
    summary = {
        "evolution_jar_sha256": sha256(args.evolution_jar),
        "mobile_jar_sha256": sha256(args.mobile_jar),
        "fixtures": {name: sha256(ROOT / name) for name in ("fixtures/visual-export/qualification.circ", "fixtures/visual-export/contract.json",
                                                            "scripts/oracle/visual-export/svg-gates.circ",
                                                            "scripts/oracle/visual-export/svg-labels.circ",
                                                            "scripts/oracle/visual-export/svg-printer.circ")},
        "results": results,
    }
    (out / "results.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False) + "\n")
    for result in results:
        state = "FAIL " + ",".join(result["failed"]) if result["failed"] else ("DIFF " + ",".join(result.get("differences", [])) if result.get("differences") else "PASS")
        print(f"{result['row']}\t{result['case']}\t{state}")
    return 0 if all(not r["failed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
