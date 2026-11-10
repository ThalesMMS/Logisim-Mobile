# Historical input replay at 29942df3

One functional replay completed with exit 0 on clean producer `29942df31947a38f9656dcc0e414c5ec457c0fc1`. Preparation compiled the JVM test classes without incremental compilation. The replay used the resulting stamp and physically frozen input/classpath copies. It did not measure performance or create a CSV, JFR or new circuit XML. The only functional output was its JSON receipt.

| Input group | Observed result |
| --- | --- |
| Independent banks 16, 64, 256 and 384 | All accepted; every output checked through `00 → 5a → 00` |
| Hierarchical banks 16, 64, 256 and 384 | All accepted; the same transitions, with child identities checked |
| Both 1024-bank inputs | Rejected with the existing `error_xml_structure` notice |
| Original SAP-1 | Reset output zero; 160 ticks with output zero before 56 and eight from 56 onward |

The eight accepted banks contain 1,440 observed main outputs in total, each checked at three input states. The checks also cover literal labels, widths, positions, connections, child topology, instance ownership, absence of oscillation and runtime diagnostics, and canonical roundtrip consistency. SAP-1 retains the expected seven circuit names, 156 components, 318 wires and three main ROMs. Its final state has 160 ticks, six identified child states and empty runtime diagnostics. The in-memory SAP roundtrip preserves names, wires and ROM contents. It does not run the program again on the reopened project.

## Input and artifact identity

The input manifest, its ten existing XMLs and the 47,528-byte SAP-1 file were copied literally. All 12 originals retained their sizes and SHA-256 values before/after execution. The historical manifest is `295571568422db33a280e9c0a52b322982cfa7f57c10e297fd70a788d7596f58`; SAP-1 is `2103aae7364ef6bc96f8620d39e7223ce6e561b4cf51021e72a2e4479c962aac`. The original manifest's old revision/dirty fields remain input data; they do not identify this replay producer.

`historical-inputs.zip` contains those exact 12 inputs, without regeneration. Its paths are `corpus/manifest.json`, the ten files under `corpus/`, and `sap1.circ`. The verifier checks the pinned manifest/SAP and every XML's hash, size and expected inventory directly from the archive. It does not launch a parser or simulator.

The capture records 305 source/configuration hashes, 1,256 class hashes and 1,259 classpath files including Kotlin stdlib. The source stamp and clean producer remained unchanged. The runner checked the original and copied inputs around preparation, execution and publication; directory symlinks and links back to originals are rejected. The command, source hashes, classpath hashes, Java version/launcher identity, environment key names and payload identity remain in `replay.json`. Java inherited only the recorded allowlist subset, without Java-option variables; no environment values are archived.

The consumed temporary classpath was removed after its checks. Local source/classpath ZIP snapshots were subsequently made from the unchanged producer files and verified byte for byte against all consumed hashes. `artifact-snapshots.json` records their paths, sizes and SHA-256 values. They are retained as local build artifacts rather than duplicated in this public package. This is not a claim that the ZIPs are the same filesystem objects as the removed temporary copies.

Runtime identification is `/usr/bin/java` with its launcher hash and the reported JBR 21.0.11 version. The archive does not contain a complete snapshot of JBR, libjvm, modules or host libraries. The sources captured here are the entrypoint, runner, profile and relevant oracle/fixture/SAP test files; the full source closure is recorded by hash and retained in the local snapshot.

## Verify or reproduce

Verify the committed archive without Java:

```sh
python3 docs/performance-evidence/historical-replay-29942df3/verify.py
```

`--check-local-snapshots` additionally checks the two retained ZIPs, their complete entries and the 12 originals at the captured paths. It does not compile or rerun the replay.

For a future reproduction, first extract the committed input archive to a new directory. Then prepare the current source/classes and run the functional entrypoint with a new receipt path:

```sh
python3 -m zipfile -e docs/performance-evidence/historical-replay-29942df3/historical-inputs.zip outputs/performance/historical-inputs
python3 scripts/benchmark-circuits.py --prepare-only
python3 scripts/benchmark-circuits.py --workload replay \
  --input-manifest outputs/performance/historical-inputs/corpus/manifest.json \
  --sap-input outputs/performance/historical-inputs/sap1.circ \
  --skip-build --output outputs/performance/historical-replay-copy.json
```

These are reproduction instructions, not additional invocations performed for this receipt. In the shared development environment, the integrator serializes preparation/build commands with the existing build lock.

## Scope

The independent literals anchor this historical corpus and SAP program. Full metadata and canonical roundtrip references derive from the first import, so they do not independently establish preservation of every original XML metadata field. Only the first imported projects are simulated. Bank behavior is the three stated input phases with the second input fixed at `5a`; SAP diagnostics are required empty at the end, with display/oscillation checked per tick. This does not qualify every possible input, waveform or original graphical bound.

This replay revalidates the historical bytes functionally against the identified core. It does not reattribute the old bb63 timing captures, measure latency or retention, qualify Native drawing/pan/zoom/background, prove energy/device budgets or close issue #48. The separate restricted-JFR privacy smoke remains separate evidence.
