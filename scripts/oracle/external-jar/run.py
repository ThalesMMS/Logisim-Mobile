#!/usr/bin/env python3
"""Compare an Evolution JAR library run through the companion's isolated path with Evolution.

    python3 scripts/oracle/external-jar/run.py EVOLUTION_JAR --out docs/parity-evidence/<dir>

Builds companion/tests/fixtures/DriveLibrary.java against EVOLUTION_JAR, then applies the same
input frames to companion/tests/fixtures/jar-drive-contention.circ twice:

* Mobile: the companion's approved "jar-run" operation (macOS seatbelt sandbox, JarCircuitRunner,
  the uploaded JAR in its own class loader), driven through CompanionService as the app does;
* Evolution: EvolutionJarOracle.java, which opens the project with Evolution's own Loader so the
  "jar#external/drive.jar#..." library goes through Evolution's JAR loading.

Needs macOS and JDK 21 (JAVA_HOME or scripts/java-home.sh). Writes logs/ and results.json.
"""
from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import itertools
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
FIXTURES = ROOT / "companion/tests/fixtures"
CLASS = "dev.logisim.fixture.DriveLibrary"
LABELS = ("A", "EA", "B", "EB")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def frames():
    """Every driven value and enable on both drivers: 4 x 3 x 4 x 3 = 144 frames."""
    return [dict(zip(LABELS, combo)) for combo in itertools.product("01?E", "01?", "01?E", "01?")]


def mobile(project: bytes, library: bytes, java: str, evolution_jar: Path, items) -> list[str]:
    os.environ["LOGISIM_COMPANION_EVOLUTION_JAR"] = str(evolution_jar)
    from companion.server import CompanionService, Configuration, default_registry
    config = Configuration("synthetic-authentication-for-external-jar-oracle", {"jar-run"}, tool_paths={"jar-java": java})
    host = CompanionService(config, default_registry())
    try:
        uploaded = lambda name, data: {"name": name, "sha256": sha256(data), "base64": base64.b64encode(data).decode()}
        request = {"operation": "jar-run", "inputs": [uploaded("project.circ", project), uploaded("library.jar", library)],
                   "parameters": {"libraryClass": CLASS, "frames": items}}
        plan = host.plan(request)
        if plan["osSandbox"] != "macos-seatbelt-v1":
            raise SystemExit(f"unexpected sandbox {plan['osSandbox']}")
        job = host.submit({**request, "planToken": plan["planToken"]})
        deadline = time.monotonic() + 120
        while job["state"] in {"queued", "running"} and time.monotonic() < deadline:
            time.sleep(0.05)
            job = host.poll(job["jobId"])
        if job["state"] != "succeeded":
            raise SystemExit(f"companion jar-run {job['state']}: {job['error']}")
        result = job["result"]
        return [f"{index}\t" + "\t".join(f"{label}={frame[label]}" for label in sorted(frame))
                for index, frame in enumerate(result["frames"])], plan["osSandbox"], result["librarySha256"]
    finally:
        host.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("evolution_jar", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    java_home = os.environ.get("JAVA_HOME") or subprocess.run(
        ["sh", "-c", ". scripts/java-home.sh && printf %s \"$JAVA_HOME\""], cwd=ROOT, text=True, capture_output=True).stdout
    java_home = str(Path(java_home).resolve())
    java, javac, jar_tool = (f"{java_home}/bin/{tool}" for tool in ("java", "javac", "jar"))
    evolution_jar = args.evolution_jar.resolve()
    out = args.out.resolve()
    (out / "logs").mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="oracle-external-jar-"))
    try:
        classes = work / "classes"
        subprocess.run([javac, "-cp", str(evolution_jar), "-d", str(classes), str(FIXTURES / "DriveLibrary.java")], check=True, timeout=120)
        library_path = work / "drive.jar"
        subprocess.run([jar_tool, "cf", str(library_path), "-C", str(classes), "."], check=True, timeout=60)
        library = library_path.read_bytes()
        project = (FIXTURES / "jar-drive-contention.circ").read_bytes()
        items = frames()

        mobile_lines, sandbox, library_sha = mobile(project, library, java, evolution_jar, items)

        desktop = work / "desktop"
        (desktop / "external").mkdir(parents=True)
        shutil.copy(library_path, desktop / "external/drive.jar")
        (desktop / "project.circ").write_bytes(project)
        frames_tsv = desktop / "frames.tsv"
        frames_tsv.write_text("".join("\t".join(f"{label}={frame[label]}" for label in LABELS) + "\n" for frame in items))
        result = subprocess.run([java, "-Djava.awt.headless=true", f"-Duser.home={work / 'home'}", "--class-path", str(evolution_jar),
                                 str(HERE / "EvolutionJarOracle.java"), str(desktop / "project.circ"), str(frames_tsv)],
                                text=True, capture_output=True, timeout=120)
        if result.returncode:
            raise SystemExit(f"Evolution oracle failed:\n{result.stderr[-3000:]}")
        desktop_lines = [line for line in result.stdout.splitlines() if line[:1].isdigit()]

        differences = [(i, frame, m, d) for i, (frame, m, d) in enumerate(itertools.zip_longest(items, mobile_lines, desktop_lines)) if m != d]
        log = [f"{'SAME' if m == d else 'DIFF'} {' '.join(f'{k}={v}' for k, v in frame.items())} -> mobile {m} | evolution {d}"
               for frame, m, d in zip(items, mobile_lines, desktop_lines)]
        summary = (("PASS" if not differences and len(mobile_lines) == len(items) else "FAIL")
                   + f": {len(items) - len(differences)}/{len(items)} frames equal between the companion's isolated jar-run and Evolution's own JAR loader")
        (out / "logs/external-jar.txt").write_text("\n".join(log + [summary]) + "\n")
        print(summary)
        receipt = {
            "issue": "https://github.com/ThalesMMS/logisim-mobile-dev/issues/349",
            "mobile_revision": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, capture_output=True).stdout.strip(),
            "mobile_dirty": bool(subprocess.run(["git", "status", "--porcelain", "--", "companion", "scripts/oracle/external-jar"],
                                                cwd=ROOT, text=True, capture_output=True).stdout.strip()),
            "evolution_jar_sha256": sha256(evolution_jar.read_bytes()),
            "library_class": CLASS, "library_sha256": library_sha, "project_sha256": sha256(project),
            "os_sandbox": sandbox, "frames": len(items), "differences": len(differences),
            "environment": {"os": platform.platform(), "java": subprocess.run([java, "-version"], text=True, capture_output=True).stderr.splitlines()[0],
                            "python": platform.python_version()},
            "date": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "summary": summary,
        }
        (out / "results.json").write_text(json.dumps(receipt, indent=2) + "\n")
        sys.exit(1 if differences else 0)
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
