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

interface WorkspaceDocumentLinks {
    fun read(projectId: WorkspaceProjectId): WorkspaceExternalDocument?
    fun write(link: WorkspaceExternalDocument)
    fun windowProject(windowId: String): WorkspaceProjectId?
    fun selectWindowProject(windowId: String, projectId: WorkspaceProjectId?)
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
    override fun read(projectId: WorkspaceProjectId): WorkspaceExternalDocument? = storage.withExclusiveAccess {
        val raw = storage.read("external-${projectId.value}.workspace-link") ?: return@withExclusiveAccess null
        val values = read(raw)
        if (values.size != 7 || values[0] != projectId.value) unreadable()
        val mode = DocumentOpenMode.entries.firstOrNull { it.name == values[5] } ?: unreadable()
        WorkspaceExternalDocument(projectId, DocumentReference(values[1], values[2], values[3].toBooleanStrictOrNull() ?: unreadable(),
            values[4].takeIf(String::isNotEmpty), mode), values[6])
    }

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
        val body = values.joinToString("") { "${it.length}:$it" }
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
}
