# Second Android FPGA attempt stops during root acquisition

The second authorized native attempt used the reviewed driver
`c1835a329862af96c7de7b2bd6957578196e0e36` and the unchanged product
`ce3e061d38cd9851038d1cd8a71557ede9add641` on `emulator-5556`.
It ended **FAIL / NATIVE_FAIL / NOT_QUALIFIED** after 48.09 seconds. There was
no further attempt and no source or timeout change during this run.

The corrected public-label selector resolved the pairing fields. The address
field received focus, but `input_text` could not acquire its first verification
snapshot. The SDK helper reported no active root or active/focused application
window root for the expected package. The driver stopped before deleting or
entering address characters. The later failure hierarchy still contains the
default `http://127.0.0.1:8042` and an empty masked token field.

## Separate the failed snapshot from failure collection

| Observation | Scenario snapshot 1 | Collector snapshot 2 |
| --- | ---: | ---: |
| Client status | Rejected | Complete |
| Capture state | ERROR | COMPLETE |
| App PID before and after | 21917 | 21917 |
| Root attempts | 9 | 7 |
| Root wait | 1,003 ms | 1,312 ms |
| Capture elapsed | 1,003 ms | 2,036 ms |
| Capture overrun | 3 ms | 1,036 ms |
| Traversed nodes | 0 | 32 |
| Root source | unavailable | active-window |
| Window ID | -1 | 28854 |

The SDK capture budget was 1,000 ms. Snapshot 2 came from `ui.fail_artifacts`
after the scenario aborted. Its completion is diagnostic evidence and exceeded
that budget; it does not turn the scenario into a pass or constitute a workflow
retry. `snapshot-summary.json` derives these fields from the original receipts.
Raw Bundles, stdout, stderr, nonces and the available XML remain unchanged.

The stable PID and later hierarchy do not establish the cause of root
unavailability. They do not prove an application crash, a rendering defect or
an incomplete tree. The error remains distinct from an incomplete-tree result.

## Scope and cleanup

The real host audit contains no recorded plan, submit or cancel request, no
handler event and no FPGA job. GET capability requests are outside that audit.
The scenario did not reach host verification, board mapping, project ZIP
export, HDL upload, synthesis, tool errors or cancellation. These flows remain
unqualified by this run. The checkpoint circuits and workspace TAR are the
existing E2E workspace collected for failure context; they are not FPGA results.

Before UI, the app, test and snapshot APKs passed local manifest/target/hash
checks and installed hash checks. The catalog checkout remained clean at ce3.
The previous Companion sheet was observed and closed through the public Close
action, with before/after captures. No APK installation was needed for attempt 2.

The three installed hashes remained unchanged after failure:

| APK | SHA-256 |
| --- | --- |
| E2E app | `71bafede4e6c9c5ea637d5ad0bb7cce5e366b0842b8f06d32d34143db2277a43` |
| E2E tests | `c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882` |
| Snapshot helper | `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387` |

The owned loopback host on port 63770 closed and its reverse mapping was
removed. Post-run checks confirmed no listener on that port and no matching
reverse mapping. The lease returned with the Companion sheet open and its
address field focused. No normal app, emulator-5558, port 8042 listener,
programmer, cable or physical board was operated.

This package copies the complete native output and non-APK preflight evidence
byte for byte. `copy-chain.json` records every source path, size and digest.
The APK binaries remain in their frozen directories and are identified by the
original producer manifest. `SHA256SUMS` covers the copied and derived files.
Android's logcat output retains its original trailing spaces. The local
attribute exception applies only to that exact raw file; its hash and bytes
match the source. The unfiltered log also includes earlier application entries,
which remain context rather than timing measurements of this workflow.

The first attempt's selector failure remains in its own immutable receipt.
The second attempt preserves a separate root-acquisition failure. Issue #65
remains open; no FPGA software flow or physical programming is claimed complete.
