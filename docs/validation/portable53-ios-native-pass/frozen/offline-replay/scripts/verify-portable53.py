#!/usr/bin/env python3
"""Verify Portable53 artifacts using existing core/desktop JARs, without Gradle or devices."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from portable_receipt_contract import validate_ui_exports

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--core-jar", type=Path, required=True)
parser.add_argument("--desktop-jar", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--allow-no-opaque", action="store_true", help="Directory imports capture referenced .circ sources only")
parser.add_argument("--artifact", type=Path)
parser.add_argument("--origin", choices=("fixture", "app-export"))
parser.add_argument("--peer", type=Path, help="Another export from the same workspace; identities must match")
parser.add_argument("--ui-receipt", type=Path, help="Native runner receipt required to label an artifact as app-export")
parser.add_argument("--workspace-before", type=Path)
parser.add_argument("--workspace-imported", type=Path)
parser.add_argument("--workspace-restarted", type=Path)
parser.add_argument("--workspace-reopened", type=Path)
args = parser.parse_args()
workspace = (args.workspace_before, args.workspace_imported, args.workspace_restarted, args.workspace_reopened)
if args.artifact:
    if args.origin is None or any(workspace):
        parser.error("artifact verification needs --origin and no workspace paths")
else:
    if not all(workspace) or args.origin or args.peer:
        parser.error("provide --artifact/--origin, or all four --workspace paths")
ui_receipt = None
validated_exports = []
if args.origin == "app-export":
    if not args.ui_receipt:
        parser.error("--origin app-export requires --ui-receipt from the actual native run")
    try:
        ui_receipt, validated_exports = validate_ui_exports(args.ui_receipt, args.artifact, args.peer, ROOT / "fixtures")
    except (ValueError, OSError) as error:
        parser.error(str(error))
version = re.search(r'kotlin\("multiplatform"\) version "([^"]+)"', (ROOT / "build.gradle.kts").read_text()).group(1)
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
def jar(group, name, release):
    return next((cache / group / name / release).glob("*/*.jar"))
stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
compiler = [jar("org.jetbrains.kotlin", name, version) for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), jar("org.jetbrains", "annotations", "13.0")]
with tempfile.TemporaryDirectory(prefix="portable53-oracle-") as temporary:
    work = Path(temporary)
    core = work / "core-reference.jar"
    desktop = work / "desktop-reference.jar"
    shutil.copyfile(args.core_jar, core)
    shutil.copyfile(args.desktop_jar, desktop)
    classes = work / "classes"
    classpath = os.pathsep.join(map(str, (core, desktop, stdlib)))
    sources = [ROOT / "scripts/portable/Portable53Oracle.kt", ROOT / "composeApp/src/commonMain/kotlin/dev/logisim/mobile/DocumentActions.kt",
               ROOT / "composeApp/src/commonMain/kotlin/dev/logisim/mobile/WorkspaceDocumentLinks.kt"]
    subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath, f"-Xfriend-paths={core}",
        "-d", str(classes), *map(str, sources)], check=True)
    fixture = ROOT / "fixtures/portable-projects"
    opaque = str(not args.allow_no_opaque).lower()
    if args.artifact:
        operation = ["artifact", str(args.artifact.resolve()), str(args.output.resolve()), str(fixture), opaque, args.origin]
        if args.peer:
            operation.append(str(args.peer.resolve()))
    else:
        operation = ["workspace", *[str(path.resolve()) for path in workspace], str(args.output.resolve()), str(fixture), opaque]
    subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(classes) + os.pathsep + classpath,
        "dev.logisim.mobile.qualification.Portable53OracleKt", *operation], check=True)
    report = json.loads(args.output.read_text())
    report["recordedAtUtc"] = datetime.now(timezone.utc).isoformat()
    report["sourceRevision"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report["worktreeDirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True))
    report["oracleSourcesSha256"] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources + [Path(__file__).resolve(), ROOT / "scripts/portable_receipt_contract.py"]}
    report["referenceCoreSha256"] = hashlib.sha256(core.read_bytes()).hexdigest()
    report["referenceDesktopSha256"] = hashlib.sha256(desktop.read_bytes()).hexdigest()
    report["nativeUiStatus"] = "NOT_RUN" if ui_receipt is None else "PASSED_PER_NATIVE_RECEIPT"
    if validated_exports:
        report["validatedExports"] = validated_exports
    if args.ui_receipt:
        report["nativeUiReceipt"] = str(args.ui_receipt.resolve())
        report["nativeUiReceiptSha256"] = hashlib.sha256(args.ui_receipt.read_bytes()).hexdigest()
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
