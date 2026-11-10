# Editor #36 — iPhone R3 failure record

The single selected Native XCTest failed in **300.753 seconds** with **1 test, 1 failure, exit 65**. Fifteen contract steps completed and six literal XML checkpoints were exported (steps 2, 7, 9, 11, 13 and 15). Step 16, `connectPorts`, was attempted; step 17 onward was not completed. This record does not qualify the 62-step Editor case or close issue #36.

The test log records a request to tap the Start terminal `port:2:c1:out` at 297.98 seconds. It does not record a tap of the Complete terminal `port:2:c2:in`. All six exports precede a completed connection and contain zero wires. Wiring, subsequent history, copy, zoom and signal assertions were not reached.

Twenty original header observations and seven decisions are archived. All seven Components decisions skipped a tap after observing the unique canonical button publicly selected, enabled and with a valid frame. Header tap requests and backward-scroll requests were both zero. The Components-false → tap-once → fresh-selection-confirmation route and backward-scroll route were not exercised. These records describe API requests and public accessibility snapshots, not callback delivery or universal absence of an input method.

The final Search was present (count 1, role 52), enabled, with an empty label and frame `(24, 158, 342, 24)`. The list viewport was `(24, 182, 342, 604)`, the observed input-region set was empty, and hittable was unqueried. The guard rejected this snapshot before reveal/scroll in that invocation. Both final trees still contain `editor-tool-sheet`, `a11y-circuit-list`, Search and `component:c1`. The evidence does not establish that a reveal gesture dismissed the sheet.

Run `f2167ca1720d` used harness source `91bc58e38e0171777df5951b018a23ac6da8da69`, 18 prepared Swift files and 17 compiled target files. The staged accessibility-suite source was excluded from this target. The literal selector was `EditorEndToEndTests/EditorEndToEndTests/testEditingConnectionsHistoryAndViewport`, timeout 900 seconds, alpha disabled. The immutable app came from `3a0a01f1af05dd3fea75d5a1499c762d039c478a`; its bundle hash was `813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef`. Successful preflight 003c and postflight compare all 12 installed/producer app files, strict signature verification, catalogue and input hashes. The iPhone 17e was returned in portrait; the explicit final termination reported that the app was already not running.

Preparation attempts 003 (`KeyError: app`) and 003b (catalogue-shape assertion) failed before Native execution. Their original diagnostics are retained as setup errors with Native run count zero. They do not increase the single Native attempt count.

`raw.tar.gz` preserves all 555 non-video attachment files, the original 556-entry attachment manifest, full selected test/build logs, harness/resources, the 50 pinned source inputs, execution scripts, setup diagnostics and pre/postflight records: 669 members in total. `raw-members.json` records every member's size and SHA256. No raw log was trimmed. The original video, Simulator log, xcresult tree and post-teardown screenshot remain local with paths, sizes and SHA256 references in `receipt.json`; default verification never reads those local paths. R1 and R2 remain separate historical records.

Run these commands with Python 3 from any directory; no Git repository, device, app, Xcode or local recording is required:

```sh
python3 /absolute/path/to/ios-editor36-phone-3a-r3-failure/verify.py
python3 /absolute/path/to/ios-editor36-phone-3a-r3-failure/test_verify.py
```

The verifier reports `FAIL_PRESERVED` after validating the recorded failure. Tests mutate real XML and metadata, rebuild archive/checksum layers, and require semantic rejection of altered wires, attributes, identities, status/device/source, header observations and execution counts. Their passing result validates the evidence package; it is not a Native PASS.
