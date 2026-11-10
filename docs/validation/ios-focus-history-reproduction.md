# iOS focus history reproduction on a046

The full editor history reproduces Find losing keyboard focus to Y during Command-A. Sending the command through `XCUIApplication` also reproduces the failure. The app artifact and source remain a0462cc809f38a22fc1d41a0ab695e369231de8b, with bundle tree SHA256 `cc8943c84f9bf255845acb96e60fca18941ff32eb1fda605d1541cd6d5fec318`.

Issue 27 remains open. No product correction or dependency change was tested in these runs. The observations do not identify the callback responsible for the focus change.

## Native results

| Run | History | Result |
| --- | --- | --- |
| [001](ios-focus-a046-001.json) | First insertion visits X and Y without replacing their values. No XML or Inspector roundtrip. | Four minimal routing probes pass. One-character Q does not qualify complete selection. |
| [002](ios-focus-full-history-a046-002.json) | Original contract steps 1–3, including XML, coordinate replacement, Explorer search, and Inspector Label edit. | Both command targets change focus from Find to Y. The application case fails on Delete. The field case observes the same focus change, then times out during native idle waits and retries. Neither reaches QFOCUS. |
| [003](ios-focus-reduced-y-a046-003.json) | One Insert dialog, original keyboard replacement of Y only. | Preparation fails: Y becomes `10100` instead of `100`. Find is not tested. This is a separate observed selection failure. |
| [004](ios-focus-reduced-insert-a046-004.json) | First insertion with original Find, X, and Y replacements, without XML or Inspector. | Preparation times out during X replacement. The second Insert and its Find probe are not reached. |

The smallest verified reproduction therefore remains the full history in run 002. Runs 003 and 004 do not establish that the omitted steps are necessary. They stop before the comparable Find command.

## Verified sequence

The run reuses the original `EditorEndToEndTests` helpers and the first three steps of `editor-actions-v1.json`:

1. Create an empty project.
2. Open the `.circ` XML dialog, read and verify one empty circuit, and close the dialog.
3. Open Tools, Explorer, and Insert component.
4. Replace Find with `INPUT`, X with `100`, and Y with `100`. Each replacement uses tap, native focus snapshot, field Command-A, snapshot, Delete, type, and an exact value assertion.
5. Hide the keyboard and insert Input.
6. Search the Explorer for `(100, 100)` with the same keyboard replacement helper.
7. Open the inserted component's Inspector and replace Label with `E2E_IN`.
8. Hide the keyboard, Apply, and drag the identified Inspector handle to dismiss it.
9. Open Tools, Explorer, and Insert again. Tap Find and capture its keyboard focus.
10. Send Command-A through either the field or application. Capture focus again, then send Delete without another tap.

The snapshots in run 002 show Find focused at step 9 and Y focused at step 10. The target query still resolves Find at index 0 with unchanged bounds.

The frozen helpers, shared native harness, XML reader, and contract were compared with the failing a046-002 evidence before adding the probes. Their contents were identical. The additions before the comparable command are contract assertions and activity names, with no extra UI action.

## Prepared direct QFOCUS variants

Commit `d16150ba` adds two reviewed and typechecked variants that remain **NATIVE_NOT_RUN**:

- `EditorEndToEndTests/EditorEndToEndTests/testFieldCommandAWithQFOCUSAfterFullInspectorRoundtrip`
- `EditorEndToEndTests/EditorEndToEndTests/testApplicationCommandAWithQFOCUSAfterFullInspectorRoundtrip`

These variants preserve the full prelude but type `QFOCUS` immediately after tapping Find in the second Insert. They omit the initial empty-field command. They assert the six-character value, send Command-A without another tap, require one Delete to clear the entire value, type `R`, and verify X and Y remain `100`.

The verified empty-field variants remain available with their original actions:

- `EditorEndToEndTests/EditorEndToEndTests/testFieldCommandAAfterFullInspectorRoundtrip`
- `EditorEndToEndTests/EditorEndToEndTests/testApplicationCommandAAfterFullInspectorRoundtrip`

Run the selected methods through `scripts/run-ios-e2e.py` only after reserving an explicit simulator lease. Use the artifact and catalog inputs recorded in the receipts and a new output directory. The native runs above used EFC1402B-5511-40D9-8A21-359172F886AB, the iPad Pro 11-inch M5 simulator on iOS 27. No run used 17e or physical hardware.

EFC was returned after run 004 with the app still on the first Insert dialog in portrait: Find `INPUT`, X empty and focused, Y `100`, and the software keyboard visible. The test and wrapper processes had ended. The final screenshot is preserved in the run 004 receipt.
