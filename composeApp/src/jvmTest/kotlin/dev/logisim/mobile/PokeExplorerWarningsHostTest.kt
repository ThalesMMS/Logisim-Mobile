package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.IoKeypadState
import dev.logisim.mobile.core.SimulationNoticeSource
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W3-04 at phone size: the Poke tool's Button, Joystick and Matrix Keypad reach their menu items through the selection bar (C40),
 * every interactive input in the Explorer says what its action does (X0), a located simulation warning shows its
 * components (C67), and passing test vector rows keep AA contrast in the dark theme (C62). Each scenario writes what
 * it observed to `behavior-<scenario>.txt` beside its screenshots before asserting.
 */
@OptIn(ExperimentalTestApi::class)
class PokeExplorerWarningsHostTest {
    private val english = AppLanguage.ENGLISH

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.app(state: EditorState, xml: String, dark: Boolean = false) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides english) {
                LogisimTheme(dark = dark) { App(initialXml = xml, language = english, workspaceHeader = header) }
            }
        }
        waitForIdle()
        runOnIdle { state.dismissImportReport() }
        waitForIdle()
    }

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun EditorState.screen(x: Float, y: Float): Offset =
        Offset(x * viewport.view.scale + viewport.view.pan.x, y * viewport.view.scale + viewport.view.pan.y)

    private fun EditorState.centerOf(id: String): Offset {
        val bounds = document.components.first { it.id == id }.bounds(componentRegistry)
        return screen((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
    }

    // C40: a Button, Joystick or Matrix Keypad in the Poke tool is pressed by a held finger, so its menu items are in the bar.
    @Test
    fun aPokedButtonOffersItsMenuItemsInTheSelectionBar() {
        for (width in listOf(390, 360)) runDesktopComposeUiTest(width, 844) {
            val state = EditorState(pokeFixture)
            app(state, pokeFixture)
            runOnIdle { state.changeMode(EditorMode.INTERACT) }
            waitForIdle()
            onNodeWithTag("editor-canvas").performTouchInput { down(state.centerOf("btn")); up() }
            waitForIdle()
            val buttonBar = exists("selection-bar")
            val items = listOf("selection-rotate-left", "selection-rotate-right", "selection-delete").filter { exists(it) }
            screenshot("c40-poke-button-bar-$width")
            val facingBefore = state.document.components.first { it.id == "btn" }.attributes["facing"]
            if (exists("selection-rotate-right")) tap("selection-rotate-right")
            val facingAfter = state.document.components.first { it.id == "btn" }.attributes["facing"]
            // A Joystick, the other held pointer input, gets the same bar.
            onNodeWithTag("editor-canvas").performTouchInput { down(state.centerOf("joy")); up() }
            waitForIdle()
            val joystickBar = exists("selection-bar") && state.selectedIds == setOf("joy")
            screenshot("c40-poke-joystick-bar-$width")
            // A Matrix Keypad key is pressed while held and released on lift, like a Button: it gets the bar too.
            // Evolution's keypad has no facing, so Rotate stays disabled and Delete removes it.
            fun keypadDown() = (state.rootSimulation.componentStates["key"] as? IoKeypadState)?.pressed.orEmpty().isNotEmpty()
            val keypadPressed = mutableListOf<Boolean>()
            onNodeWithTag("editor-canvas").performTouchInput { down(state.centerOf("key")) }
            waitForIdle()
            keypadPressed += keypadDown()
            onNodeWithTag("editor-canvas").performTouchInput { up() }
            waitForIdle()
            keypadPressed += keypadDown()
            val keypadBar = exists("selection-bar") && state.selectedIds == setOf("key")
            val keypadItems = listOf("selection-rotate-left", "selection-rotate-right", "selection-delete").filter { exists(it) }
            val keypadRotateEnabled = exists("selection-rotate-right") && onNodeWithTag("selection-rotate-right")
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
            screenshot("c40-poke-keypad-bar-$width")
            if (exists("selection-delete")) tap("selection-delete")
            val keypadDeleted = state.document.components.none { it.id == "key" }
            // An input pin keeps its long-press menu in the Poke tool, so tapping one shows no bar.
            onNodeWithTag("editor-canvas").performTouchInput { down(state.centerOf("a")); up() }
            waitForIdle()
            val pinBar = exists("selection-bar")
            observe("c40-poke-bar-$width", "buttonBar" to buttonBar, "items" to items, "facingBefore" to facingBefore,
                "facingAfterRotateRight" to facingAfter, "joystickBar" to joystickBar, "keypadBar" to keypadBar,
                "keypadItems" to keypadItems, "keypadPressedWhileHeldThenReleased" to keypadPressed,
                "keypadRotateEnabled" to keypadRotateEnabled, "keypadDeleted" to keypadDeleted, "pinBar" to pinBar)
            assertTrue(buttonBar, "a poked Button shows the selection bar at $width dp")
            assertEquals(listOf("selection-rotate-left", "selection-rotate-right", "selection-delete"), items)
            assertEquals("south", facingAfter, "Rotate right turns the Button from east to south")
            assertTrue(joystickBar, "a poked Joystick shows the bar")
            assertTrue(keypadBar, "a poked Matrix Keypad shows the bar")
            assertEquals(listOf("selection-rotate-left", "selection-rotate-right", "selection-delete"), keypadItems)
            assertEquals(listOf(true, false), keypadPressed, "a keypad key is down while held and up after the lift")
            assertFalse(keypadRotateEnabled, "the keypad has no facing, so Rotate is disabled")
            assertTrue(keypadDeleted, "Delete in the bar removes the keypad")
            assertFalse(pinBar, "an input pin in the Poke tool keeps its long-press menu and shows no bar")
        }
    }

    // X0: every interactive input says what its action does; DIP switch, keypad and joystick open their controls.
    @Test
    fun theExplorerNamesWhatEachInputActionDoes() = runDesktopComposeUiTest(390, 2400) {
        val state = EditorState(inputsFixture)
        val opened = mutableListOf<String>()
        val actions = CircuitAccessibilityActions(
            selectComponent = state::select, openProperties = { opened += it }, removeComponent = state::removeComponent,
            activateComponent = state::activateComponent, selectWire = {}, removeWire = { false },
            connect = { _, _ -> false }, insertComponent = state::insertComponent,
            editInputValue = { true }, toggleInputBit = state::toggleInputBit,
        )
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides english) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        AccessibleCircuitExplorer(state.document, state.simulation, state.componentRegistry,
                            state.selectedIds, emptySet(), actions)
                    }
                }
            }
        }
        waitForIdle()
        val customActions = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsActions.CustomActions].map { it.label } }
        screenshot("x0-explorer-input-actions")
        fun invoke(label: String): Boolean {
            val node = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
                .firstOrNull { node -> node.config[SemanticsActions.CustomActions].any { it.label == label } } ?: return false
            var result = false
            runOnIdle { result = node.config[SemanticsActions.CustomActions].first { it.label == label }.action() }
            waitForIdle()
            return result
        }
        val clockBefore = state.simulation.vectorValues["clk"]?.toULongOrNull()
        val clockToggled = invoke("Toggle CLK")
        val clockAfter = state.simulation.vectorValues["clk"]?.toULongOrNull()
        val dataToggled = invoke("Toggle bit 0 of D")
        val dataAfter = state.simulation.vectorValues["d"]?.toULongOrNull()
        // A hex pin: a tap advances its low digit, but the action says bit 0, so two of them turn bit 0 on, then off.
        val hexValues = (1..2).map {
            invoke("Toggle bit 0 of H")
            state.simulation.vectorValues["h"]?.toULongOrNull()
        }
        val controls = listOf("Open controls for SW", "Open controls for J", "Open controls for K").map(::invoke)
        observe("x0-input-action-names", "customActions" to customActions, "clock" to "$clockBefore -> $clockAfter",
            "clockApplied" to clockToggled, "dataApplied" to dataToggled, "dataAfterLowBit" to dataAfter,
            "hexAfterTwoLowBitToggles" to hexValues, "openControls" to controls, "openedProperties" to opened)
        val expected = listOf("Toggle A", "Toggle bit 0 of D", "Press B", "Toggle CLK", "Open controls for SW",
            "Open controls for J", "Open controls for K")
        assertEquals(emptyList(), expected.filter { it !in customActions }, "missing actions in $customActions")
        assertTrue(customActions.none { it.startsWith("Activate ") }, "no action is still called Activate: $customActions")
        assertTrue(clockToggled && clockBefore != clockAfter, "Toggle CLK changes the clock level")
        assertTrue(dataToggled)
        assertEquals(0x01uL, dataAfter, "the low-bit toggle changes exactly bit 0")
        assertEquals(listOf(0x01uL, 0x00uL), hexValues, "on a hex pin too, the low-bit toggle toggles bit 0")
        assertEquals(listOf("sw", "j", "k"), opened, "Open controls opens the inspector, where the I/O controls are")
    }

    // C67: a warning that knows its components offers Show, which selects them and closes the dialog.
    @Test
    fun aLocatedWarningShowsItsComponents() {
        for ((width, height) in listOf(390 to 844, 800 to 380)) runDesktopComposeUiTest(width, height) {
            val state = EditorState(widthFixture)
            app(state, widthFixture)
            val notices = state.simulation.notices.map { it.resolve(english) }.distinct()
            if (height < 420) {
                val label = dev.logisim.mobile.core.localization.LocalizedText(Messages.A11ySimulationWarningCount,
                    mapOf("count" to notices.size.toString()), quantity = notices.size.toLong()).resolve(english)
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))[0]
                    .performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
            } else {
                tap("editor-project-toolbar-overflow")
                tap("menu-title-simulate")
                tap("menu-diagnostics")
            }
            val dialog = exists("simulation-warnings-dialog")
            val show = exists("simulation-warning-show-0")
            val showDescription = if (!show) null else onNodeWithTag("simulation-warning-show-0").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            screenshot("c67-located-warning-${width}x$height")
            if (show) tap("simulation-warning-show-0")
            val closed = !exists("simulation-warnings-dialog")
            val selected = state.selectedIds
            screenshot("c67-located-warning-shown-${width}x$height")
            observe("c67-located-warning-${width}x$height", "notices" to notices, "dialog" to dialog, "show" to show,
                "showDescription" to showDescription, "closedAfterShow" to closed, "selected" to selected,
                "fitSelection" to state.fitSelection)
            assertTrue(dialog)
            assertTrue(show, "the width warning offers Show")
            assertEquals(message("a11y_simulation_warning_show", "message" to notices.first()).resolve(english), showDescription)
            assertTrue(closed, "Show closes the dialog")
            assertEquals(setOf("wide", "narrow"), selected, "Show selects both pins on the mismatched net")
            assertTrue(state.fitSelection, "Show fits the view to them")
        }
    }

    // C67: when the warning's components are gone (the circuit changed under the dialog), Show says so and stays.
    @Test
    fun aShowWhoseComponentsAreGoneKeepsTheDialogAndSaysSo() {
        for (width in listOf(390, 360)) runDesktopComposeUiTest(width, 844) {
            val state = EditorState(widthFixture)
            val notice = state.simulation.notices.first { it.key == Messages.ErrorNetworkWidth }
            val sources = state.simulation.noticeSources
            val asked = mutableListOf<List<String>>()
            var dismissed = false
            // The snapshot's sources name components the circuit no longer has.
            runOnIdle { state.removeComponent("wide"); state.removeComponent("narrow") }
            var shownSources by mutableStateOf(sources)
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides english) {
                    LogisimTheme(dark = false) {
                        Surface(Modifier.fillMaxSize()) {
                            SimulationWarningsDialog(listOf(notice), shownSources,
                                onShow = { ids -> asked += ids; state.showComponents(ids) }) { dismissed = true }
                        }
                    }
                }
            }
            waitForIdle()
            tap("simulation-warning-show-0")
            val stillOpen = exists("simulation-warnings-dialog")
            val goneShown = exists("simulation-warning-gone-0")
            val goneText = if (!goneShown) null else onNodeWithTag("simulation-warning-gone-0", useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            screenshot("c67-warning-source-gone-$width")
            // The simulation refreshes under the open dialog: the same warning now names components that exist,
            // so the stale "no longer in the circuit" line goes.
            runOnIdle { shownSources = sources + (notice to SimulationNoticeSource(listOf("restored"))) }
            waitForIdle()
            val goneAfterRefresh = exists("simulation-warning-gone-0")
            observe("c67-warning-source-gone-$width", "asked" to asked, "stillOpen" to stillOpen, "dismissed" to dismissed,
                "goneShown" to goneShown, "goneText" to goneText, "status" to state.statusText.resolve(english),
                "goneAfterSourcesChanged" to goneAfterRefresh)
            assertEquals(listOf(listOf("wide", "narrow")), asked)
            assertTrue(stillOpen && !dismissed, "a Show that found nothing keeps the dialog open")
            assertEquals(message("simulation_warning_source_gone").resolve(english), goneText)
            assertEquals(message("simulation_warning_source_gone").resolve(english), state.statusText.resolve(english))
            assertFalse(goneAfterRefresh, "new sources for the same warning clear the stale gone line")
        }
    }

    // C62: passing rows read at AA contrast on the dark sheet, from a palette token instead of a fixed green.
    @Test
    fun passingVectorRowsKeepAaContrastInTheDarkTheme() = runDesktopComposeUiTest(390, 844) {
        val editor = EditorState(vectorFixture)
        val tool = TestVectorToolState()
        tool.text = "A Y\n0 0\n1 1\n"
        val session = tool.load(editor.project, editor.componentRegistry)!!
        session.resume()
        while (session.advance() != null) Unit
        tool.revision++
        val ratios = mutableMapOf<Boolean, Double>()
        for (dark in listOf(false, true)) {
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides english) {
                    LogisimTheme(dark = dark) {
                        Surface(Modifier.fillMaxSize()) {
                            TestVectorPanel(editor.project, tool, onExport = {}, registry = editor.componentRegistry)
                        }
                    }
                }
            }
            waitForIdle()
            screenshot("c62-vector-results-${if (dark) "dark" else "light"}")
            val pass = message(Messages.VectorPass).resolve(english)
            val row = onAllNodesWithText(pass, substring = true)[0]
            val pixels = row.captureToImage().toPixelMap()
            val colors = (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y] } }
            val background = colors.groupingBy { it }.eachCount().maxBy { it.value }.key
            fun contrast(a: Color, b: Color): Double {
                val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
                return (max(first, second) + 0.05) / (min(first, second) + 0.05)
            }
            // The glyph ink: the pixel farthest in contrast from the background.
            ratios[dark] = colors.maxOf { contrast(it, background) }
        }
        observe("c62-vector-result-contrast", "light" to ratios[false], "dark" to ratios[true])
        assertTrue(ratios.getValue(false) >= 4.5, "light: ${ratios[false]}")
        assertTrue(ratios.getValue(true) >= 4.5, "dark: ${ratios[true]}")
    }

    private companion object {
        val pokeFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#I/O" name="5"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="5" loc="(200,100)" name="Button" logisim-mobile-id="btn"><a name="label" val="B"/></comp>
    <comp lib="5" loc="(300,100)" name="Joystick" logisim-mobile-id="joy"><a name="label" val="J"/></comp>
    <comp lib="5" loc="(200,200)" name="MatrixKeypad" logisim-mobile-id="key"><a name="label" val="K"/></comp>
  </circuit>
</project>
"""

        val inputsFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#I/O" name="5"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin" logisim-mobile-id="d"><a name="width" val="8"/><a name="label" val="D"/></comp>
    <comp lib="0" loc="(100,600)" name="Pin" logisim-mobile-id="h"><a name="width" val="8"/><a name="radix" val="16"/><a name="label" val="H"/></comp>
    <comp lib="5" loc="(100,300)" name="Button" logisim-mobile-id="b"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(100,400)" name="Clock" logisim-mobile-id="clk"><a name="label" val="CLK"/></comp>
    <comp lib="5" loc="(300,100)" name="DipSwitch" logisim-mobile-id="sw"><a name="label" val="SW"/></comp>
    <comp lib="5" loc="(300,300)" name="Joystick" logisim-mobile-id="j"><a name="label" val="J"/></comp>
    <comp lib="5" loc="(300,500)" name="MatrixKeypad" logisim-mobile-id="k"><a name="label" val="K"/></comp>
  </circuit>
</project>
"""

        /** An 8-bit input wired to a 1-bit output: the net reports a width mismatch. */
        val widthFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="wide"><a name="width" val="8"/><a name="label" val="W"/></comp>
    <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="narrow"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="N"/></comp>
    <wire from="(100,100)" to="(300,100)"/>
  </circuit>
</project>
"""

        val vectorFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="y"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="Y"/></comp>
    <wire from="(100,100)" to="(300,100)"/>
  </circuit>
</project>
"""
    }
}
