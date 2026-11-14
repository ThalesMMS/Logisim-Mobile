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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.FlipFlopPoked
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoInteractions
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.ProjectMouseBinding
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.outputPorts
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.rememberLocalizer
import java.io.File
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Touch gestures on the shared canvas at phone size (390x844, density 1), driven through [CircuitCanvas] wired to
 * an [EditorState] as App wires it. Each scenario writes what it observed to `behavior-<scenario>.txt` next to the
 * screenshots (`-PuiScreenshots=<dir>`), before asserting, so a run on the original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasTouchGestureHostTest {
    private val fixture = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")

    private class Recorder {
        val textTaps = mutableListOf<TextToolTarget>()
        val events = mutableListOf<Pair<String, ComponentEvent>>()
        val pokes = mutableListOf<Pair<String, Int?>>()
    }

    private fun ComposeUiTest.editor(
        state: EditorState,
        mouseBindings: Map<ProjectMouseGesture, ProjectMouseBinding> = emptyMap(),
    ): Recorder {
        val recorder = Recorder()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
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
                            onSelectWire = state::selectWire, onMoveSelection = state::moveSelected,
                            keepConnectionsWhenMoving = state.keepConnectionsWhenMoving,
                            onCommand = state::handleCanvasCommand, onTogglePlacementMatrix = state::togglePlacementMatrix,
                            wirePokePrimaryRadix = WirePokeRadix.BINARY, wirePokeSecondaryRadix = null,
                            onViewSubcircuit = {}, showZoomControls = false,
                            onDrawEditToolWire = state::drawEditToolWire,
                            onTextToolTap = { target, _ -> recorder.textTaps += target },
                            onComponentEvent = { id, event -> recorder.events += id to event; state.sendComponentEvent(id, event) },
                            onPokePin = { id, bit -> recorder.pokes += id to bit; if (bit != null) state.pokePin(id, bit) },
                            onSequentialHexEdit = {},
                            showPlacementPreview = true, placementPrototype = state.placementPreview(),
                            onRemoveWire = { state.removeWire(it) }, onSplitWire = state::splitWireAt,
                            mouseBindings = mouseBindings, onMappedTap = state::tap,
                            onMappedPlacement = state::insertMappedComponent,
                            onPlacementReleasedOffCanvas = state::releasePlacementOffCanvas,
                        )
                        // One line, so a longer status never resizes (and refits) the canvas mid-scenario.
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

    /** World units covered by [dp] screen dp at the current zoom (the host renders at density 1). */
    private fun EditorState.units(dp: Float): Float = dp / viewport.view.scale

    private fun ComposeUiTest.menuShown() = onAllNodesWithTag("canvas-context-menu").fetchSemanticsNodes().isNotEmpty()

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    /** Touch down at [from], then to [to] and on to [then] in ten steps per leg, then up. */
    private fun ComposeUiTest.touchDrag(from: Offset, to: Offset, then: Offset? = null) {
        onNodeWithTag("canvas").performTouchInput {
            down(from)
            var at = from
            for (target in listOfNotNull(to, then)) {
                for (step in 1..10) moveTo(at + (target - at) * (step / 10f))
                at = target
            }
            up()
        }
        waitForIdle()
    }

    /** The window pixel at [screen] (the canvas is at the window's origin). */
    private fun ComposeUiTest.pixel(screen: Offset): Color =
        onAllNodes(isRoot())[0].captureToImage().toPixelMap()[screen.x.toInt(), screen.y.toInt()]

    private fun GridPoint.offset() = Offset(x.toFloat(), y.toFloat())

    @Test
    fun dragToPlaceMovesTheGhostAndPlacesOnceAtTheRelease() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.place(ComponentKind.AND)
        editor(state)
        val pan = state.viewport.view.pan
        val before = state.document.components.size
        val from = state.screen(150f, 200f)
        val to = state.screen(250f, 260f)
        val expected = canvasPoint(state.viewport.view.world(to), snapToGrid = true)
        onNodeWithTag("canvas").performTouchInput {
            down(from)
            for (step in 1..8) moveTo(from + (to - from) * (step / 10f))
        }
        waitForIdle()
        screenshot("place-drag-ghost-following-finger")
        onNodeWithTag("canvas").performTouchInput { moveTo(to); up() }
        waitForIdle()
        screenshot("place-drag-released")
        val added = state.document.components.drop(before)
        observe("drag-to-place", "placed" to added.size, "position" to added.firstOrNull()?.position,
            "expected" to expected, "panChanged" to (state.viewport.view.pan != pan))
        assertEquals(1, added.size)
        assertEquals(ComponentKind.AND, added.single().kind)
        assertEquals(expected, added.single().position)
        assertEquals(pan, state.viewport.view.pan)
    }

    @Test
    fun aMouseDragInThePlaceToolAlsoPlacesAtTheRelease() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.place(ComponentKind.AND)
        editor(state)
        val before = state.document.components.size
        val from = state.screen(150f, 200f)
        val to = state.screen(250f, 260f)
        val expected = canvasPoint(state.viewport.view.world(to), snapToGrid = true)
        onNodeWithTag("canvas").performMouseInput {
            moveTo(from); press()
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            release()
        }
        waitForIdle()
        val added = state.document.components.drop(before)
        observe("mouse-drag-to-place", "placed" to added.size, "position" to added.firstOrNull()?.position, "expected" to expected)
        assertEquals(listOf(expected), added.map { it.position })
    }

    @Test
    fun aPinchInThePlaceToolPlacesNothing() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.place(ComponentKind.AND)
        editor(state)
        val before = state.document.components.size
        val scale = state.viewport.view.scale
        val a = state.screen(150f, 200f)
        val b = state.screen(250f, 260f)
        onNodeWithTag("canvas").performTouchInput {
            down(0, a); down(1, b)
            for (step in 1..6) { moveTo(0, a - Offset(step * 8f, step * 8f)); moveTo(1, b + Offset(step * 8f, step * 8f)) }
            up(0); up(1)
        }
        waitForIdle()
        observe("pinch-in-place", "placed" to state.document.components.size - before,
            "zoomed" to (state.viewport.view.scale != scale))
        assertEquals(before, state.document.components.size)
        assertTrue(state.viewport.view.scale > scale)
    }

    @Test
    fun aSlowTapInThePlaceToolPlacesOnceWithoutAMenu() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        val pin = state.component("E2E_IN")
        state.select(pin.id)
        state.place(ComponentKind.AND)
        editor(state)
        val before = state.document.components.size
        onNodeWithTag("canvas").performTouchInput { longClick(state.screen(150f, 200f)) }
        waitForIdle()
        screenshot("place-slow-tap")
        val added = state.document.components.drop(before)
        observe("slow-tap-place", "placed" to added.size, "position" to added.firstOrNull()?.position,
            "menu" to menuShown(), "status" to state.statusText.key)
        assertEquals(listOf(GridPoint(150, 200)), added.map { it.position })
        assertTrue(!menuShown())
    }

    @Test
    fun aSlowTapInTheWireToolStartsTheWireOnTheGridAndOnAPin() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.WIRE)
        editor(state)
        onNodeWithTag("canvas").performTouchInput { longClick(state.screen(150f, 200f)) }
        waitForIdle()
        val onGrid = state.pendingWire
        val gridMenu = menuShown()
        state.changeMode(EditorMode.WIRE)
        waitForIdle()
        val out = state.component("E2E_OUT")
        val body = out.bounds(state.componentRegistry)
        onNodeWithTag("canvas").performTouchInput {
            longClick(state.screen((body.left + body.right) / 2f, (body.top + body.bottom) / 2f))
        }
        waitForIdle()
        screenshot("wire-slow-tap-on-pin")
        observe("slow-tap-wire", "gridStart" to onGrid, "gridMenu" to gridMenu, "pinStart" to state.pendingWire,
            "pinMenu" to menuShown())
        assertEquals(GridPoint(150, 200), onGrid)
        assertTrue(!gridMenu)
        assertEquals(GridPoint(300, 100), state.pendingWire)
        assertTrue(!menuShown())
    }

    @Test
    fun aSlowTapInTheTextToolOpensTheTextTool() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.TEXT)
        val recorder = editor(state)
        onNodeWithTag("canvas").performTouchInput { longClick(state.screen(150f, 200f)) }
        waitForIdle()
        observe("slow-tap-text", "textTaps" to recorder.textTaps, "menu" to menuShown())
        assertEquals(listOf<TextToolTarget>(TextToolTarget.Empty(GridPoint(150, 200))), recorder.textTaps)
    }

    @Test
    fun aLongPressInTheEditToolStillOpensTheComponentMenu() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        val gate = state.component("E2E_NOT").bounds(state.componentRegistry)
        onNodeWithTag("canvas").performTouchInput {
            longClick(state.screen((gate.left + gate.right) / 2f, (gate.top + gate.bottom) / 2f))
        }
        waitForIdle()
        screenshot("edit-long-press-menu")
        observe("long-press-edit", "menu" to menuShown())
        assertTrue(menuShown())
    }

    @Test
    fun aWireDragTenDpOffTwoPinsConnectsThePorts() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(wiringProject)
        state.changeMode(EditorMode.WIRE)
        editor(state)
        val miss = state.units(10f)
        val from = state.screen(100f + miss, 100f)
        val to = state.screen(300f - miss, 200f)
        onNodeWithTag("canvas").performTouchInput { down(from) }
        waitForIdle()
        screenshot("wire-snap-ring-pressed")
        onNodeWithTag("canvas").performTouchInput { for (step in 1..10) moveTo(from + (to - from) * (step / 10f)) }
        waitForIdle()
        screenshot("wire-snap-ring-dragging")
        onNodeWithTag("canvas").performTouchInput { up() }
        waitForIdle()
        screenshot("wire-drag-connected")
        val ends = state.document.wires.flatMap { listOf(it.from, it.to) }.toSet()
        observe("wire-drag-10dp-miss", "scale" to state.viewport.view.scale, "missUnits" to miss,
            "wires" to state.document.wires)
        assertTrue(GridPoint(100, 100) in ends, "wire starts at pin A's port: ${state.document.wires}")
        assertTrue(GridPoint(300, 200) in ends, "wire ends at pin B's port: ${state.document.wires}")
    }

    @Test
    fun anEditDragTenDpOffAnOutputDrawsAWireAndABodyDragMovesTheGate() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(wiringProject)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        val gate = state.document.components.single { it.kind == ComponentKind.AND }
        val output = assertNotNull(gate.outputPorts(state.componentRegistry).singleOrNull())
        val miss = state.units(10f)
        val from = state.screen(output.x + miss, output.y.toFloat())
        val to = state.screen(output.x + 60f, output.y.toFloat())
        onNodeWithTag("canvas").performTouchInput { down(from) }
        waitForIdle()
        screenshot("edit-snap-ring-pressed-beside-output")
        onNodeWithTag("canvas").performTouchInput {
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            up()
        }
        waitForIdle()
        screenshot("edit-drag-beside-output-released")
        val wired = state.document.wires.toList()
        val moved = state.document.components.single { it.kind == ComponentKind.AND }.position != gate.position
        state.undo()
        waitForIdle()

        val body = gate.bounds(state.componentRegistry)
        val center = state.screen((body.left + body.right) / 2f, (body.top + body.bottom) / 2f)
        onNodeWithTag("canvas").performTouchInput {
            down(center)
            for (step in 1..10) moveTo(center + Offset(0f, step * 6f))
            up()
        }
        waitForIdle()
        val bodyMoved = state.document.components.single { it.kind == ComponentKind.AND }.position
        observe("edit-drag-10dp-off-output", "wires" to wired, "gateMovedByPortDrag" to moved,
            "gateAfterBodyDrag" to bodyMoved, "gateBefore" to gate.position)
        assertEquals(listOf(WireSegment(output, GridPoint(output.x + 60, output.y))), wired)
        assertTrue(!moved)
        assertTrue(bodyMoved != gate.position)
    }

    @Test
    fun theMouseKeepsEvolutionsWiringRadius() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(wiringProject)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        val gate = state.document.components.single { it.kind == ComponentKind.AND }
        val output = gate.outputPorts(state.componentRegistry).single()
        fun drag(from: Offset) = onNodeWithTag("canvas").performMouseInput {
            moveTo(from); press()
            for (step in 1..10) moveTo(from + Offset(step * 6f, 0f))
            release()
        }
        // Ten units off the output is beyond the mouse radius: a rubber band, no wire and no move.
        drag(state.screen(output.x + 10f, output.y.toFloat()))
        waitForIdle()
        val farWires = state.document.wires.toList()
        val farGate = state.document.components.single { it.kind == ComponentKind.AND }.position
        // Four units off is within it: a wire from the port.
        drag(state.screen(output.x + 4f, output.y.toFloat()))
        waitForIdle()
        observe("mouse-edit-drag-off-output", "wiresFrom10Units" to farWires, "gateAfter10Units" to farGate,
            "wiresFrom4Units" to state.document.wires)
        assertEquals(emptyList(), farWires)
        assertEquals(gate.position, farGate)
        assertEquals(output, state.document.wires.single().from)
    }

    @Test
    fun aPokeTapOnTheWireBesideAnInputPokesTheWire() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.changeMode(EditorMode.INTERACT)
        val recorder = editor(state)
        val pin = state.component("E2E_IN")
        val initial = state.simulation.componentValues[pin.id]
        val edge = pin.bounds(state.componentRegistry).right
        onNodeWithTag("canvas").performTouchInput { click(state.screen(edge + 12f, 100f)) }
        waitForIdle()
        screenshot("poke-wire-beside-input")
        observe("poke-wire-beside-input", "pinPokes" to recorder.pokes, "wirePoke" to state.wirePoke,
            "inputBefore" to initial, "inputAfter" to state.simulation.componentValues[pin.id])
        assertEquals(emptyList(), recorder.pokes)
        assertEquals(WireSegment(GridPoint(100, 100), GridPoint(170, 100)), state.wirePoke?.wire)
        assertEquals(initial, state.simulation.componentValues[pin.id])
    }

    @Test
    fun aTapTwelveUnitsFromAFlipFlopCentreTogglesIt() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(flipFlopProject)
        state.changeMode(EditorMode.INTERACT)
        val recorder = editor(state)
        val flop = state.document.components.single { it.kind == ComponentKind.D_FLIP_FLOP }
        val bounds = flop.layout(state.componentRegistry).bounds
        val center = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        onNodeWithTag("canvas").performMouseInput { click(state.screen(center.x + 12f, center.y)) }
        waitForIdle()
        val mouseEvents = recorder.events.toList()
        onNodeWithTag("canvas").performTouchInput { click(state.screen(center.x + 12f, center.y)) }
        waitForIdle()
        screenshot("flip-flop-touch-poke")
        observe("flip-flop-touch-poke", "mouseEvents" to mouseEvents, "touchEvents" to recorder.events.drop(mouseEvents.size))
        assertEquals(emptyList(), mouseEvents)
        assertEquals(listOf<Pair<String, ComponentEvent>>(flop.id to FlipFlopPoked), recorder.events)
        assertNull(state.wirePoke)
    }

    @Test
    fun aShortStubFromAPortEndsOnItsOwnGridPoint() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(stubProject)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        // At this fit zoom the finger's port radius spans more than the 10-unit pitch.
        assertTrue(state.units(CanvasTouchWireToleranceDp) > 10f)
        val output = state.document.components.single { it.kind == ComponentKind.AND }.outputPorts(state.componentRegistry).single()
        val stub = WireSegment(output, GridPoint(output.x + 10, output.y))
        // The finger passes the touch slop, then comes back to the grid point next to the port.
        fun stubDrag() = touchDrag(state.screen(output.x.toFloat(), output.y.toFloat()),
            state.screen(output.x + 40f, output.y.toFloat()), state.screen(output.x + 10f, output.y.toFloat()))
        stubDrag()
        val editWires = state.document.wires.toSet() - stubWires
        if (editWires.isNotEmpty()) state.undo()
        state.changeMode(EditorMode.WIRE)
        waitForIdle()
        stubDrag()
        screenshot("wire-short-stub-from-port")
        val wireWires = state.document.wires.toSet() - stubWires
        observe("short-stub-from-port", "scale" to state.viewport.view.scale,
            "portRadiusUnits" to state.units(CanvasTouchWireToleranceDp), "editTool" to editWires, "wireTool" to wireWires)
        assertEquals(setOf(stub), editWires)
        assertEquals(setOf(stub), wireWires)
    }

    @Test
    fun aStubFromAMiddleInputOfAFiveInputAndAddsOnlyTheStub() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fiveInputProject)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        val gate = state.document.components.single { it.kind == ComponentKind.AND }
        val inputs = gate.inputPorts(state.componentRegistry).sortedBy { it.y }
        assertEquals(5, inputs.size)
        val middle = inputs[2]
        val ports = (gate.inputPorts(state.componentRegistry) + gate.outputPorts(state.componentRegistry)).toSet()
        val end = GridPoint(middle.x - 10, middle.y)
        // At this fit zoom the neighbours (14.1 units from the stub's end) are within the finger's port radius.
        val neighbour = (Offset(end.x.toFloat(), end.y.toFloat()) - inputs[1].offset()).getDistance()
        assertTrue(state.units(CanvasTouchWireToleranceDp) > neighbour, "reach ${state.units(CanvasTouchWireToleranceDp)}")
        val initial = state.document.wires.toSet()
        // The finger passes the touch slop, then comes back to the grid point left of the input.
        fun stubDrag(): Set<Set<GridPoint>> {
            touchDrag(state.screen(middle.x.toFloat(), middle.y.toFloat()),
                state.screen(middle.x - 40f, middle.y.toFloat()), state.screen(end.x.toFloat(), end.y.toFloat()))
            return (state.document.wires.toSet() - initial).map { setOf(it.from, it.to) }.toSet()
        }
        val editWires = stubDrag()
        if (editWires.isNotEmpty()) state.undo()
        state.changeMode(EditorMode.WIRE)
        waitForIdle()
        val wireWires = stubDrag()
        screenshot("wire-stub-from-five-input-and")
        observe("stub-from-five-input-and", "scale" to state.viewport.view.scale,
            "portRadiusUnits" to state.units(CanvasTouchWireToleranceDp), "neighbourUnits" to neighbour,
            "editTool" to editWires, "wireTool" to wireWires,
            "portToPort" to (editWires + wireWires).filter { it.all { point -> point in ports } })
        assertEquals(setOf(setOf(middle, end)), editWires)
        assertEquals(setOf(setOf(middle, end)), wireWires)
        assertTrue((editWires + wireWires).none { it.all { point -> point in ports } })
    }

    @Test
    fun aParallelWireOnePitchAwayNeitherRetracesNorJoinsTheWireBesideIt() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(stubProject)
        state.changeMode(EditorMode.WIRE)
        editor(state)
        assertTrue(state.units(CanvasTouchWireToleranceDp) > 10f)
        // Both ends one pitch from the short wire's ends: resolving onto them would retrace and delete it.
        touchDrag(state.screen(100f, 110f), state.screen(200f, 110f))
        val besideShort = state.document.wires.toSet()
        // One end one pitch from the long wire's end: resolving onto it would join the two nets.
        touchDrag(state.screen(100f, 190f), state.screen(200f, 190f))
        screenshot("wire-parallel-one-pitch")
        observe("parallel-wire-one-pitch", "scale" to state.viewport.view.scale, "afterShort" to besideShort,
            "afterLong" to state.document.wires)
        val first = WireSegment(GridPoint(100, 110), GridPoint(200, 110))
        val second = WireSegment(GridPoint(100, 190), GridPoint(200, 190))
        assertEquals(stubWires + first, besideShort)
        assertEquals(stubWires + first + second, state.document.wires.toSet())
    }

    @Test
    fun aFingerBelowAPinButNotPastItsPortStillDragsThePin() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(wiringProject)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        val pin = state.document.components.first { it.kind == ComponentKind.INPUT }
        val body = pin.bounds(state.componentRegistry)
        val port = (pin.inputPorts(state.componentRegistry) + pin.outputPorts(state.componentRegistry)).single()
        assertEquals(body.right, port.x)
        // Just below the body's right half: outside it, within the finger's port radius, beside the bottom edge.
        val press = Offset(port.x - 3f, body.bottom + 2f)
        val distance = (press - port.offset()).getDistance()
        assertTrue(distance > 8f && distance < state.units(CanvasTouchWireToleranceDp), "distance $distance")
        val from = state.screen(press.x, press.y)
        touchDrag(from, from + Offset(0f, 60f))
        val after = state.document.components.first { it.id == pin.id }.position
        observe("edit-drag-below-pin", "wires" to state.document.wires, "pinBefore" to pin.position, "pinAfter" to after)
        assertEquals(emptyList(), state.document.wires)
        assertTrue(after != pin.position)
    }

    @Test
    fun aPlaceDragReleasedOffTheCanvasPlacesNothingButAppliesTheAfterPlacementTool() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.place(ComponentKind.AND)
        state.select(state.document.components.first().id)
        editor(state)
        val selectedBefore = state.selectedIds
        val before = state.document.components.size
        val canvas = onNodeWithTag("canvas").fetchSemanticsNode().size
        val from = state.screen(150f, 200f)
        // Over the status bar below the canvas, as a finger sliding onto the toolbar.
        val off = Offset(from.x, canvas.height + 12f)
        onNodeWithTag("canvas").performTouchInput { down(from); for (step in 1..10) moveTo(from + (off - from) * (step / 10f)) }
        waitForIdle()
        screenshot("place-drag-off-canvas")
        onNodeWithTag("canvas").performTouchInput { up() }
        waitForIdle()
        screenshot("place-drag-off-canvas-released")
        val offCanvas = state.document.components.drop(before).map { it.position }
        // AddTool.determineNext: with "Edit tool after adding" (the default) the release switches to the Edit tool
        // and drops the selection, although nothing was added.
        val modeAfterEdit = state.mode
        val selectedAfterEdit = state.selectedIds
        // With "Keep the tool", the Place tool stays.
        state.afterPlacement = EditorAfterPlacement.KEEP_TOOL
        state.place(ComponentKind.AND)
        waitForIdle()
        touchDrag(from, off)
        val modeAfterKeep = state.mode
        val offCanvasKept = state.document.components.drop(before).map { it.position }
        // Off and back on before the release, it places where it was released (AddTool.mouseEntered).
        val back = state.screen(250f, 260f)
        val expected = canvasPoint(state.viewport.view.world(back), snapToGrid = true)
        touchDrag(from, off, back)
        val added = state.document.components.drop(before).map { it.position }
        observe("place-drag-off-canvas", "canvasHeight" to canvas.height, "placedOffCanvas" to offCanvas,
            "selectedBefore" to selectedBefore, "modeAfterEditPreference" to modeAfterEdit,
            "selectedAfterEditPreference" to selectedAfterEdit, "placedOffCanvasKeepTool" to offCanvasKept,
            "modeAfterKeepTool" to modeAfterKeep, "placedAfterReturning" to added, "expected" to expected)
        assertEquals(emptyList(), offCanvas)
        assertTrue(selectedBefore.isNotEmpty())
        assertEquals(EditorMode.EDIT, modeAfterEdit)
        assertEquals(emptySet(), selectedAfterEdit)
        assertEquals(emptyList(), offCanvasKept)
        assertEquals(EditorMode.PLACE, modeAfterKeep)
        assertEquals(listOf(expected), added)
    }

    @Test
    fun aSlowTapInsideALargeBodyInTheWireToolOpensItsMenu() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(wiringProject)
        state.changeMode(EditorMode.WIRE)
        editor(state)
        val gate = state.document.components.single { it.kind == ComponentKind.AND }
        val body = gate.bounds(state.componentRegistry)
        val center = Offset((body.left + body.right) / 2f, (body.top + body.bottom) / 2f)
        val ports = gate.inputPorts(state.componentRegistry) + gate.outputPorts(state.componentRegistry)
        // Every port is beyond the finger's reach, so a quick tap there would start a wire inside the body.
        assertTrue(ports.all { (it.offset() - center).getDistance() > state.units(CanvasTouchHitToleranceDp) })
        onNodeWithTag("canvas").performTouchInput { longClick(state.screen(center.x, center.y)) }
        waitForIdle()
        screenshot("wire-slow-tap-in-body-menu")
        observe("slow-tap-wire-body", "menu" to menuShown(), "pendingWire" to state.pendingWire,
            "selected" to state.selectedId)
        assertTrue(menuShown())
        assertTrue(onAllNodesWithTag("canvas-menu-delete").fetchSemanticsNodes().isNotEmpty())
        assertNull(state.pendingWire)
        assertEquals(gate.id, state.selectedId)
    }

    @Test
    fun aMappedPlaceDragPlacesTheMappedComponentAtTheReleaseAndKeepsTheTool() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(mappedProject)
        state.changeMode(EditorMode.EDIT)
        editor(state, ProjectMouseMappings.read(state.project, state.componentRegistry))
        val pan = state.viewport.view.pan
        val before = state.document.components.size
        val from = state.screen(150f, 200f)
        val to = state.screen(250f, 240f)
        val expected = canvasPoint(state.viewport.view.world(to), snapToGrid = true)
        onNodeWithTag("canvas").performMouseInput {
            moveTo(from); press(MouseButton.Tertiary)
            for (step in 1..10) moveTo(from + (to - from) * (step / 10f))
            release(MouseButton.Tertiary)
        }
        waitForIdle()
        val added = state.document.components.drop(before)
        val mode = state.mode
        val selected = state.selectedIds
        // Released off the canvas from the Wire tool, the mapped (middle button) placement adds nothing. AddTool's
        // determineNext still drops the selection, and Canvas restores the Wire tool (tempTool).
        state.changeMode(EditorMode.WIRE)
        state.select(added.single().id)
        waitForIdle()
        val canvas = onNodeWithTag("canvas").fetchSemanticsNode().size
        val off = Offset(from.x, canvas.height + 12f)
        onNodeWithTag("canvas").performMouseInput {
            moveTo(from); press(MouseButton.Tertiary)
            for (step in 1..10) moveTo(from + (off - from) * (step / 10f))
            release(MouseButton.Tertiary)
        }
        waitForIdle()
        val afterOff = state.document.components.drop(before)
        observe("mapped-place-drag", "placed" to added.map { it.kind to it.position }, "expected" to expected,
            "width" to added.firstOrNull()?.width, "mode" to mode, "panChanged" to (state.viewport.view.pan != pan),
            "selected" to selected, "placedAfterOffCanvas" to afterOff.size, "modeAfterOffCanvas" to state.mode,
            "selectedAfterOffCanvas" to state.selectedIds)
        assertEquals(listOf(ComponentKind.INPUT to expected), added.map { it.kind to it.position })
        assertEquals(8, added.single().width)
        assertEquals(EditorMode.EDIT, mode)
        assertEquals(pan, state.viewport.view.pan)
        assertEquals(setOf(added.single().id), selected)
        assertEquals(added, afterOff)
        assertEquals(EditorMode.WIRE, state.mode)
        assertEquals(emptySet(), state.selectedIds)
    }

    @Test
    fun eachDragToPlaceReleaseTakesOneAutoLabelOrOneMatrixOrigin() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        state.afterPlacement = EditorAfterPlacement.KEEP_TOOL
        state.place(ComponentKind.AND)
        assertTrue(state.setPlacementAutoLabel("G1"))
        editor(state)
        val before = state.document.components.size
        touchDrag(state.screen(150f, 200f), state.screen(250f, 260f))
        val labels = state.document.components.drop(before).map { it.label }
        val nextLabel = state.placementAutoLabel
        state.togglePlacementMatrix()
        waitForIdle()
        val to = state.screen(250f, 330f)
        touchDrag(state.screen(150f, 200f), to)
        val origin = state.pendingMatrixPlacement?.origin
        observe("drag-to-place-label-matrix", "labels" to labels, "nextLabel" to nextLabel, "matrixOrigin" to origin,
            "components" to state.document.components.size - before)
        assertEquals(listOf("G1"), labels)
        assertEquals("G2", nextLabel)
        assertEquals(canvasPoint(state.viewport.view.world(to), snapToGrid = true), origin)
        assertEquals(before + 1, state.document.components.size)
    }

    @Test
    fun theSnapRingShowsWhilePressedAndClearsOnAPinchAndOnRelease() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(wiringProject)
        state.changeMode(EditorMode.WIRE)
        editor(state)
        val from = state.screen(100f + state.units(10f), 100f)
        // On the ring around pin A's port, clear of the pin and of the wire that will run to pin B.
        val probe = state.screen(100f + 9f * 0.7071f, 100f + 9f * 0.7071f)
        fun ring(): Boolean = pixel(probe).let { it.blue - it.red > 0.25f }
        val idle = ring()
        onNodeWithTag("canvas").performTouchInput { down(0, from) }
        waitForIdle()
        val pressed = ring()
        onNodeWithTag("canvas").performTouchInput { down(1, from + Offset(120f, 120f)) }
        waitForIdle()
        val pinched = ring()
        onNodeWithTag("canvas").performTouchInput { up(1); up(0) }
        waitForIdle()
        val afterPinch = state.document.wires.toList()
        onNodeWithTag("canvas").performTouchInput { down(from) }
        waitForIdle()
        val pressedAgain = ring()
        val to = state.screen(300f, 200f)
        onNodeWithTag("canvas").performTouchInput { for (step in 1..10) moveTo(from + (to - from) * (step / 10f)); up() }
        waitForIdle()
        val released = ring()
        observe("snap-ring", "idle" to idle, "pressed" to pressed, "pinched" to pinched, "wiresAfterPinch" to afterPinch,
            "pressedAgain" to pressedAgain, "released" to released, "wires" to state.document.wires)
        assertTrue(!idle && pressed && !pinched && pressedAgain && !released)
        assertEquals(emptyList(), afterPinch)
        assertTrue(state.document.wires.isNotEmpty())
    }

    @Test
    fun aTouchTapOnADipSwitchMarginOrAKeypadGapReachesTheNearestSwitchOrKey() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(ioProject)
        state.place(IoKinds.DipSwitch)
        state.tap(GridPoint(100, 100))
        state.place(IoKinds.MatrixKeypad)
        state.tap(GridPoint(100, 300))
        state.changeMode(EditorMode.INTERACT)
        val recorder = editor(state)
        val reach = ceil(state.units(CanvasTouchHitToleranceDp)).toInt()
        // A point inside the component that only the finger's reach turns into a switch or a key.
        fun touchOnly(component: CircuitComponent): GridPoint {
            val b = component.bounds(state.componentRegistry)
            return (b.left + 1 until b.right).asSequence()
                .flatMap { x -> (b.top + 1 until b.bottom).asSequence().map { GridPoint(x, it) } }
                .first { IoInteractions.press(component, it) == null && IoInteractions.press(component, it, reach) != null }
        }
        val observed = mutableListOf<Pair<String, Any?>>()
        val checks = mutableListOf<() -> Unit>()
        for (kind in listOf(IoKinds.DipSwitch, IoKinds.MatrixKeypad)) {
            val component = state.document.components.single { it.kind == kind }
            val point = touchOnly(component)
            val action = assertNotNull(IoInteractions.press(component, point, reach))
            val screen = state.screen(point.x.toFloat(), point.y.toFloat())
            val start = recorder.events.size
            onNodeWithTag("canvas").performMouseInput { click(screen) }
            waitForIdle()
            val mouse = recorder.events.drop(start).map { it.second }
            onNodeWithTag("canvas").performTouchInput { click(screen) }
            waitForIdle()
            val touch = recorder.events.drop(start + mouse.size).map { it.second }
            observed += "$kind" to listOf(point, mouse, touch)
            checks += {
                assertEquals(emptyList(), mouse, "$kind mouse")
                assertEquals(listOfNotNull<ComponentEvent>(action.press, action.release), touch, "$kind touch")
            }
        }
        observe("io-touch-reach", "reach" to reach, *observed.toTypedArray())
        checks.forEach { it() }
    }

    @Test
    fun aLongPressOnAShortWireBetweenGatesDeletesOrSplitsTheWire() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(shortWireProject)
        state.changeMode(EditorMode.EDIT)
        editor(state)
        val wire = state.document.wires.single()
        onNodeWithTag("canvas").performTouchInput { longClick(state.screen(210f, 300f)) }
        waitForIdle()
        screenshot("edit-long-press-short-wire-menu")
        val selected = state.selectedWires
        val menu = menuShown()
        val items = onAllNodesWithTag("canvas-menu-delete").fetchSemanticsNodes().size
        observe("short-wire-menu", "menu" to menu, "selected" to selected, "selectedComponent" to state.selectedId,
            "deleteItems" to items)
        assertTrue(menu)
        onNodeWithTag("canvas-menu-delete").performClick()
        waitForIdle()
        val deleted = state.document.wires.toList()
        state.undo()
        waitForIdle()
        onNodeWithTag("canvas").performTouchInput { longClick(state.screen(210f, 300f)) }
        waitForIdle()
        onNodeWithText("Dividir fio aqui").performClick()
        waitForIdle()
        val split = state.document.wires.map { setOf(it.from, it.to) }.toSet()
        observe("short-wire-menu", "menu" to menu, "selected" to selected, "afterDelete" to deleted, "afterSplit" to split,
            "components" to state.document.components.size)
        assertEquals(setOf(wire), selected)
        assertEquals(emptyList(), deleted)
        assertEquals(setOf(setOf(GridPoint(200, 300), GridPoint(210, 300)), setOf(GridPoint(210, 300), GridPoint(220, 300))), split)
        assertEquals(2, state.document.components.size)
    }

    companion object {
        private const val wiringProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="appearance" val="NewPins"/></comp>
    <comp lib="0" loc="(300,200)" name="Pin">
      <a name="facing" val="west"/><a name="type" val="output"/><a name="appearance" val="NewPins"/>
    </comp>
    <comp lib="1" loc="(200,300)" name="AND Gate"/>
  </circuit>
</project>"""

        /** A short and a long wire, and an AND gate whose output has room for a stub. */
        private const val stubProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <wire from="(100,100)" to="(200,100)"/>
    <wire from="(100,180)" to="(380,180)"/>
    <comp lib="1" loc="(200,300)" name="AND Gate"/>
  </circuit>
</project>"""

        /** A five-input AND (inputs one pitch apart) at the stub project's fit zoom. */
        private const val fiveInputProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <wire from="(100,100)" to="(200,100)"/>
    <wire from="(100,180)" to="(380,180)"/>
    <comp lib="1" loc="(200,300)" name="AND Gate"><a name="inputs" val="5"/></comp>
  </circuit>
</project>"""

        private val stubWires = setOf(WireSegment(GridPoint(100, 100), GridPoint(200, 100)),
            WireSegment(GridPoint(100, 180), GridPoint(380, 180)))

        private const val mappedProject = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="6" desc="#Base"/>
              <main name="main"/>
              <mappings><tool lib="0" map="Button2" name="Pin"><a name="width" val="8"/></tool></mappings>
              <circuit name="main"><comp lib="0" loc="(100,100)" name="Pin"/><comp lib="0" loc="(300,300)" name="Pin"/></circuit>
            </project>"""

        private const val ioProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main"/>
</project>"""

        /** A 20-unit wire from an AND output to a NOT input. */
        private const val shortWireProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(200,300)" name="AND Gate"/>
    <comp lib="1" loc="(250,300)" name="NOT Gate"/>
    <wire from="(200,300)" to="(220,300)"/>
  </circuit>
</project>"""

        private const val flipFlopProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Memory" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(200,200)" name="D Flip-Flop"/>
    <comp lib="0" loc="(100,100)" name="Pin"/>
    <comp lib="0" loc="(300,300)" name="Pin"/>
  </circuit>
</project>"""
    }
}
