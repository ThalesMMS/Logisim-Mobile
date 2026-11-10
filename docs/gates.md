# Gates library

`GatesLibrary.definitions()` registers the 13 desktop entries (NOT, Buffer, AND, OR, NAND, NOR, XOR, XNOR, Odd Parity, Even Parity, Controlled Buffer, Controlled Inverter, and PLA).

The definitions share typed attributes, named ports, bounds, and evaluation with a one-unit delay. Gates with multiple inputs accept 2 to 64 inputs and widths of 1 to 64 bits. Each input can be negated independently. Supported sizes are 30, 50, and 70. NOT and Controlled Inverter use 20 or 30.

The `out` attribute accepts `01`, `0Z`, and `Z1`. The `gateUndefined` project option controls whether a gate ignores disconnected inputs or produces an error. Buffer and Controlled Buffer retain their own policies for undefined bits. In strict mode, a Buffer or NOT with a fully defined result returns before applying `out`, matching the order of `Buffer.repair` on desktop.

PLA uses the first matching row. Tables accept both compact and `|`-delimited formats. Expanding the dimensions preserves the low bits and adds zeros. As on desktop, any undefined or error bit in the input produces an all-ones lookup.

## Verification

`GatesLibraryTest` checks truth tables, per-bit resolution, disconnected inputs, control, negations, rotation, and PLA. `GatesDesktopParityTest` compares 312 external cases with desktop factory results, covering outputs, delays, port positions, widths and directions, and drawing bounds.

Regenerate the corpus with `scripts/oracle/gates/generate.sh`. The reference is desktop revision `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01`.

## Compatibility for negations above 30

This desktop revision uses an `int` shift when changing `negate31` through `negate63`. For example, setting `negate63=true` also enables negation on inputs 31 to 62. The mobile model uses independent flags for all 64 inputs. This desktop mask defect falls outside the differential corpus's equivalence claims.
