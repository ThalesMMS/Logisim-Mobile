# Final independent acceptance review of issue #53

Recorded-artifact acceptance: **PASS**. All four platform/mode flows now have passed native receipts, four snapshots, two actual app exports, same-ID restart, fresh-ID reopen, preserved definitions/instances/metadata and outputs 8/3. Twelve fresh independent offline checks passed, including separate Desktop reopening of every before/after export. Current-main iOS native execution remains **NOT_RUN**, pending the two planned targeted retests on the final app. No issue was closed by this reviewer.

| Platform and mode | Native result | Export pair | Fresh independent checks |
| --- | --- | --- | --- |
| Android bundle | PASS, 204.06 s, product 59d5606b | 2 actual files, 5,395 bytes each, SHA-256 930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113 | Storage + Desktop before + Desktop after PASS. The original nine-check replay reports are preserved byte-for-byte, without rerunning them. |
| Android folder | PASS, 217.81 s, product 59d5606b | 2 actual files, 4,905 bytes each, SHA-256 7c0c1b4a4572a29c14273d8dd676afe43ef70f41e144c73ca4d26142b1a5c8bd | New independent storage + Desktop before + Desktop after PASS. Imported/restored 13dc653f7acf119c9f70fe84ad1aa6a1; fresh reopen ef5cd59f81bd8ad0e9d2a63a43b78854. |
| iOS bundle | PASS, 222.379 s, producer b9bd71ae | 2 actual files, 5,395 bytes each, SHA-256 930cebe2bffa952f917a6c7fff5b23fceb2444ea8a05b5c969d225df35ef4113 | Storage + Desktop before + Desktop after PASS. |
| iOS folder | PASS, 230.970 s, producer b9bd71ae | 2 actual files, 4,905 bytes each, SHA-256 3ffe3b61443a982cf54788b18cc65da954e01684f6ae9bcf123c1d297e2fc26b | Storage + Desktop before + Desktop after PASS. |

The final Android-folder proof is exact commit 2832d29b053da7026540dcc63bd62aed1d040845, docs/validation/portable53-android-folder-pass. Its actual receipt is PASSED, with opposite before/after restart stages, and all three canvas captures contain the exact labels and values 00001000 and 00000011. Replayed bytes contain three definitions, 11 components, seven wires, four independent mobile runtime child states, relative library paths and intact source/main metadata. Folder import intentionally captures only referenced .circ sources; --allow-no-opaque was used in its three strict checks. Source attachment is identical after restart; reopening persists the exact selected emitted archive. No editable native-file association is inherited. The final run is separate from every preserved FAILED/partial attempt.

The earlier Android bundle Desktop-before report is a genuinely independent execution at /tmp/logisim-portable53-review-replay/android-bundle-desktop-before.json. Its SHA-256 remains 8d753ff705d2f2c580a8e82ec67c59fec4cc1b6f71577c9bd72de886954e5670. The original 495fccc7 package checked Desktop-after and the identical peer; do not attribute this later independent before report to that original package. Both this report and the original nine-check index are copied unchanged into the review package.

## Current-main attribution after adoption

Main 85da031e4ed11474c2e763976392729810ef142d now contains the Compose 1.13.0-beta02 pins, OnFocusBehavior.DoNothing and keyboard-aware tool header that differed in the earlier stable-main comparison. The prior finding that those changes were unintegrated is historical and superseded. This does not make the old b9bd receipt a current-main execution.

A fresh selected-path comparison still finds 34/38 byte-identical files between b9bd and main85da. Every selected codec, bundle, durable storage/model/format, portable model/tool/bridge and iOS asset/directory adapter matches. Four changed files are App.kt, CatalogWorkspaceRuntime.kt, EditorState.kt and SimulationRunController.kt. They include typed simulation request handling, imported initialization ordering, queued lifecycle handling and the header zIndex. These are real executable differences on the import/restart path, so the planned two targeted #53 iOS retests on the final main-derived artifact are the appropriate attribution check. They do not require resolving all of #27 or unrelated hardware/release work. No failure of the final app is inferred while those retests are NOT_RUN.

The prior trace found no #53 dependence on Find/Command-A/clipboard or a Beta-exclusive control. All 71 passed iOS native trees have zero Keyboard/TextField/TextView nodes. The test uses canonical enabled/hittable buttons and UIKit Files, and its visible-button-first helper avoids a defective Beta sheet frame without depending on that defect. The feature proof remains valid for its original artifacts. The source and native scope must stay explicit when publishing or closing the owning issue.

Android native evidence remains attributed to product59 with editor APK SHA aefbd33fd958a32c81e35aa667b19ccc9ea96609e80107281204dbbd1a8cd546, matching post-run readback along with the test APK and old snapshot helper. The adopted main is not retroactively substituted for that APK. Physical-device tests and performance claims are outside this acceptance report.

## Verification records

- Exact native evidence commits: Android bundle 495fccc7353f88b1d861fa6fc1d0b25b2a488084; iOS bundle/folder b811fd26e4fdb98cc0a9f9d9af6c29117836605e; Android folder 2832d29b053da7026540dcc63bd62aed1d040845.
- Original nine replay index: /tmp/logisim-portable53-review-replay/results.json. New final-folder replay index: /tmp/logisim-portable53-review-replay/folder-final-results.json.
- Direct core probe passed stable identity, shared definition, missing source, cycle, disappearance, replacement identity, revision change and XML change. Existing CircLibraryCodecTest XML has 9 passed, zero skips/failures/errors.
- Integrity reports: /tmp/logisim-portable53-integrity-review.md and /tmp/logisim-portable53-folder-integrity-review.md. No original evidence bytes were rewritten.
- New main source comparison: /tmp/logisim-portable53-adopted-main-source-comparison.json. Earlier UI source trace: /tmp/logisim-portable53-ios-ui-provenance-review.md.

No production source or device state changed during this review. Replays used frozen unmodified Kotlin/Python oracle sources and pinned core/Desktop JARs directly through Java21/Kotlin2.4.21, without Gradle or native control. A separate documentary review worktree is used only to preserve these review artifacts for root integration. Root owns push, native final-app tests and issue state.
