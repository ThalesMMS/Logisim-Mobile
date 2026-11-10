"""Synthetic parser and negative-oracle checks. These tests do not run an app."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from io_held_frame_oracle import inspect_frame, main, read_ppm


def ppm(color=(238, 238, 238), knob=None):
    width = height = 132
    data = bytearray(color * (width * height))
    if knob:
        for y in range(height):
            for x in range(width):
                if (x + .5 - knob[0] * width) ** 2 + (y + .5 - knob[1] * height) ** 2 <= 6 ** 2:
                    offset = (y * width + x) * 3
                    data[offset:offset + 3] = bytes((255, 0, 0))
    return b"P6\n132 132\n255\n" + data


def metadata(component="IO_BUTTON"):
    return {"recordType": "native-touch-video-region", "component": component, "phase": "after-release",
            "unixSeconds": 103, "requestedGesture": {"apiStartedUnixSeconds": 100, "apiReturnedUnixSeconds": 102},
            "roiNormalizedToApplication": {"x": 0, "y": 0, "width": 1, "height": 1}}


def association(component="IO_BUTTON", phase="held"):
    return {"schemaVersion": 1, "testCase": "IoEndToEndTests.testIoMomentaryControlsAndJoystick",
            "metadataAttachmentName": "io-touch-" + component.lower() + "-after-release",
            "video": {"sha256": "abc", "ptsZeroUnixSeconds": 99, "ptsMappingEvidence": "retained/recording-clock.json"},
            "sample": {"ptsSeconds": 2 if phase == "held" else 5, "phase": phase, "frameMatchesApplication": True,
                       "coordinateMappingEvidence": "retained/crop-review.json", "extractionEvidence": "retained/ffmpeg.log"}}


class HeldFrameOracleTests(unittest.TestCase):
    def test_ppm_header_preserves_whitespace_valued_first_pixel(self):
        data = b"P6\n# comment\n8 8\n255\n" + bytes((10, 32, 13)) * 64
        self.assertEqual(bytes((10, 32, 13)) * 64, read_ppm(data)[2])
        with self.assertRaisesRegex(ValueError, "one complete"):
            read_ppm(data + b"\n")

    def test_blue_held_background_passes_and_gray_cannot_claim_held(self):
        self.assertEqual("passed", inspect_frame(ppm((184, 215, 255)), metadata(), association(), "abc")["pixelCheck"])
        self.assertEqual("failed", inspect_frame(ppm(), metadata(), association(), "abc")["pixelCheck"])

    def test_gray_released_background_passes_and_blue_cannot_claim_released(self):
        self.assertEqual("passed", inspect_frame(ppm(), metadata(), association(phase="released"), "abc")["pixelCheck"])
        self.assertEqual("failed", inspect_frame(ppm((184, 215, 255)), metadata(), association(phase="released"), "abc")["pixelCheck"])

    def test_keypad_uses_its_own_exact_attachment(self):
        values = metadata("IO_KEYPAD"), association("IO_KEYPAD")
        self.assertEqual("passed", inspect_frame(ppm((184, 215, 255)), *values, "abc")["pixelCheck"])
        with self.assertRaisesRegex(ValueError, "attachment names"):
            inspect_frame(ppm((184, 215, 255)), values[0], association(), "abc")

    def test_centered_joystick_cannot_claim_held_top_right(self):
        values = metadata("IO_JOYSTICK"), association("IO_JOYSTICK")
        self.assertEqual("passed", inspect_frame(ppm(knob=(.5 + .4 * 120 / 132, .5 - .4 * 120 / 132)), *values, "abc")["pixelCheck"])
        self.assertEqual("failed", inspect_frame(ppm(knob=(.5, .5)), *values, "abc")["pixelCheck"])
        self.assertEqual("failed", inspect_frame(ppm((255, 0, 0)), *values, "abc")["pixelCheck"])

    def test_joystick_release_requires_center(self):
        values = metadata("IO_JOYSTICK"), association("IO_JOYSTICK", "released")
        self.assertEqual("passed", inspect_frame(ppm(knob=(.5, .5)), *values, "abc")["pixelCheck"])
        self.assertEqual("failed", inspect_frame(ppm(knob=(.86, .14)), *values, "abc")["pixelCheck"])

    def test_held_sample_outside_the_api_window_is_rejected(self):
        for pts in [0, 1, 3, 4]:
            value = association()
            value["sample"]["ptsSeconds"] = pts
            with self.subTest(pts=pts), self.assertRaisesRegex(ValueError, "outside"):
                inspect_frame(ppm((184, 215, 255)), metadata(), value, "abc")

    def test_release_before_native_after_release_observation_is_rejected(self):
        value = association(phase="released")
        value["sample"]["ptsSeconds"] = 3.5
        with self.assertRaisesRegex(ValueError, "predates"):
            inspect_frame(ppm(), metadata(), value, "abc")

    def test_unverified_mapping_or_changed_video_cannot_pass(self):
        for mutate in [lambda value: value["sample"].update(frameMatchesApplication=False),
                       lambda value: value["video"].update(ptsMappingEvidence=""),
                       lambda value: value.update(testCase="")]:
            value = association()
            mutate(value)
            with self.assertRaises(ValueError):
                inspect_frame(ppm((184, 215, 255)), metadata(), value, "abc")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            inspect_frame(ppm((184, 215, 255)), metadata(), association(), "different")

    def test_cli_preserves_partial_scope_even_for_correct_pixels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "video.mp4").write_bytes(b"synthetic-video-identity-only")
            (root / "frame.ppm").write_bytes(ppm((184, 215, 255)))
            (root / "metadata.json").write_text(json.dumps(metadata()))
            value = association()
            value["video"]["sha256"] = hashlib.sha256((root / "video.mp4").read_bytes()).hexdigest()
            (root / "association.json").write_text(json.dumps(value))
            arguments = [part for key, name in [("frame", "frame.ppm"), ("metadata", "metadata.json"),
                         ("association", "association.json"), ("video", "video.mp4"), ("output", "result.json")]
                         for part in ["--" + key, str(root / name)]]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, main(arguments))
            result = json.loads((root / "result.json").read_text())
            self.assertEqual("sampled-frame-only", result["scope"])
            self.assertEqual("not-inferred", result["nativeExecution"])
            self.assertEqual("requires-separate-review", result["temporalQualification"])
            self.assertEqual("not-verified", result["functionalHeldOutputQualification"])
            self.assertEqual(value["video"]["sha256"], result["sha256"]["video"])
            value["video"]["sha256"] = "changed"
            (root / "association.json").write_text(json.dumps(value))
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, main(arguments))


if __name__ == "__main__":
    unittest.main()
