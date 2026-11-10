# Wiring components

`WiringLibrary.definitions()` supplies the desktop `#Wiring` file identities to a `ComponentRegistry`. The registry contains 15 definitions for 14 desktop tools because input and output pins have separate runtime identities.

| Desktop tool | Electrical behavior | Main attributes |
| --- | --- | --- |
| Splitter | Connects assigned bus bits to branch bits in both directions | `incoming`, `fanout`, `bitN`, `spacing`, `appear`, `facing` |
| Pin | Drives an input or observes an output | `type`, `width`, `behavior`, `initial`, `radix`, `appearance`, `facing` |
| Probe | Observes the network and infers its width | `radix`, `appearance`, `facing` |
| Tunnel | Joins terminals with the same nonempty label | `width`, `label`, `facing` |
| Pull Resistor | Supplies a weak zero, one, or error to undriven bits | `pull`, `facing` |
| Clock | Produces low and high phases measured in half-cycle ticks | `highDuration`, `lowDuration`, `phaseOffset`, `appearance`, `facing` |
| POR | Changes once after an elapsed-time deadline; an interaction can restart the pulse | `PorHighDuration`, `porTransition`, `porsize`, `facing` |
| Constant | Drives an integer value | `width`, `value`, `facing` |
| Power | Drives all bits high | `width`, `facing` |
| Ground | Drives all bits low | `width`, `facing` |
| NoConnect | Marks an intentionally unconnected terminal without driving it | `width` |
| Transistor | Passes an input through a P-type or N-type transistor | `type`, `width`, `selloc`, `facing` |
| Transmission Gate | Passes the input when its P and N controls enable it | `width`, `selloc`, `facing` |
| Bit Extender | Truncates or extends with zero, one, the sign bit, or a control input | `in_width`, `out_width`, `type` |

The stored names are `POR` and `NoConnect`. `initial` and `value` use the desktop `HexLongAttribute` format: values are serialized with `0x`; input without a prefix is decimal, `0x` selects hexadecimal, `0b` selects binary, and a leading zero selects octal. A leading minus sign is parsed before the radix prefix. Bare hexadecimal letters are invalid. Unknown visual and mapping attributes remain in the original attribute map.

Input pin `behavior=tristate` starts floating. `pullup` and `pulldown` replace unknown bits in the intended pin value before driving the network. These are strong pin values, unlike a Pull Resistor. Neither operation replaces error bits.

POR uses the elapsed milliseconds supplied by the caller. It does not derive seconds from clock ticks or propagation delays. The runtime returns the next deadline so the host can wake the simulator even when the circuit has no Clock component. The transient deadline is not written into `.circ` files.

The Probe electrical terminal has inferred width. Its body can be measured for a resolved value through `WiringLibrary.probeLayout`. Pin and Clock use the explicit file appearance. The legacy desktop setting that chooses new pin shapes does not rewrite an imported appearance.

Use `WiringLibrary.reconfigure` when changing a Constant width or a Splitter fanout or incoming width. The helper sign-extends Constant values. It preserves existing Splitter assignments when the fanout changes and redistributes them when the incoming width changes, matching the desktop attribute editor.

## Verify against the desktop

Build the JVM core, then run:

```sh
./gradlew :core:jvmTest --tests '*Wiring*Test' --console=plain
sh scripts/verify-wiring-oracle.sh /path/to/logisim-evolution-all.jar
```

The oracle calls the actual desktop component propagation methods and compares transistor, transmission-gate, and extension values. It also compares terminal positions, widths, directions, and bounds across Wiring attributes and orientations. POR deadline tests use an injected clock so the assertions do not wait for wall-clock seconds.

The constant/pin integer parser is checked against the original desktop JAR with 43 literal assertions, including radix prefixes, unsigned 64-bit limits, signed minimum, invalid input, and default sign extension:

```sh
./scripts/oracle/wiring/constant-attributes.sh /path/to/logisim-evolution.jar
```

The oracle runs headless with in-memory preferences. `WiringLibraryTest.constantAndPinInitialValuesMatchDesktopHexLongParsing` checks the same numeric rules through the mobile components' actual behavior. `HdlExportTest` retains the decimal constant fixture (`value="10"` produces decimal 10) and both external HDL simulators validate its emitted output.
