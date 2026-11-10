#!/usr/bin/env python3
"""Verify the frozen JVM capture offline without launching Java or rebuilding."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PIN = "bb63d681afac30197e1db0e3869672b94662acc9"
CHECK_LOCAL_JFR = False


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(name):
    return json.loads((ROOT / name).read_text())


def original_file(record):
    name = Path(record["path"]).name
    if name.endswith(".jfr"):
        entry = next(x for x in read("jfr-local-only.json")["files"] if x["file"] == name)
        assert entry["status"] == "LOCAL_ONLY"
        assert record["sha256"] == entry["sha256"]
        if "bytes" in record:
            assert record["bytes"] == entry["bytes"]
        assert not (ROOT / name).exists()
        if CHECK_LOCAL_JFR:
            path = Path(entry["path"])
            assert sha(path) == entry["sha256"] and path.stat().st_size == entry["bytes"]
            assert path.read_bytes()[:4] == b"FLR\x00"
        return
    path = ROOT / name
    assert path.is_file(), path
    assert sha(path) == record["sha256"], path
    if "bytes" in record:
        assert path.stat().st_size == record["bytes"], path


def verify(stem):
    sums = (ROOT / "SHA256SUMS").read_text().splitlines()
    listed = set()
    for line in sums:
        digest, name = line.split("  ", 1)
        assert name not in listed and Path(name).name == name, name
        listed.add(name)
        assert sha(ROOT / name) == digest, name
    assert listed == {p.name for p in ROOT.iterdir() if p.is_file() and p.name != "SHA256SUMS"}

    qualification = read("qualification.json")
    qualifications = {run["run"]: run for run in qualification["runs"]}
    assert set(qualifications) == {"jvm-quiet-01", "jvm-quiet-02"}
    assert qualifications["jvm-quiet-01"]["quiet_window_status"] == "PENDING"
    assert qualifications["jvm-quiet-02"]["quiet_window_status"] == "COORDINATED_WITH_LIMITED_PROCESS_CENSUS"
    result = read(stem + ".json")
    capture = read(stem + "-capture.json")
    preparation = read("preparation.json")
    stamp = read("performance-sources.json")
    assert capture["status"] == "PASS" and capture["returncode"] == 0
    assert qualifications[stem]["functional_and_identity_status"] == "PASS"
    assert qualifications[stem]["started_utc"] == capture["started_utc"]
    assert qualifications[stem]["finished_utc"] == capture["finished_utc"]
    assert capture["identity_before"] == capture["identity_after"]
    identity = capture["identity_before"]
    assert identity["revision"] == result["revision"] == preparation["source_revision"] == PIN
    assert not identity["worktree_dirty"] and not result["worktree_dirty"]
    assert identity["stamp_sha256"] == preparation["stamp"]["sha256"] == sha(ROOT / "performance-sources.json")
    assert len(stamp["classes"]) == identity["classes"] == preparation["compiled_class_count"] == 1255
    assert len(stamp["sources"]) == identity["sources"] == 302
    assert len(stamp["classpath_files"]) == identity["classpath_files"] == 1257
    assert result["compiled_class_sha256"] == stamp["classes"]
    source_hashes = result["source_sha256"].copy()
    assert source_hashes.pop("scripts/benchmark-circuits.py") == identity["runner_sha256"]
    assert source_hashes == stamp["sources"]
    execution = result["execution"]
    assert execution["build_skipped"] is True
    classpath = execution["classpath_files_sha256"].copy()
    assert classpath.pop("kotlin-stdlib.jar") == execution["kotlin_stdlib_sha256"]
    assert classpath == stamp["classpath_files"]
    assert execution["identity_checks"] == ["before_build", "after_build", "before_execution", "after_execution"]
    private_jfr = read("jfr-local-only.json")
    assert private_jfr["status"] == "LOCAL_ONLY"
    assert {x["file"] for x in private_jfr["files"]} == {"jvm-quiet-01.jfr", "jvm-quiet-02.jfr"}
    jfr = next(x for x in private_jfr["files"] if x["file"] == stem + ".jfr")
    assert execution["jfr"]["sha256"] == jfr["sha256"]
    assert execution["output_files_sha256"] == {stem + ".csv": sha(ROOT / (stem + ".csv"))}
    for record in capture["files"] + preparation["logs"]:
        original_file(record)
    for phase in ("before", "after"):
        environment = capture["environment_" + phase]
        assert len(environment["records"]) == 5
        for record in environment["records"]:
            original_file(record)
        census = environment["records"][-1]
        assert census["argv"] == ["ps", *(["-ww"] if stem.endswith("02") else []), "-axo", "pid=,comm=,pcpu=,rss="]
    for record in capture.get("compiler_preflight", []):
        original_file(record)
    assert result["schema"] == 2
    assert result["warmups"] == 2
    assert result["verification_scope"] == "outside_timed_regions"
    assert result["cold_settle_scope"] == "new_simulator_in_warm_jvm"
    assert result["gc_scope"] == "benchmark_loop_including_preparation_warmups_and_verification"
    assert result["unmeasured"] == ["draw_frame", "pan_zoom", "device_energy", "background_execution", "cancellation_latency"]
    expected = {(family, size, phase) for family in ("independent", "hierarchical")
        for size in (16, 64, 256, 384)
        for phase in ("serialize", "import", "cold_settle", "input_change", "unchanged_settle")}
    results = {(x["case"], x["size"], x["phase"]): x for x in result["results"]}
    assert len(result["results"]) == len(results) == len(expected) == 40
    assert set(results) == expected
    with (ROOT / (stem + ".csv")).open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    assert len(rows) == 280
    assert {(row["case"], int(row["size"]), row["phase"], int(row["iteration"])) for row in rows} == {
        (*key, iteration) for key in expected for iteration in range(7)}
    for key, summary in results.items():
        group = [row for row in rows if (row["case"], int(row["size"]), row["phase"]) == key]
        assert len(group) == summary["iterations"] == 7
        assert summary["components"] == key[1] * 4 and summary["wires"] == key[1]
        assert all(int(row["components"]) == key[1] * 4 and int(row["wires"]) == key[1] for row in group)
        wall = sorted(int(row["wall_ns"]) for row in group)
        assert wall[0] > 0
        assert summary["wall_median_ns"] == wall[3]
        assert summary["wall_p95_ns"] == summary["wall_max_ns"] == wall[-1]
        for column, field in (("cpu_ns", "cpu_median_ns"), ("allocated_bytes", "allocated_median_bytes")):
            values = sorted(int(row[column]) for row in group if row[column])
            assert summary[field] == (values[(len(values) - 1) // 2] if values else None)
        assert summary["heap_max_bytes"] == max(int(row["heap_bytes"]) for row in group)
    return {"status": "PASS", "run": stem, "revision": PIN, "groups": len(results), "samples": len(rows),
        "jfr_content_verification": "LOCAL_ORIGINAL_PASS" if CHECK_LOCAL_JFR else "NOT_RUN_LOCAL_ONLY_REFERENCE",
        "scope": "Offline integrity, capture identity and summary/CSV consistency; no Java or Native execution"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-local-jfr", action="store_true", help="Also hash the private originals at their captured local paths; never prints their contents")
    CHECK_LOCAL_JFR = parser.parse_args().check_local_jfr
    print(json.dumps([verify(stem) for stem in ("jvm-quiet-01", "jvm-quiet-02")], indent=2))
