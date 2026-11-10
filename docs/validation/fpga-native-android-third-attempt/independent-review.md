# FPGA native third failure review

Reviewed offline on 2026-11-05. The review read and hashed the supplied evidence and inspected the preserved final PNG. It did not use ADB, live UI, hosts, EDA tools, tests or builds. No repository or evidence file was changed.

Evidence roots:

- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-typed-root/outputs/fpga-native-5c2abc60-003`
- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-typed-root/outputs/fpga-5c2abc60-stage-003`

## Result and source identities

The run is terminal `FAIL`, `NATIVE_FAIL`, `NOT_QUALIFIED`. It ran once on `emulator-5558`, starting at 07:09:58.925148 and finishing at 07:10:23.500494 UTC, approximately 24.58 seconds later.

The identities have different roles and must remain separate:

| Role | Identity |
| --- | --- |
| App and test producer, recorded by the build receipt | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Root artifact manifest and snapshot-helper producer | `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c` |
| Native FPGA harness | `5912ff5029d5f4aabecd175112bfc386d8c5337b` |
| Frozen `android_ui.py` SHA-256 | `8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634` |
| Localization/catalog checkout | `fpga-native-catalog-3a0a01f1` |

The stage records production-input equivalence between 3a and 5c with no changed files. The build receipt states that app/test artifacts were copied from the root manifest, and that the helper was built at 5c. This review recomputed the copied APK hashes and source-file hashes; it did not rebuild either producer. This attempt is not attributable to product ce3 from attempts 1 and 2.

All eleven frozen source and fixture files match the run's hashes. The preserved typed Android UI source matches the stage's declared 8afbbc35 hash. The native and staging build receipts are byte-identical, and the producer-manifest digest matches the actual copied manifest.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| E2E app | 16,298,862 | `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| E2E tests | 221,322 | `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Snapshot helper | 965,334 | `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |

Each staged file is byte-identical to its frozen root source and agrees with the stage preflight and root producer manifest. The run's three installed-before records equal the lease-return installed-after records, including APK paths and hashes. The UI preparation records replacing the previous isolated triplet with these supplied APKs. The normal app is outside those identities.

## Pairing succeeded before the scenario failed

The final PNG visibly shows the verified Companion card. The final native hierarchy has these same messages:

- `The companion host is verified. External tools run only when you request an operation.`
- `Protocol 1, input limit 4194304 bytes`
- `fpga-synthesize: available`

The card matches `additional_companion_verified` in the supplied 3a catalog and `AdditionalEditorTools.kt`. That product branch appears after its connection callback stores the connected client and capabilities.

The frozen FPGA driver instead calls `self.e.reveal("companion_ui_verified")` after pressing Verify. That key's English value is `Host verified. Each external operation requires its own action.`, which differs from the actual final card. The helper then tries to find scrollable content while searching for its unmatched target and raises `The active panel exposes no scrollable content`.

The native result therefore demonstrates address entry, confidential credential entry and the visible verified/capabilities card for this pairing. The terminal failure is a driver expectation mismatch after pairing. It does not demonstrate a host rejection, missing verified card or root-unavailable failure. It also does not complete the FPGA scenario.

## Five complete SDK captures

Every raw stdout Bundle matches its JSON receipt. Each XML matches its decoded payload and nonce. All five capture receipts are `complete`, have capture state `COMPLETE`, instrumentation code `-1`, zero overrun and zero incomplete-tree attempts. The app PID before and after every capture is 18293.

| Capture | Elapsed | Nodes | Observation |
| --- | ---: | ---: | --- |
| 1 | 116 ms | 32 | Default 21-character address focused; token empty |
| 2 | 26 ms | 35 | Address changed to `http://127.0.0.1:53178` |
| 3 | 526 ms | 35 | Empty password editor focused |
| 4 | 81 ms | 35 | Password editor contains 64 masking bullets |
| 5 | 156 ms | 24 | Pairing editors replaced by the verified card |

Capture 4's password text consists exclusively of 64 U+2022 bullets. It is not a raw hexadecimal credential. No credential value is reproduced in this review. This check concerns the preserved editable-field payloads; it is not a general secret scan of every binary workspace member.

Capture 5 and the later `fpga-failure-ui.xml` are distinct captures, so they should not be described as byte-identical. Both support the verified card. The final PNG was also inspected directly from disk.

No capture in this run is rejected or reports `ROOT_UNAVAILABLE`. Thus this run does not exercise or qualify the new typed unavailable-root error path. Five successful captures cannot be used as proof that this error condition was handled on device.

## Host operations, cleanup and remaining scope

The host audit registers only `fpga-synthesize`, keeps programming unregistered and contains zero audited plan/submit/cancel requests, zero handler events and zero jobs. The artifact manifest is empty. The audit does not record GET capabilities requests, so these zeros are consistent with the successful visible pairing and are not a general network trace.

The terminal lease receipt identifies port 53178 with no remaining owned listener or reverse mapping, records `retryPerformed=false`, and returns the Companion sheet open. It reports no operation on the normal app or another device. The frozen runner's cleanup closes its own host and removes only its owned reverse mapping after checking its target. There are no cleanup errors in the run report. This review did not perform an independent live listener or mapping check.

The checkpoint circuits and private workspace archive remain context from the existing isolated E2E workspace. They are not generated FPGA artifacts. The run did not reach board import/mapping, project ZIP export, an FPGA plan or job, synthesis, compiler-error handling, cancellation or physical programming. Those flows remain unqualified, and issue #65 remains open.

No blocker found in publishing this as a failed, unqualified third attempt with a verified pairing substep, distinct source identities, masked token evidence and no claim that ROOT_UNAVAILABLE was exercised.
