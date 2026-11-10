#!/usr/bin/env python3
"""Measure the JVM core on generated circuits, checking each propagated result."""
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
JFR_PROFILE = ROOT / "scripts/performance/benchmark-profile.jfc"
JAVA_ENVIRONMENT_KEYS = (
    "PATH", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP",
    "SYSTEMROOT", "WINDIR",
)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--workload", choices=("circuits", "spatial-index", "corpus"), default="circuits")
parser.add_argument("--sizes", help="Comma-separated gates/instances or spatial items")
parser.add_argument("--iterations", type=int, default=7)
parser.add_argument("--warmups", type=int, default=2)
parser.add_argument("--output", type=Path)
parser.add_argument("--skip-build", action="store_true")
parser.add_argument("--prepare-only", action="store_true",
                    help="Build and verify the source/class stamp without launching a benchmark JVM")
parser.add_argument("--no-incremental", action="store_true", help="Compile without Kotlin incremental caches")
parser.add_argument("--jfr", type=Path, help="Write a JVM Flight Recording for CPU and allocation analysis")
parser.add_argument("--phases", help="Measure only selected circuit phases, separated by commas")
args = parser.parse_args()
args.sizes = args.sizes or ("1000,10000,50000" if args.workload == "spatial-index" else
    "16,64,256,384,1024" if args.workload == "corpus" else "16,64,256")
args.output = args.output or ROOT / ("outputs/performance/corpus" if args.workload == "corpus" else "outputs/performance/current.json")
sizes = [int(value) for value in args.sizes.split(",")]
maximum = 1_000_000 if args.workload == "spatial-index" else 16_384
if not sizes or any(value < 1 or value > maximum for value in sizes):
    parser.error(f"sizes must be between 1 and {maximum}")
if not 3 <= args.iterations <= 1000 or not 0 <= args.warmups <= 100:
    parser.error("iterations must be 3..1000 and warmups 0..100")
if args.prepare_only and args.jfr:
    parser.error("--prepare-only cannot record JFR")
if args.phases and (args.workload != "circuits" or not set(args.phases.split(',')) <=
                    {"serialize", "import", "cold_settle", "input_change", "unchanged_settle"}):
    parser.error("phases must name circuit phases")
if args.workload == "corpus" and args.output.exists() and not args.prepare_only:
    parser.error("Use a new corpus directory.")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def same_destination(left, right):
    return left.resolve() == right.resolve() or (left.exists() and right.exists() and left.samefile(right))


def source_hashes():
    files = []
    for source_set in ("commonMain", "jvmMain", "jvmAndAndroidMain", "commonTest", "jvmTest"):
        files += list((ROOT / "core/src" / source_set).rglob("*.kt"))
    files += [ROOT / "build.gradle.kts", ROOT / "core/build.gradle.kts", ROOT / "gradle.properties",
              Path(__file__).resolve(), JFR_PROFILE]
    for name in ("settings.gradle.kts", "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties"):
        if (ROOT / name).is_file():
            files.append(ROOT / name)
    return {str(path.relative_to(ROOT)): digest(path) for path in sorted(files)}


def revision():
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


expected_revision = revision()
expected_sources = source_hashes()
runner_hash = digest(Path(__file__))
initially_dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True))


def check_sources(phase):
    if revision() != expected_revision or source_hashes() != expected_sources or digest(Path(__file__)) != runner_hash:
        parser.error(f"benchmark sources or revision changed {phase}; results were not published")


roots = {name: ROOT / "core/build/classes/kotlin/jvm" / name for name in ("main", "test")}
stamp = ROOT / "core/build/performance-sources.json"


def class_hashes(files):
    return {str(Path("core/build/classes/kotlin/jvm") / name): sha256
            for name, sha256 in files.items() if name.endswith(".class")}


def directory_hashes(directories):
    return {f"{name}/{path.relative_to(root).as_posix()}": digest(path)
            for name, root in directories.items() for path in sorted(root.rglob("*")) if path.is_file()}


def classpath_hashes(directories, stdlib):
    return directory_hashes(directories) | {"kotlin-stdlib.jar": digest(stdlib)}


check_sources("before build")

if args.skip_build:
    compiled_files = directory_hashes(roots)
    compiled_hashes = class_hashes(compiled_files)
    if not compiled_hashes or not stamp.is_file() or json.loads(stamp.read_text()) != {
            "sources": expected_sources, "classes": compiled_hashes, "classpath_files": compiled_files}:
        parser.error("compiled benchmark identity is missing or stale; run once without --skip-build")
else:
    subprocess.run([str(ROOT / "gradlew"), ":core:jvmTestClasses", "--console=plain",
        *(["-Pkotlin.incremental=false"] if args.no_incremental else [])], cwd=ROOT, check=True)
    check_sources("during build")
    compiled_files = directory_hashes(roots)
    compiled_hashes = class_hashes(compiled_files)
    if not compiled_hashes:
        parser.error("compiled benchmark classes are missing")
    stamp.write_text(json.dumps({"sources": expected_sources, "classes": compiled_hashes,
        "classpath_files": compiled_files}, sort_keys=True) + "\n")
check_sources("after build")
if args.prepare_only:
    print(json.dumps({"prepared": True, "scope": "build_and_identity_only",
        "revision": expected_revision, "worktree_dirty": initially_dirty,
        "stamp": str(stamp), "compiled_classes": len(compiled_hashes),
        "benchmark_executed": False}, indent=2))
    raise SystemExit(0)
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
stdlibs = sorted((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar"))
if len(stdlibs) != 1:
    parser.error("expected exactly one Kotlin standard library JAR in the configured cache")
stdlib = stdlibs[0]
expected_classpath = compiled_files | {"kotlin-stdlib.jar": digest(stdlib)}
java = (Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else Path(shutil.which("java") or "java")
java = java.resolve(strict=True)
java_hash = digest(java)
java_environment = {name: os.environ[name] for name in JAVA_ENVIRONMENT_KEYS if name in os.environ}
java_version = subprocess.run([str(java), "--version"], cwd=ROOT,
    capture_output=True, text=True, check=True, env=java_environment).stdout.strip()


def check_artifacts(phase):
    if classpath_hashes(roots, stdlib) != expected_classpath or digest(java) != java_hash:
        parser.error(f"benchmark artifacts changed {phase}; results were not published")


entrypoint = "SpatialIndexBenchmark" if args.workload == "spatial-index" else "PerformanceCorpusExport" if args.workload == "corpus" else "CircuitPerformanceBenchmarkKt"
args.output = args.output.resolve()
if args.jfr:
    args.jfr = args.jfr.resolve()
args.output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="logisim-benchmark-", dir=args.output.parent) as temporary:
    directory = Path(temporary)
    copied_roots = {name: directory / "classpath" / name for name in roots}
    for name, root in roots.items():
        if root.exists():
            shutil.copytree(root, copied_roots[name])
        else:
            copied_roots[name].mkdir(parents=True)
    copied_stdlib = directory / "classpath/kotlin-stdlib.jar"
    shutil.copyfile(stdlib, copied_stdlib)
    if classpath_hashes(copied_roots, copied_stdlib) != expected_classpath:
        parser.error("copied benchmark classpath differs from the verified artifacts; results were not published")
    check_sources("while freezing classpath")
    check_artifacts("while freezing classpath")
    classpath = os.pathsep.join(map(str, [*copied_roots.values(), copied_stdlib]))
    staged = directory / "results" / args.output.name
    recording = directory / "recording.jfr"
    copied_profile = directory / "benchmark-profile.jfc"
    profile_hash = expected_sources[str(JFR_PROFILE.relative_to(ROOT))]
    if args.jfr:
        shutil.copyfile(JFR_PROFILE, copied_profile)
        if digest(copied_profile) != profile_hash:
            parser.error("copied JFR profile differs from the verified source; results were not published")
    jfr_arguments = [f"-XX:StartFlightRecording=filename={recording},settings={copied_profile},dumponexit=true"] if args.jfr else []
    arguments = [str(staged), args.sizes]
    if args.workload != "corpus":
        arguments += [str(args.iterations), str(args.warmups), *([args.phases] if args.phases else [])]
    command = [str(java), "-Xms256m", "-Xmx2g", *jfr_arguments, "-cp", classpath,
        f"dev.logisim.mobile.core.{entrypoint}", *arguments]
    subprocess.run(command, cwd=ROOT, check=True, env=java_environment)
    check_sources("during execution")
    check_artifacts("during execution")
    if classpath_hashes(copied_roots, copied_stdlib) != expected_classpath:
        parser.error("frozen benchmark classpath changed during execution; results were not published")
    if args.jfr and digest(copied_profile) != profile_hash:
        parser.error("frozen benchmark JFR profile changed during execution; results were not published")
    manifest = staged / "manifest.json" if args.workload == "corpus" else staged
    result = json.loads(manifest.read_text())
    payload_root = staged if args.workload == "corpus" else staged.parent
    payloads = {path.relative_to(payload_root).as_posix(): digest(path)
                for path in sorted(payload_root.rglob("*")) if path.is_file() and path != manifest}
    if args.jfr and not recording.is_file():
        parser.error("JVM did not produce the requested JFR recording; results were not published")
    if args.jfr and (same_destination(args.jfr, args.output) or
            (args.workload == "corpus" and args.output in args.jfr.parents) or
            (args.workload != "corpus" and any(same_destination(args.jfr, args.output.parent / name) for name in payloads))):
        parser.error("JFR destination overlaps benchmark results; results were not published")
    result["revision"] = expected_revision
    result["worktree_dirty"] = initially_dirty
    result["source_sha256"] = expected_sources | {str(Path(__file__).resolve().relative_to(ROOT)): runner_hash}
    result["compiled_class_sha256"] = compiled_hashes
    result["execution"] = {
        "argv": command,
        "build_skipped": args.skip_build,
        "java_path": str(java), "java_sha256": java_hash, "java_version": java_version,
        "java_environment_keys": sorted(java_environment),
        "java_environment_allowlist": list(JAVA_ENVIRONMENT_KEYS),
        "java_environment_policy": "Only the explicit runtime/locale/temp allowlist is inherited; no Java option variables or credentials.",
        "classpath_files_sha256": expected_classpath,
        "kotlin_stdlib_sha256": expected_classpath["kotlin-stdlib.jar"],
        "output_files_sha256": payloads,
        "jfr": {"path": str(args.jfr.resolve()), "sha256": digest(recording),
                "profile_source": str(JFR_PROFILE.relative_to(ROOT)), "profile_sha256": profile_hash,
                "profile_policy": "Sampled CPU/allocation and GC; initial environment, system properties and JVM information disabled."} if args.jfr else None,
        "identity_checks": ["before_build", "after_build", "before_execution", "after_execution"],
        "classpath_copy": "Physical temporary copies of main, test and Kotlin stdlib; removed after execution.",
    }
    manifest.write_text(json.dumps(result, indent=2) + "\n")
    if args.jfr:
        args.jfr.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(recording, args.jfr)
        if digest(args.jfr) != result["execution"]["jfr"]["sha256"]:
            parser.error("published JFR differs from the measured recording")
    if args.workload == "corpus":
        staged.rename(args.output)
        manifest = args.output / "manifest.json"
    else:
        for name in payloads:
            destination = args.output.parent / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(payload_root / name, destination)
        if any(digest(args.output.parent / name) != expected for name, expected in payloads.items()):
            parser.error("published benchmark payload differs from the measured output")
        manifest.replace(args.output)
        manifest = args.output
    print(manifest)
