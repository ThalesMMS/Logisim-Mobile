#!/usr/bin/env python3
"""Verify the historical functional replay receipt without launching its producer."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile


PIN = "29942df31947a38f9656dcc0e414c5ec457c0fc1"
MANIFEST_SHA256 = "295571568422db33a280e9c0a52b322982cfa7f57c10e297fd70a788d7596f58"
SAP_SHA256 = "2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac"
STAMP_SHA256 = "4b463e001d5cb05005cf1c21c43f2982592b3c2f4a1e9dd4ee0241a56f68126a"
JAVA_SHA256 = "1dddd396385d905cb14fa5cc36a94d84ca760c63b576e754ae4503ec9eb7bb5f"
PRODUCER = PurePosixPath("/Users/thales/GitHub/logisim-mobile-worktrees/performance48-corpus-replay")
OUTPUT = PRODUCER / "outputs/performance/historical-replay-29942df3-001"
CORPUS = PurePosixPath("/Users/thales/GitHub/logisim-mobile-worktrees/performance/outputs/performance/mobile-corpus-v2-complete")
ENV_ALLOWLIST = ["PATH", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP", "SYSTEMROOT", "WINDIR"]
ENV_KEYS = ["LANG", "LC_ALL", "LC_CTYPE", "PATH", "TMPDIR"]
IDENTITY_CHECKS = ["before_build", "after_build", "before_execution", "after_execution"]
CAPTURED_SOURCES = {
    "PerformanceCorpusReplay.kt": "core/src/jvmTest/kotlin/dev/logisim/mobile/core/PerformanceCorpusReplay.kt",
    "PerformanceOracle.kt": "core/src/commonTest/kotlin/dev/logisim/mobile/core/PerformanceOracle.kt",
    "PerformanceCircuits.kt": "core/src/commonTest/kotlin/dev/logisim/mobile/core/PerformanceCircuits.kt",
    "Sap1IntegrationTest.kt": "core/src/jvmTest/kotlin/dev/logisim/mobile/core/Sap1IntegrationTest.kt",
    "benchmark-circuits.py": "scripts/benchmark-circuits.py",
    "benchmark-profile.jfc": "scripts/performance/benchmark-profile.jfc",
}
PUBLIC_FILES = set(CAPTURED_SOURCES) | {
    "README.md", "verify.py", "attempt.json", "replay.json", "prepare.log", "replay.log",
    "performance-sources.json", "artifact-snapshots.json", "historical-inputs.zip",
}
SNAPSHOTS = {
    "source-snapshot.zip": (796_923, "de2855f2692114b54954cd30acc897884f640fbfdbcd558dcc749d0b31a75dea", 305),
    "classpath-snapshot.zip": (5_945_642, "6657d96f9e109d9308050da7ef4166668a486ad3caaa69a52e3147471058e41f", 1259),
}
LIMITS = [
    "Full metadata and canonical roundtrip references derive from the first import; original metadata is not independently proved in full.",
    "Simulation uses the first import. It is not repeated on the reopened project. Banks cover only 00, 5a, 00 with b=5a; SAP covers its historical program.",
    "SAP runtime diagnostics are required empty at the final tick. Bank runtime diagnostics are checked in each phase; the analyzer rejects ERROR diagnostics.",
    "Pin kinds, port positions, widths and bindings are checked; not every original direction attribute or graphical bound has an independent literal reference.",
    "Runtime provenance identifies /usr/bin/java and the recorded JBR version. The launcher hash does not freeze the complete JBR runtime.",
    "Source and classpath ZIPs are post-execution producer snapshots matching the consumed hash maps, not the removed temporary classpath itself.",
    "Invocation count is the attempt record's count. This offline verifier does not observe historical host processes or rerun the engine.",
    "This is functional evidence only. It does not measure performance or qualify Native, hardware, general desktop equivalence, or historical bb63 timings.",
]


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(data, label):
    value = json.loads(data, object_pairs_hook=unique_object)
    require(isinstance(value, dict), f"{label} must be a JSON object")
    return value


def safe_name(name):
    require(isinstance(name, str) and name, "empty or non-string artifact name")
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in name and path.as_posix() == name,
            f"unsafe artifact name: {name}")


def hash_map(value, count, label):
    require(isinstance(value, dict) and len(value) == count, f"{label} must contain {count} entries")
    for name, digest in value.items():
        safe_name(name)
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), f"invalid {label} digest: {name}")
    return value


def verify_checksums(root):
    expected_inventory = PUBLIC_FILES | {"SHA256SUMS.txt"}
    entries = list(root.iterdir())
    require({entry.name for entry in entries} == expected_inventory, "unexpected or missing public artifact; no CSV, XML, JFR, or new payload is allowed")
    require(all(entry.is_file() and not entry.is_symlink() for entry in entries), "public artifacts must be regular files")
    checksums = {}
    for line in (root / "SHA256SUMS.txt").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        require(match is not None, "invalid SHA256SUMS.txt record")
        digest, name = match.groups()
        safe_name(name)
        require(name in PUBLIC_FILES and name not in checksums, f"unexpected or duplicate checksum: {name}")
        checksums[name] = digest
    require(set(checksums) == PUBLIC_FILES, "SHA256SUMS.txt must cover every public file exactly once")
    files = {name: (root / name).read_bytes() for name in PUBLIC_FILES}
    for name, data in files.items():
        require(sha256(data) == checksums[name], f"public checksum mismatch: {name}")
    return files


def zip_payloads(path, expected):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        require(len(entries) == len(expected) and {entry.filename for entry in entries} == set(expected), f"ZIP inventory mismatch: {path.name}")
        payloads = {}
        for entry in entries:
            safe_name(entry.filename)
            require(not entry.is_dir() and not stat.S_ISLNK(entry.external_attr >> 16), f"ZIP entry must be a regular file: {entry.filename}")
            data = archive.read(entry)
            require(sha256(data) == expected[entry.filename], f"ZIP payload hash mismatch: {entry.filename}")
            payloads[entry.filename] = data
        return payloads


def verify_inputs(root, attempt, replay):
    names = [f"{kind}-{banks}.circ" for kind in ("independent", "hierarchical") for banks in (16, 64, 256, 384, 1024)]
    input_hashes = hash_map(replay["input_sha256"], 12, "input hashes")
    require(set(input_hashes) == {"corpus/manifest.json", "sap1.circ"} | {"corpus/" + name for name in names}, "historical input inventory differs")
    require(input_hashes["corpus/manifest.json"] == MANIFEST_SHA256 and input_hashes["sap1.circ"] == SAP_SHA256, "historical manifest or SAP pin differs")
    payloads = zip_payloads(root / "historical-inputs.zip", input_hashes)
    require(len(payloads["corpus/manifest.json"]) == 82_126 and len(payloads["sap1.circ"]) == 47_528, "historical manifest or SAP byte length differs")
    manifest = read_json(payloads["corpus/manifest.json"], "historical manifest")
    require(manifest["schema"] == 1 and manifest["corpus_version"] == 2, "historical corpus schema differs")
    require([case["file"] for case in manifest["cases"]] == names, "historical corpus cases differ")
    expected_cases = []
    for case in manifest["cases"]:
        banks = case["banks"]
        rejected = banks == 1024
        data = payloads["corpus/" + case["file"]]
        require(len(data) == case["bytes"] and sha256(data) == case["sha256"], f"original XML differs: {case['file']}")
        require(case["import_status"] == ("rejected" if rejected else "accepted") and case["verified_outputs"] == (0 if rejected else banks), "historical case status or output count differs")
        require(case["main_components"] == 4 * banks and case["main_wires"] == banks, "historical case shape counts differ")
        require(case["low_output_hex"] == "00" and case["high_output_hex"] == "5a", "historical output literals differ")
        require(case["rejection_notice_keys"] == (["error_xml_structure"] if rejected else []), "historical rejection contract differs")
        expected_cases.append({key: case[key] for key in ("file", "sha256", "import_status", "verified_outputs")})
    require(replay["cases"] == expected_cases and all(type(case["verified_outputs"]) is int for case in replay["cases"]), "replay cases, statuses, outputs, or hashes differ")
    require(attempt["original_inputs_before"] == attempt["original_inputs_after"] and attempt["original_inputs_unchanged"] is True, "original inputs changed across replay")
    before = attempt["original_inputs_before"]
    require(isinstance(before, list) and len(before) == 12 and len({item["name"] for item in before}) == 12, "original input records must contain twelve unique names")
    expected_paths = {"corpus/" + name: str(CORPUS / name) for name in ["manifest.json", *names]}
    expected_paths["sap1.circ"] = str(PRODUCER / "examples/sap1.circ")
    require(replay["original_input_paths"] == expected_paths, "recorded original input paths differ")
    require({item["name"] for item in before} == set(payloads), "original input records differ from archived inputs")
    for item in before:
        data = payloads[item["name"]]
        require(item["path"] == expected_paths[item["name"]] and type(item["bytes"]) is int and item["bytes"] == len(data) and item["sha256"] == sha256(data), "original input record differs from literal archived bytes")
    return before


def verify_runtime(attempt, replay, files):
    execution = replay["execution"]
    require(execution["build_skipped"] is True and execution["jfr"] is None and execution["output_files_sha256"] == {}, "replay must skip build and produce no benchmark payload or JFR")
    require(execution["java_path"] == "/usr/bin/java" and execution["java_sha256"] == JAVA_SHA256, "recorded Java launcher identity differs")
    version = ("openjdk 21.0.11 2026-04-21\nOpenJDK Runtime Environment JBR-21.0.11+10-1163.116-nomod (build 21.0.11+10-b1163.116)\n"
               "OpenJDK 64-Bit Server VM JBR-21.0.11+10-1163.116-nomod (build 21.0.11+10-b1163.116, mixed mode, sharing)")
    require(execution["java_version"] == version, "recorded JBR version differs")
    require(execution["java_environment_allowlist"] == ENV_ALLOWLIST and execution["java_environment_keys"] == ENV_KEYS, "recorded Java environment policy or observed keys differ")
    require(execution["identity_checks"] == IDENTITY_CHECKS and execution["input_identity_checks"] == IDENTITY_CHECKS, "recorded identity checks differ")
    argv = execution["argv"]
    require(isinstance(argv, list) and len(argv) == 9 and argv[:4] == ["/usr/bin/java", "-Xms256m", "-Xmx2g", "-cp"] and argv[5] == "dev.logisim.mobile.core.PerformanceCorpusReplay", "consumed command is not the functional replay")
    temporary = PurePosixPath(argv[6]).parents[1]
    require(temporary.parent == OUTPUT and temporary.name.startswith("logisim-benchmark-") and argv[6] == str(temporary / "results/replay.json"), "replay output was not staged in the recorded producer directory")
    require(argv[4].split(":") == [str(temporary / "classpath" / name) for name in ("main", "test", "kotlin-stdlib.jar")], "consumed classpath was not physically staged")
    require(argv[7:] == [str(temporary / "inputs/corpus/manifest.json"), str(temporary / "inputs/sap1.circ")], "consumed inputs were not staged copies")
    wrapper = ["python3", "/tmp/logisim-gradle-serial.py", "python3", "scripts/benchmark-circuits.py"]
    require(attempt["prepare_argv"] == wrapper + ["--prepare-only", "--no-incremental"], "prepare command differs")
    require(attempt["replay_argv"] == wrapper + ["--workload", "replay", "--input-manifest", str(CORPUS / "manifest.json"), "--sap-input", "examples/sap1.circ", "--skip-build", "--output", str(OUTPUT / "replay.json")], "recorded replay invocation differs")
    require(files["replay.log"].decode().splitlines() == [argv[6], str(OUTPUT / "replay.json")], "replay log paths differ")


def verify(root, check_local):
    files = verify_checksums(root)
    attempt = read_json(files["attempt.json"], "attempt")
    replay = read_json(files["replay.json"], "replay")
    stamp = read_json(files["performance-sources.json"], "source stamp")
    snapshots = read_json(files["artifact-snapshots.json"], "snapshot references")
    require(attempt["schema"] == 1 and attempt["status"] == "PASS" and attempt["prepare_status"] == "PASS", "attempt or prepare did not pass")
    require(type(attempt["replay_returncode"]) is int and attempt["replay_returncode"] == 0 and type(attempt["functional_replay_invocations"]) is int and attempt["functional_replay_invocations"] == 1, "attempt must record exactly one successful functional replay")
    require(attempt["source_pin"] == attempt["source_after"] == replay["revision"] == PIN, "producer source pin differs")
    require(attempt["worktree_after_dirty"] is False and replay["worktree_dirty"] is False, "producer was not clean")
    for key, expected in {"schema": 1, "accepted": 8, "rejected": 2, "sap_ticks": 160}.items():
        require(type(replay[key]) is int and replay[key] == expected, f"functional receipt differs: {key}")
    require(replay["scope"] == "functional_replay" and replay["benchmark_executed"] is False and replay["sap_display_hex"] == "08", "receipt is not the expected functional SAP replay")
    require(isinstance(replay["metadata_reference_limit"], str) and "first import" in replay["metadata_reference_limit"] and "do not independently prove" in replay["metadata_reference_limit"], "receipt must preserve the metadata reference limit")
    outputs = attempt["outputs"]
    require(len(outputs) == 3 and {item["file"] for item in outputs} == {"replay.json", "replay.log", "prepare.log"}, "attempt output inventory differs")
    for item in outputs:
        require(type(item["bytes"]) is int and len(files[item["file"]]) == item["bytes"] and sha256(files[item["file"]]) == item["sha256"], f"attempt output hash mismatch: {item['file']}")
    require(attempt["prepare_log_sha256"] == sha256(files["prepare.log"]), "prepare log digest differs")
    prepare_log = files["prepare.log"].decode()
    require("BUILD SUCCESSFUL" in prepare_log, "prepare log does not report success")
    prepared = read_json(prepare_log[prepare_log.index("{"):], "prepare result")
    require(prepared["prepared"] is True and prepared["scope"] == "build_and_identity_only" and prepared["revision"] == PIN and prepared["worktree_dirty"] is False and prepared["compiled_classes"] == 1256 and prepared["benchmark_executed"] is False, "prepare result differs from the source-only build contract")
    sources = hash_map(replay["source_sha256"], 305, "source hashes")
    classes = hash_map(replay["compiled_class_sha256"], 1256, "class hashes")
    classpath = hash_map(replay["execution"]["classpath_files_sha256"], 1259, "classpath hashes")
    require(set(stamp) == {"sources", "classes", "classpath_files"} and stamp["sources"] == sources and stamp["classes"] == classes, "stamp source or class maps differ")
    require(stamp["classpath_files"] == {key: value for key, value in classpath.items() if key != "kotlin-stdlib.jar"}, "stamp classpath map differs")
    require(classpath["kotlin-stdlib.jar"] == replay["execution"]["kotlin_stdlib_sha256"], "Kotlin standard library identity differs")
    prefix = "core/build/classes/kotlin/jvm/"
    require(all(key.startswith(prefix) and key.endswith(".class") for key in classes), "compiled class paths differ")
    require({key[len(prefix):]: value for key, value in classes.items()} == {key: value for key, value in classpath.items() if key.endswith(".class")}, "class files do not match the frozen classpath")
    before_stamp = attempt["stamp_before"]
    require(sha256(files["performance-sources.json"]) == before_stamp["sha256"] == attempt["stamp_after_sha256"] == STAMP_SHA256, "consumed source stamp changed or differs")
    require(before_stamp["source_files"] == 305 and before_stamp["classes"] == 1256 and before_stamp["classpath_files"] == 1258 and before_stamp["path"] == str(PRODUCER / "core/build/performance-sources.json") and prepared["stamp"] == before_stamp["path"], "prepare stamp counts or path differ")
    for name, source in CAPTURED_SOURCES.items():
        require(sha256(files[name]) == sources[source], f"captured source differs from consumed source map: {name}")
    original_inputs = verify_inputs(root, attempt, replay)
    verify_runtime(attempt, replay, files)
    require(snapshots["schema"] == 1 and snapshots["source_pin"] == PIN and snapshots["status"] == "LOCAL_BUILD_SNAPSHOTS", "snapshot reference source or status differs")
    references = snapshots["snapshots"]
    require(len(references) == 2 and {item["file"] for item in references} == set(SNAPSHOTS), "local snapshot reference inventory differs")
    for item in references:
        size, digest, count = SNAPSHOTS[item["file"]]
        require(item["bytes"] == size and item["sha256"] == digest and item["entries"] == count and item["path"] == str(OUTPUT / item["file"]), "local snapshot reference differs from captured identity")
        if check_local:
            path = Path(item["path"])
            require(path.is_file() and not path.is_symlink(), f"local snapshot is missing or linked: {path}")
            data = path.read_bytes()
            require(len(data) == size and sha256(data) == digest, f"local snapshot bytes differ: {path.name}")
            zip_payloads(path, sources if item["file"] == "source-snapshot.zip" else classpath)
    if check_local:
        for item in original_inputs:
            path = Path(item["path"])
            require(path.is_file() and not path.is_symlink(), f"original input is missing or linked: {item['name']}")
            data = path.read_bytes()
            require(len(data) == item["bytes"] and sha256(data) == item["sha256"], f"current original input differs: {item['name']}")
    return {"status": "PASS", "scope": "offline_functional_receipt_verification", "source_pin": PIN,
            "accepted": 8, "rejected": 2, "sap_ticks": 160, "sap_display_hex": "08",
            "archived_inputs_verified": 12, "source_map_entries": 305, "compiled_classes": 1256,
            "classpath_entries": 1259, "captured_source_files_verified": len(CAPTURED_SOURCES),
            "local_snapshots": "bytes_and_maps_verified" if check_local else "references_verified_only",
            "current_original_inputs": "verified" if check_local else "not_read",
            "producer_executed": False, "environment_values_read": False, "limits": LIMITS}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-local-snapshots", action="store_true",
                        help="Also verify both recorded local ZIPs, their complete maps, and the twelve current original inputs")
    args = parser.parse_args()
    try:
        result = verify(Path(__file__).resolve().parent, args.check_local_snapshots)
    except (VerificationError, OSError, ValueError, KeyError, TypeError, IndexError, zipfile.BadZipFile) as error:
        raise SystemExit(f"FAIL: {error}") from None
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
