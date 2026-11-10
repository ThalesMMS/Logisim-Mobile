#!/usr/bin/env python3
"""Create a source archive and provenance manifest for an exact committed revision."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--output", type=Path, default=Path("outputs/source-release"))
    parser.add_argument("--binary", type=Path, action="append", default=[])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    commit = git(root, "rev-parse", "--verify", f"{args.ref}^{{commit}}")
    if git(root, "status", "--porcelain", "--untracked-files=no"):
        parser.error("Commit tracked changes before creating corresponding source.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"logisim-mobile-source-{commit[:12]}.tar.gz"
    subprocess.run(["git", "-C", str(root), "archive", "--format=tar.gz",
                    f"--prefix=logisim-mobile-{commit[:12]}/", "--output", str(archive), commit], check=True)
    artifacts = []
    for binary in args.binary:
        binary = binary.resolve(strict=True)
        artifacts.append({"file": binary.name, "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()})
    manifest = {"schemaVersion": 1, "sourceCommit": commit,
                "repository": "https://github.com/ThalesMMS/Logisim-Mobile",
                "license": "GPL-3.0-only", "sourceArchive": archive.name,
                "sourceArchiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "binaries": artifacts,
                "buildInstructions": "README.md", "licenseInventory": "legal/runtime-dependencies.json"}
    (output / f"source-manifest-{commit[:12]}.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(archive)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
