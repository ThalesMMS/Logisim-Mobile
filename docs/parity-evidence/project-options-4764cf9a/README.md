# `simlimit` cut at Evolution's step (#354), Mobile 4764cf9a

Mobile `4764cf9afdc13da60d5e5349a0e28586fd82672e` (the #354 commit, rebased on `origin/main` `1cdb02a2`), core JAR built with `./gradlew :core:jvmJar` (SHA-256 `02f8b35d15ab82f4930f21bc3761c1e95b42e23987c30bb2bff3c9371f2af7be`). Evolution `d58a61e0`, JAR `logisim-evolution-5.1.0dev-all.jar` (SHA-256 `d0b287d1695bc12f276f42b8db39af9a00e266ce0e0b53472b5aeab795a7a038`). macOS (Apple silicon), Homebrew OpenJDK 21.0.13, JVM only, 2026-11-08. Replaces the `option/simlimit` result in `options-tools-a4e57de9`, which stays as the historical record of the difference.

## Change

Evolution's `Propagator.propagate` runs `stepInternal` on iterations 1 to `simlimit - 1` and sets `isOscillating` on iteration `simlimit` without stepping. `CircuitSimulator.settle` applied the next timestamp in every round, including the last, so an oscillating circuit froze one step later. The last round now only checks whether events remain: an empty queue settles, otherwise the circuit is reported as oscillating with the same frozen state as Evolution.

## Commands

```sh
./gradlew :core:jvmJar
ORACLE_TIMEOUT=60 scripts/oracle/project-options/run.sh EVOLUTION_JAR /tmp/po354
./gradlew :core:jvmTest --tests 'dev.logisim.mobile.core.HeadlessCircuitCliTest' --tests 'dev.logisim.mobile.core.CircuitSimulatorTest'
scripts/oracle/temporal-kinds/run.sh EVOLUTION_JAR /tmp/kinds354
```

## Results

| Check | Result |
| --- | --- |
| `project-options/run.sh` ([log](logs/run.log)) | `PASS: 21 project-option fixtures match Evolution`. The 5 fixtures that differed at `a4e57de9` now match: `simlimit-ring-halt-{4,5,6,1000}` and `pulse-latch-halt-0` (Evolution and Mobile exit 1 with `detected oscillation`). |
| Race runs `simrand-race-32` (informative, 5 runs) | Both programs now halt on `detected oscillation` at random ticks; before, Mobile ended on the halt pin. `simrand-race-0`: neither halts. |
| Focused tests | `oscillatingRingStopsAtEvolutionSimulationLimitStep` (ring at limits 3, 4, 5, 6, 1000: `E 0`, `E 0`, `E 0 1`, `E 0`, `E 0`) and `bufferChainSettlesFromTheSameSimulationLimitAsEvolution` (12 buffers: `E` up to 13, settled from 14) pass, with the rest of `HeadlessCircuitCliTest` and `CircuitSimulatorTest`. |
| Corpus and temporal corpus | `oracle-replay-4764cf9a`: 222 records, 0 differences; 6 temporal fixtures, 155 records, equal in `settle` and `step`, byte-identical to `oracle-replay-9ef2232e`. |
| Temporal sweep, defaults ([TSV](logs/temporal-kinds-default.tsv)) | 160 circuits: 157 `SAME` in both modes, 3 `DESKTOP_ERROR` (known). Against `temporal-kinds/default.tsv` the only change is `blif_circuit` `VALUE` → `SAME`, from the native BLIF simulation of #335. |

Pre-fix check (same fixtures, JAR built from the previous `CircuitSimulator.kt`): ring limits 4, 6 and 1000 printed `E 0 1`, limit 5 printed `E 0`, and `pulse-latch-halt-0` exited 0 on `halted due to halt pin`.

`logs/outputs.sha256` hashes every stdout of the run; the ring, pulse and chain 13/14 outputs of both programs are kept in `logs/`.
