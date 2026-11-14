package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircImportResult
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorStateTest {
    @Test
    fun theStatusLineStartsWithThePokeHintAndTouchHintsNameTwoFingerPanning() {
        val xml = """
            <project source="5.1.0-dev" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <main name="main"/>
              <circuit name="main"><comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="E2E_IN"/></comp></circuit>
            </project>
        """.trimIndent()
        // C145: the first open says how many version defaults it applied; once that report was presented, an editor
        // for the same file starts with the hint.
        val opened = EditorState(xml)
        assertEquals("import_report_status", opened.statusText.key)
        val state = EditorState(xml, initialImportReport = checkNotNull(opened.importReport).consumed())
        // Not "Tap A or B to toggle the input." on a circuit without A or B: the hint of the Poke tool it opens in.
        assertEquals(EditorMode.INTERACT, state.mode)
        assertEquals(Messages.StatusInteract, state.statusText.key)
        assertNotEquals("status_initial", state.statusText.key)
        for ((mode, key) in listOf(EditorMode.EDIT to Messages.StatusEditToolTouch, EditorMode.SELECT to Messages.StatusSelectTouch,
                EditorMode.WIRE to Messages.StatusWireTouch)) {
            state.changeMode(mode)
            assertEquals(key, state.statusText.key)
            for (language in listOf(AppLanguage.ENGLISH, AppLanguage.PORTUGUESE)) {
                val hint = state.statusText.resolve(language)
                assertTrue(hint.lowercase().contains(if (language == AppLanguage.ENGLISH) "two fingers" else "dois dedos"), hint)
                assertFalse(hint.startsWith("Click"), hint)
            }
        }
        state.changeMode(EditorMode.SELECT)
        // No Shift on a phone: the hint names the selection bar's Add toggle.
        assertTrue(state.statusText.resolve(AppLanguage.ENGLISH).contains("Add selects several"))
        assertTrue(state.statusText.resolve(AppLanguage.PORTUGUESE).contains("Adicionar"))
        state.changeMode(EditorMode.INTERACT)
        assertEquals(Messages.StatusInteract, state.statusText.key)
    }

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

    private fun EditorState.tickFrequency() = SimulationProjectOptions.read(project).tickFrequencyHz

    @Test
    fun aTickFrequencyChangeKeepsTheSelectionTheHistoryAndTheRunAndIsSaved() {
        val state = EditorState(null)
        state.place(ComponentKind.NOT)
        state.tap(GridPoint(500, 200))
        val input = state.document.components.first { it.kind == ComponentKind.INPUT }.id
        state.select(input)
        val undo = state.undoHistory
        val redo = state.redoHistory
        var invalidated = 0
        state.onSimulationInvalidated = { invalidated++ }
        val revision = state.revision

        assertTrue(state.setTickFrequency(8.0))

        assertEquals(8.0, state.tickFrequency())
        assertEquals(setOf(input), state.selectedIds)
        assertEquals(undo, state.undoHistory)
        assertEquals(redo, state.redoHistory)
        assertEquals(0, invalidated, "Evolution's setTickFrequency keeps the simulation running")
        assertEquals(revision + 1, state.revision)
        // Choosing the current rate again changes nothing.
        assertTrue(state.setTickFrequency(8.0))
        assertEquals(revision + 1, state.revision)
    }

    @Test
    fun theTickFrequencySerializesAsSimulationOptionsWritesIt() {
        val xml = CircCodec.write(dev.logisim.mobile.core.DemoCircuits.andGate())
        val chosen = EditorState(xml)
        val panel = EditorState(xml)
        assertTrue(chosen.setTickFrequency(8.0))
        // SimulationOptionsPanel applies options.applyTo(project) through applyProject.
        panel.applyProject(SimulationProjectOptions.read(panel.project).copy(tickFrequencyHz = 8.0).applyTo(panel.project))
        assertEquals(panel.exportXml(), chosen.exportXml())
        assertTrue("""<a name="simulationFrequency" val="8.0"/>""" in chosen.exportXml(), chosen.exportXml())
        assertTrue("""<a name="tickFrequencyHz" val="8.0"/>""" in chosen.exportXml())
    }

    @Test
    fun undoAndRedoKeepTheNewestTickFrequency() {
        val state = EditorState(null)
        val components = state.document.components.size
        state.place(ComponentKind.NOT)
        state.tap(GridPoint(500, 200))
        assertTrue(state.setTickFrequency(8.0))

        state.undo()
        assertEquals(components, state.document.components.size, "the edit is undone")
        assertEquals(8.0, state.tickFrequency(), "the rate is not")
        state.redo()
        assertEquals(components + 1, state.document.components.size)
        assertEquals(8.0, state.tickFrequency())

        assertTrue(state.setTickFrequency(2.0))
        state.undo()
        assertEquals(2.0, state.tickFrequency())
        assertTrue("""<a name="simulationFrequency" val="2.0"/>""" in state.exportXml())
        // Later edits keep writing the chosen rate: the editor was not left on the old main circuit.
        state.place(ComponentKind.NOT)
        state.tap(GridPoint(600, 300))
        assertEquals(2.0, state.tickFrequency())
    }

    @Test
    fun aRateAppliedFromSimulationOptionsStaysUndoable() {
        val state = EditorState(null)
        assertTrue(state.setTickFrequency(8.0))
        state.applyProject(SimulationProjectOptions.read(state.project).copy(tickFrequencyHz = 16.0).applyTo(state.project))
        assertEquals(16.0, state.tickFrequency())
        state.undo()
        assertEquals(8.0, state.tickFrequency())
        state.redo()
        assertEquals(16.0, state.tickFrequency())
    }

    @Test
    fun aReadOnlyDocumentKeepsItsTickFrequency() {
        val state = EditorState(CircCodec.write(dev.logisim.mobile.core.DemoCircuits.andGate()), initialReadOnly = true)
        val xml = state.exportXml()
        assertFalse(state.setTickFrequency(8.0))
        assertEquals(xml, state.exportXml())
        assertEquals(0, state.revision)
    }

    @Test
    fun inputValuesDriveUnknownAndSignedValuesWithoutTouchingTheDocument() {
        val state = EditorState("""
            <project source="5.1.0-dev" version="1.0"><lib desc="#Wiring" name="0"/><main name="main"/>
              <circuit name="main"><comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="T"/><a name="width" val="8"/>
                <a name="behavior" val="tristate"/><a name="radix" val="10signed"/></comp></circuit>
            </project>
        """.trimIndent())
        val pin = state.document.components.single().id
        val xml = state.exportXml()
        state.setInputValue(pin, dev.logisim.mobile.core.LogicVector.unknown(8))
        assertEquals(dev.logisim.mobile.core.LogicVector.unknown(8), state.simulation.vectorValues[pin])
        state.setInputValue(pin, 0xFDuL)
        assertEquals(0xFDuL, state.simulation.vectorValues[pin]?.toULongOrNull())
        assertEquals(xml, state.exportXml())
        assertFalse(state.canUndo)
    }
}
