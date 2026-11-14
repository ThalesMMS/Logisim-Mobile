package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentMenu
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSelectionTest {
    @Test
    fun wireOnlySelectionMovesAndUndoesAsOneOperation() {
        val original = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(wires = listOf(original))))))
        state.selectWire(original)
        state.moveSelected(GridPoint(20, 30))
        val moved = WireSegment(GridPoint(120, 130), GridPoint(220, 130))
        assertEquals(listOf(moved), state.document.wires)
        assertEquals(setOf(moved), state.selectedWires)
        state.undo()
        assertEquals(listOf(original), state.document.wires)
    }

    @Test
    fun disconnectedMoveLeavesUnselectedWiresAndUndoesAsOneOperation() {
        val wire = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val circuit = CircuitDocument(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, wire.from, "IN"),
            CircuitComponent("out", ComponentKind.OUTPUT, wire.to, "OUT"),
        ), wires = listOf(wire))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val original = state.project
        val input = state.document.components.first { it.kind == ComponentKind.INPUT }
        val delta = GridPoint(0, 40)
        val destination = GridPoint(100, 140)
        state.changeMode(EditorMode.MOVE)

        state.select(input.id)
        state.moveSelected(delta)
        assertTrue(state.document.wires.any { it.contains(destination) })
        state.undo()

        state.select(input.id)
        state.keepConnectionsWhenMoving = false
        state.moveSelected(delta)
        assertEquals(destination, state.document.components.first { it.id == input.id }.position)
        assertEquals(listOf(wire), state.document.wires)
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)

        state.select(input.id)
        state.selectWire(wire, additive = true)
        state.moveSelected(delta)
        assertEquals(listOf(WireSegment(destination, GridPoint(200, 140))), state.document.wires)
    }

    @Test
    fun wireOnlyCopyRepeatedPasteAndDeleteKeepThePastedGroupSelected() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(100, 0))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(wires = listOf(wire))))))
        state.selectAll()
        state.handleCanvasCommand(CanvasCommand.COPY)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.confirmPaste()
        assertEquals(setOf(WireSegment(GridPoint(20, 20), GridPoint(120, 20))), state.selectedWires)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.tap(GridPoint(40, 40))
        assertEquals(3, state.document.wires.size)
        state.handleCanvasCommand(CanvasCommand.DELETE)
        assertEquals(2, state.document.wires.size)
        state.undo()
        assertEquals(3, state.document.wires.size)
    }

    @Test
    fun pastePreviewMovesAndCancelsWithoutEditingBeforeOneUndoableCommit() {
        val wire = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val circuit = CircuitDocument(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, wire.from, "IN"),
            CircuitComponent("out", ComponentKind.OUTPUT, wire.to, "OUT"),
        ), wires = listOf(wire))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val original = state.project
        val originalSelection = state.document.components.map { it.id }.toSet()
        state.selectAll()
        state.handleCanvasCommand(CanvasCommand.COPY)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        // The block's centre, (150, 100) on the grid, follows the pointer (C45).
        state.movePastePreview(GridPoint(300, 200))
        assertEquals(GridPoint(150, 100), state.pendingPaste?.offset)
        assertEquals(original, state.project)
        assertEquals(originalSelection, state.selectedIds)
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertNull(state.pendingPaste)
        assertEquals(original, state.project)
        assertEquals(originalSelection, state.selectedIds)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.movePastePreview(GridPoint(400, 300))
        state.confirmPaste()
        val newWire = WireSegment(GridPoint(350, 300), GridPoint(450, 300))
        assertNull(state.pendingPaste)
        assertEquals(setOf(newWire.from, newWire.to),
            state.document.components.filter { it.id in state.selectedIds }.map { it.position }.toSet())
        assertEquals(setOf(newWire), state.selectedWires)
        assertEquals(setOf(wire, newWire), state.document.wires.toSet())
        assertEquals(1, state.revision)
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)
    }

    @Test
    fun duplicatePreviewPlacesInOneUndoAndKeepsTheCopiedSelection() {
        val pin = CircuitComponent("pin", ComponentKind.INPUT, GridPoint(100, 100), "copy_pin")
        val led = CircuitComponent("led", ComponentKind.LED, GridPoint(160, 160), "duplicate_led")
        val circuit = CircuitDocument(components = listOf(pin, led))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val original = state.project
        state.selectComponent(state.document.components.single { it.label == pin.label }.id)
        state.handleCanvasCommand(CanvasCommand.COPY)
        state.selectComponent(state.document.components.single { it.label == led.label }.id)

        state.handleCanvasCommand(CanvasCommand.DUPLICATE)
        // The LED's bounds centre, (170, 160), follows the pointer.
        state.movePastePreview(GridPoint(300, 200))
        assertEquals(GridPoint(130, 40), state.pendingPaste?.offset)
        assertEquals(original, state.project)
        assertFalse(state.canUndo)
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertNull(state.pendingPaste)
        assertEquals(original, state.project)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.DUPLICATE)
        state.tap(GridPoint(400, 300))
        val placed = state.document.components.single { it.id in state.selectedIds }
        assertEquals(led.label, placed.label)
        assertEquals(GridPoint(390, 300), placed.position, "the LED's centre lands at the tap")
        assertEquals(3, state.document.components.size)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        assertEquals(pin.label, state.pendingPaste?.fragment?.components?.single()?.label)
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.PASTE)
        val anchor = state.pendingPaste!!.anchor
        state.tap(GridPoint(500, 400))
        assertEquals(pin.label, state.document.components.single {
            it.position == GridPoint(pin.position.x + 500 - anchor.x, pin.position.y + 400 - anchor.y)
        }.label)
    }

    private val turnable = """
        <project source="3.8.0" version="1.0">
          <lib desc="#Wiring" name="0"/><lib desc="#Gates" name="1"/><lib desc="#Base" name="2"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="1" loc="(200,100)" name="AND Gate"><a name="label" val="A"/></comp>
            <comp lib="1" loc="(200,200)" name="NOT Gate"><a name="label" val="B"/></comp>
            <comp lib="0" loc="(100,300)" name="Pin"><a name="label" val="P"/></comp>
            <comp lib="2" loc="(300,300)" name="Text"><a name="text" val="note"/></comp>
            <wire from="(400,100)" to="(500,100)"/>
          </circuit>
        </project>
    """.trimIndent()

    private fun EditorState.labelled(label: String) = document.components.single { it.label == label }
    private fun EditorState.text() = document.components.single { it.kind == ComponentKind.TEXT }

    @Test
    fun rotatingTheSelectionTurnsEveryComponentWithAFacingInOneUndoableStep() {
        val state = EditorState(turnable)
        val original = state.project
        state.changeMode(EditorMode.SELECT)
        val turned = listOf("A", "B", "P").map { state.labelled(it).id }
        turned.forEach { state.select(it, additive = true) }
        assertTrue(state.canRotateSelection)
        assertTrue(state.rotateSelected(left = false))
        // EditTool.attemptRotate: Direction.getRight, a quarter turn clockwise, for each of them.
        assertEquals(listOf("south", "south", "south"), listOf("A", "B", "P").map { state.labelled(it).facing })
        assertEquals(Messages.HistoryRotateSelection, state.undoHistory.first().label.key)
        assertEquals("Change Selection Facing", state.undoHistory.first().label.resolve(AppLanguage.ENGLISH))
        assertEquals(turned.toSet(), state.selectedIds)
        assertEquals(1, state.undoHistory.size)
        state.undo()
        assertEquals(original, state.project)

        state.select(state.labelled("A").id)
        assertTrue(state.rotateSelected(left = true))
        assertEquals("north", state.labelled("A").facing)
        // One component: Evolution's rotateComponentAction, Rotate %s.
        assertEquals(Messages.HistoryRotateComponent, state.undoHistory.first().label.key)
        assertEquals("Rotate AND", state.undoHistory.first().label.resolve(AppLanguage.ENGLISH))
        assertTrue(state.rotateComponent(state.labelled("A").id, left = false))
        assertEquals("east", state.labelled("A").facing)
        assertEquals(Messages.HistoryRotateComponent, state.undoHistory.first().label.key)
    }

    @Test
    fun wiresAndComponentsWithoutAFacingStayAsTheyAre() {
        val state = EditorState(turnable)
        val text = state.text()
        val wire = state.document.wires.single()
        assertFalse(ComponentMenu.hasFacing(text, state.componentRegistry))
        state.changeMode(EditorMode.EDIT)
        state.select(text.id)
        state.selectWire(wire, additive = true)
        assertFalse(state.canRotateSelection)
        assertFalse(state.rotateSelected(left = false))
        assertFalse(state.canUndo)

        state.select(state.labelled("B").id, additive = true)
        assertTrue(state.rotateSelected(left = false))
        assertEquals("south", state.labelled("B").facing)
        assertEquals(text, state.text())
        assertEquals(listOf(wire), state.document.wires)
        // The NOT alone turned, so the step is named after it.
        assertEquals(Messages.HistoryRotateComponent, state.undoHistory.first().label.key)
    }

    @Test
    fun addToSelectionTogglesItemsAddsMarqueesAndEndsWithTheSelectingTools() {
        val state = EditorState(turnable)
        val a = state.labelled("A").id
        val b = state.labelled("B").id
        state.toggleAdditiveSelection()
        assertFalse(state.additiveSelection, "Poke has no selection to add to")
        state.changeMode(EditorMode.EDIT)
        state.toggleAdditiveSelection()
        assertTrue(state.additiveSelection)
        // The canvas passes additive = true for a finger while it is on.
        state.select(a, additive = state.additiveSelection)
        state.select(b, additive = state.additiveSelection)
        assertEquals(setOf(a, b), state.selectedIds)
        state.select(a, additive = state.additiveSelection)
        assertEquals(setOf(b), state.selectedIds)
        // An additive marquee adds what it encloses and keeps the rest (Mobile does not drop already-selected items).
        state.selectInArea(GridPoint(50, 250), GridPoint(150, 350), additive = state.additiveSelection)
        assertEquals(setOf(b, state.labelled("P").id), state.selectedIds)

        state.changeMode(EditorMode.SELECT)
        assertTrue(state.additiveSelection, "Edit, Select and Move keep it")
        state.changeMode(EditorMode.MOVE)
        assertTrue(state.additiveSelection)
        for (mode in listOf(EditorMode.INTERACT, EditorMode.WIRE, EditorMode.PLACE, EditorMode.TEXT)) {
            state.changeMode(EditorMode.SELECT)
            state.toggleAdditiveSelection()
            if (!state.additiveSelection) state.toggleAdditiveSelection()
            assertTrue(state.additiveSelection)
            state.changeMode(mode)
            assertFalse(state.additiveSelection, "$mode ends it")
        }
        state.changeMode(EditorMode.SELECT)
        state.toggleAdditiveSelection()
        state.newDocument()
        assertFalse(state.additiveSelection, "Another document ends it")
    }

    @Test
    fun addToSelectionEndsWhenNavigatingOrUndoingIntoAnotherDocument() {
        val state = EditorState(turnable.replace("</project>", """<circuit name="other"/></project>"""))
        val other = state.project.circuits.single { it.name == "other" }
        state.changeMode(EditorMode.EDIT)
        state.toggleAdditiveSelection()
        assertTrue(state.additiveSelection)
        state.navigate(CircuitNavigationTarget(other.id, other.name))
        assertFalse(state.additiveSelection, "another circuit ends it")

        // An ordinary undo keeps it; an undo that brings back the replaced document ends it.
        val main = state.project.mainCircuit
        state.navigate(CircuitNavigationTarget(main.id, main.name))
        state.select(state.labelled("B").id)
        state.toggleAdditiveSelection()
        assertTrue(state.rotateSelected(left = false))
        state.undo()
        assertTrue(state.additiveSelection, "an undo inside the document keeps it")
        state.newDocument()
        state.changeMode(EditorMode.SELECT)
        state.toggleAdditiveSelection()
        assertTrue(state.additiveSelection)
        state.undo()
        assertEquals(main.id, state.project.mainCircuit.id)
        assertFalse(state.additiveSelection, "undoing back to the replaced document ends it")
    }

    @Test
    fun whileAddingTheSelectingToolsShowTheAdditiveHint() {
        val state = EditorState(turnable)
        for ((mode, hint) in listOf(EditorMode.EDIT to Messages.StatusEditToolTouch, EditorMode.SELECT to Messages.StatusSelectTouch,
                EditorMode.MOVE to Messages.StatusMove)) {
            state.changeMode(mode)
            assertEquals(hint, state.displayedStatusText.key, "$mode")
            state.toggleAdditiveSelection()
            // In Move a background drag then draws a marquee (canvasDragAction), so "drag the background to pan" is wrong.
            assertEquals(Messages.StatusAdditiveTouch, state.displayedStatusText.key, "$mode")
            assertEquals(hint, state.statusText.key, "the tool's own hint comes back")
            state.toggleAdditiveSelection()
            assertEquals(hint, state.displayedStatusText.key, "$mode")
        }
        assertEquals(CanvasDragAction.SELECT_AREA, canvasDragAction(EditorMode.MOVE, CanvasHit.Empty, additive = true))
        assertEquals(CanvasDragAction.PAN, canvasDragAction(EditorMode.MOVE, CanvasHit.Empty, additive = false))
        // Other messages, such as the result of a command, show as they are.
        state.toggleAdditiveSelection()
        state.reportStatus("done")
        assertEquals("done", state.displayedStatusText.resolve(AppLanguage.ENGLISH))
        state.changeMode(EditorMode.INTERACT)
        assertEquals(Messages.StatusInteract, state.displayedStatusText.key)
    }

    @Test
    fun editingAPropertyTheSelectionDoesNotShareReportsItLocalized() {
        val state = EditorState(turnable)
        state.select(state.labelled("A").id)
        state.select(state.text().id, additive = true)
        val before = state.project
        state.updateSelectedAttributes(mapOf("size" to "30"))
        assertEquals(before, state.project)
        assertEquals(Messages.InspectorNotEditable, state.statusText.key)
        assertTrue(state.statusText.resolve(AppLanguage.ENGLISH).startsWith("Property "))
    }

    @Test
    fun cancellingAnUnfinishedWireKeepsTheDocumentUnchanged() {
        val state = EditorState(null)
        val original = state.project
        state.changeMode(EditorMode.WIRE)
        state.tap(GridPoint(20, 20))
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertEquals(null, state.pendingWire)
        assertEquals(original, state.project)
        assertTrue(state.status.contains("cancelada"))
    }

    /** A font whose characters, spaces included, are all [unit] wide at 10 sp. */
    private fun evenFont(unit: Float) = { text: String, size: Float -> text.length * unit * size / 10f }

    // PKG-07: the selection bar's captions shrink before they wrap, and wrap only between words.
    @Test
    fun selectionBarCaptionsNeverBreakInsideAWord() {
        val measure = evenFont(5f)
        // 45 px at 10 sp: in a 44 px cell "Adicionar" shrinks to 9.5 sp and stays one word.
        assertEquals(SelectionCaption(9.5f, listOf("Adicionar")), fitSelectionCaption("Adicionar", 44f, measure))
        assertEquals(SelectionCaption(10f, listOf("Adicionar")), fitSelectionCaption("Adicionar", 48f, measure))
        assertEquals(SelectionCaption(10f, listOf("Girar à", "esquerda")), fitSelectionCaption("Girar à esquerda", 44f, measure))
        // 48 px even at 8 sp: "Propriedades" keeps a line of its own, which the caption ellipsizes, instead of breaking.
        assertEquals(SelectionCaption(8f, listOf("Propriedades")), fitSelectionCaption("Propriedades", 44f, measure))
        // More words than two lines hold: the last line takes the rest, ellipsized.
        assertEquals(SelectionCaption(8f, listOf("aaaaaaaaaaa", "bbbbbbbbbbb ccccccccccc")),
            fitSelectionCaption("aaaaaaaaaaa bbbbbbbbbbb ccccccccccc", 44f, measure))
        for (text in listOf("Girar à esquerda", "Adicionar", "Propriedades", "Drehen nach links", "Eigenschaften", "左に回転"))
            for (width in listOf(20f, 44f, 48f, 52f, 72f)) {
                val caption = fitSelectionCaption(text, width, measure)
                assertEquals(text.split(' '), caption.lines.flatMap { it.split(' ') }, "$text in $width")
                assertTrue(caption.lines.size in 1..2 && caption.size in 8f..10f, "$text in $width: $caption")
            }
    }
}
