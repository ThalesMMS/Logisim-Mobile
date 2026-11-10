# Selected Editor36 case on iPhone: failed

The single `testEditingConnectionsHistoryAndViewport` execution failed after **377.055 seconds**, with one test and one failure. The wrapper exited `65` and records `status=FAIL`, `native_execution_gate=NATIVE_FAIL` and `qualification_gate=FAIL`. This is a failed selected case, not an issue #36 qualification.

The application was producer `3a0a01f1af05dd3fea75d5a1499c762d039c478a`, bundle tree `813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef`. The reserved iPhone 17e Simulator used iOS 27.0. Harness source remained `ba9d9682248fca28adbf5fc050f018156c7edc5c`; all 17 prepared Swift files retain the runner-recorded bytes. The case allowance was 900 seconds, matching the earlier iPad execution. The external execution driver serialized only XCTest compilation and changed no test, assertion or app source.

## Observed progress and failure

Fifteen ordered contract actions completed. They created INPUT, NOT and OUTPUT, applied their labels and facing, and checked Undo/Redo of the NOT orientation. Six complete XML exports correspond to steps 2, 7, 9, 11, 13 and 15. The literal checkpoints contain either the empty circuit or the same three distinct components with no wires. Their component attributes and IDs are retained, including repeated byte-identical snapshots after Undo/Redo.

Step 16 attempted the first connection. The log records the source terminal button `port:2:c1:out` being tapped at 317.51 seconds. It then records the Components button at 320.08 seconds and an unsuccessful reveal of the destination search field. The complete-connection command was not reached. The failure at `NativeUIHarness.swift:82` reports that a matching snapshot for `a11y-circuit-list` could not be obtained.

The reveal sequence contains twelve upward drags and one final downward drag. The list was still found at 369.27 seconds; the downward gesture at 370.45 seconds requested `(195,190)` to `(195,778)`. Subsequent queries could not find the list. The final native tree and screenshot show the editor without the Explorer sheet. There is no recorded negative tap point in this sequence.

The owning application source places Search in the Explorer's `LazyColumn`, rather than in a sibling header. The sampled video frames show the search field above the viewport while the source card is displayed, and show it again while the sheet is displaced downward. Media offsets are not independently synchronized event timestamps. The sequence is consistent with scrolling back to the top and continuing into the sheet's dismissal gesture, but neither gesture consumption nor the dismissal callback was instrumented. This receipt does not assign an internal cause or attribute the failure to the new accessibility ownership guard.

## Preserved evidence

[receipt.json](receipt.json) maps the six exports and fifteen successful step notes to their raw attachment names, byte counts and SHA-256 values. [raw.tar.gz](raw.tar.gz) retains 624 members, including all 577 non-video native attachments, the complete XCTest and build logs, results, the executed harness and resources, the preflight, the external execution driver and the installed-app postflight. Raw bytes were not trimmed. The original attachment manifest still describes the locally retained video; its explicit omission from the public archive is recorded separately.

The automatic screen recording is 126,831,107 bytes, above GitHub's per-file limit. It remains intact locally with its hash and path in the receipt. The large Simulator log and the runner's screenshot taken after teardown also remain local. The product's own final native screenshot and four independently extracted video frames are retained in this package. The extracted frames are observations from the existing recording, not a new native execution.

The postflight independently checked all 12 installed app files and all 12 producer files against the preflight, along with the catalog's three source hashes and deep/strict code-signature verification. UUID, model and runtime remained unchanged. Native teardown terminated app PID 76016 and restored portrait; a separate terminate returned code 3 because the app was already stopped. The Simulator lease was returned before any packaging or source changes.

## Limits

The receipt does not qualify completed connections, resulting wires, native signal inversion, copy/paste/delete, the remaining history actions or viewport phases. It contains no Save/restart, SAP-1, rotation, canvas drag/pan/pinch, screen-reader or physical-device qualification. The earlier iPad pass remains separate evidence with its original producer.

Run the standalone archive checks with `python3 verify.py` from this directory. They need no app, Simulator, Git checkout or external source files. The checks validate the failed prefix and its six observed XMLs; they do not turn this execution into a pass.
