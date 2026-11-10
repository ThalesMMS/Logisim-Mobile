#!/usr/bin/env python3
"""Compare codec regex caching with existing compiler/core artifacts; never invokes Gradle."""
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
SOURCE = "core/src/commonMain/kotlin/dev/logisim/mobile/core/CircCodec.kt"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--reference-jar", type=Path, required=True, help="An already compiled compatible core-jvm.jar")
parser.add_argument("--baseline-revision", required=True)
parser.add_argument("--iterations", type=int, default=31)
parser.add_argument("--warmups", type=int, default=31)
parser.add_argument("--output", type=Path, default=ROOT / "outputs/performance/codec-regex.json")
args = parser.parse_args()
if not 3 <= args.iterations <= 1000 or not 0 <= args.warmups <= 100:
    parser.error("iterations must be 3..1000 and warmups 0..100")
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
def cached(group, name, release):
    return next((cache / group / name / release).glob("*/*.jar"))
stdlib = cached("org.jetbrains.kotlin", "kotlin-stdlib", version)
compiler = [cached("org.jetbrains.kotlin", name, version) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [cached("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
             cached("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
             cached("org.jetbrains", "annotations", "13.0")]
baseline = subprocess.check_output(["git", "show", f"{args.baseline_revision}:{SOURCE}"], cwd=ROOT)
patched = (ROOT / SOURCE).read_bytes()
results = []
artifacts = []
bytecode = {}
with tempfile.TemporaryDirectory(prefix="codec-regex-") as temporary:
    work = Path(temporary)
    reference = work / "core-reference.jar"
    shutil.copyfile(args.reference_jar, reference)
    for label, source in (("baseline", baseline), ("cached", patched)):
        directory = work / label
        directory.mkdir()
        source_file = directory / "CircCodec.kt"
        source_file.write_bytes(source)
        subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21",
            "-classpath", f"{reference}{os.pathsep}{stdlib}", f"-Xfriend-paths={reference}",
            "-d", str(directory), str(source_file), str(ROOT / "scripts/performance/CodecRegexBenchmark.kt")], check=True)
        listing = subprocess.check_output(["javap", "-private", "-c", "-classpath", str(directory),
            "dev.logisim.mobile.core.CircCodec"], text=True)
        bytecode[label] = {}
        for method in ("parsePoint", "appendAttribute"):
            block = next(block for block in listing.split("\n\n") if re.search(rf"\b{method}\([^\n]*\);", block))
            count = block.count('kotlin/text/Regex."<init>"')
            bytecode[label][method] = count
            expected = 1 if label == "baseline" else 0
            if count != expected:
                raise AssertionError(f"{label} {method}: expected {expected} Regex constructors, found {count}")
    for label in ("baseline", "cached", "cached", "baseline"):
        output = subprocess.check_output(["java", "-Xms256m", "-Xmx2g", "-cp",
            os.pathsep.join(map(str, (work / label, reference, stdlib))),
            "dev.logisim.mobile.core.CodecRegexBenchmarkKt", str(ROOT / "examples/sap1.circ"),
            str(args.iterations), str(args.warmups)], text=True)
        if "grammar\t23\tpassed" not in output:
            raise AssertionError("Grammar boundary checks did not complete")
        run_artifacts = []
        for line in output.splitlines():
            fields = line.split("\t")
            if fields[0] == "artifact":
                run_artifacts.append(fields[1:])
            elif fields[0] == "measurement":
                results.append(dict(variant=label, case=fields[1], phase=fields[2],
                    wall_median_ns=int(fields[3]), cpu_median_ns=int(fields[4]), allocated_median_bytes=int(fields[5])))
        if artifacts and artifacts != run_artifacts:
            raise AssertionError("Import/export changed circuit counts or serialized XML bytes")
        artifacts = run_artifacts
    report = dict(schema=1, baseline_revision=args.baseline_revision,
        source_sha256={"baseline": hashlib.sha256(baseline).hexdigest(), "cached": hashlib.sha256(patched).hexdigest()},
        reference_jar_sha256=hashlib.sha256(reference.read_bytes()).hexdigest(),
        runtime="JVM", java=subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0],
        iterations=args.iterations, warmups=args.warmups, execution_order=["baseline", "cached", "cached", "baseline"],
        hot_method_regex_constructors=bytecode, artifacts=artifacts, grammar_cases=23, results=results,
        limitation="JVM measurements; Android ANR must be rechecked on the device after integration.")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + "\n")
print(args.output)
print(json.dumps(bytecode))
