#!/usr/bin/env python3
"""Recheck this source-only unit without building or opening the app."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

BASE = "199fafaecee12bc1112909b392e1633a7eff51be"
EDITOR = "scripts/ios_e2e_ui/EditorEndToEndTests.swift"
IMMUTABLE = (
    "scripts/ios_native_ui/NativeUIHarness.swift",
    "scripts/ios_native_ui/NativeTextReplacement.swift",
    "fixtures/user-scenarios/editor-actions-v1.json",
    "scripts/ios_e2e_ui/project.yml",
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--serial-lock", type=Path, default=Path("/tmp/logisim-gradle-serial.py"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)

    def at_base(path):
        return subprocess.check_output(["git", "show", f"{BASE}:{path}"], cwd=root)

    snapshots = {}

    def snapshot(path):
        relative = str(path.relative_to(root))
        if relative not in snapshots:
            snapshots[relative] = path.read_bytes()
        return snapshots[relative]

    snapshot(Path(__file__).resolve())
    source_bytes = snapshot(root / EDITOR)
    source = source_bytes.decode()
    base = at_base(EDITOR).decode()
    current_start = source.index("    private enum ExplorerHeaderTarget:")
    current_end = source.index("    private func closeExplorer()", current_start)
    old_start = base.index("    private func revealExplorerSearch()")
    old_end = base.index("    private func closeExplorer()", old_start)
    normalized = source[:current_start] + base[old_start:old_end] + source[current_end:]
    new_calls = "        _ = try revealExplorerHeader(.components)\n        let search = try revealExplorerHeader(.search)"
    old_calls = '        try click("a11y_explorer_components", region: "a11y-circuit-list")\n        let search = try revealExplorerSearch()'
    assert normalized.count(new_calls) == 1
    assert normalized.replace(new_calls, old_calls) == base, "Outside-private source changed"
    geometry_start = "    private func explorerSearchGeometry"
    geometry = source[source.index(geometry_start):current_start]
    assert geometry == base[base.index(geometry_start):old_start], "Geometry changed"
    probe = root / "docs/validation/ios-editor36-search-reveal/geometry-probe.swift"
    probe_bytes = snapshot(probe)
    assert probe_bytes == at_base(str(probe.relative_to(root))), "Literal geometry probe changed"
    assert geometry.replace("    private func", "    func", 1).strip() in probe_bytes.decode()
    immutable = {}
    for path in IMMUTABLE:
        data = snapshot(root / path)
        assert data == at_base(path), f"Immutable file changed: {path}"
        immutable[path] = sha(data)

    names = re.findall(r"^\s+- ([A-Za-z0-9_]+\.swift)\s*$",
                       snapshot(root / "scripts/ios_e2e_ui/project.yml").decode(), re.MULTILINE)
    assert len(names) == len(set(names)) == 17
    sources = []
    for name in names:
        folder = "ios_native_ui" if name in {"NativeUIHarness.swift", "NativeTextReplacement.swift"} else "ios_e2e_ui"
        path = root / "scripts" / folder / name
        if name != "EditorEndToEndTests.swift":
            assert snapshot(path) == at_base(str(path.relative_to(root))), f"Other target source changed: {name}"
        sources.append(path)
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], text=True).strip()
    platform = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-platform-path"], text=True).strip()
    assert args.serial_lock.is_file(), "Shared compiler lock wrapper is required"
    commands = {
        "geometry": ["xcrun", "swift", str(probe)],
        "typecheck": ["xcrun", "swiftc", "-typecheck", "-swift-version", "5", "-target",
                      "arm64-apple-ios17.0-simulator", "-sdk", sdk, "-F",
                      str(Path(platform) / "Developer/Library/Frameworks"), "-I",
                      str(Path(platform) / "Developer/usr/lib"), *map(str, sources)],
    }
    results = {}
    for name, command in commands.items():
        with (output / f"{name}.log").open("wb") as log:
            result = subprocess.run([sys.executable, str(args.serial_lock), *command],
                                    cwd=root, stdout=log, stderr=subprocess.STDOUT)
        results[name] = result.returncode
    assert all((root / path).read_bytes() == data for path, data in snapshots.items()), "Input changed during replay"
    receipt = {
        "base": BASE, "status": "PASS" if all(code == 0 for code in results.values()) else "FAIL",
        "sourceSHA256": sha(source_bytes), "outsidePrivateDeltaByteIdentical": True,
        "geometryByteIdenticalToE835": True, "geometryProbeCases": 15,
        "literalGeometryProbeByteIdentical": True, "inputsByteIdenticalBeforeAfter": True,
        "geometryProbeExitCode": results["geometry"], "sdkSources": len(sources),
        "sdkExitCode": results["typecheck"], "nativeStatus": "NOT_RUN",
        "otherTargetSourcesByteIdentical": True,
        "unchangedFiles": immutable,
        "sdkSourceSHA256": {str(path.relative_to(root)): sha(snapshot(path)) for path in sources},
        "inputSHA256": {path: sha(data) for path, data in snapshots.items()},
    }
    (output / "validation.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({key: receipt[key] for key in ("status", "sourceSHA256", "sdkSources", "nativeStatus")}))
    return 0 if receipt["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
