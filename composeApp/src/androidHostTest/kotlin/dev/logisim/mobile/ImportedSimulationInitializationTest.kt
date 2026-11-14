package dev.logisim.mobile

import dev.logisim.mobile.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.test.*

/** Exercise the catalog callback and simulation queue used by a mounted editor import. */
class ImportedSimulationInitializationTest {
    private class MemoryDisk : WorkspaceCatalogStorage {
        private val files = mutableMapOf<String, String>()
        override fun <T> withExclusiveAccess(action: () -> T): T = synchronized(files) { action() }
        override fun read(name: String) = synchronized(files) { files[name] }
        override fun listNames() = synchronized(files) { files.keys.toList() }
        override fun writeAtomically(name: String, contents: String) { synchronized(files) { files[name] = contents } }
    }

    @Test
    fun catalogReplacementsCannotCancelImportedOrRestoredProjectInitialization() = runBlocking<Unit> {
        assertCatalogImportAndHistory()
    }

    @Test
    fun projectFrequencyEffectCannotCancelInitializationWhenHertzChanges() = runBlocking<Unit> {
        assertCatalogImportAndHistory(syncFrequency = true)
    }

    @Test
    fun navigationEffectCannotCancelTheNewProjectsInitialization() = runBlocking<Unit> {
        assertCatalogImportAndHistory(syncNavigation = true)
    }

    private suspend fun assertCatalogImportAndHistory(syncFrequency: Boolean = false, syncNavigation: Boolean = false) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val control = EditorSessionControl()
        val runtime = CatalogWorkspaceRuntime(MemoryDisk(), "io-import-initialization", control,
            deliveryContext = coroutineContext)
        var controller: SimulationRunController? = null
        try {
            val initial = CircuitProject(listOf(DemoCircuits.andGate()))
            runtime.start(CircCodec.writeProject(if (syncFrequency) SimulationProjectOptions(tickFrequencyHz = 1.0).applyTo(initial) else initial))
            until { runtime.state.workspace != null }
            val workspace = runtime.state.workspace!!
            val state = EditorState(workspace.editorXml)
            val run = bindSimulation(scope, state)
            controller = run
            state.onWorkspaceProjectReplaced = { runtime.projectReplaced(workspace.editorMount, it) }
            control.attach(run::pause, run::setActive, state::exportViewState, state::importViewState,
                identity = { state.documentIdentity }, exportXml = state::exportXml,
                documentWritable = { state.documentWritable }, setInteractionEnabled = state::setInteractionEnabled)
            runtime.editorAttached(workspace.editorMount)
            until { !runtime.state.busy && state.interactionsEnabled && control.activationAllowed }
            if (syncFrequency) run.configureFrequency(SimulationProjectOptions.read(state.project).tickFrequencyHz)
            var navigation = state.activeCircuitName to state.activeInstancePath
            fun projectEffects() {
                if (syncFrequency) {
                    val frequency = SimulationProjectOptions.read(state.project).tickFrequencyHz
                    assertTrue(run.configureFrequency(frequency))
                    assertEquals(frequency, run.status.value.frequencyHz)
                }
                val current = state.activeCircuitName to state.activeInstancePath
                // The production navigation effect: only a circuit outside the running one pauses (C58).
                if (syncNavigation && current != navigation) pauseOutsideRunningCircuit(state, run)
                navigation = current
            }
            val source = sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ"))
                .first { it.isFile }.readText()
            val fixture = if (syncFrequency) CircCodec.writeProject(SimulationProjectOptions(tickFrequencyHz = 2.0)
                .applyTo(assertIs<CircProjectImportResult.Success>(CircCodec.readProject(source)).project)) else source

            assertTrue(state.importXml(fixture))
            projectEffects()
            // Match the production driver: import, then one public Event Step, without a clock pulse.
            assertNotNull(run.step(SimulationStep.EVENT)).join()
            runtime.awaitPersistence()

            val expected = mapOf(
                "OUT_BUTTON" to "0", "OUT_LED_LEVEL" to "0", "OUT_DIP0" to "0", "OUT_DIP1" to "0",
                "OUT_JOY_X" to "1000", "OUT_JOY_Y" to "1000",
                "OUT_RGB_R" to "1", "OUT_RGB_G" to "1", "OUT_RGB_B" to "0",
                "OUT_BAR_VALUE" to "10100101", "OUT_SEGMENTS" to "00111111",
                "OUT_KEYBOARD_DATA" to "0000000", "OUT_KEYBOARD_AVAILABLE" to "0", "OUT_CLOCK" to "0",
                "OUT_MATRIX_ROW0" to "01110", "OUT_KEYPAD_C0" to "1", "OUT_KEYPAD_C1" to "1",
                "OUT_KEYPAD_C2" to "1", "OUT_KEYPAD_C3" to "1",
            )
            fun assertInitializedOutputs() {
                val actual = state.document.components.filter { it.kind == ComponentKind.OUTPUT }
                    .associate { it.label to state.simulation.vectorValues[it.id]?.display }
                assertEquals(expected, actual)
                assertEquals(0L, state.simulation.ticks)
                assertTrue(state.simulation.pendingEvents.isEmpty(), "Initialization must finish all delayed outputs")
                assertFalse(state.simulation.oscillating)
            }
            assertInitializedOutputs()
            assertEquals(SimulationStep.EVENT, run.status.value.lastStep)
            assertEquals("IO_Showcase", state.project.mainCircuitName)

            state.newDocument()
            projectEffects()
            assertNotNull(run.step(SimulationStep.EVENT)).join()
            assertTrue(state.document.components.isEmpty())
            state.undo()
            projectEffects()
            assertNotNull(run.step(SimulationStep.EVENT)).join()
            runtime.awaitPersistence()
            assertInitializedOutputs()
            state.redo()
            projectEffects()
            assertNotNull(run.step(SimulationStep.EVENT)).join()
            assertTrue(state.document.components.isEmpty())
            assertTrue(state.simulation.vectorValues.isEmpty())
        } finally {
            controller?.close()
            scope.cancel()
            runtime.close()
            runtime.awaitClosed()
        }
    }

    @Test
    fun propertyEditsAndHistoryPublishTheirNewSimulationRevision() = runBlocking<Unit> {
        assertQueuedEditAndHistory { state, id ->
            state.selectComponent(id)
            state.updateSelectedAttribute("value", "0x0")
        }
    }

    @Test
    fun projectEditsAndHistoryPublishTheirNewSimulationRevision() = runBlocking<Unit> {
        assertQueuedEditAndHistory { state, id ->
            state.applyProject(state.project.copy(circuits = state.project.circuits.map { circuit ->
                circuit.copy(components = circuit.components.map { component ->
                    if (component.id == id) component.copy(attributes = component.attributes + ("value" to "0x0")) else component
                })
            }))
        }
    }

    private suspend fun assertQueuedEditAndHistory(edit: (EditorState, String) -> Unit) = coroutineScope {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val state = EditorState("""<project><lib name="0" desc="#Wiring"/><main name="Edit"/><circuit name="Edit">
            <comp lib="0" name="Constant" loc="(100,100)"><a name="label" val="SOURCE"/><a name="width" val="1"/><a name="value" val="0x1"/></comp>
            <comp lib="0" name="Pin" loc="(200,100)"><a name="label" val="OUT"/><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="1"/><a name="radix" val="2"/></comp>
            <wire from="(100,100)" to="(200,100)"/></circuit></project>""")
        val run = bindSimulation(scope, state)
        fun output() = state.document.components.single { it.label == "OUT" }.let { state.simulation.vectorValues[it.id]?.display }
        try {
            assertEquals("1", output())
            edit(state, state.document.components.single { it.label == "SOURCE" }.id)
            until { run.status.value.pendingSteps == 0 }
            assertEquals("0", output(), "The user's edit must publish its newly captured revision")
            state.undo()
            until { run.status.value.pendingSteps == 0 }
            assertEquals("1", output())
            state.redo()
            until { run.status.value.pendingSteps == 0 }
            assertEquals("0", output())
            assertEquals(0L, state.simulation.ticks)
            assertTrue(state.simulation.pendingEvents.isEmpty())
        } finally { run.close(); scope.cancel() }
    }

    private fun bindSimulation(scope: CoroutineScope, state: EditorState): SimulationRunController {
        val run = SimulationRunController(scope, state::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step,
                autoPropagating = SimulationProjectOptions.read(snapshot.project).autoPropagating).state },
            isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun,
            onFailure = { throw it })
        state.onSimulationInvalidated = run::invalidate
        state.onSimulationRequested = run::request
        return run
    }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(1) }
    }
}
