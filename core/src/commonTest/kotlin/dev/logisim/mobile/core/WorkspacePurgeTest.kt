package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Permanent deletion from the trash, project names, and what "Edited" means for the catalog. */
class WorkspacePurgeTest {
    private class Store(val deletes: Boolean = true) : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun read(name: String): String? = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
        override val supportsDelete: Boolean get() = deletes
        override fun delete(name: String) { files.remove(name) }
    }

    private var sequence = 0
    private var time = 1_000L
    private fun repository(store: Store) = WorkspaceCatalogRepository(store, { it.startsWith("<project>") },
        clock = { time }, idFactory = { "p-${++sequence}" })
    private fun xml(name: String) = "<project><circuit name=\"$name\"/></project>"
    private fun WorkspaceMutationResult.document() = assertIs<WorkspaceMutationResult.Success>(this).document
    private fun payloads(store: Store, id: WorkspaceProjectId) =
        store.files.keys.filter { Regex("project-${Regex.escape(id.value)}-[0-9]+\\.workspace").matches(it) }

    @Test
    fun aTrashedProjectIsPurgedWithEveryRevisionAndTheCatalogBackup() {
        val store = Store()
        val ids = ArrayDeque(listOf("p-1", "p-1-1"))
        val repository = WorkspaceCatalogRepository(store, { it.startsWith("<project>") }, clock = { time }, idFactory = { ids.removeFirst() })
        var doomed = repository.create("Doomed", xml("a")).document()
        val kept = repository.create("Kept", xml("kept")).document()
        assertEquals("p-1-1", kept.info.id.value)
        repeat(5) { doomed = repository.save(doomed.info.id, doomed.info.revision, xml("a$it")).document() }
        // An ID that extends the doomed one must keep its revisions.
        assertEquals(6, payloads(store, doomed.info.id).size)
        doomed = repository.delete(doomed.info.id, doomed.info.revision).document()

        val purged = repository.purge(doomed.info.id, doomed.info.revision)
        assertIs<WorkspaceMutationResult.Success>(purged)
        assertEquals(emptyList(), payloads(store, doomed.info.id))
        assertEquals(listOf("Kept"), repository.list(includeDeleted = true).projects.map { it.name })
        assertTrue(payloads(store, kept.info.id).isNotEmpty())
        val backup = WorkspaceCatalogFormat.readCatalog(store.files.getValue(WorkspaceCatalogRepository.BACKUP_FILE))
        assertEquals(listOf(kept.info.id), assertNotNull(backup).projects.map { it.id }, "A recovery from the backup cannot list it")
    }

    @Test
    fun onlyATrashedProjectAtItsRevisionCanBePurged() {
        val store = Store(); val repository = repository(store)
        val open = repository.create("Open", xml("open")).document()
        assertIs<WorkspaceMutationResult.Conflict>(repository.purge(open.info.id, open.info.revision), "Not in the trash")
        val trashed = repository.delete(open.info.id, open.info.revision).document()
        assertIs<WorkspaceMutationResult.Conflict>(repository.purge(open.info.id, open.info.revision), "Stale revision")
        assertIs<WorkspaceMutationResult.Missing>(repository.purge(WorkspaceProjectId("nobody"), 1))
        assertTrue(payloads(store, open.info.id).isNotEmpty())
        val withoutDelete = Store(deletes = false).also { it.files.putAll(store.files) }
        assertIs<WorkspaceMutationResult.Failure>(repository(withoutDelete).purge(open.info.id, trashed.info.revision),
            "A store that cannot delete refuses before changing the catalog")
        assertTrue(repository(withoutDelete).list(includeDeleted = true).projects.any { it.id == open.info.id })
    }

    @Test
    fun attachmentsSharedWithADuplicateSurviveThePurge() {
        val store = Store(); val repository = repository(store)
        val attachment = WorkspaceAttachment("source.zip", "application/zip", byteArrayOf(1, 2, 3))
        val original = repository.create("Original", xml("o"), attachments = mapOf("portable-source" to attachment)).document()
        val blob = WorkspaceAttachmentBlobs.storageName(original.attachments.getValue("portable-source"))
        val copy = repository.duplicate(original.info.id, original.info.revision).document()
        val trashed = repository.delete(original.info.id, original.info.revision).document()
        assertIs<WorkspaceMutationResult.Success>(repository.purge(original.info.id, trashed.info.revision))
        assertNotNull(store.files[blob], "The duplicate still references the blob")
        assertIs<WorkspaceAttachmentReadResult.Success>(repository.readAttachment(copy.info.id, "portable-source"))

        val trashedCopy = repository.delete(copy.info.id, copy.info.revision).document()
        assertIs<WorkspaceMutationResult.Success>(repository.purge(copy.info.id, trashedCopy.info.revision))
        assertNull(store.files[blob], "No revision references it any more")
    }

    @Test
    fun viewOnlySavesKeepTheEditedTime() {
        val store = Store(); val repository = repository(store)
        var document = repository.create("A", xml("a")).document()
        time = 5_000L
        document = repository.save(document.info.id, document.info.revision, xml("a"), WorkspaceViewState(activeCircuitId = "a")).document()
        assertEquals(1_000L, document.info.updatedAtMillis, "Panning or selecting is not an edit")
        time = 9_000L
        document = repository.save(document.info.id, document.info.revision, xml("b"), document.viewState).document()
        assertEquals(9_000L, document.info.updatedAtMillis)
        time = 12_000L
        document = repository.rename(document.info.id, document.info.revision, "B").document()
        assertEquals(12_000L, document.info.updatedAtMillis)
    }

    @Test
    fun repeatedNamesAreNumbered() {
        assertEquals("Untitled", uniqueProjectName("Untitled", listOf("main")))
        assertEquals("Untitled 2", uniqueProjectName("Untitled", listOf("Untitled")))
        assertEquals("Untitled 3", uniqueProjectName(" Untitled ", listOf("untitled", "Untitled 2")))
        assertEquals("Sem título 2", uniqueProjectName("Sem título", listOf("Sem título", "Untitled")))
    }
}
