# Native pointer cancellation

Android R5 reproduced an edit after a cancelled drag. The fix recognizes the consumed terminal release delivered by Compose before the Canvas can turn it into a completed drag or tap.

## Native baseline

The preserved run is `android-e2e-df276e/outputs/android-e2e/df276e-mixed-cancel-r5-single-snapshot/native`. Its app SHA256 is `600f656ac6cbb4661bbff880995b77a1fc240bb0607671bb96d0a010b234a834`, produced from the `df276e` product pin through build head `40dd9172`. Test producer head is `05679f92`.

| Observation | Recorded value |
| --- | --- |
| Interaction mode | Select, explicit ACTIVE with checked/checkable true |
| Native sequence | DOWN, ten MOVE events, CANCEL; all accepted, no UP |
| Pointer | ID 0, source 4098, tool type FINGER, constant downTime 30209577 |
| Timing | Strictly increasing event times; actual span 1,019 ms |
| Cancellation | Pressure zero, same final coordinates, cleanup false |
| Exported NOT position | `(200,100)` before, `(250,120)` after |
| Exported wires | Two before, four rerouted segments after |

Only the NOT location and wires changed. The native receipt SHA256 is `4b7f6c3fe7e2f636f185103d10c3a329832463b77355413ae20403091cc12ae7`. The before/after XML hashes are `9b0e31116c2884277bb46edbffefe4c520ed1a5e0efd38d6e05070eb7d4ae9e4` and `642fc7e2a824b8364f61b42eb05ea189b3dc84c44e499a7add89243993643894`.

The injector receipt establishes emitted events and Android acceptance. It does not itself trace Compose delivery or qualify physical touch hardware. Concurrent work on another emulator excludes performance conclusions from the gesture duration.

## Boundary and change

The resolved Android Compose UI 1.12.1 sources and bytecode show the cancellation path through MotionEventAdapter, AndroidComposeView, PointerInputEventProcessor and HitPathTracker to `onCancelPointerInput`. The suspending pointer node keeps the coroutine and dispatches synthetic release changes with pressed false, previousPressed true and consumption true. Without a native MotionEvent, that PointerEvent is classified as Release.

The Canvas previously stopped when no pointers remained, called `finish()`, and committed its pending move. Its `finally` cleared the preview only after that commit. The machine's explicit `cancel()` already prevented completion, but the native cancellation boundary did not call it.

The Canvas now passes the incoming PointerEvent through the machine's adapter before consuming changes. A terminal event containing a consumed release cancels the machine before `finish()`. The completion is null, so the existing cleanup discards previews and no document move or tap is committed. An unconsumed release still commits, and a consumed MOVE while pressed does not cancel the gesture. No dependency, XML oracle or Android input sequence changed.

This signal also covers a terminal release claimed by another detector. It does not identify ACTION_CANCEL exclusively. The change uses public common PointerEvent APIs and does not depend on an Android test hook or a native MotionEvent observer.

## Focused verification

The actual adapter and machine compiled with Kotlin 2.4.21 and the resolved Android 1.12.1 classes. Four new tests construct public PointerInputChange/PointerEvent objects and call the same adapter used by the Canvas. No test calls `cancel()` to establish the expected outcome.

Before the guard, ten tests ran with two failures: a consumed terminal release completed a Drag and a Tap. After the guard, the identical test source and 25 immutable dependency inputs produced `OK (10 tests)`. Normal release and consumed pressed-MOVE controls passed in both runs. Two independent source reviews found no blocking issue.

Host receipts and the exact red/green source diff are preserved under `/tmp/native-pointer-cancel-host`. The test-source SHA256 is `82135af08c81c02ae30eeaa8a0dd6333303be6615307facf12bb0058e17cbb11`; the guarded machine source SHA256 is `38615129c48c956f8f25f1b80a174583994efa8f2f83c62694a58a630e0ccf05`.

The project test entry point is:

```sh
./gradlew :composeApp:testAndroidHostTest --tests '*CanvasInteractionTest'
```

That Gradle command was not run in the original fix worktree. The Android owner subsequently built the fixed APK and passed all ten tests through Gradle. The focused host proof does not execute Android dispatch or the full Canvas UI.

## Fixed-APK R6 result

Candidate `76ea1ec1` used app SHA256 `76b64e7bb65a6eea9b4fff6f9c084778f46906014ccf7c7fe2dbbb91fee35000` and test SHA256 `2706fd5ec065a9e8bfb63b4f4597d972edc91006ef147dd2d02f20840f4350e2`. Production changes were the two Canvas files above. The declared test-only fixture broker change affected setup; the injector and XML oracles remained intact.

In `df276e-native-cancel-fixed-r6/native-run2`, the complete before/after CANCEL exports were byte-identical: 1,191 bytes, SHA256 `612038e988fdf01ffb1c78506fabfb23f89f944b8e98b4f846f7ff4e72bf6e6f`, three components and two wires. Installed APK hashes stayed stable and last ANR was unchanged.

At that point the full case still failed before the subsequent normal-UP/Undo controls: it could not locate the selected NOT body node, while its port nodes were present. That observation failure does not reverse the passed cancellation invariant.

## Complete R7 control

With the same fixed app, R7 used test SHA256 `31d6e6cad94ac10f6a0bf21b5bd9c675b2ea80cbbfb71f7917f554f394b97f70`. The harness now retained the actual pre-CANCEL body/frame and required identical viewport anchors before reusing that point. It completed CANCEL, the subsequent normal-UP move to `(250,120)`, connectivity checks and one Undo restoring the original XML. Before, after CANCEL and after Undo were byte-identical with SHA256 `651908b34000e73611b0c9627a075c6af93cdca1fe0f4dc136f8de7f8385e545`.

The native case is PASS in `df276e-native-cancel-fixed-r7/native-run`; runner elapsed time is 17.809 seconds. Installed app/test hashes stayed stable and last ANR was unchanged. The independent context case also passed with the same artifacts in `df276e-native-context-r2`, including Duplicate and one Undo, in 13.922 seconds.

Both receipts report PARTIAL qualification because each runs one selected case. They do not establish an eight-case suite pass or qualify physical hardware. Issue #27 remains open for the remaining required behavior.
