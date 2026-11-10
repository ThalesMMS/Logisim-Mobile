package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

enum class WorkspaceSaveStatus { CLEAN, DIRTY, SAVED, FAILED, CONFLICT }

data class WorkspaceSessionState(
    val document: WorkspaceDocument? = null,
    val xml: String? = null,
    val viewState: WorkspaceViewState = WorkspaceViewState(),
    val saveStatus: WorkspaceSaveStatus = WorkspaceSaveStatus.CLEAN,
    val confirmedRevision: Long? = null,
    val conflict: WorkspaceDocument? = null,
    val notices: List<LocalizedText> = emptyList(),
    val foreground: Boolean = true,
) {
    val message: String? get() = notices.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { it.resolve(AppLanguage.PORTUGUESE) }
    val dirty: Boolean get() = saveStatus in setOf(WorkspaceSaveStatus.DIRTY, WorkspaceSaveStatus.FAILED, WorkspaceSaveStatus.CONFLICT)
    val readOnly: Boolean get() = document?.readOnly == true
    val mayRun: Boolean get() = foreground && document != null && conflict == null && !readOnly
}

/** One window owns one session; repository transactions coordinate windows and processes. */
class WorkspaceSession(
    private val repository: WorkspaceCatalogRepository,
    private val pauseSimulation: () -> Unit,
    private val onStateChanged: (WorkspaceSessionState) -> Unit = {},
) {
    var state: WorkspaceSessionState = WorkspaceSessionState()
        private set

    fun open(id: WorkspaceProjectId, discardPending: Boolean = false): Boolean {
        if (!discardPending && !flushBeforeSwitch()) return false
        pauseSimulation()
        return when (val result = repository.open(id)) {
            is WorkspaceOpenResult.Success -> {
                val selected = repository.selectActive(id)
                install(result.document.copy(notices = result.document.notices + selected.notices))
                true
            }
            is WorkspaceOpenResult.Failure -> { failure(result.notice); false }
            WorkspaceOpenResult.Missing -> { failure(message(Messages.WorkspaceErrorUnavailable)); false }
        }
    }

    fun create(name: String, xml: String, attachments: Map<String, WorkspaceAttachment> = emptyMap()): Boolean {
        if (!flushBeforeSwitch()) return false
        pauseSimulation()
        return installMutation(repository.create(name, xml, attachments = attachments))
    }

    fun edit(xml: String, viewState: WorkspaceViewState = state.viewState) {
        val document = state.document ?: return
        if (document.readOnly) return
        val view = compatibleView(xml, viewState)
        val changed = xml != document.xml || view != document.viewState || document.contentRevision != document.info.revision
        update(state.copy(xml = xml, viewState = view,
            saveStatus = when {
                state.conflict != null -> WorkspaceSaveStatus.CONFLICT
                changed -> WorkspaceSaveStatus.DIRTY
                else -> WorkspaceSaveStatus.CLEAN
            }, notices = if (state.conflict != null) state.notices else withRecovery(emptyList())))
    }

    fun flush(): WorkspaceMutationResult? {
        val document = state.document ?: return null
        if (document.readOnly || !state.dirty) return WorkspaceMutationResult.Success(document)
        val xml = state.xml ?: return null
        val result = repository.save(document.info.id, document.info.revision, xml, state.viewState)
        when (result) {
            is WorkspaceMutationResult.Success -> recordSave(result.document)
            is WorkspaceMutationResult.Conflict -> {
                pauseSimulation()
                update(state.copy(saveStatus = WorkspaceSaveStatus.CONFLICT, conflict = result.current,
                    notices = withRecovery(listOf(message(Messages.WorkspaceConflictSaved)))))
            }
            is WorkspaceMutationResult.Failure -> failure(result.notice)
            WorkspaceMutationResult.Missing -> failure(message(Messages.WorkspaceConflictRemoved))
        }
        return result
    }

    /** Commit a candidate without replacing the live buffer when storage rejects it. */
    fun commitEdit(xml: String): WorkspaceMutationResult {
        val document = state.document ?: return WorkspaceMutationResult.Missing
        if (document.readOnly || state.dirty) return WorkspaceMutationResult.Failure(message(Messages.WorkspaceErrorUnavailable))
        val result = repository.save(document.info.id, document.info.revision, xml, compatibleView(xml, state.viewState))
        if (result is WorkspaceMutationResult.Success) recordSave(result.document)
        return result
    }

    private fun recordSave(document: WorkspaceDocument) = update(state.copy(document = document,
        xml = document.xml, viewState = document.viewState, confirmedRevision = document.contentRevision,
        saveStatus = WorkspaceSaveStatus.SAVED, conflict = null, notices = withRecovery(emptyList())))

    fun foregroundChanged(foreground: Boolean) {
        if (!foreground) {
            pauseAndFlush()
            return
        }
        if (state.foreground) return
        update(state.copy(foreground = true))
        val document = state.document ?: return
        when (val opened = repository.open(document.info.id)) {
            is WorkspaceOpenResult.Success -> {
                if (opened.document.info.revision != document.info.revision || opened.document.readOnly != document.readOnly) {
                    if (state.dirty) update(state.copy(saveStatus = WorkspaceSaveStatus.CONFLICT,
                        conflict = opened.document, notices = withRecovery(listOf(message(Messages.WorkspaceConflictChanged)))))
                    else install(opened.document, state.viewState)
                }
            }
            is WorkspaceOpenResult.Failure -> failure(opened.notice)
            WorkspaceOpenResult.Missing -> failure(message(Messages.WorkspaceConflictRemoved))
        }
    }

    fun pauseAndFlush() {
        if (state.foreground) update(state.copy(foreground = false))
        pauseSimulation()
        flush()
    }

    fun reloadStored(): Boolean {
        val id = state.document?.info?.id ?: return false
        pauseSimulation()
        return when (val opened = repository.open(id)) {
            is WorkspaceOpenResult.Success -> { install(opened.document); true }
            is WorkspaceOpenResult.Failure -> { failure(opened.notice); false }
            WorkspaceOpenResult.Missing -> { failure(message(Messages.WorkspaceErrorUnavailable)); false }
        }
    }

    fun saveLocalAsCopy(name: String): Boolean {
        val xml = state.xml ?: return false
        pauseSimulation()
        val attachments = mutableMapOf<String, WorkspaceAttachment>()
        val document = state.document
        if (document != null) for ((key, reference) in document.attachments) {
            when (val result = repository.readAttachment(document.info.id, key)) {
                is WorkspaceAttachmentReadResult.Success -> {
                    if (WorkspaceAttachmentBlobs.references(mapOf(key to result.attachment))[key] != reference) {
                        failure(message("workspace_attachment_corrupt", "key" to key, "name" to reference.name))
                        return false
                    }
                    attachments[key] = result.attachment
                }
                is WorkspaceAttachmentReadResult.Failure -> { failure(result.notice); return false }
                WorkspaceAttachmentReadResult.Missing -> {
                    failure(message("workspace_attachment_missing", "key" to key, "name" to reference.name))
                    return false
                }
            }
        }
        return installMutation(repository.create(name, xml, state.viewState, attachments))
    }

    fun saveLocalAsCopy(name: String, xml: String, viewState: WorkspaceViewState,
                        attachments: Map<String, WorkspaceAttachment> = emptyMap()): Boolean {
        pauseSimulation()
        return installMutation(repository.create(name, xml, viewState, attachments))
    }

    fun close(discardPending: Boolean = false): Boolean {
        pauseSimulation()
        if (!discardPending && !flushBeforeSwitch()) return false
        val selected = repository.selectActive(null)
        if (selected.readOnly) { failure(selected.notices); return false }
        update(WorkspaceSessionState(foreground = state.foreground))
        return true
    }

    private fun installMutation(result: WorkspaceMutationResult): Boolean = when (result) {
        is WorkspaceMutationResult.Success -> { install(result.document, stored = false); true }
        is WorkspaceMutationResult.Failure -> { failure(result.notice); false }
        is WorkspaceMutationResult.Conflict -> { failure(message(Messages.WorkspaceConflictRevision)); false }
        WorkspaceMutationResult.Missing -> { failure(message(Messages.WorkspaceErrorUnavailable)); false }
    }

    private fun flushBeforeSwitch(): Boolean = !state.dirty || flush() is WorkspaceMutationResult.Success
    private fun install(document: WorkspaceDocument, view: WorkspaceViewState = document.viewState, stored: Boolean = true) {
        val project = (CircCodec.readProject(document.xml) as? CircProjectImportResult.Success)?.project
        val restored = when {
            project == null -> WorkspaceViewRestoration(view)
            stored -> view.restoreStored(project)
            else -> WorkspaceViewRestoration(view.compatibleWith(project))
        }
        val notices = document.notices + if (restored.recovered) listOf(message(Messages.WorkspaceViewIdentityRecovered)) else emptyList()
        update(WorkspaceSessionState(document, document.xml, restored.viewState,
            if (document.readOnly || document.contentRevision == document.info.revision) WorkspaceSaveStatus.CLEAN else WorkspaceSaveStatus.DIRTY,
            document.contentRevision, notices = notices, foreground = state.foreground))
    }
    private fun failure(notice: LocalizedText) = failure(listOf(notice))
    private fun failure(notices: List<LocalizedText>) = update(state.copy(
        saveStatus = if (state.document == null || state.readOnly) WorkspaceSaveStatus.CLEAN else WorkspaceSaveStatus.FAILED, notices = withRecovery(notices)))
    private fun withRecovery(notices: List<LocalizedText>): List<LocalizedText> =
        (state.notices.filter { it.key == Messages.WorkspaceViewIdentityRecovered } + notices).distinct()
    private fun update(next: WorkspaceSessionState) { state = next; onStateChanged(next) }

    private fun compatibleView(xml: String, view: WorkspaceViewState): WorkspaceViewState {
        val project = (CircCodec.readProject(xml) as? CircProjectImportResult.Success)?.project ?: return view
        return view.compatibleWith(project)
    }
}
