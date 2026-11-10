# HDL export

`HdlExporter.export(project, language, registry)` produces synthesizable Verilog or VHDL-2008 for the reachable hierarchy of the project's main circuit. Each result contains the top module name, dependency-first source files, top-level ports with their original circuit/component IDs, and diagnostics with circuit/component/terminal references. Any error suppresses **all** source files, so a failed dependency cannot leave an apparently usable partial design.

The HDL editor tool selects a language and source file, exposes native save/share callbacks, copies source text, and navigates diagnostic origins. Save every listed file in one directory and compile them in the displayed order. Top-level pins, clocks, and buttons become vector ports, including one-bit ports. PortIO pin banks also become external ports, with separate banks for each instance of a child circuit. Interactive clocks do not become autonomous RTL oscillators.

## Representation

The exporter uses `CircuitNetworks.bitNetworks()` for wires, junctions, splitters, and tunnels. Every bit has its own internal scalar net, including buses up to 64 bits. It rejects undriven output pins, missing required inputs, multiple strong drivers (including distinct outputs of one component), except that controlled buffers and inverters can share a resolved net with other tri-state drivers or a single strong driver, missing child circuits/appearance ports, recursive instances, and invalid component configurations.

Names are deterministic ASCII identifiers with namespace prefixes. Reserved words, Unicode, punctuation, case-insensitive collisions, and repeated child instances are handled without changing the source model. Child circuits are emitted once, and each instance retains its own sequential state. Defaults combine the registered schema, component defaults, imported implicit attributes, and explicit attributes.

## Component profile

| Family | Exported configurations |
| --- | --- |
| Wiring | Pins, constants, power/ground, tunnels, splitters, bit extension with zero/one/sign/input fill |
| Gates | AND/OR/XOR/NAND/NOR/XNOR/NOT/buffer, input inversion, bus widths, odd/even parity; XOR supports exactly-one and odd-parity modes |
| Tri-state I/O | Controlled buffers and inverters drive high impedance when disabled. PortIO exports input-only, output-only, or resolved bidirectional buses with a shared enable or one enable per bit. FPGA mappings use the same bit order as the exported ports. |
| TTL | 7400 (NAND), 7402 (NOR), 7404 (inverter), 7408 (AND), 7432 (OR), 7486 (XOR), and 7474 (dual D flip-flop) with hidden power pins. Unconnected logic inputs default to low in HDL. |
| Plexers | Multiplexer, demultiplexer, decoder, bit selector, priority encoder, binary selection and nonfloating disabled outputs |
| Arithmetic | Adder/subtractor with carry/borrow, negator, absolute with overflow, signed/unsigned comparison and min/max, signed/unsigned multiplication with carry, logical/arithmetic shifts and rotations, bit finder in all four search modes, signed/unsigned exponentiation, integer square root with remainder |
| Sequential | D/T/JK flip-flops, register, counter; rising/falling edges, high/low level triggers, asynchronous clear/preset, enable/load/direction, counter limit modes, shift register with serial or parallel load, and seeded xoshiro256++ Random |
| ROM | One address port and one data line, sparse zero-filled contents decoded by the shared memory image codec |
| RAM | Synchronous reads and writes on either clock edge, separate data buses, byte mode, and read-before-write or read-after-write. Clear is unsupported. A connected output enable requires byte enables. |
| Line-enable RAM | Separate data buses with one, two, four or eight consecutive words. Each enabled line writes on the configured edge. Reads follow the address asynchronously. |
| VHDL HDL-IP | Embedded source for a self-contained entity with scalar or vector input and output ports. VHDL target only. |
| Observation | Probes, supported display widgets, and text are omitted with a warning |

Unsupported components and unsupported attribute combinations produce explicit errors. This includes DualRAM, RAM modes outside the listed profile, mixed signed/unsigned multiplication, transistors, transmission gates, pull resistors, open-collector/open-emitter gates, floating disabled outputs, divider and bit adder, floating point, TTL with exposed power pins, TTL chips outside the listed profile, BLIF and Tcl components, and active I/O peripherals. S-R export is conservative: one input must currently be unconnected (its documented neutral value is low); a wired constant also receives a conservative diagnostic. Unrestricted simultaneous set/reset has no defined binary RTL value. ROM export permits at most 65,536 nonzero words.

When exporting VHDL, the HDL tool lists embedded HDL-IP source files before the generated circuits. The app copies the source locally without running it. Instances that share identical source use one file and keep their own generic values and port mappings. Different source for the same case-insensitive entity name, bidirectional HDL-IP ports, and VHDL HDL-IP in a Verilog export produce diagnostics. Check the implementation with a VHDL compiler after saving all the files.

The 7474 HDL has two independent rising-edge clocks and active-low asynchronous clear and preset. Clear takes priority if both controls are asserted. If preset is still asserted when clear is released, Q sets without a clock edge. The circuit simulators report an unknown output while both controls are asserted. The pinned Evolution VHDL generator connects the second output pair to the first state and declares the second clock as a vector. Mobile follows the physical pin assignment and the independent halves in the Evolution simulator. [Evolution simulator](https://github.com/logisim-evolution/logisim-evolution/blob/d58a61e0c290361722b43df5b1a6b86c1f7c7f0d/src/main/java/com/cburch/logisim/std/ttl/Ttl7474.java#L101) · [Evolution HDL generator](https://github.com/logisim-evolution/logisim-evolution/blob/d58a61e0c290361722b43df5b1a6b86c1f7c7f0d/src/main/java/com/cburch/logisim/std/ttl/Ttl7474HdlGenerator.java#L24)

With RAM byte enables, BE0 controls the least significant eight bits. The last enable controls the remaining bits if the word ends with a partial byte. Each exported output slice updates on the selected clock edge while its BE and OE are high, as in the Evolution HDL component. The circuit simulator reads the full word when OE is high and uses the byte enables for writes. Disconnected BE and OE inputs are low in the masked HDL profile.

The equivalence domain is settled, **defined binary behavior** with high-impedance outputs on the supported tri-state profiles. The HDL simulator resolves external and internal PortIO drivers. Simulator unknown/error propagation, gate delays, hazards, physical reset timing, metastability, and FPGA clock/pin constraints are outside this generator. Reset and preset priority is preserved. Sequential initial values are emitted, but whether hardware honors initialization depends on the synthesis target. Priority-encoder data is zero when no input is asserted, where the circuit simulator can expose an unknown/don't-care address. Use its valid/present output to qualify the data. Random follows the Evolution HDL reset sequence. Its output is unspecified until the first active clock edge, and two recovery edges follow power-up or clear before it advances. Hardware exports use seed `0x5DEECE66D` when the configured seed is zero.

For line-enable RAM, a disallowed unaligned address blocks writes and returns unknown bits in HDL simulation. The portable HDL returns zero and discards writes for lines beyond the last word. The Mobile simulator keeps those words in a short RAM's backing page without including them in the memory image. Mobile uses the circuit clock directly. The pinned Evolution FPGA generator uses a separate FPGA clock and tick pipeline, with up to nine FPGA cycles before outputs settle and different behavior for gated clocks. In Verilog and VHDL simulation of the generated Evolution module, a second-line write at address 0xF in a four-bit bank changed word zero. The Evolution circuit simulator kept the same write in the backing page, leaving word zero unchanged. Compare stable in-range outputs using the configured clock edge. [Evolution line-enable RAM generator](https://github.com/logisim-evolution/logisim-evolution/blob/d58a61e0c290361722b43df5b1a6b86c1f7c7f0d/src/main/java/com/cburch/logisim/std/memory/RamHdlGeneratorFactory.java#L419-L450) · [Evolution backing memory](https://github.com/logisim-evolution/logisim-evolution/blob/d58a61e0c290361722b43df5b1a6b86c1f7c7f0d/src/main/java/com/cburch/logisim/std/memory/MemContents.java#L257-L270)

## Reproduce validation

Install Icarus Verilog (`iverilog` and `vvp`) and NVC, then run:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :core:jvmTest --tests '*HdlExportTest'
ANDROID_HOME="$HOME/Library/Android/sdk" ./scripts/verify-hdl-export.sh
```

The first command checks literal vectors against the actual circuit simulator, deterministic output, origin diagnostics, Portuguese/English diagnostic notices, hierarchy, and naming. The second also runs those tests, then generates separate Verilog/VHDL designs and testbenches from those fixture vectors, invokes the external compilers and simulators, and writes `outputs/hdl-export-validation/result.json`. Missing tools or failed compilation/simulation fail the command. `tool-output.log`, source `.circ` files, and SHA-256 hashes make the run inspectable. The expected results are literal fixture values or independently calculated integer results; they are not derived from the emitted RTL.

The fixture set covers 64-bit carry propagation, 128-bit multiplication results and a 64-bit counter limit, all 256 splitter values, ROM contents, asynchronous reset/preset, falling-edge enable, counter wrap/load/down, independent hierarchical state, exactly-one XOR with inversion, signed comparison, signed/unsigned multiplication with carry, subtractor borrow, arithmetic/rotate shifts, multiplexers/demultiplexers/decoders with enable, padded bit selection, valid priority encoding, decimal constants, and counter loads above the limit. This is sampled equivalence coverage, not a proof over every accepted circuit.

The recorded run is in [hdl-export-validation.json](hdl-export-validation.json): 12 Kotlin tests, 52 independently compiled/simulated designs, and 3,076 literal output comparisons.

NVC is the VHDL-2008 validation backend on this Mac. The Homebrew GHDL cask was disabled; the workflow does not change Gatekeeper settings or bypass that restriction.
