#!/usr/bin/env python3
"""Build the isolated fixture with Xcode Simulator signing, without launching it."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import signal
import subprocess
import traceback


class BuildInterrupted(Exception):
    def __init__(self, signum):
        self.signum = signum
        super().__init__(f"Build interrupted by signal {signum}")


def interrupt(signum, _frame):
    raise BuildInterrupted(signum)


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--expected-source", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lock-file", type=Path, default=Path("/tmp/logisim-gradle.lock"))
    args = parser.parse_args()
    signal.signal(signal.SIGINT, interrupt)
    signal.signal(signal.SIGTERM, interrupt)
    source = args.source_root.resolve()
    output = args.output.resolve()
    if output.exists():
        parser.error("Preserve prior evidence; use a new output directory")
    output.mkdir(parents=True)
    report = {"status": "PREPARING", "started": now(), "source": args.expected_source,
              "sourceRoot": str(source), "buildDriverSHA256": sha(Path(__file__).resolve()),
              "nativeExecution": "NOT_RUN", "domainRegistration": "NOT_RUN",
              "physicalDevice": False, "signing": "Xcode ad-hoc Simulator signing, fixture source group only",
              "simulatorEntitlementReviewRequired": True,
              "commands": []}
    manifest = output / "artifact.json"

    def save():
        manifest.write_text(json.dumps(report, indent=2) + "\n")

    def run(command, log, cwd=source):
        record = {"arguments": [str(x) for x in command], "started": now(), "log": log}
        report["commands"].append(record)
        save()
        with (output / log).open("w") as stream:
            process = subprocess.Popen(command, cwd=cwd, text=True, stdout=stream,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait()
            except BuildInterrupted as error:
                record["interruptedSignal"] = error.signum
                for signum in [signal.SIGINT, signal.SIGTERM]:
                    signal.signal(signum, signal.SIG_IGN)
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
                # Descendants may survive after the session leader exits.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=10)
                raise
            finally:
                record.update({"finished": now(), "exitCode": process.returncode})
                save()
        if code:
            raise RuntimeError(f"Command failed ({code}); see {log}")

    try:
        current = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
        if current != args.expected_source:
            raise ValueError(f"Source mismatch: {current}")
        if subprocess.check_output(["git", "status", "--porcelain"], cwd=source, text=True).strip():
            raise ValueError("The source checkout must be clean")
        stage = output / "Fixture"
        prefix = "scripts/ios_fileprovider_revocation/"
        committed = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", args.expected_source,
                                             "--", prefix], cwd=source, text=True).splitlines()
        if not committed:
            raise ValueError("The frozen commit contains no fixture source")
        for name in committed:
            relative = Path(name).relative_to(prefix)
            target = stage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(subprocess.check_output(["git", "show", args.expected_source + ":" + name], cwd=source))
        report["sourceFiles"] = {str(p.relative_to(stage)): sha(p) for p in sorted(stage.rglob("*")) if p.is_file()}
        if sha(stage / "build-simulator-fixture.py") != report["buildDriverSHA256"]:
            raise ValueError("The invoked driver differs from the requested committed source")
        verifier_path = stage / "simulator_entitlements.py"
        verifier_bytes = verifier_path.read_bytes()
        report["simulatorEntitlementVerifierSHA256"] = hashlib.sha256(verifier_bytes).hexdigest()
        report["driverSourceMatchesExportedPin"] = True
        artifact_reader_path = output / "artifact-reader.py"
        artifact_reader_bytes = subprocess.check_output(
            ["git", "show", args.expected_source + ":scripts/run-ios-accessibility.py"], cwd=source)
        artifact_reader_path.write_bytes(artifact_reader_bytes)
        report["artifactReaderSHA256"] = hashlib.sha256(artifact_reader_bytes).hexdigest()
        report["artifactReaderSourcePath"] = "scripts/run-ios-accessibility.py"
        group = "group.thalesmms.logisim.validation.revocationfixture"
        for name in ["App", "Provider"]:
            entitlement = plistlib.loads((stage / "Config" / f"{name}.entitlements").read_bytes())
            if entitlement != {"com.apple.security.application-groups": [group]}:
                raise ValueError("Unexpected fixture entitlements")
        save()
        with args.lock_file.open("a") as lock:
            report["lockRequested"] = now()
            save()
            fcntl.flock(lock, fcntl.LOCK_EX)
            report["lockAcquired"] = now()
            save()
            run(["python3", "verify-fixture.py"], "configuration.log", cwd=stage)
            run(["xcodegen", "generate", "--spec", "project.yml"], "xcodegen.log", cwd=stage)
            run(["xcodebuild", "-project", str(stage / "LogisimRevocationFixture.xcodeproj"),
                 "-scheme", "RevocationFixture", "-configuration", "Debug", "-sdk", "iphonesimulator",
                 "-destination", "generic/platform=iOS Simulator", "-derivedDataPath", str(output / "DerivedData"),
                 "ARCHS=arm64", "ONLY_ACTIVE_ARCH=YES", "CODE_SIGNING_ALLOWED=YES", "CODE_SIGNING_REQUIRED=YES",
                 "CODE_SIGN_STYLE=Manual", "DEVELOPMENT_TEAM=", "CODE_SIGN_IDENTITY=-", "build"], "build.log")
            app = output / "DerivedData/Build/Products/Debug-iphonesimulator/RevocationFixture.app"
            provider = app / "PlugIns/RevocationProvider.appex"
            if not app.is_dir() or not provider.is_dir():
                raise ValueError("Expected fixture app and provider products are missing")
            section_spec = importlib.util.spec_from_file_location(
                "fixture_simulator_entitlements", verifier_path)
            if sha(verifier_path) != report["simulatorEntitlementVerifierSHA256"]:
                raise ValueError("The staged Simulator entitlement verifier changed after export")
            sections = importlib.util.module_from_spec(section_spec)
            exec(compile(verifier_bytes, str(verifier_path), "exec"), sections.__dict__)
            report["simulatorEntitlements"] = {}
            for name, bundle, identifier in [
                    ("Provider", provider, "thalesmms.logisim.validation.revocationfixture.provider"),
                    ("App", app, "thalesmms.logisim.validation.revocationfixture")]:
                report["simulatorEntitlements"][name] = sections.verify_bundle(
                    bundle, identifier, group, output, name)
                sections.verify_packaging_inputs(report["simulatorEntitlements"][name], bundle,
                                                 output / "DerivedData", output, name)
                save()
                actual = subprocess.run(["codesign", "--display", "--entitlements", ":-", str(bundle)],
                                        text=False, capture_output=True)
                (output / f"entitlements-{name}.stdout.plist").write_bytes(actual.stdout)
                (output / f"entitlements-{name}.stderr.log").write_bytes(actual.stderr)
                if actual.returncode:
                    raise ValueError(f"Could not read signed {name} entitlements")
                host_entitlements = plistlib.loads(actual.stdout) if actual.stdout else None
                if host_entitlements is not None and not isinstance(host_entitlements, dict):
                    raise ValueError(f"Signed {name} entitlements are not a dictionary")
                if host_entitlements:
                    if host_entitlements.get("com.apple.developer.team-identifier"):
                        raise ValueError(f"Signed {name} entitlements claim a Team identifier")
                    group_key = "com.apple.security.application-groups"
                    if group_key in host_entitlements and host_entitlements[group_key] != [group]:
                        raise ValueError(f"Signed {name} entitlements claim another App Group")
                report["simulatorEntitlements"][name]["codeSignatureEntitlements"] = host_entitlements
                run(["codesign", "--verify", "--deep", "--strict", "--verbose=2", str(bundle)], f"verify-{name}.log")
                run(["codesign", "--display", "--verbose=4", str(bundle)], f"signature-{name}.log")
                signature = (output / f"signature-{name}.log").read_text()
                if "Signature=adhoc" not in signature or "TeamIdentifier=not set" not in signature:
                    raise ValueError(f"The {name} signature must be ad-hoc without a Team identifier")
            if sha(artifact_reader_path) != report["artifactReaderSHA256"]:
                raise ValueError("The exported artifact reader changed after export")
            common_spec = importlib.util.spec_from_file_location("ios_artifact", artifact_reader_path)
            common = importlib.util.module_from_spec(common_spec)
            exec(compile(artifact_reader_bytes, str(artifact_reader_path), "exec"), common.__dict__)
            report["app"] = common.artifact(app, "thalesmms.logisim.validation.revocationfixture")
            report["provider"] = common.artifact(provider, "thalesmms.logisim.validation.revocationfixture.provider")
            report["appGroup"] = group
            report["sdk"] = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"], text=True).strip()
            report["architecture"] = subprocess.check_output(["lipo", "-archs", str(app / "RevocationFixture")], text=True).strip()
            report["status"] = "BUILD_AND_ADHOC_SIGN_PASS"
            report["lockWorkFinished"] = now()
    except Exception as error:
        report["status"] = "INTERRUPTED" if isinstance(error, BuildInterrupted) else "FAIL"
        report["error"] = {"type": type(error).__name__, "message": str(error)}
        (output / "exception.log").write_text(traceback.format_exc())
    finally:
        report["finished"] = now()
        save()
    print(manifest)
    return 0 if report["status"] == "BUILD_AND_ADHOC_SIGN_PASS" else 130 if report["status"] == "INTERRUPTED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
