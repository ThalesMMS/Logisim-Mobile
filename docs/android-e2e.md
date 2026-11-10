# Android user scenarios

The `e2e` build type adds `.e2e` to the configured Android application ID. It has separate files, preferences, provider authorities, and URI grants. The runner resets only that isolated application and its test APK. It never clears the normal application's data.

## Run

Use an emulator or test device with an English system locale for the native DocumentsUI steps. The application itself is exercised in Portuguese or English through its personal language preference. Connect one device or set `ANDROID_SERIAL` explicitly.

```sh
./gradlew -PandroidTestBuildType=e2e :androidApp:assembleE2e :androidApp:assembleE2eAndroidTest :androidSnapshot:assembleDebug
ANDROID_BUILD_TYPE=e2e ./scripts/test-android-documents.sh
python3 scripts/test_android_ui.py
python3 scripts/e2e-android.py --language en
python3 scripts/e2e-android.py --language pt
```

Use `--scenario files`, `rejected`, `editing`, `sequential`, `layout`, or `unicode` to diagnose one group. The default runs all groups. A missing control, unavailable feature, ambiguous selector, ANR, or failed assertion fails the run.

The separate `inspector`, `io-showcase`, `portable`, and `visual-export34` groups qualify their mounted tools. They run only when explicitly selected. Visual export also requires `--build-receipt <path>` with the editor APK's producer revision and digest. Portable qualification stages an owned folder under Downloads and confirms its observed native controls; it never grants the Downloads root.

The file scenarios use Downloads and a provider in the test APK. Incoming intents start from a visible external fixture activity with a user action and temporary URI grants. Sharing targets only the external test receiver. No message or document is sent to an email, cloud, or messaging service.

## Evidence

Each run creates a timestamped directory in `outputs/android-e2e/`. Its manifest records the APK hashes, source revision and local changes, application ID, device fingerprint/API, screen size/density, and languages. The runner verifies the installed app and test APK hashes before and after its scenarios. Each completed scenario records its result, screenshot, and current UI hierarchy. Exported documents and the external share receipt remain beside them.

XML exports use the visible Copy action. Before each copy, a foreground test activity places a fresh non-XML marker on the clipboard. The reader then requires a complete project document, preventing old clipboard contents from passing a failed copy. Sharing also checks the exact byte count and SHA-256 at the external receiver.

On failure, independent collectors retain the original error, screenshot, fresh hierarchy, application logcat, matching ANR details, and the isolated application's private checkpoint archive. A collector failure is recorded separately and does not turn the scenario into a pass. `last-document.circ` identifies the most recent successful UI export; a checkpoint recovered from an archive may precede the visible edit.

The standard-library driver tests use a simulated adb process to check selectors, stale-hierarchy rejection, shell quoting, isolation guards, and partial artifact failures. They do not replace the device scenarios.

The Android host enables Compose test tags as resource IDs once at the workspace root. Named accessibility actions remain the primary route for the editing scenarios.

For a focused Compose text field, the driver uses a dedicated self-targeted `androidSnapshot` APK to read `UiAutomation` directly. This avoids the CLI's wait for UI idle while a text cursor is active, keeping the existing eight-second focus deadline. Each snapshot requires a new nonce, matching XML metadata and an unchanged editor PID. It retains its XML and receipt. Clipboard, launcher, receiver and system picker activities continue using the CLI. The separate helper preserves those test activities and providers, and its APK hash is verified alongside the editor and test APKs.

Each external circuit fixture has a UUID filename. Incoming VIEW/SEND and local SAF imports wait for the callback naming their specific file before inspecting the exported project. A status left by an earlier import cannot satisfy that wait, including when the two documents have identical circuit contents.

When waiting for a rejected input's error, the runner never dismisses an import report. Other automatic report acknowledgements read a fresh hierarchy after their screenshot and preserve an error that appeared above the report. This prevents an old Close coordinate from dismissing a newer modal by tapping outside it.

## Binary document adapters

`python3 scripts/test-android-assets.py` exercises the isolated native fixture without mounting the editor. It opens PNG and memory payloads, rejects oversized input, cancels native pickers, exports and shares PNG/SVG/PDF/HEX bytes, recovers a pending export after process death, and opens then cancels PDF print preview. It never sends a print job. Its manifest and callbacks explicitly identify this scope as `native-document-adapter-only`.

These adapter results complement the editor scenarios. They do not establish that an editor tool prepared the correct image, memory data, or PDF. `--skip-install` still requires the installed app and test APKs to match the local artifacts exactly.

## Scope

The suite covers a deterministic editing document, NOT behavior, wire endpoints and history, selection and clipboard operations, canvas touch, zoom, SAP-1 sequential execution, document exchange, rejected input, recovery, and accessible action routes. Layout checks temporarily change rotation and font scale and restore those settings even on failure.

The native provider runner also checks read-only metadata, revoked permission, a missing file, incompatible UTF-8, source preservation, export readback, and intent resolution. Its success requires ten reported passes and zero failures.

These checks describe emulator/device behavior of the built artifact. They do not establish TalkBack usability on physical hardware, every device size, or cloud-provider authentication and offline behavior.
