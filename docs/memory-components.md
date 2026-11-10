# Memory components

`MemoryComponents.definitions()` provides ROM, RAM, and Dual RAM definitions for the shared component registry. `MemoryComponents.DualRam` is the nominal kind whose file name is `DualRAM`. The definitions use the desktop `#Memory` library.

`MemoryImage` stores immutable sparse words. Addresses use 2 to 24 bits and data words use 1 to 64 bits. ROM contents belong to the document's `contents` attribute. RAM contents belong to `MemoryComponentState.runtime.image`; simulation does not write them into document attributes.

## Supported behavior

- Single, dual, quad, and octal lines, with optional misaligned access.
- Shared, separate, and controlled separate data buses.
- Byte write masks, including a partial high byte.
- Rising, falling, high, and low triggers.
- Synchronous and asynchronous reads.
- Read-before-write and read-after-write modes.
- Clear priority and volatile/nonvolatile reset behavior.
- Independent dual-port clocks, writes, and output registers.

Dual RAM applies port A before port B. If both ports write the same address in one evaluation, port B determines the stored word and a diagnostic reports the collision. Each port's output follows its own read-before/write-after setting and the desktop's port order.

Unknown addresses do not write memory. Error addresses produce error outputs; undefined addresses produce undefined outputs. The desktop converts an undefined data word to all one bits when writing. This implementation preserves that behavior.

The current desktop Dual RAM low-level byte-write path never enables a write, while single-port RAM writes when write-enable is low. The runtime oracle records this compatibility behavior instead of silently changing it.

## Verification

Run the common tests with `./gradlew :core:jvmTest --tests '*Memory*Test'`. Run the independent desktop comparisons with:

```sh
./scripts/oracle/memory-runtime/run.sh /path/to/logisim-evolution-all.jar
./scripts/oracle/memory-images/run.sh /path/to/logisim-evolution-all.jar
```

`MemorySimulationTest` runs the definitions through `CircuitSimulator`, including a ROM file round trip, document content changes, clocked writes, byte masks, shared-bus release, dual-port collisions, session edits, and volatile/nonvolatile reset. Collision and rejected-edit diagnostics remain visible in the final simulation result.

The runtime oracle compares geometry and propagation for 696 configurations. The image oracle compares all 11 formats exposed by the desktop memory editor. Neither check measures analog hardware timing.

## Applying RAM edits

Send `MemoryCellsChanged` or `MemoryImageReplaced` as a component event to the selected runtime instance. Invalid addresses, values, or dimensions reject that event without changing the image. Use a document edit to change ROM contents. RAM edit events reject ROM targets.

A short line-enabled RAM can retain words beyond its declared depth in simulation, as in the Evolution backing page. These words stay outside the visible and exported memory image. An ordinary edit, image replacement, or change of line mode preserves them. Growing the address width brings any retained words within the new depth into the visible image. A resize limits an allocated short page to the new depth, so later line writes cannot extend it. Clearing a nonempty page removes that limit. An explicit Clear sends `MemoryImageReplaced` with `clearLineOverflow` set and clears the retained words along with the visible contents.

Loading a file copies only the declared addresses. A zero-only source that left its page unallocated can release a destination page when the copy clears its last word. A RAW batch can allocate a page even when every word is zero, in which case the copy keeps the destination page allocated.

Evolution RAM stores words in signed types of 8, 16, 32 or 64 bits. Changing the data width copies the stored word, then masks it for display and export. Reducing a word's width can leave hidden high bits in simulation, and increasing the width can expose them again. An edit that changes a visible value replaces its stored word. Loading an image replaces the stored words in the declared address range.
