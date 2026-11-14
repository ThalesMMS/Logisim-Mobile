package dev.logisim.mobile

import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotEquals as differs
import kotlin.test.assertTrue

/**
 * C139: with no editor composed (the last tab closed), a file opened from another app or the picker opens a new
 * session at once, parsed on the worker; nothing waits for the next editor and nothing is delivered twice.
 */
class WorkspaceWindowOpenRuntimeTest {
    private class Disk(private val uiThread: Long) : WorkspaceCatalogStorage {
        val directory: Path = Files.createTempDirectory("workspace-window-open-")
        private val lock = ReentrantLock()
        override fun <T> withExclusiveAccess(action: () -> T): T {
            differs(uiThread, Thread.currentThread().id, "storage transaction ran on UI")
            return lock.withLock(action)
        }
        override fun read(name: String): String? {
            differs(uiThread, Thread.currentThread().id, "storage read ran on UI")
            return directory.resolve(name).takeIf { Files.exists(it) }?.let { Files.readString(it) }
        }
        override fun listNames(): List<String> = Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.toList() }
        override fun writeAtomically(name: String, contents: String) {
            differs(uiThread, Thread.currentThread().id, "storage write ran on UI")
            Files.writeString(directory.resolve(name), contents)
        }
        fun remove() { Files.list(directory).use { paths -> paths.forEach(Files::delete) }; Files.delete(directory) }
    }
    private class Native : DocumentActions {
        var handler: ((DocumentOpenResult) -> Unit)? = null
        var retained: DocumentReference? = null
        override val currentReference get() = retained
        override fun retain(reference: DocumentReference?) { retained = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) { this.handler = handler }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
    }
    private class Scene(val runtime: CatalogWorkspaceRuntime, val native: Native, val control: EditorSessionControl) {
        private var editorXml = ""
        fun attach() {
            if (control.isAttached) control.detach()
            editorXml = runtime.state.workspace!!.editorXml!!
            control.attach({}, {}, { WorkspaceViewState() }, {}, identity = { 0L },
                exportXml = { editorXml }, documentWritable = { true }, setInteractionEnabled = {})
            runtime.editorAttached(runtime.state.workspace!!.editorMount)
        }
    }
    private fun xml(name: String) = "<project><main name=\"$name\"/><circuit name=\"$name\"/></project>"
    private suspend fun until(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(1) }

    /** Starts a window and closes its only tab, so no editor is composed; [body] runs with the window's own handler. */
    private fun withEditorlessWindow(body: suspend Scene.(delivered: MutableList<DocumentOpenResult>) -> Unit) = runBlocking<Unit> {
        val disk = Disk(Thread.currentThread().id)
        val native = Native()
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(disk, "window-open", control, nativeDocuments = native, deliveryContext = coroutineContext)
        try {
            val scene = Scene(runtime, native, control)
            runtime.start(xml("A"))
            until { runtime.state.workspace != null }
            scene.attach()
            until { !runtime.state.busy }
            val a = runtime.state.workspace!!.editorProjectId!!
            var closed: Boolean? = null
            runtime.closeEditorSession(a, null, save = true, writeOriginal = false) { closed = it }
            until { closed != null }
            assertTrue(closed!!)
            assertTrue(runtime.state.workspace!!.editorClosed)
            control.detach()
            // App is not composed, so no editor handler exists; the window's own handler takes the file, as the host's does.
            val delivered = mutableListOf<DocumentOpenResult>()
            runtime.setWindowOpenHandler { result ->
                delivered += result
                if (result is DocumentOpenResult.Opened) runtime.openWithoutEditor(result)
            }
            scene.body(delivered)
        } finally { runtime.close(); runtime.awaitClosed(); disk.remove() }
    }

    @Test fun aFileOpenedWithNoEditorMountsANewSessionAtOnceAndIsNotReplayed() = withEditorlessWindow { delivered ->
        val a = runtime.state.workspace!!.catalog.projects.single().id
        val reference = DocumentReference("content://adder", "Adder.circ", true, "r1", DocumentOpenMode.EDIT_IN_PLACE)
        native.handler!!(DocumentOpenResult.Opened(DocumentContent(reference, xml("Adder"))))
        until { runtime.state.workspace?.editorClosed == false }
        val workspace = runtime.state.workspace!!
        val adder = workspace.editorProjectId!!
        assertNotEquals(a, adder)
        assertEquals("Adder", workspace.catalog.projects.single { it.id == adder }.name)
        assertEquals("Adder.circ", workspace.sessionFiles[adder], "Edit in place keeps the link to its file")
        assertEquals(emptySet(), workspace.externalDirty, "Opened from the file, the file has it")
        assertEquals(1, delivered.size)

        // The editor returns (App composes): the window handler goes, and the file is not delivered again.
        runtime.setWindowOpenHandler(null)
        val editorDelivered = mutableListOf<DocumentOpenResult>()
        runtime.documents!!.setOpenHandler { editorDelivered += it }
        attach()
        until { !runtime.state.busy }
        assertEquals(emptyList(), editorDelivered)
        assertFalse(runtime.state.workspace!!.editorClosed)
    }

    // Android: returning from the picker resumes the window, which stays busy until that is handled; the result can land first.
    @Test fun aPickerResultThatArrivesWhileTheWindowResumesStillOpens() = withEditorlessWindow { delivered ->
        runtime.documents!!.open(DocumentOpenMode.IMPORT_COPY)
        runtime.foregroundChanged(false)
        runtime.foregroundChanged(true)
        assertTrue(runtime.state.busy)
        val reference = DocumentReference("content://picked", "Picked.circ", false, null, DocumentOpenMode.IMPORT_COPY)
        native.handler!!(DocumentOpenResult.Opened(DocumentContent(reference, xml("Picked"))))
        assertEquals(emptyList(), delivered, "Held while the window is busy, not cancelled")
        until { runtime.state.workspace?.editorClosed == false }
        val workspace = runtime.state.workspace!!
        assertEquals("Picked", workspace.catalog.projects.single { it.id == workspace.editorProjectId }.name)
        assertIs<DocumentOpenResult.Opened>(delivered.single())
    }

    // Two files at once: the first opens here, the second goes to the editor the first one mounts, and nothing is left
    // behind to open by surprise when the last tab closes again.
    @Test fun aSecondFileWaitsForTheEditorTheFirstOneMounts() = withEditorlessWindow { delivered ->
        val first = DocumentReference("content://one", "One.circ", false, null, DocumentOpenMode.IMPORT_COPY)
        val second = DocumentReference("content://two", "Two.circ", false, null, DocumentOpenMode.IMPORT_COPY)
        native.handler!!(DocumentOpenResult.Opened(DocumentContent(first, xml("One"))))
        native.handler!!(DocumentOpenResult.Opened(DocumentContent(second, xml("Two"))))
        until { runtime.state.workspace?.editorClosed == false }
        assertEquals(listOf(first), delivered.map { (it as DocumentOpenResult.Opened).content.reference })
        runtime.setWindowOpenHandler(null)
        val editorDelivered = mutableListOf<DocumentOpenResult>()
        runtime.documents!!.setOpenHandler { editorDelivered += it }
        attach()
        until { editorDelivered.isNotEmpty() }
        assertEquals(listOf(second), editorDelivered.map { (it as DocumentOpenResult.Opened).content.reference })
        assertEquals(1, delivered.size)
    }
}
