package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.WorkspaceAttachment
import dev.logisim.mobile.core.WorkspaceAttachmentReadResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableProjectWorkspaceBridgeTest {
    private val project = CircuitProject(listOf(CircuitDocument("portable")))
    private fun copy() = PortableProjectCopy(project, "Imported project",
        DocumentAsset("source.lsebdl", PORTABLE_PROJECT_MIME_TYPE, byteArrayOf(0, 1, 127, -1)))

    @Test
    fun openingSerializesOffTheCallerAndWaitsForTheNewEditorToBeReady() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val worker = QueuedDispatcher()
        var stored = false
        var reply: ((WorkspacePreparedCopyResult) -> Unit)? = null
        var opened: PortableProjectCopyOpenResult? = null
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { mount, identity -> mount == 3L && identity == 7L },
            openPrepared = { name, xml, attachments, mount, identity, completed ->
                stored = true
                assertEquals("Imported project", name)
                assertEquals("portable", assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project.mainCircuitName)
                val attachment = attachments.getValue(PortableSourceAttachment)
                assertEquals("source.lsebdl", attachment.name)
                assertEquals(PORTABLE_PROJECT_MIME_TYPE, attachment.mimeType)
                assertContentEquals(byteArrayOf(0, 1, 127, -1), attachment.bytes)
                assertEquals(3L, mount)
                assertEquals(7L, identity)
                reply = completed
            }, readAttachment = { _, _, _ -> WorkspaceAttachmentReadResult.Missing }, workerDispatcher = worker)
        try {
            bridge.openCopy(copy(), 7) { opened = it }
            assertFalse(stored)
            assertNull(opened)
            worker.runAll()
            assertTrue(stored)
            assertNull(opened)
            reply!!.invoke(WorkspacePreparedCopyResult.Opened(WorkspaceProjectId("fresh-project"), 4, 0))
            assertEquals(PortableProjectCopyOpenResult.Opened(0), opened)
        } finally { scope.cancel() }
    }

    @Test
    fun switchingProjectsDuringPreparationCannotCommitTheCopy() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val worker = QueuedDispatcher()
        var identity = 7L
        var stored = false
        var opened: PortableProjectCopyOpenResult? = null
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { _, expected -> identity == expected },
            openPrepared = { _, _, _, _, _, _ -> stored = true },
            readAttachment = { _, _, _ -> WorkspaceAttachmentReadResult.Missing }, workerDispatcher = worker)
        try {
            bridge.openCopy(copy(), 7) { opened = it }
            identity = 8
            worker.runAll()
            assertFalse(stored)
            assertEquals(PortableProjectCopyOpenResult.Cancelled, opened)
        } finally { scope.cancel() }
    }

    @Test
    fun closingTheSceneCancelsPreparationWithoutCreatingAWorkspace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val worker = QueuedDispatcher()
        var stored = false
        var opened: PortableProjectCopyOpenResult? = null
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { _, _ -> true }, openPrepared = { _, _, _, _, _, _ -> stored = true },
            readAttachment = { _, _, _ -> WorkspaceAttachmentReadResult.Missing }, workerDispatcher = worker)
        bridge.openCopy(copy(), 7) { opened = it }
        scope.cancel()
        worker.runAll()
        assertFalse(stored)
        assertNull(opened)
    }

    @Test
    fun aPreparationFailureCannotCreateAPartialWorkspace() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var stored = false
        var opened: PortableProjectCopyOpenResult? = null
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { _, _ -> true }, openPrepared = { _, _, _, _, _, _ -> stored = true },
            readAttachment = { _, _, _ -> WorkspaceAttachmentReadResult.Missing },
            workerDispatcher = Dispatchers.Unconfined, serialize = { throw IllegalStateException("serializer failed") })
        try {
            bridge.openCopy(copy(), 7) { opened = it }
            assertFalse(stored)
            assertIs<PortableProjectCopyOpenResult.Failure>(opened)
        } finally { scope.cancel() }
    }

    @Test
    fun sourceLookupIsBoundToTheMountAndIdentityThatRequestedExport() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { _, _ -> true }, openPrepared = { _, _, _, _, _, _ -> },
            readAttachment = { mount, identity, key ->
                assertEquals(3L, mount)
                assertEquals(7L, identity)
                assertEquals("portable-source", key)
                WorkspaceAttachmentReadResult.Success(WorkspaceAttachment("source.lsebdl",
                    PORTABLE_PROJECT_MIME_TYPE, byteArrayOf(12, 34)))
            })
        try {
            val archive = bridge.sourceArchiveForIdentity(7)!!
            assertEquals("source.lsebdl", archive.name)
            assertContentEquals(byteArrayOf(12, 34), archive.bytes)
        } finally { scope.cancel() }
    }

    @Test
    fun anUnavailableRequiredSourceFailsInsteadOfExportingAnUnrelatedGraph() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val notice = message("portable_copy_not_opened")
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { _, _ -> true }, openPrepared = { _, _, _, _, _, _ -> },
            readAttachment = { _, _, _ -> WorkspaceAttachmentReadResult.Failure(notice) })
        try {
            var failed: LocalizedStateException? = null
            try { bridge.sourceArchiveForIdentity(7) } catch (error: LocalizedStateException) { failed = error }
            assertEquals(notice, failed?.text)
        } finally { scope.cancel() }
    }

    @Test
    fun aNormalProjectWithNoSourceAttachmentCanExportItsCurrentDefinitions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = PortableProjectWorkspaceBridge(scope, ComponentCatalog.registry, 3,
            isCurrent = { _, _ -> true }, openPrepared = { _, _, _, _, _, _ -> },
            readAttachment = { _, _, _ -> WorkspaceAttachmentReadResult.Missing })
        try { assertNull(bridge.sourceArchiveForIdentity(7)) } finally { scope.cancel() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
