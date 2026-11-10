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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CatalogWorkspaceBindingTest {
    private class Store : WorkspaceCatalogStorage {
        val files = mutableMapOf<String, String>()
        var failCatalog = false
        var beforeWrite: (() -> Unit)? = null
        override fun <T> withExclusiveAccess(action: () -> T): T = action()
        override fun read(name: String) = files[name]
        override fun listNames() = files.keys.toList()
        override fun writeAtomically(name: String, contents: String) {
            beforeWrite?.invoke()
            check(!failCatalog || name != WorkspaceCatalogRepository.CATALOG_FILE) { "disk unavailable" }
            files[name] = contents
        }
    }
    private class Native : DocumentActions {
        var retained: DocumentReference? = null
        var handler: ((DocumentOpenResult) -> Unit)? = null
        var write: ((DocumentWriteResult) -> Unit)? = null
        var lastSaved: Pair<DocumentReference, String>? = null
        var saveCalls = 0
        var pendingOpen: DocumentOpenResult? = null
        var reopened: DocumentReference? = null
        override val currentReference get() = retained
        override fun retain(reference: DocumentReference?) { retained = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) {
            this.handler = handler
            if (handler != null) pendingOpen?.let { pendingOpen = null; handler(it) }
        }
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) { reopened = reference }
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) { write = onResult }
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) {
            saveCalls++; lastSaved = reference to text; write = onResult
        }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
        fun deliver(reference: DocumentReference, text: String) { handler!!(DocumentOpenResult.Opened(DocumentContent(reference, text))) }
    }
    private class Window(val repository: WorkspaceCatalogRepository, val links: WorkspaceDocumentLinks,
        val native: Native = Native(), windowId: String = "main", legacyXml: String? = null,
        legacyReference: DocumentReference? = null, pause: () -> Unit = {}) {
        lateinit var editor: EditorState
        lateinit var documents: EditorDocuments
        val binding = CatalogWorkspaceBinding(repository, links, windowId, pauseSimulation = pause,
            exportView = { if (::editor.isInitialized) editor.exportViewState() else null },
            exportXml = { if (::editor.isInitialized) editor.exportXml() else null },
            editorIdentity = { if (::editor.isInitialized) editor.documentIdentity else null },
            editorWritable = { if (::editor.isInitialized) editor.documentWritable else null }, nativeDocuments = native)
        init { binding.start(legacyXml, legacyReference); mount() }
        fun mount(sessions: WorkspaceEditorSessions? = null) {
            if (::documents.isInitialized) documents.detach()
            val state = binding.state
            editor = state.editorProjectId?.let { sessions?.restore(it, state.editorXml, state.readOnly) }
                ?: EditorState(state.editorXml, initialImportReport = state.editorImportReport)
            state.editorProjectId?.let { sessions?.opened(it); sessions?.retain(it, editor) }
            val mount = binding.state.editorMount
            documents = EditorDocuments(editor, binding.documents!!)
            documents.attach()
            editor.onWorkspaceProjectReplaced = { binding.projectReplaced(mount, it) }
            binding.editorAttached(mount, editor.importReport)
        }
        fun changed() = binding.documentChanged(binding.state.editorMount, editor.exportXml())
        fun open(id: WorkspaceProjectId) { assertTrue(binding.openProject(id)); mount() }
    }
    private var serial = 0
    private val historicalProject = """<project source="2.7.1"><lib name="0" desc="#Wiring"/><main name="old"/><circuit name="old"><comp lib="0" name="Pin" loc="(0,0)"><a name="output" val="false"/></comp></circuit></project>"""
    private fun repository(store: Store) = WorkspaceCatalogRepository(store,
        validate = { CircCodec.readProject(it) is CircProjectImportResult.Success }, clock = { 123L }, idFactory = { "project-${++serial}" })
    private fun xml(name: String) = "<project><main name=\"$name\"/><circuit name=\"$name\"/></project>"
    private fun reference(name: String, mode: DocumentOpenMode = DocumentOpenMode.EDIT_IN_PLACE) =
        DocumentReference("bookmark-$name", "$name.circ", writable = true, revision = "sha-$name", mode = mode)
    private fun WorkspaceOpenResult.xml() = assertIs<WorkspaceOpenResult.Success>(this).document.xml
    private fun WorkspaceMutationResult.id() = assertIs<WorkspaceMutationResult.Success>(this).document.info.id

    @Test fun openingAndClosingAnotherSessionKeepsTheFirstEditorsSelectionSimulationAndHistory() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val sessions = WorkspaceEditorSessions()
        val a = window.binding.state.editorProjectId!!
        window.mount(sessions)
        val first = window.editor
        first.place(ComponentKind.INPUT)
        first.tap(GridPoint(30, 40))
        val input = first.document.components.single().id
        first.select(input)
        first.setInputValue(input, 1uL)
        val simulation = first.rootSimulation
        val savedA = first.exportXml()
        assertTrue(first.canUndo)

        assertTrue(window.binding.openNewSession("B", xml("B")))
        window.mount(sessions)
        val b = window.binding.state.editorProjectId!!
        assertEquals(listOf(a, b), sessions.openIds)
        assertNotEquals(first, window.editor)
        assertTrue(window.editor.document.components.isEmpty())
        window.editor.place(ComponentKind.NOT)
        window.editor.tap(GridPoint(50, 60))

        assertTrue(window.binding.closeEditorSession(b, a, save = true))
        sessions.close(b)
        window.mount(sessions)
        assertSame(first, window.editor)
        assertEquals(savedA, window.editor.exportXml())
        assertEquals(setOf(input), window.editor.selectedIds)
        assertEquals(simulation, window.editor.rootSimulation)
        assertTrue(window.editor.canUndo)
        assertTrue(repo.open(b).xml().contains("NOT Gate"))
        assertEquals(listOf(a), sessions.openIds)
    }

    @Test fun discardingPendingEditsClosesOnlyTheActiveSessionAfterAFailedSave() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        assertTrue(window.binding.openNewSession("B", xml("B")))
        window.mount()
        val b = window.binding.state.editorProjectId!!
        val savedB = repo.open(b).xml()
        window.editor.place(ComponentKind.NOT)
        window.editor.tap(GridPoint(10, 10))
        val editedB = window.editor.exportXml()
        store.failCatalog = true
        assertFalse(window.binding.closeEditorSession(b, a, save = true))
        assertEquals(b, window.binding.state.editorProjectId)
        assertEquals(editedB, window.binding.state.session.xml)

        store.failCatalog = false
        assertTrue(window.binding.closeEditorSession(b, a, save = false))
        assertEquals(a, window.binding.state.editorProjectId)
        assertEquals(savedB, repo.open(b).xml())
        window.mount()
        assertTrue(window.binding.closeEditorSession(a, next = null, save = true))
        assertTrue(window.binding.state.editorClosed)
        assertNull(window.binding.state.editorProjectId)
    }

    @Test fun discardingAnInactiveTabRestoresItsProjectWithoutChangingTheActiveTab() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        assertTrue(window.binding.openNewSession("B", xml("B")))
        window.mount()
        val b = window.binding.state.editorProjectId!!
        val originalB = repo.open(b).xml()

        window.editor.place(ComponentKind.NOT)
        window.editor.tap(GridPoint(30, 40))
        val editedB = window.editor.exportXml()
        assertNotEquals(originalB, editedB)
        window.changed()
        assertEquals(editedB, repo.open(b).xml())
        assertTrue(window.binding.openProject(a))
        window.mount()
        val aMount = window.binding.state.editorMount
        val originalA = repo.open(a).xml()

        assertTrue(window.binding.closeInactiveSession(b, save = false))
        assertEquals(a, window.binding.state.editorProjectId)
        assertEquals(aMount, window.binding.state.editorMount)
        assertEquals(originalA, repo.open(a).xml())
        assertEquals(originalB, repo.open(b).xml())
    }

    @Test fun discardDoesNotOverwriteARevisionSavedByAnotherWindow() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        assertTrue(window.binding.openNewSession("B", xml("B")))
        window.mount()
        val b = window.binding.state.editorProjectId!!
        window.editor.place(ComponentKind.NOT)
        window.editor.tap(GridPoint(30, 40))
        assertTrue(window.binding.openProject(a))
        val revision = assertIs<WorkspaceOpenResult.Success>(repo.open(b)).document.info.revision
        val otherWindow = xml("B-from-other-window")
        assertIs<WorkspaceMutationResult.Success>(repo.save(b, revision, otherWindow))

        assertFalse(window.binding.closeInactiveSession(b, save = false))
        assertEquals(a, window.binding.state.editorProjectId)
        assertEquals(otherWindow, repo.open(b).xml())
    }

    @Test fun recentOriginalAndCopyWithTheSameNameReopenTheirOwnContents() {
        val store = Store(); val repo = repository(store)
        val first = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("initial"))
        val original = reference("A")
        first.native.deliver(original, xml("providerA"))
        val oldOriginalProject = first.binding.state.editorProjectId!!
        first.native.deliver(original.copy(writable = false, mode = DocumentOpenMode.IMPORT_COPY), xml("copyA"))
        val copyProject = first.binding.state.editorProjectId!!
        val secondOriginal = reference("B")
        first.native.deliver(secondOriginal, xml("providerB"))
        val secondProject = first.binding.state.editorProjectId!!

        val resumed = Window(repo, StoredWorkspaceDocumentLinks(store))
        val originalEntry = WorkspaceRecentFile.Original(original)
        val copyEntry = WorkspaceRecentFile.Copy(copyProject, "A.circ")
        assertEquals(listOf(WorkspaceRecentFile.Original(secondOriginal), copyEntry, originalEntry), resumed.binding.state.recentFiles)

        resumed.binding.documents!!.reopen(originalEntry.reference)
        assertEquals(original, resumed.native.reopened)
        val currentReference = original.copy(revision = "new-revision")
        resumed.native.deliver(currentReference, xml("newProviderA"))
        assertEquals("newProviderA", resumed.editor.project.mainCircuitName)
        assertEquals(currentReference, resumed.documents.reference)
        assertEquals(WorkspaceRecentFile.Original(currentReference), resumed.binding.state.recentFiles.first())
        assertEquals(3, resumed.binding.state.recentFiles.size)

        resumed.documents.save()
        val updatedBookmark = currentReference.copy(id = "updated-bookmark-A", revision = "saved-revision")
        resumed.native.write!!(DocumentWriteResult.Saved(updatedBookmark))
        assertEquals(WorkspaceRecentFile.Original(updatedBookmark), resumed.binding.state.recentFiles.first())
        assertEquals(3, resumed.binding.state.recentFiles.size)

        resumed.open(copyEntry.projectId)
        assertEquals("copyA", resumed.editor.project.mainCircuitName)
        assertNull(resumed.documents.reference)
        assertEquals(copyEntry, resumed.binding.state.recentFiles.first())
        assertTrue(repo.open(oldOriginalProject).xml().contains("providerA"))
        assertTrue(repo.open(secondProject).xml().contains("providerB"))
        assertEquals(currentReference, resumed.native.lastSaved?.first)
        assertEquals(1, resumed.native.saveCalls)

        assertTrue(resumed.binding.saveLocalAsCopy("Independent copy"))
        val independent = resumed.binding.state.editorProjectId!!
        assertEquals(WorkspaceRecentFile.Copy(independent, "Independent copy"), resumed.binding.state.recentFiles.first())
        assertNotEquals(copyProject, independent)
    }

    @Test fun saveAsToAProviderWithoutAtomicReplacementIsListedInRecentFiles() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("initial"))
        val saved = reference("Saved").copy(writable = false)

        window.documents.saveAs()
        window.native.write!!(DocumentWriteResult.Saved(saved))

        assertEquals(WorkspaceRecentFile.Original(saved), window.binding.state.recentFiles.first())
        assertEquals(WorkspaceRecentFile.Original(saved), Window(repo, StoredWorkspaceDocumentLinks(store)).binding.state.recentFiles.first())
    }

    @Test fun unavailableRecentOriginalLeavesTheOpenProjectAndCopyUntouched() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("initial"))
        val original = reference("A")
        window.native.deliver(original, xml("providerA"))
        window.native.deliver(original.copy(writable = false, mode = DocumentOpenMode.IMPORT_COPY), xml("copyA"))
        val copy = window.binding.state.editorProjectId!!
        val contents = repo.open(copy).xml()
        val originalEntry = WorkspaceRecentFile.Original(original)

        for (failure in listOf("Permission revoked", "File moved")) {
            window.binding.documents!!.reopen(originalEntry.reference)
            window.native.handler!!(DocumentOpenResult.Failure(failure))
            assertEquals(copy, window.binding.state.editorProjectId)
            assertEquals("copyA", window.editor.project.mainCircuitName)
            assertEquals(contents, repo.open(copy).xml())
            assertTrue(originalEntry in window.binding.state.recentFiles)
        }
        assertEquals(0, window.native.saveCalls)
    }

    @Test fun anImportReportStaysWithItsWorkspaceAfterAnotherProjectIsOpened() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        assertTrue(window.editor.importXml(historicalProject, dev.logisim.mobile.core.localization.LocalizedText.literal("old-project.circ")))
        window.changed()
        val b = window.binding.state.editorProjectId!!
        val expected = window.editor.importReport!!.copy(showOnOpen = false)
        assertTrue(expected.migrations.isNotEmpty())
        assertEquals(expected, window.binding.state.editorImportReport)
        assertTrue(window.editor.importReportVisible)
        val savedXml = repo.open(b).xml()
        window.open(a)
        assertNotEquals(expected, window.binding.state.editorImportReport)
        assertNull(window.binding.state.editorImportReport?.origin)
        window.open(b)
        assertEquals(expected, window.editor.importReport)
        assertFalse(window.editor.importReportVisible)
        assertEquals("old-project.circ", window.editor.importReport?.origin?.resolve(dev.logisim.mobile.core.localization.AppLanguage.ENGLISH))
        assertEquals(savedXml, repo.open(b).xml())
        assertFalse(savedXml.contains("old-project.circ"))
    }

    @Test fun aFailedImportCommitKeepsItsReportUntilTheDraftGetsAStableId() {
        val store = Store(); val repo = repository(store)
        val window = Window(repo, StoredWorkspaceDocumentLinks(store), legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        store.failCatalog = true
        assertTrue(window.editor.importXml(historicalProject, dev.logisim.mobile.core.localization.LocalizedText.literal("pending.circ")))
        window.changed()
        assertNull(window.binding.state.editorProjectId)
        val report = window.binding.state.editorImportReport!!
        assertTrue(report.migrations.isNotEmpty())
        store.failCatalog = false
        assertTrue(window.binding.flush())
        val b = window.binding.state.editorProjectId!!
        window.open(a); window.open(b)
        assertEquals(report, window.editor.importReport)
        assertFalse(window.editor.importReportVisible)
    }

    @Test fun theReportFromAnInitialHistoricalRecordOpensOnlyOnItsFirstMount() {
        val store = Store(); val repo = repository(store)
        val original = repo.create("Historical", historicalProject).id()
        val window = Window(repo, StoredWorkspaceDocumentLinks(store))
        assertTrue(window.editor.importReportVisible)
        val consumed = window.binding.state.editorImportReport!!
        assertFalse(consumed.showOnOpen)
        assertTrue(consumed.migrations.isNotEmpty())
        val other = repo.create("Other", xml("Other")).id()
        window.open(other); window.open(original)
        assertEquals(consumed, window.editor.importReport)
        assertFalse(window.editor.importReportVisible)
    }

    @Test fun rejectedMountedXmlCannotBeReplacedByTheFallbackOnBackgroundOrNewProject() {
        val store = Store()
        val registry = dev.logisim.mobile.core.ComponentCatalog.registry.withDefinitions(listOf(
            dev.logisim.mobile.core.ComponentCatalog.registry.requireDefinition(dev.logisim.mobile.core.ComponentKind.NOT)
                .copy(library = "#RemovedProvider", fileName = "Old NOT")))
        val raw = """<project><lib name="old" desc="#RemovedProvider"/><main name="Original"/><circuit name="Original"><comp lib="old" name="Old NOT" loc="(100,100)"/></circuit></project>"""
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(raw, registry))
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(raw))
        // The old repository still knows the provider while the editor no longer does.
        val old = WorkspaceCatalogRepository(store, validate = { CircCodec.readProject(it, registry) is CircProjectImportResult.Success },
            idFactory = { "original-${++serial}" })
        val id = old.create("Original", raw).id()
        val window = Window(old, StoredWorkspaceDocumentLinks(store))
        val before = store.files.toMap()
        assertFalse(window.editor.documentWritable)
        assertTrue(window.binding.state.readOnly)
        assertEquals(raw, window.editor.exportXml())
        window.editor.dismissImportError()
        window.binding.foregroundChanged(false)
        window.binding.foregroundChanged(true)
        window.changed()
        assertEquals(raw, old.open(id).xml())
        assertEquals(before, store.files)
        window.editor.newDocument(); window.changed()
        assertTrue(window.editor.documentWritable)
        assertFalse(window.binding.state.readOnly)
        assertNotEquals(id, window.binding.state.editorProjectId)
        assertEquals(raw, old.open(id).xml())
        assertEquals(1L, assertIs<WorkspaceOpenResult.Success>(old.open(id)).document.info.revision)
        window.editor.undo(); window.changed()
        assertEquals(id, window.binding.state.editorProjectId)
        assertFalse(window.editor.documentWritable)
        assertTrue(window.binding.state.readOnly)
        assertEquals(raw, window.editor.exportXml())
        assertEquals(raw, old.open(id).xml())
    }

    @Test fun unavailableStoredProviderRemainsTheActiveOriginalAcrossBackgroundAndRecreation() {
        val store = Store()
        val registry = dev.logisim.mobile.core.ComponentCatalog.registry.withDefinitions(listOf(
            dev.logisim.mobile.core.ComponentCatalog.registry.requireDefinition(dev.logisim.mobile.core.ComponentKind.NOT)
                .copy(library = "#RemovedProvider", fileName = "Old NOT")))
        val raw = """<project><lib name="old" desc="#RemovedProvider"/><main name="Original"/><circuit name="Original"><comp lib="old" name="Old NOT" loc="(100,100)"/></circuit></project>"""
        val old = WorkspaceCatalogRepository(store, validate = { CircCodec.readProject(it, registry) is CircProjectImportResult.Success },
            idFactory = { "original-${++serial}" })
        val id = old.create("Original", raw).id()
        val current = repository(store)
        val window = Window(current, StoredWorkspaceDocumentLinks(store), windowId = "original-scene")
        val before = store.files.toMap()
        assertEquals(id, window.binding.state.editorProjectId)
        assertTrue(window.binding.state.readOnly)
        window.binding.foregroundChanged(false)
        window.binding.foregroundChanged(true)
        assertEquals(raw, current.open(id).xml())
        assertEquals(before, store.files)
        val recreated = Window(current, StoredWorkspaceDocumentLinks(store), windowId = "original-scene")
        assertEquals(id, recreated.binding.state.editorProjectId)
        assertEquals(raw, recreated.editor.exportXml())
        assertTrue(recreated.binding.state.readOnly)
    }

    @Test fun nativeCopyImportCreatesAnotherCatalogIdentityAndPreservesTheOriginal() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        val original = window.editor.exportXml(); window.changed()
        window.native.deliver(reference("B", DocumentOpenMode.IMPORT_COPY), xml("B"))
        val b = window.binding.state.editorProjectId!!
        window.changed()
        assertNotEquals(a, b)
        assertEquals(original, repo.open(a).xml())
        assertEquals("B", (CircCodec.readProject(repo.open(b).xml()) as CircProjectImportResult.Success).project.mainCircuitName)
        assertNull(links.read(b)); assertNull(window.native.currentReference)
        window.editor.undo(); window.changed()
        assertEquals(a, window.binding.state.editorProjectId)
        assertEquals(original, repo.open(a).xml())
        assertEquals(2, repo.list().projects.size)
    }

    @Test fun synchronousNativeOpenDuringAttachWaitsForTheWorkspaceListener() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val native = Native().apply { pendingOpen = DocumentOpenResult.Opened(DocumentContent(reference("B"), xml("B"))) }
        val window = Window(repo, links, native = native, legacyXml = xml("A"))
        window.changed()
        val documents = repo.list().projects.map { assertIs<WorkspaceOpenResult.Success>(repo.open(it.id)).document }
        assertEquals(2, documents.size)
        val a = documents.single { (CircCodec.readProject(it.xml) as CircProjectImportResult.Success).project.mainCircuitName == "A" }
        val b = documents.single { (CircCodec.readProject(it.xml) as CircProjectImportResult.Success).project.mainCircuitName == "B" }
        assertNotEquals(a.info.id, b.info.id)
        assertEquals(b.info.id, window.binding.state.editorProjectId)
        assertNull(links.read(a.info.id))
        assertEquals(reference("B"), links.read(b.info.id)?.reference)
    }

    @Test fun importAndUndoRestoreOnlyTheirOwnFileReferences() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val aReference = reference("A"); val bReference = reference("B")
        val window = Window(repo, links, legacyXml = xml("A"), legacyReference = aReference)
        val a = window.binding.state.editorProjectId!!
        window.native.deliver(bReference, xml("B")); window.changed()
        val b = window.binding.state.editorProjectId!!
        assertEquals(aReference, links.read(a)?.reference)
        assertEquals(bReference, links.read(b)?.reference)
        assertEquals(bReference, window.native.currentReference)
        window.editor.undo(); window.changed()
        assertEquals(a, window.binding.state.editorProjectId)
        assertEquals(aReference, window.native.currentReference)
        assertEquals(aReference, window.documents.reference)
        assertTrue(window.documents.canSave)
    }

    @Test fun delayedSaveCompletesForCapturedProjectWithoutRebindingTheNewProject() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"), legacyReference = reference("A"))
        val a = window.binding.state.editorProjectId!!
        var callback: DocumentWriteResult? = null
        val aXml = window.editor.exportXml()
        window.binding.documents!!.save(reference("A"), aXml) { callback = it }
        val completion = window.native.write!!
        val b = repo.create("B", xml("B")).id(); window.open(b)
        completion(DocumentWriteResult.Saved(reference("A").copy(revision = "sha-A-new")))
        assertEquals(reference("A") to aXml, window.native.lastSaved)
        assertIs<DocumentWriteResult.Cancelled>(callback)
        assertEquals("sha-A-new", links.read(a)?.reference?.revision)
        assertNull(links.read(b)); assertNull(window.native.currentReference)
        assertEquals("B", window.editor.project.mainCircuitName)
    }

    @Test fun delayedPickerResultCannotReplaceTheProjectOpenedWhileItWasPending() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        window.documents.editFile()
        val b = repo.create("B", xml("B")).id(); window.open(b)
        window.native.deliver(reference("C"), xml("C"))
        assertEquals(b, window.binding.state.editorProjectId)
        assertEquals("B", window.editor.project.mainCircuitName)
        assertEquals(2, repo.list().projects.size)
    }

    @Test fun eachWindowRestoresItsProjectAndRefusesAnotherProjectsBookmark() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val first = Window(repo, links, windowId = "scene-A", legacyXml = xml("A"), legacyReference = reference("A"))
        val a = first.binding.state.editorProjectId!!
        val second = Window(repo, links, windowId = "scene-B")
        val b = repo.create("B", xml("B")).id(); second.open(b)
        val resumed = Window(repo, links, windowId = "scene-A", legacyReference = reference("B"))
        assertEquals(a, resumed.binding.state.editorProjectId)
        assertEquals(reference("A"), resumed.native.currentReference)
        var result: DocumentWriteResult? = null
        resumed.binding.documents!!.save(reference("B"), resumed.editor.exportXml()) { result = it }
        assertIs<DocumentWriteResult.Failure>(result)
        assertEquals(0, resumed.native.saveCalls)
        assertEquals(b, links.windowProject("scene-B"))
    }

    @Test fun restoredStampMismatchNeverAllowsAStaleExternalOverwrite() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val id = repo.create("A", xml("A")).id()
        links.write(WorkspaceExternalDocument(id, reference("B"), workspaceContentStamp(xml("B"))))
        val window = Window(repo, links)
        assertNull(window.native.currentReference)
        // App emits an initial canonical XML autosave. It must not repair an untrusted
        // restored association by stamping the wrong bookmark with A's current XML.
        window.changed()
        assertEquals(workspaceContentStamp(xml("B")), links.read(id)?.workspaceStamp)
        window.binding.documents!!.retain(reference("B"))
        var result: DocumentWriteResult? = null
        window.binding.documents!!.save(reference("B"), window.editor.exportXml()) { result = it }
        assertNull(window.native.currentReference)
        assertIs<DocumentWriteResult.Failure>(result)
        assertEquals(0, window.native.saveCalls)
    }

    @Test fun replacementDuringWriteFailureKeepsDraftAndCannotAutosaveItOverA() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        val a = window.binding.state.editorProjectId!!
        window.changed(); val original = repo.open(a).xml()
        store.failCatalog = true
        window.native.deliver(reference("B"), xml("B")); window.changed()
        assertNull(window.binding.state.editorProjectId)
        assertEquals(original, repo.open(a).xml())
        assertEquals("B", window.editor.project.mainCircuitName)
        store.failCatalog = false
        assertTrue(window.binding.flush())
        assertNotEquals(a, window.binding.state.editorProjectId)
        assertEquals(original, repo.open(a).xml())
    }

    @Test fun backgroundPausesBeforeSavingTheLatestEditorAndForegroundNeverResumesIt() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        var running = false
        val window = Window(repo, links, windowId = "paused-scene", legacyXml = xml("A"), pause = { running = false })
        val id = window.binding.state.editorProjectId!!
        window.editor.insertComponent(dev.logisim.mobile.core.ComponentKind.NOT, dev.logisim.mobile.core.GridPoint(300, 300))
        val edited = window.editor.exportXml()
        running = true
        store.beforeWrite = { assertFalse(running) }
        window.binding.foregroundChanged(false)
        assertFalse(running)
        assertEquals(edited, repo.open(id).xml())
        assertFalse(window.binding.state.session.dirty)
        window.binding.foregroundChanged(true)
        assertFalse(running)
        val recreated = Window(repo, links, windowId = "paused-scene")
        assertEquals(id, recreated.binding.state.editorProjectId)
        assertEquals(edited, recreated.editor.exportXml())
    }

    @Test fun editorFromAnOldMountCannotOverwriteTheNewSessionDuringItsAttachment() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = xml("A"))
        val oldMount = window.binding.state.editorMount
        val bXml = xml("B"); val b = repo.create("B", bXml).id()
        assertTrue(window.binding.openProject(b))
        // The old editor still supplies A until the new Compose subtree has attached.
        assertTrue(window.binding.flush())
        window.binding.documentChanged(oldMount, xml("stale"))
        assertEquals(bXml, repo.open(b).xml())
        window.mount(); assertEquals("B", window.editor.project.mainCircuitName)
    }

    @Test fun savingVisibleDraftAsCopyPreservesAnEarlierConflictingBufferToo() {
        val store = Store(); val repo = repository(store); val links = StoredWorkspaceDocumentLinks(store)
        val window = Window(repo, links, legacyXml = historicalProject)
        val a = window.binding.state.editorProjectId!!
        val reportA = window.binding.state.editorImportReport!!
        assertTrue(reportA.migrations.isNotEmpty())
        window.editor.insertComponent(dev.logisim.mobile.core.ComponentKind.NOT, dev.logisim.mobile.core.GridPoint(300, 300))
        val localA = window.editor.exportXml()
        val initial = assertIs<WorkspaceOpenResult.Success>(repo.open(a)).document
        repo.save(a, initial.info.revision, xml("remote"))
        window.native.deliver(reference("B"), xml("B")); window.changed()
        assertNull(window.binding.state.editorProjectId)
        assertTrue(window.binding.saveLocalAsCopy("visible B"))
        assertEquals("B", (CircCodec.readProject(window.binding.state.editorXml!!) as CircProjectImportResult.Success).project.mainCircuitName)
        val recoveredA = repo.list().projects.single { repo.open(it.id).xml() == localA }.id
        assertEquals(xml("remote"), repo.open(a).xml())
        window.open(recoveredA)
        assertEquals(reportA, window.editor.importReport)
        assertFalse(window.editor.importReportVisible)
    }
}
