# Android document operations

The Android adapter opens UTF-8 `.circ` files through the Storage Access Framework. It accepts content URIs from **Open with** and **Share to**, and exports a new document through the system picker. It does not request broad storage access or derive filesystem paths from provider URIs.

**Edit external file** requests read and write access to the chosen URI. **Save to file** is available when the document advertises the atomic replacement contract below and the app has both grants. Other documents can still be opened and saved through **Save As**. The app explains this fallback when it opens the document.

The adapter takes persistent permissions when the provider offers them. A temporary share grant still allows importing a copy. If a grant is revoked or a document is moved, the adapter returns an error and the editor keeps the open project. A read-only provider remains usable for imports.

Picker state is separate for each activity session. The session ID survives Android state restoration. Export text, the returned destination URI, and its phase are journaled before writing, so a recreated activity can retry an export without relying on an in-memory callback. An already complete destination is verified without rewriting it. A non-empty destination with different contents is reported as a conflict, including a partial write interrupted inside the provider. Document operations run on an IO executor and deliver results on the main thread.

## Editor integration

`DocumentActions.setOpenHandler` receives both picker results and incoming intents. The editor must validate the project before calling `retain(reference)`. A rejected import must not change the project or its existing file reference. Creating a new project or loading an example must call `retain(null)`.

`DocumentShareResult.Presented` means that the Android share chooser opened. It does not claim that another app received or saved the file. Sharing uses a `FileProvider` restricted to `cache/shared-circuits`, with a temporary read grant.

## Atomic replacement contract

Standard SAF stream writes can truncate the original before the full replacement is received. Providers can opt in per document by answering `ContentResolver.call(uri, "org.logisim.documents.atomic-replace-capabilities", uri.toString(), null)` with a Bundle containing integer `version = 1`.

The operation `org.logisim.documents.atomic-replace-v1` uses the same URI argument. Its extras contain `expectedSha256` (lowercase SHA-256 of the original bytes), `byteCount` (0 to 16 MiB), and `payload` (a read-only `ParcelFileDescriptor`). The provider verifies the calling UID's read and write grants to that exact URI. It must use a durable, non-reused document ID, keep the original addressable at that URI, and compare the expected digest and publish the complete staged replacement as one transaction with respect to other writers. Reject a missing document, stale revision, revoked grant, or incomplete payload before publication. Return `result = "saved"` after publication or `result = "conflict"` when the precondition fails.

The app reads back the same URI before confirming a save. Retrying after a lost reply can confirm matching bytes without rewriting them. An unavailable or unconfirmed original leaves the editor open. **Close → Save** uses this same operation for linked originals and closes only after confirmation. The separate test APK includes a provider implementing this contract. Other providers can adopt it independently.

## Device checks

Build the application and its isolated provider tests:

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5556 ./scripts/test-android-documents.sh
```

The runner exercises a provider in a separate test process. It checks UTF-8 import, read-only metadata, export/readback, preservation of the original, external conflicts, revoked permission, missing documents, malformed UTF-8, safe share names, and `text/plain` intent resolution. It also checks atomic save readback and retry with a lost reply, interrupted upload, and a concurrent write between client preflight and provider commit. Success ends with `DOCUMENT_TESTS_PASSED=13`. The provider and its bootstrap receiver are only in the test APK.

Validated on an Android API 35 emulator with the development application ID `dev.logisim.mobile`:

- All ten external-provider tests passed.
- The system picker imported the SAP-1 file from Downloads, and cancellation preserved the current project.
- Save As exported all seven circuits, 156 components, and 318 wires. The original source SHA-256 remained unchanged.
- The saved Downloads URI retained read/write permissions and reopened in a new process.
- Open with loaded the external provider, reloaded a changed file at the same URI, and rejected malformed XML while preserving the preceding project.
- An external test receiver read all seven circuits through the share URI and its temporary grant. The URI exposed the restricted FileProvider route rather than a private filesystem path.
- Android killed the background application while CreateDocument remained open. Pressing Save recreated the application in a new process and exported all seven circuits from the journaled payload.

Local evidence is written under `outputs/android-documents/`, including instrumented results, picker snapshots, the shared receiver result, and exported `.circ` files. Provider authorities and the test runner derive their application ID from the installed application/build configuration.

These checks use Downloads and a separate test provider. A cloud provider's availability and authentication depend on that provider; no cloud account or network transfer was exercised.
