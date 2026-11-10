package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LegacySequentialImportTest {
    private val registry = ComponentCatalog.registry

    @Test
    fun original7400LibraryTerminalsKeepTheirWireConnectionsAfterReopen() {
        // Component placements and attached wires from the 74161 and 7474 circuits in 7400-lib.circ.
        val original = read("""
            <project source="2.7.1" version="1.0">
              <lib name="4" desc="#Memory"/>
              <circuit name="74161">
                <comp lib="4" name="Counter" loc="(240,210)">
                  <a name="width" val="4"/><a name="max" val="0xf"/>
                </comp>
                <wire from="(180,220)" to="(210,220)"/>
                <wire from="(190,210)" to="(210,210)"/>
                <wire from="(200,200)" to="(210,200)"/>
                <wire from="(220,230)" to="(220,250)"/>
                <wire from="(230,230)" to="(230,240)"/>
                <wire from="(240,210)" to="(260,210)"/>
                <wire from="(240,220)" to="(260,220)"/>
              </circuit>
              <circuit name="7474">
                <comp lib="4" name="D Flip-Flop" loc="(150,100)"/>
                <wire from="(120,130)" to="(120,200)"/>
                <wire from="(140,130)" to="(140,140)"/>
                <wire from="(150,100)" to="(170,100)"/>
                <wire from="(150,120)" to="(160,120)"/>
                <wire from="(80,120)" to="(110,120)"/>
                <wire from="(90,100)" to="(110,100)"/>
              </circuit>
            </project>
        """.trimIndent())

        val counterWired = mapOf("data" to GridPoint(210, 210), "clock" to GridPoint(220, 230),
            "load" to GridPoint(210, 200), "clear" to GridPoint(230, 230), "enable" to GridPoint(210, 220),
            "q" to GridPoint(240, 210), "carry" to GridPoint(240, 220))
        val flipFlopWired = mapOf("d" to GridPoint(110, 120), "clock" to GridPoint(110, 100),
            "clear" to GridPoint(140, 130), "preset" to GridPoint(120, 130),
            "q" to GridPoint(150, 100), "nq" to GridPoint(150, 120))

        val saved = CircCodec.writeProject(original)
        assertFalse("logisim-mobile-original-sequential" in saved)
        assertFalse("name=\"appearance\"" in saved)
        val bundled = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(CircBundleCodec.create(original))).resolved.project
        for (project in listOf(original, read(saved), bundled)) {
            val counter = project.circuit("74161")!!
            val flipFlop = project.circuit("7474")!!
            assertEquals(counterWired, wiredPorts(counter))
            assertEquals(counterWired, ports(counter))
            assertEquals(flipFlopWired, wiredPorts(flipFlop))
            assertEquals(flipFlopWired + ("enable" to GridPoint(130, 130)), ports(flipFlop))
        }
    }

    @Test
    fun evolutionSequentialLayoutsAndExplicitAppearancesRetainTheirPorts() {
        val evolution = read("""
            <project source="5.1.0-dev"><lib name="4" desc="#Memory"/>
              <circuit name="counter"><comp lib="4" name="Counter" loc="(240,210)"><a name="width" val="4"/></comp></circuit>
              <circuit name="classic"><comp lib="4" name="D Flip-Flop" loc="(150,100)"><a name="appearance" val="classic"/></comp></circuit>
            </project>
        """.trimIndent())
        assertEquals(GridPoint(430, 320), ports(evolution.circuit("counter")!!)["q"])
        assertEquals(GridPoint(240, 260), ports(evolution.circuit("counter")!!)["up"])
        assertEquals(GridPoint(150, 100), ports(evolution.circuit("classic")!!)["q"])
        assertEquals(6, ports(evolution.circuit("classic")!!).size)
    }

    @Test
    fun componentsRetainTheirTerminalsWhenCopiedAcrossFileVersions() {
        fun counter(version: String, appearance: String = "") = read("""
            <project source="$version"><lib name="4" desc="#Memory"/>
              <circuit name="main"><comp lib="4" name="Counter" loc="(240,210)"><a name="width" val="4"/>$appearance</comp></circuit>
            </project>
        """.trimIndent())
        val legacy = counter("2.7.1")
        val evolution = counter("5.1.0-dev")
        val explicitEvolution = counter("2.7.1", """<a name="appearance" val="logisim_evolution"/>""")
        val explicitClassic = counter("5.1.0-dev", """<a name="appearance" val="classic"/>""")

        for ((source, destination) in listOf(legacy to evolution, evolution to legacy,
            explicitEvolution to evolution, explicitClassic to legacy)) {
            val origin = CircuitEditor(source.mainCircuit)
            val target = CircuitEditor(destination.mainCircuit.copy(components = emptyList()))
            val originalComponent = source.mainCircuit.components.single()
            val fragment = origin.copy(setOf(originalComponent.id))
            target.paste(fragment, GridPoint(0, 0))
            val saved = CircCodec.writeProject(destination.withCircuit(target.document))
            val reopened = read(saved)
            assertEquals(ports(source.mainCircuit), ports(reopened.mainCircuit))
            assertEquals(originalComponent.isOriginalSequential, reopened.mainCircuit.components.single().isOriginalSequential)
        }
    }

    @Test
    fun flipFlopUsesTheRestoredEnableConnection() {
        val circuit = read("""
            <project source="2.7.1">
              <lib name="0" desc="#Wiring"/><lib name="4" desc="#Memory"/>
              <circuit name="main">
                <comp lib="4" name="D Flip-Flop" loc="(150,100)"/>
                <comp lib="0" name="Constant" loc="(80,120)"/>
                <comp lib="0" name="Pin" loc="(90,100)"><a name="label" val="CLK"/></comp>
                <comp lib="0" name="Pin" loc="(130,150)"><a name="label" val="EN"/></comp>
                <wire from="(80,120)" to="(110,120)"/>
                <wire from="(90,100)" to="(110,100)"/>
                <wire from="(130,130)" to="(130,150)"/>
              </circuit>
            </project>
        """.trimIndent()).mainCircuit
        val clock = circuit.components.single { it.label == "CLK" }.id
        val enable = circuit.components.single { it.label == "EN" }.id
        val output = GridPoint(150, 100)
        val simulator = CircuitSimulator()
        var state = simulator.settle(circuit, inputs = mapOf(clock to LogicValue.LOW, enable to LogicValue.LOW))
        state = simulator.settle(circuit, state, inputs = mapOf(clock to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, state.portValues[output])
        state = simulator.settle(circuit, state, inputs = mapOf(clock to LogicValue.LOW, enable to LogicValue.HIGH))
        state = simulator.settle(circuit, state, inputs = mapOf(clock to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, state.portValues[output])
    }

    @Test
    fun originalRandomReplaysItsFixedSeedSequenceAfterReopen() {
        val original = originalRandomProject(seed = 123, width = 8)

        for (project in listOf(original, read(CircCodec.writeProject(original)))) {
            val circuit = project.mainCircuit
            val clock = circuit.components.single { it.label == "CLK" }.id
            val enable = circuit.components.single { it.label == "EN" }.id
            val reset = circuit.components.single { it.label == "RST" }.id
            val simulator = CircuitSimulator()
            var state = simulator.settle(circuit, inputs = mapOf(
                clock to LogicValue.LOW, enable to LogicValue.HIGH, reset to LogicValue.LOW,
            ))
            fun output() = state.portVectors.getValue(GridPoint(500, 300)).toULongOrNull()
            fun set(id: String, value: LogicValue): ULong? {
                state = simulator.settle(circuit, state, inputs = mapOf(id to value))
                return output()
            }
            assertEquals(123uL, output())
            assertEquals(43uL, set(clock, LogicValue.HIGH))
            assertEquals(43uL, set(clock, LogicValue.LOW))
            assertEquals(46uL, set(clock, LogicValue.HIGH))
            assertEquals(123uL, set(reset, LogicValue.HIGH))
            assertEquals(123uL, set(clock, LogicValue.LOW))
            assertEquals(123uL, set(clock, LogicValue.HIGH))
            assertEquals(123uL, set(reset, LogicValue.LOW))
            assertEquals(123uL, set(enable, LogicValue.LOW))
            assertEquals(123uL, set(clock, LogicValue.LOW))
            assertEquals(123uL, set(clock, LogicValue.HIGH))
            assertEquals(123uL, set(enable, LogicValue.HIGH))
            assertEquals(123uL, set(clock, LogicValue.LOW))
            assertEquals(43uL, set(clock, LogicValue.HIGH))
        }
    }

    @Test
    fun originalRandomWithDefaultSeedUsesLegacyLcgAndReseedsOncePerReset() {
        val original = originalRandomProject(seed = 0, width = 32)
        for (project in listOf(original, read(CircCodec.writeProject(original)))) {
            val circuit = project.mainCircuit
            val clock = circuit.components.single { it.label == "CLK" }.id
            val enable = circuit.components.single { it.label == "EN" }.id
            val reset = circuit.components.single { it.label == "RST" }.id
            val options = mapOf("randomSeed" to "123")
            val simulator = CircuitSimulator()
            var state = simulator.settle(circuit, inputs = mapOf(
                clock to LogicValue.LOW, enable to LogicValue.HIGH, reset to LogicValue.LOW,
            ), options = options)
            fun output() = state.portVectors.getValue(GridPoint(500, 300)).toULongOrNull()
            fun set(id: String, value: LogicValue): ULong? {
                state = simulator.settle(circuit, state, inputs = mapOf(id to value), options = options)
                return output()
            }

            assertEquals(123uL, output())
            assertEquals(0x2d21bd2buL, set(clock, LogicValue.HIGH))
            assertEquals(0xdeece6e8uL, set(reset, LogicValue.HIGH))
            assertEquals(0xdeece6e8uL, set(clock, LogicValue.LOW))
            assertEquals(0xdeece6e8uL, set(clock, LogicValue.HIGH))
            assertEquals(0xdeece6e8uL, set(reset, LogicValue.LOW))
            assertEquals(0xdeece6e8uL, set(clock, LogicValue.LOW))
            assertEquals(0xdf2d032cuL, set(clock, LogicValue.HIGH))
            assertEquals(123uL, set(reset, LogicValue.HIGH))
        }
    }

    private fun originalRandomProject(seed: Int, width: Int): CircuitProject = read("""
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="1" desc="#Memory"/>
              <main name="main"/><circuit name="main">
                <comp lib="1" name="Random" loc="(400,300)"><a name="seed" val="$seed"/><a name="width" val="$width"/></comp>
                <comp lib="0" name="Pin" loc="(200,290)"><a name="label" val="CLK"/></comp>
                <comp lib="0" name="Pin" loc="(200,310)"><a name="label" val="EN"/></comp>
                <comp lib="0" name="Pin" loc="(380,400)"><a name="label" val="RST"/><a name="facing" val="north"/></comp>
                <comp lib="0" name="Pin" loc="(500,300)"><a name="width" val="$width"/><a name="facing" val="west"/><a name="output" val="true"/></comp>
                <wire from="(200,290)" to="(370,290)"/><wire from="(200,310)" to="(370,310)"/>
                <wire from="(380,320)" to="(380,400)"/><wire from="(400,300)" to="(500,300)"/>
              </circuit>
            </project>
        """.trimIndent())

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun ports(circuit: CircuitDocument): Map<String, GridPoint> =
        registry.layout(circuit.components.single()).ports.associate { it.name to it.position }

    private fun wiredPorts(circuit: CircuitDocument): Map<String, GridPoint> =
        ports(circuit).filterValues { position -> circuit.wires.any { it.contains(position) } }
}
