# Final acceptance delta verification at `8000d09a`

App candidate `8000d09abdcbfeaf5ce9afc5e3b2e9b913db84ac`. The only product change since `2653be42` is `0f32bd48` (`CircuitSemanticOverlay.kt`, #365). Evolution `d58a61e0`, JAR `d0b287d1695bc12f…`. macOS, Xcode 27.2, Android API 35 and 31 emulators, and iOS 27.0 (24A434) simulator. Emulator and simulator coverage only.

Only `7aaf53c6` (iOS harness, below) and this record landed after the candidate. The original run is documented in [`gate-2653be42`](../gate-2653be42).

Artifacts include APK `813d809ec57f7ed3b760c4d99b244b4321143ed64a9ac625c9ab98a97804db0a` (identical to `0f32bd48`), test APK `9633bc192dbc8a57f6d407878b362248633a6ef5e4a890432bcd5634322edd9d`, and snapshot APK `31bb9550c15990007e6091e230d0c6bc29580093dff26c361cc2f9fedb57bb41`. iOS app tree `9125ce98b7e70b25c478e61b59b5f491c18ab7f9de5db0087cb480f8acf8fa26`. One build per platform.

## New runs at `8000d09a`

| Area | Environment | Command | Result |
| --- | --- | --- | --- |
| Integration | macOS, JDK 21, via launchd | `./scripts/verify.sh all` | Passed in 195 s |
| `scripts` | macOS | `cd scripts && python3 -m unittest` | 530 OK |
| Matrix | macOS | `python3 scripts/parity_matrix.py --check` | Passed, 521 rows, 0 requiring reverification, 100.0% verified |
| `io-showcase` | `logisim35` phone, `logisim35tab` tablet | `scripts/e2e-android.py --scenario io-showcase` | PASS on both without retries |
| `layout12`, font scales 1 and 2 | 320 × 568 dp small phone, tablet | `scripts/e2e-android.py --scenario layout12 --layout12-font both` | PASS on both without retries |
| Mixed input, 10 cases | Phone and tablet | `scripts/run-android-mixed-input.py --case <caso>` | 10/10 on each profile without retries |
| Display preferences (310–316) | Phone | `scripts/android_display_preferences_proof.py` | PASS on the second run, detailed below |
| TalkBack base flow | API 35 (TalkBack 15.0.0.639625893), API 31 (unpatched 14.1) | `receipts/talkback-api{35,31}-run.sh`, `gate-2653be42/talkback-flow.py` | All steps PASS, `duplicate-applied` now shows 4→5 nodes |
| TalkBack #365 assertion | API 35 and 31 | `talkback-flow-365.py` | All steps PASS. Five nodes with disjoint bounds. The second AND is reachable by explore by touch and selectable with a double tap. |
| iPad 27.0, `replacePresentedText` and canvas | `E47EAD1D` | `scripts/run-ios-e2e.py --only-testing <21 testes>` | 12 PASS on the first run, SAP-1 PASS after the harness fix, 8 harness failures tracked in #367 |
| iPad 27.0, FPGA tick and shortcuts | `E47EAD1D` | `scripts/run-ios-fpga-tick.py` | 4/4 PASS |
| iPhone 27.0, canvas | `3707429D` | `scripts/run-ios-e2e.py --only-testing <10 testes>` | 8 PASS on the first run, including SAP-1 with the fix. External provider PASS after the fix. `testProjectMouseMappings` runs only on iPad. |
| `.circ` in Evolution | JAR `d58a61e0` | `ReopenAll`, `receipts/reopen-*` | 217 opened, 4 are intentionally invalid `incompatible.circ` fixtures |

The harness’s native menu at `8000d09a` ran on iPad. It used Select All 23 times in `testAccessibleAndGateExplorerFlowSaveAndReopen`, 8 in `testEditingConnectionsHistoryAndViewport`, 5 in `testEditorExportsPixelsAndCancelsNativeDelivery`, 1 in `testOriginal271OpenEditSimulateSaveAndReopen`, and 3 in FPGA tick. The fallback gesture opened the menu after the first gesture failed in two cases, `testOriginal271…` and `testMapsThatBindTheClockKeepThePhysicalInput`.

### Observed failures and causes

- **`testSap1SequentialExecution` on iPad.** The canvas showed `0000` and `1000`, and node `import-105` showed 8. The test sorted the two displays by target center, and #365 moved a target enough to reverse the order. `7aaf53c6` changed sorting to use the label column. PASS on iPad and iPhone with the same app.
- **`testPeerDocumentCopyEditRestartAndConflict` on iPhone.** The tap on Components hit Insert component while the sheet was opening. This was line 244, the same step that passed only on a retry in the `2653be42` gate. `7aaf53c6` fixed it, and the case passed without a retry.
- **`android_display_preferences_proof.py` on phone.** The first run stopped with `pla-5x2.txt` outside the file picker’s visible area. The AVD had accumulated 43 `logisim-fpga-*` files from previous FPGA runs. The test passed after removing them. The screenshot and successful run after cleanup suggest the picker sorts by name.

## Evidence reused from `2653be42`

Only `CircuitSemanticOverlay.kt` changed in the product. It adds semantics nodes when targets collide and does not consume pointer input. The groups below do not read `canvas-component`.

- Android: `files`, `editing`, `sequential`, `layout`, `unicode`, `inspector`, `legacy-original`, `portable`, and `visual-export34` on phone and tablet, plus FPGA and shortcuts.
- iOS: file picker, matrix on 27.2 (known 27.2 limitation still applies), HDL (`#364` with harness `8000d09a` and an identical product, as `git diff cd0d9abc 8000d09a` is empty for product directories), and `VisualExport` on iPhone (`#362`). The one iPhone 27.2 export failure waited for the Files collection, a known picker limitation (#362), and was left there.
- JVM oracles, companion (264 OK, 5 skips), and earlier `.circ` checks are independent of the overlay.

## Outside this verification

- #367 tracks eight outdated iOS harness tests outside the matrix accepted in #240. They fail before reaching the Edit menu. Later harness changes resolved the issue. [Issue #367 evidence](issue-367).
- #366 tracks long presses on glyphs. [Issue #366 evidence](issue-366).
- `testProjectMouseMappings` and `testNarrowInspector…` on iPad are covered by #367.
