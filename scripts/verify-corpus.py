#!/usr/bin/env python3
"""Compare example values, errors, transitions, ports and exported files with Logisim."""
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


def run(command, **kwargs):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, command))}\n{result.stderr}")
    return result.stdout


def operations(example):
    rows = [f"P\t{p['id']}\t{p['x']}\t{p['y']}\t{p['width']}" for p in example["probes"]]
    for visible, actions in ((False, example.get("startup", [])), (True, example["actions"])):
        for action in actions:
            rows.append(f"A\t{action['name']}\t{action.get('ticks', 0)}")
            rows.extend(f"I\t{k}\t{v}" for k, v in action.get("inputs", {}).items())
            rows.append(f"F\t{int(visible)}")
    return "\n".join(rows) + "\n"


def records(text):
    result = {}
    revision = None
    for line in text.splitlines():
        fields = line.split("\t")
        if fields[0] == "M" and fields[1] == "revision":
            revision = fields[2]
        elif fields[0] in {"A", "F", "G", "V", "W"}:
            key = "\t".join(fields[:-1])
            if key in result:
                raise ValueError(f"Duplicate record {key}")
            result[key] = fields[-1]
    if not result:
        raise ValueError("Runner produced no corpus records")
    return result, revision


def differences(expected, actual):
    return [{"key": key, "desktop": expected.get(key), "mobile": actual.get(key)}
            for key in sorted(expected.keys() | actual.keys()) if expected.get(key) != actual.get(key)]


ORIGINAL_VERSION = "2.7.1"
ORIGINAL_FIXTURES = ROOT / "examples/compatibility/original-2.7.1"


def verify_original(args, jar, mobile, stdlib):
    provenance_path = ORIGINAL_FIXTURES / "provenance.json"
    provenance = {
        "release": ORIGINAL_VERSION,
        "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "fixtures": {},
    }
    if not args.update_reference:
        saved = json.loads(provenance_path.read_text())
        if saved["jar_sha256"] != provenance["jar_sha256"]:
            raise ValueError("Expected the official Logisim 2.7.1 JAR recorded in provenance.json")
    reports = []
    pending_references = {}
    with tempfile.TemporaryDirectory(prefix="logisim-original-corpus-") as directory:
        classes = Path(directory)
        jvm = ["java", "-Djava.awt.headless=true", f"-Djava.util.prefs.userRoot={classes / 'prefs'}"]
        run(["javac", "-cp", str(jar), "-d", str(classes), "scripts/oracle/OriginalCircuitCorpus.java"])
        original = jvm + ["-cp", f"{classes}{os.pathsep}{jar}", "OriginalCircuitCorpus"]
        mobile_cli = jvm + ["-cp", f"{mobile}{os.pathsep}{stdlib}", "dev.logisim.mobile.core.CircuitCorpusCli", "--fixture"]
        for fixture in sorted(ORIGINAL_FIXTURES.glob("*.circ")):
            case = args.output / fixture.stem
            case.mkdir(exist_ok=True)
            ops = fixture.with_suffix(".ops.tsv")
            export = case / "mobile.circ"
            reference = fixture.with_suffix(".original.tsv")
            original_text = run(original + [str(fixture), str(ops)], timeout=30)
            expected, version = records(original_text)
            if version != ORIGINAL_VERSION or not any(key.startswith("V\t") for key in expected):
                raise ValueError(f"Invalid original baseline for {fixture.name}")
            provenance["fixtures"][fixture.name] = {
                "circuit_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
                "operations_sha256": hashlib.sha256(ops.read_bytes()).hexdigest(),
                "records": len(expected),
            }
            if args.update_reference:
                pending_references[reference] = "\n".join(sorted(original_text.splitlines())) + "\n"
            elif records(reference.read_text()) != (expected, version):
                raise ValueError(f"Original observations changed for {fixture.name}")
            comparisons = {}
            migrations = {}
            documents = (("mobile", fixture, export), ("mobile-reopened", export, case / "mobile-reopened.circ"))
            for name, document, destination in documents:
                observed = run(mobile_cli + [str(document), str(ops), str(destination)], timeout=30)
                (case / f"{name}.tsv").write_text(observed)
                comparisons[name] = differences(expected, records(observed)[0])
                migrations[name] = [line.split("\t")[1:] for line in observed.splitlines() if line.startswith("D\t")]
                rules = {migration[0] for migration in migrations[name]}
                if rules != {"version-defaults"}:
                    comparisons[name].append({"expected_migrations": ["version-defaults"], "observed_migrations": sorted(rules)})
            try:
                reopened = run(original + [str(export), str(ops)], timeout=30)
                (case / "original-reopened.tsv").write_text(reopened)
                observed, reopened_version = records(reopened)
                if reopened_version != version:
                    raise ValueError(f"Original reopen reported revision {reopened_version!r}, expected {version!r}")
                compatibility = {"differences": differences(expected, observed)}
                reopen_status = "differs" if compatibility["differences"] else "matches"
            except (RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
                compatibility = {"error": str(error)}
                reopen_status = "failed"
            reports.append({"id": fixture.stem, "records": len(expected), "mobile": comparisons,
                            "mobile_import_migrations": migrations,
                            "original_export_compatibility": compatibility})
            print(f"{fixture.stem}: {len(expected)} records, "
                  f"{sum(map(len, comparisons.values()))} mobile differences, "
                  f"original export {reopen_status}")
    if not reports:
        raise ValueError("Original corpus contains no fixtures")
    (args.output / "summary.json").write_text(json.dumps(reports, indent=2) + "\n")
    if any(any(report["mobile"].values()) for report in reports):
        raise SystemExit("Mobile behavior differs from the original 2.7.1. See summary.json.")
    if any(report["original_export_compatibility"] != {"differences": []} for report in reports):
        raise SystemExit("Original 2.7.1 could not reopen mobile exports without differences. See summary.json.")
    if args.update_reference:
        provenance_path.write_text(json.dumps(provenance, indent=2) + "\n")
        for path, content in pending_references.items():
            path.write_text(content)
    elif saved != provenance:
        raise ValueError("Original fixture provenance changed")
    print(f"PASS: {len(reports)} original 2.7.1 fixtures and {sum(r['records'] for r in reports)} records")



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    baseline = parser.add_mutually_exclusive_group(required=True)
    baseline.add_argument("--desktop-jar", type=Path)
    baseline.add_argument("--original-jar", type=Path)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--update-reference", action="store_true", help="Replace checked-in desktop records after reviewing fixture changes")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.desktop_jar:
        run([sys.executable, "scripts/generate_examples.py", "--check"])
    if not args.skip_build:
        subprocess.run(["./gradlew", ":core:jvmJar", "--console=plain"], cwd=ROOT, check=True)
    mobile = ROOT / "core/build/libs/core-jvm.jar"
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text())[1]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    stdlib = Path(os.environ["KOTLIN_STDLIB_JAR"]) if "KOTLIN_STDLIB_JAR" in os.environ else next(
        (cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version).glob("*/*.jar"))
    default_output = "original-corpus-validation" if args.original_jar else "corpus-validation"
    args.output = (args.output or ROOT / "outputs" / default_output).resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.original_jar:
        verify_original(args, args.original_jar.resolve(), mobile, stdlib)
        return
    desktop = args.desktop_jar.resolve()
    catalog = json.loads((ROOT / "examples/catalog.json").read_text())
    reports = []
    reference = ROOT / "examples/corpus/reference"
    pending_references = {}
    provenance_path = reference / "provenance.json"
    provenance = json.loads(provenance_path.read_text()) if provenance_path.is_file() else {}
    if args.update_reference:
        reference.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="logisim-corpus-") as temporary:
        classes = Path(temporary)
        run(["javac", "-cp", str(desktop), "-d", str(classes), "scripts/oracle/CircuitCorpusDesktop.java"])
        desktop_command = ["java", "-Djava.awt.headless=true", "-cp", f"{classes}{os.pathsep}{desktop}", "CircuitCorpusDesktop"]
        mobile_command = ["java", "-cp", f"{mobile}{os.pathsep}{stdlib}", "dev.logisim.mobile.core.CircuitCorpusCli"]
        for example in catalog["examples"]:
            directory = args.output / example["id"]
            directory.mkdir(exist_ok=True)
            script = directory / "operations.tsv"
            script.write_text(operations(example))
            exported = directory / "exported.circ"
            original_text = run(desktop_command + [str(ROOT / example["path"]), str(script)])
            mobile_text = run(mobile_command + [example["id"], str(exported)])
            roundtrip_text = run(desktop_command + [str(exported), str(script)])
            (directory / "desktop.tsv").write_text(original_text)
            (directory / "mobile.tsv").write_text(mobile_text)
            (directory / "desktop-roundtrip.tsv").write_text(roundtrip_text)
            original, revision = records(original_text)
            actual, _ = records(mobile_text)
            roundtrip, roundtrip_revision = records(roundtrip_text)
            if not catalog["desktopRevision"].startswith(revision or "missing") or revision != roundtrip_revision:
                raise ValueError(f"Desktop revision is {revision}, expected {catalog['desktopRevision']}")
            failed = differences(original, actual) + differences(original, roundtrip)
            golden = reference / (example["id"] + ".tsv")
            if args.update_reference:
                pending_references[golden] = original_text
            elif not golden.is_file():
                failed.append({"key": "reference", "desktop": "missing checked-in desktop record"})
            else:
                recorded, recorded_revision = records(golden.read_text())
                failed += differences(recorded, original)
                if recorded_revision != revision:
                    failed.append({"key": "reference-revision", "recorded": recorded_revision, "desktop": revision})
                fixture_hash = hashlib.sha256((ROOT / example["path"]).read_bytes()).hexdigest()
                if provenance.get("fixtures", {}).get(example["path"]) != fixture_hash:
                    failed.append({"key": "reference-fixture-hash", "fixture": example["path"]})
            for action in example["actions"]:
                for probe, value in action.get("expected", {}).items():
                    key = f"V\t{action['name']}\t{probe}"
                    if original.get(key) != value:
                        failed.append({"key": key, "declared": value, "desktop": original.get(key)})
            report = {"id": example["id"], "records": len(original), "differences": failed}
            reports.append(report)
            print(f"{example['id']}: {len(original)} records, {len(failed)} differences")
    summary = {"desktopRevision": catalog["desktopRevision"],
               "sha256": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (desktop, mobile, ROOT / "examples/catalog.json")},
               "examples": reports, "records": sum(r["records"] for r in reports),
               "differences": sum(len(r["differences"]) for r in reports)}
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    if summary["differences"]:
        raise SystemExit("Corpus differs from the pinned desktop; see summary.json")
    if args.update_reference:
        provenance = {"desktopRevision": catalog["desktopRevision"],
                      "desktopJarSha256": hashlib.sha256(desktop.read_bytes()).hexdigest(),
                      "fixtures": {e["path"]: hashlib.sha256((ROOT / e["path"]).read_bytes()).hexdigest()
                                   for e in catalog["examples"]},
                      "records": summary["records"], "command": "python3 scripts/verify-corpus.py --desktop-jar DESKTOP_JAR --update-reference"}
        (reference / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        for path, text in pending_references.items():
            path.write_text(text)
    print(f"PASS: {len(reports)} fixtures, {summary['records']} records, original and exported projects match")


if __name__ == "__main__":
    main()
