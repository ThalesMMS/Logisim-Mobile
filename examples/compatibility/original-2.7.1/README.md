# Logisim 2.7.1 corpus

The circuits were prepared for this test. `provenance.json` records the hashes of the [official Logisim 2.7.1 JAR](https://sourceforge.net/projects/circuit/files/2.7.x/2.7.1/), the projects, and the stimuli. The oracle uses the original JAR's API to measure the circuits without opening the UI.

- `inverter.circ` verifies combinational logic when the input changes.
- `clock.circ` verifies that the high and low phases each last two ticks.
- `latch.circ` verifies state retention in an SR latch built from cross-coupled NOR gates.
- `hierarchy.circ` verifies Classic gates and propagation through a subcircuit.
- `rom.circ` verifies the positions of the three ROM terminals and the `SEL`-controlled read of `0xA5`.

Run the comparison using the `logisim-generic-2.7.1.jar` file from the release page.

```sh
python3 scripts/verify-corpus.py --original-jar /path/to/logisim-generic-2.7.1.jar
```

The runner compares the JAR's results against the tracked `.original.tsv` records. The same sequence exercises the Mobile engine before and after export and reimport. The records include the main circuit, terminal geometry, wires, selected attributes, and settled values. The summary lists the migrations applied by the Mobile importer. `original_export_compatibility` records whether 2.7.1 itself can reopen the exported file.

<a name="execução-registrada"></a>

## Recorded run

The Mobile replay at [`54e77c08`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/54e77c08cededdc3c188579959a17873e98cb36f) against the official JAR produced the results below. The JAR's SHA-256 is in the [provenance manifest](provenance.json).

| Circuit | Records | Differences in Mobile before and after reimport | Differences when reopened in 2.7.1 |
| --- | ---: | ---: | ---: |
| Clock | 15 | 0 | 0 |
| Subcircuit | 25 | 0 | 0 |
| Inverter | 16 | 0 | 0 |
| SR latch | 33 | 0 | 0 |
| ROM | 16 | 0 | 0 |
| Total | 105 | 0 | 0 |

The run covers the stimuli, structure, and attributes sampled in these five files. The [Evolution parity matrix](../../../docs/parity.md) uses a separate reference for Mobile.

The [legacy-original-open-edit-simulate-reopen](../../../fixtures/user-scenarios/legacy-original-actions-v1.json) scenario uses `inverter.circ` to verify editing, simulation, and reopening a real export through the UI. The JVM runner exercises the engine independently of the Android and iOS interfaces.

<a name="casos-da-frente-de-motor-e-componentes"></a>

### Simulation engine and component cases

The fixes in [Epic #247](https://github.com/ThalesMMS/logisim-mobile-dev/issues/247) have separate results from each issue's verification runs. The seven cases below cover the 2.7.1 profile. The `simrand` test uses Evolution as its reference and appears in the [validation record](../../../docs/validation.md#propagation-noise-in-the-evolution-profile).

| Case | Fix on `main` | Recorded result |
| --- | --- | --- |
| Controlled Buffer and Inverter | [`e413baef`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/e413baef7d815deecc84b0fa969c7d0e574010d0) | 49 JVM tests passed. Eight outputs matched the 2.7.1 JAR, including after reopening the export. A floating control gave `E`, a low control gave `?`, and a high control gave `1` for Buffer and `0` for Inverter. [#136](https://github.com/ThalesMMS/logisim-mobile-dev/issues/136#issuecomment-6676286457) |
| N and P transistors | [`ab8e830b`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/ab8e830b741524a9af0de64f8e1d5410eeced561) | 29 JVM tests passed using an artifact whose sources were verified byte for byte. Six cases matched the 2.7.1 JAR, including after export and reopening. A new isolated build timed out. [#144](https://github.com/ThalesMMS/logisim-mobile-dev/issues/144#issuecomment-6676290044) |
| Random with a zero seed | [`c052f12f`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/c052f12f75cacc182db19b2f33ff263739de4a7d) | 34 JVM tests passed. Two runs generated fresh seeds and followed the 2.7.1 JAR's recurrence. The tests covered reset, fixed seeds, and deterministic mode. [#153](https://github.com/ThalesMMS/logisim-mobile-dev/issues/153#issuecomment-6676323791) |
| DotMatrix off color | [`1a741cbd`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/1a741cbdea157e4aad27b42477599cdea5d981ee) | 18 JVM tests passed. The fixture that omitted `offcolor` produced `#808080` when on and `#404040` when off in both the original JAR and Mobile. The tests covered reopening, explicit colors, and the modern profile. [#156](https://github.com/ThalesMMS/logisim-mobile-dev/issues/156#issuecomment-6676324844) |
| Propagation limit | [`2cd60d48`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/2cd60d48bc0f02ed9ac2e42b5d59cffb186624f2) | 30 JVM tests passed. The Mobile CLI settled the chain of 299 inverters at `Y=1` using the implicit limit in the 2.7.1 and Evolution `5.1.0dev` profiles. The explicit value `256` triggered a diagnostic, and the CLI still detected unstable feedback. [#157](https://github.com/ThalesMMS/logisim-mobile-dev/issues/157#issuecomment-6676326035) |
| TTY cursor | [`104c96e2`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/104c96e24c5dfd85d9f1b531290af4458b365d78) | A raster test passed on a simulated iPhone 17e running iOS 27.0. A line break moved the cursor down 15 px, a space moved it horizontally, and print output omitted it. [#158](https://github.com/ThalesMMS/logisim-mobile-dev/issues/158#issuecomment-6676807829) |
| Button and LED labels | [`ff1af6aa`](https://github.com/ThalesMMS/logisim-mobile-dev/commit/ff1af6aa5bdecc40ade4a68ec4ab17c664f8217b) | 26 JVM tests passed. The 2.7.1 oracle and the Mobile renderer primitives centered labels when `labelloc` was omitted or explicitly set to `center`. The run used renderer measurements on the JVM. [#159](https://github.com/ThalesMMS/logisim-mobile-dev/issues/159#issuecomment-6676395189) |

<a name="casos-da-frente-de-arquivos-e-projetos"></a>

### File and project cases

These results come from each issue's verification runs. See the [file and library validation record](../../../docs/validation.md#files-projects-and-libraries) for the remaining cases.

| Case | Fix on `main` | Recorded result |
| --- | --- | --- |
| Omitted or empty local library | `3f4fb963` | 24 codec tests passed on the JVM. Both variants produced the same canonical `.circ` and `Y=1,0,1`, including after reopening and in the `--sub` table. The official 2.7.1 JAR produced the same output. [#137](https://github.com/ThalesMMS/logisim-mobile-dev/issues/137#issuecomment-6676287637) |
| Missing version | `bbdfb122` | 38 JVM tests passed. The CLI rejected the ROM without `source` before simulation. Explicitly selecting `2.7.1` preserved `10100101` and `????????`. Explicitly selecting a modern version preserved the modern interpretation. [#148](https://github.com/ThalesMMS/logisim-mobile-dev/issues/148#issuecomment-6676351815) |
| Modern multiplier export | `64472e03` | On Android 15, the 2.7.1 export rejected a modern 32-bit multiplier before sharing. Save As wrote the native `.circ` to Downloads with identical XML. [#149](https://github.com/ThalesMMS/logisim-mobile-dev/issues/149#issuecomment-6682261653) |
| Large projects | `bdf36f38` | 15 JVM tests passed. Mobile imported, exported, and reopened the 2.7.1 and Evolution variants, each with 3,400 inverters and 3,400 wires. The tests confirmed the existing protections for input size, nesting depth, and external entities. [#150](https://github.com/ThalesMMS/logisim-mobile-dev/issues/150#issuecomment-6676322625) |
| Windows-1252 XML | `6653578a`, `83b00b21` | The JVM tests confirmed the label `Saída €` after importing CP1252 and reopening in UTF-8, and confirmed rejection of undefined bytes. The CLI gained a separate test for `Saída€`, the header observed in the 2.7.1 JAR. [#161 fix](https://github.com/ThalesMMS/logisim-mobile-dev/issues/161#issuecomment-6676327256) · [Additional verification](https://github.com/ThalesMMS/logisim-mobile-dev/issues/161#issuecomment-6676549866) |
| Import from before 2.6.3 | `862109cc` | On Android 15, the report showed conversion from 2.0.3 to 2.7.1. The hierarchy retained `LegacyHierarchy` and `Invert`, with `Y=1/0`. The export reopened, and rejection of a truncated file preserved the active project and its source bytes. [#222](https://github.com/ThalesMMS/logisim-mobile-dev/issues/222#issuecomment-6683932933) |
