#!/usr/bin/env python3
"""Run both frozen desktop differentials from verified, immutable input copies."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile


SOURCE_PATHS = ("core", "scripts", "examples", "build.gradle.kts")


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def validate_revision(value, label):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError(f"{label} must be a full lowercase 40-character Git revision")


def validate_sha(value, label):
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError(f"{label} must be a lowercase SHA-256 digest")


def source_state(root, revision):
    actual = git(root, "rev-parse", "--verify", revision + "^{commit}")
    if actual != revision:
        raise ValueError("Mobile revision does not identify the declared commit")
    changed = git(root, "diff", "--name-only", revision, "--", *SOURCE_PATHS)
    if changed:
        raise ValueError("Source files differ from the mobile revision: " + changed.replace("\n", ", "))
    return {"root": str(root), "revision": revision, "head": git(root, "rev-parse", "HEAD"),
            "tree": git(root, "rev-parse", revision + "^{tree}"),
            "coreTree": git(root, "rev-parse", revision + ":core"),
            "otherTrackedChanges": git(root, "diff", "--name-only", "HEAD").splitlines()}


def archive_source(root, revision, destination):
    archive = subprocess.check_output(["git", "-C", str(root), "archive", "--format=tar", revision])
    destination.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as stream:
        for member in stream:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or not (member.isdir() or member.isfile()):
                raise ValueError(f"Unsafe source archive entry: {member.name}")
            target = destination.joinpath(*name.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with stream.extractfile(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(member.mode & 0o777)


def copy_jar(original, destination, expected):
    before = sha(original)
    if expected != before:
        raise ValueError(f"Unexpected JAR hash for {original}: {before}, expected {expected}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, destination)
    copied, after = sha(destination), sha(original)
    if before != copied or before != after:
        raise ValueError(f"JAR changed while copying: {original}")
    return {"original": str(original), "copy": str(destination), "expectedSha256": expected,
            "sha256Before": before, "sha256Copied": copied, "sha256AfterCopy": after}


def validate_provenance(source, desktop_revision, desktop_sha):
    paths = ("examples/catalog.json", "examples/corpus/reference/provenance.json",
             "examples/corpus/temporal/provenance.json")
    for relative in paths:
        record = json.loads((source / relative).read_text())
        revision = record.get("desktopRevision", record.get("desktop_revision"))
        if revision != desktop_revision:
            raise ValueError(f"Desktop revision mismatch in {relative}: {revision}")
        expected = record.get("desktopJarSha256", record.get("desktop_jar_sha256"))
        if expected is not None and expected != desktop_sha:
            raise ValueError(f"Desktop JAR provenance mismatch in {relative}")
    return {relative: sha(source / relative) for relative in paths}


def stdlib_path(source, explicit):
    if explicit:
        return explicit.resolve()
    if os.environ.get("KOTLIN_STDLIB_JAR"):
        return Path(os.environ["KOTLIN_STDLIB_JAR"]).resolve()
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"',
                        (source / "build.gradle.kts").read_text())
    if not version:
        raise ValueError("Cannot determine Kotlin stdlib version; pass --kotlin-stdlib-jar")
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    candidates = sorted((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" / version[1]).glob("*/*.jar"))
    if not candidates:
        raise ValueError("Kotlin stdlib is not cached; pass --kotlin-stdlib-jar. This tool never builds or downloads it.")
    if len({sha(path) for path in candidates}) != 1:
        raise ValueError("Ambiguous cached Kotlin stdlib; pass --kotlin-stdlib-jar")
    return candidates[0].resolve()


def input_hashes(source):
    return {path.relative_to(source).as_posix(): sha(path)
            for prefix in ("scripts", "examples") for path in sorted((source / prefix).rglob("*"))
            if path.is_file()}


def execute_oracle(name, source, desktop, output, environment):
    directory = output / name
    directory.mkdir()
    command = [sys.executable, str(source / "scripts" / ("verify-corpus.py" if name == "corpus" else "verify-temporal-oracle.py")),
               "--desktop-jar", str(desktop), "--skip-build", "--output", str(directory)]
    started = timestamp()
    result = subprocess.run(command, cwd=source, env=environment, text=True, capture_output=True)
    stdout, stderr = directory / "stdout.log", directory / "stderr.log"
    stdout.write_text(result.stdout)
    stderr.write_text(result.stderr)
    record = {"name": name, "argv": command, "cwd": str(source), "startedAt": started,
              "finishedAt": timestamp(), "exitCode": result.returncode,
              "stdout": str(stdout), "stderr": str(stderr),
              "stdoutSha256": sha(stdout), "stderrSha256": sha(stderr)}
    summary = directory / "summary.json"
    if summary.is_file():
        record["summary"] = {"path": str(summary), "sha256": sha(summary)}
        try:
            record["summary"]["data"] = json.loads(summary.read_text())
        except (OSError, ValueError) as error:
            record["summaryParseError"] = str(error)
    return record


def replay(args):
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Output must not exist; use a new directory to preserve previous receipts")
    output.mkdir(parents=True)
    receipt = {"schemaVersion": 1, "startedAt": timestamp(), "status": "preflight_failed",
               "scope": "Headless JVM circuit and temporal differentials only; no UI, device or hardware parity claim.",
               "tool": {"path": str(Path(__file__).resolve()), "sha256": sha(__file__), "python": sys.version},
               "commands": [], "requestedInputs": {
                   "core": {"path": str(args.core_jar.resolve()), "sha256": args.core_sha256},
                   "desktop": {"path": str(args.desktop_jar.resolve()), "sha256": args.desktop_sha256},
                   "mobileRevision": args.mobile_revision, "desktopRevision": args.desktop_revision}}
    jars = {}
    receipt["jars"] = jars
    try:
        validate_revision(args.mobile_revision, "--mobile-revision")
        validate_revision(args.desktop_revision, "--desktop-revision")
        validate_sha(args.core_sha256, "--core-sha256")
        validate_sha(args.desktop_sha256, "--desktop-sha256")
        root = args.source_root.resolve()
        receipt["sourceBefore"] = source_state(root, args.mobile_revision)
        source = output / "source"
        archive_source(root, args.mobile_revision, source)
        receipt["sourceInputs"] = input_hashes(source)
        receipt["provenance"] = validate_provenance(source, args.desktop_revision, args.desktop_sha256)
        jars["core"] = copy_jar(args.core_jar.resolve(), source / "core/build/libs/core-jvm.jar", args.core_sha256)
        jars["desktop"] = copy_jar(args.desktop_jar.resolve(), output / "inputs/desktop.jar", args.desktop_sha256)
        stdlib = stdlib_path(source, args.kotlin_stdlib_jar)
        jars["kotlinStdlib"] = copy_jar(stdlib, output / "inputs/kotlin-stdlib.jar", sha(stdlib))
        environment = os.environ.copy()
        environment["KOTLIN_STDLIB_JAR"] = jars["kotlinStdlib"]["copy"]
        receipt["environment"] = {"KOTLIN_STDLIB_JAR": environment["KOTLIN_STDLIB_JAR"]}
        receipt["status"] = "failed"
        for name in ("corpus", "temporal"):
            receipt["commands"].append(execute_oracle(name, source, Path(jars["desktop"]["copy"]), output, environment))
        receipt["sourceAfter"] = source_state(root, args.mobile_revision)
        receipt["archivedInputsAfter"] = input_hashes(source)
        if receipt["sourceInputs"] != receipt["archivedInputsAfter"]:
            raise ValueError("Archived scripts, fixtures or reference records changed during replay")
        if all(command["exitCode"] == 0 and "summaryParseError" not in command for command in receipt["commands"]):
            receipt["status"] = "passed"
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        receipt["error"] = str(error)
    finally:
        for record in jars.values():
            try:
                record["sha256After"] = sha(record["original"])
                record["copySha256After"] = sha(record["copy"])
                if record["sha256After"] != record["expectedSha256"] or record["copySha256After"] != record["expectedSha256"]:
                    raise ValueError("A JAR changed during replay: " + record["original"])
            except (OSError, ValueError) as error:
                receipt["status"] = "failed"
                receipt.setdefault("finalizationErrors", []).append(str(error))
        receipt["finishedAt"] = timestamp()
        (output / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--mobile-revision", required=True)
    parser.add_argument("--desktop-revision", required=True)
    parser.add_argument("--core-jar", type=Path, required=True)
    parser.add_argument("--desktop-jar", type=Path, required=True)
    parser.add_argument("--core-sha256", required=True)
    parser.add_argument("--desktop-sha256", required=True)
    parser.add_argument("--kotlin-stdlib-jar", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        receipt = replay(args)
    except (OSError, ValueError) as error:
        parser.exit(1, f"oracle replay: {error}\n")
    print(f"Oracle replay {receipt['status']}: {args.output.resolve() / 'receipt.json'}")
    if receipt.get("error"):
        print(receipt["error"], file=sys.stderr)
    return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
