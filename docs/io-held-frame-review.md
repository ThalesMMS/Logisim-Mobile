# Reviewing recorded I/O frames

`scripts/io_held_frame_oracle.py` checks one retained RGB frame for the Button or MatrixKeypad control background, or the Joystick knob position. It consumes the production XCTest `native-touch-video-region` attachment from `IoEndToEndTests.swift`. Use the `after-release` attachment because it contains both bounds of the synchronous gesture call.

The script does not execute an app. Its result always has `scope: sampled-frame-only`, `nativeExecution: not-inferred`, `temporalQualification: requires-separate-review` and `functionalHeldOutputQualification: not-verified`. Correct pixels do not prove that the touch reached the app or that a connected output changed. Retain the owner’s separate native execution, output-probe and temporal reviews. The current XCTest assertions run after release, so a pressed Inspector frame cannot qualify the connected output during hold.

## Frame association

Retain the original video, its SHA-256, the testcase-qualified metadata attachment, and the extraction command/log. The native attachment name repeats between executions. Preserve the testcase and run namespace when exporting `.xcresult` attachments.

The frame must show the application coordinate space in `applicationFramePoints`. Check the video dimensions, orientation and crop against a matching native screenshot before setting `frameMatchesApplication` to true. The tool cannot identify system bars, rotated video or an incorrect crop from RGB bytes.

Create an association JSON with actual values and evidence paths from that run:

```json
{
  "schemaVersion": 1,
  "testCase": "Run-UUID/IoEndToEndTests.testIoMomentaryControlsAndJoystick",
  "metadataAttachmentName": "io-touch-io_button-after-release",
  "video": {
    "sha256": "SHA256_OF_ORIGINAL_VIDEO",
    "ptsZeroUnixSeconds": 0,
    "ptsMappingEvidence": "/absolute/path/to/recording-clock.json"
  },
  "sample": {
    "ptsSeconds": 0,
    "phase": "held",
    "frameMatchesApplication": true,
    "coordinateMappingEvidence": "/absolute/path/to/crop-review.json",
    "extractionEvidence": "/absolute/path/to/extraction.log"
  }
}
```

Replace zero timestamps with the observed mapping between the video presentation timestamp and UTC. A shell command launch timestamp alone does not establish the first encoded frame’s UTC. Record the mapping method and its uncertainty. The script requires a held sample inside the XCTest API call interval. That interval includes automation overhead and does not measure physical contact. Review the retained sequence to confirm that the selected frame belongs to the held phase. For a released sample, the timestamp must follow the native `after-release` observation.

Use `IO_BUTTON`, `IO_KEYPAD` or `IO_JOYSTICK` metadata from the same testcase. For the keypad, the ROI is the particular key that XCTest held. The tool rejects a different control’s attachment name and a changed video SHA-256. It records the frame, metadata, association and video hashes in the result.

## Extracting a frame

Use a frame’s actual PTS from the retained video. Do not infer PTS from a filename or nominal frame rate. An example extraction of the exact PTS `12.345678` is:

```sh
ffmpeg -copyts -i recording.mp4 -vf "select='eq(pts*TB,12.345678)',showinfo" -frames:v 1 -fps_mode passthrough -c:v ppm -f image2 frame.ppm
```

Exact selection can produce no output if the timestamp is rounded. In that case, select an observed frame by index and retain `showinfo` output with its actual `pts_time`. Verify that the output contains one frame and that any video start offset is included in the UTC mapping. The oracle accepts one 8-bit binary RGB PPM P6 frame.

```sh
python3 scripts/io_held_frame_oracle.py \
  --frame /absolute/path/to/frame.ppm \
  --metadata /absolute/path/to/io-touch-io_button-after-release.json \
  --association /absolute/path/to/association.json \
  --video /absolute/path/to/recording.mp4 \
  --output /absolute/path/to/frame-check.json
```

## Pixel checks

Button and keypad held controls use RGB `184,215,255`; released controls use `238,238,238`. The check excludes the outer eight percent of the control frame and requires at least 55 percent of interior pixels to match within 18 per channel. This leaves room for the label while rejecting the released gray background as a held result.

The joystick check finds red pixels and checks their centroid against the control frame. The centered position is `0.5,0.5`. The held top-right position for raw `14,-14` is approximately `0.864,0.136`, based on the centered 120 dp drawing in the 132 dp control and its movement scale of 0.8. The radius is deliberately not inferred from dp. The renderer uses `drawCircle` with a pixel radius of 10, so its normalized size changes with pixel density. The script requires at least 12 red pixels, rejects a red area above ten percent of the control, and permits centroid error up to 0.08 of the control size.

`python3 scripts/test_io_held_frame_oracle.py` checks synthetic positive and negative frames, attachment/video association, and timestamp bounds. These synthetic tests provide no native runtime evidence.
