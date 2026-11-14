package dev.logisim.mobile

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Placement and label editing in the whole editor at phone size (390x844, and 360x780 where the layout is tight):
 * facing, width and inputs set before placing (C34), drops over the floating controls (R06-2), a double tap that
 * edits a label (C46), the titled register poke (C49), a validated matrix placement (X9) and the multi-bit input
 * actions (X0). Each scenario writes what it observed to `behavior-<scenario>.txt` beside its screenshots
 * (`-PuiScreenshots=<dir>`) before asserting, so a run on the original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class)
class PlacementAndLabelsHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private val portuguese = AppLanguage.PORTUGUESE

    /** Preferences kept in memory with the light theme, so screenshots do not follow the host's dark mode. */
    private class LightPreferences : PreferencesStorage {
        private var stored: String? = EditorPreferencesCodec.write(EditorPreferences(themeMode = EditorThemeMode.LIGHT))
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    private fun ComposeUiTest.app(state: EditorState, xml: String = fixture) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        val preferences = LightPreferences()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides portuguese) {
                LogisimTheme(dark = false) {
                    App(initialXml = xml, language = portuguese, workspaceHeader = header, preferencesStorage = preferences)
                }
            }
        }
        waitForIdle()
        // A fixture that leaves attributes to the source version's defaults opens the import report first.
        if (exists("editor-import-report")) {
            onAllNodes(hasText(localized(Messages.ActionClose)) and hasClickAction())[0].performClick()
            waitForIdle()
        }
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.tapIfShown(tag: String): Boolean = exists(tag).also { if (it) tap(tag) }

    private fun ComposeUiTest.enabled(tag: String): Boolean? = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.let { it.config.getOrNull(SemanticsProperties.Disabled) == null }

    private fun ComposeUiTest.text(tag: String): String? = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun ComposeUiTest.texts(tag: String): List<String> = onAllNodesWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    private fun EditorState.screen(x: Float, y: Float): Offset =
        Offset(x * viewport.view.scale + viewport.view.pan.x, y * viewport.view.scale + viewport.view.pan.y)

    private fun EditorState.screen(component: CircuitComponent): Offset {
        val bounds = component.bounds(componentRegistry)
        return screen((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
    }

    private fun ComposeUiTest.tapCanvas(point: Offset) {
        onNodeWithTag("editor-canvas").performTouchInput { click(point) }
        waitForIdle()
    }

    private fun localized(key: String, vararg args: Pair<String, Any?>) = message(key, *args).resolve(portuguese)

    private fun placementStrip(width: Int, height: Int, name: String) = runDesktopComposeUiTest(width, height) {
        val state = EditorState(fixture)
        app(state)
        state.place(ComponentKind.NOT)
        waitForIdle()
        screenshot("c34-place-mode-$name")
        val strip = exists("placement-strip")
        val facingButtons = listOf("north", "east", "south", "west").filter { exists("placement-facing-$it") }
        val stripNode = onAllNodesWithTag("placement-strip").fetchSemanticsNodes().firstOrNull()
        val buttonWidths = (facingButtons.map { "placement-facing-$it" } + listOf("placement-rotate", "placement-attributes"))
            .filter { exists(it) }.associateWith { onNodeWithTag(it).fetchSemanticsNode().size }
        // The strip is a titled pane, not an extra focus stop announced before its buttons.
        val stripDescription = stripNode?.config?.getOrNull(SemanticsProperties.ContentDescription)
        val paneTitles = onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.PaneTitle] }
        val clipped = stripNode?.let { bar ->
            buttonWidths.keys.filter { tag ->
                val node = onNodeWithTag(tag).fetchSemanticsNode()
                node.positionInRoot.x < bar.positionInRoot.x - 0.5f ||
                    node.positionInRoot.x + node.size.width > bar.positionInRoot.x + bar.size.width + 0.5f
            }
        }
        // Eight south-facing NOT gates: south once, then tap after tap, re-choosing NOT in between.
        tapIfShown("placement-facing-south")
        onNodeWithTag("editor-canvas").performTouchInput { down(state.screen(150f, 250f)) }
        waitForIdle()
        screenshot("c34-south-ghost-under-finger-$name")
        onNodeWithTag("editor-canvas").performTouchInput { up() }
        waitForIdle()
        val first = state.document.components.last()
        state.place(ComponentKind.NOT)
        waitForIdle()
        tapCanvas(state.screen(250f, 250f))
        val second = state.document.components.last()
        observe("c34-placement-strip-$name", "strip" to strip, "facingButtons" to facingButtons,
            "buttonSizes" to buttonWidths, "clipped" to clipped, "stripContentDescription" to stripDescription,
            "paneTitles" to paneTitles,
            "firstFacing" to first.attributes["facing"], "secondFacing" to second.attributes["facing"])
        assertTrue(strip, "PLACE mode shows the placement strip")
        assertEquals(null, stripDescription, "no extra focus stop before the strip's buttons")
        assertTrue(localized("placement_tool_strip", "name" to ComponentKind.NOT.localizedLabel().resolve(portuguese)) in paneTitles,
            "the strip is a pane titled for TalkBack: $paneTitles")
        assertEquals(listOf("north", "east", "south", "west"), facingButtons)
        assertEquals(emptyList(), clipped)
        buttonWidths.values.forEach { assertTrue(it.width >= 48 && it.height >= 48, "48 dp targets: $buttonWidths") }
        assertEquals(ComponentKind.NOT, first.kind)
        assertEquals("south", first.attributes["facing"])
        assertEquals("south", second.attributes["facing"], "re-choosing NOT keeps the tool's facing")
    }

    @Test fun phonePlacementStripSetsFacingBeforePlacing() = placementStrip(390, 844, "phone")
    @Test fun narrowPhonePlacementStripFits() = placementStrip(360, 780, "phone-360")

    @Test
    fun toolAttributesSetTheWidthOfSeveralPinsBeforePlacing() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        state.place(ComponentKind.INPUT)
        waitForIdle()
        val opened = tapIfShown("placement-attributes")
        val sheet = exists("placement-attributes-sheet")
        screenshot("c34-tool-attributes-sheet")
        // A width of 1 cannot go lower: − is disabled instead of silently doing nothing.
        val minusAtOne = enabled("placement-attr-width-minus")
        repeat(7) { tapIfShown("placement-attr-width-plus") }
        val typedWidth = text("placement-attr-width")
        val minusAtEight = enabled("placement-attr-width-minus")
        screenshot("c34-tool-attributes-width-8")
        tapIfShown("placement-attributes-done")
        tapCanvas(state.screen(150f, 250f))
        val first = state.document.components.last()
        state.place(ComponentKind.INPUT)
        waitForIdle()
        tapCanvas(state.screen(150f, 320f))
        val second = state.document.components.last()
        screenshot("c34-two-8-bit-pins")
        observe("c34-tool-attributes", "opened" to opened, "sheet" to sheet, "widthField" to typedWidth,
            "minusEnabledAtWidth1" to minusAtOne, "minusEnabledAtWidth8" to minusAtEight,
            "firstWidth" to first.attributes["width"], "secondWidth" to second.attributes["width"])
        assertTrue(sheet, "Tool attributes opens a sheet")
        assertEquals("8", typedWidth)
        assertEquals(false, minusAtOne, "− is disabled at the smallest width")
        assertEquals(true, minusAtEight)
        assertEquals(ComponentKind.INPUT, first.kind)
        assertEquals("8", first.attributes["width"])
        assertEquals("8", second.attributes["width"])
    }

    @Test
    fun toolAttributesStoreTypedQuantitiesAsTheInspectorDoes() = runDesktopComposeUiTest(390, 844) {
        // The sheet's letter keyboard allows "4.7k" and "100uF"; Evolution's EngineeringAttribute writes whole numbers.
        val state = EditorState(fixture)
        app(state)
        state.place(ExtraIoKinds.Ne555)
        waitForIdle()
        tap("placement-attributes")
        onNodeWithTag("placement-attr-r1").performTextReplacement("4.7k")
        waitForIdle()
        val typedResistance = text("placement-attr-r1")
        onNodeWithTag("placement-attr-c1").performTextReplacement("100uF")
        waitForIdle()
        val toolResistance = state.placementPreview()?.attributes?.get("r1")
        val toolCapacitance = state.placementPreview()?.attributes?.get("c1")
        // A value the attribute refuses says why in the sheet and leaves the tool as it was.
        onNodeWithTag("placement-attr-c1").performTextReplacement("5uF")
        waitForIdle()
        val afterRefusal = state.placementPreview()?.attributes?.get("c1")
        val expectedRefusal = (PropertyEditorModel.validate(state.componentRegistry, listOfNotNull(state.placementPreview()), "c1", "5uF")
            as? PropertyEditResult.Invalid)?.notice?.resolve(portuguese)
        val refusal = expectedRefusal?.let { onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().size } ?: 0
        screenshot("c34-tool-attributes-ne555-quantities")
        tapIfShown("placement-attributes-done")
        tapCanvas(state.screen(150f, 250f))
        val placed = state.document.components.last()
        val xml = state.exportXml()
        observe("c34-tool-attributes-ne555", "typedResistance" to typedResistance, "toolResistance" to toolResistance,
            "toolCapacitance" to toolCapacitance, "refusal" to refusal, "afterRefusal" to afterRefusal,
            "placedKind" to placed.kind, "placedAttributes" to placed.attributes)
        assertEquals("4.7k", typedResistance, "the typed text stays while it is being typed")
        assertEquals("4700", toolResistance)
        assertEquals("100", toolCapacitance)
        assertNotNull(expectedRefusal)
        assertEquals(1, refusal, "the sheet shows the inspector's localized refusal: $expectedRefusal")
        assertEquals("100", afterRefusal)
        assertEquals(ExtraIoKinds.Ne555, placed.kind)
        assertTrue("<a name=\"r1\" val=\"4700\"/>" in xml, xml)
        assertTrue("<a name=\"c1\" val=\"100\"/>" in xml, xml)
    }

    @Test
    fun aPlacementDragReleasedOverTheStripPlacesNothing() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        state.place(ComponentKind.NOT)
        waitForIdle()
        val before = state.document.components.size
        val canvas = onNodeWithTag("editor-canvas").fetchSemanticsNode()
        val strip = onAllNodesWithTag("placement-strip").fetchSemanticsNodes().firstOrNull()
        val target = strip?.let { it.positionInRoot - canvas.positionInRoot + Offset(it.size.width / 2f, it.size.height / 2f) }
        val from = state.screen(150f, 200f)
        if (target != null) onNodeWithTag("editor-canvas").performTouchInput {
            down(from)
            for (step in 1..10) moveTo(from + (target - from) * (step / 10f))
            up()
        }
        waitForIdle()
        screenshot("r06-2-drop-over-strip")
        observe("r06-2-drop-over-strip", "strip" to (strip != null), "dropAt" to target,
            "placed" to state.document.components.size - before, "mode" to state.mode)
        assertNotNull(target, "the strip floats over the canvas")
        assertEquals(before, state.document.components.size, "a drop over the floating strip adds nothing")
    }

    @Test
    fun aDoubleTapOnALabelledPinEditsItsLabel() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        state.changeMode(EditorMode.EDIT)
        waitForIdle()
        val pin = state.component("E2E_IN")
        val history = state.undoHistory.size
        onNodeWithTag("editor-canvas").performTouchInput { doubleClick(state.screen(pin)) }
        waitForIdle()
        val dialog = exists("text-tool-input")
        val prefilled = text("text-tool-input")
        screenshot("c46-double-tap-label")
        if (dialog) {
            onNodeWithTag("text-tool-input").performTextReplacement("Cin")
            waitForIdle()
            tap("text-tool-apply")
        }
        val label = state.document.components.first { it.id == pin.id }.label
        observe("c46-double-tap", "dialog" to dialog, "prefilled" to prefilled, "labelAfter" to label,
            "historySteps" to state.undoHistory.size - history)
        assertTrue(dialog, "a double tap on a labelled pin opens the label editor")
        assertEquals("E2E_IN", prefilled)
        assertEquals("Cin", label)
        assertEquals(1, state.undoHistory.size - history)
        state.undo()
        assertEquals("E2E_IN", state.document.components.first { it.id == pin.id }.label)
    }

    @Test
    fun theRegisterPokeDialogNamesTheRegister() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(registerProject)
        app(state, registerProject)
        state.changeMode(EditorMode.INTERACT)
        waitForIdle()
        val register = state.component("R1")
        tapCanvas(state.screen(register))
        val dialog = exists("sequential-poke-hex")
        val title = texts("sequential-poke-title")
        screenshot("c49-register-poke-dialog")
        observe("c49-register-poke", "dialog" to dialog, "title" to title)
        assertTrue(dialog)
        assertTrue(title.any { "R1" in it }, "the title names the register: $title")
    }

    @Test
    fun matrixPlacementValidatesTheBaseLabelAndStepsTheSpacing() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state)
        state.place(ComponentKind.LED)
        state.togglePlacementMatrix()
        waitForIdle()
        tapCanvas(state.screen(150f, 250f))
        val placement = assertNotNull(state.pendingMatrixPlacement)
        onNodeWithTag("matrix-label").performTextReplacement("led 1")
        waitForIdle()
        val confirmEnabled = enabled("matrix-confirm")
        val labelError = texts("matrix-label-error")
        screenshot("x9-matrix-invalid-label")
        val dxBefore = text("matrix-dx")
        val minusAtMinimum = enabled("matrix-dx-minus")
        val stepped = tapIfShown("matrix-dx-plus")
        val dxAfter = text("matrix-dx")
        onNodeWithTag("matrix-label").performTextReplacement("LED")
        waitForIdle()
        val confirmAfterFix = enabled("matrix-confirm")
        val confirmText = onAllNodesWithTag("matrix-confirm", useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .ifEmpty {
                onAllNodesWithTag("matrix-confirm").fetchSemanticsNodes().firstOrNull()?.let { node ->
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
                }.orEmpty()
            }
        screenshot("x9-matrix-valid")
        observe("x9-matrix", "minDx" to placement.minDx, "confirmEnabledWithInvalidLabel" to confirmEnabled,
            "labelError" to labelError, "dxBefore" to dxBefore, "stepped" to stepped, "dxAfter" to dxAfter,
            "confirmEnabledAfterFix" to confirmAfterFix, "confirmText" to confirmText, "minusEnabledAtMinimum" to minusAtMinimum)
        assertEquals(false, confirmEnabled, "an invalid base label disables Place")
        assertEquals(false, minusAtMinimum, "− is disabled at the minimum spacing")
        assertTrue(labelError.isNotEmpty(), "the label field explains the problem")
        assertEquals((placement.minDx + 10).toString(), dxAfter)
        assertEquals(true, confirmAfterFix)
    }

    @Test
    fun aMultiBitInputOffersSetValueAndToggleBitActions() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(multiBitProject)
        app(state, multiBitProject)
        state.changeMode(EditorMode.INTERACT)
        waitForIdle()
        val hex = state.component("A")
        val node = onAllNodesWithTag("canvas-component:${hex.id}").fetchSemanticsNodes().firstOrNull()
        val actions = node?.config?.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val labels = actions.map { it.label }
        val setValue = actions.firstOrNull { it.label == localized("a11y_input_set_value", "name" to "A") }
        if (setValue != null) {
            runOnUiThread { setValue.action() }
            waitForIdle()
        }
        val dialog = exists("input-value-field")
        if (dialog) {
            onNodeWithTag("input-value-field").performTextReplacement("5A")
            waitForIdle()
            screenshot("x0-set-value-dialog")
            tap("input-value-apply")
        }
        val value = state.simulation.vectorValues[hex.id]?.toULongOrNull()
        val bits = state.component("B")
        val bitNode = onAllNodesWithTag("canvas-component:${bits.id}").fetchSemanticsNodes().firstOrNull()
        val toggle = bitNode?.config?.getOrNull(SemanticsActions.CustomActions).orEmpty()
            .firstOrNull { it.label == localized("a11y_input_toggle_bit", "name" to "B") }
        if (toggle != null) {
            runOnUiThread { toggle.action() }
            waitForIdle()
        }
        val chooser = exists("input-bit-chooser")
        screenshot("x0-toggle-bit-chooser")
        tapIfShown("input-bit-3")
        val bitValue = state.simulation.vectorValues[bits.id]?.toULongOrNull()
        observe("x0-multi-bit-actions", "actions" to labels, "setValueDialog" to dialog, "hexValue" to value,
            "bitChooser" to chooser, "bitValue" to bitValue)
        assertNotNull(setValue, "an 8-bit input offers Set value: $labels")
        assertEquals(0x5AuL, value)
        assertNotNull(toggle, "an 8-bit input offers Toggle bit")
        assertTrue(chooser)
        assertEquals(0x08uL, bitValue)
    }

    @Test
    fun aComponentNodeOffersTheComponentMenuToTalkBack() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(memoryProject)
        app(state, memoryProject)
        state.changeMode(EditorMode.EDIT)
        waitForIdle()
        fun labels(kind: ComponentKind): List<String> = state.document.components.first { it.kind == kind }.let { component ->
            onAllNodesWithTag("canvas-component:${component.id}").fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        }
        val ram = labels(ComponentKind.RAM)
        val gate = labels(ComponentKind.NOT)
        val sub = labels(ComponentKind.SUBCIRCUIT)
        val ramComponent = state.document.components.first { it.kind == ComponentKind.RAM }
        val clear = onAllNodesWithTag("canvas-component:${ramComponent.id}").fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsActions.CustomActions).orEmpty()
            .firstOrNull { it.label == localized("component_menu_memory_edit") }
        if (clear != null) { runOnUiThread { clear.action() }; waitForIdle() }
        val memoryEditorOpened = exists("memory-editor-panel") || state.selectedIds == setOf(ramComponent.id)
        observe("c42-talkback-component-menu", "ram" to ram, "not" to gate, "subcircuit" to sub,
            "editInvoked" to (clear != null), "memoryEditorOrSelection" to memoryEditorOpened)
        assertTrue(localized("component_menu_memory_edit") in ram, "RAM: $ram")
        assertTrue(localized("component_menu_memory_clear") in ram, "RAM: $ram")
        assertTrue(localized("component_menu_rotate_left") in gate, "NOT: $gate")
        assertTrue(localized("action_view_subcircuit", "name" to "sub") in sub, "subcircuit: $sub")
        assertFalse(gate.any { it == localized("component_menu_delete") }, "Remove already covers Delete: $gate")
        assertTrue(memoryEditorOpened)
    }

    private companion object {
        const val registerProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Memory" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(200,200)" name="Register"><a name="label" val="R1"/></comp>
  </circuit>
</project>"""

        const val multiBitProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(150,150)" name="Pin">
      <a name="appearance" val="NewPins"/><a name="width" val="8"/><a name="radix" val="16"/><a name="label" val="A"/>
    </comp>
    <comp lib="0" loc="(150,300)" name="Pin">
      <a name="appearance" val="NewPins"/><a name="width" val="8"/><a name="label" val="B"/>
    </comp>
  </circuit>
</project>"""

        const val memoryProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(250,150)" name="RAM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/></comp>
    <comp lib="1" loc="(100,350)" name="NOT Gate"/>
    <comp loc="(250,400)" name="sub"/>
  </circuit>
  <circuit name="sub">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="appearance" val="NewPins"/></comp>
  </circuit>
</project>"""
    }
}
