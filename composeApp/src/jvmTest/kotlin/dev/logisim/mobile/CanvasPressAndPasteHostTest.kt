package dev.logisim.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.rememberLocalizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Presses and paste on the shared canvas at phone size (390x844, density 1), wired to an [EditorState] as App wires
 * it: the long-press menu opening while the finger is down with a haptic tick (C40), edge auto-pan while dragging
 * (C43), and a touch paste that a drag moves and only the button commits (C45). Each scenario writes what it
 * observed to `behavior-<scenario>.txt` beside the screenshots before asserting.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasPressAndPasteHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")

    private class Recorder : HapticFeedback {
        val haptics = mutableListOf<HapticFeedbackType>()
        val moves = mutableListOf<GridPoint>()
        var pasteConfirms = 0
        val pastePreviews = mutableListOf<GridPoint>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }

    private fun ComposeUiTest.editor(state: EditorState): Recorder {
        val recorder = Recorder()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE, LocalHapticFeedback provides recorder) {
                LogisimTheme(dark = false) {
                    val strings = rememberLocalizer()
                    Column(Modifier.fillMaxSize()) {
                        CircuitCanvas(
                            document = state.document, simulation = state.simulation, registry = state.componentRegistry,
                            selectedId = state.selectedId, pendingWire = state.pendingWire, wirePoke = state.wirePoke,
                            onWirePoke = state::pokeWire, onDismissWirePoke = state::dismissWirePoke, mode = state.mode,
                            modifier = Modifier.weight(1f).fillMaxWidth().testTag("canvas"),
                            onCanvasTap = state::tap, selectedIds = state.selectedIds, selectedWires = state.selectedWires,
                            viewportState = state.viewport, onViewportChange = state::updateViewport,
                            onSelectArea = state::selectInArea, onSelectComponent = state::select,
                            onSelectWire = state::selectWire,
                            onMoveSelection = { delta, keep -> recorder.moves += delta; state.moveSelected(delta, keep) },
                            keepConnectionsWhenMoving = state.keepConnectionsWhenMoving,
                            onCommand = state::handleCanvasCommand, onTogglePlacementMatrix = state::togglePlacementMatrix,
                            wirePokePrimaryRadix = WirePokeRadix.BINARY, wirePokeSecondaryRadix = null,
                            onViewSubcircuit = {}, showZoomControls = false,
                            onDrawEditToolWire = state::drawEditToolWire,
                            onComponentEvent = { id, event -> state.sendComponentEvent(id, event) },
                            showPlacementPreview = true, placementPrototype = state.placementPreview(),
                            pendingPaste = state.pendingPaste,
                            onMovePastePreview = { recorder.pastePreviews += it; state.movePastePreview(it) },
                            onConfirmPaste = { recorder.pasteConfirms++; state.confirmPaste() },
                        )
                        Text("${state.mode} · ${strings(state.statusText)} · ${state.document.components.size} / " +
                            "${state.document.wires.size}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(ChromeColors.Bar).padding(8.dp))
                    }
                }
            }
        }
        waitForIdle()
        return recorder
    }

    private fun EditorState.screen(x: Float, y: Float): Offset =
        Offset(x * viewport.view.scale + viewport.view.pan.x, y * viewport.view.scale + viewport.view.pan.y)

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    private fun EditorState.center(label: String): Offset {
        val bounds = component(label).bounds(componentRegistry)
        return screen((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
    }

    private fun ComposeUiTest.menuShown() = onAllNodesWithTag("canvas-context-menu").fetchSemanticsNodes().isNotEmpty()

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    @Test
    fun theLongPressMenuOpensWhileTheFingerIsDownAndALaterMoveDoesNotMove() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.EDIT)
        val recorder = editor(state)
        val gate = state.component("E2E_NOT")
        val center = state.center("E2E_NOT")
        onNodeWithTag("canvas").performTouchInput { down(center) }
        mainClock.advanceTimeBy(LongPressTimeoutMillis + 100)
        waitForIdle()
        val menuWhileDown = menuShown()
        val haptics = recorder.haptics.toList()
        screenshot("c40-menu-while-finger-down")
        onNodeWithTag("canvas").performTouchInput {
            for (step in 1..6) moveBy(Offset(5f, 0f))
            up()
        }
        waitForIdle()
        val menuAfterUp = menuShown()
        val position = state.document.components.first { it.id == gate.id }.position
        observe("c40-long-press", "menuWhileDown" to menuWhileDown, "haptics" to haptics,
            "menuAfterLift" to menuAfterUp, "moves" to recorder.moves, "gateBefore" to gate.position, "gateAfter" to position)
        assertTrue(menuWhileDown, "the menu opens at the long-press timeout, before the finger lifts")
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        assertTrue(menuAfterUp, "lifting the finger leaves the menu open")
        assertEquals(emptyList(), recorder.moves)
        assertEquals(gate.position, position)
    }

    @Test
    fun aHeldButtonInThePokeToolNeverOpensTheMenu() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(buttonProject)
        state.changeMode(EditorMode.INTERACT)
        val recorder = editor(state)
        val button = state.document.components.single { it.kind == ComponentKind.BUTTON }
        val bounds = button.bounds(state.componentRegistry)
        val center = state.screen((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        onNodeWithTag("canvas").performTouchInput { down(center) }
        mainClock.advanceTimeBy(LongPressTimeoutMillis + 100)
        waitForIdle()
        val pressed = state.simulation.componentStates[button.id]
        val menu = menuShown()
        onNodeWithTag("canvas").performTouchInput { up() }
        waitForIdle()
        observe("c40-held-button", "menuWhileHeld" to menu, "stateWhileHeld" to pressed, "haptics" to recorder.haptics)
        assertTrue(!menu)
        assertEquals(emptyList(), recorder.haptics)
    }

    @Test
    fun draggingAComponentIntoTheRightEdgePansTheView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.EDIT)
        val recorder = editor(state)
        val gate = state.component("E2E_NOT")
        val from = state.center("E2E_NOT")
        val width = onNodeWithTag("canvas").fetchSemanticsNode().size.width.toFloat()
        val to = Offset(width - 4f, from.y)
        val pan = state.viewport.view.pan
        val scale = state.viewport.view.scale
        onNodeWithTag("canvas").performTouchInput {
            down(from)
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
        }
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        val panWhileHeld = state.viewport.view.pan
        screenshot("c43-edge-auto-pan-held")
        onNodeWithTag("canvas").performTouchInput { up() }
        waitForIdle()
        val moved = state.document.components.first { it.id == gate.id }.position
        val onScreenUnits = (to.x - from.x) / scale
        observe("c43-edge-auto-pan", "panBefore" to pan, "panWhileHeld" to panWhileHeld, "moves" to recorder.moves,
            "gateBefore" to gate.position, "gateAfter" to moved, "onScreenUnits" to onScreenUnits)
        assertTrue(panWhileHeld.x < pan.x - 1f, "holding in the right margin pans the view: $pan -> $panWhileHeld")
        assertEquals(pan.y, panWhileHeld.y)
        val delta = assertNotNull(recorder.moves.singleOrNull())
        assertTrue(delta.x > onScreenUnits, "the drop follows the pan: ${delta.x} > $onScreenUnits")
    }

    @Test
    fun aFingerJitteringInTheRightMarginKeepsPanningAndTheDropMatchesTheView() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.EDIT)
        val recorder = editor(state)
        val gate = state.component("E2E_NOT")
        val from = state.center("E2E_NOT")
        val width = onNodeWithTag("canvas").fetchSemanticsNode().size.width.toFloat()
        val to = Offset(width - 12f, from.y)
        val pan = state.viewport.view.pan
        val startWorld = state.viewport.view.world(from)
        // Android keeps sending ACTION_MOVE for a resting finger (position, pressure and touch-major jitter): here half
        // a pixel back and forth every 12 ms for about half a second, never 40 ms without an event.
        onNodeWithTag("canvas").performTouchInput {
            down(from)
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            for (tick in 1..40) moveBy(Offset(if (tick % 2 == 1) 0.5f else -0.5f, 0f), delayMillis = 12)
        }
        waitForIdle()
        val panWhileJittering = state.viewport.view.pan
        screenshot("c43-edge-auto-pan-jitter")
        // The lift follows the last tick at once: the drop uses the view the finger last saw, not the one before it.
        onNodeWithTag("canvas").performTouchInput { up() }
        waitForIdle()
        val moved = state.document.components.first { it.id == gate.id }.position
        val expected = canvasPoint(state.viewport.view.world(to) - startWorld, snapToGrid = true)
        observe("c43-edge-auto-pan-jitter", "panBefore" to pan, "panWhileJittering" to panWhileJittering,
            "panAfterLift" to state.viewport.view.pan, "moves" to recorder.moves, "expectedDrop" to expected,
            "gateBefore" to gate.position, "gateAfter" to moved, "status" to state.statusText.key,
            "undoSteps" to state.undoHistory.size, "components" to state.document.components.map { it.label to it.position })
        assertTrue(panWhileJittering.x < pan.x - 1f, "a jittering finger in the right margin pans: $pan -> $panWhileJittering")
        assertEquals(pan.y, panWhileJittering.y)
        assertEquals(expected, recorder.moves.singleOrNull(), "the drop is where the finger is in the panned view")
    }

    @Test
    fun aMouseClickPastesAtThePointerAsInEvolution() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(widthErrorProject)
        state.changeMode(EditorMode.EDIT)
        state.select(state.component("G").id)
        state.copySelected()
        state.paste()
        val recorder = editor(state)
        val count = state.document.components.size
        val at = Offset(220f, 560f)
        onNodeWithTag("canvas").performMouseInput {
            moveTo(at)
            click(at)
        }
        waitForIdle()
        val expected = canvasPoint(state.viewport.view.world(at), snapToGrid = true)
        val pasted = state.document.components.lastOrNull()
        val anchor = pasted?.let { pasteAnchor(it.bounds(state.componentRegistry)) }
        observe("c45-mouse-click-paste", "pendingAfterClick" to (state.pendingPaste != null),
            "componentsAdded" to state.document.components.size - count, "pastedAnchor" to anchor, "expected" to expected,
            "confirmButtonClicks" to recorder.pasteConfirms)
        assertEquals(null, state.pendingPaste, "a mouse click pastes at once (SelectionActions.Paste then a click)")
        assertEquals(count + 1, state.document.components.size)
        assertEquals(expected, anchor, "the block's centre lands under the pointer")
        assertEquals(0, recorder.pasteConfirms, "no button was needed")
    }

    @Test
    fun aTouchDragMovesThePastePreviewAndOnlyTheButtonCommits() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(widthErrorProject)
        state.changeMode(EditorMode.EDIT)
        state.select(state.component("G").id)
        state.copySelected()
        state.paste()
        val recorder = editor(state)
        val count = state.document.components.size
        screenshot("c45-paste-preview-with-width-error")
        // A one-finger drag across the empty lower half of the canvas, as a pan attempt or a move would be.
        val from = Offset(120f, 520f)
        val to = Offset(220f, 620f)
        onNodeWithTag("canvas").performTouchInput {
            down(from)
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            up()
        }
        waitForIdle()
        val pendingAfterDrag = state.pendingPaste != null
        val confirmsAfterDrag = recorder.pasteConfirms
        val countAfterDrag = state.document.components.size
        val lastPreview = recorder.pastePreviews.lastOrNull()
        val expected = canvasPoint(state.viewport.view.world(to), snapToGrid = true)
        screenshot("c45-paste-preview-moved")
        val warning = onAllNodesWithTag("editor-width-error").fetchSemanticsNodes().firstOrNull()
        val buttons = onAllNodesWithTag("paste-confirm").fetchSemanticsNodes().firstOrNull()
        val overlap = if (warning != null && buttons != null) {
            val w = warning.boundsInRoot
            val b = buttons.boundsInRoot
            w.overlaps(b)
        } else null
        if (pendingAfterDrag) onNodeWithTag("paste-confirm").performClick()
        waitForIdle()
        observe("c45-paste-drag", "pendingAfterDrag" to pendingAfterDrag, "confirmsAfterDrag" to confirmsAfterDrag,
            "componentsAfterDrag" to countAfterDrag - count, "lastPreview" to lastPreview, "expectedPreview" to expected,
            "buttonOverlapsWarning" to overlap, "confirmsAfterButton" to recorder.pasteConfirms,
            "componentsAfterButton" to state.document.components.size - count)
        assertTrue(pendingAfterDrag, "a drag moves the preview without pasting")
        assertEquals(0, confirmsAfterDrag)
        assertEquals(count, countAfterDrag)
        assertEquals(expected, lastPreview)
        assertEquals(false, overlap, "the paste buttons never cover the width warning")
        assertEquals(1, recorder.pasteConfirms)
        assertEquals(count + 1, state.document.components.size)
    }

    private companion object {
        /** Compose's default ViewConfiguration.longPressTimeoutMillis. */
        const val LongPressTimeoutMillis = 500L

        const val buttonProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#I/O" name="5"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="5" loc="(200,200)" name="Button"/>
  </circuit>
</project>"""

        /** An 8-bit input wired to a 1-bit NOT gate: the canvas shows the width warning. */
        const val widthErrorProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="appearance" val="NewPins"/><a name="width" val="8"/></comp>
    <comp lib="1" loc="(200,100)" name="NOT Gate"><a name="label" val="G"/></comp>
    <wire from="(100,100)" to="(170,100)"/>
  </circuit>
</project>"""
    }
}
