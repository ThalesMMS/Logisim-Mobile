package dev.logisim.mobile.core

import kotlin.test.*

@OptIn(ExperimentalUnsignedTypes::class)
class Rv32CpuTest {
    private fun memory(vararg words: UInt): SocAddressSpace = SocAddressSpace("bus", listOf(SocMemoryDevice("ram", sizeInBytes = 4096u))).also { bus ->
        words.forEachIndexed { index, instruction -> bus.write(index.toUInt() * 4u, instruction) }
    }
    private fun r(function: Int, function7: Int = 0) = (function7 shl 25 or (2 shl 20) or (1 shl 15) or (function shl 12) or (3 shl 7) or 0x33).toUInt()
    private fun run(instruction: UInt, a: UInt, b: UInt) = Rv32Cpu.step(Rv32CpuState().withRegister(1, a).withRegister(2, b), memory(instruction))

    @Test fun arithmeticBitwiseAndShiftsUse32BitRegisters() {
        val a = 0x80000001u
        val b = 2u
        val expected = listOf(0x80000003u, 4u, 1u, 0u, 0x80000003u, 0x20000000u, 0x80000003u, 0u)
        for (function in 0..7) assertEquals(expected[function], run(r(function), a, b).state.register(3), "function $function")
        assertEquals(0x7FFFFFFFu, run(r(0, 32), a, b).state.register(3))
        assertEquals(0xE0000000u, run(r(5, 32), a, b).state.register(3))
    }

    @Test fun allMultiplyDivideOperationsIncludeSignedHighWords() {
        val expected = listOf(0xFFFFFFFAu, UInt.MAX_VALUE, UInt.MAX_VALUE, 1u, 0xFFFFFFFFu, 0x7FFFFFFEu, 0xFFFFFFFFu, 1u)
        for (function in 0..7) assertEquals(expected[function], run(r(function, 1), 0xFFFFFFFDu, 2u).state.register(3), "M function $function")
        assertEquals(0x80000000u, run(r(4, 1), 0x80000000u, UInt.MAX_VALUE).state.register(3))
        assertEquals(0u, run(r(6, 1), 0x80000000u, UInt.MAX_VALUE).state.register(3))
    }

    @Test fun divisionByZeroHasDefinedIsaResultsRatherThanHostException() {
        for (function in listOf(4, 5)) assertEquals(UInt.MAX_VALUE, run(r(function, 1), 17u, 0u).state.register(3))
        for (function in listOf(6, 7)) assertEquals(17u, run(r(function, 1), 17u, 0u).state.register(3))
    }

    @Test fun programSumsOneThroughFiveAndWritesMmio() {
        // addi x1,x0,5; addi x2,x0,0; add x2,x2,x1; addi x1,x1,-1; bne x1,x0,-8; sw x2,0(x3)
        val bus = memory(0x00500093u, 0x00000113u, 0x00110133u, 0xFFF08093u, 0xFE009CE3u, 0x0021A023u)
        val pio = SocPioDevice("pio", 0x1000u, 8, SocPioDirection.OUTPUT)
        bus.attach(pio)
        var cpu = Rv32CpuState().withRegister(3, 0x1000u)
        repeat(18) { cpu = Rv32Cpu.step(cpu, bus).state }
        assertEquals(15u, pio.outputBits)
        assertEquals(24u, cpu.pc)
        assertEquals(18, cpu.trace.size)
        assertEquals("sw        sp,0(gp)", cpu.trace.first().assembly)
        assertEquals(0u, cpu.register(0))
    }

    @Test fun loadAndStoreWidthsSignExtendCorrectly() {
        val bus = memory(0x00008183u, 0x0000C203u, 0x00209283u, 0x0020D303u, 0x0020A383u)
        bus.write(0x100u, 0x80FF0081u)
        var cpu = Rv32CpuState().withRegister(1, 0x100u)
        repeat(5) { cpu = Rv32Cpu.step(cpu, bus).state }
        assertEquals(0xFFFFFF81u, cpu.register(3))
        assertEquals(0x81u, cpu.register(4))
        assertEquals(0xFFFF80FFu, cpu.register(5))
        assertEquals(0x80FFu, cpu.register(6))
        assertEquals(0x80FF0081u, cpu.register(7))
    }

    @Test fun jumpsWriteReturnAddressAndClearJalrLowBit() {
        val jump = Rv32Cpu.step(Rv32CpuState(), memory(0x008002EFu))
        assertEquals(8u, jump.state.pc); assertEquals(4u, jump.state.register(5))
        val indirect = Rv32Cpu.step(Rv32CpuState().withRegister(1, 0x101u), memory(0x000082E7u))
        assertEquals(0x100u, indirect.state.pc); assertEquals(4u, indirect.state.register(5))
    }

    @Test fun csrAndInterruptExecuteHandlerThenMretRestoresStatus() {
        val bus = memory(0x3000D1F3u)
        bus.write(0x100u, 0x00120213u) // addi x4,x4,1
        bus.write(0x104u, 0x30200073u) // mret
        var cpu = Rv32CpuState().withRegister(4, 10u).copy(csrs = Rv32CpuState().csrs + mapOf(0x305 to 0x100u, 0x304 to 0x800u))
        cpu = Rv32Cpu.step(cpu, bus).state
        assertEquals(1u, cpu.csrs[0x300]) // csrrwi mstatus,1
        cpu = cpu.copy(csrs = cpu.csrs + (0x300 to 8u))
        cpu = Rv32Cpu.step(cpu, bus, true).state
        assertEquals(11u, cpu.register(4)); assertEquals(0x104u, cpu.pc)
        assertEquals(4u, cpu.csrs[0x341]); assertEquals(0x8000000Bu, cpu.csrs[0x342])
        assertEquals(0x80u, cpu.csrs[0x300]); assertEquals(0x800u, cpu.csrs[0x344])
        cpu = Rv32Cpu.step(cpu, bus).state
        assertEquals(4u, cpu.pc); assertEquals(0x88u, cpu.csrs[0x300])
    }

    @Test fun invalidInstructionAndBusErrorsStopAtDesktopAddresses() {
        val invalid = Rv32Cpu.step(Rv32CpuState(), memory(0xFFFFFFFFu))
        assertTrue(invalid.state.stopped); assertEquals(4u, invalid.state.pc); assertNotNull(invalid.error)
        val error = Rv32Cpu.step(Rv32CpuState(pc = 0x2000u), memory())
        assertTrue(error.state.stopped); assertEquals(0x2000u, error.state.pc)
        assertEquals(error.state, Rv32Cpu.step(error.state, memory()).state)
    }

    @Test fun zeroRegisterAndResetMatchDesktopValidity() {
        val cpu = Rv32Cpu.step(Rv32CpuState(), memory(0x00100013u)).state
        assertEquals(0u, cpu.register(0))
        val reset = Rv32Cpu.reset(0x100u, cpu.withRegister(10, 99u))
        assertEquals(99u, reset.register(10)); assertEquals(1u, reset.knownRegisters); assertEquals(0x100u, reset.pc)
    }
    @Test fun environmentUnitPrecedesCsrDecodeLikeDesktop() {
        // The desktop environment unit checks funct12 before the CSR unit and ignores funct3.
        val result = Rv32Cpu.step(Rv32CpuState(), memory(0x00001073u))
        assertEquals("ecall", result.trace?.assembly)
        assertFalse(result.state.stopped)
        assertEquals(4u, result.state.pc)
        val unsupported = Rv32Cpu.step(Rv32CpuState(), memory(0x777010F3u))
        assertTrue(unsupported.state.stopped)
        assertEquals(0u, unsupported.state.pc)
        assertFalse(0x777 in unsupported.state.csrs)
    }


    @Test fun traceTextMatchesEvolutionGetAsmInstruction() {
        // Rows of Evolution d58a61e0's trace window (scripts/oracle/soc-debugger sweeps 48k words).
        for ((word, text) in listOf(0x00000093u to "li        ra,0", 0x002080B3u to "add       ra,ra,sp",
                0xFE314CE3u to "blt       sp,gp,pc-8", 0x00000013u to "nop       ", 0x00008067u to "ret       ",
                0x00001237u to "lui       tp,1", 0x00122023u to "sw        ra,0(tp)", 0x10002283u to "lw        t0,256(zero)",
                0x30529073u to "csrw      mtvec,t0", 0x0FF0000Fu to "fence     iorw,iorw", 0x00000073u to "ecall")) {
            assertEquals(text, Rv32Disassembler.format(word), word.toString(16))
        }
        assertEquals(null, Rv32Disassembler.format(0xFFFFFFFFu))
    }
}
