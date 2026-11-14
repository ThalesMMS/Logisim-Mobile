package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceOpenResult
import dev.logisim.mobile.core.WorkspaceProjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files and workspace: whether a linked file has the project's edits (persisted beside the link), project names,
 * permanent deletion, Open recent, and reopening a file a project is already linked to.
 */
class WorkspaceFilesBindingTest {
    private class Store : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        var failCatalog = false
        private var locked = false
        /** Like the iOS flock, the lock is not reentrant: nesting would hang there, so it fails here. */
        override fun <T> withExclusiveAccess(action: () -> T): T {
            check(!locked) { "withExclusiveAccess is not reentrant on iOS" }
            locked = true
            try { return action() } finally { locked = false }
        }
        override fun read(name: String) = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) {
            check(!failCatalog || name != WorkspaceCatalogRepository.CATALOG_FILE) { "disk unavailable" }
            files[name] = contents
        }
        override val supportsDelete: Boolean get() = true
        override fun delete(name: String) { files.remove(name) }
    }
    private class Native : DocumentActions {
        var retained: DocumentReference? = null
        var handler: ((DocumentOpenResult) -> Unit)? = null
        var write: ((DocumentWriteResult) -> Unit)? = null
        override val currentReference get() = retained
        override fun retain(reference: DocumentReference?) { retained = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) { this.handler = handler }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) { write = onResult }
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) { write = onResult }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
        fun deliver(reference: DocumentReference, text: String) { handler!!(DocumentOpenResult.Opened(DocumentContent(reference, text))) }
    }
    private class Window(val repository: WorkspaceCatalogRepository, val links: WorkspaceDocumentLinks,
                         legacyXml: String? = null, legacyReference: DocumentReference? = null) {
        val native = Native()
        lateinit var editor: EditorState
        lateinit var documents: EditorDocuments
        val binding = CatalogWorkspaceBinding(repository, links, "main", pauseSimulation = {},
            exportView = { if (::editor.isInitialized) editor.exportViewState() else null },
            exportXml = { if (::editor.isInitialized) editor.exportXml() else null },
            editorIdentity = { if (::editor.isInitialized) editor.documentIdentity else null },
            editorWritable = { if (::editor.isInitialized) editor.documentWritable else null }, nativeDocuments = native)
        init { binding.start(legacyXml, legacyReference); mount() }
        fun mount() {
            if (::documents.isInitialized) documents.detach()
            val state = binding.state
            editor = EditorState(state.editorXml, initialImportReport = state.editorImportReport)
            val mount = binding.state.editorMount
            documents = EditorDocuments(editor, binding.documents!!)
            documents.attach()
            editor.onWorkspaceProjectReplaced = { binding.projectReplaced(mount, it) }
            binding.editorAttached(mount, editor.importReport)
        }
        fun changed() = binding.documentChanged(binding.state.editorMount, editor.exportXml())
        private var edits = 0
        fun edit() { editor.place(ComponentKind.NOT); editor.tap(GridPoint(30 + 60 * edits++, 40)); changed() }
        fun open(id: WorkspaceProjectId) { assertTrue(binding.openProject(id)); mount() }
    }
    private var serial = 0
    private fun repository(store: Store) = WorkspaceCatalogRepository(store,
        validate = { CircCodec.readProject(it) is CircProjectImportResult.Success }, clock = { 123L }, idFactory = { "project-${++serial}" })
    private fun xml(name: String) = "<project><main name=\"$name\"/><circuit name=\"$name\"/></project>"
    /** What the host passes for a file: the editor's own export of what it read (EditorState.acceptImport). */
    private fun canonical(xml: String) = CircCodec.writeProject(assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project)
    private fun reference(name: String) = DocumentReference("bookmark-$name", "$name.circ", writable = true, revision = "sha-$name",
        mode = DocumentOpenMode.EDIT_IN_PLACE)
    private fun WorkspaceOpenResult.xml() = assertIs<WorkspaceOpenResult.Success>(this).document.xml
    private fun WorkspaceCatalogRepository.revision(id: WorkspaceProjectId) = list(includeDeleted = true).projects.single { it.id == id }.revision

    // C132: the file's own state, kept apart from the autosaved workspace stamp, survives the window.
    @Test fun aFileEditedInPlaceIsNotSavedUntilWrittenAndThatSurvivesTheWindow() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        assertTrue(window.binding.openNewSession("B", xml("B"), reference = reference("B"))); window.mount()
        val b = window.binding.state.editorProjectId!!
        window.changed()
        assertEquals(emptySet(), window.binding.state.externalDirty, "Opened from the file, the file has it")
        window.edit()
        assertEquals(setOf(b), window.binding.state.externalDirty, "Autosave reached the app, not the file")
        assertEquals(workspaceContentStamp(repo.open(b).xml()), links.read(b)?.workspaceStamp, "The link still follows autosave")

        assertEquals(setOf(b), Window(repo, links).binding.state.externalDirty, "Another window or a relaunch agrees")
        window.documents.save()
        window.native.write!!(DocumentWriteResult.Saved(reference("B").copy(revision = "saved")))
        assertEquals(emptySet(), window.binding.state.externalDirty, "Saved to the file")
        assertEquals(emptySet(), Window(repo, links).binding.state.externalDirty, "Recorded beside the link")

        // Save As to a provider that cannot replace atomically keeps the project linked to its own file, unsaved.
        window.edit()
        window.documents.saveAs()
        window.native.write!!(DocumentWriteResult.Saved(reference("Elsewhere").copy(writable = false)))
        assertEquals(setOf(b), window.binding.state.externalDirty)
    }

    @Test fun aLinkFromAnOlderBuildIsNotTrustedAndOlderBuildsStillReadTheLink() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val id = assertIs<WorkspaceMutationResult.Success>(repo.create("A", xml("A"))).document.info.id
        // Seven fields, as every build writes it, and no sync record.
        links.write(WorkspaceExternalDocument(id, reference("A"), workspaceContentStamp(repo.open(id).xml())))
        val window = Window(repo, links)
        assertEquals(setOf(id), window.binding.state.externalDirty, "Nothing recorded that the file matches")
        window.documents.save()
        window.native.write!!(DocumentWriteResult.Saved(reference("A")))
        assertEquals(emptySet(), window.binding.state.externalDirty)
        assertEquals(reference("A"), links.read(id)?.reference, "The link file keeps its seven fields")
    }

    // R02-1: an edited draft and a legacy checkpoint are linked without claiming they are what the file holds.
    @Test fun anEditedDraftIsLinkedButNotVouchedFor() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        window.changed()
        store.failCatalog = true
        window.native.deliver(reference("B"), xml("B")); window.changed()
        assertNull(window.binding.state.editorProjectId)
        window.editor.place(ComponentKind.NOT); window.editor.tap(GridPoint(30, 40)); window.changed()
        store.failCatalog = false
        assertTrue(window.binding.flush())
        val b = window.binding.state.editorProjectId!!
        assertEquals("B.circ", window.binding.state.sessionFiles[b])
        assertEquals(setOf(b), window.binding.state.unconfirmedFiles, "Closing offers Save to B.circ")
        assertEquals(setOf(b), window.binding.state.externalDirty)
    }

    @Test fun aMigratedLegacyCheckpointIsLinkedButNotVouchedFor() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("L"), legacyReference = reference("L"))
        val id = window.binding.state.editorProjectId!!
        assertEquals("L.circ", window.binding.state.sessionFiles[id])
        assertEquals(setOf(id), window.binding.state.unconfirmedFiles)
        assertEquals(setOf(id), window.binding.state.externalDirty)
    }

    // R02-2: after Save to file, undoing back to the opened version is a change from the file.
    @Test fun undoingToTheOpenedVersionAfterSavingIsAChange() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        assertTrue(window.binding.openNewSession("B", xml("B"), reference = reference("B"))); window.mount()
        val b = window.binding.state.editorProjectId!!
        window.edit()
        window.documents.save()
        window.native.write!!(DocumentWriteResult.Saved(reference("B")))
        assertEquals(emptySet(), window.binding.state.changedSessions)
        window.editor.undo(); window.changed()
        assertEquals(setOf(b), window.binding.state.changedSessions, "The file holds the edit; the tab no longer does")
        assertEquals(setOf(b), window.binding.state.externalDirty)
    }

    // R02-3: a restore from the trash list starts a fresh session; only the tab's Undo brings the old one back.
    @Test fun restoringFromTheTrashListStartsAFreshSession() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        assertTrue(window.binding.openNewSession("B", xml("B"))); window.mount()
        val b = window.binding.state.editorProjectId!!
        window.edit()
        window.open(a)
        assertTrue(window.binding.deleteProject(b, repo.revision(b)))
        assertTrue(window.binding.restoreProject(b, repo.revision(b)))
        assertFalse(b in window.binding.state.sessionOpenedAt, "No hidden session comes back")
        window.open(b)
        assertEquals(emptySet(), window.binding.state.changedSessions, "Its edits were saved before the trash; nothing changed since this open")
    }

    // C134: generated names are numbered; the .circ content is untouched.
    @Test fun newSessionsAreNumberedWithoutTouchingTheirContent() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val content = xml("main")
        assertTrue(window.binding.openNewSession("Untitled", content, uniqueName = true))
        val first = window.binding.state.editorProjectId!!
        assertEquals(content, repo.open(first).xml(), "Stored as given: only the catalog name was generated")
        window.mount()
        assertTrue(window.binding.openNewSession("Untitled", content, uniqueName = true))
        val second = window.binding.state.editorProjectId!!
        assertEquals(content, repo.open(second).xml())
        window.mount()
        val names = repo.list().projects.associate { it.id to it.name }
        assertEquals("Untitled", names[first]); assertEquals("Untitled 2", names[second])
        assertTrue(window.binding.saveLocalAsCopy("Untitled"))
        assertEquals("Untitled 3", repo.list().projects.single { it.id == window.binding.state.editorProjectId }.name)
        assertTrue(window.binding.duplicateProject(first, repo.revision(first), name = "Untitled (cópia)"))
        assertTrue(repo.list().projects.any { it.name == "Untitled (cópia)" }, "The copy is named in the app language")
    }

    @Test fun aFreshInstallStartsWithTheExampleTitle() {
        val store = Store(); val repo = repository(store)
        val binding = CatalogWorkspaceBinding(repo, StoredWorkspaceDocumentLinks(store), "main", pauseSimulation = {})
        binding.start(defaultName = "Porta AND")
        val project = repo.list().projects.single()
        assertEquals("Porta AND", project.name)
        val circuit = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(repo.open(project.id).xml())).project.mainCircuitName
        assertEquals("Minha_primeira_porta_AND", circuit, "The circuit keeps its name")
    }

    // C136: a permanent deletion removes the project, its revisions, its link and its recent entry.
    @Test fun purgingATrashedProjectForgetsItEverywhere() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        assertTrue(window.binding.openNewSession("B", xml("B"), reference = reference("B"))); window.mount()
        val b = window.binding.state.editorProjectId!!
        window.edit()
        assertTrue(window.binding.saveLocalAsCopy("B copy"))
        val copy = window.binding.state.editorProjectId!!
        window.open(a)
        assertTrue(window.binding.deleteProject(copy, repo.revision(copy)))
        assertTrue(window.binding.deleteProject(b, repo.revision(b)))
        assertEquals(2, window.binding.emptyTrash())
        assertEquals(listOf(a), repo.list(includeDeleted = true).projects.map { it.id })
        assertTrue(store.files.keys.none { it.startsWith("project-${b.value}-") || it.startsWith("project-${copy.value}-") })
        assertNull(links.read(b))
        assertTrue(window.binding.state.recentFiles.none { it is WorkspaceRecentFile.Copy && it.projectId == copy })
        assertTrue(links.recentFiles().none { it is WorkspaceRecentFile.Copy && it.projectId == copy })
    }

    // C147: Open recent follows renames, forgets entries, and an unchanged file reopens its project.
    @Test fun openRecentFollowsRenamesAndForgetsEntries() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        assertTrue(window.binding.saveLocalAsCopy("main"))
        val copy = window.binding.state.editorProjectId!!
        assertTrue(window.binding.renameProject(copy, repo.revision(copy), "Adder"))
        assertEquals(WorkspaceRecentFile.Copy(copy, "Adder"), window.binding.state.recentFiles.first())
        assertTrue(window.binding.removeRecent(window.binding.state.recentFiles.first()))
        assertTrue(window.binding.state.recentFiles.none { it is WorkspaceRecentFile.Copy && it.projectId == copy })
        assertTrue(links.recentFiles().none { it is WorkspaceRecentFile.Copy && it.projectId == copy })
    }

    // iOS locks with flock on a new descriptor, which blocks a nested call in the same process: the reverse lookup reads
    // the links under the lock it already holds (this Store fails on nesting instead of hanging).
    @Test fun findingTheProjectsLinkedToAFileTakesTheLockOnce() {
        val store = Store(); val links = StoredWorkspaceDocumentLinks(store)
        links.write(WorkspaceExternalDocument(WorkspaceProjectId("p1"), reference("B"), "1-a"))
        links.write(WorkspaceExternalDocument(WorkspaceProjectId("p2"), reference("C"), "1-b"))
        assertEquals(listOf(WorkspaceProjectId("p1")), links.linkedProjects(reference("B").id))
        assertEquals(emptyList(), links.linkedProjects("bookmark-nothing"))
    }

    @Test fun reopeningAnUnchangedLinkedFileReturnsToItsProject() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        // As the workspace host opens a file (EditorState.openWorkspaceSession -> openEditorSession).
        fun openFile(reference: DocumentReference, xml: String) {
            assertTrue(window.binding.openNewSession(reference.name.removeSuffix(".circ"), xml, reference = reference, uniqueName = true))
            window.mount()
        }
        openFile(reference("B"), canonical(xml("B")))
        val b = window.binding.state.editorProjectId!!
        window.edit()
        val edited = repo.open(b).xml()
        window.open(a)
        val projects = repo.list().projects.size
        openFile(reference("B").copy(revision = "same-file-again"), canonical(xml("B")))
        assertEquals(b, window.binding.state.editorProjectId, "The file opens its project")
        assertEquals(projects, repo.list().projects.size, "No other copy")
        assertEquals(edited, repo.open(b).xml(), "The project keeps its edits")
        assertEquals(setOf(b), window.binding.state.externalDirty, "which the file does not have")
        assertEquals("same-file-again", links.read(b)?.reference?.revision)

        // The file changed elsewhere: a new copy, as before.
        openFile(reference("B"), canonical(xml("B-elsewhere")))
        assertNotEquals(b, window.binding.state.editorProjectId)
        assertEquals(projects + 1, repo.list().projects.size)
    }
}
