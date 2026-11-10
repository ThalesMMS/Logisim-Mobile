package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ProjectBuiltinLibraries
import dev.logisim.mobile.core.DemoCircuits
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceAttachment
import dev.logisim.mobile.core.WorkspaceAttachmentReadResult
import dev.logisim.mobile.core.WorkspaceCatalogResult
import dev.logisim.mobile.core.WorkspaceDocument
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceOpenResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceSession
import dev.logisim.mobile.core.WorkspaceSessionState
import dev.logisim.mobile.core.WorkspaceViewState
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message

data class CatalogWorkspaceState(
    val catalog: WorkspaceCatalogResult,
    val session: WorkspaceSessionState,
    val editorMount: Long,
    val editorXml: String?,
    val editorProjectId: WorkspaceProjectId?,
    val notices: List<LocalizedText> = emptyList(),
    val readOnly: Boolean = false,
    val editorImportReport: EditorImportReport? = null,
    val editorClosed: Boolean = false,
    val recentFiles: List<WorkspaceRecentFile> = emptyList(),
)

internal data class WorkspaceEditorScope(
    val mount: Long,
    val projectId: WorkspaceProjectId?,
    val identity: Long,
    val revision: Long,
)

internal data class WorkspaceSessionSave(
    val scope: WorkspaceEditorScope,
    val reference: DocumentReference?,
    val xml: String,
)

/** A mount identifies an editor window; document identities distinguish replace/undo within it. */
class CatalogWorkspaceBinding(
    private val repository: WorkspaceCatalogRepository,
    private val links: WorkspaceDocumentLinks,
    private val windowId: String,
    private val pauseSimulation: () -> Unit,
    private val exportView: () -> WorkspaceViewState? = { null },
    private val exportXml: () -> String? = { null },
    private val editorIdentity: () -> Long? = { null },
    private val editorWritable: () -> Boolean? = { null },
    nativeDocuments: DocumentActions? = null,
    private val externalWorkspaceConfirmed: (String) -> Unit = {},
    private val onStateChanged: (CatalogWorkspaceState) -> Unit = {},
) {
    private var catalog = repository.list(includeDeleted = true)
    private var mount = 0L
    private var attachedMount: Long? = null
    private var mountedXml: String? = null
    private var projectId: WorkspaceProjectId? = null
    private var currentIdentity = 0L
    private val identityProjects = mutableMapOf<Long, WorkspaceProjectId>()
    private val importReports = mutableMapOf<WorkspaceProjectId, EditorImportReport>()
    private val validatedLinks = mutableSetOf<WorkspaceProjectId>()
    private var draftXml: String? = null
    private var draftReference: DocumentReference? = null
    private var draftImportReport: EditorImportReport? = null
    private var draftReadOnly = false
    private var editorReadOnly = false
    private var initialized = false
    private var editorClosed = false
    // Autosave preserves crash recovery. This is the explicit save point for each open tab.
    private data class SessionCheckpoint(val xml: String, val view: WorkspaceViewState, val revision: Long)
    private val sessionCheckpoints = mutableMapOf<WorkspaceProjectId, SessionCheckpoint>()
    private var notices = emptyList<LocalizedText>()
    private var recentFiles = emptyList<WorkspaceRecentFile>()
    private val session = WorkspaceSession(repository, pauseSimulation) { publish() }
    private val documentBinding = nativeDocuments?.let { native -> CatalogDocumentActions(native,
        object : WorkspaceDocumentCoordinator {
            override val state get() = this@CatalogWorkspaceBinding.state
            override fun scope() = this@CatalogWorkspaceBinding.scope()
            override fun matches(scope: WorkspaceEditorScope) = this@CatalogWorkspaceBinding.matches(scope)
            override fun activeLink() = this@CatalogWorkspaceBinding.activeLink()
            override fun confirmedExternal(xml: String) = this@CatalogWorkspaceBinding.confirmedExternal(xml)
            override fun externalSaved(scope: WorkspaceEditorScope, reference: DocumentReference, text: String,
                                       previousReference: DocumentReference?, onCommitted: () -> Unit) {
                this@CatalogWorkspaceBinding.externalSaved(scope, reference, text, previousReference)
                onCommitted()
            }
        }) }
    val documents: DocumentActions? get() = documentBinding
    val state: CatalogWorkspaceState get() = CatalogWorkspaceState(catalog.copy(activeProjectId = projectId), session.state, mount,
        draftXml ?: mountedXml, projectId, notices + catalog.notices,
        readOnly = draftReadOnly || editorReadOnly || (projectId != null && session.state.readOnly),
        editorImportReport = if (projectId == null) draftImportReport else projectId?.let { importReports[it] },
        editorClosed = editorClosed,
        recentFiles = recentFiles.filter { file -> file !is WorkspaceRecentFile.Copy ||
            catalog.projects.any { it.id == file.projectId && !it.deleted } })

    fun start(legacyXml: String? = null, legacyReference: DocumentReference? = null) {
        if (initialized) return
        initialized = true
        val before = repository.list(includeDeleted = true)
        val loaded = repository.load(legacyXml)
        notices = loaded.notices
        loadRecentFiles()
        refresh()
        val restoredWindow = runCatching { links.windowProject(windowId) }.onFailure(::report).getOrNull()
        val target = restoredWindow?.takeIf { id -> catalog.projects.any { it.id == id && !it.deleted } }
            ?: catalog.activeProjectId ?: catalog.projects.firstOrNull { !it.deleted }?.id
        if (target != null && session.open(target)) {
            val document = session.state.document!!
            if (before.projects.isEmpty() && !before.readOnly && legacyXml == document.xml && legacyReference != null) {
                associate(document, legacyReference, document.xml)
            }
            mountDocument(document)
        } else if (target == null && legacyXml == null && loaded.notices.isEmpty() && !catalog.readOnly && session.create(projectName(defaultXml()), defaultXml())) {
            mountDocument(session.state.document!!)
        } else {
            draftXml = legacyXml ?: defaultXml()
            draftReadOnly = true
            mountedXml = draftXml
            documentBinding?.activate(null)
            publish()
        }
    }

    fun editorAttached(editorMount: Long, importReport: EditorImportReport? = null) {
        if (editorMount == mount) {
            attachedMount = editorMount
            checkEditorWritable()
            if (state.readOnly) documentBinding?.activate(null)
            val id = projectId
            if (id != null && importReport != null && !state.readOnly) {
                val consumed = importReport.copy(showOnOpen = false)
                if (importReports[id] != consumed) { importReports[id] = consumed; publish() }
            }
            documentBinding?.editorAttached()
        }
    }

    fun documentChanged(editorMount: Long, xml: String) {
        if (editorMount != mount || !checkEditorWritable() || state.readOnly) return
        if (projectId == null) { draftXml = xml; mountedXml = xml; publish(); return }
        session.edit(xml, exportView() ?: session.state.viewState)
        flushSession()
        refresh(); publish()
    }

    fun viewChanged(editorMount: Long, view: WorkspaceViewState) {
        if (editorMount != mount || projectId == null || !checkEditorWritable() || state.readOnly) return
        session.edit(session.state.xml ?: return, view)
    }

    fun projectReplaced(editorMount: Long, event: EditorProjectReplacement, externalReference: DocumentReference? = null) {
        if (editorMount != mount) return
        val previousReadOnly = state.readOnly
        editorReadOnly = editorWritable() == false
        draftReadOnly = false
        currentIdentity = event.identity
        val oldId = identityProjects[event.previousIdentity] ?: projectId
        if (!previousReadOnly && oldId != null && session.state.document?.info?.id == oldId) {
            session.edit(event.previousXml, session.state.viewState)
            flushSession()
        }
        val pending = externalReference ?: documentBinding?.pendingReference
        val known = identityProjects[event.identity]
        val changed = if (known != null) session.open(known) else session.create(projectName(event.xml), event.xml)
        if (changed) {
            if (!editorReadOnly) session.edit(event.xml, exportView() ?: WorkspaceViewState())
            val result = session.flush()
            if (result is WorkspaceMutationResult.Success) {
                projectId = result.document.info.id
                identityProjects[event.identity] = result.document.info.id
                event.importReport?.let { importReports[result.document.info.id] = it.copy(showOnOpen = false) }
                draftImportReport = null
                draftXml = null
                draftReference = null
                mountedXml = event.xml
                rememberCheckpoint(result.document)
                if (pending != null) {
                    associate(result.document, pending, event.xml)
                    rememberRecent(result.document.info.id, pending)
                }
                validateLink(result.document)
                documentBinding?.activate(result.document)
                rememberWindow(result.document.info.id)
                refresh()
                publish()
                return
            }
        }
        // The editor already accepted B. Never feed B's later autosave into A's session.
        projectId = null
        draftXml = event.xml
        draftReference = pending
        draftImportReport = event.importReport?.copy(showOnOpen = false) ?: known?.let { importReports[it] }
        mountedXml = event.xml
        documentBinding?.activate(null)
        refresh()
        publish()
    }

    fun flush(): Boolean {
        if (!checkEditorWritable() || state.readOnly) return true
        if (projectId == null) {
            val draft = draftXml ?: return true
            if (!session.create(projectName(draft), draft)) { publish(); return false }
            val document = session.state.document!!
            projectId = document.info.id
            rememberCheckpoint(document)
            identityProjects[currentIdentity] = document.info.id
            draftImportReport?.let { importReports[document.info.id] = it }
            draftImportReport = null
            draftReference?.let {
                associate(document, it, draft)
                rememberRecent(document.info.id, it)
            }
            draftReference = null; draftXml = null; mountedXml = document.xml
            documentBinding?.activate(document)
            rememberWindow(document.info.id)
            refresh(); publish()
            return true
        }
        captureEditor()
        val result = flushSession()
        refresh(); publish()
        return result == null || result is WorkspaceMutationResult.Success
    }

    internal fun commitEditorChange(xml: String): Boolean {
        if (projectId == null || state.readOnly || !flush()) return false
        val revision = session.state.document?.info?.revision
        val result = session.commitEdit(xml)
        if (result is WorkspaceMutationResult.Success) {
            mountedXml = result.document.xml
            confirm(result, revision)
        }
        notices = when (result) {
            is WorkspaceMutationResult.Success -> emptyList()
            is WorkspaceMutationResult.Conflict -> listOf(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> listOf(result.notice)
            WorkspaceMutationResult.Missing -> listOf(message(Messages.WorkspaceErrorUnavailable))
        }
        refresh(); publish()
        return result is WorkspaceMutationResult.Success
    }

    fun foregroundChanged(foreground: Boolean) {
        if (!foreground) {
            pauseSimulation()
            captureEditor()
            flush()
            session.foregroundChanged(false)
            if (!session.state.dirty) session.state.document?.takeIf { it.info.id == projectId }
                ?.let { confirm(WorkspaceMutationResult.Success(it)) }
            refresh(); publish()
            return
        }
        val oldXml = session.state.xml
        val oldReadOnly = session.state.readOnly
        loadRecentFiles()
        session.foregroundChanged(true)
        if (projectId != null && (session.state.xml != oldXml || session.state.readOnly != oldReadOnly) && !session.state.dirty) session.state.document?.let(::mountDocument)
        refresh(); publish()
    }

    fun openProject(id: WorkspaceProjectId): Boolean {
        if (projectId == id) return true
        if (!flush()) return false
        if (!session.open(id)) { publish(); return false }
        recentFiles.filterIsInstance<WorkspaceRecentFile.Copy>().firstOrNull { it.projectId == id }?.let { rememberRecent(it) }
        mountDocument(session.state.document!!)
        return true
    }

    /** Without a template, the new main circuit takes [circuitAttributes], as Evolution's Circuit and CircuitAttributes do. */
    fun createProject(
        name: String,
        templateXml: String? = null,
        circuitName: String = DEFAULT_NEW_CIRCUIT_NAME,
        circuitAttributes: Map<String, String> = emptyMap(),
    ): Boolean = openPreparedCopy(name, templateXml ?: defaultXml(CircuitDocument(
        name = circuitName.trim().ifEmpty { DEFAULT_NEW_CIRCUIT_NAME }, attributes = circuitAttributes)))

    fun openPreparedCopy(name: String, xml: String, attachments: Map<String, WorkspaceAttachment> = emptyMap()): Boolean =
        openNewSession(name, xml, attachments = attachments)

    internal fun openNewSession(
        name: String,
        xml: String,
        report: EditorImportReport? = null,
        reference: DocumentReference? = null,
        attachments: Map<String, WorkspaceAttachment> = emptyMap(),
    ): Boolean {
        if (!flush()) return false
        if (!session.create(name, xml, attachments)) { publish(); return false }
        val document = checkNotNull(session.state.document)
        report?.let { importReports[document.info.id] = it }
        reference?.let {
            associate(document, it, xml)
            rememberRecent(document.info.id, it)
        }
        mountDocument(document)
        return true
    }

    internal fun prepareSessionSave(id: WorkspaceProjectId): WorkspaceSessionSave? {
        if (id !in sessionCheckpoints || (id == projectId && !flush())) return null
        val document = openForSessionClose(id) ?: return null
        val link = try {
            links.read(id)
        } catch (error: Exception) {
            report(error)
            return null
        }
        if (link != null && (document.readOnly || link.workspaceStamp != workspaceContentStamp(document.xml))) {
            sessionCloseFailure(message("workspace_external_link_mismatch"))
            return null
        }
        val scope = if (id == projectId) scope() else WorkspaceEditorScope(mount, id, 0, document.info.revision)
        return WorkspaceSessionSave(scope, link?.reference, document.xml)
    }

    internal fun closeEditorSession(
        expected: WorkspaceProjectId, next: WorkspaceProjectId?, save: Boolean, originalXml: String? = null,
    ): Boolean {
        if (projectId != expected || next == expected) return false
        if (save && !flush()) return false
        if (!matchesSavedOriginal(expected, originalXml)) return false
        if (!save && !discardSessionEdits(expected)) return false
        if (next != null) {
            if (!session.open(next, discardPending = !save)) { publish(); return false }
            sessionCheckpoints.remove(expected)
            mountDocument(checkNotNull(session.state.document))
            return true
        }
        if (!session.close(discardPending = !save)) { publish(); return false }
        sessionCheckpoints.remove(expected)
        projectId = null
        draftXml = null
        mountedXml = null
        draftReference = null
        draftImportReport = null
        draftReadOnly = false
        editorReadOnly = false
        editorClosed = true
        identityProjects.clear()
        currentIdentity = 0
        mount++
        attachedMount = null
        documentBinding?.editorDetached()
        documentBinding?.activate(null)
        rememberWindow(null)
        refresh(); publish()
        return true
    }

    internal fun closeInactiveSession(id: WorkspaceProjectId, save: Boolean, originalXml: String? = null): Boolean {
        if (id == projectId || id !in sessionCheckpoints || !matchesSavedOriginal(id, originalXml)) return false
        if (!save && !discardSessionEdits(id)) return false
        sessionCheckpoints.remove(id)
        refresh(); publish()
        return true
    }

    private fun openForSessionClose(id: WorkspaceProjectId): WorkspaceDocument? = when (val opened = repository.open(id)) {
        is WorkspaceOpenResult.Success -> opened.document
        is WorkspaceOpenResult.Failure -> { sessionCloseFailure(opened.notice); null }
        WorkspaceOpenResult.Missing -> { sessionCloseFailure(message(Messages.WorkspaceErrorUnavailable)); null }
    }

    private fun matchesSavedOriginal(id: WorkspaceProjectId, originalXml: String?): Boolean {
        if (originalXml == null) return true
        val current = openForSessionClose(id) ?: return false
        return current.xml == originalXml || sessionCloseFailure(message(Messages.WorkspaceConflictSaved))
    }

    private fun discardSessionEdits(id: WorkspaceProjectId): Boolean {
        val checkpoint = sessionCheckpoints[id] ?: return true
        val current = openForSessionClose(id) ?: return false
        if (current.xml == checkpoint.xml) return true
        if (current.info.revision != checkpoint.revision || (id == projectId && state.session.conflict != null)) {
            return sessionCloseFailure(message(Messages.WorkspaceConflictSaved))
        }
        return when (val result = repository.save(id, checkpoint.revision, checkpoint.xml, checkpoint.view)) {
            is WorkspaceMutationResult.Success -> { confirm(result, checkpoint.revision); true }
            is WorkspaceMutationResult.Conflict -> sessionCloseFailure(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> sessionCloseFailure(result.notice)
            WorkspaceMutationResult.Missing -> sessionCloseFailure(message(Messages.WorkspaceErrorUnavailable))
        }
    }

    internal fun sessionCloseFailure(notice: LocalizedText): Boolean {
        notices = listOf(notice)
        refresh(); publish()
        return false
    }

    internal fun readAttachment(id: WorkspaceProjectId, key: String): WorkspaceAttachmentReadResult {
        val result = repository.readAttachment(id, key)
        if (result != WorkspaceAttachmentReadResult.Missing) return result
        return when (val opened = repository.open(id)) {
            is WorkspaceOpenResult.Success -> if (key !in opened.document.attachments) WorkspaceAttachmentReadResult.Missing
                else WorkspaceAttachmentReadResult.Failure(message("workspace_source_not_current"))
            is WorkspaceOpenResult.Failure -> WorkspaceAttachmentReadResult.Failure(opened.notice)
            WorkspaceOpenResult.Missing -> WorkspaceAttachmentReadResult.Failure(message("workspace_source_not_current"))
        }
    }

    fun duplicateProject(id: WorkspaceProjectId, expectedRevision: Long) = mutateProject(id, expectedRevision) { revision ->
        repository.duplicate(id, revision)
    }

    fun renameProject(id: WorkspaceProjectId, expectedRevision: Long, name: String) = mutateProject(id, expectedRevision) { revision ->
        repository.rename(id, revision, name)
    }

    fun deleteProject(id: WorkspaceProjectId, expectedRevision: Long) = mutateProject(id, expectedRevision) { revision ->
        repository.delete(id, revision)
    }

    fun restoreProject(id: WorkspaceProjectId, expectedRevision: Long) = mutateProject(id, expectedRevision) { revision ->
        repository.restore(id, revision)
    }

    fun reloadStored(): Boolean {
        if (!session.reloadStored()) { publish(); return false }
        val document = checkNotNull(session.state.document)
        sessionCheckpoints.remove(document.info.id)
        mountDocument(document)
        return true
    }

    fun saveLocalAsCopy(name: String): Boolean {
        if (!checkEditorWritable() || state.readOnly) {
            notices = listOf(message("workspace_document_unavailable")); publish(); return false
        }
        captureEditor()
        val saved = if (projectId == null) {
            // A replacement can arrive after A's save fails. Preserve A's pending buffer before
            // installing B's explicit copy; neither buffer may silently replace the other.
            if (session.state.dirty) {
                val previousReport = session.state.document?.info?.id?.let { importReports[it] }
                if (!session.saveLocalAsCopy(session.state.document?.info?.name ?: name)) { publish(); return false }
                previousReport?.let { importReports[session.state.document!!.info.id] = it }
            }
            session.saveLocalAsCopy(name, draftXml ?: return false, exportView() ?: WorkspaceViewState())
        } else session.saveLocalAsCopy(name)
        if (!saved) { publish(); return false }
        (if (projectId == null) draftImportReport else projectId?.let { importReports[it] })?.let {
            importReports[session.state.document!!.info.id] = it
        }
        val document = session.state.document!!
        rememberRecent(WorkspaceRecentFile.Copy(document.info.id, document.info.name))
        mountDocument(document)
        return true
    }

    private fun activeIdentity(): Long = if (attachedMount == mount) editorIdentity() ?: currentIdentity else currentIdentity

    internal fun scope(): WorkspaceEditorScope = WorkspaceEditorScope(mount, projectId,
        activeIdentity(), session.state.document?.info?.revision ?: 0)

    internal fun matches(scope: WorkspaceEditorScope): Boolean = scope.mount == mount &&
        scope.projectId == projectId && scope.identity == activeIdentity()

    internal fun activeLink(): WorkspaceExternalDocument? {
        val id = projectId ?: return null
        return runCatching { links.read(id) }.onFailure(::report).getOrNull()
    }

    internal fun externalSaved(scope: WorkspaceEditorScope, reference: DocumentReference, text: String,
                               previousReference: DocumentReference?) {
        val id = scope.projectId ?: return
        val current = (repository.open(id) as? WorkspaceOpenResult.Success)?.document ?: return
        if (current.readOnly) return
        sessionCheckpoints[id]?.let { checkpoint ->
            sessionCheckpoints[id] = checkpoint.copy(xml = text, view = current.viewState)
        }
        val stamp = if (current.info.revision > scope.revision) workspaceContentStamp(current.xml) else workspaceContentStamp(text)
        if (reference.mode == DocumentOpenMode.EDIT_IN_PLACE && reference.writable) {
            runCatching {
                links.write(WorkspaceExternalDocument(id, reference, stamp))
                validatedLinks += id
                val previous = previousReference?.let { WorkspaceRecentFile.Original(it) }
                    ?: WorkspaceRecentFile.Copy(id, current.info.name)
                rememberRecent(WorkspaceRecentFile.Original(reference), replaces = previous)
            }.onFailure(::report)
        } else if (reference.mode == DocumentOpenMode.EDIT_IN_PLACE) {
            // Providers without atomic replacement still hold the saved file; list it like
            // Evolution does after Save As, so it can be reopened (as a copy that asks for Save As).
            rememberRecent(WorkspaceRecentFile.Original(reference))
        }
        if (matches(scope)) documentBinding?.activate(current, requiredStamp = stamp)
    }

    internal fun confirmedExternal(xml: String) = externalWorkspaceConfirmed(xml)
    internal fun report(error: Throwable) {
        notices = listOf(error.localizedDescription(message("workspace_external_link_unreadable")))
        publish()
    }

    private fun mutateProject(id: WorkspaceProjectId, revision: Long, action: (Long) -> WorkspaceMutationResult) {
        val active = projectId == id
        if (active && !flush()) return
        val expected = if (active) session.state.document?.info?.revision ?: revision else revision
        when (val result = action(expected)) {
            is WorkspaceMutationResult.Success -> {
                if (result.document.info.id != id) importReports[id]?.let { importReports[result.document.info.id] = it }
                else if (result.document.info.deleted) sessionCheckpoints.remove(id)
                else recordCheckpointWrite(result.document, expected)
                if (active && result.document.info.deleted) {
                    session.close(); projectId = null; draftXml = defaultXml(); mountedXml = draftXml
                    draftReadOnly = false; editorReadOnly = false; draftImportReport = null
                    identityProjects.clear(); currentIdentity = 0; mount++; attachedMount = null
                    documentBinding?.editorDetached()
                    documentBinding?.activate(null); rememberWindow(null)
                } else if (active) session.open(id)
            }
            is WorkspaceMutationResult.Conflict -> notices = listOf(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> notices = listOf(result.notice)
            WorkspaceMutationResult.Missing -> notices = listOf(message(Messages.WorkspaceErrorUnavailable))
        }
        refresh(); publish()
    }

    private fun checkEditorWritable(): Boolean {
        if (attachedMount != mount || editorWritable() != false) return true
        if (!editorReadOnly) {
            editorReadOnly = true
            notices = listOf(message("workspace_document_unavailable"))
            pauseSimulation()
            documentBinding?.activate(null)
            publish()
        }
        return false
    }

    private fun captureEditor() {
        if (projectId == null || attachedMount != mount || !checkEditorWritable() || state.readOnly) return
        session.edit(exportXml() ?: session.state.xml ?: return, exportView() ?: session.state.viewState)
    }

    private fun flushSession(): WorkspaceMutationResult? {
        val revision = session.state.document?.info?.revision
        return session.flush().also { confirm(it, revision) }
    }

    private fun recordCheckpointWrite(document: WorkspaceDocument, expectedRevision: Long) {
        val id = document.info.id
        val checkpoint = sessionCheckpoints[id] ?: return
        if (checkpoint.revision == expectedRevision) {
            sessionCheckpoints[id] = checkpoint.copy(revision = document.info.revision)
        }
    }

    private fun confirm(result: WorkspaceMutationResult?, expectedRevision: Long? = null) {
        if (result !is WorkspaceMutationResult.Success) return
        val document = result.document
        expectedRevision?.let { recordCheckpointWrite(document, it) }
        if (document.readOnly || state.readOnly || document.info.id !in validatedLinks) return
        runCatching {
            links.read(document.info.id)?.let { link ->
                links.write(link.copy(workspaceStamp = workspaceContentStamp(document.xml)))
                if (projectId == document.info.id) documentBinding?.activate(document)
            }
        }.onFailure(::report)
    }

    private fun rememberCheckpoint(document: WorkspaceDocument) {
        sessionCheckpoints.getOrPut(document.info.id) {
            SessionCheckpoint(document.xml, document.viewState, document.info.revision)
        }
    }

    private fun mountDocument(document: WorkspaceDocument) {
        editorClosed = false
        rememberCheckpoint(document)
        projectId = document.info.id; draftXml = null; draftReference = null; draftImportReport = null
        draftReadOnly = false; editorReadOnly = false
        mountedXml = document.xml; currentIdentity = 0; mount++; attachedMount = null
        identityProjects.clear(); identityProjects[0] = document.info.id
        documentBinding?.editorDetached()
        validateLink(document)
        documentBinding?.activate(document)
        rememberWindow(document.info.id)
        refresh(); publish()
    }

    private fun associate(document: WorkspaceDocument, reference: DocumentReference, xml: String) {
        if (reference.mode != DocumentOpenMode.EDIT_IN_PLACE || !reference.writable) return
        runCatching {
            links.write(WorkspaceExternalDocument(document.info.id, reference, workspaceContentStamp(xml)))
            validatedLinks += document.info.id
        }.onFailure(::report)
    }
    private fun validateLink(document: WorkspaceDocument) {
        val link = runCatching { links.read(document.info.id) }.onFailure(::report).getOrNull()
        if (link?.workspaceStamp == workspaceContentStamp(document.xml)) validatedLinks += document.info.id
        else {
            validatedLinks -= document.info.id
            if (link != null) notices = listOf(message("workspace_external_link_mismatch"))
        }
    }

    private fun loadRecentFiles() {
        runCatching { links.recentFiles() }.onSuccess { recentFiles = it }.onFailure(::report)
    }

    private fun rememberRecent(projectId: WorkspaceProjectId, reference: DocumentReference) {
        rememberRecent(when (reference.mode) {
            DocumentOpenMode.EDIT_IN_PLACE -> WorkspaceRecentFile.Original(reference)
            DocumentOpenMode.IMPORT_COPY -> WorkspaceRecentFile.Copy(projectId, reference.name)
        })
    }

    private fun rememberRecent(file: WorkspaceRecentFile, replaces: WorkspaceRecentFile? = null) {
        runCatching { links.recordRecent(file, replaces) }.onSuccess { recentFiles = it }.onFailure(::report)
    }
    private fun rememberWindow(id: WorkspaceProjectId?) { runCatching { links.selectWindowProject(windowId, id) }.onFailure(::report) }
    private fun refresh() { catalog = repository.list(includeDeleted = true) }
    private fun publish() { onStateChanged(state) }
    private fun projectName(xml: String): String = (CircCodec.readProject(xml) as? CircProjectImportResult.Success)
        ?.project?.mainCircuitName ?: "main"
    private fun defaultXml(circuit: CircuitDocument = DemoCircuits.andGate()) = CircCodec.writeProject(
        ProjectBuiltinLibraries.withDefaults(CircuitProject(listOf(circuit)), ComponentCatalog.registry),
    )
}
