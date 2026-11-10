# Formats and integrations compared with Evolution at Mobile a4e57de9 (#331)

Campaign [#331](https://github.com/ThalesMMS/logisim-mobile-dev/issues/331), Epic #255, final acceptance #243.

- Mobile `a4e57de9eb3555fb5e6eed78ab6d5470e43ee229`, `core/build/libs/core-jvm.jar` built from it with `./gradlew :core:jvmJar` (product code unchanged; only `scripts/oracle/**` was added or fixed).
- Evolution `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, JAR `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`.
- macOS (Apple silicon), Homebrew OpenJDK 21, Yosys, Icarus Verilog 13, NVC 1.23, tclsh. JVM/host only: no emulator, simulator, board or vendor toolchain.
- Evolution runs headless with an in-memory `java.util.prefs` backend and a temporary `user.home` (`scripts/oracle/integrations/MemoryPreferencesFactory.java`). The SoC program oracle needs AWT (Evolution creates a `JDialog` when it loads a SoC bus) and runs with `-Dapple.awt.UIElement=true`.

Command (one run, 2026-11-07):

```sh
. scripts/java-home.sh && export JAVA_HOME
python3 scripts/oracle/integrations/run.py EVOLUTION_JAR --out docs/parity-evidence/formats-integrations-a4e57de9
```

`results.json` records the hashes, tool versions and exit status of each suite; `logs/` keeps the output.

| Suite | What runs on each side | Result |
| --- | --- | --- |
| `fpga-boards` | Evolution `BoardReaderClass` vs Mobile `FpgaBoardCodec` on GECKO5 and BASYS3 | PASS, 2518 fields |
| `vectors` | Evolution `TestVectorEvaluator` vs Mobile vector runner on the same `.circ` and vector files | PASS, 14 rows |
| `hdl-import` | Mobile import (companion Yosys + `HdlNativeImporter`), `.circ` reopened in Evolution and in Mobile, checked against Icarus | PASS, 44 rows (the oracle needed the new `HdlMemoryPolicy` argument) |
| `verilog-evolution-import` | Evolution's own `VerilogImporter` on the same Verilog vs the Mobile import, both executed in Evolution's engine | PASS, 40 vectors (hierarchy, gates, macros) |
| `hdl-declarations` | Evolution `VhdlParser`/`BlifParser` vs Mobile `HdlIpDeclarations` (name, ports, widths, generic defaults) | 13 same, 11 accepted only by Mobile, **2 differ** |
| `blif-cosim` | Evolution `BlifParser` + `DenseLogicCircuit` vs the companion `hdl.step` handler, same step sequences | PASS, 350 steps in 5 fixtures; fixtures Evolution cannot read were skipped |
| `fpga-hdl` | `--test-fpga fpga-comb.circ main BASYS3 HDLONLY` in Evolution and in the Mobile launcher, VHDL and Verilog; package pins, part and build-script inputs compared; both top levels simulated (nvc/Icarus) by package pin | PASS, 16 combinations per language, both equal Evolution `-tty table` |
| `soc-drivers` | Evolution `PioMenu.exportC` call sequence (`SocSupport`) vs Mobile `SocDriverExport` register maps, 5 PIO variants | 3/5 equal; see differences |
| `soc-program-desktop` | Evolution CPU, bus, RAM and PIO rerun on `fixtures/soc/program-{rv32,nios2}.circ` | Equal to the committed desktop CSVs; the Mobile side is `SocProgramDesktopParityTest` (Gradle, not run here) |
| `tcl-desktop-protocol` | Companion Tcl replay vs Evolution's unmodified `tcl_wrapper.tcl` under tclsh | OK, 3 tests |
| `evolution-images` | Evolution `ExportImage` drawing path (headless copy) for GIF, JPG, TikZ and SVG of `fixtures/visual-export/qualification.circ` | Reference files written, 1503×903 at scale 0.25. No Mobile counterpart ran (see below) |

## Differences found

1. **VHDL generics** (`hdl-declarations`). Evolution gives an `integer`/`natural` generic without a default the value 0 (`positive`: 1) and accepts `time` generics (`5 ns` → 5000000 fs). Mobile rejects both (`missing-generic`, `unsupported-generic`). Reproduce: `scripts/oracle/integrations/hdl/evolution_generics.vhd`, `evolution_time_generic.vhd`.
2. **Dialect supersets** (not defects in Mobile). Evolution's `VhdlParser` rejects `end entity;`, port lists with widths written as expressions, and more. Its `BlifParser` rejects `.names` logic tables and ignores `.latch`. Mobile reads all of these. Evolution would reject or mis-simulate such a component in a file saved by Mobile.
3. **PIO C drivers** (`soc-drivers`). Evolution's exported `DirectionReg` uses `base[2]` (0x8). That is the IRQ mask offset, and Evolution's own `PioState` decodes the direction register at 0x4. Evolution emits `Outset`/`Outclear` only when the PIO has inputs. Mobile follows the bus decoder: 0x4, and set/clear only when the PIO has outputs. The C style also differs (`uint32_t`, `BASE_ADDRESS` macros).
4. **External JAR in the sandbox** (`logs/jar-runtime.txt`, run outside `run.py`). `JarRuntimeIntegrationTest.test_live_driver_trace_*` used `companion/tests/fixtures/DriveLibrary.java`, compiled against the Evolution JAR. It failed with `incompatible-jar: InvocationTargetException: null` under host isolation. The same `JarCircuitRunner.java` run directly on the same JAR and project loaded the library and completed. Reproduce:

   ```sh
   LOGISIM_COMPANION_EVOLUTION_JAR=JAR LOGISIM_TEST_JAR_JAVA=$JAVA_HOME/bin/java LOGISIM_TEST_DRIVE_JAR=drive.jar python3 -m unittest companion.tests.test_jar_runtime
   ```

## Not compared in this run

- **GIF, JPG, SVG, TikZ.** The Mobile exporters live in `composeApp` (`VisualSceneRenderer`, `TikzDrawing`) and need a host test. The Evolution references are in `evolution-images/`.
- **WaveDrom.** Evolution's exporter lives inside `ChronoPanel` and needs a `LogFrame` window.
- **SoC assembler.** Evolution's assembler needs an `RSyntaxTextArea` token stream.
- **Telnet.** Only spec-derived companion tests exist.
- **Autosave.** This is a design difference: Evolution writes `.circ.autosave`, Mobile keeps workspace drafts.
