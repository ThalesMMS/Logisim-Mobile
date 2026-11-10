# AD28 FileProvider diagnostic: preserved FAIL

The one authorized diagnostic attempt remains **FAIL**: one test, one failure, zero passes/skips, 17.003 seconds and `xcodebuild` exit 65. It observed a real Prepare followed by a failed Register callback. It did not reach Inspect or qualify `BOOTSTRAP_REGISTER`.

Fixture source was `11fc44d53b229558fbfc57b66e0b2f2d11a0037b`; the unchanged bootstrap flow used provenance pin `6630378c89a0c9e0a6b15ee8b6ec275a5cd1aaca`. The [receipt](receipt.json), [raw execution record](evidence/execution.json), [test log](evidence/test.log) and [local xcresult summary](evidence/xcresult-summary.json) retain the failure. The summary was extracted from the existing local result bundle during archiving, without a device query.

## Public observations

The [prepared UITextView value](evidence/attachments/41FFAB13-A6FD-499D-BD4E-13AA4EFEB868.txt) records event 1 `prepare`, generation 1, authentication allowed, 552 bytes and SHA-256 `54c247e7241ee56e77fdb1958ac3a6945b4e5c7a63b59c90a0d20ba9ae362d88`. The signed [store source](evidence/fixture-source/Shared/FixtureStore.swift) uses the fixture App Group for these reads/writes. This establishes the control app's storage access, not extension execution or cross-process sharing.

The [operation-failed value](evidence/attachments/47D5794A-6AFF-40A3-AB0C-D9CD97C215B1.txt), lines 35–55, and identical [terminal value](evidence/attachments/832BD07F-97F9-4923-9467-67FF79FBB3D9.txt) retain:

```text
NSFileProviderErrorDomain / -2001
  → NSFileProviderErrorDomain / -2014
termination = complete
```

Events 2 and 3 are `domain.registration.requested` and `domain.registration.failed`, from fixture PID 69225. The failure snapshot has exactly the prepared backend state. The root message remains “The application cannot be used right now.” `complete` means the bounded serializer reached the end of its recognized `NSUnderlyingErrorKey` chain; it does not mean a complete causal explanation or capture of every userInfo value.

Public accessibility trees, six screenshots and their original attachment mapping are included. The text values preserve the complete captured chain; no assertion depends on all JSON being visible at once in a screenshot. No Inspect, fetch, authentication change, Save or retry was performed. No domain count was observed.

## Exact error classification

SDK 27.2 `NSFileProviderError.h:181–185` defines `-2014` as `NSFileProviderErrorApplicationExtensionNotFound`. Its comment specifies that the system has no **launchable** `com.apple.fileprovider-nonui` extension for the domain's app bundle. The availability macro at `NSFileProviderDefines.h:39` declares iOS 17.1/macOS 14.1. Numbered source lines and whole-file hashes are preserved in [sdk-numbered-extracts.txt](sdk-numbered-extracts.txt).

Apple's [applicationExtensionNotFound page](https://developer.apple.com/documentation/fileprovider/nsfileprovidererror/code/applicationextensionnotfound) describes the missing-extension category. Its [March 2024 File Provider updates](https://developer.apple.com/documentation/updates/fileprovider) identify this as an underlying error category of `providerNotFound`; the [root case documentation](https://developer.apple.com/documentation/fileprovider/nsfileprovidererror/code/providernotfound) describes provider discovery failure. URLs and access date are in [primary-sources.json](primary-sources.json).

The supported classification is **a system-reported provider discovery/launchability failure**. It does not establish physical absence of an embedded `.appex`, invalid signing, lack of Simulator support, or the reason the system regarded no extension as launchable. The producer and archived installation records include the embedded provider's identity. No runtime registry query was performed for this archive; any subsequent discovery investigation is separate evidence.

## Identity, cohort and scope

Recorded preflight and postflight agree on AD28 UUID `AD28C68F-21D3-4FA6-BCD7-383AE2A1D372`, the iPad Pro 11-inch M5 model and iOS 27.0 runtime (24A434). Installation targets that same UUID. App tree `8b2577db…` and provider tree `d6e6ee49…` match manifest SHA-256 `bda24c74410814276f00474a689ae95508deb7330cc0eacae9f55bf54ec7388a` before installation and in the installed/postflight records. Extracted entitlements and signature/build logs are included. The recorded cleanup terminated only the fixture, exit 0. The procedure installed no main app and made no main-app qualification assertion.

The earlier B25 failures remain **FAIL** in original archive commit `f2b4cde813727ee5d5a844cfbf7894bf071abc09`; a [reference receipt](evidence/previous-failures-reference.json) is included. The second B25 attempt used fixture `48412de3` and did not retain an underlying chain. AD28 is a different cohort, with fixture `11fc44d5` adding diagnostic capture. The harness source comparison is only a four-literal provenance delta. These changes prevent a causal A/B conclusion: AD28's `-2014` cannot be assigned retrospectively to B25.

Successful registration, extension execution, provider fetch, denial, eviction, Save, security-scope revocation and physical-device behavior remain unqualified. No new patch, build, signing, live query, retry or configuration change occurred during archiving. Original outputs remain unchanged.

## Portable verification

```sh
python3 verify-archived-evidence.py
```

The standard-library verifier reads this folder only. It checks the checksum census, copied records, failure counts, exact diagnostic chain, preserved backend state, source/provenance, pre/post identities, entitlements, cleanup target, screenshots and prior harness comparison. Historical host/Simulator paths are metadata and are never followed. [inventory.json](inventory.json) records each original source and included/excluded attachment. Raw xcresult bundles, video, binary automation snapshots and executable bundles are excluded.
