# iOS I/O run with four failures

The single B25 Simulator run `ios-3a0a01f1-io44-b25-001` finished with four native test failures and exit code 65. The app source was `3a0a01f1`, bundle tree SHA256 `813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef`. Admission and post-run artifact/cleanup checks succeeded. Those checks do not change the failed test result. No I/O method or issue #44 completion is qualified by this receipt.

| Method | Retained progress | Document checkpoints |
| --- | --- | --- |
| Dimensions | Initial nineteen probes recorded, full XML copied and received; observing Close failed | First checkpoint `INCOMPLETE_AFTER_XML_CAPTURE`, reason `METADATA_MISSING`; eleven `NOT_REACHED` |
| Display signals | Baseline assertion failed | Two `NOT_REACHED` |
| Momentary controls and joystick | Baseline assertion failed; pointer sequence not reached | Two `NOT_REACHED` |
| Keyboard, TTY and capacity | Baseline assertion failed; text sequence not reached | Two `NOT_REACHED` |

There are zero I/O metadata attachments and zero qualified metadata/XML pairs. Eighteen checkpoints were planned across the four methods. One XML was captured before its method failed, so it is incomplete rather than unattempted. The other seventeen checkpoints were not reached. No metadata was generated retrospectively.

The orphan export `1-io-fixture-opened.circ`, its native clipboard receiver output, the base fixture and four case-specific nonce files have the same SHA256 `a2595e0ec949702685cf0465485e430b0491b1d8b31daf3350abe5f3697b55b9`. The complete XML has 54 components and 64 wires and passes the existing document oracle as content. It does not complete the checkpoint: `ObservedProjectXML` attaches the XML before trying Close, while the checkpoint metadata is added only after that helper returns.

The cases have distinct app PIDs and nonce filenames. Dimensions has a real `io-probes` attachment containing all nineteen expected values before Copy. Its final tree is a later modal snapshot with an older editor root; that background does not prove the initial I/O state. The other three final trees each expose two complete, correct nineteen-probe descriptions at the same frame as the first Canvas marker. The frozen selector requires exactly one description at that frame and returns no value for this topology. This reproduces the observer's rejection for those final snapshots. It does not establish the values throughout the earlier timeout or directly observe internal engine reset work.

The Close review preserves a separate observation. The raw XCTest synthesized event targets PID 76677 at `(492.29816714665515, 703.1480597029523)`, with offsets 0 and 0.05 seconds. This is synthesis, not proof of delivery. Its coordinate differs from the final AX Close frame, and the retained frames show window transition context around the attempt. Ten intermediate wait dumps contain only an Application, without Window or XML nodes; they cannot establish that the modal was absent. The final screenshot shows the modal still open. The record does not demonstrate invocation or failure of the product's Close callback. Multiple scenes and inconsistent observed roots remain relevant preconditions for investigating automation delivery.

The bundle contains the original terminal results, execution plan, artifact admission, cleanup report, test log and full attachment manifest. Selected raw attachments include all four final screenshot/tree pairs, nonce report trees, initial Dimensions probes, both XML captures, synthesized events and degraded wait snapshots. It also contains the staged harness, its planned source files, the producer's document oracle and inputs, product sources used in the Close review, four extracted video frames with PTS metadata, and the original diagnostic notes. Historical absolute paths inside original receipts identify provenance; verification resolves archived files locally.

Run the offline checks from the repository root:

```sh
python3 scripts/verify_ios_io44_failed_receipt.py
python3 scripts/ios_io44_case_postflight.py --output /tmp/io44-ios-document-postflight
python3 -m unittest discover -s scripts -p 'test_ios_io44_*.py' -v
```

The default postflight uses the oracle and inputs frozen inside this bundle. Metadata is correlated by exact testcase group and original attachment name, never by a global first match. It refuses an unfinished run and rejects ambiguous or missing pairs. Eighteen tests passed, including copied-bundle verification and controls against promoting XML capture, cleanup or synthesized input to native success. They also cover repeated basenames across cases, missing local XML, duplicate attachments, mismatched metadata and disconnected documents. These are offline checks, not another iOS run.

`verification.json` and `postflight/` record the original audit. `receipt.json` lists archived hashes and source origins. The full 44 MB video, 138 MB simulator log, DerivedData and complete `.xcresult` remain in the original raw directory. The full stream is needed to independently re-extract the selected frames; the archived frames do not establish a precise UTC-to-PTS association or input latency. Neither the runner, app nor device was modified or rerun to create this receipt.
