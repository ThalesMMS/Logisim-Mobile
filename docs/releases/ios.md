# Prepare an iOS archive for TestFlight

This process checks an Apple signing identity and provisioning profile supplied by the maintainer, creates a distribution archive, and exports a local IPA. Uploading the IPA to App Store Connect and accepting Apple agreements are separate steps.

## Set the signing configuration

Set the following environment variables.

- `LOGISIM_APPLE_TEAM_ID` identifies the Apple Developer team.
- `LOGISIM_APPLE_SIGNING_IDENTITY` specifies the full name or SHA-1 fingerprint of a valid distribution certificate in the current keychain.
- `LOGISIM_IOS_PROFILE` points to an App Store distribution `.mobileprovision` file that covers `thalesmms.logisim.mobile`.

The private key stays in the keychain. Unlock the keychain and obtain the certificate and provisioning profile before running the script. The preflight checks the team, bundle identifier, validity, and profile type. The selected certificate must also appear in the profile's `DeveloperCertificates` list. The archive uses manual signing with automatic provisioning updates disabled. Only `--build` installs the supplied profile in the user's provisioning directory. If a file with the same UUID has different contents, the script preserves the existing file and reports an error.

## Target capabilities

The bundle identifier is `thalesmms.logisim.mobile` on both iPhone and iPad, with an iOS 16 deployment target. The target uses no custom entitlements for App Groups, iCloud, push notifications, or execution outside the sandbox. Files uses the system document picker and bookmarks. The `NSLocalNetworkUsageDescription` purpose string in `Info.plist` explains the optional connection to the companion chosen by the user. The distribution profile should contain only the capabilities the target needs. Final qualification still needs to record the signed archive's effective entitlements and verify its capabilities.

## Check and export

Increment `app.build` and update `app.version` in `app.properties`. Set `app.sourceReleaseUrl` to the stable public URL reserved for the release page or source bundle for this build. Regenerate the identity files and commit before preparing a distribution artifact.

```sh
python3 tools/generate_app_identity.py
python3 -m tools.release_ios --check
python3 -m tools.release_ios --build
```

The preflight requires macOS, Xcode, XcodeGen, a valid certificate, and a compatible profile. `--build` creates the archive, verifies the signature and `Info.plist`, exports an IPA using the `app-store-connect` method, and checks the bundle identifier, version, build, and privacy manifest again inside the IPA. The app must include its own manifest even if a framework has one.

The results in `outputs/releases/ios/<version>-<build>/` include the archive, export options, log, IPA, source bundle from the same revision, source manifest with the IPA hash, and `release.json`. The script preserves directories that already contain release metadata. You can export locally without App Store Connect credentials.

## Qualify the channel

Before offering the beta, check the compatibility of the distribution channel and the libraries linked in the IPA. [Distribution license audit](../distribution-license-audit.md#distribution-channels-and-terms)

Publish the source bundle and manifest at the configured address, verify that the download works without authentication, and include the URL in the beta information. Check privacy, capabilities, and store metadata. Record installation and upgrade tests on physical iPhones and iPads, project migration, recovery, touch and keyboard input, accessibility, and memory use. Physical-device qualification is a separate step from simulator testing.

Issues #17, #28, #36, #48, and #56 track the licensing, accessibility, automation, performance, and lifecycle requirements. The maintainer chooses the team and channel, reviews the publication information, and authorizes the upload once those requirements are met.

## Preparation status

The preflight test confirmed that missing credentials stop the process before signing. The tests cover expired profiles, incorrect teams or bundle identifiers, development, ad hoc, and enterprise profiles, and artifact identity mismatches. Issue #63 still tracks the team, certificate, profile, distribution archive, physical-device testing, and TestFlight upload.

## Privacy and beta metadata

The target includes `iosApp/PrivacyInfo.xcprivacy`. The preflight checks the declared reasons, and the export requires a copy in the archive and at the app root inside the IPA. The declarations cover the API calls used by the app.

| Category | Reason | Observed use |
| --- | --- | --- |
| User defaults | `CA92.1` | The app's own preferences and document references in `IosPreferencesStorage` and `IosDocumentActions`. |
| File timestamp | `C617.1`, `3B52.1` | Metadata for internal files and documents authorized by the user. `IosDocumentActions` and `IosDocumentAssetActions` check size before reading bytes. The native runtime also references `stat` and `fstat`. |
| System boot time | `35F9.1` | `TimeSource.Monotonic` measures run intervals and timeouts in `EditorState` and `ExternalIoRuntimeBridge`. The inspected framework references `mach_absolute_time`. |

The reasons come from Apple's documentation as checked on November 4, 2026. [Required reason APIs](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api) · [Approved reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)

The source and simulator framework inspection still needs to be followed by a review of the distribution binary, its SDKs, and the archive's privacy report. Complete the store's App Privacy responses separately.

The [TestFlight metadata draft](ios-testflight-metadata.json) includes a description, testing instructions, review notes, and fields still pending for the team, contact, URLs, group, and screenshots. The [iOS privacy policy draft](ios-privacy-draft.md) describes local storage, Files, export, and the user-selected companion. Before uploading, publish and verify a policy that reflects the final behavior, then add an accessible link in the app. Apple also requires the link in App Store Connect metadata. [App Review Guidelines for privacy](https://developer.apple.com/app-store/review/guidelines/#privacy)

## Requirements checked on November 4, 2026

Apple's requirements page specified Xcode 26 or later and the iOS or iPadOS 26 SDK or later for uploads. The app's iOS 16 deployment target met the iOS 13 minimum listed on that page. Check the updated age-rating questionnaire and EU trader status in the account based on the selected channel and territories. These fields still require verification in App Store Connect. [Apple upload requirements](https://developer.apple.com/news/upcoming-requirements/)

The App Review Guidelines showed an October 16, 2026 update. Physical-device testing, published metadata, review of optional integrations, and license reconciliation in #17 were still pending at the time of this check. Upload, processing, and TestFlight approval remain separate milestones. [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
