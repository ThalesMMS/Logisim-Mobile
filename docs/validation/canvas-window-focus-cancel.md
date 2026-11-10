# Canvas cancellation on window focus loss

The canvas now observes `LocalWindowInfo.current.isWindowFocused` as well as its local `FocusState`. Losing window focus cancels the active gesture, releases held IO, and clears drag, selection, and wire previews. Returning focus does not restore the cancelled gesture.

The window focus value is also a `pointerInput` key. A focus change cancels the suspended gesture handler, whose cancellation path marks its machine cancelled before cleanup. A DOWN received while the window is already unfocused cannot start a gesture.

## Before the change

The Android `focusloss` R1 run failed with `The gesture changed the exported document`. The run used product source `76ea1ec1282e2a2106fde6792bc9512975cd7125` and APK SHA-256 `76b64e7bb65a6eea9b4fff6f9c084778f46906014ccf7c7fe2dbbb91fee35000`.

The raw artifacts are under `outputs/android-e2e/df276e-native-focusloss-r1` in the `android-e2e-df276e` worktree. The canvas and gesture-machine sources in that producer match this change's base, `50668f45230ada6f49b10c1e05fe983b8ea183c4`.

The run accepted DOWN, ten MOVEs, and UP. During the held gesture, the editor window `19512` lost focus and the inspector's field received focus in window `19519`. After the inspector closed, window `19512` regained focus. The resulting XML moved the NOT from `(200,100)` to `(250,120)` and rerouted its two wires into four segments.

The original guard watched only `FocusState.hasFocus`. Android Compose updates window focus through `WindowInfo`; losing window focus does not require a change to the canvas's local focus target.

## Local regression checks

`CanvasWindowFocusTest` calls the same `observeCanvasWindowFocus` function used by the canvas's `LaunchedEffect`. Its public `WindowInfo` implementation uses Compose mutable state. The test changes focus to false, waits for the observer, restores true, and delivers a normal, unconsumed UP through the real gesture machine. It verifies no move and unchanged `EditorState` XML. The control with focus always true commits one move to `(250,120)`. Additional cases cover an observer that starts with an unfocused window, with and without an active gesture.

The focused Android host command is:

```sh
ANDROID_HOME=/Users/thales/Library/Android/sdk python3 /tmp/logisim-gradle-serial.py ./gradlew :composeApp:testAndroidHostTest --tests 'dev.logisim.mobile.CanvasWindowFocusTest' --tests 'dev.logisim.mobile.CanvasInteractionTest' --console=plain
```

The first run passed all 13 tests and compiled `AndroidMain`. Review then found an unwanted initial-state effect: observing an idle, initially unfocused window ran the editor's Cancel command. The added regression failed with `expected:<0> but was:<1>`. The observer now emits cleanup for a true-to-false transition or an active gesture, preserving the idle editor's status and pending wire on initial attachment.

Final verification passed 14 tests: four `CanvasWindowFocusTest` cases and ten `CanvasInteractionTest` cases, with zero failures, errors, or skips. `AndroidMain` compiled in the same build. The log is `/tmp/logisim-window-focus-test-complete.log`, SHA-256 `d3cdb554a6b8ffc22a3658d3fb36f329787b5d3a294dd37b423a321e8d8fcbcc`.

## Scope and remaining runtime check

`snapshotFlow` observes state and can coalesce changes. The contract covers an observed false value; it does not promise to capture a false-to-true excursion without an opportunity to observe false. Once observed, cancellation remains in the active machine until the gesture ends.

The local tests exercise the public `WindowInfo` binding, gesture completion, and document result. They do not run the canvas composition, inspector window, or native input delivery. The handler reset, initial DOWN guard, and IO and preview cleanup are source checks. The exact native `focusloss` flow must pass again on an APK containing this change before runtime acceptance. The separate scroll case and the rest of issue #27 remain outside this validation.
