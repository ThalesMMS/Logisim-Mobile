#!/usr/bin/env python3
"""FPGA Commander "Generate HDL only" in Evolution and `--test-fpga ... HDLONLY` in Mobile (#343).

For each board family (Vivado, ISE, Quartus, open ECP5) one circuit is mapped on the board in
Evolution's saved-map format: two-bit AND and NOT, and OR, with physical buttons, switches, LEDs and
generic pins, one input bit tied to a constant and one output bit left open. Both programs generate
Verilog and constraints from the same file. The comparison does not expect equal files (the
generators name and structure the wrappers differently); it checks what reaches the board:

1. the constraints place the same physical pins, in the same direction, with the same I/O standard
   and pull mode;
2. Icarus simulates both top levels with every combination of the input pins and the output pins
   must carry the same values, which also checks active-low inversion and constant inputs.

Usage: fpga_commander.py CLASSPATH  (Evolution JAR, Mobile core JAR, Kotlin stdlib and the compiled
FpgaCommanderOracle/MemoryPreferences classes)."""
import itertools
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[3]
BOARDS = ["BASYS3", "BASYS2", "TERASIC_DE0", "GECKO5_EDUCATION"]


def java():
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, timeout=600, **kwargs)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed ({result.returncode}):\n{result.stdout[-4000:]}\n{result.stderr[-4000:]}")
    return result.stdout + result.stderr


def evolution_jar(classpath):
    return next(part for part in classpath.split(":") if "logisim-evolution" in Path(part).name)


def resources(board_xml):
    """Board resources as Evolution's map keys: (x, y, pin) per physical pin, in file order."""
    root = ElementTree.parse(board_xml).getroot()
    inputs, outputs = [], []
    for element in root.find("IOComponents"):
        x, y = element.get("Rect_x_y_w_h").split(",")[:2]
        if element.tag in ("Button", "DIPSwitch") and element.get("InputPinSet"):
            inputs += [(x, y, index) for index in range(len(element.get("InputPinSet").split(",")))]
        elif element.tag == "LED" and element.get("OutputPinSet"):
            outputs.append((x, y, 0))
        elif element.tag == "Pin" and element.get("BiDirPinSet"):
            outputs.append((x, y, 0))
    return inputs, outputs


def circuit(board, board_xml):
    inputs, outputs = resources(board_xml)
    pins = iter(inputs)
    leds = iter(outputs)
    key = lambda resource: "_".join(map(str, resource))
    maps = {
        "a": f"{key(next(pins))},{key(next(pins))}",
        "b": f"{key(next(pins))},1",  # bit 1 tied high
        "c": f"{key(next(pins))},{key(next(pins))}", "d": key(next(pins)), "e": key(next(pins)),
        "y": f"{key(next(leds))},{key(next(leds))}",  # y[1] = a[1] & 1
        "nc": f"{key(next(leds))},open",  # bit 1 left open
        "o": key(next(leds)),
    }
    boardmap = "\n      ".join(f'<mc key="/{name}" pmap="{value}"/>' for name, value in maps.items())

    def pin(x, y, label, width, output=False):
        kind = '<a name="type" val="output"/><a name="facing" val="west"/>' if output else ""
        return f'<comp lib="0" loc="({x},{y})" name="Pin"><a name="appearance" val="classic"/><a name="width" val="{width}"/>{kind}<a name="label" val="{label}"/></comp>'
    return f'''<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<project source="3.8.0" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    {pin(100, 100, "a", 2)}
    {pin(100, 140, "b", 2)}
    <comp lib="1" loc="(200,120)" name="AND Gate"><a name="inputs" val="2"/><a name="width" val="2"/></comp>
    {pin(250, 120, "y", 2, True)}
    {pin(100, 200, "c", 2)}
    <comp lib="1" loc="(160,200)" name="NOT Gate"><a name="width" val="2"/></comp>
    {pin(220, 200, "nc", 2, True)}
    {pin(100, 260, "d", 1)}
    {pin(100, 300, "e", 1)}
    <comp lib="1" loc="(200,280)" name="OR Gate"><a name="inputs" val="2"/></comp>
    {pin(250, 280, "o", 1, True)}
    <wire from="(100,100)" to="(150,100)"/>
    <wire from="(100,140)" to="(150,140)"/>
    <wire from="(200,120)" to="(250,120)"/>
    <wire from="(100,200)" to="(130,200)"/>
    <wire from="(160,200)" to="(220,200)"/>
    <wire from="(100,260)" to="(150,260)"/>
    <wire from="(100,300)" to="(150,300)"/>
    <wire from="(200,280)" to="(250,280)"/>
    <boardmap boardname="{board}">
      {boardmap}
    </boardmap>
  </circuit>
</project>
'''


# --- Constraints ------------------------------------------------------------------------------

def constraints(directory):
    """port -> {pin, iostandard, pull} from every constraint format both programs write."""
    ports = {}

    def entry(port):
        return ports.setdefault(port.strip().strip("{}\"").replace("<", "[").replace(">", "]"), {})

    for path in sorted(p for p in Path(directory).rglob("*") if p.is_file()):
        suffix = path.suffix.lower()
        text = path.read_text(errors="replace")
        if suffix == ".xdc":
            for prop, value, port in re.findall(r"set_property\s+(?:-dict\s+)?\{?\s*(PACKAGE_PIN|IOSTANDARD|PULLUP|PULLDOWN|PULLTYPE)\s+(\S+?)\}?\s+\[get_ports\s+\{?([^}\]]+)\}?\]", text):
                key = {"PACKAGE_PIN": "pin", "IOSTANDARD": "iostandard"}.get(prop, "pull")
                entry(port)[key] = value if key != "pull" else (prop if prop != "PULLTYPE" else value)
        elif suffix == ".ucf":
            for port, attributes in re.findall(r'NET\s+"?([^"\s]+)"?\s+(.*?);', text):
                for name, value in re.findall(r"(LOC|IOSTANDARD)\s*=\s*\"?([^\"|\s;]+)", attributes):
                    entry(port)["pin" if name == "LOC" else "iostandard"] = value
                for pull in re.findall(r"\b(PULLUP|PULLDOWN|KEEPER)\b", attributes):
                    entry(port)["pull"] = pull
        elif suffix in (".tcl", ".qsf"):
            for pin, port in re.findall(r"set_location_assignment\s+(\S+)\s+-to\s+(\S+)", text):
                entry(port)["pin"] = pin
            for value, port in re.findall(r'set_instance_assignment\s+-name\s+IO_STANDARD\s+"?([^"]+?)"?\s+-to\s+(\S+)', text):
                entry(port)["iostandard"] = value
            for port in re.findall(r"set_instance_assignment\s+-name\s+WEAK_PULL_UP_RESISTOR\s+ON\s+-to\s+(\S+)", text):
                entry(port)["pull"] = "PULLUP"
        elif suffix == ".lpf":
            for port, site in re.findall(r'LOCATE\s+COMP\s+"([^"]+)"\s+SITE\s+"([^"]+)"', text):
                entry(port)["pin"] = site
            for port, attributes in re.findall(r'IOBUF\s+PORT\s+"([^"]+)"\s+(.*?);', text):
                for name, value in re.findall(r"(IO_TYPE|PULLMODE)\s*=\s*(\S+)", attributes):
                    entry(port)["iostandard" if name == "IO_TYPE" else "pull"] = value
    return {port: values for port, values in ports.items() if "pin" in values}


# --- Simulation -------------------------------------------------------------------------------

def verilog_modules(directory):
    files = sorted(p for p in Path(directory).rglob("*.v"))
    modules = {}
    for path in files:
        text = re.sub(r"//.*", "", path.read_text(errors="replace"))
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        for match in re.finditer(r"\bmodule\s+(\w+)\s*(?:#\s*\(.*?\)\s*)?\((.*?)\)\s*;(.*?)\bendmodule", text, re.S):
            modules[match.group(1)] = (path, match.group(2), match.group(3))
    return files, modules


def ports_of(header, body):
    """(name, direction, width) for ANSI and non-ANSI port lists."""
    declarations = {}
    for direction, rng, names in re.findall(r"\b(input|output|inout)\s+(?:wire\s+|reg\s+|logic\s+)?(\[\s*\d+\s*:\s*\d+\s*\])?\s*([\w\s,]+?)(?=[;)]|,\s*(?:input|output|inout)\b|$)", header + ";" + body):
        width = 1
        if rng:
            high, low = map(int, re.findall(r"\d+", rng))
            width = abs(high - low) + 1
        for name in re.split(r"[\s,]+", names.strip()):
            if name and name not in ("wire", "reg", "logic"):
                declarations[name] = (direction, width)
    order = [n for n in re.split(r"[\s,]+", re.sub(r"\b(input|output|inout|wire|reg|logic)\b|\[[^\]]*\]", " ", header)) if n]
    return [(name, *declarations[name]) for name in order if name in declarations]


def top_module(directory, placed_ports):
    files, modules = verilog_modules(directory)
    bases = {re.sub(r"\[.*", "", port) for port in placed_ports}
    for name, (path, header, body) in modules.items():
        ports = ports_of(header, body)
        if ports and bases <= {port[0] for port in ports}:
            return files, name, ports
    raise RuntimeError(f"No top module in {directory} declares {sorted(bases)}")


def simulate(directory, placed, vectors, work):
    """Drive input pins (by physical pin) and read output pins for every vector."""
    files, top, ports = top_module(directory, placed)
    by_pin = {values["pin"]: port for port, values in placed.items()}
    lines = ["`timescale 1ns/1ps", "module oracle_bench;"]
    connections = []
    for name, direction, width in ports:
        kind = "reg" if direction == "input" else "wire"
        lines.append(f"  {kind} [{width - 1}:0] p_{name};")
        connections.append(f".{name}(p_{name})")
    lines.append(f"  {top} dut({', '.join(connections)});")

    def signal(port):
        match = re.fullmatch(r"(\w+)(?:\[(\d+)\])?", port)
        return f"p_{match.group(1)}[{match.group(2) or 0}]"
    directions = {name: direction for name, direction, _ in ports}
    inputs = sorted(pin for pin, port in by_pin.items() if directions.get(re.sub(r"\[.*", "", port)) == "input")
    outputs = sorted(pin for pin, port in by_pin.items() if directions.get(re.sub(r"\[.*", "", port)) != "input")
    lines.append("  initial begin")
    for name, direction, width in ports:
        if direction == "input":
            lines.append(f"    p_{name} = 0;")
    for vector in vectors:
        for pin in inputs:
            lines.append(f"    {signal(by_pin[pin])} = {vector[pin]};")
        lines.append("    #10;")
        lines.append('    $display("V ' + " ".join(f"{pin}=%b" for pin in outputs) + '", '
                     + ", ".join(signal(by_pin[pin]) for pin in outputs) + ");")
    lines += ["    $finish;", "  end", "endmodule", ""]
    bench = Path(work) / "oracle_bench.v"
    bench.write_text("\n".join(lines))
    binary = Path(work) / "bench.vvp"
    run(["iverilog", "-g2012", "-o", str(binary), "-s", "oracle_bench", str(bench), *map(str, files)])
    output = run(["vvp", "-n", str(binary)])
    return inputs, outputs, [line[2:] for line in output.splitlines() if line.startswith("V ")]


def main():
    classpath = sys.argv[1]
    differences = 0
    with tempfile.TemporaryDirectory(prefix="fpga-commander-") as temporary:
        temporary = Path(temporary)
        with zipfile.ZipFile(evolution_jar(classpath)) as jar:
            for board in BOARDS:
                (temporary / f"{board}.xml").write_bytes(jar.read(f"resources/logisim/boards/{board}.xml"))
        for board in BOARDS:
            work = temporary / board
            work.mkdir()
            board_xml = temporary / f"{board}.xml"
            project = work / f"commander_{board.lower()}.circ"
            project.write_text(circuit(board, board_xml))
            evolution = work / "evolution"
            run([java(), "-Djava.awt.headless=true", "-Djava.util.prefs.PreferencesFactory=MemoryPreferences$Factory",
                       "-cp", classpath, "FpgaCommanderOracle", str(project), "main", str(board_xml), str(evolution)])
            mobile = work / "mobile"
            mobile.mkdir()
            archive = work / "mobile.zip"
            run([java(), "-cp", classpath, "dev.logisim.mobile.core.HeadlessCircuitCli", "--test-fpga", str(project), "main",
                 str(board_xml), "HDLONLY", "--output", str(archive)])
            with zipfile.ZipFile(archive) as generated:
                generated.extractall(mobile)
            if os.environ.get("FPGA_COMMANDER_KEEP"):
                shutil.copytree(work, Path(os.environ["FPGA_COMMANDER_KEEP"]) / board, dirs_exist_ok=True)
            evolution_pins = constraints(evolution)
            mobile_pins = constraints(mobile)
            print(f"== {board}: Evolution {sorted(str(p.relative_to(evolution)) for p in evolution.rglob('*') if p.is_file())}")
            print(f"   Mobile {sorted(str(p.relative_to(mobile)) for p in mobile.rglob('*') if p.is_file())}")
            placed_e = {v["pin"]: {k: v.get(k) for k in ("iostandard", "pull")} for v in evolution_pins.values()}
            placed_m = {v["pin"]: {k: v.get(k) for k in ("iostandard", "pull")} for v in mobile_pins.values()}
            if placed_e == placed_m:
                print(f"SAME {board} constraints: {len(placed_e)} pins {dict(sorted(placed_e.items()))}")
            else:
                differences += 1
                print(f"DIFF {board} constraints\n   evolution {dict(sorted(placed_e.items()))}\n   mobile    {dict(sorted(placed_m.items()))}")
            # Every combination of the physical input pins, by pin name.
            sim_dir = work / "sim-e"
            sim_dir.mkdir()
            files, top, ports = top_module(evolution, evolution_pins)
            directions = {name: direction for name, direction, _ in ports}
            input_pins = sorted(v["pin"] for p, v in evolution_pins.items() if directions.get(re.sub(r"\[.*", "", p)) == "input")
            vectors = [dict(zip(input_pins, bits)) for bits in itertools.product((0, 1), repeat=len(input_pins))]
            ein, eout, evalues = simulate(evolution, evolution_pins, vectors, sim_dir)
            sim_dir = work / "sim-m"
            sim_dir.mkdir()
            min_, mout, mvalues = simulate(mobile, mobile_pins, vectors, sim_dir)
            if (ein, eout) == (min_, mout) and evalues == mvalues and len(evalues) == len(vectors):
                print(f"SAME {board} behaviour: {len(vectors)} input vectors over {ein}, outputs {eout}, "
                      f"{len(set(evalues))} distinct output patterns (Evolution top {top})")
            else:
                differences += 1
                print(f"DIFF {board} behaviour: inputs {ein}/{min_} outputs {eout}/{mout}")
                for vector, e, m in list(zip(vectors, evalues, mvalues))[:8]:
                    if e != m:
                        print(f"   {vector}: evolution {e} mobile {m}")
    print(f"SUMMARY differences={differences}")
    return 1 if differences else 0


if __name__ == "__main__":
    sys.exit(main())
