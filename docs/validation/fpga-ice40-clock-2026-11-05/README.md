# Preserve the physical clock in iCE40 project exports

The iCE40 generator validated the clock's physical pin and frequency but omitted
the frequency from `pins.pcf`. An exported project therefore lost this timing
constraint. The companion's global frequency parameter masked the omission in
its own bitstream flow.

The generator now emits `set_frequency` for each mapped clock port. The PCF
travels unchanged in the ordinary project ZIP and in companion bitstream inputs.
The [nextpnr PCF parser](https://raw.githubusercontent.com/YosysHQ/nextpnr/main/ice40/pcf.cc)
supports this command and passes its MHz value to `addClock`.

## Verification

`tests-red.log` records the new clock export test failing before the production
change. The expected PCF included the missing `set_frequency` instruction.
`tests-green.log` records all 37 FPGA JVM tests passing after the change, with no
failures, errors or skipped tests. The new case checks literal PCFs at 50 MHz and
12.288 MHz and verifies that ordinary signals receive only pin assignments.

`Ice40Clock.java` generated a sequential HX1K/TQ144 project using the compiled
mobile core. The probe extracted `pins.pcf` from its exported ZIP and compared
it byte for byte with the generated file. Yosys, nextpnr-ice40 and icepack then
built that project through the real companion handler. The request omitted
`frequency_mhz`, so the timing constraint came from the PCF itself.

`place-route.stderr.log` records nextpnr applying 12.29 MHz, its rounded display
of the PCF's 12.288 MHz. `result.json` records all three completed stages and
the hashes of the netlist, route and 32,220-byte bitstream. The output reports
`programmed=false` and `hardwareVerified=false`.

The pinned core JAR hash and source hashes are in the receipts. The JAR and
binary build products remain in the local ignored output directory rather than
this evidence package. This check does not establish electrical timing,
programming, a connected board, native app interaction or completion of #65.

## Replay

Run the focused tests from the repository root:

```sh
./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.Fpga*' :core:jvmJar
```

The Java source launcher needs that core JAR and the Kotlin standard library:

```sh
java --class-path '/path/core-jvm.jar:/path/kotlin-stdlib.jar' \
  docs/validation/fpga-ice40-clock-2026-11-05/Ice40Clock.java /tmp/ice40-clock-generated
```

Upload `user_top.v`, `logisim_fpga_top.v` and `pins.pcf` from that output directory
to the companion's `fpga-synthesize` operation with these parameters:

```json
{"flow":"ice40","part":"hx1k","package":"tq144","top":"logisim_fpga_top","sources":["user_top.v","logisim_fpga_top.v"],"language":"verilog","goal":"bitstream","constraint":"pins.pcf"}
```

The host must explicitly configure Yosys, nextpnr-ice40 and icepack. This replay
does not call `fpga-program`.
