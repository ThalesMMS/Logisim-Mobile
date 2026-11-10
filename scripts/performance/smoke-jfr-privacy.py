#!/usr/bin/env python3
"""Plan, or explicitly execute, one source-launcher JVM privacy smoke. No core workload."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java", type=Path, required=True)
parser.add_argument("--output-dir", type=Path, required=True)
parser.add_argument("--expected-source", required=True)
parser.add_argument("--execute", action="store_true", help="Run exactly one JVM; default only prints its plan")
args = parser.parse_args()
java = args.java.resolve(strict=True)
output = args.output_dir.resolve()
runner = ROOT / "scripts/benchmark-circuits.py"
profile = ROOT / "scripts/performance/benchmark-profile.jfc"
source = ROOT / "scripts/performance/JfrPrivacySmoke.java"
sources = [runner, profile, source, Path(__file__).resolve()]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identity():
    return {str(path.relative_to(ROOT)): digest(path) for path in sources} | {"java_launcher": digest(java)}


tree = ast.parse(runner.read_text())
allowlist = ast.literal_eval(next(node.value for node in tree.body if isinstance(node, ast.Assign)
    and any(isinstance(target, ast.Name) and target.id == "JAVA_ENVIRONMENT_KEYS" for target in node.targets)))
# This parent fixture is wholly declarative. It never reads inherited credentials or environment values.
parent_environment = {"PATH": str(java.parent) + ":/usr/bin:/bin", "JAVA_HOME": str(java.parent.parent),
    "LANG": "C", "LC_ALL": "C", "LC_CTYPE": "UTF-8", "TZ": "UTC", "TMPDIR": str(output / "tmp"),
    "GITHUB_TOKEN": "FAKE_LOGISIM_PRIVACY_GITHUB_20261105",
    "AWS_SECRET_ACCESS_KEY": "FAKE_LOGISIM_PRIVACY_AWS_20261105",
    "UNEXPECTED_ENVIRONMENT": "FAKE_LOGISIM_PRIVACY_UNKNOWN_20261105",
    "JAVA_TOOL_OPTIONS": "-Dlogisim.privacy.tool=FAKE_LOGISIM_PRIVACY_TOOL_20261105",
    "_JAVA_OPTIONS": "-Dlogisim.privacy.private=FAKE_LOGISIM_PRIVACY_PRIVATE_20261105",
    "JDK_JAVA_OPTIONS": "-Dlogisim.privacy.jdk=FAKE_LOGISIM_PRIVACY_JDK_20261105"}
environment = {name: parent_environment[name] for name in allowlist if name in parent_environment}
sentinels = [value.partition("=")[2] if value.startswith("-D") else value
    for key, value in parent_environment.items() if key not in allowlist]
command = [str(java), "--add-modules", "jdk.jfr",
    f"-XX:StartFlightRecording=name=logisim-privacy-smoke,settings={output / profile.name},dumponexit=false",
    "--source", "21", str(output / source.name), str(output / "recording.jfr"), str(output / "java-receipt.json")]
before = identity()
plan = {"scope": "One source-launcher JVM, data-free marker and local post-stop RecordingFile decoding; no core benchmark",
    "argv": command, "source_identity": before, "expected_source": args.expected_source,
    "parent_environment_keys": sorted(parent_environment), "java_environment_keys": sorted(environment),
    "execute": args.execute, "timeout_seconds": 30}
if not args.execute:
    print(json.dumps(plan, indent=2))
    raise SystemExit(0)
if output.exists():
    parser.error("Use a new output directory; no retry or overwrite is performed")
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, env=environment, text=True).strip()
dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, env=environment, text=True).strip()
if revision != args.expected_source or dirty:
    parser.error("Source revision must match the approved clean producer")
output.mkdir(parents=True)
(output / "tmp").mkdir()
for path in [profile, source]:
    shutil.copyfile(path, output / path.name)
    if digest(output / path.name) != digest(path):
        parser.error("Frozen smoke input differs from its source")
(output / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
started = time.monotonic_ns()
failure = None
returncode = None
with (output / "run.log").open("w") as log:
    try:
        result = subprocess.run(command, cwd=output, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        returncode = result.returncode
    except subprocess.TimeoutExpired:
        failure = "timeout"
after = identity()
revision_after = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, env=environment, text=True).strip()
dirty_after = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, env=environment, text=True).strip()
same_identity = before == after and revision_after == revision and not dirty_after and all(
    digest(output / path.name) == digest(path) for path in [profile, source])
java_receipt = None
if (output / "java-receipt.json").exists():
    try:
        java_receipt = json.loads((output / "java-receipt.json").read_text())
    except json.JSONDecodeError:
        failure = failure or "invalid_java_receipt"
scanned = [path for path in [output / "recording.jfr", output / "run.log", output / "java-receipt.json"] if path.is_file()]
sentinel_bytes_absent = all(not any(value.encode() in path.read_bytes() for value in sentinels) for path in scanned)
forbidden_events = {name: 0 for name in ("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.JVMInformation")}
literal_checks = java_receipt is not None and java_receipt.get("status") == "PASS" and java_receipt.get("marker_count") == 1 \
    and java_receipt.get("forbidden_event_counts") == forbidden_events \
    and java_receipt.get("disabled_settings") == {name: True for name in forbidden_events} \
    and java_receipt.get("recording_closed_before_decode") is True \
    and java_receipt.get("sentinel_bytes_absent") is True and java_receipt.get("option_properties_absent") is True \
    and java_receipt.get("absent_environment") == {name: True for name in parent_environment if name not in allowlist}
passed = returncode == 0 and failure is None and same_identity and sentinel_bytes_absent and literal_checks
receipt = {"status": "PASS" if passed else "FAIL", "returncode": returncode, "failure": failure,
    "elapsed_ns": time.monotonic_ns() - started, "source_revision": revision, "source_identity_before": before,
    "source_revision_after": revision_after, "source_identity_after": after, "same_identity": same_identity,
    "sentinel_bytes_absent": sentinel_bytes_absent, "literal_count_checks": literal_checks,
    "jvm_invocations": 1, "files": [{"file": path.name, "bytes": path.stat().st_size, "sha256": digest(path)} for path in scanned],
    "scope": plan["scope"]}
(output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
print(json.dumps({"status": receipt["status"], "output": str(output), "jvm_invocations": 1}))
raise SystemExit(0 if passed else 1)
