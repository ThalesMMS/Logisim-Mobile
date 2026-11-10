#!/usr/bin/env python3
"""Compare timestamped transitions with the pinned desktop propagator and frozen records."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "examples/corpus/temporal"
REVISION = "d58a61e0c290361722b43df5b1a6b86c1f7c7f0d"


def run(command):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, command))}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def records(text):
    lines = [line for line in text.splitlines() if line.startswith("E\t")]
    if not lines:
        raise ValueError("Oracle emitted no transitions")
    for line in lines:
        fields = line.split("\t")
        if len(fields) != 5 or int(fields[2]) < 0:
            raise ValueError(f"Invalid temporal record: {line}")
    return lines


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--desktop-jar", required=True, type=Path)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--update-reference", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "outputs/temporal-validation")
    args = parser.parse_args()
    desktop = args.desktop_jar.resolve()
    if not args.skip_build:
        subprocess.run(["./gradlew", ":core:jvmJar", "--console=plain"], cwd=ROOT, check=True)
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text())[1]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    stdlib = Path(os.environ["KOTLIN_STDLIB_JAR"]) if "KOTLIN_STDLIB_JAR" in os.environ else next(
        (cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar"))
    mobile = ROOT / "core/build/libs/core-jvm.jar"
    args.output.mkdir(parents=True, exist_ok=True)
    provenance = {"desktop_revision": REVISION, "desktop_jar_sha256": sha(desktop), "fixtures": {}}
    pending = {}
    fixtures = sorted(FIXTURES.glob("*.circ"))
    expected_ids = {"simultaneous-registers", "derived-clock-feedback", "ram-feedback", "subcircuit-ripple",
                    "gate-input-tail", "register-input-tail"}
    if {fixture.stem for fixture in fixtures} != expected_ids:
        raise ValueError("Temporal corpus must include the six reviewed timing cases")
    with tempfile.TemporaryDirectory(prefix="logisim-temporal-") as directory:
        run(["javac", "-cp", str(desktop), "-d", directory, "scripts/oracle/TemporalDesktopOracle.java"])
        desktop_command = ["java", "-Djava.awt.headless=true", "-cp", directory + os.pathsep + str(desktop),
                           "com.cburch.logisim.circuit.TemporalDesktopOracle"]
        mobile_command = ["java", "-cp", str(mobile) + os.pathsep + str(stdlib), "dev.logisim.mobile.core.TemporalCorpusCli"]
        total = 0
        for fixture in fixtures:
            ops = fixture.with_suffix(".ops.tsv")
            desktop_text = run(desktop_command + [str(fixture), str(ops)])
            revision = next(line.split("\t")[2] for line in desktop_text.splitlines() if line.startswith("M\trevision\t"))
            if not REVISION.startswith(revision):
                raise ValueError(f"Wrong desktop revision {revision}")
            expected = records(desktop_text)
            for mode in ("settle", "step"):
                text = run(mobile_command + [str(fixture), str(ops)] + (["step"] if mode == "step" else []))
                (args.output / f"{fixture.stem}.{mode}.tsv").write_text(text)
                actual = records(text)
                if expected != actual:
                    import difflib
                    raise ValueError(f"Temporal divergence in {fixture.stem} ({mode})\n" + "\n".join(
                        difflib.unified_diff(expected, actual, fromfile="desktop", tofile="mobile", lineterm="")))
            reference = fixture.with_suffix(".desktop.tsv")
            if args.update_reference:
                pending[reference] = "\n".join(expected) + "\n"
            elif records(reference.read_text()) != expected:
                raise ValueError(f"Frozen desktop records changed for {fixture.stem}")
            provenance["fixtures"][fixture.stem] = {"circuit_sha256": sha(fixture), "operations_sha256": sha(ops),
                                                       "records": len(expected)}
            total += len(expected)
        if args.update_reference:
            for path, text in pending.items(): path.write_text(text)
            (FIXTURES / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        elif json.loads((FIXTURES / "provenance.json").read_text()) != provenance:
            raise ValueError("Temporal provenance differs from reviewed fixtures or desktop JAR")
    run([sys.executable, "scripts/generate-temporal-fixtures.py"] + ([] if args.update_reference else ["--check"]))
    print(f"Temporal oracle passed: {len(provenance['fixtures'])} fixtures, {total} records in settle and step modes")


if __name__ == "__main__":
    main()
