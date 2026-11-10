#!/usr/bin/env python3
"""Build a small host executable and exercise only fixture storage in temporary directories."""
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent

if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="logisim-revocation-checks-") as build:
        executable = Path(build) / "fixture-store-checks"
        subprocess.run(["xcrun", "swiftc", str(ROOT / "Shared/FixtureErrorDiagnostic.swift"),
                        str(ROOT / "Shared/FixtureStore.swift"),
                        str(ROOT / "HostChecks.swift"), "-o", str(executable)], check=True, timeout=60)
        subprocess.run([str(executable)], check=True, timeout=30)
