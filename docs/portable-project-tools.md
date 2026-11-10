# Portable project editor tools

`portableProjectEditorTool` adds bundle open/save/share and explicitly granted project-folder import to the editor tool list. Its files are independent of `App`, `EditorState`, and the native hosts.

```kotlin
portableProjectEditorTool(
    assets = documentAssets,
    directories = directoryAssets,
    opener = portableWorkspaceCopyOpener,
    sourceArchiveForIdentity = portableSourceSidecars::forEditorIdentity,
)
```

The tool opens `.lsebdl` through `DocumentAssetActions`. A directory import first obtains a separate `DirectoryAssetGrant`, lists bounded `.circ` choices on `Dispatchers.IO`, and lets the user choose the main file. The source resolver accepts relative references contained in that grant. Absolute paths, URL references, and paths escaping its root are refused. The native reader independently verifies containment.

When opening a single `.circ` fails on a relative library reference, the editor offers **Open project folder**. Choose a folder that contains the original file and the referenced libraries. The retry matches the original filename and XML before reading any dependency, then opens an independent workspace copy. If the folder has more than one file with that name, use the portable project tool to choose the exact root file.

The importer reads the source graph through `CircLibraryCodec`, creates a portable snapshot using the existing bundle writer, then rechecks the main file and dependencies before accepting it. Every outcome releases the directory grant. The resulting workspace copy retains the source archive; it has no external editable-file handle or directory capability. Later exports use that snapshot instead of rereading the original folder.

## Workspace bridge contract

`PortableProjectCopy` contains the prepared project, a suggested workspace name, and an immutable `DocumentAsset` containing its source archive. The project and archive use the same canonical circuit identities and references. ZIP bytes belong in a workspace sidecar, not inside `.circ` XML.

`PortableProjectCopyOpener.openCopy(copy, expectedDocumentIdentity, onResult)` must:

1. Prepare storage and the new workspace ID on a worker.
2. Preserve the current workspace, including unsaved changes.
3. Persist `copy.sourceArchive` under the new workspace identity.
4. Recheck the expected editor identity before replacing the scene.
5. Clear the previous native document association and mount the new project as a copy.
6. Deliver `Opened(newDocumentIdentity)`, `Cancelled`, or a typed failure on the UI thread.

The suspend callback `sourceArchiveForIdentity` reads that workspace's source sidecar. The panel invokes it on `Dispatchers.IO`. It must return the archive for the requested identity or null for a plain project; it must not substitute another workspace's source archive. Keep the sidecar while circuit names, component values, and layouts change. The bundle exporter maps edits by stable circuit ID.

A malformed sidecar fails export explicitly. It never causes a silent flattened replacement. Plain projects without a source archive export as a self-contained single-file bundle. Additional opaque files and unreferenced circuit files in an imported bundle remain byte-for-byte intact. A collision with an exported source path fails explicitly before any native output is written.

## Native adapters and gates

`DirectoryAssetActions.open` displays an explicit native folder picker. Directory `list/read` methods are blocking worker operations. Native implementations bound traversal and reads, refuse filesystem symlinks or provider aliases escaping the scope, and release their own access without revoking unrelated grants. A single `.circ` file grant does not authorize sibling libraries.

The pure source-flow suite `PortableProjectModelTest` covers real bundle/source graph imports, relative path containment, changed and missing sources, grant release, malformed sidecars, rename/export after XML workspace reload, and source identity separation. Fourteen tests passed using the cached Kotlin compiler and existing JVM core artifact without invoking Gradle. Full Compose compilation, native provider tests, and opening/exporting through the mounted Android/iOS UI remain integration gates. The feature does not close #53 until those native flows pass.
