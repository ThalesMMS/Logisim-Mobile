# Canvas input observations on b06cefcc

Exactly one scroll case and one pinch case ran on `emulator-5558` with
`--canvas-input-trace`. The app source was
`b06cefcc42a5c85a4f55d4ba127d4b93c2c38c5e`, based on `3a0a01f1`.
The diagnostic source remains separate from main.

| Case | Recorded result | Native case time | Observation |
| --- | --- | --- | --- |
| Scroll | `PASS`, `PARTIAL` | 12.046 seconds | Three injected scroll events reached the handler and the viewport composition. |
| Pinch | `FAIL`, `NOT_QUALIFIED` | 25.908 seconds | Preparation stopped at `zoom out applied`. No pinch MotionEvent was injected. |

These results do not reclassify earlier failures from other APKs. They do not
qualify physical mouse, trackpad, pen, or touch hardware.

## Scroll completed the observed viewport path

The trace retained 13 input records and 107 state records, with no drops or errors.
The three injected `ACTION_SCROLL` events match received events by uptime,
downTime, source, metaState, tool, axes, and raw coordinates.

| Input sample | Branch | Request, stored, composed sequences | Result |
| --- | --- | --- | --- |
| 2 | Ctrl zoom | 78, 80, 83 | Scale `1.4747826 → 1.6628127`. |
| 5 | Pan | 90, 92, 95 | Pan delta `(-84, +84)` at density `2.625`. |
| 10 | Inverse pan | 106, 108, 111 | Pan restored to `(335.64435, 530.6266)`. |

Each requested viewport equals its stored and composed observations. Immediate
prop rereads still show the previous value. Those rereads do not indicate an
overwrite. Ten other received callbacks are not Scroll events; their origins
are not fully established by this dataset.

The fixture, before-export, and after-export XML files are byte-identical.
Their SHA-256 is
`97dd76d6050d1cb4606c7c6e7e5d9108f1121d227a8f906a9bbd5aa80d17b6b1`.
The existing UI and XML assertions passed without a changed oracle.

## Pinch stopped during preparation

All four ZoomOut callbacks reached storage and composition:

| Request, stored, composed sequences | Composed scale |
| --- | --- |
| 54, 56, 58 | `1.8434782` |
| 59, 61, 63 | `1.4747826` |
| 64, 66, 68 | `1.1798261` |
| 69, 71, 73 | `0.94386095` |

The trace retained all 73 state records and no input records, with no errors or
drops. No later viewport request, stored value, or composition appears in that
trace. The injector receipt contains only its session metadata.

The last preparation wait returned false 273 times over 15.050 seconds. The
accessibility tree reports `118%`, while the existing failure PNG visually shows
`94%`, matching the last composed scale. The AX input and output centers are
`504` and `770`, so their final span is `266` pixels.

The literal span before the fourth ZoomOut was not retained. The generic
readiness rings also discarded earlier records. The final span cannot reconstruct
that comparison or establish why the wait remained false.

## The captures have ordered calls, not an atomic state

The following times use elapsed realtime milliseconds:

| Observation | Recorded time |
| --- | ---: |
| Last composed viewport, sequence 73 | `40219142` |
| Last preparation wait starts | `40219123` |
| Last preparation wait ends | `40234173` |
| Failure snapshot root query starts | `40234276` |
| Failure snapshot root query ends | `40234277` |

The root query occurred 15.134 seconds after composition. The accepted window
was `14571`. `snapshot()` calls `screenshot()` before it queries the root and
recursively reads children. Neither the screenshot nor each child has a recorded
capture timestamp. Source order does not make the returned pixels and node
contents an atomic snapshot. File modification times do not supply that clock.

The divergence is preserved without assigning its cause to a cache, Compose,
the app, or hardware. [The observation plan](cache-observation-plan.md) describes
the missing measurements and the reviewed public APIs. No cache operation or new
pinch execution is part of this record.

## Artifact and cleanup records

The [producer manifest](data/producer/artifact.json) and the preflight bind the
source, catalog, AAPT metadata, and three installed APK hashes:

- App: `c12be1b537b1e02744f057fb36372f3669df25e3385e9c2e7e4fda8a8fc1484b`.
- Test: `f0883691aea034441b4eda8375e424662e78e9a420e7b5f12d225f26e8d45c31`.
- Snapshot helper: `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c`.

The initial preflight wrongly expected one helper instrumentation. The authorized
helper contains two self-targeted instrumentations. That guard failure remains
archived, followed by the completed preflight. No input case ran during that repair.

Final readbacks match all three hashes. The ANR record stayed unchanged. Cleanup
stopped only the three isolated packages, and the device lease was returned.
No subsequent device query, retry, manual viewport change, build, or product patch
was performed by this unit.

## Verify the archive offline

From the repository root:

```sh
python3 docs/validation/canvas-input-diagnostic-b06cefcc/verify.py
```

`VERIFIED` means archive integrity. The verifier still prints scroll as
`PASS/PARTIAL` and pinch as `FAIL/NOT_QUALIFIED`. It does not execute ADB, build an
APK, qualify the pinch, or reinterpret the failure. A portable-copy check passed;
raw tampering and relabeling pinch as PASS were rejected.

[evidence.json](evidence.json) records each raw file's size, SHA-256, and source.
[analysis.json](analysis.json) records derived values, timestamps, and the exact
two case commands. APK binaries are not copied into this archive.
