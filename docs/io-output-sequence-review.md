# Checking the visible output during a hold

`scripts/io_output_sequence_oracle.py` checks the value drawn inside the Canvas output Pin `OUT_HELD`. It requires four retained PNGs with observed capture intervals. The expected sequence is `0,1,1,0`: a zero before the XCTest gesture, two high samples during it, and a zero afterward.

The tool is prepared and tested with synthetic images. Native held-output execution remains **NOT_RUN** until the platform owner runs the case and reviews the retained evidence. A successful tool result establishes only the sampled output glyphs and their declared capture intervals. The result always retains `nativeExecution: not-inferred` and `temporalQualification: requires-separate-review`. It does not measure physical DOWN/UP or claim that every intervening frame had the expected value.

## Native case and references

Use `IoEndToEndTests.testIoCanvasButtonHeldOutputVideo` with `examples/io-held-output.circ`. That case attaches `io-canvas-held-output-sequence.json`, the source circuit, before/after native descriptions, geometry and screenshots. It holds the actual Canvas Button for four seconds and verifies the released output before and after the gesture.

Stage the fixture path under `Run.fixtures.ioHeld`. The native metadata must identify the Simulator UDID. If XCTest does not inherit `SIMULATOR_UDID`, stage that same UDID under `Run.simulatorUDID`. The checker requires the declared Simulator/Foundation clock source and matching screenshot-receipt UDID; it does not combine a physical device's clock with host capture intervals.

The circuit has two additional output Pins. `REF_LOW` is connected to constant zero; `REF_HIGH` is connected to constant one. The case verifies their literal values through the native Canvas description before and after the gesture. All three output Pins have width 1 and radix 2. The checker also requires the complete six-component fixture, its positions, the two fixed constant values and three independent wires. A matching hash alone does not turn disconnected Pins into valid reference sources. The metadata records distinct glyph ROIs and checks stable geometry across the gesture. The glyph ROI is inside the drawing body. The minimum 48 point accessibility target is larger than that body and cannot serve as the crop.

The classifier compares `OUT_HELD` with the two reference glyphs in each PNG. It ignores the hue of the ink. It never reads the Button's fill color as an output value. It requires at least 0.90 shape similarity and a margin of at least 0.15 over the other reference. If the references are indistinguishable, the crop is blank or solid, or the target does not match either digit, the result is unknown and the sequence fails.

An Apple Vision spike did not qualify exact digit recognition. Its fast mode read synthetic `1` as `I`, and accurate modes exceeded the bounded local test times. This classifier uses the observed zero and one references rather than substituting letters for digits. Synthetic tests also reject `?`, `X`, `E`, `I`, `O`, a label, blank content, solid colors and identical references.

## Collecting frames

The iOS owner runs the passive collector only while holding the simulator lease. The collector takes screenshots, records host clock intervals, and sends no input or launch command:

```sh
python3 scripts/ios_capture_probe_frames.py \
  --udid EFC-UDID \
  --test-case IoEndToEndTests.testIoCanvasButtonHeldOutputVideo \
  --output-dir /absolute/path/to/new-capture-directory \
  --duration-seconds 30 \
  --interval-seconds .15
```

Retain `capture-session.json`, `captures.jsonl`, each PNG, and each `.capture.json` receipt. The requested interval is a scheduling target. Use the actual capture start and return timestamps from the receipts. If the collector cannot capture a frame within the required budget, the case fails that budget.

Create an explicit association with the four selected receipts. Keep run directories separate. Preserve the native metadata attachment and exact testcase name rather than pairing files by a similar filename.

```json
{
  "schemaVersion": 1,
  "testCase": "IoEndToEndTests.testIoCanvasButtonHeldOutputVideo",
  "metadataAttachmentName": "io-canvas-held-output-sequence.json",
  "sampleTimeSource": "captureInterval",
  "frameMatchesApplication": true,
  "coordinateMappingEvidence": "/absolute/path/to/crop-review.json",
  "samples": [
    {"phase": "pre", "png": "pre.png", "captureEvidence": "pre.capture.json", "captureStartedUnixSeconds": 99.6, "captureReturnedUnixSeconds": 99.8},
    {"phase": "held", "png": "held-a.png", "captureEvidence": "held-a.capture.json", "captureStartedUnixSeconds": 100.2, "captureReturnedUnixSeconds": 100.4},
    {"phase": "held", "png": "held-b.png", "captureEvidence": "held-b.capture.json", "captureStartedUnixSeconds": 103.5, "captureReturnedUnixSeconds": 103.7},
    {"phase": "post", "png": "post.png", "captureEvidence": "post.capture.json", "captureStartedUnixSeconds": 104.1, "captureReturnedUnixSeconds": 104.3}
  ]
}
```

These example timestamps assume an API call from 100 to 104. Replace them with the actual native metadata and capture receipts. Each receipt must have `recordType: ios-probe-frame-capture`, schema version 1, status `captured`, the same testcase, the actual simulator UDID and read-only `simctl screenshot` command, and the matching PNG path, hash and timestamps. The tool reads the receipts and verifies those bindings and the host wall-clock source.

The entire capture interval must fit its phase. The first high sample must finish within 0.75 seconds of API invocation. The second high sample must start at least one second after the first sample finishes, lie within the API call, and start within 0.75 seconds of API return. The final zero sample must start after API return and finish within 0.5 seconds of it. These are API-relative limits. Review the retained native sequence to associate them with the actual touch phase.

Before declaring `frameMatchesApplication`, verify the PNG orientation and coordinate space against `applicationFramePoints` and the native geometry. Retain that review at `coordinateMappingEvidence`. The tool cannot identify a misplaced crop from a declared ROI alone. There is no video PTS or frame-rate conversion in this mode. An optional video remains separate visual evidence.

## Running the check

```sh
python3 scripts/io_output_sequence_oracle.py \
  --metadata /absolute/path/to/io-canvas-held-output-sequence.json \
  --association /absolute/path/to/association.json \
  --fixture /absolute/path/to/io-held-output.circ \
  --output /absolute/path/to/output-sequence-check.json
```

The command compiles the current small ImageIO/CoreGraphics comparator with the installed macOS SDK. It uses no Vision model, Gradle or app launch. The result records the comparator binary/source, script, fixture, metadata, association, PNG and capture-receipt hashes. With `--video`, also put its SHA-256 in the association's `videoSha256`; the video is never the timing source.

`python3 scripts/test_io_output_sequence_oracle.py` exercises the actual comparator with synthetic PNGs and verifies the sequence, timing, receipt and fixture rejection paths. The glyph crop must be at least 8 by 12 pixels and contain at least a ten-pixel-high glyph. Small or ambiguous native values fail conservatively. Qualify the thresholds against the actual native fixture run before using the result to close issue #44.
