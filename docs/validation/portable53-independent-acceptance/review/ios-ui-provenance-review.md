# Issue #53 iOS UI provenance review

Result: no concrete dependency on a candidate-exclusive control, focus action, or text-edit behavior was found in the frozen #53 native flow. The actual iOS native PASS remains evidence for `b9bd71ae49e562661ee114b248e342a02ed29508`, not a recorded execution of main. Source comparison alone does not supply the missing main execution.

This read-only review compares the exact passed app source with main pinned to `e54aa75f85c8665c1e313dce370b3163182ded64`. It reads the frozen harness and captured accessibility trees from evidence commit `b811fd26e4fdb98cc0a9f9d9af6c29117836605e`. No UI, device, build, Gradle, or repository mutation was used.

## Versions and candidate-only product changes

`52c7ce8f60e2e3d14a866123cb29afc02d3d54b0` changes five version pins on the `a0462cc8` product baseline: Compose Gradle plugin plus runtime, foundation, UI, and resources, from `1.12.2` to `1.13.0-beta02`. Kotlin and the Compose compiler remain `2.4.21`, Material3 remains `1.9.0`, and lifecycle remains `2.11.0`. `b9bd71ae` itself adds two preparation documents only. Sources for the five pins are `build.gradle.kts:4` and `composeApp/build.gradle.kts:31` through `:35` at each commit. AndroidSnapshot added to main settings is unrelated to the iOS flow.

The app-level differences relevant to menus and focus are:

| Difference | Exact source | Dependency in #53 flow |
| --- | --- | --- |
| Beta sets `onFocusBehavior = OnFocusBehavior.DoNothing`; main uses the default ComposeUIViewController configuration | Beta `composeApp/src/iosMain/kotlin/dev/logisim/mobile/MainViewController.kt:63`; main same file `:62` | No Compose focus or text-entry action occurs in this test. This difference is real but no dependence is demonstrated. |
| Beta constrains the tool-sheet modifier with `editorDialogBounds()` only while the software keyboard is visible | Beta `composeApp/src/commonMain/kotlin/dev/logisim/mobile/App.kt:617`; main same file `:615` | No captured native Keyboard node occurs in any of the 71 passed-flow trees. The Beta branch reduces to the same unbounded modifier in that condition. |
| Beta tool header uses a one-line ellipsized title and a minimum 48dp button tagged `editor-tool-close` or `editor-tool-hide-keyboard` | Beta `App.kt:625` through `:633`; main `App.kt:620` through `:621` | Frozen #53 never queries either header tag and never invokes Hide keyboard. It closes using the canonical `action_close` label. Main provides the same Close label and dismissal callback. Geometry can still differ at runtime, but no candidate-exclusive action is required. |
| Beta version addresses the separate full-history Find/Command-A focus investigation | Beta `docs/validation/ios-focus-dependency-experiment.md:7` through `:23` | #53 does not execute Find, Select All, Command-A, Delete, text replacement, Inspector numeric entry, or clipboard operations. The proposed text-action mechanism is not exercised by this flow. |

## Production controls used by the frozen test

The following files are exact Git-blob matches between Beta and pinned main:

- `composeApp/src/commonMain/kotlin/dev/logisim/mobile/PortableProjectTools.kt`, blob `9d0da8687de18d11ee7708973087f51b6c5fd001`.
- `BuiltInEditorTools.kt`, blob `ceaf67200def9a6b1233a87c2e08c0fd38df66cd`.
- `EditorDialogBounds.kt`, blob `9566487c8318d709565fb6c9ad25185a0c3f59fc`.
- `EditorToolbar.kt`, blob `df9305f039782ee4d25299dd0397d3b8401ff688`.
- iOS `EditorWindowSemantics.ios.kt`, blob `419cc1c21329a1f035ebc1f98efcca3cfb295411`.
- iOS `AppDialogPresentation.ios.kt`, blob `74731988266b65c0945de3571c57d16ab93cee0d`.

The tool-menu code is also unchanged apart from line offsets: Beta `App.kt:606` through `:613`, main `App.kt:604` through `:611`. Both mount the bounded `AppAlertDialog`, expose `editor-tool-menu`, and activate the selected EditorTool.

The shared panel buttons are `PortableProjectTools.kt:256` through `:270`: Open bundle, Open folder, each granted relative circuit path, and Export bundle. Each exists with the same enabled conditions and native asset callbacks in both sources. The root source selection uses the actual `main.circ` label at `:262` through `:263`. The 10 localization keys used for menus, portable actions, file-save state, and canvas output are identical in both English and Portuguese resources.

The frozen test's actual action path is:

1. `bundle/frozen-harness/PortableProjectEndToEndTests.swift:183` through `:189` closes an existing tool by `action_close`, opens project tools via the toolbar, and reveals the canonical `portable_title` in `editor-tool-menu`.
2. Lines `:192` through `:227` find a unique canonical native Button, require that its entire positive-size frame is in the app and that it is hittable/enabled, and tap it. Only when no such button is visible does it require a matching identified scroll region and use the shared reveal helper.
3. Lines `:77` through `:86`, `:136` through `:138`, and `:260` through `:265` use the bundle/folder/export buttons and UIKit Files picker. `NativeFilesDestinationNavigator.swift:161` through `:166` reads any filename fields into diagnostics and taps native commit; it does not edit a filename or focus a Compose input.
4. Lines `:230` through `:250` close by canonical label and read the actual canvas outputs and instance tags. Lines `:253` through `:257` open Files, inspect disabled Save, and close by canonical label. None uses the Beta-specific header identifiers.

The frozen helper comment at `PortableProjectEndToEndTests.swift:214` through `:215` correctly records a Beta sheet-frame defect. For example the passed bundle tree `bundle/native-evidence/evidence/021-portable-panel-opened.txt:24` gives `editor-tool-sheet` frame y=0...460, while its Close button is at y=810 at `:30`. The visible-button-first strategy avoids scrolling through that displaced region. It does not depend on the defective frame or on a candidate-exclusive button. Correcting that frame in another build would not invalidate the test's first path. General offscreen action scrolling is not qualified by this workaround.

## Runtime evidence and remaining limits

All 35 bundle and 36 folder captured accessibility trees contain zero native `Keyboard`, `TextField`, `TextView`, or `SecureTextField` nodes. Passed panel trees expose `editor-tool-close` with label Close and never `editor-tool-hide-keyboard`. The frozen Portable test, shared NativeUIHarness, and destination helper contain no text-entry/key-command calls in this flow. This supports the narrower finding that the Beta text-action and keyboard-header paths were not used.

No candidate-exclusive UI gate was established. A different Compose version may still change native accessibility geometry, modal presentation, or lifetime at runtime. Reading identical app source cannot prove those library-level outcomes on main. Thus:

| Criterion | Result |
| --- | --- |
| Existing #53 evidence has correct Beta source attribution | PASS |
| #53 requires a control or action absent from main source | Not established; no such gate found |
| #53 requires the Find/Select All/focus behavior motivating the Beta experiment | No such dependency found |
| Native #53 bundle/folder runtime on main e54aa75f | NOT_RUN by this review; cannot be relabeled from the Beta receipt |
| Need to merge Beta solely because #53 used it | Not supported by this analysis |

The acceptance reviewer should combine this UI provenance analysis with the core/workspace/adapter comparison and the project's chosen evidence policy. Preserve the Beta source pin in any final issue record.
