# Historical corpus functional replay

This unit prepares an identified functional replay for issue #48. The original preparation was followed by one real PASS on producer `29942df3`, documented in the [separate receipt](../performance-evidence/historical-replay-29942df3/README.md). It records no performance measurements and produces no CSV, JFR, or new circuit XML. It does not requalify the historical `bb63d681` timings, Native execution, or device behavior.

The isolated branch starts at `8c79c7eb6ee2921aac383031426f18d3a302503c`. `PerformanceCorpusReplay.kt` is a JVM test entry point. `scripts/benchmark-circuits.py --workload replay` stages its inputs and retains the runner's source, classpath, runtime, and environment identity checks.

## Pinned inputs

The corpus manifest is `/Users/thales/GitHub/logisim-mobile-worktrees/performance/outputs/performance/mobile-corpus-v2-complete/manifest.json`. Its length is 82,126 bytes and its SHA-256 is `295571568422db33a280e9c0a52b322982cfa7f57c10e297fd70a788d7596f58`.

The corpus directory contains that manifest and exactly ten XML files. The manifest pins each file's byte length and SHA-256. The accepted cases are the `independent` and `hierarchical` banks at 16, 64, 256, and 384. Both 1024-bank cases retain their exact rejected XML and must report `error_xml_structure`.

The SAP input is `examples/sap1.circ`. Its length is 47,528 bytes and its SHA-256 is `2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac`.

The runner checks the pinned originals before the build, after the build, before execution, after execution, and before publication. It physically copies the manifest, all ten XMLs, and SAP into temporary staging. It checks the copies against the same hashes before and after execution. It rejects unsafe names, missing or unexpected entries, symbolic links to input files, replaced staging directories, hardlinks back to originals, changed bytes, an existing receipt, and a receipt inside the original corpus directory. The published receipt includes the original paths and hashes. It does not include regenerated circuit files.

## Functional contract

The eight accepted banks must retain unique `a-i`, `b-i`, and `out-i` labels, 8-bit pins, `4n` main components, `n` main wires, and the literal geometry and topology of each bank. Hierarchical banks must retain `and8`, its three 8-bit ports, the child topology, and the instance identities in simulation state. Every output must transition from `00` to `5a` to `00`. Runtime diagnostics must remain empty for those banks.

Canonical serialization occurs only in memory. `PerformanceOracle` checks a subsequent import against a reference derived from the first import. The independent label, width, count, topology, hierarchy, and behavior literals anchor the checks. The reference does not independently prove that the first import preserved every original XML metadata field. The receipt records this limit.

SAP reuses the literal expectations in `Sap1IntegrationTest`: seven circuit names, 156 components, 318 wires, and three main ROMs. Reset must display zero. Across 160 ticks, the display must remain zero before tick 56 and eight from tick 56 onward. The final state must contain six identified child states and empty runtime diagnostics. The replay does not add a requirement for empty diagnostics at every SAP tick. The in-memory roundtrip must retain circuit names, ROM contents, and wires, and reach a stable canonical representation.

## Prepared invocation

This command is a future execution recipe. It has not been run for this preparation.

```sh
python3 scripts/benchmark-circuits.py \
	--workload replay \
	--input-manifest /Users/thales/GitHub/logisim-mobile-worktrees/performance/outputs/performance/mobile-corpus-v2-complete/manifest.json \
	--sap-input examples/sap1.circ \
	--output outputs/performance/historical-replay.json
```

The first execution compiles the JVM test classes. `--skip-build` accepts only a matching source and compiled-file stamp. The runner freezes physical copies of both class directories and the Kotlin standard library before execution. It inherits only the existing runtime, locale, and temporary-directory environment allowlist. The JFC source remains part of the source identity, but replay rejects `--jfr`.

Replay also rejects `--sizes`, `--iterations`, `--warmups`, `--phases`, and `--prepare-only`. The output is a new JSON receipt with `scope: functional_replay` and `benchmark_executed: false`. Before publication, Python requires schema 1, eight accepted cases, two rejected cases, the ten expected file names, statuses, output counts, and hashes, plus SAP's 160 ticks and display `08`. Python also requires the metadata-reference limit. No functional pass is claimed until the real entry point completes.

## Preparation checks

Only static source inspection, original-input hashing, and Python fake-process tests ran during the preparation commits. The later receipt records the authorized compilation and one real functional replay separately. No benchmark, Native or device process ran for either stage. The preparation tests use inert fixture bytes and replace the pins inside a temporary copy of the runner. They do not export a new circuit corpus or modify the historical inputs.

The focused test command is:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_benchmark_identity.py'
```

The 28 tests cover the existing benchmark identity contract and nine replay cases. The replay cases check literal physical copies, restricted environment inheritance, incompatible options, changed original and copied inputs, unsafe and unexpected names, rejection of benchmark payloads, preservation of existing receipts, and rejection of incomplete or incorrect functional receipts. A dedicated negative replaces either staged `inputs` or `inputs/corpus` with a symlink to an independent physical copy outside the staging input root. Removing only the directory guard makes those attacks pass, so the negative does not rely on original-file identity or changed bytes.
