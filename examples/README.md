# Example circuits

The `catalog.json` catalog describes each example's files, inputs, steps, and expected results. `scripts/generate_examples.py` generates `BundledCircuitExamples.kt` from those same files. The app and tests both load the AND circuit through `CircuitExamples`.

In the app, open **Tools → Help and examples → Examples**. Read the instructions and confirm **Open a copy** to open a project in the workspace, separate from the original external file. The reference scenario runs on an independent copy and leaves your edits in the open circuit intact. The [user guide](../docs/user-guide.md) describes the controls, and the [compatibility guide](../docs/compatibility-guide.md) explains what the results cover.

| File | Capability | Expected result |
| --- | --- | --- |
| `and.circ` | Combinational logic | The output is 1 when both inputs are 1. |
| `d-flip-flop.circ` | Sequential logic | Q captures D on the rising edge and responds to the asynchronous clear. |
| `bus-adder.circ` | Eight-bit bus | `FF + 01 = 00`, with a carry of 1. |
| `hierarchy.circ` | Subcircuit | An inverter module turns 0 into 1 and 1 into 0. |
| `rom.circ` | Memory | Addresses 0 through 3 read `12`, `34`, `56`, and `78`. |
| `sap1.circ` | Hierarchical processor | The program computes `2 + 3 + 4 - 1 = 8`. |
| `corpus/bus-conflict.circ` | Diagnostics | Drivers that disagree produce errors only on the conflicting bits. |

`sap1.circ` contains the full SAP-1 computer, with seven circuits and a program that computes `2 + 3 + 4 - 1 = 8`. The [SAP-1 reference](SAP-1.md) records its source, license, and execution test.

When opening SAP-1 from Help or importing its file, select `main`. In **Signals**, toggle the `Reset` input from `0` to `1` and back to `0` before running the circuit. After 56 steps, the output register should show `08`. The SAP-1 shortcut in the library applies the pulse on load. The reference test uses the catalog's `startup` steps.

`CircCodec.readProject` imports all circuits from a file. `CircCodec.writeProject` exports the project and preserves its metadata. The `read` and `write` methods accept a single document for compatibility with the AND example.

The XML reader limits input size, element count, and nesting depth and rejects DTDs and external entities. When a read fails, it returns diagnostics and discards the partial import.

Run these commands to verify that the bundled catalog matches the source files.

```sh
python3 scripts/generate_examples.py --check
./gradlew :core:jvmTest --tests '*CircuitExamplesTest'
```

`CircuitExamplesTest` lives in `commonTest` and can also run on the iOS Simulator through the Native target. It opens every example, applies the declared stimuli, and repeats the run after exporting and reimporting each project. See the [corpus guide](../docs/corpus.md) for the independent comparison with the desktop.
