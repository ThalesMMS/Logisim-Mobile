# Temporal sweep by component

`run.sh` compares event times in Evolution and Mobile for every catalog component that has separate input and output terminals.
`TemporalKindSweep` writes one circuit per component. The component has its default attributes, with one Pin on each terminal. In each of the three actions, every input changes three times, and the queue is drained after each change. Because of that, the times also show events that keep the queue busy without changing any value.

```sh
./gradlew :core:jvmJar
scripts/oracle/temporal-kinds/run.sh /path/to/logisim-evolution-all.jar /tmp/temporal-kinds
```

The Evolution side uses `scripts/oracle/TemporalDesktopOracle.java`. The Mobile side uses `TemporalCorpusCli`, in `settle` mode and again in `step` mode. Each line of `summary.tsv` gives one of these results for each mode:

| Result | Meaning |
| --- | --- |
| `SAME` | The records match byte for byte |
| `TIME` | Values and order match, but the times differ |
| `VALUE` | At least one value differs |
| `DESKTOP_ERROR` / `MOBILE_ERROR` | The engine did not finish the operations |

`--variants` first runs `AttributeOptionsDesktop`, which lists the finite values of each desktop attribute. Those are options, Boolean values, widths from 1 to 64 and integer ranges. The sweep then writes one circuit per value, changing one attribute from the default. Cosmetic attributes such as `facing` and the label attributes are left out. Values that Mobile rejects go to `fixtures/rejected.txt`.

`--negative` also puts unknown (`x`) and error (`E`) bits in the inputs. `--pairs N` replaces the single changes with up to N combinations of two different attributes for each component. The combinations are drawn with a fixed seed per component. Telnet fixtures run one at a time, because they listen on the same TCP ports.

```sh
scripts/oracle/temporal-kinds/run.sh /path/to/logisim-evolution-all.jar /tmp/kinds
scripts/oracle/temporal-kinds/run.sh --variants /path/to/logisim-evolution-all.jar /tmp/variants
scripts/oracle/temporal-kinds/run.sh --variants --negative /path/to/logisim-evolution-all.jar /tmp/negative
scripts/oracle/temporal-kinds/run.sh --pairs 6 /path/to/logisim-evolution-all.jar /tmp/pairs
```

Results with Evolution `d58a61e0`, in `docs/parity-evidence/temporal-kinds/`:

| Mode | Circuits | `SAME` in both modes |
| --- | ---: | ---: |
| Defaults | 160 | 156 |
| `--variants` | 984 | 967 |
| `--variants --negative` | 984 | 971 |
| `--pairs 6` | 556 | 540 |

Every other result has a known cause:

| Circuits | Result | Cause |
| --- | --- | --- |
| Exponentiator, Reptar Local Bus, VHDL entity, DualRAM with a HIGH trigger and error inputs | `DESKTOP_ERROR` | `BigInteger` overflow, no simulation, QuestaSim required, and a dialog that fails in headless mode |
| PLA with `out_width` or `in_width` | `DESKTOP_ERROR` | The generated file changes a width without table rows. Evolution takes the widths from the table, so it opens these files as 2×2. Mobile does the same, and its probe widths then no longer match. |
| BLIF without a source file | `VALUE` | Mobile co-simulated BLIF through the companion. Since #335 it simulates BLIF natively; `docs/parity-evidence/blif-circuit` repeats these suites for BLIF with `SAME` |
| Random with seed 0 in the Evolution appearances | `VALUE` | Evolution seeds from `System.currentTimeMillis()` |
| 74123 | `VALUE` | Evolution measures the pulse with `System.nanoTime()` |

Rejected value: the hidden `LedBar.matrixrows` attribute.

`FIXTURES=REGEX` keeps only the matching fixture IDs, for example
`FIXTURES='blif_circuit(__.*)?' scripts/oracle/temporal-kinds/run.sh --variants JAR /tmp/blif`.

## Round trip

`ResaveDesktop.java` opens each generated circuit in Evolution and writes the desktop serialization to `<id>.desktop.circ`. Running `TemporalCorpusCli` on that file and comparing the output with the Evolution trace of the original checks the Mobile → Evolution → Mobile round trip:

```sh
javac -cp EVOLUTION_JAR -d classes scripts/oracle/temporal-kinds/ResaveDesktop.java
java -Djava.awt.headless=true -cp classes:EVOLUTION_JAR ResaveDesktop /tmp/variants/fixtures
```

With `--variants`, Evolution re-saved 801 of the 984 circuits. Its writer opens an AWT window for TTL chips, which fails in headless mode. Telnet was left out. Of the 790 compared, 775 match the original trace byte for byte. The other 15 are cases that already differed before the round trip.
