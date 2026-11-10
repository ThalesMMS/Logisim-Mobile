# Selected Editor36 case: PASS on iPad Simulator

`testEditingConnectionsHistoryAndViewport` passed with **62 completed steps, one test, zero failures and 618.164 seconds**. Wrapper session `98544` exited `0`. The untouched result remains `status=PASS`, `native_execution_gate=NATIVE_PASS` and **`qualification_gate=PARTIAL`**. This receipt covers this selected case only.

The run used `FINAL 6cef0542`, test validation commit `85da031e`, bundle tree `d987d43d…` and executable `198a06dc…` on the B25 iPad Pro 11-inch M5 Simulator, iOS 27.0. The raw producer manifest and runner identities are retained. The existing independent postflight records the same installed identity after execution; packaging this receipt did not read live binaries or operate a Simulator.

## Executed route

The mounted Circuit explorer inserts INPUT `(100,100)`, NOT `(200,100)` and OUTPUT `(300,100)` through its type and coordinate fields. The Inspector assigns the labels and changes OUTPUT to west and NOT to north. Undo and Redo restore the expected orientations. Inspector dismissal uses its identified native drag handle.

Connections use the explorer's start/complete port commands. They produce the complete wires `(100,100)→(170,100)` and `(200,100)→(300,100)`. Activating INPUT through the explorer changes the actual native canvas summary from OUTPUT `1` to `0` and back to `1`; those three labels are preserved in the raw XCTest log in steps 19, 21 and 23. Removing the first wire, Undo and Redo are followed by complete XML comparisons.

The command toolbar selects all components, copies and pastes the selection at offset `(20,20)`, then exercises Undo/Redo. Native explorer captions after the first paste expose distinct pairs `c1/c4`, `c2/c5` and `c3/c6`, with complete labels and coordinates. The pasted XML has six components and four exact wires. A later selection deletion and its Undo/Redo restore the complete empty and wired documents.

The viewport steps use zoom buttons, explorer selection and Fit Selection/Fit Circuit controls. The native log records `179%→224%→179%`, then `400%` after fitting the selected NOT. The final native tree records `350%` after Fit Circuit; that final number is an observation, not an expected value asserted by the original driver. Step 62 verifies that the wired XML remains intact. These semantic values do not validate raster rendering or centering geometry.

## Retained evidence

The [receipt](ios-editor36-pass.json) maps every XML occurrence to its contract step, export index, raw attachment filename, size and SHA-256. All 117 exported attachments are retained unchanged, including 20 XMLs, 62 step notes, 20 checkpoint notes, three native identity notes, eight geometry notes, the portrait observation, the final tree and two screenshots. The archive also retains the full 529 KB XCTest log, raw results/Run/postflight, the executed literal contract, producer manifest, catalog and the relevant executed Swift sources. Total raw evidence is about 1.57 MB; no app binaries, xcresult or 417 MB Simulator log are included.

| Literal checkpoint | Contract steps | Components / wires |
| --- | --- | --- |
| `empty` | 2, 45, 49 | 0 / 0 |
| `components` | 7, 11, 15 | 3 / 0 |
| `north` | 9, 13 | 3 / 0 |
| `wired` | 18, 27, 31, 38, 42, 47, 51, 62 | 3 / 2 |
| `wire-removed` | 25, 29 | 3 / 1 |
| `pasted` | 35, 40 | 6 / 4 |

The shared contract is the executed byte sequence with SHA-256 `9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9`; its provenance names `04a0b34a`, rather than the app producer. The retained Editor, base harness, text replacement, XML observation and clipboard helper sources match their runner-recorded hashes and are byte-identical to reference commit `ba9d9682`. That reference identifies available source bytes; the runner did not record its source HEAD.

Run the standalone offline verifier:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 docs/validation/artifacts/ios-editor36-pass/verify.py
```

It checks every archived raw hash and attachment mapping, all 62 ordered actions, the exact components and attributes and complete wire endpoint multiplicities in all 20 exports, unique copied IDs, Undo/Redo restorations, the native output labels and zoom observations. Repeated snapshots are byte-identical. The complete XML hashes additionally anchor metadata that the original runtime canonical comparison did not inspect. Deliberately changed wire endpoints and duplicate copied identities were rejected by the offline document oracle; a changed raw evidence byte was rejected by the manifest gate. This verification is an archive check, not a native rerun.

## Limits

This case uses explorer commands for insertion and connections. It does not prove canvas drag placement, canvas wire drawing, pinch or canvas pan. Inspector dragging and explorer/toolbar scrolling are distinct interactions. Setup requests portrait once; there is no landscape transition or screen-rotation validation. This receipt contains no Save/restart, SAP-1, physical-device or global issue-closure qualification. Earlier failed runs remain separate evidence.
