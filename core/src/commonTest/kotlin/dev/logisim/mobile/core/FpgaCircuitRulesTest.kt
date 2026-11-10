package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FpgaCircuitRulesTest {
    @Test
    fun findsAnOpenGateInputAndAGatedClockBeforeBoardMapping() {
        val flip = CircuitComponent("flip", ComponentKind.D_FLIP_FLOP, GridPoint(300, 200))
        val ports = flip.layout().ports.associateBy { it.name }
        val gate = CircuitComponent("gate", ComponentKind.AND, ports.getValue("clock").position)
        val connectedInput = gate.layout().inputs.first()
        val openInput = gate.layout().inputs.last()
        val document = CircuitDocument("main", listOf(flip, gate,
            CircuitComponent("clock", ComponentKind.CLOCK, connectedInput.position),
            CircuitComponent("data", ComponentKind.INPUT, ports.getValue("d").position),
            CircuitComponent("result", ComponentKind.OUTPUT, ports.getValue("q").position)))

        val review = FpgaCircuitRules.review(CircuitProject(listOf(document)), HdlLanguage.VERILOG)

        val open = review.diagnostics.single {
            it.notice.key == "fpga_core_terminal_open" && it.target.componentIds == setOf(gate.id)
        }
        assertEquals(setOf(DiagnosticTerminalRef(gate.id, openInput.name)), open.target.terminals)
        val gated = review.diagnostics.single { it.notice.key == "fpga_core_clock_gated" }
        assertEquals(FpgaDiagnosticSeverity.WARNING, gated.severity)
        assertEquals(document.id, gated.target.circuitId)
        assertEquals(setOf(flip.id, gate.id), gated.target.componentIds)
        assertEquals(mapOf("component" to flip.id, "source" to gate.id), gated.notice.arguments)
        assertNotNull(review.design, review.hdlDiagnostics.toString())
    }

    @Test
    fun keepsElectricalShortsSeparateFromPinMappingAndSelectsBothDrivers() {
        val at = GridPoint(120, 100)
        val end = GridPoint(180, 100)
        val wire = WireSegment(at, end)
        val document = CircuitDocument("main", listOf(
            CircuitComponent("sourceA", ComponentKind.INPUT, at),
            CircuitComponent("sourceB", ComponentKind.INPUT, end),
            CircuitComponent("result", ComponentKind.OUTPUT, end)), listOf(wire))
        val review = FpgaCircuitRules.review(CircuitProject(listOf(document)), HdlLanguage.VERILOG)
        val short = review.diagnostics.single { it.notice.key == "fpga_core_circuit_short" }

        assertEquals(FpgaDiagnosticSeverity.ERROR, short.severity)
        assertEquals(setOf("sourceA", "sourceB"), short.target.componentIds)
        assertEquals(setOf(wire), short.target.wires)
        assertTrue(review.hdlDiagnostics.any { it.code == "HDL_MULTIPLE_DRIVERS" })
        assertNull(review.design)
    }

    @Test
    fun doesNotCountTheDedicatedClockAsMappableIo() {
        val clock = CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 100))
        val circuit = CircuitDocument("clock_only", listOf(clock))
        val review = FpgaCircuitRules.review(CircuitProject(listOf(circuit)), HdlLanguage.VERILOG)
        val diagnostic = review.diagnostics.single { it.notice.key == "fpga_core_no_circuit_io" }

        assertEquals(FpgaDiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(circuit.id, diagnostic.target.circuitId)
        assertNull(review.design)
    }

    @Test
    fun reportsAnOpenOutputAsACircuitWarningRatherThanAnInputOrMappingError() {
        val output = CircuitComponent("result", ComponentKind.OUTPUT, GridPoint(150, 100))
        val circuit = CircuitDocument("open_output", listOf(output))
        val review = FpgaCircuitRules.review(CircuitProject(listOf(circuit)), HdlLanguage.VERILOG)
        val diagnostic = review.diagnostics.single()

        assertEquals("fpga_core_circuit_output_open", diagnostic.notice.key)
        assertEquals(FpgaDiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals(setOf(output.id), diagnostic.target.componentIds)
        assertTrue(review.hdlDiagnostics.any { it.code == "HDL_UNDRIVEN_OUTPUT" })
    }

    @Test
    fun tracesDedicatedAndPossiblyGatedClocksToSeparateChildOccurrences() {
        val fixture = HdlFixtureCases.hierarchy().project
        val child = fixture.circuit("child")!!
        val flop = child.components.single { it.kind == ComponentKind.D_FLIP_FLOP }
        val childClock = flop.layout().inputs.single { it.name == "clock" }
        val childPin = child.components.single { it.kind == ComponentKind.INPUT && it.position == childClock.position }
        val directId = "0_${childPin.id}"
        val main = fixture.mainCircuit.copy(components = fixture.mainCircuit.components.map {
            if (it.id == directId) it.copy(kind = ComponentKind.CLOCK) else it
        })
        val project = fixture.withCircuit(main)
        val diagnostics = FpgaCircuitRules.review(project, HdlLanguage.VERILOG).diagnostics

        val possible = diagnostics.single { it.notice.key == "fpga_core_clock_external" }
        assertEquals(child.id, possible.target.circuitId)
        assertEquals(listOf("instance1"), possible.target.instancePath)
        assertEquals(setOf(flop.id), possible.target.componentIds)
        assertEquals(setOf("instance0", "instance1"), diagnostics.filter { it.notice.key == "fpga_core_clock_mixed_instances" }
            .flatMap { it.target.componentIds }.toSet())
    }
}
