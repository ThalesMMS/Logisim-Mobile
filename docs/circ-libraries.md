# External libraries and portable bundles

`CircLibraryCodec` resolves projects with `file#...` libraries. The caller supplies the content and a resolver. The codec performs no file, socket, or URL access itself.

```kotlin
val result = CircLibraryCodec.read(xml, sourceId, CircuitLibraryResolver { from, reference ->
    permittedFiles.resolve(from, reference)
})
```

The resolver returns `CircuitLibrarySource(id, xml, revision)`. `id` identifies the canonical file. `revision` may represent a provider version, date, or hash. The change check also compares content so it can detect XML changes even when the revision is absent.

A platform adapter must limit resolution to files the user has authorized. Permission to open an Android file gives access to that file only. On iOS, the adapter must retain authorized access to the document or folder. The codec operates within those permissions.

## Read result

`CircLibraryImportResult.Success.resolved` contains the following fields.

- `project` contains all definitions needed to simulate the project.
- `snapshots` contains source identities and revisions.
- `changes(resolver)` reports missing, replaced, or changed files before a new operation.

Definitions receive distinct names within the assembled project. Persistent IDs distinguish circuits with the same name in different files. The reader looks up references in nested libraries, detects cycles, and rejects ambiguous names.

## Export an .lsebdl bundle

Use the bundle API to preserve the original file graph, its metadata, and edits to the assembled project.

```kotlin
val packageFiles = CircLibraryCodec.bundle(resolved, editedProject)
val bytes = CircBundleCodec.write(packageFiles)
```

The bundle contains `manifest.xml`, `main.circ`, and libraries under `library/`. References are relative. The selected main circuit must remain in the source file. To make an external definition the main circuit, import it as a local definition before exporting.

`CircBundleCodec.create(project)` creates a single-file bundle from an assembled project. Use it when you do not need to retain the original file boundaries. Read a bundle as follows.

```kotlin
when (val imported = CircBundleCodec.read(bytes)) {
    is CircBundleImportResult.Success -> openResolved(CircLibraryCodec.read(imported.bundle))
    is CircBundleImportResult.Failure -> showDiagnostics(imported.diagnostics)
}
```

The ZIP codec accepts stored or DEFLATE entries. It validates CRCs, sizes, and descriptors and rejects symlinks, absolute paths, directory traversal, reserved names, and case-insensitive name collisions. It also rejects ZIP64, encryption, and other compression methods. Limits are 256 entries, 2 MB per file, and 32 MB of bundle contents. The assembled project must also fit within the `.circ` parser limit.

## Verification

`CircLibraryCodecTest` covers nested libraries, duplicate names, missing files, cycles, revision changes, relative paths, edited metadata, and preservation of instance order. ZIP tests cover interoperability with `java.util.zip` and malformed input. Fixed and dynamic DEFLATE fixtures are in common tests for execution on the Kotlin platforms.

```sh
./scripts/verify-circ-bundle.sh /path/to/logisim-evolution-all.jar
```

This command produces a bundle with an external library, opens its manifest and project on desktop, and reopens its definitions on mobile. The Android and iOS adapters handle document pickers and providers. These UI flows require verification separate from the core tests.
