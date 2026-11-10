"""Pure native-receipt checks shared by the offline verifier tests and CLI."""
import hashlib
import json
from pathlib import Path
import re


def validate_ui_exports(receipt_path, artifact, peer, fixture_root):
    paths = [("artifact", Path(artifact).resolve())]
    if peer is None:
        raise ValueError("app-export verification requires --peer from the same native run")
    paths.append(("peer", Path(peer).resolve()))
    if paths[0][1] == paths[1][1]:
        raise ValueError("artifact and peer must be distinct export files")
    for role, path in paths:
        if Path(fixture_root).resolve() in path.parents:
            raise ValueError(f"checked-in input fixtures cannot be labelled as app-export artifacts ({role})")
        if not path.is_file():
            raise ValueError(f"the {role} export file does not exist")
    if paths[0][1].samefile(paths[1][1]):
        raise ValueError("artifact and peer must be distinct export files, not aliases")
    receipt_path = Path(receipt_path).resolve()
    receipt = json.loads(receipt_path.read_text())
    if not isinstance(receipt, dict) or receipt.get("executedViaUi") is not True or receipt.get("status") != "PASSED" or receipt.get("platform") not in ("android", "ios"):
        raise ValueError("the native receipt does not record a passed Android/iOS UI execution")
    if not re.fullmatch(r"[0-9a-f]{64}", receipt.get("appArtifactSha256", "")):
        raise ValueError("the native receipt must fingerprint the application artifact")
    evidence = receipt.get("evidence", [])
    if not evidence or not all(isinstance(path, str) and (receipt_path.parent / path).is_file() for path in evidence):
        raise ValueError("the native receipt must point to captured UI evidence files")
    entries = receipt.get("exports", [])
    if not isinstance(entries, list):
        raise ValueError("the native receipt exports must be a list")
    checked = []
    for role, path in paths:
        matching = [entry for entry in entries if isinstance(entry, dict) and isinstance(entry.get("path"), str) and
                    (receipt_path.parent / entry["path"]).resolve() == path]
        if len(matching) != 1:
            raise ValueError(f"the {role} export path must appear exactly once in the same native receipt")
        entry = matching[0]
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if entry.get("sha256") != digest:
            raise ValueError(f"the {role} export hash does not match the native receipt")
        if type(entry.get("afterRestart")) is not bool:
            raise ValueError(f"the {role} export must declare its before/after restart stage")
        checked.append({"role": role, "path": str(path), "sha256": digest, "afterRestart": entry["afterRestart"],
                        "desktopChecked": role == "artifact"})
    if {entry["afterRestart"] for entry in checked} != {False, True}:
        raise ValueError("artifact and peer must have opposite before/after restart stages")
    return receipt, checked
