package dev.logisim.mobile

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.EvolutionDefaultTemplate
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Touch editing in the whole editor at phone (390x844) and tablet (1180x820) size, for a project without
 * `<toolbar>` (fixtures/user-scenarios/editing-not.circ). Each scenario writes what it observed to
 * `behavior-<scenario>.txt` beside its screenshots (`-PuiScreenshots=<dir>`) before asserting, so a run on the
 * original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class)
class TouchEditingHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private val portuguese = AppLanguage.PORTUGUESE

    /** Preferences kept in memory, as a device stores them. */
    private class MemoryPreferences(preferences: EditorPreferences) : PreferencesStorage {
        private var stored: String? = EditorPreferencesCodec.write(preferences)
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    /** The whole App, editing [state] (passed through the workspace session so the test can read it). */
    private fun ComposeUiTest.app(state: EditorState, language: AppLanguage = portuguese, preferences: EditorPreferences? = null) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        val storage = preferences?.let(::MemoryPreferences)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) {
                    App(initialXml = fixture, language = language, workspaceHeader = header, preferencesStorage = storage)
                }
            }
        }
        waitForIdle()
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun text(key: String, language: AppLanguage = portuguese) = message(key).resolve(language)

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.toolTags(): List<String> = onAllNodes(SemanticsMatcher("tool button") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tool-") == true
    }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun ComposeUiTest.statusLine(): String =
        onNodeWithTag("editor-status").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text }.orEmpty()

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    /** The canvas point, in the canvas node's own pixels (density 1), of the centre of [component]. */
    private fun EditorState.screen(component: CircuitComponent): Offset {
        val bounds = component.bounds(componentRegistry)
        return screen((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
    }

    private fun EditorState.screen(x: Float, y: Float): Offset =
        Offset(x * viewport.view.scale + viewport.view.pan.x, y * viewport.view.scale + viewport.view.pan.y)

    private fun ComposeUiTest.tapCanvas(point: Offset) {
        onNodeWithTag("editor-canvas").performTouchInput { click(point) }
        waitForIdle()
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** A menu item, scrolled into the open menu's viewport first. */
    private fun ComposeUiTest.tapMenu(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openEditMenu() {
        tap("editor-project-toolbar-overflow")
        tap("menu-title-edit")
    }

    private fun ComposeUiTest.statusLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag("editor-status").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private val selectionBarTags = listOf("selection-rotate-left", "selection-rotate-right", "selection-duplicate",
        "selection-copy", "selection-delete", "selection-add", "selection-properties")

    /** The selection bar buttons not wholly inside the bar's visible row, which would need an unmarked scroll. */
    private fun ComposeUiTest.clippedBarButtons(): List<String> {
        val bar = onNodeWithTag("selection-bar").fetchSemanticsNode()
        val left = bar.positionInRoot.x
        val right = left + bar.size.width
        return selectionBarTags.filter { exists(it) }.filter { tag ->
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            node.positionInRoot.x < left - 0.5f || node.positionInRoot.x + node.size.width > right + 0.5f
        }
    }

    private fun toolRow(width: Int, height: Int, name: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        val original = CircCodec.writeProject(state.project, state.componentRegistry)
        app(state)
        screenshot("tools-$name")
        val tools = toolTags()
        observe("tools-$name", "tools" to tools, "mode" to state.mode, "status" to statusLine(),
            "statusKey" to state.statusText.key)
        // A project without <toolbar> still offers Poke, Edit, Select, Wire and Text, plus the touch-only Move.
        for (tool in listOf("poke", "edit", "select", "wire", "text")) onNodeWithTag("tool-mobile-tool-$tool").assertIsDisplayed()
        onNodeWithTag("tool-move").assertIsDisplayed()
        onNodeWithTag("tool-mobile-tool-poke").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        // The status line describes the Poke tool the editor opens in, not a circuit with inputs A and B.
        assertEquals(EditorMode.INTERACT, state.mode)
        assertEquals(Messages.StatusInteract, state.statusText.key)
        assertEquals(text(Messages.StatusInteract), statusLine())
        // Showing the injected tools never writes a <toolbar>.
        assertEquals(original, state.exportXml())
        assertFalse(state.exportXml().contains("<toolbar"))
    }

    @Test fun phoneToolRowOffersTheBaseTools() = toolRow(390, 844, "phone")
    @Test fun tabletToolRowOffersTheBaseTools() = toolRow(1180, 820, "tablet")

    @Test
    fun afterRunTheWireToolStillDrawsAWire() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        tap("tool-run")
        val runMode = state.mode
        tap("tool-run")
        val wires = state.document.wires.size
        val wireTool = exists("tool-mobile-tool-wire")
        observe("run-then-wire", "modeAfterRun" to runMode, "wireToolShown" to wireTool, "tools" to toolTags())
        assertEquals(EditorMode.INTERACT, runMode)
        tap("tool-mobile-tool-wire")
        assertEquals(EditorMode.WIRE, state.mode)
        // From the output pin's free end down 50 units: one new wire.
        val from = state.screen(300f, 100f)
        val to = state.screen(300f, 150f)
        onNodeWithTag("editor-canvas").performTouchInput {
            down(from)
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            up()
        }
        waitForIdle()
        screenshot("run-then-wire-drawn")
        observe("run-then-wire", "modeAfterRun" to runMode, "wireToolShown" to wireTool,
            "wiresBefore" to wires, "wiresAfter" to state.document.wires.size)
        assertEquals(wires + 1, state.document.wires.size)
    }

    private fun selectionBar(width: Int, height: Int, name: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        app(state)
        val not = state.component("E2E_NOT")
        val count = state.document.components.size
        if (exists("tool-mobile-tool-edit")) tap("tool-mobile-tool-edit") else state.changeMode(EditorMode.EDIT)
        waitForIdle()
        tapCanvas(state.screen(not))
        screenshot("selection-bar-$name")
        val shown = listOf("selection-rotate-left", "selection-rotate-right", "selection-delete", "selection-duplicate",
            "selection-copy", "selection-add").filter { exists(it) }
        observe("selection-bar-$name", "mode" to state.mode, "selected" to state.selectedIds, "barButtons" to shown,
            "properties" to exists("selection-properties"))
        assertEquals(setOf(not.id), state.selectedIds)
        // The docked inspector (tablet) or the toolbar's Properties button (phone) already opens Properties.
        assertFalse(exists("selection-properties"))
        onNodeWithTag("selection-rotate-right").assertIsDisplayed()
        onNodeWithTag("selection-delete").assertIsDisplayed()
        // Rotate right is a quarter turn clockwise: east to south, one undoable step named after the rotation.
        tap("selection-rotate-right")
        assertEquals("south", state.component("E2E_NOT").facing)
        assertEquals("history_rotate_component", state.undoHistory.first().label.key)
        screenshot("selection-bar-$name-rotated")
        tap("selection-delete")
        assertEquals(count - 1, state.document.components.size)
        tap("tool-undo")
        assertEquals(count, state.document.components.size)
        assertEquals("south", state.component("E2E_NOT").facing)
        tap("tool-undo")
        assertEquals("east", state.component("E2E_NOT").facing)
    }

    @Test fun phoneSelectionBarRotatesDeletesAndUndoes() = selectionBar(390, 844, "phone")
    @Test fun tabletSelectionBarRotatesDeletesAndUndoes() = selectionBar(1180, 820, "tablet")

    @Test
    fun deletingAndTurningASelectedGateTakesFewerTaps() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        val not = state.component("E2E_NOT")
        val count = state.document.components.size
        // Original route to Delete: ≡, Edit, Delete.
        state.changeMode(EditorMode.EDIT)
        waitForIdle()
        tapCanvas(state.screen(not))
        tap("editor-project-toolbar-overflow")
        tap("menu-title-edit")
        tap("menu-delete")
        val menuDeleted = state.document.components.size == count - 1
        tap("tool-undo")
        // Original route to a half turn of the output pin: two long-press round trips through the component menu.
        // (The pin turns about its terminal; a second quarter turn of the wired NOT is refused by the core editor,
        // which keeps connections: recorded below as notQuarterTurns, a gap outside this change.)
        val longPressFacings = mutableListOf(state.component("E2E_OUT").facing)
        repeat(2) {
            onNodeWithTag("editor-canvas").performTouchInput { longClick(state.screen(state.component("E2E_OUT"))) }
            waitForIdle()
            if (exists("canvas-menu-rotate_right")) tap("canvas-menu-rotate_right")
            longPressFacings += state.component("E2E_OUT").facing
        }
        while (state.canUndo) state.undo()
        waitForIdle()
        tapCanvas(state.screen(state.component("E2E_OUT")))
        val barShown = exists("selection-rotate-right")
        val facts = mutableListOf<Pair<String, Any?>>("menuDeleteTaps" to 3, "menuDeleted" to menuDeleted, "longPressInteractionsForHalfTurn" to 4,
            "longPressFacings" to longPressFacings, "selectionBarShown" to barShown)
        observe("tap-counts", *facts.toTypedArray())
        assertTrue(menuDeleted)
        assertEquals(listOf("west", "north", "east"), longPressFacings)
        // With the selection bar: two taps for a half turn, and one tap to delete a selected gate.
        val barFacings = mutableListOf(state.component("E2E_OUT").facing)
        repeat(2) { tap("selection-rotate-right"); barFacings += state.component("E2E_OUT").facing }
        tapCanvas(state.screen(state.component("E2E_NOT")))
        tap("selection-delete")
        val barDeleted = state.document.components.size == count - 1
        tap("tool-undo")
        // The core editor's connection-keeping refusal, for the record.
        val notFacings = mutableListOf(state.component("E2E_NOT").facing)
        tapCanvas(state.screen(state.component("E2E_NOT")))
        repeat(2) { tap("selection-rotate-right"); notFacings += state.component("E2E_NOT").facing }
        facts += listOf("barHalfTurnTaps" to 2, "barFacings" to barFacings, "barDeleteTaps" to 1, "barDeleted" to barDeleted,
            "notQuarterTurns" to notFacings, "notSecondTurnStatus" to state.statusText.key)
        observe("tap-counts", *facts.toTypedArray())
        assertEquals(listOf("west", "north", "east"), barFacings)
        assertTrue(barDeleted)
    }

    @Test
    fun addToSelectionLetsAFingerSelectTwoComponents() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        val not = state.component("E2E_NOT")
        val output = state.component("E2E_OUT")
        if (exists("tool-mobile-tool-edit")) tap("tool-mobile-tool-edit") else state.changeMode(EditorMode.EDIT)
        waitForIdle()
        tapCanvas(state.screen(not))
        val toggle = exists("selection-add")
        if (toggle) tap("selection-add")
        tapCanvas(state.screen(output))
        screenshot("additive-two-selected")
        observe("additive", "toggleShown" to toggle, "selected" to state.selectedIds.map { id ->
            state.document.components.first { it.id == id }.label })
        assertEquals(setOf(not.id, output.id), state.selectedIds)
        assertTrue(exists("selection-adding-chip"))
        // A second tap on a selected component drops it, as Shift does in Evolution's SelectTool.
        tapCanvas(state.screen(not))
        assertEquals(setOf(output.id), state.selectedIds)
        // Leaving the selecting tools ends the additive mode.
        tap("tool-mobile-tool-poke")
        assertFalse(exists("selection-adding-chip"))
        state.changeMode(EditorMode.EDIT)
        waitForIdle()
        tapCanvas(state.screen(not))
        assertEquals(setOf(not.id), state.selectedIds)
    }

    @Test
    fun longPressOnAToolbarGlyphShowsItsName() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        val label = text("simulation_control_step_event")
        onNodeWithTag("tool-event").performTouchInput { longClick() }
        waitForIdle()
        screenshot("tooltip-event")
        val tips = onAllNodesWithText(label).fetchSemanticsNodes()
        val shown = tips.isNotEmpty()
        val button = onNodeWithTag("tool-event").fetchSemanticsNode().config
        val descriptions = button.getOrNull(SemanticsProperties.ContentDescription)
        // Any long-press action TalkBack would offer on a toolbar glyph: on its node or on a wrapper around it.
        val longPressActions = onAllNodes(SemanticsMatcher("tool button") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tool-") == true
        }).fetchSemanticsNodes().flatMap { node -> generateSequence(node) { it.parent }.take(3).toList() }
            .mapNotNull { node -> node.config.getOrNull(SemanticsActions.OnLongClick)?.let { it.label ?: "(no label)" } }
        observe("tooltip", "label" to label, "shown" to shown, "buttonDescriptions" to descriptions,
            "tooltipHiddenFromAccessibility" to tips.map { it.config.contains(SemanticsProperties.HideFromAccessibility) },
            "longPressActions" to longPressActions)
        assertTrue(shown)
        // TalkBack reads one name, the button's description; the visible tooltip is not announced again.
        assertEquals(listOf(label), descriptions)
        assertTrue(tips.all { it.config.contains(SemanticsProperties.HideFromAccessibility) })
        // And no "Show tooltip" long-press action whose result it could not hear.
        assertEquals(emptyList(), longPressActions)
    }

    @Test
    fun theEditMenuAlwaysListsTheTools() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        tap("editor-project-toolbar-overflow")
        tap("menu-title-edit")
        screenshot("edit-menu-tools")
        val modes = listOf("mode-poke", "mode-edit", "select", "move", "mode-wire", "mode-text")
        observe("edit-menu", "shown" to modes.filter { exists("menu-$it") })
        modes.forEach { onNodeWithTag("menu-$it").assertExists() }
        onNodeWithTag("menu-mode-poke").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        tapMenu("menu-mode-wire")
        assertEquals(EditorMode.WIRE, state.mode)
        // In a selecting tool the menu also turns Add to selection on and off, and shows whether it is on.
        state.changeMode(EditorMode.SELECT)
        waitForIdle()
        openEditMenu()
        onNodeWithTag("menu-additive-selection").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        tapMenu("menu-additive-selection")
        assertTrue(state.additiveSelection)
        assertTrue(exists("selection-adding-chip"))
        openEditMenu()
        onNodeWithTag("menu-additive-selection").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        tapMenu("menu-additive-selection")
        assertFalse(state.additiveSelection)
        state.toggleAdditiveSelection()
        waitForIdle()
        tap("selection-adding-stop")
        assertFalse(state.additiveSelection)
    }

    @Test
    fun theEditMenuTurnsOnlyASelectionWithAFacing() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        state.changeMode(EditorMode.EDIT)
        state.select(state.component("E2E_NOT").id)
        waitForIdle()
        openEditMenu()
        screenshot("edit-menu-rotate")
        onNodeWithTag("menu-rotate-left").assertIsEnabled()
        onNodeWithTag("menu-rotate-right").assertIsEnabled()
        tapMenu("menu-rotate-right")
        assertEquals("south", state.component("E2E_NOT").facing)
        assertEquals("history_rotate_component", state.undoHistory.first().label.key)
        // A wire has no facing to turn.
        state.selectWire(state.document.wires.first())
        waitForIdle()
        openEditMenu()
        observe("edit-menu-rotate", "selectedWires" to state.selectedWires.size, "canRotate" to state.canRotateSelection)
        onNodeWithTag("menu-rotate-left").assertIsNotEnabled()
        onNodeWithTag("menu-rotate-right").assertIsNotEnabled()
    }

    // A hidden toolbar folds the project's tools into the Edit menu: default.templ's Text Tool is listed once, as the
    // tool group's Text item, which uses the project's own Text Tool.
    @Test
    fun aHiddenToolbarListsTheProjectsTextToolOnce() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.newDocument(EvolutionDefaultTemplate.apply(state.project, state.componentRegistry))
        val projectText = assertNotNull(projectTextTool(projectToolbarEntries(state.project, state.componentRegistry)))
        app(state, preferences = EditorPreferences(toolbarPlacement = EditorToolbarPlacement.HIDDEN))
        openEditMenu()
        val label = text(Messages.MouseMappingText)
        val textItems = onAllNodes(SemanticsMatcher("Edit menu item named $label") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("menu-") == true &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
        }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        screenshot("edit-menu-hidden-toolbar")
        observe("edit-menu-hidden-toolbar", "textItems" to textItems, "projectTextToolItem" to exists("menu-${projectText.actionId}"))
        assertEquals(listOf("menu-mode-text"), textItems)
        assertFalse(exists("menu-${projectText.actionId}"))
        tapMenu("menu-mode-text")
        assertEquals(EditorMode.TEXT, state.mode)
        assertEquals(projectText.prototype, state.placingTemplate)
    }

    // C23, C35, C44: every touch hint, Add to selection's included, fits the phone status line whole.
    private fun touchHintsFit(width: Int, height: Int, name: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        var language by mutableStateOf(AppLanguage.ENGLISH)
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { App(initialXml = fixture, language = language, workspaceHeader = header) }
            }
        }
        waitForIdle()
        val facts = mutableListOf<Pair<String, Any?>>()
        val cut = mutableListOf<String>()
        for (shown in listOf(AppLanguage.ENGLISH, portuguese)) {
            language = shown
            for (mode in listOf(EditorMode.INTERACT, EditorMode.EDIT, EditorMode.SELECT, EditorMode.MOVE, EditorMode.WIRE)) {
                for (adding in listOf(false, true)) {
                    if (adding && mode !in AdditiveSelectionModes) continue
                    state.changeMode(mode)
                    if (state.additiveSelection != adding) state.toggleAdditiveSelection()
                    waitForIdle()
                    val layout = statusLayout()
                    val scenario = "${shown.name.lowercase()}-${mode.name.lowercase()}${if (adding) "-adding" else ""}"
                    facts += scenario to "${state.displayedStatusText.key} lines=${layout.lineCount} cut=${layout.hasVisualOverflow}"
                    if (layout.hasVisualOverflow) cut += scenario
                    if (mode == EditorMode.SELECT || mode == EditorMode.EDIT && !adding) screenshot("status-hint-$name-$scenario")
                }
            }
        }
        observe("status-hints-$name", *facts.toTypedArray())
        assertEquals(emptyList(), cut)
    }

    @Test fun touchHintsFitA360DpPhone() = touchHintsFit(360, 780, "360")
    @Test fun touchHintsFitA390DpPhone() = touchHintsFit(390, 844, "390")

    /** The clause every touch hint starts with, so the lines a large font leaves still say how to pan and zoom. */
    private val panAndZoom = mapOf(AppLanguage.ENGLISH to "Two fingers pan and zoom.", portuguese to "Dois dedos: mover e zoom.")

    // C44 at large fonts: the status keeps to the 48 dp the bottom bar's buttons take, so 130 % leaves it two lines and
    // 200 % one. The pan and zoom clause comes first; this records what each touch hint still shows.
    private fun touchHintsAtLargeFont(width: Int, height: Int, name: String, fontScale: Float) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            var language by mutableStateOf(AppLanguage.ENGLISH)
            val header = EditorWorkspaceHeader("", "").also {
                it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
            }
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density.density, fontScale)) {
                    LogisimTheme(dark = false) { App(initialXml = fixture, language = language, workspaceHeader = header) }
                }
            }
            waitForIdle()
            val facts = mutableListOf<Pair<String, Any?>>("fontScale" to fontScale)
            val hidden = mutableListOf<String>()
            for (shown in listOf(AppLanguage.ENGLISH, portuguese)) {
                language = shown
                val clause = checkNotNull(panAndZoom[shown])
                for ((mode, adding) in listOf(EditorMode.EDIT to false, EditorMode.SELECT to false, EditorMode.WIRE to false,
                        EditorMode.SELECT to true)) {
                    state.changeMode(mode)
                    if (state.additiveSelection != adding) state.toggleAdditiveSelection()
                    waitForIdle()
                    val hint = statusLine()
                    val layout = statusLayout()
                    val last = layout.lineCount - 1
                    val ellipsized = layout.isLineEllipsized(last)
                    // Where the shown text stops: the last line's end, or the ellipsis on it.
                    val shownEnd = if (!ellipsized) layout.getLineEnd(last, visibleEnd = true) else
                        layout.getOffsetForPosition(Offset(layout.size.width.toFloat(), layout.getLineTop(last) + 1f))
                    val clauseLine = layout.getLineForOffset(clause.length - 1)
                    val clauseShown = !layout.hasVisualOverflow || clauseLine < last ||
                        clauseLine == last && shownEnd >= clause.length
                    val scenario = "${shown.name.lowercase()}-${mode.name.lowercase()}${if (adding) "-adding" else ""}"
                    facts += scenario to "${state.displayedStatusText.key} lines=${layout.lineCount} ellipsized=$ellipsized " +
                        "lineEnd=${layout.getLineEnd(last)} shownEnd=$shownEnd shown=\"${hint.take(shownEnd)}\" panAndZoomShown=$clauseShown"
                    assertTrue(hint.startsWith(clause), hint)
                    if (!clauseShown) hidden += scenario
                    if (mode == EditorMode.EDIT) screenshot("status-hint-$name-${shown.name.lowercase()}-edit")
                }
            }
            observe("status-hints-$name", *facts.toTypedArray())
            if (fontScale <= 1.3f) assertEquals(emptyList(), hidden)
        }

    @Test fun touchHintsAt130PercentFontShowPanAndZoom() = touchHintsAtLargeFont(360, 780, "360-font130", 1.3f)
    @Test fun touchHintsAt200PercentFontStartWithPanAndZoom() = touchHintsAtLargeFont(360, 780, "360-font200", 2f)

    // The selection bar's captions shrink to 8 sp at most and wrap only between words; a word too wide even then keeps a
    // line of its own, ellipsized. Measured with the editor's text style at the 48 and 52 dp cells of seven buttons on a
    // 360 and a 390 dp phone, in every published language.
    @Test
    fun selectionBarCaptionsKeepWholeWords() = runDesktopComposeUiTest(390, 200) {
        lateinit var measurer: TextMeasurer
        lateinit var style: TextStyle
        setContent {
            LogisimTheme(dark = false) {
                measurer = rememberTextMeasurer()
                style = LocalTextStyle.current
            }
        }
        waitForIdle()
        fun measure(line: String, size: Float) =
            measurer.measure(line, style.merge(TextStyle(fontSize = size.sp)), maxLines = 1, softWrap = false).size.width.toFloat()
        val keys = listOf(Messages.ComponentMenuRotateLeft, Messages.ComponentMenuRotateRight, Messages.ActionDuplicate,
            Messages.ActionCopy, Messages.ActionDelete, Messages.SelectionAddShort, Messages.ActionProperties)
        val facts = mutableListOf<Pair<String, Any?>>()
        val ellipsized = mutableMapOf<String, List<String>>()
        for (language in AppLanguage.entries.filter { it.isAvailable }) for (cell in listOf(48, 52)) {
            val width = cell - 4f
            val captions = keys.map { key ->
                val text = message(key).resolve(language)
                val caption = fitSelectionCaption(text, width, ::measure)
                assertEquals(text.split(' ').filter { it.isNotEmpty() }, caption.lines.flatMap { it.split(' ') }, text)
                assertTrue(caption.lines.size <= 2 && caption.size >= 8f, "$text: $caption")
                caption
            }
            // Only a lone word wider than the cell at 8 sp, or the second line of words that two lines do not hold, is
            // ellipsized.
            val cut = captions.flatMap { caption ->
                caption.lines.withIndex().filter { (index, line) ->
                    measure(line, caption.size) > width && (' ' !in line || index == caption.lines.lastIndex)
                }.map { it.value }
            }
            val badlyCut = captions.flatMap { caption ->
                caption.lines.filterIndexed { index, line ->
                    measure(line, caption.size) > width && ' ' in line && index != caption.lines.lastIndex
                }
            }
            assertEquals(emptyList(), badlyCut)
            ellipsized["${language.tag}-$cell"] = cut
            facts += "${language.tag}-$cell" to captions.joinToString(" | ") { "${it.lines.joinToString(" / ")} @${it.size}sp" } +
                "  ellipsized=$cut"
        }
        observe("selection-bar-captions", *facts.toTypedArray())
        assertEquals(emptyList(), ellipsized.getValue("en-48") + ellipsized.getValue("en-52"))
        // Portuguese: "Adicionar" is whole in both cells; only "Propriedades" may be ellipsized.
        assertTrue(ellipsized.getValue("pt-48").all { it == text(Messages.ActionProperties) }, "${ellipsized["pt-48"]}")
        assertTrue(ellipsized.getValue("pt-52").all { it == text(Messages.ActionProperties) }, "${ellipsized["pt-52"]}")
    }

    // The bar's buttons share the width and stay whole: six on a 360 dp phone, seven with Properties when the toolbar
    // is hidden. Properties joins the bar only then.
    private fun selectionBarFits(width: Int, height: Int, name: String, placement: EditorToolbarPlacement, properties: Boolean) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            app(state, preferences = EditorPreferences(toolbarPlacement = placement))
            state.changeMode(EditorMode.EDIT)
            state.select(state.component("E2E_NOT").id)
            waitForIdle()
            screenshot("selection-bar-fit-$name")
            val shown = selectionBarTags.filter { exists(it) }
            val clipped = clippedBarButtons()
            val widths = shown.map { onNodeWithTag(it).fetchSemanticsNode().size.width }
            observe("selection-bar-fit-$name", "placement" to placement, "buttons" to shown, "widthsPx" to widths,
                "clipped" to clipped)
            assertEquals(properties, "selection-properties" in shown)
            assertEquals(if (properties) 7 else 6, shown.size)
            assertEquals(emptyList(), clipped)
            assertTrue(widths.all { it >= 48 }, "$widths")
            if (properties) {
                tap("selection-properties")
                assertTrue(exists("editor-inspector-sheet"))
            }
        }

    @Test fun selectionBarFitsA360DpPhone() = selectionBarFits(360, 800, "360", EditorToolbarPlacement.TOP, properties = false)
    @Test fun selectionBarWithPropertiesFitsA360DpPhone() =
        selectionBarFits(360, 800, "360-hidden-toolbar", EditorToolbarPlacement.HIDDEN, properties = true)
    @Test fun selectionBarWithPropertiesFitsA390DpPhone() =
        selectionBarFits(390, 844, "390-hidden-toolbar", EditorToolbarPlacement.HIDDEN, properties = true)

    @Test
    fun aTapOnTheAddingChipNeverReachesTheCircuitBelow() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        val not = state.component("E2E_NOT")
        val output = state.component("E2E_OUT")
        state.changeMode(EditorMode.EDIT)
        state.select(not.id)
        state.toggleAdditiveSelection()
        waitForIdle()
        val chip = onNodeWithTag("selection-adding-chip").fetchSemanticsNode()
        val canvas = onNodeWithTag("editor-canvas").fetchSemanticsNode()
        // The middle of the chip's label, left of its 48 dp stop button, in the chip's and the canvas's pixels.
        val label = Offset((chip.size.width - 48f) / 2f, chip.size.height / 2f)
        val target = chip.positionInRoot + label - canvas.positionInRoot
        // Pan the circuit so the output pin lies under that label.
        val view = state.viewport.view
        state.updateViewport(state.viewport.copy(view = view.copy(pan = view.pan + target - state.screen(output)), fitted = false))
        waitForIdle()
        screenshot("adding-chip-over-output")
        onNodeWithTag("selection-adding-chip").performTouchInput { click(label) }
        waitForIdle()
        val afterChipTap = state.selectedIds.map { id -> state.document.components.first { it.id == id }.label }
        // The same point, once the chip is gone, is the pin: the tap above did not reach it.
        tap("selection-adding-stop")
        tapCanvas(target)
        val withoutChip = state.selectedIds.map { id -> state.document.components.first { it.id == id }.label }
        observe("adding-chip-tap", "pinScreen" to state.screen(output), "target" to target,
            "selectedAfterChipTap" to afterChipTap, "selectedAfterCanvasTap" to withoutChip)
        assertEquals(listOf("E2E_NOT"), afterChipTap)
        assertEquals(listOf("E2E_OUT"), withoutChip)
    }

    @Test
    fun helpDescribesTheTouchEditor() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        tap("editor-project-toolbar-overflow")
        tap("menu-title-help")
        tap("menu-help")
        screenshot("help-phone")
        val paragraphs = listOf("touch_help_library", "touch_help_viewport", "touch_help_simulation", "touch_help_editing")
        val shown = paragraphs.map { key ->
            runCatching { message(key).resolve(portuguese) }.getOrNull()
                ?.takeIf { it != key }?.let { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } == true
        }
        val original = onAllNodesWithText(text(Messages.EditorHelpLibrary)).fetchSemanticsNodes().isNotEmpty()
        observe("help", "originalLibraryParagraph" to original, "touchParagraphs" to shown)
        assertFalse(original)
        assertEquals(listOf(true, true, true, true), shown)
    }
}
