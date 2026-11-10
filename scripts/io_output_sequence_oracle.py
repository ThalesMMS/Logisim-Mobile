"""Compare visible OUTPUT glyphs with fixed 0/1 Pins in the same native frames."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from io_held_frame_oracle import digest, number, required_text

ROOT = Path(__file__).resolve().parents[1]


def verify_fixture(path, expected_hash):
    if digest(path) != expected_hash:
        raise ValueError("The circuit differs from the native fixture SHA-256")
    project = ET.fromstring(path.read_bytes())
    circuits = project.findall("circuit")
    if len(circuits) != 1 or len(circuits[0].findall("comp")) != 6:
        raise ValueError("The held-output fixture must retain its six components and single circuit")
    wires = [frozenset([wire.get("from"), wire.get("to")]) for wire in circuits[0].findall("wire")]
    expected_wires = {frozenset([f"(140,{y})", f"(240,{y})"]) for y in [120, 200, 280]}
    if len(wires) != 3 or set(wires) != expected_wires:
        raise ValueError("The target and fixed reference Pins must retain their three independent wires")
    components = {}
    for component in circuits[0].findall("comp"):
        attrs = {item.get("name"): item.get("val") for item in component.findall("a")}
        if len(attrs) != len(component.findall("a")) or attrs.get("label") in components:
            raise ValueError("The fixture must retain unique component labels and attributes")
        components[attrs.get("label")] = (component, attrs)
    for label, kind, location, attributes in [
        ("BTN_HELD", "Button", "(140,120)", {"press": "active"}),
        ("CONST_LOW", "Constant", "(140,200)", {"width": "1", "value": "0x0"}),
        ("CONST_HIGH", "Constant", "(140,280)", {"width": "1", "value": "0x1"}),
        ("OUT_HELD", "Pin", "(240,120)", {}),
        ("REF_LOW", "Pin", "(240,200)", {}),
        ("REF_HIGH", "Pin", "(240,280)", {}),
    ]:
        component, attrs = components.get(label, (None, {}))
        if component is None or component.get("name") != kind or component.get("loc") != location or any(attrs.get(key) != value for key, value in attributes.items()):
            raise ValueError(f"The fixture changed the declared component, position or fixed source for {label}")
    for label in ["OUT_HELD", "REF_LOW", "REF_HIGH"]:
        component, attrs = components[label]
        if component.get("name") != "Pin" or attrs.get("type") != "output" or attrs.get("width") != "1" or attrs.get("radix") != "2":
            raise ValueError(f"{label} must be an explicit one-bit binary output Pin")


def glyph_rois(metadata):
    rois = {"target": metadata["probe"]["valueRoiNormalizedToApplication"]}
    for key, label, expected in [("low", "REF_LOW", "0"), ("high", "REF_HIGH", "1")]:
        reference = metadata["referenceProbes"][key]
        if reference.get("label") != label or reference.get("kind") != "OUTPUT" or reference.get("width") != 1 or str(reference.get("radix")) != "2":
            raise ValueError("The reference must be its declared one-bit binary output Pin")
        if reference.get("expected") != expected or reference.get("observedBefore") != expected or reference.get("observedAfter") != expected or reference.get("geometryStable") is not True:
            raise ValueError("Reference values and geometry must be observed before and after the gesture")
        rois[key] = reference["valueRoiNormalizedToApplication"]
    bounds = []
    for roi in rois.values():
        x, y, width, height = [number(roi.get(key), f"ROI {key}") for key in ["x", "y", "width", "height"]]
        if min(x, y) < 0 or min(width, height) <= 0 or x + width > 1 or y + height > 1:
            raise ValueError("All glyph ROIs must be inside the application frame")
        bounds.append((x, y, x + width, y + height))
    for i, first in enumerate(bounds):
        for second in bounds[i + 1:]:
            if min(first[2], second[2]) > max(first[0], second[0]) and min(first[3], second[3]) > max(first[1], second[1]):
                raise ValueError("Target and reference glyph crops must not overlap")
    return rois


def validate_association(metadata, association):
    if metadata.get("recordType") != "native-output-probe-sequence" or metadata.get("schemaVersion") != 1:
        raise ValueError("Use the native output-probe sequence metadata")
    test_case = required_text(metadata.get("testCase"), "native testCase")
    if metadata.get("targetEnvironment") != "simulator" or metadata.get("timeSource") != "Foundation.Date Unix seconds in iOS Simulator":
        raise ValueError("Capture intervals require the native Simulator's declared system wall clock")
    required_text(metadata.get("simulatorUDID"), "native simulatorUDID")
    if association.get("schemaVersion") != 1 or association.get("testCase") != test_case:
        raise ValueError("The video association must name the exact native testcase")
    if association.get("metadataAttachmentName") != "io-canvas-held-output-sequence.json":
        raise ValueError("Unexpected native output-probe attachment")
    probe = metadata["probe"]
    if probe.get("label") != "OUT_HELD" or probe.get("kind") != "OUTPUT" or probe.get("width") != 1 or str(probe.get("radix")) != "2":
        raise ValueError("The observed probe must be the one-bit binary OUT_HELD output")
    if metadata.get("expectedSequence") != ["0", "1", "0"]:
        raise ValueError("The native output expectation must be exactly 0, 1, 0")
    if probe.get("geometryStable") is not True:
        raise ValueError("Native pre/post output geometry must remain stable")
    rois = glyph_rois(metadata)
    if association.get("sampleTimeSource") != "captureInterval":
        raise ValueError("Use observed PNG capture intervals, without an inferred video clock")
    if association.get("frameMatchesApplication") is not True:
        raise ValueError("Extracted frames must match the native application coordinate space")
    required_text(association.get("coordinateMappingEvidence"), "coordinateMappingEvidence")
    gesture, limits = metadata["gesture"], metadata["limits"]
    start = number(gesture.get("apiStartedUnixSeconds"), "apiStartedUnixSeconds")
    end = number(gesture.get("apiReturnedUnixSeconds"), "apiReturnedUnixSeconds")
    if gesture.get("requestedHoldSeconds") != 4 or end - start < 4:
        raise ValueError("The source gesture must request and enclose the four-second hold")
    if limits.get("maxPressLatencySeconds") != .75 or limits.get("maxReleaseLatencySeconds") != .5 or limits.get("minHeldSampleSeparationSeconds") != 1 or limits.get("maxHeldEndGapSeconds") != .75:
        raise ValueError("Use the reviewed API-relative latency and held-separation limits")
    samples = association["samples"]
    if not isinstance(samples, list) or [item.get("phase") for item in samples] != ["pre", "held", "held", "post"]:
        raise ValueError("Provide exactly pre, held, held, post frames")
    intervals = []
    for sample in samples:
        lower = number(sample.get("captureStartedUnixSeconds"), "captureStartedUnixSeconds")
        upper = number(sample.get("captureReturnedUnixSeconds"), "captureReturnedUnixSeconds")
        if lower >= upper:
            raise ValueError("Each PNG must have a nonempty observed capture interval")
        required_text(sample.get("png"), "sample PNG path")
        required_text(sample.get("captureEvidence"), "sample captureEvidence")
        intervals.append((lower, upper))
    pre, first, last, post = intervals
    if not pre[1] < first[0] < first[1] < last[0] < last[1] < post[0]:
        raise ValueError("Samples must have ordered, nonoverlapping capture intervals")
    if pre[1] >= start or start - pre[0] > 5:
        raise ValueError("The zero baseline must precede the API call by at most five seconds")
    if first[0] <= start or last[1] >= end:
        raise ValueError("Both held samples must lie inside the API call window")
    if first[1] - start > .75:
        raise ValueError("The first high sample exceeds the 0.75 s API press budget")
    if last[0] - first[1] < 1:
        raise ValueError("Held high samples must be separated by at least one second")
    if end - last[0] > .75:
        raise ValueError("The final held sample must be within 0.75 s of API return")
    if post[0] < end or post[1] - end > .5:
        raise ValueError("The final zero must follow API return within 0.5 s")
    return rois, intervals


def recognize(executable, png, rois):
    result = subprocess.run([str(executable), str(png), json.dumps(rois)],
                            text=True, capture_output=True, check=True, timeout=15)
    return json.loads(result.stdout)


def verify_capture(sample, base, test_case):
    png = base / sample["png"]
    evidence = base / sample["captureEvidence"]
    with png.open("rb") as stream:
        if stream.read(8) != b"\x89PNG\r\n\x1a\n":
            raise ValueError("Capture frames must be actual PNG files")
    receipt = json.loads(evidence.read_text())
    if receipt.get("recordType") != "ios-probe-frame-capture" or receipt.get("schemaVersion") != 1 or receipt.get("collectionStatus") != "captured":
        raise ValueError("Use a successful native frame-capture receipt")
    if receipt.get("testCase") != test_case or receipt.get("frameSha256") != digest(png):
        raise ValueError("The frame capture testcase or PNG SHA-256 differs")
    for key in ["captureStartedUnixSeconds", "captureReturnedUnixSeconds"]:
        if receipt.get(key) != sample[key]:
            raise ValueError("Sample times differ from the actual capture receipt")
    required_text(receipt.get("simulatorUDID"), "capture simulatorUDID")
    command = receipt.get("captureCommand")
    if receipt.get("timeSource") != "time.time Unix seconds on the iOS Simulator host":
        raise ValueError("Use the Simulator host wall-clock capture receipt")
    expected_command = ["xcrun", "simctl", "io", receipt["simulatorUDID"], "screenshot", "--type=png"]
    if not isinstance(command, list) or command[:-1] != expected_command or not isinstance(command[-1], str) or Path(command[-1]).resolve() != png.resolve():
        raise ValueError("The actual read-only screenshot command must bind this PNG and simulator")
    if Path(required_text(receipt.get("frame"), "capture frame")).resolve() != png.resolve():
        raise ValueError("The frame receipt names a different PNG")
    return {"pngSha256": digest(png), "captureReceiptSha256": digest(evidence), "receipt": receipt}


def evaluate_samples(samples, read_glyph):
    observations = []
    for sample, expected in zip(samples, ["0", "1", "1", "0"], strict=True):
        observed = read_glyph(sample)
        scores = observed.get("scores", {})
        expected_score = number(scores.get(expected), "expected glyph similarity")
        other_score = number(scores.get("1" if expected == "0" else "0"), "other glyph similarity")
        if observed.get("engine") != "same-frame-glyph-reference-v1" or observed.get("value") != expected or expected_score < .9 or expected_score - other_score < .15 or number(observed.get("referenceSimilarity"), "reference similarity") > .75:
            raise ValueError(f"Expected literal output {expected} in {sample['phase']}; got {observed}")
        observations.append({"phase": sample["phase"], "expected": expected, "recognition": observed})
    return observations


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["metadata", "association", "fixture", "output"]:
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--video", type=Path, help="Optional retained visual evidence; never used as a timing source")
    args = parser.parse_args(arguments)
    record = {"recordType": "io-visible-output-sequence-check", "status": "failed", "nativeExecution": "not-inferred",
              "temporalQualification": "requires-separate-review", "scope": "visible-output-pin-samples",
              "latencyAnchor": "synchronous-XCTest-API-call",
              "limitation": "The API window does not measure physical contact. Review capture receipts, crop and touch phase in the retained native run. Recognition does not prove unobserved frames."}
    try:
        metadata = json.loads(args.metadata.read_text())
        association = json.loads(args.association.read_text())
        rois, intervals = validate_association(metadata, association)
        video_hash = digest(args.video) if args.video else None
        if args.video and association.get("videoSha256") != video_hash:
            raise ValueError("The optional video differs from its association SHA-256")
        verify_fixture(args.fixture, metadata["fixtureSha256"])
        captures = [verify_capture(sample, args.association.parent, metadata["testCase"]) for sample in association["samples"]]
        simulators = {capture["receipt"]["simulatorUDID"] for capture in captures}
        if len(simulators) != 1 or (metadata.get("simulatorUDID") and metadata["simulatorUDID"] not in simulators):
            raise ValueError("The capture receipts must belong to the native case's single simulator")
        with tempfile.TemporaryDirectory(prefix="io-output-glyph-") as directory:
            executable = Path(directory) / "io-output-glyph-compare"
            subprocess.run(["xcrun", "swiftc", str(ROOT / "scripts/io_output_glyph_compare.swift"), "-o", str(executable)],
                           text=True, capture_output=True, check=True, timeout=30)
            paths = [args.association.parent / sample["png"] for sample in association["samples"]]
            if len({digest(path) for path in paths}) < 2:
                raise ValueError("An unchanged image cannot prove the output transition")
            observations = evaluate_samples(association["samples"], lambda sample: recognize(executable, args.association.parent / sample["png"], rois))
            executable_hash = digest(executable)
        record.update({"status": "passed", "testCase": metadata["testCase"], "probe": metadata["probe"],
                       "observations": observations, "captureIntervalsUnixSeconds": intervals,
                       "captureReceipts": captures,
                       "sha256": {"metadata": digest(args.metadata), "association": digest(args.association),
                                  "fixture": digest(args.fixture), "video": video_hash,
                                  "glyphExecutable": executable_hash,
                                  "glyphSource": digest(ROOT / "scripts/io_output_glyph_compare.swift"),
                                  "sequenceSource": digest(Path(__file__)),
                                  "frames": [digest(path) for path in paths]}})
    except (ValueError, KeyError, OSError, TypeError, AttributeError, ET.ParseError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        record.update({"error": str(error), "errorType": type(error).__name__})
        if isinstance(error, subprocess.CalledProcessError):
            record["toolStderr"] = error.stderr
            record["toolStdout"] = error.stdout
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"status": record["status"], "nativeExecution": record["nativeExecution"], "record": str(args.output)}))
    return 0 if record["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
