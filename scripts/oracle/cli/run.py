#!/usr/bin/env python3
"""Compare the Mobile headless CLI with Logisim Evolution's command line, option by option.

Run from any directory (``python3 -I scripts/oracle/cli/run.py --evolution-jar JAR``). Every case runs
the Evolution JAR and ``dev.logisim.mobile.core.HeadlessCircuitCli`` on the same fixtures, with the
same arguments or with the Mobile spelling listed in the case, and compares the outputs under the
normalization rules below. Results go to ``--out`` (default ``outputs/cli-oracle``): ``results.json``
and one log per case with both command lines and their complete outputs.

Isolation: Evolution runs with ``-Djava.awt.headless=true`` and ``IsolatedPreferencesFactory`` (a file
backed ``java.util.prefs`` store in the output folder), so it never reads or writes the user's real
preferences. Mobile runs with ``LOGISIM_MOBILE_CLI_PROFILE`` in the output folder.

Normalization rules
-------------------
- Exit codes are compared by class: success (0) or failure (non-zero). Evolution exits -1 (255) or 10
  on errors, the Mobile CLI 1, 2 or 3. Each case records both values.
- Evolution writes diagnostics, including ``halted due to ...``, through its logger on stderr. Only
  stdout is compared, plus the presence of the halt reason on either stream.
- ``speed`` reports wall-clock rates: the line format and the tick count are compared, not the rate.
- Options that only affect Evolution's GUI start (gates, locale, template, splash, clear-prefs) are
  compared at the preference level: ``EvolutionStartupOracle`` runs ``Startup.parseArgs`` in process
  and prints the persisted store; Mobile prints its launcher profile with ``--startup-settings``.
- ``-w``, ``-b`` and ``-n`` start Swing in Evolution before running, which fails headless.
  ``EvolutionAutomationOracle`` runs the same Evolution calls that ``Startup.run`` makes for them.

A case is PASS when the compared values are equal. ``known`` marks DIFF cases whose cause is recorded
in docs/parity-evidence (accepted difference or open divergence); the runner still reports them as DIFF.
"""

from __future__ import annotations

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
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "fixtures"
VECTORS = ROOT / "scripts/oracle/vectors"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def default_stdlib() -> Path:
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar"))


def java_home() -> Path:
    out = subprocess.run(["sh", "-c", f". '{ROOT}/scripts/java-home.sh' && printf %s \"$JAVA_HOME\""],
                         capture_output=True, text=True, check=True).stdout
    return Path(out)


class Runner:
    def __init__(self, args):
        self.out = args.out.resolve()
        if self.out.exists():
            shutil.rmtree(self.out)
        (self.out / "logs").mkdir(parents=True)
        self.work = Path(tempfile.mkdtemp(prefix="cli-oracle-"))
        self.timeout = args.timeout
        self.evolution_jar = args.evolution_jar.resolve()
        self.mobile_jar = args.mobile_jar.resolve()
        self.stdlib = (args.stdlib_jar or default_stdlib()).resolve()
        home = java_home()
        self.java = str(home / "bin/java")
        self.javac = str(home / "bin/javac")
        self.classes = self.work / "classes"
        self.classes.mkdir()
        subprocess.run([self.javac, "-cp", str(self.evolution_jar), "-d", str(self.classes),
                        str(HERE / "IsolatedPreferencesFactory.java"), str(HERE / "EvolutionStartupOracle.java"),
                        str(HERE / "EvolutionAutomationOracle.java")], check=True)
        vector_classes = self.work / "vector-classes"
        vector_classes.mkdir()
        mobile_cp = f"{self.mobile_jar}:{self.stdlib}"
        subprocess.run([self.javac, "-cp", mobile_cp, "-d", str(vector_classes), str(VECTORS / "VectorMobileOracle.java")], check=True)
        self.vector_fixtures = self.work / "vectors"
        subprocess.run([self.java, "-cp", f"{vector_classes}:{mobile_cp}", "VectorMobileOracle", "--fixtures",
                        str(self.vector_fixtures)], check=True)
        self.evolution_prefs = self.out / "evolution-prefs.properties"
        self.mobile_profile = self.out / "mobile-cli.properties"
        self.results = []
        self.default_language = None

    # -- process helpers -------------------------------------------------------------------------
    def _run(self, argv, stdin=b"", env=None):
        try:
            p = subprocess.run(argv, input=stdin, capture_output=True, timeout=self.timeout, cwd=ROOT, env=env)
            return {"rc": p.returncode, "stdout": p.stdout.decode("utf-8", "replace"),
                    "stderr": p.stderr.decode("utf-8", "replace")}
        except subprocess.TimeoutExpired as e:
            return {"rc": "timeout", "stdout": (e.stdout or b"").decode("utf-8", "replace"),
                    "stderr": (e.stderr or b"").decode("utf-8", "replace")}

    def _evo_base(self, prefs):
        return [self.java, "-Djava.awt.headless=true",
                "-Djava.util.prefs.PreferencesFactory=IsolatedPreferencesFactory",
                f"-Dlogisim.oracle.prefs={prefs or self.evolution_prefs}",
                "-cp", f"{self.classes}:{self.evolution_jar}"]

    def evo(self, *args, stdin=b"", prefs=None):
        r = self._run(self._evo_base(prefs) + ["com.cburch.logisim.Main", *args], stdin)
        r["argv"] = ["logisim-evolution", *args]
        return r

    def evo_class(self, cls, *args, prefs=None):
        r = self._run(self._evo_base(prefs) + [cls, *args])
        r["argv"] = [f"java {cls}", *args]
        return r

    def mob(self, *args, stdin=b""):
        env = dict(os.environ, LOGISIM_MOBILE_CLI_PROFILE=str(self.mobile_profile))
        r = self._run([self.java, "-cp", f"{self.mobile_jar}:{self.stdlib}", "dev.logisim.mobile.core.HeadlessCircuitCli",
                       *args], stdin, env)
        r["argv"] = ["HeadlessCircuitCli", *args]
        return r

    # -- recording -------------------------------------------------------------------------------
    def rel(self, text: str) -> str:
        return text.replace(str(self.work), "$WORK").replace(str(self.out), "$OUT").replace(str(ROOT) + "/", "")

    @staticmethod
    def digest(text):
        return f"{len(text.splitlines())} lines, sha256 {hashlib.sha256(text.encode()).hexdigest()}"

    def excerpt(self, text, limit=40):
        lines = self.rel(text).splitlines(True)
        if len(lines) <= 2 * limit:
            return "".join(lines)
        return "".join(lines[:limit]) + f"[... {len(lines) - 2 * limit} lines omitted ...]\n" + "".join(lines[-limit:])

    def record(self, row, case, passed, detail, runs, known=None):
        log = self.out / "logs" / f"{case}.txt"
        with log.open("w") as f:
            f.write(f"row: {row}\ncase: {case}\nresult: {'PASS' if passed else 'DIFF'}\ndetail: {detail}\n")
            for label, r in runs:
                f.write(f"\n=== {label}: {self.rel(' '.join(r['argv']))}\nexit: {r['rc']}\n"
                        f"--- stdout ({self.digest(r['stdout'])})\n{self.excerpt(r['stdout'])}"
                        f"--- stderr ({self.digest(r['stderr'])})\n{self.excerpt(r['stderr'])}")
        entry = {"row": row, "case": case, "result": "PASS" if passed else "DIFF", "detail": self.rel(detail),
                 "commands": {label: self.rel(" ".join(r["argv"])) for label, r in runs},
                 "exit": {label: r["rc"] for label, r in runs}, "log": f"logs/{case}.txt"}
        if known and not passed:
            entry["known"] = known
        self.results.append(entry)
        print(f"{'PASS' if passed else 'DIFF'} {row} {case}: {self.rel(detail)}")

    # -- comparison helpers ----------------------------------------------------------------------
    @staticmethod
    def ok(r):
        return r["rc"] == 0

    @staticmethod
    def accepted(r):
        return "Unknown option" not in r["stderr"]

    @staticmethod
    def halt_reason(r):
        m = re.search(r"halted due to ([a-z ]+)", r["stdout"] + r["stderr"])
        return m.group(1).strip() if m else None

    @staticmethod
    def without_halt_line(text):
        return "".join(l for l in text.splitlines(True) if not l.startswith("halted due to "))

    def same_stdout(self, row, case, e, m, known=None, transform=lambda s: s):
        es, ms = transform(e["stdout"]), m["stdout"]
        passed = es == ms and (e["rc"] == 0) == (m["rc"] == 0)
        detail = "stdout and exit class equal" if passed else (
            f"stdout differs (evolution {len(es.splitlines())} lines, mobile {len(ms.splitlines())} lines)"
            if es != ms else f"exit class differs (evolution {e['rc']}, mobile {m['rc']})")
        self.record(row, case, passed, detail, [("evolution", e), ("mobile", m)], known)

    # -- cases -----------------------------------------------------------------------------------
    def help_version(self):
        e, m = self.evo("--help"), self.mob("--help")
        e_opts = sorted(set(re.findall(r"(?<![\w-])(--?[a-z][a-z-]*)", e["stdout"].split("Options")[0])))
        missing = [o for o in e_opts if not re.search(rf"(?<![\w-]){re.escape(o)}(?![\w-])", m["stdout"])]
        self.record("cli/help", "help-exit", self.ok(e) and self.ok(m) and bool(m["stdout"].strip()),
                    "both exit 0 and print usage", [("evolution", e), ("mobile", m)])
        self.record("cli/help", "help-option-spellings", not missing,
                    "Evolution spellings absent from the Mobile help text: " + (", ".join(missing) or "none"),
                    [("evolution", e), ("mobile", m)], known="spellings")
        for flag in ("--version", "-v"):
            e, m = self.evo(flag), self.mob(flag)
            first_e, first_m = e["stdout"].splitlines()[:1], m["stdout"].splitlines()[:1]
            self.record("cli/version", f"version{flag.replace('-', '_')}", e["stdout"] == m["stdout"] and self.ok(m),
                        f"evolution {first_e} ({len(e['stdout'].splitlines())} lines), mobile {first_m}; "
                        f"exit {e['rc']}/{m['rc']}", [("evolution", e), ("mobile", m)], known="product-identity")

    def tty(self):
        fx = FIXTURES
        for name in ("examples/and.circ", "examples/bus-adder.circ", "examples/hierarchy.circ", "examples/d-flip-flop.circ"):
            e, m = self.evo("-t", "table", name), self.mob("--tty-evolution", "-tty", "table", name)
            self.same_stdout("cli/tty", f"table-{Path(name).stem}", e, m)
        for modes in ("table,csv", "table,tabs", "table,hex", "table,binary", "table,csv,hex", "table,tabs,binary"):
            e = self.evo("-t", modes, "examples/bus-adder.circ")
            m = self.mob("--tty-evolution", "-tty", modes, "examples/bus-adder.circ")
            self.same_stdout("cli/tty", f"table-bus-adder-{modes.replace(',', '-')}", e, m)
        # Same literal arguments on both programs.
        e, m = self.evo("-tty", "table", "examples/and.circ"), self.mob("-tty", "table", "examples/and.circ")
        self.same_stdout("cli/tty", "same-args-dash-tty-table", e, m, known="tty-default-profile")
        for spelling in ("-t", "--tty"):
            e, m = self.evo(spelling, "table", "examples/and.circ"), self.mob(spelling, "table", "examples/and.circ")
            self.same_stdout("cli/tty", f"same-args-{spelling.strip('-')}", e, m, known="spellings")
        # Temporal trace with a halt pin: same Evolution spelling on both programs.
        mem = str(fx / "memory.circ")
        e, m = self.evo("-t", "table,halt", mem), self.mob("-t", "table,halt", mem)
        reason_on_stderr = lambda r: "halted due to" in r["stderr"] and "halted due to" not in r["stdout"]
        passed = e["stdout"] == m["stdout"] and self.halt_reason(e) == self.halt_reason(m) \
            and reason_on_stderr(e) and reason_on_stderr(m)
        self.record("cli/tty", "halt-trace", passed,
                    f"trace equal: {e['stdout'] == m['stdout']}; halt reason evolution={self.halt_reason(e)!r}, "
                    f"mobile={self.halt_reason(m)!r}; both on stderr: {reason_on_stderr(e) and reason_on_stderr(m)}",
                    [("evolution", e), ("mobile", m)])
        # Statistics: Evolution's -t stats on both programs; the literal -tty keeps 2.7.1's captions.
        for name in ("examples/hierarchy.circ", "examples/sap1.circ", str(fx / "memory.circ")):
            e, m = self.evo("-t", "stats", name), self.mob("-t", "stats", name)
            self.same_stdout("cli/tty", f"stats-{Path(name).stem}", e, m)
        # Speed: format and tick count.
        e, m = self.evo("-t", "halt,speed", mem), self.mob("-t", "halt,speed", mem)
        pattern = re.compile(r"^(\d+(?:\.\d+)?) Hz \((\d+) ticks in (\d+) milliseconds\)$", re.M)
        me, mm = pattern.search(e["stdout"]), pattern.search(m["stdout"])
        passed = bool(me and mm and me.group(2) == mm.group(2))
        self.record("cli/tty", "speed", passed,
                    f"speed line format and tick count: evolution={me and me.group(2)}, mobile={mm and mm.group(2)}",
                    [("evolution", e), ("mobile", m)])
        # TTY component output.
        tty = str(fx / "tty.circ")
        e, m = self.evo("-t", "tty,halt", tty), self.mob("-tty", "tty,halt", tty)
        passed = e["stdout"] == self.without_halt_line(m["stdout"]) and self.halt_reason(e) == self.halt_reason(m)
        self.record("cli/tty", "tty-component", passed, "TTY bytes and halt reason equal",
                    [("evolution", e), ("mobile", m)])
        e, m = self.evo("-t", "table,tty", tty), self.mob("--tty-evolution", "-tty", "table,tty", tty)
        self.same_stdout("cli/tty", "tty-component-with-table", e, m)

    def memory(self):
        mem, mh, ch = str(FIXTURES / "memory.circ"), str(FIXTURES / "mem.hex"), str(FIXTURES / "code.hex")
        es, ms = self.work / "evolution-save.hex", self.work / "mobile-save.hex"
        e = self.evo("-t", "table,halt", mem, "-l", mh, "mem", "-l", ch, "code", "--save", str(es))
        m = self.mob("-t", "table,halt", mem, "-l", mh, "mem", "-l", ch, "code", "--save", str(ms))
        self.record("cli/load", "load-labeled-ram-and-rom", e["stdout"] == m["stdout"] and self.ok(e) == self.ok(m),
                    "same arguments: trace equal after loading mem.hex and code.hex by label",
                    [("evolution", e), ("mobile", m)])
        saved = es.exists() and ms.exists() and es.read_bytes() == ms.read_bytes()
        self.record("cli/save", "save-ram-after-halt", saved and self.ok(e) and self.ok(m),
                    f"saved images byte-equal: {saved} ({es.exists() and es.read_text().splitlines()[1:2]})",
                    [("evolution", e), ("mobile", m)])
        e = self.evo("-t", "table,halt", mem, "-l", mh)
        m = self.mob("-t", "table,halt", mem, "-l", mh)
        self.record("cli/load", "load-unlabeled", e["stdout"] == m["stdout"] and self.ok(e) == self.ok(m),
                    "same arguments: an unlabeled image loads every RAM and ROM in both programs",
                    [("evolution", e), ("mobile", m)])
        e = self.evo("-t", "table,halt", mem, "-l", mh, "nolabel")
        m = self.mob("-t", "table,halt", mem, "-l", mh, "nolabel")
        self.record("cli/load", "load-unknown-label", not self.ok(e) and not self.ok(m),
                    f"both fail for an absent label (exit {e['rc']}/{m['rc']})", [("evolution", e), ("mobile", m)])
        m = self.mob("--tty-evolution", "-tty", "table,halt", mem, "-l", mh, "mem")
        self.record("cli/load", "same-args-l", self.accepted(m), "Mobile accepts Evolution's '-l'",
                    [("mobile", m)], known="spellings")
        e = self.evo("-t", "table", "examples/and.circ", "--save", str(es))
        m = self.mob("--tty-evolution", "-tty", "table", "examples/and.circ", "--save", str(ms))
        self.record("cli/save", "save-without-ram", self.ok(e) == self.ok(m),
                    f"circuit without RAM and without halt pin: exit {e['rc']}/{m['rc']}",
                    [("evolution", e), ("mobile", m)], known="save-without-ram")

    def substitute(self):
        sub = FIXTURES / "sub"
        main, old, new = str(sub / "main.circ"), str(sub / "lib-not.circ"), str(sub / "lib-buf.circ")
        e = self.evo("-t", "table", "-s", old, new, main)
        m = self.mob("--tty-evolution", "-tty", "table", "-sub", old, new, main)
        self.same_stdout("cli/substitute", "absolute-old-path", e, m)
        e = self.evo("-t", "table", "-s", old, old, main)
        m = self.mob("--tty-evolution", "-tty", "table", "-sub", old, old, main)
        self.same_stdout("cli/substitute", "identity-mapping", e, m)
        e = self.evo("-t", "table", "-s", "lib-not.circ", new, main)
        m = self.mob("--tty-evolution", "-tty", "table", "-sub", "lib-not.circ", new, main)
        self.same_stdout("cli/substitute", "project-relative-old-path", e, m, known="substitute-paths")
        e = self.evo("-t", "table", main)
        m = self.mob("--tty-evolution", "-tty", "table", main)
        self.same_stdout("cli/substitute", "no-mapping", e, m, known="substitute-paths")
        for spelling in ("-s", "--substitute"):
            m = self.mob("--tty-evolution", "-tty", "table", spelling, old, new, main)
            self.record("cli/substitute", f"same-args-{spelling.strip('-')}", self.accepted(m),
                        f"Mobile accepts Evolution's '{spelling}'", [("mobile", m)], known="spellings")
        e = self.evo("-t", "table", "-s", old, new, "-s", old, old, main)
        m = self.mob("--tty-evolution", "-tty", "table", "-sub", old, new, "-sub", old, old, main)
        self.record("cli/substitute", "duplicate-old", not self.ok(e) and not self.ok(m),
                    f"both reject a repeated OLD (exit {e['rc']}/{m['rc']})", [("evolution", e), ("mobile", m)])

    def toplevel(self):
        sub = FIXTURES / "sub"
        main, old = str(sub / "main.circ"), str(sub / "lib-not.circ")
        for circuit in ("top", "main"):
            e = self.evo("-t", "table", "-s", old, old, "--toplevel-circuit", circuit, main)
            m = self.mob("--tty-evolution", "-tty", "table", "-sub", old, old, "--circuit", circuit, main)
            self.same_stdout("cli/toplevel-circuit", f"select-{circuit}", e, m)
        e = self.evo("-t", "table", "-s", old, old, "--toplevel-circuit", "absent", main)
        m = self.mob("--tty-evolution", "-tty", "table", "-sub", old, old, "--circuit", "absent", main)
        self.record("cli/toplevel-circuit", "absent-circuit", self.ok(e) == self.ok(m),
                    f"absent circuit: exit {e['rc']}/{m['rc']}", [("evolution", e), ("mobile", m)], known="toplevel-absent")
        m = self.mob("--tty-evolution", "-tty", "table", "-sub", old, old, "--toplevel-circuit", "top", main)
        self.record("cli/toplevel-circuit", "same-args-toplevel-circuit", self.accepted(m),
                    "Mobile accepts Evolution's '--toplevel-circuit'", [("mobile", m)], known="spellings")

    @staticmethod
    def evolution_vector_failures(r):
        # TestThread prints "Error on test vector N (line L):" on stderr and the "  PIN = observed
        # (expected ...)" lines of each failure on stdout, one block per failing vector, in order.
        numbers = [int(n) for n in re.findall(r"Error on test vector (\d+)", r["stderr"])]
        blocks, current = [], None
        for line in r["stdout"].splitlines():
            hit = re.match(r"\s+(\S+) = (\S+) \(expected", line)
            if hit:
                if current is None:
                    current = []
                    blocks.append(current)
                current.append((hit.group(1), hit.group(2).replace("U", "?")))
            else:
                current = None
        totals = re.search(r"Passed: (\d+), Failed: (\d+)", r["stdout"])
        return dict(zip(numbers, blocks)), totals and (int(totals.group(1)), int(totals.group(2)))

    @staticmethod
    def mobile_vector_failures(m):
        import csv
        import io
        rows = {}
        table = list(csv.reader(io.StringIO(m["stdout"])))
        header = table[0]
        for line in table[1:]:
            if line[5] == "FAIL":
                rows[int(line[1])] = [(d.split(":")[0], d.split("!= ")[1]) for d in line[-1].split("; ")]
        totals = re.search(r"passed=(\d+) failed=(\d+)", m["stderr"])
        return rows, totals and (int(totals.group(1)), int(totals.group(2)))

    def test_vector(self):
        for fixture, circuit, vectors in (("and", "AND", "and.txt"), ("flip", "Flip", "flip.txt")):
            circ, vec = str(self.vector_fixtures / f"{fixture}.circ"), str(VECTORS / vectors)
            e = self.evo_class("EvolutionAutomationOracle", "vector", circ, circuit, vec)
            m = self.mob("-w", circuit, vec, circ)
            ef, mf = self.evolution_vector_failures(e), self.mobile_vector_failures(m)
            self.record("cli/test-vector", f"vectors-{fixture}", ef == mf,
                        f"failing vectors, observed values and totals: evolution={ef}, mobile={mf}",
                        [("evolution", e), ("mobile", m)])
            self.record("cli/test-vector", f"vectors-{fixture}-exit", (e["rc"] == 0) == (m["rc"] == 0),
                        f"exit evolution={e['rc']} (Startup.run exits 0 after -w), mobile={m['rc']}",
                        [("evolution", e), ("mobile", m)], known="vector-exit")

    def test_circuit(self):
        for fixture in ("bench-pass", "bench-fail"):
            circ = str(FIXTURES / f"{fixture}.circ")
            e = self.evo_class("EvolutionAutomationOracle", "bench", circ)
            m = self.mob("-b", circ)
            self.same_stdout("cli/test-circuit", fixture, e, m)

    def new_file_format(self):
        sources = ["examples/and.circ", "examples/hierarchy.circ", "examples/bus-adder.circ",
                   str(FIXTURES / "memory.circ"), "core/src/jvmTest/resources/original/counter.circ"]
        for source in sources:
            stem = Path(source).stem
            eo, mo = self.work / f"evolution-{stem}.circ", self.work / f"mobile-{stem}.circ"
            e = self.evo_class("EvolutionAutomationOracle", "convert", source, str(eo))
            m = self.mob("-n", source, str(mo))
            modes = "table,halt" if stem in ("memory", "counter") else "table"
            tables = {label: self.evo("-t", modes, str(path))["stdout"] for label, path in
                      (("original", Path(source) if Path(source).is_absolute() else ROOT / source), ("evolution-n", eo), ("mobile-n", mo))}
            passed = self.ok(e) and self.ok(m) and len(set(tables.values())) == 1
            self.record("cli/new-file-format", f"convert-{stem}", passed,
                        "Evolution -t " + modes + " output equal for the original, Evolution's -n output and Mobile's -n output: "
                        + str({k: hashlib.sha256(v.encode()).hexdigest()[:12] for k, v in tables.items()}),
                        [("evolution", e), ("mobile", m)])
            header = lambda p: re.search(r'<project source="([^"]+)"', p.read_text()).group(1) if p.exists() else None
            self.record("cli/new-file-format", f"convert-{stem}-header", header(eo) == header(mo),
                        f"written source version: evolution={header(eo)}, mobile={header(mo)}",
                        [("evolution", e), ("mobile", m)], known="convert-preserves-profile")

    def test_fpga(self):
        project = "core/src/jvmTest/resources/fpga/sr-dedicated.circ"
        workspace = self.work / "fpga-workspace"
        prefs = self.work / "fpga-prefs.properties"
        prefs.write_text(f"/com/cburch/logisim\\u0001FPGAWorkspace={workspace}\n")
        e = self.evo("-f", project, "main", "BASYS3", "HDLONLY", prefs=prefs)
        archive = self.work / "mobile-fpga.zip"
        m = self.mob("-f", project, "main", "core/src/jvmTest/resources/fpga/BASYS3.xml", "HDLONLY", "--output", str(archive))
        xdc_e = next(workspace.rglob("*.xdc"), None)
        def constraints(text):
            pins = sorted(re.findall(r"PACKAGE_PIN (\S+) \[get_ports \{(\S+)\}\]", text))
            standards = dict(re.findall(r"IOSTANDARD (\S+) \[get_ports \{(\S+)\}\]", text)[::1])
            standards = {port: std for std, port in re.findall(r"IOSTANDARD (\S+) \[get_ports \{(\S+)\}\]", text)}
            clocks = re.findall(r"create_clock[^\n]*-period (\S+)[^\n]*\[get_ports \{(\S+)\}\]", text)
            clock_pins = sorted((pin, float(period)) for period, port in clocks for pin, p in pins if p == port)
            return sorted((pin, standards.get(port)) for pin, port in pins), clock_pins
        ce = constraints(xdc_e.read_text()) if xdc_e else None
        cm = None
        if archive.exists():
            with zipfile.ZipFile(archive) as z:
                cm = constraints(z.read("pins.xdc").decode())
                names = z.namelist()
        flows = (bool(xdc_e and any(workspace.rglob("vivado*.tcl"))), archive.exists() and "build.tcl" in names
                 and "synth_design" in zipfile.ZipFile(archive).read("build.tcl").decode())
        self.record("cli/test-fpga", "hdlonly-basys3-constraints", ce is not None and ce == cm and all(flows),
                    f"Vivado flow on both: {flows}; physical pins, I/O standards and clock period: evolution={ce}, mobile={cm}",
                    [("evolution", e), ("mobile", m)])

    def startup_profile(self):
        """Gates, locale, template, splash and clear-prefs at the preference level."""
        tpl = FIXTURES / "template.circ"
        steps = [
            ("defaults", [], []),
            ("gates-iec", ["--gates", "iec"], ["--gates", "iec"]),
            ("persisted-after-gates", [], []),
            ("gates-ANSI-short", ["-g", "ANSI"], ["-g", "ANSI"]),
            ("gates-invalid", ["--gates", "round"], ["--gates", "round"]),
            ("locale-pt", ["--locale", "pt"], ["--locale", "pt"]),
            ("persisted-after-locale", [], []),
            ("locale-pt-BR", ["-o", "pt-BR"], ["-o", "pt-BR"]),
            ("locale-de", ["--locale", "de"], ["--locale", "de"]),
            ("locale-en", ["--locale", "en"], ["--locale", "en"]),
            ("template-empty", ["--user-template", "empty"], ["--user-template", "empty"]),
            ("persisted-after-template", [], []),
            ("template-custom", ["-u", str(tpl)], ["-u", str(tpl)]),
            ("template-missing", ["-u", str(FIXTURES / "absent.circ")], ["-u", str(FIXTURES / "absent.circ")]),
            ("template-plain", ["-u", "plain"], ["-u", "plain"]),
            ("no-splash", ["--no-splash"], ["--no-splash"]),
            ("gates-iec-before-clear", ["--gates", "iec", "-u", "empty", "--locale", "pt"], ["--gates", "iec", "-u", "empty", "--locale", "pt"]),
            ("clear-prefs", ["--clear-prefs"], ["--clear-prefs"]),
            ("persisted-after-clear", [], []),
            ("gates-iec-before-tty-clear", ["--gates", "iec"], ["--gates", "iec"]),
            ("clear-prefs-with-tty", ["--clear-prefs", "-t", "table", "examples/and.circ"],
             ["--clear-prefs", "--tty-evolution", "-tty", "table", "examples/and.circ"]),
            ("persisted-after-tty-clear", [], []),
        ]
        def row_of(case):
            for word, row in (("clear", "cli/clear-prefs"), ("defaults", "cli/clear-prefs"), ("locale", "cli/locale"),
                              ("template", "cli/user-template"), ("splash", "cli/no-splash")):
                if word in case:
                    return row
            return "cli/gates"
        known = {"locale-pt-BR": "locale-set", "locale-de": "locale-set", "no-splash": "no-splash-notice",
                 "clear-prefs-with-tty": "clear-prefs-tty", "persisted-after-tty-clear": "clear-prefs-tty"}
        for case, evo_args, mob_args in steps:
            e = self.evo_class("EvolutionStartupOracle", *evo_args) if "-t" not in evo_args else None
            if e is None:  # a TTY run: the real Evolution entry point, then read the store
                run = self.evo(*evo_args)
                e = self.evo_class("EvolutionStartupOracle")
                e = dict(e, rc=run["rc"], argv=run["argv"], stdout=e["stdout"], stderr=run["stderr"] + e["stderr"])
            m_run = self.mob(*mob_args, "--startup-settings") if "-tty" not in mob_args else self.mob(*mob_args)
            m = m_run if "-tty" not in mob_args else dict(self.mob("--startup-settings"), argv=m_run["argv"],
                                                           rc=m_run["rc"], stderr=m_run["stderr"])
            ev = dict(re.findall(r"^(\w+)=(.*)$", e["stdout"], re.M))
            mv = dict(re.findall(r"^(\w+)=(.*)$", m["stdout"], re.M)) if self.ok(m) else {}
            parse_ok = ev.get("parse", "ok") == "ok" and e["rc"] == 0
            session = re.search(r"session=(\w+)", ev.get("locale", "")).group(1) if ev.get("locale") else None
            stored = ev.get("locale", "").split(" ")[0]
            if case == "defaults":
                self.default_language = session
            evo_profile = {"gates": ev.get("gates"), "locale": stored or self.default_language,
                           "template": ev.get("template", "").replace(str(tpl.resolve()), "FILE").replace(str(tpl), "FILE")}
            mob_profile = {k: mv.get(k, "").replace(str(tpl.resolve()), "FILE").replace(str(tpl), "FILE")
                           for k in ("gates", "locale", "template")}
            if mob_profile["template"] not in ("empty", "plain", ""):
                mob_profile["template"] = "custom:FILE"
            if evo_profile["template"].startswith("custom:"):
                evo_profile["template"] = "custom:FILE"
            if not parse_ok or not self.ok(m_run):
                passed = parse_ok == self.ok(m_run)
                detail = f"option rejected: evolution={not parse_ok} (exit {e['rc']}), mobile={not self.ok(m_run)} (exit {m_run['rc']})"
            else:
                passed = evo_profile == mob_profile
                detail = f"evolution={evo_profile}, mobile={mob_profile}"
            if case == "no-splash":
                passed = passed and ev.get("splash") == "false" and not m_run["stderr"].strip()
                detail += f"; evolution splash={ev.get('splash')}, mobile stderr={m_run['stderr'].strip()!r}"
            self.record(row_of(case), f"profile-{case}", passed, detail, [("evolution", e), ("mobile", m)], known.get(case))

    def run(self):
        for step in (self.help_version, self.tty, self.memory, self.substitute, self.toplevel, self.test_vector,
                     self.test_circuit, self.new_file_format, self.test_fpga, self.startup_profile):
            step()
        rows = {}
        for r in self.results:
            rows.setdefault(r["row"], {"pass": 0, "diff": 0, "known": []})
            if r["result"] == "PASS":
                rows[r["row"]]["pass"] += 1
            else:
                rows[r["row"]]["diff"] += 1
                rows[r["row"]]["known"].append(r.get("known", "UNEXPLAINED"))
        mobile_revision = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        java_version = subprocess.run([self.java, "-version"], capture_output=True, text=True).stderr.splitlines()[0]
        summary = {
            "mobile_revision": mobile_revision,
            "mobile_jar_sha256": sha256(self.mobile_jar),
            "evolution_jar_sha256": sha256(self.evolution_jar),
            "java": java_version,
            "command": "python3 -I scripts/oracle/cli/run.py --evolution-jar EVOLUTION_JAR",
            "rows": rows,
            "cases": self.results,
        }
        (self.out / "results.json").write_text(json.dumps(summary, indent=1, ensure_ascii=False) + "\n")
        shutil.rmtree(self.work, ignore_errors=True)
        unexplained = [r for r in self.results if r["result"] == "DIFF" and "known" not in r]
        print(f"{sum(r['result'] == 'PASS' for r in self.results)} PASS, "
              f"{sum(r['result'] == 'DIFF' for r in self.results)} DIFF ({len(unexplained)} without a recorded cause)")
        return 1 if unexplained else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--evolution-jar", type=Path, required=True)
    parser.add_argument("--mobile-jar", type=Path, default=ROOT / "core/build/libs/core-jvm.jar")
    parser.add_argument("--stdlib-jar", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "outputs/cli-oracle")
    parser.add_argument("--timeout", type=int, default=60)
    sys.exit(Runner(parser.parse_args()).run())


if __name__ == "__main__":
    main()
