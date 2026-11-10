package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Drives the same native → catalog → editor loader chain used by the Android host. */
class NativeImportLifecycleTest {
    private class MemoryDisk : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = synchronized(files) { action() }
        override fun read(name: String) = synchronized(files) { files[name] }
        override fun listNames() = synchronized(files) { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) { synchronized(files) { files[name] = contents } }
    }

    private class Native : DocumentActions {
        private var handler: ((DocumentOpenResult) -> Unit)? = null
        private val queued = ArrayDeque<DocumentOpenResult>()
        override val currentReference: DocumentReference? get() = null
        override fun retain(reference: DocumentReference?) = Unit
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) {
            this.handler = handler
            if (handler != null) while (queued.isNotEmpty()) handler(queued.removeFirst())
        }
        fun deliver(xml: String) {
            val reference = DocumentReference("content://fixture/writable.circ", "writable.circ", false,
                "revision-${xml.length}", DocumentOpenMode.IMPORT_COPY)
            val result = DocumentOpenResult.Opened(DocumentContent(reference, xml))
            handler?.invoke(result) ?: queued.addLast(result)
        }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
    }

    @Test fun twoDifferentRejectedFilesAtTheSameUriSurviveColdAttachmentAndForegroundTransitions() = runBlocking<Unit> {
        val native = Native()
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(MemoryDisk(), "external-open", control,
            nativeDocuments = native, deliveryContext = coroutineContext)
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var loader: EditorImportLoader? = null
        var documents: EditorDocuments? = null
        try {
            runtime.start("<project><main name=\"kept\"/><circuit name=\"kept\"/></project>")
            runtime.foregroundChanged(false)
            native.deliver("<broken/>") // OS result can precede Compose/editor attachment.
            runtime.foregroundChanged(true)
            until { runtime.state.workspace != null }
            val workspace = runtime.state.workspace!!
            val state = EditorState(workspace.editorXml)
            val original = state.exportXml()
            val actions = runtime.documents!!
            loader = EditorImportLoader(scope, ComponentCatalog.registry,
                { state.documentIdentity to state.revision }, { state.interactionsEnabled },
                { state.reportError(it.toString()) }, workerDispatcher = Dispatchers.Default)
            val activeLoader = loader
            documents = EditorDocuments(state, actions) { content, current, complete ->
                if (!activeLoader.open(content.text, isRequestCurrent = current,
                        commit = { result -> actions.commitOpen(content.reference) { state.acceptImport(result) } },
                        onComplete = complete)) complete(false)
            }
            documents.attach()
            control.attach({}, {}, { WorkspaceViewState() }, {},
                identity = { state.documentIdentity }, exportXml = state::exportXml,
                setInteractionEnabled = state::setInteractionEnabled)
            runtime.editorAttached(workspace.editorMount)
            until { state.importErrorText != null }
            assertEquals(original, state.exportXml())
            state.dismissImportError()

            runtime.foregroundChanged(false)
            native.deliver("<project version=\"1.0\"><lib name=\"8\" desc=\"file#missing-library.circ\"/>" +
                "<main name=\"main\"/><circuit name=\"main\"><comp lib=\"8\" loc=\"(100,100)\" name=\"UnavailableGate\"/></circuit></project>")
            runtime.foregroundChanged(true)
            until { state.importErrorText != null }
            assertTrue(state.importError.orEmpty().contains("UnavailableGate"), state.importError)
            assertEquals(original, state.exportXml())
        } finally {
            documents?.detach(); loader?.close(); scope.cancel()
            runtime.close(); runtime.awaitClosed()
        }
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
    }
}
