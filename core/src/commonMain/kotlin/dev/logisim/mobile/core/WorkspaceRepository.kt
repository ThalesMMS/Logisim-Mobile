package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message

/** Platform storage must replace a file atomically or leave its previous contents intact. */
interface WorkspaceStorage {
    fun read(name: String): String?
    fun writeAtomically(name: String, contents: String)
}

enum class WorkspaceLoadSource { EMPTY, CURRENT, BACKUP, LEGACY }

data class WorkspaceLoadResult(
    val xml: String?,
    val revision: Long = 0,
    val source: WorkspaceLoadSource = WorkspaceLoadSource.EMPTY,
    val notices: List<LocalizedText> = emptyList(),
    val readOnly: Boolean = false,
) {
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
}

sealed interface WorkspaceSaveResult {
    data class Success(val revision: Long) : WorkspaceSaveResult
    data class Failure(val notice: LocalizedText) : WorkspaceSaveResult {
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

/**
 * A recoverable checkpoint for the open project. The XML is kept intact inside a versioned
 * envelope; a second file retains the last valid revision before each replacement.
 */
class WorkspaceRepository(
    private val storage: WorkspaceStorage,
    private val validate: (String) -> Boolean,
) {
    fun load(legacyXml: String? = null): WorkspaceLoadResult {
        val notices = mutableListOf<LocalizedText>()
        val current = readSnapshot(CURRENT_FILE, notices)
        if (current != null) return loadSnapshot(current, WorkspaceLoadSource.CURRENT, notices)
        val backup = readSnapshot(BACKUP_FILE, notices)
        if (backup != null) {
            notices += message(Messages.StatusStorageRecovered)
            return loadSnapshot(backup, WorkspaceLoadSource.BACKUP, notices)
        }
        if (legacyXml != null) {
            if (!isValidXml(legacyXml)) {
                notices += message(Messages.ErrorLegacyProject)
                return WorkspaceLoadResult(legacyXml, source = WorkspaceLoadSource.LEGACY,
                    notices = notices + message("workspace_document_unavailable"), readOnly = true)
            } else {
                val saved = save(legacyXml)
                if (saved is WorkspaceSaveResult.Failure) notices += saved.notice
                return WorkspaceLoadResult(
                    legacyXml, (saved as? WorkspaceSaveResult.Success)?.revision ?: 0,
                    WorkspaceLoadSource.LEGACY, notices,
                )
            }
        }
        return WorkspaceLoadResult(null, notices = notices)
    }

    fun save(xml: String): WorkspaceSaveResult {
        if (!isValidXml(xml)) return WorkspaceSaveResult.Failure(
            message(Messages.ErrorStorageInvalid),
        )
        return try {
            val currentRaw = storage.read(CURRENT_FILE)
            val current = currentRaw?.let(::decode)
            val backupRaw = storage.read(BACKUP_FILE)
            val backup = backupRaw?.let(::decode)
            if (current?.let { !isValidXml(it.xml) } == true ||
                (current == null && backup?.let { !isValidXml(it.xml) } == true)) {
                return WorkspaceSaveResult.Failure(message("workspace_document_unavailable"))
            }
            if ((currentRaw != null && current == null && backup == null) ||
                (currentRaw == null && backupRaw != null && backup == null)) {
                return WorkspaceSaveResult.Failure(
                    message(Messages.ErrorStorageExisting),
                )
            }
            if (current?.xml == xml) return WorkspaceSaveResult.Success(current.revision)
            val revision = maxOf(current?.revision ?: 0, backup?.revision ?: 0) + 1
            checkLocalized(revision > 0) { message(Messages.ErrorStorageRevisionLimit) }
            if (current != null) storage.writeAtomically(BACKUP_FILE, currentRaw)
            else if (currentRaw != null) storage.writeAtomically(RECOVERED_FILE, currentRaw)
            storage.writeAtomically(CURRENT_FILE, encode(Snapshot(xml, revision)))
            WorkspaceSaveResult.Success(revision)
        } catch (error: Exception) {
            WorkspaceSaveResult.Failure(
                message(Messages.ErrorStorageSave, "reason" to error.localizedDescription(message(Messages.ErrorStorageGeneric))),
            )
        }
    }

    private data class Snapshot(val xml: String, val revision: Long)

    private fun readSnapshot(name: String, notices: MutableList<LocalizedText>): Snapshot? = try {
        storage.read(name)?.let { raw ->
            decode(raw).also {
                if (it == null) notices += message(Messages.ErrorStorageSnapshot, "name" to name)
            }
        }
    } catch (error: Exception) {
        notices += message(Messages.ErrorStorageLoad, "name" to name, "reason" to error.localizedDescription(message(Messages.ErrorStorageGeneric)))
        null
    }

    private fun loadSnapshot(snapshot: Snapshot, source: WorkspaceLoadSource, notices: List<LocalizedText>): WorkspaceLoadResult {
        val supported = isValidXml(snapshot.xml)
        return WorkspaceLoadResult(snapshot.xml, snapshot.revision, source,
            notices + if (supported) emptyList() else listOf(message("workspace_document_unavailable")), readOnly = !supported)
    }

    private fun isValidXml(xml: String): Boolean = try {
        xml.length <= MAX_XML_LENGTH && validate(xml)
    } catch (_: Exception) {
        false
    }

    private fun encode(snapshot: Snapshot): String = buildString {
        append("LOGISIM-WORKSPACE\n1\n")
        append(snapshot.revision).append('\n')
        append(snapshot.xml.length).append('\n')
        append(checksum(snapshot.xml)).append("\n\n")
        append(snapshot.xml)
    }

    private fun decode(raw: String): Snapshot? {
        val separator = raw.indexOf("\n\n")
        if (separator < 0 || separator > 128) return null
        val header = raw.substring(0, separator).split('\n')
        if (header.size != 5 || header[0] != "LOGISIM-WORKSPACE" || header[1] != "1") return null
        val revision = header[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val length = header[3].toIntOrNull()?.takeIf { it in 0..MAX_XML_LENGTH } ?: return null
        val xml = raw.substring(separator + 2)
        if (xml.length != length || checksum(xml) != header[4]) return null
        return Snapshot(xml, revision)
    }

    private fun checksum(xml: String): String {
        var hash = 0xcbf29ce484222325uL
        for (byte in xml.encodeToByteArray()) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
        return hash.toString(16)
    }

    companion object {
        const val CURRENT_FILE = "current.workspace"
        const val BACKUP_FILE = "previous.workspace"
        const val RECOVERED_FILE = "recovered-invalid.workspace"
        private const val MAX_XML_LENGTH = 2_000_000
    }
}
