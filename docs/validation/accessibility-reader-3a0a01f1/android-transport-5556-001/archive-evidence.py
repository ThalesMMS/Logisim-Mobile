#!/usr/bin/env python3
"""Freeze local observations without APK binaries or derived video frames."""

import gzip
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[4]
DESTINATION = Path(__file__).resolve().parent
DIRECTORIES = (
    "android-talkback-preflight-5556-001",
    "android-talkback-observation-5556-001",
    "android-talkback-observation-5556-002",
    "android-talkback-ui-5556-001",
    "android-talkback-final-5556-001",
    "android-talkback-videos-5556-001",
    "android-reader-transport-readonly-5556-001",
)

files = []
for directory in DIRECTORIES:
    source = ROOT / "outputs" / directory
    if not source.is_dir():
        raise SystemExit(f"Missing original observations: {source}")
    files.extend(
        path for path in source.rglob("*")
        if path.is_file() and path.suffix != ".apk"
        and "segment003-frames" not in path.parts
    )
files.sort()
archive = DESTINATION / "observations.tar.gz"
manifest = []
with archive.open("wb") as raw:
    with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as tar:
            for path in files:
                name = path.relative_to(ROOT).as_posix()
                data = path.read_bytes()
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                entry.mode = 0o644
                entry.mtime = 0
                with path.open("rb") as stream:
                    tar.addfile(entry, stream)
                manifest.append({"path": name, "bytes": len(data),
                                 "sha256": hashlib.sha256(data).hexdigest()})

receipt = {
    "result": "FAIL_NOT_QUALIFIED",
    "archive": archive.name,
    "archive_bytes": archive.stat().st_size,
    "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
    "scope": "One stopped Android reader transport unit and public restoration. No product defect attribution.",
    "excluded": ["Installed APK copies, identified by preflight/final SHA-256", "Derived PNG frames, reproducible from the retained video"],
    "files": manifest,
}
(DESTINATION / "archive-manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps({key: receipt[key] for key in ("result", "archive_bytes", "archive_sha256")}))
