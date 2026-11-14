package dev.logisim.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ProjectBuiltinLibraries
import dev.logisim.mobile.core.WorkspaceCatalogRepository
import dev.logisim.mobile.core.WorkspaceCatalogStorage
import dev.logisim.mobile.core.WorkspaceMutationResult
import dev.logisim.mobile.core.WorkspaceProjectId
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The explorer and library panel (W2-01: C18, C26, C29, C138, C152, X8) driven through the whole App at phone
 * (390x844) and tablet (1180x820) size. Each criterion is checked and recorded in `criteria-<test>.txt` before the
 * test fails, so a run on the original code lists every criterion it misses. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryExplorerHostTest {
    private val english = AppLanguage.ENGLISH
    private val portuguese = AppLanguage.PORTUGUESE

    /** The editing fixture with every built-in library declared, as a project made from the default template. */
    private val fixture: String = run {
        val project = assertIs<CircProjectImportResult.Success>(
            CircCodec.readProject(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))).project
        CircCodec.writeProject(ProjectBuiltinLibraries.withDefaults(project, ComponentCatalog.registry), ComponentCatalog.registry)
    }

    private class Criteria(private val name: String) {
        private val results = mutableListOf<Pair<String, Boolean>>()
        fun check(what: String, ok: Boolean) { results += what to ok }
        fun check(what: String, ok: () -> Boolean) = check(what, try { ok() } catch (failure: Throwable) { false })
        fun verify() {
            HostScreenshots.directory.mkdirs()
            File(HostScreenshots.directory, "criteria-$name.txt")
                .writeText(results.joinToString("\n", postfix = "\n") { (what, ok) -> "${if (ok) "PASS" else "FAIL"}\t$what" })
            val failed = results.filterNot { it.second }.map { it.first }
            assertTrue(failed.isEmpty(), "Unmet criteria:\n" + failed.joinToString("\n"))
        }
    }

    private fun t(key: String, vararg arguments: Pair<String, Any?>, language: AppLanguage = english) =
        message(key, *arguments).resolve(language)

    private fun insert(label: String, language: AppLanguage = english) = t("a11y_insert_component", "name" to label, language = language)

    private fun ComposeUiTest.app(state: EditorState, language: AppLanguage = english) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { App(initialXml = fixture, language = language, workspaceHeader = header) }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.described(description: String) =
        onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun SemanticsNodeInteraction.state(): String? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun SemanticsNodeInteraction.role(): Role? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)
    private fun SemanticsNodeInteraction.selected(): Boolean? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)
    private fun SemanticsNodeInteraction.tall() = getUnclippedBoundsInRoot().let { it.bottom - it.top } >= 48.dp
    private fun SemanticsNodeInteraction.displayed() = try { assertIsDisplayed(); true } catch (_: AssertionError) { false }

    /** Scrolls the library list to [description] (a lazy list after W2-01, a scrolling column before). */
    private fun ComposeUiTest.reveal(description: String) {
        if (exists("library-list")) onNodeWithTag("library-list").performScrollToNode(hasContentDescription(description))
        else onNodeWithContentDescription(description).performScrollTo()
        waitForIdle()
    }

    /** Whether the list holds [description], scrolling through a lazy list to find it. */
    private fun ComposeUiTest.reachable(description: String): Boolean =
        if (exists("library-list")) runCatching { reveal(description) }.isSuccess && described(description) else described(description)

    /** Whether any library row or heading shows [text], scrolling through a lazy list to find it. */
    private fun ComposeUiTest.shown(text: String): Boolean =
        if (exists("library-list")) runCatching { onNodeWithTag("library-list").performScrollToNode(hasText(text)) }.isSuccess
        else onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.revealTag(tag: String) {
        if (exists("library-list")) onNodeWithTag("library-list").performScrollToNode(hasTestTag(tag))
        else onNodeWithTag(tag).performScrollTo()
        waitForIdle()
    }

    private fun ComposeUiTest.openLibrarySheet() {
        onNodeWithTag("tool-library").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("editor-library-sheet") }
        waitForIdle()
    }

    private fun ComposeUiTest.libraryTab() = onNodeWithContentDescription(t("a11y_show_components"))

    @Test
    fun phoneLibraryOpensOnComponentsAndKeepsItsPlace() = runDesktopComposeUiTest(390, 844) {
        val c = Criteria("phone-library")
        val state = EditorState(fixture)
        app(state)
        openLibrarySheet()
        screenshot("phone-1-library-opened")
        c.check("C18 the phone Library button opens the Library tab") { libraryTab().selected() == true }
        if (libraryTab().selected() != true) { libraryTab().performClick(); waitForIdle() }
        c.check("C29 explorer tabs are at least 48 dp tall") { libraryTab().tall() }
        c.check("C18 an inline filter field heads the Library tab") { exists("library-filter") }
        c.check("C18 'Manage libraries' stays the first entry of the list") {
            onNodeWithText(t("active_library_manage") + "…").displayed()
        }
        c.check("C18 group headers are buttons with an expanded/collapsed state") {
            revealTag("library-group-Gates")
            onNodeWithTag("library-group-Gates").let { it.role() == Role.Button && it.state() == t("a11y_state_expanded") }
        }
        c.check("C29/C152 library rows are 48 dp buttons") {
            reveal(insert("AND"))
            onNodeWithContentDescription(insert("AND")).let { it.tall() && it.role() == Role.Button }
        }
        c.check("C18 TTL starts collapsed") {
            revealTag("library-group-TTL")
            onNodeWithTag("library-group-TTL").state() == t("a11y_state_collapsed")
        }
        c.check("C18 a collapsed TTL group has no 7400 row anywhere in the list") { !reachable(insert("7400")) }

        // Place a component from the end of the Gates group: the sheet closes.
        reveal(insert("Controlled inverter"))
        onNodeWithContentDescription(insert("Controlled inverter")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists("editor-library-sheet") }
        c.check("Choosing a row arms the placement tool") { state.mode == EditorMode.PLACE }

        openLibrarySheet()
        screenshot("phone-2-reopened")
        c.check("C18 the reopened sheet keeps the Library tab") { libraryTab().selected() == true }
        c.check("C18 the reopened sheet shows the same row without scrolling") {
            onNodeWithContentDescription(insert("Controlled inverter")).displayed()
        }
        c.check("C29/C152 the armed placement tool's row is selected") {
            onAllNodesWithContentDescription(insert("Controlled inverter")).fetchSemanticsNodes()
                .let { rows -> rows.isNotEmpty() && rows.all { it.config.getOrNull(SemanticsProperties.Selected) == true } }
        }
        if (libraryTab().selected() != true) { libraryTab().performClick(); waitForIdle() }
        c.check("C18 'Recently used' lists the component just placed") {
            revealTag("library-group-recent")
            revealTag("library-recent-CONTROLLED_INVERTER")
            exists("library-group-recent") && exists("library-recent-CONTROLLED_INVERTER")
        }
        screenshot("phone-2b-recently-used")

        if (runCatching { revealTag("library-group-TTL") }.isSuccess) {
            revealTag("library-group-TTL")
            onNodeWithTag("library-group-TTL").performClick()
            waitForIdle()
            c.check("C18 expanding TTL announces the new state") { onNodeWithTag("library-group-TTL").state() == t("a11y_state_expanded") }
            reveal(insert("7400"))
            screenshot("phone-3-ttl-expanded")
            c.check("C26 TTL rows show a chip glyph, not a three-letter fragment") {
                onNodeWithContentDescription(insert("7400")).fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text } == listOf("7400")
            }
            revealTag("library-group-TTL")
            onNodeWithTag("library-group-TTL").performClick()
            waitForIdle()
            c.check("C18 collapsing TTL removes its rows") { !reachable(insert("7400")) }
        } else c.check("C18 TTL group can be expanded and collapsed", false)

        if (exists("library-filter")) {
            onNodeWithTag("library-filter").performTextInput("flip")
            waitForIdle()
            val rows = onAllNodes(androidx.compose.ui.test.SemanticsMatcher("library row") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(insert("")) } == true
            }).fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].first() }
            File(HostScreenshots.directory.also { it.mkdirs() }, "phone-filter-flip-rows.txt").writeText(rows.joinToString("\n", postfix = "\n"))
            screenshot("phone-4-filter-flip")
            c.check("C18 typing 'flip' leaves only flip-flop rows") {
                rows.size >= 4 && rows.all { it.contains("flip", ignoreCase = true) }
            }
        } else {
            screenshot("phone-4-no-filter")
            c.check("C18 typing 'flip' leaves only flip-flop rows", false)
        }
        c.verify()
    }

    @Test
    fun phoneLibraryIconsAndHeadingsInPortuguese() = runDesktopComposeUiTest(390, 844) {
        val c = Criteria("phone-library-pt")
        val state = EditorState(fixture)
        app(state, portuguese)
        openLibrarySheet()
        val tab = onNodeWithContentDescription(t("a11y_show_components", language = portuguese))
        if (tab.selected() != true) { tab.performClick(); waitForIdle() }
        screenshot("phone-pt-1-library")
        // Expand the groups whose rows used to show identifier fragments (C26).
        for (group in listOf("Plexers", "Arithmetic", "Memory")) if (exists("library-list") && runCatching { revealTag("library-group-$group") }.isSuccess) {
            if (onNodeWithTag("library-group-$group").state() == t("a11y_state_collapsed", language = portuguese)) {
                onNodeWithTag("library-group-$group").performClick()
                waitForIdle()
            }
        }
        for (heading in listOf("Seletores", "Aritmética de ponto flutuante", "Megafunções BFH", "Sistema em chip")) {
            c.check("C138 the heading '$heading' is localized") {
                if (exists("library-list")) onNodeWithTag("library-list").performScrollToNode(hasText(heading))
                else onNodeWithText(heading).performScrollTo()
                onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty()
            }
        }
        for (raw in listOf("Plexers", "FPArithmetic", "BFH-Praktika", "Soc")) {
            c.check("C138 no raw library id '$raw' is shown") { !shown(raw) }
        }
        if (exists("library-list")) revealTag("library-group-Plexers") else runCatching { onNodeWithText("Multiplexador").performScrollTo() }
        screenshot("phone-pt-2-plexers")
        for (fragment in listOf("MUL", "DEM", "PRI", "BIT", "JK_", "SR_", "T_F", "SHI")) {
            c.check("C26 no icon shows the identifier fragment '$fragment'") { !shown(fragment) }
        }
        if (exists("library-list")) runCatching { onNodeWithTag("library-list").performScrollToNode(hasContentDescription(insert("Flip-flop J-K", portuguese))) }
        else runCatching { onNodeWithContentDescription(insert("Flip-flop J-K", portuguese)).performScrollTo() }
        waitForIdle()
        screenshot("phone-pt-3-memory")
        c.verify()
    }

    @Test
    fun narrowPhoneWithLargeTextKeepsTheLibraryUsable() = runDesktopComposeUiTest(360, 640) {
        val c = Criteria("narrow-large-text")
        val state = EditorState(fixture)
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides portuguese,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                LogisimTheme(dark = false) { App(initialXml = fixture, language = portuguese, workspaceHeader = header) }
            }
        }
        waitForIdle()
        openLibrarySheet()
        val tab = onNodeWithContentDescription(t("a11y_show_components", language = portuguese))
        c.check("C18 the Library tab opens first at 360 dp") { tab.selected() == true }
        c.check("C29 the tabs stay at least 48 dp with large text") { tab.tall() }
        screenshot("narrow-1-library-large-text")
        if (exists("library-list")) {
            revealTag("library-group-Memory")
            onNodeWithTag("library-group-Memory").performClick()
            waitForIdle()
            reveal(insert("RAM de duas portas", portuguese))
        }
        c.check("C29 a row with a four-letter symbol stays at least 48 dp") { onNodeWithContentDescription(insert("RAM de duas portas", portuguese)).tall() }
        screenshot("narrow-2-memory-large-text")
        c.verify()
    }

    @Test
    fun tabletSidebarRowsAreSelectableAndTheMainCircuitIsAnnounced() = runDesktopComposeUiTest(1180, 820) {
        val c = Criteria("tablet-sidebar")
        val state = EditorState(fixture)
        app(state)
        screenshot("tablet-1-sidebar")
        c.check("C18 the explorer opens on the Library tab") { libraryTab().selected() == true }
        if (libraryTab().selected() != true) { libraryTab().performClick(); waitForIdle() }
        c.check("C29 explorer tabs are at least 48 dp tall") { libraryTab().tall() }
        reveal(insert("AND"))
        val and = onNodeWithContentDescription(insert("AND"))
        c.check("C29 the AND row is at least 48 dp tall") { and.tall() }
        c.check("C29/C152 library rows have the button role") { and.role() == Role.Button }
        c.check("C29/C152 an unarmed row is not selected") { and.selected() == false }
        and.performClick()
        waitForIdle()
        c.check("Choosing AND arms the placement tool") { state.mode == EditorMode.PLACE && state.placingKind.name == "AND" }
        c.check("C29/C152 the armed placement tool's row is selected") { onNodeWithContentDescription(insert("AND")).selected() == true }
        screenshot("tablet-2-and-armed")

        onNodeWithContentDescription(t("a11y_show_tree")).performClick()
        waitForIdle()
        screenshot("tablet-3-circuits")
        c.check("C29/C152 the main circuit row says it is the main circuit") {
            onAllNodesWithContentDescription(t("a11y_open_definition", "name" to "main"), substring = true)
                .fetchSemanticsNodes().single().config[SemanticsProperties.ContentDescription].single()
                .contains("main circuit")
        }

        c.check("C29 a menu title says it is collapsed") { onNodeWithTag("menu-title-file").state() == t("a11y_state_collapsed") }
        onNodeWithTag("menu-title-file").performClick()
        waitForIdle()
        c.check("C29 an open menu title says it is expanded") { onNodeWithTag("menu-title-file").state() == t("a11y_state_expanded") }
        screenshot("tablet-4-file-menu")
        c.verify()
    }

    @Test
    fun phoneToolbarExpandersAnnounceTheirState() = runDesktopComposeUiTest(320, 640) {
        val c = Criteria("phone-expanders")
        val state = EditorState(fixture)
        app(state)
        val overflow = "editor-command-toolbar-overflow"
        c.check("The phone command toolbar overflows") { exists(overflow) }
        c.check("C29 the toolbar overflow toggle says it is collapsed") { onNodeWithTag(overflow).state() == t("a11y_state_collapsed") }
        if (exists(overflow)) { onNodeWithTag(overflow).performClick(); waitForIdle() }
        c.check("C29 the toolbar overflow toggle says it is expanded") { onNodeWithTag(overflow).state() == t("a11y_state_expanded") }
        screenshot("phone-5-toolbar-expanded")
        if (exists(overflow)) { onNodeWithTag(overflow).performClick(); waitForIdle() }

        val menu = "editor-project-toolbar-overflow"
        c.check("C29 the compact menu button says it is collapsed") { onNodeWithTag(menu).state() == t("a11y_state_collapsed") }
        onNodeWithTag(menu).performClick()
        waitForIdle()
        c.check("C29 the compact menu button says it is expanded") {
            onAllNodesWithTag(menu).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.StateDescription) == t("a11y_state_expanded") }
        }
        screenshot("phone-6-compact-menu")
        c.verify()
    }

    @Test
    fun inspectorRowsSpeakTheirValue() = runDesktopComposeUiTest(390, 844) {
        val c = Criteria("phone-inspector")
        val state = EditorState(fixture)
        app(state)
        state.select(state.document.components.first { it.label == "E2E_IN" }.id)
        waitForIdle()
        onNodeWithTag("tool-properties").performClick()
        waitForIdle()
        val row = onNodeWithTag("inspector-row-labelloc")
        c.check("C152 the label location row speaks its value") { row.state() == "West" }
        c.check("C152 the label location row has a role") { row.role() != null }
        c.check("C152 the label location row is 48 dp tall") { row.tall() }
        screenshot("phone-7-inspector")
        c.verify()
    }

    @Test
    fun manageLibrariesListsEveryBuiltinLibraryAsACheckbox() = runDesktopComposeUiTest(390, 844) {
        val c = Criteria("manage-libraries")
        val state = EditorState(null).apply { newDocument() }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides portuguese) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { ActiveProjectLibrariesDialog(state, null) {} } }
            }
        }
        waitForIdle()
        screenshot("manage-1-default-template-pt")
        val libraries = ProjectBuiltinLibraries.available(state.componentRegistry)
        for (library in libraries) {
            val id = library.removePrefix("#")
            val loaded = ProjectBuiltinLibraries.includes(state.project, library)
            val tag = if (loaded) "active-library-remove-$id" else "active-library-add-$id"
            c.check("X8 $library is listed as a checkbox that is ${if (loaded) "on" else "off"}") {
                onNodeWithTag(tag).performScrollTo()
                val node = onNodeWithTag(tag)
                node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) ==
                    (if (loaded) ToggleableState.On else ToggleableState.Off) && node.role() == Role.Checkbox && node.tall()
            }
        }
        c.check("X8 no 'Show available libraries' toggle is left") { !exists("active-library-show-available") }
        for (name in listOf("Seletores", "Aritmética de ponto flutuante", "Megafunções BFH", "Sistema em chip", "HDL-IP")) {
            c.check("C138 Manage libraries names '$name'") {
                onNodeWithText(name).performScrollTo()
                true
            }
        }
        for (raw in listOf("Descarregar Plexers", "Descarregar FPArithmetic", "Descarregar BFH-Praktika", "Descarregar Soc")) {
            c.check("C138 no '$raw' button") { onAllNodesWithText(raw).fetchSemanticsNodes().isEmpty() }
        }

        // Unchecking and checking Plexers writes what Unload and Add wrote.
        val reference = EditorState(null).apply { newDocument() }
        val plexers = LocalizedText.literal("Plexers")
        assertTrue(reference.setBuiltinLibrary("#Plexers", false, plexers))
        val unloaded = reference.exportXml()
        assertTrue(reference.setBuiltinLibrary("#Plexers", true, plexers))
        val reloaded = reference.exportXml()
        onNodeWithTag("active-library-remove-Plexers").performScrollTo().performClick()
        waitForIdle()
        c.check("X8 unchecking Plexers unloads it like Unload did") { state.exportXml() == unloaded }
        c.check("X8 the unchecked Plexers row is still listed, off") {
            onNodeWithTag("active-library-add-Plexers").performScrollTo()
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }
        screenshot("manage-2-plexers-unchecked")
        c.check("X8 checking Plexers again loads it like Add did") {
            onNodeWithTag("active-library-add-Plexers").performScrollTo().performClick()
            waitForIdle()
            state.exportXml() == reloaded
        }
        c.check("X8 a library in use stays checked and explains why") {
            state.insertComponent(state.componentRegistry.resolve("#TTL", "7400", emptyMap())!!.kind, dev.logisim.mobile.core.GridPoint(300, 300))
            waitForIdle()
            onNodeWithTag("active-library-remove-TTL").performScrollTo().performClick()
            waitForIdle()
            onNodeWithTag("active-library-remove-TTL").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On &&
                state.statusText.key == "active_library_in_use" &&
                state.statusText.resolve(portuguese).contains("TTL")
        }
        c.check("C138 the in-use refusal names the library by its display name") {
            val used = EditorState(null).apply { newDocument() }
            assertTrue(used.insertComponent(dev.logisim.mobile.core.ComponentKind.AND, dev.logisim.mobile.core.GridPoint(400, 400)))
            used.setBuiltinLibrary("#Gates", false, LocalizedText.literal("Portas"))
            val refusal = used.statusText.resolve(portuguese)
            File(HostScreenshots.directory.also { it.mkdirs() }, "manage-in-use-refusal-pt.txt").writeText(refusal + "\n")
            used.statusText.key == "active_library_in_use" && refusal.contains("Portas") && !refusal.contains("Gates")
        }
        waitForIdle()
        screenshot("manage-3-in-use")
        c.verify()
    }

    private class MemoryStorage : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        private val lock = ReentrantLock()
        override fun <T> withExclusiveAccess(action: () -> T): T = lock.withLock(action)
        override fun read(name: String): String? = lock.withLock { files[name] }
        override fun listNames(): List<String> = lock.withLock { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) = lock.withLock { files[name] = contents }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun workspaceTabsAreTabsAndTheActiveOneIsNotANoOpButton() = runDesktopComposeUiTest(390, 844) {
        val c = Criteria("workspace-tabs")
        val storage = MemoryStorage()
        val circuit = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
        var time = 1_000_000L
        val repository = WorkspaceCatalogRepository(storage,
            validate = { CircCodec.readProject(it) is CircProjectImportResult.Success }, clock = { time++ })
        val (counter, adder) = listOf("Contador", "Somador").map { name ->
            assertIs<WorkspaceMutationResult.Success>(repository.create(name, circuit)).document.info.id
        }
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestLifecycle()) {
                CatalogWorkspaceHost(storage = storage, language = portuguese)
            }
        }
        waitUntil(timeoutMillis = 10_000) { exists("editor-canvas") }
        if (exists("editor-project-toolbar-overflow")) onNodeWithTag("editor-project-toolbar-overflow").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("menu-title-file") }
        onNodeWithTag("menu-title-file").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("menu-projects") }
        onNodeWithTag("menu-projects").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("workspace-project-${counter.value}") }
        onNodeWithTag("workspace-project-${counter.value}").performClick()
        waitUntil(timeoutMillis = 10_000) { exists("workspace-session-${adder.value}") && exists("workspace-session-${counter.value}") }
        waitForIdle()
        screenshot("workspace-1-tabs")
        fun tab(id: WorkspaceProjectId) = onNodeWithTag("workspace-session-${id.value}")
        val active = WorkspaceCatalogRepository(storage, validate = { true }).list().activeProjectId
        val (open, other) = if (active == counter) counter to adder else adder to counter
        c.check("C152 workspace tabs have the tab role") { tab(open).role() == Role.Tab && tab(other).role() == Role.Tab }
        c.check("C152 the active tab is selected, the other is not") { tab(open).selected() == true && tab(other).selected() == false }
        c.check("C152 the active tab offers no do-nothing click") {
            tab(open).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) == null
        }
        c.check("C152 the other tab can be opened") { tab(other).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) != null }
        c.check("C152 tabs are at least 48 dp tall") { tab(open).tall() && tab(other).tall() }
        tab(other).performClick()
        waitUntil(timeoutMillis = 10_000) { WorkspaceCatalogRepository(storage, validate = { true }).list().activeProjectId == other }
        waitForIdle()
        c.check("C152 choosing the other tab selects it") { tab(other).selected() == true && tab(open).selected() == false }
        screenshot("workspace-2-switched")
        c.verify()
    }
}
