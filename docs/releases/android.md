# Prepare an Android distribution

This process builds signed APK and AAB files using an identity supplied by the maintainer. It covers local artifact preparation. Key creation, store submission, and production promotion are separate steps.

## Configure the signing identity

Set all four environment variables. Keep the keystore and both password files outside the source checkout. The preflight rejects paths inside the checkout, including paths reached through an external symbolic link. Password contents stay in their files and never appear in command-line arguments or metadata.

- `LOGISIM_ANDROID_KEYSTORE` points to the distribution keystore.
- `LOGISIM_ANDROID_KEY_ALIAS` identifies the existing key by its alias.
- `LOGISIM_ANDROID_STORE_PASSWORD_FILE` points to the keystore password file.
- `LOGISIM_ANDROID_KEY_PASSWORD_FILE` points to the key password file.

Keep the signing identity consistent across updates. Changing the key without a compatible rotation process can prevent updates to installed copies. Protect and back up the selected key.

## Check and build

Update `app.version` and increment `app.build` in `app.properties`. Regenerate the app identity files and commit the source before creating distribution artifacts.

```sh
python3 tools/generate_app_identity.py
python3 -m tools.release_android
python3 -m tools.release_android --build
```

The first release command checks inputs, tools, generated files, and whether the source checkout is clean. The second runs `assembleRelease` and `bundleRelease`, verifies the APK and AAB signatures, checks the APK identifier and version, and copies the artifacts to `outputs/releases/android/<version>-<build>/`. The `release.json` file records the commit, version, build, file sizes, SHA-256 digests, and public SHA-256 fingerprints of the APK's current signing certificates. The log includes `apksigner --print-certs` output so you can compare the identity used in upgrade tests. The script preserves directories that already contain release metadata.

The Gradle debug build works without signing credentials. Verified distributions require this script and the signing configuration.

## Review the store listing

The [Android listing draft](android-listing-draft.json) contains English and Portuguese copy for `thalesmms.logisim.mobile`, version `0.2.0`, build `4`. Education is the proposed category and English the proposed default language. The channel, account, public contact, target audience, pricing, rating, and Data safety declaration still need decisions. Account and test track setup are separate from this preparation.

```sh
python3 -m tools.validate_android_listing
python3 -m tools.validate_android_listing --require-reviewed
```

The first command checks the app identity, release notes, and listing copy. The second also rejects fields pending review. Signing, Google Play acceptance, and runtime checks require separate verification. The listing tracks the promotion criteria and leaves fields for physical-device and artifact evidence open until the corresponding checks run.

The copy follows the name and description limits in the [store listing setup guide](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en). Creating a release depends on the selected account's permissions and track. [Google Play release guide](https://support.google.com/googleplay/android-developer/answer/9859348?hl=en) The Data safety review covers SDKs and distinguishes apps available exclusively on the internal testing track from other distribution channels. [Google Play Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

The privacy review should cover local editing, documents selected through the system picker, export and sharing, and data sent to a paired companion after a user action. The source-code observations in the listing draft inform that review. Submission of the store form remains a separate step. Phone and tablet screenshots from the final APK and the promotional graphic are still pending.

## Qualify before distribution

Test the package across the supported Android versions and on physical devices. Verify clean installation, upgrade from a previous version with the same key, project migration, interruption recovery, export, accessibility, memory use, and behavior on small screens and tablets. Record the devices, OS versions, APKs, and results.

Issues #35, #48, and #56 cover automated scenarios, performance, and the app lifecycle. The distribution audit covers license and privacy requirements. Review those results and the store metadata before promoting the release to a public channel.

## Preparation status

The preflight test confirmed that missing credentials stop the process before signing. Issue #62 still requires a run with the maintainer's signing identity and hardware to verify the key, the signed distribution build, and upgrades on physical devices.
