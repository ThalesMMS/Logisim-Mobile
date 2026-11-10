#!/usr/bin/env python3
"""Record application identity, source state and hashes for release artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

try:
    from .generate_app_identity import ROOT, read_identity
except ImportError:
    from generate_app_identity import ROOT, read_identity


def artifact_record(path: Path) -> dict:
    if not path.is_file():
        raise ValueError(f"Artifact is not a file: {path}")
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"name": path.name, "size": path.stat().st_size, "sha256": digest}


def create_metadata(root: Path, platform: str, artifacts: list[Path]) -> dict:
    identity = read_identity(root / "app.properties")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)
    names = [path.name for path in artifacts]
    if len(names) != len(set(names)):
        raise ValueError("Artifact names must be unique")
    return {"application": identity.name, "platform": platform,
            "identifier": identity.android_id if platform == "android" else identity.apple_id,
            "version": identity.version, "build": identity.build,
            "sourceCommit": commit, "sourceDirty": bool(status.strip()),
            "artifacts": [artifact_record(path) for path in artifacts]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True, choices=("android", "ios"))
    parser.add_argument("--artifact", action="append", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    options = parser.parse_args()
    try:
        metadata = create_metadata(ROOT, options.platform, options.artifact)
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(json.dumps(metadata, indent=2) + "\n")
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
