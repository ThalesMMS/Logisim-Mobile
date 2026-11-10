# SoC debugger and RV32 and Nios II assemblers compared with Evolution (#353)

- Mobile: `c7b67eb5d89aa1592c98690dc2fddc74ec6c6bbf` (`core-jvm.jar` SHA-256 `19adc9926121c888b37fbff3590498bc23f6177fc47889d02d19e2c8e79f691a`)
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (`logisim-evolution-5.1.0dev-all.jar` SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS 27.0.1 arm64, OpenJDK 21.0.13 (`scripts/java-home.sh`), AWT without a visible window (`apple.awt.UIElement`). JVM only.

## Command

```sh
./gradlew :core:jvmJar
scripts/oracle/soc-debugger/run.sh EVOLUTION_JAR docs/parity-evidence/soc-debugger-c7b67eb5/logs
```

## Result

`SoC debugger: 28 of 28 comparisons equal` ([results](logs/soc-debugger-results.json), [log](logs/soc-debugger.txt)).

- RV32 and Nios II match on ELF loading, registers across 22 steps, trace (PC, word, and text byte for byte), breakpoints, and resumes.
- RV32 trace text matches `getAsmInstruction()` (for example, `li        ra,0` and `blt       sp,gp,pc-8`). The scan matched byte for byte across 48,056 words covering all opcodes, random fields, and pseudo-instructions.
- The RV32 assembler covered 83 instructions, one per case. Both produced the same words for 73, including `jalr rd, rs1, imm`, `not`, `jr`, `seqz`, and `snez`, and both rejected 10. Seven source files covered `.text`/`.data`/`.rodata`/`.bss`/`.section`, `.org`, `.zero`, `.byte`…`.dword`, `.ascii`/`.string`, `.equ`, left-to-right arithmetic, `pc+8`, and CSRs by name.
- The new Nios II assembler covered 121 instructions, one per case, and four source files. Both produced the same words for 111 and rejected 10. This includes `movia`, `cmpgt`/`cmple`…, `bgt`/`ble`…, `custom`, and `rdctl`/`wrctl`.

Mobile now matches Evolution’s rejection of `jalr x0, 0(x1)`, `fence`, `fp`, `0b101`, uppercase, `//`, and `.globl`, which it previously accepted.

- `flushp` has no operand. The Evolution unit reuses rA from its previous instruction (session state). Mobile encodes rA = 0, as a fresh Evolution session does. The oracle compares `flushp` first.
- Evolution sections store only nonzero bytes and pad with zeros. The oracle compares memory contents (nonzero words).

Evolution’s `.macro` and `.endm` were outside the acceptance criteria for this run and were still pending in [#357](https://github.com/ThalesMMS/logisim-mobile-dev/issues/357).

The earlier #331 campaign matched 15 of 19 comparisons (`telnet-soc-debugger-a4e57de9` receipt).

The focused run `./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.Rv32*' --tests 'dev.logisim.mobile.core.Soc*'` had no failures (`Rv32AssemblerTest` 5, `Rv32CpuTest` 11).
