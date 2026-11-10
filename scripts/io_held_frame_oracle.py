"""Check a declared I/O video frame without qualifying the native gesture."""
import argparse
import hashlib
import json
import math
from pathlib import Path


def read_ppm(data):
    """Read one 8-bit binary RGB PPM, as emitted by ffmpeg's ppm encoder."""
    offset = 0

    def token():
        nonlocal offset
        while offset < len(data):
            if data[offset:offset + 1] == b"#":
                end = data.find(b"\n", offset)
                if end < 0:
                    raise ValueError("Unterminated PPM comment")
                offset = end + 1
            elif data[offset] in b" \t\r\n":
                offset += 1
            else:
                break
        start = offset
        while offset < len(data) and data[offset] not in b" \t\r\n#":
            offset += 1
        if offset == start:
            raise ValueError("Incomplete PPM header")
        return data[start:offset]

    if token() != b"P6":
        raise ValueError("Frame must be a binary P6 RGB PPM")
    width, height, maximum = int(token()), int(token()), int(token())
    if width < 8 or height < 8 or width * height > 100_000_000 or maximum != 255:
        raise ValueError("Unsupported PPM dimensions or channel depth")
    if offset >= len(data) or data[offset] not in b" \t\r\n":
        raise ValueError("Missing PPM raster separator")
    offset += 2 if data[offset:offset + 2] == b"\r\n" else 1
    pixels = data[offset:]
    if len(pixels) != width * height * 3:
        raise ValueError("Expected exactly one complete RGB frame")
    return width, height, pixels


def number(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} must be a finite number")
    return value


def required_text(value, name):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must identify retained evidence")
    return value


def inspect_frame(frame, metadata, association, video_hash):
    """Validate the supplied association and inspect only its declared frame."""
    if metadata.get("recordType") != "native-touch-video-region" or metadata.get("phase") != "after-release":
        raise ValueError("Use the native after-release metadata with the API time window")
    component = metadata.get("component")
    if component not in {"IO_BUTTON", "IO_KEYPAD", "IO_JOYSTICK"}:
        raise ValueError("Unknown fixture control")
    if association.get("schemaVersion") != 1:
        raise ValueError("Unsupported frame association schema")
    test_case = required_text(association.get("testCase"), "testCase")
    expected_attachment = "io-touch-" + component.lower() + "-after-release"
    if association.get("metadataAttachmentName") != expected_attachment:
        raise ValueError("Frame association and native attachment names differ")
    video = association["video"]
    if video.get("sha256") != video_hash:
        raise ValueError("The retained video differs from the association SHA-256")
    required_text(video.get("ptsMappingEvidence"), "ptsMappingEvidence")
    zero = number(video.get("ptsZeroUnixSeconds"), "ptsZeroUnixSeconds")
    sample = association["sample"]
    phase = sample.get("phase")
    if phase not in {"held", "released"}:
        raise ValueError("The sample phase must be held or released")
    if sample.get("frameMatchesApplication") is not True:
        raise ValueError("The full frame must match the application's recorded coordinate space")
    required_text(sample.get("coordinateMappingEvidence"), "coordinateMappingEvidence")
    required_text(sample.get("extractionEvidence"), "extractionEvidence")
    pts = number(sample.get("ptsSeconds"), "ptsSeconds")
    if pts < 0:
        raise ValueError("Frame PTS cannot be negative")
    unix = zero + pts
    gesture = metadata["requestedGesture"]
    start = number(gesture.get("apiStartedUnixSeconds"), "apiStartedUnixSeconds")
    end = number(gesture.get("apiReturnedUnixSeconds"), "apiReturnedUnixSeconds")
    recorded = number(metadata.get("unixSeconds"), "metadata unixSeconds")
    if not start < end <= recorded:
        raise ValueError("Native gesture timestamps are inconsistent")
    if phase == "held" and not start < unix < end:
        raise ValueError("Held sample is outside the synchronous API time window")
    if phase == "released" and unix < recorded:
        raise ValueError("Released sample predates the native after-release observation")

    width, height, pixels = read_ppm(frame)
    roi = metadata["roiNormalizedToApplication"]
    x, y, rw, rh = [number(roi.get(key), f"ROI {key}") for key in ["x", "y", "width", "height"]]
    if x < 0 or y < 0 or rw <= 0 or rh <= 0 or x + rw > 1 or y + rh > 1:
        raise ValueError("The native control ROI must be inside the application frame")
    left, top, right, bottom = round(x * width), round(y * height), round((x + rw) * width), round((y + rh) * height)
    if right - left < 8 or bottom - top < 8:
        raise ValueError("Control ROI is too small for pixel inspection")
    result = {"testCase": test_case, "metadataAttachmentName": expected_attachment,
              "component": component, "phase": phase, "framePtsSeconds": pts,
              "mappedFrameUnixSeconds": unix, "apiWindowUnixSeconds": [start, end],
              "frameSizePixels": [width, height], "roiPixels": [left, top, right, bottom]}
    if component in {"IO_BUTTON", "IO_KEYPAD"}:
        rgb = (184, 215, 255) if phase == "held" else (238, 238, 238)
        dx, dy = max(1, round((right - left) * .08)), max(1, round((bottom - top) * .08))
        total = (right - left - 2 * dx) * (bottom - top - 2 * dy)
        matched = sum(all(abs(pixels[(row * width + column) * 3 + channel] - rgb[channel]) <= 18 for channel in range(3))
                      for row in range(top + dy, bottom - dy) for column in range(left + dx, right - dx))
        fraction = matched / total
        result["pixels"] = {"expectedRgb": rgb, "channelTolerance": 18, "matchingFraction": fraction, "requiredFraction": .55}
        passed = fraction >= .55
    else:
        count, sum_x, sum_y = 0, 0, 0
        for row in range(top, bottom):
            for column in range(left, right):
                offset = (row * width + column) * 3
                if pixels[offset] > 180 and pixels[offset + 1] < 100 and pixels[offset + 2] < 100:
                    count += 1
                    sum_x += column + .5
                    sum_y += row + .5
        fraction = count / ((right - left) * (bottom - top))
        expected = (.5 + .4 * 120 / 132, .5 - .4 * 120 / 132) if phase == "held" else (.5, .5)
        center = ((sum_x / count - left) / (right - left), (sum_y / count - top) / (bottom - top)) if count else None
        result["pixels"] = {"redPixelCount": count, "redFraction": fraction, "centroidNormalizedToControl": center,
                            "expectedCentroid": expected, "centroidTolerance": .08,
                            "minimumRedPixels": 12, "maximumRedFraction": .1}
        passed = count >= 12 and fraction <= .1 and all(abs(center[i] - expected[i]) <= .08 for i in range(2))
    result["pixelCheck"] = "passed" if passed else "failed"
    return result


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frame", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--association", required=True, type=Path)
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(arguments)
    record = {"recordType": "io-sampled-frame-check", "scope": "sampled-frame-only", "nativeExecution": "not-inferred",
              "temporalQualification": "requires-separate-review", "functionalHeldOutputQualification": "not-verified", "pixelCheck": "failed",
              "limitation": "The XCTest API window does not measure contact times. Confirm the selected video frame shows the declared touch phase. Pixel checks do not prove output probes or the complete gesture."}
    try:
        video_hash = digest(args.video)
        record.update(inspect_frame(args.frame.read_bytes(), json.loads(args.metadata.read_text()),
                                    json.loads(args.association.read_text()), video_hash))
        record["sha256"] = {key: digest(path) for key, path in {"frame": args.frame, "metadata": args.metadata,
                            "association": args.association}.items()}
        record["sha256"]["video"] = video_hash
    except (AssertionError, ValueError, KeyError, OSError, TypeError, AttributeError) as error:
        record.update({"error": str(error), "errorType": type(error).__name__})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"pixelCheck": record["pixelCheck"], "scope": record["scope"], "record": str(args.output)}))
    return 0 if record["pixelCheck"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
