package dev.logisim.mobile

import dev.logisim.mobile.core.CircBundleCodec
import dev.logisim.mobile.core.CircBundleImportResult
import dev.logisim.mobile.core.CircLibraryCodec
import dev.logisim.mobile.core.CircLibraryImportResult
import dev.logisim.mobile.core.CircXmlEncoding
import dev.logisim.mobile.core.CircuitLibraryResolver
import dev.logisim.mobile.core.CircuitLibrarySource
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

const val PORTABLE_PROJECT_MIME_TYPE = "application/x-logisim-bundle"
const val PORTABLE_PROJECT_MAXIMUM_BYTES = CircBundleCodec.MAX_TOTAL_BYTES + 1_000_000
const val PORTABLE_DIRECTORY_MAXIMUM_FILES = 256

/** A new workspace copy owns this snapshot; it never inherits a native editable-file reference. */
data class PortableProjectCopy(val project: CircuitProject, val name: String, val sourceArchive: DocumentAsset)

/**
 * Create the workspace and its source sidecar off the UI thread, unlink the old native document,
 * then deliver a UI-thread result. Recheck expectedDocumentIdentity before replacing the editor.
 */
fun interface PortableProjectCopyOpener {
    fun openCopy(copy: PortableProjectCopy, expectedDocumentIdentity: Long,
        onResult: (PortableProjectCopyOpenResult) -> Unit)
}

sealed interface PortableProjectCopyOpenResult {
    data class Opened(val documentIdentity: Long) : PortableProjectCopyOpenResult
    data object Cancelled : PortableProjectCopyOpenResult
    data class Failure(val notice: LocalizedText) : PortableProjectCopyOpenResult
}

sealed interface PortableProjectReadResult {
    data class Ready(val copy: PortableProjectCopy) : PortableProjectReadResult
    data class Failure(val notices: List<LocalizedText>) : PortableProjectReadResult
}

sealed interface PortableProjectExportResult {
    data class Ready(val asset: DocumentAsset) : PortableProjectExportResult
    data class Failure(val notices: List<LocalizedText>) : PortableProjectExportResult
}

/** Path normalization is scoped to the granted directory; native adapters enforce containment too. */
object DirectoryAssetPaths {
    fun canonical(path: String): String {
        requireLocalized(path.isNotEmpty() && path.length <= 2048 && !path.startsWith('/') &&
            path.none { it.code < 32 || it.code in 127..159 || it == '\\' || it == ':' } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            message("portable_directory_path_invalid")
        }
        return path
    }

    fun resolve(from: String, reference: String): String {
        canonical(from)
        val normalized = reference.replace('\\', '/')
        requireLocalized(normalized.isNotEmpty() && normalized.length <= 2048 && !normalized.startsWith('/') &&
            reference.none { it.code < 32 || it.code in 127..159 || it == ':' }) { message("portable_directory_reference_outside") }
        val parts = from.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty).toMutableList()
        for (part in normalized.split('/')) when (part) {
            "", "." -> Unit
            ".." -> {
                requireLocalized(parts.isNotEmpty()) { message("portable_directory_reference_outside") }
                parts.removeAt(parts.lastIndex)
            }
            else -> parts += part
        }
        return canonical(parts.joinToString("/"))
    }
}

/** Blocking provider access: call on Dispatchers.IO. The picker grant remains owned by the caller. */
fun portableDirectoryFiles(grant: DirectoryAssetGrant): List<DirectoryCircuitFile> {
    requireLocalized(grant.id.isNotBlank()) { message("portable_directory_identity_invalid") }
    val files = grant.circuitFiles(PORTABLE_DIRECTORY_MAXIMUM_FILES)
    requireLocalized(files.size <= PORTABLE_DIRECTORY_MAXIMUM_FILES && files.map { it.path }.distinct().size == files.size) {
        message("portable_directory_too_many_files")
    }
    files.forEach {
        DirectoryAssetPaths.canonical(it.path)
        requireLocalized(it.path.endsWith(".circ", ignoreCase = true)) { message("portable_directory_file_invalid") }
    }
    return files.sortedBy { it.path }
}

fun preparePortableBundle(asset: DocumentAsset, registry: ComponentRegistry = ComponentCatalog.registry): PortableProjectReadResult = try {
    requireLocalized(asset.size <= PORTABLE_PROJECT_MAXIMUM_BYTES) { message("portable_bundle_too_large") }
    when (val unpacked = CircBundleCodec.read(asset.bytes)) {
        is CircBundleImportResult.Failure -> PortableProjectReadResult.Failure(unpacked.notices)
        is CircBundleImportResult.Success -> when (val loaded = CircLibraryCodec.read(unpacked.bundle, registry)) {
            is CircLibraryImportResult.Failure -> PortableProjectReadResult.Failure(loaded.notices)
            is CircLibraryImportResult.Success -> PortableProjectReadResult.Ready(PortableProjectCopy(
                loaded.resolved.project, portableProjectName(asset.name),
                DocumentAsset(portableProjectName(asset.name) + ".lsebdl", PORTABLE_PROJECT_MIME_TYPE, asset.bytes),
            ))
        }
    }
} catch (failure: Exception) {
    PortableProjectReadResult.Failure(listOf(portableProjectNotice(failure, "portable_bundle_open_failed")))
}

/**
 * Capture and recheck the complete source graph before creating a self-contained workspace copy.
 * The grant is released on every outcome; source handles never enter the request or workspace.
 */
fun preparePortableDirectory(grant: DirectoryAssetGrant, mainPath: String,
    registry: ComponentRegistry = ComponentCatalog.registry): PortableProjectReadResult =
    preparePortableDirectory(grant, registry, mainPath = { mainPath })

internal fun preparePortableDirectoryForDocument(grant: DirectoryAssetGrant, document: DocumentContent,
    registry: ComponentRegistry): PortableProjectReadResult =
    preparePortableDirectory(grant, registry, expectedRootXml = document.text) {
        val name = document.reference.name
        val matching = portableDirectoryFiles(grant).filter { it.path.substringAfterLast('/') == name }
        requireLocalized(matching.size == 1) { message("portable_guided_folder_match", "name" to name) }
        matching.single().path
    }

private fun preparePortableDirectory(grant: DirectoryAssetGrant, registry: ComponentRegistry,
    expectedRootXml: String? = null, mainPath: () -> String): PortableProjectReadResult = try {
    val root = DirectoryAssetPaths.canonical(mainPath())
    requireLocalized(root.endsWith(".circ", ignoreCase = true)) { message("portable_directory_file_invalid") }
    requireLocalized(grant.id.isNotBlank()) { message("portable_directory_identity_invalid") }
    val paths = mutableMapOf<String, String>()
    fun source(path: String): CircuitLibrarySource? {
        val canonical = DirectoryAssetPaths.canonical(path)
        val content = grant.read(canonical, CircBundleCodec.MAX_FILE_BYTES) ?: return null
        requireLocalized(content.size <= CircBundleCodec.MAX_FILE_BYTES) { message("portable_directory_file_too_large") }
        val xml = CircXmlEncoding.decode(content.bytes)
        val id = "directory:${grant.id.length}:${grant.id}:$canonical"
        paths[id] = canonical
        return CircuitLibrarySource(id, xml, content.revision)
    }
    val main = source(root) ?: throw LocalizedArgumentException(message("portable_directory_file_missing"))
    // A native text reader can strip the UTF-8 BOM, which the .circ parser also ignores.
    requireLocalized(expectedRootXml == null || main.xml.removePrefix("\uFEFF") ==
        expectedRootXml.removePrefix("\uFEFF")) { message("portable_guided_source_mismatch") }
    val resolver = CircuitLibraryResolver { from, reference ->
        val path = paths[from] ?: throw LocalizedArgumentException(message("portable_directory_reference_outside"))
        source(DirectoryAssetPaths.resolve(path, reference))
    }
    when (val loaded = CircLibraryCodec.read(main.xml, main.id, resolver, registry)) {
        is CircLibraryImportResult.Failure -> PortableProjectReadResult.Failure(loaded.notices)
        is CircLibraryImportResult.Success -> {
            val bundle = CircLibraryCodec.bundle(loaded.resolved, registry = registry)
            val mainAfter = source(root)
            val changes = loaded.resolved.changes(resolver)
            if (mainAfter != main || changes.isNotEmpty()) {
                PortableProjectReadResult.Failure(changes.map { it.notice }.ifEmpty { listOf(message("portable_directory_changed")) })
            } else {
                val archive = CircBundleCodec.write(bundle)
                preparePortableBundle(DocumentAsset(portableProjectName(root.substringAfterLast('/')) + ".lsebdl",
                    PORTABLE_PROJECT_MIME_TYPE, archive), registry)
            }
        }
    }
} catch (failure: Exception) {
    PortableProjectReadResult.Failure(listOf(portableProjectNotice(failure, "portable_directory_open_failed")))
} finally {
    grant.release()
}

/** Reuse the original graph from the workspace sidecar when exporting edited definitions. */
fun exportPortableProject(project: CircuitProject, sourceArchive: DocumentAsset? = null,
    registry: ComponentRegistry = ComponentCatalog.registry): PortableProjectExportResult {
    return try {
        val bundle = if (sourceArchive == null) CircBundleCodec.create(project, registry) else {
            val unpacked = when (val result = CircBundleCodec.read(sourceArchive.bytes)) {
                is CircBundleImportResult.Success -> result.bundle
                is CircBundleImportResult.Failure -> return PortableProjectExportResult.Failure(result.notices)
            }
            val resolved = when (val result = CircLibraryCodec.read(unpacked, registry)) {
                is CircLibraryImportResult.Success -> result.resolved
                is CircLibraryImportResult.Failure -> return PortableProjectExportResult.Failure(result.notices)
            }
            val exported = CircLibraryCodec.bundle(resolved, project, registry)
            val sources = resolved.snapshots.map { it.sourceId }.toSet() + "manifest.xml"
            val extras = unpacked.files.filterKeys { it !in sources }
            requireLocalized(extras.keys.none { it in exported.files }) { message("portable_bundle_extra_collision") }
            exported.copy(files = exported.files + extras)
        }
        PortableProjectExportResult.Ready(DocumentAsset(portableProjectName(project.mainCircuitName) + ".lsebdl",
            PORTABLE_PROJECT_MIME_TYPE, CircBundleCodec.write(bundle)))
    } catch (failure: Exception) {
        PortableProjectExportResult.Failure(listOf(portableProjectNotice(failure, "portable_bundle_export_failed")))
    }
}

internal fun portableProjectNotice(failure: Throwable, fallback: String): LocalizedText = when (failure) {
    is LocalizedArgumentException -> failure.text
    is LocalizedStateException -> failure.text
    else -> message(fallback)
}

/** Source snapshots are linked to an editor identity, never to whichever document is currently visible. */
class PortableProjectSourceSession {
    private var identity: Long? = null
    private var archive: DocumentAsset? = null
    fun opened(documentIdentity: Long, copy: PortableProjectCopy) {
        identity = documentIdentity
        archive = copy.sourceArchive
    }
    fun archiveFor(documentIdentity: Long): DocumentAsset? = archive.takeIf { identity == documentIdentity }
    fun retainOnly(documentIdentity: Long) {
        if (identity != documentIdentity) { identity = null; archive = null }
    }
}

private fun portableProjectName(raw: String): String = raw.substringBeforeLast('.', raw)
    .map { if (it in "/\\:*?\"<>|" || it.code < 32) '_' else it }.joinToString("").trim().ifBlank { "project" }
