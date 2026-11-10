# Verify the examples against desktop

The corpus uses desktop revision `d58a61e0c290361722b43df5b1a6b86c1f7c7f0d`, the same reference as the parity matrix. The `.circ` files, stimuli, and expected results are in `examples/catalog.json`. Desktop records preserved in `examples/corpus/reference/` include per-bit values and terminal positions, widths, and directions.

The runner checks three paths for each file.

1. Desktop opens the original file and applies the stimuli.
2. The shared engine opens the same file and performs the same actions.
3. Desktop opens the mobile export and repeats the actions.

The desktop records must also match the checked-in references. Missing, extra, and differing records are errors. The comparator checks the full set of outputs. Bits at `0`, `1`, unknown, and error are compared individually so partially known nibbles retain their bit-level distinctions.

## Run

Use JDK 21 and a JAR built from the pinned desktop revision.

```sh
python3 scripts/generate_examples.py --check
python3 scripts/verify-corpus.py --desktop-jar /path/to/logisim-evolution-5.1.0dev-all.jar
```

The command first verifies that the embedded Kotlin catalog matches the current files and stimuli, then builds the core JVM JAR. Use `--skip-build` only if that artifact already matches the current sources. Set `KOTLIN_STDLIB_JAR` to use a Kotlin library outside the Gradle cache. The runner works without opening the desktop UI.

Each directory under `outputs/corpus-validation/` contains the stimuli, the three records, and the exported `.circ`. `summary.json` records the revision, JAR hashes, and differences by example, action, probe, or component. The runner rejects a JAR whose `BuildInfo.branchLastCommitHash` differs from the declared revision.

## Recorded scope

The seven examples cover AND with known and unknown inputs, flip-flop capture and retention, eight-bit carry, a subcircuit, ROM reads, SAP-1, and conflicting driver resolution. The initial corpus contains 222 records per run, comprising seven circuit counts, 182 components with their terminal sets, and 33 signal observations. The three paths agreed in the reference JVM run.

The three tests in `CircuitExamplesTest` under `commonTest` exercise the same stimuli and results without Java dependencies. The integrator runs the Native target to verify execution on iOS Simulator. UI, physical devices, and equivalence of all internal desktop events require separate testing.

The `scripts/verify.sh` flow and Python tests also reject drift between the checked-in files and the two embedded corpora. This gate runs only Python. Differential comparison of the JARs runs desktop separately.

Example titles are localizable. The circuit and input identifiers used by the runner are stable ASCII because desktop changes invalid HDL identifiers by adding a UUID suffix. The comparator preserves those changes in arbitrary files.

## Update a reference

First review the changes to the files and expected results. Then run the update command.

```sh
python3 scripts/verify-corpus.py --desktop-jar /path/to/logisim-evolution-5.1.0dev-all.jar --update-reference
```

This command replaces references only after all expected results agree with both engines and with desktop reopening the export. `provenance.json` preserves the input file hashes, desktop JAR hash, and revision used. A normal run preserves the expected results.

## Extend a family

Add the `.circ` to the catalog, declare inputs and probes, and use literal results. Generate the Kotlin catalog and run `CircuitExamplesTest` and the differential runner. New components need registered geometry and behavior before they can appear in a working app example.

The desktop call `driveInputPin` must be followed by `markComponentAsDirty`, as in `TestVectorEvaluator`. Changing the intended value alone does not schedule propagation. The checked-in runners follow this rule before propagating each group of inputs.

Timestamped feedback, simultaneous capture and RAM propagation are covered by the separate [temporal oracle](temporal-oracle.md).
