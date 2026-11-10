# Typed Inspector acceptance review

Subsequent result: the [final6cef addendum](inspector-final-acceptance-6cef0542.md) records completed Android Beta and iPhone cases and the final acceptance. This report retains its original cutoff and failed attempt.

Reviewed source: `ab1b04cf4c396c0391b2aa9c4ad8f486f45d5d0c`. This review read archived source, test XML, native receipts and images. It ran no builds, tests, ADB commands, Simulator actions or application UI. Issue #21 remained open when fetched; its dependency #4 was `closed/completed`.

**Keep #21 open at this cutoff.** Typed editing and its domain behavior are verified. The dedicated scenario passed on an Android phone emulator and an iPad Simulator, but iPhone attempt001 failed during Project XML fixture setup before reaching numeric Inspector edits. The existing iPhone17e Simulator is sufficient to exercise the phone requirement; physical hardware is not required to complete this issue.

## Criteria

| Criterion | Evidence | Disposition |
| --- | --- | --- |
| Generate typed controls, validate values and show specific messages | `PropertyEditorModel` derives shared fields from registered schemas and validates each selected component. The inspector presents boolean, direction, integer, decimal and text inputs. `PropertyEditorModelTest`9 and `PropertyLocalizationTest`3 passed on the reviewed pin. Both completed native cases observed the width0-specific message and disabled Apply action. | Implemented; model and mounted native validation passed within the recorded artifacts. |
| Update drawing, terminals and simulation as one undoable action without silent disconnection | `EditorState.updateSelectedAttributes` constructs replacements before publication; `CircuitEditor.updateComponents` edits a candidate. Connected terminal removal is explicitly refused; named terminals are rerouted with connection-preservation checks. `change` records one snapshot and requests fresh simulation. `TypedAttributeEditingTest`3 and `EditorRegistryIntegrationTest`1 passed. The completed native cases exported width4 as the sole change and restored the complete baseline with one Undo. | Implemented; behavioral and native Undo evidence passed. |
| Make the inspector available on phones; test focus, invalid values and supported multiselection | Android3d's phone emulator case passed focus/IME and numeric validation. iPad A4ab passed the same case with visible software keyboard and fields above the keyboard/assistant. Multiselection is exercised through the actual `EditorState` and typed model. iPhone001 failed before reaching these assertions. | Phone-sized iOS execution remains pending. No native multiselection gesture is claimed. |

## Current behavioral evidence

The [retained result index](inspector-behavior-evidence-ab1b04cf.json) contains ten raw JUnit suites copied from the integrator's immutable ab1 archive. Each copied file was checked against that archive's byte count and SHA-256 before retention.

| Target | Suites | Executed tests | Result |
| --- | --- | ---: | --- |
| Android editor host | PropertyEditorModel9, PropertyLocalization3, TypedAttributeEditing3, EditorRegistryIntegration1 | 16 | Zero failures, errors or skips; UTC03:55:27.140–03:55:41.173 on2026-11-05. |
| Core JVM | ComponentRegistry9, CircuitEditing11, CircuitEditor6, DefaultCatalogIntegration4, KeypadResizeEditor6, CircuitDiagnostics13 | 49 | Zero failures, errors or skips. |

The complete ab1 gate recorded981 core JVM tests,478 editor host tests and8 snapshot JUnit tests, all green, plus debug/e2e/test/snapshot APK builds. That gate did not execute the iOS application or iOS Native tests. The65 selected cases above are part of those totals, not additional runs.

Multiselection assertions use two selected AND gates and the real editor state. A shared width change updates both and one Undo restores the original document; an invalid value preserves the entire project without partial publication. A mixed-schema selection rejects width8 when one component permits only1–4, and accepts4. Mixed values and unknown read-only data remain explicit. Label edits also update both selected components and Undo restores them.

Connection tests exercise atomic batch rejection, named-terminal rotation, routing rejection when another net would be joined, keypad resizing with preserved net partitions and behavior after XML reopen. The source guard refusing removal of a connected terminal was inspected; this review did not identify a dedicated executed test for that specific branch.

## Native observations

| Artifact and receipt | Actual result | Checked observations |
| --- | --- | --- |
| Android producer `3d231c53`, [receipt](android-inspector-3d231c53.json),181.48s | PASS on API35 phone emulator,1080×2400,density420,en-US | App/test/snapshot APK hashes match the retained manifest and local APKs: `6c094eb6…`/`31d6e6ca…`/`139f59d5…`. Before/after installed-hash records agree. The frozen Inspector module matches original `4a016d37`. Width0 is focused, its software numeric keyboard is visible, and the dialog Apply ancestor is disabled. The valid snapshot shows focused4 and enabled Apply. Cancel and oneUndo exports are byte-identical to baseline; the only valid XML delta is width1→4. |
| iOS producer `4ab8aa93`, [iPad receipt](ios-a-inspector-001.json),121.227s | PASS,1/1,zero failures/skips | Bundle-tree SHA`d39e8d7b8f02dc114a0f83c3c491d9d7aaa114dc43e38d15efcb373d81129cb4` independently recomputed with the canonical per-file hash algorithm. All retained evidence and frozen harness hashes match. Native tree records `Keyboard Focused`, width0-specific error and disabled Apply. Width0/4 fields end at613/627pt, above the input surfaces beginning at870pt. Cancel and Undo exports are byte-identical; valid XML changes only width. |
| Same iOS A4ab, iPhone17e attempt001 | FAIL,1/1 | [Retained summary](artifacts/inspector-acceptance-ab1b04cf/iphone001-xcresult-summary.json) reports that the native editing menu exposed neither Select All nor an enabled Cut/Copy pair. Failure occurred in `loadInput`/Project XML before the numeric Inspector. No width0/4 or Undo pass is inferred. |

The Android `TextView` child displaying Apply has `enabled=true`; its clickable parent has `enabled=false`. The parent's state is the action's state. The [additional focus evidence](inspector-focus-evidence-ab1b04cf.json) retains the original keyboard screenshot, focused native tree and width4 tree, supplementing the owner's post-keyboard rejection image. Twenty-six raw snapshot records had distinct nonces and unchanged app PID3425; these are observations from that run, not a new device check.

## Source and dependency drift

The [source comparison](inspector-source-comparison-ab1b04cf.json) records SHA-256 values from immutable Git objects.

- `PropertyEditorModel`, `TypedPropertyInspector`, `EditorState`, `CircuitEditor`, `ComponentRegistry`, dialog presentation/bounds and inspector handle are byte-identical in reviewed ab1, iOS A4ab and Android3d.
- `App.kt`, the iOS host and Compose dependencies in ab1 are identical to iOS A4ab. Relative to A4ab, ab1 adds only the canvas/window-focus cancellation changes among the common/platform sources examined. That canvas implementation is identical to the one executed in Android3d; this is not a new iOS canvas or accessibility pass.
- Android3d used Compose1.12.2. Reviewed ab1 uses1.13.0-beta02 and Android compileSdk37.1. The generic tool-sheet header/IME path also changed to the A implementation. The property inspector code itself is unchanged. The Android3d runtime result therefore remains evidence for its actual artifact; it does not certify the later beta Android APK by compilation alone.

No full unfiltered accessibility audit, physical device, enlarged-font matrix, split-window matrix or native multiselection gesture is certified here. Those claims belong to their owning validation work. This report establishes the completed Inspector observations and preserves the failed iPhone attempt without converting it into a pass.

## Remaining verifiable unit

The existing iOS owner is replacing the failing fixture-setup path with an actual Files fixture and a narrowly scoped numeric replacement operation. Require a terminal phone result, exact installed artifact identity, visible software keyboard, width0's message/disabledApply, unchanged XML after Cancel, width4-only delta and complete oneUndo restoration. Review any later application source changes separately before associating this ab1 evidence with a newer pin.
