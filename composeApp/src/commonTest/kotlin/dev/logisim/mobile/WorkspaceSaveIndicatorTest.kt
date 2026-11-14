package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogResult
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceDocument
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.WorkspaceProjectInfo
import dev.logisim.mobile.core.WorkspaceSaveStatus
import dev.logisim.mobile.core.WorkspaceSessionState
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WorkspaceSaveIndicatorTest {
    private val id = WorkspaceProjectId("project")
    private val document = WorkspaceDocument(WorkspaceProjectInfo(id, "Adder", 3, 1, 2), "<project/>")
    private val idle = CatalogWorkspaceRuntimeState(busy = false, pendingWrites = false)
    private val writing = CatalogWorkspaceRuntimeState(busy = false, pendingWrites = true)

    private fun state(
        status: WorkspaceSaveStatus = WorkspaceSaveStatus.SAVED,
        conflict: WorkspaceDocument? = null,
        saveFailed: Boolean = false,
        readOnly: Boolean = false,
        projectId: WorkspaceProjectId? = id,
    ) = CatalogWorkspaceState(WorkspaceCatalogResult(listOf(document.info), id),
        WorkspaceSessionState(document, document.xml, saveStatus = status, conflict = conflict), 1, document.xml, projectId,
        readOnly = readOnly, saveFailed = saveFailed)

    @Test
    fun precedenceIsConflictThenFailureThenReadOnlyThenSavingThenDraftThenPendingThenSaved() {
        val cases = listOf(
            Triple(state(), idle, WorkspaceSaveIndicator.SAVED),
            Triple(state(WorkspaceSaveStatus.CLEAN), idle, WorkspaceSaveIndicator.SAVED),
            Triple(state(WorkspaceSaveStatus.DIRTY), idle, WorkspaceSaveIndicator.PENDING),
            Triple(state(WorkspaceSaveStatus.DIRTY), writing, WorkspaceSaveIndicator.SAVING),
            Triple(state(projectId = null), idle, WorkspaceSaveIndicator.DRAFT),
            Triple(state(projectId = null), writing, WorkspaceSaveIndicator.SAVING),
            Triple(state(readOnly = true), writing, WorkspaceSaveIndicator.READ_ONLY),
            // A failure is never masked by the writes that retry it.
            Triple(state(WorkspaceSaveStatus.FAILED, saveFailed = true), writing, WorkspaceSaveIndicator.FAILED),
            Triple(state(WorkspaceSaveStatus.FAILED, saveFailed = true, readOnly = true), idle, WorkspaceSaveIndicator.FAILED),
            // FAILED from a failed open or copy, not a write: the stored project matches the buffer.
            Triple(state(WorkspaceSaveStatus.FAILED), idle, WorkspaceSaveIndicator.SAVED),
            // Sticky: a later edit puts the session back to DIRTY, but nothing was saved yet.
            Triple(state(WorkspaceSaveStatus.DIRTY, saveFailed = true), writing, WorkspaceSaveIndicator.FAILED),
            Triple(state(WorkspaceSaveStatus.CONFLICT, conflict = document, saveFailed = true), writing, WorkspaceSaveIndicator.CONFLICT),
        )
        for ((workspace, runtime, expected) in cases) {
            assertEquals(expected, workspaceSaveIndicator(workspace, runtime), "$workspace / $runtime")
        }
        assertEquals(setOf(WorkspaceSaveIndicator.FAILED, WorkspaceSaveIndicator.CONFLICT),
            WorkspaceSaveIndicator.entries.filter { it.needsAction }.toSet())
    }

    @Test
    fun aFailedWriteStaysFailedThroughLaterEditsUntilAWriteSucceeds() {
        val store = object : WorkspaceCatalogStorage {
            val files = mutableMapOf<String, String>()
            var fail = false
            override fun <T> withExclusiveAccess(action: () -> T): T = action()
            override fun read(name: String) = files[name]
            override fun listNames() = files.keys.toList()
            override fun writeAtomically(name: String, contents: String) {
                check(!fail || name != WorkspaceCatalogRepository.CATALOG_FILE) { "disk full" }
                files[name] = contents
            }
        }
        val repository = WorkspaceCatalogRepository(store, validate = { CircCodec.readProject(it) is CircProjectImportResult.Success })
        lateinit var editor: EditorState
        val binding = CatalogWorkspaceBinding(repository, StoredWorkspaceDocumentLinks(store), "main", pauseSimulation = {},
            exportXml = { editor.exportXml() }, exportView = { editor.exportViewState() },
            editorIdentity = { editor.documentIdentity }, editorWritable = { editor.documentWritable })
        binding.start("<project><main name=\"A\"/><circuit name=\"A\"/></project>")
        editor = EditorState(binding.state.editorXml)
        binding.editorAttached(binding.state.editorMount)
        fun indicator(pending: Boolean = false) = workspaceSaveIndicator(binding.state, if (pending) writing else idle)
        fun edit(x: Int) {
            editor.place(ComponentKind.NOT); editor.tap(GridPoint(x, 40))
            binding.documentChanged(binding.state.editorMount, editor.exportXml())
        }

        store.fail = true
        edit(30)
        assertEquals(WorkspaceSaveStatus.FAILED, binding.state.session.saveStatus)
        assertEquals(WorkspaceSaveIndicator.FAILED, indicator())
        val banner = workspaceBannerNotice(binding.state, indicator())
        assertEquals(Messages.WorkspaceErrorAccess, banner?.key)
        assertFalse(banner in workspaceNotices(binding.state, banner).map { it.text }, "The banner's notice is not listed twice")
        assertEquals(listOf(WorkspaceNoticeSeverity.INFO), workspaceNotices(binding.state, banner).map { it.severity },
            "Only the migration notice from start remains, as information")

        binding.viewChanged(binding.state.editorMount, editor.exportViewState())
        assertEquals(WorkspaceSaveStatus.DIRTY, binding.state.session.saveStatus)
        assertEquals(WorkspaceSaveIndicator.FAILED, indicator(pending = true))

        store.fail = false
        edit(60)
        assertEquals(WorkspaceSaveIndicator.SAVED, indicator())
        assertNull(workspaceBannerNotice(binding.state, indicator()))
    }

    @Test
    fun aFailedOpenIsNotAFailedSave() {
        val store = object : WorkspaceCatalogStorage {
            val files = mutableMapOf<String, String>()
            override fun <T> withExclusiveAccess(action: () -> T): T = action()
            override fun read(name: String) = files[name]
            override fun listNames() = files.keys.toList()
            override fun writeAtomically(name: String, contents: String) { files[name] = contents }
        }
        val repository = WorkspaceCatalogRepository(store, validate = { CircCodec.readProject(it) is CircProjectImportResult.Success })
        lateinit var editor: EditorState
        val binding = CatalogWorkspaceBinding(repository, StoredWorkspaceDocumentLinks(store), "main", pauseSimulation = {},
            exportXml = { editor.exportXml() }, exportView = { editor.exportViewState() },
            editorIdentity = { editor.documentIdentity }, editorWritable = { editor.documentWritable })
        binding.start("<project><main name=\"A\"/><circuit name=\"A\"/></project>")
        editor = EditorState(binding.state.editorXml)
        binding.editorAttached(binding.state.editorMount)
        assertFalse(binding.openProject(WorkspaceProjectId("missing")))
        assertEquals(WorkspaceSaveStatus.FAILED, binding.state.session.saveStatus, "The session reports the failed open")
        assertFalse(binding.state.saveFailed)
        assertEquals(WorkspaceSaveIndicator.SAVED, workspaceSaveIndicator(binding.state, idle),
            "The open project's last write succeeded, so it is not 'Not saved'")
    }

    @Test
    fun migrationAndRecoveryNoticesAreInformation() {
        assertEquals(WorkspaceNoticeSeverity.INFO, workspaceNoticeSeverity(message(Messages.WorkspaceMigrated)))
        assertEquals(WorkspaceNoticeSeverity.INFO, workspaceNoticeSeverity(message(Messages.WorkspaceCatalogRecovered)))
        assertEquals(WorkspaceNoticeSeverity.WARNING, workspaceNoticeSeverity(message(Messages.WorkspaceReadOnly)))
        assertEquals(WorkspaceNoticeSeverity.ERROR, workspaceNoticeSeverity(message(Messages.WorkspaceConflictSaved)))
        val notice = message(Messages.WorkspaceConflictSaved)
        val duplicated = state().copy(notices = listOf(notice), session = state().session.copy(notices = listOf(notice)))
        assertEquals(listOf(notice), workspaceNotices(duplicated).map { it.text })
    }
}
