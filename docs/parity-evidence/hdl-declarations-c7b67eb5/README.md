# VHDL declarations compared with Evolution (#348)

- Mobile: `c7b67eb5d89aa1592c98690dc2fddc74ec6c6bbf` (`core-jvm.jar` sha256 `19adc9926121c888b37fbff3590498bc23f6177fc47889d02d19e2c8e79f691a`)
- Evolution: `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d` (`logisim-evolution-5.1.0dev-all.jar` sha256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`)
- Environment: macOS 27.0.1 arm64, OpenJDK 21.0.13 (`scripts/java-home.sh`), headless, in-memory preferences. JVM only.

## Command

```sh
./gradlew :core:jvmJar
python3 scripts/oracle/integrations/run.py EVOLUTION_JAR --out docs/parity-evidence/hdl-declarations-c7b67eb5 --only hdl-declarations
```

`HdlDeclarationOracle.java` reads each source file with Evolution’s `VhdlParser`/`BlifParser` and Mobile’s `HdlIpDeclarations`. It compares the entity, port names, directions and widths, and generic defaults.

## Result

`PASS: 18/26 HDL declarations match Evolution, 8 accepted only by Mobile, 0 differ` ([log](logs/hdl-declarations.txt)).

- In `evolution_generics.vhd`, `MODE : integer` now defaults to 0 (1 for `positive`), as in Evolution. Both produce `{mode=0, offset=31, start=3, step=3, width=8}`.
- In `evolution_time_generic.vhd`, `DELAY : time := 5 ns` is 5000000 (fs) in both. The units `fs`, `ps`, `ns`, and `us` follow Evolution’s 32-bit conversion. The generics map and companion request write `5000000 fs`, as does `VhdlHdlGeneratorFactory`.
- Mobile previously rejected both entities (`missing-generic`, `unsupported-generic`), as recorded in campaign #331’s `formats-integrations-a4e57de9` receipt.

The 8 sources accepted only by Mobile are the same dialect extensions recorded in the campaign.

Focused tests `./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.HdlIp*'` (`HdlIpTest.numericGenericsWithoutValueAndTimeGenericsFollowEvolution`) e `python3 -m unittest companion.tests.test_hdl_sim` (37 tests, including `test_time_generic_in_femtoseconds_sets_the_delay`, which simulates a `time` generic in NVC).
