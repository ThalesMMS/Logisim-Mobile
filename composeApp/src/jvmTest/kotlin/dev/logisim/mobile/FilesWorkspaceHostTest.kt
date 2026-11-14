package dev.logisim.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceOpenResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Files and workspace on a phone (Skia, as on iOS): what the header says about a linked file, project names, dates,
 * the trash, the window without a project, Open recent, session tabs, the import report and Merge project.
 * Screenshots go to `-PuiScreenshots=<dir>`; every scenario is captured before its assertions.
 */
@OptIn(ExperimentalTestApi::class)
class FilesWorkspaceHostTest {
    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        private val lock = ReentrantLock()
        override fun <T> withExclusiveAccess(action: () -> T): T = lock.withLock(action)
        override fun read(name: String): String? = lock.withLock { files[name] }
        override fun listNames(): List<String> = lock.withLock { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) = lock.withLock { files[name] = contents }
        override val supportsDelete get() = true
        override fun delete(name: String) { lock.withLock { files.remove(name) } }
    }

    /** A file provider that edits in place and delivers files opened from other apps; each save waits for [finish]. */
    private class Files : DocumentActions {
        var retained: DocumentReference? = null
        var handler: ((DocumentOpenResult) -> Unit)? = null
        val opened = mutableListOf<DocumentOpenMode>()
        val saved = mutableListOf<Pair<DocumentReference, String>>()
        var pending: ((DocumentWriteResult) -> Unit)? = null
        override val currentReference get() = retained
        override fun retain(reference: DocumentReference?) { retained = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) { this.handler = handler }
        override fun open(mode: DocumentOpenMode) { opened += mode }
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) {
            saved += reference to text; pending = onResult
        }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
        fun finish(result: DocumentWriteResult) { val callback = pending; pending = null; callback?.invoke(result) }
        fun deliver(reference: DocumentReference, text: String) = handler?.invoke(DocumentOpenResult.Opened(DocumentContent(reference, text)))
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val language = AppLanguage.PORTUGUESE
    private val circuit = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private fun t(key: String, vararg arguments: Pair<String, Any?>) = message(key, *arguments).resolve(language)
    private fun repository(storage: WorkspaceCatalogStorage, clock: () -> Long = { System.currentTimeMillis() }) =
        WorkspaceCatalogRepository(storage, validate = { CircCodec.readProject(it) is CircProjectImportResult.Success }, clock = clock)

    /** Creates the projects oldest first at [ages] milliseconds ago, so the last one is the one the window opens. */
    private fun seed(storage: WorkspaceCatalogStorage, vararg names: String, ages: List<Long> = names.indices.map { 60_000L * (names.size - it) }): List<WorkspaceProjectId> {
        val now = System.currentTimeMillis()
        return names.mapIndexed { index, name ->
            val seeding = repository(storage, clock = { now - ages[index] })
            assertIs<WorkspaceMutationResult.Success>(seeding.create(name, circuit)).document.info.id
        }
    }

    private fun ComposeUiTest.host(storage: WorkspaceCatalogStorage, documents: DocumentActions? = null, lifecycle: TestLifecycle = TestLifecycle()) {
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                CatalogWorkspaceHost(storage = storage, language = language, documents = documents)
            }
        }
        waitForTag("editor-canvas")
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.hasText(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.waitForTag(tag: String, timeout: Long = 10_000) = waitUntil(timeoutMillis = timeout) { exists(tag) }
    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false, timeout: Long = 10_000) =
        waitUntil(timeoutMillis = timeout) { hasText(text, substring) }
    /** False instead of a timeout failure, so a scenario can record what is missing after its screenshot. */
    private fun ComposeUiTest.eventually(timeout: Long = 4_000, condition: () -> Boolean): Boolean =
        runCatching { waitUntil(timeoutMillis = timeout, condition = condition) }.isSuccess

    private fun ComposeUiTest.settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        waitUntil(timeoutMillis = millis + 5_000) { System.currentTimeMillis() >= until }
        waitForIdle()
    }

    private fun ComposeUiTest.menu(menu: String, action: String) {
        if (exists("editor-project-toolbar-overflow")) onNodeWithTag("editor-project-toolbar-overflow").performClick()
        waitForTag("menu-title-$menu"); onNodeWithTag("menu-title-$menu").performClick()
        waitForTag("menu-$action"); onNodeWithTag("menu-$action").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openProjects() {
        menu("file", "projects")
        waitForText(t("workspace_new_project"))
    }

    private fun ComposeUiTest.deleteEverything() {
        menu("edit", "select-all")
        menu("edit", "delete")
    }

    private fun ComposeUiTest.rowAction(id: WorkspaceProjectId, key: String) {
        val overflow = "workspace-project-actions-${id.value}"
        if (exists(overflow)) {
            onNodeWithTag(overflow).performClick()
            waitForText(t(key))
        }
        onNodeWithText(t(key)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openRow(id: WorkspaceProjectId) {
        onNodeWithTag("workspace-project-${id.value}").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.closeFromProjects() {
        openProjects()
        onNodeWithText(t("workspace_close_session")).performClick()
        waitForIdle()
    }

    private fun stored(storage: WorkspaceCatalogStorage, id: WorkspaceProjectId) =
        assertIs<WorkspaceOpenResult.Success>(repository(storage).open(id)).document

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::collect)
        collect(root)
    }

    private fun SemanticsNodeInteraction.config() = fetchSemanticsNode().config

    // C132: a project opened with Edit external file says whether its file has the latest edits.
    @Test
    fun aLinkedProjectSaysWhetherItsFileHasTheEdits() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        seed(storage, "Contador")
        val files = Files()
        host(storage, documents = files)
        settle(800)
        val reference = DocumentReference("bookmark-somador", "Somador.circ", writable = true, revision = "r1",
            mode = DocumentOpenMode.EDIT_IN_PLACE)
        runOnIdle { files.deliver(reference, circuit) }
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.size == 2 }
        waitForTag("editor-canvas")
        settle(1_200)
        screenshot("c132-1-linked-file-opened")
        // The project took the file's name ("Somador"), so the header says "the file" rather than repeating it.
        val savedTo = t("workspace_saved_to_its_file")
        val notSaved = t("workspace_not_saved_to_its_file")
        val openedSaysSaved = hasText(savedTo, substring = true)
        deleteEverything()
        settle(1_500)
        screenshot("c132-2-linked-file-edited")
        val editedSaysNotSaved = hasText(notSaved, substring = true)
        val editedClaimsSaved = texts().any { it.contains(t("workspace_saved_revision", "revision" to "").substringBefore(" ·")) && it.contains("·") }
        menu("file", "save")
        waitUntil(timeoutMillis = 10_000) { files.pending != null }
        runOnIdle { files.finish(DocumentWriteResult.Saved(reference.copy(revision = "r2"))) }
        settle(1_200)
        screenshot("c132-3-linked-file-saved")
        assertTrue(openedSaysSaved, "Opened from the file, the header says the file has it")
        assertTrue(editedSaysNotSaved, "After an edit the header says the file does not have it")
        assertFalse(editedClaimsSaved && !editedSaysNotSaved, "The header does not say Saved while the file is stale")
        assertTrue(hasText(savedTo, substring = true), "Saving to the file says so")
    }

    // C134: File > New gives projects their own names, not the circuit name.
    @Test
    fun newProjectsAreUntitledAndNumbered() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        seed(storage, "Somador")
        host(storage)
        settle(800)
        menu("file", "new")
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.size == 2 }
        settle(1_000)
        menu("file", "new")
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.size == 3 }
        settle(1_000)
        openProjects()
        settle(300)
        screenshot("c134-1-two-new-projects")
        val names = repository(storage).list().projects.map { it.name }.toSet()
        val untitled = t("workspace_untitled_project")
        assertEquals(setOf("Somador", untitled, "$untitled 2"), names)
    }

    // C135: rows say when each project was edited; neither rows nor header show the storage revision.
    @Test
    fun rowsShowWhenProjectsWereEditedNotRevisions() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        seed(storage, "Contador", "Somador", ages = listOf(3 * 24 * 3_600_000L, 5 * 60_000L))
        host(storage)
        settle(1_000)
        screenshot("c135-1-header")
        val headerHasRevision = texts().any { Regex("(?i)revis(ion|ão) \\d+").containsMatchIn(it) }
        openProjects()
        settle(300)
        screenshot("c135-2-project-rows")
        val revisionRows = texts().filter { Regex("(Revision|Revisão) \\d+ · [0-9a-f]{8}").containsMatchIn(it) }
        assertFalse(headerHasRevision, "The header shows no revision counter")
        assertEquals(emptyList(), revisionRows, "Rows show no revision counter or hex id")
        assertTrue(hasText(t("workspace_edited", "time" to LocalizedText("time_days_ago", quantity = 3))),
            "The older project says it was edited 3 days ago")
    }

    // C136: the trash can be emptied, one project or all, behind a confirmation that says it cannot be undone.
    @Test
    fun trashedProjectsCanBeDeletedPermanently() = runDesktopComposeUiTest(360, 760) {
        val storage = MemoryStorage()
        val (counter, _) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        rowAction(counter, "workspace_move_to_trash")
        settle(500)
        onNodeWithText(t("workspace_show_trash")).performClick()
        waitForText("Contador")
        settle(300)
        screenshot("c136-1-trash-view")
        val overflow = "workspace-project-actions-${counter.value}"
        val offered = exists(overflow).also { if (it) onNodeWithTag(overflow).performClick() } &&
            eventually { hasText(t("workspace_delete_permanently")) }
        if (offered) {
            onNodeWithText(t("workspace_delete_permanently")).performClick()
            waitForIdle()
        }
        settle(300)
        screenshot("c136-2-delete-confirmation")
        assertTrue(offered, "A trashed row offers Delete permanently")
        assertTrue(hasText(t("workspace_delete_permanently_explanation")), "The confirmation says it cannot be undone")
        onNode(androidx.compose.ui.test.hasText(t("workspace_delete_permanently")) and hasAnyAncestor(hasTestTag("workspace-delete-dialog"))).performClick()
        waitUntil(timeoutMillis = 10_000) { repository(storage).list(includeDeleted = true).projects.none { it.id == counter } }
        settle(300)
        screenshot("c136-3-after-delete")
        assertTrue(storage.listNames().none { it.startsWith("project-${counter.value}-") }, "No saved version of the project is left")
        assertTrue(hasText(t("workspace_trash_empty")))
        assertFalse(hasText("Contador"))
    }

    // C139: closing the last tab leaves a way forward, and a file opened from another app opens at once.
    @Test
    fun theWindowWithoutAProjectOffersAWayForward() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        seed(storage, "Somador")
        val files = Files()
        host(storage, documents = files)
        settle(800)
        closeFromProjects()
        waitUntil(timeoutMillis = 10_000) { !exists("editor-canvas") }
        settle(300)
        screenshot("c139-1-no-project-open")
        val offersNew = hasText(t("workspace_new_project"))
        val offersImport = hasText(t("action_import_copy"))
        val offersRecent = hasText(t("workspace_open_recent"))
        val offersExamples = hasText(t("help_examples"))
        val reference = DocumentReference("bookmark-adder", "Adder.circ", writable = true, revision = "r1", mode = DocumentOpenMode.EDIT_IN_PLACE)
        // The provider's own note (here: this file can only be saved with Save as) is shown, as the editor shows it.
        val notice = t("android_doc_save_as_required")
        runOnIdle { files.handler?.invoke(DocumentOpenResult.Opened(DocumentContent(reference, circuit), message("android_doc_save_as_required"))) }
        val openedAtOnce = eventually(6_000) { exists("editor-canvas") }
        val noticeShown = eventually(4_000) { hasText(notice) }
        settle(500)
        screenshot("c139-2-after-open-with")
        assertTrue(offersNew && offersImport && offersRecent && offersExamples, "New project, Import, Open recent and Examples are offered")
        assertTrue(openedAtOnce, "A file opened from another app opens without opening another project first")
        assertEquals(setOf("Somador", "Adder"), repository(storage).list().projects.map { it.name }.toSet())
        assertTrue(noticeShown, "The open's notice is shown")
    }

    // C147: Open recent follows renames and can forget an entry.
    @Test
    fun openRecentFollowsRenamesAndForgetsEntries() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        StoredWorkspaceDocumentLinks(storage).recordRecent(WorkspaceRecentFile.Copy(adder, "Somador"))
        val files = Files()
        host(storage, documents = files)
        settle(800)
        // Rename the project in Projects, as the user does.
        openProjects()
        rowAction(adder, "action_rename")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].apply {
            performTextInput("Somador completo"); performImeAction()
        }
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.single().name == "Somador completo" }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        settle(500)
        // Tapping the open project's row closes the sheet.
        openRow(adder)
        waitUntil(timeoutMillis = 10_000) { !hasText(t("workspace_new_project")) }
        menu("file", "recent")
        waitForText(t("workspace_open_recent"))
        settle(300)
        screenshot("c147-1-open-recent")
        val showsNewName = hasText("Somador completo")
        val remove = t("workspace_remove_recent", "name" to "Somador completo")
        val removable = onAllNodesWithContentDescription(remove).fetchSemanticsNodes().isNotEmpty()
        assertTrue(showsNewName, "The recent entry shows the project's current name")
        assertTrue(removable, "The entry can be removed from the list")
        onAllNodesWithContentDescription(remove)[0].assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        waitForText(t("workspace_no_recent_files"))
        screenshot("c147-2-entry-removed")
        assertEquals(emptyList(), StoredWorkspaceDocumentLinks(storage).recentFiles())
    }

    // C148: tabs are tabs, say which ones changed, tell identical names apart and close to their neighbour.
    @Test
    fun sessionTabsAreTabsThatMarkChanges() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (first, second, third) = seed(storage, "main", "main", "Somador")
        host(storage)
        openProjects(); openRow(first); waitForTag("workspace-session-${first.value}"); settle(500)
        openProjects(); openRow(second); waitForTag("workspace-session-${second.value}"); settle(500)
        deleteEverything()
        settle(1_200)
        screenshot("c148-1-three-tabs-one-edited")
        val tab = onNodeWithTag("workspace-session-${second.value}").config()
        val role = tab.getOrNull(SemanticsProperties.Role)
        val state = tab.getOrNull(SemanticsProperties.StateDescription)
        val closeLabel = onNodeWithTag("workspace-close-session-${third.value}").config().getOrNull(SemanticsProperties.ContentDescription)
        val labels = listOf(first, second).map { id ->
            onAllNodes(hasAnyAncestor(hasTestTag("workspace-session-${id.value}")) or hasTestTag("workspace-session-${id.value}"))
                .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }.joinToString(" ")
        }
        assertEquals(Role.Tab, role, "A session is a tab")
        assertTrue(state?.isNotBlank() == true, "The edited tab says it changed: $state")
        assertEquals(listOf(t("workspace_a11y_close_tab", "name" to "Somador")), closeLabel)
        assertFalse(closeLabel.orEmpty().any { "?" in it }, "The close button is not announced as a question")
        assertTrue(labels[0] != labels[1], "Tabs with the same name are told apart: $labels")
        onNodeWithTag("workspace-close-session-${third.value}").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        for (id in listOf(first, second, third)) onNodeWithTag("workspace-session-${id.value}").assertHeightIsAtLeast(48.dp)
        val duplicateCloseLabels = listOf(first, second).map { id ->
            onNodeWithTag("workspace-close-session-${id.value}").config().getOrNull(SemanticsProperties.ContentDescription)
        }
        assertTrue(duplicateCloseLabels[0] != duplicateCloseLabels[1], "Each close button names its own tab: $duplicateCloseLabels")
        onNodeWithTag("workspace-session-${second.value}").assertIsDisplayed()
        assertEquals(true, tab.getOrNull(SemanticsProperties.Selected))

        // Closing the open middle tab hands the editor to its neighbour, and that tab scrolls into view.
        openProjects(); openRow(first); settle(500)
        onNodeWithTag("workspace-close-session-${first.value}").performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("workspace-session-${first.value}") }
        settle(800)
        screenshot("c148-2-closed-middle-tab")
        assertEquals(second, repository(storage).list().activeProjectId, "The tab after it is selected")
        onNodeWithTag("workspace-session-${second.value}").assertIsDisplayed()
    }

    // R02-4: during a conflict, closing the tab offers what resolves it.
    @Test
    fun aConflictCloseDialogOffersReloadAndSaveACopy() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        host(storage)
        settle(800)
        val current = stored(storage, adder)
        val elsewhere = HostScreenshots.fixture("fixtures/user-scenarios/fpga-and.circ")
        assertIs<WorkspaceMutationResult.Success>(repository(storage).save(adder, current.info.revision, elsewhere))
        deleteEverything()
        waitForTag("workspace-save-banner")
        closeFromProjects()
        waitForTag("workspace-close-session-dialog")
        settle(300)
        screenshot("r02-4-conflict-close-dialog")
        fun inDialog(key: String) = onAllNodes(androidx.compose.ui.test.hasText(t(key)) and hasAnyAncestor(hasTestTag("workspace-close-session-dialog")))
            .fetchSemanticsNodes().isNotEmpty()
        assertTrue(inDialog("workspace_banner_reload"), "Reload is offered in the dialog")
        assertTrue(inDialog("workspace_banner_save_copy"), "Save a copy is offered in the dialog")
        assertFalse(inDialog("workspace_close_keep_changes"), "Keep changes cannot succeed during a conflict")
        // Reload discards the edits the dialog just said exist only here: Save a copy is the first (primary) action.
        val saveCopy = onNodeWithTag("workspace-close-session-save-copy").fetchSemanticsNode().boundsInRoot
        val reload = onNodeWithTag("workspace-close-session-reload").fetchSemanticsNode().boundsInRoot
        assertTrue(saveCopy.top < reload.top, "Save a copy comes before Reload: $saveCopy, $reload")
    }

    // C132 at phone widths: a long file status moves to its own line instead of squeezing out the project name.
    @Test
    fun theHeaderKeepsTheProjectNameBesideALongFileStatus() {
        for (width in listOf(360, 390)) runDesktopComposeUiTest(width, 760) {
            val storage = MemoryStorage()
            seed(storage, "Somador")
            val files = Files()
            host(storage, documents = files)
            settle(800)
            // The project already named "Somador" makes this one "Somador 2", so the header names the file.
            val reference = DocumentReference("bookmark-somador", "Somador.circ", writable = true, revision = "r1",
                mode = DocumentOpenMode.EDIT_IN_PLACE)
            runOnIdle { files.deliver(reference, circuit) }
            waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.size == 2 }
            waitForTag("editor-canvas")
            settle(1_000)
            deleteEverything()
            val notSaved = t("workspace_not_saved_to_file", "file" to "Somador.circ")
            fun status(): Pair<String?, String?> {
                val node = onAllNodesWithTag("editor-header-save-state", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.config
                val visible = node?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                return visible to (node?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: visible)
            }
            val shown = eventually(6_000) { status().second == notSaved }
            settle(300)
            screenshot("c132-4-header-$width-not-saved")
            assertTrue(shown, "The header says, and TalkBack reads, that Somador.circ does not have the edits: ${status()}")
            assertTrue(status().first in setOf(notSaved, t("workspace_not_saved_to_its_file")), "${status()}")
            assertEquals(listOf("Somador 2"), onNodeWithTag("editor-header-project", useUnmergedTree = true).config()
                .getOrNull(SemanticsProperties.Text)?.map { it.text })
            for (tag in listOf("editor-header-project", "editor-header-save-state")) {
                val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                onNodeWithTag(tag, useUnmergedTree = true).config()[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
                    .action?.invoke(layout)
                assertFalse(layout.single().isLineEllipsized(0), "$tag is not cut at ${width}dp")
            }
        }
    }

    // "Saving…" waits 500 ms, so the autosave after every pan or tap does not flicker the header.
    @Test
    fun savingShowsOnlyForAWriteThatTakesAWhile() = runDesktopComposeUiTest {
        val pending = androidx.compose.runtime.mutableStateOf(false)
        var shown = false
        mainClock.autoAdvance = false
        setContent { shown = rememberSavingShown(pending.value) }
        mainClock.advanceTimeByFrame()
        pending.value = true
        mainClock.advanceTimeBy(400)
        assertFalse(shown, "Not yet at 400 ms")
        mainClock.advanceTimeBy(200)
        assertTrue(shown, "Shown once the write is still pending at 500 ms")
        pending.value = false
        mainClock.advanceTimeByFrame()
        assertFalse(shown, "Gone as soon as the write completes")
        pending.value = true
        mainClock.advanceTimeBy(300)
        pending.value = false
        mainClock.advanceTimeBy(600)
        assertFalse(shown, "A quick write never shows it")
    }

    private val legacy = """<?xml version="1.0" encoding="UTF-8"?>
<project source="2.7.1" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="A"/></comp>
    <comp lib="0" loc="(100,140)" name="Pin"><a name="label" val="B"/></comp>
    <comp lib="1" loc="(200,120)" name="AND Gate"><a name="label" val="G1"/></comp>
    <comp lib="1" loc="(300,120)" name="NOT Gate"/>
  </circuit>
</project>
"""

    private fun ComposeUiTest.app(xml: String, assets: DocumentAssetActions? = null) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { App(initialXml = xml, language = language, assets = assets) }
            }
        }
        waitForIdle()
    }

    // C109 / C145: the import report leads with what changed, names components and can show them.
    @Test
    fun theImportReportNamesComponentsInsteadOfIds() = runDesktopComposeUiTest(390, 844) {
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacy))
        val ids = imported.migrations.mapNotNull { it.componentId }.distinct()
        assertTrue(ids.isNotEmpty(), "The fixture produces component migrations")
        app(legacy)
        // C145: version defaults alone leave the report closed, so it is opened from the File menu once loaded.
        waitForTag("editor-canvas")
        if (!exists("editor-import-report")) menu("file", "import-report")
        waitForTag("editor-import-report")
        settle(300)
        screenshot("c109-1-import-report")
        val shown = texts()
        val rawRule = shown.filter { line -> imported.migrations.any { line.contains(it.rule) } }
        val rawIds = shown.filter { line -> Regex("\\b(${ids.joinToString("|") { Regex.escape(it) }})\\b").containsMatchIn(line) }
        assertEquals(emptyList(), rawRule, "No raw rule code is the visible content")
        assertEquals(emptyList(), rawIds, "No component id is shown")
        assertTrue(shown.any { "G1" in it }, "The labelled gate is named")
        val rawNames = shown.filter { line -> listOf("AND Gate", "NOT Gate", "Pin:").any { it in line } }
        assertEquals(emptyList(), rawNames, "Headings name components by their kind in the app language, not their XML name")
        assertTrue(hasText(t("import_report_show")), "Each group can show its components")
        // Each group's first line is its notice (with attribute labels), not the rule.
        val expected = importReportGroups(EditorImportReport.from(imported), imported.project).first().notice.resolve(language)
        val firstGroup = onAllNodes(hasTestTag("import-report-rule-${imported.migrations.first().rule}"))[0].fetchSemanticsNode()
        assertEquals(expected, firstGroup.children.first().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
    }

    // C109: Show selects the group's components in their circuit and closes the report.
    @Test
    fun showSelectsTheReportedComponents() = runDesktopComposeUiTest(390, 844) {
        val gateOnly = legacy.replace(Regex("<comp lib=\"0\"[^\n]*\n"), "").replace(Regex("<comp lib=\"1\" loc=\"\\(300,120\\)\"[^\n]*\n"), "")
        val state = EditorState(gateOnly)
        val gate = state.project.mainCircuit.components.single { it.label == "G1" }
        assertEquals(1, importReportGroups(checkNotNull(state.importReport), state.project).size, gateOnly)
        var dismissed = false
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) {
                    ImportReportDialog(checkNotNull(state.importReport), { dismissed = true }, state.project) { circuit, ids ->
                        dismissed = true
                        state.selectCircuit(circuit)
                        ids.forEachIndexed { index, id -> state.select(id, additive = index > 0) }
                    }
                }
            }
        }
        waitForIdle()
        val show = androidx.compose.ui.test.hasContentDescription(t("import_report_show_a11y", "circuit" to "main"))
        onNode(show).assertHeightIsAtLeast(48.dp).performClick()
        waitForIdle()
        assertTrue(dismissed)
        assertEquals(setOf(gate.id), state.selectedIds)
    }

    // C109 through App: the report it opens for a legacy file selects with Show, closes, and the selection bar appears.
    @Test
    fun showInTheEditorSelectsTheReportedComponents() = runDesktopComposeUiTest(390, 844) {
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacy))
        val group = importReportGroups(EditorImportReport.from(imported), imported.project).first()
        val control = EditorSessionControl()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { App(initialXml = legacy, language = language, sessionControl = control) }
            }
        }
        waitForIdle()
        // C145: version defaults alone leave the report closed, so it is opened from the File menu once loaded.
        waitForTag("editor-canvas")
        if (!exists("editor-import-report")) menu("file", "import-report")
        waitForTag("editor-import-report")
        val before = control.exportViewState()
        onAllNodes(androidx.compose.ui.test.hasContentDescription(t("import_report_show_a11y", "circuit" to group.circuit)))[0].performClick()
        waitForIdle()
        settle(300)
        screenshot("c109-2-shown-in-editor")
        val after = control.exportViewState()
        assertFalse(exists("editor-import-report"), "Show closes the report")
        assertEquals(group.componentIds.toSet(), after?.selectedComponentIds, "Show selects the group's components")
        assertTrue(before?.viewports != after?.viewports, "Show frames them: ${before?.viewports} -> ${after?.viewports}")
    }

    private fun adjustments(count: Int) = LocalizedText("import_report_status", quantity = count.toLong()).resolve(language)
    private fun ComposeUiTest.statusText(): String =
        onNodeWithTag("editor-status").config().getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
    private val details get() = androidx.compose.ui.test.hasContentDescription(t("import_report_details_a11y"))

    // C145: a file whose adjustments only record how it was read opens without the report. The status line says how
    // many there were, politely, and its Details action opens the report; the File menu item stays.
    @Test
    fun informationalAdjustmentsOpenWithAStatusInsteadOfTheReport() = runDesktopComposeUiTest(390, 844) {
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacy))
        assertEquals(setOf("version-defaults"), imported.migrations.map { it.rule }.toSet())
        app(legacy)
        settle(300)
        screenshot("c145-1-informational-opened")
        assertFalse(exists("editor-import-report"), "Version defaults alone do not open the report")
        assertEquals(adjustments(imported.migrations.size), statusText())
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite,
            onNodeWithTag("editor-status").config().getOrNull(SemanticsProperties.LiveRegion), "The status is announced politely")
        onNode(details).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        waitForTag("editor-import-report")
        settle(300)
        screenshot("c145-2-details-opened")
        onNodeWithText(t(dev.logisim.mobile.core.localization.Messages.ActionClose)).performClick()
        waitForIdle()
        assertFalse(exists("editor-import-report"))
        menu("file", "import-report")
        waitForTag("editor-import-report")
    }

    // C145: renaming a label changes what the user sees, so the report still opens by itself.
    @Test
    fun aBehaviorChangingAdjustmentStillOpensTheReport() = runDesktopComposeUiTest(390, 844) {
        // Evolution renames labels it refuses when it opens its own files; the original 2.7.1 keeps them.
        val renamed = legacy.replace("source=\"2.7.1\"", "source=\"3.8.0\"")
            .replace("<a name=\"label\" val=\"A\"/>", "<a name=\"label\" val=\"entrada 1\"/>")
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(renamed))
        assertTrue("evolution-label-renamed" in imported.migrations.map { it.rule }, imported.migrations.toString())
        app(renamed)
        waitForTag("editor-import-report")
        settle(300)
        screenshot("c145-3-behavior-change-opens-report")
        assertFalse(exists("import-report-status-details"), "No status action while the report is open")
    }

    // C145: the first project of a new workspace (the AND demo) and a legacy file opened into it greet the user with
    // the editor, not with the report; the file's status names it.
    @Test
    fun theWorkspaceOpensInformationalProjectsWithoutTheReport() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val files = Files()
        host(storage, documents = files)
        settle(800)
        screenshot("c145-4-first-project")
        assertFalse(exists("editor-import-report"), "The AND demo opens without the report")
        val demoAdjustments = checkNotNull(Regex("\\d+").find(statusText())).value.toInt()
        assertEquals(adjustments(demoAdjustments), statusText(), "Its version defaults are named in the status line")
        assertTrue(exists("import-report-status-details"))
        val reference = DocumentReference("bookmark-antigo", "Antigo.circ", writable = true, revision = "r1",
            mode = DocumentOpenMode.EDIT_IN_PLACE)
        runOnIdle { files.deliver(reference, legacy) }
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.size == 2 }
        waitForTag("editor-canvas")
        settle(1_200)
        screenshot("c145-5-legacy-file-opened")
        assertFalse(exists("editor-import-report"), "A version-defaults file opens without the report")
        val count = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(legacy)).migrations.size
        val expected = LocalizedText("import_report_status_file", quantity = count.toLong(),
            localizedArguments = mapOf("name" to LocalizedText.literal("Antigo.circ"))).resolve(language)
        assertEquals(expected, statusText())
        onNode(details).performClick()
        waitForTag("editor-import-report")
        assertTrue(hasText(t("import_report_origin", "origin" to "Antigo.circ")), "The report is the file's")
    }

    private class MergeAssets(private val asset: DocumentAsset) : DocumentAssetActions {
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) = onResult(AssetOpenResult.Opened(asset))
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = Unit
    }

    private val library = """<?xml version="1.0" encoding="UTF-8"?>
<project source="3.8.0" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp loc="(200,100)" name="helper"/>
  </circuit>
  <circuit name="helper">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="X"/></comp>
  </circuit>
  <circuit name="extra">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="Y"/></comp>
  </circuit>
</project>
"""

    // C102: Merge project shows dependencies and name conflicts, with full-width rows.
    @Test
    fun mergeProjectShowsDependenciesAndConflicts() = runDesktopComposeUiTest(360, 760) {
        app(circuit, MergeAssets(DocumentAsset("library.circ", "application/octet-stream", library.encodeToByteArray())))
        menu("file", "merge-project")
        waitForText(t("project_merge_choose"))
        onNodeWithText(t("project_merge_choose")).performClick()
        waitForText("helper", substring = true)
        settle(300)
        screenshot("c102-1-merge-dialog")
        val shown = texts()
        assertTrue(shown.any { "main" in it && "main_2" in it }, "The conflict main → main_2 is listed: $shown")
        assertTrue(shown.any { "helper" in it && it != "helper" }, "main lists what it uses")
        assertTrue(hasText("library.circ", substring = true), "The loaded file is named")
        onAllNodes(androidx.compose.ui.test.hasTestTag("project-merge-circuit")).fetchSemanticsNodes().also {
            assertEquals(3, it.size, "One row per circuit")
        }
        for (index in 0 until 3) onAllNodesWithTag("project-merge-circuit")[index].assertHeightIsAtLeast(48.dp)
    }
}
