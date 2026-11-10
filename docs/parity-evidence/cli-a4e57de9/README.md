# CLI comparison with Evolution at a4e57de9 (#331)

Mobile `a4e57de9eb3555fb5e6eed78ab6d5470e43ee229`, with `core/build/libs/core-jvm.jar` built at that revision (SHA-256 `0e596cb9d1a760eeb30d3f871adf5d009b3305f67f2cb5d3f82bef1205a500b9`). Evolution `d58a61e0`, JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. macOS (Apple silicon), Homebrew OpenJDK 21.0.13, JVM only. Run date 2026-11-07.

```sh
./gradlew :core:jvmJar
python3 -I scripts/oracle/cli/run.py --evolution-jar /caminho/logisim-evolution-5.1.0dev-all.jar --out docs/parity-evidence/cli-a4e57de9
```

84 cases yielded 57 PASS and 27 DIFF. The causes of the DIFF results are listed below and marked `known` in [`results.json`](results.json). Each case has a log with both command lines, exit codes, and outputs. Long outputs include a hash and an excerpt. [Logs](logs).

## Method

- Evolution runs with `java.awt.headless=true` and `IsolatedPreferencesFactory`, which stores `java.util.prefs` in a file in the output directory and isolates the user’s actual preferences. Mobile uses `LOGISIM_MOBILE_CLI_PROFILE` in the same directory.
- Each case runs both programs on the same fixtures: `examples/*.circ`, `scripts/oracle/cli/fixtures` (counter with `halt`, labeled RAM and ROM, ROM-fed TTY, external library with two replacements, `logisim_test_verif` benches), vectors from `scripts/oracle/vectors`, and `sr-dedicated.circ` with `BASYS3`.
- Evolution’s `-t table` is compared with Mobile’s `--tty-evolution -tty table`. The comparison also records what happens with the same literal command line for both programs.
- Exit codes are compared by class (success or failure). Evolution exits with -1 (255) or 10 on failure, and Mobile with 1, 2, or 3. Only stdout is compared. The oracle looks for the halt reason on both streams because Evolution writes it through the stderr logger. For `speed`, it compares line format and tick count and excludes rate.
- Gates, locale, template, splash, and clear-prefs apply only during graphical startup in Evolution. `EvolutionStartupOracle` calls `Startup.parseArgs` in-process as `Main.main` does (after reading `FollowSystemTheme`), applies `loadTemplate`, and prints the persisted store. Mobile prints its profile with `--startup-settings`. Each program reuses its store through a sequence of 22 steps.
- Evolution launches Swing before running `-w`, `-b`, and `-n`, which fails without a display. `EvolutionAutomationOracle` calls the same methods that `Startup.run` calls for each option. For `-b`, it omits `VhdlSimulatorTop.enable()`, which launches QuestaSim (out of scope and absent from the host) and prompts for its directory in a window. For `-n`, it enables `Main.headless`, which logs the old-file warning.

## Result by row

| Row | PASS/DIFF cases | Proposed result | Compared |
| --- | --- | --- | --- |
| `cli/help` | 1/1 | Explained difference | Both exit with 0 and print usage. Each lists its own options. Mobile help omits `--fpga-cable`, `--toplevel-circuit`, `-h`, `-l`, `-m`, `-s`, and `-t`. |
| `cli/version` | 0/2 | Explained difference | Both exit with 0. Evolution prints 4 lines (name, URL, commit, JVM). Mobile prints `Logisim Mobile 0.2.0`. |
| `cli/tty` | 17/7 | Divergent | The table matches for 4 circuits and 6 combinations of `csv`, `tabs`, `hex`, and `binary`. TTY bytes, `table,tty`, `speed` format and ticks, and `stats` counts and order match. See D1 through D4. |
| `cli/load` | 2/2 | Divergent | Loading labeled RAM and ROM matches, and both reject nonexistent labels. See D1 (`-l`) and D5. |
| `cli/save` | 2/0 | Match | The image saved after `halt` matches byte for byte (`v3.0 hex words plain`). Both succeed with a circuit without RAM or `halt`. |
| `cli/substitute` | 3/4 | Divergent | Both handle absolute OLD and identity mappings and reject repeated OLD. See D1 (`-s`, `--substitute`) and D6. |
| `cli/toplevel-circuit` | 3/1 | Divergent | Both select `top` and `main` (`--toplevel-circuit` vs. `--circuit`) and reject a missing circuit. See D1 (`--toplevel-circuit`). |
| `cli/test-vector` | 3/1 | Explained difference | Failing vectors, observed values, and totals match in `and` (6/2) and `flip` (6/0). D7 covers the exit code. |
| `cli/test-circuit` | 2/0 | Explained difference | `Test bench pass`/`fail` and exit class match. See D8. |
| `cli/new-file-format` | 6/4 | Explained difference | For 5 files (including 2.7.1 `original/counter.circ`), Evolution’s `-t table[,halt]` gives the same output on the original and both conversions. D9 covers the header. |
| `cli/test-fpga` | 1/0 | Explained difference | `HDLONLY` for `BASYS3` matches on the Vivado flow, physical pins, I/O standards, and clock period. See D10. |
| `cli/gates` | 4/0 | Match | `--gates iec`, `-g ANSI`, persistence, and rejection of invalid values match. |
| `cli/locale` | 3/2 | Explained difference | `pt` and `en` persist identically. See D11. |
| `cli/user-template` | 5/0 | Match | `empty`, `plain`, file, persistence, and rejection of a nonexistent file match. |
| `cli/no-splash` | 0/1 | Explained difference | See D12. |
| `cli/clear-prefs` | 5/2 | Explained difference | Defaults and profile reset match. See D13. |

## Observed differences

- **D1 `spellings`** (divergent). Mobile returns `Unknown option` and exits with 2 for the Evolution spellings `-t`/`--tty`, `-l`, `-s`/`--substitute`, and `--toplevel-circuit`. It accepts `-tty`, `--load`/`-load`, `--sub`/`-sub`, and `--circuit`.
- **D2 `tty-default-profile`**. The same command line, `-tty table and.circ`, produces a truth table with a header in Evolution and a 2.7.1 trace in Mobile. Mobile uses the Evolution format with `--tty-evolution`.
- **D3 `halt-trace-chars`** (divergent). With `--tty-evolution` and a `halt` pin, the Mobile trace uses `x` for floating bits (2.7.1), and Evolution uses `U`. The traces match after replacing `U` with `x`. Mobile writes `halted due to halt pin` to stdout. Evolution logs it to stderr.
- **D4 `stats-wording`**. The final captions are `TOTAL (without project's subcircuits)`/`(with subcircuits)` in Mobile (2.7.1) and `TOTAL (without project’s sub circuits)`/`(with sub circuits)` in Evolution. Counts, names, and order match in `hierarchy`, `sap1`, and `memory`.
- **D5 `load-unlabeled-rom`** (divergent). Unlabeled `-l FILE` loads the image into every RAM and ROM in Evolution, as stated in its help. Mobile loads only RAM and keeps the ROM contents.
- **D6 `substitute-paths`**. Evolution compares OLD with the loader’s resolved path based on the path supplied for the project (relative to the current directory). Mobile resolves OLD relative to the project directory. Absolute paths match. Without `-s`, Evolution opens the referenced library. Mobile requires an explicit mapping for each `.circ` library.
- **D7 `vector-exit`**. After `-w`, `Startup.run` exits with 0 even if vectors fail. Mobile exits with 1. Evolution outputs text, and Mobile outputs CSV with `status=` on stderr.
- **D8**. Evolution’s `-b` always starts QuestaSim and opens a window when it is unconfigured. The bench reads an asynchronous simulator using wall-clock time (60 s). With constant `ok` and `done` driven by the clock, Evolution results vary between runs (3 of 6 passed in the oracle). The fixture deterministically uses one wire for `done` and `ok`. Mobile uses logical ticks (10,000 by default). Failure returns 255 in Evolution and 1 in Mobile.
- **D9 `convert-preserves-profile`**. Evolution’s `-n` writes `source="5.1.0dev"`. Mobile preserves the input profile (`3.8.0`, `2.7.1`, `5.1.0-dev`), as documented in `docs/headless-cli.md`.
- **D10**. `-f` takes a built-in board name in Evolution and the board XML in Mobile. Evolution writes to `FPGAWorkspace` using its VHDL generator. Mobile writes a ZIP using its own generator (Verilog by default). Port names and HDL differ.
- **D11 `locale-set`**. Evolution accepts 13 languages by exact name (`de`, `pt`, …) and rejects `pt-BR`. Mobile accepts `en` and `pt` with regional variants.
- **D12 `no-splash-notice`**. Both start without a splash screen. Mobile reports on stderr that its launcher already starts that way.
- **D13 `clear-prefs-tty`**. Evolution ignores `--clear-prefs` when `-t` is present. Mobile always restores its profile. Evolution clears all preferences. The Mobile launcher profile contains only gates, locale, and template.

## Outside this comparison

- Keyboard input in `-t tty`. Evolution reads stdin on a thread and delivers bytes according to wall-clock time, without a fixed point between ticks. The `tty-component` case compares only TTY output.
- `--fpga-cable` and building with the companion depend on Quartus or physical boards, which are out of scope.
- `original/counter.circ` (2.7.1) produces different traces with `-t table,halt` because Mobile uses 2.7.1 simulation semantics for 2.7.1 files (`OriginalCounterCompatibilityTest`). This comparison uses it only for `-n`, where Evolution reads both conversions.
