# iCE40 bitstream simulation

This 32,220-byte HX1K/TQ144 bitstream was generated through the Android app and the Mac companion. Its SHA-256 is `8278fc7ae275f3885958a2d2f8a2955283dabac60f117a02b2c20176d5f60295`.

The simulation reconstructs the configured logic from the binary using IceStorm. Icarus Verilog checks the recovered named ports and physical package pins against all four input combinations of an AND gate. Inputs use pins 21 and 22; output uses pin 23.

Run from the repository root with `iceunpack`, `icebox_vlog`, `iverilog` and `vvp` available.

```sh
sample="$PWD/examples/fpga-bitstream"
work=$(mktemp -d)
iceunpack "$sample/ice40-and.bin" "$work/unpacked.asc"
icebox_vlog -d tq144 -p "$sample/pins.pcf" -n bitstream_and "$work/unpacked.asc" > "$work/recovered.v"
icebox_vlog -l -d tq144 -n physical_and "$work/unpacked.asc" > "$work/physical.v"
iverilog -g2012 -s truth_table -o "$work/check.vvp" "$work/recovered.v" "$work/physical.v" "$sample/truth-table.v"
vvp "$work/check.vvp"
```

The observed results were `00→0`, `01→0`, `10→0` and `11→1`. This checks the binary's combinational logic and pin mapping. Loading a board and checking its electrical behavior still require hardware.
