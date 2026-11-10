package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FpgaClockDesignRulesTest {
    private val board = FpgaBoardDefinition("Clock fixture", FpgaDevice(FpgaVendor.OPEN_FPGA, "ecp5", "85k", "CABGA381", "5"),
        FpgaClock("F1", 50_000_000, FpgaElectrical()), emptyList())
    private fun design(frequency: Long) = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
        listOf(FpgaSourceFile("user_top.v", "module user_top(input clock); endmodule")),
        listOf(FpgaDesignPort("clock", FpgaPinDirection.INPUT, clockFrequencyHz = frequency)))

    @Test fun invalidPhysicalClockRatesAreRejectedEvenWithoutAPhysicalBinding() {
        for (frequency in listOf(0L, -1L, 1_000_000_000_001L)) {
            val diagnostics = FpgaDesignRules.check(design(frequency), board, emptyMap(), FpgaFlow.OPEN_ECP5)
            assertTrue(diagnostics.any { it.code == "invalid-clock-frequency" }, frequency.toString())
        }
    }

    @Test fun aDeclaredPhysicalClockCannotBeReplacedByAConstant() {
        val diagnostics = FpgaDesignRules.check(design(50_000_000), board,
            mapOf(FpgaSignalBit("clock") to FpgaPinBinding.Constant(true)), FpgaFlow.OPEN_ECP5)
        assertTrue(diagnostics.any { it.code == "clock-source-required" })
    }

    @Test fun recordsAParentClockForTheDedicatedChildSinkAndKeepsExternalDiagnostics() {
        val fixture = HdlFixtureCases.hierarchy().project
        val child = fixture.circuit("child")!!
        val flop = child.components.single { it.kind == ComponentKind.D_FLIP_FLOP }
        val clockPoint = flop.layout().inputs.single { it.name == "clock" }.position
        val childInput = child.components.single { it.kind == ComponentKind.INPUT && it.position == clockPoint }
        val dedicatedId = "0_${childInput.id}"
        val unusedClock = CircuitComponent("unused_clock", ComponentKind.CLOCK, GridPoint(2000, 100))
        val main = fixture.mainCircuit.copy(components = fixture.mainCircuit.components.map {
            if (it.id == dedicatedId) it.copy(kind = ComponentKind.CLOCK) else it
        } + unusedClock)
        val unusedCircuit = CircuitDocument("unreferenced", listOf(unusedClock.copy(id = "unreachable_clock")))
        val analysis = FpgaCircuitRules.analyzeConnectivity(fixture.withCircuit(main).addCircuit(unusedCircuit))
        val dedicated = FpgaClockComponentRef(emptyList(), dedicatedId)

        assertEquals(setOf(dedicated, FpgaClockComponentRef(emptyList(), unusedClock.id)), analysis.dedicatedClocks)
        assertEquals(mapOf(
            FpgaClockSinkRef(listOf("instance0"), flop.id, "clock") to FpgaClockRoute(dedicated, FpgaClockUse.RISING_EDGE,
                listOf(FpgaClockBoundary(listOf("instance0"), childInput.id, 0, PortDirection.INPUT))),
        ), analysis.dedicatedClockRoutes)
        val external = analysis.diagnostics.single { it.notice.key == "fpga_core_clock_external" }
        assertEquals(listOf("instance1"), external.target.instancePath)
        assertEquals(setOf(DiagnosticTerminalRef(flop.id, "clock")), external.target.terminals)
    }

    @Test fun recordsChildClockOccurrencesForEachParentTrigger() {
        val childClock = CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 100))
        val childOutput = CircuitComponent("clock_out", ComponentKind.OUTPUT, childClock.position)
        val child = CircuitDocument("generator", listOf(childClock, childOutput))
        val triggers = listOf(
            "rising" to FpgaClockUse.RISING_EDGE,
            "falling" to FpgaClockUse.FALLING_EDGE,
            "high" to FpgaClockUse.HIGH_LEVEL,
            "low" to FpgaClockUse.LOW_LEVEL,
        )
        val sinks = triggers.mapIndexed { index, (trigger, _) ->
            CircuitComponent("sink_$trigger", ComponentKind.D_FLIP_FLOP, GridPoint(300 + index * 200, 200),
                attributes = mapOf("trigger" to trigger))
        }
        val instances = sinks.mapIndexed { index, sink ->
            CircuitComponent("generator_$index", ComponentKind.SUBCIRCUIT,
                sink.layout().inputs.single { it.name == "clock" }.position, circuitName = child.name,
                explicitPorts = listOf(ComponentPort(childOutput.id, GridPoint(0, 0), PortDirection.OUTPUT)))
        }
        val analysis = FpgaCircuitRules.analyzeConnectivity(CircuitProject(listOf(CircuitDocument("main", sinks + instances), child)))
        val sources = instances.map { FpgaClockComponentRef(listOf(it.id), childClock.id) }

        assertEquals(sources.toSet(), analysis.dedicatedClocks)
        assertEquals(sinks.mapIndexed { index, sink ->
            FpgaClockSinkRef(emptyList(), sink.id, "clock") to FpgaClockRoute(sources[index], triggers[index].second,
                listOf(FpgaClockBoundary(listOf(instances[index].id), childOutput.id, 0, PortDirection.OUTPUT)))
        }.toMap(), analysis.dedicatedClockRoutes)
    }
}
