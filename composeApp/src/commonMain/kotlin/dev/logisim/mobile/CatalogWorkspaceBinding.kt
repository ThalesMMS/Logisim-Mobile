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
import dev.logisim.mobile.core.uniqueProjectName
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import kotlin.time.Clock

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
    /** Open sessions whose project XML differs from the version they were opened with (view-only changes excluded). */
    val changedSessions: Set<WorkspaceProjectId> = emptySet(),
    /** When each open session was opened: the version a close-and-revert restores. */
    val sessionOpenedAt: Map<WorkspaceProjectId, Long> = emptyMap(),
    /** The name of the external file each open session is linked to, for "Save to {file}" on close. */
    val sessionFiles: Map<WorkspaceProjectId, String> = emptyMap(),
    /** The last write of the open project failed; it stays set until a write succeeds. */
    val saveFailed: Boolean = false,
    /**
     * Open sessions linked to a file that this window has not seen match the project: mounted from the catalog
     * (after a relaunch, a reload or a reopen) rather than opened from or saved to the file. Closing one offers
     * "Save to {file}" even when nothing changed since the tab opened.
     */
    val unconfirmedFiles: Set<WorkspaceProjectId> = emptySet(),
    /** The closed project whose close-and-revert can still be undone. */
    val revertUndo: WorkspaceProjectId? = null,
    /**
     * Open sessions linked to a file that does not hold their current content, as far as any window recorded: edited
     * since the file was last read or written, or never seen to match it. Evolution marks such a project dirty.
     */
    val externalDirty: Set<WorkspaceProjectId> = emptySet(),
    /**
     * An already open linked file that was opened again and brought its project forward: the editor says "File NAME
     * opened", as a fresh open does, so assistive technology hears where the file went. Another mount clears it; the
     * host says each [WorkspaceReopenedFile.serial] once.
     */
    val reopenedFile: WorkspaceReopenedFile? = null,
)

/** The [serial]th reopen of a linked file in this window, which brought [projectId] forward under [name]. */
data class WorkspaceReopenedFile(val serial: Long, val projectId: WorkspaceProjectId, val name: LocalizedText)

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
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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
    private var reopenedFile: WorkspaceReopenedFile? = null
    private var reopenSerial = 0L
    private val validatedLinks = mutableSetOf<WorkspaceProjectId>()
    private var draftXml: String? = null
    private var draftReference: DocumentReference? = null
    private var draftImportReport: EditorImportReport? = null
    private var draftReadOnly = false
    private var editorReadOnly = false
    private var initialized = false
    private var editorClosed = false
    // Autosave preserves crash recovery. This is the explicit save point for each open tab.
    // [baseline] is the editor's own export of [xml] when the tab first attached: a file written by another tool
    // reads back in the editor's canonical form, and that first autosave is not a change the user made.
    private data class SessionCheckpoint(val xml: String, val view: WorkspaceViewState, val revision: Long,
                                         val openedAtMillis: Long, val baseline: String? = null) {
        fun unchanged(current: String) = current == xml || current == baseline
    }
    private val sessionCheckpoints = mutableMapOf<WorkspaceProjectId, SessionCheckpoint>()
    private var awaitingBaseline: WorkspaceProjectId? = null
    // The last XML seen for each open session, so a close can tell untouched tabs without reading storage.
    private val sessionXml = mutableMapOf<WorkspaceProjectId, String>()
    private val sessionFiles = mutableMapOf<WorkspaceProjectId, String>()
    // Sessions whose checkpoint this window has seen in their linked file: opened from it, or saved to it.
    private val confirmedFiles = mutableSetOf<WorkspaceProjectId>()
    // Each tab's last comparison, so reading the state compares whole projects again only after either side changed.
    private class ChangeCheck(val checkpoint: SessionCheckpoint, val xml: String, val changed: Boolean)
    private val changeChecks = mutableMapOf<WorkspaceProjectId, ChangeCheck>()
    // What each linked session's file was last known to hold (read from storage on mount and on resume), and the
    // last stamp computed for its current XML, so building the state hashes a project again only after it changed.
    private val externalSyncs = mutableMapOf<WorkspaceProjectId, String?>()
    private class StampCheck(val xml: String, val stamp: String)
    private val stampChecks = mutableMapOf<WorkspaceProjectId, StampCheck>()
    // The last trashed tab's session, so undoing that trash brings the tab back with the version it opened with.
    private data class TrashedSession(val checkpoint: SessionCheckpoint, val xml: String?, val file: String?, val confirmed: Boolean)
    private val trashedSessions = mutableMapOf<WorkspaceProjectId, TrashedSession>()
    // A close-and-revert keeps what it replaced until the next revert or reopen, so the user can undo it.
    private data class RevertedSession(val id: WorkspaceProjectId, val revision: Long, val xml: String, val view: WorkspaceViewState)
    private var lastRevert: RevertedSession? = null
    private var saveFailed = false
    // Counts every change to [notices], so a mount keeps the notices raised while it was prepared (see mountDocument).
    private var noticeChanges = 0L
    private var notices = emptyList<LocalizedText>()
        set(value) { field = value; noticeChanges++ }
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
        draftXml ?: mountedXml, projectId, (notices + catalog.notices).distinct(),
        readOnly = readOnly,
        editorImportReport = if (projectId == null) draftImportReport else projectId?.let { importReports[it] },
        editorClosed = editorClosed,
        // A copy is listed under its project's current name, so a rename shows here too.
        recentFiles = recentFiles.mapNotNull { file ->
            if (file !is WorkspaceRecentFile.Copy) file
            else catalog.projects.firstOrNull { it.id == file.projectId && !it.deleted }?.let { file.copy(name = it.name) }
        },
        changedSessions = changedSessions(),
        sessionOpenedAt = sessionCheckpoints.mapValues { it.value.openedAtMillis },
        sessionFiles = sessionFiles.filterKeys { it in sessionCheckpoints },
        saveFailed = saveFailed && !editorClosed,
        unconfirmedFiles = sessionFiles.keys.filterTo(mutableSetOf()) { it in sessionCheckpoints && it !in confirmedFiles },
        revertUndo = lastRevert?.id?.takeIf { it != projectId },
        externalDirty = externalDirty(),
        reopenedFile = reopenedFile?.takeIf { it.projectId == projectId })

    private fun sessionCurrentXml(id: WorkspaceProjectId): String? =
        if (id == projectId && session.state.document?.info?.id == id) session.state.xml else sessionXml[id]

    private fun externalDirty(): Set<WorkspaceProjectId> = sessionFiles.keys.filterTo(mutableSetOf()) { id ->
        if (id !in sessionCheckpoints) return@filterTo false
        val current = sessionCurrentXml(id) ?: return@filterTo true
        val stamp = stampChecks[id]?.takeIf { it.xml === current }?.stamp
            ?: workspaceContentStamp(current).also { stampChecks[id] = StampCheck(current, it) }
        externalSyncs[id] != stamp
    }

    // The binding checks this on every edit; building the whole state for it would compare every open tab's XML.
    private val readOnly: Boolean get() = draftReadOnly || editorReadOnly || (projectId != null && session.state.readOnly)

    private fun changedSessions(): Set<WorkspaceProjectId> = sessionCheckpoints.mapNotNullTo(mutableSetOf()) { (id, checkpoint) ->
        val current = sessionCurrentXml(id) ?: return@mapNotNullTo null
        val check = changeChecks[id]?.takeIf { it.checkpoint === checkpoint && it.xml === current }
            ?: ChangeCheck(checkpoint, current, !checkpoint.unchanged(current)).also { changeChecks[id] = it }
        id.takeIf { check.changed }
    }

    /**
     * [defaultName] names the project a fresh install starts with (the localized AND example title), and
     * [recoveredName] a project migrated from the single-document storage; null keeps the earlier defaults.
     */
    fun start(legacyXml: String? = null, legacyReference: DocumentReference? = null,
              defaultName: String? = null, recoveredName: String? = null) {
        if (initialized) return
        initialized = true
        val before = repository.list(includeDeleted = true)
        val loaded = if (recoveredName == null) repository.load(legacyXml) else repository.load(legacyXml, recoveredName)
        notices = loaded.notices
        val mark = noticeChanges
        loadRecentFiles()
        refresh()
        val restoredWindow = runCatching { links.windowProject(windowId) }.onFailure(::report).getOrNull()
        val target = restoredWindow?.takeIf { id -> catalog.projects.any { it.id == id && !it.deleted } }
            ?: catalog.activeProjectId ?: catalog.projects.firstOrNull { !it.deleted }?.id
        if (target != null && session.open(target)) {
            val document = session.state.document!!
            if (before.projects.isEmpty() && !before.readOnly && legacyXml == document.xml && legacyReference != null) {
                // The legacy checkpoint may hold edits that never reached the file: link it, but vouch for nothing.
                associate(document, legacyReference, document.xml, fromFile = false)
            }
            mountDocument(document, mark)
            keepStartupNotices(loaded.notices)
        } else if (target == null && legacyXml == null && loaded.notices.isEmpty() && !catalog.readOnly &&
            session.create(defaultName ?: projectName(defaultXml()), defaultXml())) {
            mountDocument(session.state.document!!, mark)
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
            projectId?.takeIf { it == awaitingBaseline }?.let { id ->
                awaitingBaseline = null
                val baseline = exportXml()
                sessionCheckpoints[id]?.let { checkpoint ->
                    if (baseline == null) return@let
                    sessionCheckpoints[id] = checkpoint.copy(baseline = baseline)
                    // The file was just read as the checkpoint; the editor's canonical form of it is the same content.
                    if (baseline != checkpoint.xml && externalSyncs[id] == workspaceContentStamp(checkpoint.xml)) {
                        runCatching { links.read(id) }.getOrNull()?.let { link ->
                            val synced = workspaceContentStamp(baseline)
                            runCatching { links.writeSync(id, WorkspaceExternalSync(link.reference.id, synced)) }
                                .onSuccess { externalSyncs[id] = synced }.onFailure(::report)
                        }
                    }
                }
            }
            checkEditorWritable()
            if (readOnly) documentBinding?.activate(null)
            val id = projectId
            if (id != null && importReport != null && !readOnly) {
                val consumed = importReport.consumed()
                if (importReports[id] != consumed) { importReports[id] = consumed; publish() }
            }
            documentBinding?.editorAttached()
        }
    }

    fun documentChanged(editorMount: Long, xml: String) {
        if (editorMount != mount || !checkEditorWritable() || readOnly) return
        if (projectId == null) { draftXml = xml; mountedXml = xml; publish(); return }
        session.edit(xml, exportView() ?: session.state.viewState)
        flushSession()
        refresh(); publish()
    }

    fun viewChanged(editorMount: Long, view: WorkspaceViewState) {
        if (editorMount != mount || projectId == null || !checkEditorWritable() || readOnly) return
        session.edit(session.state.xml ?: return, view)
    }

    fun projectReplaced(editorMount: Long, event: EditorProjectReplacement, externalReference: DocumentReference? = null) {
        if (editorMount != mount) return
        val previousReadOnly = readOnly
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
                saveFailed = false
                projectId = result.document.info.id
                identityProjects[event.identity] = result.document.info.id
                event.importReport?.let { importReports[result.document.info.id] = it.consumed() }
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
        saveFailed = true
        projectId = null
        draftXml = event.xml
        draftReference = pending
        draftImportReport = event.importReport?.consumed() ?: known?.let { importReports[it] }
        mountedXml = event.xml
        documentBinding?.activate(null)
        refresh()
        publish()
    }

    fun flush(): Boolean {
        if (!checkEditorWritable() || readOnly) return true
        if (projectId == null) {
            val draft = draftXml ?: return true
            if (!session.create(projectName(draft), draft)) { saveFailed = true; publish(); return false }
            saveFailed = false
            val document = session.state.document!!
            projectId = document.info.id
            rememberCheckpoint(document)
            identityProjects[currentIdentity] = document.info.id
            draftImportReport?.let { importReports[document.info.id] = it }
            draftImportReport = null
            draftReference?.let {
                // The draft may have been edited since the replacement failed, so it is not what the file holds.
                associate(document, it, draft, fromFile = false)
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
        if (projectId == null || readOnly || !flush()) return false
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
        val mark = noticeChanges
        loadRecentFiles()
        sessionFiles.keys.forEach { loadSync(it) }
        session.foregroundChanged(true)
        if (projectId != null && (session.state.xml != oldXml || session.state.readOnly != oldReadOnly) && !session.state.dirty) {
            session.state.document?.let { mountDocument(it, mark) }
        }
        refresh(); publish()
    }

    fun openProject(id: WorkspaceProjectId): Boolean {
        if (projectId == id) return true
        if (!flush()) return false
        val mark = noticeChanges
        if (!session.open(id)) { publish(); return false }
        val document = session.state.document!!
        recentFiles.filterIsInstance<WorkspaceRecentFile.Copy>().firstOrNull { it.projectId == id }
            ?.let { rememberRecent(it.copy(name = document.info.name)) }
        mountDocument(document, mark)
        return true
    }

    /**
     * Without a template, the new main circuit takes [circuitAttributes], as Evolution's Circuit and CircuitAttributes do.
     * [uniqueName] numbers [name] ("Untitled 2") when another project already uses it.
     */
    fun createProject(
        name: String,
        templateXml: String? = null,
        circuitName: String = DEFAULT_NEW_CIRCUIT_NAME,
        circuitAttributes: Map<String, String> = emptyMap(),
        uniqueName: Boolean = false,
    ): Boolean = openNewSession(name, templateXml ?: defaultXml(CircuitDocument(
        name = circuitName.trim().ifEmpty { DEFAULT_NEW_CIRCUIT_NAME }, attributes = circuitAttributes)), uniqueName = uniqueName)

    fun openPreparedCopy(name: String, xml: String, attachments: Map<String, WorkspaceAttachment> = emptyMap()): Boolean =
        openNewSession(name, xml, attachments = attachments)

    /**
     * A file opened in place that a project is already linked to, unchanged since that project last read or wrote it,
     * reopens that project (with any edits made since) instead of creating another copy, as Evolution brings an
     * already open file's window forward.
     */
    internal fun openNewSession(
        name: String,
        xml: String,
        report: EditorImportReport? = null,
        reference: DocumentReference? = null,
        attachments: Map<String, WorkspaceAttachment> = emptyMap(),
        uniqueName: Boolean = false,
    ): Boolean {
        if (!flush()) return false
        val mark = noticeChanges
        linkedProjectFor(reference, xml)?.let { existing -> return reopenLinked(existing, checkNotNull(reference), report, mark) }
        val projectName = if (uniqueName) uniqueProjectName(name, repository.list().projects.map { it.name }) else name
        if (!session.create(projectName, xml, attachments)) { publish(); return false }
        val document = checkNotNull(session.state.document)
        report?.let { importReports[document.info.id] = it }
        reference?.let {
            associate(document, it, xml)
            rememberRecent(document.info.id, it)
        }
        mountDocument(document, mark)
        return true
    }

    private fun linkedProjectFor(reference: DocumentReference?, xml: String): WorkspaceProjectId? {
        if (reference == null || reference.mode != DocumentOpenMode.EDIT_IN_PLACE || !reference.writable) return null
        val incoming = workspaceContentStamp(xml)
        val candidates = runCatching { links.linkedProjects(reference.id) }.onFailure(::report).getOrNull().orEmpty()
        val live = repository.list().projects.map { it.id }.toSet()
        return candidates.filter { it in live }.firstOrNull { id ->
            val stored = (repository.open(id) as? WorkspaceOpenResult.Success)?.document ?: return@firstOrNull false
            val link = runCatching { links.read(id) }.getOrNull() ?: return@firstOrNull false
            val sync = runCatching { links.readSync(id) }.getOrNull()?.takeIf { it.referenceId == link.reference.id }
            !stored.readOnly && link.workspaceStamp == workspaceContentStamp(stored.xml) &&
                (sync?.workspaceStamp == incoming || workspaceContentStamp(stored.xml) == incoming)
        }
    }

    private fun reopenLinked(id: WorkspaceProjectId, reference: DocumentReference, report: EditorImportReport?, mark: Long): Boolean {
        if (projectId != id) {
            if (!session.open(id)) { publish(); return false }
        }
        val document = checkNotNull(session.state.document)
        runCatching {
            links.read(id)?.let { link -> if (link.reference != reference) links.write(link.copy(reference = reference)) }
        }.onFailure(::report)
        // The report keeps the file just opened as its origin, as the opened status names it.
        val origin = report?.origin ?: LocalizedText.literal(reference.name)
        (importReports[id] ?: report)?.let { importReports[id] = it.copy(origin = origin) }
        rememberRecent(id, reference)
        val reopened = WorkspaceReopenedFile(++reopenSerial, id, origin)
        if (projectId == id) { validateLink(document); documentBinding?.activate(document); reopenedFile = reopened; refresh(); publish() }
        else { mountDocument(document, mark); reopenedFile = reopened; publish() }
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
            forgetSession(expected)
            mountDocument(checkNotNull(session.state.document), noticeChanges)
            return true
        }
        if (!session.close(discardPending = !save)) { publish(); return false }
        forgetSession(expected)
        detachEditor()
        refresh(); publish()
        return true
    }

    /** Leaves the window without an editor, as after closing its last tab; it never mounts a draft to autosave. */
    private fun detachEditor() {
        projectId = null
        draftXml = null
        mountedXml = null
        draftReference = null
        draftImportReport = null
        draftReadOnly = false
        editorReadOnly = false
        editorClosed = true
        saveFailed = false
        identityProjects.clear()
        currentIdentity = 0
        mount++
        attachedMount = null
        documentBinding?.editorDetached()
        documentBinding?.activate(null)
        rememberWindow(null)
    }

    private fun forgetSession(id: WorkspaceProjectId) {
        sessionCheckpoints.remove(id)
        sessionXml.remove(id)
        sessionFiles.remove(id)
        confirmedFiles.remove(id)
        changeChecks.remove(id)
        externalSyncs.remove(id)
        stampChecks.remove(id)
    }

    internal fun closeInactiveSession(id: WorkspaceProjectId, save: Boolean, originalXml: String? = null): Boolean {
        if (id == projectId || id !in sessionCheckpoints || !matchesSavedOriginal(id, originalXml)) return false
        if (!save && !discardSessionEdits(id)) return false
        forgetSession(id)
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
        // The open tab may hold edits that never reached storage (a failed autosave); they are what the undo must put back.
        val unsaved = session.state.takeIf { id == projectId && it.document?.info?.id == id && it.dirty }
            ?.xml?.takeUnless { checkpoint.unchanged(it) }
        val view = if (unsaved != null) session.state.viewState else current.viewState
        if (checkpoint.unchanged(current.xml)) {
            // Storage already holds the version the tab opened with: nothing to write, but the dropped buffer can be undone.
            if (unsaved != null) lastRevert = RevertedSession(id, current.info.revision, unsaved, view)
            return true
        }
        if (current.info.revision != checkpoint.revision || (id == projectId && session.state.conflict != null)) {
            return sessionCloseFailure(message(Messages.WorkspaceConflictSaved))
        }
        val replaced = unsaved ?: current.xml
        return when (val result = repository.save(id, checkpoint.revision, checkpoint.xml, checkpoint.view)) {
            is WorkspaceMutationResult.Success -> {
                confirm(result, checkpoint.revision)
                lastRevert = RevertedSession(id, result.document.info.revision, replaced, view)
                true
            }
            is WorkspaceMutationResult.Conflict -> sessionCloseFailure(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> sessionCloseFailure(result.notice)
            WorkspaceMutationResult.Missing -> sessionCloseFailure(message(Messages.WorkspaceErrorUnavailable))
        }
    }

    /** Puts back what the last close-and-revert of [id] replaced, unless the project was reopened since. */
    fun undoRevert(id: WorkspaceProjectId): Boolean {
        val reverted = lastRevert?.takeIf { it.id == id && id != projectId } ?: return false
        val result = repository.save(id, reverted.revision, reverted.xml, reverted.view)
        // A failed write can be tried again; once another revision or a removal replaced the revert, it cannot.
        if (result !is WorkspaceMutationResult.Failure) lastRevert = null
        return when (result) {
            is WorkspaceMutationResult.Success -> { notices = documentNotices(); refresh(); publish(); true }
            is WorkspaceMutationResult.Conflict -> sessionCloseFailure(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> sessionCloseFailure(result.notice)
            WorkspaceMutationResult.Missing -> sessionCloseFailure(message(Messages.WorkspaceErrorUnavailable))
        }
    }

    /** A file that could not be opened says why, in the Projects sheet; the window is left as it was. */
    internal fun reportNotice(notice: LocalizedText): Boolean = sessionCloseFailure(notice)

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

    /** [name] is the copy's name in the app language ("{name} (copy)"), numbered when already used. */
    fun duplicateProject(id: WorkspaceProjectId, expectedRevision: Long, name: String? = null) = mutateProject(id, expectedRevision) { revision ->
        repository.duplicate(id, revision, name?.let { uniqueProjectName(it, repository.list().projects.map { project -> project.name }) })
    }

    fun renameProject(id: WorkspaceProjectId, expectedRevision: Long, name: String) = mutateProject(id, expectedRevision) { revision ->
        repository.rename(id, revision, name)
    }

    /** Trashing the open project mounts [next] (its neighbouring tab) or leaves the window without an editor. */
    fun deleteProject(id: WorkspaceProjectId, expectedRevision: Long, next: WorkspaceProjectId? = null) =
        mutateProject(id, expectedRevision, next) { revision -> repository.delete(id, revision) }

    /**
     * [reopenSession] is the Undo of a tab's trash: the session comes back with the version it opened with (its tab, in
     * the host). Restoring from the trash list starts afresh, and the kept session is dropped.
     */
    fun restoreProject(id: WorkspaceProjectId, expectedRevision: Long, reopenSession: Boolean = false) =
        mutateProject(id, expectedRevision, restoring = true, reopenSession = reopenSession) { revision ->
            repository.restore(id, revision)
        }

    /** Deletes a trashed project and every saved version of it; this cannot be undone. */
    fun purgeProject(id: WorkspaceProjectId, expectedRevision: Long): Boolean {
        val result = repository.purge(id, expectedRevision)
        if (result is WorkspaceMutationResult.Success) forgetPurged(id)
        notices = when (result) {
            is WorkspaceMutationResult.Success -> documentNotices()
            is WorkspaceMutationResult.Conflict -> listOf(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> listOf(result.notice)
            WorkspaceMutationResult.Missing -> listOf(message(Messages.WorkspaceErrorUnavailable))
        }
        refresh(); publish()
        return result is WorkspaceMutationResult.Success
    }

    /** Deletes every project in the trash; returns how many were deleted (all of them, or the call fails). */
    fun emptyTrash(): Int? {
        refresh()
        val trashed = catalog.projects.filter { it.deleted }
        var deleted = 0
        for (project in trashed) {
            when (val result = repository.purge(project.id, project.revision)) {
                is WorkspaceMutationResult.Success -> { forgetPurged(project.id); deleted++ }
                else -> {
                    notices = listOf((result as? WorkspaceMutationResult.Failure)?.notice ?: message(Messages.WorkspaceConflictSaved))
                    refresh(); publish()
                    return null
                }
            }
        }
        notices = documentNotices()
        refresh(); publish()
        return deleted
    }

    private fun forgetPurged(id: WorkspaceProjectId) {
        trashedSessions.remove(id)
        importReports.remove(id)
        if (lastRevert?.id == id) lastRevert = null
        runCatching { links.forget(id) }.onFailure(::report)
        loadRecentFiles()
    }

    fun removeRecent(file: WorkspaceRecentFile): Boolean {
        val stored = recentFiles.firstOrNull { it.sameEntry(file) } ?: return false
        val removed = runCatching { links.removeRecent(stored) }.onSuccess { recentFiles = it }.onFailure(::report).isSuccess
        publish()
        return removed
    }

    private fun WorkspaceRecentFile.sameEntry(other: WorkspaceRecentFile) = when (this) {
        is WorkspaceRecentFile.Original -> other is WorkspaceRecentFile.Original && other.reference.id == reference.id
        is WorkspaceRecentFile.Copy -> other is WorkspaceRecentFile.Copy && other.projectId == projectId
    }

    fun reloadStored(): Boolean {
        if (!session.reloadStored()) { publish(); return false }
        val document = checkNotNull(session.state.document)
        forgetSession(document.info.id)
        mountDocument(document, noticeChanges)
        return true
    }

    /** [requestedName] is numbered ("… (copy) 2") when another project already uses it. */
    fun saveLocalAsCopy(requestedName: String): Boolean {
        if (!checkEditorWritable() || readOnly) {
            notices = listOf(message("workspace_document_unavailable")); publish(); return false
        }
        val name = uniqueProjectName(requestedName, repository.list().projects.map { it.name })
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
        val mark = noticeChanges
        rememberRecent(WorkspaceRecentFile.Copy(document.info.id, document.info.name))
        mountDocument(document, mark)
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
            // The file now holds [text]: an undo back to the opened version is a change from it, so the baseline goes.
            sessionCheckpoints[id] = checkpoint.copy(xml = text, view = current.viewState, baseline = null)
        }
        val stamp = if (current.info.revision > scope.revision) workspaceContentStamp(current.xml) else workspaceContentStamp(text)
        // The project content the file now holds: the stored revision it was written from, unless edits followed it.
        val synced = if (text == current.xml || current.info.revision <= scope.revision) workspaceContentStamp(current.xml)
            else workspaceContentStamp(text)
        if (reference.mode == DocumentOpenMode.EDIT_IN_PLACE && reference.writable) {
            runCatching {
                links.write(WorkspaceExternalDocument(id, reference, stamp))
                links.writeSync(id, WorkspaceExternalSync(reference.id, synced))
                externalSyncs[id] = synced
                validatedLinks += id
                sessionFiles[id] = reference.name
                // The checkpoint above is now the file's contents.
                if (id in sessionCheckpoints) confirmedFiles += id
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

    private fun mutateProject(
        id: WorkspaceProjectId, revision: Long, next: WorkspaceProjectId? = null, restoring: Boolean = false,
        reopenSession: Boolean = false, action: (Long) -> WorkspaceMutationResult,
    ): Boolean {
        val active = projectId == id
        if (active && !flush()) return false
        val expected = if (active) session.state.document?.info?.revision ?: revision else revision
        val result = action(expected)
        when (result) {
            is WorkspaceMutationResult.Success -> {
                // An earlier failure no longer applies; a warning about the open document does, unless it was trashed.
                notices = if (active && result.document.info.deleted) emptyList() else documentNotices()
                if (result.document.info.id != id) importReports[id]?.let { importReports[result.document.info.id] = it }
                else if (result.document.info.deleted) { trashSession(id, expected, result.document.info.revision); importReports.remove(id) }
                else {
                    if (restoring && reopenSession && id !in sessionCheckpoints) restoreTrashedSession(id)
                    else if (restoring) trashedSessions.remove(id)
                    recordCheckpointWrite(result.document, expected)
                }
                if (active && result.document.info.deleted) {
                    // Never mount a writable demo here: its first autosave would create a project nobody asked for.
                    val target = next?.takeIf { it != id }
                    if (target != null && session.open(target, discardPending = true)) mountDocument(checkNotNull(session.state.document), noticeChanges)
                    else { session.close(discardPending = true); detachEditor() }
                } else if (active) session.open(id)
            }
            is WorkspaceMutationResult.Conflict -> notices = listOf(message(Messages.WorkspaceConflictSaved))
            is WorkspaceMutationResult.Failure -> notices = listOf(result.notice)
            WorkspaceMutationResult.Missing -> notices = listOf(message(Messages.WorkspaceErrorUnavailable))
        }
        refresh(); publish()
        return result is WorkspaceMutationResult.Success
    }

    private fun trashSession(id: WorkspaceProjectId, expectedRevision: Long, trashedRevision: Long) {
        // Only the latest trash can be undone (the host offers one Undo), so older stashed sessions go.
        trashedSessions.clear()
        sessionCheckpoints[id]?.let { checkpoint ->
            val tracked = if (checkpoint.revision == expectedRevision) checkpoint.copy(revision = trashedRevision) else checkpoint
            trashedSessions[id] = TrashedSession(tracked, sessionXml[id], sessionFiles[id], id in confirmedFiles)
        }
        forgetSession(id)
    }

    private fun restoreTrashedSession(id: WorkspaceProjectId) {
        val trashed = trashedSessions.remove(id) ?: return
        sessionCheckpoints[id] = trashed.checkpoint
        trashed.xml?.let { sessionXml[id] = it }
        trashed.file?.let { sessionFiles[id] = it; loadSync(id) }
        if (trashed.confirmed) confirmedFiles += id
    }

    /** The notices that describe the open document itself, which outlast changes to other projects. */
    private fun documentNotices(): List<LocalizedText> = notices.filter { it.key in DOCUMENT_NOTICES }

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
        if (projectId == null || attachedMount != mount || !checkEditorWritable() || readOnly) return
        session.edit(exportXml() ?: session.state.xml ?: return, exportView() ?: session.state.viewState)
    }

    private fun flushSession(): WorkspaceMutationResult? {
        val revision = session.state.document?.info?.revision
        return session.flush().also {
            when (it) {
                is WorkspaceMutationResult.Success -> saveFailed = false
                is WorkspaceMutationResult.Failure, WorkspaceMutationResult.Missing -> saveFailed = true
                else -> Unit
            }
            confirm(it, revision)
        }
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
        if (document.readOnly || readOnly || document.info.id !in validatedLinks) return
        runCatching {
            links.read(document.info.id)?.let { link ->
                links.write(link.copy(workspaceStamp = workspaceContentStamp(document.xml)))
                if (projectId == document.info.id) documentBinding?.activate(document)
            }
        }.onFailure(::report)
    }

    /** True when this opens the tab, rather than returning to it. */
    private fun rememberCheckpoint(document: WorkspaceDocument): Boolean {
        if (document.info.id in sessionCheckpoints) return false
        sessionCheckpoints[document.info.id] = SessionCheckpoint(document.xml, document.viewState, document.info.revision, clock())
        return true
    }

    /** Migration and recovery notices from loading the catalog outlive the first mount. */
    private fun keepStartupNotices(startup: List<LocalizedText>) {
        if (startup.isEmpty()) return
        notices = (startup + notices).distinct()
        publish()
    }

    /**
     * [since] is [noticeChanges] when the caller began preparing this mount. A notice about the previous editor no
     * longer applies, but one raised since then (a failed link or recent-files write for this document) stays;
     * validateLink re-reports a link mismatch below.
     */
    private fun mountDocument(document: WorkspaceDocument, since: Long) {
        reopenedFile = null
        if (noticeChanges == since) notices = emptyList()
        editorClosed = false
        saveFailed = false
        if (lastRevert?.id == document.info.id) lastRevert = null
        // A trashed tab's kept session only serves its Undo; opening the project again starts a new session.
        trashedSessions.remove(document.info.id)
        awaitingBaseline = document.info.id.takeIf { rememberCheckpoint(document) }
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

    /**
     * Links [document] to the file [reference]. [fromFile] says [xml] is what the file was just read as; only then does
     * the tab count as matching its file. A legacy checkpoint or an edited draft is linked without that claim.
     */
    private fun associate(document: WorkspaceDocument, reference: DocumentReference, xml: String, fromFile: Boolean = true) {
        if (reference.mode != DocumentOpenMode.EDIT_IN_PLACE || !reference.writable) return
        runCatching {
            val stamp = workspaceContentStamp(xml)
            links.write(WorkspaceExternalDocument(document.info.id, reference, stamp))
            val synced = stamp.takeIf { fromFile }
            links.writeSync(document.info.id, WorkspaceExternalSync(reference.id, synced))
            externalSyncs[document.info.id] = synced
            validatedLinks += document.info.id
            sessionFiles[document.info.id] = reference.name
            // The tab opens with the file's own contents (its checkpoint, made now or just before).
            if (fromFile && (sessionCheckpoints[document.info.id]?.xml ?: document.xml) == xml) confirmedFiles += document.info.id
        }.onFailure(::report)
    }

    /** Reads what [id]'s file was last known to hold; another window may have saved it since this one looked. */
    private fun loadSync(id: WorkspaceProjectId, link: WorkspaceExternalDocument? = runCatching { links.read(id) }.getOrNull()) {
        externalSyncs[id] = link?.let { current ->
            runCatching { links.readSync(id) }.getOrNull()?.takeIf { it.referenceId == current.reference.id }?.workspaceStamp
        }
    }

    private fun validateLink(document: WorkspaceDocument) {
        val link = runCatching { links.read(document.info.id) }.onFailure(::report).getOrNull()
        if (link != null && link.reference.mode == DocumentOpenMode.EDIT_IN_PLACE && link.reference.writable) {
            sessionFiles[document.info.id] = link.reference.name
            loadSync(document.info.id, link)
        } else sessionFiles.remove(document.info.id)
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
    private fun publish() {
        val id = projectId
        val current = session.state
        if (id != null && id in sessionCheckpoints && current.document?.info?.id == id) current.xml?.let { sessionXml[id] = it }
        onStateChanged(state)
    }
    private fun projectName(xml: String): String = (CircCodec.readProject(xml) as? CircProjectImportResult.Success)
        ?.project?.mainCircuitName ?: "main"
    private fun defaultXml(circuit: CircuitDocument = DemoCircuits.andGate()) = CircCodec.writeProject(
        ProjectBuiltinLibraries.withDefaults(CircuitProject(listOf(circuit)), ComponentCatalog.registry),
    )

    private companion object {
        val DOCUMENT_NOTICES = setOf(Messages.WorkspaceDocumentUnavailable, Messages.WorkspaceReadOnly, "workspace_external_link_mismatch")
    }
}
