package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nios2CpuTest {
    @Test fun controlFlowMatchesDesktopExecutionUnits() {
        val vectors = nios2DesktopControlFixtures.trim().lines()
        assertEquals(68, vectors.size)
        for (line in vectors) {
            val fields = line.split(' ', limit = 13)
            fun field(index: Int) = fields[index].toUInt(16)
            val initial = Nios2Cpu.reset(0x20u).withRegister(2, field(1)).withRegister(3, field(2))
                .withRegister(29, 0x204u).withRegister(30, 0x304u).withRegister(31, 0x404u)
                .copy(status = 0x800001u, estatus = 0xA5000001u, bstatus = 0x5A000001u, ienable = 0xAA55AA55u)
            val result = Nios2Cpu.step(initial, TestMemory(mapOf(0x20u to field(0))),
                exceptionVector = 0x80u, breakVector = 0x100u)
            assertNull(result.error, line)
            assertEquals(field(3), result.state.pc, line)
            for ((fieldIndex, register) in listOf(4 to 4, 5 to 29, 6 to 30, 7 to 31))
                assertEquals(field(fieldIndex), result.state.register(register), line)
            assertEquals(field(8), result.state.status, line)
            assertEquals(field(9), result.state.estatus, line)
            assertEquals(field(10), result.state.bstatus, line)
            assertEquals(field(11), result.state.ienable, line)
            assertEquals(fields[12], result.trace?.assembly, line)
        }
    }

    @Test fun arithmeticMatchesDesktopExecutionUnits() {
        val vectors = nios2DesktopAluFixtures.trim().lines()
        assertEquals(320, vectors.size)
        for (line in vectors) {
            val fields = line.split(' ', limit = 5)
            val instruction = fields[0].toUInt(16)
            val memory = TestMemory(mapOf(0u to instruction))
            val initial = Nios2Cpu.reset().withRegister(2, fields[1].toUInt(16))
                .withRegister(3, fields[2].toUInt(16))
            val result = Nios2Cpu.step(initial, memory)
            assertNull(result.error, line)
            assertEquals(fields[3].toUInt(16), result.state.register(4), line)
            assertEquals(fields[4], result.trace?.assembly, line)
            assertEquals(4u, result.state.pc, line)
        }
    }

    @Test fun littleEndianMemoryAndIoInstructionsSignExtendCorrectly() {
        for ((load, expected) in listOf(0x07 to 0xFFFFFF80u, 0x03 to 0x80u,
            0x27 to 0xFFFFFF80u, 0x23 to 0x80u, 0x0F to 0xFFFF8080u,
            0x0B to 0x8080u, 0x2F to 0xFFFF8080u, 0x2B to 0x8080u,
            0x17 to 0x12348080u, 0x37 to 0x12348080u)) {
            val memory = TestMemory(mapOf(0u to i(load, 2, 4, -4), 0x100u to 0x12348080u))
            val result = Nios2Cpu.step(Nios2Cpu.reset().withRegister(2, 0x104u), memory)
            assertNull(result.error)
            assertEquals(expected, result.state.register(4), "opcode $load")
        }
        for ((store, expected) in listOf(0x05 to 0x123480FEu, 0x25 to 0x123480FEu,
            0x0D to 0x1234FFFEu, 0x2D to 0x1234FFFEu,
            0x15 to 0xFFFFFFFEu, 0x35 to 0xFFFFFFFEu)) {
            val memory = TestMemory(mapOf(0u to i(store, 2, 3, 0), 0x100u to 0x12348080u))
            val result = Nios2Cpu.step(Nios2Cpu.reset().withRegister(2, 0x100u)
                .withRegister(3, 0xFFFFFFFEu), memory)
            assertNull(result.error)
            assertEquals(expected, memory.read(0x100u).data, "opcode $store")
        }
    }

    @Test fun branchesUseSignedByteOffsetsAndUnsignedComparisons() {
        val cases = listOf(0x0E to false, 0x2E to true, 0x16 to true, 0x36 to false,
            0x26 to false, 0x1E to true)
        for ((opcode, taken) in cases) {
            val memory = TestMemory(mapOf(0x100u to i(opcode, 2, 3, -20)))
            val result = Nios2Cpu.step(Nios2Cpu.reset(0x100u).withRegister(2, UInt.MAX_VALUE)
                .withRegister(3, 1u), memory)
            assertEquals(if (taken) 0xF0u else 0x104u, result.state.pc, "opcode $opcode")
        }
        val wrap = Nios2Cpu.step(Nios2Cpu.reset(UInt.MAX_VALUE - 3u),
            TestMemory(mapOf((UInt.MAX_VALUE - 3u) to i(0x06, 0, 0, 8))))
        assertEquals(8u, wrap.state.pc)
    }

    @Test fun callsAndReturnsWriteReturnAddress() {
        val call = Nios2Cpu.step(Nios2Cpu.reset(0x100u), TestMemory(mapOf(0x100u to j(0x00, 0x400u))))
        assertEquals(0x400u, call.state.pc)
        assertEquals(0x104u, call.state.register(31))
        val ret = Nios2Cpu.step(call.state, TestMemory(mapOf(0x400u to r(0x05, 31, 0, 0))))
        assertEquals(0x104u, ret.state.pc)
        val indirect = Nios2Cpu.step(Nios2Cpu.reset().withRegister(2, 0x5678u),
            TestMemory(mapOf(0u to r(0x1D, 2, 0, 31))))
        assertEquals(0x5678u, indirect.state.pc)
        assertEquals(4u, indirect.state.register(31))
        val jump = Nios2Cpu.step(Nios2Cpu.reset(0xF0000000u),
            TestMemory(mapOf(0xF0000000u to j(0x01, 0x400u))))
        assertEquals(0x400u, jump.state.pc)
    }

    @Test fun interruptFetchesHandlerImmediatelyAndEretRestoresStatus() {
        val memory = TestMemory(mapOf(0x80u to i(0x04, 0, 4, 77),
            0x84u to r(0x01, 29, 30, 0), 0x20u to i(0x04, 0, 5, 99)))
        val enabled = Nios2Cpu.reset(0x20u).copy(status = 0x00800001u, ienable = 4u)
        val interrupt = Nios2Cpu.step(enabled, memory, irqLines = 4u, exceptionVector = 0x80u)
        assertEquals(0x84u, interrupt.state.pc)
        assertEquals(0x20u, interrupt.state.register(29))
        assertEquals(77u, interrupt.state.register(4))
        assertEquals(0x00800000u, interrupt.state.status)
        assertEquals(0x00800001u, interrupt.state.estatus)
        assertEquals(0x80u, interrupt.trace?.pc)
        val returned = Nios2Cpu.step(interrupt.state, memory, irqLines = 4u)
        assertEquals(0x20u, returned.state.pc)
        assertEquals(0x00800001u, returned.state.status)
        val continued = Nios2Cpu.step(returned.state, memory)
        assertEquals(99u, continued.state.register(5))
        assertEquals(0u, continued.state.ipending)
    }

    @Test fun trapAndBreakUseDifferentSavedStatusAndReturnRegisters() {
        val initial = Nios2Cpu.reset(0x20u).copy(status = 0x00800001u)
        val trap = Nios2Cpu.step(initial, TestMemory(mapOf(0x20u to r(0x2D, 0, 0, 29, 7))), exceptionVector = 0x80u)
        assertEquals(0x80u, trap.state.pc)
        assertEquals(0x24u, trap.state.register(29))
        assertEquals(initial.status, trap.state.estatus)
        val request = Nios2Cpu.step(initial, TestMemory(mapOf(0x20u to r(0x34, 0, 0, 30, 3))), breakVector = 0x100u)
        assertEquals(0x100u, request.state.pc)
        assertEquals(0x24u, request.state.register(30))
        assertEquals(initial.status, request.state.bstatus)
        val returned = Nios2Cpu.step(request.state, TestMemory(mapOf(0x100u to r(0x09, 30, 0, 30))))
        assertEquals(0x24u, returned.state.pc)
        assertEquals(initial.status, returned.state.status)
    }

    @Test fun controlRegistersMaskStatusAndRejectUnsupportedWrites() {
        var state = Nios2Cpu.reset().withRegister(2, UInt.MAX_VALUE)
        state = Nios2Cpu.step(state, TestMemory(mapOf(0u to r(0x2E, 2, 0, 0, 0)))).state
        assertEquals(0x00800001u, state.status)
        val read = Nios2Cpu.step(state, TestMemory(mapOf(4u to r(0x26, 0, 0, 4, 0))))
        assertEquals(0x00800001u, read.state.register(4))
        val unknownRead = Nios2Cpu.step(Nios2Cpu.reset(), TestMemory(mapOf(0u to r(0x26, 0, 0, 4, 31))))
        assertEquals(0u, unknownRead.state.register(4))
        val denied = Nios2Cpu.step(Nios2Cpu.reset().withRegister(2, 1u), TestMemory(mapOf(0u to r(0x2E, 2, 0, 0, 4))))
        assertTrue(denied.state.stopped)
        assertEquals(0u, denied.state.pc)
        assertNotNull(denied.error)
    }

    @Test fun customInstructionWaitsAndCompletesAlongsideNextInstruction() {
        val memory = TestMemory(mapOf(0u to custom(2, 3, 4, 0x55, readRA = true),
            4u to i(0x04, 4, 5, 1)))
        val issued = Nios2Cpu.step(Nios2Cpu.reset().withRegister(2, 10u).withRegister(3, 20u), memory)
        assertEquals(4u, issued.state.pc)
        assertEquals(1, issued.state.trace.size)
        assertEquals(Nios2CustomRequest(10u, 20u, 0x55, 2, 3, 4, true, false, false), issued.state.pendingCustom)
        val waiting = Nios2Cpu.step(issued.state, memory)
        assertEquals(4u, waiting.state.pc)
        assertEquals(false, waiting.state.pendingCustom?.start)
        assertNull(waiting.trace)
        val completed = Nios2Cpu.step(waiting.state, memory, customResponse = Nios2CustomResponse(true, 30u))
        assertEquals(30u, completed.state.register(4))
        assertEquals(31u, completed.state.register(5))
        assertEquals(8u, completed.state.pc)
        assertEquals(2, completed.state.trace.size)
        assertNull(completed.state.pendingCustom)
    }

    @Test fun customRegistersAndUndefinedSignalsFollowDesktopHandshake() {
        val memory = TestMemory(mapOf(0u to custom(2, 3, 4, 1, writeRC = true),
            4u to i(0x04, 0, 5, 1)))
        val issued = Nios2Cpu.step(Nios2Cpu.reset().withRegister(4, 99u), memory)
        val complete = Nios2Cpu.step(issued.state, memory, customResponse = Nios2CustomResponse(true, 30u))
        assertEquals(99u, complete.state.register(4))
        val badDone = Nios2Cpu.step(issued.state, memory, customResponse = Nios2CustomResponse(null))
        assertTrue(badDone.state.stopped)
        assertEquals(4u, badDone.state.pc)
        assertNotNull(badDone.error)
        val ordinary = issued.state.copy(pendingCustom = issued.state.pendingCustom?.copy(writeRC = false))
        val undefinedResult = Nios2Cpu.step(ordinary, memory, customResponse = Nios2CustomResponse(true))
        assertEquals(0u, undefinedResult.state.register(4))
    }

    @Test fun malformedInstructionStopsAfterAdvancingPcButBusErrorDoesNot() {
        for (instruction in listOf(0xFFFFFFFFu, r(0x31, 2, 3, 4, 1),
            r(0x05, 2, 0, 0), i(0x06, 2, 0, 0))) {
            val result = Nios2Cpu.step(Nios2Cpu.reset(), TestMemory(mapOf(0u to instruction)))
            assertTrue(result.state.stopped, instruction.toString(16))
            assertEquals(4u, result.state.pc)
            assertNotNull(result.error)
        }
        val noCode = Nios2Cpu.step(Nios2Cpu.reset(), TestMemory())
        assertEquals(0u, noCode.state.pc)
        assertTrue(noCode.state.stopped)
        val noData = Nios2Cpu.step(Nios2Cpu.reset().withRegister(2, 0x100u), TestMemory(mapOf(0u to i(0x17, 2, 4, 0))))
        assertEquals(0u, noData.state.pc)
        assertTrue(noData.state.stopped)
        val zeroDivisor = Nios2Cpu.step(Nios2Cpu.reset(), TestMemory(mapOf(0u to r(0x18, 2, 3, 4))))
        assertEquals(0u, zeroDivisor.state.pc)
        assertTrue(zeroDivisor.state.stopped)
        assertEquals("Division by zero.", zeroDivisor.error)
    }

    @Test fun hardwareOnlyInstructionsAreValidNoOpsAndStateIsIndependent() {
        val hardware = listOf(i(0x3B, 2, 0, -4), i(0x1B, 2, 0, -4),
            i(0x33, 2, 0, -4), i(0x13, 2, 0, -4), r(0x0C, 2, 0, 0),
            r(0x29, 2, 0, 0), r(0x04, 0, 0, 0), r(0x36, 0, 0, 0))
        for (instruction in hardware) {
            val state = Nios2Cpu.reset().withRegister(2, 0x1234u)
            val result = Nios2Cpu.step(state, TestMemory(mapOf(0u to instruction)))
            assertNull(result.error)
            assertEquals(4u, result.state.pc)
            assertEquals(state.registers, result.state.registers)
            assertEquals(0u, state.pc)
        }
        val reset = Nios2Cpu.reset(0x40u, Nios2Cpu.reset().withRegister(2, 0x1234u))
        assertEquals(0x1234u, reset.register(2))
        assertEquals(1u, reset.knownRegisters)
        assertEquals(0x00800000u, reset.status)
        assertEquals(0u, reset.withRegister(0, 99u).register(0))
        assertFalse(reset.stopped)
    }

    @Test fun traceIsBoundedAndStoppedCpuDoesNotExecute() {
        val memory = TestMemory(mapOf(0u to i(0x06, 0, 0, -4)))
        var state = Nios2Cpu.reset()
        repeat(300) { state = Nios2Cpu.step(state, memory).state }
        assertEquals(300L, state.instructions)
        assertEquals(256, state.trace.size)
        val stopped = state.copy(stopped = true)
        assertEquals(stopped, Nios2Cpu.step(stopped, memory).state)
        assertNull(Nios2Cpu.step(stopped, memory).trace)
    }

    /** W2-06 C79: the listing decodes each word without executing it, and gives the desktop trace text. */
    @Test fun disassemblerMatchesTheDesktopTraceText() {
        for (line in nios2DesktopAluFixtures.trim().lines()) {
            val fields = line.split(' ', limit = 5)
            assertEquals(fields[4], Nios2Disassembler.format(fields[0].toUInt(16)), line)
        }
        for (line in nios2DesktopControlFixtures.trim().lines()) {
            val fields = line.split(' ', limit = 13)
            assertEquals(fields[12], Nios2Disassembler.format(fields[0].toUInt(16)), line)
        }
        // add r0,r0,r0 is nop, add with rb = 0 is mov, addi from zero is movi: as the trace writes them.
        assertEquals("nop", Nios2Disassembler.format(r(0x31, 0, 0, 0)))
        assertEquals("mov       r4,r2", Nios2Disassembler.format(r(0x31, 2, 0, 4)))
        assertEquals("movi      r5,-7", Nios2Disassembler.format(i(0x04, 0, 5, -7)))
        assertEquals("ldw       r4,-4(r2)", Nios2Disassembler.format(i(0x17, 2, 4, -4)))
        assertNull(Nios2Disassembler.format(r(0x31, 2, 3, 4, immediate = 1)), "add with a shift amount is invalid")
        assertNull(Nios2Disassembler.format(i(0x06, 1, 0, 8)), "br with registers is invalid")
        assertNull(Nios2Disassembler.format(0x3Fu))
    }

    /** Every trace entry, including a failed load and a division by zero, is the listing text of its word. */
    @Test fun traceAndListingShowTheSameText() {
        val program = listOf(i(0x04, 0, 2, 0x100), r(0x31, 0, 0, 0), r(0x31, 2, 0, 3), i(0x14, 0, 6, 0xFFFF),
            i(0x34, 0, 7, 0x1234), custom(2, 3, 4, 7, readRA = true), i(0x17, 2, 4, 0), r(0x24, 2, 0, 5))
        for (start in program.indices) {
            val memory = TestMemory(program.withIndex().associate { (index, word) -> index.toUInt() * 4u to word })
            var state = Nios2Cpu.reset(start.toUInt() * 4u)
            repeat(program.size) {
                val result = Nios2Cpu.step(state, memory, customResponse = Nios2CustomResponse(true, 0u))
                state = result.state
                result.trace?.let { entry ->
                    if (entry.assembly != "invalid" && entry.assembly != "fetch") {
                        assertEquals(Nios2Disassembler.format(entry.instruction), entry.assembly, entry.toString())
                    }
                }
            }
        }
    }

    private fun i(opcode: Int, a: Int, b: Int, immediate: Int): UInt =
        (opcode or ((immediate and 65535) shl 6) or (b shl 22) or (a shl 27)).toUInt()
    private fun r(opx: Int, a: Int, b: Int, c: Int, immediate: Int = 0): UInt =
        (0x3A or (opx shl 11) or ((immediate and 31) shl 6) or (c shl 17) or (b shl 22) or (a shl 27)).toUInt()
    private fun j(opcode: Int, address: UInt): UInt = ((address shr 2) shl 6) or opcode.toUInt()
    private fun custom(a: Int, b: Int, c: Int, n: Int, readRA: Boolean = false, writeRC: Boolean = false): UInt =
        (0x32 or ((n or (if (readRA) 0x400 else 0) or (if (writeRC) 0x100 else 0)) shl 6) or
            (c shl 17) or (b shl 22) or (a shl 27)).toUInt()

    private class TestMemory(initial: Map<UInt, UInt> = emptyMap()) : SocCpuMemory {
        val words = initial.toMutableMap()
        override fun read(address: UInt, size: SocAccessSize): SocBusReply {
            val word = words[address and 3u.inv()] ?: return SocBusReply(error = SocBusError.NO_RESPONDER)
            val shift = if (size == SocAccessSize.WORD) 0 else (address and 3u).toInt() * 8
            val mask = when (size) { SocAccessSize.BYTE -> 255u; SocAccessSize.HALF_WORD -> 65535u; SocAccessSize.WORD -> UInt.MAX_VALUE }
            return SocBusReply((word shr shift) and mask)
        }
        override fun write(address: UInt, value: UInt, size: SocAccessSize): SocBusReply {
            val base = address and 3u.inv()
            val old = words[base] ?: return SocBusReply(error = SocBusError.NO_RESPONDER)
            val shift = if (size == SocAccessSize.WORD) 0 else (address and 3u).toInt() * 8
            val mask = when (size) { SocAccessSize.BYTE -> 255u; SocAccessSize.HALF_WORD -> 65535u; SocAccessSize.WORD -> UInt.MAX_VALUE }
            words[base] = (old and (mask shl shift).inv()) or ((value and mask) shl shift)
            return SocBusReply()
        }
    }
}

// Generated by scripts/Nios2DesktopOracle.java against Logisim-evolution 5.1.0dev.
private val nios2DesktopAluFixtures = """
10C8703A 00000000 00000001 00000000 and       r4,r2,r3
10C8703A 00000001 FFFFFFFF 00000001 and       r4,r2,r3
10C8703A FFFFFFFF 00000001 00000001 and       r4,r2,r3
10C8703A 80000000 FFFFFFFF 80000000 and       r4,r2,r3
10C8703A 7FFFFFFF 80000000 00000000 and       r4,r2,r3
10C8703A 12345678 76543210 12141210 and       r4,r2,r3
10C8703A AAAAAAAA 0000001F 0000000A and       r4,r2,r3
10C8703A 55555555 00000020 00000000 and       r4,r2,r3
10C8B03A 00000000 00000001 00000001 or        r4,r2,r3
10C8B03A 00000001 FFFFFFFF FFFFFFFF or        r4,r2,r3
10C8B03A FFFFFFFF 00000001 FFFFFFFF or        r4,r2,r3
10C8B03A 80000000 FFFFFFFF FFFFFFFF or        r4,r2,r3
10C8B03A 7FFFFFFF 80000000 FFFFFFFF or        r4,r2,r3
10C8B03A 12345678 76543210 76747678 or        r4,r2,r3
10C8B03A AAAAAAAA 0000001F AAAAAABF or        r4,r2,r3
10C8B03A 55555555 00000020 55555575 or        r4,r2,r3
10C8F03A 00000000 00000001 00000001 xor       r4,r2,r3
10C8F03A 00000001 FFFFFFFF FFFFFFFE xor       r4,r2,r3
10C8F03A FFFFFFFF 00000001 FFFFFFFE xor       r4,r2,r3
10C8F03A 80000000 FFFFFFFF 7FFFFFFF xor       r4,r2,r3
10C8F03A 7FFFFFFF 80000000 FFFFFFFF xor       r4,r2,r3
10C8F03A 12345678 76543210 64606468 xor       r4,r2,r3
10C8F03A AAAAAAAA 0000001F AAAAAAB5 xor       r4,r2,r3
10C8F03A 55555555 00000020 55555575 xor       r4,r2,r3
10C8303A 00000000 00000001 FFFFFFFE nor       r4,r2,r3
10C8303A 00000001 FFFFFFFF 00000000 nor       r4,r2,r3
10C8303A FFFFFFFF 00000001 00000000 nor       r4,r2,r3
10C8303A 80000000 FFFFFFFF 00000000 nor       r4,r2,r3
10C8303A 7FFFFFFF 80000000 00000000 nor       r4,r2,r3
10C8303A 12345678 76543210 898B8987 nor       r4,r2,r3
10C8303A AAAAAAAA 0000001F 55555540 nor       r4,r2,r3
10C8303A 55555555 00000020 AAAAAA8A nor       r4,r2,r3
10C9883A 00000000 00000001 00000001 add       r4,r2,r3
10C9883A 00000001 FFFFFFFF 00000000 add       r4,r2,r3
10C9883A FFFFFFFF 00000001 00000000 add       r4,r2,r3
10C9883A 80000000 FFFFFFFF 7FFFFFFF add       r4,r2,r3
10C9883A 7FFFFFFF 80000000 FFFFFFFF add       r4,r2,r3
10C9883A 12345678 76543210 88888888 add       r4,r2,r3
10C9883A AAAAAAAA 0000001F AAAAAAC9 add       r4,r2,r3
10C9883A 55555555 00000020 55555575 add       r4,r2,r3
10C9C83A 00000000 00000001 FFFFFFFF sub       r4,r2,r3
10C9C83A 00000001 FFFFFFFF 00000002 sub       r4,r2,r3
10C9C83A FFFFFFFF 00000001 FFFFFFFE sub       r4,r2,r3
10C9C83A 80000000 FFFFFFFF 80000001 sub       r4,r2,r3
10C9C83A 7FFFFFFF 80000000 FFFFFFFF sub       r4,r2,r3
10C9C83A 12345678 76543210 9BE02468 sub       r4,r2,r3
10C9C83A AAAAAAAA 0000001F AAAAAA8B sub       r4,r2,r3
10C9C83A 55555555 00000020 55555535 sub       r4,r2,r3
10C9383A 00000000 00000001 00000000 mul       r4,r2,r3
10C9383A 00000001 FFFFFFFF FFFFFFFF mul       r4,r2,r3
10C9383A FFFFFFFF 00000001 FFFFFFFF mul       r4,r2,r3
10C9383A 80000000 FFFFFFFF 80000000 mul       r4,r2,r3
10C9383A 7FFFFFFF 80000000 80000000 mul       r4,r2,r3
10C9383A 12345678 76543210 0B88D780 mul       r4,r2,r3
10C9383A AAAAAAAA 0000001F AAAAAA96 mul       r4,r2,r3
10C9383A 55555555 00000020 AAAAAAA0 mul       r4,r2,r3
10C8C03A 00000000 00000001 00000000 div       r4,r2,r3
10C8C03A 00000001 FFFFFFFF FFFFFFFF div       r4,r2,r3
10C8C03A FFFFFFFF 00000001 FFFFFFFF div       r4,r2,r3
10C8C03A 80000000 FFFFFFFF 80000000 div       r4,r2,r3
10C8C03A 7FFFFFFF 80000000 00000000 div       r4,r2,r3
10C8C03A 12345678 76543210 00000000 div       r4,r2,r3
10C8C03A AAAAAAAA 0000001F FD3F4FD4 div       r4,r2,r3
10C8C03A 55555555 00000020 02AAAAAA div       r4,r2,r3
10C9203A 00000000 00000001 00000000 divu      r4,r2,r3
10C9203A 00000001 FFFFFFFF 00000000 divu      r4,r2,r3
10C9203A FFFFFFFF 00000001 FFFFFFFF divu      r4,r2,r3
10C9203A 80000000 FFFFFFFF 00000000 divu      r4,r2,r3
10C9203A 7FFFFFFF 80000000 00000000 divu      r4,r2,r3
10C9203A 12345678 76543210 00000000 divu      r4,r2,r3
10C9203A AAAAAAAA 0000001F 05816058 divu      r4,r2,r3
10C9203A 55555555 00000020 02AAAAAA divu      r4,r2,r3
10C8F83A 00000000 00000001 00000000 mulxss    r4,r2,r3
10C8F83A 00000001 FFFFFFFF FFFFFFFF mulxss    r4,r2,r3
10C8F83A FFFFFFFF 00000001 FFFFFFFF mulxss    r4,r2,r3
10C8F83A 80000000 FFFFFFFF 00000000 mulxss    r4,r2,r3
10C8F83A 7FFFFFFF 80000000 C0000000 mulxss    r4,r2,r3
10C8F83A 12345678 76543210 086A1C97 mulxss    r4,r2,r3
10C8F83A AAAAAAAA 0000001F FFFFFFF5 mulxss    r4,r2,r3
10C8F83A 55555555 00000020 0000000A mulxss    r4,r2,r3
10C8383A 00000000 00000001 00000000 mulxuu    r4,r2,r3
10C8383A 00000001 FFFFFFFF 00000000 mulxuu    r4,r2,r3
10C8383A FFFFFFFF 00000001 00000000 mulxuu    r4,r2,r3
10C8383A 80000000 FFFFFFFF 7FFFFFFF mulxuu    r4,r2,r3
10C8383A 7FFFFFFF 80000000 3FFFFFFF mulxuu    r4,r2,r3
10C8383A 12345678 76543210 086A1C97 mulxuu    r4,r2,r3
10C8383A AAAAAAAA 0000001F 00000014 mulxuu    r4,r2,r3
10C8383A 55555555 00000020 0000000A mulxuu    r4,r2,r3
10C8B83A 00000000 00000001 00000000 mulxsu    r4,r2,r3
10C8B83A 00000001 FFFFFFFF 00000000 mulxsu    r4,r2,r3
10C8B83A FFFFFFFF 00000001 FFFFFFFF mulxsu    r4,r2,r3
10C8B83A 80000000 FFFFFFFF 80000000 mulxsu    r4,r2,r3
10C8B83A 7FFFFFFF 80000000 3FFFFFFF mulxsu    r4,r2,r3
10C8B83A 12345678 76543210 086A1C97 mulxsu    r4,r2,r3
10C8B83A AAAAAAAA 0000001F FFFFFFF5 mulxsu    r4,r2,r3
10C8B83A 55555555 00000020 0000000A mulxsu    r4,r2,r3
10C9003A 00000000 00000001 00000000 cmpeq     r4,r2,r3
10C9003A 00000001 FFFFFFFF 00000000 cmpeq     r4,r2,r3
10C9003A FFFFFFFF 00000001 00000000 cmpeq     r4,r2,r3
10C9003A 80000000 FFFFFFFF 00000000 cmpeq     r4,r2,r3
10C9003A 7FFFFFFF 80000000 00000000 cmpeq     r4,r2,r3
10C9003A 12345678 76543210 00000000 cmpeq     r4,r2,r3
10C9003A AAAAAAAA 0000001F 00000000 cmpeq     r4,r2,r3
10C9003A 55555555 00000020 00000000 cmpeq     r4,r2,r3
10C8403A 00000000 00000001 00000000 cmpge     r4,r2,r3
10C8403A 00000001 FFFFFFFF 00000001 cmpge     r4,r2,r3
10C8403A FFFFFFFF 00000001 00000000 cmpge     r4,r2,r3
10C8403A 80000000 FFFFFFFF 00000000 cmpge     r4,r2,r3
10C8403A 7FFFFFFF 80000000 00000001 cmpge     r4,r2,r3
10C8403A 12345678 76543210 00000000 cmpge     r4,r2,r3
10C8403A AAAAAAAA 0000001F 00000000 cmpge     r4,r2,r3
10C8403A 55555555 00000020 00000001 cmpge     r4,r2,r3
10C9403A 00000000 00000001 00000000 cmpgeu    r4,r2,r3
10C9403A 00000001 FFFFFFFF 00000000 cmpgeu    r4,r2,r3
10C9403A FFFFFFFF 00000001 00000001 cmpgeu    r4,r2,r3
10C9403A 80000000 FFFFFFFF 00000000 cmpgeu    r4,r2,r3
10C9403A 7FFFFFFF 80000000 00000000 cmpgeu    r4,r2,r3
10C9403A 12345678 76543210 00000000 cmpgeu    r4,r2,r3
10C9403A AAAAAAAA 0000001F 00000001 cmpgeu    r4,r2,r3
10C9403A 55555555 00000020 00000001 cmpgeu    r4,r2,r3
10C8803A 00000000 00000001 00000001 cmplt     r4,r2,r3
10C8803A 00000001 FFFFFFFF 00000000 cmplt     r4,r2,r3
10C8803A FFFFFFFF 00000001 00000001 cmplt     r4,r2,r3
10C8803A 80000000 FFFFFFFF 00000001 cmplt     r4,r2,r3
10C8803A 7FFFFFFF 80000000 00000000 cmplt     r4,r2,r3
10C8803A 12345678 76543210 00000001 cmplt     r4,r2,r3
10C8803A AAAAAAAA 0000001F 00000001 cmplt     r4,r2,r3
10C8803A 55555555 00000020 00000000 cmplt     r4,r2,r3
10C9803A 00000000 00000001 00000001 cmpltu    r4,r2,r3
10C9803A 00000001 FFFFFFFF 00000001 cmpltu    r4,r2,r3
10C9803A FFFFFFFF 00000001 00000000 cmpltu    r4,r2,r3
10C9803A 80000000 FFFFFFFF 00000001 cmpltu    r4,r2,r3
10C9803A 7FFFFFFF 80000000 00000001 cmpltu    r4,r2,r3
10C9803A 12345678 76543210 00000001 cmpltu    r4,r2,r3
10C9803A AAAAAAAA 0000001F 00000000 cmpltu    r4,r2,r3
10C9803A 55555555 00000020 00000000 cmpltu    r4,r2,r3
10C8183A 00000000 00000001 00000000 rol       r4,r2,r3
10C8183A 00000001 FFFFFFFF 80000000 rol       r4,r2,r3
10C8183A FFFFFFFF 00000001 FFFFFFFF rol       r4,r2,r3
10C8183A 80000000 FFFFFFFF 40000000 rol       r4,r2,r3
10C8183A 7FFFFFFF 80000000 7FFFFFFF rol       r4,r2,r3
10C8183A 12345678 76543210 56781234 rol       r4,r2,r3
10C8183A AAAAAAAA 0000001F 55555555 rol       r4,r2,r3
10C8183A 55555555 00000020 55555555 rol       r4,r2,r3
10C8583A 00000000 00000001 00000000 ror       r4,r2,r3
10C8583A 00000001 FFFFFFFF 00000002 ror       r4,r2,r3
10C8583A FFFFFFFF 00000001 FFFFFFFF ror       r4,r2,r3
10C8583A 80000000 FFFFFFFF 00000001 ror       r4,r2,r3
10C8583A 7FFFFFFF 80000000 7FFFFFFF ror       r4,r2,r3
10C8583A 12345678 76543210 56781234 ror       r4,r2,r3
10C8583A AAAAAAAA 0000001F 55555555 ror       r4,r2,r3
10C8583A 55555555 00000020 55555555 ror       r4,r2,r3
10C8983A 00000000 00000001 00000000 sll       r4,r2,r3
10C8983A 00000001 FFFFFFFF 80000000 sll       r4,r2,r3
10C8983A FFFFFFFF 00000001 FFFFFFFE sll       r4,r2,r3
10C8983A 80000000 FFFFFFFF 00000000 sll       r4,r2,r3
10C8983A 7FFFFFFF 80000000 7FFFFFFF sll       r4,r2,r3
10C8983A 12345678 76543210 56780000 sll       r4,r2,r3
10C8983A AAAAAAAA 0000001F 00000000 sll       r4,r2,r3
10C8983A 55555555 00000020 55555555 sll       r4,r2,r3
10C9D83A 00000000 00000001 00000000 sra       r4,r2,r3
10C9D83A 00000001 FFFFFFFF 00000000 sra       r4,r2,r3
10C9D83A FFFFFFFF 00000001 FFFFFFFF sra       r4,r2,r3
10C9D83A 80000000 FFFFFFFF FFFFFFFF sra       r4,r2,r3
10C9D83A 7FFFFFFF 80000000 7FFFFFFF sra       r4,r2,r3
10C9D83A 12345678 76543210 00001234 sra       r4,r2,r3
10C9D83A AAAAAAAA 0000001F FFFFFFFF sra       r4,r2,r3
10C9D83A 55555555 00000020 55555555 sra       r4,r2,r3
10C8D83A 00000000 00000001 00000000 srl       r4,r2,r3
10C8D83A 00000001 FFFFFFFF 00000000 srl       r4,r2,r3
10C8D83A FFFFFFFF 00000001 7FFFFFFF srl       r4,r2,r3
10C8D83A 80000000 FFFFFFFF 00000001 srl       r4,r2,r3
10C8D83A 7FFFFFFF 80000000 7FFFFFFF srl       r4,r2,r3
10C8D83A 12345678 76543210 00001234 srl       r4,r2,r3
10C8D83A AAAAAAAA 0000001F 00000001 srl       r4,r2,r3
10C8D83A 55555555 00000020 55555555 srl       r4,r2,r3
1100004C 00000000 00000001 00000000 andi      r4,r2,1
113FFFCC 00000001 FFFFFFFF 00000001 andi      r4,r2,65535
1100004C FFFFFFFF 00000001 00000001 andi      r4,r2,1
113FFFCC 80000000 FFFFFFFF 00000000 andi      r4,r2,65535
1100000C 7FFFFFFF 80000000 00000000 andi      r4,r2,0
110C840C 12345678 76543210 00001210 andi      r4,r2,12816
110007CC AAAAAAAA 0000001F 0000000A andi      r4,r2,31
1100080C 55555555 00000020 00000000 andi      r4,r2,32
11000054 00000000 00000001 00000001 ori       r4,r2,1
113FFFD4 00000001 FFFFFFFF 0000FFFF ori       r4,r2,65535
11000054 FFFFFFFF 00000001 FFFFFFFF ori       r4,r2,1
113FFFD4 80000000 FFFFFFFF 8000FFFF ori       r4,r2,65535
11000014 7FFFFFFF 80000000 7FFFFFFF ori       r4,r2,0
110C8414 12345678 76543210 12347678 ori       r4,r2,12816
110007D4 AAAAAAAA 0000001F AAAAAABF ori       r4,r2,31
11000814 55555555 00000020 55555575 ori       r4,r2,32
1100005C 00000000 00000001 00000001 xori      r4,r2,1
113FFFDC 00000001 FFFFFFFF 0000FFFE xori      r4,r2,65535
1100005C FFFFFFFF 00000001 FFFFFFFE xori      r4,r2,1
113FFFDC 80000000 FFFFFFFF 8000FFFF xori      r4,r2,65535
1100001C 7FFFFFFF 80000000 7FFFFFFF xori      r4,r2,0
110C841C 12345678 76543210 12346468 xori      r4,r2,12816
110007DC AAAAAAAA 0000001F AAAAAAB5 xori      r4,r2,31
1100081C 55555555 00000020 55555575 xori      r4,r2,32
1100006C 00000000 00000001 00000000 andhi     r4,r2,1
113FFFEC 00000001 FFFFFFFF 00000000 andhi     r4,r2,65535
1100006C FFFFFFFF 00000001 00010000 andhi     r4,r2,1
113FFFEC 80000000 FFFFFFFF 80000000 andhi     r4,r2,65535
1100002C 7FFFFFFF 80000000 00000000 andhi     r4,r2,0
110C842C 12345678 76543210 12100000 andhi     r4,r2,12816
110007EC AAAAAAAA 0000001F 000A0000 andhi     r4,r2,31
1100082C 55555555 00000020 00000000 andhi     r4,r2,32
11000074 00000000 00000001 00010000 orhi      r4,r2,1
113FFFF4 00000001 FFFFFFFF FFFF0001 orhi      r4,r2,65535
11000074 FFFFFFFF 00000001 FFFFFFFF orhi      r4,r2,1
113FFFF4 80000000 FFFFFFFF FFFF0000 orhi      r4,r2,65535
11000034 7FFFFFFF 80000000 7FFFFFFF orhi      r4,r2,0
110C8434 12345678 76543210 32345678 orhi      r4,r2,12816
110007F4 AAAAAAAA 0000001F AABFAAAA orhi      r4,r2,31
11000834 55555555 00000020 55755555 orhi      r4,r2,32
1100007C 00000000 00000001 00010000 xorhi     r4,r2,1
113FFFFC 00000001 FFFFFFFF FFFF0001 xorhi     r4,r2,65535
1100007C FFFFFFFF 00000001 FFFEFFFF xorhi     r4,r2,1
113FFFFC 80000000 FFFFFFFF 7FFF0000 xorhi     r4,r2,65535
1100003C 7FFFFFFF 80000000 7FFFFFFF xorhi     r4,r2,0
110C843C 12345678 76543210 20245678 xorhi     r4,r2,12816
110007FC AAAAAAAA 0000001F AAB5AAAA xorhi     r4,r2,31
1100083C 55555555 00000020 55755555 xorhi     r4,r2,32
11000044 00000000 00000001 00000001 addi      r4,r2,1
113FFFC4 00000001 FFFFFFFF 00000000 addi      r4,r2,-1
11000044 FFFFFFFF 00000001 00000000 addi      r4,r2,1
113FFFC4 80000000 FFFFFFFF 7FFFFFFF addi      r4,r2,-1
11000004 7FFFFFFF 80000000 7FFFFFFF addi      r4,r2,0
110C8404 12345678 76543210 12348888 addi      r4,r2,12816
110007C4 AAAAAAAA 0000001F AAAAAAC9 addi      r4,r2,31
11000804 55555555 00000020 55555575 addi      r4,r2,32
11000064 00000000 00000001 00000000 muli      r4,r2,1
113FFFE4 00000001 FFFFFFFF FFFFFFFF muli      r4,r2,-1
11000064 FFFFFFFF 00000001 FFFFFFFF muli      r4,r2,1
113FFFE4 80000000 FFFFFFFF 00000000 muli      r4,r2,-1
11000024 7FFFFFFF 80000000 00000000 muli      r4,r2,0
110C8424 12345678 76543210 0000038F muli      r4,r2,12816
110007E4 AAAAAAAA 0000001F FFFFFFF5 muli      r4,r2,31
11000824 55555555 00000020 0000000A muli      r4,r2,32
11000060 00000000 00000001 00000000 cmpeqi    r4,r2,1
113FFFE0 00000001 FFFFFFFF 00000000 cmpeqi    r4,r2,-1
11000060 FFFFFFFF 00000001 00000000 cmpeqi    r4,r2,1
113FFFE0 80000000 FFFFFFFF 00000000 cmpeqi    r4,r2,-1
11000020 7FFFFFFF 80000000 00000000 cmpeqi    r4,r2,0
110C8420 12345678 76543210 00000000 cmpeqi    r4,r2,12816
110007E0 AAAAAAAA 0000001F 00000000 cmpeqi    r4,r2,31
11000820 55555555 00000020 00000000 cmpeqi    r4,r2,32
11000058 00000000 00000001 00000001 cmpnei    r4,r2,1
113FFFD8 00000001 FFFFFFFF 00000001 cmpnei    r4,r2,-1
11000058 FFFFFFFF 00000001 00000001 cmpnei    r4,r2,1
113FFFD8 80000000 FFFFFFFF 00000001 cmpnei    r4,r2,-1
11000018 7FFFFFFF 80000000 00000001 cmpnei    r4,r2,0
110C8418 12345678 76543210 00000001 cmpnei    r4,r2,12816
110007D8 AAAAAAAA 0000001F 00000001 cmpnei    r4,r2,31
11000818 55555555 00000020 00000001 cmpnei    r4,r2,32
11000048 00000000 00000001 00000000 cmpgei    r4,r2,1
113FFFC8 00000001 FFFFFFFF 00000001 cmpgei    r4,r2,-1
11000048 FFFFFFFF 00000001 00000000 cmpgei    r4,r2,1
113FFFC8 80000000 FFFFFFFF 00000000 cmpgei    r4,r2,-1
11000008 7FFFFFFF 80000000 00000001 cmpgei    r4,r2,0
110C8408 12345678 76543210 00000001 cmpgei    r4,r2,12816
110007C8 AAAAAAAA 0000001F 00000000 cmpgei    r4,r2,31
11000808 55555555 00000020 00000001 cmpgei    r4,r2,32
11000068 00000000 00000001 00000000 cmpgeui   r4,r2,1
113FFFE8 00000001 FFFFFFFF 00000000 cmpgeui   r4,r2,65535
11000068 FFFFFFFF 00000001 00000001 cmpgeui   r4,r2,1
113FFFE8 80000000 FFFFFFFF 00000001 cmpgeui   r4,r2,65535
11000028 7FFFFFFF 80000000 00000001 cmpgeui   r4,r2,0
110C8428 12345678 76543210 00000001 cmpgeui   r4,r2,12816
110007E8 AAAAAAAA 0000001F 00000001 cmpgeui   r4,r2,31
11000828 55555555 00000020 00000001 cmpgeui   r4,r2,32
11000050 00000000 00000001 00000001 cmplti    r4,r2,1
113FFFD0 00000001 FFFFFFFF 00000000 cmplti    r4,r2,-1
11000050 FFFFFFFF 00000001 00000001 cmplti    r4,r2,1
113FFFD0 80000000 FFFFFFFF 00000001 cmplti    r4,r2,-1
11000010 7FFFFFFF 80000000 00000000 cmplti    r4,r2,0
110C8410 12345678 76543210 00000000 cmplti    r4,r2,12816
110007D0 AAAAAAAA 0000001F 00000001 cmplti    r4,r2,31
11000810 55555555 00000020 00000000 cmplti    r4,r2,32
11000070 00000000 00000001 00000001 cmpltui   r4,r2,1
113FFFF0 00000001 FFFFFFFF 00000001 cmpltui   r4,r2,65535
11000070 FFFFFFFF 00000001 00000000 cmpltui   r4,r2,1
113FFFF0 80000000 FFFFFFFF 00000000 cmpltui   r4,r2,65535
11000030 7FFFFFFF 80000000 00000000 cmpltui   r4,r2,0
110C8430 12345678 76543210 00000000 cmpltui   r4,r2,12816
110007F0 AAAAAAAA 0000001F 00000000 cmpltui   r4,r2,31
11000830 55555555 00000020 00000000 cmpltui   r4,r2,32
1008107A 00000000 00000001 00000000 roli      r4,r2,1
100817FA 00000001 FFFFFFFF 80000000 roli      r4,r2,31
1008107A FFFFFFFF 00000001 FFFFFFFF roli      r4,r2,1
100817FA 80000000 FFFFFFFF 40000000 roli      r4,r2,31
1008103A 7FFFFFFF 80000000 7FFFFFFF roli      r4,r2,0
1008143A 12345678 76543210 56781234 roli      r4,r2,16
100817FA AAAAAAAA 0000001F 55555555 roli      r4,r2,31
1008103A 55555555 00000020 55555555 roli      r4,r2,0
1008907A 00000000 00000001 00000000 slli      r4,r2,1
100897FA 00000001 FFFFFFFF 80000000 slli      r4,r2,31
1008907A FFFFFFFF 00000001 FFFFFFFE slli      r4,r2,1
100897FA 80000000 FFFFFFFF 00000000 slli      r4,r2,31
1008903A 7FFFFFFF 80000000 7FFFFFFF slli      r4,r2,0
1008943A 12345678 76543210 56780000 slli      r4,r2,16
100897FA AAAAAAAA 0000001F 00000000 slli      r4,r2,31
1008903A 55555555 00000020 55555555 slli      r4,r2,0
1009D07A 00000000 00000001 00000000 srai      r4,r2,1
1009D7FA 00000001 FFFFFFFF 00000000 srai      r4,r2,31
1009D07A FFFFFFFF 00000001 FFFFFFFF srai      r4,r2,1
1009D7FA 80000000 FFFFFFFF FFFFFFFF srai      r4,r2,31
1009D03A 7FFFFFFF 80000000 7FFFFFFF srai      r4,r2,0
1009D43A 12345678 76543210 00001234 srai      r4,r2,16
1009D7FA AAAAAAAA 0000001F FFFFFFFF srai      r4,r2,31
1009D03A 55555555 00000020 55555555 srai      r4,r2,0
1008D07A 00000000 00000001 00000000 srli      r4,r2,1
1008D7FA 00000001 FFFFFFFF 00000000 srli      r4,r2,31
1008D07A FFFFFFFF 00000001 7FFFFFFF srli      r4,r2,1
1008D7FA 80000000 FFFFFFFF 00000001 srli      r4,r2,31
1008D03A 7FFFFFFF 80000000 7FFFFFFF srli      r4,r2,0
1008D43A 12345678 76543210 00001234 srli      r4,r2,16
1008D7FA AAAAAAAA 0000001F 00000001 srli      r4,r2,31
1008D03A 55555555 00000020 55555555 srli      r4,r2,0
"""

private val nios2DesktopControlFixtures = """
10FFFB0E 00000000 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E 00000001 FFFFFFFF 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E 80000000 FFFFFFFF 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E 7FFFFFFF 80000000 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E 12345678 76543210 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E AAAAAAAA 0000001F 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB0E 55555555 00000020 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bge       r2,r3,pc-16
10FFFB2E 00000000 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E 00000001 FFFFFFFF 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E FFFFFFFF 00000001 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E 80000000 FFFFFFFF 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E 7FFFFFFF 80000000 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E 12345678 76543210 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E AAAAAAAA 0000001F 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB2E 55555555 00000020 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bgeu      r2,r3,pc-16
10FFFB16 00000000 00000001 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 00000001 FFFFFFFF 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 FFFFFFFF 00000001 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 80000000 FFFFFFFF 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 7FFFFFFF 80000000 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 12345678 76543210 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 AAAAAAAA 0000001F 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB16 55555555 00000020 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 blt       r2,r3,pc-16
10FFFB36 00000000 00000001 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 00000001 FFFFFFFF 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 80000000 FFFFFFFF 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 7FFFFFFF 80000000 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 12345678 76543210 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 AAAAAAAA 0000001F 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB36 55555555 00000020 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bltu      r2,r3,pc-16
10FFFB26 00000000 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 00000001 FFFFFFFF 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 80000000 FFFFFFFF 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 7FFFFFFF 80000000 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 12345678 76543210 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 AAAAAAAA 0000001F 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB26 55555555 00000020 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 beq       r2,r3,pc-16
10FFFB1E 00000000 00000001 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E 00000001 FFFFFFFF 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E FFFFFFFF 00000001 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E 80000000 FFFFFFFF 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E 7FFFFFFF 80000000 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E 12345678 76543210 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E AAAAAAAA 0000001F 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
10FFFB1E 55555555 00000020 00000010 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 bne       r2,r3,pc-16
003FFF06 12345678 FFFFFFFF 00000020 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 br        pc+0
00004000 12345678 FFFFFFFF 00000400 00000000 00000204 00000304 00000024 00800001 A5000001 5A000001 AA55AA55 call      1024
00004001 12345678 FFFFFFFF 00000400 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 jmpi      1024
103EE83A 12345678 FFFFFFFF 12345678 00000000 00000204 00000304 00000024 00800001 A5000001 5A000001 AA55AA55 callr     r2
F800283A 12345678 FFFFFFFF 00000404 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 ret
1000683A 12345678 FFFFFFFF 12345678 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 jmp       r2
003B69FA 12345678 FFFFFFFF 00000080 00000000 00000024 00000304 00000404 00800000 00800001 5A000001 AA55AA55 trap      7
EF80083A 12345678 FFFFFFFF 00000204 00000000 00000204 00000304 00000404 A5000001 A5000001 5A000001 AA55AA55 eret
003DA0FA 12345678 FFFFFFFF 00000100 00000000 00000204 00000024 00000404 00800000 A5000001 00800001 AA55AA55 break     3
F03C483A 12345678 FFFFFFFF 00000304 00000000 00000204 00000304 00000404 5A000001 A5000001 5A000001 AA55AA55 bret
0009303A FFFFFFFF 00000001 00000024 00800001 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 rdctl     r4,ctl0
0009307A FFFFFFFF 00000001 00000024 A5000001 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 rdctl     r4,ctl1
000930BA FFFFFFFF 00000001 00000024 5A000001 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 rdctl     r4,ctl2
000930FA FFFFFFFF 00000001 00000024 AA55AA55 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 rdctl     r4,ctl3
0009313A FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 rdctl     r4,ctl4
0009317A FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 rdctl     r4,ctl5
1001703A FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 AA55AA55 wrctl     ctl0,r2
1001707A FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 FFFFFFFF 5A000001 AA55AA55 wrctl     ctl1,r2
100170BA FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 FFFFFFFF AA55AA55 wrctl     ctl2,r2
100170FA FFFFFFFF 00000001 00000024 00000000 00000204 00000304 00000404 00800001 A5000001 5A000001 FFFFFFFF wrctl     ctl3,r2
"""
