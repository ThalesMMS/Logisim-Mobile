# File Provider fixture: Xcode Simulator signing preparation

Status: **SOURCE ONLY**. No replacement artifact, native bootstrap, File Provider registration, fetch or revocation is qualified by this change. Earlier native FAILs remain unchanged.

## Observed gap

The fixture's 11fc build disabled Xcode signing and then manually applied an ad-hoc signature with App Group entitlements. Its code-signature XML and DER slots are present, agree semantically, and match their CodeDirectory hashes. However, the app and provider main stubs and debug dylibs lack `__TEXT,__entitlements` and `__TEXT,__ents_der`, in both produced and installed copies. The retained build has no `ProcessProductPackaging`, `Simulated.xcent` or entitlement `-sectcreate` steps. Static archive `a033a384` preserves those observations.

The existing-group reinstall still failed registration. Its log records a new lookup returning the group and then descriptor rejection; this change does not retroactively identify that failure's cause.

## Primary pipeline evidence

[The source census](ios-fileprovider-xcode-pipeline-source-census-2026-11-05.json) retains local paths/digests and immutable upstream URLs/digests. Original source bodies remain in the local research output; they are not copied wholesale into this note.

| Primary input | Relevant behavior |
| --- | --- |
| Installed Xcode 27.2 iPhoneSimulator `SDKSettings.json` | Ad-hoc signing is allowed; identity is `-`; signing and entitlements are required; entitlement destination is `__entitlements`. |
| Installed `Embedded-Simulator.xcspec`, byte-equal to [pinned Swift Build specification](https://github.com/swiftlang/swift-build/blob/c9e5ce18b6653517a5179b7ab7e0f07c7cd49233/Sources/SWBApplePlatform/Specs/Embedded-Simulator.xcspec#L48) | Maps the XML and DER entitlement inputs to the two `__TEXT` linker sections. |
| Pinned Swift Build `Settings.swift`, lines 4033–4050, 4155–4185, 4201–4227 | Exits signing-settings calculation when signing is disallowed; otherwise computes the simulated packaging inputs and section overrides. |
| Pinned `SigningSupport.swift`, lines 94–109 | Distinguishes the Simulator section context from code-signature entitlement content. |
| Pinned `ProductPackaging.swift`, lines 61–73 | Requires the evaluated identity/signing settings before emitting the entitlement packaging task. |

The upstream source pin is not a binary identity claim for every installed Xcode build-service instruction. The SDK/spec comparison and the actual fixture file measurements support this builder correction; a new build must still demonstrate its output.

## Change and gates

The builder requests Xcode's own manual ad-hoc Simulator signing with identity `-` and no team. It removes manual signature replacement. Swift code, Info.plist structure, group entitlement source files, project specification, domain identity and callbacks remain byte-identical to 11fc.

The new verifier reads bounded thin arm64 Mach-O data and DER v1 entitlement dictionaries. It preserves each complete XML/DER pair and its real owner, checks semantic equality and the exact group, and compares the section bytes with the unique Xcode-generated packaging files. It does not pretend that a debug-dylib owner is the main executable. Unsupported encodings or missing/mismatched sections fail the file check.

Every additional generated key and value is retained for artifact review. The source entitlement files still contain exactly one group. No Team/prefix value or release signing identity is invented. `simulatorEntitlementReviewRequired=true`, `nativeExecution=NOT_RUN` and `domainRegistration=NOT_RUN` remain explicit even if build/signature/file checks succeed.

The driver and both Python readers come from the requested committed pin. The pure tests use fake SDK commands and synthetic files to exercise flags, missing/wrong sections, DER/XML disagreement, group/Team rejection, packaging mismatches, mutable-reader rejection and cancellation. They are not Xcode compilation or native behavior tests.

The next build, if separately coordinated, must use a new output directory, retain its full manifest and packaging/link logs, and receive review of actual generated dictionaries and fingerprints before any installation. No plist-nesting compatibility probe is included.

## Validation of this source change

The focused offline suite passed 23 checks: 12 binary/DER cases, nine fake-SDK builder cases and two cancellation cases. The existing configuration checker passed with native execution reported as `NOT_RUN`. A Git diff confirmed no changes to the fixture's Swift sources, Info.plists, entitlement source files or project specification.

Two independent source readers found no remaining concrete blocker in the five Python files. The retained [review record](ios-fileprovider-xcode-signing-source-review-2026-11-05.json) includes their inspected file hashes and scope. This review did not build or run the proposed fixture.
