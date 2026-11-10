#!/usr/bin/env python3
"""Export ComponentCatalog.registry from a hash-verified JVM artifact, without Gradle."""

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

ROOT = Path(__file__).resolve().parents[1]
CORE_SOURCES = "core/src/commonMain/kotlin/dev/logisim/mobile/core/"
STRUCTURAL_SOURCES = ("ComponentCatalog.kt", "ComponentRegistry.kt", "ComponentAttributes.kt")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str], *, cwd: Path, timeout: int = 60) -> str:
    result = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", capture_output=True, timeout=timeout)
    if result.returncode:
        raise ValueError(f"Command failed ({result.returncode}): {command[0]}\n{result.stderr.strip()}")
    return result.stdout


def verified_copy(source: Path, destination: Path, expected: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Expected SHA-256 must contain 64 lowercase hexadecimal characters")
    shutil.copyfile(source, destination)
    actual = sha256(destination)
    if actual != expected:
        raise ValueError(f"Core JAR SHA-256 mismatch: expected {expected}, copied {actual}")


def kotlin_stdlib(source_root: Path, revision: str) -> Path:
    override = os.environ.get("KOTLIN_STDLIB_JAR")
    if override:
        return Path(override).resolve(strict=True)
    build = run(["git", "show", f"{revision}:build.gradle.kts"], cwd=source_root)
    version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', build)
    if not version:
        raise ValueError("Cannot determine the Kotlin version from the declared source revision")
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    candidates = sorted((cache / "caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib"
                         / version[1]).glob("*/*.jar"))
    if len(candidates) != 1:
        raise ValueError("Set KOTLIN_STDLIB_JAR to the artifact's Kotlin standard library")
    return candidates[0].resolve()


def registry_sources(snapshot: dict, source_root: Path, revision: str) -> list[dict]:
    owners = {owner.rsplit(".", 1)[-1] for entry in snapshot["definitions"] for owner in entry["metadataOwners"]}
    owners.update(alias["symbol"].split(".", 1)[0] for alias in snapshot["kindAliases"])
    sources = set(STRUCTURAL_SOURCES)
    found = set()
    for path in (source_root / CORE_SOURCES).glob("*.kt"):
        text = path.read_text(encoding="utf-8")
        declarations = set(re.findall(r"\b(?:class|object|interface)\s+([A-Za-z_][A-Za-z_0-9]*)", text))
        matches = {owner for owner in owners if owner in declarations or owner == path.stem + "Kt"}
        if matches:
            sources.add(path.name)
            found.update(matches)
    missing = owners - found
    if missing:
        raise ValueError("Cannot bind metadata owners to current core source: " + ", ".join(sorted(missing)))
    result = []
    for name in sorted(sources):
        relative = CORE_SOURCES + name
        content = (source_root / relative).read_bytes()
        # The receipt declares a source revision; reject a mixed working tree at the registry boundary.
        recorded = subprocess.run(["git", "show", f"{revision}:{relative}"], cwd=source_root, capture_output=True)
        if recorded.returncode or recorded.stdout != content:
            raise ValueError(f"Registry source differs from declared revision: {relative}")
        result.append({"path": relative, "sha256": hashlib.sha256(content).hexdigest()})
    return result


def write_or_check(snapshot: dict, output: Path, check: bool) -> None:
    text = json.dumps(snapshot, indent=2, ensure_ascii=False) + "\n"
    if check:
        if not output.is_file() or output.read_text(encoding="utf-8") != text:
            raise ValueError(f"Registry snapshot is stale: {output}")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")


def export(args: argparse.Namespace) -> tuple[dict, dict]:
    source_root = args.source_root.resolve()
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_revision):
        raise ValueError("Source revision must be a complete 40-character lowercase Git commit")
    revision = run(["git", "rev-parse", "--verify", f"{args.source_revision}^{{commit}}"], cwd=source_root).strip()
    source_tree = run(["git", "rev-parse", f"{revision}:core/src/commonMain"], cwd=source_root).strip()
    bridge = Path(__file__).with_name("RegistryInventory.java")
    stdlib = args.stdlib_jar.resolve(strict=True) if args.stdlib_jar else kotlin_stdlib(source_root, revision)
    with tempfile.TemporaryDirectory(prefix="logisim-registry-") as temporary:
        directory = Path(temporary)
        copied = directory / "core-jvm.jar"
        verified_copy(args.core_jar, copied, args.expected_sha256)
        run([args.javac, "-encoding", "UTF-8", "-d", str(directory), str(bridge)], cwd=source_root)
        classpath = os.pathsep.join(map(str, (directory, copied, stdlib)))
        snapshot = json.loads(run([args.java, "-cp", classpath, "RegistryInventory"], cwd=source_root))
        if snapshot.get("schema_version") != 1 or not snapshot.get("definitions"):
            raise ValueError("Registry bridge returned an empty or unsupported snapshot")
        snapshot["registrySources"] = registry_sources(snapshot, source_root, revision)
        receipt = {
            "schema_version": 1,
            "declaredSourceRevision": revision,
            "coreCommonMainTree": source_tree,
            "coreJarSha256": sha256(copied),
            "kotlinStdlibSha256": sha256(stdlib),
            "exporterSha256": sha256(Path(__file__)),
            "bridgeSha256": sha256(bridge),
            "definitionCount": len(snapshot["definitions"]),
            "scope": "Public registry metadata and source bindings. Registration does not establish behavioral, UI or target parity.",
            "sourceAttestation": "The caller declares the artifact's producing revision; source hashes are verified against that Git commit.",
            "javaVersion": run([args.java, "--version"], cwd=source_root).strip(),
        }
    write_or_check(snapshot, args.output, args.check)
    receipt["snapshotSha256"] = sha256(args.output)
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return snapshot, receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-jar", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, default=ROOT / "parity/registry/mobile.json")
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--stdlib-jar", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    try:
        snapshot, receipt = export(args)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        return 1
    mode = "Verified" if args.check else "Exported"
    print(f"{mode} {len(snapshot['definitions'])} registry definitions; artifact {receipt['coreJarSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
