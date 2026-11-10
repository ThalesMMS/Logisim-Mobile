package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WiringIntegrationTest {
    private val registry = ComponentCatalog.registry.withDefinitions(WiringLibrary.definitions())
    private val simulator = CircuitSimulator(registry = registry)
    private fun component(id: String, kind: ComponentKind, point: GridPoint, attrs: Map<String, String> = emptyMap(), label: String = "") =
        registry.create(kind, id, point, attrs, label)

    @Test
    fun inferredProbeAndWeakPullDoNotCreateWidthConflicts() {
        val position = GridPoint(100, 100)
        val document = CircuitDocument(components = listOf(
            component("pull", WiringLibrary.PullResistor, position, mapOf("pull" to "1")),
            component("probe", ComponentKind.PROBE, position),
            component("out", ComponentKind.OUTPUT, position, mapOf("width" to "8"))))
        val state = simulator.settle(document)
        assertEquals(255uL, state.portVectors.getValue(position).toULongOrNull())
        assertEquals(8, state.portVectors.getValue(position).width)
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.toString())
    }

    @Test
    fun weakPullFillsOnlyFloatingBitsAndStrongDriveWinsOverConflictingPulls() {
        val position = GridPoint(100, 100)
        val document = CircuitDocument(components = listOf(
            component("up", WiringLibrary.PullResistor, position, mapOf("pull" to "1")),
            component("input", ComponentKind.INPUT, position, mapOf("width" to "4", "behavior" to "tristate")),
            component("out", ComponentKind.OUTPUT, position, mapOf("width" to "4"))))
        val mixed = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.UNKNOWN, LogicValue.HIGH, LogicValue.ERROR))
        val state = simulator.settle(document, vectorInputs = mapOf("input" to mixed))
        assertEquals("E110", state.portVectors.getValue(position).display)
        val conflicting = document.copy(components = document.components +
            component("down", WiringLibrary.PullResistor, position))
        val driven = simulator.settle(conflicting, vectorInputs = mapOf("input" to LogicVector.low(4)))
        assertEquals("0000", driven.portVectors.getValue(position).display)
        val floating = simulator.settle(conflicting)
        assertEquals("EEEE", floating.portVectors.getValue(position).display)
    }

    @Test
    fun pinPullTransformsParentDriveAndTristateRetainsAnUnknownInitialValue() {
        val position = GridPoint(100, 100)
        val tristate = CircuitDocument(components = listOf(component("input", ComponentKind.INPUT, position,
            mapOf("width" to "4", "behavior" to "tristate"))))
        assertEquals("????", simulator.settle(tristate).vectorValues.getValue("input").display)
        val pulled = tristate.copy(components = listOf(component("input", ComponentKind.INPUT, position,
            mapOf("width" to "4", "behavior" to "pullup"))))
        assertEquals("1111", simulator.settle(pulled, vectorInputs = mapOf("input" to LogicVector.unknown(4)))
            .vectorValues.getValue("input").display)
    }

    @Test
    fun porChangesWithoutAnyClockComponentAndCanBeRearmed() {
        val document = CircuitDocument(components = listOf(component("por", WiringLibrary.PowerOnReset, GridPoint(100, 100))))
        val initial = simulator.settle(document, elapsedTimeMillis = 50)
        assertEquals(LogicValue.HIGH, initial.componentValues["por"])
        assertEquals(2050L, initial.nextWakeupMillis)
        val before = simulator.settle(document, initial, elapsedTimeMillis = 2049)
        assertEquals(LogicValue.HIGH, before.componentValues["por"])
        val elapsed = simulator.settle(document, before, elapsedTimeMillis = 2050)
        assertEquals(LogicValue.LOW, elapsed.componentValues["por"])
        assertNull(elapsed.nextWakeupMillis)
        val rearmed = simulator.settle(document, elapsed, elapsedTimeMillis = 3000,
            events = mapOf("por" to listOf(WiringLibrary.PowerOnResetTriggered)))
        assertEquals(LogicValue.HIGH, rearmed.componentValues["por"])
        assertEquals(5000L, rearmed.nextWakeupMillis)
        assertEquals(0L, rearmed.ticks)
    }

    @Test
    fun asymmetricClockMatchesTheDesktopPhaseConvention() {
        val document = CircuitDocument(components = listOf(component("clock", ComponentKind.CLOCK, GridPoint(100, 100),
            mapOf("highDuration" to "2", "lowDuration" to "3"))))
        var state = simulator.settle(document)
        val levels = (0 until 10).map {
            val value = state.componentValues.getValue("clock")
            state = simulator.settle(document, state, tick = true)
            value.display
        }
        assertEquals(listOf("0", "0", "0", "1", "1", "0", "0", "0", "1", "1"), levels)
    }

    @Test
    fun importedClockDefaultsToClassicWhileNewClocksUseNewPins() {
        val xml = """<project source="3.8.0" version="1.0"><lib name="0" desc="#Wiring"/><main name="main"/><circuit name="main"><comp lib="0" name="Clock" loc="(100,100)"/></circuit></project>"""
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project.mainCircuit.components.single()
        assertEquals("classic", imported.attributes["appearance"])
        assertEquals("NewPins", component("new", ComponentKind.CLOCK, GridPoint(100, 100)).attributes["appearance"])
    }

    @Test
    fun transistorKeepsItsSourceBehaviorAcrossProjectVersions() {
        val input = LogicVector.fromBits(listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR))
        for ((type, active, modernOutput) in listOf(
            Triple("n", LogicVector.high(1), "E??0"),
            Triple("p", LogicVector.low(1), "E?1?"),
        )) {
            fun project(version: String): CircuitProject {
                val xml = """
                    <project source="$version" version="1.0">
                      <lib name="0" desc="#Wiring"/>
                      <main name="main"/>
                      <circuit name="main">
                        <comp lib="0" name="Pin" loc="(160,100)" logisim-mobile-id="data"><a name="width" val="4"/></comp>
                        <comp lib="0" name="Pin" loc="(180,80)" logisim-mobile-id="enable"/>
                        <comp lib="0" name="Transistor" loc="(200,100)"><a name="type" val="$type"/><a name="width" val="4"/></comp>
                        <comp lib="0" name="Pin" loc="(200,100)" logisim-mobile-id="result">
                          <a name="width" val="4"/><a name="output" val="true"/>
                        </comp>
                      </circuit>
                    </project>
                """.trimIndent()
                return assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
            }
            fun checkOutput(project: CircuitProject, enabledOutput: String) {
                val inactive = if (active.bit(0) == LogicValue.HIGH) LogicVector.low(1) else LogicVector.high(1)
                for ((control, expected) in listOf(
                    active to enabledOutput, inactive to "????", LogicVector.unknown(1) to "E?EE",
                    LogicVector.error(1) to "E?EE",
                )) {
                    val state = simulator.settle(project, vectorInputs = mapOf("data" to input, "enable" to control))
                    assertEquals(expected, state.vectorValues["result"]?.display, "$type, control ${control.display}")
                }
            }

            val original = project("2.7.1")
            val modern = project("5.1.0-dev")
            for ((inputProject, enabledOutput) in listOf(original to "E?10", modern to modernOutput)) {
                checkOutput(inputProject, enabledOutput)
                for (destination in listOf("2.7.1", "5.1.0-dev")) {
                    val saved = CircCodec.writeProject(inputProject.copy(sourceVersion = destination), registry)
                    val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved, registry)).project
                    checkOutput(reopened, enabledOutput)
                }
            }
        }
    }

    @Test
    fun splitterDistributesBusesAndTunnelJoinsRemoteBranches() {
        val splitter = component("split", ComponentKind.SPLITTER, GridPoint(100, 100), mapOf("incoming" to "8", "fanout" to "2"))
        val branches = registry.layout(splitter).ports.drop(1)
        val remote = GridPoint(300, 100)
        val document = CircuitDocument(components = listOf(
            splitter,
            component("input", ComponentKind.INPUT, splitter.position, mapOf("width" to "8")),
            component("low", ComponentKind.OUTPUT, branches[0].position, mapOf("width" to "4")),
            component("localTunnel", ComponentKind.TUNNEL, branches[1].position, mapOf("width" to "4"), "upper"),
            component("remoteTunnel", ComponentKind.TUNNEL, remote, mapOf("width" to "4"), "upper"),
            component("high", ComponentKind.OUTPUT, remote, mapOf("width" to "4"))))
        val state = simulator.settle(document, vectorInputs = mapOf("input" to LogicVector.of(8, 0xabuL)))
        assertEquals(11uL, state.vectorValues.getValue("low").toULongOrNull())
        assertEquals(10uL, state.vectorValues.getValue("high").toULongOrNull())
        assertEquals(10uL, state.vectorValues.getValue("remoteTunnel").toULongOrNull())
        assertEquals(0xabuL, state.vectorValues.getValue("split").toULongOrNull())
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.toString())
        assertFalse(state.oscillating)
    }

    @Test
    fun allWiringToolsRoundTripWithTheirDeclaredGeometryAndAttributes() {
        val circuits = WiringLibrary.definitions().mapIndexed { index, definition ->
            val instance = registry.create(definition.kind, "device", GridPoint(100, 100),
                mapOf("mapinfo" to "preserved-$index"), "label_$index")
            CircuitDocument("case_$index", listOf(instance))
        }
        val xml = CircCodec.writeProject(CircuitProject(circuits), registry)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        assertEquals(circuits.size, reopened.circuits.size)
        for ((before, after) in circuits.zip(reopened.circuits)) {
            val a = before.components.single()
            val b = after.components.single()
            assertEquals(a.kind, b.kind)
            assertEquals(a.label, b.label)
            assertEquals(a.attributes["mapinfo"], b.attributes["mapinfo"])
            assertEquals(registry.layout(a), registry.layout(b))
        }
    }
}
