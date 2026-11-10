# Typed Inspector native cases

These cases complete the invalid-value and keyboard-focus path for issue #21. They use production controls and inspect the XML exported by the app.

Both cases create a fresh project with one labeled input pin at `(100,100)`. They open its width property, enter `0`, observe the specific validation message and disabled Apply action, and cancel. The exported project must remain unchanged. They then enter `4`, apply it, verify that only width changed, and use one Undo to restore the original export with width `1`.

The Android case also requires the numeric field's native `focused=true` state and an observed software keyboard. The iOS case requires a visible keyboard and checks that the focused field and valid Apply action remain outside its frame. Screenshots, native trees and exported XML accompany runtime observations.

## Integration

Android's E2E owner can import `run_inspector` from `scripts/android_inspector_scenarios.py` and call it with the existing scenarios object. It reuses the current `_Editing` adapter and accepts only the isolated `.e2e` application. No runner or shared selector implementation is changed by these files.

For iOS, add `InspectorEndToEndTests.swift` to the `EditorEndToEndTests` target's explicit source list in `scripts/ios_e2e_ui/project.yml` and the copy list in `prepare` in `scripts/run-ios-e2e.py`. The existing `NativeUIHarness.swift`, `ObservedProjectXML.swift` and canonical catalog supply its dependencies. Select:

```text
EditorEndToEndTests/InspectorEndToEndTests/testInvalidWidthPreservesProjectAndValidWidthIsOneUndoAction
```

Enable the Simulator software keyboard for this case. Use the existing runner's explicit app artifact, source pin, Simulator UUID and output directory. The case records the observed window frame; phone coverage requires an actual phone-sized run.

## Offline verification

Prepared against source `9455c044b899e3eed2f4ced7f94b57d43fd2f881`:

- Seven Python checks passed. Literal XML verifies width, metadata preservation and Undo expectations; modal trees distinguish the dialog's Apply action from the label Apply action underneath it. Missing, duplicate and clipped controls are rejected.
- Swift parsing and type checking passed for the arm64 iOS Simulator target with the shared native harness and XML reader.
- All 16 literal iOS selector keys exist as English and Portuguese strings in the canonical catalog.

At preparation time, native execution was **NOT_RUN**. The later observations below retain their own artifact identities and outcomes.

## Later native attempt

The prepared case was selected on iPad artifacta046 and failed during Project XML fixture replacement, before opening the numeric inspector. Width0/error/disabledApply, cancel preservation, width4 and oneUndo remained **NOT_RUN** in that attempt. See the [historical c8 acceptance ledger](layout-inspector-acceptance-c8bef175.md) for that cutoff.

Subsequent dedicated cases passed on the Android3d phone emulator and iOS A4ab iPad. Both observed invalid0, disabled Apply, unchanged export after Cancel, valid4 and exact oneUndo restoration. The iPhone17e A4ab attempt001 failed during fixture setup before those numeric assertions. The [ab1 acceptance review](inspector-acceptance-ab1b04cf.md) maps current behavioral tests, source drift, retained focus images and the remaining phone execution. These results do not qualify a physical device or later artifact automatically.

The final Android Beta and iPhone Phone005 cases then passed on production6cef. The [final acceptance addendum](inspector-final-acceptance-6cef0542.md) maps all three #21 criteria to those exact receipts and the retained behavioral tests. Earlier setup failures remain failed evidence; whole-suite native qualification remains partial.
