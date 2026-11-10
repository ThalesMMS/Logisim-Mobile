#!/usr/bin/env python3
"""Verify the preserved failed preparation without contacting Android."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET
import zipfile


SOURCE = "2827b19b2041ed98dc5e10f2c9d4a94e07911869"
PRODUCT = "3a0a01f1af05dd3fea75d5a1499c762d039c478a"
CIRCUIT = "19198a15e4880baccd3d00391f257c766a8be0859daae1ca721d3d8ed7c12a31"
APK_HASHES = {
    "e2e_apk.apk": "10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973",
    "e2e_test_apk.apk": "19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b",
    "snapshot_apk.apk": "504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c",
}
FAILURE = "AssertionError('The status_file_opened control did not appear within 30s')"


def digest(payload):
    return hashlib.sha256(payload).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(directory):
    receipt = json.loads((directory / "receipt.json").read_bytes())
    require(receipt["status"] == "FAIL_SETUP", "The observed outcome must remain FAIL_SETUP")
    require(receipt["source_pin"] == SOURCE and receipt["product_pin"] == PRODUCT, "Source identity differs")
    require(receipt["exec_handle"] == 89831 and receipt["observed_exit_code"] == 1, "Terminal identity differs")
    require(receipt["sampling_started"] is False and receipt["collector_launched"] is False, "No sample ran")
    require(receipt["native_case_attempts"] == 1 and receipt["native_retries"] == 0, "Only one case attempt exists")
    require(receipt["builds"] == 0 and receipt["installs"] == 0, "No build or install ran")
    require(receipt["producer_clean_after"] == {"head": SOURCE, "porcelain": ""}, "Producer changed")
    require(receipt["host_argument_collection"] is False, "No process-argument collection was authorized")
    for key in ("raw_archive", "raw_manifest"):
        record = receipt[key]
        payload = (directory / record["file"]).read_bytes()
        require(len(payload) == record["bytes"] and digest(payload) == record["sha256"], f"Integrity: {key}")
    manifest = json.loads((directory / "raw-manifest.json").read_bytes())
    table = manifest["files"]
    require(len(table) == receipt["original_file_count"] == 243, "Raw file set changed")
    require(sum(row["bytes"] for row in table.values()) == receipt["original_payload_bytes"] == 1332430, "Raw byte count changed")
    with zipfile.ZipFile(directory / "raw-attempt.zip") as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == set(table), "Archive file set differs")
        raw = {}
        for name in names:
            path = PurePosixPath(name)
            require(not path.is_absolute() and ".." not in path.parts, "Unsafe archive path")
            require((archive.getinfo(name).external_attr >> 16) & 0o170000 == 0o100000, "Only ordinary files are archived")
            payload = archive.read(name)
            require(len(payload) == table[name]["bytes"] and digest(payload) == table[name]["sha256"], f"Integrity: {name}")
            raw[name] = payload
    for name in ("attempt.json", "failure.txt", "import-report-1-ui.xml", "driver.py"):
        require((directory / name).read_bytes() == raw[name], f"Convenience copy differs: {name}")
    require(not any(name.startswith("metrics/") or name == "collector.log" for name in raw), "Collection output was not produced")
    attempt = json.loads(raw["attempt.json"])
    require(attempt["status"] == "FAIL" and attempt["failure"] == FAILURE, "Preserve the literal preparation failure")
    require(attempt["sampling_started"] is False and "collector_argv" not in attempt, "Collector was not reached")
    require("canonical_before_sha256" not in attempt and "model_before" not in attempt, "Canonical model was not observed")
    require(attempt["source"] == SOURCE and attempt["product_source"] == PRODUCT, "Attempt identity differs")
    require(attempt["serial"] == "emulator-5558" and attempt["native_case_attempts"] == 1, "Lane or attempt changed")
    require(attempt["builds"] == 0 and attempt["installs"] == 0, "Attempt installed or built")
    require(attempt["expected_apks"] == APK_HASHES, "Expected artifacts differ")
    require(attempt["installed_before"] == attempt["installed_after"], "Installed artifacts changed")
    require({row["sha256"] for row in attempt["installed_before"]} == set(APK_HASHES.values()), "Installed hashes differ")
    restore = attempt["configuration_restoration"]
    require(restore == {"actions": [], "final": attempt["configuration_before"], "matches_before": True}, "Configuration was not returned unchanged")
    secure = restore["final"]["settings"]["secure"]
    require(secure["accessibility_enabled"] == "0" and secure["enabled_accessibility_services"] == "null" and secure["touch_exploration_enabled"] == "0", "Reader was not off")
    cleanup = attempt["owned_app_cleanup"]
    packages = {"thalesmms.logisim.mobile.e2e", "thalesmms.logisim.mobile.e2e.test", "thalesmms.logisim.mobile.snapshot.e2e.test"}
    require({row["package"] for row in cleanup} == packages and len(cleanup) == 3, "Cleanup package set differs")
    require(all(row == {"package": row["package"], "stopped": True, "pid_after": ""} for row in cleanup), "An owned app remained running")
    require(attempt["original_inputs_unchanged"] is True and attempt["original_inputs_before"] == attempt["original_inputs_after"], "Original input identity differs")
    require(len(attempt["original_inputs_before"]) == 11, "Historical corpus file count differs")
    require(digest(raw["source-independent-16.circ"]) == CIRCUIT and len(raw["source-independent-16.circ"]) == 16109, "Historical source differs")
    require(attempt["provider_source_before_sha256"] == CIRCUIT, "Provider staging readback differs")
    require(attempt["driver_sha256"] == digest(raw["driver.py"]), "Executed driver differs")
    calls = json.loads(raw["adb-calls.json"])
    require([row["number"] for row in calls] == list(range(1, 119)), "ADB envelope sequence differs")
    for row in calls:
        require(row["argv"][1:3] == ["-s", "emulator-5558"], "ADB lane differs")
        if row["number"] == 79:
            require("stdout" not in row and "stderr" not in row and row.get("error", "").startswith("TimeoutExpired("), "Preserve the deadline-limited UI query timeout")
            continue
        for channel in ("stdout", "stderr"):
            entry = row[channel]
            require(digest(raw[entry["file"]]) == entry["sha256"] and len(raw[entry["file"]]) == entry["bytes"], "ADB payload differs")
    require(raw["adb/0027-stdout"] == raw["source-independent-16.circ"], "Initial provider bytes differ")
    require(b"status_file_opened control did not appear within 30s" in raw["failure.txt"], "Failure traceback differs")
    report = ET.fromstring(raw["import-report-1-ui.xml"])
    report_texts = {node.get("text", "") for node in report.iter("node")}
    require(f"Document: {attempt['fixture_name']}" in report_texts, "Report belongs to another fixture")
    require({"Circuit: gates-16", "Source version: 3.8.0. Circuits: 1.", "Applied 48 times."}.issubset(report_texts), "Import report observations differ")
    final = ET.fromstring(raw["adb/0077-stdout"])
    texts = {node.get("text", "") for node in final.iter("node")}
    require({"gates-16", "Steps 0", "▶ Run", "Operation cancelled."}.issubset(texts), "Final observed UI differs")
    components = [node for node in final.iter("node") if node.get("resource-id", "").startswith("canvas-component:")]
    require(len(components) == 64, "Final Canvas component observation differs")
    labels = {node.get("content-desc") for node in components}
    require({f"{kind}-{i}" for kind in ("a", "b", "out") for i in range(16)}.issubset(labels), "Observed pin labels differ")
    return {"status": "VERIFIED_FAIL_SETUP", "raw_files": len(raw), "adb_envelopes": len(calls), "sample_windows": 0}


if __name__ == "__main__":
    try:
        print(json.dumps(verify(Path(__file__).resolve().parent), sort_keys=True))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
