# Keyboard callback race on Android source 85da

The retained native run `85da031e-io-keyboard-a32d3302` passed all nineteen initial outputs, then sent a single `adb input text AB` command. Its focused Keyboard field contained B with count1/16; the independent Label field remained IO_KEYBOARD. It failed before character consumption, TTY and capacity checks. This diagnosis preserves that failure; it does not qualify the native scenario.

A deterministic Host reproduction compiles the original IoInputPanel source and invokes its actual generated onValueChange callback. It uses the exact Compose EditProcessor version identified inside the APK, the real EditorState/SimulationRunController/engine and the I/O fixture. The engine is gated with a deferred signal, without timing sleeps. A frame boundary reapplies the current controlled value to EditProcessor, as the corresponding foundation implementation does during recomposition.

| Worker / frame order | Actual callback text | Final kernel buffer | Literal AB assertion |
| --- | --- | --- | --- |
| Worker blocked, frame between characters | A, B | B | FAIL |
| Worker blocked, no intermediate frame | A, AB | AB | PASS |
| A published before intermediate frame | A, AB | AB | PASS |

The first case reproduces a real product race: the callback queues a simulation event but immediately leaves the controlled TextFieldValue empty; recomposition resets the editing processor before the next character. The two controls distinguish this from a kernel that inherently drops A. The native run did not record callback/frame timing, so the Host does not assert that its exact scheduling was observed on the emulator.

The framework provenance is bound to APK67e81d69, source85da and Compose1.13.0-beta02. The official foundation source shows that the field requests recomposition after its callback and resets its processor from the caller value on recomposition. The hardware-key path applies FinishComposingTextCommand followed by CommitTextCommand for each typed character. AOSP Android15 sendText creates and injects key events in order; one shell command is therefore not an atomic AB editing callback. Sources and hashes are recorded in compose-provenance.json. The Host includes those same finish/commit commands.

Reproduction source: `scripts/diagnostics/KeyboardInputCallbackRaceTest.kt`. The three JUnit cases intentionally have one failure on this source pin. This diagnostic is outside the default test source set. `host-receipt.json` records the manual Kotlin/Compose compiler invocation and JVM classpath; its paths refer to this owner's worktree and extracted local dependencies. It is a local execution receipt rather than a portable dependency installer. Unchanged app dependency classes are a frozen compiled copy with unknown source provenance; all affected callback/editor/controller/driver classes were rebuilt from the source hashes recorded in the receipt, and fresh-Core6cef has no source difference through85da. EditProcessor's actual code-source location is printed in host-red.log.

No product fix, injector change, Native rerun, UI, ADB, Gradle, new input delay or relaxed text oracle was performed for this reproduction.
