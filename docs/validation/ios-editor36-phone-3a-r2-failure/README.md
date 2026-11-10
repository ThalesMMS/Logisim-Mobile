# Editor36 iPhone R2: preserved failure

The selected native case failed after **419.831 seconds**, with one test, one failure and exit code 65. The wrapper remains `status=FAIL`, `native_execution_gate=NATIVE_FAIL` and `qualification_gate=FAIL`. Sixteen contract steps completed; step 17, the second connection, failed while obtaining the `a11y-circuit-list` snapshot at `NativeUIHarness.swift:118`. Step 18, the complete wired-document checkpoint, was not reached.

The app was producer `3a0a01f1af05dd3fea75d5a1499c762d039c478a`, bundle tree `813f1b2f05a28af4b593c8acf2c57d13257107ddb9ddcb9e38b35f2822a1d0ef`. The retained preflight and postflight verify the same twelve installed and producer files, catalog with 2497 messages, and deep/strict signature checks. The execution used the reserved iPhone 17e Simulator on iOS 27.0. Teardown terminated app PID 4675 and restored portrait; the execution owner returned the device lease.

Harness `e8354b17a2d1fcb5b0592c4810cd5a3d95622571` prepared eighteen Swift files. Its XCTest project declared seventeen compiled Swift files; `AccessibilityUITests.swift` was prepared but outside this target. The literal contract retained SHA-256 `9a77ddc4628e87a87b3dbcbe28a46691ed15b10f22b94b70544cce9944216ec9`, the same selected test and 900-second allowance, without alpha mode.

The [source comparison](source-comparison.json) records eight prepared-source differences from R1, including changes from the newer main baseline. This is **not an isolated A/B test** of the private search-reveal change. R1 remains a separate failure in original receipt commit `4c60e16e`; none of its artifacts or result fields were rewritten.

The six complete XML exports belong to passed checkpoints 2, 7, 9, 11, 13 and 15. They verify the empty project, the three inserted components, OUTPUT facing west, NOT facing north, and the exact Undo/Redo restorations and component identities. All six exports precede the connection steps and contain zero wires. Step 16 completed its first connection workflow, but no subsequent complete XML was captured. This package does not qualify the full wired document, output inversion, copy/paste/delete, remaining history, zoom, SAP-1, Save/restart, rotation, canvas gestures, assistive-reader flow or physical hardware.

The [receipt](receipt.json) maps the sixteen passed notes and six XMLs to their actual attachment filenames and hashes. `raw.tar.gz` retains 715 byte-identical members: all 668 non-video attachments, the attachment manifest, full XCTest/build logs, prepared harness and resources, runner sources, preflight, postflight and execution driver. The 149,798,956-byte recording, large Simulator log and post-teardown screenshot remain local with size and SHA-256 references. The native product screenshot and final tree are in the public archive. The verifier does not access those local-only paths.

Run the standalone archive checks:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 verify.py
PYTHONDONTWRITEBYTECODE=1 python3 test_verify.py
```

The verifier authenticates the archive and checks the app, device, source inventories, literal failure, sixteen notes, six complete XMLs, stable native identities and full XML restorations. The negative tests rebuild archive hashes before attempting semantic changes, including extra wires, wrong facing, swapped IDs, a forged PASS, a different device or source inventory, and a false isolated-A/B claim. These checks validate the retained record; they do not rerun the app or turn the failed native scenario into a pass. The terminal snapshots do not establish which layer consumed a gesture or caused the missing Explorer.

The [independent archive review](independent-review.json) compared all 715 public raw members with their originals and reproduced the fourteen checks outside the repository. It approved preserving this failure with its stated limits; no native rerun occurred.
