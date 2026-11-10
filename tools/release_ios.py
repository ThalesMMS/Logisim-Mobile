"""Validate an explicit Apple distribution identity and export an iOS archive."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import fnmatch
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import uuid
import zipfile

from .generate_app_identity import ROOT, read_identity
from .release_common import ReleaseError, clean_source, executable, generated_identity, output_directory, required_environment, required_file, run
from .release_metadata import create_metadata


def validate_profile(profile: dict, team: str, bundle_id: str, now: datetime) -> str:
    if team not in profile.get("TeamIdentifier", []):
        raise ReleaseError("Provisioning profile belongs to a different Apple team")
    expiration = profile.get("ExpirationDate")
    if not isinstance(expiration, datetime):
        raise ReleaseError("Provisioning profile has no expiration date")
    expiry = expiration.replace(tzinfo=timezone.utc) if expiration.tzinfo is None else expiration.astimezone(timezone.utc)
    if expiry <= now.astimezone(timezone.utc):
        raise ReleaseError("Provisioning profile has expired")
    entitlements = profile.get("Entitlements", {})
    prefixes = profile.get("ApplicationIdentifierPrefix", [team])
    if not any(fnmatch.fnmatchcase(f"{prefix}.{bundle_id}", entitlements.get("application-identifier", "")) for prefix in prefixes):
        raise ReleaseError("Provisioning profile does not cover the application identifier")
    if entitlements.get("get-task-allow") or profile.get("ProvisionedDevices") or profile.get("ProvisionsAllDevices"):
        raise ReleaseError("Use an App Store distribution profile for TestFlight preparation")
    profile_uuid = profile.get("UUID", "")
    try:
        uuid.UUID(profile_uuid)
    except (ValueError, AttributeError):
        raise ReleaseError("Provisioning profile has no valid UUID")
    return profile_uuid


def signing_inputs() -> tuple[str, str, Path, str]:
    team = required_environment("LOGISIM_APPLE_TEAM_ID")
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        raise ReleaseError("LOGISIM_APPLE_TEAM_ID must contain 10 uppercase letters or digits")
    identity = required_environment("LOGISIM_APPLE_SIGNING_IDENTITY")
    profile_path = required_file("LOGISIM_IOS_PROFILE")
    raw = subprocess.check_output([executable("security"), "cms", "-D", "-i", str(profile_path)])
    profile = plistlib.loads(raw)
    uuid = validate_profile(profile, team, read_identity(ROOT / "app.properties").apple_id,
                            datetime.now(timezone.utc))
    identities = subprocess.check_output([executable("security"), "find-identity", "-v", "-p", "codesigning"], text=True)
    available = re.findall(r'^\s*\d+\)\s+([A-Fa-f0-9]{40})\s+"([^"\n]+)"', identities, re.MULTILINE)
    matches = [(fingerprint, name) for fingerprint, name in available if identity.upper() == fingerprint.upper() or identity == name]
    if len(matches) != 1:
        raise ReleaseError("Choose one exact available signing certificate name or SHA-1 fingerprint")
    certificates = profile.get("DeveloperCertificates", [])
    fingerprint = matches[0][0].upper()
    if not any(isinstance(certificate, bytes) and hashlib.sha1(certificate).hexdigest().upper() == fingerprint
               for certificate in certificates):
        raise ReleaseError("The selected signing certificate is not included in the provisioning profile")
    return team, identity, profile_path, uuid


def verify_info(info: dict) -> None:
    identity = read_identity(ROOT / "app.properties")
    if (info.get("CFBundleIdentifier"), info.get("CFBundleShortVersionString"), info.get("CFBundleVersion")) != (
            identity.apple_id, identity.version, str(identity.build)):
        raise ReleaseError("iOS artifact identity does not match app.properties")


def verify_privacy_manifest(contents: bytes) -> None:
    manifest = plistlib.loads(contents)
    required = {
        "NSPrivacyAccessedAPICategoryUserDefaults": {"CA92.1"},
        "NSPrivacyAccessedAPICategoryFileTimestamp": {"C617.1", "3B52.1"},
        "NSPrivacyAccessedAPICategorySystemBootTime": {"35F9.1"},
    }
    if not isinstance(manifest, dict) or not isinstance(manifest.get("NSPrivacyAccessedAPITypes"), list):
        raise ReleaseError("iOS privacy manifest has no required API declarations")
    declared = {}
    for entry in manifest["NSPrivacyAccessedAPITypes"]:
        if not isinstance(entry, dict):
            raise ReleaseError("iOS privacy manifest has an invalid API declaration")
        category = entry.get("NSPrivacyAccessedAPIType")
        reasons = entry.get("NSPrivacyAccessedAPITypeReasons")
        if not isinstance(category, str) or category in declared or not isinstance(reasons, list) or not all(isinstance(value, str) for value in reasons):
            raise ReleaseError("iOS privacy manifest has an invalid API category or reason list")
        declared[category] = set(reasons)
    if any(not reasons <= declared.get(category, set()) for category, reasons in required.items()):
        raise ReleaseError("iOS privacy manifest omits a required API reason used by this application")


def verify_ipa(path: Path) -> None:
    with zipfile.ZipFile(path) as ipa:
        manifests = [name for name in ipa.namelist() if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        if len(manifests) != 1:
            raise ReleaseError("IPA contains no unique application Info.plist")
        verify_info(plistlib.loads(ipa.read(manifests[0])))
        privacy_path = manifests[0].removesuffix("Info.plist") + "PrivacyInfo.xcprivacy"
        if ipa.namelist().count(privacy_path) != 1:
            raise ReleaseError("IPA does not contain one application privacy manifest")
        verify_privacy_manifest(ipa.read(privacy_path))


def record_release(destination: Path, source_commit: str, source_release_url: str, log: Path) -> None:
    metadata = create_metadata(ROOT, "ios", [destination])
    if metadata["sourceCommit"] != source_commit or metadata["sourceDirty"]:
        raise ReleaseError("Source changed during the iOS build; rebuild from a clean checkout")

    output = destination.parent
    run([sys.executable, str(ROOT / "scripts/prepare-source-release.py"), "--ref", source_commit,
         "--output", str(output), "--binary", str(destination)], log=log)
    metadata["sourceReleaseUrl"] = source_release_url
    (output / "release.json").write_text(json.dumps(metadata, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--check", action="store_true", help="Check prerequisites without building; this is the default")
    action.add_argument("--build", action="store_true", help="Archive and export locally after preflight checks pass")
    options = parser.parse_args()
    try:
        if sys.platform != "darwin":
            raise ReleaseError("iOS distribution preparation requires macOS and Xcode")
        identity = read_identity(ROOT / "app.properties")
        if options.build and not identity.source_release_url:
            raise ReleaseError("Set app.sourceReleaseUrl before building an iOS distribution archive")
        verify_privacy_manifest((ROOT / "iosApp/PrivacyInfo.xcprivacy").read_bytes())
        team, signing_identity, profile_path, profile_uuid = signing_inputs()
        for tool in ("xcodegen", "xcodebuild", "codesign"):
            executable(tool)
        generated_identity()
        run([sys.executable, str(ROOT / "scripts/generate-legal-notices.py"), "--check"])
        source_commit = clean_source()
        if not options.build:
            print("Apple profile, signing identity and source checks passed; no archive was built")
            return 0
        output = output_directory("ios")
        if (output / "release.json").exists():
            raise ReleaseError("Release artifacts already exist; increment app.build before another distribution build")
        profile_target = Path.home() / "Library/MobileDevice/Provisioning Profiles" / f"{profile_uuid}.mobileprovision"
        profile_target.parent.mkdir(parents=True, exist_ok=True)
        if profile_target.exists() and profile_target.read_bytes() != profile_path.read_bytes():
            raise ReleaseError("An installed profile has the same UUID but different contents")
        if not profile_target.exists():
            shutil.copy2(profile_path, profile_target)
        archive = output / "LogisimMobile.xcarchive"
        log = output / "build.log"
        run([executable("xcodegen"), "generate", "--spec", "iosApp/project.yml"], log=log)
        run([executable("xcodebuild"), "-project", "iosApp/LogisimMobile.xcodeproj", "-scheme", "LogisimMobile",
             "-configuration", "Release", "-destination", "generic/platform=iOS", "-archivePath", str(archive),
             "archive", f"DEVELOPMENT_TEAM={team}", "CODE_SIGN_STYLE=Manual",
             f"CODE_SIGN_IDENTITY={signing_identity}", f"PROVISIONING_PROFILE_SPECIFIER={profile_uuid}"], log=log)
        app = archive / "Products/Applications/LogisimMobile.app"
        verify_info(plistlib.loads((app / "Info.plist").read_bytes()))
        verify_privacy_manifest((app / "PrivacyInfo.xcprivacy").read_bytes())
        run([executable("codesign"), "--verify", "--deep", "--strict", str(app)], log=log)
        options_path = output / "ExportOptions.plist"
        options_path.write_bytes(plistlib.dumps({"method": "app-store-connect", "destination": "export",
            "teamID": team, "signingStyle": "manual", "signingCertificate": signing_identity,
            "provisioningProfiles": {identity.apple_id: profile_uuid},
            "manageAppVersionAndBuildNumber": False, "stripSwiftSymbols": True}))
        export_path = output / "export"
        run([executable("xcodebuild"), "-exportArchive", "-archivePath", str(archive),
             "-exportPath", str(export_path), "-exportOptionsPlist", str(options_path)], log=log)
        exported = list(export_path.glob("*.ipa"))
        if len(exported) != 1:
            raise ReleaseError("Expected one exported IPA")
        verify_ipa(exported[0])
        destination = output / f"LogisimMobile-{identity.version}-{identity.build}.ipa"
        shutil.copy2(exported[0], destination)
        record_release(destination, source_commit, identity.source_release_url, log)
        print(f"Verified iOS archive, IPA and corresponding source exported locally: {output}")
        return 0
    except (ReleaseError, ValueError, OSError, plistlib.InvalidFileException, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
