package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceUnavailableProjectTest {
    private class Store : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T) = action()
        override fun read(name: String) = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) { files[name] = contents }
    }
    private val availableProvider = ComponentCatalog.registry.withDefinitions(listOf(
        ComponentCatalog.registry.requireDefinition(ComponentKind.NOT).copy(library = "#RemovedProvider", fileName = "Old NOT")))
    private val original = """<project source="5.1.0-dev" version="1.0"><lib name="old" desc="#RemovedProvider"/><main name="Original"/><circuit name="Original"><comp lib="old" name="Old NOT" loc="(100,100)"/></circuit></project>"""
    private val empty = "<project><main name=\"Original\"/><circuit name=\"Original\"/></project>"
    private var ids = 0
    private fun repository(store: Store, registry: ComponentRegistry) = WorkspaceCatalogRepository(store,
        validate = { CircCodec.readProject(it, registry) is CircProjectImportResult.Success }, idFactory = { "stored-${++ids}" })
    private fun WorkspaceMutationResult.document() = assertIs<WorkspaceMutationResult.Success>(this).document
    private fun WorkspaceOpenResult.document() = assertIs<WorkspaceOpenResult.Success>(this).document

    @Test fun unavailableProviderKeepsLatestRawRevisionAndBecomesEditableWhenProviderReturns() {
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(original, availableProvider))
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(original))
        val store = Store(); val old = repository(store, availableProvider)
        val first = old.create("Original", empty).document()
        val latest = old.save(first.info.id, first.info.revision, original).document()
        val before = store.files.toMap()
        val current = repository(store, ComponentCatalog.registry)
        val blocked = current.open(first.info.id).document()
        assertEquals(original, blocked.xml)
        assertEquals(latest.info.id, blocked.info.id)
        assertEquals(2L, blocked.contentRevision)
        assertTrue(blocked.readOnly)
        assertTrue(blocked.notices.isNotEmpty())
        val session = WorkspaceSession(current, {})
        assertTrue(session.open(blocked.info.id))
        assertFalse(session.state.mayRun)
        session.edit(empty)
        session.pauseAndFlush()
        assertEquals(original, session.state.xml)
        assertFalse(session.state.dirty)
        assertIs<WorkspaceMutationResult.Failure>(current.save(blocked.info.id, blocked.info.revision, empty))
        assertEquals(before, store.files)
        val restored = repository(store, availableProvider).open(blocked.info.id).document()
        assertFalse(restored.readOnly)
        assertEquals(original, restored.xml)
        assertEquals(blocked.info.id, restored.info.id)
        assertEquals(2L, restored.info.revision)
        assertEquals(before, store.files)
    }

    @Test fun legacyCheckpointMigratesRawOnceWithoutReplacingItWithBackupOrDemo() {
        val store = Store()
        val old = WorkspaceRepository(store) { CircCodec.readProject(it, availableProvider) is CircProjectImportResult.Success }
        assertIs<WorkspaceSaveResult.Success>(old.save(empty))
        assertIs<WorkspaceSaveResult.Success>(old.save(original))
        val originalCheckpoint = store.files.getValue(WorkspaceRepository.CURRENT_FILE)
        val currentCheckpoint = WorkspaceRepository(store) { CircCodec.readProject(it) is CircProjectImportResult.Success }
        val blocked = currentCheckpoint.load()
        assertTrue(blocked.readOnly)
        assertEquals(WorkspaceLoadSource.CURRENT, blocked.source)
        assertEquals(original, blocked.xml)
        val current = repository(store, ComponentCatalog.registry)
        val loaded = current.load()
        val id = loaded.projects.single().id
        assertEquals(original, current.open(id).document().xml)
        assertTrue(current.open(id).document().readOnly)
        assertEquals(originalCheckpoint, store.files[WorkspaceRepository.CURRENT_FILE])
        val before = store.files.toMap()
        assertIs<WorkspaceSaveResult.Failure>(currentCheckpoint.save(empty))
        assertEquals(id, current.load().projects.single().id)
        val reopened = repository(store, availableProvider).open(id).document()
        assertFalse(reopened.readOnly)
        assertEquals(1L, reopened.info.revision)
        assertEquals(original, reopened.xml)
        assertEquals(before, store.files)
    }

    @Test fun rejectedLegacyPreferenceIsArchivedReadOnlyAndOriginalCanBeDuplicated() {
        val store = Store(); val current = repository(store, ComponentCatalog.registry)
        val id = current.load(original).projects.single().id
        val document = current.open(id).document()
        assertTrue(document.readOnly)
        assertEquals(original, document.xml)
        val duplicate = current.duplicate(id, document.info.revision, "Original copy").document()
        assertTrue(duplicate.readOnly)
        assertEquals(original, duplicate.xml)
        assertEquals(original, current.open(id).document().xml)
        assertEquals(1L, current.open(id).document().info.revision)
        assertEquals(2, current.list().projects.size)
    }
}
