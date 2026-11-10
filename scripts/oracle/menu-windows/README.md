# Print, FPGA Commander and the assembly window against Evolution

`run.sh EVOLUTION_JAR [assembly] [print] [fpga]` compares three menu actions of Logisim Evolution `d58a61e0`, whose effect only shows in a Swing window or on a printer, with Mobile (#343). Build the Mobile core first (`./gradlew :core:jvmJar`). The FPGA section also needs Icarus Verilog (`iverilog`, `vvp`). Evolution's preferences stay in memory (`MemoryPreferences`).

## `MenuSimulate/assemblyWindow`: `AssemblyWindowOracle`

Evolution's `AssemblyWindow` is built (never shown) on `fixtures/assembly-counters.circ`, and the listing `fixtures/firmware.lss` is loaded with `JEditorPane.setPage`, as File > Open .lss does. After each clock half-period the oracle selects every register in the window's list, calls its `updateHighlightLine`, and reads the line of the gray highlight and the status bar. Mobile runs the same circuit in `CircuitSimulator` and asks the core `AssemblyListing` and `assemblyRegisters` that `AssemblyViewerTool` shows.

The circuit has counters of 8, 12, 16 and 32 bits with different steps, an unlabeled register and a register with a floating input. The listing is in `avr-objdump` style with CRLF, LF and CR line ends and every case of Evolution's pattern `^ +<hex>:` (upper case, tab indentation, leading zeros, no indentation, a space before the colon, repeated addresses, labels, missing addresses).

Accepted difference: when the register value is undefined, Evolution keeps the previous highlight; Mobile removes it and says the value is undefined. Evolution also refreshes the highlight only on Refresh, on a new register choice or while ticks are enabled; Mobile follows the value after every simulation step.

## `MenuFile/print`: `PrintLayoutOracle`

Evolution's `Print.MyPrintable` prints on `new PageFormat()` (Letter, one-inch margins) through a recording `Graphics2D` (`sun.print.ProxyGraphics2D`). Mobile's `VisualPdf.encodePages` encodes the same page (`VisualPdfPage(612, 792, 72)`) with a drawing of Evolution's bounds expanded by 4 and a header raster of Evolution's header size at Mobile's two pixels per point. For every page, with three header templates and rotation on and off, the oracle maps the corners of the circuit bounds and of the header line to page points on both sides. The fixture `print-pages.circ` has an empty circuit, circuits smaller than the page, wide and tall ones, one near each side of the rotation threshold and a very large one. It also compares the list of printable circuits and Evolution's private `Print.format` with `PrintHeader.expand` on 60,033 templates.

The circuit itself is not drawn: Evolution reaches for the project window's canvas only to draw it, and that drawing is the one compared by File > Export Image. Evolution rounds the header's left edge to a whole point (offset up to 0.5 pt).

Accepted differences: Mobile prints on A4 with 24 pt margins (the system print dialog scales to the paper), Evolution on Letter with one-inch margins; the header uses the platform font at 12 pt, Evolution the Java default font; Mobile shrinks a header wider than the page, Evolution lets it run off the page.

## `MenuFpga/FPGACommander`: `fpga_commander.py` and `FpgaCommanderOracle`

For BASYS3 (Vivado), BASYS2 (ISE), TERASIC_DE0 (Quartus) and GECKO5_EDUCATION (open ECP5), the script writes one circuit with a saved board map: two-bit AND and NOT and a one-bit OR, mapped on buttons, switches (active low on GECKO5), LEDs and generic pins, with one input bit tied high and one output bit left open. Evolution generates Verilog and constraints with `Download(..., generateHdlOnly = true)`, as the commander's "Generate HDL only" and `--test-fpga ... HDLONLY` do. Mobile generates its archive with `HeadlessCircuitCli --test-fpga ... HDLONLY`.

The generators name and structure the files differently, so the oracle checks what reaches the board: the constraints place the same physical pins with the same I/O standard and pull mode, and Icarus simulates both top levels over every combination of the input pins with the same values on every output pin. Changing the tied constant or dropping an active-low inversion in Mobile's top level makes 64 of the 128 vectors differ.

Not covered: clocked designs (tick generator), seven-segment and other multi-pin resources, synthesis and programming (vendor tools and boards are out of scope).
