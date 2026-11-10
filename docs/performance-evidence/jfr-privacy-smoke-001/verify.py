#!/usr/bin/env python3
"""Verify the archived privacy smoke without starting Java or reading environment values."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
PIN = "8c79c7eb6ee2921aac383031426f18d3a302503c"
FORBIDDEN = {"jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.JVMInformation"}
ENVIRONMENT = {"GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "UNEXPECTED_ENVIRONMENT", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}
OBSERVED_EVENTS = {"dev.logisim.PrivacySmokeMarker": 1, "jdk.ExecutionSample": 3,
    "jdk.NativeMethodSample": 3, "jdk.ObjectAllocationSample": 88,
    "jdk.GarbageCollection": 2, "jdk.GCPhasePause": 2}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(name):
    return json.loads((ROOT / name).read_text())


def verify(check_local_jfr=False):
    listed = set()
    for line in (ROOT / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        assert Path(name).name == name and name not in listed
        listed.add(name)
        assert sha(ROOT / name) == digest, name
    assert listed == {p.name for p in ROOT.iterdir() if p.is_file() and p.name != "SHA256SUMS"}
    result, plan, observed = read("receipt.json"), read("plan.json"), read("java-receipt.json")
    private = read("jfr-local-only.json")
    assert result["status"] == observed["status"] == "PASS"
    assert result["returncode"] == 0 and result["jvm_invocations"] == 1
    assert result["source_revision"] == result["source_revision_after"] == plan["expected_source"] == PIN
    assert result["same_identity"] and result["literal_count_checks"] and result["sentinel_bytes_absent"]
    assert result["source_identity_before"] == result["source_identity_after"] == plan["source_identity"]
    for name, digest in result["source_identity_before"].items():
        if name == "java_launcher":
            continue
        assert sha(ROOT / Path(name).name) == digest, name
    assert observed["marker_count"] == 1
    assert observed["event_counts"] == OBSERVED_EVENTS
    assert observed["event_counts"]["dev.logisim.PrivacySmokeMarker"] == 1
    assert all(isinstance(count, int) and count > 0 for count in observed["event_counts"].values())
    assert observed["forbidden_event_counts"] == {name: 0 for name in FORBIDDEN}
    assert all(observed["event_counts"].get(name, 0) == 0 for name in FORBIDDEN)
    assert observed["disabled_settings"] == {name: True for name in FORBIDDEN}
    assert observed["absent_environment"] == {name: True for name in ENVIRONMENT}
    assert observed["option_properties_absent"] and observed["sentinel_bytes_absent"]
    assert observed["recording_closed_before_decode"] is True
    assert observed["java_version"] == "21.0.11+10-b1163.116"
    assert plan["execute"] is True and plan["timeout_seconds"] == 30
    expected_keys = {"PATH", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR"}
    assert set(plan["java_environment_keys"]) == expected_keys
    assert set(plan["parent_environment_keys"]) == expected_keys | ENVIRONMENT
    tree = ast.parse((ROOT / "benchmark-circuits.py").read_text())
    allowlist = ast.literal_eval(next(node.value for node in tree.body if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "JAVA_ENVIRONMENT_KEYS" for target in node.targets)))
    assert expected_keys <= set(allowlist) and not ENVIRONMENT.intersection(allowlist)
    configuration = ET.fromstring((ROOT / "benchmark-profile.jfc").read_text())
    settings = {event.get("name"): {item.get("name"): item.text for item in event.findall("setting")}
        for event in configuration.findall("event")}
    assert all(settings[name]["enabled"] == "false" for name in FORBIDDEN)
    assert settings["jdk.ExecutionSample"]["enabled"] == settings["jdk.ObjectAllocationSample"]["enabled"] == "true"
    argv = plan["argv"]
    assert argv[1:3] == ["--add-modules", "jdk.jfr"]
    assert argv[3].startswith("-XX:StartFlightRecording=name=logisim-privacy-smoke,settings=")
    assert argv[3].endswith(",dumponexit=false") and "settings=profile," not in argv[3]
    assert argv[4:6] == ["--source", "21"] and Path(argv[6]).name == "JfrPrivacySmoke.java"
    assert private["status"] == "LOCAL_ONLY_PENDING_ROOT_REVIEW" and not (ROOT / "recording.jfr").exists()
    for record in result["files"]:
        if record["file"] == "recording.jfr":
            assert record["bytes"] == private["bytes"] == 209700
            assert record["sha256"] == private["sha256"] == "ecfb11bfc308f76ff110b5c350cd7b1ac3d5c36154a6a4416138490159855ffd"
        else:
            path = ROOT / record["file"]
            assert sha(path) == record["sha256"] and path.stat().st_size == record["bytes"]
    if check_local_jfr:
        path = Path(private["path"])
        assert sha(path) == private["sha256"] and path.stat().st_size == private["bytes"]
        assert path.read_bytes()[:4] == b"FLR\x00"
        assert sha(Path(argv[0])) == result["source_identity_before"]["java_launcher"]
    return {"status": "PASS", "producer": PIN, "marker_count": 1, "sensitive_event_count": 0,
        "java_invocations_in_capture": 1, "verification": "Python-only archive integrity and literal receipts",
        "jfr_content": "LOCAL_HASH_PASS" if check_local_jfr else "LOCAL_ONLY_REFERENCE"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-local-jfr", action="store_true", help="Hash the private original and launcher; never prints contents")
    print(json.dumps(verify(parser.parse_args().check_local_jfr), indent=2))
