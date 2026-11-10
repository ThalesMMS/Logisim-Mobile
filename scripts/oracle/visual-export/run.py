#!/usr/bin/env python3
"""Compare Mobile visual exports (GIF, JPEG, SVG, TikZ, WaveDrom) with Logisim Evolution d58a61e0.

Inputs (all JVM/host):
  * Evolution: EvolutionVisualExport.java (headless copy of ExportImage) and WaveDromDesktopOracle.java
    (Evolution's chronogram Model plus the body of ChronoPanel.exportWaveDrom), run from the given fat JAR.
  * Mobile rasters: the whole-circuit PNGs saved by the mounted Android and iOS editors for
    fixtures/visual-export/qualification.circ (docs/validation/artifacts/*/whole.png, historical evidence at the
    revisions in their receipts). GIF and JPEG encode the same renderDrawingBitmap pixels; this script runs the Mobile
    GIF encoder (core VisualGif) on them. The Mobile JPEG encoder is the platform codec and does not run on the JVM.
  * Mobile SVG: written by `./gradlew :composeApp:testAndroidHostTest --tests '*EvolutionVisualExportOracleTest'`
    into composeApp/build/evolution-visual-export (run it first; pass --skip-svg to omit that part).
  * Mobile WaveDrom: WaveDromMobileOracle.java on core-jvm.jar (TraceRecorder.exportWaveDrom).

Usage: python3 -I scripts/oracle/visual-export/run.py --evolution-jar JAR [--mobile-jar core/build/libs/core-jvm.jar]
       [--stdlib-jar JAR] [--out DIR]
"""
import argparse
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
TIMEOUT = 180


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(command, log, **kwargs):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=TIMEOUT, **kwargs)
    log.write(f"$ {' '.join(map(str, command))}\nexit {result.returncode}\n--- stdout\n{result.stdout}--- stderr\n{result.stderr}\n")
    return result


def stdlib_jar():
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob(f"*/kotlin-stdlib-{version}.jar"))


# ---------- raster ----------

def raster(args, classes, out, logs, results):
    evolution_dir = out / "evolution"
    with open(logs / "evolution-export.txt", "w") as log:
        for printer, shape, circuit, name, directory in [
            ("false", None, "fixtures/visual-export/qualification.circ", "visual_export_qualification", evolution_dir / "qualification"),
            ("true", None, "fixtures/visual-export/qualification.circ", "visual_export_qualification", evolution_dir / "qualification-printer"),
            ("false", "rectangular", "scripts/oracle/visual-export/svg-gates.circ", "main", evolution_dir / "svg-gates-iec"),
            ("false", None, "scripts/oracle/visual-export/svg-gates.circ", "main", evolution_dir / "svg-gates-ansi"),
        ]:
            command = ["java", "-Djava.awt.headless=true", "-Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory",
                       "-cp", f"{classes}:{args.evolution_jar}", "EvolutionVisualExport", circuit, name, "0.25" if "qualification" in circuit else "1",
                       printer, str(directory)] + ([shape] if shape else [])
            if run(command, log).returncode:
                raise SystemExit(f"Evolution export failed; see {logs / 'evolution-export.txt'}")
    mobile = {
        "android": ROOT / "docs/validation/artifacts/android-export34-final-002/whole.png",
        "ios": ROOT / "docs/validation/artifacts/ios-export34-final-6cef-002/whole.png",
    }
    for platform, png in mobile.items():
        for fmt in ("gif", "jpg"):
            target = evolution_dir / "qualification" / f"visual_export_qualification.{fmt}"
            command = ["java", "-Djava.awt.headless=true", "-cp", f"{classes}:{args.mobile_jar}:{args.stdlib_jar}", "RasterCompare",
                       "fixtures/visual-export/contract.json", str(png.relative_to(ROOT)), str(target), "-5", "-5"]
            if fmt == "gif":
                command.append("--gif-roundtrip")
            with open(logs / f"raster-{platform}-{fmt}.txt", "w") as log:
                result = run(command, log)
            checks = [line.split("\t") for line in result.stdout.splitlines() if line.count("\t") >= 2]
            results.append({
                "row": f"format/image-{fmt}", "case": f"{platform}-whole-{fmt}",
                "mobile": {"png": str(png.relative_to(ROOT)), "sha256": sha256(png)},
                "evolution": {"file": str(target.relative_to(out)), "sha256": sha256(target)},
                "checks": [{"check": c[0], "name": c[1], "result": c[2], "detail": c[3] if len(c) > 3 else ""} for c in checks],
                "failed": [f"{c[0]}:{c[1]}" for c in checks if c[2] == "FAIL"],
            })


# ---------- SVG ----------

def svg_number(value):
    return float(value)


def evolution_svg(path, origin):
    root = ET.parse(path).getroot()
    ns = "{http://www.w3.org/2000/svg}"
    ox, oy = origin
    wires, rects, texts, ports, bubbles = set(), [], [], [], []
    for element in root:
        tag = element.tag.replace(ns, "")
        if tag == "path" and element.get("stroke-width") == "3":
            wires |= path_edges(element.get("d"), ox, oy)
        elif tag == "rect" and element.get("stroke") == "#000000":
            rects.append((svg_number(element.get("x")) + ox, svg_number(element.get("y")) + oy,
                          svg_number(element.get("width")), svg_number(element.get("height"))))
        elif tag == "text":
            texts.append(element.text)
        elif tag == "circle" and element.get("r") == "2":
            ports.append((svg_number(element.get("cx")) + ox, svg_number(element.get("cy")) + oy))
        elif tag == "circle":
            bubbles.append((svg_number(element.get("cx")) + ox, svg_number(element.get("cy")) + oy, svg_number(element.get("r"))))
    return {"size": (root.get("width"), root.get("height")), "wires": wires, "rects": sorted(rects), "texts": sorted(texts),
            "ports": sorted(set(ports)), "bubbles": sorted(bubbles)}


def mobile_svg(path):
    root = ET.parse(path).getroot()
    ns = "{http://www.w3.org/2000/svg}"
    wires, rects, texts, ports, bubbles = set(), [], [], [], []
    for group in root.findall(f"{ns}g"):
        ident = group.get("data-object-id", "")
        if ident.startswith("wire:"):
            for path_element in group.iter(f"{ns}path"):
                wires |= path_edges(path_element.get("d"), 0, 0)
            continue
        for inner in group.findall(f"{ns}g"):
            match = re.match(r"translate\(([-\d.]+) ([-\d.]+)\) rotate\(0\) scale\(1 1\)", inner.get("transform", ""))
            tx, ty = float(match.group(1)), float(match.group(2))
            for rect in inner.findall(f"{ns}rect"):
                rects.append((float(rect.get("x")) + tx, float(rect.get("y")) + ty, float(rect.get("width")), float(rect.get("height"))))
            for text in inner.findall(f"{ns}text"):
                texts.append(text.text)
            for ellipse in inner.findall(f"{ns}ellipse"):
                bubbles.append((float(ellipse.get("cx")) + tx, float(ellipse.get("cy")) + ty, float(ellipse.get("rx"))))
        for ellipse in group.findall(f"{ns}ellipse"):
            ports.append((round(float(ellipse.get("cx")), 3), round(float(ellipse.get("cy")), 3)))
    return {"size": (root.get("width"), root.get("height")), "wires": wires, "rects": sorted(rects), "texts": sorted(texts),
            "ports": sorted(set(ports)), "bubbles": sorted(bubbles)}


def path_edges(d, ox, oy):
    """Axis-aligned wire path (M/L/H/V, absolute) as a set of 10-unit grid edges in world coordinates."""
    edges = set()
    tokens = re.findall(r"[MLHV]|-?[\d.]+", d.replace(",", " "))
    x = y = None
    i = 0
    while i < len(tokens):
        op = tokens[i]
        if op in "ML":
            nx, ny = float(tokens[i + 1]) + ox, float(tokens[i + 2]) + oy
            i += 3
        elif op == "H":
            nx, ny = float(tokens[i + 1]) + ox, y
            i += 2
        elif op == "V":
            nx, ny = x, float(tokens[i + 1]) + oy
            i += 2
        else:
            raise ValueError(f"Unexpected wire path {d}")
        if op != "M" and x is not None:
            if nx == x:
                for k in range(int(min(y, ny)), int(max(y, ny)), 10):
                    edges.add(((x, k), (x, k + 10)))
            elif ny == y:
                for k in range(int(min(x, nx)), int(max(x, nx)), 10):
                    edges.add(((k, y), (k + 10, y)))
            else:
                raise ValueError(f"Diagonal wire path {d}")
        x, y = nx, ny
    return edges


def close(a, b, tolerance):
    return len(a) == len(b) and all(all(abs(p - q) <= tolerance for p, q in zip(u, v)) for u, v in zip(a, b))


def svg(args, out, logs, results):
    built = ROOT / "composeApp/build/evolution-visual-export"
    mobile_file = built / "svg-gates-iec.svg"
    if not mobile_file.is_file():
        raise SystemExit(f"Missing {mobile_file}; run ./gradlew :composeApp:testAndroidHostTest --tests '*EvolutionVisualExportOracleTest'")
    mobile_dir = out / "mobile"
    mobile_dir.mkdir(parents=True, exist_ok=True)
    for name in ("svg-gates-iec.svg", "svg-gates-scene.txt", "qualification-scene.txt", "host-canvas.txt"):
        shutil.copy(built / name, mobile_dir / name)
    evolution_file = out / "evolution/svg-gates-iec/main.svg"
    bounds = re.search(r"bounds\t(-?\d+)\t(-?\d+)", (logs / "evolution-export.txt").read_text().split("svg-gates-iec")[1]).groups()
    e = evolution_svg(evolution_file, (int(bounds[0]), int(bounds[1])))
    m = mobile_svg(mobile_file)
    checks = [
        ("wire-edges", e["wires"] == m["wires"], f"{len(e['wires'])} Evolution / {len(m['wires'])} Mobile 10-unit edges"),
        ("gate-boxes", close(e["rects"], m["rects"], 0.5), f"Evolution {e['rects']} / Mobile {m['rects']}"),
        ("gate-symbols", e["texts"] == m["texts"], f"Evolution {e['texts']} / Mobile {m['texts']}"),
        ("port-markers", close(e["ports"], m["ports"], 0.5), f"{len(e['ports'])} Evolution / {len(m['ports'])} Mobile terminal markers"),
        ("negation-bubble", close([b[:2] for b in e["bubbles"]], [b[:2] for b in m["bubbles"]], 1.5),
         f"Evolution {e['bubbles']} / Mobile {m['bubbles']} (centre tolerance 1.5)"),
        ("size", True, f"Evolution {e['size']} (bounds+5) / Mobile {m['size']} (stroke-inclusive bounds + margin 5)"),
    ]
    scene = (mobile_dir / "svg-gates-scene.txt").read_text() + (mobile_dir / "qualification-scene.txt").read_text()
    checks.append(("coverage", False, "Mobile SVG needs a complete vector scene: " + scene.replace("\n", " ").strip()
                   + "; Evolution exports SVG for every circuit, including the ANSI fixture and qualification.circ "
                   + f"({sha256(out / 'evolution/svg-gates-ansi/main.svg')[:12]}…, {sha256(out / 'evolution/qualification/visual_export_qualification.svg')[:12]}…)"))
    results.append({"row": "format/image-svg", "case": "svg-gates-iec",
                    "mobile": {"file": "mobile/svg-gates-iec.svg", "sha256": sha256(mobile_file)},
                    "evolution": {"file": "evolution/svg-gates-iec/main.svg", "sha256": sha256(evolution_file)},
                    "checks": [{"check": c, "result": "PASS" if ok else "FAIL", "detail": d} for c, ok, d in checks],
                    "failed": [c for c, ok, _ in checks if not ok]})
    host = (mobile_dir / "host-canvas.txt").read_text().strip()
    results.append({"row": "format/image-tikz", "case": "tikz-host",
                    "evolution": {"file": "evolution/qualification/visual_export_qualification.tex",
                                  "sha256": sha256(out / "evolution/qualification/visual_export_qualification.tex")},
                    "checks": [{"check": "mobile-run", "result": "NOT_RUN", "detail": "Mobile TikZ draws through the Compose canvas: " + host}],
                    "failed": ["mobile-run"]})


# ---------- WaveDrom ----------

def wavedrom(args, classes, out, logs, results):
    half_cycles = "34"
    with open(logs / "wavedrom.txt", "w") as log:
        evolution = run(["java", "-Djava.awt.headless=true", "-Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory",
                         "-cp", f"{classes}:{args.evolution_jar}", "WaveDromDesktopOracle", "scripts/oracle/visual-export/wavedrom.circ", half_cycles], log)
        mobile = run(["java", "-cp", f"{classes}:{args.mobile_jar}:{args.stdlib_jar}", "WaveDromMobileOracle",
                      "scripts/oracle/visual-export/wavedrom.circ", half_cycles, "clk", "q", "carry", "open"], log)
    if evolution.returncode or mobile.returncode:
        raise SystemExit(f"WaveDrom oracle failed; see {logs / 'wavedrom.txt'}")
    (out / "evolution").mkdir(exist_ok=True)
    (out / "mobile").mkdir(exist_ok=True)
    (out / "evolution/wavedrom.json").write_text(evolution.stdout)
    (out / "mobile/wavedrom.json").write_text(mobile.stdout)
    e = {lane["name"]: lane for lane in json.loads(evolution.stdout)["signal"]}
    m = {lane["name"]: lane for lane in json.loads(mobile.stdout)["signal"]}
    checks = []
    for name, lane in m.items():
        width_suffix = next((key for key in e if re.fullmatch(re.escape(name) + r"(\[\d+\.\.0\])?", key)), None)
        if width_suffix is None:
            checks.append((f"{name}:present", False, "no Evolution lane"))
            continue
        other = e[width_suffix]
        checks.append((f"{name}:name", width_suffix == name, f"Evolution '{width_suffix}' / Mobile '{name}'"))
        evolution_wave = other["wave"]
        trailing = evolution_wave.endswith("x") and len(evolution_wave) == len(lane["wave"]) + 1
        if trailing:
            evolution_wave = evolution_wave[:-1]
        checks.append((f"{name}:trailing-sample", True, "Evolution samples t=end past the last segment and appends 'x'" if trailing
                       else "same length"))
        mobile_wave, mobile_data = lane["wave"], list(lane.get("data", []))
        raw_equal = mobile_wave == evolution_wave and mobile_data == other.get("data", [])
        checks.append((f"{name}:wave", raw_equal, f"Evolution '{evolution_wave}' {other.get('data', [])} / Mobile '{mobile_wave}' {mobile_data}"))
        if not raw_equal:
            # Evolution writes a bus at its minimum or maximum as 0 / 1 without data; normalise Mobile likewise.
            normalised, data, index = [], [], 0
            for character in mobile_wave:
                if character == "2":
                    value = mobile_data[index]
                    index += 1
                    if set(value) == {"0"}:
                        character = "0"
                    elif set(value) == {"1"}:
                        character = "1"
                    else:
                        data.append(value)
                normalised.append(character)
            mapped = "".join(normalised)
            checks.append((f"{name}:wave-min-max-normalised", mapped == evolution_wave and data == other.get("data", []),
                           f"Mobile with bus min/max as 0/1: '{mapped}' {data}"))
    extra = sorted(set(e) - {k for k in e if any(re.fullmatch(re.escape(n) + r"(\[\d+\.\.0\])?", k) for n in m)})
    checks.append(("selection", True, f"Evolution default selection also logs {extra}; Mobile logs the selected signals"))
    results.append({"row": "format/image-wavedrom", "case": f"counter-{half_cycles}-half-cycles",
                    "evolution": {"file": "evolution/wavedrom.json"}, "mobile": {"file": "mobile/wavedrom.json"},
                    "checks": [{"check": c, "result": "PASS" if ok else "FAIL", "detail": d} for c, ok, d in checks],
                    "failed": [c for c, ok, _ in checks if not ok]})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evolution-jar", required=True)
    parser.add_argument("--mobile-jar", default=str(ROOT / "core/build/libs/core-jvm.jar"))
    parser.add_argument("--stdlib-jar")
    parser.add_argument("--out", default=str(ROOT / "outputs/visual-export-oracle"))
    parser.add_argument("--skip-svg", action="store_true")
    args = parser.parse_args()
    args.stdlib_jar = args.stdlib_jar or str(stdlib_jar())
    out = Path(args.out).resolve()
    logs = out / "logs"
    for name in ("evolution", "mobile", "logs"):
        shutil.rmtree(out / name, ignore_errors=True)
    logs.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory() as classes:
        with open(logs / "compile.txt", "w") as log:
            for classpath, sources in [
                ("", ["scripts/oracle/project-options/MemoryPreferencesFactory.java"]),
                (args.evolution_jar, ["scripts/oracle/visual-export/EvolutionVisualExport.java", "scripts/oracle/visual-export/WaveDromDesktopOracle.java"]),
                (f"{args.mobile_jar}:{args.stdlib_jar}", ["scripts/oracle/visual-export/RasterCompare.java", "scripts/oracle/visual-export/WaveDromMobileOracle.java"]),
            ]:
                command = ["javac", "-d", classes] + (["-cp", classpath] if classpath else []) + sources
                if run(command, log).returncode:
                    raise SystemExit(f"javac failed; see {logs / 'compile.txt'}")
        raster(args, classes, out, logs, results)
        if not args.skip_svg:
            svg(args, out, logs, results)
        wavedrom(args, classes, out, logs, results)
    summary = {
        "evolution_jar_sha256": sha256(args.evolution_jar),
        "mobile_jar_sha256": sha256(args.mobile_jar),
        "fixtures": {name: sha256(ROOT / name) for name in ("fixtures/visual-export/qualification.circ", "fixtures/visual-export/contract.json",
                                                            "scripts/oracle/visual-export/svg-gates.circ", "scripts/oracle/visual-export/wavedrom.circ")},
        "results": results,
    }
    (out / "results.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False) + "\n")
    for result in results:
        print(f"{result['row']}\t{result['case']}\t{'PASS' if not result['failed'] else 'DIFF ' + ','.join(result['failed'])}")


if __name__ == "__main__":
    sys.exit(main())
