# Menu actions compared with Evolution again after #348

- Mobile: `c7b67eb5d89aa1592c98690dc2fddc74ec6c6bbf` (`core-jvm.jar` sha256 `19adc9926121c888b37fbff3590498bc23f6177fc47889d02d19e2c8e79f691a`)
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (`logisim-evolution-5.1.0dev-all.jar` sha256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS 27.0.1 arm64, OpenJDK 21.0.13, Android host tests.

## Command

```sh
scripts/oracle/menus/run.sh EVOLUTION_JAR OUT
```

## Result

The result matches the [`menus-a6c86b17`](../menus-a6c86b17/README.md) receipt, with 53 `SAME` comparisons and 6 accepted `DIFF` comparisons. The board editor and Verilog import both returned `SAME`. See [compare.txt](logs/compare.txt).

The comparison ran again because #348 changed `HdlSimulationCompanion.kt` to write `time` generics in fs in the companion request. The `MenuSimulate/simulateVhdlEnable` row remains an explained difference. Evolution enables global VHDL simulation in QuestaSim (out of scope), while Mobile simulates each component through the companion.
