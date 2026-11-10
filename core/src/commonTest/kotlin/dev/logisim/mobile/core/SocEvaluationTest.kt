package dev.logisim.mobile.core

import kotlin.test.*

class SocEvaluationTest {
    private class Rig(val components: List<CircuitComponent>) {
        var states = components.associate { it.id to SocEvaluation.initialState(it) }
        fun evaluate(id: String, events: List<ComponentEvent> = emptyList(), inputs: Map<String, LogicVector> = emptyMap()): ComponentEvaluation {
            val component = components.first { it.id == id }
            val result = SocEvaluation.evaluate(ComponentEvaluationContext(component, SocComponents.attributes(component), inputs, inputs.keys,
                states.getValue(id), events = events), components, states)
            states = states + result.peerStates + (id to result.evaluation.state)
            return result.evaluation
        }
        fun memory() = (states.getValue("ram") as SocDeviceComponentState).device as SocMemoryDevice
    }
    private fun components(cpuKind: ComponentKind = SocComponents.RV32) = listOf(
        CircuitComponent("bus", SocComponents.BUS, GridPoint(0, 0), attributes = mapOf("SocBusIdentifier" to "bus-id")),
        CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 200), attributes = mapOf("SocBusSelection" to "bus-id")),
        CircuitComponent("cpu", cpuKind, GridPoint(800, 0), attributes = mapOf("SocBusSelection" to "bus-id")),
        CircuitComponent("pio", SocComponents.PIO, GridPoint(0, 300), attributes = mapOf("SocBusSelection" to "bus-id", "StartAddress" to "0x1000", "direction" to "outputonly", "width" to "8")),
    )

    @Test fun rv32TransactionsUpdatePeersAndPreserveEarlierSnapshots() {
        val rig = Rig(components())
        rig.memory().load(0u, byteArrayOf(0x93.toByte(), 0, 0x50, 0, 0x23, 0x20, 0x11, 0)) // addi x1,zero,5; sw x1,0(x2)
        rig.evaluate("cpu", listOf(SocSetRegisterEvent(2, 0x1000u)))
        val previous = rig.states
        rig.evaluate("cpu", listOf(SocStepEvent))
        assertEquals(5u, (rig.states["cpu"] as SocRv32ComponentState).cpu.register(1))
        rig.evaluate("cpu", listOf(SocStepEvent))
        assertEquals(5u, ((rig.states["pio"] as SocDeviceComponentState).device as SocPioDevice).outputBits)
        assertEquals(0u, ((previous["pio"] as SocDeviceComponentState).device as SocPioDevice).outputBits)
        assertEquals(3, (rig.states["bus"] as SocBusComponentState).bus.trace.size)
    }

    @Test fun identicalBusIdsInSeparateInstancesCannotShareMemory() {
        val first = Rig(components()); val second = Rig(components())
        first.evaluate("bus", listOf(SocTransactionEvent(SocBusRequest(0u, 0x12345678u, read = false, write = true))))
        assertEquals(0x12345678u, first.memory().storedWords[0u])
        assertTrue(second.memory().storedWords.isEmpty())
    }

    @Test fun runClockStopAndStepHaveDistinctBehavior() {
        val rig = Rig(components())
        rig.memory().load(0u, byteArrayOf(0x93.toByte(), 0x80.toByte(), 0x10, 0, 0x93.toByte(), 0x80.toByte(), 0x10, 0))
        rig.evaluate("cpu", listOf(SocSetRegisterEvent(1, 0u)), mapOf("clock" to LogicVector.low(1)))
        rig.evaluate("cpu", inputs = mapOf("clock" to LogicVector.high(1)))
        assertEquals(0u, (rig.states["cpu"] as SocRv32ComponentState).cpu.pc)
        rig.evaluate("cpu", listOf(SocSetRunningEvent(true)), mapOf("clock" to LogicVector.low(1)))
        rig.evaluate("cpu", inputs = mapOf("clock" to LogicVector.high(1)))
        assertEquals(4u, (rig.states["cpu"] as SocRv32ComponentState).cpu.pc)
        rig.evaluate("cpu", listOf(SocSetRunningEvent(false)))
        rig.evaluate("cpu", listOf(SocStepEvent))
        assertEquals(2u, (rig.states["cpu"] as SocRv32ComponentState).cpu.register(1))
        assertTrue((rig.states["cpu"] as SocRv32ComponentState).cpu.stopped)
    }

    @Test fun uartSignalHandshakeConsumesAndPresentsOneCharacterPerClock() {
        val component = CircuitComponent("uart", SocComponents.UART, GridPoint(0, 0))
        val rig = Rig(listOf(component))
        val uart = (rig.states["uart"] as SocDeviceComponentState).device as SocJtagUartDevice
        uart.transact(SocBusRequest(0u, 65u, read = false, write = true))
        rig.evaluate("uart", inputs = mapOf("clock" to LogicVector.low(1), "reset" to LogicVector.low(1)))
        val output = rig.evaluate("uart", inputs = mapOf("clock" to LogicVector.high(1), "reset" to LogicVector.low(1),
            "available" to LogicVector.high(1), "dataIn" to LogicVector.of(7, 97u)))
        assertEquals(65uL, output.outputs.getValue("dataOut").toULongOrNull())
        assertEquals(LogicValue.HIGH, output.outputs.getValue("write").bit(0))
        assertEquals(LogicValue.HIGH, output.outputs.getValue("readEnable").bit(0))
        val after = (rig.states["uart"] as SocDeviceComponentState).device as SocJtagUartDevice
        assertEquals(listOf(97), after.readQueue)
    }
}
