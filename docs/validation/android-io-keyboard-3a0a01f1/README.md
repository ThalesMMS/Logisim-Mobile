# Android keyboard run stopped before backspace

The single selected `keyboard-tty` unit remains **FAILED**. On the Android 15 emulator, the new app retained `AB` and exposed the expected character probes through two clocked characters. The test then failed while locating the ASCII backspace control, before clicking it. This receipt qualifies that partial progress only; it does not close issue #44.

| Identity | Retained value |
| --- | --- |
| App producer | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Equivalent production inputs and helper source | `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c` |
| Frozen executor | `bbbf1e9e1caa38bc8608c994e050aba171879899` |
| Package / serial | `thalesmms.logisim.mobile.e2e` / `emulator-5556` |
| App / test / helper SHA256 | `10e2b026…956973` / `19ab4b9d…0aa2b` / `504cbdf7…ab3d3c` |

The [receipt](receipt.json) stores full hashes and the retained inventory. Local AAPT identities were checked before ADB. The execution manifest records the three installed hashes before the scenario; the post-run readback records the same hashes afterward. The app came from the root 3a gate. The helper was built at 5c. These sources are deliberately distinguished. The producer manifest identifies the 2497-message catalog and unchanged production inputs between those sources.

The I/O driver, contract, circuit fixture, target metadata and document oracle were byte-identical to the earlier `a32d3302` executor used by failed run 85649. The current `android_ui.py` came unchanged from main `2d4d417f`, including its typed snapshot protocol. No expected I/O value, text, timeout or scenario step was weakened for this attempt.

## Observed progress

- The imported public Copy XML baseline matches the complete I/O fixture, with 54 components and 64 wire segments. All nineteen literal baseline probes passed. This XML export precedes character entry; it is not an after-failure export or proof of transient kernel-state persistence.
- Two retained complete SDK snapshots show the focused Keyboard editor containing `AB`, with the component Label still `IO_KEYBOARD`. This is direct UI evidence beyond the runner assertion.
- Canvas descriptions expose `OUT_KEYBOARD_DATA` values `1000001` for A, then `1000010` for B, then `0000000` after consumption. `OUT_KEYBOARD_AVAILABLE` changes `1`, `1`, `0`. Each character uses the unchanged two-half-cycle sequence.
- Manual inspection of the original full PNGs found TTY first-row `A` at Steps 2 and `AB` at Steps 4. This is a visual review of [afterFirst](raw/io-tty-afterFirst.png) and [afterSecond](raw/io-tty-afterSecond.png), not an automated pixel oracle. The native probe checks are separate from that review.

ASCII backspace was not clicked. Form feed and capacity truncation were not reached. Home and End are not explicitly exercised by this existing unit. The contract contains a capacity restoration value, but the driver does not execute that restoration even in a successful run; no restoration is claimed here.

## Recorded selector obstruction

The English catalog resolves `io_ascii_backspace` to `ASCII backspace`. Both retained final hierarchies contain one matching app-owned, enabled label at `[518,1158][795,1211]`. It is wholly visible and belongs to the active Inspector scope.

The frozen `_Editing.action_node` resolves that label to its clickable, enabled ancestor at `[455,1122][858,1248]`. Its horizontal scrolling ancestor ends at `[21,1121][1059,1247]`. The strict `visible_node` test therefore rejects the clickable ancestor because its bottom extends one pixel beyond the scroll viewport. The frozen `_Editing.nodes` returns no candidate for these recorded trees. In a separate offline copy, changing only the scroll bottom from 1247 to 1248 makes the same resolver return one candidate. No original hierarchy was edited.

This is a deterministic obstruction reproduced from the two final trees. It does not reconstruct every query during the twenty-second wait. The last CLI hierarchy timeout had about 0.155 seconds remaining; it is a later symptom and does not by itself establish why the control lookup failed. No control was tapped during this audit, and no product defect or remedy is inferred from the clipping alone. The [derived census](analysis/selector-census.json) is kept separate from the raw captures.

## Snapshot and cleanup limits

All ten retained SDK captures are complete and have unique nonces. Each records app PID 23045 before and after capture. None is a typed `ROOT_UNAVAILABLE` or `INCOMPLETE_TREE`; this run does not qualify recovery of either state. The receipts do not claim a shared `screen()` call or a continuous PID outside the observations actually retained.

The owner preserved the failure state and read back all three APK hashes before stopping only the isolated app. The [cleanup record](raw/post-run-readback-cleanup.json) identifies PID 23045 before that explicitly authorized force-stop and no app PID afterward. No new launch, device command or retry was performed to create this documentary package. The 5556 lease was returned. No pre-group ANR baseline was retained, so this package makes no "ANR unchanged" claim; the retained ANR dump is a post-failure observation only. Earlier failed run 85649 remains a separate result for a different app producer.

## Offline checks

Run from this directory or pass the documented package path to the verifier:

```sh
python3 -B verifier.py
python3 -B -m unittest discover -s . -p test_verify.py -v
```

The verifier checks the archived bytes and recorded semantic facts without importing current application code or accessing Android. Negative controls rehash corrupted evidence before checking it, so the semantic checks must reject the corruption independently of inventory checks. No APK or JAR is included. This is one English API 35 emulator attempt, not physical-device, full-suite, performance or complete #44 qualification.
