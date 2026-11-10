# SoC runtime and processor inspection

The SoC module registers the eight desktop factories from the `#Soc` library. `SocComponents.definitions()` supplies the `.circ` identities, attribute parsing and absolute pin geometry. Each circuit instance owns its device snapshots, processor registers, interrupt controller and bus trace. No CPU, memory or peripheral state is global.

`SocComponentBehavior` opts into the engine's peer-state contract. A transaction completes synchronously against the current instance before the next component evaluates. Two masters at one timestamp execute in document order. Changed peers are evaluated again at that timestamp so a PIO drawn before its CPU still presents the new electrical output. External step and load events are consumed once.

The supported devices are sparse little-endian memory, PIO input/output/bidirectional ports and edge capture, JTAG UART FIFOs and electrical keyboard/TTY handshakes, DMA with separate source/destination buses and fixed-address modes, VGA control and framebuffer snooping, and the RV32 single-context PLIC. The bus retains the desktop error IDs and 10,000 transaction entries. Hidden DMA reads and explicit framebuffer refreshes do not enter the visible trace.

Copying a SoC fragment gives copied buses new identifiers and remaps references to buses in that fragment. References to external buses stay unchanged. Duplicate imported bus identifiers produce a diagnostic rather than a host exception.

## CPUs and executable loading

The RV32 core implements the desktop RV32I, M and Zicsr instruction families, machine external interrupt entry, implemented CSRs and `MRET`. The Nios II core implements the desktop integer, immediate, shift, rotate, comparison, load/store and I/O forms, control transfers, control registers, exception/interrupt entry and returns. Custom Nios instructions expose their request ports and wait for `DONE`; no custom result is invented.

ELF loading accepts executable ELF32 little-endian files for machine 243, RV32, or 113, Nios II. It loads `PT_LOAD` segments at their physical addresses and zero-fills BSS. Headers, segment bounds, architecture and target RAM ranges are validated before loading, with a 64 MiB total limit. The entry point survives a processor reset. A failed load leaves RAM unchanged.

`createSocDebuggerTool(DocumentAssetActions)` opens ELF files through the platform binary document picker. The panel exposes step, clock-driven run/stop, registers, control registers, memory, bus read/write transactions and instruction/bus traces for the selected circuit instance. The callback overload also allows another host to supply executable bytes. Open `fixtures/soc/program-rv32.circ` or `program-nios2.circ`, then load the matching `sum-rv32.elf` or `sum-nios2.elf` and execute 22 steps to reproduce the captured result. `scripts/build-soc-demo-elf.py` rebuilds these executables. Controls scroll on narrow screens; circuit drawings retain the desktop geometry.

To assemble RV32 source, open `program-rv32.circ`, select its CPU in SoC / CPUs and open the RV32 assembler. Enter `addi x5, x0, 7`, choose **Assemble and load**, then choose **Step**. Register x5 reads `00000007`. The assembler writes one contiguous text section starting at the selected CPU's reset address. It supports labels, integer and machine instructions, and reports invalid source with the line number. Nios II programs can be loaded as ELF.

The renderer reads snapshots and never advances a CPU or DMA engine. VGA writes update the frame through bus snooping. Reloading the whole frame from memory is an explicit event.

## Desktop evidence and defined differences

The oracle scripts execute the local desktop JAR directly. ALU fixtures cover 288 RV32 cases and 388 Nios II arithmetic/control cases. Both complete programs sum 1 through 5, write 15 to RAM and PIO, and load 15 back into a register. The captured 22 snapshots per CPU include PC, sum, loop counter, loaded data, RAM and PIO. Mobile tests compare every snapshot through both the isolated evaluator and the real event simulator.

The reference is source commit `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01` and local `logisim-evolution-5.1.0dev-all.jar`, SHA-256 `0f5af2768ae804c79923e942929e19e5e6ff5e9f125fa4c88ecfb09239e09c01`. The eight-component fixture was loaded by that JAR to verify bounds, pin order, widths and directions.

Several desktop behaviors are preserved deliberately: unaligned RAM accesses select the containing word/half-word; Nios `MULI` returns the desktop's high product; the desktop decoder chooses DIV for the shared DIV/CMPNE encoding; RV environment decoding precedes CSR decoding. ECALL, EBREAK, fences and Nios cache/synchronization instructions retain the desktop model's execution behavior, with non-modal notices for unsupported environment handling.

RV32 division by zero returns the ISA-defined result instead of throwing the desktop's host arithmetic exception. Nios division by zero and unsupported control-register writes halt with a diagnostic instead of throwing a host exception. Uninitialized RAM uses a per-device deterministic Java-compatible random stream. Tests initialize program/data memory explicitly. Processor history keeps up to 256 entries, and the canvas shows the most recent 21 like the desktop register/trace view.

## Verification

Use the shared Gradle lock while other worktrees are active:

```sh
ANDROID_HOME=/Users/thales/Library/Android/sdk python3 /tmp/logisim-gradle-serial.py ./gradlew \
  :core:jvmTest --tests 'dev.logisim.mobile.core.Soc*Test' \
  --tests 'dev.logisim.mobile.core.Rv32*Test' --tests 'dev.logisim.mobile.core.Nios2CpuTest' \
  :composeApp:compileAndroidMain -Pkotlin.incremental=false --console=plain
```

The tests cover MMIO permissions and address errors, byte/half-word access, DMA bursts and first-error retry, interrupt masks and claim/complete, UART signals, framebuffer snooping, ELF rejection/BSS, retained entry points, copy/paste references, two masters, hierarchy isolation, codec round trips and bilingual diagnostics. Physical device interaction belongs to the final platform qualification.
