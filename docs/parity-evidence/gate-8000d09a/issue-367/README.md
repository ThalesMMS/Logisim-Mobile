# Eight iOS tests on iPad 27.0 (#367)

Candidate `8000d09a`, app `9125ce98…` (same product as `main`, with empty `git diff 8000d09a main -- composeApp core iosApp shared companion`). Xcode 27.2, iOS 27.0 (24A434), simulators. The fixes affect only the harness. Acceptance #243 remains closed.

The review checked each failure against the native tree, capture, and source. It confirmed that all failures came from the harness and corrected incomplete or inaccurate causes in the original report.

## Cause by test

| Test | Failing step | Cause | Type | Fix |
| --- | --- | --- | --- | --- |
| `testProjectMouseMappings` | `menu-title-simulate` | The tap to close the menu, at x≈430 on the status line, hit the last Project menu item because it extended to that line. This opened the `Project toolbar` sheet, **observed** in the video and native tree. The document without `<toolbar>` would then have lacked Wire (#344). [Capture](project-menu-covers-status-line.png) | Harness | Tap beyond the menu. Add `Wiring Tool` to the fixture. |
| `testNarrowInspectorMovesInlineOnWideTablet` | `menu-title-simulate` | Same tap. At 834 pt, the inspector already docks in portrait (`editorLayoutIsWide`, 760 pt), so there is no sheet or Properties button. **Observed.** | Harness | At ≥ 760 pt, verify the docked inspector. Test the sheet-to-docked transition on iPad mini. |
| `testInvalidWidth…` | `editor-inspector-sheet`, line 240 | The capture shows the docked inspector open with the right selection. The test only accepted a sheet. **Observed.** | Harness | Accept sheet or docked inspector. |
| `testIoDisplay…`, `Dimension`, `Dip`, `Keyboard`, `Momentary` | `No reachable control for Components` | The first query ran while the sheet was still opening (inferred from two matches, neither accepted, as observed in `7aaf53c6`). Scrolling then lost the button. The final scroll to the top had no follow-up query (**observed** in code and log). | Harness | Wait for the frame to settle and query after the final scroll. |

These fixes exposed three additional harness defects in the same five I/O tests.

- The functions handled only sheets. The docked inspector is 300 pt wide, with a fixed panel above the list, and requires a short scroll in the lower strip. **Observed.**
- `ASCII form feed` sits outside the panel in the ASCII key row and requires horizontal scrolling. **Observed.**
- For `IO_DIP.positions`, the harness compared text (`2` and `0x2`). The product writes `0x2` like Evolution (`Attributes.forHexInteger`, `DipSwitch.java:164`). The harness now compares values, as `scripts/io_showcase_contract.py` already does on Android. **Observed.**

## Result

The last three runs used identical app and harness sources. Command `scripts/run-ios-e2e.py --simulator <udid> --app <app 8000d09a> --baseline-sha 8000d09a --only-testing <teste> --test-timeout 900`. [Results](results.json).

| 27.0 simulator | Portrait width | Run | Result |
| --- | --- | --- | --- |
| Logisim iPad 27.0 `E47EAD1D` | 834 pt, docked | `ipad-2`, all eight | 7 `Passed`, `testInvalidWidth…` failed |
| iPad mini (A17 Pro) `18F1B676` | 744 pt, sheet | `mini-1` | `testNarrow…` and `testInvalidWidth…` `NATIVE_PASS` |
| iPad (A16) `1159F2FC` | 820 pt, docked | `a16-1` | `testInvalidWidth…` `NATIVE_PASS` |
| Logisim iPad 27.0 `E47EAD1D` | 834 pt, docked | `ipad-3`, after the Close fix on phone | `testIoDisplay…` `NATIVE_PASS` |
| Logisim iPhone 27.0 `3707429D` | Sheet | `iphone-1`, `iphone-2`, `iphone-3` | 6 tests `Passed` across the runs |

`ipad-1` records the first round, before the second set of fixes.

After `ipad-2`, `mini-1`, `a16-1`, and `iphone-1`, the only change was to `closeExplorerSheet` in `IoEndToEndTests.swift`, used in Activate and visual capture. On phone it taps Hide keyboard before Close because the header swaps the button when the keyboard is open (`App.kt:1415`). `ipad-3` and the last two iPhone runs used the final file.

On iPhone, `iphone-1` passed Invalid, Dip, and Momentary, and failed at Close in the other three. After the fix, `iphone-2` passed Display and Dimension and reached the host timeout of 1800 s during Keyboard (rc 142, host result). `iphone-3` passed Keyboard alone. `testProjectMouseMappings` runs only on iPad.

## Limits

- **`testInvalidWidth…` failed on `E47EAD1D` because a simulator prerequisite was missing.** The policy requires tapping Delete on the software keyboard. On this simulator, the `Keyboard` node has height 0 and `inputView` is offscreen at y=1210. The same behavior occurred in the receiver app. The keyboard appeared on the other two iPads without opening the Simulator UI. The reviewer left the Simulator.app keyboard setting alone to avoid affecting the running iPhone 17.
- The ≥ 760 pt branch of `testNarrow…` on `E47EAD1D` covers the docked inspector in both orientations. The sheet-to-docked transition passed on iPad mini.
- The remaining iPad and iPhone observations indicate harness or simulator limitations. `testNarrow…` was omitted on iPhone because the transition applies to tablets.
