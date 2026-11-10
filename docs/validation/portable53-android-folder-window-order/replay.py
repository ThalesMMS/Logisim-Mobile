#!/usr/bin/env python3
"""Read the three frozen TARs and the partial export without contacting a device."""
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parent
RAW = ROOT / "failed-13849"
ACTIVE = "72be893d7f51138afdb9f99380ea7957"
OLD = "f33b7bd5cea6be9a6459411a64b31c74"
SESSION = "36d6551b-a976-41f1-95e3-1a0dabfb9124"


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


# Reuse only the pure envelope/token readers from the exact frozen scenario.
source = RAW / "source-android_portable_scenarios.py"
assert sha(source.read_bytes()) == "38611e7a471296b1b0d8064bad85d917671b587364ee5784a8eae27bc8487004"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("frozen_portable13849", source)
frozen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(frozen)


def snapshot(name):
    path = RAW / name
    files = {}
    seen = set()
    with tarfile.open(path) as archive:
        for member in archive.getmembers():
            parts = PurePosixPath(member.name).parts
            assert member.name == "/".join(parts) and not member.name.startswith("/")
            assert not any(part in (".", "..") for part in parts)
            folded = member.name.casefold()
            assert folded not in seen, "Duplicate or aliased TAR path"
            seen.add(folded)
            if member.isdir():
                assert member.name in {"files", "files/workspace", "shared_prefs"}
                continue
            assert member.isfile() and 0 <= member.size <= 100 * 1024 * 1024
            allowed = len(parts) == 3 and parts[:2] == ("files", "workspace")
            allowed |= member.name in {"files/profileInstalled", "shared_prefs/editor-preferences.xml",
                                      "shared_prefs/document-links.xml"}
            assert allowed, "Unexpected TAR member"
            files[member.name] = archive.extractfile(member).read()
    tokens = frozen._tokens(files["files/workspace/catalog.workspace"], "CATALOG", "1")
    active = next(tokens)
    assert next(tokens) in ("true", "false")
    infos = [frozen._info(tokens) for _ in range(int(next(tokens)))]
    frozen._finished(tokens)
    info = next(item for item in infos if item["id"] == active and not item["deleted"])
    payload = f"files/workspace/project-{active}-{info['revision']}.workspace"
    saved = frozen._info(frozen._tokens(files[payload], "PROJECT", "4"))
    assert saved["id"] == active and saved["revision"] == info["revision"]
    stable, pending = {}, {}
    for filename, raw in files.items():
        if not filename.startswith("files/workspace/window-"):
            continue
        target = stable if filename.endswith(".workspace-link") else pending if filename.endswith(".workspace-link.new") else None
        if target is not None:
            link = frozen._tokens(raw, "LINK", "1", link=True)
            session, project = next(link), next(link)
            frozen._finished(link)
            assert session not in target
            target[session] = project
    return {"file": name, "sha256": sha(path.read_bytes()), "activeProject": info,
            "catalogProjectIds": sorted(item["id"] for item in infos),
            "stableWindows": stable, "pendingWindows": pending}


snapshots = [snapshot(name) for name in ("portable-folder-imported-workspace.tar",
    "portable-folder-storage-roundtrip-private-files.tar", "failure-private-files.tar")]
assert all(item["activeProject"]["id"] == ACTIVE for item in snapshots)
assert snapshots[0]["activeProject"]["revision"] == 1
assert snapshots[0]["stableWindows"][SESSION] == OLD
assert snapshots[0]["pendingWindows"][SESSION] == ACTIVE
for item in snapshots[1:]:
    assert item["activeProject"]["revision"] == 2
    assert item["stableWindows"][SESSION] == ACTIVE
    assert not item["pendingWindows"]
assert snapshots[1]["sha256"] == snapshots[2]["sha256"]

operation = json.loads((RAW / "portable-folder-before-restart-create-operation.json").read_text())
assert operation["session"] == SESSION
assert operation["file"] == f"asset-{SESSION}-create.json"
assert operation["operation"]["mime"] == "application/x-logisim-bundle"
exports = list(RAW.glob("*-folder-before-restart.lsebdl"))
assert len(exports) == 1
export = exports[0]
raw = export.read_bytes()
assert len(raw) == 4905 and raw.startswith(b"PK\x03\x04")
with zipfile.ZipFile(export) as archive:
    assert archive.testzip() is None
    entries = archive.namelist()
    assert len(entries) == len(set(entries))
assert not list(RAW.glob("*-folder-after-restart.lsebdl"))
assert not list(RAW.glob("portable-*-receipt.json"))
results = json.loads((RAW / "results.json").read_text())
assert results == [{"scenario": "portable-folder-storage-roundtrip", "status": "failed",
                   "error": "The actual export session is not linked to the imported workspace"}]
print(json.dumps({"status": "FAILED_WINDOW_ORDER_REPLAYED", "snapshots": snapshots,
    "createOperation": operation, "partialAppExport": {"file": export.name,
        "bytes": len(raw), "sha256": sha(raw), "zipCrcsValid": True, "entries": entries},
    "scope": "Read-only replay of the failed run. No restart, peer export, strict artifact qualification or desktop reopen is claimed."},
    indent=2))
