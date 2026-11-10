package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceCatalogTest {
    private class Store : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        var failWrite: String? = null
        override fun <T> withExclusiveAccess(action: () -> T) = action()
        override fun read(name: String) = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) {
            check(name != failWrite) { "interrupted write" }
            files[name] = contents
        }
    }
    private var nextId = 0
    private fun repository(store: Store) = WorkspaceCatalogRepository(store,
        validate = { CircCodec.readProject(it) is CircProjectImportResult.Success },
        clock = { 123L }, idFactory = { "id-${++nextId}" })
    private fun xml(name: String) = "<project><main name=\"$name\"/><circuit name=\"$name\"/></project>"
    private fun WorkspaceMutationResult.document() = assertIs<WorkspaceMutationResult.Success>(this).document
    private fun WorkspaceOpenResult.document() = assertIs<WorkspaceOpenResult.Success>(this).document

    @Test fun duplicateNamesNeverAddressTheWrongProject() {
        val store = Store(); val repository = repository(store)
        val first = repository.create("Mesmo nome", xml("first")).document()
        val second = repository.create("Mesmo nome", xml("second")).document()
        assertTrue(first.info.id != second.info.id)
        repository.rename(first.info.id, 1, "Renomeado").document()
        assertEquals("Mesmo nome", repository.open(second.info.id).document().info.name)
        assertEquals(listOf("Renomeado", "Mesmo nome"), repository.list().projects.map { it.name })
    }

    @Test fun duplicateHasIndependentIdRevisionAndContents() {
        val store = Store(); val repository = repository(store)
        val original = repository.create("Original", xml("main")).document()
        val copy = repository.duplicate(original.info.id, 1).document()
        assertTrue(copy.info.id != original.info.id)
        assertEquals(1L, copy.info.revision)
        repository.save(copy.info.id, 1, xml("copy")).document()
        assertEquals(xml("main"), repository.open(original.info.id).document().xml)
    }

    @Test fun staleSaveDoesNotOverwriteAndDeleteDoesNotResurrect() {
        val store = Store(); val firstWindow = repository(store); val otherWindow = repository(store)
        val project = firstWindow.create("A", xml("a")).document()
        firstWindow.save(project.info.id, 1, xml("first")).document()
        val before = store.files.toMap()
        val conflict = assertIs<WorkspaceMutationResult.Conflict>(otherWindow.save(project.info.id, 1, xml("other")))
        assertEquals(2L, conflict.current.info.revision)
        assertEquals(xml("first"), conflict.current.xml)
        assertEquals(before, store.files)
        val deleted = firstWindow.delete(project.info.id, 2).document()
        assertTrue(deleted.info.deleted)
        assertIs<WorkspaceMutationResult.Missing>(otherWindow.save(project.info.id, 2, xml("old")))
        assertTrue(firstWindow.list().projects.isEmpty())
        val restored = firstWindow.restore(project.info.id, 3).document()
        assertFalse(restored.info.deleted)
        assertEquals(xml("first"), restored.xml)
    }

    @Test fun failedCatalogCommitNeverOpensItsOrphanPayload() {
        val store = Store(); val repository = repository(store)
        val project = repository.create("A", xml("committed")).document()
        store.failWrite = WorkspaceCatalogRepository.CATALOG_FILE
        assertIs<WorkspaceMutationResult.Failure>(repository.save(project.info.id, 1, xml("orphan")))
        assertEquals(xml("committed"), repository(store).open(project.info.id).document().xml)
        store.failWrite = null
        val third = repository.save(project.info.id, 1, xml("third")).document()
        assertEquals(3L, third.info.revision)
        store.files["project-${project.info.id.value}-3.workspace"] = "broken"
        val recovered = repository(store).open(project.info.id).document()
        assertEquals(xml("committed"), recovered.xml)
        assertEquals(1L, recovered.contentRevision)
        assertTrue(recovered.diagnostics.isNotEmpty())
    }

    @Test fun payloadCorruptionFollowsOnlyCommittedHistory() {
        val store = Store(); val repository = repository(store)
        val first = repository.create("A", xml("one")).document()
        repository.save(first.info.id, 1, xml("two")).document()
        repository.save(first.info.id, 2, xml("three")).document()
        store.files["project-${first.info.id.value}-3.workspace"] = "broken"
        store.files["project-${first.info.id.value}-2.workspace"] = "broken"
        val recovered = repository(store).open(first.info.id).document()
        assertEquals(1L, recovered.contentRevision)
        assertEquals(xml("one"), recovered.xml)
        val saved = repository.save(first.info.id, 3, recovered.xml).document()
        assertEquals(4L, saved.info.revision)
        assertEquals(4L, saved.contentRevision)
    }

    @Test fun corruptCatalogRestoresPreviousAndKeepsOriginalBytes() {
        val store = Store(); val repository = repository(store)
        val project = repository.create("A", xml("one")).document()
        repository.save(project.info.id, 1, xml("two")).document()
        store.files[WorkspaceCatalogRepository.CATALOG_FILE] = "damaged current"
        assertEquals(xml("one"), repository(store).open(project.info.id).document().xml)
        assertTrue(repository.list().diagnostics.isNotEmpty())
        repository.save(project.info.id, 1, xml("recovered")).document()
        assertTrue(store.files.values.contains("damaged current"))
        assertEquals(xml("recovered"), repository(store).open(project.info.id).document().xml)
    }

    @Test fun futureCatalogIsReadOnlyAndInvalidXmlDoesNotChangeFiles() {
        val store = Store(); val repository = repository(store)
        repository.create("A", xml("a")).document()
        val before = store.files.toMap()
        assertIs<WorkspaceMutationResult.Failure>(repository.create("B", "not xml"))
        assertEquals(before, store.files)
        store.files[WorkspaceCatalogRepository.CATALOG_FILE] = "LOGISIM-CATALOG\n99\nfuture"
        val future = store.files.toMap()
        assertTrue(repository.list().readOnly)
        assertIs<WorkspaceMutationResult.Failure>(repository.create("B", xml("b")))
        assertEquals(future, store.files)
    }

    @Test fun anOversizedViewIsRejectedBeforeConfirmingAnUnreadableRevision() {
        val store = Store(); val repository = repository(store)
        val project = repository.create("A", xml("a")).document()
        val before = store.files.toMap()
        val oversized = WorkspaceViewState(selectedComponentIds = (0..100_000).map { "component-$it" }.toSet())
        assertIs<WorkspaceMutationResult.Failure>(repository.save(project.info.id, 1, project.xml, oversized))
        assertEquals(before, store.files)
        assertEquals(1L, repository.open(project.info.id).document().info.revision)
    }

    @Test fun aFuturePayloadIsNotReplacedByAnOlderReadableRevision() {
        val store = Store(); val repository = repository(store)
        val first = repository.create("A", xml("old")).document()
        repository.save(first.info.id, 1, xml("new")).document()
        store.files["project-${first.info.id.value}-2.workspace"] = "LOGISIM-PROJECT\n5\nfuture payload"
        val before = store.files.toMap()
        assertIs<WorkspaceOpenResult.Failure>(repository.open(first.info.id))
        assertIs<WorkspaceMutationResult.Failure>(repository.save(first.info.id, 2, xml("older app")))
        assertEquals(before, store.files)
    }

    @Test fun anUnknownViewportFormatCannotRollbackToWritableHistory() {
        val store = Store(); val repository = repository(store)
        val first = repository.create("A", xml("old")).document()
        val latest = repository.save(first.info.id, 1, xml("current")).document()
        val name = "project-${latest.info.id.value}-${latest.info.revision}.workspace"
        val body = store.files.getValue(name).substringAfter("\n\n").replaceFirst("6:LEGACY", "7:UNKNOWN")
        val unknown = "LOGISIM-PROJECT\n4\n${WorkspaceCatalogFormat.checksum(body)}\n\n$body"
        store.files[name] = unknown
        val before = store.files.toMap()

        assertTrue(WorkspaceCatalogFormat.usesUnsupportedProjectVersion(unknown))
        assertIs<WorkspaceOpenResult.Failure>(repository.open(first.info.id))
        assertIs<WorkspaceMutationResult.Failure>(repository.save(first.info.id, latest.info.revision, xml("must not overwrite")))
        assertEquals(before, store.files)
    }

    @Test fun legacyMigrationIsIdempotentAndPreservesSource() {
        val store = Store(); val legacy = xml("legacy")
        val repository = repository(store)
        val migrated = repository.load(legacy)
        assertEquals(1, migrated.projects.size)
        assertEquals(legacy, repository.open(migrated.projects.single().id).document().xml)
        assertEquals(1, repository(store).load(legacy).projects.size)
        assertTrue(store.files.containsKey(WorkspaceRepository.CURRENT_FILE))
        repository.delete(migrated.projects.single().id, 1).document()
        assertTrue(repository(store).load(legacy).projects.isEmpty())
    }

    @Test fun activeProjectAndViewStateSurviveReopening() {
        val store = Store(); val repository = repository(store)
        val first = repository.create("A", xml("a")).document()
        val second = repository.create("B", xml("b")).document()
        val view = WorkspaceViewState("a", setOf("gate-2"), mapOf("a" to WorkspaceViewport(1.5f, -25f, 30f)))
        repository.save(first.info.id, 1, first.xml, view).document()
        repository.selectActive(first.info.id)
        val reopened = repository(store)
        assertEquals(first.info.id, reopened.list().activeProjectId)
        assertEquals(view, reopened.open(first.info.id).document().viewState)
        assertEquals(1L, reopened.open(second.info.id).document().info.revision)
    }

    @Test fun interruptedMigrationCanBeRetriedWithoutDuplicatingTheProject() {
        val store = Store(); val legacy = xml("legacy")
        store.failWrite = WorkspaceCatalogRepository.CATALOG_FILE
        assertTrue(repository(store).load(legacy).diagnostics.isNotEmpty())
        store.failWrite = null
        val loaded = repository(store).load(legacy)
        assertEquals(1, loaded.projects.size)
        assertEquals(1, repository(store).load(legacy).projects.size)
        assertEquals(legacy, repository(store).open(loaded.projects.single().id).document().xml)
    }

    @Test fun invalidIdentifiersCannotBecomeFilesystemPaths() {
        val store = Store()
        val invalid = WorkspaceCatalogRepository(store, { true }, { 0L }, { "../outside" })
        assertIs<WorkspaceMutationResult.Failure>(invalid.create("A", xml("a")))
        assertTrue(store.files.isEmpty())
    }
}
