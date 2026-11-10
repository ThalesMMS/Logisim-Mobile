#!/usr/bin/env python3
"""Replay source-only serialization evidence without platform operations."""
from pathlib import Path
import hashlib
import json
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
        require(Path(name).name == name and name not in expected, "Invalid evidence name")
        expected[name] = digest
        require(hashlib.sha256((folder / name).read_bytes()).hexdigest() == digest, "Changed evidence: " + name)
    require(set(expected) == {p.name for p in folder.iterdir() if p.is_file() and p.name != "SHA256SUMS"}, "Evidence inventory changed")
    receipt = json.loads((folder / "preparation.json").read_text())
    prepared = json.loads((folder / "prepare-results.json").read_text())
    require(receipt["scope"] == "SOURCE_PREPARATION_ONLY" and prepared["status"] == "PREPARED", "Source preparation only")
    require(prepared["native_execution_gate"] == prepared["test_execution_gate"] == "NATIVE_NOT_RUN" and
            prepared["qualification_gate"] == "NOT_QUALIFIED", "No Native qualification")
    require(prepared["rootLease"] is None and prepared["configuration"]["rootLease"] is None and
            prepared["configuration"]["simulatorUDID"] is None, "No runtime lease or device")
    require(not prepared["harness"]["dirty"] and prepared["harness"]["commit"] == receipt["sourceCommit"], "Clean source pin")
    sources = dict(prepared["harness"]["sources"])
    checks = json.loads((folder / "checks.json").read_text())
    sources.update(checks["sourceSHA256"])
    for name, digest in sources.items():
        actual = subprocess.check_output(["git", "-C", str(repository), "show", receipt["sourceCommit"] + ":" + name])
        require(hashlib.sha256(actual).hexdigest() == digest, "Source differs: " + name)
    require(len(prepared["staged"]["sources"]) == 6 and len(prepared["staged"]["resources"]) == 5, "Staging contract")
    commands = json.loads((folder / "prepare-commands.json").read_text())
    require([entry["arguments"][0] for entry in commands] == ["codesign"] * 6 + ["xcodegen"], "Prepare must not invoke Native tools")
    for entry in commands:
        require(entry["exitCode"] == 0, "Prepare command failed")
        for stream in ("stdout", "stderr"):
            evidence = entry[stream]
            data = (folder / evidence["file"]).read_bytes()
            require(len(data) == evidence["bytes"] and hashlib.sha256(data).hexdigest() == evidence["sha256"], "Command bytes differ")
    require(all(entry["exitCode"] == 0 for entry in checks["checks"]), "A source check failed")
    python = (folder / "python-tests.stderr").read_text()
    require("Ran 30 tests" in python and python.rstrip().endswith("OK"), "Expected complete Python suite")
    require(len(receipt["checks"]["sdkSourceSHA256"]) == 6 and all(
        prepared["harness"]["sources"][name] == digest for name, digest in receipt["checks"]["sdkSourceSHA256"].items()), "SDK source drift")
    require(receipt["buildSerialization"]["wrapperExecutedDuringChecks"] is False and
            receipt["buildSerialization"]["realLockAccessedDuringChecks"] is False, "No real serialization operation")
    print(json.dumps({"status": "PASS", "scope": "SOURCE_PREPARATION_ONLY", "nativeAuthentication": "NOT_RUN",
                      "sourceCommit": receipt["sourceCommit"], "pythonTests": 30, "evidenceFiles": len(expected)}, indent=2))


if __name__ == "__main__":
    main()
