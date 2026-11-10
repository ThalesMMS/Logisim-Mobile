#!/usr/bin/env python3
"""Verify build-only evidence; never install, build, or execute the copied inputs."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re

SOURCE = "e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630"
PRODUCER = "b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e"
PRIOR_FAILURE = "1412dc22a5afee6a92ca68aad72ef8bd5878ecf4"
MARKER = ":androidApp:bundleE2eClassesToCompileJar"
JAR_SHA = "2b34b6c3fc3eddf347456d9c77cbd30114a7af0771f475416a84d9edd4c64656"
TEST_SHA = "33b888b38368b6ea56bc512bcef7e5c1d20d62fb8ab461e7f8da457800ba962a"
SIGNER = "09611b1599fe1fd47863a36cc34a1cab942e5beabbfc2f4ddcd8e9ed7b06d56e"
PACKAGE = "thalesmms.logisim.mobile.e2e.test"
TARGET = "thalesmms.logisim.mobile.e2e"
RUNNER = "dev.logisim.mobile.documents.DocumentIntegrationInstrumentation"
EXPECTED = set(("artifact.json build-test-only.py compiler-inputs-postcheck.json excluded-task-graph.log "
    "frozen-b06-builds-before.json guard-build-disabled-producer-marker-state.json "
    "guard-build-inputs-compileE2eAndroidTestJavaWithJavac.json guard-build-inputs-compileE2eAndroidTestKotlin.json "
    "guard-build-resolved-artifacts.json guard-build-task-graph.json guard-dry-run-resolved-artifacts.json "
    "guard-dry-run-task-graph.json new-test-classes.json reused-app-signature.log reused-producer-inputs-after.json "
    "reused-producer-inputs-before.json test-apk-badging.log test-apk-build.log test-apk-manifest.log "
    "test-apk-signature.log test-only-guard.gradle").split())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def verify(root, local_artifacts=False):
    root = Path(root)
    index = json.loads((root / "index.json").read_bytes())
    require(index["status"] == "TEST_APK_BUILD_PASS_ARCHIVE" and index["nativeStatus"] == "NOT_RUN", "Archive outcome changed")
    require(index["source"] == SOURCE and index["frozenProducer"] == PRODUCER and index["priorFailureCommit"] == PRIOR_FAILURE, "Archive lineage changed")
    require(index["qualification"] == "BUILD_ONLY_FROZEN_PRODUCTION_INPUTS", "Build was promoted beyond its scope")
    require(set(index["files"]) == EXPECTED, "Raw inventory changed")
    raw, paths = {}, set()
    for logical, entry in index["files"].items():
        zipped = logical.endswith(".json") and entry["rawBytes"] > 65536
        relative = PurePosixPath(entry["archive"])
        require(relative.as_posix() == "raw/" + logical + (".gz" if zipped else ""), "Raw path changed")
        require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe raw path")
        path = root
        require(not path.is_symlink(), "Symlink archive root")
        for part in relative.parts:
            path = path / part
            require(not path.is_symlink(), "Symlink raw input")
        archived = path.read_bytes()
        require(len(archived) == entry["archiveBytes"] and sha(archived) == entry["archiveSha256"], "Archived hash changed: " + logical)
        require(entry["encoding"] == ("gzip" if zipped else "identity") and 0 <= entry["rawBytes"] <= 8 * 1024 * 1024, "Encoding or size changed")
        if zipped:
            require(archived[:2] == b"\x1f\x8b" and archived[4:8] == b"\0" * 4 and not archived[3] & 8, "Gzip metadata changed")
            with gzip.GzipFile(fileobj=io.BytesIO(archived)) as stream:
                value = stream.read(entry["rawBytes"] + 1)
        else:
            value = archived
        require(len(value) == entry["rawBytes"] and sha(value) == entry["rawSha256"], "Raw hash changed: " + logical)
        raw[logical] = value
        paths.add(relative.as_posix())
    require(paths == {p.relative_to(root).as_posix() for p in (root / "raw").rglob("*") if p.is_file()}, "Unindexed raw file")
    data = lambda name: json.loads(raw[name])
    artifact = data("artifact.json")
    require(artifact["status"] == "TEST_APK_BUILD_PASS" and artifact["nativeStatus"] == "NOT_RUN"
            and artifact["qualification"] == "BUILD_ONLY_FROZEN_PRODUCTION_INPUTS", "Recorded build scope changed")
    require(artifact["source"] == SOURCE and artifact["frozenProducer"] == PRODUCER and artifact["priorFailureCommit"] == PRIOR_FAILURE, "Build lineage changed")
    require(artifact["buildProcessExitCode"] == 0 and artifact["appRebuilt"] is False and artifact["helperRebuilt"] is False, "Production rebuild was attributed")
    require(artifact["frozenOriginalBuildsUnchanged"] is True and artifact["reusedProducerInputsUnchanged"] is True, "Preservation gate changed")
    require(len(artifact["productionInputsBefore"]) == 312 and artifact["producerCopyFilesRevalidated"] == 11448, "Production inventory changed")
    require(sum(map(len, data("frozen-b06-builds-before.json").values())) == 11985, "Frozen inventory changed")
    require(raw["reused-producer-inputs-before.json"] == raw["reused-producer-inputs-after.json"]
            and sum(map(len, data("reused-producer-inputs-before.json").values())) == 11448, "Copied production bytes changed")
    require(artifact["driverSha256"] == sha(raw["build-test-only.py"]) and artifact["guardSha256"] == sha(raw["test-only-guard.gradle"]), "Driver or guard binding changed")
    allowed, excluded, graph = map(set, (artifact["allowedTestTasks"], artifact["excludedProducerTasks"], artifact["allowedGraphTasks"]))
    require(len(allowed) == 37 and len(excluded) == 62 and len(graph) == 38 and graph == allowed | {MARKER} and not graph & excluded, "38/62 task partition changed")
    require(set(re.findall(r"(?m)^(:\S+) SKIPPED$", raw["excluded-task-graph.log"].decode())) == graph, "Dry-run graph changed")
    for phase in ("build", "dry-run"):
        guard = data("guard-" + phase + "-task-graph.json")
        require(guard["status"] == "ADMITTED" and set(guard["tasks"]) == graph and not guard["unexpected"] and not guard["missing"]
                and guard["testTaskCount"] == 37 and guard["excludedProducerTaskCount"] == 62 and guard["markerTask"] == MARKER, "Guard admission changed")
        admission = guard["markerAdmission"]
        require(admission["enabled"] is False and admission["actionCount"] == 0 and admission["jar"]["sha256"] == JAR_SHA, "Marker was enabled at admission")
        resolved = data("guard-" + phase + "-resolved-artifacts.json")["configurations"]
        require({c["configuration"] for c in resolved} == {"e2eAndroidTestCompileClasspath", "e2eAndroidTestRuntimeClasspath", "e2eRuntimeClasspath"}, "Resolved configuration inventory changed")
        require(all([v for v in c["components"] if v.startswith("androidx.compose.ui:ui-android:")] == ["androidx.compose.ui:ui-android:1.13.0-beta02"] for c in resolved), "Resolved Compose UI version changed")
    marker = data("guard-build-disabled-producer-marker-state.json")
    require(marker == artifact["disabledMarkerResult"] and marker["status"] == "SKIPPED_FROZEN_OUTPUTS_UNCHANGED" and marker["task"] == MARKER, "Marker result changed")
    for key, expected in {"executed": True, "skipped": True, "didWork": False, "failure": None}.items():
        require(marker[key] is expected and marker["state"][key] is expected, "Marker state changed: " + key)
    require(marker["enabled"] is False and marker["actions"] == 0 and marker["state"]["skipMessage"] == "SKIPPED", "Marker performed work")
    require(marker["jarBeforeSha256"] == marker["jarAfterSha256"] == JAR_SHA, "Marker JAR hash changed")
    require(all(marker[key]["sha256"] == JAR_SHA and marker[key]["bytes"] == 153299 for key in ("jarAtGraph", "jarBeforeTask", "jarAfterTask")), "Marker JAR observations changed")
    require(marker["outputsAtGraph"] == marker["outputsBeforeTask"] == marker["outputsAfterTask"] == [artifact["disabledMarker"]["jar"]], "Marker output inventory changed")
    commands = artifact["commands"]
    require(len(commands) == 6 and all(c["exitCode"] == 0 and c["logSha256"] == sha(raw[c["log"]]) for c in commands), "Command result or log binding changed")
    require(round(commands[1]["elapsedSeconds"], 6) == 4.960695, "Build duration changed")
    log = raw["test-apk-build.log"].decode()
    require("BUILD SUCCESSFUL" in log and "BUILD FAILED" not in log and "> Task " + MARKER + " SKIPPED" in log, "Build log outcome changed")
    classes = data("new-test-classes.json")
    require({k: len(v) for k, v in classes.items()} == {"kotlin": 25, "java": 37}
            and artifact["testClassInventory"]["counts"] == {"kotlin": 25, "java": 37}
            and artifact["testClassInventory"]["sha256"] == sha(raw["new-test-classes.json"]), "Test output inventory changed")
    require(any(v["path"] == "dev/logisim/mobile/input/ZoomOutObservationCapture.class" for v in classes["kotlin"])
            and any(v["path"] == "dev/logisim/mobile/input/NativeZoomOutObservations.class" for v in classes["java"]), "Collector class identity missing")
    post = data("compiler-inputs-postcheck.json")
    require(post["allCapturedInputBytesUnchanged"] is True and len(post["checks"]) == 2, "Compiler postcheck changed")
    require({check["report"] for check in post["checks"]} == {"guard-build-inputs-compileE2eAndroidTestKotlin.json", "guard-build-inputs-compileE2eAndroidTestJavaWithJavac.json"}, "Compiler task inventory changed")
    for check in post["checks"]:
        captured = data(check["report"])
        require(check["missingRootsBefore"] == 0 and not check["afterDifferences"] and captured["missingRoots"] == 0
                and len(captured["files"]) == check["files"] and captured["task"] == check["task"], "Compiler input identity changed")
    require(artifact["testIdentity"] == {"package": PACKAGE, "instrumentations": [{"name": RUNNER, "targetPackage": TARGET}]}, "Test package/runner/target changed")
    require("package: name='" + PACKAGE + "'" in raw["test-apk-badging.log"].decode(), "Badging package changed")
    manifest = raw["test-apk-manifest.log"].decode()
    require(all('"' + value + '"' in manifest for value in (PACKAGE, TARGET, RUNNER)), "Manifest identity changed")
    require(artifact["testAndReusedAppSignerSha256"] == SIGNER and all("Signer #1 certificate SHA-256 digest: " + SIGNER in raw[name].decode()
            and "Number of signers: 1" in raw[name].decode() and "Verified using v2 scheme (APK Signature Scheme v2): true" in raw[name].decode()
            for name in ("test-apk-signature.log", "reused-app-signature.log")), "Signer identity changed")
    test = artifact["testArtifact"]
    require(test["sha256"] == TEST_SHA and test["bytes"] == 318555 and test["producerSource"] == SOURCE, "New test APK identity changed")
    references = {item["kind"]: item for item in index["localArtifacts"]}
    require(set(references) == {"test_apk", "e2e_apk", "snapshot_helper", "disabled_marker_jar"}
            and references["test_apk"]["sha256"] == TEST_SHA and references["test_apk"]["bytes"] == 318555
            and references["disabled_marker_jar"]["sha256"] == JAR_SHA, "Local artifact references changed")
    require(references["e2e_apk"]["sha256"] == "c12be1b537b1e02744f057fb36372f3669df25e3385e9c2e7e4fda8a8fc1484b"
            and references["snapshot_helper"]["sha256"] == "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c", "Frozen APK references changed")
    if local_artifacts:
        for item in references.values():
            value = Path(item["path"]).read_bytes()
            require(len(value) == item["bytes"] and sha(value) == item["sha256"], "Local artifact bytes changed: " + item["kind"])
    return len(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--local-artifacts", action="store_true", help="Also hash local APK/JAR references; never execute them")
    args = parser.parse_args()
    try:
        count = verify(args.archive, args.local_artifacts)
    except (KeyError, ValueError, OSError, TypeError, EOFError) as error:
        parser.exit(1, "INVALID_ARCHIVE: " + str(error) + "\n")
    print(f"VERIFIED_ARCHIVE_ONLY files={count} build=TEST_APK_BUILD_PASS native=NOT_RUN localArtifacts={args.local_artifacts}")


if __name__ == "__main__":
    main()
