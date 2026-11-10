#!/usr/bin/env python3
"""Recompute a retained Simulator compositor census without live/device commands."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / "docs/validation/ios-metal-cohort-census"


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def evidence_files(value):
    if isinstance(value, dict):
        if "path" in value and "sha256" in value:
            yield value
        for child in value.values():
            yield from evidence_files(child)
    elif isinstance(value, list):
        for child in value:
            yield from evidence_files(child)


def read_ips(path):
    text = path.read_text()
    decoder = json.JSONDecoder()
    header, end = decoder.raw_decode(text.lstrip())
    body, tail = decoder.raw_decode(text.lstrip()[end:].lstrip())
    if text.lstrip()[end:].lstrip()[tail:].strip():
        raise ValueError(f"Unexpected data after IPS body: {path}")
    images = body.get("usedImages", [])
    thread = body["threads"][body["faultingThread"]]
    frames = []
    for frame in thread.get("frames", []):
        index = frame.get("imageIndex", -1)
        image = images[index] if 0 <= index < len(images) else {}
        frames.append({
            "image_name": image.get("name"),
            "image_uuid": image.get("uuid"),
            "symbol": frame.get("symbol"),
            "imageOffset": frame.get("imageOffset"),
            "symbolLocation": frame.get("symbolLocation"),
        })
    normalized = json.dumps(frames, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    return {
        "incident_id": body.get("incident", header.get("incident_id")),
        "procName": body.get("procName"),
        "pid": body.get("pid"),
        "procLaunch": body.get("procLaunch"),
        "captureTime": body.get("captureTime"),
        "coalitionName": body.get("coalitionName"),
        "exception": body.get("exception"),
        "faultingThread_index": body["faultingThread"],
        "faultingThread_name": thread.get("name"),
        "host_osVersion_from_ips": body.get("osVersion"),
        "frame_count": len(frames),
        "normalized_frames_sha256": hashlib.sha256(normalized).hexdigest(),
        "stack_family_symbols": [frame["symbol"] for frame in frames if any(
            token in (frame["symbol"] or "") for token in (
                "MTLReportFailure", "_MTLMessageContext", "validateWithDevice",
                "MTLSimDevice", "MetalContext::create_surface", "FlattenNode::retain_surface",
            ))],
    }


def census(inputs_path, inventory_path, source_audit_path):
    inputs = json.loads(inputs_path.read_text())
    inventory = json.loads(inventory_path.read_text())
    verified = {}
    for record in evidence_files(inputs):
        path = Path(record["path"])
        if not path.is_file():
            raise ValueError(f"Retained evidence is missing: {path}")
        actual = digest(path)
        if actual != record["sha256"]:
            raise ValueError(f"Retained evidence hash changed: {path}")
        if "bytes" in record and path.stat().st_size != record["bytes"]:
            raise ValueError(f"Retained evidence size changed: {path}")
        verified[str(path)] = actual

    rows, incidents = [], {}
    for entry in inputs["inputs"]:
        crash = None
        if entry.get("crash"):
            ips = next(Path(item["path"]) for item in entry["evidence"] if item["path"].endswith(".ips"))
            crash = read_ips(ips)
            expected = entry["crash"]
            for key in ("incident_id", "procName", "pid", "procLaunch", "frame_count", "normalized_frames_sha256"):
                if crash[key] != expected[key]:
                    raise ValueError(f"IPS assertion changed for {entry['id']}: {key}")
            incident = crash["incident_id"] or digest(ips)
            incidents.setdefault(incident, crash | {"cohort": entry["cohort"]["udid"]})

        scene = entry["scene_window"]
        app = entry["app"]
        row = {
            "id": entry["id"], "kind": entry["kind"],
            "native_execution_gate": entry.get("native_execution_gate", entry.get("status")),
            "qualification_gate": entry.get("qualification_gate"),
            "duration_seconds": entry.get("duration_seconds"),
            "cohort": entry["cohort"], "app": app,
            "transition": entry["transition"], "recording": entry["recording"],
            "scene_window": {
                "distinct_lifecycle_scene_ids": scene.get("observed_scene_ids"),
                "distinct_lifecycle_scene_count": scene.get("observed_scene_count"),
                "active_scene_count": None,
                "window_count": scene.get("observed_window_count"),
                "basis": scene.get("scene_count_basis", scene.get("unknown_reason")),
            },
            "crash": crash,
            "evidence_count": len(entry.get("evidence", [])),
            "rendering_source_binding": None,
        }
        if app["source"] == "3a0a01f1af05dd3fea75d5a1499c762d039c478a":
            row["rendering_source_binding"] = {
                "family": "ComposeUIViewController", "cmp_ui": "1.13.0-beta02",
                "skiko_inspected": "0.156.1", "supported_backend_contract": "METAL",
                "opaque_default": True, "parallelRendering_default": True,
                "app_override": "OnFocusBehavior.DoNothing",
                "actual_active_renderer_sampled": False,
            }
        rows.append(row)

    actors = Counter(crash["procName"] for crash in incidents.values())
    stacks = Counter(crash["normalized_frames_sha256"] for crash in incidents.values())
    continuity = inputs["actor_continuity"]
    later = next(crash for crash in incidents.values() if crash["pid"] == continuity["later_abort_pid"])
    if later["procLaunch"] != continuity["later_procLaunch"]:
        raise ValueError("Retained service continuity launch time changed")
    availability = inventory["ios27_1"]
    return {
        "schema_version": 1,
        "inputs_sha256": digest(inputs_path), "inventory_sha256": digest(inventory_path),
        "source_audit_sha256": digest(source_audit_path),
        "scope": "Offline hash verification and IPS parsing; no live/device commands.",
        "premise_to_question": inputs["premise_to_question"],
        "rows": rows, "unique_incident_count": len(incidents),
        "incident_deduplication": "incident_id, falling back to IPS SHA256; copied reports are not new incidents",
        "crash_actor_counts": dict(sorted(actors.items())),
        "normalized_stack_counts": dict(sorted(stacks.items())),
        "positive_selected_controls": sum(row["native_execution_gate"] in ("PASS", "NATIVE_PASS") for row in rows),
        "actor_continuity": continuity,
        "verified_evidence_files": len(verified),
        "verified_evidence": dict(sorted(verified.items())),
        "runtime27_1": availability,
        "common_supported_ios27_0_and27_1_device_types": inventory["common_supported_ios27_0_and27_1_device_types"],
        "limits": [
            "IPS osVersion is the host OS; target iOS runtime comes from retained XCTest/cohort evidence.",
            "Distinct lifecycle scene IDs are not simultaneous connected/active scene or window counts.",
            "Absent video evidence leaves recording unknown; it does not establish recording disabled.",
            "Source defaults/backend contracts do not measure the actual active renderer or texture lifetime.",
            "Service continuity does not identify a leak, trigger or exclusive cause.",
            "Selected native PASS controls retain PARTIAL suite qualification where recorded.",
            "Runtime27.1 supports only iPhone Duo here; changing to it also changes model/geometry.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, default=DEFAULT / "inputs.json")
    parser.add_argument("--inventory", type=Path, default=DEFAULT / "inventory.json")
    parser.add_argument("--source-audit", type=Path, default=DEFAULT / "source-audit.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true", help="Compare with the committed census; do not write it")
    args = parser.parse_args()
    result = census(args.inputs, args.inventory, args.source_audit)
    payload = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if (DEFAULT / "census.json").read_text() != payload:
            raise SystemExit("Committed census differs from retained inputs")
        print(f"CENSUS_PASS {result['unique_incident_count']} incidents, {result['verified_evidence_files']} verified files")
    elif args.output:
        args.output.write_text(payload)
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
