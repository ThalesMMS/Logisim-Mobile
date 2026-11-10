# FPGA native first failure review

Reviewed read-only on 2026-11-05. No ADB, live UI, host, EDA tool, test or build was run. Only the supplied files were read, parsed and hashed. No repository file was changed.

Evidence roots:

- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-preflight/outputs/fpga-native-ce3e061d-001`
- `/Users/thales/GitHub/logisim-mobile-worktrees/fpga-native-preflight/outputs/fpga-ce3e061d-stage-001`

## Result and scope

The recorded native run is correctly terminal `FAIL`, `NATIVE_FAIL`, `NOT_QUALIFIED`. It selected synthesis, tool-error and cancel, but failed during pairing preparation before any of those cases executed. It does not qualify mapping, project generation, synthesis, compiler errors, cancellation, physical programming or issue #65.

`fpga-run.json` identifies product source `ce3e061d38cd9851038d1cd8a71557ede9add641`, harness source `219edebd298d62f4bb12eb0e842fb09e5577c47b`, serial `emulator-5556`, Android 15 emulator fingerprint and English locale. The elapsed timestamps are 06:08:33.712051 through 06:08:55.001737 UTC.

## Failure observation

The preserved hierarchy contains exactly two enabled editable fields in the Companion sheet. The address contains the default `http://127.0.0.1:8042`. The confidential field is empty, has `password=true`, is focusable and clickable, and has bounds `[74,1937][1006,2105]`.

The confidential EditText itself has an empty `content-desc`. Its child View has the public description `Pairing token, confidential field` and the same bounds. A child TextView also displays `Pairing token`. The frozen driver, `android_fpga_scenarios.py:249-251`, filters EditTexts by their own `content-desc`, so it rejects this actual hierarchy before entering an address or token or pressing Pair. This is a selector mismatch. The evidence does not show an absent secret field or a product pairing rejection.

The run's original error type is `AssertionError`. The separate failure error text says `RuntimeError` because the frozen runner explicitly wraps the error in `RuntimeError(message)` when collecting failure artifacts. Both retain the same message, `Pairing must expose one confidential token field`; this difference does not indicate a second product failure.

## APK and source provenance

Recomputed hashes and sizes of the staged APKs match the copied source files, `preflight.json`, the producer manifest and the installed-before records:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| App | 16,298,862 | `71bafede4e6c9c5ea637d5ad0bb7cce5e366b0842b8f06d32d34143db2277a43` |
| Test | 138,810 | `c439cef16d4f9ccced20189d12b22b731449b17c5417dbe25d65167ad7e3d882` |
| Snapshot helper | 951,790 | `2f17d8af178460537e2e4106d8ea79063fa938b0fdebcae1710b9b0294fee387` |

The app is `thalesmms.logisim.mobile.e2e`; the test instrumentation targets that app. The snapshot helper is `thalesmms.logisim.mobile.snapshot.e2e.test`, and both of its declared instrumentations target that helper. The normal app is not one of these identities.

The staging installation record documents replacing an earlier isolated app hash `67e81d69…` with this app hash. The test and snapshot helper already matched. The three staging after-install records are identical to the run's installed-before records, which are in turn identical to the lease-return installed-after records, including APK paths and hashes. Thus the recorded run and handoff retain the staged triplet. This is not a claim that the pre-staging app was already identical.

The run and staging build receipts are byte-identical, SHA-256 `07291cc39f0285bcc3fea55c0eb910fbc0a89fb78de4a7a6a194bf268b433be1`. Their producer-manifest digest is `921779a60dffc5b5c2bb35b444749a26cc6fd138308263519069164f5143eecb`, which matches the actual copied producer manifest.

All eleven frozen source/fixture files match the SHA-256 values in `fpga-run.json.sources`.

## Host and cleanup

The separate `companion-audit.json` equals the audit embedded in `fpga-run.json`. It registers only `fpga-synthesize`, marks programming unregistered, and records zero audited plan/submit/cancel requests, zero handler events and zero jobs. `companion-artifacts/manifest.json` is an empty list. There is no FPGA execution or generated artifact to approve. The audit does not instrument capabilities GET requests, so zero audited requests should not be described as a general network trace.

The frozen runner's `finally` closes its own host and removes only its owned reverse mapping after checking the target. The recorded report contains no cleanup errors. `lease-return.json` records port 57931 with no remaining own listener and no remaining own reverse mapping, keeps the Companion pairing sheet open as the handoff screen, and declares no other device or normal app touched. These are consistent with the cleanup source and APK handoff records. The review did not perform an independent live check of listener or ADB state.

Failure collection reports no collection errors. The private-files TAR is attributed to the isolated app, contains 17 members and 14 regular files, and has no symlinks, absolute paths or traversal members. The five extracted XML checkpoints represent two stored workspace document histories. They are failure preservation evidence, not a claimed before/after FPGA acceptance sequence.

No blocker found in publishing these files as an explicitly failed and unqualified first attempt, with the selector mismatch and remaining cases stated above.
