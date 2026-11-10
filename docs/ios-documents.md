# Documents on iOS and iPadOS

The `IosDocumentActions` adapter uses Files to pick documents and the system share sheet to share a copy. The editor decides whether to accept the contents before retaining a link to the external file.

## Copies and external editing

Opening a copy uses `UIDocumentPickerViewController` with `asCopy=true`. The adapter keeps a private copy and the editor clears the external link. Changes to the local project leave the selected file intact.

**Edit external file** uses `asCopy=false`. **Save to file** coordinates the write and compares the SHA-256 of the current contents with the revision that was read. If another app changed the file or the provider reports unresolved versions, the operation returns a conflict and preserves both versions. The user can reopen the file or export the local edit elsewhere.

**Save as** creates a `.circ` in the selected destination. Cancelling the picker preserves the open document. **Share** passes a temporary copy to `UIActivityViewController`. Receipt by the other app requires separate confirmation.

## Access and reopening

Provider reads and writes use `NSFileCoordinator` on a worker queue. Each operation starts security-scoped access and ends it in `finally`. The adapter rejects non-file URLs without starting downloads.

The stored link contains a bookmark, name, mode, and revision. The editor confirms the link only after a valid import. A removed file, revoked access, invalid bookmark, or unavailable provider produces an error and preserves the local project. Initial opening preserves recovered local changes without automatically reloading an external file over them.

Starting another open discards older responses, so a slow download cannot overwrite the most recent choice. Creating a project or opening an example also invalidates pending reads.

Each SwiftUI window has an `IosDocumentSession` and an identity in `SceneStorage`. `onOpenURL` routes a document received from Files to that window's session. External links in other windows remain separate.

The link also stores the fingerprint of the confirmed local checkpoint. If a window recovers XML that differs from the XML associated with the link, the app clears the link and preserves the local project. The user must open the external file again to resume editing at that destination. This prevents one window's checkpoint from overwriting another window's file.

`IosCatalogStorage` uses a stable lock file with `flock`, separate from the files replaced atomically. The common catalog checks revisions to coordinate windows and processes.

## Verification

Native tests in `composeApp/src/iosTest` exercise real Foundation files, bookmarks, independent copies, revision conflicts, reopening, atomic writes, contention among storage instances, and two opens with one read blocked by the file coordinator. Simulator execution is verified separately from compilation.

Validate the UI with the following scenarios.

1. Open a copy of a local `.circ`, edit the circuit, and confirm the source file remains identical.
2. Open the same file in external editing mode, save it, and reopen it from Files.
3. Cancel the open and export pickers and confirm the open project remains intact.
4. Export to Files and open the share sheet on iPhone and iPad. On iPad, the sheet must have a valid anchor.
5. Open a `.circ` from Files with the app closed and with a window already open.
6. Edit the file externally before saving. Confirm the conflict warning and preservation of the external version.
7. Remove the file or revoke provider access and confirm the local project stays available.
8. Repeat with an external provider such as iCloud Drive and with two iPad windows.

### Local evidence on 2026-11-04

On an iPad Pro 11-inch (M5) with simulated iPadOS 27.0, the built app opened the pickers, cancelled import and sharing while preserving the circuit, exported to On My iPad, and reopened that file as a copy and as an external document. **Save to file** was unavailable for the copy and available in external mode. The share sheet showed the `.circ` name and a valid anchor.

A controlled external edit changed the SHA-256 from `82ffa290c0d433440b5a112ec82119705ed345cf6832f23c3adab16bb7156143` to `089b6f9acf086bf99170e34b72baf6c52d58e31a88a2de19a1639200631c79e8`. The save attempt displayed the conflict warning and preserved the second hash exactly. Captures and the report are under `outputs/ios-documents-smoke` in the validation environment.

The run passed 43 Native tests, including copies, bookmarks, removed files, revision conflicts, concurrent opens, and catalog locking. Framework linking and the Swift host build also passed. A cloud provider, revoked access, two concurrent windows, cold and warm Open With, and physical devices still require integration validation.

The consolidated host at snapshot `main 0beefbd`, with native forward port `ae4bb87`, also built and opened on the iPad. The catalog recovered revision 2 and the circuit with 4 components and 7 wires. After replacing the app and restarting the process, the external bookmark still allowed reads. Saving detected the conflict again and preserved the same external hash. The editor remained usable after dismissing the warning. The later snapshot `main 518840e` requires its own full validation matrix.

The Native run at snapshot `b2f6427` passed 157 of 157 tests, including 13 for the common memory bridge, 12 for binary files, and 10 for text documents. The host build and reopening test above are separate evidence from that suite.

Completion of these scenarios is the closure gate for issue #26. The local-file validation covers that provider scenario.

## Platform references

The access, copy selection, and coordination contract follows Apple's documentation for [UIDocumentPickerViewController](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller) and [directory and document access](https://developer.apple.com/documentation/uikit/providing-access-to-directories). File association uses [LSSupportsOpeningDocumentsInPlace](https://developer.apple.com/documentation/bundleresources/information-property-list/lssupportsopeningdocumentsinplace).
