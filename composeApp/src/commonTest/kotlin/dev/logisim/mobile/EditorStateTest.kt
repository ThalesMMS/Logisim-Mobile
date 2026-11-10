package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircImportResult
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorStateTest {
    @Test
    fun insertionFollowsThePreferenceAndKeepsTheNewComponentSelected() {
        val state = EditorState(null)
        state.newDocument()
        val first = GridPoint(100, 100)
        val second = GridPoint(200, 100)

        state.place(ComponentKind.AND)
        state.tap(first)
        assertEquals(EditorMode.EDIT, state.mode)
        assertEquals(state.document.components.single().id, state.selectedId)
        assertEquals("AND inserted. Edit mode.", state.statusText.resolve())
        state.tap(second)
        assertEquals(listOf(first), state.document.components.map { it.position })

        state.afterPlacement = EditorAfterPlacement.KEEP_TOOL
        state.place(ComponentKind.AND)
        state.tap(second)
        state.tap(GridPoint(300, 100))
        assertEquals(EditorMode.PLACE, state.mode)
        assertEquals("AND inserted. Tap to insert another.", state.statusText.resolve())
        assertEquals(listOf(first, second, GridPoint(300, 100)), state.document.components.map { it.position })
    }

    @Test
    fun choosingAnOlderHistoryActionTraversesBothStacksInOrder() {
        val state = EditorState(null)
        state.newDocument()
        val first = ComponentKind.NOT
        val second = ComponentKind.OR
        val third = ComponentKind.XOR
        assertTrue(state.insertComponent(first, GridPoint(100, 100)))
        assertTrue(state.insertComponent(second, GridPoint(200, 100)))
        assertTrue(state.insertComponent(third, GridPoint(300, 100)))
        assertEquals(listOf("Add XOR", "Add OR", "Add NOT"), state.undoHistory.take(3).map { it.label.resolve() })
        assertEquals("Inserir XOR", state.undoHistory.first().label.resolve(AppLanguage.PORTUGUESE))

        state.travelHistory(EditorHistoryDirection.UNDO, state.undoHistory[1].id)

        assertEquals(listOf(first), state.document.components.map { it.kind })
        assertEquals(listOf("Add OR", "Add XOR"), state.redoHistory.map { it.label.resolve() })
        val lastRedoAction = state.redoHistory.last().id

        state.travelHistory(EditorHistoryDirection.REDO, lastRedoAction)

        assertEquals(listOf(first, second, third), state.document.components.map { it.kind })
        assertTrue(state.redoHistory.isEmpty())
        assertEquals(lastRedoAction, state.undoHistory.first().id)

        state.undo()
        assertTrue(state.insertComponent(third, GridPoint(400, 100)))
        state.travelHistory(EditorHistoryDirection.REDO, lastRedoAction)
        assertEquals(listOf(GridPoint(100, 100), GridPoint(200, 100), GridPoint(400, 100)), state.document.components.map { it.position })
        assertFalse(state.canRedo)
    }

    @Test
    fun loadingTheSameExampleResetsInputsAndClockWithoutSavingAnUnchangedDocument() {
        val state = EditorState(null)
        val input = state.document.components.first { it.kind == ComponentKind.INPUT }.id
        state.toggle(input)
        state.tick()
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[input])
        assertEquals(1L, state.simulation.ticks)
        assertEquals(0, state.revision)

        state.loadDemo()

        assertEquals(LogicValue.LOW, state.simulation.componentValues[input])
        assertEquals(0L, state.simulation.ticks)
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)
    }

    @Test
    fun importResetsStimuliEvenWhenComponentIdentifiersAreReused() {
        val seed = EditorState(null)
        val xml = CircCodec.write(seed.document)
        val state = EditorState(xml)
        val ids = state.document.components.map { it.id }
        val input = state.document.components.first { it.kind == ComponentKind.INPUT }.id
        state.toggle(input)
        state.tick()
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[input])

        assertTrue(state.importXml(xml))

        assertEquals(ids, state.document.components.map { it.id })
        assertEquals(LogicValue.LOW, state.simulation.componentValues[input])
        assertEquals(0L, state.simulation.ticks)
    }

    @Test
    fun creatingAnEmptyDocumentCanBeUndoneAndSerialized() {
        val state = EditorState(null)
        val original = state.document
        state.newDocument()
        assertTrue(state.document.components.isEmpty())
        assertTrue(state.document.wires.isEmpty())
        assertTrue(state.canUndo)
        assertEquals(1, state.revision)
        val decoded = assertIs<CircImportResult.Success>(CircCodec.read(CircCodec.write(state.document)))
        assertEquals(state.document, decoded.document)

        state.undo()

        assertEquals(original, state.document)
        assertEquals(2, state.revision)
        assertTrue(state.canRedo)
    }

    @Test
    fun inputStimulationIsNotPartOfDocumentHistory() {
        val state = EditorState(null)
        val input = state.document.components.first { it.kind == ComponentKind.INPUT }.id
        state.toggle(input)
        state.place(ComponentKind.NOT)
        state.tap(GridPoint(500, 200))
        assertEquals(1, state.revision)
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[input])
        assertTrue(state.canUndo)

        state.undo()

        assertEquals(LogicValue.HIGH, state.simulation.componentValues[input])
        assertFalse(state.canUndo)
    }

    @Test
    fun aFailedImportLeavesDocumentAndStimuliUnchanged() {
        val state = EditorState(null)
        val before = state.document
        val input = before.components.first { it.kind == ComponentKind.INPUT }.id
        state.toggle(input)
        val simulation = state.simulation

        assertFalse(state.importXml("<not-a-circuit/>"))

        assertEquals(before, state.document)
        assertEquals(simulation, state.simulation)
        assertEquals(0, state.revision)
        assertNotEquals(null, state.importError)
    }
}
