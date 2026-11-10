# TTL model validation

`TtlComponents.definitions` exports the 85 device types in the desktop `TtlLibrary` catalog. `TtlLogic` evaluates physical, one-based pin numbers. The package geometry and pin labels come from metadata extracted from the desktop JAR.

## Evidence

- 36 JVM tests cover catalog, geometry, registry definitions, combinational behavior, sequential transitions, and all 85 per-model fixtures.
- The desktop oracle checks 444,000 combinational output values and delays, plus 95,232 sequential output values and delays. Both comparisons completed with zero mismatches.
- The sequential oracle supplies output feedback between steps and injects a deterministic clock for 74123.
- `scripts/oracle/ttl/run.sh` reproduces the differential checks against a supplied desktop JAR.

## Model rules

Power terminals are hidden unless `VccGndPorts=true`. An invalid explicit supply releases normal outputs, as in the desktop implementation. Bidirectional ports retain the previously driven level where the desktop leaves them untouched. Unknown and error inputs follow each model, including Boolean-only desktop models that treat a non-high input as low.

The 74123 exposes `1Rext`, `1Cext`, `2Rext`, and `2Cext`, and requests its next simulation wakeup. The simulator must pass elapsed active propagation time, freezing it while propagation is paused. The 74299 data terminals are bidirectional in the mobile layout so the register can sample external signals and release its shared bus.

`ShowInternalStructure` changes the package display to a white body with signal labels. The runtime does not model analog voltage thresholds or electrical drive strength.

The active catalog includes `74283`. It does not register `7483`; these part numbers are not pin-compatible aliases.

## Catalog

| Model | Pins | GND | VCC | Output pins | Bidirectional pins |
| --- | ---: | ---: | ---: | --- | --- |
| 7400 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7402 | 14 | 7 | 14 | 1,4,10,13 | None |
| 7404 | 14 | 7 | 14 | 2,4,6,8,10,12 | None |
| 7405 | 14 | 7 | 14 | 2,4,6,8,10,12 | None |
| 7408 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7410 | 14 | 7 | 14 | 6,8,12 | None |
| 7411 | 14 | 7 | 14 | 6,8,12 | None |
| 7413 | 14 | 7 | 14 | 6,8 | None |
| 7414 | 14 | 7 | 14 | 2,4,6,8,10,12 | None |
| 7418 | 14 | 7 | 14 | 6,8 | None |
| 7419 | 14 | 7 | 14 | 2,4,6,8,10,12 | None |
| 7420 | 14 | 7 | 14 | 6,8 | None |
| 7421 | 14 | 7 | 14 | 6,8 | None |
| 7424 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7427 | 14 | 7 | 14 | 6,8,12 | None |
| 7430 | 14 | 7 | 14 | 8 | None |
| 7432 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7434 | 14 | 7 | 14 | 2,4,6,8,10,12 | None |
| 7436 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7438 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7442 | 16 | 8 | 16 | 1,2,3,4,5,6,7,9,10,11 | None |
| 7443 | 16 | 8 | 16 | 1,2,3,4,5,6,7,9,10,11 | None |
| 7444 | 16 | 8 | 16 | 1,2,3,4,5,6,7,9,10,11 | None |
| 7447 | 16 | 8 | 16 | 9,10,11,12,13,14,15 | 4 |
| 7451 | 14 | 7 | 14 | 6,8 | None |
| 7454 | 14 | 7 | 14 | 8 | None |
| 7458 | 14 | 7 | 14 | 6,8 | None |
| 7464 | 14 | 7 | 14 | 8 | None |
| 7474 | 14 | 7 | 14 | 5,6,8,9 | None |
| 7476 | 16 | 8 | 16 | 10,11,13,14 | None |
| 7485 | 16 | 8 | 16 | 5,6,7 | None |
| 7486 | 14 | 7 | 14 | 3,6,8,11 | None |
| 7487 | 14 | 7 | 14 | 3,6,9,12 | None |
| 7493 | 14 | 10 | 5 | 8,9,11,12 | None |
| 74107 | 14 | 7 | 14 | 2,3,5,6 | None |
| 74123 | 16 | 8 | 16 | 4,5,12,13 | None |
| 74125 | 14 | 7 | 14 | 3,6,8,11 | None |
| 74132 | 14 | 7 | 14 | 3,6,8,11 | None |
| 74138 | 16 | 8 | 16 | 7,9,10,11,12,13,14,15 | None |
| 74139 | 16 | 8 | 16 | 4,5,6,7,9,10,11,12 | None |
| 74148 | 16 | 8 | 16 | 6,7,9,14,15 | None |
| 74151 | 16 | 8 | 16 | 5,6 | None |
| 74153 | 16 | 8 | 16 | 7,9 | None |
| 74157 | 16 | 8 | 16 | 4,7,9,12 | None |
| 74158 | 16 | 8 | 16 | 4,7,9,12 | None |
| 74161 | 16 | 8 | 16 | 11,12,13,14,15 | None |
| 74163 | 16 | 8 | 16 | 11,12,13,14,15 | None |
| 74164 | 14 | 7 | 14 | 3,4,5,6,10,11,12,13 | None |
| 74165 | 16 | 8 | 16 | 7,9 | None |
| 74166 | 16 | 8 | 16 | 13 | None |
| 74173 | 16 | 8 | 16 | 3,4,5,6 | None |
| 74175 | 16 | 8 | 16 | 2,3,6,7,10,11,14,15 | None |
| 74181 | 24 | 12 | 24 | 9,10,11,13,14,15,16,17 | None |
| 74182 | 16 | 8 | 16 | 7,9,10,11,12 | None |
| 74190 | 16 | 8 | 16 | 2,3,6,7,12,13 | None |
| 74192 | 16 | 8 | 16 | 2,3,6,7,12,13 | None |
| 74193 | 16 | 8 | 16 | 2,3,6,7,12,13 | None |
| 74194 | 16 | 8 | 16 | 12,13,14,15 | None |
| 74240 | 20 | 10 | 20 | 3,5,7,9,12,14,16,18 | None |
| 74241 | 20 | 10 | 20 | 3,5,7,9,12,14,16,18 | None |
| 74244 | 20 | 10 | 20 | 3,5,7,9,12,14,16,18 | None |
| 74245 | 20 | 10 | 20 | None | 2,3,4,5,6,7,8,9,11,12,13,14,15,16,17,18 |
| 74257 | 16 | 8 | 16 | 4,7,9,12 | None |
| 74259 | 16 | 8 | 16 | 4,5,6,7,9,10,11,12 | None |
| 74266 | 14 | 7 | 14 | 3,6,8,11 | None |
| 74273 | 20 | 10 | 20 | 2,5,6,9,12,15,16,19 | None |
| 74283 | 16 | 8 | 16 | 1,4,9,10,13 | None |
| 74299 | 20 | 10 | 20 | 4,5,6,7,8,13,14,15,16,17 | None |
| 74374 | 20 | 10 | 20 | 2,5,6,9,12,15,16,19 | None |
| 74365 | 16 | 8 | 16 | 3,5,7,9,11,13 | None |
| 74377 | 20 | 10 | 20 | 2,5,6,9,12,15,16,19 | None |
| 74381 | 20 | 10 | 20 | 8,9,11,12,13,14 | None |
| 74393 | 14 | 7 | 14 | 3,4,5,6,8,9,10,11 | None |
| 74390 | 16 | 8 | 16 | 3,5,6,7,9,10,11,13 | None |
| 74541 | 20 | 10 | 20 | 11,12,13,14,15,16,17,18 | None |
| 74594 | 16 | 8 | 16 | 1,2,3,4,5,6,7,9,15 | None |
| 74595 | 16 | 8 | 16 | 1,2,3,4,5,6,7,9,15 | None |
| 74640 | 20 | 10 | 20 | None | 2,3,4,5,6,7,8,9,11,12,13,14,15,16,17,18 |
| 74670 | 16 | 8 | 16 | 6,7,9,10 | None |
| 74688 | 20 | 10 | 20 | 19 | None |
| 744017 | 16 | 8 | 16 | 1,2,3,4,5,6,7,9,10,11,12 | None |
| 744060 | 16 | 8 | 16 | 1,2,3,4,5,6,7,13,14,15 | None |
| 744094 | 16 | 8 | 16 | 4,5,6,7,9,10,11,12,13,14 | None |
| 744075 | 14 | 7 | 14 | 6,9,10 | None |
| 747266 | 14 | 7 | 14 | 3,6,8,11 | None |
