# Circuit names, labels, #FPArithmetic and the default template against Evolution d58a61e0

Runs of 2026-11-07 for #321, #323 and #324 at Mobile `e8c4dd9deebd5c16ab1f6ed46ed081a419075794` (code in `25d5bd56` and `4a3b2ae1`), on macOS (Apple silicon) with Homebrew OpenJDK 21.0.13, from a clean worktree. Desktop JAR: `logisim-evolution-5.1.0dev-all.jar` built from `d58a61e0`, SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. Both oracles load Evolution headless (`java.awt.headless=true`) with in-memory Java preferences.

## #321: names and labels (`evolution-names-e8c4dd9d`)

`EvolutionNamesEvolutionTest` saves, through the editor, the built-in example, the Android `unicode` scenario file (`Visão → ‫اختبار‬`, `E2E — ligação longa → entrada / ‫مرحبا‬`), the example as Mobile `da740d84` saved it (`Minha primeira porta AND`, `Saída`) and the seven bundled examples, into `fixtures/evolution-names/mobile-*.circ`. It also writes `names.tsv`: 21 sample texts with Mobile's rename, suffix, entry decision and load decision.

`scripts/oracle/evolution-names/run.sh EVOLUTION_JAR` opens each file in Evolution twice, re-saves it and opens the re-saved file, and checks `names.tsv` against `XmlReader.generateValidVHDLLabel(text, suffix)`, `XmlReader.labelVHDLInvalid` and `SyntaxChecker.getErrorMessage(text, "VHDL")`. All 93 `result` lines in `fixtures/evolution-names/evolution-load.tsv` are true: every file reopens with the names Mobile wrote, the same on a second open and after a re-save, and the three functions agree with Mobile on every sample.

Negative control: the same oracle on the `da740d84` file prints `Minha_primeira_porta_AND_71030764` and `Sa_da_8dd3ce3d`, different on each open, and fails `names-unchanged-on-open`, `same-names-on-second-open` and `names-unchanged-after-resave`.

## #323 and #324: new project (`creation-defaults-e8c4dd9d`)

`scripts/oracle/creation-defaults/run.sh EVOLUTION_JAR` (from #313) now also prints the toolbar, the mouse mappings, the libraries and the main circuit frequency of each file once Evolution has loaded it, and requires them to match (`template-matches`). For the three profiles, `fixtures/creation-defaults/evolution-load.tsv` has no `difference` line and all nine `result` lines are true, including `evolution-resave-unchanged` for `evolution-true-true-false`, which listed `#FPArithmetic` twice before #323. A run of the new oracle on the files saved before #324 listed the 18 toolbar entries, the 3 mouse mappings, the libraries and `simulationFrequency=4.0` as differences.

## Commands

```sh
LOGISIM_REGENERATE_FIXTURES=1 ./gradlew :composeApp:testAndroidHostTest --tests '*CreationDefaultsEvolutionTest' --tests '*EvolutionNamesEvolutionTest'
scripts/oracle/evolution-names/run.sh EVOLUTION_JAR
scripts/oracle/creation-defaults/run.sh EVOLUTION_JAR
./gradlew :core:jvmTest :composeApp:testAndroidHostTest
```

The fixture tests then pass without `LOGISIM_REGENERATE_FIXTURES` (the saved files equal the committed ones and every oracle result is true). Both oracles reproduced the committed `evolution-load.tsv` files byte for byte at `e8c4dd9d`. `:core:jvmTest` ran 1,336 tests (11 skipped) and `:composeApp:testAndroidHostTest` 800 tests; the four failures of the first run (default frequency 4.0 and the repaired #FPArithmetic in `CreationDefaultsTest`) were test expectations updated in `4a3b2ae1` and pass in a focused re-run.

This evidence covers headless JVM file behaviour. It does not run the Android `unicode` scenario or the apps on a device or simulator.
