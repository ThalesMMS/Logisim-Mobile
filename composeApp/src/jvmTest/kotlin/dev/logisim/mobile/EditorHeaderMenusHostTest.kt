package dev.logisim.mobile

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.logisim.mobile.core.CircuitExamples
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.ProjectBuiltinLibraries
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Wave 2 package W2-02 (editor header, status line, zoom and menus), driven through the whole editor at phone size
 * (390x844 dp) and tablet size (1180x820 dp). Each scenario writes what it observed to `behavior-w202-<scenario>.txt`
 * beside its screenshots (`-PuiScreenshots=<dir>`) before asserting, so a run on the original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class)
class EditorHeaderMenusHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private val hierarchy = checkNotNull(CircuitExamples.find("hierarchy")).xml
    private val english = AppLanguage.ENGLISH
    private val portuguese = AppLanguage.PORTUGUESE

    /** A file provider that records what the editor asks of it. */
    private class RecordingDocuments(override var currentReference: DocumentReference? = null) : DocumentActions {
        val created = mutableListOf<String>()
        var saves = 0
        override fun retain(reference: DocumentReference?) { currentReference = reference }
        override fun setOpenHandler(handler: ((DocumentOpenResult) -> Unit)?) = Unit
        override fun open(mode: DocumentOpenMode) = Unit
        override fun reopen(reference: DocumentReference) = Unit
        override fun create(suggestedName: String, text: String, onResult: (DocumentWriteResult) -> Unit) { created += suggestedName }
        override fun save(reference: DocumentReference, text: String, onResult: (DocumentWriteResult) -> Unit) { saves++ }
        override fun share(suggestedName: String, text: String, onResult: (DocumentShareResult) -> Unit) = Unit
    }

    /** Preferences kept in memory, as a device stores them. */
    private class MemoryPreferences(preferences: EditorPreferences) : PreferencesStorage {
        private var stored: String? = EditorPreferencesCodec.write(preferences)
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    /** A clipboard the test fills or empties, in place of the system one. */
    @Suppress("DEPRECATION")
    private class TestClipboard(var content: String? = null) : ClipboardManager {
        override fun getText(): AnnotatedString? = content?.let(::AnnotatedString)
        override fun setText(annotatedString: AnnotatedString) { content = annotatedString.text }
    }

    @Suppress("DEPRECATION")
    private fun ComposeUiTest.app(
        state: EditorState,
        language: AppLanguage = english,
        density: Float? = null,
        fontScale: Float = 1f,
        openProjects: (() -> Unit)? = null,
        documents: DocumentActions? = null,
        projectName: String = "",
        xml: String = fixture,
        preferences: EditorPreferences? = null,
        clipboard: ClipboardManager? = null,
        onOpenWorkspace: () -> Unit = {},
        probe: @Composable () -> Unit = {},
    ) {
        val header = EditorWorkspaceHeader(projectName, "", onOpenProjects = openProjects, fileName = projectName.ifBlank { null }).also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {},
                open = { _, _, _ -> onOpenWorkspace(); false })
        }
        val storage = preferences?.let(::MemoryPreferences)
        setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language,
                LocalDensity provides Density(density ?: current.density, fontScale),
                LocalClipboardManager provides (clipboard ?: LocalClipboardManager.current)) {
                LogisimTheme(dark = false) {
                    probe()
                    App(initialXml = xml, language = language, workspaceHeader = header, documents = documents,
                        preferencesStorage = storage)
                }
            }
        }
        waitForIdle()
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-w202-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun text(key: String, language: AppLanguage = english, vararg arguments: Pair<String, Any?>) =
        message(key, *arguments).resolve(language)

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /**
     * Activates [tag] through its accessibility click, as TalkBack does. A first mouse move on the host can show a
     * ChromeButton tooltip whose popup takes the click meant for the control.
     */
    private fun ComposeUiTest.activate(tag: String) {
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /** A menu item, scrolled into the open menu's viewport first. */
    private fun ComposeUiTest.tapMenu(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    /** Opens menu [id] from the menu bar or the compact menu, going back first when another menu is open. */
    private fun ComposeUiTest.openMenu(id: String) {
        if (exists("menu-back")) activate("menu-back")
        else if (exists("editor-project-toolbar-overflow")) activate("editor-project-toolbar-overflow")
        activate("menu-title-$id")
    }

    private fun ComposeUiTest.description(tag: String): String? =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun ComposeUiTest.shownText(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text }.orEmpty()

    /** The visible text of the menu item [tag], whose own Text node sits below the item. */
    private fun ComposeUiTest.menuText(tag: String): String =
        onNodeWithTag(tag).fetchSemanticsNode().let { node ->
            fun collect(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.children.flatMap(::collect)
            collect(node).joinToString(" ")
        }

    private fun ComposeUiTest.menuTags(prefix: String = "menu-"): List<String> = onAllNodes(SemanticsMatcher("menu entry") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun ComposeUiTest.statusLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("editor-status").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun ComposeUiTest.paneTitles(): List<String> = onAllNodes(SemanticsMatcher("pane") {
        it.config.contains(SemanticsProperties.PaneTitle)
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }

    private fun ComposeUiTest.headings(): List<String> = onAllNodes(isHeading()).fetchSemanticsNodes().map { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
    }

    // C22: the % button opens a popover with Fit, presets and a live slider; no keyboard opens until Custom… is chosen.
    @Test
    fun zoomPopoverOffersFitPresetsAndALiveSlider() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, portuguese)
        val fitted = state.viewport.view
        state.zoomBy(2f)
        waitForIdle()
        tap("tool-zoom-exact")
        screenshot("w202-zoom-popover-phone")
        val focusedFields = onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().size
        val popover = exists("editor-zoom-menu")
        val items = listOf("menu-zoom-popover-fit", "menu-zoom-popover-fit-selection", "menu-zoom-popover-100", "menu-zoom-popover-50", "menu-zoom-popover-200",
            "menu-zoom-popover-in", "menu-zoom-popover-out", "menu-zoom-popover-custom", "editor-zoom-menu-slider").filter { exists(it) }
        observe("zoom-popover", "popover" to popover, "items" to items, "focusedTextFields" to focusedFields,
            "dialog" to exists("editor-zoom-dialog"))
        assertEquals(0, focusedFields, "no text field takes focus, so no keyboard covers the zoom")
        assertTrue(popover, "the % button opens the zoom popover")
        assertEquals(9, items.size, "$items")
        // Fit is one tap away and fits the circuit as the View menu does.
        tapMenu("menu-zoom-popover-fit")
        assertTrue(state.viewport.fitted)
        assertEquals(fitted, state.viewport.view)
        assertFalse(exists("editor-zoom-menu"), "a preset closes the popover")
        // 100% and 200% presets.
        tap("tool-zoom-exact")
        tapMenu("menu-zoom-popover-200")
        assertEquals(2f, state.viewport.view.scale, 0.001f)
        // The slider zooms while it moves, with no Apply.
        tap("tool-zoom-exact")
        val before = state.viewport.view.scale
        onNodeWithTag("editor-zoom-menu-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitForIdle()
        val during = state.viewport.view.scale
        screenshot("w202-zoom-popover-slider")
        observe("zoom-popover", "popover" to popover, "items" to items, "focusedTextFields" to focusedFields,
            "scaleBeforeSlider" to before, "scaleAfterSlider" to during, "popoverOpenAfterSlider" to exists("editor-zoom-menu"))
        assertNotEquals(before, during)
        // The midpoint of the logarithmic track is the geometric mean of 5% and 1200%.
        assertEquals(kotlin.math.sqrt(5.0 * 1200.0).toFloat() / 100f, during, 0.01f)
        assertTrue(exists("editor-zoom-menu"), "the slider keeps the popover open")
        // Custom… opens the exact entry.
        tapMenu("menu-zoom-popover-custom")
        assertTrue(exists("editor-zoom-dialog"))
    }

    // C48: on a 3x screen, 100% shows one dp per circuit unit (3 px), as Evolution's 100% is one logical pixel.
    @Test
    fun zoomPercentCountsDensityIndependentPixels() = runDesktopComposeUiTest(1170, 2532) {
        val state = EditorState(fixture)
        app(state, english, density = 3f)
        val initialLabel = menuText("tool-zoom-exact")
        tap("tool-zoom-exact")
        if (exists("menu-zoom-popover-custom")) tapMenu("menu-zoom-popover-custom")
        onNodeWithTag("editor-zoom-percent").performTextClearance()
        onNodeWithTag("editor-zoom-percent").performTextInput("100")
        tap("editor-zoom-apply")
        val label = menuText("tool-zoom-exact")
        val a11y = description("tool-zoom-exact")
        screenshot("w202-zoom-density3-100")
        observe("zoom-density3", "initialLabel" to initialLabel, "labelAfter100" to label, "description" to a11y,
            "scalePx" to state.viewport.view.scale)
        assertEquals(3f, state.viewport.view.scale, 0.001f, "100% is 3 px per unit at density 3")
        assertEquals("100%", label)
        assertEquals(text(Messages.A11yZoomExact, english, "percent" to 100), a11y)
        // The 1200% ceiling counts dp too: 36 px per unit here.
        state.zoomBy(1000f)
        waitForIdle()
        observe("zoom-density3", "initialLabel" to initialLabel, "labelAfter100" to label, "description" to a11y,
            "scalePx" to 3f, "maxScalePx" to state.viewport.view.scale, "maxLabel" to menuText("tool-zoom-exact"))
        assertEquals(36f, state.viewport.view.scale, 0.01f)
        assertEquals("1200%", menuText("tool-zoom-exact"))
    }

    // C27: the header title is the circuit picker; the projects list stays one entry away in it.
    @Test
    fun headerTitleSwitchesCircuits() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(hierarchy)
        var projectsOpened = 0
        app(state, english, openProjects = { projectsOpened++ }, projectName = "Lab 3", xml = hierarchy)
        state.dismissImportReport()
        waitForIdle()
        val main = state.project.mainCircuit
        val other = state.project.circuits.first { it.id != main.id }
        activate("editor-header-title")
        screenshot("w202-header-circuit-picker")
        val picker = exists("editor-circuit-picker")
        val rows = state.project.circuits.filter { exists("circuit-picker-${it.id}") }.map { it.name }
        observe("header-picker", "projectsOpenedByTitle" to projectsOpened, "picker" to picker, "rows" to rows,
            "document" to state.document.name)
        assertEquals(0, projectsOpened, "the title does not leave the editor")
        assertTrue(picker)
        assertEquals(state.project.circuits.map { it.name }, rows)
        val current = onNodeWithTag("circuit-picker-${main.id}").fetchSemanticsNode().config
        assertEquals(true, current.getOrNull(SemanticsProperties.Selected))
        assertTrue(description("circuit-picker-${main.id}")?.contains(text("a11y_main_circuit", english, "name" to main.name)) == true,
            description("circuit-picker-${main.id}"))
        assertTrue(exists("menu-circuit-picker-add"))
        tapMenu("circuit-picker-${other.id}")
        assertEquals(other.name, state.document.name)
        assertEquals(0, projectsOpened)
        // Projects… in the picker still returns to the workspace list.
        activate("editor-header-title")
        tapMenu("menu-circuit-picker-projects")
        assertEquals(1, projectsOpened)
    }

    // C28: a long notice expands on tap to its whole text, and collapses again.
    @Test
    fun longStatusNoticeExpandsToItsWholeText() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        state.reportStatus(message("project_graph_unavailable"))
        waitForIdle()
        val collapsed = statusLayout()
        screenshot("w202-status-collapsed")
        val config = onNodeWithTag("editor-status").fetchSemanticsNode().config
        val clickable = config.contains(SemanticsActions.OnClick)
        val stateDescription = config.getOrNull(SemanticsProperties.StateDescription)
        if (clickable) tap("editor-status")
        val expanded = statusLayout()
        screenshot("w202-status-expanded")
        observe("status-expand", "collapsedOverflow" to collapsed.hasVisualOverflow, "clickable" to clickable,
            "stateDescription" to stateDescription, "expandedOverflow" to expanded.hasVisualOverflow,
            "expandedLines" to expanded.lineCount)
        assertTrue(collapsed.hasVisualOverflow, "the notice is longer than the line")
        assertTrue(clickable, "a cut notice can be opened")
        assertEquals(text("status_line_collapsed"), stateDescription)
        assertFalse(expanded.hasVisualOverflow, "the whole notice is shown")
        assertEquals(text("project_graph_unavailable"), expanded.layoutInput.text.text)
        assertEquals(text("status_line_expanded"),
            onNodeWithTag("editor-status").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        // A new notice starts collapsed again.
        state.reportStatus(message("project_graph_unavailable"))
        state.reportStatus(LocalizedText.literal("x".repeat(400)))
        waitForIdle()
        assertTrue(statusLayout().hasVisualOverflow)
    }

    // C30: Undo and Redo name the action they undo or redo, in the header and the Edit menu.
    @Test
    fun undoAndRedoNameTheirAction() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        val before = description("tool-undo")
        state.insertComponent(ComponentKind.AND, GridPoint(400, 300))
        waitForIdle()
        val label = state.undoHistory.first().label
        val undoDescription = description("tool-undo")
        openMenu("edit")
        screenshot("w202-edit-menu-undo")
        val undoText = menuText("menu-undo")
        val redoText = menuText("menu-redo")
        observe("undo-labels", "descriptionBefore" to before, "descriptionAfter" to undoDescription,
            "menuUndo" to undoText, "menuRedo" to redoText, "history" to label.resolve(english))
        assertEquals(text("a11y_undo_action", english, "action" to label), undoDescription)
        assertEquals(text("edit_undo_item", english, "action" to label), undoText)
        assertEquals(text("edit_cant_redo", english), redoText)
        tapMenu("menu-undo")
        assertEquals(text("a11y_redo_action", english, "action" to label), description("tool-redo"))
    }

    // C24, C146: Evolution's Project circuit commands, Help and examples under Help, Licenses straight to its dialog.
    @Test
    fun menusFollowEvolutionsGrouping() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        openMenu("help")
        screenshot("w202-help-menu")
        val help = menuTags()
        openMenu("project")
        screenshot("w202-project-menu")
        val project = menuTags().filter { it != "menu-back" && it.startsWith("menu-") && !it.startsWith("menu-title-") }
        val projectItems = onAllNodes(SemanticsMatcher("project items") {
            val tag = it.config.getOrNull(SemanticsProperties.TestTag)
            tag != null && tag.startsWith("menu-") && tag != "menu-back" && it.config.contains(SemanticsActions.OnClick)
        }).fetchSemanticsNodes().size
        openMenu("file")
        screenshot("w202-file-menu")
        val examples = HelpContent.examples.filter { exists("menu-example-${it.id}") }
        val exampleTitles = examples.map { menuText("menu-example-${it.id}") }
        openMenu("simulate")
        val simulate = menuTags()
        openMenu("fpga")
        val fpga = menuTags()
        observe("menus", "help" to help, "project" to project, "projectItems" to projectItems, "fileExamples" to exampleTitles,
            "simulate" to simulate, "fpga" to fpga)
        assertTrue("menu-project-tool-help-examples" in help, "$help")
        assertTrue("menu-legal-notices" in help, "$help")
        assertTrue(listOf("menu-add-circuit", "menu-move-circuit-up", "menu-move-circuit-down", "menu-set-main-circuit",
            "menu-remove-circuit").all { it in project }, "$project")
        assertFalse("menu-project-tool-help-examples" in project)
        assertTrue(projectItems <= 12, "the Project menu has $projectItems items")
        assertEquals(HelpContent.examples.map { it.title.resolve(english) }, exampleTitles)
        assertTrue(listOf("menu-project-tool-trace", "menu-project-tool-test-vectors").all { it in simulate }, "$simulate")
        assertTrue(listOf("menu-project-tool-fpga", "menu-project-tool-hdl-export").all { it in fpga }, "$fpga")
        // Licenses opens the dialog itself, not a sheet holding one more button.
        openMenu("help")
        tapMenu("menu-legal-notices")
        screenshot("w202-licenses")
        assertFalse(exists("editor-tool-sheet"))
        onNodeWithText(text(Messages.LegalTitle, english)).assertIsDisplayed()
    }

    // C24: the circuit commands are searchable from Find Action.
    @Test
    fun findActionFindsTheCircuitCommands() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        openMenu("help")
        tapMenu("menu-find-action")
        onNodeWithTag("editor-search-query").performTextInput("add circuit")
        mainClock.advanceTimeBy(500)
        waitForIdle()
        screenshot("w202-find-add-circuit")
        val found = exists("editor-search-result-command:project:add-circuit")
        observe("find-action", "addCircuit" to found)
        assertTrue(found)
        onNodeWithTag("editor-search-result-command:project:add-circuit").assertIsEnabled()
    }

    // C146: the gallery opens an example without a confirmation, since the copy leaves the project untouched.
    @Test
    fun galleryOpensAnExampleWithoutConfirmation() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        openMenu("help")
        if (!exists("menu-project-tool-help-examples")) openMenu("project")
        tapMenu("menu-project-tool-help-examples")
        onNodeWithText(text("help_examples", english)).performClick()
        waitForIdle()
        onAllNodesWithText(text("help_open_example", english))[0].performScrollTo().performClick()
        waitForIdle()
        screenshot("w202-gallery-open")
        val confirmation = onAllNodesWithText(text("help_confirm_open", english, "name" to message("help_example_and_title")))
            .fetchSemanticsNodes().isNotEmpty()
        observe("gallery-open", "confirmation" to confirmation, "status" to state.statusText.key)
        assertFalse(confirmation, "no confirmation for a separate copy")
    }

    // C159: each sheet announces its own title, and the library groups are headings.
    @Test
    fun sheetsAnnounceTheirTitleAndSections() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, portuguese)
        tap("tool-library")
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        onNode(hasContentDescription(text(Messages.A11yShowComponents, portuguese))).performClick()
        waitForIdle()
        val library = paneTitles()
        screenshot("w202-library-sheet")
        // The Library tab is a lazy list of collapsible groups (keyed by library descriptor, shown by its localized
        // name): each group's header is scrolled into view and must then be a heading.
        val descriptors = registryComponentGroups(state.componentRegistry, state.project).map { it.first }
        val groups = descriptors.map { ProjectBuiltinLibraries.displayName(it).resolve(portuguese) }
        val headings = (headings() + descriptors.flatMap { descriptor ->
            onNodeWithTag("library-list").performScrollToKey("group:$descriptor")
            waitForIdle()
            headings()
        }).distinct()
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        openMenu("file")
        tapMenu("menu-preferences")
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val preferences = paneTitles()
        val preferenceHeadings = headings()
        screenshot("w202-preferences-sheet")
        observe("sheet-titles", "library" to library, "preferences" to preferences, "libraryHeadings" to headings,
            "groups" to groups, "preferenceHeadings" to preferenceHeadings)
        assertTrue(text(Messages.ActionLibrary, portuguese) in library, "$library")
        assertTrue(text(Messages.PreferencesTitle, portuguese) in preferences, "$preferences")
        assertFalse(text("a11y_sheet_pane", portuguese) in library + preferences)
        assertTrue(groups.all { it in headings }, "groups $groups headings $headings")
        assertTrue(text(Messages.PreferencesTitle, portuguese) in preferenceHeadings, "$preferenceHeadings")
    }

    // C111: Import is disabled until the XML differs from the export; Copy is then the main action.
    @Test
    fun xmlDialogImportsOnlyChangedText() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        openMenu("file")
        tapMenu("menu-xml")
        screenshot("w202-xml-unchanged")
        val importButton = hasText(text(Messages.ActionImportXml, english)) and SemanticsMatcher("button") {
            it.config.contains(SemanticsActions.OnClick)
        }
        val unchangedEnabled = onNode(importButton, useUnmergedTree = false).fetchSemanticsNode().config
            .contains(SemanticsProperties.Disabled).not()
        observe("xml-dialog", "importEnabledUnchanged" to unchangedEnabled)
        onNode(importButton).assertIsNotEnabled()
        onNodeWithText(text("xml_edit", english)).performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput(" ")
        waitForIdle()
        screenshot("w202-xml-changed")
        onNode(importButton).assertIsEnabled()
    }

    // C140: Save to file… opens Save As for a project with no linked file, as Ctrl+S does.
    @Test
    fun saveToFileSavesAsWhenNoFileIsLinked() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val documents = RecordingDocuments()
        app(state, english, documents = documents, projectName = "Lab 3")
        openMenu("file")
        screenshot("w202-file-save")
        val enabled = !onNodeWithTag("menu-save").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        val label = menuText("menu-save")
        observe("save-file", "enabled" to enabled, "label" to label)
        assertTrue(enabled)
        assertEquals(text("action_save_file_picker", english), label)
        tapMenu("menu-save")
        assertEquals(listOf("Lab 3.circ"), documents.created)
        assertEquals(0, documents.saves)
    }

    // C31: Remove explains why a circuit in use cannot go; Add circuit… focuses its name and Done creates it.
    @Test
    fun circuitManagementExplainsAndCreates() = runDesktopComposeUiTest(1180, 820) {
        val state = EditorState(hierarchy)
        app(state, english, xml = hierarchy)
        state.dismissImportReport()
        waitForIdle()
        val used = state.project.circuits.first { it.id != state.project.mainCircuit.id }
        // The explorer opens on the Library tab (C18); circuit management lives in the Circuits tab beside it.
        activate("explorer-tab-project")
        onNode(hasContentDescription(text(Messages.A11yManageCircuit, english, "name" to used.name))).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        val deleteEnabled = !onNodeWithTag("circuit-delete-${used.id}").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        observe("circuit-management", "deleteEnabled" to deleteEnabled)
        assertTrue(deleteEnabled)
        activate("circuit-delete-${used.id}")
        screenshot("w202-circuit-remove-blocked")
        onNodeWithText(text("circuit_remove_used_error", english, "name" to used.name)).assertIsDisplayed()
        assertTrue(state.project.circuits.any { it.id == used.id })
        activate("circuit-remove-blocked-close")
        activate("circuits-add")
        onNodeWithTag("circuit-name-field").assertIsFocused()
        onNodeWithTag("circuit-name-field").performTextInput("alu")
        onNodeWithTag("circuit-name-field").performImeAction()
        waitForIdle()
        screenshot("w202-circuit-added")
        assertTrue(state.project.circuits.any { it.name == "alu" })
        openMenu("file")
        assertEquals("Create a new project", description("menu-new"))
    }

    // R0708-2: at density 2.75 and 200% font the header bar's floor still leaves its overflow button 48 dp.
    @Test
    fun headerBarFloorKeepsTouchTargetsAtDensity275() = runDesktopComposeUiTest(1650, 1100) {
        val state = EditorState(fixture)
        app(state, english, density = 2.75f, fontScale = 2f)
        screenshot("w202-header-density275-font200")
        val overflow = onNodeWithTag("editor-simulation-toolbar-overflow").fetchSemanticsNode().size.width
        val run = onNodeWithTag("tool-run").fetchSemanticsNode().size.width
        observe("header-density275", "overflowPx" to overflow, "runPx" to run, "minimumPx" to 132)
        assertTrue(overflow >= 132, "overflow $overflow px")
        assertTrue(run >= 132, "run $run px")
    }

    // R0708-3: the phone bottom bar shows the plain tick counter, the whole text with no substitute description.
    @Test
    fun phoneBottomBarKeepsThePlainTickCounter() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        state.publishRun(state.captureRunSnapshot(), state.simulation.copy(ticks = 1_234_567))
        waitForIdle()
        val shown = shownText("editor-simulation-ticks")
        val a11y = onNodeWithTag("editor-simulation-ticks", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        observe("phone-ticks", "shown" to shown, "description" to a11y)
        assertEquals(text("simulation_ticks", english, "count" to 1_234_567L), shown)
        assertEquals(null, a11y)
    }

    // R0708-3: only the header shortens the counter to the count (TicksCounter); the phone bottom bar keeps the plain
    // text. Even a count too wide for the bar is that text, cut with an ellipsis, with no substitute description, so
    // a tick never re-measures the label or recomposes a BoxWithConstraints there.
    @Test
    fun phoneBottomBarCounterStaysPlainWhenCut() = runDesktopComposeUiTest(320, 700) {
        val state = EditorState(fixture)
        app(state, english, fontScale = 2f)
        val ticks = Long.MAX_VALUE
        state.publishRun(state.captureRunSnapshot(), state.simulation.copy(ticks = ticks))
        waitForIdle()
        screenshot("w202-phone-ticks-cut")
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("editor-simulation-ticks", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val a11y = onNodeWithTag("editor-simulation-ticks", useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        val whole = text("simulation_ticks", english, "count" to ticks)
        observe("phone-ticks-cut", "shown" to layout.layoutInput.text.text, "overflow" to layout.hasVisualOverflow,
            "description" to a11y)
        assertEquals(whole, layout.layoutInput.text.text, "the bottom bar keeps the whole label, not the count alone")
        assertTrue(layout.hasVisualOverflow, "the scenario cuts the counter")
        assertEquals(null, a11y)
    }

    /**
     * The header title at [width] x [height] px keeps at least the first glyph of the circuit name and the ellipsis
     * beside the picker's chevron; where that leaves no room, the chevron gives way rather than stand alone. Only where
     * the title's whole inner width (its target less 8 dp padding on each side) cannot show that glyph does the chevron
     * stay alone, so the target is never blank. The minimum is measured here in the title's own style.
     */
    private fun headerTitleKeepsTheName(width: Int, height: Int, name: String, density: Float? = null, fontScale: Float = 1f,
                                        preferences: EditorPreferences? = null, ticks: Long = 0, chevronExpected: Boolean? = null) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            val title = state.document.name
            var minimalPx = 0
            app(state, english, density = density, fontScale = fontScale, preferences = preferences, probe = {
                val measurer = rememberTextMeasurer()
                val style = LocalTextStyle.current.merge(TextStyle(fontSize = 14.sp, lineHeight = 1.25.em, fontWeight = FontWeight.SemiBold))
                minimalPx = minOf(measurer.measure(title, style, maxLines = 1).size.width,
                    measurer.measure(title.take(1) + "…", style, maxLines = 1).size.width)
            })
            if (ticks > 0) {
                state.publishRun(state.captureRunSnapshot(), state.simulation.copy(ticks = ticks))
                waitForIdle()
            }
            screenshot("w202-header-title-$name")
            val nameNode = onNode(hasText(title) and hasAnyAncestor(hasTestTag("editor-header-title")), useUnmergedTree = true)
                .fetchSemanticsNode()
            val target = onNodeWithTag("editor-header-title").fetchSemanticsNode().size
            // A chevron that gives way is left unplaced.
            val chevron = onAllNodesWithTag("editor-header-title-chevron", useUnmergedTree = true).fetchSemanticsNodes()
                .any { it.layoutInfo.isPlaced }
            val px = density ?: this.density.density
            val inner = target.width - (16 * px).toInt()
            observe("header-title-$name", "targetPx" to target.width, "innerPx" to inner, "namePx" to nameNode.size.width,
                "minimalPx" to minimalPx, "chevron" to chevron, "menuBar" to exists("menu-title-file"))
            if (inner >= minimalPx) {
                assertTrue(nameNode.size.width >= minimalPx, "the name shows ${nameNode.size.width} px of the $minimalPx px its first glyph needs")
            } else {
                assertTrue(chevron, "a title too narrow for any glyph keeps the chevron")
            }
            assertTrue(target.width >= 48 * px && target.height >= 48 * px, "the picker target $target")
            chevronExpected?.let { assertEquals(it, chevron, "chevron") }
        }

    // At the simulation bar's floor (600 x 400 dp, 200% font, seven-digit count) the title keeps its bare 48 dp target,
    // too narrow for a glyph of the name: the chevron stays as the picker's visible sign (the count stays whole, as
    // SimulationControlsHostTest.header600AtLargeFontKeepsTouchTargets checks).
    @Test
    fun headerTitleAtTheBarFloorKeepsTheChevron() = headerTitleKeepsTheName(600, 400, "600x400-font200", fontScale = 2f,
        ticks = 8_888_888, chevronExpected = true)

    // Review of C27: at 600 x 400 dp, density 2.75 and 200% font, the chevron no longer leaves the title without its name:
    // it gives way and the name shows its first glyph.
    @Test
    fun headerTitleKeepsTheNameAtDensity275AndLargeFont() =
        headerTitleKeepsTheName(1650, 1100, "density275-font200", density = 2.75f, fontScale = 2f, chevronExpected = false)

    // Review of C27: with the FPGA menu in the menu bar, a 1024 x 768 tablet with the left toolbar and seven-digit count
    // still shows the name beside the chevron, which the title's reserve now counts.
    @Test
    fun headerTitleKeepsTheNameBesideTheMenuBar() = headerTitleKeepsTheName(1024, 768, "1024x768-left-toolbar",
        preferences = EditorPreferences(toolbarPlacement = EditorToolbarPlacement.LEFT), ticks = 8_888_888, chevronExpected = true)

    // C111: Paste with an empty clipboard says so and keeps the text; a paste can be undone in one step; closing the
    // dialog opens no project copy.
    @Test
    fun xmlDialogPasteExplainsAnEmptyClipboardAndUndoes() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val clipboard = TestClipboard(null)
        var copies = 0
        app(state, english, clipboard = clipboard, onOpenWorkspace = { copies++ })
        openMenu("file")
        tapMenu("menu-xml")
        val original = state.exportXml()
        val originalSize = text("xml_preview_size", english, "count" to original.length)
        fun shown(value: String) = onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        fun importEnabled() = !onNodeWithTag("editor-xml-import").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        assertTrue(shown(originalSize))
        tap("editor-xml-paste")
        screenshot("w202-xml-paste-empty")
        val emptyNotice = shown(text("xml_paste_empty", english))
        val keptAfterEmpty = shown(originalSize)
        val undoAfterEmpty = exists("editor-xml-undo-paste")
        val pasted = "<project source=\"pasted\"/>"
        clipboard.content = pasted
        tap("editor-xml-paste")
        screenshot("w202-xml-pasted")
        val pastedShown = shown(text("xml_preview_size", english, "count" to pasted.length))
        val importAfterPaste = importEnabled()
        val noticeAfterPaste = shown(text("xml_paste_empty", english))
        val undoOffered = exists("editor-xml-undo-paste")
        if (undoOffered) tap("editor-xml-undo-paste")
        screenshot("w202-xml-paste-undone")
        val restored = shown(originalSize)
        val importAfterUndo = importEnabled()
        val undoAfterUndo = exists("editor-xml-undo-paste")
        onNode(hasText(text(Messages.ActionClose, english)) and hasClickAction()).performClick()
        waitForIdle()
        val dialogClosed = !shown(originalSize)
        observe("xml-paste", "emptyNotice" to emptyNotice, "keptAfterEmpty" to keptAfterEmpty, "undoAfterEmpty" to undoAfterEmpty,
            "pastedShown" to pastedShown, "importAfterPaste" to importAfterPaste, "noticeAfterPaste" to noticeAfterPaste,
            "undoOffered" to undoOffered, "restored" to restored, "importAfterUndo" to importAfterUndo,
            "undoAfterUndo" to undoAfterUndo, "dialogClosed" to dialogClosed, "projectCopiesOpened" to copies)
        assertTrue(emptyNotice, "an empty clipboard is reported")
        assertTrue(keptAfterEmpty, "an empty paste keeps the text")
        assertFalse(undoAfterEmpty, "nothing to undo after an empty paste")
        assertTrue(pastedShown && importAfterPaste, "the pasted text replaces the export and can be imported")
        assertFalse(noticeAfterPaste, "a successful paste clears the notice")
        assertTrue(undoOffered, "Undo paste is offered")
        assertTrue(restored && !importAfterUndo && !undoAfterUndo, "Undo paste restores the export, unchanged again")
        assertTrue(dialogClosed)
        assertEquals(0, copies, "closing the dialog opens no project copy")
    }

    // C27: Evolution's Close window shortcut still returns to the project list now that the title opens the picker.
    @Test
    fun closeWindowShortcutStillReturnsToTheProjects() = runDesktopComposeUiTest(1180, 820) {
        val state = EditorState(fixture)
        var projectsOpened = 0
        var windowFocused: Boolean? = null
        app(state, english, openProjects = { projectsOpened++ }, projectName = "Lab 3",
            probe = { windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused })
        val focusedNodes = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().size
        // The editor's shortcut target is the focusable node spanning the window; focus it as a keyboard user's first
        // key press into the window does.
        val editor = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus), useUnmergedTree = true)
            .fetchSemanticsNodes().maxBy { it.size.width.toLong() * it.size.height }
        onNode(SemanticsMatcher("editor shortcuts") { it.id == editor.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.W) } } }
        waitForIdle()
        observe("window-close", "projectsOpened" to projectsOpened, "picker" to exists("editor-circuit-picker"),
            "windowFocused" to windowFocused, "focusedNodes" to focusedNodes)
        assertEquals(1, projectsOpened)
        assertFalse(exists("editor-circuit-picker"))
    }

    // Review of C31: a project's only circuit gets Evolution's circuitRemoveLastError, not advice to pick another main.
    @Test
    fun removingTheOnlyCircuitExplainsWhy() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, english)
        assertEquals(1, state.project.circuits.size)
        openMenu("project")
        tapMenu("menu-remove-circuit")
        screenshot("w202-remove-last-circuit")
        val last = onAllNodesWithText(text("circuit_remove_last_error", english)).fetchSemanticsNodes().isNotEmpty()
        val main = onAllNodesWithText(text("circuit_remove_main_error", english, "name" to state.document.name)).fetchSemanticsNodes().isNotEmpty()
        observe("remove-last-circuit", "lastCircuitMessage" to last, "mainCircuitMessage" to main)
        assertTrue(last)
        assertFalse(main)
        assertEquals(1, state.project.circuits.size)
    }

    // The narrowest phone (360 dp, Portuguese): the header picker, the zoom popover and an opened notice keep 48 dp
    // targets and stay inside the window.
    @Test
    fun narrowPhoneKeepsTheNewControlsWhole() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(hierarchy)
        app(state, portuguese, openProjects = {}, projectName = "Laboratório 3", xml = hierarchy)
        state.dismissImportReport()
        waitForIdle()
        fun size(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().let { it.boundsInRoot }
        val title = size("editor-header-title")
        activate("editor-header-title")
        screenshot("w202-360-header-picker")
        val pickerRows = state.project.circuits.map { size("circuit-picker-${it.id}") } + size("menu-circuit-picker-projects")
        onNodeWithTag("editor-circuit-picker").assertIsDisplayed()
        // Close the picker through its own entry, then open the zoom popover.
        tapMenu("circuit-picker-${state.project.mainCircuit.id}")
        tap("tool-zoom-exact")
        screenshot("w202-360-zoom-popover")
        val zoomRows = listOf("menu-zoom-popover-in", "menu-zoom-popover-fit", "menu-zoom-popover-custom").map { size(it) }
        // Material's Slider requires its 44 dp handle height inside the 48 dp row it gets; Compose extends a pointer
        // target that small to the 48 dp minimum touch size.
        val slider = size("editor-zoom-menu-slider")
        val zoomMenu = size("editor-zoom-menu")
        tapMenu("menu-zoom-popover-100")
        state.reportStatus(message("project_graph_unavailable"))
        waitForIdle()
        activate("editor-status")
        screenshot("w202-360-status-expanded")
        val status = size("editor-status")
        observe("narrow-phone", "title" to title, "pickerRows" to pickerRows, "zoomRows" to zoomRows, "slider" to slider,
            "zoomMenu" to zoomMenu, "status" to status)
        assertTrue(title.height >= 48f && title.width >= 48f, "$title")
        assertTrue((pickerRows + zoomRows).all { it.height >= 48f }, "$pickerRows $zoomRows")
        assertTrue(slider.height >= 44f && slider.width >= 160f, "$slider")
        assertTrue(zoomMenu.left >= 0f && zoomMenu.right <= 360f, "$zoomMenu")
        assertTrue(status.height >= 48f && status.bottom <= 780f, "$status")
        assertTrue(abs(state.zoomPercent - 100f) < 0.01f)
    }
}
