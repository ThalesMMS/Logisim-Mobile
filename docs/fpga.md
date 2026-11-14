# FPGA projects and the companion

The FPGA panel lets you select a bundled board or import a Logisim board XML, map each exported HDL port bit, and validate the mapping before generating a project. It supports physical pins, constant input bits, open output or bidirectional bits, resource polarity, I/O standards, pull resistors, drive strength, and the declared board clock. Validation rejects incomplete mappings, incompatible directions, repeated physical pins, invalid device parameters, mismatched clock frequencies, and electrical settings the selected flow cannot represent.

The built-in catalog contains the 31 board definitions from Logisim Evolution `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`. Imported XML is copied into the app and appears as an external board. The last selection is restored when the app reopens. Select an external board and use **Remove external board** to remove that copy from the catalog.

For a scalar input, choose a physical clock and enter its frequency in hertz, or use the declared board clock shortcut. The simulator tick rate is never substituted for a physical frequency. Invalid or constant physical clocks are rejected. `HdlExportResult.fpgaDesign()` preserves the exported module order, directions and bus widths without inventing a clock rate.

A `Clock` component that drives clock inputs is fed by a tick generator, as in Logisim Evolution. The panel then lists no port for that Clock. Enter the tick frequency in hertz. The panel starts from the circuit's saved download frequency, then its simulation frequency. It shows the frequency the board oscillator actually gives and the number of oscillator cycles between ticks. **Save mapping** also stores the frequency in `downloadFrequency`, following the same rule as the Evolution clock panel. The export uses the board oscillator once, both for the tick and for other inputs mapped to it. The CLI produces the same files for the same circuit, board, map and frequency. A board without an oscillator cannot use the tick. A saved map that connects the Clock to a board pin keeps that physical input, and the panel says so. Circuits the tick generator does not support also keep the physical Clock input.

On Series 7 boards in the Vivado flow, choose **Derived clock (Series 7 MMCM)** on the clock input to set its feedback multiplier and output divider. The FPGA project keeps the physical oscillator pin and its period in the XDC. For example, BASYS3 uses W5 at 100 MHz, while a multiplier of 8 and a divider of 16 drive the design at 50 MHz. The exported wrapper instantiates the clock manager and global buffers. Vivado derives the internal timing clock from the primary constraint. [AMD Vivado constraints guide](https://docs.amd.com/r/en-US/ug903-vivado-using-constraints/Automatically-Derived-Clocks)

The importer preserves the original XML, including its board image data. It reads resource rectangles and electrical metadata without decoding the image. Mapping uses a touch-sized list of named resources and pins. For a supported display, choose **Scanned display** on an output, select the display, and enter a logical channel. **Map whole output starting here** fills consecutive channels from bit 0 of a bus. Logical channels run in row-major order for LED arrays, with red, green, and blue planes in that order for RGB. Seven-segment channels are grouped by digit, eight per digit in board segment order. Unmapped channels stay off. A project that provides its own scan logic can still map the physical terminals.

The FPGA wrapper uses the board oscillator to advance one scan position per millisecond. It generates row or column addresses for LED arrays and either decoded digit addresses or one-hot digit selects for seven-segment displays. Segment and LED data use the board's activity level, and one-hot digit select polarity follows the drive mode. The wrapper reserves the display pins and rejects conflicting physical mappings. An imported Evolution output pin mapped to an RGB pixel drives all three color channels and retains the pixel mapping on save. Independent color assignments made in Mobile are saved in Mobile metadata.

Use **New board** to create a definition or **Edit board** to change the selected board. Pick a PNG or JPEG for the board image, enter the FPGA and clock details, then add or select a resource. Resource positions and dimensions are in board-image pixels. Enter the physical pins in order for each direction. **Save board XML** validates the definition and opens the system file picker. The saved file can be imported by both Logisim Evolution Mobile and Logisim Evolution.

## Supported and verified paths

| Path | Project generation | Tool execution verified on this Mac | Physical board verified |
| --- | --- | --- | --- |
| ECP5 open flow | Verilog/VHDL wrapper, LPF and `build-ecp5.sh` | Yosys, nextpnr-ecp5 and ecppack, GECKO5 device 85K/CABGA381 | No |
| iCE40 open flow | Verilog/VHDL wrapper and PCF | Yosys, nextpnr-ice40 and icepack, HX1K/TQ144 | No |
| Vivado (BASYS3, ALCHITRY Au + Io, CMOD A7 15T, ZYBO) | HDL, XDC and build Tcl | Approved companion build with fixture executable. Real Vivado pending. | No |
| Xilinx ISE | HDL, UCF, XST project and fixed stage arguments. Fixture tools cover BASYS2, Reptar, Spartan-3E Starter and GECKO4LED bitstreams, and MICROLABTLE JEDEC. | Licensed Xilinx tools pending | No |
| Altera Quartus (catalog FPGA and MAX V/MAX 7000S CPLD) | HDL, QSF, QPF and SDC | Approved companion build with fixture executable. Real Quartus pending. | No |

Programming on a physical board and builds with Quartus, Vivado or ISE are not qualified in this project. The vendor adapters are exercised only with fixture executables.

GECKO5 EDUCATION and BASYS3 board imports match the desktop `BoardReaderClass` across 2,518 field comparisons: device and clock metadata, 130 resources, 180 physical pins, rectangles, labels, directions and electrical settings. The fixtures and their provenance are in `core/src/jvmTest/resources/fpga/README.md`.

The Android-exported iCE40 AND bitstream also passed simulation after reconstruction with IceStorm. Icarus checked all four input combinations using both the named HDL ports and the recovered physical pins 21, 22 and 23. The [runnable bitstream sample](../examples/fpga-bitstream/README.md) retains the binary and testbench. This is the simulated validation requested when no board was available. Physical programming and electrical behavior remain untested.

The generated Verilog and VHDL wrappers run through Icarus Verilog 13.0 and NVC 1.23.1. Four simulations check 28 cases covering bus order, active-low inputs and outputs, bidirectional driving and sampling, high impedance, and contention. These are HDL simulation results, not electrical measurements.

## Companion operations

`FpgaBuildPlan.companionRequest()` uploads flattened, self-contained HDL files. The bitstream goal also uploads the generated LPF or PCF. Uploaded Yosys and Tcl scripts are never executed by the FPGA handler; it constructs fixed argument lists and a synthesis script on the host. Vendor projects can also be exported for the appropriate installed vendor tools.

An approved `fpga-quartus-build` request sends HDL, pin assignments, timing constraints and an approved device profile. The host validates the assignments, generates its own Quartus project, and returns `design.sof` with its digest for FPGA targets. The profiles cover the catalog's Cyclone, Cyclone II, Cyclone III, Cyclone IV E, Cyclone V, Cyclone 10 LP and MAX 10 FPGA parts, including DE0-CV. Install device support for the selected part and configure `quartus_sh` on the host. Use a separate companion endpoint for each installed Quartus version when targeting different generations.

Consult the [Altera device support matrix](https://www.altera.com/developers/software/device-support) when choosing the edition and version. Legacy Cyclone devices use Quartus II through 13.0 SP1 Subscription or 11.0 SP1 Web. Cyclone II uses Quartus II through 13.0 SP1, and Cyclone III through 13.1. Quartus Prime Standard and Lite support Cyclone IV E, Cyclone V, Cyclone 10 LP and MAX 10 with the corresponding device packages.

Adjust the companion's time, memory and output limits for the Quartus installation, for example `--job-timeout-seconds 900 --job-memory-mib 6144 --job-output-mib 7`. Artifacts use the 5 MiB inline limit. When the host and client support large SOF transfer, the client can download a reviewed Quartus `design.sof` over 5 MiB and up to 8 MiB as a binary file.

For the real Quartus qualification in [#291](https://github.com/ThalesMMS/logisim-mobile-dev/issues/291), record the installed edition and version, selected device, SOF digest and timing result. A host-controlled `quartus_sh` launcher can retain the tool's `quartus-output/build.sta.rpt` after compilation for the qualification record.

For a BASYS3 (`xc7a35tcpg236-1`) or ALCHITRY Au + Io (`xc7a35tftg256-1`), a companion with an approved `fpga-vivado-build` operation can build the bitstream. The mobile plan sends flattened HDL and generated XDC. The host accepts only supported pin, electrical and clock constraints, including pins generated for display scanning, and creates its own Vivado Tcl script. The result contains the bitstream and timing report. Programming requires a separate flow.

Run the companion on a supported Linux x86-64 host with the AMD Artix-7 tools and the license required by that installation. Configure the Vivado executable or an operator-controlled launcher that exposes the license to Vivado. For example:

```sh
python3 -m companion.server --allow fpga-vivado-build \
  --tool vivado=/absolute/path/to/vivado --job-timeout-seconds 300 \
  --job-memory-mib 12288 --job-output-mib 6
```

The command uses the normal companion pairing and approval controls. Adjust the memory and time budgets for the selected installation and design. The companion limits process resources and rejects job results above the output budget. The [Vivado host requirements](https://docs.amd.com/r/en-US/ug973-vivado-release-notes-install-license/Supported-Operating-Systems) list the supported platforms.

To validate #180, pair the Mobile app to that host, import `BASYS3.xml`, map the one-bit pass-through A to V17 and Y to U16, and approve the bitstream plan. Check that Vivado logs synthesize, route and write the bitstream, then export the artifacts. Record the tool version, bitstream size and SHA-256, timing report and result flags. Run a second request with an HDL error for diagnostics and cancel a running build to verify that no artifact is returned. These steps generate a bitstream without requiring a physical board.

Quartus builds for `MAX_V_CONSOLE` and `MAX_V_80P_25P` use the 5M570ZF256C5 device and return `design.pof`. Install MAX II/MAX V device support in the host's Quartus installation. To program a POF, the operator must enable `fpga-program`, configure `quartus_pgm`, and create a `quartus-cpld` target with the exact cable name reported by Quartus and its zero-based JTAG position. For example, a fixture target is `{"board":"MAX_V_CONSOLE","transport":"quartus-cpld","cable":"USB-Blaster [1-2]","chainPosition":0}`. The Mobile confirmation identifies the nonvolatile CPLD write and binds the file digest to the configured target. Use `quartus` for volatile SOF programming. External FPGA flash is a separate operation.

`EPM2525_CONSOLE` and `EPM2525_FULL` use the EPM7128SLC84-7, and `SLO_MAX7000S` uses the EPM7256SQC208-7. Configure the companion to use [Quartus II 13.0 SP1 with MAX 7000 device support](https://www.altera.com/developers/software/device-support) for these builds. The app and CLI expect `design.pof`. Select one of the exact board names in the host's `quartus-cpld` target to program a built or imported POF from the app. The review shows the full device part. The companion checks the part in the POF and scans the selected cable for the expected JTAG device before programming.

To prepare volatile programming of a SOF over 4 MiB and up to 8 MiB, add the exact part as `sofPart` to the host's `quartus` target. A DE10-Nano profile uses `"sofPart":"5CSEBA6U23I7"`. The host checks the file's device record and SHA-256 against the reviewed file and target before queuing the job.

The panel's export callback receives the complete build plan. `plan.archive()` creates an ordinary deterministic ZIP containing its original file paths and contents. The companion panel exposes only artifacts whose size and SHA-256 pass client validation; its export callback can package those bytes with `fpgaArchive(artifacts.associate { it.name to it.bytes })`, preserving binary bitstreams. Native document operations remain responsible for the destination picker and save result.

`fpga-synthesize` has separate `synthesis` and `bitstream` goals. The first produces a synthesized JSON netlist. The second additionally runs place and route and packages a bitstream. Logs identify each stage; tool failures retain its exit status and diagnostics. Explicit cancellation terminates the active child process. Results report synthesis, placement/routing and bitstream generation separately and leave `programmed` and `hardwareVerified` false.

The host advertises `fpga-program` only when its operator configured a supported board and a physical cable serial. It also requires the server's request-bound, one-shot confirmation and an explicitly allowed operation. The client cannot choose a command or replace the configured board/cable. Tests exercise programming with a fixture executable only. No actual board was programmed during this implementation; hardware testing remains pending as requested.

After a validated bitstream build, the programming panel lists only aliases advertised by that host. Review the selected alias and bitstream hash and size, then explicitly confirm a separate programming plan. A lost submission response retains the original request identifier for retry. Active or uncertain programming stays bound to its original host; the displayed programmer result remains separate from physical hardware verification.

Temporary programming remains the default. When the operator sets `"allowFlash": true` for an explicit openFPGALoader JTAG profile, choose **Persistent (flash)** separately and confirm the flash write after reviewing the target, mode, and bitstream. The programmer's success report does not prove persistence. To qualify the board, use a test bitstream with visibly different behavior from its previous image, power the board off and on without reprogramming it, and verify that the test design runs again.

For GECKO4 Education, set `board` to `GECKO4_EDUCATION` for an `EP4CE15F23C8` or `GECKO4_EDUCATION_EPFL_EDITION` for an `EP4CE30F23C8`. Set `transport` to `quartus`, `chainPosition` to `0`, and `allowFlash` to `true`. Use the full cable name reported by `quartus_pgm --list`, such as `USB-Blaster [1-2]`. Install Quartus with Cyclone IV support and point `quartus_pgm` and `quartus_cpf` at that installation. The host looks for `sfl_ep4ce15.sof` or `sfl_ep4ce30.sof` under `common/devinfo/programmer`, according to the configured board. Confirm the board and EPCQ16 physically before attaching the cable. Some other FPGA models share the same JTAG IDs. [Cyclone IV JTAG device IDs](https://www.intel.com/programmable/technical-pdfs/654525.pdf)

Import a SOF for that board, then review the EPCQ16 target, cable, chain position and SOF hash before approving persistent programming. The host checks the SOF's device metadata against the reviewed board before conversion. It creates the COF and JIC, checks for the single expected FPGA on the selected cable, loads the serial flash bridge, and programs and verifies the JIC at position one. The job logs include the stage and the hashes of the SOF, COF, bridge and JIC. Use **Temporary** to program the SOF without conversion.

The host-only loader `companion.fpga_host_config.load_program_targets(path)` accepts this configuration shape:

```json
{"version": 1, "targets": {"bench": {"board": "ulx3s", "serial": "physical-cable-serial"}}}
```

The example is a schema illustration, not a configured test bench. It contains board and cable identities, never executable paths or command arguments. Duplicate fields, unknown fields, invalid identities and oversized files are rejected. An empty `targets` object leaves programming unregistered. Only the host operator selects this configuration; it is not loaded from mobile job inputs.

The companion runs trusted local EDA tools; it is not an operating-system sandbox. Its authenticated protocol, resource limits, configured tools, timeout, cancellation and explicit programming consent are defined by the companion service. The client currently limits uploads to 64 files and 4 MiB; the FPGA handler additionally limits individual HDL sources and validates their encodings and names. VHDL translation and ECP5 synthesis were separately verified with the official suite's GHDL `7.0.0-dev`; this check complements the NVC wrapper simulations.

## FPGA builds on an ISE host

On a POSIX host that runs Xilinx ISE and has its required license and runtime configured, enable `--allow fpga-ise-build` and configure `--tool NAME=/absolute/path` for each of `xst`, `ngdbuild`, `map`, `par` and `bitgen`. The companion starts its tools with a minimal environment. If ISE needs its settings script or a license environment variable, point these five tool entries to host-owned launchers that prepare the environment and forward the fixed arguments to the corresponding executable. The client uploads HDL and generated UCF only.

Use the pinned Evolution BASYS2 board file and a one-bit circuit wired from A to Y. In the Mobile FPGA panel, select Xilinx ISE and map the input to P11 and the output to M5. Pair the approved host, prepare the bitstream, review the request and run it. Verify that synthesis, translation, mapping, placement and routing, and packing complete in order. Save the returned `design.bit`, compare its SHA-256 to the artifact digest, and check that both programming and physical verification are false. To qualify errors and cancellation, submit invalid HDL through a separate approved request and cancel an active build. Check the failing stage and logs, and check that cancellation returns no successful bitstream.

For Spartan-3E Starter, map A to N17 and Y to F9. The host selects `XC3S500E-FG320-5`. For GECKO4LED, map A to K1 and Y to AC25. The host selects `XC3S5000-FG676-5` and sets `UnusedPin:PULLDOWN` during bitstream generation, as specified by the board definition.

The fixture tests exercise the host handler and input boundaries. The build with licensed ISE tools remains pending. A physical board and cable are needed for separate programming and hardware qualification.

## MICROLABTLE JEDEC on an ISE host

ISE project exports recognize `XC2C`, `XA2C`, `XCR3`, `XC9500` and `XA9500` by device part, including imported boards. Unpack the ZIP and run `sh build-cpld.sh` in the licensed ISE environment to synthesize, fit and generate `logisim.jed` using the exported UCF. The companion has a separate approved profile for its build operation.

The companion operation `fpga-ise-cpld-build` targets the MICROLABTLE CoolRunner-II `XC2C64` in package `VQ44` at speed grade `5`. Enable it and configure `xst`, `ngdbuild`, `cpldfit` and `hprep6` using host-owned tool paths or launchers. The app sends the generated HDL and UCF. After approval, the host runs synthesis, translation, fitting and JEDEC generation. The exported artifact is `design.jed`.

For the build acceptance, open `fixtures/user-scenarios/fpga-and.circ` in the app, choose the pinned MICROLABTLE board with Xilinx ISE and map `p_a` to P19, `p_b` to P44 and `p_y` to P5. Review the build and run it. Retain the tool version, four stage results and the `design.jed` SHA-256 shown in the app. Compare the digest to the extracted file after exporting. The build reports artifact generation separately from programming and hardware verification.

Enable `fpga-program` and configure `impact` to exercise the separate programming path. The host operator configures the actual cable and zero-based JTAG chain position. For example, if iMPACT identifies the MICROLABTLE on `usb21` at the first position, a matching `--fpga-targets` file is:

```json
{"version":1,"targets":{"bench":{"board":"MICROLABTLE","transport":"ise-cpld","cable":"usb21","chainPosition":0}}}
```

In the app, choose **Program an existing bitstream**, select `design.jed`, and review the digest, cable, chain position and nonvolatile programming action. Confirm once for the selected CPLD. The companion runs the iMPACT CPLD command against that position. Check the positional programmer success message and `programmingMode: cpld-nonvolatile` in the result. Existing ISE FPGA profiles continue to use `.bit` with `-onlyFpga`. The live vendor run remains pending. Record board-level behavior and persistence after power-off in [#194](https://github.com/ThalesMMS/logisim-mobile-dev/issues/194).

## Reproduce the checks

```sh
./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.Fpga*' :core:jvmJar
./scripts/oracle/fpga-boards/run.sh /path/to/logisim-evolution-all.jar
./scripts/oracle/fpga-build/run.sh
LOGISIM_FPGA_BITSTREAM=1 ./scripts/oracle/fpga-build/run.sh
python3 -m unittest companion.tests.test_fpga -v
```

The Python tests run installed tools and explicitly skip an unavailable toolchain. Set `NEXTPNR_ECP5` and `ECPPACK` to configured executables when they are outside `PATH`. The source tree does not bundle these tools or their device databases.

Homebrew provides `yosys`, `nextpnr-ice40`, `icestorm` (`icepack`) and `prjtrellis` (`ecppack` and the ECP5 database) under `/opt/homebrew/bin`, which the tests and `scripts/oracle/fpga-build` find on `PATH`. Homebrew has no `nextpnr-ecp5` formula. Use the OSS CAD Suite, or build the nextpnr release used by the `nextpnr-ice40` formula with `-DARCH=ecp5 -DTRELLIS_INSTALL_PREFIX=$(brew --prefix prjtrellis)` and set `NEXTPNR_ECP5` to the installed binary. A RISC-V bare-metal toolchain is available as `riscv64-elf-gcc` and `riscv64-elf-binutils`; the ELF demos written by `scripts/build-soc-demo-elf.py` do not need it, but `riscv64-elf-objdump -D -b binary -m riscv:rv32` disassembles their code. On 2026-11-07, at revision `f2b6dbb0`, this Mac used Yosys 0.70, nextpnr-ice40 0.11.1, nextpnr-ecp5 0.11.1 (built from source), icestorm 1.1, prjtrellis 1.4, riscv64-elf-gcc 16.2.0 and binutils 2.47. `python3 -m unittest companion.tests.test_fpga` ran the real iCE40 and ECP5 place, route and pack tests (14 tests; GHDL skipped), and `LOGISIM_FPGA_BITSTREAM=1 ./scripts/oracle/fpga-build/run.sh` passed 28 wrapper cases and produced a 279,950-byte `design.bit`. No board was programmed.

The open flows follow the [nextpnr project](https://github.com/YosysHQ/nextpnr) and [Yosys synthesis commands](https://yosyshq.readthedocs.io/projects/yosys/en/stable/using_yosys/synthesis/synth.html). The optional [OSS CAD Suite](https://github.com/YosysHQ/oss-cad-suite-build/releases) provides matching ECP5 tools and databases. The validation suite downloaded on this Mac was the official Darwin arm64 release `2026-11-04`, archive SHA-256 `ec25d6c7fe507336444a34d9cec10f9ef8c2ac03717575c7c39d90e16781f5dc`. Tool installation does not prove programming or operation on a physical board.
