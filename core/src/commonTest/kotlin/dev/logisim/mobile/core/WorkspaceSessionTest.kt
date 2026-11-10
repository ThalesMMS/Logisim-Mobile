package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceSessionTest {
    private class Storage : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        var fail = false
        override fun <T> withExclusiveAccess(action: () -> T) = action()
        override fun read(name: String) = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) { check(!fail); files[name] = contents }
    }
    private val storage = Storage()
    private var nextId = 0
    private val repository = WorkspaceCatalogRepository(storage,
        { CircCodec.readProject(it) is CircProjectImportResult.Success }, { 10L }, { "project-${++nextId}" })
    private var pauses = 0
    private val session = WorkspaceSession(repository, { pauses++ })
    private fun xml(name: String) = "<project><main name=\"$name\"/><circuit name=\"$name\"/></project>"

    @Test fun backgroundConfirmsRevisionAndForegroundNeverResumesTheRun() {
        assertTrue(session.create("A", xml("one")))
        session.edit(xml("two"))
        assertEquals(1L, session.state.confirmedRevision)
        val beforePause = pauses
        session.foregroundChanged(false)
        assertEquals(beforePause + 1, pauses)
        assertEquals(2L, session.state.confirmedRevision)
        assertFalse(session.state.dirty)
        assertFalse(session.state.mayRun)
        session.foregroundChanged(true)
        assertEquals(beforePause + 1, pauses)
        assertTrue(session.state.mayRun)
        assertEquals(xml("two"), session.state.xml)
    }

    @Test fun failedBackgroundSaveKeepsLocalEditAndConfirmedRevision() {
        session.create("A", xml("one"))
        session.edit(xml("two"))
        storage.fail = true
        session.foregroundChanged(false)
        assertEquals(WorkspaceSaveStatus.FAILED, session.state.saveStatus)
        assertEquals(1L, session.state.confirmedRevision)
        assertEquals(xml("two"), session.state.xml)
        assertNotNull(session.state.message)
        storage.fail = false
        session.foregroundChanged(true)
        assertTrue(session.state.dirty)
        assertIs<WorkspaceMutationResult.Success>(session.flush())
        assertEquals(2L, session.state.confirmedRevision)
    }

    @Test fun lateEditsAreFlushedWhenAnAlreadyPausedSessionIsDisposed() {
        session.create("A", xml("one"))
        session.foregroundChanged(false)
        session.edit(xml("late"))
        session.pauseAndFlush()
        assertEquals(2L, session.state.confirmedRevision)
        assertFalse(session.state.dirty)
        assertFalse(session.state.foreground)
        assertEquals(xml("late"), session.state.xml)
    }

    @Test fun conflictingWindowNeverOverwritesLocalBufferAndCanSaveACopy() {
        session.create("A", xml("one"))
        val id = session.state.document!!.info.id
        repository.save(id, 1, xml("other"))
        session.edit(xml("local"))
        assertIs<WorkspaceMutationResult.Conflict>(session.flush())
        assertEquals(xml("local"), session.state.xml)
        assertFalse(session.state.mayRun)
        session.edit(xml("local-again"))
        assertFalse(session.state.mayRun)
        assertTrue(session.saveLocalAsCopy("My copy"))
        assertTrue(session.state.document!!.info.id != id)
        assertEquals(xml("local-again"), session.state.xml)
        assertEquals(xml("other"), assertIs<WorkspaceOpenResult.Success>(repository.open(id)).document.xml)
    }

    @Test fun switchFlushesTheOldProjectBeforeOpeningTheNewOne() {
        session.create("A", xml("a"))
        val a = session.state.document!!.info.id
        val b = assertIs<WorkspaceMutationResult.Success>(repository.create("B", xml("b"))).document.info.id
        session.edit(xml("edited-a"))
        assertTrue(session.open(b))
        assertEquals(xml("b"), session.state.xml)
        assertEquals(xml("edited-a"), assertIs<WorkspaceOpenResult.Success>(repository.open(a)).document.xml)
    }

    @Test fun failedFlushBlocksAProjectSwitchWithoutLosingTheBuffer() {
        session.create("A", xml("a"))
        val a = session.state.document!!.info.id
        val b = assertIs<WorkspaceMutationResult.Success>(repository.create("B", xml("b"))).document.info.id
        session.edit(xml("edited-a"))
        storage.fail = true
        assertFalse(session.open(b))
        assertEquals(a, session.state.document!!.info.id)
        assertEquals(xml("edited-a"), session.state.xml)
    }

    @Test fun viewRestorationDropsRemovedComponentsAndUnknownCircuits() {
        val document = """<project><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Pin" loc="(0,0)" logisim-mobile-id="import-1"/></circuit></project>"""
        session.create("A", document)
        session.edit(document, WorkspaceViewState("main", setOf("import-1", "removed"),
            mapOf("main" to WorkspaceViewport(0.5f, 20f, 30f), "missing" to WorkspaceViewport(2f, 0f, 0f))))
        session.flush()
        val id = session.state.document!!.info.id
        val reopened = WorkspaceSession(repository, {})
        reopened.open(id)
        assertEquals(setOf("import-1"), reopened.state.viewState.selectedComponentIds)
        assertEquals(setOf(workspaceNavigationKey("main", emptyList())), reopened.state.viewState.viewports.keys)
        assertEquals(0.5f, reopened.state.viewState.viewports.getValue(workspaceNavigationKey("main", emptyList())).scale)
    }

    @Test fun foregroundRefreshPreservesOwnViewportAndReloadsExternalEdits() {
        session.create("A", xml("main"))
        session.edit(xml("main"), WorkspaceViewState("main", viewports = mapOf("main" to WorkspaceViewport(2f, 5f, 6f))))
        session.flush()
        val id = session.state.document!!.info.id
        session.foregroundChanged(false)
        repository.save(id, 2, xml("main"), WorkspaceViewState("main", viewports = mapOf("main" to WorkspaceViewport(3f, 1f, 2f))))
        session.foregroundChanged(true)
        assertEquals(3L, session.state.confirmedRevision)
        assertEquals(2f, session.state.viewState.viewports.getValue(workspaceNavigationKey("main", emptyList())).scale)
    }

    @Test fun storedLegacyRoutesResetWithANoticeWhileLiveEditsStayValid() {
        val xml = """<project><main name="main"/><circuit name="main"><comp name="leaf" loc="(0,0)"/></circuit><circuit name="leaf"/></project>"""
        session.create("A", xml)
        val root = workspaceNavigationKey("main", emptyList())
        val instance = workspaceNavigationKey("leaf", listOf("import-1"))
        val rootViewport = WorkspaceViewport(0.5f, 10f, 20f)
        session.edit(xml, WorkspaceViewState("leaf", viewports = mapOf(root to rootViewport,
            instance to WorkspaceViewport(2f, -10f, -20f)), activeInstancePath = listOf("import-1"),
            viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED))
        assertEquals(listOf("import-1"), session.state.viewState.activeInstancePath)
        assertEquals(setOf(root, instance), session.state.viewState.viewports.keys)
        assertTrue(session.state.notices.isEmpty())
        session.flush()

        val reopened = WorkspaceSession(repository, {})
        assertTrue(reopened.open(session.state.document!!.info.id))
        assertEquals(emptyList(), reopened.state.viewState.activeInstancePath)
        assertEquals(mapOf(root to rootViewport), reopened.state.viewState.viewports)
        assertEquals(listOf("workspace_view_identity_recovered"), reopened.state.notices.map { it.key })
        assertEquals(xml, reopened.state.xml)
        reopened.edit(xml, reopened.state.viewState)
        reopened.flush()
        assertEquals(listOf("workspace_view_identity_recovered"), reopened.state.notices.map { it.key })
    }
}
