# Binary documents on Android

`AndroidDocumentAssetActions` implements the common `DocumentAssetActions` contract. It opens images or other binary files through `ACTION_OPEN_DOCUMENT`, creates exports with `ACTION_CREATE_DOCUMENT`, shares immutable copies with the existing restricted `FileProvider`, and presents the native print interface for PDF files.

Construct it in `MainActivity.onCreate` before the activity starts, alongside the text document adapter:

```kotlin
val assets = AndroidDocumentAssetActions(this, documentSession) { recovered ->
    // Forward Failure.notice to the current UI. Completed does not bind an older project.
}
```

The session identifier must survive saved-instance-state restoration. Pass `assets` to the annotation image-picker bridge and visual-export delivery bridge. The adapter does not add editor controls itself.

## Files and permissions

The caller supplies accepted MIME types and a maximum import size. The absolute limit is 64 MiB. Image consumers can request a smaller limit; an ELF consumer can request the full limit with `application/octet-stream`, `application/x-elf`, and `application/x-executable`. The raw `application/octet-stream` type accepts arbitrary content and opens an unrestricted file picker, so text memory images are selectable too. PNG, JPEG, GIF, PDF, ZIP, and ELF signatures take priority over provider metadata. The image consumer must still validate or decode the imported bytes.

Only content URIs are read or written. Import copies the selected bytes and does not retain a link to the source. No imported URI is converted into a filesystem path. A revoked grant, missing file, wrong type, size limit, or insufficient memory returns a localized failure.

Exports preserve their bytes and requested extension, including `.bin`, `.hex`, `.mem`, and `.txt`. Assets and share intents retain the caller's MIME type, such as `application/octet-stream` or `text/plain`. Names are sanitized and bounded while keeping a short final extension. Export metadata stores that bounded name. A non-empty destination with different bytes is rejected before writing. An empty destination is written and read back. Identical bytes count as an already completed export, which makes recovery idempotent. This policy does not promise atomic replacement in a third-party provider.

For text memory formats such as `.hex` and `.mem`, CreateDocument requests generic binary storage to preserve the requested extension. Android can otherwise append its preferred `.txt` suffix when the extension does not match `text/plain`. The payload bytes and share intent retain their original MIME type; memory decoding uses the format selected by the caller. The filename behavior follows [Android's FileUtils implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/FileUtils.java).

Shared copies live below `cache/shared-circuits` and use `${applicationId}.files` with a temporary read grant. The same route is restricted by the existing FileProvider configuration. `Completed` means that a save was confirmed or the native share/print interface opened. It does not assert receipt by another application or physical printing.

## Interrupted operations

Export bytes and metadata are journaled separately in the application's private files directory before the picker launches. Each export owns a payload file with a unique identifier, so an older activity cannot overwrite bytes belonging to a later export. The destination URI and writing phase are journaled before I/O. When Android recreates the activity, the adapter can finish a pending export without an in-memory callback. It reports the outcome through `onRecoveredResult`; the host must show recovered failures without associating an older export with the current project.

An image selection whose callback belonged to a destroyed editor is discarded. The recovery notice asks the user to select the file again. It is never attached silently to another project. An export interrupted before the picker launched also asks the user to retry.

## PDF printing

The print adapter reads page counts with `PdfRenderer`, honors the requested page ranges, cancellation, orientation, margins, and monochrome mode. It rasterizes selected pages into `PrintedPdfDocument` at up to 300 dpi, with a shared budget of 16 million bitmap pixels for the job. Large jobs can use a lower raster resolution. The PDF source remains unchanged and the private print copy is deleted when the print adapter finishes.

## Verification

```sh
./gradlew :composeApp:compileAndroidMain \
  :composeApp:testAndroidHostTest --tests '*AndroidAssetFilePolicyTest' \
  :androidApp:assembleDebug
```

The host tests cover extension preservation and traversal sanitization, signature precedence, MIME restrictions and raw binary loading, the 64 MiB limit, and defensive ownership of payload bytes. Compilation does not establish native picker, provider, or printer behavior.

The native adapter copies, journals, reads, verifies, and shares payload bytes on its IO executor. Its main-thread work includes picker/print presentation, callbacks, JSON metadata reads and synchronization limited to 32 KiB, and deletion of temporary files. The `DocumentAsset` constructor and `bytes` getter copy their byte array; callers preparing or decoding large buffers should do that work in a background context.

Runtime checks for the integrated host must select PNG/JPEG/GIF files, cancel selection, reject an oversized file, save PNG/SVG/PDF with byte-for-byte readback, share to a separate receiver, and recreate the process while CreateDocument remains open. Print checks must include a multi-page PDF, page 2 only, disjoint ranges, cancellation, invalid PDF, landscape, margins, and monochrome output. No cloud provider or physical printer is implied by the host tests.
