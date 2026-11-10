# Provider authentication runner

`scripts/run-ios-provider-auth.py` prepares one isolated XCTest target for a Files grant followed by a real File Provider authentication denial. The default command reads local artifacts, records signatures and Simulator entitlement sections, stages six Swift sources and five JSON resources, and runs XcodeGen source generation. It does not invoke `simctl` or `xcodebuild`.

The current delivery is source preparation. No authentication run is authorized by the fixture's earlier artifact review. A future passing case would cover provider authentication denial after a grant. It would not prove revocation of the kernel security token, cloud behavior, physical devices, or completion of issue 26.

## Prepare the target

Run from a clean checkout after all six Swift sources exist. Use a fresh output directory.

```sh
python3 scripts/run-ios-provider-auth.py --output /absolute/path/to/new-auth-preparation
```

The result is `PREPARED` with `native_execution_gate=NATIVE_NOT_RUN` and `qualification_gate=NOT_QUALIFIED`. A failed fingerprint, missing source, changed resource, or failed source generation returns a nonzero exit code. Preparation records a dirty checkout but cannot authorize execution from it.

The runner pins Main source `3a0a01f1af05dd3fea75d5a1499c762d039c478a` and fixture source `601cc3733be5aafe4e4d6ef5b565ffb337eeb1cd`. `ProvenanceMainArtifact.json`, `ProvenanceSignedFixture.json`, and `ProvenanceArtifactReview.json` are byte-exact producer and review records. The review permits the fixture's two exact Simulator entitlement dictionaries for its historical bootstrap. It is not an Apple provisioning profile, a certificate, approval of a Developer Team, release approval, or authorization of this authentication test. The producer's review-required fields remain unchanged.

The catalog comes from the approved Main source and contains 2,497 keys. The target stages these sources without changing the shared files:

| Source | Owner path |
| --- | --- |
| `NativeUIHarness.swift` | `scripts/ios_native_ui/NativeUIHarness.swift` |
| `NativeFilesDestinationNavigator.swift` | `scripts/ios_e2e_ui/NativeFilesDestinationNavigator.swift` |
| `ProviderAuthenticationEndToEndTests.swift` | `scripts/ios_provider_auth_ui/` |
| `ProviderAuthenticationOracle.swift` | `scripts/ios_provider_auth_ui/` |
| `ProviderFixtureController.swift` | `scripts/ios_provider_auth_ui/` |
| `ProviderFilesNavigator.swift` | `scripts/ios_provider_auth_ui/` |

There is no ClipboardReceiver, XML paste helper, System Open route, or text-entry fallback in this target. The case reads XML through the editor's Edit XML action. It controls the fixture through its public UIKit buttons and reads its displayed evidence. Test actions do not write project storage or the fixture App Group directly.

## Run configuration

Preparation writes `Run.json` with the following fields. Main and fixture identity objects contain the pinned source and artifact hashes.

```json
{
  "schemaVersion": 1,
  "runID": "012345abcdef",
  "receiptDirectory": "/absolute/output/receipts/012345abcdef",
  "simulatorUDID": null,
  "main": {},
  "fixture": {},
  "fileName": "revocation.circ",
  "providerDisplayName": "Logisim Revocation Fixture",
  "initialSHA256": "54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88",
  "inputLabel": "REVOCATION_INPUT",
  "rootLease": null
}
```

The initial file is 552 bytes. The fixture owns the stable domain `logisim-revocation-fixture-v1` and item `revocation-circuit-v1`. Its App Group is `group.thalesmms.logisim.validation.revocationfixture`. `receiptDirectory` is for test evidence only. XCTest attachments remain required evidence; an unwritable evidence directory must not cause a silent fallback to project or App Group storage.

## Future execution admission

`--execute` is disabled unless the caller supplies an explicit Simulator UUID, a twelve-digit hexadecimal run ID, and a separate ROOT lease. The output directory must be fresh. The lease must bind the exact output path, UUID, single selector, both artifact identities, clean harness commit, and every source hash returned by `source_state()`.

| Lease field | Required value |
| --- | --- |
| `schemaVersion` | `1` |
| `authority` | `ROOT` |
| `nativeAuthorized` | `true` after separate authorization |
| `scope` | `LOCAL_SIMULATOR_MAIN_PLUS_PROVIDER` |
| `operation` | `MAIN_PLUS_PROVIDER_AUTH_AFTER_GRANT` |
| `leaseID` | Nonempty recorded ROOT lease identifier |
| `simulatorUDID` | Exact explicit command-line UUID |
| `runID` | Exact command-line run ID |
| `outputDirectory` | Resolved fresh output path |
| `selector` | `ProviderAuthenticationEndToEndTests/ProviderAuthenticationEndToEndTests/testAuthenticationDenialAfterGrantPreservesProject` |
| `main`, `fixture` | Exact `preflight.json` identity objects |
| `harnessCommit`, `harnessSources` | Exact clean `source_state()` values |

The future caller must hold the separate lease for the explicit Simulator. Do not wrap the complete runner in the shared build lock or acquire that lock before calling the runner. The runner serializes only `build-for-testing`, through `/tmp/logisim-gradle-serial.py` and `/tmp/logisim-gradle.lock`. The wrapper's child command is the isolated `xcodebuild` build. After that wrapper returns, `test-without-building` runs directly, outside the build lock. There is no nested lock acquisition.

Before the build, the runner requires the wrapper to be a regular file without a symlink and to have SHA256 `61fb2ff0e8e1969b80c2880424683834a5dcd3cdcf79450a40dba15dc4f6471b`. It retains the wrapper bytes and records the lock path, checksum, and wrapper completion in the output. The reviewed wrapper holds its exclusive lock until the child build returns. Preparation neither runs this wrapper nor opens the shared lock file. This build lock does not replace the Simulator lease.

The build has no outer subprocess timeout. Killing only the wrapper could leave its `xcodebuild` child running after the lock closes. The runner waits for the wrapper's zero exit status before starting XCTest; a failed build stops the case. The test subprocess remains bounded by the configured test allowance plus 180 seconds. The proposed 1,200-second test allowance does not impose a build timeout.

Only the build wrapper uses `Popen` in a separate session. If the runner receives `KeyboardInterrupt` while waiting, it records a pending interruption and continues waiting for the wrapper to finish naturally. It then records the exit code and propagates the interruption before checking resources or starting XCTest. Other commands keep their existing subprocess behavior. This cooperative cancellation path does not kill the wrapper or its build child.

The admitted execute path requires the specified Simulator to be booted. It checks installed application IDs, then verifies the existing fixture app and provider against the approved complete bundle fingerprints. Missing or divergent fixture bytes stop the run. The runner never installs, refreshes, unregisters, or erases the fixture or its domain. An exact installed Main is reused. An absent Main can be installed under the new lease; an existing divergent Main stops the run. This integration changes the previous UIKit-only test context to Main plus Provider.

Execution builds only the isolated UI test target. Before starting the single case, the runner compares all five JSON resources in the built test bundle against their staged bytes. It records installed Main, fixture, and provider fingerprints before and after the test. Success requires one test, one pass, zero failures, zero skips, the exact executed test identifier, and matching installed fingerprints. Even that selected-case success records `qualification_gate=PARTIAL`.

## Offline checks

```sh
python3 -m unittest discover -s scripts -p test_ios_provider_auth_runner.py -v
python3 -m py_compile scripts/run-ios-provider-auth.py
```

These checks test command admission, lease bindings, resource packaging, and local evidence handling. They do not establish a Files grant, provider discovery, authentication denial, or Native UI success.

The pure Oracle can run as a macOS executable in a temporary directory:

```sh
authCheckDirectory="$(mktemp -d)"
swiftc scripts/ios_provider_auth_ui/ProviderAuthenticationOracle.swift \
  scripts/ios_provider_auth_ui/tests/ProviderAuthenticationOracleChecks.swift \
  -o "$authCheckDirectory/oracle-checks"
"$authCheckDirectory/oracle-checks"
```

The following command checks all six Swift sources against the installed Simulator SDK. It does not build or launch either app.

```sh
xcrun swiftc -typecheck -swift-version 5 -target arm64-apple-ios17.0-simulator \
  -sdk /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator27.2.sdk \
  -F /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/Library/Frameworks \
  -I /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/usr/lib \
  scripts/ios_native_ui/NativeUIHarness.swift \
  scripts/ios_e2e_ui/NativeFilesDestinationNavigator.swift \
  scripts/ios_provider_auth_ui/ProviderAuthenticationOracle.swift \
  scripts/ios_provider_auth_ui/ProviderAuthenticationEndToEndTests.swift \
  scripts/ios_provider_auth_ui/ProviderFixtureController.swift \
  scripts/ios_provider_auth_ui/ProviderFilesNavigator.swift
```
