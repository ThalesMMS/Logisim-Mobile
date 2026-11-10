# I/O geometry oracle

`generate-layout-fixtures.py` opens 77 I/O configurations with the desktop JAR and records their bounds, terminal positions, widths, and directions in `IoGeometryOracleTest.kt`. The test expectations come from the desktop, not from the mobile layout function.

The fixture set covers the 12 components in issue #44, all four directions where supported, separate and bus inputs, decimal-point options, matrix row/column/select modes, DIP widths, and keypad sizes. The reference source is Logisim Evolution revision `fbb1eca6`.

Regenerate the expectations:

```sh
python3 scripts/oracle/io/generate-layout-fixtures.py /path/to/logisim-evolution-5.1.0dev-all.jar
```

Check against the same desktop without changing the file:

```sh
python3 scripts/oracle/io/generate-layout-fixtures.py /path/to/logisim-evolution-5.1.0dev-all.jar --check
```

Run the common logic and geometry tests:

```sh
./gradlew :core:jvmTest --tests '*Io*Test'
```

The I/O state tests cover button press/release and cancellation, 32-switch DIP state, joystick quantization, keyboard buffering and clock consumption, TTY control characters and scrolling, keypad conflicts, segment masks, matrix packing, and persistence. A one-row TTY wraps safely; the desktop implementation can throw when committing into its zero-length history array.

Run the independent randomized comparison of keyboard, TTY, and matrix state:

```sh
./scripts/oracle/io/run-state-oracle.sh /path/to/logisim-evolution-5.1.0dev-all.jar
```

The comparison uses the real desktop state classes and the compiled mobile JAR. With seed `440071`, 889,328 observations match. The script prints hashes for both JARs. TTY differential samples use two or more rows; the one-row behavior is covered by the mobile regression test.

`IoInputPanel` and `IoInteractions` dispatch the same `IoInputEvent` types. `drawIoComponent` reads the evaluated `ComponentState` and does not modify the simulation. Device UI validation belongs to the integrated Android/iOS test run.
