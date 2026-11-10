#!/usr/bin/env python3
"""Verify this portable failed-build archive without opening its origin paths."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys

SOURCE = "e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630"
PRODUCER = "b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e"
BUILD_FILES = ("artifact.json build-test-only.py excluded-task-graph.log frozen-b06-builds-before.json "
               "guard-build-inputs-compileE2eAndroidTestKotlin.json guard-build-resolved-artifacts.json "
               "guard-build-task-graph.json guard-dry-run-resolved-artifacts.json guard-dry-run-task-graph.json "
               "post-failure-test-class-readback.json reused-producer-inputs-after.json "
               "reused-producer-inputs-before.json test-apk-build.log test-only-guard.gradle").split()
EXPECTED = {"failed-build/" + name for name in BUILD_FILES} | {
    "initial-graph/manifest.json", "initial-graph/task-graph.log", "initial-graph/reuse-preflight.json",
    "source-review/provider-completion-review.json"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(root):
    root = Path(root)
    index = json.loads((root / "index.json").read_bytes())
    require(index["status"] == "FAILED_BUILD_ARCHIVE" and index["nativeStatus"] == "NOT_RUN", "Archive outcome changed")
    require(index["source"] == SOURCE and index["producer"] == PRODUCER, "Source identity changed")
    require(set(index["files"]) == EXPECTED, "Archive input inventory changed")
    raw = {}
    archived_paths = set()
    for logical, entry in index["files"].items():
        zipped = logical.endswith(".json") and entry["rawBytes"] > 65536
        relative = PurePosixPath(entry["archive"])
        require(relative.as_posix() == "raw/" + logical + (".gz" if zipped else ""), "Archive path changed")
        require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe archive path")
        path = root.joinpath(*relative.parts)
        current = root
        require(not current.is_symlink(), "Symlink archive root")
        for part in relative.parts:
            current = current / part
            require(not current.is_symlink(), "Symlink in archive")
        archived = path.read_bytes()
        require(len(archived) == entry["archiveBytes"] and sha(archived) == entry["archiveSha256"], "Archived byte hash changed: " + logical)
        require(entry["encoding"] == ("gzip" if zipped else "identity"), "Compression policy changed")
        require(0 <= entry["rawBytes"] <= 8 * 1024 * 1024, "Raw entry exceeds archive bounds")
        if zipped:
            require(archived[:2] == b"\x1f\x8b" and archived[4:8] == b"\0" * 4 and not archived[3] & 8, "Gzip metadata changed")
            with gzip.GzipFile(fileobj=io.BytesIO(archived)) as stream:
                value = stream.read(entry["rawBytes"] + 1)
        else:
            value = archived
        require(len(value) == entry["rawBytes"] and sha(value) == entry["rawSha256"], "Raw byte hash changed: " + logical)
        raw[logical] = value
        archived_paths.add(path.relative_to(root).as_posix())
    actual_paths = {path.relative_to(root).as_posix() for path in (root / "raw").rglob("*") if path.is_file()}
    require(actual_paths == archived_paths, "Unindexed raw file present")
    data = lambda name: json.loads(raw[name])
    artifact = data("failed-build/artifact.json")
    require(artifact["source"] == SOURCE and artifact["frozenProducer"] == PRODUCER, "Build identities changed")
    require(artifact["status"] == "FAILED_BUILD" and artifact["nativeStatus"] == "NOT_RUN", "Failed build was reclassified")
    require(artifact["buildProcessExitCode"] == 1 and artifact["appRebuilt"] is False and artifact["helperRebuilt"] is False, "Build outcome changed")
    require(artifact["frozenOriginalBuildsUnchanged"] is True and artifact["reusedProducerInputsUnchanged"] is True, "Recorded preservation gate changed")
    require(len(artifact["productionInputsBefore"]) == 312, "Production source inventory changed")
    reuse = data("initial-graph/reuse-preflight.json")
    require(reuse["status"] == "PROPOSED_REUSE_NOT_PERFORMED" and reuse["copyPerformed"] is False and reuse["buildExecuted"] is False, "Initial preflight was reclassified")
    source_fields = ("path", "bytes", "sha256", "gitBlob")
    require([{key: row[key] for key in source_fields} for row in reuse["productionInputs"]] == artifact["productionInputsBefore"]
            and all(row["matchesB06Blob"] is True and row["matchesB06File"] is True for row in reuse["productionInputs"]), "Production source identities differ")
    require(artifact["driverSha256"] == sha(raw["failed-build/build-test-only.py"])
            and artifact["guardSha256"] == sha(raw["failed-build/test-only-guard.gradle"]), "Driver or guard identity changed")
    excluded, allowed = set(artifact["excludedProducerTasks"]), set(artifact["allowedTestTasks"])
    require(len(excluded) == 63 and len(allowed) == 37 and not excluded & allowed, "Task partition changed")
    graph_tasks = set(re.findall(r"(?m)^(:\S+) SKIPPED$", raw["initial-graph/task-graph.log"].decode()))
    require(len(graph_tasks) == 100 and graph_tasks == excluded | allowed, "Initial 100-task graph changed")
    graph = data("initial-graph/manifest.json")
    require(graph["status"] == "DRY_RUN_PASS" and graph["exitCode"] == 0 and graph["source"] == SOURCE, "Initial graph outcome changed")
    require(graph["logSha256"] == sha(raw["initial-graph/task-graph.log"]), "Initial graph log binding changed")
    for phase in ("build", "dry-run"):
        guard = data("failed-build/guard-" + phase + "-task-graph.json")
        require(guard["status"] == "ADMITTED" and set(guard["tasks"]) == allowed and not guard["unexpected"] and not guard["missing"], "Guarded 37-task graph changed")
    commands = artifact["commands"]
    require(len(commands) == 2 and [c["exitCode"] for c in commands] == [0, 1], "Attempt sequence changed")
    require(round(commands[1]["elapsedSeconds"], 3) == 14.210, "Failed-build duration changed")
    for command in commands:
        require(command["logSha256"] == sha(raw["failed-build/" + command["log"]]), "Command log binding changed")
    log = raw["failed-build/test-apk-build.log"].decode()
    require("friendPathsSet$kotlin_gradle_plugin_common" in log and "before task ':androidApp:bundleE2eClassesToCompileJar' has completed" in log, "Original failure phase changed")
    tasks = re.findall(r"(?m)^> Task (:\S+)(?: (.*))?$", log)
    require(sum(not state for _, state in tasks) == 10 and [(name, state) for name, state in tasks if state == "FAILED"] == [(":androidApp:compileE2eAndroidTestKotlin", "FAILED")], "Executed task outcome changed")
    require("BUILD FAILED" in log and "BUILD SUCCESSFUL" not in log, "Build log was reclassified")
    frozen = data("failed-build/frozen-b06-builds-before.json")
    require(sum(map(len, frozen.values())) == 11985, "Frozen original inventory changed")
    require(all(c["contentEqual"] is True and c["distinctInodes"] is True for c in artifact["copies"].values()), "Recorded physical-copy gate changed")
    before = raw["failed-build/reused-producer-inputs-before.json"]
    require(before == raw["failed-build/reused-producer-inputs-after.json"] and sum(map(len, json.loads(before).values())) == 11448, "Copied production input identities changed")
    readback = data("failed-build/post-failure-test-class-readback.json")
    require(readback["newTestApkPresent"] is False and set(readback["newCollectorClassesPresent"].values()) == {False}, "New artifacts were falsely attributed")
    classes = readback["testClassDirectories"]
    require(classes["kotlin"]["filesBefore"] == 22 and classes["kotlin"]["filesAfter"] == 0, "Kotlin output census changed")
    require(classes["java"]["filesBefore"] == classes["java"]["filesAfter"] == 34 and classes["java"]["byteIdenticalToB06"] is True, "Old Java output census changed")
    review = data("source-review/provider-completion-review.json")
    require(review["status"] == "SOURCE_REVIEW_ONLY" and review["originalFailureLog"]["sha256"] == sha(raw["failed-build/test-apk-build.log"]), "Source review was reattributed")
    return len(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    try:
        count = verify(args.archive)
    except (KeyError, ValueError, OSError, TypeError, EOFError) as error:
        parser.exit(1, "INVALID_ARCHIVE: " + str(error) + "\n")
    print(f"VERIFIED_ARCHIVE_ONLY files={count} build=FAILED_BUILD native=NOT_RUN")


if __name__ == "__main__":
    main()
