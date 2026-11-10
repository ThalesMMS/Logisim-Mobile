# I/O R2 stopped after a simulator service abort

The authorized second call stopped before it produced any qualifying I/O evidence. It used harness `0732f74f`, the unchanged app `3a0a01f1`, bundle tree `813f1b2f…`, and the 2,497-key catalog. The preflight checked 19 Swift files, the fixture and contract, all 12 app files, and the deep, strict signature. Only the test runner and receiver were built. The installed app was reused with identical bytes.

The [crash report](triage/backboardd-2026-11-05-063406.ips) identifies `backboardd`, PID 66271, in the exact B25 simulator coalition. It records `EXC_CRASH`, `SIGABRT`, and signal 6 at 06:34:05.3038 -0300. The triggered display thread passes through Metal texture validation, `MTLSimDevice`, and QuartzCore composition.

The last Dimensions action in the [test log](run/test.log) is its owned fixture's System Open followed by `Wait Running Foreground` at t8.41s. The crash timestamp falls in that interval. No Window admission, origin verification, or complete XML checkpoint is exported. The report contains no assertion text or texture descriptor. It does not identify whether the app, harness, or recorder triggered the system failure.

XCTest then started a new runner process and announced an internal restart. The monitor sent SIGINT to its one runner process group. Signals began Set Up during that internal restart. The wrapper made one call, not another attempt. Its exit was 130. No manual fallback, recovery, or repeat followed.

## Evidence status

The [independent postflight](postflight/review-index.json) records two incomplete methods and two methods not run. There are zero qualified pairs among 18 expected XML checkpoints. It preserves the absence of an exported attachment manifest without inferring that an XML or Window receipt does not exist inside the incomplete result bundle. The result bundle has no `Info.plist`; read-only export failures are retained under `triage`.

The original first-call four failures remain in their separate receipt. This second call neither promotes them nor establishes a functional failure of the four methods. The source-only window and report gates still need a native qualification.

## Cleanup and files

The [cleanup receipt](run/post-cleanup.json) confirms that the owned runner and recorder ceased. Both the supplied and installed app retain the same 12-file fingerprint. The app on leased B25 was terminated, and the test runner was already absent. The lease was returned. No workspace, preference, other simulator, or device was erased or recovered.

The packet contains frozen harness sources, the plan, preflight, logs, crash report, incomplete postflight, and final screenshot. The last-15-minute app log is context rather than a case-specific event trace. The passive video remains at the raw path with its hash in the wrapper receipt. It is visual auxiliary evidence and has no qualified hold or clock association.

`SHA256SUMS` checks the archived file bytes with `shasum -a 256 -c SHA256SUMS` from this directory. The [manifest](manifest.json) records the single call, internal restart, process, stack, and limits. No source of the app or runtime driver changed during this archive step.
