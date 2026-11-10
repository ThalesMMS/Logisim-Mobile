# Build serialization preparation

Original source `da35a428b24ca7a5d8678558a6679963fb501319` follows scenario `0294ef60` and its separate preparation archive `ac9fffe0`. It narrows the shared compilation lock to `build-for-testing`. The native test runs directly after the wrapper completes; its device lease is independent.

The build wrapper is a checksum-pinned, unchanged `/tmp/logisim-gradle-serial.py`. A separate build session prevents an interrupt of the runner from killing the lock-owning wrapper while leaving its xcodebuild child alive. An interrupted runner waits for natural build completion, records the result and stops before XCTest. The build has no outer timeout; the test case allowance is 1,200 seconds. No real wrapper or lock was used in this preparation.

Thirty Python checks passed, including false-command cases for wrapper/build ordering, direct test execution, no Native IPC during prepare, failed builds, deferred interruption, symlink rejection and wrapper checksum mismatch. Six Swift sources passed iOS Simulator SDK typechecking after updating the case allowance. The oracle code is unchanged from the earlier 36/36 pure host checks. Independent review found no remaining blocker.

The clean-source prepare result retains Main3a, fixture601, five JSON resources and six Swift sources. Its only external processes were six read-only codesign commands and XcodeGen. Native authentication remains `NOT_RUN`; the provider's Files location remains `PENDING_RUNTIME`. No device lease is implied by these records.

```sh
python3 docs/validation/ios-provider-auth-build-serialization/verify.py
```

The replay reads archived data and original Git objects. It never runs the wrapper, Xcode or Simulator tools. This archive does not qualify issue #26.
