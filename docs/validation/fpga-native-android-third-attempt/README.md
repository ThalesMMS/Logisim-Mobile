# Third Android FPGA attempt reaches the verified-host card

This single authorized run on `emulator-5558` ended **FAIL / NATIVE_FAIL /
NOT_QUALIFIED** after 24.58 seconds. It completed real companion pairing but
stopped before importing the FPGA circuit or submitting a synthesis job. No
further native attempt was made.

## Source and artifact identities

| Item | Identity |
| --- | --- |
| Native harness | `5912ff5029d5f4aabecd175112bfc386d8c5337b` |
| Harness base | `2d4d417f2b170a86f7d3a7b4ab75ad1cd87872c4` |
| Actual app/test producer | `3a0a01f1af05dd3fea75d5a1499c762d039c478a` |
| Integrated manifest and new snapshot helper | `5c2abc607d3a734cf8f499c7b3690ba9ffacbc1c` |
| Preserved Android observer SHA-256 | `8afbbc3514a2dcc33939f4c7a4bc9ec1674b54c2eb4610a5764336238057a634` |

The four original FPGA test-preparation commits were applied on the current
base in a new worktree. The current observer stayed byte-identical to 5c.
Production inputs and localization were independently compared between the
actual 3a producer and 5c, with no differences. The clean catalog checkout
remained at the actual app producer, 3a. These identities do not reattribute the
two earlier ce3 failures to this product or observer.

Before installing or using UI, all three APK copies passed size, SHA-256,
compiled package and instrumentation-target checks. The supplied app, tests and
helper were then installed only on 5558 and verified again. No product build
was performed. The first local preflight command omitted the scripts import
path; its preparation note records that error, which occurred before ADB/UI.
The unchanged copies passed the completed preflight with that path configured.

| APK | SHA-256 |
| --- | --- |
| E2E app | `10e2b026ad2a398d86a50d12cdb9499b6a03fb89c507cc8a219143f917956973` |
| E2E tests | `19ab4b9d97f740464e77a6754c8afcf3c1b80d86054f30a28af20ce472f0aa2b` |
| Snapshot helper | `504cbdf78311ede3955cefc748b29e76b3f5f600aa5de25e5974660e9dab3d3c` |

## Observed progress and failure

The native trees show the address changing from its 21-character default to the
22-character owned endpoint. The confidential field received 64 masked bullets;
it contains no raw hexadecimal token in the retained trees. The final screenshot
and hierarchy show the public connected-host card:

- The companion host is verified.
- Protocol 1, input limit 4,194,304 bytes.
- `fpga-synthesize: available`.
- The public `Disconnect host` action.

`AdditionalEditorTools` replaces `CompanionPairingPanel` with this card after its
connection callback. The driver instead searched for `companion_ui_verified`,
the notice owned by the disposed form. The expected text was absent. Its attempt
to reveal that text rejected the connected card because its complete content
already fit and exposed no scrollable range. This is a driver selector failure;
the evidence does not show a failed pairing or a broken scroll control.

All five SDK captures completed with PID 18293 before and after:

| Capture | SDK elapsed | Overrun |
| --- | ---: | ---: |
| 1 | 116 ms | 0 ms |
| 2 | 26 ms | 0 ms |
| 3 | 526 ms | 0 ms |
| 4 | 81 ms | 0 ms |
| 5 | 156 ms | 0 ms |

No `ROOT_UNAVAILABLE` result occurred, so this run does not exercise recovery
from that typed failure. Snapshot 5 and the later failure hierarchy are separate
captures; both show the verified card and are retained independently.

The host audit contains no plan, submit or cancel request, no handler event and
no FPGA job. GET capability requests are outside that audit. The observed
authenticated capability card therefore does not contradict the empty job audit.
Mapping, ZIP export, HDL upload, synthesis, tool errors, cancellation and physical
programming remain unqualified by this attempt. Collected workspace files and
checkpoint circuits belong to the existing E2E workspace, not FPGA results.

## Cleanup and driver correction

The owned host on port 53178 closed and its reverse mapping was removed. Post-run
checks confirmed both, and all three installed APK hashes remained unchanged.
The 5558 lease returned. Neither 5556 nor a port 8042 listener was operated.
No programming operation, programmer, cable or physical board was used.

The app retains its verified connection card after Close. Before a future
authorized pairing, the public `Disconnect host` action must remove this
residual test connection. No such action or new UI was performed after the lease
was returned.

The source correction now requires `additional_companion_verified` and the exact
`additional_companion_operation` for available `fpga-synthesize`. It does not
change the app, observer, timeout or backend. Its regression failed before the
correction with the same missing-scroll error; all 40 focused checks passed
afterward. The corrected flow remains **Native NOT_RUN**. The green log's
temporary `FPGA FAIL` line belongs to the mocked cleanup-negative check, not
another native run.

The complete native output and non-APK preflight files are copied byte for byte.
`copy-chain.json` records their origins, sizes and hashes. Raw Android logcat
spacing is preserved by the local attribute exception for that single file;
historical log entries remain context, not workflow timing measurements.
The first two failures remain in their separate immutable receipts. Issue #65
stays open.
