package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocEvaluationReviewTest {
    private class Rig(cpuKind: ComponentKind = SocComponents.NIOS2) {
        val components = listOf(
            CircuitComponent("bus", SocComponents.BUS, GridPoint(0, 0), attributes = mapOf("SocBusIdentifier" to "bus-id")),
            CircuitComponent("ram", SocComponents.MEMORY, GridPoint(0, 200), attributes = mapOf("SocBusSelection" to "bus-id")),
            CircuitComponent("cpu", cpuKind, GridPoint(800, 0), attributes = mapOf("SocBusSelection" to "bus-id", "irqWidth" to "1")),
        )
        var states = components.associate { it.id to SocEvaluation.initialState(it) }
        fun evaluate(events: List<ComponentEvent> = emptyList(), vararg inputs: Pair<String, LogicVector>): ComponentEvaluation {
            val component = components.last()
            val values = mapOf(*inputs)
            val result = SocEvaluation.evaluate(ComponentEvaluationContext(component, SocComponents.attributes(component),
                values, values.keys, states.getValue("cpu"), events = events), components, states)
            states = states + result.peerStates + ("cpu" to result.evaluation.state)
            return result.evaluation
        }
        fun nios() = assertIs<SocNios2ComponentState>(states.getValue("cpu"))
        fun rv() = assertIs<SocRv32ComponentState>(states.getValue("cpu"))
        fun writeWord(address: UInt, value: UInt) {
            val memory = assertIs<SocMemoryDevice>(assertIs<SocDeviceComponentState>(states.getValue("ram")).device)
            memory.load(address, ByteArray(4) { (value shr (it * 8)).toByte() })
        }
    }

    @Test fun loadedElfEntryRemainsTheResetTargetForBothProcessors() {
        for ((kind, machine, instruction) in listOf(Triple(SocComponents.NIOS2, 113, 0x010001C4u),
            Triple(SocComponents.RV32, 243, 0x00700213u))) {
            val rig = Rig(kind)
            rig.evaluate(listOf(SocLoadExecutableEvent(elf(machine, listOf(instruction)))))
            val loadedPc = if (kind == SocComponents.NIOS2) rig.nios().cpu.pc else rig.rv().cpu.pc
            assertEquals(0x100u, loadedPc)
            rig.evaluate(listOf(SocStepEvent))
            val executedPc = if (kind == SocComponents.NIOS2) rig.nios().cpu.pc else rig.rv().cpu.pc
            assertEquals(0x104u, executedPc)
            rig.evaluate(inputs = arrayOf("reset" to LogicVector.high(1)))
            val resetPc = if (kind == SocComponents.NIOS2) rig.nios().cpu.pc else rig.rv().cpu.pc
            assertEquals(0x100u, resetPc, kind.name)
            val stopped = if (kind == SocComponents.NIOS2) rig.nios().cpu.stopped else rig.rv().cpu.stopped
            assertTrue(stopped)
        }
    }

    @Test fun loadedProgramBreakpointsStopBeforeFetchAndResumeOnlyOnceOnBothCpus() {
        for ((kind, machine, instruction, jump) in listOf(
            BreakpointProgram(SocComponents.NIOS2, 113, 0x010001C4u, 0x003FFF06u),
            BreakpointProgram(SocComponents.RV32, 243, 0x00700213u, 0x0000006Fu),
        )) {
            val rig = Rig(kind)
            fun pc() = if (kind == SocComponents.NIOS2) rig.nios().cpu.pc else rig.rv().cpu.pc
            fun stopped() = if (kind == SocComponents.NIOS2) rig.nios().cpu.stopped else rig.rv().cpu.stopped
            fun trace() = if (kind == SocComponents.NIOS2) rig.nios().cpu.trace else rig.rv().cpu.trace
            fun debugger() = if (kind == SocComponents.NIOS2) rig.nios().debugger else rig.rv().debugger
            fun clock(high: Boolean, vararg events: ComponentEvent) =
                rig.evaluate(events.toList(), "clock" to if (high) LogicVector.high(1) else LogicVector.low(1))

            clock(false, SocLoadExecutableEvent(elf(machine, listOf(instruction, jump))))
            val program = requireNotNull(debugger().program)
            assertEquals(2, program.size)
            assertEquals(0, program.indexOf(pc()))
            assertEquals(SocProgramWord(0x104u, jump), program[1])

            clock(false, SocSetBreakpointEvent(0x104u, true), SocSetRunningEvent(true))
            clock(true)
            assertEquals(0x104u, pc())
            assertFalse(stopped())
            clock(false)
            val beforeHit = trace()
            clock(true)
            assertTrue(stopped())
            assertEquals(0x104u, debugger().breakpointHit)
            assertEquals(beforeHit, trace())

            clock(false, SocSetRunningEvent(true))
            clock(true)
            assertFalse(stopped())
            assertEquals(0x104u, pc())
            assertEquals(2, trace().size)
            clock(false)
            clock(true)
            assertTrue(stopped())
            assertEquals(0x104u, debugger().breakpointHit)
            assertEquals(2, trace().size)

            clock(true, SocStepEvent)
            assertTrue(stopped())
            assertNull(debugger().breakpointHit)
            assertEquals(3, trace().size)
        }
    }

    private data class BreakpointProgram(val kind: ComponentKind, val machine: Int, val instruction: UInt, val jump: UInt)

    @Test fun niosPendingIrqsUpdateWithoutExecutingInstructionsAndDuringReset() {
        val rig = Rig()
        rig.evaluate(inputs = arrayOf("irq0" to LogicVector.high(1), "clock" to LogicVector.low(1)))
        assertEquals(1u, rig.nios().cpu.ipending)
        assertEquals(0u, rig.nios().cpu.pc)
        assertTrue(rig.nios().cpu.stopped)
        rig.evaluate(inputs = arrayOf("irq0" to LogicVector.high(1), "reset" to LogicVector.high(1)))
        assertEquals(1u, rig.nios().cpu.ipending)
        rig.evaluate(inputs = arrayOf("irq0" to LogicVector.low(1)))
        assertEquals(0u, rig.nios().cpu.ipending)
    }

    @Test fun niosSamplesNewIrqAfterTheInstructionOnTheSameClockEdge() {
        val rig = Rig()
        rig.writeWord(0u, 0x010001C4u) // movi r4,7
        rig.writeWord(0x14u, 0x01400244u) // movi r5,9
        rig.states = rig.states + ("cpu" to rig.nios().copy(cpu = rig.nios().cpu.copy(
            status = 0x00800001u, ienable = 1u)))
        rig.evaluate(listOf(SocSetRunningEvent(true)), "clock" to LogicVector.low(1), "irq0" to LogicVector.low(1))
        rig.evaluate(inputs = arrayOf("clock" to LogicVector.high(1), "irq0" to LogicVector.high(1)))
        assertEquals(4u, rig.nios().cpu.pc)
        assertEquals(7u, rig.nios().cpu.register(4))
        assertEquals(1u, rig.nios().cpu.ipending)
        assertEquals(0u, rig.nios().cpu.register(29))
        rig.evaluate(inputs = arrayOf("clock" to LogicVector.low(1), "irq0" to LogicVector.high(1)))
        rig.evaluate(inputs = arrayOf("clock" to LogicVector.high(1), "irq0" to LogicVector.high(1)))
        assertEquals(0x18u, rig.nios().cpu.pc)
        assertEquals(4u, rig.nios().cpu.register(29))
        assertEquals(9u, rig.nios().cpu.register(5))
    }

    @Test fun rvMachinePendingBitUpdatesWhileCpuIsStopped() {
        val rig = Rig(SocComponents.RV32)
        val plic = rig.rv().plic
        plic.transact(SocBusRequest(0x0C002000u, 2u, read = false, write = true))
        rig.evaluate(inputs = arrayOf("irq0" to LogicVector.high(1), "clock" to LogicVector.low(1)))
        assertTrue(rig.rv().cpu.stopped)
        assertEquals(0x800u, rig.rv().cpu.csrs.getValue(0x344) and 0x800u)
        assertEquals(0u, rig.rv().cpu.pc)
        rig.evaluate(inputs = arrayOf("irq0" to LogicVector.low(1)))
        assertEquals(0u, rig.rv().cpu.csrs.getValue(0x344) and 0x800u)
    }

    @Test fun rvResetRetainsPlicEnableAndInServiceSources() {
        val rig = Rig(SocComponents.RV32)
        val initial = rig.rv().plic
        initial.transact(SocBusRequest(0x0C002000u, 2u, read = false, write = true))
        initial.setIrqLines(1u)
        assertEquals(1u, initial.transact(SocBusRequest(0x0C200004u)).data)
        rig.evaluate(inputs = arrayOf("reset" to LogicVector.high(1), "irq0" to LogicVector.high(1)))
        assertEquals(0u, rig.rv().cpu.csrs.getValue(0x344))
        assertEquals(2u, rig.rv().plic.transact(SocBusRequest(0x0C002000u)).data)
        rig.evaluate(inputs = arrayOf("reset" to LogicVector.low(1), "irq0" to LogicVector.high(1)))
        assertEquals(0u, rig.rv().plic.transact(SocBusRequest(0x0C200004u)).data)
        assertEquals(0u, rig.rv().cpu.csrs.getValue(0x344) and 0x800u)
        rig.rv().plic.transact(SocBusRequest(0x0C200004u, 1u, read = false, write = true))
        rig.evaluate(inputs = arrayOf("irq0" to LogicVector.high(1)))
        assertEquals(0x800u, rig.rv().cpu.csrs.getValue(0x344) and 0x800u)
    }

    @Test fun pioOutputAndIrqUseTheDesktopTenTickDelay() {
        val output = CircuitComponent("pio", SocComponents.PIO, GridPoint(0, 0), attributes = mapOf(
            "direction" to "outputonly", "outputresetvalue" to "1"))
        fun evaluate(component: CircuitComponent, state: ComponentState, inputs: Map<String, LogicVector>) =
            SocEvaluation.evaluate(ComponentEvaluationContext(component, SocComponents.attributes(component),
                inputs, inputs.keys, state), listOf(component), mapOf(component.id to state)).evaluation
        val driven = evaluate(output, SocEvaluation.initialState(output), emptyMap())
        assertEquals(LogicValue.HIGH, driven.outputs.getValue("output0").bit(0))
        assertEquals(10L, driven.outputDelays["output0"] ?: driven.delay)
        val input = output.copy(attributes = mapOf("direction" to "inputonly", "genirq" to "true"))
        val state = assertIs<SocDeviceComponentState>(SocEvaluation.initialState(input))
        state.device.transact(SocBusRequest(8u, 1u, read = false, write = true))
        val interrupt = evaluate(input, state, mapOf("input0" to LogicVector.high(1)))
        assertEquals(LogicValue.HIGH, interrupt.outputs.getValue("irq").bit(0))
        assertEquals(10L, interrupt.outputDelays["irq"] ?: interrupt.delay)
    }

    @Test fun niosCustomSignalsUseFiveTicksAndStartFallsWithoutDelay() {
        val rig = Rig()
        rig.writeWord(0u, 0x10C80172u) // custom 5,r4,r2,r3
        rig.writeWord(4u, 0x0001883Au) // nop
        rig.evaluate(listOf(SocSetRegisterEvent(2, 10u), SocSetRegisterEvent(3, 20u)))
        val issued = rig.evaluate(listOf(SocStepEvent))
        assertEquals(LogicValue.HIGH, issued.outputs.getValue("start").bit(0))
        assertEquals(10uL, issued.outputs.getValue("dataA").toULongOrNull())
        assertEquals(20uL, issued.outputs.getValue("dataB").toULongOrNull())
        assertEquals(5uL, issued.outputs.getValue("n").toULongOrNull())
        for (port in listOf("dataA", "dataB", "start", "n", "a", "readRA", "b", "readRB", "c", "writeRC"))
            assertEquals(5L, issued.outputDelays[port] ?: issued.delay, port)
        val waiting = rig.evaluate(listOf(SocStepEvent), "done" to LogicVector.low(1))
        assertEquals(LogicValue.LOW, waiting.outputs.getValue("start").bit(0))
        assertEquals(0L, waiting.outputDelays["start"] ?: waiting.delay)
        assertEquals(4u, rig.nios().cpu.pc)
        val completed = rig.evaluate(listOf(SocStepEvent), "done" to LogicVector.high(1), "result" to LogicVector.of(32, 30u))
        assertEquals(30u, rig.nios().cpu.register(4))
        assertEquals(8u, rig.nios().cpu.pc)
        assertEquals(LogicValue.LOW, completed.outputs.getValue("start").bit(0))
    }

    private fun elf(machine: Int, instructions: List<UInt>): ByteArray = ByteArray(84 + instructions.size * 4).also { bytes ->
        fun put16(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte() }
        fun put32(at: Int, value: UInt) { repeat(4) { bytes[at + it] = (value shr (it * 8)).toByte() } }
        bytes[0] = 0x7F; bytes[1] = 69; bytes[2] = 76; bytes[3] = 70
        bytes[4] = 1; bytes[5] = 1; bytes[6] = 1
        put16(16, 2); put16(18, machine); put32(20, 1u); put32(24, 0x100u); put32(28, 52u)
        put16(40, 52); put16(42, 32); put16(44, 1)
        val size = (instructions.size * 4).toUInt()
        put32(52, 1u); put32(56, 84u); put32(64, 0x100u); put32(68, size); put32(72, size); put32(76, 5u)
        instructions.forEachIndexed { index, instruction -> put32(84 + index * 4, instruction) }
    }
}
