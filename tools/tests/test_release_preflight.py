import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from tools.release_android import signing_inputs, verify_apk_signature
from tools.release_common import ReleaseError, required_environment


class ReleasePreflightTest(unittest.TestCase):
    def test_missing_identity_fails_without_creating_one(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ReleaseError, "LOGISIM_ANDROID_KEYSTORE"):
                signing_inputs()

    def test_password_files_are_required_and_never_return_password_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            keystore = root / "release.keystore"
            keystore.write_bytes(b"test-keystore")
            password = root / "password"
            password.write_text("sensitive-test-value\n")
            values = {"LOGISIM_ANDROID_KEYSTORE": str(keystore), "LOGISIM_ANDROID_KEY_ALIAS": "release",
                      "LOGISIM_ANDROID_STORE_PASSWORD_FILE": str(password), "LOGISIM_ANDROID_KEY_PASSWORD_FILE": str(password)}
            with patch.dict(os.environ, values, clear=True):
                result = signing_inputs()
                self.assertEqual("release", result["alias"])
                self.assertEqual(str(password.resolve()), result["storePasswordFile"])
                self.assertNotIn("sensitive-test-value", str(result))
                password.write_text("\n")
                with self.assertRaisesRegex(ReleaseError, "must not be empty"):
                    signing_inputs()

    def test_signing_files_inside_source_checkout_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkout = root / "checkout"
            checkout.mkdir()
            outside = root / "credentials"
            outside.mkdir()
            for directory in (checkout, outside):
                (directory / "release.keystore").write_bytes(b"test-keystore")
                (directory / "password").write_text("sensitive-test-value\n")
            values = {"LOGISIM_ANDROID_KEYSTORE": str(outside / "release.keystore"),
                      "LOGISIM_ANDROID_KEY_ALIAS": "release",
                      "LOGISIM_ANDROID_STORE_PASSWORD_FILE": str(outside / "password"),
                      "LOGISIM_ANDROID_KEY_PASSWORD_FILE": str(outside / "password")}
            names = {"LOGISIM_ANDROID_KEYSTORE": "release.keystore",
                     "LOGISIM_ANDROID_STORE_PASSWORD_FILE": "password",
                     "LOGISIM_ANDROID_KEY_PASSWORD_FILE": "password"}
            with patch("tools.release_android.ROOT", checkout):
                for name, filename in names.items():
                    with self.subTest(name=name):
                        invalid = dict(values, **{name: str(checkout / filename)})
                        with patch.dict(os.environ, invalid, clear=True):
                            with self.assertRaisesRegex(ReleaseError, name + " must be stored outside"):
                                signing_inputs()

    def test_external_symlink_to_checkout_credential_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            checkout = root / "checkout"
            checkout.mkdir()
            key = checkout / "release.keystore"
            key.write_bytes(b"test-keystore")
            alias = root / "external.keystore"
            alias.symlink_to(key)
            with patch("tools.release_android.ROOT", checkout), patch.dict(os.environ, {
                "LOGISIM_ANDROID_KEYSTORE": str(alias), "LOGISIM_ANDROID_KEY_ALIAS": "release"
            }, clear=True):
                with self.assertRaisesRegex(ReleaseError, "LOGISIM_ANDROID_KEYSTORE must be stored outside"):
                    signing_inputs()

    def test_verified_apk_records_public_signer_fingerprint(self):
        fingerprint = "AB" * 32
        result = SimpleNamespace(returncode=0,
                                 stdout=f"Verified\nSigner #1 certificate SHA-256 digest: {fingerprint}\n", stderr="")
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "verify.log"
            with patch("tools.release_android.subprocess.run", return_value=result) as run:
                self.assertEqual(["ab" * 32], verify_apk_signature(Path("app.apk"), Path("apksigner"), log))
            run.assert_called_once_with(["apksigner", "verify", "--verbose", "--print-certs", "app.apk"],
                                        capture_output=True, text=True)
            self.assertIn(fingerprint, log.read_text())

    def test_unsigned_or_unidentified_apk_cannot_produce_signer_metadata(self):
        for result in (SimpleNamespace(returncode=1, stdout="", stderr="DOES NOT VERIFY"),
                       SimpleNamespace(returncode=0, stdout="Verified", stderr="")):
            with self.subTest(returncode=result.returncode), tempfile.TemporaryDirectory() as temporary:
                with patch("tools.release_android.subprocess.run", return_value=result):
                    with self.assertRaises(ReleaseError):
                        verify_apk_signature(Path("app.apk"), Path("apksigner"), Path(temporary) / "verify.log")

    def test_whitespace_does_not_create_an_identity(self):
        with patch.dict(os.environ, {"TEAM": "  "}, clear=True):
            with self.assertRaises(ReleaseError):
                required_environment("TEAM")


if __name__ == "__main__":
    unittest.main()
