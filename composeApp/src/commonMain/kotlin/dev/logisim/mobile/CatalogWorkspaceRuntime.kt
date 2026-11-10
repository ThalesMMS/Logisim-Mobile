package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext

/** XML/view are captured on the UI thread before entering the storage actor. */
data class WorkspaceEditorSnapshot(
    val xml: String?,
    val view: WorkspaceViewState?,
    val identity: Long,
    val writable: Boolean?,
    val importReport: EditorImportReport?,
)

data class CatalogWorkspaceRuntimeState(
    val workspace: CatalogWorkspaceState? = null,
    val busy: Boolean = true,
    val pendingWrites: Boolean = false,
)

/** One worker owns the session, repository and links. Native actions and editor control stay on UI. */
class CatalogWorkspaceRuntime(
    storage: WorkspaceCatalogStorage,
    private val windowId: String,
    private val control: EditorSessionControl,
    nativeDocuments: DocumentActions? = null,
    registry: ComponentRegistry = ComponentCatalog.registry,
    private val deliveryContext: CoroutineContext = Dispatchers.Main.immediate,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onExternalWorkspaceSaved: (String) -> Unit = {},
    private val projectTemplates: ProjectTemplateStore? = null,
    private val onStateChanged: (CatalogWorkspaceRuntimeState) -> Unit = {},
) {
    private enum class Kind { START, ATTACH, EDIT, REPLACE, FOREGROUND, SWITCH, METADATA, EXTERNAL, CLOSE }
    private data class Work(
        val sequence: Long,
        val kind: Kind,
        val mount: Long?,
        val snapshot: WorkspaceEditorSnapshot?,
        val identityAfter: Long? = null,
        val action: (CatalogWorkspaceBinding) -> Boolean,
        val completed: (Boolean) -> Unit = {},
        val externalScope: WorkspaceEditorScope? = null,
        val externalReference: DocumentReference? = null,
        val committedXml: String? = null,
    )
    private data class Reply(val state: CatalogWorkspaceState, val identity: Long,
        val link: WorkspaceExternalDocument?, val documentStamp: String?, val success: Boolean)
    private data class Attachment(val mount: Long, val identity: Long, val writable: Boolean?, val report: EditorImportReport?)
    private data class PreparedCopy(val projectId: WorkspaceProjectId, val mount: Long,
        val completed: (WorkspacePreparedCopyResult) -> Unit)

    private val queue = Channel<Work>(Channel.UNLIMITED)
    private val workerIdentities = mutableMapOf<Pair<Long, Long>, WorkspaceProjectId>()
    private val workerJob = SupervisorJob()
    private val workerScope = CoroutineScope(workerJob + workerDispatcher)
    private var sequence = 0L
    private var started = false
    private var latestMutation = 0L
    private var completedSequence = 0L
    private var logicalIdentity = 0L
    private var readyMount: Long? = null
    private var attachment: Attachment? = null
    private var foreground = true
    private var closed = false
    private var nativeWrites = 0
    private var closingSession: WorkspaceProjectId? = null
    private var switchingSequence: Long? = null
    private var projectCommitInFlight = false
    private var latestForegroundSequence: Long? = null
    private var latestBusySequence = 0L
    private var cachedLink: WorkspaceExternalDocument? = null
    private var cachedDocumentStamp: String? = null
    private var stateValue = CatalogWorkspaceRuntimeState()
    private var validationRegistry = registry
    private var preparedCopy: PreparedCopy? = null
    val state: CatalogWorkspaceRuntimeState get() = stateValue
    private val emptyState = CatalogWorkspaceState(WorkspaceCatalogResult(emptyList(), null), WorkspaceSessionState(), 0, null, null)

    private val nativeBinding = nativeDocuments?.let { native -> CatalogDocumentActions(native,
        object : WorkspaceDocumentCoordinator {
            override val state get() = stateValue.workspace ?: emptyState
            override fun scope(): WorkspaceEditorScope = currentScope()
            override fun matches(scope: WorkspaceEditorScope) = matchesCurrent(scope)
            override fun activeLink() = cachedLink?.takeIf { it.projectId == state.editorProjectId }
            override fun activeDocumentStamp() = cachedDocumentStamp
            override fun confirmedExternal(xml: String) = onExternalWorkspaceSaved(xml)
            override fun externalSaved(scope: WorkspaceEditorScope, reference: DocumentReference, text: String,
                                       previousReference: DocumentReference?, onCommitted: () -> Unit) {
                enqueueExternalSave(scope, reference, text, previousReference, onCommitted)
            }
            override fun nativeWriteStarted() { nativeWrites++ }
            override fun nativeWriteFinished() { nativeWrites--; closeQueueIfDrained() }
        }) }
    val documents: DocumentActions? get() = nativeBinding

    init {
        control.setInteractionEnabled(false)
        control.setActive(false)
        workerScope.launch {
            var editor: WorkspaceEditorSnapshot? = null
            var workerIdentity = 0L
            val identities = workerIdentities
            val model = CatalogWorkspaceBinding(
                WorkspaceCatalogRepository(storage, validate = { CircCodec.readProject(it, validationRegistry) is CircProjectImportResult.Success }),
                StoredWorkspaceDocumentLinks(storage), windowId, pauseSimulation = {},
                exportXml = { editor?.xml }, exportView = { editor?.view },
                editorIdentity = { editor?.identity }, editorWritable = { editor?.writable },
            )
            try {
                for (work in queue) {
                    var success = false
                    try {
                        val beforeMount = model.state.editorMount
                        val sameMount = work.mount == null || work.mount == beforeMount
                        val sameIdentity = work.snapshot == null || work.snapshot.identity == workerIdentity
                        val required = work.kind in setOf(Kind.ATTACH, Kind.EDIT, Kind.REPLACE)
                        if (!required || (sameMount && (sameIdentity || work.kind == Kind.REPLACE))) {
                            if (sameMount && (sameIdentity || work.kind == Kind.REPLACE)) editor = work.snapshot ?: editor
                            success = work.action(model)
                            if (success && work.committedXml != null) editor = editor?.copy(xml = work.committedXml)
                            if (model.state.editorMount != beforeMount) { workerIdentity = 0; editor = null }
                            else if (work.identityAfter != null) workerIdentity = work.identityAfter
                        }
                        model.state.editorProjectId?.let { identities[model.state.editorMount to workerIdentity] = it }
                    } catch (error: Exception) { model.report(error) }
                    val link = model.activeLink()
                    val reply = Reply(model.state, workerIdentity, link,
                        model.state.session.document?.xml?.let(::workspaceContentStamp), success)
                    withContext(deliveryContext.minusKey(Job)) { accept(work, reply) }
                }
            } finally { workerJob.cancel() }
        }.invokeOnCompletion { workerJob.cancel() }
    }

    fun start(legacyXml: String? = null, legacyReference: DocumentReference? = null) {
        if (started || closed) return
        started = true
        enqueue(Kind.START, null, null) { it.start(legacyXml, legacyReference); true }
    }

    fun updateExtensionRegistry(registry: ComponentRegistry) {
        enqueue(Kind.METADATA, null, null) { validationRegistry = registry; true }
    }

    fun editorAttached(mount: Long, report: EditorImportReport? = null) {
        if (closed || !control.isAttached || mount != stateValue.workspace?.editorMount ||
            (stateValue.busy && readyMount == mount)) return
        val identity = control.documentIdentity ?: return
        val next = Attachment(mount, identity, control.documentWritable, report)
        if (attachment == next) return
        attachment = next
        val snapshot = capture(mount) ?: return
        enqueue(Kind.ATTACH, mount, snapshot) { it.editorAttached(mount, report); true }
    }

    fun documentChanged(mount: Long, xml: String) {
        if (closed || stateValue.busy || mount != stateValue.workspace?.editorMount) return
        val snapshot = capture(mount, xml) ?: return
        enqueue(Kind.EDIT, mount, snapshot, durable = true) { it.documentChanged(mount, xml); true }
    }

    fun projectReplaced(mount: Long, event: EditorProjectReplacement) {
        if (closed || stateValue.busy || mount != stateValue.workspace?.editorMount) return
        control.pause()
        val snapshot = capture(mount, event.xml) ?: return
        val reference = nativeBinding?.pendingReference
        logicalIdentity = event.identity
        cachedLink = null; cachedDocumentStamp = null
        stateValue = stateValue.copy(workspace = stateValue.workspace?.copy(editorXml = event.xml, editorProjectId = null,
            readOnly = snapshot.writable == false, editorImportReport = event.importReport), pendingWrites = true)
        nativeBinding?.activate(null)
        publish()
        enqueue(Kind.REPLACE, mount, snapshot, identityAfter = event.identity, durable = true) {
            it.projectReplaced(mount, event, reference); true
        }
    }

    fun viewChanged(mount: Long, view: WorkspaceViewState) {
        if (closed || stateValue.busy || mount != stateValue.workspace?.editorMount) return
        val snapshot = capture(mount)?.copy(view = view) ?: return
        enqueue(Kind.EDIT, mount, snapshot, durable = true) { it.viewChanged(mount, view); it.flush() }
    }

    fun flush() {
        if (closed || stateValue.busy) return
        val workspace = stateValue.workspace ?: return
        val snapshot = capture(workspace.editorMount, allowDetached = true)
        enqueue(Kind.EDIT, workspace.editorMount, snapshot, durable = true) { it.flush() }
    }

    fun foregroundChanged(active: Boolean) {
        if (closed) return
        foreground = active
        control.setActive(false)
        control.setInteractionEnabled(false)
        nativeBinding?.editorDetached()
        val workspace = stateValue.workspace
        val snapshot = workspace?.takeUnless { projectCommitInFlight }?.let { capture(it.editorMount, allowDetached = true) }
        if (active) { stateValue = stateValue.copy(busy = true); publish() }
        latestForegroundSequence = sequence + 1
        if (active) latestBusySequence = sequence + 1
        enqueue(Kind.FOREGROUND, workspace?.editorMount, snapshot, durable = !active) { it.foregroundChanged(active); true }
    }

    fun openProject(id: WorkspaceProjectId, completed: (Boolean) -> Unit = {}) = transition(completed) { it.openProject(id) }

    internal fun openEditorSession(projectName: String, xml: String, report: EditorImportReport?): Boolean {
        if (closed || stateValue.busy || closingSession != null || stateValue.workspace == null || !control.isAttached) return false
        val reference = nativeBinding?.pendingReference
        val name = reference?.name?.removeSuffix(".circ")?.takeIf { it.isNotBlank() } ?: projectName
        transition { it.openNewSession(name, xml, report, reference) }
        return true
    }

    internal fun closeEditorSession(
        id: WorkspaceProjectId, next: WorkspaceProjectId?, save: Boolean, completed: (Boolean) -> Unit,
    ) {
        val workspace = stateValue.workspace
        if (closed || stateValue.busy || closingSession != null || workspace == null) { completed(false); return }
        val documents = nativeBinding
        if (!save || documents == null || !documents.supportsEditInPlace) {
            finishSessionClose(id, next, save, null, completed)
            return
        }
        closingSession = id
        val activeMount = workspace.editorMount.takeIf { workspace.editorProjectId == id }
        val snapshot = activeMount?.let { capture(it) }
        if (activeMount != null) {
            control.pause()
            control.setInteractionEnabled(false)
        }
        fun ready() = !closed && foreground && closingSession == id &&
            (activeMount == null || stateValue.workspace?.let {
                it.editorMount == activeMount && it.editorProjectId == id
            } == true)
        fun complete(originalXml: String?, success: Boolean) {
            val proceed = success && ready()
            closingSession = null
            val state = stateValue.workspace
            control.setInteractionEnabled(!closed && !stateValue.busy && foreground &&
                readyMount == state?.editorMount && control.isAttached)
            if (proceed) finishSessionClose(id, next, true, originalXml, completed)
            else completed(false)
        }
        var prepared: WorkspaceSessionSave? = null
        enqueue(Kind.METADATA, activeMount, snapshot, durable = true, completed = { success ->
            val source = prepared
            if (!success || !ready() || source == null) complete(null, false)
            else if (source.reference == null) complete(null, true)
            else documents.saveSessionOriginal(source, ::ready) { result ->
                when (result) {
                    is DocumentWriteResult.Saved -> complete(source.xml, true)
                    DocumentWriteResult.Cancelled -> complete(null, false)
                    is DocumentWriteResult.Failure, is DocumentWriteResult.Conflict -> {
                        val notice = (result as? DocumentWriteResult.Failure)?.notice ?: message(Messages.ErrorFileConflict)
                        enqueue(Kind.METADATA, null, null, completed = { complete(null, false) }) { it.sessionCloseFailure(notice) }
                    }
                }
            }
        }) {
            prepared = it.prepareSessionSave(id)
            prepared != null
        }
    }

    private fun finishSessionClose(
        id: WorkspaceProjectId, next: WorkspaceProjectId?, save: Boolean, originalXml: String?, completed: (Boolean) -> Unit,
    ) {
        if (id == stateValue.workspace?.editorProjectId) {
            transition(completed) { it.closeEditorSession(id, next, save, originalXml) }
        } else if (closed || stateValue.busy) {
            completed(false)
        } else {
            enqueue(Kind.METADATA, null, null, durable = true, completed = completed) {
                it.closeInactiveSession(id, save, originalXml)
            }
        }
    }

    fun openRecentFile(file: WorkspaceRecentFile, completed: (Boolean) -> Unit) {
        if (closed || stateValue.busy || closingSession != null || file !in stateValue.workspace?.recentFiles.orEmpty()) { completed(false); return }
        when (file) {
            is WorkspaceRecentFile.Copy -> openProject(file.projectId, completed)
            is WorkspaceRecentFile.Original -> {
                val documents = nativeBinding ?: run { completed(false); return }
                documents.reopen(file.reference)
                completed(true)
            }
        }
    }
    fun createProject(
        name: String,
        circuitName: String = DEFAULT_NEW_CIRCUIT_NAME,
        circuitAttributes: Map<String, String> = emptyMap(),
        completed: (Boolean) -> Unit = {},
    ) = transition(completed) { it.createProject(name, projectTemplates?.templateXml(circuitName, circuitAttributes), circuitName, circuitAttributes) }
    fun openPreparedCopy(name: String, xml: String, completed: (Boolean) -> Unit = {}) =
        transition(completed) { it.openPreparedCopy(name, xml) }
    fun openPreparedCopy(name: String, xml: String, attachments: Map<String, WorkspaceAttachment>,
                         expectedMount: Long, expectedIdentity: Long, completed: (WorkspacePreparedCopyResult) -> Unit) {
        if (!sourceIsCurrent(expectedMount, expectedIdentity)) { completed(WorkspacePreparedCopyResult.Cancelled); return }
        val ownedAttachments = attachments.toMap()
        transition(completed = { success ->
            if (closed) completed(WorkspacePreparedCopyResult.Cancelled)
            else if (!success) {
                val workspace = stateValue.workspace
                completed(WorkspacePreparedCopyResult.Failure(workspace?.session?.notices?.firstOrNull()
                    ?: workspace?.notices?.firstOrNull() ?: message("workspace_copy_not_opened")))
            } else {
                val workspace = stateValue.workspace
                val id = workspace?.editorProjectId
                if (id == null) completed(WorkspacePreparedCopyResult.Failure(message("workspace_copy_not_opened")))
                else {
                    preparedCopy?.completed?.invoke(WorkspacePreparedCopyResult.Cancelled)
                    preparedCopy = PreparedCopy(id, workspace.editorMount, completed)
                    finishPreparedCopy()
                }
            }
        }) { it.openPreparedCopy(name, xml, ownedAttachments) }
    }

    suspend fun readAttachment(mount: Long, identity: Long, key: String): WorkspaceAttachmentReadResult =
        withContext(deliveryContext.minusKey(Job)) {
            fun failure() = WorkspaceAttachmentReadResult.Failure(message("workspace_source_not_current"))
            if (!sourceIsCurrent(mount, identity)) return@withContext failure()
            val source = currentScope()
            val done = CompletableDeferred<WorkspaceAttachmentReadResult>()
            var result: WorkspaceAttachmentReadResult = failure()
            val accepted = enqueue(Kind.METADATA, null, null, completed = {
                done.complete(if (sourceIsCurrent(mount, identity)) result else failure())
            }) { model ->
                val id = source.projectId ?: workerIdentities[source.mount to source.identity]
                result = if (id == null) WorkspaceAttachmentReadResult.Failure(message("workspace_source_not_committed"))
                    else model.readAttachment(id, key)
                true
            }
            if (!accepted) return@withContext failure()
            select<WorkspaceAttachmentReadResult> {
                done.onAwait { it }
                workerJob.onJoin { failure() }
            }
        }

    private fun sourceIsCurrent(mount: Long, identity: Long): Boolean = !closed && !stateValue.busy && control.isAttached &&
        stateValue.workspace?.editorMount == mount && logicalIdentity == identity && control.documentIdentity == identity

    private fun finishPreparedCopy() {
        val pending = preparedCopy ?: return
        val workspace = stateValue.workspace
        if (closed || workspace == null || workspace.editorMount != pending.mount || workspace.editorProjectId != pending.projectId) {
            preparedCopy = null
            pending.completed(WorkspacePreparedCopyResult.Cancelled)
        } else if (!stateValue.busy && foreground && readyMount == pending.mount && control.isAttached &&
            control.documentIdentity == logicalIdentity) {
            preparedCopy = null
            pending.completed(WorkspacePreparedCopyResult.Opened(pending.projectId, pending.mount, logicalIdentity))
        }
    }

    fun reloadStored() = transition { it.reloadStored() }
    fun saveLocalAsCopy(name: String) = transition { it.saveLocalAsCopy(name) }
    fun duplicateProject(id: WorkspaceProjectId, revision: Long) = transition { it.duplicateProject(id, revision); true }
    fun renameProject(id: WorkspaceProjectId, revision: Long, name: String) = transition { it.renameProject(id, revision, name); true }
    fun deleteProject(id: WorkspaceProjectId, revision: Long) = transition { it.deleteProject(id, revision); true }
    fun restoreProject(id: WorkspaceProjectId, revision: Long) = transition { it.restoreProject(id, revision); true }

    internal fun commitEditorChange(mount: Long, identity: Long, xml: String, completed: (Boolean) -> Unit) {
        if (!sourceIsCurrent(mount, identity) || stateValue.workspace?.editorProjectId == null || control.documentWritable != true) {
            completed(false)
            return
        }
        projectCommitInFlight = true
        transition(completed = {
            projectCommitInFlight = false
            completed(it)
        }, committedXml = xml) { it.commitEditorChange(xml) }
    }

    private fun transition(completed: (Boolean) -> Unit = {}, committedXml: String? = null, action: (CatalogWorkspaceBinding) -> Boolean) {
        val workspace = stateValue.workspace
        if (closed || stateValue.busy || closingSession != null || workspace == null) { completed(false); return }
        val snapshot = capture(workspace.editorMount)
        control.pause(); control.setActive(false); control.setInteractionEnabled(false)
        nativeBinding?.editorDetached()
        stateValue = stateValue.copy(busy = true)
        val next = sequence + 1
        switchingSequence = next
        latestBusySequence = next
        publish()
        enqueue(Kind.SWITCH, workspace.editorMount, snapshot, durable = true, completed = completed,
            committedXml = committedXml, action = action)
    }

    fun close() {
        if (closed) return
        control.pause(); control.setActive(false); control.setInteractionEnabled(false)
        nativeBinding?.editorDetached()
        val workspace = stateValue.workspace
        val snapshot = workspace?.takeUnless { projectCommitInFlight }?.let { capture(it.editorMount, allowDetached = true) }
        enqueue(Kind.CLOSE, workspace?.editorMount, snapshot, durable = true) { it.foregroundChanged(false); true }
        closed = true
        val pending = preparedCopy
        preparedCopy = null
        closeQueueIfDrained()
        pending?.completed?.invoke(WorkspacePreparedCopyResult.Cancelled)
    }

    private fun closeQueueIfDrained() { if (closed && nativeWrites == 0) queue.close() }

    suspend fun awaitPersistence() = withContext(deliveryContext.minusKey(Job)) {
        val done = CompletableDeferred<Unit>()
        val accepted = enqueue(Kind.METADATA, null, null, completed = { done.complete(Unit) }) { true }
        if (accepted) done.await() else workerJob.join()
    }

    suspend fun awaitClosed() { workerJob.join() }

    private fun capture(mount: Long, xml: String? = null, allowDetached: Boolean = false): WorkspaceEditorSnapshot? {
        if (mount != stateValue.workspace?.editorMount) return null
        val identity = control.documentIdentity ?: return null
        if (!control.isAttached && (!allowDetached || readyMount != mount || identity != logicalIdentity)) return null
        return WorkspaceEditorSnapshot(xml ?: control.exportXml(), control.exportViewState(), identity,
            control.documentWritable, control.exportImportReport())
    }

    private fun enqueue(kind: Kind, mount: Long?, snapshot: WorkspaceEditorSnapshot?, identityAfter: Long? = null,
                        durable: Boolean = false, completed: (Boolean) -> Unit = {}, externalScope: WorkspaceEditorScope? = null,
                        externalReference: DocumentReference? = null, committedXml: String? = null,
                        action: (CatalogWorkspaceBinding) -> Boolean): Boolean {
        val number = ++sequence
        if (durable) { latestMutation = number; stateValue = stateValue.copy(pendingWrites = true); publish() }
        val accepted = queue.trySend(Work(number, kind, mount, snapshot, identityAfter, action, completed, externalScope, externalReference, committedXml)).isSuccess
        if (!accepted) completed(false)
        return accepted
    }

    private fun enqueueExternalSave(scope: WorkspaceEditorScope, reference: DocumentReference, text: String,
                                    previousReference: DocumentReference?, completed: () -> Unit) {
        // Native writes keep the actor alive after disposal until their metadata is acknowledged.
        enqueue(Kind.EXTERNAL, null, null, completed = { completed() }, externalScope = scope, externalReference = reference) { model ->
            val resolved = scope.copy(projectId = scope.projectId ?: workerIdentities[scope.mount to scope.identity])
            model.externalSaved(resolved, reference, text, previousReference)
            true
        }
    }

    private fun currentScope(): WorkspaceEditorScope {
        val workspace = stateValue.workspace ?: emptyState
        return WorkspaceEditorScope(workspace.editorMount, workspace.editorProjectId, logicalIdentity,
            workspace.session.document?.info?.revision ?: 0)
    }

    private fun matchesCurrent(scope: WorkspaceEditorScope): Boolean {
        val current = currentScope()
        return !closed && !stateValue.busy && scope.mount == current.mount && scope.identity == current.identity &&
            (scope.projectId == null || scope.projectId == current.projectId)
    }

    private fun accept(work: Work, reply: Reply) {
        completedSequence = work.sequence
        val previous = stateValue.workspace
        val switching = work.kind == Kind.SWITCH && switchingSequence == work.sequence
        val lifecycleCurrent = work.kind != Kind.FOREGROUND || latestForegroundSequence == work.sequence
        val attachmentCurrent = work.kind != Kind.ATTACH || (reply.success && work.mount == reply.state.editorMount &&
            work.snapshot?.identity == reply.identity && attachment?.mount == reply.state.editorMount && attachment?.identity == reply.identity)
        val resumedFromCurrent = work.kind == Kind.FOREGROUND && (work.mount == null || work.mount == previous?.editorMount) &&
            (work.snapshot == null || work.snapshot.identity == logicalIdentity)
        val matches = lifecycleCurrent && attachmentCurrent && (previous == null || work.kind == Kind.START || switching || resumedFromCurrent ||
            (reply.state.editorMount == previous.editorMount && reply.identity == logicalIdentity))
        if (!closed && matches) {
            val changedMount = previous?.editorMount != reply.state.editorMount
            if (changedMount) { logicalIdentity = reply.identity; readyMount = null; attachment = null }
            cachedLink = reply.link
            cachedDocumentStamp = reply.documentStamp
            var busy = stateValue.busy
            if (work.kind == Kind.ATTACH) {
                readyMount = reply.state.editorMount
                busy = work.sequence < latestBusySequence
            } else if (reply.state.editorClosed) {
                busy = work.sequence < latestBusySequence
            } else if (!changedMount && (switching || work.kind == Kind.FOREGROUND))
                busy = work.sequence < latestBusySequence || readyMount != reply.state.editorMount || !control.isAttached
            stateValue = CatalogWorkspaceRuntimeState(reply.state, busy, completedSequence < latestMutation)
            val ready = !busy && foreground && readyMount == reply.state.editorMount && control.isAttached
            control.setInteractionEnabled(ready && (closingSession == null || closingSession != reply.state.editorProjectId))
            control.setActive(ready && !reply.state.readOnly && reply.state.session.conflict == null)
            if (ready) {
                val document = reply.state.session.document?.takeIf { it.info.id == reply.state.editorProjectId }
                nativeBinding?.activate(document,
                    requiredStamp = if (work.kind == Kind.EXTERNAL && work.externalScope?.let(::matchesCurrent) == true &&
                        reply.link?.let { it.reference == work.externalReference } == true) reply.link?.workspaceStamp else null)
                finishPreparedCopy()
                if (!stateValue.busy && foreground && readyMount == stateValue.workspace?.editorMount && control.isAttached)
                    nativeBinding?.editorAttached()
                else nativeBinding?.editorDetached()
            } else nativeBinding?.editorDetached()
            publish()
        }
        work.completed(reply.success)
    }

    private fun publish() { if (!closed) onStateChanged(stateValue) }
}
