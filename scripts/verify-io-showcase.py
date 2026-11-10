#!/usr/bin/env python3
"""Check the shared I/O UI fixture against the compiled core, without invoking Gradle."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-jar", type=Path, default=ROOT / "core/build/libs/core-jvm.jar")
    parser.add_argument("--desktop-jar", type=Path)
    parser.add_argument("--write", action="store_true", help="Regenerate the checked-in .circ and target manifest.")
    args = parser.parse_args()
    results = ROOT / "outputs/io-showcase"
    results.mkdir(parents=True, exist_ok=True)
    (results / "result.json").write_text(json.dumps({"status": "RUNNING", "uiExecution": {"android": "NOT_RUN", "ios": "NOT_RUN"}}, indent=2) + "\n")
    original_core = args.core_jar.resolve(strict=True)
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"

    def jar(group: str, artifact: str, revision: str) -> Path:
        matches = list((cache / group / artifact / revision).glob("*/*.jar"))
        if len(matches) != 1:
            raise RuntimeError(f"Expected one cached JAR for {group}:{artifact}:{revision}, found {len(matches)}")
        return matches[0]

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
    annotations = jar("org.jetbrains", "annotations", "24.0.1")
    compiler = [
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version),
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", version),
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.kotlin", "kotlin-daemon-embeddable", version),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        annotations,
    ]
    support = [
        stdlib, annotations,
        jar("org.jetbrains.kotlin", "kotlin-test", version),
        jar("org.jetbrains.kotlin", "kotlin-test-junit", version),
        jar("junit", "junit", "4.13.2"),
        jar("org.hamcrest", "hamcrest-core", "1.3"),
    ]
    receipt = {
        "status": "RUNNING",
        "scope": "fixture-core-and-optional-desktop-geometry",
        "uiExecution": {"android": "NOT_RUN", "ios": "NOT_RUN"},
        "sourceRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "coreJar": {"path": str(original_core), "sha256": digest(original_core)},
        "kotlinVersion": version,
        "desktopGeometry": "NOT_RUN",
    }
    with tempfile.TemporaryDirectory(prefix="logisim-io-showcase-") as raw:
        temporary = Path(raw)
        core = temporary / "core-jvm.jar"
        shutil.copyfile(original_core, core)
        classpath = os.pathsep.join(map(str, [core, *support]))
        classes = temporary / "classes"
        classes.mkdir()
        sources = [
            ROOT / "scripts/fixtures/IoShowcaseFixture.kt",
            ROOT / "core/src/jvmTest/kotlin/dev/logisim/mobile/core/IoShowcaseFixtureTest.kt",
        ]
        subprocess.run([
            "java", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21",
            "-classpath", classpath, "-d", str(classes), *map(str, sources),
        ], check=True, cwd=ROOT)
        runtime = os.pathsep.join([str(classes), classpath])
        generated = temporary / "generated"
        subprocess.run(["java", "-cp", runtime, "dev.logisim.mobile.fixtures.IoShowcaseFixture", str(generated)], check=True, cwd=ROOT)
        for name in ("io-showcase.circ", "io-showcase.targets.json"):
            destination = ROOT / "examples" / name
            if args.write:
                shutil.copyfile(generated / name, destination)
            elif not destination.is_file() or destination.read_bytes() != (generated / name).read_bytes():
                raise RuntimeError(f"{destination.relative_to(ROOT)} differs from the generator; run with --write")
        junit = subprocess.run([
            "java", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", runtime,
            "org.junit.runner.JUnitCore", "dev.logisim.mobile.core.IoShowcaseFixtureTest",
        ], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (results / "junit.log").write_text(junit.stdout)
        print(junit.stdout)
        if junit.returncode:
            raise RuntimeError(f"I/O fixture tests failed, see {results / 'junit.log'}")
        match = re.search(r"OK \((\d+) tests?\)", junit.stdout)
        if match is None:
            raise RuntimeError("JUnit succeeded without an identifiable test count")
        receipt["coreTests"] = {"status": "PASS", "tests": int(match.group(1))}
        if args.desktop_jar:
            desktop = args.desktop_jar.resolve(strict=True)
            desktop_classpath = os.pathsep.join(map(str, [core, stdlib, desktop]))
            subprocess.run(["javac", "-cp", desktop_classpath, "-d", str(classes), str(ROOT / "scripts/CircDesktopCompatibility.java")], check=True, cwd=ROOT)
            checked = subprocess.run([
                "java", "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + desktop_classpath,
                "CircDesktopCompatibility", "examples/io-showcase.circ",
            ], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (results / "desktop.log").write_text(checked.stdout)
            print(checked.stdout)
            if checked.returncode:
                raise RuntimeError(f"Desktop fixture check failed, see {results / 'desktop.log'}")
            receipt["desktopGeometry"] = {"status": "PASS", "jarSha256": digest(desktop)}
        if digest(original_core) != receipt["coreJar"]["sha256"]:
            raise RuntimeError("The source core JAR changed during verification; rerun against a fixed artifact")
        receipt["sources"] = {str(path.relative_to(ROOT)): digest(path) for path in sources}
        receipt["fixtures"] = {name: digest(ROOT / "examples" / name) for name in ("io-showcase.circ", "io-showcase.targets.json")}
    receipt["status"] = "PASS"
    (results / "result.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"PASS fixture/core checks. Android and iOS UI execution remain NOT_RUN. Receipt: {results / 'result.json'}")


if __name__ == "__main__":
    try:
        main()
    except BaseException as failure:
        result_file = ROOT / "outputs/io-showcase/result.json"
        result_file.parent.mkdir(parents=True, exist_ok=True)
        previous = json.loads(result_file.read_text()) if result_file.is_file() else {}
        previous.update(status="FAIL", error=f"{type(failure).__name__}: {failure}")
        result_file.write_text(json.dumps(previous, indent=2) + "\n")
        raise
