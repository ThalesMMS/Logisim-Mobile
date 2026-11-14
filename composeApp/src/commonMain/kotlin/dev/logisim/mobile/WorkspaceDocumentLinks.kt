package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message

data class WorkspaceExternalDocument(
    val projectId: WorkspaceProjectId,
    val reference: DocumentReference,
    val workspaceStamp: String,
)

sealed interface WorkspaceRecentFile {
    val name: String

    data class Original(val reference: DocumentReference) : WorkspaceRecentFile {
        override val name get() = reference.name
    }

    data class Copy(val projectId: WorkspaceProjectId, override val name: String) : WorkspaceRecentFile
}

/**
 * What a linked project's file was last known to hold: [workspaceStamp] is the project content that was read from
 * or written to the file with reference [referenceId], or null when nothing vouches for the file (it may be stale).
 * It is kept apart from [WorkspaceExternalDocument.workspaceStamp], which follows every autosave.
 */
data class WorkspaceExternalSync(val referenceId: String, val workspaceStamp: String?)

interface WorkspaceDocumentLinks {
    fun read(projectId: WorkspaceProjectId): WorkspaceExternalDocument?
    fun write(link: WorkspaceExternalDocument)
    fun windowProject(windowId: String): WorkspaceProjectId?
    fun selectWindowProject(windowId: String, projectId: WorkspaceProjectId?)
    fun recentFiles(): List<WorkspaceRecentFile>
    fun recordRecent(file: WorkspaceRecentFile, replaces: WorkspaceRecentFile? = null): List<WorkspaceRecentFile>
    fun readSync(projectId: WorkspaceProjectId): WorkspaceExternalSync? = null
    fun writeSync(projectId: WorkspaceProjectId, sync: WorkspaceExternalSync) = Unit
    fun removeRecent(file: WorkspaceRecentFile): List<WorkspaceRecentFile> = recentFiles()
    /** Projects whose link names the file [referenceId]. */
    fun linkedProjects(referenceId: String): List<WorkspaceProjectId> = emptyList()
    /** Drops everything kept for a permanently deleted project: its link, its sync record and its recent entries. */
    fun forget(projectId: WorkspaceProjectId) = Unit
}

/** Accidental-content identity check, not an authorization token or a file revision. */
fun workspaceContentStamp(xml: String): String {
    val bytes = xml.encodeToByteArray()
    var hash = 0xcbf29ce484222325uL
    bytes.forEach { hash = (hash xor it.toUByte().toULong()) * 0x100000001b3uL }
    return "${bytes.size}-${hash.toString(16)}"
}

/** Opaque native references are indexed by project, independently of the last active window. */
class StoredWorkspaceDocumentLinks(private val storage: WorkspaceCatalogStorage) : WorkspaceDocumentLinks {
    override fun read(projectId: WorkspaceProjectId): WorkspaceExternalDocument? = storage.withExclusiveAccess { readLink(projectId) }

    override fun write(link: WorkspaceExternalDocument) = storage.withExclusiveAccess {
        require(link.reference.id.isNotBlank() && link.workspaceStamp.matches(Regex("[0-9]+-[0-9a-f]+")))
        write("external-${link.projectId.value}.workspace-link", listOf(link.projectId.value, link.reference.id,
            link.reference.name, link.reference.writable.toString(), link.reference.revision.orEmpty(), link.reference.mode.name, link.workspaceStamp))
    }

    override fun windowProject(windowId: String): WorkspaceProjectId? = storage.withExclusiveAccess {
        val raw = storage.read(windowName(windowId)) ?: return@withExclusiveAccess null
        val values = read(raw)
        if (values.size != 2 || values[0] != windowId) unreadable()
        values[1].takeIf(String::isNotEmpty)?.let(::WorkspaceProjectId)
    }

    override fun selectWindowProject(windowId: String, projectId: WorkspaceProjectId?) = storage.withExclusiveAccess {
        write(windowName(windowId), listOf(windowId, projectId?.value.orEmpty()))
    }

    override fun recentFiles(): List<WorkspaceRecentFile> = storage.withExclusiveAccess { readRecent() }

    /**
     * A separate file, so builds that read only the seven-field link keep working; a missing or unreadable record
     * means nothing vouches for the file.
     */
    override fun readSync(projectId: WorkspaceProjectId): WorkspaceExternalSync? = storage.withExclusiveAccess {
        val raw = storage.read(syncName(projectId)) ?: return@withExclusiveAccess null
        val values = runCatching { read(raw) }.getOrNull() ?: return@withExclusiveAccess null
        if (values.size != 3 || values[0] != projectId.value) return@withExclusiveAccess null
        WorkspaceExternalSync(values[1], values[2].takeIf { it.matches(STAMP) })
    }

    override fun writeSync(projectId: WorkspaceProjectId, sync: WorkspaceExternalSync) = storage.withExclusiveAccess {
        require(sync.referenceId.isNotBlank() && (sync.workspaceStamp == null || sync.workspaceStamp.matches(STAMP)))
        write(syncName(projectId), listOf(projectId.value, sync.referenceId, sync.workspaceStamp.orEmpty()))
    }

    override fun removeRecent(file: WorkspaceRecentFile): List<WorkspaceRecentFile> = storage.withExclusiveAccess {
        val files = readRecent().filterNot { it.sameIdentity(file) }
        writeRecent(files)
        files
    }

    override fun linkedProjects(referenceId: String): List<WorkspaceProjectId> = storage.withExclusiveAccess {
        storage.listNames().mapNotNull { name -> LINK.matchEntire(name)?.groupValues?.get(1) }.mapNotNull { id ->
            // Already holding the lock: the platform locks are not reentrant (iOS flock on a second descriptor blocks).
            runCatching { readLink(WorkspaceProjectId(id)) }.getOrNull()?.takeIf { it.reference.id == referenceId }?.projectId
        }
    }

    override fun forget(projectId: WorkspaceProjectId) = storage.withExclusiveAccess {
        if (storage.supportsDelete) {
            storage.delete("external-${projectId.value}.workspace-link")
            storage.delete(syncName(projectId))
        }
        writeRecent(readRecent().filterNot { it is WorkspaceRecentFile.Copy && it.projectId == projectId })
    }

    override fun recordRecent(file: WorkspaceRecentFile, replaces: WorkspaceRecentFile?): List<WorkspaceRecentFile> = storage.withExclusiveAccess {
        val files = (listOf(file) + readRecent().filterNot { it.sameIdentity(file) || it.sameIdentity(replaces) }).take(10)
        writeRecent(files)
        files
    }

    private fun syncName(projectId: WorkspaceProjectId) = "external-${projectId.value}.workspace-sync"

    /** The body of [read]; callers hold [WorkspaceCatalogStorage.withExclusiveAccess]. */
    private fun readLink(projectId: WorkspaceProjectId): WorkspaceExternalDocument? {
        val raw = storage.read("external-${projectId.value}.workspace-link") ?: return null
        val values = read(raw)
        if (values.size != 7 || values[0] != projectId.value) unreadable()
        val mode = DocumentOpenMode.entries.firstOrNull { it.name == values[5] } ?: unreadable()
        return WorkspaceExternalDocument(projectId, DocumentReference(values[1], values[2], values[3].toBooleanStrictOrNull() ?: unreadable(),
            values[4].takeIf(String::isNotEmpty), mode), values[6])
    }

    private fun writeRecent(files: List<WorkspaceRecentFile>) {
        write(RECENT_FILES, files.map { entry ->
            pack(when (entry) {
                is WorkspaceRecentFile.Original -> with(entry.reference) {
                    listOf("original", id, name, writable.toString(), revision.orEmpty())
                }
                is WorkspaceRecentFile.Copy -> listOf("copy", entry.projectId.value, entry.name)
            })
        })
    }

    private fun WorkspaceRecentFile.sameIdentity(other: WorkspaceRecentFile?): Boolean = when (this) {
        is WorkspaceRecentFile.Original -> other is WorkspaceRecentFile.Original && other.reference.id == reference.id
        is WorkspaceRecentFile.Copy -> other is WorkspaceRecentFile.Copy && other.projectId == projectId
    }

    private fun readRecent(): List<WorkspaceRecentFile> = storage.read(RECENT_FILES)?.let(::read).orEmpty().map { entry ->
        val fields = unpack(entry)
        when {
            fields.size == 5 && fields[0] == "original" && fields[1].isNotBlank() -> WorkspaceRecentFile.Original(
                DocumentReference(fields[1], fields[2], fields[3].toBooleanStrictOrNull() ?: unreadable(),
                    fields[4].takeIf(String::isNotEmpty), DocumentOpenMode.EDIT_IN_PLACE))
            fields.size == 3 && fields[0] == "copy" -> WorkspaceRecentFile.Copy(WorkspaceProjectId(fields[1]), fields[2])
            else -> unreadable()
        }
    }

    private fun windowName(windowId: String): String {
        require(windowId.isNotBlank() && windowId.length <= 500)
        return "window-${workspaceContentStamp(windowId)}.workspace-link"
    }

    private fun write(name: String, values: List<String>) {
        val old = storage.read(name)
        if (old != null) {
            if (old.startsWith("LOGISIM-LINK\n") && old.substringAfter('\n').substringBefore('\n') != "1") unreadable()
            if (runCatching { read(old) }.isFailure) {
                storage.writeAtomically("${name}-${workspaceContentStamp(old)}.invalid", old)
            }
        }
        val body = pack(values)
        require(body.length <= 2_000_000)
        storage.writeAtomically(name, "LOGISIM-LINK\n1\n${workspaceContentStamp(body)}\n\n$body")
    }

    private fun read(raw: String): List<String> {
        if (raw.length > 2_000_100) unreadable()
        val endHeader = raw.indexOf("\n\n")
        if (endHeader !in 1..128) unreadable()
        val header = raw.substring(0, endHeader).split('\n')
        val body = raw.substring(endHeader + 2)
        if (header.size != 3 || header[0] != "LOGISIM-LINK" || header[1] != "1" || header[2] != workspaceContentStamp(body)) unreadable()
        return unpack(body)
    }

    private fun pack(values: List<String>) = values.joinToString("") { "${it.length}:$it" }

    private fun unpack(body: String): List<String> {
        val values = mutableListOf<String>()
        var index = 0
        while (index < body.length) {
            val separator = body.indexOf(':', index)
            if (separator !in index..index + 8) unreadable()
            val length = body.substring(index, separator).toIntOrNull() ?: unreadable()
            if (length < 0 || length > body.length - separator - 1 || values.size >= 10) unreadable()
            index = separator + 1 + length
            values += body.substring(separator + 1, index)
        }
        return values
    }

    private fun unreadable(): Nothing = throw LocalizedStateException(message("workspace_external_link_unreadable"))

    private companion object {
        const val RECENT_FILES = "recent-files.workspace-link"
        val LINK = Regex("external-([A-Za-z0-9_-]{1,80})\\.workspace-link")
        val STAMP = Regex("[0-9]+-[0-9a-f]+")
    }
}
