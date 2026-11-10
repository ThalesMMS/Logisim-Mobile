# Memory runtime oracle

Run `./scripts/oracle/memory-runtime/run.sh /path/to/logisim-evolution-all.jar` from the repository.

The runner sends the same generated inputs to the desktop component implementation and the common Kotlin memory implementation. It compares terminal positions, widths, directions, bounds, and settled output values. Results are written to `outputs/memory-oracle/`.

Cases cover ROM, RAM and Dual RAM, all three appearance identifiers, shared and separate buses, byte and line enables, rising and falling edges, level triggers, read-before-write and read-after-write, disabled outputs, clear, unknown and erroneous addresses, and data widths of 1, 8, 20 and 64 bits.

The desktop raises a GUI warning before evaluating simultaneous Dual RAM writes at equal addresses. That path cannot run headlessly. The common tests separately verify the documented port-order result: port A writes first and port B writes last. Unknown addresses are injected into one Dual RAM port at a time so the desktop warning does not interrupt the oracle.

The implementation follows desktop behavior for these cases:

- Writing an undefined data word stores all one bits, as `Value.toLongValue()` returns `-1`.
- Disabled outputs on an uncontrolled separate byte bus retain their previous value.
- Dual RAM applies its two ports in order. With read-before-write, the second port can observe the first port's write.
- The current Dual RAM low-level byte-write path never enables a write. The single-port RAM low-level path writes while write-enable is low.

Source reference: `std/memory/Rom.java`, `Ram.java`, `DualRam.java`, `RamAppearance.java`, `DualRamAppearance.java` and `ClockState.java` in the pinned desktop source tree. These observations describe digital simulation; they do not model analog memory timing.
