import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from tools.generate_app_identity import generate, read_identity

ROOT = Path(__file__).resolve().parents[2]


class AppIdentityTest(unittest.TestCase):
    def test_published_identity_matches_both_hosts(self):
        identity = read_identity(ROOT / "app.properties")
        self.assertEqual("thalesmms.logisim.mobile", identity.android_id)
        self.assertEqual("thalesmms.logisim.mobile", identity.apple_id)
        config = (ROOT / "iosApp/Config/Identity.xcconfig").read_text()
        self.assertIn("PRODUCT_BUNDLE_IDENTIFIER = thalesmms.logisim.mobile\n", config)
        self.assertIn(f"MARKETING_VERSION = {identity.version}\n", config)
        self.assertIn(f"CURRENT_PROJECT_VERSION = {identity.build}\n", config)
        metadata = json.loads((ROOT / "docs/releases/app-identity.json").read_text())
        self.assertEqual(identity.android_id, metadata["androidApplicationId"])
        self.assertEqual(identity.apple_id, metadata["appleBundleIdentifier"])
        self.assertTrue(generate(ROOT, True))

    def test_check_detects_missing_and_modified_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "app.properties").write_bytes((ROOT / "app.properties").read_bytes())
            self.assertTrue(generate(root, False))
            target = root / "iosApp/Config/Identity.xcconfig"
            target.write_text(target.read_text().replace("0.2.0", "99.0.0"))
            with patch("sys.stderr"):
                self.assertFalse(generate(root, True))
            self.assertTrue(generate(root, False))
            target.unlink()
            with patch("sys.stderr"):
                self.assertFalse(generate(root, True))

    def test_corresponding_source_url_is_optional_and_embedded_in_release_identity(self):
        properties = (ROOT / "app.properties").read_text()
        base = "\n".join(line for line in properties.splitlines() if not line.startswith("app.sourceReleaseUrl="))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "app.properties"
            config.write_text(base + "\n")
            self.assertIsNone(read_identity(config).source_release_url)

            url = "https://example.test/mobile/releases/tag/0.2.0-2"
            config.write_text(base + f"\napp.sourceReleaseUrl={url}\n")
            self.assertTrue(generate(root, False))
            metadata = json.loads((root / "docs/releases/app-identity.json").read_text())
            self.assertEqual(url, metadata["sourceReleaseUrl"])
            kotlin = root / "composeApp/src/commonMain/kotlin/dev/logisim/mobile/AppIdentity.kt"
            self.assertIn(f'sourceReleaseUrl: String? = "{url}"', kotlin.read_text())

            for invalid in ("http://example.test/source", "https:///source", "https://token@example.test/source"):
                config.write_text(base + f"\napp.sourceReleaseUrl={invalid}\n")
                with self.subTest(url=invalid), self.assertRaisesRegex(ValueError, "app.sourceReleaseUrl"):
                    read_identity(config)

    def test_rejects_duplicate_properties_and_platform_invalid_versions(self):
        original = (ROOT / "app.properties").read_text()
        build_property = f"app.build={read_identity(ROOT / 'app.properties').build}\n"
        invalid = [original + build_property, original.replace(build_property, "app.build=0\n"),
                   original.replace(build_property, "app.build=2147483647\n"),
                   original.replace("app.version=0.2.0", "app.version=release-candidate"),
                   original.replace("app.appleId=thalesmms.logisim.mobile", "app.appleId=no spaces")]
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / "app.properties"
            for text in invalid:
                file.write_text(text)
                with self.assertRaises(ValueError):
                    read_identity(file)

    def test_apple_icon_is_1024_rgb_and_android_has_adaptive_monochrome(self):
        icon = (ROOT / "iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png").read_bytes()
        self.assertEqual(b"\x89PNG\r\n\x1a\n", icon[:8])
        width, height, bit_depth, color_type = struct.unpack(">IIBB", icon[16:26])
        self.assertEqual((1024, 1024, 8, 2), (width, height, bit_depth, color_type))
        manifest = (ROOT / "androidApp/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:icon="@mipmap/ic_launcher"', manifest)
        self.assertIn('android:name="dev.logisim.mobile.MainActivity"', manifest)
        self.assertIn("<monochrome", (ROOT / "androidApp/src/main/res/mipmap-anydpi-v33/ic_launcher.xml").read_text())
        for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
            self.assertTrue((ROOT / f"androidApp/src/main/res/mipmap-{density}/ic_launcher.png").is_file())


if __name__ == "__main__":
    unittest.main()
