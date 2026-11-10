# Project catalog

`WorkspaceCatalogRepository` stores multiple projects by ID. Projects can share a name. Create, open, save, rename, duplicate, move to trash, and restore all use `WorkspaceProjectId` as the key.

## Revisions and conflicts

Each mutation receives the revision opened by the caller. If another writer has committed a revision, the operation returns `WorkspaceMutationResult.Conflict` with the current document and preserves the caller's local content. The caller can reload or create a copy with its content.

Saving identical content keeps the revision. A committed change gets a new revision. Revision numbers can have gaps because the repository never reuses a file written during an interrupted save.

Deleting moves the project to the trash and can be undone. A stale save cannot resurrect a deleted project. The API retains historical files.

## Writing and recovery

The adapter runs each operation inside `withExclusiveAccess`. The lock uses a separate, stable file so atomic replacement of stored data cannot replace the lock.

The repository writes the immutable payload first and replaces the catalog last. Replacing the catalog commits the revision. Orphaned payloads remain uncommitted.

The catalog keeps a previous copy and the committed revision history of each project. If the current catalog is corrupt, the reader uses the previous one and reports the recovery. If a payload is unreadable, the reader tries only committed revisions. `contentRevision` identifies the revision actually read. `info.revision` remains the expected revision for the next save.

If every catalog copy is unreadable, the API returns an error and blocks writes. When recovering from a backup, the repository preserves the unreadable catalog in another file before any new commit.

`load(legacyXml)` copies the previous checkpoint or legacy preference once and preserves the original. The catalog uses internal storage separate from external file providers.

## Integration

`WorkspacePanel` presents the catalog and emits callbacks for its operations. The session owner updates the list after each result. To show the trash, pass the list from `list(includeDeleted = true)`.

`AndroidCatalogStorage` implements atomic writes and interprocess locking. `FileWorkspaceCatalogStorage` provides the same contract for JVM tools and real-file tests. The native documents work integrates the iOS adapter.

`WorkspaceCatalogTest` and `WorkspaceCatalogFileTest` cover duplicate names, CRUD, revisions, conflicts, corruption, migration, viewport and selection, process termination between payload and catalog writes, and concurrent writes from two separate processes.

## Session and lifecycle

`WorkspaceSession` belongs to a window. The local buffer and its committed revision are separate states. `edit` changes the buffer. `flush` confirms a revision only after the repository completes the transaction. A failure preserves the buffer and the last committed revision.

`foregroundChanged(false)` pauses the simulation before saving the buffer. Returning to the foreground leaves the simulation paused. The session compares the external revision and reloads changes only when no local edits are pending.

A conflict preserves the local buffer and the external revision. `reloadStored` discards the buffer on explicit request. `saveLocalAsCopy` creates another project from the buffer. Switching projects first attempts to save the current edit and cancels the switch if the write fails.

`WorkspaceViewState` stores the active circuit, selection, and viewport for each circuit. On reopening, the session removes references to components or circuits that no longer exist. The window keeps its viewport when updating to a compatible external revision.

`rememberWorkspaceSession` binds the session to Compose lifecycle events. Only `RESUMED` counts as foreground. `ON_PAUSE`, `ON_STOP`, and disposal pause execution and attempt to commit pending changes. The app must supply the callback to pause its simulation controller and send XML and viewport changes to the session. Starting simulation remains an explicit app action.

## Editor host and external files

`CatalogWorkspaceHost` mounts `App` for the selected project, presents the project list, and displays the committed internal revision. The host receives native storage, file actions, and a stable `windowId`. Each iOS scene supplies its own ID. Two scenes can open different projects because window identity is independent of the global catalog selection.

Editor mount and replaced document identity use separate counters. Importing a file or creating a circuit opens another ID in the catalog. Undoing that replacement finds the previous ID again. Normal edits preserve the ID. The previous XML is committed before linking the new project. If the commit or creation fails, the new content remains a draft and its autosaves cannot write to the previous project. Saving the draft as a copy also preserves any earlier conflicting buffer.

`StoredWorkspaceDocumentLinks` stores the native opaque reference per project ID. Window selection has a separate record. The content stamp confirms that the restored reference corresponds to the restored XML. Credentials and SHA-256 revisions used by the native adapter are separate. The legacy association migrates only if the catalog was empty and the XML matches. Importing as a copy always clears the link that would allow overwriting the source file.

Asynchronous opens and writes capture the project, mount, and identity. A write requested for A may finish after the window opens B, but updates only A's reference. Its response is cancelled for the editor now showing B. A late picker result also leaves the project opened after the request intact. External writes check the current project's reference and revision before calling the native adapter.

`CatalogWorkspaceBindingTest` exercises these flows using real `EditorState` and `EditorDocuments` instances. `WorkspaceDocumentLinksTest` covers opaque Unicode references, two windows, corruption, and future formats. The Android and iOS adapters handle native writes and pickers.

The v4 project payload preserves the circuit, instance path, component and wire selection, viewport per path, and sidecar attachment references. An explicit field distinguishes legacy viewport maps from maps whose keys include the length of the circuit ID and each path segment. This distinguishes IDs containing `:` without inferring the format from the ID contents.

The reader still supports v1, v2, and v3 payloads. The existing v3 contains attachment references and legacy viewport keys. Rewriting any old version as v4 preserves references and keeps the map marked as legacy until it is validated against the project. Metadata operations preserve this classification. Restore migrates only keys with exactly one valid destination. It discards ambiguous legacy keys and aliases with conflicting values to avoid applying a view to the wrong circuit. You may need to frame a legacy view again. Obsolete paths and selections are still removed.

An unknown viewport format marker in a v4 payload with a valid checksum blocks opening and saving. Earlier revisions cannot be recovered as writable content in this case.

When restoring stored XML, `restoreStored` also requires the persistent `logisim-mobile-id` on every instance in the path and every selected component. Without those IDs, the session resets unsafe selections and instance views and displays a recovery notice. It preserves safe definition and main-circuit views and leaves the XML intact. Live models remain compatible before the markers are written on first export.

## Project unavailable in the current registry

Envelope integrity and whether the current codec can interpret the XML are separate checks. If the checksum and payload structure are valid, a missing library or component keeps the current revision. The repository returns the exact XML with `WorkspaceDocument.readOnly = true`. It determines this state on open and leaves the persisted format intact. Restoring the provider makes the same ID, XML, and revision editable again without creating a revision.

A legacy checkpoint or preference also migrates as a read-only original if its XML cannot be interpreted. The original checkpoint remains intact. The host skips capturing the fallback visual circuit or writing view state over the document. Explicitly creating or importing another project uses a new identity. Undoing the switch can return to the protected original.

`EditorSessionControl.documentWritable` distinguishes an accepted export from a screen showing rejected content. `EditorState` retains the raw XML for exporting the original, displays an empty placeholder, and blocks edits until explicit replacement. The host shares its component registry with the codec and observes editor validity during backgrounding and disposal as well.

`WorkspaceUnavailableProjectTest` covers provider removal and return, an unavailable current revision with an older backup, checkpoint and preference migration, and copying the original. `CatalogWorkspaceBindingTest` covers the case in which the repository accepts the project but the editor rejects it, including backgrounding, recreation, New, and Undo.

## Session import report

The host keeps an `EditorImportReport` by project ID, including source, version, and typed migrations. A draft whose commit failed keeps its report until receiving an ID. Switching projects, duplicating, or saving a copy carries the report to the corresponding mount. Reports are kept outside the XML and catalog format.

After the initial editor is created, `editorAttached` registers the report with `showOnOpen = false`. This preserves an open report and keeps the toolbar action available without reopening the dialog when revisiting the workspace. The initial report is captured before delivering a pending native open so project B cannot receive project A's report.

## Run transactions off the UI thread

`CatalogWorkspaceRuntime` is the production host. A serial coroutine per window owns `CatalogWorkspaceBinding`, `WorkspaceSession`, the repository, and links. Construction, codec validation, reads, writes, and CAS commits run on the worker. Native actions and editor control stay on the UI dispatcher. The host captures XML, viewport, identity, and validity before enqueuing an operation.

Replacement immediately makes the visible project a pending draft so a native write cannot use the previous project's reference. Subsequent commands preserve the replacement order. Older results update the window only if it still owns their mount and identity. Opening or creating another workspace pauses simulation and blocks new edits until the commit completes and the new view model attaches. A failure preserves the previous view model, buffer, and history. An ACK from an earlier attach cannot release a later switch's gate.

Disposal enqueues the final capture before closing the queue. In-flight native writes keep the actor alive until the ACK for their metadata, even if their UI responses are already cancelled. `awaitPersistence` waits for the enqueued prefix without blocking the UI. `awaitClosed` waits for full shutdown.

`CatalogWorkspaceRuntimeTest` uses real temporary files, atomic writes, and fsync. It covers a deliberately blocked write with a UI heartbeat, replacement followed by another edit, failure to switch projects, CAS between two windows, delayed attach, a new view model still preparing, and a late native write after navigation or disposal. The test fails if storage access occurs on the UI thread.

## Portable copies and binary sources

The `openPreparedCopy(name, xml, attachments, expectedMount, expectedIdentity, completed)` overload confirms the source before enqueuing a create transaction. It delivers `Opened` only after the catalog persists the new identity and the new view model receives the attach ACK. Disk confirmation alone cannot signal that the editor opened. The callback belongs to the scene scope, which survives a mount switch.

`readAttachment(mount, identity, key)` verifies the source before and after reading on the worker. A stale mount, an identity without a committed project, a closed queue, or an unavailable project returns `Failure`. `Missing` means only that a committed project lacks the key, preventing silent export of an incomplete graph. The `portable-source` key preserves the original file without retaining native folder permissions.

The host passes `directoryAssets` to `App(directories = ...)` and provides the copy and export bridge by mount. The core attachment code owns the blob format and transactions. The actor serializes operations. The real-file test simulates a view model delay, a catalog failure after preparing the source, a removed blob, and a read that loses its scope during a switch.
