# Simulate generated FPGA wrappers

Build the mobile core, then run with JDK 21 or newer, Icarus Verilog, and NVC:

```sh
./gradlew :core:jvmJar
./scripts/oracle/fpga-build/run.sh
```

The Java source launcher imports the checked-in GECKO5 definition through
`FpgaBoardCodec` and calls the compiled `FpgaProjectGenerator` for Verilog and
VHDL. Icarus compiles and executes each generated Verilog wrapper. NVC analyzes,
elaborates, and runs each generated VHDL wrapper with `--std=08`.

The combinational design computes logical `y = &a`. Inputs `a[0]` and `a[1]`
map to GECKO5 DIP-switch pins D19 and D17. Output `y` maps to D14. All three
pins are active low, so each bench requires physical `y = a0 | a1` for all
four binary input patterns.

The second design drives or reads a two-bit bidirectional bus. It maps
`drive[0:1]` to C1 and C2, `enable` to B2, `io[0:1]` to C3 and C4, and
`sampled[0:1]` to D5 and C5. All are active-high GPIO. Each bench checks four
FPGA-driven values, four externally driven values, both drivers released
with `ZZ`, and conflicting values resolved to `XX`. The distinct `01` and
`10` patterns check bit order.

The benches obtain wrapper port names from the generated plan, and the
launcher also verifies their emitted LPF pin locations against the requested
physical bindings. It writes all generated project files and benches into a
temporary directory that the runner removes on exit, including after failure.
The runner prints the hashes of the mobile JAR and XML fixture.

The runner emits a JSON result after all four simulations finish. It reads
the success markers and case counts from each simulator's log, rejects a
missing or unexpected result, and obtains tool versions from the executables.
The mobile JAR is copied before execution so its reported hash identifies
the artifact loaded by the launcher even if another build starts.

The verified run used Java 21.0.11, Icarus Verilog 13.0, and NVC 1.23.1.
It executed these cases:

| Design | Verilog | VHDL | Total |
| --- | ---: | ---: | ---: |
| Combinational physical input states | 4 | 4 | 8 |
| Bidirectional drive, receive, release, and conflict | 10 | 10 | 20 |
| Total | 14 | 14 | 28 |

The first argument overrides `core/build/libs/core-jvm.jar`. Use
`KOTLIN_STDLIB_JAR`, `IVERILOG_BIN`, `VVP_BIN`, or `NVC_BIN` to select tools
outside the default paths. A configured `JAVA_HOME` selects Java.

By default, this check executes generated HDL behavior without synthesis.
Set `LOGISIM_FPGA_BITSTREAM=1` to additionally pass the generated combinational
`user_top.v`, `logisim_fpga_top.v`, and unchanged `pins.lpf` to
`companion.fpga.synthesize`:

```sh
LOGISIM_FPGA_BITSTREAM=1 ./scripts/oracle/fpga-build/run.sh
```

The optional build runs Yosys synthesis, nextpnr ECP5 place and route for
`85k/CABGA381`, and ecppack. It checks the returned stages and actual artifact
files, including a nonempty bitstream and the routed device identifier.
The final JSON includes artifact sizes and hashes, tool versions, the exact
generated LPF, and any warnings. A failed stage emits the companion diagnostic
and returns a nonzero status. All build files remain temporary.

`YOSYS_BIN`, `NEXTPNR_ECP5`, and `ECPPACK` override synthesis tools. The defaults
prefer Homebrew Yosys and the official suite installed at
`outputs/toolchains/oss-cad-suite-fpga/oss-cad-suite/bin`, then search `PATH`.
Neither mode programs hardware or claims hardware verification.

The verified optional run used Yosys `0.70+post`, nextpnr
`0.11.1-86-g64c09988`, and Project Trellis ecppack `1.4-88-g2d12005`.
The generated LPF included `BLOCK RESETPATHS`, `BLOCK ASYNCPATHS`, pull-ups
and LVCMOS18 on D19 and D17, and LVCMOS33 with 4 mA drive on D14. The build
accepted that file unchanged and produced a 279,931-byte `design.bit` with
SHA-256 `b982d4c4812618c49ed0f6436f2453b7aa8a5a125a0db34a85f413a196c8d8b1`.
The recorded warnings came from Yosys's internal ABC files and combinational
network handling. nextpnr emitted no LPF warnings. The JSON reported
`programmed=false` and `hardwareVerified=false`.
