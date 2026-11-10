# Compose beta integration review

Scope: candidate `f739a5c697a601c7647bee75f7b8f2abb8775618` versus main `f938ccca9172851121277b7bacf74a99abdaa70e`. This review covers dependency and SDK integration for #13/#17. It does not repeat the product audit, run an application, or prepare a release.

## CI and current instructions

The existing CI installer supplied Android API 37.0 only. Candidate androidApp and composeApp require API 37.1 through the expanded compileSdk release block, because the Compose beta AAR metadata requires it. Core and androidSnapshot still require API 37.0. The installer now requests both platforms while retaining Build Tools 36.0.0. All four SDK discovery tests pass, including an SDK path containing spaces and versioned command-line tools. These tests invoke a temporary stub sdkmanager; this review does not download an SDK or run a CI build.

The current CI toolchain paragraph now names Compose 1.13.0-beta02 and both Android platforms. Historical receipts, the initial SAP-1 validation's Compose 1.12.2 environment and the original license audit at `7ef4187` remain unchanged. No other literal dependency-version assumption was found in the CI scripts or workflow by the independent source review.

## Maven inventory and notices

The existing metadata-only `exportLegalRuntimeDependencies` task executed in 19 seconds with exit 0. It resolved Android runtime and both iOS ARM64 production configurations, with 245 component coordinates instead of the old snapshot's 233. The existing notices generator refreshed the inventory and both generated notice outputs. Its check, four legal regression tests and source checks pass. See [the Maven receipt](compose-beta-maven-notices-2026-11-04.json).

The generator's normal `--check` compares generated text with the committed inventory. It does not independently resolve Gradle coordinates. Therefore its passing result before this refresh did not prove the old 1.12.2 inventory matched the beta graph. The existing metadata collector and `--refresh` command remain the update procedure; no new release or general validation framework was introduced.

Native Skia licensing is separate from the Apache metadata declared by Maven POMs. The resolved Skiko version changed from 0.150.2 to 0.156.1. Its focused native provenance delta is recorded separately; a passing Maven refresh does not classify the entire native binary as Apache-2.0 or establish distribution compatibility.

## Identity and distribution policy

`app.properties`, generated Apple identity settings, Android manifest and iOS project settings have no changes between the two reviewed revisions. The identity generator check passes. Android minSdk remains 26, application targetSdk remains 36 and iOS deployment target remains 16.0. Both application identifiers remain `thalesmms.logisim.mobile`; version 0.2.0 and build 2 remain unchanged.

Issues #62 and #63 were read during this review and both remain closed with reason `not_planned`. No signing team, distribution credentials, release preflight, upload or publication was requested or executed. The source-only CI and notice updates preserve that decision.

## Focused native notice delta

Skiko 0.156.1 selects Skia `m156-6416302ae5`. The Skiko NOTICE and Apache text and Skia BSD text remain byte-identical; ICU's complete upstream notice changes. The official old and new iOS Release arm64 archives were verified against their release digests. Their library lists change from 27 to 31 with no removals. The four additions are the Chromium PartitionAlloc allocator/base/shim and raw-pointer archives. Their pinned BSD notice is now bundled.

The pinned PartitionAlloc source also contains Apple-derived declarations under APSL-2.0. Its upstream metadata, original copyright comments and license are retained conservatively. The inspected release shim contains only its empty object in arm64 and arm64e slices, matching the disabled-shim GN branch. Source presence and archive membership do not establish final app linkage or license compatibility. The source notices do not classify the whole app as APSL or declare distribution readiness.

The combined 245-coordinate Maven inventory and native provenance were passed through the existing generator. Generated notices, all four legal tests, identity and source checks passed; all 13 recorded native text hashes match their local files. See [the directed native receipt](beta-native-notice-delta-2026-11-04.json) for pinned source URLs, archive hashes/members and the exact limits. No build, application execution, upload or release preparation ran during this integration review.
