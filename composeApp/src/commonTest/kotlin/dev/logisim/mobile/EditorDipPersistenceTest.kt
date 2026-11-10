package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorDipPersistenceTest {
    @Test
    fun lastPositionsBecomeSerializedContentAndSurviveReopening() {
        val state = EditorState(fixture("A"))
        val dip = state.component("DIP")
        assertTrue(state.sendComponentEvent(dip.id, IoInputEvent.ToggleSwitch(0)))
        assertTrue(state.sendComponentEvent(dip.id, IoInputEvent.ToggleSwitch(1)))
        assertEquals(3uL, positions(state, dip.id))
        assertEquals("3", savedPositions(state))
        assertEquals(2, state.revision)
        assertFalse(state.canUndo, "Input stimulation must not create a schematic undo action")

        val reopened = EditorState(state.exportXml())
        assertEquals(3uL, positions(reopened, reopened.component("DIP").id))
        assertEquals("3", savedPositions(reopened))
    }

    @Test
    fun clockAndMomentaryButtonDoNotSaveOrInvalidateAnUnchangedDocument() {
        val state = EditorState(fixture("A"))
        val project = state.project
        val xml = state.exportXml()
        repeat(4) { state.tick() }
        assertTrue(state.sendComponentEvent(state.component("Button").id, IoInputEvent.Press(true)))
        assertTrue(state.sendComponentEvent(state.component("Button").id, IoInputEvent.Press(false)))
        assertSame(project, state.project)
        assertEquals(xml, state.exportXml())
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)
    }

    @Test
    fun undoAndRedoOfAnIndependentEditKeepTheLatestSavedInputPositions() {
        val state = EditorState(fixture("A"))
        assertTrue(state.insertComponent(ComponentKind.NOT, GridPoint(600, 100)))
        assertTrue(state.sendComponentEvent(state.component("DIP").id, IoInputEvent.ToggleSwitch(0)))
        assertEquals("1", savedPositions(state))
        state.undo()
        assertFalse(state.document.components.any { it.kind == ComponentKind.NOT })
        assertEquals("1", savedPositions(state))
        assertEquals(1uL, positions(state, state.component("DIP").id))
        state.redo()
        assertTrue(state.document.components.any { it.kind == ComponentKind.NOT })
        assertEquals("1", savedPositions(state))
        assertEquals(1uL, positions(state, state.component("DIP").id))
    }

    @Test
    fun asyncAcceptedResultPersistsOnceAndRejectedOrReadOnlyResultsPreserveXml() {
        val state = EditorState(fixture("A"))
        val requested = mutableListOf<SimulationRunSnapshot>()
        state.onSimulationRequested = { snapshot, _ -> requested += snapshot; SimulationRequestResult.ACCEPTED }
        assertTrue(state.sendComponentEvent(state.component("DIP").id, IoInputEvent.ToggleSwitch(0)))
        assertEquals("0", savedPositions(state))
        assertEquals(0, state.revision)
        val snapshot = requested.single()
        val result = advanceSimulation(snapshot, SimulationStep.SETTLE).state
        state.publishRun(snapshot, result)
        assertEquals("1", savedPositions(state))
        assertEquals(1, state.revision)
        state.publishRun(snapshot, result)
        assertEquals(1, state.revision)

        val raw = fixture("Read only") + "\n<!-- preserve original bytes -->\n"
        val readOnly = EditorState(raw, initialReadOnly = true)
        val before = readOnly.captureRunSnapshot()
        assertFalse(readOnly.sendComponentEvent(readOnly.component("DIP").id, IoInputEvent.ToggleSwitch(0)))
        readOnly.publishRun(before, result)
        assertEquals(raw, readOnly.exportXml())
        assertEquals(0, readOnly.revision)
    }

    @Test
    fun theLastInteractedInstanceDeterminesSavedAttributesWithoutMixingRuntimeStates() {
        val dip = CircuitComponent("dip", IoKinds.DipSwitch, GridPoint(100, 100), "DIP",
            mapOf("number" to "4", "resetbehavior" to "last", "positions" to "0"))
        val child = CircuitDocument("child", listOf(dip))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("left", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), "Left", circuitName = "child"),
            CircuitComponent("right", ComponentKind.SUBCIRCUIT, GridPoint(400, 100), "Right", circuitName = "child"),
        ))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child), "main")))
        val left = state.component("Left").id
        val right = state.component("Right").id
        val importedDip = state.project.circuit("child")!!.components.single().id
        assertTrue(state.injectEvents(mapOf("$left/$importedDip" to listOf(IoInputEvent.ToggleSwitch(0)))))
        assertTrue(state.injectEvents(mapOf("$right/$importedDip" to listOf(IoInputEvent.ToggleSwitch(1)))))
        assertEquals(1uL, assertIs<IoDipState>(state.rootSimulation.circuitStates.getValue(left).componentStates[importedDip]).positions)
        assertEquals(2uL, assertIs<IoDipState>(state.rootSimulation.circuitStates.getValue(right).componentStates[importedDip]).positions)
        assertEquals("2", savedPositions(state, "child"))
        val revision = state.revision
        repeat(4) { state.tick() }
        assertEquals(revision, state.revision)
        assertEquals("2", savedPositions(state, "child"))
        val reopened = EditorState(state.exportXml())
        assertEquals(2uL, assertIs<IoDipState>(reopened.rootSimulation.circuitStates.getValue(left).componentStates[importedDip]).positions)
        assertEquals(2uL, assertIs<IoDipState>(reopened.rootSimulation.circuitStates.getValue(right).componentStates[importedDip]).positions)
    }

    @Test
    fun undoAcrossProjectReplacementKeepsEachDocumentsSavedPositions() {
        val state = EditorState(fixture("A"))
        assertTrue(state.sendComponentEvent(state.component("DIP").id, IoInputEvent.ToggleSwitch(0)))
        assertTrue(state.importXml(fixture("B")))
        assertTrue(state.sendComponentEvent(state.component("DIP").id, IoInputEvent.ToggleSwitch(1)))
        assertEquals("2", savedPositions(state))
        state.undo()
        assertEquals("A", state.project.mainCircuitName)
        assertEquals("1", savedPositions(state))
        state.redo()
        assertEquals("B", state.project.mainCircuitName)
        assertEquals("2", savedPositions(state))
    }

    @Test
    fun explicitPositionAttributeEditsKeepTheirOwnUndoAndRedo() {
        val state = EditorState(fixture("A"))
        val dip = state.component("DIP").id
        assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(0)))
        state.selectComponent(dip)
        state.updateSelectedAttributes(mapOf("positions" to "2"))
        assertEquals("2", savedPositions(state))
        state.undo()
        assertEquals("1", savedPositions(state))
        state.redo()
        assertEquals("2", savedPositions(state))
    }

    @Test
    fun openAndClosedResetModesDoNotSerializeTransientPositions() {
        for (mode in listOf("open", "closed")) {
            val state = EditorState(fixture("A").replace("val=\"last\"", "val=\"$mode\""))
            assertTrue(state.sendComponentEvent(state.component("DIP").id, IoInputEvent.ToggleSwitch(0)))
            assertEquals("0", savedPositions(state))
            assertEquals(0, state.revision)
            assertFalse(state.canUndo)
        }
    }

    @Test
    fun queuedInteractionsCaptureTheNewPersistedProjectBeforeTheNextStimulus() = runBlocking {
        val state = EditorState(fixture("A"))
        val jobs = mutableListOf<Job>()
        val controller = SimulationRunController(this, state::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step).state },
            isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun)
        state.onSimulationRequested = { snapshot, _ ->
            if (controller.submitStimulus(snapshot.inputs, snapshot.vectorInputs, snapshot.events)?.let { jobs += it } != null)
                SimulationRequestResult.ACCEPTED else SimulationRequestResult.REJECTED
        }
        val dip = state.component("DIP").id
        assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(0)))
        assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(1)))
        jobs.joinAll()
        assertEquals(3uL, positions(state, dip))
        assertEquals("3", savedPositions(state))
        assertEquals(2, state.revision)
        controller.close()
    }

    @Test
    fun unchangedFrequencyAfterSavingTheFirstDipKeepsTheNextAcceptedInteraction() = runBlocking {
        val state = EditorState(fixture("A"))
        val firstMayFinish = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondMayFinish = CompletableDeferred<Unit>()
        val jobs = mutableListOf<Job>()
        var advances = 0
        val controller = SimulationRunController(this, state::captureRunSnapshot,
            advance = { snapshot, step ->
                if (++advances == 1) firstMayFinish.await()
                else { secondStarted.complete(Unit); secondMayFinish.await() }
                advanceSimulation(snapshot, step).state
            },
            isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun,
            workerDispatcher = Dispatchers.Unconfined)
        try {
            state.onSimulationRequested = { snapshot, _ ->
                if (controller.submitStimulus(snapshot.inputs, snapshot.vectorInputs, snapshot.events)?.let { jobs += it } != null)
                    SimulationRequestResult.ACCEPTED else SimulationRequestResult.REJECTED
            }
            val dip = state.component("DIP").id
            assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(0)))
            assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(1)))
            firstMayFinish.complete(Unit)
            withTimeout(5_000) { secondStarted.await() }
            assertEquals("1", savedPositions(state))
            // App reconfigures the frequency when the first saved interaction changes its project.
            assertTrue(controller.configureFrequency(SimulationProjectOptions.read(state.project).tickFrequencyHz))
            secondMayFinish.complete(Unit)
            jobs.joinAll()
            assertEquals("3", savedPositions(state))
            assertEquals(3uL, positions(state, dip))
            assertEquals(2, state.revision)
            assertTrue(jobs.none { it.isCancelled })
        } finally {
            firstMayFinish.complete(Unit)
            secondMayFinish.complete(Unit)
            controller.close()
        }
    }

    private fun fixture(name: String): String = CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(name, listOf(
        CircuitComponent("dip", IoKinds.DipSwitch, GridPoint(100, 100), "DIP",
            mapOf("number" to "4", "resetbehavior" to "last", "positions" to "0")),
        CircuitComponent("button", IoKinds.Button, GridPoint(300, 100), "Button"),
        CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(500, 100), "Clock"),
    )))))

    private fun EditorState.component(label: String) = document.components.single { it.label == label }
    private fun positions(state: EditorState, id: String) = assertIs<IoDipState>(state.rootSimulation.componentStates[id]).positions
    private fun savedPositions(state: EditorState, circuit: String = state.project.mainCircuitName): String? {
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(state.exportXml())).project
        return reopened.circuit(circuit)!!.components.single { it.kind == IoKinds.DipSwitch }.attributes["positions"]
    }
}
