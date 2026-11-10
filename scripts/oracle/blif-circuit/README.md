# Compare BLIF circuits with Evolution

`run.sh` compares the `BLIFCircuit` component of Mobile and Evolution `d58a61e0` on a
corpus that exercises the whole parser subset of `blif.templ`:

```sh
./gradlew :core:jvmJar
scripts/oracle/blif-circuit/run.sh /path/to/logisim-evolution-all.jar /tmp/blif-circuit
```

`make-fixtures.py` writes one circuit per case, with the BLIF source in the component's
`content` attribute and a Pin on each port. Inputs change with a fixed seed, and some changes
include unknown (`x`) and error (`E`) bits. The cases cover every gate type, `BUF`, `NOT`,
`.conn` and the `$true`/`$false`/`$undef` constants, `MUX`, tristate buses with contention,
several drivers on one net, `PULLUP`/`PULLDOWN`, a `DFF` counter, `DLATCH`, `DFFSR`, a
cross-coupled NAND latch, bus ports and a name listed in `.inputs` and `.outputs`, an 8-bit
bus, the template, and a `.names` truth table that Evolution rejects.

The Evolution side is `scripts/oracle/TemporalDesktopOracle.java`; the Mobile side is
`TemporalCorpusCli` in `settle` and `step` modes. `summary.tsv` lists both modes, with the
results of the temporal sweep (`SAME`, `TIME`, `VALUE`, `DESKTOP_ERROR`, `MOBILE_ERROR`).
`ResaveDesktop` then writes each circuit as Evolution saves it, and `roundtrip.tsv` compares
Mobile on that file with the Evolution trace of the original.

Evolution cannot save the truth-table case without a display: its parse error opens a dialog.
That row is `DESKTOP_ERROR` in `roundtrip.tsv`. A source that oscillates by itself, such as
`.subckt NOT A=y Y=y`, is not in the corpus. Evolution's oracle stops at its step limit, while
the Mobile trace rejects oscillation, so the two programs cannot be compared on it.

The receipt is `docs/parity-evidence/blif-circuit/receipt.json`, refreshed with
`python3 scripts/parity_matrix.py reverify blif-circuit --evolution-jar JAR`. That command also
repeats the temporal sweep suites for the `blif_circuit` fixtures only.
