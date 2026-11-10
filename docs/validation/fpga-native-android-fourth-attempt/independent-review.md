# FPGA native fourth failure review

Reviewed offline on 2026-11-05. Only the supplied raw files were read, parsed and hashed. No ADB, live UI, host, EDA tool, test or build was run, and no repository or raw evidence file was changed.

Evidence roots:

- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-verified-host-card/outputs/fpga-native-5c2abc60-004`
- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-verified-host-card/outputs/fpga-5c2abc60-stage-004`

## Result and provenance

The run remains terminal `FAIL`, `NATIVE_FAIL`, `NOT_QUALIFIED`. It ran on `emulator-5558` from 08:11:26.184814 through 08:12:57.677223 UTC, approximately 91.49 seconds. `results.json` records only the first selected case, `fpga-synthesis`, as failed. The compiler-error and cancellation cases did not execute.

The native harness is `51a6a01c93e2195d40c2ee98e84365b262da97e3`. The actual app/test producer is `3a0a01f1af05dd3fea75d5a1499c762d039c478a`. The integrated manifest and helper producer remain `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c`. The preserved observer SHA-256 is `8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634`; the catalog is the clean 3a checkout declared by preflight.

All eleven frozen source and fixture hashes match the run report. The native and stage build receipts are byte-identical, and the producer-manifest digest matches its copied bytes. Recomputed staged APK hashes, sizes and bytes agree with the root sources, producer manifest and preflight:

| Artifact | SHA-256 |
| --- | --- |
| E2E app | `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| E2E tests | `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Snapshot helper | `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |

The stage records restoring these approved artifacts before the attempt. In particular, its previous app/test hashes differed from the approved pair. The approved installed-after-preparation records equal the run's installed-before records, which equal the lease-return installed-after records, including paths. This attempt must not be attributed to the intervening diagnostic app/test artifacts, to ce3 from attempts 1 and 2, or to the older third-attempt harness.

## Immediate failure sequence

Pairing completed and `fpga-host-verified-ui.xml` was retained. The run then opened a scoped incoming circuit fixture. The incoming-open receipt records that its uniquely named document opened, and the final editor contains the named inputs `a` and `b`, AND gate and output `y`.

The intended board document has identity `FPGA_E2E_HX1K_08debb96`. Its generated metadata declares 535 UTF-8 bytes and SHA-256 `998c7feaa473cccf9b6b3ab2cd8876cba828f06a51733ea963327b71771fd2cf`. The saved intended XML agrees with those values.

The critical native observations precede the terminal missing-scroll assertion:

1. Capture 6 shows the FPGA sheet with an empty, focused board XML editor.
2. Captures 7 through 11 show 245 characters and bytes in that editor. Each captured value is exactly the first 245 characters of the intended board text and ends inside `<Button Label="A`, with an unterminated attribute.
3. Starting with capture 8, the product displays `Unable to import the FPGA board XML: Unterminated XML attribute.` The same rejection remains through capture 11.
4. The traceback in `fpga-synthesis-error.txt` shows `success → mapping(export=True) → validate(valid=False) → click(fpga_validate_mapping, scroll=True) → reveal → scroll_region`. The final assertion is `The active panel exposes no scrollable content`.

The first observed operation failure is therefore board XML import rejection after the captured text differs from its intended 535-byte payload. The terminal missing-scroll error is secondary. The evidence does not qualify mapping validation or synthesis, and it does not identify why the text stopped at 245 characters. In particular, this review does not assign that truncation to a specific Android input API or product callback.

The final hierarchies, `fpga-synthesis-ui.xml` and `fpga-failure-ui.xml`, show the editor without an `editor-tool-sheet` marker and with status `Operation cancelled.` Capture 12 is an earlier sparse tree with nine nodes and no field or text while the sheet marker remains. These observations bound the disappearance of the panel; they do not prove the exact swipe, Back action or keyboard event that dismissed it.

The original lease receipt's `handoffScreen` says that the FPGA tool sheet remains open. That string conflicts with the two final saved hierarchies. Preserve the raw receipt, but do not repeat its handoff-screen description as an observed fact. A derived receipt should state this discrepancy and use the final preserved editor hierarchies as its UI evidence.

## Snapshot and host limits

All twelve SDK captures are `complete`, with capture state `COMPLETE`, zero overrun and PID 19581 before and after. Their elapsed times are 72, 32, 461, 26, 55, 228, 51, 52, 36, 76, 52 and 173 ms. The raw stdout Bundles match their JSON receipts, and each saved XML matches its decoded payload. No unavailable-root failure is exercised by these captures.

The host audit has zero plan, submit or cancel requests, zero handler events and zero jobs. Only `fpga-synthesize` is advertised, programming is unregistered and the artifact manifest is empty. GET capabilities requests are outside this audit; empty job audit is compatible with the earlier verified pairing. Neither an EDA tool error nor a cancelled FPGA job occurred.

## Cleanup and acceptance scope

The lease-return receipt records owned host port 60113 with no listener or reverse mapping remaining, unchanged approved APK identities, no other device or normal app operated, and `retryPerformed=false`. The run report has no cleanup errors. This review did not perform an independent live listener or ADB mapping check. The stale handoff-screen description above does not change the recorded port cleanup or artifact identity checks.

Publish this as a separate failed, unqualified fourth attempt. Board import failed; mapping, ZIP export, FPGA plan/job submission, synthesis, tool-error handling and cancellation remain unqualified or not run. There was no native recovery or physical programming. Issue #65 remains open. The earlier three receipts keep their own sources and failure causes.
