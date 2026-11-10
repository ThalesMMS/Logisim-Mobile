"""Offline fixture tests for the Android issue 34 evidence checks."""
import json
import hashlib
import math
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import xml.etree.ElementTree as ET
import zlib

from android_visual_export34_scenarios import ink_coverage, inspect_pdf, preserved_project, receipt_for, rendered_page_colors, samples


FIXTURE_DIRECTORY = Path(__file__).resolve().parent.parent / "fixtures" / "visual-export"


class WholeDrawingInkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = json.loads((FIXTURE_DIRECTORY / "contract.json").read_text())
        cls.spec = cls.contract["whole"]["png"]
        cls.regions = cls.spec["ink"]["regions"]

    def raster(self, *, missing_ids=(), missing_kinds=(), difference=3):
        """Synthetic oracle input, not a rendering of the qualification circuit."""
        width, height = self.spec["width"], self.spec["height"]
        pixels = bytearray(b"\xff" * (width * height * 3))
        for sample in self.spec["samples"]:
            offset = (sample["y"] * width + sample["x"]) * 3
            pixels[offset:offset + 3] = bytes.fromhex(sample["argb"])[1:]
        for region in self.regions:
            if region["id"] in missing_ids or region["kind"] in missing_kinds:
                continue
            left, top, right, bottom = region["pixelBounds"]
            positions = [(x, y) for y in range(top, bottom) for x in range(left, right)]
            for x, y in positions[:region["minimumInkPixels"]]:
                offset = (y * width + x) * 3
                pixels[offset:offset + 3] = bytes([255 - difference, 255, 255])
        return width, height, 3, pixels

    def test_literal_regions_cover_fixture_text_symbols_and_frame(self):
        source = (FIXTURE_DIRECTORY / "qualification.circ").read_bytes()
        self.assertEqual("791508747474976204e73586a944caa1b0054801d7ab3306f6a02db7033f59d1",
                         hashlib.sha256(source).hexdigest())
        main = ET.fromstring(source).find("circuit[@name='visual_export_qualification']")
        components = {a.get("val"): comp for comp in main.findall("comp")
                      for a in comp.findall("a[@name='label']")}
        self.assertEqual(78, len(self.regions))
        by_id = {region["id"]: region for region in self.regions}
        symbols = {f"EXPORT34_CELL_{index:03d}" for index in range(72)}
        self.assertEqual(symbols, {r["id"] for r in self.regions if r["kind"] == "symbol"})
        self.assertEqual({"text-near", "text-far"}, {r["id"] for r in self.regions if r["kind"] == "text"})
        frame = {"frame-top": [100, -5, 5900, 5], "frame-bottom": [100, 3595, 5900, 3605],
                 "frame-left": [-5, 100, 5, 3500], "frame-right": [5995, 100, 6005, 3500]}
        self.assertEqual(set(frame), {r["id"] for r in self.regions if r["kind"] == "frame"})
        for identity in symbols:
            x, y = map(int, components[identity].get("loc").strip("()").split(","))
            self.assertEqual([x - 55, y - 55, x + 55, y + 55], by_id[identity]["worldBounds"])
        self.assertEqual([95, 95, 260, 160], by_id["text-near"]["worldBounds"])
        self.assertEqual([5695, 3295, 5900, 3345], by_id["text-far"]["worldBounds"])
        self.assertEqual("(100,100)", components["EXPORT34_TEXT"].get("loc"))
        self.assertEqual("(5700,3300)", components["EXPORT34_FAR"].get("loc"))
        self.assertEqual({("(0,0)", "(6000,0)"), ("(6000,0)", "(6000,3600)"),
                          ("(6000,3600)", "(0,3600)"), ("(0,3600)", "(0,0)")},
                         {(wire.get("from"), wire.get("to")) for wire in main.findall("wire")})
        for identity, bounds in frame.items():
            self.assertEqual(bounds, by_id[identity]["worldBounds"])
        for region in self.regions:
            left, top, right, bottom = region["worldBounds"]
            # Literal fixture bounds (-5,-5), world margin10, and scale0.25.
            self.assertEqual([math.floor((left + 15) / 4), math.floor((top + 15) / 4),
                              math.ceil((right + 15) / 4), math.ceil((bottom + 15) / 4)], region["pixelBounds"])
            self.assertEqual(30 if region["kind"] == "frame" else 5, region["minimumInkPixels"])

    def test_accepts_the_literal_minimum_for_every_region_without_requiring_black(self):
        decoded = self.raster()
        samples(decoded, self.spec, 2)
        proof = ink_coverage(decoded, self.spec)
        self.assertEqual([r["id"] for r in self.regions], [r["id"] for r in proof["regions"]])
        self.assertTrue(all(r["inkPixels"] == r["minimumInkPixels"] for r in proof["regions"]))
        self.assertEqual(2, proof["channelDifference"])

    def test_member_regions_are_disjoint_and_do_not_include_the_embedded_image(self):
        image = (28, 53, 59, 74)
        for index, region in enumerate(self.regions):
            left, top, right, bottom = region["pixelBounds"]
            for other in [image] + [r["pixelBounds"] for r in self.regions[index + 1:]]:
                self.assertTrue(right <= other[0] or other[2] <= left or bottom <= other[1] or other[3] <= top,
                                f"Another fixture member could satisfy region {region['id']}")

    def test_white_raster_with_only_the_old_color_samples_is_rejected(self):
        decoded = self.raster(missing_kinds={"text", "symbol", "frame"})
        samples(decoded, self.spec, 2)
        with self.assertRaisesRegex(AssertionError, "text-near: 0 ink pixels"):
            ink_coverage(decoded, self.spec)

    def test_omitting_any_member_class_is_rejected(self):
        for kind, expected in [("text", "text-near"), ("symbol", "EXPORT34_CELL_000"), ("frame", "frame-top")]:
            with self.subTest(kind=kind), self.assertRaisesRegex(AssertionError, f"{expected}: 0 ink pixels"):
                ink_coverage(self.raster(missing_kinds={kind}), self.spec)

    def test_each_text_symbol_and_frame_edge_must_have_ink(self):
        for region in self.regions:
            with self.subTest(region=region["id"]), self.assertRaisesRegex(AssertionError, f"{region['id']}: 0 ink pixels"):
                ink_coverage(self.raster(missing_ids={region["id"]}), self.spec)

    def test_background_rounding_at_the_channel_threshold_is_not_ink(self):
        with self.assertRaisesRegex(AssertionError, "text-near: 0 ink pixels"):
            ink_coverage(self.raster(difference=2), self.spec)

    def test_one_pixel_below_each_member_class_minimum_is_rejected(self):
        for identity in ["text-near", "EXPORT34_CELL_000", "frame-top"]:
            region = next(r for r in self.regions if r["id"] == identity)
            decoded = self.raster()
            width, _, channels, pixels = decoded
            x, y = region["pixelBounds"][:2]
            offset = (y * width + x) * channels
            pixels[offset:offset + 3] = b"\xff\xff\xff"
            with self.subTest(region=identity), self.assertRaisesRegex(AssertionError,
                    f"{identity}: {region['minimumInkPixels'] - 1} ink pixels"):
                ink_coverage(decoded, self.spec)


def write_pdf(path, rgb):
    content = b"q 200 0 0 100 0 0 cm /Fixture Do Q"
    compressed = zlib.compress(rgb)
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 5 0 R /Resources << /XObject << /Fixture 4 0 R >> >> >>",
        b"<< /Subtype /Image /Type /XObject /Width 2 /Height 1 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /FlateDecode /Length %d >>\nstream\n" % len(compressed)
        + compressed + b"\nendstream",
        b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
    ]
    data = bytearray(b"%PDF-1.4\n")
    offsets = []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += b"%d 0 obj\n" % index + obj + b"\nendobj\n"
    xref = len(data)
    data += b"xref\n0 6\n0000000000 65535 f \n"
    for offset in offsets:
        data += b"%010d 00000 n \n" % offset
    path.write_bytes(data + b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % xref)


class ExportEvidenceTest(unittest.TestCase):
    def test_color_and_dimension_oracle_rejects_wrong_pixels_or_crop(self):
        spec = {"width": 2, "height": 1, "samples": [{"x": 0, "y": 0, "argb": "ffff0000"}]}
        self.assertEqual("fffe0100", samples((2, 1, 3, bytes.fromhex("fe0100000000")), spec, 2)["samples"][0]["argb"])
        for raster in [(2, 1, 3, b"\xff" * 6), (1, 1, 4, bytes.fromhex("ff0000ff"))]:
            with self.assertRaises(AssertionError):
                samples(raster, spec, 2)

    def test_previews_require_colors_inside_page(self):
        raster = (6, 1, 3, bytes.fromhex("ff00000000007fff7f000000ffffff000000"))
        both = rendered_page_colors(raster, (0, 0, 4, 1))
        self.assertEqual({"redFraction": 0.5, "greenFraction": 0.5}, both)
        self.assertEqual(0, rendered_page_colors(raster, (4, 0, 6, 1))["redFraction"])

    def test_pdf_pixels_are_decoded_from_its_embedded_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "external.pdf"
            spec = {"pdf": {"signature": "%PDF-1.4", "pages": 1, "rasterWidth": 2, "rasterHeight": 1},
                    "png": {"width": 2, "height": 1, "samples": [{"x": 1, "y": 0, "argb": "ff7fff7f"}]}}
            write_pdf(path, bytes.fromhex("ff00007fff7f"))
            self.assertEqual(1, inspect_pdf(path, spec, 0)["pages"])
            write_pdf(path, bytes.fromhex("ff0000ffffff"))
            with self.assertRaises(AssertionError):
                inspect_pdf(path, spec, 0)

    def test_final_xml_allows_attribute_order_and_rejects_changed_project_content(self):
        before = '<project><comp x="1" y="2"><a name="label">kept </a></comp></project>'
        reordered = before.replace('x="1" y="2"', 'y="2" x="1"')
        proof = preserved_project(before, reordered)
        self.assertNotEqual(proof["beforeXmlSha256"], proof["afterXmlSha256"])
        for altered in (reordered.replace('x="1"', 'x="3"'), reordered.replace("kept ", "kept")):
            with self.assertRaises(AssertionError):
                preserved_project(before, altered)

    def test_build_receipt_rejects_adapter_as_editor(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            ui = SimpleNamespace(package="dev.logisim.mobile.e2e", serial="fixture", output=output)
            build = {"gitHead": "a" * 40, "appSha256": "b" * 64}
            receipt = output / "build.json"
            receipt.write_text(json.dumps(build))
            installed = {"package": ui.package + ".test", "sha256": build["appSha256"]}
            manifest = {**build, "serial": ui.serial, "package": ui.package, "installedArtifactsBefore": [installed]}
            (output / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(AssertionError):
                receipt_for(ui, receipt)
            installed["package"] = ui.package
            (output / "manifest.json").write_text(json.dumps(manifest))
            self.assertEqual(ui.serial, receipt_for(ui, receipt)["serial"])


if __name__ == "__main__":
    unittest.main()
