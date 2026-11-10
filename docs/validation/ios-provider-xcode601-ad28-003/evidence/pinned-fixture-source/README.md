# FileProvider authentication-denial fixture

This standalone validation app and FileProvider extension serve one real `.circ` item. They do not modify Logisim, its document adapter, or its App Groups. The current diagnostic source has not been built or run natively. Two earlier bootstrap attempts failed at different boundaries, as recorded below. The authentication-denial scenario remains **NOT_RUN**. Configuration checks, SDK typechecking and host storage checks do not prove domain registration or denial propagation.

The coordinated native procedure is in [QUALIFY.md](QUALIFY.md).

## Identities and configuration

| Object | Identifier |
| --- | --- |
| Control app | `thalesmms.logisim.validation.revocationfixture` |
| Extension | `thalesmms.logisim.validation.revocationfixture.provider` |
| Fixture App Group | `group.thalesmms.logisim.validation.revocationfixture` |
| Domain | `logisim-revocation-fixture-v1` |
| Document | `revocation-circuit-v1` |
| Filename | `revocation.circ` |

Both targets reference the fixture App Group. No team or provisioning profile is embedded. Device signing requires a team and profiles that authorize this group. Production groups are unnecessary.

The extension plist follows the installed Xcode iOS File Provider Extension template. It uses extension point `com.apple.fileprovider-nonui`, enumeration support, and principal class `$(PRODUCT_MODULE_NAME).RevocationFileProvider`. The SDK exposes the selected replicated APIs for iOS 16 and later. This project targets iOS 17 and later.

Existing plist and entitlement files are referenced through build settings. XcodeGen does not recreate them. Header availability does not prove registration in a particular Simulator. Registration or App Group failure remains a failed qualification with its actual error.

## Runtime behavior

`ControlApp` is a UIKit app with one declared application-scene configuration. Its scene delegate creates and retains a `UIWindow(windowScene:)` containing the control view controller. Preparing the backend, registering its domain, changing authentication and requesting eviction require explicit buttons. Startup displays existing evidence. It does not register a domain or reset an existing backend.

`Provider` implements `NSFileProviderReplicatedExtension`. Metadata and enumeration identify the same document when authentication is denied. Fetch and upload return `NSFileProviderErrorDomain / -1000`, `notAuthenticated`, through FileProvider callbacks. The fixture does not simulate a removed document.

`Shared/FixtureStore.swift` commits backend bytes, authentication and ordered events together under a process-shared `flock`. Installed targets resolve this store through their fixture App Group. An internal directory initializer lets host checks use a separate temporary directory. Neither installed target accepts a directory from external input.

The initial backend contains a two-pin circuit. Content versions use the actual bytes' SHA-256. Authorized uploads update those bytes and their generation. The store rechecks authentication under its lock before a copy or upload commit. Denied operations preserve the backend. FileProvider receives a disposable copy in its own temporary directory and never owns the backend record.

The domain supports reads and content changes to its existing file. Creation, deletion, renaming, moving and metadata-only uploads return explicit unsupported errors. It is not a general document provider. The reply lock arbitrates cancellation and upload commit. Cancellation before commit prevents mutation. Cancellation after commit starts does not replace the transaction's result with a cancellation callback.

## Checks

These commands run from this directory.

| Command | Scope |
| --- | --- |
| `python3 verify-fixture.py` | Plists, isolated identities, group agreement and literal XML. Reports native execution as `NOT_RUN`. |
| `python3 run-host-checks.py` | Builds a small macOS executable in a temporary directory. Exercises real Store reads, writes, denial, CAS, and two worker processes. No domain or UI. |
| `xcodegen generate --spec project.yml` | Generates the standalone project without building or running it. |
| `python3 test_build_simulator_fixture.py` | Exercises cancellation with a temporary Git fixture, private locks and fake child processes; no Xcode, signing or Simulator. |

Host checks verify the literal initial fingerprint, authorized upload and fetch, denied operations preserving bytes, idempotent preparation, restored reading, and outdated-upload rejection. Two processes append 80 events. Reopening the store verifies valid JSON, contiguous sequences, distinct process identities and unchanged backend bytes. These results qualify fixture storage only.

The control app and extension passed separate SDK typechecks for arm64 iOS 17 Simulator; the extension check used `-application-extension`. A later standalone build from frozen source `ff99aadb4991933774eed684dda70805de9d0055` passed ARM64 Simulator compilation and ad-hoc signing with SDK 27.2. The [build receipt](../../docs/validation/ios-fileprovider-fixture-build-2026-11-05.md) records the original driver, signed bundle fingerprints, extracted entitlements and log hashes. Preserving the builder and receipt did not rebuild those binaries.

The subsequent bootstrap attempt installed that unchanged app, then failed its only test after 36.779 seconds. The fixture process trapped in `___UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke` before the test reached Prepare or Register. The [scene-lifecycle preparation receipt](../../docs/validation/ios-fileprovider-scene-lifecycle-2026-11-05.md) records that source adjustment and its original offline checks.

Scene source `48412de3dae41e97fba38623f1dc466f41bbe441` was later built and ad-hoc signed. A second bootstrap failed after 16.147 seconds: Prepare and the control app's public App Group snapshot succeeded, then the registration callback returned `NSFileProviderErrorDomain / -2001`. Inspect was not reached. Both FAILs are preserved separately in archive commit `f2b4cde813727ee5d5a844cfbf7894bf071abc09`; neither establishes a domain count, extension execution or the cause of the registration error.

Diagnostic source `11fc44d53b229558fbfc57b66e0b2f2d11a0037b` was subsequently built and tested on AD28. Registration failed after 17.003 seconds with a retained -2001 → -2014 chain. A separately authorized exact-bundle reinstall with the group already present failed after 16.731 seconds; a new group-query result preceded descriptor rejection. Those results are preserved in archives `124d5ef4` and `a4ebb123`. Provider fetch, authentication denial and revocation remain `NOT_RUN`; no physical device was used.

## Error diagnostics

Error events retain their original `errorDomain` and `errorCode` fields and add an optional `errorDiagnostic`. Events from older databases decode without that field. The public evidence JSON includes it automatically; no button, registration or backend policy changes.

The diagnostic follows only `NSUnderlyingErrorKey`, preserving each NSError's domain, code and reason. Reason uses `localizedFailureReason`, falling back to `localizedDescription`, and is limited to 512 Unicode scalars per node. The chain contains at most eight nodes and reports `complete`, `cycle` or `depthLimit`; cycles use object identity. No arbitrary userInfo dictionary is serialized. This can retain an underlying provider error when one is actually supplied; it does not infer a missing suberror or change an error into success.

`FixtureErrorDiagnosticChecks.swift` exercises synthetic Foundation errors only. Compile it with `Shared/FixtureErrorDiagnostic.swift` and run the resulting host executable. No FileProvider framework, App Group, domain or UI is used by those checks. SDK typechecking separately checks integration with the existing app and provider sources; it does not execute either target.

The [preparation receipt](../../docs/validation/ios-fileprovider-error-chain-2026-11-05.json) records the exact commands, twelve host check groups, SDK typechecks and source/evidence hashes. It does not qualify a new signed artifact or native behavior.

## Build a pinned Simulator fixture

The current builder is a source-only correction to the fixture's Simulator setup. It has not generated a new real artifact or established registration. The [preparation note](../../docs/validation/ios-fileprovider-xcode-signing-preparation-2026-11-05.md) records the pipeline evidence and limits.

Run the builder from its exact clean committed source during a coordinated build window. It owns `/tmp/logisim-gradle.lock`, exports the fixture files from that source pin and uses a new output directory. The invoked driver must match the exported driver. Both its Simulator-section verifier and artifact reader are loaded from retained bytes of the requested commit, rather than importing a mutable worktree after the build.

After freezing the source pin, the build command is:

```sh
FIXTURE_SOURCE_PIN=$(git rev-parse HEAD)
python3 scripts/ios_fileprovider_revocation/build-simulator-fixture.py \
  --source-root "$PWD" \
  --expected-source "$FIXTURE_SOURCE_PIN" \
  --output "$PWD/outputs/ios-fileprovider-fixture-xcode-simulator-001"
```

Choose an unused output path. This driver requires a pin containing this driver and verifier; it is not an instruction to reproduce the earlier hand-signed artifacts. Their original drivers, binaries and results remain preserved separately.

Xcode now performs the ad-hoc Simulator signing itself: manual identity `-`, signing allowed/required, and an empty development team. There is no post-build signature replacement. The app and provider keep their original source entitlement files, each declaring only the fixture App Group; no source, plist nesting, domain or provider behavior changes.

The builder captures the XML and DER Simulator sections, their actual Mach-O owners and every generated dictionary key/value. XML and DER must agree and contain the exact group. A nonempty Team identifier is rejected. It also retains the unique generated `-Simulated.xcent`/`.der` inputs and verifies their bytes against the sections. Code-signature entitlement dictionaries are recorded separately and are not substituted for the Simulator sections. Generated additional keys require review before installation or native execution; `simulatorEntitlementReviewRequired` remains true.

`BUILD_AND_ADHOC_SIGN_PASS` describes the build, file-section and signature checks only. `nativeExecution` and `domainRegistration` remain `NOT_RUN`. A real artifact must be checked for its actual packaging/link steps, generated values, image owners and new fingerprints before a separately coordinated bootstrap.

SIGINT/SIGTERM record `INTERRUPTED` and return 130. Interruption while waiting for a lock records no acquired lock or build command. An active child command runs in its own process session; cancellation terminates that owned process group before the driver releases its lock. Pure tests substitute private locks, fake SDK tools and synthetic Mach-O data; they do not acquire the global lock, call Xcode or touch a Simulator.

Run the focused offline checks from the repository root:

```sh
python3 -B -m unittest discover -s scripts/ios_fileprovider_revocation -p 'test_*simulator*.py'
```

## Limits and recovery controls

**Allow authentication** updates fixture state and calls `signalErrorResolved` for `notAuthenticated` on its domain. It does not register a missing domain. **Evict item** requests eviction without changing authentication. **Deny authentication without eviction** is diagnostic: materialized content may remain readable. Domain removal is absent from the control app.

Eviction is asynchronous. New reads can materialize content again. A completed eviction followed by a denied system request is required evidence. The authentication toggle alone is insufficient.

A provider upload rejection can arrive after a local filesystem save succeeds. That behavior does not satisfy an immediate editor-error assertion. File coordination and the adapter's pre-write read may expose denial synchronously, which remains unobserved here.

This fixture targets provider authentication denied after an earlier native selection. It does not establish revocation of a kernel security-scope token. A false `startAccessingSecurityScopedResource()` result, a missing file, a disabled domain, POSIX mode changes, or an adapter-injected error is a different condition. No custom FileProvider Simulator runtime is qualified by this source delivery, and issue #26 remains open.
