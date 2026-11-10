# Failed pinch with retained ZoomOut comparisons

Exactly one diagnostic pinch case ran on `emulator-5558` with test source
`e7fb33f8d76c6ec8bc7a7046e7c252fb9386d630` and the frozen `b06cefcc` app.
The case failed after 10.854 seconds with
`Touch pinch did not preserve the injected span ratio`.
The complete Native result remains `FAIL/NOT_QUALIFIED`.

Preparation001 was cancelled before device access. Its record contains zero
Native calls and an empty command list. It never executed the case and is not
a first Native attempt. The separate Native002 record contains one invocation.

The injector recorded 17 accepted MotionEvents: down, second-pointer down,
13 moves, second-pointer up, and up. Acceptance does not qualify the pinch
ratio or physical touch behavior. The literal span values consumed by the
failing ratio assertion were not retained. This packet does not reconstruct
those values from other measurements or override the recorded failure.

The dedicated ZoomOut collector retained all four preparation groups. The first
three completed after one poll each; the fourth completed after 102 polls.
The 117 records occupy 28,298 bytes, excluding the header and summary, and report
no drops, oversized records, invalid records, encoding errors, or incompleteness.
These are preparation comparisons, separate from the later failed ratio check.

The Canvas trace retained all 34 input records. Its state channel recorded 198
attempts, retained 192, and dropped six. Global sequences 2 through 7 are absent.
They precede the first retained fixture state record at sequence 13; their
contents are unknown. The whole Canvas trace is therefore incomplete. This
packet does not infer the missing states or describe the entire trace as complete.

The private archive contains seven files. The fixture XML and pre-pinch XML are
byte-identical. Post-ratio document export was not reached. A single failure PNG
is retained; no before PNG was captured for a visual size comparison. Neither
the image nor separate viewport observations establish that the circuit doubled
in size.

The handoff record confirms the standard app/test/helper hashes were restored,
the recorded settings and ANR state matched their baselines, reader settings
were off, all three owned package PID results were empty, and Home owned focus.
These statements describe the recorded handoff, not the emulator's later state.
The diagnostic APKs are identified separately from the restored standard APKs.

The attached offline analysis contains exactly its ten indexed files and its
index. The pure-function replay records `PASS_OFFLINE_ONLY`, with 1,867 input
identities unchanged. The Android-region adjustment is a separately identified
model, not an execution of Android or Compose. Modeled endpoint spans of 417
and 227 are not the literal operands consumed by the failing assertion. The
7.5-pixel modeled anchor displacement concerns a later assertion that was not
reached. It is a prediction, not another observed failure. The 101 unsuccessful
ZoomOut polls do not isolate a cache fault. The analysis preserves the Native
failure and does not authorize another run.

`index.json` binds each of 185 inputs to its raw size/SHA-256 and archived
size/SHA-256. JSON files over 64 KiB use gzip with mtime zero and no embedded
filename. All other inputs retain their original bytes. No APK, class or JAR
binary is copied. Origin paths are provenance and are not read by verification.
The copied drivers are evidence and are never executed by the verifier.

```sh
python3 verify.py
python3 -m unittest discover -s . -p test_verify.py -v
```

`VERIFIED_ARCHIVE_ONLY` confirms integrity and the retained failure boundaries.
It never qualifies a successful pinch, document preservation after the ratio
check, cache behavior, physical input devices, or issue #27. No device query,
build, copied driver, or Native case runs during verification.
