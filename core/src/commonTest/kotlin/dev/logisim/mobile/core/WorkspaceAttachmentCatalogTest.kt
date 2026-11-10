package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceAttachmentCatalogTest {
    private class Store : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        val writes = mutableListOf<String>()
        var failWrite: String? = null
        var failRead: String? = null
        private var locked = false
        override fun <T> withExclusiveAccess(action: () -> T): T {
            check(!locked) { "Nested storage lock" }
            locked = true
            try { return action() } finally { locked = false }
        }
        override fun read(name: String): String? {
            check(name != failRead) { "Read failed" }
            return files[name]
        }
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) {
            check(name != failWrite) { "Write failed" }
            files[name] = contents
            writes += name
        }
    }

    private var sequence = 0
    private fun repository(store: Store) = WorkspaceCatalogRepository(store, { it.startsWith("<project>") },
        clock = { 123L }, idFactory = { "project-${++sequence}" })
    private fun xml(name: String) = "<project><circuit name=\"$name\"/></project>"
    private fun asset(name: String = "original.zip", content: ByteArray = byteArrayOf(0, 1, -1, 42)) =
        WorkspaceAttachment(name, "application/zip", content)
    private fun WorkspaceMutationResult.document() = assertIs<WorkspaceMutationResult.Success>(this).document
    private fun WorkspaceOpenResult.document() = assertIs<WorkspaceOpenResult.Success>(this).document
    private fun payload(document: WorkspaceDocument) = "project-${document.info.id.value}-${document.info.revision}.workspace"
    private fun source(document: WorkspaceDocument) = document.attachments.getValue("portable-source")

    @Test
    fun allBlobsAreWrittenBeforeThePayloadAndCatalogCommit() {
        val store = Store()
        val first = asset()
        val second = asset("second.zip", byteArrayOf(7, 8, 9))
        val document = repository(store).create("A", xml("main"), attachments = mapOf("portable-source" to first, "other" to second)).document()
        assertEquals(2, document.attachments.size)
        assertEquals(listOf(
            WorkspaceAttachmentBlobs.storageName(document.attachments.getValue("other")),
            WorkspaceAttachmentBlobs.storageName(source(document)), payload(document),
            WorkspaceCatalogRepository.BACKUP_FILE, WorkspaceCatalogRepository.CATALOG_FILE,
        ), store.writes)
        assertEquals(xml("main"), document.xml)
        assertTrue(store.files.getValue(payload(document)).startsWith("LOGISIM-PROJECT\n4\n"))
        assertFalse(document.xml.contains("LOGISIM-WORKSPACE-ATTACHMENT"))
        assertFalse(document.xml.contains("original.zip"))
    }

    @Test
    fun failuresAtEveryWriteLeaveTheOldCatalogAndProjectCommitted() {
        for (stage in listOf("blob", "payload", "backup", "catalog")) {
            val store = Store()
            val repository = repository(store)
            val original = repository.create("Original", xml("committed")).document()
            val before = store.files.getValue(WorkspaceCatalogRepository.CATALOG_FILE)
            val attachment = asset()
            val nextId = "project-${sequence + 1}"
            store.failWrite = when (stage) {
                "blob" -> WorkspaceAttachmentBlobs.storageName(WorkspaceAttachmentBlobs.references(mapOf("portable-source" to attachment)).getValue("portable-source"))
                "payload" -> "project-$nextId-1.workspace"
                "backup" -> WorkspaceCatalogRepository.BACKUP_FILE
                else -> WorkspaceCatalogRepository.CATALOG_FILE
            }
            assertIs<WorkspaceMutationResult.Failure>(repository.create("New", xml("uncommitted"), attachments = mapOf("portable-source" to attachment)), stage)
            assertEquals(before, store.files.getValue(WorkspaceCatalogRepository.CATALOG_FILE), stage)
            assertEquals(listOf(original.info.id), repository.list().projects.map { it.id }, stage)
            assertEquals(xml("committed"), repository(store).open(original.info.id).document().xml, stage)
        }
    }

    @Test
    fun reopeningRestoresExactBytesAndTypedMissingDoesNotNestLocks() {
        val store = Store()
        val bytes = ByteArray(256) { it.toByte() }
        val attachments = mutableMapOf("portable-source" to asset("binary.zip", bytes))
        val document = repository(store).create("Binary", xml("main"), attachments = attachments).document()
        bytes.fill(0)
        attachments.clear()
        val reopened = repository(store).open(document.info.id).document()
        assertEquals(document.attachments, reopened.attachments)
        assertFalse(reopened.readOnly)
        val result = assertIs<WorkspaceAttachmentReadResult.Success>(repository(store).readAttachment(document.info.id, "portable-source"))
        assertEquals("binary.zip", result.attachment.name)
        assertEquals("application/zip", result.attachment.mimeType)
        assertContentEquals(ByteArray(256) { it.toByte() }, result.attachment.bytes)
        assertIs<WorkspaceAttachmentReadResult.Missing>(repository(store).readAttachment(document.info.id, "absent"))
        assertIs<WorkspaceAttachmentReadResult.Missing>(repository(store).readAttachment(WorkspaceProjectId("absent"), "portable-source"))
    }

    @Test
    fun equalXmlAndNamesWithDifferentSourcesRemainIndependent() {
        val store = Store()
        val repository = repository(store)
        val first = repository.create("Same", xml("same"), attachments = mapOf("portable-source" to asset(content = byteArrayOf(1)))).document()
        val second = repository.create("Same", xml("same"), attachments = mapOf("portable-source" to asset(content = byteArrayOf(2)))).document()
        assertNotEquals(source(first).sha256, source(second).sha256)
        assertContentEquals(byteArrayOf(1), assertIs<WorkspaceAttachmentReadResult.Success>(repository.readAttachment(first.info.id, "portable-source")).attachment.bytes)
        assertContentEquals(byteArrayOf(2), assertIs<WorkspaceAttachmentReadResult.Success>(repository.readAttachment(second.info.id, "portable-source")).attachment.bytes)
        assertEquals(first.attachments, repository.open(first.info.id).document().attachments)
    }

    @Test
    fun savesMetadataHistoryAndDuplicationPreserveTheSourceDescriptor() {
        val store = Store()
        val repository = repository(store)
        val original = repository.create("Original", xml("one"), attachments = mapOf("portable-source" to asset())).document()
        val saved = repository.save(original.info.id, 1, xml("two")).document()
        val renamed = repository.rename(original.info.id, 2, "Renamed").document()
        val deleted = repository.delete(original.info.id, 3).document()
        val restored = repository.restore(original.info.id, 4).document()
        for (document in listOf(saved, renamed, deleted, restored)) assertEquals(original.attachments, document.attachments)
        val copy = repository.duplicate(original.info.id, 5).document()
        assertNotEquals(original.info.id, copy.info.id)
        assertEquals(original.attachments, copy.attachments)
        assertEquals(1, store.files.keys.count { it.startsWith("attachment-") })
        assertContentEquals(byteArrayOf(0, 1, -1, 42), assertIs<WorkspaceAttachmentReadResult.Success>(repository.readAttachment(copy.info.id, "portable-source")).attachment.bytes)
        for (revision in 1L..5L) assertEquals(original.attachments,
            assertNotNull(WorkspaceCatalogFormat.readDocument(store.files.getValue("project-${original.info.id.value}-$revision.workspace"))).attachments)
    }

    @Test
    fun missingOrCorruptBlobsKeepTheCurrentXmlReadOnlyInsteadOfUsingHistory() {
        for (corrupt in listOf(false, true)) {
            val store = Store()
            val repository = repository(store)
            val original = repository.create("A", xml("old"), attachments = mapOf("portable-source" to asset())).document()
            val latest = repository.save(original.info.id, 1, xml("current")).document()
            val blobName = WorkspaceAttachmentBlobs.storageName(source(latest))
            if (corrupt) store.files[blobName] = "corrupt source" else store.files.remove(blobName)
            val before = store.files.toMap()
            val reopened = repository.open(original.info.id).document()
            assertTrue(reopened.readOnly)
            assertEquals(xml("current"), reopened.xml)
            assertEquals(2L, reopened.contentRevision)
            assertEquals(latest.attachments, reopened.attachments)
            assertEquals(if (corrupt) "workspace_attachment_corrupt" else "workspace_attachment_missing", reopened.notices.last().key)
            assertIs<WorkspaceAttachmentReadResult.Failure>(repository.readAttachment(original.info.id, "portable-source"))
            assertIs<WorkspaceMutationResult.Failure>(repository.save(original.info.id, 2, xml("replaced")))
            assertIs<WorkspaceMutationResult.Failure>(repository.duplicate(original.info.id, 2))
            assertEquals(before, store.files)
            assertEquals(xml("current"), repository.rename(original.info.id, 2, "Metadata only").document().xml)
            assertEquals(latest.attachments, repository.open(original.info.id).document().attachments)
        }
    }

    @Test
    fun blobReadFailuresPreserveTheXmlAndReportTheStorageFailure() {
        val store = Store()
        val repository = repository(store)
        val document = repository.create("A", xml("main"), attachments = mapOf("portable-source" to asset())).document()
        store.failRead = WorkspaceAttachmentBlobs.storageName(source(document))
        val opened = repository.open(document.info.id).document()
        assertTrue(opened.readOnly)
        assertEquals(document.xml, opened.xml)
        assertEquals("workspace_attachment_access", opened.notices.last().key)
        assertEquals("workspace_attachment_access", assertIs<WorkspaceAttachmentReadResult.Failure>(repository.readAttachment(document.info.id, "portable-source")).notice.key)
    }

    @Test
    fun creatingAgainstAnExistingCorruptBlobNeverOverwritesIt() {
        val store = Store()
        val repository = repository(store)
        val attachment = asset()
        val document = repository.create("A", xml("main"), attachments = mapOf("portable-source" to attachment)).document()
        val blob = WorkspaceAttachmentBlobs.storageName(source(document))
        store.files[blob] = "keep this corrupt original"
        val before = store.files.toMap()
        assertIs<WorkspaceMutationResult.Failure>(repository.create("B", xml("main"), attachments = mapOf("portable-source" to attachment)))
        assertEquals(before, store.files)
    }

    @Test
    fun descriptorChangesAreInsideTheChecksummedPayload() {
        val info = WorkspaceProjectInfo(WorkspaceProjectId("same-id"), "Same", 1, 123, 123)
        val a = WorkspaceAttachmentBlobs.references(mapOf("portable-source" to asset(content = byteArrayOf(1))))
        val b = WorkspaceAttachmentBlobs.references(mapOf("portable-source" to asset(content = byteArrayOf(2))))
        val first = WorkspaceCatalogFormat.writeDocument(WorkspaceDocument(info, xml("same"), attachments = a))
        val second = WorkspaceCatalogFormat.writeDocument(WorkspaceDocument(info, xml("same"), attachments = b))
        assertNotEquals(first.substringBefore("\n\n"), second.substringBefore("\n\n"))
        assertNull(WorkspaceCatalogFormat.readDocument(first.replace(a.getValue("portable-source").sha256, b.getValue("portable-source").sha256)))
    }

    @Test
    fun sessionCreateAndConflictCopyRetainTheExactOriginalArchive() {
        val store = Store()
        val repository = repository(store)
        val session = WorkspaceSession(repository, {})
        assertTrue(session.create("Original", xml("original"), mapOf("portable-source" to asset())))
        val original = assertNotNull(session.state.document)
        repository.save(original.info.id, 1, xml("remote")).document()
        session.edit(xml("local"))
        assertIs<WorkspaceMutationResult.Conflict>(session.flush())
        assertTrue(session.saveLocalAsCopy("Local copy"))
        val copy = assertNotNull(session.state.document)
        assertNotEquals(original.info.id, copy.info.id)
        assertEquals(xml("local"), copy.xml)
        assertEquals(original.attachments, copy.attachments)
        assertContentEquals(byteArrayOf(0, 1, -1, 42),
            assertIs<WorkspaceAttachmentReadResult.Success>(repository.readAttachment(copy.info.id, "portable-source")).attachment.bytes)
        assertEquals(xml("remote"), repository.open(original.info.id).document().xml)
    }

    @Test
    fun copyingALocalBufferCannotSilentlyDropAMissingArchive() {
        val store = Store()
        val repository = repository(store)
        val session = WorkspaceSession(repository, {})
        assertTrue(session.create("Original", xml("original"), mapOf("portable-source" to asset())))
        val original = assertNotNull(session.state.document)
        session.edit(xml("local"))
        store.files.remove(WorkspaceAttachmentBlobs.storageName(source(original)))
        val before = store.files.toMap()
        assertFalse(session.saveLocalAsCopy("Unsafe copy"))
        assertEquals(original.info.id, session.state.document?.info?.id)
        assertEquals(xml("local"), session.state.xml)
        assertEquals(before, store.files)
        assertEquals(1, repository.list().projects.size)
    }

    @Test
    fun copyingAnExplicitDifferentDraftDoesNotInheritTheMountedSource() {
        val store = Store()
        val repository = repository(store)
        val session = WorkspaceSession(repository, {})
        assertTrue(session.create("Mounted A", xml("A"), mapOf("portable-source" to asset("A.zip"))))
        val original = assertNotNull(session.state.document)
        assertTrue(session.saveLocalAsCopy("Draft B", xml("B"), WorkspaceViewState()))
        val copy = assertNotNull(session.state.document)
        assertEquals(xml("B"), copy.xml)
        assertTrue(copy.attachments.isEmpty())
        assertIs<WorkspaceAttachmentReadResult.Missing>(repository.readAttachment(copy.info.id, "portable-source"))
        assertEquals("A.zip", assertIs<WorkspaceAttachmentReadResult.Success>(repository.readAttachment(original.info.id, "portable-source")).attachment.name)
    }

    @Test
    fun emptyAttachmentsWriteVersionFourWithoutReclassifyingLegacyViewports() {
        val document = WorkspaceDocument(WorkspaceProjectInfo(WorkspaceProjectId("legacy"), "Legacy", 1, 123, 123), xml("main"))
        val raw = WorkspaceCatalogFormat.writeDocument(document)
        assertTrue(raw.startsWith("LOGISIM-PROJECT\n4\n"))
        val parsed = assertNotNull(WorkspaceCatalogFormat.readDocument(raw))
        assertEquals(document, parsed)
        assertEquals(WorkspaceViewportKeyFormat.LEGACY, parsed.viewState.viewportKeyFormat)
        assertTrue(parsed.attachments.isEmpty())
        assertTrue(WorkspaceCatalogFormat.usesUnsupportedProjectVersion("LOGISIM-PROJECT\n5\nfuture"))
    }
}
