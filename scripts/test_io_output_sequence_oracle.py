"""Exercise synthetic glyph frames and capture receipts, without a native app."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from io_held_frame_oracle import digest
from io_output_sequence_oracle import evaluate_samples, main, recognize, validate_association, verify_capture, verify_fixture

ROOT = Path(__file__).resolve().parents[1]


def roi(x):
    return {"x": x / 660, "y": .25, "width": 80 / 660, "height": .5}


def native_metadata():
    reference = lambda label, value, x: {"label": label, "kind": "OUTPUT", "width": 1, "radix": "2", "expected": value,
                                       "observedBefore": value, "observedAfter": value, "geometryStable": True,
                                       "valueRoiNormalizedToApplication": roi(x)}
    return {"schemaVersion": 1, "recordType": "native-output-probe-sequence", "testCase": "Run/IoEndToEndTests.testIoCanvasButtonHeldOutputVideo",
            "targetEnvironment": "simulator", "simulatorUDID": "synthetic-no-device",
            "timeSource": "Foundation.Date Unix seconds in iOS Simulator",
            "probe": {"label": "OUT_HELD", "kind": "OUTPUT", "width": 1, "radix": "2", "geometryStable": True,
                      "valueRoiNormalizedToApplication": roi(60)}, "expectedSequence": ["0", "1", "0"],
            "referenceProbes": {"low": reference("REF_LOW", "0", 260), "high": reference("REF_HIGH", "1", 460)},
            "gesture": {"apiStartedUnixSeconds": 100, "apiReturnedUnixSeconds": 104, "requestedHoldSeconds": 4},
            "limits": {"maxPressLatencySeconds": .75, "maxReleaseLatencySeconds": .5, "minHeldSampleSeparationSeconds": 1, "maxHeldEndGapSeconds": .75}}


def association():
    return {"schemaVersion": 1, "testCase": native_metadata()["testCase"], "metadataAttachmentName": "io-canvas-held-output-sequence.json",
            "sampleTimeSource": "captureInterval", "frameMatchesApplication": True, "coordinateMappingEvidence": "retained/crop-review.json",
            "samples": [{"phase": phase, "png": value + ".png", "captureEvidence": str(index) + ".capture.json",
                         "captureStartedUnixSeconds": lower, "captureReturnedUnixSeconds": upper}
                        for index, (phase, value, lower, upper) in enumerate([
                            ("pre", "0", 99.6, 99.8), ("held", "1", 100.2, 100.4),
                            ("held", "1", 103.5, 103.7), ("post", "0", 104.1, 104.3)])]}


class OutputSequenceValidationTests(unittest.TestCase):
    def test_reviewed_capture_intervals_pass(self):
        _, intervals = validate_association(native_metadata(), association())
        self.assertEqual((100.2, 100.4), intervals[1])

    def test_wrong_probe_and_unobserved_or_identical_reference_geometry_fail(self):
        for change in [lambda item: item["probe"].update(label="BTN_HELD"),
                       lambda item: item["referenceProbes"]["high"].update(observedBefore="0"),
                       lambda item: item["referenceProbes"]["high"].update(valueRoiNormalizedToApplication=roi(260)),
                       lambda item: item["probe"].update(geometryStable=False),
                       lambda item: item.update(targetEnvironment="device"),
                       lambda item: item.update(simulatorUDID=""),
                       lambda item: item.update(timeSource="unspecified")]:
            data = native_metadata()
            change(data)
            with self.assertRaises(ValueError):
                validate_association(data, association())

    def test_late_overlapping_and_outside_capture_intervals_fail(self):
        for index, lower, upper in [(1, 100.6, 100.8), (1, 99.9, 100.1), (2, 100.8, 101),
                                    (2, 103.9, 104.1), (3, 104.3, 104.6), (0, 99.8, 100.1)]:
            data = association()
            data["samples"][index].update(captureStartedUnixSeconds=lower, captureReturnedUnixSeconds=upper)
            with self.subTest(index=index, lower=lower), self.assertRaises(ValueError):
                validate_association(native_metadata(), data)

    def test_always_low_always_high_and_unknown_cannot_pass_sequence(self):
        for values in [["0"] * 4, ["1"] * 4, ["0", "unknown", "1", "0"]]:
            iterator = iter(values)
            def read(_):
                value = next(iterator)
                return {"engine": "same-frame-glyph-reference-v1", "value": value,
                        "scores": {"0": 1 if value == "0" else .2, "1": 1 if value == "1" else .2}, "referenceSimilarity": .2}
            with self.subTest(values=values), self.assertRaises(ValueError):
                evaluate_samples(association()["samples"], read)

    def test_matching_hash_cannot_hide_an_input_pin_or_wrong_radix(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.circ"
            for direction, radix in [("input", "2"), ("output", "16")]:
                source = (ROOT / "examples/io-held-output.circ").read_text()
                path.write_text(source.replace('name="type" val="output"', 'name="type" val="' + direction + '"', 1)
                                .replace('name="radix" val="2"', 'name="radix" val="' + radix + '"', 1))
                with self.subTest(direction=direction, radix=radix), self.assertRaises(ValueError):
                    verify_fixture(path, digest(path))

    def test_matching_hash_cannot_hide_a_changed_fixed_reference_or_disconnected_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.circ"
            source = (ROOT / "examples/io-held-output.circ").read_text()
            for changed in [source.replace('name="value" val="0x1"', 'name="value" val="0x0"'),
                            source.replace('<wire from="(140,200)" to="(240,200)"/>', ''),
                            source.replace('loc="(240,280)"', 'loc="(240,200)"')]:
                path.write_text(changed)
                with self.assertRaises(ValueError):
                    verify_fixture(path, digest(path))


@unittest.skipUnless(sys.platform == "darwin" and shutil.which("xcrun"), "ImageIO glyph checks require the installed macOS SDK")
class OutputGlyphIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="io-output-glyph-tests-")
        cls.root = Path(cls.temporary.name)
        cls.executable = cls.root / "compare"
        subprocess.run(["xcrun", "swiftc", str(ROOT / "scripts/io_output_glyph_compare.swift"), "-o", str(cls.executable)], check=True, capture_output=True, timeout=30)
        generator = cls.root / "generate.swift"
        generator.write_text('''import Foundation
import AppKit
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let cases = ["0", "1", "?", "X", "E", "I", "O", "OUT_HELD", "blank", "blue", "solid", "same-refs"]
for value in cases {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 660, pixelsHigh: 200, bitsPerSample: 8,
                                  samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
    NSColor.white.setFill(); NSRect(x: 0, y: 0, width: 660, height: 200).fill()
    let font = NSFont.monospacedSystemFont(ofSize: 48, weight: .regular)
    func draw(_ text: String, _ center: CGFloat, _ color: NSColor) {
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        let size = (text as NSString).size(withAttributes: attrs)
        (text as NSString).draw(at: NSPoint(x: center - size.width / 2, y: 100 - size.height / 2), withAttributes: attrs)
    }
    draw("0", 300, .black); draw(value == "same-refs" ? "0" : "1", 500, .systemGreen)
    if value == "blue" || value == "solid" {
        (value == "blue" ? NSColor(red: 184/255, green: 215/255, blue: 1, alpha: 1) : NSColor.black).setFill()
        NSRect(x: 60, y: 50, width: 80, height: 100).fill()
    } else if value != "blank" { draw(value == "same-refs" ? "0" : value, 100, value == "1" ? .black : .systemGreen) }
    NSGraphicsContext.restoreGraphicsState()
    try bitmap.representation(using: .png, properties: [:])!.write(to: root.appendingPathComponent(value + ".png"))
}
''')
        subprocess.run(["xcrun", "swift", str(generator), str(cls.root)], check=True, capture_output=True, timeout=30)
        cls.rois = {"target": roi(60), "low": roi(260), "high": roi(460)}

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_literal_digits_match_same_frame_references_with_different_ink_colors(self):
        for value in ["0", "1"]:
            with self.subTest(value=value):
                self.assertEqual(value, recognize(self.executable, self.root / (value + ".png"), self.rois)["value"])

    def test_unknown_blank_label_solid_color_and_identical_references_are_rejected(self):
        for value in ["?", "X", "E", "I", "O", "OUT_HELD", "blank", "blue", "solid", "same-refs"]:
            with self.subTest(value=value):
                try:
                    result = recognize(self.executable, self.root / (value + ".png"), self.rois)
                except subprocess.CalledProcessError:
                    continue
                self.assertEqual("unknown", result["value"])

    def test_capture_receipts_must_bind_the_actual_png_and_times(self):
        sample = association()["samples"][0]
        receipt = {"recordType": "ios-probe-frame-capture", "schemaVersion": 1, "collectionStatus": "captured",
                   "testCase": native_metadata()["testCase"], "simulatorUDID": "synthetic-no-device",
                   "frameSha256": digest(self.root / "0.png"), "frame": str(self.root / "0.png"),
                   "timeSource": "time.time Unix seconds on the iOS Simulator host",
                   "captureCommand": ["xcrun", "simctl", "io", "synthetic-no-device", "screenshot", "--type=png", str(self.root / "0.png")],
                   "captureStartedUnixSeconds": sample["captureStartedUnixSeconds"], "captureReturnedUnixSeconds": sample["captureReturnedUnixSeconds"]}
        path = self.root / sample["captureEvidence"]
        path.write_text(json.dumps(receipt))
        verify_capture(sample, self.root, native_metadata()["testCase"])
        for key, value in [("frameSha256", "changed"), ("testCase", "another-test"), ("captureReturnedUnixSeconds", 99.9),
                           ("captureCommand", ["synthetic-test"]), ("frame", "/another.png"), ("timeSource", "unspecified")]:
            bad = dict(receipt, **{key: value})
            path.write_text(json.dumps(bad))
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_capture(sample, self.root, native_metadata()["testCase"])

    def test_cli_reads_four_actual_synthetic_frames_and_preserves_partial_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.circ"
            shutil.copyfile(ROOT / "examples/io-held-output.circ", fixture)
            data, link = native_metadata(), association()
            data["fixtureSha256"] = digest(fixture)
            for index, sample in enumerate(link["samples"]):
                source = self.root / sample["png"]
                sample["png"] = str(source)
                receipt = {"recordType": "ios-probe-frame-capture", "schemaVersion": 1, "collectionStatus": "captured",
                           "testCase": data["testCase"], "simulatorUDID": "synthetic-no-device", "frame": str(source),
                           "timeSource": "time.time Unix seconds on the iOS Simulator host",
                           "captureCommand": ["xcrun", "simctl", "io", "synthetic-no-device", "screenshot", "--type=png", str(source)],
                           "frameSha256": digest(source), **{key: sample[key] for key in ["captureStartedUnixSeconds", "captureReturnedUnixSeconds"]}}
                (root / sample["captureEvidence"]).write_text(json.dumps(receipt))
            (root / "native.json").write_text(json.dumps(data)); (root / "association.json").write_text(json.dumps(link))
            arguments = ["--metadata", str(root / "native.json"), "--association", str(root / "association.json"),
                         "--fixture", str(fixture), "--output", str(root / "result.json")]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, main(arguments))
            result = json.loads((root / "result.json").read_text())
            self.assertEqual("not-inferred", result["nativeExecution"])
            self.assertEqual("requires-separate-review", result["temporalQualification"])
            self.assertEqual(["0", "1", "1", "0"], [item["recognition"]["value"] for item in result["observations"]])
            fixture.write_text(fixture.read_text().replace('val="output"', 'val="input"', 1))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, main(arguments))


if __name__ == "__main__":
    unittest.main()
