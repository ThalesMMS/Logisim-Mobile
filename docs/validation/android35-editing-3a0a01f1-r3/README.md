# Android editing passed in R3

One `--scenario editing` invocation passed all four original groups on the API 35 phone emulator `emulator-5558`, with English app UI. The app producer was `3a0a01f1`; the driver was `da4ffddb`; the snapshot helper came from `5c2abc60`. This receipt qualifies these editing groups only. The Files workflow remains pending, and issue #35 remains open.

| Original group | Result | Recorded duration |
| --- | --- | --- |
| Create components and edit properties | PASS |236.75s |
| Connect wires and exercise the NOT truth table | PASS |194.88s |
| Copy, paste, delete and restore history | PASS |176.08s |
| Zoom controls and touch/Explorer insertion parity | PASS |179.99s |

The controller recorded exactly one native invocation, exit 0, and 800.418 s total. These durations describe this run, without a performance budget or idle-host claim. The [original results](raw/results.json), [controller record](control/unit.json) and [receipt](receipt.json) retain the exact values, timestamps and arguments.

The installed app, test and helper hashes matched before and after the run:

| Artifact | SHA256 |
| --- | --- |
| App |`10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| Test |`19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Helper |`504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |

The runner installs its supplied isolated APKs and clears only the `.e2e` app and test data before starting. It retained 24 complete Copy XML checkpoints. The offline replay checks 22 against the frozen literal document contract. The other two pass the original OR-insertion comparator at circuit location `(220,200)`, with identical component attributes and wires for touch and Explorer insertion. The two exports happen to have identical bytes in this run. Their comparator permits different fresh component IDs. The final document is byte-identical to the original wired document.

The historical comparator checks all component attributes and wire multiplicities while ignoring XML element order and generated IDs. The portable verifier additionally checks the project options, main circuit, library ownership, permitted XML structure and unique component IDs. It does not replace the historical expectations with values generated from the retained documents.

The original runner passed its three output assertions, 1 then 0 then 1. The archived SDK trees independently retain both NOT truth rows: [capture64](raw/focus-snapshot-b4965009830c4b1ebaa26418682b77e3-64.xml) shows input 1, NOT 0 and output 0; [capture68](raw/focus-snapshot-b4965009830c4b1ebaa26418682b77e3-68.xml) shows input 0, NOT 1 and output 1. Each has exactly one Canvas description with the matching output. No initial 1 capture before 64 was found, so these captures are not presented as a complete 1→0→1 timeline. The Signals panel also contains output text; the verifier does not confuse it with the Canvas description.

All 82 SDK snapshots are complete. Their returned Bundles agree with the retained XML, nonces, node censuses and sampled app PID pairs. These observations do not prove continuous process lifetime. Ten Search journals retain every preparation tree and requested motion. One short backward request preceded the reached `E2E_NOT` field and six preceded `E2E_OUT`; the other eight searches needed no gesture. The verifier checks these requests against the authenticated short-motion helper and verifies each final field is wholly inside its retained viewport and window. Requests and sequential observations do not establish app event delivery or callback timing.

Before/after readback preserved the recorded device settings, reader-off state and last-ANR value. No ANR was recorded in those before/after dumps. The accessibility dumps contain changed client/window census information, so they are not claimed byte-identical. The controller force-stopped only its owned isolated app after saving the evidence; the subsequent PID query was empty. Source input hashes agree before and after execution.

R1 and R2 remain failed in their separate archives. R1 used 5556; R2 and R3 used 5558. This PASS does not assign a callback, keyboard, renderer or cohort cause to an earlier failure. The source-only receipt for `da4ffddb` remains an earlier preparation record, without being rewritten as native evidence.

## Portable replay

The package contains 403 unchanged run files, 74 unchanged controller files and 16 frozen historical source inputs plus their manifest, totaling 494 authenticated files and 9,313,268 bytes. `inventory.json` records every size and hash. The historical source manifest has a fixed verifier pin and is authenticated before importing either Python oracle. The packet contains no APK or JAR.

From any checkout, run:

```sh
python3 -B docs/validation/android35-editing-3a0a01f1-r3/verify.py
python3 -B -m unittest discover -s docs/validation/android35-editing-3a0a01f1-r3 -p test_verify.py -v
```

The verifier needs only Python's standard library and this packet. It uses no other worktree, Git object, Android process or network service. The 13 checks include corruptions with recalculated evidence hashes, historical-source authentication before import, a valid fresh-ID variation, link rejection and execution after relocation outside a checkout. The [original replay result](verification.json) and [test log](checks.log) are retained. This replay performs no new native execution.

The completed groups do not qualify the pending Files workflow, physical input ergonomics, reader-driven creation, painted viewport timing, pinch, energy consumption or whole-app UI parity.
