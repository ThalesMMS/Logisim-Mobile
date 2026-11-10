# Candidate fix for the asynchronous Keyboard field

The isolated source fix builds on lifecycle initialization fix `1532eff9`. The original callback reproduction remains in the parent directory and its historical source commit. The injector, literal AB expectation and native deadlines are unchanged.

The field now echoes an edit synchronously using a local TextFieldValue draft. That draft is displayed only while the exact ReplaceKeyboardText object returned by its callback remains in the controller's accepted queue, including in-flight computation. Event identity is referential: an equal payload from a different request does not retain an older draft. The kernel's existing normalization supplies the pending text, selection and composition bounds for capacity and filtered characters.

Once the corresponding request publishes, is cancelled or is rejected, the field renders the kernel again. An older A publication cannot replace a newer pending AB draft. Clock consumption, Home/End/Delete, ASCII control insertion, unrelated work and a document replacement therefore use their current kernel state. The panel is keyed by document identity, circuit, instance path and component identity.

The controller exposes its already-existing manualTailId as latestManualRequest in status. The complete observable status travels through both Inspector layouts to the panel. This makes cancellation followed immediately by a different request observable even when the final pending count is still 1 and the kernel published nothing. The metadata retains event references through the existing release replay; no simulator/Core behavior or persistence format changes.

## Verification

- The original product callback reproduction changed from one failure and two passing controls to three passing controls. With the worker blocked across a frame, the actual callback texts are now A then AB and the final kernel buffer is AB.
- The consolidated host run passed 55 tests. It includes the new typing/draft controls and existing lifecycle initialization, worker, FIFO and release tests. New controls cover cancellation without publication, equal payloads with different identities, an identical edit retried after cancellation, capacity, filtered text, reversed selection/composition bounds, clock consumption, Home/End/Delete, ASCII controls, inactive rejection and document ownership.
- A separate headless Compose Runtime test passed with the actual compiler's strong skipping enabled. It rendered AB, cancelled that request and queued a different input without yielding. The pending count stayed 1, no kernel publication occurred, and the field became empty. The negative mutation that forces the stamp to 0 failed to observe that update. Compiler reports mark the probe and production panel as restartable/skippable and SimulationRunStatus as stable.
- App.kt passed a separate Kotlin/Compose source compilation, including both Inspector calls. The dependency order keeps the APK-matched Beta UI classes before supplemental cached AARs; no experimental opt-in was added to product source.
- Independent source review approved the event ownership, observable stamp, lifecycle replay and normalization. The reviewer did not claim a separate execution.

The normal host tests contain no UI window. The opt-in strong-skipping diagnostic requires the explicitly scoped JVM-only android.os.Trace adapter under scripts/diagnostics; tracing is a no-op there, while Compose Runtime, StateFlow, the controller, event queue and field model are real. That adapter is not part of the App or normal host tests. The receipts record the compiler commands, source hashes and local classpaths. The unchanged frozen app dependency classes have unknown source provenance; affected sources are rebuilt, and Core is the verified fresh `6cef` artifact with no source difference through the lifecycle base. This is compilation and Host evidence only: no Gradle, APK/framework build, simulator, ADB, native UI rerun or platform qualification was performed.

## Remaining limits

The retained Android run `85649` native case remains failed until a new approved app is tested. After acknowledgement the panel still reconstructs a collapsed selection from the kernel cursor and drops composition, as the original implementation did. This fix qualifies the pending draft and AB race; it does not establish native selection/IME parity, pointer holds, TTY sequences, iOS or all of issue #44.
