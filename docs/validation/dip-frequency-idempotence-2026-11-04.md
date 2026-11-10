# Repeated frequency configuration preserves input

Base: `9455c044b899e3eed2f4ced7f94b57d43fd2f881`.

The DIP `last` persistence fix updates the project object after accepting an input result. The editor observes that object and applies its configured tick frequency. Previously, applying the same frequency paused the controller and cancelled queued input commands. A second accepted DIP interaction could therefore disappear after the first interaction changed the saved project.

`configureFrequency` now returns success immediately when the requested frequency already matches the controller. It still validates the value and rejects configuration after close. A changed frequency continues to replace the pending clock delay.

## Verification

Two regression tests failed against the unchanged controller:

- `EditorDipPersistenceTest.unchangedFrequencyAfterSavingTheFirstDipKeepsTheNextAcceptedInteraction` accepts two real editor DIP events, allows the first to publish, and suspends the second during computation. It then calls the same frequency configuration used by the editor's project effect. The exported positions were `1` instead of the required `3`.
- `SimulationRunControllerTest.configuringTheCurrentFrequencyKeepsTheExistingRunDelay` observed two pending delay requests `[250, 250]` instead of the existing `[250]` delay.

With the one-line guard, all 31 tests passed: 10 DIP persistence cases, 12 controller cases, and nine editor state, interaction gate, and simulation queue cases. The controller cases include the existing changed-frequency check from 250 ms to 1000 ms, invalid frequency rejection, late result rejection, pause, and foreground activation.

The complete current `EditorState.kt`, `SimulationRunController.kt`, and those test sources were compiled with Kotlin 2.4.21/JVM 21 and executed with JUnit 4.13.2 against immutable dependency snapshots. Local logs, frozen sources, source/dependency SHA-256 receipts and the reproducer are in `/tmp/logisim-frequency-idempotence/` and `/tmp/verify-frequency-idempotence.py`.

This directly exercises the controller call caused by the editor's project effect; it does not execute a Compose recomposition or native UI. No Gradle or Native execution was performed for this focused change. Integrated gates remain separate evidence.

## Integrated verification

The fix was integrated as `ce1c583e`, followed by the native import lifecycle regression in `df276e39`. The integrated Gradle run completed successfully: 456 Android host editor tests and 458 iOS Simulator ARM64 editor tests, with zero failures, errors or skips. This includes both frequency regressions and the saved DIP queue case. The Native task explicitly selected simulator `20A4D87D-A2A6-46BD-A889-C4888CE1E152`.

```sh
./gradlew :composeApp:testAndroidHostTest :composeApp:iosSimulatorArm64Test \
  -PiosTestDevice=20A4D87D-A2A6-46BD-A889-C4888CE1E152 \
  -Pkotlin.incremental=false --console=plain
```

The local log is `outputs/implementation/frequency-import-lifecycle-integrated-validation.log` (`BUILD SUCCESSFUL in 2m 36s`). This was a host/Native test run; application UI receipts from the earlier `9455c044` build do not include the subsequent frequency guard.
