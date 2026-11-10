#!/usr/bin/env python3
"""Verify this portable receipt archive without reading external paths or running native tools."""

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent
CASE_IDS = ["phone001", "phone002", "phone003", "phone004"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def local(path):
    relative = PurePosixPath(path)
    require(not relative.is_absolute() and ".." not in relative.parts, "Unsafe archive path: " + path)
    target = ROOT.joinpath(*relative.parts)
    require(target.resolve().is_relative_to(ROOT), "Archive path escapes its root: " + path)
    require(target.is_file() and not target.is_symlink(), "Missing or linked archive file: " + path)
    return target


def read_json(path):
    return json.loads(local(path).read_bytes())


def verify():
    manifest = read_json("manifest.json")
    require(manifest["schema_version"] == 1, "Unexpected schema")
    require([case["id"] for case in manifest["cases"]] == CASE_IDS, "Unexpected receipt set")
    actual_files = {p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*") if p.is_file()}
    require(actual_files == set(manifest["files"]) | {"manifest.json"}, "Unlisted or missing archive files")
    total = 0
    for path, expected in manifest["files"].items():
        data = local(path).read_bytes()
        require(len(data) == expected["bytes"] and digest(data) == expected["sha256"], "Hash/size mismatch: " + path)
        total += len(data)
    for path, origin in manifest["originals"].items():
        data = local(path).read_bytes()
        require(len(data) == origin["bytes"] and digest(data) == origin["sha256"], "Original-copy mismatch: " + path)

    for case in manifest["cases"]:
        prefix = case["id"] + ": "
        result = read_json(case["results"])
        run = read_json(case["run_configuration"])
        require(result["status"] == "FAIL" and result["native_execution_gate"] == "NATIVE_FAIL"
                and result["qualification_gate"] == "FAIL" and result["execution"]["exit_code"] == 65,
                prefix + "Native failure receipt changed")
        require(case["result"] == "FAIL" and case["stage"] == "NUMERIC_NOT_RUN", prefix + "Stage/result changed")
        require(case["numeric_actions"] == {"delete_taps": 0, "typed_values": []}, prefix + "Numeric actions changed")
        require(case["width_policy_prechecks_entered"] == (case["id"] == "phone004"), prefix + "Precheck scope changed")
        require(case["run_id"] == result["run_id"] == run["run_id"], prefix + "Run identity mismatch")
        require(case["app"]["declared_source_pin"] == result["declared_baseline_sha"], prefix + "App source mismatch")
        for key in ["bundle_id", "executable_sha256", "bundle_tree_sha256"]:
            require(case["app"][key] == result["artifact"][key], prefix + "App identity mismatch: " + key)
        for name, source in case["harness_sources"].items():
            require(digest(local(source["path"]).read_bytes()) == source["sha256"] == result["harness_sources"][name],
                    prefix + "Captured harness mismatch: " + name)
        for attachment in case["attachments"]:
            path = attachment["path"]
            if path.endswith(".png"):
                require(local(path).read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), prefix + "Invalid PNG")
        final = [item for item in case["attachments"] if item["path"].endswith("/final-screen.png")]
        require(len(final) == 1 and final[0]["suggestedHumanReadableName"].startswith("final-screen_"),
                prefix + "Final screenshot provenance changed")

        log = case["test_log"]
        excerpt = local(log["excerpt"]).read_bytes()
        chunks = re.split(rb"^=== original test.log lines ([0-9]+)-([0-9]+) ===\n", excerpt, flags=re.MULTILINE)
        require(chunks[0] == b"" and len(chunks) == 1 + len(log["ranges"]) * 3, prefix + "Invalid log ranges")
        last_end = 0
        for index, expected in enumerate(log["ranges"]):
            start, end, body = chunks[1 + index * 3:4 + index * 3]
            start, end = int(start), int(end)
            require(start == expected["start_line"] and end == expected["end_line"] and start > last_end,
                    prefix + "Log range identity/order mismatch")
            require(len(body.splitlines()) == end - start + 1 and digest(body) == expected["sha256"],
                    prefix + "Log range content mismatch")
            last_end = end
        require(("failed (" + case["duration_seconds"] + " seconds).").encode() in excerpt,
                prefix + "Missing terminal duration")

        fixture = case["fixture"]
        raw = local(fixture["path"]).read_bytes()
        if fixture["kind"] == "TEMPLATE_ONLY":
            require(case["id"] == "phone001" and fixture["runtime_input_sha256"] is None
                    and fixture["runtime_label"] == "UNOBSERVED_UUID" and "inspector" not in run["fixtures"],
                    prefix + "Template was promoted to runtime input")
            source = local(fixture["source_path"]).read_text()
            marker = '        let xml = """\n'
            start = source.index(marker) + len(marker)
            end = source.index('\n        """', start)
            require(raw == (source[start:end] + "\n").encode(), prefix + "Lexical template mismatch")
            require(ET.fromstring(raw).find("circuit/comp/a[@name='label']").get("val") == r"\(label)",
                    prefix + "Runtime label was invented")
        else:
            require(fixture["kind"] == "STAGED_INPUT" and digest(raw) == fixture["sha256"]
                    == run["inspector"]["fixtureSHA256"], prefix + "Staged input hash mismatch")
            document = ET.fromstring(raw)
            circuits, pins = document.findall("circuit"), document.findall("circuit/comp")
            require(len(circuits) == 1 and len(pins) == 1 and not document.findall("circuit/wire"),
                    prefix + "Fixture multiplicity changed")
            require(document.find("main").get("name") == circuits[0].get("name") == fixture["circuit"]
                    == run["titles"]["inspector"], prefix + "Fixture circuit mismatch")
            require(pins[0].attrib == {"lib": "0", "loc": "(100,100)", "name": "Pin"}, prefix + "Fixture pin changed")
            require({a.get("name"): a.get("val") for a in pins[0].findall("a")} == {
                "type": "input", "width": "1", "label": fixture["label"]}, prefix + "Fixture attributes changed")
            require(fixture["label"] == run["inspector"]["label"], prefix + "Fixture label mismatch")

    return {"integrity": "INTEGRITY_PASS", "cases": len(manifest["cases"]), "files": len(manifest["files"]),
            "bytes": total, "native_results": {case: "FAIL" for case in CASE_IDS}, "numeric_stage": "NUMERIC_NOT_RUN"}


if __name__ == "__main__":
    try:
        print(json.dumps(verify(), indent=2))
    except (ValueError, KeyError, TypeError, OSError, ET.ParseError) as error:
        print(json.dumps({"integrity": "FAIL", "error": str(error)}), file=sys.stderr)
        raise SystemExit(1)
