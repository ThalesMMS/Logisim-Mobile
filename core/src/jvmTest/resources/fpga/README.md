# Desktop board fixtures

`GECKO5_EDUCATION.xml` and `BASYS3.xml` are unmodified copies of
`src/main/resources/resources/logisim/boards/` in Logisim-evolution at commit
`fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01`. They retain their compressed board
pictures and remain covered by the repository's GPLv3 license.

| File | SHA-256 |
| --- | --- |
| `GECKO5_EDUCATION.xml` | `f338b7f119713ac121fde3f05ae4a0d7edc97d3ac0226cc15251ac03daa66ffb` |
| `BASYS3.xml` | `f112fdc3e7c47daff7a339fb757c620450b214934654177e6a6189b6e976deaf` |

`FpgaBoardFixtureTest` checks device and clock metadata, physical pin counts,
directions, electrical settings, and selected resource attributes. GECKO5 has
80 physical pins across 30 resources. Its scanning display uses 11 external
pins, and its LED array uses 34. These counts describe package connections,
not the displays' logical pixels. BASYS3 has 100 pins across 100 resources.

Run the fixture tests with the rest of the JVM core tests:

```sh
./gradlew :core:jvmTest --tests dev.logisim.mobile.core.FpgaBoardFixtureTest
```

The differential runner in `scripts/oracle/fpga-boards/` additionally imports
the same files through desktop `BoardReaderClass` and the compiled mobile core.
The checks do not synthesize a design, detect a board, or program hardware.

`ram-mixed-byte.circ` and `ram-mixed-line.circ` were generated for issue #300.
Each has one `mem` subcircuit with a RAM. Instance `a` receives a dedicated `Clock`.
Instance `b` receives its clock from the BASYS3 input mapped to V2. All ports have
one bit, so every port can be mapped to a button or an LED.
`HeadlessFpgaTest` checks that the VHDL output uses one entity, a boolean generic
and the two `generate` branches.

`ram-line-shared-bus.circ` was generated for issue #303. It has a quad line RAM
with a shared data bus and 1-bit data. The RAM uses a dedicated `Clock` and has
16 addresses. Evolution connects `dataIn` and `dataOut` to the same bus. The
FPGA export makes the same connection, while the portable exporter still rejects
this profile.

`reptar-clock.circ` was generated for issue #219. It has a Reptar Local Bus and
a dedicated `Clock` that drives a T flip-flop. The flip-flop output controls
the bus tri-state. The saved REPTAR_SPARTAN_6 map binds the three LocalBus
banks. The export must use the E16 oscillator once, both for the bus project
and for the synthesized tick. `fixtures/hdl/reptar/clock-tick-test.vhd` simulates
that ZIP with a 25 MHz tick.
