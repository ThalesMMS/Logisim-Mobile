# Explorer terminal and wire buttons expose one label each (#328)

Mobile `f44bb2f9`. New evidence from the run on 2026-11-07, separate from `1295aead` (#241).

## Fix

In `AccessibleCircuitExplorer.kt`, terminal (`port:*`) and wire (`a11y-wire-*`) buttons store their spoken label in `contentDescription`. The visible text now uses `clearAndSetSemantics {}` (`ExplorerButtonCaption`). Compose on iOS previously combined the description with the child’s text, duplicating the label. Android still reads the same `contentDescription`.

## Native iOS accessibility tree (XCUITest)

- iPhone 17 Simulator, iOS 27.2 (UDID `8BCFCCB9…`, created for this run). Debug app with executable SHA-256 `300cf7cd…184a`.
- `python3 scripts/run-ios-e2e.py --simulator <udid> --baseline-sha f44bb2f9 --app <LogisimMobile.app> --output <dir> --only-testing EditorEndToEndTests/EditorEndToEndTests/testExplorerTerminalAndWireButtonsExposeTheirLabelOnce`. Result `PASS` (`xcui-results.json`, `xcui-test-log.txt`).
- Terminal: `identifier: 'port:2:c2:in', label: 'Start a connection at Y, in', value: Input in, 1 bit. Low, 0.` The label appears once, and `value` retains the direction, width, and value (`terminal-button.txt`, `native-tree-terminal.txt`). Before the fix (#328, `1295aead`), the label was `'Start a connection at Y, in, Start a connection at Y, in'`.
- Wire `identifier: 'a11y-wire-0', label: 'Wire 1, from 100, 100 to 300, 100. Connects A, out; Y, in.'`. The name appears once, without the suffix `, Wire 1…` (`wire-button.txt`, `native-tree-wire.txt`).

## Focused semantics test (Compose, iOS)

`composeApp/src/iosTest/.../AccessibleExplorerSemanticsTest.kt`, run with `./gradlew :composeApp:iosSimulatorArm64Test -PiosTestDevice=<udid> --tests dev.logisim.mobile.AccessibleExplorerSemanticsTest`.

- With the fix, 1 test passed with 0 failures. The merged `port:*` and `a11y-wire-0` nodes have the label as their `ContentDescription` and no `Text`.
- For the negative control, reverting the caption to `Text(text)` makes the test fail with `The visible caption must not repeat the label. Expected value to be null, but was: <[Start a connection at Y, in]>` (`ios-semantics-test-negative-control.xml`).

## Android (APK E2E `48d5b855…`, `f44bb2f9`, Android 15 AVD, API 35)

`android-explorer-port-f44bb2f9.xml` is a UIAutomator dump of the Explorer open at **Terminais de E2E_IN**. The clickable `port:2:c1:out` node retains the child with `content-desc="Start a connection at E2E_IN, out"`. At the same position, the `3a0a01f1` dump (`docs/validation/android-editing-3a0a01f1-1bb15545-r2-001/raw/focus-snapshot-…-47.xml`) also had a `TextView` that duplicated the text. The fix removed that child and preserved `content-desc` and `state-desc`.

TalkBack speech capture was marked NOT_RUN. TalkBack 15.0 on this AVD omits speech from logcat, and the Android Accessibility Suite notification permission dialog reappears on each activation. The Android criterion relies on the semantics test (which confirms the preserved description) and the dump above.

The Android `editing` scenario stopped before wiring at the `components` checkpoint. The contract expects `appearance="classic"` and the app has written `NewPins` since `99a90313`. Issue #329 tracks the gap.
