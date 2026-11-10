#!/usr/bin/env python3
"""Run six frozen offline oracles against the copied MAIN6cef iOS evidence."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys


ROOT = Path(__file__).resolve().parent
PRODUCT = "6cef05429cf66be23569aaebc3c72950cf3fe53a"
CORE = "7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20"
DESKTOP = "0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--core-jar", required=True, type=Path)
parser.add_argument("--desktop-jar", required=True, type=Path)
parser.add_argument("--output-dir", required=True, type=Path)
args = parser.parse_args()
core, desktop = args.core_jar.resolve(), args.desktop_jar.resolve()
assert sha(core) == CORE and sha(desktop) == DESKTOP, "Reference JAR fingerprint mismatch"
output = args.output_dir.resolve()
assert not output.exists(), "Use a new output directory to preserve previous reports"
protected = [ROOT / name for name in ("native", "original-oracles", "preparation-only", "preflight",
                                      "producer-metadata", "oracle-source")]
assert not any(path == output or path in output.parents for path in protected)
chain = json.loads((ROOT / "copy-chain.json").read_text())
for item in chain["copiedFiles"]:
    relative = PurePosixPath(item["path"])
    assert not relative.is_absolute() and ".." not in relative.parts
    path = ROOT / item["path"]
    assert path.is_file() and not path.is_symlink()
    assert path.stat().st_size == item["bytes"] and sha(path) == item["sha256"], item["path"]

output.mkdir(parents=True)
jobs = []
receipts = {}
cli = ROOT / "oracle-source/scripts/verify-portable53.py"
for mode in ("bundle", "folder"):
    native = ROOT / "native" / mode
    evidence = native / "documents" / f"portable-{mode}-evidence"
    receipt_path = evidence / "ui-receipt.json"
    receipt = json.loads(receipt_path.read_text())
    result = json.loads((native / "results.json").read_text())
    assert receipt["status"] == "PASSED" and receipt["sourcePin"] == PRODUCT
    assert result["native_execution_gate"] == "NATIVE_PASS" and result["execution"]["exit_code"] == 0
    receipts[mode] = {"path": str(receipt_path), "sha256": sha(receipt_path),
                      "nativeGate": result["native_execution_gate"]}
    base = [sys.executable, str(cli), "--core-jar", str(core), "--desktop-jar", str(desktop)]
    if mode == "folder":
        base.append("--allow-no-opaque")
    snapshots = [item for stage in ("before", "imported", "restarted", "reopened")
                 for item in ("--workspace-" + stage, str(evidence / receipt["workspaceSnapshots"][stage]))]
    jobs.append({"name": mode + "-storage", "mode": mode, "expected": "STORAGE_SNAPSHOTS_VALIDATED",
                 "args": base + snapshots})
    exports = {item["afterRestart"]: evidence / item["path"] for item in receipt["exports"]}
    assert set(exports) == {False, True}
    for after, label in ((False, "before"), (True, "after")):
        jobs.append({"name": mode + "-desktop-" + label, "mode": mode,
            "expected": "APP_EXPORT_ARTIFACT_VALIDATED",
            "args": base + ["--origin", "app-export", "--artifact", str(exports[after]),
                "--peer", str(exports[not after]), "--ui-receipt", str(receipt_path)]})
for job in jobs:
    job["report"] = str(output / (job["name"] + ".json"))
    job["log"] = str(output / (job["name"] + ".log"))
    job["args"] += ["--output", job["report"]]
(output / "commands.json").write_text(json.dumps(jobs, indent=2) + "\n")
environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")


def run(job):
    with Path(job["log"]).open("wb") as log:
        process = subprocess.run(job["args"], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    report = json.loads(Path(job["report"]).read_text()) if Path(job["report"]).is_file() else {}
    passed = process.returncode == 0 and report.get("status") == job["expected"]
    return {"name": job["name"], "exitCode": process.returncode, "status": report.get("status"),
            "passed": passed, "report": job["report"], "log": job["log"],
            "reportSha256": sha(Path(job["report"])) if report else None,
            "logSha256": sha(Path(job["log"]))}


with ThreadPoolExecutor(max_workers=3) as workers:
    results = list(workers.map(run, jobs))
passed = all(item["passed"] for item in results)
summary = {"status": "SIX_OFFLINE_ORACLES_PASSED" if passed else "OFFLINE_ORACLE_FAILURE",
    "productSource": PRODUCT, "coreJarSha256": CORE, "desktopJarSha256": DESKTOP,
    "nativeReceipts": receipts, "results": results,
    "scope": "Offline replay only. The two original native gates are retained observations; this command performs no native execution."}
(output / "replay-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps({"status": summary["status"], "results": results}, indent=2))
raise SystemExit(0 if passed else 1)
