# SoC assembler macros (RV32 and Nios II) compared with Evolution (#357)

- Mobile: `f0f47b6fb940fbe2509ddada1442f2c89a0f21bb` (`core-jvm.jar` SHA-256 `680bd30c8afa66c32dd042299f3b7586bf06e21731d98b46f9ed22b2ec641748`)
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (`logisim-evolution-5.1.0dev-all.jar` SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS 27.0.1 arm64, OpenJDK 21.0.13 (`scripts/java-home.sh`), AWT without a visible window (`apple.awt.UIElement`). JVM only.

## Command

```sh
./gradlew :core:jvmJar
scripts/oracle/soc-debugger/run.sh EVOLUTION_JAR docs/parity-evidence/soc-debugger-f0f47b6f/logs
```

## Result

`SoC debugger: 82 of 82 comparisons equal` ([results](logs/soc-debugger-results.json), [log](logs/soc-debugger.txt), [run log](logs/soc-debugger-run.txt)).

The 28 comparisons in the `soc-debugger-c7b67eb5` receipt (#353) still match. The new run adds 54 source files with macros, 45 for RV32 and 9 for Nios II.

- Both assemblers emit the same words for 21 files (16 RV32, 5 Nios II). These cover parameters `@1`…`@N`, local labels referenced backward and forward in each expansion, nested macro calls, use before definition, global labels and labels passed as parameters, 8-byte `li` and `la`, macros in `.data`, hexadecimal parameter counts, redefinition (last definition wins), unused macros with invalid bodies, and Nios II `movia` and `call`.
- Both reject 33 files. For each, the oracle requires Mobile to report an error on a line Evolution flags, with matching messages for macro keys. Cases include incomplete definitions (missing name, count, `.endm`, or premature EOF), directives or `.macro` inside a macro, undefined parameters (`@3`, `@0`), wrong parameter counts, empty parameters, direct and mutual recursion, a local label used outside its macro, a local label repeated in two macros, a macro named after a label, a stray `.endm`, and `@1` outside a macro.
- The oracle also covers Evolution-specific behavior. The `AssemblerToken` constructor copies the body, so a copied hexadecimal literal loses its label flag and `lui` or `auipc` throws `NullPointerException` in Evolution (Mobile rejects the input). A label copied from the body loses its label status for `lui`, while a label passed as a parameter retains it. Both uses share the same parameter token (`pc+8`), causing the second use to be rejected. `@1+4` loses the `+` in the fourth pass. A lone `@` throws `NumberFormatException` in Evolution (Mobile rejects it).

Evolution reports all errors it finds. Mobile stops at the first, which always matches an error reported by Evolution in this corpus.

The focused run `./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.SocAssembler*' --tests 'dev.logisim.mobile.core.Rv32*' --tests 'dev.logisim.mobile.core.Nios*' --tests 'dev.logisim.mobile.core.Soc*' --tests '*Locali*'` had no failures (`SocAssemblerMacroTest` 5, `Rv32AssemblerTest` 5). `python3 scripts/test-localization.py` reported OK.
