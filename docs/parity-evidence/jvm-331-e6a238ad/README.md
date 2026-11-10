# Campaign #331 rerun at e6a238ad

Mobile `e6a238ad5c5fac8a26859fa4cad62a61acb84e4b` (`origin/main`), `core-jvm.jar` SHA-256 `54cd741eaa2470e338680c6d622818d14791dc5d3d76eeb01d8d1464e377fb84`. Evolution `d58a61e0`, JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`. macOS (Apple silicon), Homebrew OpenJDK 21.0.13, Yosys, Icarus Verilog, NVC, and tclsh. JVM only. Run date 2026-11-08. [Environment inputs](inputs.txt).

The campaign first ran at `a4e57de9` (`cli-a4e57de9`, `formats-integrations-a4e57de9`, `options-tools-a4e57de9`, `telnet-soc-debugger-a4e57de9`, and `visual-export-a4e57de9` receipts). This run repeats the oracles for the 36 rows without a recorded comparison after fixes for #331 and issues #345–#361. The remaining 11 rows have evidence in their own issues.

Each step ran with a timeout that kills the process (`perl` with `fork` and `SIGKILL`). [Logs](logs).

| Step | Command | Result |
| --- | --- | --- |
| Tests | `./gradlew :core:jvmJar :core:jvmTest --tests '*HeadlessCircuitCliTest' --tests '*HeadlessTerminalTest' --tests '*SignalTraceTest' --tests '*JvmTelnetTransportTest' --tests '*TelnetRuntimeTest'` | One of 62 tests failed on the first run. The #354 ring test expected the halt reason on stdout, and the Evolution profile from #331 writes it to stderr. After the test fix in `b114b28d`, all 62 passed ([log](logs/gradle-after-test-fix.txt)). `SocProgramDesktopParityTest` and `Rv32DesktopOracleTest` passed 5/5 ([log](logs/gradle-soc.txt)). |
| CLI | `python3 -I scripts/oracle/cli/run.py --evolution-jar EVOLUTION_JAR --out cli` | 80 cases, 64 PASS and 16 DIFF, all with recorded causes ([`cli/results.json`](cli/results.json)). |
| Integrations | `python3 scripts/oracle/integrations/run.py EVOLUTION_JAR --out integrations` | `fpga-boards` PASS (2518 fields), `vectors` PASS (14), `hdl-import` PASS (44), `verilog-evolution-import` PASS, `hdl-declarations` 18 matching, 8 accepted only by Mobile, 0 differing, `blif-cosim` PASS (350 steps), `fpga-hdl` PASS in VHDL and Verilog, `soc-drivers` 3 of 5 matching maps, `soc-program-desktop` matches the CSV files, `tcl-desktop-protocol` OK. |
| Telnet | `scripts/oracle/telnet/run.sh EVOLUTION_JAR telnet` | 14 of 16 scenarios match when sampled at edges. The 2 differences involve switching peers, one per Mobile path. |
| Project options | `ORACLE_TIMEOUT=30 scripts/oracle/project-options/run.sh EVOLUTION_JAR project-options` | PASS, 21 matching fixtures. Outputs over 20 kB were stored as SHA-256 and header. |
| Exports | `python3 -I scripts/oracle/visual-export/run.py --evolution-jar EVOLUTION_JAR --out visual-export` | GIF and JPEG PASS on Android and iOS. WaveDrom matches except for the Evolution name `q[3..0]` and final `x`. SVG and TikZ show DIFF because this run omitted the host test that produces Mobile output. Issue #361 has evidence for both rows. |

## Result by row

- **Match**: `cli/load`, `cli/save`, `cli/toplevel-circuit`, `cli/gates`, `cli/user-template`, `format/test-vector`, `format/vhdl`, `format/verilog`, `format/tcl`, `integration/hdl-export`, `integration/fpga-mapping`, `integration/fpga-boards`, `integration/hdl-verilog`, `integration/soc-bus`, `integration/tcl`, `option/gateUndefined`.
- **Explained difference**: `cli/help`, `cli/version`, `cli/tty`, `cli/substitute`, `cli/test-vector`, `cli/test-circuit`, `cli/new-file-format`, `cli/test-fpga`, `cli/locale`, `cli/no-splash`, `cli/clear-prefs`, `format/blif`, `format/autosave`, `format/image-jpg`, `format/image-wavedrom`, `integration/fpga-build`, `integration/hdl-blif`, `integration/soc-drivers`, `integration/telnet`, `option/simrand`.

`parity/matrix.json` records each difference. See the [CLI comparison](../cli-a4e57de9/README.md) for causes D2 and D6 through D13.
