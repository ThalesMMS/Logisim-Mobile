#!/usr/bin/env python3
"""Verify this source-preparation receipt without invoking Apple platform tools."""
from pathlib import Path
import hashlib
import json
import re
import subprocess


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    folder = Path(__file__).resolve().parent
    repository = folder.parents[2]
    expected = {}
    for line in (folder / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        require(re.fullmatch(r"[a-f0-9]{64}", digest) is not None and Path(name).name == name, "Invalid checksum entry")
        require(name not in expected, "Duplicate checksum entry")
        expected[name] = digest
        require(hashlib.sha256((folder / name).read_bytes()).hexdigest() == digest, "Evidence bytes changed: " + name)
    require(set(expected) == {path.name for path in folder.iterdir() if path.is_file() and path.name != "SHA256SUMS"},
            "Unlisted or absent evidence")
    receipt = json.loads((folder / "preparation.json").read_text())
    prepared = json.loads((folder / "prepare-results.json").read_text())
    require(receipt["scope"] == "SOURCE_PREPARATION_ONLY", "This is not a Native receipt")
    require(prepared["status"] == "PREPARED" and prepared["native_execution_gate"] == "NATIVE_NOT_RUN" and
            prepared["test_execution_gate"] == "NATIVE_NOT_RUN" and prepared["qualification_gate"] == "NOT_QUALIFIED", "Preparation gates changed")
    require(prepared["rootLease"] is None and prepared["configuration"]["rootLease"] is None and
            prepared["configuration"]["simulatorUDID"] is None, "No Native lease or device may be inferred")
    require(prepared["harness"]["commit"] == receipt["sourceCommit"] and prepared["harness"]["dirty"] is False,
            "The clean source pin must agree")
    for name, digest in prepared["harness"]["sources"].items():
        content = subprocess.check_output(["git", "-C", str(repository), "show", receipt["sourceCommit"] + ":" + name])
        require(hashlib.sha256(content).hexdigest() == digest, "Pinned source bytes differ: " + name)
    require(len(prepared["staged"]["sources"]) == 6 and len(prepared["staged"]["resources"]) == 5, "Staging contract changed")
    commands = json.loads((folder / "prepare-commands.json").read_text())
    require([entry["arguments"][0] for entry in commands] == ["codesign"] * 6 + ["xcodegen"], "Unexpected prepare command")
    for entry in commands:
        require(entry["exitCode"] == 0, "A prepare command failed")
        for stream in ("stdout", "stderr"):
            evidence = entry[stream]
            content = (folder / evidence["file"]).read_bytes()
            require(len(content) == evidence["bytes"] and hashlib.sha256(content).hexdigest() == evidence["sha256"],
                    "Command evidence changed")
    oracle = json.loads((folder / "oracle-checks.json").read_text())
    require(oracle["passedCount"] == 36 and oracle["failedCount"] == 0 and oracle["failures"] == [], "Pure oracle checks failed")
    require("Ran 24 tests" in (folder / "python-tests.stderr").read_text() and
            (folder / "python-tests.stderr").read_text().rstrip().endswith("OK"), "Python gate is not the complete passing suite")
    sdk = json.loads((folder / "sdk-typecheck.json").read_text())
    require(sdk["exitCode"] == 0 and len(sdk["sourceSHA256"]) == 6, "SDK typecheck failed")
    for name, digest in sdk["sourceSHA256"].items():
        require(prepared["harness"]["sources"][name] == digest, "SDK checked different source bytes")
    print(json.dumps({"status": "PASS", "scope": "SOURCE_PREPARATION_ONLY", "nativeAuthentication": "NOT_RUN",
                      "sourceCommit": receipt["sourceCommit"], "evidenceFiles": len(expected)}, indent=2))


if __name__ == "__main__":
    main()
