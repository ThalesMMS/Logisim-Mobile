# Licensing and distribution

November 4, 2026 audit. The mobile baseline is `7ef4187`, and the desktop reference is `fbb1eca6ff72cb634b8dd1d72dc232b9838c9e01`. This document records the applicable conditions and the evidence available at the time. Distribution readiness and store decisions require separate verification.

## Code and materials

The app retains the GPLv3 license in [LICENSE.md](../LICENSE.md). It is an independent port that adapts Logisim Evolution's behavior, geometry, and file format. The license choice applies across the change in implementation language. The notices identify the port's sources and modifications and attribute the port's results to its own maintainers.

The SAP-1 example retains its original MIT license, with attribution to Thales and source pinned to commit `ddb9dd86d601ac217157beedeb88464ee0128c87`. The complete copy and the license text are in `examples/`.

The inventory has three complementary records.

- [Code and material provenance](../legal/provenance.json)
- [Production dependencies resolved by Gradle](../legal/runtime-dependencies.json)
- [Native dependencies and sources of license texts](../legal/native-license-sources.json)

Gradle resolved 233 Maven components for Android and iOS. Their POMs declare Apache-2.0, including the license Guava ListenableFuture inherits from its parent POM. Those declarations cover the Maven components. Skiko 0.150.2 uses the native Skia package `m150-a3f509bf52`, whose distribution archive contains additional libraries and licenses. The app also bundles their full license texts.

The Adobe DNG license has its own conditions, including a clause for commercial product distribution. The Skia package contains a static archive, and determining which object files actually made it into the final app requires separate evidence. The audit retains the notices and leaves DNG inclusion and license compatibility open pending that evidence. Skiko's POM alone does not settle either question.

The audited source used code-defined circuit geometry and platform fonts. The audit found no external fonts or images. The App icon entry in the provenance inventory identifies the original geometric project icon, its generator, and its Android and Apple derivatives by path and SHA-256 under GPL-3.0-only. The generator constructs the artwork from shapes and uses no external fonts, images, or upstream artwork.

## In-app notices

`LegalNoticesButton()` opens the notices and full license texts offline. The panel includes rights, the disclaimer of warranty, the date modifications began, attributions, and dependencies. The generated text in `LegalNoticeContent.kt` corresponds to the files in `legal/`. When `app.sourceReleaseUrl` is configured, the panel also links to the source for that version and build.

Integrators must expose this button in the app's help or toolbar. Verify that users can reach it in the host being distributed.

<a name="canais-e-condições"></a>

## Distribution channels and terms

Issues #62 and #63 were closed as `not planned` on 2026-11-04. On 2026-11-10, the maintainer indicated plans to create a public source repository and initially distribute the iOS app through TestFlight only. Preparing the source and credits is separate from deciding whether the channel's terms are suitable.

| Channel | Decision and status |
| --- | --- |
| Repository and source archive | GPLv3 with notices preserved and the exact commit identified. |
| Direct Android APK | No release is planned for this work. The debug APK remains a development and test artifact. |
| Google Play | Outside the plan for this work. Account, key, channel, and publication decisions are open. |
| iOS through Xcode | Local builds and installs verify those steps. Distribution approval is separate. |
| TestFlight | Planned beta channel. Channel compatibility and inspection of the signed IPA remain pending. |
| App Store | Publication is outside the initial plan. |

The Android documentation requires APK signing and an upload key for App Bundles. The debug key is for development. Keep release keys and passwords outside the repository. [Android app signing documentation](https://developer.android.com/studio/publish/app-signing)

The Apple Developer Program License Agreement requires compliance with FOSS licenses in section 3.3.4(A)(v). Exhibit B, item 2, also places conditions on the scope of the end-user license. Section 10 of GPLv3 prohibits additional restrictions on the rights it grants. Assess the channel's terms alongside the rights available for each app. [Apple Developer Program License Agreement](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/)

The TestFlight terms for Brazil limit beta use and restrict copying, modification, and transfer. Providing access to source addresses the GPLv3 section 6 obligation. Distribution to external testers still requires resolving those restrictions under sections 10 and 12, for example through a written exception covering TestFlight from the rights holders of the affected materials. Apple's internal testing mode is limited to the organization itself. Copies for use within the same entity are subject to the provisions of GPLv3 section 2. Using Apple as an intermediary under sections 6.6 and 7.4 of the agreement requires a separate analysis. The audit recorded no applicable exception. [TestFlight terms for Brazil](https://www.apple.com/legal/internet-services/itunes/testflight/br/terms.html)

The App Review Guidelines consulted for this audit showed an October 16, 2026 update. Rule 2.2 applies the guidelines to TestFlight. Rule 2.5.2 provides a limited educational exception for executable code and requires that the source be visible and editable. Arbitrary loading of desktop JARs falls outside the exception. Describe the behavior for review. Rule 5.2 requires rights to the included materials. [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)

<a name="fonte-correspondente"></a>

## Corresponding Source

Identify the source commit for each binary distribution and provide the source, notices, and build instructions for that revision. Set `app.sourceReleaseUrl` to the public HTTPS URL of the release page or corresponding source archive. Use a stable URL for each build, such as a release tag page, and include the same URL where the binary is offered. Use a revision-specific URL because `main` moves over time.

Run the following command from a clean checkout of the revision used for the build.

```sh
python3 scripts/prepare-source-release.py --ref HEAD --output outputs/source-release
```

The command uses `git archive` to produce an archive of the exact commit and a manifest with SHA-256. The `--binary` option records hashes of binaries supplied by the pipeline. Build the clean revision in the release pipeline to establish provenance, and publish the source archive alongside the corresponding binaries.

The archive includes the Gradle wrapper, dependency versions, source, notices, and build instructions. External SDKs and tools keep their own terms. Building a modified version requires no distribution of private keys. Installation and signing instructions should explain each platform's procedure and the chosen channel's limits.

## Update and verify the inventory

```sh
./gradlew -I scripts/legal-dependencies.init.gradle :composeApp:exportLegalRuntimeDependencies
python3 scripts/generate-legal-notices.py --refresh build/legal/runtime-dependencies.json
python3 scripts/generate-legal-notices.py --check
python3 -m unittest discover -s scripts/tests -p 'test_legal*.py'
```

The collector reads Gradle's production dependency resolution, including Android and both iOS ARM64 targets. Review new non-Apache licenses in the Maven inventory and explicitly include their texts. Track native libraries separately, with the commits and hashes of the copied texts.

The release issues track verification on physical devices, acceptance of terms by an account, upload, processing, and the App Review decision. Record evidence for each step separately.

## Compose beta dependency update

The focused review of candidate `f739a5c6` updated the production inventory to 245 Maven coordinates and Skiko 0.156.1 with Skia `m156-6416302ae5`. The updated notices include the new full ICU license text, PartitionAlloc's BSD attribution, and Apple statements retained from its upstream tree. The archive inspection documents the contents of the archive. Determining which objects are in the final app and whether the licenses permit distribution requires separate evidence. The [integration record](validation/compose-beta-integration-review-2026-11-04.md) and its native receipt describe the evidence collected.
