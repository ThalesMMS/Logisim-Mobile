"""Check signing inputs or build and verify signed Android distribution artifacts."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from .generate_app_identity import ROOT, read_identity
from .release_common import ReleaseError, clean_source, executable, generated_identity, output_directory, required_environment, required_file, run
from .release_metadata import create_metadata


def external_signing_file(name: str, *, secret: bool = False) -> Path:
    path = required_file(name, secret=secret)
    if path.is_relative_to(ROOT.resolve()):
        raise ReleaseError(f"{name} must be stored outside the source checkout")
    return path


def signing_inputs() -> dict[str, str]:
    return {
        "keystore": str(external_signing_file("LOGISIM_ANDROID_KEYSTORE")),
        "alias": required_environment("LOGISIM_ANDROID_KEY_ALIAS"),
        "storePasswordFile": str(external_signing_file("LOGISIM_ANDROID_STORE_PASSWORD_FILE", secret=True)),
        "keyPasswordFile": str(external_signing_file("LOGISIM_ANDROID_KEY_PASSWORD_FILE", secret=True)),
    }


def build_tools() -> tuple[Path, Path]:
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk"))))
    candidates = [directory for directory in (sdk / "build-tools").glob("*")
                  if (directory / "apksigner").is_file() and (directory / "aapt2").is_file()]
    if not candidates:
        raise ReleaseError("Install Android SDK build-tools with apksigner and aapt2")
    latest = sorted(candidates, key=lambda value: tuple(int(part) for part in re.findall(r"\d+", value.name)))[-1]
    return latest / "apksigner", latest / "aapt2"


def verify_package(apk: Path, aapt: Path) -> None:
    identity = read_identity(ROOT / "app.properties")
    output = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output)
    if match is None or match.groups() != (identity.android_id, str(identity.build), identity.version):
        raise ReleaseError("APK identity does not match app.properties")


def verify_apk_signature(apk: Path, signer: Path, log: Path) -> list[str]:
    verified = subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(apk)],
                              capture_output=True, text=True)
    with log.open("a") as stream:
        stream.write(verified.stdout + verified.stderr)
    if verified.returncode:
        raise ReleaseError("APK signature verification failed")
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$",
                              verified.stdout, re.MULTILINE)
    if not certificates:
        raise ReleaseError("APK signer certificate SHA-256 was not reported")
    return sorted({digest.lower() for digest in certificates})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--check", action="store_true", help="Validate prerequisites without building; this is the default")
    action.add_argument("--build", action="store_true", help="Build after all preflight checks pass")
    options = parser.parse_args()
    try:
        inputs = signing_inputs()
        os.environ.update({"LOGISIM_ANDROID_KEYSTORE": inputs["keystore"],
                           "LOGISIM_ANDROID_STORE_PASSWORD_FILE": inputs["storePasswordFile"],
                           "LOGISIM_ANDROID_KEY_PASSWORD_FILE": inputs["keyPasswordFile"]})
        signer, aapt = build_tools()
        executable("java")
        executable("jarsigner")
        generated_identity()
        clean_source()
        if not options.build:
            print("Android signing inputs and source checks passed; no artifacts were built")
            return 0
        output = output_directory("android")
        if (output / "release.json").exists():
            raise ReleaseError("Release artifacts already exist; increment app.build before another distribution build")
        log = output / "build.log"
        run([str(ROOT / "gradlew"), ":androidApp:assembleRelease", ":androidApp:bundleRelease", "--console=plain"], log=log)
        apk = ROOT / "androidApp/build/outputs/apk/release/androidApp-release.apk"
        aab = ROOT / "androidApp/build/outputs/bundle/release/androidApp-release.aab"
        if not apk.is_file() or not aab.is_file():
            raise ReleaseError("Gradle did not produce both signed APK and AAB artifacts")
        certificates = verify_apk_signature(apk, signer, log)
        verified = subprocess.run([executable("jarsigner"), "-J-Duser.language=en", "-J-Duser.country=US", "-verify", str(aab)], capture_output=True, text=True)
        with log.open("a") as stream:
            stream.write(verified.stdout + verified.stderr)
        if verified.returncode or "jar verified" not in verified.stdout.lower():
            raise ReleaseError("AAB signature verification failed")
        verify_package(apk, aapt)
        identity = read_identity(ROOT / "app.properties")
        stem = f"LogisimMobile-{identity.version}-{identity.build}"
        artifacts = []
        for source in (apk, aab):
            destination = output / (stem + source.suffix)
            shutil.copy2(source, destination)
            artifacts.append(destination)
        metadata = create_metadata(ROOT, "android", artifacts)
        metadata["signing"] = {"apkSignerCertificateSha256": certificates}
        (output / "release.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(f"Verified signed Android artifacts: {output}")
        return 0
    except (ReleaseError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
