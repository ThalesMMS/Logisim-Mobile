# Android I/O dimensions audit

The selected `dimensions` case passed in the mounted Android editor on source `85da031e`, APK `67e81d69`, with executor `a32d3302`. The retained run took 582.02 seconds on the Android 15 emulator. This receipt qualifies that case only. Issue #44 remains partial. The separate pointer attempt `79942` and keyboard attempt `85649` remain failed with their causes open.

An offline audit checked the actual exported documents and native descriptions, independently of the run's declared `passed` status. It issued no device command and did not repeat the native execution.

| Attribute | Native edit and restoration | Exported components | Wire segments |
| --- | --- | --- | --- |
| LED bar height | 3 → 4 → 3 | 54 throughout | 64 throughout |
| TTY rows | 3 → 4 → 3 | 54 throughout | 64 throughout |
| TTY columns | 12 → 8 → 12 | 54 throughout | 64 throughout |
| Dot matrix rows | 7 → 8 → 7 | 54 throughout | 64 throughout |
| Connected keypad size | large → normal → large | 54 throughout | 64 → 80 → 64 |

The twelve complete XML exports cover the baseline, each edit, each restoration and the final document. The existing `IoShowcaseOracle` checks every component, position and attribute, plus the partition of connected terminals. It permits the keypad's changed routing segment count while requiring the same intended electrical connections. The six recorded Canvas observations match all nineteen literal baseline outputs. Each of the eleven paired native hierarchies also contains exactly one complete nineteen-probe Canvas description. The native Canvas wrapper has an empty description; the observed description belongs to its descendant.

The original manifest records matching hashes for all three installed APKs before and after execution. The independent provenance audit also recomputed the local APK hashes, checked their package identities with local AAPT, and matched all eight frozen scripts to executor `a32d3302`. The fixture, target manifest, I/O contract and existing oracle are identical at the producer, executor and audit base `5cb2fead`. The original manifest's `scenarioContractSha256` identifies `editor-actions-v1.json`; the I/O contract has its own hash in `receipt.json`.

The exported XML came through the public Copy XML flow recorded in the frozen executor. Its clipboard preparation nonce and deletion of the previous receiver file protect that flow against stale clipboard bytes. The archived hierarchies were captured after each export, once the editor was visible again. They do not display the clipboard payload itself. Original screenshots and focused input snapshots remain in the raw run directory recorded in `receipt.json`; this audit makes no raster UI parity claim.

Reproduce the offline audit from the repository root:

```sh
python3 scripts/verify_io44_dimensions_receipt.py
python3 -m unittest discover -s scripts -p test_io44_dimensions_receipt.py -v
```

The verifier imports the existing I/O oracle and checks the archived evidence hashes, bounded selection, six APK receipts, nineteen probes and all twelve documents. Twelve tests passed. The negative controls update bundle hashes after corrupting documents, so missing wires or attributes must be rejected by the document oracle itself. Other controls reject a wrong restoration, unknown Keyboard output, incomplete native description, changed APK, modified executor, missing checkpoint and a full-issue or full-suite claim.

The checked-in `audit-summary.json` is the original audit result. The receipt, original evidence and provenance remain unchanged.

## Historical oracle snapshot

The proof includes all five historical oracle inputs under `oracle-snapshot/`, with their original repository-relative paths. Its `manifest.json` records audit pin `5cb2fead2743cbba84f0efbad51e8bca822c7f05` and the SHA256 values already recorded in `receipt.json`. These are archived verification inputs, not the current application or scenario sources.

The verifier checks the fixed audit pin, the pinned manifest hash, the exact file inventory and every input hash before running the archived Python oracle. It rejects symlinks. The archived module resolves its fixture and contracts inside the same snapshot. Replay therefore needs neither a historical checkout nor Git objects or files elsewhere on this Mac. Changes to the current sources, including the shared SAP Pause interval of 2500 ms, do not affect this historical audit. The snapshot retains its original 750 ms SAP contract solely to authenticate this run's recorded `scenarioContractSha256`; it does not alter the current contract.

The replay tests now include nineteen cases. They retain the original semantic negative controls after evidence hashes are recomputed and add missing or modified snapshot, rehashed snapshot and receipt, wrong pin, external link and changed-current-source controls. Running the same commands above reads only the proof package and Python standard library. This evidence does not qualify pointer holds, text entry, iOS, physical hardware, other I/O cases or overall issue completion.
