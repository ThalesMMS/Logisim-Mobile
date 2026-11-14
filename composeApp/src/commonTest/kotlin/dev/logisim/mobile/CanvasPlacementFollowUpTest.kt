package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.AppearanceEdits
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentMenuItem
import dev.logisim.mobile.core.CreationDefaults
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.createNew
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Model checks of the wave-2 canvas and placement package: tool attributes before placing (C34), the Text tool's
 * label check (C41), edge auto-pan (C43), the paste anchor and start (C45), multi-bit input actions (X0), Insert's
 * position suggestions (X1), live matrix label checks (X9) and the documented end-of-drag reach (R06-1).
 */
class CanvasPlacementFollowUpTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin">
      <a name="appearance" val="NewPins"/><a name="label" val="A"/>
    </comp>
  </circuit>
</project>"""

    // C34

    @Test
    fun aFacingChosenBeforePlacingSavesExactlyLikeAFacingEditAfterPlacing() {
        val before = EditorState(xml)
        before.place(ComponentKind.NOT)
        assertTrue(before.updatePlacingTemplate(mapOf("facing" to "south")))
        assertEquals("south", before.placementPreview()?.facing, "the ghost shows the chosen facing")
        before.tap(GridPoint(300, 300))

        val after = EditorState(xml)
        after.place(ComponentKind.NOT)
        after.tap(GridPoint(300, 300))
        after.updateSelectedAttribute("facing", "south")

        assertEquals("south", before.document.components.last().attributes["facing"])
        assertEquals(after.saveXml(), before.saveXml())
        // Choosing the tool before placing is not an edit: one undo step, the placement.
        assertEquals(1, before.undoHistory.size)
    }

    @Test
    fun aWidthChosenBeforePlacingAppliesToEveryPinAndStaysWithTheTool() {
        val state = EditorState(xml)
        state.place(ComponentKind.INPUT)
        assertTrue(state.updatePlacingTemplate(mapOf("width" to "8")))
        state.tap(GridPoint(200, 200))
        state.place(ComponentKind.INPUT)
        assertEquals("8", state.placementPreview()?.attributes?.get("width"), "re-choosing the tool keeps its width")
        state.tap(GridPoint(200, 300))
        assertEquals(listOf("8", "8"), state.document.components.drop(1).map { it.attributes["width"] })

        // A width the pin refuses is reported and leaves the tool as it was.
        state.place(ComponentKind.INPUT)
        assertFalse(state.updatePlacingTemplate(mapOf("width" to "65")))
        assertEquals("8", state.placementPreview()?.attributes?.get("width"))

        // Another kind starts from its own defaults; a new project forgets the choices.
        state.place(ComponentKind.OUTPUT)
        assertEquals("1", state.placementPreview()?.attributes?.get("width") ?: "1")
        assertTrue(state.importXml(xml))
        state.place(ComponentKind.INPUT)
        assertNull(state.placingTemplate)
    }

    @Test
    fun rotateAndArrowKeysTurnTheLibraryTool() {
        val state = EditorState(xml)
        state.place(ComponentKind.AND)
        assertTrue(state.placementHasFacing)
        assertTrue(state.rotatePlacingTemplate())
        assertEquals("south", state.placementPreview()?.facing)
        assertTrue(state.handleToolFacing(dev.logisim.mobile.core.Direction.WEST))
        assertEquals("west", state.placementPreview()?.facing)
        state.place(ComponentKind.CONSTANT)
        state.place(ComponentKind.AND)
        assertEquals("west", state.placementPreview()?.facing)
    }

    // C41

    @Test
    fun theTextToolRefusesLabelsEvolutionRefusesAndKeepsTheDocument() {
        val state = EditorState(xml)
        val pin = state.document.components.single()
        val edit = assertNotNull(state.beginTextToolEdit(TextToolTarget.Existing(pin.id), null))
        val before = state.project
        for (label in listOf("Carry In", "out", "2nd")) {
            val problem = assertNotNull(state.textToolProblem(edit, label), label)
            assertTrue(problem.key.startsWith("evolution_"), "$label: ${problem.key}")
            assertEquals(problem, state.applyTextToolEdit(edit, label))
            assertEquals(before, state.project)
        }
        assertNull(state.textToolProblem(edit, "A"), "an unchanged label always closes")
        assertNull(state.applyTextToolEdit(edit, "Cin"))
        assertEquals("Cin", state.document.components.single().label)
    }

    @Test
    fun originalLogisimProjectsAndTextAnnotationsSkipTheLabelCheck() {
        val original = EditorState(xml.replace("source=\"5.1.0-dev\"", "source=\"2.7.1\""))
        val pin = original.document.components.single()
        val edit = assertNotNull(original.beginTextToolEdit(TextToolTarget.Existing(pin.id), null))
        assertNull(original.textToolProblem(edit, "Carry In"))
    }

    // C43

    @Test
    fun autoPanFollowsEvolutionsMarginAndSteps() {
        // Canvas.autoPanDelta: 32 px margin, 4 to 16 px per tick, deeper is faster.
        assertEquals(-16f, autoPanDelta(0f, 390f, 32f, 4f, 16f))
        assertEquals(-(4f + 12f * 1f / 32f), autoPanDelta(31f, 390f, 32f, 4f, 16f))
        assertEquals(0f, autoPanDelta(32f, 390f, 32f, 4f, 16f))
        assertEquals(0f, autoPanDelta(200f, 390f, 32f, 4f, 16f))
        assertEquals(4f + 12f * 1f / 32f, autoPanDelta(358f, 390f, 32f, 4f, 16f))
        assertEquals(16f, autoPanDelta(389f, 390f, 32f, 4f, 16f))
        assertEquals(16f, autoPanDelta(500f, 390f, 32f, 4f, 16f), "past the edge is the fastest step")
        assertEquals(0f, autoPanDelta(10f, 50f, 32f, 4f, 16f), "a view smaller than both margins never scrolls")
        // Near the right edge the circuit moves left: the pan decreases.
        val pan = canvasAutoPanDelta(Offset(386f, 400f), 390f, 800f, 1f)
        assertTrue(pan.x < 0f && pan.y == 0f, "$pan")
        assertEquals(Offset(32f, 32f), canvasAutoPanDelta(Offset(0f, 0f), 390f, 800f, 2f))
    }

    // C45

    @Test
    fun theDraggedPasteFollowsTheBlockCentreOnTheGrid() {
        assertEquals(GridPoint(150, 100), pasteAnchor(ComponentBounds(80, 90, 221, 110)))
        assertEquals(GridPoint(-10, 0), pasteAnchor(ComponentBounds(-20, -4, 0, 4)))
        val state = EditorState(xml)
        state.selectAll()
        state.copySelected()
        state.paste()
        val pending = assertNotNull(state.pendingPaste)
        assertEquals(GridPoint(20, 20), pending.offset, "a canvas that never laid out keeps the 20-unit offset")
        val bounds = state.document.components.single().bounds(state.componentRegistry)
        assertEquals(pasteAnchor(bounds), pending.anchor)
        state.movePastePreview(GridPoint(300, 400))
        assertEquals(GridPoint(300 - pending.anchor.x, 400 - pending.anchor.y), state.pendingPaste?.offset)
        assertEquals(0, state.pendingPaste!!.offset.x % 10)
    }

    @Test
    fun aPasteWhoseOriginalIsOffScreenStartsCentredInTheView() {
        val state = EditorState(xml)
        // The view shows world x 1000..1400, y 1000..1800: the pin at (100, 100) is far off screen.
        state.updateViewport(CanvasViewportState(CircuitViewport(1f, Offset(-1000f, -1000f)), IntSize(400, 800), initialized = true))
        state.selectAll()
        state.copySelected()
        state.paste()
        val pending = assertNotNull(state.pendingPaste)
        assertEquals(GridPoint(1200, 1400), GridPoint(pending.anchor.x + pending.offset.x, pending.anchor.y + pending.offset.y))
        // With the original in view, the copy starts 20 units from it, as before.
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        state.updateViewport(CanvasViewportState(CircuitViewport(1f, Offset.Zero), IntSize(400, 800), initialized = true))
        state.paste()
        assertEquals(GridPoint(20, 20), state.pendingPaste?.offset)
    }

    // X0

    @Test
    fun onlyMultiBitInputsOfferValueEntryAndToggleBitTogglesThatBit() {
        val wide = xml.replace("<a name=\"label\" val=\"A\"/>", "<a name=\"label\" val=\"A\"/><a name=\"width\" val=\"8\"/><a name=\"radix\" val=\"16\"/>")
        val state = EditorState(wide)
        val pin = state.document.components.single()
        val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry)
        assertTrue(graph.component(pin.id)!!.offersValueEntry)
        assertTrue(graph.component(pin.id)!!.isInteractiveInput)
        val narrow = EditorState(xml)
        val bit = narrow.document.components.single()
        assertFalse(CircuitAccessibilityGraph(narrow.document, narrow.componentRegistry).component(bit.id)!!.offersValueEntry)

        assertTrue(state.toggleInputBit(pin.id, 3), "a hexadecimal pin still toggles one bit")
        assertEquals(0x08uL, state.simulation.vectorValues[pin.id]?.toULongOrNull())
        assertTrue(state.toggleInputBit(pin.id, 7))
        assertEquals(0x88uL, state.simulation.vectorValues[pin.id]?.toULongOrNull())
        assertFalse(state.toggleInputBit(pin.id, 8))
        assertFalse(state.canUndo, "poking drives the simulation only")
    }

    // X1

    @Test
    fun insertSuggestionsAreOnTheGridAndNeverCoverAComponent() {
        val registry = ComponentCatalog.registry
        val state = EditorState(xml)
        val document = state.document
        val existing = document.components.single()
        val existingBounds = existing.bounds(registry)
        fun overlaps(a: ComponentBounds, b: ComponentBounds) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        fun boundsAt(kind: ComponentKind, point: GridPoint) = registry.create(kind, "x", point).bounds(registry)

        val free = assertNotNull(accessibleInsertSuggestion(document, registry, ComponentKind.NOT, AccessibleInsertPosition.NEXT_FREE))
        assertEquals(0, free.x % 10)
        assertEquals(0, free.y % 10)
        assertFalse(overlaps(boundsAt(ComponentKind.NOT, free), existingBounds))

        val right = assertNotNull(accessibleInsertSuggestion(document, registry, ComponentKind.NOT,
            AccessibleInsertPosition.RIGHT_OF_SELECTED, existing))
        val rightBounds = boundsAt(ComponentKind.NOT, right)
        assertTrue(rightBounds.left >= existingBounds.right + 20 && rightBounds.left < existingBounds.right + 30, "$rightBounds")
        assertEquals(existing.position.y, right.y)

        val below = assertNotNull(accessibleInsertSuggestion(document, registry, ComponentKind.INPUT,
            AccessibleInsertPosition.BELOW_SELECTED, existing))
        assertTrue(boundsAt(ComponentKind.INPUT, below).top >= existingBounds.bottom + 20)
        assertNull(accessibleInsertSuggestion(document, registry, ComponentKind.NOT, AccessibleInsertPosition.RIGHT_OF_SELECTED, null))
        assertNull(accessibleInsertSuggestion(document, registry, ComponentKind.NOT, AccessibleInsertPosition.CUSTOM, existing))

        // Inserting twice at the next free spot never stacks.
        assertTrue(state.insertComponent(ComponentKind.NOT, free))
        val second = assertNotNull(accessibleInsertSuggestion(state.document, registry, ComponentKind.INPUT,
            AccessibleInsertPosition.NEXT_FREE, origin = free))
        assertTrue(state.document.components.none { overlaps(it.bounds(registry), boundsAt(ComponentKind.INPUT, second)) })
    }

    @Test
    fun insertSuggestionsNeverPutATerminalOnAWire() {
        // editing-not.circ: input pin E2E_IN at (100,100) wired to the NOT at (200,100) along y = 100.
        val wired = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="appearance" val="NewPins"/><a name="label" val="E2E_IN"/></comp>
    <comp lib="1" loc="(200,100)" name="NOT Gate"><a name="size" val="30"/><a name="label" val="E2E_NOT"/></comp>
    <comp lib="0" loc="(300,100)" name="Pin">
      <a name="facing" val="west"/><a name="type" val="output"/><a name="appearance" val="NewPins"/><a name="label" val="E2E_OUT"/>
    </comp>
    <wire from="(100,100)" to="(170,100)"/>
    <wire from="(200,100)" to="(300,100)"/>
  </circuit>
</project>"""
        val state = EditorState(wired)
        val registry = state.componentRegistry
        val document = state.document
        val pin = document.components.first { it.label == "E2E_IN" }
        val ports = document.components.flatMap { component -> component.layout(registry).ports.map { it.position } }.toSet()
        fun touches(kind: ComponentKind, point: GridPoint): List<GridPoint> {
            val placed = registry.createNew(kind, "x", point, CreationDefaults())
            val bounds = placed.bounds(registry)
            val crossed = document.wires.filter { wire ->
                minOf(wire.from.x, wire.to.x) <= bounds.right && maxOf(wire.from.x, wire.to.x) >= bounds.left &&
                    minOf(wire.from.y, wire.to.y) <= bounds.bottom && maxOf(wire.from.y, wire.to.y) >= bounds.top
            }.map { it.from }
            return placed.layout(registry).ports.map { it.position }
                .filter { port -> port in ports || document.wires.any { it.contains(port) } } + crossed
        }
        // The reviewer's case: right of the selected E2E_IN used to be (150,100), both NOT terminals on its wire.
        val right = assertNotNull(accessibleInsertSuggestion(document, registry, ComponentKind.NOT,
            AccessibleInsertPosition.RIGHT_OF_SELECTED, pin))
        assertEquals(emptyList(), touches(ComponentKind.NOT, right), "right of the selected pin: $right")
        for (kind in listOf(ComponentKind.NOT, ComponentKind.INPUT, ComponentKind.AND)) {
            for (position in listOf(AccessibleInsertPosition.RIGHT_OF_SELECTED, AccessibleInsertPosition.BELOW_SELECTED,
                AccessibleInsertPosition.NEXT_FREE)) {
                // The Next free spot scan starts on the wire itself.
                val point = assertNotNull(accessibleInsertSuggestion(document, registry, kind, position, pin, GridPoint(150, 100)),
                    "$kind $position")
                assertEquals(emptyList(), touches(kind, point), "$kind $position at $point connects nothing")
            }
        }
        // Inserting the suggestion leaves the gate unconnected: the pin still drives only the original NOT.
        assertTrue(state.insertComponent(ComponentKind.NOT, right))
        val inserted = state.document.components.last()
        assertTrue(inserted.layout(registry).ports.none { port -> state.document.wires.any { it.contains(port.position) } })
    }

    // C42

    @Test
    fun aReadOnlyCanvasNodeOffersNoEditingMenuItem() {
        val registry = ComponentCatalog.registry
        val ram = registry.create(ComponentKind.RAM, "ram", GridPoint(200, 200))
        val gate = registry.create(ComponentKind.NOT, "not", GridPoint(100, 100))
        val sub = CircuitComponent("sub", ComponentKind.SUBCIRCUIT, GridPoint(300, 300), circuitName = "child")
        fun items(component: CircuitComponent, canEdit: Boolean, child: String? = null) =
            canvasAccessibleMenu(component, registry, canEdit, null, child).map { it.first }
        assertTrue(ComponentMenuItem.MEMORY_EDIT in items(ram, true) && ComponentMenuItem.MEMORY_CLEAR in items(ram, true))
        assertTrue(ComponentMenuItem.ROTATE_LEFT in items(gate, true))
        // Viewing and saving change nothing; rotating, clearing, loading or editing would.
        assertEquals(listOf(ComponentMenuItem.MEMORY_SAVE), items(ram, false))
        assertEquals(emptyList(), items(gate, false))
        assertEquals(listOf(ComponentMenuItem.VIEW_SUBCIRCUIT), items(sub, false, "child"))
        assertFalse(ComponentMenuItem.VIEW_SUBCIRCUIT in items(sub, true, null), "no View without the child circuit")
    }

    // X0

    @Test
    fun toggleBitSaysWhenNothingChangedAndKeepsTheBitThroughTheDebugCopy() {
        val wide = xml.replace("<a name=\"label\" val=\"A\"/>", "<a name=\"label\" val=\"A\"/><a name=\"width\" val=\"8\"/><a name=\"radix\" val=\"16\"/>")
        val state = EditorState(wide)
        val pin = state.document.components.single()
        state.setInteractionEnabled(false)
        assertFalse(state.toggleInputBit(pin.id, 3), "a locked editor changes nothing")
        state.setInteractionEnabled(true)
        assertTrue(state.simulation.vectorValues[pin.id]?.toULongOrNull().let { it == null || it == 0uL })

        val child = CircuitDocument("child", listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(20, 40),
            attributes = mapOf("width" to "8", "radix" to "16"))))
        val drive = CircuitComponent("drive", ComponentKind.INPUT, GridPoint(20, 40), attributes = mapOf("width" to "8", "radix" to "16"))
        val main = CircuitDocument("main", listOf(drive,
            CircuitComponent("childInstance", ComponentKind.SUBCIRCUIT, GridPoint(100, 40), circuitName = child.name)))
        val project = AppearanceEdits.apply(CircuitProject(listOf(main, child)), child.name, SymbolAppearance.classic(child))
        val instance = project.mainCircuit.components.single { it.kind == ComponentKind.SUBCIRCUIT }
        val wire = WireSegment(drive.position, instance.inputPorts().single())
        val nested = EditorState(CircCodec.writeProject(project.withCircuit(project.mainCircuit.copy(wires = listOf(wire)))))
        nested.setInputValue("drive", 0x08uL)

        // A circuit opened directly, not through an instance: Evolution changes no input there, and Set value says so.
        assertTrue(nested.requestInputValueEdit("drive"))
        nested.selectCircuit(child.name)
        assertFalse(nested.toggleInputBit("input", 3))
        assertEquals(Messages.StatusSubcircuitInput, nested.statusText.key)
        nested.reportStatus(LocalizedText.literal(""))
        assertFalse(nested.requestInputValueEdit("input"))
        assertEquals(Messages.StatusSubcircuitInput, nested.statusText.key)
        nested.setInteractionEnabled(false)
        nested.reportStatus(LocalizedText.literal(""))
        assertFalse(nested.requestInputValueEdit("input"))
        assertEquals("a11y_explorer_operation_failed", nested.statusText.key, "a locked editor is reported too")
        nested.setInteractionEnabled(true)

        // In an instance view the first poke asks for a debugging copy; confirming toggles bit 3, not the hex digit.
        nested.selectCircuit(main.name)
        nested.navigate(CircuitNavigationTarget(child.id, child.name, listOf(instance.id)))
        assertEquals(0x08uL, nested.simulation.vectorValues["input"]?.toULongOrNull())
        assertTrue(nested.toggleInputBit("input", 3))
        assertTrue(nested.canConfirmDebugCopy)
        nested.confirmDebugCopy()
        assertEquals(0x00uL, nested.simulation.vectorValues["input"]?.toULongOrNull(), "bit 3 toggled off")
    }

    @Test
    fun customCoordinatesSnapToTheGridInsteadOfBeingRefused() {
        assertEquals(GridPoint(100, 100), accessibleInsertionPoint("104", "96"))
        assertEquals(GridPoint(110, -20), accessibleInsertionPoint(" 105 ", "-16"))
        assertNull(accessibleInsertionPoint("x", "100"))
    }

    // X9

    @Test
    fun theMatrixBaseLabelIsCheckedBeforeApply() {
        val registry = ComponentCatalog.registry
        val document = EditorState(xml).document
        assertEquals(Messages.MatrixPlaceInvalidLabel, matrixLabelProblem(document, registry, ComponentKind.LED, "led 1", 2, 2)?.key)
        // A_X0_Y0 is taken by an existing component's label.
        val taken = document.copy(components = document.components.map { it.copy(label = "LED_X1_Y0") })
        assertEquals(Messages.MatrixPlaceInvalidLabel, matrixLabelProblem(taken, registry, ComponentKind.LED, "LED", 2, 1)?.key)
        assertNull(matrixLabelProblem(taken, registry, ComponentKind.LED, "LED", 1, 1))
        assertNull(matrixLabelProblem(document, registry, ComponentKind.LED, "LED", 4, 4))
        assertNull(matrixLabelProblem(document, registry, ComponentKind.LED, "", 4, 4))

        val placement = PendingMatrixPlacement(CircuitDocument(), registry.create(ComponentKind.LED, "t", GridPoint(0, 0)),
            GridPoint(100, 100), ComponentBounds(0, 0, 20, 20), "")
        assertEquals(placement.minDx + 10, placement.steppedSpacing(placement.minDx, placement.minDx, up = true))
        assertEquals(placement.minDx, placement.steppedSpacing(placement.minDx, placement.minDx, up = false))
        assertEquals(990, placement.steppedSpacing(990, placement.minDx, up = true))
        assertEquals(50, placement.steppedSpacing(45, placement.minDx, up = true), "a typed value steps from its grid step")
        assertEquals(placement.minDx, placement.steppedSpacing(null, placement.minDx, up = true))
    }

    // R06-1

    @Test
    fun aDragEndNearerAFarSiblingPortThanItsStartReachesThatPort() {
        // The documented fingertip trade-off: a release 10 units left of input 3 of a five-input AND, 30 units from
        // the start at input 0, attaches to input 3 at a touch reach of 20 units (17 dp at 0.85 dp per unit). The
        // snap ring shows the attachment before the finger lifts; the mouse's 8-unit reach keeps the free point.
        val registry = ComponentCatalog.registry
        val gate = registry.create(ComponentKind.AND, "g", GridPoint(200, 200), mapOf("inputs" to "5"))
        val document = CircuitDocument(components = listOf(gate))
        val inputs = gate.inputPorts(registry)
        val from = inputs[0]
        val target = inputs[3]
        val release = Offset(target.x - 10f, target.y.toFloat())
        val snapped = canvasPoint(release, snapToGrid = true)
        assertEquals(target, document.editToolConnectionPoint(release, snapped, 8f, registry, terminalTolerance = 20f, from = from))
        assertNull(document.editToolConnectionPoint(release, snapped, 8f, registry, terminalTolerance = 8f, from = from))
    }
}
