package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.WorkspaceDocument
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedDescription

/** Every async native operation captures its original project and editor identity. */
internal class CatalogDocumentActions(
    private val native: DocumentActions,
    private val workspace: WorkspaceDocumentCoordinator,
) : DocumentActions {
    private var handler: ((DocumentOpenResult) -> Unit)? = null
    private var requestedOpen: WorkspaceEditorScope? = null
    private var editorReady = false
    private val pendingOpens = ArrayDeque<Pair<DocumentOpenResult, WorkspaceEditorScope?>>()
    private var reference: DocumentReference? by mutableStateOf(null)
    private var confirmed: Pair<DocumentReference, String>? = null
    private var deliveredOpen: Pair<DocumentReference, WorkspaceEditorScope>? = null
    var pendingReference: DocumentReference? = null
        private set

    override val supportsEditInPlace: Boolean get() = native.supportsEditInPlace
    override val currentReference: DocumentReference? get() = reference

    fun activate(document: WorkspaceDocument?, requiredStamp: String? = null) {
        val link = workspace.activeLink()
        val stamp = document?.let { workspace.activeDocumentStamp() ?: workspaceContentStamp(it.xml) }
        val next = if (document != null && !document.readOnly && !workspace.state.readOnly && link?.projectId == document.info.id &&
            link.workspaceStamp == (requiredStamp ?: stamp)) link.reference else null
        if (reference != next || native.currentReference != next) native.restoreReference(next)
        reference = next
        if (next == null) confirmed = null
        else if (document != null && stamp != null && link?.workspaceStamp == stamp && confirmed != (next to stamp)) {
            workspace.confirmedExternal(document.xml)
            confirmed = next to stamp
        }
    }

    override fun retain(reference: DocumentReference?) {
        if (reference == null) {
            this.reference = null
            confirmed = null
            native.retain(null)
            return
        }
        val link = workspace.activeLink()
        if (link?.reference != reference || this.reference != reference) {
            this.reference = null
            confirmed = null
            native.retain(null)
            return
        }
        this.reference = reference
        native.retain(reference)
    }

    override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) {
        editorReady = false
        this.handler = handler
        native.setOpenHandler(if (handler == null && windowHandler == null) null else ::opened)
    }

    // The workspace window's own handler while it has no editor (its last tab closed): a file opened from another app
    // or the picker opens a new session at once instead of waiting in the queue for the next editor.
    private var windowHandler: ((DocumentOpenResult) -> Unit)? = null

    fun setWindowHandler(handler: ((DocumentOpenResult) -> Unit)?) {
        windowHandler = handler
        if (this.handler != null) return
        native.setOpenHandler(if (handler == null) null else ::opened)
        workspaceIdle()
    }

    /**
     * The workspace finished its queued work. A file that arrived meanwhile (returning from the picker resumes the
     * window, which keeps it busy until that is done) goes to the window now, one at a time: opening one makes the
     * workspace busy again, and whatever is left waits for the next idle moment or for the editor that file mounts.
     */
    fun workspaceIdle() {
        while (handler == null && windowHandler != null && pendingOpens.isNotEmpty() && workspace.acceptsWindowOpen()) {
            val (result, requested) = pendingOpens.removeFirst()
            deliverToWindow(result, requested)
        }
    }

    /** A file the window could not open after all waits for the next idle moment, or for the next editor. */
    fun requeue(result: DocumentOpenResult) { pendingOpens.addLast(result to null) }

    private fun deliverToWindow(result: DocumentOpenResult, requested: WorkspaceEditorScope?) {
        val window = windowHandler ?: return
        if (!workspace.acceptsWindowOpen()) { pendingOpens.addLast(result to requested); return }
        // A picker request made by an editor that has gone since then is not this window's.
        window(if (requested != null && !workspace.matches(requested)) DocumentOpenResult.Cancelled else result)
    }

    fun editorDetached() { editorReady = false }

    fun editorAttached() {
        editorReady = true
        while (editorReady && handler != null && pendingOpens.isNotEmpty()) {
            val (result, requested) = pendingOpens.removeFirst()
            deliver(result, requested)
        }
    }

    override fun commitOpen(reference: DocumentReference, accept: () -> Boolean): Boolean {
        val delivered = deliveredOpen
        deliveredOpen = null
        if (delivered == null || delivered.first != reference || !workspace.matches(delivered.second)) return false
        val previous = pendingReference
        pendingReference = reference
        return try { accept() } finally { pendingReference = previous }
    }

    override fun open(mode: DocumentOpenMode) {
        requestedOpen = workspace.scope()
        native.open(mode)
    }

    override fun reopen(reference: DocumentReference) {
        requestedOpen = workspace.scope()
        native.reopen(reference)
    }

    override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) {
        val scope = workspace.scope()
        if (!workspace.matches(scope)) { onResult(DocumentWriteResult.Cancelled); return }
        write(scope, text, onResult) { callback -> native.create(suggestedName, text, callback) }
    }

    override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) {
        val scope = workspace.scope()
        val link = workspace.activeLink()
        if (!workspace.matches(scope)) { onResult(DocumentWriteResult.Cancelled); return }
        if (workspace.state.readOnly || scope.projectId == null || this.reference != reference ||
            link?.projectId != scope.projectId || link.reference != reference || !supportsEditInPlace ||
            reference.mode != DocumentOpenMode.EDIT_IN_PLACE || !reference.writable) {
            onResult(DocumentWriteResult.Failure(message("workspace_external_link_mismatch")))
            return
        }
        write(scope, text, onResult, previousReference = reference) { callback -> native.save(reference, text, callback) }
    }

    fun saveSessionOriginal(
        prepared: WorkspaceSessionSave, current: () -> Boolean, onResult: (DocumentWriteResult) -> Unit,
    ) {
        val reference = prepared.reference
        if (reference == null || !supportsEditInPlace || reference.mode != DocumentOpenMode.EDIT_IN_PLACE || !reference.writable) {
            onResult(DocumentWriteResult.Failure(message("workspace_external_link_mismatch")))
            return
        }
        write(prepared.scope, prepared.xml, onResult, current, previousReference = reference) { callback ->
            native.save(reference, prepared.xml, callback)
        }
    }

    private fun write(
        scope: WorkspaceEditorScope,
        text: String,
        onResult: (DocumentWriteResult) -> Unit,
        current: () -> Boolean = { workspace.matches(scope) },
        previousReference: DocumentReference? = null,
        start: ((DocumentWriteResult) -> Unit) -> Unit,
    ) {
        workspace.nativeWriteStarted()
        var received = false
        var completed = false
        fun complete(result: DocumentWriteResult) {
            if (completed) return
            completed = true
            try { onResult(if (current()) result else DocumentWriteResult.Cancelled) }
            finally { workspace.nativeWriteFinished() }
        }
        fun failure(error: Exception) = DocumentWriteResult.Failure(error.localizedDescription(message("error_storage_generic")))
        try {
            start callback@{ result ->
                if (received) return@callback
                received = true
                if (result is DocumentWriteResult.Saved) {
                    try { workspace.externalSaved(scope, result.reference, text, previousReference) { complete(result) } }
                    catch (error: Exception) { complete(failure(error)) }
                } else complete(result)
            }
        } catch (error: Exception) { complete(failure(error)) }
    }

    override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) =
        native.share(suggestedName, text, onResult)

    private fun opened(result: DocumentOpenResult) {
        val requested = requestedOpen
        requestedOpen = null
        if (handler == null && windowHandler != null) {
            // In order behind files still waiting; at once when the window is idle.
            if (pendingOpens.isEmpty()) deliverToWindow(result, requested) else pendingOpens.addLast(result to requested)
            return
        }
        // iOS can deliver a queued OS-open synchronously from setOpenHandler, before App's
        // remaining effects install the workspace replacement listener.
        if (!editorReady || handler == null) {
            pendingOpens.addLast(result to requested)
            return
        }
        deliver(result, requested)
    }

    private fun deliver(result: DocumentOpenResult, requested: WorkspaceEditorScope?) {
        if (requested != null && !workspace.matches(requested)) {
            handler?.invoke(DocumentOpenResult.Cancelled)
            return
        }
        pendingReference = (result as? DocumentOpenResult.Opened)?.content?.reference
        pendingReference?.let { deliveredOpen = it to workspace.scope() }
        try { handler?.invoke(result) } finally { pendingReference = null }
    }
}
