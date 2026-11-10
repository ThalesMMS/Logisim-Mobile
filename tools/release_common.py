"""Shared preflight checks for explicit distribution builds."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess

from .generate_app_identity import ROOT, read_identity


class ReleaseError(Exception):
    pass


def required_environment(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ReleaseError(f"Set {name} before preparing a distribution build")
    return value


def required_file(name: str, *, secret: bool = False) -> Path:
    path = Path(required_environment(name)).expanduser().resolve()
    if not path.is_file():
        raise ReleaseError(f"{name} must name an existing file")
    if secret and not path.read_bytes().rstrip(b"\r\n"):
        raise ReleaseError(f"{name} must not be empty")
    return path


def executable(name: str) -> str:
    value = shutil.which(name)
    if value is None:
        raise ReleaseError(f"Install {name} before preparing this distribution build")
    return value


def clean_source() -> str:
    state = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    if state.strip():
        raise ReleaseError("Commit source changes before creating distribution artifacts")
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


def generated_identity() -> None:
    for script in ("generate_app_identity.py", "generate_app_icons.py"):
        result = subprocess.run([executable("python3"), str(ROOT / "tools" / script), "--check"], cwd=ROOT)
        if result.returncode:
            raise ReleaseError("Regenerate application identity and icons before building")


def run(command: list[str], *, log: Path | None = None) -> None:
    if log is None:
        result = subprocess.run(command, cwd=ROOT)
    else:
        with log.open("ab") as stream:
            result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise ReleaseError(f"{Path(command[0]).name} failed with exit code {result.returncode}")


def output_directory(platform: str) -> Path:
    identity = read_identity(ROOT / "app.properties")
    output = ROOT / "outputs" / "releases" / platform / f"{identity.version}-{identity.build}"
    output.mkdir(parents=True, exist_ok=True)
    return output
