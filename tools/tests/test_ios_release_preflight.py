from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
import io
import json
import sys
from types import SimpleNamespace
import unittest
from pathlib import Path
import plistlib
import tempfile
import zipfile
import hashlib
from unittest.mock import DEFAULT, patch

from tools.generate_app_identity import read_identity
from tools.release_common import ReleaseError
from tools.release_ios import ROOT, main, record_release, signing_inputs, validate_profile, verify_info, verify_ipa, verify_privacy_manifest


class IosReleasePreflightTest(unittest.TestCase):
    now = datetime(2026, 11, 4, tzinfo=timezone.utc)

    def profile(self):
        return {"UUID": "12345678-1234-5678-1234-567812345678", "TeamIdentifier": ["ABCD123456"],
                "ApplicationIdentifierPrefix": ["ABCD123456"],
                "ExpirationDate": self.now + timedelta(days=30),
                "Entitlements": {"application-identifier": "ABCD123456.thalesmms.logisim.mobile", "get-task-allow": False}}

    def test_valid_store_profile_covers_exact_application(self):
        self.assertEqual("12345678-1234-5678-1234-567812345678",
                         validate_profile(self.profile(), "ABCD123456", "thalesmms.logisim.mobile", self.now))

    def test_rejects_expired_wrong_team_and_wrong_application(self):
        profile = self.profile()
        profile["ExpirationDate"] = self.now - timedelta(seconds=1)
        with self.assertRaisesRegex(ReleaseError, "expired"):
            validate_profile(profile, "ABCD123456", "thalesmms.logisim.mobile", self.now)
        with self.assertRaisesRegex(ReleaseError, "different Apple team"):
            validate_profile(self.profile(), "OTHER12345", "thalesmms.logisim.mobile", self.now)
        with self.assertRaisesRegex(ReleaseError, "application identifier"):
            validate_profile(self.profile(), "ABCD123456", "other.app", self.now)

    def test_rejects_development_adhoc_and_enterprise_profiles(self):
        for key, value in (("ProvisionedDevices", ["test-device"]), ("ProvisionsAllDevices", True)):
            profile = self.profile()
            profile[key] = value
            with self.assertRaisesRegex(ReleaseError, "App Store distribution"):
                validate_profile(profile, "ABCD123456", "thalesmms.logisim.mobile", self.now)
        profile = self.profile()
        profile["Entitlements"]["get-task-allow"] = True
        with self.assertRaisesRegex(ReleaseError, "App Store distribution"):
            validate_profile(profile, "ABCD123456", "thalesmms.logisim.mobile", self.now)

    def test_legacy_identifier_prefix_is_separate_from_team(self):
        profile = self.profile()
        profile["ApplicationIdentifierPrefix"] = ["OLDPREFIX1"]
        profile["Entitlements"]["application-identifier"] = "OLDPREFIX1.thalesmms.logisim.mobile"
        self.assertEqual(profile["UUID"], validate_profile(profile, "ABCD123456", "thalesmms.logisim.mobile", self.now))

    def test_artifact_cannot_report_a_different_identifier(self):
        identity = read_identity(ROOT / "app.properties")
        with self.assertRaisesRegex(ReleaseError, "identity"):
            verify_info({"CFBundleIdentifier": "dev.logisim.mobile",
                         "CFBundleShortVersionString": identity.version, "CFBundleVersion": str(identity.build)})

    def privacy_manifest(self):
        return {"NSPrivacyAccessedAPITypes": [
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults", "NSPrivacyAccessedAPITypeReasons": ["CA92.1"]},
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp", "NSPrivacyAccessedAPITypeReasons": ["C617.1", "3B52.1"]},
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategorySystemBootTime", "NSPrivacyAccessedAPITypeReasons": ["35F9.1"]},
        ]}

    def test_rejects_manifest_missing_document_or_preference_api_reasons(self):
        for category in range(3):
            manifest = self.privacy_manifest()
            del manifest["NSPrivacyAccessedAPITypes"][category]
            with self.assertRaisesRegex(ReleaseError, "omits a required API reason"):
                verify_privacy_manifest(plistlib.dumps(manifest))
        manifest = self.privacy_manifest()
        manifest["NSPrivacyAccessedAPITypes"][1]["NSPrivacyAccessedAPITypeReasons"] = ["C617.1"]
        with self.assertRaisesRegex(ReleaseError, "omits a required API reason"):
            verify_privacy_manifest(plistlib.dumps(manifest))

    def test_rejects_duplicate_api_category_and_invalid_reason_type(self):
        manifest = self.privacy_manifest()
        manifest["NSPrivacyAccessedAPITypes"].append(manifest["NSPrivacyAccessedAPITypes"][0])
        with self.assertRaisesRegex(ReleaseError, "invalid API category"):
            verify_privacy_manifest(plistlib.dumps(manifest))
        manifest = self.privacy_manifest()
        manifest["NSPrivacyAccessedAPITypes"][0]["NSPrivacyAccessedAPITypeReasons"] = "CA92.1"
        with self.assertRaisesRegex(ReleaseError, "invalid API category"):
            verify_privacy_manifest(plistlib.dumps(manifest))

    def make_ipa(self, directory, *, privacy=True, privacy_path="Payload/LogisimMobile.app/PrivacyInfo.xcprivacy",
                 identity="thalesmms.logisim.mobile", ipad_orientation_override=True):
        path = Path(directory) / "test.ipa"
        release = read_identity(ROOT / "app.properties")
        ipad_key = "UISupportedInterfaceOrientations~ipad"
        ipad_orientations = plistlib.loads((ROOT / "iosApp/Info.plist").read_bytes())[ipad_key]
        info = {
            "CFBundleIdentifier": identity,
            "CFBundleShortVersionString": release.version,
            "CFBundleVersion": str(release.build),
            "UIDeviceFamily": [1, 2],
            "UISupportedInterfaceOrientations": [
                value for value in ipad_orientations if value != "UIInterfaceOrientationPortraitUpsideDown"
            ],
        }
        if ipad_orientation_override:
            info[ipad_key] = ipad_orientations
        with zipfile.ZipFile(path, "w") as ipa:
            ipa.writestr("Payload/LogisimMobile.app/Info.plist", plistlib.dumps(info))
            if privacy:
                ipa.writestr(privacy_path, plistlib.dumps(self.privacy_manifest()))
        return path

    def test_accepts_ipa_with_the_application_identity_and_bundled_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            verify_ipa(self.make_ipa(directory))

    def test_rejects_ipad_ipa_with_only_three_interface_orientations(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ReleaseError, "iPad multitasking requires all four interface orientations"):
                verify_ipa(self.make_ipa(directory, ipad_orientation_override=False))

    def test_rejects_ipa_without_application_manifest_even_with_framework_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ReleaseError, "application privacy manifest"):
                verify_ipa(self.make_ipa(directory, privacy=False))
            with self.assertRaisesRegex(ReleaseError, "application privacy manifest"):
                verify_ipa(self.make_ipa(directory, privacy_path="Payload/LogisimMobile.app/Frameworks/ComposeApp.framework/PrivacyInfo.xcprivacy"))

    def test_rejects_ipa_with_an_unrelated_application_identifier(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ReleaseError, "identity"):
                verify_ipa(self.make_ipa(directory, identity="other.app"))

    def test_distribution_build_requires_source_url_before_signing(self):
        with (patch("tools.release_ios.sys.platform", "darwin"),
              patch("tools.release_ios.sys.argv", ["release_ios", "--build"]),
              patch("tools.release_ios.read_identity", return_value=SimpleNamespace(source_release_url=None)),
              patch("tools.release_ios.signing_inputs") as signing,
              redirect_stderr(io.StringIO()) as errors):
            self.assertEqual(2, main())
        self.assertIn("Set app.sourceReleaseUrl", errors.getvalue())
        signing.assert_not_called()

    def test_check_does_not_require_a_source_url_or_package_source(self):
        with (patch("tools.release_ios.sys.platform", "darwin"),
              patch("tools.release_ios.sys.argv", ["release_ios", "--check"]),
              patch("tools.release_ios.read_identity", return_value=SimpleNamespace(source_release_url=None)),
              patch.multiple("tools.release_ios", signing_inputs=DEFAULT, executable=DEFAULT,
                             generated_identity=DEFAULT, clean_source=DEFAULT, record_release=DEFAULT, run=DEFAULT) as calls,
              redirect_stdout(io.StringIO())):
            calls["signing_inputs"].return_value = ("team", "identity", Path("test.mobileprovision"), "uuid")
            self.assertEqual(0, main())
        calls["signing_inputs"].assert_called_once()
        calls["run"].assert_called_once_with(
            [sys.executable, str(ROOT / "scripts/generate-legal-notices.py"), "--check"])
        calls["record_release"].assert_not_called()

    def test_release_records_the_source_url_after_packaging_the_exact_commit(self):
        commit = "a" * 40
        source_url = "https://example.test/releases/0.2.0-2"
        metadata = {"sourceCommit": commit, "sourceDirty": False}
        with tempfile.TemporaryDirectory() as directory:
            ipa = self.make_ipa(directory)
            log = ipa.parent / "build.log"
            receipt = ipa.parent / "release.json"

            def package_source(command, *, log):
                self.assertFalse(receipt.exists())

            with (patch("tools.release_ios.create_metadata", return_value=metadata),
                  patch("tools.release_ios.run", side_effect=package_source) as package):
                record_release(ipa, commit, source_url, log)
            package.assert_called_once_with(
                [sys.executable, str(ROOT / "scripts/prepare-source-release.py"), "--ref", commit,
                 "--output", directory, "--binary", str(ipa)], log=log)
            self.assertEqual(source_url, json.loads(receipt.read_text())["sourceReleaseUrl"])

    def test_release_does_not_finalize_if_source_changed_or_packaging_failed(self):
        commit = "a" * 40
        source_url = "https://example.test/releases/0.2.0-2"
        with tempfile.TemporaryDirectory() as directory:
            ipa = self.make_ipa(directory)
            log = ipa.parent / "build.log"
            with (patch("tools.release_ios.create_metadata", return_value={"sourceCommit": "b" * 40, "sourceDirty": False}),
                  patch("tools.release_ios.run") as package,
                  self.assertRaisesRegex(ReleaseError, "Source changed during the iOS build")):
                record_release(ipa, commit, source_url, log)
            package.assert_not_called()
            with (patch("tools.release_ios.create_metadata", return_value={"sourceCommit": commit, "sourceDirty": False}),
                  patch("tools.release_ios.run", side_effect=ReleaseError("Could not package source")),
                  self.assertRaisesRegex(ReleaseError, "Could not package source")):
                record_release(ipa, commit, source_url, log)
            self.assertFalse((ipa.parent / "release.json").exists())

    def signing_environment(self, directory):
        certificate = b"test-profile-certificate"
        profile = self.profile()
        profile["ExpirationDate"] = datetime.now(timezone.utc) + timedelta(days=30)
        profile["DeveloperCertificates"] = [certificate]
        path = Path(directory) / "app.mobileprovision"
        path.write_bytes(plistlib.dumps(profile))
        fingerprint = hashlib.sha1(certificate).hexdigest().upper()
        environment = {"LOGISIM_APPLE_TEAM_ID": "ABCD123456", "LOGISIM_APPLE_SIGNING_IDENTITY": "Apple Distribution: Test", "LOGISIM_IOS_PROFILE": str(path)}
        return path, fingerprint, environment

    def test_signing_identity_must_be_the_certificate_in_the_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            path, fingerprint, environment = self.signing_environment(directory)
            available = f'  1) {fingerprint} "Apple Distribution: Test"\n'
            with patch.dict("os.environ", environment), patch("tools.release_ios.executable", side_effect=lambda name: name), patch("tools.release_ios.subprocess.check_output", side_effect=[path.read_bytes(), available]):
                team, identity, selected, profile_uuid = signing_inputs()
            self.assertEqual(("ABCD123456", "Apple Distribution: Test", path.resolve(), "12345678-1234-5678-1234-567812345678"), (team, identity, selected, profile_uuid))
            wrong = '  1) ' + 'F' * 40 + ' "Apple Distribution: Test"\n'
            with patch.dict("os.environ", environment), patch("tools.release_ios.executable", side_effect=lambda name: name), patch("tools.release_ios.subprocess.check_output", side_effect=[path.read_bytes(), wrong]):
                with self.assertRaisesRegex(ReleaseError, "not included in the provisioning profile"):
                    signing_inputs()


if __name__ == "__main__":
    unittest.main()
