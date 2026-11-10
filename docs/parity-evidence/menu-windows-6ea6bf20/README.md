# Print, FPGA Commander and assembly window against Evolution d58a61e0 (2026-11-08)

Issue #343. Runs on macOS (Apple silicon) with Homebrew OpenJDK 21.0.13 and Icarus Verilog 13.0, from a clean worktree at `6ea6bf20b271d8e7d49add01e3b79b47eb0cb6fe`.

| Artifact | SHA-256 |
| --- | --- |
| Mobile core JAR (`./gradlew :core:jvmJar` at `6ea6bf20`) | `fd86ff771d45f272ead68bebb6053d85d77ffd52714dbc732d426fb298899ce3` |
| Desktop JAR (`logisim-evolution-5.1.0dev-all.jar`, built from `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`) | `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038` |

Command: `scripts/oracle/menu-windows/run.sh EVOLUTION_JAR` (exit 0). [Log](logs/menu-windows.txt), with the home directory replaced by `~` and temporary paths by `TMP`. The method and fixtures are described in [`scripts/oracle/menu-windows/README.md`](../../../scripts/oracle/menu-windows/README.md).

| Entry | Result |
| --- | --- |
| `MenuSimulate/assemblyWindow` | Same register list (`ACC, ADDR, BIG, PC, UND`: labeled registers sorted by label). 389 highlighted lines (or "line not found") equal over 97 states of 4 counters (8, 12, 16 and 32 bits). 96 states of the register with a floating input: Evolution keeps the previous highlight, Mobile shows that the value is undefined (accepted). |
| `MenuFile/print` | Same printable circuits in the same order; `Print.format` equals `PrintHeader.expand` on 60,033 templates; 48 pages (8 circuits, 3 headers, rotation on and off) with the same scale, rotation and position of the circuit bounds (within 1e-6 pt) and of the header line (within 0.5 pt: Evolution rounds its left edge to a whole point), on Evolution's Letter page with one-inch margins. |
| `MenuFpga/FPGACommander` | BASYS3 (Vivado XDC), BASYS2 (ISE UCF), TERASIC_DE0 (Quartus TCL/QSF) and GECKO5_EDUCATION (ECP5 LPF): the same 11 physical pins with the same I/O standard and pull mode, and the same values on the 4 output pins for all 128 input combinations (16 distinct output patterns per board, active-low switches and a tied-high input bit included). A tied constant flipped, or an active-low inversion dropped, in Mobile's top level changes 64 of 128 vectors. |

Mismatches fixed in `6ea6bf20` (found while building this comparison): Mobile's PDF used an empty header, no rotation, the export margin and a 4 pt gap below the header, and the PDF printed by Print could be the single-page "fit to page" image; the assembly viewer matched tab-indented lines, unindented lines and addresses with leading zeros, and offered unlabeled registers.

These runs are JVM comparisons. They do not cover the Android or iOS print dialogs, the drawing of the circuit on the page (compared by File > Export Image), FPGA synthesis or programming, or clocked FPGA designs.
