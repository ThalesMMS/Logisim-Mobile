package dev.logisim.mobile.core

import kotlin.test.*

class SocSimulatorTest {
    private val registry = ComponentCatalog.registry.withDefinitions(SocComponents.definitions())
    private val simulator = CircuitSimulator(registry = registry)
    private fun components(twoMasters: Boolean = false): List<CircuitComponent> = buildList {
        add(CircuitComponent("bus", SocComponents.BUS, GridPoint(0, 0), attributes = mapOf("SocBusIdentifier" to "shared")))
        add(CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 200), attributes = mapOf("SocBusSelection" to "shared")))
        // A slave before its masters must still expose their new values in the settled snapshot.
        add(CircuitComponent("pio", SocComponents.PIO, GridPoint(0, 300), attributes = mapOf("SocBusSelection" to "shared", "StartAddress" to "0x1000", "direction" to "outputonly", "width" to "8")))
        add(CircuitComponent("cpu", SocComponents.RV32, GridPoint(800, 0), attributes = mapOf("SocBusSelection" to "shared")))
        if (twoMasters) add(CircuitComponent("cpu2", SocComponents.RV32, GridPoint(1600, 0), attributes = mapOf("SocBusSelection" to "shared")))
    }
    private fun write(address: UInt, value: UInt) = SocTransactionEvent(SocBusRequest(address, value, read = false, write = true))

    @Test fun simulatorRoutesCpuWritesToEarlierSlavesAndSettlesWithoutRepeatingInstructions() {
        val document = CircuitDocument("main", components())
        var state = simulator.settle(document, events = mapOf("bus" to listOf(write(0u, 0x00500093u), write(4u, 0x00112023u))))
        state = simulator.settle(document, state, events = mapOf("cpu" to listOf(SocSetRegisterEvent(2, 0x1000u), SocStepEvent)))
        state = simulator.settle(document, state, events = mapOf("cpu" to listOf(SocStepEvent)))
        assertFalse(state.oscillating)
        assertEquals(8u, (state.componentStates.getValue("cpu") as SocRv32ComponentState).cpu.pc)
        assertEquals(5u, ((state.componentStates.getValue("pio") as SocDeviceComponentState).device as SocPioDevice).outputBits)
        assertEquals(LogicValue.HIGH, state.componentOutputs.getValue("pio").getValue("output0").bit(0))
        assertEquals(LogicValue.LOW, state.componentOutputs.getValue("pio").getValue("output1").bit(0))
        assertEquals(2, (state.componentStates.getValue("cpu") as SocRv32ComponentState).cpu.trace.size)
    }

    @Test fun manualBusEventsReportAccessWidthAndReadBeforeWritingInOneTraceEntry() {
        val document = CircuitDocument("main", components())
        val events = listOf(
            write(0u, 0x11223344u),
            SocTransactionEvent(SocBusRequest(1u, size = SocAccessSize.BYTE)),
            SocTransactionEvent(SocBusRequest(2u, 0xAABBu, SocAccessSize.HALF_WORD, read = true, write = true, atomic = true)),
            SocTransactionEvent(SocBusRequest(0u)),
            SocTransactionEvent(SocBusRequest(0xFFFFFFFFu, size = SocAccessSize.BYTE)),
        )
        val state = simulator.settle(document, events = mapOf("bus" to events))
        val trace = (state.componentStates.getValue("bus") as SocBusComponentState).bus.trace

        assertEquals(listOf(0u, 1u, 2u, 0u, 0xFFFFFFFFu), trace.map { it.request.address })
        assertEquals(SocAccessSize.BYTE, trace[1].request.size)
        assertEquals(0x33u, trace[1].reply.data)
        assertEquals(SocAccessSize.HALF_WORD, trace[2].request.size)
        assertTrue(trace[2].request.read && trace[2].request.write && trace[2].request.atomic)
        assertEquals(0x1122u, trace[2].reply.data)
        assertEquals(0xAABBu, trace[2].request.writeData)
        assertEquals(0xAABB3344u, trace[3].reply.data)
        assertEquals(SocBusError.NO_RESPONDER, trace[4].reply.error)
    }

    @Test fun twoMastersSerializeCompleteTransactionsInDocumentOrderAtTheSameTime() {
        val document = CircuitDocument("main", components(twoMasters = true))
        var state = simulator.settle(document, events = mapOf("bus" to listOf(write(0u, 0x00112023u)))) // sw x1,0(x2)
        state = simulator.settle(document, state, events = mapOf("bus" to listOf(SocClearTraceEvent),
            "cpu" to listOf(SocSetRegisterEvent(1, 11u), SocSetRegisterEvent(2, 0x1000u), SocStepEvent),
            "cpu2" to listOf(SocSetRegisterEvent(1, 22u), SocSetRegisterEvent(2, 0x1000u), SocStepEvent)))
        assertFalse(state.oscillating)
        assertEquals(22u, ((state.componentStates.getValue("pio") as SocDeviceComponentState).device as SocPioDevice).outputBits)
        val writes = (state.componentStates.getValue("bus") as SocBusComponentState).bus.trace.filter { it.request.write }
        assertEquals(listOf(11u, 22u), writes.map { it.request.writeData })
        assertEquals(listOf("cpu", "cpu2"), writes.map { it.request.initiator })
    }

    @Test fun subcircuitInstancesWithIdenticalBusIdentifiersKeepSeparateRam() {
        val child = CircuitDocument("soc", components())
        val main = CircuitDocument("main", listOf(
            CircuitComponent("left", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "soc"),
            CircuitComponent("right", ComponentKind.SUBCIRCUIT, GridPoint(1000, 0), circuitName = "soc")))
        val project = CircuitProject(listOf(main, child), "main")
        val state = simulator.settle(project, events = mapOf("left/bus" to listOf(write(0u, 0x12345678u)), "right/bus" to listOf(write(0u, 0xAABBCCDDu))))
        fun value(path: String) = ((state.circuitStates.getValue(path).componentStates.getValue("ram") as SocDeviceComponentState).device as SocMemoryDevice).storedWords[0u]
        assertFalse(state.oscillating)
        assertEquals(0x12345678u, value("left")); assertEquals(0xAABBCCDDu, value("right"))
    }

    @Test fun breakpointsDoNotLeakBetweenSubcircuitInstances() {
        val child = CircuitDocument("soc", components() +
            CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(800, 630)))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("left", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "soc"),
            CircuitComponent("right", ComponentKind.SUBCIRCUIT, GridPoint(1000, 0), circuitName = "soc")))
        val project = CircuitProject(listOf(main, child), "main")
        var state = simulator.settle(project, events = mapOf(
            "left/bus" to listOf(write(0u, 0x00500093u)),
            "right/bus" to listOf(write(0u, 0x00500093u)),
        ))
        state = simulator.settle(project, state, events = mapOf(
            "left/cpu" to listOf(SocSetBreakpointEvent(0u, true), SocSetRunningEvent(true)),
            "right/cpu" to listOf(SocSetRunningEvent(true)),
        ))
        state = simulator.settle(project, state, tick = true)
        fun cpu(path: String) = assertIs<SocRv32ComponentState>(state.circuitStates.getValue(path).componentStates.getValue("cpu"))
        assertEquals(0u, cpu("left").cpu.pc)
        assertEquals(0u, cpu("left").debugger.breakpointHit)
        assertEquals(0u, cpu("left").cpu.register(1))
        assertEquals(4u, cpu("right").cpu.pc)
        assertEquals(5u, cpu("right").cpu.register(1))
        assertTrue(cpu("right").debugger.breakpoints.isEmpty())
    }

    @Test fun codecImportsTheCompleteSocFixtureWithAnExplicitRegistry() {
        val xml = """<project source="5.1.0" version="1.0"><lib name="8" desc="#Soc"/><main name="main"/><circuit name="main">
            <comp lib="8" name="SocBus" loc="(20,20)"><a name="SocBusIdentifier" val="shared"/></comp>
            <comp lib="8" name="Socmem" loc="(20,250)"><a name="SocBusSelection" val="shared"/></comp>
            <comp lib="8" name="SocPio" loc="(20,350)"/><comp lib="8" name="SocVga" loc="(20,550)"/>
            <comp lib="8" name="SocDma" loc="(750,20)"/><comp lib="8" name="SocJtagUart" loc="(750,250)"/>
            <comp lib="8" name="Rv32im" loc="(1200,20)"/><comp lib="8" name="Nios2" loc="(1900,20)"/>
            </circuit></project>"""
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        assertEquals(SocComponents.kinds, project.mainCircuit.components.map { it.kind }.toSet())
        val state = simulator.settle(project)
        assertFalse(state.oscillating)
        assertTrue(state.diagnostics.none { "indisponível" in it })
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project, registry), registry)).project
        assertEquals(SocComponents.kinds, reopened.mainCircuit.components.map { it.kind }.toSet())
    }
}
