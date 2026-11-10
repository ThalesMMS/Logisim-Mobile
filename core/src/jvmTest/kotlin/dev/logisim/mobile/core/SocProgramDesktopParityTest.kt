package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocProgramDesktopParityTest {
    private val registry = ComponentCatalog.registry.withDefinitions(SocComponents.definitions())
    private val rv32Program = listOf(0x00000093u, 0x00100113u, 0x00600193u, 0x002080B3u,
        0x00110113u, 0xFE314CE3u, 0x00001237u, 0x00122023u, 0x10102023u, 0x10002283u)
    private val niosProgram = listOf(0x00800004u, 0x00C00044u, 0x01000184u, 0x10C5883Au,
        0x18C00044u, 0x193FFD16u, 0x01440004u, 0x28800015u, 0x00804015u, 0x01804017u)

    @Test fun rv32WholeProgramMatchesEveryDesktopInstructionThroughRuntime() = run("rv32", rv32Program, false)
    @Test fun niosWholeProgramMatchesEveryDesktopInstructionThroughRuntime() = run("nios2", niosProgram, false)
    @Test fun rv32WholeProgramMatchesEveryDesktopInstructionThroughSimulator() = run("rv32", rv32Program, true)
    @Test fun niosWholeProgramMatchesEveryDesktopInstructionThroughSimulator() = run("nios2", niosProgram, true)

    private fun fixture(name: String): String = listOf(File("fixtures/soc/$name"), File("../fixtures/soc/$name"))
        .first { it.isFile }.readText()

    private fun run(architecture: String, program: List<UInt>, throughSimulator: Boolean) {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(fixture("program-$architecture.circ"), registry)).project
        val document = project.mainCircuit
        val cpu = document.components.first { it.kind == SocComponents.RV32 || it.kind == SocComponents.NIOS2 }
        val bus = document.components.first { it.kind == SocComponents.BUS }
        val ram = document.components.first { it.kind == SocComponents.MEMORY }
        val pio = document.components.first { it.kind == SocComponents.PIO }
        val expected = fixture("program-$architecture-desktop.csv").lineSequence().drop(1)
            .filter { it.isNotBlank() && !it.startsWith("verified,") }.map { it.split(',') }.toList()
        assertEquals(22, expected.size)
        val initialization = program.mapIndexed { index, word -> write(index.toUInt() * 4u, word) } + write(0x100u, 0u)
        val simulator = CircuitSimulator(registry = registry)
        var simulation = if (throughSimulator) simulator.settle(document, events = mapOf(bus.id to initialization)) else null
        var states = simulation?.componentStates ?: document.components.associate { it.id to SocEvaluation.initialState(it) }
        fun evaluate(component: CircuitComponent, events: List<ComponentEvent>) {
            if (throughSimulator) {
                simulation = simulator.settle(document, requireNotNull(simulation), events = mapOf(component.id to events))
                assertFalse(requireNotNull(simulation).oscillating)
                states = requireNotNull(simulation).componentStates
            } else {
                val evaluated = SocEvaluation.evaluate(ComponentEvaluationContext(component, SocComponents.attributes(component),
                    emptyMap(), emptySet(), states.getValue(component.id), events = events), document.components, states)
                assertTrue(evaluated.evaluation.diagnostics.isEmpty(), evaluated.evaluation.diagnostics.joinToString())
                states = states + evaluated.peerStates + (component.id to evaluated.evaluation.state)
            }
        }
        if (!throughSimulator) evaluate(bus, initialization)
        for ((step, fields) in expected.withIndex()) {
            evaluate(cpu, listOf(SocStepEvent))
            val expectedPc = fields[2].toUInt(16)
            val expectedSum = fields[3].toUInt(16)
            val expectedCounter = fields[4].toUInt(16)
            val expectedLoaded = fields[5].toUInt(16)
            val context = "$architecture, instruction ${step + 1}, simulator=$throughSimulator"
            when (val state = states.getValue(cpu.id)) {
                is SocRv32ComponentState -> {
                    assertEquals(expectedPc, state.cpu.pc, context)
                    assertEquals(expectedSum, state.cpu.register(1), context)
                    assertEquals(expectedCounter, state.cpu.register(2), context)
                    assertEquals(expectedLoaded, state.cpu.register(5), context)
                    assertEquals((step + 1).toLong(), state.cpu.instructions, context)
                    assertTrue(state.cpu.stopped, context)
                }
                is SocNios2ComponentState -> {
                    assertEquals(expectedPc, state.cpu.pc, context)
                    assertEquals(expectedSum, state.cpu.register(2), context)
                    assertEquals(expectedCounter, state.cpu.register(3), context)
                    assertEquals(expectedLoaded, state.cpu.register(6), context)
                    assertEquals((step + 1).toLong(), state.cpu.instructions, context)
                    assertTrue(state.cpu.stopped, context)
                }
                else -> error("Missing processor state.")
            }
            val device = assertIs<SocPioDevice>(assertIs<SocDeviceComponentState>(states.getValue(pio.id)).device)
            assertEquals(fields[6].toUInt(16), device.outputBits, context)
            val memory = assertIs<SocMemoryDevice>(assertIs<SocDeviceComponentState>(states.getValue(ram.id)).device)
            assertEquals(fields[7].toUInt(16), memory.storedWords[0x100u], context)
            if (throughSimulator) for (bit in 0 until 8) {
                val output = simulation!!.componentOutputs.getValue(pio.id).getValue("output$bit")
                assertEquals(if (device.outputBits and (1u shl bit) != 0u) LogicValue.HIGH else LogicValue.LOW, output.bit(0), context)
            }
        }
        val writes = assertIs<SocBusComponentState>(states.getValue(bus.id)).bus.trace.filter {
            it.request.initiator == cpu.id && it.request.write
        }
        assertEquals(listOf(0x1000u, 0x100u), writes.map { it.request.address })
        assertEquals(listOf(15u, 15u), writes.map { it.request.writeData })
    }

    private fun write(address: UInt, value: UInt): ComponentEvent =
        SocTransactionEvent(SocBusRequest(address, value, read = false, write = true, hidden = true))
}
