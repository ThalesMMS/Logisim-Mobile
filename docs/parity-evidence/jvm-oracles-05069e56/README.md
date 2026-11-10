# JVM oracles against Evolution d58a61e0 (2026-11-07)

Runs on macOS (Apple silicon) with Homebrew OpenJDK 21.0.13, from a clean worktree. Every run used the same two JARs:

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar`) | `8fa1d01d0a6f396e71ef435fb5bd3122e0a2d3c13f137c10cb4060ffa9261e50` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

The core JAR is byte-identical at `fcbfd700` and `05069e56`. Between them, `ac8278d8` changes only Android runner scripts and `05069e56` only changes `scripts/WiringOracle.java`, which no longer compiled because `WiringValues.transistor` gained a defaulted `original` argument; it now passes `false`, the Evolution mode. The runs marked `05069e56` were first made at the pre-rebase commit `8be35b3b` and repeated at `05069e56` with the same results; the logs are from the second run.

`EVOLUTION_JAR` is the desktop JAR above. `logs/` keeps the output of each run, with local paths replaced by `EVOLUTION/` and `MOBILE/`.

| Evidence | Mobile revision | Command | Result |
| --- | --- | --- | --- |
| `wiring-05069e56` | `05069e56` | `scripts/verify-wiring-oracle.sh EVOLUTION_JAR` | PASS: 80,000 value comparisons (Transistor, Transmission Gate, Bit Extender) and 533 geometry cases (Clock, Probe, Pin, Constant, Power, Ground, Pull Resistor, Tunnel, Power-On Reset, Transistor, Transmission Gate, Splitter, Do Not Connect, Bit Extender). [log](logs/wiring.txt) |
| `io-fcbfd700` | `fcbfd700` | `python3 scripts/oracle/io/generate-layout-fixtures.py EVOLUTION_JAR --check`, `scripts/oracle/io/run-state-oracle.sh EVOLUTION_JAR`, `./gradlew :core:jvmTest --tests '*IoGeometryOracleTest'` (at `05069e56`) | PASS: the desktop reproduces the 77 committed geometry cases (Button, LED, RGB LED, DIP switch, Joystick, LED bar, Hex digit, 7-segment, Dot matrix, TTY, Keypad, Keyboard) and the Mobile layout matches them; 889,328 keyboard, TTY and dot-matrix state observations match with seed 440071. [geometry](logs/iogeom.txt), [state](logs/iostate.txt) |
| `extra-fcbfd700` | `fcbfd700` | `scripts/oracle/extra/run.sh EVOLUTION_JAR` | PASS: 71,316 comparisons, including Slider and TwoPinLed. [log](logs/extra.txt) |
| `ttl-fcbfd700` | `fcbfd700` | `scripts/oracle/ttl/run.sh EVOLUTION_JAR --no-build` | PASS: 444,000 combinational and 95,232 sequential comparisons, no mismatch, seed 45007483 (85 models, including 7447, 74245, 74299 and 74640). [log](logs/ttl.txt) |
| `fp-fcbfd700` | `fcbfd700` | `scripts/oracle/floating-point/run.sh EVOLUTION_JAR` | PASS: 94,116 outputs/delays and 1,160 layouts against 18 desktop factories, including FPConstant, seed 33042026. [log](logs/fp.txt) |
| `annotations-fcbfd700` | `fcbfd700` | `scripts/oracle/annotations/run.sh EVOLUTION_JAR` | PASS: the desktop opens and re-saves Text and Image annotations with the same geometry, pixels and attributes. [log](logs/annot.txt) |
| `memory-images-fcbfd700` | `fcbfd700` | `scripts/oracle/memory-images/run.sh EVOLUTION_JAR` | PASS: 2,464 words in both directions across the 11 desktop memory-image formats and 7 widths. [log](logs/mem.txt) |
| `soc-05069e56` | `05069e56` | `javac -cp EVOLUTION_JAR scripts/SocProgramDesktopOracle.java scripts/SocRv32DesktopOracle.java`, then `java SocProgramDesktopOracle fixtures/soc/program-{rv32,nios2}.circ` and `java SocRv32DesktopOracle`; `./gradlew :core:jvmTest --tests '*SocProgramDesktopParityTest' --tests '*Rv32DesktopOracleTest'` | PASS: both desktop program traces are byte-identical to `fixtures/soc/program-*-desktop.csv`; the 288 RV32 register vectors are identical to `Rv32DesktopOracleTest`; the Mobile runtime and simulator reproduce all 22 steps of both programs (CPU, bus, RAM and PIO). [tests](logs/gradle-tests.txt) |
| `product-jvm-05069e56` | `05069e56` | `java -cp core-jvm.jar:kotlin-stdlib.jar dev.logisim.mobile.core.HeadlessCircuitCli --geometry 800x600 examples/and.circ`; `./gradlew :core:jvmTest --tests '*SharedVhdlDefinitionTest' :composeApp:testAndroidHostTest --tests '*EditorKeyboardShortcutsTest' --tests '*EditorSearchCommandsTest' --tests '*ProjectSearchTest' --tests '*EditorPreferencesTest' --tests '*CatalogWorkspaceBindingTest' --tests '*EditorToolbarLayoutTest'` | `--geometry` exits with code 2 and a diagnostic ([log](logs/geometry.txt)); 81 tests pass, including the 22 continuous-save tests of `CatalogWorkspaceBindingTest` ([tests](logs/gradle-tests.txt)). |

`scripts/oracle/wiring/constant-attributes.sh` also passed (43 desktop parser checks), but it does not load Mobile code, so the matrix does not cite it as a comparison.

These runs prove headless JVM behaviour only. They do not cover Android or iOS rendering, touch input, sound, or device timing.
