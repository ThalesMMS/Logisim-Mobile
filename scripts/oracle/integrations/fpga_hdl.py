#!/usr/bin/env python3
"""Compare FPGA HDL generation, pin mapping and constraints with Logisim Evolution.

Both programs generate an HDL-only FPGA project for the same `.circ` and the same
BASYS3 board map: Evolution with `--test-fpga ... HDLONLY`, the Mobile JVM launcher
with `--test-fpga ... HDLONLY --hdl-language`. The checker then:

1. reads the package-pin constraints of both projects and requires the same pins,
   directions and the same board clock pin;
2. simulates both generated top levels (nvc for VHDL, Icarus for Verilog) with one
   testbench that drives and samples *package pins*, for every input combination;
3. requires both traces to equal Evolution's own `-tty table` of the circuit.

Evolution runs with an in-memory java.util.prefs backend and a temporary user.home,
so the user's preferences and workspace are not touched.
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def run(command, cwd=None, timeout=300, env=None, check=True):
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=timeout, env=env)
    if check and result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(map(str, command))}\n{result.stdout}\n{result.stderr}")
    return result


def kotlin_stdlib() -> Path:
    if os.environ.get("KOTLIN_STDLIB_JAR"):
        return Path(os.environ["KOTLIN_STDLIB_JAR"])
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next(path for path in (cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar")
                if not path.name.endswith(("-sources.jar", "-javadoc.jar")))


class Evolution:
    """Runs the Evolution JAR headless, with isolated preferences and home."""

    def __init__(self, jar: Path, work: Path, java: str):
        self.jar, self.work, self.java = jar, work, java
        self.prefs = work / "prefs"
        self.prefs.mkdir(parents=True, exist_ok=True)
        run([java.replace("/java", "/javac") if java.endswith("/java") else "javac", "-d", str(self.prefs),
             str(HERE / "MemoryPreferencesFactory.java")])

    def __call__(self, arguments, home: Path, seed: str = "", cwd=None, timeout=300):
        home.mkdir(parents=True, exist_ok=True)
        command = [self.java, "-Djava.awt.headless=true", f"-Duser.home={home}",
                   "-Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory", f"-Dmemprefs.seed={seed}",
                   "-cp", f"{self.prefs}:{self.jar}", "com.cburch.logisim.Main", *arguments]
        return run(command, cwd=cwd, timeout=timeout, check=False)


def mobile_cli(java, core_jar, stdlib, arguments, cwd, profile):
    env = dict(os.environ, LOGISIM_MOBILE_CLI_PROFILE=str(profile))
    return run([java, "-cp", f"{core_jar}:{stdlib}", "dev.logisim.mobile.core.HeadlessCircuitCli", *arguments],
               cwd=cwd, env=env, check=False, timeout=300)


def constraints(xdc: str) -> dict[str, str]:
    """package pin -> port."""
    return {pin: port for pin, port in re.findall(r"PACKAGE_PIN\s+(\S+)\s+\[get_ports\s+\{([^}]+)\}\]", xdc)}


def clock_ports(xdc: str) -> set[str]:
    return set(re.findall(r"create_clock[^\n]*\[get_ports\s+\{([^}]+)\}\]", xdc))


def vhdl_ports(text: str, entity: str) -> dict[str, str]:
    body = re.search(rf"ENTITY\s+{entity}\s+IS\s+PORT\s*\((.*?)\)\s*;\s*END", text, re.S | re.I).group(1)
    return {name: direction.upper() for name, direction in re.findall(r"(\w+)\s*:\s*(IN|OUT|INOUT)\b", body, re.I)}


def verilog_ports(text: str, module: str) -> dict[str, str]:
    body = text[re.search(rf"module\s+{module}\b", text).start():]
    body = body[:body.index("endmodule")]
    directions = {"input": "IN", "output": "OUT", "inout": "INOUT"}
    ports = {}
    # ANSI header items and non-ANSI body declarations: "input [wire|reg] [range] a, b"
    header = body[body.index("(") + 1:body.index(");")]
    declarations = [item.strip() for item in header.split(",")]
    declarations += re.findall(r"^[ \t]*((?:input|output|inout)\b[^;]*);", body[body.index(");") + 2:], re.M)
    current = None
    for declaration in declarations:
        match = re.match(r"(input|output|inout)\b\s*(?:wire|reg)?\s*(?:\[[^\]]*\])?\s*(.*)", declaration, re.S)
        if match:
            current = directions[match.group(1)]
            names = match.group(2)
        elif current and re.fullmatch(r"\w+", declaration):
            names = declaration
        else:
            continue
        for name in re.split(r"[\s,]+", names.strip()):
            if re.fullmatch(r"\w+", name):
                ports[name] = current
    return ports


def vhdl_bench(entity, ports, pin_of, inputs, outputs, clock):
    signals = "\n".join(f"  SIGNAL s_{port} : std_logic := '0';" for port in ports)
    mapping = ",\n".join(f"    {port} => s_{port}" for port in ports)
    lines = []
    for combo in itertools.product("01", repeat=len(inputs)):
        for pin, value in zip(inputs, combo):
            lines.append(f"    s_{pin_of[pin]} <= '{value}';")
        lines.append("    FOR i IN 0 TO 7 LOOP WAIT FOR 5 ns; " + (f"s_{pin_of[clock]} <= NOT s_{pin_of[clock]}; " if clock else "") + "END LOOP;")
        report = " & \",\" & ".join(f"std_logic'image(s_{pin_of[pin]})" for pin in outputs)
        lines.append(f"    REPORT \"ROW " + "".join(combo) + f" \" & {report} SEVERITY NOTE;")
    return f"""LIBRARY ieee;
USE ieee.std_logic_1164.ALL;
ENTITY tb IS END ENTITY;
ARCHITECTURE sim OF tb IS
{signals}
BEGIN
  dut: ENTITY work.{entity} PORT MAP (
{mapping}
  );
  PROCESS BEGIN
{chr(10).join(lines)}
    WAIT;
  END PROCESS;
END ARCHITECTURE;
"""


def verilog_bench(module, ports, pin_of, inputs, outputs, clock):
    declarations = "\n".join((f"  reg s_{port} = 0;" if direction == "IN" else f"  wire s_{port};") for port, direction in ports.items())
    mapping = ", ".join(f".{port}(s_{port})" for port in ports)
    lines = []
    for combo in itertools.product("01", repeat=len(inputs)):
        for pin, value in zip(inputs, combo):
            lines.append(f"    s_{pin_of[pin]} = {value};")
        lines.append("    repeat (8) begin #5; " + (f"s_{pin_of[clock]} = ~s_{pin_of[clock]}; " if clock else "") + "end")
        fmt = ",".join("'%b'" for _ in outputs)
        lines.append(f'    $display("ROW {"".join(combo)} {fmt}", ' + ", ".join(f"s_{pin_of[pin]}" for pin in outputs) + ");")
    return f"""module tb;
{declarations}
  {module} dut({mapping});
  initial begin
{chr(10).join(lines)}
    $finish;
  end
endmodule
"""


def simulate(language, directory: Path, files: list[Path], top, xdc_text, board_inputs, board_outputs):
    pin_of = constraints(xdc_text)
    clocks = clock_ports(xdc_text)
    clock_pin = next((pin for pin, port in pin_of.items() if port in clocks), None)
    texts = "\n".join(path.read_text() for path in files)
    ports = vhdl_ports(texts, top) if language == "vhdl" else verilog_ports(texts, top)
    missing = [port for port in pin_of.values() if port not in ports]
    if missing:
        raise RuntimeError(f"constraint ports missing from {top}: {missing}")
    if language == "vhdl":
        bench = directory / "tb.vhd"
        bench.write_text(vhdl_bench(top, ports, pin_of, board_inputs, board_outputs, clock_pin))
        work = directory / "nvcwork"
        shutil.rmtree(work, ignore_errors=True)
        run(["nvc", "--std=2008", f"--work=work:{work}", "-a", *map(str, files), str(bench)], cwd=directory)
        result = run(["nvc", "--std=2008", f"--work=work:{work}", "-e", "tb", "-r"], cwd=directory)
        output = result.stdout + result.stderr
        rows = re.findall(r"ROW (\d+) ([^\s]+)", output)
        rows = [(combo, values.replace("'", "")) for combo, values in rows]
    else:
        bench = directory / "tb.v"
        bench.write_text(verilog_bench(top, ports, pin_of, board_inputs, board_outputs, clock_pin))
        run(["iverilog", "-g2012", "-s", "tb", "-o", str(directory / "sim.vvp"), *map(str, files), str(bench)], cwd=directory)
        output = run(["vvp", str(directory / "sim.vvp")], cwd=directory).stdout
        rows = [(combo, values.replace("'", "")) for combo, values in re.findall(r"ROW (\d+) (\S+)", output)]
    return clock_pin, rows


def evolution_files(project_dir: Path, language: str) -> tuple[list[Path], str]:
    hdl = project_dir / language
    order = sorted(hdl.rglob("*.vhd" if language == "vhdl" else "*.v"),
                   key=lambda path: (path.parent.name in ("circuit", "toplevel"), path.parent.name == "toplevel",
                                     "behavior" in path.name, path.name))
    xdc = next(project_dir.rglob("*.xdc")).read_text()
    return order, xdc


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("evolution_jar", type=Path)
    parser.add_argument("--core-jar", type=Path, default=ROOT / "core/build/libs/core-jvm.jar")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    java = os.path.join(os.environ["JAVA_HOME"], "bin/java") if os.environ.get("JAVA_HOME") else "java"
    stdlib = kotlin_stdlib()
    circ = HERE / "fpga-comb.circ"
    board = ROOT / "core/src/jvmTest/resources/fpga/BASYS3.xml"
    # Board pins of the BASYS3 map in fpga-comb.circ: switches a..d, LEDs y, z, w.
    board_inputs = ["V17", "V16", "W16", "W17"]
    board_outputs = ["U16", "E19", "U19"]
    results = {"fixture": str(circ.relative_to(ROOT)), "board": "BASYS3", "languages": {}}
    with tempfile.TemporaryDirectory() as temporary:
        temporary = Path(temporary)
        evolution = Evolution(args.evolution_jar, temporary, java)
        work = temporary / "project"
        work.mkdir()
        shutil.copy(circ, work / "fpga-comb.circ")
        table = evolution(["-tty", "table,csv", "fpga-comb.circ"], temporary / "home-tty", cwd=work)
        reference = [line.split(",") for line in table.stdout.strip().splitlines()[1:]]
        expected = [("".join(row[:4]), ",".join(row[4:])) for row in reference]
        if len(expected) != 16:
            raise RuntimeError(f"unexpected Evolution -tty table:\n{table.stdout}\n{table.stderr}")
        for language, pref in (("vhdl", "VHDL"), ("verilog", "Verilog")):
            home = temporary / f"home-{language}"
            evo = evolution(["--test-fpga", "fpga-comb.circ", "main", "BASYS3", "HDLONLY"], home,
                            seed=f"com/cburch/logisim:hdlType={pref}", cwd=work)
            project_dir = home / "logisim_evolution_workspace/fpga-comb/main"
            if evo.returncode or not project_dir.exists():
                raise RuntimeError(f"Evolution HDLONLY failed:\n{evo.stdout[-3000:]}\n{evo.stderr[-3000:]}")
            evo_files, evo_xdc = evolution_files(project_dir, language)
            mobile_zip = temporary / f"mobile-{language}.zip"
            mob = mobile_cli(java, args.core_jar, stdlib, ["--test-fpga", "fpga-comb.circ", "main", str(board), "HDLONLY",
                             "--output", str(mobile_zip), "--hdl-language", language], work, temporary / "cli.properties")
            if mob.returncode:
                raise RuntimeError(f"Mobile HDLONLY failed: {mob.stderr}")
            mobile_dir = temporary / f"mobile-{language}"
            with zipfile.ZipFile(mobile_zip) as archive:
                archive.extractall(mobile_dir)
                names = archive.namelist()
            suffix = ".vhd" if language == "vhdl" else ".v"
            mobile_files = sorted((mobile_dir / name for name in names if name.endswith(suffix)),
                                  key=lambda path: path.name == f"logisim_fpga_top{suffix}")
            mobile_xdc = (mobile_dir / "pins.xdc").read_text()
            evo_pins, mob_pins = constraints(evo_xdc), constraints(mobile_xdc)
            if set(evo_pins) != set(mob_pins):
                raise RuntimeError(f"{language}: package pins differ: Evolution {sorted(evo_pins)} Mobile {sorted(mob_pins)}")
            evo_clock, evo_rows = simulate(language, project_dir, evo_files, "logisimTopLevelShell", evo_xdc, board_inputs, board_outputs)
            mob_clock, mob_rows = simulate(language, mobile_dir, mobile_files, "logisim_fpga_top", mobile_xdc, board_inputs, board_outputs)
            for name, rows in (("Evolution", evo_rows), ("Mobile", mob_rows)):
                if rows != expected:
                    raise RuntimeError(f"{language}: {name} generated HDL differs from Evolution -tty table:\n{rows}\n{expected}")
            if evo_clock != mob_clock:
                raise RuntimeError(f"{language}: board clock pin differs: {evo_clock} vs {mob_clock}")
            # Build scripts: same FPGA part, and the Mobile script reads every generated HDL file and the constraints.
            evo_part = re.search(r"set_property part (\S+)", (project_dir / "scripts/vivadoCreateProject.tcl").read_text()).group(1)
            build = (mobile_dir / "build.tcl").read_text()
            mob_part = re.search(r"-part (\S+)", build).group(1)
            if evo_part != mob_part:
                raise RuntimeError(f"{language}: FPGA part differs: Evolution {evo_part}, Mobile {mob_part}")
            unread = [path.name for path in mobile_files if path.name not in build] + ([] if "pins.xdc" in build else ["pins.xdc"])
            if unread:
                raise RuntimeError(f"{language}: Mobile build.tcl does not read {unread}")
            results["languages"][language] = {
                "part": evo_part,
                "package_pins": sorted(evo_pins), "clock_pin": evo_clock, "rows": len(expected),
                "evolution_files": [str(path.relative_to(project_dir)) for path in evo_files],
                "mobile_files": names,
            }
            out_dir = args.out / f"fpga-hdl-{language}"
            shutil.rmtree(out_dir, ignore_errors=True)
            shutil.copytree(project_dir, out_dir / "evolution", ignore=shutil.ignore_patterns("nvcwork", "*.vvp"))
            shutil.copytree(mobile_dir, out_dir / "mobile", ignore=shutil.ignore_patterns("nvcwork", "*.vvp"))
            print(f"PASS {language}: {len(expected)} input combinations, pins {sorted(evo_pins)}, clock {evo_clock}, part {evo_part}; "
                  "Evolution and Mobile top levels both match Evolution -tty table")
    results["status"] = "PASS"
    (args.out / "fpga-hdl.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
