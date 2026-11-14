package dev.logisim.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
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
import dev.logisim.mobile.core.localization.message
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The project workspace on a 390 dp phone (Skia, as on iOS), driven through its controls: trashing the open project,
 * closing tabs, a failing store and the Projects sheet. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceHostTest {
    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        private val lock = ReentrantLock()
        @Volatile var failCatalogWrites = false
        override fun <T> withExclusiveAccess(action: () -> T): T = lock.withLock(action)
        override fun read(name: String): String? = lock.withLock { files[name] }
        override fun listNames(): List<String> = lock.withLock { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) = lock.withLock {
            check(!failCatalogWrites || name != WorkspaceCatalogRepository.CATALOG_FILE) { "No space left on device" }
            files[name] = contents
        }
    }

    /** A file provider that edits in place; each save waits for [finish]. */
    private class Files : DocumentActions {
        var retained: DocumentReference? = null
        val saved = mutableListOf<Pair<DocumentReference, String>>()
        var pending: ((DocumentWriteResult) -> Unit)? = null
        override val currentReference get() = retained
        override fun retain(reference: DocumentReference?) { retained = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) = Unit
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) = Unit
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) {
            saved += reference to text; pending = onResult
        }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
        fun finish(result: DocumentWriteResult) { val callback = pending; pending = null; callback?.invoke(result) }
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

    /** Creates the projects oldest first, so the last one is the newest and the one the window opens. */
    private fun seed(storage: WorkspaceCatalogStorage, vararg names: String): List<WorkspaceProjectId> {
        var time = 1_000_000L
        val seeding = repository(storage, clock = { time++ })
        return names.map { name -> assertIs<WorkspaceMutationResult.Success>(seeding.create(name, circuit)).document.info.id }
    }

    private fun catalogDump(storage: WorkspaceCatalogStorage): String = repository(storage).list(includeDeleted = true).projects
        .joinToString("\n") { "${it.name}\trevision=${it.revision}\tdeleted=${it.deleted}" }

    private fun ComposeUiTest.host(storage: WorkspaceCatalogStorage, lifecycle: TestLifecycle = TestLifecycle(),
                                   documents: DocumentActions? = null) {
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

    private fun ComposeUiTest.waitForTag(tag: String, timeout: Long = 10_000) =
        waitUntil(timeoutMillis = timeout) { exists(tag) }

    private fun ComposeUiTest.waitForText(text: String, substring: Boolean = false, timeout: Long = 10_000) =
        waitUntil(timeoutMillis = timeout) { hasText(text, substring) }

    /** Waits for real time while the storage worker runs, letting composition advance. */
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

    /** An edit through the shipping menus: Edit > Select all, then Edit > Delete. */
    private fun ComposeUiTest.deleteEverything() {
        menu("edit", "select-all")
        menu("edit", "delete")
    }

    /** Opens a row's action from its overflow menu (or, on the original layout, its inline button). */
    private fun ComposeUiTest.rowAction(id: WorkspaceProjectId, key: String) {
        val overflow = "workspace-project-actions-${id.value}"
        if (exists(overflow)) {
            onNodeWithTag(overflow).performClick()
            waitForText(t(key))
        }
        onNodeWithText(t(key)).performClick()
        waitForIdle()
    }

    /** Opens a project from its row (or, on the original layout, the `index`th Open button). */
    private fun ComposeUiTest.openRow(id: WorkspaceProjectId, index: Int) {
        val row = "workspace-project-${id.value}"
        if (exists(row)) onNodeWithTag(row).performClick()
        else onAllNodesWithText(t("action_open"))[index].performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.closeDialogShown() = exists("workspace-close-session-dialog")

    private fun stored(storage: WorkspaceCatalogStorage, id: WorkspaceProjectId) =
        assertIs<WorkspaceOpenResult.Success>(repository(storage).open(id)).document

    @Test
    fun trashingTheOpenProjectInventsNoProject() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        val lifecycle = TestLifecycle()
        host(storage, lifecycle)
        openProjects()
        screenshot("trash-1-projects-sheet")
        rowAction(adder, "workspace_move_to_trash")
        settle(1_500)
        screenshot("trash-2-after-moving-the-open-project")
        // Leave the app, which flushes the workspace (the reported autosave path).
        runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        settle(1_000)
        val dump = catalogDump(storage)
        File(HostScreenshots.directory.also { it.mkdirs() }, "trash-catalog-after-background.txt").writeText(dump + "\n")
        runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        settle(500)
        val projects = repository(storage).list(includeDeleted = true).projects
        assertEquals(listOf("Somador"), projects.map { it.name }, "Trashing the open project must not create a project:\n$dump")
        assertTrue(projects.single().deleted)
        assertFalse(exists("editor-canvas"), "No editor (and no demo draft) is left mounted")
        assertTrue(hasText(t("workspace_projects")), "The empty window offers the project list")
    }

    @Test
    fun trashingTheOpenProjectCanBeUndone() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        host(storage)
        openProjects()
        rowAction(adder, "workspace_move_to_trash")
        waitForText(t("workspace_moved_to_trash", "name" to "Somador"))
        screenshot("trash-3-undo-snackbar")
        onNodeWithText(t("workspace_undo")).performClick()
        waitUntil(timeoutMillis = 10_000) { !repository(storage).list(includeDeleted = true).projects.single().deleted }
        waitForTag("editor-canvas")
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().activeProjectId == adder }
        waitForText("Somador", substring = true)
        settle(300)
        screenshot("trash-4-after-undo")
        assertEquals(listOf("Somador"), repository(storage).list(includeDeleted = true).projects.map { it.name })
    }

    @Test
    fun trashingTheOpenTabHandsTheEditorToItsNeighbour() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, adder) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        openRow(counter, index = 1)
        waitForTag("workspace-close-session-${counter.value}")
        settle(500)
        // Back to the first tab, so the trashed one has a tab after it.
        onNodeWithTag("workspace-session-${adder.value}").performClick()
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().activeProjectId == adder }
        settle(500)
        openProjects()
        rowAction(adder, "workspace_move_to_trash")
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().activeProjectId == counter }
        waitForTag("editor-canvas")
        settle(500)
        screenshot("trash-5-neighbour-tab")
        assertEquals(listOf("Contador", "Somador"), repository(storage).list(includeDeleted = true).projects.map { it.name }.sorted())
    }

    @Test
    fun closingAnUntouchedTabAsksNothing() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, adder) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        openRow(counter, index = 1)
        waitForTag("workspace-close-session-${counter.value}")
        settle(800)
        screenshot("close-1-two-tabs")
        onNodeWithTag("workspace-close-session-${counter.value}").performClick()
        settle(500)
        screenshot("close-2-untouched-tab")
        assertFalse(closeDialogShown(), "An untouched tab closes without a Save/Discard question")
        waitUntil(timeoutMillis = 10_000) { !exists("workspace-session-tabs") }
        assertEquals(adder, repository(storage).list().activeProjectId)
    }

    @Test
    fun closingAnEditedTabOffersToKeepTheChanges() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, _) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        openRow(counter, index = 1)
        waitForTag("workspace-close-session-${counter.value}")
        settle(800)
        deleteEverything()
        settle(1_000)
        assertFalse(stored(storage, counter).xml.contains("NOT Gate"), "The edit was autosaved")
        onNodeWithTag("workspace-close-session-${counter.value}").performClick()
        waitForTag("workspace-close-session-dialog")
        screenshot("close-3-edited-tab-dialog")
        onNodeWithText(t("workspace_close_keep_changes")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("workspace-session-tabs") }
        assertFalse(stored(storage, counter).xml.contains("NOT Gate"), "Keep changes keeps the autosaved edit")
    }

    @Test
    fun aFailingStoreShowsAPersistentBannerWithRetry() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        host(storage)
        settle(800)
        storage.failCatalogWrites = true
        deleteEverything()
        settle(1_500)
        screenshot("save-failure-1-banner")
        waitForTag("workspace-save-banner")
        onNodeWithTag("workspace-save-banner").assertExists()
        assertTrue(hasText(t("workspace_banner_retry")), "Retry is offered without opening Projects")
        assertTrue(hasText(t("workspace_not_saved"), substring = true), "The header says the edit is not saved")

        storage.failCatalogWrites = false
        onNodeWithText(t("workspace_banner_retry")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("workspace-save-banner") }
        settle(500)
        screenshot("save-failure-2-after-retry")
        assertFalse(stored(storage, adder).xml.contains("NOT Gate"), "Retry saved the edit")
    }

    @Test
    fun renameSelectsTheNameAndSubmitsFromTheKeyboard() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        host(storage)
        openProjects()
        rowAction(adder, "action_rename")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        settle(300)
        screenshot("rename-1-dialog")
        val field = onAllNodes(hasSetTextAction())[0]
        val config = field.fetchSemanticsNode().config
        assertEquals(true, config.getOrNull(SemanticsProperties.Focused), "The name field takes focus")
        assertEquals(TextRange(0, "Somador".length), config.getOrNull(SemanticsProperties.TextSelectionRange),
            "The old name is selected, so typing replaces it")
        assertTrue(config.getOrNull(SemanticsActions.OnImeAction) != null, "Done on the keyboard submits")
        field.performTextInput("Somador completo")
        waitForIdle()
        screenshot("rename-2-typed")
        field.performImeAction()
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().projects.single().name == "Somador completo" }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertEquals(adder, repository(storage).list().activeProjectId)
    }

    @Test
    fun projectRowsKeepSecondaryActionsInALabelledMenu() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, adder) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        screenshot("rows-1-projects-sheet")
        val row = onNodeWithTag("workspace-project-${counter.value}")
        assertEquals(t("workspace_open_named", "name" to "Contador"),
            row.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)
        val actions = onNodeWithTag("workspace-project-actions-${counter.value}")
        actions.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertEquals(listOf(t("workspace_more_actions_named", "name" to "Contador")),
            actions.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(hasText(t("workspace_currently_open")), "The open project is marked as currently open")
        assertFalse(hasText(t("action_open")), "Rows open on tap instead of a separate Open button")
        onNodeWithTag("workspace-project-actions-${adder.value}").performClick()
        waitForText(t("workspace_move_to_trash"))
        screenshot("rows-2-overflow-menu")
        onNodeWithText(t("action_rename")).assertExists()
        onNodeWithText(t("action_duplicate")).assertExists()
    }

    @Test
    fun tappingARowOpensItAndTheTrashLabelToggles() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, _) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        onNodeWithText(t("workspace_show_trash")).performClick()
        waitForText(t("workspace_trash_empty"))
        screenshot("rows-3-trash-shown")
        onNodeWithText(t("workspace_show_trash")).performClick()
        waitForText("Contador")
        openRow(counter, index = 1)
        waitUntil(timeoutMillis = 10_000) { repository(storage).list().activeProjectId == counter }
        waitForTag("workspace-session-tabs")
    }

    /** A project linked to a file it edits in place, as after opening it in place and relaunching the app. */
    private fun linked(storage: WorkspaceCatalogStorage, id: WorkspaceProjectId): DocumentReference {
        val reference = DocumentReference("bookmark-${id.value}", "Somador.circ", writable = true, revision = "r1",
            mode = DocumentOpenMode.EDIT_IN_PLACE)
        StoredWorkspaceDocumentLinks(storage).write(WorkspaceExternalDocument(id, reference, workspaceContentStamp(stored(storage, id).xml)))
        return reference
    }

    private fun ComposeUiTest.closeFromProjects() {
        openProjects()
        onNodeWithText(t("workspace_close_session")).performClick()
        waitForIdle()
    }

    @Test
    fun aRestoredLinkedTabNeverClosesWithoutOfferingToSaveItsFile() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        val reference = linked(storage, adder)
        val files = Files()
        host(storage, documents = files)
        settle(800)
        closeFromProjects()
        waitForTag("workspace-close-session-dialog")
        screenshot("linked-1-restored-tab-dialog")
        val save = t("workspace_close_save_to_file", "file" to "Somador.circ")
        assertTrue(hasText(save), "This window never saw the file match the project, so closing offers to save it")
        assertTrue(hasText(t("workspace_close_dont_save_to_file")))
        assertFalse(hasText(t("workspace_close_revert_to_opened", "elapsed" to t("workspace_opened_just_now"))),
            "A linked file is saved or left as it is, not reverted")
        onNodeWithText(t("workspace_close_dont_save_to_file")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("editor-canvas") }
        assertEquals(emptyList(), files.saved, "Don't save to file writes nothing")

        // Reopened from the catalog, the window still cannot vouch for the file: Save writes it.
        onNodeWithText(t("workspace_projects")).performClick()
        waitForText(t("workspace_new_project"))
        openRow(adder, index = 0)
        waitForTag("editor-canvas")
        settle(800)
        closeFromProjects()
        waitForTag("workspace-close-session-dialog")
        onNodeWithText(save).performClick()
        waitUntil(timeoutMillis = 10_000) { files.pending != null }
        assertEquals(reference, files.saved.single().first)
        assertEquals(stored(storage, adder).xml, files.saved.single().second)
        runOnIdle { files.finish(DocumentWriteResult.Saved(reference.copy(revision = "r2"))) }
        waitUntil(timeoutMillis = 10_000) { !exists("editor-canvas") }
        assertFalse(closeDialogShown())
    }

    @Test
    fun revertingAnEditThatFailedToSaveSaysSoAndCanBeUndone() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, _) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        openRow(counter, index = 1)
        waitForTag("workspace-close-session-${counter.value}")
        settle(800)
        val opened = stored(storage, counter).xml
        storage.failCatalogWrites = true
        deleteEverything()
        waitForTag("workspace-save-banner")
        onNodeWithTag("workspace-close-session-${counter.value}").performClick()
        waitForTag("workspace-close-session-dialog")
        screenshot("close-4-unsaved-edit-dialog")
        assertTrue(hasText(t("workspace_close_unsaved_explanation")), "The dialog says the edits are not saved")
        assertFalse(hasText(t("workspace_close_changed_explanation")), "It does not claim they are already saved")
        onNodeWithText(t("workspace_close_revert_to_opened", "elapsed" to t("workspace_opened_just_now"))).performClick()
        waitForText(t("workspace_reverted", "name" to "Contador"))
        screenshot("close-5-reverted-undo-snackbar")
        assertFalse(exists("workspace-session-tabs"), "The tab closed; the other project took the editor")
        assertEquals(opened, stored(storage, counter).xml)

        storage.failCatalogWrites = false
        onNodeWithText(t("workspace_undo")).performClick()
        waitUntil(timeoutMillis = 10_000) { !stored(storage, counter).xml.contains("NOT Gate") }
        assertTrue(stored(storage, counter).xml != opened, "Undo saved the edit that never reached storage")
    }

    @Test
    fun aConflictOffersReloadInsteadOfRetry() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (adder) = seed(storage, "Somador")
        host(storage)
        settle(800)
        // Another window saves a newer revision first.
        val current = stored(storage, adder)
        val elsewhere = HostScreenshots.fixture("fixtures/user-scenarios/fpga-and.circ")
        assertIs<WorkspaceMutationResult.Success>(repository(storage).save(adder, current.info.revision, elsewhere))
        deleteEverything()
        waitForTag("workspace-save-banner")
        settle(300)
        screenshot("conflict-1-banner")
        assertTrue(hasText(t("workspace_banner_reload")), "Reload resolves a conflict")
        assertFalse(hasText(t("workspace_banner_retry")), "Retrying cannot")
        assertTrue(hasText(t("workspace_banner_save_copy")))
        assertTrue(hasText(t("workspace_saved_elsewhere"), substring = true))

        closeFromProjects()
        waitForTag("workspace-close-session-dialog")
        screenshot("conflict-2-close-dialog")
        assertTrue(hasText(t("workspace_close_unsaved_explanation")))
        assertFalse(hasText(t("workspace_close_revert_to_opened", "elapsed" to t("workspace_opened_just_now"))),
            "A conflict refuses the revert, so it is not offered")
        onNodeWithText(t("action_cancel")).performClick()
        waitUntil(timeoutMillis = 10_000) { !closeDialogShown() }
        onNodeWithText(t("workspace_banner_reload")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("workspace-save-banner") }
        waitForTag("editor-canvas")
    }

    @Test
    fun undoingTheTrashOfAnInactiveTabBringsTheTabBack() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        val (counter, adder) = seed(storage, "Contador", "Somador")
        host(storage)
        openProjects()
        openRow(counter, index = 1)
        waitForTag("workspace-session-${adder.value}")
        settle(500)
        openProjects()
        rowAction(adder, "workspace_move_to_trash")
        waitForText(t("workspace_moved_to_trash", "name" to "Somador"))
        assertFalse(exists("workspace-session-${adder.value}"))
        onNodeWithText(t("workspace_undo")).performClick()
        waitForTag("workspace-session-${adder.value}")
        settle(300)
        screenshot("trash-6-inactive-tab-restored")
        assertEquals(counter, repository(storage).list().activeProjectId, "Undo does not switch away from the open tab")
        assertFalse(repository(storage).list(includeDeleted = true).projects.single { it.id == adder }.deleted)
    }

    @Test
    fun aRefusedRenameKeepsTheDialogOpenWithTheReason() = runDesktopComposeUiTest(390, 844) {
        val storage = MemoryStorage()
        // Somador is open; Contador is renamed from the list while another window renames it too.
        val (counter, _) = seed(storage, "Contador", "Somador")
        host(storage)
        settle(500)
        openProjects()
        val stale = stored(storage, counter).info.revision
        assertIs<WorkspaceMutationResult.Success>(repository(storage).rename(counter, stale, "Contador (outra janela)"))
        rowAction(counter, "action_rename")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val field = onAllNodes(hasSetTextAction())[0]
        field.performTextInput("Meu contador")
        field.performImeAction()
        val reason = t(dev.logisim.mobile.core.localization.Messages.WorkspaceConflictSaved)
        waitForText(reason)
        settle(300)
        screenshot("rename-3-refused")
        assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty(), "The dialog stays open")
        fun name() = repository(storage).list().projects.single { it.id == counter }.name
        assertEquals("Contador (outra janela)", name())

        // The sheet lists the notice once, with a 48 dp dismiss button.
        onNodeWithText(t("action_cancel")).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertEquals(1, onAllNodesWithText(reason).fetchSemanticsNodes().size)
        val dismiss = onNodeWithContentDescription(t("workspace_dismiss_notice"))
        dismiss.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        dismiss.performClick()
        waitUntil(timeoutMillis = 10_000) { !hasText(reason) }

        // A rename that succeeds clears it; refused again later, the notice is shown again.
        fun rename(name: String) {
            rowAction(counter, "action_rename")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasSetTextAction())[0].apply { performTextInput(name); performImeAction() }
        }
        rename("Meu contador")
        waitUntil(timeoutMillis = 10_000) { name() == "Meu contador" }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertIs<WorkspaceMutationResult.Success>(repository(storage).rename(counter, stored(storage, counter).info.revision, "Outro nome"))
        rename("Mais um nome")
        waitForText(reason)
        onNodeWithText(t("action_cancel")).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        assertTrue(hasText(reason), "A notice that went away and came back is shown again")
    }
}
