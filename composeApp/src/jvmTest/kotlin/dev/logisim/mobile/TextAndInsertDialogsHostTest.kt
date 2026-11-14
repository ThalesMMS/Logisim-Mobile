package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Text tool dialog keeping a label Evolution refuses (C41), the titled memory address dialog (C49) and the
 * Explorer's Insert dialog choosing a free position without typed coordinates (X1), at phone size (390x844).
 */
@OptIn(ExperimentalTestApi::class)
class TextAndInsertDialogsHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
    private val portuguese = AppLanguage.PORTUGUESE

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.enabled(tag: String): Boolean? = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.let { it.config.getOrNull(SemanticsProperties.Disabled) == null }

    private fun ComposeUiTest.text(tag: String): String? = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun localized(key: String, vararg args: Pair<String, Any?>) = message(key, *args).resolve(portuguese)

    private fun ComposeUiTest.content(body: @androidx.compose.runtime.Composable () -> Unit) = setContent {
        CompositionLocalProvider(LocalAppLanguage provides portuguese) {
            LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { body() } }
        }
    }

    @Test
    fun theTextToolKeepsALabelEvolutionRefusesAndSaysWhy() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val pin = state.document.components.first { it.label == "E2E_IN" }
        val edit = assertNotNull(state.beginTextToolEdit(TextToolTarget.Existing(pin.id), null))
        var open by mutableStateOf(true)
        content { if (open) TextToolDialog(edit, onApply = { state.applyTextToolEdit(edit, it) }, onDismiss = { open = false }) }
        waitForIdle()
        val results = mutableMapOf<String, List<Any?>>()
        for (typed in listOf("Carry In", "out", "2nd")) {
            onNodeWithTag("text-tool-input").performTextReplacement(typed)
            waitForIdle()
            val applyEnabled = enabled("text-tool-apply")
            val error = onAllNodesWithTag("text-tool-error", useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            if (typed == "Carry In") screenshot("c41-text-tool-refused-label")
            onNodeWithTag("text-tool-apply").performClick()
            waitForIdle()
            results[typed] = listOf(applyEnabled, error, open, text("text-tool-input"))
            if (!open) break
        }
        val label = state.document.components.first { it.id == pin.id }.label
        observe("c41-text-tool-validation", "results(applyEnabled,error,dialogOpen,draft)" to results, "label" to label,
            "status" to state.statusText.key)
        for (typed in listOf("Carry In", "out", "2nd")) {
            val (applyEnabled, error, stillOpen, draft) = assertNotNull(results[typed], "$typed was tried")
            assertEquals(false, applyEnabled, "$typed disables Apply")
            assertTrue((error as List<*>).isNotEmpty(), "$typed shows why")
            assertEquals(true, stillOpen, "$typed keeps the dialog")
            assertEquals(typed, draft, "$typed keeps the typed text")
        }
        assertEquals("E2E_IN", label)
    }

    @Test
    fun theMemoryAddressDialogHasATitle() = runDesktopComposeUiTest(390, 844) {
        content { CanvasMemoryAddressEditor("ram", "0", onGo = { null }, onDismiss = {}) }
        waitForIdle()
        screenshot("c49-memory-address-dialog")
        val title = onAllNodesWithTag("memory-word-title", useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        observe("c49-memory-address", "title" to title)
        assertTrue(title.isNotEmpty(), "the address dialog is titled")
    }

    @Test
    fun aTextToolTapBesideALabelEditsItInsteadOfAddingAText() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        var measurer: TextMeasurer? = null
        content { measurer = rememberTextMeasurer() }
        waitForIdle()
        val text = assertNotNull(measurer)
        val pin = state.document.components.first { it.label == "E2E_IN" }
        val label = assertNotNull(classicComponentLabel(pin, classicComponentBounds(pin, state.componentRegistry)))
        val bounds = text.visualTextBounds(label)
        // Four units below the label "E2E_IN", on empty canvas.
        val near = Offset(((bounds.left + bounds.right) / 2).toFloat(), (bounds.bottom + 4).toFloat())
        val mouse = text.textToolTarget(state.document, near, state.componentRegistry)
        val finger = text.textToolTarget(state.document, near, state.componentRegistry, tolerance = 12f)
        val far = Offset(near.x, (bounds.bottom + 40).toFloat())
        val farTarget = text.textToolTarget(state.document, far, state.componentRegistry, tolerance = 12f)
        observe("c46-text-tool-near-miss", "labelBounds" to bounds, "press" to near, "mouseTarget" to mouse,
            "fingerTarget" to finger, "farTarget" to farTarget)
        assertEquals(TextToolTarget.Empty(canvasPoint(near, snapToGrid = false)), mouse, "the mouse keeps Evolution's exact hit")
        assertEquals(TextToolTarget.Existing(pin.id), finger, "a finger beside the label edits it")
        assertEquals(TextToolTarget.Empty(canvasPoint(far, snapToGrid = false)), farTarget)

        // The dialog names its field Text for a Text annotation, Label otherwise.
        val annotation = assertNotNull(state.beginTextToolEdit(TextToolTarget.Empty(GridPoint(300, 300)),
            state.componentRegistry.create(ComponentKind.TEXT, "t", GridPoint(0, 0))))
        var open by mutableStateOf(true)
        content { if (open) TextToolDialog(annotation, onApply = { state.applyTextToolEdit(annotation, it) }, onDismiss = { open = false }) }
        waitForIdle()
        assertTrue(onAllNodesWithText(localized("annotation_text"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aFingerTapInsideAnUnlabelledPinLabelsThatPinNotItsNeighbour() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(stackedPins)
        var measurer: TextMeasurer? = null
        content { measurer = rememberTextMeasurer() }
        waitForIdle()
        val text = assertNotNull(measurer)
        val registry = state.componentRegistry
        val top = state.document.components.first { it.label == "TOP" }
        val bottom = state.document.components.first { it.label.isEmpty() }
        val label = text.visualTextBounds(assertNotNull(classicComponentLabel(top, classicComponentBounds(top, registry))))
        val body = classicComponentBounds(bottom, registry)
        // Inside the unlabelled pin's body, two units below TOP's label (which reaches into the body's top edge).
        val press = Offset(((label.left + label.right) / 2).toFloat().coerceIn(body.left + 1f, body.right - 1f),
            maxOf(body.top + 1f, label.bottom.toFloat() + 2f))
        val dx = maxOf(label.left - press.x, 0.0, press.x - label.right)
        val dy = maxOf(label.top - press.y, 0.0, press.y - label.bottom)
        val labelDistance = kotlin.math.sqrt(dx * dx + dy * dy)
        val finger = text.textToolTarget(state.document, press, registry, tolerance = 12f)
        val mouse = text.textToolTarget(state.document, press, registry)
        observe("c46-text-tool-exact-body", "topLabel" to label, "bottomBody" to body, "press" to press,
            "distanceToTopLabel" to labelDistance, "fingerTarget" to finger, "mouseTarget" to mouse)
        assertTrue(body.contains(press) && labelDistance > 0.0 && labelDistance <= 12.0,
            "the press is in the body, off TOP's label but within its reach")
        assertEquals(TextToolTarget.Existing(bottom.id), finger, "an exact body hit beats a neighbour's label in reach")
        assertEquals(TextToolTarget.Existing(bottom.id), mouse)
    }

    @Test
    fun theExplorerOffersSetValueAndToggleBitForAMultiBitInput() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(multiBitProject)
        val pin = state.document.components.single()
        val edits = mutableListOf<String>()
        val actions = CircuitAccessibilityActions(
            selectComponent = state::select, openProperties = {}, removeComponent = state::removeComponent,
            activateComponent = state::activateComponent, selectWire = {}, removeWire = { false },
            connect = { _, _ -> false }, insertComponent = state::insertComponent,
            editInputValue = { id -> edits += id; true }, toggleInputBit = state::toggleInputBit,
        )
        content {
            AccessibleCircuitExplorer(state.document, state.simulation, state.componentRegistry,
                state.selectedIds, emptySet(), actions)
        }
        waitForIdle()
        val setValue = localized("a11y_input_set_value", "name" to "A")
        val toggleBit = localized("a11y_input_toggle_bit", "name" to "A")
        val customActions = onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.CustomActions)).fetchSemanticsNodes()
            .flatMap { node -> node.config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].map { it.label } }
        onAllNodes(hasText(setValue) and hasClickAction())[0].performScrollTo().performClick()
        waitForIdle()
        onAllNodes(hasText(toggleBit) and hasClickAction())[0].performScrollTo().performClick()
        waitForIdle()
        val chooser = exists("input-bit-chooser")
        screenshot("x0-explorer-toggle-bit-chooser")
        if (chooser) onNodeWithTag("input-bit-3").performScrollTo().performClick()
        waitForIdle()
        val value = state.simulation.vectorValues[pin.id]?.toULongOrNull()
        // A locked editor changes nothing, and the Explorer says so instead of staying silent.
        state.setInteractionEnabled(false)
        onAllNodes(hasText(toggleBit) and hasClickAction())[0].performScrollTo().performClick()
        waitForIdle()
        if (exists("input-bit-2")) onNodeWithTag("input-bit-2").performScrollTo().performClick()
        waitForIdle()
        val failure = onAllNodesWithText(localized("a11y_explorer_operation_failed"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        screenshot("x0-explorer-locked-toggle")
        observe("x0-explorer-actions", "customActions" to customActions, "setValueCalls" to edits, "bitChooser" to chooser,
            "valueAfterBit3" to value, "lockedFailureNotice" to failure,
            "valueAfterLockedToggle" to state.simulation.vectorValues[pin.id]?.toULongOrNull())
        assertTrue(setValue in customActions && toggleBit in customActions, "the Explorer node offers both: $customActions")
        assertEquals(listOf(pin.id), edits, "Set value opens the value dialog for A")
        assertTrue(chooser)
        assertEquals(0x08uL, value)
        assertTrue(failure, "a toggle that changes nothing is reported")
        assertEquals(0x08uL, state.simulation.vectorValues[pin.id]?.toULongOrNull())
    }

    @Test
    fun insertSaysWhenNoFreeSpotIsNearby() = runDesktopComposeUiTest(390, 844) {
        // A long wire runs right of the selected pin along its row: every spot on the row would touch it.
        val state = EditorState(longWireProject)
        val pin = state.document.components.single()
        state.select(pin.id)
        val actions = CircuitAccessibilityActions(
            selectComponent = state::select, openProperties = {}, removeComponent = state::removeComponent,
            activateComponent = state::activateComponent, selectWire = {}, removeWire = { false },
            connect = { _, _ -> false }, insertComponent = state::insertComponent,
        )
        content {
            AccessibleCircuitExplorer(state.document, state.simulation, state.componentRegistry,
                state.selectedIds, emptySet(), actions)
        }
        waitForIdle()
        onNodeWithTag("a11y-explorer-insert").performScrollTo().performClick()
        waitForIdle()
        val label = ComponentKind.NOT.localizedLabel().resolve(portuguese)
        onNodeWithText(localized("a11y_explorer_insert_kind")).performTextReplacement(ComponentKind.NOT.name)
        waitForIdle()
        onAllNodes(hasText(label) and hasAnyAncestor(isDialog()) and hasClickAction() and !hasSetTextAction())[0].performClick()
        waitForIdle()
        val noSpot = onAllNodesWithText(localized("a11y_insert_no_free_position"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val typedCoordinates = onAllNodesWithText(localized("a11y_explorer_insert_invalid"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        screenshot("x1-insert-no-free-spot")
        observe("x1-insert-no-free-spot", "components" to state.document.components.map { it.kind.name to it.position },
            "noFreeSpotMessage" to noSpot, "typedCoordinatesMessage" to typedCoordinates)
        assertEquals(1, state.document.components.size, "nothing is inserted on the wire")
        assertTrue(noSpot, "the dialog says no free spot was found nearby")
        assertFalse(typedCoordinates, "it does not ask to fix coordinates nobody typed")
    }

    @Test
    fun insertPicksAFreeSpotWithoutTypedCoordinates() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(emptyProject)
        val actions = CircuitAccessibilityActions(
            selectComponent = state::select, openProperties = {}, removeComponent = state::removeComponent,
            activateComponent = state::activateComponent, selectWire = {}, removeWire = { false },
            connect = { _, _ -> false }, insertComponent = state::insertComponent,
        )
        content {
            AccessibleCircuitExplorer(state.document, state.simulation, state.componentRegistry,
                state.selectedIds, emptySet(), actions)
        }
        waitForIdle()
        fun insert(kind: ComponentKind, screenshotName: String? = null): Boolean {
            onNodeWithTag("a11y-explorer-insert").performScrollTo().performClick()
            waitForIdle()
            val xField = exists("a11y-insert-x") || onAllNodesWithText(localized("a11y_explorer_insert_x")).fetchSemanticsNodes().isNotEmpty()
            val label = kind.localizedLabel().resolve(portuguese)
            onNodeWithText(localized("a11y_explorer_insert_kind")).performTextReplacement(kind.name)
            waitForIdle()
            screenshotName?.let { screenshot(it) }
            val choice = onAllNodes(hasText(label) and hasAnyAncestor(isDialog()) and hasClickAction() and !hasSetTextAction())
            assertTrue(choice.fetchSemanticsNodes().isNotEmpty(), "$label listed")
            choice[0].performClick()
            waitForIdle()
            return xField
        }
        val xShownFirst = insert(ComponentKind.NOT, "x1-insert-dialog")
        val xShownSecond = insert(ComponentKind.INPUT)
        // Custom coordinates reveals X and Y, which step by 10.
        onNodeWithTag("a11y-explorer-insert").performScrollTo().performClick()
        waitForIdle()
        if (exists("a11y-insert-position-custom")) onNodeWithTag("a11y-insert-position-custom").performClick()
        waitForIdle()
        if (exists("a11y-insert-x-plus")) onNodeWithTag("a11y-insert-x-plus").performClick()
        waitForIdle()
        val customX = text("a11y-insert-x")
        screenshot("x1-insert-custom-coordinates")
        val placed = state.document.components
        val bounds = placed.map { it.bounds(state.componentRegistry) }
        val overlapping = bounds.size == 2 && bounds[0].left < bounds[1].right && bounds[1].left < bounds[0].right &&
            bounds[0].top < bounds[1].bottom && bounds[1].top < bounds[0].bottom
        observe("x1-insert", "positions" to placed.map { it.kind.name to it.position }, "overlapping" to overlapping,
            "coordinateFieldsShownByDefault" to listOf(xShownFirst, xShownSecond), "customXAfterPlus" to customX)
        assertEquals(2, placed.size)
        assertNotEquals(placed[0].position, placed[1].position)
        assertFalse(overlapping, "the second insert does not cover the first")
        assertFalse(xShownFirst, "X and Y are hidden until Custom coordinates is chosen")
        assertEquals("110", customX)
    }

    // A1 and the nested list: the Insert dialog scrolls as one list (its results are not a second scrolling list
    // inside it), and the coordinate fields carry a Next / Done key, so a keyboard without one (iOS's number pad)
    // never traps the user in X. Next moves to Y, Done hides the keyboard.
    @Test
    fun insertScrollsAsOneListAndItsCoordinatesHaveNextAndDone() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(emptyProject)
        val actions = CircuitAccessibilityActions(
            selectComponent = state::select, openProperties = {}, removeComponent = state::removeComponent,
            activateComponent = state::activateComponent, selectWire = {}, removeWire = { false },
            connect = { _, _ -> false }, insertComponent = state::insertComponent,
        )
        var hidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { hidden++ }
        }
        content {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                AccessibleCircuitExplorer(state.document, state.simulation, state.componentRegistry,
                    state.selectedIds, emptySet(), actions)
            }
        }
        waitForIdle()
        onNodeWithTag("a11y-explorer-insert").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("a11y-insert-position-custom").performClick()
        waitForIdle()
        val scrollers = onAllNodes(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().size
        // Every type is in the one list, the last library's last type too.
        val listed = onAllNodes(hasText(ComponentKind.NOT.localizedLabel().resolve(portuguese)) and hasAnyAncestor(isDialog()) and
            hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().size
        fun ime(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ImeAction)
        fun focused(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        val imeX = ime("a11y-insert-x")
        val imeY = ime("a11y-insert-y")
        onNodeWithTag("a11y-insert-x").performClick()
        waitForIdle()
        val xFocused = focused("a11y-insert-x")
        onNodeWithTag("a11y-insert-x").performImeAction()
        waitForIdle()
        val yFocusedAfterNext = focused("a11y-insert-y")
        val hiddenBeforeDone = hidden
        onNodeWithTag("a11y-insert-y").performImeAction()
        waitForIdle()
        val keyboardHiddenByDone = hidden > hiddenBeforeDone
        // iOS leaves its keyboard up while the field keeps the focus, so Done also clears it.
        val yFocusedAfterDone = focused("a11y-insert-y")
        // A signed coordinate can be typed: the minus sign is kept and the point is accepted.
        onNodeWithTag("a11y-insert-x").performTextReplacement("-40")
        waitForIdle()
        screenshot("a1-insert-one-list")
        observe("a1-insert-one-list", "verticalScrollContainers" to scrollers, "notListed" to listed, "imeX" to imeX,
            "imeY" to imeY, "xFocused" to xFocused, "yFocusedAfterNext" to yFocusedAfterNext,
            "keyboardHiddenByDone" to keyboardHiddenByDone, "yFocusedAfterDone" to yFocusedAfterDone, "x" to text("a11y-insert-x"))
        assertEquals(1, scrollers, "the dialog has one vertical scroll container")
        assertEquals(1, listed)
        assertEquals(ImeAction.Next, imeX)
        assertEquals(ImeAction.Done, imeY)
        assertTrue(xFocused && yFocusedAfterNext, "Next on X moves the focus to Y")
        assertTrue(keyboardHiddenByDone, "Done on Y hides the keyboard")
        assertFalse(yFocusedAfterDone, "Done on Y clears the focus")
        assertEquals("-40", text("a11y-insert-x"))
        assertEquals(GridPoint(-40, 100), accessibleInsertionPoint("-40", "100"))
    }

    private companion object {
        const val emptyProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main"/>
</project>"""

        /** A labelled pin whose label sits south of it, and an unlabelled pin just below that label. */
        const val stackedPins = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="appearance" val="NewPins"/><a name="label" val="TOP"/><a name="labelloc" val="south"/></comp>
    <comp lib="0" loc="(100,140)" name="Pin"><a name="appearance" val="NewPins"/></comp>
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
  </circuit>
</project>"""

        const val longWireProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="appearance" val="NewPins"/><a name="label" val="S"/></comp>
    <wire from="(100,100)" to="(4000,100)"/>
  </circuit>
</project>"""
    }
}
