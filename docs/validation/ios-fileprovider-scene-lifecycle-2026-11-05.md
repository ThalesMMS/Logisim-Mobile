# FileProvider fixture scene-lifecycle preparation

The isolated fixture control app now declares one application-scene configuration and creates its window with `UIWindow(windowScene:)`. The scene delegate retains the window and presents the same control view controller. The provider, backend, accessibility identifiers, entitlements, App Group, target settings and production app are unchanged from `c8c8185e5a644cd28ed12411d01c6fa079b3b212`.

The [machine-readable receipt](ios-fileprovider-scene-lifecycle-2026-11-05.json) records source fingerprints, commands, local evidence hashes and the prior signed artifact. The controller body is byte-identical to the baseline, and the app plist differs only by `UIApplicationSceneManifest`.

## Offline checks

- SDK 27.2 typecheck passed with warnings as errors for arm64 iOS 17 Simulator. This checks source; it does not build or run an app.
- Configuration checks passed for the single scene, bundle identities, App Group agreement and initial XML.
- XcodeGen generated a project in a copied staging directory. Staged inputs remained unchanged and both targets retained their plist and entitlement references. No Xcode build ran.
- Existing storage checks passed on the host, including two worker processes and 80 events, using temporary storage. They did not access the installed fixture's App Group or a FileProvider domain.
- Independent read-only review found no source or isolation blockers.

The new app build, native launch, registration, provider fetch, authentication-denial and revocation gates are **NOT_RUN**. A new signed artifact and its fingerprints are required before a separate bootstrap-provenance update. The existing bootstrap pins remain untouched.

## Preserved bootstrap failure

The prior B25 bootstrap used the frozen `ff99aadb4991933774eed684dda70805de9d0055` app and test source `10099e21e2f46d811effd495c3f28bce34b10172`. It remains **FAIL**: one selected test, one failure, 36.779 seconds, `xcodebuild` exit 65. The failed assertion waited for `fixture.evidence` before the first Prepare tap; neither Prepare nor Register was reached.

The fixture's own process, PID 11986, trapped with `EXC_BREAKPOINT / SIGTRAP`. The first frame on faulting thread 0 was `___UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption_block_invoke`, incident `33602B25-5EFA-4793-9A34-B55E46653E99`. The old source created an app-delegate window with `UIWindow(frame:)`, and its plist did not declare scenes. The new source adopts the scene lifecycle; its runtime outcome remains untested.

The original IPS, test log, execution record, installation verification and signed artifact were read without modification. Their paths and SHA-256 values are in the receipt. The frozen app tree remains `b56a12ba0f9e7c8aaefa36e62a981ea24694c5518697162d2014b0d144cb4974`; the provider tree remains `06ea5a3e6a30ed9a20ebc84d40b180682487a5718481069ca44facbf4bf92b81`. This source preparation neither rebuilt those binaries nor changed their failure result.

This fixture-process launch trap is separate from the earlier `backboardd` compositor failures. It establishes no shared cause and qualifies no provider-denial behavior.
