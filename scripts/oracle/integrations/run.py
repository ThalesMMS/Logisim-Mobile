#!/usr/bin/env python3
"""Run the format and integration comparisons of campaign #331 and write a receipt.

    python3 scripts/oracle/integrations/run.py EVOLUTION_JAR --out docs/parity-evidence/<dir>

Needs JDK 21 (JAVA_HOME or scripts/java-home.sh), a built core/build/libs/core-jvm.jar,
yosys, iverilog, vvp, nvc and tclsh. Evolution runs headless with an in-memory
java.util.prefs backend and a temporary user.home (MemoryPreferencesFactory.java); the SoC
program oracle needs AWT (it creates a JDialog) and runs with apple.awt.UIElement instead.
Each suite writes logs/<suite>.txt; results.json records the exit status of every suite.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
EVOLUTION_SOURCE = os.environ.get("LOGISIM_DESKTOP_SOURCE", str(Path.home() / "logisim-evolution"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def kotlin_stdlib() -> Path:
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next(path for path in (cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar")
                if not path.name.endswith(("-sources.jar", "-javadoc.jar")))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("evolution_jar", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--only", default="", help="comma-separated suite names")
    args = parser.parse_args()
    java_home = os.environ.get("JAVA_HOME") or subprocess.run(
        ["sh", "-c", ". scripts/java-home.sh && printf %s \"$JAVA_HOME\""], cwd=ROOT, text=True, capture_output=True).stdout
    env = dict(os.environ, JAVA_HOME=java_home, PATH=f"{java_home}/bin:{os.environ['PATH']}",
               LOGISIM_DESKTOP_SOURCE=EVOLUTION_SOURCE)
    java, javac = f"{java_home}/bin/java", f"{java_home}/bin/javac"
    jar = args.evolution_jar.resolve()
    core = ROOT / "core/build/libs/core-jvm.jar"
    stdlib = kotlin_stdlib()
    out = args.out.resolve()
    logs = out / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="oracle-331-"))
    prefs = work / "prefs"
    subprocess.run([javac, "-d", str(prefs), str(HERE / "MemoryPreferencesFactory.java")], check=True)
    isolated = ["-Djava.awt.headless=true", f"-Duser.home={work / 'home'}", "-Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory"]
    both = f"{prefs}:{jar}:{core}:{stdlib}"
    mobile_sha = sha256(core)
    hdl_out = ROOT / "outputs/hdl-import-desktop-oracle"
    fixtures = ROOT / "fixtures/hdl"

    def verilog_import():
        """Evolution's own VerilogImporter vs Mobile's import, both executed in Evolution's engine."""
        lines, failed = [], False
        for name, top, files, defines in (("hierarchy", "hierarchy", ["hierarchy.v"], []),
                                          ("gates", "gates", ["gates.v"], []),
                                          ("macros", "macros", ["macros.v"], ["-DMASK=2"])):
            directory = work / f"verilog-{name}"
            directory.mkdir(exist_ok=True)
            for source in files + (["constants.vh"] if name == "macros" else []):
                shutil.copy(fixtures / "import" / source, directory / source)
            result = subprocess.run([java, *isolated, "--class-path", f"{prefs}:{jar}", str(HERE / "VerilogEvolutionImport.java"),
                                     str(directory), top, str(hdl_out / name / "vectors.txt"), *defines, *files],
                                    text=True, capture_output=True, timeout=300, env=env)
            evolution = [line for line in result.stdout.splitlines() if line.startswith("V\t")]
            mobile = [line for line in (hdl_out / f"{name}-desktop.stdout").read_text().splitlines() if line.startswith("V\t")]
            same = result.returncode == 0 and evolution == mobile and evolution and all("\tPASS" in line for line in evolution)
            failed |= not same
            lines.append(f"{'SAME' if same else 'DIFF'} {name}: {len(evolution)} vectors from Evolution's VerilogImporter, "
                         f"{len(mobile)} from Mobile's import, both run in Evolution's engine")
            if not same:
                lines.append(result.stderr[-2000:])
        lines.append(("FAIL" if failed else "PASS") + ": Evolution and Mobile Verilog imports agree on every vector")
        return (1 if failed else 0), "\n".join(lines) + "\n"

    suites = {
        "fpga-boards": [str(ROOT / "scripts/oracle/fpga-boards/run.sh"), str(jar)],
        "vectors": ["sh", "-c", f"LOGISIM_SKIP_BUILD=1 scripts/oracle/vectors/run.sh {jar}"],
        "hdl-import": ["python3", str(ROOT / "scripts/oracle/hdl-import/run.py"), "--mobile-jar", str(core), "--mobile-sha256", mobile_sha,
                       "--desktop-jar", str(jar), "--desktop-sha256", sha256(jar)],
        "verilog-evolution-import": verilog_import,
        "hdl-declarations": [java, *isolated, "--class-path", both, str(HERE / "HdlDeclarationOracle.java"),
                             *map(str, sorted((HERE / "hdl").glob("*.vhd"))), *map(str, sorted(fixtures.glob("*/*.vhd"))),
                             *map(str, sorted(fixtures.glob("*/*.blif")))],
        "blif-cosim": ["python3", str(HERE / "blif_cosim.py"), "--java", java, "--classpath", f"{jar}:{core}:{stdlib}",
                       *map(str, sorted(fixtures.glob("*/*.blif")))],
        "fpga-hdl": ["python3", str(HERE / "fpga_hdl.py"), str(jar), "--out", str(out)],
        "soc-drivers": [java, *isolated, "--class-path", both, str(HERE / "soc/SocDriverOracle.java"), str(HERE / "soc/drivers.circ")],
        "soc-program-desktop": ["sh", "-c", " && ".join(
            f"{java} -Dapple.awt.UIElement=true -Duser.home={work / 'home'} -Djava.util.prefs.PreferencesFactory=MemoryPreferencesFactory "
            f"--class-path {prefs}:{jar} scripts/SocProgramDesktopOracle.java fixtures/soc/program-{a}.circ 2>/dev/null | grep -v INFO "
            f"| diff - fixtures/soc/program-{a}-desktop.csv && echo 'SAME {a}: Evolution rerun equals fixtures/soc/program-{a}-desktop.csv'"
            for a in ("rv32", "nios2"))],
        "tcl-desktop-protocol": ["python3", "-m", "unittest", "-v", "companion.tests.test_tcl_desktop_protocol"],
        "evolution-images": [java, *isolated, "--class-path", f"{prefs}:{jar}", str(HERE / "EvolutionImageExport.java"),
                             str(ROOT / "fixtures/visual-export/qualification.circ"), "visual_export_qualification", "0.25",
                             str(out / "evolution-images")],
    }
    only = set(filter(None, args.only.split(",")))
    results = {}
    for name, command in suites.items():
        if only and name not in only:
            continue
        started = datetime.datetime.now(datetime.timezone.utc)
        try:
            if callable(command):
                code, text = command()
            else:
                result = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True, timeout=1200)
                code, text = result.returncode, result.stdout + result.stderr
        except subprocess.TimeoutExpired:
            code, text = "timeout", "timeout after 1200 s\n"
        text = "\n".join(line for line in text.splitlines() if "] INFO " not in line) + "\n"
        (logs / f"{name}.txt").write_text(text)
        summary = next((line for line in reversed(text.splitlines()) if re.match(r"(PASS|FAIL|RESULT|OK|SAME|FAILED)", line)), "")
        results[name] = {"exit": code, "summary": summary, "log": f"logs/{name}.txt",
                         "seconds": round((datetime.datetime.now(datetime.timezone.utc) - started).total_seconds(), 1)}
        print(f"{name}: exit {code} {summary}", flush=True)
    receipt = {
        "campaign": "https://github.com/ThalesMMS/logisim-mobile-dev/issues/331",
        "mobile_revision": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True).stdout.strip(),
        "mobile_dirty": bool(subprocess.run(["git", "status", "--porcelain", "--", "core", "composeApp", "companion"], cwd=ROOT,
                                            text=True, capture_output=True).stdout.strip()),
        "core_jar_sha256": mobile_sha,
        "evolution_revision": subprocess.run(["git", "-C", EVOLUTION_SOURCE, "rev-parse", "HEAD"], text=True, capture_output=True).stdout.strip(),
        "evolution_jar_sha256": sha256(jar),
        "environment": {"os": platform.platform(), "java": subprocess.run([java, "-version"], text=True, capture_output=True).stderr.splitlines()[0],
                        "python": platform.python_version(),
                        **{tool: (subprocess.run([tool, "-V" if tool == "iverilog" else "--version"], text=True, capture_output=True).stdout
                                  .splitlines() or [""])[0] for tool in ("yosys", "iverilog", "nvc")}},
        "date": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "suites": results,
    }
    existing = json.loads((out / "results.json").read_text()) if only and (out / "results.json").exists() else {}
    if existing:
        existing["suites"].update(results)
        receipt["suites"] = existing["suites"]
    (out / "results.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n")
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
