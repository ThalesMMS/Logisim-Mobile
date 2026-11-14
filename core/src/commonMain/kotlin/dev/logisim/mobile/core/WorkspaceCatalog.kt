package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.systemAppLanguage

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.jvm.JvmInline

interface WorkspaceCatalogStorage : WorkspaceStorage {
    fun <T> withExclusiveAccess(action: () -> T): T
    fun listNames(): List<String>

    /**
     * Removes [name] if it exists. Only an explicit permanent deletion uses it ([WorkspaceCatalogRepository.purge]);
     * a store that cannot delete ([supportsDelete] false) refuses, and the project stays in the trash.
     */
    fun delete(name: String): Unit = throw UnsupportedOperationException("delete")
    val supportsDelete: Boolean get() = false
}

@JvmInline
value class WorkspaceProjectId(val value: String) {
    init { requireLocalized(value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { message(Messages.WorkspaceErrorId) } }
}

data class WorkspaceViewport(val scale: Float, val panX: Float, val panY: Float) {
    init { require(scale.isFinite() && scale > 0 && panX.isFinite() && panY.isFinite()) }
}

enum class WorkspaceViewportKeyFormat { LEGACY, LENGTH_PREFIXED }

data class WorkspaceViewState(
    val activeCircuitId: String? = null,
    val selectedComponentIds: Set<String> = emptySet(),
    val viewports: Map<String, WorkspaceViewport> = emptyMap(),
    val activeInstancePath: List<String> = emptyList(),
    val selectedWires: Set<WireSegment> = emptySet(),
    val viewportKeyFormat: WorkspaceViewportKeyFormat = WorkspaceViewportKeyFormat.LEGACY,
)

data class WorkspaceProjectInfo(
    val id: WorkspaceProjectId,
    val name: String,
    val revision: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deleted: Boolean = false,
    val previousRevision: Long = 0,
    val history: List<Long> = emptyList(),
)

data class WorkspaceDocument(
    val info: WorkspaceProjectInfo,
    val xml: String,
    val viewState: WorkspaceViewState = WorkspaceViewState(),
    val contentRevision: Long = info.revision,
    val notices: List<LocalizedText> = emptyList(),
    val readOnly: Boolean = false,
    val attachments: Map<String, WorkspaceAttachmentReference> = emptyMap(),
) {
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
}

data class WorkspaceCatalogResult(
    val projects: List<WorkspaceProjectInfo>,
    val activeProjectId: WorkspaceProjectId?,
    val notices: List<LocalizedText> = emptyList(),
    val readOnly: Boolean = false,
) {
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }
}

sealed interface WorkspaceOpenResult {
    data class Success(val document: WorkspaceDocument) : WorkspaceOpenResult
    data object Missing : WorkspaceOpenResult
    data class Failure(val notice: LocalizedText) : WorkspaceOpenResult {
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

sealed interface WorkspaceMutationResult {
    data class Success(val document: WorkspaceDocument) : WorkspaceMutationResult
    data class Conflict(val current: WorkspaceDocument) : WorkspaceMutationResult
    data object Missing : WorkspaceMutationResult
    data class Failure(val notice: LocalizedText) : WorkspaceMutationResult {
        val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
    }
}

/** The catalog replacement commits a revision. Payloads written before it remain unreferenced. */
class WorkspaceCatalogRepository(
    private val storage: WorkspaceCatalogStorage,
    private val validate: (String) -> Boolean,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val idFactory: () -> String = { Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') } },
) {
    fun load(legacyXml: String? = null, legacyName: String = message(Messages.WorkspaceRecoveredProject).resolve(systemAppLanguage())): WorkspaceCatalogResult = lockedCatalog {
        val loaded = readCatalog()
        if (loaded.readOnly || loaded.catalog.legacyHandled) return@lockedCatalog loaded.result()
        val legacy = WorkspaceRepository(storage, validate).load(legacyXml)
        if (legacy.xml == null) return@lockedCatalog loaded.result(legacy.notices)
        val created = createIn(loaded, checkedName(legacyName), legacy.xml, WorkspaceViewState(), preserveUnsupported = legacy.readOnly)
        when (created) {
            is WorkspaceMutationResult.Success -> readCatalog().result(legacy.notices + message(Messages.WorkspaceMigrated))
            is WorkspaceMutationResult.Failure -> loaded.result(legacy.notices + created.notice)
            else -> loaded.result(legacy.notices)
        }
    }

    fun list(includeDeleted: Boolean = false): WorkspaceCatalogResult = lockedCatalog {
        readCatalog().result(includeDeleted = includeDeleted)
    }

    fun open(id: WorkspaceProjectId): WorkspaceOpenResult = try {
        storage.withExclusiveAccess {
            val loaded = readCatalog()
            if (loaded.readOnly) return@withExclusiveAccess WorkspaceOpenResult.Failure(READ_ONLY)
            val info = loaded.catalog.projects.firstOrNull { it.id == id && !it.deleted }
                ?: return@withExclusiveAccess WorkspaceOpenResult.Missing
            readDocument(info, loaded.notices)
        }
    } catch (error: Exception) { WorkspaceOpenResult.Failure(storageError(error)) }

    fun create(name: String, xml: String, viewState: WorkspaceViewState = WorkspaceViewState(),
               attachments: Map<String, WorkspaceAttachment> = emptyMap()): WorkspaceMutationResult = mutation {
        createIn(readCatalog(), checkedName(name), xml, viewState, attachments = attachments)
    }

    fun readAttachment(id: WorkspaceProjectId, key: String): WorkspaceAttachmentReadResult = try {
        storage.withExclusiveAccess {
            val loaded = readCatalog()
            if (loaded.readOnly) return@withExclusiveAccess WorkspaceAttachmentReadResult.Failure(READ_ONLY)
            val info = loaded.catalog.projects.firstOrNull { it.id == id }
                ?: return@withExclusiveAccess WorkspaceAttachmentReadResult.Missing
            when (val opened = readDocument(info, loaded.notices)) {
                is WorkspaceOpenResult.Success -> {
                    val reference = opened.document.attachments[key]
                        ?: return@withExclusiveAccess WorkspaceAttachmentReadResult.Missing
                    readAttachmentBlob(key, reference)
                }
                is WorkspaceOpenResult.Failure -> WorkspaceAttachmentReadResult.Failure(opened.notice)
                WorkspaceOpenResult.Missing -> WorkspaceAttachmentReadResult.Missing
            }
        }
    } catch (error: Exception) { WorkspaceAttachmentReadResult.Failure(storageError(error)) }

    fun save(id: WorkspaceProjectId, expectedRevision: Long, xml: String,
             viewState: WorkspaceViewState = WorkspaceViewState()): WorkspaceMutationResult = edit(id, expectedRevision) { current ->
        requireValidXml(xml)
        if (current.xml == xml && current.viewState == viewState && current.contentRevision == current.info.revision) current
        else current.copy(xml = xml, viewState = viewState)
    }

    fun rename(id: WorkspaceProjectId, expectedRevision: Long, name: String): WorkspaceMutationResult = edit(id, expectedRevision, allowReadOnly = true) {
        it.copy(info = it.info.copy(name = checkedName(name)))
    }

    fun delete(id: WorkspaceProjectId, expectedRevision: Long): WorkspaceMutationResult = edit(id, expectedRevision, allowReadOnly = true) {
        it.copy(info = it.info.copy(deleted = true))
    }

    fun restore(id: WorkspaceProjectId, expectedRevision: Long): WorkspaceMutationResult = edit(id, expectedRevision, allowDeleted = true, allowReadOnly = true) {
        it.copy(info = it.info.copy(deleted = false))
    }

    fun duplicate(id: WorkspaceProjectId, expectedRevision: Long, name: String? = null): WorkspaceMutationResult = mutation {
        val loaded = readCatalog()
        if (loaded.readOnly) return@mutation WorkspaceMutationResult.Failure(READ_ONLY)
        val info = loaded.catalog.projects.firstOrNull { it.id == id && !it.deleted }
            ?: return@mutation WorkspaceMutationResult.Missing
        val current = (readDocument(info, loaded.notices) as? WorkspaceOpenResult.Success)?.document
            ?: return@mutation WorkspaceMutationResult.Failure(message(Messages.WorkspaceErrorSource))
        if (info.revision != expectedRevision) return@mutation WorkspaceMutationResult.Conflict(current)
        val attachmentProblems = attachmentNotices(current.attachments)
        if (attachmentProblems.isNotEmpty()) return@mutation WorkspaceMutationResult.Failure(LocalizedText.join(attachmentProblems))
        createIn(loaded, checkedName(name ?: message(Messages.WorkspaceCopyName, "name" to info.name).resolve(systemAppLanguage())),
            current.xml, current.viewState, preserveUnsupported = current.readOnly, attachmentReferences = current.attachments)
    }

    /**
     * Permanently deletes a project from the trash: the catalog forgets it first (its backup too), then every saved
     * revision is removed, and so is each attachment blob no remaining revision of any project references. It cannot
     * be undone. Success carries the project's last revision as it was before the deletion.
     */
    fun purge(id: WorkspaceProjectId, expectedRevision: Long): WorkspaceMutationResult = mutation {
        val loaded = readCatalog()
        if (loaded.readOnly) return@mutation WorkspaceMutationResult.Failure(READ_ONLY)
        if (!storage.supportsDelete) return@mutation WorkspaceMutationResult.Failure(message(Messages.ErrorStorageGeneric))
        val info = loaded.catalog.projects.firstOrNull { it.id == id } ?: return@mutation WorkspaceMutationResult.Missing
        val document = (readDocument(info, loaded.notices) as? WorkspaceOpenResult.Success)?.document ?: WorkspaceDocument(info, "")
        if (info.revision != expectedRevision || !info.deleted) return@mutation WorkspaceMutationResult.Conflict(document)
        val own = storage.listNames().filter { payloadOf(it) == id.value }
        val attachments = attachmentNames(own)
        val remaining = loaded.catalog.copy(projects = loaded.catalog.projects.filterNot { it.id == id },
            activeId = loaded.catalog.activeId.takeIf { it != id })
        commitCatalog(loaded, remaining)
        // The backup still listed the project; replace it too, so a recovery from it cannot point at deleted revisions.
        commitCatalog(readCatalog(), remaining)
        own.forEach(storage::delete)
        if (attachments.isNotEmpty()) {
            val referenced = attachmentNames(storage.listNames().filter { payloadOf(it) != null })
            (attachments - referenced).forEach(storage::delete)
        }
        WorkspaceMutationResult.Success(document)
    }

    /** The project ID of a revision payload name, or null for any other stored name. */
    private fun payloadOf(name: String): String? = PAYLOAD.matchEntire(name)?.groupValues?.get(1)

    private fun attachmentNames(payloads: List<String>): Set<String> = payloads.flatMapTo(mutableSetOf()) { name ->
        storage.read(name)?.let(WorkspaceCatalogFormat::readDocument)?.attachments?.values.orEmpty()
            .map(WorkspaceAttachmentBlobs::storageName)
    }

    fun selectActive(id: WorkspaceProjectId?): WorkspaceCatalogResult = lockedCatalog {
        val loaded = readCatalog()
        if (loaded.readOnly) return@lockedCatalog loaded.result()
        requireLocalized(id == null || loaded.catalog.projects.any { it.id == id && !it.deleted }) { message(Messages.WorkspaceErrorMissing) }
        if (loaded.catalog.activeId != id) commitCatalog(loaded, loaded.catalog.copy(activeId = id))
        readCatalog().result()
    }

    private fun createIn(loaded: LoadedCatalog, name: String, xml: String, viewState: WorkspaceViewState,
                         preserveUnsupported: Boolean = false,
                         attachments: Map<String, WorkspaceAttachment> = emptyMap(),
                         attachmentReferences: Map<String, WorkspaceAttachmentReference> = emptyMap()): WorkspaceMutationResult {
        if (loaded.readOnly) return WorkspaceMutationResult.Failure(READ_ONLY)
        if (preserveUnsupported) requireLocalized(xml.length <= 16_000_000) { message(Messages.WorkspaceErrorDocument) }
        else requireValidXml(xml)
        val sources = attachments.toMap()
        val references = if (sources.isEmpty()) attachmentReferences.toMap() else WorkspaceAttachmentBlobs.references(sources)
        WorkspaceAttachmentBlobs.validateReferences(references)
        val id = generateSequence { WorkspaceProjectId(idFactory()) }
            .take(100).firstOrNull { candidate -> loaded.catalog.projects.none { it.id == candidate } && storage.listNames().none { it.startsWith("project-${candidate.value}-") } }
            ?: return WorkspaceMutationResult.Failure(message(Messages.WorkspaceErrorUniqueId))
        val now = clock()
        val document = WorkspaceDocument(WorkspaceProjectInfo(id, name, 1, now, now), xml, viewState,
            readOnly = preserveUnsupported && !validXml(xml), attachments = references)
        writeAttachments(sources)
        writePayload(document)
        commitCatalog(loaded, loaded.catalog.copy(projects = loaded.catalog.projects + document.info, activeId = id, legacyHandled = true))
        return WorkspaceMutationResult.Success(document)
    }

    private fun edit(id: WorkspaceProjectId, expectedRevision: Long, allowDeleted: Boolean = false,
                     allowReadOnly: Boolean = false, transform: (WorkspaceDocument) -> WorkspaceDocument): WorkspaceMutationResult = mutation {
        val loaded = readCatalog()
        if (loaded.readOnly) return@mutation WorkspaceMutationResult.Failure(READ_ONLY)
        val info = loaded.catalog.projects.firstOrNull { it.id == id && (allowDeleted || !it.deleted) }
            ?: return@mutation WorkspaceMutationResult.Missing
        val opened = readDocument(info, loaded.notices)
        if (opened !is WorkspaceOpenResult.Success) return@mutation WorkspaceMutationResult.Failure(
            (opened as? WorkspaceOpenResult.Failure)?.notice ?: message(Messages.WorkspaceErrorMissing))
        val current = opened.document
        if (info.revision != expectedRevision) return@mutation WorkspaceMutationResult.Conflict(current)
        if (current.readOnly && !allowReadOnly) return@mutation WorkspaceMutationResult.Failure(message("workspace_document_unavailable"))
        val changed = transform(current)
        if (changed == current && current.contentRevision == current.info.revision) return@mutation WorkspaceMutationResult.Success(current)
        var nextRevision = info.revision + 1
        while (nextRevision > 0 && storage.read(payloadName(id, nextRevision)) != null) nextRevision++
        checkLocalized(nextRevision > 0) { message(Messages.ErrorStorageRevisionLimit) }
        // "Edited" is about the project, not where it was panned or what was selected: a view-only save keeps the time.
        val viewOnly = changed.xml == current.xml && changed.info == current.info && changed.attachments == current.attachments
        val next = changed.copy(info = changed.info.copy(revision = nextRevision, previousRevision = info.revision,
            history = (listOf(info.revision) + info.history).distinct(),
            updatedAtMillis = if (viewOnly) info.updatedAtMillis else clock()), contentRevision = nextRevision, notices = emptyList())
        writePayload(next)
        commitCatalog(loaded, loaded.catalog.copy(projects = loaded.catalog.projects.map { if (it.id == id) next.info else it },
            activeId = if (next.info.deleted && loaded.catalog.activeId == id) null else loaded.catalog.activeId))
        WorkspaceMutationResult.Success(next)
    }

    private fun readDocument(info: WorkspaceProjectInfo, notices: List<LocalizedText>): WorkspaceOpenResult {
        for (revision in (listOf(info.revision) + info.history + info.previousRevision).filter { it > 0 }.distinct()) {
            val raw = storage.read(payloadName(info.id, revision))
            if (raw != null && WorkspaceCatalogFormat.usesUnsupportedProjectVersion(raw)) {
                return WorkspaceOpenResult.Failure(message(Messages.WorkspaceErrorFutureRevision, "revision" to revision))
            }
            val payload = raw?.let { WorkspaceCatalogFormat.readDocument(it) }
            if (payload != null && payload.info.id == info.id && payload.info.revision == revision) {
                val supported = validXml(payload.xml)
                val attachmentProblems = attachmentNotices(payload.attachments)
                val notes = (if (revision != info.revision) notices + message(Messages.WorkspaceRevisionRecovered, "recovered" to revision, "latest" to info.revision) else notices) +
                    (if (supported) emptyList() else listOf(message("workspace_document_unavailable"))) + attachmentProblems
                return WorkspaceOpenResult.Success(payload.copy(info = info, contentRevision = revision, notices = notes,
                    readOnly = !supported || attachmentProblems.isNotEmpty()))
            }
        }
        return WorkspaceOpenResult.Failure(message(Messages.WorkspaceErrorNoRevision, "name" to info.name))
    }

    private fun writeAttachments(attachments: Map<String, WorkspaceAttachment>) {
        val references = WorkspaceAttachmentBlobs.references(attachments)
        val written = mutableSetOf<String>()
        for ((key, attachment) in attachments.entries.sortedBy { it.key }) {
            val reference = references.getValue(key)
            if (!written.add(reference.sha256)) continue
            val name = WorkspaceAttachmentBlobs.storageName(reference)
            val existing = storage.read(name)
            if (existing == null) storage.writeAtomically(name, WorkspaceAttachmentBlobs.encode(attachment))
            else checkLocalized(WorkspaceAttachmentBlobs.decode(existing, reference) != null) {
                message("workspace_attachment_corrupt", "key" to key, "name" to reference.name)
            }
        }
    }

    private fun readAttachmentBlob(key: String, reference: WorkspaceAttachmentReference): WorkspaceAttachmentReadResult = try {
        val raw = storage.read(WorkspaceAttachmentBlobs.storageName(reference))
        if (raw == null) WorkspaceAttachmentReadResult.Failure(message("workspace_attachment_missing", "key" to key, "name" to reference.name))
        else WorkspaceAttachmentBlobs.decode(raw, reference)?.let(WorkspaceAttachmentReadResult::Success)
            ?: WorkspaceAttachmentReadResult.Failure(message("workspace_attachment_corrupt", "key" to key, "name" to reference.name))
    } catch (error: Exception) {
        WorkspaceAttachmentReadResult.Failure(message("workspace_attachment_access", "key" to key, "name" to reference.name,
            "reason" to error.localizedDescription(message(Messages.ErrorStorageGeneric))))
    }

    private fun attachmentNotices(references: Map<String, WorkspaceAttachmentReference>): List<LocalizedText> =
        references.entries.sortedBy { it.key }.mapNotNull { (key, reference) ->
            (readAttachmentBlob(key, reference) as? WorkspaceAttachmentReadResult.Failure)?.notice
        }

    private fun writePayload(document: WorkspaceDocument) {
        val name = payloadName(document.info.id, document.info.revision)
        checkLocalized(storage.read(name) == null) { message(Messages.WorkspaceErrorReplaceRevision) }
        storage.writeAtomically(name, WorkspaceCatalogFormat.writeDocument(document))
    }

    private fun readCatalog(): LoadedCatalog {
        val currentRaw = storage.read(CATALOG_FILE)
        if (currentRaw != null && WorkspaceCatalogFormat.usesUnsupportedCatalogVersion(currentRaw)) {
            return LoadedCatalog(Catalog(), null, listOf(READ_ONLY), readOnly = true)
        }
        val current = currentRaw?.let(WorkspaceCatalogFormat::readCatalog)
        if (current != null) return LoadedCatalog(current, currentRaw)
        val backupRaw = storage.read(BACKUP_FILE)
        if (backupRaw != null && WorkspaceCatalogFormat.usesUnsupportedCatalogVersion(backupRaw)) {
            return LoadedCatalog(Catalog(), null, listOf(READ_ONLY), readOnly = true)
        }
        val backup = backupRaw?.let(WorkspaceCatalogFormat::readCatalog)
        if (backup != null) return LoadedCatalog(backup, null,
            listOf(message(Messages.WorkspaceCatalogRecovered)), invalidRaw = currentRaw)
        if (currentRaw != null || backupRaw != null) return LoadedCatalog(Catalog(), null,
            listOf(READ_ONLY), readOnly = true)
        return LoadedCatalog(Catalog(), null)
    }

    private fun commitCatalog(loaded: LoadedCatalog, catalog: Catalog) {
        checkLocalized(!loaded.readOnly) { READ_ONLY }
        loaded.invalidRaw?.let { raw ->
            val name = "catalog-invalid-${WorkspaceCatalogFormat.checksum(raw)}.workspace"
            if (storage.read(name) == null) storage.writeAtomically(name, raw)
        }
        val previous = loaded.validRaw ?: WorkspaceCatalogFormat.writeCatalog(loaded.catalog)
        storage.writeAtomically(BACKUP_FILE, previous)
        storage.writeAtomically(CATALOG_FILE, WorkspaceCatalogFormat.writeCatalog(catalog))
    }

    private fun checkedName(name: String): String = name.trim().also {
        requireLocalized(it.isNotEmpty() && it.length <= 200 && it.none { character -> character.code < 32 }) { message(Messages.WorkspaceErrorName) }
    }
    private fun validXml(xml: String) = xml.length <= 16_000_000 && runCatching { validate(xml) }.getOrDefault(false)
    private fun requireValidXml(xml: String) = requireLocalized(validXml(xml)) { message(Messages.WorkspaceErrorDocument) }
    private fun storageError(error: Exception) = message(Messages.WorkspaceErrorAccess, "reason" to error.localizedDescription(message(Messages.ErrorStorageGeneric)))
    private fun mutation(action: () -> WorkspaceMutationResult): WorkspaceMutationResult = try {
        storage.withExclusiveAccess(action)
    } catch (error: Exception) { WorkspaceMutationResult.Failure(storageError(error)) }
    private fun lockedCatalog(action: () -> WorkspaceCatalogResult): WorkspaceCatalogResult = try {
        storage.withExclusiveAccess(action)
    } catch (error: Exception) { WorkspaceCatalogResult(emptyList(), null, listOf(storageError(error)), readOnly = true) }

    internal data class Catalog(val projects: List<WorkspaceProjectInfo> = emptyList(),
                                val activeId: WorkspaceProjectId? = null, val legacyHandled: Boolean = false)
    private data class LoadedCatalog(val catalog: Catalog, val validRaw: String?, val notices: List<LocalizedText> = emptyList(),
                                     val readOnly: Boolean = false, val invalidRaw: String? = null) {
        fun result(additional: List<LocalizedText> = emptyList(), includeDeleted: Boolean = false) = WorkspaceCatalogResult(
            catalog.projects.filter { includeDeleted || !it.deleted }, catalog.activeId, notices + additional, readOnly)
    }

    companion object {
        const val CATALOG_FILE = "catalog.workspace"
        const val BACKUP_FILE = "catalog.previous"
        private val READ_ONLY = message(Messages.WorkspaceReadOnly)
        // IDs may contain '-', so the revision is the last group of digits.
        private val PAYLOAD = Regex("project-([A-Za-z0-9_-]{1,80})-([0-9]+)\\.workspace")
        private fun payloadName(id: WorkspaceProjectId, revision: Long) = "project-${id.value}-$revision.workspace"
    }
}
