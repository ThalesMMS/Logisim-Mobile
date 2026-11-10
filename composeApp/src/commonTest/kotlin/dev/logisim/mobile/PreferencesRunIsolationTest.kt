package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.SimulationProjectOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreferencesRunIsolationTest {
    @Test
    fun migratedAndRestoredUserDefaultsDoNotReplaceTheRunningProjectsOptions() = runBlocking<Unit> {
        val storage = MemoryPreferencesStorage("LOGISIM-EDITOR-PREFERENCES\n1\ntickFrequency=1.0\ngrid=false\n")
        val repository = EditorPreferencesRepository(storage)
        val migrated = repository.load()
        assertEquals(1, migrated.migratedFromVersion)
        val project = SimulationProjectOptions(propagationLimit = 700, tickFrequencyHz = 8.0).applyTo(
            CircuitProject(listOf(CircuitDocument(components = listOf(
                CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(20, 20)),
            )))),
        )
        val editor = EditorState(CircCodec.writeProject(project), defaultTickFrequencyHz = migrated.preferences.defaultTickFrequencyHz)
        val clockId = editor.document.components.single { it.kind == ComponentKind.CLOCK }.id
        val openedXml = editor.exportXml()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val waits = mutableListOf<Long>()
        val failures = mutableListOf<Throwable>()
        val controller = SimulationRunController(
            scope = scope,
            capture = editor::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step).state },
            isCurrent = editor::isCurrentRunSnapshot,
            publish = editor::publishRun,
            onFailure = { failures += it },
            workerDispatcher = Dispatchers.Unconfined,
            waitForNext = { waits += it; permits.receive() },
        )
        editor.onSimulationInvalidated = controller::invalidate
        try {
            // The host configures RUN from project options, never from the user default.
            controller.configureFrequency(SimulationProjectOptions.read(editor.project).tickFrequencyHz)
            assertTrue(controller.start())
            assertEquals(listOf(125L), waits)
            permits.send(Unit)
            yield()
            assertEquals(1L, editor.rootSimulation.ticks)
            assertEquals(LogicValue.HIGH, editor.rootSimulation.componentValues.getValue(clockId))

            assertIs<PreferencesSaveResult.Success>(repository.save(migrated.preferences.copy(
                defaultTickFrequencyHz = 16.0, gateShape = EditorGateShape.IEC,
            )))
            editor.defaultTickFrequencyHz = EditorPreferencesRepository(storage).load().preferences.defaultTickFrequencyHz
            assertEquals(16.0, editor.defaultTickFrequencyHz)
            assertEquals(SimulationRunPhase.RUNNING, controller.status.value.phase)
            assertEquals(8.0, controller.status.value.frequencyHz)
            assertEquals(openedXml, editor.exportXml())
            assertEquals(mapOf(
                "propagationLimit" to "700", "memoryStartupUnknown" to "false", "gateUndefined" to "ignore",
                "tickFrequencyHz" to "8.0", "autoPropagating" to "true",
            ), editor.captureRunSnapshot().options)
            permits.send(Unit)
            yield()
            assertEquals(2L, editor.rootSimulation.ticks)
            assertEquals(LogicValue.LOW, editor.rootSimulation.componentValues.getValue(clockId))
            assertEquals(listOf(125L, 125L, 125L), waits)
            assertEquals(openedXml, editor.exportXml())

            controller.pause()
            editor.newDocument()
            assertEquals(16.0, SimulationProjectOptions.read(editor.project).tickFrequencyHz)
            assertEquals(0L, editor.rootSimulation.ticks)
            val customizedNewXml = editor.exportXml()
            controller.configureFrequency(SimulationProjectOptions.read(editor.project).tickFrequencyHz)
            assertTrue(controller.start())
            assertEquals(63L, waits.last())
            permits.send(Unit)
            yield()
            assertEquals(1L, editor.rootSimulation.ticks)

            assertIs<PreferencesSaveResult.Success>(repository.restoreDefaults())
            val restored = EditorPreferencesRepository(storage).load().preferences
            editor.defaultTickFrequencyHz = restored.defaultTickFrequencyHz
            assertTrue(restored.showGrid)
            assertEquals(EditorGateShape.ANSI, restored.gateShape)
            assertEquals(1.0, editor.defaultTickFrequencyHz)
            assertEquals(16.0, controller.status.value.frequencyHz)
            assertEquals(16.0, SimulationProjectOptions.read(editor.project).tickFrequencyHz)
            assertEquals(customizedNewXml, editor.exportXml())
            permits.send(Unit)
            yield()
            assertEquals(2L, editor.rootSimulation.ticks)
            assertEquals(63L, waits.last())

            controller.pause()
            editor.newDocument()
            assertEquals(1.0, SimulationProjectOptions.read(editor.project).tickFrequencyHz)
            assertEquals(0L, editor.rootSimulation.ticks)
            controller.configureFrequency(SimulationProjectOptions.read(editor.project).tickFrequencyHz)
            assertTrue(controller.start())
            assertEquals(1000L, waits.last())
            permits.send(Unit)
            yield()
            assertEquals(1L, editor.rootSimulation.ticks)
            assertEquals(1.0, editor.captureRunSnapshot().options.getValue("tickFrequencyHz").toDouble())
            assertEquals(emptyList(), failures)
        } finally {
            controller.close()
            scope.cancel()
            permits.close()
        }
    }

    private class MemoryPreferencesStorage(private var serialized: String) : PreferencesStorage {
        override fun load(): String = serialized
        override fun save(serialized: String) { this.serialized = serialized }
    }
}
