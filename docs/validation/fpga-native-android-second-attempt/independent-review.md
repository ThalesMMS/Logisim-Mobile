# FPGA native second failure review

Reviewed read-only on 2026-11-05. The review read and hashed the supplied files. It did not run ADB, live UI, hosts, EDA tools, tests or builds, and changed no repository or evidence file.

Evidence roots:

- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-pairing-scope/outputs/fpga-native-ce3e061d-002`
- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-pairing-scope/outputs/fpga-ce3e061d-stage-002`

## Terminal result and identities

The run remains `FAIL`, `NATIVE_FAIL`, `NOT_QUALIFIED`. It used harness `c1835a329862af96c7de7b2bd6957578196e0e36` and product `ce3e061d38cd9851038d1cd8a71557ede9add641` on `emulator-5556`. It started at 06:23:36.388069 and finished at 06:24:24.477857 UTC, approximately 48.09 seconds later.

All eleven frozen source and fixture files match the hashes in `fpga-run.json.sources`. The native and staging build receipts are byte-identical. Their producer-manifest digest matches the copied producer manifest. The three staged APKs match the producer entries, staged preflight, original copied APK bytes and installed-before records:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| E2E app | 16,298,862 | `71bafede4e6c9c5ea637d5ad0bb7cce5e366b0842b8f06d32d34143db2277a43` |
| E2E tests | 138,810 | `c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882` |
| Snapshot helper | 951,790 | `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387` |

The stage's `installedBeforeUi` list equals the run's `installedBefore` list, which equals the lease-return `installedAfter` list, including paths and hashes. The stage records no installation for this attempt, no operation on the normal app and no operation on another device. It records closing the residual Companion sheet through the public close action and observing the editor before starting the second attempt.

## Two distinct snapshots

The stdout Bundles match their respective JSON receipts. The snapshots have distinct nonces and are not interchangeable observations.

| Observation | First snapshot | Later failure collector snapshot |
| --- | --- | --- |
| Driver receipt | `rejected` | `complete` |
| Capture state | `ERROR` | `COMPLETE` |
| Instrumentation code | `0` | `-1` |
| Root source | `unavailable` | `active-window` |
| Root attempts | 9 | 7 |
| Traversed nodes | 0 | 32 |
| Elapsed | 1,003 ms | 2,036 ms |
| Declared overrun | 3 ms | 1,036 ms |
| App PID before and after | 21917 / 21917 | 21917 / 21917 |

The first receipt has no XML payload and reports that UiAutomation returned neither the active root nor an active/focused application-window root before its capture deadline. Its single capture-attempt record agrees with that failure. This rejected snapshot caused the scenario to abort.

The later complete snapshot belongs to failure artifact collection. In the frozen source, `AndroidUi.fail_artifacts` performs a separate `screen(timeout=8)` while collecting its hierarchy. The later XML matches both the decoded Bundle payload and the saved `fpga-failure-ui.xml`, including its nonce. It is a valid preserved hierarchy, but its completion does not turn the earlier scenario failure into success or establish compliance with the 1,000 ms capture budget. The recorded overrun remains explicit.

The PID is stable across both snapshot receipts. That rules out a process replacement between these recorded boundaries. It does not by itself prove the cause of the unavailable root or establish overall UI responsiveness.

## Field state and execution limit

The collector's hierarchy shows the address editor focused and still containing the unchanged default `http://127.0.0.1:8042`, exactly 21 characters. The confidential token editor is empty, not focused and marked `password=true`. Its public confidential description remains in a child View.

The frozen pairing function first checks the empty confidential editor, then calls `input_text` on the address. The frozen `input_text` taps its target and requires a fresh focused hierarchy before deleting the previous value or entering any new text. The unavailable-root failure and the later focused, unchanged address are consistent with stopping at that initial focused read. The evidence does not qualify address entry, credential entry, host verification or any FPGA case.

The error artifact wraps the original error as `RuntimeError` for collection; the run report retains `AssertionError` and the same unavailable-root message. This is the runner's documented collection wrapper, not evidence of another product failure.

## Host operations and lease cleanup

The host advertises only `fpga-synthesize` and keeps programming unregistered. Its audit has zero plan/submit/cancel requests, zero handler events and zero jobs. The artifact manifest is empty. Capabilities GET requests are outside this audit, so this is not a general network trace. No synthesis, compiler-error or cancellation case is qualified.

The terminal lease receipt identifies owned host port 63770 and records `ownHostListenerPresent=false`, `ownReverseMappingPresent=false`, `retryPerformed=false`, and no operation on another device or the normal app. Its handoff screen remains the Companion pairing sheet. The frozen runner closes its host and removes only its owned reverse mapping in `finally`, checking the target before removal. The report records no cleanup errors, and the three APK handoff identities remain unchanged. This review did not perform an independent live listener or mapping check.

Failure collection records no collection errors. The later complete hierarchy is preserved solely as failure evidence. It is not a scenario retry. No blocker found in publishing this attempt as terminal failed and unqualified, with the snapshot distinction and timing overrun retained.
