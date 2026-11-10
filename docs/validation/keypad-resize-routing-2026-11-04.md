# Connected keypad resizing

`CircuitEditor.updateComponents` now accepts `large` to `normal` keypad size changes in `examples/io-showcase.circ`. The fixture keeps its four scan inputs, four column probes, and all eight named keypad terminals. The I/O renderer, schema, and fixture are unchanged.

The old editor tried two routes with one elbow each. For the four row terminals, one orientation overlapped wires at `y=1030`; the other crossed the fixed scan pins at `y=1150`. The columns had the same problem on the other axis. Both candidates changed the terminal connections, so the editor correctly rejected them with `Group editing cannot preserve the connections.` The rejected edit was a routing limitation, rather than a removed terminal or an invalid size value.

When both simple routes fail, the editor now tries routes with two elbows on separate grid channels. It follows each existing wire through free bends and stops at a terminal, a junction, or a loose end. This also lets a later resize replace the whole free chain instead of accumulating bends. The existing terminal-connection comparison must accept the complete candidate before the editor publishes an undo entry.

Closed chains move with their terminal. An open chain that already ends at the new terminal position retains its wires. These cases cannot be replaced by a zero-length route because that would remove wires without changing the terminal partition. Fixed branches and shared terminal positions remain attached.

This is a bounded routing fallback. If neither channel order preserves the connections, the edit is still rejected atomically.

## Verification

The standalone check uses Kotlin 2.4.21, JVM 21, and JUnit 4.13.2. It compiles the changed `CircuitEditor.kt` and test sources ahead of an immutable Core JAR produced from `f72c8b138a2417b6beb8bb80ed3f4101171fbbbb`. That JAR has SHA-256 `7d16a8519f19e033f7ad3350b97402abf492270747b51ed725a9237228f64e20`. Its original editor source matches the worktree base `c8bef175a7c6de0ad2e5a22ea2999df0212414c9`.

Six `KeypadResizeEditorTest` cases pass. They cover the complete fixture across `normal`, `large`, and `normal` sizes; all sixteen key scans; every named terminal's net; XML export and reopen; exact Undo and Redo; an unrelated loose wire; a fixed branch; a shared terminal; a closed loop; a loose end at the new port position; and atomic rejection of a port placed on a different net.

The same six-test source reproduces five failures against the original editor. The negative test for an unavoidable short passes in both versions. The test source has SHA-256 `ba185410dd1aa0eba2b640e7481069043b17133a2bbac26d1ba4463d82ddffbd`; the compiled correction has SHA-256 `fbd09a07a863a03c02ac30eced87247bfa07cfc6454c4ff8436e65c8562d7e3f`.

The final focused run passes 69 tests: the six new cases plus `CircuitEditorTest`, `CircuitEditingTest`, `ComponentRegistryTest`, `CircuitDiagnosticsTest`, `IoLibraryTest`, `IoGeometryOracleTest`, and `IoShowcaseFixtureTest`.

Local commands and receipts are retained in `/tmp/verify-keypad-resize.py` and `/tmp/logisim-keypad-resize/`. The receipts record source hashes, the frozen JAR hash, compiler status, and JUnit status. The normal Gradle entry point for the new tests is:

```sh
./gradlew :core:jvmTest --tests dev.logisim.mobile.core.KeypadResizeEditorTest
```

Gradle, Kotlin/Native, Android UI, and iOS UI were not run for this focused correction. The native I/O scenario must still exercise the resize on an app built with this source.
