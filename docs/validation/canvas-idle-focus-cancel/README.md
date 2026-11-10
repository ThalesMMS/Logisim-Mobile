# Preserve editor status on idle Canvas focus loss

Automatic loss of Canvas or window focus dispatched `CanvasCommand.CANCEL` even when no input operation existed. The generic editor handler then replaced the current status with `Operation cancelled.`. This candidate limits that automatic dispatch to an active gesture, pending wire or held IO pointer. It always cancels the gesture and clears transient input. Explicit ESC still dispatches CANCEL while idle and closes the context menu.

The callback is shared by the existing Canvas `onFocusChanged` and `WindowInfo` watcher. A wire pending between two taps counts as an operation even without a pressed pointer. The window watcher cancels the gesture before invoking this callback, so the guard intentionally does not exclude the CANCELLED phase. The pending-input boolean is evaluated before cleanup releases held IO.

## Reproduction and checks

Before adding the guard, the callback extraction preserved the previous behavior. A focused window with no gesture, wire or held input was changed to unfocused through real Compose `mutableStateOf`, Snapshot notifications and `snapshotFlow`. The shared Canvas callback forwarded to the real EditorState handler. The regression failed with:

```text
expected:<[File fixture.circ open]ed.> but was:<[Operation cancell]ed.>
```

The same regression passed after the guard. All 37 adjacent host tests passed with zero failures, errors or skips: CanvasWindowFocus 9, CanvasInteraction 10, CanvasWireEditing 6, EditorSelection 3, KeyboardShortcutPolicy 6 and EditorIoInteractionGate 3. New cases cover the pending wire between taps, pressed/dragged/transforming gestures, held IO release and explicit ESC while idle. Existing cases retain terminal-release and focus-regain coverage.

Both Gradle runs used the global serial lock. Commands, environment, source hashes, the exact before-fix patch, logs and XML reports are retained. `source-proof.json` records unchanged EditorState, App, CanvasInteraction, IO renderer and the entire key-event block. Independent source review passed.

## Scope

This is a host Compose runtime/state counterexample. It does not execute a full Compose UI tree, native file picker or platform focus delivery. The fixture imports valid circuit XML and reports the same StatusFileOpened value used by EditorDocuments. Historical Native events were not retained, so their route remains unknown. No Native result is reassigned to this candidate; no status predicate or runtime harness is changed.

The UI acceptance check remains pending: retain focus/input events on an authorized immutable producer, open a project, transfer focus while idle, and confirm the import status survives. Separately exercise active drag, pending wire, pinch, held IO and explicit ESC to retain cancellation and graph/XML behavior. No device or app build occurred in this unit.
